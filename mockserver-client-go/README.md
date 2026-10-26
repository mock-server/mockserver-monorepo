# MockServer Go Client

An idiomatic Go client for the [MockServer](https://www.mock-server.com) control-plane REST API.

Zero third-party dependencies — uses only the Go standard library (`net/http` + `encoding/json`).

## Installation

```bash
go get github.com/mock-server/mockserver-monorepo/mockserver-client-go/v9
```

## Quick Start

```go
package main

import (
    "log"

    mockserver "github.com/mock-server/mockserver-monorepo/mockserver-client-go/v9"
)

func main() {
    client := mockserver.New("localhost", 1080)

    // Create an expectation
    _, err := client.When(
        mockserver.Request().Method("GET").Path("/hello"),
    ).Respond(
        mockserver.Response().StatusCode(200).Body("world"),
    )
    if err != nil {
        log.Fatal(err)
    }

    // Verify the request was received at least once
    err = client.Verify(
        mockserver.Request().Path("/hello"),
        mockserver.AtLeast(1),
    )
    if err != nil {
        log.Fatal(err)
    }

    // Reset all expectations and logs
    if err := client.Reset(); err != nil {
        log.Fatal(err)
    }
}
```

## API

### Client Construction

```go
// By host and port
client := mockserver.New("localhost", 1080)

// From a full URL
client := mockserver.NewFromURL("http://mockserver.internal:1080")

// With options
client := mockserver.New("localhost", 1080,
    mockserver.WithContextPath("/myapp"),
    mockserver.WithTimeout(10 * time.Second),
)

// With control-plane JWT authentication: the token is sent as
// "Authorization: Bearer <token>" on every control-plane request and on the
// callback/breakpoint WebSocket upgrade
client := mockserver.New("localhost", 1080,
    mockserver.WithControlPlaneBearerToken(jwt),
)

// With control-plane Basic authentication (over TLS only): sent on the same
// requests and WebSocket; a bearer token, when also set, is sent instead
client := mockserver.New("localhost", 1080,
    mockserver.WithTLS(),
    mockserver.WithControlPlaneBasicAuthentication("admin", password),
)

// With control-plane mutual TLS: the client certificate is presented, and the
// CA trusted, on every control-plane request and on the callback/breakpoint
// WebSocket (an http.Client passed with WithHTTPClient supplies its own TLS settings)
client := mockserver.New("localhost", 1080,
    mockserver.WithTLS(),
    mockserver.WithCACertPEMPath("ca.pem"),
    mockserver.WithClientCertPEM("client-cert.pem", "client-key.pem"),
)
```

### Creating Expectations

```go
// Simple response
client.When(
    mockserver.Request().Method("GET").Path("/api/users"),
).Respond(
    mockserver.Response().StatusCode(200).JSONBody(`[{"id":1}]`),
)

// With times and TTL
client.When(
    mockserver.Request().Method("POST").Path("/api/orders"),
    mockserver.WithTimes(mockserver.Once()),
    mockserver.WithTimeToLive(mockserver.TTL("SECONDS", 60)),
).Respond(
    mockserver.Response().StatusCode(201).WithDelay("MILLISECONDS", 100),
)

// Forward to another host
client.When(
    mockserver.Request().Path("/proxy/.*"),
).Forward(
    mockserver.Forward().Host("backend.local").Port(8080).Scheme("HTTP"),
)

// With expectation ID for deduplication
client.When(
    mockserver.Request().Path("/stable"),
).WithID("my-stable-exp").Respond(
    mockserver.Response().StatusCode(200),
)
```

### Expectation Metadata

Attach free-form metadata (any JSON values) to an expectation, then retrieve or
clear expectations by it. Invalid selections (blank key, nil value, a clear that
selects nothing, paging on clear, negative offset/limit) return an error and
send nothing.

```go
client.When(mockserver.Request().Path("/checkout")).
    WithMetadataEntry("owner", "team-a").
    WithMetadataEntry("tags", []string{"smoke"}).
    Respond(mockserver.Response().StatusCode(200))

mine, _ := client.RetrieveActiveExpectationsByMetadata("owner", "team-a")
tagged, _ := client.RetrieveActiveExpectationsByMetadataKey("tags")
unused, _ := client.RetrieveUnmatchedExpectations()

// filter: metadata value, JSONPath, unmatched and paging (offset/limit)
page, _ := client.RetrieveActiveExpectationsWithFilter(nil,
    mockserver.NewExpectationFilter().WithMetadataJSONPath("$.tags[?(@ == 'smoke')]").WithOffset(0).WithLimit(20))
recorded, _ := client.RetrieveRecordedExpectationsPage(nil, 0, 50)
// replay changing responses in order, keep only some request headers, move large bodies into files
shaped, _ := client.RetrieveRecordedExpectationsWithOptions(nil, mockserver.NewRecordedExpectationsOptions().
    WithRepeatsAsScenarios(true).WithCaptureHeaders("X-Tenant").WithExtractBodiesLargerThan(1024))

// clear only the matching expectations (the event log is kept)
_ = client.ClearByMetadata("owner", "team-a")
_ = client.ClearByMetadataKey("tags")
_ = client.ClearUnmatchedExpectations()
_ = client.ClearWithFilter(mockserver.Request().Path("/checkout"),
    mockserver.NewExpectationFilter().WithMetadata("ticket", 4211))
```

A string value is matched as text; any other value is sent as compact JSON. Use
`mockserver.MetadataJSONNull` to select a JSON null value.

### Request Matchers

#### Header, query-string, cookie and path-parameter values

`HeaderMatcher`, `QueryStringParameterMatcher`, `CookieMatcher` and
`PathParameterMatcher` take `MatcherValue`s. Use `Literal` for an exact value
(even one starting with `!` or `?`) and `NotLiteral` to negate one.

`Before`, `After` and `EqualToDateTime` compare the value as a date-time instead.
The expected value is `now`, `now` with an offset such as `now -1 hours`, or an
ISO-8601, RFC-1123 or epoch date-time. Each option returns a new value:
`WithActualFormat`, `WithTruncateExpected`, `WithTruncateActual`,
`WithApplyTruncationLast`, `WithZone` and `WithNot`.

```go
client.When(
    mockserver.Request().Path("/orders").
        HeaderMatcher("X-Tag", mockserver.Literal("!urgent")).
        HeaderMatcher("X-Issued-At", mockserver.After("now -1 hours")).
        HeaderMatcher("If-Modified-Since",
            mockserver.Before("2026-10-21T00:00:00Z").WithActualFormat("rfc1123")).
        QueryStringParameterMatcher("from",
            mockserver.After("2026-01-01").WithActualFormat("dd/MM/yyyy").WithZone("Europe/London")),
).Respond(
    mockserver.Response().StatusCode(200),
)
```

A plain string such as `"before:2026-01-01"` is still an ordinary literal value.

A JSONPath or XPath body matcher can compare the value it selects as a date-time
too; the body matches when any selected value (or node text) satisfies it:

```go
request := mockserver.Request().Path("/orders").Build()
request.Body = mockserver.JSONPathBody("$.order.created").WithDateTime(mockserver.After("now -1 days"))
```

#### JWT matcher

Match a decoded bearer-token JWT by its claims, issuer, audience, and algorithm.
Claim and string values follow MockServer's string-matcher semantics: an exact
value, a regular expression, or a `!`-prefixed negated match.

```go
client.When(
    mockserver.Request().Method("GET").Path("/secure").
        Jwt(mockserver.NewJwt().
            Claim("role", "^admin.*$").      // regex claim
            Claim("scope", "!internal").     // negated claim
            WithIssuer("https://issuer.example.com").
            WithAudience("api").
            WithAlgorithm("RS256")),
).Respond(
    mockserver.Response().StatusCode(200),
)
```

By default the token is read from the `Authorization` header with the `Bearer`
scheme; override with `WithHeader(...)` / `WithScheme(...)`.

#### Client IP matcher

Match requests by the client IP address or CIDR range, IPv4 or IPv6. The client address is the TCP peer of the connection, not `X-Forwarded-For`; prefix with `!` to match every other address.

```go
req := mockserver.Request().Path("/internal").ClientIP("10.0.0.0/8")
```

#### Any-of (logical OR) matcher

`AnyOfRequest(...)` (or `RequestBuilder.AnyOf(...)`) matches a request when any one of its branches does, tried in order; set `Not` to match requests that match none of them. A branch is any `*HttpRequest`, including an OpenAPI matcher, a conditional or another anyOf. `ExplainedExpectation.AnyOf` says which branch came closest when none matches.

```go
client.Upsert(mockserver.Expectation{
    HttpRequest: mockserver.AnyOfRequest(
        &mockserver.HttpRequest{Method: "GET", Path: "/pets"},
        &mockserver.HttpRequest{Method: "GET", Path: "/animals"},
    ),
    HttpResponse: &mockserver.HttpResponse{Body: "pets or animals"},
})
```

#### allOf body matcher

Require every nested body matcher to match with `AllOfBody(...)` (or the standalone
`AllOf(...)` constructor). Elements may be any body matcher — `JSONPathBody`,
`RegexBody`, a `*TypedBody`, or a plain string.

```go
client.When(
    mockserver.Request().Method("POST").Path("/orders").
        AllOfBody(
            mockserver.JSONPathBody("$.items[?(@.qty > 0)]"),
            mockserver.RegexBody(`.*"currency":"GBP".*`),
        ),
).Respond(
    mockserver.Response().StatusCode(201),
)
```

#### Conditional (if/then/else) matcher

When the `If` request matches, the `Then` request must match too; otherwise
`Else` must (with no `Else`, the matcher matches whenever `If` does not). Each
branch can be a request matcher, an OpenAPI matcher (`SpecUrlOrPayload`) or
another conditional. An `httpRequest` holding `if` read back from the server
fills `If`, `Then` and `Else`.

```go
client.When(
    mockserver.Request().
        If(mockserver.Request().Method("POST")).
        Then(mockserver.Request().Path("/orders")).
        Else(mockserver.Request().Method("GET")),
).Respond(
    mockserver.Response().StatusCode(200),
)

// or, for branches the builder cannot express (e.g. a JSON schema body):
client.Upsert(mockserver.Expectation{
    HttpRequest: mockserver.ConditionalRequest(
        &mockserver.HttpRequest{Method: "POST"},
        &mockserver.HttpRequest{Body: mockserver.JSONSchemaBody(`{"type": "object", "required": ["orderId"]}`)},
        nil,
    ),
    HttpResponse: &mockserver.HttpResponse{StatusCode: 200},
})
```

### Verification

```go
// At least N times
client.Verify(mockserver.Request().Path("/hello"), mockserver.AtLeast(1))

// At most N times
client.Verify(mockserver.Request().Path("/hello"), mockserver.AtMost(5))

// Exactly N times
client.Verify(mockserver.Request().Path("/hello"), mockserver.ExactlyTimes(3))

// Between min and max
client.Verify(mockserver.Request().Path("/hello"), mockserver.Between(1, 5))

// Sequence verification
client.VerifySequence(
    mockserver.Request().Path("/login"),
    mockserver.Request().Path("/dashboard"),
)
```

### Retrieval

```go
// Active expectations
expectations, _ := client.RetrieveActiveExpectations(nil)

// Recorded requests (optionally filtered)
requests, _ := client.RetrieveRecordedRequests(mockserver.Request().Path("/api/.*"))

// Recorded expectations
recorded, _ := client.RetrieveRecordedExpectations(nil)

// Active expectations as generated MockServer SDK setup code
// (format: FormatJava, FormatJavaScript, FormatPython, FormatGo,
//  FormatCSharp, FormatRuby, FormatRust or FormatPHP)
code, _ := client.RetrieveExpectationsAsCode(nil, mockserver.FormatJava)

// Recorded request/response pairs as generated SDK setup code
recordedCode, _ := client.RetrieveRecordedExpectationsAsCode(nil, mockserver.FormatGo)

// Log messages
logs, _ := client.RetrieveLogMessages(nil)
```

### Control

```go
// Clear specific expectations
client.Clear(mockserver.Request().Path("/old"), mockserver.ClearExpectations)

// Clear by expectation ID
client.ClearByID("exp-123", mockserver.ClearAll)

// Reset everything
client.Reset()

// Check server status
status, _ := client.Status()
fmt.Println(status.Ports)

// Check if running
if client.IsRunning() { ... }

// Bind additional ports
ports, _ := client.Bind(1081, 1082)
```

### Matching Diagnostics

Find out why a request was served by the wrong expectation, or by none. Each call is read-only on the server: it consumes no `Times` and moves no scenario.

```go
// Which expectation would serve a recorded request now, and why each one
// ahead of it was passed over (correlation ID from its log entries)
explanation, err := client.Explain("abc-123-def-456")
if err == nil {
    for _, skipped := range explanation.SkippedExpectations {
        fmt.Println(skipped.ExpectationID, skipped.Reason, skipped.Explanation)
    }
}

// Compare a request with every active expectation, field by field
report, err := client.DebugMismatch(mockserver.Request().Method("GET").Path("/api/users"))
if err == nil && report.ClosestMatch != nil {
    fmt.Println("closest:", report.ClosestMatch.ExpectationID)
}

// Recent requests that matched nothing, closest expectations first, each
// with a suggested JSON patch that would make it match
unmatched, err := client.ExplainUnmatched(&mockserver.ExplainUnmatchedOptions{Limit: 5, SuggestFixes: true})
if err == nil {
    for _, r := range unmatched.UnmatchedRequests {
        fmt.Println(r.Method, r.Path, len(r.ClosestExpectations))
    }
}
```

`Explain` returns an error for an empty correlation ID (without calling the server) and for one that is no longer in the event log. A `Reason` or `Outcome` sent by a newer server is kept as sent.

### Interactive Breakpoints

Register breakpoint matchers to pause forwarded/proxied traffic at REQUEST, RESPONSE, RESPONSE_STREAM, or INBOUND_STREAM phases. A callback WebSocket connection is opened automatically to receive paused items.

```go
// REQUEST-only breakpoint
id, _ := client.AddRequestBreakpoint(
    mockserver.Request().Path("/api/.*"),
    func(req map[string]interface{}) interface{} {
        // Inspect/modify the request, or return a response to abort
        return req // continue with original
    },
)

// REQUEST + RESPONSE breakpoint
id, _ := client.AddRequestResponseBreakpoint(
    mockserver.Request().Path("/api/.*"),
    func(req map[string]interface{}) interface{} { return req },
    func(req, resp map[string]interface{}) map[string]interface{} { return resp },
)

// Streaming breakpoint (RESPONSE_STREAM / INBOUND_STREAM)
id, _ := client.AddStreamBreakpoint(
    mockserver.Request().Path("/stream/.*"),
    []mockserver.BreakpointPhase{mockserver.PhaseResponseStream},
    func(frame *mockserver.PausedStreamFrame) *mockserver.StreamFrameDecision {
        d := mockserver.ContinueFrame(frame.CorrelationID)
        return &d
    },
)

// List, remove, clear matchers
list, _ := client.ListBreakpointMatchers()
client.RemoveBreakpointMatcher(id)
client.ClearBreakpointMatchers()
client.CloseBreakpointWebSocket()
```

**Stream frame decisions:** `ContinueFrame`, `ModifyFrame`, `DropFrame`, `InjectFrame`, `CloseFrame`.

### LLM Mocking

Mock LLM provider APIs (OpenAI, Anthropic, Gemini, Bedrock, Azure OpenAI, Ollama) with fluent builders. The action is carried in the `httpLlmResponse` field of an expectation; MockServer re-encodes the provider-agnostic `Completion` into the wire shape of the configured provider. Use the `Provider*` and `Role*` constants.

```go
// Single completion mock
_, _ = mockserver.LlmMock("/v1/chat/completions").
    WithProvider(mockserver.ProviderOpenAI).
    WithModel("gpt-4o").
    RespondingWithCompletion(
        mockserver.NewCompletion().
            WithText("Hello!").
            WithStopReason("stop").
            WithUsage(mockserver.NewUsage().WithInputTokens(10).WithOutputTokens(5)),
    ).
    ApplyTo(client)

// Embedding mock
_, _ = mockserver.LlmMock("/v1/embeddings").
    WithProvider(mockserver.ProviderOpenAI).
    RespondingWithEmbedding(
        mockserver.NewEmbedding().WithDimensions(1536).WithDeterministicFromInput(true),
    ).
    ApplyTo(client)

// Multi-turn conversation (scenario state advancement, optional per-session isolation)
_, _ = mockserver.Conversation().
    WithPath("/v1/chat/completions").
    WithProvider(mockserver.ProviderAnthropic).
    WithModel("claude-3-5-sonnet").
    IsolateBy(mockserver.IsolateByHeader("x-session-id")).
    Turn().
        WhenLatestMessageContains("hello").
        RespondingWith(mockserver.NewCompletion().WithText("hi there")).
    Turn().
        WhenTurnIndex(1).
        RespondingWith(mockserver.NewCompletion().WithText("bye")).
    ApplyTo(client)

// Provider failover: N failures, then success. Consecutive identical failures
// are coalesced; omitted error bodies default per status code.
_, _ = mockserver.LlmFailover().
    WithPath("/v1/chat/completions").
    WithProvider(mockserver.ProviderOpenAI).
    WithModel("gpt-4o").
    FailWithCount(429, 2). // two rate-limit attempts (default 429 body)
    FailWith(503).         // one service-unavailable attempt
    ThenRespondWith(mockserver.NewCompletion().WithText("recovered")).
    ApplyTo(client)
```

`FailWithBody(statusCode, body)` supplies a custom error body. Each builder also has a `Build()` method that returns `[]Expectation` without registering them.

### MCP Mocking

Mock a Model Context Protocol (MCP) server speaking JSON-RPC 2.0 over the Streamable HTTP transport. `McpMock` generates the full set of expectations (`initialize`, `ping`, `notifications/initialized`, plus `tools`, `resources`, and `prompts` list/call handlers). Tool/resource/prompt definitions are configured via closures.

```go
_, _ = mockserver.McpMock("/mcp").
    WithServerName("WeatherServer").
    WithTool("get_weather", func(t *mockserver.McpToolBuilder) {
        t.WithDescription("Get the weather for a city").
            WithInputSchema(`{"type":"object","properties":{"city":{"type":"string"}}}`).
            RespondingWith("72F and sunny", false)
    }).
    WithResource("file:///config.json", func(r *mockserver.McpResourceBuilder) {
        r.WithName("config").WithMimeType("application/json").WithContent(`{"debug":true}`)
    }).
    WithPrompt("greeting", func(p *mockserver.McpPromptBuilder) {
        p.WithDescription("A greeting").
            WithArgument("name", "the name", true).
            RespondingWith(mockserver.RoleUser, "Hello {{name}}")
    }).
    ApplyTo(client)
```

Responses use a Velocity template that echoes the inbound JSON-RPC id back via `$!{request.jsonRpcRawId}`. Tool input schemas are validated (invalid JSON panics) and compacted. `McpMock("")` defaults the path to `/mcp`.

### A2A Mocking

Mock an A2A (Agent2Agent) agent speaking JSON-RPC 2.0 over HTTP. `A2aMock` generates a GET agent-card discovery document plus the task methods (`tasks/send`, `tasks/get`, `tasks/cancel`), with optional SSE streaming and push-notification delivery. Skills are advertised on the agent card; custom `tasks/send` handlers match the inbound message text by regex.

```go
_, _ = mockserver.A2aMock("/a2a").
    WithAgentName("WeatherAgent").
    WithAgentDescription("Provides weather forecasts").
    WithSkill("forecast", func(s *mockserver.A2aSkillBuilder) {
        s.WithName("Forecast").
            WithDescription("Provides weather forecasts").
            WithTag("weather").
            WithExample("What is the weather in London?")
    }).
    OnTaskSend("(?i).*london.*", "Sunny in London", false).
    WithStreaming().
    WithPushNotifications("http://localhost:1234/a2a/callback").
    ApplyTo(client)
```

The agent card is served at `/.well-known/agent.json` (override via `WithAgentCardPath`) and reports `capabilities.streaming` / `capabilities.pushNotifications`. With `WithStreaming`, the streaming method (default `message/stream`, override via `WithStreamingMethod`) returns an SSE stream of status/artifact update events wrapped in JSON-RPC envelopes. With `WithPushNotifications`, each `tasks/send` both returns the JSON-RPC task response and POSTs the completed task to the webhook URL. Task responses use a Velocity template that echoes the inbound JSON-RPC id via `$!{request.jsonRpcRawId}`. `A2aMock("")` defaults the path to `/a2a`.

## Start / Launch MockServer

The Go client can download and launch a local MockServer instance directly -- no Java installation and no Docker required. The launcher downloads a self-contained platform bundle (`mockserver-<version>-<os>-<arch>`) from the GitHub Release, verifies its SHA-256, caches it per-user, and starts it.

### Quick start

```go
package main

import (
    "fmt"
    "log"

    mockserver "github.com/mock-server/mockserver-monorepo/mockserver-client-go/v9"
)

func main() {
    handle, err := mockserver.StartServer(1080, "", nil)
    if err != nil {
        log.Fatal(err)
    }
    defer handle.Stop()

    fmt.Printf("MockServer running on port %d\n", handle.Port)
    // ... use MockServer ...
}
```

### Just ensure the binary is present

```go
launcherPath, err := mockserver.EnsureBinary(mockserver.Version, nil)
if err != nil {
    log.Fatal(err)
}
fmt.Println("Launcher at:", launcherPath)
```

### Specify a version

```go
handle, err := mockserver.StartServer(1080, "9.2.0", nil)
```

### API reference

| Function / Type | Description |
|---|---|
| `EnsureBinary(version string, opts *EnsureOptions) (string, error)` | Download, verify, cache, and return the launcher path. |
| `StartServer(port int, version string, opts *EnsureOptions) (*ServerHandle, error)` | Ensure the binary and start MockServer. Pass `""` for version to use the default. |
| `ServerHandle` | Handle to the running process. Fields: `Port`, `Launcher`. Methods: `Stop() error`, `Wait() error`. |
| `Version` | The default MockServer version this client targets (from embedded `VERSION` file). |
| `ResolvePlatform() (PlatformInfo, error)` | Detect the current OS and architecture. |
| `CacheDir() string` | Return the binary cache directory path. |

### Supported platforms

| OS | Architecture |
|---|---|
| Linux | x86_64, aarch64 |
| macOS (darwin) | x86_64, aarch64 |
| Windows | x86_64, aarch64 |

### Environment variables

| Variable | Purpose |
|---|---|
| `MOCKSERVER_BINARY_BASE_URL` | Mirror host for the release assets (corporate / air-gapped networks) |
| `MOCKSERVER_BINARY_CACHE` | Override the cache directory (default: `~/.cache/mockserver/binaries` on Unix) |
| `MOCKSERVER_SKIP_BINARY_DOWNLOAD` | Fail instead of downloading (use with a pre-seeded cache in CI) |

### Version

By default the launcher downloads the MockServer version embedded in the `VERSION` file at build time. The release pipeline updates this file automatically. Pass an explicit version string to `StartServer` or `EnsureBinary` to override.

## Using in tests

`MockServerT` registers a `t.Cleanup` that resets the server when the test (and
its subtests) finish, so recorded requests, expectations and logs do not leak
between tests:

```go
func TestSomething(t *testing.T) {
    client := mockserver.MockServerT(t, mockserver.New("localhost", 1080))
    // ... register expectations, make requests ...
    // client.Reset() runs automatically when the test ends.
}

// or construct + register in one call:
func TestAnother(t *testing.T) {
    client := mockserver.NewMockServerT(t, "localhost", 1080)
    // ...
}
```

## Build & Test

```bash
go test ./...
go vet ./...
```

Integration tests require `MOCKSERVER_URL` environment variable:

```bash
MOCKSERVER_URL=http://localhost:1080 go test ./... -v
```

## Requirements

- Go 1.21 or later
- `github.com/gorilla/websocket` (for breakpoint callback WebSocket)
