package org.mockserver.mockservlet;

import org.apache.catalina.Context;
import org.apache.catalina.connector.Connector;
import org.apache.catalina.startup.Tomcat;
import org.apache.tomcat.util.net.SSLHostConfig;
import org.apache.tomcat.util.net.SSLHostConfigCertificate;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.socket.PortFactory;
import org.mockserver.socket.tls.KeyStoreFactory;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.mockserver.configuration.Configuration.configuration;

/**
 * Data-plane authentication in a real servlet container: Tomcat terminates TLS, asks for (but does not require) a
 * client certificate and verifies it against its own trust store, and the servlet requires the credentials and the
 * certificate Tomcat reports. The servlet is built from its own configuration, so no global state changes.
 */
public class MockServerServletDataPlaneAuthenticationTomcatIntegrationTest {

    private static final String TOKEN = "tomcat-token";
    private static final int HTTP_PORT = PortFactory.findFreePort();
    private static final int HTTPS_PORT = PortFactory.findFreePort();
    private static Tomcat tomcat;
    private static KeyStoreFactory keyStoreFactory;

    @BeforeClass
    public static void startTomcat() throws Exception {
        keyStoreFactory = new KeyStoreFactory(configuration(), new MockServerLogger());
        keyStoreFactory.loadOrCreateKeyStore();
        String keyStore = new File(keyStoreFactory.keyStoreFileName).getAbsolutePath();

        tomcat = new Tomcat();
        tomcat.setBaseDir(new File(".").getCanonicalPath() + File.separatorChar + "tomcat_data_plane_authentication");
        tomcat.setPort(HTTP_PORT);
        tomcat.getConnector();

        Connector httpsConnector = new Connector();
        httpsConnector.setPort(HTTPS_PORT);
        httpsConnector.setSecure(true);
        httpsConnector.setScheme("https");
        httpsConnector.setProperty("SSLEnabled", "true");
        SSLHostConfig sslHostConfig = new SSLHostConfig();
        sslHostConfig.setSslProtocol("TLS");
        sslHostConfig.setCertificateVerification("optional");
        sslHostConfig.setTruststoreFile(keyStore);
        sslHostConfig.setTruststorePassword(KeyStoreFactory.KEY_STORE_PASSWORD);
        SSLHostConfigCertificate certificate = new SSLHostConfigCertificate(sslHostConfig, SSLHostConfigCertificate.Type.RSA);
        certificate.setCertificateKeyAlias(KeyStoreFactory.KEY_STORE_CERT_ALIAS);
        certificate.setCertificateKeystoreFile(keyStore);
        certificate.setCertificateKeystorePassword(KeyStoreFactory.KEY_STORE_PASSWORD);
        sslHostConfig.addCertificate(certificate);
        httpsConnector.addSslHostConfig(sslHostConfig);
        tomcat.getService().addConnector(httpsConnector);

        Context context = tomcat.addContext("", new File(".").getAbsolutePath());
        Tomcat.addServlet(context, "mockServerServlet", new MockServerServlet(configuration()
            .dataPlaneAuthenticationRequired(true)
            .dataPlaneBearerAuthenticationToken(TOKEN)
            .tlsMutualAuthenticationRequired(true)));
        context.addServletMappingDecoded("/*", "mockServerServlet");
        tomcat.start();

        HttpURLConnection upsert = (HttpURLConnection) new URL("http://localhost:" + HTTP_PORT + "/mockserver/expectation").openConnection();
        upsert.setRequestMethod("PUT");
        upsert.setDoOutput(true);
        upsert.getOutputStream().write("{\"httpRequest\":{\"path\":\"/mocked\"},\"httpResponse\":{\"body\":\"mocked\"}}".getBytes(StandardCharsets.UTF_8));
        assertThat(upsert.getResponseCode(), is(201));
    }

    @AfterClass
    public static void stopTomcat() throws Exception {
        if (tomcat != null) {
            tomcat.stop();
            tomcat.destroy();
        }
    }

    private static HttpURLConnection get(String url, SSLContext sslContext, boolean withCredentials) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        if (connection instanceof HttpsURLConnection https) {
            https.setSSLSocketFactory(sslContext.getSocketFactory());
            https.setHostnameVerifier((hostname, session) -> true);
        }
        if (withCredentials) {
            connection.setRequestProperty("Authorization", "Bearer " + TOKEN);
        }
        return connection;
    }

    private static String body(HttpURLConnection connection) throws IOException {
        try (InputStream inputStream = connection.getResponseCode() < 400 ? connection.getInputStream() : connection.getErrorStream()) {
            return inputStream == null ? "" : new String(inputStream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static SSLContext withoutClientCertificate() throws Exception {
        SSLContext sslContext = SSLContext.getInstance("TLS");
        sslContext.init(null, io.netty.handler.ssl.util.InsecureTrustManagerFactory.INSTANCE.getTrustManagers(), null);
        return sslContext;
    }

    @Test
    public void shouldServeARequestWithCredentialsAndAContainerVerifiedClientCertificate() throws Exception {
        HttpURLConnection connection = get("https://localhost:" + HTTPS_PORT + "/mocked", keyStoreFactory.sslContext(), true);

        assertThat(connection.getResponseCode(), is(200));
        assertThat(body(connection), is("mocked"));
    }

    @Test
    public void shouldRefuseARequestWithAClientCertificateButNoCredentials() throws Exception {
        HttpURLConnection connection = get("https://localhost:" + HTTPS_PORT + "/mocked", keyStoreFactory.sslContext(), false);

        assertThat(connection.getResponseCode(), is(401));
        assertThat(connection.getHeaderField("WWW-Authenticate"), is("Bearer"));
    }

    @Test
    public void shouldRefuseARequestWithCredentialsButNoClientCertificate() throws Exception {
        HttpURLConnection connection = get("https://localhost:" + HTTPS_PORT + "/mocked", withoutClientCertificate(), true);

        assertThat(connection.getResponseCode(), is(401));
        assertThat(body(connection), is("Unauthorized for data plane"));
    }

    @Test
    public void shouldAnswerUpgradeRequiredWithoutTls() throws Exception {
        HttpURLConnection connection = get("http://localhost:" + HTTP_PORT + "/mocked", null, true);

        assertThat(connection.getResponseCode(), is(426));
        assertThat(connection.getHeaderField("Upgrade"), is("TLS/1.2, HTTP/1.1"));
    }
}
