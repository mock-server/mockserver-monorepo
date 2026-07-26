package com.mockserver.jetbrains

import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser

/**
 * Pure builder that turns the LLM tool-window form fields into a MockServer
 * expectation JSON document with an `httpLlmResponse` action — the JetBrains parity
 * of the VS Code LLM-authoring aids. Kept IDE-free so it is unit-testable.
 *
 * The produced expectation matches on `path` (and an optional `method`) and responds
 * with an `httpLlmResponse` carrying the provider/model/completion the user typed.
 * Optional token usage and streaming are included only when set.
 *
 * The output MUST satisfy the expectation JSON Schema bundled at
 * `/schemas/mockserver-expectation.schema.json` — the same schema MockServer validates against, which
 * rejects anything else with `400 incorrect expectation json format`.
 * `LlmExpectationSchemaConformanceTest` enforces that; assert against the schema rather than against
 * this builder's own output, or a wrong document looks correct (issue #2455).
 */
object LlmExpectationBuilder {

    private val PRETTY: Gson = com.google.gson.GsonBuilder().setPrettyPrinting().create()

    /** The form inputs for an LLM expectation. [completion] and [provider] are required upstream. */
    data class Form(
        val path: String,
        val method: String?,
        val provider: String,
        val model: String?,
        val completion: String,
        val stream: Boolean = false,
        val promptTokens: Int? = null,
        val completionTokens: Int? = null,
        val finishReason: String? = null,
    )

    /**
     * Build the pretty-printed expectation JSON for [form]. Throws
     * [IllegalArgumentException] when a required field (path, provider, completion)
     * is blank.
     */
    fun build(form: Form): String {
        require(form.path.isNotBlank()) { "Path is required." }
        require(form.provider.isNotBlank()) { "Provider is required." }
        require(form.completion.isNotBlank()) { "Completion text is required." }

        val httpRequest = JsonObject().apply {
            form.method?.trim()?.takeIf { it.isNotEmpty() }?.let { addProperty("method", it) }
            addProperty("path", form.path.trim())
        }

        // The completion text, streaming flag, stop reason, and token usage all live INSIDE the
        // `completion` object; `httpLlmResponse` sets additionalProperties:false, so emitting them at
        // the top level (as this builder once did) is rejected with
        // `400 incorrect expectation json format` — issue #2455.
        val completion = JsonObject().apply {
            addProperty("text", form.completion)
            if (form.stream) addProperty("streaming", true)
            form.finishReason?.trim()?.takeIf { it.isNotEmpty() }?.let { addProperty("stopReason", it) }
            if (form.promptTokens != null || form.completionTokens != null) {
                add("usage", JsonObject().apply {
                    form.promptTokens?.let { addProperty("inputTokens", it) }
                    form.completionTokens?.let { addProperty("outputTokens", it) }
                })
            }
        }

        val httpLlmResponse = JsonObject().apply {
            addProperty("provider", form.provider.trim())
            form.model?.trim()?.takeIf { it.isNotEmpty() }?.let { addProperty("model", it) }
            add("completion", completion)
        }

        val expectation = JsonObject().apply {
            add("httpRequest", httpRequest)
            add("httpLlmResponse", httpLlmResponse)
        }
        return PRETTY.toJson(expectation)
    }

    /**
     * Wrap one or more `httpRequest` definitions from an expectation file into the
     * JSON body MockServer's `PUT /mockserver/expectation` accepts (the document is
     * sent as-is). Exposed for symmetry/testing; the action sends the built text.
     */
    fun asExpectationArray(expectationJson: String): String {
        val parsed = JsonParser.parseString(expectationJson)
        return if (parsed.isJsonArray) PRETTY.toJson(parsed) else PRETTY.toJson(JsonArray().apply { add(parsed) })
    }
}
