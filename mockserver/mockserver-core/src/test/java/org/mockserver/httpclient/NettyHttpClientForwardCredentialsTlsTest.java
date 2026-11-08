package org.mockserver.httpclient;

import io.netty.channel.nio.NioEventLoopGroup;
import org.bouncycastle.asn1.x509.BasicConstraints;
import org.bouncycastle.asn1.x509.Extension;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.mockserver.configuration.Configuration;
import org.mockserver.file.FileReader;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.model.HttpRequest;
import org.mockserver.socket.tls.BundledCertificateAuthority;
import org.mockserver.socket.tls.ForwardProxyTLSX509CertificatesTrustManager;
import org.mockserver.socket.tls.NettySslContextFactory;
import org.mockserver.socket.tls.PEMToFile;

import javax.security.auth.x500.X500Principal;
import java.io.File;
import java.math.BigInteger;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.security.KeyPairGenerator;
import java.security.cert.X509Certificate;
import java.util.Date;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import static org.junit.Assert.assertThrows;
import static org.mockserver.configuration.Configuration.configuration;
import static org.mockserver.model.HttpRequest.request;

/**
 * A TLS connection that carries {@code forwardCredentialHeadersByHost} headers is always a new one, built with the
 * credential context fixed at startup, which trusts only the JVM's roots or the CUSTOM file, less MockServer's own CA:
 * so a certificate that CA signed is refused, even when the CUSTOM file names it, a later change to the forward trust
 * does not reach it, and a pooled connection opened under other trust never carries a credential.
 */
public class NettyHttpClientForwardCredentialsTlsTest {

    // PATH is set in every environment the build runs in, so the forward client resolves it without a test seam
    private static final String CREDENTIAL = System.getenv("PATH");
    private static final String BUNDLED_CA = "org/mockserver/socket/CertificateAuthorityCertificate.pem";

    @Rule
    public TemporaryFolder temporaryFolder = new TemporaryFolder();

    private NioEventLoopGroup group;
    private TlsTestUpstream upstream;

    @Before
    public void start() {
        group = new NioEventLoopGroup(2);
        upstream = new TlsTestUpstream();
    }

    @After
    public void stop() {
        upstream.close();
        group.shutdownGracefully(0, 0, TimeUnit.MILLISECONDS).syncUninterruptibly();
    }

    private Configuration withCredentials(ForwardProxyTLSX509CertificatesTrustManager trust) {
        Configuration configuration = configuration()
            .forwardCredentialHeadersByHost("localhost=X-Credential:PATH")
            .forwardProxyTLSX509CertificatesTrustManagerType(trust)
            .forwardProxyTLSHostnameVerificationEnabled(true)
            .forwardConnectionPoolEnabled(true);
        return trust == ForwardProxyTLSX509CertificatesTrustManager.CUSTOM ? configuration.forwardProxyTLSCustomTrustX509Certificates(TlsTestUpstream.SEPARATE_CA) : configuration;
    }

    private HttpRequest to(String method) {
        return request("/x").withMethod(method).withSecure(true).withHeader("Host", "localhost:" + upstream.port());
    }

    private void send(NettyHttpClient client, HttpRequest request) throws Exception {
        client.sendRequest(request, InetSocketAddress.createUnresolved("localhost", upstream.port())).get(30, TimeUnit.SECONDS);
    }

    private List<TlsTestUpstream.Received> carryingTheCredential() {
        return upstream.received().stream().filter(received -> received.headers().contains("X-Credential")).collect(Collectors.toList());
    }

    @Test
    public void shouldRefuseAnUpstreamCertificateMockServersOwnCaSigned() throws Exception {
        NettyHttpClient client = new NettyHttpClient(withCredentials(ForwardProxyTLSX509CertificatesTrustManager.JVM), new MockServerLogger(), group, null, true);

        ExecutionException refused = assertThrows(ExecutionException.class, () -> send(client, to("GET")));

        assertThat(refused.getCause().getMessage(), allOf(containsString("TLS handshake with localhost"), containsString("certification path")));
        upstream.awaitHandshakeFailure();
        assertThat(upstream.received(), is(empty()));
        // the forward client's own JVM trust adds MockServer's CA, so a request without the credential gets through
        send(client, to("TRACE"));
        assertThat(upstream.received(), hasSize(1));
        assertThat(carryingTheCredential(), is(empty()));
    }

    @Test
    public void shouldNotLetARuntimeChangeToTheForwardTrustReachTheCredentialConnection() throws Exception {
        Configuration configuration = withCredentials(ForwardProxyTLSX509CertificatesTrustManager.JVM);
        NettyHttpClient client = new NettyHttpClient(configuration, new MockServerLogger(), group, null, true);

        // as a PUT /mockserver/configuration applies them
        configuration.forwardProxyTLSX509CertificatesTrustManagerType(ForwardProxyTLSX509CertificatesTrustManager.CUSTOM).forwardProxyTLSCustomTrustX509Certificates(BUNDLED_CA);
        assertThrows(ExecutionException.class, () -> send(client, to("GET")));
        configuration.forwardProxyTLSX509CertificatesTrustManagerType(ForwardProxyTLSX509CertificatesTrustManager.ANY).forwardProxyTLSHostnameVerificationEnabled(false);
        assertThrows(ExecutionException.class, () -> send(client, to("GET")));

        send(client, to("TRACE"));
        assertThat(upstream.received(), hasSize(1));
        assertThat(carryingTheCredential(), is(empty()));
    }

    @Test
    public void shouldLeaveMockServersOwnCaOutOfACustomTrustFileThatNamesIt() throws Exception {
        File trustFile = temporaryFolder.newFile("trust.pem");
        Files.writeString(trustFile.toPath(), FileReader.readFileFromClassPathOrPath(BUNDLED_CA) + "\n" + FileReader.readFileFromClassPathOrPath(TlsTestUpstream.SEPARATE_CA));
        Configuration configuration = withCredentials(ForwardProxyTLSX509CertificatesTrustManager.CUSTOM).forwardProxyTLSCustomTrustX509Certificates(trustFile.getAbsolutePath());
        NettyHttpClient client = new NettyHttpClient(configuration, new MockServerLogger(), group, null, true);

        // the forward client's own CUSTOM trust holds MockServer's CA, so only the credential connection refuses
        assertThrows(ExecutionException.class, () -> send(client, to("GET")));
        upstream.awaitHandshakeFailure();
        send(client, to("TRACE"));
        assertThat(carryingTheCredential(), is(empty()));
        upstream.close();
        upstream = TlsTestUpstream.signedBySeparateCa();

        send(client, to("GET"));
        assertThat(carryingTheCredential(), hasSize(1));
    }

    @Test
    public void shouldLeaveACustomTrustCertificateForTheBundledCasKeyOut() throws Exception {
        X509Certificate bundled = PEMToFile.x509FromPEMFile(BUNDLED_CA);
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        // another issuer's certificate for the bundled CA's name and key: neither equal to it nor signed by it
        X509Certificate forTheBundledKey = new JcaX509CertificateConverter().getCertificate(
            new JcaX509v3CertificateBuilder(new X500Principal("CN=Not MockServer"), BigInteger.valueOf(System.nanoTime()), new Date(System.currentTimeMillis() - 86_400_000L), new Date(System.currentTimeMillis() + 86_400_000L), bundled.getSubjectX500Principal(), bundled.getPublicKey())
                .addExtension(Extension.basicConstraints, true, new BasicConstraints(true))
                .build(new JcaContentSignerBuilder("SHA256withRSA").build(generator.generateKeyPair().getPrivate()))
        );
        File trustFile = temporaryFolder.newFile("trust.pem");
        Files.writeString(trustFile.toPath(), PEMToFile.certToPEM(forTheBundledKey) + "\n" + FileReader.readFileFromClassPathOrPath(TlsTestUpstream.SEPARATE_CA));
        Configuration configuration = withCredentials(ForwardProxyTLSX509CertificatesTrustManager.CUSTOM).forwardProxyTLSCustomTrustX509Certificates(trustFile.getAbsolutePath());
        NettyHttpClient client = new NettyHttpClient(configuration, new MockServerLogger(), group, null, true);

        // the forward client's own CUSTOM trust always adds MockServer's CA, so only the credential connection refuses
        ExecutionException refused = assertThrows(ExecutionException.class, () -> send(client, to("GET")));
        assertThat(refused.getCause().getMessage(), allOf(containsString("TLS handshake with localhost"), containsString("certification path")));
        upstream.awaitHandshakeFailure();
        send(client, to("TRACE"));
        assertThat(carryingTheCredential(), is(empty()));
        upstream.close();
        upstream = TlsTestUpstream.signedBySeparateCa();

        send(client, to("GET"));
        assertThat(carryingTheCredential(), hasSize(1));
    }

    @Test
    public void shouldLeaveTheDynamicallyCreatedCaOutOfACustomTrustFileThatNamesIt() throws Exception {
        File directory = temporaryFolder.newFolder("dynamic");
        Configuration configuration = withCredentials(ForwardProxyTLSX509CertificatesTrustManager.CUSTOM)
            .dynamicallyCreateCertificateAuthorityCertificate(true)
            .directoryToSaveDynamicSSLCertificate(directory.getAbsolutePath());
        upstream.close();
        upstream = TlsTestUpstream.servingWith(new NettySslContextFactory(configuration, new MockServerLogger(), true).createServerSslContext());
        File dynamicCa = new File(directory, "CertificateAuthorityCertificate.pem");
        assertThat(BundledCertificateAuthority.chainContainsBundledCertificateAuthority(dynamicCa.getAbsolutePath()), is(false));
        File trustFile = temporaryFolder.newFile("trust.pem");
        Files.writeString(trustFile.toPath(), Files.readString(dynamicCa.toPath()) + "\n" + FileReader.readFileFromClassPathOrPath(TlsTestUpstream.SEPARATE_CA));
        NettyHttpClient client = new NettyHttpClient(configuration.forwardProxyTLSCustomTrustX509Certificates(trustFile.getAbsolutePath()), new MockServerLogger(), group, null, true);

        ExecutionException refused = assertThrows(ExecutionException.class, () -> send(client, to("GET")));
        assertThat(refused.getCause().getMessage(), allOf(containsString("TLS handshake with localhost"), containsString("certification path")));
        upstream.awaitHandshakeFailure();
        send(client, to("TRACE"));
        assertThat(carryingTheCredential(), is(empty()));
        upstream.close();
        upstream = TlsTestUpstream.signedBySeparateCa();

        send(client, to("GET"));
        assertThat(carryingTheCredential(), hasSize(1));
    }

    @Test
    public void shouldNeverSendTheCredentialOnAConnectionOpenedForAnotherRequest() throws Exception {
        upstream.close();
        upstream = TlsTestUpstream.signedBySeparateCa();
        Configuration configuration = withCredentials(ForwardProxyTLSX509CertificatesTrustManager.CUSTOM);
        NettyHttpClient client = new NettyHttpClient(configuration, new MockServerLogger(), group, null, true);

        send(client, to("GET"));
        configuration.forwardProxyTLSX509CertificatesTrustManagerType(ForwardProxyTLSX509CertificatesTrustManager.ANY).forwardProxyTLSHostnameVerificationEnabled(false);
        // without the credential, so pooled: opened while the forward trust is lowered
        send(client, to("TRACE"));
        configuration.forwardProxyTLSX509CertificatesTrustManagerType(ForwardProxyTLSX509CertificatesTrustManager.CUSTOM).forwardProxyTLSHostnameVerificationEnabled(true);
        send(client, to("GET"));
        send(client, to("TRACE"));

        List<TlsTestUpstream.Received> received = upstream.received();
        assertThat(received.stream().map(TlsTestUpstream.Received::method).collect(Collectors.toList()), contains("GET", "TRACE", "GET", "TRACE"));
        int pooledPort = received.get(1).clientPort();
        // the pool did keep the connection opened under lowered trust, and the second TRACE reused it
        assertThat(received.get(3).clientPort(), is(pooledPort));
        assertThat(received.get(3).requestOnConnection(), is(2));
        List<TlsTestUpstream.Received> credentialed = carryingTheCredential();
        assertThat(credentialed, hasSize(2));
        for (TlsTestUpstream.Received withCredential : credentialed) {
            assertThat(withCredential.headers().get("X-Credential"), is(CREDENTIAL));
            assertThat(withCredential.requestOnConnection(), is(1));
            assertThat(withCredential.clientPort(), not(pooledPort));
        }
        assertThat(credentialed.get(0).clientPort(), not(credentialed.get(1).clientPort()));
    }
}
