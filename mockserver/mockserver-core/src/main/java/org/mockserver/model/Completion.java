package org.mockserver.model;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

public class Completion extends ObjectWithJsonToString {
    private int hashCode;
    private String text;
    private List<ToolUse> toolCalls;
    private String stopReason;
    private Usage usage;
    private Boolean streaming;
    private StreamingPhysics streamingPhysics;
    private String outputSchema;
    private Boolean enforceOutputSchema;
    private String model;
    private String toolChoice;
    private String reasoningText;
    private String reasoningSignature;
    private CompletionGeneration generate;

    public static Completion completion() {
        return new Completion();
    }

    public Completion withText(String text) {
        this.text = text;
        this.hashCode = 0;
        return this;
    }

    public String getText() {
        return text;
    }

    public Completion withToolCalls(List<ToolUse> toolCalls) {
        this.toolCalls = toolCalls;
        this.hashCode = 0;
        return this;
    }

    public Completion withToolCalls(ToolUse... toolCalls) {
        this.toolCalls = Arrays.asList(toolCalls);
        this.hashCode = 0;
        return this;
    }

    public Completion withToolCall(ToolUse toolCall) {
        if (this.toolCalls == null) {
            this.toolCalls = new ArrayList<>();
        }
        this.toolCalls.add(toolCall);
        this.hashCode = 0;
        return this;
    }

    public List<ToolUse> getToolCalls() {
        return toolCalls;
    }

    public Completion withStopReason(String stopReason) {
        this.stopReason = stopReason;
        this.hashCode = 0;
        return this;
    }

    public String getStopReason() {
        return stopReason;
    }

    public Completion withUsage(Usage usage) {
        this.usage = usage;
        this.hashCode = 0;
        return this;
    }

    public Usage getUsage() {
        return usage;
    }

    public Completion withStreaming(Boolean streaming) {
        this.streaming = streaming;
        this.hashCode = 0;
        return this;
    }

    public Completion streaming() {
        return withStreaming(Boolean.TRUE);
    }

    public Boolean getStreaming() {
        return streaming;
    }

    public Completion withStreamingPhysics(StreamingPhysics streamingPhysics) {
        this.streamingPhysics = streamingPhysics;
        this.hashCode = 0;
        return this;
    }

    /**
     * Compose streaming physics from independent values. Accepts any combination of
     * {@link org.mockserver.model.Delay} (interpreted as time-to-first-token) and
     * {@link StreamingPhysics} fragments (each typically carrying a single field —
     * e.g. {@code tokensPerSecond(50)} or {@code jitter(0.2)}). Non-null fields from
     * the fragments are merged left-to-right onto a single {@link StreamingPhysics}
     * instance which is then assigned to this completion. Calling implicitly enables
     * streaming.
     */
    public Completion withStreamingPhysics(Object... parts) {
        StreamingPhysics merged = StreamingPhysics.streamingPhysics();
        for (Object part : parts) {
            if (part == null) {
                continue;
            }
            if (part instanceof Delay) {
                merged.withTimeToFirstToken((Delay) part);
            } else if (part instanceof StreamingPhysics) {
                StreamingPhysics fragment = (StreamingPhysics) part;
                if (fragment.getTimeToFirstToken() != null) {
                    merged.withTimeToFirstToken(fragment.getTimeToFirstToken());
                }
                if (fragment.getTokensPerSecond() != null) {
                    merged.withTokensPerSecond(fragment.getTokensPerSecond());
                }
                if (fragment.getJitter() != null) {
                    merged.withJitter(fragment.getJitter());
                }
                if (fragment.getSeed() != null) {
                    merged.withSeed(fragment.getSeed());
                }
                if (fragment.getSubwordStreaming() != null) {
                    merged.withSubwordStreaming(fragment.getSubwordStreaming());
                }
            } else {
                throw new IllegalArgumentException(
                    "withStreamingPhysics accepts Delay or StreamingPhysics fragments; got: "
                        + part.getClass().getName());
            }
        }
        this.streamingPhysics = merged;
        this.hashCode = 0;
        return this;
    }

    public StreamingPhysics getStreamingPhysics() {
        return streamingPhysics;
    }

    /**
     * Optional JSON Schema (as a JSON string) that this completion's {@link #getText() text}
     * is expected to conform to. When set, the LLM response handler validates the configured
     * text against the schema as the response is encoded. Validation is fail-soft: a mismatch
     * does not alter the response body — it adds an {@code x-mockserver-structured-output-invalid}
     * diagnostic header and logs a warning, so a deliberately non-conforming fixture still
     * returns exactly as configured while malformed structured-output fixtures are surfaced.
     */
    public Completion withOutputSchema(String outputSchema) {
        this.outputSchema = outputSchema;
        this.hashCode = 0;
        return this;
    }

    public String getOutputSchema() {
        return outputSchema;
    }

    /**
     * Opt-in strict structured-output enforcement. When {@code true} <em>and</em> an
     * {@link #getOutputSchema() outputSchema} is declared, the LLM response handler
     * <strong>enforces</strong> conformance instead of merely flagging it: if the
     * configured {@link #getText() text} does not conform to the schema, the handler
     * fails loudly with a provider-correct error response rather than returning the
     * non-conforming body.
     *
     * <p>This models real providers' strict {@code response_format: json_schema} mode,
     * where the provider <em>guarantees</em> schema-valid output — so a non-conforming
     * fixture is a configuration error that should surface rather than pass silently.
     *
     * <p>When unset or {@code false} (the default), behaviour is unchanged: a mismatch
     * is fail-soft — the body is returned as configured and only the
     * {@code x-mockserver-structured-output-invalid} diagnostic header + a warning log
     * are added. Has no effect without an {@code outputSchema}.
     */
    public Completion withEnforceOutputSchema(Boolean enforceOutputSchema) {
        this.enforceOutputSchema = enforceOutputSchema;
        this.hashCode = 0;
        return this;
    }

    public Completion enforceOutputSchema() {
        return withEnforceOutputSchema(Boolean.TRUE);
    }

    public Boolean getEnforceOutputSchema() {
        return enforceOutputSchema;
    }

    /**
     * Optional model identifier extracted from the provider response. Set by
     * {@code parseCompletionResponse} implementations so the caller can read
     * the model without re-parsing the response body.
     */
    public Completion withModel(String model) {
        this.model = model;
        this.hashCode = 0;
        return this;
    }

    public String getModel() {
        return model;
    }

    /**
     * Optional tool-choice directive modelling the request's {@code tool_choice} for this
     * mocked exchange. Recognised values: {@code auto} (model decides), {@code none}
     * (never call a tool), {@code required} (must call a tool), or a named tool. When set to
     * {@code required} and at least one tool call is configured, the encoded response's
     * {@code finish_reason} is forced to {@code tool_calls}. Absent {@code toolChoice}
     * leaves the existing finish-reason behaviour unchanged.
     */
    public Completion withToolChoice(String toolChoice) {
        this.toolChoice = toolChoice;
        this.hashCode = 0;
        return this;
    }

    public String getToolChoice() {
        return toolChoice;
    }

    /**
     * Optional reasoning/"thinking" text — the model's chain-of-thought that providers surface
     * separately from the visible answer. When set, the provider codec prepends a provider-correct
     * reasoning content block before the text block (Anthropic {@code thinking} block, OpenAI
     * Responses {@code reasoning} summary item, Gemini {@code thought} part, Ollama
     * {@code message.thinking}). Additive: absent unless set, so existing expectations are
     * unchanged. The request matcher is unaffected — this only influences response encoding.
     */
    public Completion withReasoningText(String reasoningText) {
        this.reasoningText = reasoningText;
        this.hashCode = 0;
        return this;
    }

    public String getReasoningText() {
        return reasoningText;
    }

    /**
     * Optional cryptographic signature accompanying {@link #getReasoningText() reasoning text},
     * used by Anthropic to sign (and later verify/redact) a thinking block. Emitted as the
     * {@code signature} field of the Anthropic {@code thinking} content block (and the
     * {@code signature_delta} streaming event) only when both reasoning text and a signature are
     * set. Ignored by providers that have no signed-reasoning concept.
     */
    public Completion withReasoningSignature(String reasoningSignature) {
        this.reasoningSignature = reasoningSignature;
        this.hashCode = 0;
        return this;
    }

    public String getReasoningSignature() {
        return reasoningSignature;
    }

    /**
     * Request-time probabilistic generation config. When set, the completion's text,
     * streaming physics and usage are generated per request (sampled output length,
     * input-scaled TTFT, filler text of exactly the sampled token count) instead of
     * served from the fixed {@link #getText() text} fixture. See
     * {@link CompletionGeneration}.
     */
    public Completion withGenerate(CompletionGeneration generate) {
        this.generate = generate;
        this.hashCode = 0;
        return this;
    }

    public CompletionGeneration getGenerate() {
        return generate;
    }

    /**
     * Per-request deep-enough copy: a new {@link Completion} carrying the same field values,
     * so request-specific mutation (usage inference, generation) never mutates the shared
     * expectation model. The copy deliberately excludes {@link #getGenerate() generate}: the
     * generation config describes how to build a response, not the response itself.
     */
    public Completion mutableCopy() {
        return Completion.completion()
            .withText(text)
            .withToolCalls(toolCalls)
            .withToolChoice(toolChoice)
            .withStopReason(stopReason)
            .withUsage(usage)
            .withStreaming(streaming)
            .withStreamingPhysics(streamingPhysics)
            .withOutputSchema(outputSchema)
            .withEnforceOutputSchema(enforceOutputSchema)
            .withModel(model)
            .withReasoningText(reasoningText)
            .withReasoningSignature(reasoningSignature);
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
        Completion that = (Completion) o;
        return Objects.equals(text, that.text) &&
            Objects.equals(toolCalls, that.toolCalls) &&
            Objects.equals(stopReason, that.stopReason) &&
            Objects.equals(usage, that.usage) &&
            Objects.equals(streaming, that.streaming) &&
            Objects.equals(streamingPhysics, that.streamingPhysics) &&
            Objects.equals(outputSchema, that.outputSchema) &&
            Objects.equals(enforceOutputSchema, that.enforceOutputSchema) &&
            Objects.equals(model, that.model) &&
            Objects.equals(toolChoice, that.toolChoice) &&
            Objects.equals(reasoningText, that.reasoningText) &&
            Objects.equals(reasoningSignature, that.reasoningSignature) &&
            Objects.equals(generate, that.generate);
    }

    @Override
    public int hashCode() {
        if (hashCode == 0) {
            hashCode = Objects.hash(text, toolCalls, stopReason, usage, streaming, streamingPhysics, outputSchema, enforceOutputSchema, model, toolChoice, reasoningText, reasoningSignature, generate);
        }
        return hashCode;
    }
}
