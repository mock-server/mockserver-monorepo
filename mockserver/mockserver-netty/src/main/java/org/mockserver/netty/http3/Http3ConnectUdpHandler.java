package org.mockserver.netty.http3;

import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFactory;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoop;
import io.netty.channel.epoll.EpollDatagramChannel;
import io.netty.channel.socket.DatagramChannel;
import io.netty.channel.socket.DatagramPacket;
import io.netty.channel.socket.SocketProtocolFamily;
import io.netty.channel.socket.nio.NioDatagramChannel;
import io.netty.handler.codec.http3.DefaultHttp3DataFrame;
import io.netty.handler.codec.http3.DefaultHttp3HeadersFrame;
import io.netty.handler.codec.http3.Http3DataFrame;
import io.netty.handler.codec.http3.Http3HeadersFrame;
import io.netty.handler.codec.quic.QuicStreamChannel;
import io.netty.handler.codec.quic.QuicStreamResetException;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.text.StringEscapeUtils;
import org.mockserver.authentication.ProxyAuthenticationValidator;
import org.mockserver.configuration.Configuration;
import org.mockserver.log.model.LogEntry;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.model.HttpRequest;
import org.mockserver.proxyconfiguration.InetAddressValidator;
import org.mockserver.socket.NettyAllocator;
import org.mockserver.socket.NettyTransport;
import org.slf4j.event.Level;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketException;
import java.nio.channels.ClosedChannelException;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Objects;

import static io.netty.handler.codec.http.HttpHeaderNames.PROXY_AUTHENTICATE;
import static io.netty.handler.codec.http.HttpHeaderNames.PROXY_AUTHORIZATION;
import static org.apache.commons.lang3.StringUtils.isBlank;
import static org.mockserver.exception.ExceptionHandling.MAX_FAULT_MESSAGE_LENGTH;
import static org.mockserver.exception.ExceptionHandling.boundedFault;
import static org.mockserver.exception.ExceptionHandling.boundedFaultDescription;
import static org.mockserver.exception.ExceptionHandling.boundedFaultMessage;
import static org.mockserver.log.model.LogEntry.LogMessageType.AUTHENTICATION_FAILED;
import static org.mockserver.netty.http3.Http3ExceptionHandler.peerAddress;

/**
 * HTTP/3 CONNECT-UDP (MASQUE, RFC 9298) relay handler.
 * <p>
 * This handler intercepts HTTP/3 extended CONNECT requests with
 * {@code :protocol = connect-udp} when the {@code http3ConnectUdpEnabled}
 * configuration flag is set to {@code true}. It is inserted into the QUIC
 * stream pipeline <strong>before</strong> the regular {@link Http3MockServerHandler}
 * so that CONNECT-UDP requests are handled here and non-CONNECT requests pass
 * through to the normal mock pipeline.
 * <p>
 * <strong>Relay design:</strong>
 * <ol>
 *   <li>The handler recognises an extended CONNECT request by checking
 *       {@code :method = CONNECT} and {@code :protocol = connect-udp}.</li>
 *   <li>The target authority is parsed from the {@code :authority}
 *       pseudo-header (host:port).</li>
 *   <li>A UDP datagram channel is opened on the stream's own transport (epoll or NIO; IPv4 for an IPv4
 *       target) and connected to the target address.</li>
 *   <li>The handler responds with {@code 200 OK} to indicate the tunnel is
 *       established.</li>
 *   <li>Subsequent HTTP/3 DATA frames received on the QUIC stream are
 *       forwarded as UDP datagrams to the target. Each DATA frame payload
 *       is sent as one UDP datagram (simple 1:1 mapping without capsule
 *       framing -- suitable for testing).</li>
 *   <li>UDP datagrams received from the target are forwarded back to the
 *       client as HTTP/3 DATA frames on the same QUIC stream.</li>
 *   <li>When the QUIC stream is closed (input closed, error, or handler
 *       removal), the UDP channel is closed to release resources.</li>
 * </ol>
 * <p>
 * Plain CONNECT requests (without {@code :protocol}) are passed through to
 * the next handler in the pipeline (the mock handler) -- MockServer is not a
 * TCP forward proxy.
 * <p>
 * <strong>Proxy authentication:</strong> when {@code proxyAuthenticationUsername} and
 * {@code proxyAuthenticationPassword} are set, a CONNECT-UDP request without a matching
 * {@code proxy-authorization} header is refused with 407, before its target is looked at.
 * <p>
 * <strong>Destination restriction (SSRF mitigation):</strong> before a tunnel is
 * established the requested target is subjected to two checks:
 * <ol>
 *   <li>an optional allowlist ({@code http3ConnectUdpAllowedTargets}) — when
 *       non-empty, only targets whose {@code host} or {@code host:port} matches an
 *       allowlist entry may be relayed; everything else is refused with 403;</li>
 *   <li>the same private-network / metadata block the forward proxy uses
 *       ({@code forwardProxyBlockPrivateNetworks} via {@link InetAddressValidator})
 *       — when enabled, a target resolving to a loopback, link-local, RFC 1918 /
 *       RFC 4193 private, RFC 6598 carrier-grade NAT, wildcard, or cloud-metadata address
 *       is refused with 403.</li>
 * </ol>
 * With an empty allowlist and {@code forwardProxyBlockPrivateNetworks=false}
 * (both defaults) behaviour is unchanged — the relay is unrestricted — so existing
 * experimental users are unaffected unless they opt in to a restriction.
 * <p>
 * <strong>Framing note:</strong> a production MASQUE proxy would use the HTTP
 * Datagram / Capsule Protocol (RFC 9297) framing. This implementation uses a
 * simplified 1:1 DATA-frame-to-UDP-datagram mapping which is sufficient for
 * testing and mock-server usage. The Capsule Protocol adds a context ID varint
 * prefix; clients that require it should be adapted accordingly.
 *
 * @see <a href="https://www.rfc-editor.org/rfc/rfc9298">RFC 9298 - Proxying UDP in HTTP (CONNECT-UDP)</a>
 * @see <a href="https://www.rfc-editor.org/rfc/rfc9220">RFC 9220 - Bootstrapping WebSockets with HTTP/3</a>
 * @see <a href="https://www.rfc-editor.org/rfc/rfc9297">RFC 9297 - HTTP Datagrams and the Capsule Protocol</a>
 */
public class Http3ConnectUdpHandler extends ChannelInboundHandlerAdapter {

    /**
     * MockServer configuration used to resolve the CONNECT-UDP allowlist
     * ({@code http3ConnectUdpAllowedTargets}) and the SSRF private-network block
     * ({@code forwardProxyBlockPrivateNetworks}), and the proxy credentials. Never null,
     * so a missing configuration cannot turn proxy authentication off.
     */
    private final Configuration configuration;

    private final MockServerLogger mockServerLogger;

    /**
     * The UDP channel connected to the relay target. Null until a CONNECT-UDP
     * tunnel is established. Volatile because the channel may be closed from
     * a different thread (e.g. event loop vs handler removal).
     */
    private volatile Channel udpChannel;

    /**
     * Whether this handler has consumed the CONNECT request and established a
     * tunnel. When true, subsequent DATA frames are relayed; when false, the
     * handler is transparent and passes everything through.
     */
    private boolean tunnelEstablished;

    private boolean connectUdpRequested;

    /**
     * @param configuration    the MockServer configuration used to resolve the
     *                         CONNECT-UDP allowlist, the SSRF private-network block and
     *                         the proxy credentials; must not be null
     * @param mockServerLogger the server's logger, so the relay's entries reach its log and event log
     */
    public Http3ConnectUdpHandler(Configuration configuration, MockServerLogger mockServerLogger) {
        this.configuration = Objects.requireNonNull(configuration, "configuration");
        this.mockServerLogger = mockServerLogger;
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
        if (tunnelEstablished && msg instanceof Http3DataFrame) {
            // tunnel is active -- relay DATA frame payload as a UDP datagram
            relayToTarget(ctx, (Http3DataFrame) msg);
            return;
        }

        if (msg instanceof Http3HeadersFrame) {
            Http3HeadersFrame headersFrame = (Http3HeadersFrame) msg;
            CharSequence method = headersFrame.headers().method();
            CharSequence protocol = headersFrame.headers().protocol();

            if (method != null && "CONNECT".equalsIgnoreCase(method.toString())) {
                if (protocol != null && "connect-udp".equalsIgnoreCase(protocol.toString())) {
                    // Extended CONNECT with :protocol=connect-udp (RFC 9298)
                    connectUdpRequested = true;
                    handleConnectUdp(ctx, headersFrame);
                    return;
                }
                // Plain CONNECT (no :protocol) -- pass through to mock handler.
                // MockServer can match it as a regular request.
            }
        }

        // Not a CONNECT-UDP request -- pass through to the next handler
        // (Http3MockServerHandler for normal mock/proxy processing)
        ctx.fireChannelRead(msg);
    }

    /**
     * Establish a CONNECT-UDP tunnel: parse the target authority, open a UDP
     * channel, and respond with 200 OK.
     */
    private void handleConnectUdp(ChannelHandlerContext ctx, Http3HeadersFrame headersFrame) {
        if (!proxyAuthenticated(headersFrame)) {
            sendProxyAuthenticationRequired(ctx);
            return;
        }

        CharSequence authority = headersFrame.headers().authority();
        if (authority == null || authority.length() == 0) {
            sendErrorResponse(ctx, "400", "Missing :authority for CONNECT-UDP");
            return;
        }

        InetSocketAddress targetAddress = parseAuthority(authority.toString());
        if (targetAddress == null) {
            sendErrorResponse(ctx, "400", "Invalid :authority for CONNECT-UDP: " + authority);
            return;
        }

        // the raw host string as supplied by the client (no brackets), used only for
        // the allowlist match; the SSRF block and the connect both use the single
        // resolved address below so they can never diverge (DNS-rebinding / TOCTOU)
        String targetHost = targetAddress.getHostString();
        int targetPort = targetAddress.getPort();

        // (1) allowlist: when configured non-empty, only listed targets may be relayed
        String allowedTargets = configuration.http3ConnectUdpAllowedTargets();
        if (!isBlank(allowedTargets) && !matchesAllowlist(allowedTargets, targetHost, targetPort)) {
            sendTargetNotPermitted(ctx, "not in http3ConnectUdpAllowedTargets: " + authority);
            return;
        }

        // Resolve the authority host EXACTLY ONCE. parseAuthority already resolved it
        // when constructing the InetSocketAddress; if it could not be resolved we must
        // reject rather than proceed, otherwise we could validate one address and
        // connect to another. Never re-resolve the host string after this point.
        if (targetAddress.isUnresolved() || targetAddress.getAddress() == null) {
            sendTargetNotPermitted(ctx, "unresolved CONNECT-UDP target: " + authority);
            return;
        }
        InetAddress resolvedTarget = targetAddress.getAddress();

        // (2) SSRF private-network block: reuse the forward-proxy policy on the SAME
        // resolved address we will connect to, so a target resolving to loopback /
        // RFC1918 / link-local / ULA / CGNAT / wildcard / metadata is refused when
        // forwardProxyBlockPrivateNetworks is enabled (no-op when disabled)
        try {
            InetAddressValidator.validateForwardTarget(configuration, resolvedTarget);
        } catch (IllegalArgumentException e) {
            sendTargetNotPermitted(ctx, "SSRF block for " + authority + ": " + e.getMessage());
            return;
        }

        // Connect to the exact validated InetAddress (wrapping it in a fresh
        // InetSocketAddress does NOT re-resolve), guaranteeing validated == connected.
        InetSocketAddress connectAddress = new InetSocketAddress(resolvedTarget, targetPort);

        if (mockServerLogger.isEnabledForInstance(Level.DEBUG)) {
            mockServerLogger.logEvent(
                new LogEntry()
                    .setLogLevel(Level.DEBUG)
                    .setMessageFormat("CONNECT-UDP tunnel from:{}requested to:{}")
                    .setArguments(peerAddress(ctx.channel()), connectAddress)
            );
        }

        // Open a UDP channel connected to the target, on the parent channel's event loop.
        ChannelFuture bindFuture = bindRelaySocket(ctx.channel().eventLoop(), resolvedTarget, new UdpRelayHandler(ctx, mockServerLogger));
        bindFuture.addListener(future -> {
            if (!future.isSuccess()) {
                sendRelayUnavailable(ctx, "bind failed for " + connectAddress, future.cause());
                return;
            }

            Channel boundChannel = bindFuture.channel();
            ChannelFuture connectFuture = boundChannel.connect(connectAddress);
            connectFuture.addListener(connFuture -> {
                if (!connFuture.isSuccess()) {
                    boundChannel.close();
                    sendRelayUnavailable(ctx, "connect failed for " + connectAddress, connFuture.cause());
                    return;
                }

                this.udpChannel = boundChannel;
                this.tunnelEstablished = true;

                if (mockServerLogger.isEnabledForInstance(Level.INFO)) {
                    mockServerLogger.logEvent(
                        new LogEntry()
                            .setLogLevel(Level.INFO)
                            .setMessageFormat("CONNECT-UDP tunnel from:{}established to:{}")
                            .setArguments(peerAddress(ctx.channel()), connectAddress)
                    );
                }

                // Respond 200 OK to the client to indicate the tunnel is up
                DefaultHttp3HeadersFrame responseHeaders = new DefaultHttp3HeadersFrame();
                responseHeaders.headers().status("200");
                responseHeaders.headers().add("server", "mockserver-http3-masque");
                ctx.writeAndFlush(responseHeaders);
            });
        });
    }

    /**
     * Binds the relay socket to an ephemeral port in the target's family, on the transport of the stream's event loop
     * (epoll or NIO). On macOS a dual-stack socket on port 0 can be given a port another process holds on IPv4, so an
     * IPv4 target gets an IPv4 socket.
     */
    static ChannelFuture bindRelaySocket(EventLoop eventLoop, InetAddress target, ChannelHandler handler) {
        boolean ipv4 = target instanceof Inet4Address;
        boolean epoll = NettyTransport.datagramChannelClassFor(eventLoop) == EpollDatagramChannel.class;
        return new Bootstrap()
            .group(eventLoop)
            .channelFactory((ChannelFactory<DatagramChannel>) () -> epoll
                ? (ipv4 ? new EpollDatagramChannel(SocketProtocolFamily.INET) : new EpollDatagramChannel())
                : (ipv4 ? new NioDatagramChannel(SocketProtocolFamily.INET) : new NioDatagramChannel()))
            .option(ChannelOption.ALLOCATOR, NettyAllocator.ALLOCATOR)
            .handler(handler)
            .bind(0);
    }

    /**
     * Relay a DATA frame payload from the client to the UDP target.
     */
    private void relayToTarget(ChannelHandlerContext ctx, Http3DataFrame dataFrame) {
        Channel target = this.udpChannel;
        if (target == null || !target.isActive()) {
            // the relay socket's failure, if any, was logged once when it closed
            if (mockServerLogger.isEnabledForInstance(Level.DEBUG)) {
                mockServerLogger.logEvent(
                    new LogEntry()
                        .setLogLevel(Level.DEBUG)
                        .setMessageFormat("CONNECT-UDP relay socket for tunnel from:{}is closed, dropping a DATA frame of:{}bytes")
                        .setArguments(peerAddress(ctx.channel()), dataFrame.content().readableBytes())
                );
            }
            dataFrame.release();
            return;
        }

        ByteBuf content = dataFrame.content();
        if (content.isReadable()) {
            // Send the DATA frame payload as a UDP datagram
            InetSocketAddress remoteAddr = (InetSocketAddress) target.remoteAddress();
            DatagramPacket packet = new DatagramPacket(content.retain(), remoteAddr);
            target.writeAndFlush(packet).addListener(f -> {
                if (!f.isSuccess() && mockServerLogger.isEnabledForInstance(Level.DEBUG)) {
                    mockServerLogger.logEvent(
                        new LogEntry()
                            .setLogLevel(Level.DEBUG)
                            .setMessageFormat("CONNECT-UDP relay failed to send a datagram to:{}")
                            .setArguments(remoteAddr)
                            .setThrowable(boundedFault(f.cause()))
                    );
                }
            });
        }
        dataFrame.release();
    }

    /**
     * A CONNECT-UDP tunnel is a proxy tunnel, so when proxy authentication is configured it needs the same
     * {@code Proxy-Authorization} credential as an HTTP {@code CONNECT}.
     */
    private boolean proxyAuthenticated(Http3HeadersFrame headersFrame) {
        String username = configuration.proxyAuthenticationUsername();
        String password = configuration.proxyAuthenticationPassword();
        if (!ProxyAuthenticationValidator.proxyAuthenticationConfigured(username, password)) {
            return true;
        }
        HttpRequest credentials = HttpRequest.request();
        for (CharSequence value : headersFrame.headers().getAll(PROXY_AUTHORIZATION)) {
            credentials.withHeader(PROXY_AUTHORIZATION.toString(), value.toString());
        }
        return ProxyAuthenticationValidator.isAuthenticated(credentials, username, password);
    }

    private void sendProxyAuthenticationRequired(ChannelHandlerContext ctx) {
        if (mockServerLogger.isEnabledForInstance(Level.INFO)) {
            mockServerLogger.logEvent(
                new LogEntry()
                    .setType(AUTHENTICATION_FAILED)
                    .setLogLevel(Level.INFO)
                    .setMessageFormat("proxy authentication failed so returning 407 for CONNECT-UDP request from:{}")
                    .setArguments(peerAddress(ctx.channel()))
            );
        }
        writeErrorResponse(ctx, "407", "proxy authentication required",
            "Basic realm=\"" + StringEscapeUtils.escapeJava(configuration.proxyAuthenticationRealm()) + "\", charset=\"UTF-8\"");
    }

    /**
     * Refuse a CONNECT-UDP target with a <strong>generic</strong> 403 body. The
     * specific reason (allowlist miss vs SSRF block vs unresolved, and which host)
     * is logged server-side only, so the client cannot use the relay as a recon
     * oracle to probe which internal hosts exist or are blocked. Both the allowlist
     * and SSRF paths return the identical body so they are indistinguishable.
     */
    private void sendTargetNotPermitted(ChannelHandlerContext ctx, String serverSideReason) {
        if (mockServerLogger.isEnabledForInstance(Level.WARN)) {
            mockServerLogger.logEvent(
                new LogEntry()
                    .setLogLevel(Level.WARN)
                    .setMessageFormat("CONNECT-UDP target from:{}refused:{}")
                    .setArguments(peerAddress(ctx.channel()), bounded(serverSideReason))
            );
        }
        writeErrorResponse(ctx, "403", "CONNECT-UDP target not permitted");
    }

    /**
     * Report a relay setup failure (UDP bind/connect) with a generic 502 body,
     * logging the concrete cause server-side only.
     */
    private void sendRelayUnavailable(ChannelHandlerContext ctx, String serverSideReason, Throwable cause) {
        mockServerLogger.logEvent(
            new LogEntry()
                .setLogLevel(Level.ERROR)
                .setMessageFormat("CONNECT-UDP relay for tunnel from:{}unavailable:{}")
                .setArguments(peerAddress(ctx.channel()), serverSideReason)
                .setThrowable(boundedFault(cause))
        );
        writeErrorResponse(ctx, "502", "CONNECT-UDP relay unavailable");
    }

    /**
     * Send an error response and close the QUIC stream. The {@code message} is
     * returned to the client verbatim, so this must only be used for non-sensitive
     * messages that describe the client's own malformed request (e.g. a missing or
     * malformed {@code :authority}) -- never to echo internal host/topology detail.
     */
    private void sendErrorResponse(ChannelHandlerContext ctx, String status, String message) {
        if (mockServerLogger.isEnabledForInstance(Level.WARN)) {
            mockServerLogger.logEvent(
                new LogEntry()
                    .setLogLevel(Level.WARN)
                    .setMessageFormat("CONNECT-UDP request from:{}refused with status:{}because:{}")
                    .setArguments(peerAddress(ctx.channel()), status, bounded(message))
            );
        }
        writeErrorResponse(ctx, status, message);
    }

    /**
     * Write an HTTP/3 error response (headers + JSON body) and shut down the stream.
     */
    private void writeErrorResponse(ChannelHandlerContext ctx, String status, String message) {
        writeErrorResponse(ctx, status, message, null);
    }

    private void writeErrorResponse(ChannelHandlerContext ctx, String status, String message, String proxyAuthenticate) {
        String body = "{\"error\":\"" + escapeJsonString(message) + "\"}";
        byte[] bodyBytes = body.getBytes(StandardCharsets.UTF_8);

        DefaultHttp3HeadersFrame responseHeaders = new DefaultHttp3HeadersFrame();
        responseHeaders.headers().status(status);
        responseHeaders.headers().add("content-type", "application/json; charset=utf-8");
        responseHeaders.headers().addInt("content-length", bodyBytes.length);
        responseHeaders.headers().add("server", "mockserver-http3-masque");
        if (proxyAuthenticate != null) {
            responseHeaders.headers().add(PROXY_AUTHENTICATE, proxyAuthenticate);
        }

        ctx.write(responseHeaders);
        ctx.writeAndFlush(new DefaultHttp3DataFrame(Unpooled.wrappedBuffer(bodyBytes)))
            .addListener(QuicStreamChannel.SHUTDOWN_OUTPUT);
    }

    /**
     * Determine whether a target {@code host}/{@code port} is permitted by a
     * comma-separated allowlist of {@code host} or {@code host:port} entries.
     * <p>
     * Matching is by exact, case-insensitive host equality. An entry without a
     * port matches the host on any port; an entry with a port matches only that
     * host and port. IPv6 literals must be bracketed in both the entry and (per
     * RFC 3986) the request authority, e.g. {@code [::1]:53}. Blank entries are
     * ignored. An entirely blank allowlist is treated as "not enforced" by the
     * caller (this method is only invoked for a non-blank allowlist).
     *
     * @return true if at least one allowlist entry matches the target
     */
    static boolean matchesAllowlist(String allowedTargetsCsv, String host, int port) {
        if (allowedTargetsCsv == null || host == null) {
            return false;
        }
        String target = host.toLowerCase(Locale.ROOT);
        for (String raw : allowedTargetsCsv.split(",")) {
            String entry = raw.trim();
            if (entry.isEmpty()) {
                continue;
            }
            String entryHost;
            Integer entryPort;
            if (entry.startsWith("[")) {
                // [ipv6] or [ipv6]:port
                int closeBracket = entry.indexOf(']');
                if (closeBracket < 0) {
                    continue;
                }
                entryHost = entry.substring(1, closeBracket);
                if (closeBracket + 1 < entry.length() && entry.charAt(closeBracket + 1) == ':') {
                    entryPort = parsePort(entry.substring(closeBracket + 2));
                    if (entryPort == null) {
                        continue;
                    }
                } else {
                    entryPort = null;
                }
            } else {
                int lastColon = entry.lastIndexOf(':');
                Integer maybePort = lastColon >= 0 ? parsePort(entry.substring(lastColon + 1)) : null;
                if (maybePort != null) {
                    entryHost = entry.substring(0, lastColon);
                    entryPort = maybePort;
                } else {
                    entryHost = entry;
                    entryPort = null;
                }
            }
            if (entryHost.equalsIgnoreCase(target) && (entryPort == null || entryPort == port)) {
                return true;
            }
        }
        return false;
    }

    private static Integer parsePort(String value) {
        try {
            int p = Integer.parseInt(value.trim());
            return (p > 0 && p <= 65535) ? p : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * Parse an authority string (host:port) into an {@link InetSocketAddress}.
     * Supports both IPv4 ({@code host:port}) and IPv6 ({@code [host]:port}) forms.
     *
     * @return the parsed address, or null if the authority is malformed
     */
    static InetSocketAddress parseAuthority(String authority) {
        if (authority == null || authority.isEmpty()) {
            return null;
        }

        String host;
        int port;

        if (authority.startsWith("[")) {
            // IPv6: [host]:port
            int closeBracket = authority.indexOf(']');
            if (closeBracket < 0) {
                return null;
            }
            host = authority.substring(1, closeBracket);
            if (closeBracket + 1 < authority.length() && authority.charAt(closeBracket + 1) == ':') {
                try {
                    port = Integer.parseInt(authority.substring(closeBracket + 2));
                } catch (NumberFormatException e) {
                    return null;
                }
            } else {
                return null; // port required for CONNECT-UDP
            }
        } else {
            // IPv4 or hostname: host:port
            int lastColon = authority.lastIndexOf(':');
            if (lastColon < 0 || lastColon == authority.length() - 1) {
                return null; // port required for CONNECT-UDP
            }
            host = authority.substring(0, lastColon);
            try {
                port = Integer.parseInt(authority.substring(lastColon + 1));
            } catch (NumberFormatException e) {
                return null;
            }
        }

        if (port <= 0 || port > 65535) {
            return null;
        }

        return new InetSocketAddress(host, port);
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) throws Exception {
        closeUdpChannel();
        super.channelInactive(ctx);
    }

    @Override
    public void handlerRemoved(ChannelHandlerContext ctx) throws Exception {
        closeUdpChannel();
        super.handlerRemoved(ctx);
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        if (!connectUdpRequested) {
            // not a CONNECT-UDP stream: the request handler after this one reports and closes it
            ctx.fireExceptionCaught(cause);
            return;
        }
        if (cause instanceof QuicStreamResetException || cause instanceof ClosedChannelException) {
            // a client ending its tunnel, as Http3MockServerHandler logs it on a request stream
            if (mockServerLogger.isEnabledForInstance(Level.DEBUG)) {
                mockServerLogger.logEvent(
                    new LogEntry()
                        .setLogLevel(Level.DEBUG)
                        .setMessageFormat("CONNECT-UDP stream of HTTP/3 connection from:{}closed or reset by its client:{}")
                        .setArguments(peerAddress(ctx.channel()), boundedFaultMessage(cause))
                );
            }
        } else if (mockServerLogger.isEnabledForInstance(Level.WARN)) {
            mockServerLogger.logEvent(
                new LogEntry()
                    .setLogLevel(Level.WARN)
                    .setMessageFormat("exception on CONNECT-UDP stream of HTTP/3 connection from:{}:{}")
                    .setArguments(peerAddress(ctx.channel()), boundedFaultDescription(cause))
                    .setThrowable(boundedFault(cause))
            );
        }
        closeUdpChannel();
        ctx.close();
    }

    /**
     * A reason as a log entry may carry it: it can hold the client's {@code :authority}, which is as long as
     * {@code maxHeaderSize} allows.
     */
    private static String bounded(String reason) {
        return StringUtils.abbreviate(reason, MAX_FAULT_MESSAGE_LENGTH);
    }

    /**
     * Escape a string for safe embedding in a JSON string value. Handles
     * backslash, double-quote, and the control characters required by RFC 8259
     * (newline, carriage-return, tab, backspace, form-feed), plus any other
     * control character below U+0020.
     */
    static String escapeJsonString(String value) {
        if (value == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder(value.length() + 16);
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            switch (ch) {
                case '\\':
                    sb.append("\\\\");
                    break;
                case '"':
                    sb.append("\\\"");
                    break;
                case '\n':
                    sb.append("\\n");
                    break;
                case '\r':
                    sb.append("\\r");
                    break;
                case '\t':
                    sb.append("\\t");
                    break;
                case '\b':
                    sb.append("\\b");
                    break;
                case '\f':
                    sb.append("\\f");
                    break;
                default:
                    if (ch < 0x20) {
                        sb.append(String.format("\\u%04x", (int) ch));
                    } else {
                        sb.append(ch);
                    }
                    break;
            }
        }
        return sb.toString();
    }

    /**
     * Close the UDP relay channel if it is open.
     */
    private void closeUdpChannel() {
        Channel ch = this.udpChannel;
        if (ch != null) {
            this.udpChannel = null;
            ch.close();
            if (mockServerLogger.isEnabledForInstance(Level.DEBUG)) {
                mockServerLogger.logEvent(
                    new LogEntry()
                        .setLogLevel(Level.DEBUG)
                        .setMessageFormat("CONNECT-UDP relay socket to:{}closed")
                        .setArguments(ch.remoteAddress())
                );
            }
        }
    }

    /**
     * Inbound handler on the UDP channel that relays received datagrams back to
     * the client as HTTP/3 DATA frames on the QUIC stream.
     */
    static class UdpRelayHandler extends ChannelInboundHandlerAdapter {

        private final ChannelHandlerContext quicStreamCtx;
        private final MockServerLogger mockServerLogger;

        UdpRelayHandler(ChannelHandlerContext quicStreamCtx, MockServerLogger mockServerLogger) {
            this.quicStreamCtx = quicStreamCtx;
            this.mockServerLogger = mockServerLogger;
        }

        @Override
        public void channelRead(ChannelHandlerContext ctx, Object msg) {
            if (msg instanceof DatagramPacket) {
                DatagramPacket packet = (DatagramPacket) msg;
                ByteBuf content = packet.content();
                if (content.isReadable() && quicStreamCtx.channel().isActive()) {
                    DefaultHttp3DataFrame dataFrame = new DefaultHttp3DataFrame(content.retain());
                    quicStreamCtx.writeAndFlush(dataFrame);
                }
                packet.release();
            }
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
            if (mockServerLogger.isEnabledForInstance(Level.WARN)) {
                mockServerLogger.logEvent(
                    new LogEntry()
                        .setLogLevel(Level.WARN)
                        .setMessageFormat("CONNECT-UDP relay socket to:{}for HTTP/3 connection from:{}failed and is closed:{}")
                        .setArguments(ctx.channel().remoteAddress(), peerAddress(quicStreamCtx.channel()), boundedFaultDescription(cause))
                        // an I/O error's stack trace, such as an ICMP port unreachable's, says nothing
                        .setThrowable(cause instanceof SocketException ? null : boundedFault(cause))
                );
            }
            ctx.close();
        }
    }
}
