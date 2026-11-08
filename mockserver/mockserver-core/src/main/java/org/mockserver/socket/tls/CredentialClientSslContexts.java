package org.mockserver.socket.tls;

import io.netty.handler.ssl.SslContext;

import javax.annotation.Nullable;
import java.util.Locale;
import java.util.Map;

/**
 * The client contexts for forward connections that carry {@code forwardCredentialHeadersByHost} credentials, built
 * once by {@link NettySslContextFactory#createCredentialClientSslContexts()} and never rebuilt, so no later change to
 * the forward TLS settings reaches a connection that carries a credential.
 */
public final class CredentialClientSslContexts {

    private final SslContext http1;
    private final SslContext http2;
    // lower-case host to {HTTP/1.1, HTTP/2} contexts that present that host's forwardProxyClientCertificatesByHost pair
    private final Map<String, SslContext[]> byHost;
    @Nullable
    private final String clientCertificatesByHost;

    CredentialClientSslContexts(SslContext http1, SslContext http2, Map<String, SslContext[]> byHost, @Nullable String clientCertificatesByHost) {
        this.http1 = http1;
        this.http2 = http2;
        this.byHost = Map.copyOf(byHost);
        this.clientCertificatesByHost = clientCertificatesByHost;
    }

    /**
     * The context for a connection to {@code host}, offering HTTP/2 through ALPN when {@code enableHttp2}.
     */
    public SslContext forHost(boolean enableHttp2, @Nullable String host) {
        String[] perHost = NettySslContextFactory.resolveForwardProxyClientCertificate(clientCertificatesByHost, host);
        SslContext[] hostContexts = perHost != null ? byHost.get(host.toLowerCase(Locale.ROOT)) : null;
        if (hostContexts != null) {
            return hostContexts[enableHttp2 ? 1 : 0];
        }
        return enableHttp2 ? http2 : http1;
    }
}
