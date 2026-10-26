package org.mockserver.netty.http3;

import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.handler.codec.http3.DefaultHttp3Headers;
import org.junit.After;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Test;
import org.mockserver.extension.FilterContext;
import org.mockserver.extension.FilterResult;
import org.mockserver.extension.RequestFilter;
import org.mockserver.model.HttpRequest;
import org.mockserver.model.Protocol;
import org.mockserver.netty.MockServer;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.lessThan;
import static org.mockserver.configuration.Configuration.configuration;
import static org.mockserver.netty.http3.Http3TestServer.startWithHttp3;
import static org.mockserver.stop.Stop.stopQuietly;

/**
 * Stopping a Netty server while a request filter is still running leaves no extension pool thread behind. Over
 * HTTP/1.1 the graceful drain lets the request finish, and answers it {@code 503} only if the drain gives up on it,
 * even when HTTP/3 is on. Over HTTP/3, which the drain does not wait for, it is answered {@code 503} before its
 * streams close.
 */
@SuppressWarnings("deprecation") // NioEventLoopGroup deprecation in Netty 4.2
public class RequestFilterStopIntegrationTest {

    // longer than any of these tests waits, so a 503 can only come from the stop
    private static final long BUDGET_MILLIS = 30_000;

    private static volatile BlockingFilterState state;
    private static volatile ProtocolGatedFilterState gated;

    private MockServer mockServer;
    private NioEventLoopGroup clientGroup;

    /**
     * Blocks, interruptibly, until the server stops.
     */
    public static class BlockingFilter implements RequestFilter {
        @Override
        public String name() {
            return "blocks-until-stopped";
        }

        @Override
        public FilterResult filter(HttpRequest request, FilterContext context) throws Exception {
            BlockingFilterState current = state;
            current.threadName = Thread.currentThread().getName();
            current.entered.countDown();
            new CountDownLatch(1).await(BUDGET_MILLIS, TimeUnit.MILLISECONDS);
            return FilterResult.proceed();
        }
    }

    /**
     * Over HTTP/3 blocks, interruptibly, until the server stops; over any other protocol waits until the test releases
     * it.
     */
    public static class ProtocolGatedFilter implements RequestFilter {
        @Override
        public String name() {
            return "gated-by-protocol";
        }

        @Override
        public FilterResult filter(HttpRequest request, FilterContext context) throws Exception {
            ProtocolGatedFilterState current = gated;
            current.threadName = Thread.currentThread().getName();
            if (context.protocol() == Protocol.HTTP_3) {
                current.http3Entered.countDown();
                new CountDownLatch(1).await(BUDGET_MILLIS, TimeUnit.MILLISECONDS);
            } else {
                current.tcpEntered.countDown();
                current.releaseTcp.await(BUDGET_MILLIS, TimeUnit.MILLISECONDS);
            }
            return FilterResult.proceed();
        }
    }

    static final class ProtocolGatedFilterState {
        final CountDownLatch http3Entered = new CountDownLatch(1);
        final CountDownLatch tcpEntered = new CountDownLatch(1);
        final CountDownLatch releaseTcp = new CountDownLatch(1);
        volatile String threadName;

        String poolPrefix() {
            return threadName.substring(0, threadName.lastIndexOf('-') + 1);
        }
    }

    static final class BlockingFilterState {
        final CountDownLatch entered = new CountDownLatch(1);
        volatile String threadName;

        String poolPrefix() {
            return threadName.substring(0, threadName.lastIndexOf('-') + 1);
        }
    }

    @Before
    public void resetState() {
        state = new BlockingFilterState();
        gated = new ProtocolGatedFilterState();
    }

    @After
    public void stopEverything() {
        if (clientGroup != null) {
            clientGroup.shutdownGracefully(0, 1, TimeUnit.SECONDS).syncUninterruptibly();
        }
        stopQuietly(mockServer);
    }

    @Test
    public void shouldAnswerAnHttp1RequestWhoseFilterIsRunningWith503WhenTheServerStops() throws Exception {
        mockServer = new MockServer(configuration()
            .extensionDataPlaneTimeoutMillis(BUDGET_MILLIS)
            // the drain gives up on the pending request quickly; the 503 comes from DataPlaneHooks.stop() after it
            .stopDrainMillis(300L)
            .extensions(BlockingFilter.class), 0);
        HttpClient client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();
        CompletableFuture<HttpResponse<String>> pending = client.sendAsync(
            java.net.http.HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + mockServer.getLocalPort() + "/pending")).timeout(Duration.ofSeconds(20)).build(),
            HttpResponse.BodyHandlers.ofString());
        assertThat("the filter was called", state.entered.await(10, TimeUnit.SECONDS), is(true));

        long start = System.nanoTime();
        mockServer.stop();
        HttpResponse<String> response = pending.get(10, TimeUnit.SECONDS);

        assertThat(response.statusCode(), is(503));
        assertThat(response.body(), is("MockServer is stopping"));
        assertThat("answered by the stop, not the budget", TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start), lessThan(BUDGET_MILLIS / 3));
        DataPlaneThreads.assertNoneRemain(state.poolPrefix());
    }

    @Test
    public void shouldAnswerAnHttp3RequestWhoseFilterIsRunningWith503WhenTheServerStops() throws Exception {
        assumeQuicAvailable();
        mockServer = startWithHttp3(configuration()
            .http3MaxIdleTimeout(30_000L)
            .extensionDataPlaneTimeoutMillis(BUDGET_MILLIS)
            .extensions(BlockingFilter.class));
        clientGroup = new NioEventLoopGroup(1);

        try (Http3TestClient client = Http3TestClient.open(clientGroup, mockServer)) {
            Http3TestClient.Exchange pending = client.send(new DefaultHttp3Headers()
                .method("GET")
                .scheme("https")
                .authority("127.0.0.1:" + mockServer.getHttp3Port())
                .path("/pending"));
            assertThat("the filter was called", state.entered.await(10, TimeUnit.SECONDS), is(true));

            long start = System.nanoTime();
            mockServer.stop();

            assertThat(pending.status(), is(503));
            assertThat(pending.body(), is("MockServer is stopping"));
            assertThat("answered by the stop, not the budget", TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start), lessThan(BUDGET_MILLIS / 3));
        }
        DataPlaneThreads.assertNoneRemain(state.poolPrefix());
    }

    /**
     * HTTP/3 stops before the TCP drain, so stopping its pending calls must not stop the TCP ones: an HTTP/1.1 request
     * whose filter is still running when the server stops drains and gets its normal response, not a 503.
     */
    @Test
    public void shouldDrainAnHttp1RequestWhoseFilterIsRunningWhenTheServerStopsWithHttp3On() throws Exception {
        assumeQuicAvailable();
        mockServer = startWithHttp3(configuration()
            .http3MaxIdleTimeout(30_000L)
            .extensionDataPlaneTimeoutMillis(BUDGET_MILLIS)
            .stopDrainMillis(20_000L)
            .extensions(ProtocolGatedFilter.class));
        clientGroup = new NioEventLoopGroup(1);
        HttpClient tcpClient = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();
        CompletableFuture<HttpResponse<String>> tcpPending = tcpClient.sendAsync(
            java.net.http.HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + mockServer.getLocalPort() + "/draining")).timeout(Duration.ofSeconds(20)).build(),
            HttpResponse.BodyHandlers.ofString());
        assertThat("the HTTP/1.1 filter was called", gated.tcpEntered.await(10, TimeUnit.SECONDS), is(true));

        try (Http3TestClient client = Http3TestClient.open(clientGroup, mockServer)) {
            Http3TestClient.Exchange http3Pending = client.send(new DefaultHttp3Headers()
                .method("GET")
                .scheme("https")
                .authority("127.0.0.1:" + mockServer.getHttp3Port())
                .path("/stopped"));
            assertThat("the HTTP/3 filter was called", gated.http3Entered.await(10, TimeUnit.SECONDS), is(true));

            // stops HTTP/3 before it returns; the TCP drain runs on the stop thread
            CompletableFuture<String> stopped = mockServer.stopAsync();

            assertThat(http3Pending.status(), is(503));
            assertThat(http3Pending.body(), is("MockServer is stopping"));
            assertThat("the HTTP/1.1 request is still draining", tcpPending.isDone(), is(false));
            gated.releaseTcp.countDown();
            HttpResponse<String> tcpResponse = tcpPending.get(10, TimeUnit.SECONDS);
            assertThat("drained to its normal response (no expectation matches)", tcpResponse.statusCode(), is(404));
            stopped.get(30, TimeUnit.SECONDS);
        }
        DataPlaneThreads.assertNoneRemain(gated.poolPrefix());
    }

    private static void assumeQuicAvailable() {
        boolean available;
        try {
            available = Http3Server.isQuicAvailable();
        } catch (Throwable notLoaded) {
            available = false;
        }
        Assume.assumeTrue("native QUIC transport not available on this platform", available);
    }

    static final class DataPlaneThreads {
        static void assertNoneRemain(String prefix) throws InterruptedException {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            long remaining;
            do {
                remaining = Thread.getAllStackTraces().keySet().stream().filter(thread -> thread.isAlive() && thread.getName().startsWith(prefix)).count();
                if (remaining == 0) {
                    return;
                }
                TimeUnit.MILLISECONDS.sleep(50);
            } while (System.nanoTime() < deadline);
            assertThat("threads named " + prefix + "* still alive after the stop", remaining, is(0L));
        }
    }
}
