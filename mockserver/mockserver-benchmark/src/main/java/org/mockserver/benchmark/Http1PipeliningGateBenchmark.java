package org.mockserver.benchmark;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandler;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.DefaultFullHttpRequest;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpServerCodec;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.util.ReferenceCountUtil;
import org.mockserver.codec.HeldRequestAccounting;
import org.mockserver.codec.Http1PipeliningGate;
import org.mockserver.configuration.Configuration;
import org.mockserver.logging.MockServerLogger;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.infra.Blackhole;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

/**
 * Cost of {@link Http1PipeliningGate}, which answers pipelined HTTP/1.1 requests in order, with the gate in the
 * pipeline ({@code gated=true}) or a pass-through handler in its place ({@code gated=false}, the cost with no gate
 * installed). The difference between the arms is the gate's cost.
 * <ul>
 * <li>{@code exchange}: one request read and one response written and flushed on a kept-alive connection, the
 * non-pipelined hot path (expected: a few nanoseconds, 0 B/op more).</li>
 * <li>{@code churn}: a connection's pipeline built, one exchange, then closed: the gate's per-connection cost.</li>
 * <li>{@code pipelined}: two requests decoded from one read by the real codec and both answered; with the gate the
 * second is held, then released one event-loop task after the first response.</li>
 * </ul>
 *
 * <pre>./run.sh -prof gc Http1PipeliningGateBenchmark</pre>
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(1)
public class Http1PipeliningGateBenchmark {

    private static final Configuration CONFIGURATION = Configuration.configuration();
    private static final MockServerLogger LOGGER = new MockServerLogger(Http1PipeliningGateBenchmark.class);

    @Param({"false", "true"})
    public boolean gated;

    private EmbeddedChannel channel;
    private EmbeddedChannel codecChannel;
    private DefaultFullHttpRequest request;
    private DefaultFullHttpResponse response;
    private ByteBuf twoRequests;

    @Setup(Level.Trial)
    public void setup() {
        channel = new EmbeddedChannel(handler());
        codecChannel = new EmbeddedChannel(new HttpServerCodec(), handler());
        request = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, "/", Unpooled.EMPTY_BUFFER);
        response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK, Unpooled.EMPTY_BUFFER);
        response.headers().set(HttpHeaderNames.CONTENT_LENGTH, 0);
        twoRequests = Unpooled.unreleasableBuffer(Unpooled.copiedBuffer(
            "GET /one HTTP/1.1\r\nHost: localhost\r\n\r\nGET /two HTTP/1.1\r\nHost: localhost\r\n\r\n", StandardCharsets.US_ASCII));
    }

    @TearDown(Level.Trial)
    public void tearDown() {
        channel.finishAndReleaseAll();
        codecChannel.finishAndReleaseAll();
    }

    private ChannelHandler handler() {
        return gated ? new Http1PipeliningGate(CONFIGURATION, HeldRequestAccounting.NONE, LOGGER) : new ChannelDuplexHandler();
    }

    @Benchmark
    public void exchange(Blackhole blackhole) {
        exchangeOn(channel, blackhole);
    }

    @Benchmark
    public void churn(Blackhole blackhole) {
        EmbeddedChannel connection = new EmbeddedChannel(handler());
        exchangeOn(connection, blackhole);
        connection.close();
        blackhole.consume(connection);
    }

    @Benchmark
    public void pipelined(Blackhole blackhole) {
        codecChannel.writeInbound(twoRequests.duplicate());
        answerAll(blackhole);
        // with the gate, the second request is released by a task that writing the first response runs
        answerAll(blackhole);
    }

    private void exchangeOn(EmbeddedChannel connection, Blackhole blackhole) {
        // not writeInbound(Object...): its varargs array is removed by C2 only when inlined, which varies by run and arm
        connection.pipeline().fireChannelRead(request);
        connection.pipeline().fireChannelReadComplete();
        connection.runPendingTasks();
        blackhole.consume(connection.inboundMessages().poll());
        connection.writeOneOutbound(response);
        connection.flushOutbound();
        blackhole.consume(connection.outboundMessages().poll());
    }

    private void answerAll(Blackhole blackhole) {
        int requests = 0;
        for (Object inbound = codecChannel.readInbound(); inbound != null; inbound = codecChannel.readInbound()) {
            if (inbound instanceof io.netty.handler.codec.http.HttpRequest) {
                requests++;
            }
            blackhole.consume(inbound);
            ReferenceCountUtil.release(inbound);
        }
        for (int i = 0; i < requests; i++) {
            codecChannel.writeOutbound(response);
        }
        for (Object outbound = codecChannel.readOutbound(); outbound != null; outbound = codecChannel.readOutbound()) {
            blackhole.consume(outbound);
            ReferenceCountUtil.release(outbound);
        }
    }
}
