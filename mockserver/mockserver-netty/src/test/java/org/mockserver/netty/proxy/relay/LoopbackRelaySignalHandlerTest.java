package org.mockserver.netty.proxy.relay;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.channel.local.LocalAddress;
import io.netty.handler.codec.http.DefaultFullHttpRequest;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpServerCodec;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.util.ReferenceCountUtil;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockserver.netty.unification.HttpServerCodecResponsePairing;
import org.mockserver.responsewriter.HttpExchangeEndedEvent;
import org.mockserver.responsewriter.RawResponseBytesEvent;

import java.net.SocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.is;

/**
 * MockServer's side of a relay loopback tells the tunnel's relay where an exchange ends with nothing the relay's codec
 * decodes as its response, and of no other.
 */
public class LoopbackRelaySignalHandlerTest {

    // unique: the relay's loopback addresses are held JVM-wide and tests run in parallel
    private final SocketAddress loopbackAddress = new LocalAddress("loopback-" + UUID.randomUUID());
    private final SocketAddress listenerAddress = new LocalAddress("listener-" + UUID.randomUUID());
    private final List<Object> seenOnClientLegAfterCodec = new ArrayList<>();
    private final List<Object> seenOnLoopbackAfterHandler = new ArrayList<>();
    private EmbeddedChannel proxyClient;
    private EmbeddedChannel relayLoopback;
    private EmbeddedChannel acceptedLoopback;

    @Before
    public void openTunnel() {
        proxyClient = new EmbeddedChannel(new HttpServerCodec(), recorder(seenOnClientLegAfterCodec));
        relayLoopback = new EmbeddedChannel() {
            @Override
            protected SocketAddress localAddress0() {
                return loopbackAddress;
            }

            @Override
            protected SocketAddress remoteAddress0() {
                return listenerAddress;
            }
        };
        acceptedLoopback = acceptedFrom(loopbackAddress);
        RelayLoopbackAddresses.register(relayLoopback, proxyClient);
    }

    @After
    public void closeTunnel() {
        acceptedLoopback.finishAndReleaseAll();
        relayLoopback.finishAndReleaseAll();
        proxyClient.finishAndReleaseAll();
    }

    @Test
    public void shouldAnnounceAnExchangeEndedWithNoResponseToTheRelayAtItsPlaceAmongTheBytesWritten() {
        List<Object> relayed = new ArrayList<>();
        LoopbackHttpClientCodec relayCodec = new LoopbackHttpClientCodec(8192);
        relayLoopback.pipeline().addLast(LoopbackRawResponseSplitter.forTunnel(proxyClient, relayCodec), relayCodec, new ChannelInboundHandlerAdapter() {
            @Override
            public void channelRead(ChannelHandlerContext ctx, Object msg) {
                relayed.add(msg instanceof RawResponseBytes ? msg.toString() : msg.getClass().getSimpleName());
                ReferenceCountUtil.release(msg);
            }
        });
        HttpServerCodecResponsePairing pairing = new HttpServerCodecResponsePairing();
        EmbeddedChannel accepted = new EmbeddedChannel(pairing.beforeCodec(), new HttpServerCodec(), pairing.afterCodec(), LoopbackRelaySignalHandler.INSTANCE) {
            @Override
            protected SocketAddress localAddress0() {
                return listenerAddress;
            }

            @Override
            protected SocketAddress remoteAddress0() {
                return loopbackAddress;
            }
        };
        relayLoopback.writeOutbound(new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.HEAD, "/"));
        relayLoopback.writeOutbound(new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, "/"));
        for (ByteBuf request; (request = relayLoopback.readOutbound()) != null; ) {
            accepted.writeInbound(request);
        }
        for (Object decoded; (decoded = accepted.readInbound()) != null; ) {
            ReferenceCountUtil.release(decoded);
        }

        HttpExchangeEndedEvent.fire(accepted.pipeline().lastContext());
        FullHttpResponse response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK, Unpooled.copiedBuffer("simple", StandardCharsets.US_ASCII));
        response.headers().set(HttpHeaderNames.CONTENT_LENGTH, 6);
        accepted.writeOutbound(response);
        for (ByteBuf written; (written = accepted.readOutbound()) != null; ) {
            relayLoopback.writeInbound(written);
        }
        relayLoopback.runPendingTasks();
        proxyClient.runPendingTasks();

        assertThat("not told out of band: the relay ends the client leg's exchange at its place", seenOnClientLegAfterCodec, is(empty()));
        assertThat("the HEAD's exchange ends, then the GET's response is decoded with its body", relayed, contains("raw response bytes (0 bytes, the last)", "DefaultHttpResponse", "DefaultLastHttpContent"));
        accepted.finishAndReleaseAll();
    }

    @Test
    public void shouldTellTheClientLegOfAnExchangeEndedWithNoResponseWhenTheTunnelHasNoSplitter() {
        acceptedLoopback.pipeline().fireUserEventTriggered(HttpExchangeEndedEvent.INSTANCE);

        assertThat("not on the loopback's thread: the client leg's exchange count is confined to its own event loop", seenOnClientLegAfterCodec, is(empty()));
        proxyClient.runPendingTasks();

        assertThat("fired from the client leg's codec, so the handlers after it end their oldest exchange", seenOnClientLegAfterCodec, contains((Object) HttpExchangeEndedEvent.INSTANCE));
        assertThat("and still seen by the loopback's own handlers", seenOnLoopbackAfterHandler, contains((Object) HttpExchangeEndedEvent.INSTANCE));
    }

    @Test
    public void shouldNotTellTheClientLegOfARawBytesResponseWhichTheRelayEndsWhenItHasRelayedItsBytes() {
        acceptedLoopback.pipeline().fireUserEventTriggered(HttpExchangeEndedEvent.RAW_RESPONSE_WRITTEN);
        proxyClient.runPendingTasks();

        assertThat(seenOnClientLegAfterCodec, is(empty()));
        assertThat(seenOnLoopbackAfterHandler, contains((Object) HttpExchangeEndedEvent.RAW_RESPONSE_WRITTEN));
    }

    @Test
    public void shouldCountWhatIsWrittenFromBeneathTheCodecButNotItsStandInResponses() {
        HttpServerCodecResponsePairing pairing = new HttpServerCodecResponsePairing();
        EmbeddedChannel accepted = new EmbeddedChannel(new ChannelInboundHandlerAdapter(), pairing.beforeCodec(), new HttpServerCodec(), pairing.afterCodec(), LoopbackRelaySignalHandler.INSTANCE) {
            @Override
            protected SocketAddress localAddress0() {
                return listenerAddress;
            }

            @Override
            protected SocketAddress remoteAddress0() {
                return loopbackAddress;
            }
        };
        ChannelHandlerContext beforeCodec = accepted.pipeline().context(pairing.beforeCodec());
        ChannelHandlerContext counter = accepted.pipeline().context(LoopbackWrittenBytes.class);
        List<String> names = accepted.pipeline().names();
        assertThat(names.indexOf(counter.name()), is(names.indexOf(beforeCodec.name()) - 1));
        accepted.writeInbound(Unpooled.copiedBuffer("GET / HTTP/1.1\r\n\r\n", StandardCharsets.US_ASCII));
        for (Object decoded; (decoded = accepted.readInbound()) != null; ) {
            ReferenceCountUtil.release(decoded);
        }

        HttpExchangeEndedEvent.fire(accepted.pipeline().lastContext());

        long written = 0;
        for (ByteBuf buffer; (buffer = accepted.readOutbound()) != null; buffer.release()) {
            written += buffer.readableBytes();
        }
        assertThat("the stand-in response's bytes never reach the socket", written, is(0L));
        assertThat(((LoopbackWrittenBytes) counter.handler()).count(), is(0L));
        accepted.finishAndReleaseAll();
    }

    @Test
    public void shouldCountWhatIsWrittenFromImmediatelyBeforeTheLoopbacksCodec() {
        EmbeddedChannel accepted = new EmbeddedChannel(new ChannelInboundHandlerAdapter(), new HttpServerCodec(), LoopbackRelaySignalHandler.INSTANCE) {
            @Override
            protected SocketAddress localAddress0() {
                return listenerAddress;
            }

            @Override
            protected SocketAddress remoteAddress0() {
                return loopbackAddress;
            }
        };
        ChannelHandlerContext codec = accepted.pipeline().context(HttpServerCodec.class);
        ChannelHandlerContext counter = accepted.pipeline().context(LoopbackWrittenBytes.class);
        List<String> names = accepted.pipeline().names();
        assertThat("raw bytes are written from the codec's context, and must be counted in the order they are written", names.indexOf(counter.name()), is(names.indexOf(codec.name()) - 1));

        codec.writeAndFlush(Unpooled.wrappedBuffer(new byte[7]));
        accepted.writeOutbound(new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK));

        long written = 0;
        for (ByteBuf buffer; (buffer = accepted.readOutbound()) != null; buffer.release()) {
            written += buffer.readableBytes();
        }
        assertThat(((LoopbackWrittenBytes) counter.handler()).count(), is(written));
        accepted.finishAndReleaseAll();
    }

    @Test
    public void shouldNotAnnounceRawBytesOfAConnectionThatIsNoRelaysLoopback() {
        EmbeddedChannel direct = acceptedFrom(new LocalAddress("client-" + UUID.randomUUID()));

        direct.pipeline().fireUserEventTriggered(new RawResponseBytesEvent(7));

        assertThat(seenOnLoopbackAfterHandler.size(), is(1));
        direct.finishAndReleaseAll();
    }

    @Test
    public void shouldPassOnTheEventOfAConnectionThatIsNoRelaysLoopback() {
        EmbeddedChannel direct = acceptedFrom(new LocalAddress("client-" + UUID.randomUUID()));

        direct.pipeline().fireUserEventTriggered(HttpExchangeEndedEvent.INSTANCE);
        proxyClient.runPendingTasks();

        assertThat(seenOnClientLegAfterCodec, is(empty()));
        assertThat(seenOnLoopbackAfterHandler, contains((Object) HttpExchangeEndedEvent.INSTANCE));
        direct.finishAndReleaseAll();
    }

    @Test
    public void shouldForgetTheTunnelWhenItsLoopbackCloses() {
        assertThat(RelayLoopbackAddresses.isRelayLoopback(acceptedLoopback), is(true));

        relayLoopback.close();

        assertThat(RelayLoopbackAddresses.isRelayLoopback(acceptedLoopback), is(false));
        acceptedLoopback.pipeline().fireUserEventTriggered(HttpExchangeEndedEvent.INSTANCE);
        proxyClient.runPendingTasks();
        assertThat(seenOnClientLegAfterCodec, is(empty()));
    }

    private EmbeddedChannel acceptedFrom(SocketAddress remoteAddress) {
        return new EmbeddedChannel(LoopbackRelaySignalHandler.INSTANCE, recorder(seenOnLoopbackAfterHandler)) {
            @Override
            protected SocketAddress localAddress0() {
                return listenerAddress;
            }

            @Override
            protected SocketAddress remoteAddress0() {
                return remoteAddress;
            }
        };
    }

    private static ChannelInboundHandlerAdapter recorder(List<Object> seen) {
        return new ChannelInboundHandlerAdapter() {
            @Override
            public void userEventTriggered(ChannelHandlerContext ctx, Object evt) {
                seen.add(evt);
            }
        };
    }
}
