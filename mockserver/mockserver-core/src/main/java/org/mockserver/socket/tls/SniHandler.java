package org.mockserver.socket.tls;

import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.DecoderException;
import io.netty.handler.ssl.AbstractSniHandler;
import io.netty.handler.ssl.ApplicationProtocolNames;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslHandler;
import io.netty.util.AttributeKey;
import io.netty.util.ReferenceCountUtil;
import io.netty.util.concurrent.Future;
import io.netty.util.concurrent.Promise;
import io.netty.util.internal.PlatformDependent;
import org.mockserver.configuration.Configuration;
import org.mockserver.log.model.LogEntry;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.model.Protocol;
import org.slf4j.event.Level;

import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLPeerUnverifiedException;
import javax.net.ssl.SSLSession;
import java.security.cert.Certificate;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.apache.commons.lang3.StringUtils.isNotBlank;
import static org.slf4j.event.Level.TRACE;
import static org.slf4j.event.Level.WARN;

/**
 * @author jamesdbloom
 */
public class SniHandler extends AbstractSniHandler<SslContext> {

    // public so the single AttributeKey instance is the shared source of truth -- ConnectionScopeHandler
    // references these directly to propagate connection-scoped TLS state onto HTTP/2 stream child channels.
    public static final AttributeKey<SSLEngine> UPSTREAM_SSL_ENGINE = AttributeKey.valueOf("UPSTREAM_SSL_ENGINE");
    public static final AttributeKey<SslHandler> UPSTREAM_SSL_HANDLER = AttributeKey.valueOf("UPSTREAM_SSL_HANDLER");
    public static final AttributeKey<Certificate[]> UPSTREAM_CLIENT_CERTIFICATES = AttributeKey.valueOf("UPSTREAM_CLIENT_CERTIFICATES");
    public static final AttributeKey<Protocol> NEGOTIATED_APPLICATION_PROTOCOL = AttributeKey.valueOf("NEGOTIATED_APPLICATION_PROTOCOL");

    /**
     * The SNI hostname presented by the client during the TLS handshake, recorded on the channel so
     * that diagnostic log lines (e.g. SSL/decoder-fault messages) can identify which target host /
     * client a failed connection was for. Captured in {@link #lookup(ChannelHandlerContext, String)}.
     */
    public static final AttributeKey<String> SNI_HOSTNAME = AttributeKey.valueOf("SNI_HOSTNAME");

    /**
     * Shared, bounded pool that runs the (potentially RSA-keygen-heavy) server SSL context provisioning
     * OFF the Netty event loop (defect C5). Previously {@code createServerSslContext()} ran synchronously
     * inside {@link #lookup}, so certificate generation blocked the event loop — and, under a global
     * lock, stalled every other connection on that loop. The pool is CPU-bound work sized to the
     * available processors; daemon threads so it never blocks JVM shutdown.
     */
    private static final ExecutorService SSL_CONTEXT_EXECUTOR = Executors.newFixedThreadPool(
        Math.max(2, Runtime.getRuntime().availableProcessors()),
        new java.util.concurrent.ThreadFactory() {
            private final AtomicInteger counter = new AtomicInteger();

            @Override
            public Thread newThread(Runnable runnable) {
                Thread thread = new Thread(runnable, "mockserver-ssl-context-" + counter.incrementAndGet());
                thread.setDaemon(true);
                return thread;
            }
        });

    private final Configuration configuration;
    private final NettySslContextFactory nettySslContextFactory;
    /**
     * Coalesces concurrent SNI lookups for the SAME host onto a single in-flight generation, so a burst
     * of parallel handshakes to one host does not each trigger a separate provisioning (defect C5).
     */
    private final ConcurrentHashMap<String, CompletableFuture<SslContext>> inFlightByHost = new ConcurrentHashMap<>();

    public SniHandler(Configuration configuration, NettySslContextFactory nettySslContextFactory) {
        this.configuration = configuration;
        this.nettySslContextFactory = nettySslContextFactory;
    }

    @Override
    protected Future<SslContext> lookup(ChannelHandlerContext ctx, String hostname) {
        if (isNotBlank(hostname)) {
            // add the SAN on the event loop BEFORE the (offloaded) generation runs, so the generated
            // certificate is guaranteed to contain this host's SAN
            configuration.addSubjectAlternativeName(hostname);
            ctx.channel().attr(SNI_HOSTNAME).set(hostname);
        }
        String host = isNotBlank(hostname) ? hostname.toLowerCase(Locale.ROOT) : "";
        CompletableFuture<SslContext> generation = inFlightByHost.computeIfAbsent(host, key -> {
            CompletableFuture<SslContext> future =
                CompletableFuture.supplyAsync(nettySslContextFactory::createServerSslContext, SSL_CONTEXT_EXECUTOR);
            future.whenComplete((sslContext, throwable) -> inFlightByHost.remove(key, future));
            return future;
        });
        Promise<SslContext> promise = ctx.executor().newPromise();
        generation.whenComplete((sslContext, throwable) -> {
            if (throwable != null) {
                promise.setFailure(throwable instanceof CompletionException && throwable.getCause() != null ? throwable.getCause() : throwable);
            } else {
                promise.setSuccess(sslContext);
            }
        });
        return promise;
    }

    @Override
    protected void onLookupComplete(ChannelHandlerContext ctx, String hostname, Future<SslContext> sslContextFuture) {
        if (!sslContextFuture.isSuccess()) {
            final Throwable cause = sslContextFuture.cause();
            if (cause instanceof Error) {
                throw (Error) cause;
            }
            throw new DecoderException("Failed to get the SslContext for " + hostname, cause);
        } else {
            try {
                replaceHandler(ctx, sslContextFuture);
            } catch (Throwable cause) {
                PlatformDependent.throwException(cause);
            }
        }
    }

    private void replaceHandler(ChannelHandlerContext ctx, Future<SslContext> sslContext) {
        SslHandler sslHandler = null;
        try {
            sslHandler = sslContext.getNow().newHandler(ctx.alloc());
            ctx.channel().attr(UPSTREAM_SSL_ENGINE).set(sslHandler.engine());
            ctx.channel().attr(UPSTREAM_SSL_HANDLER).set(sslHandler);
            ctx.pipeline().replace(this, "SslHandler#0", sslHandler);
            sslHandler = null;
        } finally {
            // Since the SslHandler was not inserted into the pipeline the ownership of the SSLEngine was not
            // transferred to the SslHandler.
            // See https://github.com/netty/netty/issues/5678
            if (sslHandler != null) {
                ReferenceCountUtil.safeRelease(sslHandler.engine());
            }
        }
    }

    public static Certificate[] retrieveClientCertificates(MockServerLogger mockServerLogger, ChannelHandlerContext ctx) {
        Certificate[] clientCertificates = null;
        if (ctx.channel().attr(UPSTREAM_CLIENT_CERTIFICATES).get() != null) {
            clientCertificates = ctx.channel().attr(UPSTREAM_CLIENT_CERTIFICATES).get();
        } else if (ctx.channel().attr(UPSTREAM_SSL_ENGINE).get() != null) {
            SSLEngine sslEngine = ctx.channel().attr(UPSTREAM_SSL_ENGINE).get();
            if (sslEngine != null) {
                SSLSession sslSession = sslEngine.getSession();
                if (sslSession != null) {
                    try {
                        Certificate[] peerCertificates = sslSession.getPeerCertificates();
                        ctx.channel().attr(UPSTREAM_CLIENT_CERTIFICATES).set(peerCertificates);
                        return peerCertificates;
                    } catch (SSLPeerUnverifiedException ignore) {
                        if (MockServerLogger.isEnabled(TRACE) && mockServerLogger != null) {
                            mockServerLogger.logEvent(
                                new LogEntry()
                                    .setLogLevel(Level.TRACE)
                                    .setMessageFormat("no client certificate chain as client did not complete mTLS")
                            );
                        }
                    }
                }
            }
        }
        return clientCertificates;
    }

    /**
     * Record the negotiated application protocol on the channel as a trusted, server-side signal.
     * Used by the cleartext HTTP/2 (h2c) path, where there is no ALPN to read the protocol from: the
     * pipeline detects the h2c connection preface and marks the channel as HTTP/2 so downstream
     * stream-scoped behaviour (e.g. capturing the HTTP/2 stream id) is enabled for genuine h2c
     * connections without trusting any client-supplied header.
     *
     * @param channel  the channel the protocol was negotiated on
     * @param protocol the negotiated protocol (e.g. {@link Protocol#HTTP_2} for h2c)
     */
    public static void setNegotiatedApplicationProtocol(io.netty.channel.Channel channel, Protocol protocol) {
        if (channel != null && protocol != null) {
            channel.attr(NEGOTIATED_APPLICATION_PROTOCOL).set(protocol);
        }
    }

    /**
     * Returns the SNI hostname recorded on the given channel during the TLS handshake, or
     * {@code null} if none was captured (e.g. plaintext connection, or no SNI presented).
     * Null-safe — a {@code null} channel returns {@code null}.
     *
     * @param channel the channel to read the SNI hostname from
     * @return the SNI hostname, or {@code null}
     */
    public static String getSniHostname(io.netty.channel.Channel channel) {
        return channel != null ? channel.attr(SNI_HOSTNAME).get() : null;
    }

    public static Protocol getALPNProtocol(MockServerLogger mockServerLogger, ChannelHandlerContext ctx) {
        Protocol protocol = null;
        try {
            if (ctx != null && ctx.channel() != null) {
                if (ctx.channel().attr(NEGOTIATED_APPLICATION_PROTOCOL).get() != null) {
                    return ctx.channel().attr(NEGOTIATED_APPLICATION_PROTOCOL).get();
                } else if (ctx.channel().attr(UPSTREAM_SSL_HANDLER).get() != null) {
                    SslHandler sslHandler = ctx.channel().attr(UPSTREAM_SSL_HANDLER).get();
                    String negotiatedApplicationProtocol = sslHandler.applicationProtocol();
                    if (isNotBlank(negotiatedApplicationProtocol)) {
                        if (negotiatedApplicationProtocol.equalsIgnoreCase(ApplicationProtocolNames.HTTP_2)) {
                            protocol = Protocol.HTTP_2;
                        } else if (negotiatedApplicationProtocol.equalsIgnoreCase(ApplicationProtocolNames.HTTP_1_1)) {
                            protocol = Protocol.HTTP_1_1;
                        }
                        ctx.channel().attr(NEGOTIATED_APPLICATION_PROTOCOL).set(protocol);
                        if (MockServerLogger.isEnabled(TRACE) && mockServerLogger != null) {
                            mockServerLogger.logEvent(
                                new LogEntry()
                                    .setLogLevel(Level.TRACE)
                                    .setMessageFormat("found ALPN protocol:{}")
                                    .setArguments(negotiatedApplicationProtocol)
                            );
                        }
                    }
                }
            }
        } catch (Throwable throwable) {
            if (MockServerLogger.isEnabled(WARN) && mockServerLogger != null) {
                mockServerLogger.logEvent(
                    new LogEntry()
                        .setLogLevel(Level.WARN)
                        .setMessageFormat("exception reading ALPN protocol")
                        .setThrowable(throwable)
                );
            }
        }
        return protocol;
    }
}
