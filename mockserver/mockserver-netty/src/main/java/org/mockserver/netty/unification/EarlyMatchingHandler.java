package org.mockserver.netty.unification;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpObject;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.util.AttributeKey;
import io.netty.util.ReferenceCountUtil;
import org.mockserver.configuration.Configuration;
import org.mockserver.log.model.LogEntry;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.mappers.FullHttpRequestToMockServerHttpRequest;
import org.mockserver.mock.Expectation;
import org.mockserver.mock.HttpState;
import org.mockserver.mock.action.http.HttpActionHandler;
import org.mockserver.netty.responsewriter.EarlyNettyResponseWriter;
import org.mockserver.socket.tls.SniHandler;
import org.slf4j.event.Level;

import java.security.cert.Certificate;
import java.util.Collections;

import static org.mockserver.exception.ExceptionHandling.closeOnFlush;
import static org.mockserver.exception.ExceptionHandling.connectionClosedException;

/**
 * Inspects the incoming Netty HttpRequest (headers only) and, when a matching expectation
 * is configured with {@code respondBeforeBody=true}, dispatches the response and closes the
 * connection before HttpObjectAggregator buffers the request body. On no match, removes itself
 * and forwards the HttpRequest downstream so the standard pipeline (aggregator + matcher) runs.
 *
 * <p>Only installed in the HTTP/1.1 pipeline. CONNECT and HTTP/2 requests bypass early matching
 * and run through the standard pipeline.
 *
 * <p>See issue #1831 for the motivating use case (reproducing okhttp/okhttp#1001).
 */
public class EarlyMatchingHandler extends SimpleChannelInboundHandler<HttpObject> {

    static final AttributeKey<Boolean> EARLY_DISPATCHED = AttributeKey.valueOf("EARLY_DISPATCHED");

    private final Configuration configuration;
    private final MockServerLogger mockServerLogger;
    private final HttpState httpState;
    private final HttpActionHandler actionHandler;
    private final boolean isSecure;

    public EarlyMatchingHandler(Configuration configuration, HttpState httpState, HttpActionHandler actionHandler, boolean isSecure) {
        super(false);
        this.configuration = configuration;
        this.mockServerLogger = httpState.getMockServerLogger();
        this.httpState = httpState;
        this.actionHandler = actionHandler;
        this.isSecure = isSecure;
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, HttpObject msg) {
        if (Boolean.TRUE.equals(ctx.channel().attr(EARLY_DISPATCHED).get())) {
            // body bytes for an already-dispatched request — discard. EarlyNettyResponseWriter
            // unconditionally closes the connection after the response, so we don't trigger close
            // here on LastHttpContent (would risk double-close).
            ReferenceCountUtil.release(msg);
            return;
        }

        if (msg instanceof HttpRequest) {
            HttpRequest nettyRequest = (HttpRequest) msg;

            if (HttpMethod.CONNECT.equals(nettyRequest.method())) {
                passThroughAndDetach(ctx, msg);
                return;
            }

            // Gate BEFORE mapping: with no respondBeforeBody expectation configured, the full
            // headers-only ingest below would build an HttpRequest only for firstMatchingEarlyExpectation
            // to return null. This is the emptiness gate ONLY - the control-plane path guard stays inside
            // that method - so the outcome is identical to mapping and finding no match: pass through and detach.
            if (!httpState.hasEarlyExpectations()) {
                passThroughAndDetach(ctx, msg);
                return;
            }

            try {
                Certificate[] clientCerts = SniHandler.retrieveClientCertificates(mockServerLogger, ctx);
                FullHttpRequestToMockServerHttpRequest mapper = new FullHttpRequestToMockServerHttpRequest(
                    configuration, mockServerLogger, isSecure, clientCerts, port(ctx)
                );
                org.mockserver.model.HttpRequest headersOnly = mapper.mapHeadersOnlyHttpRequestToMockServerRequest(
                    nettyRequest,
                    Collections.emptyList(),
                    ctx.channel().localAddress(),
                    ctx.channel().remoteAddress(),
                    null
                );
                Expectation expectation = httpState.firstMatchingEarlyExpectation(headersOnly);
                if (expectation != null) {
                    EarlyNettyResponseWriter responseWriter = new EarlyNettyResponseWriter(
                        configuration, mockServerLogger, ctx, httpState.getScheduler()
                    );
                    // dispatch first; only mark EARLY_DISPATCHED after a successful dispatch so an
                    // exception cannot leave the channel attribute set and poison later requests
                    actionHandler.processEarlyAction(headersOnly, expectation, ctx, responseWriter, false);
                    ctx.channel().attr(EARLY_DISPATCHED).set(Boolean.TRUE);
                    ReferenceCountUtil.release(msg);
                    return;
                }
            } catch (Throwable throwable) {
                if (mockServerLogger.isEnabledForInstance(Level.WARN)) {
                    mockServerLogger.logEvent(
                        new LogEntry()
                            .setLogLevel(Level.WARN)
                            .setMessageFormat("exception during early header matching, falling back to standard pipeline")
                            .setThrowable(throwable)
                    );
                }
            }

            passThroughAndDetach(ctx, msg);
            return;
        }

        // any other object (HttpContent before we've seen the header request) — pass through
        ctx.fireChannelRead(msg);
    }

    private void passThroughAndDetach(ChannelHandlerContext ctx, HttpObject msg) {
        ctx.fireChannelRead(msg);
        // remove ourselves so subsequent HttpContent for this request flows directly to the aggregator
        if (ctx.pipeline().get(EarlyMatchingHandler.class) != null) {
            ctx.pipeline().remove(this);
        }
    }

    private static Integer port(ChannelHandlerContext ctx) {
        if (ctx.channel().localAddress() instanceof java.net.InetSocketAddress) {
            return ((java.net.InetSocketAddress) ctx.channel().localAddress()).getPort();
        }
        return null;
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        // No isSslOrDecoderFault branch here: this handler only special-cases a benign
        // client-close after an early response; every other exception (including SSL/decoder
        // faults) is re-fired to the downstream handler, which logs it at the correct level.
        if (Boolean.TRUE.equals(ctx.channel().attr(EARLY_DISPATCHED).get()) && connectionClosedException(cause)) {
            if (mockServerLogger.isEnabledForInstance(Level.TRACE)) {
                mockServerLogger.logEvent(
                    new LogEntry()
                        .setLogLevel(Level.TRACE)
                        .setMessageFormat("connection closed by client after early response: " + ctx.channel())
                );
            }
            closeOnFlush(ctx.channel());
            return;
        }
        ctx.fireExceptionCaught(cause);
    }
}
