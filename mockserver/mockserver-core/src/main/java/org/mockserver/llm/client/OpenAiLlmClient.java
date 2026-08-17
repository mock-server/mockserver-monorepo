package org.mockserver.llm.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.mockserver.llm.ParsedConversation;
import org.mockserver.model.Completion;
import org.mockserver.model.HttpRequest;
import org.mockserver.model.HttpResponse;
import org.mockserver.model.Provider;
import org.mockserver.model.Usage;

/**
 * Runtime client for the OpenAI Chat Completions API
 * ({@code POST /v1/chat/completions}, {@code Authorization: Bearer}).
 */
public class OpenAiLlmClient extends AbstractLlmClient {

    static final String DEFAULT_BASE_URL = "https://api.openai.com";
    static final String DEFAULT_MODEL = "gpt-4o-mini";

    @Override
    public Provider provider() {
        return Provider.OPENAI;
    }

    /**
     * The default base URL used when the backend does not supply one. OpenAI-compatible
     * subclasses (Mistral, xAI, DeepSeek, Groq, OpenRouter, OrcaRouter) override this to point at
     * their own host while inheriting the identical request/response wire format.
     */
    protected String defaultBaseUrl() {
        return DEFAULT_BASE_URL;
    }

    @Override
    public HttpRequest buildCompletionRequest(LlmBackend backend, ParsedConversation prompt) {
        String baseUrl = resolveBaseUrl(backend, defaultBaseUrl());
        ObjectNode body = OBJECT_MAPPER.createObjectNode();
        body.put("model", resolveModel(backend, DEFAULT_MODEL));
        body.put("temperature", 0);
        body.put("seed", 0);
        ArrayNode messages = body.putArray("messages");
        appendRoleContentMessages(messages, prompt, "system", "user", "assistant", "tool");
        HttpRequest request = postJson(backend, baseUrl, "/v1/chat/completions", writeJson(body));
        if (backend.hasApiKey()) {
            request.withHeader("Authorization", "Bearer " + backend.apiKey());
        }
        return request;
    }

    @Override
    public Completion parseCompletionResponse(HttpResponse response) {
        JsonNode root = readBody(response);
        Completion completion = Completion.completion();
        if (root.hasNonNull("model")) {
            completion.withModel(root.path("model").asText());
        }
        JsonNode choice = root.path("choices").path(0);
        JsonNode message = choice.path("message");
        if (message.hasNonNull("content")) {
            completion.withText(message.path("content").asText());
        }
        if (choice.hasNonNull("finish_reason")) {
            completion.withStopReason(choice.path("finish_reason").asText());
        }
        JsonNode usageNode = root.path("usage");
        if (usageNode.isObject()) {
            Usage usage = Usage.usage();
            if (usageNode.has("prompt_tokens")) {
                usage.withInputTokens(usageNode.path("prompt_tokens").asInt());
            }
            if (usageNode.has("completion_tokens")) {
                usage.withOutputTokens(usageNode.path("completion_tokens").asInt());
            }
            JsonNode promptDetails = usageNode.path("prompt_tokens_details");
            if (promptDetails.isObject() && promptDetails.has("cached_tokens")) {
                usage.withCachedInputTokens(promptDetails.path("cached_tokens").asInt());
            }
            JsonNode completionDetails = usageNode.path("completion_tokens_details");
            if (completionDetails.isObject() && completionDetails.has("reasoning_tokens")) {
                usage.withReasoningTokens(completionDetails.path("reasoning_tokens").asInt());
            }
            completion.withUsage(usage);
        }
        return completion;
    }
}
