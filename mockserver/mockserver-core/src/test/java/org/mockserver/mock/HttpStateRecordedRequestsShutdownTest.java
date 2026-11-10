package org.mockserver.mock;

import org.junit.Test;
import org.mockserver.configuration.Configuration;
import org.mockserver.log.MockServerEventLog;
import org.mockserver.log.model.LogEntry;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.model.HttpRequestAndHttpResponse;
import org.mockserver.scheduler.Scheduler;
import org.mockserver.serialization.HttpRequestAndHttpResponseSerializer;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockserver.configuration.Configuration.configuration;
import static org.mockserver.log.model.LogEntry.LogMessageType.FORWARDED_REQUEST;
import static org.mockserver.log.model.LogEntry.LogMessageType.RUNNABLE;
import static org.mockserver.model.HttpRequest.request;
import static org.mockserver.model.HttpResponse.response;

public class HttpStateRecordedRequestsShutdownTest {

    @Test
    public void shouldKeepRecordedExpectationsWhenStopping() throws Exception {
        Path snapshot = Files.createTempFile("recordedExpectationsShutdown", ".json");
        Configuration configuration = configuration()
            .persistRecordedExpectations(true).persistedRecordedExpectationsPath(snapshot.toString());
        MockServerLogger logger = new MockServerLogger(configuration, getClass());
        Scheduler scheduler = new Scheduler(configuration, logger);
        HttpState state = new HttpState(configuration, logger, scheduler);
        boolean stopped = false;
        try {
            state.log(new LogEntry().setType(FORWARDED_REQUEST)
                .setHttpRequest(request("/recorded-before-stop"))
                .setHttpResponse(response().withBody("preserved response"))
                .setExpectation(request("/recorded-before-stop"), response().withBody("preserved response")));
            stopped = true;
            state.stop();

            com.fasterxml.jackson.databind.JsonNode expectations = org.mockserver.serialization.ObjectMapperFactory
                .createObjectMapper().readTree(Files.readString(snapshot));
            assertThat(expectations.size(), is(1));
            assertThat(expectations.get(0).at("/httpRequest/path").asText(), is("/recorded-before-stop"));
            assertThat(expectations.get(0).at("/httpResponse/body").asText(), is("preserved response"));
        } finally {
            if (!stopped) {
                state.stop();
            }
            scheduler.shutdown();
            Files.deleteIfExists(snapshot);
        }
    }

    @Test
    public void shouldPersistQueuedExchangeBeforeClosingRecordingOnStop() throws Exception {
        Path archive = Files.createTempFile("recordedRequestsShutdown", ".ndjson");
        Configuration configuration = configuration()
            .persistRecordedRequestsToDisk(true).persistedRecordedRequestsPath(archive.toString());
        MockServerLogger logger = new MockServerLogger(configuration, getClass());
        HttpState state = new HttpState(configuration, logger, mock(Scheduler.class));
        CountDownLatch consumerBlocked = new CountDownLatch(1);
        CountDownLatch shutdownStarted = new CountDownLatch(1);
        Thread stoppingThread = Thread.currentThread();
        MockServerEventLog eventLog = state.getMockServerLog();
        eventLog.registerListener(log -> {
            if (Thread.currentThread() == stoppingThread) {
                shutdownStarted.countDown();
            }
        });
        boolean stopped = false;
        try {
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

            // The synchronous stop notification releases a consumer with an exchange still queued.
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
}
