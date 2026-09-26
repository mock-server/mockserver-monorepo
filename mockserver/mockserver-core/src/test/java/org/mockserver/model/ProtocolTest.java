package org.mockserver.model;

import org.junit.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.sameInstance;
import static org.mockserver.model.NottableString.string;

/**
 * Pins the precomputed per-constant {@link NottableString} that the matcher reads for every candidate
 * reaching the protocol field. It must equal {@code string(name())} (the value it replaced) and be a
 * shared, stable instance - reverting getNottableName() to a different value reddens the equality
 * assertion.
 */
public class ProtocolTest {

    @Test
    public void nottableNameEqualsStringOfName() {
        for (Protocol protocol : Protocol.values()) {
            assertThat(protocol.getNottableName(), is(string(protocol.name())));
        }
    }

    @Test
    public void nottableNameIsCachedInstance() {
        for (Protocol protocol : Protocol.values()) {
            assertThat(protocol.getNottableName(), is(sameInstance(protocol.getNottableName())));
        }
    }

    @Test
    public void nottableNameCarriesEnumConstantValue() {
        assertThat(Protocol.HTTP_1_1.getNottableName().getValue(), is("HTTP_1_1"));
        assertThat(Protocol.HTTP_2.getNottableName().getValue(), is("HTTP_2"));
        assertThat(Protocol.HTTP_3.getNottableName().getValue(), is("HTTP_3"));
    }
}
