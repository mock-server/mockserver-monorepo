package org.mockserver.netty.integration.tls.inbound;

import org.apache.http.HttpResponse;
import org.apache.http.client.HttpClient;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.client.utils.URIBuilder;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.util.EntityUtils;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;
import org.mockserver.integration.ClientAndServer;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.socket.tls.KeyAndCertificateFactory;
import org.mockserver.socket.tls.KeyAndCertificateFactoryFactory;
import org.mockserver.socket.tls.KeyStoreFactory;
import org.mockserver.testing.integration.mock.AbstractMockingIntegrationTestBase;
import org.mockserver.uuid.UUIDService;

import javax.net.ssl.SSLContext;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.security.cert.X509Certificate;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.core.Is.is;
import static org.mockserver.configuration.Configuration.configuration;
import static org.mockserver.configuration.ConfigurationProperties.directoryToSaveDynamicSSLCertificate;
import static org.mockserver.configuration.ConfigurationProperties.dynamicallyCreateCertificateAuthorityCertificate;
import static org.mockserver.configuration.ConfigurationProperties.tlsMutualAuthenticationCertificateChain;
import static org.mockserver.configuration.ConfigurationProperties.tlsMutualAuthenticationRequired;
import static org.mockserver.model.HttpRequest.request;
import static org.mockserver.model.HttpResponse.response;
import static org.mockserver.model.HttpStatusCode.OK_200;
import static org.mockserver.socket.tls.PEMToFile.privateKeyFromPEMFile;
import static org.mockserver.socket.tls.PEMToFile.x509FromPEMFile;
import static org.mockserver.stop.Stop.stopQuietly;

/**
 * @author jamesdbloom
 */
public class ClientAuthenticationAdditionalCertificateChainMockingIntegrationTest extends AbstractMockingIntegrationTestBase {

    private static String originalTLSMutualAuthenticationCertificateChain;
    private static boolean originalTLSMutualAuthenticationRequired;
    private static boolean originalDynamicallyCreateCertificateAuthorityCertificate;
    private static String originalDirectoryToSaveDynamicSSLCertificate;

    @BeforeClass
    @SuppressWarnings("ResultOfMethodCallIgnored")
    public static void startServer() throws Exception {
        // save original value
        originalTLSMutualAuthenticationCertificateChain = tlsMutualAuthenticationCertificateChain();
        originalTLSMutualAuthenticationRequired = tlsMutualAuthenticationRequired();
        originalDynamicallyCreateCertificateAuthorityCertificate = dynamicallyCreateCertificateAuthorityCertificate();
        originalDirectoryToSaveDynamicSSLCertificate = directoryToSaveDynamicSSLCertificate();

        // MockServer's own CA is trusted alongside the chain only when it is not the bundled CA
        File temporaryDirectory = new File(File.createTempFile("random", "temp").getParent(), UUIDService.getUUID());
        temporaryDirectory.mkdirs();
        dynamicallyCreateCertificateAuthorityCertificate(true);
        directoryToSaveDynamicSSLCertificate(temporaryDirectory.getAbsolutePath());

        // set new certificate authority values
        tlsMutualAuthenticationCertificateChain("org/mockserver/netty/integration/tls/ca.pem");
        tlsMutualAuthenticationRequired(true);

        mockServerClient = ClientAndServer.startClientAndServer().withSecure(true);
    }

    @AfterClass
    public static void stopServer() {
        stopQuietly(mockServerClient);

        // set back to original value
        tlsMutualAuthenticationCertificateChain(originalTLSMutualAuthenticationCertificateChain);
        tlsMutualAuthenticationRequired(originalTLSMutualAuthenticationRequired);
        dynamicallyCreateCertificateAuthorityCertificate(originalDynamicallyCreateCertificateAuthorityCertificate);
        directoryToSaveDynamicSSLCertificate(originalDirectoryToSaveDynamicSSLCertificate);
    }

    @Override
    public int getServerPort() {
        return mockServerClient.getPort();
    }

    @Test
    public void shouldReturnUpdateInHttp() {
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
        HttpClient httpClient = HttpClients.custom().setSSLContext(getSslContext()).build();
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

    private SSLContext getSslContext() {
        KeyAndCertificateFactory keyAndCertificateFactory
            = KeyAndCertificateFactoryFactory.createKeyAndCertificateFactory(configuration(), new MockServerLogger());
        assertThat(keyAndCertificateFactory, notNullValue());
        keyAndCertificateFactory.buildAndSavePrivateKeyAndX509Certificate();
        return new KeyStoreFactory(configuration(), new MockServerLogger())
            .sslContext(
                privateKeyFromPEMFile("org/mockserver/netty/integration/tls/leaf-key-pkcs8.pem"),
                x509FromPEMFile("org/mockserver/netty/integration/tls/leaf-cert.pem"),
                x509FromPEMFile("org/mockserver/netty/integration/tls/ca.pem"),
                new X509Certificate[]{
                    x509FromPEMFile("org/mockserver/netty/integration/tls/ca.pem"),
                    keyAndCertificateFactory.certificateAuthorityX509Certificate()
                }
            );
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
        UntrustedClientCertificate.assertRefused(mockServerClient, getServerPort(), calculatePath("some_path"));
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
        HttpClient httpClient = HttpClients.custom().setSSLContext(getSslContext()).build();
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
