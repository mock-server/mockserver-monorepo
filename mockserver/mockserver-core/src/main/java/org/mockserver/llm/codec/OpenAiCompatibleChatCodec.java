package org.mockserver.llm.codec;

import org.mockserver.llm.ParsedConversation;
import org.mockserver.llm.ProviderCodec;
import org.mockserver.model.*;

import java.util.List;

/**
 * Base codec for OpenAI-chat-compatible providers (Mistral, xAI/Grok, DeepSeek,
 * Groq, OpenRouter, OrcaRouter). These providers expose the OpenAI Chat Completions wire format
 * ({@code POST /v1/chat/completions}, {@code Authorization: Bearer}) on a different
 * host, so — exactly like {@link AzureOpenAiCodec} — all encoding and decoding
 * delegates to {@link OpenAiChatCompletionsCodec}; only {@link #provider()} differs
 * per subclass.
 * <p>
 * Because the wire shape is byte-identical to OpenAI's, the OpenAI golden fixtures
 * cover the response format; these aliases are exercised by {@code
 * OpenAiCompatibleProviderCodecTest} instead of dedicated golden files.
 */
public abstract class OpenAiCompatibleChatCodec implements ProviderCodec {

    private final OpenAiChatCompletionsCodec delegate = new OpenAiChatCompletionsCodec();

    @Override
    public abstract Provider provider();

    @Override
    public String apiVersion() {
        return delegate.apiVersion();
    }

    @Override
    public HttpResponse encode(Completion completion, String model) {
        return delegate.encode(completion, model);
    }

    @Override
    public List<SseEvent> encodeStreaming(Completion completion, String model, StreamingPhysics physics) {
        return delegate.encodeStreaming(completion, model, physics);
    }

    @Override
    public ParsedConversation decode(HttpRequest request) {
        return delegate.decode(request);
    }

    @Override
    public HttpResponse encodeEmbedding(EmbeddingResponse embedding, String input) {
        return delegate.encodeEmbedding(embedding, input);
    }
}
