package org.mockserver.mock;

import org.junit.Test;
import org.mockserver.configuration.Configuration;
import org.mockserver.log.MockServerEventLog;
import org.mockserver.log.model.LogEntry;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.model.HttpRequestAndHttpResponse;
import org.mockserver.scheduler.Scheduler;
import org.mockserver.serialization.HttpRequestAndHttpResponseSerializer;
import org.mockserver.state.StateBackend;

import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockserver.configuration.Configuration.configuration;
import static org.mockserver.log.model.LogEntry.LogMessageType.FORWARDED_REQUEST;
import static org.mockserver.log.model.LogEntry.LogMessageType.RUNNABLE;
import static org.mockserver.model.HttpRequest.request;
import static org.mockserver.model.HttpResponse.response;

public class HttpStateRecordedRequestsShutdownTest {

    @Test
    public void shouldPersistQueuedExchangeBeforeClosingRecordingOnStop() throws Exception {
        Path archive = Files.createTempFile("recordedRequestsShutdown", ".ndjson");
        Configuration configuration = configuration()
            .persistRecordedRequestsToDisk(true)
            .persistedRecordedRequestsPath(archive.toString());
        MockServerLogger logger = new MockServerLogger(configuration, getClass());
        HttpState state = new HttpState(configuration, logger, mock(Scheduler.class));
        CountDownLatch consumerBlocked = new CountDownLatch(1);
        CountDownLatch shutdownStarted = new CountDownLatch(1);
        Thread stoppingThread = Thread.currentThread();
        MockServerEventLog eventLog = state.getMockServerLog();
        // the event log's stop notifies listeners synchronously on the stopping thread
        eventLog.registerListener(log -> {
            if (Thread.currentThread() == stoppingThread) {
                shutdownStarted.countDown();
            }
        });
        boolean stopped = false;
        try {
            // hold the event-log consumer so the exchange below is still queued in the ring when stop begins
            eventLog.add(new LogEntry().setType(RUNNABLE).setConsumer(() -> {
                consumerBlocked.countDown();
                try {
                    assertTrue("shutdown did not release the consumer", shutdownStarted.await(10, SECONDS));
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError(interrupted);
                }
            }));
            assertTrue("event consumer did not start", consumerBlocked.await(10, SECONDS));
            state.log(new LogEntry().setType(FORWARDED_REQUEST)
                .setHttpRequest(request("/queued-at-shutdown").withMethod("POST").withBody("request body"))
                .setHttpResponse(response().withStatusCode(201).withBody("response body")));

            stopped = true;
            state.stop();

            List<String> lines = Files.readAllLines(archive);
            assertThat(lines.size(), is(1));
            HttpRequestAndHttpResponse recorded = new HttpRequestAndHttpResponseSerializer(logger).deserialize(lines.get(0));
            assertThat(recorded.getHttpRequest().getPath().getValue(), is("/queued-at-shutdown"));
            assertThat(recorded.getHttpRequest().getBodyAsString(), is("request body"));
            assertThat(recorded.getHttpResponse().getStatusCode(), is(201));
            assertThat(recorded.getHttpResponse().getBodyAsString(), is("response body"));
        } finally {
            shutdownStarted.countDown();
            if (!stopped) {
                state.stop();
            }
            Files.deleteIfExists(archive);
        }
    }

    @Test
    public void shouldStillDrainCloseRecordingAndCloseBackendWhenAnEarlierStopStepFails() throws Exception {
        Path archive = Files.createTempFile("recordedRequestsShutdownFailure", ".ndjson");
        Configuration configuration = configuration()
            .persistRecordedRequestsToDisk(true)
            .persistedRecordedRequestsPath(archive.toString());
        MockServerLogger logger = new MockServerLogger(configuration, getClass());
        HttpState state = new HttpState(configuration, logger, mock(Scheduler.class));
        try {
            // the spy shares the real event log and recording file; only its state backend is replaced
            HttpState failingStop = spy(state);
            StateBackend backend = mock(StateBackend.class);
            Field stateBackendField = HttpState.class.getDeclaredField("stateBackend");
            stateBackendField.setAccessible(true);
            stateBackendField.set(failingStop, backend);
            doThrow(new IllegalStateException("earlier stop step failed")).when(failingStop).completeOpenRecordedStreams(anyLong());
            state.log(new LogEntry().setType(FORWARDED_REQUEST)
                .setHttpRequest(request("/logged-before-failed-stop"))
                .setHttpResponse(response().withStatusCode(200)));

            assertThrows(IllegalStateException.class, failingStop::stop);

            verify(backend).close();
            List<String> lines = Files.readAllLines(archive);
            assertThat(lines.size(), is(1));
            assertThat(new HttpRequestAndHttpResponseSerializer(logger).deserialize(lines.get(0)).getHttpRequest().getPath().getValue(),
                is("/logged-before-failed-stop"));
        } finally {
            // release what the failed stop skipped, and the real backend; the event log and file tolerate a second stop
            state.stop();
            Files.deleteIfExists(archive);
        }
    }
}
