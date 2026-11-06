package org.mockserver.netty.http3;

import io.netty.buffer.ByteBufAllocator;
import io.netty.buffer.CompositeByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http3.Http3Exception;
import io.netty.handler.codec.http3.Http3ErrorCode;
import io.netty.handler.codec.http3.DefaultHttp3DataFrame;
import io.netty.handler.codec.http3.DefaultHttp3HeadersFrame;
import io.netty.handler.codec.quic.QuicStreamResetException;
import io.netty.util.AttributeKey;
import io.netty.util.DefaultAttributeMap;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockserver.configuration.Configuration;
import org.mockserver.grpc.GrpcDerivedHeaders;
import org.mockserver.grpc.GrpcProtoDescriptorStore;
import org.mockserver.grpc.GrpcStatusMapper;
import org.mockserver.log.model.LogEntry;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.metrics.Metrics;
import org.mockserver.mock.HttpState;
import org.mockserver.mock.action.http.HttpActionHandler;
import org.mockserver.model.HttpRequest;
import org.mockserver.netty.connection.ServerClosedChannels;
import org.slf4j.event.Level;

import io.netty.channel.ChannelFuture;
import io.netty.handler.codec.http3.Http3DataFrame;
import io.netty.handler.codec.http3.Http3HeadersFrame;
import org.mockserver.model.HttpResponse;
import org.mockserver.responsewriter.ControlPlaneFailureResponse;
import org.mockserver.responsewriter.ResponseWriter;

import java.net.InetSocketAddress;
import java.nio.channels.ClosedChannelException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.startsWith;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;
import static org.hamcrest.Matchers.sameInstance;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.mockserver.configuration.Configuration.configuration;

/**
 * Unit tests for {@link Http3MockServerHandler}, specifically testing ByteBuf
 * lifecycle management (leak prevention, no double-release).
 * <p>
 * These tests do NOT require the native QUIC transport -- they exercise the
 * handler's buffer management by directly invoking the protected methods
 * (accessible from the same package).
 */
public class Http3MockServerHandlerTest {

    private static final Configuration CONFIGURATION = configuration();
    private static final MockServerLogger LOGGER = new MockServerLogger(Http3MockServerHandlerTest.class);

    private static final String BIDI_SERVICE = "com.example.grpc.GreetingService";
    private static final String BIDI_METHOD = "Chat";
    private static final String BIDI_PATH = "/" + BIDI_SERVICE + "/" + BIDI_METHOD;

    @Test
    public void shouldRecogniseOnlyNettysRefusalOfAHeaderSectionOverTheLimit() {
        assertThat(Http3MockServerHandler.isHeaderSectionTooLarge(new Http3Exception(Http3ErrorCode.H3_EXCESSIVE_LOAD, "Header size exceeded max allowed size (8192)")), is(true));
        assertThat(Http3MockServerHandler.isHeaderSectionTooLarge(new Http3Exception(Http3ErrorCode.H3_EXCESSIVE_LOAD, "Received an invalid frame len 9000 for frame of type 1.")), is(true));

        assertThat("an over-long frame of another type", Http3MockServerHandler.isHeaderSectionTooLarge(new Http3Exception(Http3ErrorCode.H3_EXCESSIVE_LOAD, "Received an invalid frame len 9000 for frame of type 33.")), is(false));
        assertThat("a frame length over an int", Http3MockServerHandler.isHeaderSectionTooLarge(new Http3Exception(Http3ErrorCode.H3_EXCESSIVE_LOAD, "Received an invalid frame len.")), is(false));
        assertThat("no message", Http3MockServerHandler.isHeaderSectionTooLarge(new Http3Exception(Http3ErrorCode.H3_EXCESSIVE_LOAD, null)), is(false));
        assertThat("another error code", Http3MockServerHandler.isHeaderSectionTooLarge(new Http3Exception(Http3ErrorCode.H3_MESSAGE_ERROR, "Header size exceeded max allowed size (8192)")), is(false));
        assertThat("another error code", Http3MockServerHandler.isHeaderSectionTooLarge(new Http3Exception(Http3ErrorCode.H3_FRAME_ERROR, "Received an invalid frame len 9000 for frame of type 1.")), is(false));
        assertThat("not an HTTP/3 error", Http3MockServerHandler.isHeaderSectionTooLarge(new IllegalStateException("Header size exceeded max allowed size (8192)")), is(false));
    }

    @Test
    public void shouldLogAStreamItsClientResetOrClosedAtDebugAndAnyOtherExceptionAsAWarningAndCloseTheStream() {
        List<LogEntry> logged = new ArrayList<>();
        Level[] logLevel = {Level.DEBUG};
        MockServerLogger capturingLogger = new MockServerLogger(Http3MockServerHandlerTest.class) {
            @Override
            public boolean isEnabledForInstance(Level level) {
                return isEnabled(level, logLevel[0]);
            }

            @Override
            public void logEvent(LogEntry logEntry) {
                if (isEnabledForInstance(logEntry.getLogLevel())) {
                    logged.add(logEntry);
                }
            }
        };
        for (Throwable cause : new Throwable[]{new QuicStreamResetException("STREAM_RESET", Http3ErrorCode.H3_REQUEST_CANCELLED.code()), new ClosedChannelException()}) {
            logged.clear();
            logLevel[0] = Level.DEBUG;
            ChannelHandlerContext ctx = streamContext();

            new Http3MockServerHandler(CONFIGURATION, capturingLogger, mock(HttpState.class), mock(HttpActionHandler.class), new Metrics(CONFIGURATION)).exceptionCaught(ctx, cause);

            assertThat(cause.toString(), logged, hasSize(1));
            assertThat(logged.get(0).getLogLevel(), is(Level.DEBUG));
            assertThat(logged.get(0).getMessageFormat(), is("request stream of HTTP/3 connection from:{}closed or reset by its client:{}"));
            assertThat(logged.get(0).getThrowable(), is(nullValue()));
            verify(ctx).close();

            logged.clear();
            logLevel[0] = Level.INFO;
            new Http3MockServerHandler(CONFIGURATION, capturingLogger, mock(HttpState.class), mock(HttpActionHandler.class), new Metrics(CONFIGURATION)).exceptionCaught(streamContext(), cause);
            assertThat("nothing at the default level", logged, empty());
        }

        logged.clear();
        logLevel[0] = Level.DEBUG;
        IllegalStateException other = new IllegalStateException("handler failed");
        ChannelHandlerContext ctx = streamContext();
        new Http3MockServerHandler(CONFIGURATION, capturingLogger, mock(HttpState.class), mock(HttpActionHandler.class), new Metrics(CONFIGURATION)).exceptionCaught(ctx, other);
        assertThat(logged, hasSize(1));
        assertThat(logged.get(0).getLogLevel(), is(Level.WARN));
        assertThat(logged.get(0).getThrowable(), sameInstance(other));
        verify(ctx).close();
    }

    @Test
    public void shouldMarkAStreamItClosesForAnExceptionAsClosedByTheServerButNotOneItsClientResetOrClosed() {
        ChannelHandlerContext failed = streamContext();
        new Http3MockServerHandler(CONFIGURATION, mock(MockServerLogger.class), mock(HttpState.class), mock(HttpActionHandler.class), new Metrics(CONFIGURATION)).exceptionCaught(failed, new IllegalStateException("handler failed"));
        assertThat(ServerClosedChannels.isClosedByServer(failed.channel()), is(true));

        for (Throwable cause : new Throwable[]{new QuicStreamResetException("STREAM_RESET", Http3ErrorCode.H3_REQUEST_CANCELLED.code()), new ClosedChannelException()}) {
            ChannelHandlerContext ctx = streamContext();

            new Http3MockServerHandler(CONFIGURATION, mock(MockServerLogger.class), mock(HttpState.class), mock(HttpActionHandler.class), new Metrics(CONFIGURATION)).exceptionCaught(ctx, cause);

            assertThat(cause.toString(), ServerClosedChannels.isClosedByServer(ctx.channel()), is(false));
            verify(ctx).close();
        }
    }

    private static ChannelHandlerContext streamContext() {
        Channel stream = mock(Channel.class);
        when(stream.remoteAddress()).thenReturn(new InetSocketAddress("127.0.0.1", 50443));
        // real attributes, which ServerClosedChannels sets
        DefaultAttributeMap attributes = new DefaultAttributeMap();
        when(stream.attr(any())).thenAnswer(invocation -> attributes.attr(invocation.<AttributeKey<Object>>getArgument(0)));
        ChannelHandlerContext ctx = mock(ChannelHandlerContext.class);
        when(ctx.executor()).thenReturn(io.netty.util.concurrent.ImmediateEventExecutor.INSTANCE);
        when(ctx.channel()).thenReturn(stream);
        return ctx;
    }

    @Test
    public void shouldAnswerAnUnexpectedControlPlaneFailureWithAServerErrorAndAGenericMessage() throws Exception {
        assertThat(controlPlaneStatusWhenHandlingThrows(new NullPointerException("internal detail of the fault")), is("500"));
    }

    @Test
    public void shouldAnswerAControlPlaneClientErrorWithABadRequest() throws Exception {
        assertThat(controlPlaneStatusWhenHandlingThrows(new IllegalArgumentException("incorrect expectation json format")), is("400"));
    }

    private String controlPlaneStatusWhenHandlingThrows(RuntimeException fault) throws Exception {
        HttpState httpState = mock(HttpState.class);
        when(httpState.handle(any(), any(), anyBoolean())).thenThrow(fault);
        HttpActionHandler httpActionHandler = mock(HttpActionHandler.class);
        Http3MockServerHandler handler = new Http3MockServerHandler(
            CONFIGURATION, LOGGER, httpState, httpActionHandler, new Metrics(CONFIGURATION)
        );
        ChannelHandlerContext ctx = mockChannelHandlerContextWithWrite();

        DefaultHttp3HeadersFrame headersFrame = new DefaultHttp3HeadersFrame();
        headersFrame.headers().method("PUT");
        headersFrame.headers().path("/mockserver/expectation");
        headersFrame.headers().scheme("https");
        handler.channelRead(ctx, headersFrame);
        handler.channelInputClosed(ctx);

        verify(httpActionHandler, never()).processAction(any(), any(), any(), any(), anyBoolean(), anyBoolean());
        ArgumentCaptor<Object> written = ArgumentCaptor.forClass(Object.class);
        verify(ctx, atLeast(0)).write(written.capture());
        verify(ctx, atLeast(0)).writeAndFlush(written.capture());
        Http3HeadersFrame responseHeaders = (Http3HeadersFrame) written.getAllValues().stream()
            .filter(frame -> frame instanceof Http3HeadersFrame)
            .findFirst()
            .orElseThrow(() -> new AssertionError("no response header section written"));
        written.getAllValues().forEach(io.netty.util.ReferenceCountUtil::release);
        return String.valueOf(responseHeaders.headers().status());
    }

    @Test
    public void shouldAnswerADataPlaneFailureWithAServerErrorAndAGenericMessage() throws Exception {
        List<Object> written = writtenWhenProcessingThrows("text/plain", false);

        assertThat(statusesOf(written), contains("500"));
        String body = bodyOf(written);
        assertThat(body, startsWith(ControlPlaneFailureResponse.UNEXPECTED_FAILURE_MESSAGE));
        assertThat(body, not(containsString("internal detail")));
        Http3HeadersFrame responseHeaders = (Http3HeadersFrame) written.get(0);
        assertThat(String.valueOf(responseHeaders.headers().get("content-type")), is("text/plain; charset=utf-8"));
        assertThat("a mock response gets CORS headers only when enableCORSForAllResponses is on",
            responseHeaders.headers().contains("access-control-allow-origin") || responseHeaders.headers().contains("access-control-allow-credentials"), is(false));
    }

    @Test
    public void shouldNotAnswerADataPlaneFailureAgainAfterItsResponseWasStarted() throws Exception {
        List<Object> written = writtenWhenProcessingThrows("text/plain", true);

        assertThat(statusesOf(written), contains("200"));
    }

    @Test
    public void shouldAnswerAGrpcDataPlaneFailureWithTheInternalStatus() throws Exception {
        List<Object> written = writtenWhenProcessingThrows(GrpcStatusMapper.GRPC_CONTENT_TYPE, false);

        assertThat(statusesOf(written), contains("200"));
        Http3HeadersFrame trailersOnly = (Http3HeadersFrame) written.get(0);
        assertThat(String.valueOf(trailersOnly.headers().get(GrpcStatusMapper.GRPC_STATUS_HEADER)),
            is(String.valueOf(GrpcStatusMapper.GrpcStatusCode.INTERNAL.getCode())));
        assertThat(String.valueOf(trailersOnly.headers().get(GrpcStatusMapper.GRPC_MESSAGE_HEADER)), not(containsString("internal")));
    }

    @Test
    public void shouldNotAnswerAGrpcDataPlaneFailureAgainAfterItsResponseWasStarted() throws Exception {
        List<Object> written = writtenWhenProcessingThrows(GrpcStatusMapper.GRPC_CONTENT_TYPE, true);

        assertThat(written, hasSize(1));
        assertThat(String.valueOf(((Http3HeadersFrame) written.get(0)).headers().get(GrpcStatusMapper.GRPC_STATUS_HEADER)), is("0"));
    }

    /**
     * Sends a request whose data-plane processing throws, after first answering it when {@code answerFirst}, and
     * returns the frames written to the stream.
     */
    private List<Object> writtenWhenProcessingThrows(String contentType, boolean answerFirst) throws Exception {
        HttpState httpState = mock(HttpState.class);
        when(httpState.handle(any(), any(), anyBoolean())).thenReturn(false);
        HttpActionHandler httpActionHandler = mock(HttpActionHandler.class);
        doAnswer(invocation -> {
            if (answerFirst) {
                ResponseWriter responseWriter = invocation.getArgument(1);
                responseWriter.writeResponse(invocation.getArgument(0), HttpResponse.response().withHeader(GrpcStatusMapper.GRPC_STATUS_HEADER, "0"), false);
            }
            throw new NullPointerException("internal detail of the fault");
        }).when(httpActionHandler).processAction(any(), any(), any(), any(), anyBoolean(), anyBoolean());
        Http3MockServerHandler handler = new Http3MockServerHandler(
            CONFIGURATION, LOGGER, httpState, httpActionHandler, new Metrics(CONFIGURATION)
        );
        ChannelHandlerContext ctx = mockChannelHandlerContextWithWrite();
        Channel stream = new io.netty.channel.embedded.EmbeddedChannel();
        when(ctx.channel()).thenReturn(stream);

        DefaultHttp3HeadersFrame headersFrame = new DefaultHttp3HeadersFrame();
        headersFrame.headers().method("POST");
        headersFrame.headers().path("/some/data-plane/path");
        headersFrame.headers().scheme("https");
        headersFrame.headers().add("content-type", contentType);
        headersFrame.headers().add("origin", "https://elsewhere.example");
        handler.channelRead(ctx, headersFrame);
        handler.channelInputClosed(ctx);

        verify(httpActionHandler).processAction(any(), any(), any(), any(), anyBoolean(), anyBoolean());
        ArgumentCaptor<Object> written = ArgumentCaptor.forClass(Object.class);
        verify(ctx, atLeast(0)).write(written.capture());
        verify(ctx, atLeast(0)).writeAndFlush(written.capture());
        stream.close();
        return written.getAllValues();
    }

    @Test
    public void shouldAnswerAnMcpPostWithServiceUnavailableWhenTheMcpExecutorRefusesIt() throws Exception {
        io.netty.util.concurrent.EventExecutorGroup executor = mock(io.netty.util.concurrent.EventExecutorGroup.class);
        doThrow(new java.util.concurrent.RejectedExecutionException("full")).when(executor).execute(any(Runnable.class));
        org.mockserver.netty.mcp.McpRequestProcessor processor = mcpProcessorOn(executor);

        List<Object> written = writtenForMcpPost(processor);

        assertThat(statusesOf(written), contains("503"));
        assertThat(bodyOf(written), containsString("Server is busy, try again later"));
        verify(processor, never()).handlePost(any(), any(), any());
    }

    @Test
    public void shouldAnswerAnMcpPostWithAnInternalErrorWhenProcessingItFails() throws Exception {
        io.netty.util.concurrent.EventExecutorGroup executor = mock(io.netty.util.concurrent.EventExecutorGroup.class);
        doAnswer(invocation -> {
            invocation.<Runnable>getArgument(0).run();
            return null;
        }).when(executor).execute(any(Runnable.class));
        org.mockserver.netty.mcp.McpRequestProcessor processor = mcpProcessorOn(executor);
        when(processor.handlePost(any(), any(), any())).thenThrow(new IllegalStateException("internal detail of the fault"));

        List<Object> written = writtenForMcpPost(processor);

        assertThat(statusesOf(written), contains("500"));
        String body = bodyOf(written);
        assertThat(body, containsString("Internal error"));
        assertThat(body, not(containsString("internal detail")));
    }

    private static org.mockserver.netty.mcp.McpRequestProcessor mcpProcessorOn(io.netty.util.concurrent.EventExecutorGroup executor) {
        org.mockserver.netty.mcp.McpSessionManager sessionManager = mock(org.mockserver.netty.mcp.McpSessionManager.class);
        when(sessionManager.getExecutor()).thenReturn(executor);
        org.mockserver.netty.mcp.McpRequestProcessor processor = mock(org.mockserver.netty.mcp.McpRequestProcessor.class);
        when(processor.getSessionManager()).thenReturn(sessionManager);
        return processor;
    }

    /**
     * Sends an MCP POST and returns the frames written to the stream, all on the stream's (immediate) event loop.
     */
    private List<Object> writtenForMcpPost(org.mockserver.netty.mcp.McpRequestProcessor processor) throws Exception {
        HttpState httpState = mock(HttpState.class);
        when(httpState.controlPlaneAuthenticationSettings()).thenReturn(org.mockserver.configuration.ControlPlaneAuthenticationSettings.of(CONFIGURATION));
        Http3MockServerHandler handler = new Http3MockServerHandler(
            CONFIGURATION, LOGGER, httpState, mock(HttpActionHandler.class), new Metrics(CONFIGURATION), processor
        );
        ChannelHandlerContext ctx = mockChannelHandlerContextWithWrite();
        Channel stream = new io.netty.channel.embedded.EmbeddedChannel();
        when(ctx.channel()).thenReturn(stream);

        DefaultHttp3HeadersFrame headersFrame = new DefaultHttp3HeadersFrame();
        headersFrame.headers().method("POST");
        headersFrame.headers().path("/mockserver/mcp");
        headersFrame.headers().scheme("https");
        headersFrame.headers().add("content-type", "application/json");
        handler.channelRead(ctx, headersFrame);
        handler.channelRead(ctx, new DefaultHttp3DataFrame(Unpooled.copiedBuffer("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"ping\"}", StandardCharsets.UTF_8)));
        handler.channelInputClosed(ctx);

        ArgumentCaptor<Object> written = ArgumentCaptor.forClass(Object.class);
        verify(ctx, atLeast(0)).write(written.capture());
        verify(ctx, atLeast(0)).writeAndFlush(written.capture());
        stream.close();
        return written.getAllValues();
    }

    private static List<String> statusesOf(List<Object> written) {
        List<String> statuses = new ArrayList<>();
        for (Object frame : written) {
            if (frame instanceof Http3HeadersFrame && ((Http3HeadersFrame) frame).headers().status() != null) {
                statuses.add(String.valueOf(((Http3HeadersFrame) frame).headers().status()));
            }
        }
        return statuses;
    }

    private static String bodyOf(List<Object> written) {
        StringBuilder body = new StringBuilder();
        for (Object frame : written) {
            if (frame instanceof Http3DataFrame) {
                body.append(((Http3DataFrame) frame).content().toString(StandardCharsets.UTF_8));
                ((Http3DataFrame) frame).release();
            }
        }
        return body.toString();
    }

    @Test
    public void shouldReleaseBodyAccumulatorOnHandlerRemoved() throws Exception {
        // given: a handler that has received headers and a data frame
        Metrics metrics = new Metrics(CONFIGURATION);
        Http3MockServerHandler handler = new Http3MockServerHandler(
            CONFIGURATION, LOGGER, mock(HttpState.class), mock(HttpActionHandler.class), metrics
        );

        ChannelHandlerContext ctx = mockChannelHandlerContext();

        DefaultHttp3HeadersFrame headersFrame = new DefaultHttp3HeadersFrame();
        headersFrame.headers().method("GET");
        headersFrame.headers().path("/test");
        headersFrame.headers().scheme("https");
        headersFrame.headers().authority("localhost:8443");

        // invoke protected channelRead to initialise the bodyAccumulator
        handler.channelRead(ctx, headersFrame);

        DefaultHttp3DataFrame dataFrame = new DefaultHttp3DataFrame(
            Unpooled.wrappedBuffer("test-body".getBytes(StandardCharsets.UTF_8))
        );
        handler.channelRead(ctx, dataFrame);

        // capture the bodyAccumulator via reflection to verify its refCnt
        java.lang.reflect.Field accField = Http3MockServerHandler.class.getDeclaredField("bodyAccumulator");
        accField.setAccessible(true);
        CompositeByteBuf accumulator = (CompositeByteBuf) accField.get(handler);
        assertThat("bodyAccumulator should be allocated", accumulator.refCnt(), is(1));

        // when: handlerRemoved fires (simulating abrupt disconnect before channelInputClosed)
        handler.handlerRemoved(ctx);

        // then: the body accumulator should be released
        assertThat("bodyAccumulator should be released after handlerRemoved", accumulator.refCnt(), is(0));

        // and: the field should be nulled out (verify via reflection)
        assertThat("bodyAccumulator field should be null after release", accField.get(handler) == null, is(true));
    }

    @Test
    public void shouldNotDoubleReleaseWhenChannelInputClosedThenHandlerRemoved() throws Exception {
        // given: a handler that processes a complete request via channelInputClosed
        Metrics metrics = new Metrics(CONFIGURATION);
        HttpState httpState = mock(HttpState.class);
        when(httpState.handle(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyBoolean()))
            .thenReturn(true); // simulate control-plane handling to avoid needing httpActionHandler

        Http3MockServerHandler handler = new Http3MockServerHandler(
            CONFIGURATION, LOGGER, httpState, mock(HttpActionHandler.class), metrics
        );

        ChannelHandlerContext ctx = mockChannelHandlerContext();

        DefaultHttp3HeadersFrame headersFrame = new DefaultHttp3HeadersFrame();
        headersFrame.headers().method("GET");
        headersFrame.headers().path("/test");
        headersFrame.headers().scheme("https");

        handler.channelRead(ctx, headersFrame);

        java.lang.reflect.Field accField = Http3MockServerHandler.class.getDeclaredField("bodyAccumulator");
        accField.setAccessible(true);
        CompositeByteBuf accumulator = (CompositeByteBuf) accField.get(handler);

        // when: channelInputClosed releases the accumulator
        handler.channelInputClosed(ctx);
        assertThat("bodyAccumulator should be released after channelInputClosed", accumulator.refCnt(), is(0));

        // then: handlerRemoved should not throw (no double-release because field was nulled)
        handler.handlerRemoved(ctx); // should be a no-op since bodyAccumulator is already null
    }

    @Test
    public void shouldReleaseBodyAccumulatorWhenNoHeadersReceivedAndChannelInputClosed() throws Exception {
        // given: a handler that receives no headers but has channelInputClosed called
        // (edge case: stream closes immediately)
        Metrics metrics = new Metrics(CONFIGURATION);
        Http3MockServerHandler handler = new Http3MockServerHandler(
            CONFIGURATION, LOGGER, mock(HttpState.class), mock(HttpActionHandler.class), metrics
        );

        ChannelHandlerContext ctx = mockChannelHandlerContext();

        // when: channelInputClosed fires without prior headers
        handler.channelInputClosed(ctx);

        // then: no exception (bodyAccumulator was never allocated, early-return path handles it)
        handler.handlerRemoved(ctx); // also a no-op
    }

    @Test
    public void shouldReleaseBodyAccumulatorOnEarlyReturnWhenParsedHeadersNull() throws Exception {
        // given: a handler where channelRead(HeadersFrame) was called but parseHeaders returned
        // a valid ParsedHeaders -- this test ensures the finally block runs on the channelInputClosed
        // early-return path when parsedHeaders IS null (only bodyAccumulator was allocated)

        // Simulate the scenario where headers frame was received (so bodyAccumulator is allocated)
        // but parsedHeaders is forcibly set to null (simulating a bizarre edge case)
        Metrics metrics = new Metrics(CONFIGURATION);
        Http3MockServerHandler handler = new Http3MockServerHandler(
            CONFIGURATION, LOGGER, mock(HttpState.class), mock(HttpActionHandler.class), metrics
        );

        ChannelHandlerContext ctx = mockChannelHandlerContext();

        DefaultHttp3HeadersFrame headersFrame = new DefaultHttp3HeadersFrame();
        headersFrame.headers().method("GET");
        headersFrame.headers().path("/test");
        handler.channelRead(ctx, headersFrame);

        // Force parsedHeaders to null via reflection to test the early-return + finally release
        java.lang.reflect.Field parsedField = Http3MockServerHandler.class.getDeclaredField("parsedHeaders");
        parsedField.setAccessible(true);
        parsedField.set(handler, null);

        java.lang.reflect.Field accField = Http3MockServerHandler.class.getDeclaredField("bodyAccumulator");
        accField.setAccessible(true);
        CompositeByteBuf accumulator = (CompositeByteBuf) accField.get(handler);
        assertThat("bodyAccumulator should be allocated", accumulator.refCnt(), is(1));

        // when: channelInputClosed fires with null parsedHeaders (early-return path)
        handler.channelInputClosed(ctx);

        // then: the body accumulator should still be released by the finally block
        assertThat("bodyAccumulator should be released on early-return path", accumulator.refCnt(), is(0));
    }

    @Test
    public void shouldRejectRequestBodyExceedingMaxRequestBodySize() throws Exception {
        // given: a handler with maxRequestBodySize set to 100 bytes
        Configuration config = configuration().maxRequestBodySize(100);
        Metrics metrics = new Metrics(config);
        Http3MockServerHandler handler = new Http3MockServerHandler(
            config, LOGGER, mock(HttpState.class), mock(HttpActionHandler.class), metrics
        );

        ChannelHandlerContext ctx = mockChannelHandlerContextWithWrite();

        DefaultHttp3HeadersFrame headersFrame = new DefaultHttp3HeadersFrame();
        headersFrame.headers().method("POST");
        headersFrame.headers().path("/upload");
        headersFrame.headers().scheme("https");
        handler.channelRead(ctx, headersFrame);

        // when: send a data frame that exceeds the 100-byte limit
        byte[] oversizedPayload = new byte[150];
        java.util.Arrays.fill(oversizedPayload, (byte) 'X');
        DefaultHttp3DataFrame dataFrame = new DefaultHttp3DataFrame(
            Unpooled.wrappedBuffer(oversizedPayload)
        );
        handler.channelRead(ctx, dataFrame);

        // then: the body accumulator should be released (null)
        java.lang.reflect.Field accField = Http3MockServerHandler.class.getDeclaredField("bodyAccumulator");
        accField.setAccessible(true);
        assertThat("bodyAccumulator should be null after body exceeded", accField.get(handler) == null, is(true));

        // and: bodyExceeded flag should be set
        java.lang.reflect.Field exceededField = Http3MockServerHandler.class.getDeclaredField("bodyExceeded");
        exceededField.setAccessible(true);
        assertThat("bodyExceeded should be true", (Boolean) exceededField.get(handler), is(true));

        // and: a 413 response headers frame should have been written
        org.mockito.ArgumentCaptor<Object> captor = org.mockito.ArgumentCaptor.forClass(Object.class);
        verify(ctx).write(captor.capture());
        Object written = captor.getValue();
        assertThat("should write Http3HeadersFrame", written instanceof Http3HeadersFrame, is(true));
        Http3HeadersFrame responseHeaders = (Http3HeadersFrame) written;
        assertThat("status should be 413", responseHeaders.headers().status().toString(), is("413"));

        // cleanup: handlerRemoved should be a no-op (accumulator already released)
        handler.handlerRemoved(ctx);
    }

    @Test
    public void shouldTreatAConfiguredRequestBodyLimitOfZeroOrLessAsOneByte() throws Exception {
        for (int configured : new int[]{0, -1}) {
            Configuration config = configuration().maxRequestBodySize(configured);
            Http3MockServerHandler handler = new Http3MockServerHandler(
                config, LOGGER, mock(HttpState.class), mock(HttpActionHandler.class), new Metrics(config)
            );
            ChannelHandlerContext ctx = mockChannelHandlerContextWithWrite();
            DefaultHttp3HeadersFrame headersFrame = new DefaultHttp3HeadersFrame();
            headersFrame.headers().method("POST");
            headersFrame.headers().path("/upload");
            headersFrame.headers().scheme("https");
            handler.channelRead(ctx, headersFrame);
            java.lang.reflect.Field exceededField = Http3MockServerHandler.class.getDeclaredField("bodyExceeded");
            exceededField.setAccessible(true);

            handler.channelRead(ctx, new DefaultHttp3DataFrame(Unpooled.wrappedBuffer(new byte[1])));
            assertThat("limit " + configured + ": one byte is accepted", (Boolean) exceededField.get(handler), is(false));

            handler.channelRead(ctx, new DefaultHttp3DataFrame(Unpooled.wrappedBuffer(new byte[1])));
            assertThat("limit " + configured + ": a second byte is refused", (Boolean) exceededField.get(handler), is(true));
            ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
            verify(ctx).write(captor.capture());
            assertThat(((Http3HeadersFrame) captor.getValue()).headers().status().toString(), is("413"));
            handler.handlerRemoved(ctx);
        }
    }

    @Test
    public void shouldNotAccumulateAfterBodyExceeded() throws Exception {
        // given: a handler that has already rejected a body as too large
        Configuration config = configuration().maxRequestBodySize(50);
        Metrics metrics = new Metrics(config);
        Http3MockServerHandler handler = new Http3MockServerHandler(
            config, LOGGER, mock(HttpState.class), mock(HttpActionHandler.class), metrics
        );

        ChannelHandlerContext ctx = mockChannelHandlerContextWithWrite();

        DefaultHttp3HeadersFrame headersFrame = new DefaultHttp3HeadersFrame();
        headersFrame.headers().method("POST");
        headersFrame.headers().path("/upload");
        headersFrame.headers().scheme("https");
        handler.channelRead(ctx, headersFrame);

        // send first frame that triggers rejection
        byte[] payload = new byte[60];
        DefaultHttp3DataFrame frame1 = new DefaultHttp3DataFrame(Unpooled.wrappedBuffer(payload));
        handler.channelRead(ctx, frame1);

        // reset mock to count only subsequent interactions
        reset(ctx);
        when(ctx.alloc()).thenReturn(ByteBufAllocator.DEFAULT);

        // when: send a second data frame after rejection
        DefaultHttp3DataFrame frame2 = new DefaultHttp3DataFrame(
            Unpooled.wrappedBuffer("more data".getBytes(StandardCharsets.UTF_8))
        );
        handler.channelRead(ctx, frame2);

        // then: no further writes to ctx (413 was already sent)
        verify(ctx, never()).write(any());
        verify(ctx, never()).writeAndFlush(any());
    }

    @Test
    public void shouldNotProcessRequestAfterBodyExceeded() throws Exception {
        // given: a handler that has rejected a body as too large
        Configuration config = configuration().maxRequestBodySize(50);
        Metrics metrics = new Metrics(config);
        HttpState httpState = mock(HttpState.class);
        Http3MockServerHandler handler = new Http3MockServerHandler(
            config, LOGGER, httpState, mock(HttpActionHandler.class), metrics
        );

        ChannelHandlerContext ctx = mockChannelHandlerContextWithWrite();

        DefaultHttp3HeadersFrame headersFrame = new DefaultHttp3HeadersFrame();
        headersFrame.headers().method("POST");
        headersFrame.headers().path("/upload");
        headersFrame.headers().scheme("https");
        handler.channelRead(ctx, headersFrame);

        // trigger rejection
        DefaultHttp3DataFrame frame = new DefaultHttp3DataFrame(Unpooled.wrappedBuffer(new byte[60]));
        handler.channelRead(ctx, frame);

        // when: channelInputClosed fires (client half-closes)
        handler.channelInputClosed(ctx);

        // then: httpState.handle should NOT have been called (request was already rejected)
        verify(httpState, never()).handle(any(), any(), anyBoolean());
    }

    @Test
    public void shouldAccumulateWithinLimit() throws Exception {
        // given: a handler with maxRequestBodySize set to 200 bytes
        Configuration config = configuration().maxRequestBodySize(200);
        Metrics metrics = new Metrics(config);
        HttpState httpState = mock(HttpState.class);
        when(httpState.handle(any(), any(), anyBoolean())).thenReturn(true);
        Http3MockServerHandler handler = new Http3MockServerHandler(
            config, LOGGER, httpState, mock(HttpActionHandler.class), metrics
        );

        ChannelHandlerContext ctx = mockChannelHandlerContextWithWrite();

        DefaultHttp3HeadersFrame headersFrame = new DefaultHttp3HeadersFrame();
        headersFrame.headers().method("POST");
        headersFrame.headers().path("/small-upload");
        headersFrame.headers().scheme("https");
        handler.channelRead(ctx, headersFrame);

        // when: send data within the limit
        DefaultHttp3DataFrame frame = new DefaultHttp3DataFrame(
            Unpooled.wrappedBuffer(new byte[100])
        );
        handler.channelRead(ctx, frame);

        // then: bodyExceeded should still be false
        java.lang.reflect.Field exceededField = Http3MockServerHandler.class.getDeclaredField("bodyExceeded");
        exceededField.setAccessible(true);
        assertThat("bodyExceeded should be false", (Boolean) exceededField.get(handler), is(false));

        // and: bodyAccumulator should still be allocated
        java.lang.reflect.Field accField = Http3MockServerHandler.class.getDeclaredField("bodyAccumulator");
        accField.setAccessible(true);
        CompositeByteBuf acc = (CompositeByteBuf) accField.get(handler);
        assertThat("bodyAccumulator should still be allocated", acc != null && acc.refCnt() > 0, is(true));
        assertThat("bodyAccumulator should contain the data", acc.readableBytes(), is(100));

        // cleanup
        handler.channelInputClosed(ctx);
        handler.handlerRemoved(ctx);
    }

    /**
     * {@code x-grpc-service} and {@code x-grpc-method} are <strong>server-derived</strong> from the
     * {@code :path}, so a client must not be able to contribute a value. Because
     * {@code HttpRequest.withHeader} appends rather than replaces, the defence is the
     * {@link org.mockserver.grpc.GrpcDerivedHeaders#strip} call in {@code tryBeginGrpcBidi}, and
     * header matching being SUB_SET means a surviving forged value would let an expectation
     * qualified by {@code x-grpc-service: com.example.evil.OtherService} match a stream that
     * actually belongs to {@code com.example.grpc.GreetingService}.
     * <p>
     * This is the HTTP/3 bidi counterpart of the cases in
     * {@code org.mockserver.netty.grpc.GrpcDerivedHeaderSpoofingTest} and
     * {@code GrpcBidiMetadataMatchingTest#shouldNotLetAClientSpoofTheDerivedServiceHeader} — the one
     * strip site those did not reach.
     * <p>
     * <strong>The assertion is on the value list, never {@code getFirstHeader}.</strong> Without the
     * strip the request carries {@code [com.example.evil.OtherService, com.example.grpc.GreetingService]};
     * a {@code getFirstHeader} assertion could read either value and pass while the spoof is still
     * present and still matchable.
     * <p>
     * The request is observed where {@code tryBeginGrpcBidi} first hands it to the matcher — the
     * peek — because that is precisely the request an expectation is matched against. The peek
     * returning no expectation is irrelevant to this test: the strip has already happened by then.
     */
    @Test
    public void shouldNotLetAClientSpoofTheDerivedGrpcHeadersOnAnHttp3BidiStream() throws Exception {
        // given: bidi streaming enabled and the greeting descriptors loaded (Chat is bidi)
        Configuration config = configuration().grpcBidiStreamingEnabled(true);
        GrpcProtoDescriptorStore descriptorStore = new GrpcProtoDescriptorStore(LOGGER);
        descriptorStore.loadDescriptorSetFromPath(
            Paths.get("../mockserver-core/src/test/resources/grpc/greeting.dsc"));

        HttpState httpState = mock(HttpState.class);
        when(httpState.getGrpcDescriptorStore()).thenReturn(descriptorStore);

        Http3MockServerHandler handler = new Http3MockServerHandler(
            config, LOGGER, httpState, mock(HttpActionHandler.class), new Metrics(config)
        );
        ChannelHandlerContext ctx = mockChannelHandlerContext();

        // when: the opening HEADERS frame carries forged copies of the derived headers
        DefaultHttp3HeadersFrame headersFrame = new DefaultHttp3HeadersFrame();
        headersFrame.headers().method("POST");
        headersFrame.headers().path(BIDI_PATH);
        headersFrame.headers().scheme("https");
        headersFrame.headers().add("content-type", GrpcStatusMapper.GRPC_CONTENT_TYPE);
        headersFrame.headers().add(GrpcDerivedHeaders.SERVICE, "com.example.evil.OtherService");
        headersFrame.headers().add(GrpcDerivedHeaders.METHOD, "Evil");

        handler.channelRead(ctx, headersFrame);

        // then: the request offered for matching carries ONLY the path-derived values
        ArgumentCaptor<HttpRequest> captor = ArgumentCaptor.forClass(HttpRequest.class);
        verify(httpState).peekFirstMatchingExpectation(captor.capture());
        HttpRequest matched = captor.getValue();

        assertThat("x-grpc-service must carry ONLY the path-derived service",
            matched.getHeader(GrpcDerivedHeaders.SERVICE), contains(BIDI_SERVICE));
        assertThat("x-grpc-method must carry ONLY the path-derived method",
            matched.getHeader(GrpcDerivedHeaders.METHOD), contains(BIDI_METHOD));

        handler.handlerRemoved(ctx);
    }

    @Test
    public void shouldKeepTheRequestAndBodyWhenTrailersFollowTheBody() throws Exception {
        // given
        HttpState httpState = mock(HttpState.class);
        when(httpState.handle(any(), any(), anyBoolean())).thenReturn(true);
        Http3MockServerHandler handler = new Http3MockServerHandler(
            CONFIGURATION, LOGGER, httpState, mock(HttpActionHandler.class), new Metrics(CONFIGURATION)
        );
        ChannelHandlerContext ctx = mockChannelHandlerContextWithWrite();
        DefaultHttp3HeadersFrame headersFrame = new DefaultHttp3HeadersFrame();
        headersFrame.headers().method("POST");
        headersFrame.headers().path("/with-trailers");
        headersFrame.headers().scheme("https");
        headersFrame.headers().add("content-type", "text/plain");
        handler.channelRead(ctx, headersFrame);
        handler.channelRead(ctx, new DefaultHttp3DataFrame(Unpooled.copiedBuffer("hello", StandardCharsets.UTF_8)));
        java.lang.reflect.Field accField = Http3MockServerHandler.class.getDeclaredField("bodyAccumulator");
        accField.setAccessible(true);
        CompositeByteBuf accumulator = (CompositeByteBuf) accField.get(handler);

        // when: the request ends with a trailers HEADERS frame
        DefaultHttp3HeadersFrame trailers = new DefaultHttp3HeadersFrame();
        trailers.headers().add("x-checksum", "abc");
        handler.channelRead(ctx, trailers);
        handler.channelInputClosed(ctx);

        // then
        ArgumentCaptor<HttpRequest> captor = ArgumentCaptor.forClass(HttpRequest.class);
        verify(httpState).handle(captor.capture(), any(), anyBoolean());
        assertThat(captor.getValue().getPath().getValue(), is("/with-trailers"));
        assertThat(captor.getValue().getBodyAsString(), is("hello"));
        assertThat("the body accumulator is released, not replaced and leaked", accumulator.refCnt(), is(0));
        handler.handlerRemoved(ctx);
    }

    private ChannelHandlerContext mockChannelHandlerContext() {
        ChannelHandlerContext ctx = mock(ChannelHandlerContext.class);
        when(ctx.executor()).thenReturn(io.netty.util.concurrent.ImmediateEventExecutor.INSTANCE);
        when(ctx.alloc()).thenReturn(ByteBufAllocator.DEFAULT);
        return ctx;
    }

    /**
     * Create a mock ChannelHandlerContext that supports write/writeAndFlush
     * (needed for tests that trigger the 413 response path).
     */
    private ChannelHandlerContext mockChannelHandlerContextWithWrite() {
        ChannelHandlerContext ctx = mock(ChannelHandlerContext.class);
        when(ctx.executor()).thenReturn(io.netty.util.concurrent.ImmediateEventExecutor.INSTANCE);
        when(ctx.alloc()).thenReturn(ByteBufAllocator.DEFAULT);
        ChannelFuture future = mock(ChannelFuture.class);
        when(future.addListener(any())).thenReturn(future);
        when(ctx.write(any())).thenReturn(future);
        when(ctx.writeAndFlush(any())).thenReturn(future);
        return ctx;
    }
}
