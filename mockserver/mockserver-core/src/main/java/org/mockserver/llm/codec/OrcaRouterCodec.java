package org.mockserver.llm.codec;

import org.mockserver.model.Provider;

/**
 * Codec for OrcaRouter ({@code api.orcarouter.ai}). OrcaRouter exposes an
 * OpenAI-compatible chat API ({@code /v1/chat/completions}) that fronts many
 * upstream models, so all encoding/decoding delegates to
 * {@link OpenAiChatCompletionsCodec} via {@link OpenAiCompatibleChatCodec}.
 */
public class OrcaRouterCodec extends OpenAiCompatibleChatCodec {

    @Override
    public Provider provider() {
        return Provider.ORCAROUTER;
    }
}
