package org.mockserver.netty.proxy;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.util.AttributeKey;
import org.mockserver.configuration.Configuration;
import org.mockserver.log.model.LogEntry;
import org.mockserver.logging.MockServerLogger;
import org.slf4j.event.Level;

import java.net.InetSocketAddress;

import static org.mockserver.authentication.ProxyAuthenticationValidator.proxyAuthenticationConfigured;
import static org.mockserver.log.model.LogEntry.LogMessageType.SERVER_CONFIGURATION;
import static org.mockserver.mock.action.http.HttpActionHandler.REMOTE_SOCKET;
import static org.mockserver.netty.HttpRequestHandler.PROXYING;

/**
 * A Netty channel handler that resolves the original destination for transparently
 * intercepted connections and stores it as the {@link org.mockserver.mock.action.http.HttpActionHandler#REMOTE_SOCKET}
 * channel attribute. The downstream proxy/forward logic in {@code HttpActionHandler}
 * then uses this address instead of requiring the Host header to determine the target.
 * <p>
 * Resolution is performed by a pluggable {@link OriginalDestinationResolver} strategy
 * (typically a {@link CompositeOriginalDestinationResolver} chain). The default chain
 * contains, in order, the TPROXY resolver (when TPROXY mode is enabled), the
 * SO_ORIGINAL_DST getsockopt resolver, the conntrack resolver, and the DNS-intent
 * resolver. See {@link CompositeOriginalDestinationResolver#defaultChain(Configuration)}
 * for the full chain ordering.
 * <p>
 * Resolution strategy (in order):
 * <ol>
 *   <li><b>PROXY protocol v1</b> — handled by
 *       {@link ProxyProtocolOriginalDestinationHandler} earlier in the pipeline
 *       (reads inbound bytes, not channel metadata). If a PROXY header is present,
 *       the REMOTE_SOCKET is set before this handler fires.</li>
 *   <li><b>Channel-level chain</b> (this handler, at {@code channelActive}):
 *       <ul>
 *         <li>{@link TproxyOriginalDestinationResolver} — TPROXY local-address resolution
 *             (when {@code transparentProxyTproxy=true}; returns null otherwise)</li>
 *         <li>{@link SoOriginalDstResolver} — O(1) getsockopt(SO_ORIGINAL_DST) via JNA
 *             (Linux + epoll transport only; returns null on NIO channels)</li>
 *         <li>{@link ConntrackOriginalDestinationResolver} — Linux conntrack table</li>
 *         <li>(future) eBPF</li>
 *       </ul>
 *   </li>
 *   <li><b>Host header fallback</b> — if no strategy resolves the destination,
 *       the existing Host-based forwarding logic in {@code HttpActionHandler} applies.</li>
 * </ol>
 * <p>
 * This handler is only added to the pipeline when
 * {@link Configuration#transparentProxyEnabled()} is {@code true}.
 * It fires on {@code channelActive} (connection accepted) and sets the attribute
 * before any HTTP data is processed.
 */
public class TransparentProxyHandler extends ChannelInboundHandlerAdapter {

    /**
     * Channel attribute indicating the transparent proxy original destination
     * was resolved via SO_ORIGINAL_DST / conntrack (as opposed to Host header).
     */
    public static final AttributeKey<Boolean> TRANSPARENT_ORIGINAL_DST_RESOLVED =
        AttributeKey.valueOf("TRANSPARENT_ORIGINAL_DST_RESOLVED");

    private final Configuration configuration;
    private final MockServerLogger logger;
    private final OriginalDestinationResolver resolver;

    /**
     * Strategy interface for resolving the original destination. Allows
     * the helper to be replaced in tests without touching real sockets.
     */
    @FunctionalInterface
    public interface OriginalDestinationResolver {
        /**
         * @param channel the accepted Netty channel
         * @return the original destination, or null if unavailable
         * @throws UnsupportedOperationException on unsupported platforms
         */
        InetSocketAddress resolve(io.netty.channel.Channel channel);
    }

    public TransparentProxyHandler(Configuration configuration, MockServerLogger logger) {
        this(configuration, logger, CompositeOriginalDestinationResolver.defaultChain(configuration));
    }

    /**
     * Constructor with injectable resolver for unit testing.
     */
    public TransparentProxyHandler(Configuration configuration, MockServerLogger logger,
                                   OriginalDestinationResolver resolver) {
        this.configuration = configuration;
        this.logger = logger;
        this.resolver = resolver;
    }

    /**
     * Warns at startup that proxy credentials are not asked for on transparently proxied connections: a
     * transparent client cannot send them, so this is by design, but it is not what the credentials suggest.
     */
    public static void warnIfProxyAuthenticationConfigured(Configuration configuration, MockServerLogger logger) {
        if (Boolean.TRUE.equals(configuration.transparentProxyEnabled())
            && proxyAuthenticationConfigured(configuration.proxyAuthenticationUsername(), configuration.proxyAuthenticationPassword())
            && logger.isEnabledForInstance(Level.WARN)) {
            logger.logEvent(
                new LogEntry()
                    .setType(SERVER_CONFIGURATION)
                    .setLogLevel(Level.WARN)
                    .setMessageFormat("transparentProxyEnabled is true and proxy authentication credentials are configured: connections handled as transparent proxying, including those whose destination comes from the Host header fallback or a PROXY protocol header, are not challenged for proxy credentials, so any client that can reach the port can use it as an unauthenticated proxy")
            );
        }
    }

    @Override
    public void channelActive(ChannelHandlerContext ctx) throws Exception {
        if (Boolean.TRUE.equals(configuration.transparentProxyEnabled())) {
            resolveAndSetOriginalDestination(ctx);
        }
        super.channelActive(ctx);
    }

    private void resolveAndSetOriginalDestination(ChannelHandlerContext ctx) {
        // If PROXY protocol handler already resolved the destination (it runs
        // earlier in the pipeline on first inbound bytes), skip the channel-level chain.
        if (Boolean.TRUE.equals(ctx.channel().attr(TRANSPARENT_ORIGINAL_DST_RESOLVED).get())) {
            if (logger != null && logger.isEnabledForInstance(Level.DEBUG)) {
                logger.logEvent(
                    new LogEntry()
                        .setLogLevel(Level.DEBUG)
                        .setMessageFormat("transparent proxy: original destination already resolved (e.g. PROXY protocol) for channel {}, skipping channel-level resolution")
                        .setArguments(ctx.channel())
                );
            }
            return;
        }

        InetSocketAddress originalDst = null;

        try {
            originalDst = resolver.resolve(ctx.channel());
        } catch (UnsupportedOperationException e) {
            // Expected on non-Linux; fall through to Host-header fallback
            if (logger != null && logger.isEnabledForInstance(Level.DEBUG)) {
                logger.logEvent(
                    new LogEntry()
                        .setLogLevel(Level.DEBUG)
                        .setMessageFormat("transparent proxy: SO_ORIGINAL_DST not available ({}), will use Host header fallback")
                        .setArguments(e.getMessage())
                );
            }
        } catch (Exception e) {
            // Unexpected error reading conntrack; log and fall back
            if (logger != null && logger.isEnabledForInstance(Level.WARN)) {
                logger.logEvent(
                    new LogEntry()
                        .setLogLevel(Level.WARN)
                        .setMessageFormat("transparent proxy: failed to read original destination for channel {}: {}")
                        .setArguments(ctx.channel(), e.getMessage())
                );
            }
        }

        if (originalDst != null) {
            // Set the REMOTE_SOCKET so HttpActionHandler forwards to the original destination
            ctx.channel().attr(REMOTE_SOCKET).set(originalDst);
            ctx.channel().attr(PROXYING).set(Boolean.TRUE);
            ctx.channel().attr(TRANSPARENT_ORIGINAL_DST_RESOLVED).set(Boolean.TRUE);

            if (logger != null && logger.isEnabledForInstance(Level.DEBUG)) {
                logger.logEvent(
                    new LogEntry()
                        .setLogLevel(Level.DEBUG)
                        .setMessageFormat("transparent proxy: resolved original destination {} for channel {} via resolver chain")
                        .setArguments(originalDst, ctx.channel())
                );
            }
        } else {
            // Mark the channel as proxying (transparent mode) but without a fixed
            // REMOTE_SOCKET. The Host-header-based resolution in HttpActionHandler
            // will determine the target when the first HTTP request arrives.
            ctx.channel().attr(PROXYING).set(Boolean.TRUE);
            ctx.channel().attr(TRANSPARENT_ORIGINAL_DST_RESOLVED).set(Boolean.FALSE);

            if (logger != null && logger.isEnabledForInstance(Level.DEBUG)) {
                logger.logEvent(
                    new LogEntry()
                        .setLogLevel(Level.DEBUG)
                        .setMessageFormat("transparent proxy: using Host header fallback for channel {}")
                        .setArguments(ctx.channel())
                );
            }
        }
    }
}
