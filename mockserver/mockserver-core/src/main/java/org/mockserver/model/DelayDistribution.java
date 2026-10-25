package org.mockserver.model;

import java.util.concurrent.ThreadLocalRandom;

public class DelayDistribution extends ObjectWithReflectiveEqualsHashCodeToString {

    private static final double Z_SCORE_P99 = 2.326347874;
    private static final long MAX_DELAY_VALUE = 86_400_000L;

    private Type type;
    private Long min;
    private Long max;
    private Long median;
    private Long p99;
    private Long mean;
    private Long stdDev;

    public enum Type {
        UNIFORM,
        LOG_NORMAL,
        GAUSSIAN
    }

    public DelayDistribution() {
    }

    DelayDistribution deepCopy() {
        DelayDistribution copy = new DelayDistribution();
        copy.type = type;
        copy.min = min;
        copy.max = max;
        copy.median = median;
        copy.p99 = p99;
        copy.mean = mean;
        copy.stdDev = stdDev;
        return copy;
    }

    public static DelayDistribution uniform(long min, long max) {
        if (min < 0) {
            throw new IllegalArgumentException("min must be >= 0, got " + min);
        }
        if (max < min) {
            throw new IllegalArgumentException("max must be >= min, got min=" + min + " max=" + max);
        }
        DelayDistribution distribution = new DelayDistribution();
        distribution.type = Type.UNIFORM;
        distribution.min = min;
        distribution.max = max;
        return distribution;
    }

    public static DelayDistribution logNormal(long median, long p99) {
        if (median <= 0) {
            throw new IllegalArgumentException("median must be > 0, got " + median);
        }
        if (p99 < median) {
            throw new IllegalArgumentException("p99 must be >= median, got median=" + median + " p99=" + p99);
        }
        DelayDistribution distribution = new DelayDistribution();
        distribution.type = Type.LOG_NORMAL;
        distribution.median = median;
        distribution.p99 = p99;
        return distribution;
    }

    public static DelayDistribution gaussian(long mean, long stdDev) {
        if (mean < 0) {
            throw new IllegalArgumentException("mean must be >= 0, got " + mean);
        }
        if (stdDev < 0) {
            throw new IllegalArgumentException("stdDev must be >= 0, got " + stdDev);
        }
        DelayDistribution distribution = new DelayDistribution();
        distribution.type = Type.GAUSSIAN;
        distribution.mean = mean;
        distribution.stdDev = stdDev;
        return distribution;
    }

    public long sample() {
        if (type == null) {
            return 0;
        }
        switch (type) {
            case UNIFORM:
                return sampleUniform();
            case LOG_NORMAL:
                return sampleLogNormal();
            case GAUSSIAN:
                return sampleGaussian();
            default:
                return 0;
        }
    }

    private long clamp(long value) {
        return Math.min(Math.max(0, value), MAX_DELAY_VALUE);
    }

    private long sampleUniform() {
        long lo = min != null ? Math.max(0, min) : 0;
        long hi = max != null ? Math.max(lo, max) : lo;
        if (lo >= hi) {
            return clamp(lo);
        }
        if (hi == Long.MAX_VALUE) {
            return clamp(ThreadLocalRandom.current().nextLong(lo, hi));
        }
        return clamp(ThreadLocalRandom.current().nextLong(lo, hi + 1));
    }

    private long sampleLogNormal() {
        long med = median != null && median > 0 ? median : 1;
        long p = p99 != null ? p99 : med;
        if (p <= med) {
            return clamp(med);
        }
        double mu = Math.log(med);
        double sigma = (Math.log(p) - mu) / Z_SCORE_P99;
        double sampled = Math.exp(mu + sigma * ThreadLocalRandom.current().nextGaussian());
        return clamp(Math.round(sampled));
    }

    private long sampleGaussian() {
        long m = mean != null ? Math.max(0, mean) : 0;
        long sd = stdDev != null ? Math.max(0, stdDev) : 0;
        if (sd <= 0) {
            return clamp(m);
        }
        double sampled = m + sd * ThreadLocalRandom.current().nextGaussian();
        return clamp(Math.round(sampled));
    }

    public Type getType() {
        return type;
    }

    public DelayDistribution setType(Type type) {
        this.type = type;
        return this;
    }

    public Long getMin() {
        return min;
    }

    public DelayDistribution setMin(Long min) {
        this.min = min;
        return this;
    }

    public Long getMax() {
        return max;
    }

    public DelayDistribution setMax(Long max) {
        this.max = max;
        return this;
    }

    public Long getMedian() {
        return median;
    }

    public DelayDistribution setMedian(Long median) {
        this.median = median;
        return this;
    }

    public Long getP99() {
        return p99;
    }

    public DelayDistribution setP99(Long p99) {
        this.p99 = p99;
        return this;
    }

    public Long getMean() {
        return mean;
    }

    public DelayDistribution setMean(Long mean) {
        this.mean = mean;
        return this;
    }

    public Long getStdDev() {
        return stdDev;
    }

    public DelayDistribution setStdDev(Long stdDev) {
        this.stdDev = stdDev;
        return this;
    }
}
