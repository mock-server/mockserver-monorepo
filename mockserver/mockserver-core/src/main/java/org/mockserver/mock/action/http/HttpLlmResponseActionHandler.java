package org.mockserver.mock.action.http;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.mockserver.configuration.ConfigurationProperties;
import org.mockserver.llm.LlmCompletionGenerator;
import org.mockserver.llm.LlmContentFilterBodies;
import org.mockserver.llm.LlmErrorBodies;
import org.mockserver.llm.LlmErrorBody;
import org.mockserver.llm.LlmQuotaRegistry;
import org.mockserver.llm.LlmRateLimitHeaders;
import org.mockserver.llm.OpenAiModerationBodies;
import org.mockserver.llm.OpenAiResponsesStore;
import org.mockserver.llm.ParsedConversation;
import org.mockserver.llm.ProviderCodec;
import org.mockserver.llm.ProviderCodecRegistry;
import org.mockserver.llm.StreamingFormat;
import org.mockserver.llm.TokenCounter;
import org.mockserver.llm.client.LlmProviderSniffer;
import org.mockserver.llm.codec.EmbeddingWire;
import org.mockserver.log.model.LogEntry;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.model.*;
import org.mockserver.validator.jsonschema.JsonSchemaValidator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.event.Level;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.mockserver.log.model.LogEntry.LogMessageType.EXPECTATION_RESPONSE;
import static org.mockserver.model.HttpResponse.response;

public class HttpLlmResponseActionHandler {

    private static final Logger LOG = LoggerFactory.getLogger(HttpLlmResponseActionHandler.class);
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private final MockServerLogger mockServerLogger;

    // Compiled structured-output validators, keyed by the schema string. Building a
    // JsonSchemaValidator parses and compiles the schema (relatively expensive); the same
    // fixed schema is otherwise re-compiled on every matched LLM response. The distinct-schema
    // count is bounded by the number of configured expectations, but a misbehaving client could
    // otherwise grow the cache without limit, so it is an access-ordered LRU bounded at
    // STRUCTURED_OUTPUT_VALIDATOR_CACHE_MAX, wrapped in Collections.synchronizedMap for safe
    // concurrent get/put. A cached JsonSchemaValidator is NOT safe for concurrent isValid() —
    // isValid() reassigns its internal validator field on the rare "Unknown MetaSchema" recovery
    // path — so callers synchronize on the cached instance for the duration of the validate call
    // (see validateAgainstSchema). Validation is cheap relative to compilation, so the brief
    // per-schema serialization is a good trade for never re-parsing the schema.
    static final int STRUCTURED_OUTPUT_VALIDATOR_CACHE_MAX = 256;
    private final Map<String, JsonSchemaValidator> structuredOutputValidatorCache =
        Collections.synchronizedMap(new LinkedHashMap<String, JsonSchemaValidator>(64, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, JsonSchemaValidator> eldest) {
                return size() > STRUCTURED_OUTPUT_VALIDATOR_CACHE_MAX;
            }
        });

    /**
     * The live server {@link org.mockserver.configuration.Configuration}, or {@code null} when the
     * handler is constructed without one (unit tests). Preferred over the static
     * {@link ConfigurationProperties} store so {@code llmInferUsageEnabled} set over
     * {@code PUT /mockserver/configuration} actually takes effect here.
     */
    private final org.mockserver.configuration.Configuration configuration;

    public HttpLlmResponseActionHandler(MockServerLogger mockServerLogger) {
        this(mockServerLogger, null);
    }

    public HttpLlmResponseActionHandler(MockServerLogger mockServerLogger, org.mockserver.configuration.Configuration configuration) {
        this.mockServerLogger = mockServerLogger;
        this.configuration = configuration;
    }

    public HttpResponse handle(HttpLlmResponse httpLlmResponse, HttpRequest request) {
        Provider provider = httpLlmResponse.getProvider();
        if (provider == null) {
            if (mockServerLogger.isEnabledForInstance(Level.WARN)) {
                mockServerLogger.logEvent(
                    new LogEntry()
                        .setType(EXPECTATION_RESPONSE)
                        .setLogLevel(Level.WARN)
                        .setCorrelationId(request.getLogCorrelationId())
                        .setHttpRequest(request)
                        .setMessageFormat("no provider specified for LLM response action for request:{}")
                        .setArguments(request)
                );
            }
            return response()
                .withStatusCode(400)
                .withBody("{\"error\":\"unsupported LLM provider: null\",\"supported\":" + supportedProvidersJson() + "}");
        }

        ProviderCodecRegistry registry = ProviderCodecRegistry.getInstance();
        Optional<ProviderCodec> codec = registry.lookup(provider);
        if (!codec.isPresent()) {
            if (mockServerLogger.isEnabledForInstance(Level.INFO)) {
                mockServerLogger.logEvent(
                    new LogEntry()
                        .setType(EXPECTATION_RESPONSE)
                        .setLogLevel(Level.INFO)
                        .setCorrelationId(request.getLogCorrelationId())
                        .setHttpRequest(request)
                        .setMessageFormat("no codec registered for provider {} for LLM response action for request:{}")
                        .setArguments(provider, request)
                );
            }
            return response()
                .withStatusCode(400)
                .withBody("{\"error\":\"unsupported LLM provider: " + provider + "\",\"supported\":" + supportedProvidersJson() + "}");
        }

        String model = httpLlmResponse.getModel();
        ProviderCodec codecInstance = codec.get();

        try {
            // Moderation path (OpenAI POST /v1/moderations) — a flagged/not-flagged verdict.
            if (httpLlmResponse.getModeration() != null) {
                String body = OpenAiModerationBodies.encode(httpLlmResponse.getModeration());
                return response()
                    .withStatusCode(200)
                    .withHeader("content-type", "application/json")
                    .withBody(body);
            }

            // Embedding path
            if (httpLlmResponse.getEmbedding() != null) {
                HttpResponse encoded;
                try {
                    encoded = EmbeddingWire.withLimits(embeddingLimits(),
                        () -> codecInstance.encodeEmbedding(httpLlmResponse.getEmbedding(), request, model));
                } catch (EmbeddingWire.InvalidEmbeddingRequestException e) {
                    return EmbeddingWire.invalidRequest(provider, e);
                }
                recordEncodedCallUsage(LlmProviderSniffer.Operation.EMBEDDINGS, provider, model, request, encoded);
                return encoded;
            }

            // Rerank path
            if (httpLlmResponse.getRerank() != null) {
                List<String> documents = extractDocumentsFromRequest(request);
                HttpResponse encoded;
                try {
                    encoded = codecInstance.encodeRerank(httpLlmResponse.getRerank(), documents, request);
                } catch (EmbeddingWire.InvalidEmbeddingRequestException e) {
                    return EmbeddingWire.invalidRequest(provider, e);
                }
                recordEncodedCallUsage(LlmProviderSniffer.Operation.RERANK, provider, model, request, encoded);
                return encoded;
            }

            // Non-streaming completion path; a model load or unload is not traced, counted or checked
            boolean modelManagement = codecInstance.isModelManagementRequest(request);
            Completion completion = modelManagement
                ? httpLlmResponse.getCompletion()
                : withGeneratedCompletionIfConfigured(httpLlmResponse.getCompletion(), codecInstance, request);
            if (completion != null && !Boolean.TRUE.equals(completion.getStreaming())) {
                HttpResponse encoded = codecInstance.encode(completion, model, request);
                applyAzureContentFilterAnnotations(encoded, provider, httpLlmResponse.getContentFilter());
                if (!modelManagement) {
                    validateStructuredOutput(completion, encoded, provider, request);
                }
                applyRateLimitHeaders(encoded, provider, false, httpLlmResponse.getChaos());
                if (!modelManagement) {
                    recordCompletionSpan(provider, model, request, completion);
                    HttpActionHandler.recordLlmUsageMetrics(provider, model, completion);
                }
                recordOpenAiResponsesStateIfApplicable(provider, codecInstance, request, completion, encoded);
                return encoded;
            }

            // Streaming completion path: this method only handles non-streaming.
            // Streaming is dispatched via handleStreaming() from HttpActionHandler,
            // so reaching here means the caller bypassed the normal dispatch.
            if (completion != null && Boolean.TRUE.equals(completion.getStreaming())) {
                if (mockServerLogger.isEnabledForInstance(Level.WARN)) {
                    mockServerLogger.logEvent(
                        new LogEntry()
                            .setType(EXPECTATION_RESPONSE)
                            .setLogLevel(Level.WARN)
                            .setCorrelationId(request.getLogCorrelationId())
                            .setHttpRequest(request)
                            .setMessageFormat("streaming LLM response reached the non-streaming handle() path for provider {} — dispatcher bug; returning 501")
                            .setArguments(provider)
                    );
                }
                return response()
                    .withStatusCode(501)
                    .withBody("{\"error\":\"streaming LLM responses must be dispatched through the SSE handler\"}");
            }

            // No completion, embedding, or rerank configured
            return response()
                .withStatusCode(400)
                .withBody("{\"error\":\"httpLlmResponse must have either a completion, embedding, or rerank configured\"}");
        } catch (UnsupportedOperationException e) {
            return response()
                .withStatusCode(400)
                .withBody("{\"error\":\"" + e.getMessage() + "\"}");
        } catch (Exception e) {
            if (mockServerLogger.isEnabledForInstance(Level.WARN)) {
                mockServerLogger.logEvent(
                    new LogEntry()
                        .setType(EXPECTATION_RESPONSE)
                        .setLogLevel(Level.WARN)
                        .setCorrelationId(request.getLogCorrelationId())
                        .setHttpRequest(request)
                        .setMessageFormat("llm codec encode failed for provider {} for request:{}")
                        .setArguments(provider, request)
                        .setThrowable(e)
                );
            }
            return response()
                .withStatusCode(502)
                .withBody("{\"error\":\"llm codec encode failed\",\"provider\":\"" + provider.name() + "\"}");
        }
    }

    /**
     * Return the completion to encode for this request, generated from its
     * {@link Completion#getGenerate() generate} config when one is declared.
     * Generation subsumes usage inference: the returned completion always carries
     * computed usage — input tokens estimated from <em>this</em> request's decoded
     * conversation (so multi-turn history growth is reflected), output tokens equal
     * to the generated text's real token count — and its streaming physics are
     * sampled (input-scaled time-to-first-token, sampled tokens-per-second).
     *
     * <p>The shared expectation {@link Completion} is <strong>never mutated</strong>:
     * a per-request copy carries the generated content, so concurrent requests are
     * safe. Returns the input completion unchanged (falling back to
     * {@link #withInferredUsageIfEnabled}) when there is no {@code generate} config.
     * Fail-soft: any error decoding the request yields a 0 input-token estimate.
     */
    Completion withGeneratedCompletionIfConfigured(Completion completion, ProviderCodec codec, HttpRequest request) {
        if (completion == null || completion.getGenerate() == null) {
            return withInferredUsageIfEnabled(completion, codec, request);
        }
        CompletionGeneration generation = completion.getGenerate();
        int inputTokens = 0;
        try {
            ParsedConversation conversation = codec.decode(request);
            inputTokens = TokenCounter.estimatePromptTokens(conversation);
        } catch (Exception e) {
            // fail-soft: a request that cannot be decoded simply yields a 0 input-token estimate,
            // degrading the TTFT model (no prefill scaling) for this request
            if (mockServerLogger.isEnabledForInstance(Level.DEBUG)) {
                mockServerLogger.logEvent(
                    new LogEntry()
                        .setType(EXPECTATION_RESPONSE)
                        .setLogLevel(Level.DEBUG)
                        .setCorrelationId(request != null ? request.getLogCorrelationId() : null)
                        .setHttpRequest(request)
                        .setMessageFormat("could not decode request to estimate prompt tokens for generated LLM response — TTFT will not scale with input:{}")
                        .setArguments(e.getMessage())
                );
            }
        }
        long seed = LlmCompletionGenerator.resolveSeed(generation, newSeed(request));
        boolean streaming = Boolean.TRUE.equals(completion.getStreaming());
        return LlmCompletionGenerator.generate(completion, generation, inputTokens, seed, streaming);
    }

    /**
     * Per-request seed when the generation config declares none: a 64-bit hash of the
     * request's method, path and body. The same request therefore replays the same
     * generation (a retry of an identical request is deterministic), while distinct
     * requests diverge; 64 bits makes collisions negligible at any realistic request
     * rate. Falls back to time-based entropy when the request is {@code null} or has
     * no body.
     */
    static long newSeed(HttpRequest request) {
        if (request == null) {
            return System.nanoTime();
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            String method = request.getMethod() != null ? request.getMethod().getValue() : "";
            digest.update(method.getBytes(StandardCharsets.UTF_8));
            digest.update((byte) 0);
            String path = request.getPath() != null && request.getPath().getValue() != null
                ? request.getPath().getValue()
                : "";
            digest.update(path.getBytes(StandardCharsets.UTF_8));
            digest.update((byte) 0);
            String body = request.getBodyAsText();
            digest.update(body.getBytes(StandardCharsets.UTF_8));
            return ByteBuffer.wrap(digest.digest()).getLong();
        } catch (Exception e) {
            // fail-soft: hashing must never break the response; determinism degrades to entropy
            LOG.debug("could not derive a generation seed from the request, falling back to time-based entropy", e);
            return System.nanoTime();
        }
    }

    /**
     * Records the GenAI span for a served completion, named for the wire API its codec served:
     * {@code generate_content}, {@code text_completion} or {@code chat}.
     */
    private static void recordCompletionSpan(Provider provider, String model, HttpRequest request, Completion completion) {
        String path = request != null && request.getPath() != null ? request.getPath().getValue() : null;
        org.mockserver.telemetry.GenAiSpans.record(
            LlmProviderSniffer.servedCompletionOperation(provider, path).genAiOperationName(), provider, model, completion);
    }

    /**
     * Records the GenAI span and token metrics for a served embeddings or rerank response, with
     * the usage its codec encoded. The model is resolved as on the forward path: the one the
     * response names, then the Bedrock or Gemini path, then the request body, then the expectation.
     * Fail-soft: telemetry must never affect the served response.
     */
    private void recordEncodedCallUsage(LlmProviderSniffer.Operation operation, Provider provider, String expectationModel,
                                        HttpRequest request, HttpResponse encoded) {
        try {
            Completion usage = EncodedLlmCallUsage.read(encoded);
            if (usage == null) {
                usage = Completion.completion();
            }
            String path = request != null && request.getPath() != null ? request.getPath().getValue() : null;
            String model = usage.getModel();
            if (model == null && provider == Provider.BEDROCK) {
                model = LlmProviderSniffer.extractBedrockModelFromPath(path);
            }
            if (model == null && provider == Provider.GEMINI) {
                model = LlmProviderSniffer.extractGeminiModelFromPath(path);
            }
            if (model == null) {
                model = LlmProviderSniffer.extractModelFromRequest(request);
            }
            if (model == null) {
                model = expectationModel;
            }
            org.mockserver.telemetry.GenAiSpans.record(operation.genAiOperationName(), provider, model, usage);
            HttpActionHandler.recordLlmUsageMetrics(provider, model, usage);
        } catch (Exception e) {
            // fail-soft: telemetry must never affect the served response
        }
    }

    /**
     * For a served {@link Provider#OPENAI_RESPONSES} completion, record the issued
     * response in {@link OpenAiResponsesStore} so it can be chained (via a later request's
     * {@code previous_response_id}) or retrieved ({@code GET /v1/responses/{id}}). No-op
     * for every other provider and when the request sets {@code store:false}. Fail-soft
     * (any error is swallowed inside the store) so it can never affect the served response.
     */
    private void recordOpenAiResponsesStateIfApplicable(Provider provider, ProviderCodec codec,
                                                        HttpRequest request, Completion completion,
                                                        HttpResponse encoded) {
        if (provider != Provider.OPENAI_RESPONSES) {
            return;
        }
        String requestBody = request != null ? request.getBodyAsText() : null;
        OpenAiResponsesStore.getInstance().recordIfStored(
            requestBody, chainedConversation(codec, request), completion, encoded.getBodyAsString());
    }

    /**
     * decode() returns the fully-chained conversation (prior turns already prepended when the
     * request carried a previous_response_id), so a stored record captures the entire dialogue.
     */
    private static ParsedConversation chainedConversation(ProviderCodec codec, HttpRequest request) {
        try {
            return codec.decode(request);
        } catch (Exception e) {
            return ParsedConversation.empty();
        }
    }

    static final String STRUCTURED_OUTPUT_INVALID_HEADER = "x-mockserver-structured-output-invalid";

    /**
     * Validate the completion's configured text against its declared
     * {@link Completion#getOutputSchema() output schema}, if any. Fail-soft: a
     * mismatch never alters the response body — it adds the
     * {@code x-mockserver-structured-output-invalid} diagnostic header (when an
     * {@code encoded} response is supplied) and logs a warning. A blank schema,
     * absent text, or a malformed schema are all treated as "nothing to check"
     * and never throw, so structured-output validation can never break an LLM
     * response. {@code encoded} may be {@code null} (e.g. streaming) — then only
     * the warning is logged.
     */
    void validateStructuredOutput(Completion completion, HttpResponse encoded, Provider provider, HttpRequest request) {
        String error = structuredOutputError(completion, provider, request);
        if (error == null) {
            return;
        }
        if (encoded != null) {
            encoded.withHeader(STRUCTURED_OUTPUT_INVALID_HEADER, compactHeaderValue(error));
        }
        if (mockServerLogger.isEnabledForInstance(Level.WARN)) {
            mockServerLogger.logEvent(
                new LogEntry()
                    .setType(EXPECTATION_RESPONSE)
                    .setLogLevel(Level.WARN)
                    .setCorrelationId(request.getLogCorrelationId())
                    .setHttpRequest(request)
                    .setMessageFormat("configured LLM completion text for provider {} does not conform to its declared outputSchema:{}")
                    .setArguments(provider, error)
            );
        }
    }

    /**
     * Validate the completion text against its declared {@link Completion#getOutputSchema()
     * output schema}. Returns the validation error message when the text is present, the
     * schema is non-blank and parseable, and the text does not conform; returns {@code null}
     * in every "nothing to check" case (no completion, blank schema, absent text, or a
     * malformed schema) so structured-output checking can never throw and can never break a
     * response. Shared by the fail-soft diagnostic path ({@link #validateStructuredOutput})
     * and the strict enforcement path ({@link #enforcementErrorResponseOrNull}). {@code provider}
     * and {@code request} are used only to enrich the malformed-schema warning (correlation id +
     * provider context) and may be {@code null}.
     */
    private String structuredOutputError(Completion completion, Provider provider, HttpRequest request) {
        if (completion == null) {
            return null;
        }
        String schema = completion.getOutputSchema();
        String text = completion.getText();
        if (schema == null || schema.trim().isEmpty() || text == null) {
            return null;
        }
        try {
            String error = validateAgainstSchema(schema, text);
            return (error != null && !error.isEmpty()) ? error : null;
        } catch (Exception e) {
            // a malformed schema must never break the response — treat it as a no-op
            if (mockServerLogger.isEnabledForInstance(Level.WARN)) {
                mockServerLogger.logEvent(
                    new LogEntry()
                        .setType(EXPECTATION_RESPONSE)
                        .setLogLevel(Level.WARN)
                        .setCorrelationId(request != null ? request.getLogCorrelationId() : null)
                        .setHttpRequest(request)
                        .setMessageFormat("could not validate LLM completion text against outputSchema for provider {} — treating schema as a no-op:{}")
                        .setArguments(provider, e.getMessage())
                );
            }
            return null;
        }
    }

    /**
     * Validate {@code text} against {@code schema} using a {@link JsonSchemaValidator} cached by
     * schema string, so the fixed structured-output schema is parsed/compiled once rather than on
     * every matched LLM response. Returns the validator's error message (empty/blank when valid).
     * <p>
     * A {@link JsonSchemaValidator} is not safe for concurrent {@link JsonSchemaValidator#isValid}
     * (it reassigns its internal validator on the rare meta-schema recovery path), so the validate
     * call is synchronized on the cached instance. Compilation dominates cost, so the brief
     * per-schema serialization of the cheap validate call is an acceptable trade.
     */
    private String validateAgainstSchema(String schema, String text) {
        JsonSchemaValidator validator = structuredOutputValidatorCache.computeIfAbsent(
            schema, s -> new JsonSchemaValidator(mockServerLogger, s));
        synchronized (validator) {
            return validator.isValid(text, false);
        }
    }

    /** Test seam: the currently-cached structured-output validator for a schema string, or {@code null}. */
    JsonSchemaValidator cachedStructuredOutputValidator(String schema) {
        return structuredOutputValidatorCache.get(schema);
    }

    static final int STRUCTURED_OUTPUT_ENFORCEMENT_STATUS = 502;

    /**
     * Strict structured-output enforcement, opt-in via
     * {@link Completion#getEnforceOutputSchema()}. When enforcement is enabled and the
     * completion's configured {@link Completion#getText() text} does not conform to its
     * declared {@link Completion#getOutputSchema() output schema}, returns a provider-correct
     * error {@link HttpResponse} (status {@value #STRUCTURED_OUTPUT_ENFORCEMENT_STATUS}, with
     * the {@code x-mockserver-structured-output-invalid} diagnostic header) so the mock fails
     * loudly rather than returning a non-conforming body. Returns {@code null} when
     * enforcement is off, no schema is declared, or the text conforms — in which case the
     * response is delivered normally.
     *
     * <p>Models real providers' strict {@code response_format: json_schema} mode, which
     * guarantees schema-valid output: a non-conforming strict fixture is a configuration
     * error and surfaces as an error rather than silently passing. Applies to both the
     * streaming and non-streaming paths (checked before dispatch by {@code HttpActionHandler},
     * mirroring {@link #chaosOutcome}), so a strict streaming completion with a
     * non-conforming body never begins streaming.
     */
    public HttpResponse enforcementErrorResponseOrNull(HttpLlmResponse httpLlmResponse, HttpRequest request) {
        if (httpLlmResponse == null) {
            return null;
        }
        Completion completion = httpLlmResponse.getCompletion();
        if (completion == null || !Boolean.TRUE.equals(completion.getEnforceOutputSchema())) {
            return null;
        }
        Provider provider = httpLlmResponse.getProvider();
        if (isModelManagementRequest(provider, request)) {
            return null;
        }
        // In generate mode the expectation's completion has no text to check pre-generation;
        // generate the response first so strict enforcement sees the body actually served.
        Completion checkable = completion;
        if (completion.getGenerate() != null) {
            CompletionGeneration generation = completion.getGenerate();
            long seed = LlmCompletionGenerator.resolveSeed(generation, newSeed(request));
            boolean streaming = Boolean.TRUE.equals(completion.getStreaming());
            checkable = LlmCompletionGenerator.generate(completion, generation, 0, seed, streaming);
        }
        String error = structuredOutputError(checkable, provider, request);
        if (error == null) {
            return null;
        }
        if (mockServerLogger.isEnabledForInstance(Level.WARN)) {
            mockServerLogger.logEvent(
                new LogEntry()
                    .setType(EXPECTATION_RESPONSE)
                    .setLogLevel(Level.WARN)
                    .setCorrelationId(request != null ? request.getLogCorrelationId() : null)
                    .setHttpRequest(request)
                    .setMessageFormat("strict structured-output enforcement is enabled but the configured LLM completion text for provider {} does not conform to its declared outputSchema — returning an enforcement error:{}")
                    .setArguments(provider, error)
            );
        }
        String message = "structured-output enforcement failed: configured response does not conform to the declared outputSchema";
        String body = LlmErrorBodies.bodyFor(provider, LlmErrorBodies.Kind.SERVER_ERROR, STRUCTURED_OUTPUT_ENFORCEMENT_STATUS, message);
        if (body == null) {
            body = "{\"error\":{\"type\":\"structured_output_enforcement_failed\",\"message\":\"" + message + "\"}}";
        }
        return response()
            .withStatusCode(STRUCTURED_OUTPUT_ENFORCEMENT_STATUS)
            .withHeader("content-type", "application/json")
            .withHeader(STRUCTURED_OUTPUT_INVALID_HEADER, compactHeaderValue(error))
            .withBody(body);
    }

    private static boolean isModelManagementRequest(Provider provider, HttpRequest request) {
        return provider != null && ProviderCodecRegistry.getInstance().lookup(provider)
            .map(codec -> codec.isModelManagementRequest(request))
            .orElse(false);
    }

    /**
     * Flatten a (possibly multi-line) validation message into a single header-safe
     * line, collapsing CR/LF — HTTP header values must not contain line breaks.
     */
    private static String compactHeaderValue(String message) {
        return message.replaceAll("[\\r\\n]+", "; ").trim();
    }

    /**
     * Returns the streaming wire format for the given provider. Used by the
     * action handler dispatch to choose between SSE and NDJSON framing.
     *
     * @param provider the LLM provider
     * @return the streaming format (defaults to {@link StreamingFormat#SSE})
     */
    public StreamingFormat streamingFormatFor(Provider provider) {
        return streamingFormatFor(provider, null);
    }

    /**
     * As {@link #streamingFormatFor(Provider)}, but lets a provider that serves several wire APIs
     * pick the format from the request (Bedrock InvokeModel vs ConverseStream framing).
     */
    public StreamingFormat streamingFormatFor(Provider provider, HttpRequest request) {
        return ProviderCodecRegistry.getInstance().lookup(provider)
            .map(codec -> codec.streamingFormat(request))
            .orElse(StreamingFormat.SSE);
    }

    /**
     * Handle streaming LLM response by producing a list of SSE events.
     * Called by HttpActionHandler when streaming is detected.
     *
     * @param httpLlmResponse the LLM response action
     * @param request         the original HTTP request
     * @return list of SSE events to be sent through the SSE handler
     */
    public List<SseEvent> handleStreaming(HttpLlmResponse httpLlmResponse, HttpRequest request) {
        Provider provider = httpLlmResponse.getProvider();
        ProviderCodecRegistry registry = ProviderCodecRegistry.getInstance();
        ProviderCodec codecInstance = registry.lookup(provider)
            .orElseThrow(() -> new IllegalStateException("no codec registered for provider " + provider));

        // a model load or unload generates nothing, so it is not traced, counted or checked
        boolean modelManagement = codecInstance.isModelManagementRequest(request);
        Completion completion = modelManagement
            ? httpLlmResponse.getCompletion()
            : withGeneratedCompletionIfConfigured(httpLlmResponse.getCompletion(), codecInstance, request);
        String model = httpLlmResponse.getModel();
        StreamingPhysics physics = completion.getStreamingPhysics();

        List<SseEvent> events = codecInstance.encodeStreaming(completion, model, physics, request);
        if (provider == Provider.OPENAI_RESPONSES) {
            // recorded from the full stream, before chaos truncation, as the real server keeps
            // the response it generated whether or not the client read all of it
            OpenAiResponsesStore.getInstance().recordStreamedIfStored(
                request != null ? request.getBodyAsText() : null, chainedConversation(codecInstance, request), completion, events);
        }
        if (!modelManagement) {
            validateStructuredOutput(completion, null, provider, request);
            recordCompletionSpan(provider, model, request, completion);
            HttpActionHandler.recordLlmUsageMetrics(provider, model, completion);
        }
        // Rate-limit headers are intentionally not applied on the streaming path: it
        // returns SSE events rather than an HttpResponse, and a quota breach is
        // surfaced as a non-streaming error before streaming begins.
        return applyStreamingChaos(events, httpLlmResponse.getChaos());
    }

    /**
     * Returns an error {@link HttpResponse} (status + optional {@code Retry-After})
     * when the chaos profile triggers a probabilistic error, or {@code null}
     * otherwise. An {@code errorStatus} with no {@code errorProbability} always
     * fires; a fractional probability draws once (reproducible via {@code seed}).
     * Applies to both streaming and non-streaming responses — a provider error is
     * a normal HTTP response, not an SSE stream.
     */
    public HttpResponse chaosErrorResponseOrNull(HttpLlmResponse httpLlmResponse) {
        return chaosErrorResponseOrNull(httpLlmResponse, null);
    }

    /**
     * As {@link #chaosErrorResponseOrNull(HttpLlmResponse)}, with the request so a provider that
     * serves several wire APIs (Bedrock InvokeModel and Converse) gets the matching block body.
     */
    public HttpResponse chaosErrorResponseOrNull(HttpLlmResponse httpLlmResponse, HttpRequest request) {
        return chaosOutcome(httpLlmResponse, request).getErrorResponse();
    }

    /**
     * The chaos decision for one matched LLM request, drawn once: an error response, and for a
     * streaming completion whose provider answers a content-filter block with HTTP 200, the
     * block as the provider's event stream. Both are {@code null} when no chaos fires.
     */
    public static final class ChaosOutcome {
        private static final ChaosOutcome NONE = new ChaosOutcome(null, null);
        private final HttpResponse errorResponse;
        private final List<SseEvent> streamedBlock;

        ChaosOutcome(HttpResponse errorResponse, List<SseEvent> streamedBlock) {
            this.errorResponse = errorResponse;
            this.streamedBlock = streamedBlock;
        }

        /** The non-streaming response; for a streamed block, the same block as one JSON body. */
        public HttpResponse getErrorResponse() {
            return errorResponse;
        }

        /** The content-filter block as stream events, or {@code null} when it is not streamed. */
        public List<SseEvent> getStreamedBlock() {
            return streamedBlock;
        }
    }

    /**
     * As {@link #chaosErrorResponseOrNull(HttpLlmResponse, HttpRequest)}, also returning the
     * streamed form of a content-filter block when the completion streams.
     */
    public ChaosOutcome chaosOutcome(HttpLlmResponse httpLlmResponse, HttpRequest request) {
        LlmChaosProfile chaos = httpLlmResponse.getChaos();
        if (chaos == null) {
            return ChaosOutcome.NONE;
        }
        // Compute token count for this response (used by the token-based quota).
        long requestTokens = estimateTokenCount(httpLlmResponse);

        // Stateful quota (a hard, deterministic rate limit) is checked first and
        // consumes one slot per call. An exceeded quota short-circuits to its own
        // error before any probabilistic-error decision.
        HttpResponse quotaError = quotaErrorResponseOrNull(chaos, requestTokens, httpLlmResponse.getProvider());
        if (quotaError != null) {
            return new ChaosOutcome(quotaError, null);
        }
        // Content-filter block (probabilistic, seeded-deterministic, shares seed) takes
        // priority over the generic probabilistic error: it emits the PROVIDER-CORRECT
        // content-filter response (OpenAI 400 content_filter, Azure filtered innererror,
        // Anthropic refusal, Gemini SAFETY) so agent guardrail-handling can be exercised.
        if (chaos.getContentFilterBlockProbability() != null
            && ChaosProbability.shouldInject(chaos.getContentFilterBlockProbability(), chaos.getSeed())) {
            LlmContentFilterBodies.Block block =
                LlmContentFilterBodies.blockFor(httpLlmResponse.getProvider(), httpLlmResponse.getContentFilter(), request);
            HttpResponse blockResponse = response()
                .withStatusCode(block.getStatusCode())
                .withHeader("content-type", "application/json")
                .withBody(block.getJsonBody());
            return new ChaosOutcome(blockResponse, streamedBlockOrNull(httpLlmResponse, block, request));
        }
        // The probabilistic error fires when an errorStatus is set, OR when only an
        // errorKind is declared (the provider's natural status then supplies the code).
        LlmErrorBody.Kind kind = parseErrorKind(chaos.getErrorKind());
        if (chaos.getErrorStatus() == null && kind == null) {
            return ChaosOutcome.NONE;
        }
        if (!ChaosProbability.shouldInject(chaos.getErrorProbability(), chaos.getSeed())) {
            return ChaosOutcome.NONE;
        }
        Provider provider = httpLlmResponse.getProvider();
        ProviderError providerError = resolveProviderError(provider, kind, chaos.getErrorStatus());
        HttpResponse errorResponse = response()
            .withStatusCode(providerError.statusCode)
            .withHeader("content-type", "application/json")
            .withBody(providerError.jsonBody);
        // applyRateLimitHeaders is the single owner of the Retry-After header (and the
        // provider-specific rate-limit headers) so it is never emitted twice.
        applyRateLimitHeaders(errorResponse, provider, true, chaos);
        return new ChaosOutcome(errorResponse, null);
    }

    private List<SseEvent> streamedBlockOrNull(HttpLlmResponse httpLlmResponse, LlmContentFilterBodies.Block block, HttpRequest request) {
        Completion completion = httpLlmResponse.getCompletion();
        if (completion == null || !Boolean.TRUE.equals(completion.getStreaming())) {
            return null;
        }
        Provider provider = httpLlmResponse.getProvider();
        ProviderCodec codec = provider != null ? ProviderCodecRegistry.getInstance().lookup(provider).orElse(null) : null;
        return LlmContentFilterBodies.streamedBlockOrNull(provider, block, codec,
            httpLlmResponse.getModel(), completion.getStreamingPhysics(), request);
    }

    /** Parse a case-insensitive errorKind string to an {@link LlmErrorBody.Kind}, or null when unset/unrecognised. */
    private static LlmErrorBody.Kind parseErrorKind(String errorKind) {
        if (errorKind == null || errorKind.trim().isEmpty()) {
            return null;
        }
        try {
            return LlmErrorBody.Kind.valueOf(errorKind.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** Resolved (status, body) for an injected error: provider-specific when available, else the generic chaos body. */
    private static final class ProviderError {
        final int statusCode;
        final String jsonBody;

        ProviderError(int statusCode, String jsonBody) {
            this.statusCode = statusCode;
            this.jsonBody = jsonBody;
        }
    }

    private static final String GENERIC_CHAOS_BODY =
        "{\"error\":{\"type\":\"chaos_injected\",\"message\":\"injected chaos error\"}}";

    /**
     * Resolve the body + status for an injected probabilistic error.
     *
     * <p>Two layered behaviours, both emitting a provider-correct error body so client
     * SDK retry/backoff logic can be tested faithfully:
     * <ul>
     *   <li><strong>Explicit {@code errorKind}</strong> ({@code kind != null}) — the
     *       profile declares intent (OVERLOAD/RATE_LIMIT/SERVER_ERROR). The status is the
     *       explicit {@code explicitStatus} when set, otherwise the provider's
     *       <em>natural</em> status for that kind (e.g. Anthropic OVERLOAD → 529). This is
     *       the only path that works with no {@code errorStatus} at all.</li>
     *   <li><strong>Status-derived</strong> ({@code kind == null}, {@code explicitStatus}
     *       non-null) — the kind is derived from the status (429 → rate-limit, 529 →
     *       overloaded, other → server error) and the provider's body emitted at that
     *       exact status. Preserves the automatic provider-shape behaviour for callers
     *       that only set {@code errorStatus}.</li>
     * </ul>
     * For an unknown/null provider (no provider-specific shape) both paths fall back to
     * the generic chaos body; a missing status then defaults to 500.
     */
    private static ProviderError resolveProviderError(Provider provider, LlmErrorBody.Kind kind, Integer explicitStatus) {
        if (kind != null) {
            // Explicit errorKind: the branch helper supplies the provider's natural status for the kind.
            LlmErrorBody.ErrorShape shape = LlmErrorBody.bodyFor(provider, kind);
            if (shape != null) {
                int status = explicitStatus != null ? explicitStatus : shape.getStatusCode();
                return new ProviderError(status, shape.getJsonBody());
            }
        } else if (explicitStatus != null) {
            // No errorKind, but an explicit status: derive the kind from the status and
            // emit the provider-correct body at that status (automatic provider shaping).
            LlmErrorBodies.Kind statusKind = LlmErrorBodies.kindForStatus(explicitStatus);
            String body = LlmErrorBodies.bodyFor(provider, statusKind, explicitStatus, "injected chaos error");
            if (body != null) {
                return new ProviderError(explicitStatus, body);
            }
        }
        int status = explicitStatus != null ? explicitStatus : 500;
        return new ProviderError(status, GENERIC_CHAOS_BODY);
    }

    /**
     * Return the completion to encode for this request, with approximate token
     * {@code usage} inferred when opted in via {@code mockserver.llmInferUsageEnabled}.
     * When inference applies, a <strong>shallow copy</strong> of the completion is
     * returned carrying approximate {@code prompt_tokens} / {@code completion_tokens}
     * so the encoded response carries plausible numbers instead of zeros. The prompt
     * estimate is derived from <em>this</em> request's decoded conversation; the
     * completion estimate from the response text + tool-call arguments. Both are
     * <strong>approximate</strong> (see {@link org.mockserver.llm.TokenCounter}), never a
     * provider's exact billing.
     * <p>
     * The shared expectation {@link Completion} is <strong>never mutated</strong>: a
     * per-request copy carries the inferred usage, so concurrent requests are safe and a
     * request-dependent prompt estimate is recomputed every time rather than cached onto
     * the shared object.
     * <p>
     * Returns the input completion unchanged (default) when the feature is off, the
     * completion is {@code null}, or {@code usage} is already present with a non-zero input
     * or output count — so existing responses are unchanged unless the user opts in and
     * a declared usage is never overwritten. Fail-soft: any error decoding the request
     * yields a 0 prompt estimate rather than breaking the response.
     */
    Completion withInferredUsageIfEnabled(Completion completion, ProviderCodec codec, HttpRequest request) {
        boolean inferUsage = configuration != null
            ? configuration.llmInferUsageEnabled()
            : ConfigurationProperties.llmInferUsageEnabled();
        if (completion == null || !inferUsage) {
            return completion;
        }
        Usage existing = completion.getUsage();
        if (existing != null
            && ((existing.getInputTokens() != null && existing.getInputTokens() > 0)
            || (existing.getOutputTokens() != null && existing.getOutputTokens() > 0))) {
            // already declared (non-zero) — never overwrite user-supplied usage
            return completion;
        }
        int promptTokens = 0;
        try {
            ParsedConversation conversation = codec.decode(request);
            promptTokens = TokenCounter.estimatePromptTokens(conversation);
        } catch (Exception e) {
            // fail-soft: a request that cannot be decoded simply yields a 0 prompt estimate
            promptTokens = 0;
        }
        int completionTokens = TokenCounter.estimateCompletionTokens(completion.getText(), completion.getToolCalls());
        Usage inferred = (existing != null ? copyUsage(existing) : Usage.usage())
            .withInputTokens(promptTokens)
            .withOutputTokens(completionTokens);
        return copyCompletion(completion).withUsage(inferred);
    }

    /** Shallow copy of a {@link Completion} so per-request usage inference never mutates the shared expectation model. */
    private static Completion copyCompletion(Completion source) {
        return Completion.completion()
            .withText(source.getText())
            .withToolCalls(source.getToolCalls())
            .withToolChoice(source.getToolChoice())
            .withStopReason(source.getStopReason())
            .withUsage(source.getUsage())
            .withStreaming(source.getStreaming())
            .withStreamingPhysics(source.getStreamingPhysics())
            .withOutputSchema(source.getOutputSchema())
            .withEnforceOutputSchema(source.getEnforceOutputSchema())
            .withModel(source.getModel())
            .withReasoningText(source.getReasoningText())
            .withReasoningSignature(source.getReasoningSignature())
            .withGenerate(source.getGenerate());
    }
    /** Copy of a {@link Usage} preserving any cached/reasoning sub-counts already declared. */
    private static Usage copyUsage(Usage source) {
        return Usage.usage()
            .withInputTokens(source.getInputTokens())
            .withOutputTokens(source.getOutputTokens())
            .withCachedInputTokens(source.getCachedInputTokens())
            .withCacheCreationTokens(source.getCacheCreationTokens())
            .withReasoningTokens(source.getReasoningTokens());
    }

    /**
     * Estimate the token count for the given LLM response. If the completion has
     * a {@link Usage} with inputTokens/outputTokens, returns their sum. Otherwise
     * falls back to {@code ceil(text.length() / 4)} as a rough approximation.
     * Returns 0 for embeddings or missing completions.
     * <p>
     * In generate mode the expectation's completion has no text and usually no
     * declared usage, so the completion's <em>expected</em> output size is used:
     * the {@code outputTokens} median when declared, else 0 — charged against the
     * token-based chaos quota per request.
     */
    static long estimateTokenCount(HttpLlmResponse httpLlmResponse) {
        Completion completion = httpLlmResponse.getCompletion();
        if (completion == null) {
            return 0L;
        }
        Usage usage = completion.getUsage();
        if (usage != null) {
            int input = usage.getInputTokens() != null ? usage.getInputTokens() : 0;
            int output = usage.getOutputTokens() != null ? usage.getOutputTokens() : 0;
            if (input > 0 || output > 0) {
                return (long) input + output;
            }
        }
        // Fall back to a rough character-based estimate
        String text = completion.getText();
        if (text != null && !text.isEmpty()) {
            return (long) Math.ceil(text.length() / 4.0);
        }
        if (completion.getGenerate() != null
            && completion.getGenerate().getOutputTokens() != null
            && completion.getGenerate().getOutputTokens().getMedian() != null) {
            return Math.round(completion.getGenerate().getOutputTokens().getMedian());
        }
        return 0L;
    }

    /**
     * If the profile declares a stateful request-count quota and/or a token-based
     * quota and this request exceeds either within its current window, return the
     * quota error response (status {@code quotaErrorStatus}, default 429, plus the
     * {@code Retry-After} header when set); otherwise {@code null}.
     * <p>
     * The request-count quota is checked first. If it passes, the token-based quota
     * is checked (charging {@code requestTokens} against the token window). Both
     * quotas use independent counters within the registry, namespaced by suffix
     * ({@code quotaName} for request-count, {@code quotaName + ":tokens"} for
     * token-based).
     * <p>
     * Called once per matched LLM request (via {@link #chaosOutcome})
     * regardless of whether the response path ultimately delivers an LLM payload,
     * so the count reflects requests received, not payloads returned.
     *
     * @param chaos         the chaos profile (never null)
     * @param requestTokens estimated token count for this response
     */
    HttpResponse quotaErrorResponseOrNull(LlmChaosProfile chaos, long requestTokens, Provider provider) {
        // --- request-count quota ---
        if (chaos.getQuotaName() != null && chaos.getQuotaLimit() != null && chaos.getQuotaWindowMillis() != null) {
            boolean allowed = LlmQuotaRegistry.getInstance()
                .tryAcquire(chaos.getQuotaName(), chaos.getQuotaLimit(), chaos.getQuotaWindowMillis());
            if (!allowed) {
                return buildQuotaErrorResponse(chaos, "quota_exceeded", "LLM request quota exceeded", provider);
            }
        }
        // --- token-based quota ---
        // Note: when the request-count quota fires above, this block is skipped and
        // the token window is intentionally not charged — a blocked request produces
        // no response and therefore consumes no tokens.
        if (chaos.getQuotaName() != null && chaos.getTokenQuotaLimit() != null && chaos.getTokenQuotaWindowMillis() != null) {
            boolean allowed = LlmQuotaRegistry.getInstance()
                .tryAcquire(chaos.getQuotaName() + ":tokens", chaos.getTokenQuotaLimit(), chaos.getTokenQuotaWindowMillis(), requestTokens);
            if (!allowed) {
                return buildQuotaErrorResponse(chaos, "token_quota_exceeded", "LLM token quota exceeded", provider);
            }
        }
        return null;
    }

    private HttpResponse buildQuotaErrorResponse(LlmChaosProfile chaos, String errorType, String message, Provider provider) {
        // When the profile opts in to an explicit errorKind (e.g. forcing OVERLOAD for a
        // quota breach instead of the default rate-limit shape), emit the active provider's
        // distinct error body at that kind's natural status, so client SDK backoff logic
        // can parse it. An explicit quotaErrorStatus still overrides the status.
        LlmErrorBody.Kind explicitKind = parseErrorKind(chaos.getErrorKind());
        if (explicitKind != null) {
            LlmErrorBody.ErrorShape shape = LlmErrorBody.bodyFor(provider, explicitKind);
            if (shape != null) {
                int providerStatus = chaos.getQuotaErrorStatus() != null ? chaos.getQuotaErrorStatus() : shape.getStatusCode();
                HttpResponse providerResponse = response()
                    .withStatusCode(providerStatus)
                    .withHeader("content-type", "application/json")
                    .withBody(shape.getJsonBody());
                applyRateLimitHeaders(providerResponse, provider, true, chaos);
                return providerResponse;
            }
        }
        int status = chaos.getQuotaErrorStatus() != null ? chaos.getQuotaErrorStatus() : 429;
        // No explicit errorKind: a quota breach is a rate-limit error, so emit the
        // provider-correct error body derived from the status (so client SDK retry/backoff
        // logic reads the right error.type/code), falling back to the generic body — which
        // preserves the distinct quota_exceeded / token_quota_exceeded error types — for an
        // unknown/null provider.
        LlmErrorBodies.Kind statusKind = LlmErrorBodies.kindForStatus(status);
        String body = LlmErrorBodies.bodyFor(provider, statusKind, status, message);
        if (body == null) {
            body = "{\"error\":{\"type\":\"" + errorType + "\",\"message\":\"" + message + "\"}}";
        }
        HttpResponse errorResponse = response()
            .withStatusCode(status)
            .withHeader("content-type", "application/json")
            .withBody(body);
        // applyRateLimitHeaders is the single owner of the Retry-After header (and the
        // provider-specific rate-limit headers) so it is never emitted twice.
        applyRateLimitHeaders(errorResponse, provider, true, chaos);
        return errorResponse;
    }

    /**
     * Apply streaming chaos to the SSE event list: mid-stream truncation (keep a
     * leading fraction of events) and/or a malformed (broken-JSON) trailing
     * chunk. Deterministic. Returns the input unchanged when no streaming chaos
     * is configured.
     */
    List<SseEvent> applyStreamingChaos(List<SseEvent> events, LlmChaosProfile chaos) {
        if (chaos == null || events == null || events.isEmpty()) {
            return events;
        }
        List<SseEvent> result = events;
        if (chaos.getTruncateMode() == LlmChaosProfile.TruncateMode.MID_STREAM) {
            double fraction = chaos.getTruncateAtFraction() != null ? chaos.getTruncateAtFraction() : 0.5;
            if (fraction < 0.0) {
                fraction = 0.0;
            }
            if (fraction > 1.0) {
                fraction = 1.0;
            }
            int keep = (int) Math.floor(events.size() * fraction);
            result = new ArrayList<>(events.subList(0, keep));
        }
        if (Boolean.TRUE.equals(chaos.getMalformedSse())) {
            // append a deliberately broken-JSON chunk so the client must handle a
            // corrupt mid-stream event (missing closing brace)
            result = new ArrayList<>(result);
            result.add(SseEvent.sseEvent().withData("{\"malformed\":true"));
        }
        return result;
    }

    /**
     * Add Azure OpenAI content-filter annotations to a non-streaming Azure response:
     * {@code content_filter_results} on each {@code choices[]} entry and a top-level
     * {@code prompt_filter_results} array. No-op unless the provider is
     * {@link Provider#AZURE_OPENAI} and a {@link LlmContentFilter} is configured. Fail-soft:
     * any parse/serialize error leaves the response body unchanged so annotations can never
     * break the response.
     */
    void applyAzureContentFilterAnnotations(HttpResponse encoded, Provider provider, LlmContentFilter contentFilter) {
        if (encoded == null || provider != Provider.AZURE_OPENAI || contentFilter == null) {
            return;
        }
        String body = encoded.getBodyAsString();
        if (body == null || body.isEmpty()) {
            return;
        }
        try {
            JsonNode parsed = OBJECT_MAPPER.readTree(body);
            if (parsed == null || !parsed.isObject()) {
                return;
            }
            ObjectNode root = (ObjectNode) parsed;
            // Per-choice content_filter_results (a fresh node per choice so trees are not shared).
            JsonNode choices = root.get("choices");
            if (choices != null && choices.isArray()) {
                for (JsonNode choice : choices) {
                    if (choice.isObject()) {
                        ((ObjectNode) choice).set("content_filter_results", LlmContentFilterBodies.azureFilterResults(contentFilter));
                    }
                }
            }
            // Top-level prompt_filter_results: [{"prompt_index":0,"content_filter_results":{..}}].
            ArrayNode promptFilterResults = root.putArray("prompt_filter_results");
            ObjectNode promptEntry = promptFilterResults.addObject();
            promptEntry.put("prompt_index", 0);
            promptEntry.set("content_filter_results", LlmContentFilterBodies.azureFilterResults(contentFilter));

            encoded.withBody(OBJECT_MAPPER.writeValueAsString(root));
        } catch (Exception e) {
            // fail-soft: leave the response body unchanged
            if (mockServerLogger.isEnabledForInstance(Level.WARN)) {
                mockServerLogger.logEvent(
                    new LogEntry()
                        .setType(EXPECTATION_RESPONSE)
                        .setLogLevel(Level.WARN)
                        .setMessageFormat("could not add Azure content-filter annotations — leaving response unchanged:{}")
                        .setArguments(e.getMessage())
                );
            }
        }
    }

    /**
     * Apply provider-correct rate-limit headers to the response. When a quota is
     * configured, derives {@code requestLimit} and {@code resetSeconds} from the
     * chaos profile; {@code requestRemaining} is {@code null} (we cannot cheaply
     * compute it without re-reading the registry window, and omitting it is safe —
     * real providers sometimes omit it too). Delegates to the pure helper
     * {@link LlmRateLimitHeaders#headersFor}.
     */
    private void applyRateLimitHeaders(HttpResponse response, Provider provider, boolean limited, LlmChaosProfile chaos) {
        if (chaos == null || provider == null) {
            return;
        }
        Integer requestLimit = chaos.getQuotaLimit();
        // Reset window comes from whichever quota is configured (request-count first,
        // then token-based), falling back to a numeric Retry-After value.
        Long resetSeconds = null;
        if (chaos.getQuotaWindowMillis() != null) {
            resetSeconds = Math.max(1, chaos.getQuotaWindowMillis() / 1000);
        } else if (chaos.getTokenQuotaWindowMillis() != null) {
            resetSeconds = Math.max(1, chaos.getTokenQuotaWindowMillis() / 1000);
        } else if (chaos.getRetryAfter() != null && !chaos.getRetryAfter().isEmpty()) {
            try {
                resetSeconds = Long.parseLong(chaos.getRetryAfter());
            } catch (NumberFormatException ignored) {
                // retryAfter may be an HTTP-date string — handled via the literal Retry-After below
            }
        }
        Integer requestRemaining = limited ? 0 : null;
        long nowEpochSecond = System.currentTimeMillis() / 1000;
        Map<String, String> headers = LlmRateLimitHeaders.headersFor(
            provider, requestLimit, requestRemaining, resetSeconds, nowEpochSecond, limited);
        for (Map.Entry<String, String> entry : headers.entrySet()) {
            response.withHeader(entry.getKey(), entry.getValue());
        }
        // Retry-After is a standard HTTP header (not provider-specific), so it is set
        // here for every provider — including Gemini, Bedrock, and Ollama, which have
        // no provider-specific rate-limit headers. Prefer the literal configured
        // Retry-After (which may be an HTTP-date), else the computed reset seconds.
        // Single source → no duplicate header.
        if (limited) {
            String retryAfter = chaos.getRetryAfter() != null && !chaos.getRetryAfter().isEmpty()
                ? chaos.getRetryAfter()
                : (resetSeconds != null ? String.valueOf(resetSeconds) : null);
            if (retryAfter != null) {
                response.withHeader("Retry-After", retryAfter);
            }
        }
    }

    /** The embedding limits from the server's configuration, so a runtime change takes effect. */
    private EmbeddingWire.Limits embeddingLimits() {
        int maxResponseValues = configuration != null
            ? configuration.llmEmbeddingMaxResponseValues()
            : ConfigurationProperties.llmEmbeddingMaxResponseValues();
        int maxInputCharacters = configuration != null
            ? configuration.llmEmbeddingMaxInputCharacters()
            : ConfigurationProperties.llmEmbeddingMaxInputCharacters();
        return new EmbeddingWire.Limits(Math.max(1, maxResponseValues), Math.max(1, maxInputCharacters));
    }

    /**
     * Extracts the candidate documents from a rerank request body. Both Cohere
     * and Voyage use a {@code documents} array; entries may be plain strings or
     * objects carrying a {@code text} field (Cohere's structured form). Returns
     * an empty list when the body is absent or unparseable.
     */
    private List<String> extractDocumentsFromRequest(HttpRequest request) {
        List<String> documents = new ArrayList<>();
        if (request.getBody() != null) {
            String bodyString = request.getBodyAsText();
            try {
                JsonNode bodyNode = OBJECT_MAPPER.readTree(bodyString);
                JsonNode docsNode = bodyNode.get("documents");
                if (docsNode != null && docsNode.isArray()) {
                    for (JsonNode doc : docsNode) {
                        if (doc.isTextual()) {
                            documents.add(doc.asText());
                        } else if (doc.isObject() && doc.has("text")) {
                            documents.add(doc.get("text").asText());
                        } else {
                            documents.add(doc.toString());
                        }
                    }
                }
            } catch (Exception e) {
                // not parseable, return what we have (possibly empty)
            }
        }
        return documents;
    }

    private String supportedProvidersJson() {
        List<String> names = ProviderCodecRegistry.getInstance().supportedProviderNames();
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < names.size(); i++) {
            if (i > 0) {
                sb.append(",");
            }
            sb.append("\"").append(names.get(i)).append("\"");
        }
        sb.append("]");
        return sb.toString();
    }
}
