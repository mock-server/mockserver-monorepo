package org.mockserver.dashboard;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.After;
import org.junit.Test;
import org.mockserver.configuration.Configuration;
import org.mockserver.dashboard.model.DashboardLogEntryDTO;
import org.mockserver.dashboard.serializers.DashboardLogEntryDTOGroupSerializer;
import org.mockserver.dashboard.serializers.DashboardLogEntryDTOSerializer;
import org.mockserver.dashboard.serializers.DescriptionSerializer;
import org.mockserver.dashboard.serializers.ThrowableSerializer;
import org.mockserver.log.model.DeferredLogArgument;
import org.mockserver.log.model.LogEntry;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.mock.Expectation;
import org.mockserver.mock.HttpState;
import org.mockserver.model.HttpForward;
import org.mockserver.model.HttpRequest;
import org.mockserver.model.HttpResponse;
import org.mockserver.scheduler.Scheduler;
import org.mockserver.serialization.ObjectMapperFactory;
import org.mockserver.serialization.curl.HttpRequestToCurlSerializer;
import org.slf4j.event.Level;

import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.hamcrest.core.Is.is;
import static org.mockserver.character.Character.NEW_LINE;
import static org.mockserver.configuration.Configuration.configuration;
import static org.mockserver.log.model.LogEntry.LogMessageType.EXPECTATION_NOT_MATCHED;
import static org.mockserver.log.model.LogEntry.LogMessageType.FORWARDED_REQUEST;
import static org.mockserver.model.HttpRequest.request;
import static org.mockserver.model.HttpResponse.response;
import static org.mockserver.model.JsonBody.json;

/**
 * The dashboard's rendering of a proxied exchange, asserted whole: the serialized DTO and the WebSocket frame
 * the UI receives must carry no credential once {@code redactSecretsInLog} is set on the server's
 * {@link Configuration} instance (the object {@code PUT /mockserver/configuration} mutates). The message
 * parts, including the curl command, are built from the log arguments, which read the configuration too.
 */
public class DashboardRedactionFrameTest {

    private static final List<String> SECRETS = Arrays.asList(
        "AUTHZ-SECRET-1", "PROXYAUTH-SECRET-2", "APIKEY-SECRET-3", "COOKIE-SECRET-4",
        "QUERY-SECRET-5", "BODY-SECRET-6", "SETCOOKIE-SECRET-7", "RESPBODY-SECRET-8"
    );
    private static final ObjectMapper DASHBOARD_MAPPER = ObjectMapperFactory.createObjectMapper(
        new DashboardLogEntryDTOSerializer(),
        new DashboardLogEntryDTOGroupSerializer(),
        new DescriptionSerializer(),
        new ThrowableSerializer()
    );

    private final List<Scheduler> schedulers = new ArrayList<>();
    private final List<HttpState> httpStates = new ArrayList<>();
    private final List<DashboardWebSocketHandler> handlers = new ArrayList<>();

    @After
    public void stop() throws ReflectiveOperationException {
        // a handler never added to a pipeline never gets handlerRemoved, so shut its executors directly
        for (DashboardWebSocketHandler handler : handlers) {
            for (String field : Arrays.asList("scheduler", "throttleExecutorService")) {
                java.lang.reflect.Field executor = DashboardWebSocketHandler.class.getDeclaredField(field);
                executor.setAccessible(true);
                Object value = executor.get(handler);
                if (value instanceof java.util.concurrent.ExecutorService) {
                    ((java.util.concurrent.ExecutorService) value).shutdownNow();
                }
            }
        }
        httpStates.forEach(HttpState::stop);
        schedulers.forEach(Scheduler::shutdown);
    }

    private static LogEntry forwardedEntry() {
        HttpRequest request = request("/v1/chat")
            .withMethod("POST")
            .withQueryStringParameter("key", "QUERY-SECRET-5")
            .withHeader("Host", "upstream.example.com:8443")
            .withHeader("Authorization", "Bearer AUTHZ-SECRET-1")
            .withHeader("Proxy-Authorization", "Basic PROXYAUTH-SECRET-2")
            .withHeader("x-api-key", "APIKEY-SECRET-3")
            .withHeader("Cookie", "session=COOKIE-SECRET-4")
            .withCookie("session", "COOKIE-SECRET-4")
            .withBody(json("{\"user\":\"bob\",\"password\":\"BODY-SECRET-6\"}"));
        HttpResponse response = response()
            .withStatusCode(200)
            .withHeader("Set-Cookie", "sid=SETCOOKIE-SECRET-7; Path=/")
            .withCookie("sid", "SETCOOKIE-SECRET-7")
            .withBody(json("{\"ok\":true,\"password\":\"RESPBODY-SECRET-8\"}"));
        return new LogEntry()
            .setType(FORWARDED_REQUEST)
            .setLogLevel(Level.INFO)
            .setHttpRequest(request)
            .setHttpResponse(response)
            .setExpectation(request, response)
            .setMessageFormat("returning response:{}for forwarded request" + NEW_LINE + NEW_LINE + " in json:{}" + NEW_LINE + NEW_LINE + " in curl:{}for action:{}from expectation:{}")
            .setArguments(response, request,
                DeferredLogArgument.curl(new HttpRequestToCurlSerializer(new MockServerLogger()), request, new InetSocketAddress("upstream.example.com", 8443)),
                HttpForward.forward().withHost("upstream.example.com").withPort(8443), "some-expectation-id");
    }

    private static Configuration redacting() {
        return configuration().redactSecretsInLog(true).fixtureBodyRedactFields("password");
    }

    @Test
    public void shouldRedactTheWholeSerializedDashboardEntry() throws Exception {
        String json = DASHBOARD_MAPPER.writeValueAsString(new DashboardLogEntryDTO(forwardedEntry(), redacting()));

        assertThat(json, containsString("curl -v"));
        for (String secret : SECRETS) {
            assertThat("dashboard entry leaked " + secret + ":" + NEW_LINE + json, json, not(containsString(secret)));
        }
    }

    @Test
    public void shouldKeepOriginSecretsButNeverProxyLoginInTheSerializedEntryWhenRedactionIsOff() throws Exception {
        String json = DASHBOARD_MAPPER.writeValueAsString(new DashboardLogEntryDTO(forwardedEntry(), configuration().redactSecretsInLog(false)));

        assertOnlyOriginSecretsVisible(json);
    }

    private static void assertOnlyOriginSecretsVisible(String output) {
        for (String secret : SECRETS) {
            if ("PROXYAUTH-SECRET-2".equals(secret)) {
                assertThat("proxy login must never be exposed", output, not(containsString(secret)));
            } else {
                assertThat("origin credentials remain visible when redaction is off", output, containsString(secret));
            }
        }
    }

    @Test
    public void shouldRedactTheWholeDashboardFrame() throws Exception {
        String frame = renderFrame(redacting());

        assertThat(frame, containsString("/v1/chat"));
        for (String secret : SECRETS) {
            assertThat("dashboard frame leaked " + secret + ":" + NEW_LINE + frame, frame, not(containsString(secret)));
        }
    }

    @Test
    public void shouldKeepOriginSecretsButNeverProxyLoginInTheDashboardFrameWhenRedactionIsOff() throws Exception {
        String frame = renderFrame(configuration());

        assertOnlyOriginSecretsVisible(frame);
    }

    // ---- a request that matched no expectation: the "because" and the message parts quote what was compared ----

    private static final List<String> MISMATCH_SECRETS = Arrays.asList("AUTHZ-SECRET-1", "COOKIE-SECRET-4", "QUERY-SECRET-5");

    private static void matchAgainstExpectationsOnEachCredential(HttpState httpState) {
        httpState.add(new Expectation(request("/v1/chat").withHeader("Authorization", "Bearer expected")).thenRespond(response("a")));
        httpState.add(new Expectation(request("/v1/chat").withCookie("session", "expected")).thenRespond(response("b")));
        httpState.add(new Expectation(request("/v1/chat").withQueryStringParameter("key", "expected")).thenRespond(response("c")));
        assertThat(httpState.firstMatchingExpectation(request("/v1/chat")
            .withQueryStringParameter("key", "QUERY-SECRET-5")
            .withHeader("Authorization", "Bearer AUTHZ-SECRET-1")
            .withHeader("Cookie", "session=COOKIE-SECRET-4")
            .withCookie("session", "COOKIE-SECRET-4")), is(nullValue()));
    }

    private String serializedMismatches(Configuration configuration) throws Exception {
        HttpState httpState = newHttpState(configuration);
        matchAgainstExpectationsOnEachCredential(httpState);
        CompletableFuture<List<LogEntry>> logged = new CompletableFuture<>();
        httpState.getMockServerLog().retrieveMessageLogEntries(null, logged::complete);
        StringBuilder json = new StringBuilder();
        for (LogEntry entry : logged.get()) {
            if (entry.getType() == EXPECTATION_NOT_MATCHED) {
                json.append(DASHBOARD_MAPPER.writeValueAsString(new DashboardLogEntryDTO(entry, configuration)));
            }
        }
        assertThat(json.toString(), containsString("\"because\""));
        return json.toString();
    }

    @Test
    public void shouldRedactTheMismatchBecauseInTheDashboardEntry() throws Exception {
        String json = serializedMismatches(redacting().logLevel("INFO"));

        for (String secret : MISMATCH_SECRETS) {
            assertThat("dashboard mismatch entry leaked " + secret + ":" + NEW_LINE + json, json, not(containsString(secret)));
        }
    }

    @Test
    public void shouldLeaveTheMismatchBecauseInTheDashboardEntryWhenOff() throws Exception {
        String json = serializedMismatches(configuration().logLevel("INFO").redactSecretsInLog(false));

        for (String secret : MISMATCH_SECRETS) {
            assertThat(json, containsString(secret));
        }
    }

    @Test
    public void shouldRedactTheMismatchInTheDashboardFrame() throws Exception {
        String frame = renderFrame(redacting().logLevel("INFO"), DashboardRedactionFrameTest::matchAgainstExpectationsOnEachCredential, "EXPECTATION_NOT_MATCHED");

        for (String secret : MISMATCH_SECRETS) {
            assertThat("dashboard frame leaked " + secret + ":" + NEW_LINE + frame, frame, not(containsString(secret)));
        }
    }

    @Test
    public void shouldLeaveTheMismatchInTheDashboardFrameWhenOff() throws Exception {
        String frame = renderFrame(configuration().logLevel("INFO").redactSecretsInLog(false), DashboardRedactionFrameTest::matchAgainstExpectationsOnEachCredential, "EXPECTATION_NOT_MATCHED");

        assertThat(frame, containsString("AUTHZ-SECRET-1"));
    }

    // ---- a request concatenated into the format, no arguments, and an exception quoting it ------------

    private static final List<String> CONCATENATED_SECRETS = Arrays.asList("AUTHZ-SECRET-1", "QUERY-SECRET-5", "COOKIE-SECRET-4");

    private static LogEntry concatenatedRequestEntry() {
        HttpRequest request = request("/v1/chat")
            .withQueryStringParameter("key", "QUERY-SECRET-5")
            .withHeader("Host", "upstream.example.com:abc")
            .withHeader("Authorization", "Bearer AUTHZ-SECRET-1")
            .withHeader("Cookie", "session=COOKIE-SECRET-4");
        return new LogEntry()
            .setType(LogEntry.LogMessageType.EXCEPTION)
            .setLogLevel(Level.ERROR)
            .setHttpRequest(request)
            .setMessageFormat("exception forwarding request " + request)
            .setThrowable(new IllegalArgumentException("wrapped", new IllegalArgumentException("the request does not include the \"Host\" header:" + NEW_LINE + request)));
    }

    @Test
    public void shouldRedactAConcatenatedFormatAndItsExceptionInTheDashboardEntry() throws Exception {
        String json = DASHBOARD_MAPPER.writeValueAsString(new DashboardLogEntryDTO(concatenatedRequestEntry(), redacting()));

        assertThat(json, containsString("exception forwarding request"));
        assertThat(json, containsString("IllegalArgumentException"));
        for (String secret : CONCATENATED_SECRETS) {
            assertThat("dashboard entry leaked " + secret + ":" + NEW_LINE + json, json, not(containsString(secret)));
        }
    }

    @Test
    public void shouldLeaveAConcatenatedFormatInTheDashboardEntryWhenOff() throws Exception {
        String json = DASHBOARD_MAPPER.writeValueAsString(new DashboardLogEntryDTO(concatenatedRequestEntry(), configuration().redactSecretsInLog(false)));

        for (String secret : CONCATENATED_SECRETS) {
            assertThat(json, containsString(secret));
        }
    }

    @Test
    public void shouldRedactAConcatenatedFormatInTheDashboardFrame() throws Exception {
        String frame = renderFrame(redacting(), httpState -> httpState.getMockServerLog().add(concatenatedRequestEntry()), "exception forwarding request");

        for (String secret : CONCATENATED_SECRETS) {
            assertThat("dashboard frame leaked " + secret + ":" + NEW_LINE + frame, frame, not(containsString(secret)));
        }
    }

    private String renderFrame(Configuration configuration) throws InterruptedException {
        return renderFrame(configuration, httpState -> httpState.getMockServerLog().add(forwardedEntry()), "FORWARDED_REQUEST");
    }

    private HttpState newHttpState(Configuration configuration) {
        MockServerLogger mockServerLogger = new MockServerLogger(configuration, DashboardRedactionFrameTest.class);
        Scheduler scheduler = new Scheduler(configuration, mockServerLogger, true);
        schedulers.add(scheduler);
        HttpState httpState = new HttpState(configuration, mockServerLogger, scheduler);
        httpStates.add(httpState);
        return httpState;
    }

    private String renderFrame(Configuration configuration, Consumer<HttpState> populate, String entryType) throws InterruptedException {
        HttpState httpState = newHttpState(configuration);
        populate.accept(httpState);
        DashboardWebSocketHandler handler = new DashboardWebSocketHandler(httpState, false, true).registerListeners();
        handlers.add(handler);
        DashboardWebSocketHandlerTest.MockChannelHandlerContext context = new DashboardWebSocketHandlerTest.MockChannelHandlerContext();
        handler.getClientRegistry().put(context, request());

        // the send throttle is ~1/sec, so retry until a frame carrying the entry lands
        long deadline = System.currentTimeMillis() + 20_000;
        String frame = null;
        while (System.currentTimeMillis() < deadline) {
            handler.sendUpdate(context, request());
            SECONDS.sleep(1);
            if (context.textWebSocketFrame != null && context.textWebSocketFrame.text().contains(entryType)) {
                frame = context.textWebSocketFrame.text();
                break;
            }
        }
        assertThat("a dashboard frame carrying the entry was produced", frame, is(notNullValue()));
        return frame;
    }
}
