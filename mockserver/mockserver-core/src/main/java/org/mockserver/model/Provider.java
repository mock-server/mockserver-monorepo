package org.mockserver.model;

public enum Provider {
    ANTHROPIC,
    OPENAI,
    OPENAI_RESPONSES,
    GEMINI,
    BEDROCK,
    AZURE_OPENAI,
    OLLAMA,
    COHERE,
    VOYAGE,
    // OpenAI-chat-compatible providers: identical Chat Completions wire format on a
    // different host, so their codecs/clients delegate to the OpenAI implementations
    // and are distinguished only by host (see LlmProviderSniffer / ProviderDetector).
    MISTRAL,
    XAI,
    DEEPSEEK,
    GROQ,
    OPENROUTER,
    ORCAROUTER
}
