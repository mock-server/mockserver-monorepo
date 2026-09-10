package org.mockserver.netty.unification;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.DefaultHttpContent;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.codec.http2.DefaultHttp2Headers;
import io.netty.handler.codec.http2.DefaultHttp2HeadersFrame;
import io.netty.handler.codec.http2.Http2DataFrame;
import io.netty.handler.codec.http2.Http2Exception;
import io.netty.handler.codec.http2.Http2Headers;
import io.netty.handler.codec.http2.Http2HeadersFrame;
import io.netty.handler.codec.http2.Http2StreamFrameToHttpObjectCodec;
import io.netty.util.ReferenceCountUtil;
import org.junit.Test;

import java.nio.charset.StandardCharsets;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.Assert.fail;

/**
 * Unit coverage for {@link LenientInboundHttp2StreamFrameCodec} — the multiplex re-aggregating chain's
 * stream-frame codec, which must be lenient on the INBOUND (request) side yet strict on the OUTBOUND
 * (response/trailer) side.
 * <p>
 * These assertions pin the split precisely with an {@link EmbeddedChannel} wrapping the real codec:
 * <ul>
 *   <li>{@link #shouldAcceptInboundRequestHeaderValueWithControlCharacter} — an inbound request header
 *       value containing {@code 0x7F} (DEL, legal in an HTTP/2 field value but rejected by the HTTP/1
 *       value rules) is accepted and reaches the decoded request, not rejected. This is the leniency
 *       the {@code (true, false)} base flag provides and which the outbound override must not disturb.</li>
 *   <li>{@link #shouldRejectOutboundResponseWithIllegalHeaderName} — an outbound response whose header
 *       name contains an illegal token character (a space) is rejected with an {@link Http2Exception},
 *       exactly as Netty's strict path would reject it. This is the outbound-strict behaviour restored
 *       by the {@code encode} override; with a plain {@code Http2StreamFrameToHttpObjectCodec(true,
 *       false)} it is NOT rejected and this assertion goes red.</li>
 *   <li>{@link #shouldEmitOutboundResponseWithValidHeaderName} — a well-formed response passes straight
 *       through to a HEADERS frame.</li>
 *   <li>{@link #shouldNotValidatePerChunkHttpContent} — a plain (non-last) {@code HttpContent} carries
 *       no headers and flows through to a DATA frame untouched (the override does no per-chunk work).</li>
 * </ul>
 * The illegal name uses a space rather than an uppercase letter deliberately: Netty lower-cases header
 * names before validating, so an uppercase name would never trip the check — only an illegal token
 * character does.
 */
public class LenientInboundHttp2StreamFrameCodecTest {

    // Legal in an HTTP/2 field value (HTTP/2 forbids only NUL/CR/LF), rejected by the HTTP/1 value rules.
    // Written as the \u007F escape, NOT as a raw DEL byte: DEL is non-printing, so a literal byte here
    // renders as "abc\u007Fdef" in grep/diff/review output and reads as though the test exercises nothing.
    private static final String MALFORMED_HEADER_VALUE = "abc\u007Fdef";


    /**
     * The whole point of {@link #MALFORMED_HEADER_VALUE} is the DEL (0x7F) it carries: Netty's
     * {@code HttpHeaderValidationUtil.validateValidHeaderValue} rejects 0x7F, so without it this
     * suite would pass under BOTH strict and lenient inbound settings and prove nothing.
     * <p>
     * DEL is non-printing, so a raw byte here reads as "abcdef" in grep/diff/review output - that
     * exact misreading once produced a false review finding that the test was a no-op. This asserts
     * the character mechanically so the escape form cannot silently degrade to something inert.
     */
    @Test
    public void malformedHeaderValueMustActuallyCarryTheDelCharacter() {
        assertThat("MALFORMED_HEADER_VALUE must contain DEL (0x7F) or this suite proves nothing",
            (int) MALFORMED_HEADER_VALUE.charAt(3), is(0x7F));
    }

    @Test
    public void shouldAcceptInboundRequestHeaderValueWithControlCharacter() {
        // given - the lenient inbound codec, exactly as installed on the multiplex re-aggregating chain
        EmbeddedChannel channel = new EmbeddedChannel(new LenientInboundHttp2StreamFrameCodec());

        // DefaultHttp2Headers validates only names, never values, so the malformed value goes in verbatim
        Http2Headers headers = new DefaultHttp2Headers()
            .method("GET")
            .scheme("http")
            .authority("localhost")
            .path("/inbound_control_char");
        headers.set("x-malformed-value", MALFORMED_HEADER_VALUE);

        // when - the request headers frame arrives inbound (end of stream)
        assertThat(channel.writeInbound(new DefaultHttp2HeadersFrame(headers, true)), is(true));

        // then - it decoded to a request (was NOT rejected in the HTTP/1 conversion) and the malformed
        // value survived onto the decoded request, matching the connection-adapter path's leniency
        Object inbound = channel.readInbound();
        assertThat(inbound, instanceOf(FullHttpRequest.class));
        FullHttpRequest request = (FullHttpRequest) inbound;
        assertThat(request.headers().get("x-malformed-value"), is(MALFORMED_HEADER_VALUE));

        ReferenceCountUtil.release(inbound);
        channel.finishAndReleaseAll();
    }

    @Test
    public void shouldRejectOutboundResponseWithIllegalHeaderName() {
        // given - a response whose header NAME contains an illegal token character (a space). The
        // response is built with validateHeaders=false so the illegal name survives into the model and
        // reaches the codec, isolating the codec's own outbound validation.
        EmbeddedChannel channel = new EmbeddedChannel(new LenientInboundHttp2StreamFrameCodec());
        FullHttpResponse response = new DefaultFullHttpResponse(
            HttpVersion.HTTP_1_1, HttpResponseStatus.OK, Unpooled.EMPTY_BUFFER, false);
        response.headers().add("bad name", "value");

        // when / then - the codec re-asserts strict outbound name validation and throws, exactly as the
        // strict base codec (validateHeaders=true) would. Without the encode override this write would
        // succeed and emit a HEADERS frame carrying the illegal name (the red state of this test).
        try {
            channel.writeOutbound(response);
            fail("expected the illegal outbound header name to be rejected");
        } catch (Exception thrown) {
            // Exception (not Throwable) so a missing rejection surfaces as the fail() AssertionError
            // above rather than being swallowed here.
            assertThat("the failure must be Netty's own HTTP/2 protocol error, matching the strict path",
                rootHttp2Exception(thrown), notNullValue());
        } finally {
            channel.finishAndReleaseAll();
        }
    }

    @Test
    public void shouldEmitOutboundResponseWithValidHeaderName() {
        // given - a well-formed response
        EmbeddedChannel channel = new EmbeddedChannel(new LenientInboundHttp2StreamFrameCodec());
        ByteBuf content = Unpooled.copiedBuffer("ok", StandardCharsets.UTF_8);
        FullHttpResponse response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK, content);
        response.headers().add("x-valid-name", "value");

        // when
        assertThat(channel.writeOutbound(response), is(true));

        // then - the head is emitted as a HEADERS frame carrying the status and the valid header
        Object head = channel.readOutbound();
        assertThat(head, instanceOf(Http2HeadersFrame.class));
        Http2Headers outHeaders = ((Http2HeadersFrame) head).headers();
        assertThat(outHeaders.status().toString(), is("200"));
        assertThat(outHeaders.get("x-valid-name").toString(), is("value"));
        ReferenceCountUtil.release(head);

        // and the body follows as a DATA frame
        Object body = channel.readOutbound();
        assertThat(body, instanceOf(Http2DataFrame.class));
        ReferenceCountUtil.release(body);

        channel.finishAndReleaseAll();
    }

    @Test
    public void shouldNotValidatePerChunkHttpContent() {
        // given - a plain (non-last) content chunk, which carries no headers at all
        EmbeddedChannel channel = new EmbeddedChannel(new LenientInboundHttp2StreamFrameCodec());
        ByteBuf chunk = Unpooled.copiedBuffer("data: one\n\n", StandardCharsets.UTF_8);

        // when - it is written outbound
        assertThat(channel.writeOutbound(new DefaultHttpContent(chunk)), is(true));

        // then - it flows straight through to a non-terminal DATA frame; the override does no per-chunk
        // header work (there are no headers to validate on an HttpContent)
        Object out = channel.readOutbound();
        assertThat(out, instanceOf(Http2DataFrame.class));
        assertThat(((Http2DataFrame) out).isEndStream(), is(false));
        ReferenceCountUtil.release(out);

        channel.finishAndReleaseAll();
    }

    /**
     * Sanity anchor: the strict base codec ({@code validateHeaders=true}) rejects the same illegal
     * outbound name, confirming the override reproduces the base class's strict behaviour rather than
     * inventing its own rule.
     */
    @Test
    public void strictBaseCodecRejectsTheSameIllegalNameProvingParity() {
        EmbeddedChannel channel = new EmbeddedChannel(new Http2StreamFrameToHttpObjectCodec(true, true));
        FullHttpResponse response = new DefaultFullHttpResponse(
            HttpVersion.HTTP_1_1, HttpResponseStatus.OK, Unpooled.EMPTY_BUFFER, false);
        response.headers().add("bad name", "value");
        try {
            channel.writeOutbound(response);
            fail("expected the strict base codec to reject the illegal outbound header name");
        } catch (Exception thrown) {
            assertThat(rootHttp2Exception(thrown), notNullValue());
        } finally {
            channel.finishAndReleaseAll();
        }
    }

    private static Http2Exception rootHttp2Exception(Throwable thrown) {
        for (Throwable t = thrown; t != null; t = t.getCause()) {
            if (t instanceof Http2Exception) {
                return (Http2Exception) t;
            }
            if (t.getCause() == t) {
                break;
            }
        }
        return null;
    }
}
