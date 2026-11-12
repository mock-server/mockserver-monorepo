# Load Scenario Registry (Rust)

Demonstrates MockServer's **Load Scenario registry** with the Rust client. A load
scenario is a named, server-side traffic generator: register it once, then
start/stop it by name while the server generates synthetic traffic and reports
live status.

`src/main.rs` builds a realistic multi-stage scenario (`checkout-load`: a RATE
ramp → VU hold → PAUSE, with two Velocity-templated request steps and a
`start_delay_millis`) from the typed model, registers it, starts it, lists it
(asserting it is `RUNNING`), reads its live status, then stops and clears it. It
prints `PASS` and exits 0 on success.

## Client methods used

| Method | Endpoint |
|--------|----------|
| `load_scenario(&scenario)` | register/upsert (`PUT /mockserver/loadScenario`) |
| `start_load_scenarios(&names)` | start one/many — a `&[..]` of names (`PUT .../start`) |
| `load_scenarios()` | list all (`GET /mockserver/loadScenario`) |
| `get_load_scenario(name)` | one scenario + live status (`GET .../{name}`) |
| `stop_load_scenarios(&names)` | stop one/many; `&[]` = stop all (`PUT .../stop`) |
| `run_load_scenario(&scenario)` | register + start in one call |
| `delete_load_scenario(name)` / `clear_load_scenarios()` | delete one / clear all |

The typed model — `LoadScenario::new(...)`, `LoadProfile::of(...)`, `LoadStage`
(`::rate_ramp(...).max_vus(...)` / `::vu_hold(...)` / `::pause(...)`), `LoadStep`
and `Delay` — maps directly to the `LoadScenario` JSON contract. The registry
methods return the raw JSON (`serde_json::Value`).

## Prerequisites

- **Rust 1.88+** (`cargo`).
- **MockServer started with load generation enabled** (required to *start*
  scenarios — registering is always allowed):

  ```bash
  java -Dmockserver.loadGenerationEnabled=true \
    -jar mockserver-netty-jar-with-dependencies.jar -serverPort 1080
  # or: docker run -d -p 1080:1080 -e MOCKSERVER_LOAD_GENERATION_ENABLED=true mockserver/mockserver
  ```

  Starting without it returns HTTP 403.

## Run

```bash
MOCKSERVER_HOST=localhost MOCKSERVER_PORT=1080 cargo run
```

## Expected output

```
registered "checkout-load"
started "checkout-load"
listed: checkout-load=RUNNING
status: state=RUNNING stageType=RATE currentTarget=... requestsSent=...
stopped "checkout-load"
PASS
```

## Notes

- The per-step `request` is a standard MockServer `HttpRequest`.
- Stage VUs/rate are validated against the server's safety caps (default VUs ≤
  50, rate ≤ 500 req/s); raise the `mockserver.loadGeneration*` properties to go
  higher.
