package org.mockserver.benchmark;

import org.mockserver.configuration.Configuration;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.mock.Expectation;
import org.mockserver.mock.HttpState;
import org.mockserver.mock.action.http.HttpActionHandler;
import org.mockserver.model.HttpRequest;
import org.mockserver.model.HttpResponse;
import org.mockserver.responsewriter.ResponseWriter;
import org.mockserver.scheduler.Scheduler;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.infra.Blackhole;

import java.util.Set;
import java.util.concurrent.TimeUnit;

import static org.mockserver.configuration.Configuration.configuration;
import static org.mockserver.model.HttpRequest.request;
import static org.mockserver.model.HttpResponse.response;

/**
 * {@link HttpActionHandler#processAction} for a matched {@code RESPONSE} with no data-plane hook loaded, with a new
 * writer per request as a transport makes one: {@code RECEIVED_REQUEST}, CRUD lookup, matching, the action and
 * {@link ResponseWriter#writeResponse}. Its {@code gc.alloc.rate.norm} is what the unloaded hook guard must leave
 * unchanged, measured with C2, where {@code DataPlaneHooksAbsentCostTest} pins the same path with C1 only.
 *
 * <p>No {@code @Param}: the per-merge allocation gate passes its pins to every included class, and this class must add
 * exactly one row. WARN keeps the SLF4J output out of the fork; the event log records the same entries at any level.
 *
 * <pre>./run.sh -prof gc ProcessActionBenchmark</pre>
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(1)
public class ProcessActionBenchmark {

    private static final Set<String> LOCAL_ADDRESSES = Set.of("localhost", "127.0.0.1");

    private Configuration configuration;
    private MockServerLogger mockServerLogger;
    private HttpState httpState;
    private HttpActionHandler actionHandler;
    private HttpRequest matchedRequest;

    private static final class BlackholeResponseWriter extends ResponseWriter {
        private final Blackhole blackhole;
        private HttpResponse sent;

        private BlackholeResponseWriter(Configuration configuration, MockServerLogger mockServerLogger, Blackhole blackhole) {
            super(configuration, mockServerLogger);
            this.blackhole = blackhole;
        }

        @Override
        public void sendResponse(HttpRequest request, HttpResponse response) {
            if (blackhole != null) {
                blackhole.consume(response);
            } else {
                sent = response;
            }
        }
    }

    @Setup(Level.Trial)
    public void setup() {
        configuration = configuration().logLevel(org.slf4j.event.Level.WARN);
        mockServerLogger = new MockServerLogger(configuration, ProcessActionBenchmark.class);
        httpState = new HttpState(configuration, mockServerLogger, new Scheduler(configuration, mockServerLogger, true));
        if (httpState.dataPlaneHooks() != null) {
            throw new IllegalStateException("a data-plane hook is loaded; this benchmark measures the path with none");
        }
        httpState.add(new Expectation(request("/matched")).thenRespond(response("matched")));
        actionHandler = new HttpActionHandler(configuration, null, httpState, null, null);
        matchedRequest = request("/matched").withMethod("GET");
        BlackholeResponseWriter probe = new BlackholeResponseWriter(configuration, mockServerLogger, null);
        actionHandler.processAction(matchedRequest, probe, null, LOCAL_ADDRESSES, false, true);
        if (probe.sent == null || probe.sent.getStatusCode() == null || probe.sent.getStatusCode() != 200) {
            throw new IllegalStateException("the benchmarked request was not answered by its expectation: " + probe.sent);
        }
    }

    @TearDown(Level.Trial)
    public void tearDown() {
        httpState.stop();
    }

    @Benchmark
    public void matchedResponse(Blackhole blackhole) {
        ResponseWriter writer = new BlackholeResponseWriter(configuration, mockServerLogger, blackhole);
        actionHandler.processAction(matchedRequest, writer, null, LOCAL_ADDRESSES, false, true);
        blackhole.consume(writer);
    }
}
