package org.mockserver.serialization.model;

import org.mockserver.model.Completion;
import org.mockserver.model.ObjectWithReflectiveEqualsHashCodeToString;
import org.mockserver.model.ToolUse;
import org.mockserver.model.Usage;

import java.util.List;

public class CompletionDTO extends ObjectWithReflectiveEqualsHashCodeToString implements DTO<Completion> {

    private String text;
    private List<ToolUse> toolCalls;
    private String stopReason;
    private Usage usage;
    private Boolean streaming;
    private StreamingPhysicsDTO streamingPhysics;
    private String outputSchema;
    private Boolean enforceOutputSchema;
    private String model;
    private String toolChoice;
    private String reasoningText;
    private String reasoningSignature;

    public CompletionDTO(Completion completion) {
        if (completion != null) {
            text = completion.getText();
            toolCalls = completion.getToolCalls();
            stopReason = completion.getStopReason();
            usage = completion.getUsage();
            streaming = completion.getStreaming();
            if (completion.getStreamingPhysics() != null) {
                streamingPhysics = new StreamingPhysicsDTO(completion.getStreamingPhysics());
            }
            outputSchema = completion.getOutputSchema();
            enforceOutputSchema = completion.getEnforceOutputSchema();
            model = completion.getModel();
            toolChoice = completion.getToolChoice();
            reasoningText = completion.getReasoningText();
            reasoningSignature = completion.getReasoningSignature();
        }
    }

    public CompletionDTO() {
    }

    public Completion buildObject() {
        return new Completion()
            .withText(text)
            .withToolCalls(toolCalls)
            .withStopReason(stopReason)
            .withUsage(usage)
            .withStreaming(streaming)
            .withStreamingPhysics(streamingPhysics != null ? streamingPhysics.buildObject() : null)
            .withOutputSchema(outputSchema)
            .withEnforceOutputSchema(enforceOutputSchema)
            .withModel(model)
            .withToolChoice(toolChoice)
            .withReasoningText(reasoningText)
            .withReasoningSignature(reasoningSignature);
    }

    public String getText() {
        return text;
    }

    public CompletionDTO setText(String text) {
        this.text = text;
        return this;
    }

    public List<ToolUse> getToolCalls() {
        return toolCalls;
    }

    public CompletionDTO setToolCalls(List<ToolUse> toolCalls) {
        this.toolCalls = toolCalls;
        return this;
    }

    public String getStopReason() {
        return stopReason;
    }

    public CompletionDTO setStopReason(String stopReason) {
        this.stopReason = stopReason;
        return this;
    }

    public Usage getUsage() {
        return usage;
    }

    public CompletionDTO setUsage(Usage usage) {
        this.usage = usage;
        return this;
    }

    public Boolean getStreaming() {
        return streaming;
    }

    public CompletionDTO setStreaming(Boolean streaming) {
        this.streaming = streaming;
        return this;
    }

    public StreamingPhysicsDTO getStreamingPhysics() {
        return streamingPhysics;
    }

    public CompletionDTO setStreamingPhysics(StreamingPhysicsDTO streamingPhysics) {
        this.streamingPhysics = streamingPhysics;
        return this;
    }

    public String getOutputSchema() {
        return outputSchema;
    }

    public CompletionDTO setOutputSchema(String outputSchema) {
        this.outputSchema = outputSchema;
        return this;
    }

    public Boolean getEnforceOutputSchema() {
        return enforceOutputSchema;
    }

    public CompletionDTO setEnforceOutputSchema(Boolean enforceOutputSchema) {
        this.enforceOutputSchema = enforceOutputSchema;
        return this;
    }

    public String getModel() {
        return model;
    }

    public CompletionDTO setModel(String model) {
        this.model = model;
        return this;
    }

    public String getToolChoice() {
        return toolChoice;
    }

    public CompletionDTO setToolChoice(String toolChoice) {
        this.toolChoice = toolChoice;
        return this;
    }

    public String getReasoningText() {
        return reasoningText;
    }

    public CompletionDTO setReasoningText(String reasoningText) {
        this.reasoningText = reasoningText;
        return this;
    }

    public String getReasoningSignature() {
        return reasoningSignature;
    }

    public CompletionDTO setReasoningSignature(String reasoningSignature) {
        this.reasoningSignature = reasoningSignature;
        return this;
    }
}
