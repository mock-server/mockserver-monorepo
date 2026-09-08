package org.mockserver.serialization.model;

import org.mockserver.model.ObjectWithReflectiveEqualsHashCodeToString;
import org.mockserver.model.StreamingPhysics;

public class StreamingPhysicsDTO extends ObjectWithReflectiveEqualsHashCodeToString implements DTO<StreamingPhysics> {

    private DelayDTO timeToFirstToken;
    private Integer tokensPerSecond;
    private Double jitter;
    private Long seed;
    private Boolean subwordStreaming;

    public StreamingPhysicsDTO(StreamingPhysics streamingPhysics) {
        if (streamingPhysics != null) {
            if (streamingPhysics.getTimeToFirstToken() != null) {
                timeToFirstToken = new DelayDTO(streamingPhysics.getTimeToFirstToken());
            }
            tokensPerSecond = streamingPhysics.getTokensPerSecond();
            jitter = streamingPhysics.getJitter();
            seed = streamingPhysics.getSeed();
            subwordStreaming = streamingPhysics.getSubwordStreaming();
        }
    }

    public StreamingPhysicsDTO() {
    }

    public StreamingPhysics buildObject() {
        StreamingPhysics streamingPhysics = StreamingPhysics.streamingPhysics();
        if (timeToFirstToken != null) {
            streamingPhysics.withTimeToFirstToken(timeToFirstToken.buildObject());
        }
        streamingPhysics.withTokensPerSecond(tokensPerSecond);
        streamingPhysics.withJitter(jitter);
        streamingPhysics.withSeed(seed);
        streamingPhysics.withSubwordStreaming(subwordStreaming);
        return streamingPhysics;
    }

    public DelayDTO getTimeToFirstToken() {
        return timeToFirstToken;
    }

    public StreamingPhysicsDTO setTimeToFirstToken(DelayDTO timeToFirstToken) {
        this.timeToFirstToken = timeToFirstToken;
        return this;
    }

    public Integer getTokensPerSecond() {
        return tokensPerSecond;
    }

    public StreamingPhysicsDTO setTokensPerSecond(Integer tokensPerSecond) {
        this.tokensPerSecond = tokensPerSecond;
        return this;
    }

    public Double getJitter() {
        return jitter;
    }

    public StreamingPhysicsDTO setJitter(Double jitter) {
        this.jitter = jitter;
        return this;
    }

    public Long getSeed() {
        return seed;
    }

    public StreamingPhysicsDTO setSeed(Long seed) {
        this.seed = seed;
        return this;
    }

    public Boolean getSubwordStreaming() {
        return subwordStreaming;
    }

    public StreamingPhysicsDTO setSubwordStreaming(Boolean subwordStreaming) {
        this.subwordStreaming = subwordStreaming;
        return this;
    }
}
