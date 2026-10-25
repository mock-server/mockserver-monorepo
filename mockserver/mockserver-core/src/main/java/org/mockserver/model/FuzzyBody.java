package org.mockserver.model;

import java.util.Objects;

/**
 * A body matcher that matches when the request body is "similar enough" to an
 * expected string, using a deterministic string-similarity metric
 * (normalised Jaro-Winkler) with a configurable threshold between {@code 0.0}
 * and {@code 1.0}.
 * <p>
 * Unlike the LLM-based semantic body matcher this matcher is fully
 * deterministic and requires no external service — the same inputs always
 * produce the same similarity ratio.
 *
 * @author jamesdbloom
 */
public class FuzzyBody extends Body<String> {

    public static final double DEFAULT_THRESHOLD = 0.8d;
    public static final boolean DEFAULT_IGNORE_CASE = false;

    private int hashCode;
    private final String value;
    private final double threshold;
    private final boolean ignoreCase;

    public FuzzyBody(String value) {
        this(value, DEFAULT_THRESHOLD, DEFAULT_IGNORE_CASE);
    }

    public FuzzyBody(String value, double threshold) {
        this(value, threshold, DEFAULT_IGNORE_CASE);
    }

    public FuzzyBody(String value, double threshold, boolean ignoreCase) {
        super(Type.FUZZY);
        this.value = value;
        this.threshold = clampThreshold(threshold);
        this.ignoreCase = ignoreCase;
    }

    private static double clampThreshold(double threshold) {
        if (threshold < 0.0d) {
            return 0.0d;
        }
        if (threshold > 1.0d) {
            return 1.0d;
        }
        return threshold;
    }

    public String getValue() {
        return value;
    }

    public double getThreshold() {
        return threshold;
    }

    public boolean isIgnoreCase() {
        return ignoreCase;
    }

    /**
     * Match when the request body is at least {@code 80%} similar to {@code value}.
     */
    public static FuzzyBody fuzzy(String value) {
        return new FuzzyBody(value);
    }

    /**
     * Match when the request body is at least {@code threshold} similar to {@code value}.
     *
     * @param threshold required similarity ratio between {@code 0.0} and {@code 1.0}
     */
    public static FuzzyBody fuzzy(String value, double threshold) {
        return new FuzzyBody(value, threshold);
    }

    /**
     * Match when the request body is at least {@code threshold} similar to {@code value},
     * optionally ignoring case (and surrounding whitespace) when comparing.
     *
     * @param threshold  required similarity ratio between {@code 0.0} and {@code 1.0}
     * @param ignoreCase when {@code true} both strings are trimmed and lower-cased before comparison
     */
    public static FuzzyBody fuzzy(String value, double threshold, boolean ignoreCase) {
        return new FuzzyBody(value, threshold, ignoreCase);
    }

    @Override
    public FuzzyBody copy() {
        return withFlagsCopiedTo(new FuzzyBody(value, threshold, ignoreCase));
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
        if (!super.equals(o)) {
            return false;
        }
        FuzzyBody fuzzyBody = (FuzzyBody) o;
        return Double.compare(fuzzyBody.threshold, threshold) == 0 &&
            ignoreCase == fuzzyBody.ignoreCase &&
            Objects.equals(value, fuzzyBody.value);
    }

    @Override
    public int hashCode() {
        if (hashCode == 0) {
            hashCode = Objects.hash(super.hashCode(), value, threshold, ignoreCase);
        }
        return hashCode;
    }
}
