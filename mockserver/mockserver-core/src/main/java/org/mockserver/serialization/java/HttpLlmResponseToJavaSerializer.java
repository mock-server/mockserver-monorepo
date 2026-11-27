package org.mockserver.serialization.java;

import org.mockserver.model.*;

public class HttpLlmResponseToJavaSerializer implements ToJavaSerializer<HttpLlmResponse> {

    @Override
    public String serialize(int numberOfSpacesToIndent, HttpLlmResponse llmResponse) {
        if (llmResponse == null) {
            return "";
        }
        return new FluentJavaBuilder(numberOfSpacesToIndent, "HttpLlmResponse.llmResponse()")
            .with("withProvider", llmResponse.getProvider())
            .with("withModel", llmResponse.getModel())
            .withObject("withCompletion", llmResponse.getCompletion(), HttpLlmResponseToJavaSerializer::serializeCompletion)
            .withObject("withEmbedding", llmResponse.getEmbedding(), (indent, embedding) -> new FluentJavaBuilder(indent, "EmbeddingResponse.embedding()")
                .with("withDimensions", embedding.getDimensions())
                .with("withDeterministicFromInput", embedding.getDeterministicFromInput())
                .with("withSeed", embedding.getSeed())
                .build())
            .withObject("withRerank", llmResponse.getRerank(), (indent, rerank) -> new FluentJavaBuilder(indent, "RerankResponse.rerank()")
                .with("withTopN", rerank.getTopN())
                .with("withDeterministicFromInput", rerank.getDeterministicFromInput())
                .with("withSeed", rerank.getSeed())
                .build())
            .withObject("withModeration", llmResponse.getModeration(), (indent, moderation) -> new FluentJavaBuilder(indent, "ModerationResponse.moderationResponse()")
                .withCode("withFlaggedCategories", moderation.getFlaggedCategories(), HttpLlmResponseToJavaSerializer::stringList)
                .with("withModel", moderation.getModel())
                .build())
            .withObject("withContentFilter", llmResponse.getContentFilter(), (indent, contentFilter) -> new FluentJavaBuilder(indent, "LlmContentFilter.llmContentFilter()")
                .with("withHate", contentFilter.getHate())
                .with("withSexual", contentFilter.getSexual())
                .with("withViolence", contentFilter.getViolence())
                .with("withSelfHarm", contentFilter.getSelfHarm())
                .build())
            .withObject("withConversationPredicates", llmResponse.getConversationPredicates(), HttpLlmResponseToJavaSerializer::serializeConversationPredicates)
            .withObject("withChaos", llmResponse.getChaos(), HttpLlmResponseToJavaSerializer::serializeChaos)
            .withDelay("withDelay", llmResponse.getDelay())
            .build();
    }

    private static String serializeCompletion(int numberOfSpacesToIndent, Completion completion) {
        return new FluentJavaBuilder(numberOfSpacesToIndent, "Completion.completion()")
            .with("withText", completion.getText())
            .withEach("withToolCalls", completion.getToolCalls(), (indent, toolUse) -> new FluentJavaBuilder(indent, "ToolUse.toolUse(" + FluentJavaBuilder.literal(toolUse.getName()) + ")")
                .with("withId", toolUse.getId())
                .with("withArguments", toolUse.getArguments())
                .build())
            .with("withStopReason", completion.getStopReason())
            .withObject("withUsage", completion.getUsage(), (indent, usage) -> new FluentJavaBuilder(indent, "Usage.usage()")
                .with("withInputTokens", usage.getInputTokens())
                .with("withOutputTokens", usage.getOutputTokens())
                .with("withCachedInputTokens", usage.getCachedInputTokens())
                .with("withCacheCreationTokens", usage.getCacheCreationTokens())
                .with("withReasoningTokens", usage.getReasoningTokens())
                .build())
            .with("withStreaming", completion.getStreaming())
            .withObject("withStreamingPhysics", completion.getStreamingPhysics(), (indent, physics) -> new FluentJavaBuilder(indent, "StreamingPhysics.streamingPhysics()")
                .withDelay("withTimeToFirstToken", physics.getTimeToFirstToken())
                .with("withTokensPerSecond", physics.getTokensPerSecond())
                .with("withJitter", physics.getJitter())
                .with("withSeed", physics.getSeed())
                .with("withSubwordStreaming", physics.getSubwordStreaming())
                .build())
            .with("withOutputSchema", completion.getOutputSchema())
            .with("withEnforceOutputSchema", completion.getEnforceOutputSchema())
            .with("withModel", completion.getModel())
            .with("withToolChoice", completion.getToolChoice())
            .with("withReasoningText", completion.getReasoningText())
            .with("withReasoningSignature", completion.getReasoningSignature())
            .withObject("withGenerate", completion.getGenerate(), HttpLlmResponseToJavaSerializer::serializeGeneration)
            .build();
    }

    private static String serializeGeneration(int numberOfSpacesToIndent, CompletionGeneration generate) {
        return new FluentJavaBuilder(numberOfSpacesToIndent, "CompletionGeneration.generation()")
            .withObject("withOutputTokens", generate.getOutputTokens(), HttpLlmResponseToJavaSerializer::serializeLogNormal)
            .withObject("withTokensPerSecond", generate.getTokensPerSecond(), HttpLlmResponseToJavaSerializer::serializeLogNormal)
            .withObject("withTtft", generate.getTtft(), (indent, ttft) -> new FluentJavaBuilder(indent, "Ttft.ttft()")
                .with("withBaseMs", ttft.getBaseMs())
                .with("withMsPerKiloInputTokens", ttft.getMsPerKiloInputTokens())
                .with("withJitter", ttft.getJitter())
                .build())
            .with("withSeed", generate.getSeed())
            .build();
    }

    private static String serializeLogNormal(int numberOfSpacesToIndent, LogNormal logNormal) {
        return new FluentJavaBuilder(numberOfSpacesToIndent, "LogNormal.logNormal()")
            .with("withMedian", logNormal.getMedian())
            .with("withSigma", logNormal.getSigma())
            .with("withMin", logNormal.getMin())
            .build();
    }

    private static String serializeConversationPredicates(int numberOfSpacesToIndent, ConversationPredicates predicates) {
        return new FluentJavaBuilder(numberOfSpacesToIndent, "ConversationPredicates.conversationPredicates()")
            .with("withTurnIndex", predicates.getTurnIndex())
            .with("withLatestMessageContains", predicates.getLatestMessageContains())
            .with("withLatestMessageMatches", predicates.getLatestMessageMatches())
            .with("withLatestMessageRole", predicates.getLatestMessageRole())
            .with("withContainsToolResultFor", predicates.getContainsToolResultFor())
            .with("withSemanticMatchAgainst", predicates.getSemanticMatchAgainst())
            .withObject("withNormalization", predicates.getNormalization(), (indent, normalization) -> new FluentJavaBuilder(indent, "NormalizationOptions.normalizationOptions()")
                .with("withCollapseWhitespace", normalization.getCollapseWhitespace())
                .with("withLowercase", normalization.getLowercase())
                .with("withSortJsonKeys", normalization.getSortJsonKeys())
                .with("withDropBuiltInVolatileFields", normalization.getDropBuiltInVolatileFields())
                .withCode("withDropVolatileFields", normalization.getDropVolatileFields(), HttpLlmResponseToJavaSerializer::stringList)
                .build())
            .build();
    }

    private static String serializeChaos(int numberOfSpacesToIndent, LlmChaosProfile chaos) {
        return new FluentJavaBuilder(numberOfSpacesToIndent, "LlmChaosProfile.llmChaosProfile()")
            .with("withErrorStatus", chaos.getErrorStatus())
            .with("withRetryAfter", chaos.getRetryAfter())
            .with("withErrorProbability", chaos.getErrorProbability())
            .with("withTruncateMode", chaos.getTruncateMode())
            .with("withTruncateAtFraction", chaos.getTruncateAtFraction())
            .with("withMalformedSse", chaos.getMalformedSse())
            .with("withSeed", chaos.getSeed())
            .with("withQuotaName", chaos.getQuotaName())
            .with("withQuotaLimit", chaos.getQuotaLimit())
            .with("withQuotaWindowMillis", chaos.getQuotaWindowMillis())
            .with("withQuotaErrorStatus", chaos.getQuotaErrorStatus())
            .with("withTokenQuotaLimit", chaos.getTokenQuotaLimit())
            .with("withTokenQuotaWindowMillis", chaos.getTokenQuotaWindowMillis())
            .with("withErrorKind", chaos.getErrorKind())
            .with("withContentFilterBlockProbability", chaos.getContentFilterBlockProbability())
            .build();
    }

    private static String stringList(java.util.List<String> values) {
        StringBuilder code = new StringBuilder("java.util.Arrays.asList(");
        for (int i = 0; i < values.size(); i++) {
            code.append(i > 0 ? ", " : "").append(FluentJavaBuilder.literal(values.get(i)));
        }
        return code.append(")").toString();
    }
}
