# MockServer Python Client

Python client for [MockServer](https://www.mock-server.com) with full WebSocket callback support.

## Features

- **Full REST API**: Create expectations, verify requests, clear/reset, retrieve recorded data
- **Response Callbacks**: Register Python functions that dynamically generate responses via WebSocket
- **Forward Callbacks**: Modify requests before they are forwarded to the real server
- **Forward+Response Callbacks**: Modify both the forwarded request and the response
- **Fluent API**: `client.when(request).respond(callback)` — mirrors the Java client
- **Async + Sync**: Native asyncio API with a synchronous wrapper for non-async code
- **Minimal Dependencies**: Only `websockets` (for callback support)

## Installation

```bash
pip install mockserver-client
```

## Quick Start

### Synchronous API

```python
from mockserver import MockServerClient, HttpRequest, HttpResponse

client = MockServerClient("localhost", 1080)

# Static expectation
client.when(
    HttpRequest.request("/api/users").with_method("GET")
).respond(
    HttpResponse.response('{"users": []}', status_code=200)
)

# Verify
client.verify(
    HttpRequest.request("/api/users").with_method("GET"),
    VerificationTimes.at_least(1)
)

# Clean up
client.reset()
client.close()
```

### Context Manager

```python
with MockServerClient("localhost", 1080) as client:
    client.when(
        HttpRequest.request("/hello")
    ).respond(
        HttpResponse.response("world")
    )
```

### Async API

```python
import asyncio
from mockserver import AsyncMockServerClient, HttpRequest, HttpResponse

async def main():
    async with AsyncMockServerClient("localhost", 1080) as client:
        await client.when(
            HttpRequest.request("/api/data")
        ).respond(
            HttpResponse.response('{"key": "value"}')
        )

asyncio.run(main())
```

## Response Callbacks

Register a Python function that generates responses dynamically when matching requests arrive:

```python
from mockserver import MockServerClient, HttpRequest, HttpResponse

def handle_request(request):
    if request.method == "POST":
        return HttpResponse.response("created", status_code=201)
    return HttpResponse.not_found_response()

client = MockServerClient("localhost", 1080)
client.mock_with_callback(
    HttpRequest.request("/api/callback"),
    handle_request
)
```

Or with the fluent API:

```python
client.when(
    HttpRequest.request("/api/callback")
).respond(handle_request)
```

## Forward Callbacks

Modify requests before they are forwarded to the real server:

```python
def modify_request(request):
    return request.with_header("X-Forwarded", "true").with_path("/modified" + request.path)

client.mock_with_forward_callback(
    HttpRequest.request("/proxy/.*"),
    modify_request
)
```

## Forward+Response Callbacks

Modify both the forwarded request and the response:

```python
def modify_request(request):
    return request.with_header("X-Proxied", "true")

def modify_response(request, response):
    return response.with_header("X-Modified", "true")

client.mock_with_forward_callback(
    HttpRequest.request("/proxy/.*"),
    modify_request,
    modify_response
)
```

## Forward with Overrides

Forward the matched request with fields replaced (`request_override`), modified
(`request_modifier`), or with its response overridden (`response_override`,
`response_modifier`, `response_template`), without a callback:

```python
from mockserver import HttpOverrideForwardedRequest, HttpRequest, HttpResponse

client.when(HttpRequest.request("/some/path")).forward(
    HttpOverrideForwardedRequest(
        request_override=HttpRequest(path="/other/path", headers={"Host": ["target.host.com"]}),
        response_override=HttpResponse(headers={"X-Proxied": ["true"]}),
        response_modifier={"headers": {"remove": ["Server"]}},
    )
)
```

`http_request` and `http_response` are deprecated aliases of `request_override`
and `response_override`, kept for existing code; set one name of each pair, not both.

## Verification

```python
from mockserver import VerificationTimes

# Verify a request was received at least once
client.verify(
    HttpRequest.request("/api/users").with_method("GET"),
    VerificationTimes.at_least(1)
)

# Verify exact count
client.verify(
    HttpRequest.request("/api/users"),
    VerificationTimes.exactly(3)
)

# Verify request sequence (order matters)
client.verify_sequence(
    HttpRequest.request("/first"),
    HttpRequest.request("/second"),
    HttpRequest.request("/third"),
)

# Verify no interactions
client.verify_zero_interactions()
```

## Request Matching

### Headers, query parameters and cookies

Pass headers, query and path parameters, trailers and cookies as a dict, as in
MockServer's JSON, or as a list of `KeyToMultiValue`. A `"keyMatchStyle"` key
(`SUB_SET`, the default, or `MATCHING_KEY`) sets how the values of a key are
matched.

```python
from mockserver import HttpRequest, HttpResponse

client.when(
    HttpRequest(
        path="/search",
        headers={"Accept": "application/json"},        # one value
        query_string_parameters={"tag": ["a", "b"]},   # several values
        cookies={"session": "abc"},
    )
).respond(
    HttpResponse(status_code=302, headers={"Location": ["https://example.com"]})
)

# every value the request has for X-Tag must match, not just one
HttpRequest(headers={"keyMatchStyle": "MATCHING_KEY", "X-Tag": ["a", "b"]})
```

A value can also be a `DateTimeMatcher`, which matches a header, query or path
parameter, or cookie holding a date-time before, after or equal to an expected
value (`now`, `now -1 hours`, or a date-time such as `2026-10-21T00:00:00Z`).
A plain string such as `"before:2026-01-01"` is always matched literally.

```python
from mockserver import DateTimeMatcher, HttpRequest

HttpRequest(path="/orders") \
    .with_header("X-Issued-At", DateTimeMatcher.after("now -1 hours")) \
    .with_header("If-Modified-Since", DateTimeMatcher.before("now").with_actual_format("rfc1123")) \
    .with_cookie("seen", DateTimeMatcher.equal_to_date_time("now").with_truncate_actual("startOfDay"))
```

Each `with_*` option (`with_actual_format`, `with_truncate_expected`,
`with_truncate_actual`, `with_apply_truncation_last`, `with_zone`, `with_not`)
returns a new matcher.

A JSONPath or XPath body matcher can take a `DateTimeMatcher` too: the body
matches when a value the JSONPath selects, or the text of a node the XPath
selects, is a matching date-time.

```python
from mockserver import Body, DateTimeMatcher, HttpRequest

HttpRequest(path="/orders", body=Body.json_path("$.order.created", DateTimeMatcher.after("now -1 days")))
HttpRequest(path="/soap", body=Body.xpath("/order/@expires", None, DateTimeMatcher.after("now")))
```

### JWT matcher

Match requests by the claims (and metadata) of a JWT carried in a header. Each
claim value is exact-or-regex; prefix a value with `!` to negate it.

```python
from mockserver import HttpRequest, HttpResponse, Jwt

client.when(
    HttpRequest.request("/secure")
        .with_method("GET")
        .with_jwt(
            claims={
                "sub": "user-123",           # exact match
                "email": "^.+@example.com$", # regex match
                "role": "!admin",            # negated — matches any role except admin
            },
            issuer="https://issuer.example.com",
            audience="my-api",
            algorithm="RS256",
            header="authorization",          # optional (defaults to Authorization)
            scheme="Bearer",                 # optional token scheme
        )
).respond(
    HttpResponse.response("granted", status_code=200)
)

# ...or pass a Jwt object directly:
HttpRequest.request("/secure").with_jwt(Jwt(claims={"sub": "user-123"}))
```

### Client IP matcher

Match requests by the client IP address or CIDR range, IPv4 or IPv6. The client address is the TCP peer of the connection, not `X-Forwarded-For`; prefix with `!` to match every other address.

```python
HttpRequest.request("/internal").with_client_ip("10.0.0.0/8")
# or HttpRequest(path="/internal", client_ip="!10.0.0.0/8")
```

### Any-of (logical OR) matcher

`AnyOfRequestDefinition` matches a request when any one of its request matchers does, tried in order. Entries may be `HttpRequest`, `OpenAPIDefinition`, `ConditionalRequestDefinition` or a nested `AnyOfRequestDefinition`; `not_any_of=True` matches requests that match none of them. It serialises to `{"anyOf": [...]}` inside `httpRequest`.

```python
from mockserver import AnyOfRequestDefinition, HttpRequest, HttpResponse

client.when(
    AnyOfRequestDefinition.request_any_of(
        HttpRequest(method="GET", path="/pets"),
        HttpRequest(method="GET", path="/animals"),
    )
).respond(HttpResponse(body="pets or animals"))
```

`ExplainedExpectation.any_of` (an `AnyOfBranchComparison`) says which entry came closest when none matches.

### allOf body matcher

Require a request body to satisfy **all** of several body matchers at once.

```python
from mockserver import AllOfBody, Body, JsonPathBody, RegexBody, HttpRequest

client.when(
    HttpRequest.request("/api/orders")
        .with_method("POST")
        .with_body(AllOfBody(body_all_of=[
            JsonPathBody(json_path="$.name"),   # body has a name field
            RegexBody(regex=".*active.*"),      # and mentions "active"
        ]))
).respond(
    HttpResponse.response("ok")
)

# Shorthand factory:
HttpRequest.request("/api/orders").with_body(
    Body.all_of(JsonPathBody(json_path="$.name"), RegexBody(regex=".*active.*"))
)
```

### Conditional (if/then/else) matcher

Pass a `ConditionalRequestDefinition` where a request matcher goes: when
`if_request` matches, `then_request` must match too; otherwise `else_request`
must (with no `else_request` it matches whenever `if_request` does not). Each
branch is an `HttpRequest`, an `OpenAPIDefinition` or another
`ConditionalRequestDefinition`. Retrieved expectations whose `httpRequest` holds
`if` read back as this type.

```python
from mockserver import Body, ConditionalRequestDefinition, HttpRequest, HttpResponse

client.when(
    ConditionalRequestDefinition(
        if_request=HttpRequest(method="POST").with_header("content-type", "application/json"),
        then_request=HttpRequest(body=Body.json_schema('{"type": "object", "required": ["orderId"]}')),
        else_request=HttpRequest(method="GET"),
    )
).respond(HttpResponse(status_code=200))
```

## Retrieval

```python
# Get recorded requests
requests = client.retrieve_recorded_requests(
    HttpRequest.request("/api/.*")
)

# Get active expectations
expectations = client.retrieve_active_expectations()

# Get log messages
logs = client.retrieve_log_messages()
```

### Expectation metadata, unmatched expectations and paging

Label expectations with metadata (string keys, any JSON value). Metadata never
affects matching; it is returned on retrieve and lets you retrieve or clear
just your own expectations.

```python
from mockserver import ExpectationFilter, JSON_NULL, RecordedExpectationsOptions

client.when(HttpRequest.request("/api/users")).with_metadata("owner", "team-a").with_metadata(
    "ticket", 4211
).respond(HttpResponse(status_code=200))

mine = client.retrieve_active_expectations_by_metadata("owner", "team-a")
labelled = client.retrieve_active_expectations_by_metadata_key("owner")
never_matched = client.retrieve_unmatched_expectations()
page = client.retrieve_active_expectations(
    expectation_filter=ExpectationFilter().with_metadata_json_path("$.tags[?(@ == 'smoke')]").with_offset(0).with_limit(20)
)
recorded_page = client.retrieve_recorded_expectations(offset=0, limit=50)
# replay changing responses in order, keep only some request headers, move large bodies into files
shaped = client.retrieve_recorded_expectations(
    options=RecordedExpectationsOptions().with_repeats_as_scenarios().with_capture_headers("X-Tenant").with_extract_bodies_larger_than(1024)
)

client.clear_by_metadata("owner", "team-a")      # str compared as text, other values as JSON
client.clear_by_metadata_key("ticket")           # any value
client.clear_unmatched_expectations()
```

These calls raise `ValueError` before sending anything for a blank key or JSONPath,
a `None` value (use `..._by_metadata_key` for any value, or `JSON_NULL` for a JSON
null), a negative offset or limit, or a clear whose filter selects nothing or pages.

## Control

```python
# Clear specific expectations
client.clear(HttpRequest.request("/api/users"))

# Clear by type
client.clear(HttpRequest.request("/api/users"), clear_type="LOG")

# Reset everything
client.reset()

# Bind additional ports
client.bind(1081, 1082)

# Check if running
if client.has_started():
    print("MockServer is running")

# Stop
client.stop()
```

## AI Protocol Mocking

Declarative builders mock an MCP (Model Context Protocol) server or an A2A
(Agent-to-Agent) server with a single fluent chain. Each builder produces a set of
HTTP expectations that speak JSON-RPC 2.0 and echo the incoming request id.

```python
from mockserver import mcp_mock, a2a_mock

# Mock an MCP server (Streamable HTTP, JSON-RPC 2.0) on /mcp
mcp_mock() \
    .with_tool("get_weather") \
        .with_description("Get weather for a city") \
        .with_input_schema('{"type": "object", "properties": {"city": {"type": "string"}}}') \
        .responding_with("72F and sunny") \
        .and_() \
    .apply_to(client)

# Mock an A2A agent on /a2a (agent card + tasks/send|get|cancel)
a2a_mock() \
    .with_agent_name("TranslatorAgent") \
    .with_skill("translate") \
        .with_name("Translation") \
        .with_description("Translates text between languages") \
        .with_tag("i18n") \
        .with_example("Translate hello to Spanish") \
        .and_() \
    .on_task_send() \
        .matching_message("translate.*") \
        .responding_with("Hola") \
        .and_() \
    .apply_to(client)
```

The A2A builder also supports streaming (SSE) and push notifications:

```python
a2a_mock() \
    .with_streaming() \
    .with_push_notifications("http://localhost:1234/callback") \
    .apply_to(client)
```

`build()` returns the list of `Expectation` objects without registering them, so
you can inspect or persist them; `apply_to(client)` registers them via `upsert`.

## SRE / Resilience

Verify a service-level objective over recorded SLI samples, or run a scheduled
multi-stage chaos experiment. Both require the corresponding server feature to be
enabled (`sloTrackingEnabled`, chaos experiments).

```python
# Verify an SLO — a FAIL verdict (HTTP 406) raises MockServerVerificationError
verdict = client.verify_slo({
    "name": "checkout",
    "minimumSampleCount": 100,
    "objectives": [
        {"sli": "errorRate", "comparator": "LESS_THAN", "threshold": 0.01},
        {"sli": "p99LatencyMs", "comparator": "LESS_THAN", "threshold": 250},
    ],
})
print(verdict["result"])  # PASS or INCONCLUSIVE

# Start a multi-stage chaos experiment (only one may be active at a time)
client.start_chaos_experiment({
    "name": "latency-injection",
    "loop": False,
    "stages": [
        {"durationMillis": 60000, "profiles": {"payments.svc": {"latencyMs": 500}}},
    ],
})
```

Both methods are available on the async client too (`await client.verify_slo(...)`,
`await client.start_chaos_experiment(...)`).

## Diagnose Matching

`explain(correlation_id)` tells you which expectation would serve a recorded request now and why
each expectation ahead of it was passed over; `debug_mismatch(request)` compares a request with
every active expectation; `explain_unmatched(limit=..., suggest_fixes=...)` explains recent
requests that matched nothing. Each returns a typed dataclass (`RequestExplanation`,
`DebugMismatchResult`, `ExplainUnmatchedResult`) and raises `MockServerError` with the server's
reason on failure. The async client has the same methods.

```python
explanation = client.explain("abc-123-def-456")
for skipped in explanation.skipped_expectations:
    print(skipped.expectation_id, skipped.reason, skipped.explanation)
```

## TLS Support

```python
# Uses system trust store (default — verifies certificates)
client = MockServerClient("localhost", 1080, secure=True)

# Custom CA certificate
client = MockServerClient(
    "localhost", 1080,
    secure=True,
    ca_cert_path="/path/to/ca.pem"
)

# Disable certificate verification (testing only — NOT recommended for production)
client = MockServerClient(
    "localhost", 1080,
    secure=True,
    tls_verify=False
)
```

## Control-Plane Authentication

When MockServer requires a control-plane JWT, pass the token (or a supplier for a token that is refreshed). The client sends `Authorization: Bearer <token>` on every control-plane request and on the callback WebSocket used by callbacks and breakpoints; a supplier is called again on each reconnect.

```python
client = MockServerClient("localhost", 1080, control_plane_bearer_token=jwt)
client = MockServerClient("localhost", 1080, control_plane_bearer_token_supplier=fetch_jwt)
```

When MockServer accepts control-plane Basic authentication, pass `control_plane_basic_auth=(username, password)` with `secure=True` (MockServer refuses Basic credentials without TLS). They are sent on the same requests and WebSocket; a bearer token, when also set, is sent instead.

```python
client = MockServerClient("localhost", 1080, secure=True, control_plane_basic_auth=("admin", os.environ["MOCKSERVER_PASSWORD"]))
```

When MockServer requires mutual TLS for the control plane, pass `client_cert_path` and `client_key_path` (with `secure=True` and, for a private CA, `ca_cert_path`). The client presents that certificate, and trusts that CA, on every control-plane request and on the callback WebSocket.

```python
client = MockServerClient("localhost", 1080, secure=True, ca_cert_path="ca.pem",
                          client_cert_path="client-cert.pem", client_key_path="client-key.pem")
```

## Domain Model

All domain model classes support builder-style chaining:

```python
request = (
    HttpRequest.request("/api/users")
    .with_method("POST")
    .with_header("Content-Type", "application/json")
    .with_header("Authorization", "Bearer token")
    .with_body('{"name": "test"}')
    .with_query_param("page", "1")
    .with_secure(True)
)

response = (
    HttpResponse.response()
    .with_status_code(201)
    .with_header("Location", "/api/users/1")
    .with_body('{"id": 1, "name": "test"}')
    .with_delay(Delay(time_unit="SECONDS", value=1))
)
```

## Interactive Breakpoints

The client supports matcher-driven interactive breakpoints over the callback WebSocket. Register a breakpoint matcher to pause forwarded/proxied exchanges at specific phases and inspect/modify/continue them via callback handlers.

### Register a breakpoint (sync client)

```python
from mockserver import MockServerClient, HttpRequest, HttpResponse

client = MockServerClient("localhost", 1080)

# REQUEST phase only
bp_id = client.add_request_breakpoint(
    HttpRequest(path="/api/.*"),
    lambda request: request,  # continue unchanged (or return HttpResponse to abort)
)

# REQUEST + RESPONSE
bp_id = client.add_request_and_response_breakpoint(
    HttpRequest(path="/api/.*"),
    lambda request: request,                      # REQUEST handler
    lambda request, response: response,           # RESPONSE handler
)

# All phases with stream frame handler
bp_id = client.add_breakpoint(
    HttpRequest(path="/stream/.*"),
    ["REQUEST", "RESPONSE", "RESPONSE_STREAM", "INBOUND_STREAM"],
    request_handler=lambda request: request,
    response_handler=lambda request, response: response,
    stream_frame_handler=lambda frame: {"action": "CONTINUE"},
    # Other actions: MODIFY (with body), DROP, INJECT (with body), CLOSE
)
```

### Manage breakpoints

```python
# List all matchers
matchers = client.list_breakpoint_matchers()  # {"matchers": [...]}

# Remove a specific matcher
client.remove_breakpoint_matcher(bp_id)

# Clear all matchers
client.clear_breakpoint_matchers()
```

The async client (`AsyncMockServerClient`) exposes the same methods as coroutines.

## Start / Launch MockServer

The Python client can download and launch a local MockServer instance directly -- no Java installation and no Docker required. The launcher downloads a self-contained platform bundle (`mockserver-<version>-<os>-<arch>`) from the GitHub Release, verifies its SHA-256, caches it per-user, and starts it.

### Quick start

```python
from mockserver.launcher import start, MockServerProcess

# Download (first run) and start MockServer on port 1080
with start(port=1080) as server:
    print(f"MockServer running on port {server.port}, PID {server.pid}")
    # ... use MockServer ...
# Server is stopped automatically when the context manager exits
```

### Just ensure the binary is present

```python
from mockserver.launcher import ensure_binary

launcher_path = ensure_binary()  # returns Path to the launcher executable
```

### Specify a version

```python
from mockserver.launcher import start

server = start(port=1080, version="9.2.0")
# ...
server.stop()
```

### API reference

| Function / Class | Description |
|---|---|
| `ensure_binary(version=None, *, log=True)` | Download, verify, cache, and return the launcher `Path`. Defaults to the client's own version. |
| `start(port, version=None, *, extra_args=None, log=True)` | Ensure the binary and start MockServer. Returns a `MockServerProcess`. |
| `MockServerProcess` | Handle to the running process. Properties: `port`, `pid`, `launcher`, `returncode`. Methods: `stop(timeout=10.0)`. Supports `with` statement. |

On Windows the launcher runs `mockserver.bat` through `cmd.exe` with every argument quoted, so characters such as `&`, `|` and `^` reach MockServer unchanged. `start()` raises `ValueError` if the launcher path or an `extra_args` entry contains a double quote, a line break or NUL, or if together they contain more than one `%` (which `cmd.exe` would expand as a `%NAME%` variable). No shell is used on Linux or macOS.

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

By default the launcher downloads the MockServer version matching this client package (currently the version set in `pyproject.toml`). Pass an explicit `version` argument to override.

## Requirements

- Python 3.9+
- `websockets` >= 12.0 (for callback support)

## License

Apache 2.0

## AI Assistant Integration

MockServer includes a built-in [MCP](https://modelcontextprotocol.io) (Model Context Protocol) server that enables AI coding assistants to create expectations, verify requests, and debug HTTP traffic programmatically.

- **MCP Endpoint:** `http://localhost:1080/mockserver/mcp`
- **AI Documentation:** [llms.txt](https://www.mock-server.com/llms.txt)
- **Setup Guide:** [AI Integration](https://www.mock-server.com/mock_server/ai_mcp_setup.html)
