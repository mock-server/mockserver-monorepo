package org.mockserver.netty.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockserver.lifecycle.LifeCycle;
import org.mockserver.log.model.LogEntry;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.mock.HttpState;
import org.mockserver.scheduler.Scheduler;
import org.mockserver.serialization.ObjectMapperFactory;

import java.util.Arrays;
import java.util.Map;

import static org.hamcrest.CoreMatchers.*;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.closeTo;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockserver.configuration.Configuration.configuration;
import static org.mockserver.log.model.LogEntry.LogMessageType.FORWARDED_REQUEST;
import static org.mockserver.log.model.LogEntry.LogMessageType.RECEIVED_REQUEST;
import static org.mockserver.model.HttpRequest.request;
import static org.mockserver.model.HttpResponse.response;

public class McpToolRegistryTest {

    private McpToolRegistry toolRegistry;
    private HttpState httpState;
    private LifeCycle server;
    private ObjectMapper objectMapper;

    @Before
    public void setUp() {
        server = mock(LifeCycle.class);
        when(server.getScheduler()).thenReturn(mock(Scheduler.class));
        when(server.getLocalPorts()).thenReturn(Arrays.asList(1080));
        when(server.isRunning()).thenReturn(true);

        httpState = new HttpState(configuration(), new MockServerLogger(), mock(Scheduler.class));
        toolRegistry = new McpToolRegistry(httpState, server);
        objectMapper = ObjectMapperFactory.buildObjectMapperWithoutRemovingEmptyValues();
        // CassetteRegistry is a process-wide singleton — start each test from empty
        org.mockserver.mock.CassetteRegistry.getInstance().reset();
    }

    @After
    public void resetServiceChaos() {
        org.mockserver.mock.action.http.ServiceChaosRegistry.getInstance().reset();
        org.mockserver.mock.CassetteRegistry.getInstance().reset();
        httpState.stop();
    }

    @Test
    public void shouldRegisterAllTools() {
        Map<String, McpToolRegistry.ToolDefinition> tools = toolRegistry.getTools();
        assertThat(tools.containsKey("create_expectation"), is(true));
        assertThat(tools.containsKey("verify_request"), is(true));
        assertThat(tools.containsKey("retrieve_recorded_requests"), is(true));
        assertThat(tools.containsKey("clear_expectations"), is(true));
        assertThat(tools.containsKey("reset"), is(true));
        assertThat(tools.containsKey("get_status"), is(true));
        assertThat(tools.containsKey("verify_request_sequence"), is(true));
        assertThat(tools.containsKey("retrieve_request_responses"), is(true));
        assertThat(tools.containsKey("create_forward_expectation"), is(true));
        assertThat(tools.containsKey("debug_request_mismatch"), is(true));
        assertThat(tools.containsKey("create_expectation_from_openapi"), is(true));
        assertThat(tools.containsKey("stop_server"), is(true));
        assertThat(tools.containsKey("raw_expectation"), is(true));
        assertThat(tools.containsKey("raw_retrieve"), is(true));
        assertThat(tools.containsKey("raw_verify"), is(true));
        assertThat(tools.containsKey("explain_unmatched_requests"), is(true));
        assertThat(tools.containsKey("create_expectations_from_recorded_traffic"), is(true));
        assertThat(tools.containsKey("verify_traffic_against_openapi"), is(true));
        assertThat(tools.containsKey("run_contract_test"), is(true));
        assertThat(tools.containsKey("run_resiliency_test"), is(true));
        assertThat(tools.containsKey("record_llm_fixtures"), is(true));
        assertThat(tools.containsKey("load_expectations_from_file"), is(true));
        assertThat(tools.containsKey("mock_llm_completion"), is(true));
        assertThat(tools.containsKey("create_llm_conversation"), is(true));
        assertThat(tools.containsKey("verify_tool_call"), is(true));
        assertThat(tools.containsKey("explain_agent_run"), is(true));
        assertThat(tools.containsKey("detect_llm_drift"), is(true));
        assertThat(tools.containsKey("mock_adversarial_llm_response"), is(true));
        assertThat(tools.containsKey("run_mcp_contract_test"), is(true));
        assertThat(tools.containsKey("verify_structured_output"), is(true));
        assertThat(tools.containsKey("verify_cost_budget"), is(true));
        assertThat(tools.containsKey("manage_service_chaos"), is(true));
        assertThat(tools.containsKey("list_mock_tools"), is(true));
        assertThat(tools.containsKey("retrieve_logs"), is(true));
        assertThat(tools.containsKey("mock_llm_failover"), is(true));
        assertThat(tools.containsKey("export_optimisation_report"), is(true));
        assertThat(tools.containsKey("diff_agent_runs"), is(true));
        assertThat(tools.containsKey("list_expectations"), is(true));
        assertThat(tools.containsKey("set_operating_mode"), is(true));
        assertThat(tools.containsKey("promote_recordings"), is(true));
        assertThat(tools.containsKey("explain_request"), is(true));
        assertThat(tools.containsKey("diagnose_config"), is(true));
        assertThat(tools.containsKey("explain_forward_failure"), is(true));
        assertThat(tools.containsKey("test_template"), is(true));
        assertThat(tools.containsKey("lint_expectations"), is(true));
        assertThat(tools.containsKey("suggest_expectation_fix"), is(true));
        assertThat(tools.size(), is(46));
    }

    @Test
    public void shouldHaveToolDefinitionsWithSchemas() {
        for (McpToolRegistry.ToolDefinition tool : toolRegistry.getTools().values()) {
            assertThat(tool.getName(), notNullValue());
            assertThat(tool.getDescription(), notNullValue());
            assertThat(tool.getInputSchema(), notNullValue());
            assertThat(tool.getInputSchema().path("type").asText(), is("object"));
        }
    }

    @Test
    public void shouldCreateExpectation() {
        ObjectNode params = objectMapper.createObjectNode();
        params.put("method", "GET");
        params.put("path", "/hello");
        params.put("statusCode", 200);
        params.put("responseBody", "world");

        JsonNode result = toolRegistry.callTool("create_expectation", params);
        assertThat(result.path("status").asText(), is("created"));
        assertThat(result.path("count").asInt(), is(1));
        assertThat(result.has("id"), is(true));
    }

    @Test
    public void shouldCreateExpectationWithTimes() {
        ObjectNode params = objectMapper.createObjectNode();
        params.put("method", "GET");
        params.put("path", "/limited");
        params.put("statusCode", 200);
        params.put("times", 5);

        JsonNode result = toolRegistry.callTool("create_expectation", params);
        assertThat(result.path("status").asText(), is("created"));
    }

    @Test
    public void shouldRejectNonNumericTimes() {
        ObjectNode params = objectMapper.createObjectNode();
        params.put("method", "GET");
        params.put("path", "/invalid-times");
        params.put("statusCode", 200);
        params.put("times", "not-a-number");

        JsonNode result = toolRegistry.callTool("create_expectation", params);
        assertThat(result.path("error").asBoolean(), is(true));
        assertThat(result.path("message").asText(), is("'times' must be an integer"));
    }

    @Test
    public void shouldVerifyRequestNotFound() {
        ObjectNode params = objectMapper.createObjectNode();
        params.put("method", "GET");
        params.put("path", "/not-called");
        params.put("atLeast", 1);

        JsonNode result = toolRegistry.callTool("verify_request", params);
        assertThat(result.path("verified").asBoolean(), is(false));
    }

    @Test
    public void shouldVerifyZeroRequests() {
        ObjectNode params = objectMapper.createObjectNode();
        params.put("method", "GET");
        params.put("path", "/not-called");
        params.put("atLeast", 0);
        params.put("atMost", 0);

        JsonNode result = toolRegistry.callTool("verify_request", params);
        assertThat(result.path("verified").asBoolean(), is(true));
    }

    @Test
    public void shouldRetrieveEmptyRequests() {
        ObjectNode params = objectMapper.createObjectNode();

        JsonNode result = toolRegistry.callTool("retrieve_recorded_requests", params);
        assertThat(result.path("total").asInt(), is(0));
        assertThat(result.path("requests").isArray(), is(true));
    }

    @Test
    public void shouldClearExpectations() {
        ObjectNode createParams = objectMapper.createObjectNode();
        createParams.put("method", "GET");
        createParams.put("path", "/to-clear");
        createParams.put("statusCode", 200);
        toolRegistry.callTool("create_expectation", createParams);

        ObjectNode clearParams = objectMapper.createObjectNode();
        clearParams.put("type", "ALL");

        JsonNode result = toolRegistry.callTool("clear_expectations", clearParams);
        assertThat(result.path("status").asText(), is("cleared"));
    }

    @Test
    public void shouldReset() {
        JsonNode result = toolRegistry.callTool("reset", objectMapper.createObjectNode());
        assertThat(result.path("status").asText(), is("reset"));
    }

    @Test
    public void shouldGetStatus() {
        JsonNode result = toolRegistry.callTool("get_status", objectMapper.createObjectNode());
        assertThat(result.path("running").asBoolean(), is(true));
        assertThat(result.path("ports").get(0).asInt(), is(1080));
    }

    @Test
    public void shouldManageServiceChaos() {
        try {
            // register
            ObjectNode chaos = objectMapper.createObjectNode();
            chaos.put("errorStatus", 503);
            chaos.put("errorProbability", 1.0);
            ObjectNode registerParams = objectMapper.createObjectNode();
            registerParams.put("action", "register");
            registerParams.put("host", "payments.svc");
            registerParams.set("chaos", chaos);
            registerParams.put("ttlMillis", 300000);
            JsonNode registered = toolRegistry.callTool("manage_service_chaos", registerParams);
            assertThat(registered.path("status").asText(), is("registered"));
            assertThat(registered.path("ttlMillis").asLong(), is(300000L));
            assertThat(org.mockserver.mock.action.http.ServiceChaosRegistry.getInstance().get("payments.svc"), is(notNullValue()));

            // remove
            ObjectNode removeParams = objectMapper.createObjectNode();
            removeParams.put("action", "remove");
            removeParams.put("host", "payments.svc");
            JsonNode removed = toolRegistry.callTool("manage_service_chaos", removeParams);
            assertThat(removed.path("status").asText(), is("removed"));
            assertThat(org.mockserver.mock.action.http.ServiceChaosRegistry.getInstance().get("payments.svc"), is(nullValue()));
        } finally {
            org.mockserver.mock.action.http.ServiceChaosRegistry.getInstance().reset();
        }
    }

    @Test
    public void shouldRejectManageServiceChaosWithInvalidProfile() {
        ObjectNode chaos = objectMapper.createObjectNode();
        chaos.put("errorStatus", 999); // out of range
        ObjectNode params = objectMapper.createObjectNode();
        params.put("action", "register");
        params.put("host", "payments.svc");
        params.set("chaos", chaos);
        JsonNode result = toolRegistry.callTool("manage_service_chaos", params);
        assertThat(result.path("error").asBoolean(), is(true));
        assertThat(result.path("message").asText(), containsString("invalid chaos profile"));
        assertThat(org.mockserver.mock.action.http.ServiceChaosRegistry.getInstance().get("payments.svc"), is(nullValue()));
    }

    @Test
    public void shouldVerifyRequestSequence() {
        ObjectNode params = objectMapper.createObjectNode();
        params.putArray("requests");

        JsonNode result = toolRegistry.callTool("verify_request_sequence", params);
        assertThat(result.path("error").asBoolean(), is(true));
    }

    @Test
    public void shouldCreateForwardExpectation() {
        ObjectNode params = objectMapper.createObjectNode();
        params.put("path", "/proxy");
        params.put("host", "example.com");
        params.put("port", 8080);
        params.put("scheme", "HTTP");

        JsonNode result = toolRegistry.callTool("create_forward_expectation", params);
        assertThat(result.path("status").asText(), is("created"));
        assertThat(result.path("forwardHost").asText(), is("example.com"));
    }

    @Test
    public void shouldDebugRequestMismatchWithNoExpectations() {
        ObjectNode params = objectMapper.createObjectNode();
        params.put("method", "GET");
        params.put("path", "/test");

        JsonNode result = toolRegistry.callTool("debug_request_mismatch", params);
        assertThat(result.path("totalExpectations").asInt(), is(0));
        assertThat(result.path("results").isArray(), is(true));
    }

    @Test
    public void shouldDebugRequestMismatchWithExpectation() {
        ObjectNode createParams = objectMapper.createObjectNode();
        createParams.put("method", "POST");
        createParams.put("path", "/expected");
        createParams.put("statusCode", 200);
        toolRegistry.callTool("create_expectation", createParams);

        ObjectNode debugParams = objectMapper.createObjectNode();
        debugParams.put("method", "GET");
        debugParams.put("path", "/wrong");

        JsonNode result = toolRegistry.callTool("debug_request_mismatch", debugParams);
        assertThat(result.path("totalExpectations").asInt(), is(1));
        assertThat(result.path("results").get(0).path("matches").asBoolean(), is(false));
        assertThat(result.path("results").get(0).has("differences"), is(true));
    }

    @Test
    public void shouldReturnNullForUnknownTool() {
        JsonNode result = toolRegistry.callTool("unknown_tool", objectMapper.createObjectNode());
        assertThat(result == null, is(true));
    }

    @Test
    public void shouldHandleRawRetrieve() {
        ObjectNode params = objectMapper.createObjectNode();
        params.put("type", "ACTIVE_EXPECTATIONS");
        params.put("format", "JSON");

        JsonNode result = toolRegistry.callTool("raw_retrieve", params);
        assertThat(result.has("data"), is(true));
    }

    @Test
    public void shouldRetrieveRequestResponses() {
        ObjectNode params = objectMapper.createObjectNode();

        JsonNode result = toolRegistry.callTool("retrieve_request_responses", params);
        assertThat(result.path("total").asInt(), is(0));
        assertThat(result.path("requestResponses").isArray(), is(true));
    }

    @Test
    public void shouldHandleRetrieveRecordedRequestsWithFilter() {
        ObjectNode params = objectMapper.createObjectNode();
        params.put("method", "GET");
        params.put("path", "/filtered");
        params.put("limit", 10);

        JsonNode result = toolRegistry.callTool("retrieve_recorded_requests", params);
        assertThat(result.path("total").asInt(), is(0));
    }

    @Test
    public void shouldRejectNegativeLimit() {
        ObjectNode params = objectMapper.createObjectNode();
        params.put("limit", -1);

        JsonNode result = toolRegistry.callTool("retrieve_recorded_requests", params);
        assertThat(result.path("error").asBoolean(), is(true));
        assertThat(result.path("message").asText(), is("'limit' must be between 1 and 500"));
    }

    @Test
    public void shouldRejectZeroLimit() {
        ObjectNode params = objectMapper.createObjectNode();
        params.put("limit", 0);

        JsonNode result = toolRegistry.callTool("retrieve_recorded_requests", params);
        assertThat(result.path("error").asBoolean(), is(true));
        assertThat(result.path("message").asText(), is("'limit' must be between 1 and 500"));
    }

    @Test
    public void shouldRejectLimitAboveMaximum() {
        ObjectNode params = objectMapper.createObjectNode();
        params.put("limit", 501);

        JsonNode result = toolRegistry.callTool("retrieve_recorded_requests", params);
        assertThat(result.path("error").asBoolean(), is(true));
        assertThat(result.path("message").asText(), is("'limit' must be between 1 and 500"));
    }

    @Test
    public void shouldAcceptLimitAtMaximum() {
        ObjectNode params = objectMapper.createObjectNode();
        params.put("limit", 500);

        JsonNode result = toolRegistry.callTool("retrieve_recorded_requests", params);
        assertThat(result.path("total").asInt(), is(0));
        assertThat(result.path("requests").isArray(), is(true));
    }

    @Test
    public void shouldRejectNegativeLimitForRequestResponses() {
        ObjectNode params = objectMapper.createObjectNode();
        params.put("limit", -5);

        JsonNode result = toolRegistry.callTool("retrieve_request_responses", params);
        assertThat(result.path("error").asBoolean(), is(true));
        assertThat(result.path("message").asText(), is("'limit' must be between 1 and 500"));
    }

    @Test
    public void shouldRejectLimitAboveMaximumForRequestResponses() {
        ObjectNode params = objectMapper.createObjectNode();
        params.put("limit", 501);

        JsonNode result = toolRegistry.callTool("retrieve_request_responses", params);
        assertThat(result.path("error").asBoolean(), is(true));
        assertThat(result.path("message").asText(), is("'limit' must be between 1 and 500"));
    }

    @Test
    public void shouldRejectInvalidStatusCodeTooLow() {
        ObjectNode params = objectMapper.createObjectNode();
        params.put("method", "GET");
        params.put("path", "/bad-status");
        params.put("statusCode", 99);

        JsonNode result = toolRegistry.callTool("create_expectation", params);
        assertThat(result.path("error").asBoolean(), is(true));
        assertThat(result.path("message").asText(), is("'statusCode' must be between 100 and 999"));
    }

    @Test
    public void shouldRejectInvalidStatusCodeTooHigh() {
        ObjectNode params = objectMapper.createObjectNode();
        params.put("method", "GET");
        params.put("path", "/bad-status");
        params.put("statusCode", 1000);

        JsonNode result = toolRegistry.callTool("create_expectation", params);
        assertThat(result.path("error").asBoolean(), is(true));
        assertThat(result.path("message").asText(), is("'statusCode' must be between 100 and 999"));
    }

    @Test
    public void shouldAcceptValidStatusCodeBoundary() {
        ObjectNode params = objectMapper.createObjectNode();
        params.put("method", "GET");
        params.put("path", "/good-status-100");
        params.put("statusCode", 100);

        JsonNode result = toolRegistry.callTool("create_expectation", params);
        assertThat(result.path("status").asText(), is("created"));

        ObjectNode params2 = objectMapper.createObjectNode();
        params2.put("method", "GET");
        params2.put("path", "/good-status-999");
        params2.put("statusCode", 999);

        JsonNode result2 = toolRegistry.callTool("create_expectation", params2);
        assertThat(result2.path("status").asText(), is("created"));
    }

    @Test
    public void shouldRejectMalformedTimeToLive() {
        ObjectNode params = objectMapper.createObjectNode();
        params.put("method", "GET");
        params.put("path", "/ttl-test");
        params.put("timeToLive", "INVALID");

        JsonNode result = toolRegistry.callTool("create_expectation", params);
        assertThat(result.path("error").asBoolean(), is(true));
        assertThat(result.path("message").asText(), is("'timeToLive' must be in format '<number> <UNIT>' (e.g., '60 SECONDS')"));
    }

    @Test
    public void shouldRejectFloatTimes() {
        ObjectNode params = objectMapper.createObjectNode();
        params.put("method", "GET");
        params.put("path", "/float-times");
        params.put("statusCode", 200);
        params.put("times", 3.5);

        JsonNode result = toolRegistry.callTool("create_expectation", params);
        assertThat(result.path("error").asBoolean(), is(true));
        assertThat(result.path("message").asText(), is("'times' must be an integer"));
    }

    @Test
    public void shouldHandleClearWithFilter() {
        ObjectNode createParams = objectMapper.createObjectNode();
        createParams.put("method", "GET");
        createParams.put("path", "/keep");
        createParams.put("statusCode", 200);
        toolRegistry.callTool("create_expectation", createParams);

        ObjectNode createParams2 = objectMapper.createObjectNode();
        createParams2.put("method", "POST");
        createParams2.put("path", "/remove");
        createParams2.put("statusCode", 201);
        toolRegistry.callTool("create_expectation", createParams2);

        ObjectNode clearParams = objectMapper.createObjectNode();
        clearParams.put("method", "POST");
        clearParams.put("path", "/remove");
        clearParams.put("type", "EXPECTATIONS");

        JsonNode result = toolRegistry.callTool("clear_expectations", clearParams);
        assertThat(result.path("status").asText(), is("cleared"));
    }

    @Test
    public void shouldRejectBlankMethod() {
        ObjectNode params = objectMapper.createObjectNode();
        params.put("method", "");
        params.put("path", "/test");

        JsonNode result = toolRegistry.callTool("create_expectation", params);
        assertThat(result.path("error").asBoolean(), is(true));
        assertThat(result.path("message").asText(), is("'method' is required and must not be blank"));
    }

    @Test
    public void shouldRejectMissingMethod() {
        ObjectNode params = objectMapper.createObjectNode();
        params.put("path", "/test");

        JsonNode result = toolRegistry.callTool("create_expectation", params);
        assertThat(result.path("error").asBoolean(), is(true));
        assertThat(result.path("message").asText(), is("'method' is required and must not be blank"));
    }

    @Test
    public void shouldRejectBlankPath() {
        ObjectNode params = objectMapper.createObjectNode();
        params.put("method", "GET");
        params.put("path", "   ");

        JsonNode result = toolRegistry.callTool("create_expectation", params);
        assertThat(result.path("error").asBoolean(), is(true));
        assertThat(result.path("message").asText(), is("'path' is required and must not be blank"));
    }

    @Test
    public void shouldRejectMissingPath() {
        ObjectNode params = objectMapper.createObjectNode();
        params.put("method", "GET");

        JsonNode result = toolRegistry.callTool("create_expectation", params);
        assertThat(result.path("error").asBoolean(), is(true));
        assertThat(result.path("message").asText(), is("'path' is required and must not be blank"));
    }

    @Test
    public void shouldRejectNegativeTimes() {
        ObjectNode params = objectMapper.createObjectNode();
        params.put("method", "GET");
        params.put("path", "/negative-times");
        params.put("statusCode", 200);
        params.put("times", -1);

        JsonNode result = toolRegistry.callTool("create_expectation", params);
        assertThat(result.path("error").asBoolean(), is(true));
        assertThat(result.path("message").asText(), is("'times' must be a non-negative integer"));
    }

    @Test
    public void shouldAcceptZeroTimes() {
        ObjectNode params = objectMapper.createObjectNode();
        params.put("method", "GET");
        params.put("path", "/zero-times");
        params.put("statusCode", 200);
        params.put("times", 0);

        JsonNode result = toolRegistry.callTool("create_expectation", params);
        assertThat(result.path("status").asText(), is("created"));
    }

    @Test
    public void shouldRejectNonNumericTimeToLiveValue() {
        ObjectNode params = objectMapper.createObjectNode();
        params.put("method", "GET");
        params.put("path", "/ttl-nan");
        params.put("timeToLive", "abc SECONDS");

        JsonNode result = toolRegistry.callTool("create_expectation", params);
        assertThat(result.path("error").asBoolean(), is(true));
        assertThat(result.path("message").asText(), is("'timeToLive' value must be a number"));
    }

    @Test
    public void shouldRejectNegativeTimeToLiveValue() {
        ObjectNode params = objectMapper.createObjectNode();
        params.put("method", "GET");
        params.put("path", "/ttl-negative");
        params.put("timeToLive", "-5 SECONDS");

        JsonNode result = toolRegistry.callTool("create_expectation", params);
        assertThat(result.path("error").asBoolean(), is(true));
        assertThat(result.path("message").asText(), is("'timeToLive' value must be positive"));
    }

    @Test
    public void shouldRejectZeroTimeToLiveValue() {
        ObjectNode params = objectMapper.createObjectNode();
        params.put("method", "GET");
        params.put("path", "/ttl-zero");
        params.put("timeToLive", "0 SECONDS");

        JsonNode result = toolRegistry.callTool("create_expectation", params);
        assertThat(result.path("error").asBoolean(), is(true));
        assertThat(result.path("message").asText(), is("'timeToLive' value must be positive"));
    }

    @Test
    public void shouldRejectInvalidTimeToLiveUnit() {
        ObjectNode params = objectMapper.createObjectNode();
        params.put("method", "GET");
        params.put("path", "/ttl-bad-unit");
        params.put("timeToLive", "60 FORTNIGHTS");

        JsonNode result = toolRegistry.callTool("create_expectation", params);
        assertThat(result.path("error").asBoolean(), is(true));
        assertThat(result.path("message").asText(), containsString("'timeToLive' unit must be one of:"));
    }

    @Test
    public void shouldAcceptValidTimeToLive() {
        ObjectNode params = objectMapper.createObjectNode();
        params.put("method", "GET");
        params.put("path", "/ttl-valid");
        params.put("timeToLive", "60 SECONDS");

        JsonNode result = toolRegistry.callTool("create_expectation", params);
        assertThat(result.path("status").asText(), is("created"));
    }

    @Test
    public void shouldRejectStringStatusCode() {
        ObjectNode params = objectMapper.createObjectNode();
        params.put("method", "GET");
        params.put("path", "/string-status");
        params.put("statusCode", "abc");

        JsonNode result = toolRegistry.callTool("create_expectation", params);
        assertThat(result.path("error").asBoolean(), is(true));
        assertThat(result.path("message").asText(), is("'statusCode' must be an integer"));
    }

    @Test
    public void shouldNotExposeExceptionDetailsInErrorMessages() {
        // raw_expectation with invalid JSON should say what shape is expected, not expose internals
        ObjectNode params = objectMapper.createObjectNode();
        params.put("expectation", "not valid json");

        JsonNode result = toolRegistry.callTool("raw_expectation", params);
        assertThat(result.path("error").asBoolean(), is(true));
        String message = result.path("message").asText();
        assertThat(message, startsWith("'expectation' must be a JSON object in MockServer expectation format"));
        // Should NOT contain any Java exception class names or stack trace details
        assertThat(message, not(containsString("Exception")));
        assertThat(message, not(containsString("at org.")));
    }

    // --- verify_request atLeast/atMost validation ---

    @Test
    public void shouldRejectNonIntegerAtLeast() {
        ObjectNode params = objectMapper.createObjectNode();
        params.put("method", "GET");
        params.put("path", "/test");
        params.put("atLeast", "abc");

        JsonNode result = toolRegistry.callTool("verify_request", params);
        assertThat(result.path("error").asBoolean(), is(true));
        assertThat(result.path("message").asText(), is("'atLeast' must be an integer"));
    }

    @Test
    public void shouldRejectNonIntegerAtMost() {
        ObjectNode params = objectMapper.createObjectNode();
        params.put("method", "GET");
        params.put("path", "/test");
        params.put("atMost", 3.5);

        JsonNode result = toolRegistry.callTool("verify_request", params);
        assertThat(result.path("error").asBoolean(), is(true));
        assertThat(result.path("message").asText(), is("'atMost' must be an integer"));
    }

    @Test
    public void shouldRejectNegativeAtLeast() {
        ObjectNode params = objectMapper.createObjectNode();
        params.put("method", "GET");
        params.put("path", "/test");
        params.put("atLeast", -1);

        JsonNode result = toolRegistry.callTool("verify_request", params);
        assertThat(result.path("error").asBoolean(), is(true));
        assertThat(result.path("message").asText(), is("'atLeast' must be non-negative"));
    }

    @Test
    public void shouldRejectAtMostLessThanAtLeast() {
        ObjectNode params = objectMapper.createObjectNode();
        params.put("method", "GET");
        params.put("path", "/test");
        params.put("atLeast", 5);
        params.put("atMost", 2);

        JsonNode result = toolRegistry.callTool("verify_request", params);
        assertThat(result.path("error").asBoolean(), is(true));
        assertThat(result.path("message").asText(), is("'atMost' must be >= 'atLeast'"));
    }

    // --- clear_expectations type validation ---

    @Test
    public void shouldRejectInvalidClearType() {
        ObjectNode params = objectMapper.createObjectNode();
        params.put("type", "INVALID");

        JsonNode result = toolRegistry.callTool("clear_expectations", params);
        assertThat(result.path("error").asBoolean(), is(true));
        assertThat(result.path("message").asText(), is("'type' must be one of: ALL, LOG, EXPECTATIONS"));
    }

    @Test
    public void shouldAcceptValidClearTypes() {
        for (String type : new String[]{"ALL", "LOG", "EXPECTATIONS"}) {
            ObjectNode params = objectMapper.createObjectNode();
            params.put("type", type);

            JsonNode result = toolRegistry.callTool("clear_expectations", params);
            assertThat("type " + type + " should be accepted", result.path("status").asText(), is("cleared"));
        }
    }

    // --- create_forward_expectation validation ---

    @Test
    public void shouldRejectBlankPathForForwardExpectation() {
        ObjectNode params = objectMapper.createObjectNode();
        params.put("path", "");
        params.put("host", "example.com");

        JsonNode result = toolRegistry.callTool("create_forward_expectation", params);
        assertThat(result.path("error").asBoolean(), is(true));
        assertThat(result.path("message").asText(), is("'path' is required and must not be blank"));
    }

    @Test
    public void shouldRejectBlankHostForForwardExpectation() {
        ObjectNode params = objectMapper.createObjectNode();
        params.put("path", "/proxy");
        params.put("host", "  ");

        JsonNode result = toolRegistry.callTool("create_forward_expectation", params);
        assertThat(result.path("error").asBoolean(), is(true));
        assertThat(result.path("message").asText(), is("'host' is required and must not be blank"));
    }

    @Test
    public void shouldRejectMissingHostForForwardExpectation() {
        ObjectNode params = objectMapper.createObjectNode();
        params.put("path", "/proxy");

        JsonNode result = toolRegistry.callTool("create_forward_expectation", params);
        assertThat(result.path("error").asBoolean(), is(true));
        assertThat(result.path("message").asText(), is("'host' is required and must not be blank"));
    }

    @Test
    public void shouldRejectNonIntegerPortForForwardExpectation() {
        ObjectNode params = objectMapper.createObjectNode();
        params.put("path", "/proxy");
        params.put("host", "example.com");
        params.put("port", "abc");

        JsonNode result = toolRegistry.callTool("create_forward_expectation", params);
        assertThat(result.path("error").asBoolean(), is(true));
        assertThat(result.path("message").asText(), is("'port' must be an integer"));
    }

    @Test
    public void shouldRejectPortZeroForForwardExpectation() {
        ObjectNode params = objectMapper.createObjectNode();
        params.put("path", "/proxy");
        params.put("host", "example.com");
        params.put("port", 0);

        JsonNode result = toolRegistry.callTool("create_forward_expectation", params);
        assertThat(result.path("error").asBoolean(), is(true));
        assertThat(result.path("message").asText(), is("'port' must be between 1 and 65535"));
    }

    @Test
    public void shouldRejectPortAboveMax() {
        ObjectNode params = objectMapper.createObjectNode();
        params.put("path", "/proxy");
        params.put("host", "example.com");
        params.put("port", 65536);

        JsonNode result = toolRegistry.callTool("create_forward_expectation", params);
        assertThat(result.path("error").asBoolean(), is(true));
        assertThat(result.path("message").asText(), is("'port' must be between 1 and 65535"));
    }

    @Test
    public void shouldAcceptValidPortBoundaries() {
        ObjectNode params1 = objectMapper.createObjectNode();
        params1.put("path", "/proxy1");
        params1.put("host", "example.com");
        params1.put("port", 1);

        JsonNode result1 = toolRegistry.callTool("create_forward_expectation", params1);
        assertThat(result1.path("status").asText(), is("created"));
        assertThat(result1.path("forwardPort").asInt(), is(1));

        ObjectNode params2 = objectMapper.createObjectNode();
        params2.put("path", "/proxy2");
        params2.put("host", "example.com");
        params2.put("port", 65535);

        JsonNode result2 = toolRegistry.callTool("create_forward_expectation", params2);
        assertThat(result2.path("status").asText(), is("created"));
        assertThat(result2.path("forwardPort").asInt(), is(65535));
    }

    @Test
    public void shouldRejectInvalidSchemeForForwardExpectation() {
        ObjectNode params = objectMapper.createObjectNode();
        params.put("path", "/proxy");
        params.put("host", "example.com");
        params.put("scheme", "FTP");

        JsonNode result = toolRegistry.callTool("create_forward_expectation", params);
        assertThat(result.path("error").asBoolean(), is(true));
        assertThat(result.path("message").asText(), is("'scheme' must be HTTP or HTTPS"));
    }

    // --- raw_retrieve type/format validation ---

    @Test
    public void shouldRejectInvalidRawRetrieveType() {
        ObjectNode params = objectMapper.createObjectNode();
        params.put("type", "INVALID");

        JsonNode result = toolRegistry.callTool("raw_retrieve", params);
        assertThat(result.path("error").asBoolean(), is(true));
        assertThat(result.path("message").asText(), is("'type' must be one of: REQUESTS, REQUEST_RESPONSES, RECORDED_EXPECTATIONS, ACTIVE_EXPECTATIONS, LOGS"));
    }

    @Test
    public void shouldRejectInvalidRawRetrieveFormat() {
        ObjectNode params = objectMapper.createObjectNode();
        params.put("type", "REQUESTS");
        params.put("format", "XML");

        JsonNode result = toolRegistry.callTool("raw_retrieve", params);
        assertThat(result.path("error").asBoolean(), is(true));
        assertThat(result.path("message").asText(), is("'format' must be one of: JSON, JAVA, LOG_ENTRIES"));
    }

    @Test
    public void shouldAcceptAllValidRawRetrieveTypes() {
        for (String type : new String[]{"REQUESTS", "REQUEST_RESPONSES", "RECORDED_EXPECTATIONS", "ACTIVE_EXPECTATIONS", "LOGS"}) {
            ObjectNode params = objectMapper.createObjectNode();
            params.put("type", type);
            params.put("format", "JSON");

            JsonNode result = toolRegistry.callTool("raw_retrieve", params);
            assertThat("type " + type + " should be accepted", result.has("data"), is(true));
        }
    }

    // --- verify_request_sequence element validation ---

    @Test
    public void shouldRejectNonObjectElementsInRequestSequence() {
        ObjectNode params = objectMapper.createObjectNode();
        params.putArray("requests").add("not-an-object");

        JsonNode result = toolRegistry.callTool("verify_request_sequence", params);
        assertThat(result.path("error").asBoolean(), is(true));
        assertThat(result.path("message").asText(), is("Each element of 'requests' must be an object"));
    }

    // --- create_expectation timeToLive non-textual ---

    @Test
    public void shouldRejectNonTextualTimeToLive() {
        ObjectNode params = objectMapper.createObjectNode();
        params.put("method", "GET");
        params.put("path", "/ttl-int");
        params.put("timeToLive", 60);

        JsonNode result = toolRegistry.callTool("create_expectation", params);
        assertThat(result.path("error").asBoolean(), is(true));
        assertThat(result.path("message").asText(), is("'timeToLive' must be a string in format '<number> <UNIT>' (e.g., '60 SECONDS')"));
    }

    @Test
    public void shouldRejectBooleanTimeToLive() {
        ObjectNode params = objectMapper.createObjectNode();
        params.put("method", "GET");
        params.put("path", "/ttl-bool");
        params.put("timeToLive", true);

        JsonNode result = toolRegistry.callTool("create_expectation", params);
        assertThat(result.path("error").asBoolean(), is(true));
        assertThat(result.path("message").asText(), is("'timeToLive' must be a string in format '<number> <UNIT>' (e.g., '60 SECONDS')"));
    }

    // --- explain_unmatched_requests tool tests ---

    @Test
    public void shouldExplainUnmatchedRequestsWithNoHistory() {
        // given
        ObjectNode params = objectMapper.createObjectNode();

        // when
        JsonNode result = toolRegistry.callTool("explain_unmatched_requests", params);

        // then
        assertThat(result.path("unmatchedRequestCount").asInt(), is(0));
        assertThat(result.path("unmatchedRequests").isArray(), is(true));
    }

    @Test
    public void shouldExplainUnmatchedRequestsWithLimit() {
        // given
        ObjectNode params = objectMapper.createObjectNode();
        params.put("limit", 5);

        // when
        JsonNode result = toolRegistry.callTool("explain_unmatched_requests", params);

        // then
        assertThat(result.has("unmatchedRequestCount"), is(true));
        assertThat(result.path("unmatchedRequests").isArray(), is(true));
    }

    @Test
    public void shouldRejectInvalidLimitForExplainUnmatched() {
        // given
        ObjectNode params = objectMapper.createObjectNode();
        params.put("limit", 0);

        // when
        JsonNode result = toolRegistry.callTool("explain_unmatched_requests", params);

        // then
        assertThat(result.path("error").asBoolean(), is(true));
        assertThat(result.path("message").asText(), is("'limit' must be between 1 and 100"));
    }

    @Test
    public void shouldRejectLimitAboveMaxForExplainUnmatched() {
        // given
        ObjectNode params = objectMapper.createObjectNode();
        params.put("limit", 101);

        // when
        JsonNode result = toolRegistry.callTool("explain_unmatched_requests", params);

        // then
        assertThat(result.path("error").asBoolean(), is(true));
        assertThat(result.path("message").asText(), is("'limit' must be between 1 and 100"));
    }

    @Test
    public void shouldExplainUnmatchedRequestsToolHasSchema() {
        // given
        McpToolRegistry.ToolDefinition tool = toolRegistry.getTools().get("explain_unmatched_requests");

        // then
        assertThat(tool, notNullValue());
        assertThat(tool.getDescription(), containsString("matched no expectation"));
        assertThat(tool.getInputSchema().path("properties").has("limit"), is(true));
    }

    // --- debug_request_mismatch ranking and remediation tests ---

    @Test
    public void shouldDebugRequestMismatchWithRankedResultsAndRemediation() {
        // given - create two expectations with different paths
        ObjectNode createParams1 = objectMapper.createObjectNode();
        createParams1.put("method", "GET");
        createParams1.put("path", "/close-match");
        createParams1.put("statusCode", 200);
        toolRegistry.callTool("create_expectation", createParams1);

        ObjectNode createParams2 = objectMapper.createObjectNode();
        createParams2.put("method", "POST");
        createParams2.put("path", "/far-match");
        createParams2.put("statusCode", 201);
        toolRegistry.callTool("create_expectation", createParams2);

        // when - debug with a request that matches method of first but not path
        ObjectNode debugParams = objectMapper.createObjectNode();
        debugParams.put("method", "GET");
        debugParams.put("path", "/wrong-path");

        JsonNode result = toolRegistry.callTool("debug_request_mismatch", debugParams);

        // then
        assertThat(result.path("totalExpectations").asInt(), is(2));
        assertThat(result.path("results").isArray(), is(true));
        // results should be ranked - first result should have more matched fields
        JsonNode firstResult = result.path("results").get(0);
        JsonNode secondResult = result.path("results").get(1);
        int firstDiffering = firstResult.path("totalFieldCount").asInt() - firstResult.path("matchedFieldCount").asInt();
        int secondDiffering = secondResult.path("totalFieldCount").asInt() - secondResult.path("matchedFieldCount").asInt();
        assertThat("results should be ranked with closest match first", firstDiffering <= secondDiffering, is(true));
    }

    // --- create_expectations_from_recorded_traffic tool tests ---

    @Test
    public void shouldCreateExpectationsFromRecordedTrafficToolHasSchema() {
        // given
        McpToolRegistry.ToolDefinition tool = toolRegistry.getTools().get("create_expectations_from_recorded_traffic");

        // then
        assertThat(tool, notNullValue());
        assertThat(tool.getDescription(), containsString("recorded"));
        assertThat(tool.getDescription(), containsString("forwarding"));
        assertThat(tool.getInputSchema().path("properties").has("method"), is(true));
        assertThat(tool.getInputSchema().path("properties").has("path"), is(true));
        assertThat(tool.getInputSchema().path("properties").has("preview"), is(true));
    }

    @Test
    public void shouldReturnNoRecordedTrafficWhenNoneExists() {
        // given
        ObjectNode params = objectMapper.createObjectNode();

        // when
        JsonNode result = toolRegistry.callTool("create_expectations_from_recorded_traffic", params);

        // then
        assertThat(result.path("status").asText(), is("no_recorded_traffic"));
        assertThat(result.path("count").asInt(), is(0));
    }

    @Test
    public void shouldPreviewRecordedExpectationsWithoutAdding() throws Exception {
        // given - simulate FORWARDED_REQUEST log entries (what proxy mode creates)
        httpState.log(new LogEntry()
            .setType(FORWARDED_REQUEST)
            .setLogLevel(org.slf4j.event.Level.INFO)
            .setHttpRequest(request().withMethod("GET").withPath("/api/users"))
            .setHttpResponse(response().withStatusCode(200).withBody("[{\"id\":1}]"))
            .setExpectation(request().withMethod("GET").withPath("/api/users"),
                response().withStatusCode(200).withBody("[{\"id\":1}]"))
            .setMessageFormat("returning response:{}for forwarded request")
            .setArguments(response().withStatusCode(200).withBody("[{\"id\":1}]"))
        );

        Thread.sleep(500);

        // when - call with preview=true
        ObjectNode params = objectMapper.createObjectNode();
        params.put("preview", true);
        JsonNode result = toolRegistry.callTool("create_expectations_from_recorded_traffic", params);

        // then
        assertThat(result.path("status").asText(), is("preview"));
        assertThat(result.path("count").asInt() >= 1, is(true));
        assertThat(result.path("expectations").isArray(), is(true));
        assertThat(result.path("expectations").size() >= 1, is(true));

        // verify no active expectations were added (only the forwarding, not from this tool)
        ObjectNode retrieveParams = objectMapper.createObjectNode();
        retrieveParams.put("type", "ACTIVE_EXPECTATIONS");
        retrieveParams.put("format", "JSON");
        JsonNode activeResult = toolRegistry.callTool("raw_retrieve", retrieveParams);
        // should have no active expectations (the recorded ones were just previewed)
        String data = activeResult.path("data").toString();
        assertThat("no active expectations should be added in preview mode",
            data.equals("\"\"") || data.equals("[]"), is(true));
    }

    @Test
    public void shouldCreateExpectationsFromRecordedTraffic() throws Exception {
        // given - simulate FORWARDED_REQUEST log entries
        httpState.log(new LogEntry()
            .setType(FORWARDED_REQUEST)
            .setLogLevel(org.slf4j.event.Level.INFO)
            .setHttpRequest(request().withMethod("POST").withPath("/api/orders"))
            .setHttpResponse(response().withStatusCode(201).withBody("{\"orderId\":42}"))
            .setExpectation(request().withMethod("POST").withPath("/api/orders"),
                response().withStatusCode(201).withBody("{\"orderId\":42}"))
            .setMessageFormat("returning response:{}for forwarded request")
            .setArguments(response().withStatusCode(201).withBody("{\"orderId\":42}"))
        );

        Thread.sleep(500);

        // when - call with preview=false (default)
        ObjectNode params = objectMapper.createObjectNode();
        JsonNode result = toolRegistry.callTool("create_expectations_from_recorded_traffic", params);

        // then
        assertThat(result.path("status").asText(), is("created"));
        assertThat(result.path("count").asInt() >= 1, is(true));
        assertThat(result.path("ids").isArray(), is(true));
        assertThat(result.path("ids").size() >= 1, is(true));

        // verify active expectations now exist
        ObjectNode retrieveParams = objectMapper.createObjectNode();
        retrieveParams.put("type", "ACTIVE_EXPECTATIONS");
        retrieveParams.put("format", "JSON");
        JsonNode activeResult = toolRegistry.callTool("raw_retrieve", retrieveParams);
        assertThat(activeResult.path("data").isArray(), is(true));
        assertThat(activeResult.path("data").size() >= 1, is(true));
    }

    @Test
    public void shouldFilterRecordedTrafficByMethod() throws Exception {
        // given - simulate two FORWARDED_REQUEST log entries with different methods
        httpState.log(new LogEntry()
            .setType(FORWARDED_REQUEST)
            .setLogLevel(org.slf4j.event.Level.INFO)
            .setHttpRequest(request().withMethod("GET").withPath("/api/items"))
            .setHttpResponse(response().withStatusCode(200).withBody("[]"))
            .setExpectation(request().withMethod("GET").withPath("/api/items"),
                response().withStatusCode(200).withBody("[]"))
            .setMessageFormat("returning response:{}for forwarded request")
            .setArguments(response().withStatusCode(200).withBody("[]"))
        );
        httpState.log(new LogEntry()
            .setType(FORWARDED_REQUEST)
            .setLogLevel(org.slf4j.event.Level.INFO)
            .setHttpRequest(request().withMethod("DELETE").withPath("/api/items/1"))
            .setHttpResponse(response().withStatusCode(204))
            .setExpectation(request().withMethod("DELETE").withPath("/api/items/1"),
                response().withStatusCode(204))
            .setMessageFormat("returning response:{}for forwarded request")
            .setArguments(response().withStatusCode(204))
        );

        Thread.sleep(500);

        // when - filter by GET method only
        ObjectNode params = objectMapper.createObjectNode();
        params.put("method", "GET");
        params.put("preview", true);
        JsonNode result = toolRegistry.callTool("create_expectations_from_recorded_traffic", params);

        // then - should only return GET expectations
        assertThat(result.path("status").asText(), is("preview"));
        assertThat(result.path("count").asInt(), is(1));
    }

    // --- verify_traffic_against_openapi tool tests ---

    @Test
    public void shouldVerifyTrafficAgainstOpenApiToolHasSchema() {
        // given
        McpToolRegistry.ToolDefinition tool = toolRegistry.getTools().get("verify_traffic_against_openapi");

        // then
        assertThat(tool, notNullValue());
        assertThat(tool.getDescription(), containsString("recorded by MockServer"));
        assertThat(tool.getInputSchema().path("properties").has("specUrlOrPayload"), is(true));
        assertThat(tool.getInputSchema().path("properties").has("method"), is(true));
        assertThat(tool.getInputSchema().path("properties").has("path"), is(true));
    }

    @Test
    public void shouldRejectBlankSpecForVerifyTraffic() {
        ObjectNode params = objectMapper.createObjectNode();
        params.put("specUrlOrPayload", "");

        JsonNode result = toolRegistry.callTool("verify_traffic_against_openapi", params);
        assertThat(result.path("error").asBoolean(), is(true));
        assertThat(result.path("message").asText(), is("'specUrlOrPayload' is required and must not be blank"));
    }

    @Test
    public void shouldReturnNoTrafficWhenNoneExists() {
        ObjectNode params = objectMapper.createObjectNode();
        params.put("specUrlOrPayload", "org/mockserver/openapi/openapi_simple_example.json");

        JsonNode result = toolRegistry.callTool("verify_traffic_against_openapi", params);
        assertThat(result.path("status").asText(), is("no_traffic"));
        assertThat(result.path("totalPairs").asInt(), is(0));
    }

    // --- run_contract_test tool tests ---

    @Test
    public void shouldRunContractTestToolHasSchema() {
        // given
        McpToolRegistry.ToolDefinition tool = toolRegistry.getTools().get("run_contract_test");

        // then
        assertThat(tool, notNullValue());
        assertThat(tool.getDescription(), containsString("example requests"));
        assertThat(tool.getDescription(), containsString("OpenAPI"));
        assertThat(tool.getInputSchema().path("properties").has("specUrlOrPayload"), is(true));
        assertThat(tool.getInputSchema().path("properties").has("baseUrl"), is(true));
        assertThat(tool.getInputSchema().path("properties").has("operationId"), is(true));
    }

    @Test
    public void shouldRejectBlankSpecForContractTest() {
        ObjectNode params = objectMapper.createObjectNode();
        params.put("specUrlOrPayload", "");
        params.put("baseUrl", "http://localhost:8080");

        JsonNode result = toolRegistry.callTool("run_contract_test", params);
        assertThat(result.path("error").asBoolean(), is(true));
        assertThat(result.path("message").asText(), is("'specUrlOrPayload' is required and must not be blank"));
    }

    @Test
    public void shouldRejectBlankBaseUrlForContractTest() {
        ObjectNode params = objectMapper.createObjectNode();
        params.put("specUrlOrPayload", "org/mockserver/openapi/openapi_simple_example.json");
        params.put("baseUrl", "");

        JsonNode result = toolRegistry.callTool("run_contract_test", params);
        assertThat(result.path("error").asBoolean(), is(true));
        assertThat(result.path("message").asText(), is("'baseUrl' is required and must not be blank"));
    }

    @Test
    public void shouldRejectInvalidBaseUrlForContractTest() {
        ObjectNode params = objectMapper.createObjectNode();
        params.put("specUrlOrPayload", "org/mockserver/openapi/openapi_simple_example.json");
        params.put("baseUrl", "not a url");

        JsonNode result = toolRegistry.callTool("run_contract_test", params);
        assertThat(result.path("error").asBoolean(), is(true));
        assertThat(result.path("message").asText(), containsString("'baseUrl' is not a valid URL"));
    }

    // --- run_resiliency_test tool tests ---

    @Test
    public void shouldRunResiliencyTestToolHasSchema() {
        // given
        McpToolRegistry.ToolDefinition tool = toolRegistry.getTools().get("run_resiliency_test");

        // then
        assertThat(tool, notNullValue());
        assertThat(tool.getDescription(), containsString("malformed"));
        assertThat(tool.getDescription(), containsString("boundary"));
        assertThat(tool.getDescription(), containsString("OpenAPI"));
        assertThat(tool.getInputSchema().path("properties").has("specUrlOrPayload"), is(true));
        assertThat(tool.getInputSchema().path("properties").has("baseUrl"), is(true));
        assertThat(tool.getInputSchema().path("properties").has("operationId"), is(true));
    }

    @Test
    public void shouldRejectBlankSpecForResiliencyTest() {
        ObjectNode params = objectMapper.createObjectNode();
        params.put("specUrlOrPayload", "");
        params.put("baseUrl", "http://localhost:8080");

        JsonNode result = toolRegistry.callTool("run_resiliency_test", params);
        assertThat(result.path("error").asBoolean(), is(true));
        assertThat(result.path("message").asText(), is("'specUrlOrPayload' is required and must not be blank"));
    }

    @Test
    public void shouldRejectBlankBaseUrlForResiliencyTest() {
        ObjectNode params = objectMapper.createObjectNode();
        params.put("specUrlOrPayload", "org/mockserver/openapi/openapi_simple_example.json");
        params.put("baseUrl", "");

        JsonNode result = toolRegistry.callTool("run_resiliency_test", params);
        assertThat(result.path("error").asBoolean(), is(true));
        assertThat(result.path("message").asText(), is("'baseUrl' is required and must not be blank"));
    }

    @Test
    public void shouldRejectInvalidBaseUrlForResiliencyTest() {
        ObjectNode params = objectMapper.createObjectNode();
        params.put("specUrlOrPayload", "org/mockserver/openapi/openapi_simple_example.json");
        params.put("baseUrl", "not a url");

        JsonNode result = toolRegistry.callTool("run_resiliency_test", params);
        assertThat(result.path("error").asBoolean(), is(true));
        assertThat(result.path("message").asText(), containsString("'baseUrl' is not a valid URL"));
    }

    // --- record_llm_fixtures tool tests ---

    @Test
    public void shouldRecordLlmFixturesToolHasSchema() {
        // given
        McpToolRegistry.ToolDefinition tool = toolRegistry.getTools().get("record_llm_fixtures");

        // then
        assertThat(tool, notNullValue());
        assertThat(tool.getDescription(), containsString("fixture"));
        assertThat(tool.getDescription(), containsString("redacted"));
        assertThat(tool.getInputSchema().path("properties").has("path"), is(true));
        assertThat(tool.getInputSchema().path("properties").has("host"), is(true));
        assertThat(tool.getInputSchema().path("properties").has("requestPath"), is(true));
        assertThat(tool.getInputSchema().path("properties").path("keepSseComments").path("type").asText(), is("boolean"));
    }

    @Test
    public void shouldRejectBlankPathForRecordLlmFixtures() {
        ObjectNode params = objectMapper.createObjectNode();
        params.put("path", "");

        JsonNode result = toolRegistry.callTool("record_llm_fixtures", params);
        assertThat(result.path("error").asBoolean(), is(true));
        assertThat(result.path("message").asText(), is("'path' is required and must not be blank"));
    }

    @Test
    public void shouldReturnNoRecordedTrafficForRecordLlmFixtures() {
        ObjectNode params = objectMapper.createObjectNode();
        params.put("path", System.getProperty("java.io.tmpdir") + "/test-fixture.json");

        JsonNode result = toolRegistry.callTool("record_llm_fixtures", params);
        assertThat(result.path("status").asText(), is("no_recorded_traffic"));
        assertThat(result.path("count").asInt(), is(0));
    }

    // --- load_expectations_from_file tool tests ---

    @Test
    public void shouldLoadExpectationsFromFileToolHasSchema() {
        // given
        McpToolRegistry.ToolDefinition tool = toolRegistry.getTools().get("load_expectations_from_file");

        // then
        assertThat(tool, notNullValue());
        assertThat(tool.getDescription(), containsString("fixture file"));
        assertThat(tool.getDescription(), containsString("replay"));
        assertThat(tool.getInputSchema().path("properties").has("path"), is(true));
    }

    @Test
    public void shouldRejectBlankPathForLoadExpectationsFromFile() {
        ObjectNode params = objectMapper.createObjectNode();
        params.put("path", "");

        JsonNode result = toolRegistry.callTool("load_expectations_from_file", params);
        assertThat(result.path("error").asBoolean(), is(true));
        assertThat(result.path("message").asText(), is("'path' is required and must not be blank"));
    }

    @Test
    public void shouldRejectNonExistentFileForLoad() {
        ObjectNode params = objectMapper.createObjectNode();
        params.put("path", System.getProperty("java.io.tmpdir") + "/nonexistent-fixture-12345.json");

        JsonNode result = toolRegistry.callTool("load_expectations_from_file", params);
        assertThat(result.path("error").asBoolean(), is(true));
        assertThat(result.path("message").asText(), containsString("does not exist"));
    }

    @Test
    public void shouldFilterRecordedTrafficByPath() throws Exception {
        // given
        httpState.log(new LogEntry()
            .setType(FORWARDED_REQUEST)
            .setLogLevel(org.slf4j.event.Level.INFO)
            .setHttpRequest(request().withMethod("GET").withPath("/api/users"))
            .setHttpResponse(response().withStatusCode(200).withBody("[]"))
            .setExpectation(request().withMethod("GET").withPath("/api/users"),
                response().withStatusCode(200).withBody("[]"))
            .setMessageFormat("returning response:{}for forwarded request")
            .setArguments(response().withStatusCode(200).withBody("[]"))
        );
        httpState.log(new LogEntry()
            .setType(FORWARDED_REQUEST)
            .setLogLevel(org.slf4j.event.Level.INFO)
            .setHttpRequest(request().withMethod("GET").withPath("/api/products"))
            .setHttpResponse(response().withStatusCode(200).withBody("[]"))
            .setExpectation(request().withMethod("GET").withPath("/api/products"),
                response().withStatusCode(200).withBody("[]"))
            .setMessageFormat("returning response:{}for forwarded request")
            .setArguments(response().withStatusCode(200).withBody("[]"))
        );

        Thread.sleep(500);

        // when - filter by path
        ObjectNode params = objectMapper.createObjectNode();
        params.put("path", "/api/users");
        params.put("preview", true);
        JsonNode result = toolRegistry.callTool("create_expectations_from_recorded_traffic", params);

        // then - should only return /api/users expectations
        assertThat(result.path("status").asText(), is("preview"));
        assertThat(result.path("count").asInt(), is(1));
    }

    // --- cassette auto-registration (gap #73) ---

    @Test
    public void shouldAutoRegisterCassetteWhenExpectationsLoadedFromFile() throws Exception {
        // given - a fixture file of two expectations inside the allowed temp directory
        java.nio.file.Path fixture = java.nio.file.Files.createTempFile("mcp-load-cassette-", ".json");
        java.nio.file.Files.write(fixture, ("["
            + "{\"httpRequest\":{\"path\":\"/replay/one\"},\"httpResponse\":{\"statusCode\":200,\"body\":\"one\"}},"
            + "{\"httpRequest\":{\"path\":\"/replay/two\"},\"httpResponse\":{\"statusCode\":200,\"body\":\"two\"}}"
            + "]").getBytes(java.nio.charset.StandardCharsets.UTF_8));
        try {
            ObjectNode params = objectMapper.createObjectNode();
            params.put("path", fixture.toString());

            // when - the file is loaded via the MCP tool the codebase uses to trigger a file load
            JsonNode result = toolRegistry.callTool("load_expectations_from_file", params);
            assertThat(result.path("status").asText(), is("loaded"));
            assertThat(result.path("count").asInt(), is(2));

            // then - the loaded fixture is now discoverable as a cassette (GET /mockserver/cassettes
            // serialises exactly this registry) with the correct count and a "loaded" origin
            java.util.List<org.mockserver.mock.CassetteRegistry.Entry> cassettes =
                org.mockserver.mock.CassetteRegistry.getInstance().list();
            assertThat(cassettes.size(), is(1));
            org.mockserver.mock.CassetteRegistry.Entry entry = cassettes.get(0);
            assertThat(entry.path, is(fixture.toAbsolutePath().normalize().toString()));
            assertThat(entry.expectationCount, is(2));
            assertThat(entry.origin, is("loaded"));
        } finally {
            java.nio.file.Files.deleteIfExists(fixture);
        }
    }

    @Test
    public void shouldAutoRegisterCassetteWhenFixturesRecordedToFile() throws Exception {
        // given - recorded/forwarded traffic that record_llm_fixtures can persist
        httpState.log(new LogEntry()
            .setType(FORWARDED_REQUEST)
            .setLogLevel(org.slf4j.event.Level.INFO)
            .setHttpRequest(request().withMethod("GET").withPath("/api/thing"))
            .setHttpResponse(response().withStatusCode(200).withBody("[]"))
            .setExpectation(request().withMethod("GET").withPath("/api/thing"),
                response().withStatusCode(200).withBody("[]"))
            .setMessageFormat("returning response:{}for forwarded request")
            .setArguments(response().withStatusCode(200).withBody("[]"))
        );
        Thread.sleep(500);

        java.nio.file.Path fixture = java.nio.file.Files.createTempFile("mcp-record-cassette-", ".json");
        try {
            ObjectNode params = objectMapper.createObjectNode();
            params.put("path", fixture.toString());

            // when - the recording is persisted via the MCP tool
            JsonNode result = toolRegistry.callTool("record_llm_fixtures", params);
            assertThat(result.path("status").asText(), is("written"));
            int written = result.path("count").asInt();
            assertThat(written > 0, is(true));

            // then - the written fixture is discoverable as a cassette with a "recorded" origin
            java.util.List<org.mockserver.mock.CassetteRegistry.Entry> cassettes =
                org.mockserver.mock.CassetteRegistry.getInstance().list();
            assertThat(cassettes.size(), is(1));
            org.mockserver.mock.CassetteRegistry.Entry entry = cassettes.get(0);
            assertThat(entry.path, is(fixture.toAbsolutePath().normalize().toString()));
            assertThat(entry.expectationCount, is(written));
            assertThat(entry.origin, is("recorded"));
        } finally {
            java.nio.file.Files.deleteIfExists(fixture);
        }
    }

    // --- path traversal rejection tests ---

    @Test
    public void shouldRejectPathTraversalForRecordLlmFixtures() {
        // given - path outside allowed directories
        ObjectNode params = objectMapper.createObjectNode();
        params.put("path", "/etc/passwd");

        // when
        JsonNode result = toolRegistry.callTool("record_llm_fixtures", params);

        // then
        assertThat(result.path("error").asBoolean(), is(true));
        assertThat(result.path("message").asText(), containsString("Path is outside allowed directories"));
    }

    @Test
    public void shouldRejectDotDotPathTraversalForRecordLlmFixtures() {
        // given - path with ../ that escapes allowed roots
        ObjectNode params = objectMapper.createObjectNode();
        params.put("path", System.getProperty("java.io.tmpdir") + "/../../etc/shadow");

        // when
        JsonNode result = toolRegistry.callTool("record_llm_fixtures", params);

        // then
        assertThat(result.path("error").asBoolean(), is(true));
        assertThat(result.path("message").asText(), containsString("Path is outside allowed directories"));
    }

    @Test
    public void shouldRejectPathTraversalForLoadExpectationsFromFile() {
        // given - path outside allowed directories
        ObjectNode params = objectMapper.createObjectNode();
        params.put("path", "/etc/passwd");

        // when
        JsonNode result = toolRegistry.callTool("load_expectations_from_file", params);

        // then
        assertThat(result.path("error").asBoolean(), is(true));
        assertThat(result.path("message").asText(), containsString("Path is outside allowed directories"));
    }

    @Test
    public void shouldRejectDotDotPathTraversalForLoadExpectationsFromFile() {
        // given - path with ../ that escapes allowed roots
        ObjectNode params = objectMapper.createObjectNode();
        params.put("path", System.getProperty("java.io.tmpdir") + "/../../etc/shadow");

        // when
        JsonNode result = toolRegistry.callTool("load_expectations_from_file", params);

        // then
        assertThat(result.path("error").asBoolean(), is(true));
        assertThat(result.path("message").asText(), containsString("Path is outside allowed directories"));
    }

    // --- M2: null host validation ---

    @Test
    public void shouldRejectBaseUrlWithNoHostForContractTest() {
        // given - a file:// URI has no host
        ObjectNode params = objectMapper.createObjectNode();
        params.put("specUrlOrPayload", "org/mockserver/openapi/openapi_simple_example.json");
        params.put("baseUrl", "file:///some/path");

        // when
        JsonNode result = toolRegistry.callTool("run_contract_test", params);

        // then
        assertThat(result.path("error").asBoolean(), is(true));
        assertThat(result.path("message").asText(), containsString("must be an absolute HTTP/HTTPS URL with a hostname"));
    }

    @Test
    public void shouldRejectBaseUrlWithNoHostForResiliencyTest() {
        // given - a file:// URI has no host
        ObjectNode params = objectMapper.createObjectNode();
        params.put("specUrlOrPayload", "org/mockserver/openapi/openapi_simple_example.json");
        params.put("baseUrl", "file:///some/path");

        // when
        JsonNode result = toolRegistry.callTool("run_resiliency_test", params);

        // then
        assertThat(result.path("error").asBoolean(), is(true));
        assertThat(result.path("message").asText(), containsString("must be an absolute HTTP/HTTPS URL with a hostname"));
    }

    @Test
    public void shouldRejectNonHttpSchemeForContractTest() {
        // given - a URL with a host but a non-HTTP scheme
        ObjectNode params = objectMapper.createObjectNode();
        params.put("specUrlOrPayload", "org/mockserver/openapi/openapi_simple_example.json");
        params.put("baseUrl", "ftp://example.com/path");

        // when
        JsonNode result = toolRegistry.callTool("run_contract_test", params);

        // then
        assertThat(result.path("error").asBoolean(), is(true));
        assertThat(result.path("message").asText(), containsString("must use the http or https scheme"));
    }

    @Test
    public void shouldRejectNonHttpSchemeForResiliencyTest() {
        // given - a URL with a host but a non-HTTP scheme
        ObjectNode params = objectMapper.createObjectNode();
        params.put("specUrlOrPayload", "org/mockserver/openapi/openapi_simple_example.json");
        params.put("baseUrl", "ftp://example.com/path");

        // when
        JsonNode result = toolRegistry.callTool("run_resiliency_test", params);

        // then
        assertThat(result.path("error").asBoolean(), is(true));
        assertThat(result.path("message").asText(), containsString("must use the http or https scheme"));
    }

    // --- M3: file size limit for load_expectations_from_file ---

    @Test
    public void shouldLoadSmallValidFixtureFile() throws Exception {
        // given - create a small valid fixture file
        String tmpDir = System.getProperty("java.io.tmpdir");
        java.nio.file.Path fixturePath = java.nio.file.Paths.get(tmpDir, "mcp-test-small-fixture.json");
        String fixtureContent = "[{\"httpRequest\":{\"method\":\"GET\",\"path\":\"/test\"},\"httpResponse\":{\"statusCode\":200,\"body\":\"ok\"}}]";
        java.nio.file.Files.write(fixturePath, fixtureContent.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        try {
            ObjectNode params = objectMapper.createObjectNode();
            params.put("path", fixturePath.toString());

            // when
            JsonNode result = toolRegistry.callTool("load_expectations_from_file", params);

            // then - should load successfully (not hit size limit)
            assertThat(result.has("error"), is(false));
            assertThat(result.path("status").asText(), is("loaded"));
            assertThat(result.path("count").asInt(), is(1));
        } finally {
            java.nio.file.Files.deleteIfExists(fixturePath);
        }
    }

    @Test
    public void shouldAcceptPathInsideTempDirForRecordLlmFixtures() {
        // given - valid path inside the system temp directory (no recorded traffic, but path should pass validation)
        ObjectNode params = objectMapper.createObjectNode();
        params.put("path", System.getProperty("java.io.tmpdir") + "/valid-fixture.json");

        // when
        JsonNode result = toolRegistry.callTool("record_llm_fixtures", params);

        // then - should not get path error, should get "no recorded traffic" instead
        assertThat(result.has("error"), is(false));
        assertThat(result.path("status").asText(), is("no_recorded_traffic"));
    }

    @Test
    public void shouldAcceptPathInsideWorkingDirForRecordLlmFixtures() {
        // given - valid path inside the working directory (no recorded traffic, but path should pass validation)
        String workingDir = System.getProperty("user.dir");
        ObjectNode params = objectMapper.createObjectNode();
        params.put("path", workingDir + "/test-output-fixture.json");

        // when
        JsonNode result = toolRegistry.callTool("record_llm_fixtures", params);

        // then - should not get path error, should get "no recorded traffic" instead
        assertThat(result.has("error"), is(false));
        assertThat(result.path("status").asText(), is("no_recorded_traffic"));
    }

    // --- run_mcp_contract_test tool tests ---

    @Test
    public void shouldRunMcpContractTestToolHasSchema() {
        // given
        McpToolRegistry.ToolDefinition tool = toolRegistry.getTools().get("run_mcp_contract_test");

        // then
        assertThat(tool, notNullValue());
        assertThat(tool.getDescription(), containsString("MCP"));
        assertThat(tool.getDescription(), containsString("JSON-RPC"));
        assertThat(tool.getInputSchema().path("properties").has("targetUrl"), is(true));
        assertThat(tool.getInputSchema().path("properties").has("protocolVersion"), is(true));
        assertThat(tool.getInputSchema().path("properties").has("toolName"), is(true));
    }

    @Test
    public void shouldRejectBlankTargetUrlForMcpContractTest() {
        ObjectNode params = objectMapper.createObjectNode();
        params.put("targetUrl", "");

        JsonNode result = toolRegistry.callTool("run_mcp_contract_test", params);
        assertThat(result.path("error").asBoolean(), is(true));
        assertThat(result.path("message").asText(), is("'targetUrl' is required and must not be blank"));
    }

    @Test
    public void shouldRejectTargetUrlWithNoHostForMcpContractTest() {
        ObjectNode params = objectMapper.createObjectNode();
        params.put("targetUrl", "file:///some/path");

        JsonNode result = toolRegistry.callTool("run_mcp_contract_test", params);
        assertThat(result.path("error").asBoolean(), is(true));
        assertThat(result.path("message").asText(), containsString("must be an absolute HTTP/HTTPS URL with a hostname"));
    }

    @Test
    public void shouldRejectNonHttpSchemeForMcpContractTest() {
        ObjectNode params = objectMapper.createObjectNode();
        params.put("targetUrl", "ftp://example.com/mcp");

        JsonNode result = toolRegistry.callTool("run_mcp_contract_test", params);
        assertThat(result.path("error").asBoolean(), is(true));
        assertThat(result.path("message").asText(), containsString("must use the http or https scheme"));
    }

    // --- verify_structured_output tool tests ---

    @Test
    public void shouldVerifyStructuredOutputToolHasSchema() {
        McpToolRegistry.ToolDefinition tool = toolRegistry.getTools().get("verify_structured_output");
        assertThat(tool, notNullValue());
        assertThat(tool.getDescription(), containsString("JSON Schema"));
        assertThat(tool.getInputSchema().path("properties").has("provider"), is(true));
        assertThat(tool.getInputSchema().path("properties").has("schema"), is(true));
    }

    @Test
    public void shouldRejectMissingProviderForVerifyStructuredOutput() {
        ObjectNode params = objectMapper.createObjectNode();
        params.put("schema", "{\"type\":\"object\"}");

        JsonNode result = toolRegistry.callTool("verify_structured_output", params);
        assertThat(result.path("error").asBoolean(), is(true));
        assertThat(result.path("message").asText(), is("'provider' is required"));
    }

    @Test
    public void shouldRejectBlankSchemaForVerifyStructuredOutput() {
        ObjectNode params = objectMapper.createObjectNode();
        params.put("provider", "ANTHROPIC");
        params.put("schema", "");

        JsonNode result = toolRegistry.callTool("verify_structured_output", params);
        assertThat(result.path("error").asBoolean(), is(true));
        assertThat(result.path("message").asText(), is("'schema' is required and must not be blank"));
    }

    @Test
    public void shouldReturnZeroCheckedWhenNoRecordedResponses() {
        ObjectNode params = objectMapper.createObjectNode();
        params.put("provider", "ANTHROPIC");
        params.put("schema", "{\"type\":\"object\",\"required\":[\"city\"]}");

        JsonNode result = toolRegistry.callTool("verify_structured_output", params);
        assertThat(result.has("error"), is(false));
        assertThat(result.path("checked").asInt(), is(0));
        assertThat(result.path("allConform").asBoolean(), is(false));
    }

    @Test
    public void shouldReportConformingStructuredOutput() throws Exception {
        // Record an Anthropic response whose output text is JSON conforming to the schema.
        httpState.log(new LogEntry()
            .setType(FORWARDED_REQUEST)
            .setLogLevel(org.slf4j.event.Level.INFO)
            .setHttpRequest(request().withMethod("POST").withPath("/v1/messages"))
            .setHttpResponse(response().withStatusCode(200).withBody(
                "{\"content\":[{\"type\":\"text\",\"text\":\"{\\\"city\\\":\\\"Paris\\\"}\"}],\"usage\":{\"input_tokens\":1,\"output_tokens\":1}}"))
            .setMessageFormat("returning response:{}for forwarded request")
            .setArguments(response().withStatusCode(200)));
        Thread.sleep(500);

        ObjectNode params = objectMapper.createObjectNode();
        params.put("provider", "ANTHROPIC");
        params.put("path", "/v1/messages");
        params.put("schema", "{\"type\":\"object\",\"required\":[\"city\"]}");
        JsonNode result = toolRegistry.callTool("verify_structured_output", params);

        assertThat(result.has("error"), is(false));
        assertThat(result.path("checked").asInt(), is(1));
        assertThat(result.path("conforming").asInt(), is(1));
        assertThat(result.path("allConform").asBoolean(), is(true));
    }

    @Test
    public void shouldFlagNonConformingStructuredOutput() throws Exception {
        // Record an Anthropic response whose output text is missing the required field.
        httpState.log(new LogEntry()
            .setType(FORWARDED_REQUEST)
            .setLogLevel(org.slf4j.event.Level.INFO)
            .setHttpRequest(request().withMethod("POST").withPath("/v1/messages"))
            .setHttpResponse(response().withStatusCode(200).withBody(
                "{\"content\":[{\"type\":\"text\",\"text\":\"{\\\"foo\\\":1}\"}],\"usage\":{\"input_tokens\":1,\"output_tokens\":1}}"))
            .setMessageFormat("returning response:{}for forwarded request")
            .setArguments(response().withStatusCode(200)));
        Thread.sleep(500);

        ObjectNode params = objectMapper.createObjectNode();
        params.put("provider", "ANTHROPIC");
        params.put("path", "/v1/messages");
        params.put("schema", "{\"type\":\"object\",\"required\":[\"city\"]}");
        JsonNode result = toolRegistry.callTool("verify_structured_output", params);

        assertThat(result.path("checked").asInt(), is(1));
        assertThat(result.path("nonConforming").asInt(), is(1));
        assertThat(result.path("allConform").asBoolean(), is(false));
    }

    // --- verify_cost_budget tool tests ---

    @Test
    public void shouldRejectMissingMaxCostForVerifyCostBudget() {
        ObjectNode params = objectMapper.createObjectNode();
        params.put("provider", "ANTHROPIC");

        JsonNode result = toolRegistry.callTool("verify_cost_budget", params);
        assertThat(result.path("error").asBoolean(), is(true));
        assertThat(result.path("message").asText(), containsString("maxCostUsd"));
    }

    @Test
    public void shouldRejectNegativeMaxCostForVerifyCostBudget() {
        ObjectNode params = objectMapper.createObjectNode();
        params.put("provider", "ANTHROPIC");
        params.put("maxCostUsd", -1.0);

        JsonNode result = toolRegistry.callTool("verify_cost_budget", params);
        assertThat(result.path("error").asBoolean(), is(true));
        assertThat(result.path("message").asText(), containsString("negative"));
    }

    @Test
    public void shouldReportZeroCostWhenNoRecordedResponses() {
        ObjectNode params = objectMapper.createObjectNode();
        params.put("provider", "ANTHROPIC");
        params.put("maxCostUsd", 5.0);

        JsonNode result = toolRegistry.callTool("verify_cost_budget", params);
        assertThat(result.has("error"), is(false));
        assertThat(result.path("checked").asInt(), is(0));
        assertThat(result.path("totalCostUsd").asDouble(), is(0.0));
        assertThat(result.path("withinBudget").asBoolean(), is(true));
    }

    @Test
    public void shouldPassWhenCostWithinBudget() throws Exception {
        // 1M input + 1M output @ claude-sonnet-4 ($3 in / $15 out) = $18.00
        httpState.log(new LogEntry()
            .setType(FORWARDED_REQUEST)
            .setLogLevel(org.slf4j.event.Level.INFO)
            .setHttpRequest(request().withMethod("POST").withPath("/v1/messages"))
            .setHttpResponse(response().withStatusCode(200).withBody(
                "{\"content\":[{\"type\":\"text\",\"text\":\"ok\"}],\"usage\":{\"input_tokens\":1000000,\"output_tokens\":1000000}}"))
            .setMessageFormat("returning response:{}for forwarded request")
            .setArguments(response().withStatusCode(200)));
        Thread.sleep(500);

        ObjectNode params = objectMapper.createObjectNode();
        params.put("provider", "ANTHROPIC");
        params.put("path", "/v1/messages");
        params.put("model", "claude-sonnet-4");
        params.put("maxCostUsd", 20.0);
        JsonNode result = toolRegistry.callTool("verify_cost_budget", params);

        assertThat(result.has("error"), is(false));
        assertThat(result.path("checked").asInt(), is(1));
        assertThat(result.path("totalInputTokens").asLong(), is(1000000L));
        assertThat(result.path("totalOutputTokens").asLong(), is(1000000L));
        assertThat(result.path("totalCostUsd").asDouble(), is(18.0));
        assertThat(result.path("withinBudget").asBoolean(), is(true));
        assertThat(result.path("budgetCheckAuthoritative").asBoolean(), is(true));
    }

    @Test
    public void shouldSumCostAcrossMultipleResponses() throws Exception {
        // two responses, 500k+500k tokens each at claude-sonnet-4 -> $9.00 each -> $18.00 total
        for (int i = 0; i < 2; i++) {
            httpState.log(new LogEntry()
                .setType(FORWARDED_REQUEST)
                .setLogLevel(org.slf4j.event.Level.INFO)
                .setHttpRequest(request().withMethod("POST").withPath("/v1/messages"))
                .setHttpResponse(response().withStatusCode(200).withBody(
                    "{\"content\":[{\"type\":\"text\",\"text\":\"ok\"}],\"usage\":{\"input_tokens\":500000,\"output_tokens\":500000}}"))
                .setMessageFormat("returning response:{}for forwarded request")
                .setArguments(response().withStatusCode(200)));
        }
        Thread.sleep(500);

        ObjectNode params = objectMapper.createObjectNode();
        params.put("provider", "ANTHROPIC");
        params.put("path", "/v1/messages");
        params.put("model", "claude-sonnet-4");
        params.put("maxCostUsd", 20.0);
        JsonNode result = toolRegistry.callTool("verify_cost_budget", params);

        assertThat(result.path("checked").asInt(), is(2));
        assertThat(result.path("totalInputTokens").asLong(), is(1000000L));
        assertThat(result.path("totalOutputTokens").asLong(), is(1000000L));
        assertThat(result.path("totalCostUsd").asDouble(), is(18.0));
        assertThat(result.path("withinBudget").asBoolean(), is(true));
        assertThat(result.path("budgetCheckAuthoritative").asBoolean(), is(true));
    }

    @Test
    public void shouldReportPartialTotalAndNonAuthoritativeForMixedPriceability() throws Exception {
        // one priceable response (model in request body) + one unpriceable (no model)
        httpState.log(new LogEntry()
            .setType(FORWARDED_REQUEST)
            .setLogLevel(org.slf4j.event.Level.INFO)
            .setHttpRequest(request().withMethod("POST").withPath("/v1/messages")
                .withBody("{\"model\":\"claude-sonnet-4\"}"))
            .setHttpResponse(response().withStatusCode(200).withBody(
                "{\"content\":[{\"type\":\"text\",\"text\":\"ok\"}],\"usage\":{\"input_tokens\":1000000,\"output_tokens\":0}}"))
            .setMessageFormat("returning response:{}for forwarded request")
            .setArguments(response().withStatusCode(200)));
        httpState.log(new LogEntry()
            .setType(FORWARDED_REQUEST)
            .setLogLevel(org.slf4j.event.Level.INFO)
            .setHttpRequest(request().withMethod("POST").withPath("/v1/messages"))
            .setHttpResponse(response().withStatusCode(200).withBody(
                "{\"content\":[{\"type\":\"text\",\"text\":\"ok\"}],\"usage\":{\"input_tokens\":1000000,\"output_tokens\":0}}"))
            .setMessageFormat("returning response:{}for forwarded request")
            .setArguments(response().withStatusCode(200)));
        Thread.sleep(500);

        ObjectNode params = objectMapper.createObjectNode();
        params.put("provider", "ANTHROPIC");
        params.put("path", "/v1/messages");
        params.put("maxCostUsd", 20.0);
        JsonNode result = toolRegistry.callTool("verify_cost_budget", params);

        assertThat(result.path("checked").asInt(), is(2));
        assertThat(result.path("unpriceable").asInt(), is(1));
        // only the priceable response (1M input @ $3) contributes to the total
        assertThat(result.path("totalCostUsd").asDouble(), is(3.0));
        // total understates true cost -> not authoritative even though withinBudget is true
        assertThat(result.path("withinBudget").asBoolean(), is(true));
        assertThat(result.path("budgetCheckAuthoritative").asBoolean(), is(false));
    }

    @Test
    public void shouldFailWhenCostExceedsBudget() throws Exception {
        httpState.log(new LogEntry()
            .setType(FORWARDED_REQUEST)
            .setLogLevel(org.slf4j.event.Level.INFO)
            .setHttpRequest(request().withMethod("POST").withPath("/v1/messages"))
            .setHttpResponse(response().withStatusCode(200).withBody(
                "{\"content\":[{\"type\":\"text\",\"text\":\"ok\"}],\"usage\":{\"input_tokens\":1000000,\"output_tokens\":1000000}}"))
            .setMessageFormat("returning response:{}for forwarded request")
            .setArguments(response().withStatusCode(200)));
        Thread.sleep(500);

        ObjectNode params = objectMapper.createObjectNode();
        params.put("provider", "ANTHROPIC");
        params.put("path", "/v1/messages");
        params.put("model", "claude-sonnet-4");
        params.put("maxCostUsd", 5.0);
        JsonNode result = toolRegistry.callTool("verify_cost_budget", params);

        assertThat(result.path("totalCostUsd").asDouble(), is(18.0));
        assertThat(result.path("withinBudget").asBoolean(), is(false));
    }

    @Test
    public void shouldReportUnpriceableWhenModelUnknown() throws Exception {
        // no model param and the request body carries no model -> unpriceable
        httpState.log(new LogEntry()
            .setType(FORWARDED_REQUEST)
            .setLogLevel(org.slf4j.event.Level.INFO)
            .setHttpRequest(request().withMethod("POST").withPath("/v1/messages"))
            .setHttpResponse(response().withStatusCode(200).withBody(
                "{\"content\":[{\"type\":\"text\",\"text\":\"ok\"}],\"usage\":{\"input_tokens\":100,\"output_tokens\":100}}"))
            .setMessageFormat("returning response:{}for forwarded request")
            .setArguments(response().withStatusCode(200)));
        Thread.sleep(500);

        ObjectNode params = objectMapper.createObjectNode();
        params.put("provider", "ANTHROPIC");
        params.put("path", "/v1/messages");
        params.put("maxCostUsd", 5.0);
        JsonNode result = toolRegistry.callTool("verify_cost_budget", params);

        assertThat(result.path("checked").asInt(), is(1));
        assertThat(result.path("unpriceable").asInt(), is(1));
        assertThat(result.path("totalCostUsd").asDouble(), is(0.0));
        // unpriceable responses are excluded, so an empty total stays within budget...
        assertThat(result.path("withinBudget").asBoolean(), is(true));
        // ...but the check is NOT authoritative — the caller must not trust withinBudget here
        assertThat(result.path("budgetCheckAuthoritative").asBoolean(), is(false));
    }

    private void logForwardedGeminiCall(String host, String path, int inputTokens, int outputTokens) {
        // https://docs.cloud.google.com/vertex-ai/generative-ai/docs/model-reference/inference
        httpState.log(new LogEntry()
            .setType(FORWARDED_REQUEST)
            .setLogLevel(org.slf4j.event.Level.INFO)
            .setHttpRequest(request().withMethod("POST").withPath(path).withHeader("Host", host)
                .withBody("{\"contents\":[{\"role\":\"user\",\"parts\":[{\"text\":\"hi\"}]}]}"))
            .setHttpResponse(response().withStatusCode(200).withBody(
                "{\"candidates\":[{\"content\":{\"role\":\"model\",\"parts\":[{\"text\":\"Hello\"}]},\"finishReason\":\"STOP\"}],"
                    + "\"usageMetadata\":{\"promptTokenCount\":" + inputTokens + ",\"candidatesTokenCount\":" + outputTokens + "},"
                    + "\"modelVersion\":\"gemini-2.0-flash-001\"}"))
            .setMessageFormat("returning response:{}for forwarded request")
            .setArguments(response().withStatusCode(200)));
    }

    // Vertex AI bills Gemini 2.0 Flash at 0.15 / 0.60 USD per million input / output tokens
    // (https://cloud.google.com/vertex-ai/generative-ai/pricing), the Gemini API at 0.10 / 0.40
    @Test
    public void shouldPriceANativeVertexAiGeminiCallAtVertexAiRates() throws Exception {
        String path = "/v1/projects/my-project/locations/us-central1/publishers/google/models/gemini-2.0-flash-001:generateContent";
        logForwardedGeminiCall("us-central1-aiplatform.googleapis.com", path, 1_000_000, 1_000_000);
        Thread.sleep(500);

        ObjectNode params = objectMapper.createObjectNode();
        params.put("provider", "GEMINI");
        params.put("path", path);
        params.put("maxCostUsd", 0.7);
        JsonNode result = toolRegistry.callTool("verify_cost_budget", params);

        assertThat(result.has("error"), is(false));
        assertThat(result.path("checked").asInt(), is(1));
        assertThat(result.path("unpriceable").asInt(), is(0));
        assertThat(result.path("results").get(0).path("model").asText(), is("gemini-2.0-flash-001"));
        assertThat(result.path("totalCostUsd").asDouble(), closeTo(0.75, 1e-9));
        assertThat(result.path("withinBudget").asBoolean(), is(false));
        assertThat(result.path("budgetCheckAuthoritative").asBoolean(), is(true));
    }

    @Test
    public void shouldPriceAPinnedModelOnAVertexAiHostAtVertexAiRates() throws Exception {
        String path = "/v1/projects/my-project/locations/eu/publishers/google/models/gemini-2.0-flash-001:generateContent";
        logForwardedGeminiCall("aiplatform.eu.rep.googleapis.com", path, 1_000_000, 0);
        Thread.sleep(500);

        ObjectNode params = objectMapper.createObjectNode();
        params.put("provider", "GEMINI");
        params.put("path", path);
        params.put("model", "gemini-2.0-flash");
        params.put("maxCostUsd", 1.0);
        JsonNode result = toolRegistry.callTool("verify_cost_budget", params);

        assertThat(result.path("totalCostUsd").asDouble(), closeTo(0.15, 1e-9));
    }

    @Test
    public void shouldPriceANativeGeminiApiCallAtGeminiApiRates() throws Exception {
        String path = "/v1beta/models/gemini-2.0-flash:generateContent";
        logForwardedGeminiCall("generativelanguage.googleapis.com", path, 1_000_000, 1_000_000);
        Thread.sleep(500);

        ObjectNode params = objectMapper.createObjectNode();
        params.put("provider", "GEMINI");
        params.put("path", path);
        params.put("maxCostUsd", 1.0);
        JsonNode result = toolRegistry.callTool("verify_cost_budget", params);

        assertThat(result.path("unpriceable").asInt(), is(0));
        assertThat(result.path("results").get(0).path("model").asText(), is("gemini-2.0-flash"));
        assertThat(result.path("totalCostUsd").asDouble(), closeTo(0.5, 1e-9));
    }

    @Test
    public void shouldNotBeAuthoritativeWhenNoResponses() {
        ObjectNode params = objectMapper.createObjectNode();
        params.put("provider", "ANTHROPIC");
        params.put("maxCostUsd", 5.0);

        JsonNode result = toolRegistry.callTool("verify_cost_budget", params);
        assertThat(result.path("checked").asInt(), is(0));
        assertThat(result.path("withinBudget").asBoolean(), is(true));
        assertThat(result.path("budgetCheckAuthoritative").asBoolean(), is(false));
    }

    @Test
    public void shouldCreateExpectationWithChaos() {
        ObjectNode params = objectMapper.createObjectNode();
        params.put("method", "GET");
        params.put("path", "/chaos-test");
        params.put("statusCode", 200);
        params.put("responseBody", "ok");

        ObjectNode chaos = objectMapper.createObjectNode();
        chaos.put("errorStatus", 503);
        chaos.put("errorProbability", 0.5);
        chaos.put("retryAfter", "30");
        ObjectNode latency = objectMapper.createObjectNode();
        latency.put("timeUnit", "MILLISECONDS");
        latency.put("value", 200);
        chaos.set("latency", latency);
        chaos.put("seed", 42);
        chaos.put("succeedFirst", 3);
        chaos.put("failRequestCount", 5);
        chaos.put("truncateBodyAtFraction", 0.25);
        chaos.put("malformedBody", true);
        chaos.put("slowResponseChunkSize", 8);
        ObjectNode slowDelay = objectMapper.createObjectNode();
        slowDelay.put("timeUnit", "MILLISECONDS");
        slowDelay.put("value", 250);
        chaos.set("slowResponseChunkDelay", slowDelay);
        chaos.put("quotaName", "acct-mcp");
        chaos.put("quotaLimit", 4);
        chaos.put("quotaWindowMillis", 60000);
        chaos.put("quotaErrorStatus", 429);
        chaos.put("degradationRampMillis", 30000);
        params.set("chaos", chaos);

        JsonNode result = toolRegistry.callTool("create_expectation", params);
        assertThat(result.path("status").asText(), is("created"));
        assertThat(result.path("count").asInt(), is(1));
        assertThat(result.has("id"), is(true));

        // Verify the expectation was persisted with chaos profile by retrieving active expectations
        ObjectNode retrieveParams = objectMapper.createObjectNode();
        retrieveParams.put("type", "ACTIVE_EXPECTATIONS");
        retrieveParams.put("format", "JSON");
        JsonNode retrieveResult = toolRegistry.callTool("raw_retrieve", retrieveParams);
        JsonNode expectations = retrieveResult.path("data");
        assertThat(expectations.isArray(), is(true));
        boolean foundChaos = false;
        for (JsonNode exp : expectations) {
            if ("/chaos-test".equals(exp.path("httpRequest").path("path").asText())) {
                JsonNode chaosResult = exp.path("chaos");
                assertThat(chaosResult.path("errorStatus").asInt(), is(503));
                assertThat(chaosResult.path("errorProbability").asDouble(), is(0.5));
                assertThat(chaosResult.path("retryAfter").asText(), is("30"));
                assertThat(chaosResult.path("latency").path("timeUnit").asText(), is("MILLISECONDS"));
                assertThat(chaosResult.path("latency").path("value").asInt(), is(200));
                assertThat(chaosResult.path("seed").asLong(), is(42L));
                assertThat(chaosResult.path("succeedFirst").asInt(), is(3));
                assertThat(chaosResult.path("failRequestCount").asInt(), is(5));
                assertThat(chaosResult.path("truncateBodyAtFraction").asDouble(), is(0.25));
                assertThat(chaosResult.path("malformedBody").asBoolean(), is(true));
                assertThat(chaosResult.path("slowResponseChunkSize").asInt(), is(8));
                assertThat(chaosResult.path("slowResponseChunkDelay").path("timeUnit").asText(), is("MILLISECONDS"));
                assertThat(chaosResult.path("slowResponseChunkDelay").path("value").asInt(), is(250));
                assertThat(chaosResult.path("quotaName").asText(), is("acct-mcp"));
                assertThat(chaosResult.path("quotaLimit").asInt(), is(4));
                assertThat(chaosResult.path("quotaWindowMillis").asLong(), is(60000L));
                assertThat(chaosResult.path("quotaErrorStatus").asInt(), is(429));
                assertThat(chaosResult.path("degradationRampMillis").asLong(), is(30000L));
                foundChaos = true;
            }
        }
        assertThat("Expected to find chaos expectation", foundChaos, is(true));
    }

    @Test
    public void shouldCreateExpectationWithPartialChaos() {
        ObjectNode params = objectMapper.createObjectNode();
        params.put("method", "POST");
        params.put("path", "/chaos-partial");
        params.put("statusCode", 200);

        ObjectNode chaos = objectMapper.createObjectNode();
        chaos.put("errorStatus", 500);
        chaos.put("errorProbability", 1.0);
        params.set("chaos", chaos);

        JsonNode result = toolRegistry.callTool("create_expectation", params);
        assertThat(result.path("status").asText(), is("created"));
        assertThat(result.path("count").asInt(), is(1));
    }

    @Test
    public void shouldCreateExpectationWithoutChaos() {
        ObjectNode params = objectMapper.createObjectNode();
        params.put("method", "GET");
        params.put("path", "/no-chaos");
        params.put("statusCode", 200);

        JsonNode result = toolRegistry.callTool("create_expectation", params);
        assertThat(result.path("status").asText(), is("created"));
    }

    @Test
    public void shouldRejectInvalidChaosLatencyTimeUnit() {
        ObjectNode params = objectMapper.createObjectNode();
        params.put("method", "GET");
        params.put("path", "/chaos-bad-unit");
        params.put("statusCode", 200);

        ObjectNode chaos = objectMapper.createObjectNode();
        chaos.put("errorStatus", 503);
        chaos.put("errorProbability", 0.5);
        ObjectNode latency = objectMapper.createObjectNode();
        latency.put("timeUnit", "FORTNIGHTS");
        latency.put("value", 200);
        chaos.set("latency", latency);
        params.set("chaos", chaos);

        JsonNode result = toolRegistry.callTool("create_expectation", params);
        assertThat(result.path("error").asBoolean(), is(true));
        assertThat(result.path("message").asText(), is("chaos latency timeUnit must be one of: NANOSECONDS, MICROSECONDS, MILLISECONDS, SECONDS, MINUTES, HOURS, DAYS"));
    }

    @Test
    public void shouldGenerateMcpToolsFromMockExpectations() {
        assertThat(toolRegistry.getTools().containsKey("list_mock_tools"), is(true));

        ObjectNode createParams = objectMapper.createObjectNode();
        createParams.put("method", "GET");
        createParams.put("path", "/users");
        createParams.put("statusCode", 200);
        toolRegistry.callTool("create_expectation", createParams);

        JsonNode result = toolRegistry.callTool("list_mock_tools", objectMapper.createObjectNode());

        assertThat(result.path("count").asInt(), is(1));
        JsonNode tool = result.path("tools").get(0);
        assertThat(tool.path("name").asText(), is("get_users"));
        assertThat(tool.path("description").asText(), is("Mock for GET /users"));
        assertThat(tool.at("/_mockserver/method").asText(), is("GET"));
        assertThat(tool.at("/_mockserver/path").asText(), is("/users"));
    }

    // --- retrieve_logs tool tests ---

    @Test
    public void shouldRetrieveEmptyLogs() {
        ObjectNode params = objectMapper.createObjectNode();

        JsonNode result = toolRegistry.callTool("retrieve_logs", params);
        assertThat(result.path("total").asInt(), is(0));
        assertThat(result.path("returned").asInt(), is(0));
        assertThat(result.path("logEntries").isArray(), is(true));
    }

    @Test
    public void shouldRetrieveLogEntriesAfterActivity() throws Exception {
        // given - create an expectation and trigger some log activity
        httpState.log(new LogEntry()
            .setType(RECEIVED_REQUEST)
            .setLogLevel(org.slf4j.event.Level.INFO)
            .setHttpRequest(request().withMethod("GET").withPath("/api/test"))
            .setMessageFormat("received request:{}")
            .setArguments(request().withMethod("GET").withPath("/api/test"))
        );

        Thread.sleep(500);

        // when
        ObjectNode params = objectMapper.createObjectNode();
        JsonNode result = toolRegistry.callTool("retrieve_logs", params);

        // then
        assertThat(result.path("total").asInt() >= 1, is(true));
        assertThat(result.path("logEntries").isArray(), is(true));
        assertThat(result.path("returned").asInt() >= 1, is(true));
    }

    @Test
    public void shouldRetrieveLogsFilteredByCorrelationId() throws Exception {
        // given - log two entries with different correlation IDs
        String targetCorrelationId = "test-correlation-id-123";
        httpState.log(new LogEntry()
            .setType(RECEIVED_REQUEST)
            .setLogLevel(org.slf4j.event.Level.INFO)
            .setCorrelationId(targetCorrelationId)
            .setHttpRequest(request().withMethod("GET").withPath("/api/filtered"))
            .setMessageFormat("received request:{}")
            .setArguments(request().withMethod("GET").withPath("/api/filtered"))
        );
        httpState.log(new LogEntry()
            .setType(RECEIVED_REQUEST)
            .setLogLevel(org.slf4j.event.Level.INFO)
            .setCorrelationId("other-correlation-id-456")
            .setHttpRequest(request().withMethod("POST").withPath("/api/other"))
            .setMessageFormat("received request:{}")
            .setArguments(request().withMethod("POST").withPath("/api/other"))
        );

        Thread.sleep(500);

        // when - filter by correlation ID
        ObjectNode params = objectMapper.createObjectNode();
        params.put("correlationId", targetCorrelationId);
        JsonNode result = toolRegistry.callTool("retrieve_logs", params);

        // then - should only return entries matching the correlation ID
        assertThat(result.path("total").asInt() >= 1, is(true));
        assertThat(result.path("logEntries").isArray(), is(true));
        // verify all returned entries have the target correlation ID
        for (JsonNode entry : result.path("logEntries")) {
            assertThat("every returned log entry must carry the correlationId field", entry.has("correlationId"), is(true));
            assertThat(entry.path("correlationId").asText(), is(targetCorrelationId));
        }
    }

    @Test
    public void shouldRetrieveLogsWithLimit() throws Exception {
        // given - create multiple log entries
        for (int i = 0; i < 5; i++) {
            httpState.log(new LogEntry()
                .setType(RECEIVED_REQUEST)
                .setLogLevel(org.slf4j.event.Level.INFO)
                .setHttpRequest(request().withMethod("GET").withPath("/api/item/" + i))
                .setMessageFormat("received request:{}")
                .setArguments(request().withMethod("GET").withPath("/api/item/" + i))
            );
        }

        Thread.sleep(500);

        // when - retrieve with limit=2
        ObjectNode params = objectMapper.createObjectNode();
        params.put("limit", 2);
        JsonNode result = toolRegistry.callTool("retrieve_logs", params);

        // then - should return at most 2 entries (the most recent ones)
        assertThat(result.path("returned").asInt() <= 2, is(true));
        assertThat(result.path("logEntries").isArray(), is(true));
        // total should be >= 5 (there are at least 5 entries in the log)
        assertThat(result.path("total").asInt() >= 5, is(true));
    }

    @Test
    public void shouldRejectNegativeLimitForRetrieveLogs() {
        ObjectNode params = objectMapper.createObjectNode();
        params.put("limit", -1);

        JsonNode result = toolRegistry.callTool("retrieve_logs", params);
        assertThat(result.path("error").asBoolean(), is(true));
        assertThat(result.path("message").asText(), is("'limit' must be between 1 and 500"));
    }

    @Test
    public void shouldRejectZeroLimitForRetrieveLogs() {
        ObjectNode params = objectMapper.createObjectNode();
        params.put("limit", 0);

        JsonNode result = toolRegistry.callTool("retrieve_logs", params);
        assertThat(result.path("error").asBoolean(), is(true));
        assertThat(result.path("message").asText(), is("'limit' must be between 1 and 500"));
    }

    @Test
    public void shouldRejectLimitAboveMaximumForRetrieveLogs() {
        ObjectNode params = objectMapper.createObjectNode();
        params.put("limit", 501);

        JsonNode result = toolRegistry.callTool("retrieve_logs", params);
        assertThat(result.path("error").asBoolean(), is(true));
        assertThat(result.path("message").asText(), is("'limit' must be between 1 and 500"));
    }

    @Test
    public void shouldAcceptLimitAtMaximumForRetrieveLogs() {
        ObjectNode params = objectMapper.createObjectNode();
        params.put("limit", 500);

        JsonNode result = toolRegistry.callTool("retrieve_logs", params);
        assertThat(result.path("total").asInt(), is(0));
        assertThat(result.path("logEntries").isArray(), is(true));
    }

    // ---- list_expectations tool ----

    @Test
    public void shouldListActiveExpectations() {
        // given - two active expectations created through the MCP server
        ObjectNode create1 = objectMapper.createObjectNode();
        create1.put("method", "GET");
        create1.put("path", "/list-a");
        create1.put("responseBody", "a");
        toolRegistry.callTool("create_expectation", create1);
        ObjectNode create2 = objectMapper.createObjectNode();
        create2.put("method", "POST");
        create2.put("path", "/list-b");
        create2.put("responseBody", "b");
        toolRegistry.callTool("create_expectation", create2);

        // when
        JsonNode result = toolRegistry.callTool("list_expectations", objectMapper.createObjectNode());

        // then
        assertThat(result.path("count").asInt(), is(2));
        assertThat(result.path("expectations").isArray(), is(true));
        assertThat(result.path("expectations").size(), is(2));
    }

    @Test
    public void shouldListActiveExpectationsFilteredByPath() {
        ObjectNode create1 = objectMapper.createObjectNode();
        create1.put("method", "GET");
        create1.put("path", "/filter-keep");
        create1.put("responseBody", "keep");
        toolRegistry.callTool("create_expectation", create1);
        ObjectNode create2 = objectMapper.createObjectNode();
        create2.put("method", "GET");
        create2.put("path", "/filter-drop");
        create2.put("responseBody", "drop");
        toolRegistry.callTool("create_expectation", create2);

        ObjectNode params = objectMapper.createObjectNode();
        params.put("path", "/filter-keep");
        JsonNode result = toolRegistry.callTool("list_expectations", params);

        assertThat(result.path("count").asInt(), is(1));
        assertThat(result.path("expectations").get(0).toString(), containsString("/filter-keep"));
    }

    @Test
    public void shouldListNoExpectationsWhenNoneRegistered() {
        JsonNode result = toolRegistry.callTool("list_expectations", objectMapper.createObjectNode());
        assertThat(result.path("count").asInt(), is(0));
        assertThat(result.path("expectations").isArray(), is(true));
        assertThat(result.path("expectations").size(), is(0));
    }

    // ---- set_operating_mode tool ----

    @Test
    public void shouldSetOperatingModeToSpy() {
        ObjectNode params = objectMapper.createObjectNode();
        params.put("mode", "SPY");
        JsonNode result = toolRegistry.callTool("set_operating_mode", params);

        assertThat(result.path("status").asText(), is("ok"));
        assertThat(result.path("mode").asText(), is("SPY"));
        assertThat(result.path("proxyUnmatchedRequests").asBoolean(), is(true));
    }

    @Test
    public void shouldSetOperatingModeToSimulate() {
        ObjectNode params = objectMapper.createObjectNode();
        params.put("mode", "simulate");
        JsonNode result = toolRegistry.callTool("set_operating_mode", params);

        assertThat(result.path("mode").asText(), is("SIMULATE"));
        assertThat(result.path("proxyUnmatchedRequests").asBoolean(), is(false));
    }

    @Test
    public void shouldRejectUnknownOperatingMode() {
        ObjectNode params = objectMapper.createObjectNode();
        params.put("mode", "BOGUS");
        JsonNode result = toolRegistry.callTool("set_operating_mode", params);

        assertThat(result.path("error").asBoolean(), is(true));
        assertThat(result.path("message").asText(), containsString("BOGUS"));
    }

    @Test
    public void shouldRejectMissingOperatingMode() {
        JsonNode result = toolRegistry.callTool("set_operating_mode", objectMapper.createObjectNode());
        assertThat(result.path("error").asBoolean(), is(true));
    }

    // ---- promote_recordings tool ----

    @Test
    public void shouldPromoteRecordedTrafficToActiveExpectations() throws Exception {
        // given - recorded (forwarded) traffic, what SPY/CAPTURE mode produces
        httpState.log(new LogEntry()
            .setType(FORWARDED_REQUEST)
            .setLogLevel(org.slf4j.event.Level.INFO)
            .setHttpRequest(request().withMethod("GET").withPath("/promote/users"))
            .setHttpResponse(response().withStatusCode(200).withBody("[{\"id\":1}]"))
            .setExpectation(request().withMethod("GET").withPath("/promote/users"),
                response().withStatusCode(200).withBody("[{\"id\":1}]"))
            .setMessageFormat("returning response:{}for forwarded request")
            .setArguments(response().withStatusCode(200).withBody("[{\"id\":1}]"))
        );
        Thread.sleep(500);

        // when
        JsonNode result = toolRegistry.callTool("promote_recordings", objectMapper.createObjectNode());

        // then
        assertThat(result.path("status").asText(), is("promoted"));
        assertThat(result.path("count").asInt() >= 1, is(true));
        assertThat(result.path("ids").isArray(), is(true));

        // and the promoted mock is now an active expectation
        JsonNode active = toolRegistry.callTool("list_expectations", objectMapper.createObjectNode());
        assertThat(active.path("count").asInt() >= 1, is(true));
        assertThat(active.path("expectations").toString(), containsString("/promote/users"));
    }

    @Test
    public void shouldReportNoRecordedTrafficWhenNothingToPromote() {
        JsonNode result = toolRegistry.callTool("promote_recordings", objectMapper.createObjectNode());
        assertThat(result.path("status").asText(), is("no_recorded_traffic"));
        assertThat(result.path("count").asInt(), is(0));
    }

    // ---- create_expectation -> verify_request round-trip ----

    @Test
    public void shouldRoundTripCreateExpectationThenVerifyRequest() {
        // create a mock, then verify a request pattern that has NOT been received yet fails
        ObjectNode create = objectMapper.createObjectNode();
        create.put("method", "GET");
        create.put("path", "/round-trip");
        create.put("responseBody", "ok");
        JsonNode created = toolRegistry.callTool("create_expectation", create);
        assertThat(created.path("status").asText(), is("created"));

        ObjectNode verify = objectMapper.createObjectNode();
        verify.put("method", "GET");
        verify.put("path", "/round-trip");
        verify.put("atLeast", 1);
        JsonNode verifyResult = toolRegistry.callTool("verify_request", verify);
        // no request received against the mock yet, so verification reports not-verified with a diff
        assertThat(verifyResult.path("verified").asBoolean(), is(false));
        assertThat(verifyResult.path("message").asText().isEmpty(), is(false));
    }

    @Test
    public void shouldReturnLoadProblemsOfOpenApiSpecThatLoads() {
        ObjectNode params = objectMapper.createObjectNode();
        params.put("specUrlOrPayload", "openapi: 3.0.0\n" +
            "info:\n" +
            "  title: \"" + java.util.UUID.randomUUID() + "\"\n" +
            "  version: \"1\"\n" +
            "paths:\n" +
            "  /pets:\n" +
            "    get:\n" +
            "      operationId: listPets\n" +
            "      responses:\n" +
            "        \"200\":\n" +
            "          description: ok\n" +
            "          content:\n" +
            "            application/json:\n" +
            "              schema:\n" +
            "                $ref: \"#/components/schemas/Missing\"\n");

        JsonNode result = toolRegistry.callTool("create_expectation_from_openapi", params);

        assertThat(result.toString(), result.path("count").asInt(), is(1));
        assertThat(result.path("loadProblems").size(), is(1));
        JsonNode problem = result.path("loadProblems").get(0);
        assertThat(problem.path("kind").asText(), is("REF"));
        assertThat(problem.path("location").asText(), is("#/paths/~1pets/get/responses/200/content/application~1json/schema/$ref"));
        assertThat(problem.path("description").asText(), is("$ref target #/components/schemas/Missing does not exist"));
    }

    @Test
    public void shouldListAtMostFiftyLoadProblemsAndCountTheRest() {
        StringBuilder paths = new StringBuilder("paths:\n");
        for (int i = 0; i < 53; i++) {
            paths.append("  p").append(i).append(":\n")
                .append("    get:\n")
                .append("      responses:\n")
                .append("        \"200\":\n")
                .append("          description: ok\n");
        }
        ObjectNode params = objectMapper.createObjectNode();
        params.put("specUrlOrPayload", "openapi: 3.0.0\n" +
            "info:\n" +
            "  title: \"" + java.util.UUID.randomUUID() + "\"\n" +
            "  version: \"1\"\n" +
            paths);

        JsonNode result = toolRegistry.callTool("create_expectation_from_openapi", params);

        assertThat(result.toString(), result.path("loadProblems").size(), is(50));
        assertThat(result.path("loadProblems").get(0).path("kind").asText(), is("INVALID_PATH"));
        assertThat(result.path("loadProblemsNotShown").asInt(), is(3));
    }

    @Test
    public void shouldReturnNoLoadProblemsForCleanOpenApiSpec() {
        ObjectNode params = objectMapper.createObjectNode();
        params.put("specUrlOrPayload", "org/mockserver/openapi/openapi_petstore_example.json");

        JsonNode result = toolRegistry.callTool("create_expectation_from_openapi", params);

        assertThat(result.toString(), result.path("count").asInt(), is(4));
        assertThat(result.path("loadProblems").isArray(), is(true));
        assertThat(result.path("loadProblems").size(), is(0));
        assertThat(result.has("loadProblemsNotShown"), is(false));
    }

    // ---- per-tool read/mutate classification (control-plane authorization) ----

    @Test
    public void shouldClassifyStateMutatingToolsAsMutating() {
        for (String tool : Arrays.asList(
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
            "test_template")) {
            assertThat("expected '" + tool + "' to be classified as mutating",
                McpToolRegistry.isMutatingTool(tool), is(true));
        }
    }

    @Test
    public void shouldClassifyReadOnlyToolsAsNonMutating() {
        for (String tool : Arrays.asList(
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
            "run_mcp_contract_test")) {
            assertThat("expected '" + tool + "' to be classified as read-only",
                McpToolRegistry.isMutatingTool(tool), is(false));
        }
    }

    @Test
    public void shouldClassifyEveryRegisteredToolAsEitherReadOrMutate() {
        java.util.Set<String> reading = McpToolRegistry.readingToolNames();
        java.util.Set<String> mutating = McpToolRegistry.mutatingToolNames();

        // disjoint: no tool is classified as both read and mutate (which would otherwise be
        // silently resolved to read by the deny-list, weakening the protection)
        assertThat("read and mutate classifications must be disjoint",
            java.util.Collections.disjoint(reading, mutating), is(true));

        // complete: every registered tool is explicitly classified in exactly one set, so a
        // newly added tool cannot be silently left unclassified
        java.util.Set<String> classified = new java.util.HashSet<>(reading);
        classified.addAll(mutating);
        assertThat("every registered tool must be explicitly classified read or mutate",
            classified, is(new java.util.HashSet<>(toolRegistry.getTools().keySet())));

        // and the runtime decision agrees with the explicit sets
        for (String tool : mutating) {
            assertThat(tool + " must be mutating", McpToolRegistry.isMutatingTool(tool), is(true));
        }
        for (String tool : reading) {
            assertThat(tool + " must be read-only", McpToolRegistry.isMutatingTool(tool), is(false));
        }
    }

    @Test
    public void shouldTreatUnknownToolAsMutatingFailClosed() {
        assertThat(McpToolRegistry.isMutatingTool("some_unregistered_tool"), is(true));
    }
}
