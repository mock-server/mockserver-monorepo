package org.mockserver.netty.unification;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.util.AttributeKey;
import org.junit.Test;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.mock.action.http.HttpActionHandler;
import org.mockserver.model.Protocol;
import org.mockserver.netty.HttpRequestHandler;
import org.mockserver.socket.tls.SniHandler;

import java.net.InetSocketAddress;
import java.security.cert.Certificate;
import java.util.HashSet;
import java.util.Set;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.arrayContaining;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.hamcrest.Matchers.sameInstance;
import static org.mockito.Mockito.mock;
import static org.mockserver.mock.action.http.HttpActionHandler.getRemoteAddress;
import static org.mockserver.netty.unification.PortUnificationHandler.isHttp2Enabled;
import static org.mockserver.netty.unification.PortUnificationHandler.isSslEnabledUpstream;
import static org.mockserver.socket.tls.SniHandler.getALPNProtocol;
import static org.mockserver.socket.tls.SniHandler.getSniHostname;
import static org.mockserver.socket.tls.SniHandler.retrieveClientCertificates;

/**
 * Fast regression guard for {@link ConnectionScopeHandler}.
 * <p>
 * HTTP/2 stream ({@code Http2StreamChannel}) child channels do not inherit their parent
 * connection channel's attribute map. {@link ConnectionScopeHandler} copies the connection-scoped
 * attributes across. These tests assert not merely that the raw attribute lands on the child, but
 * that the very reader helpers the affected subsystems call ({@code SniHandler.getALPNProtocol},
 * {@code PortUnificationHandler.isHttp2Enabled}, {@code SniHandler.retrieveClientCertificates},
 * {@code HttpRequestHandler}'s {@code PROXYING} read) return the correct connection-scoped value on
 * the child after propagation — and the broken default before it.
 */
public class ConnectionScopeHandlerTest {

    // reference the owning classes' public constants (the shared source of truth), not re-typed literals
    private static final AttributeKey<Boolean> PROXYING = HttpRequestHandler.PROXYING;
    private static final AttributeKey<Set<String>> LOCAL_HOST_HEADERS = HttpRequestHandler.LOCAL_HOST_HEADERS;

    private final MockServerLogger mockServerLogger = new MockServerLogger();

    @SuppressWarnings("unchecked")
    private static <T> void set(EmbeddedChannel channel, AttributeKey<?> key, T value) {
        channel.attr((AttributeKey<T>) key).set(value);
    }

    private static ChannelHandlerContext contextOf(EmbeddedChannel channel) {
        channel.pipeline().addLast("probe", new ChannelInboundHandlerAdapter());
        return channel.pipeline().context("probe");
    }

    /**
     * Every declared connection-scoped attribute set on the parent is copied to the child by value.
     */
    @Test
    public void shouldPropagateEveryConnectionScopedAttribute() {
        EmbeddedChannel parent = new EmbeddedChannel();
        EmbeddedChannel child = new EmbeddedChannel();
        try {
            // distinct sentinel per key so a mis-mapped copy is caught
            for (AttributeKey<?> key : ConnectionScopeHandler.CONNECTION_SCOPED_ATTRIBUTES) {
                set(parent, key, new Object());
            }

            ConnectionScopeHandler.propagate(parent, child);

            for (AttributeKey<?> key : ConnectionScopeHandler.CONNECTION_SCOPED_ATTRIBUTES) {
                assertThat("attribute " + key.name() + " must be copied to the child",
                    child.attr(key).get(), is(notNullValue()));
                assertThat("attribute " + key.name() + " must be the same instance as on the parent",
                    child.attr(key).get(), is(sameInstance(parent.attr(key).get())));
            }
        } finally {
            parent.finishAndReleaseAll();
            child.finishAndReleaseAll();
        }
    }

    /**
     * Attributes never set on the parent must be left unset (not written as {@code null}) on the
     * child, so the "attribute absent" semantics the reader helpers rely on are preserved.
     */
    @Test
    public void shouldNotWriteAttributesThatWereAbsentOnParent() {
        EmbeddedChannel parent = new EmbeddedChannel();
        EmbeddedChannel child = new EmbeddedChannel();
        try {
            set(parent, PROXYING, Boolean.TRUE); // only one attribute present

            ConnectionScopeHandler.propagate(parent, child);

            assertThat(child.attr(PROXYING).get(), is(Boolean.TRUE));
            for (AttributeKey<?> key : ConnectionScopeHandler.CONNECTION_SCOPED_ATTRIBUTES) {
                if (!key.name().equals("PROXYING")) {
                    assertThat("absent parent attribute " + key.name() + " must not be set on the child",
                        child.attr(key).get(), is(nullValue()));
                }
            }
        } finally {
            parent.finishAndReleaseAll();
            child.finishAndReleaseAll();
        }
    }

    /**
     * Row 1 (protocol detection) &amp; row 2 (WebSocket 501) &amp; row 3 (mTLS control-plane auth):
     * the reader helpers the affected subsystems call return the broken default on a bare child, and
     * the correct connection-scoped value once {@link ConnectionScopeHandler} has propagated.
     */
    @Test
    public void shouldRestoreReaderHelperResultsOnChild() {
        EmbeddedChannel parent = new EmbeddedChannel();
        Certificate[] clientCertificates = new Certificate[]{mock(Certificate.class)};
        Set<String> localHosts = new HashSet<>();
        localHosts.add("mockserver.example:1080");
        InetSocketAddress remoteSocket = new InetSocketAddress("upstream.internal", 8443);

        // connection-scoped state recorded on the parent during port unification / TLS handshake / proxy
        set(parent, PortUnificationHandler.HTTP2_ENABLED, Boolean.TRUE);
        set(parent, PortUnificationHandler.TLS_ENABLED_UPSTREAM, Boolean.TRUE);
        set(parent, SniHandler.NEGOTIATED_APPLICATION_PROTOCOL, Protocol.HTTP_2);
        set(parent, SniHandler.UPSTREAM_CLIENT_CERTIFICATES, clientCertificates);
        set(parent, SniHandler.SNI_HOSTNAME, "sni.example.com");
        set(parent, PROXYING, Boolean.TRUE);
        set(parent, HttpActionHandler.REMOTE_SOCKET, remoteSocket);
        set(parent, LOCAL_HOST_HEADERS, localHosts);

        // --- RED baseline: a bare child (no propagation) misreports every one of these ---
        EmbeddedChannel bareChild = new EmbeddedChannel();
        ChannelHandlerContext bareCtx = contextOf(bareChild);
        assertThat("protocol not detected on bare child", getALPNProtocol(mockServerLogger, bareCtx), is(nullValue()));
        assertThat("http2 gate false on bare child", isHttp2Enabled(bareChild), is(false));
        assertThat("no upstream TLS seen on bare child", isSslEnabledUpstream(bareChild), is(false));
        assertThat("no client certs on bare child", retrieveClientCertificates(mockServerLogger, bareCtx), is(nullValue()));
        assertThat("no SNI on bare child", getSniHostname(bareChild), is(nullValue()));
        assertThat("proxying flag lost on bare child", bareChild.attr(PROXYING).get(), is(nullValue()));
        assertThat("forward remote-socket lost on bare child", getRemoteAddress(bareCtx), is(nullValue()));
        assertThat("local-host set lost on bare child", bareChild.attr(LOCAL_HOST_HEADERS).get(), is(nullValue()));
        bareChild.finishAndReleaseAll();

        // --- GREEN: after propagation every reader helper returns the connection-scoped value ---
        EmbeddedChannel child = new EmbeddedChannel();
        ConnectionScopeHandler.propagate(parent, child);
        ChannelHandlerContext childCtx = contextOf(child);

        assertThat("protocol detected as HTTP_2 on child", getALPNProtocol(mockServerLogger, childCtx), is(Protocol.HTTP_2));
        assertThat("http2 gate true on child (drives WebSocket 501)", isHttp2Enabled(child), is(true));
        assertThat("upstream TLS seen on child", isSslEnabledUpstream(child), is(true));
        assertThat("client certs available on child (drives mTLS auth)",
            retrieveClientCertificates(mockServerLogger, childCtx), is(arrayContaining(clientCertificates)));
        assertThat("SNI hostname available on child", getSniHostname(child), is("sni.example.com"));
        assertThat("proxying flag preserved on child", child.attr(PROXYING).get(), is(Boolean.TRUE));
        assertThat("forward remote-socket preserved on child (drives proxied-forward target)",
            getRemoteAddress(childCtx), is(remoteSocket));
        assertThat("local-host set preserved on child", child.attr(LOCAL_HOST_HEADERS).get(), is(localHosts));

        child.finishAndReleaseAll();
        parent.finishAndReleaseAll();
    }

    /**
     * The handler copies once at pipeline construction and then removes itself, so it never sits in
     * the data path; and it must tolerate a channel with no parent without throwing.
     */
    @Test
    public void shouldRemoveItselfFromPipelineAndTolerateNoParent() {
        EmbeddedChannel channel = new EmbeddedChannel();
        try {
            channel.pipeline().addFirst("connectionScope", ConnectionScopeHandler.INSTANCE);

            // handlerAdded fired synchronously on add: the handler has already removed itself
            assertThat(channel.pipeline().get(ConnectionScopeHandler.class), is(nullValue()));
            assertThat(channel.pipeline().get("connectionScope"), is(nullValue()));
        } finally {
            channel.finishAndReleaseAll();
        }
    }
}
