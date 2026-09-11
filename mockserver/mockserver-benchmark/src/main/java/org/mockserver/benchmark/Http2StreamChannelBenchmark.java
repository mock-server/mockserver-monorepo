package org.mockserver.benchmark;

import io.netty.bootstrap.Bootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpScheme;
import io.netty.handler.codec.http2.DefaultHttp2Headers;
import io.netty.handler.codec.http2.DefaultHttp2HeadersFrame;
import io.netty.handler.codec.http2.Http2DataFrame;
import io.netty.handler.codec.http2.Http2FrameCodecBuilder;
import io.netty.handler.codec.http2.Http2Headers;
import io.netty.handler.codec.http2.Http2HeadersFrame;
import io.netty.handler.codec.http2.Http2MultiplexHandler;
import io.netty.handler.codec.http2.Http2ResetFrame;
import io.netty.handler.codec.http2.Http2StreamChannel;
import io.netty.handler.codec.http2.Http2StreamChannelBootstrap;
import io.netty.util.ReferenceCountUtil;
import org.mockserver.client.MockServerClient;
import org.mockserver.netty.MockServer;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.StringJoiner;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;

import static org.mockserver.model.HttpRequest.request;
import static org.mockserver.model.HttpResponse.response;
import static org.mockserver.stop.Stop.stopQuietly;

/**
 * HTTP/2 multiplex throughput / latency benchmark — answers the open question from issue #2669:
 * <em>what does giving every HTTP/2 stream its own channel cost under concurrency?</em>
 *
 * <p>Since #2669 the server's only HTTP/2 pipeline is {@code Http2FrameCodec + Http2MultiplexHandler},
 * so every stream is a child channel with its own pipeline (strictly more per-stream allocation than a
 * shared connection pipeline). This harness drives {@code N} concurrent streams over ONE h2c connection
 * and reports throughput plus p50/p95/p99 latency, sweeping {@code N = 1, 10, 100}. The server caps
 * {@code MAX_CONCURRENT_STREAMS} at 100, so 100 is the concurrency ceiling on a single connection — a
 * higher concurrency is a different (multi-connection) question and is intentionally not attempted here.</p>
 *
 * <p><strong>Concurrency model.</strong> Each HTTP/2 request gets its <em>own</em> stream — that is the
 * unit whose per-stream cost #2669 changed. Concurrency {@code N} is realised as {@code N} driver
 * threads ("lanes"); each lane issues {@code requestsPerStream} requests sequentially, opening a fresh
 * {@link Http2StreamChannel} for every one, so at any instant there are exactly {@code N} in-flight
 * streams on the connection. Total requests per measured run = {@code N * requestsPerStream}. The
 * driver threads block on per-request futures — they are NOT event-loop threads, so blocking is safe.</p>
 *
 * <p><strong>Why not the earlier harness.</strong> A previous attempt reused ONE {@code Http2StreamChannel}
 * for many requests, writing several {@code HEADERS} frames on a single stream — an HTTP/2 protocol
 * violation (a stream carries exactly one request/response), which produced "expected N responses, got 0".
 * The fix is one stream per request, as demonstrated by {@code Http2MultiplexSseEndStreamIntegrationTest}.</p>
 *
 * <p><strong>Self-validation is mandatory and fails the JVM loudly (exit code 2), never warn-and-continue.</strong>
 * A prior harness stopped its timer on a stream-count latch and divided an <em>assumed</em> request count by
 * elapsed time, "reporting" 1,074,476 req/s and a p95 of 95 ns — physically impossible over TCP. The gates
 * in {@link RunResult#validate()} exist to make that class of false-green impossible:
 * <ol>
 *   <li>the latch counts TOTAL expected responses ({@code streams * requestsPerStream}), and the run asserts
 *       {@code completed == expected};</li>
 *   <li>every response must be HTTP 200 with the exact expected body;</li>
 *   <li>a latency floor — a loopback HTTP/2 round trip cannot be under ~1 µs;</li>
 *   <li>a throughput ceiling — wildly-above-documented numbers are treated as a broken harness, not a win;</li>
 *   <li>any stream reset / timeout / non-200 fails the run — statistics are never computed over an errored run.</li>
 * </ol>
 * These gates are HARNESS-integrity checks, deliberately distinct from any performance threshold (there is
 * none yet — see the CI job). A gate failure means the measurement is untrustworthy and exits non-zero;
 * a slow-but-valid run exits zero and simply records slower numbers.</p>
 *
 * <p>Run it via {@code mockserver-benchmark}'s classpath (see {@code run-h2-multiplex.sh}):</p>
 * <pre>
 *   java -cp ... org.mockserver.benchmark.Http2StreamChannelBenchmark [output.json]
 *   java -cp ... org.mockserver.benchmark.Http2StreamChannelBenchmark selftest
 * </pre>
 */
public final class Http2StreamChannelBenchmark {

    private static final String PATH = "/perf";
    private static final String RESPONSE_BODY = "{\"status\":\"ok\"}";

    /** A loopback HTTP/2 round trip through the full mock-matching server cannot plausibly be under 1 µs. */
    private static final long LATENCY_FLOOR_NANOS = 1_000L;

    /**
     * Sanity ceiling for req/s on ONE loopback connection through the full request-matching path. This
     * repo's documented end-to-end knee is ~36k req/s on 6 cores; a single-connection in-JVM run is a
     * different shape, so this ceiling is set well above any plausible real number purely to catch the
     * "divided an assumed count by a tiny elapsed" false-green (the 1,074,476 req/s bug). It is NOT a
     * performance threshold.
     */
    private static final long THROUGHPUT_CEILING_RPS = 200_000L;

    private Http2StreamChannelBenchmark() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length > 0 && "selftest".equalsIgnoreCase(args[0])) {
            selfTest();
            return;
        }

        // Sweep and iteration counts are env-tunable so CI (large) and a local smoke run (tiny) share code.
        int[] streamSweep = parseIntList(env("H2_BENCH_STREAMS", "1,10,100"));
        int requestsPerStream = Integer.parseInt(env("H2_BENCH_REQUESTS_PER_STREAM", "500"));
        int warmupRequests = Integer.parseInt(env("H2_BENCH_WARMUP_REQUESTS", "200"));
        int perRequestTimeoutSeconds = Integer.parseInt(env("H2_BENCH_TIMEOUT_S", "30"));
        Path output = Paths.get(args.length > 0 ? args[0] : env("H2_BENCH_OUTPUT", "perf-h2-multiplex.json"));

        // Harness self-test hooks (default off) — used ONLY by the CI harness-validation smoke to prove the
        // gates still fire on a bad run. They corrupt the current run on purpose, so they NEVER coexist with
        // a real measurement.
        boolean faultBadPort = Boolean.parseBoolean(env("H2_BENCH_SELFTEST_BAD_PORT", "false"));
        boolean faultExpectMismatch = Boolean.parseBoolean(env("H2_BENCH_SELFTEST_EXPECT_MISMATCH", "false"));

        for (int n : streamSweep) {
            if (n < 1) {
                fail("stream count must be >= 1, got " + n);
            }
            if (n > 100) {
                fail("stream count " + n + " exceeds the server MAX_CONCURRENT_STREAMS (100) on one connection");
            }
        }

        MockServer mockServer = null;
        MockServerClient mockServerClient = null;
        try {
            mockServer = new MockServer();
            int serverPort = mockServer.getLocalPort();
            mockServerClient = new MockServerClient("localhost", serverPort);
            mockServerClient
                .when(request().withPath(PATH))
                .respond(response().withStatusCode(200).withBody(RESPONSE_BODY));

            int targetPort = faultBadPort ? closedPort() : serverPort;

            System.out.println("=== HTTP/2 multiplex benchmark (issue #2669) ===");
            System.out.println("server port=" + serverPort + " target port=" + targetPort
                + " streams=" + Arrays.toString(streamSweep)
                + " requestsPerStream=" + requestsPerStream
                + " warmupRequests=" + warmupRequests);

            NioEventLoopGroup group = new NioEventLoopGroup();
            List<RunResult> results = new ArrayList<>();
            try {
                if (warmupRequests > 0 && !faultBadPort) {
                    System.out.println("--- warm-up (10 streams x " + warmupRequests + ") ---");
                    RunResult warm = measure(group, targetPort, 10, warmupRequests, perRequestTimeoutSeconds, false);
                    validateOrAbort(warm, "warm-up");
                    System.out.println("    warm-up ok: " + warm.summary());
                }

                for (int n : streamSweep) {
                    System.out.println("--- " + n + " concurrent stream(s) x " + requestsPerStream + " requests ---");
                    RunResult result = measure(group, targetPort, n, requestsPerStream, perRequestTimeoutSeconds, faultExpectMismatch);
                    validateOrAbort(result, n + " stream(s)");
                    System.out.println("    " + result.summary());
                    results.add(result);
                }
            } finally {
                group.shutdownGracefully(0, 2, TimeUnit.SECONDS).sync();
            }

            String json = toJson(results);
            Files.write(output, json.getBytes(StandardCharsets.UTF_8));
            System.out.println("--- wrote " + output.toAbsolutePath());
            System.out.println(json);
        } finally {
            stopQuietly(mockServerClient);
            stopQuietly(mockServer);
        }
    }

    /**
     * Drive one measured run at concurrency {@code streams}. Returns the (unvalidated) result; the caller
     * must call {@link RunResult#validate()} before trusting any number.
     */
    private static RunResult measure(NioEventLoopGroup group, int port, int streams, int requestsPerStream,
                                     int perRequestTimeoutSeconds, boolean faultExpectMismatch) throws Exception {
        RunResult result = new RunResult(streams, requestsPerStream);
        if (faultExpectMismatch) {
            // Inflate the EXPECTED total without sending the extra requests → gate #1 (completed == expected)
            // must fire. Used only by the harness-validation smoke.
            result.inflateExpectedForSelfTest(1);
        }

        final Channel parent;
        try {
            parent = openConnection(group, port);
        } catch (Exception connectFailure) {
            // A closed/unreachable target must abort loudly, not silently "measure" zero.
            result.recordError("connect to port " + port + " failed: " + connectFailure);
            return result;
        }

        List<Thread> lanes = new ArrayList<>();
        CountDownLatch startGate = new CountDownLatch(1);
        AtomicLong firstWriteNanos = new AtomicLong(Long.MAX_VALUE);
        AtomicLong lastCompleteNanos = new AtomicLong(Long.MIN_VALUE);

        for (int lane = 0; lane < streams; lane++) {
            Thread t = new Thread(() -> {
                try {
                    startGate.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    result.recordError("lane interrupted before start");
                    return;
                }
                for (int i = 0; i < requestsPerStream; i++) {
                    try {
                        long writeNanos = oneRequest(parent, port, perRequestTimeoutSeconds, result);
                        long completeNanos = System.nanoTime();
                        firstWriteNanos.getAndUpdate(prev -> Math.min(prev, writeNanos));
                        lastCompleteNanos.getAndUpdate(prev -> Math.max(prev, completeNanos));
                    } catch (Throwable failure) {
                        result.recordError("request failed: " + failure);
                        return; // stop this lane on first error; validate() will fail the run
                    }
                }
            }, "h2-bench-lane-" + lane);
            t.setDaemon(true);
            lanes.add(t);
            t.start();
        }

        long wallStart = System.nanoTime();
        startGate.countDown();
        for (Thread t : lanes) {
            t.join(TimeUnit.SECONDS.toMillis((long) perRequestTimeoutSeconds * requestsPerStream + 60));
            if (t.isAlive()) {
                result.recordError("lane " + t.getName() + " did not finish within the run budget");
            }
        }
        long wallEnd = System.nanoTime();

        // Wall clock spans the first request write to the last response completion — measuring only the
        // window in which requests were actually in flight, not thread setup/teardown.
        long firstWrite = firstWriteNanos.get();
        long lastComplete = lastCompleteNanos.get();
        if (firstWrite != Long.MAX_VALUE && lastComplete != Long.MIN_VALUE && lastComplete > firstWrite) {
            result.setWallNanos(lastComplete - firstWrite);
        } else {
            result.setWallNanos(wallEnd - wallStart);
        }
        parent.close().awaitUninterruptibly(2, TimeUnit.SECONDS);
        return result;
    }

    /**
     * Open a fresh stream, send one GET, block the calling (driver) thread until END_STREAM, and record the
     * latency. Returns the write timestamp (nanos). Any non-200, body mismatch, reset, exception or timeout
     * is recorded as an error on {@code result} and thrown so the lane stops.
     */
    private static long oneRequest(Channel parent, int port, int timeoutSeconds, RunResult result) throws Exception {
        CompletableFuture<Void> done = new CompletableFuture<>();
        StringBuilder body = new StringBuilder();

        Http2StreamChannel stream = new Http2StreamChannelBootstrap(parent)
            .handler(new ChannelInboundHandlerAdapter() {
                private boolean status200;

                @Override
                public void channelRead(ChannelHandlerContext ctx, Object msg) {
                    try {
                        if (msg instanceof Http2HeadersFrame) {
                            Http2HeadersFrame headers = (Http2HeadersFrame) msg;
                            CharSequence status = headers.headers().status();
                            status200 = status != null && "200".contentEquals(status);
                            if (!status200) {
                                result.recordError("non-200 status: " + status);
                                done.completeExceptionally(new IllegalStateException("status " + status));
                                return;
                            }
                            if (headers.isEndStream()) {
                                complete();
                            }
                        } else if (msg instanceof Http2DataFrame) {
                            Http2DataFrame data = (Http2DataFrame) msg;
                            body.append(data.content().toString(StandardCharsets.UTF_8));
                            if (data.isEndStream()) {
                                complete();
                            }
                        } else if (msg instanceof Http2ResetFrame) {
                            result.recordError("stream reset: errorCode=" + ((Http2ResetFrame) msg).errorCode());
                            done.completeExceptionally(new IllegalStateException("RST_STREAM"));
                        }
                    } finally {
                        ReferenceCountUtil.release(msg);
                    }
                }

                private void complete() {
                    if (!status200) {
                        result.recordError("END_STREAM without a 200 status");
                        done.completeExceptionally(new IllegalStateException("no 200"));
                        return;
                    }
                    if (!RESPONSE_BODY.contentEquals(body)) {
                        result.recordError("body mismatch: <" + body + ">");
                        done.completeExceptionally(new IllegalStateException("body mismatch"));
                        return;
                    }
                    done.complete(null);
                }

                @Override
                public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
                    result.recordError("stream exception: " + cause);
                    done.completeExceptionally(cause);
                }
            })
            .open()
            .sync()
            .getNow();

        try {
            Http2Headers headers = new DefaultHttp2Headers()
                .method(HttpMethod.GET.asciiName())
                .scheme(HttpScheme.HTTP.name())
                .authority("localhost:" + port)
                .path(PATH);

            long writeNanos = System.nanoTime();
            stream.writeAndFlush(new DefaultHttp2HeadersFrame(headers, true));
            try {
                done.get(timeoutSeconds, TimeUnit.SECONDS);
            } catch (TimeoutException timeout) {
                result.recordError("request timed out after " + timeoutSeconds + "s");
                throw timeout;
            } catch (ExecutionException execution) {
                throw execution;
            }
            result.recordLatency(System.nanoTime() - writeNanos);
            return writeNanos;
        } finally {
            stream.close();
        }
    }

    private static Channel openConnection(NioEventLoopGroup group, int port) throws InterruptedException {
        Bootstrap bootstrap = new Bootstrap()
            .group(group)
            .channel(NioSocketChannel.class)
            .handler(new ChannelInitializer<SocketChannel>() {
                @Override
                protected void initChannel(SocketChannel ch) {
                    ch.pipeline().addLast(Http2FrameCodecBuilder.forClient().build());
                    ch.pipeline().addLast(new Http2MultiplexHandler(new ChannelInitializer<Channel>() {
                        @Override
                        protected void initChannel(Channel ch) {
                            ch.pipeline().addLast(new ChannelInboundHandlerAdapter());
                        }
                    }));
                }
            });
        return bootstrap.connect("localhost", port).sync().channel();
    }

    /** A port that is bound then immediately closed — a connect there fails deterministically. */
    private static int closedPort() {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    // --- result + gates -------------------------------------------------------

    static final class RunResult {
        private final int streams;
        private final int requestsPerStream;
        private long expected;
        private final List<Long> latenciesNanos = new CopyOnWriteArrayList<>();
        private final List<String> errors = new CopyOnWriteArrayList<>();
        private volatile long wallNanos;

        RunResult(int streams, int requestsPerStream) {
            this.streams = streams;
            this.requestsPerStream = requestsPerStream;
            this.expected = (long) streams * requestsPerStream;
        }

        void recordLatency(long nanos) {
            latenciesNanos.add(nanos);
        }

        void recordError(String error) {
            errors.add(error);
        }

        void setWallNanos(long nanos) {
            this.wallNanos = nanos;
        }

        void inflateExpectedForSelfTest(int extra) {
            this.expected += extra;
        }

        long completed() {
            return latenciesNanos.size();
        }

        double throughputRps() {
            return wallNanos <= 0 ? 0.0 : completed() / (wallNanos / 1e9);
        }

        /**
         * The five harness-integrity gates. Throws {@link AssertionError} on any breach so the run aborts
         * loudly rather than publishing a number derived from a broken measurement.
         */
        void validate() {
            if (!errors.isEmpty()) {
                throw new AssertionError("run had " + errors.size() + " error(s); first: " + errors.get(0));
            }
            if (completed() != expected) {
                throw new AssertionError("incomplete run: expected " + expected + " responses, got " + completed());
            }
            if (wallNanos <= 0) {
                throw new AssertionError("non-positive wall time: " + wallNanos + " ns");
            }
            long min = Collections.min(latenciesNanos);
            if (min < LATENCY_FLOOR_NANOS) {
                throw new AssertionError("latency floor breached: min " + min + " ns < " + LATENCY_FLOOR_NANOS
                    + " ns — a loopback HTTP/2 round trip cannot be this fast; the harness is not measuring round trips");
            }
            double rps = throughputRps();
            if (rps > THROUGHPUT_CEILING_RPS) {
                throw new AssertionError(String.format(Locale.ROOT,
                    "throughput ceiling breached: %.0f req/s > %d — suspect a divide-by-tiny-elapsed harness bug, not a real result",
                    rps, THROUGHPUT_CEILING_RPS));
            }
        }

        long percentileNanos(double p) {
            List<Long> sorted = new ArrayList<>(latenciesNanos);
            Collections.sort(sorted);
            if (sorted.isEmpty()) {
                return 0;
            }
            int idx = (int) Math.ceil(p / 100.0 * sorted.size()) - 1;
            idx = Math.max(0, Math.min(sorted.size() - 1, idx));
            return sorted.get(idx);
        }

        String summary() {
            return String.format(Locale.ROOT,
                "throughput=%.0f req/s | p50=%.1fµs p95=%.1fµs p99=%.1fµs | min=%.1fµs max=%.1fµs | requests=%d",
                throughputRps(),
                percentileNanos(50) / 1000.0, percentileNanos(95) / 1000.0, percentileNanos(99) / 1000.0,
                Collections.min(latenciesNanos) / 1000.0, Collections.max(latenciesNanos) / 1000.0,
                completed());
        }
    }

    private static String toJson(List<RunResult> results) {
        // Shape: {"h2_multiplex": {"streams_<N>": {throughput_rps, p50_us, p95_us, p99_us, min_us, max_us, requests}}}
        StringJoiner entries = new StringJoiner(",\n");
        for (RunResult r : results) {
            String entry = String.format(Locale.ROOT,
                "    \"streams_%d\": {\"throughput_rps\": %.1f, \"p50_us\": %.2f, \"p95_us\": %.2f, "
                    + "\"p99_us\": %.2f, \"min_us\": %.2f, \"max_us\": %.2f, \"requests\": %d}",
                r.streams,
                r.throughputRps(),
                r.percentileNanos(50) / 1000.0,
                r.percentileNanos(95) / 1000.0,
                r.percentileNanos(99) / 1000.0,
                Collections.min(r.latenciesNanos) / 1000.0,
                Collections.max(r.latenciesNanos) / 1000.0,
                r.completed());
            entries.add(entry);
        }
        return "{\n  \"h2_multiplex\": {\n" + entries + "\n  }\n}\n";
    }

    // --- gate self-test (no server needed) ------------------------------------

    /**
     * Prove every gate fires. Feeds fabricated {@link RunResult}s that each breach exactly one gate and
     * asserts {@link RunResult#validate()} throws; asserts a clean result does NOT throw. Exits non-zero if
     * any gate fails to fire (the false-green we are guarding against) — this is the loud, server-free proof
     * that the validation logic works.
     */
    static void selfTest() {
        System.out.println("=== gate self-test ===");
        int failures = 0;

        // Gate: errors present.
        RunResult withError = new RunResult(1, 1);
        withError.recordLatency(5_000);
        withError.setWallNanos(1_000_000);
        withError.recordError("synthetic error");
        failures += expectThrows("errors-present gate", withError);

        // Gate: completed != expected (the exact "expected N, got 0" reference failure).
        RunResult mismatch = new RunResult(1, 500);
        mismatch.setWallNanos(1_000_000_000L);
        // no latencies recorded → completed 0 != expected 500
        failures += expectThrows("expected!=completed gate", mismatch);

        // Gate: latency floor (the 95 ns physically-impossible p95 class).
        RunResult tooFast = new RunResult(1, 2);
        tooFast.recordLatency(95);   // 95 ns — impossible
        tooFast.recordLatency(120);
        tooFast.setWallNanos(1_000_000);
        failures += expectThrows("latency-floor gate", tooFast);

        // Gate: throughput ceiling (the 1,074,476 req/s divide-by-tiny-elapsed class).
        RunResult tooFast2 = new RunResult(1, 100);
        for (int i = 0; i < 100; i++) {
            tooFast2.recordLatency(2_000); // valid per-request latency ...
        }
        tooFast2.setWallNanos(1_000); // ... but 100 requests in 1 µs ⇒ 100,000,000 req/s
        failures += expectThrows("throughput-ceiling gate", tooFast2);

        // Gate: non-positive wall time.
        RunResult zeroWall = new RunResult(1, 1);
        zeroWall.recordLatency(5_000);
        zeroWall.setWallNanos(0);
        failures += expectThrows("wall-time gate", zeroWall);

        // Control: a clean, plausible run must PASS.
        RunResult clean = new RunResult(2, 3);
        for (int i = 0; i < 6; i++) {
            clean.recordLatency(50_000 + i); // ~50 µs
        }
        clean.setWallNanos(3_000_000L); // 6 reqs / 3 ms = 2000 req/s
        failures += expectPasses("clean-run control", clean);

        if (failures > 0) {
            fail(failures + " gate self-test check(s) FAILED — the harness validation is not sound");
        }
        System.out.println("--- all gate self-tests passed (every gate fires; clean run passes)");
    }

    private static int expectThrows(String name, RunResult result) {
        try {
            result.validate();
            System.out.println("  FAIL: " + name + " did NOT throw (gate would let a bad run through)");
            return 1;
        } catch (AssertionError expected) {
            System.out.println("  ok:   " + name + " fired — " + expected.getMessage());
            return 0;
        }
    }

    private static int expectPasses(String name, RunResult result) {
        try {
            result.validate();
            System.out.println("  ok:   " + name + " passed");
            return 0;
        } catch (AssertionError unexpected) {
            System.out.println("  FAIL: " + name + " threw on a clean run — " + unexpected.getMessage());
            return 1;
        }
    }

    // --- small helpers --------------------------------------------------------

    /** Validate a measured run; on any gate breach print the loud banner and exit non-zero (distinct from a slow run). */
    private static void validateOrAbort(RunResult result, String label) {
        try {
            result.validate();
        } catch (AssertionError gate) {
            fail("[" + label + "] " + gate.getMessage());
        }
    }

    private static void fail(String message) {
        System.err.println("HARNESS VALIDATION FAILED: " + message);
        System.exit(2);
    }

    private static String env(String key, String defaultValue) {
        String value = System.getenv(key);
        return value == null || value.isEmpty() ? defaultValue : value;
    }

    private static int[] parseIntList(String csv) {
        String[] parts = csv.split(",");
        int[] out = new int[parts.length];
        for (int i = 0; i < parts.length; i++) {
            out[i] = Integer.parseInt(parts[i].trim());
        }
        return out;
    }
}
