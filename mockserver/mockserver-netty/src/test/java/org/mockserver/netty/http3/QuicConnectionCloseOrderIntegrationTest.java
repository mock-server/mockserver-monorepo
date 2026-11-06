package org.mockserver.netty.http3;

import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.handler.codec.http3.DefaultHttp3Headers;
import io.netty.handler.codec.quic.Quic;
import io.netty.handler.codec.quic.QuicChannel;
import io.netty.handler.codec.quic.QuicStreamChannel;
import org.junit.AfterClass;
import org.junit.Assume;
import org.junit.BeforeClass;
import org.junit.Test;
import org.mockserver.netty.MockServer;
import org.mockserver.netty.connection.ServerClosedChannels;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.mockserver.configuration.Configuration.configuration;
import static org.mockserver.netty.http3.Http3TestServer.startWithHttp3;
import static org.mockserver.stop.Stop.stopQuietly;

/**
 * {@link ServerClosedChannels} takes a QUIC connection that reported a transport error as closed by MockServer once
 * it has closed, unless it timed out, which relies on Netty's QUIC channel reporting itself closed, and timed out when
 * it did, before its streams' close listeners run. This checks that on Netty's channel itself: on a client's
 * connection, of the same class as the server's, as nothing a client sends through quiche makes the server's QUIC find
 * a transport error. QUIC-gated.
 */
@SuppressWarnings("deprecation") // NioEventLoopGroup deprecation in Netty 4.2
public class QuicConnectionCloseOrderIntegrationTest {

    private static NioEventLoopGroup clientGroup;
    private static MockServer mockServer;

    @BeforeClass
    public static void startServer() {
        try {
            Assume.assumeTrue("native QUIC transport not available on this platform", Quic.isAvailable());
        } catch (Throwable t) {
            Assume.assumeNoException("native QUIC transport failed to load", t);
        }
        clientGroup = new NioEventLoopGroup(1);
        mockServer = startWithHttp3(configuration().http3MaxIdleTimeout(30000L));
    }

    @AfterClass
    public static void stopServer() {
        stopQuietly(mockServer);
        if (clientGroup != null) {
            clientGroup.shutdownGracefully(0, 1, TimeUnit.SECONDS);
        }
    }

    @Test(timeout = 30000)
    public void shouldTellAStreamsCloseListenerThatAConnectionQuicClosedAfterATransportErrorWasClosedByTheServer() throws Exception {
        try (Http3TestClient client = Http3TestClient.open(clientGroup, mockServer)) {
            QuicStreamChannel stream = openRequestStream(client);
            CompletableFuture<Boolean> closedByServer = classifiedOnClose(stream);
            QuicChannel connection = client.quicChannel();
            ServerClosedChannels.markTransportError(connection);
            assertThat("while the connection is open", ServerClosedChannels.isClosedByServer(stream), is(false));

            // as QUIC closes a connection once its CONNECTION_CLOSE for a transport error has gone
            connection.eventLoop().execute(() -> connection.unsafe().close(connection.voidPromise()));

            assertThat(closedByServer.get(10, TimeUnit.SECONDS), is(true));
        }
    }

    @Test(timeout = 30000)
    public void shouldTellAStreamsCloseListenerThatAConnectionQuicClosedWithoutATransportErrorWasNotClosedByTheServer() throws Exception {
        try (Http3TestClient client = Http3TestClient.open(clientGroup, mockServer)) {
            QuicStreamChannel stream = openRequestStream(client);
            CompletableFuture<Boolean> closedByServer = classifiedOnClose(stream);
            QuicChannel connection = client.quicChannel();

            connection.eventLoop().execute(() -> connection.unsafe().close(connection.voidPromise()));

            assertThat(closedByServer.get(10, TimeUnit.SECONDS), is(false));
        }
    }

    @Test(timeout = 30000)
    public void shouldTellAStreamsCloseListenerThatAConnectionThatTimedOutAfterATransportErrorWasNotClosedByTheServer() throws Exception {
        try (Http3TestClient client = Http3TestClient.open(clientGroup, mockServer, 1000)) {
            QuicStreamChannel stream = openRequestStream(client);
            CompletableFuture<Boolean> closedByServer = classifiedOnClose(stream);
            QuicChannel connection = client.quicChannel();
            ServerClosedChannels.markTransportError(connection);

            // nothing is sent on the connection, so its idle timeout closes it

            assertThat(closedByServer.get(10, TimeUnit.SECONDS), is(false));
            assertThat(connection.isTimedOut(), is(true));
        }
    }

    private static QuicStreamChannel openRequestStream(Http3TestClient client) throws Exception {
        // a request whose header section never ends, so the server leaves the stream open
        QuicStreamChannel stream = client.start(new DefaultHttp3Headers()
            .method("POST").scheme("https").authority("localhost:" + mockServer.getHttp3Port()).path("/never-ends")).stream();
        assertThat(stream.isOpen(), is(true));
        return stream;
    }

    private static CompletableFuture<Boolean> classifiedOnClose(QuicStreamChannel stream) {
        CompletableFuture<Boolean> closedByServer = new CompletableFuture<>();
        stream.closeFuture().addListener(future -> closedByServer.complete(ServerClosedChannels.isClosedByServer(stream)));
        return closedByServer;
    }
}
