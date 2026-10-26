package org.mockserver.mock;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockserver.configuration.Configuration;
import org.mockserver.file.FileReader;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.model.HttpRequest;
import org.mockserver.model.HttpResponse;
import org.mockserver.responsewriter.ResponseWriter;
import org.mockserver.scheduler.Scheduler;
import org.mockserver.serialization.ObjectMapperFactory;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.mockserver.configuration.Configuration.configuration;
import static org.mockserver.model.HttpRequest.request;
import static org.mockserver.model.HttpResponse.response;

/**
 * A shut-down {@link Scheduler} discards, rather than rejects, the work a control-plane endpoint offloads onto it, so
 * each endpoint that offloads its blocking work must answer {@code 503} itself instead of leaving the caller waiting.
 */
public class HttpStateSchedulerShutdownTest {

    private static final ObjectMapper OBJECT_MAPPER = ObjectMapperFactory.createObjectMapper();
    private static final long WAIT_SECONDS = 30;

    private final Configuration configuration = configuration();
    private Scheduler scheduler;
    private HttpState httpState;
    private String spec;

    @Before
    public void startThenShutDownTheScheduler() {
        scheduler = new Scheduler(configuration, new MockServerLogger());
        httpState = new HttpState(configuration, new MockServerLogger(), scheduler);
        spec = FileReader.readFileFromClassPathOrPath("org/mockserver/openapi/openapi_petstore_example.json");
        scheduler.shutdown();
    }

    @After
    public void stop() {
        httpState.stop();
    }

    @Test
    public void answersAnOpenApiImportWith503() throws Exception {
        CapturingResponseWriter responseWriter = new CapturingResponseWriter(configuration);

        assertThat(httpState.handle(request("/mockserver/openapi").withMethod("PUT")
            .withBody("{\"specUrlOrPayload\":" + OBJECT_MAPPER.writeValueAsString(spec) + "}"), responseWriter, false), is(true));

        assertThat(responseWriter.awaitResponse().getStatusCode(), is(503));
        assertAnsweredOnce(responseWriter);
    }

    @Test
    public void answersAContractTestWith503() throws Exception {
        httpState.setReplayHandler(outbound -> CompletableFuture.completedFuture(response().withStatusCode(200)));
        CapturingResponseWriter responseWriter = new CapturingResponseWriter(configuration);

        assertThat(httpState.handle(request("/mockserver/contractTest").withMethod("PUT")
            .withBody("{\"spec\":" + OBJECT_MAPPER.writeValueAsString(spec) + ",\"baseUrl\":\"http://localhost:1080\",\"operationId\":\"listPets\"}"), responseWriter, false), is(true));

        HttpResponse response = responseWriter.awaitResponse();
        assertThat(response.getBodyAsString(), response.getStatusCode(), is(503));
        assertAnsweredOnce(responseWriter);
    }

    @Test
    public void answersATrafficValidationWith503() throws Exception {
        CapturingResponseWriter responseWriter = new CapturingResponseWriter(configuration);

        assertThat(httpState.handle(request("/mockserver/trafficValidate").withMethod("PUT")
            .withBody("{\"spec\":" + OBJECT_MAPPER.writeValueAsString(spec) + "}"), responseWriter, false), is(true));

        HttpResponse response = responseWriter.awaitResponse();
        assertThat(response.getBodyAsString(), response.getStatusCode(), is(503));
        assertAnsweredOnce(responseWriter);
    }

    /**
     * Once the scheduler has terminated no offloaded work can still write, so one response now means one for good.
     */
    private void assertAnsweredOnce(CapturingResponseWriter responseWriter) throws InterruptedException {
        assertThat(scheduler.getExecutorService().awaitTermination(WAIT_SECONDS, TimeUnit.SECONDS), is(true));
        assertThat(responseWriter.responses, hasSize(1));
    }

    private static class CapturingResponseWriter extends ResponseWriter {
        private final List<HttpResponse> responses = new CopyOnWriteArrayList<>();

        private CapturingResponseWriter(Configuration configuration) {
            super(configuration, new MockServerLogger());
        }

        @Override
        public void sendResponse(HttpRequest request, HttpResponse response) {
            responses.add(response);
        }

        HttpResponse awaitResponse() throws InterruptedException {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(WAIT_SECONDS);
            while (responses.isEmpty() && System.nanoTime() < deadline) {
                TimeUnit.MILLISECONDS.sleep(5);
            }
            assertThat("a response was written", responses.isEmpty(), is(false));
            return responses.get(0);
        }
    }
}
