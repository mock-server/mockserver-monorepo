package org.mockserver.netty.http3;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPromise;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http3.Http3Headers;
import io.netty.handler.codec.http3.Http3HeadersFrame;
import io.netty.util.ReferenceCountUtil;
import org.junit.After;
import org.junit.Test;
import org.mockserver.configuration.Configuration;
import org.mockserver.extension.DataPlaneHooks;
import org.mockserver.extension.ExtensionRegistry;
import org.mockserver.extension.ResponseTransformer;
import org.mockserver.extension.TransformContext;
import org.mockserver.grpc.GrpcStatusMapper;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.model.HttpRequest;
import org.mockserver.model.HttpResponse;
import org.mockserver.responsewriter.TransformingWriter;
import org.mockserver.scheduler.Scheduler;
import org.slf4j.event.Level;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.mockserver.configuration.Configuration.configuration;
import static org.mockserver.model.HttpRequest.request;
import static org.mockserver.model.HttpResponse.response;

/**
 * The HTTP/3 writers built when a response transformer is loaded: a claimed exchange counts as a started response, so
 * the handler's failure path never writes a second one after a transformer times out; and over gRPC an elapsed
 * {@code grpc-timeout} wins over a response the transformers are still working on, leaving one terminal frame.
 */
public class TransformingHttp3ResponseWriterTest {

    private final Configuration configuration = configuration().logLevel(Level.ERROR);
    private final MockServerLogger mockServerLogger = new MockServerLogger(configuration, TransformingHttp3ResponseWriterTest.class);
    private final Scheduler scheduler = new Scheduler(configuration, mockServerLogger);
    private final Recorder recorder = new Recorder();
    private final EmbeddedChannel channel = new EmbeddedChannel(recorder, new ChannelInboundHandlerAdapter());
    private final ChannelHandlerContext ctx = channel.pipeline().lastContext();
    private final List<ExtensionRegistry> registries = new ArrayList<>();
    private final CountDownLatch release = new CountDownLatch(1);

    @After
    public void stop() {
        release.countDown();
        registries.forEach(ExtensionRegistry::stop);
        channel.finishAndReleaseAll();
        scheduler.shutdown();
    }

    // every HEADERS frame written, with :status or grpc-status; DATA frames are released, promises left pending so the
    // QUIC-only end-of-stream listeners never run on this channel
    private static final class Recorder extends ChannelOutboundHandlerAdapter {
        final ConcurrentLinkedQueue<Http3Headers> headers = new ConcurrentLinkedQueue<>();

        @Override
        public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) {
            if (msg instanceof Http3HeadersFrame) {
                headers.add(((Http3HeadersFrame) msg).headers());
            }
            ReferenceCountUtil.release(msg);
        }
    }

    public static class Transformer implements ResponseTransformer {
        final AtomicInteger calls = new AtomicInteger();
        final CountDownLatch entered = new CountDownLatch(1);
        private final CountDownLatch release;

        Transformer(CountDownLatch release) {
            this.release = release;
        }

        @Override
        public String name() {
            return "h3-writer-test";
        }

        @Override
        public HttpResponse transform(HttpRequest request, HttpResponse response, TransformContext context) throws Exception {
            calls.incrementAndGet();
            entered.countDown();
            if (release != null) {
                release.await();
            }
            return response.withHeader("x-transformed", "yes");
        }
    }

    private DataPlaneHooks hooks(ResponseTransformer transformer, long budgetMillis) {
        ExtensionRegistry registry = ExtensionRegistry.start(configuration().extensionDataPlaneTimeoutMillis(budgetMillis).extensions(() -> transformer), mockServerLogger);
        registries.add(registry);
        return registry.dataPlaneHooks();
    }

    private TransformingHttp3GrpcResponseWriter grpcWriter(DataPlaneHooks hooks) {
        return new TransformingHttp3GrpcResponseWriter(configuration, mockServerLogger, ctx, null, null, null, null, scheduler, hooks);
    }

    private static HttpRequest dataRequest() {
        return (HttpRequest) request("/data").withMethod("GET").withLogCorrelationId("correlation-1");
    }

    private static HttpRequest grpcRequest(String grpcTimeout) {
        return (HttpRequest) request("/com.example.grpc.GreetingService/Greeting")
            .withMethod("POST")
            .withHeader("content-type", "application/grpc")
            .withHeader("grpc-timeout", grpcTimeout)
            .withLogCorrelationId("correlation-1");
    }

    private List<Http3Headers> awaitHeaders(int count) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (recorder.headers.size() < count && System.nanoTime() < deadline) {
            Thread.sleep(5);
        }
        // nothing more may arrive: every write here completes well within this
        Thread.sleep(100);
        return new ArrayList<>(recorder.headers);
    }

    private static void awaitTrue(java.util.function.BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) {
            Thread.sleep(5);
        }
    }

    @Test
    public void shouldTransformAnHttp3Response() throws Exception {
        TransformingHttp3ResponseWriter writer = new TransformingHttp3ResponseWriter(configuration, mockServerLogger, ctx, scheduler, hooks(new Transformer(null), 2_000L));
        HttpRequest request = dataRequest();
        writer.markDataPlane(request);

        writer.writeResponse(request, response("body"), false);

        List<Http3Headers> written = awaitHeaders(1);
        assertThat(written.size(), is(1));
        assertThat(written.get(0).status().toString(), is("200"));
        assertThat(written.get(0).get("x-transformed").toString(), is("yes"));
    }

    @Test
    public void shouldWriteOneResponseWhenTheTransformerTimesOutAndTheHandlerAlsoAnswersAFailure() throws Exception {
        Transformer transformer = new Transformer(release);
        DataPlaneHooks hooks = hooks(transformer, 200L);
        TransformingHttp3ResponseWriter writer = new TransformingHttp3ResponseWriter(configuration, mockServerLogger, ctx, scheduler, hooks);
        HttpRequest request = dataRequest();
        writer.markDataPlane(request);
        writer.writeResponse(request, response("answer"), false);
        assertThat(transformer.entered.await(10, TimeUnit.SECONDS), is(true));

        // the handler's failure path, as when processAction throws after the write it scheduled
        assertThat("a claimed exchange counts as started", writer.isResponseStarted(), is(true));
        TransformingWriter.failUnanswered(mockServerLogger, writer, request, new IllegalStateException("thrown after the write"));

        awaitTrue(() -> hooks.timeoutCount() == 1);
        List<Http3Headers> written = awaitHeaders(1);
        assertThat(written.size(), is(1));
        assertThat("the transformer's timeout answers, once", written.get(0).status().toString(), is("500"));
        assertThat(written.get(0).contains("x-transformed"), is(false));
    }

    @Test
    public void shouldDropAResponseWrittenAfterTheHandlerAnsweredAFailure() throws Exception {
        Transformer transformer = new Transformer(null);
        DataPlaneHooks hooks = hooks(transformer, 2_000L);
        TransformingHttp3ResponseWriter writer = new TransformingHttp3ResponseWriter(configuration, mockServerLogger, ctx, scheduler, hooks);
        HttpRequest request = dataRequest();

        TransformingWriter.failUnanswered(mockServerLogger, writer, request, new IllegalStateException("thrown before the write"));
        writer.writeResponse(request, response("late"), false);

        List<Http3Headers> written = awaitHeaders(1);
        assertThat(written.size(), is(1));
        assertThat(written.get(0).status().toString(), is("500"));
        assertThat("the late response never reaches the transformers", transformer.calls.get(), is(0));
        assertThat(hooks.droppedWriteCount(), is(1L));
    }

    @Test
    public void shouldNotAnswerAFailureOnAStreamWhoseResponseWasWrittenDirectly() throws Exception {
        TransformingHttp3ResponseWriter writer = new TransformingHttp3ResponseWriter(configuration, mockServerLogger, ctx, scheduler, hooks(new Transformer(null), 2_000L));
        HttpRequest request = dataRequest();
        writer.markDataPlane(request);
        writer.respondingDirectly();

        TransformingWriter.failUnanswered(mockServerLogger, writer, request, new IllegalStateException("thrown after an SSE response started"));

        assertThat(awaitHeaders(0).size(), is(0));
        assertThat(writer.isResponseStarted(), is(true));
    }

    @Test
    public void shouldLetTheGrpcDeadlineWinOverAResponseTheTransformersAreStillWorkingOn() throws Exception {
        Transformer transformer = new Transformer(release);
        DataPlaneHooks hooks = hooks(transformer, 10_000L);
        TransformingHttp3GrpcResponseWriter writer = grpcWriter(hooks);
        HttpRequest request = grpcRequest("100m");
        writer.scheduleDeadline(request);
        writer.markDataPlane(request);
        writer.writeResponse(request, response().withStatusCode(200).withHeader("grpc-status", "0"), false);
        assertThat(transformer.entered.await(10, TimeUnit.SECONDS), is(true));

        channel.advanceTimeBy(200, TimeUnit.MILLISECONDS);
        channel.runScheduledPendingTasks();
        release.countDown();
        awaitTrue(() -> hooks.availableSlots() == hooks.maxInFlight());
        channel.runPendingTasks();

        List<Http3Headers> written = awaitHeaders(1);
        assertThat("only the deadline's trailers-only frame", written.size(), is(1));
        assertThat(written.get(0).get(GrpcStatusMapper.GRPC_STATUS_HEADER).toString(), is(String.valueOf(GrpcStatusMapper.GrpcStatusCode.DEADLINE_EXCEEDED.getCode())));
        assertThat(transformer.calls.get(), is(1));
    }

    @Test
    public void shouldDropAGrpcResponseMadeAfterTheDeadlineWithoutRunningTheTransformers() throws Exception {
        Transformer transformer = new Transformer(null);
        DataPlaneHooks hooks = hooks(transformer, 2_000L);
        TransformingHttp3GrpcResponseWriter writer = grpcWriter(hooks);
        HttpRequest request = grpcRequest("100m");
        writer.scheduleDeadline(request);
        writer.markDataPlane(request);

        channel.advanceTimeBy(200, TimeUnit.MILLISECONDS);
        channel.runScheduledPendingTasks();
        writer.writeResponse(request, response().withStatusCode(200).withHeader("grpc-status", "0"), false);

        List<Http3Headers> written = awaitHeaders(1);
        assertThat(written.size(), is(1));
        assertThat(written.get(0).get(GrpcStatusMapper.GRPC_STATUS_HEADER).toString(), is(String.valueOf(GrpcStatusMapper.GrpcStatusCode.DEADLINE_EXCEEDED.getCode())));
        assertThat(transformer.calls.get(), is(0));
        assertThat(hooks.droppedWriteCount(), is(1L));
    }

    @Test
    public void shouldNotWriteAGrpcErrorWhileTheTransformersHoldTheExchange() throws Exception {
        Transformer transformer = new Transformer(release);
        TransformingHttp3GrpcResponseWriter writer = grpcWriter(hooks(transformer, 10_000L));
        HttpRequest request = grpcRequest("10S");
        writer.markDataPlane(request);
        writer.writeResponse(request, response().withStatusCode(200).withHeader("grpc-status", "0"), false);
        assertThat(transformer.entered.await(10, TimeUnit.SECONDS), is(true));

        // the gRPC handler's failure path
        writer.writeErrorResponseIfUnanswered(GrpcStatusMapper.GrpcStatusCode.INTERNAL, "thrown after the write");
        release.countDown();

        List<Http3Headers> written = awaitHeaders(1);
        assertThat(written.size(), is(1));
        assertThat("the transformed response, not INTERNAL", written.get(0).get(GrpcStatusMapper.GRPC_STATUS_HEADER).toString(), is("0"));
        assertThat(written.get(0).get("x-transformed").toString(), is("yes"));
    }

    @Test
    public void shouldAnswerAnUnclaimedGrpcExchangeWithInternalAndDropTheLaterResponse() throws Exception {
        Transformer transformer = new Transformer(null);
        DataPlaneHooks hooks = hooks(transformer, 2_000L);
        TransformingHttp3GrpcResponseWriter writer = grpcWriter(hooks);
        HttpRequest request = grpcRequest("10S");

        TransformingWriter.failUnanswered(mockServerLogger, writer, request, new IllegalStateException("thrown before the write"));
        writer.writeResponse(request, response().withStatusCode(200).withHeader("grpc-status", "0"), false);

        List<Http3Headers> written = awaitHeaders(1);
        assertThat(written.size(), is(1));
        assertThat(written.get(0).get(GrpcStatusMapper.GRPC_STATUS_HEADER).toString(), is(String.valueOf(GrpcStatusMapper.GrpcStatusCode.INTERNAL.getCode())));
        assertThat(transformer.calls.get(), is(0));
        assertThat(hooks.droppedWriteCount(), is(1L));
    }
}
