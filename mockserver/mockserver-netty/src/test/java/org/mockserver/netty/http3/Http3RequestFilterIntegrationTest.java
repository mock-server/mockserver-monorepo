package org.mockserver.netty.http3;

import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.handler.codec.http3.DefaultHttp3Headers;
import io.netty.handler.codec.http3.Http3Headers;
import org.junit.AfterClass;
import org.junit.Assume;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.mockserver.client.MockServerClient;
import org.mockserver.extension.FilterContext;
import org.mockserver.extension.FilterResult;
import org.mockserver.extension.RequestFilter;
import org.mockserver.grpc.GrpcStatusMapper;
import org.mockserver.model.HttpRequest;
import org.mockserver.netty.MockServer;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.lessThan;
import static org.hamcrest.Matchers.startsWith;
import static org.mockserver.configuration.Configuration.configuration;
import static org.mockserver.model.HttpRequest.request;
import static org.mockserver.model.HttpResponse.response;
import static org.mockserver.netty.http3.Http3TestServer.startWithHttp3;
import static org.mockserver.stop.Stop.stopQuietly;

/**
 * The extension request filter (H1) on HTTP/3, plain and gRPC: it runs on the extension pool for every request that
 * reaches matching, including {@code /mockserver/status} and {@code /mockserver/ready}, which HTTP/3 does not serve
 * itself, and a gRPC call's {@code grpc-timeout} still ends the call while its filter runs.
 */
@SuppressWarnings("deprecation") // NioEventLoopGroup deprecation in Netty 4.2
public class Http3RequestFilterIntegrationTest {

    private static final String GRPC_PATH = "/probe.Service/Method";
    private static final long SLOW_FILTER_MILLIS = 1_500;
    private static final List<String> PATHS = new CopyOnWriteArrayList<>();
    private static final List<String> THREADS = new CopyOnWriteArrayList<>();
    private static final List<String> PROTOCOLS = new CopyOnWriteArrayList<>();
    private static volatile Script script;

    private static MockServer mockServer;
    private static MockServerClient mockServerClient;
    private static NioEventLoopGroup clientGroup;

    interface Script {
        FilterResult filter(HttpRequest request) throws Exception;
    }

    public static class ScriptedFilter implements RequestFilter {
        @Override
        public String name() {
            return "h3-scripted";
        }

        @Override
        public FilterResult filter(HttpRequest request, FilterContext context) throws Exception {
            PATHS.add(request.getPath().getValue());
            THREADS.add(Thread.currentThread().getName());
            PROTOCOLS.add(String.valueOf(context.protocol()));
            return script.filter(request);
        }
    }

    @BeforeClass
    public static void startServer() {
        boolean available;
        try {
            available = Http3Server.isQuicAvailable();
        } catch (Throwable notLoaded) {
            available = false;
        }
        Assume.assumeTrue("native QUIC transport not available on this platform", available);
        mockServer = startWithHttp3(configuration()
            .logLevel("INFO")
            .attemptToProxyIfNoMatchingExpectation(false)
            .http3MaxIdleTimeout(30_000L)
            .extensionDataPlaneTimeoutMillis(10_000L)
            .extensions(ScriptedFilter.class));
        mockServerClient = new MockServerClient("127.0.0.1", mockServer.getLocalPort());
        clientGroup = new NioEventLoopGroup(1);
    }

    @AfterClass
    public static void stopServer() {
        if (clientGroup != null) {
            clientGroup.shutdownGracefully(0, 1, TimeUnit.SECONDS).syncUninterruptibly();
        }
        stopQuietly(mockServerClient);
        stopQuietly(mockServer);
    }

    @Before
    public void reset() {
        script = request -> FilterResult.proceed();
        PATHS.clear();
        THREADS.clear();
        PROTOCOLS.clear();
        mockServerClient.reset();
    }

    @Test
    public void shouldFilterHttp3StatusAndReadyWhichFallThroughToMatching() throws Exception {
        script = request -> FilterResult.respond(response().withStatusCode(200).withBody("answered by the filter for " + request.getPath().getValue()));

        try (Http3TestClient client = Http3TestClient.open(clientGroup, mockServer)) {
            Http3TestClient.Exchange status = client.send(http3Request("PUT", "/mockserver/status"));
            assertThat(status.status(), is(200));
            assertThat(status.body(), is("answered by the filter for /mockserver/status"));

            Http3TestClient.Exchange ready = client.send(http3Request("GET", "/mockserver/ready"));
            assertThat(ready.status(), is(200));
            assertThat(ready.body(), is("answered by the filter for /mockserver/ready"));
        }
        assertThat(PATHS, contains("/mockserver/status", "/mockserver/ready"));
        assertThat(PROTOCOLS, everyItem(is("HTTP_3")));
        assertThat("the filter must run on the extension pool, never the QUIC event loop", THREADS, everyItem(startsWith("MockServer-ext-dataplane-")));
    }

    @Test
    public void shouldMatchThePlainRequestTheFilterLetsThrough() throws Exception {
        script = request -> FilterResult.proceedWith(request.withHeader("x-filtered", "yes"));
        mockServerClient.when(request().withPath("/h3-filtered").withHeader("x-filtered", "yes")).respond(response().withBody("matched the filtered request"));

        try (Http3TestClient client = Http3TestClient.open(clientGroup, mockServer)) {
            Http3TestClient.Exchange exchange = client.send(http3Request("GET", "/h3-filtered"));
            assertThat(exchange.status(), is(200));
            assertThat(exchange.body(), is("matched the filtered request"));
        }
        assertThat(PATHS, contains("/h3-filtered"));
        assertThat(THREADS, everyItem(startsWith("MockServer-ext-dataplane-")));
    }

    /**
     * The deadline is written on the stream's event loop while the filter is still running; the response matching
     * makes once the filter lets the call through is then dropped, so the stream carries one trailers-only frame. With
     * a hook loaded the writer is a transforming one, so the deadline claims the exchange and the drop is logged there.
     */
    @Test
    public void shouldEndAGrpcCallAtItsDeadlineWhileItsFilterRuns() throws Exception {
        CountDownLatch filterReturned = new CountDownLatch(1);
        script = request -> {
            if (request.containsHeader("grpc-timeout")) {
                try {
                    Thread.sleep(SLOW_FILTER_MILLIS);
                } finally {
                    filterReturned.countDown();
                }
            }
            return FilterResult.proceed();
        };
        mockServerClient.when(request().withPath(GRPC_PATH)).respond(response().withStatusCode(200).withBody(new byte[]{0, 0, 0, 0, 0}));

        try (Http3TestClient client = Http3TestClient.open(clientGroup, mockServer)) {
            long start = System.nanoTime();
            Http3TestClient.Exchange expired = client.send(grpcRequest().set("grpc-timeout", "300m"));
            Http3Headers headers = expired.headers();
            long endedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);

            assertThat(String.valueOf(headers.get(GrpcStatusMapper.GRPC_STATUS_HEADER)), is(String.valueOf(GrpcStatusMapper.GrpcStatusCode.DEADLINE_EXCEEDED.getCode())));
            assertThat("ended at the deadline, while the filter was still running", endedMillis, lessThan(SLOW_FILTER_MILLIS));
            assertThat(filterReturned.await(10, TimeUnit.SECONDS), is(true));
            awaitLogMessage("because its exchange was already answered");
            assertThat("trailers-only: nothing followed the deadline's frame", expired.receivedTrailers() || expired.dataFrames() > 0, is(false));

            Http3TestClient.Exchange next = client.send(grpcRequest());
            assertThat(next.status(), is(200));
            assertThat(next.trailer(GrpcStatusMapper.GRPC_STATUS_HEADER), is("0"));
        }
    }

    private static Http3Headers http3Request(String method, String path) {
        return new DefaultHttp3Headers()
            .method(method)
            .scheme("https")
            .authority("127.0.0.1:" + mockServer.getHttp3Port())
            .path(path);
    }

    private static Http3Headers grpcRequest() {
        return http3Request("POST", GRPC_PATH)
            .set("content-type", GrpcStatusMapper.GRPC_CONTENT_TYPE)
            .set("te", "trailers");
    }

    private static void awaitLogMessage(String text) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        List<String> messages = List.of();
        while (System.nanoTime() < deadline) {
            messages = Arrays.asList(mockServerClient.retrieveLogMessagesArray(null));
            if (messages.stream().anyMatch(message -> message.contains(text))) {
                return;
            }
            TimeUnit.MILLISECONDS.sleep(50);
        }
        throw new AssertionError("no log message containing \"" + text + "\" in: " + messages);
    }
}
