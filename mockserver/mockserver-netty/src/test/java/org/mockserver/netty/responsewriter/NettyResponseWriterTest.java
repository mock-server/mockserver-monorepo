package org.mockserver.netty.responsewriter;

import io.netty.channel.*;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpServerCodec;
import io.netty.handler.codec.http.LastHttpContent;
import io.netty.handler.codec.http2.Http2Frame;
import io.netty.util.ReferenceCountUtil;
import io.netty.util.ReferenceCounted;
import io.netty.util.concurrent.GenericFutureListener;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.model.ConnectionOptions;
import org.mockserver.model.Delay;
import org.mockserver.model.HttpRequest;
import org.mockserver.model.HttpResponse;
import org.mockserver.responsewriter.HttpExchangeEndedEvent;
import org.mockserver.scheduler.Scheduler;

import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.hasItemInArray;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.nullValue;
import static org.hamcrest.core.Is.is;
import static org.mockito.Mockito.*;
import static org.mockito.MockitoAnnotations.openMocks;
import static org.mockserver.configuration.Configuration.configuration;
import org.mockserver.configuration.ConfigurationProperties;
import static org.mockserver.configuration.ConfigurationProperties.enableCORSForAllResponses;
import static org.mockserver.model.ConnectionOptions.connectionOptions;
import static org.mockserver.model.HttpRequest.request;
import static org.mockserver.model.HttpResponse.notFoundResponse;
import static org.mockserver.model.HttpResponse.response;

@SuppressWarnings("unchecked")
public class NettyResponseWriterTest {

    @Mock
    private ChannelHandlerContext mockChannelHandlerContext;
    @Mock
    private ChannelFuture mockChannelFuture;
    @Mock
    private Channel mockChannel;
    @Mock
    private Scheduler scheduler;

    private ArgumentCaptor<GenericFutureListener<ChannelFuture>> genericFutureListenerArgumentCaptor;

    @Before
    public void setupTestFixture() {
        openMocks(this);

        genericFutureListenerArgumentCaptor = ArgumentCaptor.forClass(GenericFutureListener.class);
        when(mockChannelFuture.addListener(genericFutureListenerArgumentCaptor.capture())).thenReturn(null);
        when(mockChannelFuture.channel()).thenReturn(mockChannel);
        when(mockChannelHandlerContext.writeAndFlush(any())).thenReturn(mockChannelFuture);
        when(mockChannel.close()).thenReturn(mockChannelFuture);
        when(mockChannel.disconnect()).thenReturn(mockChannelFuture);
        when(mockChannelFuture.isSuccess()).thenReturn(true);
    }

    @Test
    public void shouldWriteBasicResponse() {
        // given
        HttpRequest request = request("some_request");
        HttpResponse response = response("some_response");

        // when
        new NettyResponseWriter(configuration(), new MockServerLogger(), mockChannelHandlerContext, scheduler).writeResponse(request.clone(), response.clone(), false);

        // then
        verify(mockChannelHandlerContext).writeAndFlush(
            response("some_response")
                .withHeader("connection", "close")
        );
        verify(mockChannelFuture).addListener(any(GenericFutureListener.class));
    }

    @Test
    public void shouldWriteNullResponse() {
        // given
        HttpRequest request = request("some_request");

        // when
        new NettyResponseWriter(configuration(), new MockServerLogger(), mockChannelHandlerContext, scheduler).writeResponse(request.clone(), null, false);

        // then
        verify(mockChannelHandlerContext).writeAndFlush(
            notFoundResponse()
                .withHeader("connection", "close")
        );
        verify(mockChannelFuture).addListener(any(GenericFutureListener.class));
    }

    @Test
    public void shouldWriteAddCORSHeaders() {
        boolean enableCORSForAllResponses = enableCORSForAllResponses();
        try {
            // given
            enableCORSForAllResponses(true);
            HttpRequest request = request("some_request");
            HttpResponse response = response("some_response");

            // when
            new NettyResponseWriter(configuration(), new MockServerLogger(), mockChannelHandlerContext, scheduler).writeResponse(request.clone(), response.clone(), false);

            // then
            verify(mockChannelHandlerContext).writeAndFlush(
                response
                    .withHeader("access-control-allow-origin", "*")
                    .withHeader("access-control-allow-methods", "CONNECT, DELETE, GET, HEAD, OPTIONS, POST, PUT, PATCH, TRACE")
                    .withHeader("access-control-allow-headers", "Allow, Content-Encoding, Content-Length, Content-Type, ETag, Expires, Last-Modified, Location, Server, Vary, Authorization")
                    .withHeader("access-control-expose-headers", "Allow, Content-Encoding, Content-Length, Content-Type, ETag, Expires, Last-Modified, Location, Server, Vary, Authorization")
                    .withHeader("access-control-max-age", "0")
                    .withHeader("access-control-allow-credentials", "false")
                    .withHeader("connection", "close")
            );
            verify(mockChannelFuture).addListener(any(GenericFutureListener.class));
        } finally {
            enableCORSForAllResponses(enableCORSForAllResponses);
        }
    }

    @Test
    public void shouldAddCORSHeadersForControlPlaneResponsesWithoutAnyCORSConfig() {
        // Control-plane / dashboard responses (apiResponse == true) always carry CORS headers so
        // the dashboard works cross-origin, independent of enableCORSForAPI / enableCORSForAllResponses.
        boolean enableCORSForAllResponses = enableCORSForAllResponses();
        boolean enableCORSForAPI = ConfigurationProperties.enableCORSForAPI();
        try {
            // given — both CORS switches off (defaults)
            enableCORSForAllResponses(false);
            ConfigurationProperties.enableCORSForAPI(false);

            // when — written as a control-plane (api) response
            new NettyResponseWriter(configuration(), new MockServerLogger(), mockChannelHandlerContext, scheduler)
                .writeResponse(request("some_request"), response("some_response"), true);

            // then — CORS headers are present anyway (assert on the CORS header specifically rather
            // than the whole response, which also carries volatile version / deprecated headers)
            ArgumentCaptor<HttpResponse> responseCaptor = ArgumentCaptor.forClass(HttpResponse.class);
            verify(mockChannelHandlerContext).writeAndFlush(responseCaptor.capture());
            assertThat(responseCaptor.getValue().getFirstHeader("access-control-allow-origin"), is("*"));
            assertThat(responseCaptor.getValue().getFirstHeader("access-control-allow-methods"),
                is("CONNECT, DELETE, GET, HEAD, OPTIONS, POST, PUT, PATCH, TRACE"));
        } finally {
            enableCORSForAllResponses(enableCORSForAllResponses);
            ConfigurationProperties.enableCORSForAPI(enableCORSForAPI);
        }
    }

    @Test
    public void shouldNotAddCORSHeadersForMockResponsesWithoutCORSConfig() {
        // Mock/proxy responses (apiResponse == false) stay governed by enableCORSForAllResponses only,
        // so they get no CORS headers by default — control-plane CORS must not leak to mocked APIs.
        boolean enableCORSForAllResponses = enableCORSForAllResponses();
        try {
            enableCORSForAllResponses(false);

            new NettyResponseWriter(configuration(), new MockServerLogger(), mockChannelHandlerContext, scheduler)
                .writeResponse(request("some_request"), response("some_response"), false);

            ArgumentCaptor<HttpResponse> responseCaptor = ArgumentCaptor.forClass(HttpResponse.class);
            verify(mockChannelHandlerContext).writeAndFlush(responseCaptor.capture());
            assertThat(responseCaptor.getValue().getFirstHeader("access-control-allow-origin"), is(""));
        } finally {
            enableCORSForAllResponses(enableCORSForAllResponses);
        }
    }

    @Test
    public void shouldKeepAlive() {
        // given
        HttpRequest request = request("some_request")
            .withKeepAlive(true);
        HttpResponse response = response("some_response");

        // when
        new NettyResponseWriter(configuration(), new MockServerLogger(), mockChannelHandlerContext, scheduler).writeResponse(request.clone(), response.clone(), false);

        // then
        verify(mockChannelHandlerContext).writeAndFlush(
            response("some_response")
                .withHeader("connection", "keep-alive")
        );
        verify(mockChannelFuture, times(0)).addListener(any(GenericFutureListener.class));
    }

    @Test
    public void shouldOverrideKeepAlive() {
        // given
        EmbeddedChannel channel = new EmbeddedChannel();
        when(mockChannelHandlerContext.channel()).thenReturn(channel);
        HttpRequest request = request("some_request");
        HttpResponse response = response("some_response")
            .withConnectionOptions(
                connectionOptions()
                    .withKeepAliveOverride(true)
            );

        // when
        new NettyResponseWriter(configuration(), new MockServerLogger(), mockChannelHandlerContext, scheduler).writeResponse(request.clone(), response.clone(), false);

        // then
        verify(mockChannelHandlerContext).writeAndFlush(
            response("some_response")
                .withHeader("connection", "keep-alive")
                .withConnectionOptions(
                    connectionOptions()
                        .withKeepAliveOverride(true)
                )
        );
        verify(mockChannelFuture).addListener(any(GenericFutureListener.class));
        // the socket closes after a head that does not say so, so the pipelining gate is told first
        org.hamcrest.MatcherAssert.assertThat(org.mockserver.socket.ConnectionClosing.isMarked(channel), org.hamcrest.Matchers.is(true));
        channel.finishAndReleaseAll();
    }

    @Test
    public void shouldSuppressConnectionHeader() {
        // given
        EmbeddedChannel channel = new EmbeddedChannel();
        when(mockChannelHandlerContext.channel()).thenReturn(channel);
        HttpRequest request = request("some_request");
        HttpResponse response = response("some_response")
            .withConnectionOptions(
                connectionOptions()
                    .withSuppressConnectionHeader(true)
            );

        // when
        new NettyResponseWriter(configuration(), new MockServerLogger(), mockChannelHandlerContext, scheduler).writeResponse(request.clone(), response.clone(), false);

        // then
        verify(mockChannelHandlerContext).writeAndFlush(
            response("some_response")
                .withConnectionOptions(
                    connectionOptions()
                        .withSuppressConnectionHeader(true)
                )
        );
        verify(mockChannelFuture).addListener(any(GenericFutureListener.class));
        // the socket closes after a head that does not say so, so the pipelining gate is told first
        org.hamcrest.MatcherAssert.assertThat(org.mockserver.socket.ConnectionClosing.isMarked(channel), org.hamcrest.Matchers.is(true));
        channel.finishAndReleaseAll();
    }

    @Test
    public void shouldCloseSocket() throws Exception {
        // given
        HttpRequest request = request("some_request");
        HttpResponse response = response("some_response")
            .withConnectionOptions(
                connectionOptions()
                    .withCloseSocket(true)
            );

        // when
        new NettyResponseWriter(configuration(), new MockServerLogger(), mockChannelHandlerContext, scheduler).writeResponse(request.clone(), response.clone(), false);
        genericFutureListenerArgumentCaptor.getValue().operationComplete(mockChannelFuture);
        genericFutureListenerArgumentCaptor.getValue().operationComplete(mockChannelFuture);

        // then
        verify(mockChannel).disconnect();
        verify(mockChannel).close();
        verify(mockChannelHandlerContext).writeAndFlush(
            response("some_response")
                .withHeader("connection", "close")
                .withConnectionOptions(
                    connectionOptions()
                        .withCloseSocket(true)
                )
        );
    }

    @Test
    public void shouldEmitTrailersOnStreamingResponseOverHttp1() throws Exception {
        // given -- a streaming-body response that also carries trailers (INC-02): the streaming
        // path used to flush LastHttpContent.EMPTY_LAST_CONTENT, silently dropping trailers.
        EmbeddedChannel channel = new EmbeddedChannel(new ChannelOutboundHandlerAdapter());
        try {
            org.mockserver.model.StreamingBody streamingBody = new org.mockserver.model.StreamingBody(1024);
            streamingBody.setEventLoop(channel.eventLoop());

            org.mockserver.model.HttpResponse response = response()
                .withStatusCode(200)
                .withStreamingBody(streamingBody)
                .withTrailer("x-checksum", "abc123")
                .withTrailer("x-signature", "deadbeef");

            // subscribe via the writer (runs on the channel event loop)
            channel.eventLoop().execute(() ->
                new NettyResponseWriter(configuration(), new MockServerLogger(), channel.pipeline().firstContext(), scheduler)
                    .sendResponse(request("/stream"), response)
            );
            channel.runPendingTasks();

            // feed one chunk and complete the stream, draining the event loop after each step
            channel.eventLoop().execute(() -> streamingBody.addChunk(io.netty.buffer.Unpooled.copiedBuffer("chunk-1", java.nio.charset.StandardCharsets.UTF_8)));
            channel.runPendingTasks();
            channel.eventLoop().execute(streamingBody::complete);
            channel.runPendingTasks();

            // then -- the head announces the trailers and the terminating LastHttpContent carries them
            io.netty.handler.codec.http.HttpResponse head = channel.readOutbound();
            assertThat(head.headers().get(HttpHeaderNames.TRANSFER_ENCODING), is("chunked"));
            String trailerHeader = head.headers().get(HttpHeaderNames.TRAILER);
            assertThat(trailerHeader.toLowerCase().contains("x-checksum"), is(true));
            assertThat(trailerHeader.toLowerCase().contains("x-signature"), is(true));

            // drain the remaining outbound messages; the last LastHttpContent must carry the trailers
            LastHttpContent lastContent = null;
            Object outbound;
            while ((outbound = channel.readOutbound()) != null) {
                if (outbound instanceof LastHttpContent) {
                    lastContent = (LastHttpContent) outbound;
                }
                ReferenceCountUtil.release(outbound);
            }
            assertThat("a LastHttpContent must be written at stream completion", lastContent != null, is(true));
            assertThat(lastContent.trailingHeaders().get("x-checksum"), is("abc123"));
            assertThat(lastContent.trailingHeaders().get("x-signature"), is("deadbeef"));
            if (head instanceof ReferenceCounted) {
                ((ReferenceCounted) head).release();
            }
        } finally {
            channel.finishAndReleaseAll();
        }
    }

    @Test
    public void shouldNoteTheClientWhenItsChannelClosesBeforeTheStreamedResponseCompletes() {
        // given
        EmbeddedChannel channel = new EmbeddedChannel(new ChannelOutboundHandlerAdapter());
        try {
            org.mockserver.model.StreamingBody streamingBody = new org.mockserver.model.StreamingBody(1024);
            streamingBody.setEventLoop(channel.eventLoop());
            AtomicInteger upstreamClosed = new AtomicInteger();
            streamingBody.setUpstreamCloser(upstreamClosed::incrementAndGet);
            channel.eventLoop().execute(() ->
                new NettyResponseWriter(configuration(), new MockServerLogger(), channel.pipeline().firstContext(), scheduler)
                    .sendResponse(request("/stream"), response().withStatusCode(200).withStreamingBody(streamingBody))
            );
            channel.runPendingTasks();
            assertThat(streamingBody.isClosedByClient(), is(false));

            // when -- the client goes away mid-response
            channel.close();
            channel.runPendingTasks();

            // then
            assertThat(streamingBody.isClosedByClient(), is(true));
            assertThat(upstreamClosed.get(), is(1));
        } finally {
            channel.finishAndReleaseAll();
        }
    }

    @Test
    public void shouldNotNoteTheClientWhenTheStreamedResponseCompletedBeforeItsChannelCloses() {
        // given
        EmbeddedChannel channel = new EmbeddedChannel(new ChannelOutboundHandlerAdapter());
        try {
            org.mockserver.model.StreamingBody streamingBody = new org.mockserver.model.StreamingBody(1024);
            streamingBody.setEventLoop(channel.eventLoop());
            channel.eventLoop().execute(() ->
                new NettyResponseWriter(configuration(), new MockServerLogger(), channel.pipeline().firstContext(), scheduler)
                    .sendResponse(request("/stream"), response().withStatusCode(200).withStreamingBody(streamingBody))
            );
            channel.runPendingTasks();
            channel.eventLoop().execute(streamingBody::complete);
            channel.runPendingTasks();

            // when
            channel.close();
            channel.runPendingTasks();

            // then
            assertThat(streamingBody.isClosedByClient(), is(false));
        } finally {
            channel.finishAndReleaseAll();
        }
    }

    /**
     * A streaming response over HTTP/2 delivers nothing unless its head carries the stream id.
     * <p>
     * This path is subtler than the SSE one: {@code ResponseWriter.writeResponse} HAS already copied
     * the request's stream id onto the model response, but {@code writeStreamingResponse} builds the
     * Netty head by copying only {@code getHeaderMultimap()} — and the stream id lives in a separate
     * protocol-guarded field, so it was dropped on the floor. The non-streaming sibling in the same
     * class writes the model object and so has always been correct. Same defect class as #2419.
     */
    @Test
    public void shouldSendStreamingResponseHeadDownTheRequestHttp2Stream() throws Exception {
        // given - a streaming-body response to a request that arrived on HTTP/2 stream 5
        EmbeddedChannel channel = new EmbeddedChannel(new ChannelOutboundHandlerAdapter());
        try {
            org.mockserver.model.StreamingBody streamingBody = new org.mockserver.model.StreamingBody(1024);
            streamingBody.setEventLoop(channel.eventLoop());

            org.mockserver.model.HttpResponse response = response()
                .withStatusCode(200)
                .withStreamingBody(streamingBody);

            // when - written through writeResponse so the stream id is copied from the request, as
            // it is in production (sendResponse alone would not exercise that copy)
            channel.eventLoop().execute(() ->
                new NettyResponseWriter(configuration(), new MockServerLogger(), channel.pipeline().firstContext(), scheduler)
                    .writeResponse(request("/stream").withStreamId(5), response, false)
            );
            channel.runPendingTasks();
            channel.eventLoop().execute(streamingBody::complete);
            channel.runPendingTasks();

            // then - the head is routed back onto stream 5, so the client actually receives the stream
            io.netty.handler.codec.http.HttpResponse head = channel.readOutbound();
            assertThat("a response head must be written", head != null, is(true));
            assertThat(head.headers().getInt(org.mockserver.mappers.Http2StreamIds.STREAM_ID_HEADER), is(5));

            Object outbound;
            while ((outbound = channel.readOutbound()) != null) {
                ReferenceCountUtil.release(outbound);
            }
            if (head instanceof ReferenceCounted) {
                ((ReferenceCounted) head).release();
            }
        } finally {
            channel.finishAndReleaseAll();
        }
    }

    @Test
    public void shouldNotAddStreamIdHeaderToStreamingResponseOnHttp1() throws Exception {
        // given - an HTTP/1.1 request has no stream id
        EmbeddedChannel channel = new EmbeddedChannel(new ChannelOutboundHandlerAdapter());
        try {
            org.mockserver.model.StreamingBody streamingBody = new org.mockserver.model.StreamingBody(1024);
            streamingBody.setEventLoop(channel.eventLoop());

            org.mockserver.model.HttpResponse response = response()
                .withStatusCode(200)
                .withStreamingBody(streamingBody);

            channel.eventLoop().execute(() ->
                new NettyResponseWriter(configuration(), new MockServerLogger(), channel.pipeline().firstContext(), scheduler)
                    .writeResponse(request("/stream"), response, false)
            );
            channel.runPendingTasks();
            channel.eventLoop().execute(streamingBody::complete);
            channel.runPendingTasks();

            // then - no HTTP/2 extension header leaks onto an HTTP/1.1 response
            io.netty.handler.codec.http.HttpResponse head = channel.readOutbound();
            assertThat("a response head must be written", head != null, is(true));
            assertThat(head.headers().get(org.mockserver.mappers.Http2StreamIds.STREAM_ID_HEADER), nullValue());

            Object outbound;
            while ((outbound = channel.readOutbound()) != null) {
                ReferenceCountUtil.release(outbound);
            }
            if (head instanceof ReferenceCounted) {
                ((ReferenceCounted) head).release();
            }
        } finally {
            channel.finishAndReleaseAll();
        }
    }

    @Test
    public void shouldDelaySocketClose() throws Exception {
        // given
        HttpRequest request = request("some_request");
        HttpResponse response = response("some_response")
            .withConnectionOptions(
                connectionOptions()
                    .withCloseSocket(true)
                    .withCloseSocketDelay(new Delay(SECONDS, 3))
            );

        // when
        new NettyResponseWriter(configuration(), new MockServerLogger(), mockChannelHandlerContext, scheduler).writeResponse(request.clone(), response.clone(), false);
        genericFutureListenerArgumentCaptor.getValue().operationComplete(mockChannelFuture);

        // then
        verify(scheduler).schedule(isA(Runnable.class), eq(false), eq(new Delay(SECONDS, 3)));
        verify(mockChannelHandlerContext).writeAndFlush(
            response("some_response")
                .withHeader("connection", "close")
                .withConnectionOptions(
                    connectionOptions()
                        .withCloseSocket(true)
                        .withCloseSocketDelay(new Delay(SECONDS, 3))
                )
        );
    }

    @Test
    public void shouldEndTheExchangeAfterAFinalInformationalResponse() {
        assertThat(exchangeEndedEventsAfterWriting(102), is(1));
        assertThat(exchangeEndedEventsAfterWriting(103), is(1));
    }

    /**
     * Over HTTP/2 a final 1xx is followed by a reset of its stream; over HTTP/1.1, which has no streams, the status
     * line and headers are all that is written.
     */
    @Test
    public void shouldWriteOnlyTheInformationalResponseOverHttp1() {
        for (ConnectionOptions options : Arrays.asList(null, connectionOptions().withChunkSize(1).withChunkDelay(new Delay(TimeUnit.MILLISECONDS, 0)))) {
            EmbeddedChannel channel = new EmbeddedChannel(new HttpServerCodec(), new ChannelOutboundHandlerAdapter());
            try {
                new NettyResponseWriter(configuration(), new MockServerLogger(), channel.pipeline().lastContext(), scheduler)
                    .writeResponse(request("/informational").withKeepAlive(true), response().withStatusCode(102).withConnectionOptions(options), false);
                channel.runPendingTasks();

                // the response object as written, which MockServer's own codec would encode: only it, and no reset
                List<Object> written = new ArrayList<>();
                for (Object out = channel.readOutbound(); out != null; out = channel.readOutbound()) {
                    written.add(out);
                }
                assertThat(written, not(empty()));
                assertThat(written, everyItem(not(instanceOf(Http2Frame.class))));
                if (options == null) {
                    assertThat(written, contains(instanceOf(HttpResponse.class)));
                }
                written.forEach(ReferenceCountUtil::release);
                assertThat(channel.isOpen(), is(true));
            } finally {
                channel.finishAndReleaseAll();
            }
        }
    }

    @Test
    public void shouldNotEndTheExchangeSeparatelyForAResponseTheCodecHandlersSeeEnd() {
        assertThat(exchangeEndedEventsAfterWriting(200), is(0));
        assertThat("a 101 makes the connection a WebSocket instead", exchangeEndedEventsAfterWriting(101), is(0));
    }

    private int exchangeEndedEventsAfterWriting(int statusCode) {
        AtomicInteger events = new AtomicInteger();
        EmbeddedChannel channel = new EmbeddedChannel(
            new HttpServerCodec(),
            new ChannelInboundHandlerAdapter() {
                @Override
                public void userEventTriggered(ChannelHandlerContext ctx, Object evt) {
                    if (evt == HttpExchangeEndedEvent.INSTANCE) {
                        events.incrementAndGet();
                    }
                }
            },
            new ChannelOutboundHandlerAdapter()
        );
        try {
            new NettyResponseWriter(configuration(), new MockServerLogger(), channel.pipeline().lastContext(), scheduler)
                .writeResponse(request("/informational").withKeepAlive(true), response().withStatusCode(statusCode), false);
            channel.runPendingTasks();
            return events.get();
        } finally {
            channel.finishAndReleaseAll();
        }
    }
}
