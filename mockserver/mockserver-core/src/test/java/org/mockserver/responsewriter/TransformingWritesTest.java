package org.mockserver.responsewriter;

import org.junit.After;
import org.junit.Test;
import org.mockserver.configuration.Configuration;
import org.mockserver.extension.ExtensionRegistry;
import org.mockserver.extension.ResponseTransformer;
import org.mockserver.extension.TransformContext;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.model.HttpRequest;
import org.mockserver.model.HttpResponse;
import org.mockserver.scheduler.Scheduler;
import org.slf4j.event.Level;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.mockserver.configuration.Configuration.configuration;
import static org.mockserver.model.HttpRequest.request;
import static org.mockserver.model.HttpResponse.response;

/**
 * The transport-independent write rules: an exchange whose final write throws is still answered, and the static
 * {@code failUnanswered} answers an exchange nothing has marked yet.
 */
public class TransformingWritesTest {

    private final Configuration configuration = configuration().logLevel(Level.ERROR);
    private final MockServerLogger mockServerLogger = new MockServerLogger(configuration, TransformingWritesTest.class);
    private final Scheduler scheduler = new Scheduler(configuration, mockServerLogger);
    private ExtensionRegistry registry;

    public static class Identity implements ResponseTransformer {
        @Override
        public String name() {
            return "identity";
        }

        @Override
        public HttpResponse transform(HttpRequest request, HttpResponse response, TransformContext context) {
            return response;
        }
    }

    @After
    public void stop() {
        if (registry != null) {
            registry.stop();
        }
        scheduler.shutdown();
    }

    private TransformingWrites writes(TransformingWrites.BaseWrite baseWrite) {
        registry = ExtensionRegistry.start(configuration().extensions(Identity.class), mockServerLogger);
        return new TransformingWrites(registry.dataPlaneHooks(), scheduler, mockServerLogger, baseWrite, () -> false);
    }

    private static HttpRequest dataRequest() {
        return (HttpRequest) request("/data").withMethod("GET").withKeepAlive(true).withLogCorrelationId("correlation-1");
    }

    @Test
    public void shouldAnswerAClaimedExchangeWhoseWriteThrows() throws Exception {
        List<HttpResponse> written = new CopyOnWriteArrayList<>();
        AtomicBoolean thrown = new AtomicBoolean();
        TransformingWrites writes = writes((request, response, apiResponse) -> {
            if (thrown.compareAndSet(false, true)) {
                throw new IllegalStateException("the transport failed to write");
            }
            written.add(response);
        });
        HttpRequest request = dataRequest();
        writes.markDataPlane(request);

        writes.writeResponse(request, response("answer"), false);

        assertThat(awaitOne(written).getStatusCode(), is(500));
        assertThat(written.get(0).getBodyAsString(), containsString("correlation id"));
        assertThat(writes.isClaimed(), is(true));
    }

    @Test
    public void shouldAnswerAnUnmarkedExchangeThroughTheStaticFailUnanswered() throws Exception {
        List<HttpResponse> written = new CopyOnWriteArrayList<>();
        TransformingWrites writes = writes((request, response, apiResponse) -> written.add(response));
        TestWriter writer = new TestWriter(configuration, mockServerLogger, writes);

        TransformingWriter.failUnanswered(mockServerLogger, writer, dataRequest(), new RuntimeException("before any mark"));

        assertThat(awaitOne(written).getStatusCode(), is(500));
        assertThat(writer.isClaimed(), is(true));
    }

    private static HttpResponse awaitOne(List<HttpResponse> written) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (written.isEmpty() && System.nanoTime() < deadline) {
            Thread.sleep(5);
        }
        Thread.sleep(50);
        assertThat(written.size(), is(1));
        return written.get(0);
    }

    private static final class TestWriter extends ResponseWriter implements TransformingWriter {
        private final TransformingWrites writes;

        private TestWriter(Configuration configuration, MockServerLogger mockServerLogger, TransformingWrites writes) {
            super(configuration, mockServerLogger);
            this.writes = writes;
        }

        @Override
        public void sendResponse(HttpRequest request, HttpResponse response) {
            throw new UnsupportedOperationException("writes go through TransformingWrites");
        }

        @Override
        public void writeResponse(HttpRequest request, HttpResponse response, boolean apiResponse) {
            writes.writeResponse(request, response, apiResponse);
        }

        @Override
        public void markDataPlane(HttpRequest request) {
            writes.markDataPlane(request);
        }

        @Override
        public void markGenerated() {
            writes.markGenerated();
        }

        @Override
        public boolean isClaimed() {
            return writes.isClaimed();
        }

        @Override
        public void writeUntransformed(HttpRequest request, HttpResponse response) {
            writes.writeUntransformed(request, response);
        }

        @Override
        public void failUnanswered(Throwable throwable) {
            writes.failUnanswered(throwable);
        }
    }
}
