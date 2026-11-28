package org.mockserver.serialization.model;

import org.mockserver.model.ObjectWithReflectiveEqualsHashCodeToString;
import org.mockserver.model.Ttft;

public class TtftDTO extends ObjectWithReflectiveEqualsHashCodeToString implements DTO<Ttft> {

    private Double baseMs;
    private Double msPerKiloInputTokens;
    private Double jitter;

    public TtftDTO(Ttft ttft) {
        if (ttft != null) {
            baseMs = ttft.getBaseMs();
            msPerKiloInputTokens = ttft.getMsPerKiloInputTokens();
            jitter = ttft.getJitter();
        }
    }

    public TtftDTO() {
    }

    public Ttft buildObject() {
        return new Ttft()
            .withBaseMs(baseMs)
            .withMsPerKiloInputTokens(msPerKiloInputTokens)
            .withJitter(jitter);
    }

    public Double getBaseMs() {
        return baseMs;
    }

    public TtftDTO setBaseMs(Double baseMs) {
        this.baseMs = baseMs;
        return this;
    }

    public Double getMsPerKiloInputTokens() {
        return msPerKiloInputTokens;
    }

    public TtftDTO setMsPerKiloInputTokens(Double msPerKiloInputTokens) {
        this.msPerKiloInputTokens = msPerKiloInputTokens;
        return this;
    }

    public Double getJitter() {
        return jitter;
    }

    public TtftDTO setJitter(Double jitter) {
        this.jitter = jitter;
        return this;
    }
}
