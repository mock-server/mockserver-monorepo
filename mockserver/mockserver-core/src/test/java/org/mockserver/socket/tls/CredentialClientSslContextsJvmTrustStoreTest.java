package org.mockserver.socket.tls;

import io.netty.bootstrap.Bootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslHandler;
import io.netty.util.concurrent.Future;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockserver.configuration.Configuration;
import org.mockserver.httpclient.TlsTestUpstream;
import org.mockserver.log.model.LogEntry;
import org.mockserver.logging.MockServerLogger;
import org.slf4j.event.Level;

import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.security.KeyStore;
import java.security.cert.X509Certificate;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import static org.junit.Assert.assertThrows;
import static org.mockserver.configuration.Configuration.configuration;

/**
 * {@link KeyStoreFactory} points {@code javax.net.ssl.trustStore} at a store holding MockServer's CA and certificate,
 * so the JVM's default trust can include them; the credential contexts still never trust them. Registered in the
 * sequential surefire phase, as it changes that JVM-wide property.
 */
public class CredentialClientSslContextsJvmTrustStoreTest {

    private final String trustStoreBefore = System.getProperty("javax.net.ssl.trustStore");
    private final List<String> warnings = new CopyOnWriteArrayList<>();
    private final MockServerLogger logger = new MockServerLogger(configuration().logLevel("WARN"), CredentialClientSslContextsJvmTrustStoreTest.class) {
        @Override
        public void logEvent(LogEntry logEntry) {
            if (logEntry.getLogLevel() == Level.WARN) {
                warnings.add(logEntry.getMessage());
            }
        }
    };
    private File keyStoreFile;
    private NioEventLoopGroup group;

    @Before
    public void pointTheJvmTrustStoreAtAKeyStoreFactoryStore() throws Exception {
        KeyStoreFactory keyStoreFactory = new KeyStoreFactory(configuration(), new MockServerLogger());
        keyStoreFactory.loadOrCreateKeyStore();
        keyStoreFile = new File(keyStoreFactory.keyStoreFileName);
        assertThat(System.getProperty("javax.net.ssl.trustStore"), is(keyStoreFile.getAbsolutePath()));
        group = new NioEventLoopGroup(1);
    }

    @After
    public void restoreTheJvmTrustStore() {
        if (trustStoreBefore == null) {
            System.clearProperty("javax.net.ssl.trustStore");
        } else {
            System.setProperty("javax.net.ssl.trustStore", trustStoreBefore);
        }
        if (keyStoreFile != null) {
            keyStoreFile.delete();
        }
        group.shutdownGracefully(0, 0, TimeUnit.MILLISECONDS).syncUninterruptibly();
    }

    private NettySslContextFactory credentialFactory() {
        Configuration configuration = configuration()
            .forwardProxyTLSX509CertificatesTrustManagerType(ForwardProxyTLSX509CertificatesTrustManager.JVM)
            .forwardProxyTLSHostnameVerificationEnabled(true);
        return new NettySslContextFactory(configuration, logger, false);
    }

    @Test
    public void shouldBuildNoCredentialContextWhenTheJvmTrustStoreHoldsOnlyMockServersCaAndCertificate() throws Exception {
        TrustManagerFactory jvmTrust = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        jvmTrust.init((KeyStore) null);
        X509Certificate[] jvmAnchors = ((X509TrustManager) jvmTrust.getTrustManagers()[0]).getAcceptedIssuers();
        assertThat(BundledCertificateAuthority.containsBundledCertificateAuthority(jvmAnchors), is(true));

        IllegalStateException refused = assertThrows(IllegalStateException.class, () -> credentialFactory().createCredentialClientSslContexts());

        assertThat(refused.getMessage(), containsString("the JVM's default trust store holds no certificate other than MockServer's own CA or certificate"));
        assertThat(warnings, hasItem(allOf(containsString("the JVM's default trust store"), containsString(String.valueOf(jvmAnchors.length)), containsString("of MockServer's own CA or certificates"))));
    }

    @Test
    public void shouldTrustOnlyTheOtherCertificatesInTheJvmTrustStore() throws Exception {
        KeyStore store = KeyStore.getInstance(KeyStoreFactory.KEY_STORE_TYPE);
        try (FileInputStream in = new FileInputStream(keyStoreFile)) {
            store.load(in, KeyStoreFactory.KEY_STORE_PASSWORD.toCharArray());
        }
        store.setCertificateEntry("separate-ca", PEMToFile.x509FromPEMFile(TlsTestUpstream.SEPARATE_CA));
        try (FileOutputStream out = new FileOutputStream(keyStoreFile)) {
            store.store(out, KeyStoreFactory.KEY_STORE_PASSWORD.toCharArray());
        }

        CredentialClientSslContexts contexts = credentialFactory().createCredentialClientSslContexts();

        try (TlsTestUpstream ownCa = new TlsTestUpstream(); TlsTestUpstream separateCa = TlsTestUpstream.signedBySeparateCa()) {
            for (boolean http2 : Arrays.asList(false, true)) {
                Future<Channel> refused = handshake(contexts.forHost(http2, "localhost"), ownCa.port());
                assertThat(refused.cause(), is(notNullValue()));
                assertThat(causes(refused.cause()), containsString("certification path"));
                Future<Channel> trusted = handshake(contexts.forHost(http2, "localhost"), separateCa.port());
                assertThat(String.valueOf(trusted.cause()), trusted.isSuccess(), is(true));
                trusted.getNow().close().syncUninterruptibly();
            }
        }
    }

    private static String causes(Throwable throwable) {
        StringBuilder causes = new StringBuilder();
        for (Throwable cause = throwable; cause != null; cause = cause.getCause()) {
            causes.append(cause).append('\n');
        }
        return causes.toString();
    }

    private Future<Channel> handshake(SslContext sslContext, int port) {
        SslHandler[] sslHandler = new SslHandler[1];
        Channel channel = new Bootstrap()
            .group(group)
            .channel(NioSocketChannel.class)
            .handler(new ChannelInitializer<SocketChannel>() {
                @Override
                protected void initChannel(SocketChannel ch) {
                    sslHandler[0] = sslContext.newHandler(ch.alloc(), "localhost", port);
                    ch.pipeline().addLast(sslHandler[0]);
                }
            })
            .connect("localhost", port)
            .syncUninterruptibly()
            .channel();
        Future<Channel> handshake = sslHandler[0].handshakeFuture().awaitUninterruptibly();
        if (!handshake.isSuccess()) {
            channel.close().syncUninterruptibly();
        }
        return handshake;
    }
}
