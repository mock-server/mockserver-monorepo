package org.mockserver.proxyservlet;

import jakarta.servlet.http.HttpServletResponseWrapper;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockserver.echo.http.EchoServer;
import org.mockserver.extension.ResponseTransformer;
import org.mockserver.extension.TransformContext;
import org.mockserver.model.HttpRequest;
import org.mockserver.model.HttpResponse;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;
import static org.hamcrest.Matchers.startsWith;
import static org.mockserver.configuration.Configuration.configuration;

/**
 * An extension response transformer in the proxy WAR: a response proxied to an upstream is transformed, with
 * {@link TransformContext#isProxyRequest()} true, and written by the servlet's request thread before {@code service}
 * returns; with no transformer loaded the proxied response is unchanged.
 */
public class ProxyServletResponseTransformerTest {

    private static final List<String> CALLS = new CopyOnWriteArrayList<>();

    private EchoServer upstream;
    private ProxyServlet servlet;

    public static class Transformer implements ResponseTransformer {
        @Override
        public String name() {
            return "proxy-servlet-transformer";
        }

        @Override
        public HttpResponse transform(HttpRequest request, HttpResponse response, TransformContext context) {
            CALLS.add(request.getPath().getValue() + " proxy=" + context.isProxyRequest() + " generated=" + context.isGenerated() + " thread=" + Thread.currentThread().getName());
            return response.withHeader("x-transformed", context.isProxyRequest() ? "proxied" : "not-proxied");
        }
    }

    // records the thread that writes the status, which the servlet's writer does in sendResponse
    private static final class RecordingResponse extends HttpServletResponseWrapper {
        final MockHttpServletResponse delegate;
        volatile Thread statusWrittenBy;

        RecordingResponse(MockHttpServletResponse delegate) {
            super(delegate);
            this.delegate = delegate;
        }

        @Override
        public void setStatus(int sc) {
            statusWrittenBy = Thread.currentThread();
            super.setStatus(sc);
        }
    }

    @Before
    public void startUpstream() {
        CALLS.clear();
        upstream = new EchoServer(false);
    }

    @After
    public void stop() {
        if (servlet != null) {
            servlet.destroy();
        }
        upstream.stop();
        CALLS.clear();
    }

    // no expectation matches and the Host is not the servlet's own, so the request is proxied to that Host
    private RecordingResponse proxy(String path, String body) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", path);
        request.addHeader("Host", "127.0.0.1:" + upstream.getPort());
        request.setContent(body.getBytes(UTF_8));
        RecordingResponse response = new RecordingResponse(new MockHttpServletResponse());
        servlet.service(request, response);
        return response;
    }

    @Test
    public void shouldTransformAProxiedResponseAndWriteItOnTheRequestThread() {
        servlet = new ProxyServlet(configuration().extensions(Transformer.class));

        RecordingResponse response = proxy("/proxied", "through the proxy");

        assertThat(response.delegate.getStatus(), is(200));
        assertThat(new String(response.delegate.getContentAsByteArray(), UTF_8), is("through the proxy"));
        assertThat(response.delegate.getHeader("x-transformed"), is("proxied"));
        assertThat("the upstream answered it", upstream.getLastRequest().getPath().getValue(), is("/proxied"));
        assertThat("written by the request thread, after it waited", response.statusWrittenBy, is(Thread.currentThread()));
        assertThat(CALLS.size(), is(1));
        assertThat(CALLS.get(0), startsWith("/proxied proxy=true generated=false thread=MockServer-ext-dataplane"));
    }

    @Test
    public void shouldNotTransformAControlPlaneResponse() throws Exception {
        servlet = new ProxyServlet(configuration().extensions(Transformer.class));
        MockHttpServletRequest status = new MockHttpServletRequest("PUT", "/mockserver/status");
        MockHttpServletResponse response = new MockHttpServletResponse();

        servlet.service(status, response);

        assertThat(response.getStatus(), is(200));
        assertThat(response.getHeader("x-transformed"), nullValue());
        assertThat(CALLS, is(empty()));
    }

    @Test
    public void shouldLeaveAProxiedResponseUnchangedWithNoTransformerLoaded() {
        servlet = new ProxyServlet(configuration());

        RecordingResponse response = proxy("/untransformed", "unchanged");

        assertThat(response.delegate.getStatus(), is(200));
        assertThat(new String(response.delegate.getContentAsByteArray(), UTF_8), is("unchanged"));
        assertThat(response.delegate.getHeader("x-transformed"), nullValue());
        assertThat(upstream.getLastRequest().getPath().getValue(), is("/untransformed"));
        assertThat(response.statusWrittenBy, is(Thread.currentThread()));
        assertThat(CALLS, is(empty()));
    }
}
