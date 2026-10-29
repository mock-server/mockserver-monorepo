package org.mockserver.netty.http3;

import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPipeline;
import io.netty.handler.codec.http3.DefaultHttp3DataFrame;
import io.netty.handler.codec.http3.DefaultHttp3HeadersFrame;
import io.netty.handler.codec.http3.Http3ErrorCode;
import io.netty.handler.codec.quic.QuicStreamChannel;
import io.netty.util.concurrent.EventExecutor;
import org.mockserver.configuration.Configuration;
import org.mockserver.log.model.LogEntry;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.model.ConnectionOptions;
import org.mockserver.model.HttpRequest;
import org.mockserver.model.HttpResponse;
import org.mockserver.model.StreamingBody;
import org.mockserver.responsewriter.ResponseWriter;
import org.mockserver.responsewriter.StreamErrorWriter;
import org.mockserver.telemetry.TraceContextAttributes;
import org.mockserver.telemetry.W3CTraceContext;
import org.slf4j.event.Level;

import java.util.ArrayList;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.concurrent.RejectedExecutionException;

/**
 * A {@link ResponseWriter} that serialises the MockServer {@link HttpResponse}
 * as HTTP/3 frames and writes them to a QUIC stream channel.
 * <p>
 * This allows the standard request-processing pipeline ({@code HttpState},
 * {@code HttpActionHandler}) to write responses identically regardless of
 * whether the request arrived via HTTP/1.1, HTTP/2, or HTTP/3.
 * <p>
 * <strong>Streaming support:</strong> when the response carries a
 * {@link StreamingBody} (SSE, chunked proxy forwarding, LLM streaming),
 * the headers are sent immediately and each chunk is forwarded as an HTTP/3
 * DATA frame. The QUIC stream output is shut down when the stream completes.
 * Backpressure is implemented via {@link StreamingBody#chunkWritten(int)}: each
 * chunk write completion reports its bytes, which requests the next upstream read
 * once the unwritten backlog has drained.
 * <p>
 * A response to {@code HEAD} is its header section alone, static or streamed, keeping {@code content-length}. A
 * {@code 1xx} that ends the exchange is its header section alone, and its stream is reset with {@code H3_NO_ERROR}.
 */
public class Http3ResponseWriter extends ResponseWriter implements StreamErrorWriter {

    private final ChannelHandlerContext ctx;
    private volatile boolean responseStarted;

    public Http3ResponseWriter(Configuration configuration, MockServerLogger mockServerLogger, ChannelHandlerContext ctx) {
        super(configuration, mockServerLogger);
        this.ctx = ctx;
    }

    /**
     * Whether a response, or a reset of the stream, has been started on this stream.
     */
    boolean isResponseStarted() {
        return responseStarted;
    }

    /**
     * Reset the QUIC stream with the supplied HTTP/3 error code (RESET_STREAM, RFC 9114
     * section 4.1). Only this request stream is reset; other streams on the QUIC connection are
     * unaffected. No ByteBuf is allocated, so there is nothing to release.
     */
    @Override
    public void writeStreamError(long errorCode) {
        responseStarted = true;
        if (ctx.executor().inEventLoop()) {
            resetStream(errorCode);
        } else {
            executeOnStreamEventLoop(ctx, mockServerLogger, () -> resetStream(errorCode));
        }
    }

    private void resetStream(long errorCode) {
        if (ctx.channel() instanceof QuicStreamChannel && ctx.channel().isActive()) {
            // Netty's QuicStreamChannel.shutdownOutput takes an int, but a QUIC application error code
            // is a 62-bit varint. Every RFC 9114 §8.1 HTTP/3 code is tiny (<= 0x110), so this only
            // matters for out-of-range vendor codes — clamp (and warn) rather than silently truncating.
            int resetCode;
            if (errorCode < 0 || errorCode > Integer.MAX_VALUE) {
                if (MockServerLogger.isEnabled(Level.WARN) && mockServerLogger != null) {
                    mockServerLogger.logEvent(
                        new LogEntry()
                            .setLogLevel(Level.WARN)
                            .setMessageFormat("HTTP/3 stream error code {} is out of the supported int range, clamping to {}")
                            .setArguments(errorCode, Integer.MAX_VALUE)
                    );
                }
                resetCode = Integer.MAX_VALUE;
            } else {
                resetCode = (int) errorCode;
            }
            // shutdownOutput(int) sends a RESET_STREAM frame carrying the application error code,
            // tearing down this stream's output without affecting the rest of the QUIC connection.
            ((QuicStreamChannel) ctx.channel()).shutdownOutput(resetCode);
        } else if (ctx.channel().isActive()) {
            ctx.close();
        }
    }

    /**
     * Close the whole QUIC connection at once with {@code H3_INTERNAL_ERROR} (a CONNECTION_CLOSE frame),
     * failing every stream on it: QUIC runs on UDP, so there is no TCP reset to send.
     */
    @Override
    public void writeConnectionReset() {
        responseStarted = true;
        if (ctx.executor().inEventLoop()) {
            closeConnection();
        } else {
            executeOnStreamEventLoop(ctx, mockServerLogger, this::closeConnection);
        }
    }

    private void closeConnection() {
        if (ctx.channel() instanceof QuicStreamChannel && ((QuicStreamChannel) ctx.channel()).parent() != null) {
            ((QuicStreamChannel) ctx.channel()).parent().close(true, Http3ErrorCode.H3_INTERNAL_ERROR.code(), Unpooled.EMPTY_BUFFER);
        } else if (ctx.channel().isActive()) {
            ctx.close();
        }
    }

    /**
     * An action that writes its own response (SSE, a streamed LLM response) writes HTTP/1.1 objects, which this
     * stream's HTTP/3 frame codec cannot take, so {@link Http3HttpObjectEncoder} is added ahead of the request's
     * handler to turn them into HTTP/3 frames. It is added synchronously, from whichever thread this runs on, so the
     * writes that follow pass through it.
     */
    @Override
    public Runnable respondingDirectly() {
        responseStarted = true;
        ChannelPipeline pipeline = ctx.pipeline();
        if (!ctx.isRemoved() && pipeline.get(Http3HttpObjectEncoder.NAME) == null) {
            try {
                pipeline.addBefore(ctx.name(), Http3HttpObjectEncoder.NAME, Http3HttpObjectEncoder.INSTANCE);
            } catch (NoSuchElementException | IllegalArgumentException streamGone) {
                // the stream closed meanwhile (its handlers were removed): nothing more is written to it
            }
        }
        return super.respondingDirectly();
    }

    @Override
    public void sendResponse(HttpRequest request, HttpResponse response) {
        responseStarted = true;
        HttpResponse toSend = response != null ? response : HttpResponse.notFoundResponse();
        if (ctx.executor().inEventLoop()) {
            writeResponse(request, toSend);
        } else if (!executeOnStreamEventLoop(ctx, mockServerLogger, () -> writeResponse(request, toSend))) {
            discard(toSend.getStreamingBody());
        }
    }

    /**
     * Run {@code write}, from a thread off the stream's event loop (a scheduler thread releasing a delayed response),
     * on that loop as one task, so its frames are written and flushed together. The task has no caller left to answer
     * a failure, so it logs it and resets the stream.
     *
     * @return false when the event loop has shut down, so nothing will be written
     */
    static boolean executeOnStreamEventLoop(ChannelHandlerContext ctx, MockServerLogger mockServerLogger, Runnable write) {
        EventExecutor executor = ctx.executor();
        try {
            executor.execute(() -> {
                try {
                    write.run();
                } catch (Throwable throwable) {
                    if (mockServerLogger != null && mockServerLogger.isEnabledForInstance(Level.WARN)) {
                        mockServerLogger.logEvent(
                            new LogEntry()
                                .setLogLevel(Level.WARN)
                                .setMessageFormat("exception writing HTTP/3 response, resetting stream:{}")
                                .setArguments(throwable.getMessage())
                                .setThrowable(throwable)
                        );
                    }
                    if (ctx.channel() instanceof QuicStreamChannel) {
                        ((QuicStreamChannel) ctx.channel()).shutdownOutput(Http3ErrorCode.H3_INTERNAL_ERROR.code());
                    } else {
                        ctx.close();
                    }
                }
            });
            return true;
        } catch (RejectedExecutionException shutDown) {
            return false;
        }
    }

    private void writeResponse(HttpRequest request, HttpResponse response) {

        // W3C trace-context propagation on outbound HTTP/3 responses, gated by
        // otelPropagateTraceContext -- mirrors the outbound logic of
        // TraceContextHandler on the TCP path.
        propagateTraceContext(response);

        warnIfConnectionOptionsIgnored(response);

        if (isInformational(response)) {
            writeInformationalResponse(response);
        } else if (isHead(request)) {
            writeResponseToHead(response);
        } else if (response.getStreamingBody() != null) {
            writeStreamingResponse(request, response);
        } else {
            writeStaticResponse(response);
        }
    }

    /**
     * Whether a request's method is {@code HEAD}, whose response has no content (RFC 9110 section 9.3.2).
     */
    static boolean isHead(String method) {
        return "HEAD".equalsIgnoreCase(method);
    }

    private static boolean isHead(HttpRequest request) {
        return request != null && isHead(request.getMethod(""));
    }

    /**
     * Whether a response's status is {@code 1xx}, which over HTTP/3 can only be an interim response (RFC 9114
     * section 4.1): {@code 101} included, as HTTP/3 has no upgrade.
     */
    static boolean isInformational(HttpResponse response) {
        Integer statusCode = response.getStatusCode();
        return statusCode != null && statusCode >= 100 && statusCode < 200;
    }

    /**
     * Write a mocked {@code 1xx} that ends the exchange: its header section alone, then the stream reset with
     * {@code H3_NO_ERROR}, as HTTP/2 resets it with {@code NO_ERROR}. Ending the stream after an interim response is
     * malformed, and Netty's codec takes a DATA frame or trailers after one as a connection error.
     */
    private void writeInformationalResponse(HttpResponse response) {
        StreamingBody streamingBody = response.getStreamingBody();
        // reset in a later task: a reset drops what the stream has not sent, and a write made while the connection
        // is being read is sent only when that read completes
        ctx.writeAndFlush(Http3RequestBridge.toHttp3HeadersFrame(response, true))
            .addListener(future -> ctx.executor().execute(() -> writeStreamError(Http3ErrorCode.H3_NO_ERROR.code())));
        discard(streamingBody);
    }

    /**
     * Write a response to {@code HEAD}: the header section a {@code GET} is sent, ending the stream, with no
     * DATA frame and no trailers. A streamed body is discarded as it arrives and its upstream closed.
     */
    private void writeResponseToHead(HttpResponse response) {
        StreamingBody streamingBody = response.getStreamingBody();
        if (streamingBody == null) {
            ctx.writeAndFlush(Http3RequestBridge.toHttp3HeadersFrame(response))
                .addListener(QuicStreamChannel.SHUTDOWN_OUTPUT);
            return;
        }
        ctx.writeAndFlush(Http3RequestBridge.toHttp3HeadersFrame(response, true))
            .addListener(future -> shutdownQuicStreamOutput());
        discard(streamingBody);
    }

    /**
     * Take a streamed body that will not be sent as it arrives, and close its upstream: the client's response is
     * already whole, so neither the end of the body nor an error can change it.
     */
    private static void discard(StreamingBody streamingBody) {
        if (streamingBody == null) {
            return;
        }
        streamingBody.subscribe(chunk -> streamingBody.chunkWritten(chunk.readableBytes()), () -> {
        }, error -> {
        });
        if (!streamingBody.isCompleted()) {
            streamingBody.closeUpstream();
        }
    }

    /**
     * Warn, once per response, when an expectation carries {@link ConnectionOptions} that this
     * writer does not act on.
     *
     * <h3>Why a warning and not an implementation</h3>
     * <p>{@code ConnectionOptions} is honoured in nineteen places in the HTTP/1.1 writer and in
     * NONE on HTTP/3, so a user who sets {@code closeSocket} or {@code chunkSize} and switches a
     * test to HTTP/3 silently gets none of it while the expectation still reports as created.
     * Accepting an option and ignoring it is the worst of the three available behaviours, so
     * until the applicable subset is implemented the omission is at least made observable.</p>
     *
     * <p>The fields split into two groups:</p>
     * <ul>
     *   <li><b>Inapplicable by protocol</b> — {@code suppressConnectionHeader} and
     *       {@code keepAliveOverride} govern the {@code Connection}/{@code Keep-Alive} headers,
     *       which RFC 9114 section 4.2 forbids on HTTP/3 outright. There is nothing to
     *       implement; these are reported as not applicable rather than as missing.</li>
     *   <li><b>Applicable but unimplemented</b> — {@code closeSocket}, {@code closeSocketDelay},
     *       {@code chunkSize}, {@code chunkDelay}, {@code suppressContentLengthHeader} and
     *       {@code contentLengthHeaderOverride} all have meaningful QUIC equivalents (closing
     *       the connection, segmenting the body across STREAM frames, header manipulation).
     *       These are the follow-up work.</li>
     * </ul>
     *
     * <p>Deliberately not a hard rejection: failing the response would break users who set
     * {@code ConnectionOptions} globally across a suite that happens to include HTTP/3, turning
     * a silent no-op into a broken test run.</p>
     *
     * <p>This fires once per response carrying {@code connectionOptions}, which is repetitive for
     * a suite that sets them globally. Note that de-duplicating per writer would achieve nothing —
     * {@code Http3MockServerHandler} constructs a new writer for every request. Meaningful dedup
     * would have to hang off the QUIC CONNECTION (the stream channel's parent), which is left as
     * follow-up rather than added speculatively to this path.</p>
     */
    private void warnIfConnectionOptionsIgnored(HttpResponse response) {
        ConnectionOptions connectionOptions = response.getConnectionOptions();
        // Null-guarded on the logger as writeStreamError above is: this runs on the response path
        // and must never be the reason a response fails to be written. Deliberately NOT also gated
        // on MockServerLogger.isEnabled(WARN) — that reads the global configured log level, which
        // would make the emission depend on mutable process-wide state and any test of it
        // order-dependent. The body only runs when connectionOptions is set, which is rare.
        if (connectionOptions == null || mockServerLogger == null) {
            return;
        }
        List<String> unimplemented = unimplementedOnHttp3(connectionOptions);
        List<String> notApplicable = notApplicableOnHttp3(connectionOptions);
        if (unimplemented.isEmpty() && notApplicable.isEmpty()) {
            return;
        }
        mockServerLogger.logEvent(
            new LogEntry()
                .setLogLevel(Level.WARN)
                .setMessageFormat(
                    "connectionOptions are not applied on HTTP/3 and have been ignored - "
                        + "not yet implemented:{}- not applicable to HTTP/3 (RFC 9114 section 4.2 forbids "
                        + "connection-specific header fields):{}")
                .setArguments(unimplemented, notApplicable)
        );
    }

    /**
     * The {@link ConnectionOptions} fields that are set, have a meaningful HTTP/3 equivalent, and
     * are nonetheless not acted on by this writer. Package-private so the classification can be
     * asserted directly rather than only inferred from a log line.
     */
    static List<String> unimplementedOnHttp3(ConnectionOptions connectionOptions) {
        List<String> unimplemented = new ArrayList<>();
        if (connectionOptions == null) {
            return unimplemented;
        }
        if (connectionOptions.getCloseSocket() != null) {
            unimplemented.add("closeSocket");
        }
        if (connectionOptions.getCloseSocketDelay() != null) {
            unimplemented.add("closeSocketDelay");
        }
        if (connectionOptions.getChunkSize() != null) {
            unimplemented.add("chunkSize");
        }
        if (connectionOptions.getChunkDelay() != null) {
            unimplemented.add("chunkDelay");
        }
        if (connectionOptions.getSuppressContentLengthHeader() != null) {
            unimplemented.add("suppressContentLengthHeader");
        }
        if (connectionOptions.getContentLengthHeaderOverride() != null) {
            unimplemented.add("contentLengthHeaderOverride");
        }
        return unimplemented;
    }

    /**
     * The {@link ConnectionOptions} fields that are set but cannot apply to HTTP/3 at all,
     * because RFC 9114 section 4.2 forbids connection-specific header fields.
     */
    static List<String> notApplicableOnHttp3(ConnectionOptions connectionOptions) {
        List<String> notApplicable = new ArrayList<>();
        if (connectionOptions == null) {
            return notApplicable;
        }
        if (connectionOptions.getSuppressConnectionHeader() != null) {
            notApplicable.add("suppressConnectionHeader");
        }
        if (connectionOptions.getKeepAliveOverride() != null) {
            notApplicable.add("keepAliveOverride");
        }
        return notApplicable;
    }

    /**
     * When {@code otelPropagateTraceContext} is enabled, copy the trace context
     * headers (traceparent and optionally tracestate) from the channel attribute
     * onto the response. This mirrors the outbound write() logic of
     * {@code TraceContextHandler} on the TCP path.
     */
    private void propagateTraceContext(HttpResponse response) {
        if (configuration.otelPropagateTraceContext()) {
            W3CTraceContext context = ctx.channel().attr(TraceContextAttributes.TRACE_CONTEXT).get();
            if (context != null && context.isValid()) {
                response.withHeader("traceparent", context.toTraceparent());
                if (context.getTraceState() != null && !context.getTraceState().isEmpty()) {
                    response.withHeader("tracestate", context.getTraceState());
                }
            }
        }
    }

    /**
     * Write a streaming response: send headers immediately, then subscribe to the
     * {@link StreamingBody} to forward each chunk as an HTTP/3 DATA frame. When the
     * stream completes (or errors), shut down the QUIC stream output.
     */
    private void writeStreamingResponse(HttpRequest request, HttpResponse response) {
        StreamingBody streamingBody = response.getStreamingBody();

        if (mockServerLogger.isEnabledForInstance(Level.DEBUG)) {
            mockServerLogger.logEvent(
                new LogEntry()
                    .setLogLevel(Level.DEBUG)
                    .setHttpRequest(request)
                    .setMessageFormat("streaming response over HTTP/3 for request:{}")
                    .setArguments(request)
            );
        }

        // Send the response headers immediately (without SHUTDOWN_OUTPUT). Dropping content-length:
        // a streamed body's length is unknown at header time, and one copied from a relayed upstream
        // response describes a different body than the one actually streamed.
        DefaultHttp3HeadersFrame headersFrame = Http3RequestBridge.toHttp3HeadersFrame(response, true);
        ctx.writeAndFlush(headersFrame);

        // a stream reset or closed mid-response (a write stall, the client going away) takes no more of the upstream
        ChannelFutureListener closeUpstreamIfIncomplete = future -> {
            if (!streamingBody.isCompleted()) {
                streamingBody.closeUpstreamForClientClose();
            }
        };
        ctx.channel().closeFuture().addListener(closeUpstreamIfIncomplete);

        // Subscribe to the streaming body to forward chunks as HTTP/3 DATA frames.
        // After each chunk write completes, call streamingBody.chunkWritten(bytes), which
        // requests the next upstream read once the backlog has drained -- this implements
        // backpressure so a slow client does not cause unbounded buffering.
        streamingBody.subscribe(
            // onChunk
            chunk -> {
                final int chunkSize = chunk.readableBytes();
                if (ctx.channel().isActive()) {
                    ctx.writeAndFlush(new DefaultHttp3DataFrame(Unpooled.copiedBuffer(chunk)))
                        .addListener(future -> streamingBody.chunkWritten(chunkSize));
                } else {
                    // The client has gone, so nothing will take the rest of the stream
                    streamingBody.closeUpstreamForClientClose();
                    streamingBody.chunkWritten(chunkSize);
                }
            },
            // onComplete -- flush an empty DATA frame to ensure all prior chunk
            // writes have drained through the QUIC pipeline before shutting down
            // the stream output (avoids truncation race with pending async writes).
            // When the response carries trailers, emit a trailing HEADERS frame after
            // the final DATA frame and before shutting down the stream output.
            () -> {
                ctx.channel().closeFuture().removeListener(closeUpstreamIfIncomplete);
                if (ctx.channel().isActive()) {
                    DefaultHttp3HeadersFrame trailersFrame = Http3RequestBridge.toHttp3TrailersFrame(response);
                    if (trailersFrame != null) {
                        ctx.write(new DefaultHttp3DataFrame(Unpooled.EMPTY_BUFFER));
                        ctx.writeAndFlush(trailersFrame)
                            .addListener(future -> shutdownQuicStreamOutput());
                    } else {
                        ctx.writeAndFlush(new DefaultHttp3DataFrame(Unpooled.EMPTY_BUFFER))
                            .addListener(future -> shutdownQuicStreamOutput());
                    }
                }
            },
            // onError
            error -> {
                ctx.channel().closeFuture().removeListener(closeUpstreamIfIncomplete);
                if (mockServerLogger.isEnabledForInstance(Level.WARN)) {
                    mockServerLogger.logEvent(
                        new LogEntry()
                            .setLogLevel(Level.WARN)
                            .setHttpRequest(request)
                            .setMessageFormat("streaming response error over HTTP/3 for request:{}error:{}")
                            .setArguments(request, error.getMessage())
                            .setThrowable(error)
                    );
                }
                if (error instanceof StreamingBody.StreamAbortedException) {
                    // reset rather than end the stream, so the client sees an incomplete response
                    if (ctx.channel() instanceof QuicStreamChannel) {
                        ((QuicStreamChannel) ctx.channel()).shutdownOutput(Http3ErrorCode.H3_INTERNAL_ERROR.code());
                    }
                    ctx.channel().close();
                } else if (ctx.channel().isActive()) {
                    ctx.writeAndFlush(new DefaultHttp3DataFrame(Unpooled.EMPTY_BUFFER))
                        .addListener(future -> shutdownQuicStreamOutput());
                }
            }
        );
    }

    /**
     * Write a static (non-streaming) response: headers + optional body DATA frame,
     * then shut down the QUIC stream output.
     */
    private void writeStaticResponse(HttpResponse response) {
        DefaultHttp3HeadersFrame headersFrame = Http3RequestBridge.toHttp3HeadersFrame(response);
        DefaultHttp3DataFrame dataFrame = Http3RequestBridge.toHttp3DataFrame(response);
        DefaultHttp3HeadersFrame trailersFrame = Http3RequestBridge.toHttp3TrailersFrame(response);

        ctx.write(headersFrame);
        if (dataFrame != null) {
            if (trailersFrame != null) {
                // headers + data + trailing HEADERS frame, then shutdown the stream output
                ctx.write(dataFrame);
                ctx.writeAndFlush(trailersFrame)
                    .addListener(QuicStreamChannel.SHUTDOWN_OUTPUT);
            } else {
                ctx.writeAndFlush(dataFrame)
                    .addListener(QuicStreamChannel.SHUTDOWN_OUTPUT);
            }
        } else if (trailersFrame != null) {
            // body-less response with trailers: headers + trailing HEADERS frame
            ctx.writeAndFlush(trailersFrame)
                .addListener(QuicStreamChannel.SHUTDOWN_OUTPUT);
        } else {
            ctx.flush();
            shutdownQuicStreamOutput();
        }
    }

    /**
     * Shut down the output side of the QUIC stream, signalling to the peer that no
     * more data will be sent on this stream. Safe to call multiple times.
     */
    private void shutdownQuicStreamOutput() {
        if (ctx.channel() instanceof QuicStreamChannel) {
            ((QuicStreamChannel) ctx.channel()).shutdownOutput();
        }
    }
}
