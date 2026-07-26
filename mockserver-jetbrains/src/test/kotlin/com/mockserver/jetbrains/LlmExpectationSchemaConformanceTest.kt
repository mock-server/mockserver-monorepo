package com.mockserver.jetbrains

import com.networknt.schema.InputFormat
import com.networknt.schema.SchemaRegistry
import com.networknt.schema.SpecificationVersion
import org.junit.jupiter.api.Test
import tools.jackson.databind.json.JsonMapper
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Validates what the LLM tool window SENDS against the expectation JSON Schema the plugin BUNDLES —
 * the very schema MockServer validates incoming expectations against (issue #2455).
 *
 * The plugin's "Load into Server" action was rejected with `400 incorrect expectation json format`
 * because [LlmExpectationBuilder] emitted a shape that never existed on the server: a flat
 * `completion` string, a `finishReason` field, and a `provider` of `OPEN_AI`. Nothing caught it —
 * the existing builder tests asserted the output matched the SAME invented shape the builder
 * produced, so they were self-derived and would pass no matter how wrong the document was. The
 * plugin has always shipped the correct schema at `/schemas/mockserver-expectation.schema.json`; it
 * just never checked its own output against it.
 *
 * This test closes that loop: the builder's output must validate against the bundled schema, and the
 * provider/field catalogue offered by [LlmCompletion] must only suggest things that schema accepts.
 * A negative case pins the checker itself — the exact payload from the bug report must still be
 * REJECTED, so a validator that silently accepts everything cannot make this suite pass.
 */
class LlmExpectationSchemaConformanceTest {

    private val mapper = JsonMapper.builder().build()

    private val schemaJson: String = LlmExpectationSchemaConformanceTest::class.java
        .getResourceAsStream("/schemas/mockserver-expectation.schema.json")!!
        .bufferedReader().use { it.readText() }

    private val schema = SchemaRegistry
        .withDefaultDialect(SpecificationVersion.DRAFT_7)
        .getSchema(schemaJson, InputFormat.JSON)

    /** Validation messages for [json] as a single expectation, empty when it conforms. */
    private fun validate(json: String): List<String> =
        schema.validate(json, InputFormat.JSON).map { it.message }

    /** The `httpLlmResponse` definition from the bundled schema — the contract under test. */
    private fun httpLlmResponseDefinition() =
        mapper.readTree(schemaJson).get("definitions").get("httpLlmResponse")

    @Test
    fun `a fully populated LLM expectation validates against the bundled schema`() {
        val json = LlmExpectationBuilder.build(
            LlmExpectationBuilder.Form(
                path = "/v1/chat/completions",
                method = "POST",
                provider = "OPENAI",
                model = "gpt-4o",
                completion = "Test",
                stream = true,
                promptTokens = 10,
                completionTokens = 5,
                finishReason = "stop",
            )
        )

        val errors = validate(json)

        assertTrue(errors.isEmpty(), "expectation must satisfy the bundled schema but got: $errors\n$json")
    }

    @Test
    fun `a minimal LLM expectation validates against the bundled schema`() {
        val json = LlmExpectationBuilder.build(
            LlmExpectationBuilder.Form(
                path = "/v1/messages", method = null, provider = "ANTHROPIC", model = null, completion = "hi"
            )
        )

        val errors = validate(json)

        assertTrue(errors.isEmpty(), "expectation must satisfy the bundled schema but got: $errors\n$json")
    }

    @Test
    fun `every provider the plugin suggests is accepted by the schema`() {
        val schemaProviders = mutableSetOf<String>()
        httpLlmResponseDefinition().get("properties").get("provider").get("enum")
            .forEach { schemaProviders.add(it.stringValue()) }

        val unknown = LlmCompletion.PROVIDERS.filterNot { schemaProviders.contains(it) }

        assertTrue(
            unknown.isEmpty(),
            "completion offers providers the server rejects: $unknown (schema allows $schemaProviders)"
        )
    }

    @Test
    fun `every field the plugin suggests is a real httpLlmResponse property`() {
        val properties = httpLlmResponseDefinition().get("properties")
        val schemaFields = mutableSetOf<String>()
        properties.propertyNames().forEach { schemaFields.add(it) }

        val unknown = LlmCompletion.FIELDS.map { it.insertText }.filterNot { schemaFields.contains(it) }

        assertTrue(
            unknown.isEmpty(),
            "completion offers fields that are not httpLlmResponse properties: $unknown (schema declares $schemaFields)"
        )
    }

    @Test
    fun `every provider the plugin builds with is accepted by the schema`() {
        // The tool window's combo box is populated from LlmCompletion.PROVIDERS, so anything a user can
        // pick must round-trip through the builder into a document the server accepts.
        LlmCompletion.PROVIDERS.forEach { provider ->
            val json = LlmExpectationBuilder.build(
                LlmExpectationBuilder.Form(
                    path = "/v1/chat/completions", method = "POST", provider = provider, model = "m", completion = "c"
                )
            )
            assertEquals(emptyList(), validate(json), "provider $provider produced an invalid expectation:\n$json")
        }
    }

    @Test
    fun `the shape reported in issue 2455 is still rejected`() {
        // Guards the checker itself: this is verbatim what the 7.4.0 plugin sent and what MockServer
        // answered 400 to. If a future change makes validation vacuous, this test goes red.
        val reported = """
            {
              "httpRequest" : { "method" : "POST", "path" : "/v1/chat/completions" },
              "httpLlmResponse" : {
                "provider" : "OPEN_AI",
                "model" : "gpt-4o",
                "completion" : "Test",
                "finishReason" : "stop"
              }
            }
        """.trimIndent()

        val errors = validate(reported)

        assertTrue(errors.isNotEmpty(), "the payload from the bug report must not validate")
    }
}
