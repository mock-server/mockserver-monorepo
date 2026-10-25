package org.mockserver.netty.integration;

import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.handler.codec.http2.DefaultHttp2Headers;
import io.netty.handler.codec.http2.Http2Headers;
import org.junit.After;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.mockserver.client.MockServerClient;
import org.mockserver.extension.FilterContext;
import org.mockserver.extension.FilterResult;
import org.mockserver.extension.RequestFilter;
import org.mockserver.model.ContinuationThrowingHeaders;
import org.mockserver.model.HttpRequest;
import org.mockserver.netty.MockServer;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.lessThan;
import static org.hamcrest.Matchers.startsWith;
import static org.mockserver.configuration.Configuration.configuration;
import static org.mockserver.model.HttpRequest.request;
import static org.mockserver.model.HttpResponse.response;
import static org.mockserver.netty.integration.PipelinedHttp1Client.get;
import static org.mockserver.netty.integration.PipelinedHttp1Client.rawRequest;
import static org.mockserver.stop.Stop.stopQuietly;

/**
 * The extension request filter (H1) on Netty HTTP/1.1 and HTTP/2: where it runs, what it may change, how its answers
 * and failures reach the client, and what the event log records.
 */
public class RequestFilterIntegrationTest {

    private static final ScriptedFilter FILTER = new ScriptedFilter();
    private static final long SLOW_FILTER_MILLIS = 1_500;

    private static MockServer mockServer;
    private static MockServerClient mockServerClient;
    private static EventLoopGroup http2Group;

    @BeforeClass
    public static void startServer() {
        mockServer = new MockServer(configuration()
            .logLevel("INFO")
            .attemptToProxyIfNoMatchingExpectation(false)
            // one event loop, so a filter that ran on it would stall every other connection
            .nioEventLoopThreadCount(1)
            .extensionDataPlaneTimeoutMillis(10_000L)
            .extensions(() -> FILTER), 0);
        mockServerClient = new MockServerClient("127.0.0.1", mockServer.getLocalPort());
        http2Group = new NioEventLoopGroup(1);
    }

    @AfterClass
    public static void stopServer() {
        if (http2Group != null) {
            http2Group.shutdownGracefully(0, 1, TimeUnit.SECONDS).syncUninterruptibly();
        }
        stopQuietly(mockServerClient);
        stopQuietly(mockServer);
    }

    @Before
    public void reset() {
        FILTER.reset();
        mockServerClient.reset();
    }

    @After
    public void checkFilterThreads() {
        assertThat("the filter must only run on the extension pool", FILTER.threads, everyItem(startsWith("MockServer-ext-dataplane-")));
    }

    @Test
    public void shouldKeepPipelinedResponsesInOrderWhenTheFilterAnswersTheFirst() throws Exception {
        FILTER.script = (request, context) -> {
            if (path(request).equals("/answered")) {
                Thread.sleep(300);
                return FilterResult.respond(response().withStatusCode(201).withBody("answered by the filter"));
            }
            return FilterResult.proceed();
        };
        mockServerClient.when(request().withPath("/second")).respond(response().withBody("second"));

        try (PipelinedHttp1Client client = PipelinedHttp1Client.plain(mockServer.getLocalPort())) {
            client.pipeline(get("/answered"), get("/second"));
            List<PipelinedHttp1Client.Response> responses = client.readResponses(false, false);

            assertThat(responses.get(0).status, is(201));
            assertThat(responses.get(0).body(), is("answered by the filter"));
            assertThat(responses.get(1).status, is(200));
            assertThat(responses.get(1).body(), is("second"));
        }
        assertThat(FILTER.paths, contains("/answered", "/second"));
        List<String> answeredLog = awaitLog("/answered", entries -> entries.stream().anyMatch(entry -> entry.contains("returned by request filter")));
        assertThat(count(answeredLog, "received request:"), is(1));
        assertThat(count(answeredLog, "returned by request filter:"), is(1));
    }

    @Test
    public void shouldAnswerAContinuationThatThrowsAtOnceAndServeTheNextPipelinedRequest() throws Exception {
        FILTER.script = (request, context) -> path(request).equals("/throws")
            // changed by value too, or the result would be dropped for the unchanged input
            ? FilterResult.proceedWith(request.withHeaders(new ContinuationThrowingHeaders(request.withHeader("x-throws", "yes").getHeaders())))
            : FilterResult.proceed();
        mockServerClient.when(request().withPath("/second")).respond(response().withBody("second"));

        long start = System.nanoTime();
        try (PipelinedHttp1Client client = PipelinedHttp1Client.plain(mockServer.getLocalPort())) {
            client.pipeline(get("/throws"), get("/second"));
            List<PipelinedHttp1Client.Response> responses = client.readResponses(false, false);

            assertThat(responses.get(0).status, is(500));
            assertThat(responses.get(0).body(), containsString("see the MockServer log for correlation id"));
            assertThat(responses.get(1).status, is(200));
            assertThat(responses.get(1).body(), is("second"));
        }
        assertThat("answered at once, not after a timeout", TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start), lessThan(5_000L));
    }

    @Test
    public void shouldNotRunTheFilterOnTheEventLoop() throws Exception {
        CountDownLatch slowFilterEntered = new CountDownLatch(1);
        FILTER.script = (request, context) -> {
            if (path(request).equals("/slow")) {
                slowFilterEntered.countDown();
                Thread.sleep(SLOW_FILTER_MILLIS);
            }
            return FilterResult.proceed();
        };
        mockServerClient.when(request().withPath("/slow")).respond(response().withBody("slow"));
        mockServerClient.when(request().withPath("/fast")).respond(response().withBody("fast"));

        try (PipelinedHttp1Client slowClient = PipelinedHttp1Client.plain(mockServer.getLocalPort());
             PipelinedHttp1Client fastClient = PipelinedHttp1Client.plain(mockServer.getLocalPort())) {
            slowClient.pipeline(get("/slow"));
            assertThat(slowFilterEntered.await(10, TimeUnit.SECONDS), is(true));

            long start = System.nanoTime();
            fastClient.pipeline(get("/fast"));
            PipelinedHttp1Client.Response fast = fastClient.readResponse();
            long fastMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);

            assertThat(fast.body(), is("fast"));
            assertThat("the only event loop was free while the slow filter ran", fastMillis, lessThan(SLOW_FILTER_MILLIS / 2));
            assertThat(slowClient.readResponse().body(), is("slow"));
        }
    }

    @Test
    public void shouldFilterAnUnservedControlPlanePathThatReachesMatching() throws Exception {
        mockServerClient.when(request()).respond(response().withBody("caught"));

        try (PipelinedHttp1Client client = PipelinedHttp1Client.plain(mockServer.getLocalPort())) {
            client.pipeline(get("/mockserver/x"));
            assertThat(client.readResponse().body(), is("caught"));
        }
        assertThat(FILTER.paths, contains("/mockserver/x"));
    }

    @Test
    public void shouldNotFilterAControlPlaneRequest() throws Exception {
        try (PipelinedHttp1Client client = PipelinedHttp1Client.plain(mockServer.getLocalPort())) {
            client.pipeline(rawRequest("PUT", "/mockserver/status"));
            assertThat(client.readResponse().status, is(200));
        }
        assertThat(FILTER.paths, is(empty()));
    }

    @Test
    public void shouldNotFilterACorsPreflightForAControlPlaneRoute() throws Exception {
        try (PipelinedHttp1Client client = PipelinedHttp1Client.plain(mockServer.getLocalPort())) {
            client.pipeline(rawRequest("OPTIONS", "/mockserver/expectation", "Origin: http://localhost:4000", "Access-Control-Request-Method: PUT"));
            assertThat(client.readResponse().status, is(200));
        }
        // answered by processAction's control-plane preflight branch, which is the only place logging this
        awaitAnyLogEntry(entry -> entry.contains("returning CORS response for OPTIONS request"));
        assertThat(FILTER.paths, is(empty()));
    }

    @Test
    public void shouldNeverFilterARequestRefusedForWantingMutualTls() throws Exception {
        ScriptedFilter mutualTlsFilter = new ScriptedFilter();
        MockServer mutualTlsServer = new MockServer(configuration()
            .attemptToProxyIfNoMatchingExpectation(false)
            .tlsMutualAuthenticationRequired(true)
            // a throwaway test server: no client certificate is ever presented
            .tlsMutualAuthenticationAllowBundledCa(true)
            .extensions(() -> mutualTlsFilter), 0);
        try (PipelinedHttp1Client client = PipelinedHttp1Client.plain(mutualTlsServer.getLocalPort())) {
            client.pipeline(get("/needs-mutual-tls"));
            assertThat(client.readResponse().status, is(426));
        } finally {
            stopQuietly(mutualTlsServer);
        }
        assertThat(mutualTlsFilter.paths, is(empty()));
    }

    @Test
    public void shouldRefuseAFilterThatMovesARequestOntoTheControlPlane() throws Exception {
        FILTER.script = (request, context) -> FilterResult.proceedWith(request.withPath("/mockserver/clear"));
        mockServerClient.when(request().withPath("/kept")).respond(response().withBody("kept"));

        try (PipelinedHttp1Client client = PipelinedHttp1Client.plain(mockServer.getLocalPort())) {
            client.pipeline(rawRequest("PUT", "/data"));
            PipelinedHttp1Client.Response refused = client.readResponse();
            assertThat(refused.status, is(500));
            assertThat(refused.body(), containsString("extension scripted requestFilter failed (contract_violation)"));
        }
        // nothing was cleared: the control plane never saw the rewritten request
        FILTER.script = (request, context) -> FilterResult.proceed();
        try (PipelinedHttp1Client client = PipelinedHttp1Client.plain(mockServer.getLocalPort())) {
            client.pipeline(get("/kept"));
            assertThat(client.readResponse().body(), is("kept"));
        }
        List<String> log = awaitLog("/data", entries -> entries.stream().anyMatch(entry -> entry.contains("contract_violation")));
        assertThat(count(log, "received request:"), is(1));
    }

    @Test
    public void shouldLogTheFilteredRequestOnceAndTheOriginalOnlyWhenChanged() throws Exception {
        FILTER.script = (request, context) -> path(request).equals("/changed")
            ? FilterResult.proceedWith(request.withHeader("x-added-by-filter", "yes"))
            : FilterResult.proceedWith(request);
        mockServerClient.when(request().withPath("/changed").withHeader("x-added-by-filter", "yes")).respond(response().withBody("matched the filtered request"));
        mockServerClient.when(request().withPath("/unchanged")).respond(response().withBody("unchanged"));

        try (PipelinedHttp1Client client = PipelinedHttp1Client.plain(mockServer.getLocalPort())) {
            client.pipeline(get("/changed"));
            assertThat(client.readResponse().body(), is("matched the filtered request"));
            client.pipeline(get("/unchanged"));
            assertThat(client.readResponse().body(), is("unchanged"));
        }

        List<String> changedLog = awaitLog("/changed", entries -> count(entries, "request filtered by:") > 0 && count(entries, "returning response:") > 0);
        assertThat(count(changedLog, "received request:"), is(1));
        assertThat(changedLog.stream().filter(entry -> entry.contains("received request:")).findFirst().orElseThrow(), containsString("x-added-by-filter"));
        assertThat(count(changedLog, "request filtered by:"), is(1));
        List<String> unchangedLog = awaitLog("/unchanged", entries -> count(entries, "returning response:") > 0);
        assertThat(count(unchangedLog, "received request:"), is(1));
        assertThat(count(unchangedLog, "request filtered by:"), is(0));
    }

    @Test
    public void shouldAnswerFromTheFilterOverHttp2() throws Exception {
        FILTER.script = (request, context) -> FilterResult.respond(response().withStatusCode(202).withBody("answered over h2"));

        try (Http2TestClient client = Http2TestClient.h2c(http2Group, mockServer.getLocalPort())) {
            Http2Headers headers = new DefaultHttp2Headers().method("GET").scheme("http").authority("localhost").path("/h2");
            Http2TestClient.Exchange exchange = client.send(headers, true);
            assertThat(exchange.status(), is(202));
            assertThat(exchange.body(), is("answered over h2"));
        }
        assertThat(FILTER.protocols, contains("HTTP_2"));
    }

    @Test
    public void shouldAnswerAFilterFailureOverHttp2() throws Exception {
        FILTER.script = (request, context) -> {
            throw new IllegalStateException("filter failed");
        };

        try (Http2TestClient client = Http2TestClient.h2c(http2Group, mockServer.getLocalPort())) {
            Http2Headers headers = new DefaultHttp2Headers().method("GET").scheme("http").authority("localhost").path("/h2-failure");
            Http2TestClient.Exchange exchange = client.send(headers, true);
            assertThat(exchange.status(), is(500));
            assertThat(exchange.body(), containsString("extension scripted requestFilter failed (threw)"));
        }
        List<String> log = awaitLog("/h2-failure", entries -> count(entries, "received request:") > 0 && count(entries, "failed:") > 0);
        assertThat(count(log, "received request:"), is(1));
    }

    @Test
    public void shouldNeverFilterARequestTheDataPlaneAuthenticationGateRefuses() throws Exception {
        ScriptedFilter authFilter = new ScriptedFilter();
        MockServer authServer = new MockServer(configuration()
            .attemptToProxyIfNoMatchingExpectation(false)
            .dataPlaneAuthenticationRequired(true)
            .dataPlaneBearerAuthenticationToken("filter-token")
            .extensions(() -> authFilter), 0);
        try {
            new MockServerClient("127.0.0.1", authServer.getLocalPort()).when(request().withPath("/gated")).respond(response().withBody("let through"));
            try (PipelinedHttp1Client client = PipelinedHttp1Client.plain(authServer.getLocalPort())) {
                client.pipeline(get("/gated"));
                assertThat(client.readResponse().status, is(401));
            }
            assertThat(authFilter.paths, is(empty()));

            try (PipelinedHttp1Client client = PipelinedHttp1Client.plain(authServer.getLocalPort())) {
                client.pipeline(rawRequest("GET", "/gated", "Authorization: Bearer filter-token"));
                assertThat(client.readResponse().body(), is("let through"));
            }
            assertThat(authFilter.paths, contains("/gated"));
        } finally {
            stopQuietly(authServer);
        }
    }

    private static String path(HttpRequest request) {
        return request.getPath().getValue();
    }

    private static int count(List<String> entries, String text) {
        return (int) entries.stream().filter(entry -> entry.contains(text)).count();
    }

    /**
     * The event log entries for one path, once {@code settled} holds for them; log entries are written asynchronously.
     */
    private static List<String> awaitLog(String path, Predicate<List<String>> settled) throws InterruptedException {
        List<String> entries = new ArrayList<>();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            entries = new ArrayList<>(Arrays.asList(mockServerClient.retrieveLogMessagesArray(request().withPath(path))));
            if (settled.test(entries)) {
                // one more read, so an entry logged just after the one waited for is counted too
                TimeUnit.MILLISECONDS.sleep(200);
                return new ArrayList<>(Arrays.asList(mockServerClient.retrieveLogMessagesArray(request().withPath(path))));
            }
            TimeUnit.MILLISECONDS.sleep(50);
        }
        throw new AssertionError("log for " + path + " never settled: " + entries);
    }

    private static void awaitAnyLogEntry(Predicate<String> wanted) throws InterruptedException {
        List<String> entries = new ArrayList<>();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            entries = Arrays.asList(mockServerClient.retrieveLogMessagesArray(null));
            if (entries.stream().anyMatch(wanted)) {
                return;
            }
            TimeUnit.MILLISECONDS.sleep(50);
        }
        throw new AssertionError("no matching log entry in: " + entries);
    }

    interface Script {
        FilterResult filter(HttpRequest request, FilterContext context) throws Exception;
    }

    public static class ScriptedFilter implements RequestFilter {
        volatile Script script;
        final List<String> paths = new CopyOnWriteArrayList<>();
        final List<String> threads = new CopyOnWriteArrayList<>();
        final List<String> protocols = new CopyOnWriteArrayList<>();

        ScriptedFilter() {
            reset();
        }

        void reset() {
            script = (request, context) -> FilterResult.proceed();
            paths.clear();
            threads.clear();
            protocols.clear();
        }

        @Override
        public String name() {
            return "scripted";
        }

        @Override
        public FilterResult filter(HttpRequest request, FilterContext context) throws Exception {
            paths.add(path(request));
            threads.add(Thread.currentThread().getName());
            protocols.add(String.valueOf(context.protocol()));
            return script.filter(request, context);
        }
    }
}
