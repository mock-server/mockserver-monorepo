package org.mockserver.netty.grpc;

import org.junit.Test;
import org.mockserver.model.HttpRequest;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.mockserver.model.HttpRequest.request;

/**
 * As {@link GrpcForwardFidelityIntegrationTest}, with no descriptors loaded, so MockServer relays
 * and records each call's protobuf frames as they are.
 */
public class GrpcForwardWithoutDescriptorsFidelityIntegrationTest extends GrpcForwardFidelityIntegrationTest {

    @Override
    protected boolean hasDescriptors() {
        return false;
    }

    /**
     * Without descriptors the recorded request is the framed protobuf, not JSON.
     */
    @Override
    @Test
    public void shouldVerifyAndRetrieveTheDecodedForwardedRequest() {
        greetingReply("verify-me");

        HttpRequest[] recorded = proxyClient.retrieveRecordedRequests(request().withPath("/" + SERVICE + "/Greeting"));
        assertThat(recorded.length, is(1));
        // one uncompressed frame: flag 0, length 11, then field 1 (0x0a), length 9, "verify-me"
        byte[] expected = new byte[]{0, 0, 0, 0, 11, 0x0a, 9, 'v', 'e', 'r', 'i', 'f', 'y', '-', 'm', 'e'};
        assertThat(java.util.Arrays.toString(recorded[0].getBodyAsRawBytes()), is(java.util.Arrays.toString(expected)));
    }
}
