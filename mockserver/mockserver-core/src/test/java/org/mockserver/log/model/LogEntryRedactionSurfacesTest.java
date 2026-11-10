package org.mockserver.log.model;

import org.junit.After;
import org.junit.Before;
import org.junit.ClassRule;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockserver.configuration.Configuration;
import org.mockserver.configuration.ConfigurationProperties;
import org.mockserver.fixture.FixtureRedactor;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.mock.Expectation;
import org.mockserver.mock.HttpState;
import org.mockserver.model.HttpForward;
import org.mockserver.model.HttpRequest;
import org.mockserver.model.HttpResponse;
import org.mockserver.model.LogEventRequestAndResponse;
import org.mockserver.model.NottableString;
import org.mockserver.model.RetrieveType;
import org.mockserver.responsewriter.ResponseWriter;
import org.mockserver.scheduler.Scheduler;
import org.mockserver.serialization.LogEntrySerializer;
import org.mockserver.serialization.RequestDefinitionSerializer;
import org.mockserver.serialization.curl.HttpRequestToCurlSerializer;
import org.mockserver.time.GlobalFixedTime;
import org.mockserver.verify.Verification;
import org.mockserver.verify.VerificationSequence;
import org.mockserver.verify.VerificationTimes;
import org.slf4j.Logger;
import org.slf4j.event.Level;

import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Executors;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockserver.character.Character.NEW_LINE;
import static org.mockserver.configuration.Configuration.configuration;
import static org.mockserver.log.model.LogEntry.LogMessageType.EXPECTATION_NOT_MATCHED;
import static org.mockserver.log.model.LogEntry.LogMessageType.FORWARDED_REQUEST;
import static org.mockserver.log.model.LogEntry.LogMessageType.NO_MATCH_RESPONSE;
import static org.mockserver.log.model.LogEntry.LogMessageType.RECEIVED_REQUEST;
import static org.mockserver.model.HttpRequest.request;
import static org.mockserver.model.HttpResponse.response;
import static org.mockserver.model.JsonBody.json;

/**
 * Asserts on WHOLE rendered outputs that no secret from a proxied exchange survives {@code redactSecretsInLog}
 * on any surface a FORWARDED_REQUEST log entry reaches: the stdout message (full and compact), the JSON log
 * entry, and every retrieve type and format. Field-level assertions let the {@code message}, {@code arguments},
 * {@code expectation} and cookie-list leaks through, because each was a sibling of a field that was redacted.
 * <p>
 * Mutates the global {@code redactSecretsInLog} / {@code fixtureBodyRedactFields} store, so it runs in the
 * sequential Surefire phase only.
 */
public class LogEntryRedactionSurfacesTest {

    @ClassRule
    public static final GlobalFixedTime fixedTime = new GlobalFixedTime();

    private static final List<String> SECRETS = Arrays.asList(
        "AUTHZ-SECRET-1",
        "PROXYAUTH-SECRET-2",
        "APIKEY-SECRET-3",
        "COOKIE-SECRET-4",
        "QUERY-SECRET-5",
        "BODY-SECRET-6",
        "SETCOOKIE-SECRET-7",
        "RESPBODY-SECRET-8"
    );
    private static final String PATH = "/v1/chat";
    private static final InetSocketAddress UPSTREAM = new InetSocketAddress("upstream.example.com", 8443);

    private final HttpRequestToCurlSerializer curlSerializer = new HttpRequestToCurlSerializer(new MockServerLogger());
    private final List<HttpState> httpStates = new ArrayList<>();
    private ScheduledExecutorService executor;
    private boolean redactOriginal;
    private String bodyFieldsOriginal;

    @Before
    public void rememberGlobalState() {
        redactOriginal = ConfigurationProperties.redactSecretsInLog();
        bodyFieldsOriginal = ConfigurationProperties.fixtureBodyRedactFields();
        ConfigurationProperties.redactSecretsInLog(false);
        ConfigurationProperties.fixtureBodyRedactFields("");
        executor = Executors.newScheduledThreadPool(2);
    }

    @After
    public void restoreGlobalState() {
        for (HttpState httpState : httpStates) {
            httpState.stop();
        }
        executor.shutdownNow();
        // through the setters: System.clearProperty would leave ConfigurationProperties' cached value in place
        ConfigurationProperties.redactSecretsInLog(redactOriginal);
        ConfigurationProperties.fixtureBodyRedactFields(bodyFieldsOriginal);
    }

    static HttpRequest proxiedRequest() {
        return request(PATH)
            .withMethod("POST")
            .withQueryStringParameter("key", "QUERY-SECRET-5")
            .withQueryStringParameter("model", "gpt")
            .withHeader("Host", "upstream.example.com:8443")
            .withHeader("Authorization", "Bearer AUTHZ-SECRET-1")
            .withHeader("Proxy-Authorization", "Basic PROXYAUTH-SECRET-2")
            .withHeader("x-api-key", "APIKEY-SECRET-3")
            .withHeader("Cookie", "session=COOKIE-SECRET-4")
            .withCookie("session", "COOKIE-SECRET-4")
            .withBody(json("{\"user\":\"bob\",\"password\":\"BODY-SECRET-6\"}"));
    }

    static HttpResponse proxiedResponse() {
        return response()
            .withStatusCode(200)
            .withHeader("Set-Cookie", "sid=SETCOOKIE-SECRET-7; Path=/")
            .withCookie("sid", "SETCOOKIE-SECRET-7")
            .withBody(json("{\"ok\":true,\"password\":\"RESPBODY-SECRET-8\"}"));
    }

    /**
     * The entry exactly as HttpActionHandler logs a proxied exchange, curl argument included.
     */
    private LogEntry forwardedEntry(Object curlArgument) {
        HttpRequest request = proxiedRequest();
        HttpResponse response = proxiedResponse();
        HttpForward action = HttpForward.forward().withHost("upstream.example.com").withPort(8443);
        return new LogEntry()
            .setType(FORWARDED_REQUEST)
            .setLogLevel(Level.INFO)
            .setEpochTime(1_700_000_000_000L)
            .setHttpRequest(request)
            .setHttpResponse(response)
            .setExpectation(request, response)
            .setMessageFormat("returning response:{}for forwarded request" + NEW_LINE + NEW_LINE + " in json:{}" + NEW_LINE + NEW_LINE + " in curl:{}for action:{}from expectation:{}")
            .setArguments(response, request, curlArgument, action, "some-expectation-id");
    }

    /**
     * As a proxied exchange is logged: the request as received, then the forwarded exchange.
     */
    private void logProxiedExchange(HttpState httpState) {
        HttpRequest received = proxiedRequest();
        httpState.log(new LogEntry()
            .setType(RECEIVED_REQUEST)
            .setLogLevel(Level.INFO)
            .setHttpRequest(received)
            .setMessageFormat("received request:{}")
            .setArguments(received));
        httpState.log(forwardedEntry());
    }

    private LogEntry forwardedEntry() {
        return forwardedEntry(DeferredLogArgument.curl(curlSerializer, proxiedRequest(), UPSTREAM));
    }

    private static Configuration redactingInstance() {
        return configuration().redactSecretsInLog(true).fixtureBodyRedactFields("password");
    }

    private static void enableStaticRedaction() {
        ConfigurationProperties.redactSecretsInLog(true);
        ConfigurationProperties.fixtureBodyRedactFields("password");
    }

    private static void assertNoSecrets(String surface, String output) {
        assertThat(surface + " rendered nothing to check", output == null || output.isEmpty(), is(false));
        for (String secret : SECRETS) {
            assertThat(surface + " leaked " + secret + ":" + NEW_LINE + output, output, not(containsString(secret)));
        }
    }

    private static void assertAllOriginSecrets(String surface, String output) {
        for (String secret : SECRETS) {
            assertThat(surface + " should retain only origin secrets with redaction off:" + NEW_LINE + output,
                output, secret.equals("PROXYAUTH-SECRET-2") ? not(containsString(secret)) : containsString(secret));
        }
    }

    // ---- the message, stdout and JSON log entry --------------------------------------------------

    @Test
    public void shouldRedactEveryRenderedFormOfTheEntryWithStaticRedaction() {
        enableStaticRedaction();
        LogEntry entry = forwardedEntry();

        assertNoSecrets("getMessage()", entry.getMessage());
        assertNoSecrets("getCompactMessage()", entry.getCompactMessage());
        assertNoSecrets("log_entries JSON", new LogEntrySerializer(new MockServerLogger()).serialize(entry));
        assertNoSecrets("stdout", stdout(entry, configuration().logLevel("INFO").compactLogFormat(false)));
        assertNoSecrets("stdout compact", stdout(entry, configuration().logLevel("INFO").compactLogFormat(true)));
        assertNoSecrets("argument rendering", Arrays.toString(entry.getArguments()));
    }

    @Test
    public void shouldRedactEveryRenderedFormOfTheEntryWithInstanceRedaction() {
        Configuration configuration = redactingInstance();
        LogEntry entry = forwardedEntry();

        assertNoSecrets("getMessage(configuration)", entry.getMessage(configuration));
        assertNoSecrets("getCompactMessage(configuration)", entry.getCompactMessage(configuration));
        assertNoSecrets("log_entries JSON", new LogEntrySerializer(new MockServerLogger(), configuration).serialize(entry));
        assertNoSecrets("stdout", stdout(entry, redactingInstance().logLevel("INFO").compactLogFormat(false)));
        assertNoSecrets("stdout compact", stdout(entry, redactingInstance().logLevel("INFO").compactLogFormat(true)));
        assertNoSecrets("argument rendering", Arrays.toString(entry.getArguments(configuration)));
    }

    @Test
    public void shouldNotServeAMessageMemoisedBeforeRedactionWasEnabled() {
        Configuration configuration = configuration().redactSecretsInLog(false);
        LogEntry entry = forwardedEntry();
        assertAllOriginSecrets("message rendered with redaction off", entry.getMessage(configuration));

        configuration.redactSecretsInLog(true).fixtureBodyRedactFields("password");

        assertNoSecrets("message rendered after redaction was enabled", entry.getMessage(configuration));
        assertNoSecrets("log_entries JSON after redaction was enabled", new LogEntrySerializer(new MockServerLogger(), configuration).serialize(entry));
    }

    @Test
    public void shouldReRenderWhenTheRedactedBodyFieldsChange() {
        Configuration configuration = configuration().redactSecretsInLog(true);
        LogEntry entry = forwardedEntry();
        assertThat(entry.getMessage(configuration), containsString("BODY-SECRET-6"));

        configuration.fixtureBodyRedactFields("password");

        assertNoSecrets("message after fixtureBodyRedactFields changed", entry.getMessage(configuration));
    }

    @Test
    public void shouldNeverRenderTheCurlArgumentUnredactedThroughToString() {
        // redaction OFF everywhere: toString() is the fail-closed path for callers that bypass LogEntry
        DeferredLogArgument curl = DeferredLogArgument.curl(curlSerializer, proxiedRequest(), UPSTREAM);
        String rendered = curl.toString();

        for (String secret : Arrays.asList("AUTHZ-SECRET-1", "PROXYAUTH-SECRET-2", "APIKEY-SECRET-3", "COOKIE-SECRET-4", "QUERY-SECRET-5")) {
            assertThat(rendered, not(containsString(secret)));
        }
        assertThat(rendered, containsString(FixtureRedactor.REDACTED_PLACEHOLDER));
    }

    @Test
    public void shouldRenderTheCurlArgumentRedactedThroughRenderUnderStaticRedaction() {
        enableStaticRedaction();

        assertNoSecrets("render()", DeferredLogArgument.curl(curlSerializer, proxiedRequest(), UPSTREAM).render(LogEntry.eventLogRedactor(null)));
    }

    // ---- with redaction off, the output is what master produced from a pre-rendered curl String -----

    @Test
    public void shouldRenderEquivalentOriginTrafficWhenRedactionIsOff() {
        Configuration off = configuration().redactSecretsInLog(false);
        LogEntry deferred = forwardedEntry();
        LogEntry preRendered = forwardedEntry(curlSerializer.toCurl(proxiedRequest().removeHeader("Proxy-Authorization"), UPSTREAM));

        assertThat(deferred.getMessage(), is(preRendered.getMessage()));
        assertThat(deferred.getMessage(off), is(preRendered.getMessage(off)));
        assertThat(deferred.getCompactMessage(off), is(preRendered.getCompactMessage(off)));
        assertThat(stripExpectationIds(new LogEntrySerializer(new MockServerLogger(), off).serialize(deferred)),
            is(stripExpectationIds(new LogEntrySerializer(new MockServerLogger(), off).serialize(preRendered))));
        assertThat(Arrays.toString(deferred.getArguments(off)), is(Arrays.toString(preRendered.getArguments(off))));
        assertAllOriginSecrets("message with redaction off", deferred.getMessage(off));
        assertThat("entries differing only in how the curl is held are equal", deferred, is(preRendered));
    }

    private static String stripExpectationIds(String json) {
        // the synthetic expectation's id is random per entry
        return json.replaceAll("\"id\" : \"[^\"]*\"", "\"id\" : \"<id>\"");
    }

    // ---- retrieve: every type and format that carries the exchange ---------------------------------

    @Test
    public void shouldRedactEveryRetrieveSurfaceWithStaticRedaction() {
        enableStaticRedaction();
        assertEveryRetrieveSurfaceRedacted(newHttpState(configuration()));
    }

    @Test
    public void shouldRedactEveryRetrieveSurfaceWithInstanceRedaction() {
        assertEveryRetrieveSurfaceRedacted(newHttpState(redactingInstance()));
    }

    @Test
    public void shouldLeaveOriginSecretsAcrossRetrieveSurfacesWhenRedactionIsOff() {
        HttpState httpState = newHttpState(configuration());
        logProxiedExchange(httpState);

        assertAllOriginSecrets("LOGS", retrieve(httpState, RetrieveType.LOGS, null));
        assertAllOriginSecrets("LOGS LOG_ENTRIES", retrieve(httpState, RetrieveType.LOGS, "LOG_ENTRIES"));
        assertThat(retrieve(httpState, RetrieveType.REQUESTS, "CURL"), containsString("COOKIE-SECRET-4"));
        assertAllOriginSecrets("logEntryBody", logEntryBodies(httpState));
    }

    @Test
    public void shouldRedactRecordedExpectationsWhenTheirOwnFlagIsSet() {
        HttpState httpState = newHttpState(configuration().redactSecretsInRecordedExpectations(true));
        logProxiedExchange(httpState);

        String recorded = retrieve(httpState, RetrieveType.RECORDED_EXPECTATIONS, "JSON");

        // redactSecretsInRecordedExpectations masks credentials in headers, cookies and the query string;
        // JSON body fields are governed by redactSecretsInLog's fixtureBodyRedactFields, not this flag
        assertThat(recorded, containsString(PATH));
        for (String secret : Arrays.asList("AUTHZ-SECRET-1", "PROXYAUTH-SECRET-2", "APIKEY-SECRET-3", "COOKIE-SECRET-4", "QUERY-SECRET-5", "SETCOOKIE-SECRET-7")) {
            assertThat("recorded expectations leaked " + secret + ":" + NEW_LINE + recorded, recorded, not(containsString(secret)));
        }
    }

    @Test
    public void shouldLeaveRecordedExpectationsToTheirOwnFlag() {
        // redactSecretsInLog does not govern recorded expectations, which replay against the upstream
        HttpState httpState = newHttpState(redactingInstance());
        logProxiedExchange(httpState);

        assertThat(retrieve(httpState, RetrieveType.RECORDED_EXPECTATIONS, "JSON"), containsString("AUTHZ-SECRET-1"));
    }

    // ---- a request that matched no expectation: the matcher's "because" quotes the values it compared -----

    private static final List<String> MISMATCH_SECRETS = Arrays.asList("AUTHZ-SECRET-1", "COOKIE-SECRET-4", "QUERY-SECRET-5");

    private static HttpRequest nonMatchingRequest() {
        return request(PATH)
            .withMethod("POST")
            .withQueryStringParameter("key", "QUERY-SECRET-5")
            .withHeader("Authorization", "Bearer AUTHZ-SECRET-1")
            .withHeader("Cookie", "session=COOKIE-SECRET-4")
            .withCookie("session", "COOKIE-SECRET-4");
    }

    /**
     * Matches {@link #nonMatchingRequest()} against an expectation on each credential and returns the
     * EXPECTATION_NOT_MATCHED entries logged, checking their raw "because" does quote every credential.
     */
    private static List<LogEntry> logMismatches(HttpState httpState) throws Exception {
        httpState.add(new Expectation(request(PATH).withHeader("Authorization", "Bearer expected")).thenRespond(response("a")));
        httpState.add(new Expectation(request(PATH).withCookie("session", "expected")).thenRespond(response("b")));
        httpState.add(new Expectation(request(PATH).withQueryStringParameter("key", "expected")).thenRespond(response("c")));
        assertThat(httpState.firstMatchingExpectation(nonMatchingRequest()), is(nullValue()));
        CompletableFuture<List<LogEntry>> logged = new CompletableFuture<>();
        httpState.getMockServerLog().retrieveMessageLogEntries(null, logged::complete);
        List<LogEntry> mismatches = new ArrayList<>();
        for (LogEntry entry : logged.get()) {
            if (entry.getType() == EXPECTATION_NOT_MATCHED) {
                mismatches.add(entry);
            }
        }
        assertThat("at least one mismatch per expectation", mismatches.size(), greaterThanOrEqualTo(3));
        StringBuilder rawBecause = new StringBuilder();
        mismatches.forEach(entry -> rawBecause.append(entry.getBecause()));
        for (String secret : MISMATCH_SECRETS) {
            assertThat("the raw because should quote " + secret + " for this test to prove anything", rawBecause.toString(), containsString(secret));
        }
        return mismatches;
    }

    private static void assertNoMismatchSecrets(String surface, String output) {
        assertThat(surface + " rendered nothing to check", output == null || output.isEmpty(), is(false));
        for (String secret : MISMATCH_SECRETS) {
            assertThat(surface + " leaked " + secret + ":" + NEW_LINE + output, output, not(containsString(secret)));
        }
    }

    @Test
    public void shouldRedactTheMismatchBecauseOnEverySurface() throws Exception {
        Configuration configuration = redactingInstance().logLevel("INFO");
        HttpState httpState = newHttpState(configuration);
        List<LogEntry> mismatches = logMismatches(httpState);

        for (LogEntry mismatch : mismatches) {
            assertNoMismatchSecrets("because", "because:" + mismatch.getBecause(configuration));
            assertNoMismatchSecrets("message", mismatch.getMessage(configuration));
            assertNoMismatchSecrets("arguments", Arrays.toString(mismatch.getArguments(configuration)));
            assertNoMismatchSecrets("stdout", stdout(mismatch, redactingInstance().logLevel("INFO").compactLogFormat(false)));
            assertNoMismatchSecrets("stdout compact", stdout(mismatch, redactingInstance().logLevel("INFO").compactLogFormat(true)));
            if (String.valueOf(mismatch.getBecause()).contains("SECRET")) {
                assertThat(mismatch.getBecause(configuration), containsString(FixtureRedactor.REDACTED_PLACEHOLDER));
            }
        }
        assertNoMismatchSecrets("LOGS", retrieve(httpState, RetrieveType.LOGS, null));
        String logEntries = retrieve(httpState, RetrieveType.LOGS, "LOG_ENTRIES");
        assertNoMismatchSecrets("LOGS LOG_ENTRIES", logEntries);
        assertThat(logEntries, containsString("\"because\""));
    }

    @Test
    public void shouldRedactTheMismatchBecauseUnderStaticRedaction() throws Exception {
        enableStaticRedaction();
        List<LogEntry> mismatches = logMismatches(newHttpState(configuration().logLevel("INFO")));

        for (LogEntry mismatch : mismatches) {
            assertNoMismatchSecrets("because", "because:" + mismatch.getBecause(null));
            assertNoMismatchSecrets("message", mismatch.getMessage());
            assertNoMismatchSecrets("compact message", mismatch.getCompactMessage());
            assertNoMismatchSecrets("log_entries JSON", new LogEntrySerializer(new MockServerLogger()).serialize(mismatch));
        }
    }

    @Test
    public void shouldLeaveTheMismatchBecauseUnchangedWhenRedactionIsOff() throws Exception {
        Configuration off = configuration().logLevel("INFO").redactSecretsInLog(false);
        HttpState httpState = newHttpState(off);
        List<LogEntry> mismatches = logMismatches(httpState);

        for (LogEntry mismatch : mismatches) {
            assertThat(mismatch.getBecause(off), is(mismatch.getBecause()));
            assertThat(mismatch.getMessage(off), is(mismatch.getMessage()));
            assertThat(mismatch.getCompactMessage(off), is(mismatch.getCompactMessage()));
        }
        String logs = retrieve(httpState, RetrieveType.LOGS, "LOG_ENTRIES");
        for (String secret : MISMATCH_SECRETS) {
            assertThat(logs, containsString(secret));
        }
    }

    @Test
    public void shouldRedactExplainUnmatchedDifferences() {
        HttpState redacting = newHttpState(redactingInstance());
        logUnmatched(redacting);
        HttpState off = newHttpState(configuration().redactSecretsInLog(false));
        logUnmatched(off);

        String redacted = explainUnmatched(redacting);
        assertThat(redacted, containsString("differences"));
        assertNoMismatchSecrets("explainUnmatched", redacted);
        assertThat(explainUnmatched(off), containsString("AUTHZ-SECRET-1"));
    }

    private static void logUnmatched(HttpState httpState) {
        httpState.add(new Expectation(request(PATH).withHeader("Authorization", "Bearer expected").withQueryStringParameter("key", "expected").withCookie("session", "expected")).thenRespond(response("a")));
        httpState.log(new LogEntry()
            .setType(NO_MATCH_RESPONSE)
            .setLogLevel(Level.INFO)
            .setHttpRequest(nonMatchingRequest())
            .setHttpResponse(response().withStatusCode(404))
            .setMessageFormat("no expectation for:{}returning response:{}")
            .setArguments(nonMatchingRequest(), response().withStatusCode(404)));
    }

    private static String explainUnmatched(HttpState httpState) {
        return httpState.explainUnmatched(request("/mockserver/explainUnmatched").withMethod("PUT")).getBodyAsString();
    }

    @Test
    public void shouldRedactExplainDifferences() throws Exception {
        HttpState redacting = newHttpState(redactingInstance());
        logReceived(redacting);
        HttpState off = newHttpState(configuration().redactSecretsInLog(false));
        logReceived(off);

        String redacted = explain(redacting);
        assertThat(redacted, containsString("differences"));
        assertNoMismatchSecrets("explain", redacted);
        assertThat(explain(off), containsString("AUTHZ-SECRET-1"));
    }

    private static void logReceived(HttpState httpState) {
        httpState.add(new Expectation(request(PATH).withHeader("Authorization", "Bearer expected").withQueryStringParameter("key", "expected").withCookie("session", "expected")).thenRespond(response("a")));
        httpState.log(new LogEntry()
            .setType(RECEIVED_REQUEST)
            .setLogLevel(Level.INFO)
            .setCorrelationId("explain-redaction")
            .setHttpRequest(nonMatchingRequest()));
    }

    private static String explain(HttpState httpState) throws InterruptedException {
        HttpRequest explainRequest = request("/mockserver/explain").withMethod("PUT").withQueryStringParameter("correlationId", "explain-redaction");
        long deadline = System.currentTimeMillis() + 5000;
        HttpResponse response = httpState.explain(explainRequest);
        while (response.getStatusCode() == 404 && System.currentTimeMillis() < deadline) {
            Thread.sleep(20);
            response = httpState.explain(explainRequest);
        }
        return response.getBodyAsString();
    }

    // ---- matcher differences at TRACE, and arguments nested in collections --------------------------

    @Test
    public void shouldScrubMatcherDifferenceArgumentsOfAnyType() {
        LogEntry difference = new LogEntry()
            .setLogLevel(Level.TRACE)
            .setHttpRequest(nonMatchingRequest())
            .setMessageFormat("exact string match failed expected:{}found:{}")
            .setArguments(NottableString.string("Bearer expected"), NottableString.string("Bearer AUTHZ-SECRET-1"));

        assertNoMismatchSecrets("TRACE message", difference.getMessage(redactingInstance()));
        assertNoMismatchSecrets("TRACE log_entries JSON", new LogEntrySerializer(new MockServerLogger(), redactingInstance()).serialize(difference));
        assertThat(difference.getMessage(configuration().redactSecretsInLog(false)), containsString("AUTHZ-SECRET-1"));
    }

    @Test
    public void shouldScrubCredentialsConcatenatedIntoTheMessageFormat() {
        // some formats are built by concatenation, e.g. "exception ... - " + throwable.getMessage()
        LogEntry entry = new LogEntry()
            .setLogLevel(Level.WARN)
            .setHttpRequest(nonMatchingRequest())
            .setMessageFormat("upstream rejected Bearer AUTHZ-SECRET-1 for request:{}")
            .setArguments(nonMatchingRequest());

        assertNoMismatchSecrets("message", entry.getMessage(redactingInstance()));
        assertNoMismatchSecrets("compact message", entry.getCompactMessage(redactingInstance()));
        assertThat(entry.getMessage(configuration().redactSecretsInLog(false)), containsString("AUTHZ-SECRET-1"));
    }

    // ---- a request concatenated into the format, no arguments, and an exception quoting it --------------
    // the exact shape HttpForwardAction, HttpRequestHandler and the servlets logged before this change

    private static final List<String> CONCATENATED_SECRETS = Arrays.asList("AUTHZ-SECRET-1", "QUERY-SECRET-5", "COOKIE-SECRET-4");

    private static LogEntry concatenatedRequestEntry() {
        HttpRequest request = nonMatchingRequest().withHeader("Host", "upstream.example.com:abc");
        IllegalArgumentException thrown = new IllegalArgumentException("wrapped",
            new IllegalArgumentException("Host header must be provided to determine remote socket address, the request does not include the \"Host\" header:" + NEW_LINE + request));
        return new LogEntry()
            .setType(LogEntry.LogMessageType.EXCEPTION)
            .setLogLevel(Level.ERROR)
            .setHttpRequest(request)
            .setMessageFormat("exception forwarding request " + request)
            .setThrowable(thrown);
    }

    private static void assertNoConcatenatedSecrets(String surface, String output) {
        assertThat(surface + " rendered nothing to check", output == null || output.isEmpty(), is(false));
        for (String secret : CONCATENATED_SECRETS) {
            assertThat(surface + " leaked " + secret + ":" + NEW_LINE + output, output, not(containsString(secret)));
        }
    }

    @Test
    public void shouldRedactARequestConcatenatedIntoTheFormatAndItsException() {
        Configuration configuration = redactingInstance().logLevel("INFO");
        LogEntry entry = concatenatedRequestEntry();

        assertNoConcatenatedSecrets("message", entry.getMessage(configuration));
        assertNoConcatenatedSecrets("compact message", entry.getCompactMessage(configuration));
        assertNoConcatenatedSecrets("message format", entry.getMessageFormat(configuration));
        assertNoConcatenatedSecrets("console", consoleError(entry, redactingInstance().logLevel("INFO").compactLogFormat(false)));
        assertNoConcatenatedSecrets("console compact", consoleError(entry, redactingInstance().logLevel("INFO").compactLogFormat(true)));
        String json = new LogEntrySerializer(new MockServerLogger(), configuration).serialize(entry);
        assertThat(json, containsString("\"messageFormat\""));
        assertThat(json, containsString("\"throwable\""));
        assertThat(json, containsString("IllegalArgumentException"));
        assertNoConcatenatedSecrets("log_entries JSON", json);
        assertThat("the stored throwable is never changed", String.valueOf(entry.getThrowable().getCause().getMessage()), containsString("AUTHZ-SECRET-1"));
    }

    @Test
    public void shouldRedactARequestConcatenatedIntoTheFormatUnderStaticRedaction() {
        enableStaticRedaction();
        LogEntry entry = concatenatedRequestEntry();

        assertNoConcatenatedSecrets("message", entry.getMessage());
        assertNoConcatenatedSecrets("compact message", entry.getCompactMessage());
        assertNoConcatenatedSecrets("log_entries JSON", new LogEntrySerializer(new MockServerLogger()).serialize(entry));
    }

    @Test
    public void shouldSerializeARedactedThrowableUnderPrivateFieldVisibility() throws Exception {
        // SloCriteriaSerializer widens the process-wide mapper to private-field access; a Throwable subclass that
        // is not a JDK class must still serialize there, or the whole LOG_ENTRIES retrieve fails. A fresh mapper,
        // so no serializer cached by an earlier test can hide a failure.
        com.fasterxml.jackson.databind.ObjectMapper widened = new com.fasterxml.jackson.databind.ObjectMapper()
            .setVisibility(com.fasterxml.jackson.annotation.PropertyAccessor.FIELD, com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.ANY);
        Throwable redacted = concatenatedRequestEntry().getThrowable(redactingInstance());
        assertThat(redacted, org.hamcrest.Matchers.instanceOf(RedactedThrowable.class));

        String json = widened.writeValueAsString(redacted);

        assertThat(json, containsString("java.lang.IllegalArgumentException: wrapped"));
        assertNoConcatenatedSecrets("throwable JSON", json);
    }

    @Test
    public void shouldLeaveAConcatenatedFormatAndItsExceptionUnchangedWhenRedactionIsOff() {
        Configuration off = configuration().logLevel("INFO").redactSecretsInLog(false);
        LogEntry entry = concatenatedRequestEntry();

        assertThat(entry.getMessage(off), is(entry.getMessageFormat()));
        assertThat(entry.getMessageFormat(off), is(entry.getMessageFormat()));
        assertThat(entry.getThrowable(off), is(org.hamcrest.Matchers.sameInstance(entry.getThrowable())));
        String json = new LogEntrySerializer(new MockServerLogger(), off).serialize(entry);
        for (String secret : CONCATENATED_SECRETS) {
            assertThat(json, containsString(secret));
        }
    }

    /**
     * What the console receives for an ERROR entry: the message and the full stack trace of the throwable passed.
     */
    private static String consoleError(LogEntry entry, Configuration configuration) {
        Logger logger = mock(Logger.class);
        when(logger.isErrorEnabled()).thenReturn(true);
        MockServerLogger.writeToSystemOut(logger, entry, configuration);
        ArgumentCaptor<String> message = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Throwable> throwable = ArgumentCaptor.forClass(Throwable.class);
        verify(logger).error(message.capture(), throwable.capture());
        return message.getValue() + NEW_LINE + org.apache.commons.lang3.exception.ExceptionUtils.getStackTrace(throwable.getValue());
    }

    // ---- a query string that cannot be decoded: logged with no decoded request to redact against --------

    private static final String UNDECODABLE_URI = "/v1/models?key=GEMINI-KEY-SECRET-12345&alt=%zz";

    /**
     * Logs the parse failure for {@link #UNDECODABLE_URI} and hands the entry to {@code check} while the server that
     * holds it is still running (stopping it clears the retained entries).
     */
    private static void withUndecodableQueryLogged(Configuration configuration, java.util.function.Consumer<LogEntry> check) throws Exception {
        HttpState httpState = null;
        ScheduledExecutorService executor = Executors.newScheduledThreadPool(1);
        try {
            Scheduler scheduler = mock(Scheduler.class);
            when(scheduler.getExecutorService()).thenReturn(executor);
            httpState = new HttpState(configuration, new MockServerLogger(configuration, MockServerLogger.class), scheduler);
            new org.mockserver.codec.ExpandedParameterDecoder(configuration, httpState.getMockServerLogger()).retrieveQueryParameters(UNDECODABLE_URI, true);
            CompletableFuture<List<LogEntry>> logged = new CompletableFuture<>();
            httpState.getMockServerLog().retrieveMessageLogEntries(null, logged::complete);
            List<LogEntry> entries = new ArrayList<>();
            for (LogEntry entry : logged.get()) {
                if (String.valueOf(entry.getMessageFormat()).contains("while parsing query string")) {
                    entries.add(entry);
                }
            }
            assertThat("the parse failure was logged", entries.size(), is(1));
            check.accept(entries.get(0));
        } finally {
            if (httpState != null) {
                httpState.stop();
            }
            executor.shutdownNow();
        }
    }

    @Test
    public void shouldRedactAnUndecodableQueryStringOnTheConsoleAndInLogEntries() throws Exception {
        Configuration configuration = redactingInstance().logLevel("INFO");
        withUndecodableQueryLogged(configuration, entry -> {
            String console = consoleError(entry, configuration);
            String json = new LogEntrySerializer(new MockServerLogger(), configuration).serialize(entry);

            assertThat(console, containsString("/v1/models"));
            assertThat("console leaked the query credential:" + NEW_LINE + console, console, not(containsString("GEMINI-KEY-SECRET-12345")));
            assertThat(json, containsString("\"throwable\""));
            assertThat("LOG_ENTRIES leaked the query credential:" + NEW_LINE + json, json, not(containsString("GEMINI-KEY-SECRET-12345")));
        });
    }

    @Test
    public void shouldLogAnUndecodableQueryStringAsBeforeWhenRedactionIsOff() throws Exception {
        Configuration off = configuration().logLevel("INFO").redactSecretsInLog(false);
        withUndecodableQueryLogged(off, entry -> {
            // unchanged with redaction off: the raw query string and the decoder's message, which quotes it
            assertThat(entry.getMessage(off), containsString(UNDECODABLE_URI));
            assertThat(entry.getThrowable(off).getMessage(), containsString("GEMINI-KEY-SECRET-12345"));
        });
    }

    // ---- a matcher quoting the value it compared, with no request attached ---------------------------

    @Test
    public void shouldMaskTheValueARegexMatcherQuotesWithoutARequest() {
        List<LogEntry> logged = new java.util.concurrent.CopyOnWriteArrayList<>();
        MockServerLogger logger = new MockServerLogger(configuration().logLevel("DEBUG"), LogEntryRedactionSurfacesTest.class) {
            @Override
            public void logEvent(LogEntry logEntry) {
                logged.add(logEntry);
            }
        };

        // the request value, read as a regex, would match the (literal) matcher: logged at DEBUG with no request
        String requestValue = "Bearer .*MATCHED-TOKEN-SECRET-1.*";
        new org.mockserver.matchers.RegexStringMatcher(logger, false).matches(NottableString.string("Bearer xMATCHED-TOKEN-SECRET-1x"), NottableString.string(requestValue));

        LogEntry entry = logged.stream().filter(e -> String.valueOf(e.getMessageFormat()).contains("would match")).findFirst().orElseThrow(AssertionError::new);
        assertThat(entry.getMessage(redactingInstance()), not(containsString(requestValue)));
        assertThat(entry.getCompactMessage(redactingInstance()), not(containsString(requestValue)));
        assertThat(new LogEntrySerializer(new MockServerLogger(), redactingInstance()).serialize(entry), not(containsString(requestValue)));
        assertThat(entry.getMessage(configuration().redactSecretsInLog(false)), containsString(requestValue));
    }

    // ---- the compact console form shortens arguments: redact before it does ----------------------------

    @Test
    public void shouldNotLeakAPrefixOfALongCredentialInTheCompactForm() {
        StringBuilder jwt = new StringBuilder("eyJhbGciOiJSUzI1NiIsInR5cCI6IkpXVCJ9.");
        while (jwt.length() < 471) {
            jwt.append("JWTSECRETPART");
        }
        String token = jwt.substring(0, 471);
        LogEntry entry = new LogEntry()
            .setLogLevel(Level.TRACE)
            .setHttpRequest(request(PATH).withHeader("Authorization", "Bearer " + token))
            .setMessageFormat("string or regex match failed expected:{}found:{}")
            .setArguments(NottableString.string("Bearer expected"), NottableString.string("Bearer " + token));

        String compact = entry.getCompactMessage(redactingInstance());
        String console = stdoutAt(entry, redactingInstance().logLevel("TRACE").compactLogFormat(true));

        for (String output : Arrays.asList(compact, console)) {
            assertThat(output, containsString(FixtureRedactor.REDACTED_PLACEHOLDER));
            assertThat("leaked a prefix of the token:" + NEW_LINE + output, output, not(containsString(token.substring(0, 20))));
        }
    }

    // ---- a cyclic cause / suppressed graph --------------------------------------------------------------

    @Test(timeout = 5_000)
    public void shouldRedactACyclicThrowableGraphInBoundedTime() {
        RuntimeException first = new RuntimeException("first AUTHZ-SECRET-1");
        RuntimeException second = new RuntimeException("second");
        first.initCause(second);
        second.initCause(first);
        first.addSuppressed(second);
        second.addSuppressed(first);
        LogEntry entry = new LogEntry()
            .setLogLevel(Level.ERROR)
            .setHttpRequest(nonMatchingRequest())
            .setMessageFormat("cyclic")
            .setThrowable(first);

        for (int i = 0; i < 1_000; i++) {
            Throwable redacted = entry.getThrowable(redactingInstance());
            assertThat(redacted, org.hamcrest.Matchers.instanceOf(RedactedThrowable.class));
        }
        Throwable redacted = entry.getThrowable(redactingInstance());
        String printed = org.apache.commons.lang3.exception.ExceptionUtils.getStackTrace(redacted);
        assertThat(printed, not(containsString("AUTHZ-SECRET-1")));
        assertThat(printed, containsString("second"));
        assertNoMismatchSecrets("cyclic throwable JSON", new LogEntrySerializer(new MockServerLogger(), redactingInstance()).serialize(entry));
    }

    // ---- a Netty message that could not be mapped ----------------------------------------------------

    @Test
    public void shouldRedactANettyRequestQuotedByAMappingFailure() {
        io.netty.handler.codec.http.DefaultFullHttpRequest nettyRequest = new io.netty.handler.codec.http.DefaultFullHttpRequest(
            io.netty.handler.codec.http.HttpVersion.HTTP_1_1, io.netty.handler.codec.http.HttpMethod.GET, "/v1/models?key=QUERY-SECRET-5&x=%zz");
        try {
            nettyRequest.headers().add("Authorization", "Bearer AUTHZ-SECRET-1").add("Cookie", "session=COOKIE-SECRET-4");
            HttpRequest forLog = org.mockserver.mappers.NettyMessageForLog.request(nettyRequest);
            LogEntry entry = new LogEntry()
                .setLogLevel(Level.ERROR)
                .setHttpRequest(forLog)
                .setMessageFormat("exception decoding request{}")
                .setArguments(nettyRequest)
                .setThrowable(new IllegalArgumentException("bad header Bearer AUTHZ-SECRET-1"));

            assertThat(forLog.getPath().getValue(), is("/v1/models"));
            assertThat(entry.getMessage(configuration().redactSecretsInLog(false)), containsString("AUTHZ-SECRET-1"));
            assertNoMismatchSecrets("message", entry.getMessage(redactingInstance()));
            assertNoMismatchSecrets("log_entries JSON", new LogEntrySerializer(new MockServerLogger(), redactingInstance()).serialize(entry));
            assertNoMismatchSecrets("console", consoleError(entry, redactingInstance().logLevel("INFO")));
        } finally {
            nettyRequest.release();
        }
    }

    // ---- a request passed only as an argument still supplies the values the rest of the text is scrubbed of ----

    @Test
    public void shouldScrubTextUsingARequestPassedOnlyAsAnArgument() {
        HttpRequest request = nonMatchingRequest();
        LogEntry direct = new LogEntry()
            .setLogLevel(Level.WARN)
            .setMessageFormat("failed{}due to:{}")
            .setArguments(request, "error quoting Bearer AUTHZ-SECRET-1 and session=COOKIE-SECRET-4 and key QUERY-SECRET-5")
            .setThrowable(new IllegalStateException("rejected Bearer AUTHZ-SECRET-1"));
        LogEntry nested = new LogEntry()
            .setLogLevel(Level.WARN)
            .setMessageFormat("failed{}due to:{}")
            .setArguments(Collections.singletonList(new LogEventRequestAndResponse().withHttpRequest(request)), "error quoting Bearer AUTHZ-SECRET-1 and QUERY-SECRET-5");
        LogEntry expectation = new LogEntry()
            .setLogLevel(Level.ERROR)
            .setMessageFormat("exception while serializing expectation to JSON with value:{}")
            .setArguments(new Expectation(request).thenRespond(response("ok")))
            .setThrowable(new IllegalStateException("could not write Bearer AUTHZ-SECRET-1"));

        for (LogEntry entry : Arrays.asList(direct, nested, expectation)) {
            assertNoMismatchSecrets("message", entry.getMessage(redactingInstance()));
            assertNoMismatchSecrets("compact message", entry.getCompactMessage(redactingInstance()));
            assertNoMismatchSecrets("log_entries JSON", new LogEntrySerializer(new MockServerLogger(), redactingInstance()).serialize(entry));
        }
        assertNoMismatchSecrets("console", consoleError(expectation, redactingInstance().logLevel("INFO")));
        assertThat(direct.getMessage(configuration().redactSecretsInLog(false)), containsString("AUTHZ-SECRET-1"));
    }

    // ---- cost: a verification failure can quote thousands of recorded requests ----------------------------

    @Test
    public void shouldRenderAVerificationFailureQuotingThousandsOfRequestsInLinearTime() {
        List<HttpRequest> recorded = new ArrayList<>();
        for (int i = 0; i < 5_000; i++) {
            recorded.add(request("/api/items/" + i)
                .withHeader("Authorization", "Bearer TOKEN-" + i + "-" + java.util.UUID.randomUUID())
                .withHeader("Cookie", "session=SESSION-" + i + "-" + java.util.UUID.randomUUID() + "; lang=en")
                .withBody("{\"id\":" + i + "}"));
        }
        Configuration on = redactingInstance();
        Configuration off = configuration().redactSecretsInLog(false);
        for (int warmUp = 0; warmUp < 2; warmUp++) {
            renderVerificationFailure(recorded, off);
            renderVerificationFailure(recorded, on);
        }

        long offNanos = medianRenderNanos(recorded, off);
        long onNanos = medianRenderNanos(recorded, on);

        // scrubbing costs the text's length, not values x length
        assertThat("rendered with redaction on in " + onNanos / 1_000_000 + "ms against " + offNanos / 1_000_000 + "ms off",
            onNanos <= 5 * offNanos + 250_000_000L, is(true));
        String rendered = renderVerificationFailure(recorded, on);
        assertThat(rendered, not(containsString(recorded.get(0).getFirstHeader("Authorization").substring("Bearer ".length()))));
        assertThat(rendered, containsString("/api/items/4999"));
    }

    @Test
    public void shouldRenderTheCurlOfALargeForwardedBodyInLinearTime() {
        StringBuilder body = new StringBuilder("[");
        for (int i = 0; i < 20_000; i++) {
            body.append(i == 0 ? "" : ",").append("{\"id\":").append(i).append(",\"password\":\"PW-").append(java.util.UUID.randomUUID()).append("\"}");
        }
        HttpRequest forwarded = request("/bulk").withMethod("POST").withHeader("Host", "example.com")
            .withHeader("Authorization", "Bearer AUTHZ-SECRET-1").withBody(json(body.append("]").toString()));
        java.util.function.Function<Configuration, String> render = configuration -> {
            LogEntry entry = new LogEntry()
                .setType(FORWARDED_REQUEST)
                .setLogLevel(Level.INFO)
                .setHttpRequest(forwarded)
                .setMessageFormat("returning response:{}for forwarded request in json:{} in curl:{}")
                .setArguments(response("ok"), forwarded, DeferredLogArgument.curl(curlSerializer, forwarded, UPSTREAM));
            return entry.getMessage(configuration) + new LogEntrySerializer(new MockServerLogger(), configuration).serialize(entry);
        };

        assertRedactedRenderWithinAFewTimesUnredacted(render);
        String rendered = render.apply(redactingInstance());
        assertThat(rendered, not(containsString("AUTHZ-SECRET-1")));
        assertThat(rendered, not(containsString("PW-")));
    }

    @Test
    public void shouldRenderAListOfManyExpectationsInLinearTime() {
        List<Expectation> expectations = new ArrayList<>();
        for (int i = 0; i < 4_000; i++) {
            expectations.add(new Expectation(request("/p/" + i).withHeader("Authorization", "Bearer TOKEN-" + i + "-" + java.util.UUID.randomUUID()))
                .thenRespond(response("ok").withHeader("Set-Cookie", "sid=SID-" + i + "-" + java.util.UUID.randomUUID())));
        }
        java.util.function.Function<Configuration, String> render = configuration -> {
            LogEntry entry = new LogEntry()
                .setLogLevel(Level.TRACE)
                .setMessageFormat("persisting expectations{}to{}")
                .setArguments(expectations, "/tmp/expectations.json");
            return entry.getMessage(configuration) + new LogEntrySerializer(new MockServerLogger(), configuration).serialize(entry);
        };

        assertRedactedRenderWithinAFewTimesUnredacted(render);
        String rendered = render.apply(redactingInstance());
        assertThat(rendered, not(containsString("TOKEN-0-")));
        assertThat(rendered, not(containsString("SID-0-")));
        assertThat(rendered, containsString("/p/3999"));
    }

    @Test
    public void shouldRedactRequestsInsideMapsAndOptionals() {
        HttpRequest request = nonMatchingRequest();
        java.util.Map<String, Object> map = new java.util.HashMap<>();
        map.put("request", request);
        for (LogEntry entry : Arrays.asList(
            new LogEntry().setLogLevel(Level.INFO).setMessageFormat("map:{}").setArguments(map),
            new LogEntry().setLogLevel(Level.INFO).setMessageFormat("optional:{}").setArguments(java.util.Optional.of(request))
        )) {
            assertNoMismatchSecrets("message", entry.getMessage(redactingInstance()));
            assertNoMismatchSecrets("log_entries JSON", new LogEntrySerializer(new MockServerLogger(), redactingInstance()).serialize(entry));
            assertThat(entry.getMessage(configuration().redactSecretsInLog(false)), containsString("AUTHZ-SECRET-1"));
        }
    }

    @Test
    public void shouldRenderANestedSensitiveBodyInBoundedTimeAndMemory() {
        StringBuilder body = new StringBuilder("{\"password\":");
        int depth = 900;
        for (int i = 0; i < depth; i++) {
            body.append("{\"a\":");
        }
        body.append("\"LEAF-SECRET-");
        for (int i = 0; i < 300; i++) {
            body.append((char) ('a' + i % 26));
        }
        body.append('"');
        for (int i = 0; i < depth; i++) {
            body.append('}');
        }
        HttpRequest forwarded = request("/nested").withMethod("POST").withHeader("Host", "example.com")
            .withHeader("Authorization", "Bearer AUTHZ-SECRET-1").withBody(json(body.append(",\"x\":\"filler\"}").toString()));

        java.util.function.Function<Configuration, String> render = forwardedRender(forwarded);
        assertRedactedRenderWithinAFewTimesUnredacted(render);
        assertRedactedRenderAllocatesAboutAsMuchAsUnredacted(render);
        String rendered = render.apply(redactingInstance());
        assertThat(rendered, not(containsString("AUTHZ-SECRET-1")));
        assertThat(rendered, not(containsString("LEAF-SECRET-")));
    }

    @Test
    public void shouldRenderALargeFlatSensitiveBodyInBoundedTimeAndMemory() {
        java.util.Random random = new java.util.Random(1L);
        StringBuilder body = new StringBuilder("{\"password\":[");
        List<String> values = new ArrayList<>();
        for (int i = 0; i < 40_000; i++) {
            StringBuilder value = new StringBuilder();
            for (int j = 0; j < 24; j++) {
                value.append((char) ('a' + random.nextInt(26)));
            }
            values.add(value.toString());
            body.append(i == 0 ? "\"" : ",\"").append(value).append('"');
        }
        HttpRequest forwarded = request("/flat").withMethod("POST").withHeader("Host", "example.com")
            .withHeader("Authorization", "Bearer AUTHZ-SECRET-1").withBody(json(body.append("],\"x\":\"filler\"}").toString()));

        java.util.function.Function<Configuration, String> render = forwardedRender(forwarded);
        assertRedactedRenderWithinAFewTimesUnredacted(render);
        assertRedactedRenderAllocatesAboutAsMuchAsUnredacted(render);
        String rendered = render.apply(redactingInstance());
        assertThat(rendered, not(containsString("AUTHZ-SECRET-1")));
        // beyond the values searched for in free text, but masked in the body field (and so the cURL) it came from
        assertThat(rendered, not(containsString(values.get(0))));
        assertThat(rendered, not(containsString(values.get(values.size() - 1))));
    }

    private java.util.function.Function<Configuration, String> forwardedRender(HttpRequest forwarded) {
        return configuration -> {
            LogEntry entry = new LogEntry()
                .setType(FORWARDED_REQUEST)
                .setLogLevel(Level.INFO)
                .setHttpRequest(forwarded)
                .setMessageFormat("returning response:{}for forwarded request in json:{} in curl:{}")
                .setArguments(response("ok"), forwarded, DeferredLogArgument.curl(curlSerializer, forwarded, UPSTREAM));
            return entry.getMessage(configuration) + new LogEntrySerializer(new MockServerLogger(), configuration).serialize(entry);
        };
    }

    /**
     * The credential values searched for in free text are bounded (see SensitiveValueMatcher), so the memory a render
     * allocates with redaction on stays close to what it allocates with it off, whatever the body carries.
     */
    private static void assertRedactedRenderAllocatesAboutAsMuchAsUnredacted(java.util.function.Function<Configuration, String> render) {
        java.lang.management.ThreadMXBean threads = java.lang.management.ManagementFactory.getThreadMXBean();
        org.junit.Assume.assumeTrue("thread allocation is not measurable on this JVM", threads instanceof com.sun.management.ThreadMXBean
            && ((com.sun.management.ThreadMXBean) threads).isThreadAllocatedMemorySupported());
        com.sun.management.ThreadMXBean allocation = (com.sun.management.ThreadMXBean) threads;
        Configuration on = redactingInstance();
        Configuration off = configuration().redactSecretsInLog(false);
        long[] offBytes = new long[3];
        long[] onBytes = new long[3];
        for (int i = 0; i < offBytes.length; i++) {
            long start = allocation.getCurrentThreadAllocatedBytes();
            render.apply(off);
            offBytes[i] = allocation.getCurrentThreadAllocatedBytes() - start;
            start = allocation.getCurrentThreadAllocatedBytes();
            render.apply(on);
            onBytes[i] = allocation.getCurrentThreadAllocatedBytes() - start;
        }
        Arrays.sort(offBytes);
        Arrays.sort(onBytes);
        assertThat("allocated " + onBytes[1] / 1_000_000 + "MB rendering with redaction on against " + offBytes[1] / 1_000_000 + "MB off",
            onBytes[1] <= offBytes[1] * 3 / 2 + 32_000_000L, is(true));
    }

    private static void assertRedactedRenderWithinAFewTimesUnredacted(java.util.function.Function<Configuration, String> render) {
        Configuration on = redactingInstance();
        Configuration off = configuration().redactSecretsInLog(false);
        for (int warmUp = 0; warmUp < 2; warmUp++) {
            render.apply(off);
            render.apply(on);
        }
        long offNanos = medianNanos(() -> render.apply(off));
        long onNanos = medianNanos(() -> render.apply(on));
        assertThat("rendered with redaction on in " + onNanos / 1_000_000 + "ms against " + offNanos / 1_000_000 + "ms off",
            onNanos <= 5 * offNanos + 250_000_000L, is(true));
    }

    private static long medianNanos(Runnable render) {
        long[] nanos = new long[3];
        for (int i = 0; i < nanos.length; i++) {
            long start = System.nanoTime();
            render.run();
            nanos[i] = System.nanoTime() - start;
        }
        Arrays.sort(nanos);
        return nanos[1];
    }

    private static long medianRenderNanos(List<HttpRequest> recorded, Configuration configuration) {
        long[] nanos = new long[3];
        for (int i = 0; i < nanos.length; i++) {
            long start = System.nanoTime();
            renderVerificationFailure(recorded, configuration);
            nanos[i] = System.nanoTime() - start;
        }
        Arrays.sort(nanos);
        return nanos[1];
    }

    /**
     * The message and the JSON log entry of a request verification failure, as MockServerEventLog logs it; a new entry
     * each time, so the message memo does not hide the work.
     */
    private static String renderVerificationFailure(List<HttpRequest> recorded, Configuration configuration) {
        LogEntry entry = new LogEntry()
            .setType(LogEntry.LogMessageType.VERIFICATION_FAILED)
            .setLogLevel(Level.INFO)
            .setHttpRequest(request("/api/missing"))
            .setMessageFormat("request not found once, expected:{}but was:{}")
            .setArguments(request("/api/missing"), recorded);
        return entry.getMessage(configuration) + new LogEntrySerializer(new MockServerLogger(), configuration).serialize(entry);
    }

    @Test
    public void shouldRedactRequestsAndResponsesNestedInArguments() {
        HttpRequest request = proxiedRequest().removeHeader("Proxy-Authorization");
        HttpResponse response = proxiedResponse();
        LogEntry entry = new LogEntry()
            .setType(LogEntry.LogMessageType.VERIFICATION_FAILED)
            .setLogLevel(Level.INFO)
            .setMessageFormat("expected:{}but was:{}and:{}")
            .setArguments(Collections.singletonList(request), new Object[]{response},
                new LogEventRequestAndResponse().withHttpRequest(request).withHttpResponse(response));

        assertNoSecrets("nested arguments", new LogEntrySerializer(new MockServerLogger(), redactingInstance()).serialize(entry));
        assertNoSecrets("nested message", entry.getMessage(redactingInstance()));
        assertAllOriginSecrets("nested arguments with redaction off", new LogEntrySerializer(new MockServerLogger(), configuration().redactSecretsInLog(false)).serialize(entry));
    }

    // ---- failed response verification: the failure returned, and the entry logged -------------------

    private static final List<String> VERIFY_SECRETS = Arrays.asList("AUTHZ-SECRET-1", "COOKIE-SECRET-4", "SETCOOKIE-SECRET-7", "RESPBODY-SECRET-8");

    @Test
    public void shouldRedactAFailedResponseVerification() throws Exception {
        HttpState httpState = newHttpState(redactingInstance().logLevel("INFO").detailedVerificationFailures(true));
        httpState.log(forwardedEntry());

        String failure = httpState.verify(new Verification().withRequest(request(PATH)).withResponse(response().withStatusCode(500)).withTimes(VerificationTimes.once())).get();

        assertThat(failure, containsString("Response not found"));
        assertNoVerifySecrets("verification failure", failure);
        String logs = retrieve(httpState, RetrieveType.LOGS, "LOG_ENTRIES");
        assertThat(logs, containsString("response not found"));
        assertNoVerifySecrets("LOGS LOG_ENTRIES", logs);
        assertNoVerifySecrets("LOGS", retrieve(httpState, RetrieveType.LOGS, null));
    }

    @Test
    public void shouldRedactAFailedResponseSequenceVerification() throws Exception {
        HttpState httpState = newHttpState(redactingInstance().logLevel("INFO").detailedVerificationFailures(true));
        httpState.log(forwardedEntry());

        String failure = httpState.verify(new VerificationSequence().withResponses(response().withStatusCode(500))).get();

        assertThat(failure, containsString("Response sequence not found"));
        assertNoVerifySecrets("verification sequence failure", failure);
        String logs = retrieveUnfiltered(httpState);
        assertThat(logs, containsString("response sequence not found"));
        assertNoVerifySecrets("LOGS", logs);
    }

    @Test
    public void shouldLeaveAFailedResponseVerificationUnchangedWhenRedactionIsOff() throws Exception {
        HttpState httpState = newHttpState(configuration().logLevel("INFO").redactSecretsInLog(false).detailedVerificationFailures(true));
        httpState.log(forwardedEntry());

        String failure = httpState.verify(new Verification().withRequest(request(PATH)).withResponse(response().withStatusCode(500)).withTimes(VerificationTimes.once())).get();
        String sequenceFailure = httpState.verify(new VerificationSequence().withResponses(response().withStatusCode(500))).get();

        assertThat(failure, containsString("SETCOOKIE-SECRET-7"));
        assertThat(sequenceFailure, containsString("SETCOOKIE-SECRET-7"));
    }

    private static void assertNoVerifySecrets(String surface, String output) {
        for (String secret : VERIFY_SECRETS) {
            assertThat(surface + " leaked " + secret + ":" + NEW_LINE + output, output, not(containsString(secret)));
        }
    }

    private static String retrieveUnfiltered(HttpState httpState) {
        CapturingResponseWriter responseWriter = new CapturingResponseWriter();
        assertThat(httpState.handle(request("/mockserver/retrieve").withMethod("PUT").withQueryStringParameter("type", "LOGS"), responseWriter, false), is(true));
        assertThat(responseWriter.response.getStatusCode(), is(200));
        return responseWriter.response.getBodyAsString();
    }

    private void assertEveryRetrieveSurfaceRedacted(HttpState httpState) {
        logProxiedExchange(httpState);

        assertNoSecrets("LOGS", retrieve(httpState, RetrieveType.LOGS, null));
        assertNoSecrets("LOGS LOG_ENTRIES", retrieve(httpState, RetrieveType.LOGS, "LOG_ENTRIES"));
        for (String format : Arrays.asList("JSON", "HAR", "CURL")) {
            assertNoSecrets("REQUESTS " + format, retrieve(httpState, RetrieveType.REQUESTS, format));
        }
        for (String format : Arrays.asList("JSON", "HAR")) {
            assertNoSecrets("REQUEST_RESPONSES " + format, retrieve(httpState, RetrieveType.REQUEST_RESPONSES, format));
        }
        assertNoSecrets("logEntryBody", logEntryBodies(httpState));
    }

    /**
     * The forwarded entry's request and response as GET /mockserver/logEntryBody returns them, which is how the
     * dashboard loads a body it shortened.
     */
    private static String logEntryBodies(HttpState httpState) {
        try {
            CompletableFuture<List<LogEntry>> logged = new CompletableFuture<>();
            httpState.getMockServerLog().retrieveMessageLogEntries(null, logged::complete);
            String id = logged.get(10, java.util.concurrent.TimeUnit.SECONDS).stream()
                .filter(entry -> entry.getType() == FORWARDED_REQUEST)
                .findFirst()
                .orElseThrow(() -> new AssertionError("no forwarded entry logged"))
                .id();
            StringBuilder bodies = new StringBuilder();
            for (String part : Arrays.asList("request", "response")) {
                CapturingResponseWriter responseWriter = new CapturingResponseWriter();
                HttpRequest get = request("/mockserver/logEntryBody").withMethod("GET")
                    .withQueryStringParameter("id", id).withQueryStringParameter("part", part);
                assertThat(httpState.handle(get, responseWriter, false), is(true));
                assertThat("logEntryBody " + part + " status", responseWriter.response.getStatusCode(), is(200));
                bodies.append(responseWriter.response.getBodyAsString());
            }
            return bodies.toString();
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    private HttpState newHttpState(Configuration configuration) {
        Scheduler scheduler = mock(Scheduler.class);
        when(scheduler.getExecutorService()).thenReturn(executor);
        HttpState httpState = new HttpState(configuration, new MockServerLogger(configuration, MockServerLogger.class), scheduler);
        httpStates.add(httpState);
        return httpState;
    }

    private static String retrieve(HttpState httpState, RetrieveType type, String format) {
        HttpRequest retrieve = request("/mockserver/retrieve")
            .withMethod("PUT")
            .withQueryStringParameter("type", type.name())
            .withBody(new RequestDefinitionSerializer(new MockServerLogger()).serialize(request(PATH)));
        if (format != null) {
            retrieve.withQueryStringParameter("format", format);
        }
        CapturingResponseWriter responseWriter = new CapturingResponseWriter();
        assertThat(httpState.handle(retrieve, responseWriter, false), is(true));
        assertThat(type + " " + format + " status", responseWriter.response.getStatusCode(), is(200));
        String body = responseWriter.response.getBodyAsString();
        assertThat(type + " " + format + " should include the exchange:" + NEW_LINE + body, body, containsString(PATH));
        return body;
    }

    private static String stdout(LogEntry entry, Configuration configuration) {
        Logger logger = mock(Logger.class);
        when(logger.isInfoEnabled()).thenReturn(true);
        MockServerLogger.writeToSystemOut(logger, entry, configuration);
        ArgumentCaptor<String> message = ArgumentCaptor.forClass(String.class);
        verify(logger).info(message.capture(), nullable(Throwable.class));
        return message.getValue();
    }

    private static String stdoutAt(LogEntry entry, Configuration configuration) {
        Logger logger = mock(Logger.class);
        when(logger.isTraceEnabled()).thenReturn(true);
        MockServerLogger.writeToSystemOut(logger, entry, configuration);
        ArgumentCaptor<String> message = ArgumentCaptor.forClass(String.class);
        verify(logger).trace(message.capture(), nullable(Throwable.class));
        return message.getValue();
    }

    private static class CapturingResponseWriter extends ResponseWriter {
        private volatile HttpResponse response;

        CapturingResponseWriter() {
            super(configuration(), new MockServerLogger());
        }

        @Override
        public void sendResponse(HttpRequest request, HttpResponse response) {
            this.response = response;
        }
    }
}
