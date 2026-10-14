package org.mockserver.netty.mcp;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.apache.commons.lang3.tuple.Pair;
import org.mockserver.client.LlmConversationBuilder;
import org.mockserver.client.LlmFailoverBuilder;
import org.mockserver.client.TurnBuilder;
import org.mockserver.lifecycle.LifeCycle;
import org.mockserver.configuration.ConfigurationProperties;
import org.mockserver.httpclient.NettyHttpClient;
import org.mockserver.llm.IsolationSource;
import org.mockserver.llm.ProviderCodecRegistry;
import org.mockserver.llm.client.LlmProviderSniffer;
import org.mockserver.llm.analysis.AgentRunAnalyzer;
import org.mockserver.llm.client.LlmBackend;
import org.mockserver.llm.client.LlmBackendResolver;
import org.mockserver.llm.client.LlmClient;
import org.mockserver.llm.client.LlmClientRegistry;
import org.mockserver.llm.client.NettyHttpClientLlmTransport;
import org.mockserver.llm.cost.LlmPricing;
import org.mockserver.validator.jsonschema.JsonSchemaExpectationValidator;
import org.mockserver.validator.jsonschema.JsonSchemaValidator;
import org.mockserver.llm.drift.DriftDetector;
import org.mockserver.llm.drift.DriftReport;
import org.mockserver.matchers.MatchType;
import org.mockserver.log.model.LogEntry;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.matchers.MismatchRemediation;
import org.mockserver.matchers.MatchDifference;
import org.mockserver.matchers.TimeToLive;
import org.mockserver.matchers.Times;
import org.mockserver.mock.CassetteRegistry;
import org.mockserver.filters.TransportHeaderFilter;
import org.mockserver.mock.Expectation;
import org.mockserver.mock.HttpState;
import org.mockserver.mock.MockMode;
import org.mockserver.mock.mcp.McpToolSchemaGenerator;
import org.mockserver.mock.OpenAPIExpectation;
import org.mockserver.model.*;
import org.mockserver.openapi.OpenAPILoadProblems;
import org.mockserver.openapi.OpenApiContractTest;
import org.mockserver.openapi.OpenApiResiliencyTest;
import org.mockserver.openapi.OpenApiTrafficValidator;
import org.mockserver.proxyconfiguration.InetAddressValidator;
import org.mockserver.scheduler.Scheduler;
import org.mockserver.serialization.ExpectationSerializer;
import org.mockserver.serialization.LogEventRequestAndResponseSerializer;
import org.mockserver.serialization.ObjectMapperFactory;
import org.mockserver.serialization.RequestDefinitionSerializer;
import org.mockserver.fixture.FixtureRedactor;
import org.mockserver.fixture.SseAwareExpectationConverter;
import org.mockserver.verify.Verification;
import org.mockserver.verify.VerificationSequence;
import org.mockserver.verify.VerificationTimes;
import org.slf4j.event.Level;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import static org.mockserver.model.HttpRequest.request;
import static org.mockserver.model.HttpResponse.response;

public class McpToolRegistry {

    private final HttpState httpState;
    private final LifeCycle server;
    private final ObjectMapper objectMapper;
    private final MockServerLogger mockServerLogger;
    private final Map<String, ToolDefinition> tools;
    private ExpectationSerializer expectationSerializer;
    private RequestDefinitionSerializer requestDefinitionSerializer;
    private JsonSchemaExpectationValidator expectationValidator;
    private final McpToolSchemaGenerator mcpToolSchemaGenerator = new McpToolSchemaGenerator();

    public McpToolRegistry(HttpState httpState, LifeCycle server) {
        this.httpState = httpState;
        this.server = server;
        this.objectMapper = ObjectMapperFactory.createObjectMapper();
        this.mockServerLogger = httpState.getMockServerLogger();
        this.tools = new LinkedHashMap<>();
        registerAllTools();
    }

    private ExpectationSerializer getExpectationSerializer() {
        if (expectationSerializer == null) {
            expectationSerializer = new ExpectationSerializer(mockServerLogger, httpState.getConfiguration());
        }
        return expectationSerializer;
    }

    private RequestDefinitionSerializer getRequestDefinitionSerializer() {
        if (requestDefinitionSerializer == null) {
            requestDefinitionSerializer = new RequestDefinitionSerializer(mockServerLogger);
        }
        return requestDefinitionSerializer;
    }

    public Map<String, ToolDefinition> getTools() {
        return tools;
    }

    /**
     * Names of the tools that MUTATE control-plane state (add/upsert expectations, clear,
     * reset, register service chaos, stop the server, load/record fixtures into state).
     * Every other registered tool only READS state (retrieve/list/status/verify/debug/
     * explain/analyse) or runs a self-contained test against a separate target service
     * without changing MockServer's own state, and so requires only the READ role.
     * <p>
     * This split mirrors the HTTP control plane's read/mutate classification
     * ({@code HttpState.isControlPlaneRead}): an MCP {@code tools/call} arrives as a single
     * JSON-RPC POST, so the HTTP verb cannot distinguish read from mutate per tool — the
     * classification has to be per tool. Used to choose the role required when control-plane
     * authorization ({@code controlPlaneAuthorizationEnabled}) is enforced for tool calls.
     */
    private static final java.util.Set<String> MUTATING_TOOLS = java.util.Set.of(
        "create_expectation",
        "create_forward_expectation",
        "create_expectation_from_openapi",
        "create_expectations_from_recorded_traffic",
        "clear_expectations",
        "reset",
        "manage_service_chaos",
        "stop_server",
        "raw_expectation",
        "load_expectations_from_file",
        "record_llm_fixtures",
        "mock_llm_completion",
        "create_llm_conversation",
        "mock_llm_failover",
        "mock_adversarial_llm_response",
        "set_operating_mode",
        "promote_recordings",
        "test_template"
    );

    /**
     * Whether the named tool mutates control-plane state (and so requires the MUTATE role
     * when control-plane authorization is enforced). Unknown tool names are treated as
     * mutating (fail-closed): a tool that is not explicitly classified as a read must not
     * slip through with only the READ role. Implemented as a deny-list against the explicit
     * {@link #READING_TOOLS} set so an unclassified (e.g. newly added) tool defaults to MUTATE.
     */
    public static boolean isMutatingTool(String toolName) {
        return !READING_TOOLS.contains(toolName);
    }

    /** Package-private view of the explicitly read-classified tools, for the classification test. */
    static java.util.Set<String> readingToolNames() {
        return READING_TOOLS;
    }

    /** Package-private view of the explicitly mutate-classified tools, for the classification test. */
    static java.util.Set<String> mutatingToolNames() {
        return MUTATING_TOOLS;
    }

    /**
     * Names of the tools that only READ state (or run a self-contained test against a
     * separate target service without changing MockServer's own state). Every registered
     * tool is in exactly one of {@link #MUTATING_TOOLS} or this set; the two are asserted
     * disjoint-and-complete by {@code McpToolRegistryTest}.
     */
    private static final java.util.Set<String> READING_TOOLS = java.util.Set.of(
        "verify_request",
        "verify_request_sequence",
        "verify_tool_call",
        "verify_structured_output",
        "verify_cost_budget",
        "verify_traffic_against_openapi",
        "retrieve_recorded_requests",
        "retrieve_logs",
        "retrieve_request_responses",
        "get_status",
        "debug_request_mismatch",
        "explain_unmatched_requests",
        "explain_request",
        "diagnose_config",
        "explain_forward_failure",
        "lint_expectations",
        "suggest_expectation_fix",
        "explain_agent_run",
        "export_optimisation_report",
        "diff_agent_runs",
        "detect_llm_drift",
        "list_mock_tools",
        "list_expectations",
        "raw_retrieve",
        "raw_verify",
        "run_contract_test",
        "run_resiliency_test",
        "run_mcp_contract_test"
    );

    public JsonNode callTool(String name, JsonNode params) {
        ToolDefinition tool = tools.get(name);
        if (tool == null) {
            return null;
        }
        return tool.handler.apply(params != null ? params : objectMapper.createObjectNode());
    }

    private void registerAllTools() {
        registerCreateExpectation();
        registerVerifyRequest();
        registerRetrieveRecordedRequests();
        registerRetrieveLogs();
        registerClearExpectations();
        registerReset();
        registerManageServiceChaos();
        registerGetStatus();
        registerVerifyRequestSequence();
        registerRetrieveRequestResponses();
        registerCreateForwardExpectation();
        registerDebugRequestMismatch();
        registerExplainUnmatchedRequests();
        registerExplainRequest();
        registerDiagnoseConfig();
        registerExplainForwardFailure();
        registerTestTemplate();
        registerLintExpectations();
        registerSuggestExpectationFix();
        registerCreateExpectationFromOpenApi();
        registerCreateExpectationsFromRecordedTraffic();
        registerStopServer();
        registerRawExpectation();
        registerRawRetrieve();
        registerRawVerify();
        registerVerifyTrafficAgainstOpenApi();
        registerRunContractTest();
        registerRunResiliencyTest();
        registerRunMcpContractTest();
        registerRecordLlmFixtures();
        registerLoadExpectationsFromFile();
        registerMockLlmCompletion();
        registerCreateLlmConversation();
        registerMockLlmFailover();
        registerVerifyToolCall();
        registerVerifyStructuredOutput();
        registerVerifyCostBudget();
        registerExplainAgentRun();
        registerExportOptimisationReport();
        registerDiffAgentRuns();
        registerDetectLlmDrift();
        registerMockAdversarialLlmResponse();
        registerListMockTools();
        registerListExpectations();
        registerSetOperatingMode();
        registerPromoteRecordings();
    }

    private void registerListMockTools() {
        ObjectNode schema = objectMapper.createObjectNode();
        schema.put("type", "object");
        schema.putObject("properties");

        tools.put("list_mock_tools", new ToolDefinition(
            "list_mock_tools",
            "Generates Model Context Protocol tool definitions from the current mock expectations, so an agent can discover the mocked endpoints as callable tools. Each response expectation becomes one tool (name derived from method and path, inputSchema for query parameters and request body, plus a _mockserver annotation with the target method/path).",
            schema,
            this::handleListMockTools
        ));
    }

    private JsonNode handleListMockTools(JsonNode params) {
        try {
            ArrayNode generated = mcpToolSchemaGenerator
                .generate(httpState.getRequestMatchers().retrieveActiveExpectations(null));
            ObjectNode resultNode = objectMapper.createObjectNode();
            resultNode.set("tools", generated);
            resultNode.put("count", generated.size());
            return resultNode;
        } catch (Exception e) {
            return errorResult("Failed to generate mock tools", e);
        }
    }

    private void registerListExpectations() {
        ObjectNode schema = objectMapper.createObjectNode();
        schema.put("type", "object");
        ObjectNode properties = schema.putObject("properties");
        properties.putObject("method").put("type", "string").put("description", "Optionally filter active expectations to those whose request matcher targets this HTTP method");
        properties.putObject("path").put("type", "string").put("description", "Optionally filter active expectations to those whose request matcher targets this path");

        tools.put("list_expectations", new ToolDefinition(
            "list_expectations",
            "Lists the currently active mock expectations, optionally filtered by a request method and/or path. "
                + "Returns each expectation in the full MockServer JSON format (including its id), so an agent can inspect, "
                + "modify (via create_expectation/raw_expectation) or clear (via clear_expectations) what is mocked. "
                + "Equivalent to PUT /mockserver/retrieve?type=ACTIVE_EXPECTATIONS.",
            schema,
            this::handleListExpectations
        ));
    }

    private JsonNode handleListExpectations(JsonNode params) {
        try {
            HttpRequest filterRequest = request();
            JsonNode methodNode = params.path("method");
            if (!methodNode.isMissingNode() && !methodNode.isNull()) {
                filterRequest.withMethod(methodNode.asText());
            }
            JsonNode pathNode = params.path("path");
            if (!pathNode.isMissingNode() && !pathNode.isNull()) {
                filterRequest.withPath(pathNode.asText());
            }

            HttpRequest retrieveRequest = request()
                .withMethod("PUT")
                .withPath("/mockserver/retrieve")
                .withQueryStringParameter("type", "ACTIVE_EXPECTATIONS")
                .withQueryStringParameter("format", "JSON")
                .withBody(getRequestDefinitionSerializer().serialize(filterRequest));

            HttpResponse retrieveResponse = httpState.retrieve(retrieveRequest);
            String body = retrieveResponse.getBodyAsString();

            ObjectNode resultNode = objectMapper.createObjectNode();
            if (body != null && !body.isEmpty()) {
                JsonNode expectations = objectMapper.readTree(body);
                resultNode.set("expectations", expectations);
                resultNode.put("count", expectations.isArray() ? expectations.size() : 0);
            } else {
                resultNode.set("expectations", objectMapper.createArrayNode());
                resultNode.put("count", 0);
            }
            return resultNode;
        } catch (Exception e) {
            return errorResult("Failed to list expectations", e);
        }
    }

    private void registerSetOperatingMode() {
        ObjectNode schema = objectMapper.createObjectNode();
        schema.put("type", "object");
        ObjectNode properties = schema.putObject("properties");
        ObjectNode modeProp = properties.putObject("mode");
        modeProp.put("type", "string").put("description", "Operating mode: SIMULATE (match mocks, 404 unmatched), SPY (match mocks, forward+record unmatched), or CAPTURE (forward+record everything)");
        modeProp.putArray("enum").add("SIMULATE").add("SPY").add("CAPTURE");
        schema.putArray("required").add("mode");

        tools.put("set_operating_mode", new ToolDefinition(
            "set_operating_mode",
            "Sets the high-level operating mode of MockServer in one switch: SIMULATE (return mocks, 404 on no match), "
                + "SPY (return mocks but forward+record requests that match no expectation), or CAPTURE (forward+record all traffic). "
                + "SPY/CAPTURE let an agent record real upstream traffic that can then be turned into mocks via promote_recordings. "
                + "Equivalent to PUT /mockserver/mode?mode=<MODE>.",
            schema,
            this::handleSetOperatingMode
        ));
    }

    private JsonNode handleSetOperatingMode(JsonNode params) {
        try {
            JsonNode modeNode = params.path("mode");
            if (modeNode.isMissingNode() || modeNode.isNull() || !modeNode.isTextual()) {
                return errorResult("'mode' is required (one of SIMULATE, SPY, CAPTURE)");
            }
            MockMode mode;
            try {
                mode = MockMode.parse(modeNode.asText());
            } catch (IllegalArgumentException iae) {
                return errorResult(iae.getMessage());
            }
            httpState.setMode(mode);

            ObjectNode resultNode = objectMapper.createObjectNode();
            resultNode.put("status", "ok");
            resultNode.put("mode", mode.name());
            resultNode.put("proxyUnmatchedRequests", mode.proxyUnmatchedRequests());
            return resultNode;
        } catch (Exception e) {
            return errorResult("Failed to set operating mode", e);
        }
    }

    private void registerPromoteRecordings() {
        ObjectNode schema = objectMapper.createObjectNode();
        schema.put("type", "object");
        ObjectNode properties = schema.putObject("properties");
        properties.putObject("method").put("type", "string").put("description", "Filter recorded traffic by HTTP method (e.g. GET, POST)");
        properties.putObject("path").put("type", "string").put("description", "Filter recorded traffic by request path (e.g. /api/users)");
        properties.putObject("consolidate").put("type", "boolean").put("description", "Collapse duplicate recorded exchanges into consolidated reusable mocks (default true); when false each recording is promoted verbatim");
        properties.putObject("parameterize").put("type", "boolean").put("description", "When consolidating, generalise volatile path/query/header/body values so a single recorded id does not pin the mock (default true)");
        properties.putObject("redactSensitiveData").put("type", "boolean").put("description", "Redact secrets (auth headers, common secret body fields) from promoted mocks (default true)");

        tools.put("promote_recordings", new ToolDefinition(
            "promote_recordings",
            "Promotes traffic already recorded by MockServer's forwarding/proxy mode (e.g. after set_operating_mode SPY/CAPTURE) "
                + "into ACTIVE mock expectations in one step, so an agent can \"record then mock\". Redacts secrets, and by default "
                + "consolidates and parameterizes the recordings into reusable mocks (unlimited times). "
                + "Equivalent to PUT /mockserver/recordings/promote.",
            schema,
            this::handlePromoteRecordings
        ));
    }

    private JsonNode handlePromoteRecordings(JsonNode params) {
        try {
            HttpRequest filter = request();
            JsonNode methodNode = params.path("method");
            if (!methodNode.isMissingNode() && !methodNode.isNull()) {
                filter.withMethod(methodNode.asText());
            }
            JsonNode pathNode = params.path("path");
            if (!pathNode.isMissingNode() && !pathNode.isNull()) {
                filter.withPath(pathNode.asText());
            }

            boolean consolidate = params.path("consolidate").asBoolean(true);
            boolean parameterize = params.path("parameterize").asBoolean(true);
            boolean redact = params.path("redactSensitiveData").asBoolean(true);
            org.mockserver.imports.ImportRedaction.Options redactionOptions = redact
                ? org.mockserver.imports.ImportRedaction.Options.enabled()
                : org.mockserver.imports.ImportRedaction.Options.disabled();

            List<Expectation> activated = httpState.promoteRecordings(filter, consolidate, parameterize, redactionOptions);

            ObjectNode resultNode = objectMapper.createObjectNode();
            if (activated.isEmpty()) {
                resultNode.put("status", "no_recorded_traffic");
                resultNode.put("message", "No recorded traffic found matching the filter. "
                    + "Ensure MockServer has forwarded requests (e.g. via set_operating_mode SPY/CAPTURE or create_forward_expectation) before calling this tool.");
                resultNode.put("count", 0);
                return resultNode;
            }
            resultNode.put("status", "promoted");
            resultNode.put("count", activated.size());
            ArrayNode ids = resultNode.putArray("ids");
            for (Expectation exp : activated) {
                ids.add(exp.getId());
            }
            return resultNode;
        } catch (Exception e) {
            return errorResult("Failed to promote recordings", e);
        }
    }

    private void registerCreateExpectation() {
        ObjectNode schema = objectMapper.createObjectNode();
        schema.put("type", "object");
        ObjectNode properties = schema.putObject("properties");
        properties.putObject("method").put("type", "string").put("description", "HTTP method (GET, POST, PUT, DELETE, etc.)");
        properties.putObject("path").put("type", "string").put("description", "Request path to match");
        properties.putObject("statusCode").put("type", "integer").put("description", "Response status code (default 200)");
        ObjectNode responseBodyProp = properties.putObject("responseBody");
        responseBodyProp.put("description", "Response body as string or object");
        ArrayNode anyOf = responseBodyProp.putArray("anyOf");
        anyOf.add(objectMapper.createObjectNode().put("type", "string"));
        anyOf.add(objectMapper.createObjectNode().put("type", "object"));
        properties.putObject("responseHeaders").put("type", "object").put("description", "Response headers as key-value pairs");
        properties.putObject("times").put("type", "integer").put("description", "Number of times this expectation should match");
        properties.putObject("timeToLive").put("type", "string").put("description", "Time to live e.g. '60 SECONDS'");
        {
            ObjectNode chaosProp = properties.putObject("chaos");
            chaosProp.put("type", "object").put("description", "Declarative HTTP chaos/fault injection profile for probabilistic errors, latency, and count-based stateful faults");
            ObjectNode chaosProps = chaosProp.putObject("properties");
            chaosProps.putObject("errorStatus").put("type", "integer").put("description", "HTTP status to inject on error (e.g. 500, 503, 429)");
            chaosProps.putObject("errorProbability").put("type", "number").put("description", "Probability of injecting an error (0.0-1.0)");
            chaosProps.putObject("retryAfter").put("type", "string").put("description", "Optional Retry-After header value on injected error");
            ObjectNode latencyProp = chaosProps.putObject("latency");
            latencyProp.put("type", "object").put("description", "Optional injected latency delay");
            ObjectNode latencyProps = latencyProp.putObject("properties");
            latencyProps.putObject("timeUnit").put("type", "string").put("description", "Time unit (e.g. MILLISECONDS, SECONDS)");
            latencyProps.putObject("value").put("type", "integer").put("description", "Delay value");
            chaosProps.putObject("dropConnectionProbability").put("type", "number").put("description", "Probability (0.0-1.0) of dropping the TCP connection without sending any response");
            chaosProps.putObject("seed").put("type", "integer").put("description", "Optional seed for reproducible probabilistic draws");
            chaosProps.putObject("succeedFirst").put("type", "integer").put("description", "First N matches are NOT eligible for chaos (>= 0)");
            chaosProps.putObject("failRequestCount").put("type", "integer").put("description", "After succeedFirst, next M matches ARE eligible for chaos (>= 1)");
            chaosProps.putObject("outageAfterMillis").put("type", "integer").put("description", "Time-based outage window: chaos becomes active this many ms after the first match (>= 0)");
            chaosProps.putObject("outageDurationMillis").put("type", "integer").put("description", "Time-based outage window: chaos stays active for this many ms then self-heals (>= 1)");
            chaosProps.putObject("truncateBodyAtFraction").put("type", "number").put("description", "Corrupt the response body by keeping only this leading fraction (0.0-1.0) of its bytes");
            chaosProps.putObject("malformedBody").put("type", "boolean").put("description", "Corrupt the response body by appending a broken-JSON fragment so it fails to parse");
            chaosProps.putObject("slowResponseChunkSize").put("type", "integer").put("description", "Dribble the response body in chunks of this many bytes (>= 1); requires slowResponseChunkDelay");
            ObjectNode slowDelayProp = chaosProps.putObject("slowResponseChunkDelay");
            slowDelayProp.put("type", "object").put("description", "Delay between dribbled response chunks");
            ObjectNode slowDelayProps = slowDelayProp.putObject("properties");
            slowDelayProps.putObject("timeUnit").put("type", "string").put("description", "Time unit (e.g. MILLISECONDS, SECONDS)");
            slowDelayProps.putObject("value").put("type", "integer").put("description", "Delay value");
            chaosProps.putObject("quotaName").put("type", "string").put("description", "Stateful quota: shared counter key; expectations with the same quotaName share one rate-limit counter");
            chaosProps.putObject("quotaLimit").put("type", "integer").put("description", "Stateful quota: max requests allowed per window before requests are rejected (>= 1)");
            chaosProps.putObject("quotaWindowMillis").put("type", "integer").put("description", "Stateful quota: fixed-window length in milliseconds (>= 1)");
            chaosProps.putObject("quotaErrorStatus").put("type", "integer").put("description", "Stateful quota: status returned when the quota is exceeded (default 429)");
            chaosProps.putObject("degradationRampMillis").put("type", "integer").put("description", "Gradual degradation: ramp errorProbability and dropConnectionProbability from 0 to full over this many milliseconds from the first match (>= 1)");
        }
        ArrayNode required = schema.putArray("required");
        required.add("method");
        required.add("path");

        tools.put("create_expectation", new ToolDefinition(
            "create_expectation",
            "Creates a mock expectation that defines how MockServer should respond to a matching request. "
                + "It accepts no other parameters than those in its input schema: an unknown one is rejected with an error naming it. "
                + "Use raw_expectation to match request headers, query parameters, cookies or a body, or to add a delay or a template.",
            schema,
            this::handleCreateExpectation
        ));
    }

    private JsonNode handleCreateExpectation(JsonNode params) {
        try {
            JsonNode schemaProperties = tools.get("create_expectation").getInputSchema().path("properties");
            ObjectNode unknownParameters = unknownParametersResult("create_expectation", params, schemaProperties,
                "To match on request headers, query parameters, cookies or a body, or to add a delay or a template, " +
                    "use raw_expectation with the full expectation JSON.");
            if (unknownParameters != null) {
                return unknownParameters;
            }
            if (params.has("chaos") && !params.get("chaos").isNull()) {
                if (!params.get("chaos").isObject()) {
                    return errorResult("'chaos' must be an object, for example {\"errorStatus\": 503, \"errorProbability\": 0.5}");
                }
                ObjectNode unknownChaosParameters = unknownParametersResult("create_expectation 'chaos'", params.get("chaos"),
                    schemaProperties.path("chaos").path("properties"), null);
                if (unknownChaosParameters != null) {
                    return unknownChaosParameters;
                }
            }
            for (String field : new String[]{"method", "path"}) {
                if (params.has(field) && !params.get(field).isNull() && !params.get(field).isTextual()) {
                    return errorResult("'" + field + "' must be a string, for example " + ("method".equals(field) ? "\"GET\"" : "\"/users/1\""));
                }
            }
            String method = params.path("method").asText(null);
            if (method == null || method.trim().isEmpty()) {
                return errorResult("'method' is required and must not be blank");
            }
            String path = params.path("path").asText(null);
            if (path == null || path.trim().isEmpty()) {
                return errorResult("'path' is required and must not be blank");
            }

            JsonNode statusCodeNode = params.path("statusCode");
            int statusCode = 200;
            if (!statusCodeNode.isMissingNode() && !statusCodeNode.isNull()) {
                if (!statusCodeNode.isIntegralNumber()) {
                    return errorResult("'statusCode' must be an integer");
                }
                statusCode = statusCodeNode.asInt();
                if (statusCode < 100 || statusCode > 999) {
                    return errorResult("'statusCode' must be between 100 and 999");
                }
            }

            HttpRequest httpRequest = request().withMethod(method).withPath(path);
            HttpResponse httpResponse = response().withStatusCode(statusCode);

            JsonNode responseBody = params.path("responseBody");
            if (!responseBody.isMissingNode() && !responseBody.isNull()) {
                if (responseBody.isTextual()) {
                    httpResponse.withBody(responseBody.asText());
                } else {
                    httpResponse.withBody(objectMapper.writeValueAsString(responseBody), MediaType.APPLICATION_JSON);
                }
            }

            JsonNode responseHeaders = params.path("responseHeaders");
            if (!responseHeaders.isMissingNode() && !responseHeaders.isNull() && !responseHeaders.isObject()) {
                return errorResult("'responseHeaders' must be an object mapping each header name to a string value, for example {\"Content-Type\": \"application/json\"}");
            }
            if (responseHeaders.isObject()) {
                Iterator<Map.Entry<String, JsonNode>> fields = responseHeaders.fields();
                while (fields.hasNext()) {
                    Map.Entry<String, JsonNode> entry = fields.next();
                    List<String> values = headerValues(entry.getValue());
                    if (values == null) {
                        return errorResult("'responseHeaders." + entry.getKey() + "' must be a string or an array of strings");
                    }
                    httpResponse.withHeader(entry.getKey(), values.toArray(new String[0]));
                }
            }

            Expectation expectation = Expectation.when(httpRequest).thenRespond(httpResponse);

            JsonNode timesNode = params.path("times");
            if (!timesNode.isMissingNode() && !timesNode.isNull()) {
                if (!timesNode.isIntegralNumber()) {
                    return errorResult("'times' must be an integer");
                }
                int timesValue = timesNode.asInt();
                if (timesValue < 0) {
                    return errorResult("'times' must be a non-negative integer");
                }
                expectation = Expectation.when(httpRequest, Times.exactly(timesValue), TimeToLive.unlimited()).thenRespond(httpResponse);
            }

            JsonNode ttlNode = params.path("timeToLive");
            if (!ttlNode.isMissingNode() && !ttlNode.isNull() && !ttlNode.isTextual()) {
                return errorResult("'timeToLive' must be a string in format '<number> <UNIT>' (e.g., '60 SECONDS')");
            }
            if (!ttlNode.isMissingNode() && !ttlNode.isNull() && ttlNode.isTextual()) {
                String ttlStr = ttlNode.asText().trim();
                String[] parts = ttlStr.split("\\s+", 2);
                if (parts.length != 2) {
                    return errorResult("'timeToLive' must be in format '<number> <UNIT>' (e.g., '60 SECONDS')");
                }
                long ttlValue;
                try {
                    ttlValue = Long.parseLong(parts[0]);
                } catch (NumberFormatException e) {
                    return errorResult("'timeToLive' value must be a number");
                }
                if (ttlValue <= 0) {
                    return errorResult("'timeToLive' value must be positive");
                }
                TimeUnit timeUnit;
                try {
                    timeUnit = TimeUnit.valueOf(parts[1].toUpperCase());
                } catch (IllegalArgumentException e) {
                    return errorResult("'timeToLive' unit must be one of: DAYS, HOURS, MINUTES, SECONDS, MILLISECONDS, MICROSECONDS, NANOSECONDS");
                }
                Times effectiveTimes = (timesNode.isMissingNode() || timesNode.isNull()) ? Times.unlimited() : Times.exactly(timesNode.asInt());
                expectation = Expectation.when(httpRequest, effectiveTimes, TimeToLive.exactly(timeUnit, ttlValue)).thenRespond(httpResponse);
            }

            JsonNode chaosNode = params.path("chaos");
            if (chaosNode.isObject()) {
                HttpChaosProfile chaos = HttpChaosProfile.httpChaosProfile();
                JsonNode errorStatusNode = chaosNode.path("errorStatus");
                if (!errorStatusNode.isMissingNode() && !errorStatusNode.isNull()) {
                    chaos.withErrorStatus(errorStatusNode.asInt());
                }
                JsonNode errorProbabilityNode = chaosNode.path("errorProbability");
                if (!errorProbabilityNode.isMissingNode() && !errorProbabilityNode.isNull()) {
                    chaos.withErrorProbability(errorProbabilityNode.asDouble());
                }
                JsonNode retryAfterNode = chaosNode.path("retryAfter");
                if (!retryAfterNode.isMissingNode() && !retryAfterNode.isNull()) {
                    chaos.withRetryAfter(retryAfterNode.asText());
                }
                JsonNode dropConnectionProbabilityNode = chaosNode.path("dropConnectionProbability");
                if (!dropConnectionProbabilityNode.isMissingNode() && !dropConnectionProbabilityNode.isNull()) {
                    chaos.withDropConnectionProbability(dropConnectionProbabilityNode.asDouble());
                }
                JsonNode latencyNode = chaosNode.path("latency");
                if (latencyNode.isObject()) {
                    String latencyTimeUnit = latencyNode.path("timeUnit").asText("MILLISECONDS");
                    int latencyValue = latencyNode.path("value").asInt(0);
                    try {
                        chaos.withLatency(new Delay(TimeUnit.valueOf(latencyTimeUnit.toUpperCase()), latencyValue));
                    } catch (IllegalArgumentException e) {
                        return errorResult("chaos latency timeUnit must be one of: NANOSECONDS, MICROSECONDS, MILLISECONDS, SECONDS, MINUTES, HOURS, DAYS");
                    }
                }
                JsonNode seedNode = chaosNode.path("seed");
                if (!seedNode.isMissingNode() && !seedNode.isNull()) {
                    chaos.withSeed(seedNode.asLong());
                }
                JsonNode succeedFirstNode = chaosNode.path("succeedFirst");
                if (!succeedFirstNode.isMissingNode() && !succeedFirstNode.isNull()) {
                    chaos.withSucceedFirst(succeedFirstNode.asInt());
                }
                JsonNode failRequestCountNode = chaosNode.path("failRequestCount");
                if (!failRequestCountNode.isMissingNode() && !failRequestCountNode.isNull()) {
                    chaos.withFailRequestCount(failRequestCountNode.asInt());
                }
                JsonNode outageAfterMillisNode = chaosNode.path("outageAfterMillis");
                if (!outageAfterMillisNode.isMissingNode() && !outageAfterMillisNode.isNull()) {
                    chaos.withOutageAfterMillis(outageAfterMillisNode.asLong());
                }
                JsonNode outageDurationMillisNode = chaosNode.path("outageDurationMillis");
                if (!outageDurationMillisNode.isMissingNode() && !outageDurationMillisNode.isNull()) {
                    chaos.withOutageDurationMillis(outageDurationMillisNode.asLong());
                }
                JsonNode truncateBodyAtFractionNode = chaosNode.path("truncateBodyAtFraction");
                if (!truncateBodyAtFractionNode.isMissingNode() && !truncateBodyAtFractionNode.isNull()) {
                    chaos.withTruncateBodyAtFraction(truncateBodyAtFractionNode.asDouble());
                }
                JsonNode malformedBodyNode = chaosNode.path("malformedBody");
                if (!malformedBodyNode.isMissingNode() && !malformedBodyNode.isNull()) {
                    chaos.withMalformedBody(malformedBodyNode.asBoolean());
                }
                JsonNode slowChunkSizeNode = chaosNode.path("slowResponseChunkSize");
                if (!slowChunkSizeNode.isMissingNode() && !slowChunkSizeNode.isNull()) {
                    int slowChunkSize = slowChunkSizeNode.asInt();
                    if (slowChunkSize < 1) {
                        return errorResult("chaos slowResponseChunkSize must be >= 1");
                    }
                    chaos.withSlowResponseChunkSize(slowChunkSize);
                }
                JsonNode slowChunkDelayNode = chaosNode.path("slowResponseChunkDelay");
                if (slowChunkDelayNode.isObject()) {
                    String slowTimeUnit = slowChunkDelayNode.path("timeUnit").asText("MILLISECONDS");
                    int slowValue = slowChunkDelayNode.path("value").asInt(0);
                    if (slowValue < 1) {
                        return errorResult("chaos slowResponseChunkDelay value must be >= 1 (a zero delay would not slow the response)");
                    }
                    try {
                        chaos.withSlowResponseChunkDelay(new Delay(TimeUnit.valueOf(slowTimeUnit.toUpperCase()), slowValue));
                    } catch (IllegalArgumentException e) {
                        return errorResult("chaos slowResponseChunkDelay timeUnit must be one of: NANOSECONDS, MICROSECONDS, MILLISECONDS, SECONDS, MINUTES, HOURS, DAYS");
                    }
                }
                JsonNode quotaNameNode = chaosNode.path("quotaName");
                if (quotaNameNode.isTextual()) {
                    chaos.withQuotaName(quotaNameNode.asText());
                }
                JsonNode quotaLimitNode = chaosNode.path("quotaLimit");
                if (!quotaLimitNode.isMissingNode() && !quotaLimitNode.isNull()) {
                    int quotaLimit = quotaLimitNode.asInt();
                    if (quotaLimit < 1) {
                        return errorResult("chaos quotaLimit must be >= 1");
                    }
                    chaos.withQuotaLimit(quotaLimit);
                }
                JsonNode quotaWindowMillisNode = chaosNode.path("quotaWindowMillis");
                if (!quotaWindowMillisNode.isMissingNode() && !quotaWindowMillisNode.isNull()) {
                    long quotaWindowMillis = quotaWindowMillisNode.asLong();
                    if (quotaWindowMillis < 1) {
                        return errorResult("chaos quotaWindowMillis must be >= 1");
                    }
                    chaos.withQuotaWindowMillis(quotaWindowMillis);
                }
                JsonNode quotaErrorStatusNode = chaosNode.path("quotaErrorStatus");
                if (!quotaErrorStatusNode.isMissingNode() && !quotaErrorStatusNode.isNull()) {
                    chaos.withQuotaErrorStatus(quotaErrorStatusNode.asInt());
                }
                JsonNode degradationRampMillisNode = chaosNode.path("degradationRampMillis");
                if (!degradationRampMillisNode.isMissingNode() && !degradationRampMillisNode.isNull()) {
                    long degradationRampMillis = degradationRampMillisNode.asLong();
                    if (degradationRampMillis < 1) {
                        return errorResult("chaos degradationRampMillis must be >= 1");
                    }
                    chaos.withDegradationRampMillis(degradationRampMillis);
                }
                expectation.withChaos(chaos);
            }

            List<Expectation> result = httpState.add(expectation);

            ObjectNode resultNode = objectMapper.createObjectNode();
            resultNode.put("status", "created");
            resultNode.put("count", result.size());
            if (!result.isEmpty()) {
                resultNode.put("id", result.get(0).getId());
            }
            return resultNode;
        } catch (Exception e) {
            return errorResult("Failed to create expectation", e);
        }
    }

    /** A header's values when {@code value} is a scalar or an array of scalars, otherwise null. */
    private static List<String> headerValues(JsonNode value) {
        List<String> values = new ArrayList<>();
        if (value.isValueNode() && !value.isNull()) {
            values.add(value.asText());
            return values;
        }
        if (value.isArray() && value.size() > 0) {
            for (JsonNode item : value) {
                if (!item.isValueNode() || item.isNull()) {
                    return null;
                }
                values.add(item.asText());
            }
            return values;
        }
        return null;
    }

    private static final Map<String, String> PARAMETER_ALIASES = Map.of(
        "body", "responseBody",
        "headers", "responseHeaders",
        "status", "statusCode",
        "responsestatus", "statusCode",
        "responsestatuscode", "statusCode",
        "httpmethod", "method",
        "ttl", "timeToLive"
    );

    /**
     * An error naming each parameter of {@code params} that {@code schemaProperties} does not declare, with the
     * accepted names and a likely intended name, so a caller does not lose a field silently. Null when all are known.
     * Keys starting with {@code _} (such as a client's {@code _meta}) are metadata, not parameters, and are let through.
     */
    private ObjectNode unknownParametersResult(String scope, JsonNode params, JsonNode schemaProperties, String alternative) {
        Set<String> accepted = new LinkedHashSet<>();
        schemaProperties.fieldNames().forEachRemaining(accepted::add);
        List<String> unknown = new ArrayList<>();
        params.fieldNames().forEachRemaining(name -> {
            if (!accepted.contains(name) && !name.startsWith("_")) {
                unknown.add(name);
            }
        });
        if (unknown.isEmpty()) {
            return null;
        }
        ObjectNode suggestions = objectMapper.createObjectNode();
        StringBuilder message = new StringBuilder(scope).append(" does not accept: ");
        for (int i = 0; i < unknown.size(); i++) {
            String name = unknown.get(i);
            message.append(i > 0 ? ", " : "").append("'").append(name).append("'");
            String suggestion = suggestParameter(name, accepted);
            if (suggestion != null) {
                suggestions.put(name, suggestion);
                message.append(" (did you mean '").append(suggestion).append("'?)");
            }
        }
        message.append(". It accepts: ").append(String.join(", ", accepted)).append(".");
        if (alternative != null) {
            message.append(" ").append(alternative);
        }
        ObjectNode result = errorResult(message.toString());
        ArrayNode unknownNode = result.putArray("unknownParameters");
        unknown.forEach(unknownNode::add);
        ArrayNode acceptedNode = result.putArray("acceptedParameters");
        accepted.forEach(acceptedNode::add);
        if (suggestions.size() > 0) {
            result.set("suggestions", suggestions);
        }
        return result;
    }

    private static String suggestParameter(String unknown, Set<String> accepted) {
        String normalised = normaliseParameterName(unknown);
        for (String name : accepted) {
            if (normaliseParameterName(name).equals(normalised)) {
                return name;
            }
        }
        String alias = PARAMETER_ALIASES.get(normalised);
        return alias != null && accepted.contains(alias) ? alias : null;
    }

    private static String normaliseParameterName(String name) {
        return name.toLowerCase(Locale.ROOT).replace("_", "").replace("-", "");
    }

    private void registerVerifyRequest() {
        ObjectNode schema = objectMapper.createObjectNode();
        schema.put("type", "object");
        ObjectNode properties = schema.putObject("properties");
        properties.putObject("method").put("type", "string").put("description", "HTTP method to verify");
        properties.putObject("path").put("type", "string").put("description", "Request path to verify");
        properties.putObject("atLeast").put("type", "integer").put("description", "Minimum number of matching requests expected");
        properties.putObject("atMost").put("type", "integer").put("description", "Maximum number of matching requests expected");

        tools.put("verify_request", new ToolDefinition(
            "verify_request",
            "Verifies that a request matching the specified criteria was received a certain number of times",
            schema,
            this::handleVerifyRequest
        ));
    }

    private JsonNode handleVerifyRequest(JsonNode params) {
        try {
            HttpRequest httpRequest = request();
            JsonNode methodNode = params.path("method");
            if (!methodNode.isMissingNode() && !methodNode.isNull()) {
                httpRequest.withMethod(methodNode.asText());
            }
            JsonNode pathNode = params.path("path");
            if (!pathNode.isMissingNode() && !pathNode.isNull()) {
                httpRequest.withPath(pathNode.asText());
            }

            JsonNode atLeastNode = params.path("atLeast");
            if (!atLeastNode.isMissingNode() && !atLeastNode.isNull() && !atLeastNode.isIntegralNumber()) {
                return errorResult("'atLeast' must be an integer");
            }
            JsonNode atMostNode = params.path("atMost");
            if (!atMostNode.isMissingNode() && !atMostNode.isNull() && !atMostNode.isIntegralNumber()) {
                return errorResult("'atMost' must be an integer");
            }
            int atLeast = atLeastNode.asInt(1);
            int atMost = atMostNode.asInt(-1);
            if (atLeast < 0) {
                return errorResult("'atLeast' must be non-negative");
            }
            if (atMost != -1 && atMost < atLeast) {
                return errorResult("'atMost' must be >= 'atLeast'");
            }

            VerificationTimes times;
            if (atMost == -1) {
                times = VerificationTimes.atLeast(atLeast);
            } else {
                times = VerificationTimes.between(atLeast, atMost);
            }

            Verification verification = new Verification()
                .withRequest(httpRequest)
                .withTimes(times);

            Future<String> result = httpState.verify(verification);
            String verificationResult = result.get(10, TimeUnit.SECONDS);

            ObjectNode resultNode = objectMapper.createObjectNode();
            if (verificationResult == null || verificationResult.isEmpty()) {
                resultNode.put("verified", true);
                resultNode.put("message", "Verification passed");
            } else {
                resultNode.put("verified", false);
                resultNode.put("message", verificationResult);
            }
            return resultNode;
        } catch (Exception e) {
            return errorResult("Verification failed", e);
        }
    }

    private void registerRetrieveRecordedRequests() {
        ObjectNode schema = objectMapper.createObjectNode();
        schema.put("type", "object");
        ObjectNode properties = schema.putObject("properties");
        properties.putObject("method").put("type", "string").put("description", "Filter by HTTP method");
        properties.putObject("path").put("type", "string").put("description", "Filter by request path");
        properties.putObject("limit").put("type", "integer").put("description", "Maximum number of requests to return (default 50)");

        tools.put("retrieve_recorded_requests", new ToolDefinition(
            "retrieve_recorded_requests",
            "Retrieves recorded requests that were received by MockServer, optionally filtered by method and path",
            schema,
            this::handleRetrieveRecordedRequests
        ));
    }

    private JsonNode handleRetrieveRecordedRequests(JsonNode params) {
        try {
            HttpRequest filterRequest = request();
            JsonNode methodNode = params.path("method");
            if (!methodNode.isMissingNode() && !methodNode.isNull()) {
                filterRequest.withMethod(methodNode.asText());
            }
            JsonNode pathNode = params.path("path");
            if (!pathNode.isMissingNode() && !pathNode.isNull()) {
                filterRequest.withPath(pathNode.asText());
            }
            int limit = params.path("limit").asInt(50);
            if (limit < 1 || limit > 500) {
                return errorResult("'limit' must be between 1 and 500");
            }

            HttpRequest retrieveRequest = request()
                .withMethod("PUT")
                .withPath("/mockserver/retrieve")
                .withQueryStringParameter("type", "REQUESTS")
                .withQueryStringParameter("format", "JSON")
                .withBody(getRequestDefinitionSerializer().serialize(filterRequest));

            HttpResponse retrieveResponse = httpState.retrieve(retrieveRequest);
            String body = retrieveResponse.getBodyAsString();

            if (body != null && !body.isEmpty()) {
                JsonNode allRequests = objectMapper.readTree(body);
                if (allRequests.isArray() && allRequests.size() > limit) {
                    ArrayNode limited = objectMapper.createArrayNode();
                    for (int i = allRequests.size() - limit; i < allRequests.size(); i++) {
                        limited.add(allRequests.get(i));
                    }
                    ObjectNode resultNode = objectMapper.createObjectNode();
                    resultNode.set("requests", limited);
                    resultNode.put("total", allRequests.size());
                    resultNode.put("returned", limited.size());
                    return resultNode;
                } else {
                    ObjectNode resultNode = objectMapper.createObjectNode();
                    resultNode.set("requests", allRequests);
                    resultNode.put("total", allRequests.isArray() ? allRequests.size() : 0);
                    resultNode.put("returned", allRequests.isArray() ? allRequests.size() : 0);
                    return resultNode;
                }
            }

            ObjectNode resultNode = objectMapper.createObjectNode();
            resultNode.set("requests", objectMapper.createArrayNode());
            resultNode.put("total", 0);
            resultNode.put("returned", 0);
            return resultNode;
        } catch (Exception e) {
            return errorResult("Failed to retrieve requests", e);
        }
    }

    private void registerRetrieveLogs() {
        ObjectNode schema = objectMapper.createObjectNode();
        schema.put("type", "object");
        ObjectNode properties = schema.putObject("properties");
        properties.putObject("correlationId").put("type", "string").put("description", "Filter to a single request's log entries by its correlation ID — the key debugging filter. Each incoming request is assigned a unique correlation ID that links all log entries for that request's lifecycle.");
        properties.putObject("limit").put("type", "integer").put("description", "Maximum number of log entries to return (default 100, max 500). Returns the most recent entries when more are available.");

        tools.put("retrieve_logs", new ToolDefinition(
            "retrieve_logs",
            "Retrieve MockServer's recorded log messages — request matching, mismatches, actions and errors — to debug why a test/request behaved as it did. Returns structured LOG_ENTRIES format. Use the correlationId filter to narrow to a single request's lifecycle.",
            schema,
            this::handleRetrieveLogs
        ));
    }

    private JsonNode handleRetrieveLogs(JsonNode params) {
        try {
            JsonNode correlationIdNode = params.path("correlationId");
            String correlationId = (correlationIdNode.isTextual() && !correlationIdNode.asText().isEmpty())
                ? correlationIdNode.asText() : null;

            int limit = params.path("limit").asInt(100);
            if (limit < 1 || limit > 500) {
                return errorResult("'limit' must be between 1 and 500");
            }

            String body = retrieveLogsAsLogEntries(correlationId);

            if (body != null && !body.isEmpty()) {
                JsonNode allEntries = objectMapper.readTree(body);
                if (allEntries.isArray() && allEntries.size() > limit) {
                    ArrayNode limited = objectMapper.createArrayNode();
                    for (int i = allEntries.size() - limit; i < allEntries.size(); i++) {
                        limited.add(allEntries.get(i));
                    }
                    ObjectNode resultNode = objectMapper.createObjectNode();
                    resultNode.set("logEntries", limited);
                    resultNode.put("total", allEntries.size());
                    resultNode.put("returned", limited.size());
                    return resultNode;
                } else {
                    ObjectNode resultNode = objectMapper.createObjectNode();
                    resultNode.set("logEntries", allEntries);
                    resultNode.put("total", allEntries.isArray() ? allEntries.size() : 0);
                    resultNode.put("returned", allEntries.isArray() ? allEntries.size() : 0);
                    return resultNode;
                }
            }

            ObjectNode resultNode = objectMapper.createObjectNode();
            resultNode.set("logEntries", objectMapper.createArrayNode());
            resultNode.put("total", 0);
            resultNode.put("returned", 0);
            return resultNode;
        } catch (Exception e) {
            return errorResult("Failed to retrieve logs", e);
        }
    }

    /**
     * Shared helper that builds the retrieve request for type=LOGS, format=LOG_ENTRIES
     * with an optional correlationId filter and returns the raw response body.
     * Used by both {@code retrieve_logs} and {@code raw_retrieve} (when type=LOGS, format=LOG_ENTRIES).
     */
    private String retrieveLogsAsLogEntries(String correlationId) {
        HttpRequest retrieveRequest = request()
            .withMethod("PUT")
            .withPath("/mockserver/retrieve")
            .withQueryStringParameter("type", "LOGS")
            .withQueryStringParameter("format", "LOG_ENTRIES");

        if (correlationId != null && !correlationId.isEmpty()) {
            retrieveRequest.withQueryStringParameter("correlationId", correlationId);
        }

        HttpResponse retrieveResponse = httpState.retrieve(retrieveRequest);
        return retrieveResponse.getBodyAsString();
    }

    private void registerClearExpectations() {
        ObjectNode schema = objectMapper.createObjectNode();
        schema.put("type", "object");
        ObjectNode properties = schema.putObject("properties");
        properties.putObject("method").put("type", "string").put("description", "Filter by HTTP method");
        properties.putObject("path").put("type", "string").put("description", "Filter by request path");
        ObjectNode typeProp = properties.putObject("type");
        typeProp.put("type", "string").put("description", "What to clear: ALL, LOG, or EXPECTATIONS");
        ArrayNode typeEnum = typeProp.putArray("enum");
        typeEnum.add("ALL");
        typeEnum.add("LOG");
        typeEnum.add("EXPECTATIONS");

        tools.put("clear_expectations", new ToolDefinition(
            "clear_expectations",
            "Clears expectations and/or recorded requests from MockServer matching the specified criteria",
            schema,
            this::handleClearExpectations
        ));
    }

    private JsonNode handleClearExpectations(JsonNode params) {
        try {
            HttpRequest clearRequest = request()
                .withMethod("PUT")
                .withPath("/mockserver/clear");

            String type = params.path("type").asText("ALL");
            if (!"ALL".equals(type) && !"LOG".equals(type) && !"EXPECTATIONS".equals(type)) {
                return errorResult("'type' must be one of: ALL, LOG, EXPECTATIONS");
            }
            clearRequest.withQueryStringParameter("type", type);

            JsonNode methodNode = params.path("method");
            JsonNode pathNode = params.path("path");
            boolean hasFilter = (!methodNode.isMissingNode() && !methodNode.isNull()) ||
                (!pathNode.isMissingNode() && !pathNode.isNull());

            if (hasFilter) {
                HttpRequest filterRequest = request();
                if (!methodNode.isMissingNode() && !methodNode.isNull()) {
                    filterRequest.withMethod(methodNode.asText());
                }
                if (!pathNode.isMissingNode() && !pathNode.isNull()) {
                    filterRequest.withPath(pathNode.asText());
                }
                clearRequest.withBody(getRequestDefinitionSerializer().serialize(filterRequest));
            }

            httpState.clear(clearRequest);

            ObjectNode resultNode = objectMapper.createObjectNode();
            resultNode.put("status", "cleared");
            resultNode.put("type", type);
            return resultNode;
        } catch (Exception e) {
            return errorResult("Failed to clear", e);
        }
    }

    private void registerReset() {
        ObjectNode schema = objectMapper.createObjectNode();
        schema.put("type", "object");
        schema.putObject("properties");

        tools.put("reset", new ToolDefinition(
            "reset",
            "Resets MockServer by clearing all expectations, recorded requests, and logs",
            schema,
            this::handleReset
        ));
    }

    private JsonNode handleReset(JsonNode params) {
        try {
            httpState.reset();
            ObjectNode resultNode = objectMapper.createObjectNode();
            resultNode.put("status", "reset");
            resultNode.put("message", "MockServer has been reset");
            return resultNode;
        } catch (Exception e) {
            return errorResult("Failed to reset", e);
        }
    }

    private void registerManageServiceChaos() {
        ObjectNode schema = objectMapper.createObjectNode();
        schema.put("type", "object");
        ObjectNode properties = schema.putObject("properties");
        properties.putObject("action").put("type", "string").put("description", "One of: register (set a host's chaos), remove (clear one host), clear (clear all)");
        properties.putObject("host").put("type", "string").put("description", "Upstream host to break (required for register/remove); matched case-insensitively, ignoring any :port");
        properties.putObject("chaos").put("type", "object").put("description", "The HttpChaosProfile to apply (required for register): same fields as the create_expectation 'chaos' block (errorStatus, errorProbability, dropConnectionProbability, latency, truncateBodyAtFraction, malformedBody, slowResponseChunkSize/Delay, quota*, degradationRampMillis, etc.)");
        properties.putObject("ttlMillis").put("type", "integer").put("description", "Optional time-to-live for a 'register': the chaos auto-reverts after this many ms (a dead-man's switch so chaos self-heals if no clear is sent). Omit for chaos that persists until explicitly cleared.");
        schema.putArray("required").add("action");

        tools.put("manage_service_chaos", new ToolDefinition(
            "manage_service_chaos",
            "Manage service-scoped HTTP chaos: register one chaos profile per upstream host (applied to all matched forwards to that host that have no chaos of their own), remove a host's profile, or clear all. The host-keyed profiles are applied on the matched-forward path; an expectation's own chaos always takes precedence.",
            schema,
            this::handleManageServiceChaos
        ));
    }

    private JsonNode handleManageServiceChaos(JsonNode params) {
        try {
            String action = params.path("action").asText(null);
            if (action == null || action.isBlank()) {
                return errorResult("'action' is required and must be one of: register, remove, clear");
            }
            org.mockserver.mock.action.http.ServiceChaosRegistry registry = org.mockserver.mock.action.http.ServiceChaosRegistry.getInstance();
            switch (action.toLowerCase()) {
                case "clear":
                    registry.reset();
                    return objectMapper.createObjectNode().put("status", "cleared");
                case "remove": {
                    String host = params.path("host").asText(null);
                    if (host == null || host.isBlank()) {
                        return errorResult("'host' is required for action 'remove'");
                    }
                    registry.remove(host);
                    return objectMapper.createObjectNode().put("status", "removed").put("host", host);
                }
                case "register": {
                    String host = params.path("host").asText(null);
                    if (host == null || host.isBlank()) {
                        return errorResult("'host' is required for action 'register'");
                    }
                    if (!params.path("chaos").isObject()) {
                        return errorResult("'chaos' object is required for action 'register'");
                    }
                    org.mockserver.model.HttpChaosProfile profile;
                    try {
                        profile = org.mockserver.serialization.ObjectMapperFactory.createObjectMapper()
                            .treeToValue(params.get("chaos"), org.mockserver.serialization.model.HttpChaosProfileDTO.class)
                            .buildObject();
                    } catch (IllegalArgumentException | com.fasterxml.jackson.core.JsonProcessingException e) {
                        // validation (IllegalArgumentException from withX) or malformed chaos JSON
                        return errorResult("invalid chaos profile: " + e.getMessage());
                    }
                    long ttlMillis = 0L;
                    if (params.hasNonNull("ttlMillis")) {
                        ttlMillis = params.path("ttlMillis").asLong(0L);
                        if (ttlMillis < 1) {
                            return errorResult("'ttlMillis' must be >= 1 when supplied");
                        }
                    }
                    registry.put(host, profile, ttlMillis);
                    ObjectNode registered = objectMapper.createObjectNode().put("status", "registered").put("host", host);
                    if (ttlMillis > 0) {
                        registered.put("ttlMillis", ttlMillis);
                    }
                    return registered;
                }
                default:
                    return errorResult("unknown action '" + action + "', must be one of: register, remove, clear");
            }
        } catch (Exception e) {
            return errorResult("Failed to manage service chaos", e);
        }
    }

    private void registerGetStatus() {
        ObjectNode schema = objectMapper.createObjectNode();
        schema.put("type", "object");
        schema.putObject("properties");

        tools.put("get_status", new ToolDefinition(
            "get_status",
            "Returns the current status of MockServer including listening ports and running state",
            schema,
            this::handleGetStatus
        ));
    }

    private JsonNode handleGetStatus(JsonNode params) {
        try {
            ObjectNode resultNode = objectMapper.createObjectNode();
            List<Integer> ports = server.getLocalPorts();
            ArrayNode portsArray = resultNode.putArray("ports");
            for (Integer port : ports) {
                portsArray.add(port);
            }
            resultNode.put("running", server.isRunning());
            return resultNode;
        } catch (Exception e) {
            return errorResult("Failed to get status", e);
        }
    }

    private void registerVerifyRequestSequence() {
        ObjectNode schema = objectMapper.createObjectNode();
        schema.put("type", "object");
        ObjectNode properties = schema.putObject("properties");
        ObjectNode requestsProp = properties.putObject("requests");
        requestsProp.put("type", "array").put("description", "Ordered list of requests to verify in sequence");
        ObjectNode requestItems = requestsProp.putObject("items");
        requestItems.put("type", "object");
        ObjectNode itemProps = requestItems.putObject("properties");
        itemProps.putObject("method").put("type", "string");
        itemProps.putObject("path").put("type", "string");
        schema.putArray("required").add("requests");

        tools.put("verify_request_sequence", new ToolDefinition(
            "verify_request_sequence",
            "Verifies that requests were received in the specified order",
            schema,
            this::handleVerifyRequestSequence
        ));
    }

    private JsonNode handleVerifyRequestSequence(JsonNode params) {
        try {
            JsonNode requestsNode = params.path("requests");
            if (!requestsNode.isArray() || requestsNode.size() == 0) {
                return errorResult("'requests' must be a non-empty array");
            }

            VerificationSequence sequence = new VerificationSequence();
            List<org.mockserver.model.RequestDefinition> requests = new ArrayList<>();
            for (JsonNode reqNode : requestsNode) {
                if (!reqNode.isObject()) {
                    return errorResult("Each element of 'requests' must be an object");
                }
                HttpRequest httpRequest = request();
                JsonNode methodNode = reqNode.path("method");
                if (!methodNode.isMissingNode() && !methodNode.isNull()) {
                    httpRequest.withMethod(methodNode.asText());
                }
                JsonNode pathNode = reqNode.path("path");
                if (!pathNode.isMissingNode() && !pathNode.isNull()) {
                    httpRequest.withPath(pathNode.asText());
                }
                requests.add(httpRequest);
            }
            sequence.withRequests(requests);

            Future<String> result = httpState.verify(sequence);
            String verificationResult = result.get(10, TimeUnit.SECONDS);

            ObjectNode resultNode = objectMapper.createObjectNode();
            if (verificationResult == null || verificationResult.isEmpty()) {
                resultNode.put("verified", true);
                resultNode.put("message", "Request sequence verification passed");
            } else {
                resultNode.put("verified", false);
                resultNode.put("message", verificationResult);
            }
            return resultNode;
        } catch (Exception e) {
            return errorResult("Sequence verification failed", e);
        }
    }

    private void registerRetrieveRequestResponses() {
        ObjectNode schema = objectMapper.createObjectNode();
        schema.put("type", "object");
        ObjectNode properties = schema.putObject("properties");
        properties.putObject("method").put("type", "string").put("description", "Filter by HTTP method");
        properties.putObject("path").put("type", "string").put("description", "Filter by request path");
        properties.putObject("limit").put("type", "integer").put("description", "Maximum number of pairs to return (default 50)");

        tools.put("retrieve_request_responses", new ToolDefinition(
            "retrieve_request_responses",
            "Retrieves request-response pairs that were recorded by MockServer",
            schema,
            this::handleRetrieveRequestResponses
        ));
    }

    private JsonNode handleRetrieveRequestResponses(JsonNode params) {
        try {
            HttpRequest filterRequest = request();
            JsonNode methodNode = params.path("method");
            if (!methodNode.isMissingNode() && !methodNode.isNull()) {
                filterRequest.withMethod(methodNode.asText());
            }
            JsonNode pathNode = params.path("path");
            if (!pathNode.isMissingNode() && !pathNode.isNull()) {
                filterRequest.withPath(pathNode.asText());
            }
            int limit = params.path("limit").asInt(50);
            if (limit < 1 || limit > 500) {
                return errorResult("'limit' must be between 1 and 500");
            }

            HttpRequest retrieveRequest = request()
                .withMethod("PUT")
                .withPath("/mockserver/retrieve")
                .withQueryStringParameter("type", "REQUEST_RESPONSES")
                .withQueryStringParameter("format", "JSON")
                .withBody(getRequestDefinitionSerializer().serialize(filterRequest));

            HttpResponse retrieveResponse = httpState.retrieve(retrieveRequest);
            String body = retrieveResponse.getBodyAsString();

            if (body != null && !body.isEmpty()) {
                JsonNode allPairs = objectMapper.readTree(body);
                if (allPairs.isArray() && allPairs.size() > limit) {
                    ArrayNode limited = objectMapper.createArrayNode();
                    for (int i = allPairs.size() - limit; i < allPairs.size(); i++) {
                        limited.add(allPairs.get(i));
                    }
                    ObjectNode resultNode = objectMapper.createObjectNode();
                    resultNode.set("requestResponses", limited);
                    resultNode.put("total", allPairs.size());
                    resultNode.put("returned", limited.size());
                    return resultNode;
                } else {
                    ObjectNode resultNode = objectMapper.createObjectNode();
                    resultNode.set("requestResponses", allPairs);
                    resultNode.put("total", allPairs.isArray() ? allPairs.size() : 0);
                    resultNode.put("returned", allPairs.isArray() ? allPairs.size() : 0);
                    return resultNode;
                }
            }

            ObjectNode resultNode = objectMapper.createObjectNode();
            resultNode.set("requestResponses", objectMapper.createArrayNode());
            resultNode.put("total", 0);
            resultNode.put("returned", 0);
            return resultNode;
        } catch (Exception e) {
            return errorResult("Failed to retrieve request-response pairs", e);
        }
    }

    private void registerCreateForwardExpectation() {
        ObjectNode schema = objectMapper.createObjectNode();
        schema.put("type", "object");
        ObjectNode properties = schema.putObject("properties");
        properties.putObject("path").put("type", "string").put("description", "Request path to match for forwarding");
        properties.putObject("host").put("type", "string").put("description", "Host to forward requests to");
        properties.putObject("port").put("type", "integer").put("description", "Port to forward requests to (default 443)");
        ObjectNode schemeProp = properties.putObject("scheme");
        schemeProp.put("type", "string").put("description", "Scheme for forwarding: HTTP or HTTPS (default HTTPS)");
        ArrayNode schemeEnum = schemeProp.putArray("enum");
        schemeEnum.add("HTTP");
        schemeEnum.add("HTTPS");
        ArrayNode required = schema.putArray("required");
        required.add("path");
        required.add("host");

        tools.put("create_forward_expectation", new ToolDefinition(
            "create_forward_expectation",
            "Creates a forward/proxy expectation that forwards matching requests to a specified host",
            schema,
            this::handleCreateForwardExpectation
        ));
    }

    private JsonNode handleCreateForwardExpectation(JsonNode params) {
        try {
            String path = params.path("path").asText(null);
            if (path == null || path.trim().isEmpty()) {
                return errorResult("'path' is required and must not be blank");
            }
            String host = params.path("host").asText(null);
            if (host == null || host.trim().isEmpty()) {
                return errorResult("'host' is required and must not be blank");
            }
            JsonNode portNode = params.path("port");
            int port = 443;
            if (!portNode.isMissingNode() && !portNode.isNull()) {
                if (!portNode.isIntegralNumber()) {
                    return errorResult("'port' must be an integer");
                }
                port = portNode.asInt();
                if (port < 1 || port > 65535) {
                    return errorResult("'port' must be between 1 and 65535");
                }
            }
            String scheme = params.path("scheme").asText("HTTPS");
            if (!"HTTP".equals(scheme) && !"HTTPS".equals(scheme)) {
                return errorResult("'scheme' must be HTTP or HTTPS");
            }

            HttpRequest httpRequest = request().withPath(path);
            HttpForward httpForward = HttpForward.forward()
                .withHost(host)
                .withPort(port)
                .withScheme(HttpForward.Scheme.valueOf(scheme.toUpperCase()));

            Expectation expectation = Expectation.when(httpRequest).thenForward(httpForward);
            List<Expectation> result = httpState.add(expectation);

            ObjectNode resultNode = objectMapper.createObjectNode();
            resultNode.put("status", "created");
            resultNode.put("count", result.size());
            if (!result.isEmpty()) {
                resultNode.put("id", result.get(0).getId());
            }
            resultNode.put("forwardHost", host);
            resultNode.put("forwardPort", port);
            resultNode.put("forwardScheme", scheme);
            return resultNode;
        } catch (Exception e) {
            return errorResult("Failed to create forward expectation", e);
        }
    }

    private void registerDebugRequestMismatch() {
        ObjectNode schema = objectMapper.createObjectNode();
        schema.put("type", "object");
        ObjectNode properties = schema.putObject("properties");
        properties.putObject("method").put("type", "string").put("description", "HTTP method of the request to debug");
        properties.putObject("path").put("type", "string").put("description", "Path of the request to debug");
        properties.putObject("headers").put("type", "object").put("description", "Headers of the request to debug");
        ObjectNode bodyProp = properties.putObject("body");
        bodyProp.put("description", "Body of the request to debug");
        ArrayNode required = schema.putArray("required");
        required.add("method");
        required.add("path");

        tools.put("debug_request_mismatch", new ToolDefinition(
            "debug_request_mismatch",
            "Analyzes why a request does not match any active expectations, showing per-field match failures for each expectation",
            schema,
            this::handleDebugRequestMismatch
        ));
    }

    private JsonNode handleDebugRequestMismatch(JsonNode params) {
        try {
            HttpRequest httpRequest = request()
                .withMethod(params.path("method").asText())
                .withPath(params.path("path").asText());

            JsonNode headersNode = params.path("headers");
            if (headersNode.isObject()) {
                Iterator<Map.Entry<String, JsonNode>> fields = headersNode.fields();
                while (fields.hasNext()) {
                    Map.Entry<String, JsonNode> entry = fields.next();
                    httpRequest.withHeader(entry.getKey(), entry.getValue().asText());
                }
            }

            JsonNode bodyNode = params.path("body");
            if (!bodyNode.isMissingNode() && !bodyNode.isNull()) {
                if (bodyNode.isTextual()) {
                    httpRequest.withBody(bodyNode.asText());
                } else {
                    httpRequest.withBody(objectMapper.writeValueAsString(bodyNode));
                }
            }

            HttpRequest wrapperRequest = request()
                .withMethod("PUT")
                .withBody(getRequestDefinitionSerializer().serialize(httpRequest));
            HttpResponse response = httpState.debugMismatch(wrapperRequest);
            JsonNode resultNode = objectMapper.readTree(response.getBodyAsString());

            // enhance: sort results by closeness (fewest differing fields first) and add remediation hints
            if (resultNode.has("results") && resultNode.get("results").isArray()) {
                ArrayNode results = (ArrayNode) resultNode.get("results");
                List<JsonNode> sortedResults = new ArrayList<>();
                for (JsonNode node : results) {
                    sortedResults.add(node);
                }
                sortedResults.sort((a, b) -> {
                    int aDiff = a.path("totalFieldCount").asInt(0) - a.path("matchedFieldCount").asInt(0);
                    int bDiff = b.path("totalFieldCount").asInt(0) - b.path("matchedFieldCount").asInt(0);
                    return Integer.compare(aDiff, bDiff);
                });

                ArrayNode rankedResults = objectMapper.createArrayNode();
                for (JsonNode node : sortedResults) {
                    // add remediation hints to each result that has differences
                    if (node.has("differences") && node.get("differences").isObject()) {
                        ObjectNode resultWithRemediation = (ObjectNode) node;
                        ObjectNode remediationNode = objectMapper.createObjectNode();
                        Iterator<Map.Entry<String, JsonNode>> diffFields = node.get("differences").fields();
                        while (diffFields.hasNext()) {
                            Map.Entry<String, JsonNode> diffField = diffFields.next();
                            MatchDifference.Field field = fieldFromName(diffField.getKey());
                            if (field != null && diffField.getValue().isArray()) {
                                List<String> diffs = new ArrayList<>();
                                for (JsonNode d : diffField.getValue()) {
                                    diffs.add(d.asText());
                                }
                                String hint = MismatchRemediation.hint(field, diffs);
                                if (!hint.isEmpty()) {
                                    remediationNode.put(diffField.getKey(), hint);
                                }
                            }
                        }
                        if (remediationNode.size() > 0) {
                            resultWithRemediation.set("remediation", remediationNode);
                        }
                    }
                    rankedResults.add(node);
                }
                ((ObjectNode) resultNode).set("results", rankedResults);

                // add remediation hint to closestMatch if present and is a JSON object
                if (resultNode.has("closestMatch") && resultNode.get("closestMatch").isObject() && rankedResults.size() > 0) {
                    JsonNode closest = rankedResults.get(0);
                    if (closest.has("remediation")) {
                        ((ObjectNode) resultNode.get("closestMatch")).set("remediation", closest.get("remediation"));
                    }
                }
            }

            return resultNode;
        } catch (Exception e) {
            return errorResult("Failed to debug request mismatch", e);
        }
    }

    private static MatchDifference.Field fieldFromName(String name) {
        for (MatchDifference.Field field : MatchDifference.Field.values()) {
            if (field.getName().equals(name)) {
                return field;
            }
        }
        return null;
    }

    private void registerExplainUnmatchedRequests() {
        ObjectNode schema = objectMapper.createObjectNode();
        schema.put("type", "object");
        ObjectNode properties = schema.putObject("properties");
        properties.putObject("limit").put("type", "integer").put("description", "Maximum number of unmatched requests to return (default 10, max 100)");

        tools.put("explain_unmatched_requests", new ToolDefinition(
            "explain_unmatched_requests",
            "Returns recent requests that hit MockServer and matched no expectation, with ranked closest-expectation diagnostics and actionable remediation hints for each mismatch. Use after a failed test run to understand why requests got 404s without needing to reconstruct the request. "
                + "A request MockServer forwarded or proxied that failed with a 502 is not listed: explain_forward_failure explains it.",
            schema,
            this::handleExplainUnmatchedRequests
        ));
    }

    private JsonNode handleExplainUnmatchedRequests(JsonNode params) {
        try {
            int limit = params.path("limit").asInt(10);
            if (limit < 1 || limit > 100) {
                return errorResult("'limit' must be between 1 and 100");
            }

            HttpRequest wrapperRequest = request()
                .withMethod("PUT")
                .withBody("{\"limit\":" + limit + "}");
            HttpResponse response = httpState.explainUnmatched(wrapperRequest);
            return objectMapper.readTree(response.getBodyAsString());
        } catch (Exception e) {
            return errorResult("Failed to explain unmatched requests", e);
        }
    }

    private void registerExplainRequest() {
        ObjectNode schema = objectMapper.createObjectNode();
        schema.put("type", "object");
        ObjectNode properties = schema.putObject("properties");
        properties.putObject("correlationId").put("type", "string").put("description", "Correlation id of one request MockServer has received, as shown by retrieve_logs (each log entry carries it) or the dashboard log");
        schema.putArray("required").add("correlationId");

        tools.put("explain_request", new ToolDefinition(
            "explain_request",
            "Explains one request MockServer has already received, identified by its correlation id: which expectation would serve it now, "
                + "why each expectation evaluated ahead of that one was passed over (NAMESPACE, INACTIVE, FIELDS_DIFFER with per-field differences "
                + "and remediation hints, CONVERSATION, SCENARIO_STATE, PERCENTAGE), which lower-priority expectations it shadows, and the outcome "
                + "when it arrived, with atArrival.forwardFailure giving the reason when its forward failed with a 502. Recomputed now against the current expectations without consuming Times or moving scenarios; says whether the "
                + "expectations changed since. Recorded values are redacted as in the event log (redactSecretsInLog). "
                + "Equivalent to PUT /mockserver/explain?correlationId=<id>.",
            schema,
            this::handleExplainRequest
        ));
    }

    private JsonNode handleExplainRequest(JsonNode params) {
        try {
            JsonNode correlationIdNode = params.path("correlationId");
            if (!correlationIdNode.isTextual() || correlationIdNode.asText().isBlank()) {
                return errorResult("'correlationId' is required");
            }
            HttpResponse response = httpState.explain(request()
                .withMethod("PUT")
                .withPath("/mockserver/explain")
                .withQueryStringParameter("correlationId", correlationIdNode.asText()));
            JsonNode body = objectMapper.readTree(response.getBodyAsString());
            if (response.getStatusCode() == null || response.getStatusCode() != 200) {
                ObjectNode error = errorResult(body.path("error").asText("Failed to explain request"));
                error.put("correlationId", correlationIdNode.asText());
                return error;
            }
            return body;
        } catch (Exception e) {
            return errorResult("Failed to explain request", e);
        }
    }

    private void registerDiagnoseConfig() {
        ObjectNode schema = objectMapper.createObjectNode();
        schema.put("type", "object");
        ObjectNode properties = schema.putObject("properties");
        properties.putObject("name").put("type", "string").put("description", "Optional property name or fragment to look up, in any form (maxExpectations, mockserver.maxExpectations or MOCKSERVER_MAX_EXPECTATIONS); "
            + "returns every matching property, including those at their default with the default's value. Without it, only the properties set away from their default are returned.");

        tools.put("diagnose_config", new ToolDefinition(
            "diagnose_config",
            "Reports MockServer's effective configuration: each mockserver.* property set away from its default, its value, the source that supplied it "
                + "(system-property, properties-file, environment-variable or runtime-set) and its built-in default ('default', when known). Pass 'name' to check whether a particular setting took effect. "
                + "Also lists each unrecognised mockserver.* / MOCKSERVER_* key, which MockServer ignores, by name only with the property it was probably meant to be. "
                + "Credential values are always redacted. Reports the same values and sources as GET /mockserver/config, which lists every property.",
            schema,
            this::handleDiagnoseConfig
        ));
    }

    private JsonNode handleDiagnoseConfig(JsonNode params) {
        try {
            JsonNode nameNode = params.path("name");
            String requestedName = nameNode.isTextual() ? nameNode.asText().trim() : "";
            String filter = ConfigurationProperties.normaliseKeyForSuggestion(requestedName);
            List<ConfigurationProperties.ResolvedProperty> resolved = ConfigurationProperties.effectiveConfiguration();
            ArrayNode matching = objectMapper.createArrayNode();
            int explicitlySet = 0;
            for (ConfigurationProperties.ResolvedProperty property : resolved) {
                boolean atDefault = ConfigurationProperties.SOURCE_DEFAULT.equals(property.getSource());
                if (!atDefault) {
                    explicitlySet++;
                }
                if (filter.isEmpty() ? !atDefault : ConfigurationProperties.normaliseKeyForSuggestion(property.getName()).contains(filter)) {
                    ObjectNode propertyNode = matching.addObject();
                    propertyNode.put("name", property.getName());
                    propertyNode.put("value", property.getValue());
                    propertyNode.put("source", property.getSource());
                    if (property.getDefaultValue() != null) {
                        propertyNode.put("default", property.getDefaultValue());
                    }
                }
            }
            ObjectNode resultNode = objectMapper.createObjectNode();
            resultNode.set("properties", matching);
            resultNode.put("count", matching.size());
            resultNode.put("explicitlySetCount", explicitlySet);
            resultNode.put("totalProperties", resolved.size());
            // names only: an unrecognised key's value may be a credential, so it is never read
            ArrayNode unrecognised = resultNode.putArray("unrecognised");
            for (ConfigurationProperties.UnrecognisedProperty property : ConfigurationProperties.unrecognisedConfiguration()) {
                ObjectNode unrecognisedNode = unrecognised.addObject();
                unrecognisedNode.put("name", property.getName());
                unrecognisedNode.put("setAs", property.getSetAs());
                ArrayNode suggestions = unrecognisedNode.putArray("suggestions");
                property.getSuggestions().forEach(suggestions::add);
                unrecognisedNode.put("systemPropertyOnly", property.isSystemPropertyOnly());
            }
            resultNode.put("note", "values come from system properties, the properties file, environment variables and runtime setters; "
                + "a value set only on an embedded server's Configuration object or through PUT /mockserver/configuration is not shown; "
                + "each key in 'unrecognised' is ignored, so set the suggested property instead");
            if (!filter.isEmpty() && matching.isEmpty()) {
                List<String> suggestions = ConfigurationProperties.suggestRecognisedKeys(requestedName, ConfigurationProperties.FORM_SYSTEM_PROPERTY);
                resultNode.put("hint", "no recognised configuration property name contains '" + requestedName + "'; "
                    + (suggestions.isEmpty() ? "check the spelling" : "did you mean " + String.join(" or ", suggestions) + "?"));
                ArrayNode suggestionsNode = resultNode.putArray("suggestions");
                suggestions.forEach(suggestionsNode::add);
            }
            return resultNode;
        } catch (Exception e) {
            return errorResult("Failed to diagnose configuration", e);
        }
    }

    private void registerExplainForwardFailure() {
        ObjectNode schema = objectMapper.createObjectNode();
        schema.put("type", "object");
        ObjectNode properties = schema.putObject("properties");
        properties.putObject("correlationId").put("type", "string").put("description", "Optional correlation id of one request whose forward failed, as shown by retrieve_logs; without it, the most recent failed forwards are returned");
        properties.putObject("limit").put("type", "integer").put("description", "How many of the most recent failed forwards to return when no correlationId is given (default 5, max " + EXPLAIN_FORWARD_FAILURE_MAX + ")");

        tools.put("explain_forward_failure", new ToolDefinition(
            "explain_forward_failure",
            "Explains why MockServer could not forward or proxy a request: returns the recorded failed forwards (most recent first, or the one with "
                + "'correlationId'), each with its method, path, status code and the reason its 502 gave. MockServer names the cause and what to change "
                + "when it can (causeNamed true): a TLS failure, an HTTP/2 error, a connection it could not set up, an upstream response it could not "
                + "decode, an unresolvable host, a refused or timed-out connection, no route, local ports or files running out, an unreachable upstream "
                + "proxy, a pooled connection closed under a non-idempotent request; forwardLoopSuspected is true when the forward went back to MockServer "
                + "itself. Reasons are redacted as in the event log (redactSecretsInLog). Failed forwards are recorded only at logLevel INFO or more detailed.",
            schema,
            this::handleExplainForwardFailure
        ));
    }

    private static final int EXPLAIN_FORWARD_FAILURE_MAX = 20;

    private JsonNode handleExplainForwardFailure(JsonNode params) {
        try {
            JsonNode correlationIdNode = params.path("correlationId");
            if (!correlationIdNode.isMissingNode() && !correlationIdNode.isNull() && (!correlationIdNode.isTextual() || correlationIdNode.asText().isBlank())) {
                return errorResult("'correlationId' must be a non-blank string");
            }
            int limit = params.path("limit").asInt(5);
            if (limit < 1 || limit > EXPLAIN_FORWARD_FAILURE_MAX) {
                return errorResult("'limit' must be between 1 and " + EXPLAIN_FORWARD_FAILURE_MAX);
            }
            String correlationId = correlationIdNode.isTextual() ? correlationIdNode.asText() : null;
            HttpResponse response = httpState.explainForwardFailures(correlationId, limit);
            JsonNode body = objectMapper.readTree(response.getBodyAsString());
            if (response.getStatusCode() == null || response.getStatusCode() != 200) {
                ObjectNode error = errorResult(body.path("error").asText("Failed to explain forward failure"));
                if (correlationId != null) {
                    error.put("correlationId", correlationId);
                }
                return error;
            }
            return body;
        } catch (Exception e) {
            return errorResult("Failed to explain forward failure", e);
        }
    }

    private void registerTestTemplate() {
        ObjectNode schema = objectMapper.createObjectNode();
        schema.put("type", "object");
        ObjectNode properties = schema.putObject("properties");
        ObjectNode templateType = properties.putObject("templateType");
        templateType.put("type", "string").put("description", "Template engine");
        templateType.putArray("enum").add("VELOCITY").add("MUSTACHE").add("JAVASCRIPT");
        properties.putObject("template").put("type", "string").put("description", "The template text, as it would appear in an httpResponseTemplate or httpForwardTemplate");
        ObjectNode actionType = properties.putObject("actionType");
        actionType.put("type", "string").put("description", "RESPONSE_TEMPLATE (default) renders a response; FORWARD_TEMPLATE renders the request to forward");
        actionType.putArray("enum").add("RESPONSE_TEMPLATE").add("FORWARD_TEMPLATE");
        properties.putObject("request").put("type", "object").put("description", "Optional sample request in MockServer's JSON request format (method, path, queryStringParameters, headers, body); defaults to GET /");
        schema.putArray("required").add("templateType").add("template");

        tools.put("test_template", new ToolDefinition(
            "test_template",
            "Dry-runs a Velocity, Mustache or JavaScript response or forward template against a sample request without creating an expectation, "
                + "using the server's own engines, sandbox and time limit. Returns the rendered response or request, or the failure with its template "
                + "line and column and a hint, plus warnings for unresolved Velocity references and scenario.set calls (reported, never applied). "
                + "Nothing reaches the event log. It runs the template code it is sent, so it needs the same mutate role as "
                + "PUT /mockserver/template/test, which it is equivalent to.",
            schema,
            this::handleTestTemplate
        ));
    }

    private JsonNode handleTestTemplate(JsonNode params) {
        try {
            ObjectNode body = objectMapper.createObjectNode();
            for (String field : new String[]{"templateType", "template", "actionType", "request"}) {
                if (params.hasNonNull(field)) {
                    body.set(field, params.get(field));
                }
            }
            // completes within the template time limit plus the dry run's grace period, so join() is bounded
            HttpResponse response = httpState.testTemplate(request()
                .withMethod("PUT")
                .withPath("/mockserver/template/test")
                .withBody(objectMapper.writeValueAsString(body))).join();
            JsonNode result = objectMapper.readTree(response.getBodyAsString());
            if (response.getStatusCode() == null || response.getStatusCode() != 200) {
                return errorResult(result.path("error").asText("Failed to test template"));
            }
            return result;
        } catch (Exception e) {
            return errorResult("Failed to test template", e);
        }
    }

    private static final int LINT_MAX_ISSUES_RETURNED = 1000;

    private void registerLintExpectations() {
        ObjectNode schema = objectMapper.createObjectNode();
        schema.put("type", "object");
        ObjectNode properties = schema.putObject("properties");
        ObjectNode expectationsProperty = properties.putObject("expectations");
        expectationsProperty.putArray("type").add("object").add("array");
        expectationsProperty.put("description", "Optional expectation, or array of expectations, in MockServer's JSON format to lint without storing them; "
            + "without it, the active expectations are linted");
        properties.putObject("maxIssues").put("type", "integer").put("description", "Most issues to return, errors first (default 100, max " + LINT_MAX_ISSUES_RETURNED + "); the counts always cover every issue found");

        tools.put("lint_expectations", new ToolDefinition(
            "lint_expectations",
            "Lints expectations for mistakes that make them never match or never answer: an expectation shadowed by an earlier one, times / time to "
                + "live / percentage of 0, unreachable sequential responses, path mistakes (no leading slash, a scheme and host or a query string in "
                + "the path, a glob wildcard, an OpenAPI {param} with no path parameters), method and header-name typos, duplicate ids, invalid "
                + "expectations (error) and STRICT JSON bodies with arrays (info). Lints the active expectations, or the ones passed in without storing "
                + "them. Advisory only: nothing is rejected or changed. Equivalent to PUT /mockserver/lint.",
            schema,
            this::handleLintExpectations
        ));
    }

    private JsonNode handleLintExpectations(JsonNode params) {
        try {
            int maxIssues = params.path("maxIssues").asInt(100);
            if (maxIssues < 1 || maxIssues > LINT_MAX_ISSUES_RETURNED) {
                return errorResult("'maxIssues' must be between 1 and " + LINT_MAX_ISSUES_RETURNED);
            }
            JsonNode expectations = params.path("expectations");
            if (!expectations.isMissingNode() && !expectations.isNull() && !expectations.isObject() && !expectations.isArray()) {
                return errorResult("'expectations' must be an expectation object or an array of expectations");
            }
            HttpRequest lintRequest = request().withMethod("PUT").withPath("/mockserver/lint");
            if (expectations.isObject() || expectations.isArray()) {
                lintRequest.withBody(objectMapper.writeValueAsString(expectations));
            }
            HttpResponse response = httpState.lint(lintRequest);
            JsonNode result = objectMapper.readTree(response.getBodyAsString());
            if (response.getStatusCode() == null || response.getStatusCode() != 200) {
                return errorResult(result.path("error").asText("Failed to lint expectations"));
            }
            JsonNode issues = result.path("issues");
            if (result.isObject() && issues.isArray() && issues.size() > maxIssues) {
                List<JsonNode> ordered = new ArrayList<>();
                issues.forEach(ordered::add);
                ordered.sort(Comparator.comparingInt(issue -> severityRank(issue.path("severity").asText())));
                ArrayNode returned = objectMapper.createArrayNode();
                ordered.subList(0, maxIssues).forEach(returned::add);
                ((ObjectNode) result).set("issues", returned);
                ((ObjectNode) result).put("issuesReturned", maxIssues);
                ((ObjectNode) result).put("issuesOmitted", ordered.size() - maxIssues);
            }
            return result;
        } catch (Exception e) {
            return errorResult("Failed to lint expectations", e);
        }
    }

    private static int severityRank(String severity) {
        switch (severity) {
            case "ERROR":
                return 0;
            case "WARNING":
                return 1;
            default:
                return 2;
        }
    }

    private static final int SUGGEST_FIX_MAX_REQUESTS = 20;

    private void registerSuggestExpectationFix() {
        ObjectNode schema = objectMapper.createObjectNode();
        schema.put("type", "object");
        ObjectNode properties = schema.putObject("properties");
        properties.putObject("limit").put("type", "integer").put("description", "How many of the most recent unmatched requests to suggest fixes for (default 5, max " + SUGGEST_FIX_MAX_REQUESTS + ")");
        properties.putObject("expectationId").put("type", "string").put("description", "Optional: only suggest fixes to this expectation");

        tools.put("suggest_expectation_fix", new ToolDefinition(
            "suggest_expectation_fix",
            "For each recent request that matched no expectation, suggests a fix to each of its five closest expectations: a guarded RFC 6902 JSON "
                + "patch of the expectation, as list_expectations returns it, that changes only its httpRequest so it would match, the fields it "
                + "could not patch with a reason, and matchesAfterPatch. Each change is preceded by a test of the current value, so the patch fails "
                + "if the expectation changed since. Nothing is applied: to apply one, patch the expectation's JSON and send it with raw_expectation "
                + "keeping its id. Values the event log redacts are never copied into a patch. Equivalent to PUT /mockserver/explainUnmatched with "
                + "\"suggestFixes\": true, keeping only the expectations with a suggested fix.",
            schema,
            this::handleSuggestExpectationFix
        ));
    }

    private JsonNode handleSuggestExpectationFix(JsonNode params) {
        try {
            int limit = params.path("limit").asInt(5);
            if (limit < 1 || limit > SUGGEST_FIX_MAX_REQUESTS) {
                return errorResult("'limit' must be between 1 and " + SUGGEST_FIX_MAX_REQUESTS);
            }
            JsonNode expectationIdNode = params.path("expectationId");
            if (!expectationIdNode.isMissingNode() && !expectationIdNode.isNull() && (!expectationIdNode.isTextual() || expectationIdNode.asText().isBlank())) {
                return errorResult("'expectationId' must be a non-blank string");
            }
            String expectationId = expectationIdNode.isTextual() ? expectationIdNode.asText() : null;
            HttpResponse response = httpState.explainUnmatched(request()
                .withMethod("PUT")
                .withBody(objectMapper.writeValueAsString(objectMapper.createObjectNode().put("limit", limit).put("suggestFixes", true))));
            JsonNode result = objectMapper.readTree(response.getBodyAsString());
            if (response.getStatusCode() == null || response.getStatusCode() != 200 || !result.isObject()) {
                return errorResult(result.path("error").asText("Failed to suggest expectation fixes"));
            }
            ArrayNode requests = objectMapper.createArrayNode();
            int fixCount = 0;
            for (JsonNode unmatched : result.path("unmatchedRequests")) {
                ArrayNode withFix = objectMapper.createArrayNode();
                for (JsonNode closest : unmatched.path("closestExpectations")) {
                    if (closest.has("suggestedFix") && (expectationId == null || expectationId.equals(closest.path("expectationId").asText()))) {
                        withFix.add(closest);
                    }
                }
                if (withFix.isEmpty() && expectationId != null) {
                    continue;
                }
                ObjectNode requestNode = unmatched.isObject() ? ((ObjectNode) unmatched).deepCopy() : objectMapper.createObjectNode();
                requestNode.set("closestExpectations", withFix);
                requests.add(requestNode);
                fixCount += withFix.size();
            }
            ObjectNode reshaped = (ObjectNode) result;
            reshaped.set("unmatchedRequests", requests);
            reshaped.put("unmatchedRequestCount", requests.size());
            reshaped.put("suggestedFixCount", fixCount);
            if (fixCount == 0) {
                reshaped.put("note", expectationId != null
                    ? "no recent unmatched request has a suggested fix for expectation '" + expectationId + "'"
                    : "no recent unmatched request has a close expectation to fix; explain_unmatched_requests shows every comparison");
            }
            return reshaped;
        } catch (Exception e) {
            return errorResult("Failed to suggest expectation fixes", e);
        }
    }

    /**
     * Keeps a tool result for a spec with very many problems small; the dashboard shows the same number.
     */
    static final int MAX_LOAD_PROBLEMS = 50;

    private void registerCreateExpectationFromOpenApi() {
        ObjectNode schema = objectMapper.createObjectNode();
        schema.put("type", "object");
        ObjectNode properties = schema.putObject("properties");
        properties.putObject("specUrlOrPayload").put("type", "string").put("description", "OpenAPI spec URL or JSON/YAML payload");
        properties.putObject("operationsAndResponses").put("type", "object").put("description", "Map of operationId to response status code (string) or object with statusCode and optional exampleName");
        properties.putObject("contextPathPrefix").put("type", "string").put("description", "Optional path prefix to prepend to all OpenAPI spec paths");
        schema.putArray("required").add("specUrlOrPayload");

        tools.put("create_expectation_from_openapi", new ToolDefinition(
            "create_expectation_from_openapi",
            "Creates expectations from an OpenAPI specification, optionally specifying which operations and response codes to use. "
                + "loadProblems lists where a spec that loaded breaks the OpenAPI rules (kind, JSON-pointer location, description, hint), which can stop parts of it working as expected; "
                + "at most " + MAX_LOAD_PROBLEMS + " are listed, and loadProblemsNotShown counts the rest",
            schema,
            this::handleCreateExpectationFromOpenApi
        ));
    }

    private JsonNode handleCreateExpectationFromOpenApi(JsonNode params) {
        try {
            String specUrlOrPayload = params.path("specUrlOrPayload").asText();
            OpenAPIExpectation openAPIExpectation = OpenAPIExpectation.openAPIExpectation(specUrlOrPayload);

            JsonNode opsNode = params.path("operationsAndResponses");
            if (opsNode.isObject()) {
                Map<String, Object> operationsAndResponses = new LinkedHashMap<>();
                Iterator<Map.Entry<String, JsonNode>> fields = opsNode.fields();
                while (fields.hasNext()) {
                    Map.Entry<String, JsonNode> entry = fields.next();
                    if (entry.getValue().isTextual()) {
                        operationsAndResponses.put(entry.getKey(), entry.getValue().asText());
                    } else if (entry.getValue().isObject()) {
                        Map<String, Object> richValue = new LinkedHashMap<>();
                        JsonNode statusCode = entry.getValue().path("statusCode");
                        if (!statusCode.isMissingNode()) {
                            richValue.put("statusCode", statusCode.asText());
                        }
                        JsonNode exampleNameNode = entry.getValue().path("exampleName");
                        if (!exampleNameNode.isMissingNode()) {
                            richValue.put("exampleName", exampleNameNode.asText());
                        }
                        operationsAndResponses.put(entry.getKey(), richValue);
                    }
                }
                openAPIExpectation.withOperationsAndResponses(operationsAndResponses);
            }

            JsonNode contextPathNode = params.path("contextPathPrefix");
            if (contextPathNode.isTextual()) {
                openAPIExpectation.withContextPathPrefix(contextPathNode.asText());
            }

            HttpState.OpenAPIImport result = httpState.addOpenAPI(openAPIExpectation);

            ObjectNode resultNode = objectMapper.createObjectNode();
            resultNode.put("status", "created");
            resultNode.put("count", result.expectations().size());
            ArrayNode ids = resultNode.putArray("ids");
            for (Expectation exp : result.expectations()) {
                ids.add(exp.getId());
            }
            ArrayNode loadProblems = resultNode.putArray("loadProblems");
            List<OpenAPILoadProblems.Problem> problems = result.loadProblems();
            for (OpenAPILoadProblems.Problem problem : problems.subList(0, Math.min(problems.size(), MAX_LOAD_PROBLEMS))) {
                problem.writeTo(loadProblems.addObject());
            }
            if (problems.size() > MAX_LOAD_PROBLEMS) {
                resultNode.put("loadProblemsNotShown", problems.size() - MAX_LOAD_PROBLEMS);
            }
            return resultNode;
        } catch (Exception e) {
            return errorResult("Failed to create expectations from OpenAPI", e);
        }
    }

    private void registerCreateExpectationsFromRecordedTraffic() {
        ObjectNode schema = objectMapper.createObjectNode();
        schema.put("type", "object");
        ObjectNode properties = schema.putObject("properties");
        properties.putObject("method").put("type", "string").put("description", "Filter recorded traffic by HTTP method (e.g. GET, POST)");
        properties.putObject("path").put("type", "string").put("description", "Filter recorded traffic by request path (e.g. /api/users)");
        properties.putObject("preview").put("type", "boolean").put("description", "When true, return the generated expectations as JSON without adding them. Defaults to false.");

        tools.put("create_expectations_from_recorded_traffic", new ToolDefinition(
            "create_expectations_from_recorded_traffic",
            "Converts traffic already recorded by MockServer's forwarding/proxy mode into active mock expectations in one step. "
                + "After observing a real API via a forwarding proxy, call this tool to generate expectations that replay the recorded responses. "
                + "Use preview=true to inspect the expectations before committing, or leave it false (default) to activate them immediately.",
            schema,
            this::handleCreateExpectationsFromRecordedTraffic
        ));
    }

    private JsonNode handleCreateExpectationsFromRecordedTraffic(JsonNode params) {
        try {
            // Build a filter request to retrieve RECORDED_EXPECTATIONS
            HttpRequest filterRequest = request();
            JsonNode methodNode = params.path("method");
            if (!methodNode.isMissingNode() && !methodNode.isNull()) {
                filterRequest.withMethod(methodNode.asText());
            }
            JsonNode pathNode = params.path("path");
            if (!pathNode.isMissingNode() && !pathNode.isNull()) {
                filterRequest.withPath(pathNode.asText());
            }

            boolean preview = params.path("preview").asBoolean(false);

            // Use the existing retrieve mechanism with RECORDED_EXPECTATIONS type
            HttpRequest retrieveRequest = request()
                .withMethod("PUT")
                .withPath("/mockserver/retrieve")
                .withQueryStringParameter("type", "RECORDED_EXPECTATIONS")
                .withQueryStringParameter("format", "JSON")
                .withBody(getRequestDefinitionSerializer().serialize(filterRequest));

            HttpResponse retrieveResponse = httpState.retrieve(retrieveRequest);
            String body = retrieveResponse.getBodyAsString();

            if (body == null || body.isEmpty() || "[]".equals(body.trim())) {
                ObjectNode resultNode = objectMapper.createObjectNode();
                resultNode.put("status", "no_recorded_traffic");
                resultNode.put("message", "No recorded traffic found matching the filter. "
                    + "Ensure MockServer has forwarded requests (e.g. via create_forward_expectation) before calling this tool.");
                resultNode.put("count", 0);
                return resultNode;
            }

            // Deserialize the recorded expectations
            Expectation[] recordedExpectations = getExpectationSerializer().deserializeArray(body, false);

            if (recordedExpectations.length == 0) {
                ObjectNode resultNode = objectMapper.createObjectNode();
                resultNode.put("status", "no_recorded_traffic");
                resultNode.put("message", "No recorded traffic found matching the filter.");
                resultNode.put("count", 0);
                return resultNode;
            }

            // Like promote: these mocks serve applications that call MockServer directly, with their own Host.
            for (int i = 0; i < recordedExpectations.length; i++) {
                recordedExpectations[i] = TransportHeaderFilter.withoutTransportHeaders(recordedExpectations[i], true);
            }

            if (preview) {
                // Return the expectations as JSON without adding them
                ObjectNode resultNode = objectMapper.createObjectNode();
                resultNode.put("status", "preview");
                resultNode.put("count", recordedExpectations.length);
                resultNode.put("message", "Preview of expectations that would be created. Call again with preview=false to activate them.");
                ArrayNode expectationsArray = resultNode.putArray("expectations");
                for (Expectation exp : recordedExpectations) {
                    JsonNode expNode = objectMapper.readTree(getExpectationSerializer().serialize(exp));
                    expectationsArray.add(expNode);
                }
                return resultNode;
            } else {
                // Make expectations persistent: use unlimited times and TTL
                List<Expectation> addedExpectations = new ArrayList<>();
                for (Expectation recordedExp : recordedExpectations) {
                    Expectation persistentExp = new Expectation(
                        recordedExp.getHttpRequest(),
                        Times.unlimited(),
                        TimeToLive.unlimited(),
                        0
                    ).thenRespond(recordedExp.getHttpResponse());
                    addedExpectations.addAll(httpState.add(persistentExp));
                }

                ObjectNode resultNode = objectMapper.createObjectNode();
                resultNode.put("status", "created");
                resultNode.put("count", addedExpectations.size());
                ArrayNode ids = resultNode.putArray("ids");
                for (Expectation exp : addedExpectations) {
                    ids.add(exp.getId());
                }
                return resultNode;
            }
        } catch (Exception e) {
            return errorResult("Failed to create expectations from recorded traffic", e);
        }
    }

    private void registerStopServer() {
        ObjectNode schema = objectMapper.createObjectNode();
        schema.put("type", "object");
        schema.putObject("properties");

        tools.put("stop_server", new ToolDefinition(
            "stop_server",
            "Stops the MockServer instance",
            schema,
            this::handleStopServer
        ));
    }

    private JsonNode handleStopServer(JsonNode params) {
        try {
            ObjectNode resultNode = objectMapper.createObjectNode();
            resultNode.put("status", "stopping");
            resultNode.put("message", "MockServer is shutting down");

            new Scheduler.SchedulerThreadFactory("MockServer MCP Stop").newThread(() -> server.stop()).start();

            return resultNode;
        } catch (Exception e) {
            return errorResult("Failed to stop server", e);
        }
    }

    private void registerRawExpectation() {
        ObjectNode schema = objectMapper.createObjectNode();
        schema.put("type", "object");
        ObjectNode properties = schema.putObject("properties");
        properties.putObject("expectation").put("type", "object").put("description", "Full expectation JSON in MockServer format");
        schema.putArray("required").add("expectation");

        tools.put("raw_expectation", new ToolDefinition(
            "raw_expectation",
            "Creates an expectation using the full MockServer JSON format, supporting all features including templates, callbacks, and overrides",
            schema,
            this::handleRawExpectation
        ));
    }

    private JsonNode handleRawExpectation(JsonNode params) {
        try {
            JsonNode expectationNode = params.path("expectation");
            if (!expectationNode.isObject() && !(expectationNode.isArray() && expectationNode.size() > 0)) {
                return errorResult("'expectation' must be a JSON object in MockServer expectation format, or a non-empty array of them, " +
                    "for example {\"httpRequest\": {\"method\": \"GET\", \"path\": \"/hello\"}, \"httpResponse\": {\"statusCode\": 200, \"body\": \"hi\"}}");
            }
            List<String> validationErrors = expectationSchemaErrors(expectationNode);
            if (!validationErrors.isEmpty()) {
                ObjectNode result = errorResult("The expectation does not match the MockServer expectation schema: correct each entry of " +
                    "'validationErrors' (a JSON path, then what is wrong there) and call raw_expectation again");
                ArrayNode errors = result.putArray("validationErrors");
                validationErrors.forEach(errors::add);
                return result;
            }
            String expectationJson = objectMapper.writeValueAsString(expectationNode);

            Expectation[] expectations = getExpectationSerializer().deserializeArray(expectationJson, false);
            List<Expectation> allResults = new ArrayList<>();
            for (Expectation exp : expectations) {
                allResults.addAll(httpState.add(exp));
            }

            ObjectNode resultNode = objectMapper.createObjectNode();
            resultNode.put("status", "created");
            resultNode.put("count", allResults.size());
            ArrayNode ids = resultNode.putArray("ids");
            for (Expectation exp : allResults) {
                ids.add(exp.getId());
            }
            return resultNode;
        } catch (Exception e) {
            return errorResult("Failed to create raw expectation", e);
        }
    }

    /**
     * The expectation schema's errors for each entry of {@code expectationNode}, one per item, prefixed with the
     * entry's position when there are several. Entries in the OpenAPI form are left to the deserializer.
     */
    private List<String> expectationSchemaErrors(JsonNode expectationNode) throws JsonProcessingException {
        List<JsonNode> entries = new ArrayList<>();
        if (expectationNode.isArray()) {
            expectationNode.forEach(entries::add);
        } else {
            entries.add(expectationNode);
        }
        List<String> errors = new ArrayList<>();
        for (int i = 0; i < entries.size(); i++) {
            JsonNode entry = entries.get(i);
            String prefix = entries.size() > 1 ? "expectation " + (i + 1) + ": " : "";
            if (!entry.isObject()) {
                errors.add(prefix + "must be an object");
                continue;
            }
            if (entry.has("specUrlOrPayload")) {
                continue;
            }
            String report = getExpectationValidator().isValid(objectMapper.writeValueAsString(entry), false);
            if (report == null || report.isEmpty()) {
                continue;
            }
            int before = errors.size();
            for (String line : report.split("\\R")) {
                if (line.startsWith(" - ")) {
                    errors.add(prefix + line.substring(3).trim());
                } else if (errors.size() > before && !line.isBlank()) {
                    errors.set(errors.size() - 1, errors.get(errors.size() - 1) + " " + line.trim());
                }
            }
            if (errors.size() == before) {
                errors.add(prefix + "does not match the expectation schema");
            }
        }
        return errors;
    }

    private JsonSchemaExpectationValidator getExpectationValidator() {
        if (expectationValidator == null) {
            expectationValidator = JsonSchemaExpectationValidator.jsonSchemaExpectationValidator(mockServerLogger);
        }
        return expectationValidator;
    }

    private void registerRawRetrieve() {
        ObjectNode schema = objectMapper.createObjectNode();
        schema.put("type", "object");
        ObjectNode properties = schema.putObject("properties");
        properties.putObject("requestDefinition").put("type", "object").put("description", "Request definition to filter results");
        ObjectNode typeProp = properties.putObject("type");
        typeProp.put("type", "string").put("description", "Type of data to retrieve");
        ArrayNode typeEnum = typeProp.putArray("enum");
        typeEnum.add("REQUESTS");
        typeEnum.add("REQUEST_RESPONSES");
        typeEnum.add("RECORDED_EXPECTATIONS");
        typeEnum.add("ACTIVE_EXPECTATIONS");
        typeEnum.add("LOGS");
        ObjectNode formatProp = properties.putObject("format");
        formatProp.put("type", "string").put("description", "Response format");
        ArrayNode formatEnum = formatProp.putArray("enum");
        formatEnum.add("JSON");
        formatEnum.add("JAVA");
        formatEnum.add("LOG_ENTRIES");
        properties.putObject("correlationId").put("type", "string").put("description", "Filter log entries by correlation ID (only applies when type is LOGS)");

        tools.put("raw_retrieve", new ToolDefinition(
            "raw_retrieve",
            "Retrieves data from MockServer using the full retrieve API with complete control over type, format, and correlation ID filtering",
            schema,
            this::handleRawRetrieve
        ));
    }

    private JsonNode handleRawRetrieve(JsonNode params) {
        try {
            String type = params.path("type").asText("REQUESTS");
            if (!"REQUESTS".equals(type) && !"REQUEST_RESPONSES".equals(type) && !"RECORDED_EXPECTATIONS".equals(type) && !"ACTIVE_EXPECTATIONS".equals(type) && !"LOGS".equals(type)) {
                return errorResult("'type' must be one of: REQUESTS, REQUEST_RESPONSES, RECORDED_EXPECTATIONS, ACTIVE_EXPECTATIONS, LOGS");
            }
            String format = params.path("format").asText("JSON");
            if (!"JSON".equals(format) && !"JAVA".equals(format) && !"LOG_ENTRIES".equals(format)) {
                return errorResult("'format' must be one of: JSON, JAVA, LOG_ENTRIES");
            }

            String body;
            JsonNode requestDefNode = params.path("requestDefinition");
            boolean hasRequestDef = requestDefNode.isObject();

            // delegate to shared helper for LOGS + LOG_ENTRIES without a request body filter
            if ("LOGS".equals(type) && "LOG_ENTRIES".equals(format) && !hasRequestDef) {
                JsonNode correlationIdNode = params.path("correlationId");
                String correlationId = (correlationIdNode.isTextual() && !correlationIdNode.asText().isEmpty())
                    ? correlationIdNode.asText() : null;
                body = retrieveLogsAsLogEntries(correlationId);
            } else {
                HttpRequest retrieveRequest = request()
                    .withMethod("PUT")
                    .withPath("/mockserver/retrieve")
                    .withQueryStringParameter("type", type)
                    .withQueryStringParameter("format", format);

                JsonNode correlationIdNode = params.path("correlationId");
                if (correlationIdNode.isTextual() && !correlationIdNode.asText().isEmpty()) {
                    retrieveRequest.withQueryStringParameter("correlationId", correlationIdNode.asText());
                }

                if (hasRequestDef) {
                    retrieveRequest.withBody(objectMapper.writeValueAsString(requestDefNode));
                }

                HttpResponse retrieveResponse = httpState.retrieve(retrieveRequest);
                body = retrieveResponse.getBodyAsString();
            }

            if (body != null && !body.isEmpty()) {
                try {
                    JsonNode parsed = objectMapper.readTree(body);
                    ObjectNode resultNode = objectMapper.createObjectNode();
                    resultNode.set("data", parsed);
                    return resultNode;
                } catch (Exception e) {
                    ObjectNode resultNode = objectMapper.createObjectNode();
                    resultNode.put("data", body);
                    return resultNode;
                }
            }

            ObjectNode resultNode = objectMapper.createObjectNode();
            resultNode.put("data", "");
            return resultNode;
        } catch (Exception e) {
            return errorResult("Failed to retrieve", e);
        }
    }

    private void registerRawVerify() {
        ObjectNode schema = objectMapper.createObjectNode();
        schema.put("type", "object");
        ObjectNode properties = schema.putObject("properties");
        properties.putObject("verification").put("type", "object").put("description", "Full verification JSON in MockServer format");
        schema.putArray("required").add("verification");

        tools.put("raw_verify", new ToolDefinition(
            "raw_verify",
            "Performs request verification using the full MockServer verification JSON format",
            schema,
            this::handleRawVerify
        ));
    }

    private JsonNode handleRawVerify(JsonNode params) {
        try {
            JsonNode verificationNode = params.path("verification");
            String verificationJson = objectMapper.writeValueAsString(verificationNode);

            org.mockserver.serialization.VerificationSerializer verificationSerializer =
                new org.mockserver.serialization.VerificationSerializer(mockServerLogger);
            Verification verification = verificationSerializer.deserialize(verificationJson);

            Future<String> result = httpState.verify(verification);
            String verificationResult = result.get(10, TimeUnit.SECONDS);

            ObjectNode resultNode = objectMapper.createObjectNode();
            if (verificationResult == null || verificationResult.isEmpty()) {
                resultNode.put("verified", true);
                resultNode.put("message", "Verification passed");
            } else {
                resultNode.put("verified", false);
                resultNode.put("message", verificationResult);
            }
            return resultNode;
        } catch (Exception e) {
            return errorResult("Raw verification failed", e);
        }
    }

    private void registerVerifyTrafficAgainstOpenApi() {
        ObjectNode schema = objectMapper.createObjectNode();
        schema.put("type", "object");
        ObjectNode properties = schema.putObject("properties");
        properties.putObject("specUrlOrPayload").put("type", "string").put("description", "OpenAPI spec as a URL, file path, or inline JSON/YAML payload");
        properties.putObject("method").put("type", "string").put("description", "Filter recorded traffic by HTTP method (e.g. GET, POST)");
        properties.putObject("path").put("type", "string").put("description", "Filter recorded traffic by request path (e.g. /api/users)");
        schema.putArray("required").add("specUrlOrPayload");

        tools.put("verify_traffic_against_openapi", new ToolDefinition(
            "verify_traffic_against_openapi",
            "Verify the API calls already recorded by MockServer conform to an OpenAPI contract. "
                + "Pulls recorded request-response pairs from the event log, validates each against the spec, "
                + "and returns a per-pair conformance report with request and response validation errors.",
            schema,
            this::handleVerifyTrafficAgainstOpenApi
        ));
    }

    private JsonNode handleVerifyTrafficAgainstOpenApi(JsonNode params) {
        try {
            String specUrlOrPayload = params.path("specUrlOrPayload").asText(null);
            if (specUrlOrPayload == null || specUrlOrPayload.trim().isEmpty()) {
                return errorResult("'specUrlOrPayload' is required and must not be blank");
            }

            // Retrieve recorded request-response pairs
            HttpRequest filterRequest = request();
            JsonNode methodNode = params.path("method");
            if (!methodNode.isMissingNode() && !methodNode.isNull()) {
                filterRequest.withMethod(methodNode.asText());
            }
            JsonNode pathNode = params.path("path");
            if (!pathNode.isMissingNode() && !pathNode.isNull()) {
                filterRequest.withPath(pathNode.asText());
            }

            HttpRequest retrieveRequest = request()
                .withMethod("PUT")
                .withPath("/mockserver/retrieve")
                .withQueryStringParameter("type", "REQUEST_RESPONSES")
                .withQueryStringParameter("format", "JSON")
                .withBody(getRequestDefinitionSerializer().serialize(filterRequest));

            HttpResponse retrieveResponse = httpState.retrieve(retrieveRequest);
            String body = retrieveResponse.getBodyAsString();

            if (body == null || body.isEmpty() || "[]".equals(body.trim())) {
                ObjectNode resultNode = objectMapper.createObjectNode();
                resultNode.put("status", "no_traffic");
                resultNode.put("message", "No recorded request-response pairs found matching the filter.");
                resultNode.put("totalPairs", 0);
                resultNode.put("passed", 0);
                resultNode.put("failed", 0);
                return resultNode;
            }

            // Parse request-response pairs using the standard serializer to preserve
            // all fields (cookies, query parameters, multi-value headers, etc.)
            LogEventRequestAndResponseSerializer requestAndResponseSerializer =
                new LogEventRequestAndResponseSerializer(mockServerLogger);
            LogEventRequestAndResponse[] parsed = requestAndResponseSerializer.deserializeArray(body);
            List<Pair<HttpRequest, HttpResponse>> pairs = new ArrayList<>();
            for (LogEventRequestAndResponse entry : parsed) {
                pairs.add(Pair.of(entry.getHttpRequest(), entry.getHttpResponse()));
            }

            // Validate
            OpenApiTrafficValidator validator = new OpenApiTrafficValidator(mockServerLogger, httpState.getConfiguration());
            List<OpenApiTrafficValidator.TrafficValidationResult> results = validator.validate(specUrlOrPayload, pairs);

            // Build response
            int passedCount = 0;
            int failedCount = 0;
            ArrayNode resultsArray = objectMapper.createArrayNode();
            for (OpenApiTrafficValidator.TrafficValidationResult result : results) {
                ObjectNode resultNode = objectMapper.createObjectNode();
                resultNode.put("method", result.getRequestMethod());
                resultNode.put("path", result.getRequestPath());
                if (result.getMatchedOperation() != null) {
                    resultNode.put("matchedOperation", result.getMatchedOperation());
                } else {
                    resultNode.putNull("matchedOperation");
                }
                resultNode.put("passed", result.isPassed());

                if (!result.getRequestErrors().isEmpty()) {
                    ArrayNode reqErrors = resultNode.putArray("requestErrors");
                    for (String error : result.getRequestErrors()) {
                        reqErrors.add(error);
                    }
                }
                if (!result.getResponseErrors().isEmpty()) {
                    ArrayNode respErrors = resultNode.putArray("responseErrors");
                    for (String error : result.getResponseErrors()) {
                        respErrors.add(error);
                    }
                }

                if (result.isPassed()) {
                    passedCount++;
                } else {
                    failedCount++;
                }
                resultsArray.add(resultNode);
            }

            ObjectNode responseNode = objectMapper.createObjectNode();
            responseNode.put("status", failedCount == 0 ? "conformant" : "non_conformant");
            responseNode.put("totalPairs", results.size());
            responseNode.put("passed", passedCount);
            responseNode.put("failed", failedCount);
            responseNode.set("results", resultsArray);
            return responseNode;
        } catch (Exception e) {
            return errorResult("Failed to verify traffic against OpenAPI", e);
        }
    }

    private void registerRunContractTest() {
        ObjectNode schema = objectMapper.createObjectNode();
        schema.put("type", "object");
        ObjectNode properties = schema.putObject("properties");
        properties.putObject("specUrlOrPayload").put("type", "string").put("description", "OpenAPI spec as a URL, file path, or inline JSON/YAML payload");
        properties.putObject("baseUrl").put("type", "string").put("description", "Base URL of the service under test (e.g. http://localhost:8080)");
        properties.putObject("operationId").put("type", "string").put("description", "Optional filter to test only a specific operation by operationId");
        ArrayNode required = schema.putArray("required");
        required.add("specUrlOrPayload");
        required.add("baseUrl");

        tools.put("run_contract_test", new ToolDefinition(
            "run_contract_test",
            "Send example requests derived from an OpenAPI spec to a running service and check the responses conform — "
                + "use this to verify a service you just built or changed. Builds representative requests for each "
                + "operation (path parameters, query parameters, headers, request body) from spec examples and schema defaults, "
                + "sends them to the specified base URL, and validates each response against the spec. "
                + "Each request has a 10-second timeout.",
            schema,
            this::handleRunContractTest
        ));
    }

    private JsonNode handleRunContractTest(JsonNode params) {
        try {
            String specUrlOrPayload = params.path("specUrlOrPayload").asText(null);
            if (specUrlOrPayload == null || specUrlOrPayload.trim().isEmpty()) {
                return errorResult("'specUrlOrPayload' is required and must not be blank");
            }
            String baseUrl = params.path("baseUrl").asText(null);
            if (baseUrl == null || baseUrl.trim().isEmpty()) {
                return errorResult("'baseUrl' is required and must not be blank");
            }

            if (mockServerLogger.isEnabledForInstance(Level.INFO)) {
                mockServerLogger.logEvent(
                    new LogEntry()
                        .setLogLevel(Level.INFO)
                        .setMessageFormat("MCP run_contract_test sending requests to external target:{}")
                        .setArguments(baseUrl)
                );
            }

            String operationIdFilter = params.path("operationId").asText(null);

            // Parse baseUrl into host, port, scheme
            java.net.URI uri;
            try {
                uri = new java.net.URI(baseUrl);
            } catch (Exception e) {
                return errorResult("'baseUrl' is not a valid URL: " + baseUrl);
            }
            String host = uri.getHost();
            if (host == null || host.isEmpty()) {
                return errorResult("'baseUrl' must be an absolute HTTP/HTTPS URL with a hostname: " + baseUrl);
            }
            String scheme = uri.getScheme();
            if (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme)) {
                return errorResult("'baseUrl' must use the http or https scheme: " + baseUrl);
            }
            ObjectNode refused = blockedTargetResult("run_contract_test", host);
            if (refused != null) {
                return refused;
            }
            int port = uri.getPort();
            boolean secure = "https".equalsIgnoreCase(uri.getScheme());
            if (port == -1) {
                port = secure ? 443 : 80;
            }
            String basePath = uri.getPath() != null ? uri.getPath() : "";
            if (basePath.endsWith("/")) {
                basePath = basePath.substring(0, basePath.length() - 1);
            }
            final String effectiveBasePath = basePath;
            final java.net.InetSocketAddress remoteAddress = new java.net.InetSocketAddress(host, port);
            final boolean isSecure = secure;

            OpenApiContractTest contractTest = new OpenApiContractTest(mockServerLogger, httpState.getConfiguration());
            List<OpenApiContractTest.ContractTestResult> results = contractTest.runContractTests(
                specUrlOrPayload,
                baseUrl,
                operationIdFilter,
                request -> {
                    try {
                        // Prepend base path to request path
                        String requestPath = request.getPath() != null ? request.getPath().getValue() : "/";
                        if (!effectiveBasePath.isEmpty() && !requestPath.startsWith(effectiveBasePath)) {
                            request.withPath(effectiveBasePath + requestPath);
                        }
                        request.withHeader("Host", host + ":" + remoteAddress.getPort());
                        request.withSecure(isSecure);

                        // 10s timeout: contract tests expect a healthy endpoint that responds
                        // promptly; a longer timeout avoids false failures on slow cold-start services
                        return sendToCheckedTarget(request, remoteAddress, isSecure, 10000);
                    } catch (Exception e) {
                        return HttpResponse.response()
                            .withStatusCode(0)
                            .withBody("connection error: " + e.getMessage());
                    }
                }
            );

            // Build response
            int passedCount = 0;
            int failedCount = 0;
            ArrayNode resultsArray = objectMapper.createArrayNode();
            for (OpenApiContractTest.ContractTestResult result : results) {
                ObjectNode resultNode = objectMapper.createObjectNode();
                resultNode.put("operationId", result.getOperationId());
                resultNode.put("method", result.getMethod());
                resultNode.put("path", result.getPath());
                resultNode.put("statusCode", result.getStatusCodeReceived());
                resultNode.put("passed", result.isPassed());

                if (!result.getValidationErrors().isEmpty()) {
                    ArrayNode errors = resultNode.putArray("validationErrors");
                    for (String error : result.getValidationErrors()) {
                        errors.add(error);
                    }
                }

                if (result.isPassed()) {
                    passedCount++;
                } else {
                    failedCount++;
                }
                resultsArray.add(resultNode);
            }

            ObjectNode responseNode = objectMapper.createObjectNode();
            responseNode.put("status", failedCount == 0 ? "all_passed" : "failures_detected");
            responseNode.put("totalOperations", results.size());
            responseNode.put("passed", passedCount);
            responseNode.put("failed", failedCount);
            responseNode.set("results", resultsArray);
            return responseNode;
        } catch (Exception e) {
            return errorResult("Failed to run contract test", e);
        }
    }

    private void registerRunResiliencyTest() {
        ObjectNode schema = objectMapper.createObjectNode();
        schema.put("type", "object");
        ObjectNode properties = schema.putObject("properties");
        properties.putObject("specUrlOrPayload").put("type", "string").put("description", "OpenAPI spec as a URL, file path, or inline JSON/YAML payload");
        properties.putObject("baseUrl").put("type", "string").put("description", "Base URL of the service under test (e.g. http://localhost:8080)");
        properties.putObject("operationId").put("type", "string").put("description", "Optional filter to test only a specific operation by operationId");
        ArrayNode required = schema.putArray("required");
        required.add("specUrlOrPayload");
        required.add("baseUrl");

        tools.put("run_resiliency_test", new ToolDefinition(
            "run_resiliency_test",
            "Send malformed and boundary-case requests derived from an OpenAPI spec to a running service "
                + "and report which inputs it failed to handle gracefully — use this to verify the error handling "
                + "of a service you built or changed. Generates mutations such as omitting required fields, "
                + "type violations, numeric and string boundary violations, and malformed JSON bodies. "
                + "Each request has a 5-second timeout so unresponsive endpoints are classified as UNEXPECTED promptly.",
            schema,
            this::handleRunResiliencyTest
        ));
    }

    private JsonNode handleRunResiliencyTest(JsonNode params) {
        try {
            String specUrlOrPayload = params.path("specUrlOrPayload").asText(null);
            if (specUrlOrPayload == null || specUrlOrPayload.trim().isEmpty()) {
                return errorResult("'specUrlOrPayload' is required and must not be blank");
            }
            String baseUrl = params.path("baseUrl").asText(null);
            if (baseUrl == null || baseUrl.trim().isEmpty()) {
                return errorResult("'baseUrl' is required and must not be blank");
            }

            if (mockServerLogger.isEnabledForInstance(Level.INFO)) {
                mockServerLogger.logEvent(
                    new LogEntry()
                        .setLogLevel(Level.INFO)
                        .setMessageFormat("MCP run_resiliency_test sending requests to external target:{}")
                        .setArguments(baseUrl)
                );
            }

            String operationIdFilter = params.path("operationId").asText(null);

            // Parse baseUrl into host, port, scheme
            java.net.URI uri;
            try {
                uri = new java.net.URI(baseUrl);
            } catch (Exception e) {
                return errorResult("'baseUrl' is not a valid URL: " + baseUrl);
            }
            String host = uri.getHost();
            if (host == null || host.isEmpty()) {
                return errorResult("'baseUrl' must be an absolute HTTP/HTTPS URL with a hostname: " + baseUrl);
            }
            String scheme = uri.getScheme();
            if (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme)) {
                return errorResult("'baseUrl' must use the http or https scheme: " + baseUrl);
            }
            ObjectNode refused = blockedTargetResult("run_resiliency_test", host);
            if (refused != null) {
                return refused;
            }
            int port = uri.getPort();
            boolean secure = "https".equalsIgnoreCase(uri.getScheme());
            if (port == -1) {
                port = secure ? 443 : 80;
            }
            String basePath = uri.getPath() != null ? uri.getPath() : "";
            if (basePath.endsWith("/")) {
                basePath = basePath.substring(0, basePath.length() - 1);
            }
            final String effectiveBasePath = basePath;
            final java.net.InetSocketAddress remoteAddress = new java.net.InetSocketAddress(host, port);
            final boolean isSecure = secure;

            OpenApiResiliencyTest resiliencyTest = new OpenApiResiliencyTest(mockServerLogger, httpState.getConfiguration());
            OpenApiResiliencyTest.ResiliencyTestReport report = resiliencyTest.runResiliencyTests(
                specUrlOrPayload,
                baseUrl,
                operationIdFilter,
                request -> {
                    try {
                        String requestPath = request.getPath() != null ? request.getPath().getValue() : "/";
                        if (!effectiveBasePath.isEmpty() && !requestPath.startsWith(effectiveBasePath)) {
                            request.withPath(effectiveBasePath + requestPath);
                        }
                        request.withHeader("Host", host + ":" + remoteAddress.getPort());
                        request.withSecure(isSecure);
                        // 5s timeout: resiliency tests deliberately send malformed input, so an
                        // unresponsive endpoint should be classified as UNEXPECTED promptly
                        return sendToCheckedTarget(request, remoteAddress, isSecure, 5000);
                    } catch (Exception e) {
                        return HttpResponse.response()
                            .withStatusCode(0)
                            .withBody("connection error: " + e.getMessage());
                    }
                }
            );

            // Build response
            ArrayNode resultsArray = objectMapper.createArrayNode();
            for (OpenApiResiliencyTest.MutationResult result : report.getResults()) {
                ObjectNode resultNode = objectMapper.createObjectNode();
                resultNode.put("operationId", result.getOperationId());
                resultNode.put("method", result.getMethod());
                resultNode.put("path", result.getPath());
                resultNode.put("mutationType", result.getMutationType().name());
                resultNode.put("mutationDescription", result.getMutationDescription());
                resultNode.put("statusCode", result.getStatusCode());
                resultNode.put("classification", result.getClassification().name());
                resultsArray.add(resultNode);
            }

            // Per-operation summaries
            ArrayNode operationSummaries = objectMapper.createArrayNode();
            for (OpenApiResiliencyTest.OperationSummary summary : report.getOperationSummaries().values()) {
                ObjectNode summaryNode = objectMapper.createObjectNode();
                summaryNode.put("operationId", summary.getOperationId());
                summaryNode.put("method", summary.getMethod());
                summaryNode.put("path", summary.getPath());
                summaryNode.put("handled", summary.getHandled());
                summaryNode.put("unexpected", summary.getUnexpected());
                operationSummaries.add(summaryNode);
            }

            ObjectNode responseNode = objectMapper.createObjectNode();
            responseNode.put("status", report.getUnexpectedCount() == 0 ? "all_handled" : "failures_detected");
            responseNode.put("totalMutations", report.getTotalMutations());
            responseNode.put("handled", report.getHandledCount());
            responseNode.put("unexpected", report.getUnexpectedCount());
            responseNode.set("operationSummaries", operationSummaries);
            responseNode.set("results", resultsArray);
            return responseNode;
        } catch (Exception e) {
            return errorResult("Failed to run resiliency test", e);
        }
    }

    private void registerRunMcpContractTest() {
        ObjectNode schema = objectMapper.createObjectNode();
        schema.put("type", "object");
        ObjectNode properties = schema.putObject("properties");
        properties.putObject("targetUrl").put("type", "string").put("description", "Full URL of the target MCP server's Streamable HTTP endpoint (e.g. http://localhost:1080/mockserver/mcp)");
        properties.putObject("protocolVersion").put("type", "string").put("description", "MCP protocol version to advertise during initialize (default 2025-06-18; use e.g. 2025-03-26 to test an older revision)");
        properties.putObject("toolName").put("type", "string").put("description", "Optional tool to exercise via a tools/call shape check; omit to skip (a tools/call may have side effects on the target)");
        ArrayNode required = schema.putArray("required");
        required.add("targetUrl");

        tools.put("run_mcp_contract_test", new ToolDefinition(
            "run_mcp_contract_test",
            "Check that a target MCP (Model Context Protocol) server correctly implements the protocol over Streamable HTTP — "
                + "use this to verify an MCP server you built or changed. Runs the required JSON-RPC handshake and core methods against "
                + "the endpoint (initialize, notifications/initialized, ping, tools/list, and unknown-method rejection) and validates the "
                + "shape of each response (JSON-RPC 2.0 envelope, required result fields), never the semantics of any tool. Optionally "
                + "exercises one tools/call. Each request has a 10-second timeout (so up to ~60 seconds total across all checks).",
            schema,
            this::handleRunMcpContractTest
        ));
    }

    private JsonNode handleRunMcpContractTest(JsonNode params) {
        try {
            String targetUrl = params.path("targetUrl").asText(null);
            if (targetUrl == null || targetUrl.trim().isEmpty()) {
                return errorResult("'targetUrl' is required and must not be blank");
            }
            String protocolVersion = params.path("protocolVersion").asText(null);
            String toolName = params.path("toolName").asText(null);

            if (mockServerLogger.isEnabledForInstance(Level.INFO)) {
                mockServerLogger.logEvent(
                    new LogEntry()
                        .setLogLevel(Level.INFO)
                        .setMessageFormat("MCP run_mcp_contract_test connecting to external MCP server:{}")
                        .setArguments(targetUrl)
                );
            }

            // Parse targetUrl into host, port, scheme, path
            java.net.URI uri;
            try {
                uri = new java.net.URI(targetUrl);
            } catch (Exception e) {
                return errorResult("'targetUrl' is not a valid URL: " + targetUrl);
            }
            String host = uri.getHost();
            if (host == null || host.isEmpty()) {
                return errorResult("'targetUrl' must be an absolute HTTP/HTTPS URL with a hostname: " + targetUrl);
            }
            String scheme = uri.getScheme();
            if (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme)) {
                return errorResult("'targetUrl' must use the http or https scheme: " + targetUrl);
            }
            ObjectNode refused = blockedTargetResult("run_mcp_contract_test", host);
            if (refused != null) {
                return refused;
            }
            final boolean isSecure = "https".equalsIgnoreCase(scheme);
            int port = uri.getPort();
            if (port == -1) {
                port = isSecure ? 443 : 80;
            }
            final java.net.InetSocketAddress remoteAddress = new java.net.InetSocketAddress(host, port);
            final String hostHeader = host + ":" + port;
            final String mcpPath = (uri.getRawPath() == null || uri.getRawPath().isEmpty()) ? "/" : uri.getRawPath();

            McpContractTest.JsonRpcExchange exchange = (message, sessionId) -> {
                try {
                    HttpRequest httpRequest = request()
                        .withMethod("POST")
                        .withPath(mcpPath)
                        .withHeader("Host", hostHeader)
                        .withHeader("Content-Type", "application/json")
                        .withHeader("Accept", "application/json, text/event-stream")
                        .withSecure(isSecure)
                        .withBody(objectMapper.writeValueAsString(message));
                    if (sessionId != null && !sessionId.isEmpty()) {
                        httpRequest.withHeader("Mcp-Session-Id", sessionId);
                    }
                    HttpResponse httpResponse = sendToCheckedTarget(httpRequest, remoteAddress, isSecure, 10000);
                    int statusCode = httpResponse.getStatusCode() != null ? httpResponse.getStatusCode() : 0;
                    if (statusCode == 0) {
                        return McpContractTest.ExchangeResult.transportError(httpResponse.getBodyAsText());
                    }
                    return new McpContractTest.ExchangeResult(
                        statusCode,
                        firstHeaderIgnoreCase(httpResponse, "Mcp-Session-Id"),
                        parseJsonRpcBody(httpResponse),
                        null
                    );
                } catch (Exception e) {
                    return McpContractTest.ExchangeResult.transportError(e.getMessage());
                }
            };

            McpContractTest.Report report = new McpContractTest(objectMapper).run(protocolVersion, toolName, exchange);

            int passed = 0;
            int failed = 0;
            ArrayNode resultsArray = objectMapper.createArrayNode();
            for (McpContractTest.CheckResult check : report.getChecks()) {
                ObjectNode node = objectMapper.createObjectNode();
                node.put("check", check.getCheck());
                node.put("passed", check.isPassed());
                if (check.getStatusCode() != null) {
                    node.put("statusCode", check.getStatusCode());
                }
                if (check.getDetail() != null) {
                    node.put("detail", check.getDetail());
                }
                if (!check.getValidationErrors().isEmpty()) {
                    ArrayNode errors = node.putArray("validationErrors");
                    for (String error : check.getValidationErrors()) {
                        errors.add(error);
                    }
                }
                if (check.isPassed()) {
                    passed++;
                } else {
                    failed++;
                }
                resultsArray.add(node);
            }

            ObjectNode responseNode = objectMapper.createObjectNode();
            responseNode.put("status", failed == 0 ? "all_passed" : "failures_detected");
            responseNode.put("protocolVersion", report.getProtocolVersion());
            if (report.getServerName() != null) {
                ObjectNode serverInfo = responseNode.putObject("serverInfo");
                serverInfo.put("name", report.getServerName());
                if (report.getServerVersion() != null) {
                    serverInfo.put("version", report.getServerVersion());
                }
            }
            responseNode.put("totalChecks", report.getChecks().size());
            responseNode.put("passed", passed);
            responseNode.put("failed", failed);
            responseNode.set("results", resultsArray);
            return responseNode;
        } catch (Exception e) {
            return errorResult("Failed to run MCP contract test", e);
        }
    }

    /**
     * Return the first value of the named response header, matched case-insensitively, or null.
     */
    private String firstHeaderIgnoreCase(HttpResponse response, String name) {
        if (response.getHeaderList() == null) {
            return null;
        }
        for (org.mockserver.model.Header header : response.getHeaderList()) {
            if (header.getName() != null && header.getName().getValue() != null
                && header.getName().getValue().equalsIgnoreCase(name)
                && header.getValues() != null && !header.getValues().isEmpty()) {
                return header.getValues().get(0).getValue();
            }
        }
        return null;
    }

    /**
     * Parse the JSON-RPC message from an MCP HTTP response. Streamable HTTP servers may reply with
     * either application/json or text/event-stream; for SSE the JSON-RPC message is carried in the
     * "data:" field(s). Returns null when the body is empty or not valid JSON.
     */
    private JsonNode parseJsonRpcBody(HttpResponse response) {
        String body = response.getBodyAsText();
        if (body == null || body.trim().isEmpty()) {
            return null;
        }
        String contentType = firstHeaderIgnoreCase(response, "Content-Type");
        String json = body;
        boolean looksLikeSse = (contentType != null && contentType.toLowerCase().contains("text/event-stream"))
            || body.stripLeading().startsWith("event:")
            || body.stripLeading().startsWith("data:");
        if (looksLikeSse) {
            // Extract the first SSE event's data only. Within an event, multiple "data:" lines
            // are joined with newlines (SSE semantics); a blank line ends the event. A synchronous
            // JSON-RPC response carries one message, so stop at the first complete event.
            StringBuilder data = new StringBuilder();
            boolean collecting = false;
            for (String line : body.split("\n")) {
                String trimmed = line.trim();
                if (trimmed.startsWith("data:")) {
                    if (data.length() > 0) {
                        data.append("\n");
                    }
                    data.append(trimmed.substring("data:".length()).trim());
                    collecting = true;
                } else if (trimmed.isEmpty() && collecting) {
                    break;
                }
            }
            if (data.length() > 0) {
                json = data.toString();
            }
        }
        try {
            return objectMapper.readTree(json);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Applies forwardProxyBlockPrivateNetworks to the host a tool sends to: null when it may be sent to, otherwise
     * the error result the tool returns, the refusal logged once.
     */
    private ObjectNode blockedTargetResult(String toolName, String host) {
        try {
            InetAddressValidator.validateForwardTarget(httpState.getConfiguration(), host);
            return null;
        } catch (IllegalArgumentException blocked) {
            if (mockServerLogger.isEnabledForInstance(Level.WARN)) {
                mockServerLogger.logEvent(
                    new LogEntry()
                        .setLogLevel(Level.WARN)
                        .setMessageFormat("MCP {} blocked by SSRF policy:{}")
                        .setArguments(toolName, blocked.getMessage())
                );
            }
            return errorResult(toolName + " blocked by SSRF policy: " + blocked.getMessage());
        }
    }

    /**
     * Checks the target again just before each request, so a name whose DNS answer changed after the tool's first
     * check is refused (the request's connection error) rather than sent.
     */
    private HttpResponse sendToCheckedTarget(HttpRequest request, java.net.InetSocketAddress remoteAddress, boolean secure, int timeoutMillis) {
        InetAddressValidator.validateForwardTarget(httpState.getConfiguration(), remoteAddress.getHostString());
        return sendHttpRequest(request, remoteAddress, secure, timeoutMillis);
    }

    /**
     * Maximum response body size (10 MB). Responses exceeding this limit are truncated.
     */
    private static final int MAX_RESPONSE_BODY_SIZE = 10 * 1024 * 1024;

    /**
     * Maximum fixture file size (50 MB). Files exceeding this limit are rejected to prevent OOM.
     */
    static final long MAX_FIXTURE_FILE_SIZE = 50L * 1024 * 1024;

    /**
     * Send an HTTP request using java.net.HttpURLConnection for simplicity and client-agnosticism.
     * This avoids the need to construct a NettyHttpClient which requires an EventLoopGroup.
     */
    HttpResponse sendHttpRequest(HttpRequest request, java.net.InetSocketAddress remoteAddress, boolean secure, int timeoutMillis) {
        java.net.HttpURLConnection connection = null;
        try {
            String scheme = secure ? "https" : "http";
            String path = request.getPath() != null ? request.getPath().getValue() : "/";
            // a path not starting with "/" (from a spec) would otherwise be read as part of the URL's authority
            if (!path.startsWith("/")) {
                path = "/" + path;
            }

            // Build query string from query parameters
            StringBuilder queryString = new StringBuilder();
            if (request.getQueryStringParameterList() != null) {
                for (org.mockserver.model.Parameter param : request.getQueryStringParameterList()) {
                    if (param.getName() != null && param.getValues() != null) {
                        for (org.mockserver.model.NottableString value : param.getValues()) {
                            if (queryString.length() > 0) {
                                queryString.append("&");
                            } else {
                                queryString.append("?");
                            }
                            queryString.append(java.net.URLEncoder.encode(param.getName().getValue(), "UTF-8"));
                            queryString.append("=");
                            queryString.append(java.net.URLEncoder.encode(value.getValue(), "UTF-8"));
                        }
                    }
                }
            }

            String urlString = scheme + "://" + remoteAddress.getHostName() + ":" + remoteAddress.getPort() + path + queryString;
            java.net.URL url = new java.net.URL(urlString);
            connection = (java.net.HttpURLConnection) url.openConnection();
            connection.setRequestMethod(request.getMethod() != null ? request.getMethod().getValue() : "GET");
            // a redirect would go to a host the private network check has not seen
            connection.setInstanceFollowRedirects(!InetAddressValidator.isEnabled(httpState.getConfiguration()));
            connection.setConnectTimeout(timeoutMillis);
            connection.setReadTimeout(timeoutMillis);

            // Set headers
            if (request.getHeaderList() != null) {
                for (org.mockserver.model.Header header : request.getHeaderList()) {
                    if (header.getName() != null && header.getValues() != null && !header.getValues().isEmpty()) {
                        connection.setRequestProperty(header.getName().getValue(), header.getValues().get(0).getValue());
                    }
                }
            }

            // Set body
            String bodyString = request.getBodyAsText();
            if (bodyString != null && !bodyString.isEmpty()) {
                connection.setDoOutput(true);
                try (java.io.OutputStream os = connection.getOutputStream()) {
                    os.write(bodyString.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                }
            }

            int statusCode = connection.getResponseCode();
            HttpResponse response = HttpResponse.response().withStatusCode(statusCode);

            // Read response headers
            Map<String, java.util.List<String>> responseHeaders = connection.getHeaderFields();
            if (responseHeaders != null) {
                for (Map.Entry<String, java.util.List<String>> entry : responseHeaders.entrySet()) {
                    if (entry.getKey() != null && entry.getValue() != null && !entry.getValue().isEmpty()) {
                        response.withHeader(entry.getKey(), entry.getValue().toArray(new String[0]));
                    }
                }
            }

            // Read response body with size cap
            java.io.InputStream rawStream;
            try {
                rawStream = connection.getInputStream();
            } catch (java.io.IOException e) {
                rawStream = connection.getErrorStream();
            }
            if (rawStream != null) {
                try (java.io.InputStream inputStream = rawStream) {
                    java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
                    byte[] buffer = new byte[4096];
                    int bytesRead;
                    long totalRead = 0;
                    boolean truncated = false;
                    while ((bytesRead = inputStream.read(buffer)) != -1) {
                        long remaining = MAX_RESPONSE_BODY_SIZE - totalRead;
                        if (remaining <= 0) {
                            truncated = true;
                            break;
                        }
                        int toWrite = (int) Math.min(bytesRead, remaining);
                        baos.write(buffer, 0, toWrite);
                        totalRead += toWrite;
                        if (toWrite < bytesRead) {
                            truncated = true;
                            break;
                        }
                    }
                    String bodyContent = baos.toString("UTF-8");
                    if (truncated) {
                        bodyContent += "\n[TRUNCATED: response body exceeded " + MAX_RESPONSE_BODY_SIZE + " bytes]";
                    }
                    response.withBody(bodyContent);
                }
            }

            return response;
        } catch (Exception e) {
            return HttpResponse.response()
                .withStatusCode(0)
                .withBody("connection error: " + e.getMessage());
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    private void registerRecordLlmFixtures() {
        ObjectNode schema = objectMapper.createObjectNode();
        schema.put("type", "object");
        ObjectNode properties = schema.putObject("properties");
        properties.putObject("path").put("type", "string").put("description",
            "File path to write the fixture JSON to. The directory must exist.");
        properties.putObject("host").put("type", "string").put("description",
            "Optional filter: only include recorded traffic whose request host matches this value");
        properties.putObject("requestPath").put("type", "string").put("description",
            "Optional filter: only include recorded traffic whose request path matches this value");
        ObjectNode redactBodyFieldsProp = properties.putObject("redactBodyFields");
        redactBodyFieldsProp.put("type", "array").put("description",
            "Optional JSON field names whose values are redacted from request/response bodies (in addition to sensitive headers and the mockserver.fixtureBodyRedactFields config)");
        redactBodyFieldsProp.putObject("items").put("type", "string");
        schema.putArray("required").add("path");

        tools.put("record_llm_fixtures", new ToolDefinition(
            "record_llm_fixtures",
            "Snapshots LLM/MCP traffic recorded through MockServer's forwarding proxy into a committable, secret-free "
                + "fixture file for deterministic replay. Converts recorded request-response pairs (including SSE streaming "
                + "responses from APIs like Anthropic Claude, OpenAI, etc.) into MockServer expectations with secrets "
                + "(Authorization, api-key, etc.) automatically redacted from headers. JSON body fields are redacted too "
                + "when named via redactBodyFields or the mockserver.fixtureBodyRedactFields config; otherwise bodies are "
                + "left intact, so review fixtures before committing if your app puts credentials in bodies. "
                + "The output file uses MockServer's standard expectation JSON format and can be loaded "
                + "with load_expectations_from_file or via initializationJsonPath.",
            schema,
            this::handleRecordLlmFixtures
        ));
    }

    private JsonNode handleRecordLlmFixtures(JsonNode params) {
        try {
            String filePath = params.path("path").asText(null);
            if (filePath == null || filePath.trim().isEmpty()) {
                return errorResult("'path' is required and must not be blank");
            }

            // Validate the file path is inside allowed directories
            Path outputPath = Paths.get(filePath).toAbsolutePath().normalize();
            ObjectNode pathError = validateFilePath(outputPath, objectMapper);
            if (pathError != null) {
                return pathError;
            }

            File parentDir = outputPath.getParent().toFile();
            if (!parentDir.exists()) {
                return errorResult("Directory does not exist: " + parentDir.getAbsolutePath());
            }

            // Build a filter request to retrieve RECORDED_EXPECTATIONS
            HttpRequest filterRequest = request();
            JsonNode hostNode = params.path("host");
            if (!hostNode.isMissingNode() && !hostNode.isNull() && hostNode.isTextual()) {
                filterRequest.withHeader("Host", hostNode.asText());
            }
            JsonNode requestPathNode = params.path("requestPath");
            if (!requestPathNode.isMissingNode() && !requestPathNode.isNull() && requestPathNode.isTextual()) {
                filterRequest.withPath(requestPathNode.asText());
            }

            // Retrieve recorded expectations
            HttpRequest retrieveRequest = request()
                .withMethod("PUT")
                .withPath("/mockserver/retrieve")
                .withQueryStringParameter("type", "RECORDED_EXPECTATIONS")
                .withQueryStringParameter("format", "JSON")
                .withBody(getRequestDefinitionSerializer().serialize(filterRequest));

            HttpResponse retrieveResponse = httpState.retrieve(retrieveRequest);
            String body = retrieveResponse.getBodyAsString();

            if (body == null || body.isEmpty() || "[]".equals(body.trim())) {
                ObjectNode resultNode = objectMapper.createObjectNode();
                resultNode.put("status", "no_recorded_traffic");
                resultNode.put("message", "No recorded traffic found matching the filter. "
                    + "Ensure MockServer has forwarded requests (e.g. via create_forward_expectation or proxy mode) before calling this tool.");
                resultNode.put("count", 0);
                return resultNode;
            }

            // Deserialize the recorded expectations
            Expectation[] recordedExpectations = getExpectationSerializer().deserializeArray(body, false);

            if (recordedExpectations.length == 0) {
                ObjectNode resultNode = objectMapper.createObjectNode();
                resultNode.put("status", "no_recorded_traffic");
                resultNode.put("message", "No recorded traffic found matching the filter.");
                resultNode.put("count", 0);
                return resultNode;
            }

            // Convert SSE-streamed responses to HttpSseResponse expectations
            SseAwareExpectationConverter sseConverter = new SseAwareExpectationConverter();
            Expectation[] sseAwareExpectations = sseConverter.convert(recordedExpectations);

            // Redact sensitive headers, plus any configured/requested JSON body fields
            org.mockserver.configuration.Configuration recordConfiguration = httpState.getConfiguration();
            List<String> bodyFields = new ArrayList<>(splitCsv(recordConfiguration != null
                ? recordConfiguration.fixtureBodyRedactFields()
                : ConfigurationProperties.fixtureBodyRedactFields()));
            JsonNode redactBodyFieldsNode = params.path("redactBodyFields");
            if (redactBodyFieldsNode.isArray()) {
                for (JsonNode f : redactBodyFieldsNode) {
                    if (f.isTextual() && !f.asText().isEmpty()) {
                        bodyFields.add(f.asText());
                    }
                }
            }
            FixtureRedactor redactor = bodyFields.isEmpty()
                ? new FixtureRedactor()
                : new FixtureRedactor(FixtureRedactor.defaultSensitiveHeaders(), bodyFields);
            Expectation[] redactedExpectations = redactor.redact(sseAwareExpectations);

            // Serialize to the fixture file
            String json = getExpectationSerializer().serialize(redactedExpectations);
            Files.write(outputPath, json.getBytes(StandardCharsets.UTF_8));

            // Auto-register the written fixture as a cassette so it is discoverable via
            // GET /mockserver/cassettes without a separate PUT /mockserver/cassettes call.
            CassetteRegistry.getInstance().register(outputPath.toString(), null, redactedExpectations.length, "recorded");

            ObjectNode resultNode = objectMapper.createObjectNode();
            resultNode.put("status", "written");
            resultNode.put("count", redactedExpectations.length);
            resultNode.put("file", outputPath.toString());
            resultNode.put("message", "Wrote " + redactedExpectations.length + " expectation(s) to " + outputPath
                + ". Secrets have been redacted. Load with load_expectations_from_file or initializationJsonPath for replay.");
            return resultNode;
        } catch (Exception e) {
            return errorResult("Failed to record LLM fixtures", e);
        }
    }

    private void registerLoadExpectationsFromFile() {
        ObjectNode schema = objectMapper.createObjectNode();
        schema.put("type", "object");
        ObjectNode properties = schema.putObject("properties");
        properties.putObject("path").put("type", "string").put("description",
            "File path to the fixture JSON file to load. Must be a valid MockServer expectations JSON file.");
        properties.putObject("strict").put("type", "boolean").put("description",
            "Strict VCR mode: register a low-priority catch-all per cassette path so a request matching no recorded entry returns HTTP 599 instead of falling through. Defaults to the mockserver.llmVcrStrict config.");
        ObjectNode normalizeFieldsProp = properties.putObject("normalizeRequestBodyFields");
        normalizeFieldsProp.put("type", "array").put("description",
            "Optional JSON field names to drop from each recorded request body on load, matching the remaining fields loosely (ignoring extra fields), so volatile values do not block replay.");
        normalizeFieldsProp.putObject("items").put("type", "string");
        schema.putArray("required").add("path");

        tools.put("load_expectations_from_file", new ToolDefinition(
            "load_expectations_from_file",
            "Loads expectations from a JSON fixture file and adds them as active mock expectations in MockServer. "
                + "Use this to replay LLM/MCP traffic previously recorded with record_llm_fixtures. "
                + "The file must contain expectations in MockServer's standard JSON format (an array of expectation objects). "
                + "SSE streaming responses in the fixture are replayed with Server-Sent Events.",
            schema,
            this::handleLoadExpectationsFromFile
        ));
    }

    private JsonNode handleLoadExpectationsFromFile(JsonNode params) {
        try {
            String filePath = params.path("path").asText(null);
            if (filePath == null || filePath.trim().isEmpty()) {
                return errorResult("'path' is required and must not be blank");
            }

            Path inputPath = Paths.get(filePath).toAbsolutePath().normalize();
            ObjectNode pathError = validateFilePath(inputPath, objectMapper);
            if (pathError != null) {
                return pathError;
            }

            if (!Files.exists(inputPath)) {
                return errorResult("File does not exist: " + inputPath);
            }
            if (!Files.isReadable(inputPath)) {
                return errorResult("File is not readable: " + inputPath);
            }

            long fileSize = Files.size(inputPath);
            if (fileSize > MAX_FIXTURE_FILE_SIZE) {
                return errorResult("File exceeds the maximum allowed size of "
                    + (MAX_FIXTURE_FILE_SIZE / (1024 * 1024)) + " MB: " + inputPath);
            }

            String json = new String(Files.readAllBytes(inputPath), StandardCharsets.UTF_8);
            if (json.trim().isEmpty()) {
                return errorResult("File is empty: " + inputPath);
            }

            Expectation[] expectations = getExpectationSerializer().deserializeArray(json, false);

            if (expectations.length == 0) {
                ObjectNode resultNode = objectMapper.createObjectNode();
                resultNode.put("status", "empty");
                resultNode.put("message", "File contained no expectations.");
                resultNode.put("count", 0);
                return resultNode;
            }

            // Optional replay normalisation: drop volatile JSON fields from each
            // recorded request body and match the rest loosely (ignore extra fields).
            List<String> normalizeFields = new ArrayList<>();
            JsonNode normalizeFieldsNode = params.path("normalizeRequestBodyFields");
            if (normalizeFieldsNode.isArray()) {
                for (JsonNode f : normalizeFieldsNode) {
                    if (f.isTextual() && !f.asText().isEmpty()) {
                        normalizeFields.add(f.asText());
                    }
                }
            }
            if (!normalizeFields.isEmpty()) {
                for (Expectation exp : expectations) {
                    relaxRequestBodyForReplay(exp, normalizeFields);
                }
            }

            // Add expectations with unlimited times and TTL for replay
            List<Expectation> addedExpectations = new ArrayList<>();
            for (Expectation exp : expectations) {
                addedExpectations.addAll(httpState.add(exp));
            }

            // Strict VCR mode: register a low-priority catch-all per distinct path so a
            // request matching no recorded entry fails loudly rather than falling through.
            boolean strict = params.path("strict").isBoolean()
                ? params.path("strict").asBoolean()
                : (httpState.getConfiguration() != null
                    ? httpState.getConfiguration().llmVcrStrict()
                    : ConfigurationProperties.llmVcrStrict());
            int strictGuards = 0;
            if (strict) {
                Set<String> paths = new LinkedHashSet<>();
                for (Expectation exp : expectations) {
                    if (exp.getHttpRequest() instanceof HttpRequest) {
                        HttpRequest req = (HttpRequest) exp.getHttpRequest();
                        if (req.getPath() != null && req.getPath().getValue() != null) {
                            paths.add(req.getPath().getValue());
                        }
                    }
                }
                for (String path : paths) {
                    // Build the body via the mapper so a path with JSON-special characters
                    // cannot produce malformed JSON.
                    String guardBody = objectMapper.createObjectNode()
                        .put("error", "strict VCR: no recorded fixture matched this request on path " + path)
                        .toString();
                    // Stable per-path id so re-loading upserts the same guard rather than
                    // accumulating duplicates across repeated load calls.
                    Expectation guard = Expectation.when(request().withPath(path), Integer.MIN_VALUE)
                        .withId("__llm_vcr_strict__" + path)
                        .thenRespond(HttpResponse.response()
                            .withStatusCode(599)
                            .withHeader("content-type", "application/json")
                            .withBody(guardBody));
                    httpState.add(guard);
                    strictGuards++;
                }
            }

            // Auto-register the loaded fixture as a cassette so it is discoverable via
            // GET /mockserver/cassettes without a separate PUT /mockserver/cassettes call.
            CassetteRegistry.getInstance().register(inputPath.toString(), null, addedExpectations.size(), "loaded");

            ObjectNode resultNode = objectMapper.createObjectNode();
            resultNode.put("status", "loaded");
            resultNode.put("count", addedExpectations.size());
            resultNode.put("file", inputPath.toString());
            resultNode.put("strict", strict);
            if (strictGuards > 0) {
                resultNode.put("strictGuards", strictGuards);
            }
            ArrayNode ids = resultNode.putArray("ids");
            for (Expectation exp : addedExpectations) {
                ids.add(exp.getId());
            }
            resultNode.put("message", "Loaded " + addedExpectations.size() + " expectation(s) from " + inputPath
                + ". They are now active and will match incoming requests."
                + (strict ? " Strict VCR mode is on (" + strictGuards + " catch-all guard(s))." : ""));
            return resultNode;
        } catch (Exception e) {
            return errorResult("Failed to load expectations from file", e);
        }
    }

    /**
     * Validates that a file path is inside the process working directory or the system temp directory.
     * Returns null if the path is allowed, or an error result if it is not.
     */
    static ObjectNode validateFilePath(Path filePath, com.fasterxml.jackson.databind.ObjectMapper objectMapper) {
        Path normalised = filePath.toAbsolutePath().normalize();
        Path workingDir = Paths.get("").toAbsolutePath().normalize();
        Path tempDir = Paths.get(System.getProperty("java.io.tmpdir")).toAbsolutePath().normalize();
        if (!normalised.startsWith(workingDir) && !normalised.startsWith(tempDir)) {
            ObjectNode resultNode = objectMapper.createObjectNode();
            resultNode.put("error", true);
            resultNode.put("message", "Path is outside allowed directories. Files must be inside the working directory ("
                + workingDir + ") or the system temp directory (" + tempDir + ")");
            return resultNode;
        }
        return null;
    }

    // --- mock_llm_completion ---

    private void registerMockLlmCompletion() {
        ObjectNode schema = objectMapper.createObjectNode();
        schema.put("type", "object");
        ObjectNode properties = schema.putObject("properties");
        ObjectNode providerProp = properties.putObject("provider");
        providerProp.put("type", "string").put("description", "LLM provider with a registered codec (ANTHROPIC, OPENAI, OPENAI_RESPONSES, GEMINI, BEDROCK, AZURE_OPENAI, OLLAMA).");
        ArrayNode providerEnum = providerProp.putArray("enum");
        for (String name : ProviderCodecRegistry.getInstance().supportedProviderNames()) {
            providerEnum.add(name);
        }
        properties.putObject("path").put("type", "string").put("description", "Request path to match (e.g. /v1/messages)");
        properties.putObject("model").put("type", "string").put("description", "Model name (e.g. claude-sonnet-4, gpt-4o)");
        properties.putObject("text").put("type", "string").put("description", "Response text content");
        ObjectNode toolCallsProp = properties.putObject("toolCalls");
        toolCallsProp.put("type", "array").put("description", "Tool/function calls to include in the response");
        ObjectNode toolCallItems = toolCallsProp.putObject("items");
        toolCallItems.put("type", "object");
        ObjectNode toolCallProps = toolCallItems.putObject("properties");
        toolCallProps.putObject("name").put("type", "string").put("description", "Tool name");
        ObjectNode argsProp = toolCallProps.putObject("arguments");
        argsProp.put("description", "Tool arguments as a JSON string or object");
        ArrayNode argsAnyOf = argsProp.putArray("anyOf");
        argsAnyOf.add(objectMapper.createObjectNode().put("type", "string"));
        argsAnyOf.add(objectMapper.createObjectNode().put("type", "object"));
        toolCallItems.putArray("required").add("name");
        properties.putObject("stopReason").put("type", "string").put("description", "Stop reason (e.g. end_turn, tool_use, stop)");
        ObjectNode usageProp = properties.putObject("usage");
        usageProp.put("type", "object").put("description", "Token usage");
        ObjectNode usageProps = usageProp.putObject("properties");
        usageProps.putObject("inputTokens").put("type", "integer");
        usageProps.putObject("outputTokens").put("type", "integer");
        properties.putObject("streaming").put("type", "boolean").put("description", "Whether to stream the response (default false)");
        ObjectNode outputSchemaProp = properties.putObject("outputSchema");
        outputSchemaProp.put("description", "Optional JSON Schema the response 'text' is expected to conform to. Validated as the response is encoded (fail-soft): a mismatch leaves the body unchanged but adds an 'x-mockserver-structured-output-invalid' diagnostic header and logs a warning. Accepts a JSON Schema as a string or an inline object.");
        ArrayNode outputSchemaAnyOf = outputSchemaProp.putArray("anyOf");
        outputSchemaAnyOf.add(objectMapper.createObjectNode().put("type", "string"));
        outputSchemaAnyOf.add(objectMapper.createObjectNode().put("type", "object"));
        properties.putObject("enforceOutputSchema").put("type", "boolean").put("description", "Opt-in strict structured-output enforcement (default false). When true and an 'outputSchema' is set, a non-conforming configured response fails loudly with a provider-correct error (HTTP 502) instead of the fail-soft diagnostic header — modelling a provider's strict response_format:json_schema mode that guarantees schema-valid output.");
        putChaosSchema(properties);
        ArrayNode required = schema.putArray("required");
        required.add("provider");
        required.add("path");

        tools.put("mock_llm_completion", new ToolDefinition(
            "mock_llm_completion",
            "Creates a mock LLM completion expectation that returns a provider-correct response (Anthropic, OpenAI, etc.) from a high-level description of text, tool calls, and usage",
            schema,
            this::handleMockLlmCompletion
        ));
    }

    private JsonNode handleMockLlmCompletion(JsonNode params) {
        try {
            // Validate provider
            String providerStr = params.path("provider").asText(null);
            if (providerStr == null || providerStr.trim().isEmpty()) {
                return errorResult("'provider' is required");
            }
            Provider provider;
            try {
                provider = Provider.valueOf(providerStr.trim());
            } catch (IllegalArgumentException e) {
                return unsupportedLlmProviderResult(providerStr);
            }

            // Pre-validate codec availability
            if (!ProviderCodecRegistry.getInstance().lookup(provider).isPresent()) {
                return unsupportedLlmProviderResult(providerStr);
            }

            // Validate path
            String path = params.path("path").asText(null);
            if (path == null || path.trim().isEmpty()) {
                return errorResult("'path' is required and must not be blank");
            }

            // Build completion
            Completion completion = Completion.completion();
            JsonNode textNode = params.path("text");
            if (!textNode.isMissingNode() && !textNode.isNull()) {
                completion.withText(textNode.asText());
            }

            JsonNode stopReasonNode = params.path("stopReason");
            if (!stopReasonNode.isMissingNode() && !stopReasonNode.isNull()) {
                completion.withStopReason(stopReasonNode.asText());
            }

            // Tool calls
            JsonNode toolCallsNode = params.path("toolCalls");
            if (toolCallsNode.isArray()) {
                for (JsonNode tcNode : toolCallsNode) {
                    String toolName = tcNode.path("name").asText(null);
                    if (toolName == null || toolName.trim().isEmpty()) {
                        return errorResult("each toolCalls entry must have a non-empty 'name'");
                    }
                    ToolUse toolUse = ToolUse.toolUse(toolName);
                    JsonNode argsNode = tcNode.path("arguments");
                    if (!argsNode.isMissingNode() && !argsNode.isNull()) {
                        if (argsNode.isTextual()) {
                            toolUse.withArguments(argsNode.asText());
                        } else if (argsNode.isObject()) {
                            toolUse.withArguments(objectMapper.writeValueAsString(argsNode));
                        } else {
                            return errorResult("toolCalls[].arguments must be a string or object");
                        }
                    }
                    completion.withToolCall(toolUse);
                }
            }

            // Usage
            JsonNode usageNode = params.path("usage");
            if (usageNode.isObject()) {
                Usage usage = Usage.usage();
                JsonNode inputTokensNode = usageNode.path("inputTokens");
                if (!inputTokensNode.isMissingNode() && !inputTokensNode.isNull()) {
                    if (!inputTokensNode.isIntegralNumber()) {
                        return errorResult("usage.inputTokens must be an integer");
                    }
                    usage.withInputTokens(inputTokensNode.asInt());
                }
                JsonNode outputTokensNode = usageNode.path("outputTokens");
                if (!outputTokensNode.isMissingNode() && !outputTokensNode.isNull()) {
                    if (!outputTokensNode.isIntegralNumber()) {
                        return errorResult("usage.outputTokens must be an integer");
                    }
                    usage.withOutputTokens(outputTokensNode.asInt());
                }
                completion.withUsage(usage);
            }

            // Streaming
            boolean streaming = params.path("streaming").asBoolean(false);
            if (streaming) {
                completion.streaming();
            }

            // Optional output schema (string or inline object) for structured-output validation
            JsonNode outputSchemaNode = params.path("outputSchema");
            if (!outputSchemaNode.isMissingNode() && !outputSchemaNode.isNull()) {
                if (outputSchemaNode.isTextual()) {
                    completion.withOutputSchema(outputSchemaNode.asText());
                } else if (outputSchemaNode.isObject()) {
                    completion.withOutputSchema(objectMapper.writeValueAsString(outputSchemaNode));
                } else {
                    return errorResult("'outputSchema' must be a JSON Schema string or object");
                }
            }

            // Optional strict structured-output enforcement
            JsonNode enforceOutputSchemaNode = params.path("enforceOutputSchema");
            if (enforceOutputSchemaNode.isBoolean() && enforceOutputSchemaNode.asBoolean()) {
                completion.enforceOutputSchema();
            }

            // Model
            String model = params.path("model").asText(null);

            // Build HttpLlmResponse
            HttpLlmResponse llmResponse = HttpLlmResponse.llmResponse()
                .withProvider(provider)
                .withModel(model)
                .withCompletion(completion);
            LlmChaosProfile chaos = parseChaosProfile(params.path("chaos"));
            if (chaos != null) {
                llmResponse.withChaos(chaos);
            }

            // Build and register expectation
            Expectation expectation = Expectation.when(
                HttpRequest.request().withMethod("POST").withPath(path)
            ).thenRespondWithLlm(llmResponse);

            List<Expectation> result = httpState.add(expectation);

            ObjectNode resultNode = objectMapper.createObjectNode();
            resultNode.put("status", "created");
            resultNode.put("count", result.size());
            if (!result.isEmpty()) {
                resultNode.put("id", result.get(0).getId());
            }
            resultNode.put("provider", provider.name());
            return resultNode;
        } catch (Exception e) {
            return errorResult("Failed to create LLM completion expectation", e);
        }
    }

    // --- create_llm_conversation ---

    private void registerCreateLlmConversation() {
        ObjectNode schema = objectMapper.createObjectNode();
        schema.put("type", "object");
        ObjectNode properties = schema.putObject("properties");
        ObjectNode providerProp = properties.putObject("provider");
        providerProp.put("type", "string").put("description", "LLM provider with a registered codec (ANTHROPIC, OPENAI, OPENAI_RESPONSES, GEMINI, BEDROCK, AZURE_OPENAI, OLLAMA).");
        ArrayNode providerEnum = providerProp.putArray("enum");
        for (String name : ProviderCodecRegistry.getInstance().supportedProviderNames()) {
            providerEnum.add(name);
        }
        properties.putObject("path").put("type", "string").put("description", "Request path to match (e.g. /v1/messages)");
        properties.putObject("model").put("type", "string").put("description", "Model name");
        ObjectNode isolateByProp = properties.putObject("isolateBy");
        isolateByProp.put("type", "object").put("description", "Per-session isolation configuration");
        ObjectNode isolateByProps = isolateByProp.putObject("properties");
        ObjectNode sourceProp = isolateByProps.putObject("source");
        sourceProp.put("type", "string").put("description", "Where to extract the isolation key: header, queryParameter, or cookie");
        ArrayNode sourceEnum = sourceProp.putArray("enum");
        sourceEnum.add("header");
        sourceEnum.add("queryParameter");
        sourceEnum.add("cookie");
        isolateByProps.putObject("name").put("type", "string").put("description", "Name of the header, query parameter, or cookie");
        ObjectNode turnsProp = properties.putObject("turns");
        turnsProp.put("type", "array").put("description", "Ordered list of conversation turns");
        ObjectNode turnItems = turnsProp.putObject("items");
        turnItems.put("type", "object");
        ObjectNode turnItemProps = turnItems.putObject("properties");

        // match predicates
        ObjectNode matchProp = turnItemProps.putObject("match");
        matchProp.put("type", "object").put("description", "Predicates for matching this turn");
        ObjectNode matchProps = matchProp.putObject("properties");
        matchProps.putObject("turnIndex").put("type", "integer").put("description", "Match when conversation has this many assistant turns (0-based)");
        matchProps.putObject("latestMessageContains").put("type", "string").put("description", "Match when latest message contains this substring");
        matchProps.putObject("latestMessageMatches").put("type", "string").put("description", "Match when latest message matches this Java regex pattern");
        matchProps.putObject("latestMessageRole").put("type", "string").put("description", "Match when latest message has this role");
        matchProps.putObject("containsToolResultFor").put("type", "string").put("description", "Match when conversation contains a tool result for this tool name");
        matchProps.putObject("semanticMatchAgainst").put("type", "string").put("description", "Opt-in fuzzy match: the intent the latest message should express, judged by a runtime LLM. Off unless mockserver.llmSemanticMatchingEnabled is set and a backend resolves; non-deterministic, exploratory only, never for assertions");

        // optional prompt normalisation applied before the latestMessage* text predicates
        ObjectNode normProp = matchProps.putObject("normalization");
        normProp.put("type", "object").put("description", "Optional deterministic normalisation applied to the latest-message text (and the latestMessageContains value) before matching, so cosmetic differences in dynamically-assembled prompts do not block a match");
        ObjectNode normProps = normProp.putObject("properties");
        normProps.putObject("collapseWhitespace").put("type", "boolean").put("description", "Collapse runs of whitespace to a single space and trim (default true)");
        normProps.putObject("lowercase").put("type", "boolean").put("description", "Lowercase the text before matching (default false)");
        normProps.putObject("sortJsonKeys").put("type", "boolean").put("description", "When the prompt is JSON, sort object keys so key ordering is irrelevant (default true)");
        normProps.putObject("dropBuiltInVolatileFields").put("type", "boolean").put("description", "Strip ISO-8601 timestamps, UUIDs, and prefix_… ids (req_/msg_/call_/…) before matching (default false)");
        ObjectNode dropFieldsProp = normProps.putObject("dropVolatileFields");
        dropFieldsProp.put("type", "array").put("description", "Names of JSON fields to drop from the prompt before matching");
        dropFieldsProp.putObject("items").put("type", "string");

        // response
        ObjectNode responseProp = turnItemProps.putObject("response");
        responseProp.put("type", "object").put("description", "Response configuration for this turn");
        ObjectNode responseProps = responseProp.putObject("properties");
        responseProps.putObject("text").put("type", "string").put("description", "Response text content");
        ObjectNode respToolCallsProp = responseProps.putObject("toolCalls");
        respToolCallsProp.put("type", "array").put("description", "Tool/function calls");
        ObjectNode respToolCallItems = respToolCallsProp.putObject("items");
        respToolCallItems.put("type", "object");
        ObjectNode respTcItemProps = respToolCallItems.putObject("properties");
        respTcItemProps.putObject("name").put("type", "string");
        ObjectNode respArgsProp = respTcItemProps.putObject("arguments");
        respArgsProp.put("description", "Tool arguments as string or object");
        ArrayNode respArgsAnyOf = respArgsProp.putArray("anyOf");
        respArgsAnyOf.add(objectMapper.createObjectNode().put("type", "string"));
        respArgsAnyOf.add(objectMapper.createObjectNode().put("type", "object"));
        responseProps.putObject("stopReason").put("type", "string").put("description", "Stop reason");
        ObjectNode respUsageProp = responseProps.putObject("usage");
        respUsageProp.put("type", "object");
        ObjectNode respUsageProps = respUsageProp.putObject("properties");
        respUsageProps.putObject("inputTokens").put("type", "integer");
        respUsageProps.putObject("outputTokens").put("type", "integer");
        responseProps.putObject("streaming").put("type", "boolean").put("description", "Whether to stream");

        // optional per-turn chaos profile
        putChaosSchema(turnItemProps);

        // Optional ids: array of expectation IDs to assign to the generated
        // expectations in turn order. When an id matches an existing
        // expectation the registration upserts in place — used by the
        // dashboard's "edit existing conversation" flow to keep IDs stable.
        ObjectNode idsProp = properties.putObject("ids");
        idsProp.put("type", "array").put("description", "Optional expectation IDs to assign per turn. Reusing the existing IDs updates the conversation in place.");
        idsProp.putObject("items").put("type", "string");

        ArrayNode required = schema.putArray("required");
        required.add("provider");
        required.add("path");
        required.add("turns");

        tools.put("create_llm_conversation", new ToolDefinition(
            "create_llm_conversation",
            "Creates a multi-turn LLM conversation mock with scenario-based state advancement. Each turn matches based on conversation predicates and returns a configured completion.",
            schema,
            this::handleCreateLlmConversation
        ));
    }

    private JsonNode handleCreateLlmConversation(JsonNode params) {
        try {
            // Validate provider
            String providerStr = params.path("provider").asText(null);
            if (providerStr == null || providerStr.trim().isEmpty()) {
                return errorResult("'provider' is required");
            }
            Provider provider;
            try {
                provider = Provider.valueOf(providerStr.trim());
            } catch (IllegalArgumentException e) {
                return unsupportedLlmProviderResult(providerStr);
            }

            // Pre-validate codec availability
            if (!ProviderCodecRegistry.getInstance().lookup(provider).isPresent()) {
                return unsupportedLlmProviderResult(providerStr);
            }

            // Validate path
            String path = params.path("path").asText(null);
            if (path == null || path.trim().isEmpty()) {
                return errorResult("'path' is required and must not be blank");
            }

            // Validate turns
            JsonNode turnsNode = params.path("turns");
            if (!turnsNode.isArray() || turnsNode.size() == 0) {
                return errorResult("'turns' must be a non-empty array");
            }

            // Model
            String model = params.path("model").asText(null);

            // Build conversation using LlmConversationBuilder
            LlmConversationBuilder conversationBuilder = LlmConversationBuilder.conversation()
                .withPath(path)
                .withProvider(provider)
                .withModel(model);

            // Isolation
            JsonNode isolateByNode = params.path("isolateBy");
            if (isolateByNode.isObject()) {
                String source = isolateByNode.path("source").asText(null);
                String isoName = isolateByNode.path("name").asText(null);
                if (source == null || isoName == null || isoName.trim().isEmpty()) {
                    return errorResult("isolateBy requires both 'source' and 'name'");
                }
                IsolationSource isolationSource;
                switch (source) {
                    case "header":
                        isolationSource = IsolationSource.header(isoName);
                        break;
                    case "queryParameter":
                        isolationSource = IsolationSource.queryParameter(isoName);
                        break;
                    case "cookie":
                        isolationSource = IsolationSource.cookie(isoName);
                        break;
                    default:
                        return errorResult("isolateBy.source must be one of: header, queryParameter, cookie");
                }
                conversationBuilder.isolateBy(isolationSource);
            }

            // Build turns
            for (int i = 0; i < turnsNode.size(); i++) {
                JsonNode turnNode = turnsNode.get(i);
                TurnBuilder turnBuilder = conversationBuilder.turn();

                // Match predicates
                JsonNode matchNode = turnNode.path("match");
                if (matchNode.isObject()) {
                    JsonNode turnIndexNode = matchNode.path("turnIndex");
                    if (!turnIndexNode.isMissingNode() && !turnIndexNode.isNull()) {
                        if (!turnIndexNode.isIntegralNumber()) {
                            return errorResult("turns[" + i + "].match.turnIndex must be an integer");
                        }
                        turnBuilder.whenTurnIndex(turnIndexNode.asInt());
                    }
                    JsonNode latestMsgContains = matchNode.path("latestMessageContains");
                    if (!latestMsgContains.isMissingNode() && !latestMsgContains.isNull()) {
                        turnBuilder.whenLatestMessageContains(latestMsgContains.asText());
                    }
                    JsonNode latestMsgMatches = matchNode.path("latestMessageMatches");
                    if (!latestMsgMatches.isMissingNode() && !latestMsgMatches.isNull()) {
                        try {
                            turnBuilder.whenLatestMessageMatches(
                                java.util.regex.Pattern.compile(latestMsgMatches.asText()));
                        } catch (java.util.regex.PatternSyntaxException e) {
                            return errorResult("turns[" + i + "].match.latestMessageMatches is not a valid regex: " + e.getMessage());
                        }
                    }
                    JsonNode latestMsgRole = matchNode.path("latestMessageRole");
                    if (!latestMsgRole.isMissingNode() && !latestMsgRole.isNull()) {
                        try {
                            turnBuilder.whenLatestMessageRole(
                                org.mockserver.llm.ParsedMessage.Role.valueOf(latestMsgRole.asText().toUpperCase()));
                        } catch (IllegalArgumentException e) {
                            return errorResult("turns[" + i + "].match.latestMessageRole must be one of: USER, ASSISTANT, SYSTEM, TOOL");
                        }
                    }
                    JsonNode containsToolResult = matchNode.path("containsToolResultFor");
                    if (!containsToolResult.isMissingNode() && !containsToolResult.isNull()) {
                        turnBuilder.whenContainsToolResultFor(containsToolResult.asText());
                    }
                    JsonNode semanticMatch = matchNode.path("semanticMatchAgainst");
                    if (!semanticMatch.isMissingNode() && !semanticMatch.isNull()) {
                        turnBuilder.whenSemanticMatch(semanticMatch.asText());
                    }
                    JsonNode normalizationNode = matchNode.path("normalization");
                    if (normalizationNode.isObject()) {
                        NormalizationOptions normalization = NormalizationOptions.normalizationOptions();
                        JsonNode collapseNode = normalizationNode.path("collapseWhitespace");
                        if (collapseNode.isBoolean()) {
                            normalization.withCollapseWhitespace(collapseNode.asBoolean());
                        }
                        JsonNode lowercaseNode = normalizationNode.path("lowercase");
                        if (lowercaseNode.isBoolean()) {
                            normalization.withLowercase(lowercaseNode.asBoolean());
                        }
                        JsonNode sortKeysNode = normalizationNode.path("sortJsonKeys");
                        if (sortKeysNode.isBoolean()) {
                            normalization.withSortJsonKeys(sortKeysNode.asBoolean());
                        }
                        JsonNode dropBuiltInNode = normalizationNode.path("dropBuiltInVolatileFields");
                        if (dropBuiltInNode.isBoolean()) {
                            normalization.withDropBuiltInVolatileFields(dropBuiltInNode.asBoolean());
                        }
                        JsonNode dropFieldsNode = normalizationNode.path("dropVolatileFields");
                        if (dropFieldsNode.isArray()) {
                            java.util.List<String> dropFields = new java.util.ArrayList<>();
                            for (JsonNode fieldNode : dropFieldsNode) {
                                if (fieldNode.isTextual()) {
                                    dropFields.add(fieldNode.asText());
                                }
                            }
                            normalization.withDropVolatileFields(dropFields);
                        }
                        turnBuilder.withNormalization(normalization);
                    }
                }

                // Response
                JsonNode responseNode = turnNode.path("response");
                if (responseNode.isObject()) {
                    Completion turnCompletion = Completion.completion();
                    JsonNode respTextNode = responseNode.path("text");
                    if (!respTextNode.isMissingNode() && !respTextNode.isNull()) {
                        turnCompletion.withText(respTextNode.asText());
                    }
                    JsonNode respStopNode = responseNode.path("stopReason");
                    if (!respStopNode.isMissingNode() && !respStopNode.isNull()) {
                        turnCompletion.withStopReason(respStopNode.asText());
                    }
                    JsonNode respToolCallsNode = responseNode.path("toolCalls");
                    if (respToolCallsNode.isArray()) {
                        for (JsonNode tcNode : respToolCallsNode) {
                            String toolName = tcNode.path("name").asText(null);
                            if (toolName == null || toolName.trim().isEmpty()) {
                                return errorResult("turns[" + i + "].response.toolCalls entry must have a non-empty 'name'");
                            }
                            ToolUse toolUse = ToolUse.toolUse(toolName);
                            JsonNode argsNode = tcNode.path("arguments");
                            if (!argsNode.isMissingNode() && !argsNode.isNull()) {
                                if (argsNode.isTextual()) {
                                    toolUse.withArguments(argsNode.asText());
                                } else if (argsNode.isObject()) {
                                    toolUse.withArguments(objectMapper.writeValueAsString(argsNode));
                                }
                            }
                            turnCompletion.withToolCall(toolUse);
                        }
                    }
                    JsonNode respUsageNode = responseNode.path("usage");
                    if (respUsageNode.isObject()) {
                        Usage usage = Usage.usage();
                        JsonNode inNode = respUsageNode.path("inputTokens");
                        if (!inNode.isMissingNode() && !inNode.isNull() && inNode.isIntegralNumber()) {
                            usage.withInputTokens(inNode.asInt());
                        }
                        JsonNode outNode = respUsageNode.path("outputTokens");
                        if (!outNode.isMissingNode() && !outNode.isNull() && outNode.isIntegralNumber()) {
                            usage.withOutputTokens(outNode.asInt());
                        }
                        turnCompletion.withUsage(usage);
                    }
                    boolean respStreaming = responseNode.path("streaming").asBoolean(false);
                    if (respStreaming) {
                        turnCompletion.streaming();
                    }
                    turnBuilder.respondingWith(turnCompletion);
                }

                // Optional chaos profile for this turn's response
                LlmChaosProfile turnChaos = parseChaosProfile(turnNode.path("chaos"));
                if (turnChaos != null) {
                    turnBuilder.withChaos(turnChaos);
                }

                // Chain to next (except last)
                if (i < turnsNode.size() - 1) {
                    turnBuilder.andThen();
                }
            }

            // Build expectations
            Expectation[] expectations = conversationBuilder.build();

            // Optional per-turn IDs — when supplied (and matching the turn
            // count), apply them so the registration upserts existing
            // expectations rather than allocating fresh UUIDs.
            JsonNode idsNode = params.path("ids");
            if (idsNode.isArray()) {
                for (int i = 0; i < expectations.length && i < idsNode.size(); i++) {
                    JsonNode idNode = idsNode.get(i);
                    if (idNode.isTextual()) {
                        String idVal = idNode.asText();
                        if (idVal != null && !idVal.isEmpty()) {
                            expectations[i].withId(idVal);
                        }
                    }
                }
            }

            // Register each expectation
            List<Expectation> allResults = new ArrayList<>();
            for (Expectation exp : expectations) {
                allResults.addAll(httpState.add(exp));
            }

            // Extract scenario name from first expectation
            String scenarioName = expectations.length > 0 ? expectations[0].getScenarioName() : null;

            // Build result with state info
            ObjectNode resultNode = objectMapper.createObjectNode();
            resultNode.put("status", "created");
            resultNode.put("count", allResults.size());
            if (scenarioName != null) {
                resultNode.put("scenarioName", scenarioName);
            }
            ArrayNode statesArray = resultNode.putArray("states");
            for (Expectation exp : expectations) {
                ObjectNode stateNode = objectMapper.createObjectNode();
                stateNode.put("scenarioState", exp.getScenarioState());
                stateNode.put("newScenarioState", exp.getNewScenarioState());
                if (exp.getId() != null) {
                    stateNode.put("id", exp.getId());
                }
                statesArray.add(stateNode);
            }
            ArrayNode ids = resultNode.putArray("ids");
            for (Expectation exp : allResults) {
                ids.add(exp.getId());
            }
            return resultNode;
        } catch (IllegalStateException e) {
            return errorResult(e.getMessage());
        } catch (Exception e) {
            return errorResult("Failed to create LLM conversation", e);
        }
    }

    // --- mock_llm_failover ---

    private void registerMockLlmFailover() {
        ObjectNode schema = objectMapper.createObjectNode();
        schema.put("type", "object");
        ObjectNode properties = schema.putObject("properties");
        ObjectNode providerProp = properties.putObject("provider");
        providerProp.put("type", "string").put("description", "LLM provider with a registered codec (ANTHROPIC, OPENAI, OPENAI_RESPONSES, GEMINI, BEDROCK, AZURE_OPENAI, OLLAMA).");
        ArrayNode providerEnum = providerProp.putArray("enum");
        for (String name : ProviderCodecRegistry.getInstance().supportedProviderNames()) {
            providerEnum.add(name);
        }
        properties.putObject("path").put("type", "string").put("description", "Request path to match (e.g. /v1/chat/completions)");
        properties.putObject("model").put("type", "string").put("description", "Model name for the success response (e.g. gpt-4o, claude-sonnet-4)");
        ObjectNode failStatusesProp = properties.putObject("failStatuses");
        failStatusesProp.put("type", "array").put("description", "Ordered array of HTTP status codes — one per failure attempt (e.g. [503, 503, 429]). The first N requests return these statuses in order, then subsequent requests succeed.");
        failStatusesProp.putObject("items").put("type", "integer");
        properties.putObject("text").put("type", "string").put("description", "Success response text content");
        ObjectNode toolCallsProp = properties.putObject("toolCalls");
        toolCallsProp.put("type", "array").put("description", "Tool/function calls for the success response");
        ObjectNode toolCallItems = toolCallsProp.putObject("items");
        toolCallItems.put("type", "object");
        ObjectNode toolCallProps = toolCallItems.putObject("properties");
        toolCallProps.putObject("name").put("type", "string");
        ObjectNode argsProp = toolCallProps.putObject("arguments");
        argsProp.put("description", "Tool arguments as a JSON string or object");
        ArrayNode argsAnyOf = argsProp.putArray("anyOf");
        argsAnyOf.add(objectMapper.createObjectNode().put("type", "string"));
        argsAnyOf.add(objectMapper.createObjectNode().put("type", "object"));
        toolCallItems.putArray("required").add("name");
        properties.putObject("stopReason").put("type", "string").put("description", "Stop reason for the success response");
        ObjectNode usageProp = properties.putObject("usage");
        usageProp.put("type", "object").put("description", "Token usage for the success response");
        ObjectNode usageProps = usageProp.putObject("properties");
        usageProps.putObject("inputTokens").put("type", "integer");
        usageProps.putObject("outputTokens").put("type", "integer");
        ArrayNode required = schema.putArray("required");
        required.add("provider");
        required.add("path");
        required.add("failStatuses");

        tools.put("mock_llm_failover", new ToolDefinition(
            "mock_llm_failover",
            "Creates an LLM failover/retry scenario: the first N requests fail with the specified HTTP statuses, then subsequent requests succeed with a provider-correct LLM response. Use this to test that retry/failover logic (LiteLLM, Envoy AI Gateway, SDK retries) works correctly.",
            schema,
            this::handleMockLlmFailover
        ));
    }

    private JsonNode handleMockLlmFailover(JsonNode params) {
        try {
            // Validate provider
            String providerStr = params.path("provider").asText(null);
            if (providerStr == null || providerStr.trim().isEmpty()) {
                return errorResult("'provider' is required");
            }
            Provider provider;
            try {
                provider = Provider.valueOf(providerStr.trim());
            } catch (IllegalArgumentException e) {
                return unsupportedLlmProviderResult(providerStr);
            }

            // Pre-validate codec availability
            if (!ProviderCodecRegistry.getInstance().lookup(provider).isPresent()) {
                return unsupportedLlmProviderResult(providerStr);
            }

            // Validate path
            String path = params.path("path").asText(null);
            if (path == null || path.trim().isEmpty()) {
                return errorResult("'path' is required and must not be blank");
            }

            // Validate failStatuses
            JsonNode failStatusesNode = params.path("failStatuses");
            if (!failStatusesNode.isArray() || failStatusesNode.size() == 0) {
                return errorResult("'failStatuses' must be a non-empty array of HTTP status codes");
            }

            // Build using LlmFailoverBuilder
            LlmFailoverBuilder builder = LlmFailoverBuilder.llmFailover()
                .withPath(path)
                .withProvider(provider)
                .withModel(params.path("model").asText(null));

            // Add failures
            for (int i = 0; i < failStatusesNode.size(); i++) {
                JsonNode statusNode = failStatusesNode.get(i);
                if (!statusNode.isIntegralNumber()) {
                    return errorResult("failStatuses[" + i + "] must be an integer");
                }
                int status = statusNode.asInt();
                if (status < 100 || status > 599) {
                    return errorResult("failStatuses[" + i + "] must be between 100 and 599");
                }
                builder.failWith(status);
            }

            // Build success completion
            Completion completion = Completion.completion();
            JsonNode textNode = params.path("text");
            if (!textNode.isMissingNode() && !textNode.isNull()) {
                completion.withText(textNode.asText());
            }
            JsonNode stopReasonNode = params.path("stopReason");
            if (!stopReasonNode.isMissingNode() && !stopReasonNode.isNull()) {
                completion.withStopReason(stopReasonNode.asText());
            }
            JsonNode toolCallsNode = params.path("toolCalls");
            if (toolCallsNode.isArray()) {
                for (JsonNode tcNode : toolCallsNode) {
                    String toolName = tcNode.path("name").asText(null);
                    if (toolName == null || toolName.trim().isEmpty()) {
                        return errorResult("each toolCalls entry must have a non-empty 'name'");
                    }
                    ToolUse toolUse = ToolUse.toolUse(toolName);
                    JsonNode argsNode = tcNode.path("arguments");
                    if (!argsNode.isMissingNode() && !argsNode.isNull()) {
                        if (argsNode.isTextual()) {
                            toolUse.withArguments(argsNode.asText());
                        } else if (argsNode.isObject()) {
                            toolUse.withArguments(objectMapper.writeValueAsString(argsNode));
                        } else {
                            return errorResult("toolCalls[].arguments must be a string or object");
                        }
                    }
                    completion.withToolCall(toolUse);
                }
            }
            JsonNode usageNode = params.path("usage");
            if (usageNode.isObject()) {
                Usage usage = Usage.usage();
                JsonNode inputTokensNode = usageNode.path("inputTokens");
                if (!inputTokensNode.isMissingNode() && !inputTokensNode.isNull() && inputTokensNode.isIntegralNumber()) {
                    usage.withInputTokens(inputTokensNode.asInt());
                }
                JsonNode outputTokensNode = usageNode.path("outputTokens");
                if (!outputTokensNode.isMissingNode() && !outputTokensNode.isNull() && outputTokensNode.isIntegralNumber()) {
                    usage.withOutputTokens(outputTokensNode.asInt());
                }
                completion.withUsage(usage);
            }

            builder.thenRespondWith(completion);

            // Build and register expectations
            Expectation[] expectations = builder.build();
            List<Expectation> allResults = new ArrayList<>();
            for (Expectation exp : expectations) {
                allResults.addAll(httpState.add(exp));
            }

            // Build result
            ObjectNode resultNode = objectMapper.createObjectNode();
            resultNode.put("status", "created");
            resultNode.put("count", allResults.size());
            resultNode.put("failureAttempts", builder.getFailureCount());
            resultNode.put("provider", provider.name());
            ArrayNode ids = resultNode.putArray("ids");
            for (Expectation exp : allResults) {
                ids.add(exp.getId());
            }
            return resultNode;
        } catch (IllegalStateException e) {
            return errorResult(e.getMessage());
        } catch (Exception e) {
            return errorResult("Failed to create LLM failover scenario", e);
        }
    }

    // --- verify_tool_call ---

    private void registerVerifyToolCall() {
        ObjectNode schema = objectMapper.createObjectNode();
        schema.put("type", "object");
        ObjectNode properties = schema.putObject("properties");
        ObjectNode providerProp = properties.putObject("provider");
        providerProp.put("type", "string").put("description", "LLM provider whose recorded requests to inspect. Use AUTO to detect the provider from the recorded request paths (useful for proxied traffic where the provider is implicit in the upstream URL).");
        ArrayNode providerEnum = providerProp.putArray("enum");
        providerEnum.add("AUTO");
        for (String name : ProviderCodecRegistry.getInstance().supportedProviderNames()) {
            providerEnum.add(name);
        }
        properties.putObject("path").put("type", "string").put("description", "Optional request path filter (e.g. /v1/messages)");
        properties.putObject("toolName").put("type", "string").put("description", "Name of the tool the agent should have called");
        properties.putObject("argumentsRegex").put("type", "string").put("description", "Optional Java regex matched against the tool call's argument JSON");
        properties.putObject("atLeast").put("type", "integer").put("description", "Minimum number of matching tool calls required (default 1)");
        properties.putObject("atMost").put("type", "integer").put("description", "Optional maximum number of matching tool calls allowed");
        ArrayNode required = schema.putArray("required");
        required.add("provider");
        required.add("toolName");

        tools.put("verify_tool_call", new ToolDefinition(
            "verify_tool_call",
            "Assert that an agent called a named tool (optionally with arguments matching a regex) a given number of times, by inspecting LLM requests recorded through MockServer. Works with both mock-backed and proxied/forwarded traffic.",
            schema,
            this::handleVerifyToolCall
        ));
    }

    private JsonNode handleVerifyToolCall(JsonNode params) {
        try {
            String providerStr = params.path("provider").asText(null);
            if (providerStr == null || providerStr.trim().isEmpty()) {
                return errorResult("'provider' is required");
            }
            String toolName = params.path("toolName").asText(null);
            if (toolName == null || toolName.trim().isEmpty()) {
                return errorResult("'toolName' is required and must not be blank");
            }
            String path = emptyToNull(params.path("path").asText(null));
            String argumentsRegex = emptyToNull(params.path("argumentsRegex").asText(null));
            int atLeast = params.path("atLeast").isIntegralNumber() ? params.path("atLeast").asInt() : 1;
            Integer atMost = params.path("atMost").isIntegralNumber() ? params.path("atMost").asInt() : null;
            if (atMost != null && atMost < atLeast) {
                return errorResult("'atMost' must be greater than or equal to 'atLeast'");
            }

            List<HttpRequest> requests = retrieveRecordedHttpRequests(path);
            Provider provider = resolveProviderOrAuto(params, requests);
            if (provider == null) {
                return "AUTO".equalsIgnoreCase(providerStr.trim())
                    ? errorResult("could not auto-detect LLM provider from recorded request paths; specify the provider explicitly")
                    : unsupportedLlmProviderResult(providerStr);
            }

            AgentRunAnalyzer.ToolCallReport report;
            try {
                report = new AgentRunAnalyzer().inspectToolCalls(requests, provider, toolName, argumentsRegex);
            } catch (IllegalArgumentException e) {
                return errorResult(e.getMessage());
            }
            boolean satisfied = report.getCount() >= atLeast && (atMost == null || report.getCount() <= atMost);

            ObjectNode resultNode = objectMapper.createObjectNode();
            resultNode.put("toolName", toolName);
            resultNode.put("provider", provider.name());
            resultNode.put("count", report.getCount());
            resultNode.put("atLeast", atLeast);
            if (atMost != null) {
                resultNode.put("atMost", atMost);
            }
            resultNode.put("satisfied", satisfied);
            if (!satisfied) {
                resultNode.put("message", "expected tool '" + toolName + "' to be called "
                    + (atMost != null ? "between " + atLeast + " and " + atMost : "at least " + atLeast)
                    + " time(s) but found " + report.getCount());
            }
            return resultNode;
        } catch (Exception e) {
            return errorResult("Failed to verify tool call", e);
        }
    }

    // --- verify_structured_output ---

    private void registerVerifyStructuredOutput() {
        ObjectNode schema = objectMapper.createObjectNode();
        schema.put("type", "object");
        ObjectNode properties = schema.putObject("properties");
        ObjectNode providerProp = properties.putObject("provider");
        providerProp.put("type", "string").put("description", "LLM provider whose recorded responses to validate");
        ArrayNode providerEnum = providerProp.putArray("enum");
        for (String name : ProviderCodecRegistry.getInstance().supportedProviderNames()) {
            providerEnum.add(name);
        }
        properties.putObject("schema").put("type", "string").put("description", "JSON Schema that the structured output text of each recorded response must conform to");
        properties.putObject("path").put("type", "string").put("description", "Optional request path filter (only validate responses to this path)");
        ArrayNode required = schema.putArray("required");
        required.add("provider");
        required.add("schema");

        tools.put("verify_structured_output", new ToolDefinition(
            "verify_structured_output",
            "Validate that the structured (JSON) output of recorded LLM responses conforms to a JSON Schema. "
                + "Decodes each recorded response for the given provider, extracts the assistant's output text, and checks it "
                + "against the schema — use this to assert an agent (or mocked model) produced schema-valid structured output. "
                + "Read-only and deterministic; responses with no text output are reported separately as skipped.",
            schema,
            this::handleVerifyStructuredOutput
        ));
    }

    private JsonNode handleVerifyStructuredOutput(JsonNode params) {
        try {
            String providerStr = params.path("provider").asText(null);
            if (providerStr == null || providerStr.trim().isEmpty()) {
                return errorResult("'provider' is required");
            }
            Provider provider = parseProviderParam(params);
            if (provider == null) {
                return unsupportedLlmProviderResult(providerStr);
            }
            String schemaStr = params.path("schema").asText(null);
            if (schemaStr == null || schemaStr.trim().isEmpty()) {
                return errorResult("'schema' is required and must not be blank");
            }
            Optional<LlmClient> clientOpt = LlmClientRegistry.getInstance().lookup(provider);
            if (!clientOpt.isPresent()) {
                return errorResult("no runtime client for provider " + provider + " to parse responses");
            }
            LlmClient client = clientOpt.get();

            JsonSchemaValidator validator;
            try {
                validator = new JsonSchemaValidator(mockServerLogger, schemaStr);
            } catch (Exception e) {
                return errorResult("'schema' is not a valid JSON Schema: " + e.getMessage());
            }

            String path = emptyToNull(params.path("path").asText(null));
            List<LogEventRequestAndResponse> pairs = retrieveRecordedPairs(path);

            int checked = 0;
            int conforming = 0;
            int skipped = 0;
            ArrayNode results = objectMapper.createArrayNode();
            for (LogEventRequestAndResponse pair : pairs) {
                HttpResponse recordedResponse = pair.getHttpResponse();
                if (recordedResponse == null) {
                    continue;
                }
                String outputText;
                try {
                    outputText = client.parseCompletionResponse(recordedResponse).getText();
                } catch (Exception e) {
                    outputText = null;
                }
                if (outputText == null || outputText.trim().isEmpty()) {
                    skipped++;
                    continue;
                }
                checked++;
                String error = validator.isValid(outputText, false);
                boolean conforms = error == null || error.isEmpty();
                if (conforms) {
                    conforming++;
                }
                ObjectNode entry = objectMapper.createObjectNode();
                if (pair.getHttpRequest() instanceof HttpRequest && ((HttpRequest) pair.getHttpRequest()).getPath() != null) {
                    entry.put("path", ((HttpRequest) pair.getHttpRequest()).getPath().getValue());
                }
                entry.put("conforms", conforms);
                if (!conforms) {
                    entry.put("error", error);
                }
                results.add(entry);
            }

            ObjectNode resultNode = objectMapper.createObjectNode();
            resultNode.put("provider", provider.name());
            resultNode.put("checked", checked);
            resultNode.put("conforming", conforming);
            resultNode.put("nonConforming", checked - conforming);
            resultNode.put("skippedNoOutput", skipped);
            resultNode.put("allConform", checked > 0 && conforming == checked);
            resultNode.set("results", results);
            return resultNode;
        } catch (Exception e) {
            return errorResult("Failed to verify structured output", e);
        }
    }

    // --- verify_cost_budget ---

    private void registerVerifyCostBudget() {
        ObjectNode schema = objectMapper.createObjectNode();
        schema.put("type", "object");
        ObjectNode properties = schema.putObject("properties");
        ObjectNode providerProp = properties.putObject("provider");
        providerProp.put("type", "string").put("description", "LLM provider whose recorded responses to total (must have a registered runtime client).");
        ArrayNode providerEnum = providerProp.putArray("enum");
        for (String name : ProviderCodecRegistry.getInstance().supportedProviderNames()) {
            providerEnum.add(name);
        }
        properties.putObject("maxCostUsd").put("type", "number").put("description", "Cost budget in USD. The assertion passes when the total estimated cost of the recorded responses is at or below this value.");
        properties.putObject("model").put("type", "string").put("description", "Optional model id used to price every response (e.g. claude-sonnet-4). If omitted, the model is read from each recorded request body, or from the path of a native Gemini call; a call sent to a Vertex AI host is priced at Vertex AI's rates. Responses whose model cannot be priced are reported as unpriceable and excluded from the total.");
        properties.putObject("path").put("type", "string").put("description", "Optional request path filter (only total responses to this path).");
        ArrayNode required = schema.putArray("required");
        required.add("provider");
        required.add("maxCostUsd");

        tools.put("verify_cost_budget", new ToolDefinition(
            "verify_cost_budget",
            "Assert that the total estimated USD cost of recorded LLM responses is within a budget. "
                + "Decodes each recorded response for the given provider (via the runtime-LLM client SPI), sums input/output "
                + "tokens from the reported usage, prices them with the built-in pricing table, and compares the total against "
                + "maxCostUsd. Read-only and deterministic — use it as a CI cost gate for an agent run. Responses without usage "
                + "are skipped; responses whose model has no known price are reported as unpriceable and excluded from the total.",
            schema,
            this::handleVerifyCostBudget
        ));
    }

    private JsonNode handleVerifyCostBudget(JsonNode params) {
        try {
            String providerStr = params.path("provider").asText(null);
            if (providerStr == null || providerStr.trim().isEmpty()) {
                return errorResult("'provider' is required");
            }
            Provider provider = parseProviderParam(params);
            if (provider == null) {
                return unsupportedLlmProviderResult(providerStr);
            }
            JsonNode maxCostNode = params.path("maxCostUsd");
            if (maxCostNode.isMissingNode() || maxCostNode.isNull() || !maxCostNode.isNumber()) {
                return errorResult("'maxCostUsd' is required and must be a number");
            }
            double maxCostUsd = maxCostNode.asDouble();
            if (maxCostUsd < 0) {
                return errorResult("'maxCostUsd' must not be negative");
            }
            Optional<LlmClient> clientOpt = LlmClientRegistry.getInstance().lookup(provider);
            if (!clientOpt.isPresent()) {
                return errorResult("no runtime client for provider " + provider + " to parse responses");
            }
            LlmClient client = clientOpt.get();
            String modelOverride = emptyToNull(params.path("model").asText(null));
            String path = emptyToNull(params.path("path").asText(null));
            List<LogEventRequestAndResponse> pairs = retrieveRecordedPairs(path);

            int checked = 0;
            int skipped = 0;
            int unpriceable = 0;
            long totalInputTokens = 0;
            long totalOutputTokens = 0;
            double totalCostUsd = 0.0;
            ArrayNode results = objectMapper.createArrayNode();
            for (LogEventRequestAndResponse pair : pairs) {
                HttpResponse recordedResponse = pair.getHttpResponse();
                if (recordedResponse == null) {
                    continue;
                }
                Usage usage;
                try {
                    usage = client.parseCompletionResponse(recordedResponse).getUsage();
                } catch (Exception e) {
                    usage = null;
                }
                if (usage == null || (usage.getInputTokens() == null && usage.getOutputTokens() == null)) {
                    skipped++;
                    continue;
                }
                checked++;
                long inputTokens = usage.getInputTokens() == null ? 0 : usage.getInputTokens();
                long outputTokens = usage.getOutputTokens() == null ? 0 : usage.getOutputTokens();
                totalInputTokens += inputTokens;
                totalOutputTokens += outputTokens;

                String model = modelOverride != null ? modelOverride : modelFromRequest(pair.getHttpRequest());
                boolean vertexAi = pair.getHttpRequest() instanceof HttpRequest
                    && LlmProviderSniffer.isVertexAiHost((HttpRequest) pair.getHttpRequest(), null);
                Double cost = vertexAi
                    ? LlmPricing.estimateVertexAiCostUsd(provider, model, inputTokens, outputTokens)
                    : LlmPricing.estimateCostUsd(provider, model, inputTokens, outputTokens);

                ObjectNode entry = objectMapper.createObjectNode();
                if (pair.getHttpRequest() instanceof HttpRequest && ((HttpRequest) pair.getHttpRequest()).getPath() != null) {
                    entry.put("path", ((HttpRequest) pair.getHttpRequest()).getPath().getValue());
                }
                if (model != null) {
                    entry.put("model", model);
                }
                entry.put("inputTokens", inputTokens);
                entry.put("outputTokens", outputTokens);
                if (cost == null) {
                    unpriceable++;
                    entry.put("priceable", false);
                } else {
                    totalCostUsd += cost;
                    entry.put("priceable", true);
                    entry.put("costUsd", cost);
                }
                results.add(entry);
            }

            ObjectNode resultNode = objectMapper.createObjectNode();
            resultNode.put("provider", provider.name());
            if (modelOverride != null) {
                resultNode.put("model", modelOverride);
            }
            resultNode.put("checked", checked);
            resultNode.put("skippedNoUsage", skipped);
            resultNode.put("unpriceable", unpriceable);
            resultNode.put("totalInputTokens", totalInputTokens);
            resultNode.put("totalOutputTokens", totalOutputTokens);
            resultNode.put("totalCostUsd", totalCostUsd);
            resultNode.put("maxCostUsd", maxCostUsd);
            resultNode.put("withinBudget", totalCostUsd <= maxCostUsd);
            // withinBudget only reflects the priced subset. It is authoritative only when at
            // least one response was checked AND every checked response could be priced —
            // otherwise the total understates the true cost and the gate may pass vacuously.
            resultNode.put("budgetCheckAuthoritative", checked > 0 && unpriceable == 0);
            resultNode.set("results", results);
            return resultNode;
        } catch (Exception e) {
            return errorResult("Failed to verify cost budget", e);
        }
    }

    /**
     * Best-effort extraction of the {@code model} field from a recorded LLM
     * request body. Returns {@code null} if the body is absent or not JSON with
     * a textual {@code model} field.
     */
    private String modelFromRequest(RequestDefinition requestDefinition) {
        if (!(requestDefinition instanceof HttpRequest)) {
            return null;
        }
        HttpRequest httpRequest = (HttpRequest) requestDefinition;
        // a native Gemini call names its model only in its path
        String pathModel = httpRequest.getPath() != null
            ? LlmProviderSniffer.extractGeminiModelFromPath(httpRequest.getPath().getValue())
            : null;
        if (httpRequest.getBody() == null) {
            return pathModel;
        }
        try {
            JsonNode body = objectMapper.readTree(httpRequest.getBodyAsText());
            JsonNode model = body.path("model");
            return model.isTextual() ? model.asText() : pathModel;
        } catch (Exception e) {
            return pathModel;
        }
    }

    /**
     * Retrieve recorded request/response pairs (optionally filtered by path) from
     * the running server's event log.
     */
    private List<LogEventRequestAndResponse> retrieveRecordedPairs(String path) {
        HttpRequest filter = request();
        if (path != null && !path.isEmpty()) {
            filter.withPath(path);
        }
        HttpRequest retrieveRequest = request()
            .withMethod("PUT")
            .withPath("/mockserver/retrieve")
            .withQueryStringParameter("type", "REQUEST_RESPONSES")
            .withQueryStringParameter("format", "JSON")
            .withBody(getRequestDefinitionSerializer().serialize(filter));
        HttpResponse retrieveResponse = httpState.retrieve(retrieveRequest);
        String body = retrieveResponse.getBodyAsString();
        List<LogEventRequestAndResponse> result = new ArrayList<>();
        if (body != null && !body.trim().isEmpty()) {
            try {
                LogEventRequestAndResponse[] pairs =
                    new LogEventRequestAndResponseSerializer(mockServerLogger).deserializeArray(body);
                result.addAll(Arrays.asList(pairs));
            } catch (IllegalArgumentException e) {
                // No parseable request/response pairs (e.g. an empty "[]" result) — treat as none.
            }
        }
        return result;
    }

    // --- explain_agent_run ---

    private void registerExplainAgentRun() {
        ObjectNode schema = objectMapper.createObjectNode();
        schema.put("type", "object");
        ObjectNode properties = schema.putObject("properties");
        ObjectNode providerProp = properties.putObject("provider");
        providerProp.put("type", "string").put("description", "LLM provider whose recorded requests to summarise. Use AUTO to detect the provider from the recorded request paths (useful for proxied traffic where the provider is implicit in the upstream URL).");
        ArrayNode providerEnum = providerProp.putArray("enum");
        providerEnum.add("AUTO");
        for (String name : ProviderCodecRegistry.getInstance().supportedProviderNames()) {
            providerEnum.add(name);
        }
        properties.putObject("path").put("type", "string").put("description", "Optional request path filter (e.g. /v1/messages)");
        ObjectNode isolationTypeProp = properties.putObject("isolationType");
        isolationTypeProp.put("type", "string").put("description", "Optional: scope the run to a single session by the isolation source the conversation was isolated on.");
        ArrayNode isolationTypeEnum = isolationTypeProp.putArray("enum");
        isolationTypeEnum.add("header");
        isolationTypeEnum.add("query_parameter");
        isolationTypeEnum.add("cookie");
        properties.putObject("isolationKey").put("type", "string").put("description", "Optional: the header/query-parameter/cookie name used to isolate the session (e.g. x-agent-id).");
        properties.putObject("isolationValue").put("type", "string").put("description", "Optional: the value identifying the session (e.g. agent-001).");
        ArrayNode required = schema.putArray("required");
        required.add("provider");

        tools.put("explain_agent_run", new ToolDefinition(
            "explain_agent_run",
            "Summarise an agent run reconstructed from recorded LLM requests: message and assistant-turn counts, the ordered tool-call sequence, tool results, and the latest message role. Works with both mock-backed and proxied/forwarded traffic.",
            schema,
            this::handleExplainAgentRun
        ));
    }

    private JsonNode handleExplainAgentRun(JsonNode params) {
        try {
            String providerStr = params.path("provider").asText(null);
            if (providerStr == null || providerStr.trim().isEmpty()) {
                return errorResult("'provider' is required");
            }
            String path = emptyToNull(params.path("path").asText(null));
            String isolationType = emptyToNull(params.path("isolationType").asText(null));
            String isolationKey = emptyToNull(params.path("isolationKey").asText(null));
            String isolationValue = emptyToNull(params.path("isolationValue").asText(null));
            List<HttpRequest> requests = retrieveRecordedHttpRequests(path, isolationType, isolationKey, isolationValue);
            Provider provider = resolveProviderOrAuto(params, requests);
            if (provider == null) {
                return "AUTO".equalsIgnoreCase(providerStr.trim())
                    ? errorResult("could not auto-detect LLM provider from recorded request paths; specify the provider explicitly")
                    : unsupportedLlmProviderResult(providerStr);
            }

            Optional<AgentRunAnalyzer.RunSummary> summaryOpt = new AgentRunAnalyzer().summarise(requests, provider);

            ObjectNode resultNode = objectMapper.createObjectNode();
            resultNode.put("provider", provider.name());
            if (!summaryOpt.isPresent()) {
                resultNode.put("message", "no decodable " + provider + " conversation found in recorded requests");
                resultNode.put("messageCount", 0);
                return resultNode;
            }
            AgentRunAnalyzer.RunSummary summary = summaryOpt.get();
            resultNode.put("messageCount", summary.getMessageCount());
            resultNode.put("assistantTurnCount", summary.getAssistantTurnCount());
            ArrayNode toolCalls = resultNode.putArray("toolCallSequence");
            summary.getToolCallSequence().forEach(toolCalls::add);
            ArrayNode toolResults = resultNode.putArray("toolResultsFor");
            summary.getToolResultsFor().forEach(toolResults::add);
            resultNode.put("latestMessageRole", summary.getLatestMessageRole());

            // correlated call graph (nodes + directed edges)
            AgentRunAnalyzer.CallGraph graph = new AgentRunAnalyzer().buildCallGraph(requests, provider);
            ObjectNode graphNode = resultNode.putObject("callGraph");
            ArrayNode graphNodes = graphNode.putArray("nodes");
            for (AgentRunAnalyzer.GraphNode node : graph.getNodes()) {
                ObjectNode n = graphNodes.addObject();
                n.put("id", node.getId());
                n.put("kind", node.getKind());
                n.put("label", node.getLabel());
            }
            ArrayNode graphEdges = graphNode.putArray("edges");
            for (AgentRunAnalyzer.GraphEdge edge : graph.getEdges()) {
                ObjectNode e = graphEdges.addObject();
                e.put("from", edge.getFrom());
                e.put("to", edge.getTo());
                e.put("kind", edge.getKind());
            }
            return resultNode;
        } catch (Exception e) {
            return errorResult("Failed to explain agent run", e);
        }
    }

    // --- export_optimisation_report ---

    private void registerExportOptimisationReport() {
        ObjectNode schema = objectMapper.createObjectNode();
        schema.put("type", "object");
        ObjectNode properties = schema.putObject("properties");
        ObjectNode formatProp = properties.putObject("format");
        formatProp.put("type", "string").put("description",
            "Output format: 'markdown' for the copy-paste optimisation brief (default), 'json' for the structured LlmOptimisationReport bundle, 'csv' for the per-call + totals spreadsheet export, or an eval/fine-tune dataset export of the captured sessions — 'openai-evals' (OpenAI-evals JSONL), 'fine-tune' (chat fine-tune JSONL), or 'promptfoo' (promptfoo test-suite JSON). All formats redact secrets.");
        ArrayNode formatEnum = formatProp.putArray("enum");
        formatEnum.add("markdown");
        formatEnum.add("json");
        formatEnum.add("csv");
        formatEnum.add("openai-evals");
        formatEnum.add("fine-tune");
        formatEnum.add("promptfoo");
        properties.putObject("session").put("type", "string").put("description",
            "Optional session/grouping key filter (e.g. 'host:api.openai.com'); default is all captured LLM traffic.");
        properties.putObject("host").put("type", "string").put("description",
            "Optional upstream host filter (e.g. api.openai.com).");
        properties.putObject("provider").put("type", "string").put("description",
            "Optional LLM provider filter (e.g. OPENAI, ANTHROPIC).");

        tools.put("export_optimisation_report", new ToolDefinition(
            "export_optimisation_report",
            "Turn captured LLM traffic (proxied through MockServer) into an optimisation export: a copy-paste Markdown "
                + "'optimisation brief' (paste into any LLM for cost-reduction advice) or the structured JSON bundle. "
                + "Includes deterministic optimisation signals (repeated system prompts, large static context resent, "
                + "deterministic tool calls, oversized tool results, output bloat, duplicate retries) with token and cost "
                + "estimates. Export-only and deterministic — MockServer never calls an LLM. Secrets are redacted.",
            schema,
            this::handleExportOptimisationReport
        ));
    }

    private JsonNode handleExportOptimisationReport(JsonNode params) {
        try {
            String format = params.path("format").asText("markdown");
            java.util.Optional<org.mockserver.llm.analysis.LlmDatasetExporter.DatasetFormat> datasetFormat =
                org.mockserver.llm.analysis.LlmDatasetExporter.DatasetFormat.fromWire(format);
            if (!datasetFormat.isPresent()
                && !"markdown".equalsIgnoreCase(format) && !"json".equalsIgnoreCase(format) && !"csv".equalsIgnoreCase(format)) {
                return errorResult("'format' must be one of: markdown, json, csv, openai-evals, fine-tune, promptfoo");
            }
            org.mockserver.llm.analysis.LlmOptimisationReportService.Filter filter =
                new org.mockserver.llm.analysis.LlmOptimisationReportService.Filter(
                    emptyToNull(params.path("session").asText(null)),
                    emptyToNull(params.path("host").asText(null)),
                    emptyToNull(params.path("provider").asText(null)));

            List<LogEventRequestAndResponse> pairs = retrieveRecordedPairs(null);
            org.mockserver.llm.analysis.LlmOptimisationReportService service =
                new org.mockserver.llm.analysis.LlmOptimisationReportService(httpState.getConfiguration());
            org.mockserver.llm.analysis.LlmOptimisationReportService.Result result = service.build(pairs, filter);

            if (datasetFormat.isPresent()) {
                ObjectNode resultNode = objectMapper.createObjectNode();
                resultNode.put("format", format.toLowerCase().replace('_', '-'));
                resultNode.put("dataset", service.renderDataset(result, datasetFormat.get()));
                return resultNode;
            }
            if ("json".equalsIgnoreCase(format)) {
                ObjectNode resultNode = objectMapper.createObjectNode();
                resultNode.put("format", "json");
                resultNode.set("report", objectMapper.valueToTree(result.getReport()));
                return resultNode;
            }
            if ("csv".equalsIgnoreCase(format)) {
                ObjectNode resultNode = objectMapper.createObjectNode();
                resultNode.put("format", "csv");
                resultNode.put("csv", service.renderCsv(result));
                return resultNode;
            }
            ObjectNode resultNode = objectMapper.createObjectNode();
            resultNode.put("format", "markdown");
            resultNode.put("brief", service.renderBrief(result));
            return resultNode;
        } catch (Exception e) {
            return errorResult("Failed to export optimisation report", e);
        }
    }

    // --- diff_agent_runs ---

    private void registerDiffAgentRuns() {
        ObjectNode schema = objectMapper.createObjectNode();
        schema.put("type", "object");
        ObjectNode properties = schema.putObject("properties");
        ObjectNode before = properties.putObject("before");
        before.put("type", "object").put("description",
            "Selects the BEFORE run from captured LLM traffic by session/host/provider (same filter as export_optimisation_report).");
        ObjectNode beforeProps = before.putObject("properties");
        beforeProps.putObject("session").put("type", "string").put("description", "Session/grouping key (e.g. 'host:api.openai.com').");
        beforeProps.putObject("host").put("type", "string").put("description", "Upstream host filter (e.g. api.openai.com).");
        beforeProps.putObject("provider").put("type", "string").put("description", "LLM provider filter (e.g. OPENAI).");
        ObjectNode after = properties.putObject("after");
        after.put("type", "object").put("description", "Selects the AFTER run — same shape as 'before'.");
        ObjectNode afterProps = after.putObject("properties");
        afterProps.putObject("session").put("type", "string").put("description", "Session/grouping key.");
        afterProps.putObject("host").put("type", "string").put("description", "Upstream host filter.");
        afterProps.putObject("provider").put("type", "string").put("description", "LLM provider filter.");
        properties.putObject("normalization").put("type", "object").put("description",
            "Optional prompt-normalisation options (whitespace, JSON key ordering, volatile ids) applied before diffing.");

        tools.put("diff_agent_runs", new ToolDefinition(
            "diff_agent_runs",
            "Prompt-level diff of two recorded agent runs captured through MockServer. Reconstructs each run's canonical "
                + "conversation, normalises the prompts (whitespace / JSON key order / volatile ids), and reports what changed: "
                + "message additions / removals / edits, tool calls added or removed, and the token / cost delta. Read-only and "
                + "deterministic — MockServer never calls an LLM. Message text is masked for credential shapes.",
            schema,
            this::handleDiffAgentRuns
        ));
    }

    private JsonNode handleDiffAgentRuns(JsonNode params) {
        try {
            org.mockserver.llm.analysis.LlmOptimisationReportService.Filter beforeFilter = filterFromParams(params.path("before"));
            org.mockserver.llm.analysis.LlmOptimisationReportService.Filter afterFilter = filterFromParams(params.path("after"));

            NormalizationOptions options = null;
            if (params.path("normalization").isObject()) {
                options = objectMapper.treeToValue(params.get("normalization"), NormalizationOptions.class);
            }

            List<LogEventRequestAndResponse> pairs = retrieveRecordedPairs(null);
            org.mockserver.llm.analysis.LlmOptimisationReportService service =
                new org.mockserver.llm.analysis.LlmOptimisationReportService(httpState.getConfiguration());
            org.mockserver.llm.analysis.LlmOptimisationReportService.Result beforeResult = service.build(pairs, beforeFilter);
            org.mockserver.llm.analysis.LlmOptimisationReportService.Result afterResult = service.build(pairs, afterFilter);

            // Redact each run's requests via the report service's FixtureRedactor (default
            // sensitive headers/query params + configured fixtureBodyRedactFields) BEFORE the
            // diff decodes and surfaces prompt text, so a configured body field is masked in
            // the diff output — the same guarantee the export path gives.
            FixtureRedactor redactor = service.redactor();
            org.mockserver.llm.analysis.AgentRunDiff.RunDiffResult diff = new org.mockserver.llm.analysis.AgentRunDiff()
                .diff(diffRunSide(beforeResult, redactor), diffRunSide(afterResult, redactor), options);

            ObjectNode resultNode = objectMapper.createObjectNode();
            resultNode.put("promptChanged", diff.isPromptChanged());
            resultNode.put("messageCountBefore", diff.getMessageCountBefore());
            resultNode.put("messageCountAfter", diff.getMessageCountAfter());
            ArrayNode messageDiffs = resultNode.putArray("messageDiffs");
            for (org.mockserver.llm.analysis.AgentRunDiff.MessageDiff md : diff.getMessageDiffs()) {
                ObjectNode m = messageDiffs.addObject();
                m.put("changeType", md.getChangeType().name());
                m.put("role", md.getRole());
                m.put("beforeText", md.getBeforeText());
                m.put("afterText", md.getAfterText());
            }
            ArrayNode added = resultNode.putArray("toolCallsAdded");
            diff.getToolCallsAdded().forEach(added::add);
            ArrayNode removed = resultNode.putArray("toolCallsRemoved");
            diff.getToolCallsRemoved().forEach(removed::add);
            if (diff.getTokenDelta() != null) {
                org.mockserver.llm.analysis.AgentRunDiff.TokenDelta d = diff.getTokenDelta();
                ObjectNode t = resultNode.putObject("tokenDelta");
                t.put("inputTokensBefore", d.getInputTokensBefore());
                t.put("inputTokensAfter", d.getInputTokensAfter());
                t.put("inputTokensDelta", d.getInputTokensDelta());
                t.put("outputTokensBefore", d.getOutputTokensBefore());
                t.put("outputTokensAfter", d.getOutputTokensAfter());
                t.put("outputTokensDelta", d.getOutputTokensDelta());
                t.put("costUsdBefore", d.getCostUsdBefore());
                t.put("costUsdAfter", d.getCostUsdAfter());
                t.put("costUsdDelta", d.getCostUsdDelta());
            }
            return resultNode;
        } catch (Exception e) {
            return errorResult("Failed to diff agent runs", e);
        }
    }

    private static org.mockserver.llm.analysis.LlmOptimisationReportService.Filter filterFromParams(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return new org.mockserver.llm.analysis.LlmOptimisationReportService.Filter(null, null, null);
        }
        return new org.mockserver.llm.analysis.LlmOptimisationReportService.Filter(
            emptyToNull(node.path("session").asText(null)),
            emptyToNull(node.path("host").asText(null)),
            emptyToNull(node.path("provider").asText(null)));
    }

    private static org.mockserver.llm.analysis.AgentRunDiff.RunSide diffRunSide(
        org.mockserver.llm.analysis.LlmOptimisationReportService.Result result, FixtureRedactor redactor) {
        List<HttpRequest> requests = new ArrayList<>();
        Provider provider = null;
        for (org.mockserver.llm.analysis.LlmOptimisationReportBuilder.CapturedExchange exchange : result.getIncludedExchanges()) {
            if (exchange.getRequest() == null) {
                continue;
            }
            // Detect on the RAW exchange, decode the REDACTED request (mirrors the export path).
            if (provider == null) {
                provider = LlmProviderSniffer.detectForAnalysis(exchange.getRequest(), exchange.getResponse()).orElse(null);
            }
            RequestDefinition redacted = redactor.redactRequestDefinition(exchange.getRequest());
            requests.add(redacted instanceof HttpRequest ? (HttpRequest) redacted : exchange.getRequest());
        }
        org.mockserver.llm.analysis.LlmOptimisationReport.Totals totals = result.getReport().getTotals();
        return new org.mockserver.llm.analysis.AgentRunDiff.RunSide(
            requests, provider,
            totals != null ? totals.getInputTokens() : null,
            totals != null ? totals.getOutputTokens() : null,
            totals != null ? totals.getEstimatedCostUsd() : null);
    }

    // --- detect_llm_drift ---

    private void registerDetectLlmDrift() {
        ObjectNode schema = objectMapper.createObjectNode();
        schema.put("type", "object");
        ObjectNode properties = schema.putObject("properties");
        properties.putObject("cassettePath").put("type", "string").put("description",
            "Path to a recorded fixture (cassette) JSON file whose exchanges are replayed against the live provider");
        ObjectNode providerProp = properties.putObject("provider");
        providerProp.put("type", "string").put("description", "LLM provider the cassette was recorded against");
        ArrayNode providerEnum = providerProp.putArray("enum");
        for (String name : ProviderCodecRegistry.getInstance().supportedProviderNames()) {
            providerEnum.add(name);
        }
        properties.putObject("backendName").put("type", "string").put("description",
            "Optional named backend from mockserver.llmBackendsConfig; otherwise the default backend is resolved from env/properties");
        ArrayNode required = schema.putArray("required");
        required.add("cassettePath");
        required.add("provider");

        tools.put("detect_llm_drift", new ToolDefinition(
            "detect_llm_drift",
            "Replays a recorded LLM cassette against the live provider and reports STRUCTURAL drift in the responses "
                + "(new/removed fields, type changes) — not semantic differences. Requires a configured runtime LLM backend "
                + "(env vars, mockserver.llmProvider/llmApiKey, or a named backend); if none is configured the tool is disabled. "
                + "Fails closed per exchange: a network error or non-2xx live response is reported as could-not-check, never as drift. "
                + "Intended for an opt-in/scheduled CI lane, not the per-commit build.",
            schema,
            this::handleDetectLlmDrift
        ));
    }

    private JsonNode handleDetectLlmDrift(JsonNode params) {
        try {
            String providerStr = params.path("provider").asText(null);
            if (providerStr == null || providerStr.trim().isEmpty()) {
                return errorResult("'provider' is required");
            }
            Provider provider = parseProviderParam(params);
            if (provider == null) {
                return unsupportedLlmProviderResult(providerStr);
            }
            String cassettePath = params.path("cassettePath").asText(null);
            if (cassettePath == null || cassettePath.trim().isEmpty()) {
                return errorResult("'cassettePath' is required and must not be blank");
            }
            Path inputPath = Paths.get(cassettePath).toAbsolutePath().normalize();
            ObjectNode pathError = validateFilePath(inputPath, objectMapper);
            if (pathError != null) {
                return pathError;
            }
            if (!Files.exists(inputPath) || !Files.isReadable(inputPath)) {
                return errorResult("Cassette file does not exist or is not readable: " + inputPath);
            }

            // Resolve a backend (off unless configured)
            LlmBackendResolver resolver = new LlmBackendResolver(httpState.getConfiguration());
            String backendName = emptyToNull(params.path("backendName").asText(null));
            Optional<LlmBackend> backendOpt = backendName != null ? resolver.resolveByName(backendName) : resolver.resolveDefault();
            if (!backendOpt.isPresent()) {
                ObjectNode disabled = objectMapper.createObjectNode();
                disabled.put("disabled", true);
                disabled.put("message", "No runtime LLM backend configured — drift detection is off. Set OPENAI_API_KEY/"
                    + "ANTHROPIC_API_KEY/GEMINI_API_KEY/OLLAMA_HOST, the mockserver.llm* properties, or mockserver.llmBackendsConfig.");
                return disabled;
            }
            LlmBackend backend = backendOpt.get();

            // Extract recorded exchanges (non-streaming JSON responses)
            String json = new String(Files.readAllBytes(inputPath), StandardCharsets.UTF_8);
            Expectation[] expectations = getExpectationSerializer().deserializeArray(json, false);
            List<DriftDetector.RecordedExchange> exchanges = new ArrayList<>();
            int skippedSse = 0;
            for (Expectation exp : expectations) {
                if (exp.getHttpRequest() instanceof HttpRequest && exp.getHttpResponse() != null) {
                    exchanges.add(new DriftDetector.RecordedExchange(
                        (HttpRequest) exp.getHttpRequest(), exp.getHttpResponse().getBodyAsText()));
                } else if (exp.getHttpSseResponse() != null) {
                    skippedSse++;
                }
            }
            if (exchanges.isEmpty()) {
                ObjectNode empty = objectMapper.createObjectNode();
                empty.put("status", "no_exchanges");
                empty.put("message", "No non-streaming exchanges found in cassette to check"
                    + (skippedSse > 0 ? " (" + skippedSse + " streaming exchange(s) skipped)" : ""));
                return empty;
            }

            // Build a transient transport for the live calls and run the detector.
            // Transient per-call is acceptable for the opt-in/scheduled CI lane this
            // tool targets; promote to a shared instance if usage becomes frequent.
            org.mockserver.configuration.Configuration driftConfiguration = httpState.getConfiguration();
            long timeout = backend.timeoutMillis() != null
                ? backend.timeoutMillis()
                : (driftConfiguration != null
                    ? driftConfiguration.llmRequestTimeoutMillis()
                    : ConfigurationProperties.llmRequestTimeoutMillis());
            io.netty.channel.EventLoopGroup group = new io.netty.channel.nio.NioEventLoopGroup(1);
            DriftReport report;
            try {
                NettyHttpClient httpClient = new NettyHttpClient(httpState.getConfiguration(), mockServerLogger, group, null, false);
                report = new DriftDetector(new NettyHttpClientLlmTransport(httpClient), timeout)
                    .detect(exchanges, provider, backend);
            } finally {
                // bounded wind-down so threads do not accumulate across rapid calls
                group.shutdownGracefully(0, 200, java.util.concurrent.TimeUnit.MILLISECONDS);
            }

            ObjectNode resultNode = objectMapper.createObjectNode();
            resultNode.put("status", "checked");
            // surface a likely misconfiguration: the cassette's provider and the
            // resolved backend's provider differ, so every live call will mis-target
            if (backend.provider() != provider) {
                resultNode.put("warning", "resolved backend provider " + backend.provider()
                    + " does not match cassette provider " + provider + "; live calls may all fail");
            }
            resultNode.put("exchanges", exchanges.size());
            resultNode.put("drifted", report.driftedCount());
            resultNode.put("checked", report.checkedCount());
            resultNode.put("couldNotCheck", report.couldNotCheckCount());
            if (skippedSse > 0) {
                resultNode.put("skippedStreaming", skippedSse);
            }
            ArrayNode details = resultNode.putArray("details");
            for (DriftReport.ExchangeDrift ex : report.getExchanges()) {
                ObjectNode node = details.addObject();
                node.put("index", ex.getIndex());
                node.put("status", ex.getStatus().name());
                if (ex.getNote() != null) {
                    node.put("note", ex.getNote());
                }
                if (!ex.getAddedPaths().isEmpty()) {
                    ArrayNode a = node.putArray("addedPaths");
                    ex.getAddedPaths().forEach(a::add);
                }
                if (!ex.getRemovedPaths().isEmpty()) {
                    ArrayNode r = node.putArray("removedPaths");
                    ex.getRemovedPaths().forEach(r::add);
                }
                if (!ex.getTypeChangedPaths().isEmpty()) {
                    ArrayNode t = node.putArray("typeChangedPaths");
                    ex.getTypeChangedPaths().forEach(t::add);
                }
            }
            return resultNode;
        } catch (Exception e) {
            return errorResult("Failed to detect LLM drift", e);
        }
    }

    // --- mock_adversarial_llm_response ---

    private void registerMockAdversarialLlmResponse() {
        ObjectNode schema = objectMapper.createObjectNode();
        schema.put("type", "object");
        ObjectNode properties = schema.putObject("properties");
        ObjectNode providerProp = properties.putObject("provider");
        providerProp.put("type", "string").put("description", "LLM provider with a registered codec");
        ArrayNode providerEnum = providerProp.putArray("enum");
        for (String name : ProviderCodecRegistry.getInstance().supportedProviderNames()) {
            providerEnum.add(name);
        }
        properties.putObject("path").put("type", "string").put("description", "Request path to match (e.g. /v1/messages)");
        properties.putObject("model").put("type", "string").put("description", "Model name");
        ObjectNode payloadProp = properties.putObject("payload");
        payloadProp.put("type", "string").put("description", "Adversarial payload id — one of the enum values below");
        ArrayNode payloadEnum = payloadProp.putArray("enum");
        for (String id : org.mockserver.llm.adversarial.AdversarialResponseLibrary.ids()) {
            payloadEnum.add(id);
        }
        ArrayNode required = schema.putArray("required");
        required.add("provider");
        required.add("path");
        required.add("payload");

        tools.put("mock_adversarial_llm_response", new ToolDefinition(
            "mock_adversarial_llm_response",
            "Creates a mock LLM completion whose response is a curated ADVERSARIAL payload "
                + "(prompt injection, jailbreak, data-exfiltration request, malformed/oversized content) so you can "
                + "test that your agent RESISTS hostile or malformed model/tool output. Defensive testing aid — the "
                + "payloads are benign test fixtures, not working exploits. Deterministic.",
            schema,
            this::handleMockAdversarialLlmResponse
        ));
    }

    private JsonNode handleMockAdversarialLlmResponse(JsonNode params) {
        try {
            String providerStr = params.path("provider").asText(null);
            if (providerStr == null || providerStr.trim().isEmpty()) {
                return errorResult("'provider' is required");
            }
            Provider provider = parseProviderParam(params);
            if (provider == null) {
                return unsupportedLlmProviderResult(providerStr);
            }
            String path = params.path("path").asText(null);
            if (path == null || path.trim().isEmpty()) {
                return errorResult("'path' is required and must not be blank");
            }
            String payloadId = params.path("payload").asText(null);
            if (payloadId == null || payloadId.trim().isEmpty()) {
                return errorResult("'payload' is required");
            }
            Optional<org.mockserver.llm.adversarial.AdversarialResponseLibrary.Payload> payload =
                org.mockserver.llm.adversarial.AdversarialResponseLibrary.get(payloadId);
            if (!payload.isPresent()) {
                return errorResult("unknown adversarial payload '" + payloadId + "'; available: "
                    + org.mockserver.llm.adversarial.AdversarialResponseLibrary.ids());
            }

            Completion completion = Completion.completion().withText(payload.get().getText());
            HttpLlmResponse llmResponse = HttpLlmResponse.llmResponse()
                .withProvider(provider)
                .withModel(params.path("model").asText(null))
                .withCompletion(completion);
            Expectation expectation = Expectation.when(
                HttpRequest.request().withMethod("POST").withPath(path)
            ).thenRespondWithLlm(llmResponse);
            List<Expectation> result = httpState.add(expectation);

            ObjectNode resultNode = objectMapper.createObjectNode();
            resultNode.put("status", "created");
            resultNode.put("count", result.size());
            if (!result.isEmpty()) {
                resultNode.put("id", result.get(0).getId());
            }
            resultNode.put("payload", payloadId);
            resultNode.put("category", payload.get().getCategory());
            return resultNode;
        } catch (Exception e) {
            return errorResult("Failed to create adversarial LLM response", e);
        }
    }

    private Provider parseProviderParam(JsonNode params) {
        String providerStr = params.path("provider").asText(null);
        if (providerStr == null || providerStr.trim().isEmpty()) {
            return null;
        }
        try {
            Provider provider = Provider.valueOf(providerStr.trim());
            return ProviderCodecRegistry.getInstance().lookup(provider).isPresent() ? provider : null;
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /**
     * Resolve the provider from the params, supporting {@code "AUTO"} as a
     * special value that auto-detects the provider from the recorded requests'
     * paths. Returns null if the provider cannot be resolved.
     */
    private Provider resolveProviderOrAuto(JsonNode params, List<HttpRequest> requests) {
        String providerStr = params.path("provider").asText(null);
        if (providerStr != null && "AUTO".equalsIgnoreCase(providerStr.trim())) {
            // Route AUTO detection through the analysis sniffer so it uses host +
            // request-body shape (not path alone), matching the Optimise report and
            // dashboard. Response bodies are not readily available here, so pass null.
            if (requests != null) {
                for (HttpRequest request : requests) {
                    Provider detected = LlmProviderSniffer.detectForAnalysis(request, null).orElse(null);
                    if (detected != null) {
                        return detected;
                    }
                }
            }
            return null;
        }
        return parseProviderParam(params);
    }

    private static String emptyToNull(String value) {
        return value == null || value.isEmpty() ? null : value;
    }

    private static List<String> splitCsv(String csv) {
        List<String> result = new ArrayList<>();
        if (csv != null && !csv.trim().isEmpty()) {
            for (String part : csv.split(",")) {
                String trimmed = part.trim();
                if (!trimmed.isEmpty()) {
                    result.add(trimmed);
                }
            }
        }
        return result;
    }

    /**
     * Replay normalisation: if the expectation's request has a JSON body, drop the
     * named (volatile) fields and match the remainder loosely (extra fields in the
     * incoming request are ignored), so volatile values do not block replay.
     */
    private void relaxRequestBodyForReplay(Expectation expectation, List<String> dropFields) {
        if (!(expectation.getHttpRequest() instanceof HttpRequest)) {
            return;
        }
        HttpRequest request = (HttpRequest) expectation.getHttpRequest();
        String bodyString = request.getBodyAsText();
        if (bodyString == null || bodyString.isEmpty()) {
            return;
        }
        try {
            JsonNode root = objectMapper.readTree(bodyString);
            if (root != null && (root.isObject() || root.isArray())) {
                Set<String> dropSet = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
                dropSet.addAll(dropFields);
                removeFieldsRecursively(root, dropSet);
                request.withBody(org.mockserver.model.JsonBody.json(objectMapper.writeValueAsString(root), MatchType.ONLY_MATCHING_FIELDS));
            }
        } catch (Exception e) {
            // not JSON — leave the body matcher unchanged
        }
    }

    private void removeFieldsRecursively(JsonNode node, Set<String> dropFields) {
        if (node.isObject()) {
            ObjectNode object = (ObjectNode) node;
            List<String> toRemove = new ArrayList<>();
            object.fieldNames().forEachRemaining(name -> {
                if (dropFields.contains(name)) {
                    toRemove.add(name);
                }
            });
            toRemove.forEach(object::remove);
            object.fields().forEachRemaining(e -> removeFieldsRecursively(e.getValue(), dropFields));
        } else if (node.isArray()) {
            for (JsonNode child : node) {
                removeFieldsRecursively(child, dropFields);
            }
        }
    }

    /**
     * Add the optional {@code chaos} object schema to a tool's properties.
     */
    private void putChaosSchema(ObjectNode properties) {
        ObjectNode chaosProp = properties.putObject("chaos");
        chaosProp.put("type", "object").put("description", "Optional fault/chaos profile for resilience testing");
        ObjectNode chaosProps = chaosProp.putObject("properties");
        chaosProps.putObject("errorStatus").put("type", "integer").put("description", "HTTP error status to return (e.g. 429, 529); fires always unless errorProbability is set");
        chaosProps.putObject("retryAfter").put("type", "string").put("description", "Value for the Retry-After header on an injected error");
        chaosProps.putObject("errorProbability").put("type", "number").put("description", "Probability 0.0-1.0 of injecting the error (default 1.0 when errorStatus set)");
        ObjectNode truncateModeProp = chaosProps.putObject("truncateMode");
        truncateModeProp.put("type", "string").put("description", "Streaming truncation mode");
        ArrayNode truncateEnum = truncateModeProp.putArray("enum");
        truncateEnum.add("NONE");
        truncateEnum.add("MID_STREAM");
        chaosProps.putObject("truncateAtFraction").put("type", "number").put("description", "Fraction 0.0-1.0 of SSE events to keep before truncating (default 0.5)");
        chaosProps.putObject("malformedSse").put("type", "boolean").put("description", "Append a malformed (broken-JSON) SSE chunk");
        chaosProps.putObject("seed").put("type", "integer").put("description", "Seed making a fractional errorProbability reproducible");
        chaosProps.putObject("quotaName").put("type", "string").put("description", "Stateful quota bucket name; expectations sharing a name share one request counter");
        chaosProps.putObject("quotaLimit").put("type", "integer").put("description", "Stateful quota: max requests allowed per window before requests are rejected");
        chaosProps.putObject("quotaWindowMillis").put("type", "integer").put("description", "Stateful quota: window length in milliseconds");
        chaosProps.putObject("quotaErrorStatus").put("type", "integer").put("description", "Stateful quota: HTTP status returned when the quota is exceeded (default 429)");
    }

    /**
     * Parse an optional chaos profile object into an {@link LlmChaosProfile}, or
     * null if the node is absent/empty.
     */
    private LlmChaosProfile parseChaosProfile(JsonNode node) {
        if (node == null || !node.isObject() || node.size() == 0) {
            return null;
        }
        LlmChaosProfile chaos = LlmChaosProfile.llmChaosProfile();
        if (node.path("errorStatus").isIntegralNumber()) {
            chaos.withErrorStatus(node.path("errorStatus").asInt());
        }
        if (node.path("retryAfter").isTextual()) {
            chaos.withRetryAfter(node.path("retryAfter").asText());
        }
        if (node.path("errorProbability").isNumber()) {
            chaos.withErrorProbability(node.path("errorProbability").asDouble());
        }
        if (node.path("truncateMode").isTextual()) {
            try {
                chaos.withTruncateMode(LlmChaosProfile.TruncateMode.valueOf(node.path("truncateMode").asText().toUpperCase()));
            } catch (IllegalArgumentException ignored) {
                // leave unset on an unrecognised value
            }
        }
        if (node.path("truncateAtFraction").isNumber()) {
            chaos.withTruncateAtFraction(node.path("truncateAtFraction").asDouble());
        }
        if (node.path("malformedSse").isBoolean()) {
            chaos.withMalformedSse(node.path("malformedSse").asBoolean());
        }
        if (node.path("seed").isIntegralNumber()) {
            chaos.withSeed(node.path("seed").asLong());
        }
        if (node.path("quotaName").isTextual()) {
            chaos.withQuotaName(node.path("quotaName").asText());
        }
        if (node.path("quotaLimit").isIntegralNumber()) {
            chaos.withQuotaLimit(node.path("quotaLimit").asInt());
        }
        if (node.path("quotaWindowMillis").isIntegralNumber()) {
            chaos.withQuotaWindowMillis(node.path("quotaWindowMillis").asLong());
        }
        if (node.path("quotaErrorStatus").isIntegralNumber()) {
            chaos.withQuotaErrorStatus(node.path("quotaErrorStatus").asInt());
        }
        return chaos;
    }

    /**
     * Retrieve the requests recorded by MockServer (optionally filtered by path)
     * as concrete {@link HttpRequest}s for LLM analysis.
     */
    private List<HttpRequest> retrieveRecordedHttpRequests(String path) {
        return retrieveRecordedHttpRequests(path, null, null, null);
    }

    private List<HttpRequest> retrieveRecordedHttpRequests(String path, String isolationType, String isolationKey, String isolationValue) {
        HttpRequest filter = request();
        if (path != null && !path.isEmpty()) {
            filter.withPath(path);
        }
        if (isolationKey != null && !isolationKey.trim().isEmpty()
            && isolationValue != null && !isolationValue.trim().isEmpty()
            && isolationType != null) {
            switch (isolationType.trim().toLowerCase()) {
                case "header":
                    filter.withHeader(isolationKey, isolationValue);
                    break;
                case "query_parameter":
                    filter.withQueryStringParameter(isolationKey, isolationValue);
                    break;
                case "cookie":
                    filter.withCookie(isolationKey, isolationValue);
                    break;
                default:
                    // unknown isolation type: no extra filter (graceful)
                    break;
            }
        }
        HttpRequest retrieveRequest = request()
            .withMethod("PUT")
            .withPath("/mockserver/retrieve")
            .withQueryStringParameter("type", "REQUESTS")
            .withQueryStringParameter("format", "JSON")
            .withBody(getRequestDefinitionSerializer().serialize(filter));
        HttpResponse retrieveResponse = httpState.retrieve(retrieveRequest);
        String body = retrieveResponse.getBodyAsString();
        List<HttpRequest> result = new ArrayList<>();
        if (body != null && !body.isEmpty()) {
            RequestDefinition[] definitions = getRequestDefinitionSerializer().deserializeArray(body);
            for (RequestDefinition definition : definitions) {
                if (definition instanceof HttpRequest) {
                    result.add((HttpRequest) definition);
                }
            }
        }
        return result;
    }

    private ObjectNode errorResult(String message) {
        ObjectNode resultNode = objectMapper.createObjectNode();
        resultNode.put("error", true);
        resultNode.put("message", message);
        return resultNode;
    }

    private ObjectNode errorResult(String message, Throwable throwable) {
        if (mockServerLogger.isEnabledForInstance(Level.WARN)) {
            mockServerLogger.logEvent(
                new LogEntry()
                    .setLogLevel(Level.WARN)
                    .setMessageFormat("MCP tool error: {}")
                    .setArguments(message)
                    .setThrowable(throwable)
            );
        }
        return errorResult(message);
    }

    private ObjectNode unsupportedLlmProviderResult(String providerStr) {
        ObjectNode resultNode = objectMapper.createObjectNode();
        resultNode.put("error", true);
        resultNode.put("message", "unsupported LLM provider: " + providerStr);
        ArrayNode supported = resultNode.putArray("supported");
        for (String name : ProviderCodecRegistry.getInstance().supportedProviderNames()) {
            supported.add(name);
        }
        return resultNode;
    }

    public static class ToolDefinition {
        private final String name;
        private final String description;
        private final JsonNode inputSchema;
        private final Function<JsonNode, JsonNode> handler;

        public ToolDefinition(String name, String description, JsonNode inputSchema, Function<JsonNode, JsonNode> handler) {
            this.name = name;
            this.description = description;
            this.inputSchema = inputSchema;
            this.handler = handler;
        }

        public String getName() {
            return name;
        }

        public String getDescription() {
            return description;
        }

        public JsonNode getInputSchema() {
            return inputSchema;
        }

        public Function<JsonNode, JsonNode> getHandler() {
            return handler;
        }
    }
}
