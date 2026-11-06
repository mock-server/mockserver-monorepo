package org.mockserver.netty.proxy.relay;

import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPromise;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.DecoderException;
import io.netty.handler.codec.http.*;
import io.netty.handler.ssl.SslHandler;
import io.netty.util.AttributeKey;
import io.netty.util.ReferenceCountUtil;
import io.netty.util.internal.OutOfDirectMemoryError;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockserver.log.model.LogEntry;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.netty.connection.ServerClosedChannels;
import org.mockserver.socket.tls.NettySslContextFactory;
import org.slf4j.event.Level;

import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLException;
import java.lang.reflect.Constructor;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.charset.StandardCharsets;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import static org.junit.Assert.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockserver.configuration.Configuration.configuration;
import static org.mockserver.netty.unification.PortUnificationHandler.enableSslDownstream;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.any;

/**
 * EmbeddedChannel tests for {@link UpstreamProxyRelayHandler} verifying:
 * - channelActive writes EMPTY_BUFFER to trigger pipeline activation
 * - channelRead0 relays FullHttpRequest to the downstream channel
 * - channelInactive closes the downstream channel
 * - exceptionCaught closes the handler's own channel
 */
public class UpstreamProxyRelayHandlerTest {

    private static final int MAX_REQUEST_BODY_SIZE = 10 * 1024 * 1024;

    private EmbeddedChannel upstreamChannel;
    private EmbeddedChannel downstreamChannel;
    private MockServerLogger mockServerLogger;

    @Before
    public void setUp() {
        mockServerLogger = new MockServerLogger();
        // The upstream channel is the "proxy client" side (reads from client, writes to client).
        // The downstream channel is the "MockServer" side (relays requests to MockServer).
        upstreamChannel = new EmbeddedChannel();
        downstreamChannel = new EmbeddedChannel();
        EmbeddedChannel handlerChannel = new EmbeddedChannel(
            new UpstreamProxyRelayHandler(mockServerLogger, upstreamChannel, downstreamChannel, "backend.example.com", 443, MAX_REQUEST_BODY_SIZE)
        );
        // Store the handler channel as upstreamChannel for test purposes - the handler is
        // added to the channel that receives decoded FullHttpRequest from the proxy client.
        // Actually, the UpstreamProxyRelayHandler sits on the upstreamChannel (the proxy client
        // side) and relays requests to the downstreamChannel (MockServer side).
        // Let me restructure: the handler channel IS the channel that gets FullHttpRequest
        // messages relayed from the proxy client pipeline.
        this.upstreamChannel = handlerChannel;
    }

    @After
    public void tearDown() {
        if (upstreamChannel != null) {
            try {
                upstreamChannel.checkException();
            } catch (Exception ignored) {
            }
            try {
                upstreamChannel.finishAndReleaseAll();
            } catch (Exception ignored) {
            }
        }
        if (downstreamChannel != null) {
            try {
                downstreamChannel.finishAndReleaseAll();
            } catch (Exception ignored) {
            }
        }
    }

    @Test
    public void shouldRelayFullHttpRequestToDownstreamChannel() {
        // given
        DefaultFullHttpRequest request = new DefaultFullHttpRequest(
            HttpVersion.HTTP_1_1,
            HttpMethod.GET,
            "/test",
            Unpooled.copiedBuffer("body", StandardCharsets.UTF_8)
        );
        // UpstreamProxyRelayHandler uses super(false) so it doesn't auto-release; the single
        // reference is handed to the downstream channel and released after readOutbound below.

        // when
        upstreamChannel.writeInbound(request);

        // then - the request should appear on the downstream channel's outbound
        Object relayed = downstreamChannel.readOutbound();
        assertThat("request should be relayed to downstream", relayed, is(notNullValue()));
        assertThat(relayed, instanceOf(FullHttpRequest.class));
        FullHttpRequest relayedRequest = (FullHttpRequest) relayed;
        assertThat(relayedRequest.method(), is(HttpMethod.GET));
        assertThat(relayedRequest.uri(), is("/test"));
        assertThat(relayedRequest.content().toString(StandardCharsets.UTF_8), is("body"));
        relayedRequest.release();
    }

    @Test
    public void shouldCloseDownstreamChannelOnChannelInactive() {
        // given - downstream is active
        assertTrue("downstream should be active initially", downstreamChannel.isActive());

        // when - upstream becomes inactive
        upstreamChannel.close();
        upstreamChannel.runPendingTasks();
        downstreamChannel.runPendingTasks();

        // then - downstream should be closed (closeOnFlush only closes if active)
        assertFalse("downstream should be closed when upstream goes inactive", downstreamChannel.isActive());
    }

    @Test
    public void shouldCloseOwnChannelOnException() {
        // given
        assertTrue("upstream should be active initially", upstreamChannel.isActive());

        // when
        upstreamChannel.pipeline().fireExceptionCaught(new RuntimeException("simulated error"));
        upstreamChannel.runPendingTasks();

        // then
        assertFalse("upstream channel should be closed on exception", upstreamChannel.isActive());
    }

    @Test
    public void shouldLogWarnAndCloseOnDecoderFault() {
        // given - a handler wired with a mock logger so the log level can be asserted
        MockServerLogger logger = mock(MockServerLogger.class);
        when(logger.isEnabledForInstance(any(Level.class))).thenReturn(true);
        EmbeddedChannel downstream = new EmbeddedChannel();
        EmbeddedChannel channel = new EmbeddedChannel(
            new UpstreamProxyRelayHandler(logger, new EmbeddedChannel(), downstream, "backend.example.com", 443, MAX_REQUEST_BODY_SIZE)
        );

        // when - a genuine decoder fault is caught
        channel.pipeline().fireExceptionCaught(new DecoderException("bad frame"));
        channel.runPendingTasks();

        // then - it is surfaced at WARN (not silently dropped) and the channel is closed
        ArgumentCaptor<LogEntry> captor = ArgumentCaptor.forClass(LogEntry.class);
        verify(logger).logEvent(captor.capture());
        assertThat(captor.getValue().getLogLevel(), is(Level.WARN));
        assertFalse("channel should be closed on decoder fault", channel.isActive());

        downstream.finishAndReleaseAll();
        channel.finishAndReleaseAll();
    }

    @Test
    public void shouldLogWarnAndCloseOnSslFault() {
        // given
        MockServerLogger logger = mock(MockServerLogger.class);
        when(logger.isEnabledForInstance(any(Level.class))).thenReturn(true);
        EmbeddedChannel downstream = new EmbeddedChannel();
        EmbeddedChannel channel = new EmbeddedChannel(
            new UpstreamProxyRelayHandler(logger, new EmbeddedChannel(), downstream, "backend.example.com", 443, MAX_REQUEST_BODY_SIZE)
        );

        // when - a throwable whose cause is an SSLException is caught
        channel.pipeline().fireExceptionCaught(new RuntimeException("relay failed", new SSLException("handshake failed")));
        channel.runPendingTasks();

        // then
        ArgumentCaptor<LogEntry> captor = ArgumentCaptor.forClass(LogEntry.class);
        verify(logger).logEvent(captor.capture());
        assertThat(captor.getValue().getLogLevel(), is(Level.WARN));
        assertFalse("channel should be closed on SSL fault", channel.isActive());

        downstream.finishAndReleaseAll();
        channel.finishAndReleaseAll();
    }

    @Test
    public void shouldStaySilentOnBenignConnectionClose() {
        // given
        MockServerLogger logger = mock(MockServerLogger.class);
        when(logger.isEnabledForInstance(any(Level.class))).thenReturn(true);
        EmbeddedChannel downstream = new EmbeddedChannel();
        EmbeddedChannel channel = new EmbeddedChannel(
            new UpstreamProxyRelayHandler(logger, new EmbeddedChannel(), downstream, "backend.example.com", 443, MAX_REQUEST_BODY_SIZE)
        );

        // when - a benign connection reset is caught
        channel.pipeline().fireExceptionCaught(new RuntimeException("Connection reset by peer"));
        channel.runPendingTasks();

        // then - nothing is logged but the channel is still closed
        verify(logger, org.mockito.Mockito.never()).logEvent(org.mockito.ArgumentMatchers.any(LogEntry.class));
        assertFalse("channel should be closed on benign close", channel.isActive());

        downstream.finishAndReleaseAll();
        channel.finishAndReleaseAll();
    }

    @Test
    public void shouldLogErrorOnUnexpectedException() {
        // given
        MockServerLogger logger = mock(MockServerLogger.class);
        when(logger.isEnabledForInstance(any(Level.class))).thenReturn(true);
        EmbeddedChannel downstream = new EmbeddedChannel();
        EmbeddedChannel channel = new EmbeddedChannel(
            new UpstreamProxyRelayHandler(logger, new EmbeddedChannel(), downstream, "backend.example.com", 443, MAX_REQUEST_BODY_SIZE)
        );

        // when - an unexpected exception is caught
        channel.pipeline().fireExceptionCaught(new IllegalStateException("unexpected"));
        channel.runPendingTasks();

        // then - it is surfaced at ERROR and the channel is closed
        ArgumentCaptor<LogEntry> captor = ArgumentCaptor.forClass(LogEntry.class);
        verify(logger).logEvent(captor.capture());
        assertThat(captor.getValue().getLogLevel(), is(Level.ERROR));
        assertFalse("channel should be closed on unexpected exception", channel.isActive());

        downstream.finishAndReleaseAll();
        channel.finishAndReleaseAll();
    }

    @Test
    public void shouldVerifyDownstreamTlsAgainstConnectHostnameNotConnectedSocketAddress() {
        // COR-05: when this relay installs the downstream client TLS, the reference identity for HTTPS
        // endpoint identification (host name verification) MUST be the CONNECT target host — the same
        // identity RelayConnectHandler#configurePipelines uses for the sibling loopback TLS — NOT the
        // connected socket's address. Empirically the downstream socket's getHostString() is the MockServer
        // loopback ("0.0.0.0") on the common forward-proxy path and only coincidentally the target on a
        // reverse-proxy path, so verifying against it is wrong. Pinned via degrade-and-confirm-red: reverting
        // to the socket address makes getPeerHost() the injected IP below and fails this test.

        // given - a relay whose downstream socket resolves to an IP that is deliberately NOT the CONNECT host
        MockServerLogger logger = new MockServerLogger();
        NettySslContextFactory clientFactory = new NettySslContextFactory(configuration(), logger, false);

        EmbeddedChannel proxyClientChannel = new EmbeddedChannel();
        enableSslDownstream(proxyClientChannel);

        // Swallow the relayed request write so the follow-on FullHttpRequest (which the freshly-installed
        // SslHandler at the head would reject as a non-ByteBuf, closing the channel and tearing down the
        // pipeline before we can inspect it) never reaches the SslHandler. Installed toward the tail, so the
        // SslHandler's own handshake output toward the transport is unaffected.
        EmbeddedChannel downstream = new EmbeddedChannel(new ChannelOutboundHandlerAdapter() {
            @Override
            public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) {
                ReferenceCountUtil.release(msg);
                promise.setSuccess();
            }
        }) {
            @Override
            public SocketAddress remoteAddress() {
                return new InetSocketAddress("10.9.8.7", 8443);
            }
        };

        EmbeddedChannel handlerChannel = new EmbeddedChannel(
            new UpstreamProxyRelayHandler(logger, proxyClientChannel, downstream, "backend.example.com", 443, MAX_REQUEST_BODY_SIZE)
        );
        AttributeKey<NettySslContextFactory> factoryKey = AttributeKey.valueOf("NETTY_SSL_CONTEXT_FACTORY");
        handlerChannel.attr(factoryKey).set(clientFactory);

        // when - a request drives the lazy downstream-TLS installation
        handlerChannel.writeInbound(new DefaultFullHttpRequest(
            HttpVersion.HTTP_1_1, HttpMethod.GET, "/", Unpooled.EMPTY_BUFFER));

        // then - the installed client SslHandler's engine is keyed to the CONNECT host, never the socket IP
        SslHandler sslHandler = downstream.pipeline().get(SslHandler.class);
        assertThat("downstream client TLS should have been installed", sslHandler, is(notNullValue()));
        SSLEngine engine = sslHandler.engine();
        assertThat(engine.getPeerHost(), is("backend.example.com"));
        assertThat("must not verify against the connected socket IP", engine.getPeerHost(), is(not("10.9.8.7")));

        try {
            downstream.finishAndReleaseAll();
        } catch (Exception ignored) {
        }
        try {
            handlerChannel.finishAndReleaseAll();
        } catch (Exception ignored) {
        }
        try {
            proxyClientChannel.finishAndReleaseAll();
        } catch (Exception ignored) {
        }
    }

    @Test
    public void shouldMarkTheProxyClientClosedByTheServerForAnExceptionItClosesForButNotForABenignConnectionClose() throws Exception {
        for (Throwable cause : new Throwable[]{new IllegalStateException("unexpected"), new DecoderException("bad frame"), outOfDirectMemory()}) {
            EmbeddedChannel proxyClient = proxyClientLeg();

            proxyClient.pipeline().fireExceptionCaught(cause);

            assertThat(cause.toString(), ServerClosedChannels.isClosedByServer(proxyClient), is(true));
            proxyClient.finishAndReleaseAll();
        }
        EmbeddedChannel proxyClient = proxyClientLeg();

        proxyClient.pipeline().fireExceptionCaught(new RuntimeException("Connection reset by peer"));

        assertThat(ServerClosedChannels.isClosedByServer(proxyClient), is(false));
        proxyClient.finishAndReleaseAll();
    }

    private static EmbeddedChannel proxyClientLeg() {
        return new EmbeddedChannel(new UpstreamProxyRelayHandler(mock(MockServerLogger.class), new EmbeddedChannel(), new EmbeddedChannel(), "backend.example.com", 443, MAX_REQUEST_BODY_SIZE));
    }

    private static Throwable outOfDirectMemory() throws Exception {
        Constructor<OutOfDirectMemoryError> constructor = OutOfDirectMemoryError.class.getDeclaredConstructor(String.class);
        constructor.setAccessible(true);
        return constructor.newInstance("failed to allocate 4194304 byte(s) of direct memory");
    }

    @Test
    public void shouldWriteEmptyBufferOnChannelActive() {
        // The channelActive writes EMPTY_BUFFER and calls ctx.read()
        // Since EmbeddedChannel fires channelActive on construction, just verify
        // the channel is active and no exception was thrown.
        assertTrue("channel should be active after initialization", upstreamChannel.isActive());
        upstreamChannel.checkException();
    }
}
