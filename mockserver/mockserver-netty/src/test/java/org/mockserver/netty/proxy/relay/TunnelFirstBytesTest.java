package org.mockserver.netty.proxy.relay;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.junit.Test;
import org.mockserver.netty.proxy.relay.TunnelFirstBytes.Protocol;

import java.nio.charset.StandardCharsets;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.mockserver.netty.proxy.relay.TunnelFirstBytes.Protocol.BINARY;
import static org.mockserver.netty.proxy.relay.TunnelFirstBytes.Protocol.H2C;
import static org.mockserver.netty.proxy.relay.TunnelFirstBytes.Protocol.HTTP1;
import static org.mockserver.netty.proxy.relay.TunnelFirstBytes.Protocol.NEED_MORE;
import static org.mockserver.netty.proxy.relay.TunnelFirstBytes.Protocol.TLS;

public class TunnelFirstBytesTest {

    private static final int MAX_LINE = 64;
    private static final byte[] CLIENT_HELLO_START = {22, 3, 1, 0, (byte) 0xc8, 1, 0, 0, (byte) 0xc4, 3, 3};
    private static final byte[] SSL_REQUEST = {0, 0, 0, 8, 4, (byte) 0xd2, 0x16, 0x2f};

    private static Protocol classify(byte[] bytes, boolean http2Enabled, boolean assumeAllRequestsAreHttp, boolean noMoreToCome) {
        ByteBuf in = Unpooled.wrappedBuffer(bytes);
        try {
            return TunnelFirstBytes.classify(in, http2Enabled, assumeAllRequestsAreHttp, MAX_LINE, noMoreToCome);
        } finally {
            in.release();
        }
    }

    private static Protocol classify(String text) {
        return classify(text.getBytes(StandardCharsets.ISO_8859_1), true, false, false);
    }

    private static Protocol classify(String text, int maxInitialLineLength) {
        ByteBuf in = Unpooled.wrappedBuffer(text.getBytes(StandardCharsets.ISO_8859_1));
        try {
            return TunnelFirstBytes.classify(in, true, false, maxInitialLineLength, false);
        } finally {
            in.release();
        }
    }

    private static Protocol classifySilent(String text) {
        return classify(text.getBytes(StandardCharsets.ISO_8859_1), true, false, true);
    }

    @Test
    public void shouldTellTls() {
        assertThat(classify(CLIENT_HELLO_START, true, false, false), is(TLS));
        assertThat("a record header not yet whole", classify(new byte[]{22, 3}, true, false, false), is(NEED_MORE));
        assertThat("a record type with a header no TLS record has", classify(new byte[]{22, 9, 9, 9, 9}, true, false, false), is(BINARY));
    }

    @Test
    public void shouldTellTheH2cPrefaceOnlyWithHttp2Enabled() {
        String preface = "PRI * HTTP/2.0\r\n\r\nSM\r\n\r\n";
        assertThat(classify(preface), is(H2C));
        assertThat(classify("PRI * HTTP/2"), is(NEED_MORE));
        assertThat("a request line like any other with HTTP/2 off", classify(preface.getBytes(StandardCharsets.US_ASCII), false, false, false), is(HTTP1));
    }

    @Test
    public void shouldTellHttpByItsRequestLineWhateverItsMethod() {
        assertThat(classify("GET / HTTP/1.1\r\n"), is(HTTP1));
        assertThat(classify("PROPFIND /dav HTTP/1.1\r\nDepth: 1\r\n"), is(HTTP1));
        assertThat(classify("purge /x HTTP/1.0\n"), is(HTTP1));
        assertThat(classify("GET http://example.com/a?b=c HTTP/1.1\r\n"), is(HTTP1));
        assertThat("an empty line before it", classify("\r\nGET / HTTP/1.1\r\n"), is(HTTP1));
        assertThat("a websocket upgrade", classify("GET /ws HTTP/1.1\r\nUpgrade: websocket\r\n"), is(HTTP1));
        assertThat("a target in UTF-8", classify("GET /cafÃ© HTTP/1.1\r\n"), is(HTTP1));
    }

    @Test
    public void shouldWaitForTheRestOfARequestLine() {
        assertThat(classify("G"), is(NEED_MORE));
        assertThat(classify("GET"), is(NEED_MORE));
        assertThat(classify("GET /a"), is(NEED_MORE));
        assertThat(classify("GET /a HTTP/1."), is(NEED_MORE));
        assertThat(classify("GET /a HTTP/1.1\r"), is(NEED_MORE));
    }

    @Test
    public void shouldTakeAnUnfinishedRequestLinePastItsMethodAsHttpOnceTheClientFallsSilent() {
        assertThat(classifySilent("PROPFIND /da"), is(HTTP1));
        assertThat(classifySilent("GET "), is(HTTP1));
        assertThat("no method yet", classifySilent("GET"), is(BINARY));
    }

    @Test
    public void shouldTakeAnOverLongRequestLineAsHttpForTheCodecToRefuse() {
        assertThat(classify("GET /" + "a".repeat(MAX_LINE)), is(HTTP1));
        assertThat("a method longer than any line", classify("A".repeat(MAX_LINE + 1)), is(BINARY));
    }

    @Test
    public void shouldLookNoFurtherThanItsLookAheadWhateverTheLineLimit() {
        int lineLimit = 65_536;
        assertThat("a line past its method longer than the look-ahead", classify("GET /" + "a".repeat(TunnelFirstBytes.MAX_LOOK_AHEAD), lineLimit), is(HTTP1));
        assertThat("a method longer than the look-ahead", classify("A".repeat(TunnelFirstBytes.MAX_LOOK_AHEAD + 1), lineLimit), is(BINARY));
        assertThat("a line within the look-ahead", classify("GET /" + "a".repeat(TunnelFirstBytes.MAX_LOOK_AHEAD - 10), lineLimit), is(NEED_MORE));
    }

    @Test
    public void shouldTakeRareFormsOfHttpAndTlsAsBinary() {
        assertThat("an SSLv2-format ClientHello", classify(new byte[]{(byte) 0x80, 0x2e, 1, 3, 1}, true, false, false), is(BINARY));
        assertThat("whitespace before the method", classify(" GET / HTTP/1.1\r\n"), is(BINARY));
        assertThat("a lower-case version", classify("GET / http/1.1\r\n"), is(BINARY));
    }

    @Test
    public void shouldTellBinary() {
        assertThat("a PostgreSQL SSLRequest", classify(SSL_REQUEST, true, false, false), is(BINARY));
        assertThat("a Redis array", classify("*1\r\n$4\r\nPING\r\n"), is(BINARY));
        assertThat("a Redis inline command", classify("GET key\r\n"), is(BINARY));
        assertThat("a Redis SET", classify("SET k v\r\n"), is(BINARY));
        assertThat("memcached", classify("get k\r\n"), is(BINARY));
        assertThat("a line with no version", classify("hello\n"), is(BINARY));
        assertThat("another protocol's version", classify("OPTIONS rtsp://a/b RTSP/1.0\r\n"), is(BINARY));
        assertThat("a nested SOCKS5 greeting", classify(new byte[]{5, 1, 0}, true, false, false), is(BINARY));
        assertThat("a nested SOCKS4 request", classify(new byte[]{4, 1, 0, 80, 127, 0, 0, 1, 0}, true, false, false), is(BINARY));
        assertThat("a PROXIED_ preamble", classify("PROXIED_host:80"), is(BINARY));
        assertThat("a short message once the client falls silent", classifySilent("ab"), is(BINARY));
        assertThat("a short TLS-looking message once the client falls silent", classify(new byte[]{22, 3}, true, false, true), is(BINARY));
    }

    @Test
    public void shouldTakeEverythingButTlsAndH2cAsHttpWhenAssumeAllRequestsAreHttp() {
        assertThat(classify(SSL_REQUEST, true, true, false), is(HTTP1));
        assertThat(classify("hello\n".getBytes(StandardCharsets.US_ASCII), true, true, false), is(HTTP1));
        assertThat(classify(CLIENT_HELLO_START, true, true, false), is(TLS));
        assertThat(classify("PRI * HTTP/2.0\r\n\r\nSM\r\n\r\n".getBytes(StandardCharsets.US_ASCII), true, true, false), is(H2C));
    }
}
