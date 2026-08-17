package org.mockserver.llm.client;

import org.mockserver.model.Provider;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Singleton registry of {@link LlmClient} implementations keyed by
 * {@link Provider}. Structurally identical to
 * {@link org.mockserver.llm.ProviderCodecRegistry}: clients are registered in a
 * static initializer at boot. Adding a provider = implement {@link LlmClient}
 * and add one {@code register(...)} line here.
 */
public class LlmClientRegistry {

    private static final LlmClientRegistry INSTANCE = new LlmClientRegistry();

    static {
        INSTANCE.register(new OllamaLlmClient());
        INSTANCE.register(new OpenAiLlmClient());
        INSTANCE.register(new OpenAiResponsesLlmClient());
        INSTANCE.register(new AzureOpenAiLlmClient());
        INSTANCE.register(new AnthropicLlmClient());
        INSTANCE.register(new GeminiLlmClient());
        INSTANCE.register(new BedrockLlmClient());
        // OpenAI-chat-compatible aliases (delegate parsing to OpenAiLlmClient)
        INSTANCE.register(new MistralLlmClient());
        INSTANCE.register(new XaiLlmClient());
        INSTANCE.register(new DeepSeekLlmClient());
        INSTANCE.register(new GroqLlmClient());
        INSTANCE.register(new OpenRouterLlmClient());
        INSTANCE.register(new OrcaRouterLlmClient());
    }

    private final ConcurrentHashMap<Provider, LlmClient> clients = new ConcurrentHashMap<>();

    public static LlmClientRegistry getInstance() {
        return INSTANCE;
    }

    public void register(LlmClient client) {
        clients.put(client.provider(), client);
    }

    public Optional<LlmClient> lookup(Provider provider) {
        return Optional.ofNullable(clients.get(provider));
    }

    public List<String> supportedProviderNames() {
        List<String> names = new ArrayList<>();
        for (Provider p : clients.keySet()) {
            names.add(p.name());
        }
        Collections.sort(names);
        return names;
    }
}
