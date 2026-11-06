package org.mockserver.netty.websocketregistry;

import io.netty.channel.Channel;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPromise;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.DefaultFullHttpRequest;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpServerCodec;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.util.ReferenceCountUtil;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockserver.closurecallback.websocketregistry.WebSocketClientRegistry;
import org.mockserver.codec.MockServerHttpServerCodec;
import org.mockserver.dashboard.DashboardWebSocketHandler;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.mock.HttpState;
import org.mockserver.netty.HttpRequestHandler;
import org.mockserver.scheduler.Scheduler;
import org.mockserver.uuid.UUIDService;

import java.io.IOException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;
import static org.hamcrest.Matchers.sameInstance;
import static org.mockito.Mockito.mock;
import static org.mockserver.closurecallback.websocketclient.WebSocketClient.CLIENT_REGISTRATION_ID_HEADER;
import static org.mockserver.configuration.Configuration.configuration;

/**
 * Clients treat the 101 handshake response as the registration acknowledgement and immediately create
 * expectations that call back over the connection, so the client id must already be routable when the
 * 101 is written; otherwise a request matched in that window is answered 404.
 */
public class CallbackWebSocketRegistrationOrderTest {

    private MockServerLogger logger;
    private Scheduler scheduler;
    private HttpState httpState;
    private WebSocketClientRegistry registry;
    private String clientId;

    @Before
    public void createState() {
        logger = new MockServerLogger(CallbackWebSocketRegistrationOrderTest.class);
        scheduler = new Scheduler(configuration(), logger, true);
        httpState = new HttpState(configuration(), logger, scheduler);
        registry = httpState.getWebSocketClientRegistry();
        clientId = UUIDService.getUUID();
    }

    @After
    public void stopState() {
        httpState.stop();
        scheduler.shutdown();
    }

    @Test
    public void shouldRegisterClientBeforeWritingHandshakeResponse() {
        AtomicBoolean handshakeWritten = new AtomicBoolean();
        AtomicReference<Channel> registeredWhenHandshakeWritten = new AtomicReference<>();
        EmbeddedChannel channel = callbackChannel(new ChannelOutboundHandlerAdapter() {
            @Override
            public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) {
                if (isHandshakeResponse(msg)) {
                    handshakeWritten.set(true);
                    registeredWhenHandshakeWritten.set(registry.registeredChannel(clientId));
                }
                ctx.write(msg, promise);
            }
        });
        try {
            channel.writeInbound(upgrade(true));

            assertThat("handshake response written", handshakeWritten.get(), is(true));
            assertThat("client routable when the handshake response is written", registeredWhenHandshakeWritten.get(), sameInstance(channel));
            assertThat("client still registered after the handshake", registry.registeredChannel(clientId), sameInstance(channel));
        } finally {
            channel.finishAndReleaseAll();
        }
    }

    @Test
    public void shouldNotRegisterClientWhenHandshakeThrows() {
        EmbeddedChannel channel = callbackChannel(new ChannelOutboundHandlerAdapter());
        try {
            channel.writeInbound(upgrade(false));
            channel.runPendingTasks();

            assertThat("upgrade channel closed", channel.isOpen(), is(false));
            assertThat("client id not registered", registry.registeredChannel(clientId), nullValue());
        } finally {
            channel.finishAndReleaseAll();
        }
    }

    @Test
    public void shouldRestorePreviousChannelWhenTakeoverHandshakeThrows() {
        EmbeddedChannel unresponsive = new EmbeddedChannel(new ChannelInboundHandlerAdapter());
        registry.registerClient(clientId, unresponsive.pipeline().firstContext());
        unresponsive.releaseOutbound();
        EmbeddedChannel channel = callbackChannel(new ChannelOutboundHandlerAdapter());
        try {
            channel.writeInbound(upgrade(false));
            channel.advanceTimeBy(CallbackClientIdClaim.LIVENESS_TIMEOUT_MILLIS + 100, TimeUnit.MILLISECONDS);
            channel.runScheduledPendingTasks();
            channel.runPendingTasks();

            assertThat("upgrade channel closed", channel.isOpen(), is(false));
            assertThat("client id back on the previous channel", registry.registeredChannel(clientId), sameInstance(unresponsive));
            assertThat("takeover reservation released", registry.reserveTakeover(clientId, new EmbeddedChannel()), is(true));
        } finally {
            channel.finishAndReleaseAll();
            unresponsive.finishAndReleaseAll();
        }
    }

    @Test
    public void shouldNotRegisterClientWhenHandshakeResponseWriteFails() {
        EmbeddedChannel channel = callbackChannel(new ChannelOutboundHandlerAdapter() {
            @Override
            public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) {
                if (isHandshakeResponse(msg)) {
                    ReferenceCountUtil.release(msg);
                    promise.setFailure(new IOException("write failed"));
                } else {
                    ctx.write(msg, promise);
                }
            }
        });
        try {
            channel.writeInbound(upgrade(true));
            channel.runPendingTasks();

            assertThat("upgrade channel closed", channel.isOpen(), is(false));
            assertThat("client id not registered", registry.registeredChannel(clientId), nullValue());
        } finally {
            channel.finishAndReleaseAll();
        }
    }

    private EmbeddedChannel callbackChannel(ChannelHandler outboundInterceptor) {
        return new EmbeddedChannel(
            new HttpServerCodec(),
            outboundInterceptor,
            new CallbackWebSocketServerHandler(httpState),
            new MockServerHttpServerCodec(configuration(), logger, false, null, (Integer) null),
            new DashboardWebSocketHandler(httpState, false, false),
            mock(HttpRequestHandler.class)
        );
    }

    private FullHttpRequest upgrade(boolean withKey) {
        FullHttpRequest upgrade = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, "/_mockserver_callback_websocket");
        upgrade.headers().set("Host", "localhost").set("Upgrade", "websocket").set("Connection", "Upgrade")
            .set("Sec-WebSocket-Version", "13")
            .set(CLIENT_REGISTRATION_ID_HEADER, clientId);
        if (withKey) {
            upgrade.headers().set("Sec-WebSocket-Key", "dGhlIHNhbXBsZSBub25jZQ==");
        }
        return upgrade;
    }

    private static boolean isHandshakeResponse(Object msg) {
        return msg instanceof HttpResponse && ((HttpResponse) msg).status().equals(HttpResponseStatus.SWITCHING_PROTOCOLS);
    }
}
