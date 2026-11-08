package org.mockserver.netty.proxy;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.embedded.EmbeddedChannel;
import org.junit.Test;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.netty.unification.PortUnificationHandler;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.hamcrest.CoreMatchers.*;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.mockserver.mock.action.http.HttpActionHandler.REMOTE_SOCKET;
import static org.mockserver.netty.HttpRequestHandler.PROXYING;
import static org.mockserver.netty.proxy.TransparentProxyHandler.TRANSPARENT_ORIGINAL_DST_RESOLVED;
import static org.mockserver.socket.SocketAddresses.PROXY_PROTOCOL_SOURCE;

public class ProxyProtocolOriginalDestinationHandlerTest {

    private final MockServerLogger logger = new MockServerLogger(ProxyProtocolOriginalDestinationHandlerTest.class);

    // --- Valid PROXY v1 TCP4 ---

    @Test
    public void shouldParseValidProxyV1Tcp4Header() {
        // given
        ProxyProtocolOriginalDestinationHandler handler = new ProxyProtocolOriginalDestinationHandler(logger);
        EmbeddedChannel channel = new EmbeddedChannel(handler);

        String proxyHeader = "PROXY TCP4 192.168.1.1 10.0.0.1 56324 80\r\n";
        String httpRequest = "GET / HTTP/1.1\r\nHost: example.com\r\n\r\n";

        // when
        channel.writeInbound(Unpooled.copiedBuffer(proxyHeader + httpRequest, StandardCharsets.US_ASCII));

        // then — REMOTE_SOCKET set to destination from PROXY header
        InetSocketAddress remoteSocket = channel.attr(REMOTE_SOCKET).get();
        assertThat(remoteSocket, is(notNullValue()));
        assertThat(remoteSocket.getAddress().getHostAddress(), is("10.0.0.1"));
        assertThat(remoteSocket.getPort(), is(80));
        assertThat(channel.attr(PROXYING).get(), is(Boolean.TRUE));
        assertThat(channel.attr(TRANSPARENT_ORIGINAL_DST_RESOLVED).get(), is(Boolean.TRUE));

        // handler should have removed itself
        assertThat(channel.pipeline().get(ProxyProtocolOriginalDestinationHandler.class), is(nullValue()));

        // remaining HTTP bytes should pass through
        ByteBuf remaining = channel.readInbound();
        assertThat(remaining, is(notNullValue()));
        String remainingStr = remaining.toString(StandardCharsets.US_ASCII);
        assertThat(remainingStr, is(httpRequest));
        remaining.release();

        channel.close();
    }

    @Test
    public void shouldParseValidProxyV1Tcp4WithHttpsPort() {
        // given
        ProxyProtocolOriginalDestinationHandler handler = new ProxyProtocolOriginalDestinationHandler(logger);
        EmbeddedChannel channel = new EmbeddedChannel(handler);

        String proxyHeader = "PROXY TCP4 172.16.0.5 93.184.216.34 12345 443\r\n";

        // when
        channel.writeInbound(Unpooled.copiedBuffer(proxyHeader, StandardCharsets.US_ASCII));

        // then
        InetSocketAddress remoteSocket = channel.attr(REMOTE_SOCKET).get();
        assertThat(remoteSocket, is(notNullValue()));
        assertThat(remoteSocket.getAddress().getHostAddress(), is("93.184.216.34"));
        assertThat(remoteSocket.getPort(), is(443));

        channel.close();
    }

    // --- Valid PROXY v1 TCP6 ---

    @Test
    public void shouldParseValidProxyV1Tcp6Header() {
        // given
        ProxyProtocolOriginalDestinationHandler handler = new ProxyProtocolOriginalDestinationHandler(logger);
        EmbeddedChannel channel = new EmbeddedChannel(handler);

        String proxyHeader = "PROXY TCP6 ::1 ::1 56324 8080\r\n";
        String httpRequest = "GET / HTTP/1.1\r\n\r\n";

        // when
        channel.writeInbound(Unpooled.copiedBuffer(proxyHeader + httpRequest, StandardCharsets.US_ASCII));

        // then
        InetSocketAddress remoteSocket = channel.attr(REMOTE_SOCKET).get();
        assertThat(remoteSocket, is(notNullValue()));
        assertThat(remoteSocket.getPort(), is(8080));
        assertThat(channel.attr(PROXYING).get(), is(Boolean.TRUE));
        assertThat(channel.attr(TRANSPARENT_ORIGINAL_DST_RESOLVED).get(), is(Boolean.TRUE));

        // handler removed
        assertThat(channel.pipeline().get(ProxyProtocolOriginalDestinationHandler.class), is(nullValue()));

        // remaining bytes pass through
        ByteBuf remaining = channel.readInbound();
        assertThat(remaining, is(notNullValue()));
        assertThat(remaining.toString(StandardCharsets.US_ASCII), is(httpRequest));
        remaining.release();

        channel.close();
    }

    @Test
    public void shouldParseValidProxyV1Tcp6FullAddress() {
        // given
        ProxyProtocolOriginalDestinationHandler handler = new ProxyProtocolOriginalDestinationHandler(logger);
        EmbeddedChannel channel = new EmbeddedChannel(handler);

        String proxyHeader = "PROXY TCP6 2001:db8::1 2001:db8::2 56324 443\r\n";

        // when
        channel.writeInbound(Unpooled.copiedBuffer(proxyHeader, StandardCharsets.US_ASCII));

        // then
        InetSocketAddress remoteSocket = channel.attr(REMOTE_SOCKET).get();
        assertThat(remoteSocket, is(notNullValue()));
        assertThat(remoteSocket.getPort(), is(443));
        assertThat(channel.attr(TRANSPARENT_ORIGINAL_DST_RESOLVED).get(), is(Boolean.TRUE));

        channel.close();
    }

    // --- Non-PROXY traffic (pass-through) ---

    @Test
    public void shouldPassThroughNonProxyTraffic() {
        // given
        ProxyProtocolOriginalDestinationHandler handler = new ProxyProtocolOriginalDestinationHandler(logger);
        EmbeddedChannel channel = new EmbeddedChannel(handler);

        String httpRequest = "GET / HTTP/1.1\r\nHost: example.com\r\n\r\n";

        // when
        channel.writeInbound(Unpooled.copiedBuffer(httpRequest, StandardCharsets.US_ASCII));

        // then — no REMOTE_SOCKET set, handler removed, bytes pass through
        assertThat(channel.attr(REMOTE_SOCKET).get(), is(nullValue()));
        assertThat(channel.pipeline().get(ProxyProtocolOriginalDestinationHandler.class), is(nullValue()));

        ByteBuf remaining = channel.readInbound();
        assertThat(remaining, is(notNullValue()));
        assertThat(remaining.toString(StandardCharsets.US_ASCII), is(httpRequest));
        remaining.release();

        channel.close();
    }

    @Test
    public void shouldPassThroughTlsClientHello() {
        // given — TLS ClientHello starts with 0x16 (not 'P')
        ProxyProtocolOriginalDestinationHandler handler = new ProxyProtocolOriginalDestinationHandler(logger);
        EmbeddedChannel channel = new EmbeddedChannel(handler);

        byte[] tlsHello = new byte[]{0x16, 0x03, 0x01, 0x00, 0x05, 0x01, 0x00, 0x01, 0x00, 0x00};

        // when
        channel.writeInbound(Unpooled.wrappedBuffer(tlsHello));

        // then — pass through
        assertThat(channel.attr(REMOTE_SOCKET).get(), is(nullValue()));
        assertThat(channel.pipeline().get(ProxyProtocolOriginalDestinationHandler.class), is(nullValue()));

        ByteBuf remaining = channel.readInbound();
        assertThat(remaining, is(notNullValue()));
        byte[] result = new byte[remaining.readableBytes()];
        remaining.readBytes(result);
        assertThat(result, is(tlsHello));
        remaining.release();

        channel.close();
    }

    // --- PROXY UNKNOWN ---

    @Test
    public void shouldHandleProxyUnknown() {
        // given
        ProxyProtocolOriginalDestinationHandler handler = new ProxyProtocolOriginalDestinationHandler(logger);
        EmbeddedChannel channel = new EmbeddedChannel(handler);

        String proxyHeader = "PROXY UNKNOWN\r\n";
        String httpRequest = "GET / HTTP/1.1\r\n\r\n";

        // when
        channel.writeInbound(Unpooled.copiedBuffer(proxyHeader + httpRequest, StandardCharsets.US_ASCII));

        // then — no REMOTE_SOCKET set (UNKNOWN has no address), handler removed
        assertThat(channel.attr(REMOTE_SOCKET).get(), is(nullValue()));
        assertThat(channel.pipeline().get(ProxyProtocolOriginalDestinationHandler.class), is(nullValue()));

        ByteBuf remaining = channel.readInbound();
        assertThat(remaining, is(notNullValue()));
        assertThat(remaining.toString(StandardCharsets.US_ASCII), is(httpRequest));
        remaining.release();

        channel.close();
    }

    // --- Malformed headers (fail-safe pass-through) ---

    @Test
    public void shouldPassThroughMalformedProxyHeader() {
        // given — looks like PROXY but has wrong format
        ProxyProtocolOriginalDestinationHandler handler = new ProxyProtocolOriginalDestinationHandler(logger);
        EmbeddedChannel channel = new EmbeddedChannel(handler);

        String malformed = "PROXY BADPROTO 1.2.3.4\r\nGET / HTTP/1.1\r\n\r\n";

        // when
        channel.writeInbound(Unpooled.copiedBuffer(malformed, StandardCharsets.US_ASCII));

        // then — no REMOTE_SOCKET, handler removed, remaining bytes pass through
        assertThat(channel.attr(REMOTE_SOCKET).get(), is(nullValue()));
        assertThat(channel.pipeline().get(ProxyProtocolOriginalDestinationHandler.class), is(nullValue()));

        // The bytes after the malformed PROXY header line should be available
        ByteBuf remaining = channel.readInbound();
        assertThat(remaining, is(notNullValue()));
        remaining.release();

        channel.close();
    }

    @Test
    public void shouldPassThroughMalformedMissingPorts() {
        // given — PROXY TCP4 but missing port fields
        ProxyProtocolOriginalDestinationHandler handler = new ProxyProtocolOriginalDestinationHandler(logger);
        EmbeddedChannel channel = new EmbeddedChannel(handler);

        String malformed = "PROXY TCP4 1.2.3.4 5.6.7.8\r\n";

        // when
        channel.writeInbound(Unpooled.copiedBuffer(malformed, StandardCharsets.US_ASCII));

        // then — no REMOTE_SOCKET, handler removed
        assertThat(channel.attr(REMOTE_SOCKET).get(), is(nullValue()));
        assertThat(channel.pipeline().get(ProxyProtocolOriginalDestinationHandler.class), is(nullValue()));

        channel.close();
    }

    @Test
    public void shouldPassThroughInvalidPort() {
        // given — port is not a number
        ProxyProtocolOriginalDestinationHandler handler = new ProxyProtocolOriginalDestinationHandler(logger);
        EmbeddedChannel channel = new EmbeddedChannel(handler);

        String malformed = "PROXY TCP4 1.2.3.4 5.6.7.8 abc xyz\r\n";

        // when
        channel.writeInbound(Unpooled.copiedBuffer(malformed, StandardCharsets.US_ASCII));

        // then
        assertThat(channel.attr(REMOTE_SOCKET).get(), is(nullValue()));
        assertThat(channel.pipeline().get(ProxyProtocolOriginalDestinationHandler.class), is(nullValue()));

        channel.close();
    }

    @Test
    public void shouldTreatAV1HeaderWhoseDestinationIsNotAnAddressOfTheDeclaredFamilyAsMalformed() {
        String[] notAnAddress = {
            "TCP4 1.2.3.4 db.example.com",
            "TCP4 1.2.3.4 user@secret.internal.example",
            "TCP4 1.2.3.4 secret.internal.example/.allowed.com",
            "TCP4 1.2.3.4 secret.internal.example:80/",
            "TCP4 1.2.3.4 localhost",
            "TCP4 1.2.3.4 0177.0.0.1",
            "TCP4 1.2.3.4 010.0.0.1",
            "TCP4 1.2.3.4 127.1",
            "TCP4 1.2.3.4 2130706433",
            "TCP4 1.2.3.4 ::1",
            "TCP6 ::1 10.0.0.1",
            "TCP6 ::1 [::1]",
            "TCP6 ::1 fe80::1%eth0",
            "TCP6 ::1 ::ffff:010.0.0.1",
            "TCP6 ::1 db.example.com"
        };
        for (String header : notAnAddress) {
            EmbeddedChannel channel = new EmbeddedChannel(new ProxyProtocolOriginalDestinationHandler(logger));

            channel.writeInbound(Unpooled.copiedBuffer("PROXY " + header + " 56324 5432\r\nGET / HTTP/1.1\r\n\r\n", StandardCharsets.US_ASCII));

            assertThat(header, channel.attr(REMOTE_SOCKET).get(), is(nullValue()));
            assertThat(header, channel.attr(TRANSPARENT_ORIGINAL_DST_RESOLVED).get(), is(nullValue()));
            assertThat(header, channel.pipeline().get(ProxyProtocolOriginalDestinationHandler.class), is(nullValue()));
            ByteBuf remaining = channel.readInbound();
            assertThat(header, remaining.toString(StandardCharsets.US_ASCII), is("GET / HTTP/1.1\r\n\r\n"));
            remaining.release();
            channel.close();
        }
    }

    @Test
    public void shouldReadAV1DestinationThatIsAnAddressOfTheDeclaredFamily() {
        String[][] addresses = {
            {"TCP4 1.2.3.4 10.0.0.1", "10.0.0.1"},
            {"TCP4 1.2.3.4 0.0.0.0", "0.0.0.0"},
            {"TCP4 1.2.3.4 100.64.0.10", "100.64.0.10"},
            {"TCP6 ::1 2001:db8::5", "2001:db8::5"}
        };
        for (String[] address : addresses) {
            EmbeddedChannel channel = new EmbeddedChannel(new ProxyProtocolOriginalDestinationHandler(logger));

            channel.writeInbound(Unpooled.copiedBuffer("PROXY " + address[0] + " 56324 5432\r\n", StandardCharsets.US_ASCII));

            InetSocketAddress destination = channel.attr(REMOTE_SOCKET).get();
            assertThat(address[0], destination.isUnresolved(), is(false));
            assertThat(address[0], destination.getAddress(), is(new InetSocketAddress(address[1], 0).getAddress()));
            assertThat(address[0], destination.getPort(), is(5432));
            channel.close();
        }
    }

    // --- Oversized header (fail-safe) ---

    @Test
    public void shouldPassThroughOversizedHeader() {
        // given — PROXY signature followed by very long line without CRLF
        ProxyProtocolOriginalDestinationHandler handler = new ProxyProtocolOriginalDestinationHandler(logger);
        EmbeddedChannel channel = new EmbeddedChannel(handler);

        StringBuilder sb = new StringBuilder("PROXY TCP4 ");
        // Pad to exceed MAX_PROXY_V1_LINE_LENGTH (108)
        while (sb.length() < 120) {
            sb.append("x");
        }
        // No CRLF

        // when
        channel.writeInbound(Unpooled.copiedBuffer(sb.toString(), StandardCharsets.US_ASCII));

        // then — fail-safe: pass through, handler removed
        assertThat(channel.attr(REMOTE_SOCKET).get(), is(nullValue()));
        assertThat(channel.pipeline().get(ProxyProtocolOriginalDestinationHandler.class), is(nullValue()));

        ByteBuf remaining = channel.readInbound();
        assertThat(remaining, is(notNullValue()));
        assertThat(remaining.readableBytes(), is(sb.length()));
        remaining.release();

        channel.close();
    }

    // --- Fragmented delivery (bytes arrive in multiple chunks) ---

    @Test
    public void shouldHandleFragmentedProxyHeader() {
        // given — PROXY header arrives in two chunks
        ProxyProtocolOriginalDestinationHandler handler = new ProxyProtocolOriginalDestinationHandler(logger);
        EmbeddedChannel channel = new EmbeddedChannel(handler);

        String proxyHeader = "PROXY TCP4 192.168.1.1 10.0.0.1 56324 80\r\n";
        String httpRequest = "GET / HTTP/1.1\r\n\r\n";
        String full = proxyHeader + httpRequest;

        // Split at an arbitrary point inside the PROXY header
        String chunk1 = full.substring(0, 15); // "PROXY TCP4 192."
        String chunk2 = full.substring(15);

        // when — first chunk
        channel.writeInbound(Unpooled.copiedBuffer(chunk1, StandardCharsets.US_ASCII));

        // handler should still be in pipeline (waiting for more bytes)
        assertThat(channel.pipeline().get(ProxyProtocolOriginalDestinationHandler.class), is(notNullValue()));
        assertThat(channel.readInbound(), is(nullValue())); // no output yet

        // when — second chunk
        channel.writeInbound(Unpooled.copiedBuffer(chunk2, StandardCharsets.US_ASCII));

        // then — PROXY header parsed
        InetSocketAddress remoteSocket = channel.attr(REMOTE_SOCKET).get();
        assertThat(remoteSocket, is(notNullValue()));
        assertThat(remoteSocket.getAddress().getHostAddress(), is("10.0.0.1"));
        assertThat(remoteSocket.getPort(), is(80));
        assertThat(channel.pipeline().get(ProxyProtocolOriginalDestinationHandler.class), is(nullValue()));

        // remaining HTTP bytes pass through
        ByteBuf remaining = channel.readInbound();
        assertThat(remaining, is(notNullValue()));
        assertThat(remaining.toString(StandardCharsets.US_ASCII), is(httpRequest));
        remaining.release();

        channel.close();
    }

    // --- PROXY header only (no trailing bytes) ---

    @Test
    public void shouldHandleProxyHeaderWithNoTrailingBytes() {
        // given — only the PROXY header, no HTTP request bytes yet
        ProxyProtocolOriginalDestinationHandler handler = new ProxyProtocolOriginalDestinationHandler(logger);
        EmbeddedChannel channel = new EmbeddedChannel(handler);

        String proxyHeader = "PROXY TCP4 192.168.1.1 10.0.0.1 56324 80\r\n";

        // when
        channel.writeInbound(Unpooled.copiedBuffer(proxyHeader, StandardCharsets.US_ASCII));

        // then — REMOTE_SOCKET set, handler removed, no output bytes
        InetSocketAddress remoteSocket = channel.attr(REMOTE_SOCKET).get();
        assertThat(remoteSocket, is(notNullValue()));
        assertThat(remoteSocket.getAddress().getHostAddress(), is("10.0.0.1"));
        assertThat(remoteSocket.getPort(), is(80));
        assertThat(channel.pipeline().get(ProxyProtocolOriginalDestinationHandler.class), is(nullValue()));
        assertThat(channel.readInbound(), is(nullValue())); // no remaining bytes

        channel.close();
    }

    // --- Port boundary validation ---

    @Test
    public void shouldRejectPortOutOfRange() {
        // given
        ProxyProtocolOriginalDestinationHandler handler = new ProxyProtocolOriginalDestinationHandler(logger);
        EmbeddedChannel channel = new EmbeddedChannel(handler);

        String proxyHeader = "PROXY TCP4 1.2.3.4 5.6.7.8 1234 99999\r\n";

        // when
        channel.writeInbound(Unpooled.copiedBuffer(proxyHeader, StandardCharsets.US_ASCII));

        // then — invalid port, no REMOTE_SOCKET
        assertThat(channel.attr(REMOTE_SOCKET).get(), is(nullValue()));
        assertThat(channel.pipeline().get(ProxyProtocolOriginalDestinationHandler.class), is(nullValue()));

        channel.close();
    }

    // --- PROXY protocol v2 (binary) ---

    /** The 12-byte PROXY v2 signature. */
    private static final byte[] V2_SIG = {
        0x0D, 0x0A, 0x0D, 0x0A, 0x00, 0x0D, 0x0A, 0x51, 0x55, 0x49, 0x54, 0x0A
    };

    /** Builds a PROXY v2 header: signature + verCmd + famTrans + uint16 length + address block. */
    private static byte[] proxyV2(int verCmd, int famTrans, byte[] addrBlock) {
        int len = addrBlock.length;
        byte[] out = new byte[16 + len];
        System.arraycopy(V2_SIG, 0, out, 0, 12);
        out[12] = (byte) verCmd;
        out[13] = (byte) famTrans;
        out[14] = (byte) ((len >> 8) & 0xFF);
        out[15] = (byte) (len & 0xFF);
        System.arraycopy(addrBlock, 0, out, 16, len);
        return out;
    }

    private static byte[] concat(byte[] a, byte[] b) {
        byte[] out = new byte[a.length + b.length];
        System.arraycopy(a, 0, out, 0, a.length);
        System.arraycopy(b, 0, out, a.length, b.length);
        return out;
    }

    @Test
    public void shouldParseValidProxyV2Inet() {
        // given — v2 + PROXY (0x21), AF_INET + STREAM (0x11); src 192.168.1.1, dst 10.0.0.1:443
        ProxyProtocolOriginalDestinationHandler handler = new ProxyProtocolOriginalDestinationHandler(logger);
        EmbeddedChannel channel = new EmbeddedChannel(handler);

        byte[] addr = {
            (byte) 192, (byte) 168, 1, 1,   // src
            10, 0, 0, 1,                     // dst
            (byte) 0xDC, 0x04,               // src port 56324
            0x01, (byte) 0xBB                // dst port 443
        };
        byte[] header = proxyV2(0x21, 0x11, addr);
        String httpRequest = "GET / HTTP/1.1\r\n\r\n";

        // when
        channel.writeInbound(Unpooled.wrappedBuffer(concat(header, httpRequest.getBytes(StandardCharsets.US_ASCII))));

        // then
        InetSocketAddress remoteSocket = channel.attr(REMOTE_SOCKET).get();
        assertThat(remoteSocket, is(notNullValue()));
        assertThat(remoteSocket.getAddress().getHostAddress(), is("10.0.0.1"));
        assertThat(remoteSocket.getPort(), is(443));
        assertThat(channel.attr(PROXYING).get(), is(Boolean.TRUE));
        assertThat(channel.attr(TRANSPARENT_ORIGINAL_DST_RESOLVED).get(), is(Boolean.TRUE));
        assertThat(channel.pipeline().get(ProxyProtocolOriginalDestinationHandler.class), is(nullValue()));

        ByteBuf remaining = channel.readInbound();
        assertThat(remaining, is(notNullValue()));
        assertThat(remaining.toString(StandardCharsets.US_ASCII), is(httpRequest));
        remaining.release();

        channel.close();
    }

    @Test
    public void shouldParseValidProxyV2Inet6() {
        // given — v2 + PROXY (0x21), AF_INET6 + STREAM (0x21)
        ProxyProtocolOriginalDestinationHandler handler = new ProxyProtocolOriginalDestinationHandler(logger);
        EmbeddedChannel channel = new EmbeddedChannel(handler);

        byte[] src = new byte[16];
        byte[] dst = new byte[16];
        dst[15] = 1; // ::1
        byte[] addr = new byte[36];
        System.arraycopy(src, 0, addr, 0, 16);
        System.arraycopy(dst, 0, addr, 16, 16);
        addr[32] = (byte) 0xDC; addr[33] = 0x04; // src port
        addr[34] = 0x01; addr[35] = (byte) 0xBB; // dst port 443
        byte[] header = proxyV2(0x21, 0x21, addr);

        // when
        channel.writeInbound(Unpooled.wrappedBuffer(header));

        // then
        InetSocketAddress remoteSocket = channel.attr(REMOTE_SOCKET).get();
        assertThat(remoteSocket, is(notNullValue()));
        assertThat(remoteSocket.getPort(), is(443));
        assertThat(channel.attr(TRANSPARENT_ORIGINAL_DST_RESOLVED).get(), is(Boolean.TRUE));
        assertThat(channel.pipeline().get(ProxyProtocolOriginalDestinationHandler.class), is(nullValue()));

        channel.close();
    }

    @Test
    public void shouldHandleProxyV2LocalCommandWithoutDestination() {
        // given — v2 + LOCAL (0x20), AF_UNSPEC, no address block (health check)
        ProxyProtocolOriginalDestinationHandler handler = new ProxyProtocolOriginalDestinationHandler(logger);
        EmbeddedChannel channel = new EmbeddedChannel(handler);

        byte[] header = proxyV2(0x20, 0x00, new byte[0]);
        String httpRequest = "GET / HTTP/1.1\r\n\r\n";

        // when
        channel.writeInbound(Unpooled.wrappedBuffer(concat(header, httpRequest.getBytes(StandardCharsets.US_ASCII))));

        // then — no destination (LOCAL), header consumed, handler removed, trailing bytes pass through
        assertThat(channel.attr(REMOTE_SOCKET).get(), is(nullValue()));
        assertThat(channel.pipeline().get(ProxyProtocolOriginalDestinationHandler.class), is(nullValue()));

        ByteBuf remaining = channel.readInbound();
        assertThat(remaining, is(notNullValue()));
        assertThat(remaining.toString(StandardCharsets.US_ASCII), is(httpRequest));
        remaining.release();

        channel.close();
    }

    @Test
    public void shouldConsumeProxyV2UnixFamilyWithoutDestination() {
        // given — v2 + PROXY (0x21), AF_UNIX (0x31); 216-byte address block, no IP destination
        ProxyProtocolOriginalDestinationHandler handler = new ProxyProtocolOriginalDestinationHandler(logger);
        EmbeddedChannel channel = new EmbeddedChannel(handler);

        byte[] header = proxyV2(0x21, 0x31, new byte[216]);
        String httpRequest = "GET / HTTP/1.1\r\n\r\n";

        // when
        channel.writeInbound(Unpooled.wrappedBuffer(concat(header, httpRequest.getBytes(StandardCharsets.US_ASCII))));

        // then — no IP destination, header consumed, trailing bytes pass through
        assertThat(channel.attr(REMOTE_SOCKET).get(), is(nullValue()));
        assertThat(channel.pipeline().get(ProxyProtocolOriginalDestinationHandler.class), is(nullValue()));

        ByteBuf remaining = channel.readInbound();
        assertThat(remaining, is(notNullValue()));
        assertThat(remaining.toString(StandardCharsets.US_ASCII), is(httpRequest));
        remaining.release();

        channel.close();
    }

    @Test
    public void shouldHandleFragmentedProxyV2Header() {
        // given — v2 INET header split across two reads
        ProxyProtocolOriginalDestinationHandler handler = new ProxyProtocolOriginalDestinationHandler(logger);
        EmbeddedChannel channel = new EmbeddedChannel(handler);

        byte[] addr = {(byte) 192, (byte) 168, 1, 1, 10, 0, 0, 1, (byte) 0xDC, 0x04, 0x01, (byte) 0xBB};
        byte[] header = proxyV2(0x21, 0x11, addr); // 28 bytes total

        // when — first chunk: signature only (12 bytes)
        channel.writeInbound(Unpooled.wrappedBuffer(java.util.Arrays.copyOfRange(header, 0, 12)));
        assertThat(channel.pipeline().get(ProxyProtocolOriginalDestinationHandler.class), is(notNullValue()));
        assertThat(channel.attr(REMOTE_SOCKET).get(), is(nullValue()));

        // when — second chunk: the rest
        channel.writeInbound(Unpooled.wrappedBuffer(java.util.Arrays.copyOfRange(header, 12, header.length)));

        // then
        InetSocketAddress remoteSocket = channel.attr(REMOTE_SOCKET).get();
        assertThat(remoteSocket, is(notNullValue()));
        assertThat(remoteSocket.getAddress().getHostAddress(), is("10.0.0.1"));
        assertThat(remoteSocket.getPort(), is(443));
        assertThat(channel.pipeline().get(ProxyProtocolOriginalDestinationHandler.class), is(nullValue()));

        channel.close();
    }

    @Test
    public void shouldPassThroughWhenV2VersionNibbleIsNot2() {
        // given — valid v2 signature but version nibble 3 (verCmd 0x31)
        ProxyProtocolOriginalDestinationHandler handler = new ProxyProtocolOriginalDestinationHandler(logger);
        EmbeddedChannel channel = new EmbeddedChannel(handler);

        byte[] addr = {(byte) 192, (byte) 168, 1, 1, 10, 0, 0, 1, (byte) 0xDC, 0x04, 0x01, (byte) 0xBB};
        byte[] header = proxyV2(0x31, 0x11, addr); // version 3 — unsupported

        // when
        channel.writeInbound(Unpooled.wrappedBuffer(header));

        // then — pass through, no destination resolved
        assertThat(channel.attr(REMOTE_SOCKET).get(), is(nullValue()));
        assertThat(channel.pipeline().get(ProxyProtocolOriginalDestinationHandler.class), is(nullValue()));

        ByteBuf remaining = channel.readInbound();
        assertThat(remaining, is(notNullValue()));
        assertThat(remaining.readableBytes(), is(header.length));
        remaining.release();

        channel.close();
    }

    @Test
    public void shouldPassThroughWhenV2AddrLenExceedsMax() {
        // given — valid v2 signature/version but declared address length > MAX (1024)
        ProxyProtocolOriginalDestinationHandler handler = new ProxyProtocolOriginalDestinationHandler(logger);
        EmbeddedChannel channel = new EmbeddedChannel(handler);

        byte[] header = proxyV2(0x21, 0x11, new byte[0]); // 16-byte prefix, length 0
        header[14] = 0x07; // overwrite length field with 2000 (> 1024)
        header[15] = (byte) 0xD0;

        // when — only the 16-byte prefix is supplied (the oversized length is rejected before waiting)
        channel.writeInbound(Unpooled.wrappedBuffer(header));

        // then — pass through, no destination
        assertThat(channel.attr(REMOTE_SOCKET).get(), is(nullValue()));
        assertThat(channel.pipeline().get(ProxyProtocolOriginalDestinationHandler.class), is(nullValue()));

        ByteBuf remaining = channel.readInbound();
        assertThat(remaining, is(notNullValue()));
        assertThat(remaining.readableBytes(), is(header.length));
        remaining.release();

        channel.close();
    }

    @Test
    public void shouldPassThroughWhenV2FirstByteButNotFullSignature() {
        // given — starts with 0x0D but is not the v2 signature
        ProxyProtocolOriginalDestinationHandler handler = new ProxyProtocolOriginalDestinationHandler(logger);
        EmbeddedChannel channel = new EmbeddedChannel(handler);

        byte[] notV2 = {0x0D, 0x0A, 0x0D, 0x0A, 0x00, 0x0D, 0x0A, 0x00, 0x00, 0x00, 0x00, 0x00, 0x42};

        // when
        channel.writeInbound(Unpooled.wrappedBuffer(notV2));

        // then — pass through, no destination
        assertThat(channel.attr(REMOTE_SOCKET).get(), is(nullValue()));
        assertThat(channel.pipeline().get(ProxyProtocolOriginalDestinationHandler.class), is(nullValue()));

        ByteBuf remaining = channel.readInbound();
        assertThat(remaining, is(notNullValue()));
        byte[] result = new byte[remaining.readableBytes()];
        remaining.readBytes(result);
        assertThat(result, is(notV2));
        remaining.release();

        channel.close();
    }

    // --- what follows the header, and who sent it ---

    @Test
    public void shouldPassOnAShortGreetingThatIsNotAProxyHeaderAtOnce() {
        // a SOCKS5 greeting is three bytes, and its client waits for the answer before sending more
        EmbeddedChannel channel = new EmbeddedChannel(new ProxyProtocolOriginalDestinationHandler(logger));
        byte[] greeting = {0x05, 0x01, 0x00};

        channel.writeInbound(Unpooled.wrappedBuffer(greeting));

        assertPassedOnUnchanged(channel, greeting);
    }

    @Test
    public void shouldPassOnShortBytesThatStartLikeAV1HeaderButDivergeAtOnce() {
        EmbeddedChannel channel = new EmbeddedChannel(new ProxyProtocolOriginalDestinationHandler(logger));
        byte[] put = "PU".getBytes(StandardCharsets.US_ASCII);

        channel.writeInbound(Unpooled.wrappedBuffer(put));

        assertPassedOnUnchanged(channel, put);
    }

    @Test
    public void shouldPassOnShortBytesThatStartLikeAV2HeaderButDivergeAtOnce() {
        EmbeddedChannel channel = new EmbeddedChannel(new ProxyProtocolOriginalDestinationHandler(logger));
        byte[] notV2 = {0x0D, 0x0A, 0x42};

        channel.writeInbound(Unpooled.wrappedBuffer(notV2));

        assertPassedOnUnchanged(channel, notV2);
    }

    @Test
    public void shouldHoldBytesWhileTheyMayStillBeAProxyHeader() {
        EmbeddedChannel v1 = new EmbeddedChannel(new ProxyProtocolOriginalDestinationHandler(logger));
        EmbeddedChannel v2 = new EmbeddedChannel(new ProxyProtocolOriginalDestinationHandler(logger));

        v1.writeInbound(Unpooled.copiedBuffer("PROX", StandardCharsets.US_ASCII));
        v2.writeInbound(Unpooled.wrappedBuffer(new byte[]{0x0D, 0x0A, 0x0D}));

        for (EmbeddedChannel channel : new EmbeddedChannel[]{v1, v2}) {
            assertThat(channel.readInbound(), is(nullValue()));
            assertThat(channel.pipeline().get(ProxyProtocolOriginalDestinationHandler.class), is(notNullValue()));
            channel.close();
        }
    }

    @Test
    public void shouldPassOnBytesThatAreOnlyTheStartOfASignatureWhenTheClientFallsSilent() {
        for (byte[] start : Arrays.asList(ascii("P"), ascii("PROXY"), new byte[]{0x0D}, new byte[]{0x0D, 0x0A}, Arrays.copyOf(PROXY_V2_SIGNATURE_START, 11))) {
            AtomicInteger readsCompleted = new AtomicInteger();
            EmbeddedChannel channel = new EmbeddedChannel(new ProxyProtocolOriginalDestinationHandler(logger), new ChannelInboundHandlerAdapter() {
                @Override
                public void channelReadComplete(ChannelHandlerContext ctx) {
                    readsCompleted.incrementAndGet();
                    ctx.fireChannelReadComplete();
                }
            });
            String sent = ByteBufUtil.hexDump(start);

            channel.writeInbound(Unpooled.wrappedBuffer(start));
            channel.advanceTimeBy(PortUnificationHandler.UNDECIDED_PROTOCOL_WAIT_MILLIS - 1, TimeUnit.MILLISECONDS);
            channel.runScheduledPendingTasks();
            assertThat(sent + " is held just before the wait ends", channel.readInbound(), is(nullValue()));
            int readsCompletedBeforeTheWaitEnds = readsCompleted.get();

            channel.advanceTimeBy(1, TimeUnit.MILLISECONDS);
            channel.runScheduledPendingTasks();
            assertThat(sent + " is passed on as a completed read", readsCompleted.get(), is(readsCompletedBeforeTheWaitEnds + 1));
            assertPassedOnUnchanged(channel, start);
        }
    }

    @Test
    public void shouldRestartTheWaitWhenMoreOfTheSignatureArrives() {
        EmbeddedChannel channel = new EmbeddedChannel(new ProxyProtocolOriginalDestinationHandler(logger));

        channel.writeInbound(Unpooled.copiedBuffer("PRO", StandardCharsets.US_ASCII));
        channel.advanceTimeBy(PortUnificationHandler.UNDECIDED_PROTOCOL_WAIT_MILLIS - 1, TimeUnit.MILLISECONDS);
        channel.runScheduledPendingTasks();
        channel.writeInbound(Unpooled.copiedBuffer("X", StandardCharsets.US_ASCII));
        channel.advanceTimeBy(PortUnificationHandler.UNDECIDED_PROTOCOL_WAIT_MILLIS - 1, TimeUnit.MILLISECONDS);
        channel.runScheduledPendingTasks();
        assertThat(channel.readInbound(), is(nullValue()));

        channel.advanceTimeBy(1, TimeUnit.MILLISECONDS);
        channel.runScheduledPendingTasks();
        assertPassedOnUnchanged(channel, ascii("PROX"));
    }

    @Test
    public void shouldNotEndTheWaitWhileReadsArePaused() {
        EmbeddedChannel channel = new EmbeddedChannel(new ProxyProtocolOriginalDestinationHandler(logger));
        channel.writeInbound(Unpooled.copiedBuffer("PR", StandardCharsets.US_ASCII));

        channel.config().setAutoRead(false);
        channel.advanceTimeBy(PortUnificationHandler.UNDECIDED_PROTOCOL_WAIT_MILLIS, TimeUnit.MILLISECONDS);
        channel.runScheduledPendingTasks();
        assertThat("the rest may be waiting unread", channel.readInbound(), is(nullValue()));

        channel.config().setAutoRead(true);
        channel.advanceTimeBy(PortUnificationHandler.UNDECIDED_PROTOCOL_WAIT_MILLIS, TimeUnit.MILLISECONDS);
        channel.runScheduledPendingTasks();
        assertPassedOnUnchanged(channel, ascii("PR"));
    }

    @Test
    public void shouldEndTheWaitWhenTheHeaderArrivesOrTheHandlerIsRemoved() {
        EmbeddedChannel decided = new EmbeddedChannel(new ProxyProtocolOriginalDestinationHandler(logger));
        decided.writeInbound(Unpooled.copiedBuffer("PROX", StandardCharsets.US_ASCII));
        decided.writeInbound(Unpooled.copiedBuffer("Y TCP4 192.168.1.1 10.0.0.1 56324 80\r\n", StandardCharsets.US_ASCII));
        assertThat(decided.attr(REMOTE_SOCKET).get(), is(new InetSocketAddress("10.0.0.1", 80)));
        assertThat("no wait is left running", decided.runScheduledPendingTasks(), is(-1L));
        decided.close();

        EmbeddedChannel removed = new EmbeddedChannel(new ProxyProtocolOriginalDestinationHandler(logger));
        ByteBuf held = Unpooled.copiedBuffer("PROX", StandardCharsets.US_ASCII);
        removed.writeInbound(held);
        removed.pipeline().remove(ProxyProtocolOriginalDestinationHandler.class);
        assertThat("the held bytes are released", held.refCnt(), is(0));
        assertThat("no wait is left running", removed.runScheduledPendingTasks(), is(-1L));
        removed.close();
    }

    @Test
    public void shouldKeepHoldingAHeaderWhoseSignatureHasArrived() {
        EmbeddedChannel channel = new EmbeddedChannel(new ProxyProtocolOriginalDestinationHandler(logger));

        channel.writeInbound(Unpooled.copiedBuffer("PROXY TCP4 192.168", StandardCharsets.US_ASCII));
        channel.advanceTimeBy(PortUnificationHandler.UNDECIDED_PROTOCOL_WAIT_MILLIS, TimeUnit.MILLISECONDS);
        channel.runScheduledPendingTasks();

        assertThat(channel.readInbound(), is(nullValue()));
        channel.writeInbound(Unpooled.copiedBuffer(".1.1 10.0.0.1 56324 80\r\n", StandardCharsets.US_ASCII));
        assertThat(channel.attr(REMOTE_SOCKET).get(), is(new InetSocketAddress("10.0.0.1", 80)));
        channel.close();
    }

    private static final byte[] PROXY_V2_SIGNATURE_START = {0x0D, 0x0A, 0x0D, 0x0A, 0x00, 0x0D, 0x0A, 0x51, 0x55, 0x49, 0x54, 0x0A};

    private static byte[] ascii(String text) {
        return text.getBytes(StandardCharsets.US_ASCII);
    }

    @Test
    public void shouldRecordTheV1SourceAsTheClientAddress() {
        EmbeddedChannel channel = new EmbeddedChannel(new ProxyProtocolOriginalDestinationHandler(logger));

        channel.writeInbound(Unpooled.copiedBuffer("PROXY TCP4 192.168.1.1 10.0.0.1 56324 80\r\n", StandardCharsets.US_ASCII));

        assertThat(channel.attr(PROXY_PROTOCOL_SOURCE).get(), is(new InetSocketAddress("192.168.1.1", 56324)));
        channel.close();
    }

    @Test
    public void shouldRecordTheV1Tcp6SourceAsTheClientAddress() {
        EmbeddedChannel channel = new EmbeddedChannel(new ProxyProtocolOriginalDestinationHandler(logger));

        channel.writeInbound(Unpooled.copiedBuffer("PROXY TCP6 2001:db8::1 2001:db8::2 56324 443\r\n", StandardCharsets.US_ASCII));

        assertThat(channel.attr(PROXY_PROTOCOL_SOURCE).get(), is(new InetSocketAddress("2001:db8::1", 56324)));
        channel.close();
    }

    @Test
    public void shouldNotRecordAV1SourceThatIsNotAnIpAddress() {
        EmbeddedChannel channel = new EmbeddedChannel(new ProxyProtocolOriginalDestinationHandler(logger));

        channel.writeInbound(Unpooled.copiedBuffer("PROXY TCP4 not-an-address 10.0.0.1 56324 80\r\n", StandardCharsets.US_ASCII));

        assertThat(channel.attr(PROXY_PROTOCOL_SOURCE).get(), is(nullValue()));
        assertThat(channel.attr(REMOTE_SOCKET).get(), is(new InetSocketAddress("10.0.0.1", 80)));
        channel.close();
    }

    @Test
    public void shouldNotRecordAV1SourcePortOutOfRange() {
        EmbeddedChannel channel = new EmbeddedChannel(new ProxyProtocolOriginalDestinationHandler(logger));

        channel.writeInbound(Unpooled.copiedBuffer("PROXY TCP4 192.168.1.1 10.0.0.1 65536 80\r\n", StandardCharsets.US_ASCII));

        assertThat(channel.attr(PROXY_PROTOCOL_SOURCE).get(), is(nullValue()));
        channel.close();
    }

    @Test
    public void shouldRecordTheV2InetSourceAsTheClientAddress() {
        EmbeddedChannel channel = new EmbeddedChannel(new ProxyProtocolOriginalDestinationHandler(logger));
        byte[] addr = {
            (byte) 192, (byte) 168, 1, 1,   // src
            10, 0, 0, 1,                     // dst
            (byte) 0xDC, 0x04,               // src port 56324
            0x01, (byte) 0xBB                // dst port 443
        };

        channel.writeInbound(Unpooled.wrappedBuffer(proxyV2(0x21, 0x11, addr)));

        assertThat(channel.attr(PROXY_PROTOCOL_SOURCE).get(), is(new InetSocketAddress("192.168.1.1", 56324)));
        assertThat(channel.attr(REMOTE_SOCKET).get(), is(new InetSocketAddress("10.0.0.1", 443)));
        channel.close();
    }

    @Test
    public void shouldRecordTheV2Inet6SourceAsTheClientAddress() {
        EmbeddedChannel channel = new EmbeddedChannel(new ProxyProtocolOriginalDestinationHandler(logger));
        byte[] addr = new byte[36];
        addr[0] = 0x20; addr[1] = 0x01; addr[2] = 0x0d; addr[3] = (byte) 0xb8; addr[15] = 1; // src 2001:db8::1
        addr[31] = 2;                                                                       // dst ::2
        addr[32] = (byte) 0xDC; addr[33] = 0x04;                                            // src port 56324
        addr[34] = 0x01; addr[35] = (byte) 0xBB;                                            // dst port 443

        channel.writeInbound(Unpooled.wrappedBuffer(proxyV2(0x21, 0x21, addr)));

        assertThat(channel.attr(PROXY_PROTOCOL_SOURCE).get(), is(new InetSocketAddress("2001:db8::1", 56324)));
        assertThat(channel.attr(REMOTE_SOCKET).get(), is(new InetSocketAddress("::2", 443)));
        channel.close();
    }

    @Test
    public void shouldNotRecordASourceForAV2LocalCommand() {
        EmbeddedChannel channel = new EmbeddedChannel(new ProxyProtocolOriginalDestinationHandler(logger));
        byte[] addr = {(byte) 192, (byte) 168, 1, 1, 10, 0, 0, 1, (byte) 0xDC, 0x04, 0x01, (byte) 0xBB};

        channel.writeInbound(Unpooled.wrappedBuffer(proxyV2(0x20, 0x11, addr)));

        assertThat(channel.attr(PROXY_PROTOCOL_SOURCE).get(), is(nullValue()));
        channel.close();
    }

    private static void assertPassedOnUnchanged(EmbeddedChannel channel, byte[] expected) {
        assertThat(channel.pipeline().get(ProxyProtocolOriginalDestinationHandler.class), is(nullValue()));
        ByteBuf passedOn = channel.readInbound();
        assertThat(passedOn, is(notNullValue()));
        byte[] actual = new byte[passedOn.readableBytes()];
        passedOn.readBytes(actual);
        passedOn.release();
        assertThat(actual, is(expected));
        assertThat(channel.attr(REMOTE_SOCKET).get(), is(nullValue()));
        channel.close();
    }
}
