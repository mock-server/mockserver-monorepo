package org.mockserver.httpclient;

import io.netty.channel.socket.nio.NioSocketChannel;
import org.junit.Test;
import org.mockserver.configuration.Configuration;
import org.mockserver.logging.LoggingHandler;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.model.Protocol;
import org.mockserver.socket.tls.ForwardProxyTLSX509CertificatesTrustManager;
import org.slf4j.event.Level;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.mockserver.configuration.Configuration.configuration;

/**
 * The TRACE wire loggers print every header unmasked, so a forward client that adds
 * {@code forwardCredentialHeadersByHost} credentials does not install them, whatever the log level.
 */
public class HttpClientInitializerTraceWireLoggingTest {

    private static boolean hasTraceWireLogger(boolean traceWireLogging) {
        Configuration atTrace = configuration().logLevel(Level.TRACE);
        NioSocketChannel channel = new NioSocketChannel();
        try {
            new HttpClientInitializer(null, new MockServerLogger(atTrace, HttpClientInitializerTraceWireLoggingTest.class), true, null, Protocol.HTTP_1_1, atTrace, null, traceWireLogging, null).initChannel(channel);
            return channel.pipeline().get(LoggingHandler.class) != null;
        } finally {
            channel.unsafe().closeForcibly();
        }
    }

    @Test
    public void shouldNotInstallTheTraceWireLoggerWhenTold() {
        assertThat(hasTraceWireLogger(true), is(true));
        assertThat(hasTraceWireLogger(false), is(false));
    }

    @Test
    public void shouldTurnTheTraceWireLoggersOffOnlyForAForwardClientThatAddsCredentials() {
        Configuration withCredentials = configuration().logLevel(Level.TRACE)
            .forwardCredentialHeadersByHost("http://api.example.com:8080=X-Path:PATH")
            .forwardProxyTLSX509CertificatesTrustManagerType(ForwardProxyTLSX509CertificatesTrustManager.JVM);

        NettyHttpClient forwardClient = new NettyHttpClient(withCredentials, new MockServerLogger(withCredentials, getClass()), (io.netty.channel.EventLoopGroup) null, null, true);
        NettyHttpClient withoutCredentials = new NettyHttpClient(configuration().logLevel(Level.TRACE), new MockServerLogger(), (io.netty.channel.EventLoopGroup) null, null, true);

        assertThat(forwardClient.forwardCredentialHeaders(), is(notNullValue()));
        assertThat(forwardClient.traceWireLogging(), is(false));
        assertThat(withoutCredentials.forwardCredentialHeaders(), is(nullValue()));
        assertThat(withoutCredentials.traceWireLogging(), is(true));
    }
}
