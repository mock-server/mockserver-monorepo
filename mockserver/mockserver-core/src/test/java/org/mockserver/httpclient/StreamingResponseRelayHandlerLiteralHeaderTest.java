package org.mockserver.httpclient;

import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.DefaultHttpResponse;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpVersion;
import org.junit.Test;
import org.mockserver.configuration.Configuration;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.model.Cookie;
import org.mockserver.model.Header;
import org.mockserver.model.HttpResponse;
import org.mockserver.model.Message;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static io.netty.handler.codec.http.HttpHeaderNames.SET_COOKIE;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.mockserver.httpclient.NettyHttpClient.RESPONSE_FUTURE;

/**
 * The streaming sibling of the aggregated response mapper. Both build the recorded
 * {@code HttpResponse}, so a header or cookie name beginning with {@code !} must be recorded
 * literally on this path too — the marker-parsing {@code NottableString.string(name)} is for
 * matcher input, not for a real message.
 */
public class StreamingResponseRelayHandlerLiteralHeaderTest {

    private static HttpResponse relayHead(DefaultHttpResponse nettyResponse) throws Exception {
        EmbeddedChannel channel = new EmbeddedChannel(
            new StreamingResponseRelayHandler(Configuration.configuration(), new MockServerLogger()));
        CompletableFuture<Message> future = new CompletableFuture<>();
        channel.attr(RESPONSE_FUTURE).set(future);

        channel.writeInbound(nettyResponse);

        return (HttpResponse) future.get(10, TimeUnit.SECONDS);
    }

    @Test
    public void shouldRecordAStreamedResponseHeaderNameBeginningWithANegationMarkerAsLiteral() throws Exception {
        // given
        DefaultHttpResponse nettyResponse = new DefaultHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK);
        nettyResponse.headers().add("!foo", "bar");

        // when
        HttpResponse result = relayHead(nettyResponse);

        // then — the literal "!foo", NOT a negation of "foo"
        Header header = result.getHeaderList().stream()
            .filter(h -> h.getName().getValue().equals("!foo"))
            .findFirst().orElseThrow(() -> new AssertionError("no header literally named '!foo': " + result.getHeaderList()));
        assertThat(header.getName().isNot(), is(false));
        assertThat(header.getValues().get(0).getValue(), equalTo("bar"));
    }

    @Test
    public void shouldRecordAStreamedResponseHeaderValueBeginningWithANegationMarkerAsLiteral() throws Exception {
        // given
        DefaultHttpResponse nettyResponse = new DefaultHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK);
        nettyResponse.headers().add("X-Tag", "!foo");

        // when
        HttpResponse result = relayHead(nettyResponse);

        // then
        Header header = result.getHeaderList().stream()
            .filter(h -> h.getName().getValue().equalsIgnoreCase("X-Tag"))
            .findFirst().orElseThrow(AssertionError::new);
        assertThat(header.getValues().get(0).getValue(), equalTo("!foo"));
        assertThat(header.getValues().get(0).isNot(), is(false));
    }

    @Test
    public void shouldRecordAStreamedSetCookieNameAndValueBeginningWithANegationMarkerAsLiteral() throws Exception {
        // given
        DefaultHttpResponse nettyResponse = new DefaultHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK);
        nettyResponse.headers().add(SET_COOKIE, "!session=!abc");

        // when
        HttpResponse result = relayHead(nettyResponse);

        // then — both halves literal
        Cookie cookie = result.getCookieList().stream()
            .filter(c -> c.getName().getValue().equals("!session"))
            .findFirst().orElseThrow(() -> new AssertionError("no cookie literally named '!session': " + result.getCookieList()));
        assertThat(cookie.getName().isNot(), is(false));
        assertThat(cookie.getValue().getValue(), equalTo("!abc"));
        assertThat(cookie.getValue().isNot(), is(false));
    }

    @Test
    public void shouldRecordAStreamedResponseHeaderValueBeginningWithAnOptionalMarkerAsLiteral() throws Exception {
        // given — "?" is a legal header VALUE character and string() strips it as the optional marker
        DefaultHttpResponse nettyResponse = new DefaultHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK);
        nettyResponse.headers().add("X-Tag", "?foo");

        // when
        HttpResponse result = relayHead(nettyResponse);

        // then
        Header header = result.getHeaderList().stream()
            .filter(h -> h.getName().getValue().equalsIgnoreCase("X-Tag"))
            .findFirst().orElseThrow(AssertionError::new);
        assertThat(header.getValues().get(0).getValue(), equalTo("?foo"));
    }
}
