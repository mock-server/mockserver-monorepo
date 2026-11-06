package org.mockserver.netty.unification;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.DefaultFullHttpRequest;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.FullHttpMessage;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.codec.http2.DefaultHttp2Connection;
import io.netty.handler.codec.http2.DefaultHttp2Headers;
import io.netty.handler.codec.http2.DefaultHttp2FrameReader;
import io.netty.handler.codec.http2.Http2CodecUtil;
import io.netty.handler.codec.http2.Http2ConnectionHandler;
import io.netty.handler.codec.http2.Http2ConnectionHandlerBuilder;
import io.netty.handler.codec.http2.Http2Exception;
import io.netty.handler.codec.http2.Http2FrameAdapter;
import io.netty.handler.codec.http2.Http2Headers;
import io.netty.handler.codec.http2.HttpConversionUtil.ExtensionHeaderNames;
import io.netty.handler.codec.http2.HttpToHttp2ConnectionHandler;
import io.netty.handler.codec.http2.ReadOnlyHttp2Headers;
import io.netty.util.AsciiString;
import org.junit.Test;
import org.mockserver.logging.MockServerLogger;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.sameInstance;
import static org.mockserver.configuration.Configuration.configuration;

/**
 * The header blocks a tunnel's two HTTP/2 handlers send, read off the wire: a request relayed to the loopback and a
 * response relayed to the proxy client carry none of the {@code x-http2-} extension headers Netty's adapter set on
 * them when it read them, and the priority the extension headers held is still sent, in the frame's priority fields.
 */
public class ExtensionHeaderStrippingHttp2ConnectionEncoderTest {

    @Test
    public void shouldRelayARequestToTheLoopbackWithoutExtensionHeaders() {
        HttpToHttp2ConnectionHandler loopback = Http2RequestHeaderLimit.relayLoopbackHandler(new MockServerLogger(), new EmbeddedChannel(), new DefaultHttp2Connection(false), new Http2FrameAdapter(), null);
        EmbeddedChannel channel = new EmbeddedChannel(loopback);
        HeadersRead read = new HeadersRead();
        try {
            read.from(written(channel));
            FullHttpMessage request = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, "/path");
            request.headers().set("host", "localhost").set("x-kept", "kept");
            withEveryExtensionHeader(request, 1);
            channel.writeAndFlush(request);

            read.from(written(channel));

            assertThat(read.blocks, hasSize(1));
            assertThat(extensionHeaders(read.blocks.get(0)), is(empty()));
            assertThat(read.blocks.get(0).get("x-kept"), is((CharSequence) AsciiString.of("kept")));
            assertThat("the stream weight is sent as the frame's priority", read.weights, contains((short) 42));
        } finally {
            read.close();
            channel.finishAndReleaseAll();
        }
    }

    @Test
    public void shouldRelayAResponseToTheProxyClientWithoutExtensionHeaders() {
        HttpToHttp2ConnectionHandler tunnel = Http2RequestHeaderLimit.tunnelServerHandler(configuration(), new MockServerLogger(), new DefaultHttp2Connection(true), new Http2FrameAdapter(), null, false);
        EmbeddedChannel server = new EmbeddedChannel(tunnel);
        Http2ConnectionHandler clientCodec = new Http2ConnectionHandlerBuilder().server(false).frameListener(new Http2FrameAdapter()).build();
        EmbeddedChannel client = new EmbeddedChannel(clientCodec);
        HeadersRead read = new HeadersRead();
        try {
            sendRequestOnStream3(client, clientCodec, server);
            read.from(written(server));

            FullHttpMessage response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK, Unpooled.copiedBuffer("served", StandardCharsets.UTF_8));
            response.headers().set("x-kept", "kept");
            withEveryExtensionHeader(response, 3);
            server.writeAndFlush(response);

            read.from(written(server));

            assertThat(read.blocks, hasSize(1));
            assertThat(read.blocks.get(0).status(), is((CharSequence) AsciiString.of("200")));
            assertThat(extensionHeaders(read.blocks.get(0)), is(empty()));
            assertThat(read.blocks.get(0).get("x-kept"), is((CharSequence) AsciiString.of("kept")));
        } finally {
            read.close();
            server.finishAndReleaseAll();
            client.finishAndReleaseAll();
        }
    }

    @Test
    public void shouldWriteAHeaderBlockWithoutPriorityWithoutExtensionHeaders() {
        HttpToHttp2ConnectionHandler tunnel = Http2RequestHeaderLimit.tunnelServerHandler(configuration(), new MockServerLogger(), new DefaultHttp2Connection(true), new Http2FrameAdapter(), null, false);
        EmbeddedChannel server = new EmbeddedChannel(tunnel);
        Http2ConnectionHandler clientCodec = new Http2ConnectionHandlerBuilder().server(false).frameListener(new Http2FrameAdapter()).build();
        EmbeddedChannel client = new EmbeddedChannel(clientCodec);
        HeadersRead read = new HeadersRead();
        try {
            sendRequestOnStream3(client, clientCodec, server);
            read.from(written(server));

            ChannelHandlerContext serverCtx = server.pipeline().context(tunnel);
            Http2Headers headers = new DefaultHttp2Headers().status("200").add("x-kept", "kept").addShort(ExtensionHeaderNames.STREAM_WEIGHT.text(), (short) 16);
            tunnel.encoder().writeHeaders(serverCtx, 3, headers, 0, true, serverCtx.newPromise());
            server.flush();

            read.from(written(server));

            assertThat(read.blocks, hasSize(1));
            assertThat(extensionHeaders(read.blocks.get(0)), is(empty()));
            assertThat(read.blocks.get(0).get("x-kept"), is((CharSequence) AsciiString.of("kept")));
            assertThat("no priority", read.weights, is(empty()));
        } finally {
            read.close();
            server.finishAndReleaseAll();
            client.finishAndReleaseAll();
        }
    }

    @Test
    public void shouldLeaveAReadOnlyHeaderBlockWithoutExtensionHeadersAsItIs() {
        Http2Headers readOnly = ReadOnlyHttp2Headers.serverHeaders(false, AsciiString.of("200"), AsciiString.of("x-kept"), AsciiString.of("kept"));

        assertThat(ExtensionHeaderStrippingHttp2ConnectionEncoder.withoutExtensionHeaders(readOnly), sameInstance(readOnly));
        assertThat(readOnly.size(), is(2));
    }

    @Test
    public void shouldRemoveEveryExtensionHeaderAndNothingElse() {
        Http2Headers headers = new DefaultHttp2Headers().status("200").add("x-kept", "kept").add("x-http2-not-netty", "kept");
        for (ExtensionHeaderNames name : ExtensionHeaderNames.values()) {
            headers.add(name.text(), "1");
        }

        ExtensionHeaderStrippingHttp2ConnectionEncoder.withoutExtensionHeaders(headers);

        List<String> names = new ArrayList<>();
        headers.forEach(header -> names.add(header.getKey().toString()));
        assertThat(names, contains(":status", "x-kept", "x-http2-not-netty"));
    }

    private static void sendRequestOnStream3(EmbeddedChannel client, Http2ConnectionHandler clientCodec, EmbeddedChannel server) {
        ChannelHandlerContext clientCtx = client.pipeline().context(clientCodec);
        clientCodec.encoder().writeHeaders(clientCtx, 3, new DefaultHttp2Headers().method("GET").scheme("https").authority("localhost").path("/path"), 0, true, clientCtx.newPromise());
        client.flush();
        server.writeInbound(Unpooled.wrappedBuffer(written(client)));
    }

    private static void withEveryExtensionHeader(FullHttpMessage message, int streamId) {
        message.headers()
            .setInt(ExtensionHeaderNames.STREAM_ID.text(), streamId)
            .set(ExtensionHeaderNames.SCHEME.text(), "https")
            .setShort(ExtensionHeaderNames.STREAM_WEIGHT.text(), (short) 42)
            .setInt(ExtensionHeaderNames.STREAM_PROMISE_ID.text(), 2);
    }

    private static List<String> extensionHeaders(Http2Headers headers) {
        List<String> names = new ArrayList<>();
        headers.forEach(header -> {
            if (header.getKey().toString().toLowerCase(Locale.ROOT).startsWith("x-http2-")) {
                names.add(header.getKey().toString());
            }
        });
        return names;
    }

    private static byte[] written(EmbeddedChannel channel) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        for (ByteBuf buffer = channel.readOutbound(); buffer != null; buffer = channel.readOutbound()) {
            bytes.writeBytes(ByteBufUtil.getBytes(buffer));
            buffer.release();
        }
        return bytes.toByteArray();
    }

    private static final class HeadersRead extends Http2FrameAdapter {
        private final DefaultHttp2FrameReader reader = new DefaultHttp2FrameReader();
        // the reader allocates a header block's buffer from its context
        private final EmbeddedChannel readerChannel = new EmbeddedChannel(new ChannelInboundHandlerAdapter());
        private final ByteBuf unread = Unpooled.buffer();
        private final List<Http2Headers> blocks = new ArrayList<>();
        private final List<Short> weights = new ArrayList<>();

        void from(byte[] written) {
            unread.writeBytes(written);
            ByteBuf preface = Http2CodecUtil.connectionPrefaceBuf();
            if (unread.readableBytes() >= preface.readableBytes() && ByteBufUtil.equals(unread, unread.readerIndex(), preface, 0, preface.readableBytes())) {
                unread.skipBytes(preface.readableBytes());
            }
            preface.release();
            try {
                reader.readFrame(readerChannel.pipeline().firstContext(), unread, this);
            } catch (Http2Exception unreadable) {
                throw new AssertionError(unreadable);
            }
        }

        @Override
        public void onHeadersRead(ChannelHandlerContext ctx, int streamId, Http2Headers headers, int padding, boolean endOfStream) {
            blocks.add(headers);
        }

        @Override
        public void onHeadersRead(ChannelHandlerContext ctx, int streamId, Http2Headers headers, int streamDependency, short weight, boolean exclusive, int padding, boolean endOfStream) {
            blocks.add(headers);
            weights.add(weight);
        }

        void close() {
            reader.close();
            unread.release();
            readerChannel.finishAndReleaseAll();
        }
    }
}
