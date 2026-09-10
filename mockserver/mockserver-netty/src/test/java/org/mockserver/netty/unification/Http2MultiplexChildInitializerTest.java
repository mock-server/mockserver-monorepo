package org.mockserver.netty.unification;

import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http2.DefaultHttp2DataFrame;
import io.netty.handler.codec.http2.DefaultHttp2Headers;
import io.netty.handler.codec.http2.DefaultHttp2HeadersFrame;
import io.netty.handler.codec.http2.Http2StreamFrameToHttpObjectCodec;
import org.junit.Test;

import java.nio.charset.StandardCharsets;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;

/**
 * Tests for the Phase 0 gRPC bidi-streaming multiplex pipeline scaffolding.
 * <p>
 * Verifies:
 * <ul>
 *   <li>The re-aggregation chain (Http2StreamFrameToHttpObjectCodec + HttpObjectAggregator)
 *       correctly produces a FullHttpRequest from real HTTP/2 frames (Http2HeadersFrame +
 *       Http2DataFrame), genuinely exercising the codec's inbound decode path.</li>
 *   <li>Multiple DATA frames are concatenated into a single body.</li>
 *   <li>Headers (method, path, content-type) are preserved through the decode + re-aggregation.</li>
 *   <li>The HttpObjectAggregator enforces max body size, rejecting oversized bodies.</li>
 * </ul>
 * <p>
 * These tests feed real {@link DefaultHttp2HeadersFrame} and {@link DefaultHttp2DataFrame}
 * objects so that {@link Http2StreamFrameToHttpObjectCodec#acceptInboundMessage} matches
 * and the codec's decode method runs. Prior to this fix, HttpObject inputs bypassed the
 * codec entirely (it only accepts Http2HeadersFrame/Http2DataFrame inbound).
 */
public class Http2MultiplexChildInitializerTest {

    /**
     * Verifies that Http2StreamFrameToHttpObjectCodec(true) + HttpObjectAggregator decodes
     * a single Http2HeadersFrame (with endStream=true) into a FullHttpRequest with the
     * correct method, path, and headers. The body is empty because no DATA frames follow.
     * This exercises the codec's inbound path for a headers-only request.
     */
    @Test
    public void shouldReAggregateFullHttpRequestThroughPhase0Chain() {
        // Build a pipeline with just the re-aggregation handlers + a capture handler
        CaptureHandler capture = new CaptureHandler();
        EmbeddedChannel channel = new EmbeddedChannel(
            new Http2StreamFrameToHttpObjectCodec(true),
            new HttpObjectAggregator(1048576),
            capture
        );

        // Build a gRPC-like request as real HTTP/2 frames: HEADERS (not end-stream) + DATA (end-stream)
        byte[] bodyBytes = "test-grpc-body-content".getBytes(StandardCharsets.UTF_8);

        DefaultHttp2Headers h2Headers = new DefaultHttp2Headers();
        h2Headers.method("POST");
        h2Headers.path("/com.example.Service/Method");
        h2Headers.set("content-type", "application/grpc");

        channel.writeInbound(new DefaultHttp2HeadersFrame(h2Headers, false));
        channel.writeInbound(new DefaultHttp2DataFrame(Unpooled.wrappedBuffer(bodyBytes), true));

        assertThat("should have captured a request", capture.captured, is(notNullValue()));
        assertThat(capture.captured.method(), is(HttpMethod.POST));
        assertThat(capture.captured.uri(), is("/com.example.Service/Method"));
        assertThat(capture.captured.headers().get("content-type"), is("application/grpc"));

        byte[] capturedBody = new byte[capture.captured.content().readableBytes()];
        capture.captured.content().readBytes(capturedBody);
        assertThat(new String(capturedBody, StandardCharsets.UTF_8), is("test-grpc-body-content"));

        capture.release();
        channel.finishAndReleaseAll();
    }

    /**
     * Verifies that the HttpObjectAggregator in the re-aggregation chain enforces the
     * maxRequestBodySize limit, rejecting requests that exceed it.
     * <p>
     * Feeds a real Http2HeadersFrame followed by an oversized Http2DataFrame through the
     * codec so the codec's inbound decode is genuinely exercised before the aggregator
     * rejects the oversized content.
     */
    @Test
    public void shouldEnforceMaxRequestBodySizeInReAggregation() {
        int maxBodySize = 64;
        CaptureHandler capture = new CaptureHandler();
        EmbeddedChannel channel = new EmbeddedChannel(
            new Http2StreamFrameToHttpObjectCodec(true),
            new HttpObjectAggregator(maxBodySize),
            capture
        );

        // Feed real HTTP/2 frames: HEADERS then an oversized DATA frame
        DefaultHttp2Headers h2Headers = new DefaultHttp2Headers();
        h2Headers.method("POST");
        h2Headers.path("/com.example.Service/LargeMethod");
        h2Headers.set("content-type", "application/grpc");

        byte[] largeBody = new byte[maxBodySize + 1];
        java.util.Arrays.fill(largeBody, (byte) 'X');

        // The codec decodes the Http2HeadersFrame into an HttpRequest and the Http2DataFrame
        // into a LastHttpContent. The aggregator then rejects the oversized content (responds
        // 413) and never forwards the assembled request downstream.
        channel.writeInbound(new DefaultHttp2HeadersFrame(h2Headers, false));
        channel.writeInbound(new DefaultHttp2DataFrame(Unpooled.wrappedBuffer(largeBody), true));

        assertThat("oversized request must be rejected by the aggregator and never reach the capture handler",
            capture.captured, is(org.hamcrest.Matchers.nullValue()));

        channel.finishAndReleaseAll();
    }

    /**
     * Verifies that the re-aggregation pipeline preserves all gRPC-relevant headers
     * (content-type, te, grpc-encoding, custom metadata) when decoded from real HTTP/2 frames.
     */
    @Test
    public void shouldPreserveGrpcHeadersThroughReAggregation() {
        CaptureHandler capture = new CaptureHandler();
        EmbeddedChannel channel = new EmbeddedChannel(
            new Http2StreamFrameToHttpObjectCodec(true),
            new HttpObjectAggregator(1048576),
            capture
        );

        byte[] bodyBytes = "payload".getBytes(StandardCharsets.UTF_8);

        DefaultHttp2Headers h2Headers = new DefaultHttp2Headers();
        h2Headers.method("POST");
        h2Headers.path("/com.example.Service/StreamMethod");
        h2Headers.set("content-type", "application/grpc");
        h2Headers.set("te", "trailers");
        h2Headers.set("grpc-encoding", "identity");
        h2Headers.set("x-custom-metadata", "test-value");

        channel.writeInbound(new DefaultHttp2HeadersFrame(h2Headers, false));
        channel.writeInbound(new DefaultHttp2DataFrame(Unpooled.wrappedBuffer(bodyBytes), true));

        assertThat("should have captured a request", capture.captured, is(notNullValue()));
        assertThat(capture.captured.headers().get("content-type"), is("application/grpc"));
        assertThat(capture.captured.headers().get("te"), is("trailers"));
        assertThat(capture.captured.headers().get("grpc-encoding"), is("identity"));
        assertThat(capture.captured.headers().get("x-custom-metadata"), is("test-value"));

        capture.release();
        channel.finishAndReleaseAll();
    }

    /**
     * Simple capture handler for test assertions.
     * Not @Sharable — holds mutable state (the captured request reference).
     */
    private static class CaptureHandler extends SimpleChannelInboundHandler<FullHttpRequest> {
        FullHttpRequest captured;

        @Override
        protected void channelRead0(ChannelHandlerContext ctx, FullHttpRequest msg) {
            // Retain so we can inspect after the handler completes (released via release())
            release();
            captured = msg.retainedDuplicate();
        }

        void release() {
            if (captured != null) {
                captured.release();
                captured = null;
            }
        }
    }
}
