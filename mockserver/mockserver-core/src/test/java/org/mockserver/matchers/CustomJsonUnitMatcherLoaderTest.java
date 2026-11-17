package org.mockserver.matchers;

import org.hamcrest.BaseMatcher;
import org.hamcrest.Description;
import org.hamcrest.Matcher;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockserver.configuration.ConfigurationProperties;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.core.Is.is;
import static org.hamcrest.core.IsSame.sameInstance;

public class CustomJsonUnitMatcherLoaderTest {

    // held here rather than on the fixture, because reading a field of the fixture would initialise it
    static final AtomicBoolean NON_PROVIDER_INITIALISED = new AtomicBoolean();
    static final AtomicBoolean NON_PROVIDER_CONSTRUCTED = new AtomicBoolean();

    private String previousProperty;

    @Before
    public void recordPreviousProperty() {
        previousProperty = ConfigurationProperties.customJsonUnitMatchersClass();
        CustomJsonUnitMatcherLoader.reset();
    }

    @After
    public void restorePreviousProperty() {
        ConfigurationProperties.customJsonUnitMatchersClass(previousProperty);
        CustomJsonUnitMatcherLoader.reset();
    }

    @Test
    public void returnsEmptyMapWhenPropertyIsBlank() {
        ConfigurationProperties.customJsonUnitMatchersClass("");

        Map<String, Matcher<?>> matchers = CustomJsonUnitMatcherLoader.load();

        assertThat(matchers.isEmpty(), is(true));
    }

    @Test
    public void loadsMatchersFromConfiguredProvider() {
        ConfigurationProperties.customJsonUnitMatchersClass(SingleMatcherProvider.class.getName());

        Map<String, Matcher<?>> matchers = CustomJsonUnitMatcherLoader.load();

        assertThat(matchers.size(), is(1));
        assertThat(matchers.get("largerThan"), sameInstance(SingleMatcherProvider.LARGER_THAN));
    }

    @Test
    public void cachesProviderResultForSameClassName() {
        ConfigurationProperties.customJsonUnitMatchersClass(CountingProvider.class.getName());
        CountingProvider.callCount = 0;

        CustomJsonUnitMatcherLoader.load();
        CustomJsonUnitMatcherLoader.load();
        CustomJsonUnitMatcherLoader.load();

        assertThat(CountingProvider.callCount, is(1));
    }

    @Test
    public void reloadsWhenPropertyChanges() {
        ConfigurationProperties.customJsonUnitMatchersClass(SingleMatcherProvider.class.getName());
        Map<String, Matcher<?>> first = CustomJsonUnitMatcherLoader.load();
        assertThat(first.get("largerThan"), sameInstance(SingleMatcherProvider.LARGER_THAN));

        ConfigurationProperties.customJsonUnitMatchersClass(AlternativeProvider.class.getName());
        Map<String, Matcher<?>> second = CustomJsonUnitMatcherLoader.load();

        assertThat(second.get("isPositive"), sameInstance(AlternativeProvider.IS_POSITIVE));
        assertThat(second.size(), is(1));
    }

    @Test
    public void returnsEmptyMapWhenClassNotFound() {
        ConfigurationProperties.customJsonUnitMatchersClass("org.mockserver.does.not.Exist");

        Map<String, Matcher<?>> matchers = CustomJsonUnitMatcherLoader.load();

        assertThat(matchers.isEmpty(), is(true));
    }

    @Test
    public void returnsEmptyMapWhenClassIsNotProvider() {
        ConfigurationProperties.customJsonUnitMatchersClass(NotAProvider.class.getName());

        Map<String, Matcher<?>> matchers = CustomJsonUnitMatcherLoader.load();

        assertThat(matchers.isEmpty(), is(true));
    }

    @Test
    public void neverInitialisesOrConstructsAClassThatIsNotAProvider() {
        ConfigurationProperties.customJsonUnitMatchersClass(InitialisationTrackingNonProvider.class.getName());

        Map<String, Matcher<?>> matchers = CustomJsonUnitMatcherLoader.load();

        assertThat(matchers.isEmpty(), is(true));
        assertThat("static initialiser of a non-provider class must not run", NON_PROVIDER_INITIALISED.get(), is(false));
        assertThat("constructor of a non-provider class must not run", NON_PROVIDER_CONSTRUCTED.get(), is(false));
    }

    @Test
    public void returnsEmptyMapWhenProviderConstructorThrows() {
        ConfigurationProperties.customJsonUnitMatchersClass(ThrowingProvider.class.getName());

        Map<String, Matcher<?>> matchers = CustomJsonUnitMatcherLoader.load();

        assertThat(matchers.isEmpty(), is(true));
    }

    @Test
    public void returnsEmptyMapWhenProviderReturnsNull() {
        ConfigurationProperties.customJsonUnitMatchersClass(NullReturningProvider.class.getName());

        Map<String, Matcher<?>> matchers = CustomJsonUnitMatcherLoader.load();

        assertThat(matchers.isEmpty(), is(true));
    }

    @Test
    public void cachesEmptyMapInstanceWhenPropertyBlank() {
        ConfigurationProperties.customJsonUnitMatchersClass("");

        Map<String, Matcher<?>> first = CustomJsonUnitMatcherLoader.load();
        Map<String, Matcher<?>> second = CustomJsonUnitMatcherLoader.load();

        assertThat(second, sameInstance(first));
    }

    public static class SingleMatcherProvider implements CustomJsonUnitMatcherProvider {

        static final Matcher<?> LARGER_THAN = new LargerThanMatcher(BigDecimal.valueOf(100));

        @Override
        public Map<String, Matcher<?>> jsonUnitMatchers() {
            Map<String, Matcher<?>> matchers = new HashMap<>();
            matchers.put("largerThan", LARGER_THAN);
            return matchers;
        }
    }

    public static class AlternativeProvider implements CustomJsonUnitMatcherProvider {

        static final Matcher<?> IS_POSITIVE = new LargerThanMatcher(BigDecimal.ZERO);

        @Override
        public Map<String, Matcher<?>> jsonUnitMatchers() {
            Map<String, Matcher<?>> matchers = new HashMap<>();
            matchers.put("isPositive", IS_POSITIVE);
            return matchers;
        }
    }

    public static class CountingProvider implements CustomJsonUnitMatcherProvider {

        static int callCount;

        @Override
        public Map<String, Matcher<?>> jsonUnitMatchers() {
            callCount++;
            return Collections.emptyMap();
        }
    }

    public static class NullReturningProvider implements CustomJsonUnitMatcherProvider {

        @Override
        public Map<String, Matcher<?>> jsonUnitMatchers() {
            return null;
        }
    }

    public static class ThrowingProvider implements CustomJsonUnitMatcherProvider {

        public ThrowingProvider() {
            throw new IllegalStateException("provider boom");
        }

        @Override
        public Map<String, Matcher<?>> jsonUnitMatchers() {
            return Collections.emptyMap();
        }
    }

    public static class NotAProvider {
    }

    public static class InitialisationTrackingNonProvider {

        static {
            NON_PROVIDER_INITIALISED.set(true);
        }

        public InitialisationTrackingNonProvider() {
            NON_PROVIDER_CONSTRUCTED.set(true);
        }
    }

    static class LargerThanMatcher extends BaseMatcher<Object> {

        private final BigDecimal threshold;

        LargerThanMatcher(BigDecimal threshold) {
            this.threshold = threshold;
        }

        @Override
        public boolean matches(Object item) {
            if (item == null) {
                return false;
            }
            try {
                return new BigDecimal(item.toString()).compareTo(threshold) > 0;
            } catch (NumberFormatException e) {
                return false;
            }
        }

        @Override
        public void describeTo(Description description) {
            description.appendText("a number larger than ").appendValue(threshold);
        }
    }
}
