package org.mockserver.matchers;

import org.junit.Test;
import org.mockserver.model.HttpRequest;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.sameInstance;

/**
 * Pins the counting contract of {@link MatchDifferenceCount} after the counter was changed from a
 * boxed {@code Integer} to a primitive {@code int}. The values it returns are unchanged, so this is
 * a behaviour-neutral change; the test guards the counting contract itself (the fail-fast decision
 * in HttpRequestPropertiesMatcher reads getFailures()), not the primitive-vs-boxed representation.
 */
public class MatchDifferenceCountTest {

    private final HttpRequest request = HttpRequest.request().withPath("somePath");

    @Test
    public void startsAtZero() {
        assertThat(new MatchDifferenceCount(request).getFailures(), is(0));
    }

    @Test
    public void incrementsByOne() {
        MatchDifferenceCount count = new MatchDifferenceCount(request);
        count.incrementFailures();
        count.incrementFailures();
        count.incrementFailures();
        assertThat(count.getFailures(), is(3));
    }

    @Test
    public void retainsHttpRequest() {
        MatchDifferenceCount count = new MatchDifferenceCount(request);
        assertThat(count.getHttpRequest(), is(sameInstance(request)));
    }
}
