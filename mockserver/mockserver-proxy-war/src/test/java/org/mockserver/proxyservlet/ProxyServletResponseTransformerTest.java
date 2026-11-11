package org.mockserver.proxyservlet;

import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletResponseWrapper;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockserver.echo.http.EchoServer;
import org.mockserver.extension.ResponseTransformer;
import org.mockserver.extension.TransformContext;
import org.mockserver.mock.action.http.HttpActionHandler;
import org.mockserver.responsewriter.TransformingWriter;
import org.mockserver.model.HttpRequest;
import org.mockserver.model.HttpResponse;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.io.IOException;
import java.lang.reflect.Field;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;
import static org.hamcrest.Matchers.startsWith;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockserver.configuration.Configuration.configuration;
import static org.mockserver.responsewriter.ControlPlaneFailureResponse.UNEXPECTED_FAILURE_MESSAGE;

/**
 * An extension response transformer in the proxy WAR: a response proxied to an upstream is transformed, with
 * {@link TransformContext#isProxyRequest()} true, and written by the servlet's request thread before {@code service}
 * returns; with no transformer loaded the proxied response is unchanged. A data-plane request whose handling throws
 * while a transformer is loaded is answered with the untransformed {@code 500}.
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
    public void shouldTellTheTransformerARequestForTheServletItselfIsNotProxied() {
        servlet = new ProxyServlet(configuration().extensions(Transformer.class));
        MockHttpServletRequest upsert = new MockHttpServletRequest("PUT", "/mockserver/expectation");
        upsert.setContent("{\"httpRequest\":{\"path\":\"/mocked\"},\"httpResponse\":{\"body\":\"mocked\"}}".getBytes(UTF_8));
        MockHttpServletResponse created = new MockHttpServletResponse();
        servlet.service(upsert, created);
        assertThat(created.getStatus(), is(201));
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/mocked");
        request.addHeader("Host", "localhost");
        MockHttpServletResponse response = new MockHttpServletResponse();

        servlet.service(request, response);

        assertThat(response.getStatus(), is(200));
        assertThat(response.getHeader("x-transformed"), is("not-proxied"));
        assertThat(CALLS.size(), is(1));
        assertThat(CALLS.get(0), startsWith("/mocked proxy=false"));
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

    @Test
    public void shouldAnswerADataPlaneRequestWhoseBodyCannotBeReadWithAnUntransformedFailure() {
        servlet = new ProxyServlet(configuration().extensions(Transformer.class));
        MockHttpServletRequest request = new MockHttpServletRequest("PUT", "/unreadable") {
            @Override
            public ServletInputStream getInputStream() {
                return new FailingServletInputStream();
            }
        };
        RecordingResponse response = new RecordingResponse(new MockHttpServletResponse());

        servlet.service(request, response);

        assertUntransformedFailure(response, "connection reset reading body");
    }

    @Test
    public void shouldAnswerADataPlaneFailureWithAnUntransformedFailure() throws Exception {
        servlet = new ProxyServlet(configuration().extensions(Transformer.class));
        HttpActionHandler failingActionHandler = mock(HttpActionHandler.class);
        // marked as data-plane work first, as processAction does, so the failure would be transformed if written as a mock response
        doAnswer(invocation -> {
            TransformingWriter.markDataPlane(invocation.getArgument(1), invocation.getArgument(0));
            throw new IllegalStateException("internal detail of the data plane fault");
        }).when(failingActionHandler).processAction(any(), any(), any(), any(), anyBoolean(), anyBoolean());
        Field actionHandler = ProxyServlet.class.getDeclaredField("actionHandler");
        actionHandler.setAccessible(true);
        actionHandler.set(servlet, failingActionHandler);

        RecordingResponse response = proxy("/fails", "never proxied");

        assertUntransformedFailure(response, "internal detail of the data plane fault");
    }

    private void assertUntransformedFailure(RecordingResponse response, String internalDetail) {
        String body = new String(response.delegate.getContentAsByteArray(), UTF_8);
        assertThat(response.delegate.getStatus(), is(500));
        assertThat(body, startsWith(UNEXPECTED_FAILURE_MESSAGE));
        assertThat(body, not(containsString(internalDetail)));
        assertThat(response.delegate.getHeader("x-transformed"), nullValue());
        assertThat("written by the request thread before service returned", response.statusWrittenBy, is(Thread.currentThread()));
        assertThat(CALLS, is(empty()));
    }

    private static class FailingServletInputStream extends ServletInputStream {
        @Override
        public int read() throws IOException {
            throw new IOException("connection reset reading body");
        }

        @Override
        public boolean isFinished() {
            return false;
        }

        @Override
        public boolean isReady() {
            return true;
        }

        @Override
        public void setReadListener(ReadListener readListener) {
        }
    }
}
