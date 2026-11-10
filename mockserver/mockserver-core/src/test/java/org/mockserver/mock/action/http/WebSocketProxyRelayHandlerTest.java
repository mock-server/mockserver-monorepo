package org.mockserver.mock.action.http;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.websocketx.WebSocket13FrameDecoder;
import io.netty.handler.codec.http.websocketx.WebSocket13FrameEncoder;
import org.junit.Test;
import org.mockserver.model.HttpRequest;

import java.util.ArrayList;
import java.util.List;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Unit tests for {@link WebSocketProxyRelayHandler}'s protocol-detection and pipeline-stripping helpers, plus the
 * WebSocket-upgrade recognition predicate. The relay's networked behaviour (upstream handshake, frame relay, TLS,
 * recording) is covered by the netty-module {@code WebSocketProxyPassthroughIntegrationTest}.
 */
public class WebSocketProxyRelayHandlerTest {

    @Test
    public void shouldRecogniseWebSocketUpgradeRequest() {
        assertTrue(WebSocketProxyRelayHandler.isWebSocketUpgrade(
            HttpRequest.request().withMethod("GET").withPath("/ws")
                .withHeader("Upgrade", "websocket")
                .withHeader("Connection", "Upgrade")
                .withHeader("Sec-WebSocket-Key", "dGhlIHNhbXBsZSBub25jZQ==")));
        // case-insensitive header values
        assertTrue(WebSocketProxyRelayHandler.isWebSocketUpgrade(
            HttpRequest.request().withMethod("GET").withPath("/ws")
                .withHeader("Upgrade", "WebSocket")
                .withHeader("Connection", "keep-alive, Upgrade")
                .withHeader("Sec-WebSocket-Key", "abc")));
    }

    @Test
    public void shouldNotRelayForceResponseIndexControlHeaderToUpstream() {
        // given - a WS upgrade request carrying the force-response-variant control header plus an ordinary
        // custom header and the Netty-managed handshake headers
        HttpRequest request = HttpRequest.request().withMethod("GET").withPath("/ws")
            .withHeader("Upgrade", "websocket")
            .withHeader("Connection", "Upgrade")
            .withHeader("Sec-WebSocket-Key", "dGhlIHNhbXBsZSBub25jZQ==")
            .withHeader("x-custom", "keep-me")
            .withHeader(org.mockserver.mock.Expectation.FORCE_RESPONSE_INDEX_HEADER, "1");

        // when - the upstream handshake headers are built (this passthrough path bypasses the HTTP mapper)
        io.netty.handler.codec.http.HttpHeaders forwarded = WebSocketProxyRelayHandler.buildUpstreamCustomHeaders(request);

        // then - the control header is filtered out, ordinary custom headers survive, handshake headers excluded
        assertFalse(forwarded.contains(org.mockserver.mock.Expectation.FORCE_RESPONSE_INDEX_HEADER));
        assertTrue(forwarded.contains("x-custom"));
        assertThat(forwarded.get("x-custom"), is("keep-me"));
        assertFalse(forwarded.contains("Sec-WebSocket-Key"));
        assertFalse(forwarded.contains("Upgrade"));
    }

    @Test
    public void shouldStripProxyCredentialsFromWebSocketHandshakeButPreserveOriginCredentials() {
        HttpRequest request = HttpRequest.request("/ws")
            .withHeader("pRoXy-AuThOrIzAtIoN", "Basic proxy-secret")
            .withHeader("Proxy-Connection", "keep-alive")
            .withHeader("Authorization", "Bearer origin-secret")
            .withHeader("Cookie", "session=origin-cookie");

        io.netty.handler.codec.http.HttpHeaders forwarded = WebSocketProxyRelayHandler.buildUpstreamCustomHeaders(request);

        assertFalse(forwarded.contains("Proxy-Authorization"));
        assertFalse(forwarded.contains("Proxy-Connection"));
        assertThat(forwarded.get("Authorization"), is("Bearer origin-secret"));
        assertThat(forwarded.get("Cookie"), is("session=origin-cookie"));
    }

    @Test
    public void shouldSuppressForceResponseIndexHeaderCaseInsensitively() {
        assertTrue(WebSocketProxyRelayHandler.isSuppressedRelayHeader(org.mockserver.mock.Expectation.FORCE_RESPONSE_INDEX_HEADER));
        assertTrue(WebSocketProxyRelayHandler.isSuppressedRelayHeader("X-MockServer-Response-Index"));
        assertFalse(WebSocketProxyRelayHandler.isSuppressedRelayHeader("x-other"));
    }

    @Test
    public void shouldRejectNonUpgradeRequests() {
        assertFalse(WebSocketProxyRelayHandler.isWebSocketUpgrade(null));
        // POST is not a WS upgrade
        assertFalse(WebSocketProxyRelayHandler.isWebSocketUpgrade(
            HttpRequest.request().withMethod("POST").withPath("/ws")
                .withHeader("Upgrade", "websocket").withHeader("Connection", "Upgrade")
                .withHeader("Sec-WebSocket-Key", "abc")));
        // missing Sec-WebSocket-Key
        assertFalse(WebSocketProxyRelayHandler.isWebSocketUpgrade(
            HttpRequest.request().withMethod("GET").withPath("/ws")
                .withHeader("Upgrade", "websocket").withHeader("Connection", "Upgrade")));
        // plain HTTP GET
        assertFalse(WebSocketProxyRelayHandler.isWebSocketUpgrade(
            HttpRequest.request().withMethod("GET").withPath("/health")));
    }

    /**
     * After the downstream handshake, the client pipeline must retain ONLY the WebSocket frame codec (installed by the
     * server handshaker) plus the relay handler — every HTTP server handler must be stripped so no HTTP request
     * handling or codec remains on the now-WebSocket channel. Handlers are named with the exact simple class names the
     * real pipeline uses so the by-simple-name removal is exercised faithfully.
     */
    @Test
    public void shouldStripHttpServerHandlersLeavingOnlyWebSocketCodecAndRelay() {
        EmbeddedChannel channel = new EmbeddedChannel();
        // WebSocket codec left in place by the server handshaker
        channel.pipeline().addLast("wsDecoder", new WebSocket13FrameDecoder(true, true, 65536));
        channel.pipeline().addLast("wsEncoder", new WebSocket13FrameEncoder(false));
        // HTTP server handlers that MUST be removed (named by their real simple class names)
        channel.pipeline().addLast(new MockServerHttpServerCodec());
        channel.pipeline().addLast(new HttpContentLengthRemover());
        channel.pipeline().addLast(new EarlyMatchingHandler());
        channel.pipeline().addLast(new CallbackWebSocketServerHandler());
        channel.pipeline().addLast(new DashboardWebSocketHandler());
        channel.pipeline().addLast(new McpStreamableHttpHandler());
        channel.pipeline().addLast(new HttpRequestHandler());
        // the relay handler that must remain
        channel.pipeline().addLast("relay", new RelayMarker());

        ChannelHandlerContext ctx = channel.pipeline().context("wsDecoder");
        WebSocketProxyRelayHandler.removeHttpServerHandlers(ctx);

        List<String> remaining = new ArrayList<>();
        channel.pipeline().forEach(entry -> remaining.add(entry.getValue().getClass().getSimpleName()));

        assertThat(remaining, hasItems("WebSocket13FrameDecoder", "WebSocket13FrameEncoder", "RelayMarker"));
        assertThat(remaining, not(hasItem("MockServerHttpServerCodec")));
        assertThat(remaining, not(hasItem("HttpRequestHandler")));
        assertThat(remaining, not(hasItem("HttpContentLengthRemover")));
        assertThat(remaining, not(hasItem("EarlyMatchingHandler")));
        assertThat(remaining, not(hasItem("CallbackWebSocketServerHandler")));
        assertThat(remaining, not(hasItem("DashboardWebSocketHandler")));
        assertThat(remaining, not(hasItem("McpStreamableHttpHandler")));
        channel.finishAndReleaseAll();
    }

    // ---- dummy handlers named exactly like the real HTTP server handlers so the by-simple-name strip is exercised ---
    private static final class MockServerHttpServerCodec extends ChannelInboundHandlerAdapter { }
    private static final class HttpRequestHandler extends ChannelInboundHandlerAdapter { }
    private static final class HttpContentLengthRemover extends ChannelInboundHandlerAdapter { }
    private static final class EarlyMatchingHandler extends ChannelInboundHandlerAdapter { }
    private static final class CallbackWebSocketServerHandler extends ChannelInboundHandlerAdapter { }
    private static final class DashboardWebSocketHandler extends ChannelInboundHandlerAdapter { }
    private static final class McpStreamableHttpHandler extends ChannelInboundHandlerAdapter { }
    private static final class RelayMarker extends ChannelInboundHandlerAdapter { }
}
