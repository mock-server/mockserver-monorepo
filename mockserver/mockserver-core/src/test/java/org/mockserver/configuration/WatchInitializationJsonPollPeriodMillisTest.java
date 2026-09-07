package org.mockserver.configuration;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.Map;
import java.util.Set;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.core.Is.is;

/**
 * Resolution of {@code mockserver.watchInitializationJsonPollPeriodMillis}: default (5000), the
 * {@code -Dmockserver.watchInitializationJsonPollPeriodMillis} JVM system-property form, the
 * programmatic setter, the non-positive clamp, and the {@link Configuration} instance route.
 * <p>
 * The system-property case is the load-bearing one: a property whose name ends in a unit has a history
 * in this codebase of the {@code -Dmockserver.<name>} form being silently ignored (see
 * {@code maxSocketTimeoutInMillis}). Because this property's constant key <em>is</em> exactly
 * {@code mockserver.watchInitializationJsonPollPeriodMillis}, the natural {@code -D} name an operator
 * types resolves directly — {@link #shouldResolveRawSystemProperty} proves it rather than only
 * exercising the programmatic setter.
 * <p>
 * Mutates the process-wide {@link ConfigurationProperties} static store (and a raw system property), so
 * it is registered in the sequential (parallel-excluded) phase of {@code mockserver-core/pom.xml}.
 * <p>
 * {@link ConfigurationProperties} caches every resolved value — including built-in defaults — in its
 * static {@code propertyCache}. A raw {@link System#setProperty}/{@link System#clearProperty} does
 * <em>not</em> evict that cache, so each reset clears through the cache the same way the production
 * {@code clearProperty()} does (see {@link MaxLlmConversationBodySizeTest} for the same convention).
 */
public class WatchInitializationJsonPollPeriodMillisTest {

    private static final String KEY = "mockserver.watchInitializationJsonPollPeriodMillis";

    @Before
    @After
    public void resetProperty() throws Exception {
        System.clearProperty(KEY);
        clearCacheEntry(KEY);
        clearProgrammaticallySetKey(KEY);
    }

    @Test
    public void shouldReturnDefaultValue() {
        assertThat(ConfigurationProperties.watchInitializationJsonPollPeriodMillis(), is(5000L));
    }

    @Test
    public void shouldResolveRawSystemProperty() throws Exception {
        // given - the value set ONLY via the -Dmockserver.<name> JVM system-property form
        System.setProperty(KEY, "250");
        // and - the cache evicted so the getter re-resolves from the system property rather than a
        // previously-cached default (raw setProperty does not touch the cache)
        clearCacheEntry(KEY);

        // then - the -D form resolves to the effective poll period
        assertThat(ConfigurationProperties.watchInitializationJsonPollPeriodMillis(), is(250L));
    }

    @Test
    public void shouldReturnProgrammaticallyOverriddenValue() {
        ConfigurationProperties.watchInitializationJsonPollPeriodMillis(1234);

        assertThat(ConfigurationProperties.watchInitializationJsonPollPeriodMillis(), is(1234L));
    }

    @Test
    public void shouldClampZeroToOne() {
        ConfigurationProperties.watchInitializationJsonPollPeriodMillis(0);

        assertThat(ConfigurationProperties.watchInitializationJsonPollPeriodMillis(), is(1L));
    }

    @Test
    public void shouldClampNegativeToOne() {
        ConfigurationProperties.watchInitializationJsonPollPeriodMillis(-500);

        assertThat(ConfigurationProperties.watchInitializationJsonPollPeriodMillis(), is(1L));
    }

    @Test
    public void shouldWorkWithConfigurationInstance() {
        Configuration configuration = Configuration.configuration();

        // when - no per-instance override set
        // then - falls back to the static ConfigurationProperties value
        assertThat(configuration.watchInitializationJsonPollPeriodMillis(),
            is(ConfigurationProperties.watchInitializationJsonPollPeriodMillis()));

        // when - a per-instance value is set
        configuration.watchInitializationJsonPollPeriodMillis(750L);

        // then - the instance value wins
        assertThat(configuration.watchInitializationJsonPollPeriodMillis(), is(750L));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, String> propertyCache() throws Exception {
        java.lang.reflect.Field cacheField = ConfigurationProperties.class.getDeclaredField("propertyCache");
        cacheField.setAccessible(true);
        Object cache = cacheField.get(null);
        return cache instanceof Map ? (Map<String, String>) cache : null;
    }

    private static void clearCacheEntry(String key) throws Exception {
        Map<String, String> cache = propertyCache();
        if (cache != null) {
            cache.remove(key);
        }
    }

    @SuppressWarnings("unchecked")
    private static void clearProgrammaticallySetKey(String key) throws Exception {
        java.lang.reflect.Field keysField = ConfigurationProperties.class.getDeclaredField("programmaticallySetKeys");
        keysField.setAccessible(true);
        Object keys = keysField.get(null);
        if (keys instanceof Set) {
            ((Set<String>) keys).remove(key);
        }
    }
}
