package org.mockserver.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.Test;
import org.mockserver.model.LlmContentFilter;
import org.mockserver.model.Provider;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.core.Is.is;

public class LlmContentFilterBodiesTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    // --- Azure annotation object ---

    @Test
    public void azureFilterResultsDefaultsToSafeUnfiltered() {
        ObjectNode results = LlmContentFilterBodies.azureFilterResults(null);
        for (String category : new String[]{"hate", "self_harm", "sexual", "violence"}) {
            assertThat(category + " severity", results.get(category).get("severity").asText(), is("safe"));
            assertThat(category + " filtered", results.get(category).get("filtered").asBoolean(), is(false));
        }
    }

    @Test
    public void azureFilterResultsFiltersAtMediumOrHigh() {
        ObjectNode results = LlmContentFilterBodies.azureFilterResults(
            LlmContentFilter.llmContentFilter().withHate("high").withViolence("low"));
        assertThat(results.get("hate").get("severity").asText(), is("high"));
        assertThat(results.get("hate").get("filtered").asBoolean(), is(true));
        // low severity is delivered (not filtered)
        assertThat(results.get("violence").get("severity").asText(), is("low"));
        assertThat(results.get("violence").get("filtered").asBoolean(), is(false));
    }

    // --- Provider-correct blocks ---

    @Test
    public void openAiBlockIs400ContentFilter() throws Exception {
        LlmContentFilterBodies.Block block = LlmContentFilterBodies.blockFor(Provider.OPENAI, null);
        assertThat(block.getStatusCode(), is(400));
        JsonNode root = MAPPER.readTree(block.getJsonBody());
        assertThat(root.get("error").get("code").asText(), is("content_filter"));
        assertThat(root.get("error").get("type").asText(), is("invalid_request_error"));
    }

    @Test
    public void openAiCompatibleProvidersUseOpenAiBlock() {
        for (Provider provider : new Provider[]{Provider.OPENAI_RESPONSES, Provider.MISTRAL, Provider.XAI, Provider.DEEPSEEK, Provider.GROQ, Provider.OPENROUTER, Provider.ORCAROUTER}) {
            LlmContentFilterBodies.Block block = LlmContentFilterBodies.blockFor(provider, null);
            assertThat(provider + " status", block.getStatusCode(), is(400));
            assertThat(provider + " body", block.getJsonBody(), containsString("content_filter"));
        }
    }

    @Test
    public void azureBlockIs400WithFilteredInnererror() throws Exception {
        LlmContentFilterBodies.Block block = LlmContentFilterBodies.blockFor(Provider.AZURE_OPENAI,
            LlmContentFilter.llmContentFilter().withHate("high"));
        assertThat(block.getStatusCode(), is(400));
        JsonNode root = MAPPER.readTree(block.getJsonBody());
        assertThat(root.get("error").get("code").asText(), is("content_filter"));
        JsonNode innererror = root.get("error").get("innererror");
        assertThat(innererror.get("code").asText(), is("ResponsibleAIPolicyViolation"));
        assertThat(innererror.get("content_filter_result").get("hate").get("filtered").asBoolean(), is(true));
        assertThat(innererror.get("content_filter_result").get("hate").get("severity").asText(), is("high"));
    }

    @Test
    public void anthropicBlockIs200Refusal() throws Exception {
        for (Provider provider : new Provider[]{Provider.ANTHROPIC, Provider.BEDROCK}) {
            LlmContentFilterBodies.Block block = LlmContentFilterBodies.blockFor(provider, null);
            assertThat(provider + " status", block.getStatusCode(), is(200));
            JsonNode root = MAPPER.readTree(block.getJsonBody());
            assertThat(provider + " stop_reason", root.get("stop_reason").asText(), is("refusal"));
        }
    }

    @Test
    public void geminiBlockIs200Safety() throws Exception {
        LlmContentFilterBodies.Block block = LlmContentFilterBodies.blockFor(Provider.GEMINI, null);
        assertThat(block.getStatusCode(), is(200));
        JsonNode root = MAPPER.readTree(block.getJsonBody());
        assertThat(root.get("candidates").get(0).get("finishReason").asText(), is("SAFETY"));
        assertThat(root.get("promptFeedback").get("blockReason").asText(), is("SAFETY"));
    }

    @Test
    public void nullProviderFallsBackToGenericBlock() throws Exception {
        LlmContentFilterBodies.Block block = LlmContentFilterBodies.blockFor(null, null);
        assertThat(block.getStatusCode(), is(400));
        JsonNode root = MAPPER.readTree(block.getJsonBody());
        assertThat(root.get("error").get("type").asText(), is("content_filter"));
    }
}
