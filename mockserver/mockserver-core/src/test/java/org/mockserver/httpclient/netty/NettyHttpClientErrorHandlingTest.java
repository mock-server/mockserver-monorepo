package org.mockserver.httpclient.netty;

import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelPromise;
import io.netty.channel.DefaultChannelPromise;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.handler.ssl.SslContext;
import io.netty.util.concurrent.Future;
import io.netty.util.concurrent.GenericFutureListener;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;
import org.mockserver.echo.http.EchoServer;
import org.mockserver.httpclient.ClientConfigurationException;
import org.mockserver.httpclient.NettyHttpClient;
import org.mockserver.httpclient.SocketConnectionException;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.model.HttpResponse;
import org.mockserver.model.MediaType;
import org.mockserver.proxyconfiguration.ProxyConfiguration;
import org.mockserver.scheduler.Scheduler;
import org.mockserver.socket.tls.NettySslContextFactory;

import java.io.IOException;
import java.net.ConnectException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.util.Collections;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static io.netty.handler.codec.http.HttpHeaderNames.*;
import static io.netty.handler.codec.http.HttpHeaderValues.KEEP_ALIVE;
import static io.netty.handler.codec.http.HttpHeaderValues.*;
import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.endsWith;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.matchesPattern;
import static org.hamcrest.Matchers.sameInstance;
import static org.hamcrest.Matchers.startsWith;
import static org.hamcrest.core.AnyOf.anyOf;
import static org.hamcrest.core.Is.is;
import static org.junit.Assert.assertThrows;
import static org.mockito.Mockito.when;
import static org.mockserver.configuration.Configuration.configuration;
import static org.mockserver.model.Header.header;
import static org.mockserver.model.HttpRequest.request;
import static org.mockserver.model.HttpResponse.response;
import static org.mockserver.model.StringBody.exact;
import static org.mockserver.proxyconfiguration.ProxyConfiguration.proxyConfiguration;
import static org.mockserver.stop.Stop.stopQuietly;
import static org.mockserver.test.ClosedPort.CLOSED_PORT;

public class NettyHttpClientErrorHandlingTest {

    private static EventLoopGroup clientEventLoopGroup;
    private final MockServerLogger mockServerLogger = new MockServerLogger();

    @BeforeClass
    public static void startEventLoopGroup() {
        clientEventLoopGroup = new NioEventLoopGroup(3, new Scheduler.SchedulerThreadFactory(NettyHttpClientErrorHandlingTest.class.getSimpleName() + "-eventLoop"));
    }

    @AfterClass
    public static void stopEventLoopGroup() {
        clientEventLoopGroup.shutdownGracefully(0, 0, MILLISECONDS).syncUninterruptibly();
    }

    @Test
    public void shouldThrowSocketCommunicationExceptionForConnectException() {
        // when
        ExecutionException exception = assertThrows(ExecutionException.class, () -> new NettyHttpClient(configuration(), mockServerLogger, clientEventLoopGroup, null, false)
            .sendRequest(request().withHeader(HOST.toString(), "127.0.0.1:" + CLOSED_PORT))
            .get(60, TimeUnit.SECONDS));

        // then
        assertThat(exception.getCause(), instanceOf(ConnectException.class));
        assertThat(exception.getCause().getMessage(), startsWith("Connection refused"));
        assertThat(exception.getCause().getMessage(), endsWith(": /127.0.0.1:" + CLOSED_PORT));
    }

    @Test
    public void shouldReportConnectFailureWhenTheChannelIsTornDownBeforeTheClientListensForIt() throws Exception {
        // given
        ConnectResolvedBeforeCallerListensEventLoopGroup eventLoopGroup = new ConnectResolvedBeforeCallerListensEventLoopGroup();

        try {
            // when
            CompletableFuture<HttpResponse> response = new NettyHttpClient(configuration(), mockServerLogger, eventLoopGroup, null, false)
                .sendRequest(request().withHeader(HOST.toString(), "127.0.0.1:" + CLOSED_PORT));
            eventLoopGroup.callerReturned.countDown();
            ExecutionException exception = assertThrows(ExecutionException.class, () -> response.get(10, TimeUnit.SECONDS));

            // then
            assertThat("the refused channel was torn down before the client listened for the connect outcome", eventLoopGroup.tornDownBeforeCallerListened.get(), is(true));
            assertThat(exception.getCause(), instanceOf(ConnectException.class));
            assertThat(exception.getCause().getMessage(), startsWith("Connection refused"));
            assertThat(exception.getCause().getMessage(), endsWith(": /127.0.0.1:" + CLOSED_PORT));
        } finally {
            eventLoopGroup.callerReturned.countDown();
            eventLoopGroup.shutdownGracefully(0, 0, MILLISECONDS).syncUninterruptibly();
        }
    }

    @Test
    public void shouldReportTheChannelFailureWhenAConnectedChannelFailsBeforeTheClientListensForTheConnect() throws Exception {
        // given - an upstream that accepts each connection and closes it at once
        ConnectResolvedBeforeCallerListensEventLoopGroup eventLoopGroup = new ConnectResolvedBeforeCallerListensEventLoopGroup();

        try (ServerSocket closesEveryConnection = new ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))) {
            Thread accepting = new Thread(() -> {
                while (true) {
                    try {
                        closesEveryConnection.accept().close();
                    } catch (IOException listenerClosed) {
                        return;
                    }
                }
            }, NettyHttpClientErrorHandlingTest.class.getSimpleName() + "-acceptAndClose");
            accepting.setDaemon(true);
            accepting.start();

            // when
            CompletableFuture<HttpResponse> response = new NettyHttpClient(configuration(), mockServerLogger, eventLoopGroup, null, false)
                .sendRequest(request().withHeader(HOST.toString(), "127.0.0.1:" + closesEveryConnection.getLocalPort()));
            eventLoopGroup.callerReturned.countDown();
            ExecutionException exception = assertThrows(ExecutionException.class, () -> response.get(10, TimeUnit.SECONDS));

            // then
            assertThat("the connected channel was torn down before the client listened for the connect outcome", eventLoopGroup.tornDownBeforeCallerListened.get(), is(true));
            assertThat(exception.getCause(), instanceOf(SocketConnectionException.class));
            assertThat(exception.getCause().getMessage(), is("Channel handler removed before valid response has been received"));
        } finally {
            eventLoopGroup.callerReturned.countDown();
            eventLoopGroup.shutdownGracefully(0, 0, MILLISECONDS).syncUninterruptibly();
        }
    }

    @Test
    public void shouldReportAConfigurationErrorWhenTheClientTlsContextCannotBeCreated() {
        // given - the pipeline fails to build after the connection error handler is added, so the channel is
        // closed and its handlers removed before the connect is attempted on it
        RuntimeException cannotCreate = new RuntimeException("Exception creating SSL context for client");
        NettySslContextFactory unusableSslContextFactory = new NettySslContextFactory(configuration(), mockServerLogger, false) {
            @Override
            public SslContext createClientSslContext(boolean forwardProxyClient, boolean enableHttp2, String host) {
                throw cannotCreate;
            }
        };
        NettyHttpClient client = new NettyHttpClient(configuration(), mockServerLogger, clientEventLoopGroup, null, false, unusableSslContextFactory);

        // when
        ExecutionException asynchronous = assertThrows(ExecutionException.class, () -> client
            .sendRequest(request().withSecure(true).withHeader(HOST.toString(), "127.0.0.1:" + CLOSED_PORT))
            .get(10, TimeUnit.SECONDS));
        SocketConnectionException synchronous = assertThrows(SocketConnectionException.class, () -> client
            .sendRequest(request().withSecure(true).withHeader(HOST.toString(), "127.0.0.1:" + CLOSED_PORT), 10, TimeUnit.SECONDS));

        // then - the reason, as a configuration error that a caller catching SocketConnectionException still catches
        assertThat(asynchronous.getCause(), instanceOf(ClientConfigurationException.class));
        assertThat(asynchronous.getCause().getCause(), is(sameInstance(cannotCreate)));
        // the host as the TLS context saw it, which may be the name 127.0.0.1 resolves to
        assertThat(asynchronous.getCause().getMessage(), matchesPattern("connection to [^ ]+:" + CLOSED_PORT + " could not be set up: RuntimeException: Exception creating SSL context for client"));
        assertThat(synchronous, instanceOf(ClientConfigurationException.class));
        assertThat(synchronous.getMessage(), is(asynchronous.getCause().getMessage()));
    }

    @Test
    public void shouldReportAConfigurationErrorWhenThePipelineFailsBeforeAnyHandlerIsAdded() {
        // given - a proxy handler that cannot be constructed fails the pipeline before the connection error
        // handler is added
        ProxyConfiguration proxyWithoutAddress = proxyConfiguration(ProxyConfiguration.Type.HTTPS, (InetSocketAddress) null);
        NettyHttpClient client = new NettyHttpClient(configuration(), mockServerLogger, clientEventLoopGroup, Collections.singletonList(proxyWithoutAddress), false);

        // when
        ExecutionException exception = assertThrows(ExecutionException.class, () -> client
            .sendRequest(request().withSecure(true).withHeader(HOST.toString(), "127.0.0.1:" + CLOSED_PORT))
            .get(10, TimeUnit.SECONDS));

        // then
        assertThat(exception.getCause(), instanceOf(ClientConfigurationException.class));
        assertThat(exception.getCause().getCause(), instanceOf(NullPointerException.class));
    }

    /**
     * Resolves each connect attempt, and waits until the channel has been closed and torn down (because
     * the connect failed, or the peer closed at once), before the caller of {@code Bootstrap.connect} gets
     * the connect future back: the order a client thread loses when it is descheduled between starting a
     * connect and listening for its outcome. The single event loop is then held until the caller says it
     * has returned, so nothing completes behind its back.
     */
    private static final class ConnectResolvedBeforeCallerListensEventLoopGroup extends NioEventLoopGroup {

        final CountDownLatch callerReturned = new CountDownLatch(1);
        final AtomicBoolean tornDownBeforeCallerListened = new AtomicBoolean();

        ConnectResolvedBeforeCallerListensEventLoopGroup() {
            super(1, new Scheduler.SchedulerThreadFactory(NettyHttpClientErrorHandlingTest.class.getSimpleName() + "-connectResolvedFirst"));
        }

        @Override
        public ChannelFuture register(Channel channel) {
            ChannelFuture registered = super.register(channel);
            // never done, so the bootstrap starts the connect from the listener it adds here
            return new DefaultChannelPromise(channel) {
                @Override
                public ChannelPromise addListener(GenericFutureListener<? extends Future<? super Void>> startConnect) {
                    registered.addListener(startConnect);
                    try {
                        awaitTornDown(channel);
                        tornDownBeforeCallerListened.set(true);
                        channel.eventLoop().execute(() -> holdUntil(callerReturned));
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    return this;
                }
            };
        }

        private static void awaitTornDown(Channel channel) throws InterruptedException {
            if (!channel.closeFuture().await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("the channel was never closed");
            }
            // handlers are removed when the channel is deregistered, a later task on the same event loop
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            do {
                if (System.nanoTime() > deadline || !channel.eventLoop().submit(() -> { }).await(10, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("the closed channel was never deregistered");
                }
            } while (channel.isRegistered());
            channel.eventLoop().submit(() -> { }).await(10, TimeUnit.SECONDS);
        }

        private static void holdUntil(CountDownLatch latch) {
            try {
                latch.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    @Test
    public void shouldHandleConnectionClosure() throws Exception {
        // given
        EchoServer echoServer = new EchoServer(true, EchoServer.Error.CLOSE_CONNECTION);

        try {
            // when
            Exception exception = assertThrows(Exception.class, () -> new NettyHttpClient(configuration(), mockServerLogger, clientEventLoopGroup, null, false).sendRequest(request().withSecure(true).withHeader(HOST.toString(), "127.0.0.1:" + echoServer.getPort()))
                .get(60, TimeUnit.SECONDS));

            // then
            assertThat(exception.getMessage(), anyOf(
                containsString("Connection reset"),
                containsString("Channel set as inactive before valid response has been received"),
                containsString("upstream closed the connection during the TLS handshake")
            ));
        } finally {
            stopQuietly(echoServer);
        }
    }

    @Test
    public void shouldHandleSmallerContentLengthHeader() throws Exception {
        // given
        EchoServer echoServer = new EchoServer(true, EchoServer.Error.SMALLER_CONTENT_LENGTH);

        try {
            // when
            InetSocketAddress socket = new InetSocketAddress("127.0.0.1", echoServer.getPort());
            HttpResponse httpResponse = new NettyHttpClient(configuration(), mockServerLogger, clientEventLoopGroup, null, false)
                .sendRequest(
                    request()
                        .withHeader(CONTENT_TYPE.toString(), MediaType.TEXT_PLAIN.toString())
                        .withBody(exact("this is an example body"))
                        .withSecure(true),
                    socket
                )
                .get(60, TimeUnit.SECONDS);

            // then
            assertThat(httpResponse, is(
                response()
                    .withStatusCode(200)
                    .withReasonPhrase("OK")
                    .withHeader(CONTENT_TYPE.toString(), "text/plain")
                    .withHeader(header(CONNECTION.toString(), KEEP_ALIVE.toString()))
                    .withHeader(header(CONTENT_LENGTH.toString(), "this is an example body".length() / 2))
                    .withBody(exact("this is an ", MediaType.TEXT_PLAIN))
            ));
        } finally {
            stopQuietly(echoServer);
        }
    }

}
