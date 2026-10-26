# mockserver-client

An idiomatic Rust client for [MockServer](https://www.mock-server.com)'s control-plane REST API.

## Installation

Add to your `Cargo.toml`:

```toml
[dev-dependencies]
mockserver-client = "7.3"
```

## Quick Start

```rust
use mockserver_client::{ClientBuilder, HttpRequest, HttpResponse, VerificationTimes};

fn main() -> mockserver_client::Result<()> {
    let client = ClientBuilder::new("localhost", 1080).build()?;

    // Create an expectation
    client.when(HttpRequest::new().method("GET").path("/hello"))
        .respond(HttpResponse::new().status_code(200).body("world"))?;

    // Verify the request was received
    client.verify(
        HttpRequest::new().path("/hello"),
        VerificationTimes::at_least(1),
    )?;

    // Reset all expectations
    client.reset()?;
    Ok(())
}
```

## LLM, MCP, and A2A mocking

The [`llm`](src/llm.rs), [`mcp`](src/mcp.rs), and [`a2a`](src/a2a.rs) modules provide
fluent builders that produce the same expectation wire JSON as the Java, Node, and
Python clients, so a mock scripted from Rust behaves identically to one scripted from
any other client.

### LLM completions, embeddings, conversations, and failover

```rust
use mockserver_client::llm::{
    completion, conversation, embedding, header, llm_failover, llm_mock, turn, usage, Provider,
};

// A single chat-completion mock (action carried in `httpLlmResponse`).
llm_mock("/v1/chat/completions")
    .with_provider(Provider::OPENAI)
    .with_model("gpt-4o")
    .responding_with(completion().with_text("Hello!").with_usage(usage().with_output_tokens(3)))
    .apply_to(&client)?;

// An embedding mock (clears any completion).
llm_mock("/v1/embeddings")
    .with_provider(Provider::OPENAI)
    .responding_with(embedding().with_dimensions(1536).with_deterministic_from_input(true))
    .apply_to(&client)?;

// A multi-turn conversation using MockServer scenario-state advancement.
conversation()
    .with_path("/v1/chat/completions")
    .with_provider(Provider::ANTHROPIC)
    .isolate_by(header("x-session-id")) // optional per-session isolation
    .turn(turn().responding_with(completion().with_text("Hi, how can I help?")))
    .turn(
        turn()
            .when_latest_message_contains("weather")
            .responding_with(completion().with_text("It's sunny.")),
    )
    .apply_to(&client)?;

// Fail N times (with default provider-shaped error bodies), then succeed.
llm_failover()
    .with_path("/v1/chat/completions")
    .with_provider(Provider::OPENAI)
    .fail_with_count(429, 2) // coalesced into one expectation with times = 2
    .fail_with(500)
    .then_respond_with(completion().with_text("Recovered"))
    .apply_to(&client)?;
```

`provider` is serialised UPPERCASE (`OPENAI`, `ANTHROPIC`, ...). Conversation turns
advance through scenario states `Started -> turn_1 -> ... -> __done`. Each builder also
has a `build()` method returning the raw `serde_json::Value`(s) if you want to inspect
or submit them yourself via `client.upsert_raw(...)`.

### MCP (Model Context Protocol) servers

`mcp_mock` builds the set of expectations needed to emulate a Streamable-HTTP MCP
server speaking JSON-RPC 2.0 (`initialize`, `ping`, `notifications/initialized`,
`tools/list`, `tools/call`, `resources/list`, `resources/read`, `prompts/list`,
`prompts/get`). Responses use Velocity templates that echo the inbound JSON-RPC id.

```rust
use mockserver_client::mcp::mcp_mock;
use mockserver_client::llm::Role;

mcp_mock("/mcp")
    .with_server_name("MyServer")
    .with_tool("get_weather")
        .with_description("Get the weather for a city")
        .with_input_schema(r#"{"type":"object","properties":{"city":{"type":"string"}}}"#)
        .responding_with("72F and sunny", false)
        .and()
    .with_resource("file:///config.json")
        .with_name("config")
        .with_content(r#"{"debug":true}"#)
        .and()
    .with_prompt("greeting")
        .with_argument("name", Some("who to greet".to_string()), true)
        .responding_with(Role::ASSISTANT, "Hello there")
        .and()
    .apply_to(&client)?;
```

Use `mcp_mock_default()` for the default `/mcp` path. `build()` returns the ordered
`Vec<serde_json::Value>` of expectations.

### A2A (Agent-to-Agent) agents

`a2a_mock` builds the expectations needed to emulate an A2A agent: a discoverable
agent card on `GET /.well-known/agent.json` plus a JSON-RPC 2.0 task endpoint
(`tasks/send`, `tasks/get`, `tasks/cancel`). Task responses use Velocity templates
that echo the inbound JSON-RPC id. Optional `with_streaming()` advertises and mocks
SSE streaming (status/artifact updates), and `with_push_notifications(url)` advertises
push notifications, echoes `tasks/pushNotificationConfig/set`, and POSTs each completed
task to the webhook via an override-forwarded request while still returning the
JSON-RPC response to the caller.

```rust
use mockserver_client::a2a::a2a_mock;

a2a_mock("/agent")
    .with_agent_name("TranslatorAgent")
    .with_agent_version("2.0.0")
    .with_skill("translate")
        .with_name("Translation")
        .with_description("Translates text between languages")
        .with_tag("i18n")
        .with_example("Translate hello to French")
        .and()
    .with_default_task_response("Default done")
    .on_task_send()
        .matching_message("translate.*")
        .responding_with("Bonjour", false) // second arg = is_error
        .and()
    .with_streaming()                                   // optional SSE streaming
    .with_push_notifications("http://localhost:1234/cb") // optional webhook delivery
    .apply_to(&client)?;
```

Use `a2a_mock_default()` for the default `/a2a` path. `build()` returns the ordered
`Vec<serde_json::Value>` of expectations (`try_build()` is the fallible variant that
surfaces an invalid push-notification webhook URL instead of panicking).

## Features

- **Fluent builder API** — `client.when(request).respond(response)`
- **Response, Forward, and Error actions** — full MVP control-plane coverage
- **Verification** — `verify` (count-based) and `verify_sequence` (order-based)
- **Retrieve** — recorded requests, active expectations, recorded expectations, logs
- **Clear / Reset** — by request matcher, by expectation ID, or full reset
- **Status / Bind** — query ports, bind additional ports
- **Matching diagnostics** — `explain`, `debug_mismatch` and `explain_unmatched` return typed reports
- **LLM, MCP, and A2A builders** — fluent `llm` / `mcp` / `a2a` mock builders, wire-identical to the other clients
- **Blocking (synchronous)** — uses `reqwest` blocking client; no async runtime needed
- **TLS support** — optional HTTPS with configurable certificate verification; the breakpoint/callback WebSocket then connects over `wss` with the same TLS settings
- **Secured control plane** — `control_plane_bearer_token(..)` (JWT auth, also sent on the breakpoint/callback WebSocket upgrade), `control_plane_basic_authentication(user, password)` (HTTP Basic, over TLS only; a bearer token wins), `ca_cert_pem_path(..)` / `ca_cert_pem(..)` (trust a server CA), and `client_cert_pem(cert, key)` (mTLS)

## API Overview

```rust
use mockserver_client::*;

let client = ClientBuilder::new("localhost", 1080).build().unwrap();

// Fluent expectation creation
client.when(HttpRequest::new().method("POST").path("/api/users"))
    .times(Times::exactly(3))
    .respond(HttpResponse::new()
        .status_code(201)
        .header("Location", "/api/users/1")
        .body(r#"{"id": 1}"#))?;

// Forward action
client.when(HttpRequest::new().path("/proxy"))
    .forward(HttpForward::new("backend.local", 8080).scheme("HTTP"))?;

// Verify
client.verify(
    HttpRequest::new().method("POST").path("/api/users"),
    VerificationTimes::between(1, 3),
)?;

// Verify sequence
client.verify_sequence(vec![
    HttpRequest::new().path("/first"),
    HttpRequest::new().path("/second"),
])?;

// Clear by request matcher
client.clear(
    Some(&HttpRequest::new().path("/api/users")),
    Some(ClearType::Expectations),
)?;

// Clear by ID
client.clear_by_id("my-expectation-id", None)?;

// Retrieve recorded requests
let requests = client.retrieve_recorded_requests(None)?;

// Retrieve active expectations
let expectations = client.retrieve_active_expectations(None)?;

// Server status
let ports = client.status()?;
println!("Listening on: {:?}", ports.ports);

// Reset everything
client.reset()?;
```

### Header, query-string, cookie and path parameter value matchers

Plain values passed to `header`, `query_param`, `cookie` and `path_param` are
exact-or-regex strings, and a leading `!` negates them. The `*_matcher` builders
take a `MatcherValue` instead. Use it to match a value that really starts with
`!` or `?`, or to compare the value as a date-time:

```rust
use mockserver_client::*;

client.when(
    HttpRequest::new()
        .path("/date-time")
        .header_matcher("X-Tag", MatcherValue::literal("!foo"))       // X-Tag IS "!foo"
        .header_matcher("X-Env", MatcherValue::not_literal("dev"))    // X-Env is NOT "dev"
        .header_matcher("X-Issued-At", MatcherValue::after("now -1 hours"))
        .header_matcher(
            "If-Modified-Since",
            MatcherValue::before("2026-10-21T00:00:00Z").with_actual_format("rfc1123"),
        )
        .query_param_matcher(
            "from",
            MatcherValue::after("2026-01-01")
                .with_actual_format("dd/MM/yyyy")
                .with_zone("Europe/London"),
        )
        .cookie_matcher(
            "seen",
            MatcherValue::equal_to_date_time("now")
                .with_truncate_expected("startOfDay")
                .with_truncate_actual("startOfDay"),
        ),
)
.respond(HttpResponse::new().status_code(200))?;
```

`before`, `after` and `equal_to_date_time` accept `now`, `now <offset>` (for
example `now -1 hours`), or an ISO-8601, RFC-1123 or epoch date-time. The
optional `with_actual_format`, `with_truncate_expected`, `with_truncate_actual`,
`with_apply_truncation_last`, `with_zone` and `with_not` each return a new
value. A plain string such as `"before:2026-01-01"` is still matched literally.

A `JSON_PATH` or `XPATH` body can compare the value it selects as a date-time
with `with_date_time`; the body matches when any selected value (or node text)
satisfies it:

```rust
use mockserver_client::{Body, MatcherValue};

let body = Body::json_path("$.order.created").with_date_time(MatcherValue::after("now -1 days"));
```

### Expectation metadata, unused mocks and paging

Label expectations with free-form `metadata` (any JSON values, including
`null`), then retrieve or clear them by label, find mocks that have never
matched, or page through long lists:

```rust
use mockserver_client::*;
use serde_json::json;

client.upsert(&[Expectation::new(HttpRequest::new().path("/checkout"))
    .respond(HttpResponse::new().status_code(200))
    .metadata_entry("owner", "team-a")
    .metadata_entry("tags", json!(["smoke"]))])?;

let mine = client.retrieve_active_expectations_by_metadata("owner", "team-a")?;
let unused = client.retrieve_unmatched_expectations()?;
let page = client.retrieve_active_expectations_with_filter(
    None,
    &ExpectationFilter::new().metadata_key("owner").offset(0).limit(20),
)?;
// replay changing responses in order, keep only some request headers, move large bodies into files
let shaped = client.retrieve_recorded_expectations_with_options(
    None,
    &RecordedExpectationsOptions::new()
        .repeats_as_scenarios(true)
        .capture_headers(["X-Tenant"])
        .extract_bodies_larger_than(1024),
)?;

client.clear_by_metadata("owner", "team-a")?;
client.clear_unmatched_expectations()?;
```

A string value is matched as text and any other value as JSON (`42`, `true`,
`serde_json::Value::Null`). A clear must select something and cannot page; a
blank key or JSONPath is rejected before any request is sent.

### JWT and composite (ALL_OF) body matchers

Match requests by the claims in a JSON Web Token, and combine several body
matchers so that **all** of them must match.

```rust
use mockserver_client::*;

// JWT matcher — claim values are exact-or-regex strings; a leading `!` negates.
client.when(
    HttpRequest::new()
        .method("GET")
        .path("/secure")
        .jwt(
            Jwt::new()
                .claim("sub", "user-123")
                .claim("role", "!admin")            // NOT admin
                .claim("email", "^.+@example.com$") // regex
                .issuer("https://issuer.example.com")
                .audience("my-api")
                .algorithm("RS256")
                .header("authorization")            // optional (defaults to authorization)
                .scheme("Bearer"),                  // optional scheme prefix
        ),
)
.respond(HttpResponse::new().status_code(200))?;

// ALL_OF body — every nested matcher must match.
client.when(
    HttpRequest::new()
        .method("POST")
        .path("/orders")
        .body_value(Body::all_of(vec![
            Body::json_path("$.name"),
            Body::regex(".*active.*"),
        ])),
)
.respond(HttpResponse::new().status_code(201))?;
```

The JWT matcher serialises under the request's `"jwt"` key, and the composite
body serialises to `{"type":"ALL_OF","bodyAllOf":[ ... ]}`.

### Client IP matcher

Match requests by the client IP address or CIDR range, IPv4 or IPv6. The client address is the TCP peer of the connection, not `X-Forwarded-For`; prefix with `!` to match every other address. It serialises under the request's `"clientIp"` key and is held in `extra`, so adding it changed no public field.

```rust
let request = HttpRequest::new().path("/internal").client_ip("10.0.0.0/8");
assert_eq!(request.client_ip_value(), Some("10.0.0.0/8"));
```

### Any-of (logical OR) matcher

`HttpRequest::any_of([...])` matches a request when any one of its branches does, tried in order; `.not(true)` matches requests that match none of them. A branch may be an OpenAPI matcher, a conditional or another anyOf. It serialises to `{"anyOf": [...]}` and is held in `extra`, so adding it changed no public field; `any_of_value()` reads the branches back.

```rust
client.when(HttpRequest::any_of([
    HttpRequest::new().method("GET").path("/pets"),
    HttpRequest::new().method("GET").path("/animals"),
])).respond(HttpResponse::new().body("pets or animals")).unwrap();
```

### Binary, JSON and file response bodies

`.body(..)` sends a plain string body. For any other body shape, use
`binary_body`, `json_body`, `file_body`, or `body_value` with a `Body`:

```rust
use mockserver_client::*;

// BINARY: the bytes are base64-encoded on the wire and served back unchanged.
client.when(HttpRequest::new().path("/logo.png"))
    .respond(HttpResponse::new()
        .status_code(200)
        .binary_body(std::fs::read("logo.png").unwrap(), Some("image/png".to_string())))?;

// Any other body shape, e.g. XML with an explicit content type.
client.when(HttpRequest::new().path("/feed"))
    .respond(HttpResponse::new()
        .body_value(Body::xml("<feed/>").with_content_type("application/atom+xml")))?;

// FILE: serve a file; render its path from the request with a template type.
client.when(HttpRequest::new().path("/users/{name}").path_param("name", "[a-z]+"))
    .respond(HttpResponse::new()
        .status_code(200)
        .body_value(Body::file("responses/{{ request.pathParameters.name.0 }}.json")
            .with_content_type("application/json")
            .with_template_type("MUSTACHE")
            .with_templated_file_path(true)))?;

// A raw (non-HTTP) message matcher, used with a binary response action.
client.when(HttpRequest::new().binary_data(b"\x00\x01PING"))
    .respond_with_binary(BinaryResponse::from_bytes(b"\x00\x02PONG"))?;
```

A non-string response body is held under `HttpResponse.extra["body"]`, so the
`body` field keeps its `Option<String>` type. Expectations read back from the
server keep every body shape; `response.typed_body()` returns the body as a
`Body`. For a retrieved expectation that is the shape the server returned,
which is not always the variant you sent (a typed JSON body can come back as
an inline JSON object, and a JSON array comes back as a typed JSON body).
`Body::with_content_type(..)` sets the content type on `FILE`, `JSON`,
`BINARY`, `STRING` and `XML` bodies, and wraps an inline JSON object as a
typed JSON body so the content type stays out of the payload.
`Body::with_templated_file_path(true)` renders a `FILE` body's path against the
request; the part of the path after its fixed directory must stay inside that
directory. Such a body is held as a `Body::Object` with `"type": "FILE"`.

### Template parameters

`Expectation::respond_template_with_parameters(template, parameters)` (and
`forward_template_with_parameters`, and
`HttpOverrideForwardedRequest::response_template_with_parameters`) sets a
template with the `parameters` it reads as `parameters.*`. `HttpTemplate` has no
field for them, so they are held in the owning struct's `extra` map under
`"httpResponseTemplate.parameters"` (and so on) and written into the template on
the wire; a retrieved expectation keeps them, and `response_template_parameters()`
reads them. Replace a template with `respond_template` / `forward_template`, not by
assigning the field, so its parameters are replaced too. Deserialize with
`serde_json::from_*` or `<Expectation as Deserialize>::deserialize`: the
associated function `Expectation::deserialize(value)` drops `parameters`.

### Conditional (if/then/else) request matchers and load-step checks

`HttpRequest::conditional(if, then, else)` builds an `httpRequest` holding
`if`, `then` and `else`: when the `if` request matches, `then` must match too,
otherwise `else` must (leave out `else` with `.if_request(..).then_request(..)`,
and the matcher matches whenever `if` does not). A branch can be any
`HttpRequest`, an OpenAPI matcher (`HttpRequest::open_api(spec).operation_id(..)`)
or another conditional; expectations read back from the server keep them.

A load-scenario `LoadStep` takes per-step response assertions with
`.check(LoadCheck::status(..))`, `LoadCheck::header(..)` or
`LoadCheck::body_json_path(..)`; failing checks feed the
`LoadThresholdMetric::CheckFailureRate` threshold. These need a client released
after 8.0.0. <!-- version-ref:keep -->

```rust
use mockserver_client::*;

client.when(HttpRequest::conditional(
    HttpRequest::new().method("POST"),
    HttpRequest::new().path("/orders"),
    HttpRequest::new().method("GET"),
))
.respond(HttpResponse::new().status_code(200))?;

let step = LoadStep::new(HttpRequest::new().method("GET").path("/api/orders/123"))
    .check(LoadCheck::status(LoadCheckComparator::Equals, "200"))
    .check(LoadCheck::body_json_path("$.status", LoadCheckComparator::Equals, "CONFIRMED"));
```

### Interactive Breakpoints

Register breakpoint matchers to pause forwarded/proxied traffic at REQUEST, RESPONSE, RESPONSE_STREAM, or INBOUND_STREAM phases. A callback WebSocket connection is opened automatically.

```rust
use mockserver_client::*;

let client = ClientBuilder::new("localhost", 1080).build()?;

// REQUEST-only breakpoint
let id = client.add_request_breakpoint(
    HttpRequest::new().path("/api/.*"),
    Box::new(|req| Some(req)),  // continue with original
)?;

// REQUEST + RESPONSE breakpoint
let id2 = client.add_request_response_breakpoint(
    HttpRequest::new().path("/api/.*"),
    Box::new(|req| Some(req)),
    Box::new(|_req, resp| Some(resp)),
)?;

// Streaming breakpoint
let id3 = client.add_stream_breakpoint(
    HttpRequest::new().path("/stream/.*"),
    &[phase::RESPONSE_STREAM],
    Box::new(|frame| {
        Some(StreamFrameDecision::continue_frame(&frame.correlation_id))
    }),
)?;

// Manage matchers
let list = client.list_breakpoint_matchers()?;
client.remove_breakpoint_matcher(&id)?;
client.clear_breakpoint_matchers()?;
client.close_breakpoint_websocket();
```

**Stream frame decisions:** `StreamFrameDecision::continue_frame`, `::modify`, `::drop_frame`, `::inject`, `::close`.

### Matching diagnostics

Find out why a request was served by the wrong expectation, or by none. Each call is
read-only on the server: it consumes no `Times` and moves no scenario.

```rust
// Which expectation would serve a recorded request now, and why each one ahead
// of it was passed over (correlation id from its log entries)
let explanation = client.explain("abc-123-def-456")?;
for skipped in &explanation.skipped_expectations {
    println!("{:?} {:?}: {:?}", skipped.expectation_id, skipped.reason, skipped.explanation);
}

// Compare a request with every active expectation, field by field
let report = client.debug_mismatch(HttpRequest::new().method("GET").path("/api/users"))?;
println!("{:?}", report.closest_match);

// Recent requests that matched nothing, closest expectations first, each with a
// suggested JSON patch that would make it match
let unmatched = client.explain_unmatched(Some(
    &ExplainUnmatchedOptions::new().limit(5).suggest_fixes(true),
))?;
```

`explain` returns `Error::InvalidRequest` for a blank correlation id (without calling the
server) and `Error::NotFound` for one that is no longer in the event log.

### Enums and newer MockServer versions

Every public enum is `#[non_exhaustive]`, so a `match` on one needs a `_ =>` arm. New
values can then be added in a minor release without breaking your code.

Enums that travel over the wire (for example `ResponseMode`, `BinaryUpstream`,
`CrossProtocolTrigger`, `ExplainReason` and the `Load*` enums) also have an `Unknown` variant. A value
this client version does not recognise, sent by a newer MockServer, reads as `Unknown`
instead of failing the whole response. The original value is not kept, so `Unknown`
cannot be sent back: serialising it, or sending an expectation or load scenario that
holds it, returns an error and nothing reaches the server. To re-submit server JSON
unchanged, use `upsert_raw`.

## Start / Launch MockServer

The Rust client can download and launch a local MockServer instance directly -- no Java installation and no Docker required. The launcher downloads a self-contained platform bundle (`mockserver-<version>-<os>-<arch>`) from the GitHub Release, verifies its SHA-256, caches it per-user, and starts it.

### Quick start

```rust
use mockserver_client::launcher;

fn main() -> Result<(), Box<dyn std::error::Error>> {
    let mut handle = launcher::start(1080)?;
    println!("MockServer running on port {}", handle.port());

    // ... use MockServer ...

    handle.stop()?;
    Ok(())
}
```

### Just ensure the binary is present

```rust
let launcher_path = launcher::ensure_launcher()?;
println!("Launcher at: {}", launcher_path.display());
```

### Specify a version

```rust
let mut handle = launcher::start_with_version(
    "9.2.0", 1080, &launcher::EnsureOptions::default()
)?;
```

### API reference

| Function / Type | Description |
|---|---|
| `launcher::ensure_launcher()` | Download, verify, cache the default-version binary, and return the launcher `PathBuf`. |
| `launcher::ensure_binary(version, opts)` | Same as above, but for a specific version. |
| `launcher::start(port)` | Ensure the binary and start MockServer at the default version. Returns a `ServerHandle`. |
| `launcher::start_with_version(version, port, opts)` | Start MockServer at a specific version. Returns a `ServerHandle`. |
| `launcher::ServerHandle` | Handle to the running process. Methods: `stop()`, `wait()`, `port()`. |
| `launcher::VERSION` | The default MockServer version, derived from `Cargo.toml` at compile time via `env!("CARGO_PKG_VERSION")`. |

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

By default the launcher downloads the MockServer version matching this crate (derived from `Cargo.toml` at compile time via `env!("CARGO_PKG_VERSION")`). Pass an explicit version to override.

## Building

```sh
cargo build
cargo test
cargo clippy
```

## Integration Tests

Integration tests require a running MockServer and are skipped by default:

```sh
# Start MockServer (e.g., via Docker)
docker run -d -p 1080:1080 mockserver/mockserver

# Run integration tests
MOCKSERVER_URL=http://localhost:1080 cargo test -- --ignored
```

## License

Apache-2.0
