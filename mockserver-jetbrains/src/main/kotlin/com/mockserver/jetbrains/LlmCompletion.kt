package com.mockserver.jetbrains

/**
 * Pure, IDE-free LLM authoring completion for `httpLlmResponse` blocks in a
 * `*.mockserver.json` file — a direct port of the VS Code extension's
 * `llmCompletion.ts` so both editors offer the SAME curated suggestions.
 *
 * This is deliberately a lightweight authoring aid, NOT a schema (the bundled JSON
 * Schema already validates the file). It speeds up the common case of scaffolding
 * an LLM mock response: provider names after `"provider":`, model names after
 * `"model":`, otherwise the top-level `httpLlmResponse` field names.
 *
 * Everything here is pure (no IntelliJ-platform API), so it is unit-testable on the
 * plain classpath. The thin editor wiring lives in [LlmCompletionContributor].
 */
object LlmCompletion {

    /** A completion suggestion: the text to insert and a short human label/detail. */
    data class Suggestion(val insertText: String, val label: String, val detail: String)

    /**
     * The LLM providers MockServer accepts in `httpLlmResponse.provider`.
     *
     * These are the `org.mockserver.model.Provider` constants, which is also the enum the bundled
     * expectation schema declares — anything else is rejected with `400 incorrect expectation json
     * format`. Keep in step with that schema; `LlmExpectationSchemaConformanceTest` fails if this list
     * drifts from it.
     */
    val PROVIDERS: List<String> = listOf(
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
        "ORCAROUTER",
    )

    /** Representative model names per provider, for quick scaffolding. */
    val MODELS: List<String> = listOf(
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
    )

    /**
     * The top-level fields of an httpLlmResponse block, as declared by the bundled expectation schema.
     * The block sets `additionalProperties: false`, so suggesting anything else produces a document the
     * server rejects — note in particular that the completion text, streaming flag, tool calls, stop
     * reason, and token usage all live INSIDE `completion`, not at this level.
     */
    val FIELDS: List<Suggestion> = listOf(
        Suggestion("provider", "provider", "LLM provider wire format (e.g. OPENAI)"),
        Suggestion("model", "model", "Model name echoed in the mocked response"),
        Suggestion("completion", "completion", "The completion to return (text/toolCalls/usage/streaming)"),
        Suggestion("embedding", "embedding", "Mock an embeddings response (dimensions/seed)"),
        Suggestion("rerank", "rerank", "Mock a rerank response (topN/seed)"),
        Suggestion("moderation", "moderation", "Mock a moderation response (flaggedCategories)"),
        Suggestion("contentFilter", "contentFilter", "Content-filter severities (hate/sexual/violence/selfHarm)"),
        Suggestion("conversationPredicates", "conversationPredicates", "Match on conversation turn/role/content"),
        Suggestion("chaos", "chaos", "Error, truncation, quota, and rate-limit chaos"),
        Suggestion("delay", "delay", "Delay before responding"),
        Suggestion("primary", "primary", "Mark as the primary response for the conversation"),
    )

    /**
     * The fields nested inside `completion`, offered once the cursor is inside that object. These are
     * the ones most commonly wanted (the completion text, streaming, stop reason, tool calls, usage) and
     * were previously — wrongly — suggested at the top level.
     */
    val COMPLETION_FIELDS: List<Suggestion> = listOf(
        Suggestion("text", "text", "The assistant completion text to return"),
        Suggestion("toolCalls", "toolCalls", "Tool/function calls to emit (id/name/arguments)"),
        Suggestion("stopReason", "stopReason", "stop / length / tool_calls"),
        Suggestion("usage", "usage", "Token usage (inputTokens/outputTokens)"),
        Suggestion("streaming", "streaming", "Whether to stream the response (SSE)"),
        Suggestion("streamingPhysics", "streamingPhysics", "Streaming timing (timeToFirstToken/tokensPerSecond)"),
        Suggestion("outputSchema", "outputSchema", "JSON schema the completion must satisfy"),
        Suggestion("reasoningText", "reasoningText", "Reasoning/thinking text to return"),
        Suggestion("model", "model", "Model name echoed in the completion"),
    )

    private val PROVIDER_TAIL = Regex(""""provider"\s*:\s*"?[A-Z_]*$""")
    private val MODEL_TAIL = Regex(""""model"\s*:\s*"?[\w.\-]*$""")

    /**
     * Decide whether the cursor is inside an `httpLlmResponse` block, given the text
     * BEFORE the cursor. Heuristic (matching `llmCompletion.ts`): from the last
     * `"httpLlmResponse"` key onward, count braces — when more `{` than `}` have been
     * seen (and at least one `{`), the cursor is still within that object's braces.
     */
    fun isInsideLlmResponse(textBeforeCursor: String): Boolean {
        val key = "\"httpLlmResponse\""
        val keyIndex = textBeforeCursor.lastIndexOf(key)
        if (keyIndex < 0) return false
        var depth = 0
        var sawOpen = false
        for (ch in textBeforeCursor.substring(keyIndex)) {
            when (ch) {
                '{' -> { depth++; sawOpen = true }
                '}' -> depth--
            }
        }
        return sawOpen && depth > 0
    }

    /**
     * Decide whether the cursor is inside the `completion` object of an `httpLlmResponse` block, given
     * the text BEFORE the cursor. Same brace-depth heuristic as [isInsideLlmResponse], anchored on the
     * last `"completion"` key — used to offer the nested completion fields rather than the top-level
     * ones, since the two sets are disjoint and mixing them produces a document the server rejects.
     */
    fun isInsideCompletion(textBeforeCursor: String): Boolean {
        if (!isInsideLlmResponse(textBeforeCursor)) return false
        val keyIndex = textBeforeCursor.lastIndexOf("\"completion\"")
        if (keyIndex < 0) return false
        var depth = 0
        var sawOpen = false
        for (ch in textBeforeCursor.substring(keyIndex)) {
            when (ch) {
                '{' -> { depth++; sawOpen = true }
                '}' -> depth--
            }
        }
        return sawOpen && depth > 0
    }

    /**
     * Decide which suggestions to offer given the text before the cursor inside an
     * httpLlmResponse block: provider names right after a `"provider":`, model names
     * after `"model":`, the nested completion fields when inside `completion`, otherwise the top-level
     * field names. Pure.
     */
    fun suggestions(textBeforeCursor: String): List<Suggestion> {
        val tail = textBeforeCursor.takeLast(80)
        if (PROVIDER_TAIL.containsMatchIn(tail)) {
            return PROVIDERS.map { Suggestion(it, it, "LLM provider") }
        }
        if (MODEL_TAIL.containsMatchIn(tail)) {
            return MODELS.map { Suggestion(it, it, "Model name") }
        }
        if (isInsideCompletion(textBeforeCursor)) {
            return COMPLETION_FIELDS
        }
        return FIELDS
    }
}
