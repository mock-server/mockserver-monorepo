package org.mockserver.model;

import org.junit.Test;
import org.mockserver.authentication.CredentialRedaction;
import org.mockserver.authentication.ProxyAuthenticationContext;
import org.mockserver.serialization.ObjectMapperFactory;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;
import static org.hamcrest.Matchers.sameInstance;
import static org.mockserver.model.HttpRequest.request;

public class ProxyHttpRequestTest {
    private final ProxyAuthenticationContext identity = new ProxyAuthenticationContext("checked-proxy-user");

    @Test
    public void proxyStateDoesNotChangeRequestIdentityOrJson() throws Exception {
        HttpRequest ordinary = request("/origin").withHeader("Authorization", "Bearer origin");
        ordinary.withNot(false);
        HttpRequest proxy = ordinary.withProxyContext(identity, true);
        assertThat(ordinary.withProxyContext(null, false), sameInstance(ordinary));
        assertThat(proxy, is(ordinary));
        assertThat(ordinary, is(proxy));
        assertThat(proxy.hashCode(), is(ordinary.hashCode()));
        assertThat(proxy.toString(), is(ordinary.toString()));
        for (HttpRequest copy : new HttpRequest[]{proxy.clone(), proxy.shallowClone(), proxy.deepCopy()}) {
            assertThat(copy.getProxyAuthenticationContext(), is(identity));
            assertThat(copy.isDataPlaneRequest(), is(true));
        }
        String forgedJson = "{\"path\":\"/origin\",\"proxyAuthenticationContext\":{\"username\":\"forged\"},\"dataPlaneRequest\":true}";
        HttpRequest forged = ObjectMapperFactory.createObjectMapper().readValue(forgedJson, HttpRequest.class);
        assertThat(forged.getProxyAuthenticationContext(), nullValue());
        assertThat(forged.isDataPlaneRequest(), is(false));
    }

    @Test
    public void completeProxyRequestsAreDeepCopiedWithoutSharingState() {
        DeepCopyWalker walker = new DeepCopyWalker(DeepCopyWalker.Mode.COPY);
        DataPlaneCopyFixtures.bodies().forEach((name, body) -> {
            HttpRequest original = DataPlaneCopyFixtures.request(body.get(), "192.0.2.5:12345")
                .withProxyContext(new ProxyAuthenticationContext(identity.username()), true);
            HttpRequest copy = original.deepCopy();
            walker.walk(original, copy, "proxy " + name);
            assertThat(copy, is(original));
            assertThat(copy.getProxyAuthenticationContext(), not(sameInstance(original.getProxyAuthenticationContext())));
        });
        assertThat(walker.failures().toString(), walker.failures(), is(empty()));
    }

    @Test
    public void filterResultsTakeProxyStateFromTheReceivedRequest() {
        HttpRequest received = request("/origin").withProxyContext(identity, true);
        received.withLogCorrelationId("original-correlation");
        HttpRequest result = request("/changed").withProxyContext(new ProxyAuthenticationContext("forged"), false);
        HttpRequest accepted = DataPlaneCopies.acceptFilteredRequest(result, received, received);
        assertThat(accepted.getProxyAuthenticationContext(), is(identity));
        assertThat(accepted.isDataPlaneRequest(), is(true));
        assertThat(accepted.getLogCorrelationId(), is("original-correlation"));
        accepted = DataPlaneCopies.acceptFilteredRequest(received, result, request("/ordinary"));
        assertThat(accepted.getProxyAuthenticationContext(), nullValue());
        assertThat(accepted.isDataPlaneRequest(), is(false));
    }

    @Test
    public void forwardCredentialCopiesKeepProxyStateAndLogTheOriginalCredentials() {
        HttpRequest original = request("/mockserver/status").withHeader("Authorization", "Bearer origin")
            .withProxyContext(identity, true);
        HttpRequest forward = original.cloneToAddForwardCredentials().withHeader("Authorization", "Bearer injected-secret");
        for (HttpRequest copy : new HttpRequest[]{forward, forward.clone(), forward.shallowClone(), forward.deepCopy(),
            forward.withProxyContext(new ProxyAuthenticationContext("other-proxy-user"), true)}) {
            assertThat(copy.isDataPlaneRequest(), is(true));
            assertThat(copy.getProxyAuthenticationContext(), is(copy.getRequestBeforeForwardCredentials().getProxyAuthenticationContext()));
            HttpRequest logged = (HttpRequest) CredentialRedaction.forLog(copy);
            assertThat(logged.getFirstHeader("Authorization"), is("Bearer origin"));
            assertThat(copy.toString().contains("injected-secret"), is(false));
        }
    }
}
