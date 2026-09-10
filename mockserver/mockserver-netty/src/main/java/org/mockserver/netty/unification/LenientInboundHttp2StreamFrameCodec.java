package org.mockserver.netty.unification;

import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.HttpMessage;
import io.netty.handler.codec.http.HttpObject;
import io.netty.handler.codec.http.LastHttpContent;
import io.netty.handler.codec.http2.Http2StreamFrameToHttpObjectCodec;
import io.netty.handler.codec.http2.HttpConversionUtil;

import java.util.List;

/**
 * A {@link Http2StreamFrameToHttpObjectCodec} that is lenient on the INBOUND (request) side but
 * strict on the OUTBOUND (response) side.
 * <p>
 * Netty's {@link Http2StreamFrameToHttpObjectCodec} carries a single {@code validateHeaders} flag
 * that governs <em>both</em> directions of the HTTP/1-object &harr; HTTP/2-frame conversion. On the
 * gRPC multiplex re-aggregating chain MockServer needs the two directions to differ:
 * <ul>
 *   <li><strong>Inbound (lenient, {@code validateHeaders=false}):</strong> a request header value
 *       with a leading space, an embedded {@code 0x7F} (DEL), or a control character must be accepted
 *       and reach the matchers rather than being rejected with {@code RST_STREAM(PROTOCOL_ERROR)}
 *       before matching. This mirrors the shared-connection path, which sets
 *       {@code InboundHttp2ToHttpAdapterBuilder.validateHttpHeaders(false)}. The base class applies
 *       this flag in {@code decode}/{@code newMessage}/{@code newFullMessage} via
 *       {@link HttpConversionUtil#toHttpRequest}/{@code toFullHttpRequest}.</li>
 *   <li><strong>Outbound (strict):</strong> response header NAMES and trailer NAMES must still be
 *       validated exactly as Netty validates them by default, matching the shared-connection path
 *       whose {@code AbstractHttp2ConnectionHandlerBuilder.isValidateHeaders()} defaults to
 *       {@code true}. Passing {@code false} to the base class also silently relaxed this outbound
 *       name validation, which this subclass restores.</li>
 * </ul>
 * <p>
 * Only header/trailer NAMES are validated outbound — never values. The base class builds outbound
 * headers with {@code HttpConversionUtil.toHttp2Headers(..., validateHeaders)}, which uses the 2-arg
 * {@code DefaultHttp2Headers(validate, arraySizeHint)} constructor: that installs a name validator
 * ({@code HTTP2_NAME_VALIDATOR}) when {@code validate} is true and no value validator either way. (The
 * separate 3-arg {@code DefaultHttp2Headers(validate, validateValues, arraySizeHint)} constructor
 * <em>can</em> install a value validator, but the codec does not use it.) Header names are also
 * lower-cased before validation, so an uppercase name never trips it — only an illegal token character
 * (space, control char, DEL, separator, non-ASCII) does.
 * <p>
 * <strong>How the strict outbound check is re-asserted:</strong> rather than re-implementing the RFC
 * token rule by hand (which would drift from Netty across upgrades), this override performs the exact
 * conversion the base class would have performed with validation on — {@code toHttp2Headers(msg, true)}
 * for a response head and {@code toHttp2Headers(trailingHeaders, true)} for non-empty trailers — and
 * discards the result, letting it throw exactly the {@code Http2Exception} the strict path would throw.
 * The subsequent {@code super.encode} then re-does the conversion with {@code validateHeaders=false};
 * this is one extra header conversion per response head (and per trailer block), a deliberate and
 * acceptable cost to keep the check faithful to Netty. Per-chunk {@link io.netty.handler.codec.http.HttpContent}
 * carries no headers and is not validated.
 */
public class LenientInboundHttp2StreamFrameCodec extends Http2StreamFrameToHttpObjectCodec {

    public LenientInboundHttp2StreamFrameCodec() {
        // isServer=true; validateHeaders=false -> lenient INBOUND request-header conversion
        super(true, false);
    }

    @Override
    protected void encode(ChannelHandlerContext ctx, HttpObject obj, List<Object> out) throws Exception {
        // Re-assert the strict OUTBOUND name validation the false flag disabled, using Netty's own
        // conversion so the rule (and the resulting Http2Exception) stays identical to the base class
        // with validation on. A FullHttpResponse is both an HttpMessage and a LastHttpContent, so each
        // check runs independently: the head is validated once, and its (usually empty) trailers once.
        if (obj instanceof HttpMessage) {
            // response head names
            HttpConversionUtil.toHttp2Headers((HttpMessage) obj, true);
        }
        if (obj instanceof LastHttpContent) {
            LastHttpContent last = (LastHttpContent) obj;
            if (!last.trailingHeaders().isEmpty()) {
                // trailer names (e.g. gRPC grpc-status / grpc-message)
                HttpConversionUtil.toHttp2Headers(last.trailingHeaders(), true);
            }
        }
        super.encode(ctx, obj, out);
    }
}
