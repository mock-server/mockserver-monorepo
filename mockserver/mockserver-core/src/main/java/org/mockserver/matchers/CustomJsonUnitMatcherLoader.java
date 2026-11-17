package org.mockserver.matchers;

import com.google.common.annotations.VisibleForTesting;
import org.hamcrest.Matcher;
import org.mockserver.configuration.ConfigurationProperties;
import org.mockserver.log.model.LogEntry;
import org.mockserver.logging.MockServerLogger;

import java.lang.reflect.Constructor;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import static org.apache.commons.lang3.StringUtils.isNotBlank;
import static org.mockserver.log.model.LogEntry.LogMessageType.SERVER_CONFIGURATION;
import static org.slf4j.event.Level.WARN;

/**
 * Loads the {@link CustomJsonUnitMatcherProvider} class named by
 * {@code mockserver.customJsonUnitMatchersClass} and exposes the matchers it produces.
 *
 * <p>The provider class is instantiated once via its public no-arg constructor and its matcher
 * map is cached for the JVM lifetime keyed on the class name, so changing the configuration
 * property in-process (e.g. between tests) picks up the new provider on next access. A named
 * class that does not implement {@link CustomJsonUnitMatcherProvider} is neither initialised
 * nor constructed.</p>
 *
 * <p>If the property is blank, the named class cannot be loaded, the class does not implement
 * {@link CustomJsonUnitMatcherProvider}, or any exception is thrown during instantiation,
 * an empty map is returned and a WARN log entry is written - matching is never failed because
 * of a misconfigured provider.</p>
 */
public final class CustomJsonUnitMatcherLoader {

    private static final MockServerLogger MOCK_SERVER_LOGGER = new MockServerLogger(CustomJsonUnitMatcherLoader.class);
    private static final CacheEntry EMPTY = new CacheEntry(null, Collections.emptyMap());

    // a single volatile reference avoids a TOCTOU window where readers could see a new class
    // name with the old (empty) matcher map between the two writes
    private static volatile CacheEntry cache = EMPTY;

    private CustomJsonUnitMatcherLoader() {
    }

    /**
     * Returns the named matchers contributed by the configured provider, or an empty map if no
     * provider is configured or the provider cannot be loaded.
     */
    public static Map<String, Matcher<?>> load() {
        return load(null);
    }

    /**
     * As {@link #load()} but preferring the {@code customJsonUnitMatchersClass} value carried on the
     * supplied {@link org.mockserver.configuration.Configuration} instance, falling back to the
     * static {@link ConfigurationProperties} store when it is {@code null}. The value is fixed at
     * startup: {@code PUT /mockserver/configuration} does not change it.
     *
     * @param configuration the live server configuration, or {@code null} to read the static store
     */
    public static Map<String, Matcher<?>> load(org.mockserver.configuration.Configuration configuration) {
        String className = configuration != null
            ? configuration.customJsonUnitMatchersClass()
            : ConfigurationProperties.customJsonUnitMatchersClass();
        CacheEntry current = cache;
        if (!isNotBlank(className)) {
            if (current != EMPTY) {
                cache = EMPTY;
            }
            return EMPTY.matchers;
        }
        if (className.equals(current.className)) {
            return current.matchers;
        }
        CacheEntry next = new CacheEntry(className, instantiate(className));
        cache = next;
        return next.matchers;
    }

    private static Map<String, Matcher<?>> instantiate(String className) {
        try {
            // loaded without initialising it so a class that is not a provider never has its static initialiser run
            Class<?> loadedClass = Class.forName(className, false, CustomJsonUnitMatcherLoader.class.getClassLoader());
            if (!CustomJsonUnitMatcherProvider.class.isAssignableFrom(loadedClass)) {
                logWarn(className, "configured custom json-unit matcher class does not implement CustomJsonUnitMatcherProvider, ignoring class:{}", null);
                return Collections.emptyMap();
            }
            Constructor<? extends CustomJsonUnitMatcherProvider> constructor = loadedClass.asSubclass(CustomJsonUnitMatcherProvider.class).getDeclaredConstructor();
            constructor.setAccessible(true);
            Map<String, Matcher<?>> matchers = constructor.newInstance().jsonUnitMatchers();
            if (matchers == null || matchers.isEmpty()) {
                return Collections.emptyMap();
            }
            HashMap<String, Matcher<?>> copy = new HashMap<>(matchers);
            // a null Matcher would NPE inside json-unit; drop them so misconfiguration logs cleanly
            copy.values().removeIf(matcher -> matcher == null);
            if (copy.isEmpty()) {
                return Collections.emptyMap();
            }
            return Collections.unmodifiableMap(copy);
        } catch (Throwable throwable) {
            logWarn(className, "exception while loading custom json-unit matcher class, ignoring class:{}", throwable);
            return Collections.emptyMap();
        }
    }

    private static void logWarn(String className, String messageFormat, Throwable throwable) {
        if (MOCK_SERVER_LOGGER.isEnabledForInstance(WARN)) {
            LogEntry entry = new LogEntry()
                .setType(SERVER_CONFIGURATION)
                .setLogLevel(WARN)
                .setMessageFormat(messageFormat)
                .setArguments(className);
            if (throwable != null) {
                entry.setThrowable(throwable);
            }
            MOCK_SERVER_LOGGER.logEvent(entry);
        }
    }

    /**
     * Clears the cached provider so the next call to {@link #load()} re-reads the configuration
     * property and re-instantiates the provider. Intended for tests that change the property
     * between cases.
     */
    @VisibleForTesting
    public static void reset() {
        cache = EMPTY;
    }

    private static final class CacheEntry {
        final String className;
        final Map<String, Matcher<?>> matchers;

        CacheEntry(String className, Map<String, Matcher<?>> matchers) {
            this.className = className;
            this.matchers = matchers;
        }
    }
}
