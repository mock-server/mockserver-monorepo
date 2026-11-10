package org.mockserver.netty.unification;

import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.DefaultHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpRequest;
import org.junit.Test;
import io.netty.handler.codec.http2.DefaultHttp2Headers;
import io.netty.handler.codec.http2.DefaultHttp2HeadersFrame;
import io.netty.handler.codec.http2.Http2HeadersFrame;
import org.mockserver.authentication.ProxyAuthenticationContext;
import org.mockserver.netty.controlplane.ListenerRole;
import org.mockserver.netty.proxy.TransparentProxyHandler;
import org.mockserver.logging.MockServerLogger;
import java.net.InetSocketAddress;

import static io.netty.handler.codec.http.HttpHeaderNames.*;
import static io.netty.handler.codec.http.HttpMethod.GET;
import static io.netty.handler.codec.http.HttpVersion.HTTP_1_1;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import static org.mockserver.configuration.Configuration.configuration;
import static org.mockserver.netty.HttpRequestHandler.*;

public class ProxyAuthenticationHandlerTest {
    private EmbeddedChannel channel() {
        return new EmbeddedChannel(new ProxyAuthenticationHandler(configuration()
            .proxyAuthenticationUsername("u").proxyAuthenticationPassword("p")));
    }

    private HttpRequest request(String credential) {
        HttpRequest request = new DefaultHttpRequest(HTTP_1_1, GET, "http://origin.example/path");
        request.headers().set(HOST, "origin.example");
        if (credential != null) {
            request.headers().set(PROXY_AUTHORIZATION, credential);
        }
        return request;
    }

    @Test
    public void removesCredentialsBeforeConsumersAndRetainsIdentityAndOriginHeaders() {
        EmbeddedChannel channel = channel();
        HttpRequest request = request("Basic dTpw");
        request.headers().set(AUTHORIZATION, "Bearer origin-token").set(COOKIE, "origin-cookie");
        assertThat(channel.writeInbound(request), is(true));
        assertThat(channel.readInbound(), sameInstance(request));
        assertThat(request.headers().contains(PROXY_AUTHORIZATION), is(false));
        assertThat(request.headers().get(AUTHORIZATION), is("Bearer origin-token"));
        assertThat(request.headers().get(COOKIE), is("origin-cookie"));
        assertThat(channel.attr(PROXY_AUTHENTICATION_CONTEXT).get(), is(new ProxyAuthenticationContext("u")));
        channel.finishAndReleaseAll();
    }

    @Test
    public void refusesMissingOrInvalidCredentialsBeforeConsumersRun() {
        for (String credential : new String[]{null, "Basic wrong"}) {
            EmbeddedChannel channel = channel();
            HttpRequest request = request(credential);
            assertThat(channel.writeInbound(request), is(false));
            assertThat(request.headers().contains(PROXY_AUTHORIZATION), is(false));
            FullHttpResponse response = channel.readOutbound();
            assertThat(response.status().code(), is(407));
            assertThat(response.headers().get(PROXY_AUTHENTICATE), startsWith("Basic "));
            response.release();
            channel.finishAndReleaseAll();
        }
    }

    @Test
    public void checksEachRequestRatherThanReusingThePreviousLogin() {
        EmbeddedChannel channel = channel();
        channel.writeInbound(request("Basic dTpw"));
        channel.readInbound();
        assertThat(channel.writeInbound(request(null)), is(false));
        FullHttpResponse response = channel.readOutbound();
        assertThat(response.status().code(), is(407));
        assertThat(channel.attr(PROXY_AUTHENTICATION_CONTEXT).get(), nullValue());
        response.release();
        channel.finishAndReleaseAll();
    }

    @Test
    public void separateProxyChecksEachRequestInsteadOfTrustingProxyingFlag() {
        EmbeddedChannel channel = channel();
        channel.attr(ListenerRole.KEY).set(ListenerRole.PROXY);
        channel.attr(PROXYING).set(true);
        channel.attr(PROXY_AUTHENTICATION_CONTEXT).set(new ProxyAuthenticationContext("u"));
        HttpRequest request = request(null);
        assertThat(channel.writeInbound(request), is(false));
        FullHttpResponse response = channel.readOutbound();
        assertThat(response.status().code(), is(407));
        response.release();
        channel.finishAndReleaseAll();
    }

    @Test
    public void combinedFixedDestinationDoesNotRequireProxyCredentials() {
        EmbeddedChannel channel = new EmbeddedChannel(new ProxyAuthenticationHandler(configuration()
            .proxyAuthenticationUsername("u").proxyAuthenticationPassword("p")
            .proxyRemoteHost("origin.example").proxyRemotePort(80)));
        channel.attr(PROXYING).set(true);
        HttpRequest request = request(null);
        assertThat(channel.writeInbound(request), is(true));
        assertThat(channel.readInbound(), sameInstance(request));
        assertThat(channel.readOutbound(), nullValue());
        channel.finishAndReleaseAll();
    }

    @Test
    public void combinedTransparentResolvedAndHostFallbackRequestsDoNotRequireProxyCredentials() {
        for (InetSocketAddress destination : new InetSocketAddress[]{null, new InetSocketAddress("192.0.2.5", 80)}) {
            org.mockserver.configuration.Configuration configuration = configuration()
                .proxyAuthenticationUsername("u").proxyAuthenticationPassword("p").transparentProxyEnabled(true);
            EmbeddedChannel channel = new EmbeddedChannel(new TransparentProxyHandler(configuration,
                org.mockito.Mockito.mock(MockServerLogger.class), ignored -> destination), new ProxyAuthenticationHandler(configuration));
            HttpRequest request = request(null);
            assertThat(channel.attr(PROXYING).get(), is(true));
            assertThat(channel.writeInbound(request), is(true));
            assertThat(channel.readInbound(), sameInstance(request));
            assertThat(channel.readOutbound(), nullValue());
            channel.finishAndReleaseAll();
        }
    }

    @Test
    public void separateProxyRequiresLoginForLocalLookingRequests() {
        EmbeddedChannel channel = channel();
        channel.attr(ListenerRole.KEY).set(ListenerRole.PROXY);
        HttpRequest request = new DefaultHttpRequest(HTTP_1_1, GET, "/mockserver/status");
        request.headers().set(HOST, "localhost");
        assertThat(channel.writeInbound(request), is(false));
        FullHttpResponse response = channel.readOutbound();
        assertThat(response.status().code(), is(407));
        response.release();
        channel.finishAndReleaseAll();
    }
    @Test
    public void authenticatesAndStripsHttp2HeadersBeforeRouting() {
        EmbeddedChannel channel = channel();
        Http2HeadersFrame frame = new DefaultHttp2HeadersFrame(new DefaultHttp2Headers()
            .method("POST").scheme("http").authority("origin.example").path("/bidi")
            .set(PROXY_AUTHORIZATION, "Basic dTpw"));
        assertThat(channel.writeInbound(frame), is(true));
        assertThat(channel.readInbound(), sameInstance(frame));
        assertThat(frame.headers().contains(PROXY_AUTHORIZATION), is(false));
        assertThat(channel.attr(PROXY_AUTHENTICATION_CONTEXT).get(), is(new ProxyAuthenticationContext("u")));
        channel.finishAndReleaseAll();
    }

    @Test
    public void refusesHttp2HeadersBeforeRouting() {
        EmbeddedChannel channel = channel();
        Http2HeadersFrame frame = new DefaultHttp2HeadersFrame(new DefaultHttp2Headers()
            .method("POST").scheme("http").authority("origin.example").path("/bidi"));
        assertThat(channel.writeInbound(frame), is(false));
        Http2HeadersFrame response = channel.readOutbound();
        assertThat(response.headers().status().toString(), is("407"));
        assertThat(response.isEndStream(), is(true));
        channel.finishAndReleaseAll();
    }

    @Test
    public void stripsNonstandardMixedCaseHttp2ProxyHeadersBeforeRejecting() {
        EmbeddedChannel channel = channel();
        channel.attr(ListenerRole.KEY).set(ListenerRole.PROXY);
        channel.attr(PROXYING).set(true);
        Http2HeadersFrame frame = new DefaultHttp2HeadersFrame(new DefaultHttp2Headers(false)
            .method("GET").scheme("http").authority("origin.example").path("/")
            .set("Proxy-Authorization", "inner-credential"));
        assertThat(channel.writeInbound(frame), is(false));
        assertThat(frame.headers().contains("Proxy-Authorization"), is(false));
        Http2HeadersFrame response = channel.readOutbound();
        assertThat(response.headers().status().toString(), is("407"));
        channel.finishAndReleaseAll();
    }

}
