package org.mockserver.model;

import java.util.Objects;

/**
 * Request-time probabilistic completion generation, declared instead of a fixed
 * {@code text} fixture.
 *
 * <p>When present on a completion, the LLM response handler generates the completion
 * <strong>per request</strong>: the output length is sampled from a log-normal
 * distribution, filler text of exactly that many tokens is produced,
 * time-to-first-token scales with the decoded request's input-token estimate, and
 * {@code usage} is computed from the actual generated text rather than trusted from
 * the expectation.
 *
 * <p>Every field is optional; an omitted field leaves that aspect at its default. A
 * {@code sigma} of {@code 0} makes a distribution deterministic (always the median),
 * which is how a fixed-length output is expressed.
 */
public class CompletionGeneration extends ObjectWithJsonToString {
    private int hashCode;
    private LogNormal outputTokens;
    private LogNormal tokensPerSecond;
    private Ttft ttft;
    private Long seed;

    public static CompletionGeneration generation() {
        return new CompletionGeneration();
    }

    public CompletionGeneration withOutputTokens(LogNormal outputTokens) {
        this.outputTokens = outputTokens;
        this.hashCode = 0;
        return this;
    }

    public LogNormal getOutputTokens() {
        return outputTokens;
    }

    public CompletionGeneration withTokensPerSecond(LogNormal tokensPerSecond) {
        this.tokensPerSecond = tokensPerSecond;
        this.hashCode = 0;
        return this;
    }

    public LogNormal getTokensPerSecond() {
        return tokensPerSecond;
    }

    public CompletionGeneration withTtft(Ttft ttft) {
        this.ttft = ttft;
        this.hashCode = 0;
        return this;
    }

    public Ttft getTtft() {
        return ttft;
    }

    public CompletionGeneration withSeed(Long seed) {
        this.seed = seed;
        this.hashCode = 0;
        return this;
    }

    public Long getSeed() {
        return seed;
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
        CompletionGeneration that = (CompletionGeneration) o;
        return Objects.equals(outputTokens, that.outputTokens) &&
            Objects.equals(tokensPerSecond, that.tokensPerSecond) &&
            Objects.equals(ttft, that.ttft) &&
            Objects.equals(seed, that.seed);
    }

    @Override
    public int hashCode() {
        if (hashCode == 0) {
            hashCode = Objects.hash(outputTokens, tokensPerSecond, ttft, seed);
        }
        return hashCode;
    }
}
