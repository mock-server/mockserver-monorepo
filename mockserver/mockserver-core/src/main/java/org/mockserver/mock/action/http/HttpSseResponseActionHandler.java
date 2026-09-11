package org.mockserver.mock.action.http;

import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.*;
import org.mockserver.codec.StreamAddressedHttpContent;
import org.mockserver.configuration.Configuration;
import org.mockserver.llm.StreamingFormat;
import org.mockserver.llm.codec.BedrockEventStreamEncoder;
import org.mockserver.log.model.LogEntry;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.mappers.Http2StreamIds;
import org.mockserver.model.Delay;
import org.mockserver.model.HttpSseResponse;
import org.mockserver.model.SseEvent;
import org.mockserver.scheduler.Scheduler;
import org.slf4j.event.Level;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.mockserver.log.model.LogEntry.LogMessageType.EXPECTATION_RESPONSE;

public class HttpSseResponseActionHandler {

    private final MockServerLogger mockServerLogger;
    private final Scheduler scheduler;
    private final Configuration configuration;
    private final StreamTemplateRenderer templateRenderer;

    public HttpSseResponseActionHandler(MockServerLogger mockServerLogger, Scheduler scheduler, Configuration configuration) {
        this.mockServerLogger = mockServerLogger;
        this.scheduler = scheduler;
        this.configuration = configuration;
        this.templateRenderer = new StreamTemplateRenderer(mockServerLogger, configuration);
    }

    public void handle(HttpSseResponse httpSseResponse, ChannelHandlerContext ctx, org.mockserver.model.HttpRequest request) {
        handle(httpSseResponse, ctx, request, StreamingFormat.SSE);
    }

    public void handle(HttpSseResponse httpSseResponse, ChannelHandlerContext ctx, org.mockserver.model.HttpRequest request, StreamingFormat format) {
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
                defaultContentType = BedrockEventStreamEncoder.CONTENT_TYPE;
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

        List<SseEvent> events = httpSseResponse.getEvents();
        if (events != null && !events.isEmpty()) {
            scheduleEvents(events, 0, ctx, httpSseResponse, request, format);
        } else {
            finishStream(ctx, httpSseResponse, request);
        }
    }

    private void scheduleEvents(List<SseEvent> events, int index, ChannelHandlerContext ctx, HttpSseResponse httpSseResponse, org.mockserver.model.HttpRequest request, StreamingFormat format) {
        if (index >= events.size() || !ctx.channel().isActive()) {
            finishStream(ctx, httpSseResponse, request);
            return;
        }

        SseEvent event = events.get(index);
        Delay delay = event.getDelay();

        Runnable writeEvent = () -> {
            try {
                if (!ctx.channel().isActive()) {
                    return;
                }
                byte[] chunkBytes = formatChunkBytes(renderEvent(event, httpSseResponse, request), format);
                // On HTTP/2 (streamId != null) address the chunk to the request's own stream so the
                // shared HttpToHttp2ConnectionHandler does not route it onto whichever stream last
                // wrote a head - which, with concurrent streams in flight, would mis-route this chunk
                // onto a sibling (possibly already-closed) stream and hang this client (#2667). On
                // HTTP/1.1 (streamId == null) write a plain content chunk exactly as before.
                HttpContent content = request.getStreamId() != null
                    ? new StreamAddressedHttpContent(Unpooled.wrappedBuffer(chunkBytes), request.getStreamId(), false)
                    : new DefaultHttpContent(Unpooled.wrappedBuffer(chunkBytes));
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
                        scheduleEvents(events, index + 1, ctx, httpSseResponse, request, format);
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
                        finishStream(ctx, httpSseResponse, request);
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
                finishStream(ctx, httpSseResponse, request);
            }
        };

        if (delay != null) {
            scheduler.schedule(writeEvent, false, delay);
        } else {
            writeEvent.run();
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
        if (httpSseResponse.getTemplateType() == null || event.getData() == null) {
            return event;
        }
        String renderedData = templateRenderer.render(httpSseResponse.getTemplateType(), event.getData(), request);
        return SseEvent.sseEvent()
            .withEvent(event.getEvent())
            .withData(renderedData)
            .withId(event.getId())
            .withRetry(event.getRetry());
    }

    private void finishStream(ChannelHandlerContext ctx, HttpSseResponse httpSseResponse, org.mockserver.model.HttpRequest request) {
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
     * message wrapping the chunk data.
     */
    private byte[] formatChunkBytes(SseEvent event, StreamingFormat format) {
        if (format == StreamingFormat.AWS_EVENT_STREAM) {
            String data = event.getData();
            if (data == null) {
                data = "";
            }
            return BedrockEventStreamEncoder.encodeChunk(data);
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
}
