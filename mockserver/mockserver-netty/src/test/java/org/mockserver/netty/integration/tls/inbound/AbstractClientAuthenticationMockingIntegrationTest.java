package org.mockserver.netty.integration.tls.inbound;

import org.apache.http.HttpResponse;
import org.apache.http.client.HttpClient;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.client.utils.URIBuilder;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.util.EntityUtils;
import org.junit.Test;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.socket.tls.KeyStoreFactory;
import org.mockserver.testing.integration.mock.AbstractMockingIntegrationTestBase;

import java.nio.charset.StandardCharsets;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.core.Is.is;
import static org.mockserver.configuration.Configuration.configuration;
import static org.mockserver.model.HttpRequest.request;
import static org.mockserver.model.HttpResponse.response;
import static org.mockserver.model.HttpStatusCode.OK_200;

/**
 * @author jamesdbloom
 */
public abstract class AbstractClientAuthenticationMockingIntegrationTest extends AbstractMockingIntegrationTestBase {

    @Test
    public void shouldReturnUpgradeForHttp() {
        // when
        mockServerClient
            .when(
                request()
                    .withMethod("POST")
                    .withPath(calculatePath("some_path"))
            )
            .respond(
                response()
                    .withStatusCode(200)
                    .withBody("some_body_response")
            );

        // then
        assertThat(makeRequest(
                request()
                    .withPath(calculatePath("some_path"))
                    .withMethod("POST"),
                getHeadersToRemove()
            ), is(response()
                .withStatusCode(426)
                .withReasonPhrase("Upgrade Required")
                .withHeader("Upgrade", "TLS/1.2, HTTP/1.1")));
    }

    @Test
    public void shouldReturnResponseInHttpsNettyClient() {
        // when
        mockServerClient
            .when(
                request()
                    .withMethod("POST")
                    .withPath(calculatePath("some_path"))
            )
            .respond(
                response()
                    .withStatusCode(200)
                    .withBody("some_body_response")
            );

        // then
        assertThat(makeRequest(
                request()
                    .withSecure(true)
                    .withPath(calculatePath("some_path"))
                    .withMethod("POST"),
                getHeadersToRemove()
            ), is(response()
                .withStatusCode(OK_200.code())
                .withReasonPhrase(OK_200.reasonPhrase())
                .withBody("some_body_response")));
    }

    @Test
    public void shouldReturnResponseInHttpsApacheClient() throws Exception {
        // given
        mockServerClient
            .when(
                request()
                    .withMethod("POST")
                    .withPath(calculatePath("some_path"))
            )
            .respond(
                response()
                    .withStatusCode(200)
                    .withBody("some_body_response")
            );

        // when
        HttpClient httpClient = HttpClients.custom().setSSLContext(new KeyStoreFactory(configuration(), new MockServerLogger()).sslContext()).build();
        HttpResponse response = httpClient.execute(new HttpPost(new URIBuilder()
            .setScheme("https")
            .setHost("localhost")
            .setPort(getServerPort())
            .setPath(calculatePath("some_path"))
            .build()));
        String responseBody = new String(EntityUtils.toByteArray(response.getEntity()), StandardCharsets.UTF_8);

        // then
        assertThat(response.getStatusLine().getStatusCode(), is(OK_200.code()));
        assertThat(responseBody, is("some_body_response"));
    }

    @Test
    public void shouldFailToAuthenticateInHttpsApacheClient() throws Exception {
        // given
        mockServerClient
            .when(
                request()
                    .withMethod("POST")
                    .withPath(calculatePath("some_path"))
            )
            .respond(
                response()
                    .withStatusCode(200)
                    .withBody("some_body_response")
            );

        // then
        UntrustedClientCertificate.assertRefused(mockServerClient, getServerSecurePort(), calculatePath("some_path"));
    }

    @Test
    public void shouldFailToAuthenticateInHttpApacheClient() throws Exception {
        // given
        mockServerClient
            .when(
                request()
                    .withMethod("POST")
                    .withPath(calculatePath("some_path"))
            )
            .respond(
                response()
                    .withStatusCode(200)
                    .withBody("some_body_response")
            );

        // when
        HttpClient httpClient = HttpClients.custom().setSSLContext(new KeyStoreFactory(configuration(), new MockServerLogger()).sslContext()).build();
        HttpResponse response = httpClient.execute(new HttpPost(new URIBuilder()
            .setScheme("http")
            .setHost("localhost")
            .setPort(getServerPort())
            .setPath(calculatePath("some_path"))
            .build()));
        String responseBody = new String(EntityUtils.toByteArray(response.getEntity()), StandardCharsets.UTF_8);

        // then
        assertThat(response.getStatusLine().getStatusCode(), is(426));
        assertThat(response.containsHeader("Upgrade"), is(true));
        assertThat(responseBody, is(""));
    }

}
