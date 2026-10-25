package org.mockserver.netty;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.util.ReferenceCountUtil;
import org.junit.After;
import org.junit.Assume;
import org.junit.Test;
import org.mockserver.configuration.Configuration;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.model.HttpRequest;
import org.mockserver.model.HttpResponse;
import org.mockserver.netty.http3.Http3GrpcResponseWriter;
import org.mockserver.netty.http3.Http3ResponseWriter;
import org.mockserver.netty.responsewriter.NettyResponseWriter;
import org.mockserver.responsewriter.ResponseWriter;
import org.mockserver.scheduler.Scheduler;
import org.slf4j.event.Level;

import java.lang.management.ManagementFactory;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Supplier;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.mockserver.configuration.Configuration.configuration;
import static org.mockserver.model.HttpRequest.request;
import static org.mockserver.model.HttpResponse.response;

/**
 * Pins what each Netty writer costs today, so a data-plane hook added later must leave every writer built with no
 * hook loaded exactly as it is: the bytes of building each writer, the bytes of building one and writing a response
 * through it on an {@link EmbeddedChannel}, and every writer's instance fields. Measured with C1 only, in the
 * {@code allocation-tests-without-c2} execution, since C2 escape analysis could hide an added allocation; a
 * deliberate change must update the pins with its reason.
 */
public class NettyDataPlaneHooksAbsentCostTest {

    static final long NETTY_WRITER_BYTES = 64;
    static final long HTTP3_WRITER_BYTES = 56;
    static final long HTTP3_GRPC_WRITER_BYTES = 112;
    static final long NETTY_WRITER_AND_WRITE_BYTES = 496;
    static final long HTTP3_WRITER_AND_WRITE_BYTES = 888;

    private static final int WRITERS = 200_000;
    private static final int WRITES = 50_000;
    // the least of several measurements: background work can only add bytes, never remove the code's own
    private static final int ATTEMPTS = 3;
    private static final List<String> RESPONSE_WRITER_FIELDS = List.of(
        "ResponseWriter.configuration:Configuration",
        "ResponseWriter.mockServerLogger:MockServerLogger",
        "ResponseWriter.corsHeaders:CORSHeaders",
        "ResponseWriter.defaultResponseHeaders:DefaultResponseHeaders"
    );
    private static volatile Object sink;

    private final Configuration configuration = configuration().logLevel(Level.WARN);
    private final MockServerLogger mockServerLogger = new MockServerLogger(configuration, NettyDataPlaneHooksAbsentCostTest.class);
    private final Scheduler scheduler = new Scheduler(configuration, mockServerLogger, true);
    private final EmbeddedChannel channel = new EmbeddedChannel(new ChannelInboundHandlerAdapter());
    private final ChannelHandlerContext ctx = channel.pipeline().firstContext();

    @After
    public void closeChannel() {
        channel.finishAndReleaseAll();
        scheduler.shutdown();
    }

    @Test
    public void shouldPinTheBytesOfANettyWriter() {
        assertBytesPerWriter(() -> new NettyResponseWriter(configuration, mockServerLogger, ctx, scheduler, null), NETTY_WRITER_BYTES);
    }

    @Test
    public void shouldPinTheBytesOfAnHttp3Writer() {
        assertBytesPerWriter(() -> new Http3ResponseWriter(configuration, mockServerLogger, ctx), HTTP3_WRITER_BYTES);
    }

    @Test
    public void shouldPinTheBytesOfAnHttp3GrpcWriter() {
        assertBytesPerWriter(() -> new Http3GrpcResponseWriter(configuration, mockServerLogger, ctx, null, null, null, null), HTTP3_GRPC_WRITER_BYTES);
    }

    @Test
    public void shouldPinTheBytesOfANettyWriterAndItsWrite() {
        HttpRequest request = request("/").withMethod("GET").withKeepAlive(true);
        HttpResponse response = response("served");
        assertBytesPerWrite(() -> {
            new NettyResponseWriter(configuration, mockServerLogger, ctx, scheduler, null).writeResponse(request, response, false);
            ReferenceCountUtil.release(channel.readOutbound());
        }, NETTY_WRITER_AND_WRITE_BYTES);
    }

    @Test
    public void shouldPinTheBytesOfAnHttp3WriterAndItsWrite() {
        HttpRequest request = request("/").withMethod("GET").withKeepAlive(true);
        // no body and no trailers: the only static HTTP/3 write that needs no QUIC stream to finish on
        HttpResponse response = response().withStatusCode(204);
        assertBytesPerWrite(() -> {
            new Http3ResponseWriter(configuration, mockServerLogger, ctx).writeResponse(request, response, false);
            ReferenceCountUtil.release(channel.readOutbound());
        }, HTTP3_WRITER_AND_WRITE_BYTES);
    }

    @Test
    public void shouldPinTheFieldsOfEachNettyWriter() {
        assertThat(instanceFields(NettyResponseWriter.class), is(with(RESPONSE_WRITER_FIELDS,
            "NettyResponseWriter.ctx:ChannelHandlerContext",
            "NettyResponseWriter.scheduler:Scheduler",
            "NettyResponseWriter.startNanos:long",
            "NettyResponseWriter.inFlightRequest:InFlightRequest"
        )));
        assertThat(instanceFields(Http3ResponseWriter.class), is(with(RESPONSE_WRITER_FIELDS,
            "Http3ResponseWriter.ctx:ChannelHandlerContext",
            "Http3ResponseWriter.responseStarted:boolean"
        )));
        assertThat(instanceFields(Http3GrpcResponseWriter.class), is(with(RESPONSE_WRITER_FIELDS,
            "Http3GrpcResponseWriter.ctx:ChannelHandlerContext",
            "Http3GrpcResponseWriter.descriptorStore:GrpcProtoDescriptorStore",
            "Http3GrpcResponseWriter.grpcService:String",
            "Http3GrpcResponseWriter.grpcMethod:String",
            "Http3GrpcResponseWriter.webSocketClientRegistry:WebSocketClientRegistry",
            "Http3GrpcResponseWriter.completed:AtomicBoolean",
            "Http3GrpcResponseWriter.streamState:AtomicReference",
            "Http3GrpcResponseWriter.deadlineFuture:ScheduledFuture"
        )));
    }

    private static void assertBytesPerWriter(Supplier<ResponseWriter> newWriter, long pinned) {
        assumeC1Only("a writer's bytes");
        assertThat("bytes allocated per writer", least(WRITERS, () -> sink = newWriter.get()), is(pinned));
    }

    private static void assertBytesPerWrite(Runnable writeOne, long pinned) {
        assumeC1Only("a writer's bytes and its write");
        assertThat("bytes allocated per writer and its write", least(WRITES, writeOne), is(pinned));
    }

    private static long least(int operations, Runnable operation) {
        repeat(operations, operation);
        long least = Long.MAX_VALUE;
        for (int attempt = 0; attempt < ATTEMPTS; attempt++) {
            long before = allocatedBytes();
            repeat(operations, operation);
            least = Math.min(least, (allocatedBytes() - before) / operations);
        }
        return least;
    }

    private static void repeat(int operations, Runnable operation) {
        for (int i = 0; i < operations; i++) {
            operation.run();
        }
    }

    private static long allocatedBytes() {
        return ((com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean()).getThreadAllocatedBytes(Thread.currentThread().getId());
    }

    private static void assumeC1Only(String measurement) {
        Assume.assumeTrue(measurement + " is checked only without C2, in the allocation-tests-without-c2 execution",
            ManagementFactory.getRuntimeMXBean().getInputArguments().contains("-XX:TieredStopAtLevel=1"));
    }

    /**
     * Every instance field from {@code ResponseWriter} down, as {@code DeclaringClass.name:Type}: an added field
     * changes this even when it fits in a writer's alignment padding, where the bytes per writer cannot see it.
     */
    private static List<String> instanceFields(Class<?> writerType) {
        List<Class<?>> hierarchy = new ArrayList<>();
        for (Class<?> type = writerType; type != null && type != Object.class; type = type.getSuperclass()) {
            hierarchy.add(0, type);
        }
        List<String> fields = new ArrayList<>();
        for (Class<?> type : hierarchy) {
            for (Field field : type.getDeclaredFields()) {
                if (!Modifier.isStatic(field.getModifiers()) && !field.isSynthetic()) {
                    fields.add(type.getSimpleName() + "." + field.getName() + ":" + field.getType().getSimpleName());
                }
            }
        }
        return fields;
    }

    private static List<String> with(List<String> base, String... more) {
        List<String> all = new ArrayList<>(base);
        Collections.addAll(all, more);
        return all;
    }
}
