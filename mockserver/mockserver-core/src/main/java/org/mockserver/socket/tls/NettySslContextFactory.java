package org.mockserver.socket.tls;

import io.netty.handler.codec.http2.Http2SecurityUtil;
import io.netty.handler.ssl.*;
import io.netty.handler.ssl.util.InsecureTrustManagerFactory;
import org.mockserver.configuration.Configuration;
import org.mockserver.configuration.ConfigurationProperties;
import org.mockserver.configuration.ControlPlaneAuthenticationSettings;
import org.mockserver.configuration.ServerTlsSettings;
import org.mockserver.file.FileReader;
import org.mockserver.log.model.LogEntry;
import org.mockserver.logging.MockServerLogger;
import org.slf4j.event.Level;

import javax.annotation.Nullable;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLException;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;
import java.io.File;
import java.security.KeyStore;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.security.PrivateKey;
import java.security.cert.X509Certificate;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.LongSupplier;
import java.util.function.UnaryOperator;

import static org.apache.commons.lang3.StringUtils.isBlank;
import static org.apache.commons.lang3.StringUtils.isNotBlank;
import static org.mockserver.configuration.Configuration.configuration;
import static org.mockserver.socket.tls.KeyAndCertificateFactoryFactory.createKeyAndCertificateFactory;
import static org.mockserver.socket.tls.PEMToFile.x509ChainFromPEM;
import static org.mockserver.socket.tls.PEMToFile.x509ChainFromPEMFile;

/**
 * @author jamesdbloom
 */
public class NettySslContextFactory {

    public static Function<SslContextBuilder, SslContext> clientSslContextBuilderFunction =
        sslContextBuilder -> {
            try {
                return sslContextBuilder.build();
            } catch (SSLException e) {
                throw new RuntimeException(e);
            }
        };
    public static Consumer<NettySslContextFactory> nettySslContextFactoryCustomizer = factory -> {
    };
    public static UnaryOperator<SslContextBuilder> sslServerContextBuilderCustomizer = UnaryOperator.identity();
    public static UnaryOperator<SslContextBuilder> sslClientContextBuilderCustomizer = UnaryOperator.identity();

    private final Configuration configuration;
    private final MockServerLogger mockServerLogger;
    private final KeyAndCertificateFactory keyAndCertificateFactory;
    private final Map<String, SslContext> clientSslContexts = new ConcurrentHashMap<>();
    // Parsed forward-proxy PEM material cached by its configuration value (file path or inline PEM)
    // so an unchanged forward-proxy key/chain is not re-parsed on every TLS context (re)build.
    private final Map<String, PrivateKey> forwardProxyPrivateKeyCache = new ConcurrentHashMap<>();
    private final Map<String, X509Certificate[]> forwardProxyCertificateChainCache = new ConcurrentHashMap<>();
    private final AtomicReference<ServerContextState> serverContextState = new AtomicReference<>();
    private final Object sslContextLock = new Object();
    private Function<SslContextBuilder, SslContext> instanceClientSslContextBuilderFunction = clientSslContextBuilderFunction;
    private final boolean forServer;
    private final boolean forMockServerClient;

    /**
     * Logged at most once per JVM (see {@link #warnIfBundledCertificateAuthorityInUse()}): the publicly
     * published bundled CA — whose private key ships in the jar — is signing served traffic.
     * Package-private and resettable so the once-only latch can be exercised by tests.
     */
    static final AtomicBoolean BUNDLED_CA_WARNING_LOGGED = new AtomicBoolean(false);

    // ---- cheap, time-bounded re-check of a user-supplied FIXED server certificate ----
    // Self-generated leaves self-renew via KeyAndCertificateFactory#certificateNeedsRenewal(); fixed
    // certificates are validated once at build and then pinned into the cached SslContext, so a
    // long-running server would silently keep serving one that expired (or ignore a rotated replacement
    // file) until a config change forced a rebuild. Re-validating on every handshake was rejected as a
    // perf regression, so the check is throttled to at most once per interval and only stats the file
    // (no parse) — a change in mtime/length forces a rebuild (which re-runs CertificateConfigurationValidator
    // and picks up the replacement, failing loudly if it too is expired), while an unchanged-but-expired
    // cert is surfaced with a single WARN.
    private static final long FIXED_CERTIFICATE_RECHECK_INTERVAL_MS = 60_000L;
    /**
     * Time source consulted only by the fixed-certificate re-check; overridable so tests can advance past a
     * fixed certificate's expiry without waiting wall-clock time. Never used for issuance.
     */
    static volatile LongSupplier fixedCertificateClock = System::currentTimeMillis;
    private volatile long fixedServerCertificateLastModified = -1L;
    private volatile long fixedServerCertificateLength = -1L;
    private volatile long fixedServerCertificateNotAfterEpochMs = -1L;
    private volatile long lastFixedServerCertificateCheckMs = 0L;
    private volatile boolean fixedServerCertificateExpiryWarned;

    /**
     * @deprecated use constructor that specifies configuration explicitly
     */
    @Deprecated
    public NettySslContextFactory(MockServerLogger mockServerLogger) {
        this.configuration = configuration();
        this.mockServerLogger = mockServerLogger;
        this.forServer = true;
        this.forMockServerClient = false;
        keyAndCertificateFactory = createKeyAndCertificateFactory(configuration, mockServerLogger);
        warnIfInsecureTlsProfileConfigured();
        warnIfBundledCertificateAuthorityInUse();
        nettySslContextFactoryCustomizer.accept(this);
        if (configuration.proactivelyInitialiseTLS()) {
            createServerSslContext();
        }
    }

    public NettySslContextFactory(Configuration configuration, MockServerLogger mockServerLogger, boolean forServer) {
        this(configuration, mockServerLogger, forServer, false);
    }

    /**
     * The factory {@code MockServerClient} uses for its own connections to MockServer. It logs what the
     * client trusts instead of the server's forward-proxy notice, which describes a setting a client never makes.
     */
    public static NettySslContextFactory forMockServerClient(Configuration configuration, MockServerLogger mockServerLogger) {
        return new NettySslContextFactory(configuration, mockServerLogger, false, true);
    }

    private NettySslContextFactory(Configuration configuration, MockServerLogger mockServerLogger, boolean forServer, boolean forMockServerClient) {
        this.configuration = configuration;
        this.mockServerLogger = mockServerLogger;
        this.forServer = forServer;
        this.forMockServerClient = forMockServerClient;
        keyAndCertificateFactory = createKeyAndCertificateFactory(configuration, mockServerLogger, forServer);
        warnIfInsecureTlsProfileConfigured();
        warnIfBundledCertificateAuthorityInUse();
        nettySslContextFactoryCustomizer.accept(this);
        if (configuration.proactivelyInitialiseTLS()) {
            createServerSslContext();
        }
    }

    /**
     * Resolve the effective TLS protocols, filtering out TLSv1 and TLSv1.1 when
     * {@code tlsAllowInsecureProtocols=false}. If filtering leaves the list empty,
     * the original list is returned so SSL context creation does not fail.
     */
    private static String[] effectiveTlsProtocols(String tlsProtocols, Boolean tlsAllowInsecureProtocols) {
        String[] requested = java.util.Arrays.stream(tlsProtocols.split(","))
            .map(String::trim)
            .filter(p -> !p.isEmpty())
            .toArray(String[]::new);
        if (Boolean.TRUE.equals(tlsAllowInsecureProtocols)) {
            return requested;
        }
        String[] filtered = java.util.Arrays.stream(requested)
            .filter(p -> !p.equalsIgnoreCase("TLSv1") && !p.equalsIgnoreCase("TLSv1.1"))
            .toArray(String[]::new);
        return filtered.length > 0 ? filtered : requested;
    }

    private void warnIfInsecureTlsProfileConfigured() {
        if (mockServerLogger == null) {
            return;
        }
        boolean configuredInsecure = java.util.Arrays.stream(configuration.tlsProtocols().split(","))
            .map(String::trim)
            .anyMatch(p -> p.equalsIgnoreCase("TLSv1") || p.equalsIgnoreCase("TLSv1.1"));
        if (configuredInsecure && Boolean.TRUE.equals(configuration.tlsAllowInsecureProtocols())
            && mockServerLogger.isEnabledForInstance(Level.WARN)) {
            mockServerLogger.logEvent(
                new LogEntry()
                    .setLogLevel(Level.WARN)
                    .setMessageFormat("TLS protocol list includes deprecated TLSv1 / TLSv1.1 (RFC 8996; vulnerable to BEAST and POODLE). Set mockserver.tlsAllowInsecureProtocols=false to drop them, or remove the entries from mockserver.tlsProtocols.")
            );
        }
        if (forMockServerClient) {
            logMockServerClientTrust();
        } else if (forwardProxyTrustsEverything() && mockServerLogger.isEnabledForInstance(Level.WARN)) {
            mockServerLogger.logEvent(
                new LogEntry()
                    .setLogLevel(Level.WARN)
                    .setMessageFormat("Forward proxy is configured to trust ALL X.509 certificates (mockserver.forwardProxyTLSX509CertificatesTrustManagerType=ANY). Certificate validation is disabled — this should be used only in development; prefer JVM or CUSTOM in production.")
            );
        }
    }

    private void logMockServerClientTrust() {
        if (!mockServerLogger.isEnabledForInstance(Level.INFO)) {
            return;
        }
        ControlPlaneAuthenticationSettings controlPlane = ControlPlaneAuthenticationSettings.of(configuration);
        LogEntry logEntry = new LogEntry().setLogLevel(Level.INFO);
        if (Boolean.TRUE.equals(controlPlane.controlPlaneTLSMutualAuthenticationRequired())) {
            logEntry
                .setMessageFormat("MockServerClient verifies MockServer's TLS certificate against only the certificate authorities in controlPlaneTLSMutualAuthenticationCAChain, not MockServer's CA certificate (mockserver.certificateAuthorityCertificate), so that chain must include the CA that signed MockServer's certificate; to change it set controlPlaneTLSMutualAuthenticationCAChain on the client's ClientConfiguration or mockserver.controlPlaneTLSMutualAuthenticationCAChain in the client JVM. Server forwarding settings such as mockserver.forwardProxyTLSX509CertificatesTrustManagerType do not apply to MockServerClient. controlPlaneTLSMutualAuthenticationCAChain is:{}")
                .setArguments(controlPlane.controlPlaneTLSMutualAuthenticationCAChain());
        } else {
            logEntry
                .setMessageFormat("MockServerClient verifies MockServer's TLS certificate against the JVM's default trusted certificate authorities plus MockServer's CA certificate, which is mockserver.certificateAuthorityCertificate, or the CA created in mockserver.directoryToSaveDynamicSSLCertificate when mockserver.dynamicallyCreateCertificateAuthorityCertificate is true. To connect to a MockServer signed by another CA set mockserver.certificateAuthorityCertificate in the client JVM, or require mutual TLS with controlPlaneTLSMutualAuthenticationRequired and controlPlaneTLSMutualAuthenticationCAChain on the client's ClientConfiguration. Server forwarding settings such as mockserver.forwardProxyTLSX509CertificatesTrustManagerType do not apply to MockServerClient. mockserver.certificateAuthorityCertificate is:{}")
                .setArguments(ServerTlsSettings.of(configuration).certificateAuthorityCertificate());
        }
        mockServerLogger.logEvent(logEntry);
    }

    /**
     * Emit a single startup WARN (once per JVM) when the publicly-known bundled CA is the trust anchor
     * signing served traffic. MockServer ships that CA <em>and its private key</em> in the jar, so anyone
     * can forge certificates the bundled CA trusts — fine for local testing, dangerous if mistaken for
     * real interception security. Names the two supported fixes. This is not a bug and does not change the
     * default; it just makes the trade-off visible.
     */
    private void warnIfBundledCertificateAuthorityInUse() {
        if (mockServerLogger == null) {
            return;
        }
        // The bundled CA only signs SERVED (inbound) traffic. A client-only factory (forServer=false)
        // presents no server certificate, so "signing TLS traffic with the bundled default CA" would be
        // inaccurate and misleading in a client-only JVM — emit the warning only for the server factory.
        if (!forServer) {
            return;
        }
        if (usingBundledDefaultCertificateAuthority() && BUNDLED_CA_WARNING_LOGGED.compareAndSet(false, true)) {
            if (mockServerLogger.isEnabledForInstance(Level.WARN)) {
                mockServerLogger.logEvent(
                    new LogEntry()
                        .setLogLevel(Level.WARN)
                        .setMessageFormat("MockServer is signing TLS traffic with the bundled default Certificate Authority, whose private key is published in the MockServer jar — anyone can forge certificates it trusts, so this must not be relied on as real interception security. To use a unique private CA set mockserver.dynamicallyCreateCertificateAuthorityCertificate=true (or run with --proxy-setup).")
                );
            }
        }
    }

    /**
     * @return true when the active Certificate Authority is the publicly-published bundled default CA
     * signing served traffic: dynamic CA generation is off, no user-supplied fixed leaf/key is in effect
     * (which would mean the bundled CA signs nothing), and both CA paths are still the baked-in defaults.
     */
    boolean usingBundledDefaultCertificateAuthority() {
        try {
            if (configuration.dynamicallyCreateCertificateAuthorityCertificate()) {
                return false;
            }
            if (isNotBlank(configuration.x509CertificatePath()) && isNotBlank(configuration.privateKeyPath())) {
                return false;
            }
            return ConfigurationProperties.DEFAULT_CERTIFICATE_AUTHORITY_X509_CERTIFICATE.equals(configuration.certificateAuthorityCertificate())
                && ConfigurationProperties.DEFAULT_CERTIFICATE_AUTHORITY_PRIVATE_KEY.equals(configuration.certificateAuthorityPrivateKey());
        } catch (RuntimeException ignore) {
            return false;
        }
    }

    private boolean forwardProxyTrustsEverything() {
        try {
            return configuration.forwardProxyTLSX509CertificatesTrustManagerType() == ForwardProxyTLSX509CertificatesTrustManager.ANY;
        } catch (RuntimeException ignore) {
            return false;
        }
    }

    public NettySslContextFactory withClientSslContextBuilderFunction(Function<SslContextBuilder, SslContext> clientSslContextBuilderFunction) {
        this.instanceClientSslContextBuilderFunction = clientSslContextBuilderFunction;
        return this;
    }

    public SslContext createClientSslContext(boolean forwardProxyClient, boolean enableHttp2) {
        return createClientSslContext(forwardProxyClient, enableHttp2, null);
    }

    /**
     * Build (or return the cached) outbound client {@link SslContext} for connecting to an upstream.
     * <p>
     * When {@code host} has a per-host client certificate/key mapping configured via
     * {@code forwardProxyClientCertificatesByHost}, that host's cert/key pair is presented for outbound
     * mTLS; otherwise the global {@code forwardProxyPrivateKey} / {@code forwardProxyCertificateChain}
     * pair (or MockServer's own generated key/cert) is used, exactly as before. Contexts are cached:
     * every host that resolves to the global pair shares one cached context (so the cache cannot grow
     * unbounded across many upstream hosts), while each host with its own mapping gets its own context.
     *
     * @param host the upstream target host (may be null/blank — then only the global pair is ever used)
     */
    public SslContext createClientSslContext(boolean forwardProxyClient, boolean enableHttp2, String host) {
        // The context is built only from this one read of the inputs and cached under its signature, so a
        // PUT landing mid-build cannot leave a context built from new values under the old values' key.
        ClientTlsInputs inputs = ClientTlsInputs.read(configuration);
        String[] perHost = resolveForwardProxyClientCertificate(inputs.forwardProxyClientCertificatesByHost, host);
        // Only hosts with an explicit per-host cert/key mapping are keyed by host; all other hosts share
        // the empty host-key so a forward proxy seeing many upstream hosts cannot grow the cache without bound.
        String hostKeyPart = perHost != null ? host.toLowerCase(Locale.ROOT) : "";
        String key = "forwardProxyClient=" + forwardProxyClient + ",enableHttp2=" + enableHttp2 + ",host=" + hostKeyPart
            + ",sig=" + inputs.signature();
        SslContext clientSslContext = clientSslContexts.get(key);
        if (clientSslContext != null && !configuration.rebuildTLSContext()) {
            return clientSslContext;
        }
        synchronized (sslContextLock) {
            clientSslContext = clientSslContexts.get(key);
            if (clientSslContext != null && !configuration.rebuildTLSContext()) {
                return clientSslContext;
            }
            try {
                if (keyAndCertificateFactory.certificateNotYetCreated()) {
                    keyAndCertificateFactory.buildAndSavePrivateKeyAndX509Certificate();
                }
                SslContextBuilder sslContextBuilder =
                    SslContextBuilder
                        .forClient()
                        .protocols(effectiveTlsProtocols(inputs.tlsProtocols, inputs.tlsAllowInsecureProtocols))
                        .keyManager(
                            forwardProxyPrivateKey(perHost != null ? perHost[1] : inputs.forwardProxyPrivateKey),
                            forwardProxyCertificateChain(perHost != null ? perHost[0] : inputs.forwardProxyCertificateChain)
                        );
                if (enableHttp2) {
                    configureALPN(sslContextBuilder, Boolean.TRUE.equals(inputs.http2Enabled));
                }
                if (forwardProxyClient) {
                    switch (inputs.trustManagerType()) {
                        case ANY:
                            sslContextBuilder.trustManager(InsecureTrustManagerFactory.INSTANCE);
                            break;
                        case JVM:
                            List<X509Certificate> mockServerX509Certificates = new ArrayList<>();
                            mockServerX509Certificates.add(keyAndCertificateFactory.x509Certificate());
                            mockServerX509Certificates.add(keyAndCertificateFactory.certificateAuthorityX509Certificate());
                            sslContextBuilder.trustManager(jvmCAX509TrustCertificates(mockServerX509Certificates));
                            break;
                        case CUSTOM:
                            sslContextBuilder.trustManager(customCAX509TrustCertificates(inputs.customTrustX509Certificates));
                            break;
                    }
                } else {
                    List<X509Certificate> mockServerX509Certificates = new ArrayList<>();
                    if (isNotBlank(inputs.tlsMutualAuthenticationCertificateChain)) {
                        mockServerX509Certificates.addAll(x509ChainFromPEMFile(inputs.tlsMutualAuthenticationCertificateChain));
                        mockServerX509Certificates.add(keyAndCertificateFactory.certificateAuthorityX509Certificate());
                    } else {
                        mockServerX509Certificates.add(keyAndCertificateFactory.certificateAuthorityX509Certificate());
                    }
                    sslContextBuilder.trustManager(jvmCAX509TrustCertificates(mockServerX509Certificates));
                }
                clientSslContext = instanceClientSslContextBuilderFunction.apply(sslClientContextBuilderCustomizer.apply(sslContextBuilder));
                if (forwardProxyClient && usesValidatingTrustManager(inputs.trustManagerType())) {
                    // Host name verification (RFC 2818 / HTTPS endpoint identification) for the JVM / CUSTOM
                    // validating trust managers. Netty already enables it for client contexts created via
                    // newHandler(host, port), but NOT for the no-host newHandler overloads (e.g. the
                    // reverse-proxy relay), so verification was inconsistent across outbound paths and could
                    // not be turned off. Force it uniformly at this single chokepoint every outbound path
                    // (HTTP/1.1, HTTP/2, CONNECT relay, websocket relay, LLM forward) routes through: HTTPS
                    // when enabled (covering the no-host paths too), or explicitly cleared when the operator
                    // disables it (validate the chain but not the host name). ANY is never wrapped — its
                    // insecure trust manager makes endpoint identification a no-op — so it is left as-is.
                    String algorithm = Boolean.TRUE.equals(inputs.forwardProxyTLSHostnameVerificationEnabled) ? "HTTPS" : null;
                    clientSslContext = withEndpointIdentification(clientSslContext, algorithm);
                }
                // the key factory reads the CA settings itself, so a change during the build is only seen here
                if (inputs.signature().equals(ClientTlsInputs.read(configuration).signature())) {
                    clientSslContexts.put(key, clientSslContext);
                }
                configuration.rebuildTLSContext(false);
            } catch (Throwable throwable) {
                throw new RuntimeException("Exception creating SSL context for client", throwable);
            }
        }
        return clientSslContext;
    }

    /**
     * The client contexts for forward connections that carry {@code forwardCredentialHeadersByHost} credentials, built
     * now from the configuration in force. They trust only the JVM's default roots (JVM) or only the
     * {@code forwardProxyTLSCustomTrustX509Certificates} file (CUSTOM), less any certificate that is, shares a key with,
     * or was signed by the bundled CA, MockServer's CA or MockServer's certificate; they never use TLSv1 or
     * TLSv1.1, and always verify host names. They present {@code forwardProxyPrivateKey} and
     * {@code forwardProxyCertificateChain}, or a host's {@code forwardProxyClientCertificatesByHost} pair, when set,
     * and otherwise no client certificate. Neither customizer applies, so nothing but these settings decides what such
     * a connection trusts.
     *
     * @throws IllegalStateException when the trust type is not JVM or CUSTOM, host name verification is off, no
     *                               trusted certificate remains, or {@code tlsProtocols} names no protocol after TLSv1.1
     */
    public CredentialClientSslContexts createCredentialClientSslContexts() {
        ClientTlsInputs inputs = ClientTlsInputs.read(configuration);
        ForwardProxyTLSX509CertificatesTrustManager trustManagerType = inputs.trustManagerType();
        if (!usesValidatingTrustManager(trustManagerType) || !Boolean.TRUE.equals(inputs.forwardProxyTLSHostnameVerificationEnabled)) {
            throw new IllegalStateException("credential connections need JVM or CUSTOM forward trust and host name verification");
        }
        try {
            String[] protocols = credentialTlsProtocols(inputs.tlsProtocols);
            boolean jvm = trustManagerType == ForwardProxyTLSX509CertificatesTrustManager.JVM;
            String source = jvm ? "the JVM's default trust store" : "forwardProxyTLSCustomTrustX509Certificates";
            List<X509Certificate> certificates = withoutMockServerCertificates(
                jvm ? Arrays.asList(jvmCAX509TrustCertificates(new ArrayList<>())) : x509ChainFromPEMFile(inputs.customTrustX509Certificates),
                source
            );
            if (certificates.isEmpty()) {
                throw new IllegalStateException(source + " holds no certificate other than MockServer's own CA or certificate");
            }
            KeyStore keyStore = KeyStore.getInstance(KeyStore.getDefaultType());
            keyStore.load(null, null);
            for (int i = 0; i < certificates.size(); i++) {
                keyStore.setCertificateEntry("trusted-" + i, certificates.get(i));
            }
            TrustManagerFactory trust = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            trust.init(keyStore);
            String globalChain = isNotBlank(inputs.forwardProxyPrivateKey) && isNotBlank(inputs.forwardProxyCertificateChain) ? inputs.forwardProxyCertificateChain : null;
            String globalKey = globalChain != null ? inputs.forwardProxyPrivateKey : null;
            Map<String, SslContext[]> byHost = new HashMap<>();
            if (isNotBlank(inputs.forwardProxyClientCertificatesByHost)) {
                for (String entry : inputs.forwardProxyClientCertificatesByHost.split(",")) {
                    int equals = entry.indexOf('=');
                    String host = equals > 0 ? entry.substring(0, equals).trim().toLowerCase(Locale.ROOT) : "";
                    String[] perHost = resolveForwardProxyClientCertificate(inputs.forwardProxyClientCertificatesByHost, host);
                    if (perHost != null && !byHost.containsKey(host)) {
                        byHost.put(host, new SslContext[]{
                            credentialClientSslContext(inputs, protocols, trust, false, perHost[0], perHost[1]),
                            credentialClientSslContext(inputs, protocols, trust, true, perHost[0], perHost[1])
                        });
                    }
                }
            }
            return new CredentialClientSslContexts(
                credentialClientSslContext(inputs, protocols, trust, false, globalChain, globalKey),
                credentialClientSslContext(inputs, protocols, trust, true, globalChain, globalKey),
                byHost,
                inputs.forwardProxyClientCertificatesByHost
            );
        } catch (RuntimeException failure) {
            throw failure;
        } catch (Exception failure) {
            throw new IllegalStateException("Exception creating SSL context for credential connections", failure);
        }
    }

    // TLSv1 and TLSv1.1 are left out whatever tlsAllowInsecureProtocols says
    private static String[] credentialTlsProtocols(String tlsProtocols) {
        String[] protocols = effectiveTlsProtocols(tlsProtocols, false);
        for (String protocol : protocols) {
            if (protocol.equalsIgnoreCase("TLSv1") || protocol.equalsIgnoreCase("TLSv1.1")) {
                throw new IllegalStateException("tlsProtocols names only TLSv1 or TLSv1.1, which connections that carry forwardCredentialHeadersByHost headers never use");
            }
        }
        return protocols;
    }

    /**
     * {@code certificates} without any that is, shares a key with, or was signed by the bundled CA, MockServer's CA or
     * MockServer's certificate, warning when any is left out. A JVM-wide trust store can hold them, as
     * {@link KeyStoreFactory} sets {@code javax.net.ssl.trustStore} to one that does.
     */
    private List<X509Certificate> withoutMockServerCertificates(List<X509Certificate> certificates, String source) {
        if (keyAndCertificateFactory.certificateNotYetCreated()) {
            keyAndCertificateFactory.buildAndSavePrivateKeyAndX509Certificate();
        }
        List<X509Certificate> mockServerCertificates = Arrays.asList(
            BundledCertificateAuthority.certificate(),
            keyAndCertificateFactory.certificateAuthorityX509Certificate(),
            keyAndCertificateFactory.x509Certificate()
        );
        List<X509Certificate> kept = new ArrayList<>();
        for (X509Certificate certificate : certificates) {
            if (!isOrSignedByAny(certificate, mockServerCertificates)) {
                kept.add(certificate);
            }
        }
        int leftOut = certificates.size() - kept.size();
        if (leftOut > 0 && mockServerLogger != null && mockServerLogger.isEnabledForInstance(Level.WARN)) {
            mockServerLogger.logEvent(
                new LogEntry()
                    .setType(LogEntry.LogMessageType.SERVER_CONFIGURATION)
                    .setLogLevel(Level.WARN)
                    .setMessageFormat("{} holds {} of MockServer's own CA or certificates, which connections that carry forwardCredentialHeadersByHost headers never trust, so they are left out of the trust for those connections")
                    .setArguments(source, leftOut)
            );
        }
        return kept;
    }

    private static boolean isOrSignedByAny(X509Certificate certificate, List<X509Certificate> mockServerCertificates) {
        for (X509Certificate authority : mockServerCertificates) {
            if (authority == null) {
                continue;
            }
            if (authority.equals(certificate) || BundledCertificateAuthority.sameKeyMaterial(authority.getPublicKey(), certificate.getPublicKey())) {
                return true;
            }
            try {
                certificate.verify(authority.getPublicKey());
                return true;
            } catch (Exception notSignedByIt) {
                // checked against the next authority
            }
        }
        return false;
    }

    private SslContext credentialClientSslContext(ClientTlsInputs inputs, String[] protocols, TrustManagerFactory trust, boolean enableHttp2, @Nullable String certificateChain, @Nullable String privateKey) throws SSLException {
        SslContextBuilder builder = SslContextBuilder
            .forClient()
            .protocols(protocols)
            .trustManager(trust);
        if (certificateChain != null && privateKey != null) {
            builder.keyManager(forwardProxyPrivateKey(privateKey), forwardProxyCertificateChain(certificateChain));
        }
        if (enableHttp2) {
            configureALPN(builder, Boolean.TRUE.equals(inputs.http2Enabled));
        }
        return withEndpointIdentification(builder.build(), "HTTPS");
    }

    private PrivateKey forwardProxyPrivateKey(String forwardProxyPrivateKey) {
        if (isNotBlank(forwardProxyPrivateKey)) {
            // Cache the parsed key by the PEM *contents* (not the file path) so an unchanged PEM is
            // parsed only once while a rotated key file (same path, new contents) is re-parsed.
            String pem = FileReader.readFileFromClassPathOrPath(forwardProxyPrivateKey);
            return forwardProxyPrivateKeyCache.computeIfAbsent(pem, PEMToFile::privateKeyFromPEM);
        } else {
            return keyAndCertificateFactory.privateKey();
        }
    }

    private X509Certificate[] forwardProxyCertificateChain(String forwardProxyCertificateChain) {
        if (isNotBlank(forwardProxyCertificateChain)) {
            // Cache the parsed chain by the PEM *contents* (not the file path) so an unchanged PEM
            // chain is parsed only once while a rotated file is re-parsed. A clone is returned so
            // callers cannot mutate the cached array.
            String pem = FileReader.readFileFromClassPathOrPath(forwardProxyCertificateChain);
            return forwardProxyCertificateChainCache
                .computeIfAbsent(pem, chain -> x509ChainFromPEM(chain).toArray(new X509Certificate[0]))
                .clone();
        } else {
            return keyAndCertificateFactory.certificateChain().toArray(new X509Certificate[0]);
        }
    }

    /**
     * Resolve the per-host outbound mTLS certificate chain and private key for {@code host} from the
     * {@code forwardProxyClientCertificatesByHost} property value {@code mapping}.
     * <p>
     * The property is a comma-separated list of {@code host=certificateChainPath;privateKeyPath} entries;
     * host matching is case-insensitive. Returns {@code [certificateChainPath, privateKeyPath]} for the
     * first matching host, or {@code null} when {@code host} is blank, no mapping is configured, or no
     * entry matches — in which case the caller falls back to the global forward-proxy cert/key pair.
     * Malformed entries (no {@code =}, or no {@code ;} separating the two paths) are skipped.
     * Package-private for unit testing.
     */
    static String[] resolveForwardProxyClientCertificate(String mapping, String host) {
        if (host == null || host.isEmpty()) {
            return null;
        }
        if (!isNotBlank(mapping)) {
            return null;
        }
        for (String entry : mapping.split(",")) {
            int equals = entry.indexOf('=');
            if (equals <= 0) {
                continue;
            }
            String mappedHost = entry.substring(0, equals).trim();
            if (!mappedHost.equalsIgnoreCase(host)) {
                continue;
            }
            String pair = entry.substring(equals + 1).trim();
            int semicolon = pair.indexOf(';');
            if (semicolon <= 0 || semicolon >= pair.length() - 1) {
                continue;
            }
            String certificateChainPath = pair.substring(0, semicolon).trim();
            String privateKeyPath = pair.substring(semicolon + 1).trim();
            if (isNotBlank(certificateChainPath) && isNotBlank(privateKeyPath)) {
                return new String[]{certificateChainPath, privateKeyPath};
            }
        }
        return null;
    }

    private X509Certificate[] jvmCAX509TrustCertificates(List<X509Certificate> additionalX509Certificates) throws NoSuchAlgorithmException, KeyStoreException {
        TrustManagerFactory trustManagerFactory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        trustManagerFactory.init((KeyStore) null);
        return Arrays
            .stream(trustManagerFactory.getTrustManagers())
            .filter(trustManager -> trustManager instanceof X509TrustManager)
            .flatMap(trustManager -> Arrays.stream(((X509TrustManager) trustManager).getAcceptedIssuers()))
            .collect(() -> additionalX509Certificates, List::add, List::addAll)
            .toArray(new X509Certificate[0]);
    }

    private X509Certificate[] customCAX509TrustCertificates(String forwardProxyTLSCustomTrustX509Certificates) {
        ArrayList<X509Certificate> x509Certificates = new ArrayList<>();
        x509Certificates.add(keyAndCertificateFactory.x509Certificate());
        x509Certificates.add(keyAndCertificateFactory.certificateAuthorityX509Certificate());
        x509Certificates.addAll(x509ChainFromPEMFile(forwardProxyTLSCustomTrustX509Certificates));
        return x509Certificates.toArray(new X509Certificate[0]);
    }

    /**
     * @return true when the forward-proxy trust manager actually validates the upstream chain (JVM or
     * CUSTOM, not the trust-all ANY). Only these modes get host-name verification forced on/off; ANY is
     * left untouched because its insecure trust manager makes endpoint identification a no-op anyway.
     */
    private static boolean usesValidatingTrustManager(ForwardProxyTLSX509CertificatesTrustManager type) {
        return type == ForwardProxyTLSX509CertificatesTrustManager.JVM
            || type == ForwardProxyTLSX509CertificatesTrustManager.CUSTOM;
    }

    /**
     * Wrap a built client {@link SslContext} so every {@link SSLEngine} / {@link SslHandler} it produces —
     * regardless of which {@code newHandler(...)} overload an outbound path calls — has its endpoint
     * identification algorithm forced to {@code algorithm}: {@code "HTTPS"} to verify the upstream host
     * name (RFC 2818) on top of chain validation, or {@code null} to explicitly clear the verification
     * Netty otherwise defaults on for host/port client handlers.
     * <p>
     * Every {@code newHandler(...)} and {@code newEngine(...)} overload on {@link DelegatingSslContext}
     * funnels through {@code initEngine} — the handler overloads via its default {@code initHandler}, which
     * calls {@code initEngine(handler.engine())} (verified against Netty 4.2.16) — so overriding only
     * {@code initEngine} covers every outbound path (forward client, CONNECT relay, websocket relay,
     * reverse-proxy relay, direct engine use).
     */
    private static SslContext withEndpointIdentification(SslContext delegate, String algorithm) {
        return new DelegatingSslContext(delegate) {
            @Override
            protected void initEngine(SSLEngine engine) {
                setEndpointIdentificationAlgorithm(engine, algorithm);
            }
        };
    }

    private static void setEndpointIdentificationAlgorithm(SSLEngine engine, String algorithm) {
        SSLParameters sslParameters = engine.getSSLParameters();
        // null clears the algorithm (disables host-name verification while keeping chain validation)
        sslParameters.setEndpointIdentificationAlgorithm(algorithm);
        engine.setSSLParameters(sslParameters);
    }

    /**
     * The complete set of inputs baked into the cached server context, compared per cached
     * entry so the cache self-invalidates when ANY of them change — replacing the single consumable
     * {@code rebuildServerTLSContext} boolean, which suffered a lost-update race: one thread could add a
     * SAN and set the flag while another thread, already inside the lock, cleared it, so the second
     * thread then returned a context that was missing the first thread's hostname (defect C4).
     *
     * <p>Covers: the Subject-Alternative-Name domain and IP sets (unless {@code preventCertificateDynamicUpdate}
     * is set, which pins the domain list), the certificate-authority identity and paths, the fixed
     * leaf key/cert paths, the mTLS client-authentication inputs, and the TLS protocol/ALPN inputs.
     * Leaf/CA EXPIRY is handled separately via {@link KeyAndCertificateFactory#certificateNeedsRenewal()}
     * (defect C1) because it changes with the clock rather than with configuration.
     *
     * <p>SAN membership is deliberately NOT gated by {@code preventCertificateDynamicUpdate} for the
     * client-authentication inputs — that option suppresses certificate REGENERATION when the domain
     * list changes, and must not be able to suppress a tightening of client-authentication policy.
     *
     * <p>It is only recomputed when {@link Configuration#serverTLSContextGeneration()} moves, so every
     * setter of an input read here must advance that generation.
     *
     * <p>Every input comes from {@code settings}, the one snapshot the context was built from, except the
     * certificate paths the certificate factory derives while building (the dynamic CA paths, and the
     * leaf paths when none were configured), which come from {@code afterBuild}. So a configuration
     * change that lands mid-build is never recorded as an input the context was built from.
     *
     * @param subjectAlternativeNames {@link #subjectAlternativeNameSignature(ServerTlsSettings)}, read
     *                                before the build so a SAN added while a build is in flight forces
     *                                another build
     */
    private static String serverContextSignature(ServerTlsSettings settings, ServerTlsSettings afterBuild, String subjectAlternativeNames) {
        boolean dynamicCA = Boolean.TRUE.equals(settings.dynamicallyCreateCertificateAuthorityCertificate());
        ServerTlsSettings caPaths = dynamicCA ? afterBuild : settings;
        boolean generatedLeaf = isBlank(settings.privateKeyPath()) || isBlank(settings.x509CertificatePath());
        ServerTlsSettings leafPaths = generatedLeaf ? afterBuild : settings;
        return new StringBuilder()
            .append("mtls=").append(settings.tlsMutualAuthenticationRequired())
            .append("|mtlsChain=").append(settings.tlsMutualAuthenticationCertificateChain())
            .append("|protocols=").append(settings.tlsProtocols())
            .append("|insecureProtocols=").append(settings.tlsAllowInsecureProtocols())
            .append("|http2=").append(settings.http2Enabled())
            .append("|caCert=").append(caPaths.certificateAuthorityCertificate())
            .append("|caKey=").append(caPaths.certificateAuthorityPrivateKey())
            .append("|dynamicCA=").append(settings.dynamicallyCreateCertificateAuthorityCertificate())
            .append("|dir=").append(settings.directoryToSaveDynamicSSLCertificate())
            .append("|keyPath=").append(leafPaths.privateKeyPath())
            .append("|certPath=").append(leafPaths.x509CertificatePath())
            .append(subjectAlternativeNames)
            .toString();
    }

    private String subjectAlternativeNameSignature(ServerTlsSettings settings) {
        if (Boolean.TRUE.equals(settings.preventCertificateDynamicUpdate())) {
            return "";
        }
        return "|sanDomains=" + new TreeSet<>(nullSafeSet(configuration.sslSubjectAlternativeNameDomains()))
            + "|sanIps=" + new TreeSet<>(nullSafeSet(configuration.sslSubjectAlternativeNameIps()));
    }

    /**
     * A built server context with the configuration generation and signature it was built for. Invariant:
     * {@code sslContext} is valid for any configuration whose signature equals {@code signature}, and at
     * {@code generation} the configuration's signature equalled {@code signature}.
     */
    private static final class ServerContextState {
        private final SslContext sslContext;
        private final long generation;
        private final String signature;

        private ServerContextState(SslContext sslContext, long generation, String signature) {
            this.sslContext = sslContext;
            this.generation = generation;
            this.signature = signature;
        }
    }

    /**
     * The cached server context if it is still valid, without blocking: {@code null} when no context has
     * been built, when it must be rebuilt, or when the throttled on-disk re-check of a fixed certificate
     * is due (that check does file I/O, so it is left to {@link #createServerSslContext()}). Safe to call
     * on an event loop; a {@code null} result means the caller must provision off the event loop.
     */
    SslContext cachedServerSslContext() {
        if (fixedServerCertificateRecheckDue()) {
            return null;
        }
        return validCachedServerSslContext(false);
    }

    private SslContext validCachedServerSslContext(boolean fixedServerCertificateChanged) {
        ServerContextState state = serverContextState.get();
        if (state == null
            || fixedServerCertificateChanged
            || keyAndCertificateFactory.certificateNotYetCreated()
            || keyAndCertificateFactory.certificateNeedsRenewal()) {
            return null;
        }
        // read the generation BEFORE the values it guards: writers change a value, then advance the generation
        long generation = configuration.serverTLSContextGeneration();
        if (generation == state.generation) {
            return state.sslContext;
        }
        ServerTlsSettings settings = ServerTlsSettings.of(configuration);
        if (serverContextSignature(settings, settings, subjectAlternativeNameSignature(settings)).equals(state.signature)) {
            // the generation moved but no input changed value (e.g. an unrelated property was set)
            serverContextState.compareAndSet(state, new ServerContextState(state.sslContext, generation, state.signature));
            return state.sslContext;
        }
        return null;
    }

    /**
     * The mutable client-TLS inputs of a client {@link SslContext}, each read from the configuration once.
     * A context is built only from these values and cached under their {@link #signature()}, so rotating any
     * of them at runtime self-invalidates the cache (defects C8 and C9: the CA identity is included so a CA
     * rotation invalidates the client trust anchor too).
     */
    private static final class ClientTlsInputs {
        private final String forwardProxyPrivateKey;
        private final String forwardProxyCertificateChain;
        private final String forwardProxyClientCertificatesByHost;
        private final ForwardProxyTLSX509CertificatesTrustManager trustManagerType;
        // the getter can throw on an unparsable property; the build rethrows it, as reading it there did
        private final RuntimeException trustManagerTypeFailure;
        private final Boolean forwardProxyTLSHostnameVerificationEnabled;
        private final String customTrustX509Certificates;
        private final String tlsMutualAuthenticationCertificateChain;
        private final String tlsProtocols;
        private final Boolean tlsAllowInsecureProtocols;
        private final Boolean http2Enabled;
        private final String certificateAuthorityCertificate;
        private final String certificateAuthorityPrivateKey;
        private final Boolean dynamicallyCreateCertificateAuthorityCertificate;
        private final String directoryToSaveDynamicSSLCertificate;
        private String signature;

        private ClientTlsInputs(Configuration configuration) {
            forwardProxyPrivateKey = configuration.forwardProxyPrivateKey();
            forwardProxyCertificateChain = configuration.forwardProxyCertificateChain();
            forwardProxyClientCertificatesByHost = configuration.forwardProxyClientCertificatesByHost();
            ForwardProxyTLSX509CertificatesTrustManager type = null;
            RuntimeException typeFailure = null;
            try {
                type = configuration.forwardProxyTLSX509CertificatesTrustManagerType();
            } catch (RuntimeException failure) {
                typeFailure = failure;
            }
            trustManagerType = type;
            trustManagerTypeFailure = typeFailure;
            forwardProxyTLSHostnameVerificationEnabled = configuration.forwardProxyTLSHostnameVerificationEnabled();
            customTrustX509Certificates = configuration.forwardProxyTLSCustomTrustX509Certificates();
            tlsMutualAuthenticationCertificateChain = configuration.tlsMutualAuthenticationCertificateChain();
            tlsProtocols = configuration.tlsProtocols();
            tlsAllowInsecureProtocols = configuration.tlsAllowInsecureProtocols();
            http2Enabled = configuration.http2Enabled();
            certificateAuthorityCertificate = configuration.certificateAuthorityCertificate();
            certificateAuthorityPrivateKey = configuration.certificateAuthorityPrivateKey();
            dynamicallyCreateCertificateAuthorityCertificate = configuration.dynamicallyCreateCertificateAuthorityCertificate();
            directoryToSaveDynamicSSLCertificate = configuration.directoryToSaveDynamicSSLCertificate();
        }

        static ClientTlsInputs read(Configuration configuration) {
            return new ClientTlsInputs(configuration);
        }

        ForwardProxyTLSX509CertificatesTrustManager trustManagerType() {
            if (trustManagerTypeFailure != null) {
                throw trustManagerTypeFailure;
            }
            return trustManagerType;
        }

        String signature() {
            String memoised = signature;
            if (memoised == null) {
                memoised = "fwdKey=" + forwardProxyPrivateKey
                    + "|fwdChain=" + forwardProxyCertificateChain
                    + "|fwdByHost=" + forwardProxyClientCertificatesByHost
                    + "|trustType=" + (trustManagerTypeFailure != null ? "" : String.valueOf(trustManagerType))
                    + "|hostnameVerification=" + forwardProxyTLSHostnameVerificationEnabled
                    + "|customTrust=" + customTrustX509Certificates
                    + "|mtlsChain=" + tlsMutualAuthenticationCertificateChain
                    + "|protocols=" + tlsProtocols
                    + "|insecureProtocols=" + tlsAllowInsecureProtocols
                    + "|http2=" + http2Enabled
                    + "|caCert=" + certificateAuthorityCertificate
                    + "|caKey=" + certificateAuthorityPrivateKey
                    + "|dynamicCA=" + dynamicallyCreateCertificateAuthorityCertificate
                    + "|dir=" + directoryToSaveDynamicSSLCertificate;
                signature = memoised;
            }
            return memoised;
        }
    }

    private static <T> Set<T> nullSafeSet(Set<T> set) {
        return set == null ? Collections.emptySet() : set;
    }

    public SslContext createServerSslContext() {
        // Evaluate the fixed-certificate re-check ONCE per call (it is internally throttled and has a
        // warn-once side effect) and reuse the result across both the fast path and the double-checked
        // path, so the inner throttled re-evaluation cannot return a stale "unchanged" and short-circuit
        // a rebuild the outer check already decided on.
        boolean fixedServerCertificateChanged = fixedServerCertificateChangedOnDisk();
        SslContext cached = validCachedServerSslContext(fixedServerCertificateChanged);
        if (cached != null) {
            return cached;
        }
        synchronized (sslContextLock) {
            cached = validCachedServerSslContext(fixedServerCertificateChanged);
            if (cached != null) {
                return cached;
            }
            try {
                // the generation BEFORE the snapshot it guards: an update publishes the snapshot, then advances it
                long generation = configuration.serverTLSContextGeneration();
                ServerTlsSettings settings = ServerTlsSettings.of(configuration);
                String subjectAlternativeNames = subjectAlternativeNameSignature(settings);
                new CertificateConfigurationValidator(configuration, mockServerLogger).validate();
                keyAndCertificateFactory.buildAndSavePrivateKeyAndX509Certificate();
                logUsedCertificateData();
                boolean mutualAuthenticationRequired = Boolean.TRUE.equals(settings.tlsMutualAuthenticationRequired());
                final SslContextBuilder sslContextBuilder = SslContextBuilder
                    .forServer(
                        keyAndCertificateFactory.privateKey(),
                        keyAndCertificateFactory.certificateChain()
                    )
                    .protocols(effectiveTlsProtocols(settings.tlsProtocols(), settings.tlsAllowInsecureProtocols()))
                    .clientAuth(mutualAuthenticationRequired ? ClientAuth.REQUIRE : ClientAuth.OPTIONAL);
                configureALPN(sslContextBuilder, Boolean.TRUE.equals(settings.http2Enabled()));
                if (isNotBlank(settings.tlsMutualAuthenticationCertificateChain()) || mutualAuthenticationRequired) {
                    sslContextBuilder.trustManager(DataPlaneMutualTlsTrust.clientCertificateTrust(
                        settings.tlsMutualAuthenticationCertificateChain(),
                        keyAndCertificateFactory.certificateAuthorityX509Certificate(),
                        Boolean.TRUE.equals(configuration.tlsMutualAuthenticationAllowBundledCa()),
                        mockServerLogger
                    ));
                } else {
                    sslContextBuilder.trustManager(InsecureTrustManagerFactory.INSTANCE);
                }
                SslContext built = sslServerContextBuilderCustomizer
                    .apply(sslContextBuilder)
                    .build();
                // record the inputs this context was built from, from the same snapshot (SANs read BEFORE
                // the build), so a later change to any of them forces a rebuild without a consumable flag.
                // Derived paths are taken from after the build unless an update landed mid-build.
                ServerTlsSettings afterBuild = ServerTlsSettings.of(configuration);
                serverContextState.set(new ServerContextState(built, generation,
                    serverContextSignature(settings, afterBuild.updatedSince(settings) ? settings : afterBuild, subjectAlternativeNames)));
                recordFixedServerCertificateState();
                configuration.rebuildServerTLSContext(false);
            } catch (Error | DataPlaneMutualTlsConfigurationException error) {
                throw error;
            } catch (Exception exception) {
                throw new RuntimeException(
                    "Exception creating SSL context for server"
                        + " with privateKeyPath=\"" + configuration.privateKeyPath() + "\""
                        + " and x509CertificatePath=\"" + configuration.x509CertificatePath() + "\""
                        + " and certificateAuthorityCertificate=\"" + configuration.certificateAuthorityCertificate() + "\""
                        + (exception.getMessage() != null ? " - " + exception.getMessage() : ""),
                    exception
                );
            }
            return serverContextState.get().sslContext;
        }
    }

    private boolean fixedServerCertificateRecheckDue() {
        return isNotBlank(configuration.x509CertificatePath())
            && isNotBlank(configuration.privateKeyPath())
            && fixedCertificateClock.getAsLong() - lastFixedServerCertificateCheckMs >= FIXED_CERTIFICATE_RECHECK_INTERVAL_MS;
    }

    /**
     * True when a user-supplied FIXED server certificate has changed on disk (mtime or length) since the
     * cached context was built, so the caller should rebuild — which re-runs
     * {@link CertificateConfigurationValidator} and picks up the replacement (failing loudly if it too is
     * expired). Never true for self-generated certificates (they self-renew via
     * {@link KeyAndCertificateFactory#certificateNeedsRenewal()}). Has a warn-once side effect: when the
     * fixed certificate is unchanged on disk but has passed its {@code notAfter}, a single WARN is emitted
     * so a long-running server surfaces the expiry instead of silently serving it.
     * <p>
     * Throttled to at most one filesystem stat per {@link #FIXED_CERTIFICATE_RECHECK_INTERVAL_MS}, and only
     * ever stats the file — it never re-parses the PEM — so it is not the per-handshake perf regression that
     * re-validation was rejected for.
     */
    private boolean fixedServerCertificateChangedOnDisk() {
        String x509CertificatePath = configuration.x509CertificatePath();
        if (isBlank(x509CertificatePath) || isBlank(configuration.privateKeyPath())) {
            return false;
        }
        long now = fixedCertificateClock.getAsLong();
        if (now - lastFixedServerCertificateCheckMs < FIXED_CERTIFICATE_RECHECK_INTERVAL_MS) {
            return false;
        }
        lastFixedServerCertificateCheckMs = now;
        File certificateFile = new File(x509CertificatePath);
        if (certificateFile.isFile()) {
            long lastModified = certificateFile.lastModified();
            long length = certificateFile.length();
            if (fixedServerCertificateLastModified != -1L
                && (lastModified != fixedServerCertificateLastModified || length != fixedServerCertificateLength)) {
                // rotated in place on disk — force a rebuild to pick up (and re-validate) the replacement
                return true;
            }
        }
        // unchanged (or a classpath resource we cannot stat) — surface expiry of the served certificate
        if (fixedServerCertificateNotAfterEpochMs != -1L
            && now >= fixedServerCertificateNotAfterEpochMs
            && !fixedServerCertificateExpiryWarned) {
            fixedServerCertificateExpiryWarned = true;
            if (mockServerLogger != null && mockServerLogger.isEnabledForInstance(Level.WARN)) {
                mockServerLogger.logEvent(
                    new LogEntry()
                        .setLogLevel(Level.WARN)
                        .setMessageFormat("the configured fixed server certificate at {} has expired (notAfter {}) but is still being served; MockServer does not renew user-supplied certificates — replace the file at x509CertificatePath (and privateKeyPath) with a valid certificate")
                        .setArguments(x509CertificatePath, new Date(fixedServerCertificateNotAfterEpochMs))
                );
            }
        }
        return false;
    }

    /**
     * Snapshot the on-disk state (mtime, length) and the served leaf's {@code notAfter} of a user-supplied
     * FIXED server certificate, right after a successful build, so {@link #fixedServerCertificateChangedOnDisk()}
     * can later detect a rotation or surface an expiry. A no-op for self-generated certificates.
     */
    private void recordFixedServerCertificateState() {
        String x509CertificatePath = configuration.x509CertificatePath();
        if (isBlank(x509CertificatePath) || isBlank(configuration.privateKeyPath())) {
            fixedServerCertificateLastModified = -1L;
            fixedServerCertificateLength = -1L;
            fixedServerCertificateNotAfterEpochMs = -1L;
            return;
        }
        fixedServerCertificateExpiryWarned = false;
        lastFixedServerCertificateCheckMs = fixedCertificateClock.getAsLong();
        File certificateFile = new File(x509CertificatePath);
        if (certificateFile.isFile()) {
            fixedServerCertificateLastModified = certificateFile.lastModified();
            fixedServerCertificateLength = certificateFile.length();
        } else {
            fixedServerCertificateLastModified = -1L;
            fixedServerCertificateLength = -1L;
        }
        try {
            X509Certificate leaf = keyAndCertificateFactory.x509Certificate();
            fixedServerCertificateNotAfterEpochMs = leaf != null && leaf.getNotAfter() != null
                ? leaf.getNotAfter().getTime()
                : -1L;
        } catch (RuntimeException ignore) {
            fixedServerCertificateNotAfterEpochMs = -1L;
        }
    }

    private void logUsedCertificateData() {
        final X509Certificate caCertificate = keyAndCertificateFactory.certificateAuthorityX509Certificate();
        final X509Certificate eeCertificate = keyAndCertificateFactory.x509Certificate();
        if (caCertificate != null && eeCertificate != null && mockServerLogger.isEnabledForInstance(Level.DEBUG)) {
            mockServerLogger.logEvent(
                new LogEntry()
                    .setLogLevel(Level.DEBUG)
                    .setMessageFormat("using certificate authority serial:{}issuer:{}subject:{}and certificate serial:{}issuer:{}subject:{}")
                    .setArguments(
                        caCertificate.getSerialNumber(),
                        caCertificate.getIssuerX500Principal(),
                        caCertificate.getSubjectX500Principal(),
                        eeCertificate.getSerialNumber(),
                        eeCertificate.getIssuerX500Principal(),
                        eeCertificate.getSubjectX500Principal()
                    )
            );
        }
    }

    private static void configureALPN(SslContextBuilder sslContextBuilder, boolean http2Enabled) {
        // when HTTP/2 is disabled only advertise http/1.1 via ALPN so h2 capable clients fall back to HTTP/1.1
        String[] applicationProtocols = http2Enabled
            ? new String[]{ApplicationProtocolNames.HTTP_2, ApplicationProtocolNames.HTTP_1_1}
            : new String[]{ApplicationProtocolNames.HTTP_1_1};
        Consumer<SslContextBuilder> configureALPN = contextBuilder -> contextBuilder
            .ciphers(Http2SecurityUtil.CIPHERS, SupportedCipherSuiteFilter.INSTANCE)
            .applicationProtocolConfig(new ApplicationProtocolConfig(
                ApplicationProtocolConfig.Protocol.ALPN,
                // NO_ADVERTISE is currently the only mode supported by both OpenSsl and JDK providers.
                ApplicationProtocolConfig.SelectorFailureBehavior.NO_ADVERTISE,
                // ACCEPT is currently the only mode supported by both OpenSsl and JDK providers.
                ApplicationProtocolConfig.SelectedListenerFailureBehavior.ACCEPT,
                applicationProtocols
            ));
        if (SslProvider.isAlpnSupported(SslContext.defaultServerProvider())) {
            configureALPN.accept(sslContextBuilder.sslProvider(SslContext.defaultServerProvider()));
        } else if (SslProvider.isAlpnSupported(SslProvider.JDK)) {
            configureALPN.accept(sslContextBuilder.sslProvider(SslProvider.JDK));
        } else if (SslProvider.isAlpnSupported(SslProvider.OPENSSL)) {
            configureALPN.accept(sslContextBuilder.sslProvider(SslProvider.OPENSSL));
        }
    }

    /**
     * The certificates {@code MockServerClient} trusts for MockServer's own certificate when control-plane mTLS
     * is required: only those in {@code controlPlaneTLSMutualAuthenticationCAChain}. MockServer's CA certificate
     * is not added, so a client given a control-plane CA chain trusts nothing outside it.
     */
    public static X509Certificate[] controlPlaneTrustCertificates(String controlPlaneTLSMutualAuthenticationCAChain) {
        return x509ChainFromPEMFile(controlPlaneTLSMutualAuthenticationCAChain).toArray(new X509Certificate[0]);
    }

    /**
     * The certificates in {@code tlsMutualAuthenticationCertificateChain}, if set, plus MockServer's CA certificate,
     * whatever that CA is. Data-plane mTLS does not use this: see {@link DataPlaneMutualTlsTrust#clientCertificateTrust}.
     */
    public X509Certificate[] trustCertificateChain(String tlsMutualAuthenticationCertificateChain) {
        if (isNotBlank(tlsMutualAuthenticationCertificateChain)) {
            List<X509Certificate> x509Certificates = x509ChainFromPEMFile(tlsMutualAuthenticationCertificateChain);
            x509Certificates.add(keyAndCertificateFactory.certificateAuthorityX509Certificate());
            return x509Certificates.toArray(new X509Certificate[0]);
        } else {
            return Collections
                .singletonList(keyAndCertificateFactory.certificateAuthorityX509Certificate())
                .toArray(new X509Certificate[0]);
        }
    }

    public boolean isForServer() {
        return forServer;
    }
}
