package org.mockserver.templates.engine.helpers;

import org.mockserver.configuration.ForwardCredentialHeadersByHost;
import org.mockserver.log.model.LogEntry;
import org.mockserver.logging.MockServerLogger;
import org.slf4j.event.Level;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.UnaryOperator;

/**
 * The {@code systemValue} template helper, WireMock's {@code systemValue}: reads one environment variable
 * ({@code systemValue.env("NAME")}) or system property ({@code systemValue.property("name")}) by name.
 * <p>
 * Only names on {@code templateSystemValueAllowList} can be read, because anyone who can create an expectation
 * can write a template. One list covers both sources. Every other name, and every name when the list is empty (the
 * default), returns {@code ""}, exactly as an allowed name that is not set does, so a template cannot tell whether a
 * refused name exists. MockServer's own settings ({@code mockserver.*}, {@code MOCKSERVER_*}, in any case), the
 * JSSE store passwords and, as environment variables, every word in {@code forwardCredentialHeadersByHost} that could
 * name one, even in an entry it ignores, are refused whatever the list says. There is deliberately no way to list names or values.
 */
public class SystemValueTemplateHelper {

    private static final List<String> DENIED_PREFIXES = List.of("mockserver.", "MOCKSERVER_");
    private static final List<String> DENIED_NAMES = List.of("javax.net.ssl.keyStorePassword", "javax.net.ssl.trustStorePassword");
    // names longer than this are refused without being remembered, so the warned set stays small
    private static final int MAX_NAME_LENGTH = 256;
    // distinct refused names logged per allow list and logger; beyond this refusals are not logged
    private static final int MAX_REFUSED_NAMES_LOGGED = 1000;
    // most recently used allow lists, parsed once; a server uses one, so the bound only matters to embedded use
    private static final int MAX_CACHED_ALLOW_LISTS = 64;
    private static final Map<String, AllowList> ALLOW_LISTS = Collections.synchronizedMap(new LinkedHashMap<>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, AllowList> eldest) {
            return size() > MAX_CACHED_ALLOW_LISTS;
        }
    });

    private final AllowList allowList;
    private final MockServerLogger mockServerLogger;
    // the names this logger has already been told were refused, shared by every engine logging to it
    private final Set<String> refusedNamesLogged;
    private final UnaryOperator<String> environment;
    private final UnaryOperator<String> properties;
    // lower case, as environment variable names are case-insensitive on Windows
    private final Set<String> credentialVariables;

    /**
     * @param allowList        the {@code templateSystemValueAllowList} value
     * @param mockServerLogger where refused names are logged; may be null
     */
    public SystemValueTemplateHelper(String allowList, MockServerLogger mockServerLogger) {
        this(allowList, null, mockServerLogger);
    }

    /**
     * @param allowList                      the {@code templateSystemValueAllowList} value
     * @param forwardCredentialHeadersByHost the {@code forwardCredentialHeadersByHost} value, whose environment
     *                                       variables are never read
     * @param mockServerLogger               where refused names are logged; may be null
     */
    public SystemValueTemplateHelper(String allowList, String forwardCredentialHeadersByHost, MockServerLogger mockServerLogger) {
        this(allowList, forwardCredentialHeadersByHost, mockServerLogger, System::getenv, System::getProperty);
    }

    SystemValueTemplateHelper(String allowList, MockServerLogger mockServerLogger, UnaryOperator<String> environment, UnaryOperator<String> properties) {
        this(allowList, null, mockServerLogger, environment, properties);
    }

    SystemValueTemplateHelper(String allowList, String forwardCredentialHeadersByHost, MockServerLogger mockServerLogger, UnaryOperator<String> environment, UnaryOperator<String> properties) {
        this.allowList = allowListFor(allowList);
        this.mockServerLogger = mockServerLogger;
        this.refusedNamesLogged = mockServerLogger != null ? this.allowList.refusedNamesLoggedTo(mockServerLogger) : null;
        this.environment = environment;
        this.properties = properties;
        this.credentialVariables = credentialVariables(forwardCredentialHeadersByHost);
    }

    /**
     * Logs, once for each server start, which names templates can read through {@code systemValue} and any entries
     * that were ignored or that name MockServer's own settings. Logs nothing for an empty list.
     */
    public static void logAllowList(String allowList, MockServerLogger mockServerLogger) {
        logAllowList(allowList, null, mockServerLogger);
    }

    /**
     * As {@link #logAllowList(String, MockServerLogger)}, also warning when the list covers an environment variable
     * {@code forwardCredentialHeadersByHost} reads, named by what it is for, never by its name.
     */
    public static void logAllowList(String allowList, String forwardCredentialHeadersByHost, MockServerLogger mockServerLogger) {
        AllowList parsed = allowListFor(allowList);
        if (mockServerLogger == null || !mockServerLogger.isEnabledForInstance(Level.WARN)) {
            return;
        }
        List<String> coveredCredentials = new ArrayList<>();
        for (Map.Entry<String, String> variable : ForwardCredentialHeadersByHost.environmentVariables(forwardCredentialHeadersByHost).entrySet()) {
            if (parsed.allowsIgnoringCase(variable.getKey())) {
                coveredCredentials.add(variable.getValue());
            }
        }
        if (!coveredCredentials.isEmpty()) {
            mockServerLogger.logEvent(
                new LogEntry()
                    .setType(LogEntry.LogMessageType.WARN)
                    .setLogLevel(Level.WARN)
                    .setMessageFormat("templateSystemValueAllowList covers the environment variables forwardCredentialHeadersByHost reads for {}, which systemValue never reads whatever the list says")
                    .setArguments(String.join(", ", coveredCredentials))
            );
        }
        if (!parsed.ignoredEntries.isEmpty()) {
            mockServerLogger.logEvent(
                new LogEntry()
                    .setType(LogEntry.LogMessageType.WARN)
                    .setLogLevel(Level.WARN)
                    .setMessageFormat("templateSystemValueAllowList entries {} were ignored: an entry must be an exact name or a prefix followed by a single * at the end, and a bare * is not allowed because it would let templates read every environment variable and system property")
                    .setArguments(parsed.ignoredEntries)
            );
        }
        if (!parsed.reservedEntries.isEmpty()) {
            mockServerLogger.logEvent(
                new LogEntry()
                    .setType(LogEntry.LogMessageType.WARN)
                    .setLogLevel(Level.WARN)
                    .setMessageFormat("templateSystemValueAllowList entries {} name or cover MockServer's own settings (mockserver.* and MOCKSERVER_*) or the javax.net.ssl store passwords, which systemValue never reads whatever the list says")
                    .setArguments(parsed.reservedEntries)
            );
        }
        if (!parsed.exactNames.isEmpty() || parsed.prefixes.length > 0) {
            mockServerLogger.logEvent(
                new LogEntry()
                    .setType(LogEntry.LogMessageType.WARN)
                    .setLogLevel(Level.WARN)
                    .setMessageFormat("templates can read the environment variables and system properties named {} and those starting with {} through systemValue, so anyone who can create expectations, call them or read the log can read their values")
                    .setArguments(parsed.exactNamesText, parsed.prefixesText)
            );
        }
    }

    /**
     * The value of the environment variable, or {@code ""} when it is not set or not on the allow list.
     */
    public String env(String name) {
        return allowed(name, "environment variable", true) ? valueOrEmpty(environment.apply(name)) : "";
    }

    /**
     * The value of the system property, or {@code ""} when it is not set or not on the allow list.
     */
    public String property(String name) {
        return allowed(name, "system property", false) ? valueOrEmpty(properties.apply(name)) : "";
    }

    private boolean allowed(String name, String kind, boolean environmentVariable) {
        if (name == null || name.isEmpty()) {
            return false;
        }
        boolean reserved = isReserved(name) || (environmentVariable && credentialVariables.contains(name.toLowerCase(Locale.ROOT)));
        if (!reserved && allowList.allows(name)) {
            return true;
        }
        if (refusedNamesLogged != null && mockServerLogger.isEnabledForInstance(Level.WARN) && name.length() <= MAX_NAME_LENGTH
            && refusedNamesLogged.size() < MAX_REFUSED_NAMES_LOGGED && refusedNamesLogged.add(name)) {
            if (reserved) {
                mockServerLogger.logEvent(
                    new LogEntry()
                        .setType(LogEntry.LogMessageType.WARN)
                        .setLogLevel(Level.WARN)
                        .setMessageFormat("template systemValue refused to read {} {} because names starting with mockserver. or MOCKSERVER_, the javax.net.ssl store passwords and the environment variables forwardCredentialHeadersByHost reads are never readable by templates, so it returned an empty value")
                        .setArguments(kind, name)
                );
            } else {
                mockServerLogger.logEvent(
                    new LogEntry()
                        .setType(LogEntry.LogMessageType.WARN)
                        .setLogLevel(Level.WARN)
                        .setMessageFormat("template systemValue refused to read {} {} because it is not on templateSystemValueAllowList, so it returned an empty value; add the name, or a prefix ending in *, to templateSystemValueAllowList when the server starts if templates should read it, remembering that anyone who can create expectations can then read its value")
                        .setArguments(kind, name)
                );
            }
        }
        return false;
    }

    // environment variable names are case-insensitive on Windows, so every check ignores case
    private static boolean isReserved(String name) {
        for (String prefix : DENIED_PREFIXES) {
            if (name.regionMatches(true, 0, prefix, 0, prefix.length())) {
                return true;
            }
        }
        for (String deniedName : DENIED_NAMES) {
            if (deniedName.equalsIgnoreCase(name)) {
                return true;
            }
        }
        return false;
    }

    private static boolean prefixCoversReserved(String prefix) {
        if (isReserved(prefix)) {
            return true;
        }
        for (String reserved : DENIED_PREFIXES) {
            if (reserved.regionMatches(true, 0, prefix, 0, prefix.length())) {
                return true;
            }
        }
        for (String reserved : DENIED_NAMES) {
            if (reserved.regionMatches(true, 0, prefix, 0, prefix.length())) {
                return true;
            }
        }
        return false;
    }

    private static Set<String> credentialVariables(String forwardCredentialHeadersByHost) {
        Set<String> names = new HashSet<>();
        for (String name : ForwardCredentialHeadersByHost.possibleEnvironmentVariables(forwardCredentialHeadersByHost)) {
            names.add(name.toLowerCase(Locale.ROOT));
        }
        return names;
    }

    private static String valueOrEmpty(String value) {
        return value == null ? "" : value;
    }

    private static AllowList allowListFor(String text) {
        return ALLOW_LISTS.computeIfAbsent(text == null ? "" : text, AllowList::parse);
    }

    @Override
    public String toString() {
        return "SystemValueTemplateHelper";
    }

    private static final class AllowList {
        private final Set<String> exactNames;
        private final String[] prefixes;
        // the entries as written, comma-separated, for the startup warnings
        private final String ignoredEntries;
        private final String reservedEntries;
        private final String exactNamesText;
        private final String prefixesText;
        // weak keys, so a dry run's short-lived logger does not keep its set alive
        private final Map<MockServerLogger, Set<String>> refusedNamesLogged = Collections.synchronizedMap(new WeakHashMap<>());

        private AllowList(List<String> exactNames, List<String> prefixes, List<String> ignoredEntries, List<String> reservedEntries) {
            this.exactNames = Set.copyOf(exactNames);
            this.prefixes = prefixes.toArray(new String[0]);
            this.ignoredEntries = String.join(", ", ignoredEntries);
            this.reservedEntries = String.join(", ", reservedEntries);
            this.exactNamesText = String.join(", ", exactNames);
            this.prefixesText = String.join(", ", prefixes);
        }

        static AllowList parse(String text) {
            List<String> exactNames = new ArrayList<>();
            List<String> prefixes = new ArrayList<>();
            List<String> ignored = new ArrayList<>();
            List<String> reserved = new ArrayList<>();
            for (String entry : text.split(",")) {
                String name = entry.trim();
                if (name.isEmpty()) {
                    continue;
                }
                int star = name.indexOf('*');
                if (star < 0) {
                    if (!exactNames.contains(name)) {
                        exactNames.add(name);
                    }
                    if (isReserved(name)) {
                        reserved.add(name);
                    }
                } else if (star == name.length() - 1 && star > 0) {
                    String prefix = name.substring(0, star);
                    prefixes.add(prefix);
                    if (prefixCoversReserved(prefix)) {
                        reserved.add(name);
                    }
                } else {
                    ignored.add(name);
                }
            }
            return new AllowList(exactNames, prefixes, ignored, reserved);
        }

        Set<String> refusedNamesLoggedTo(MockServerLogger mockServerLogger) {
            return refusedNamesLogged.computeIfAbsent(mockServerLogger, logger -> ConcurrentHashMap.newKeySet());
        }

        boolean allows(String name) {
            if (exactNames.contains(name)) {
                return true;
            }
            for (String prefix : prefixes) {
                if (name.startsWith(prefix)) {
                    return true;
                }
            }
            return false;
        }

        boolean allowsIgnoringCase(String name) {
            for (String exactName : exactNames) {
                if (exactName.equalsIgnoreCase(name)) {
                    return true;
                }
            }
            for (String prefix : prefixes) {
                if (name.regionMatches(true, 0, prefix, 0, prefix.length())) {
                    return true;
                }
            }
            return false;
        }
    }
}
