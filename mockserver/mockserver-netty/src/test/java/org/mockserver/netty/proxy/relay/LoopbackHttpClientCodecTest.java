package org.mockserver.netty.proxy.relay;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.DefaultFullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.util.ReferenceCountUtil;
import org.junit.After;
import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.is;

/**
 * The relay's codec decodes each response from MockServer for the method of the request it answers, and can be told
 * of a request MockServer answered with nothing it decodes.
 */
public class LoopbackHttpClientCodecTest {

    private static final String SIMPLE = "HTTP/1.1 200 OK\r\ncontent-length: 6\r\n\r\nsimple";
    private static final String NO_CONTENT = "HTTP/1.1 204 No Content\r\n\r\n";

    private final LoopbackHttpClientCodec codec = new LoopbackHttpClientCodec(8192);
    private final EmbeddedChannel loopback = new EmbeddedChannel(codec, new HttpObjectAggregator(1024));

    @After
    public void close() {
        loopback.finishAndReleaseAll();
    }

    @Test
    public void shouldDecodeAResponseToHeadWithoutItsBody() {
        request(HttpMethod.HEAD);
        request(HttpMethod.GET);

        respond("HTTP/1.1 200 OK\r\ncontent-length: 6\r\n\r\n" + SIMPLE);

        assertThat(decoded(), contains("200 OK: ", "200 OK: simple"));
    }

    @Test
    public void shouldDecodeTheNextResponseForItsOwnRequestWhenToldTheOldestWasNotAnsweredWithOne() {
        request(HttpMethod.HEAD);
        request(HttpMethod.GET);

        codec.responseNotDecoded();
        respond(SIMPLE);

        assertThat(decoded(), contains("200 OK: simple"));
    }

    @Test
    public void shouldDecodeAResponseToHeadWithoutItsBodyWhenToldAGetBeforeItWasNotAnsweredWithOne() {
        request(HttpMethod.GET);
        request(HttpMethod.HEAD);
        request(HttpMethod.GET);

        codec.responseNotDecoded();
        respond("HTTP/1.1 200 OK\r\ncontent-length: 6\r\n\r\n" + SIMPLE);

        assertThat(decoded(), contains("200 OK: ", "200 OK: simple"));
    }

    @Test
    public void shouldNotPairAnInterimResponseWithARequest() {
        request(HttpMethod.HEAD);
        request(HttpMethod.GET);

        respond("HTTP/1.1 103 Early Hints\r\n\r\nHTTP/1.1 200 OK\r\ncontent-length: 6\r\n\r\n" + SIMPLE);

        assertThat(decoded(), contains("103 Early Hints: ", "200 OK: ", "200 OK: simple"));
    }

    @Test
    public void shouldPassOnWhatFollowsASuccessfulConnectAsBytes() {
        request(HttpMethod.CONNECT);

        respond("HTTP/1.1 200 OK\r\n\r\n" + NO_CONTENT);

        List<Object> messages = new ArrayList<>();
        for (Object message; (message = loopback.readInbound()) != null; ) {
            messages.add(message);
        }
        assertThat(messages.size(), is(2));
        assertThat(((FullHttpResponse) messages.get(0)).status().code(), is(200));
        assertThat(((ByteBuf) messages.get(1)).toString(StandardCharsets.US_ASCII), is(NO_CONTENT));
        messages.forEach(ReferenceCountUtil::release);
    }

    @Test
    public void shouldPassOnWhatFollowsASwitchingProtocolsResponseAsBytesInBothDirections() {
        request(HttpMethod.GET);
        String frame = "\u0081\u0005hello";

        respond("HTTP/1.1 101 Switching Protocols\r\nupgrade: websocket\r\nconnection: upgrade\r\nsec-websocket-accept: s3pPLMBiTxaQ9kYGzzhZRbK+xOo=\r\n\r\n" + frame);

        List<Object> messages = new ArrayList<>();
        for (Object message; (message = loopback.readInbound()) != null; ) {
            messages.add(message);
        }
        assertThat(messages.size(), is(2));
        assertThat(((FullHttpResponse) messages.get(0)).status().code(), is(101));
        assertThat(((ByteBuf) messages.get(1)).toString(StandardCharsets.ISO_8859_1), is(frame));
        messages.forEach(ReferenceCountUtil::release);

        // the client's frames are written to MockServer as they are, not refused by the request encoder
        loopback.writeOutbound(Unpooled.copiedBuffer("\u0081\u0085mask!", StandardCharsets.ISO_8859_1));
        ByteBuf written = loopback.readOutbound();
        assertThat(written.toString(StandardCharsets.ISO_8859_1), is("\u0081\u0085mask!"));
        written.release();
    }

    private void request(HttpMethod method) {
        loopback.writeOutbound(new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, method, "/"));
        for (Object encoded; (encoded = loopback.readOutbound()) != null; ) {
            ReferenceCountUtil.release(encoded);
        }
    }

    private void respond(String bytes) {
        loopback.writeInbound(Unpooled.copiedBuffer(bytes, StandardCharsets.US_ASCII));
    }

    private List<String> decoded() {
        List<String> decoded = new ArrayList<>();
        for (Object message; (message = loopback.readInbound()) != null; ReferenceCountUtil.release(message)) {
            FullHttpResponse response = (FullHttpResponse) message;
            assertThat(response.decoderResult().isSuccess(), is(true));
            decoded.add(response.status() + ": " + response.content().toString(StandardCharsets.US_ASCII));
        }
        return decoded;
    }
}
