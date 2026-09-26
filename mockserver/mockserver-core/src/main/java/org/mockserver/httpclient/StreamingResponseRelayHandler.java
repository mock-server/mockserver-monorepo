package org.mockserver.httpclient;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.handler.codec.http.*;
import io.netty.util.ReferenceCountUtil;
import org.mockserver.configuration.Configuration;
import org.mockserver.log.model.LogEntry;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.model.*;
import org.mockserver.model.Cookie;
import org.mockserver.model.Header;
import org.mockserver.model.HttpResponse;
import org.slf4j.event.Level;

import java.util.Set;
import java.util.concurrent.CompletableFuture;

import static io.netty.handler.codec.http.HttpHeaderNames.*;
import static org.mockserver.httpclient.NettyHttpClient.RESPONSE_FUTURE;
import static org.mockserver.model.NottableString.headerName;
import static org.mockserver.model.NottableString.string;
import static org.mockserver.model.NottableString.strings;

/**
 * Consumes unaggregated {@link HttpObject}s on the {@link NettyHttpClient} channel when
 * a streaming response is detected. On the response head it builds a head-only
 * {@link org.mockserver.model.HttpResponse} with a {@link StreamingBody} and completes
 * the channel's {@code RESPONSE_FUTURE} immediately so the caller can start relaying
 * chunks to the proxy client without waiting for the entire body.
 * <p>
 * On each {@link HttpContent} chunk, the bytes are fed to the {@link StreamingBody}
 * (bounded capture + forward). On {@link LastHttpContent}, the body is completed and the
 * channel is closed. On {@code channelInactive} or exception mid-stream the body is
 * errored so the log entry can still be written with whatever was captured.
 */
public class StreamingResponseRelayHandler extends ChannelInboundHandlerAdapter {

    private final Configuration configuration;
    private final MockServerLogger mockServerLogger;
    private StreamingBody streamingBody;
    private boolean headReceived;

    public StreamingResponseRelayHandler(Configuration configuration, MockServerLogger mockServerLogger) {
        this.configuration = configuration;
        this.mockServerLogger = mockServerLogger;
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
        if (msg instanceof io.netty.handler.codec.http.HttpResponse && !headReceived) {
            try {
                headReceived = true;
                io.netty.handler.codec.http.HttpResponse nettyResponse = (io.netty.handler.codec.http.HttpResponse) msg;

                // Build a head-only MockServer HttpResponse
                HttpResponse mockResponse = HttpResponse.response();
                mockResponse.withStatusCode(nettyResponse.status().code());
                mockResponse.withReasonPhrase(nettyResponse.status().reasonPhrase());

                // Map headers, stripping Content-Encoding and Content-Length
                // (chunks are decompressed by HttpContentDecompressor upstream)
                Set<String> headerNames = nettyResponse.headers().names();
                if (!headerNames.isEmpty()) {
                    Headers headers = new Headers();
                    for (String headerName : headerNames) {
                        String lowerName = headerName.toLowerCase();
                        if (lowerName.equals("content-encoding") || lowerName.equals("content-length")) {
                            continue;
                        }
                        // Literal name and values - see the note in
                        // FullHttpResponseToMockServerHttpResponse: the withEntry(String, ...)
                        // overloads marker-parse, which is wrong for a real message.
                        headers.withEntry(headerName(headerName), strings(nettyResponse.headers().getAll(headerName), false));
                    }
                    mockResponse.withHeaders(headers);
                }

                // Map cookies from Set-Cookie headers
                setCookies(mockResponse);

                // Attach a streaming body with per-chunk timestamp capture for replay timing
                streamingBody = new StreamingBody(configuration.maxStreamingCaptureBytes(), true);
                mockResponse.withStreamingBody(streamingBody);

                // Give the body a reference to the upstream event loop so that subscribe()
                // can marshal onto it, serialising with addChunk/complete/error.
                streamingBody.setEventLoop(ctx.channel().eventLoop());

                // Disable AUTO_READ on the upstream channel so that we only read the next
                // chunk when the downstream write has completed (backpressure). The downstream
                // writer calls streamingBody.requestMore() which triggers ctx.read() here.
                ctx.channel().config().setAutoRead(false);
                final ChannelHandlerContext upstreamCtx = ctx;
                streamingBody.setRequestMoreCallback(() -> {
                    if (upstreamCtx.channel().isActive()) {
                        upstreamCtx.read();
                    }
                });
                // NOTE: We do NOT call ctx.read() here. The first upstream read is triggered
                // by subscribe() after it has drained any pending chunks. This avoids a race
                // where a chunk arrives before the subscriber is connected.

                // Complete the response future immediately with the head-only response
                CompletableFuture<Message> responseFuture = ctx.channel().attr(RESPONSE_FUTURE).get();
                if (responseFuture != null && !responseFuture.isDone()) {
                    responseFuture.complete(mockResponse);
                } else {
                    // Finding 5: RESPONSE_FUTURE is null or already done — log and close
                    if (mockServerLogger != null && mockServerLogger.isEnabledForInstance(Level.WARN)) {
                        mockServerLogger.logEvent(
                            new LogEntry()
                                .setLogLevel(Level.WARN)
                                .setMessageFormat("streaming response head received but RESPONSE_FUTURE is " +
                                    (responseFuture == null ? "null" : "already completed") +
                                    " — discarding streaming response and closing channel")
                        );
                    }
                    streamingBody.error(new RuntimeException("RESPONSE_FUTURE unavailable"));
                    ctx.close();
                    return;
                }

                // If the response head is also the last content (edge case: empty body with LastHttpContent)
                if (msg instanceof LastHttpContent) {
                    streamingBody.complete();
                    ctx.close();
                }
            } finally {
                // Release the inbound HttpObject; addChunk copies bytes via getBytes()
                ReferenceCountUtil.release(msg);
            }
        } else if (msg instanceof LastHttpContent) {
            try {
                if (streamingBody != null) {
                    LastHttpContent lastContent = (LastHttpContent) msg;
                    if (lastContent.content().readableBytes() > 0) {
                        streamingBody.addChunk(lastContent.content());
                    }
                    streamingBody.complete();
                }
                ctx.close();
            } finally {
                ReferenceCountUtil.release(msg);
            }
        } else if (msg instanceof HttpContent) {
            try {
                if (streamingBody != null) {
                    HttpContent content = (HttpContent) msg;
                    if (content.content().readableBytes() > 0) {
                        streamingBody.addChunk(content.content());
                    }
                }
            } finally {
                ReferenceCountUtil.release(msg);
            }
        } else {
            // Unknown message type — transfer ownership downstream (do NOT release)
            ctx.fireChannelRead(msg);
        }
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) throws Exception {
        if (streamingBody != null && !streamingBody.isCompleted()) {
            streamingBody.error(new RuntimeException("channel closed mid-stream"));
        }
        super.channelInactive(ctx);
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
        if (streamingBody != null && !streamingBody.isCompleted()) {
            streamingBody.error(cause);
        }
        // Also complete the response future exceptionally if it hasn't been completed
        CompletableFuture<Message> responseFuture = ctx.channel().attr(RESPONSE_FUTURE).get();
        if (responseFuture != null && !responseFuture.isDone()) {
            responseFuture.completeExceptionally(cause);
        }
        ctx.close();
    }

    private void setCookies(HttpResponse httpResponse) {
        Cookies cookies = new Cookies();
        for (Header header : httpResponse.getHeaderList()) {
            if (header.getName().getValue().equalsIgnoreCase("Set-Cookie")) {
                for (NottableString cookieHeader : header.getValues()) {
                    try {
                        io.netty.handler.codec.http.cookie.Cookie httpCookie =
                            io.netty.handler.codec.http.cookie.ClientCookieDecoder.LAX.decode(cookieHeader.getValue());
                        String name = httpCookie.name().trim();
                        String value = httpCookie.value() != null ? httpCookie.value().trim() : "";
                        cookies.withEntry(new Cookie(string(name, false), string(value, false)));
                    } catch (Exception ignored) {
                        // skip malformed cookies
                    }
                }
            }
        }
        if (!cookies.isEmpty()) {
            httpResponse.withCookies(cookies);
        }
    }
}
