package org.mockserver.llm.client;

import org.mockserver.model.Provider;

/**
 * Runtime client for OrcaRouter. OpenAI-chat-compatible, so it inherits request
 * building and response parsing from {@link OpenAiLlmClient}. The default base URL
 * ({@code https://api.orcarouter.ai}) combines with the inherited
 * {@code /v1/chat/completions} path to reach the Chat Completions endpoint.
 */
public class OrcaRouterLlmClient extends OpenAiLlmClient {

    @Override
    public Provider provider() {
        return Provider.ORCAROUTER;
    }

    @Override
    protected String defaultBaseUrl() {
        return "https://api.orcarouter.ai";
    }
}
