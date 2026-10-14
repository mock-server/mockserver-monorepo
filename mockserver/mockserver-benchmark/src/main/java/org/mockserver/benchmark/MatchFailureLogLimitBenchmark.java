package org.mockserver.benchmark;

import org.mockserver.closurecallback.websocketregistry.WebSocketClientRegistry;
import org.mockserver.configuration.Configuration;
import org.mockserver.configuration.ConfigurationProperties;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.mock.Expectation;
import org.mockserver.mock.RequestMatchers;
import org.mockserver.model.HttpRequest;
import org.mockserver.scheduler.Scheduler;
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
import org.openjdk.jmh.annotations.Warmup;

import java.util.concurrent.TimeUnit;

import static org.mockito.Mockito.mock;
import static org.mockserver.mock.listeners.MockServerMatcherNotifier.Cause.API;
import static org.mockserver.model.HttpRequest.request;
import static org.mockserver.model.HttpResponse.response;

/**
 * The cost of an unmatched request at INFO with and without {@code maxLoggedMatchFailures}, on the
 * {@link MatchingBenchmark} {@code EXACT} shape (every expectation fails on its path).
 *
 * <p>{@code -1} is the default (one did-not-match entry per compared expectation); {@code 0} and {@code 10}
 * keep every comparison, so the closest-match entry is unchanged, but log only that many per-expectation entries.
 * The logger has no event log, so each entry is rendered on the calling thread: the score is the whole CPU cost of
 * a miss, which a running server splits between the serving thread (building and cloning each entry) and its
 * single logging thread (rendering and storing it).
 *
 * <pre>./run.sh -prof gc MatchFailureLogLimitBenchmark</pre>
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(1)
public class MatchFailureLogLimitBenchmark {

    @Param({"100", "1000"})
    public int expectationCount;

    @Param({"-1", "0", "10"})
    public int maxLoggedMatchFailures;

    @Param({"false", "true"})
    public boolean detailedMatchFailures;

    private RequestMatchers requestMatchers;
    private HttpRequest noMatchRequest;

    @Setup(Level.Trial)
    public void setup() {
        ConfigurationProperties.logLevel("INFO");
        ConfigurationProperties.detailedMatchFailures(detailedMatchFailures);
        Configuration configuration = Configuration.configuration().maxLoggedMatchFailures(maxLoggedMatchFailures);
        requestMatchers = new RequestMatchers(
            configuration,
            new MockServerLogger(),
            mock(Scheduler.class),
            mock(WebSocketClientRegistry.class)
        );
        for (int i = 0; i < expectationCount; i++) {
            requestMatchers.add(new Expectation(request().withMethod("GET").withPath("/exact/path-" + i))
                .thenRespond(response().withBody("e" + i)), API);
        }
        noMatchRequest = request()
            .withMethod("GET")
            .withPath("/no-such-path-zzzzzz")
            .withBody("{\"unmatched\":true}");
    }

    @Benchmark
    public Expectation firstMatchingExpectation_noMatch() {
        return requestMatchers.firstMatchingExpectation(noMatchRequest);
    }
}
