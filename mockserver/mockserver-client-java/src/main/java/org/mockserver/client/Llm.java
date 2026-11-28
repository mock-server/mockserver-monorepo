package org.mockserver.client;

import org.mockserver.llm.IsolationSource;
import org.mockserver.model.*;

import java.util.concurrent.TimeUnit;

/**
 * Convenience re-exports of LLM model-class static factories so that
 * users can write a single import:
 * <pre>
 * import static org.mockserver.client.Llm.*;
 * </pre>
 * and get all LLM-related factories in one import.
 */
public final class Llm {

    private Llm() {
        // utility class
    }

    /**
     * Creates a new Completion builder.
     */
    public static Completion completion() {
        return Completion.completion();
    }

    /**
     * Creates a new ToolUse builder.
     */
    public static ToolUse toolUse(String name) {
        return ToolUse.toolUse(name);
    }

    /**
     * Creates a Usage with the given input tokens.
     */
    public static Usage inputTokens(int inputTokens) {
        return Usage.inputTokens(inputTokens);
    }

    /**
     * Creates a Usage with the given output tokens.
     */
    public static Usage outputTokens(int outputTokens) {
        return Usage.outputTokens(outputTokens);
    }

    /**
     * Creates a new StreamingPhysics builder.
     */
    public static StreamingPhysics streamingPhysics() {
        return StreamingPhysics.streamingPhysics();
    }

    /**
     * Creates a new CompletionGeneration builder for {@code completion().withGenerate(...)},
     * declaring request-time probabilistic generation.
     */
    public static CompletionGeneration generation() {
        return CompletionGeneration.generation();
    }

    /**
     * Creates a new LogNormal builder for a generate distribution (median/sigma/min).
     */
    public static LogNormal logNormal() {
        return LogNormal.logNormal();
    }

    /**
     * Creates a new Ttft builder for a generate time-to-first-token model.
     */
    public static Ttft ttft() {
        return Ttft.ttft();
    }

    /**
     * Creates a Delay representing the time to first token.
     */
    public static Delay timeToFirstToken(long value, TimeUnit timeUnit) {
        return new Delay(timeUnit, value);
    }

    /**
     * Creates a StreamingPhysics with the given tokens per second.
     */
    public static StreamingPhysics tokensPerSecond(int tokensPerSecond) {
        return StreamingPhysics.streamingPhysics().withTokensPerSecond(tokensPerSecond);
    }

    /**
     * Creates a StreamingPhysics with the given jitter.
     */
    public static StreamingPhysics jitter(double jitter) {
        return StreamingPhysics.streamingPhysics().withJitter(jitter);
    }

    /**
     * Creates a new EmbeddingResponse builder.
     */
    public static EmbeddingResponse embedding() {
        return EmbeddingResponse.embedding();
    }

    /**
     * Creates an IsolationSource that reads the isolation key from an HTTP header.
     */
    public static IsolationSource header(String name) {
        return IsolationSource.header(name);
    }

    /**
     * Creates an IsolationSource that reads the isolation key from a query parameter.
     */
    public static IsolationSource queryParameter(String name) {
        return IsolationSource.queryParameter(name);
    }

    /**
     * Creates an IsolationSource that reads the isolation key from a cookie.
     */
    public static IsolationSource cookie(String name) {
        return IsolationSource.cookie(name);
    }

    /**
     * Creates a new LlmConversationBuilder.
     */
    public static LlmConversationBuilder conversation() {
        return LlmConversationBuilder.conversation();
    }

    /**
     * Creates a new LlmFailoverBuilder for scripting provider failover/retry scenarios.
     */
    public static LlmFailoverBuilder llmFailover() {
        return LlmFailoverBuilder.llmFailover();
    }
}
