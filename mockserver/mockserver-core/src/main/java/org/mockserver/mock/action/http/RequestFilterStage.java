package org.mockserver.mock.action.http;

import io.netty.channel.ChannelHandlerContext;
import org.mockserver.cors.CORSHeaders;
import org.mockserver.extension.DataPlaneHooks;
import org.mockserver.extension.DataPlaneOutcome;
import org.mockserver.extension.FilterContext;
import org.mockserver.grpc.GrpcDerivedHeaders;
import org.mockserver.log.model.LogEntry;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.model.HttpRequest;
import org.mockserver.model.HttpResponse;
import org.mockserver.responsewriter.ResponseWriter;
import org.mockserver.responsewriter.TransformingWriter;
import org.mockserver.scheduler.Scheduler;
import org.slf4j.event.Level;

import java.util.Set;

import static org.mockserver.log.model.LogEntry.LogMessageType.EXPECTATION_RESPONSE;
import static org.mockserver.log.model.LogEntry.LogMessageType.INFO;
import static org.mockserver.log.model.LogEntry.LogMessageType.RECEIVED_REQUEST;
import static org.mockserver.log.model.LogEntryMessages.RECEIVED_REQUEST_MESSAGE_FORMAT;

/**
 * Runs the extension request filters (H1) for one request reaching {@link HttpActionHandler#processAction}, and then
 * the rest of {@code processAction} with the request they let through. Loaded only when a filter or transformer is.
 * <ul>
 *     <li>The chain runs on the hook pool, never on an event loop. A caller with a channel continues on that channel's
 *     event loop; a waiting caller (a servlet, {@code InProcessMockServer.handle}) continues on its own thread; any
 *     other caller continues on the scheduler.</li>
 *     <li>On a channel the continuation is wrapped in catch-and-answer: whatever it throws, the exchange is answered
 *     with a {@code 500} unless a response has already claimed it.</li>
 *     <li>A filter's answer, and a failure response, are written from the thread that completed the chain, after the
 *     chain's slot is released.</li>
 *     <li>{@code RECEIVED_REQUEST} is logged exactly once: with the filtered request, the original one on a failure,
 *     or the input of the filter that answered.</li>
 * </ul>
 */
final class RequestFilterStage {

    private final HttpActionHandler handler;
    private final HttpRequest received;
    private final ResponseWriter responseWriter;
    private final ChannelHandlerContext ctx;
    private final Set<String> localAddresses;
    private final boolean proxyingRequest;
    private final boolean synchronous;
    private final MockServerLogger mockServerLogger;

    private RequestFilterStage(HttpActionHandler handler, HttpRequest received, ResponseWriter responseWriter, ChannelHandlerContext ctx, Set<String> localAddresses, boolean proxyingRequest, boolean synchronous) {
        this.handler = handler;
        this.received = received;
        this.responseWriter = responseWriter;
        this.ctx = ctx;
        this.localAddresses = localAddresses;
        this.proxyingRequest = proxyingRequest;
        this.synchronous = synchronous;
        this.mockServerLogger = handler.getMockServerLogger();
    }

    static void filter(HttpActionHandler handler, DataPlaneHooks hooks, HttpRequest request, ResponseWriter responseWriter, ChannelHandlerContext ctx, Set<String> localAddresses, boolean proxyingRequest, boolean synchronous) {
        // a preflight for a control-plane route is answered under the control-plane CORS policy, never filtered
        if (handler.isControlPlaneRoutePreflight(request)) {
            handler.processActionAfterFilter(request, responseWriter, ctx, localAddresses, proxyingRequest, synchronous);
            return;
        }
        TransformingWriter.markDataPlane(responseWriter, request);
        if (!hooks.hasRequestFilters()) {
            // only response transformers are loaded: nothing to run first, so no hop off this thread either
            handler.processActionAfterFilter(request, responseWriter, ctx, localAddresses, proxyingRequest, synchronous);
            return;
        }
        RequestFilterStage stage = new RequestFilterStage(handler, request, responseWriter, ctx, localAddresses, proxyingRequest, synchronous);
        FilterContext context = filterContext(request, proxyingRequest);
        Scheduler scheduler = handler.getScheduler();
        if (ctx != null) {
            hooks.filter(request, context, scheduler, stage::onFilteredForChannel);
        } else if (synchronous) {
            stage.act(hooks.filterAndWait(request, context, scheduler), false);
        } else {
            hooks.filter(request, context, scheduler, outcome -> scheduler.submit(() -> stage.act(outcome, true), false));
        }
    }

    static FilterContext filterContext(HttpRequest request, boolean proxyingRequest) {
        return FilterContext.builder()
            .correlationId(request.getLogCorrelationId())
            .protocol(request.getProtocol())
            .secure(Boolean.TRUE.equals(request.isSecure()))
            .remoteAddress(request.getRemoteAddress())
            .proxyRequest(proxyingRequest)
            .corsPreflight(CORSHeaders.isCorsPreflight(request))
            .grpc(request.containsHeader(GrpcDerivedHeaders.SERVICE))
            .build();
    }

    /**
     * Runs on whichever thread completed the chain; it must not block. Matching and the action go back to the
     * channel's event loop, where {@code processAction} always runs.
     */
    private void onFilteredForChannel(DataPlaneOutcome outcome) {
        if (!outcome.isProceed()) {
            act(outcome, true);
            return;
        }
        logFiltered(outcome);
        HttpRequest filtered = outcome.request();
        try {
            ctx.executor().execute(() -> continueAnswering(filtered));
        } catch (Throwable loopStopped) {
            answerUnexpected(filtered, loopStopped);
        }
    }

    /**
     * @param answerOnThrow whether a throw from the continuation is answered here; a waiting caller has its own
     *                      handling, as it does with no filter loaded
     */
    private void act(DataPlaneOutcome outcome, boolean answerOnThrow) {
        if (outcome.isFailure()) {
            logReceived(received);
            answerFailure(received, outcome.response());
        } else if (outcome.isRespond()) {
            logFiltered(outcome);
            HttpRequest request = outcome.request();
            logReceived(request);
            HttpResponse response = outcome.response();
            // at every log level, like a matched response, so verification and retrieve see it
            mockServerLogger.logEvent(
                new LogEntry()
                    .setType(EXPECTATION_RESPONSE)
                    .setLogLevel(Level.INFO)
                    .setCorrelationId(request.getLogCorrelationId())
                    .setHttpRequest(request)
                    .setHttpResponse(response)
                    .setMessageFormat("returning response:{}for request:{}returned by request filter:{}")
                    .setArguments(response, request, outcome.extensionName())
            );
            // the response transformers see a filter's answer as one MockServer made
            TransformingWriter.markGenerated(responseWriter);
            responseWriter.writeResponse(request, response, false);
        } else {
            logFiltered(outcome);
            if (answerOnThrow) {
                continueAnswering(outcome.request());
            } else {
                handler.processActionAfterFilter(outcome.request(), responseWriter, ctx, localAddresses, proxyingRequest, synchronous);
            }
        }
    }

    private void continueAnswering(HttpRequest filtered) {
        try {
            handler.processActionAfterFilter(filtered, responseWriter, ctx, localAddresses, proxyingRequest, synchronous);
        } catch (Throwable throwable) {
            answerUnexpected(filtered, throwable);
        }
    }

    private void answerUnexpected(HttpRequest request, Throwable throwable) {
        TransformingWriter.failUnanswered(mockServerLogger, responseWriter, request, throwable);
    }

    // a failure response claims the exchange and never passes the response transformers
    private void answerFailure(HttpRequest request, HttpResponse failureResponse) {
        if (responseWriter instanceof TransformingWriter) {
            ((TransformingWriter) responseWriter).writeUntransformed(request, failureResponse);
        } else {
            responseWriter.writeResponse(request, failureResponse, false);
        }
    }

    private void logReceived(HttpRequest request) {
        if (!handler.isOwnLoopedRequest(request)) {
            mockServerLogger.logEvent(
                new LogEntry()
                    .setType(RECEIVED_REQUEST)
                    .setLogLevel(Level.INFO)
                    .setCorrelationId(request.getLogCorrelationId())
                    .setHttpRequest(request)
                    .setMessageFormat(RECEIVED_REQUEST_MESSAGE_FORMAT)
                    .setArguments(request)
            );
        }
    }

    /**
     * One entry for each filter whose result changed the request, carrying the request as received.
     */
    private void logFiltered(DataPlaneOutcome outcome) {
        if (outcome.changedBy().isEmpty() || !mockServerLogger.isEnabledForInstance(Level.INFO)) {
            return;
        }
        for (String extension : outcome.changedBy()) {
            mockServerLogger.logEvent(
                new LogEntry()
                    .setType(INFO)
                    .setLogLevel(Level.INFO)
                    .setCorrelationId(received.getLogCorrelationId())
                    .setHttpRequest(received)
                    .setMessageFormat("request filtered by:{}the request as received was:{}")
                    .setArguments(extension, received)
            );
        }
    }
}
