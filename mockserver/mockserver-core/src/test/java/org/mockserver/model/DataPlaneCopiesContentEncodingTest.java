package org.mockserver.model;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.compression.SnappyFrameDecoder;
import io.netty.handler.codec.compression.SnappyFrameEncoder;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.HttpServerCodec;
import org.junit.Test;
import org.mockserver.codec.BodyContentEncodingEncoder;
import org.mockserver.codec.HttpObjectAggregators;
import org.mockserver.codec.MockServerHttpContentDecompressor;
import org.mockserver.codec.NettyHttpToMockServerHttpRequestDecoder;
import org.mockserver.codec.PreserveHeadersNettyRemoves;
import org.mockserver.codec.SnappyBlockOrFrameDecoder;
import org.mockserver.configuration.Configuration;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.mappers.MockServerHttpRequestToFullHttpRequest;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.function.UnaryOperator;
import java.util.zip.Deflater;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;
import static org.mockserver.configuration.Configuration.configuration;
import static org.mockserver.model.DataPlaneCopies.acceptFilteredRequest;
import static org.mockserver.model.DataPlaneCopies.changed;
import static org.mockserver.model.DataPlaneCopies.copyForFilter;

/**
 * A gzip (or framed Snappy) request received by the real HTTP/1.1 inbound chain, passed through a request filter's copy and accept
 * rules, then mapped to the forwarded request: whether the filter changed the body or not, the forwarded
 * {@code Content-Encoding} and {@code Content-Length} describe the bytes actually sent.
 */
public class DataPlaneCopiesContentEncodingTest {

    private static final int MAX_BODY = 1024 * 1024;
    private static final Configuration CONFIGURATION = configuration().maxRequestBodySize(MAX_BODY);
    private static final MockServerLogger LOGGER = new MockServerLogger(DataPlaneCopiesContentEncodingTest.class);
    private static final byte[] PLAIN = plain();
    private static final byte[] CHANGED = "{\"changed\":true}".getBytes(StandardCharsets.UTF_8);

    private final MockServerHttpRequestToFullHttpRequest mapper = new MockServerHttpRequestToFullHttpRequest(LOGGER, null);

    @Test
    public void shouldForwardTheClientsBytesWhenAFilterLeftTheBodyUnchanged() throws IOException {
        byte[] wire = gzip(PLAIN);
        assertThat("differs from MockServer's own gzip, so re-encoding would show", wire, not(BodyContentEncodingEncoder.encodeBody(PLAIN, "gzip")));

        for (UnaryOperator<HttpRequest> filter : List.<UnaryOperator<HttpRequest>>of(
            copy -> copy,
            copy -> copy.withHeader("x-filtered", "1"),
            copy -> copy.withBody(copy.getBody().copy())
        )) {
            HttpRequest received = receive(wire);
            HttpRequest accepted = acceptFilteredRequest(filter.apply(copyForFilter(received)), received, received);

            Forwarded forwarded = forward(accepted);

            assertThat(accepted.isBodyAsReceived(), is(true));
            assertThat("the bytes the client sent", forwarded.body, is(wire));
            assertThat(forwarded.contentEncodings, contains("gzip"));
            assertThat(forwarded.contentLength, is(String.valueOf(forwarded.body.length)));
        }
    }

    @Test
    public void shouldForwardTheFilteredBodyEncodedAgainWhenAFilterChangedIt() throws IOException {
        for (UnaryOperator<HttpRequest> filter : List.<UnaryOperator<HttpRequest>>of(
            copy -> copy.withBody(new JsonBody(new String(CHANGED, StandardCharsets.UTF_8))),
            copy -> {
                // in place, through the bytes the copy's body holds
                byte[] bytes = copy.getBody().getRawBytes();
                System.arraycopy(CHANGED, 0, bytes, 0, CHANGED.length);
                return copy;
            }
        )) {
            HttpRequest received = receive(gzip(PLAIN));
            HttpRequest accepted = acceptFilteredRequest(filter.apply(copyForFilter(received)), received, received);

            Forwarded forwarded = forward(accepted);

            assertThat(changed(accepted, received), is(true));
            assertThat(accepted.isBodyAsReceived(), is(false));
            assertThat(accepted.getOriginalBody(), nullValue());
            assertThat("the filtered body", gunzip(forwarded.body), is(accepted.getBodyAsRawBytes()));
            assertThat(forwarded.contentEncodings, contains("gzip"));
            assertThat(forwarded.contentLength, is(String.valueOf(forwarded.body.length)));
        }
        HttpRequest received = receive(gzip(PLAIN));
        assertThat("the received request is untouched", forward(received).body, is(received.getOriginalBody()));
    }

    @Test
    public void shouldForwardAChangedFramedSnappyBodyFramedAsAnOverrideDoes() {
        byte[] wire = snappyFramed(PLAIN);
        assertThat(SnappyBlockOrFrameDecoder.isFramed(wire), is(true));

        HttpRequest unchangedReceived = receive(wire, "snappy");
        Forwarded unchanged = forward(acceptFilteredRequest(copyForFilter(unchangedReceived), unchangedReceived, unchangedReceived));
        HttpRequest changedReceived = receive(wire, "snappy");
        HttpRequest accepted = acceptFilteredRequest(copyForFilter(changedReceived).withBody(new JsonBody(new String(CHANGED, StandardCharsets.UTF_8))), changedReceived, changedReceived);
        Forwarded changed = forward(accepted);

        assertThat("the bytes the client sent", unchanged.body, is(wire));
        assertThat(accepted.isBodyAsReceived(), is(false));
        assertThat("re-framed, as the original was", SnappyBlockOrFrameDecoder.isFramed(changed.body), is(true));
        assertThat(unsnappyFramed(changed.body), is(CHANGED));
        assertThat(changed.contentEncodings, contains("snappy"));
        assertThat(changed.contentLength, is(String.valueOf(changed.body.length)));
    }

    private static HttpRequest receive(byte[] body) {
        return receive(body, "gzip");
    }

    private static HttpRequest receive(byte[] body, String contentEncoding) {
        EmbeddedChannel channel = new EmbeddedChannel(
            new HttpServerCodec(),
            new PreserveHeadersNettyRemoves(),
            new MockServerHttpContentDecompressor(MAX_BODY),
            HttpObjectAggregators.httpObjectAggregator(MAX_BODY),
            new NettyHttpToMockServerHttpRequestDecoder(CONFIGURATION, LOGGER, false, null, 1080)
        );
        try {
            String head = "POST /forward HTTP/1.1\r\nhost: localhost:1080\r\ncontent-type: application/json\r\ncontent-encoding: " + contentEncoding + "\r\n"
                + "content-length: " + body.length + "\r\n\r\n";
            ByteArrayOutputStream raw = new ByteArrayOutputStream();
            raw.writeBytes(head.getBytes(StandardCharsets.US_ASCII));
            raw.writeBytes(body);
            channel.writeInbound(Unpooled.wrappedBuffer(raw.toByteArray()));
            HttpRequest request = channel.readInbound();
            assertThat(request, not(nullValue()));
            assertThat(request.isBodyAsReceived(), is(true));
            return request;
        } finally {
            channel.finishAndReleaseAll();
        }
    }

    private static final class Forwarded {
        byte[] body;
        List<String> contentEncodings;
        String contentLength;
    }

    private Forwarded forward(HttpRequest request) {
        FullHttpRequest outbound = mapper.mapMockServerRequestToNettyRequest(request);
        try {
            Forwarded forwarded = new Forwarded();
            forwarded.body = new byte[outbound.content().readableBytes()];
            outbound.content().getBytes(outbound.content().readerIndex(), forwarded.body);
            forwarded.contentEncodings = outbound.headers().getAll("content-encoding");
            forwarded.contentLength = outbound.headers().get("content-length");
            return forwarded;
        } finally {
            outbound.release();
        }
    }

    private static byte[] plain() {
        StringBuilder json = new StringBuilder("{\"items\":[");
        for (int i = 0; i < 200; i++) {
            json.append(i == 0 ? "" : ",").append("{\"id\":").append(i).append("}");
        }
        return json.append("]}").toString().getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] snappyFramed(byte[] plain) {
        EmbeddedChannel channel = new EmbeddedChannel(new SnappyFrameEncoder());
        try {
            channel.writeOutbound(Unpooled.wrappedBuffer(plain));
            return drain(channel, false);
        } finally {
            channel.finishAndReleaseAll();
        }
    }

    private static byte[] unsnappyFramed(byte[] framed) {
        EmbeddedChannel channel = new EmbeddedChannel(new SnappyFrameDecoder());
        try {
            channel.writeInbound(Unpooled.wrappedBuffer(framed));
            return drain(channel, true);
        } finally {
            channel.finishAndReleaseAll();
        }
    }

    private static byte[] drain(EmbeddedChannel channel, boolean inbound) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteBuf piece;
        while ((piece = inbound ? channel.readInbound() : channel.readOutbound()) != null) {
            byte[] bytes = new byte[piece.readableBytes()];
            piece.readBytes(bytes);
            out.writeBytes(bytes);
            piece.release();
        }
        return out.toByteArray();
    }

    private static byte[] gzip(byte[] bytes) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (GZIPOutputStream gzip = new GZIPOutputStream(out) {
            {
                def.setLevel(Deflater.BEST_SPEED);
            }
        }) {
            gzip.write(bytes);
        }
        return out.toByteArray();
    }

    private static byte[] gunzip(byte[] bytes) throws IOException {
        try (GZIPInputStream gunzip = new GZIPInputStream(new ByteArrayInputStream(bytes))) {
            return gunzip.readAllBytes();
        }
    }
}
