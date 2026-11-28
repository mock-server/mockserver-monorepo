package org.mockserver.serialization.model;

import org.mockserver.model.CompletionGeneration;
import org.mockserver.model.ObjectWithReflectiveEqualsHashCodeToString;

public class CompletionGenerationDTO extends ObjectWithReflectiveEqualsHashCodeToString implements DTO<CompletionGeneration> {

    private LogNormalDTO outputTokens;
    private LogNormalDTO tokensPerSecond;
    private TtftDTO ttft;
    private Long seed;

    public CompletionGenerationDTO(CompletionGeneration completionGeneration) {
        if (completionGeneration != null) {
            if (completionGeneration.getOutputTokens() != null) {
                outputTokens = new LogNormalDTO(completionGeneration.getOutputTokens());
            }
            if (completionGeneration.getTokensPerSecond() != null) {
                tokensPerSecond = new LogNormalDTO(completionGeneration.getTokensPerSecond());
            }
            if (completionGeneration.getTtft() != null) {
                ttft = new TtftDTO(completionGeneration.getTtft());
            }
            seed = completionGeneration.getSeed();
        }
    }

    public CompletionGenerationDTO() {
    }

    public CompletionGeneration buildObject() {
        return new CompletionGeneration()
            .withOutputTokens(outputTokens != null ? outputTokens.buildObject() : null)
            .withTokensPerSecond(tokensPerSecond != null ? tokensPerSecond.buildObject() : null)
            .withTtft(ttft != null ? ttft.buildObject() : null)
            .withSeed(seed);
    }

    public LogNormalDTO getOutputTokens() {
        return outputTokens;
    }

    public CompletionGenerationDTO setOutputTokens(LogNormalDTO outputTokens) {
        this.outputTokens = outputTokens;
        return this;
    }

    public LogNormalDTO getTokensPerSecond() {
        return tokensPerSecond;
    }

    public CompletionGenerationDTO setTokensPerSecond(LogNormalDTO tokensPerSecond) {
        this.tokensPerSecond = tokensPerSecond;
        return this;
    }

    public TtftDTO getTtft() {
        return ttft;
    }

    public CompletionGenerationDTO setTtft(TtftDTO ttft) {
        this.ttft = ttft;
        return this;
    }

    public Long getSeed() {
        return seed;
    }

    public CompletionGenerationDTO setSeed(Long seed) {
        this.seed = seed;
        return this;
    }
}
