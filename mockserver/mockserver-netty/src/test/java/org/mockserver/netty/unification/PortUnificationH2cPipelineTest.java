package org.mockserver.netty.unification;

import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http2.Http2FrameCodec;
import io.netty.handler.codec.http2.Http2MultiplexHandler;
import io.netty.handler.codec.http2.HttpToHttp2ConnectionHandler;
import org.junit.Test;
import org.mockserver.configuration.Configuration;
import org.mockserver.configuration.ConfigurationProperties;
import org.mockserver.lifecycle.LifeCycle;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.mock.HttpState;
import org.mockserver.mock.action.http.HttpActionHandler;
import org.mockserver.netty.MockServerUnificationInitializer;
import org.mockserver.scheduler.Scheduler;

import java.nio.charset.StandardCharsets;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.Mockito.mock;
import static org.mockserver.configuration.Configuration.configuration;

/**
 * Verifies the HTTP/2 (h2c) pipeline produced by PortUnificationHandler.
 * <p>
 * Since issue #2669 the {@code Http2FrameCodec} + {@code Http2MultiplexHandler} pipeline is the ONLY
 * HTTP/2 server pipeline — every stream gets its own child channel — regardless of the
 * {@code grpcBidiStreamingEnabled} flag. The old connection-adapter path
 * ({@code HttpToHttp2ConnectionHandler}) is no longer installed. This test pins that the flip is in
 * effect even with the flag off (its default).
 */
public class PortUnificationH2cPipelineTest {

    private static final String H2C_PREFACE = "PRI * HTTP/2.0\r\n\r\nSM\r\n\r\n";

    /**
     * With grpcBidiStreamingEnabled false (the default) and no gRPC descriptor, sending the h2c
     * connection preface must produce the multiplex pipeline — {@code Http2FrameCodec} +
     * {@code Http2MultiplexHandler} on the connection channel — and NOT the old
     * {@code HttpToHttp2ConnectionHandler} adapter. Before the issue #2669 flip this asserted the
     * opposite, so it is the red-proof that plain (non-gRPC) HTTP/2 now runs on multiplex.
     * <p>
     * The per-stream handlers (WebSocket, dashboard, codec, request handler) now live on the child
     * stream channels created by {@code Http2MultiplexChildInitializer} when a stream opens, so they
     * are deliberately not asserted on the connection pipeline here — see
     * {@code Http2MultiplexConnectionScopeIntegrationTest} for the end-to-end child-pipeline proof.
     */
    @Test
    public void shouldUseMultiplexHandlerWhenFlagOff() {
        boolean original = ConfigurationProperties.grpcBidiStreamingEnabled();
        try {
            ConfigurationProperties.grpcBidiStreamingEnabled(false);
            Configuration config = configuration();

            EmbeddedChannel channel = new EmbeddedChannel();
            channel.pipeline().addLast(new MockServerUnificationInitializer(
                config,
                mock(LifeCycle.class),
                new HttpState(config, new MockServerLogger(), mock(Scheduler.class)),
                mock(HttpActionHandler.class),
                null
            ));

            // Send the HTTP/2 cleartext preface — triggers switchToH2c
            channel.writeInbound(Unpooled.wrappedBuffer(H2C_PREFACE.getBytes(StandardCharsets.US_ASCII)));

            // The pipeline must contain the multiplex codec + handler
            assertThat("expected Http2FrameCodec in pipeline",
                channel.pipeline().get(Http2FrameCodec.class), is(notNullValue()));
            assertThat("expected Http2MultiplexHandler in pipeline",
                channel.pipeline().get(Http2MultiplexHandler.class), is(notNullValue()));
            // And must NOT contain the old connection-adapter handler
            assertThat("should not have HttpToHttp2ConnectionHandler in pipeline",
                channel.pipeline().get(HttpToHttp2ConnectionHandler.class), is(nullValue()));

            // PortUnificationHandler should have been removed
            assertThat("PortUnificationHandler should have been removed",
                channel.pipeline().get(PortUnificationHandler.class), is(nullValue()));

            channel.finishAndReleaseAll();
        } finally {
            ConfigurationProperties.grpcBidiStreamingEnabled(original);
        }
    }

    /**
     * SEC-05: after switching to cleartext HTTP/2 (h2c), the pipeline must record HTTP/2 as the
     * channel's negotiated protocol from the trusted server-side preface detection (there is no ALPN
     * for h2c). This is what lets the request mapper recognise genuine h2c and capture the HTTP/2 stream
     * id without trusting a client-supplied x-http2-stream-id header — a plain HTTP/1.1 client never
     * reaches switchToH2c, so it can never get this trusted signal set.
     */
    @Test
    public void shouldRecordHttp2AsNegotiatedProtocolForH2c() {
        boolean original = ConfigurationProperties.grpcBidiStreamingEnabled();
        try {
            ConfigurationProperties.grpcBidiStreamingEnabled(false);
            Configuration config = configuration();

            EmbeddedChannel channel = new EmbeddedChannel();
            channel.pipeline().addLast(new MockServerUnificationInitializer(
                config,
                mock(LifeCycle.class),
                new HttpState(config, new MockServerLogger(), mock(Scheduler.class)),
                mock(HttpActionHandler.class),
                null
            ));

            // Send the HTTP/2 cleartext preface — triggers switchToH2c
            channel.writeInbound(Unpooled.wrappedBuffer(H2C_PREFACE.getBytes(StandardCharsets.US_ASCII)));

            // The trusted, server-side negotiated protocol must now report HTTP/2 for this h2c channel
            assertThat("h2c channel should report HTTP/2 as its negotiated protocol",
                org.mockserver.socket.tls.SniHandler.getALPNProtocol(new MockServerLogger(), channel.pipeline().firstContext()),
                is(org.mockserver.model.Protocol.HTTP_2));

            channel.finishAndReleaseAll();
        } finally {
            ConfigurationProperties.grpcBidiStreamingEnabled(original);
        }
    }

    /**
     * Verifies that the default value of grpcBidiStreamingEnabled is false.
     * Belt-and-braces: even if the ConfigurationTest in mockserver-core covers this,
     * we verify here at the netty layer that the flag is indeed off by default.
     */
    @Test
    public void shouldDefaultToFlagOff() {
        // Clear any system property that might have been set
        String original = System.getProperty("mockserver.grpcBidiStreamingEnabled");
        try {
            System.clearProperty("mockserver.grpcBidiStreamingEnabled");
            assertThat("default should be false",
                ConfigurationProperties.grpcBidiStreamingEnabled(), is(false));
            assertThat("Configuration instance should fall back to false",
                configuration().grpcBidiStreamingEnabled(), is(false));
        } finally {
            if (original != null) {
                System.setProperty("mockserver.grpcBidiStreamingEnabled", original);
            }
        }
    }
}
