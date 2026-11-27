package org.mockserver.serialization.model;

import org.mockserver.model.LogNormal;
import org.mockserver.model.ObjectWithReflectiveEqualsHashCodeToString;

public class LogNormalDTO extends ObjectWithReflectiveEqualsHashCodeToString implements DTO<LogNormal> {

    private Double median;
    private Double sigma;
    private Double min;

    public LogNormalDTO(LogNormal logNormal) {
        if (logNormal != null) {
            median = logNormal.getMedian();
            sigma = logNormal.getSigma();
            min = logNormal.getMin();
        }
    }

    public LogNormalDTO() {
    }

    public LogNormal buildObject() {
        return new LogNormal()
            .withMedian(median)
            .withSigma(sigma)
            .withMin(min);
    }

    public Double getMedian() {
        return median;
    }

    public LogNormalDTO setMedian(Double median) {
        this.median = median;
        return this;
    }

    public Double getSigma() {
        return sigma;
    }

    public LogNormalDTO setSigma(Double sigma) {
        this.sigma = sigma;
        return this;
    }

    public Double getMin() {
        return min;
    }

    public LogNormalDTO setMin(Double min) {
        this.min = min;
        return this;
    }
}
