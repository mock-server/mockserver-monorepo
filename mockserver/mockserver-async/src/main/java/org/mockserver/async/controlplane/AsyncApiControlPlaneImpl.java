package org.mockserver.async.controlplane;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.mockserver.async.AsyncApiControlPlane;
import org.mockserver.async.AsyncApiControlPlaneRegistry;
import org.mockserver.async.AsyncApiMockOrchestrator;
import org.mockserver.async.MessageExampleGenerator;
import org.mockserver.async.asyncapi.AsyncApiChannel;
import org.mockserver.async.asyncapi.AsyncApiMessage;
import org.mockserver.async.asyncapi.AsyncApiParser;
import org.mockserver.async.asyncapi.AsyncApiSpec;
import org.mockserver.async.publish.AmqpMessagePublisher;
import org.mockserver.async.publish.KafkaAvroMessagePublisher;
import org.mockserver.async.publish.KafkaMessagePublisher;
import org.mockserver.async.publish.MessagePublisher;
import org.mockserver.async.publish.Mqtt5MessagePublisher;
import org.mockserver.async.publish.MqttMessagePublisher;
import org.mockserver.async.serde.SchemaRegistryClient;
import org.mockserver.async.security.KafkaSecurity;
import org.mockserver.async.security.MqttSecurity;
import org.mockserver.async.subscribe.AmqpMessageSubscriber;
import org.mockserver.async.subscribe.KafkaAvroMessageSubscriber;
import org.mockserver.async.subscribe.KafkaMessageSubscriber;
import org.mockserver.async.subscribe.MessageSubscriber;
import org.mockserver.async.subscribe.Mqtt5MessageSubscriber;
import org.mockserver.async.subscribe.MqttMessageSubscriber;
import org.mockserver.async.subscribe.RecordedMessage;
import org.mockserver.async.validation.AsyncApiSchemaValidator;
import org.mockserver.configuration.Configuration;
import org.mockserver.configuration.ConfigurationProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Implementation of {@link AsyncApiControlPlane} that lives in the mockserver-async
 * module and is registered into the core's {@link AsyncApiControlPlaneRegistry}
 * at server startup.
 * <p>
 * Handles:
 * <ul>
 *   <li>Loading AsyncAPI specs via the REST control-plane</li>
 *   <li>Creating publishers and subscribers for each channel</li>
 *   <li>Schema validation of generated and consumed messages</li>
 *   <li>Returning status including recorded messages</li>
 *   <li>Resetting all state on server reset</li>
 * </ul>
 */
public class AsyncApiControlPlaneImpl implements AsyncApiControlPlane {

    private static final Logger LOG = LoggerFactory.getLogger(AsyncApiControlPlaneImpl.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final int MAX_LOG_PAYLOAD_LENGTH = 100;

    /**
     * Maximum number of validation issue records retained. Prevents unbounded
     * memory growth if many channels produce schema-invalid examples.
     */
    static final int MAX_VALIDATION_ISSUES = 1000;

    /**
     * How long {@link #reset()}, and so server {@code stop()}, waits for the brokers it closes. The same
     * budget the orchestrator and the Kafka subscribers give their own shutdown, and well inside the 30 s
     * after which {@code stop()} gives up.
     */
    static final Duration CLOSE_WAIT = Duration.ofSeconds(5);

    /**
     * How long {@link #load(String)} waits for brokers still closing before it connects anyway; matches the 30 s
     * {@code stop()} allows a whole server. A close still running after that is stuck, not slow.
     */
    static final Duration EARLIER_CLOSE_WAIT = Duration.ofSeconds(30);

    static final String CLOSER_THREAD_NAME = "MockServer-AsyncAPI-broker-close";

    // across every control plane in the JVM: a restarted server registers a new one
    private static final Set<Teardown> CLOSING = ConcurrentHashMap.newKeySet();

    private final AsyncApiParser parser = new AsyncApiParser();
    private final MessageExampleGenerator generator = new MessageExampleGenerator();
    private final AsyncApiSchemaValidator schemaValidator = new AsyncApiSchemaValidator();

    /**
     * The server's {@link Configuration} instance, or {@code null} when this control-plane
     * was created without one. When present it is consulted first for the async broker
     * defaults so that values set on the instance — including via
     * {@code PUT /mockserver/configuration} — are honoured; {@link Configuration} itself
     * falls back to {@link ConfigurationProperties} when its own field is unset.
     */
    private final Configuration configuration;
    private final Duration closeWait;
    private final Duration earlierCloseWait;

    // Active state
    private volatile AsyncApiSpec loadedSpec;
    private volatile BrokerConfig activeBrokerConfig;
    private final List<AsyncApiMockOrchestrator> activeOrchestrators = new CopyOnWriteArrayList<>();
    private final List<MessagePublisher> activePublishers = new CopyOnWriteArrayList<>();
    private final List<MessageSubscriber> activeSubscribers = new CopyOnWriteArrayList<>();
    private final List<SchemaValidationRecord> validationIssues = new CopyOnWriteArrayList<>();
    // guarded by this; lets a load that waited for earlier closes see that a reset or stop happened meanwhile
    private long resets;

    /**
     * Create a control-plane with no {@link Configuration} instance, falling back entirely
     * to the static {@link ConfigurationProperties} store for async broker defaults.
     */
    public AsyncApiControlPlaneImpl() {
        this(null);
    }

    /**
     * Create a control-plane bound to the server's {@link Configuration} instance so that
     * instance-set async broker defaults are honoured.
     *
     * @param configuration the server configuration, or {@code null} to use only the static store
     */
    public AsyncApiControlPlaneImpl(Configuration configuration) {
        this(configuration, CLOSE_WAIT, EARLIER_CLOSE_WAIT);
    }

    AsyncApiControlPlaneImpl(Configuration configuration, Duration closeWait, Duration earlierCloseWait) {
        this.configuration = configuration;
        this.closeWait = closeWait;
        this.earlierCloseWait = earlierCloseWait;
    }

    /**
     * Register this implementation into the core registry, without a {@link Configuration}
     * instance. Prefer {@link #registerIfAvailable(Configuration)} so that instance-set
     * configuration is honoured.
     */
    public static void registerIfAvailable() {
        registerIfAvailable(null);
    }

    /**
     * Register this implementation into the core registry.
     * Call at server startup (e.g. from MockServerLifeCycle or Main).
     *
     * @param configuration the server configuration, or {@code null} to use only the static store
     */
    public static void registerIfAvailable(Configuration configuration) {
        try {
            AsyncApiControlPlaneImpl impl = new AsyncApiControlPlaneImpl(configuration);
            AsyncApiControlPlaneRegistry.getInstance().register(impl);
            LOG.info("AsyncAPI control-plane registered");
        } catch (Exception e) {
            LOG.debug("AsyncAPI control-plane not available: {}", e.getMessage());
        }
    }

    /**
     * Replaces whatever is loaded. The brokers it replaces, and any an earlier reset or a stopped server left closing,
     * close on daemon threads, and the load waits for them without holding the monitor, so a reset, stop, status or
     * verify meanwhile is not held up: a broker still closing can share the new brokers' client ids (MQTT disconnects
     * the older session of a client id). After {@link #EARLIER_CLOSE_WAIT} it logs a warning and connects anyway.
     * A reset or stop while it waits cancels it.
     */
    @Override
    public JsonNode load(String requestBody) {
        long deadline = System.nanoTime() + earlierCloseWait.toNanos();
        Long resetsAtStart = null;
        boolean gaveUp = false;
        try {
            while (true) {
                Teardown replaced;
                synchronized (this) {
                    if (resetsAtStart == null) {
                        resetsAtStart = resets;
                    } else if (resets != resetsAtStart) {
                        throw new IllegalStateException("a reset or stop happened while this load waited for earlier broker connections to close");
                    }
                    replaced = detach().registerClosing();
                    if (gaveUp || (replaced.isEmpty() && CLOSING.isEmpty())) {
                        replaced.startClosing();
                        return loadHoldingMonitor(requestBody);
                    }
                }
                replaced.startClosing();
                if (!awaitEarlierCloses(deadline)) {
                    // broker clients bind nothing an old one still holds, so a stuck close must not block every later load
                    gaveUp = true;
                    LOG.warn("AsyncAPI broker connections from an earlier reset, stop or load still closing after {}s;"
                        + " connecting the new ones anyway", earlierCloseWait.getSeconds());
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Failed to load AsyncAPI spec: " + e.getMessage(), e);
        } catch (IllegalStateException e) {
            throw new RuntimeException("Failed to load AsyncAPI spec: " + e.getMessage(), e);
        }
    }

    private JsonNode loadHoldingMonitor(String requestBody) {
        try {
            // Parse the request body: either a plain spec or a wrapper
            String specContent;
            BrokerConfig brokerConfig;

            JsonNode bodyNode = tryParseJson(requestBody);
            if (bodyNode != null && bodyNode.has("spec")) {
                specContent = bodyNode.get("spec").isTextual()
                    ? bodyNode.get("spec").asText()
                    : MAPPER.writeValueAsString(bodyNode.get("spec"));
                brokerConfig = parseBrokerConfig(bodyNode.get("brokerConfig"));
            } else {
                // Plain spec
                specContent = requestBody;
                brokerConfig = BrokerConfig.defaultConfig();
            }

            AsyncApiSpec spec = parser.parse(specContent);
            this.loadedSpec = spec;
            this.activeBrokerConfig = brokerConfig;

            // Create publishers and subscribers for each channel
            for (AsyncApiChannel channel : spec.getChannels()) {
                // Validate generated examples against schema
                String example = generator.generateExample(channel);
                if (channel.getPayloadSchema() != null) {
                    AsyncApiSchemaValidator.ValidationResult result = schemaValidator.validate(example, channel.getPayloadSchema());
                    if (!result.isValid()) {
                        addValidationIssue(new SchemaValidationRecord(
                            channel.getName(), "generated_example", result.getErrors()));
                        LOG.warn("Generated example for channel '{}' does not conform to schema: {}",
                            channel.getName(), truncate(String.valueOf(result.getErrors())));
                    }
                }

                // Validate the first spec-provided example of each message against its schema
                validateFirstMessageExamples(channel);
            }

            // Create broker connections based on config
            createBrokerConnections(spec, brokerConfig);

            // Build response
            return buildLoadResponse(spec);

        } catch (IllegalArgumentException | com.fasterxml.jackson.core.JsonProcessingException e) {
            // a spec or broker configuration the caller can correct
            detach().registerClosing().startClosing();
            throw new IllegalArgumentException("Failed to load AsyncAPI spec: " + e.getMessage(), e);
        } catch (Exception e) {
            // Clean up any partially-created brokers on failure; a later load waits for them to close
            detach().registerClosing().startClosing();
            throw new RuntimeException("Failed to load AsyncAPI spec: " + e.getMessage(), e);
        }
    }

    /**
     * The maximum number of recorded messages retained per channel, read live on each
     * {@code load()} so a value set on the {@link Configuration} instance — including via
     * {@code PUT /mockserver/configuration} — takes effect on the next load.
     */
    int recordedMessageMaxEntries() {
        return configuration != null
            ? configuration.asyncRecordedMessageMaxEntries()
            : ConfigurationProperties.asyncRecordedMessageMaxEntries();
    }

    /**
     * Create publisher/subscriber connections. Extracted so partial-failure cleanup
     * is handled by the caller's catch block, which takes them out and closes them.
     */
    private void createBrokerConnections(AsyncApiSpec spec, BrokerConfig brokerConfig) {
        int maxRecordedMessages = recordedMessageMaxEntries();

        if (brokerConfig.kafkaBootstrapServers != null) {
            boolean avro = "avro".equals(brokerConfig.kafkaValueFormat);
            SchemaRegistryClient registryClient = (avro && brokerConfig.kafkaSchemaRegistryUrl != null)
                ? new SchemaRegistryClient(brokerConfig.kafkaSchemaRegistryUrl) : null;

            MessagePublisher publisher = avro
                ? new KafkaAvroMessagePublisher(brokerConfig.kafkaBootstrapServers, brokerConfig.kafkaSecurity,
                    brokerConfig.avroSchema, registryClient, brokerConfig.avroSchemaId)
                : new KafkaMessagePublisher(brokerConfig.kafkaBootstrapServers, brokerConfig.kafkaSecurity);
            activePublishers.add(publisher);

            AsyncApiMockOrchestrator orchestrator = new AsyncApiMockOrchestrator(spec, publisher, generator);
            activeOrchestrators.add(orchestrator);

            // One-shot publish
            if (brokerConfig.publishOnLoad) {
                publishOnLoadSurvivingFailure(orchestrator, "kafka");
            }
            // Scheduled publish
            if (brokerConfig.publishIntervalMillis > 0) {
                orchestrator.startPublishing(brokerConfig.publishIntervalMillis);
            }

            // Create subscriber if consume is enabled
            if (brokerConfig.consume) {
                String groupId = brokerConfig.kafkaGroupId != null
                    ? brokerConfig.kafkaGroupId : "mockserver-async-consumer";
                MessageSubscriber subscriber = avro
                    ? new KafkaAvroMessageSubscriber(brokerConfig.kafkaBootstrapServers, groupId,
                        maxRecordedMessages, brokerConfig.kafkaSecurity, registryClient,
                        brokerConfig.avroSchema, brokerConfig.avroSchemaId)
                    : new KafkaMessageSubscriber(brokerConfig.kafkaBootstrapServers, groupId,
                        maxRecordedMessages, brokerConfig.kafkaSecurity);
                activeSubscribers.add(subscriber);
                for (AsyncApiChannel channel : spec.getChannels()) {
                    subscriber.subscribe(channel.getName());
                }
            }
        }

        if (brokerConfig.amqpUri != null) {
            MessagePublisher publisher = new AmqpMessagePublisher(brokerConfig.amqpUri, spec);
            activePublishers.add(publisher);

            AsyncApiMockOrchestrator orchestrator = new AsyncApiMockOrchestrator(spec, publisher, generator);
            activeOrchestrators.add(orchestrator);

            if (brokerConfig.publishOnLoad) {
                publishOnLoadSurvivingFailure(orchestrator, "amqp");
            }
            if (brokerConfig.publishIntervalMillis > 0) {
                orchestrator.startPublishing(brokerConfig.publishIntervalMillis);
            }

            // Create subscriber if consume is enabled
            if (brokerConfig.consume) {
                AmqpMessageSubscriber subscriber = new AmqpMessageSubscriber(
                    brokerConfig.amqpUri, spec, maxRecordedMessages);
                activeSubscribers.add(subscriber);
                for (AsyncApiChannel channel : spec.getChannels()) {
                    subscriber.subscribe(channel.getName());
                }
            }
        }

        if (brokerConfig.mqttBrokerUrl != null) {
            String pubClientId = brokerConfig.mqttClientId != null
                ? brokerConfig.mqttClientId + "-pub" : "mockserver-mqtt-pub";
            int qos = brokerConfig.mqttQos >= 0 ? brokerConfig.mqttQos : 1;
            boolean mqtt5 = brokerConfig.mqttProtocolVersion == 5;
            MessagePublisher publisher = mqtt5
                ? new Mqtt5MessagePublisher(brokerConfig.mqttBrokerUrl, pubClientId, qos, brokerConfig.mqttSecurity)
                : new MqttMessagePublisher(brokerConfig.mqttBrokerUrl, pubClientId, qos, brokerConfig.mqttSecurity);
            activePublishers.add(publisher);

            AsyncApiMockOrchestrator orchestrator = new AsyncApiMockOrchestrator(spec, publisher, generator);
            activeOrchestrators.add(orchestrator);

            if (brokerConfig.publishOnLoad) {
                publishOnLoadSurvivingFailure(orchestrator, "mqtt");
            }
            if (brokerConfig.publishIntervalMillis > 0) {
                orchestrator.startPublishing(brokerConfig.publishIntervalMillis);
            }

            // Create subscriber if consume is enabled
            if (brokerConfig.consume) {
                String subClientId = brokerConfig.mqttClientId != null
                    ? brokerConfig.mqttClientId + "-sub" : "mockserver-mqtt-sub";
                MessageSubscriber subscriber = mqtt5
                    ? new Mqtt5MessageSubscriber(brokerConfig.mqttBrokerUrl, subClientId, qos,
                        maxRecordedMessages, brokerConfig.mqttSecurity)
                    : new MqttMessageSubscriber(brokerConfig.mqttBrokerUrl, subClientId, qos,
                        maxRecordedMessages, brokerConfig.mqttSecurity);
                activeSubscribers.add(subscriber);
                for (AsyncApiChannel channel : spec.getChannels()) {
                    subscriber.subscribe(channel.getName());
                }
            }
        }
    }

    @Override
    public synchronized JsonNode status() {
        ObjectNode result = MAPPER.createObjectNode();

        if (loadedSpec == null) {
            result.put("loaded", false);
            result.putArray("channels");
            result.put("publishers", 0);
            result.put("subscribers", 0);
            result.putArray("recordedMessages");
            return result;
        }

        result.put("loaded", true);
        result.put("specTitle", loadedSpec.getTitle());
        result.put("specVersion", loadedSpec.getAsyncApiVersion());

        ArrayNode channelsArray = result.putArray("channels");
        for (AsyncApiChannel channel : loadedSpec.getChannels()) {
            ObjectNode channelNode = MAPPER.createObjectNode();
            channelNode.put("name", channel.getName());
            channelNode.put("hasSchema", channel.getPayloadSchema() != null);
            channelNode.put("exampleCount", channel.getPayloadExamples().size());
            channelsArray.add(channelNode);
        }

        result.put("publishers", activePublishers.size());
        result.put("subscribers", activeSubscribers.size());
        putLastPublishFailure(result);

        // Recorded messages from subscribers
        ArrayNode recordedArray = result.putArray("recordedMessages");
        for (MessageSubscriber subscriber : activeSubscribers) {
            for (RecordedMessage msg : subscriber.getAllRecordedMessages()) {
                ObjectNode msgNode = MAPPER.createObjectNode();
                msgNode.put("channel", msg.getChannel());
                if (msg.getKey() != null) {
                    msgNode.put("key", msg.getKey());
                }
                msgNode.put("payload", msg.getPayload());
                if (!msg.getHeaders().isEmpty()) {
                    ObjectNode headersNode = msgNode.putObject("headers");
                    msg.getHeaders().forEach(headersNode::put);
                }
                msgNode.put("timestamp", msg.getTimestamp().toString());

                // Validate recorded message against schema
                AsyncApiChannel matchingChannel = findChannel(msg.getChannel());
                if (matchingChannel != null && matchingChannel.getPayloadSchema() != null) {
                    AsyncApiSchemaValidator.ValidationResult validationResult =
                        schemaValidator.validate(msg.getPayload(), matchingChannel.getPayloadSchema());
                    msgNode.put("schemaValid", validationResult.isValid());
                    if (!validationResult.isValid()) {
                        msgNode.put("schemaErrors", validationResult.getErrors());
                    }
                }

                recordedArray.add(msgNode);
            }
        }

        // Validation issues from example generation
        if (!validationIssues.isEmpty()) {
            ArrayNode issuesArray = result.putArray("validationIssues");
            for (SchemaValidationRecord issue : validationIssues) {
                ObjectNode issueNode = MAPPER.createObjectNode();
                issueNode.put("channel", issue.channel);
                issueNode.put("context", issue.context);
                issueNode.put("errors", issue.errors);
                issuesArray.add(issueNode);
            }
        }

        return result;
    }

    @Override
    public synchronized String verify(String verificationJson) {
        if (verificationJson == null || verificationJson.isBlank()) {
            throw new IllegalArgumentException("verification request body must not be empty");
        }

        JsonNode request;
        try {
            request = MAPPER.readTree(verificationJson);
        } catch (IOException e) {
            throw new IllegalArgumentException("invalid JSON in verification request: " + e.getMessage(), e);
        }

        if (request == null || !request.has("channel")) {
            throw new IllegalArgumentException("verification request must contain a 'channel' field");
        }
        rejectUnknownFields(request);

        String channel = request.get("channel").asText();
        String payloadSubstring = textOrNull(request, "payloadSubstring");
        String payloadJsonPath = textOrNull(request, "payloadJsonPath");
        String expectedValue = textOrNull(request, "expectedValue");

        // Parse count semantics — default is atLeast: 1
        int atLeast = -1;
        int atMost = -1;
        int exactly = -1;
        JsonNode countNode = request.get("count");
        if (countNode != null && countNode.isObject()) {
            if (countNode.has("atLeast")) {
                atLeast = countNode.get("atLeast").asInt();
            }
            if (countNode.has("atMost")) {
                atMost = countNode.get("atMost").asInt();
            }
            if (countNode.has("exactly")) {
                exactly = countNode.get("exactly").asInt();
            }
        }
        // Default: atLeast 1 when no count specified
        if (atLeast < 0 && atMost < 0 && exactly < 0) {
            atLeast = 1;
        }

        // Collect matching messages from all active subscribers
        int matchingCount = 0;
        for (MessageSubscriber subscriber : activeSubscribers) {
            for (RecordedMessage msg : subscriber.getRecordedMessages(channel)) {
                if (matchesPayloadCriteria(msg, payloadSubstring, payloadJsonPath, expectedValue)) {
                    matchingCount++;
                }
            }
        }

        // Check count constraints
        return checkCount(channel, matchingCount, atLeast, atMost, exactly,
            payloadSubstring, payloadJsonPath, expectedValue);
    }

    private static final List<String> VERIFY_FIELDS = List.of("channel", "payloadSubstring", "payloadJsonPath", "expectedValue", "count");
    private static final List<String> COUNT_FIELDS = List.of("atLeast", "atMost", "exactly");

    /**
     * A misplaced or misspelt field would otherwise be ignored, and the verification would quietly
     * check the default "at least one message" instead of what the caller asked for.
     */
    private static void rejectUnknownFields(JsonNode request) {
        for (Iterator<String> names = request.fieldNames(); names.hasNext(); ) {
            String name = names.next();
            if (COUNT_FIELDS.contains(name)) {
                throw new IllegalArgumentException("'" + name + "' must be inside \"count\", e.g. {\"channel\":\"orders\",\"count\":{\"" + name + "\":1}}");
            }
            if (!VERIFY_FIELDS.contains(name)) {
                throw new IllegalArgumentException("unknown field '" + name + "' in verification request; valid fields are " + VERIFY_FIELDS);
            }
        }
        JsonNode count = request.get("count");
        if (count == null || count.isNull()) {
            return;
        }
        if (!count.isObject()) {
            throw new IllegalArgumentException("\"count\" must be an object with atLeast, atMost or exactly");
        }
        for (Iterator<String> names = count.fieldNames(); names.hasNext(); ) {
            String name = names.next();
            if (!COUNT_FIELDS.contains(name)) {
                throw new IllegalArgumentException("unknown field '" + name + "' in \"count\"; valid fields are " + COUNT_FIELDS);
            }
            JsonNode value = count.get(name);
            if (!value.isIntegralNumber() || !value.canConvertToInt() || value.asInt() < 0) {
                throw new IllegalArgumentException("\"count\"." + name + " must be a whole number of at least 0");
            }
        }
    }

    private boolean matchesPayloadCriteria(RecordedMessage msg, String payloadSubstring,
                                           String payloadJsonPath, String expectedValue) {
        String payload = msg.getPayload();
        if (payload == null) {
            payload = "";
        }

        // Substring match
        if (payloadSubstring != null && !payload.contains(payloadSubstring)) {
            return false;
        }

        // JSON path match (simple dot-notation extraction)
        if (payloadJsonPath != null && expectedValue != null) {
            String actualValue = extractJsonPath(payload, payloadJsonPath);
            if (!expectedValue.equals(actualValue)) {
                return false;
            }
        }

        return true;
    }

    /**
     * Simple dot-notation JSON path extractor (e.g. "user.name" extracts from
     * {@code {"user":{"name":"Alice"}}}). Handles string, number, boolean, and null values.
     * Returns {@code null} if the path does not resolve.
     */
    private String extractJsonPath(String payload, String path) {
        try {
            JsonNode node = MAPPER.readTree(payload);
            for (String segment : path.split("\\.")) {
                if (node == null || !node.has(segment)) {
                    return null;
                }
                node = node.get(segment);
            }
            if (node == null || node.isNull()) {
                return null;
            }
            return node.isTextual() ? node.asText() : node.toString();
        } catch (IOException e) {
            return null;
        }
    }

    private String checkCount(String channel, int actual, int atLeast, int atMost, int exactly,
                              String payloadSubstring, String payloadJsonPath, String expectedValue) {
        StringBuilder criteria = new StringBuilder();
        criteria.append("channel '").append(channel).append("'");
        if (payloadSubstring != null) {
            criteria.append(" with payload containing '").append(payloadSubstring).append("'");
        }
        if (payloadJsonPath != null && expectedValue != null) {
            criteria.append(" with ").append(payloadJsonPath).append("='").append(expectedValue).append("'");
        }

        if (exactly >= 0) {
            if (actual != exactly) {
                return "expected exactly " + exactly + " message(s) matching " + criteria
                    + " but found " + actual;
            }
        }
        if (atLeast >= 0) {
            if (actual < atLeast) {
                return "expected at least " + atLeast + " message(s) matching " + criteria
                    + " but found " + actual;
            }
        }
        if (atMost >= 0) {
            if (actual > atMost) {
                return "expected at most " + atMost + " message(s) matching " + criteria
                    + " but found " + actual;
            }
        }
        return null; // verification passed
    }

    @Override
    public String generateHttpExpectations(String requestBody) {
        return new AsyncApiHttpExpectationGenerator().generateSerialized(requestBody);
    }

    /**
     * Takes the brokers out of the control plane and closes them on a daemon thread, waiting up to
     * {@link #CLOSE_WAIT} for that before returning; closes still running then finish in the background.
     * Server {@code stop()} runs this: closing can log (the orchestrator and the broker clients do), console
     * logging is synchronous, and a broker can be slow to close, so neither a blocked console nor a slow
     * broker may hold {@code stop()}, or {@code load}/{@code status}/{@code verify} behind the monitor.
     */
    @Override
    public void reset() {
        Teardown teardown;
        synchronized (this) {
            resets++;
            // registered under the monitor, so a load that takes it next waits for these closes
            teardown = detach().registerClosing();
        }
        teardown.closeInBackground(closeWait);
    }

    /**
     * Must be called without holding this control plane's monitor: waits for the closes registered so far, on every
     * control plane in the JVM (a restarted server registers a new one), until the deadline.
     *
     * @return false if a close was still running at the deadline
     */
    private boolean awaitEarlierCloses(long deadline) throws InterruptedException {
        for (Teardown closing : CLOSING) {
            if (!closing.closed.await(Math.max(0, deadline - System.nanoTime()), TimeUnit.NANOSECONDS)) {
                return false;
            }
        }
        return true;
    }

    /**
     * Package-private: the number of broker closes still running, across every control plane in this JVM.
     */
    static int closesInProgress() {
        return CLOSING.size();
    }

    /**
     * Must be called holding this control plane's monitor: takes the active brokers out of the
     * control plane, so a later load starts clean, and returns them for closing.
     */
    private Teardown detach() {
        Teardown teardown = new Teardown(new ArrayList<>(activeOrchestrators), new ArrayList<>(activePublishers), new ArrayList<>(activeSubscribers));
        activeOrchestrators.clear();
        activePublishers.clear();
        activeSubscribers.clear();
        validationIssues.clear();
        loadedSpec = null;
        activeBrokerConfig = null;
        return teardown;
    }

    private static final class Teardown {

        private final List<AsyncApiMockOrchestrator> orchestrators;
        private final List<MessagePublisher> publishers;
        private final List<MessageSubscriber> subscribers;

        private final CountDownLatch closed = new CountDownLatch(1);

        private Teardown(List<AsyncApiMockOrchestrator> orchestrators, List<MessagePublisher> publishers, List<MessageSubscriber> subscribers) {
            this.orchestrators = orchestrators;
            this.publishers = publishers;
            this.subscribers = subscribers;
        }

        private boolean isEmpty() {
            return orchestrators.isEmpty() && publishers.isEmpty() && subscribers.isEmpty();
        }

        private Teardown registerClosing() {
            if (!isEmpty()) {
                CLOSING.add(this);
            }
            return this;
        }

        private void closeInBackground(Duration wait) {
            if (!startClosing()) {
                return;
            }
            try {
                if (!closed.await(wait.toMillis(), TimeUnit.MILLISECONDS)) {
                    // from a thread of its own: with stdout blocked, logging here would hold stop() after all
                    Thread notice = new Thread(() -> LOG.warn("AsyncAPI broker connections still closing after {}s;"
                        + " the reset or stop goes ahead and they finish closing in the background", wait.getSeconds()), CLOSER_THREAD_NAME + " notice");
                    notice.setDaemon(true);
                    notice.start();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        /**
         * Closes on a daemon thread, or on the caller's if one cannot be started.
         *
         * @return false when there was nothing to close
         */
        private boolean startClosing() {
            if (isEmpty()) {
                return false;
            }
            boolean started = false;
            try {
                Thread closer = new Thread(this::close, CLOSER_THREAD_NAME);
                closer.setDaemon(true);
                closer.start();
                started = true;
            } finally {
                if (!started) {
                    close();
                }
            }
            return true;
        }

        private void close() {
            try {
                closeAll();
            } finally {
                CLOSING.remove(this);
                closed.countDown();
            }
        }

        // orchestrators first: they publish through the publishers closed after them
        private void closeAll() {
            for (AsyncApiMockOrchestrator orchestrator : orchestrators) {
                try {
                    orchestrator.stop();
                } catch (Exception e) {
                    LOG.warn("Error stopping orchestrator: {}", e.getMessage());
                }
            }
            for (MessagePublisher publisher : publishers) {
                try {
                    publisher.close();
                } catch (Exception e) {
                    LOG.warn("Error closing publisher: {}", e.getMessage());
                }
            }
            for (MessageSubscriber subscriber : subscribers) {
                try {
                    subscriber.close();
                } catch (Exception e) {
                    LOG.warn("Error closing subscriber: {}", e.getMessage());
                }
            }
        }
    }

    /**
     * Package-private: add a subscriber for testing purposes only.
     * Allows unit tests to inject mock/stub subscribers without needing a real broker.
     */
    void addSubscriberForTesting(MessageSubscriber subscriber) {
        activeSubscribers.add(subscriber);
    }

    /**
     * Validate the first spec-provided payload example of each message in a channel
     * against that message's payload schema. Skips messages that have no schema or
     * no explicit examples. Issues are surfaced as warning logs and validation records
     * in the load response — matching the existing convention for generated-example
     * validation — without hard-failing the load.
     * <p>
     * Scope: first example per message only (catches malformed spec examples early).
     */
    private void validateFirstMessageExamples(AsyncApiChannel channel) {
        for (AsyncApiMessage message : channel.getMessages()) {
            JsonNode schema = message.getPayloadSchema();
            if (schema == null) {
                continue;
            }
            List<JsonNode> examples = message.getPayloadExamples();
            if (examples.isEmpty()) {
                continue;
            }
            // Validate the first example only
            JsonNode firstExample = examples.get(0);
            String examplePayload;
            try {
                examplePayload = MAPPER.writeValueAsString(firstExample);
            } catch (Exception e) {
                LOG.warn("Failed to serialize first message example for channel '{}' message '{}': {}",
                    channel.getName(), message.getName(), e.getMessage());
                continue;
            }
            AsyncApiSchemaValidator.ValidationResult result = schemaValidator.validate(examplePayload, schema);
            if (!result.isValid()) {
                String context = message.getName() != null
                    ? "first_message_example:" + message.getName()
                    : "first_message_example";
                addValidationIssue(new SchemaValidationRecord(
                    channel.getName(), context, result.getErrors()));
                LOG.warn("First example for channel '{}' message '{}' does not conform to schema: {}",
                    channel.getName(), message.getName(), truncate(String.valueOf(result.getErrors())));
            }
        }
    }

    /**
     * Run the load-time one-shot publish, recording any failure as a validation issue instead of
     * letting it fail the spec load.
     * <p>
     * This call sits inside the {@code try} whose {@code catch} takes out and closes every broker, so
     * an escaping exception would tear down every publisher, subscriber and orchestrator across
     * <em>all</em> brokers and fail {@code PUT /mockserver/asyncapi} outright. Since
     * {@code publishOnLoad} defaults to true, that would make the default path for an
     * exchange-routed AMQP channel with no queue yet bound a total rollback — a bootstrap
     * deadlock, because the consumer that binds the queue cannot start until the mock is up.
     * The broker connections themselves are already established and valid at this point; only the
     * first publish failed, so the mock stays loaded and the failure is reported in the load
     * response under {@code validationIssues}.
     */
    private void publishOnLoadSurvivingFailure(AsyncApiMockOrchestrator orchestrator, String broker) {
        try {
            orchestrator.publishAll();
        } catch (Exception e) {
            String detail = (e.getMessage() != null && !e.getMessage().isBlank())
                ? e.getMessage() : e.getClass().getSimpleName();
            // attribute to the broker whose publish failed — "*" would be ambiguous once a spec
            // is loaded against more than one broker
            addValidationIssue(new SchemaValidationRecord(broker, "publish_on_load", detail));
            LOG.warn("Load-time publish to {} failed; the mock remains loaded and later publishes "
                + "can still succeed once the cause is resolved: {}", broker, detail, e);
        }
    }

    /**
     * Report the most recent scheduled-publish failure, if any, so a mock that has stopped
     * producing messages can be diagnosed from the status response rather than from the log.
     */
    private void putLastPublishFailure(ObjectNode result) {
        for (AsyncApiMockOrchestrator orchestrator : activeOrchestrators) {
            String failure = orchestrator.getLastPublishFailure();
            if (failure != null) {
                result.put("lastPublishFailure", failure);
                return;
            }
        }
    }

    /**
     * Add a validation issue, enforcing the bounded cap.
     */
    private void addValidationIssue(SchemaValidationRecord record) {
        // CopyOnWriteArrayList size check + add is not atomic, but for a cap this
        // is fine — at worst we overshoot by a small number under concurrency
        while (validationIssues.size() >= MAX_VALIDATION_ISSUES) {
            validationIssues.remove(0);
        }
        validationIssues.add(record);
    }

    private JsonNode buildLoadResponse(AsyncApiSpec spec) {
        ObjectNode result = MAPPER.createObjectNode();
        result.put("loaded", true);
        result.put("specTitle", spec.getTitle());
        result.put("specVersion", spec.getAsyncApiVersion());
        result.put("channelCount", spec.getChannels().size());

        ArrayNode channelsArray = result.putArray("channels");
        for (AsyncApiChannel channel : spec.getChannels()) {
            ObjectNode channelNode = MAPPER.createObjectNode();
            channelNode.put("name", channel.getName());
            channelNode.put("hasSchema", channel.getPayloadSchema() != null);
            channelsArray.add(channelNode);
        }

        result.put("publishers", activePublishers.size());
        result.put("subscribers", activeSubscribers.size());
        putLastPublishFailure(result);

        if (!validationIssues.isEmpty()) {
            ArrayNode issuesArray = result.putArray("validationIssues");
            for (SchemaValidationRecord issue : validationIssues) {
                ObjectNode issueNode = MAPPER.createObjectNode();
                issueNode.put("channel", issue.channel);
                issueNode.put("context", issue.context);
                issueNode.put("errors", issue.errors);
                issuesArray.add(issueNode);
            }
        }

        return result;
    }

    private JsonNode tryParseJson(String text) {
        if (text == null) {
            return null;
        }
        String trimmed = text.trim();
        if (!trimmed.startsWith("{")) {
            return null;
        }
        try {
            return MAPPER.readTree(trimmed);
        } catch (IOException e) {
            return null;
        }
    }

    /**
     * Parse the broker configuration from the request body JSON. Accessible
     * at package level for unit testing.
     */
    BrokerConfig parseBrokerConfig(JsonNode node) {
        BrokerConfig config = new BrokerConfig();
        if (node != null) {
            // a JAAS configuration misplaced outside kafkaSecurity is refused too, never silently dropped
            node.fieldNames().forEachRemaining(name -> {
                if (name.toLowerCase(Locale.ROOT).contains("jaas") && !node.get(name).isNull()) {
                    LOG.warn("Refused PUT /mockserver/asyncapi: {}", SASL_JAAS_CONFIG_STARTUP_ONLY);
                    throw new IllegalArgumentException(SASL_JAAS_CONFIG_STARTUP_ONLY);
                }
            });
            config.kafkaBootstrapServers = textOrNull(node, "kafkaBootstrapServers");
            config.mqttBrokerUrl = textOrNull(node, "mqttBrokerUrl");
            config.amqpUri = textOrNull(node, "amqpUri");
            config.mqttClientId = textOrNull(node, "mqttClientId");
            config.kafkaGroupId = textOrNull(node, "kafkaGroupId");
            config.publishOnLoad = boolOrDefault(node, "publishOnLoad", true);
            config.consume = boolOrDefault(node, "consume", false);
            config.publishIntervalMillis = longOrDefault(node, "publishIntervalMillis", 0);
            config.mqttQos = intOrDefault(node, "mqttQos", 1);
            config.mqttProtocolVersion = intOrDefault(node, "mqttProtocolVersion", 3);
            if (config.mqttProtocolVersion != 3 && config.mqttProtocolVersion != 5) {
                throw new IllegalArgumentException("Unsupported 'mqttProtocolVersion' value "
                    + config.mqttProtocolVersion + " — allowed values are 3 or 5");
            }
            config.kafkaSecurity = parseKafkaSecurity(node.get("kafkaSecurity"), config.kafkaBootstrapServers);
            config.mqttSecurity = parseMqttSecurity(node.get("mqttSecurity"));
            String valueFormat = textOrNull(node, "kafkaValueFormat");
            if (valueFormat != null) {
                String normalisedFormat = valueFormat.toLowerCase();
                if ("protobuf".equals(normalisedFormat)) {
                    throw new IllegalArgumentException("Unsupported 'kafkaValueFormat' value '"
                        + valueFormat + "' — protobuf is not yet supported; allowed values are json or avro");
                }
                if (!"json".equals(normalisedFormat) && !"avro".equals(normalisedFormat)) {
                    throw new IllegalArgumentException("Unsupported 'kafkaValueFormat' value '"
                        + valueFormat + "' — allowed values are json or avro");
                }
                config.kafkaValueFormat = normalisedFormat;
            }
            config.kafkaSchemaRegistryUrl = textOrNull(node, "kafkaSchemaRegistryUrl");
            config.avroSchema = avroSchemaOrNull(node.get("avroSchema"));
            config.avroSchemaId = intOrDefault(node, "avroSchemaId", 1);
        }
        // Fall back to configured defaults when request values are absent — preferring the
        // Configuration instance (which itself falls back to ConfigurationProperties) so that
        // values set on the instance, e.g. via PUT /mockserver/configuration (which never changes the Kafka
        // bootstrap servers), are honoured
        if (config.kafkaBootstrapServers == null) {
            String configDefault = configuration != null
                ? configuration.asyncKafkaBootstrapServers()
                : ConfigurationProperties.asyncKafkaBootstrapServers();
            if (configDefault != null && !configDefault.isEmpty()) {
                config.kafkaBootstrapServers = configDefault;
            }
        }
        if (config.mqttBrokerUrl == null) {
            String configDefault = configuration != null
                ? configuration.asyncMqttBrokerUrl()
                : ConfigurationProperties.asyncMqttBrokerUrl();
            if (configDefault != null && !configDefault.isEmpty()) {
                config.mqttBrokerUrl = configDefault;
            }
        }
        if (config.amqpUri == null) {
            String configDefault = configuration != null
                ? configuration.asyncAmqpUri()
                : ConfigurationProperties.asyncAmqpUri();
            if (configDefault != null && !configDefault.isEmpty()) {
                config.amqpUri = configDefault;
            }
        }
        return config;
    }

    /**
     * The {@code kafkaSecurity} fields a control-plane request may set. None names a class the Kafka client loads.
     * {@code sasl.jaas.config} does (its login module), so it comes only from the startup-only
     * {@code asyncKafkaSaslJaasConfig} property; every other class-loading Kafka property is never set from a request.
     */
    static final List<String> KAFKA_SECURITY_FIELDS = List.of("securityProtocol", "saslMechanism",
        "sslTruststoreLocation", "sslTruststorePassword", "sslKeystoreLocation", "sslKeystorePassword", "sslKeyPassword");
    static final List<String> KAFKA_SECURITY_PROTOCOLS = List.of("PLAINTEXT", "SSL", "SASL_PLAINTEXT", "SASL_SSL");
    // Kafka's built-in mechanisms; each maps to a fixed SASL client and login class inside the Kafka client
    static final List<String> KAFKA_SASL_MECHANISMS = List.of("GSSAPI", "PLAIN", "SCRAM-SHA-256", "SCRAM-SHA-512", "OAUTHBEARER");
    static final String SASL_JAAS_CONFIG_STARTUP_ONLY = "'kafkaSecurity.saslJaasConfig' cannot be set over the control plane:"
        + " a JAAS configuration chooses which login module class the Kafka client loads, so it must be set at startup."
        + " Set the mockserver.asyncKafkaSaslJaasConfig property (or the MOCKSERVER_ASYNC_KAFKA_SASL_JAAS_CONFIG"
        + " environment variable) before the server starts, and keep securityProtocol and saslMechanism in kafkaSecurity";

    static final String KAFKA_BOOTSTRAP_SERVERS_BOUND_TO_STARTUP = "'kafkaBootstrapServers' must be omitted or match"
        + " the startup mockserver.asyncKafkaBootstrapServers when a SASL securityProtocol is used: every SASL credential"
        + " (mockserver.asyncKafkaSaslJaasConfig or the JVM's JAAS login configuration) is held by the server, so it is"
        + " sent only to the Kafka brokers chosen when the server starts";
    static final String KAFKA_SASL_NEEDS_STARTUP_BOOTSTRAP_SERVERS = "a SASL securityProtocol needs"
        + " mockserver.asyncKafkaBootstrapServers (or the MOCKSERVER_ASYNC_KAFKA_BOOTSTRAP_SERVERS environment variable)"
        + " to be set before the server starts: every SASL credential is held by the server, so it is sent only to the"
        + " Kafka brokers chosen at startup";

    private KafkaSecurity parseKafkaSecurity(JsonNode node, String requestedKafkaBootstrapServers) {
        if (node == null || !node.isObject()) {
            return null;
        }
        refuseStartupOnlyKafkaSecurityFields(node);
        KafkaSecurity.Builder builder = KafkaSecurity.builder();
        String securityProtocol = textOrNull(node, "securityProtocol");
        if (securityProtocol != null) {
            securityProtocol = securityProtocol.toUpperCase(Locale.ROOT);
            if (!KAFKA_SECURITY_PROTOCOLS.contains(securityProtocol)) {
                throw new IllegalArgumentException("Unsupported 'kafkaSecurity.securityProtocol' value '"
                    + node.get("securityProtocol").asText() + "' — allowed values are " + KAFKA_SECURITY_PROTOCOLS);
            }
            builder.securityProtocol(securityProtocol);
        }
        String saslMechanism = textOrNull(node, "saslMechanism");
        if (saslMechanism != null) {
            if (!KAFKA_SASL_MECHANISMS.contains(saslMechanism)) {
                throw new IllegalArgumentException("Unsupported 'kafkaSecurity.saslMechanism' value '"
                    + saslMechanism + "' — allowed values are " + KAFKA_SASL_MECHANISMS);
            }
            builder.saslMechanism(saslMechanism);
        }
        if (securityProtocol != null && securityProtocol.startsWith("SASL_")) {
            refuseNonStartupKafkaBootstrapServers(requestedKafkaBootstrapServers);
            String startupSaslJaasConfig = configuration != null
                ? configuration.asyncKafkaSaslJaasConfig()
                : ConfigurationProperties.asyncKafkaSaslJaasConfig();
            if (startupSaslJaasConfig != null && !startupSaslJaasConfig.isBlank()) {
                builder.saslJaasConfig(startupSaslJaasConfig);
            }
        }
        String sslTruststoreLocation = textOrNull(node, "sslTruststoreLocation");
        if (sslTruststoreLocation != null) {
            builder.sslTruststoreLocation(sslTruststoreLocation);
        }
        String sslTruststorePassword = textOrNull(node, "sslTruststorePassword");
        if (sslTruststorePassword != null) {
            builder.sslTruststorePassword(sslTruststorePassword);
        }
        String sslKeystoreLocation = textOrNull(node, "sslKeystoreLocation");
        if (sslKeystoreLocation != null) {
            builder.sslKeystoreLocation(sslKeystoreLocation);
        }
        String sslKeystorePassword = textOrNull(node, "sslKeystorePassword");
        if (sslKeystorePassword != null) {
            builder.sslKeystorePassword(sslKeystorePassword);
        }
        String sslKeyPassword = textOrNull(node, "sslKeyPassword");
        if (sslKeyPassword != null) {
            builder.sslKeyPassword(sslKeyPassword);
        }
        KafkaSecurity result = builder.build();
        return result.isEmpty() ? null : result;
    }

    /**
     * A request can no longer carry its own JAAS, so every SASL credential is held by the server (the startup JAAS,
     * or the JVM's JAAS login configuration) and goes only to the brokers chosen at startup: a SASL request may omit
     * {@code kafkaBootstrapServers} or repeat the startup {@code asyncKafkaBootstrapServers}, compared as a set of
     * host:port values. {@code PUT /mockserver/configuration} never changes that property.
     */
    private void refuseNonStartupKafkaBootstrapServers(String requestedKafkaBootstrapServers) {
        String startupKafkaBootstrapServers = configuration != null
            ? configuration.asyncKafkaBootstrapServers()
            : ConfigurationProperties.asyncKafkaBootstrapServers();
        Set<String> startupDestination = normalisedBootstrapServers(startupKafkaBootstrapServers);
        String refusal = null;
        if (startupDestination.isEmpty()) {
            refusal = KAFKA_SASL_NEEDS_STARTUP_BOOTSTRAP_SERVERS;
        } else if (requestedKafkaBootstrapServers != null && !startupDestination.equals(normalisedBootstrapServers(requestedKafkaBootstrapServers))) {
            refusal = KAFKA_BOOTSTRAP_SERVERS_BOUND_TO_STARTUP;
        }
        if (refusal != null) {
            LOG.warn("Refused PUT /mockserver/asyncapi: {}", refusal);
            throw new IllegalArgumentException(refusal);
        }
    }

    static Set<String> normalisedBootstrapServers(String bootstrapServers) {
        Set<String> servers = new HashSet<>();
        if (bootstrapServers != null) {
            for (String server : bootstrapServers.split(",")) {
                String trimmed = server.trim().toLowerCase(Locale.ROOT);
                if (!trimmed.isEmpty()) {
                    servers.add(trimmed);
                }
            }
        }
        return servers;
    }

    /**
     * Refuses, rather than ignores, a {@code saslJaasConfig} or any field that is not a known {@code kafkaSecurity}
     * field, so a caller is never left believing a class-loading Kafka property took effect. A JSON null is absent.
     */
    private static void refuseStartupOnlyKafkaSecurityFields(JsonNode node) {
        node.fieldNames().forEachRemaining(name -> {
            if (node.get(name).isNull() || KAFKA_SECURITY_FIELDS.contains(name)) {
                return;
            }
            String reason = "saslJaasConfig".equals(name)
                ? SASL_JAAS_CONFIG_STARTUP_ONLY
                : "unknown field '" + name.replaceAll("\\p{Cntrl}", "?") + "' in kafkaSecurity; valid fields are " + KAFKA_SECURITY_FIELDS
                + " (Kafka client properties that load classes, such as sasl.jaas.config, sasl.login.class or"
                + " interceptor.classes, cannot be set over the control plane)";
            LOG.warn("Refused PUT /mockserver/asyncapi: {}", reason);
            throw new IllegalArgumentException(reason);
        });
    }

    private MqttSecurity parseMqttSecurity(JsonNode node) {
        if (node == null || !node.isObject()) {
            return null;
        }
        MqttSecurity.Builder builder = MqttSecurity.builder();
        String username = textOrNull(node, "username");
        if (username != null) {
            builder.username(username);
        }
        String password = textOrNull(node, "password");
        if (password != null) {
            builder.password(password);
        }
        JsonNode sslPropsNode = node.get("sslProperties");
        if (sslPropsNode != null && sslPropsNode.isObject()) {
            Map<String, String> sslProps = new LinkedHashMap<>();
            sslPropsNode.fields().forEachRemaining(entry -> {
                if (entry.getValue().isTextual()) {
                    sslProps.put(entry.getKey(), entry.getValue().asText());
                }
            });
            if (!sslProps.isEmpty()) {
                builder.sslProperties(sslProps);
            }
        }
        MqttSecurity result = builder.build();
        return result.isEmpty() ? null : result;
    }

    private AsyncApiChannel findChannel(String name) {
        if (loadedSpec == null) {
            return null;
        }
        for (AsyncApiChannel channel : loadedSpec.getChannels()) {
            if (channel.getName().equals(name)) {
                return channel;
            }
        }
        return null;
    }

    private static String textOrNull(JsonNode node, String field) {
        JsonNode child = node.get(field);
        return (child != null && child.isTextual()) ? child.asText() : null;
    }

    /**
     * An Avro schema may be supplied either as a JSON string or as an inline JSON
     * object. Return the Avro schema JSON text in either case, or null when absent.
     */
    private static String avroSchemaOrNull(JsonNode child) {
        if (child == null || child.isNull()) {
            return null;
        }
        if (child.isTextual()) {
            String text = child.asText();
            return text.isBlank() ? null : text;
        }
        if (child.isObject()) {
            try {
                return MAPPER.writeValueAsString(child);
            } catch (Exception e) {
                LOG.warn("Failed to serialize inline avroSchema object: {}", e.getMessage());
                return null;
            }
        }
        return null;
    }

    private static boolean boolOrDefault(JsonNode node, String field, boolean defaultValue) {
        JsonNode child = node.get(field);
        return (child != null && child.isBoolean()) ? child.asBoolean() : defaultValue;
    }

    private static long longOrDefault(JsonNode node, String field, long defaultValue) {
        JsonNode child = node.get(field);
        return (child != null && child.isNumber()) ? child.asLong() : defaultValue;
    }

    private static int intOrDefault(JsonNode node, String field, int defaultValue) {
        JsonNode child = node.get(field);
        return (child != null && child.isNumber()) ? child.asInt() : defaultValue;
    }

    private static String truncate(String value) {
        if (value == null) {
            return "null";
        }
        return value.length() <= MAX_LOG_PAYLOAD_LENGTH
            ? value
            : value.substring(0, MAX_LOG_PAYLOAD_LENGTH) + "...(" + value.length() + " chars)";
    }

    /**
     * Broker connection configuration, parsed from the optional {@code brokerConfig}
     * field in the PUT request body.
     */
    static class BrokerConfig {
        String kafkaBootstrapServers;
        String kafkaGroupId;
        String mqttBrokerUrl;
        String mqttClientId;
        String amqpUri;
        boolean publishOnLoad = true;
        boolean consume = false;
        long publishIntervalMillis = 0;
        int mqttQos = 1;
        int mqttProtocolVersion = 3;
        KafkaSecurity kafkaSecurity;
        MqttSecurity mqttSecurity;
        // Kafka value serialization: "json" (default) or "avro" (Confluent wire format)
        String kafkaValueFormat = "json";
        // Confluent Schema Registry URL; when present, schema ids are registered/resolved against it
        String kafkaSchemaRegistryUrl;
        // Inline Avro schema JSON (registry-less mode, or the schema to register on publish)
        String avroSchema;
        // Fixed Avro schema id used to frame published messages in registry-less mode
        int avroSchemaId = 1;

        static BrokerConfig defaultConfig() {
            return new BrokerConfig();
        }
    }

    /**
     * Record of a schema validation issue for reporting.
     */
    static class SchemaValidationRecord {
        final String channel;
        final String context;
        final String errors;

        SchemaValidationRecord(String channel, String context, String errors) {
            this.channel = channel;
            this.context = context;
            this.errors = errors;
        }
    }
}
