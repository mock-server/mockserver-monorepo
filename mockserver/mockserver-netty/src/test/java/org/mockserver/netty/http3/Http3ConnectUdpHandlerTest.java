package org.mockserver.netty.http3;

import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.epoll.Epoll;
import io.netty.channel.epoll.EpollDatagramChannel;
import io.netty.channel.epoll.EpollEventLoopGroup;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.handler.codec.http3.DefaultHttp3DataFrame;
import io.netty.handler.codec.http3.DefaultHttp3HeadersFrame;
import io.netty.handler.codec.http3.Http3DataFrame;
import io.netty.handler.codec.http3.Http3HeadersFrame;
import io.netty.handler.codec.DecoderException;
import io.netty.handler.codec.http3.Http3ErrorCode;
import io.netty.handler.codec.quic.QuicStreamResetException;
import org.junit.Assume;
import org.junit.Test;
import org.mockserver.configuration.Configuration;
import org.mockserver.log.model.LogEntry;
import org.mockserver.logging.MockServerLogger;
import org.slf4j.event.Level;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.PortUnreachableException;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.DatagramChannel;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockserver.configuration.Configuration.configuration;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

/**
 * Unit tests for {@link Http3ConnectUdpHandler}.
 * <p>
 * These tests use an {@link EmbeddedChannel} so they do not require the native
 * QUIC transport and will run on all platforms.
 */
public class Http3ConnectUdpHandlerTest {

    @Test
    public void shouldPassThroughNonConnectRequests() {
        EmbeddedChannel channel = new EmbeddedChannel(new Http3ConnectUdpHandler(configuration(), new CapturingLogger(Level.INFO)));

        // Send a normal GET request
        DefaultHttp3HeadersFrame getHeaders = new DefaultHttp3HeadersFrame();
        getHeaders.headers().method("GET");
        getHeaders.headers().path("/hello");
        getHeaders.headers().scheme("https");
        getHeaders.headers().authority("example.com");

        channel.writeInbound(getHeaders);

        // The handler should NOT have written any outbound response
        assertNull("should not write outbound for non-CONNECT", channel.readOutbound());

        // The frame should have been passed to the next handler (inbound)
        Http3HeadersFrame passedThrough = channel.readInbound();
        assertNotNull("GET request should pass through to the next handler", passedThrough);
        assertThat("method should be GET", passedThrough.headers().method().toString(), is("GET"));
        assertThat("path should be /hello", passedThrough.headers().path().toString(), is("/hello"));

        channel.finish();
    }

    @Test
    public void shouldPassThroughPostRequests() {
        EmbeddedChannel channel = new EmbeddedChannel(new Http3ConnectUdpHandler(configuration(), new CapturingLogger(Level.INFO)));

        DefaultHttp3HeadersFrame postHeaders = new DefaultHttp3HeadersFrame();
        postHeaders.headers().method("POST");
        postHeaders.headers().path("/api/data");
        postHeaders.headers().scheme("https");
        postHeaders.headers().authority("example.com");

        channel.writeInbound(postHeaders);

        // POST should pass through
        assertNull("should not write outbound for POST", channel.readOutbound());
        Http3HeadersFrame passedThrough = channel.readInbound();
        assertNotNull("POST request should pass through", passedThrough);
        assertThat("method should be POST", passedThrough.headers().method().toString(), is("POST"));

        channel.finish();
    }

    @Test
    public void shouldPassThroughPlainConnectWithoutProtocol() {
        EmbeddedChannel channel = new EmbeddedChannel(new Http3ConnectUdpHandler(configuration(), new CapturingLogger(Level.INFO)));

        // Plain CONNECT (no :protocol) -- should pass through to mock handler
        DefaultHttp3HeadersFrame connectHeaders = new DefaultHttp3HeadersFrame();
        connectHeaders.headers().method("CONNECT");
        connectHeaders.headers().authority("target.example.com:443");

        channel.writeInbound(connectHeaders);

        // Plain CONNECT should pass through (not handled by MASQUE handler)
        assertNull("should not write outbound for plain CONNECT", channel.readOutbound());
        Http3HeadersFrame passedThrough = channel.readInbound();
        assertNotNull("plain CONNECT should pass through to the next handler", passedThrough);
        assertThat("method should be CONNECT", passedThrough.headers().method().toString(), is("CONNECT"));

        channel.finish();
    }

    @Test
    public void shouldPassThroughDataFrames() {
        EmbeddedChannel channel = new EmbeddedChannel(new Http3ConnectUdpHandler(configuration(), new CapturingLogger(Level.INFO)));

        // Data frames before tunnel is established should pass through
        DefaultHttp3DataFrame dataFrame = new DefaultHttp3DataFrame(
            io.netty.buffer.Unpooled.wrappedBuffer("test data".getBytes(StandardCharsets.UTF_8))
        );

        channel.writeInbound(dataFrame);

        assertNull("should not write outbound for data frame", channel.readOutbound());
        Http3DataFrame passedThrough = channel.readInbound();
        assertNotNull("data frame should pass through", passedThrough);
        String content = passedThrough.content().toString(StandardCharsets.UTF_8);
        assertThat("content should match", content, is("test data"));
        passedThrough.release();

        channel.finish();
    }

    @Test
    public void shouldRejectConnectUdpWithMissingAuthority() {
        EmbeddedChannel channel = new EmbeddedChannel(new Http3ConnectUdpHandler(configuration(), new CapturingLogger(Level.INFO)));

        // Extended CONNECT with :protocol=connect-udp but no :authority
        DefaultHttp3HeadersFrame connectHeaders = new DefaultHttp3HeadersFrame();
        connectHeaders.headers().method("CONNECT");
        connectHeaders.headers().protocol("connect-udp");
        // no :authority set

        channel.writeInbound(connectHeaders);

        // Should get a 400 error response
        Http3HeadersFrame responseHeaders = channel.readOutbound();
        assertNotNull("should write error response headers", responseHeaders);
        assertThat("status should be 400",
            responseHeaders.headers().status().toString(), is("400"));

        Http3DataFrame responseBody = channel.readOutbound();
        assertNotNull("should write error body", responseBody);
        String body = responseBody.content().toString(StandardCharsets.UTF_8);
        assertThat("body should explain missing authority",
            body, containsString("Missing :authority"));
        responseBody.release();

        channel.finish();
    }

    @Test
    public void shouldRejectConnectUdpWithInvalidAuthority() {
        EmbeddedChannel channel = new EmbeddedChannel(new Http3ConnectUdpHandler(configuration(), new CapturingLogger(Level.INFO)));

        // Extended CONNECT with invalid authority (no port)
        DefaultHttp3HeadersFrame connectHeaders = new DefaultHttp3HeadersFrame();
        connectHeaders.headers().method("CONNECT");
        connectHeaders.headers().protocol("connect-udp");
        connectHeaders.headers().authority("no-port-here");

        channel.writeInbound(connectHeaders);

        // Should get a 400 error response
        Http3HeadersFrame responseHeaders = channel.readOutbound();
        assertNotNull("should write error response headers", responseHeaders);
        assertThat("status should be 400",
            responseHeaders.headers().status().toString(), is("400"));

        Http3DataFrame responseBody = channel.readOutbound();
        assertNotNull("should write error body", responseBody);
        String body = responseBody.content().toString(StandardCharsets.UTF_8);
        assertThat("body should explain invalid authority",
            body, containsString("Invalid :authority"));
        responseBody.release();

        channel.finish();
    }

    // ---- allowlist + SSRF rejection tests (EmbeddedChannel, no native QUIC needed) ----

    @Test
    public void shouldRejectConnectUdpTargetNotInAllowlist() {
        Configuration config = configuration()
            .http3ConnectUdpAllowedTargets("allowed.example.com:443");
        EmbeddedChannel channel = new EmbeddedChannel(new Http3ConnectUdpHandler(config, new CapturingLogger(Level.INFO)));

        DefaultHttp3HeadersFrame connectHeaders = new DefaultHttp3HeadersFrame();
        connectHeaders.headers().method("CONNECT");
        connectHeaders.headers().protocol("connect-udp");
        connectHeaders.headers().authority("other.example.com:443");

        channel.writeInbound(connectHeaders);

        Http3HeadersFrame responseHeaders = channel.readOutbound();
        assertNotNull("should write error response headers", responseHeaders);
        assertThat("status should be 403 for a non-allowlisted target",
            responseHeaders.headers().status().toString(), is("403"));

        Http3DataFrame responseBody = channel.readOutbound();
        assertNotNull("should write error body", responseBody);
        String allowlistBody = responseBody.content().toString(StandardCharsets.UTF_8);
        assertThat("body should be the generic refusal (no internal detail leaked)",
            allowlistBody, containsString("CONNECT-UDP target not permitted"));
        assertThat("body must NOT leak the allowlist property name or target host",
            allowlistBody, not(anyOf(containsString("http3ConnectUdpAllowedTargets"), containsString("other.example.com"))));
        responseBody.release();

        channel.finish();
    }

    @Test
    public void shouldRejectUnresolvableConnectUdpTarget() {
        EmbeddedChannel channel = new EmbeddedChannel(new Http3ConnectUdpHandler(configuration(), new CapturingLogger(Level.INFO)));

        DefaultHttp3HeadersFrame connectHeaders = new DefaultHttp3HeadersFrame();
        connectHeaders.headers().method("CONNECT");
        connectHeaders.headers().protocol("connect-udp");
        // .invalid is a reserved TLD (RFC 6761) that never resolves
        connectHeaders.headers().authority("nonexistent.invalid:443");

        channel.writeInbound(connectHeaders);

        Http3HeadersFrame responseHeaders = channel.readOutbound();
        assertNotNull("should write error response headers", responseHeaders);
        assertThat("status should be 403 for an unresolvable target",
            responseHeaders.headers().status().toString(), is("403"));

        Http3DataFrame responseBody = channel.readOutbound();
        assertNotNull("should write error body", responseBody);
        assertThat("body should be the generic refusal",
            responseBody.content().toString(StandardCharsets.UTF_8),
            containsString("CONNECT-UDP target not permitted"));
        responseBody.release();

        channel.finish();
    }

    @Test
    public void shouldRejectConnectUdpToLoopbackWhenSsrfBlockingEnabled() {
        Configuration config = configuration()
            .forwardProxyBlockPrivateNetworks(true);
        EmbeddedChannel channel = new EmbeddedChannel(new Http3ConnectUdpHandler(config, new CapturingLogger(Level.INFO)));

        DefaultHttp3HeadersFrame connectHeaders = new DefaultHttp3HeadersFrame();
        connectHeaders.headers().method("CONNECT");
        connectHeaders.headers().protocol("connect-udp");
        connectHeaders.headers().authority("127.0.0.1:53");

        channel.writeInbound(connectHeaders);

        Http3HeadersFrame responseHeaders = channel.readOutbound();
        assertNotNull("should write error response headers", responseHeaders);
        assertThat("status should be 403 for a blocked loopback target",
            responseHeaders.headers().status().toString(), is("403"));

        Http3DataFrame responseBody = channel.readOutbound();
        assertNotNull("should write error body", responseBody);
        String ssrfBody = responseBody.content().toString(StandardCharsets.UTF_8);
        assertThat("body should be the generic refusal (identical to the allowlist refusal)",
            ssrfBody, containsString("CONNECT-UDP target not permitted"));
        assertThat("body must NOT leak the blocked host or reason",
            ssrfBody, not(anyOf(containsString("127.0.0.1"), containsString("loopback"), containsString("blocked"))));
        responseBody.release();

        channel.finish();
    }

    // ---- proxy authentication ----

    @Test
    public void shouldRequireProxyAuthenticationForConnectUdpWhenConfigured() {
        Http3HeadersFrame responseHeaders = connectUdpWithCredentials(null);

        assertThat(responseHeaders.headers().status().toString(), is("407"));
        assertThat(String.valueOf(responseHeaders.headers().get("proxy-authenticate")), startsWith("Basic realm="));
    }

    @Test(expected = NullPointerException.class)
    public void shouldRequireAConfigurationSoProxyAuthenticationCannotBeSkipped() {
        new Http3ConnectUdpHandler(null, new CapturingLogger(Level.INFO));
    }

    @Test
    public void shouldRefuseConnectUdpWithWrongProxyCredentials() {
        Http3HeadersFrame responseHeaders = connectUdpWithCredentials("proxy-user:wrong");

        assertThat(responseHeaders.headers().status().toString(), is("407"));
    }

    @Test
    public void shouldAcceptConnectUdpWithProxyCredentials() {
        // the target is not allowlisted, so a 403 shows the credentials were accepted without opening a relay socket
        Http3HeadersFrame responseHeaders = connectUdpWithCredentials("proxy-user:proxy-password");

        assertThat(responseHeaders.headers().status().toString(), is("403"));
    }

    private static Http3HeadersFrame connectUdpWithCredentials(String usernameAndPassword) {
        Configuration config = configuration()
            .proxyAuthenticationUsername("proxy-user")
            .proxyAuthenticationPassword("proxy-password")
            .http3ConnectUdpAllowedTargets("allowed.example.com:443");
        EmbeddedChannel channel = new EmbeddedChannel(new Http3ConnectUdpHandler(config, new CapturingLogger(Level.INFO)));
        try {
            DefaultHttp3HeadersFrame connectHeaders = new DefaultHttp3HeadersFrame();
            connectHeaders.headers().method("CONNECT");
            connectHeaders.headers().protocol("connect-udp");
            connectHeaders.headers().authority("192.0.2.1:443");
            if (usernameAndPassword != null) {
                connectHeaders.headers().add("proxy-authorization", "Basic " + java.util.Base64.getEncoder().encodeToString(usernameAndPassword.getBytes(StandardCharsets.UTF_8)));
            }

            channel.writeInbound(connectHeaders);

            Http3HeadersFrame responseHeaders = channel.readOutbound();
            assertNotNull("should write a response", responseHeaders);
            return responseHeaders;
        } finally {
            channel.finishAndReleaseAll();
        }
    }

    // ---- relay socket family ----

    @Test
    public void shouldOpenAnIpv4RelaySocketForAnIpv4Target() throws Exception {
        assertThat("on macOS a dual-stack relay socket can share its port with another process's IPv4 socket",
            relaySocketAddress(InetAddress.getByName("127.0.0.1")), instanceOf(Inet4Address.class));
        assertThat(relaySocketAddress(InetAddress.getByName("192.0.2.10")), instanceOf(Inet4Address.class));
    }

    @Test
    public void shouldOpenADefaultRelaySocketForAnIpv6Target() throws Exception {
        InetAddress defaultFamily;
        try (DatagramChannel reference = DatagramChannel.open()) {
            defaultFamily = ((InetSocketAddress) reference.bind(null).getLocalAddress()).getAddress();
        }
        assertThat(relaySocketAddress(InetAddress.getByName("::1")).getClass(), equalTo(defaultFamily.getClass()));
    }

    @Test
    @SuppressWarnings("deprecation") // EpollEventLoopGroup deprecation in Netty 4.2
    public void shouldOpenTheRelaySocketOnAnEpollStreamsOwnTransport() throws Exception {
        Assume.assumeTrue("epoll not available on this platform", Epoll.isAvailable());
        EventLoopGroup group = new EpollEventLoopGroup(1);
        try {
            ChannelFuture bound = Http3ConnectUdpHandler.bindRelaySocket(group.next(), InetAddress.getByName("127.0.0.1"), new ChannelInboundHandlerAdapter());
            assertTrue("relay socket bound", bound.await(10, TimeUnit.SECONDS) && bound.isSuccess());
            try {
                assertThat(bound.channel(), instanceOf(EpollDatagramChannel.class));
                assertThat(((InetSocketAddress) bound.channel().localAddress()).getAddress(), instanceOf(Inet4Address.class));
            } finally {
                bound.channel().close().await(10, TimeUnit.SECONDS);
            }
        } finally {
            group.shutdownGracefully(0, 1, TimeUnit.SECONDS).await(10, TimeUnit.SECONDS);
        }
    }

    @SuppressWarnings("deprecation") // NioEventLoopGroup deprecation in Netty 4.2
    private static InetAddress relaySocketAddress(InetAddress target) throws Exception {
        EventLoopGroup group = new NioEventLoopGroup(1);
        try {
            ChannelFuture bound = Http3ConnectUdpHandler.bindRelaySocket(group.next(), target, new ChannelInboundHandlerAdapter());
            assertTrue("relay socket bound", bound.await(10, TimeUnit.SECONDS) && bound.isSuccess());
            try {
                return ((InetSocketAddress) bound.channel().localAddress()).getAddress();
            } finally {
                bound.channel().close().await(10, TimeUnit.SECONDS);
            }
        } finally {
            group.shutdownGracefully(0, 1, TimeUnit.SECONDS).await(10, TimeUnit.SECONDS);
        }
    }

    // ---- matchesAllowlist tests ----

    @Test
    public void shouldMatchExactHostAndPort() {
        assertTrue(Http3ConnectUdpHandler.matchesAllowlist("api.example.com:443", "api.example.com", 443));
    }

    @Test
    public void shouldMatchHostEntryOnAnyPort() {
        assertTrue(Http3ConnectUdpHandler.matchesAllowlist("api.example.com", "api.example.com", 8080));
    }

    @Test
    public void shouldMatchCaseInsensitively() {
        assertTrue(Http3ConnectUdpHandler.matchesAllowlist("API.Example.COM:443", "api.example.com", 443));
    }

    @Test
    public void shouldNotMatchWhenPortDiffers() {
        assertFalse(Http3ConnectUdpHandler.matchesAllowlist("api.example.com:443", "api.example.com", 8443));
    }

    @Test
    public void shouldNotMatchWhenHostDiffers() {
        assertFalse(Http3ConnectUdpHandler.matchesAllowlist("api.example.com:443", "evil.example.com", 443));
    }

    @Test
    public void shouldMatchOneOfSeveralCommaSeparatedEntries() {
        String allowlist = "a.example.com:53, b.example.com, [::1]:9090";
        assertTrue(Http3ConnectUdpHandler.matchesAllowlist(allowlist, "b.example.com", 12345));
        assertTrue(Http3ConnectUdpHandler.matchesAllowlist(allowlist, "a.example.com", 53));
        assertFalse(Http3ConnectUdpHandler.matchesAllowlist(allowlist, "a.example.com", 54));
    }

    @Test
    public void shouldMatchBracketedIpv6EntryWithPort() {
        assertTrue(Http3ConnectUdpHandler.matchesAllowlist("[::1]:9090", "::1", 9090));
        assertFalse(Http3ConnectUdpHandler.matchesAllowlist("[::1]:9090", "::1", 9091));
    }

    @Test
    public void shouldIgnoreBlankEntries() {
        assertTrue(Http3ConnectUdpHandler.matchesAllowlist(" , api.example.com:443 , ", "api.example.com", 443));
        assertFalse(Http3ConnectUdpHandler.matchesAllowlist(" , , ", "api.example.com", 443));
    }

    // ---- parseAuthority tests ----

    @Test
    public void shouldParseIpv4Authority() {
        InetSocketAddress addr = Http3ConnectUdpHandler.parseAuthority("127.0.0.1:8080");
        assertNotNull("should parse IPv4 authority", addr);
        assertThat("host", addr.getHostString(), is("127.0.0.1"));
        assertThat("port", addr.getPort(), is(8080));
    }

    @Test
    public void shouldParseHostnameAuthority() {
        InetSocketAddress addr = Http3ConnectUdpHandler.parseAuthority("example.com:443");
        assertNotNull("should parse hostname authority", addr);
        assertThat("host", addr.getHostString(), is("example.com"));
        assertThat("port", addr.getPort(), is(443));
    }

    @Test
    public void shouldParseIpv6Authority() {
        InetSocketAddress addr = Http3ConnectUdpHandler.parseAuthority("[::1]:9090");
        assertNotNull("should parse IPv6 authority", addr);
        // InetSocketAddress normalizes "::1" to its expanded form
        assertThat("host should be an IPv6 loopback",
            addr.getHostString(), anyOf(is("::1"), is("0:0:0:0:0:0:0:1")));
        assertThat("port", addr.getPort(), is(9090));
    }

    @Test
    public void shouldReturnNullForMissingPort() {
        assertNull("no port", Http3ConnectUdpHandler.parseAuthority("example.com"));
    }

    @Test
    public void shouldReturnNullForEmptyAuthority() {
        assertNull("empty", Http3ConnectUdpHandler.parseAuthority(""));
        assertNull("null", Http3ConnectUdpHandler.parseAuthority(null));
    }

    @Test
    public void shouldReturnNullForInvalidPort() {
        assertNull("port 0", Http3ConnectUdpHandler.parseAuthority("host:0"));
        assertNull("port 99999", Http3ConnectUdpHandler.parseAuthority("host:99999"));
        assertNull("port abc", Http3ConnectUdpHandler.parseAuthority("host:abc"));
    }

    @Test
    public void shouldReturnNullForMalformedIpv6() {
        assertNull("no closing bracket", Http3ConnectUdpHandler.parseAuthority("[::1:8080"));
        assertNull("no port after bracket", Http3ConnectUdpHandler.parseAuthority("[::1]"));
    }

    // ---- escapeJsonString tests ----

    @Test
    public void shouldEscapeDoubleQuotes() {
        assertThat(Http3ConnectUdpHandler.escapeJsonString("say \"hello\""),
            is("say \\\"hello\\\""));
    }

    @Test
    public void shouldEscapeBackslash() {
        assertThat(Http3ConnectUdpHandler.escapeJsonString("path\\to\\file"),
            is("path\\\\to\\\\file"));
    }

    @Test
    public void shouldEscapeNewlineAndCarriageReturn() {
        assertThat(Http3ConnectUdpHandler.escapeJsonString("line1\nline2\rline3"),
            is("line1\\nline2\\rline3"));
    }

    @Test
    public void shouldEscapeTabBackspaceFormfeed() {
        assertThat(Http3ConnectUdpHandler.escapeJsonString("a\tb\bc\f"),
            is("a\\tb\\bc\\f"));
    }

    @Test
    public void shouldEscapeControlCharactersBelowU0020() {
        // NUL (0x00) and BEL (0x07) should be escaped as \\u0000 and \\u0007
        assertThat(Http3ConnectUdpHandler.escapeJsonString("\0\u0007"),
            is("\\u0000\\u0007"));
    }

    @Test
    public void shouldReturnEmptyStringForNull() {
        assertThat(Http3ConnectUdpHandler.escapeJsonString(null), is(""));
    }

    @Test
    public void shouldNotEscapePlainText() {
        assertThat(Http3ConnectUdpHandler.escapeJsonString("plain text 123"),
            is("plain text 123"));
    }

    @Test
    public void shouldEscapeMixedContent() {
        // Simulates a real error message that might contain backslash and quotes
        String input = "Connection refused: \"target\" at C:\\path\\host";
        String expected = "Connection refused: \\\"target\\\" at C:\\\\path\\\\host";
        assertThat(Http3ConnectUdpHandler.escapeJsonString(input), is(expected));
    }
    // ---- logging ----

    @Test
    public void shouldLogAClientsResetOfAConnectUdpStreamAtDebugWithoutAStackTraceAndCloseTheStream() {
        for (Throwable cause : new Throwable[]{new QuicStreamResetException("STREAM_RESET", Http3ErrorCode.H3_REQUEST_CANCELLED.code()), new ClosedChannelException()}) {
            CapturingLogger logger = new CapturingLogger(Level.DEBUG);
            EmbeddedChannel channel = connectUdpStream(configuration(), logger);
            logger.logged.clear();

            channel.pipeline().fireExceptionCaught(cause);

            assertThat(cause.toString(), logger.logged, hasSize(1));
            assertThat(logger.logged.get(0).getLogLevel(), is(Level.DEBUG));
            assertThat(logger.logged.get(0).getMessageFormat(), is("CONNECT-UDP stream of HTTP/3 connection from:{}closed or reset by its client:{}"));
            assertThat(logger.logged.get(0).getThrowable(), is(nullValue()));
            assertFalse("the stream is closed", channel.isOpen());

            CapturingLogger atInfo = new CapturingLogger(Level.INFO);
            EmbeddedChannel quiet = connectUdpStream(configuration(), atInfo);
            atInfo.logged.clear();
            quiet.pipeline().fireExceptionCaught(cause);
            assertThat("nothing at the default level", atInfo.logged, empty());
            assertFalse("the stream is closed", quiet.isOpen());
        }
    }

    @Test
    public void shouldLogAnyOtherExceptionOnAConnectUdpStreamAsAWarningWithItsMessageBounded() {
        CapturingLogger logger = new CapturingLogger(Level.INFO);
        EmbeddedChannel channel = connectUdpStream(configuration(), logger);
        logger.logged.clear();
        String peerBytes = "x".repeat(4000);

        channel.pipeline().fireExceptionCaught(new DecoderException(peerBytes));

        assertThat(logger.logged, hasSize(1));
        LogEntry entry = logger.logged.get(0);
        assertThat(entry.getLogLevel(), is(Level.WARN));
        assertThat(entry.getMessageFormat(), is("exception on CONNECT-UDP stream of HTTP/3 connection from:{}:{}"));
        assertThat(String.valueOf(entry.getArguments()[1]), startsWith("DecoderException: xxx"));
        assertThat(String.valueOf(entry.getArguments()[1]).length(), lessThan(300));
        assertThat(entry.getThrowable(), is(notNullValue()));
        assertThat(entry.getThrowable().getMessage().length(), lessThan(300));
        assertFalse("the stream is closed", channel.isOpen());
    }

    @Test
    public void shouldPassOnAnExceptionOfAStreamThatDidNotAskForConnectUdpWithoutLoggingIt() {
        CapturingLogger logger = new CapturingLogger(Level.TRACE);
        List<Throwable> passedOn = new CopyOnWriteArrayList<>();
        EmbeddedChannel channel = new EmbeddedChannel(new Http3ConnectUdpHandler(configuration(), logger), new ChannelInboundHandlerAdapter() {
            @Override
            public void exceptionCaught(io.netty.channel.ChannelHandlerContext ctx, Throwable cause) {
                passedOn.add(cause);
            }
        });
        QuicStreamResetException reset = new QuicStreamResetException("STREAM_RESET", Http3ErrorCode.H3_REQUEST_CANCELLED.code());

        channel.pipeline().fireExceptionCaught(reset);

        assertThat(logger.logged, empty());
        assertThat(passedOn, contains(reset));
        assertTrue("the request handler after it closes the stream", channel.isOpen());
        channel.finishAndReleaseAll();
    }

    @Test
    public void shouldLogARefusedRequestAsAWarningInMockServersLogWithTheAuthorityBounded() {
        CapturingLogger logger = new CapturingLogger(Level.INFO);
        EmbeddedChannel channel = new EmbeddedChannel(new Http3ConnectUdpHandler(configuration(), logger));
        String authority = "h".repeat(4000);
        DefaultHttp3HeadersFrame connectHeaders = new DefaultHttp3HeadersFrame();
        connectHeaders.headers().method("CONNECT");
        connectHeaders.headers().protocol("connect-udp");
        connectHeaders.headers().authority(authority);

        channel.writeInbound(connectHeaders);

        assertThat(logger.logged, hasSize(1));
        LogEntry entry = logger.logged.get(0);
        assertThat(entry.getLogLevel(), is(Level.WARN));
        assertThat(entry.getMessageFormat(), is("CONNECT-UDP request from:{}refused with status:{}because:{}"));
        assertThat(entry.getArguments()[1], is("400"));
        assertThat(String.valueOf(entry.getArguments()[2]), startsWith("Invalid :authority for CONNECT-UDP: hhh"));
        assertThat(String.valueOf(entry.getArguments()[2]).length(), lessThan(300));
        channel.finishAndReleaseAll();
    }

    @Test
    public void shouldLogARefusedTargetAsAWarningInMockServersLog() {
        CapturingLogger logger = new CapturingLogger(Level.INFO);
        EmbeddedChannel channel = new EmbeddedChannel(new Http3ConnectUdpHandler(configuration().http3ConnectUdpAllowedTargets("allowed.example.com:443"), logger));
        DefaultHttp3HeadersFrame connectHeaders = new DefaultHttp3HeadersFrame();
        connectHeaders.headers().method("CONNECT");
        connectHeaders.headers().protocol("connect-udp");
        connectHeaders.headers().authority("127.0.0.1:443");

        channel.writeInbound(connectHeaders);

        assertThat(logger.logged, hasSize(1));
        LogEntry entry = logger.logged.get(0);
        assertThat(entry.getLogLevel(), is(Level.WARN));
        assertThat(entry.getMessageFormat(), is("CONNECT-UDP target from:{}refused:{}"));
        assertThat(String.valueOf(entry.getArguments()[1]), is("not in http3ConnectUdpAllowedTargets: 127.0.0.1:443"));
        channel.finishAndReleaseAll();
    }

    @Test
    public void shouldLogAFailedRelaySocketAsAWarningWithoutTheStackTraceOfAnIoErrorAndCloseIt() {
        EmbeddedChannel quicStream = new EmbeddedChannel(new ChannelInboundHandlerAdapter());
        for (Throwable cause : new Throwable[]{new PortUnreachableException("ICMP Port Unreachable"), new IllegalStateException("relay failed")}) {
            CapturingLogger logger = new CapturingLogger(Level.INFO);
            EmbeddedChannel relaySocket = new EmbeddedChannel(new Http3ConnectUdpHandler.UdpRelayHandler(quicStream.pipeline().firstContext(), logger));

            relaySocket.pipeline().fireExceptionCaught(cause);

            assertThat(cause.toString(), logger.logged, hasSize(1));
            LogEntry entry = logger.logged.get(0);
            assertThat(entry.getLogLevel(), is(Level.WARN));
            assertThat(entry.getMessageFormat(), is("CONNECT-UDP relay socket to:{}for HTTP/3 connection from:{}failed and is closed:{}"));
            assertThat(String.valueOf(entry.getArguments()[2]), is(cause.getClass().getSimpleName() + ": " + cause.getMessage()));
            if (cause instanceof PortUnreachableException) {
                assertThat(entry.getThrowable(), is(nullValue()));
            } else {
                assertThat(entry.getThrowable(), sameInstance(cause));
            }
            assertFalse("the relay socket is closed", relaySocket.isOpen());
        }
        quicStream.finishAndReleaseAll();
    }

    /**
     * A stream on which the client asked for CONNECT-UDP; it is refused for its missing authority, which needs no
     * relay socket, and stays open as a QUIC stream whose output is shut down would.
     */
    private static EmbeddedChannel connectUdpStream(Configuration configuration, CapturingLogger logger) {
        EmbeddedChannel channel = new EmbeddedChannel(new Http3ConnectUdpHandler(configuration, logger));
        DefaultHttp3HeadersFrame connectHeaders = new DefaultHttp3HeadersFrame();
        connectHeaders.headers().method("CONNECT");
        connectHeaders.headers().protocol("connect-udp");
        channel.writeInbound(connectHeaders);
        Object response;
        while ((response = channel.readOutbound()) != null) {
            io.netty.util.ReferenceCountUtil.release(response);
        }
        assertTrue("the stream is still open", channel.isOpen());
        return channel;
    }

    private static final class CapturingLogger extends MockServerLogger {
        private final Level level;
        private final List<LogEntry> logged = new CopyOnWriteArrayList<>();

        private CapturingLogger(Level level) {
            super(Http3ConnectUdpHandlerTest.class);
            this.level = level;
        }

        @Override
        public boolean isEnabledForInstance(Level level) {
            return isEnabled(level, this.level);
        }

        @Override
        public void logEvent(LogEntry logEntry) {
            if (isEnabledForInstance(logEntry.getLogLevel())) {
                logged.add(logEntry);
            }
        }
    }
}
