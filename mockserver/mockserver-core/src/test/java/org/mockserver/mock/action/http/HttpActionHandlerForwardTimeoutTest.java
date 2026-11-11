package org.mockserver.mock.action.http;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockserver.configuration.Configuration;
import org.mockserver.httpclient.NettyHttpClient;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.mock.HttpState;
import org.mockserver.model.HttpForward;
import org.mockserver.model.HttpRequest;
import org.mockserver.model.HttpResponse;
import org.mockserver.responsewriter.ResponseWriter;
import org.mockserver.scheduler.Scheduler;

import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.lessThan;
import static org.hamcrest.Matchers.notNullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockserver.configuration.Configuration.configuration;
import static org.mockserver.model.HttpRequest.request;

/**
 * Completion-guarantee regression test for the forwarding client's async proxy paths. An earlier
 * change replaced the blocking {@code responseFuture.getHttpResponse().get(maxFutureTimeout)}
 * on three forward paths with an async scheduler continuation. That removed the only bound on a connected-but-
 * silent upstream when {@code maxSocketTimeout=0} disables the read timeout (also true of pooled channels, which
 * carry none): the response future never completes, the continuation never runs, and the client hangs forever.
 *
 * <p>{@link Scheduler} now applies {@code orTimeout(maxFutureTimeoutInMillis)} to the response future before the
 * continuation, so a silent upstream completes it exceptionally with a {@link java.util.concurrent.TimeoutException}
 * that the forward paths map to a 502. Each test points a REAL {@link NettyHttpClient} at a REAL server socket that
 * accepts the connection but never sends a response, with {@code maxSocketTimeout=0} (no read timeout anywhere) and
 * a small {@code maxFutureTimeout} ({@link #MAX_FUTURE_TIMEOUT_MS}ms) so the backstop, not the default 90s, is what
 * fires.
 *
 * <p>NEGATIVE CONTROL: with the {@code orTimeout} backstop removed from {@link Scheduler}, the silent-upstream
 * tests never receive a response - {@link #responseLatch} stays at 1 and the {@code @Test(timeout=...)} fires,
 * failing (not hanging) with an {@code org.junit.runners.model.TestTimedOutException}.
 *
 * <p>Global state: builds its own {@link Configuration}, {@link Scheduler} and event-loop group and binds its own
 * ephemeral sockets; no JVM-global statics ({@code ConfigurationProperties}) are mutated, so it runs in the parallel
 * Surefire phase alongside {@link HttpActionHandlerForwardConcurrencyTest}.
 */
public class HttpActionHandlerForwardTimeoutTest {

    private static final long MAX_FUTURE_TIMEOUT_MS = 2_000;

    private Configuration configuration;
    private Scheduler scheduler;
    private EventLoopGroup clientEventLoopGroup;
    private NettyHttpClient httpClient;
    private HttpActionHandler actionHandler;
    private ResponseWriter responseWriter;

    private ServerSocket silentServer;
    private ServerSocket respondingServer;
    private Thread silentAcceptThread;
    private Thread respondingAcceptThread;
    private final List<Socket> heldSockets = new ArrayList<>();

    private CountDownLatch responseLatch;
    private final AtomicReference<HttpResponse> writtenResponse = new AtomicReference<>();

    @Before
    public void setupTestFixture() throws Exception {
        silentServer = startSilentServer();
        respondingServer = startRespondingServer();

        configuration = configuration()
            .maxSocketTimeoutInMillis(0L)
            .maxFutureTimeoutInMillis(MAX_FUTURE_TIMEOUT_MS)
            .driftDetectionEnabled(false)
            .proxyRemoteHost("127.0.0.1")
            .proxyRemotePort(silentServer.getLocalPort());
        scheduler = new Scheduler(configuration, new MockServerLogger());
        clientEventLoopGroup = new NioEventLoopGroup(2, new Scheduler.SchedulerThreadFactory(HttpActionHandlerForwardTimeoutTest.class.getSimpleName() + "-eventLoop"));
        httpClient = new NettyHttpClient(configuration, new MockServerLogger(), clientEventLoopGroup, null, true);

        HttpState httpState = mock(HttpState.class);
        when(httpState.getScheduler()).thenReturn(scheduler);
        when(httpState.getMockServerLogger()).thenReturn(new MockServerLogger());
        when(httpState.getUniqueLoopPreventionHeaderName()).thenReturn("x-forwarded-by");
        when(httpState.getUniqueLoopPreventionHeaderValue()).thenReturn("MockServer");

        actionHandler = new HttpActionHandler(configuration, null, httpState, null, null);
        setField(actionHandler, "httpClient", httpClient);

        responseLatch = new CountDownLatch(1);
        responseWriter = mock(ResponseWriter.class);
        doAnswer(invocation -> {
            writtenResponse.set(invocation.getArgument(1));
            responseLatch.countDown();
            return null;
        }).when(responseWriter).writeResponse(any(HttpRequest.class), any(HttpResponse.class), anyBoolean());
    }

    @After
    public void stopTestFixture() {
        if (scheduler != null) {
            scheduler.shutdown();
        }
        if (clientEventLoopGroup != null) {
            clientEventLoopGroup.shutdownGracefully(0, 0, TimeUnit.MILLISECONDS);
        }
        closeQuietly(silentServer);
        closeQuietly(respondingServer);
        for (Socket socket : heldSockets) {
            closeQuietly(socket);
        }
        if (silentAcceptThread != null) {
            silentAcceptThread.interrupt();
        }
        if (respondingAcceptThread != null) {
            respondingAcceptThread.interrupt();
        }
    }

    @Test(timeout = 15_000)
    public void unmatchedProxyForwardToSilentUpstreamShouldReturnBadGatewayWithinFutureTimeout() throws Exception {
        Method handleUnmatchedProxyForward = HttpActionHandler.class.getDeclaredMethod(
            "handleUnmatchedProxyForward", HttpRequest.class, ResponseWriter.class, ChannelHandlerContext.class, boolean.class, boolean.class, boolean.class);
        handleUnmatchedProxyForward.setAccessible(true);

        HttpRequest request = request("/silent").withHeader("Host", "127.0.0.1:" + silentServer.getLocalPort());

        long start = System.nanoTime();
        handleUnmatchedProxyForward.invoke(actionHandler, request, responseWriter, null, false, false, true);

        assertThat("silent upstream must produce a response, not hang", responseLatch.await(10, TimeUnit.SECONDS), is(true));
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;
        assertThat(writtenResponse.get(), notNullValue());
        assertThat("connected-but-silent upstream should map to 502 Bad Gateway", writtenResponse.get().getStatusCode(), is(502));
        assertThat("must be bounded by maxFutureTimeout, not the default 90s", elapsedMs, lessThan(MAX_FUTURE_TIMEOUT_MS + 8_000));
    }

    @Test(timeout = 15_000)
    public void matchedForwardToSilentUpstreamShouldReturnBadGatewayWithinFutureTimeout() throws Exception {
        HttpRequest request = request("/silent").withHeader("Host", "127.0.0.1:" + silentServer.getLocalPort());
        HttpForwardActionResult responseFuture = new HttpForwardActionResult(
            request,
            httpClient.sendRequest(request, new InetSocketAddress("127.0.0.1", silentServer.getLocalPort())),
            null
        );

        long start = System.nanoTime();
        actionHandler.writeForwardActionResponse(responseFuture, responseWriter, request, HttpForward.forward(), false);

        assertThat("silent upstream must produce a response, not hang", responseLatch.await(10, TimeUnit.SECONDS), is(true));
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;
        assertThat(writtenResponse.get(), notNullValue());
        assertThat("connected-but-silent upstream should map to 502 Bad Gateway", writtenResponse.get().getStatusCode(), is(502));
        assertThat("must be bounded by maxFutureTimeout, not the default 90s", elapsedMs, lessThan(MAX_FUTURE_TIMEOUT_MS + 8_000));
    }

    @Test(timeout = 15_000)
    public void matchedForwardToRespondingUpstreamShouldReturnUpstreamResponseUnaffected() throws Exception {
        HttpRequest request = request("/ok").withHeader("Host", "127.0.0.1:" + respondingServer.getLocalPort());
        HttpForwardActionResult responseFuture = new HttpForwardActionResult(
            request,
            httpClient.sendRequest(request, new InetSocketAddress("127.0.0.1", respondingServer.getLocalPort())),
            null
        );

        long start = System.nanoTime();
        actionHandler.writeForwardActionResponse(responseFuture, responseWriter, request, HttpForward.forward(), false);

        assertThat("a responding upstream must produce a response", responseLatch.await(10, TimeUnit.SECONDS), is(true));
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;
        assertThat(writtenResponse.get(), notNullValue());
        assertThat("a normal forward is unaffected by the backstop", writtenResponse.get().getStatusCode(), is(200));
        assertThat("a normal forward returns immediately, well before maxFutureTimeout", elapsedMs, lessThan(MAX_FUTURE_TIMEOUT_MS));
    }

    private ServerSocket startSilentServer() throws Exception {
        ServerSocket server = new ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"));
        silentAcceptThread = new Thread(() -> {
            while (!Thread.currentThread().isInterrupted() && !server.isClosed()) {
                try {
                    Socket socket = server.accept();
                    // Accept the connection and hold it open without ever writing a response.
                    synchronized (heldSockets) {
                        heldSockets.add(socket);
                    }
                } catch (Exception ignore) {
                    return;
                }
            }
        }, "silent-upstream-accept");
        silentAcceptThread.setDaemon(true);
        silentAcceptThread.start();
        return server;
    }

    private ServerSocket startRespondingServer() throws Exception {
        ServerSocket server = new ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"));
        respondingAcceptThread = new Thread(() -> {
            while (!Thread.currentThread().isInterrupted() && !server.isClosed()) {
                try {
                    Socket socket = server.accept();
                    synchronized (heldSockets) {
                        heldSockets.add(socket);
                    }
                    InputStream in = socket.getInputStream();
                    byte[] buffer = new byte[4096];
                    in.read(buffer);
                    OutputStream out = socket.getOutputStream();
                    out.write("HTTP/1.1 200 OK\r\nContent-Length: 2\r\nConnection: close\r\n\r\nok".getBytes(StandardCharsets.UTF_8));
                    out.flush();
                } catch (Exception ignore) {
                    return;
                }
            }
        }, "responding-upstream-accept");
        respondingAcceptThread.setDaemon(true);
        respondingAcceptThread.start();
        return server;
    }

    private static void setField(Object target, String name, Object value) throws Exception {
        Field field = HttpActionHandler.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    private static void closeQuietly(java.io.Closeable closeable) {
        if (closeable != null) {
            try {
                closeable.close();
            } catch (Exception ignore) {
                // ignore
            }
        }
    }
}
