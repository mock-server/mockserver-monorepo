package org.mockserver.netty.proxy.relay;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.CombinedChannelDuplexHandler;
import io.netty.handler.codec.http.HttpClientCodec;
import io.netty.handler.codec.http.HttpDecoderConfig;
import io.netty.handler.codec.http.HttpMessage;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpRequestEncoder;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.handler.codec.http.HttpResponseDecoder;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpStatusClass;

import java.util.ArrayDeque;
import java.util.List;
import java.util.Queue;

/**
 * The relay's codec on its end of an HTTP/1.1 loopback. It decodes as Netty's {@link HttpClientCodec} does, with no
 * limit on a response's initial line or headers: each response for the method of the request it answers, the oldest
 * still unanswered (no body after {@code HEAD}; after a {@code 200} to {@code CONNECT} the rest of the connection is
 * passed on as bytes). Unlike Netty's, it can be told that MockServer answered that request with nothing it decodes
 * (raw bytes, or no response), which Netty's would pair with the next response instead.
 */
final class LoopbackHttpClientCodec extends CombinedChannelDuplexHandler<HttpResponseDecoder, HttpRequestEncoder> {

    // added by the encoder, taken by the decoder and by responseNotDecoded, all on the loopback's event loop
    private final Queue<HttpMethod> methods = new ArrayDeque<>();
    private boolean tunnelled;

    LoopbackHttpClientCodec(int maxChunkSize) {
        init(new Decoder(new HttpDecoderConfig()
            .setMaxInitialLineLength(Integer.MAX_VALUE)
            .setMaxHeaderSize(Integer.MAX_VALUE)
            .setMaxChunkSize(maxChunkSize)), new Encoder());
    }

    /**
     * The oldest request still unanswered was answered with nothing this codec decodes. Call it on the loopback's event
     * loop, after every byte written before that answer has been given to this codec, and before any written after it.
     */
    void responseNotDecoded() {
        methods.poll();
    }

    private final class Encoder extends HttpRequestEncoder {

        @Override
        protected void encode(ChannelHandlerContext ctx, Object msg, List<Object> out) throws Exception {
            if (tunnelled && msg instanceof ByteBuf) {
                // the client's bytes once the connection stopped carrying HTTP, which the encoder would refuse;
                // HttpObjectEncoder's encode owns msg, so it is handed on, not retained
                out.add(msg);
                return;
            }
            if (msg instanceof HttpRequest) {
                methods.offer(((HttpRequest) msg).method());
            }
            super.encode(ctx, msg, out);
        }
    }

    private final class Decoder extends HttpResponseDecoder {

        Decoder(HttpDecoderConfig config) {
            super(config);
        }

        @Override
        protected void decode(ChannelHandlerContext ctx, ByteBuf buffer, List<Object> out) throws Exception {
            if (tunnelled) {
                int readable = actualReadableBytes();
                if (readable > 0) {
                    out.add(buffer.readBytes(readable));
                }
            } else {
                super.decode(ctx, buffer, out);
            }
        }

        @Override
        protected boolean isContentAlwaysEmpty(HttpMessage msg) {
            HttpResponseStatus status = ((HttpResponse) msg).status();
            if (TunnelUpgrade.isUpgrade((HttpResponse) msg)) {
                // a WebSocket upgrade: what follows is the new protocol's bytes, relayed as they are
                tunnelled = true;
                methods.clear();
                return true;
            }
            if (status.codeClass() == HttpStatusClass.INFORMATIONAL) {
                // an interim response answers no request of its own
                return super.isContentAlwaysEmpty(msg);
            }
            HttpMethod method = methods.poll();
            if (HttpMethod.HEAD.equals(method)) {
                return true;
            }
            if (HttpMethod.CONNECT.equals(method) && status.code() == HttpResponseStatus.OK.code()) {
                tunnelled = true;
                methods.clear();
                return true;
            }
            return super.isContentAlwaysEmpty(msg);
        }
    }
}
