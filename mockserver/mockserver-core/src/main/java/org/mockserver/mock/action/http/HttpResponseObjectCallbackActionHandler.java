package org.mockserver.mock.action.http;

import org.mockserver.closurecallback.websocketregistry.LocalCallbackRegistry;
import org.mockserver.closurecallback.websocketregistry.WebSocketClientRegistry;
import org.mockserver.log.model.LogEntry;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.mock.HttpState;
import org.mockserver.model.HttpObjectCallback;
import org.mockserver.model.HttpRequest;
import org.mockserver.model.HttpResponse;
import org.mockserver.responsewriter.ResponseWriter;
import org.mockserver.uuid.UUIDService;

import static org.mockserver.closurecallback.websocketregistry.WebSocketClientRegistry.WEB_SOCKET_CORRELATION_ID_HEADER_NAME;
import static org.mockserver.model.HttpResponse.notFoundResponse;
import static org.slf4j.event.Level.TRACE;
import static org.slf4j.event.Level.WARN;

/**
 * @author jamesdbloom
 */
@SuppressWarnings("FieldMayBeFinal")
public class HttpResponseObjectCallbackActionHandler {
    private WebSocketClientRegistry webSocketClientRegistry;
    private final MockServerLogger mockServerLogger;

    public HttpResponseObjectCallbackActionHandler(HttpState httpStateHandler) {
        this.mockServerLogger = httpStateHandler.getMockServerLogger();
        this.webSocketClientRegistry = httpStateHandler.getWebSocketClientRegistry();
    }

    public void handle(final HttpActionHandler actionHandler, final HttpObjectCallback httpObjectCallback, final HttpRequest request, final ResponseWriter responseWriter, final boolean synchronous, Runnable expectationPostProcessor) {
        final String clientId = httpObjectCallback.getClientId();
        if (LocalCallbackRegistry.responseClientExists(clientId)) {
            handleLocally(actionHandler, httpObjectCallback, request, responseWriter, synchronous, expectationPostProcessor, clientId);
        } else {
            handleViaWebSocket(actionHandler, httpObjectCallback, request, responseWriter, synchronous, expectationPostProcessor, clientId);
        }
    }

    private void handleLocally(HttpActionHandler actionHandler, HttpObjectCallback httpObjectCallback, HttpRequest request, ResponseWriter responseWriter, boolean synchronous, Runnable expectationPostProcessor, String clientId) {
        if (mockServerLogger != null && mockServerLogger.isEnabledForInstance(TRACE)) {
            mockServerLogger.logEvent(
                new LogEntry()
                    .setLogLevel(TRACE)
                    .setHttpRequest(request)
                    .setMessageFormat("locally sending request{}to client " + clientId)
                    .setArguments(request)
            );
        }
        // ROOT FIX for the pool-on-by-default self-deadlock: a local response callback may make a
        // BLOCKING loopback call back to this same server (e.g. registering a nested expectation via the
        // same MockServerClient). Run it via scheduleLocalCallback so — in asynchronous (Netty) mode — it
        // executes on the dedicated unbounded local-callback pool, NOT inline on the server worker event
        // loop: the worker is then free to read the loopback's reply, and a recursively-nested callback
        // always gets its own thread (no pool-exhaustion deadlock). In synchronous (WAR/servlet) mode it
        // still runs inline, preserving blocking-model semantics. The response is written, as before,
        // through writeResponseActionResponse with the original responseWriter, so routing is unchanged.
        actionHandler.getScheduler().scheduleLocalCallback(() -> {
            try {
                HttpResponse callbackResponse = LocalCallbackRegistry.retrieveResponseCallback(clientId).handle(request);
                actionHandler.writeResponseActionResponse(callbackResponse, responseWriter, request, httpObjectCallback, synchronous, null, expectationPostProcessor);
            } catch (Throwable throwable) {
                if (mockServerLogger != null && mockServerLogger.isEnabledForInstance(WARN)) {
                    mockServerLogger.logEvent(
                        new LogEntry()
                            .setLogLevel(WARN)
                            .setHttpRequest(request)
                            .setMessageFormat("returning{}because client " + clientId + " response callback throw an exception")
                            .setArguments(notFoundResponse())
                            .setThrowable(throwable)
                    );
                }
                actionHandler.markGenerated(responseWriter);
                actionHandler.writeResponseActionResponse(notFoundResponse(), responseWriter, request, httpObjectCallback, synchronous, null, expectationPostProcessor);
            }
        }, synchronous);
    }

    private void handleViaWebSocket(HttpActionHandler actionHandler, HttpObjectCallback httpObjectCallback, HttpRequest request, ResponseWriter responseWriter, boolean synchronous, Runnable expectationPostProcessor, String clientId) {
        final String webSocketCorrelationId = UUIDService.getUUID();
        webSocketClientRegistry.registerResponseCallbackHandler(webSocketCorrelationId, response -> {
            if (mockServerLogger != null && mockServerLogger.isEnabledForInstance(TRACE)) {
                mockServerLogger.logEvent(
                    new LogEntry()
                        .setLogLevel(TRACE)
                        .setHttpRequest(request)
                        .setMessageFormat("received response over websocket{}for request{}from client " + clientId + " for correlationId " + webSocketCorrelationId)
                        .setArguments(response, request)
                );
            }
            webSocketClientRegistry.unregisterResponseCallbackHandler(webSocketCorrelationId);
            actionHandler.writeResponseActionResponse(response.removeHeader(WEB_SOCKET_CORRELATION_ID_HEADER_NAME), responseWriter, request, httpObjectCallback, synchronous, null, expectationPostProcessor);
        });
        if (!webSocketClientRegistry.sendClientMessage(clientId, request.clone().withHeader(WEB_SOCKET_CORRELATION_ID_HEADER_NAME, webSocketCorrelationId), null)) {
            if (mockServerLogger != null && mockServerLogger.isEnabledForInstance(WARN)) {
                mockServerLogger.logEvent(
                    new LogEntry()
                        .setLogLevel(WARN)
                        .setHttpRequest(request)
                        .setMessageFormat("returning{}because client " + clientId + " has closed web socket connection")
                        .setArguments(notFoundResponse())
                );
            }
            // The client disconnected before it could supply a response: the response callback registered
            // above will never fire, so unregister it to avoid a registry leak before responding.
            webSocketClientRegistry.unregisterResponseCallbackHandler(webSocketCorrelationId);
            actionHandler.markGenerated(responseWriter);
            actionHandler.writeResponseActionResponse(notFoundResponse(), responseWriter, request, httpObjectCallback, synchronous, null, expectationPostProcessor);
        } else if (mockServerLogger != null && mockServerLogger.isEnabledForInstance(TRACE)) {
            mockServerLogger.logEvent(
                new LogEntry()
                    .setLogLevel(TRACE)
                    .setHttpRequest(request)
                    .setMessageFormat("sending request over websocket{}to client " + clientId + " for correlationId " + webSocketCorrelationId)
                    .setArguments(request)
            );
        }
    }

}
