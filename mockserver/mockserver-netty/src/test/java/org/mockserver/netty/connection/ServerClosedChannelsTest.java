package org.mockserver.netty.connection;

import io.netty.channel.Channel;
import io.netty.channel.DefaultEventLoopGroup;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.channel.local.LocalChannel;
import io.netty.handler.codec.http2.Http2FrameStream;
import io.netty.handler.codec.http2.Http2StreamChannel;
import org.junit.Test;
import org.mockserver.model.StreamingBody;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.core.Is.is;
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
}
