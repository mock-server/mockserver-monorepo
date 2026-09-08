package org.mockserver.serialization;

import org.junit.Test;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.mock.Expectation;
import org.mockserver.model.*;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.core.Is.is;
import static org.hamcrest.core.IsNull.notNullValue;
import static org.mockserver.character.Character.NEW_LINE;
import org.mockserver.llm.ParsedMessage;
import org.mockserver.model.ConversationPredicates;

import static org.mockserver.mock.Expectation.when;
import static org.mockserver.model.Completion.completion;
import static org.mockserver.model.ConversationPredicates.conversationPredicates;
import static org.mockserver.model.EmbeddingResponse.embedding;
import static org.mockserver.model.RerankResponse.rerank;
import static org.mockserver.model.HttpLlmResponse.llmResponse;
import static org.mockserver.model.HttpRequest.request;
import static org.mockserver.model.ToolUse.toolUse;
import static org.mockserver.model.Usage.usage;

public class ExpectationLlmRoundTripTest {

    private final ExpectationSerializer serializer = new ExpectationSerializer(new MockServerLogger());

    @Test
    public void shouldRoundTripCompletionExpectation() {
        // given
        Expectation original = when(request().withPath("/v1/messages"))
            .thenRespondWithLlm(
                llmResponse()
                    .withProvider(Provider.ANTHROPIC)
                    .withModel("claude-sonnet-4-20250514")
                    .withCompletion(
                        completion()
                            .withText("Hello, world!")
                            .withStopReason("end_turn")
                            .withUsage(usage().withInputTokens(10).withOutputTokens(25))
                    )
            );

        // when
        String json = serializer.serialize(original);
        Expectation[] deserialized = serializer.deserializeArray(json, false);

        // then
        assertThat(deserialized, is(notNullValue()));
        assertThat(deserialized.length, is(1));
        Expectation result = deserialized[0];
        assertThat(result.getHttpLlmResponse(), is(notNullValue()));
        assertThat(result.getHttpLlmResponse().getProvider(), is(Provider.ANTHROPIC));
        assertThat(result.getHttpLlmResponse().getModel(), is("claude-sonnet-4-20250514"));
        assertThat(result.getHttpLlmResponse().getCompletion().getText(), is("Hello, world!"));
        assertThat(result.getHttpLlmResponse().getCompletion().getStopReason(), is("end_turn"));
        assertThat(result.getHttpLlmResponse().getCompletion().getUsage().getInputTokens(), is(10));
        assertThat(result.getHttpLlmResponse().getCompletion().getUsage().getOutputTokens(), is(25));
    }

    @Test
    public void shouldRoundTripStreamingPhysicsSubwordStreaming() {
        // given — a streaming completion opting into subword-sized deltas
        Expectation original = when(request().withPath("/v1/chat/completions"))
            .thenRespondWithLlm(
                llmResponse()
                    .withProvider(Provider.OPENAI)
                    .withModel("gpt-4o")
                    .withCompletion(
                        completion()
                            .withText("Hello, world!")
                            .withStreaming(true)
                            .withStreamingPhysics(
                                StreamingPhysics.streamingPhysics()
                                    .withTokensPerSecond(50)
                                    .withSubwordStreaming(true))
                    )
            );

        // when
        String json = serializer.serialize(original);
        Expectation[] deserialized = serializer.deserializeArray(json, false);

        // then — subwordStreaming survives the schema-validated JSON round-trip
        assertThat(deserialized, is(notNullValue()));
        assertThat(deserialized.length, is(1));
        StreamingPhysics physics = deserialized[0].getHttpLlmResponse().getCompletion().getStreamingPhysics();
        assertThat(physics, is(notNullValue()));
        assertThat(physics.getSubwordStreaming(), is(Boolean.TRUE));
        assertThat(physics.getTokensPerSecond(), is(50));
    }

    @Test
    public void shouldRoundTripStreamingPhysicsTimeToFirstToken() {
        // given — a streaming completion whose streamingPhysics carries a plain time-to-first-token Delay.
        // This is the exact shape that used to be rejected with HTTP 400
        // ("Cannot construct instance of org.mockserver.model.Delay") on deserialization,
        // even though it serialised cleanly client-side (issue #2668).
        Delay timeToFirstToken = new Delay(java.util.concurrent.TimeUnit.MILLISECONDS, 250);
        Expectation original = when(request().withPath("/v1/chat/completions"))
            .thenRespondWithLlm(
                llmResponse()
                    .withProvider(Provider.OPENAI)
                    .withModel("gpt-4o")
                    .withCompletion(
                        completion()
                            .withText("Hello, world!")
                            .withStreaming(true)
                            .withStreamingPhysics(
                                StreamingPhysics.streamingPhysics()
                                    .withTimeToFirstToken(timeToFirstToken)
                                    .withTokensPerSecond(50)
                                    .withSubwordStreaming(true))
                    )
            );

        // when — through the schema-validated JSON round-trip (serialize then deserialize)
        String json = serializer.serialize(original);
        Expectation[] deserialized = serializer.deserializeArray(json, false);

        // then — the timeToFirstToken Delay survives, equal to the original
        assertThat(deserialized, is(notNullValue()));
        assertThat(deserialized.length, is(1));
        StreamingPhysics physics = deserialized[0].getHttpLlmResponse().getCompletion().getStreamingPhysics();
        assertThat(physics, is(notNullValue()));
        assertThat(physics.getTimeToFirstToken(), is(notNullValue()));
        assertThat(physics.getTimeToFirstToken(), is(timeToFirstToken));
        // and the sibling fields are not dropped by the new DTO boundary
        assertThat(physics.getTokensPerSecond(), is(50));
        assertThat(physics.getSubwordStreaming(), is(Boolean.TRUE));
    }

    @Test
    public void shouldRoundTripStreamingPhysicsTimeToFirstTokenWithDistribution() {
        // given — a distribution-bearing time-to-first-token Delay, exercising the nested
        // DelayDistributionDTO path underneath the new DelayDTO wrapping.
        Delay timeToFirstToken = new Delay(java.util.concurrent.TimeUnit.MILLISECONDS, 0,
            org.mockserver.model.DelayDistribution.uniform(100, 400));
        Expectation original = when(request().withPath("/v1/chat/completions"))
            .thenRespondWithLlm(
                llmResponse()
                    .withProvider(Provider.OPENAI)
                    .withModel("gpt-4o")
                    .withCompletion(
                        completion()
                            .withText("Hi")
                            .withStreaming(true)
                            .withStreamingPhysics(
                                StreamingPhysics.streamingPhysics()
                                    .withTimeToFirstToken(timeToFirstToken)
                                    .withTokensPerSecond(30))
                    )
            );

        // when
        String json = serializer.serialize(original);
        Expectation[] deserialized = serializer.deserializeArray(json, false);

        // then — the distribution-bearing Delay survives intact
        assertThat(deserialized.length, is(1));
        StreamingPhysics physics = deserialized[0].getHttpLlmResponse().getCompletion().getStreamingPhysics();
        assertThat(physics, is(notNullValue()));
        assertThat(physics.getTimeToFirstToken(), is(notNullValue()));
        assertThat(physics.getTimeToFirstToken(), is(timeToFirstToken));
        assertThat(physics.getTimeToFirstToken().getDistribution(), is(notNullValue()));
        assertThat(physics.getTimeToFirstToken().getDistribution().getType(),
            is(org.mockserver.model.DelayDistribution.Type.UNIFORM));
        assertThat(physics.getTimeToFirstToken().getDistribution().getMin(), is(100L));
        assertThat(physics.getTimeToFirstToken().getDistribution().getMax(), is(400L));
        assertThat(physics.getTokensPerSecond(), is(30));
    }

    @Test
    public void shouldSerializeTimeToFirstTokenWithoutExtraNestingOnTheWire() throws Exception {
        // given — a streaming completion with a plain time-to-first-token Delay
        Expectation original = when(request().withPath("/v1/chat/completions"))
            .thenRespondWithLlm(
                llmResponse()
                    .withProvider(Provider.OPENAI)
                    .withModel("gpt-4o")
                    .withCompletion(
                        completion()
                            .withText("Hello")
                            .withStreaming(true)
                            .withStreamingPhysics(
                                StreamingPhysics.streamingPhysics()
                                    .withTimeToFirstToken(new Delay(java.util.concurrent.TimeUnit.MILLISECONDS, 250)))
                    )
            );

        // when — serialized to the wire
        String json = serializer.serialize(original);

        // then — timeToFirstToken is emitted as a bare delay object ({timeUnit, value}),
        // byte-for-byte what a raw Delay produced before the DTO was introduced —
        // no new wrapper key (e.g. no nested "delay"/"timeToFirstToken") appears.
        com.fasterxml.jackson.databind.JsonNode ttft = new com.fasterxml.jackson.databind.ObjectMapper()
            .readTree(json)
            .path("httpLlmResponse").path("completion").path("streamingPhysics").path("timeToFirstToken");
        assertThat(ttft.isObject(), is(true));
        assertThat(ttft.path("timeUnit").asText(), is("MILLISECONDS"));
        assertThat(ttft.path("value").asLong(), is(250L));
        // guard against an accidental extra layer of wrapping
        assertThat(ttft.has("delay"), is(false));
        assertThat(ttft.has("timeToFirstToken"), is(false));
    }

    @Test
    public void shouldRoundTripCompletionWithOutputSchema() {
        // given — a completion carrying a declared structured-output schema
        String schema = "{\"type\":\"object\",\"properties\":{\"name\":{\"type\":\"string\"}},\"required\":[\"name\"]}";
        Expectation original = when(request().withPath("/v1/messages"))
            .thenRespondWithLlm(
                llmResponse()
                    .withProvider(Provider.ANTHROPIC)
                    .withModel("claude-sonnet-4-20250514")
                    .withCompletion(
                        completion()
                            .withText("{\"name\":\"Ada\"}")
                            .withOutputSchema(schema)
                    )
            );

        // when
        String json = serializer.serialize(original);
        Expectation[] deserialized = serializer.deserializeArray(json, false);

        // then — the outputSchema survives the JSON round-trip intact
        assertThat(deserialized, is(notNullValue()));
        assertThat(deserialized.length, is(1));
        assertThat(deserialized[0].getHttpLlmResponse().getCompletion().getOutputSchema(), is(schema));
    }

    @Test
    public void shouldRoundTripCompletionWithEnforceOutputSchema() {
        // given — a completion opting in to strict structured-output enforcement
        String schema = "{\"type\":\"object\",\"properties\":{\"name\":{\"type\":\"string\"}},\"required\":[\"name\"]}";
        Expectation original = when(request().withPath("/v1/messages"))
            .thenRespondWithLlm(
                llmResponse()
                    .withProvider(Provider.ANTHROPIC)
                    .withModel("claude-sonnet-4-20250514")
                    .withCompletion(
                        completion()
                            .withText("{\"name\":\"Ada\"}")
                            .withOutputSchema(schema)
                            .enforceOutputSchema()
                    )
            );

        // when
        String json = serializer.serialize(original);
        Expectation[] deserialized = serializer.deserializeArray(json, false);

        // then — the enforcement flag survives the JSON round-trip intact
        assertThat(deserialized, is(notNullValue()));
        assertThat(deserialized.length, is(1));
        assertThat(deserialized[0].getHttpLlmResponse().getCompletion().getEnforceOutputSchema(), is(true));
        assertThat(deserialized[0].getHttpLlmResponse().getCompletion().getOutputSchema(), is(schema));
    }

    @Test
    public void shouldRoundTripCompletionWithToolChoice() {
        // given — a completion carrying a tool_choice directive
        Expectation original = when(request().withPath("/v1/chat/completions"))
            .thenRespondWithLlm(
                llmResponse()
                    .withProvider(Provider.OPENAI)
                    .withModel("gpt-4o")
                    .withCompletion(
                        completion()
                            .withToolCall(toolUse("get_weather").withArguments("{\"city\":\"London\"}"))
                            .withToolChoice("required")
                    )
            );

        // when
        String json = serializer.serialize(original);
        Expectation[] deserialized = serializer.deserializeArray(json, false);

        // then — toolChoice survives the schema-validated JSON round-trip
        assertThat(deserialized, is(notNullValue()));
        assertThat(deserialized.length, is(1));
        assertThat(deserialized[0].getHttpLlmResponse().getCompletion().getToolChoice(), is("required"));
    }

    @Test
    public void shouldRoundTripCompletionWithReasoningTextAndSignature() {
        // given — a completion carrying reasoning/"thinking" content plus an Anthropic signature
        Expectation original = when(request().withPath("/v1/messages"))
            .thenRespondWithLlm(
                llmResponse()
                    .withProvider(Provider.ANTHROPIC)
                    .withModel("claude-sonnet-4-20250514")
                    .withCompletion(
                        completion()
                            .withReasoningText("Let me think step by step.")
                            .withReasoningSignature("sig-xyz")
                            .withText("The answer is 42.")
                    )
            );

        // when
        String json = serializer.serialize(original);
        Expectation[] deserialized = serializer.deserializeArray(json, false);

        // then — reasoning text + signature survive the schema-validated JSON round-trip
        assertThat(deserialized.length, is(1));
        Completion completion = deserialized[0].getHttpLlmResponse().getCompletion();
        assertThat(completion.getReasoningText(), is("Let me think step by step."));
        assertThat(completion.getReasoningSignature(), is("sig-xyz"));
        assertThat(completion.getText(), is("The answer is 42."));
    }

    @Test
    public void shouldRoundTripCachedAndReasoningUsageTokens() {
        // given — usage carrying cached-input, cache-creation, and reasoning token counts
        Expectation original = when(request().withPath("/v1/chat/completions"))
            .thenRespondWithLlm(
                llmResponse()
                    .withProvider(Provider.OPENAI)
                    .withModel("gpt-4o")
                    .withCompletion(
                        completion()
                            .withText("hi")
                            .withUsage(usage()
                                .withInputTokens(100).withOutputTokens(40)
                                .withCachedInputTokens(64)
                                .withCacheCreationTokens(8)
                                .withReasoningTokens(30))
                    )
            );

        // when
        String json = serializer.serialize(original);
        Expectation[] deserialized = serializer.deserializeArray(json, false);

        // then — all optional usage fields survive the schema-validated JSON round-trip
        assertThat(deserialized.length, is(1));
        Usage usage = deserialized[0].getHttpLlmResponse().getCompletion().getUsage();
        assertThat(usage.getInputTokens(), is(100));
        assertThat(usage.getOutputTokens(), is(40));
        assertThat(usage.getCachedInputTokens(), is(64));
        assertThat(usage.getCacheCreationTokens(), is(8));
        assertThat(usage.getReasoningTokens(), is(30));
    }

    @Test
    public void shouldRoundTripCompletionWithModel() {
        // given — a completion carrying a model identifier (set on the proxied/forwarded parse path)
        Expectation original = when(request().withPath("/v1/chat/completions"))
            .thenRespondWithLlm(
                llmResponse()
                    .withProvider(Provider.OPENAI)
                    .withCompletion(
                        completion()
                            .withText("Hello, world!")
                            .withModel("gpt-4o")
                    )
            );

        // when — serialize + deserialize through the schema-validated path
        String json = serializer.serialize(original);
        Expectation[] deserialized = serializer.deserializeArray(json, false);

        // then — the completion model survives the round-trip without a validation error
        assertThat(deserialized, is(notNullValue()));
        assertThat(deserialized.length, is(1));
        assertThat(deserialized[0].getHttpLlmResponse().getCompletion().getModel(), is("gpt-4o"));
    }

    @Test
    public void shouldRoundTripEmbeddingExpectation() {
        // given
        Expectation original = when(request().withPath("/v1/embeddings"))
            .thenRespondWithLlm(
                llmResponse()
                    .withProvider(Provider.OPENAI)
                    .withModel("text-embedding-3-small")
                    .withEmbedding(
                        embedding()
                            .withDimensions(1536)
                            .withDeterministicFromInput(true)
                    )
            );

        // when
        String json = serializer.serialize(original);
        Expectation[] deserialized = serializer.deserializeArray(json, false);

        // then
        assertThat(deserialized, is(notNullValue()));
        assertThat(deserialized.length, is(1));
        Expectation result = deserialized[0];
        assertThat(result.getHttpLlmResponse(), is(notNullValue()));
        assertThat(result.getHttpLlmResponse().getProvider(), is(Provider.OPENAI));
        assertThat(result.getHttpLlmResponse().getModel(), is("text-embedding-3-small"));
        assertThat(result.getHttpLlmResponse().getEmbedding().getDimensions(), is(1536));
        assertThat(result.getHttpLlmResponse().getEmbedding().getDeterministicFromInput(), is(true));
    }

    @Test
    public void shouldRoundTripRerankExpectation() {
        // given
        Expectation original = when(request().withPath("/v1/rerank"))
            .thenRespondWithLlm(
                llmResponse()
                    .withProvider(Provider.COHERE)
                    .withRerank(
                        rerank()
                            .withTopN(3)
                            .withDeterministicFromInput(true)
                            .withSeed(42L)
                    )
            );

        // when
        String json = serializer.serialize(original);
        Expectation[] deserialized = serializer.deserializeArray(json, false);

        // then
        assertThat(deserialized, is(notNullValue()));
        assertThat(deserialized.length, is(1));
        Expectation result = deserialized[0];
        assertThat(result.getHttpLlmResponse(), is(notNullValue()));
        assertThat(result.getHttpLlmResponse().getProvider(), is(Provider.COHERE));
        assertThat(result.getHttpLlmResponse().getRerank(), is(notNullValue()));
        assertThat(result.getHttpLlmResponse().getRerank().getTopN(), is(3));
        assertThat(result.getHttpLlmResponse().getRerank().getDeterministicFromInput(), is(true));
        assertThat(result.getHttpLlmResponse().getRerank().getSeed(), is(42L));
    }

    @Test
    public void shouldRoundTripWithToolCalls() {
        // given
        Expectation original = when(request().withPath("/v1/messages"))
            .thenRespondWithLlm(
                llmResponse()
                    .withProvider(Provider.ANTHROPIC)
                    .withModel("claude-sonnet-4-20250514")
                    .withCompletion(
                        completion()
                            .withToolCall(toolUse("get_weather").withArguments("{\"city\":\"London\"}"))
                            .withStopReason("tool_use")
                    )
            );

        // when
        String json = serializer.serialize(original);
        Expectation[] deserialized = serializer.deserializeArray(json, false);

        // then
        assertThat(deserialized, is(notNullValue()));
        assertThat(deserialized.length, is(1));
        Expectation result = deserialized[0];
        assertThat(result.getHttpLlmResponse().getCompletion().getToolCalls().size(), is(1));
        assertThat(result.getHttpLlmResponse().getCompletion().getToolCalls().get(0).getName(), is("get_weather"));
        assertThat(result.getHttpLlmResponse().getCompletion().getToolCalls().get(0).getArguments(), is("{\"city\":\"London\"}"));
    }

    @Test
    public void shouldDeserializeFromJsonString() {
        // given
        String json = "{" + NEW_LINE +
            "  \"httpRequest\" : {" + NEW_LINE +
            "    \"path\" : \"/v1/messages\"" + NEW_LINE +
            "  }," + NEW_LINE +
            "  \"httpLlmResponse\" : {" + NEW_LINE +
            "    \"provider\" : \"ANTHROPIC\"," + NEW_LINE +
            "    \"model\" : \"claude-sonnet-4-20250514\"," + NEW_LINE +
            "    \"completion\" : {" + NEW_LINE +
            "      \"text\" : \"Hello\"," + NEW_LINE +
            "      \"stopReason\" : \"end_turn\"" + NEW_LINE +
            "    }" + NEW_LINE +
            "  }" + NEW_LINE +
            "}";

        // when
        Expectation[] deserialized = serializer.deserializeArray(json, false);

        // then
        assertThat(deserialized, is(notNullValue()));
        assertThat(deserialized.length, is(1));
        Expectation result = deserialized[0];
        assertThat(result.getHttpLlmResponse(), is(notNullValue()));
        assertThat(result.getHttpLlmResponse().getProvider(), is(Provider.ANTHROPIC));
        assertThat(result.getHttpLlmResponse().getModel(), is("claude-sonnet-4-20250514"));
        assertThat(result.getHttpLlmResponse().getCompletion().getText(), is("Hello"));
        assertThat(result.getHttpLlmResponse().getCompletion().getStopReason(), is("end_turn"));
    }

    @Test
    public void shouldRoundTripConversationPredicates() {
        // given
        Expectation original = when(request().withPath("/v1/messages"))
            .thenRespondWithLlm(
                llmResponse()
                    .withProvider(Provider.ANTHROPIC)
                    .withModel("claude-sonnet-4-20250514")
                    .withCompletion(completion().withText("Matched").withStopReason("end_turn"))
                    .withConversationPredicates(
                        conversationPredicates()
                            .withTurnIndex(1)
                            .withLatestMessageContains("hello")
                            .withLatestMessageMatches("\\d+")
                            .withLatestMessageRole(ParsedMessage.Role.USER)
                            .withContainsToolResultFor("search")
                            .withSemanticMatchAgainst("about the weather")
                    )
            );

        // when
        String json = serializer.serialize(original);
        Expectation[] deserialized = serializer.deserializeArray(json, false);

        // then
        assertThat(deserialized, is(notNullValue()));
        assertThat(deserialized.length, is(1));
        Expectation result = deserialized[0];
        assertThat(result.getHttpLlmResponse(), is(notNullValue()));

        ConversationPredicates preds = result.getHttpLlmResponse().getConversationPredicates();
        assertThat(preds, is(notNullValue()));
        assertThat(preds.getTurnIndex(), is(1));
        assertThat(preds.getLatestMessageContains(), is("hello"));
        assertThat(preds.getLatestMessageMatches(), is("\\d+"));
        assertThat(preds.getLatestMessageRole(), is(ParsedMessage.Role.USER));
        assertThat(preds.getContainsToolResultFor(), is("search"));
        assertThat(preds.getSemanticMatchAgainst(), is("about the weather"));

        // Verify the lazy-reconstructed matcher works
        assertThat(result.getHttpLlmResponse().getConversationMatcher(), is(notNullValue()));
        assertThat(result.getHttpLlmResponse().getConversationMatcher().getTurnIndex(), is(1));
        assertThat(result.getHttpLlmResponse().getConversationMatcher().getProvider(), is(Provider.ANTHROPIC));
    }

    @Test
    public void shouldRoundTripConversationPredicatesNormalization() {
        // given
        Expectation original = when(request().withPath("/v1/messages"))
            .thenRespondWithLlm(
                llmResponse()
                    .withProvider(Provider.ANTHROPIC)
                    .withCompletion(completion().withText("Matched"))
                    .withConversationPredicates(
                        conversationPredicates()
                            .withLatestMessageContains("hello")
                            .withNormalization(
                                org.mockserver.model.NormalizationOptions.normalizationOptions()
                                    .withCollapseWhitespace(true)
                                    .withLowercase(true)
                                    .withSortJsonKeys(true)
                                    .withDropBuiltInVolatileFields(true)
                                    .withDropVolatileFields(java.util.Arrays.asList("requestId"))
                            )
                    )
            );

        // when
        String json = serializer.serialize(original);
        Expectation[] deserialized = serializer.deserializeArray(json, false);

        // then
        assertThat(deserialized.length, is(1));
        ConversationPredicates preds = deserialized[0].getHttpLlmResponse().getConversationPredicates();
        assertThat(preds, is(notNullValue()));
        assertThat(preds.getNormalization(), is(notNullValue()));
        assertThat(preds.getNormalization().getCollapseWhitespace(), is(true));
        assertThat(preds.getNormalization().getLowercase(), is(true));
        assertThat(preds.getNormalization().getSortJsonKeys(), is(true));
        assertThat(preds.getNormalization().getDropBuiltInVolatileFields(), is(true));
        assertThat(preds.getNormalization().getDropVolatileFields(), is(java.util.Arrays.asList("requestId")));
        // the lazy-reconstructed matcher carries normalisation through
        assertThat(deserialized[0].getHttpLlmResponse().getConversationMatcher().getNormalization(), is(notNullValue()));
    }

    @Test
    public void shouldRoundTripChaosProfile() {
        // given
        Expectation original = when(request().withPath("/v1/messages"))
            .thenRespondWithLlm(
                llmResponse()
                    .withProvider(Provider.OPENAI)
                    .withCompletion(completion().withText("hi"))
                    .withChaos(
                        org.mockserver.model.LlmChaosProfile.llmChaosProfile()
                            .withErrorStatus(429)
                            .withRetryAfter("30")
                            .withErrorProbability(0.5)
                            .withTruncateMode(org.mockserver.model.LlmChaosProfile.TruncateMode.MID_STREAM)
                            .withTruncateAtFraction(0.25)
                            .withMalformedSse(true)
                            .withSeed(7L)
                            .withQuotaName("acct-1")
                            .withQuotaLimit(100)
                            .withQuotaWindowMillis(60_000L)
                            .withQuotaErrorStatus(529)
                            .withTokenQuotaLimit(50_000L)
                            .withTokenQuotaWindowMillis(60_000L)
                    )
            );

        // when
        String json = serializer.serialize(original);
        Expectation[] deserialized = serializer.deserializeArray(json, false);

        // then
        org.mockserver.model.LlmChaosProfile chaos = deserialized[0].getHttpLlmResponse().getChaos();
        assertThat(chaos, is(notNullValue()));
        assertThat(chaos.getErrorStatus(), is(429));
        assertThat(chaos.getRetryAfter(), is("30"));
        assertThat(chaos.getErrorProbability(), is(0.5));
        assertThat(chaos.getTruncateMode(), is(org.mockserver.model.LlmChaosProfile.TruncateMode.MID_STREAM));
        assertThat(chaos.getTruncateAtFraction(), is(0.25));
        assertThat(chaos.getMalformedSse(), is(true));
        assertThat(chaos.getSeed(), is(7L));
        assertThat(chaos.getQuotaName(), is("acct-1"));
        assertThat(chaos.getQuotaLimit(), is(100));
        assertThat(chaos.getQuotaWindowMillis(), is(60_000L));
        assertThat(chaos.getQuotaErrorStatus(), is(529));
        // token-based quota fields must survive the schema-validated JSON round-trip
        assertThat(chaos.getTokenQuotaLimit(), is(50_000L));
        assertThat(chaos.getTokenQuotaWindowMillis(), is(60_000L));
    }

    @Test
    public void shouldRoundTripModerationAndContentFilter() {
        // given — a moderation verdict + Azure content-filter severities
        Expectation original = when(request().withPath("/v1/moderations"))
            .thenRespondWithLlm(
                llmResponse()
                    .withProvider(Provider.OPENAI)
                    .withModel("omni-moderation-latest")
                    .withModeration(
                        org.mockserver.model.ModerationResponse.moderationResponse()
                            .withFlaggedCategory("hate")
                            .withFlaggedCategory("violence")
                            .withModel("omni-moderation-latest"))
                    .withContentFilter(
                        org.mockserver.model.LlmContentFilter.llmContentFilter()
                            .withHate("high")
                            .withViolence("low"))
            );

        // when — through the schema-validated JSON round-trip
        String json = serializer.serialize(original);
        Expectation[] deserialized = serializer.deserializeArray(json, false);

        // then
        assertThat(deserialized.length, is(1));
        HttpLlmResponse llm = deserialized[0].getHttpLlmResponse();
        assertThat(llm.getModeration(), is(notNullValue()));
        assertThat(llm.getModeration().getFlaggedCategories(), is(java.util.Arrays.asList("hate", "violence")));
        assertThat(llm.getModeration().getModel(), is("omni-moderation-latest"));
        assertThat(llm.getContentFilter(), is(notNullValue()));
        assertThat(llm.getContentFilter().getHate(), is("high"));
        assertThat(llm.getContentFilter().getViolence(), is("low"));
    }

    @Test
    public void shouldRoundTripContentFilterBlockProbability() {
        // given — the content-filter-block chaos field
        Expectation original = when(request().withPath("/v1/chat/completions"))
            .thenRespondWithLlm(
                llmResponse()
                    .withProvider(Provider.OPENAI)
                    .withCompletion(completion().withText("hi"))
                    .withChaos(
                        org.mockserver.model.LlmChaosProfile.llmChaosProfile()
                            .withContentFilterBlockProbability(1.0)
                            .withSeed(7L))
            );

        // when
        String json = serializer.serialize(original);
        Expectation[] deserialized = serializer.deserializeArray(json, false);

        // then — the field survives the schema-validated JSON round-trip
        org.mockserver.model.LlmChaosProfile chaos = deserialized[0].getHttpLlmResponse().getChaos();
        assertThat(chaos, is(notNullValue()));
        assertThat(chaos.getContentFilterBlockProbability(), is(1.0));
    }

    @Test
    public void shouldRoundTripConversationPredicatesPartial() {
        // given - only some predicates set
        Expectation original = when(request().withPath("/v1/messages"))
            .thenRespondWithLlm(
                llmResponse()
                    .withProvider(Provider.OPENAI)
                    .withCompletion(completion().withText("Result"))
                    .withConversationPredicates(
                        conversationPredicates()
                            .withContainsToolResultFor("get_weather")
                    )
            );

        // when
        String json = serializer.serialize(original);
        Expectation[] deserialized = serializer.deserializeArray(json, false);

        // then
        assertThat(deserialized.length, is(1));
        ConversationPredicates preds = deserialized[0].getHttpLlmResponse().getConversationPredicates();
        assertThat(preds, is(notNullValue()));
        assertThat(preds.getContainsToolResultFor(), is("get_weather"));
        assertThat(preds.getTurnIndex(), is(org.hamcrest.Matchers.nullValue()));
    }

    @Test
    public void shouldPreserveExpectationEqualityAfterRoundTrip() {
        // given
        HttpLlmResponse llmResponse = llmResponse()
            .withProvider(Provider.GEMINI)
            .withModel("gemini-pro")
            .withCompletion(
                completion()
                    .withText("Gemini response")
                    .withStopReason("STOP")
                    .withUsage(usage().withInputTokens(5).withOutputTokens(15))
            );
        Expectation original = when(request().withPath("/v1/generateContent"))
            .thenRespondWithLlm(llmResponse);

        // when
        String json = serializer.serialize(original);
        Expectation[] deserialized = serializer.deserializeArray(json, false);

        // then - the LLM response fields are preserved
        assertThat(deserialized.length, is(1));
        assertThat(deserialized[0].getHttpLlmResponse().getProvider(), is(original.getHttpLlmResponse().getProvider()));
        assertThat(deserialized[0].getHttpLlmResponse().getModel(), is(original.getHttpLlmResponse().getModel()));
        assertThat(deserialized[0].getHttpLlmResponse().getCompletion(), is(original.getHttpLlmResponse().getCompletion()));
    }
}
