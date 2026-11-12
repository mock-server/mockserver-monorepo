package org.mockserver.mock.action.http;

import io.netty.channel.ChannelHandlerContext;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.mockserver.configuration.Configuration;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.mock.Expectation;
import org.mockserver.mock.HttpState;
import org.mockserver.mock.crud.CrudDispatcher;
import org.mockserver.model.BinaryBody;
import org.mockserver.model.FileBody;
import org.mockserver.model.HttpRequest;
import org.mockserver.model.HttpResponse;
import org.mockserver.model.HttpTemplate;
import org.mockserver.model.MediaType;
import org.mockserver.responsewriter.ResponseWriter;
import org.mockserver.scheduler.Scheduler;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Collections;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.hamcrest.Matchers.startsWith;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockserver.configuration.Configuration.configuration;
import static org.mockserver.model.HttpRequest.request;
import static org.mockserver.model.HttpResponse.response;

/**
 * Which thread reads a matched expectation's FILE body, that the bytes written are the ones the inline read
 * produced, that errors answer as before, and what a request gets when the file read queue is full.
 */
public class HttpActionHandlerFileBodyReadTest {

    // non-ASCII, so a charset change between the two paths would show
    private static final String TEXT = "{\"greeting\":\"héllo ✓ 日本\"}";

    @Rule
    public TemporaryFolder temporaryFolder = new TemporaryFolder();

    private Scheduler scheduler;
    private HttpState httpState;
    private HttpActionHandler actionHandler;
    private final AtomicInteger postProcessed = new AtomicInteger();
    // stubbed once: re-stubbing the mock while a file read thread is still calling postProcess on it races Mockito
    private final AtomicReference<Expectation> matchedExpectation = new AtomicReference<>();
    private File textFile;
    private File binaryFile;
    private byte[] binaryBytes;
    private String mocksDirectory;

    @Before
    public void createHandler() throws Exception {
        File mocks = temporaryFolder.newFolder("mocks");
        mocksDirectory = mocks.getAbsolutePath() + "/";
        textFile = new File(mocks, "text.json");
        Files.write(textFile.toPath(), TEXT.getBytes(StandardCharsets.UTF_8));
        binaryBytes = new byte[256 * 3];
        for (int i = 0; i < binaryBytes.length; i++) {
            binaryBytes[i] = (byte) i;
        }
        binaryFile = new File(mocks, "image.png");
        Files.write(binaryFile.toPath(), binaryBytes);
        Files.write(new File(mocks, "alice.json").toPath(), "{\"user\":\"alice\",\"method\":\"{{ request.method }}\"}".getBytes(StandardCharsets.UTF_8));
        Files.write(new File(temporaryFolder.getRoot(), "secret.json").toPath(), "top-secret".getBytes(StandardCharsets.UTF_8));

        Configuration configuration = configuration();
        MockServerLogger mockServerLogger = new MockServerLogger(HttpActionHandlerFileBodyReadTest.class);
        scheduler = new Scheduler(configuration, mockServerLogger);
        httpState = mock(HttpState.class);
        when(httpState.getScheduler()).thenReturn(scheduler);
        when(httpState.getMockServerLogger()).thenReturn(mockServerLogger);
        when(httpState.getUniqueLoopPreventionHeaderName()).thenReturn("x-forwarded-by");
        when(httpState.getUniqueLoopPreventionHeaderValue()).thenReturn("MockServer_file_body_test");
        when(httpState.getCrudDispatcher()).thenReturn(new CrudDispatcher());
        when(httpState.firstMatchingExpectation(any(HttpRequest.class))).thenAnswer(invocation -> matchedExpectation.get());
        doAnswer(invocation -> postProcessed.incrementAndGet()).when(httpState).postProcess(any(Expectation.class));
        actionHandler = new HttpActionHandler(configuration, null, httpState, null, null);
    }

    @After
    public void stopScheduler() {
        scheduler.shutdown();
    }

    @Test
    public void shouldReadFileBodyOnFileReadPool() throws Exception {
        Written written = respondWith(new FileBody(textFile.getAbsolutePath(), MediaType.APPLICATION_JSON_UTF_8), request("/file"), channel(), false);

        assertThat(written.thread(), startsWith("MockServer-FileRead"));
        assertThat(written.response().getBodyAsRawBytes(), is(TEXT.getBytes(StandardCharsets.UTF_8)));
        assertThat(postProcessedEventually(1), is(true));
    }

    @Test
    public void shouldReadFileBodyOnFileReadPoolWhenCalledSynchronouslyOnAChannelAsHttp3Does() throws Exception {
        Written written = respondWith(new FileBody(textFile.getAbsolutePath(), MediaType.APPLICATION_JSON_UTF_8), request("/file"), channel(), true);

        assertThat(written.thread(), startsWith("MockServer-FileRead"));
        assertThat(written.response().getBodyAsRawBytes(), is(TEXT.getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    public void shouldReadFileBodyInlineWhenCalledSynchronouslyWithoutAChannelAsServletsDo() throws Exception {
        Written written = respondWith(new FileBody(textFile.getAbsolutePath(), MediaType.APPLICATION_JSON_UTF_8), request("/file"), null, true);

        assertThat(written.thread(), is(Thread.currentThread().getName()));
        assertThat(written.response().getBodyAsRawBytes(), is(TEXT.getBytes(StandardCharsets.UTF_8)));
        assertThat(scheduler.getFileReadExecutor(), is(nullValue()));
    }

    @Test
    public void shouldAnswerResponseWithoutFileBodyOnCallingThreadWithoutCreatingFileReadPool() throws Exception {
        Expectation expectation = new Expectation(request("/string")).thenRespond(response("inline body"));
        Written written = dispatch(expectation, request("/string"), channel(), false);

        assertThat(written.thread(), is(Thread.currentThread().getName()));
        assertThat(written.response().getBodyAsString(), is("inline body"));
        assertThat(scheduler.getFileReadExecutor(), is(nullValue()));
    }

    @Test
    public void shouldWriteSameResponseAsInlineReadForEveryFileBodyShape() throws Exception {
        FileBody[] bodies = {
            new FileBody(textFile.getAbsolutePath(), MediaType.APPLICATION_JSON_UTF_8),
            new FileBody(textFile.getAbsolutePath(), MediaType.parse("text/plain; charset=utf-8")),
            new FileBody(binaryFile.getAbsolutePath(), MediaType.parse("image/png")),
            new FileBody(binaryFile.getAbsolutePath()),
            new FileBody(mocksDirectory + "alice.json", MediaType.APPLICATION_JSON, HttpTemplate.TemplateType.MUSTACHE),
            new FileBody(mocksDirectory + "{{ request.queryStringParameters.name.0 }}.json", MediaType.APPLICATION_JSON, HttpTemplate.TemplateType.MUSTACHE).withTemplatedFilePath(true),
            new FileBody(mocksDirectory + "missing.json", MediaType.APPLICATION_JSON),
            new FileBody(mocksDirectory + "{{ request.queryStringParameters.name.0 }}.json", MediaType.APPLICATION_JSON, HttpTemplate.TemplateType.MUSTACHE).withTemplatedFilePath(true)
        };
        String[] names = {"alice", "alice", "alice", "alice", "alice", "alice", "alice", "../secret"};
        for (int i = 0; i < bodies.length; i++) {
            HttpRequest httpRequest = request("/file").withMethod("PUT").withQueryStringParameter("name", names[i]);
            Written inline = respondWith(bodies[i], httpRequest, null, true);
            Written offLoop = respondWith(bodies[i], httpRequest, channel(), false);

            assertThat("thread for " + bodies[i], offLoop.thread(), startsWith("MockServer-FileRead"));
            assertThat("status for " + bodies[i], offLoop.response().getStatusCode(), is(inline.response().getStatusCode()));
            assertThat("reason for " + bodies[i], offLoop.response().getReasonPhrase(), is(inline.response().getReasonPhrase()));
            assertThat("headers for " + bodies[i], offLoop.response().getHeaderList(), is(inline.response().getHeaderList()));
            assertThat("body type for " + bodies[i], offLoop.response().getBody().getClass(), is(inline.response().getBody().getClass()));
            assertThat("content type for " + bodies[i], offLoop.response().getBody().getContentType(), is(inline.response().getBody().getContentType()));
            assertThat("body for " + bodies[i], offLoop.response().getBodyAsRawBytes(), is(inline.response().getBodyAsRawBytes()));
        }
    }

    @Test
    public void shouldServeBinaryFileBytesUnchanged() throws Exception {
        Written written = respondWith(new FileBody(binaryFile.getAbsolutePath(), MediaType.parse("image/png")), request("/file"), channel(), false);

        assertThat(written.response().getBody() instanceof BinaryBody, is(true));
        assertThat(written.response().getBodyAsRawBytes(), is(binaryBytes));
    }

    @Test
    public void shouldAnswerMissingFileWithSameServerErrorOffTheEventLoop() throws Exception {
        Written written = respondWith(new FileBody(mocksDirectory + "missing.json", MediaType.APPLICATION_JSON), request("/file"), channel(), false);

        assertThat(written.thread(), startsWith("MockServer-FileRead"));
        assertThat(written.response().getStatusCode(), is(500));
        assertThat(written.response().getBodyAsString(), is("Error reading file for response body"));
        assertThat(postProcessedEventually(1), is(true));
    }

    @Test
    public void shouldRefuseTemplatedPathOutsideItsDirectoryOffTheEventLoop() throws Exception {
        FileBody escaping = new FileBody(mocksDirectory + "{{ request.queryStringParameters.name.0 }}.json", MediaType.APPLICATION_JSON, HttpTemplate.TemplateType.MUSTACHE).withTemplatedFilePath(true);

        Written written = respondWith(escaping, request("/file").withQueryStringParameter("name", "../secret"), channel(), false);

        assertThat(written.response().getStatusCode(), is(500));
        assertThat(written.response().getBodyAsString(), is("Error reading file for response body"));
    }

    @Test
    public void shouldAnswerServiceUnavailableWhenFileReadQueueIsFull() throws Exception {
        scheduler.maxQueuedFileReads(0);

        Written written = respondWith(new FileBody(textFile.getAbsolutePath(), MediaType.APPLICATION_JSON_UTF_8), request("/file"), channel(), false);

        assertThat(written.response().getStatusCode(), is(503));
        assertThat(written.response().getFirstHeader("Retry-After"), is("1"));
        assertThat(written.response().getBodyAsString(), is("MockServer is overloaded, too many file reads are already waiting"));
        assertThat(scheduler.getOverloadRejectionCount(Scheduler.OverloadReason.FILE_BODIES), is(1L));
        assertThat(scheduler.getOverloadRejectionCount(Scheduler.OverloadReason.STATIC_FILES), is(0L));
        assertThat(postProcessedEventually(1), is(true));
    }

    @Test
    public void shouldReadEarlyMatchedFileBodyOnFileReadPool() throws Exception {
        Expectation expectation = new Expectation(request("/early")).thenRespond(response().withBody(new FileBody(textFile.getAbsolutePath(), MediaType.APPLICATION_JSON_UTF_8)));
        ResponseWriter responseWriter = mock(ResponseWriter.class);
        CompletableFuture<Written> written = capture(responseWriter);

        actionHandler.processEarlyAction(request("/early"), expectation, channel(), responseWriter, false);

        Written result = written.get(10, TimeUnit.SECONDS);
        assertThat(result.thread(), startsWith("MockServer-FileRead"));
        assertThat(result.response().getBodyAsRawBytes(), is(TEXT.getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    public void shouldCreateFileReadPoolOnlyOnFirstFileBody() throws Exception {
        dispatch(new Expectation(request("/string")).thenRespond(response("inline")), request("/string"), channel(), false);
        assertThat(scheduler.getFileReadExecutor(), is(nullValue()));

        respondWith(new FileBody(textFile.getAbsolutePath(), MediaType.APPLICATION_JSON_UTF_8), request("/file"), channel(), false);
        assertThat(scheduler.getFileReadExecutor(), is(notNullValue()));
    }

    private Written respondWith(FileBody fileBody, HttpRequest httpRequest, ChannelHandlerContext ctx, boolean synchronous) throws Exception {
        Expectation expectation = new Expectation(request(httpRequest.getPath().getValue())).thenRespond(response().withBody(fileBody));
        return dispatch(expectation, httpRequest, ctx, synchronous);
    }

    private Written dispatch(Expectation expectation, HttpRequest httpRequest, ChannelHandlerContext ctx, boolean synchronous) throws Exception {
        matchedExpectation.set(expectation);
        ResponseWriter responseWriter = mock(ResponseWriter.class);
        CompletableFuture<Written> written = capture(responseWriter);
        actionHandler.processAction(httpRequest, responseWriter, ctx, Collections.emptySet(), false, synchronous);
        return written.get(10, TimeUnit.SECONDS);
    }

    private static CompletableFuture<Written> capture(ResponseWriter responseWriter) {
        CompletableFuture<Written> written = new CompletableFuture<>();
        doAnswer(invocation -> {
            written.complete(new Written(Thread.currentThread().getName(), invocation.getArgument(1)));
            return null;
        }).when(responseWriter).writeResponse(any(HttpRequest.class), any(HttpResponse.class), anyBoolean());
        return written;
    }

    private boolean postProcessedEventually(int count) throws InterruptedException {
        CountDownLatch never = new CountDownLatch(1);
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (postProcessed.get() < count && System.nanoTime() < deadline) {
            never.await(10, TimeUnit.MILLISECONDS);
        }
        return postProcessed.get() == count;
    }

    private static ChannelHandlerContext channel() {
        return mock(ChannelHandlerContext.class);
    }

    private record Written(String thread, HttpResponse response) {
    }
}
