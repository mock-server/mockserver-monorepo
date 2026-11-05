package org.mockserver.netty.proxy.relay;

import io.netty.bootstrap.Bootstrap;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.DefaultEventLoopGroup;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.channel.local.LocalAddress;
import io.netty.channel.local.LocalChannel;
import io.netty.channel.local.LocalServerChannel;
import io.netty.handler.codec.http2.Http2FrameStream;
import io.netty.handler.codec.http2.Http2StreamChannel;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockserver.netty.connection.ServerClosedChannels;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.core.Is.is;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class RelayLoopbackServerCloseTest {

    private DefaultEventLoopGroup group;
    private Channel serverChannel;
    private Channel loopbackClient;
    private Channel accepted;
    private EmbeddedChannel proxyClient;

    @Before
    public void openTunnel() throws Exception {
        group = new DefaultEventLoopGroup(1);
        CompletableFuture<Channel> acceptedFuture = new CompletableFuture<>();
        LocalAddress address = new LocalAddress("relay-loopback-" + System.nanoTime());
        serverChannel = new ServerBootstrap()
            .group(group)
            .channel(LocalServerChannel.class)
            .childHandler(new ChannelInitializer<LocalChannel>() {
                @Override
                protected void initChannel(LocalChannel channel) {
                    acceptedFuture.complete(channel);
                }
            })
            .bind(address).sync().channel();
        loopbackClient = new Bootstrap()
            .group(group)
            .channel(LocalChannel.class)
            .handler(new ChannelInitializer<LocalChannel>() {
                @Override
                protected void initChannel(LocalChannel channel) {
                }
            })
            .connect(address).sync().channel();
        accepted = acceptedFuture.get(5, TimeUnit.SECONDS);
        proxyClient = new EmbeddedChannel();
        RelayLoopbackAddresses.register(loopbackClient, proxyClient);
        RelayLoopbackAddresses.rememberProxyClient(accepted);
    }

    @After
    public void closeTunnel() throws Exception {
        loopbackClient.close().sync();
        serverChannel.close().sync();
        group.shutdownGracefully(0, 0, TimeUnit.MILLISECONDS).sync();
    }

    private Http2StreamChannel http2StreamOfLoopback(int streamId) {
        Http2FrameStream frameStream = mock(Http2FrameStream.class);
        when(frameStream.id()).thenReturn(streamId);
        Http2StreamChannel stream = mock(Http2StreamChannel.class);
        when(stream.parent()).thenReturn(accepted);
        when(stream.stream()).thenReturn(frameStream);
        return stream;
    }

    @Test
    public void shouldNotTreatTheLoopbackOfAnUnmarkedTunnelAsClosedByTheServer() {
        assertThat(ServerClosedChannels.isClosedByServer(accepted), is(false));
        assertThat(ServerClosedChannels.isClosedByServer(http2StreamOfLoopback(3)), is(false));
    }

    @Test
    public void shouldTreatTheLoopbackOfATunnelWhoseClientLegMockServerClosedAsClosedByTheServer() {
        ServerClosedChannels.markClosed(proxyClient);

        assertThat(ServerClosedChannels.isClosedByServer(accepted), is(true));
        assertThat(ServerClosedChannels.isClosedByServer(http2StreamOfLoopback(3)), is(true));
    }

    @Test
    public void shouldKeepTreatingTheLoopbackAsClosedByTheServerAfterTheRelaysEndOfItHasClosed() throws Exception {
        ServerClosedChannels.markClosed(proxyClient);
        loopbackClient.close().sync();

        assertThat(RelayLoopbackAddresses.proxyClientOf(accepted), is((Channel) null));
        assertThat(ServerClosedChannels.isClosedByServer(accepted), is(true));
        assertThat(ServerClosedChannels.isClosedByServer(http2StreamOfLoopback(3)), is(true));
    }

    @Test
    public void shouldTreatALoopbackStreamTheRelayResetForAClientStreamMockServerResetAsClosedByTheServerOnce() {
        ServerClosedChannels.markRelayedHttp2StreamReset(proxyClient, 5);

        assertThat(ServerClosedChannels.isClosedByServer(http2StreamOfLoopback(5)), is(true));
        assertThat(ServerClosedChannels.isClosedByServer(http2StreamOfLoopback(5)), is(false));
        assertThat(ServerClosedChannels.isClosedByServer(http2StreamOfLoopback(7)), is(false));
    }
}
