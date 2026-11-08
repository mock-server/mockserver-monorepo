package org.mockserver.httpclient;

import com.google.common.annotations.VisibleForTesting;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.ssl.SslContext;
import org.mockserver.configuration.Configuration;
import org.mockserver.configuration.ForwardCredentialHeadersByHost;
import org.mockserver.configuration.ForwardCredentialHeadersByHost.Destination;
import org.mockserver.configuration.ForwardCredentialHeadersByHost.Entry;
import org.mockserver.log.model.LogEntry;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.model.Header;
import org.mockserver.model.HttpRequest;
import org.mockserver.socket.tls.CredentialClientSslContexts;
import org.mockserver.socket.tls.ForwardProxyTLSX509CertificatesTrustManager;
import org.mockserver.socket.tls.NettySslContextFactory;
import org.slf4j.event.Level;

import javax.annotation.Nullable;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;

import static org.mockserver.configuration.ForwardCredentialHeadersByHost.ANY_PORT;
import static org.mockserver.configuration.ForwardCredentialHeadersByHost.PROPERTY_NAME;
import static org.mockserver.configuration.ForwardCredentialHeadersByHost.normaliseHost;
import static org.mockserver.configuration.ForwardCredentialHeadersByHost.parse;
import static org.mockserver.configuration.ForwardCredentialHeadersByHost.parseHostAndPort;

/**
 * The {@code forwardCredentialHeadersByHost} headers: credentials MockServer adds to each request its forward client
 * sends to a configured upstream host, valued from environment variables read once when the server starts.
 * <p>
 * The property holds only environment variable names. {@link #applyTo} adds the headers to a copy of the request
 * that is written to the upstream and nowhere else, so the request the caller holds, and everything logged,
 * recorded or shown from it, never carries a value. A copy is made only when the connection target and the single
 * {@code Host} header, read strictly as {@code host[:port]}, are the configured host and port, the path starts with
 * {@code /}, the method is not {@code TRACE} or {@code CONNECT}, and the connection uses TLS, unless the entry is
 * written {@code http://host:port}. A TLS connection that carries a copy is always a new one, built with
 * {@link #sslContextFor}, which no later change to the forward TLS settings reaches.
 */
public final class ForwardCredentialHeaders {

    // a header value is written as ISO-8859-1, which would send any later character as '?'
    private static final char HIGHEST_HEADER_CHARACTER = '\u00ff';

    private static volatile Function<String, String> environment = System::getenv;

    // an array, as an iterator would be allocated for every forwarded request
    private final Target[] targets;
    private final MockServerLogger mockServerLogger;
    // null when no TLS entry applies, or when built without a configuration, which only tests do
    @Nullable
    private final CredentialClientSslContexts sslContexts;

    private ForwardCredentialHeaders(List<Target> targets, @Nullable MockServerLogger mockServerLogger, @Nullable CredentialClientSslContexts sslContexts) {
        this.targets = targets.toArray(new Target[0]);
        this.mockServerLogger = mockServerLogger;
        this.sslContexts = sslContexts;
    }

    /**
     * Replaces how environment variables are read, for tests, which cannot set them in a running JVM; {@code null}
     * restores {@link System#getenv(String)}. Only forward clients built while it is set read through it.
     */
    @VisibleForTesting
    static void environmentForTesting(@Nullable Function<String, String> lookup) {
        environment = lookup != null ? lookup : System::getenv;
    }

    /**
     * The headers for {@code configuration}, with their values read from the environment now, or {@code null} when
     * the property is empty or no header could be resolved. TLS entries are ignored unless the forward client
     * verifies upstream certificates (JVM or CUSTOM trust) and host names, and the contexts for the connections that
     * carry them could be built from {@code nettySslContextFactory}. Logs which hosts are sent which header names,
     * and why any entry was ignored, never a value.
     */
    @Nullable
    static ForwardCredentialHeaders forConfiguration(Configuration configuration, @Nullable MockServerLogger mockServerLogger, @Nullable NettySslContextFactory nettySslContextFactory) {
        return forConfiguration(configuration, mockServerLogger, nettySslContextFactory, environment);
    }

    /**
     * As {@link #forConfiguration(Configuration, MockServerLogger, NettySslContextFactory)}, reading each environment
     * variable through {@code environment}, for tests.
     */
    @VisibleForTesting
    @Nullable
    static ForwardCredentialHeaders forConfiguration(Configuration configuration, @Nullable MockServerLogger mockServerLogger, @Nullable NettySslContextFactory nettySslContextFactory, Function<String, String> environment) {
        String value = configuration != null ? configuration.forwardCredentialHeadersByHost() : null;
        if (value == null || value.isBlank()) {
            return null;
        }
        CredentialClientSslContexts sslContexts = null;
        if (ForwardCredentialHeadersByHost.configuresTls(value)) {
            ForwardProxyTLSX509CertificatesTrustManager trustManagerType = configuration.forwardProxyTLSX509CertificatesTrustManagerType();
            if (trustManagerType == null || trustManagerType == ForwardProxyTLSX509CertificatesTrustManager.ANY) {
                warn(mockServerLogger, "forwardProxyTLSX509CertificatesTrustManagerType is ANY, so they would be sent to any server that answers for a configured host name, including one that intercepts the connection; set it to JVM, or to CUSTOM with forwardProxyTLSCustomTrustX509Certificates, and set forwardProxyTLSHostnameVerificationEnabled to true", null);
            } else if (!Boolean.TRUE.equals(configuration.forwardProxyTLSHostnameVerificationEnabled())) {
                warn(mockServerLogger, "forwardProxyTLSHostnameVerificationEnabled is false, so they would be sent to any server with a trusted certificate for another name that intercepts the connection to a configured host; set forwardProxyTLSHostnameVerificationEnabled to true", null);
            } else if (nettySslContextFactory == null) {
                warn(mockServerLogger, "this forward client has no TLS settings", null);
            } else {
                try {
                    sslContexts = nettySslContextFactory.createCredentialClientSslContexts();
                } catch (RuntimeException failure) {
                    warn(mockServerLogger, "the TLS context for them could not be built from the JVM's trust store, tlsProtocols, forwardProxyTLSCustomTrustX509Certificates, forwardProxyPrivateKey, forwardProxyCertificateChain or forwardProxyClientCertificatesByHost: " + failure.getMessage(), failure);
                }
            }
        }
        Resolved resolved = resolve(value, environment, sslContexts != null);
        log(mockServerLogger, resolved.targets, resolved.problems);
        return resolved.targets.isEmpty() ? null : new ForwardCredentialHeaders(resolved.targets, mockServerLogger, sslContexts);
    }

    /**
     * As {@link #forConfiguration}, reading each environment variable through {@code environment}, for tests: the
     * TLS entries are kept, but there are no contexts for the connections that carry them, so {@link #sslContextFor}
     * refuses them.
     */
    @VisibleForTesting
    @Nullable
    static ForwardCredentialHeaders resolve(@Nullable String value, Function<String, String> environment, @Nullable MockServerLogger mockServerLogger) {
        if (value == null || value.isBlank()) {
            return null;
        }
        Resolved resolved = resolve(value, environment, true);
        log(mockServerLogger, resolved.targets, resolved.problems);
        return resolved.targets.isEmpty() ? null : new ForwardCredentialHeaders(resolved.targets, mockServerLogger, null);
    }

    private static Resolved resolve(String value, Function<String, String> environment, boolean tlsEntriesApply) {
        Resolved resolved = new Resolved();
        Map<Destination, Map<String, Header>> headersByDestination = new LinkedHashMap<>();
        for (Entry entry : parse(value, resolved.problems)) {
            if (!entry.destination().plaintext() && !tlsEntriesApply) {
                // not even read, so the value is never held
                continue;
            }
            String variableValue = environment.apply(entry.environmentVariable());
            // never the variable's name, which may be a value pasted where the name belongs, nor the host
            String variable = "the environment variable for " + entry.position();
            if (variableValue == null) {
                resolved.problems.add(variable + " is not set, so the header is not sent");
                continue;
            }
            variableValue = variableValue.strip();
            if (variableValue.isEmpty()) {
                resolved.problems.add(variable + " is empty, so the header is not sent");
                continue;
            }
            if (hasControlCharacter(variableValue)) {
                resolved.problems.add(variable + " holds a line break or control character, which a header value cannot carry, so the header is not sent");
                continue;
            }
            if (hasCharacterAbove(variableValue, HIGHEST_HEADER_CHARACTER)) {
                resolved.problems.add(variable + " holds a character above U+00FF, which a header value cannot carry, so the header is not sent");
                continue;
            }
            headersByDestination
                .computeIfAbsent(entry.destination(), destination -> new LinkedHashMap<>())
                .put(entry.headerName().toLowerCase(Locale.ROOT), new Header(entry.headerName(), variableValue));
        }
        for (Map.Entry<Destination, Map<String, Header>> destination : headersByDestination.entrySet()) {
            resolved.targets.add(new Target(destination.getKey(), new ArrayList<>(destination.getValue().values())));
        }
        // an entry for a host and port is applied after one for the host alone, so its header wins
        resolved.targets.sort((first, second) -> Boolean.compare(first.destination.port() != ANY_PORT, second.destination.port() != ANY_PORT));
        return resolved;
    }

    /**
     * {@code request}, unchanged, unless it is sent to a configured host; then a copy with the host's headers replacing
     * any of the same name, which remembers {@code request} so a log entry given the copy shows {@code request}. The
     * connection target is {@code remoteAddress} when given, else the request's {@code Host}. The caller must send a
     * copy on a new connection that is never pooled, over TLS with {@link #sslContextFor}.
     */
    HttpRequest applyTo(HttpRequest request, @Nullable InetSocketAddress remoteAddress) {
        InetSocketAddress destination = remoteAddress;
        if (destination == null) {
            if (!anyTargetMayBeNamedBy(request)) {
                return request;
            }
            try {
                destination = request.unresolvedSocketAddressFromHostHeader();
            } catch (RuntimeException noDestination) {
                return request;
            }
        }
        boolean secure = Boolean.TRUE.equals(request.isSecure());
        String host = normaliseHost(destination.getHostString());
        int port = destination.getPort();
        if (!anyTargetMatches(host, port, secure)) {
            return request;
        }
        String notAdded = whyNotAdded(request, host, port, secure);
        if (notAdded != null) {
            if (mockServerLogger != null && mockServerLogger.isEnabledForInstance(Level.DEBUG)) {
                mockServerLogger.logEvent(
                    new LogEntry()
                        .setType(LogEntry.LogMessageType.SERVER_CONFIGURATION)
                        .setLogLevel(Level.DEBUG)
                        .setMessageFormat(PROPERTY_NAME + " headers were not added to a request to{}because{}")
                        .setArguments(new Destination(host, port, !secure).description(), notAdded)
                );
            }
            return request;
        }
        HttpRequest withCredentials = request.clone().withRequestBeforeForwardCredentials(request);
        for (Header header : headersFor(host, port, secure)) {
            withCredentials.replaceHeader(header);
        }
        return withCredentials;
    }

    /**
     * The context for a TLS connection to {@code host} that carries these headers: it trusts only what the forward
     * TLS settings named at startup, never MockServer's own CA, and always verifies the host name.
     *
     * @throws IllegalStateException when no TLS entry applies, so no such connection may be made
     */
    public SslContext sslContextFor(boolean enableHttp2, @Nullable String host) {
        if (sslContexts == null) {
            throw new IllegalStateException(PROPERTY_NAME + " has no TLS context for a connection that carries its headers");
        }
        return sslContexts.forHost(enableHttp2, host);
    }

    /**
     * The headers to send to {@code host} on {@code port}, over TLS or not, in the order to apply them (a later one
     * replaces an earlier one of the same name), or an empty list.
     */
    List<Header> headersFor(@Nullable String host, int port, boolean secure) {
        String normalisedHost = normaliseHost(host);
        if (normalisedHost.isEmpty()) {
            return Collections.emptyList();
        }
        List<Header> headers = null;
        for (Target target : targets) {
            if (target.destination.matches(normalisedHost, port, secure)) {
                if (headers == null) {
                    headers = new ArrayList<>();
                }
                headers.addAll(target.headers);
            }
        }
        return headers != null ? headers : Collections.emptyList();
    }

    /**
     * Sets on {@code headers} the headers to send to {@code host} on {@code port}, over TLS or not, replacing any of
     * the same name, for a request this client does not send, such as a relayed WebSocket handshake; returns whether
     * any was set, when the caller must connect as {@link #applyTo} requires.
     */
    public boolean addTo(HttpHeaders headers, @Nullable String host, int port, boolean secure) {
        List<Header> toAdd = headersFor(host, port, secure);
        if (toAdd.isEmpty()) {
            return false;
        }
        for (Header header : toAdd) {
            headers.set(header.getName().getValue(), header.getValues().get(0).getValue());
        }
        return true;
    }

    // without allocating, as every forward with no remote address reaches it: false only when no target's host can be
    // the one the request's socket address or Host names; an IPv6 literal, which can be written several ways, may be
    private boolean anyTargetMayBeNamedBy(HttpRequest request) {
        org.mockserver.model.SocketAddress socketAddress = request.getSocketAddress();
        String name = socketAddress != null && socketAddress.getHost() != null ? socketAddress.getHost() : request.getFirstHeader("Host");
        if (name == null || name.isEmpty()) {
            return false;
        }
        int end = name.indexOf(':');
        if (name.charAt(0) == '[' || (end >= 0 && name.indexOf(':', end + 1) >= 0)) {
            return true;
        }
        if (end < 0) {
            end = name.length();
        }
        while (end > 0 && name.charAt(end - 1) == '.') {
            end--;
        }
        for (Target target : targets) {
            String host = target.destination.host();
            if (host.length() == end && name.regionMatches(true, 0, host, 0, end)) {
                return true;
            }
        }
        return false;
    }

    private boolean anyTargetMatches(String host, int port, boolean secure) {
        for (Target target : targets) {
            if (target.destination.matches(host, port, secure)) {
                return true;
            }
        }
        return false;
    }

    // checked here, not left to the request encoder, so the credential goes only to the destination it was set for:
    // the request line's authority, which through forwardHttpProxy is HttpProxyRequestTarget.of (the one Host)
    @Nullable
    private static String whyNotAdded(HttpRequest request, String host, int port, boolean secure) {
        String method = request.getMethod("GET");
        // an upstream answers TRACE with the request it received, credential and all, and CONNECT opens a tunnel
        if ("TRACE".equalsIgnoreCase(method) || "CONNECT".equalsIgnoreCase(method)) {
            return "it is a TRACE or CONNECT request";
        }
        if (!hostHeaderNames(request, host, port, secure)) {
            return "it does not have exactly one Host header naming that host and port";
        }
        if (!isPath(request)) {
            return "its path does not start with /";
        }
        return null;
    }

    // a Host padded with whitespace or holding a control character is refused, not trimmed, as it is sent as it is
    private static boolean hostHeaderNames(HttpRequest request, String host, int port, boolean secure) {
        List<String> hostHeaders = request.getHeader("Host");
        if (hostHeaders.size() != 1) {
            return false;
        }
        String hostHeader = hostHeaders.get(0);
        if (hostHeader == null || hostHeader.isEmpty()) {
            return false;
        }
        String[] hostAndPort = parseHostAndPort(hostHeader);
        if (hostAndPort == null) {
            return false;
        }
        int hostHeaderPort = hostAndPort[1] != null ? Integer.parseInt(hostAndPort[1]) : secure ? 443 : 80;
        return hostAndPort[0].equals(host) && hostHeaderPort == port;
    }

    // anything but "/..." can name its own authority in the request line
    private static boolean isPath(HttpRequest request) {
        String path = request.getPath() != null ? request.getPath().getValue() : null;
        return path != null && path.startsWith("/");
    }

    private static boolean hasControlCharacter(String value) {
        for (int i = 0; i < value.length(); i++) {
            char character = value.charAt(i);
            if ((character < 0x20 && character != '\t') || character == 0x7F) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasCharacterAbove(String value, char highest) {
        for (int i = 0; i < value.length(); i++) {
            if (value.charAt(i) > highest) {
                return true;
            }
        }
        return false;
    }

    private static void warn(@Nullable MockServerLogger mockServerLogger, String whyTlsEntriesAreIgnored, @Nullable Throwable failure) {
        if (mockServerLogger != null && mockServerLogger.isEnabledForInstance(Level.WARN)) {
            mockServerLogger.logEvent(
                new LogEntry()
                    .setType(LogEntry.LogMessageType.SERVER_CONFIGURATION)
                    .setLogLevel(Level.WARN)
                    .setMessageFormat(PROPERTY_NAME + " ignores its TLS entries, and sends their headers nowhere, because{}")
                    .setArguments(whyTlsEntriesAreIgnored)
                    .setThrowable(failure)
            );
        }
    }

    private static void log(@Nullable MockServerLogger mockServerLogger, List<Target> targets, List<String> problems) {
        if (mockServerLogger == null) {
            return;
        }
        if (mockServerLogger.isEnabledForInstance(Level.WARN)) {
            for (String problem : problems) {
                mockServerLogger.logEvent(
                    new LogEntry()
                        .setType(LogEntry.LogMessageType.SERVER_CONFIGURATION)
                        .setLogLevel(Level.WARN)
                        .setMessageFormat(PROPERTY_NAME + ":{}")
                        .setArguments(problem)
                );
            }
        }
        if (!targets.isEmpty() && mockServerLogger.isEnabledForInstance(Level.INFO)) {
            List<String> description = new ArrayList<>();
            for (Target target : targets) {
                List<String> names = new ArrayList<>();
                for (Header header : target.headers) {
                    names.add(header.getName().getValue());
                }
                description.add(target.destination.description() + " " + names);
            }
            mockServerLogger.logEvent(
                new LogEntry()
                    .setType(LogEntry.LogMessageType.SERVER_CONFIGURATION)
                    .setLogLevel(Level.INFO)
                    .setMessageFormat(PROPERTY_NAME + " adds these headers, valued from environment variables read at startup, to every request forwarded or proxied to:{}")
                    .setArguments(String.join(", ", description))
            );
        }
    }

    private static final class Resolved {
        private final List<Target> targets = new ArrayList<>();
        private final List<String> problems = new ArrayList<>();
    }

    private static final class Target {
        private final Destination destination;
        private final List<Header> headers;

        Target(Destination destination, List<Header> headers) {
            this.destination = destination;
            this.headers = headers;
        }
    }
}
