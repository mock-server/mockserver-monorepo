# Parallel Search MCP Capture and Replay Example

## What it demonstrates

Recording a real remote MCP (Model Context Protocol) server and replaying it
offline. The upstream is the [Parallel Search MCP](https://docs.parallel.ai/integrations/mcp/search-mcp)
server at `https://search.parallel.ai/mcp`, but the pattern works for any MCP
server that speaks Streamable HTTP with JSON responses.

1. **Capture** (only with `MCP_CAPTURE=true`): a forward expectation
   (`HttpOverrideForwardedRequest` with an HTTPS `SocketAddress`) sends `/mcp`
   to `search.parallel.ai:443`. A small MCP client runs `initialize`,
   `notifications/initialized`, `tools/list`, `tools/call web_search` and
   `tools/call web_fetch` through MockServer, which records each exchange.
   The program then rewrites every recorded expectation as a replay expectation.
2. **Replay**: after a reset, the same calls are answered from expectations
   that match on the JSON-RPC method (`JSON_RPC` body matcher, or `JSON_PATH`
   on `params.name` for `tools/call`). A Velocity `HttpTemplate` echoes the
   caller's id with `$!{request.jsonRpcRawId}`. Without `MCP_CAPTURE` it replays
   the shared fixture [`../../json/parallel_search_mcp/replay_expectations.json`](../../json/parallel_search_mcp/replay_expectations.json).
3. **Check**: the program exits non-zero unless replay returns both tools,
   search results with URLs and fetched page content, every reply echoes its
   request id, and MockServer recorded no proxied traffic (nothing reached
   Parallel).

Recorded expectations are retrieved with a raw `PUT /mockserver/retrieve?type=RECORDED_EXPECTATIONS`
call, because the typed `retrieve_recorded_expectations` cannot parse recorded
JSON response bodies (they are objects, not strings).

The Parallel Search MCP server works without an API key (free tier). To capture
with your own key, set `PARALLEL_API_KEY`. Only the capture client sends it, upstream
as `Authorization: Bearer <key>`. It is not written to the replay expectations, which
keep only body matchers and response bodies. Before forwarding anything, capture turns
on MockServer's `redactSecretsInLog` setting (`PUT /mockserver/configuration`), so the
key is masked in MockServer's own log; the setting stays on until MockServer restarts, or until you send `{"redactSecretsInLog": false}` to `PUT /mockserver/configuration`.
If you start MockServer yourself, `MOCKSERVER_REDACT_SECRETS_IN_LOG=true` does the same
from the start. Replay needs no key and no network access.

## Prerequisites

- Rust 1.88+ (and Cargo)
- MockServer running on `localhost:1080` (override with `MOCKSERVER_HOST` / `MOCKSERVER_PORT`)
- Capture only: outbound HTTPS from MockServer to `search.parallel.ai`

## Run

```bash
# replay the shared fixture (offline)
cargo run

# capture from the live server, then replay the capture
MCP_CAPTURE=true cargo run
```

## Expected output

```
replay: loaded 5 expectations from replay_expectations.json
replay: Parallel Web Search MCP Server, tools ["web_search", "web_fetch"]
replay: web_search -> 2 results, first https://www.mock-server.com/
replay: web_fetch -> https://www.mock-server.com/ (595 chars)
replay: 0 requests forwarded to search.parallel.ai -- answered from recorded fixtures
```

With `MCP_CAPTURE=true`, three `capture:` lines come first, and replay reports
the live result counts.
