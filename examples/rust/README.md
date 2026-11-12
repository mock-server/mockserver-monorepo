# MockServer Rust Examples

Runnable examples demonstrating the [MockServer Rust client](../../mockserver-client-rust/).

## Prerequisites

- **Rust 1.88+** (and Cargo)
- **MockServer running** on `localhost:1080` (e.g. `docker run -d -p 1080:1080 mockserver/mockserver`)
- Each example's `Cargo.toml` references the local crate via a `path`
  dependency, so no crate publish is needed. Or use the published crate:

  ```toml
  [dependencies]
  mockserver-client = "7.0"
  ```

## Examples

| Folder | Description |
|--------|-------------|
| [create_expectation](create_expectation/) | Create an expectation, send a test request, and verify it was received. |
| [modify_proxied_response](modify_proxied_response/) | Register a RESPONSE breakpoint that modifies a proxied response in-flight. |
| [scenario](scenario/) | Run the 5 canonical stateful-scenario flows (state machine, sequential cycling, timed transition, external trigger, cross-protocol) and assert each. |
| [load_scenario](load_scenario/) | Register, start, list, and stop a server-side load scenario (RATE ramp → VU hold → PAUSE) via the Load Scenario registry. |
| [callback](callback/) | Register an object (closure) response callback that derives the response from the request, plus a declarative class callback; assert both. |
| [parallel_search_mcp](parallel_search_mcp/) | Capture a remote MCP server (Parallel Search) through MockServer and replay it offline. |

Each folder contains a runnable Rust binary and its own `README.md` with
instructions.
