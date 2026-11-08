package org.mockserver.logging;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.PooledByteBufAllocator;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http2.DefaultHttp2Headers;
import io.netty.handler.codec.http2.Http2FrameLogger;
import io.netty.handler.codec.http2.Http2Headers;
import org.junit.After;
import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.lessThan;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.hamcrest.Matchers.sameInstance;
import static org.mockserver.configuration.Configuration.configuration;

public class RawNetworkTrafficLogTest {

    private static final String SECRET = "s3cr3t-value-that-must-not-be-logged";

    private final List<String> logged = new CopyOnWriteArrayList<>();
    private final AtomicReference<String> apiKeyHeader = new AtomicReference<>("X-My-Api-Key");
    private final RawNetworkTrafficLog log = new RawNetworkTrafficLog(RawNetworkTrafficLog.SERVER_SIDE, apiKeyHeader::get, logged::add);
    private EmbeddedChannel channel;

    @After
    public void closeChannel() {
        if (channel != null) {
            channel.finishAndReleaseAll();
        }
    }

    @Test
    public void shouldNotBuildALogWhenThePropertyIsOff() {
        assertThat(RawNetworkTrafficLog.forConfiguration(configuration().logRawNetworkTraffic(false), RawNetworkTrafficLog.SERVER_SIDE), is(nullValue()));
        assertThat(RawNetworkTrafficLog.forConfiguration(null, RawNetworkTrafficLog.SERVER_SIDE), is(nullValue()));
        assertThat(RawNetworkTrafficLog.forConfiguration(configuration().logRawNetworkTraffic(true), RawNetworkTrafficLog.SERVER_SIDE), is(notNullValue()));
    }

    @Test
    public void shouldLogTheRequestLineAndMaskSensitiveHeaders() {
        channel = textChannel();

        readText("GET /some/path?x=1 HTTP/1.1\r\n" +
            "Host: localhost\r\n" +
            "Authorization: Bearer " + SECRET + "\r\n" +
            "cookie: session=" + SECRET + "\r\n" +
            "X-My-Api-Key: " + SECRET + "\r\n" +
            "Proxy-Authorization : Basic " + SECRET + "\r\n" +
            "X-Other: visible\r\n" +
            "\r\n");

        assertThat(logged, hasSize(1));
        String entry = logged.get(0);
        assertThat(entry, containsString("server connection"));
        assertThat(entry, containsString(" received "));
        assertThat(entry, containsString("GET /some/path?x=1 HTTP/1.1\\r\\n" + RawNetworkTrafficLog.CONTINUATION));
        assertThat(entry, containsString("Host: localhost\\r\\n" + RawNetworkTrafficLog.CONTINUATION));
        assertThat(entry, containsString("Authorization: " + RawNetworkTrafficLog.REDACTED + "\\r\\n" + RawNetworkTrafficLog.CONTINUATION));
        assertThat(entry, containsString("cookie: " + RawNetworkTrafficLog.REDACTED));
        assertThat(entry, containsString("X-My-Api-Key: " + RawNetworkTrafficLog.REDACTED));
        assertThat(entry, containsString("Proxy-Authorization :" + " " + RawNetworkTrafficLog.REDACTED));
        assertThat(entry, containsString("X-Other: visible\\r\\n" + RawNetworkTrafficLog.CONTINUATION));
        assertThat(entry, not(containsString(SECRET)));
    }

    @Test
    public void shouldMaskAHeaderSplitAcrossReads() {
        channel = textChannel();

        readText("GET / HTTP/1.1\r\nAuthor");
        readText("ization: Bearer " + SECRET.substring(0, 10));
        readText(SECRET.substring(10) + "\r\nAccept: */*\r\n\r\n");

        String all = String.join("\n", logged);
        assertThat(all, not(containsString(SECRET.substring(0, 10))));
        assertThat(all, not(containsString(SECRET.substring(10))));
        assertThat(all, containsString("ization: " + RawNetworkTrafficLog.REDACTED));
        assertThat(all, containsString("Accept: */*"));
    }

    @Test
    public void shouldMaskAFoldedContinuationOfASensitiveHeader() {
        channel = textChannel();

        readText("GET / HTTP/1.1\r\nAuthorization: Bearer\r\n " + SECRET + "\r\nAccept: */*\r\n\r\n");

        assertThat(logged.get(0), not(containsString(SECRET)));
        assertThat(logged.get(0), containsString("Accept: */*"));
    }

    @Test
    public void shouldMaskTheRestOfALineWhoseNameStartsWithASensitiveHeader() {
        channel = textChannel();

        // Netty's decoder reads "Set-Cookie junk: v" as Set-Cookie, and "Cookie2" starts like Cookie
        readText("HTTP/1.1 200 OK\r\nSet-Cookie junk: " + SECRET + "\r\nCookie2: " + SECRET + "\r\nAuthorization\u000b" + SECRET + "\r\nAccept: */*\r\n\r\n");

        assertThat(logged.get(0), not(containsString(SECRET)));
        assertThat(logged.get(0), containsString("Set-Cookie " + RawNetworkTrafficLog.REDACTED));
        assertThat(logged.get(0), containsString("Accept: */*"));
    }

    @Test
    public void shouldMaskTheApiKeyHeaderConfiguredWhenTheTrafficIsLogged() {
        channel = textChannel();
        apiKeyHeader.set("X-Renamed-Key");

        readText("GET / HTTP/1.1\r\nX-Renamed-Key: " + SECRET + "\r\n\r\n");

        assertThat(logged.get(0), containsString("X-Renamed-Key: " + RawNetworkTrafficLog.REDACTED));
        assertThat(logged.get(0), not(containsString(SECRET)));
    }

    @Test
    public void shouldMaskTheForwardCredentialHeadersConfigured() {
        RawNetworkTrafficLog configured = RawNetworkTrafficLog.forConfiguration(configuration()
            .logRawNetworkTraffic(true)
            .forwardCredentialHeadersByHost("api.example.com=X-Upstream-Credential:UNSET_VARIABLE;X-Tenant-Secret:OTHER"), RawNetworkTrafficLog.FORWARD_SIDE);

        assertThat(configured.isSensitiveHeader("x-upstream-credential"), is(true));
        assertThat(configured.isSensitiveHeader("X-Tenant-Secret"), is(true));
        assertThat(configured.isSensitiveHeader("X-Unrelated"), is(false));
        assertThat(RawNetworkTrafficLog.forConfiguration(configuration().logRawNetworkTraffic(true), RawNetworkTrafficLog.FORWARD_SIDE).isSensitiveHeader("X-Upstream-Credential"), is(false));
    }

    @Test
    public void shouldNotLetTrafficForgeALogLine() {
        channel = textChannel();

        readText("POST / HTTP/1.1\r\n\r\nline one\n2026-10-22 ERROR forged entry\rcarriage\u0007bell");

        String entry = logged.get(0);
        String[] lines = entry.split("\n", -1);
        for (int i = 1; i < lines.length; i++) {
            assertThat("continuation line " + i + " of: " + entry, lines[i].startsWith("| "), is(true));
        }
        assertThat(entry, containsString("line one\\n" + RawNetworkTrafficLog.CONTINUATION + "2026-10-22 ERROR forged entry\\rcarriage\\x07bell"));
        assertThat(entry, not(containsString("\r")));
    }

    @Test
    public void shouldMaskSensitiveHeadersInWhatIsSent() {
        channel = textChannel();

        ByteBuf response = Unpooled.copiedBuffer("HTTP/1.1 200 OK\r\nSet-Cookie: id=" + SECRET + "\r\ncontent-length: 0\r\n\r\n", StandardCharsets.UTF_8);
        channel.writeOutbound(response);
        ByteBuf written = channel.readOutbound();

        assertThat(written, sameInstance(response));
        assertThat(written.refCnt(), is(1));
        written.release();
        assertThat(logged, hasSize(1));
        assertThat(logged.get(0), containsString(" sent "));
        assertThat(logged.get(0), containsString("HTTP/1.1 200 OK"));
        assertThat(logged.get(0), containsString("Set-Cookie: " + RawNetworkTrafficLog.REDACTED));
        assertThat(logged.get(0), not(containsString(SECRET)));
    }

    @Test
    public void shouldShowAtMostTheLimitOfEachMessage() {
        channel = textChannel();
        String head = "POST /upload HTTP/1.1\r\ncontent-length: 20000\r\n\r\n";
        StringBuilder body = new StringBuilder();
        for (int i = 0; i < 20_000; i++) {
            body.append((char) ('a' + i % 26));
        }

        readText(head + body);

        String entry = logged.get(0);
        int notShown = head.length() + body.length() - RawNetworkTrafficLog.MAX_BYTES_SHOWN;
        assertThat(entry, containsString("POST /upload HTTP/1.1"));
        assertThat(entry, containsString("... " + notShown + " more bytes not shown"));
        assertThat(entry.length(), lessThan(RawNetworkTrafficLog.MAX_BYTES_SHOWN + 512));
    }

    @Test
    public void shouldKeepMaskingAfterATruncatedMessage() {
        channel = textChannel();
        StringBuilder body = new StringBuilder();
        for (int i = 0; i < RawNetworkTrafficLog.MAX_BYTES_SHOWN + 100; i++) {
            body.append('x');
        }

        // the sensitive header name falls in the part of the read that is not shown
        readText("POST / HTTP/1.1\r\n\r\n" + body + "\r\nAuthorization: Bearer ");
        readText(SECRET + "\r\n\r\n");

        assertThat(String.join("\n", logged), not(containsString(SECRET)));
    }

    @Test
    public void shouldEscapeControlBytes() {
        channel = textChannel();

        readBytes(new byte[]{'a', 0, 0x1b, (byte) 0xff, '\t', 'b'});

        assertThat(logged.get(0), containsString("a\\x00\\x1b\\xff\\tb"));
    }

    @Test
    public void shouldHexDumpBinaryConnections() {
        channel = new EmbeddedChannel();
        log.addTo(channel.pipeline(), true);

        readBytes(new byte[]{0, 0, 0, 8, '\n', 'o', 'k', 0x2f});

        String entry = logged.get(0);
        assertThat(entry, containsString("8 bytes:"));
        assertThat(entry, containsString("00 00 00 08 0a 6f 6b 2f"));
        // the ASCII column shows printable bytes raw, and a line break as a dot
        assertThat(entry, containsString("|.....ok/"));
        String[] lines = entry.split("\n", -1);
        for (int i = 1; i < lines.length; i++) {
            assertThat("continuation line " + i + " of: " + entry, lines[i].startsWith("| "), is(true));
        }
    }

    @Test
    public void shouldPassMessagesOnUnchangedWithoutTouchingTheirReferenceCountOrIndexes() {
        channel = textChannel();
        ByteBuf pooled = PooledByteBufAllocator.DEFAULT.buffer();
        pooled.writeBytes("GET / HTTP/1.1\r\nHost: x\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
        pooled.readerIndex(4);
        int readerIndex = pooled.readerIndex();
        int writerIndex = pooled.writerIndex();

        channel.writeInbound(pooled);
        ByteBuf read = channel.readInbound();

        assertThat(read, sameInstance(pooled));
        assertThat(read.refCnt(), is(1));
        assertThat(read.readerIndex(), is(readerIndex));
        assertThat(read.writerIndex(), is(writerIndex));
        assertThat(read.release(), is(true));

        Object notBytes = new Object();
        channel.writeInbound(notBytes);
        assertThat(channel.readInbound(), sameInstance(notBytes));
        assertThat(logged.get(1), containsString("Object (not shown)"));
    }

    @Test
    public void shouldAddTheHandlerOnceAndRemoveIt() {
        channel = new EmbeddedChannel();
        log.addTo(channel.pipeline(), false);
        log.addTo(channel.pipeline(), true);

        assertThat(channel.pipeline().names().stream().filter(RawNetworkTrafficLog.HANDLER_NAME::equals).count(), is(1L));

        RawNetworkTrafficLog.removeFrom(channel.pipeline());
        RawNetworkTrafficLog.removeFrom(channel.pipeline());
        assertThat(channel.pipeline().get(RawNetworkTrafficLog.HANDLER_NAME), is(nullValue()));
    }

    @Test
    public void shouldLogHttp2HeadersWithSensitiveValuesMasked() {
        channel = new EmbeddedChannel();
        ChannelHandlerContext ctx = contextOf(channel);
        Http2FrameLogger frameLogger = log.http2FrameLogger();
        Http2Headers headers = new DefaultHttp2Headers(false)
            .method("GET")
            .path("/h2/path")
            .add("authorization", "Bearer " + SECRET)
            .add("x-note", "line\nbreak");

        frameLogger.logHeaders(Http2FrameLogger.Direction.INBOUND, ctx, 3, headers, 0, true);

        assertThat(logged.get(0), containsString("received HEADERS frame: stream=3 endStream=true"));
        assertThat(logged.get(0), containsString(":path: /h2/path"));
        assertThat(logged.get(0), containsString("authorization: " + RawNetworkTrafficLog.REDACTED));
        assertThat(logged.get(0), containsString("x-note: line\\nbreak"));
        assertThat(logged.get(0), not(containsString(SECRET)));
    }

    @Test
    public void shouldLogHttp2DataUpToTheLimit() {
        channel = new EmbeddedChannel();
        ChannelHandlerContext ctx = contextOf(channel);
        ByteBuf data = Unpooled.wrappedBuffer(new byte[RawNetworkTrafficLog.MAX_BYTES_SHOWN + 10]);

        log.http2FrameLogger().logData(Http2FrameLogger.Direction.OUTBOUND, ctx, 5, data, 0, false);

        assertThat(logged.get(0), containsString("sent DATA frame: stream=5"));
        assertThat(logged.get(0), containsString("... 10 more bytes not shown"));
        assertThat(data.readerIndex(), is(0));
        assertThat(data.refCnt(), is(1));
        data.release();
    }

    private EmbeddedChannel textChannel() {
        EmbeddedChannel textChannel = new EmbeddedChannel();
        log.addTo(textChannel.pipeline(), false);
        return textChannel;
    }

    private static ChannelHandlerContext contextOf(EmbeddedChannel embeddedChannel) {
        embeddedChannel.pipeline().addLast("context", new io.netty.channel.ChannelInboundHandlerAdapter());
        return embeddedChannel.pipeline().context("context");
    }

    private void readText(String text) {
        readBytes(text.getBytes(StandardCharsets.UTF_8));
    }

    private void readBytes(byte[] bytes) {
        channel.writeInbound(Unpooled.wrappedBuffer(bytes));
        ByteBuf read = channel.readInbound();
        read.release();
    }
}
