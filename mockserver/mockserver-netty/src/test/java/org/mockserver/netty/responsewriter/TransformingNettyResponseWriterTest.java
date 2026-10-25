package org.mockserver.netty.responsewriter;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPromise;
import io.netty.channel.embedded.EmbeddedChannel;
import org.junit.After;
import org.junit.Test;
import org.mockserver.configuration.Configuration;
import org.mockserver.extension.DataPlaneHooks;
import org.mockserver.extension.ExtensionRegistry;
import org.mockserver.extension.ResponseTransformer;
import org.mockserver.extension.TransformContext;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.model.HttpRequest;
import org.mockserver.model.HttpResponse;
import org.mockserver.model.StreamingBody;
import org.mockserver.responsewriter.TransformingWriter;
import org.mockserver.scheduler.Scheduler;
import org.slf4j.event.Level;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiFunction;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;
import static org.mockserver.configuration.Configuration.configuration;
import static org.mockserver.model.HttpRequest.request;
import static org.mockserver.model.HttpResponse.response;

/**
 * The Netty writer built when a response transformer is loaded: once its exchange reaches the data plane, the first
 * final response claims it and later ones are dropped and counted; API responses, the data-plane CORS preflight,
 * interim responses and failure responses are written without the transformers; writes reach the channel in the
 * order they were made, whichever thread finishes them.
 */
public class TransformingNettyResponseWriterTest {

    private final Configuration configuration = configuration().logLevel(Level.WARN).enableCORSForAllResponses(true);
    private final MockServerLogger mockServerLogger = new MockServerLogger(configuration, TransformingNettyResponseWriterTest.class);
    private final Scheduler scheduler = new Scheduler(configuration, mockServerLogger);
    private final Recorder recorder = new Recorder();
    private final EmbeddedChannel channel = new EmbeddedChannel(recorder, new ChannelInboundHandlerAdapter());
    private final ChannelHandlerContext ctx = channel.pipeline().lastContext();
    private final List<ExtensionRegistry> registries = new ArrayList<>();

    @After
    public void stop() {
        registries.forEach(ExtensionRegistry::stop);
        channel.finishAndReleaseAll();
        scheduler.shutdown();
    }

    public static class Transformer implements ResponseTransformer {
        final AtomicInteger calls = new AtomicInteger();
        final List<TransformContext> contexts = new ArrayList<>();
        final List<HttpResponse> seen = new ArrayList<>();
        private final BiFunction<HttpResponse, TransformContext, HttpResponse> transform;

        Transformer(BiFunction<HttpResponse, TransformContext, HttpResponse> transform) {
            this.transform = transform;
        }

        @Override
        public String name() {
            return "writer-test";
        }

        @Override
        public synchronized HttpResponse transform(HttpRequest request, HttpResponse response, TransformContext context) {
            calls.incrementAndGet();
            contexts.add(context);
            seen.add(response);
            return transform.apply(response, context);
        }
    }

    private static final class Recorder extends ChannelOutboundHandlerAdapter {
        final ConcurrentLinkedQueue<HttpResponse> written = new ConcurrentLinkedQueue<>();

        @Override
        public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) {
            if (msg instanceof HttpResponse) {
                written.add((HttpResponse) msg);
            }
            promise.setSuccess();
        }
    }

    private DataPlaneHooks hooks(ResponseTransformer transformer) {
        ExtensionRegistry registry = ExtensionRegistry.start(configuration().extensionDataPlaneTimeoutMillis(2_000L).extensions(() -> transformer), mockServerLogger);
        registries.add(registry);
        return registry.dataPlaneHooks();
    }

    private TransformingNettyResponseWriter writer(DataPlaneHooks hooks) {
        return new TransformingNettyResponseWriter(configuration, mockServerLogger, ctx, scheduler, null, hooks);
    }

    private static HttpRequest dataRequest() {
        return (HttpRequest) request("/data").withMethod("GET").withKeepAlive(true).withLogCorrelationId("correlation-1");
    }

    private List<HttpResponse> awaitWritten(int count) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (recorder.written.size() < count && System.nanoTime() < deadline) {
            Thread.sleep(5);
        }
        // nothing more may arrive: every write here completes well within this
        Thread.sleep(50);
        return new ArrayList<>(recorder.written);
    }

    @Test
    public void shouldTransformTheFinalResponseAndAddCorsAfterTheTransformer() throws Exception {
        Transformer transformer = new Transformer((response, context) -> response.withHeader("x-transformed", "yes"));
        TransformingNettyResponseWriter writer = writer(hooks(transformer));
        HttpRequest request = dataRequest().withHeader("Origin", "https://example.com");
        writer.markDataPlane(request);

        writer.writeResponse(request, (HttpResponse) response("body").setExpectationId("expectation-1"), false);

        List<HttpResponse> written = awaitWritten(1);
        assertThat(written.size(), is(1));
        assertThat(written.get(0).getFirstHeader("x-transformed"), is("yes"));
        assertThat("CORS is added after the transformer", written.get(0).getFirstHeader("access-control-allow-origin").isEmpty(), is(false));
        assertThat("the transformer never sees the CORS headers", transformer.seen.get(0).getFirstHeader("access-control-allow-origin"), is(""));
        assertThat(transformer.contexts.get(0).expectationId(), is("expectation-1"));
        assertThat(transformer.contexts.get(0).isGenerated(), is(false));
        assertThat(transformer.contexts.get(0).correlationId(), is("correlation-1"));
        assertThat(writer.isClaimed(), is(true));
    }

    @Test
    public void shouldDropASecondFinalResponseAndCountIt() throws Exception {
        Transformer transformer = new Transformer((response, context) -> response);
        DataPlaneHooks hooks = hooks(transformer);
        TransformingNettyResponseWriter writer = writer(hooks);
        HttpRequest request = dataRequest();
        writer.markDataPlane(request);

        writer.writeResponse(request, response("first"), false);
        writer.writeResponse(request, response("second"), false);
        writer.writeUntransformed(request, response("third"));

        List<HttpResponse> written = awaitWritten(1);
        assertThat(written.size(), is(1));
        assertThat(written.get(0).getBodyAsString(), is("first"));
        assertThat(transformer.calls.get(), is(1));
        assertThat(hooks.droppedWriteCount(), is(2L));
    }

    @Test
    public void shouldNotTransformAnApiResponse() throws Exception {
        Transformer transformer = new Transformer((response, context) -> response.withHeader("x-transformed", "yes"));
        TransformingNettyResponseWriter writer = writer(hooks(transformer));
        HttpRequest request = dataRequest();
        writer.markDataPlane(request);

        writer.writeResponse(request, response("api"), true);

        List<HttpResponse> written = awaitWritten(1);
        assertThat(written.size(), is(1));
        assertThat(written.get(0).getFirstHeader("x-transformed"), is(""));
        assertThat(transformer.calls.get(), is(0));
    }

    @Test
    public void shouldNotTransformTheDataPlaneCorsPreflight() throws Exception {
        Transformer transformer = new Transformer((response, context) -> response.withHeader("x-transformed", "yes"));
        TransformingNettyResponseWriter writer = writer(hooks(transformer));
        HttpRequest request = dataRequest().withMethod("OPTIONS").withHeader("Origin", "https://example.com").withHeader("Access-Control-Request-Method", "PUT");
        writer.markDataPlane(request);

        writer.writeDataPlanePreflightResponse(request);
        writer.writeResponse(request, response("late"), false);

        List<HttpResponse> written = awaitWritten(1);
        assertThat(written.size(), is(1));
        assertThat(written.get(0).getStatusCode(), is(200));
        assertThat(written.get(0).getFirstHeader("x-transformed"), is(""));
        assertThat("the preflight claims the exchange", transformer.calls.get(), is(0));
    }

    @Test
    public void shouldWriteAsWithNoExtensionBeforeTheExchangeReachesTheDataPlane() throws Exception {
        Transformer transformer = new Transformer((response, context) -> response.withHeader("x-transformed", "yes"));
        TransformingNettyResponseWriter writer = writer(hooks(transformer));
        HttpRequest request = dataRequest();

        writer.writeResponse(request, response().withStatusCode(401), false);

        List<HttpResponse> written = awaitWritten(1);
        assertThat(written.size(), is(1));
        assertThat(written.get(0).getFirstHeader("x-transformed"), is(""));
        assertThat(writer.isClaimed(), is(false));
        assertThat(transformer.calls.get(), is(0));
    }

    @Test
    public void shouldAnswerAFailingTransformerWithAnUntransformed500() throws Exception {
        Transformer transformer = new Transformer((response, context) -> {
            throw new IllegalStateException("broken transformer");
        });
        DataPlaneHooks hooks = hooks(transformer);
        TransformingNettyResponseWriter writer = writer(hooks);
        HttpRequest request = dataRequest();
        writer.markDataPlane(request);

        writer.writeResponse(request, response("body"), false);
        List<HttpResponse> written = awaitWritten(1);
        writer.writeResponse(request, response("after the failure"), false);

        assertThat(written.size(), is(1));
        assertThat(written.get(0).getStatusCode(), is(500));
        assertThat(written.get(0).getBodyAsString(), containsString("extension writer-test responseTransformer failed (threw)"));
        assertThat("the failure response is never transformed", transformer.calls.get(), is(1));
        assertThat(awaitWritten(1).size(), is(1));
        assertThat(hooks.droppedWriteCount(), is(1L));
    }

    @Test
    public void shouldAnswerAnUnansweredExchangeWithAnUntransformedFailure() throws Exception {
        Transformer transformer = new Transformer((response, context) -> response.withHeader("x-transformed", "yes"));
        DataPlaneHooks hooks = hooks(transformer);
        TransformingNettyResponseWriter writer = writer(hooks);
        HttpRequest request = dataRequest();
        writer.markDataPlane(request);

        writer.failUnanswered(new RuntimeException("continuation threw"));
        writer.writeResponse(request, response("scheduled before the throw"), false);

        List<HttpResponse> written = awaitWritten(1);
        assertThat(written.size(), is(1));
        assertThat(written.get(0).getStatusCode(), is(500));
        assertThat(written.get(0).getFirstHeader("x-transformed"), is(""));
        assertThat(transformer.calls.get(), is(0));
        assertThat(hooks.droppedWriteCount(), is(1L));
    }

    @Test
    public void shouldNotAnswerAnExchangeAlreadyClaimed() throws Exception {
        Transformer transformer = new Transformer((response, context) -> response);
        TransformingNettyResponseWriter writer = writer(hooks(transformer));
        HttpRequest request = dataRequest();
        writer.markDataPlane(request);

        writer.writeResponse(request, response("answer"), false);
        writer.failUnanswered(new RuntimeException("after the answer"));

        List<HttpResponse> written = awaitWritten(1);
        assertThat(written.size(), is(1));
        assertThat(written.get(0).getBodyAsString(), is("answer"));
    }

    @Test
    public void shouldWriteAnInterimResponseUntransformedAndBeforeTheFinalOne() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        Transformer transformer = new Transformer((response, context) -> {
            await(release);
            return response.withHeader("x-transformed", "yes");
        });
        TransformingNettyResponseWriter writer = writer(hooks(transformer));
        HttpRequest request = dataRequest();
        writer.markDataPlane(request);

        writer.writeResponse(request, response().withStatusCode(103).withHeader("link", "</style.css>; rel=preload"), false);
        writer.writeResponse(request, response("final"), false);
        release.countDown();

        List<HttpResponse> written = awaitWritten(2);
        List<Integer> statuses = new ArrayList<>();
        written.forEach(response -> statuses.add(response.getStatusCode()));
        assertThat(statuses, contains(103, 200));
        assertThat(written.get(0).getFirstHeader("x-transformed"), is(""));
        assertThat(written.get(1).getFirstHeader("x-transformed"), is("yes"));
        assertThat("an interim response is never transformed", transformer.calls.get(), is(1));
    }

    @Test
    public void shouldKeepTheWriteOrderWhenTheTransformerFinishesLast() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        Transformer transformer = new Transformer((response, context) -> {
            await(release);
            return response;
        });
        DataPlaneHooks hooks = hooks(transformer);
        TransformingNettyResponseWriter writer = writer(hooks);
        HttpRequest request = dataRequest();
        writer.markDataPlane(request);

        writer.writeResponse(request, response("transformed slowly"), false);
        writer.writeUntransformed(request, response().withStatusCode(100));
        assertThat("nothing is written before the transformer returns", awaitWritten(0).size(), is(0));
        release.countDown();

        List<HttpResponse> written = awaitWritten(2);
        assertThat(written.size(), is(2));
        assertThat(written.get(0).getBodyAsString(), is("transformed slowly"));
        assertThat(written.get(1).getStatusCode(), is(100));
    }

    @Test
    public void shouldTellTheTransformerAGeneratedResponseIsGenerated() throws Exception {
        Transformer transformer = new Transformer((response, context) -> response.withHeader("x-generated", String.valueOf(context.isGenerated())));
        TransformingNettyResponseWriter writer = writer(hooks(transformer));
        HttpRequest request = dataRequest();
        writer.markDataPlane(request);

        TransformingWriter.markGenerated(writer);
        writer.writeResponse(request, response().withStatusCode(404), false);

        List<HttpResponse> written = awaitWritten(1);
        assertThat(written.get(0).getFirstHeader("x-generated"), is("true"));
    }

    @Test
    public void shouldShowAStreamedResponseHeadOnlyAndKeepItsBody() throws Exception {
        Transformer transformer = new Transformer((response, context) -> response.withHeader("x-streamed", String.valueOf(context.isStreamed())));
        TransformingNettyResponseWriter writer = writer(hooks(transformer));
        HttpRequest request = dataRequest();
        writer.markDataPlane(request);
        StreamingBody streamingBody = new StreamingBody(1024);

        writer.writeResponse(request, response().withStatusCode(200).withStreamingBody(streamingBody), false);

        assertThat("the transformer is shown no streaming body", awaitTransformed(transformer).getStreamingBody(), is(nullValue()));
        streamingBody.complete();
        assertThat(transformer.contexts.get(0).isStreamed(), is(true));
    }

    @Test
    public void shouldCloseTheUpstreamOfAStreamedResponseWhoseTransformerFails() throws Exception {
        Transformer transformer = new Transformer((response, context) -> {
            throw new IllegalStateException("broken transformer");
        });
        TransformingNettyResponseWriter writer = writer(hooks(transformer));
        HttpRequest request = dataRequest();
        writer.markDataPlane(request);
        StreamingBody streamingBody = new StreamingBody(1024);
        AtomicBoolean upstreamClosed = new AtomicBoolean();
        streamingBody.setUpstreamCloser(() -> upstreamClosed.set(true));

        writer.writeResponse(request, response().withStatusCode(200).withStreamingBody(streamingBody), false);

        List<HttpResponse> written = awaitWritten(1);
        assertThat(written.get(0).getStatusCode(), is(500));
        assertThat(written.get(0).getStreamingBody(), is(nullValue()));
        assertThat(upstreamClosed.get(), is(true));
    }

    @Test
    public void shouldWriteOneFinalResponseWhenManyThreadsWriteAtOnce() throws Exception {
        Transformer transformer = new Transformer((response, context) -> response);
        DataPlaneHooks hooks = hooks(transformer);
        int threads = 16;
        for (int round = 0; round < 20; round++) {
            recorder.written.clear();
            TransformingNettyResponseWriter writer = writer(hooks);
            HttpRequest request = dataRequest();
            writer.markDataPlane(request);
            CyclicBarrier start = new CyclicBarrier(threads);
            List<Thread> writers = new ArrayList<>();
            for (int t = 0; t < threads; t++) {
                int number = t;
                Thread thread = new Thread(() -> {
                    try {
                        start.await(10, TimeUnit.SECONDS);
                    } catch (Exception e) {
                        throw new IllegalStateException(e);
                    }
                    if (number % 2 == 0) {
                        writer.writeResponse(request, response("writer " + number), false);
                    } else {
                        writer.writeUntransformed(request, response("writer " + number));
                    }
                });
                thread.start();
                writers.add(thread);
            }
            for (Thread thread : writers) {
                thread.join(10_000);
            }
            assertThat(awaitWritten(1).size(), is(1));
        }
        assertThat(hooks.droppedWriteCount(), is(20L * (threads - 1)));
    }

    private static HttpResponse awaitTransformed(Transformer transformer) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            synchronized (transformer) {
                if (!transformer.seen.isEmpty()) {
                    return transformer.seen.get(0);
                }
            }
            Thread.sleep(5);
        }
        throw new AssertionError("the transformer was not called");
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
