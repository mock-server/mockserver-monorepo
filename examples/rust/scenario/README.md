# Stateful Scenarios

## What it demonstrates

How to use the MockServer Rust client's stateful-scenario features. It runs the
5 canonical scenarios in sequence, resetting the server before each, exercising
the data plane with real HTTP requests and asserting every outcome:

1. **state_machine** — a login flow driven by expectation scenario fields
   (`scenario_name` / `scenario_state` / `new_scenario_state`). `GET /profile`
   returns 401 until `POST /login` advances the `LoginFlow` from `Started` to
   `LoggedIn`, after which `GET /profile` returns Alice.
2. **sequential_cycling** — one expectation with multiple `http_responses` and
   `ResponseMode::Sequential`. `GET /api/status` cycles 200, 503, 200, then
   wraps back to the first response.
3. **timed_transition** — the scenario REST helper with a timed auto-transition.
   `scenario("DeployFlow").set_timed("Deploying", 1000, "Deployed")` flips
   `GET /status` from `deploying` to `complete` after ~1s.
4. **external_trigger** — the scenario REST helper with an external trigger.
   `scenario("HealthFlow").trigger("Down")` flips `GET /health` from healthy
   (200) to down (503).
5. **cross_protocol** — `cross_protocol_scenario(...)` with an `HTTP_REQUEST`
   trigger. Observing `GET /events` advances `ConnFlow` to `Connected`, after
   which `GET /api/conn-status` (404 before) returns `connected`. The same
   mechanism advances scenarios from `DNS_QUERY`, `WEBSOCKET_CONNECT`, and
   `GRPC_REQUEST` events.

## Prerequisites

- Rust 1.88+ (and Cargo)
- MockServer running, discovered from the environment:
  - `MOCKSERVER_HOST` (default `localhost`)
  - `MOCKSERVER_PORT` (default `1080`)

  e.g. `docker run -d -p 1080:1080 mockserver/mockserver`

## Run

```bash
cargo run
```

Point at a different server with environment variables:

```bash
MOCKSERVER_HOST=mockserver.local MOCKSERVER_PORT=1080 cargo run
```

The program exits `0` only if all 5 scenarios pass, non-zero otherwise.

## Expected output

```
PASS: state_machine
PASS: sequential_cycling
PASS: timed_transition
PASS: external_trigger
PASS: cross_protocol

All scenarios passed.
```
