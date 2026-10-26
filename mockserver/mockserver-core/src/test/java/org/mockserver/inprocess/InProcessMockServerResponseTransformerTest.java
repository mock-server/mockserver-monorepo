package org.mockserver.inprocess;

import org.junit.After;
import org.junit.Test;
import org.mockserver.extension.ResponseTransformer;
import org.mockserver.extension.TransformContext;
import org.mockserver.model.HttpRequest;
import org.mockserver.model.HttpResponse;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.lessThan;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.startsWith;
import static org.mockserver.configuration.Configuration.configuration;
import static org.mockserver.mock.Expectation.when;
import static org.mockserver.model.HttpRequest.request;
import static org.mockserver.model.HttpResponse.response;

/**
 * An extension response transformer in process: {@code handle} waits on the calling thread for the transformer, which
 * runs on the extension pool, and returns the transformed response; {@code handleAsync} completes with it; MockServer's
 * own {@code 404} is transformed too, and a transformer that runs out of its budget is answered {@code 500}.
 */
public class InProcessMockServerResponseTransformerTest {

    private static final Map<String, String> TRANSFORMER_THREADS = new ConcurrentHashMap<>();

    private InProcessMockServer mockServer;

    public static class Transformer implements ResponseTransformer {
        @Override
        public String name() {
            return "in-process-transformer";
        }

        @Override
        public HttpResponse transform(HttpRequest request, HttpResponse response, TransformContext context) throws Exception {
            String path = request.getPath().getValue();
            TRANSFORMER_THREADS.put(path, Thread.currentThread().getName());
            if (path.equals("/in-process/slow")) {
                Thread.sleep(5_000);
            }
            return response.withHeader("x-transformed", context.isGenerated() ? "generated" : "mocked");
        }
    }

    @After
    public void close() {
        if (mockServer != null) {
            mockServer.close();
        }
        TRANSFORMER_THREADS.clear();
    }

    @Test
    public void shouldReturnTheTransformedResponseFromHandle() {
        mockServer = new InProcessMockServer(configuration().extensions(Transformer.class));
        mockServer.upsert(when(request("/in-process/mocked")).thenRespond(response("mocked body")));

        HttpResponse response = mockServer.handle(request("/in-process/mocked"));

        assertThat(response.getStatusCode(), is(200));
        assertThat(response.getBodyAsString(), is("mocked body"));
        assertThat(response.getFirstHeader("x-transformed"), is("mocked"));
        assertThat(TRANSFORMER_THREADS.get("/in-process/mocked"), startsWith("MockServer-ext-dataplane"));
    }

    @Test
    public void shouldCompleteHandleAsyncWithTheTransformedResponse() throws Exception {
        mockServer = new InProcessMockServer(configuration().extensions(Transformer.class));
        mockServer.upsert(when(request("/in-process/async")).thenRespond(response("async body")));

        HttpResponse response = mockServer.handleAsync(request("/in-process/async")).get(10, TimeUnit.SECONDS);

        assertThat(response.getBodyAsString(), is("async body"));
        assertThat(response.getFirstHeader("x-transformed"), is("mocked"));
    }

    @Test
    public void shouldTransformTheGeneratedNotFound() {
        mockServer = new InProcessMockServer(configuration().extensions(Transformer.class));

        HttpResponse response = mockServer.handle(request("/in-process/missing"));

        assertThat(response.getStatusCode(), is(404));
        assertThat(response.getFirstHeader("x-transformed"), is("generated"));
    }

    @Test
    public void shouldAnswerATransformerThatRunsOutOfItsBudgetWithAFailure() {
        mockServer = new InProcessMockServer(configuration().extensionDataPlaneTimeoutMillis(300L).extensions(Transformer.class));
        mockServer.upsert(when(request("/in-process/slow")).thenRespond(response("never returned")));

        long started = System.nanoTime();
        HttpResponse response = mockServer.handle(request("/in-process/slow"));
        long waitedMillis = (System.nanoTime() - started) / 1_000_000;

        assertThat(response.getStatusCode(), is(500));
        assertThat(response.getBodyAsString(), not("never returned"));
        assertThat(response.getFirstHeader("x-transformed"), is(""));
        assertThat("the wait ends with the budget, not the transformer", waitedMillis, lessThan(4_000L));
    }
}
