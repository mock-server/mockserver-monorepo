package org.mockserver.testing;

import org.junit.Assume;
import org.mockserver.model.HttpRequest;
import org.mockserver.mock.Expectation;

import java.lang.management.ManagementFactory;

import static org.mockserver.model.HttpRequest.request;

/**
 * Per-thread allocation measurement for allocation-bound tests that call {@link HttpRequest} or {@link Expectation}.
 * <p>
 * A Mockito mock or spy of either class anywhere in the same JVM leaves it instrumented, and from then on
 * every call of its methods allocates. A bound measured in such a JVM measures Mockito, so these tests skip there and
 * are also run in the {@code allocation-tests-without-c2} execution, a fresh JVM per class where they always run.
 * Probe after measuring, before asserting: a spy in a test running in parallel can instrument the class mid-measurement,
 * and the instrumentation is never removed, so a later probe sees any that happened during the measurement.
 */
public final class AllocationMeasurement {

    private static final int PROBE_CALLS = 100_000;
    private static volatile Object sink;

    private AllocationMeasurement() {
    }

    public static long allocatedBytes() {
        return ((com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean()).getThreadAllocatedBytes(Thread.currentThread().getId());
    }

    /**
     * Skips the calling test when a plain {@code HttpRequest} getter allocates, which it never does uninstrumented.
     */
    public static void assumeHttpRequestIsNotInstrumented() {
        HttpRequest probe = request().withPath("/probe");
        for (int i = 0; i < PROBE_CALLS; i++) {
            sink = probe.getRemoteAddressBytes();
        }
        long before = allocatedBytes();
        for (int i = 0; i < PROBE_CALLS; i++) {
            sink = probe.getRemoteAddressBytes();
        }
        long perCall = (allocatedBytes() - before) / PROBE_CALLS;
        assumeNotInstrumented("HttpRequest is instrumented in this JVM (" + perCall + " bytes per getter call), so its allocations cannot be measured here", perCall);
    }

    /** Skips a shared JVM polluted by Mockito instrumentation of the matcher's expectation lifecycle check. */
    public static void assumeExpectationIsNotInstrumented() {
        Expectation probe = new Expectation(request());
        for (int i = 0; i < PROBE_CALLS; i++) {
            sink = probe.isActive();
        }
        long before = allocatedBytes();
        for (int i = 0; i < PROBE_CALLS; i++) {
            sink = probe.isActive();
        }
        long perCall = (allocatedBytes() - before) / PROBE_CALLS;
        assumeNotInstrumented("Expectation is instrumented in this JVM (" + perCall + " bytes per lifecycle check), so its allocations cannot be measured here", perCall);
    }

    /**
     * As {@link #assumeHttpRequestIsNotInstrumented()}, for the JDK's immutable {@code Set.of} / {@code Map.of}
     * collections, which a Mockito mock elsewhere in the JVM can leave instrumented in the same way.
     */
    public static void assumeImmutableCollectionsAreNotInstrumented() {
        java.util.Set<String> one = java.util.Set.of("PUT");
        java.util.Set<String> three = java.util.Set.of("DELETE", "GET", "PUT");
        java.util.Map<String, java.util.Set<String>> map = java.util.Map.of("/a", one, "/b", three, "/c", one);
        for (int i = 0; i < PROBE_CALLS; i++) {
            sink = one.contains("GET") | three.contains("POST") | map.get("/b") == null;
        }
        long before = allocatedBytes();
        for (int i = 0; i < PROBE_CALLS; i++) {
            sink = one.contains("GET") | three.contains("POST") | map.get("/b") == null;
        }
        long perCall = (allocatedBytes() - before) / PROBE_CALLS;
        assumeNotInstrumented("the immutable collections are instrumented in this JVM (" + perCall + " bytes per probe), so allocations through them cannot be measured here", perCall);
    }

    /**
     * Skips the calling test unless it runs in the {@code allocation-tests-without-c2} execution, for a bound that C2
     * escape analysis can break either way: it may remove an object on one side of a comparison but not the other.
     */
    public static void assumeC1Only(String measurement) {
        Assume.assumeTrue(measurement + " is checked only without C2, in the allocation-tests-without-c2 execution", isC1Only());
    }

    private static boolean isC1Only() {
        return ManagementFactory.getRuntimeMXBean().getInputArguments().contains("-XX:TieredStopAtLevel=1");
    }

    private static void assumeNotInstrumented(String reason, long perCall) {
        if (isC1Only()) {
            // the dedicated clean-JVM execution: never skip there, or the bound would go unchecked everywhere
            if (perCall != 0) {
                throw new AssertionError(reason);
            }
            return;
        }
        Assume.assumeTrue(reason, perCall == 0);
    }
}
