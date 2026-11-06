package org.mockserver.closurecallback.websocketregistry;

import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import io.netty.util.AttributeKey;
import org.mockserver.closurecallback.websocketclient.WebSocketException;
import org.mockserver.collections.CircularHashMap;
import org.mockserver.configuration.Configuration;
import org.mockserver.log.model.LogEntry;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.metrics.Metrics;
import org.mockserver.model.HttpRequest;
import org.mockserver.model.HttpRequestAndHttpResponse;
import org.mockserver.model.HttpResponse;
import org.mockserver.serialization.WebSocketMessageSerializer;
import org.mockserver.serialization.model.BreakpointReleasedDTO;
import org.mockserver.serialization.model.PausedStreamFrameDTO;
import org.mockserver.serialization.model.StreamFrameDecisionDTO;
import org.mockserver.serialization.model.WebSocketClientIdDTO;
import org.mockserver.serialization.model.WebSocketErrorDTO;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import static org.mockserver.metrics.Metrics.Name.*;
import static org.mockserver.metrics.Metrics.clearWebSocketMetrics;
import static org.mockserver.model.HttpResponse.response;
import static org.slf4j.event.Level.TRACE;
import static org.slf4j.event.Level.WARN;

/**
 * @author jamesdbloom
 */
public class WebSocketClientRegistry {

    public static final String WEB_SOCKET_CORRELATION_ID_HEADER_NAME = "WebSocketCorrelationId";
    /**
     * Header name carrying the matched breakpoint id so the client can route each
     * pushed paused item to the handler of the SPECIFIC breakpoint that matched.
     * Set by the server dispatchers on REQUEST/RESPONSE phase messages.
     */
    public static final String BREAKPOINT_ID_HEADER_NAME = "X-MockServer-BreakpointId";
    /**
     * Header name carrying the epoch-millis timestamp of when MockServer first
     * received the request. All phases (REQUEST, RESPONSE) of the same exchange
     * share the same value, enabling the dashboard to sort exchanges by original
     * request time rather than by WS arrival time.
     */
    public static final String REQUEST_TIMESTAMP_HEADER_NAME = "X-MockServer-RequestTimestamp";
    /**
     * Channel attribute key for the per-server {@link WebSocketClientRegistry}. Set once
     * on the parent/server channel at pipeline construction; read at hold points in Netty
     * handlers that need the registry but do not have {@code HttpState} in direct scope.
     */
    public static final AttributeKey<WebSocketClientRegistry> WS_REGISTRY_KEY =
        AttributeKey.valueOf("mockserver.webSocketClientRegistry");
    private final MockServerLogger mockServerLogger;
    private final WebSocketMessageSerializer webSocketMessageSerializer;
    private final Map<String, Channel> clientRegistry;
    private final Set<String> breakpointReleasedClients = ConcurrentHashMap.newKeySet();
    private final Map<String, Channel> takingOver = new ConcurrentHashMap<>();
    private final Map<String, WebSocketResponseCallback> responseCallbackRegistry;
    private final Map<String, WebSocketRequestCallback> forwardCallbackRegistry;
    private final Map<String, StreamFrameDecisionCallback> streamFrameCallbackRegistry;
    private final Metrics metrics;

    /**
     * Callback interface for stream-frame breakpoint decision replies received
     * over the callback WebSocket.
     */
    public interface StreamFrameDecisionCallback {
        void handle(StreamFrameDecisionDTO decision);
    }

    public WebSocketClientRegistry(Configuration configuration, MockServerLogger mockServerLogger) {
        this.mockServerLogger = mockServerLogger;
        this.webSocketMessageSerializer = new WebSocketMessageSerializer(mockServerLogger);
        this.clientRegistry = Collections.synchronizedMap(new CircularHashMap<>(configuration.maxWebSocketExpectations(), channel -> {
            if (channel != null && channel.isOpen()) {
                channel.close();
            }
        }));
        // DEFERRED LIMITATION (known): these callback registries are CircularHashMaps with no eviction
        // listener. If more than maxWebSocketExpectations callbacks are in flight concurrently, the eldest
        // entry is silently evicted and the CompletableFuture waiting on that correlationId is never
        // completed — the waiting scheduler thread blocks until maxFutureTimeoutInMillis. A clean fix is NOT
        // applied here on purpose: the eviction listener fires from inside LinkedHashMap.put (via
        // removeEldestEntry) while the map is mid-structural-modification, but the callbacks themselves
        // re-entrantly call unregister*CallbackHandler() (mutating this same map) when invoked, so completing
        // the evicted future from the listener would require a deferred-dispatch side-table to run the
        // callback off the map's monitor. That is a larger redesign than is warranted here. In practice this
        // is bounded by maxWebSocketExpectations (default large) and only manifests under extreme concurrent
        // callback load. See code-review finding #4 (WebSocketClientRegistry eviction).
        this.responseCallbackRegistry = Collections.synchronizedMap(new CircularHashMap<>(configuration.maxWebSocketExpectations()));
        this.forwardCallbackRegistry = Collections.synchronizedMap(new CircularHashMap<>(configuration.maxWebSocketExpectations()));
        this.streamFrameCallbackRegistry = Collections.synchronizedMap(new CircularHashMap<>(configuration.maxWebSocketExpectations()));
        this.metrics = new Metrics(configuration);
    }

    public void receivedTextWebSocketFrame(TextWebSocketFrame textWebSocketFrame) {
        try {
            Object deserializedMessage = webSocketMessageSerializer.deserialize(textWebSocketFrame.text());
            if (mockServerLogger != null && mockServerLogger.isEnabledForInstance(TRACE)) {
                mockServerLogger.logEvent(
                    new LogEntry()
                        .setLogLevel(TRACE)
                        .setMessageFormat("received message over websocket{}")
                        .setArguments(deserializedMessage)
                );
            }
            if (deserializedMessage instanceof HttpResponse) {
                HttpResponse httpResponse = (HttpResponse) deserializedMessage;
                String firstHeader = httpResponse.getFirstHeader(WEB_SOCKET_CORRELATION_ID_HEADER_NAME);
                WebSocketResponseCallback webSocketResponseCallback = responseCallbackRegistry.get(firstHeader);
                if (webSocketResponseCallback != null) {
                    webSocketResponseCallback.handle(httpResponse);
                }
            } else if (deserializedMessage instanceof HttpRequest) {
                HttpRequest httpRequest = (HttpRequest) deserializedMessage;
                final String firstHeader = httpRequest.getFirstHeader(WEB_SOCKET_CORRELATION_ID_HEADER_NAME);
                WebSocketRequestCallback webSocketRequestCallback = forwardCallbackRegistry.get(firstHeader);
                if (webSocketRequestCallback != null) {
                    webSocketRequestCallback.handle(httpRequest);
                }
            } else if (deserializedMessage instanceof StreamFrameDecisionDTO) {
                StreamFrameDecisionDTO decisionDTO = (StreamFrameDecisionDTO) deserializedMessage;
                String correlationId = decisionDTO.getCorrelationId();
                StreamFrameDecisionCallback callback = streamFrameCallbackRegistry.get(correlationId);
                if (callback != null) {
                    callback.handle(decisionDTO);
                }
            } else if (deserializedMessage instanceof WebSocketErrorDTO) {
                WebSocketErrorDTO webSocketErrorDTO = (WebSocketErrorDTO) deserializedMessage;
                if (forwardCallbackRegistry.containsKey(webSocketErrorDTO.getWebSocketCorrelationId())) {
                    forwardCallbackRegistry
                        .get(webSocketErrorDTO.getWebSocketCorrelationId())
                        .handleError(
                            response()
                                .withStatusCode(404)
                                .withBody(webSocketErrorDTO.getMessage())
                        );
                } else if (responseCallbackRegistry.containsKey(webSocketErrorDTO.getWebSocketCorrelationId())) {
                    responseCallbackRegistry
                        .get(webSocketErrorDTO.getWebSocketCorrelationId())
                        .handle(
                            response()
                                .withStatusCode(404)
                                .withBody(webSocketErrorDTO.getMessage())
                        );
                }
            } else {
                throw new WebSocketException("Unsupported web socket message " + deserializedMessage);
            }
        } catch (Exception e) {
            throw new WebSocketException("Exception while receiving web socket message" + textWebSocketFrame.text(), e);
        }
    }

    public int size() {
        return clientRegistry.size();
    }

    /**
     * @return the channel the client id is registered to, or null when it is not registered
     */
    public Channel registeredChannel(String clientId) {
        return clientId != null ? clientRegistry.get(clientId) : null;
    }

    /**
     * Reserves a client id for a connection that is about to register it, so only one connection at a
     * time takes an id over. A reservation held by a closed connection is taken over.
     *
     * @return false when another open connection holds the reservation
     */
    public boolean reserveTakeover(String clientId, Channel channel) {
        Channel other = takingOver.putIfAbsent(clientId, channel);
        return other == null || other == channel || (!other.isActive() && takingOver.replace(clientId, other, channel));
    }

    public void releaseTakeover(String clientId, Channel channel) {
        takingOver.remove(clientId, channel);
    }

    public void registerClient(String clientId, ChannelHandlerContext ctx) {
        registerClient(clientId, ctx, false);
    }

    /**
     * @param acceptsBreakpointReleased whether the client asked to be sent {@link BreakpointReleasedDTO}
     *                                  notices; clients that did not are never sent one
     */
    public void registerClient(String clientId, ChannelHandlerContext ctx, boolean acceptsBreakpointReleased) {
        registerChannel(clientId, ctx.channel(), acceptsBreakpointReleased);
        sendClientId(clientId, ctx.channel());
    }

    /**
     * Makes the client id routable to the channel. Must happen before anything tells the client it is
     * registered, because the client creates expectations that call back to it as soon as it is told.
     *
     * @return the channel the id was registered to before, or null, for {@link #restoreChannel}
     */
    public Channel registerChannel(String clientId, Channel channel, boolean acceptsBreakpointReleased) {
        if (acceptsBreakpointReleased) {
            breakpointReleasedClients.add(clientId);
        } else {
            breakpointReleasedClients.remove(clientId);
        }
        Channel previous = clientRegistry.put(clientId, channel);
        metrics.set(WEBSOCKET_CALLBACK_CLIENTS_COUNT, clientRegistry.size());
        if (mockServerLogger != null && mockServerLogger.isEnabledForInstance(TRACE)) {
            mockServerLogger.logEvent(
                new LogEntry()
                    .setLogLevel(TRACE)
                    .setMessageFormat("registering client " + clientId + "")
            );
        }
        return previous;
    }

    /**
     * Undoes {@link #registerChannel} for a channel that never completed its upgrade: the id goes back to
     * the previous channel while that is open, otherwise it is unregistered. Does nothing once the id has
     * been registered to another channel since.
     */
    public void restoreChannel(String clientId, Channel channel, Channel previous) {
        boolean unregistered;
        synchronized (clientRegistry) {
            if (clientRegistry.get(clientId) != channel) {
                return;
            }
            unregistered = previous == null || !previous.isOpen();
            if (unregistered) {
                clientRegistry.remove(clientId);
            } else {
                clientRegistry.put(clientId, previous);
            }
        }
        if (unregistered) {
            cleanUpClient(clientId, null);
        } else {
            metrics.set(WEBSOCKET_CALLBACK_CLIENTS_COUNT, clientRegistry.size());
        }
    }

    public void sendClientId(String clientId, Channel channel) {
        try {
            channel.writeAndFlush(new TextWebSocketFrame(webSocketMessageSerializer.serialize(new WebSocketClientIdDTO().setClientId(clientId))));
        } catch (Exception e) {
            throw new WebSocketException("Exception while sending web socket registration client id message to client " + clientId, e);
        }
    }

    /**
     * Unregisters the client when its id is still bound to this channel (or to no channel), so a
     * closing connection never removes a newer connection that re-registered the same id.
     */
    public void unregisterClient(String clientId, Channel channel) {
        synchronized (clientRegistry) {
            Channel current = clientId != null ? clientRegistry.get(clientId) : null;
            if (current != null && current != channel) {
                return;
            }
            if (current != null) {
                clientRegistry.remove(clientId);
            }
        }
        if (clientId != null && clientRegistry.get(clientId) != null) {
            // re-registered since the remove above: keep the id-keyed state the new connection owns
            if (channel != null && channel.isOpen()) {
                channel.close();
            }
            return;
        }
        cleanUpClient(clientId, channel);
    }

    public void unregisterClient(String clientId) {
        cleanUpClient(clientId, clientRegistry.remove(clientId));
    }

    private void cleanUpClient(String clientId, Channel removeChannel) {
        LocalCallbackRegistry.unregisterCallback(clientId);
        // An object-callback expectation with no client id unregisters null; a concurrent key set rejects it.
        if (clientId != null) {
            breakpointReleasedClients.remove(clientId);
        }
        if (removeChannel != null && removeChannel.isOpen()) {
            removeChannel.close();
        }
        // Clean up breakpoint matchers and in-flight WS dispatches owned by this client
        org.mockserver.mock.breakpoint.BreakpointMatcherRegistry.getInstance().removeByClientId(clientId);
        org.mockserver.mock.breakpoint.BreakpointCallbackDispatcher.getInstance().autoCompleteForClient(clientId);
        org.mockserver.mock.breakpoint.StreamFrameCallbackDispatcher.getInstance().autoCompleteForClient(clientId);
        metrics.set(WEBSOCKET_CALLBACK_CLIENTS_COUNT, clientRegistry.size());
        if (mockServerLogger != null && mockServerLogger.isEnabledForInstance(TRACE)) {
            mockServerLogger.logEvent(
                new LogEntry()
                    .setLogLevel(TRACE)
                    .setMessageFormat("unregistering client " + clientId + "")
            );
        }
    }

    public void registerResponseCallbackHandler(String webSocketCorrelationId, WebSocketResponseCallback expectationResponseCallback) {
        responseCallbackRegistry.put(webSocketCorrelationId, expectationResponseCallback);
        metrics.set(WEBSOCKET_CALLBACK_RESPONSE_HANDLERS_COUNT, responseCallbackRegistry.size());
        if (mockServerLogger != null && mockServerLogger.isEnabledForInstance(TRACE)) {
            mockServerLogger.logEvent(
                new LogEntry()
                    .setLogLevel(TRACE)
                    .setMessageFormat("registering response callback " + webSocketCorrelationId)
            );
        }
    }

    public void unregisterResponseCallbackHandler(String webSocketCorrelationId) {
        responseCallbackRegistry.remove(webSocketCorrelationId);
        metrics.set(WEBSOCKET_CALLBACK_RESPONSE_HANDLERS_COUNT, responseCallbackRegistry.size());
        if (mockServerLogger != null && mockServerLogger.isEnabledForInstance(TRACE)) {
            mockServerLogger.logEvent(
                new LogEntry()
                    .setLogLevel(TRACE)
                    .setMessageFormat("unregistering response callback " + webSocketCorrelationId + "")
            );
        }
    }

    public void registerForwardCallbackHandler(String webSocketCorrelationId, WebSocketRequestCallback expectationForwardCallback) {
        forwardCallbackRegistry.put(webSocketCorrelationId, expectationForwardCallback);
        metrics.set(WEBSOCKET_CALLBACK_FORWARD_HANDLERS_COUNT, forwardCallbackRegistry.size());
        if (mockServerLogger != null && mockServerLogger.isEnabledForInstance(TRACE)) {
            mockServerLogger.logEvent(
                new LogEntry()
                    .setLogLevel(TRACE)
                    .setMessageFormat("registering forward callback " + webSocketCorrelationId)
            );
        }
    }

    public void unregisterForwardCallbackHandler(String webSocketCorrelationId) {
        forwardCallbackRegistry.remove(webSocketCorrelationId);
        metrics.set(WEBSOCKET_CALLBACK_FORWARD_HANDLERS_COUNT, forwardCallbackRegistry.size());
        if (mockServerLogger != null && mockServerLogger.isEnabledForInstance(TRACE)) {
            mockServerLogger.logEvent(
                new LogEntry()
                    .setLogLevel(TRACE)
                    .setMessageFormat("unregistering forward callback " + webSocketCorrelationId + "")
            );
        }
    }

    public void registerStreamFrameCallbackHandler(String correlationId, StreamFrameDecisionCallback callback) {
        streamFrameCallbackRegistry.put(correlationId, callback);
        if (mockServerLogger != null && mockServerLogger.isEnabledForInstance(TRACE)) {
            mockServerLogger.logEvent(
                new LogEntry()
                    .setLogLevel(TRACE)
                    .setMessageFormat("registering stream frame callback " + correlationId)
            );
        }
    }

    public void unregisterStreamFrameCallbackHandler(String correlationId) {
        streamFrameCallbackRegistry.remove(correlationId);
        if (mockServerLogger != null && mockServerLogger.isEnabledForInstance(TRACE)) {
            mockServerLogger.logEvent(
                new LogEntry()
                    .setLogLevel(TRACE)
                    .setMessageFormat("unregistering stream frame callback " + correlationId + "")
            );
        }
    }

    /**
     * Send a paused stream frame DTO to the specified client over the callback WebSocket.
     *
     * @param clientId          the target client
     * @param pausedFrameDTO    the paused frame to send
     * @return true if the client was found and the message was sent
     */
    public boolean sendStreamFrameMessage(String clientId, PausedStreamFrameDTO pausedFrameDTO) {
        try {
            if (clientRegistry.containsKey(clientId)) {
                if (mockServerLogger != null && mockServerLogger.isEnabledForInstance(TRACE)) {
                    mockServerLogger.logEvent(
                        new LogEntry()
                            .setLogLevel(TRACE)
                            .setMessageFormat("sending stream frame message {} to client " + clientId)
                            .setArguments(pausedFrameDTO.getCorrelationId())
                    );
                }
                clientRegistry.get(clientId).writeAndFlush(
                    new TextWebSocketFrame(webSocketMessageSerializer.serialize(pausedFrameDTO)));
                return true;
            } else {
                if (mockServerLogger != null && mockServerLogger.isEnabledForInstance(WARN)) {
                    mockServerLogger.logEvent(
                        new LogEntry()
                            .setLogLevel(WARN)
                            .setMessageFormat("client " + clientId + " not found for stream frame message{}")
                            .setArguments(pausedFrameDTO.getCorrelationId())
                    );
                }
                return false;
            }
        } catch (Exception e) {
            throw new WebSocketException("Exception while sending stream frame message to client " + clientId, e);
        }
    }

    /**
     * Tell a client that MockServer resolved one of its paused breakpoint items itself
     * (for example after the breakpoint timeout). Sent only to a client that registered with
     * that capability. Never throws: it is called from timeout and stream-completion paths
     * that must carry on regardless.
     *
     * @return true if the client accepts the notice, was connected, and the notice was written
     */
    public boolean sendBreakpointReleased(String clientId, BreakpointReleasedDTO releasedDTO) {
        Channel channel = clientId != null && breakpointReleasedClients.contains(clientId) ? clientRegistry.get(clientId) : null;
        if (channel == null) {
            return false;
        }
        try {
            channel.writeAndFlush(new TextWebSocketFrame(webSocketMessageSerializer.serialize(releasedDTO)));
            return true;
        } catch (Exception e) {
            if (mockServerLogger != null && mockServerLogger.isEnabledForInstance(WARN)) {
                mockServerLogger.logEvent(
                    new LogEntry()
                        .setLogLevel(WARN)
                        .setMessageFormat("exception sending breakpoint released notice{}to client{}")
                        .setArguments(releasedDTO, clientId)
                        .setThrowable(e)
                );
            }
            return false;
        }
    }

    public boolean sendClientMessage(String clientId, HttpRequest httpRequest, HttpResponse httpResponse) {
        try {
            if (clientRegistry.containsKey(clientId)) {
                if (httpResponse == null) {
                    if (mockServerLogger != null && mockServerLogger.isEnabledForInstance(TRACE)) {
                        mockServerLogger.logEvent(
                            new LogEntry()
                                .setLogLevel(TRACE)
                                .setHttpRequest(httpRequest)
                                .setMessageFormat("sending message{}to client " + clientId)
                                .setArguments(httpRequest)
                        );
                    }
                    clientRegistry.get(clientId).writeAndFlush(new TextWebSocketFrame(webSocketMessageSerializer.serialize(httpRequest)));
                } else {
                    HttpRequestAndHttpResponse httpRequestAndHttpResponse = new HttpRequestAndHttpResponse()
                        .withHttpRequest(httpRequest)
                        .withHttpResponse(httpResponse);
                    if (mockServerLogger != null && mockServerLogger.isEnabledForInstance(TRACE)) {
                        mockServerLogger.logEvent(
                            new LogEntry()
                                .setLogLevel(TRACE)
                                .setHttpRequest(httpRequest)
                                .setMessageFormat("sending message{}to client " + clientId + "")
                                .setArguments(httpRequestAndHttpResponse)
                        );
                    }
                    clientRegistry.get(clientId).writeAndFlush(new TextWebSocketFrame(webSocketMessageSerializer.serialize(httpRequestAndHttpResponse)));
                }
                return true;
            } else {
                if (mockServerLogger != null && mockServerLogger.isEnabledForInstance(WARN)) {
                    mockServerLogger.logEvent(
                        new LogEntry()
                            .setLogLevel(WARN)
                            .setHttpRequest(httpRequest)
                            .setMessageFormat("client " + clientId + " not found for request{}client registry only contains{}")
                            .setArguments(httpRequest, clientRegistry)
                    );
                }
                return false;
            }
        } catch (Exception e) {
            throw new WebSocketException("Exception while sending web socket message " + httpRequest + " to client " + clientId, e);
        }
    }

    public synchronized void reset() {
        forwardCallbackRegistry.clear();
        responseCallbackRegistry.clear();
        streamFrameCallbackRegistry.clear();
        breakpointReleasedClients.clear();
        // copied and cleared under the map's own monitor, which registerClient/unregisterClient mutate it
        // under; closed after, because a channel on this thread's event loop closes at once and its close
        // listener unregisters the client from the map
        Map<String, Channel> clients;
        synchronized (clientRegistry) {
            clients = new LinkedHashMap<>(clientRegistry);
            clientRegistry.clear();
        }
        clients.forEach((clientId, channel) -> {
            LocalCallbackRegistry.unregisterCallback(clientId);
            channel.close();
        });
        clearWebSocketMetrics();
    }
}
