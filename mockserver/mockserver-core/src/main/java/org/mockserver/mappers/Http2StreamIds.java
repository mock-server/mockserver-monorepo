package org.mockserver.mappers;

import io.netty.handler.codec.http.HttpMessage;
import io.netty.handler.codec.http2.HttpConversionUtil;
import io.netty.util.AsciiString;
import org.mockserver.model.HttpRequest;

/**
 * The single place that stamps the HTTP/2 stream id onto an outbound Netty response head.
 * <p>
 * MockServer's HTTP/2 server pipeline uses {@code HttpToHttp2ConnectionHandler}, which routes an
 * outbound {@code HttpMessage} onto an HTTP/2 stream by reading the {@code x-http2-stream-id}
 * extension header. When that header is absent Netty falls back to
 * {@code connection().local().incrementAndGetNextStreamId()} — a <em>new, server-initiated</em>
 * stream. A response written on a server-initiated stream is never delivered to the client that
 * made the request, so the client simply hangs until it times out. Nothing is logged and nothing
 * fails, which is why this defect class has repeatedly shipped undetected:
 * <ul>
 *     <li>GitHub issue #2419 — server-streaming gRPC delivered zero messages over HTTP/2;</li>
 *     <li>SSE responses, streaming bodies, the metrics endpoint and MCP — all of which built a
 *         Netty response head by hand and wrote it straight to the channel.</li>
 * </ul>
 * <p>
 * Every one of those sites bypassed {@link MockServerHttpResponseToFullHttpResponse}, which was the
 * only code that knew about the header. Consolidating the knowledge here means a new direct-write
 * site has one obvious thing to call.
 * <p>
 * The outbound narrative above is historical: since the HTTP/2 server pipeline moved to
 * {@code Http2FrameCodec}/{@code Http2MultiplexHandler} (every stream is its own child channel), the
 * {@code x-http2-stream-id} header is blacklisted during outbound HTTP/1-to-HTTP/2 conversion, so the
 * response-head stamp is effectively inert. The live use of this class is the <em>inbound</em> capture
 * that records the request's stream id — used for {@code withProtocol(HTTP_2)} matching and by the SSE
 * streaming handler.
 * <p>
 * All methods first <em>remove</em> any existing {@code x-http2-stream-id} header before adding the
 * derived one. That is deliberate: a stream id which leaked in as an ordinary model header (for
 * example from a forwarded or proxied upstream HTTP/2 response) carries a <em>foreign</em> stream
 * id, and writing on it triggers a {@code PROTOCOL_ERROR}/{@code GOAWAY} that hangs the client just
 * as badly as the missing-header case. The stamped value is therefore always the single source of
 * truth, and stamping is idempotent.
 */
public final class Http2StreamIds {

    /**
     * {@code x-http2-stream-id} — the Netty HTTP/1-to-HTTP/2 extension header used to route an
     * outbound response head onto the stream that carried the request.
     */
    public static final AsciiString STREAM_ID_HEADER = HttpConversionUtil.ExtensionHeaderNames.STREAM_ID.text();

    private Http2StreamIds() {
        // static utility
    }

    /**
     * Stamp {@code streamId} onto {@code nettyMessage}, replacing any header already present.
     * A {@code null} {@code streamId} (the HTTP/1.1 case) strips the header and adds nothing, so
     * this is safe to call unconditionally from transport-agnostic code.
     */
    public static void stamp(HttpMessage nettyMessage, Integer streamId) {
        if (nettyMessage == null) {
            return;
        }
        nettyMessage.headers().remove(STREAM_ID_HEADER);
        if (streamId != null) {
            nettyMessage.headers().add(STREAM_ID_HEADER, streamId);
        }
    }

    /**
     * Stamp the stream id carried by a MockServer model {@link HttpRequest} onto the outbound Netty
     * response head, so the response goes back down the stream the request arrived on.
     */
    public static void stampFromRequest(HttpMessage nettyMessage, HttpRequest request) {
        stamp(nettyMessage, request != null ? request.getStreamId() : null);
    }

    /**
     * Stamp the stream id carried by an inbound Netty request onto the outbound Netty response head.
     * For handlers that work on raw Netty objects and never build a MockServer model request — on
     * HTTP/2 the inbound request carries {@code x-http2-stream-id}, added by
     * {@code InboundHttp2ToHttpAdapter}. A no-op on HTTP/1.1, where there is no id to copy.
     */
    public static void stampFromNettyRequest(HttpMessage nettyMessage, HttpMessage inboundNettyRequest) {
        stamp(nettyMessage, streamIdOf(inboundNettyRequest));
    }

    /**
     * The stream id carried by a Netty message, or {@code null} when there is none (HTTP/1.1) or the
     * header value is not a valid integer.
     */
    public static Integer streamIdOf(HttpMessage nettyMessage) {
        if (nettyMessage == null) {
            return null;
        }
        return nettyMessage.headers().getInt(STREAM_ID_HEADER);
    }
}
