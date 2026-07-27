package org.mockserver.springtest;

import org.mockserver.configuration.Configuration;
import org.mockserver.integration.ClientAndServer;
import org.mockserver.log.model.LogEntry;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.socket.tls.ForwardProxyTLSX509CertificatesTrustManager;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.mock.env.MockPropertySource;
import org.springframework.test.context.ContextCustomizer;
import org.springframework.test.context.MergedContextConfiguration;
import org.springframework.test.context.support.TestPropertySourceUtils;

import java.net.InetSocketAddress;
import java.util.*;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static org.mockserver.configuration.Configuration.configuration;
import static org.slf4j.event.Level.WARN;

public class MockServerPropertyCustomizer implements ContextCustomizer {
    private static final Pattern MOCK_SERVER_PORT_PATTERN = Pattern.compile("\\$\\{mockServerPort}");
    private static final String MOCKSERVER_PROPERTY_PREFIX = "mockserver.";
    private static final MockServerLogger LOGGER = new MockServerLogger(MockServerPropertyCustomizer.class);

    private static volatile ClientAndServer clientAndServer;

    private final List<String> properties;
    private final List<String> springProperties;
    private final List<String> mockServerProperties;

    MockServerPropertyCustomizer(List<String> properties) {
        this.properties = properties;
        this.springProperties = properties.stream()
            .filter(p -> !isMockServerProperty(p))
            .collect(Collectors.toList());
        this.mockServerProperties = properties.stream()
            .filter(MockServerPropertyCustomizer::isMockServerProperty)
            .collect(Collectors.toList());
    }

    private static boolean isMockServerProperty(String property) {
        return property != null && property.startsWith(MOCKSERVER_PROPERTY_PREFIX);
    }

    static synchronized ClientAndServer getOrCreateClientAndServer() {
        return getOrCreateClientAndServer(Collections.emptyList());
    }

    static synchronized ClientAndServer getOrCreateClientAndServer(List<String> mockServerProperties) {
        if (clientAndServer == null || !clientAndServer.isRunning()) {
            Configuration configuration = buildConfiguration(mockServerProperties);
            clientAndServer = ClientAndServer.startClientAndServer(configuration, 0);
            final ClientAndServer serverToStop = clientAndServer;
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                serverToStop.stop();
                clientAndServer = null;
            }));
        }
        return clientAndServer;
    }

    @Override
    public void customizeContext(ConfigurableApplicationContext context, MergedContextConfiguration mergedConfig) {
        ClientAndServer server = getOrCreateClientAndServer(mockServerProperties);
        int port = server.getPort();

        context
            .getEnvironment()
            .getPropertySources()
            .addLast(new MockPropertySource().withProperty("mockServerPort", port));

        springProperties.forEach(property -> {
                String replacement =
                    MOCK_SERVER_PORT_PATTERN.matcher(property).replaceAll(String.valueOf(port));
                TestPropertySourceUtils.addInlinedPropertiesToEnvironment(context, replacement);
            }
        );
    }

    static Configuration buildConfiguration(List<String> mockServerProperties) {
        Configuration config = configuration();
        for (String property : mockServerProperties) {
            int equalsIndex = property.indexOf('=');
            if (equalsIndex < 0) {
                logWarning("ignoring malformed property (no '=' found): " + property);
                continue;
            }
            String fullKey = property.substring(0, equalsIndex).trim();
            String value = property.substring(equalsIndex + 1).trim();

            if (!fullKey.startsWith(MOCKSERVER_PROPERTY_PREFIX)) {
                continue;
            }
            String key = fullKey.substring(MOCKSERVER_PROPERTY_PREFIX.length());

            try {
                applyProperty(config, key, value);
            } catch (IllegalArgumentException e) {
                logWarning("failed to apply property " + fullKey + ": " + e.getMessage());
            }
        }
        return config;
    }

    private static void applyProperty(Configuration config, String key, String value) {
        switch (key) {
            // --- String properties ---
            case "initializationClass":
                config.initializationClass(value);
                break;
            case "initializationJsonPath":
                config.initializationJsonPath(value);
                break;
            case "initializationOpenAPIPath":
                config.initializationOpenAPIPath(value);
                break;
            case "openAPIContextPathPrefix":
                config.openAPIContextPathPrefix(value);
                break;
            case "persistedExpectationsPath":
                config.persistedExpectationsPath(value);
                break;
            case "memoryUsageCsvDirectory":
                config.memoryUsageCsvDirectory(value);
                break;
            case "localBoundIP":
                config.localBoundIP(value);
                break;
            case "corsAllowOrigin":
                config.corsAllowOrigin(value);
                break;
            case "corsAllowMethods":
                config.corsAllowMethods(value);
                break;
            case "corsAllowHeaders":
                config.corsAllowHeaders(value);
                break;
            case "javascriptAllowedClasses":
                config.javascriptAllowedClasses(value);
                break;
            case "javascriptDisallowedClasses":
                config.javascriptDisallowedClasses(value);
                break;
            case "javascriptDisallowedText":
                config.javascriptDisallowedText(value);
                break;
            case "velocityDisallowedText":
                config.velocityDisallowedText(value);
                break;
            case "mustacheDisallowedText":
                config.mustacheDisallowedText(value);
                break;
            case "forwardProxyAuthenticationUsername":
                config.forwardProxyAuthenticationUsername(value);
                break;
            case "forwardProxyAuthenticationPassword":
                config.forwardProxyAuthenticationPassword(value);
                break;
            case "proxyAuthenticationRealm":
                config.proxyAuthenticationRealm(value);
                break;
            case "proxyAuthenticationUsername":
                config.proxyAuthenticationUsername(value);
                break;
            case "proxyAuthenticationPassword":
                config.proxyAuthenticationPassword(value);
                break;
            case "noProxyHosts":
                config.noProxyHosts(value);
                break;
            case "livenessHttpGetPath":
                config.livenessHttpGetPath(value);
                break;
            case "controlPlaneTLSMutualAuthenticationCAChain":
                config.controlPlaneTLSMutualAuthenticationCAChain(value);
                break;
            case "controlPlanePrivateKeyPath":
                config.controlPlanePrivateKeyPath(value);
                break;
            case "controlPlaneX509CertificatePath":
                config.controlPlaneX509CertificatePath(value);
                break;
            case "controlPlaneJWTAuthenticationJWKSource":
                config.controlPlaneJWTAuthenticationJWKSource(value);
                break;
            case "controlPlaneJWTAuthenticationExpectedAudience":
                config.controlPlaneJWTAuthenticationExpectedAudience(value);
                break;
            case "tlsProtocols":
                config.tlsProtocols(value);
                break;
            case "directoryToSaveDynamicSSLCertificate":
                config.directoryToSaveDynamicSSLCertificate(value);
                break;
            case "sslCertificateDomainName":
                config.sslCertificateDomainName(value);
                break;
            case "certificateAuthorityPrivateKey":
                config.certificateAuthorityPrivateKey(value);
                break;
            case "certificateAuthorityCertificate":
                config.certificateAuthorityCertificate(value);
                break;
            case "privateKeyPath":
                config.privateKeyPath(value);
                break;
            case "x509CertificatePath":
                config.x509CertificatePath(value);
                break;
            case "tlsMutualAuthenticationCertificateChain":
                config.tlsMutualAuthenticationCertificateChain(value);
                break;
            case "forwardProxyTLSCustomTrustX509Certificates":
                config.forwardProxyTLSCustomTrustX509Certificates(value);
                break;
            case "forwardProxyPrivateKey":
                config.forwardProxyPrivateKey(value);
                break;
            case "forwardProxyCertificateChain":
                config.forwardProxyCertificateChain(value);
                break;

            // --- String property with special overload (logLevel) ---
            case "logLevel":
                config.logLevel(value);
                break;

            // --- Boolean properties ---
            case "disableSystemOut":
                config.disableSystemOut(parseStrictBoolean(value, key));
                break;
            case "disableLogging":
                config.disableLogging(parseStrictBoolean(value, key));
                break;
            case "detailedMatchFailures":
                config.detailedMatchFailures(parseStrictBoolean(value, key));
                break;
            case "launchUIForLogLevelDebug":
                config.launchUIForLogLevelDebug(parseStrictBoolean(value, key));
                break;
            case "metricsEnabled":
                config.metricsEnabled(parseStrictBoolean(value, key));
                break;
            case "mcpEnabled":
                config.mcpEnabled(parseStrictBoolean(value, key));
                break;
            case "compactLogFormat":
                config.compactLogFormat(parseStrictBoolean(value, key));
                break;
            case "outputMemoryUsageCsv":
                config.outputMemoryUsageCsv(parseStrictBoolean(value, key));
                break;
            case "matchersFailFast":
                config.matchersFailFast(parseStrictBoolean(value, key));
                break;
            case "alwaysCloseSocketConnections":
                config.alwaysCloseSocketConnections(parseStrictBoolean(value, key));
                break;
            case "useSemicolonAsQueryParameterSeparator":
                config.useSemicolonAsQueryParameterSeparator(parseStrictBoolean(value, key));
                break;
            case "assumeAllRequestsAreHttp":
                config.assumeAllRequestsAreHttp(parseStrictBoolean(value, key));
                break;
            case "forwardBinaryRequestsWithoutWaitingForResponse":
                config.forwardBinaryRequestsWithoutWaitingForResponse(parseStrictBoolean(value, key));
                break;
            case "enableCORSForAPI":
                config.enableCORSForAPI(parseStrictBoolean(value, key));
                break;
            case "enableCORSForAllResponses":
                config.enableCORSForAllResponses(parseStrictBoolean(value, key));
                break;
            case "corsAllowCredentials":
                config.corsAllowCredentials(parseStrictBoolean(value, key));
                break;
            case "velocityDisallowClassLoading":
                config.velocityDisallowClassLoading(parseStrictBoolean(value, key));
                break;
            case "openAPIResponseValidation":
                config.openAPIResponseValidation(parseStrictBoolean(value, key));
                break;
            case "watchInitializationJson":
                config.watchInitializationJson(parseStrictBoolean(value, key));
                break;
            case "persistExpectations":
                config.persistExpectations(parseStrictBoolean(value, key));
                break;
            case "attemptToProxyIfNoMatchingExpectation":
                config.attemptToProxyIfNoMatchingExpectation(parseStrictBoolean(value, key));
                break;
            case "proxyRemoteHost":
                config.proxyRemoteHost(value);
                break;
            case "proxyRemotePort":
                int port;
                try {
                    port = Integer.parseInt(value);
                } catch (NumberFormatException nfe) {
                    throw new IllegalArgumentException("mockserver.proxyRemotePort must be an integer between 1 and 65535, got: " + value);
                }
                if (port < 1 || port > 65535) {
                    throw new IllegalArgumentException("mockserver.proxyRemotePort must be between 1 and 65535, got: " + port);
                }
                config.proxyRemotePort(port);
                break;
            case "forwardAdjustHostHeader":
                config.forwardAdjustHostHeader(parseStrictBoolean(value, key));
                break;
            case "forwardDefaultHostHeader":
                config.forwardDefaultHostHeader(value);
                break;
            case "controlPlaneTLSMutualAuthenticationRequired":
                config.controlPlaneTLSMutualAuthenticationRequired(parseStrictBoolean(value, key));
                break;
            case "controlPlaneJWTAuthenticationRequired":
                config.controlPlaneJWTAuthenticationRequired(parseStrictBoolean(value, key));
                break;
            case "proactivelyInitialiseTLS":
                config.proactivelyInitialiseTLS(parseStrictBoolean(value, key));
                break;
            case "dynamicallyCreateCertificateAuthorityCertificate":
                config.dynamicallyCreateCertificateAuthorityCertificate(parseStrictBoolean(value, key));
                break;
            case "preventCertificateDynamicUpdate":
                config.preventCertificateDynamicUpdate(parseStrictBoolean(value, key));
                break;
            case "tlsMutualAuthenticationRequired":
                config.tlsMutualAuthenticationRequired(parseStrictBoolean(value, key));
                break;

            // --- Integer properties ---
            case "maxExpectations":
                config.maxExpectations(Integer.parseInt(value));
                break;
            case "maxLogEntries":
                config.maxLogEntries(Integer.parseInt(value));
                break;
            case "maxWebSocketExpectations":
                config.maxWebSocketExpectations(Integer.parseInt(value));
                break;
            case "nioEventLoopThreadCount":
                config.nioEventLoopThreadCount(Integer.parseInt(value));
                break;
            case "actionHandlerThreadCount":
                config.actionHandlerThreadCount(Integer.parseInt(value));
                break;
            case "clientNioEventLoopThreadCount":
                config.clientNioEventLoopThreadCount(Integer.parseInt(value));
                break;
            case "webSocketClientEventLoopThreadCount":
                config.webSocketClientEventLoopThreadCount(Integer.parseInt(value));
                break;
            case "maxInitialLineLength":
                config.maxInitialLineLength(Integer.parseInt(value));
                break;
            case "maxHeaderSize":
                config.maxHeaderSize(Integer.parseInt(value));
                break;
            case "maxChunkSize":
                config.maxChunkSize(Integer.parseInt(value));
                break;
            case "corsMaxAgeInSeconds":
                config.corsMaxAgeInSeconds(Integer.parseInt(value));
                break;
            case "maximumNumberOfRequestToReturnInVerificationFailure":
                config.maximumNumberOfRequestToReturnInVerificationFailure(Integer.parseInt(value));
                break;

            // --- Long properties (note: property names differ from setter names) ---
            case "maxFutureTimeout":
                config.maxFutureTimeoutInMillis(Long.parseLong(value));
                break;
            case "maxSocketTimeout":
                config.maxSocketTimeoutInMillis(Long.parseLong(value));
                break;
            case "socketConnectionTimeout":
                config.socketConnectionTimeoutInMillis(Long.parseLong(value));
                break;

            // --- Long properties (property name matches setter name) ---
            case "breakpointTimeoutMillis":
                config.breakpointTimeoutMillis(Long.parseLong(value));
                break;
            case "javascriptTemplateExecutionTimeout":
                config.javascriptTemplateExecutionTimeout(Long.parseLong(value));
                break;

            // --- InetSocketAddress properties ---
            case "forwardHttpProxy":
                config.forwardHttpProxy(parseInetSocketAddress(value, "mockserver.forwardHttpProxy"));
                break;
            case "forwardHttpsProxy":
                config.forwardHttpsProxy(parseInetSocketAddress(value, "mockserver.forwardHttpsProxy"));
                break;
            case "forwardSocksProxy":
                config.forwardSocksProxy(parseInetSocketAddress(value, "mockserver.forwardSocksProxy"));
                break;

            // --- Enum property ---
            case "forwardProxyTLSX509CertificatesTrustManagerType":
                config.forwardProxyTLSX509CertificatesTrustManagerType(
                    ForwardProxyTLSX509CertificatesTrustManager.valueOf(value));
                break;

            // --- Set<String> properties (comma-separated) ---
            case "sslSubjectAlternativeNameDomains":
                config.sslSubjectAlternativeNameDomains(splitAndTrim(value));
                break;
            case "sslSubjectAlternativeNameIps":
                config.sslSubjectAlternativeNameIps(splitAndTrim(value));
                break;
            case "controlPlaneJWTAuthenticationRequiredClaims":
                config.controlPlaneJWTAuthenticationRequiredClaims(
                    new HashSet<>(Arrays.asList(splitAndTrim(value))));
                break;

            // --- Unsupported complex types ---
            case "logLevelOverrides":
            case "controlPlaneJWTAuthenticationMatchingClaims":
            case "proxyPass":
                logWarning("property mockserver." + key + " cannot be set via @MockServerTest"
                    + " (complex type not supported); use ConfigurationProperties or a properties file instead");
                break;

            default:
                logWarning("unknown mockserver property: mockserver." + key);
                break;
        }
    }

    private static boolean parseStrictBoolean(String value, String propertyName) {
        if ("true".equalsIgnoreCase(value)) {
            return true;
        } else if ("false".equalsIgnoreCase(value)) {
            return false;
        }
        throw new IllegalArgumentException(
            "mockserver." + propertyName + " must be 'true' or 'false', got: " + value);
    }

    private static String[] splitAndTrim(String value) {
        String[] parts = value.split(",");
        for (int i = 0; i < parts.length; i++) {
            parts[i] = parts[i].trim();
        }
        return parts;
    }

    private static InetSocketAddress parseInetSocketAddress(String value, String propertyName) {
        try {
            java.net.URI uri = new java.net.URI("https://" + value);
            String host = uri.getHost();
            int port = uri.getPort();
            if (host == null || port == -1) {
                throw new IllegalArgumentException(
                    propertyName + " must be in host:port format, got: " + value);
            }
            return new InetSocketAddress(host, port);
        } catch (java.net.URISyntaxException e) {
            throw new IllegalArgumentException(
                propertyName + " must be in host:port format, got: " + value, e);
        }
    }

    private static void logWarning(String message) {
        if (MockServerLogger.isEnabled(WARN)) {
            LOGGER.logEvent(
                new LogEntry()
                    .setLogLevel(WARN)
                    .setMessageFormat(message)
            );
        }
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (other == null || getClass() != other.getClass()) {
            return false;
        }
        MockServerPropertyCustomizer that = (MockServerPropertyCustomizer) other;
        return Objects.equals(properties, that.properties);
    }

    @Override
    public int hashCode() {
        return Objects.hash(properties);
    }
}
