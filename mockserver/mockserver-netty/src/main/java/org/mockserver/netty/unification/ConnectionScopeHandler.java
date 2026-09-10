package org.mockserver.netty.unification;

import io.netty.channel.Channel;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.util.Attribute;
import io.netty.util.AttributeKey;
import org.mockserver.mock.action.http.HttpActionHandler;
import org.mockserver.netty.HttpRequestHandler;
import org.mockserver.socket.tls.SniHandler;

import java.util.Arrays;
import java.util.List;

/**
 * Copies the connection-scoped channel attributes from a parent channel onto a child channel.
 * <p>
 * <strong>Why this exists.</strong> Netty's {@link io.netty.handler.codec.http2.Http2MultiplexHandler}
 * gives every HTTP/2 stream its own {@link io.netty.handler.codec.http2.Http2StreamChannel} child
 * channel. {@code AbstractHttp2StreamChannel} delegates {@code localAddress()}/{@code remoteAddress()}
 * to the parent but <strong>does not inherit channel attributes</strong>. Every MockServer subsystem
 * that reads connection-scoped state from {@code ctx.channel()} therefore misbehaves on a child stream
 * channel, because the connection-level decisions (TLS negotiation, negotiated protocol, proxying,
 * local-host set, client certificates, ...) were all recorded on the <em>parent</em> channel during
 * port unification / TLS handshake, before the multiplex handler split the connection into streams.
 * <p>
 * Concretely, without this propagation the following break on a multiplexed HTTP/2 stream:
 * <ul>
 *   <li>{@code SniHandler.getALPNProtocol} returns {@code null} → the request is not recognised as
 *       {@code HTTP_2}, so the stream id is never captured and {@code withProtocol(HTTP_2)} matching
 *       and HAR/log protocol reporting are wrong;</li>
 *   <li>{@code PortUnificationHandler.isHttp2Enabled} returns {@code false} → the callback and
 *       dashboard WebSocket handlers attempt a WebSocket handshake over an HTTP/2 stream instead of
 *       returning {@code 501};</li>
 *   <li>{@code SniHandler.retrieveClientCertificates} returns {@code null} → mTLS control-plane
 *       authentication fails over HTTP/2;</li>
 *   <li>{@code HttpRequestHandler.isProxyingRequest} returns {@code false} (and
 *       {@code HttpActionHandler.getRemoteAddress} returns {@code null}) → a proxied
 *       (SOCKS/CONNECT/original-destination/port-forward) request arriving over HTTP/2 is mis-routed:
 *       either treated as a local request, or forwarded to the wrong upstream (falling back to the
 *       {@code Host} header instead of the recorded CONNECT/original-destination target).</li>
 * </ul>
 * <p>
 * <strong>Design: copy values once, do not read through the parent lazily.</strong> The attribute
 * <em>values</em> are copied from the parent onto the child once, when the child pipeline is being
 * built ({@link #handlerAdded(ChannelHandlerContext)}). We deliberately do <em>not</em> keep a
 * reference to the parent channel and read through it on demand: a child stream can outlive interest
 * in (or mutation of) the parent's state, and lazy reads would re-introduce exactly the shared,
 * connection-wide mutable coupling that giving each stream its own channel is meant to remove. The
 * connection-scoped attributes copied here are all established during connection setup and are
 * stable for the life of the connection, so a one-shot copy is correct.
 * <p>
 * This handler is {@link ChannelHandler.Sharable @Sharable} and stateless, so a single instance can
 * be installed as the first handler of every child pipeline. After copying, it removes itself from
 * the pipeline — its job is done and it must not sit in the data path.
 */
@ChannelHandler.Sharable
public class ConnectionScopeHandler extends ChannelInboundHandlerAdapter {

    /**
     * The connection-scoped attribute keys copied from parent to child.
     * <p>
     * Each entry references the {@code public} {@link AttributeKey} constant declared by the class that
     * owns and reads it, so there is a single shared source of truth: a rename of the key value on the
     * owning class updates both the reader and this propagation list at once. (The keys are all
     * process-wide singletons via {@link AttributeKey#valueOf(String)} regardless, but referencing the
     * constant — not a re-typed string literal — is what makes the coupling compile-time.)
     * <p>
     * The list was derived by enumerating every channel attribute that is <em>set on the connection
     * (parent) channel</em> during port unification / TLS handshake / proxy detection and later
     * <em>read from {@code ctx.channel()} by a handler that runs in the child stream pipeline</em>:
     * <ul>
     *   <li>{@code LOCAL_HOST_HEADERS} — {@code HttpRequestHandler.getLocalAddresses};</li>
     *   <li>{@code PROXYING} — {@code HttpRequestHandler.isProxyingRequest} (set on the parent by the
     *       SOCKS/CONNECT/transparent proxy handlers);</li>
     *   <li>{@code REMOTE_SOCKET} — {@code HttpActionHandler.getRemoteAddress} on the forward path (the
     *       CONNECT / original-destination / port-forward target); the connection-scoped sibling of
     *       {@code PROXYING} — set on the parent by the same proxy handlers and the server bootstrap;</li>
     *   <li>{@code HTTP2_ENABLED} — {@code PortUnificationHandler.isHttp2Enabled}, read by the
     *       callback and dashboard WebSocket handlers;</li>
     *   <li>{@code TLS_ENABLED_UPSTREAM} / {@code TLS_ENABLED_DOWNSTREAM} —
     *       {@code PortUnificationHandler.isSslEnabledUpstream/Downstream};</li>
     *   <li>{@code NETTY_SSL_CONTEXT_FACTORY} — {@code PortUnificationHandler.getNettySslContextFactory};</li>
     *   <li>{@code NEGOTIATED_APPLICATION_PROTOCOL} / {@code UPSTREAM_SSL_HANDLER} —
     *       {@code SniHandler.getALPNProtocol};</li>
     *   <li>{@code UPSTREAM_CLIENT_CERTIFICATES} / {@code UPSTREAM_SSL_ENGINE} —
     *       {@code SniHandler.retrieveClientCertificates};</li>
     *   <li>{@code SNI_HOSTNAME} — {@code SniHandler.getSniHostname}.</li>
     * </ul>
     * {@code HTTP_ENABLED} and {@code TRANSPARENT_ORIGINAL_DST_RESOLVED} are intentionally excluded:
     * both are read only by connection-level handlers that never run on a child stream channel.
     * {@code TRACE_CONTEXT}, {@code WS_REGISTRY_KEY} and the CORS attributes are excluded because they
     * are (re-)initialised on the child itself, not inherited from the connection.
     */
    public static final List<AttributeKey<?>> CONNECTION_SCOPED_ATTRIBUTES = Arrays.<AttributeKey<?>>asList(
        HttpRequestHandler.LOCAL_HOST_HEADERS,
        HttpRequestHandler.PROXYING,
        HttpActionHandler.REMOTE_SOCKET,
        PortUnificationHandler.HTTP2_ENABLED,
        PortUnificationHandler.TLS_ENABLED_UPSTREAM,
        PortUnificationHandler.TLS_ENABLED_DOWNSTREAM,
        PortUnificationHandler.NETTY_SSL_CONTEXT_FACTORY,
        SniHandler.NEGOTIATED_APPLICATION_PROTOCOL,
        SniHandler.UPSTREAM_SSL_HANDLER,
        SniHandler.UPSTREAM_SSL_ENGINE,
        SniHandler.UPSTREAM_CLIENT_CERTIFICATES,
        SniHandler.SNI_HOSTNAME
    );

    public static final ConnectionScopeHandler INSTANCE = new ConnectionScopeHandler();

    @Override
    public void handlerAdded(ChannelHandlerContext ctx) {
        Channel child = ctx.channel();
        Channel parent = child.parent();
        if (parent != null) {
            propagate(parent, child);
        }
        // Copy is a one-shot at pipeline construction; remove self so it never sits in the data path.
        ctx.pipeline().remove(this);
    }

    /**
     * Copies every {@link #CONNECTION_SCOPED_ATTRIBUTES connection-scoped attribute} whose value is
     * non-null from {@code parent} onto {@code child}. Attributes that were never set on the parent
     * are left unset on the child (rather than written as {@code null}), preserving the "attribute
     * absent" semantics that the reader helpers rely on.
     *
     * @param parent the connection (parent) channel that recorded the connection-scoped state
     * @param child  the per-stream (child) channel to copy the state onto
     */
    public static void propagate(Channel parent, Channel child) {
        for (AttributeKey<?> key : CONNECTION_SCOPED_ATTRIBUTES) {
            copyAttribute(parent, child, key);
        }
    }

    @SuppressWarnings("unchecked")
    private static <T> void copyAttribute(Channel parent, Channel child, AttributeKey<?> key) {
        AttributeKey<T> typedKey = (AttributeKey<T>) key;
        Attribute<T> parentAttribute = parent.attr(typedKey);
        T value = parentAttribute.get();
        if (value != null) {
            child.attr(typedKey).set(value);
        }
    }
}
