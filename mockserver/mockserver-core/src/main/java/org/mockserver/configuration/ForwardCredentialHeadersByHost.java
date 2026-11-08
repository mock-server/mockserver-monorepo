package org.mockserver.configuration;

import io.netty.util.NetUtil;

import javax.annotation.Nullable;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Parses the {@code forwardCredentialHeadersByHost} property ({@code host[:port]=Header-Name:ENV_VAR_NAME,...})
 * without reading the environment, so code that only needs the configured names, such as log masking and the
 * template helpers, does not depend on the forward client that sends the values.
 */
public final class ForwardCredentialHeadersByHost {

    public static final String PROPERTY_NAME = "forwardCredentialHeadersByHost";
    public static final int ANY_PORT = -1;
    private static final String EXPECTED_FORMAT = "host[:port]=Header-Name:ENV_VAR_NAME";
    private static final String PLAINTEXT_PREFIX = "http://";
    private static final String TLS_PREFIX = "https://";
    // MockServer reads these as its own configuration, and the command line prints them when it starts
    private static final String RESERVED_VARIABLE_PREFIX = "MOCKSERVER_";
    // headers MockServer or the HTTP connection itself controls, which a credential must not replace; Cookie is
    // rebuilt from the request's cookies, so a credential in it would be dropped
    private static final Set<String> REFUSED_HEADER_NAMES = Set.of(
        "host", "content-length", "transfer-encoding", "connection", "keep-alive", "proxy-connection", "te",
        "trailer", "upgrade", "proxy-authorization", "proxy-authenticate", "cookie"
    );
    private static final Pattern HEADER_NAME = Pattern.compile("[!#$%&'*+.^_`|~0-9A-Za-z-]+");
    private static final Pattern ENVIRONMENT_VARIABLE_NAME = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");

    private ForwardCredentialHeadersByHost() {
    }

    /**
     * Whether {@code value} is set at all, whether or not its entries are valid.
     */
    public static boolean isConfigured(@Nullable String value) {
        return value != null && !value.isBlank();
    }

    /**
     * The valid entries in {@code value}, adding to {@code problems} why each other entry or header is ignored,
     * naming neither a variable nor a value.
     */
    public static List<Entry> parse(String value, List<String> problems) {
        List<Entry> entries = new ArrayList<>();
        String[] rawEntries = value.split(",");
        for (int index = 0; index < rawEntries.length; index++) {
            String rawEntry = rawEntries[index].trim();
            if (rawEntry.isEmpty()) {
                continue;
            }
            String position = "entry " + (index + 1);
            boolean plaintext = rawEntry.regionMatches(true, 0, PLAINTEXT_PREFIX, 0, PLAINTEXT_PREFIX.length());
            if (plaintext) {
                rawEntry = rawEntry.substring(PLAINTEXT_PREFIX.length());
            } else if (rawEntry.regionMatches(true, 0, TLS_PREFIX, 0, TLS_PREFIX.length())) {
                rawEntry = rawEntry.substring(TLS_PREFIX.length());
            }
            int equals = rawEntry.indexOf('=');
            if (equals <= 0 || equals == rawEntry.length() - 1) {
                problems.add(position + " is ignored because it is not " + EXPECTED_FORMAT);
                continue;
            }
            String[] hostAndPort = parseHostAndPort(rawEntry.substring(0, equals).trim());
            if (hostAndPort == null) {
                problems.add(position + " is ignored because its host is not an ASCII host name, IP address, or either with :port");
                continue;
            }
            if (plaintext && hostAndPort[1] == null) {
                problems.add(position + " is ignored because a plain HTTP entry must name its port, as http://host:port");
                continue;
            }
            Destination destination = new Destination(hostAndPort[0], hostAndPort[1] != null ? Integer.parseInt(hostAndPort[1]) : ANY_PORT, plaintext);
            String[] headers = rawEntry.substring(equals + 1).split(";");
            for (int headerIndex = 0; headerIndex < headers.length; headerIndex++) {
                String trimmed = headers[headerIndex].trim();
                String headerPosition = position + " header " + (headerIndex + 1);
                int colon = trimmed.indexOf(':');
                String headerName = colon > 0 ? trimmed.substring(0, colon).trim() : "";
                String environmentVariable = colon > 0 ? trimmed.substring(colon + 1).trim() : "";
                if (!HEADER_NAME.matcher(headerName).matches() || !ENVIRONMENT_VARIABLE_NAME.matcher(environmentVariable).matches()) {
                    problems.add(headerPosition + " is ignored because it is not Header-Name:ENV_VAR_NAME, where the variable name has only letters, digits and _");
                } else if (REFUSED_HEADER_NAMES.contains(headerName.toLowerCase(Locale.ROOT))) {
                    problems.add(headerPosition + " is ignored because MockServer or the connection controls the " + headerName.toLowerCase(Locale.ROOT) + " header");
                } else if (environmentVariable.regionMatches(true, 0, RESERVED_VARIABLE_PREFIX, 0, RESERVED_VARIABLE_PREFIX.length())) {
                    problems.add(headerPosition + " is ignored because its environment variable starts with " + RESERVED_VARIABLE_PREFIX + ", which MockServer reads as its own configuration and prints when it starts");
                } else {
                    entries.add(new Entry(destination, headerName, environmentVariable, headerPosition));
                }
            }
        }
        return entries;
    }

    /**
     * The lower-case names of the headers {@code value} configures, so the logs that show raw header lines can mask
     * them.
     */
    public static Set<String> headerNames(@Nullable String value) {
        if (!isConfigured(value)) {
            return Collections.emptySet();
        }
        Set<String> names = new LinkedHashSet<>();
        for (Entry entry : parse(value, new ArrayList<>())) {
            names.add(entry.headerName().toLowerCase(Locale.ROOT));
        }
        return names;
    }

    /**
     * The environment variables {@code value} reads, each mapped to the position of the first header it values
     * ({@code entry 1 header 2}), which a message names in place of the variable.
     */
    public static Map<String, String> environmentVariables(@Nullable String value) {
        if (!isConfigured(value)) {
            return Collections.emptyMap();
        }
        Map<String, String> variables = new LinkedHashMap<>();
        for (Entry entry : parse(value, new ArrayList<>())) {
            variables.putIfAbsent(entry.environmentVariable(), entry.position());
        }
        return variables;
    }

    /**
     * Every word in {@code value} that could name an environment variable, from entries {@link #parse} ignores too,
     * so a name meant as a credential is never readable elsewhere even when its entry is malformed.
     */
    public static Set<String> possibleEnvironmentVariables(@Nullable String value) {
        if (!isConfigured(value)) {
            return Collections.emptySet();
        }
        Set<String> names = new LinkedHashSet<>();
        for (String word : value.split("[^A-Za-z0-9_]+")) {
            if (ENVIRONMENT_VARIABLE_NAME.matcher(word).matches()) {
                names.add(word);
            }
        }
        return names;
    }

    /**
     * Whether {@code value} has an entry for TLS connections.
     */
    public static boolean configuresTls(@Nullable String value) {
        if (!isConfigured(value)) {
            return false;
        }
        for (Entry entry : parse(value, new ArrayList<>())) {
            if (!entry.destination().plaintext()) {
                return true;
            }
        }
        return false;
    }

    /**
     * {@code [host, port or null]} with the host as {@link #normaliseHost} gives it, or {@code null} when
     * {@code hostAndPort} is not exactly a host or host:port; an IPv6 literal needs brackets to have a port.
     */
    @Nullable
    public static String[] parseHostAndPort(String hostAndPort) {
        String host;
        String port = null;
        if (hostAndPort.startsWith("[")) {
            int close = hostAndPort.indexOf(']');
            if (close < 0) {
                return null;
            }
            host = hostAndPort.substring(0, close + 1);
            String rest = hostAndPort.substring(close + 1);
            if (!rest.isEmpty()) {
                if (!rest.startsWith(":")) {
                    return null;
                }
                port = rest.substring(1);
            }
        } else {
            int colon = hostAndPort.indexOf(':');
            if (colon >= 0 && colon == hostAndPort.lastIndexOf(':')) {
                host = hostAndPort.substring(0, colon);
                port = hostAndPort.substring(colon + 1);
            } else {
                host = hostAndPort;
            }
        }
        host = normaliseHost(host);
        if (host.isEmpty() || (port != null && !isPort(port))) {
            return null;
        }
        return new String[]{host, port};
    }

    /**
     * {@code host} in the one form two names for the same host compare equal in: ASCII lower case, without a
     * trailing dot, and an IPv6 literal unbracketed and compressed. {@code ""}, which matches nothing, for anything
     * else: a non-ASCII, blank or padded name, brackets around anything but an IPv6 literal, or a zone-scoped IPv6
     * address, as the zone is not compared.
     */
    public static String normaliseHost(@Nullable String host) {
        if (host == null || host.isEmpty()) {
            return "";
        }
        for (int i = 0; i < host.length(); i++) {
            char character = host.charAt(i);
            // also refuses non-ASCII, which case folding could map onto an ASCII name (U+212A KELVIN SIGN to k)
            if (character <= 0x20 || character >= 0x7F || character == '%') {
                return "";
            }
        }
        String normalised = host.toLowerCase(Locale.ROOT);
        boolean bracketed = normalised.startsWith("[") && normalised.endsWith("]");
        if (bracketed) {
            normalised = normalised.substring(1, normalised.length() - 1);
        }
        if (normalised.indexOf(':') >= 0 || bracketed) {
            if (!NetUtil.isValidIpV6Address(normalised)) {
                return "";
            }
            InetAddress address = NetUtil.createInetAddressFromIpAddressString(normalised);
            return address != null ? NetUtil.toAddressString(address) : "";
        }
        while (normalised.endsWith(".")) {
            normalised = normalised.substring(0, normalised.length() - 1);
        }
        return isHostName(normalised) ? normalised : "";
    }

    // labels of [a-z0-9_-] separated by single dots; checked by hand, as a regex would allocate for every forward
    private static boolean isHostName(String host) {
        if (host.isEmpty() || host.charAt(0) == '.') {
            return false;
        }
        for (int i = 0; i < host.length(); i++) {
            char character = host.charAt(i);
            boolean label = (character >= 'a' && character <= 'z') || (character >= '0' && character <= '9') || character == '-' || character == '_';
            if (!label && !(character == '.' && host.charAt(i - 1) != '.')) {
                return false;
            }
        }
        return true;
    }

    private static boolean isPort(String port) {
        if (port.isEmpty() || port.length() > 5) {
            return false;
        }
        for (int i = 0; i < port.length(); i++) {
            // ASCII digits only: Integer.parseInt also reads other scripts' digits
            if (port.charAt(i) < '0' || port.charAt(i) > '9') {
                return false;
            }
        }
        int value = Integer.parseInt(port);
        return value > 0 && value <= 65535;
    }

    /**
     * A configured destination: a normalised host, a port or {@link #ANY_PORT}, and whether it is plain HTTP.
     */
    public record Destination(String host, int port, boolean plaintext) {

        public boolean matches(String otherHost, int otherPort, boolean secure) {
            return host.equals(otherHost) && (port == ANY_PORT || port == otherPort) && secure != plaintext;
        }

        public String description() {
            String shown = host.indexOf(':') >= 0 ? "[" + host + "]" : host;
            String withPort = port == ANY_PORT ? shown : shown + ":" + port;
            return plaintext ? PLAINTEXT_PREFIX + withPort : withPort;
        }
    }

    /**
     * One header for one destination, valued from {@code environmentVariable}; {@code position} ({@code entry 1 header
     * 2}) is how a message names it, as neither its host nor its variable is logged.
     */
    public record Entry(Destination destination, String headerName, String environmentVariable, String position) {
    }
}
