package org.mockserver.netty.connection;

import io.netty.channel.Channel;
import io.netty.channel.DefaultChannelId;
import io.netty.channel.DefaultEventLoopGroup;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.channel.local.LocalChannel;
import io.netty.handler.codec.http2.DefaultHttp2Connection;
import io.netty.handler.codec.http2.Http2Connection;
import io.netty.handler.codec.http2.Http2Exception;
import io.netty.handler.codec.http2.Http2FrameStream;
import io.netty.handler.codec.http2.Http2Stream;
import io.netty.handler.codec.http2.Http2StreamChannel;
import io.netty.handler.codec.quic.QuicChannel;
import io.netty.util.AttributeKey;
import io.netty.util.DefaultAttributeMap;
import org.junit.Test;
import org.mockserver.model.StreamingBody;
import org.mockserver.socket.ConnectionClosing;

import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.core.Is.is;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class ServerClosedChannelsTest {

    @Test
    public void shouldNotTreatAnUnmarkedChannelAsClosedByTheServer() {
        assertThat(ServerClosedChannels.isClosedByServer(new EmbeddedChannel()), is(false));
    }

    @Test
    public void shouldTreatAMarkedChannelAsClosedByTheServer() {
        EmbeddedChannel channel = new EmbeddedChannel();

        ServerClosedChannels.markClosed(channel);

        assertThat(ServerClosedChannels.isClosedByServer(channel), is(true));
    }

    private static Http2StreamChannel http2Stream(Channel connection, int streamId) {
        Http2FrameStream frameStream = mock(Http2FrameStream.class);
        when(frameStream.id()).thenReturn(streamId);
        Http2StreamChannel stream = mock(Http2StreamChannel.class);
        when(stream.parent()).thenReturn(connection);
        when(stream.stream()).thenReturn(frameStream);
        return stream;
    }

    @Test
    public void shouldTreatAnHttp2StreamTheServerResetAsClosedByTheServerOnce() {
        EmbeddedChannel connection = new EmbeddedChannel();
        Http2StreamChannel stream = http2Stream(connection, 3);

        ServerClosedChannels.markHttp2StreamReset(connection, 3);

        assertThat(ServerClosedChannels.isClosedByServer(stream), is(true));
        assertThat(ServerClosedChannels.isClosedByServer(stream), is(false));
    }

    @Test
    public void shouldNotTreatAnotherHttp2StreamOfTheConnectionAsClosedByTheServer() {
        EmbeddedChannel connection = new EmbeddedChannel();

        ServerClosedChannels.markHttp2StreamReset(connection, 3);

        assertThat(ServerClosedChannels.isClosedByServer(http2Stream(connection, 5)), is(false));
    }

    @Test
    public void shouldTreatAnHttp2StreamOfAMarkedConnectionAsClosedByTheServer() {
        EmbeddedChannel connection = new EmbeddedChannel();
        ServerClosedChannels.markClosed(connection);

        assertThat(ServerClosedChannels.isClosedByServer(http2Stream(connection, 7)), is(true));
    }

    @Test
    public void shouldTreatAChannelWhoseAncestorIsMarkedAsClosedByTheServer() {
        EmbeddedChannel listener = new EmbeddedChannel();
        EmbeddedChannel connection = new EmbeddedChannel(listener, DefaultChannelId.newInstance(), true, false);
        EmbeddedChannel stream = new EmbeddedChannel(connection, DefaultChannelId.newInstance(), true, false);
        assertThat(ServerClosedChannels.isClosedByServer(stream), is(false));

        ServerClosedChannels.markClosed(listener);

        assertThat(ServerClosedChannels.isClosedByServer(stream), is(true));
    }

    @Test
    public void shouldTreatAConnectionMarkedClosingAsClosedByTheServer() {
        EmbeddedChannel connection = new EmbeddedChannel();

        ConnectionClosing.mark(connection);

        assertThat(ServerClosedChannels.isClosedByServer(connection), is(true));
    }

    @Test
    public void shouldTreatAStreamOfAConnectionWithATransportErrorAsClosedByTheServerOnceTheConnectionHasClosed() {
        EmbeddedChannel connection = new EmbeddedChannel();
        EmbeddedChannel stream = new EmbeddedChannel(connection, DefaultChannelId.newInstance(), true, false);

        ServerClosedChannels.markTransportError(connection);

        assertThat("a stream the client ends as the connection stays open", ServerClosedChannels.isClosedByServer(stream), is(false));
        assertThat(ServerClosedChannels.isClosedByServer(connection), is(false));

        // as QUIC closes it, outside the pipeline
        connection.unsafe().close(connection.voidPromise());

        assertThat(ServerClosedChannels.isClosedByServer(connection), is(true));
        assertThat(ServerClosedChannels.isClosedByServer(stream), is(true));
    }

    @Test
    public void shouldNotTreatAConnectionWhoseTransportErrorWasClearedAsClosedByTheServer() {
        EmbeddedChannel connection = new EmbeddedChannel();
        EmbeddedChannel unmarked = new EmbeddedChannel();
        ServerClosedChannels.markTransportError(connection);

        ServerClosedChannels.clearTransportError(connection);
        ServerClosedChannels.clearTransportError(unmarked);
        connection.unsafe().close(connection.voidPromise());

        assertThat(ServerClosedChannels.isClosedByServer(connection), is(false));
        assertThat(unmarked.hasAttr(AttributeKey.valueOf("mockserver.transportError")), is(false));
    }

    @Test
    public void shouldNotTreatAQuicConnectionThatTimedOutAfterATransportErrorAsClosedByTheServer() {
        QuicChannel timedOut = closedQuicConnection(true);
        QuicChannel notTimedOut = closedQuicConnection(false);

        ServerClosedChannels.markTransportError(timedOut);
        ServerClosedChannels.markTransportError(notTimedOut);

        assertThat(ServerClosedChannels.isClosedByServer(timedOut), is(false));
        assertThat(ServerClosedChannels.isClosedByServer(notTimedOut), is(true));
    }

    @Test
    public void shouldNotTreatAClosedConnectionWithoutATransportErrorAsClosedByTheServer() {
        EmbeddedChannel connection = new EmbeddedChannel();

        connection.unsafe().close(connection.voidPromise());

        assertThat(ServerClosedChannels.isClosedByServer(connection), is(false));
        assertThat(ServerClosedChannels.isClosedByServer(closedQuicConnection(false)), is(false));
    }

    private static QuicChannel closedQuicConnection(boolean timedOut) {
        DefaultAttributeMap attributes = new DefaultAttributeMap();
        QuicChannel connection = mock(QuicChannel.class);
        when(connection.attr(any())).thenAnswer(invocation -> attributes.attr(invocation.getArgument(0)));
        when(connection.hasAttr(any())).thenAnswer(invocation -> attributes.hasAttr(invocation.getArgument(0)));
        when(connection.isOpen()).thenReturn(false);
        when(connection.isTimedOut()).thenReturn(timedOut);
        return connection;
    }

    @Test
    public void shouldTreatAChannelOnAnEventLoopThatIsShuttingDownAsClosedByTheServer() throws Exception {
        DefaultEventLoopGroup group = new DefaultEventLoopGroup(1);
        try {
            Channel channel = new LocalChannel();
            group.register(channel).sync();
            assertThat(ServerClosedChannels.isClosedByServer(channel), is(false));

            group.shutdownGracefully(0, 0, TimeUnit.MILLISECONDS);

            assertThat(ServerClosedChannels.isClosedByServer(channel), is(true));
        } finally {
            group.shutdownGracefully(0, 0, TimeUnit.MILLISECONDS).sync();
        }
    }

    @Test
    public void shouldNoteTheClientWhenTheUpstreamIsClosedForAnUnmarkedChannel() {
        StreamingBody body = new StreamingBody(1024);
        AtomicInteger closed = new AtomicInteger();
        body.setUpstreamCloser(closed::incrementAndGet);

        ServerClosedChannels.closeUpstream(new EmbeddedChannel(), body);

        assertThat(closed.get(), is(1));
        assertThat(body.isClosedByClient(), is(true));
    }

    @Test
    public void shouldNotNoteTheClientWhenTheUpstreamIsClosedForAMarkedChannel() {
        StreamingBody body = new StreamingBody(1024);
        AtomicInteger closed = new AtomicInteger();
        body.setUpstreamCloser(closed::incrementAndGet);
        EmbeddedChannel channel = new EmbeddedChannel();
        ServerClosedChannels.markClosed(channel);

        ServerClosedChannels.closeUpstream(channel, body);

        assertThat(closed.get(), is(1));
        assertThat(body.isClosedByClient(), is(false));
    }

    @Test
    public void shouldCreateNoAttributeWhenAskedAboutAnUnmarkedChannel() {
        EmbeddedChannel listener = new EmbeddedChannel();
        EmbeddedChannel connection = new EmbeddedChannel(listener, DefaultChannelId.newInstance(), true, false);
        EmbeddedChannel stream = new EmbeddedChannel(connection, DefaultChannelId.newInstance(), true, false);

        assertThat(ServerClosedChannels.isClosedByServer(stream), is(false));
        assertThat(ServerClosedChannels.isClosedByServer(http2Stream(connection, 3)), is(false));
        assertThat(ServerClosedChannels.consumeHttp2StreamReset(connection, 3), is(false));

        for (Channel channel : new Channel[]{listener, connection, stream}) {
            for (String key : new String[]{"mockserver.closedByServer", "mockserver.resetHttp2Streams", "mockserver.relayedResetHttp2Streams", "mockserver.connectionClosing", "mockserver.relayProxyClient", "mockserver.transportError"}) {
                assertThat(key, channel.hasAttr(AttributeKey.valueOf(key)), is(false));
            }
        }
    }

    private static Http2Stream openStream(Http2Connection connection, int streamId) throws Http2Exception {
        return connection.remote().createStream(streamId, false);
    }

    @Test
    public void shouldForgetAnHttp2StreamResetOnceTheStreamHasClosed() throws Exception {
        EmbeddedChannel channel = new EmbeddedChannel();
        Http2Connection connection = new DefaultHttp2Connection(true);
        ServerClosedChannels.forgetHttp2StreamResetsOnClose(channel, connection);
        Http2Stream reset = openStream(connection, 3);
        openStream(connection, 5);
        ServerClosedChannels.markHttp2StreamReset(channel, 3);
        ServerClosedChannels.markHttp2StreamReset(channel, 5);

        reset.close();
        channel.runPendingTasks();

        assertThat("a closed stream's reset is no longer held", ServerClosedChannels.isClosedByServer(http2Stream(channel, 3)), is(false));
        assertThat("an open stream's reset is still held", ServerClosedChannels.isClosedByServer(http2Stream(channel, 5)), is(true));
    }

    @Test
    public void shouldStillTellAnHttp2StreamResetJustAfterTheStreamHasClosed() throws Exception {
        EmbeddedChannel channel = new EmbeddedChannel();
        Http2Connection connection = new DefaultHttp2Connection(true);
        ServerClosedChannels.forgetHttp2StreamResetsOnClose(channel, connection);
        Http2Stream reset = openStream(connection, 3);
        ServerClosedChannels.markHttp2StreamReset(channel, 3);

        // the stream's own channel closes after its stream, in the same turn of the event loop
        reset.close();

        assertThat(ServerClosedChannels.isClosedByServer(http2Stream(channel, 3)), is(true));
    }

    @Test
    public void shouldForgetARelayedHttp2StreamResetOnceTheLoopbackStreamHasClosed() throws Exception {
        EmbeddedChannel proxyClient = new EmbeddedChannel();
        EmbeddedChannel accepted = new EmbeddedChannel();
        TunnelProxyClient.remember(accepted, proxyClient);
        Http2Connection connection = new DefaultHttp2Connection(true);
        ServerClosedChannels.forgetRelayedHttp2StreamResetsOnClose(accepted, connection);
        Http2Stream reset = openStream(connection, 3);
        openStream(connection, 5);
        ServerClosedChannels.markRelayedHttp2StreamReset(proxyClient, 3);
        ServerClosedChannels.markRelayedHttp2StreamReset(proxyClient, 5);

        reset.close();
        accepted.runPendingTasks();

        assertThat(ServerClosedChannels.isClosedByServer(http2Stream(accepted, 3)), is(false));
        assertThat(ServerClosedChannels.isClosedByServer(http2Stream(accepted, 5)), is(true));
    }

    @Test
    public void shouldForgetARelayedHttp2StreamResetMarkedOnlyOnceTheLoopbackStreamHadClosed() throws Exception {
        EmbeddedChannel proxyClient = new EmbeddedChannel();
        EmbeddedChannel accepted = new EmbeddedChannel();
        TunnelProxyClient.remember(accepted, proxyClient);
        Http2Connection connection = new DefaultHttp2Connection(true);
        ServerClosedChannels.forgetRelayedHttp2StreamResetsOnClose(accepted, connection);
        // its whole response sent, before the relay, yet to read it, resets it
        openStream(connection, 3).close();
        accepted.runPendingTasks();
        Http2Stream next = openStream(connection, 5);
        openStream(connection, 7);
        ServerClosedChannels.markRelayedHttp2StreamReset(proxyClient, 3);
        ServerClosedChannels.markRelayedHttp2StreamReset(proxyClient, 7);
        // its HEADERS not yet read here
        ServerClosedChannels.markRelayedHttp2StreamReset(proxyClient, 9);

        next.close();
        accepted.runPendingTasks();

        Set<Integer> held = proxyClient.attr(AttributeKey.<Set<Integer>>valueOf("mockserver.relayedResetHttp2Streams")).get();
        assertThat(held, containsInAnyOrder(7, 9));
    }
}
