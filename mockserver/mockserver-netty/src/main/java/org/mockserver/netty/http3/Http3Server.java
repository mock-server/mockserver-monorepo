package org.mockserver.netty.http3;

import org.mockserver.netty.controlplane.ControlPlaneListenerSettings;
import org.mockserver.netty.controlplane.ManagementSourceGuard;
import io.netty.bootstrap.Bootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.FixedRecvByteBufAllocator;
import io.netty.channel.epoll.EpollChannelOption;
import io.netty.channel.socket.DatagramChannel;
import io.netty.channel.socket.nio.NioDatagramChannel;
import io.netty.handler.codec.http3.DefaultHttp3SettingsFrame;
import io.netty.handler.codec.http3.Http3;
import io.netty.handler.codec.http3.Http3ServerConnectionHandler;
import io.netty.handler.codec.http3.Http3SettingsFrame;
import io.netty.handler.codec.quic.EpollQuicUtils;
import io.netty.handler.codec.quic.QuicChannel;
import io.netty.handler.codec.quic.QuicChannelOption;
import io.netty.handler.codec.quic.QuicServerCodecBuilder;
import io.netty.handler.codec.quic.QuicSslContext;
import io.netty.handler.codec.quic.QuicSslContextBuilder;
import io.netty.handler.codec.quic.QuicStreamChannel;
import io.netty.handler.codec.quic.SegmentedDatagramPacketAllocator;
import io.netty.handler.ssl.ClientAuth;
import io.netty.handler.ssl.util.InsecureTrustManagerFactory;
import io.netty.util.concurrent.DefaultThreadFactory;
import io.netty.util.concurrent.Promise;
import org.mockserver.configuration.Configuration;
import org.mockserver.configuration.ConfigurationProperties;
import org.mockserver.configuration.ServerTlsSettings;
import org.mockserver.lifecycle.Ipv4UdpPortProbe;
import org.mockserver.lifecycle.LifeCycle;
import org.mockserver.netty.unification.ClientTlsHandshakeFailureLog;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.netty.connection.ServerClosedChannels;
import org.mockserver.metrics.Metrics;
import org.mockserver.mock.HttpState;
import org.mockserver.mock.action.http.HttpActionHandler;
import org.mockserver.netty.mcp.McpRequestProcessor;
import org.mockserver.netty.mcp.McpSessionManager;
import org.mockserver.socket.NettyAllocator;
import org.mockserver.socket.NettyTransport;
import org.mockserver.socket.tls.DataPlaneMutualTlsTrust;
import org.mockserver.socket.tls.KeyAndCertificateFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.BindException;
import java.net.InetSocketAddress;
import java.net.SocketException;
import java.security.PrivateKey;
import java.security.cert.X509Certificate;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.apache.commons.lang3.StringUtils.isNotBlank;
import static org.mockserver.socket.tls.KeyAndCertificateFactoryFactory.createKeyAndCertificateFactory;

/**
 * HTTP/3 (QUIC) server for MockServer, integrated with the full request pipeline.
 * <p>
 * When started, HTTP/3 requests are routed through the same expectation matching,
 * action handling, recording, and proxy forwarding pipeline as HTTP/1.1 and HTTP/2.
 * <p>
 * The server uses MockServer's configured TLS certificate material. If no custom
 * certificate is configured, MockServer's auto-generated BouncyCastle certificate
 * is used. The QUIC transport requires a native BoringSSL library, which no longer ships in the
 * default artifacts (it lives in the {@code jar-with-dependencies-http3} classifier). If it is
 * unavailable, {@code MockServer.requireQuicNative} fails start-up with an actionable message
 * BEFORE any port is bound - it does not log a warning and continue, which used to leave the
 * configured UDP port silently unserved.
 * <p>
 * HTTP/3 is OFF by default ({@code http3Port=0}) and is built on the Netty 4.2
 * {@code netty-codec-http3} module (graduated from the incubator). HTTP/3 support
 * is labelled <strong>experimental</strong> because the API may evolve.
 */
public class Http3Server {

    private static final Logger LOG = LoggerFactory.getLogger(Http3Server.class);
    private static final long STOP_TIMEOUT_SECONDS = 5;
    /**
     * The listener's UDP receive buffer. At the platform default the kernel drops client datagrams, ACKs and
     * flow-control credit among them, while a large response is sent, and a client that does not resend lost credit
     * stalls. The operating system caps it (Linux at {@code net.core.rmem_max}).
     */
    static final int RECEIVE_BUFFER_BYTES = 4 * 1024 * 1024;
    /**
     * Datagrams the listener reads per event-loop cycle before the QUIC codec flushes (Netty's default is 16). Where
     * the operating system caps the receive buffer, draining more of it between write bursts keeps client ACKs and
     * flow-control credit from being dropped.
     */
    static final int MAX_DATAGRAMS_PER_READ = 256;
    private static final int DATAGRAM_READ_BUFFER_BYTES = 2048;
    /**
     * On the epoll listener, datagrams read by one {@code recvmmsg} call, and datagrams the QUIC codec sends in one
     * {@code UDP_SEGMENT} (GSO) write where the kernel supports it; both cut the system calls per event-loop cycle.
     */
    static final int EPOLL_DATAGRAMS_PER_RECVMMSG = 16;
    static final int EPOLL_GSO_MAX_SEGMENTS = 16;

    private final AtomicInteger activeHttp3Connections = new AtomicInteger(0);

    private volatile Channel channel;
    private volatile EventLoopGroup group;

    // pipeline components (null when using the legacy echo-only constructor)
    private final Configuration configuration;
    private final ControlPlaneListenerSettings sourcePolicy;
    private final MockServerLogger mockServerLogger;
    private final HttpState httpState;
    private final HttpActionHandler httpActionHandler;
    /** Null when MCP is not wired (legacy/test constructors). */
    private final McpRequestProcessor mcpRequestProcessor;
    private final ClientTlsHandshakeFailureLog clientTlsHandshakeFailureLog;

    /**
     * Create an HTTP/3 server wired into MockServer's request pipeline, with MCP support.
     *
     * @param configuration      the server configuration
     * @param mockServerLogger   the logger
     * @param httpState          the shared HTTP state (expectations, matchers, etc.)
     * @param httpActionHandler  the action handler for processing matched expectations
     * @param server             the MockServer lifecycle instance (for MCP tool registry)
     * @param mcpSessionManager  the shared MCP session manager (may be null to disable MCP)
     */
    public Http3Server(Configuration configuration, MockServerLogger mockServerLogger,
                       HttpState httpState, HttpActionHandler httpActionHandler,
                       LifeCycle server, McpSessionManager mcpSessionManager) {
        this.configuration = configuration;
        this.sourcePolicy = new ControlPlaneListenerSettings(configuration);
        this.mockServerLogger = mockServerLogger;
        this.httpState = httpState;
        this.httpActionHandler = httpActionHandler;
        this.mcpRequestProcessor = mcpSessionManager != null
            ? new McpRequestProcessor(httpState, server, mcpSessionManager)
            : null;
        ClientTlsHandshakeFailureLog serversLog = server != null ? server.getClientTlsHandshakeFailureLog() : null;
        this.clientTlsHandshakeFailureLog = serversLog != null ? serversLog : new ClientTlsHandshakeFailureLog();
    }

    /**
     * Create an HTTP/3 server wired into MockServer's request pipeline (no MCP).
     *
     * @param configuration    the server configuration
     * @param mockServerLogger the logger
     * @param httpState        the shared HTTP state (expectations, matchers, etc.)
     * @param httpActionHandler the action handler for processing matched expectations
     */
    public Http3Server(Configuration configuration, MockServerLogger mockServerLogger,
                       HttpState httpState, HttpActionHandler httpActionHandler) {
        this(configuration, mockServerLogger, httpState, httpActionHandler, null, null);
    }

    /**
     * Legacy constructor for backwards compatibility (echo-only mode).
     * Used by tests that do not need the full pipeline.
     */
    public Http3Server() {
        this.configuration = null;
        this.sourcePolicy = null;
        this.mockServerLogger = null;
        this.httpState = null;
        this.httpActionHandler = null;
        this.mcpRequestProcessor = null;
        this.clientTlsHandshakeFailureLog = new ClientTlsHandshakeFailureLog();
    }

    /**
     * Start the HTTP/3 server on the given UDP port.
     *
     * @param port UDP port to bind; use 0 for an ephemeral port
     * @return the actual bound port
     * @throws Exception if the server cannot start
     */
    public int start(int port) throws Exception {
        // its own single-thread loop, so a large HTTP/3 response cannot hold up HTTP/1 or HTTP/2 work
        EventLoopGroup localGroup = NettyTransport.newEventLoopGroup(1, new DefaultThreadFactory("mockserver-http3"), nativeTransport());
        Class<? extends DatagramChannel> listenerClass = NettyTransport.datagramChannelClassFor(localGroup);
        boolean epoll = listenerClass != NioDatagramChannel.class;
        ChannelFuture bind = null;
        boolean success = false;
        try {
            // create a shared Metrics instance for all QUIC streams (avoids per-stream allocation)
            Metrics sharedMetrics = configuration != null ? new Metrics(configuration) : null;

            QuicSslContext sslContext = buildQuicSslContext();

            // resolve transport parameters from configuration (or use defaults
            // that match the original hardcoded values for backward compat)
            long maxIdleTimeout = configuration != null ? configuration.http3MaxIdleTimeout() : 5000L;
            long initialMaxData = configuration != null ? configuration.http3InitialMaxData() : 10000000L;
            long initialMaxStreamDataBidi = configuration != null ? configuration.http3InitialMaxStreamDataBidirectional() : 1000000L;
            long initialMaxStreamsBidi = configuration != null ? configuration.http3InitialMaxStreamsBidirectional() : 100L;
            long qpackMaxTableCapacity = configuration != null ? configuration.http3QpackMaxTableCapacity() : 0L;

            // build settings frame: QPACK dynamic table + extended CONNECT
            DefaultHttp3SettingsFrame settingsFrame = new DefaultHttp3SettingsFrame();
            settingsFrame.put(Http3SettingsFrame.HTTP3_SETTINGS_QPACK_MAX_TABLE_CAPACITY, qpackMaxTableCapacity);
            if (configuration != null) {
                // the header section limit of every protocol; Netty refuses a larger one with H3_EXCESSIVE_LOAD
                settingsFrame.put(Http3SettingsFrame.HTTP3_SETTINGS_MAX_FIELD_SECTION_SIZE, (long) configuration.maxHeaderSize());
            }

            boolean connectUdpEnabled = configuration != null && Boolean.TRUE.equals(configuration.http3ConnectUdpEnabled());
            if (connectUdpEnabled) {
                // Advertise extended CONNECT support (RFC 9220) so clients can
                // send CONNECT requests with the :protocol pseudo-header
                settingsFrame.put(Http3SettingsFrame.HTTP3_SETTINGS_ENABLE_CONNECT_PROTOCOL, 1L);
            }

            AtomicInteger connectionCounter = this.activeHttp3Connections;
            // one serves every connection; the legacy echo mode has no logger of its own
            MockServerLogger handlerLogger = mockServerLogger != null ? mockServerLogger : new MockServerLogger(Http3Server.class);
            Http3ExceptionHandler exceptionHandler = Http3ExceptionHandler.forConnection(handlerLogger, configuration, clientTlsHandshakeFailureLog);

            QuicServerCodecBuilder codecBuilder = Http3.newQuicServerCodecBuilder()
                .sslContext(sslContext)
                .maxIdleTimeout(maxIdleTimeout, TimeUnit.MILLISECONDS)
                .initialMaxData(initialMaxData)
                .initialMaxStreamDataBidirectionalLocal(initialMaxStreamDataBidi)
                .initialMaxStreamDataBidirectionalRemote(initialMaxStreamDataBidi)
                .initialMaxStreamsBidirectional(initialMaxStreamsBidi)
                // source-address-validating (stateless-retry) token handler: binds the
                // client address into a keyed HMAC so a forged-source Initial packet cannot
                // obtain a valid retry token, mitigating QUIC address-spoofing / amplification.
                // Replaces Netty's InsecureQuicTokenHandler (plaintext, forgeable token).
                .tokenHandler(new SourceAddressQuicTokenHandler())
                // QUIC connection and stream channels do not inherit the datagram channel's allocator
                .option(ChannelOption.ALLOCATOR, NettyAllocator.ALLOCATOR)
                .streamOption(ChannelOption.ALLOCATOR, NettyAllocator.ALLOCATOR)
                .handler(new ChannelInitializer<QuicChannel>() {
                    @Override
                    protected void initChannel(QuicChannel ch) {
                        // track QUIC connection open/close for dashboard visibility
                        connectionCounter.incrementAndGet();
                        ch.closeFuture().addListener(f -> connectionCounter.decrementAndGet());

                        // disable the QPACK dynamic table when capacity is 0 (the default),
                        // matching the old 1-arg constructor behaviour; enable it only when
                        // the user has configured a non-zero qpackMaxTableCapacity
                        boolean disableQpackDynamicTable = qpackMaxTableCapacity == 0;
                        ch.pipeline().addLast(new Http3ServerConnectionHandler(
                            new ChannelInitializer<QuicStreamChannel>() {
                                @Override
                                protected void initChannel(QuicStreamChannel streamCh) {
                                    long writeStallTimeoutMillis = configuration != null ? configuration.responseWriteStallTimeoutMillis() : 0;
                                    if (writeStallTimeoutMillis > 0) {
                                        // first, so it sees each write the HTTP/3 frame codec makes
                                        streamCh.pipeline().addFirst(new Http3StreamWriteStallHandler(writeStallTimeoutMillis, mockServerLogger));
                                    }
                                    if (httpState != null && httpActionHandler != null && configuration != null) {
                                        if (sourcePolicy.restrictsSources()) {
                                            streamCh.pipeline().addLast(new ManagementSourceGuard(sourcePolicy, httpState));
                                        }
                                        if (connectUdpEnabled) {
                                            streamCh.pipeline().addLast(new Http3ConnectUdpHandler(configuration, handlerLogger));
                                        }
                                        streamCh.pipeline().addLast(new Http3MockServerHandler(
                                            configuration, mockServerLogger, httpState, httpActionHandler, sharedMetrics,
                                            mcpRequestProcessor
                                        ));
                                    } else {
                                        streamCh.pipeline().addLast(new Http3EchoRequestHandler());
                                    }
                                }
                            },
                            null, null, settingsFrame, disableQpackDynamicTable
                        ));
                        // last: it takes what Netty's handler passes on, and sees each stream the client opens
                        ch.pipeline().addLast(exceptionHandler);
                    }
                });
            SegmentedDatagramPacketAllocator segmentedAllocator = segmentedAllocator(epoll);
            Http3SegmentedSends segmentedSends = segmentedAllocator.maxNumSegments() > 0 ? new Http3SegmentedSends(segmentedAllocator, handlerLogger) : null;
            if (segmentedSends != null) {
                codecBuilder.option(QuicChannelOption.SEGMENTED_DATAGRAM_PACKET_ALLOCATOR, segmentedSends);
            }
            ChannelHandler codec = codecBuilder.build();

            int datagramsPerReadCall = epoll ? EPOLL_DATAGRAMS_PER_RECVMMSG : 1;
            Bootstrap bootstrap = new Bootstrap()
                .group(localGroup)
                .channel(listenerClass)
                .option(ChannelOption.ALLOCATOR, NettyAllocator.ALLOCATOR)
                .option(ChannelOption.SO_RCVBUF, RECEIVE_BUFFER_BYTES)
                .option(ChannelOption.RCVBUF_ALLOCATOR,
                    new FixedRecvByteBufAllocator(DATAGRAM_READ_BUFFER_BYTES * datagramsPerReadCall).maxMessagesPerRead(MAX_DATAGRAMS_PER_READ))
                .handler(new ChannelInitializer<Channel>() {
                    @Override
                    protected void initChannel(Channel listener) {
                        // the QUIC codec takes no exception, so without this one would reach the end of the pipeline
                        listener.pipeline().addLast(codec);
                        if (segmentedSends != null) {
                            listener.pipeline().addLast(segmentedSends);
                        }
                        listener.pipeline().addLast(new Http3ListenerExceptionHandler(handlerLogger, epoll));
                    }
                });
            if (epoll) {
                bootstrap.option(EpollChannelOption.MAX_DATAGRAM_PAYLOAD_SIZE, DATAGRAM_READ_BUFFER_BYTES);
            }
            String localBoundIP = configuration != null ? configuration.localBoundIP() : null;
            InetSocketAddress listenerAddress = Ipv4UdpPortProbe.listenerAddress(localBoundIP, port);
            bind = port == 0 ? bootstrap.bind(listenerAddress) : bindExplicitPort(bootstrap, listenerAddress);
            Channel listener = bound(bind, epoll);
            int boundPort = ((InetSocketAddress) listener.localAddress()).getPort();
            if (Ipv4UdpPortProbe.loopbackReachesAnotherSocket(listener)) {
                // taken on 0.0.0.0 between the probe and the bind, which macOS then let this socket share
                throw Ipv4UdpPortProbe.ipv4WildcardConflict(boundPort, "HTTP/3 requests", "http3Port");
            }
            channel = listener;

            LOG.info("HTTP/3 (QUIC) server started on UDP port: {} ({})", boundPort, epoll ? "epoll" : "NIO");
            group = localGroup;
            success = true;
            return boundPort;
        } finally {
            if (!success) {
                if (bind != null) {
                    tearDown(bind.channel(), localGroup);
                }
                // no quiet period, and waited for: Netty's default would leave its thread running 2 s past the refusal
                if (!terminated(localGroup)) {
                    LOG.warn("HTTP/3 (QUIC) event loop of a refused start did not terminate within {}s", STOP_TIMEOUT_SECONDS);
                }
            }
        }
    }

    /**
     * Refuses a port another application holds on the IPv4 wildcard, which macOS would otherwise let this
     * dual-stack socket share while delivering its localhost datagrams to that application (see
     * {@link Ipv4UdpPortProbe}). The refusal is decided before Netty binds anything, so a refused port is free
     * as soon as this throws. Where the bind itself fails (Linux, or an IPv4-only stack), its own error is kept.
     */
    private ChannelFuture bindExplicitPort(Bootstrap bootstrap, InetSocketAddress listenerAddress) throws Exception {
        if (shadowedOnIpv4(listenerAddress)) {
            throw Ipv4UdpPortProbe.ipv4WildcardConflict(listenerAddress.getPort(), "HTTP/3 requests", "http3Port");
        }
        return bootstrap.bind(listenerAddress);
    }

    // a seam: tests take the port on IPv4 after this probe, as another application could
    boolean shadowedOnIpv4(InetSocketAddress listenerAddress) {
        return Ipv4UdpPortProbe.shadowedOnIpv4(listenerAddress);
    }

    /**
     * The QUIC codec releases the direct buffer it allocates when added only once the channel's pipeline is torn
     * down, which Netty does on a task the close queues; a loop shut down with no quiet period can end without
     * running it. The second task is queued from the loop, so after it.
     */
    private static void tearDown(Channel failed, EventLoopGroup group) {
        failed.close().awaitUninterruptibly(STOP_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        Promise<Void> tornDown = group.next().newPromise();
        group.execute(() -> group.execute(() -> tornDown.setSuccess(null)));
        tornDown.awaitUninterruptibly(STOP_TIMEOUT_SECONDS, TimeUnit.SECONDS);
    }

    /**
     * Not {@code sync()}, which also rethrows a failed bind's cause: an {@link InterruptedException} from here is
     * always this thread's own wait, so the interrupt flag is set again for the caller.
     */
    private static Channel bound(ChannelFuture bind, boolean epoll) throws Exception {
        try {
            bind.await();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw interrupted;
        }
        if (!bind.isSuccess()) {
            Throwable cause = bind.cause();
            if (epoll && cause instanceof IOException && !(cause instanceof SocketException)) {
                // epoll refuses a bind with its own IOException; start-up reports a BindException, as NIO raises.
                // Suppressed, not the cause: the start-up message names the root cause's class
                BindException refused = new BindException(cause.getMessage());
                refused.setStackTrace(cause.getStackTrace());
                refused.addSuppressed(cause);
                throw refused;
            }
            if (cause instanceof Error) {
                throw (Error) cause;
            }
            throw cause instanceof Exception ? (Exception) cause : new Exception(cause);
        }
        return bind.channel();
    }

    // a seam: tests give a NIO listener a segmented allocator, to fail its sends
    SegmentedDatagramPacketAllocator segmentedAllocator(boolean epoll) {
        return epoll ? EpollQuicUtils.newSegmentedAllocator(EPOLL_GSO_MAX_SEGMENTS) : SegmentedDatagramPacketAllocator.NONE;
    }

    private boolean nativeTransport() {
        return configuration != null ? Boolean.TRUE.equals(configuration.useNativeTransport()) : ConfigurationProperties.useNativeTransport();
    }

    /**
     * Returns the current number of active QUIC (HTTP/3) connections.
     */
    public int getActiveConnectionCount() {
        return activeHttp3Connections.get();
    }

    /**
     * @return the UDP listener's channel, or null if not started
     */
    Channel listener() {
        return channel;
    }

    /**
     * Returns the bound UDP port, or -1 if not started.
     */
    public int getPort() {
        if (channel != null && channel.localAddress() instanceof InetSocketAddress) {
            return ((InetSocketAddress) channel.localAddress()).getPort();
        }
        return -1;
    }

    /**
     * Stop the HTTP/3 server and release resources.
     */
    public void stop() {
        int port = getPort();
        if (channel != null) {
            // the QUIC codec closes the request streams with the listener, before the event loop is shut down
            ServerClosedChannels.markClosed(channel);
            try {
                channel.close().sync();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            channel = null;
        }
        if (group != null) {
            // a selector-registered socket is closed only when its event loop deregisters it, so the port is
            // free for a restart only once the loop has terminated
            if (!terminated(group)) {
                LOG.warn("HTTP/3 (QUIC) event loop did not terminate within {}s, so UDP port {} may still be bound", STOP_TIMEOUT_SECONDS, port);
            }
            group = null;
        }
        LOG.info("HTTP/3 (QUIC) server stopped");
    }

    private static boolean terminated(EventLoopGroup group) {
        return group.shutdownGracefully(0, STOP_TIMEOUT_SECONDS, TimeUnit.SECONDS).awaitUninterruptibly(STOP_TIMEOUT_SECONDS, TimeUnit.SECONDS);
    }

    /**
     * Build the QUIC SSL context using MockServer's configured TLS material when
     * available, falling back to a self-signed certificate when no configuration
     * is provided (legacy/echo mode).
     */
    private QuicSslContext buildQuicSslContext() throws Exception {
        PrivateKey privateKey;
        X509Certificate[] certChain;
        KeyAndCertificateFactory keyAndCertFactory = null;

        if (configuration != null && mockServerLogger != null) {
            // use MockServer's TLS certificate infrastructure
            keyAndCertFactory = createKeyAndCertificateFactory(configuration, mockServerLogger);
            if (keyAndCertFactory.certificateNotYetCreated()) {
                keyAndCertFactory.buildAndSavePrivateKeyAndX509Certificate();
            }
            privateKey = keyAndCertFactory.privateKey();
            List<X509Certificate> chain = keyAndCertFactory.certificateChain();
            certChain = chain.toArray(new X509Certificate[0]);
            LOG.info("HTTP/3 server using MockServer's configured TLS certificate");
        } else {
            // legacy self-signed fallback
            java.security.KeyPair keyPair = generateKeyPair();
            privateKey = keyPair.getPrivate();
            certChain = new X509Certificate[]{generateSelfSignedCert(keyPair)};
            LOG.info("HTTP/3 server using self-signed certificate (no configuration provided)");
        }

        QuicSslContextBuilder quicSslBuilder = QuicSslContextBuilder
            .forServer(privateKey, null, certChain)
            .applicationProtocols(Http3.supportedApplicationProtocols());

        // mTLS (client authentication) for QUIC -- mirrors the TCP path's
        // NettySslContextFactory logic:
        // - clientAuth is REQUIRE when tlsMutualAuthenticationRequired is true,
        //   OPTIONAL otherwise (OPTIONAL is the safe default: it will request
        //   a client cert but not reject connections that don't present one)
        // - trustManager is DataPlaneMutualTlsTrust's client-certificate trust when mTLS is on,
        //   or an insecure trust-all factory when it is not
        //   (same as the TCP path's InsecureTrustManagerFactory fallback)
        if (configuration != null && keyAndCertFactory != null) {
            // one snapshot, so the client-auth mode and trust chain come from the same configuration update
            ServerTlsSettings tlsSettings = ServerTlsSettings.of(configuration);
            boolean mutualAuthenticationRequired = Boolean.TRUE.equals(tlsSettings.tlsMutualAuthenticationRequired());
            quicSslBuilder.clientAuth(mutualAuthenticationRequired ? ClientAuth.REQUIRE : ClientAuth.OPTIONAL);
            if (isNotBlank(tlsSettings.tlsMutualAuthenticationCertificateChain()) || mutualAuthenticationRequired) {
                quicSslBuilder.trustManager(DataPlaneMutualTlsTrust.clientCertificateTrust(
                    tlsSettings.tlsMutualAuthenticationCertificateChain(),
                    keyAndCertFactory.certificateAuthorityX509Certificate(),
                    Boolean.TRUE.equals(configuration.tlsMutualAuthenticationAllowBundledCa()),
                    mockServerLogger
                ));
            } else {
                quicSslBuilder.trustManager(InsecureTrustManagerFactory.INSTANCE);
            }
        }

        return quicSslBuilder.build();
    }

    /**
     * Check whether the native QUIC transport is available on this platform.
     *
     * @return true if the native BoringSSL QUIC library is loadable
     */
    public static boolean isQuicAvailable() {
        try {
            return io.netty.handler.codec.quic.Quic.isAvailable();
        } catch (Throwable t) {
            return false;
        }
    }

    // -- self-signed cert generation for legacy mode --

    private static java.security.KeyPair generateKeyPair() throws Exception {
        java.security.KeyPairGenerator keyPairGen = java.security.KeyPairGenerator.getInstance("EC");
        keyPairGen.initialize(256, new java.security.SecureRandom());
        return keyPairGen.generateKeyPair();
    }

    /**
     * Generate the legacy echo-mode self-signed certificate. Reached only when {@code configuration == null}
     * (transport-level echo testing); the configured HTTP/3 path goes through the real
     * {@link KeyAndCertificateFactory} and gets the hardened short-lived leaf.
     * <p>
     * This certificate is simultaneously trust anchor AND server certificate and has no renewal loop
     * behind it, so it deliberately keeps the long CA-style validity ({@link KeyAndCertificateFactory#CERTIFICATE_VALIDITY_YEARS})
     * rather than the short 397-day leaf validity — a short-lived self-signed anchor with nothing to renew
     * it would simply expire the echo endpoint. It is otherwise brought up to the same standard as the real
     * leaf: 5-day back-dated notBefore (clock-skew tolerance), a positive serial (RFC 5280 §4.1.2.2), a SAN
     * covering localhost / 127.0.0.1 / ::1, serverAuth (+ clientAuth) EKU (Apple requires serverAuth) and a
     * TLS-server keyUsage.
     */
    private static X509Certificate generateSelfSignedCert(java.security.KeyPair keyPair) throws Exception {
        org.bouncycastle.asn1.x500.X500Name issuer = new org.bouncycastle.asn1.x500.X500Name("CN=MockServer HTTP/3, O=MockServer");
        java.math.BigInteger serial = KeyAndCertificateFactory.positiveSerialNumber();
        java.util.Date notBefore = KeyAndCertificateFactory.notBefore();
        java.util.Date notAfter = KeyAndCertificateFactory.notAfter();

        org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder builder = new org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder(
            issuer, serial, notBefore, notAfter, issuer, keyPair.getPublic()
        );

        org.bouncycastle.asn1.x509.GeneralName[] sanEntries = new org.bouncycastle.asn1.x509.GeneralName[]{
            new org.bouncycastle.asn1.x509.GeneralName(org.bouncycastle.asn1.x509.GeneralName.dNSName, "localhost"),
            new org.bouncycastle.asn1.x509.GeneralName(org.bouncycastle.asn1.x509.GeneralName.iPAddress, "127.0.0.1"),
            new org.bouncycastle.asn1.x509.GeneralName(org.bouncycastle.asn1.x509.GeneralName.iPAddress, "::1")
        };
        builder.addExtension(org.bouncycastle.asn1.x509.Extension.subjectAlternativeName, false, new org.bouncycastle.asn1.x509.GeneralNames(sanEntries));
        builder.addExtension(org.bouncycastle.asn1.x509.Extension.extendedKeyUsage, false, new org.bouncycastle.asn1.x509.ExtendedKeyUsage(new org.bouncycastle.asn1.x509.KeyPurposeId[]{
            org.bouncycastle.asn1.x509.KeyPurposeId.id_kp_serverAuth,
            org.bouncycastle.asn1.x509.KeyPurposeId.id_kp_clientAuth
        }));
        builder.addExtension(org.bouncycastle.asn1.x509.Extension.keyUsage, true, new org.bouncycastle.asn1.x509.KeyUsage(
            org.bouncycastle.asn1.x509.KeyUsage.digitalSignature | org.bouncycastle.asn1.x509.KeyUsage.keyEncipherment
        ));

        org.bouncycastle.operator.ContentSigner signer = new org.bouncycastle.operator.jcajce.JcaContentSignerBuilder("SHA256withECDSA")
            .build(keyPair.getPrivate());
        org.bouncycastle.cert.X509CertificateHolder holder = builder.build(signer);

        return new org.bouncycastle.cert.jcajce.JcaX509CertificateConverter().getCertificate(holder);
    }

    /**
     * Legacy echo request handler, kept for backward compatibility and basic
     * transport-level testing when the full pipeline is not wired.
     */
    static class Http3EchoRequestHandler extends io.netty.handler.codec.http3.Http3RequestStreamInboundHandler {

        @Override
        protected void channelRead(
            io.netty.channel.ChannelHandlerContext ctx,
            io.netty.handler.codec.http3.Http3HeadersFrame headersFrame
        ) {
            CharSequence methodSeq = headersFrame.headers().method();
            CharSequence pathSeq = headersFrame.headers().path();
            String method = methodSeq != null ? methodSeq.toString() : "UNKNOWN";
            String path = pathSeq != null ? pathSeq.toString() : "/";

            String responseBody = "MockServer HTTP/3 echo - method: " + method + ", path: " + path;
            byte[] bodyBytes = responseBody.getBytes(java.nio.charset.StandardCharsets.UTF_8);

            io.netty.handler.codec.http3.DefaultHttp3HeadersFrame responseHeaders = new io.netty.handler.codec.http3.DefaultHttp3HeadersFrame();
            responseHeaders.headers().status("200");
            responseHeaders.headers().add("content-type", "text/plain; charset=utf-8");
            responseHeaders.headers().addInt("content-length", bodyBytes.length);
            responseHeaders.headers().add("server", "mockserver-http3-experimental");

            ctx.write(responseHeaders);
            ctx.writeAndFlush(new io.netty.handler.codec.http3.DefaultHttp3DataFrame(
                io.netty.buffer.Unpooled.wrappedBuffer(bodyBytes)
            )).addListener(QuicStreamChannel.SHUTDOWN_OUTPUT);
        }

        @Override
        protected void channelRead(
            io.netty.channel.ChannelHandlerContext ctx,
            io.netty.handler.codec.http3.Http3DataFrame dataFrame
        ) {
            // echo handler: ignore request body data frames
            io.netty.util.ReferenceCountUtil.release(dataFrame);
        }

        @Override
        protected void channelInputClosed(io.netty.channel.ChannelHandlerContext ctx) {
            // stream input closed by peer - nothing to do for the echo handler
        }
    }
}
