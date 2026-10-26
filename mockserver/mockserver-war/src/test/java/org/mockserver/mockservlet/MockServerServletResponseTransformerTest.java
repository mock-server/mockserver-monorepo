package org.mockserver.mockservlet;

import jakarta.servlet.http.HttpServletResponseWrapper;
import org.junit.After;
import org.junit.Test;
import org.mockserver.extension.ResponseTransformer;
import org.mockserver.extension.TransformContext;
import org.mockserver.model.HttpRequest;
import org.mockserver.model.HttpResponse;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.lessThan;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;
import static org.hamcrest.Matchers.startsWith;
import static org.mockserver.configuration.Configuration.configuration;

/**
 * An extension response transformer in a WAR: the servlet's request thread waits for the transformer, which runs on
 * the extension pool, then writes the transformed response itself before {@code service} returns; MockServer's own
 * {@code 404} is transformed too, and a transformer that runs out of its budget is answered {@code 500} once the
 * budget ends.
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
}
