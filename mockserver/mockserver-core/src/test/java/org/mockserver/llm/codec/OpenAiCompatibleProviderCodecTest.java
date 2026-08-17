package org.mockserver.llm.codec;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.Test;
import org.mockserver.llm.ProviderCodec;
import org.mockserver.llm.ProviderCodecRegistry;
import org.mockserver.llm.ProviderDetector;
import org.mockserver.llm.client.LlmClientRegistry;
import org.mockserver.llm.client.LlmProviderSniffer;
import org.mockserver.llm.cost.LlmPricing;
import org.mockserver.model.Completion;
import org.mockserver.model.HttpResponse;
import org.mockserver.model.Provider;

import java.util.Optional;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.closeTo;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.mockserver.model.Completion.completion;
import static org.mockserver.model.HttpRequest.request;

/**
 * Covers the OpenAI-chat-compatible alias providers (Mistral, xAI/Grok, DeepSeek,
 * Groq, OpenRouter, OrcaRouter): codec delegation, registry/client registration, host-based
 * provider detection (sniffer + detector), and pricing rows.
 */
public class OpenAiCompatibleProviderCodecTest {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private static final Provider[] ALIASES = {
        Provider.MISTRAL, Provider.XAI, Provider.DEEPSEEK, Provider.GROQ, Provider.OPENROUTER,
        Provider.ORCAROUTER
    };

    // --- codec delegation ----------------------------------------------------

    @Test
    public void eachAliasCodecReportsItsProviderAndProducesOpenAiChatShape() throws Exception {
        Completion completion = completion().withText("hello world");
        for (Provider provider : ALIASES) {
            ProviderCodec codec = ProviderCodecRegistry.getInstance().lookup(provider)
                .orElseThrow(() -> new AssertionError("no codec for " + provider));
            assertThat("provider() for " + provider, codec.provider(), is(provider));

            HttpResponse encoded = codec.encode(completion, "some-model");
            assertThat("status for " + provider, encoded.getStatusCode(), is(200));
            JsonNode root = OBJECT_MAPPER.readTree(encoded.getBodyAsString());
            // Delegation to OpenAiChatCompletionsCodec is proven by the OpenAI Chat
            // Completions wire shape (object == chat.completion, assistant content echoed).
            assertThat("object for " + provider, root.path("object").asText(), is("chat.completion"));
            assertThat("model for " + provider, root.path("model").asText(), is("some-model"));
            JsonNode message = root.path("choices").path(0).path("message");
            assertThat("role for " + provider, message.path("role").asText(), is("assistant"));
            assertThat("content for " + provider, message.path("content").asText(), is("hello world"));
        }
    }

    // --- registry / client registration --------------------------------------

    @Test
    public void eachAliasHasACodecAndAClientRegistered() {
        for (Provider provider : ALIASES) {
            assertThat("codec registered for " + provider,
                ProviderCodecRegistry.getInstance().lookup(provider).isPresent(), is(true));
            assertThat("client registered for " + provider,
                LlmClientRegistry.getInstance().lookup(provider).isPresent(), is(true));
            assertThat("client provider() for " + provider,
                LlmClientRegistry.getInstance().lookup(provider).get().provider(), is(provider));
        }
    }

    @Test
    public void aliasClientParsesOpenAiShapedResponse() {
        // Inherited from OpenAiLlmClient — parse an OpenAI-shaped chat completion.
        Completion parsed = LlmClientRegistry.getInstance().lookup(Provider.DEEPSEEK).get()
            .parseCompletionResponse(HttpResponse.response()
                .withStatusCode(200)
                .withBody("{\"model\":\"deepseek-chat\",\"choices\":[{\"message\":{\"role\":\"assistant\","
                    + "\"content\":\"Paris\"},\"finish_reason\":\"stop\"}],"
                    + "\"usage\":{\"prompt_tokens\":10,\"completion_tokens\":2}}"));
        assertThat(parsed.getText(), is("Paris"));
        assertThat(parsed.getModel(), is("deepseek-chat"));
        assertThat(parsed.getUsage().getOutputTokens(), is(2));
    }

    // --- host-based detection (sniffer) --------------------------------------

    @Test
    public void snifferMapsEachAliasHostToItsProvider() {
        assertThat(LlmProviderSniffer.sniffByHost("api.mistral.ai"), is(Optional.of(Provider.MISTRAL)));
        assertThat(LlmProviderSniffer.sniffByHost("api.x.ai"), is(Optional.of(Provider.XAI)));
        assertThat(LlmProviderSniffer.sniffByHost("api.deepseek.com"), is(Optional.of(Provider.DEEPSEEK)));
        assertThat(LlmProviderSniffer.sniffByHost("api.groq.com"), is(Optional.of(Provider.GROQ)));
        assertThat(LlmProviderSniffer.sniffByHost("openrouter.ai"), is(Optional.of(Provider.OPENROUTER)));
        assertThat(LlmProviderSniffer.sniffByHost("api.orcarouter.ai"), is(Optional.of(Provider.ORCAROUTER)));
    }

    @Test
    public void snifferClassifiesForwardedAliasTrafficAsLlm() {
        // A forwarded /chat/completions to a Mistral host is now classified as LLM
        // (previously dropped as non-LLM because the host was unknown).
        Optional<Provider> provider = LlmProviderSniffer.sniff(request()
            .withPath("/v1/chat/completions")
            .withHeader("Host", "api.mistral.ai"));
        assertThat(provider, is(Optional.of(Provider.MISTRAL)));
    }

    // --- host-based detection (detector) -------------------------------------

    @Test
    public void detectorMapsEachAliasHostToItsProvider() {
        assertThat(ProviderDetector.detectFromHost("api.mistral.ai"), is(Optional.of(Provider.MISTRAL)));
        assertThat(ProviderDetector.detectFromHost("api.x.ai"), is(Optional.of(Provider.XAI)));
        assertThat(ProviderDetector.detectFromHost("api.deepseek.com"), is(Optional.of(Provider.DEEPSEEK)));
        assertThat(ProviderDetector.detectFromHost("api.groq.com"), is(Optional.of(Provider.GROQ)));
        assertThat(ProviderDetector.detectFromHost("openrouter.ai"), is(Optional.of(Provider.OPENROUTER)));
        assertThat(ProviderDetector.detectFromHost("api.orcarouter.ai"), is(Optional.of(Provider.ORCAROUTER)));
    }

    @Test
    public void detectorPrefersAliasHostOverSharedChatCompletionsPath() {
        // Host wins over the shared /chat/completions path (which alone is OPENAI).
        assertThat(ProviderDetector.detect(request()
            .withPath("/v1/chat/completions")
            .withHeader("Host", "api.groq.com")), is(Optional.of(Provider.GROQ)));
        // A non-alias host still detects by path (behaviour unchanged).
        assertThat(ProviderDetector.detect(request()
            .withPath("/v1/chat/completions")
            .withHeader("Host", "api.openai.com")), is(Optional.of(Provider.OPENAI)));
    }

    // --- pricing --------------------------------------------------------------

    @Test
    public void aliasPricingRowsArePresentAndFlaggedApproximate() {
        assertThat(LlmPricing.getPricing(Provider.MISTRAL, "mistral-large-latest"), is(notNullValue()));
        assertThat(LlmPricing.isApproximateRate(Provider.MISTRAL, "mistral-large-latest"), is(true));
        assertThat(LlmPricing.isApproximateRate(Provider.XAI, "grok-3"), is(true));
        assertThat(LlmPricing.isApproximateRate(Provider.DEEPSEEK, "deepseek-chat"), is(true));
        assertThat(LlmPricing.isApproximateRate(Provider.GROQ, "llama-3.3-70b-versatile"), is(true));
    }

    @Test
    public void aliasCostEstimatesUseTheirOwnRates() {
        // deepseek-chat 0.27 in + 1.10 out per million → 1.37 for 1M/1M
        assertThat(LlmPricing.estimateCostUsd(Provider.DEEPSEEK, "deepseek-chat", 1_000_000, 1_000_000),
            is(closeTo(1.37, 1e-9)));
        // grok-3 3 + 15 = 18
        assertThat(LlmPricing.estimateCostUsd(Provider.XAI, "grok-3", 1_000_000, 1_000_000),
            is(closeTo(18.0, 1e-9)));
        // groq llama-3.1-8b 0.05 + 0.08 = 0.13
        assertThat(LlmPricing.estimateCostUsd(Provider.GROQ, "llama-3.1-8b-instant", 1_000_000, 1_000_000),
            is(closeTo(0.13, 1e-9)));
    }

    @Test
    public void openRouterRoutesVendorPrefixedModelsToTheUnderlyingTable() {
        // openai/gpt-4o → OpenAI gpt-4o 2.5 + 10 = 12.5
        assertThat(LlmPricing.estimateCostUsd(Provider.OPENROUTER, "openai/gpt-4o", 1_000_000, 1_000_000),
            is(closeTo(12.5, 1e-9)));
        // anthropic/claude-sonnet-4-x → Anthropic sonnet-4 3 + 15 = 18
        assertThat(LlmPricing.estimateCostUsd(Provider.OPENROUTER, "anthropic/claude-sonnet-4-x", 1_000_000, 1_000_000),
            is(closeTo(18.0, 1e-9)));
        // A bare (unprefixed) id is unpriceable for OpenRouter.
        assertThat(LlmPricing.getPricing(Provider.OPENROUTER, "gpt-4o"), is(nullValue()));
    }

    @Test
    public void orcaRouterRoutesVendorPrefixedModelsToTheUnderlyingTable() {
        // OrcaRouter mirrors OpenRouter: vendor-prefixed ids route to the underlying
        // vendor's table, so anthropic/claude-sonnet-4-x → Anthropic sonnet-4 3 + 15 = 18.
        assertThat(LlmPricing.estimateCostUsd(Provider.ORCAROUTER, "anthropic/claude-sonnet-4-x", 1_000_000, 1_000_000),
            is(closeTo(18.0, 1e-9)));
        // A bare (unprefixed) id is unpriceable for OrcaRouter too.
        assertThat(LlmPricing.getPricing(Provider.ORCAROUTER, "claude-sonnet-5"), is(nullValue()));
    }
}
