package com.mockserver.jetbrains

import com.google.gson.JsonParser
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Unit tests for the LLM authoring aids (JB1): the pure completion catalogue
 * [LlmCompletion], the [LlmExpectationBuilder] form-to-expectation builder, the
 * [AgentCallGraph] parse/Mermaid/MCP helpers, and the [MermaidRenderer] HTML escaping.
 * All assertions are IDE-free.
 */
class LlmAuthoringTest {

    // --- LlmCompletion --------------------------------------------------

    @Test
    fun `inside detection follows the httpLlmResponse brace depth`() {
        assertFalse(LlmCompletion.isInsideLlmResponse("""{ "httpRequest": { "path": "/x" """))
        assertTrue(LlmCompletion.isInsideLlmResponse("""{ "httpLlmResponse": { "provider": """))
        // Closed block: cursor is no longer inside.
        assertFalse(LlmCompletion.isInsideLlmResponse("""{ "httpLlmResponse": { "provider": "OPENAI" } } """))
    }

    @Test
    fun `provider suggestions fire right after a provider key`() {
        val providers = LlmCompletion.suggestions("""{ "httpLlmResponse": { "provider": "O""").map { it.insertText }
        assertTrue(providers.contains("OPENAI"))
        assertTrue(providers.contains("ANTHROPIC"))
    }

    @Test
    fun `model suggestions fire right after a model key`() {
        val models = LlmCompletion.suggestions("""{ "httpLlmResponse": { "model": "gpt""").map { it.insertText }
        assertTrue(models.contains("gpt-4o"))
    }

    @Test
    fun `field suggestions are the default`() {
        val fields = LlmCompletion.suggestions("""{ "httpLlmResponse": { """).map { it.insertText }
        assertTrue(fields.contains("provider"))
        assertTrue(fields.contains("completion"))
        // usage/streaming/stopReason are nested under completion, NOT top-level fields
        assertFalse(fields.contains("usage"))
    }

    @Test
    fun `completion suggestions apply inside the completion object`() {
        val inside = """{ "httpLlmResponse": { "provider": "OPENAI", "completion": { """
        assertTrue(LlmCompletion.isInsideCompletion(inside))

        val fields = LlmCompletion.suggestions(inside).map { it.insertText }
        assertTrue(fields.contains("text"))
        assertTrue(fields.contains("usage"))
        assertTrue(fields.contains("streaming"))
        assertTrue(fields.contains("stopReason"))
        // the top-level-only fields must not leak into the nested object
        assertFalse(fields.contains("provider"))
    }

    @Test
    fun `a closed completion object falls back to the top level fields`() {
        val afterCompletion = """{ "httpLlmResponse": { "completion": { "text": "hi" }, """
        assertFalse(LlmCompletion.isInsideCompletion(afterCompletion))
        assertTrue(LlmCompletion.suggestions(afterCompletion).map { it.insertText }.contains("provider"))
    }

    // --- LlmExpectationBuilder ------------------------------------------

    @Test
    fun `builds an httpLlmResponse expectation with usage and streaming nested under completion`() {
        val json = LlmExpectationBuilder.build(
            LlmExpectationBuilder.Form(
                path = "/v1/chat/completions",
                method = "POST",
                provider = "OPENAI",
                model = "gpt-4o",
                completion = "Hello!",
                stream = true,
                promptTokens = 10,
                completionTokens = 5,
                finishReason = "stop",
            )
        )
        val obj = JsonParser.parseString(json).asJsonObject
        assertEquals("POST", obj.getAsJsonObject("httpRequest").get("method").asString)
        assertEquals("/v1/chat/completions", obj.getAsJsonObject("httpRequest").get("path").asString)
        val llm = obj.getAsJsonObject("httpLlmResponse")
        assertEquals("OPENAI", llm.get("provider").asString)
        assertEquals("gpt-4o", llm.get("model").asString)

        // completion is an OBJECT carrying the text, streaming flag, stop reason, and usage — a flat
        // string here (and top-level stream/finishReason/usage) is what the server 400s on (#2455)
        val completion = llm.getAsJsonObject("completion")
        assertEquals("Hello!", completion.get("text").asString)
        assertTrue(completion.get("streaming").asBoolean)
        assertEquals("stop", completion.get("stopReason").asString)
        assertEquals(10, completion.getAsJsonObject("usage").get("inputTokens").asInt)
        assertEquals(5, completion.getAsJsonObject("usage").get("outputTokens").asInt)
        assertFalse(llm.has("stream"))
        assertFalse(llm.has("finishReason"))
        assertFalse(llm.has("usage"))
    }

    @Test
    fun `omits optional fields and requires path provider completion`() {
        val json = LlmExpectationBuilder.build(
            LlmExpectationBuilder.Form(
                path = "/x", method = "", provider = "ANTHROPIC", model = "", completion = "hi"
            )
        )
        val obj = JsonParser.parseString(json).asJsonObject
        assertFalse(obj.getAsJsonObject("httpRequest").has("method"))
        val llm = obj.getAsJsonObject("httpLlmResponse")
        assertFalse(llm.has("model"))
        val completion = llm.getAsJsonObject("completion")
        assertEquals("hi", completion.get("text").asString)
        assertFalse(completion.has("usage"))
        assertFalse(completion.has("streaming"))
        assertFalse(completion.has("stopReason"))

        assertThrows<IllegalArgumentException> {
            LlmExpectationBuilder.build(LlmExpectationBuilder.Form("", null, "OPENAI", null, "x"))
        }
        assertThrows<IllegalArgumentException> {
            LlmExpectationBuilder.build(LlmExpectationBuilder.Form("/x", null, "", null, "x"))
        }
        assertThrows<IllegalArgumentException> {
            LlmExpectationBuilder.build(LlmExpectationBuilder.Form("/x", null, "OPENAI", null, ""))
        }
    }

    // --- AgentCallGraph -------------------------------------------------

    @Test
    fun `parse and render a call graph as mermaid`() {
        val raw = JsonParser.parseString(
            """
            {
              "nodes": [
                { "id": "n1", "kind": "USER", "label": "ask weather" },
                { "id": "n2", "kind": "TOOL_CALL", "label": "get_weather" }
              ],
              "edges": [ { "from": "n1", "to": "n2", "kind": "INVOKES" } ]
            }
            """.trimIndent()
        )
        val graph = AgentCallGraph.parseCallGraph(raw)!!
        assertEquals(2, graph.nodes.size)
        val mermaid = AgentCallGraph.toMermaid(graph)
        assertTrue(mermaid.startsWith("flowchart TD"))
        assertTrue(mermaid.contains("""n1["USER: ask weather"]"""))
        assertTrue(mermaid.contains("n2([get_weather])"))
        assertTrue(mermaid.contains("n1 -->|INVOKES| n2"))
    }

    @Test
    fun `parseMcpToolResult unwraps the content text json`() {
        val body = """
            { "jsonrpc": "2.0", "id": 1, "result": {
                "content": [ { "type": "text", "text": "{\"callGraph\": {\"nodes\": [], \"edges\": []}}" } ]
            } }
        """.trimIndent()
        val result = AgentCallGraph.parseMcpToolResult(body)!!
        assertTrue(result.has("callGraph"))
        val graph = AgentCallGraph.parseCallGraph(result.get("callGraph"))!!
        assertEquals(0, graph.nodes.size)
    }

    @Test
    fun `parseMcpToolResult returns null on an error envelope`() {
        assertEquals(null, AgentCallGraph.parseMcpToolResult("""{ "jsonrpc": "2.0", "error": { "code": -1 } }"""))
        assertEquals(null, AgentCallGraph.parseMcpToolResult("not json"))
    }

    @Test
    fun `mcp request bodies carry the explain_agent_run tool and arguments`() {
        val init = JsonParser.parseString(AgentCallGraph.buildInitializeBody()).asJsonObject
        assertEquals("initialize", init.get("method").asString)
        val call = JsonParser.parseString(
            AgentCallGraph.buildExplainAgentRunBody(com.google.gson.JsonObject().apply { addProperty("sessionId", "s-1") })
        ).asJsonObject
        assertEquals("tools/call", call.get("method").asString)
        val params = call.getAsJsonObject("params")
        assertEquals("explain_agent_run", params.get("name").asString)
        assertEquals("s-1", params.getAsJsonObject("arguments").get("sessionId").asString)
    }

    // --- MermaidRenderer ------------------------------------------------

    @Test
    fun `mermaid html escapes the source into a js string and blocks script breakout`() {
        val html = MermaidRenderer.toHtml("""flowchart TD
  n1["a</script><b>"]""")
        // The raw closing tag must NOT appear verbatim — it is unicode-escaped.
        assertFalse(html.contains("</script><b>"))
        assertTrue(html.contains("\\u003C") || html.contains("\\u003c"))
        assertTrue(html.contains("mermaid"))
    }

    @Test
    fun `escapeForJsString escapes quotes newlines and angle brackets`() {
        val escaped = MermaidRenderer.escapeForJsString("a\"b\nc<d>")
        // < and > are unicode-escaped so the source can't break out of a script tag.
        assertEquals("a\\\"b\\nc\\u003Cd\\u003E", escaped)
    }
}
