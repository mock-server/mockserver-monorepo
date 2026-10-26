# MockServer Ruby Client

Hand-written Ruby client for [MockServer](https://www.mock-server.com) with full REST API, fluent builder DSL, and WebSocket callback support.

## Installation

Add to your Gemfile:

```ruby
gem 'mockserver-client', '~> 7.3'
```

Or install directly:

```bash
gem install mockserver-client
```

## Quick Start

```ruby
require 'mockserver-client'

# Create a client
client = MockServer::Client.new('localhost', 1080)

# Set up an expectation using the fluent API
client.when(
  MockServer::HttpRequest.request(path: '/hello')
    .with_method('GET')
).respond(
  MockServer::HttpResponse.response(body: 'world', status_code: 200)
)

# Verify a request was received
client.verify(
  MockServer::HttpRequest.request(path: '/hello'),
  times: MockServer::VerificationTimes.at_least(1)
)

# Clean up
client.reset
client.close
```

## Block Form

```ruby
MockServer::Client.new('localhost', 1080) do |client|
  client.when(
    MockServer::HttpRequest.request(path: '/api/test')
  ).respond(
    MockServer::HttpResponse.response(body: '{"status":"ok"}', status_code: 200)
  )
end
# Client is automatically closed when the block exits
```

## Control-Plane Basic Authentication

When MockServer accepts control-plane Basic authentication, pass `control_plane_basic_auth: [username, password]` with `secure: true` (MockServer refuses Basic credentials without TLS). They are sent on every control-plane request and on the callback and breakpoint WebSockets.

```ruby
client = MockServer::Client.new('localhost', 1080, secure: true,
                                control_plane_basic_auth: ['admin', ENV.fetch('MOCKSERVER_PASSWORD')])
```

## WebSocket Callbacks

```ruby
client = MockServer::Client.new('localhost', 1080)

# Response callback - dynamically generate responses
client.mock_with_callback(
  MockServer::HttpRequest.request(path: '/dynamic'),
  ->(request) {
    MockServer::HttpResponse.new(
      status_code: 200,
      body: "Echo: #{request.path}"
    )
  }
)

# Forward callback - modify requests before forwarding
client.mock_with_forward_callback(
  MockServer::HttpRequest.request(path: '/proxy'),
  ->(request) {
    request.with_header('X-Proxied', 'true')
  }
)

client.close
```

## Models

All 27 domain model classes are available under the `MockServer` module:

- `Delay`, `Times`, `TimeToLive`
- `KeyToMultiValue`, `Body`, `SocketAddress`
- `HttpRequest`, `HttpResponse`, `HttpForward`, `HttpTemplate`
- `HttpClassCallback`, `HttpObjectCallback`, `HttpError`
- `HttpOverrideForwardedRequest`, `HttpRequestAndHttpResponse`
- `ConnectionOptions`
- `Expectation`, `ExpectationId`
- `OpenAPIDefinition`, `OpenAPIExpectation`, `ConditionalRequestDefinition`
- `Verification`, `VerificationSequence`, `VerificationTimes`
- `Ports`
- `RequestDefinition` (alias for `HttpRequest`)
- `Jwt` (JWT / bearer-token request matcher)
- `DateTimeMatcher` (date-time matcher for header, query, path-parameter and cookie values)

Every model can also be built from the expectation JSON with `from_hash`. Keys
may be strings (`'httpRequest' => {...}`, as `JSON.parse` returns them) or
symbols (`"httpRequest": {...}` or `httpRequest: {...}`):

```ruby
client.upsert(MockServer::Expectation.from_hash({
  "httpRequest": { "path": '/some/path' },
  "httpResponse": { "statusCode": 200, "body": 'some_response_body' }
}))
```

Symbol keys need a client released after 8.0.0; 8.0.0 and earlier read only <!-- version-ref:keep -->
string keys and build an empty expectation from a symbol-keyed hash.

Headers, query parameters, path parameters, trailers and cookies can be given
in the same shapes the JSON uses. A key match style sets whether at least one
value of each listed key must match (`SUB_SET`, the default) or every value of
it (`MATCHING_KEY`):

```ruby
MockServer::HttpRequest.new(
  path: '/view/cart',
  headers: { 'Accept' => ['application/json'] },
  headers_key_match_style: 'MATCHING_KEY',
  query_string_parameters: [{ name: 'cartId', values: ['055CA455'] }],
  cookies: { 'session' => '4930456C' }
)
```

A header, query parameter, path parameter or cookie value can also be a
date-time matcher. It compares the request's value as a date-time with
`before`, `after` or `equal_to_date_time`, where the expected value is `now`,
`now` with an offset such as `now -1 hours`, or a date-time such as
`2026-10-21T00:00:00Z`. A plain string such as `'before:2026-01-01'` is still
matched literally.

```ruby
MockServer::HttpRequest.new(path: '/orders')
  .with_header('X-Issued-At', MockServer::DateTimeMatcher.after('now -1 hours'))
  .with_header('If-Modified-Since',
               MockServer::DateTimeMatcher.before('2026-10-21T00:00:00Z').with_actual_format('rfc1123'))
  .with_cookie('seen', MockServer::DateTimeMatcher.equal_to_date_time('now')
                                                   .with_truncate_expected('startOfDay')
                                                   .with_truncate_actual('startOfDay')
                                                   .with_zone('Europe/London'))
# headers => [{"name"=>"X-Issued-At", "values"=>[{"after"=>"now -1 hours"}]}, ...]
```

Each `with_*` option (`with_actual_format`, `with_truncate_expected`,
`with_truncate_actual`, `with_apply_truncation_last`, `with_zone`, `with_not`)
returns a new matcher. `from_hash` reads an object holding `before`, `after` or
`equalToDateTime` as a `DateTimeMatcher`.

A JSONPath or XPath body matcher takes a `date_time:` too: the body matches when
a value the JSONPath selects, or the text of a node the XPath selects, is a
matching date-time.

```ruby
MockServer::HttpRequest.new(path: '/orders',
                            body: MockServer::Body.json_path('$.order.created',
                                                             date_time: MockServer::DateTimeMatcher.after('now -1 days')))
# body => {"type"=>"JSON_PATH", "jsonPath"=>"$.order.created", "dateTime"=>{"after"=>"now -1 days"}}
```

An `httpRequest` holding `specUrlOrPayload` is read as an `OpenAPIDefinition`
matcher, and `HttpOverrideForwardedRequest` carries `request_override`,
`response_override` and `response_template`. These need a client released
after 8.0.0. <!-- version-ref:keep -->

An `httpRequest` holding `if` is read as a `ConditionalRequestDefinition`: when
`if_request` matches, `then_request` must match too, otherwise `else_request`
must. A load scenario `LoadStep` takes `checks`, a list of `LoadCheck`
assertions on each step's response. These need a client released after 8.0.0. <!-- version-ref:keep -->

```ruby
client.when(
  MockServer::ConditionalRequestDefinition.new(
    if_request: MockServer::HttpRequest.new(method: 'POST'),
    then_request: MockServer::HttpRequest.new(path: '/orders'),
    else_request: MockServer::HttpRequest.new(method: 'GET')
  )
).respond(MockServer::HttpResponse.new(status_code: 200))
```

## JWT and combined body matchers

Match a request by the claims in its JWT (carried by default in the
`Authorization: Bearer <token>` header). Each claim value is an exact string or a
regular expression; a leading `!` negates it (the same NottableString convention
used elsewhere in MockServer). `issuer`, `audience`, `algorithm`, `header` and
`scheme` are optional.

```ruby
jwt = MockServer::Jwt.new(
  claims: {
    'sub'   => 'user-123',
    'role'  => '!admin',            # must NOT be "admin"
    'email' => '^.+@example.com$'   # regex
  },
  issuer:    'https://issuer.example.com',
  audience:  'my-api',
  algorithm: 'RS256'
)

request = MockServer::HttpRequest.new(method: 'GET', path: '/api')
                                 .with_jwt(jwt)
```

Combine several body matchers with `Body.all_of` — the request body must satisfy
**all** of them:

```ruby
body = MockServer::Body.all_of(
  MockServer::Body.json_path('$.name'),   # => {"type":"JSON_PATH","jsonPath":"$.name"}
  MockServer::Body.regex('.*active.*')    # => {"type":"REGEX","regex":".*active.*"}
)

request = MockServer::HttpRequest.new(method: 'POST', path: '/api').with_body(body)
```

## Client IP matcher

Match requests by the client IP address or CIDR range, IPv4 or IPv6. The client address is the TCP peer of the connection, not `X-Forwarded-For`; prefix with `!` to match every other address.

```ruby
MockServer::HttpRequest.new(path: '/internal').with_client_ip('10.0.0.0/8')
# or MockServer::HttpRequest.new(path: '/internal', client_ip: '!10.0.0.0/8')
```

## Any-of (logical OR) matcher

`MockServer::AnyOfRequestDefinition` matches a request when any one of its request matchers does, tried in order. Entries may be `HttpRequest`, `OpenAPIDefinition`, `ConditionalRequestDefinition` or a nested `AnyOfRequestDefinition`; `not_any_of: true` matches requests that match none of them. `ExplainedExpectation#any_of` says which entry came closest when none matches.

```ruby
client.when(
  MockServer::AnyOfRequestDefinition.new(
    any_of: [MockServer::HttpRequest.new(method: 'GET', path: '/pets'),
             MockServer::HttpRequest.new(method: 'GET', path: '/animals')]
  )
).respond(MockServer::HttpResponse.new(body: 'pets or animals'))
```

## LLM Mocking

Fluent builders under `MockServer::LLM` mock LLM provider endpoints. They produce
expectations whose action is carried in the `httpLlmResponse` field; MockServer
re-encodes the provider-agnostic completion into the configured provider's wire
shape (OpenAI / Anthropic / Gemini / Bedrock / ...) when serving the request.

```ruby
require 'mockserver-client'

client = MockServer::Client.new('localhost', 1080)

# A single completion mock
MockServer::LLM.llm_mock('/v1/chat/completions')
               .with_provider(MockServer::LLM::Provider::OPENAI)
               .with_model('gpt-4o')
               .responding_with(
                 MockServer::LLM.completion
                                .with_text('Hello!')
                                .with_usage(MockServer::LLM.usage.with_input_tokens(10).with_output_tokens(5))
               )
               .apply_to(client)
```

Multi-turn **conversations** use MockServer scenario-state advancement so each
turn is served once, in order. Add per-turn predicates with `when_*` methods and
optionally isolate per session with `isolate_by`:

```ruby
MockServer::LLM.conversation
               .with_path('/v1/chat/completions')
               .with_provider(MockServer::LLM::Provider::OPENAI)
               .isolate_by(MockServer::LLM.header('x-session-id'))
               .turn.when_latest_message_contains('weather')
                    .responding_with(MockServer::LLM.completion.with_text('It is sunny.'))
               .turn.responding_with(MockServer::LLM.completion.with_text('Anything else?'))
               .apply_to(client)
```

**Failover** scenarios script N upstream failures (consecutive identical failures
are coalesced) followed by a success; default JSON error bodies are generated per
status code unless a custom body is supplied:

```ruby
MockServer::LLM.llm_failover
               .with_path('/v1/chat/completions')
               .with_provider(MockServer::LLM::Provider::OPENAI)
               .fail_with(429, 2)          # two rate-limit failures
               .fail_with(503)             # then one service-unavailable
               .then_respond_with(MockServer::LLM.completion.with_text('Recovered'))
               .apply_to(client)
```

Each builder also exposes `build` to obtain the raw expectation Hash(es) without
registering them.

## MCP Mocking

`MockServer::MCP.mcp_mock` builds the set of expectations needed to emulate a
Streamable-HTTP MCP (Model Context Protocol) server speaking JSON-RPC 2.0. It
generates `initialize`, `ping`, `notifications/initialized`, and per-capability
`tools`, `resources`, and `prompts` handlers.

```ruby
MockServer::MCP.mcp_mock('/mcp')
               .with_server_name('MyServer')
               .with_tool('get_weather')
                 .with_description('Get the weather for a city')
                 .with_input_schema('{"type":"object","properties":{"city":{"type":"string"}}}')
                 .responding_with('72F and sunny')
               .and_then
               .with_resource('file:///config.json')
                 .with_name('config')
                 .with_content('{"debug":true}')
               .and_then
               .with_prompt('greet')
                 .with_argument('name', 'who to greet', true)
                 .responding_with('user', 'Hello there')
               .and_then
               .apply_to(client)
```

`build` returns the raw expectation Hashes if you prefer to register them yourself.

## A2A Mocking

`MockServer::A2A.a2a_mock` builds the set of expectations needed to emulate an
A2A (Agent-to-Agent) agent: an agent card served at `/.well-known/agent.json`
plus JSON-RPC 2.0 `tasks/send`, `tasks/get`, and `tasks/cancel` over `POST
<path>`. It optionally adds SSE streaming, push-notification config + delivery,
per-message custom task handlers, and advertised skills.

```ruby
MockServer::A2A.a2a_mock('/a2a')
               .with_agent_name('WeatherAgent')
               .with_agent_description('Forecasts the weather')
               .with_skill('weather')
                 .with_name('Weather lookup')
                 .with_tag('forecast')
                 .with_example('What is the weather in Paris?')
               .and_then
               .with_streaming
               .with_push_notifications('http://localhost:1234/a2a/callback')
               .on_task_send
                 .matching_message('forecast')
                 .responding_with('Sunny, 25C')
               .and_then
               .apply_to(client)
```

When push notifications are configured, each `tasks/send` both returns the
JSON-RPC task response to the caller *and* POSTs the completed task to the
webhook URL. As with MCP mocking, `build` returns the raw expectation Hashes if
you prefer to register them yourself.

## Expectation Metadata

Label expectations with `metadata` (String keys, any JSON value), then retrieve or clear them by
metadata, find the ones that never matched a request, and page large lists:

```ruby
client.when(MockServer::HttpRequest.new(path: '/checkout'))
      .with_metadata('owner' => 'team-a', 'ticket' => 4211, 'tags' => ['smoke'])
      .respond(MockServer::HttpResponse.new(status_code: 200))

client.retrieve_active_expectations_by_metadata('owner', 'team-a')
client.retrieve_active_expectations_by_metadata_key('ticket')
client.retrieve_unmatched_expectations
client.retrieve_active_expectations(
  filter: MockServer::ExpectationFilter.new.with_metadata_json_path("$.tags[?(@ == 'smoke')]").with_limit(10)
)
client.retrieve_recorded_expectations(offset: 0, limit: 50)
# replay changing responses in order, keep only some request headers, move large bodies into files
client.retrieve_recorded_expectations(
  options: MockServer::RecordedExpectationsOptions.new.with_repeats_as_scenarios
                                                    .with_capture_headers('X-Tenant')
                                                    .with_extract_bodies_larger_than(1024)
)

client.clear_by_metadata('owner', 'team-a')
client.clear_by_metadata_key('ticket')
client.clear_unmatched_expectations
```

A String value is matched as text; any other value (`42`, `true`, an Array or Hash) is matched as
JSON, and `MockServer::ExpectationFilter::JSON_NULL` selects a stored JSON null. A blank key or
JSONPath, a `nil` value, negative paging, paging on clear, or a clear with no selection raises
`ArgumentError` before anything is sent, so a mistake can never clear every expectation.

## SRE Control Plane (SLO + Chaos)

Verify service-level objectives and drive scheduled chaos experiments against
the running MockServer:

```ruby
# Evaluate SLOs over a window. Returns the verdict Hash on PASS/INCONCLUSIVE;
# raises MockServer::VerificationError on FAIL (HTTP 406).
verdict = client.verify_slo(
  'name' => 'checkout-latency',
  'window' => 'PT5M',
  'minimumSampleCount' => 100,
  'objectives' => [
    { 'sli' => 'p99_latency_ms', 'comparator' => 'LESS_THAN', 'threshold' => 250 }
  ]
)

# Start a scheduled multi-stage chaos experiment (only one may be active).
client.start_chaos_experiment(
  'name' => 'payments-brownout',
  'stages' => [
    { 'durationMillis' => 60_000, 'profiles' => { 'payments.svc' => 'LATENCY' } }
  ]
)
```

SLO tracking must be enabled on the server (`sloTrackingEnabled=true`) or
`verify_slo` raises `MockServer::Error` on HTTP 400.

## Diagnose Matching

`explain(correlation_id)` tells you which expectation would serve a recorded request now and why
each expectation ahead of it was passed over; `debug_mismatch(request)` compares a request with
every active expectation; `explain_unmatched(limit:, suggest_fixes:)` explains recent requests
that matched nothing. Each returns a typed result (`RequestExplanation`, `DebugMismatchResult`,
`ExplainUnmatchedResult`) and raises `MockServer::Error` with the server's reason on failure.

```ruby
explanation = client.explain('abc-123-def-456')
explanation.skipped_expectations.each do |skipped|
  puts "#{skipped.expectation_id} #{skipped.reason}: #{skipped.explanation}"
end
```

## Interactive Breakpoints

The client supports matcher-driven interactive breakpoints over the callback WebSocket. Register a breakpoint matcher to pause forwarded/proxied exchanges at specific phases and inspect/modify/continue them via callback handlers.

### Register a breakpoint

```ruby
client = MockServer::Client.new('localhost', 1080)

# REQUEST phase only
bp_id = client.add_request_breakpoint(
  MockServer::HttpRequest.new(path: '/api/.*'),
  ->(request) { request }  # continue unchanged (or return HttpResponse to abort)
)

# REQUEST + RESPONSE
bp_id = client.add_request_and_response_breakpoint(
  MockServer::HttpRequest.new(path: '/api/.*'),
  ->(request) { request },                      # REQUEST handler
  ->(request, response) { response }            # RESPONSE handler
)

# All phases with stream frame handler
bp_id = client.add_breakpoint(
  MockServer::HttpRequest.new(path: '/stream/.*'),
  %w[REQUEST RESPONSE RESPONSE_STREAM INBOUND_STREAM],
  request_handler: ->(request) { request },
  response_handler: ->(request, response) { response },
  stream_frame_handler: ->(frame) { { 'action' => 'CONTINUE' } }
  # Other actions: MODIFY (with body), DROP, INJECT (with body), CLOSE
)
```

### Manage breakpoints

```ruby
# List all matchers
matchers = client.list_breakpoint_matchers  # {"matchers" => [...]}

# Remove a specific matcher
client.remove_breakpoint_matcher(bp_id)

# Clear all matchers
client.clear_breakpoint_matchers
```

## Start / Launch MockServer

The Ruby client can download and launch a local MockServer instance directly -- no Java installation and no Docker required. The launcher downloads a self-contained platform bundle (`mockserver-<version>-<os>-<arch>`) from the GitHub Release, verifies its SHA-256, caches it per-user, and starts it.

### Quick start

```ruby
require 'mockserver-client'

# Download (first run) and start MockServer on port 1080
handle = MockServer::BinaryLauncher.start(port: 1080)
puts "MockServer running on port #{handle.port}, PID #{handle.pid}"

# ... use MockServer ...

handle.stop
```

### Just ensure the binary is present

```ruby
launcher_path = MockServer::BinaryLauncher.ensure_launcher
```

### Specify a version

```ruby
handle = MockServer::BinaryLauncher.start(port: 1080, version: '9.2.0')
```

### API reference

| Method / Class | Description |
|---|---|
| `MockServer::BinaryLauncher.ensure_launcher(version:, log:)` | Download, verify, cache, and return the launcher path. Defaults to `MockServer::VERSION`. |
| `MockServer::BinaryLauncher.start(port:, version:, extra_args:, log:)` | Ensure the binary and start MockServer. Returns a `ServerHandle`. |
| `MockServer::BinaryLauncher::ServerHandle` | Handle to the running process. Methods: `stop(timeout:)`, `running?`. Attributes: `pid`, `port`, `launcher`. |

On Windows the launcher runs `mockserver.bat` through `cmd.exe` with every argument quoted, so characters such as `^`, `!` and parentheses reach MockServer unchanged. `start` raises `MockServer::Error` if the launcher path or an `extra_args` entry contains `"`, `<`, `>`, `|`, `&`, a line break or NUL, or if together they contain more than one `%` (which `cmd.exe` would expand as a `%NAME%` variable); it also needs a native Windows Ruby (mswin or mingw, such as RubyInstaller). No shell is used on Linux or macOS.

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

By default the launcher downloads the MockServer version matching this client gem (currently `MockServer::VERSION` from `lib/mockserver/version.rb`). Pass an explicit `version:` keyword to override.

## Using in tests (RSpec)

Require `mockserver/rspec` to get a shared context that provides a fresh
`MockServer::Client` per example and resets the server before and after each
example, so recorded requests, expectations and logs never leak between
examples:

```ruby
# spec_helper.rb
require 'mockserver/rspec'

# any spec tagged :mockserver gets a reset `mockserver` client
RSpec.describe 'my integration', :mockserver do
  it 'records the request' do
    # `mockserver` is the shared, reset client
    mockserver.when(
      MockServer::HttpRequest.request(path: '/hello')
    ).respond(
      MockServer::HttpResponse.response(body: 'world', status_code: 200)
    )
  end
end
```

Host and port default to `127.0.0.1:1080` and can be overridden with the
`MOCKSERVER_HOST` / `MOCKSERVER_PORT` environment variables, or by defining a
`mockserver_host` / `mockserver_port` `let` in your example group.

## License

Apache-2.0

## AI Assistant Integration

MockServer includes a built-in [MCP](https://modelcontextprotocol.io) (Model Context Protocol) server that enables AI coding assistants to create expectations, verify requests, and debug HTTP traffic programmatically.

- **MCP Endpoint:** `http://localhost:1080/mockserver/mcp`
- **AI Documentation:** [llms.txt](https://www.mock-server.com/llms.txt)
- **Setup Guide:** [AI Integration](https://www.mock-server.com/mock_server/ai_mcp_setup.html)
