package org.mockserver.netty.integration.tls.inbound;

import org.apache.http.client.HttpClient;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.client.utils.URIBuilder;
import org.apache.http.impl.client.HttpClients;
import org.mockserver.client.MockServerClient;
import org.mockserver.verify.VerificationTimes;

import java.io.IOException;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.core.AnyOf.anyOf;
import static org.junit.Assert.fail;
import static org.mockserver.configuration.Configuration.configuration;
import static org.mockserver.echo.tls.UniqueCertificateChainSSLContextBuilder.uniqueCertificateChainSSLContext;
import static org.mockserver.model.HttpRequest.request;

final class UntrustedClientCertificate {

    private UntrustedClientCertificate() {
    }

    /**
     * POSTs to {@code path} over HTTPS with a client certificate from a chain MockServer does not trust, and asserts
     * the handshake is refused and no request on the path reached MockServer.
     */
    static void assertRefused(MockServerClient mockServerClient, int port, String path) throws Exception {
        HttpClient httpClient = HttpClients.custom().setSSLContext(uniqueCertificateChainSSLContext(configuration())).build();
        HttpPost post = new HttpPost(new URIBuilder()
            .setScheme("https")
            .setHost("localhost")
            .setPort(port)
            .setPath(path)
            .build());
        IOException failure = null;
        try {
            httpClient.execute(post);
        } catch (IOException exception) {
            failure = exception;
        }
        if (failure == null) {
            fail("SSLHandshakeException expected");
        }
        assertThat(failure.getMessage(),
            anyOf(
                containsString("Received fatal alert: bad_certificate"),
                containsString("Received fatal alert: certificate_unknown"),
                containsString("Received fatal alert: internal_error"),
                containsString("readHandshakeRecord"),
                containsString("Broken pipe"),
                containsString("wrong type for socket"),
                containsString("Connection reset")
            )
        );
        mockServerClient.verify(request().withPath(path), VerificationTimes.never());
    }
}
