package org.mockserver.model;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.Objects;

@JsonInclude(JsonInclude.Include.NON_NULL)
public class Timing {
    // epoch-millis / duration fields stored as primitive long to avoid a retained boxed Long per field
    // on every forwarded response (each Timing is held for the log entry's lifetime). UNSET is the "not
    // set" sentinel; the public getters/setters keep their Long signatures and null semantics, so JSON,
    // equals/hashCode and every consumer see identical output. Long.MIN_VALUE cannot collide with a real
    // epoch time or duration. Mirrors the pattern in RequestDefinition.receivedTimestamp.
    private static final long UNSET = Long.MIN_VALUE;
    private long requestStartedMillis = UNSET;
    private long connectionEstablishedMillis = UNSET;
    private long responseReceivedMillis = UNSET;
    private long connectionTimeInMillis = UNSET;
    private long timeToFirstByteInMillis = UNSET;
    private long totalTimeInMillis = UNSET;
    private long injectedChaosLatencyMillis = UNSET;
    private long injectedDelayMillis = UNSET;
    private long breakpointHeldMillis = UNSET;

    public static Timing timing() {
        return new Timing();
    }

    Timing deepCopy() {
        Timing copy = new Timing();
        copy.requestStartedMillis = requestStartedMillis;
        copy.connectionEstablishedMillis = connectionEstablishedMillis;
        copy.responseReceivedMillis = responseReceivedMillis;
        copy.connectionTimeInMillis = connectionTimeInMillis;
        copy.timeToFirstByteInMillis = timeToFirstByteInMillis;
        copy.totalTimeInMillis = totalTimeInMillis;
        copy.injectedChaosLatencyMillis = injectedChaosLatencyMillis;
        copy.injectedDelayMillis = injectedDelayMillis;
        copy.breakpointHeldMillis = breakpointHeldMillis;
        return copy;
    }

    public Long getRequestStartedMillis() {
        return requestStartedMillis == UNSET ? null : requestStartedMillis;
    }

    public Timing withRequestStartedMillis(Long requestStartedMillis) {
        this.requestStartedMillis = requestStartedMillis == null ? UNSET : requestStartedMillis;
        return this;
    }

    public Long getConnectionEstablishedMillis() {
        return connectionEstablishedMillis == UNSET ? null : connectionEstablishedMillis;
    }

    public Timing withConnectionEstablishedMillis(Long connectionEstablishedMillis) {
        this.connectionEstablishedMillis = connectionEstablishedMillis == null ? UNSET : connectionEstablishedMillis;
        return this;
    }

    public Long getResponseReceivedMillis() {
        return responseReceivedMillis == UNSET ? null : responseReceivedMillis;
    }

    public Timing withResponseReceivedMillis(Long responseReceivedMillis) {
        this.responseReceivedMillis = responseReceivedMillis == null ? UNSET : responseReceivedMillis;
        return this;
    }

    public Long getConnectionTimeInMillis() {
        return connectionTimeInMillis == UNSET ? null : connectionTimeInMillis;
    }

    public Timing withConnectionTimeInMillis(Long connectionTimeInMillis) {
        this.connectionTimeInMillis = connectionTimeInMillis == null ? UNSET : connectionTimeInMillis;
        return this;
    }

    public Long getTimeToFirstByteInMillis() {
        return timeToFirstByteInMillis == UNSET ? null : timeToFirstByteInMillis;
    }

    public Timing withTimeToFirstByteInMillis(Long timeToFirstByteInMillis) {
        this.timeToFirstByteInMillis = timeToFirstByteInMillis == null ? UNSET : timeToFirstByteInMillis;
        return this;
    }

    public Long getTotalTimeInMillis() {
        return totalTimeInMillis == UNSET ? null : totalTimeInMillis;
    }

    public Timing withTotalTimeInMillis(Long totalTimeInMillis) {
        this.totalTimeInMillis = totalTimeInMillis == null ? UNSET : totalTimeInMillis;
        return this;
    }

    /**
     * @return the latency (in milliseconds) MockServer injected via a chaos-profile latency fault, or
     * {@code null} when no chaos latency was applied to this exchange.
     */
    public Long getInjectedChaosLatencyMillis() {
        return injectedChaosLatencyMillis == UNSET ? null : injectedChaosLatencyMillis;
    }

    public Timing withInjectedChaosLatencyMillis(Long injectedChaosLatencyMillis) {
        this.injectedChaosLatencyMillis = injectedChaosLatencyMillis == null ? UNSET : injectedChaosLatencyMillis;
        return this;
    }

    /**
     * @return the delay (in milliseconds) MockServer injected from the matched action's configured
     * {@code delay}, or {@code null} when the action had no delay.
     */
    public Long getInjectedDelayMillis() {
        return injectedDelayMillis == UNSET ? null : injectedDelayMillis;
    }

    public Timing withInjectedDelayMillis(Long injectedDelayMillis) {
        this.injectedDelayMillis = injectedDelayMillis == null ? UNSET : injectedDelayMillis;
        return this;
    }

    /**
     * @return how long (in milliseconds) the exchange was held paused at a response-phase breakpoint
     * before it was resumed, or {@code null} when no breakpoint held this exchange.
     */
    public Long getBreakpointHeldMillis() {
        return breakpointHeldMillis == UNSET ? null : breakpointHeldMillis;
    }

    public Timing withBreakpointHeldMillis(Long breakpointHeldMillis) {
        this.breakpointHeldMillis = breakpointHeldMillis == null ? UNSET : breakpointHeldMillis;
        return this;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        Timing timing = (Timing) o;
        return requestStartedMillis == timing.requestStartedMillis &&
            connectionEstablishedMillis == timing.connectionEstablishedMillis &&
            responseReceivedMillis == timing.responseReceivedMillis &&
            connectionTimeInMillis == timing.connectionTimeInMillis &&
            timeToFirstByteInMillis == timing.timeToFirstByteInMillis &&
            totalTimeInMillis == timing.totalTimeInMillis &&
            injectedChaosLatencyMillis == timing.injectedChaosLatencyMillis &&
            injectedDelayMillis == timing.injectedDelayMillis &&
            breakpointHeldMillis == timing.breakpointHeldMillis;
    }

    @Override
    public int hashCode() {
        return Objects.hash(getRequestStartedMillis(), getConnectionEstablishedMillis(), getResponseReceivedMillis(), getConnectionTimeInMillis(), getTimeToFirstByteInMillis(), getTotalTimeInMillis(), getInjectedChaosLatencyMillis(), getInjectedDelayMillis(), getBreakpointHeldMillis());
    }
}
