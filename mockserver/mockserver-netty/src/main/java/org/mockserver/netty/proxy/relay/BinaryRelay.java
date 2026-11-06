package org.mockserver.netty.proxy.relay;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ConnectTimeoutException;
import io.netty.handler.ssl.SslHandler;
import io.netty.util.AttributeKey;
import io.netty.util.ReferenceCountUtil;
import io.netty.util.concurrent.ScheduledFuture;
import org.mockserver.configuration.Configuration;
import org.mockserver.httpclient.HeaderLimitExceededException;
import org.mockserver.httpclient.NettyHttpClient;
import org.mockserver.log.model.LogEntry;
import org.mockserver.log.model.SensitiveLogValue;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.model.BinaryMessage;
import org.mockserver.model.BinaryProxyListener;
import org.mockserver.netty.proxy.PostgresqlMessageFramer;
import org.mockserver.scheduler.Scheduler;
import org.mockserver.socket.ChannelReadPause;
import org.mockserver.socket.SocketAddresses;
import org.mockserver.socket.tls.SniHandler;
import org.slf4j.event.Level;

import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.EnumSet;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.RejectedExecutionException;

import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static java.util.concurrent.TimeUnit.NANOSECONDS;
import static org.mockserver.exception.ExceptionHandling.boundedFault;
import static org.mockserver.exception.ExceptionHandling.boundedFaultMessage;
import static org.mockserver.exception.ExceptionHandling.closeOnFlush;
import static org.mockserver.exception.ExceptionHandling.upstreamConnectionFailure;
import static org.mockserver.exception.ExceptionHandling.upstreamHandshakeFailure;
import static org.mockserver.formatting.StringFormatter.formatBytes;
import static org.mockserver.log.model.LogEntry.LogMessageType.FORWARDED_REQUEST;
import static org.mockserver.model.BinaryMessage.bytes;
import static org.mockserver.netty.unification.PortUnificationHandler.isSslEnabledUpstream;

/**
 * One binary client connection's single upstream connection, and the bytes relayed between the two as they arrive.
 * Kept in a channel attribute of the client connection. The upstream connection is registered on the client
 * connection's event loop, so everything here runs on that one thread; only the listener runs elsewhere.
 * <p>
 * Neither connection is read while the other cannot take what is read from it, so what is held is one read each
 * way, each connection's write buffer and the messages not yet reported to the listener.
 * <p>
 * When the client turns TLS on part way through (PostgreSQL's {@code SSLRequest}), MockServer answers the client's
 * handshake as it does for any TLS connection and starts its own handshake with the upstream on the same upstream
 * connection, so what was sent before goes up in the clear and everything after over TLS. A client whose connection
 * was TLS from its first byte gets an upstream connection that is TLS from its first byte too.
 * <p>
 * The upstream connection is made directly or through a tunnel: SOCKS5 for {@code forwardSocksProxy}, {@code CONNECT}
 * for {@code forwardHttpsProxy} or {@code forwardHttpProxy}.
 */
public final class BinaryRelay {

    private static final AttributeKey<BinaryRelay> RELAY = AttributeKey.valueOf("mockserver.binaryRelay");
    /**
     * The client connection is not read while more messages than this wait to be reported to the listener, and is
     * read again once no more than half as many do; the same bound applies to the upstream's reads.
     */
    static final int MAX_PENDING_LISTENER_CALLS = 64;
    /**
     * The client connection is not read while more replies than this wait to be dropped or replaced, and is read
     * again once no more than half as many do.
     */
    static final int MAX_PENDING_REPLACED_REPLIES = 1024;

    /** Why the client connection is not being read; each is one hold on {@link ChannelReadPause}. */
    private enum ClientHold {
        UPSTREAM_CONNECTING, UPSTREAM_NOT_WRITABLE, LISTENER_BEHIND, UPSTREAM_HANDSHAKING, CLIENT_NOT_WRITABLE, REPLIES_PENDING
    }

    private final Channel client;
    private final InetSocketAddress target;
    private final Configuration configuration;
    private final MockServerLogger mockServerLogger;
    private final Scheduler scheduler;
    private final NettyHttpClient httpClient;
    private final BinaryProxyListener listener;
    private final boolean listenerHearsUpstreamMessages;
    private final EnumSet<ClientHold> clientHolds = EnumSet.noneOf(ClientHold.class);
    private final Deque<Exchange> waitingForConnect = new ArrayDeque<>(1);
    private final boolean clientStartedWithTls;
    // null without a protocol's framing: nothing then says where a reply ends
    private final PostgresqlReplies replies;
    private final ToClient toClient = new ToClient();
    private Channel upstream;
    private SslHandler upstreamTls;
    // the upgrade came before the connect completed: how many waiting messages were sent before it, in the clear
    private int clearBeforeUpgrade = -1;
    private boolean connectStarted;
    private boolean lookingUp;
    private ScheduledFuture<?> lookUpTimeout;
    private boolean connected;
    private boolean clientClosed;
    // the upstream closed while the client was open: its close then closes the client before a handshake reports it
    private boolean upstreamClosedFirst;
    private boolean finished;
    private boolean upstreamHeldForClient;
    private boolean upstreamHeldForListener;
    private Exchange latest;
    private String latestCorrelationId;
    private int pendingListenerCalls;
    private int pendingUpstreamMessageCalls;
    // the listener is told of a connection's messages one at a time, in order
    private CompletableFuture<Void> previousListenerCall;
    private ScheduledFuture<?> upstreamStallCheck;
    private long bytesWaitingAtLastStallCheck;

    private BinaryRelay(Channel client, InetSocketAddress target, Configuration configuration, MockServerLogger mockServerLogger, Scheduler scheduler, NettyHttpClient httpClient, BinaryProxyListener listener) {
        this.client = client;
        this.target = target;
        this.configuration = configuration;
        this.mockServerLogger = mockServerLogger;
        this.scheduler = scheduler;
        this.httpClient = httpClient;
        this.listener = listener;
        this.listenerHearsUpstreamMessages = overridesOnUpstreamMessage(listener);
        this.clientStartedWithTls = isSslEnabledUpstream(client);
        this.replies = client.pipeline().get(PostgresqlMessageFramer.class) != null ? new PostgresqlReplies() : null;
    }

    /** A listener that keeps the interface's empty default is not called for each upstream read. */
    private static boolean overridesOnUpstreamMessage(BinaryProxyListener listener) {
        if (listener == null) {
            return false;
        }
        try {
            return listener.getClass().getMethod("onUpstreamMessage", BinaryMessage.class, SocketAddress.class, SocketAddress.class).getDeclaringClass() != BinaryProxyListener.class;
        } catch (NoSuchMethodException | SecurityException cannotTell) {
            return true;
        }
    }

    /**
     * Relays a message read from the client to its upstream connection, opening that connection for the first one,
     * with the upstream's reply to this message relayed, dropped or replaced. A reply is dropped or replaced only on a
     * connection whose replies are tracked ({@link #tracksReplies(Channel)}); on any other it is relayed. Must be
     * called on the client connection's event loop.
     */
    public static void forward(ChannelHandlerContext ctx, BinaryMessage binaryRequest, String logCorrelationId, UpstreamReply reply, InetSocketAddress target, Configuration configuration, MockServerLogger mockServerLogger, Scheduler scheduler, NettyHttpClient httpClient, BinaryProxyListener listener) {
        relayOf(ctx, target, configuration, mockServerLogger, scheduler, httpClient, listener).fromClient(binaryRequest, logCorrelationId, reply);
    }

    /**
     * Whether this connection has a relay, which then holds the client back itself when MockServer's own reply leaves
     * it not writable ({@link #answeredLocally}).
     */
    public static boolean hasRelay(Channel client) {
        return client.hasAttr(RELAY) && client.attr(RELAY).get() != null;
    }

    /**
     * Whether this connection's relay knows where the upstream's reply to each message ends, so a reply can be
     * dropped or replaced: one relayed with a protocol's framing (binaryMessageFraming POSTGRESQL) whose tracking has
     * not been given up on a malformed upstream message. Must be called on the client connection's event loop.
     */
    public static boolean tracksReplies(Channel client) {
        BinaryRelay relay = client.hasAttr(RELAY) ? client.attr(RELAY).get() : null;
        return relay != null && relay.replies != null && !relay.replies.lost();
    }

    /**
     * Makes this connection's relay, if it has none yet, without opening its upstream connection, so that a message
     * MockServer answers itself (matchBinaryExpectationsWhenProxying) is ordered and held back by it. Must be
     * called on the client connection's event loop.
     */
    public static void prepare(ChannelHandlerContext ctx, InetSocketAddress target, Configuration configuration, MockServerLogger mockServerLogger, Scheduler scheduler, NettyHttpClient httpClient, BinaryProxyListener listener) {
        relayOf(ctx, target, configuration, mockServerLogger, scheduler, httpClient, listener);
    }

    /**
     * Opens the upstream connection of a client that has sent nothing yet, so that a server that speaks first
     * (forwardBinaryServerFirstWaitMillis) is heard: what it sends is relayed to the client as any upstream read is.
     * Must be called on the client connection's event loop.
     */
    public static void openBeforeClientSpeaks(ChannelHandlerContext ctx, InetSocketAddress target, Configuration configuration, MockServerLogger mockServerLogger, Scheduler scheduler, NettyHttpClient httpClient, BinaryProxyListener listener) {
        BinaryRelay relay = relayOf(ctx, target, configuration, mockServerLogger, scheduler, httpClient, listener);
        if (!relay.finished && !relay.connectStarted) {
            relay.connect();
        }
    }

    /**
     * Called once MockServer has written its own reply to a message read from the client, which is not relayed and
     * is not reported to the listener. Nothing orders that reply against what the upstream still owes an earlier
     * message, so that case is logged; and the client is not read while it cannot take more.
     */
    public static void answeredLocally(Channel client, String logCorrelationId) {
        BinaryRelay relay = client.hasAttr(RELAY) ? client.attr(RELAY).get() : null;
        if (relay != null) {
            relay.repliedLocally(logCorrelationId);
        }
    }

    private static BinaryRelay relayOf(ChannelHandlerContext ctx, InetSocketAddress target, Configuration configuration, MockServerLogger mockServerLogger, Scheduler scheduler, NettyHttpClient httpClient, BinaryProxyListener listener) {
        Channel client = ctx.channel();
        BinaryRelay relay = client.attr(RELAY).get();
        if (relay == null) {
            relay = new BinaryRelay(client, target, configuration, mockServerLogger, scheduler, httpClient, listener);
            client.attr(RELAY).set(relay);
        }
        return relay;
    }

    /**
     * Ends the upstream connection once the client's has closed, after what the client sent has been written to it.
     * Called from the client connection's {@code channelInactive}, not its close future, which completes before the
     * handlers in front of this one hand over the bytes they still hold (a possible handshake start, a gathered
     * message). A connection without a relay is left alone.
     */
    public static void clientInactive(Channel client) {
        BinaryRelay relay = client.hasAttr(RELAY) ? client.attr(RELAY).get() : null;
        if (relay != null) {
            relay.clientClosed();
        }
    }

    /**
     * The client has started TLS part way through: its handshake with MockServer is under way, so the upstream
     * connection starts its own. Called when the client's server certificate has been chosen (Netty's
     * {@code SniCompletionEvent}), before anything the client sends over TLS is decrypted. A connection without a
     * relay is left alone.
     */
    public static void clientStartedTls(Channel client) {
        BinaryRelay relay = client.hasAttr(RELAY) ? client.attr(RELAY).get() : null;
        if (relay != null && !relay.clientStartedWithTls) {
            relay.upgradeUpstreamToTls();
        }
    }

    /**
     * Reads the upstream again once the client can take more. A connection without a relay is left alone.
     */
    public static void clientWritabilityChanged(Channel client) {
        BinaryRelay relay = client.hasAttr(RELAY) ? client.attr(RELAY).get() : null;
        if (relay != null && client.isWritable()) {
            relay.release(ClientHold.CLIENT_NOT_WRITABLE);
            if (relay.upstreamHeldForClient) {
                relay.upstreamHeldForClient = false;
                ChannelReadPause.resume(relay.upstream);
            }
        }
    }

    /** A forwarded message that has had no upstream read since is {@link #latest}; each is warned about once. */
    private void repliedLocally(String logCorrelationId) {
        // a message answered here and also forwarded is latest itself: its own reply is not what it overtakes
        if (latest != null && !latest.overtakenWarned && !logCorrelationId.equals(latest.correlationId) && mockServerLogger.isEnabledForInstance(Level.WARN)) {
            latest.overtakenWarned = true;
            mockServerLogger.logEvent(
                new LogEntry()
                    .setLogLevel(Level.WARN)
                    .setCorrelationId(logCorrelationId)
                    .setMessageFormat("binary mock response written to binary connection from:{}while forwarded binary request:{}has had no response from:{}the two may reach the client out of order")
                    .setArguments(client.remoteAddress(), SensitiveLogValue.of(formatBytes(latest.request.getBytes(), configuration.maxLoggedBodyBytes())), target)
            );
        }
        if (!client.isWritable()) {
            hold(ClientHold.CLIENT_NOT_WRITABLE);
        }
    }

    private void fromClient(BinaryMessage binaryRequest, String logCorrelationId, UpstreamReply reply) {
        latestCorrelationId = logCorrelationId;
        if (finished || !connectStarted && !connect()) {
            return;
        }
        if (isSslEnabledUpstream(client)) {
            // a backstop for the event: anything the client sends over TLS goes upstream over TLS
            upgradeUpstreamToTls();
            if (finished) {
                return;
            }
            if (upstreamTls == null || !upstreamTls.handshakeFuture().isSuccess()) {
                hold(ClientHold.UPSTREAM_HANDSHAKING);
            }
        }
        Exchange exchange = new Exchange(binaryRequest, logCorrelationId);
        if (replies != null && !replies.lost()) {
            long dueNanos = client.eventLoop().ticker().nanoTime() + MILLISECONDS.toNanos(reply.delayMillis());
            replies.forwarded(binaryRequest, reply, logCorrelationId, dueNanos, toClient);
            if (replies.notRelayed() > MAX_PENDING_REPLACED_REPLIES) {
                hold(ClientHold.REPLIES_PENDING);
            }
        }
        if (latest != null) {
            latest.response.complete(null);
        }
        latest = exchange;
        // its response is an upstream read, which a listener call waiting for it must not be kept from
        releaseUpstreamForListener();
        reportToListener(exchange);
        if (connected) {
            writeToUpstream(exchange);
        } else {
            waitingForConnect.add(exchange);
        }
    }

    private boolean connect() {
        connectStarted = true;
        boolean tunnelProxyByName = httpClient.binaryRelayTunnelProxyNeedsLookUp(target, clientStartedWithTls);
        if (!target.isUnresolved() && !tunnelProxyByName) {
            return connectTo(target, null);
        }
        // a lookup blocks, and the event loop serves other connections too
        hold(ClientHold.UPSTREAM_CONNECTING);
        lookingUp = true;
        try {
            scheduler.scheduleLocalCallback(() -> {
                InetSocketAddress found = null;
                InetSocketAddress tunnelProxyFound = null;
                Exception failure = null;
                try {
                    found = target.isUnresolved() ? httpClient.lookUpBinaryRelayTarget(target, clientStartedWithTls) : target;
                    if (tunnelProxyByName) {
                        tunnelProxyFound = httpClient.lookUpBinaryRelayTunnelProxy(target, clientStartedWithTls);
                    }
                } catch (Exception lookUpFailed) {
                    failure = lookUpFailed;
                }
                InetSocketAddress address = found;
                InetSocketAddress tunnelProxy = tunnelProxyFound;
                Exception cause = failure;
                onClientEventLoop(() -> lookedUp(address, tunnelProxy, cause));
            }, false);
        } catch (RejectedExecutionException serverStopping) {
            lookingUp = false;
            connectCompleted(serverStopping);
            return false;
        }
        Long timeoutMillis = configuration.socketConnectionTimeoutInMillis();
        if (lookingUp && timeoutMillis != null && timeoutMillis > 0) {
            // the resolver's own limit can be far longer; the lookup thread is left to finish, its answer ignored
            lookUpTimeout = client.eventLoop().schedule(() -> {
                if (lookingUp) {
                    lookingUp = false;
                    connectCompleted(new ConnectTimeoutException("looking up " + (target.isUnresolved() ? target.getHostString() : "the upstream proxy") + " took longer than socketConnectionTimeoutInMillis: " + timeoutMillis + "ms"));
                }
            }, timeoutMillis, MILLISECONDS);
        }
        return !finished;
    }

    private void lookedUp(InetSocketAddress address, InetSocketAddress tunnelProxy, Exception failure) {
        if (!lookingUp) {
            // timed out already
            return;
        }
        lookingUp = false;
        if (lookUpTimeout != null) {
            lookUpTimeout.cancel(false);
            lookUpTimeout = null;
        }
        if (finished) {
            return;
        }
        if (failure instanceof RuntimeException) {
            // refused, as connectBinaryRelay refuses a target it may not connect to
            refuse(failure.getMessage());
        } else if (failure != null) {
            connectCompleted(failure);
        } else {
            connectTo(address, tunnelProxy);
        }
    }

    /**
     * @param tunnelProxy the address of the upstream proxy to tunnel through, looked up off the event loop because it
     *                    is given by name, or null to connect as configured
     */
    private boolean connectTo(InetSocketAddress address, InetSocketAddress tunnelProxy) {
        SslHandler tlsFromTheStart = null;
        if (clientStartedWithTls) {
            // made before connecting, so a connection that could not be encrypted is never opened
            tlsFromTheStart = newUpstreamTls(client);
            if (tlsFromTheStart == null) {
                return false;
            }
        }
        ChannelFuture connect;
        try {
            BinaryRelayUpstreamHandler upstreamHandler = new BinaryRelayUpstreamHandler(this, mockServerLogger);
            connect = tunnelProxy != null
                ? httpClient.connectBinaryRelay(client.eventLoop(), address, clientStartedWithTls, upstreamHandler, tunnelProxy)
                : httpClient.connectBinaryRelay(client.eventLoop(), address, clientStartedWithTls, upstreamHandler);
        } catch (RuntimeException notPermitted) {
            releaseUnused(tlsFromTheStart);
            refuse(notPermitted.getMessage());
            return false;
        }
        if (connect == null) {
            releaseUnused(tlsFromTheStart);
            refuse("the forward client opened no upstream connection");
            return false;
        }
        upstream = connect.channel();
        if (connect.isDone() && !connect.isSuccess()) {
            // a pipeline whose channel failed already, perhaps unregistered, would never release the handler
            releaseUnused(tlsFromTheStart);
        } else if (tlsFromTheStart != null) {
            // in place before the connect completes, so the first byte upstream is the handshake's
            addUpstreamTls(tlsFromTheStart);
        }
        hold(ClientHold.UPSTREAM_CONNECTING);
        connect.addListener(future -> onClientEventLoop(() -> connectCompleted(future.cause())));
        upstream.closeFuture().addListener(future -> upstreamClosed());
        return !finished;
    }

    private void connectCompleted(Throwable failure) {
        if (failure != null) {
            // a proxy's CONNECT answer over maxHeaderSize was logged as a warning where it was refused
            Level level = HeaderLimitExceededException.in(failure) != null ? Level.INFO : Level.WARN;
            if (mockServerLogger.isEnabledForInstance(level)) {
                mockServerLogger.logEvent(
                    new LogEntry()
                        .setLogLevel(level)
                        .setCorrelationId(latestCorrelationId)
                        .setMessageFormat("unable to connect to:{}for binary connection from:{}closing connection:{}")
                        .setArguments(target, client.remoteAddress(), boundedFaultMessage(failure))
                        .setThrowable(upstreamConnectionFailure(failure) ? null : boundedFault(failure))
                );
            }
            failWaitingForConnect(failure);
            // not left to the failed channel's close: one never registered has a close future that never completes
            finished = true;
            releaseEveryHold();
            closeOnFlush(client);
            return;
        }
        connected = true;
        release(ClientHold.UPSTREAM_CONNECTING);
        for (Exchange exchange; (exchange = waitingForConnect.poll()) != null; ) {
            if (clearBeforeUpgrade-- == 0) {
                // what was read before the upgrade goes in the clear; the handler is added only now, after it
                startUpstreamTls();
            }
            writeToUpstream(exchange);
        }
        if (clearBeforeUpgrade >= 0) {
            startUpstreamTls();
        }
        clearBeforeUpgrade = -1;
        if (clientClosed) {
            endUpstream();
        }
    }

    /** A connect that failed before its channel was registered completes on Netty's global executor, not here. */
    private void onClientEventLoop(Runnable task) {
        if (client.eventLoop().inEventLoop()) {
            task.run();
        } else {
            try {
                client.eventLoop().execute(task);
            } catch (RejectedExecutionException serverStopping) {
                // the event loop has shut down, and the client's connection with it
            }
        }
    }

    private void writeToUpstream(Exchange exchange) {
        upstream.writeAndFlush(Unpooled.wrappedBuffer(exchange.request.getBytes())).addListener(written -> {
            if (written.isSuccess()) {
                exchange.written = true;
            } else {
                exchange.response.completeExceptionally(written.cause());
                RelayLegClose.now(upstream);
            }
        });
        if (upstreamStallCheck != null) {
            // what this write added is not the upstream failing to take what was already waiting
            bytesWaitingAtLastStallCheck = upstream.bytesBeforeWritable();
        }
        upstreamWritabilityChanged();
    }

    /**
     * Starts MockServer's TLS handshake with the upstream on the connection the relay already has. A connection
     * still connecting starts it once what was waiting for the connect has been written in the clear.
     */
    private void upgradeUpstreamToTls() {
        if (upstreamTls != null || clearBeforeUpgrade >= 0 || finished || clientClosed) {
            return;
        }
        if (mockServerLogger.isEnabledForInstance(Level.DEBUG)) {
            mockServerLogger.logEvent(
                new LogEntry()
                    .setLogLevel(Level.DEBUG)
                    .setCorrelationId(latestCorrelationId)
                    .setMessageFormat("binary connection from:{}turned TLS on, upgrading its upstream connection to:{}to TLS")
                    .setArguments(client.remoteAddress(), target)
            );
        }
        if (connected) {
            startUpstreamTls();
        } else {
            clearBeforeUpgrade = waitingForConnect.size();
        }
    }

    private void startUpstreamTls() {
        SslHandler sslHandler = newUpstreamTls(upstream);
        if (sslHandler != null) {
            addUpstreamTls(sslHandler);
        }
    }

    /** Null, with both connections closing and the fault logged, if it cannot be made. */
    private SslHandler newUpstreamTls(Channel allocatingFor) {
        try {
            return httpClient.newBinaryRelaySslHandler(allocatingFor.alloc(), target, SniHandler.getSniHostname(client));
        } catch (RuntimeException cannotCreate) {
            upstreamTlsFailed(cannotCreate);
            return null;
        }
    }

    private void addUpstreamTls(SslHandler sslHandler) {
        upstreamTls = sslHandler;
        // nearest the socket after a tunnel's handler: the relay's handler then only ever sees what was decrypted
        if (upstream.pipeline().get(NettyHttpClient.BINARY_RELAY_TUNNEL) != null) {
            upstream.pipeline().addAfter(NettyHttpClient.BINARY_RELAY_TUNNEL, "binary-relay-tls", sslHandler);
        } else {
            upstream.pipeline().addFirst("binary-relay-tls", sslHandler);
        }
        sslHandler.handshakeFuture().addListener(handshake -> {
            if (handshake.isSuccess()) {
                release(ClientHold.UPSTREAM_HANDSHAKING);
            } else if (connected) {
                upstreamTlsFailed(upstreamHandshakeFailure(handshake.cause()));
            }
            // else the connect failed, which is what is reported, and the closed connection closes the client
        });
    }

    private static void releaseUnused(SslHandler sslHandler) {
        if (sslHandler != null) {
            ReferenceCountUtil.release(sslHandler.engine());
        }
    }

    private void upstreamTlsFailed(Throwable cause) {
        if ((!clientClosed || upstreamClosedFirst) && mockServerLogger.isEnabledForInstance(Level.WARN)) {
            mockServerLogger.logEvent(
                new LogEntry()
                    .setLogLevel(Level.WARN)
                    .setCorrelationId(latestCorrelationId)
                    .setMessageFormat("unable to start TLS with upstream:{}for binary connection from:{}closing both:{}")
                    .setArguments(target, client.remoteAddress(), boundedFaultMessage(cause))
                    .setThrowable(boundedFault(cause))
            );
        }
        if (upstream != null) {
            // closing the upstream connection closes the client's
            RelayLegClose.now(upstream);
        } else {
            finished = true;
            closeOnFlush(client);
        }
    }

    /** False until the connect, through a tunnel too, has succeeded; a failure before then is logged here. */
    boolean upstreamConnected() {
        return connected;
    }

    /** True from the upgrade until MockServer's handshake with the upstream has succeeded; its faults are logged here. */
    boolean upstreamTlsNotEstablished() {
        return clearBeforeUpgrade >= 0 || upstreamTls != null && !upstreamTls.handshakeFuture().isSuccess();
    }

    /**
     * One upstream read: logged, given to the listener when it has one that takes it, and written to the client.
     * The read's bytes are copied only for the listener; the log entry is formatted from the buffer, and the buffer
     * itself is what is written to the client.
     */
    void fromUpstream(ByteBuf read) {
        Exchange answered = latest != null && latest.written ? latest : null;
        boolean listenerTakesIt = answered != null ? listener != null : listenerHearsUpstreamMessages;
        BinaryMessage binaryResponse = listenerTakesIt ? bytes(ByteBufUtil.getBytes(read)) : null;
        boolean tracked = replies != null && !replies.lost();
        if (tracked) {
            // logs and writes each piece as the reply it belongs to says
            toClient.readStarted(answered, read);
            replies.read(read, toClient);
            toClient.readEnded();
        } else {
            logRelayed(read, answered);
        }
        if (answered != null) {
            latest = null;
            answered.response.complete(binaryResponse);
        } else if (listenerTakesIt) {
            reportUpstreamMessage(binaryResponse);
        }
        if (tracked) {
            if (replies.lost()) {
                trackingLost();
            } else if (replies.notRelayed() <= MAX_PENDING_REPLACED_REPLIES / 2) {
                release(ClientHold.REPLIES_PENDING);
            }
        } else if (client.isActive()) {
            // behind any replacement still waiting from before tracking was given up
            toClient.relay(read.retain());
        }
    }

    private void logRelayed(ByteBuf read, Exchange answered) {
        LogEntry logEntry = new LogEntry()
            .setType(FORWARDED_REQUEST)
            .setLogLevel(Level.INFO)
            .setCorrelationId(latestCorrelationId);
        if (answered != null) {
            logEntry
                .setMessageFormat("returning binary response:{}from:{}for forwarded binary request:{}")
                .setArguments(SensitiveLogValue.of(formatBytes(read, configuration.maxLoggedBodyBytes())), target, SensitiveLogValue.of(formatBytes(answered.request.getBytes(), configuration.maxLoggedBodyBytes())));
        } else {
            logEntry
                .setMessageFormat("returning binary response:{}from:{}")
                .setArguments(SensitiveLogValue.of(formatBytes(read, configuration.maxLoggedBodyBytes())), target);
        }
        mockServerLogger.logEvent(logEntry);
    }

    /** Takes ownership of {@code bytes}. */
    private void writeToClient(ByteBuf bytes) {
        client.write(bytes);
        if (!client.isWritable() && !upstreamHeldForClient) {
            upstreamHeldForClient = true;
            ChannelReadPause.pause(upstream);
        }
    }

    private void trackingLost() {
        release(ClientHold.REPLIES_PENDING);
        if (mockServerLogger.isEnabledForInstance(Level.WARN)) {
            mockServerLogger.logEvent(
                new LogEntry()
                    .setLogLevel(Level.WARN)
                    .setCorrelationId(latestCorrelationId)
                    .setMessageFormat("upstream:{}of binary connection from:{}sent a message PostgreSQL message framing (binaryMessageFraming) cannot read; its replies are relayed as they are from now on, none dropped or replaced")
                    .setArguments(target, client.remoteAddress())
            );
        }
    }

    void upstreamReadComplete() {
        client.flush();
    }

    void upstreamWritabilityChanged() {
        if (finished || !upstream.isActive()) {
            return;
        }
        if (upstream.isWritable()) {
            release(ClientHold.UPSTREAM_NOT_WRITABLE);
            stopUpstreamStallCheck();
        } else {
            hold(ClientHold.UPSTREAM_NOT_WRITABLE);
            long timeoutMillis = configuration.responseWriteStallTimeoutMillis();
            if (upstreamStallCheck == null && timeoutMillis > 0) {
                bytesWaitingAtLastStallCheck = upstream.bytesBeforeWritable();
                upstreamStallCheck = client.eventLoop().schedule(() -> checkUpstreamStall(timeoutMillis), timeoutMillis, MILLISECONDS);
            }
        }
    }

    /**
     * Without this an upstream that stops taking bytes would hold its client, which is never timed out, for good.
     */
    private void checkUpstreamStall(long timeoutMillis) {
        upstreamStallCheck = null;
        if (finished || !upstream.isActive() || upstream.isWritable()) {
            return;
        }
        long bytesWaiting = upstream.bytesBeforeWritable();
        if (bytesWaiting < bytesWaitingAtLastStallCheck) {
            bytesWaitingAtLastStallCheck = bytesWaiting;
            upstreamStallCheck = client.eventLoop().schedule(() -> checkUpstreamStall(timeoutMillis), timeoutMillis, MILLISECONDS);
            return;
        }
        if (mockServerLogger.isEnabledForInstance(Level.WARN)) {
            mockServerLogger.logEvent(
                new LogEntry()
                    .setLogLevel(Level.WARN)
                    .setCorrelationId(latestCorrelationId)
                    .setMessageFormat("closing binary connection from:{}because:{}took none of the bytes waiting for it for:{}ms (responseWriteStallTimeoutMillis)")
                    .setArguments(client.remoteAddress(), target, timeoutMillis)
            );
        }
        RelayLegClose.now(upstream);
    }

    private void stopUpstreamStallCheck() {
        if (upstreamStallCheck != null) {
            upstreamStallCheck.cancel(false);
            upstreamStallCheck = null;
        }
    }

    /**
     * The listener is the user's code and may wait on the response, so it runs off the event loop. One connection's
     * messages are reported one at a time, in arrival order, and those not yet reported are counted, so a listener
     * slower than the client slows the client down.
     */
    private void reportToListener(Exchange exchange) {
        if (listener == null) {
            return;
        }
        if (++pendingListenerCalls > MAX_PENDING_LISTENER_CALLS) {
            hold(ClientHold.LISTENER_BEHIND);
        }
        SocketAddress clientAddress = SocketAddresses.clientAddress(client);
        callListener(() -> listener.onProxy(exchange.request, exchange.response, target, clientAddress), false);
    }

    /**
     * A listener slower than the upstream slows the upstream down, as one slower than the client does the client,
     * except while a message is waiting for its response: the upstream read that answers it is never held back from
     * a listener call that may be waiting for it.
     */
    private void reportUpstreamMessage(BinaryMessage upstreamMessage) {
        if (++pendingUpstreamMessageCalls > MAX_PENDING_LISTENER_CALLS && latest == null && !upstreamHeldForListener) {
            upstreamHeldForListener = true;
            ChannelReadPause.pause(upstream);
        }
        SocketAddress clientAddress = SocketAddresses.clientAddress(client);
        callListener(() -> listener.onUpstreamMessage(upstreamMessage, target, clientAddress), true);
    }

    private void callListener(Runnable onListener, boolean upstreamMessage) {
        CompletableFuture<Void> called = new CompletableFuture<>();
        CompletableFuture<Void> previousCalled = previousListenerCall;
        previousListenerCall = called;
        Runnable call = () -> scheduler.scheduleLocalCallback(() -> {
            Throwable thrown = null;
            try {
                onListener.run();
            } catch (Throwable throwable) {
                thrown = throwable;
            } finally {
                called.complete(null);
                listenerReturned(thrown, upstreamMessage);
            }
        }, false);
        if (previousCalled == null) {
            call.run();
        } else {
            previousCalled.thenRun(call);
        }
    }

    private void listenerReturned(Throwable thrown, boolean upstreamMessage) {
        try {
            client.eventLoop().execute(() -> {
                if (upstreamMessage) {
                    if (--pendingUpstreamMessageCalls <= MAX_PENDING_LISTENER_CALLS / 2) {
                        releaseUpstreamForListener();
                    }
                } else if (--pendingListenerCalls <= MAX_PENDING_LISTENER_CALLS / 2) {
                    release(ClientHold.LISTENER_BEHIND);
                }
                if (thrown != null) {
                    // reported and closed as a listener that throws is without a relay
                    client.pipeline().fireExceptionCaught(thrown);
                    closeOnFlush(client);
                }
            });
        } catch (RejectedExecutionException serverStopping) {
            // the event loop has shut down, and the connection with it
        }
    }

    private void releaseUpstreamForListener() {
        if (upstreamHeldForListener) {
            upstreamHeldForListener = false;
            ChannelReadPause.resume(upstream);
        }
    }

    private void refuse(String reason) {
        if (mockServerLogger.isEnabledForInstance(Level.WARN)) {
            mockServerLogger.logEvent(
                new LogEntry()
                    .setLogLevel(Level.WARN)
                    .setCorrelationId(latestCorrelationId)
                    .setMessageFormat("closing binary connection from:{}which cannot be forwarded to:{}because:{}")
                    .setArguments(client.remoteAddress(), target, reason)
            );
        }
        finished = true;
        // messages read while the target was looked up
        failWaitingForConnect(new IllegalStateException(reason));
        releaseEveryHold();
        if (upstream != null) {
            RelayLegClose.now(upstream);
        }
        closeOnFlush(client);
    }

    private void failWaitingForConnect(Throwable failure) {
        for (Exchange notSent : waitingForConnect) {
            notSent.response.completeExceptionally(failure);
        }
        waitingForConnect.clear();
        latest = null;
    }

    private void upstreamClosed() {
        upstreamClosedFirst = !clientClosed;
        finished = true;
        stopUpstreamStallCheck();
        // a replacement waiting for its delay, and what waits behind it, is written now: the upstream has ended
        toClient.writeEverythingHeld();
        releaseEveryHold();
        // a connection that never connected fails its messages when its connect completes, before or after this
        if (connected && latest != null) {
            latest.response.complete(null);
            latest = null;
        }
        // what the upstream sent before it closed is delivered first
        closeOnFlush(client);
    }

    private void clientClosed() {
        if (clientClosed) {
            return;
        }
        clientClosed = true;
        toClient.discardEverythingHeld();
        // the upstream is read again so that its close is seen
        releaseEveryHold();
        if (connected && !finished) {
            // what the client sent before it closed is delivered first
            endUpstream();
        }
    }

    /**
     * Ends the upstream connection's output once what was written to it has been sent: after a TLS
     * {@code close_notify} when MockServer's handshake with the upstream has succeeded.
     */
    private void endUpstream() {
        if (upstreamTls != null && upstreamTls.handshakeFuture().isSuccess() && upstream.isActive()) {
            RelayLegClose.afterWritten(upstreamTls.closeOutbound());
        } else {
            RelayLegClose.afterFlush(upstream);
        }
    }

    private void hold(ClientHold reason) {
        if (!clientClosed && !finished && clientHolds.add(reason)) {
            ChannelReadPause.pause(client);
        }
    }

    private void release(ClientHold reason) {
        if (clientHolds.remove(reason)) {
            ChannelReadPause.resume(client);
        }
    }

    private void releaseEveryHold() {
        for (ClientHold reason : ClientHold.values()) {
            release(reason);
        }
        if (upstreamHeldForClient) {
            upstreamHeldForClient = false;
            ChannelReadPause.resume(upstream);
        }
        releaseUpstreamForListener();
        toClient.releaseUpstream();
    }

    /** A message read from the client and the future its listener call is given. */
    private static final class Exchange {
        private final BinaryMessage request;
        private final String correlationId;
        private final CompletableFuture<BinaryMessage> response = new CompletableFuture<>();
        private boolean written;
        private boolean overtakenWarned;

        private Exchange(BinaryMessage request, String correlationId) {
            this.request = request;
            this.correlationId = correlationId;
        }
    }

    /**
     * The upstream's bytes on their way to the client when its replies are tracked: relayed, dropped, or replaced once
     * the reply has ended. A replacement whose delay has not passed holds back everything after it, so the client
     * gets the upstream's bytes and the replacements in the upstream's order; while it waits the upstream is not read,
     * so what is held is at most one read.
     * <p>
     * Ownership: a dropped piece is never retained, so nothing of it outlives the read. A relayed piece is retained
     * once, as its own slice, and that reference is given to the client's write or, while something waits ahead of
     * it, kept in {@link #held} until it is written or released by {@link #discardEverythingHeld()}.
     */
    private final class ToClient implements PostgresqlReplies.Output {
        private final Deque<Object> held = new ArrayDeque<>();
        private ScheduledFuture<?> timer;
        private boolean upstreamHeld;
        private Exchange answered;
        private ByteBuf currentRead;
        private int relayedFrom = -1;
        private int relayedTo;

        void readStarted(Exchange answered, ByteBuf read) {
            this.answered = answered;
            this.currentRead = read;
        }

        void readEnded() {
            writeRelayed();
            answered = null;
            currentRead = null;
        }

        @Override
        public void bytes(PostgresqlReplies.Reply reply, ByteBuf read, int index, int length) {
            if (length == 0) {
                return;
            }
            if (reply == null || reply.handling().relayed()) {
                if (relayedFrom >= 0 && relayedTo == index) {
                    relayedTo += length;
                } else {
                    writeRelayed();
                    relayedFrom = index;
                    relayedTo = index + length;
                }
                return;
            }
            writeRelayed();
            if (mockServerLogger.isEnabledForInstance(Level.INFO)) {
                mockServerLogger.logEvent(
                    new LogEntry()
                        .setLogLevel(Level.INFO)
                        .setCorrelationId(reply.correlationId())
                        .setMessageFormat("dropping binary response:{}from:{}for binary request:{}as a binary expectation answers or replaces it")
                        .setArguments(SensitiveLogValue.of(formatBytes(read.slice(index, length), configuration.maxLoggedBodyBytes())), target, SensitiveLogValue.of(formatBytes(reply.request().getBytes(), configuration.maxLoggedBodyBytes())))
                );
            }
        }

        @Override
        public void ended(PostgresqlReplies.Reply reply) {
            byte[] replacement = reply.handling().replacement();
            if (replacement == null || !client.isActive()) {
                return;
            }
            // what was relayed before the end goes first
            writeRelayed();
            Replacement pendingReplacement = new Replacement(reply, replacement);
            if (held.isEmpty() && client.eventLoop().ticker().nanoTime() >= reply.dueNanos()) {
                write(pendingReplacement);
                if (currentRead == null) {
                    // not inside an upstream read, whose read-complete would flush it: a message without a reply
                    client.flush();
                }
            } else {
                held.add(pendingReplacement);
                holdUpstream();
                if (held.size() == 1) {
                    writeDue();
                }
            }
        }

        /** Takes ownership of {@code bytes}: written now, or after what is held. */
        void relay(ByteBuf bytes) {
            if (held.isEmpty()) {
                if (client.isActive()) {
                    writeToClient(bytes);
                } else {
                    bytes.release();
                }
            } else {
                held.add(bytes);
                holdUpstream();
            }
        }

        private void writeRelayed() {
            if (relayedFrom < 0 || currentRead == null) {
                return;
            }
            int from = relayedFrom;
            int to = relayedTo;
            relayedFrom = -1;
            boolean whole = from == currentRead.readerIndex() && to == currentRead.writerIndex();
            logRelayed(whole ? currentRead : currentRead.slice(from, to - from), answered);
            answered = null;
            relay(whole ? currentRead.retain() : currentRead.retainedSlice(from, to - from));
        }

        private void write(Replacement replacement) {
            if (!client.isActive()) {
                return;
            }
            if (mockServerLogger.isEnabledForInstance(Level.INFO)) {
                mockServerLogger.logEvent(
                    new LogEntry()
                        .setType(FORWARDED_REQUEST)
                        .setLogLevel(Level.INFO)
                        .setCorrelationId(replacement.reply.correlationId())
                        .setMessageFormat("returning binary mock response:{}in place of the response from:{}for binary request:{}")
                        .setArguments(SensitiveLogValue.of(formatBytes(replacement.bytes, configuration.maxLoggedBodyBytes())), target, SensitiveLogValue.of(formatBytes(replacement.reply.request().getBytes(), configuration.maxLoggedBodyBytes())))
                );
            }
            writeToClient(Unpooled.wrappedBuffer(replacement.bytes));
        }

        private void writeDue() {
            timer = null;
            Object next;
            while ((next = held.peek()) != null) {
                if (next instanceof Replacement) {
                    long remaining = ((Replacement) next).reply.dueNanos() - client.eventLoop().ticker().nanoTime();
                    if (remaining > 0 && !finished) {
                        timer = client.eventLoop().schedule(this::writeDue, remaining, NANOSECONDS);
                        return;
                    }
                    held.poll();
                    write((Replacement) next);
                } else {
                    held.poll();
                    // a write to a closed channel releases what it is given
                    writeToClient((ByteBuf) next);
                }
            }
            client.flush();
            releaseUpstream();
        }

        void writeEverythingHeld() {
            if (timer != null) {
                timer.cancel(false);
            }
            writeDue();
        }

        /** Releases every relayed piece still held, once each. */
        void discardEverythingHeld() {
            if (timer != null) {
                timer.cancel(false);
                timer = null;
            }
            for (Object item; (item = held.poll()) != null; ) {
                if (item instanceof ByteBuf) {
                    ((ByteBuf) item).release();
                }
            }
        }

        private void holdUpstream() {
            if (!upstreamHeld && upstream != null && !finished) {
                upstreamHeld = true;
                ChannelReadPause.pause(upstream);
            }
        }

        void releaseUpstream() {
            if (upstreamHeld) {
                upstreamHeld = false;
                ChannelReadPause.resume(upstream);
            }
        }
    }

    private static final class Replacement {
        private final PostgresqlReplies.Reply reply;
        private final byte[] bytes;

        private Replacement(PostgresqlReplies.Reply reply, byte[] bytes) {
            this.reply = reply;
            this.bytes = bytes;
        }
    }
}
