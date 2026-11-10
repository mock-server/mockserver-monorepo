package org.mockserver.mock.action.http;

import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.*;
import io.netty.channel.ChannelFutureListener;
import org.mockserver.closurecallback.websocketregistry.WebSocketClientRegistry;
import org.mockserver.codec.StreamAddressedHttpContent;
import org.mockserver.configuration.Configuration;
import org.mockserver.llm.StreamingFormat;
import org.mockserver.llm.codec.BedrockEventStreamEncoder;
import org.mockserver.log.model.LogEntry;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.mappers.Http2StreamIds;
import org.mockserver.mock.breakpoint.BreakpointMatcher;
import org.mockserver.mock.breakpoint.BreakpointMatcherRegistry;
import org.mockserver.mock.breakpoint.BreakpointPhase;
import org.mockserver.mock.breakpoint.PausedStreamFrame;
import org.mockserver.mock.breakpoint.StreamFrameBreakpointRegistry;
import org.mockserver.mock.breakpoint.StreamFrameCallbackDispatcher;
import org.mockserver.mock.breakpoint.StreamFrameDecision;
import org.mockserver.model.Delay;
import org.mockserver.model.HttpSseResponse;
import org.mockserver.model.SseEvent;
import org.mockserver.scheduler.Scheduler;
import org.slf4j.event.Level;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;

import static org.mockserver.exception.ExceptionHandling.causeDescription;
import static org.mockserver.exception.ExceptionHandling.clientGoneException;
import static org.mockserver.log.model.LogEntry.LogMessageType.EXPECTATION_RESPONSE;

public class HttpSseResponseActionHandler {

    private static final byte[] JSON_ARRAY_OPEN = "[".getBytes(StandardCharsets.UTF_8);
    private static final byte[] JSON_ARRAY_CLOSE = "]".getBytes(StandardCharsets.UTF_8);
    private static final byte[] JSON_ARRAY_SEPARATOR = ",\r\n".getBytes(StandardCharsets.UTF_8);
    // CR, LF and CRLF end an SSE line; NEL, LS and PS are split too, in case a client's line reader treats them as breaks
    private static final Pattern COMMENT_LINE_BREAK = Pattern.compile("\\r\\n|[\\r\\n\\u0085\\u2028\\u2029]");

    private final MockServerLogger mockServerLogger;
    private final Scheduler scheduler;
    private final Configuration configuration;
    private final StreamTemplateRenderer templateRenderer;
    private final WebSocketClientRegistry webSocketClientRegistry;

    public HttpSseResponseActionHandler(MockServerLogger mockServerLogger, Scheduler scheduler, Configuration configuration) {
        this(mockServerLogger, scheduler, configuration, null);
    }

    /**
     * @param webSocketClientRegistry the callback WebSocket registry that RESPONSE_STREAM breakpoints
     *                                dispatch held events to; null disables stream-frame breakpoints
     */
    public HttpSseResponseActionHandler(MockServerLogger mockServerLogger, Scheduler scheduler, Configuration configuration, WebSocketClientRegistry webSocketClientRegistry) {
        this.mockServerLogger = mockServerLogger;
        this.scheduler = scheduler;
        this.configuration = configuration;
        this.templateRenderer = new StreamTemplateRenderer(mockServerLogger, configuration);
        this.webSocketClientRegistry = webSocketClientRegistry;
    }

    public void handle(HttpSseResponse httpSseResponse, ChannelHandlerContext ctx, org.mockserver.model.HttpRequest request) {
        handle(httpSseResponse, ctx, request, StreamingFormat.SSE);
    }

    public void handle(HttpSseResponse httpSseResponse, ChannelHandlerContext ctx, org.mockserver.model.HttpRequest request, StreamingFormat format) {
        handle(httpSseResponse, ctx, request, format, () -> {
        });
    }

    /**
     * Writes the response straight to {@code ctx}, running {@code responseEnded} once its last part has been
     * written, unless the connection has closed first.
     */
    public void handle(HttpSseResponse httpSseResponse, ChannelHandlerContext ctx, org.mockserver.model.HttpRequest request, StreamingFormat format, Runnable responseEnded) {
        int statusCode = httpSseResponse.getStatusCode() != null ? httpSseResponse.getStatusCode() : 200;
        DefaultHttpResponse initialResponse = new DefaultHttpResponse(
            HttpVersion.HTTP_1_1,
            HttpResponseStatus.valueOf(statusCode)
        );

        String defaultContentType;
        switch (format) {
            case NDJSON:
                defaultContentType = "application/x-ndjson";
                break;
            case AWS_EVENT_STREAM:
            case AWS_CONVERSE_EVENT_STREAM:
                defaultContentType = BedrockEventStreamEncoder.CONTENT_TYPE;
                break;
            case JSON_ARRAY:
                defaultContentType = "application/json";
                break;
            default:
                defaultContentType = "text/event-stream";
                break;
        }
        initialResponse.headers().set(HttpHeaderNames.CONTENT_TYPE, defaultContentType);
        initialResponse.headers().set(HttpHeaderNames.CACHE_CONTROL, "no-cache");
        // Make the Connection header tell the truth: it must reflect the same close/keep-alive
        // decision the end of the stream will actually honour (see finishStream). Previously this
        // was hard-coded to keep-alive while the stream always closed, so an HTTP/1.1 client that
        // reused the promised-alive connection got a RemoteDisconnected on its next request. On
        // HTTP/2 (streamId != null) the parent connection is never closed for a single stream, and
        // the connection-specific Connection/Transfer-Encoding headers are stripped during the
        // HTTP/1-to-HTTP/2 conversion anyway.
        boolean willCloseHttp1Connection = request.getStreamId() == null && shouldCloseHttp1Connection(request, httpSseResponse);
        initialResponse.headers().set(HttpHeaderNames.CONNECTION, willCloseHttp1Connection ? "close" : "keep-alive");
        initialResponse.headers().set(HttpHeaderNames.TRANSFER_ENCODING, "chunked");

        if (httpSseResponse.getHeaders() != null) {
            httpSseResponse.getHeaders().getEntries().forEach(header ->
                header.getValues().forEach(value ->
                    initialResponse.headers().set(header.getName().getValue(), value.getValue())
                )
            );
        }

        // Send the response head down the HTTP/2 stream the request arrived on. Without this the
        // HTTP/2 codec allocates a fresh server-initiated stream for the head (and every subsequent
        // chunk follows it), so the client receives nothing at all and hangs until it times out.
        // This handler builds a Netty response by hand and so never reaches the response mapper,
        // which is the only other place that stamps the id - see Http2StreamIds.
        Http2StreamIds.stampFromRequest(initialResponse, request);

        ctx.writeAndFlush(initialResponse);
        if (format == StreamingFormat.JSON_ARRAY) {
            ctx.writeAndFlush(chunkContent(JSON_ARRAY_OPEN, request));
        }

        StreamBreakpoint streamBreakpoint = streamBreakpoint(ctx, request);
        AtomicBoolean elementWritten = format == StreamingFormat.JSON_ARRAY ? new AtomicBoolean() : null;
        List<SseEvent> events = httpSseResponse.getEvents();
        if (events != null && !events.isEmpty()) {
            scheduleEvents(events, 0, ctx, httpSseResponse, request, format, elementWritten, streamBreakpoint, responseEnded);
        } else {
            closeJsonArray(ctx, request, format);
            finishStream(ctx, httpSseResponse, request, streamBreakpoint, responseEnded);
        }
    }

    /**
     * Resolves the RESPONSE_STREAM breakpoint for this response, or null when none applies, so the
     * default-off path allocates nothing. If the client connection closes, held events are dropped
     * (and their client told) and the stream's sequence counter is removed.
     */
    private StreamBreakpoint streamBreakpoint(ChannelHandlerContext ctx, org.mockserver.model.HttpRequest request) {
        if (webSocketClientRegistry == null) {
            return null;
        }
        BreakpointMatcher matcher = BreakpointMatcherRegistry.getInstance().findMatch(request, BreakpointPhase.RESPONSE_STREAM);
        if (matcher == null || matcher.getClientId() == null) {
            return null;
        }
        String streamId = (request.getLogCorrelationId() != null
            ? request.getLogCorrelationId() : org.mockserver.uuid.UUIDService.getNonSecureUUID()) + "-stream";
        ChannelFutureListener releaseOnClose = future -> {
            StreamFrameCallbackDispatcher.getInstance().releaseStream(streamId);
            // a client that leaves during an event's delay never reaches finishStream
            StreamFrameBreakpointRegistry.getInstance().evictStream(streamId);
        };
        ctx.channel().closeFuture().addListener(releaseOnClose);
        return new StreamBreakpoint(matcher, streamId, releaseOnClose);
    }

    private void scheduleEvents(List<SseEvent> events, int index, ChannelHandlerContext ctx, HttpSseResponse httpSseResponse, org.mockserver.model.HttpRequest request, StreamingFormat format, AtomicBoolean elementWritten, StreamBreakpoint streamBreakpoint, Runnable responseEnded) {
        if (index >= events.size() || !ctx.channel().isActive()) {
            if (index >= events.size()) {
                closeJsonArray(ctx, request, format);
            }
            finishStream(ctx, httpSseResponse, request, streamBreakpoint, responseEnded);
            return;
        }

        SseEvent event = events.get(index);
        Delay delay = event.getDelay();

        Runnable writeEvent = () -> {
            try {
                if (!ctx.channel().isActive()) {
                    return;
                }
                if (format != StreamingFormat.SSE && isCommentOnly(event)) {
                    // only SSE has comments; elsewhere the event keeps its delay but writes nothing. The next event is
                    // queued, not called, so a long run of undelayed comment-only events cannot grow the stack.
                    ctx.channel().eventLoop().execute(() ->
                        scheduleEvents(events, index + 1, ctx, httpSseResponse, request, format, elementWritten, streamBreakpoint, responseEnded));
                    return;
                }
                byte[] chunkBytes = formatChunkBytes(renderEvent(event, httpSseResponse, request), format);
                CompletableFuture<StreamFrameDecision> decisionFuture = streamBreakpoint != null
                    ? StreamFrameCallbackDispatcher.getInstance().tryWsDispatch(
                        streamBreakpoint.matcher, streamBreakpoint.streamId,
                        StreamFrameBreakpointRegistry.getInstance().nextSequenceNumber(streamBreakpoint.streamId),
                        PausedStreamFrame.Direction.OUTBOUND, BreakpointPhase.RESPONSE_STREAM, chunkBytes,
                        request.getMethod() != null ? request.getMethod().getValue() : null,
                        request.getPath() != null ? request.getPath().getValue() : null,
                        request.getReceivedTimestamp(), configuration, mockServerLogger, webSocketClientRegistry)
                    : null;
                if (decisionFuture == null) {
                    writeChunk(chunkBytes, null, events, index, ctx, httpSseResponse, request, format, elementWritten, streamBreakpoint, responseEnded);
                } else {
                    // the next event is scheduled only once this one is decided and written, so the
                    // end of the stream waits for every held event
                    decisionFuture.thenAccept(decision -> ctx.channel().eventLoop().execute(() ->
                        applyDecision(decision, chunkBytes, events, index, ctx, httpSseResponse, request, format, elementWritten, streamBreakpoint, responseEnded)));
                }
            } catch (Exception e) {
                if (mockServerLogger.isEnabledForInstance(Level.WARN)) {
                    mockServerLogger.logEvent(
                        new LogEntry()
                            .setLogLevel(Level.WARN)
                            .setCorrelationId(request.getLogCorrelationId())
                            .setHttpRequest(request)
                            .setMessageFormat("exception sending streaming chunk {} for request:{}")
                            .setArguments(index + 1, request)
                            .setThrowable(e)
                    );
                }
                finishStream(ctx, httpSseResponse, request, streamBreakpoint, responseEnded);
            }
        };

        if (delay != null) {
            scheduler.schedule(writeEvent, false, delay);
        } else {
            writeEvent.run();
        }
    }

    private void applyDecision(StreamFrameDecision decision, byte[] chunkBytes, List<SseEvent> events, int index, ChannelHandlerContext ctx,
                               HttpSseResponse httpSseResponse, org.mockserver.model.HttpRequest request, StreamingFormat format, AtomicBoolean elementWritten, StreamBreakpoint streamBreakpoint, Runnable responseEnded) {
        if (!ctx.channel().isActive()) {
            finishStream(ctx, httpSseResponse, request, streamBreakpoint, responseEnded);
            return;
        }
        switch (decision.getAction()) {
            case MODIFY:
                writeChunk(decision.getReplacementBody(), null, events, index, ctx, httpSseResponse, request, format, elementWritten, streamBreakpoint, responseEnded);
                break;
            case DROP:
                scheduleEvents(events, index + 1, ctx, httpSseResponse, request, format, elementWritten, streamBreakpoint, responseEnded);
                break;
            case INJECT:
                writeChunk(chunkBytes, decision.getInjectedBody(), events, index, ctx, httpSseResponse, request, format, elementWritten, streamBreakpoint, responseEnded);
                break;
            case CLOSE:
                finishStream(ctx, httpSseResponse, request, streamBreakpoint, responseEnded);
                break;
            case CONTINUE:
            default:
                writeChunk(chunkBytes, null, events, index, ctx, httpSseResponse, request, format, elementWritten, streamBreakpoint, responseEnded);
                break;
        }
    }

    /**
     * On HTTP/2 (streamId != null) address the chunk to the request's own stream so the shared
     * HttpToHttp2ConnectionHandler does not route it onto whichever stream last wrote a head - which,
     * with concurrent streams in flight, would mis-route this chunk onto a sibling (possibly
     * already-closed) stream and hang this client (#2667). On HTTP/1.1 write a plain content chunk.
     */
    private static HttpContent chunkContent(byte[] chunkBytes, org.mockserver.model.HttpRequest request) {
        return request.getStreamId() != null
            ? new StreamAddressedHttpContent(Unpooled.wrappedBuffer(chunkBytes), request.getStreamId(), false)
            : new DefaultHttpContent(Unpooled.wrappedBuffer(chunkBytes));
    }

    private void writeChunk(byte[] chunkBytes, byte[] injectedBytes, List<SseEvent> events, int index, ChannelHandlerContext ctx,
                            HttpSseResponse httpSseResponse, org.mockserver.model.HttpRequest request, StreamingFormat format, AtomicBoolean elementWritten, StreamBreakpoint streamBreakpoint, Runnable responseEnded) {
        try {
            if (format == StreamingFormat.JSON_ARRAY) {
                chunkBytes = separatedJsonArrayElement(chunkBytes, elementWritten);
                injectedBytes = separatedJsonArrayElement(injectedBytes, elementWritten);
            }
            if (injectedBytes != null) {
                ctx.write(chunkContent(chunkBytes, request));
            }
            HttpContent content = chunkContent(injectedBytes != null ? injectedBytes : chunkBytes, request);
            ctx.writeAndFlush(content).addListener(future -> {
                if (future.isSuccess()) {
                    if (mockServerLogger.isEnabledForInstance(Level.DEBUG)) {
                        mockServerLogger.logEvent(
                            new LogEntry()
                                .setType(EXPECTATION_RESPONSE)
                                .setLogLevel(Level.DEBUG)
                                .setCorrelationId(request.getLogCorrelationId())
                                .setHttpRequest(request)
                                .setMessageFormat("sent streaming chunk {} of {} for request:{}")
                                .setArguments(index + 1, events.size(), request)
                        );
                    }
                    scheduleEvents(events, index + 1, ctx, httpSseResponse, request, format, elementWritten, streamBreakpoint, responseEnded);
                } else if (clientGoneException(future.cause())) {
                    // a client that has gone is an ordinary end of the response
                    if (mockServerLogger.isEnabledForInstance(Level.DEBUG)) {
                        mockServerLogger.logEvent(
                            new LogEntry()
                                .setLogLevel(Level.DEBUG)
                                .setCorrelationId(request.getLogCorrelationId())
                                .setHttpRequest(request)
                                .setMessageFormat("client left before streaming chunk {} was sent:{}for request:{}")
                                .setArguments(index + 1, causeDescription(future.cause()), request)
                        );
                    }
                    finishStream(ctx, httpSseResponse, request, streamBreakpoint, responseEnded);
                } else {
                    if (mockServerLogger.isEnabledForInstance(Level.WARN)) {
                        mockServerLogger.logEvent(
                            new LogEntry()
                                .setLogLevel(Level.WARN)
                                .setCorrelationId(request.getLogCorrelationId())
                                .setHttpRequest(request)
                                .setMessageFormat("async write failure for streaming chunk {} for request:{}")
                                .setArguments(index + 1, request)
                                .setThrowable(future.cause())
                        );
                    }
                    finishStream(ctx, httpSseResponse, request, streamBreakpoint, responseEnded);
                }
            });
        } catch (Exception e) {
            if (mockServerLogger.isEnabledForInstance(Level.WARN)) {
                mockServerLogger.logEvent(
                    new LogEntry()
                        .setLogLevel(Level.WARN)
                        .setCorrelationId(request.getLogCorrelationId())
                        .setHttpRequest(request)
                        .setMessageFormat("exception sending streaming chunk {} for request:{}")
                        .setArguments(index + 1, request)
                        .setThrowable(e)
                );
            }
            finishStream(ctx, httpSseResponse, request, streamBreakpoint, responseEnded);
        }
    }

    /**
     * When the SSE response has a {@code templateType}, render the event's {@code data} payload as a
     * response template against the triggering request, returning a copy of the event with the rendered
     * data (the original event is never mutated, so a reused expectation renders freshly per request).
     * When there is no {@code templateType} (or no data), the original event is returned unchanged so
     * static responses are byte-for-byte identical.
     */
    private SseEvent renderEvent(SseEvent event, HttpSseResponse httpSseResponse, org.mockserver.model.HttpRequest request) {
        if (httpSseResponse.getTemplateType() == null || (event.getData() == null && event.getComment() == null)) {
            return event;
        }
        return SseEvent.sseEvent()
            .withEvent(event.getEvent())
            .withData(event.getData() != null ? templateRenderer.render(httpSseResponse.getTemplateType(), event.getData(), request) : null)
            .withId(event.getId())
            .withRetry(event.getRetry())
            .withComment(event.getComment() != null ? templateRenderer.render(httpSseResponse.getTemplateType(), event.getComment(), request) : null);
    }

    private static boolean isCommentOnly(SseEvent event) {
        return event.getComment() != null && event.getData() == null && event.getEvent() == null && event.getId() == null && event.getRetry() == null;
    }

    /** Ends a {@link StreamingFormat#JSON_ARRAY} stream that ran to completion with its closing bracket. */
    private static void closeJsonArray(ChannelHandlerContext ctx, org.mockserver.model.HttpRequest request, StreamingFormat format) {
        if (format == StreamingFormat.JSON_ARRAY && ctx.channel().isActive()) {
            ctx.writeAndFlush(chunkContent(JSON_ARRAY_CLOSE, request));
        }
    }

    /**
     * Prefixes a {@link StreamingFormat#JSON_ARRAY} element with its {@code ,\r\n} separator unless it
     * is the first element actually written, so a breakpoint DROP of the first element keeps the array valid.
     */
    private static byte[] separatedJsonArrayElement(byte[] element, AtomicBoolean elementWritten) {
        if (element == null || !elementWritten.getAndSet(true)) {
            return element;
        }
        byte[] separated = new byte[JSON_ARRAY_SEPARATOR.length + element.length];
        System.arraycopy(JSON_ARRAY_SEPARATOR, 0, separated, 0, JSON_ARRAY_SEPARATOR.length);
        System.arraycopy(element, 0, separated, JSON_ARRAY_SEPARATOR.length, element.length);
        return separated;
    }

    private void finishStream(ChannelHandlerContext ctx, HttpSseResponse httpSseResponse, org.mockserver.model.HttpRequest request, StreamBreakpoint streamBreakpoint, Runnable responseEnded) {
        if (streamBreakpoint != null) {
            ctx.channel().closeFuture().removeListener(streamBreakpoint.releaseOnClose);
            StreamFrameBreakpointRegistry.getInstance().evictStream(streamBreakpoint.streamId);
        }
        if (ctx.channel().isActive()) {
            // HTTP/2: end the response with an END_STREAM DATA frame. Every stream has its own
            // Http2MultiplexHandler child channel, so this write lands on the stream's own channel and
            // no explicit stream id is needed to route it. A bare terminal LastHttpContent would not
            // carry END_STREAM: Http2StreamFrameToHttpObjectCodec hard-codes endStream=false on its
            // bare-HttpContent branch, so the stream would never close and the client would hang after
            // the last event (#2667 / #2669). StreamAddressedHttpContent carries the end-of-stream flag
            // out-of-band and StreamAddressedContentHandler on the child channel translates it into a
            // frame the codec maps to END_STREAM. On HTTP/1.1 (streamId == null) write the plain
            // terminal LastHttpContent exactly as before, so chunked encoding is completed normally.
            Object terminal = request.getStreamId() != null
                ? new StreamAddressedHttpContent(Unpooled.EMPTY_BUFFER, request.getStreamId(), true)
                : LastHttpContent.EMPTY_LAST_CONTENT;
            ctx.writeAndFlush(terminal).addListener(future -> {
                responseEnded.run();
                // END_STREAM has already closed this stream. On HTTP/2 ctx is the stream's own child
                // channel; we deliberately do NOT call ctx.close() here - the stream is finished by
                // END_STREAM, and closing is both unnecessary and wrong when closeConnection:true,
                // because tearing down the shared parent connection to satisfy one expectation is never
                // the right trade. Just re-assert read interest below.
                if (request.getStreamId() != null) {
                    // Defensively re-assert read interest on the shared connection. AUTO_READ is
                    // enabled on the server child channel (MockServer.childOption) so Netty normally
                    // re-arms this itself; this is a no-op in that case and only matters if some
                    // other handler on this connection has turned auto-read off.
                    ctx.read();
                    return;
                }
                if (shouldCloseHttp1Connection(request, httpSseResponse)) {
                    ctx.close();
                } else {
                    // Keep-alive: defensively re-assert read interest so the client's NEXT request
                    // on this connection is read. AUTO_READ is enabled on the server child channel
                    // (MockServer.childOption), so Netty already re-arms the read at
                    // channelReadComplete and this is a no-op on the normal SSE path - verified by
                    // removing it and seeing connection reuse still work. It is kept only to stay
                    // correct if this response is written on a connection where another handler
                    // (connection-delay, breakpoint, subscription) has disabled auto-read.
                    ctx.read();
                }
            });
        }
    }

    /**
     * Resolve whether the HTTP/1.1 connection should be closed at end of stream, mirroring the
     * keep-alive-aware decision the non-streaming path makes in
     * {@code NettyResponseWriter.writeAndCloseSocket}. An explicit {@link HttpSseResponse#getCloseConnection()}
     * wins (the SSE model's equivalent of {@code ConnectionOptions.closeSocket}); otherwise the
     * request's keep-alive intent decides; and {@code alwaysCloseSocketConnections} forces a close
     * regardless. Only meaningful for HTTP/1.1 - callers must not close the shared HTTP/2 parent.
     */
    private boolean shouldCloseHttp1Connection(org.mockserver.model.HttpRequest request, HttpSseResponse httpSseResponse) {
        boolean closeChannel;
        if (httpSseResponse.getCloseConnection() != null) {
            closeChannel = httpSseResponse.getCloseConnection();
        } else {
            closeChannel = !(request.isKeepAlive() != null && request.isKeepAlive());
        }
        return closeChannel || configuration.alwaysCloseSocketConnections();
    }

    /**
     * Format a chunk as bytes for the given streaming format. SSE and NDJSON
     * produce UTF-8 text; AWS_EVENT_STREAM produces a binary event-stream
     * message wrapping the chunk data; AWS_CONVERSE_EVENT_STREAM produces one
     * whose {@code :event-type} is the event name and whose payload is the raw data.
     * JSON_ARRAY produces the bare element; its separator is added when it is written, and the
     * array's brackets when the stream opens and ends.
     */
    private byte[] formatChunkBytes(SseEvent event, StreamingFormat format) {
        if (format == StreamingFormat.JSON_ARRAY) {
            return (event.getData() != null ? event.getData() : "").getBytes(StandardCharsets.UTF_8);
        }
        if (format == StreamingFormat.AWS_EVENT_STREAM) {
            String data = event.getData();
            if (data == null) {
                data = "";
            }
            return BedrockEventStreamEncoder.encodeChunk(data);
        }
        if (format == StreamingFormat.AWS_CONVERSE_EVENT_STREAM) {
            String data = event.getData();
            // a nameless event (e.g. the malformed-SSE chaos chunk) would carry an empty :event-type,
            // which AWS SDKs drop silently; contentBlockDelta makes it reach the client's parser
            String eventType = event.getEvent() != null && !event.getEvent().isEmpty() ? event.getEvent() : "contentBlockDelta";
            return BedrockEventStreamEncoder.encodeEvent(eventType, data != null ? data : "");
        }
        return formatChunk(event, format).getBytes(StandardCharsets.UTF_8);
    }

    /**
     * Format a chunk for the given streaming format. SSE uses standard
     * {@code data:}/{@code event:} framing; NDJSON emits the raw data
     * payload followed by a single newline.
     */
    private String formatChunk(SseEvent event, StreamingFormat format) {
        if (format == StreamingFormat.NDJSON) {
            return formatNdjsonLine(event);
        }
        return formatSseEvent(event);
    }

    /**
     * Format a single NDJSON line: the raw JSON data followed by {@code \n}.
     * Ignores SSE-specific fields (event, id, retry) which have no NDJSON
     * equivalent.
     */
    private String formatNdjsonLine(SseEvent event) {
        String data = event.getData();
        if (data == null) {
            return "\n";
        }
        return data + "\n";
    }

    private String sanitizeSseFieldValue(String value) {
        if (value == null) {
            return null;
        }
        return value.replace("\n", "").replace("\r", "");
    }

    /** Package-private for testing. */
    String formatSseEvent(SseEvent event) {
        StringBuilder sb = new StringBuilder();
        if (event.getComment() != null) {
            // ':' then each line exactly as written, so "hb" sends ":hb" and " hb" sends ": hb"; a line break in the
            // comment starts another comment line, so it can never start a field or end the event
            for (String line : COMMENT_LINE_BREAK.split(event.getComment(), -1)) {
                sb.append(':').append(line).append('\n');
            }
        }
        if (event.getId() != null) {
            sb.append("id: ").append(sanitizeSseFieldValue(event.getId())).append("\n");
        }
        if (event.getEvent() != null) {
            sb.append("event: ").append(sanitizeSseFieldValue(event.getEvent())).append("\n");
        }
        if (event.getRetry() != null) {
            sb.append("retry: ").append(event.getRetry()).append("\n");
        }
        if (event.getData() != null) {
            // Per WHATWG, an SSE stream is split on CRLF, CR *or* LF. Splitting on "\n" alone
            // left a lone CR embedded in the emitted `data:` line, where the client treats it as
            // a line terminator -- so everything after the CR was parsed as a new (unrecognised)
            // field and silently dropped. Every line terminator must become its own `data:` line;
            // the client rejoins them with "\n", which is the only form a bare CR can survive in.
            for (String line : event.getData().split("\r\n|\r|\n")) {
                sb.append("data: ").append(line).append("\n");
            }
        }
        sb.append("\n");
        return sb.toString();
    }

    private static final class StreamBreakpoint {
        private final BreakpointMatcher matcher;
        private final String streamId;
        private final ChannelFutureListener releaseOnClose;

        private StreamBreakpoint(BreakpointMatcher matcher, String streamId, ChannelFutureListener releaseOnClose) {
            this.matcher = matcher;
            this.streamId = streamId;
            this.releaseOnClose = releaseOnClose;
        }
    }
}
