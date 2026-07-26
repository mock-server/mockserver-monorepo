// Phase 6 LLM authoring completion for `httpLlmResponse`. Provides a small,
// curated set of provider/model and field completions inside an `httpLlmResponse`
// block of a `*.mockserver.json` file. The catalogue is static (no network) and
// `vscode`-free so the suggestion logic can be unit-tested.
//
// This is deliberately a lightweight authoring aid, NOT a schema (the bundled
// JSON Schema already validates the file). It speeds up the common case of
// scaffolding an LLM mock response.

/** A completion suggestion: the text to insert and a short human label/detail. */
export interface LlmCompletion {
    insertText: string;
    label: string;
    detail: string;
}

/**
 * The LLM providers MockServer accepts in `httpLlmResponse.provider`.
 *
 * These are the `org.mockserver.model.Provider` constants, which is also the enum the bundled
 * expectation schema declares — anything else is rejected with `400 incorrect expectation json
 * format`. Keep in step with `schemas/mockserver-expectation.schema.json`; the extension test suite
 * fails if this list drifts from it.
 */
export const LLM_PROVIDERS = [
    "ANTHROPIC",
    "OPENAI",
    "OPENAI_RESPONSES",
    "GEMINI",
    "BEDROCK",
    "AZURE_OPENAI",
    "OLLAMA",
    "COHERE",
    "VOYAGE",
    "MISTRAL",
    "XAI",
    "DEEPSEEK",
    "GROQ",
    "OPENROUTER",
];

/** Representative model names per provider, for quick scaffolding. */
export const LLM_MODELS = [
    "gpt-4o",
    "gpt-4o-mini",
    "o1",
    "claude-3-7-sonnet",
    "claude-3-5-haiku",
    "gemini-1.5-pro",
    "gemini-2.0-flash",
    "command-r-plus",
    "mistral-large-latest",
    "llama3.3",
];

/**
 * The top-level fields of an httpLlmResponse block, as declared by the bundled expectation schema.
 * The block sets `additionalProperties: false`, so suggesting anything else produces a document the
 * server rejects — note in particular that the completion text, streaming flag, tool calls, stop
 * reason, and token usage all live INSIDE `completion`, not at this level (see LLM_COMPLETION_FIELDS).
 */
export const LLM_FIELDS: LlmCompletion[] = [
    { insertText: "provider", label: "provider", detail: "LLM provider wire format (e.g. OPENAI)" },
    { insertText: "model", label: "model", detail: "Model name echoed in the mocked response" },
    { insertText: "completion", label: "completion", detail: "The completion to return (text/toolCalls/usage/streaming)" },
    { insertText: "embedding", label: "embedding", detail: "Mock an embeddings response (dimensions/seed)" },
    { insertText: "rerank", label: "rerank", detail: "Mock a rerank response (topN/seed)" },
    { insertText: "moderation", label: "moderation", detail: "Mock a moderation response (flaggedCategories)" },
    { insertText: "contentFilter", label: "contentFilter", detail: "Content-filter severities (hate/sexual/violence/selfHarm)" },
    { insertText: "conversationPredicates", label: "conversationPredicates", detail: "Match on conversation turn/role/content" },
    { insertText: "chaos", label: "chaos", detail: "Error, truncation, quota, and rate-limit chaos" },
    { insertText: "delay", label: "delay", detail: "Delay before responding" },
    { insertText: "primary", label: "primary", detail: "Mark as the primary response for the conversation" },
];

/**
 * The fields nested inside `completion`, offered once the cursor is inside that object. These are the
 * ones most commonly wanted (the completion text, streaming, stop reason, tool calls, usage) and were
 * previously — wrongly — suggested at the top level.
 */
export const LLM_COMPLETION_FIELDS: LlmCompletion[] = [
    { insertText: "text", label: "text", detail: "The assistant completion text to return" },
    { insertText: "toolCalls", label: "toolCalls", detail: "Tool/function calls to emit (id/name/arguments)" },
    { insertText: "stopReason", label: "stopReason", detail: "stop / length / tool_calls" },
    { insertText: "usage", label: "usage", detail: "Token usage (inputTokens/outputTokens)" },
    { insertText: "streaming", label: "streaming", detail: "Whether to stream the response (SSE)" },
    { insertText: "streamingPhysics", label: "streamingPhysics", detail: "Streaming timing (timeToFirstToken/tokensPerSecond)" },
    { insertText: "outputSchema", label: "outputSchema", detail: "JSON schema the completion must satisfy" },
    { insertText: "reasoningText", label: "reasoningText", detail: "Reasoning/thinking text to return" },
    { insertText: "model", label: "model", detail: "Model name echoed in the completion" },
];

/**
 * Decide whether the cursor is inside an `httpLlmResponse` block, given the text
 * BEFORE the cursor. Heuristic: the last `httpLlmResponse` key occurs after the
 * last top-level action key change — i.e. we are still within that object's
 * braces. We approximate "within braces" by counting unbalanced `{` after the
 * `httpLlmResponse` key. Pure and `vscode`-free.
 */
export function isInsideLlmResponse(textBeforeCursor: string): boolean {
    const key = "\"httpLlmResponse\"";
    const keyIndex = textBeforeCursor.lastIndexOf(key);
    if (keyIndex < 0) {
        return false;
    }
    // From the key onward, count braces. If we are still inside (more `{` than `}`
    // seen since the key's opening brace), the cursor is within the block.
    const after = textBeforeCursor.slice(keyIndex);
    let depth = 0;
    let sawOpen = false;
    for (const ch of after) {
        if (ch === "{") {
            depth++;
            sawOpen = true;
        } else if (ch === "}") {
            depth--;
        }
    }
    return sawOpen && depth > 0;
}

/**
 * Decide whether the cursor is inside the `completion` object of an `httpLlmResponse` block, given the
 * text BEFORE the cursor. Same brace-depth heuristic as {@link isInsideLlmResponse}, anchored on the
 * last `"completion"` key — used to offer the nested completion fields rather than the top-level ones,
 * since the two sets are disjoint and mixing them produces a document the server rejects.
 */
export function isInsideCompletion(textBeforeCursor: string): boolean {
    if (!isInsideLlmResponse(textBeforeCursor)) {
        return false;
    }
    const keyIndex = textBeforeCursor.lastIndexOf("\"completion\"");
    if (keyIndex < 0) {
        return false;
    }
    const after = textBeforeCursor.slice(keyIndex);
    let depth = 0;
    let sawOpen = false;
    for (const ch of after) {
        if (ch === "{") {
            depth++;
            sawOpen = true;
        } else if (ch === "}") {
            depth--;
        }
    }
    return sawOpen && depth > 0;
}

/**
 * Decide which suggestions to offer given the text before the cursor inside an
 * httpLlmResponse block: provider names right after a `"provider":`, model names
 * after `"model":`, the nested completion fields when inside `completion`, otherwise the top-level
 * field names. Pure and `vscode`-free.
 */
export function llmSuggestions(textBeforeCursor: string): LlmCompletion[] {
    const tail = textBeforeCursor.slice(-80);
    if (/"provider"\s*:\s*"?[A-Z_]*$/.test(tail)) {
        return LLM_PROVIDERS.map((p) => ({ insertText: p, label: p, detail: "LLM provider" }));
    }
    if (/"model"\s*:\s*"?[\w.\-]*$/.test(tail)) {
        return LLM_MODELS.map((m) => ({ insertText: m, label: m, detail: "Model name" }));
    }
    if (isInsideCompletion(textBeforeCursor)) {
        return LLM_COMPLETION_FIELDS;
    }
    return LLM_FIELDS;
}
