package org.mockserver.netty.proxy.relay;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.*;
import io.netty.util.ReferenceCountUtil;
import org.junit.After;
import org.junit.Test;
import org.mockserver.logging.MockServerLogger;

import java.nio.charset.StandardCharsets;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;

/**
 * After {@link DownstreamProxyRelayHandler} relays a {@code 101} to anything but HTTP/1.x to an HTTP/1.1 tunnel's
 * client leg, both legs pass bytes as they are, as the loopback's decoder already does.
 */
public class TunnelUpgradeTest {

    private final EmbeddedChannel proxyClient = new EmbeddedChannel(new HttpServerCodec(), new HttpObjectAggregator(1024));
    private final EmbeddedChannel loopback = new EmbeddedChannel();

    @After
    public void close() {
        proxyClient.finishAndReleaseAll();
        loopback.finishAndReleaseAll();
    }

    @Test
    public void shouldRecogniseASwitchToAnythingButHttp1AsNettysDecoderDoes() {
        assertThat(TunnelUpgrade.isUpgrade(switchingProtocols(true)), is(true));
        assertThat(TunnelUpgrade.isUpgrade(switchingProtocols(false)), is(true));
        FullHttpResponse toHttp11 = switchingProtocols(false);
        toHttp11.headers().set(HttpHeaderNames.UPGRADE, "HTTP/1.1");
        assertThat(TunnelUpgrade.isUpgrade(toHttp11), is(false));
        assertThat(TunnelUpgrade.isUpgrade(new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK)), is(false));
    }

    @Test
    public void shouldPassBytesBothWaysOnceAnUpgradeIsRelayed() {
        loopback.pipeline().addLast(new DownstreamProxyRelayHandler(new MockServerLogger(), proxyClient, 1024));
        clientSends("GET /ws HTTP/1.1\r\nHost: example\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nContent-Length: 0\r\n\r\n");
        ReferenceCountUtil.release(proxyClient.readInbound());

        loopback.writeInbound(switchingProtocols(true), LastHttpContent.EMPTY_LAST_CONTENT, Unpooled.copiedBuffer("server frame", StandardCharsets.ISO_8859_1));

        assertThat(proxyClient.pipeline().get(HttpServerCodec.class), is(nullValue()));
        assertThat(proxyClient.pipeline().get(HttpObjectAggregator.class), is(nullValue()));
        assertThat(clientReceived(), startsWith("HTTP/1.1 101 Switching Protocols\r\n"));
        assertThat(clientReceived(), is("server frame"));
        assertThat(clientReceived(), is(nullValue()));

        clientSends("client frame");
        ByteBuf relayed = loopback.readOutbound();
        assertThat(relayed.toString(StandardCharsets.ISO_8859_1), is("client frame"));
        relayed.release();
    }

    @Test
    public void shouldKeepCarryingHttpAfterASwitchToHttp1() {
        loopback.pipeline().addLast(new DownstreamProxyRelayHandler(new MockServerLogger(), proxyClient, 1024));
        FullHttpResponse toHttp11 = switchingProtocols(false);
        toHttp11.headers().set(HttpHeaderNames.UPGRADE, "HTTP/1.1");

        loopback.writeInbound(toHttp11);

        assertThat(proxyClient.pipeline().get(HttpServerCodec.class), is(notNullValue()));
        assertThat(clientReceived(), startsWith("HTTP/1.1 101 Switching Protocols\r\n"));
    }

    private static FullHttpResponse switchingProtocols(boolean withUpgrade) {
        FullHttpResponse response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.SWITCHING_PROTOCOLS);
        if (withUpgrade) {
            response.headers().set(HttpHeaderNames.UPGRADE, "websocket").set(HttpHeaderNames.CONNECTION, "Upgrade");
        }
        return response;
    }

    private void clientSends(String bytes) {
        proxyClient.writeInbound(Unpooled.copiedBuffer(bytes, StandardCharsets.ISO_8859_1));
    }

    /** The next bytes written to the client, past any empty buffer the encoder ends a message with. */
    private String clientReceived() {
        for (ByteBuf written; (written = proxyClient.readOutbound()) != null; ) {
            try {
                if (written.isReadable()) {
                    return written.toString(StandardCharsets.ISO_8859_1);
                }
            } finally {
                written.release();
            }
        }
        return null;
    }
}
