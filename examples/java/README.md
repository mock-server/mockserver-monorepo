# Java Examples

Java client and proxy examples for MockServer. Unlike the other tracks, this is a
**buildable Maven project** (`org.mock-server:mockserver-examples`). It is sample code —
**not** a published artifact (see the `### Removed` note in the repo `changelog.md`) — and
it is **not** a module of the `mockserver` reactor, which is precisely what keeps it
unpublished, since the release deploys that reactor. It is instead
built and tested standalone: its parent POM is `../../mockserver/pom.xml`, so it resolves the
reactor's just-installed `SNAPSHOT` artifacts from your local `~/.m2`. CI keeps it compiled
**and** tested by running a second, standalone Maven invocation immediately after the reactor
`install` (`scripts/buildkite_quick_build.sh`), so the examples still cannot silently rot.

## What it demonstrates

- **Mocking scenarios** — [`src/main/java/org/mockserver/examples/mockserver/`](src/main/java/org/mockserver/examples/mockserver/): response actions, forward actions, callbacks, OpenAPI expectations, request matchers, verification, recording/retrieval, interactive breakpoints (modify proxied exchanges).
- **Stateful scenarios** — [`src/test/java/org/mockserver/examples/mockserver/StatefulScenarioExamples.java`](src/test/java/org/mockserver/examples/mockserver/StatefulScenarioExamples.java): a single runnable, self-asserting test demonstrating all 5 canonical stateful-scenario features via the typed Java client — a login state machine (`withScenarioName`/`withScenarioState`/`withNewScenarioState`), sequential multi-response cycling (`withHttpResponses` + `ResponseMode.SEQUENTIAL`), a timed auto-transition (`scenario(name).set(state, ms, next)`), an external trigger (`scenario(name).trigger(state)`), and a cross-protocol trigger (`withCrossProtocolScenario(...)`). It starts its own embedded MockServer by default, or connects to one given by `MOCKSERVER_HOST`/`MOCKSERVER_PORT`, and resets the server before each scenario.
- **Load scenarios** — [`src/test/java/org/mockserver/examples/mockserver/LoadScenarioExamples.java`](src/test/java/org/mockserver/examples/mockserver/LoadScenarioExamples.java): a single runnable, self-asserting test exercising the **Load Scenario registry** via the typed Java client — register (`loadScenario`), start (`startLoadScenarios`), list (`loadScenarios`, asserting `RUNNING`), live status (`getLoadScenario`), stop (`stopLoadScenarios`) and clear (`clearLoadScenarios`) — driving a realistic multi-stage scenario (RATE ramp → VU hold → PAUSE with two Velocity-templated steps and a `withStartDelayMillis`). It starts its own embedded MockServer **with load generation enabled** (`configuration().loadGenerationEnabled(true)`), or connects to one given by `MOCKSERVER_HOST`/`MOCKSERVER_PORT` (which must itself set `-Dmockserver.loadGenerationEnabled=true`).
- **MCP capture and replay** — [`src/test/java/org/mockserver/examples/mockserver/ParallelSearchMcpExamples.java`](src/test/java/org/mockserver/examples/mockserver/ParallelSearchMcpExamples.java): records an MCP client's JSON-RPC calls (`initialize`, `notifications/initialized`, `tools/list`, `tools/call web_search` / `web_fetch`) to the live [Parallel Search MCP](https://docs.parallel.ai/integrations/mcp/search-mcp) server through a forward expectation (`forwardOverriddenRequest`), turns each recorded exchange (`retrieveRecordedExpectations`) into a replay expectation that matches on the JSON-RPC method (`jsonRpc(...)`, or `jsonPath(...)` on `params.name` for `tools/call`) and echoes the caller's id with a Velocity template (`$!{request.jsonRpcRawId}`), then replays offline and asserts that nothing was forwarded upstream. By default it replays only the shared fixture [`../json/parallel_search_mcp/replay_expectations.json`](../json/parallel_search_mcp/replay_expectations.json), with no network; set `MCP_CAPTURE=true` to capture live first. Parallel's free tier needs no API key; an optional `PARALLEL_API_KEY` is sent upstream as `Authorization: Bearer <key>` by the capture client only, is not written to the replay expectations (they keep only body matchers and response bodies), and is masked in MockServer's own log because capture first turns on `redactSecretsInLog` (`PUT /mockserver/configuration`; it stays on until MockServer restarts, or until you send `{"redactSecretsInLog": false}` to `PUT /mockserver/configuration` — or start MockServer with `MOCKSERVER_REDACT_SECRETS_IN_LOG=true`). Like the scenarios above it starts an embedded MockServer, or connects to `MOCKSERVER_HOST`/`MOCKSERVER_PORT`. Run it with `mockserver/mvnw -f examples/java/pom.xml test -Dtest=ParallelSearchMcpExamples` (prefix `MCP_CAPTURE=true` to capture); replay prints `replay: web_search -> 2 results, first https://www.mock-server.com/` and `replay: 0 requests forwarded to search.parallel.ai -- answered from recorded fixtures`.
- **Request filter and response transformer extensions** — [`src/main/java/org/mockserver/examples/mockserver/extensions/`](src/main/java/org/mockserver/examples/mockserver/extensions/): `ApiKeyRequestFilter` answers `401` to requests without the right `X-Api-Key`, lets CORS preflights through and removes the key from requests it lets through; `ServedByResponseTransformer` adds `X-Served-By` to every response and gives MockServer's own no-match `404` a JSON body. [`RequestFilterAndResponseTransformerExampleTest`](src/test/java/org/mockserver/examples/mockserver/extensions/RequestFilterAndResponseTransformerExampleTest.java) starts an embedded MockServer with both loaded (`configuration().extensions(...)`) and checks what a client gets, and also checks that the website's [Extensions page](https://www.mock-server.com/mock_server/extensions.html#request_filters_and_response_transformers) shows both classes exactly as they are compiled here, so the page cannot drift from working code. CI runs this test in the `mockserver-java` pipeline, which a change to the page or to `examples/java` also triggers. To run it locally from the repo root: `mockserver/mvnw -f examples/java/pom.xml test -Dtest=RequestFilterAndResponseTransformerExampleTest`.
- **Proxying with a range of HTTP libraries** — [`src/main/java/org/mockserver/examples/proxy/`](src/main/java/org/mockserver/examples/proxy/): Apache HttpClient, Google HTTP Client, JDK `HttpURLConnection`, Jersey, Jetty, Spring `RestTemplate`, and Spring `WebClient`.

## Prerequisites

- JDK 17+ and Maven.
- The MockServer `SNAPSHOT` artifacts this project depends on must be in your local `~/.m2`.
  Because `examples/java` is **not** a reactor module you cannot reach it with `-pl`/`-am`
  from the reactor (Maven reports `Could not find the selected project in the reactor`).
  Install the reactor first:

  ```bash
  # From the repo root — installs the reactor's SNAPSHOT artifacts into ~/.m2:
  ( cd mockserver && ./mvnw -pl mockserver-netty-no-dependencies,mockserver-client-java-no-dependencies,mockserver-testing -am install -DskipTests )
  ```

## Run

Build/test the examples **standalone** (this is exactly what CI does after the reactor
`install`):

```bash
# From the repo root — compile/package (uses the mvnw wrapper in mockserver/):
mockserver/mvnw -f examples/java/pom.xml package -DskipTests

# Run the example tests (they start a MockServer and exercise the scenarios):
mockserver/mvnw -f examples/java/pom.xml test
```

## Expected output

`BUILD SUCCESS`, with the example tests starting an embedded MockServer, registering
expectations, exercising each client/scenario, and verifying the recorded interactions.

---

For conceptual documentation see https://www.mock-server.com and the other tracks in
[`../`](../) (Node, Python, Ruby, curl, JSON, Docker Compose, WASM, chaos).
