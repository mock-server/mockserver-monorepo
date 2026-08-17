package org.mockserver.llm.codec;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.Test;
import org.mockserver.llm.ProviderCodec;
import org.mockserver.llm.ProviderCodecRegistry;
import org.mockserver.llm.StreamingFormat;
import org.mockserver.model.*;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.stream.Collectors;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import static org.junit.Assert.fail;
import static org.mockserver.model.Completion.completion;
import static org.mockserver.model.ToolUse.toolUse;
import static org.mockserver.model.Usage.usage;

/**
 * Golden-file drift-detection test for LLM provider codecs.
 * <p>
 * For each registered provider that implements {@code encode()} and/or
 * {@code encodeStreaming()}, this test encodes fixed canonical inputs,
 * normalizes volatile fields (IDs, timestamps, usage counts), and compares
 * the result against committed golden files under
 * {@code src/test/resources/llm/fixtures/<provider>/}.
 * <p>
 * <strong>Regenerate goldens after intentional codec changes:</strong>
 * <pre>
 *   mvn test -pl mockserver-core -Dtest=LlmCodecGoldenFileTest \
 *       -Dmockserver.updateLlmGoldens=true
 * </pre>
 * or set env var {@code MOCKSERVER_UPDATE_LLM_GOLDENS=true}.
 */
public class LlmCodecGoldenFileTest {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper()
        .enable(SerializationFeature.INDENT_OUTPUT)
        .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);

    // compact (single-line, key-sorted) serialisation for streaming JSONL goldens
    private static final ObjectMapper COMPACT_MAPPER = new ObjectMapper()
        .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);

    /**
     * Canonical model strings per provider — the codec's natural/default model.
     */
    private static final Map<Provider, String> CANONICAL_MODELS;

    static {
        Map<Provider, String> m = new EnumMap<>(Provider.class);
        m.put(Provider.OPENAI, "gpt-4o");
        m.put(Provider.OPENAI_RESPONSES, "gpt-4o");
        m.put(Provider.ANTHROPIC, "claude-sonnet-4-20250514");
        m.put(Provider.GEMINI, "gemini-1.5-pro");
        m.put(Provider.BEDROCK, "anthropic.claude-sonnet-4-20250514-v1:0");
        m.put(Provider.AZURE_OPENAI, "gpt-4o");
        m.put(Provider.OLLAMA, "llama3.1");
        CANONICAL_MODELS = Collections.unmodifiableMap(m);
    }

    /**
     * Rerank-only providers have no chat/completion encode or streaming path, so
     * they are intentionally excluded from the chat-codec golden coverage. Their
     * rerank shape is covered by dedicated unit tests
     * ({@code CohereCodecTest} / {@code VoyageCodecTest}).
     */
    private static final Set<Provider> RERANK_ONLY_PROVIDERS =
        Collections.unmodifiableSet(EnumSet.of(Provider.COHERE, Provider.VOYAGE));

    /**
     * OpenAI-chat-compatible alias providers (Mistral, xAI/Grok, DeepSeek, Groq,
     * OpenRouter, OrcaRouter). Their codecs delegate to {@code OpenAiChatCompletionsCodec}, so
     * their wire shape is byte-identical to the {@code openai} fixtures already
     * asserted here — dedicated golden files would be pure duplicates. They are
     * instead exercised by {@code OpenAiCompatibleProviderCodecTest}, which proves
     * each alias produces exactly the OpenAI codec's output. Excluded from golden
     * coverage the same way as the rerank-only providers.
     */
    private static final Set<Provider> OPENAI_COMPATIBLE_ALIAS_PROVIDERS =
        Collections.unmodifiableSet(EnumSet.of(
            Provider.MISTRAL, Provider.XAI, Provider.DEEPSEEK, Provider.GROQ, Provider.OPENROUTER,
            Provider.ORCAROUTER));

    /**
     * Map Provider enum to fixture directory name.
     */
    private static final Map<Provider, String> PROVIDER_DIR_NAMES;

    static {
        Map<Provider, String> m = new EnumMap<>(Provider.class);
        m.put(Provider.OPENAI, "openai");
        m.put(Provider.OPENAI_RESPONSES, "openai-responses");
        m.put(Provider.ANTHROPIC, "anthropic");
        m.put(Provider.GEMINI, "gemini");
        m.put(Provider.BEDROCK, "bedrock");
        m.put(Provider.AZURE_OPENAI, "azure-openai");
        m.put(Provider.OLLAMA, "ollama");
        PROVIDER_DIR_NAMES = Collections.unmodifiableMap(m);
    }

    /**
     * Fixed text completion input used for all providers.
     */
    private static final Completion TEXT_COMPLETION = completion()
        .withText("Hello! How can I help you today?")
        .withUsage(usage().withInputTokens(12).withOutputTokens(8));

    /**
     * Fixed tool-call completion input used for all providers.
     */
    private static final Completion TOOL_CALL_COMPLETION = completion()
        .withToolCall(toolUse("get_weather").withArguments("{\"city\":\"London\",\"units\":\"celsius\"}"))
        .withUsage(usage().withInputTokens(25).withOutputTokens(15));

    /**
     * Whether we are in golden-file update (regeneration) mode.
     */
    private static boolean isUpdateMode() {
        String sysProp = System.getProperty("mockserver.updateLlmGoldens");
        if ("true".equalsIgnoreCase(sysProp)) {
            return true;
        }
        String envVar = System.getenv("MOCKSERVER_UPDATE_LLM_GOLDENS");
        return "true".equalsIgnoreCase(envVar);
    }

    /**
     * Resolve the src/test/resources base path for golden files.
     * We resolve relative to the module root (CWD or detected from this class's location).
     */
    private static Path fixturesBasePath() {
        // Try CWD-relative first (works when run from mockserver-core or mockserver/)
        Path cwd = Paths.get("").toAbsolutePath();
        Path candidate = cwd.resolve("src/test/resources/llm/fixtures");
        if (Files.isDirectory(candidate)) {
            return candidate;
        }
        // Try one level deeper (when CWD is the parent multi-module root)
        candidate = cwd.resolve("mockserver-core/src/test/resources/llm/fixtures");
        if (Files.isDirectory(candidate)) {
            return candidate;
        }
        throw new IllegalStateException(
            "Cannot locate src/test/resources/llm/fixtures from CWD: " + cwd +
                ". Run from the mockserver-core module directory or its parent.");
    }

    // -----------------------------------------------------------------------
    // Test entry point
    // -----------------------------------------------------------------------

    @Test
    public void shouldMatchGoldenFilesForAllProviders() throws Exception {
        ProviderCodecRegistry registry = ProviderCodecRegistry.getInstance();
        Path fixturesBase = fixturesBasePath();
        boolean updateMode = isUpdateMode();

        List<String> failures = new ArrayList<>();
        List<String> skipped = new ArrayList<>();
        List<String> covered = new ArrayList<>();
        int goldenFilesWritten = 0;

        for (Provider provider : Provider.values()) {
            Optional<ProviderCodec> optCodec = registry.lookup(provider);
            if (optCodec.isEmpty()) {
                skipped.add(provider.name() + " (not registered)");
                continue;
            }
            ProviderCodec codec = optCodec.get();
            if (RERANK_ONLY_PROVIDERS.contains(provider)) {
                // Rerank-only providers have no chat encode/streaming path by design.
                continue;
            }
            if (OPENAI_COMPATIBLE_ALIAS_PROVIDERS.contains(provider)) {
                // OpenAI-chat-compatible aliases delegate to OpenAiChatCompletionsCodec —
                // identical wire shape to the openai fixtures; covered by
                // OpenAiCompatibleProviderCodecTest instead of duplicate golden files.
                continue;
            }
            String dirName = PROVIDER_DIR_NAMES.get(provider);
            if (dirName == null) {
                skipped.add(provider.name() + " (no directory mapping)");
                continue;
            }
            String model = CANONICAL_MODELS.getOrDefault(provider, "unknown");
            Path providerDir = fixturesBase.resolve(dirName);
            Files.createDirectories(providerDir);

            // --- encode() ---
            boolean supportsEncode = supportsEncode(codec, provider, model);
            if (supportsEncode) {
                // text-completion.json
                String textBody = encodeAndNormalize(codec, TEXT_COMPLETION, model);
                int result = handleGolden(providerDir.resolve("text-completion.json"),
                    textBody, updateMode, provider.name(), "text-completion", failures);
                goldenFilesWritten += result;
                covered.add(provider.name() + "/text-completion");

                // tool-call.json
                String toolBody = encodeAndNormalize(codec, TOOL_CALL_COMPLETION, model);
                result = handleGolden(providerDir.resolve("tool-call.json"),
                    toolBody, updateMode, provider.name(), "tool-call", failures);
                goldenFilesWritten += result;
                covered.add(provider.name() + "/tool-call");
            } else {
                skipped.add(provider.name() + "/encode (UnsupportedOperationException)");
            }

            // --- encodeStreaming() ---
            boolean supportsStreaming = supportsEncodeStreaming(codec, provider, model);
            if (supportsStreaming) {
                // streaming-text.jsonl
                String streamingText = encodeStreamingAndNormalize(codec, TEXT_COMPLETION, model, provider);
                int result = handleGolden(providerDir.resolve("streaming-text.jsonl"),
                    streamingText, updateMode, provider.name(), "streaming-text", failures);
                goldenFilesWritten += result;
                covered.add(provider.name() + "/streaming-text");

                // streaming-tool-call.jsonl
                String streamingTool = encodeStreamingAndNormalize(codec, TOOL_CALL_COMPLETION, model, provider);
                result = handleGolden(providerDir.resolve("streaming-tool-call.jsonl"),
                    streamingTool, updateMode, provider.name(), "streaming-tool-call", failures);
                goldenFilesWritten += result;
                covered.add(provider.name() + "/streaming-tool-call");
            } else {
                skipped.add(provider.name() + "/encodeStreaming (UnsupportedOperationException)");
            }
        }

        if (updateMode) {
            System.out.println("[LlmCodecGoldenFileTest] UPDATE MODE: wrote " + goldenFilesWritten + " golden files.");
            System.out.println("  Covered: " + String.join(", ", covered));
            if (!skipped.isEmpty()) {
                System.out.println("  Skipped: " + String.join(", ", skipped));
            }
            // In update mode, always pass (the point is to regenerate)
            return;
        }

        // In assertion mode, report all failures at once
        if (!failures.isEmpty()) {
            fail("Wire-format drift detected in " + failures.size() + " golden file(s).\n" +
                "If the changes are intentional, regenerate goldens with:\n" +
                "  -Dmockserver.updateLlmGoldens=true\n" +
                "and review the diff.\n\n" +
                String.join("\n\n", failures));
        }

        // Every provider/operation must be exercised: a provider silently regressing to an
        // UnsupportedOperationException (and thus skipping its goldens) must FAIL drift detection,
        // not pass quietly.
        assertThat("Providers/operations were unexpectedly skipped (no encode/encodeStreaming): " + skipped,
            skipped, is(empty()));
        assertThat("Expected all 28 golden files (7 providers x text/tool-call/streaming-text/streaming-tool-call) to be verified",
            covered.size(), greaterThanOrEqualTo(28));

        System.out.println("[LlmCodecGoldenFileTest] PASS: " + covered.size() + " golden files verified.");
        System.out.println("  Covered: " + String.join(", ", covered));
        if (!skipped.isEmpty()) {
            System.out.println("  Skipped: " + String.join(", ", skipped));
        }
    }

    // -----------------------------------------------------------------------
    // Token-count wire-correctness — usage counts ASSERTED, never zeroed
    // -----------------------------------------------------------------------

    /**
     * Pins the actual encoded token counts against hand-authored canonical
     * {@link Usage} values, for every chat/completion provider, on both the text
     * and tool-call paths.
     * <p>
     * The golden drift test above deliberately normalizes usage blocks to zero
     * (they are structural, not stable values, so zeroing keeps ID/timestamp-
     * independent drift easy to read). That normalization means the golden files
     * <em>alone cannot prove a codec emits the correct token counts</em>: a codec
     * that silently regressed usage to {@code 0}, or swapped input/output, would
     * still match its golden. This test closes that blind spot — it encodes the
     * same canonical completions ({@link #TEXT_COMPLETION} = input 12 / output 8,
     * {@link #TOOL_CALL_COMPLETION} = input 25 / output 15) and asserts the actual
     * encoded token-count fields, named per each provider's published usage schema,
     * equal those canonical values.
     */
    @Test
    public void shouldEncodeCanonicalTokenUsageCounts() {
        ProviderCodecRegistry registry = ProviderCodecRegistry.getInstance();
        List<String> asserted = new ArrayList<>();

        for (Provider provider : Provider.values()) {
            if (RERANK_ONLY_PROVIDERS.contains(provider)
                || OPENAI_COMPATIBLE_ALIAS_PROVIDERS.contains(provider)) {
                // No chat/completion usage block on these paths (rerank-only), or
                // byte-identical to the OpenAI codec (aliases) — covered elsewhere.
                continue;
            }
            Optional<ProviderCodec> optCodec = registry.lookup(provider);
            if (optCodec.isEmpty()) {
                continue;
            }
            ProviderCodec codec = optCodec.get();
            String model = CANONICAL_MODELS.getOrDefault(provider, "unknown");
            if (!supportsEncode(codec, provider, model)) {
                continue;
            }

            // TEXT_COMPLETION carries usage(input=12, output=8)
            assertEncodedUsage(provider, encodeToTree(codec, TEXT_COMPLETION, model), 12, 8);
            // TOOL_CALL_COMPLETION carries usage(input=25, output=15)
            assertEncodedUsage(provider, encodeToTree(codec, TOOL_CALL_COMPLETION, model), 25, 15);
            asserted.add(provider.name());
        }

        assertThat("Expected canonical token-usage assertions to cover all 7 chat/completion providers "
                + "(OpenAI, OpenAI-Responses, Anthropic, Gemini, Bedrock, Azure-OpenAI, Ollama): " + asserted,
            asserted.size(), greaterThanOrEqualTo(7));
    }

    private JsonNode encodeToTree(ProviderCodec codec, Completion completion, String model) {
        try {
            // Parse the raw encoded body WITHOUT the golden normalization, so the
            // real token counts survive to be asserted rather than being zeroed.
            return OBJECT_MAPPER.readTree(codec.encode(completion, model).getBodyAsString());
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Failed to parse encoded response for usage assertion", e);
        }
    }

    /**
     * Assert the encoded token-count fields equal the canonical values, using the
     * usage field names each provider actually emits on the wire (from its public
     * API docs). {@code asInt(-1)} makes a dropped or missing field fail the
     * equality (it becomes {@code -1}) instead of silently defaulting to {@code 0}.
     */
    private void assertEncodedUsage(Provider provider, JsonNode root, int expectedInput, int expectedOutput) {
        String ctx = provider.name() + " usage";
        switch (provider) {
            case OPENAI:
            case AZURE_OPENAI: {
                // OpenAI Chat Completions: usage.{prompt_tokens,completion_tokens,total_tokens}
                JsonNode u = root.path("usage");
                assertThat(ctx + " prompt_tokens", u.path("prompt_tokens").asInt(-1), is(expectedInput));
                assertThat(ctx + " completion_tokens", u.path("completion_tokens").asInt(-1), is(expectedOutput));
                assertThat(ctx + " total_tokens", u.path("total_tokens").asInt(-1), is(expectedInput + expectedOutput));
                break;
            }
            case OPENAI_RESPONSES: {
                // OpenAI Responses API: usage.{input_tokens,output_tokens,total_tokens}
                JsonNode u = root.path("usage");
                assertThat(ctx + " input_tokens", u.path("input_tokens").asInt(-1), is(expectedInput));
                assertThat(ctx + " output_tokens", u.path("output_tokens").asInt(-1), is(expectedOutput));
                assertThat(ctx + " total_tokens", u.path("total_tokens").asInt(-1), is(expectedInput + expectedOutput));
                break;
            }
            case ANTHROPIC:
            case BEDROCK: {
                // Anthropic Messages (and Bedrock's Anthropic shape): usage.{input_tokens,output_tokens}
                JsonNode u = root.path("usage");
                assertThat(ctx + " input_tokens", u.path("input_tokens").asInt(-1), is(expectedInput));
                assertThat(ctx + " output_tokens", u.path("output_tokens").asInt(-1), is(expectedOutput));
                break;
            }
            case GEMINI: {
                // Gemini generateContent: usageMetadata.{promptTokenCount,candidatesTokenCount,totalTokenCount}
                JsonNode u = root.path("usageMetadata");
                assertThat(ctx + " promptTokenCount", u.path("promptTokenCount").asInt(-1), is(expectedInput));
                assertThat(ctx + " candidatesTokenCount", u.path("candidatesTokenCount").asInt(-1), is(expectedOutput));
                assertThat(ctx + " totalTokenCount", u.path("totalTokenCount").asInt(-1), is(expectedInput + expectedOutput));
                break;
            }
            case OLLAMA: {
                // Ollama chat: top-level prompt_eval_count / eval_count
                assertThat(ctx + " prompt_eval_count", root.path("prompt_eval_count").asInt(-1), is(expectedInput));
                assertThat(ctx + " eval_count", root.path("eval_count").asInt(-1), is(expectedOutput));
                break;
            }
            default:
                fail("No usage-field mapping defined for provider " + provider +
                    " — add its published token-count field names to assertEncodedUsage().");
        }
    }

    // -----------------------------------------------------------------------
    // Encode + normalize helpers
    // -----------------------------------------------------------------------

    private String encodeAndNormalize(ProviderCodec codec, Completion completion, String model)
        throws JsonProcessingException {
        HttpResponse response = codec.encode(completion, model);
        String body = response.getBodyAsString();
        JsonNode tree = OBJECT_MAPPER.readTree(body);
        normalizeJsonTree(tree);
        return OBJECT_MAPPER.writeValueAsString(tree);
    }

    private String encodeStreamingAndNormalize(ProviderCodec codec, Completion completion,
                                               String model, Provider provider) throws JsonProcessingException {
        // Pass null physics so no timing delays are added
        List<SseEvent> events = codec.encodeStreaming(completion, model, null);
        StringBuilder sb = new StringBuilder();
        for (SseEvent event : events) {
            String data = event.getData();
            if (data == null) {
                continue;
            }

            // Build a line representation: for SSE with event types, include the event name
            String eventType = event.getEvent();
            String normalizedData = normalizeStreamingLine(data);

            if (eventType != null && !eventType.isEmpty()) {
                // SSE format: include event type as a JSON wrapper
                sb.append("{\"event\":\"").append(eventType)
                    .append("\",\"data\":").append(normalizedData).append("}\n");
            } else {
                // Plain data (NDJSON or SSE without event type)
                sb.append(normalizedData).append("\n");
            }
        }
        return sb.toString();
    }

    /**
     * Normalize a single streaming data line. If it's valid JSON, normalize
     * the tree. If it's a sentinel (e.g. "[DONE]"), return as-is quoted.
     */
    private String normalizeStreamingLine(String data) throws JsonProcessingException {
        // Try to parse as JSON
        try {
            JsonNode tree = OBJECT_MAPPER.readTree(data);
            if (tree != null && (tree.isObject() || tree.isArray())) {
                normalizeJsonTree(tree);
                // Return compact (single-line) for JSONL readability
                return COMPACT_MAPPER.writeValueAsString(tree);
            }
        } catch (Exception ignored) {
            // Not valid JSON — treat as sentinel
        }
        // Return as a JSON string literal for non-JSON data (e.g. "[DONE]")
        return "\"" + data.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    // -----------------------------------------------------------------------
    // JSON normalization — replace volatile fields with fixed placeholders
    // -----------------------------------------------------------------------

    /**
     * Walk the JSON tree and replace volatile fields with deterministic placeholders:
     * <ul>
     *   <li>{@code id}, fields matching {@code msg_*}, {@code chatcmpl-*}, {@code resp_*},
     *       {@code call_*}, {@code toolu_*}, {@code fc_*} patterns in string values -> {@code "<id>"}</li>
     *   <li>{@code created}, {@code created_at} -> {@code 0}</li>
     *   <li>Usage numeric values -> {@code 0} (structure preserved)</li>
     *   <li>{@code system_fingerprint} -> {@code "<fp>"}</li>
     *   <li>Duration fields ({@code total_duration}, {@code load_duration}, etc.) -> {@code 0}</li>
     * </ul>
     */
    private void normalizeJsonTree(JsonNode node) {
        if (node.isObject()) {
            ObjectNode obj = (ObjectNode) node;
            Iterator<Map.Entry<String, JsonNode>> fields = obj.fields();
            List<String> fieldNames = new ArrayList<>();
            while (fields.hasNext()) {
                fieldNames.add(fields.next().getKey());
            }
            for (String fieldName : fieldNames) {
                JsonNode child = obj.get(fieldName);
                normalizeField(obj, fieldName, child);
            }
        } else if (node.isArray()) {
            ArrayNode arr = (ArrayNode) node;
            for (int i = 0; i < arr.size(); i++) {
                normalizeJsonTree(arr.get(i));
            }
        }
    }

    private void normalizeField(ObjectNode parent, String fieldName, JsonNode value) {
        // ID fields: replace generated IDs with placeholder
        if (isIdField(fieldName)) {
            if (value.isTextual()) {
                parent.put(fieldName, "<id>");
            }
            return;
        }

        // Timestamp fields
        if ("created".equals(fieldName) || "created_at".equals(fieldName)) {
            if (value.isNumber()) {
                parent.put(fieldName, 0);
            } else if (value.isTextual()) {
                // Ollama uses ISO 8601 string for created_at
                parent.put(fieldName, "<timestamp>");
            }
            return;
        }

        // system_fingerprint
        if ("system_fingerprint".equals(fieldName)) {
            parent.put(fieldName, "<fp>");
            return;
        }

        // Duration fields (Ollama)
        if (fieldName.endsWith("_duration")) {
            if (value.isNumber()) {
                parent.put(fieldName, 0);
            }
            return;
        }

        // Usage/token-count blocks: zero out numeric values but keep structure
        if (isUsageField(fieldName)) {
            if (value.isObject()) {
                zeroOutNumericValues((ObjectNode) value);
            }
            return;
        }

        // Token count fields at top level (Ollama)
        if (isTokenCountField(fieldName)) {
            if (value.isNumber()) {
                parent.put(fieldName, 0);
            }
            return;
        }

        // Recurse into children
        if (value.isObject() || value.isArray()) {
            normalizeJsonTree(value);
        }

        // Check if a string value looks like a generated ID pattern
        if (value.isTextual()) {
            String text = value.asText();
            if (looksLikeGeneratedId(text)) {
                parent.put(fieldName, "<id>");
            }
        }
    }

    private boolean isIdField(String fieldName) {
        return "id".equals(fieldName)
            || "item_id".equals(fieldName)
            || "tool_call_id".equals(fieldName);
    }

    private boolean isUsageField(String fieldName) {
        return "usage".equals(fieldName)
            || "usageMetadata".equals(fieldName);
    }

    private boolean isTokenCountField(String fieldName) {
        return "prompt_eval_count".equals(fieldName)
            || "eval_count".equals(fieldName)
            || "prompt_eval_duration".equals(fieldName)
            || "eval_duration".equals(fieldName);
    }

    /**
     * Check if a string value matches known generated-ID patterns.
     */
    private boolean looksLikeGeneratedId(String value) {
        return value.startsWith("chatcmpl-")
            || value.startsWith("msg_")
            || value.startsWith("resp_")
            || value.startsWith("call_")
            || value.startsWith("toolu_")
            || value.startsWith("fc_");
    }

    /**
     * Zero out all numeric values in an object node (for usage blocks).
     */
    private void zeroOutNumericValues(ObjectNode obj) {
        Iterator<Map.Entry<String, JsonNode>> fields = obj.fields();
        List<String> names = new ArrayList<>();
        while (fields.hasNext()) {
            names.add(fields.next().getKey());
        }
        for (String name : names) {
            JsonNode v = obj.get(name);
            if (v.isNumber()) {
                obj.put(name, 0);
            } else if (v.isObject()) {
                zeroOutNumericValues((ObjectNode) v);
            }
        }
    }

    // -----------------------------------------------------------------------
    // Golden file handling
    // -----------------------------------------------------------------------

    /**
     * Handle a golden file: either write (update mode) or assert (normal mode).
     *
     * @return 1 if a file was written, 0 otherwise
     */
    private int handleGolden(Path goldenPath, String actualContent, boolean updateMode,
                             String providerName, String goldenName, List<String> failures) throws IOException {
        if (updateMode) {
            Files.createDirectories(goldenPath.getParent());
            Files.write(goldenPath, actualContent.getBytes(StandardCharsets.UTF_8));
            return 1;
        }

        // Assertion mode
        if (!Files.exists(goldenPath)) {
            failures.add(providerName + "/" + goldenName + ": golden file not found at " + goldenPath +
                "\n  Generate it first with -Dmockserver.updateLlmGoldens=true");
            return 0;
        }

        String expected = new String(Files.readAllBytes(goldenPath), StandardCharsets.UTF_8);
        if (!expected.equals(actualContent)) {
            // Build a useful diff message
            String diff = buildDiff(expected, actualContent);
            failures.add(providerName + "/" + goldenName + ": wire format drifted!\n" +
                "  Golden file: " + goldenPath + "\n" +
                "  Diff (expected vs actual):\n" + diff);
        }
        return 0;
    }

    /**
     * Build a simple line-by-line diff showing expected vs actual.
     */
    private String buildDiff(String expected, String actual) {
        String[] expectedLines = expected.split("\n", -1);
        String[] actualLines = actual.split("\n", -1);
        StringBuilder sb = new StringBuilder();
        int maxLines = Math.max(expectedLines.length, actualLines.length);
        for (int i = 0; i < maxLines; i++) {
            String exp = i < expectedLines.length ? expectedLines[i] : "";
            String act = i < actualLines.length ? actualLines[i] : "";
            if (!exp.equals(act)) {
                sb.append("  line ").append(i + 1).append(":\n");
                sb.append("    - ").append(exp).append("\n");
                sb.append("    + ").append(act).append("\n");
            }
        }
        return sb.toString();
    }

    // -----------------------------------------------------------------------
    // Feature detection: does the codec implement encode / encodeStreaming?
    // -----------------------------------------------------------------------

    private boolean supportsEncode(ProviderCodec codec, Provider provider, String model) {
        try {
            codec.encode(completion().withText("probe"), model);
            return true;
        } catch (UnsupportedOperationException e) {
            return false;
        }
    }

    private boolean supportsEncodeStreaming(ProviderCodec codec, Provider provider, String model) {
        try {
            codec.encodeStreaming(completion().withText("probe"), model, null);
            return true;
        } catch (UnsupportedOperationException e) {
            return false;
        }
    }
}
