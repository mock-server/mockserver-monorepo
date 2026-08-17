# LLM & Agent Mocking — Internal Architecture

## Overview

MockServer provides first-class LLM mocking through a new action type `httpLlmResponse` that produces provider-correct responses from a high-level, provider-neutral `Completion` abstraction. The feature spans codec encoding, streaming physics, conversation-aware matching, session isolation, MCP tool exposure, and dashboard rendering.

## Action Type

`httpLlmResponse` is a peer to `httpResponse`, `httpSseResponse`, etc. It lives on `Expectation` as a separate field and dispatches through `HttpLlmResponseActionHandler`.

```mermaid
flowchart LR
    REQ([Incoming POST]) --> MATCH[RequestMatchers]
    MATCH --> EXP["Expectation\nwith httpLlmResponse"]
    EXP --> HANDLER["HttpLlmResponseActionHandler"]
    HANDLER -->|non-streaming| CODEC["ProviderCodec.encode()"]
    HANDLER -->|streaming| EXPAND["StreamingPhysicsExpander"]
    EXPAND --> SSE_CODEC["ProviderCodec.encodeStreaming()"]
    SSE_CODEC --> SSE_HANDLER["HttpSseResponseActionHandler"]
    CODEC --> WIRE[HTTP Response]
    SSE_HANDLER --> WIRE
```

## Codec Registry

`ProviderCodecRegistry` is a singleton that maps `Provider` enum values to `ProviderCodec` implementations. Each codec exposes:

- `encode(Completion, model)` -- non-streaming response
- `encodeStreaming(Completion, model, StreamingPhysics)` -- SSE event list
- `encodeEmbedding(EmbeddingResponse, input)` / `encodeEmbedding(EmbeddingResponse, input, model)` -- embeddings (the model-aware overload lets Bedrock pick Titan vs Cohere; most codecs ignore the model and inherit the two-arg default)
- `encodeRerank(RerankResponse, documents)` -- rerank results (Cohere/Voyage)
- `decode(HttpRequest)` -- parse inbound request to `ParsedConversation` (for conversation matchers)

All embedding codecs share `EmbeddingVectors` (deterministic-from-input or random, then L2-normalised); only the JSON envelope differs per provider. When `deterministicFromInput` is set, the vector is built by **n-gram feature hashing** rather than a hash-seeded PRNG, so it is not only deterministic but also **semantically plausible**: the input is tokenised (Unicode-aware, lowercased) into word unigrams, word bigrams, and character 3-grams; each feature is hashed (seeded FNV-1a) into one of `dimensions` buckets with a **signed** contribution (a second hash picks +/- to reduce collision bias), weighted by a sublinear term frequency (`1 + ln(count)`, character n-grams down-weighted x0.5); the accumulated vector is L2-normalised. Texts that share vocabulary / n-grams land in overlapping buckets, so their **cosine similarity is higher** (paraphrases score ~0.3-0.6) while unrelated texts are **near-orthogonal** (~0.0-0.1) — letting offline RAG / vector-search code rank related documents above unrelated ones without a real embedding model. The transform is deterministic for the same `input`, `seed`, and `dimensions` (the seeded FNV-1a feature hash is JVM-stable, though the floating-point vector is not promised bit-exact across platforms — feature summation order and `Math.log` ULP differences can perturb the low bits; the cosine ordering is what is guaranteed); feature-less input (empty/punctuation-only) falls back to a seeded non-zero vector so the result is always unit-length. The non-deterministic path (default) is unchanged (uniform-random unit vector). Embedding shapes: OpenAI/Azure `{"object":"list","data":[{"embedding":[...]}]}` (default 1536 dims); Gemini `{"embedding":{"values":[...]}}` (768); Ollama `{"embeddings":[[...]]}` — the `/api/embed` shape (768); Bedrock Titan `{"embedding":[...],"inputTextTokenCount":N}` or Bedrock Cohere `{"embeddings":[[...]]}` when the model id starts with `cohere` (1024). `ANTHROPIC` and `OPENAI_RESPONSES` have no embeddings endpoint and throw (surfaced as a 400 by the handler). Rerank shares `RerankScoring` (per-document relevance scores — reproducible when `deterministicFromInput` is set, else random — descending, capped to `topN`) and emits the provider-correct envelope via a `RerankScoring.Envelope` selector: Cohere `{"results":[{"index":N,"relevance_score":F}, ...]}`, Voyage `{"object":"list","data":[...],"usage":{"total_tokens":N}}`.

Currently registered codecs:

| Provider | Codec class | Status |
|----------|-------------|--------|
| ANTHROPIC | `AnthropicCodec` | Complete (no embeddings endpoint) |
| OPENAI | `OpenAiChatCompletionsCodec` | Complete (chat + embeddings) |
| OPENAI_RESPONSES | `OpenAiResponsesCodec` | Complete (no embeddings endpoint) |
| GEMINI | `GeminiCodec` | Complete (chat + embeddings) |
| BEDROCK | `BedrockCodec` | Complete (delegates chat to `AnthropicCodec`; streaming uses `application/vnd.amazon.eventstream` binary framing via `BedrockEventStreamEncoder`; automatic AWS SigV4 request signing implemented via `AwsSigV4Signer` on the client path; embeddings = Titan default / Cohere by model) |
| AZURE_OPENAI | `AzureOpenAiCodec` | Complete (delegates to `OpenAiChatCompletionsCodec`) |
| OLLAMA | `OllamaCodec` | Complete (chat + embeddings; see security audit for NDJSON wire-format limitation) |
| COHERE | `CohereCodec` | Rerank only (`/v1/rerank`) |
| VOYAGE | `VoyageCodec` | Rerank only (`/v1/rerank`) |
| MISTRAL | `MistralCodec` | Complete (delegates to `OpenAiChatCompletionsCodec`) |
| XAI | `XaiCodec` | Complete (delegates to `OpenAiChatCompletionsCodec`) |
| DEEPSEEK | `DeepSeekCodec` | Complete (delegates to `OpenAiChatCompletionsCodec`) |
| GROQ | `GroqCodec` | Complete (delegates to `OpenAiChatCompletionsCodec`) |
| OPENROUTER | `OpenRouterCodec` | Complete (delegates to `OpenAiChatCompletionsCodec`) |
| ORCAROUTER | `OrcaRouterCodec` | Complete (delegates to `OpenAiChatCompletionsCodec`) |

### OpenAI-compatible provider aliases

`MISTRAL`, `XAI` (Grok), `DEEPSEEK`, `GROQ`, `OPENROUTER`, and `ORCAROUTER` expose the OpenAI Chat Completions wire format on their own hosts. Their codecs extend `OpenAiCompatibleChatCodec` (which delegates every encode/decode to `OpenAiChatCompletionsCodec`, exactly like `AzureOpenAiCodec`) and their runtime clients extend `OpenAiLlmClient` (overriding only `provider()` and `defaultBaseUrl()`). Because the path is the shared `/chat/completions`, the **host** is the only distinguishing signal: `LlmProviderSniffer` (live forward/proxy path) and `ProviderDetector` (offline AUTO detection) map `api.mistral.ai`→`MISTRAL`, `api.x.ai`→`XAI`, `api.deepseek.com`→`DEEPSEEK`, `api.groq.com`→`GROQ`, `openrouter.ai`→`OPENROUTER`, `api.orcarouter.ai`→`ORCAROUTER`. This means proxy observability records traffic to these gateways as LLM (provider-correct GenAI spans + cost metrics) instead of dropping it as non-LLM. Pricing rows in `LlmPricing` are marked **approximate** (`isApproximate()`); OpenRouter and OrcaRouter route vendor-prefixed model ids (`openai/…`, `anthropic/…`, `google/…`, `mistral*/…`, `x-ai/…`, `deepseek/…`) to the underlying vendor's table. Their wire shape is byte-identical to the `openai` golden fixtures, so they are covered by `OpenAiCompatibleProviderCodecTest` rather than dedicated golden files.

## Realtime voice APIs (OpenAI Realtime, Gemini Live)

The realtime (voice) protocols — **OpenAI Realtime** (GA 2025) and **Gemini Live** (`BidiGenerateContent`) — are a
bidirectional **WebSocket event stream**, not a single HTTP request/response body, so they are a sibling feature to
the `httpLlmResponse` codecs rather than part of them. They live in `org.mockserver.llm.realtime`
(`OpenAiRealtimeCodec` / `GeminiLiveCodec`, pure event codecs) with their own `RealtimeProvider` enum
(`OPENAI_REALTIME`, `GEMINI_LIVE`) kept **separate** from the HTTP `Provider` enum, and are wired through the
`httpWebSocketResponse` action via the Java client `RealtimeMockBuilder`. Mapping the realtime providers onto the
HTTP `Provider` enum (for pricing / sniffer / detector / proxy-observability parity) is **deferred**. See
[ai-protocol-mocking.md → Realtime Voice API Mocking](ai-protocol-mocking.md#realtime-voice-api-mocking-openai-realtime-gemini-live)
for the architecture, the event coverage matrix, and usage.

## OpenAI Responses API server-side state

The Responses API is stateful in a way Chat Completions and Anthropic Messages are not: a client may send only the new turn's `input` plus a `previous_response_id`, and the server reconstructs the full conversation from the prior turn. `OpenAiResponsesStore` (`org.mockserver.llm`) is the process-wide registry that models this, so agents that chain turns via `previous_response_id` run against the mock.

```mermaid
flowchart LR
    T1["POST /v1/responses\ninput: hello"] --> ENC1["encode → resp_A"]
    ENC1 --> STORE1["store resp_A\n[user hello, assistant hi]"]
    T2["POST /v1/responses\ninput: and then?\nprevious_response_id: resp_A"] --> DEC["decode prepends resp_A\n[user hello, assistant hi, user and then?]"]
    STORE1 -.-> DEC
    GET["GET /v1/responses/resp_A"] --> STORE1
```

- **Recording** — `HttpLlmResponseActionHandler` calls `OpenAiResponsesStore.recordIfStored(...)` after encoding an `OPENAI_RESPONSES` completion. It honours the request's `store` flag (default `true`; `store:false` skips recording) and stores, keyed by the issued `resp_…` id, the fully-chained decode of the request plus this turn's assistant output — so a later turn referencing this id reconstructs the entire dialogue.
- **Chaining** — `OpenAiResponsesCodec.decode` calls `OpenAiResponsesStore.priorMessagesFor(body)`; when the request carries a `previous_response_id` whose response is stored, the prior conversation is prepended to the current turn's messages, so conversation matchers and usage inference see the whole dialogue.
- **Retrieval** — `GET /v1/responses/{id}` is served from the store by `HttpActionHandler` (on the otherwise-404 path, so user expectations always win) via `OpenAiResponsesStore.retrievalResponseOrNull(request)`, returning the stored body verbatim; an unknown id falls through to normal handling.

The store is bounded (LRU, 10k) and cleared on `HttpState.reset()`. It is fail-soft — recording never affects the served response — and fully back-compatible: a request with no `previous_response_id` and the default `store:true` behaves exactly as before.

## Streaming Physics

`StreamingPhysicsExpander` converts a `Completion` + `StreamingPhysics` configuration into a `List<SseEvent>` with pre-computed per-event delays.

Parameters:
- `timeToFirstToken` -- delay before the first SSE event
- `tokensPerSecond` -- base rate (1-10000)
- `jitter` -- fractional uniform deviation (0.0-1.0)
- `seed` -- PRNG seed for reproducible timing
- `subwordStreaming` -- split completion text into finer, subword-sized deltas instead of whole words. **On by default** (unset/`null` resolves to subword); set explicitly to `false` to opt back into the legacy whole-word split

The expanded events are handed to `HttpSseResponseActionHandler` which already honours per-event delays.

### Streaming delta granularity (`subwordStreaming`)

Each chat codec's `encodeStreaming` splits the completion text into deltas via `TokenCounter.streamingTextTokens(text, physics)`. **Subword deltas are now the default**: a `null` physics or an unset `subwordStreaming` flag segments the text into **subword-sized, concatenation-exact pieces** (a leading space absorbed into the following word, words chunked into ~4-character subword units) via `TokenCounter.segmentForStreaming`, approximating a real provider's per-token stream. Only an **explicit** `streamingPhysics.subwordStreaming: false` selects the legacy whitespace-boundary split — each word and each whitespace run its own delta. The per-event **timing math is unchanged** — each delta is still exactly one physics event getting the `timeToFirstToken` / `1000 / tokensPerSecond` delay — so subword mode simply emits more, smaller events (closer to real token counts), never altering the `StreamingPhysicsExpander` algorithm. Because a stream now carries more real-token deltas at the same per-token cadence, **the total stream duration is slightly longer** for a given `tokensPerSecond` than the old whole-word default. The joined deltas always equal the original text exactly, in both modes.

> **Behaviour change (default flip).** `subwordStreaming` shipped default-`false` and was flipped to default-`true`. The streaming codec golden fixtures (`streaming-text.jsonl` for every provider) were regenerated to reflect the finer deltas; non-streaming and streaming-tool-call goldens are unchanged (the canonical tool-call completion has no text). Consumers that need the exact old whole-word wire output must now set `subwordStreaming: false` explicitly.

## Conversation Matchers

`LlmConversationMatcher` evaluates predicates against a `ParsedConversation` decoded from the inbound request body:

- `whenTurnIndex(n)` -- assistant turn count
- `whenLatestMessageContains(text)` -- substring match on last message
- `whenLatestMessageMatches(pattern)` -- regex match on last message
- `whenLatestMessageRole(role)` -- role of last message
- `whenContainsToolResultFor(toolName)` -- tool result presence
- `withNormalization(options)` -- opt-in prompt normalisation applied before the `contains`/`matches` text predicates (see below)

Predicates are stored as `ConversationPredicates` on `HttpLlmResponse` for JSON round-tripping. The matcher is lazily reconstructed from predicates after deserialisation.

### Multimodal (image) recognition

The decoders recognise **image content parts** on the request side so a mocked request can be matched on image presence. Each `ParsedMessage` exposes `hasImage()`, `imageCount()`, and `getImages()` (a list of `ImagePart`, each carrying the declared media type where the provider shape includes it):

| Provider | Image shape recognised |
|----------|------------------------|
| OPENAI / AZURE_OPENAI | `image_url` content part (media type parsed from a `data:` URL; `null` for a remote `https` URL) |
| ANTHROPIC / BEDROCK | `image` block with a `source.media_type` |
| GEMINI | `inline_data` / `inlineData` part with `mime_type` / `mimeType` |

This is **request-side recognition only** — MockServer notes that a message contains an image (and how many, and the media type) so conversation matchers can assert it; it does not store the image bytes or generate image responses.

### Multimodal (audio) recognition

The OpenAI decoder also recognises **audio content parts** on the request side, mirroring the image handling. Each `ParsedMessage` exposes `hasAudio()`, `audioCount()`, and `getAudio()` (a list of `AudioPart`, each carrying the declared `format` where the provider shape includes it):

| Provider | Audio shape recognised |
|----------|------------------------|
| OPENAI / AZURE_OPENAI | `input_audio` content part (`format` read from `input_audio.format`, e.g. `wav`/`mp3`; `null` when absent) |

Like image recognition, this is **request-side only** — MockServer notes that a message contains audio (and how many, and the declared format); it does not store the audio bytes.

### Normalised prompt matching

Agent prompts are dynamically assembled, so exact-byte matching is brittle. `NormalizationOptions` (carried on `ConversationPredicates`) applies a **deterministic** transform to the latest-message text before the text predicates run, via `PromptNormalizer.normalize(text, options)`:

- `collapseWhitespace` (default on) -- collapse runs of whitespace to a single space and trim
- `lowercase` (default off) -- lowercase the text
- `sortJsonKeys` (default on) -- when the prompt is JSON, sort object keys so key ordering is irrelevant
- `dropBuiltInVolatileFields` (default off) -- strip ISO-8601 timestamps, UUIDs, and `prefix_…` ids (`req_`, `msg_`, `call_`, …)
- `dropVolatileFields` -- names of JSON fields to drop before matching

For `latestMessageContains`, both the subject text and the expected substring are normalised; for `latestMessageMatches`, only the subject is normalised (normalising the regex source would corrupt the pattern). Normalisation applies **only to the latest-message text** — the `containsToolResultFor` tool name, `turnIndex`, and `latestMessageRole` are matched exactly as specified. Boolean options are nullable: an unset flag uses its default (`collapseWhitespace` and `sortJsonKeys` on; `lowercase` and `dropBuiltInVolatileFields` off), resolved identically whether the options arrive via the REST API or the MCP tool. Normalisation is idempotent and pure — it never makes a test flaky — and is a *modifier*, not a predicate: it does not count toward `hasAnyPredicate()` and has no effect unless a text predicate is also set.

### Semantic prompt matching (opt-in, exploratory)

The `semanticMatch` predicate (`ConversationPredicates.semanticMatchAgainst`) matches when the latest message expresses a given intent, judged by a runtime LLM. It is deliberately quarantined from the deterministic path:

- **Off by default.** `SemanticMatching` is a static gate that is only `install`ed (at server start) when `mockserver.llmSemanticMatchingEnabled` is set **and** a backend resolves via `LlmBackendResolver`. Until then `isEnabled()` is false and `LlmConversationMatcher` **ignores** the predicate (logs once, deterministic fallback) — so default behaviour is unchanged.
- **Fail-closed when active.** `SemanticPromptMatcher` asks the LLM (via the Phase-2 `LlmCompletionService`, `temperature=0`, cached) a strict yes/no judge question; a non-affirmative, empty, or errored answer does not match.
- **Never for assertions.** It is non-deterministic by construction (a live model) and documented as exploratory only.

## Session Isolation

`IsolationSource` describes where to extract the isolation key from an inbound request (header, query parameter, or cookie). The key is encoded into the scenario name:

```
__llm_conv_<uuid>__iso=header:x-session-id
```

`ScenarioManager` uses composite keys `(scenarioName, isolationValue)` to maintain independent state per session.

## Conversation Builder

`LlmConversationBuilder` produces an array of `Expectation` objects, one per turn, with:
- Auto-generated scenario name (with optional isolation suffix)
- State progression: `Started` -> `turn_1` -> `turn_2` -> ... -> `__done`
- `ConversationPredicates` on each `HttpLlmResponse`

The class relationships between the builder, predicates, matcher, and isolation model:

```mermaid
classDiagram
    class LlmConversationBuilder {
        +withPath(path)
        +withProvider(provider)
        +withModel(model)
        +isolateBy(IsolationSource)
        +turn() TurnBuilder
        +build() Expectation[]
    }
    class TurnBuilder {
        +whenTurnIndex(int)
        +whenLatestMessageContains(String)
        +whenLatestMessageMatches(Pattern)
        +whenLatestMessageRole(Role)
        +whenContainsToolResultFor(String)
        +whenSemanticMatch(String)
        +withNormalization(NormalizationOptions)
        +respondingWith(Completion)
        +andThen() TurnBuilder
    }
    class ConversationPredicates {
        +turnIndex: Integer
        +latestMessageContains: String
        +latestMessageMatches: String
        +latestMessageRole: Role
        +containsToolResultFor: String
        +semanticMatchAgainst: String
        +normalization: NormalizationOptions
    }
    class LlmConversationMatcher {
        +matches(HttpRequest) boolean
    }
    class IsolationSource {
        +kind: Kind
        +name: String
        +header(String)
        +queryParameter(String)
        +cookie(String)
    }
    class LlmScenarioNames {
        +ISOLATION_MARKER: String
        +generate(IsolationSource) String
    }
    class ScenarioManager {
        +getState(scenarioName, isolationValue)
        +setState(scenarioName, isolationValue, state)
    }

    LlmConversationBuilder --> TurnBuilder : creates
    LlmConversationBuilder --> IsolationSource : uses
    LlmConversationBuilder --> LlmScenarioNames : uses
    TurnBuilder --> ConversationPredicates : produces
    LlmConversationMatcher --> ConversationPredicates : evaluates
    LlmScenarioNames --> IsolationSource : encodes
    ScenarioManager --> LlmScenarioNames : keyed by
```

## MCP Tools

Two MCP tools expose the LLM mocking feature to agents:

| Tool | Description |
|------|-------------|
| `mock_llm_completion` | Creates a single LLM expectation from provider, path, text, tool calls, usage, and an optional `outputSchema` (response-path structured-output validation) |
| `create_llm_conversation` | Creates a multi-turn conversation with scenario state chain, optional isolation, and an optional per-turn `match.normalization` object |
| `verify_tool_call` | Asserts an agent called a named tool `atLeast`/`atMost` times (optional args regex), by decoding recorded LLM requests. Supports `provider=AUTO` to auto-detect from request paths |
| `explain_agent_run` | Summarises a recorded agent run: turn/tool-call sequence, tool results, latest role. Supports `provider=AUTO` to auto-detect from request paths |
| `verify_structured_output` | Validates the JSON output text of recorded LLM responses against a JSON Schema (decodes each response via the runtime-LLM client SPI, then `JsonSchemaValidator`); reports per-response conformance |
| `verify_cost_budget` | Sums input/output tokens from recorded responses' usage, prices them via `org.mockserver.llm.cost.LlmPricing`, and asserts the total USD cost is within `maxCostUsd` — a deterministic CI cost gate. Unpriceable models are reported and excluded |
| `mock_llm_failover` | Creates a failover/retry scenario: the first N requests fail with specified HTTP statuses, then subsequent requests succeed with a provider-correct LLM response. Uses `LlmFailoverBuilder` |

The first two validate provider availability against `ProviderCodecRegistry` at registration time. The analysis tools delegate to `org.mockserver.llm.analysis.AgentRunAnalyzer`.

Beyond these LLM-specific tools, the same MCP server exposes generic **expectation-authoring and record/replay control tools** (`create_expectation`, `raw_expectation`, `list_expectations`, `clear_expectations`, `verify_request`, `retrieve_recorded_requests`, `retrieve_request_responses`, `set_operating_mode`, `promote_recordings`) so an agent can author and drive any mock — including recording real LLM traffic in SPY/CAPTURE mode and promoting it to mocks. Each is read/mutate-classified for the control-plane authorization gate. See [ai-protocol-mocking.md → Expectation-authoring and record/replay control tools](ai-protocol-mocking.md#expectation-authoring-and-recordreplay-control-tools).

## Structured-output validation

Structured-output validation against a JSON Schema works on **both sides** of a mock, both built on `JsonSchemaValidator`:

- **Read side — `verify_structured_output`** (assertion over recorded traffic): decodes each recorded response for a provider via the runtime-LLM client SPI and checks the assistant's output text against the schema. Read-only and deterministic.
- **Response side — `Completion.outputSchema`** (fixture sanity check): a completion may declare the JSON Schema its `text` should conform to (`Completion.withOutputSchema(...)`, the `outputSchema` expectation-JSON field, or the `mock_llm_completion` MCP param — string or inline object). `HttpLlmResponseActionHandler.validateStructuredOutput(...)` validates the configured text as the response is encoded. It is **fail-soft** by default: a mismatch never alters the response body — it adds the `x-mockserver-structured-output-invalid` diagnostic header (a single-line, CR/LF-collapsed message; non-streaming only) and logs a warning. A blank schema, absent text, or a malformed schema are all "nothing to check" and can never break the response. This surfaces malformed fixtures while still letting you return a deliberately non-conforming response unchanged.
  - **Strict enforcement (opt-in) — `Completion.enforceOutputSchema`**: set the `enforceOutputSchema` flag (`Completion.enforceOutputSchema()` / `withEnforceOutputSchema(true)`, the `enforceOutputSchema` expectation-JSON field, or the `mock_llm_completion` MCP boolean param) alongside the schema to switch from fail-soft to strict. `HttpLlmResponseActionHandler.enforcementErrorResponseOrNull(...)` then **fails loudly** when the configured body does not conform: it returns a provider-correct error (HTTP `502` via `LlmErrorBodies`, with the `x-mockserver-structured-output-invalid` header) instead of the non-conforming body. This models a provider's strict `response_format: json_schema` mode, which guarantees schema-valid output. `HttpActionHandler` checks it before dispatch (after chaos, which takes priority — a transport-level failure independent of the body), so it applies on **both** the streaming and non-streaming paths and a strict streaming completion with a non-conforming body never begins streaming. The shared `structuredOutputError(...)` helper backs both the fail-soft and strict paths, so a blank/absent-text/malformed schema is a no-op in either mode and can never produce an enforcement error. The flag is back-compatible: unset/`false` keeps the fail-soft behaviour and the flag has no effect without an `outputSchema`.

## Approximate token counting and usage inference

`TokenCounter` (`org.mockserver.llm`) is a pure, deterministic helper that estimates token counts for text. It is **an estimate, not a real tokenizer** — it loads no BPE merges, SentencePiece model, or provider vocabulary (no dependency, no embedded vocab), so its counts still differ from a provider's billed counts. It instead **approximates GPT-style BPE segmentation** with a handful of cheap structural rules so counts land close to real tokenizers — validated in `TokenCounterTest` against hard-coded OpenAI `cl100k_base` (GPT-4) reference counts to be **within ±15% for ordinary English prose** (further off for code, non-Latin scripts, or long punctuation runs). The text is walked once into runs: **words** (letters/digits, with `- ' _` kept word-internal so `open-source` stays one word) absorb a leading space, split at `lower→Upper` case and letter↔digit boundaries (so `ChatGPT`→`Chat`,`GPT`), and cost 1 token plus one extra per ~5 characters beyond the first five (short/common words stay whole, long words split); **CJK** is denser (Han/Hangul ~1.2 tokens/char, Kana ~0.5); **punctuation/symbols** ~1 token each; a plain **space** is free while a newline/tab costs ~1. The result is clamped to ≥1 for any non-empty text and 0 for `null`/empty. It exposes `estimateTokens(text)`, `estimatePromptTokens(ParsedConversation)` (per-message text + a small per-message chat-format overhead, including tool-call args and tool results), `estimateCompletionTokens(text, toolCalls)`, and the streaming segmenters `segmentForStreaming(text)` / `streamingTextTokens(text, physics)` (see Streaming Physics below).

When the opt-in `mockserver.llmInferUsageEnabled` flag is set, `HttpLlmResponseActionHandler.withInferredUsageIfEnabled(...)` returns a **per-request shallow copy** of the completion carrying approximate `prompt_tokens` / `completion_tokens` for a mocked completion that omits `usage`, on both the non-streaming and streaming paths, **before** the codec encodes. The shared expectation `Completion` is never mutated, so the request-dependent prompt estimate is recomputed every request (no stale caching, no concurrent-write race). The prompt estimate comes from decoding the inbound request with the provider codec (`ProviderCodec.decode`); the completion estimate from the response text and tool-call arguments. It is **off by default** so existing responses are unchanged (an absent `usage` continues to encode as zeros) and a completion that already declares a non-zero `usage` is never overwritten. Decoding failures are fail-soft (prompt estimate degrades to 0, never an error). This is independent of `HttpLlmResponseActionHandler.estimateTokenCount(...)`, the existing rough character estimate that backs the token-based chaos quota, which is unchanged.

## Cached/reasoning token usage and reasoning content encoding

The `Usage` optional fields and the `Completion` reasoning fields are **encoded** by every chat codec onto the response wire — additively, and only when set, so a completion that omits them encodes byte-identically to before (the golden fixtures are unchanged).

**Usage token details** — emitted only when the corresponding `Usage` field is non-null and non-zero, under each provider's native key (the same keys the runtime-LLM clients *decode*, so encode/decode are symmetric):

| Provider(s) | `cachedInputTokens` key | `cacheCreationTokens` key | `reasoningTokens` key |
|-------------|-------------------------|---------------------------|-----------------------|
| ANTHROPIC / BEDROCK | `usage.cache_read_input_tokens` | `usage.cache_creation_input_tokens` | — (no native field) |
| OPENAI / AZURE_OPENAI | `usage.prompt_tokens_details.cached_tokens` | — | `usage.completion_tokens_details.reasoning_tokens` |
| OPENAI_RESPONSES | `usage.input_tokens_details.cached_tokens` | — | `usage.output_tokens_details.reasoning_tokens` |
| GEMINI | `usageMetadata.cachedContentTokenCount` | — | `usageMetadata.thoughtsTokenCount` |
| OLLAMA | — (no native field) | — | — (no native field) |

The Anthropic `cache_read`/`cache_creation` keys are mirrored into the streaming `message_start` usage; the OpenAI Responses details into the `response.completed` usage; the Gemini counts into the final streaming chunk's `usageMetadata`. (Bedrock and Azure inherit the Anthropic/OpenAI behaviour by delegation.)

**Reasoning ("thinking") content** — `Completion.reasoningText` (plus optional `reasoningSignature` for Anthropic redaction) encodes a provider-correct reasoning block **before** the visible text block, on both paths, absent unless set:

| Provider | Non-streaming shape | Streaming events |
|----------|--------------------|------------------|
| ANTHROPIC / BEDROCK | leading `{"type":"thinking","thinking":…,"signature":…}` content block | `content_block_start`(thinking) → `thinking_delta` → optional `signature_delta` → `content_block_stop`, at index 0 before the text block |
| OPENAI_RESPONSES | leading `{"type":"reasoning","summary":[{"type":"summary_text","text":…}]}` output item | `response.output_item.added`(reasoning) → `response.reasoning_summary_part.added` → `response.reasoning_summary_text.delta`/`.done` → `…part.done` → `output_item.done` |
| GEMINI | leading `{"text":…,"thought":true}` part | a thought chunk (`parts:[{text,thought:true}]`) before the text chunks |
| OLLAMA | `message.thinking` sibling string | a leading chunk with `message.thinking` set |

OpenAI Chat Completions has no reasoning-content representation on the response wire (reasoning is summarised only via the Responses API), so `reasoningText` is not encoded for `OPENAI`/`AZURE_OPENAI`. None of this touches the request matcher — only response encoding.

## Adversarial-response harness

`AdversarialResponseLibrary` (`org.mockserver.llm.adversarial`) is a curated catalog of hostile/malformed *responses* an agent might receive from a compromised tool or jailbroken model — prompt injection, jailbreak persona-swaps, data-exfiltration requests, malformed/truncated JSON, an empty response, and an over-long repetition. The `mock_adversarial_llm_response` MCP tool mocks a chosen payload as the provider-correct LLM response so you can test that your agent **resists** it. The payloads are short, well-known benign test fixtures (not working exploits) — a defensive testing aid — and generation is deterministic (each id maps to fixed text).

## Moderation, content-filter, and refusal simulation

Production agents must handle provider **content-filter blocks** and **refusals**; these opt-in, additive capabilities let you trigger them deterministically. All default to off / not-flagged.

- **OpenAI Moderations endpoint** — set `moderation` (a `ModerationResponse`, `org.mockserver.model`) on an `HttpLlmResponse` and the handler returns OpenAI's `POST /v1/moderations` wire shape: `{"id":..,"model":..,"results":[{"flagged":bool,"categories":{..},"category_scores":{..}}]}`. Every canonical OpenAI category is emitted; those named in `flaggedCategories` are `true` with a high score, the rest `false` with a low score, and top-level `flagged` is `true` when any is flagged. Encoded by the pure `OpenAiModerationBodies` helper (`org.mockserver.llm`); category matching is case-insensitive; `model` defaults to `omni-moderation-latest`. An empty/absent list is the safe not-flagged default.
- **Azure content-filter annotations** — set `contentFilter` (an `LlmContentFilter` carrying `hate`/`sexual`/`violence`/`selfHarm` severities: `safe`/`low`/`medium`/`high`) on an `AZURE_OPENAI` response and the handler adds Azure's `content_filter_results` (on each `choices[]` entry) and `prompt_filter_results` (top-level array) annotations after the codec encodes. Each category renders as `{"filtered":bool,"severity":..}`; Azure's default policy filters at `medium`/`high`, so `filtered` is derived at those severities. Non-Azure providers ignore `contentFilter` for annotations. Fail-soft — any parse error leaves the body unchanged.
- **Anthropic refusal preset** — `LlmRefusalPresets.anthropicRefusal()` (`org.mockserver.llm`) returns a `Completion` with `stop_reason:"refusal"` (and no text, matching Anthropic's empty-content refusal), which the Anthropic codec encodes as an HTTP 200 refusal message. `anthropicRefusal(message)` includes a short refusal text.

The `moderation` and `contentFilter` fields round-trip as top-level fields on `HttpLlmResponse` through the schema-validated expectation JSON.

## Fault / chaos injection

`LlmChaosProfile` (`org.mockserver.model`) attaches a fault profile to any `HttpLlmResponse` for resilience testing. Applied by `HttpLlmResponseActionHandler`:

- **Probabilistic error** — `chaosErrorResponseOrNull(...)` returns an error `HttpResponse` (`errorStatus` + optional `Retry-After`) when triggered. An `errorStatus` with no `errorProbability` always fires; a fractional probability draws once (reproducible via `seed`). `HttpActionHandler` checks this first and, if present, returns the error on the normal (non-streaming) path — a provider error is a plain HTTP response, not an SSE stream, even for a would-be streaming completion.
- **Mid-stream truncation** — `applyStreamingChaos(...)` keeps a leading `truncateAtFraction` of the SSE events (default 0.5) so the stream ends early.
- **Malformed SSE** — appends a deliberately broken-JSON chunk so the client must handle a corrupt event.
- **Stateful request quota** — a deterministic fixed-window rate limit (`quotaName` + `quotaLimit` + `quotaWindowMillis`, optional `quotaErrorStatus` default 429). `quotaErrorResponseOrNull(...)` (called first inside `chaosErrorResponseOrNull`) records one request against `org.mockserver.llm.LlmQuotaRegistry` and returns the quota error once the in-window count exceeds the limit. The registry is a process-wide singleton keyed by `quotaName` (expectations sharing a name share one counter — model an upstream account limit), thread-safe via `ConcurrentHashMap`, with an injectable clock for testing, cleared on `HttpState.reset()`. A misconfigured/partial quota fails open (never rate-limits).
- **Content-filter block** — `contentFilterBlockProbability` (0.0–1.0, seeded-deterministic, shares `seed`) emits the **provider-correct** content-filter block via the pure `LlmContentFilterBodies` helper (`org.mockserver.llm`): OpenAI-family → HTTP 400 `content_filter` error; `AZURE_OPENAI` → HTTP 400 whose `innererror` carries the per-category `content_filter_result` (severities from the response's `contentFilter`, defaulting to hate at `high`); `ANTHROPIC`/`BEDROCK` → HTTP 200 `stop_reason:"refusal"`; `GEMINI` → HTTP 200 `finishReason:"SAFETY"`; unknown provider → generic 400. Checked (after the quota) inside `chaosErrorResponseOrNull` and takes **priority over the generic probabilistic error**, so a would-be stream short-circuits to the block. Content-filter is a distinct wire shape, so it lives in a sibling helper rather than `LlmErrorBodies`' overload/rate-limit/server kinds.

Truncation, malformed-SSE, and the stateful quota are fully deterministic; the probabilistic error path is deterministic at probability 0.0/1.0. Each injection increments the `LLM_CHAOS_INJECTED_COUNT` metric. The profile round-trips as the top-level `chaos` field on `HttpLlmResponse` (alongside `completion`, `embedding`, and `conversationPredicates`) and is exposed per turn in the dashboard wizard and via the `chaos` MCP parameter.

### Provider-specific error bodies

Both chaos error paths (the probabilistic `errorStatus` and a stateful quota breach) emit the **provider-correct JSON error body** for the detected provider, so client SDK retry/backoff logic — which parses the body's `error.type` / `error.code` — can be exercised faithfully against a mock. `LlmErrorBodies` (`org.mockserver.llm`) is a pure, deterministic helper that maps a `Provider` + a coarse error `Kind` (derived from the HTTP status) to the body shape. When the provider is `null`/unknown, the handler falls back to the previous generic body (`{"error":{"type":"chaos_injected"|"quota_exceeded"|"token_quota_exceeded",...}}`), so behaviour is unchanged for an unspecified provider.

The error `Kind` is derived from the status: `429 → RATE_LIMIT`, `529 → OVERLOADED`, any other status `→ SERVER_ERROR`.

| Provider | Body shape (by error kind) |
|----------|----------------------------|
| ANTHROPIC / BEDROCK | `{"type":"error","error":{"type":"overloaded_error"\|"rate_limit_error"\|"api_error","message":...}}` — Bedrock delivers the Anthropic body unchanged |
| OPENAI / OPENAI_RESPONSES / AZURE_OPENAI | `{"error":{"message":...,"type":"rate_limit_exceeded"\|"server_error","param":null,"code":...}}` — `code` is the numeric status for `server_error`, the string `"rate_limit_exceeded"` for a 429 |
| GEMINI | `{"error":{"code":<status>,"message":...,"status":"UNAVAILABLE"\|"RESOURCE_EXHAUSTED"\|"INTERNAL"}}` |
| OLLAMA | `{"error":"<message>"}` (a plain message string) |

The `Retry-After` and provider-specific rate-limit *headers* (see below) are still applied on top of the body by the same code path, so a 429/529 carries both the correct body and the correct headers.

### Token-based quota (TPM/TPD)

Real LLM providers (OpenAI, Anthropic) enforce token-per-minute (TPM) and token-per-day (TPD) limits in addition to request-count limits. MockServer models this with two additional `LlmChaosProfile` fields: `tokenQuotaLimit` (Long, >= 1) and `tokenQuotaWindowMillis` (Long, >= 1). When both are set alongside `quotaName`, each response charges its cumulative token count (from `Usage.inputTokens + outputTokens`, or `ceil(text.length()/4)` as a fallback when no Usage is present) against a separate fixed-window counter in `LlmQuotaRegistry` under the key `quotaName + ":tokens"`. Once the in-window token sum exceeds `tokenQuotaLimit`, the response path returns a 429 (or custom `quotaErrorStatus`) with error type `token_quota_exceeded` and the `Retry-After` header when set. The request-count quota and token quota are independent counters that can coexist on the same profile; the request-count quota is checked first. Embeddings contribute zero tokens. The `LlmQuotaRegistry.tryAcquire(name, limit, windowMillis, amount)` overload supports arbitrary increment amounts for this purpose.

### Provider rate-limit headers

When an LLM response path returns a rate-limit / quota error (probabilistic `errorStatus` or stateful quota 429), MockServer emits the **provider-correct rate-limit HTTP headers** that real LLM providers send, so client SDK retry/backoff logic (which reads those headers) can be exercised faithfully against a mock. The same headers are stamped on **successful** responses when a quota is configured, so a client can observe the limit counting down.

`LlmRateLimitHeaders` (`org.mockserver.llm`) is a pure, deterministic helper that maps a `Provider` + quota parameters to the **provider-specific** header names and values. The standard `Retry-After` header is generic HTTP (not provider-specific), so it is owned by `HttpLlmResponseActionHandler.applyRateLimitHeaders(...)` — emitted once for every provider on a 429 — rather than by the helper, so it can never appear twice on the wire.

| Provider | Provider-specific headers on error (429) | Headers on success (with quota) | `Retry-After` on 429 |
|----------|----------------------|-------------------------------|---------------------|
| OPENAI / OPENAI_RESPONSES / AZURE_OPENAI | `x-ratelimit-limit-requests`, `x-ratelimit-remaining-requests`, `x-ratelimit-reset-requests` (e.g. `"60s"`) | `x-ratelimit-limit-requests`, `x-ratelimit-reset-requests` | yes (seconds) |
| ANTHROPIC | `anthropic-ratelimit-requests-limit`, `anthropic-ratelimit-requests-remaining`, `anthropic-ratelimit-requests-reset` (RFC 3339 timestamp) | `anthropic-ratelimit-requests-limit`, `anthropic-ratelimit-requests-reset` | yes (seconds) |
| GEMINI | *(none)* | *(none)* | yes (seconds) |
| BEDROCK | *(none)* | *(none)* | yes (seconds) |
| OLLAMA | *(none)* | *(none)* | yes, when a quota window or literal `retryAfter` is set |

Header values are derived from the `LlmChaosProfile` fields: `quotaLimit` becomes the limit header; the reset duration is `quotaWindowMillis / 1000` (falling back to `tokenQuotaWindowMillis / 1000` for a token-only quota, then to a numeric `retryAfter`), so a **token-quota-only** 429 still carries reset/`Retry-After` headers; `remaining` is `0` on a 429 (omitted on success since the registry window count is not cheaply re-readable). On a 429 `Retry-After` is the literal configured `retryAfter` (which may be an HTTP-date) when set, otherwise the computed reset seconds. Applied at three sites: the probabilistic chaos error, the quota-exceeded error, and the successful non-streaming response when a quota is configured.

## Agent-run analysis

`AgentRunAnalyzer` (`org.mockserver.llm.analysis`) is a deterministic, read-only inspector. Given the LLM requests MockServer recorded (retrieved via the normal request log), it decodes each with the provider's `ProviderCodec` and treats the **richest** conversation (most messages — the latest dialogue snapshot) as the canonical run. From that it derives:

- `inspectToolCalls(requests, provider, toolName, argsRegex)` → count + matched tool calls (powers `verify_tool_call`).
- `summarise(requests, provider)` → message count, assistant-turn count, ordered tool-call name sequence, tool-result keys, latest message role (powers `explain_agent_run`).

- `buildCallGraph(requests, provider)` → a `CallGraph` of nodes (one per message, one per assistant tool call) and directed edges: `NEXT` (message sequence), `INVOKES` (assistant turn → the tool calls it made), `RESULT` (tool call → the tool message that returned its result, correlated by tool-call id). Powers the dashboard call-graph view.

No LLM is called and no network is used — it reads the structure the codecs already produce, so assertions are reproducible. The MCP tools are thin wrappers that retrieve recorded requests (`/mockserver/retrieve?type=REQUESTS`) and format the analyzer's output; `explain_agent_run` includes the `callGraph` (nodes + edges). The dashboard **Sessions** view (`SessionInspector` → `AgentRunGraph.tsx`, with the pure transform `mockserver-ui/src/lib/callGraph.ts`) loads the graph per session via `explain_agent_run` and renders it as a step list (role + invoked tool calls + result indicator) plus a copyable Mermaid `flowchart`.

### Proxied/forwarded traffic support

Agent-run analysis works identically for **proxied/forwarded** traffic. Every incoming request — whether it matches a mock expectation or is forwarded to an upstream provider — is recorded as a `RECEIVED_REQUEST` log entry with the full request body. The `type=REQUESTS` retrieval returns these entries, and `AgentRunAnalyzer` decodes them with the appropriate `ProviderCodec`.

**Provider auto-detection.** The `verify_tool_call` and `explain_agent_run` MCP tools accept `"AUTO"` as the `provider` parameter. `ProviderDetector` (`org.mockserver.llm.ProviderDetector`) infers the provider from recorded request paths (e.g. `/v1/messages` maps to `ANTHROPIC`, `/v1/chat/completions` to `OPENAI`), mirroring the UI-side detection in `llmTraffic.ts`. This is especially useful for proxy users who route real LLM calls through MockServer and may not know or want to specify the provider explicitly.

**Dashboard Sessions view.** The Sessions view groups proxied LLM traffic by upstream host (from the `Host` header) when no conversation-isolation expectations are configured, so proxy traffic to different providers appears in separate session lanes. The call graph (via `AgentRunGraph`) is shown for all sessions including these host-grouped proxy sessions.

**Capturing coding-assistant CLIs.** A common use is to point a coding assistant at MockServer as an HTTPS proxy and watch its model calls in the Traffic / LLM Traces / LLM Optimise views. Detection is path-based so it works regardless of the (possibly private) gateway host a tool is configured for:

| CLI | LLM endpoint | Detected as |
|-----|--------------|-------------|
| Claude Code (`claude`) | `api.anthropic.com/v1/messages` | `ANTHROPIC` |
| opencode (Codex backend) | `chatgpt.com/backend-api/codex/responses` | `OPENAI_RESPONSES` |
| Tabnine CLI (Gemini-CLI fork) | `<gateway>/…/chat/completions` | `OPENAI` |

opencode's OpenAI **Codex** backend serves the Responses API at the non-standard path `/backend-api/codex/responses`; `LlmProviderSniffer`, `ProviderDetector`, and `llmTraffic.ts` recognise the `/codex/responses` path (and the `chatgpt.com` host on it) the same as the hosted `/v1/responses`. A local-only smoke harness that drives the real CLIs end-to-end lives in `scripts/llm-proxy-capture/`; the CI-safe equivalent is `CodingCliLlmCaptureTest` (mockserver-core) and the `llmTraffic.test.ts` codex cases (mockserver-ui).

## Dashboard Rendering

The expectation panel renders an "LLM Response" badge (with provider, model, and text preview) when `httpLlmResponse` is present on an expectation.

The `ScriptedTurnsPanel` component renders the scripted turn sequence for conversation expectations, showing per-turn predicates, responses, and scenario state transitions.

## Domain Model

```mermaid
classDiagram
    class HttpLlmResponse {
        +provider: Provider
        +model: String
        +completion: Completion
        +embedding: EmbeddingResponse
        +rerank: RerankResponse
        +conversationPredicates: ConversationPredicates
    }
    class Completion {
        +text: String
        +toolCalls: List~ToolUse~
        +toolChoice: String
        +stopReason: String
        +usage: Usage
        +streaming: Boolean
        +streamingPhysics: StreamingPhysics
        +outputSchema: String
        +enforceOutputSchema: Boolean
        +reasoningText: String
        +reasoningSignature: String
    }
    class ToolUse {
        +id: String
        +name: String
        +arguments: String
    }
    class Usage {
        +inputTokens: Integer
        +outputTokens: Integer
        +cachedInputTokens: Integer
        +cacheCreationTokens: Integer
        +reasoningTokens: Integer
    }
    class StreamingPhysics {
        +timeToFirstToken: Delay
        +tokensPerSecond: Integer
        +jitter: Double
        +seed: Long
    }
    class EmbeddingResponse {
        +dimensions: Integer
        +deterministicFromInput: Boolean
        +seed: Integer
    }
    class RerankResponse {
        +topN: Integer
        +deterministicFromInput: Boolean
        +seed: Long
    }
    class Provider {
        <<enum>>
        ANTHROPIC
        OPENAI
        OPENAI_RESPONSES
        GEMINI
        BEDROCK
        AZURE_OPENAI
        OLLAMA
        COHERE
        VOYAGE
        MISTRAL
        XAI
        DEEPSEEK
        GROQ
        OPENROUTER
        ORCAROUTER
    }
    class ConversationPredicates {
        +turnIndex: Integer
        +latestMessageContains: String
        +latestMessageMatches: String
        +latestMessageRole: Role
        +containsToolResultFor: String
        +semanticMatchAgainst: String
        +normalization: NormalizationOptions
    }
    class ParsedConversation {
        +messages: List~ParsedMessage~
    }
    class ParsedMessage {
        +role: Role
        +textContent: String
        +toolName: String
        +toolCallId: String
        +images: List~ImagePart~
        +audio: List~AudioPart~
        +hasImage() boolean
        +imageCount() int
        +hasAudio() boolean
        +audioCount() int
    }
    class IsolationSource {
        +kind: Kind
        +name: String
    }
    class LlmScenarioNames {
        +ISOLATION_MARKER: String
    }

    HttpLlmResponse --> Provider
    HttpLlmResponse --> Completion
    HttpLlmResponse --> EmbeddingResponse
    HttpLlmResponse --> RerankResponse
    HttpLlmResponse --> ConversationPredicates
    Completion --> ToolUse
    Completion --> Usage
    Completion --> StreamingPhysics
```

## Runtime LLM client SPI

Most LLM mocking is deterministic and offline. A few opt-in features (drift detection, semantic prompt matching) need MockServer to act as a *client* against a real LLM the user already runs. This is the opposite direction to the codecs (`decode` parses an inbound request; `encode` builds a mock response), so a sibling SPI mirrors the codec-registry shape:

- `org.mockserver.llm.client.LlmClient` — `provider()`, `buildCompletionRequest(LlmBackend, ParsedConversation)`, `parseCompletionResponse(HttpResponse)`. Implementations are **pure** (no transport, no shared state) so they unit-test offline. `AbstractLlmClient` provides URL parsing, base-request construction, and JSON helpers.
- `org.mockserver.llm.client.LlmClientRegistry` — singleton, static-block registration keyed by `Provider`, structurally identical to `ProviderCodecRegistry`. All seven providers registered: Ollama, OpenAI, OpenAI Responses, Azure OpenAI, Anthropic, Gemini, Bedrock.
- `org.mockserver.llm.client.LlmBackend` — immutable record (`name, provider, baseUrl, apiKey, model, headers, timeoutMillis`); `baseUrl`/`model` default per provider, `apiKey` redacted in `toString()`.
- `org.mockserver.llm.client.LlmBackendResolver` — three config layers: (1) provider env conventions (`OPENAI_API_KEY` / `ANTHROPIC_API_KEY` / `GEMINI_API_KEY` / `OLLAMA_HOST`), (2) `mockserver.llmProvider`/`llmApiKey`/`llmModel`/`llmBaseUrl`, (3) named backends JSON (`mockserver.llmBackendsConfig`). Properties take precedence over env; named backends are selectable by name.
- `org.mockserver.llm.client.LlmCompletionService` — the single entry point for runtime-LLM features. Looks up the client, builds the request, sends it via an injected `LlmTransport`, parses the response. Enforces the safety rules: **off unless a backend resolves**, **fail closed** (timeout / transport error / non-2xx / parse failure → `Optional.empty()` + one log line), and **reproducible** (clients pin `temperature=0`/seed; responses cached per provider+model+baseUrl+normalised prompt). `LlmTransport` is a seam; `NettyHttpClientLlmTransport` wraps the server's `NettyHttpClient` in production.

```mermaid
flowchart TD
    A["Runtime-LLM feature needs an LLM"] --> B{"Backend resolved?"}
    B -- "no" --> C["Feature unavailable\nlog one line, deterministic fallback"]
    B -- "yes" --> D["LlmClientRegistry.lookup(provider)"]
    D --> E["client.buildCompletionRequest"]
    E --> F["LlmTransport.send (NettyHttpClient)"]
    F --> G["client.parseCompletionResponse to Completion"]
    F -- "timeout / error / non-2xx" --> C
```

Adding a provider = implement `LlmClient` + one `register(...)` line — the same one-line story as codecs. **Ollama** is the reference backend (no auth, local, free) used to prove the path. **Bedrock** builds the Anthropic-on-Bedrock body and parses the Anthropic-shaped response, and performs automatic AWS SigV4 request signing via `AwsSigV4Signer`: when `LlmBackend#apiKey()` carries credentials (`akid:secret[:token]`), `BedrockLlmClient` signs the request and the SigV4 headers take precedence over any `Authorization` supplied via the `headers` escape hatch; the escape hatch (or a signing proxy) remains supported for pre-signed setups.

This SPI is never on the deterministic assertion/matching path. The features that consume it (drift detection, semantic matching) are opt-in and documented in this file above.

## OpenTelemetry export

Optional, off-by-default OTLP export, in two independent parts (both fail-soft — a setup error logs one line and never affects the server or a response; `io.opentelemetry` is relocated in the shaded jar):

- **Metrics** (`org.mockserver.metrics.OtelMetricsExporter`, `mockserver.otelMetricsEnabled`) — bridges the existing `Metrics.Name` gauges (the same set exposed for Prometheus, including the LLM/SSE/chaos counters) to OTLP as observable gauges that read the current values, so Prometheus and OTLP stay consistent. An alternative to the Prometheus endpoint.
- **GenAI spans** (`org.mockserver.telemetry.GenAiSpanExporter` + `GenAiSpans`, `mockserver.otelTracesEnabled`) — emits one span per LLM completion with GenAI semantic-convention attributes (`gen_ai.system`, `gen_ai.request.model`, `gen_ai.usage.*`, `gen_ai.response.finish_reasons`, tool-call count). When a provider reports them, cached-input and reasoning token counts are also emitted under the `mockserver.gen_ai.usage.*` namespace (`cached_input_tokens`, `cache_creation_tokens`, `reasoning_tokens`) — there is no GenAI semconv attribute for these yet, and they are omitted entirely when absent. These are spans MockServer codes deliberately — **no auto-instrumentation**. `GenAiSpans` is a process-wide no-op until `GenAiSpanExporter` installs a tracer. Spans fire on two paths:
  - **Mock action path** — `HttpLlmResponseActionHandler` calls `GenAiSpans.recordCompletion()` for mocked responses (streaming and non-streaming).
  - **Forward/proxy path** — `HttpActionHandler.emitForwardGenAiSpan()` detects LLM traffic via `LlmProviderSniffer` (maps the forwarded request's target host to a `Provider`), parses the upstream response using the provider's `LlmClient.parseCompletionResponse()`, and records a completion span. Covers matched-expectation forwards and unmatched proxy-pass. Streaming forward paths emit the GenAI span in the completion listener after the full SSE body is captured. Model is extracted from the response body (most providers include it), falling back to the request body.

Both use the OTLP HTTP/protobuf exporter with the JDK HttpClient sender (no gRPC/OkHttp) and share `mockserver.otelEndpoint` (a base collector URL; `/v1/metrics` and `/v1/traces` appended per signal, resolved by `telemetry.OtelEndpoints`).

## Drift detection

`detect_llm_drift` (MCP) closes the loop on stale cassettes: it replays a recorded cassette's exchanges against the **live** provider and reports structural drift in the responses. Built from two pieces in `org.mockserver.llm.drift`:

- `StructuralShapeDiff` — pure: walks two JSON documents into path→type shape maps and reports added / removed / type-changed paths (values ignored; arrays use a representative-first-element model). Reusable.
- `DriftDetector` — for each recorded exchange, decodes the recorded request via the `ProviderCodec`, builds a fresh live request via the runtime-LLM `LlmClient` (Phase 2 SPI), sends it through an injected `LlmTransport`, and diffs the live response shape against the recorded one. **Fails closed** per exchange: a missing client/codec, network error, non-2xx, or non-JSON body is reported as `COULD_NOT_CHECK`, never as drift, and never thrown.

The MCP tool resolves a backend via `LlmBackendResolver` and is **disabled** (returns `{disabled:true}`) when none is configured. When configured, it builds a transient `NettyHttpClient`-backed transport for the live calls. Because it needs real API keys/tokens and is inherently non-deterministic against a live API, it belongs in an opt-in/scheduled CI lane (see `docs/infrastructure/ci-cd.md`), never the per-commit build. No dashboard control — it is an operational/CI tool.

## AI-Powered Stub Generation

`PUT /mockserver/generateExpectation` infers a plausible MockServer expectation from an unmatched HTTP request, optionally calling a configured LLM backend for intelligent generation.

### Request format

```json
{
  "request": { "method": "GET", "path": "/api/users/42" },
  "preview": true,
  "limit": 1
}
```

| Field | Type | Default | Description |
|-------|------|---------|-------------|
| `request` | object | *required* | The unmatched `HttpRequest` to generate a stub for |
| `preview` | boolean | `true` | When `true`, returns the suggestion without registering it; `false` registers immediately |
| `limit` | integer | `1` | Number of suggestions to return (1-5) |

### Response format

```json
{
  "suggestions": [ { "httpRequest": { ... }, "httpResponse": { ... } } ],
  "confidence": 0.75,
  "preview": true,
  "explanation": "Generated from request pattern (no LLM backend configured)"
}
```

### Behaviour

1. **LLM-powered** (when a runtime LLM backend is configured via `LlmBackendResolver`): `StubGenerationPromptBuilder` builds a prompt containing the unmatched request details and up to 10 existing expectations as context. The prompt is sent via `LlmCompletionService` and the response is parsed as expectation JSON. If the LLM response is unparseable, falls back to template generation.

2. **Template fallback** (no LLM backend): generates a simple expectation matching the request's method and path with an appropriate status code (200 for GET, 201 for POST, 204 for DELETE) and a `{"status":"ok"}` body. Confidence is reported as `0.5`.

### Wiring

- `HttpState` holds an optional `LlmCompletionService` + `LlmBackend` pair, set by `LifeCycle.installLlmCompletionServiceIfAvailable()` at boot when a backend resolves via `LlmBackendResolver`.
- The handler uses `RequestMatchers.retrieveActiveExpectations(null)` to obtain context expectations.
- When `preview=false`, generated expectations are registered via `RequestMatchers.add(expectation, Cause.API)`.

### Source files

| File | Purpose |
|------|---------|
| `llm/StubGenerationPromptBuilder.java` | Builds the LLM prompt from the unmatched request + existing expectations context |
| `llm/StubGenerationResult.java` | Result DTO with suggestions, confidence, explanation, raw LLM response |
| `mock/HttpState.handleGenerateExpectation()` | Control-plane handler for `PUT /mockserver/generateExpectation` |
| `mock/HttpState.generateSimpleStub()` | Template-based fallback when no LLM is available |

## MCP server conformance testing

`run_mcp_contract_test` (MCP) verifies that a target **MCP (Model Context Protocol) server** correctly implements the protocol over Streamable HTTP. It is deterministic and involves no LLM — it checks the *protocol*, not any tool's semantics.

- `org.mockserver.netty.mcp.McpContractTest` — the orchestrator. Pure, with an injected `JsonRpcExchange` transport (a `(message, sessionId) → ExchangeResult` function), so the whole check sequence is unit-testable without a network (`McpContractTestTest` drives it with stub servers). Runs an ordered suite of checks, each producing a `CheckResult` (name, passed, statusCode, detail, validationErrors), aggregated into a `Report` (checks + negotiated `protocolVersion` + `serverInfo`):
  1. **initialize** — POSTs `initialize`, validates the JSON-RPC 2.0 envelope and that `result` carries `protocolVersion`, a `capabilities` object, and `serverInfo.name`; captures the `Mcp-Session-Id`. A transport error here short-circuits (only this check is reported).
  2. **notifications/initialized** — sends the notification (no `id`) with the session; expects HTTP 200/202/204 and no JSON-RPC error.
  3. **ping** — expects a JSON-RPC `result`.
  4. **tools/list** — expects `result.tools` to be an array where every tool has a `name` and an object `inputSchema` (`type: object`).
  5. **rejects unknown method** — sends a bogus method; expects a JSON-RPC `error` with code `-32601` (Method not found).
  6. **tools/call** (optional) — only when the caller passes `toolName`, since a real call may have side effects; a shape check on `result.content[]` + the `isError` boolean.
- The `McpToolRegistry` handler validates `targetUrl` (absolute http/https with a host), builds the `JsonRpcExchange` over the existing `sendHttpRequest` (`HttpURLConnection`, 10 s timeout), extracts the session header case-insensitively, and parses the JSON-RPC body — handling both `application/json` and `text/event-stream` (SSE `data:` framing) responses.

The check sequence is self-consistent with MockServer's own `McpStreamableHttpHandler` (which returns `-32601` for unknown methods and `202 ACCEPTED` for the initialized notification), so pointing the tool at MockServer's own `/mockserver/mcp` endpoint passes. No dashboard control — it is an agent/CI-invoked developer tool, like `run_contract_test`.

## VCR (record / replay)

LLM/MCP traffic forwarded through MockServer can be snapshotted to committable fixture files and replayed deterministically:

- **Record** — `record_llm_fixtures` (MCP) converts recorded request/response pairs (including SSE) into expectations via `SseAwareExpectationConverter`, then `FixtureRedactor` masks sensitive **headers** and — when `redactBodyFields` / `mockserver.fixtureBodyRedactFields` is set — named **JSON body fields** (recursively, value → `***REDACTED***`).
- **Replay** — `load_expectations_from_file` (MCP) loads the fixture as active expectations. Two replay aids: **strict mode** (`strict` param or `mockserver.llmVcrStrict`) registers a lowest-priority (`Integer.MIN_VALUE`) catch-all per cassette path returning HTTP 599 so an unmatched request fails loudly; **replay normalisation** (`normalizeRequestBodyFields`) drops volatile JSON fields from each recorded request body and rewrites the matcher to `JsonBody` with `MatchType.ONLY_MATCHING_FIELDS`, so per-run values do not block the match.

Both tools **auto-register the fixture in `CassetteRegistry`** at the point the file is written/loaded (keyed by file path, with the written/loaded expectation count and an `origin` of `recorded` or `loaded` respectively), so the cassette appears under `GET /mockserver/cassettes` — and therefore in the dashboard's Cassettes tab — without a separate `PUT /mockserver/cassettes` call. Re-recording or re-loading the same path updates the existing entry in place rather than duplicating it.

These are operational settings (config + MCP, for CI/automation), not dashboard controls.

## Configuration

| Property | Default | Range | Description |
|----------|---------|-------|-------------|
| `mockserver.maxLlmConversationBodySize` | `1048576` (1 MiB) | 16384 - 67108864 | Maximum request body size for conversation matcher parsing |
| `mockserver.fixtureBodyRedactFields` | _(unset)_ | — | Comma-separated JSON field names redacted from recorded fixture bodies |
| `mockserver.llmVcrStrict` | `false` | — | Strict VCR mode: unmatched requests on a cassette path return HTTP 599 |
| `mockserver.llmProvider` | _(unset)_ | — | Default runtime-LLM backend provider (enables runtime-LLM features) |
| `mockserver.llmApiKey` | _(unset)_ | — | API key for the default backend (secret; redacted in logs) |
| `mockserver.llmModel` | _(provider default)_ | — | Model for the default backend |
| `mockserver.llmBaseUrl` | _(provider default)_ | — | Base URL override for the default backend |
| `mockserver.llmBackendsConfig` | _(unset)_ | — | Path to JSON file of named backends |
| `mockserver.llmRequestTimeoutMillis` | `30000` | — | Per-request timeout for outbound runtime-LLM calls |
| `mockserver.llmSemanticMatchingEnabled` | `false` | — | Opt-in: activate the exploratory `semanticMatch` predicate (needs a backend; never for assertions) |
| `mockserver.llmInferUsageEnabled` | `false` | — | Opt-in: fill **approximate** `prompt_tokens`/`completion_tokens` (via `TokenCounter`) when a mocked completion omits `usage`. Off by default so responses are unchanged; never overwrites a declared `usage` |
| `mockserver.otelMetricsEnabled` | `false` | — | Export MockServer's metrics to an OTLP collector (alternative to Prometheus) |
| `mockserver.otelTracesEnabled` | `false` | — | Emit one explicit GenAI semantic-convention span per served LLM completion |
| `mockserver.otelEndpoint` | _(unset)_ | — | OTLP base endpoint shared by metrics and span export |
| `mockserver.otelMetricsExportIntervalSeconds` | `60` | ≥1 | How often metrics are pushed to the OTLP collector |
| `mockserver.llmMetricsEnabled` | `false` | — | Enable LLM token/cost Prometheus counters (requires `metricsEnabled`); activates forward-path response parsing even without OTLP tracing |
| `mockserver.llmCostBudgetUsd` | `-1.0` (disabled) | — | Cumulative LLM cost budget in USD; enforced on ALL forward paths (matched FORWARD, breakpoint-continuation, unmatched proxy). When exceeded, LLM forwards return 429. Negative = disabled. Resets on server reset. Trip surfaces via `mock_server_llm_cost_budget_tripped` counter, WARN log, and the dashboard Circuit Breakers section |

## LLM failover scenarios

`LlmFailoverBuilder` (`org.mockserver.client`) produces an ordered array of expectations that simulate a provider returning failures for the first N attempts, then succeeding on subsequent attempts. This is the deterministic way to test retry/failover logic (LiteLLM, Envoy AI Gateway, SDK retries) against MockServer.

The mechanism relies on expectation registration order and `Times` exhaustion: failure expectations with `Times.exactly(n)` are registered before the success expectation with `Times.unlimited()`. MockServer matches expectations in priority-then-insertion order (`SortableExpectationId`), so the first N requests match and consume the failure expectations, then fall through to the unlimited success expectation.

```java
// Java builder
llmFailover()
    .withPath("/v1/chat/completions")
    .withProvider(Provider.OPENAI)
    .withModel("gpt-4o")
    .failWith(503)
    .failWith(503)
    .failWith(429)
    .thenRespondWith(completion().withText("The answer is 42."))
    .applyTo(mockServerClient);
```

Consecutive failures with the same status code and body are coalesced into a single expectation with `Times.exactly(count)` for efficiency. Custom error bodies can be provided per failure via `failWith(status, body)`.

| MCP Tool | Description |
|----------|-------------|
| `mock_llm_failover` | Creates a failover scenario: `failStatuses` array of HTTP status codes (one per failure attempt), then a success response with provider-correct encoding. Validates provider against `ProviderCodecRegistry`. |

## Related Documents

- [Security Audit](llm-security-audit.md) -- M5 security review including known codec limitations
- [Codec Golden-File Testing](llm-codec-fixtures.md) -- how to refresh provider fixtures
- [Request Processing](request-processing.md) -- action dispatch pipeline (LLM dispatch flow)
- [Domain Model](domain-model.md) -- model class hierarchy
- [Event System](event-system.md) -- event logging pipeline
- [AI & RPC Protocol Mocking](ai-protocol-mocking.md) -- SSE, MCP, A2A mocking

## Source References

Key source files under `mockserver/mockserver-core/src/main/java/org/mockserver/`:

| File | Role |
|------|------|
| `llm/ProviderCodecRegistry.java` | Codec registry singleton; all 9 providers registered at boot (7 chat + 2 rerank-only) |
| `llm/codec/AnthropicCodec.java` | Anthropic Messages API encoder/decoder |
| `llm/codec/OpenAiChatCompletionsCodec.java` | OpenAI Chat Completions encoder/decoder |
| `llm/codec/OpenAiResponsesCodec.java` | OpenAI Responses API encoder/decoder |
| `llm/codec/GeminiCodec.java` | Gemini encoder/decoder |
| `llm/codec/BedrockCodec.java` | Bedrock wrapper (delegates to Anthropic codec; streaming uses AWS event-stream framing) |
| `llm/codec/BedrockEventStreamEncoder.java` | AWS event-stream binary framing encoder/decoder (`application/vnd.amazon.eventstream`) |
| `llm/codec/AzureOpenAiCodec.java` | Azure OpenAI wrapper (delegates to OpenAI codec) |
| `llm/codec/OllamaCodec.java` | Ollama encoder/decoder |
| `llm/codec/CohereCodec.java` | Cohere rerank-only codec (`/v1/rerank`) |
| `llm/codec/VoyageCodec.java` | Voyage AI rerank-only codec (`/v1/rerank`) |
| `llm/codec/EmbeddingVectors.java` | Shared deterministic/L2-normalised embedding-vector generation used by every embedding codec |
| `llm/codec/RerankScoring.java` | Shared deterministic rerank scoring + `{"results":[...]}` envelope used by the rerank codecs |
| `model/RerankResponse.java` | Rerank action config (`topN`, `deterministicFromInput`, `seed`) carried on `HttpLlmResponse` |
| `llm/StreamingPhysicsExpander.java` | Converts `Completion` + `StreamingPhysics` to `List<SseEvent>` |
| `llm/IsolationSource.java` | Session isolation key extraction descriptor |
| `llm/LlmScenarioNames.java` | Scenario name generation with isolation encoding |
| `llm/ParsedConversation.java` | Decoded conversation model |
| `llm/ParsedMessage.java` | Single decoded message (role, text, tool name, tool call ID) |
| `client/LlmConversationBuilder.java` | Fluent builder producing per-turn `Expectation` arrays |
| `client/LlmFailoverBuilder.java` | Fluent builder producing failover/retry `Expectation` arrays (failures then success) |
| `client/TurnBuilder.java` | Per-turn predicate and response configuration |
| `matchers/LlmConversationMatcher.java` | Evaluates `ConversationPredicates` against decoded requests |
| `llm/PromptNormalizer.java` | Deterministic prompt normalisation (whitespace/case/JSON-key-sort/volatile-field drop) |
| `model/HttpLlmResponse.java` | Action type holding provider, model, completion, predicates |
| `model/ConversationPredicates.java` | Serialisable predicate set stored on `HttpLlmResponse` |
| `model/NormalizationOptions.java` | Serialisable normalisation modifier carried on `ConversationPredicates` |
| `llm/client/LlmClient.java` + `AbstractLlmClient.java` | Runtime-LLM client SPI (build request / parse response), pure |
| `llm/client/LlmClientRegistry.java` | Singleton registry of runtime-LLM clients keyed by `Provider` |
| `llm/client/{Ollama,OpenAi,OpenAiResponses,AzureOpenAi,Anthropic,Gemini,Bedrock}LlmClient.java` | Per-provider runtime clients |
| `llm/client/LlmProviderSniffer.java` | Maps forwarded request host/path to LLM Provider for forward-path GenAI observability (path-gated fallback) |
| `llm/client/LlmBackend.java` | Immutable backend config record (apiKey redacted) |
| `llm/client/LlmBackendResolver.java` | Three-layer backend resolution (env / properties / named JSON) |
| `llm/client/LlmCompletionService.java` | Orchestrator: off-unless-configured, fail-closed, cached |
| `llm/client/LlmTransport.java` + `NettyHttpClientLlmTransport.java` | Transport seam over `NettyHttpClient` |
| `llm/ProviderDetector.java` | Heuristic provider detection from request path; mirrors UI-side detection; powers `AUTO` provider for MCP tools |
| `llm/analysis/AgentRunAnalyzer.java` | Deterministic read-only agent-run inspection (tool-call counts, run summary, call graph) |
| `llm/semantic/SemanticPromptMatcher.java` + `SemanticMatching.java` | Opt-in LLM-judge fuzzy match + its off-by-default static gate |
| `llm/adversarial/AdversarialResponseLibrary.java` | Curated adversarial-response payloads for agent-resilience testing |
| `model/LlmChaosProfile.java` | Fault/chaos profile carried on `HttpLlmResponse` |
| `llm/LlmErrorBodies.java` | Pure helper mapping a `Provider` + error kind to the provider-correct chaos/quota error JSON body |
| `llm/TokenCounter.java` | Pure, deterministic **approximate** token-count estimator (char/word heuristic; not a real tokenizer); backs opt-in usage inference |
| `mock/action/http/HttpLlmResponseActionHandler.java` | Encodes LLM responses and applies chaos (error / truncation / malformed SSE) |
| `fixture/FixtureRedactor.java` | Masks sensitive headers and (optional) JSON body fields when recording fixtures |
| `llm/drift/StructuralShapeDiff.java` | Pure JSON shape diff (added/removed/type-changed paths) |
| `llm/drift/DriftDetector.java` + `DriftReport.java` | Replays a cassette against the live provider and reports structural drift, fail-closed |
| `llm/StubGenerationPromptBuilder.java` | Builds the LLM prompt for AI stub generation from unmatched requests |
| `llm/StubGenerationResult.java` | Result DTO for stub generation (suggestions, confidence, explanation) |
| `metrics/OtelMetricsExporter.java` | Optional OTLP metrics export bridging the Prometheus gauges (off by default) |
| `telemetry/GenAiSpanExporter.java` + `GenAiSpans.java` + `OtelEndpoints.java` | Optional explicit GenAI span export per served completion (off by default) |
| `llm/analysis/LlmOptimisationReport.java` | Structured JSON bundle — nested `Session`, `Totals`, `Call`, `ToolCall`, `Signal`, `Redaction` POJOs; schema version 1 |
| `llm/analysis/LlmOptimisationReportBuilder.java` | Builds the report from `FORWARDED_REQUEST` log entries via `ProviderCodecRegistry` + `LlmProviderSniffer` + `LlmPricing` + `FixtureRedactor` |
| `llm/analysis/OptimisationSignals.java` | Six deterministic signal detectors (see below); pure — no network, no LLM |
| `llm/analysis/LlmOptimisationBriefRenderer.java` | Renders an `LlmOptimisationReport` to a pre-framed Markdown brief |
| `llm/analysis/LlmOptimisationCsvRenderer.java` | Renders an `LlmOptimisationReport` to CSV (per-call rows + totals/verdict summary, RFC-4180 escaped) |
| `llm/analysis/LlmDatasetExporter.java` | Exports captured sessions as eval / fine-tune / promptfoo datasets (OpenAI-evals JSONL, chat fine-tune JSONL, promptfoo test-suite JSON); redacts via `FixtureRedactor` + credential masking |
| `llm/analysis/AgentRunDiff.java` | Prompt-level diff of two recorded runs — normalises via `PromptNormalizer`, LCS message alignment, tool-call add/remove, token/cost delta |
| `llm/analysis/LlmOptimisationReportService.java` | Façade: `build(pairs, filter)` + `renderBrief(result)` + `renderCsv(result)` + `renderDataset(result, format)` — used by both the REST handler and the MCP tool; also pushes the verdict snapshot to `Metrics` for the optimisation gauges |

## LLM Optimisation Export

MockServer can turn any captured LLM session into a structured **optimisation report** — either a copy-paste Markdown brief (pre-framed so a user can paste it into any LLM for cost-reduction advice) or a JSON bundle for programmatic use. The feature is export-only: MockServer never calls an LLM; every number is deterministic.

### Data flow

```mermaid
flowchart LR
    LOG["FORWARDED_REQUEST\nlog entries"] --> SVC["LlmOptimisationReportService\n.build(pairs, filter)"]
    SVC --> SNIFF["LlmProviderSniffer\n(detect provider)"]
    SVC --> CODEC["ProviderCodecRegistry\n.decode() → ParsedConversation"]
    SVC --> PRICE["LlmPricing\n(estimate USD cost)"]
    SVC --> REDACT["FixtureRedactor\n(strip secrets)"]
    SVC --> BUILD["LlmOptimisationReportBuilder\n(assemble report)"]
    BUILD --> SIG["OptimisationSignals\n(detect 9 signal types)"]
    BUILD --> RPT["LlmOptimisationReport\n(JSON bundle)"]
    RPT -->|"format=markdown"| RENDER["LlmOptimisationBriefRenderer\n(Markdown brief)"]
```

Only LLM traffic (where the sniffer recognises a provider) is included; non-LLM forwarded traffic is ignored.

### Per-call upstream latency

The per-call `latencyMs` is the measured upstream round-trip time. It is carried from the forward path to the report via an internal `x-mockserver-response-time-ms` header attached **only to the logged `FORWARDED_REQUEST` response clone** (never the response written to the real client) — the same convention as `x-mockserver-streamed` / `x-mockserver-chunk-delays-ms`. `HttpActionHandler` defines the constant (`HttpActionHandler.RESPONSE_TIME_HEADER`) and sets the header on every forward path's logged clone:

- **Non-streaming** — the value prefers the precise `Timing.getTotalTimeInMillis()` measured by `NettyHttpClient` (matching `recordForwardMetrics`), falling back to the wall-clock delta. This matters for the matched-`FORWARD` path, where `scheduler.submit(responseFuture, …)` only runs after the future has completed, so a naive nanos delta would read ~0.
- **Streaming** — the full-stream duration is computed from the captured forward-start nanos at stream completion (the upstream `Timing` only covers the response head).

`LlmOptimisationReportService` reads the header off the recorded response and passes it as `CapturedExchange.latencyMs`; the builder applies it when non-null and `>= 0` and aggregates `totals.totalLatencyMs`. A malformed/absent header degrades gracefully to a `0` latency for that call. The matched-`FORWARD` two-arg `writeForwardActionResponse(HttpResponse, …)` overload (pre-resolved responses, e.g. object-callback) has no upstream timing and leaves latency unset.

### Endpoint and MCP tool

**REST** — `GET /mockserver/llm/optimisationReport` (mockserver-netty control-plane, handled by `HttpRequestHandler.handleOptimisationReport`):

| Query parameter | Values | Default |
|-----------------|--------|---------|
| `format` | `json` \| `markdown` \| `csv` \| `openai-evals` \| `fine-tune` \| `promptfoo` | `json` |
| `session` | grouping key | all captured LLM traffic |
| `host` | upstream hostname | all hosts |
| `provider` | `OPENAI` \| `ANTHROPIC` \| `GEMINI` \| `BEDROCK` \| `AZURE_OPENAI` \| `OLLAMA` | all providers |

An unrecognised `format` returns `400` with `format must be one of: json, markdown, csv, openai-evals, fine-tune, promptfoo`. The `csv` format is served as `text/csv; charset=utf-8`; the JSONL dataset formats (`openai-evals`, `fine-tune`) as `application/x-ndjson; charset=utf-8`; and `promptfoo` as `application/json; charset=utf-8`.

CORS is enabled on this endpoint so the dashboard UI can call it even when the dashboard and control plane are on different origins.

**MCP tool** — `export_optimisation_report` (registered in `McpToolRegistry.registerExportOptimisationReport`), same parameters as the REST endpoint (`format` accepts `markdown` (default), `json`, `csv`, or the three dataset formats). Returns the brief text (`brief`), JSON bundle (`report`), CSV text (`csv`), or dataset text (`dataset`) as a tool result.

### Dataset export (eval / fine-tune / promptfoo)

`format=openai-evals|fine-tune|promptfoo` turns the captured sessions into datasets that eval / fine-tune tooling consumes, served by `LlmDatasetExporter` via `LlmOptimisationReportService.renderDataset(result, format)` (reusing the same `Result.getIncludedExchanges()` the report is built from). For each captured exchange the exporter decodes the **prompt messages** from the request (via the provider `ProviderCodec`) and the **assistant / ideal turn** from the response (via the provider `LlmClient.parseCompletionResponse`). Non-LLM exchanges and requests that decode to no messages are skipped (mirroring the report's inclusion rule).

| Format | Wire values | Shape |
|--------|-------------|-------|
| OpenAI-evals | `openai-evals`, `evals` | JSONL, one sample per line: `{"input":[{role,content}…],"ideal":"<assistant response>"}` |
| Chat fine-tune | `fine-tune`, `finetune` | JSONL, one conversation per line: `{"messages":[…prompt…,{"role":"assistant","content":…}]}` (assistant `tool_calls` included when present) |
| promptfoo | `promptfoo` | single JSON document `{"tests":[{"description":…,"vars":{"messages":[…]},"assert":[{"type":"equals","value":"<ideal>"}]}]}` |

**Redaction (on by default).** Every export runs each request and response through `FixtureRedactor` before emission (default sensitive headers / query params plus any configured `mockserver.fixtureBodyRedactFields`), and additionally masks credential shapes (OpenAI `sk-…`, AWS `AKIA…`, bearer, GitHub tokens) in free-text message content via `LlmOptimisationBriefRenderer.maskSecrets` — so a key a user pasted into a prompt never reaches the dataset. An empty capture yields an empty JSONL document (or `{"tests":[]}` for promptfoo). The dataset shapes are additive convenience exports, not frozen wire contracts.

### Agent-run diff

`PUT /mockserver/llm/diffRuns` (handled by `HttpRequestHandler.handleDiffRuns`, MCP tool `diff_agent_runs`) diffs two recorded agent runs at the prompt level. Each side is selected by the same `session`/`host`/`provider` filter the report uses; the endpoint builds an `LlmOptimisationReportService.Result` per side (reusing the report plumbing for provider detection + token/cost totals), then `AgentRunDiff` reconstructs each run's canonical conversation (`AgentRunAnalyzer.canonicalConversation`), normalises every message through `PromptNormalizer`, and aligns the two message sequences with a longest-common-subsequence diff (an adjacent REMOVED+ADDED of the same role is coalesced into a single `CHANGED`).

Request body:

```json
{
  "before": {"session": "host:api.openai.com", "provider": "OPENAI"},
  "after":  {"session": "host:api.openai.com", "provider": "OPENAI"},
  "normalization": { "collapseWhitespace": true, "sortJsonKeys": true }
}
```

Response: `{"promptChanged", "messageCountBefore/After", "messageDiffs":[{changeType, role, beforeText, afterText}], "toolCallsAdded", "toolCallsRemoved", "tokenDelta":{input/output tokens + cost before/after/delta}}`. `tokenDelta` is present only when both sides carry usage totals. The diff is deterministic and read-only — no LLM is called — and message text is masked for credential shapes. Prompt normalisation means cosmetic churn (whitespace, JSON key order, volatile ids/timestamps) is not reported as a change. The endpoint sits behind `controlPlaneRequestAuthenticated` like the other admin endpoints; no new configuration property is introduced.

### CSV format

`format=csv` is served by `LlmOptimisationCsvRenderer` (mirroring the structure of `LlmOptimisationBriefRenderer`). It is intended for spreadsheets and data pipelines: deterministic ordering, RFC-4180 escaping (a field containing a comma, double quote, CR, or LF is wrapped in double quotes with embedded quotes doubled). The output has two sections separated by a blank line:

1. **Per-call rows** — header `index,provider,model,input_tokens,output_tokens,cached_input_tokens,reasoning_tokens,estimated_cost_usd,cost_is_estimated,latency_ms,tool_calls,finish_reason`, one row per captured `Call`.
2. **Totals/verdict summary** — header `section,metric,value`, carrying the aggregate `Totals` (`call_count`, token totals, `estimated_cost_usd`, `total_latency_ms`, `tool_call_count`, `cache_hit_ratio`, `one_shot_rate`, `retry_call_count`) and the headline `Verdict` (`grade`, `total_estimated_saving_usd`, `total_wasted_input_tokens`, `saving_fraction_of_spend`, severity counts).

An empty report renders the per-call header row with no data rows, followed by the totals section with zeros — always a valid, header-bearing CSV. The CSV is additive to (not a substitute for) the JSON bundle, so unlike the JSON shape it is not a frozen wire contract.

### Optimisation verdict Prometheus gauges

Three single global Prometheus gauges expose the latest report's headline verdict/totals — `mock_server_llm_estimated_waste_usd` (from `verdict.totalEstimatedSavingUsd`), `mock_server_llm_cache_hit_ratio` (from `totals.cacheHitRatio`), and `mock_server_llm_one_shot_rate` (from `totals.oneShotRate`). They read a cached snapshot that `LlmOptimisationReportService.build(...)` pushes on every build, so they reflect the most-recently-built report (0 until one is built, and after a reset). See [metrics.md → LLM Optimisation Verdict Gauges](metrics.md#llm-optimisation-verdict-gauges) for the source-of-truth rationale and OTLP mirroring.

**Dashboard** — the LLM Optimise screen (`OptimiseView.tsx`, the **LLM Optimise** nav tab, positioned immediately after **Chaos**) fetches `format=json` for display and `format=markdown` for the "Copy optimisation brief" and "Download bundle" buttons.

### In-product verdict

`LlmOptimisationReport.Verdict` is always present on the report (an empty session yields grade `A`, zeros, and the rationale `"No optimisation opportunities detected."`). It is computed by `LlmOptimisationReportBuilder.buildVerdict` using per-call MAX attribution — the approach that ensures overlapping signals can never inflate the headline above actual spend.

**Attribution algorithm.** For each signal with `k` affected calls, divide its `estimatedSavingUsd` and `estimatedWastedInputTokens` by `k` to get per-call shares. For each call index `i`, take the **maximum** across all signals (not a sum) so the same wasted tokens cannot be counted twice. Sum those per-call maxima, then apply a final clamp: `totalEstimatedSavingUsd ≤ totals.estimatedCostUsd`.

**Grade thresholds.** `score = 100 × (1 − savingFraction)`, where `savingFraction = totalEstimatedSavingUsd / totals.estimatedCostUsd` (falls back to a token-wasted fraction when cost is zero):

| Score | Grade |
|-------|-------|
| ≥ 90 | A |
| ≥ 75 | B |
| ≥ 60 | C |
| ≥ 45 | D |
| < 45 | F |

**Severity floor:** if `highCount > 0` and the score would otherwise yield `A`, the grade is promoted to `B`.

**Rationale string** (templated, deterministic): no signals → `"No optimisation opportunities detected."`; otherwise → `"Grade C — an estimated 18% of spend ($1.42) is recoverable across 3 findings (1 high, 2 medium)."` Zero-count severities are omitted.

| Verdict field | Type | Notes |
|---|---|---|
| `grade` | String | `"A"` … `"F"` |
| `rationale` | String | Templated one-liner |
| `totalEstimatedSavingUsd` | double | Clamped ≤ `totals.estimatedCostUsd` |
| `totalWastedInputTokens` | long | Sum of per-call MAX wasted input tokens |
| `savingFractionOfSpend` | double | 0..1 |
| `costIsEstimated` | boolean | Mirrors `totals.costIsEstimated` |
| `highCount` / `mediumCount` / `lowCount` | int | Signal counts by severity |

The **dashboard** renders the verdict as a banner above the hero cards (`data-testid="optimise-verdict"`): the grade letter is colour-coded (A/B = success, C = warning, D/F = error), the headline shows `"Est. $X recoverable (Y% of spend)"`, and the rationale appears beneath it. The **"Copy verdict"** button next to "Copy optimisation brief" builds a compact plain-text verdict client-side from the already-loaded JSON (no additional fetch).

The **Markdown brief** opens with a `## Verdict` section (grade, rationale, recoverable estimate, cache-hit %, one-shot %) before the run summary.

### Session KPIs

Three new KPIs are added to `Totals` and reflected in the run summary and hero cards:

| KPI field | Formula | Dashboard label |
|---|---|---|
| `cacheHitRatio` | `inputTokens > 0 ? cachedInputTokens / inputTokens : 0` | Cache hit |
| `oneShotRate` | `callCount > 0 ? 1 − retryCallCount / callCount : 1.0` | One-shot |
| `retryCallCount` | Windowed retry count (window = 3): call `i` is a retry if it matches any of the prior 3 calls on path, model, messageCount, systemPromptFingerprint, and inputTokens. | — (used to derive `oneShotRate`) |

### Optimisation signals

`OptimisationSignals.detect(calls, providers, cachedInputTokens)` runs nine pure detectors. Results are sorted by **urgency descending** (urgency = `severityWeight × callShare`, where severityWeight is 1.0 / 0.6 / 0.3 for HIGH / MEDIUM / LOW, and callShare = affected calls / total calls); severity rank is the tie-breaker.

Each signal also carries a structured `Fix` object (all String fields, any nullable):

| `Fix` field | Content |
|---|---|
| `summary` | Imperative ≤6-word headline, e.g. `"Enable prompt caching"` |
| `action` | 1–2 sentence description of what to do |
| `configSnippet` | Copy-paste env / JSON snippet, or null |
| `exampleExpectation` | Example MockServer expectation JSON, or null |
| `docsUrl` | Absolute URL into the consumer docs, or null |

The legacy `recommendation` String on each signal is retained for back-compat. When `fix` is present the dashboard renders `fix.summary` (bold) + `fix.action` + copy-button for `configSnippet` / `exampleExpectation` + a docs link; when `fix` is null it falls back to `recommendation`.

| Signal id | Severity | Urgency | Trigger | Lever |
|-----------|----------|---------|---------|-------|
| `REPEATED_SYSTEM_PROMPT` | HIGH / MEDIUM | urgency-ranked | Same system-prompt fingerprint on ≥2 calls | Prompt caching / retrieval tool |
| `LARGE_STATIC_CONTEXT_RESENT` | HIGH | urgency-ranked | Context block ≥2,000 tokens resent on ≥2 calls | Prompt caching / retrieval tool |
| `DETERMINISTIC_TOOL_CALL` | MEDIUM | urgency-ranked | Same tool name + args fingerprint on ≥2 calls | Direct HTTP/MCP endpoint |
| `OVERSIZED_TOOL_RESULT` | MEDIUM | urgency-ranked | Tool result ≥1,000 tokens | Trim/summarise output |
| `OUTPUT_TOKEN_BLOAT` | LOW | urgency-ranked | Output ≥1,500 tokens or ≥3× session median | `max_tokens` / `response_format` |
| `DUPLICATE_CONSECUTIVE_CALL` | MEDIUM | urgency-ranked | Near-identical consecutive request shape | De-duplicate / cache / retry guard |
| `LOW_CACHE_HIT_RATE` | HIGH / MEDIUM | urgency-ranked | `cacheHitRatio < 0.5` AND a repeated cacheable system-prompt fingerprint exists AND `notYetCached > 0`. HIGH when notYetCached ≥ 2,000 tokens AND ratio < 0.2, else MEDIUM. `fix` is provider-aware: Anthropic gets a `cache_control` snippet; OpenAI/Gemini get prefix-caching advice. | Enable prompt caching |
| `MODEL_OVERSPEND` | LOW | urgency-ranked | ≥2 trivial calls (output < 256 tokens, no tool calls, no reasoning tokens) on a model whose blended rate is > 30% above the provider's cheapest model. `estimatedSavingUsd = affectedCost × savingFraction`; `estimatedWastedInputTokens = null`. | Switch to a smaller model |
| `UNUSED_TOOL_SCHEMA` | MEDIUM / LOW | urgency-ranked | Tools defined in the `tools` array but never invoked across the session, resent on ≥2 calls with `definedToolTokens > 0`. MEDIUM when total wasted tokens ≥ 1,000. Per-call waste = `definedToolTokens × unusedInCall / definedCount`. | Remove unused tools from `tools` |

Each signal carries `estimatedWastedInputTokens` (nullable) and `estimatedSavingUsd` (nullable, scaled from per-call cost via `LlmPricing`). Additionally, each signal carries `urgency` (double, 0..1) used for sorting.

#### `Call` additions for `UNUSED_TOOL_SCHEMA`

`buildCall` captures the tool names and schema size from the request body:

| `Call` field | Type | Source |
|---|---|---|
| `definedToolNames` | `List<String>` | Tool/function names from the request `tools` array (OpenAI: `function.name`; Anthropic/Gemini: `name`). Best-effort; empty on parse failure. |
| `definedToolTokens` | long | `charsToTokens(serialized tools node length)` (`~4 chars/token`). 0 when `tools` is absent. |

#### Anthropic top-level `system` field

The Anthropic provider codec (`AnthropicCodec`) maps the top-level `system` field in request bodies — both the simple `"system": "text"` string form and the `"system": [{"type":"text","text":"...","cache_control":{...}}]` blocks array form — to a leading `SYSTEM` message in the decoded conversation. This ensures `REPEATED_SYSTEM_PROMPT` and `LOW_CACHE_HIT_RATE` fire correctly on Anthropic traffic (including Bedrock). `cachedInputTokens` is populated from `usage.cache_read_input_tokens` in the Anthropic response by `AnthropicLlmClient`.

### Session grouping

Sessions group by isolation key (when LLM conversation expectations with `IsolationSource` are active — isolation key encoded in the scenario name as `__llm_conv_<uuid>__iso=<type>:<key>`) or by upstream `Host` header otherwise. The `groupingBasis` field in the report (`ISOLATION_KEY` \| `PROXY_HOST`) records which was used.

**v1 deferral.** Proxied (forwarded) LLM traffic — the optimisation use case — carries no matched-expectation scenario, so the builder groups it by upstream host (`PROXY_HOST`); server-side isolation-key grouping is deferred. The `session` filter in `LlmOptimisationReportService` therefore accepts the composite `host:<host>` key or the bare host, and the dashboard `OptimiseView` picker offers host-grouped sessions only (plus "All captured LLM traffic") so a selection always resolves rather than silently returning an empty report.

### Redaction

`FixtureRedactor` strips `Authorization`, `x-api-key`, `api-key`, `Cookie`, `Set-Cookie`, and `Proxy-Authorization` headers. JSON body fields named in `mockserver.fixtureBodyRedactFields` are also redacted. The `redaction` object in the report lists what was stripped.

### Configuration

- `mockserver.llmOptimisationMaxCalls` (int, default 200) — caps report size; only the most recent N calls are included when the session is larger. Signal thresholds are v1 constants in `OptimisationSignals` (no config properties beyond this bound).
- `mockserver.fixtureBodyRedactFields` — shared with `record_llm_fixtures`; controls body-field redaction.

### Markdown brief structure (frozen order)

1. Framing preamble (verbatim instructions for the downstream LLM)
2. `## Verdict` — grade, rationale, `"Est. $X (Y% of spend) / Z tokens recoverable"`, cache-hit %, one-shot %
3. Run summary (providers, models, token totals, estimated cost, latency, tool-call count, cache-hit rate, one-shot rate)
4. Per-call table (`# | model | in tok | out tok | cost | latency | tools | finish`)
5. Detected opportunities (urgency-ranked, each as a `###` section with title, detail, affected call indices, estimated saving, recommendation, `Fix:` line, and optional fenced `config` / `json` block for snippet / example expectation and docs link)
6. Conversations and tool definitions appendix (redacted messages + tool schemas per call)
