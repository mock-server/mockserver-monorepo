package org.mockserver.mockservlet;

import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletResponseWrapper;
import org.junit.After;
import org.junit.Test;
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
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.lessThan;
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
 * An extension response transformer in a WAR: the servlet's request thread waits for the transformer, which runs on
 * the extension pool, then writes the transformed response itself before {@code service} returns; MockServer's own
 * {@code 404} is transformed too, and a transformer that runs out of its budget is answered {@code 500} once the
 * budget ends. A data-plane request whose handling throws while a transformer is loaded is answered with the
 * untransformed {@code 500}.
 */
public class MockServerServletResponseTransformerTest {

    private static final Map<String, String> TRANSFORMER_THREADS = new ConcurrentHashMap<>();

    private MockServerServlet servlet;

    public static class Transformer implements ResponseTransformer {
        @Override
        public String name() {
            return "servlet-transformer";
        }

        @Override
        public HttpResponse transform(HttpRequest request, HttpResponse response, TransformContext context) throws Exception {
            String path = request.getPath().getValue();
            TRANSFORMER_THREADS.put(path, Thread.currentThread().getName());
            if (path.equals("/servlet/slow")) {
                Thread.sleep(5_000);
            }
            return response.withHeader("x-transformed", context.isGenerated() ? "generated" : "mocked");
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

    @After
    public void destroy() {
        if (servlet != null) {
            servlet.destroy();
        }
        TRANSFORMER_THREADS.clear();
    }

    private RecordingResponse serve(String method, String path, String body) {
        MockHttpServletRequest request = new MockHttpServletRequest(method, path);
        if (body != null) {
            request.addHeader("Content-Type", "application/json; charset=utf-8");
            request.setContent(body.getBytes(StandardCharsets.UTF_8));
        }
        RecordingResponse response = new RecordingResponse(new MockHttpServletResponse());
        servlet.service(request, response);
        return response;
    }

    @Test
    public void shouldWriteTheTransformedResponseOnTheRequestThread() throws Exception {
        servlet = new MockServerServlet(configuration().extensions(Transformer.class));
        assertThat(serve("PUT", "/mockserver/expectation", "{\"httpRequest\": {\"path\": \"/servlet/mocked\"}, \"httpResponse\": {\"body\": \"mocked body\"}}").delegate.getStatus(), is(201));

        RecordingResponse response = serve("GET", "/servlet/mocked", null);

        assertThat(response.delegate.getStatus(), is(200));
        assertThat(response.delegate.getContentAsString(), is("mocked body"));
        assertThat(response.delegate.getHeader("x-transformed"), is("mocked"));
        assertThat("written by the request thread, after it waited", response.statusWrittenBy, is(Thread.currentThread()));
        assertThat("the transformer runs on the extension pool", TRANSFORMER_THREADS.get("/servlet/mocked"), startsWith("MockServer-ext-dataplane"));
        assertThat("an API response is never transformed", TRANSFORMER_THREADS.get("/mockserver/expectation"), nullValue());
    }

    @Test
    public void shouldTransformTheGeneratedNotFound() {
        servlet = new MockServerServlet(configuration().extensions(Transformer.class));

        RecordingResponse response = serve("GET", "/servlet/missing", null);

        assertThat(response.delegate.getStatus(), is(404));
        assertThat(response.delegate.getHeader("x-transformed"), is("generated"));
        assertThat(response.statusWrittenBy, is(Thread.currentThread()));
    }

    @Test
    public void shouldAnswerATransformerThatRunsOutOfItsBudgetWithAFailureOnTheRequestThread() throws Exception {
        servlet = new MockServerServlet(configuration().extensionDataPlaneTimeoutMillis(300L).extensions(Transformer.class));
        serve("PUT", "/mockserver/expectation", "{\"httpRequest\": {\"path\": \"/servlet/slow\"}, \"httpResponse\": {\"body\": \"never sent\"}}");

        long started = System.nanoTime();
        RecordingResponse response = serve("GET", "/servlet/slow", null);
        long waitedMillis = (System.nanoTime() - started) / 1_000_000;

        assertThat(response.delegate.getStatus(), is(500));
        assertThat(response.delegate.getContentAsString(), not("never sent"));
        assertThat(response.delegate.getHeader("x-transformed"), nullValue());
        assertThat(response.statusWrittenBy, is(Thread.currentThread()));
        assertThat("the wait ends with the budget, not the transformer", waitedMillis, lessThan(4_000L));
    }

    @Test
    public void shouldAnswerADataPlaneRequestWhoseBodyCannotBeReadWithAnUntransformedFailure() {
        servlet = new MockServerServlet(configuration().extensions(Transformer.class));
        MockHttpServletRequest request = new MockHttpServletRequest("PUT", "/servlet/unreadable") {
            @Override
            public ServletInputStream getInputStream() {
                return new FailingServletInputStream();
            }
        };
        RecordingResponse response = new RecordingResponse(new MockHttpServletResponse());

        servlet.service(request, response);

        assertUntransformedFailure(response, "connection reset reading body");
        assertThat(TRANSFORMER_THREADS.get("/servlet/unreadable"), nullValue());
    }

    @Test
    public void shouldAnswerADataPlaneFailureWithAnUntransformedFailure() throws Exception {
        servlet = new MockServerServlet(configuration().extensions(Transformer.class));
        HttpActionHandler failingActionHandler = mock(HttpActionHandler.class);
        // marked as data-plane work first, as processAction does, so the failure would be transformed if written as a mock response
        doAnswer(invocation -> {
            TransformingWriter.markDataPlane(invocation.getArgument(1), invocation.getArgument(0));
            throw new IllegalStateException("internal detail of the data plane fault");
        }).when(failingActionHandler).processAction(any(), any(), any(), any(), anyBoolean(), anyBoolean());
        Field actionHandler = MockServerServlet.class.getDeclaredField("actionHandler");
        actionHandler.setAccessible(true);
        actionHandler.set(servlet, failingActionHandler);

        RecordingResponse response = serve("GET", "/servlet/fails", null);

        assertUntransformedFailure(response, "internal detail of the data plane fault");
        assertThat(TRANSFORMER_THREADS.get("/servlet/fails"), nullValue());
    }

    private static void assertUntransformedFailure(RecordingResponse response, String internalDetail) {
        String body = new String(response.delegate.getContentAsByteArray(), StandardCharsets.UTF_8);
        assertThat(response.delegate.getStatus(), is(500));
        assertThat(body, startsWith(UNEXPECTED_FAILURE_MESSAGE));
        assertThat(body, not(containsString(internalDetail)));
        assertThat(response.delegate.getHeader("x-transformed"), nullValue());
        assertThat("written by the request thread before service returned", response.statusWrittenBy, is(Thread.currentThread()));
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
