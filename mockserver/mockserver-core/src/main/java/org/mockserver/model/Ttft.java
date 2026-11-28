package org.mockserver.model;

import java.util.Objects;

/**
 * Time-to-first-token model for a generated completion. The sampled delay is
 * {@code baseMs + msPerKiloInputTokens * (inputTokens / 1000)}, optionally jittered
 * by {@code jitter} (a relative uniform spread, same semantics as
 * {@link StreamingPhysics#getJitter()}).
 *
 * <p>{@code msPerKiloInputTokens} models prefill: real providers spend roughly
 * linear time attending over the prompt before the first token, so a multi-turn
 * session whose history grows every turn sees its TTFT grow with it. A plain
 * {@code baseMs} with no per-kilo component expresses a constant TTFT.
 */
public class Ttft extends ObjectWithJsonToString {
    private int hashCode;
    private Double baseMs;
    private Double msPerKiloInputTokens;
    private Double jitter;

    public static Ttft ttft() {
        return new Ttft();
    }

    public Ttft withBaseMs(Double baseMs) {
        if (baseMs != null && baseMs < 0) {
            throw new IllegalArgumentException("baseMs must be >= 0");
        }
        this.baseMs = baseMs;
        this.hashCode = 0;
        return this;
    }

    public Double getBaseMs() {
        return baseMs;
    }

    public Ttft withMsPerKiloInputTokens(Double msPerKiloInputTokens) {
        if (msPerKiloInputTokens != null && msPerKiloInputTokens < 0) {
            throw new IllegalArgumentException("msPerKiloInputTokens must be >= 0");
        }
        this.msPerKiloInputTokens = msPerKiloInputTokens;
        this.hashCode = 0;
        return this;
    }

    public Double getMsPerKiloInputTokens() {
        return msPerKiloInputTokens;
    }

    public Ttft withJitter(Double jitter) {
        if (jitter != null && (jitter < 0.0 || jitter > 1.0)) {
            throw new IllegalArgumentException("jitter must be between 0.0 and 1.0");
        }
        this.jitter = jitter;
        this.hashCode = 0;
        return this;
    }

    public Double getJitter() {
        return jitter;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        if (hashCode() != o.hashCode()) {
            return false;
        }
        Ttft that = (Ttft) o;
        return Objects.equals(baseMs, that.baseMs) &&
            Objects.equals(msPerKiloInputTokens, that.msPerKiloInputTokens) &&
            Objects.equals(jitter, that.jitter);
    }

    @Override
    public int hashCode() {
        if (hashCode == 0) {
            hashCode = Objects.hash(baseMs, msPerKiloInputTokens, jitter);
        }
        return hashCode;
    }
}
