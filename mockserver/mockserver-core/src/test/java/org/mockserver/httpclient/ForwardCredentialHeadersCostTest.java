package org.mockserver.httpclient;

import org.junit.Test;
import org.mockserver.model.HttpRequest;

import java.net.InetSocketAddress;
import java.util.Map;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.sameInstance;
import static org.mockserver.model.HttpRequest.request;
import static org.mockserver.testing.AllocationMeasurement.allocatedBytes;
import static org.mockserver.testing.AllocationMeasurement.assumeHttpRequestIsNotInstrumented;

/**
 * Every forward and proxied request passes through {@link ForwardCredentialHeaders#applyTo(HttpRequest, java.net.InetSocketAddress)} when
 * {@code forwardCredentialHeadersByHost} is set, so a request it does not apply to must allocate nothing.
 */
public class ForwardCredentialHeadersCostTest {

    private static final int OPERATIONS = 200_000;
    // the least of several measurements: background work can only add bytes, so the minimum is the code's cost
    private static final int ATTEMPTS = 3;
    private static volatile Object sink;

    @Test
    public void shouldNotAllocateForARequestToAHostThatIsNotConfigured() {
        ForwardCredentialHeaders headers = ForwardCredentialHeaders.resolve(
            "api.example.com=Authorization:API_TOKEN;X-Tenant:TENANT_ID,billing.internal:8443=X-Api-Key:BILLING_KEY",
            Map.of("API_TOKEN", "Bearer token", "TENANT_ID", "tenant", "BILLING_KEY", "key")::get,
            null
        );
        HttpRequest request = request("/orders").withMethod("GET").withHeader("Host", "orders.internal:8080");
        InetSocketAddress otherHost = InetSocketAddress.createUnresolved("orders.internal", 8080);
        InetSocketAddress configuredHostOtherPort = InetSocketAddress.createUnresolved("billing.internal", 443);
        // the request is plain HTTP, and api.example.com is configured for TLS only
        InetSocketAddress configuredHostPlainHttp = InetSocketAddress.createUnresolved("api.example.com", 80);

        // null: the destination is read from the request's Host header, as for a forward with no socket address
        for (InetSocketAddress destination : new InetSocketAddress[]{otherHost, configuredHostOtherPort, configuredHostPlainHttp, null}) {
            assertThat(headers.applyTo(request, destination), sameInstance(request));
            apply(headers, request, destination);
            long least = Long.MAX_VALUE;
            for (int attempt = 0; attempt < ATTEMPTS; attempt++) {
                long before = allocatedBytes();
                apply(headers, request, destination);
                least = Math.min(least, (allocatedBytes() - before) / OPERATIONS);
            }
            assumeHttpRequestIsNotInstrumented();
            assertThat("bytes allocated per request to " + destination, least, is(0L));
        }
    }

    private static void apply(ForwardCredentialHeaders headers, HttpRequest request, InetSocketAddress destination) {
        Object last = null;
        for (int i = 0; i < OPERATIONS; i++) {
            last = headers.applyTo(request, destination);
        }
        sink = last;
    }
}
