package org.mockserver.model;

import java.util.concurrent.TimeUnit;

/**
 * @author jamesdbloom
 */
public class Delay extends ObjectWithReflectiveEqualsHashCodeToString {

    private final TimeUnit timeUnit;
    private final long value;
    private final DelayDistribution distribution;
    private final String template;
    private final HttpTemplate.TemplateType templateType;

    public static Delay milliseconds(long value) {
        return new Delay(TimeUnit.MILLISECONDS, value);
    }

    public static Delay seconds(long value) {
        return new Delay(TimeUnit.SECONDS, value);
    }

    public static Delay minutes(long value) {
        return new Delay(TimeUnit.MINUTES, value);
    }

    public static Delay delay(TimeUnit timeUnit, long value) {
        return new Delay(timeUnit, value);
    }

    public static Delay uniform(TimeUnit timeUnit, long min, long max) {
        return new Delay(timeUnit, 0, DelayDistribution.uniform(min, max));
    }

    public static Delay logNormal(TimeUnit timeUnit, long median, long p99) {
        return new Delay(timeUnit, 0, DelayDistribution.logNormal(median, p99));
    }

    public static Delay gaussian(TimeUnit timeUnit, long mean, long stdDev) {
        return new Delay(timeUnit, 0, DelayDistribution.gaussian(mean, stdDev));
    }

    /**
     * Creates an opt-in delay whose duration in milliseconds is computed by rendering the supplied
     * template against the incoming request (the same request context exposed to response templates,
     * e.g. {@code request.body}, {@code request.headers}). The rendered output is parsed as a
     * millisecond value, so e.g. larger request payloads can respond more slowly. When the template
     * renders to a non-numeric or blank value the delay falls back to the static {@code value}/{@code timeUnit}
     * pair (which defaults to zero). Only {@link HttpTemplate.TemplateType#VELOCITY} and
     * {@link HttpTemplate.TemplateType#MUSTACHE} are supported.
     */
    public static Delay template(HttpTemplate.TemplateType templateType, String template) {
        return new Delay(TimeUnit.MILLISECONDS, 0, null, template, templateType);
    }

    public Delay(TimeUnit timeUnit, long value) {
        this(timeUnit, value, null, null, null);
    }

    public Delay(TimeUnit timeUnit, long value, DelayDistribution distribution) {
        this(timeUnit, value, distribution, null, null);
    }

    public Delay(TimeUnit timeUnit, long value, DelayDistribution distribution, String template, HttpTemplate.TemplateType templateType) {
        this.timeUnit = timeUnit;
        this.value = value;
        this.distribution = distribution;
        this.template = template;
        this.templateType = templateType;
    }

    Delay deepCopy() {
        return new Delay(timeUnit, value, distribution != null ? distribution.deepCopy() : null, template, templateType);
    }

    public TimeUnit getTimeUnit() {
        return timeUnit;
    }

    public long getValue() {
        return value;
    }

    public DelayDistribution getDistribution() {
        return distribution;
    }

    public String getTemplate() {
        return template;
    }

    public HttpTemplate.TemplateType getTemplateType() {
        return templateType;
    }

    /**
     * @return {@code true} when this delay computes its duration from a request template
     * (see {@link #template(HttpTemplate.TemplateType, String)}).
     */
    public boolean hasTemplate() {
        return template != null && templateType != null;
    }

    public long sampleValueMillis() {
        if (distribution != null && timeUnit != null) {
            long sampled = distribution.sample();
            long millis = timeUnit.toMillis(sampled);
            return Math.max(0, millis);
        }
        if (timeUnit != null) {
            long millis = timeUnit.toMillis(value);
            return Math.max(0, millis);
        }
        return 0;
    }

    public void applyDelay() {
        if (timeUnit != null) {
            try {
                long millis = sampleValueMillis();
                if (millis > 0) {
                    TimeUnit.MILLISECONDS.sleep(millis);
                }
            } catch (InterruptedException ie) {
                throw new RuntimeException("InterruptedException while apply delay to response", ie);
            }
        }
    }
}
