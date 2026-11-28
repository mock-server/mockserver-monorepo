package org.mockserver.model;

import java.util.Objects;

/**
 * A log-normal distribution parameterised by its median and sigma (the standard
 * deviation of the underlying normal distribution of the natural logarithm).
 * Sampling returns {@code median * exp(sigma * z)} for a standard normal
 * {@code z}, rounded to a whole value and clamped to at least {@code min}.
 *
 * <p>Used by {@link CompletionGeneration} for token counts and streaming rates:
 * {@code sigma} of {@code 0} degenerates to a deterministic median, and sigma is
 * the multiplicative spread — {@code sigma = 0.7} puts roughly two thirds of
 * samples within {@code [median / 2, median * 2]}.
 */
public class LogNormal extends ObjectWithJsonToString {
    private int hashCode;
    private Double median;
    private Double sigma;
    private Double min;

    public static LogNormal logNormal() {
        return new LogNormal();
    }

    public LogNormal withMedian(Double median) {
        if (median != null && median <= 0) {
            throw new IllegalArgumentException("median must be > 0");
        }
        this.median = median;
        this.hashCode = 0;
        return this;
    }

    public Double getMedian() {
        return median;
    }

    public LogNormal withSigma(Double sigma) {
        if (sigma != null && sigma < 0) {
            throw new IllegalArgumentException("sigma must be >= 0");
        }
        this.sigma = sigma;
        this.hashCode = 0;
        return this;
    }

    public Double getSigma() {
        return sigma;
    }

    /**
     * Lower clamp applied to every sample. Defaults to {@code 1} for token counts;
     * set explicitly when a different floor is required (the streaming-rate
     * consumer clamps to its own bounds regardless).
     */
    public LogNormal withMin(Double min) {
        if (min != null && min < 0) {
            throw new IllegalArgumentException("min must be >= 0");
        }
        this.min = min;
        this.hashCode = 0;
        return this;
    }

    public Double getMin() {
        return min;
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
        LogNormal that = (LogNormal) o;
        return Objects.equals(median, that.median) &&
            Objects.equals(sigma, that.sigma) &&
            Objects.equals(min, that.min);
    }

    @Override
    public int hashCode() {
        if (hashCode == 0) {
            hashCode = Objects.hash(median, sigma, min);
        }
        return hashCode;
    }
}
