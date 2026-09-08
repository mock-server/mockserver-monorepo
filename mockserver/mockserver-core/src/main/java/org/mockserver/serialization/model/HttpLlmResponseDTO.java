package org.mockserver.serialization.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import org.mockserver.model.*;
import org.mockserver.model.ConversationPredicates;

public class HttpLlmResponseDTO extends ObjectWithReflectiveEqualsHashCodeToString implements DTO<HttpLlmResponse> {
    private DelayDTO delay;
    private Provider provider;
    private String model;
    private CompletionDTO completion;
    private EmbeddingResponse embedding;
    private RerankResponse rerank;
    private ModerationResponse moderation;
    private LlmContentFilter contentFilter;
    private ConversationPredicates conversationPredicates;
    private LlmChaosProfile chaos;
    @JsonInclude(JsonInclude.Include.NON_DEFAULT)
    private boolean primary;

    public HttpLlmResponseDTO(HttpLlmResponse httpLlmResponse) {
        if (httpLlmResponse != null) {
            if (httpLlmResponse.getDelay() != null) {
                delay = new DelayDTO(httpLlmResponse.getDelay());
            }
            provider = httpLlmResponse.getProvider();
            model = httpLlmResponse.getModel();
            if (httpLlmResponse.getCompletion() != null) {
                completion = new CompletionDTO(httpLlmResponse.getCompletion());
            }
            embedding = httpLlmResponse.getEmbedding();
            rerank = httpLlmResponse.getRerank();
            moderation = httpLlmResponse.getModeration();
            contentFilter = httpLlmResponse.getContentFilter();
            conversationPredicates = httpLlmResponse.getConversationPredicates();
            chaos = httpLlmResponse.getChaos();
            primary = httpLlmResponse.isPrimary();
        }
    }

    public HttpLlmResponseDTO() {
    }

    public HttpLlmResponse buildObject() {
        return new HttpLlmResponse()
            .withDelay(delay != null ? delay.buildObject() : null)
            .withProvider(provider)
            .withModel(model)
            .withCompletion(completion != null ? completion.buildObject() : null)
            .withEmbedding(embedding)
            .withRerank(rerank)
            .withModeration(moderation)
            .withContentFilter(contentFilter)
            .withConversationPredicates(conversationPredicates)
            .withChaos(chaos)
            .withPrimary(primary);
    }

    public DelayDTO getDelay() {
        return delay;
    }

    public HttpLlmResponseDTO setDelay(DelayDTO delay) {
        this.delay = delay;
        return this;
    }

    public Provider getProvider() {
        return provider;
    }

    public HttpLlmResponseDTO setProvider(Provider provider) {
        this.provider = provider;
        return this;
    }

    public String getModel() {
        return model;
    }

    public HttpLlmResponseDTO setModel(String model) {
        this.model = model;
        return this;
    }

    public CompletionDTO getCompletion() {
        return completion;
    }

    public HttpLlmResponseDTO setCompletion(CompletionDTO completion) {
        this.completion = completion;
        return this;
    }

    public EmbeddingResponse getEmbedding() {
        return embedding;
    }

    public HttpLlmResponseDTO setEmbedding(EmbeddingResponse embedding) {
        this.embedding = embedding;
        return this;
    }

    public RerankResponse getRerank() {
        return rerank;
    }

    public HttpLlmResponseDTO setRerank(RerankResponse rerank) {
        this.rerank = rerank;
        return this;
    }

    public ModerationResponse getModeration() {
        return moderation;
    }

    public HttpLlmResponseDTO setModeration(ModerationResponse moderation) {
        this.moderation = moderation;
        return this;
    }

    public LlmContentFilter getContentFilter() {
        return contentFilter;
    }

    public HttpLlmResponseDTO setContentFilter(LlmContentFilter contentFilter) {
        this.contentFilter = contentFilter;
        return this;
    }

    public ConversationPredicates getConversationPredicates() {
        return conversationPredicates;
    }

    public HttpLlmResponseDTO setConversationPredicates(ConversationPredicates conversationPredicates) {
        this.conversationPredicates = conversationPredicates;
        return this;
    }

    public LlmChaosProfile getChaos() {
        return chaos;
    }

    public HttpLlmResponseDTO setChaos(LlmChaosProfile chaos) {
        this.chaos = chaos;
        return this;
    }

    public boolean isPrimary() {
        return primary;
    }

    public HttpLlmResponseDTO setPrimary(boolean primary) {
        this.primary = primary;
        return this;
    }
}
