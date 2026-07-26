# Changelog
All notable and significant changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Fixed
- **`archiver.glob()` works again in `@mockserver/testcontainers` (Node), and CVE-2026-14257 stays
  closed.** The previous remedy for the `brace-expansion` denial of service (GHSA-mh99-v99m-4gvg,
  patched only in 5.0.8) was a blanket `"brace-expansion": "^5.0.8"` override. That resolved the whole
  tree to a single hoisted 5.0.8 and `npm audit` reported zero vulnerabilities — but 5.x changed the
  CommonJS export from a callable function to an object (`{ expand, EXPANSION_MAX, ... }`), while the
  minimatch copies actually installed (3.1.5, 5.1.9, 9.0.9) all call it as `expand(pattern)`. Every
  glob containing a brace therefore threw `TypeError: expand is not a function`, crashing
  `archiver.glob()`. The blast radius is narrower than it first looks — `testcontainers` copies files
  with `archiver.directory()`/`.append()`, which pass no brace pattern and still work — so what broke
  is brace globbing for anything in this module's runtime tree that does use it. The failure was
  invisible because minimatch short-circuits patterns with no `{`, so plain globs kept working and the
  unit suite stayed green. The override is now targeted: `readdir-glob` and `archiver-utils`' `glob`
  take `minimatch@^10.2.5`, which depends on `brace-expansion@^5.0.5` and is written against the new
  API, so both runtime copies land on the patched 5.0.8 with a matching minimatch. jest keeps its own
  `minimatch@3.1.5` + `brace-expansion@1.1.16` pairing and is untouched. `npm audit --omit=dev` still
  reports 0 vulnerabilities, and a new `dependency-integrity` unit test drives a brace pattern through
  both runtime minimatch copies and through a real `archiver.glob()` tar, plus asserts expansion stays
  bounded — it fails against the blanket override, so the silent half of this cannot return.
- **A forward `responseOverride` that replaces the body no longer inherits the upstream response's
  `Content-Length`, which truncated the response on the wire.** The override swapped the body but left the
  upstream header in place, so the client read only as many bytes as the body it replaced — a 34-byte
  override behind an upstream `Content-Length: 13` arrived as 13 bytes — or hung waiting for bytes that
  never came. The stale header is now dropped so the encoder recomputes it from what is actually written;
  a `Content-Length` set by the override itself, and `connectionOptions.contentLengthHeaderOverride`, are
  still honoured, and a header-only override (one that sets no body) is untouched. This affects every body
  override, and it was the remaining reason a `FILE` response body returned from a `responseOverride`
  still reached the client wrong after
  [#2450](https://github.com/mock-server/mockserver-monorepo/issues/2450): the file was materialised
  correctly and then cut short by the stale length. Covered by a Netty integration test that drives a real
  forward-with-override through a real upstream and asserts the bytes the client receives.
- **The cloud blob-store, async-broker and transparent-proxy CI steps no longer OOM-kill their own build
  before any test runs.** Each ran its Docker container with `--memory=4g`, but `mockserver/.mvn/jvm.config`
  pins the Maven JVM to `-Xmx6144m` and the wrapper prepends it to `MAVEN_OPTS`, so the `-am` dependency
  build was permitted a 6 GB heap inside a 4 GB cgroup and the kernel intermittently killed it with exit 137
  — losing the very coverage those fail-closed steps exist to guarantee. Raised each to `--memory=7g`, the
  value every other `./mvnw` step already uses and which fits the single-agent `c5.2xlarge`/`m5.2xlarge`
  default-queue instances with margin.

### Added
- **Clustered (Infinispan) expectation reload-on-startup is now proven end-to-end.** A new test
  (`ClusteredExpectationPersistenceReloadTest` in `mockserver-state-infinispan`) forms an in-JVM
  JGroups cluster consisting of a bare "fleet keeper" `InfinispanStateBackend` that stays up for the
  whole test plus a full MockServer node started with `stateBackend=infinispan`,
  `clusterEnabled=true` and `persistExpectations=true`. An expectation is created on that node over
  the wire, the persisted document is polled for through the *keeper's* backend (proving it really
  replicated across the REPL_SYNC blob cache), the node is then stopped completely, and a fresh node
  is started against the same cluster and the same `persistedExpectationsPath` — which must restore
  the expectation and MATCH a real HTTP request with it. The local persisted file is asserted to be
  empty first, so the restore cannot be coming from the filesystem-initializer route. The reload path
  in `ExpectationFileSystemPersistence` was already covered at unit level in `mockserver-core`
  (`ExpectationBlobStoreRestoreTest`, against an `InMemoryBlobStore`, with no server and no cluster)
  and end-to-end only against S3/MinIO behind a Docker gate; what no test proved is that a clustered
  node's `InfinispanBlobStore` is the store `HttpState` wires into that restore, nor that a real
  restarted member of a live cluster recovers the fleet's shared expectations. A second test sets
  `blobStoreRestoreTimeoutSeconds=0` (the documented way to skip the restore) and asserts the fresh
  node does NOT serve the expectation, which permanently pins the fact that no other mechanism —
  JGroups state transfer of the expectations cache, a stray invalidation event, or the local file —
  restores expectations when a node starts. Verified by a positive control: disabling the reload
  path in production makes the restarted node answer with an empty body and turns the test red.
- **The response-aware arm of the eviction false-green guard is now proven end-to-end over HTTP.** A new
  Netty integration test (`EvictedResponseVerificationIntegrationTest`) boots a real server with
  `maxLogEntries=2` and `failVerificationOnEvictedLog=true`, registers an expectation so a `GET
  /was-responded` exchange is recorded as a real `EXPECTATION_RESPONSE` request-response pair, then floods
  the bounded event log with further unmatched traffic so that pair is evicted. A subsequent
  `verify(request("/was-responded"), response().withStatusCode(418), never())` through the Java client must
  throw an `AssertionError` saying the **response** "could not be verified" because entries were discarded
  after reaching `maxLogEntries`. `MockServerEventLog` implements this guard twice — once in `verifyRequest`
  and once, through a completely separate counting path over recorded pairs, in `verifyResponse` — and only
  the request arm had an `*IntegrationTest`; the response arm was covered solely by an engine-level test
  against an in-process event log. The test uses `never()` because it is the simplest shape that reaches
  the guard: the guard sits on the PASS branch behind any asserted upper bound (`getAtMost() != -1` — so
  `atMost(n)`, `between(0,n)` and `exactly(0)` reach it too), whereas an `atLeast(1)`/`once()` verification
  of an evicted pair fails earlier with an ordinary "Response not found" message and proves nothing.
  `never()` is exactly the case a guard-less server would answer with a silent false green. The assertion pins the message to `Response could not be verified` so it cannot be
  satisfied by the request-side arm. Verified by a positive control (disabling only the response-side guard
  in production makes the verification pass silently and turns the test red).
- **The eviction false-green guard is now proven end-to-end over HTTP.** A new Netty integration test
  (`EvictedLogVerificationIntegrationTest`) boots a real server with `maxLogEntries=2` and
  `failVerificationOnEvictedLog=true`, records a `GET /was-called` request, then floods the bounded
  request-log ring with further traffic so the `/was-called` entry is evicted. A subsequent
  `verify(request("/was-called"), never())` through the Java client must throw an `AssertionError` whose
  message says the log "could not be verified" because entries were discarded after reaching
  `maxLogEntries` — proving the guard refuses to certify absence it can no longer see, rather than
  silently passing. Previously the guard was only covered by an engine-level test against an in-process
  `MockServerEventLog` and no `*IntegrationTest` exercised it across the wire. Verified by a positive
  control (disabling the guard in production makes `verify(never())` pass silently and turns the test
  red).
- **Custom gRPC response metadata and trailing metadata are now proven against a real `grpc-java`
  client.** Two new tests in `GrpcUnaryClientIntegrationTest` register an expectation whose gRPC
  response carries both custom response metadata authored with `withHeader(...)` and custom trailing
  metadata authored with `withTrailer(...)`, drive it with a live `grpc-java` client, and read the
  values back off the real `io.grpc.Metadata` objects the client receives (via a capturing
  `ClientInterceptor`, and via `StatusRuntimeException.getTrailers()` on the error path). The
  assertions are deliberately discriminating: the response metadata must arrive in the *initial
  headers* and not in the trailers, the trailing metadata must arrive in the *trailers* and not be
  folded into the initial headers, and both values must round-trip byte-for-byte including a value
  carrying `=`, `;`, `,` and spaces. Previously this behaviour was exercised only structurally
  (`EmbeddedChannel` / model-level assertions, which cannot tell a trailer emitted as a trailer from
  one folded into the headers) and by the existing `-bin` metadata tests, which deliberately accept
  the value from either side because a body-less unary response may legitimately collapse to
  Trailers-Only. Verified by positive controls: dropping the user-authored trailers turns both tests
  red, and dropping the user-authored response headers turns the header assertion red.
- **The `maxResponseBodySize` limit is now proven behaviourally against a real upstream.** A new
  integration test (`MaxResponseBodySizeIntegrationTest`) boots a forwarding MockServer configured with a
  4KB `maxResponseBodySize`, points it at a raw upstream socket that returns a 64KB body, and drives it
  over a plain client socket: the oversized body fails the forward and the client receives **502 Bad
  Gateway** with none of the payload relayed, while a control request whose body sits under the limit is
  forwarded intact. A third case repeats the oversized body with `Transfer-Encoding: chunked` and no
  `Content-Length`, proving the cap is enforced against the bytes actually accumulated by the forward
  client's aggregator rather than merely against a declared header. Previously this documented,
  memory-protecting bound — read whenever a forward-client pipeline is built — had no behavioural
  coverage at all, so a regression that dropped the wiring (or passed an unbounded value) would have
  removed the limit silently; only the inbound analogue `maxRequestBodySize` was verified. The new test
  covers the HTTP/1.1 forward aggregator; the HTTP/2 forward path reads the same property (for the
  per-stream aggregator and to derive the client's `maxFrameSize`) and remains uncovered.
  `maxResponseBodySize` accordingly moves from `ENFORCEMENT_EXEMPT` to `ENFORCEMENT_VERIFIED` in
  `ConfigurationEnforcementClassificationTest`. Verified by a positive control (restoring an unbounded
  aggregator lets the oversized body through with a 200 and turns both over-limit assertions red).
- **The Ruby client now proves live SSE stream consumption over the wire.** New integration examples
  (`spec/integration_spec.rb` → `SSE streaming`) register an `httpSseResponse` expectation via the Ruby
  client against a running MockServer, then open a real streaming HTTP consumer and assert every `data:`
  frame arrives in order, that the reconstructed multi-delta message matches, and that a multi-line
  `data:` payload survives the framing intact (`Content-Type: text/event-stream`). Previously the Ruby
  suite only asserted the JSON keys of a built streaming expectation (`a2a_spec`) and never consumed a
  live SSE stream, so a silent server-emission or client-parsing drop would have gone uncaught. Verified
  by a positive control (dropping events from the emitted stream turns the received-frames assertion red).
- **The `assumeAllRequestsAreHttp` protocol-detection fallback now has direct unit coverage.** Two
  paired `EmbeddedChannel` tests in `DirectProxyUnificationHandlerTest` drive
  `PortUnificationHandler.decode()` with an HTTP request using a non-standard method (`PURGE`, which is
  not one of GET/POST/PUT/HEAD/OPTIONS/PATCH/DELETE/TRACE/CONNECT): with
  `assumeAllRequestsAreHttp=true` the full HTTP pipeline is added (rather than falling to binary request
  proxying), and with the flag disabled the HTTP codec is not added — proving the flag is the only
  difference. Previously the fallback branch was exercised only by a live-socket integration test and
  the config getter's own unit test, so the `EmbeddedChannel` protocol-detection path for the flag was
  unexercised.
- **HTTP/3 streaming response bodies are now proven end-to-end through the action pipeline with a real
  QUIC client.** A new integration test (`Http3StreamingForwardIntegrationTest`) registers a `forward`
  expectation on the HTTP/3 port (with `streamingResponsesEnabled`) pointing at an upstream Server-Sent
  Events stream that serves an early event immediately and withholds the late event for 1.5s, then drives
  it with a live Netty QUIC client and asserts both events arrive as SEPARATE DATA frames spread across
  that delay — proving the streaming relay funnels through `HttpActionHandler` ->
  `ResponseWriter.writeResponse` -> `Http3ResponseWriter.writeStreamingResponse` and emits chunks
  incrementally. Previously `Http3StreamingIntegrationTest` drove `Http3ResponseWriter` directly from a
  hand-built QUIC server (bypassing expectation matching), and `Http3MockingMatrixIntegrationTest`
  exercised the real pipeline over QUIC but only with non-streaming actions, so incremental delivery of a
  streamed body through the full pipeline was untested. QUIC-gated like the sibling HTTP/3 tests so it
  skips cleanly where the native transport is unavailable.
- **The dashboard's Monaco code editor is now proven in a real browser end-to-end.** A new Playwright
  e2e test (`mockserver-ui/e2e/dashboard.spec.ts`) drives the actual bundled Monaco editor in the
  served dashboard's composer against a live MockServer: it asserts Monaco's own DOM
  (`.monaco-editor` / `.view-lines`) renders, authors a JSON response body via real editor input,
  raises and clears a live validation marker from Monaco's JSON language web worker, then registers
  the mock and confirms the Monaco-authored body round-trips to the server (present in
  `PUT /mockserver/retrieve` and served verbatim on the matching request). Previously the 178
  jsdom/vitest specs globally replaced Monaco with a bare `<textarea>`, so nothing exercised the real
  editor's tokenisation, web-worker validation, or DOM.
- **SOCKS4 CONNECT tunnelling is now proven end-to-end over a real socket.** A new socket-level
  integration test (`NettyHttpProxySOCKS4IntegrationTest`) performs a raw SOCKS4 CONNECT handshake
  against a bound MockServer targeting a loopback `EchoServer`, then sends an HTTP GET through the
  granted tunnel and asserts the EchoServer received the request and returned 200 (bytes relayed by
  `Socks4ConnectHandler`). Previously SOCKS4 was only exercised by an `EmbeddedChannel` unit test
  (`Socks4ProxyHandlerTest`, which asserts handler removal) while every real-socket proxy integration
  test used SOCKS5, so nothing drove the SOCKS4 relay path over the wire.
- **Per-host forward-proxy client-certificate selection is now proven at a real TLS handshake.** A new
  integration test (`ForwardWithCustomClientCertificateByHostIntegrationTest`) configures
  `forwardProxyClientCertificatesByHost` to present two independent client certificates (each backed by
  its own CA) keyed by host, then forwards through MockServer to two secure upstream `EchoServer`s that
  each `REQUIRE` client auth and trust only ONE of the two client CAs. Because the host string is the
  cert-mapping key while the connection target is fixed independently by the forward port, the same
  upstream is reached under both host keys: the mapped cert is accepted (200) and the mismatched cert is
  rejected at the handshake (502). This makes the presented client certificate a load-bearing assertion,
  verified by a positive control (degrading the mapping to always present cert A flips host B's accepted
  case to 502). Previously `NettySslContextFactoryTest` asserted only `SslContext` identity/distinctness
  and the pure resolver -- nothing drove the per-host cert to an actual mTLS handshake.
- **LLM streaming physics for Gemini, Ollama, and Bedrock are now proven over a real socket.** Streaming
  physics over the wire was previously e2e-tested only for Anthropic and OpenAI (both SSE); Gemini, Ollama,
  and Bedrock rested on self-derived golden JSONL plus codec unit tests, with no socket streaming e2e. Three
  new tests in `LlmAgentLoopE2eTest` serve a streaming `httpLlmResponse` for each provider, connect a real
  socket client, and assert both that the wire `Content-Type` is the provider's streaming media type
  (`text/event-stream` for Gemini SSE, `application/x-ndjson` for Ollama NDJSON,
  `application/vnd.amazon.eventstream` for Bedrock AWS event-stream binary framing) and that the text
  reconstructed by concatenating the streamed deltas — Gemini `candidates[].content.parts[].text`, Ollama
  `message.content`, and Bedrock's base64-wrapped Anthropic `text_delta` fragments decoded from the
  CRC32-validated binary event-stream messages — equals the completion text exactly. The Bedrock case
  de-chunks the HTTP/1.1 chunked body and decodes the binary framing end to end.
- **The PHP client's fidelity harness now gates the TYPED model, not just raw replay.** The existing
  `RoundTripFidelityTest` deserialises each shared fixture with `Expectation::fromArray()`, which stores
  the decoded array verbatim in `rawData` and replays it unchanged -- so it records zero gaps for every
  fixture BY CONSTRUCTION and can never detect a field the typed builders (`HttpResponse`, `HttpForward`,
  `HttpError`, `HttpRequest`) fail to model. A new `TypedRoundTripFidelityTest` closes that tautology:
  for every shared fixture it reconstructs each action/matcher block THROUGH the typed model (reflection
  copies across only the properties each class declares, recursing into declared nested typed objects,
  then serialises via the class's own `toArray()`) and diffs the rebuilt structure back against the
  fixture -- the server-schema side of the contract -- so any server field the typed model drops surfaces
  as a concrete diff path derived from the corpus, never from the client's own key list. This immediately
  documented five real request-matcher gaps the raw harness hid (`dnsClass`/`dnsName`/`dnsType`,
  `pathParameters`, `protocol`, plus NottableString `method`/`path`), each pinned in a per-field gap
  ledger with a stale-entry ratchet, while the `httpResponse`/`httpForward`/`httpError` models are proven
  to cover their entire fixture surface. A positive-control test proves the gate fires when a typed
  builder drops a field it is supposed to carry (removing `statusCode` from `HttpResponse::toArray()`
  turns the suite red for every fixture carrying it, and green again on restore) -- the exact regression
  the raw-replay harness cannot catch. Shared comparator logic is extracted to `FidelityComparator`.
- **The Go client's FORWARD and ERROR response actions are now proven over the wire.** New integration
  tests (`response_action_integration_test.go`) register a `httpForward` and a `httpError`
  (`dropConnection`) action via the Go client and drive real requests that assert the SERVER actually
  performs them: the forwarded request loops back through a match-once, higher-priority self-forward to
  a distinct upstream response body (needing no externally-reachable upstream, so it runs identically in
  the CI sibling-container harness and against a locally port-mapped server), and the error endpoint
  drops the connection at the transport level (no HTTP response) while a control endpoint on the same
  server still answers cleanly. Previously the Go client's response-action coverage was builder/JSON
  only -- no test drove a forward or error action to completion over a socket.
- **Rust client wire tests proving the server actually enforces negation matchers and performs
  response actions.** The Rust integration suite previously only asserted control-plane serialization
  (`matcher_value_tests.rs`) and registered a forward expectation it never drove (`test_forward_expectation`
  "won't actually forward"), so nothing proved a running MockServer acted on either. Three new
  `#[ignore]`d integration tests drive a live server over the data plane via a dependency-free raw
  socket: `test_negation_matcher_enforced_over_wire` registers a `NottableString` negation (bare
  `"!foo"`, explicit `MatcherValue::not_literal`, and an escaped literal `"!foo"`) and asserts the
  server matches a non-`foo` value (200) while excluding `foo` (404) — and that an escaped `"!foo"`
  matches literally rather than as a negation; `test_forward_action_actually_forwards` registers a
  higher-priority run-once FORWARD that loops back to the server's own port plus a lower-priority
  fall-through RESPOND, and asserts the distinctive fall-through body is returned only if the forward
  genuinely executed (topology-independent — no external upstream); and
  `test_error_action_actually_returns_raw_bytes` registers an ERROR action and asserts the server
  writes the configured raw bytes back. Run in CI by the existing `rust-integration-test` step.

- **Wire-level .NET client test coverage for NottableString header negation and for
  forward/error response actions.** The .NET integration suite asserted expectation *creation* but
  never proved the server honours what the client sends: there was no test that a `!foo` header
  matcher (`MatcherValue.NotLiteral`) is transmitted and enforced over the wire, nor that a
  registered forward or error action is actually performed. A new `WireBehaviorTests` drives real
  requests through a running MockServer (reached via the existing `MOCKSERVER_URL` harness) to prove:
  a "not foo" header matcher matches a non-`foo` request (200) and rejects a `foo` request (404); the
  escaped literal `MatcherValue.Literal("!foo")` matches only a header whose value really is `!foo`;
  a forward action is genuinely forwarded (the path is received twice — original plus the re-entered
  forward — not merely served directly); and an error action actually drops the connection (a
  transport failure, not an HTTP status). Each assertion was confirmed to redden when the
  corresponding client behaviour is degraded.
- **Live-broker test coverage proving Kafka SASL credentials reach and are enforced by a real
  broker.** Kafka SASL/SSL security was only asserted at the property-map level
  (`KafkaMessagePublisherSecurityTest`) and every live-broker Kafka integration test used a plaintext
  bootstrap, so nothing proved the configured credentials actually authenticate against a broker. A
  new `KafkaSecurityLiveBrokerIntegrationTest` starts a Testcontainers Kafka whose external listener
  is `SASL_PLAINTEXT`/`PLAIN` with a broker-side JAAS config that knows a single credential, then
  drives MockServer's `KafkaMessagePublisher` with a `KafkaSecurity`: a correctly-credentialed
  publisher publishes successfully and the message is read back by a matching credentialed consumer,
  while a wrong-password publisher is rejected with an authentication exception (proving enforcement,
  not merely that plaintext works). Docker-gated so it SKIPS cleanly when Docker is unavailable.
- **Credential-enforcement test coverage for MQTT security against a secured live broker.** MQTT
  `MqttSecurity` credentials were only asserted at the options-carrier unit level
  (`MqttSecurityOptionsTest`), while the sole live Mosquitto integration test ran an
  `allow_anonymous` plaintext broker — so nothing proved credentials are actually applied and
  enforced on the wire. A new `MqttTlsLiveBrokerIntegrationTest` drives MockServer's MQTT publisher
  against a Testcontainers Mosquitto broker configured with a `password_file` and
  `allow_anonymous false`: it asserts that a publisher wired with the correct `MqttSecurity`
  username/password authenticates and delivers a message to an authenticated subscriber, and that a
  publisher with the wrong password (and one with no credentials) is rejected by the broker at
  CONNECT. Docker-gated so it skips cleanly when Docker is unavailable.
- **Live-broker test coverage for the AsyncAPI control-plane `load()` → publish-on-load path.** The
  control-plane's broker-connecting path (`createBrokerConnections` / `publishOnLoad`) was untested
  against a real broker — `AsyncApiControlPlaneImplTest` loads without a reachable broker (asserting
  `publishers=0`), and the endpoint IT covers only the broker-less endpoints. A new Docker-gated
  `AsyncApiControlPlaneLiveBrokerIntegrationTest` (Testcontainers Kafka) drives `load()` with a real
  `brokerConfig`, `publishOnLoad:true` and `consume:true`, then proves the control-plane genuinely
  connected and published by consuming the on-load message with a plain third-party Kafka client and
  asserting `status()` reports `publishers>0`, a subscriber, and the recorded round-tripped message.
- **Over-the-wire test coverage for an LLM refusal preset served with a rate-limit quota.** The LLM
  refusal presets, provider-specific rate-limit headers, and stateful request-count quota were only
  asserted at the body-builder / handler-unit level; nothing drove them through a running server. A
  new `LlmRefusalQuotaRateLimitIntegrationTest` serves an `httpLlmResponse` configured with an
  Anthropic refusal preset and a 2-request quota, then asserts on the raw socket response that the
  first two requests return a `200` refusal envelope (`stop_reason:"refusal"`) carrying the
  `anthropic-ratelimit-requests-*` headers, and that the third (over-quota) request flips to a `429`
  `rate_limit_error` envelope with the exhausted rate-limit headers and `Retry-After`.

### Fixed
- **The `testcontainers-mockserver` (Python) port assertions no longer break against testcontainers
  4.15.0, which keys `DockerContainer.ports` by `str(port)` rather than `int`.** `with_exposed_ports`
  now stores `self.ports[str(port)] = None` (4.15.0 types the attribute as
  `dict[str, Optional[int]]`), so the suite's `assert 1080 in container.ports` started failing with
  `assert 1080 in {'1080': None}` and took five tests — and the whole `MockServer Python` pipeline,
  and with it the umbrella `MockServer` build — red on `master`. The tests now read the exposed ports
  through a normaliser that parses each key to an `int` (tolerating a `"1080/tcp"` protocol suffix),
  so they assert the same thing under either key style. This also closes a latent false green: the
  `assert MOCKSERVER_PORT not in container.ports` check in `test_replaces_default_port` passed
  trivially once the keys became strings, and so would no longer have caught `with_server_port`
  failing to drop the previously exposed port. Only the tests changed — `MockServerContainer` itself
  was already correct, as `get_exposed_port` takes an `int` and normalises internally.
  `<blobStoreKeyPrefix>/<file name>` instead of under the writing machine's absolute local path, and a
  `blobStoreKeyPrefix` that does not end in a separator is now treated as a folder-style prefix instead
  of being glued straight onto the key (`mockserver` + `x.json` was `mockserverx.json` and is now
  `mockserver/x.json`). Anything persisted by an earlier version is stored under the OLD name and will
  NOT be restored after upgrading — the one-line migration is below.** The blob key was the ABSOLUTE
  local `persistedExpectationsPath` (for example `/var/folders/.../persistedExpectations.json`) and the
  configured `blobStoreKeyPrefix` was concatenated onto it with plain string addition. With the prefix
  shape the documentation recommends — `blobStoreKeyPrefix="mockserver/"`, with a trailing separator —
  that composed `mockserver//var/folders/.../persistedExpectations.json`, and MinIO rejects the doubled
  `//` outright with HTTP 400, "Object name contains unsupported characters", so under that one
  configuration nothing was ever persisted and consequently nothing could be restored on restart. Under
  every other prefix shape the write SUCCEEDED and restore worked — a leading `/` is a legal byte in an
  S3 object name, and the read composed exactly the same name back — but the object was then named after
  the writing container's local filesystem layout, so a second instance that resolved
  `persistedExpectationsPath` to a different absolute path (started from a different working directory,
  or in a different container) looked under a different name and silently restored nothing. The key is
  now derived by the new shared `org.mockserver.state.BlobKeys` helper in `mockserver-core`: for every
  store other than `FilesystemBlobStore` the key is the FILE NAME of `persistedExpectationsPath` alone,
  and prefix and key are joined with exactly one separator, with any leading separator dropped and any
  repeated separators collapsed, so every prefix shape a user can configure — unset, `mockserver`,
  `mockserver/`, `/mockserver/` — now produces the same valid object name
  (`mockserver/persistedExpectations.json`). That normalisation is applied for all
  `put`/`get`/`list`/`delete` operations wherever `blobStoreKeyPrefix` is applied, so it renames EVERY
  blob key, not only the persisted-expectations document. `FilesystemBlobStore` is unaffected: it
  interprets the key as a file path, so it keeps the absolute path and writes exactly the file it always
  did. **Upgrading:** because the old key always embedded the absolute local path and the new key is the
  bare file name, the object name changes for every non-filesystem persistence user, under every prefix
  shape and on every platform — there is no configuration in which the old name is preserved. On the
  first start after upgrading, the restore looks under the new name, misses (logged at `INFO` with the
  name it looked for), and the instance starts with no restored expectations; the next expectation change
  then writes a fresh object under the new name and leaves the old one behind. To carry existing state
  across the upgrade, copy or rename the object once before starting the new version, for example
  `aws s3 mv s3://<bucket>/<prefix>/<old absolute path> s3://<bucket>/<prefix>/<file name>` — otherwise
  accept the miss and let the first expectation change re-create it. One long-standing footgun goes away
  with the change: restoring after a restart no longer requires the two instances to resolve
  `persistedExpectationsPath` to the same absolute path, only to the same file name. Deployments that
  must NOT share state within one bucket should give each its own `blobStoreKeyPrefix` (or its own file
  name). Proven by a Docker-gated MinIO round trip that writes and then reads back an expectation with a
  trailing-slash `blobStoreKeyPrefix` (the exact configuration that returned HTTP 400 before), a MinIO
  put/get/list/delete round trip across all four prefix shapes, and Docker-free unit coverage of the key
  composition and of the key the persistence layer derives.
- **The configuration enforcement-evidence guard no longer certifies evidence it cannot see.**
  `ConfigurationEnforcementClassificationTest` records, for every risky configuration property, the
  `Class#method` test that proves an instance-set value changes observable behaviour. It validated those
  pointers by loading the class — but it runs in `mockserver-core`, so any pointer naming a test in a
  sibling module was silently skipped on `ClassNotFoundException`. That exempted precisely the most
  valuable evidence, the end-to-end Layer C pointers: renaming, moving or deleting the referenced test
  left a dangling pointer and the guard still passed green, for `maxRequestBodySize`,
  `maxResponseBodySize`, `wasmEnabled`, `redactSecretsInLog`, `clusterEnabled`, `dnsEnabled`,
  `grpcBidiStreamingEnabled`, `http3ConnectUdpEnabled` and `transparentProxyEnabled`. A class that
  cannot be loaded is now resolved
  by locating its `.java` source under any module's `src/test/java` and asserting the file declares both
  the class and the referenced method, so cross-module pointers are checked in a full reactor build and
  when only some modules are built. The guard fails closed: a pointer resolvable by neither route is now
  a failure naming the dangling pointer, never a silent skip. Anti-vacuity assertions in the spirit of
  the sibling `ConfigurationCallSiteGuardTest` keep the scan honest — the set of pointers resolved by
  source scan must match the declared cross-module ratchet exactly, `mockserver-netty` and
  `mockserver-state-infinispan` must both have contributed, and classpath resolution must still cover
  the bulk of the pointers — so a scan that resolves nothing cannot pass. Verified by degrading a real
  `mockserver-netty` test method name: the guard now fails with a message naming the dangling pointer,
  where the previous version passed green with the identical defect in place.
- **gRPC trailing metadata is no longer silently dropped over HTTP/3.** Every trailer an expectation
  authored — `response().withTrailer("x-request-cost", "42")`, and the gRPC chaos profile's
  `customTrailers` — reached an HTTP/1.1 or HTTP/2 client but **never reached an HTTP/3 client at
  all**, in every branch of the HTTP/3 gRPC response path (with a body and body-less, and both with
  and without proto descriptors loaded). The same expectation therefore produced different trailing
  metadata depending only on which transport the client happened to use, and there was no error or
  warning anywhere to indicate the loss — a test asserting on trailing metadata over HTTP/3 simply
  saw nothing. The cause was that `Http3GrpcResponseWriter` builds its HTTP/3 frames by hand rather
  than through `MockServerHttpResponseToFullHttpResponse.mapResponseWithTrailers` (which is what
  carries trailers on the other transports), and `GrpcHttp3Adapter.buildTrailingHeadersFrame` /
  `buildTrailersOnlyFrame` populated only `grpc-status` and `grpc-message`; the response's own
  trailers were never read. They are now emitted on the terminal frame: on the trailing HEADERS
  frame when the response has a body, and folded into the Trailers-Only frame when it does not,
  which is the shape gRPC defines for that form (`HTTP-Status Content-Type Trailers`, where
  `Trailers` includes custom metadata) and leaves the framing unchanged — there is still exactly one
  terminal frame, written with `SHUTDOWN_OUTPUT`, so an added trailer cannot cost the response its
  end-of-stream marker the way it did on HTTP/2 before the fix above. A user-authored trailer cannot
  override or spoof the transport's own status: the new shared
  `GrpcResponseStatusResolver.passThroughTrailers` (the trailer twin of `passThroughHeaders`)
  excludes `grpc-status`, `grpc-message` and `grpc-status-name`, mirroring the exclusion the HTTP/2
  path makes in `remainingTrailers`, and also excludes the connection-specific fields,
  `content-length`/`content-type` and pseudo-header names that RFC 9114 forbids in a trailer section.
  Trailer field names are lower-cased and CR/LF stripped from values before reaching the frame,
  because HTTP/3 field names must be lower-case and Netty's `DefaultHttp3Headers` rejects an
  upper-case one by throwing — so an expectation authoring `withTrailer("X-Request-Cost", …)` would
  otherwise have taken the client's entire response down rather than dropping a single field.
  Verified over the wire by two new tests in `Http3GrpcIntegrationTest` that drive a live in-JVM
  Netty QUIC client and read the metadata off the HEADERS frames it actually received, asserting
  which side each value arrived on (a trailer must not be folded into the initial headers, a
  response header must not be repeated as a trailer) and asserting the HEADERS-frame count so the
  metadata cannot arrive at the cost of correct framing, plus adapter-level coverage in
  `GrpcHttp3AdapterTest`. Positive control: neutering the trailer pass-through in production turns
  all seven new assertions red with the trailer absent.
- **A FILE response body is now served verbatim (and templated) on every response path, and a FILE
  request body is now actually matched (#2450).** A response whose body is a `FILE` (a `filePath` with
  no template engine) was previously read on the static response action only; the same FILE body
  returned from an object callback, a class callback, a response template, or a forward
  `responseOverride` reached the wire unread, emitting the file *path* string instead of the file
  *contents*. Materialisation now lives in a single shared `FileBodyMaterialiser` invoked from the two
  response-write funnels (`writeResponseActionResponse`, covering the static, object-callback,
  class-callback, response-template and SSE paths, and `writeForwardActionResponse`, covering the
  forward `responseOverride`), so all five producers — and the shared WAR/servlet path — serve the file
  contents. Templated FILE bodies (a `FileBody` carrying a Velocity/Mustache `templateType`) are rendered
  against the request on these paths too, not only verbatim ones; a text content type yields the decoded
  string and a binary or absent content type yields the raw bytes intact. A missing or unreadable file
  now produces a clean, logged `500` whose body does not leak the path, instead of a broken connection or
  the path string. Separately, a `FILE` body used for **request matching** had no case in
  `BodyMatcherBuilder`, so the body constraint was silently ignored (it matched any body); it now matches
  the request body against the exact file contents (string or binary). This resolves the earlier
  "static response only" caveat.
- **A gRPC error response carrying custom trailing metadata no longer loses its status.** On HTTP/2 a
  body-less gRPC response is collapsed into the gRPC Trailers-Only form, which moves `grpc-status`
  into the initial HEADERS frame and relies on that frame being end-of-stream. When the expectation
  also authored a custom trailer with `withTrailer(...)`, that trailer kept a separate trailing
  HEADERS frame alive, so the initial frame was no longer end-of-stream: a real client read it as
  ordinary headers (where `grpc-status` is ignored) and then found no status at all in the terminal
  frame, failing the call with `UNKNOWN: missing GRPC status`. In other words, adding a single
  trailer to an error response destroyed the error — the caller lost both the status code and the
  message. `GrpcToHttpResponseHandler.asTrailersOnlyIfHttp2` now skips the Trailers-Only collapse
  whenever any user-authored trailer remains, keeping `grpc-status`/`grpc-message` in the trailing
  HEADERS frame alongside the custom metadata, which is the correct shape in that case. The same fix
  covers the gRPC chaos fault path, which produced the byte-identical broken shape: a fault response
  configured with `customTrailers` emits them as real trailers alongside `grpc-status`/`grpc-message`
  on a body-less response, so a chaos-injected error over HTTP/2 also reached the client as
  `UNKNOWN: missing GRPC status` instead of the configured status. Found by the new real-client
  trailing-metadata coverage described under Added.
- **gRPC-Web now re-frames matched-expectation responses correctly over a real HTTP/1.1 socket, and
  is covered by an over-the-wire integration test.** Every previous gRPC-Web test drove the handler
  through an `EmbeddedChannel` and set `x-grpc-web-content-type` directly on the response, so none
  exercised the actual mock-matching path: there the marker lives on the request only and was lost,
  and a matched expectation went back to a browser client as `application/grpc` with `grpc-status` in
  HTTP trailers a gRPC-Web client cannot read. The original request content-type is now retained in
  the per-stream `GrpcPendingRequests` record alongside the resolved service/method, so
  `GrpcToHttpResponseHandler` re-frames the response as gRPC-Web (length-prefixed message frame + a
  `0x80` trailer frame carrying `grpc-status` in the body, base64-encoded for the `-text` variant).
  A new `GrpcWebOverTheWireIntegrationTest` posts a real `application/grpc-web` and
  `application/grpc-web-text` framed request to a running server over a raw socket and asserts on the
  exact bytes a browser client would receive.
- **The AsyncAPI control-plane HTTP endpoints now have an over-the-wire integration test.**
  `PUT /mockserver/asyncapi`, `GET /mockserver/asyncapi` and `PUT /mockserver/asyncapi/verify` were
  only exercised at the orchestrator/control-plane level, so a regression in the Netty →
  `HttpState` → `AsyncApiControlPlaneRegistry` routing or response wiring would not have been caught.
  A new `AsyncApiControlPlaneIntegrationTest` boots a real MockServer and drives all three endpoints
  over a raw socket without any live broker: it asserts the load response (`201`, `loaded:true`,
  channel count, zero publishers/subscribers), the status response (`200`, channels, counts, and the
  empty/unloaded case), and the broker-less verify verdict (`406` with the "at least 1 … found 0"
  failure detail) plus the blank-body `400`.
- **A `StreamingBody` response delivered to a real HTTP/2 inbound client is now covered end-to-end.**
  `NettyResponseWriter.writeStreamingResponse` re-stamps the request's HTTP/2 stream id onto the
  streaming response head (the field is not part of the copied header multimap) and flushes each chunk
  as it arrives, but no test drove that path with a real HTTP/2 client — `Http2SseStreamingIntegrationTest`
  covered only the SSE sibling and explicitly noted the `StreamingBody` case was untested, while the
  existing streaming-relay tests drive an HTTP/1.1 inbound socket. A new `Http2StreamingBodyIntegrationTest`
  drives a real prior-knowledge h2c multiplex client through a `streamingResponsesEnabled` forward
  MockServer to an SSE upstream and asserts on the frames the client receives on its OWN request stream:
  both events arrive (proving the stream-id stamp — the #2419 class), and the early event's DATA frame
  arrives promptly as one of at least two separate, in-order DATA frames rather than being buffered into
  one. Degrading the write path to buffer chunks until stream completion was verified to make the
  incremental-delivery assertion go red.
- **PROXY-protocol destination resolution is now proven to drive transparent-proxy forwarding over a real
  socket.** `ProxyProtocolOriginalDestinationHandler` was only exercised via `EmbeddedChannel`, which asserts
  the handler sets the `REMOTE_SOCKET` channel attribute but never that this attribute actually chooses the
  forward target end-to-end. A new non-privileged loopback `ProxyProtocolForwardingIntegrationTest` runs
  MockServer with `transparentProxyEnabled=true` and no fixed remote, opens a raw socket, writes a valid
  PROXY v1 `TCP4` header naming a loopback `EchoServer` as the destination followed by a plain GET whose
  `Host` header points at an unrelated (closed) decoy port, and asserts the EchoServer reflects the request
  back — proving the PROXY-protocol `REMOTE_SOCKET`, and not the `Host` header, drives forwarding. The test
  needs no `NET_ADMIN`/privileged capability because the PROXY-protocol header is an application-level byte
  prefix. Verified as a genuine regression guard by a positive control: ignoring the PROXY-header
  destination turns the forwarding assertions RED, restoring it returns them GREEN.
- **A FILE response body with no template engine now serves the file contents, not the file path (#2450).**
  A static response with a body of type `FILE` and a `filePath` but no `templateType` previously returned
  the literal file-path string as the response body instead of the file's contents; only adding a
  `templateType` (e.g. `MUSTACHE`) caused the file to actually be read. `HttpResponseActionHandler` now
  reads any FILE body that is not template-rendered — no `templateType`, or an unsupported one such as
  JavaScript — and serves its contents verbatim, preserving the declared content type. (A FILE body
  returned from an object/class callback, from a response template, or as a forward `responseOverride`
  bypasses this handler and is addressed separately.) Binary files (images, PDFs,
  archives, identified via the content type) are served as raw bytes so they are not corrupted by
  charset decoding; text files are served as-is with no template processing. A missing file fails the
  same way as the templated path.
- **The VS Code extension (`mockserver-vscode`) now compiles under TypeScript 7.** TypeScript 7 no
  longer auto-includes every installed `@types/*` package, so `@types/node`'s ambient declarations
  (the `path`/`fs`/`crypto`/`child_process` module globals, the `NodeJS` namespace, `Buffer`,
  `process`, `console`) were dropped and `tsc -p ./` failed with 97 errors. The extension's
  `tsconfig.json` now explicitly opts `@types/node` back in via `"types": ["node"]` — the fix the
  compiler itself suggests — with no change to the extension's source or its published output. This
  is build tooling only and is not shipped to extension users.
- **The drift `responseTimeThresholdMs` performance-flag gate is now covered by behavioural tests.**
  `DriftAnalyzer.checkPerformanceDrift` raises a `PERFORMANCE` drift record only when an expectation's
  observed p95 latency exceeds the instance-set `responseTimeThresholdMs`, but no test drove responses
  straddling that threshold, so a regression that flagged everything (or nothing) would not have been
  caught. A new `DriftPerformanceThresholdTest` feeds the real `PercentileTracker` latencies under and
  over the threshold and asserts on the production `DriftStore` outcome: the over-threshold case flags
  exactly one `PERFORMANCE` record (with `expectedValue=<=threshold` and the actual p95), the
  under-threshold and disabled (`0`) cases flag nothing, a slow-tail distribution whose p95 crosses the
  threshold flips, and the `DriftAlertNotifier` webhook fires only when the flag is raised and its
  severity meets the notifier threshold.
- **LLM provider codecs now have their emitted token-usage counts asserted, not normalized away.** The
  `LlmCodecGoldenFileTest` golden drift harness deliberately zeroes usage blocks before comparing (usage
  counts are structural, not stable values), which meant the golden files alone could not prove a codec
  emits the *correct* token counts — a codec that silently regressed usage to `0`, or swapped
  input/output, would still have matched its golden. A new
  `LlmCodecGoldenFileTest.shouldEncodeCanonicalTokenUsageCounts` closes that blind spot: for all seven
  chat/completion providers (OpenAI, OpenAI-Responses, Anthropic, Gemini, Bedrock, Azure-OpenAI, Ollama)
  it encodes the canonical text and tool-call completions and asserts the actual encoded token-count
  fields — named per each provider's published usage schema (`prompt_tokens`/`completion_tokens`/
  `total_tokens`, `input_tokens`/`output_tokens`, `usageMetadata.promptTokenCount`/`candidatesTokenCount`/
  `totalTokenCount`, Ollama's top-level `prompt_eval_count`/`eval_count`) — equal the hand-authored
  canonical `Usage` values (input 12 / output 8 for text, 25 / 15 for tool-call), using `asInt(-1)` so a
  dropped or missing field fails the equality rather than silently defaulting to `0`.
- **GenAI span emission on the LLM SERVE path is now covered end-to-end.** When MockServer serves a
  locally-mocked `httpLlmResponse` completion, `HttpLlmResponseActionHandler` emits an OpenTelemetry
  GenAI (`gen_ai.*`) span via `GenAiSpans.recordCompletion(...)` — a distinct code path from the
  forward/proxy-path emission already guarded by `ForwardPathGenAiSpanEmissionTest`, and previously
  untested end-to-end. A new `ServePathGenAiSpanEmissionTest` installs an `InMemorySpanExporter`
  through the `GenAiSpanExporter.startWithProcessor(...)` seam, drives a real `MockServer` serving an
  OpenAI-shaped completion, and asserts exactly one GenAI span carrying `gen_ai.request.model`,
  `gen_ai.system`, and the input/output usage-token attributes is produced by the production serve
  path (read back from the exporter, not reconstructed).
- **The HTTP parser limit `maxHeaderSize` is now covered by a behavioural test.** The three parser
  limits (`maxInitialLineLength`, `maxHeaderSize`, `maxChunkSize`) are wired into the Netty
  `HttpServerCodec` in the HTTP/1.1 request pipeline (`PortUnificationHandler.switchToHttp`), but no
  test drove an over-limit request, so a regression that dropped the configured value and fell back to
  Netty's 8192-byte default — or removed the wiring entirely — would have gone unnoticed. A new
  `HttpParserLimitsIntegrationTest` starts a server configured with `maxHeaderSize=1024` and drives raw
  HTTP/1.1 requests over a plain `Socket` against a header-conditional expectation. The
  client-observable effect of the limit is header truncation: Netty's decoder stops parsing at the
  byte that crosses the limit and drops every header after it (MockServer logs the decode failure but
  still serves the request from the headers it did parse). A control request whose marker header sits
  within the 1024-byte limit is parsed, matches, and returns the mocked `200`; an otherwise-identical
  request with a ~2KB filler header inserted ahead of the marker pushes the marker past the boundary,
  so it is dropped, the request no longer matches, and MockServer returns `404`. The filler size sits
  strictly between the configured limit and Netty's 8192-byte default, so the test is a genuine
  positive control: reverting the wiring to ignore the configured `maxHeaderSize` (using the default)
  lets the whole header block through, the marker survives, and the over-limit request matches and
  returns `200` — turning the test red (confirmed).
- **OpenAI Responses API `previous_response_id` chaining and `GET /v1/responses/{id}` retrieval are now
  covered end-to-end over a real socket.** These stateful behaviours were previously exercised only at the
  handler+store level (`OpenAiResponsesStateTest`), so a regression in the wire path — the automatic
  storing of an issued response, the codec's reconstruction of a prior turn from `previous_response_id`,
  or the `GET`-based retrieval — would not have been caught. A new `OpenAiResponsesStateEndToEndTest` boots
  a real MockServer, POSTs a first `/v1/responses` turn (default `store:true`) and captures its response
  id, POSTs a second turn carrying only the new input plus `previous_response_id`, and asserts over the
  wire that the chained turn matches (proved via a `whenTurnIndex(1)` predicate that can only match once
  the prior assistant turn has been reconstructed) and that `GET /v1/responses/{id}` returns the stored
  response body.
- **The `outputMemoryUsageCsv` memory-usage CSV export is now covered by tests.** `MemoryMonitoring`
  builds a CSV header from the `buildStatistics()` keys on construction and appends a data row on each
  `logMemoryMetrics()` call, but this export path had no test anywhere. A new `MemoryMonitoringTest`
  enables CSV output to a JUnit `TemporaryFolder` and asserts the file is created, its header row
  exactly matches the `buildStatistics()` column keys, a triggered data row has a matching column count
  with a positive numeric `heapUsed` value, and that NO file is written when `outputMemoryUsageCsv` is
  disabled.
- **`TOKEN_BUCKET` rate-limit enforcement is now covered end-to-end through the handler/wire path.**
  Every 429-rendering test (`HttpActionHandlerRateLimitTest`, `RateLimitIntegrationTest`) previously used
  only `FIXED_WINDOW`; `TOKEN_BUCKET` was exercised solely at the registry level, so a regression that
  failed to render the synthetic 429 for a token-bucket limit would not have been caught. A new
  `HttpActionHandlerRateLimitTest.tokenBucketBurstOfOneAllowsBurstThenReturns429` drives two immediate
  requests against a `TOKEN_BUCKET` limit with `burst=1` and a negligible refill through the real
  `HttpActionHandler`, asserting the algorithm-specific behaviour: the burst of one is allowed (normal
  response), the second request exhausts the bucket and returns a `429` carrying
  `X-RateLimit-Limit: 1` (the bucket burst), `X-RateLimit-Remaining: 0`, and the `Retry-After`/reset headers.
- **WASM custom-rule host-isolation is now pinned by a test.** A WASM rule module can only reach the
  filesystem or any host/WASI capability through functions the host explicitly imports into the instance,
  and `WasmRuntime` deliberately wires NONE — it instantiates every module with a bare
  `Instance.builder(module).build()`, never `withImportValues(...)`. No test asserted this, so a
  regression that started wiring host imports would have gone unnoticed. A new
  `WasmRuntimeHostIsolationTest` hand-assembles a minimal-but-valid module whose import section declares
  `wasi_snapshot_preview1.fd_write` and whose exported `match` actually calls it, then asserts chicory
  refuses to instantiate it (`UnlinkableException`, mirroring the runtime's own build call), that
  `WasmRuntime.callMatch` therefore fails closed to `false`, and — as a positive control — that supplying
  a stub `fd_write` host import (the wiring MockServer omits) makes the identical module instantiate. The
  refusal assertion was confirmed to go RED when the host import is wired and GREEN when it is not.
- **Request-side OpenAPI violations in `trafficValidate` are now covered by an integration test.** Both
  existing `TrafficValidateIntegrationTest` cases only exercised response-schema violations, leaving the
  request-validation half of the traffic-validation path (`OpenApiTrafficValidator` →
  `OpenAPIRequestValidator`) unverified end-to-end. A new
  `shouldReportFailureWhenRecordedRequestViolatesSpec` records a `POST /pets` whose body omits the
  required `id`/`name` fields (with a 201 response that conforms to the spec, isolating the failure to
  the request side) and asserts the resulting `ContractReport` surfaces a failing result carrying
  REQUEST validation errors.
- **The `driftDetectionEnabled` master switch is now covered by a behavioural enforcement test.** The
  existing `DriftDetectionConfigTest` only re-implemented the gate boolean inline and never exercised
  the production code path, so a regression that ignored the flag would not have been caught. A new
  `HttpActionHandlerDriftDetectionTest` drives the real forward request path through `HttpActionHandler`
  — forwarding a request whose upstream response drifts (500) from a matching response-type stub (200) —
  and asserts that a STATUS `DriftRecord` IS recorded into the shared `DriftStore` when
  `driftDetectionEnabled(true)`, and that NONE is recorded when `driftDetectionEnabled(false)` or the
  sample rate is zero. This asserts on the real drift-recording outcome rather than a hand-mirrored
  copy of the gate.
- **The transparent-proxy original-destination end-to-end suites are now collectable by CI.** The three
  privileged interception suites — `SoOriginalDstEndToEndIntegrationTest` (iptables REDIRECT +
  SO_ORIGINAL_DST), `TproxyEndToEndIntegrationTest` (iptables TPROXY / IP_TRANSPARENT) and
  `EbpfOriginalDestinationEndToEndIntegrationTest` (pinned BPF map read path) — were previously named
  `*EndToEndIT`, a suffix that matches NEITHER Surefire's `**/*Test.java` include NOR Failsafe's
  `**/*IntegrationTest.java` include, so they were never compiled into a run set and never executed on
  any build. They are renamed to `*EndToEndIntegrationTest` so Failsafe collects them, and each now
  additionally SKIPS cleanly (rather than erroring) when the Docker daemon refuses to start the required
  NET_ADMIN / `--privileged` sibling container (e.g. a user-namespace-remapped daemon), via
  `DockerCliTestSupport.containerStartRejected(...)`. A new opt-in CI step
  (`.buildkite/scripts/steps/java-transparent-proxy-test.sh`, `RUN_TRANSPARENT_PROXY_E2E=true`) runs
  them under the Docker socket and asserts via `assert-suite-ran.sh` that they actually executed; by
  default it prints a loud, visible notice that they were not run, because the standard build agents
  lack the `docker` CLI and reject `--privileged` containers.

### Added
- **The mock-drift detection pipeline now has an end-to-end assembly test spanning the live forward
  through to the `GET /mockserver/drift` retrieval endpoint.** The individual pieces (`DriftAnalyzer`,
  `DriftStore`, and the `driftDetectionEnabled` gate in `HttpActionHandler`) were unit-tested, but no
  test drove the assembled path the Drift dashboard actually depends on: a live forward whose upstream
  response differs from a co-registered response stub → asynchronous drift analysis → the process-wide
  `DriftStore` → the real control-plane `GET /mockserver/drift` handler that reads it back. A new
  `DriftEndToEndAssemblyTest` forwards a request through the real `HttpActionHandler` (upstream 500 vs a
  stub's 200, drift analysis forced to run synchronously), then serves `GET /mockserver/drift` through a
  real `HttpState` and asserts the returned JSON contains the recorded `STATUS` drift (both unfiltered
  and via the `expectationId` query filter the dashboard uses); a companion case proves a non-drifting
  forward leaves the endpoint empty. Registered in the sequential Surefire phase because it mutates the
  singleton `DriftStore` and `PercentileTracker`.
- **The WAR servlet decoder's RFC 6265 cookie surrounding-quote stripping now has direct coverage.**
  `HttpServletRequestToMockServerHttpRequestDecoderTest` gains a test that feeds a
  `jakarta.servlet.http.Cookie` whose value carries surrounding double quotes (`"quotedValue"`, as
  Servlet 6 / Tomcat 11+ preserves) alongside an already-unquoted value, and asserts the mapped
  `HttpRequest` cookies are `quotedValue` (quotes stripped) and `plainValue` (unchanged) — pinning the
  `stripSurroundingQuotes(...)` behaviour that every prior fixture left unexercised because it only used
  plain ASCII values a container never quotes.
- **The dashboard static-asset handler's default MIME-type fallback is now covered, extending the
  #2358 null-`Content-Type` NPE guard to unmapped file extensions.** Every existing `DashboardHandlerTest`
  serves a mapped extension (`.js`, `.svg`), so `MIME_MAP.getOrDefault(extension, DEFAULT_MIME_TYPE)`
  never exercised its fallback arm — the exact branch that turns an unmapped extension into a valid,
  non-null `application/octet-stream` header instead of the null value that crashes Netty's header
  encoder. A new test serves a synthetic `unmapped-fixture.webp` (an extension deliberately absent from
  both `MIME_MAP` and the string-content list) and asserts the served response is found (not the 404
  not-found response) and carries `Content-Type: application/octet-stream`.
- **The `BCKeyAndCertificateFactory` IPv6 Subject-Alternative-Name branch is now covered, closing the gap
  where only IPv4 SAN IPs were exercised.** `BCKeyAndCertificateFactoryBehaviourTest` gains
  `shouldIncludeIPv6AddressesInSAN`, which configures `sslSubjectAlternativeNameIps("127.0.0.1", "::1",
  "2001:db8::1")`, generates the leaf certificate, and asserts the generated cert's iPAddress SAN entries
  (GeneralName type 7) contain both IPv6 addresses AND the IPv4 address in the same certificate. Assertions
  compare via `InetAddress` so they are independent of the JDK's canonical string form for IPv6 (e.g.
  `::1` -> `0:0:0:0:0:0:0:1`). This pins the `IPAddress.isValidIPv6`/`isValidIPv6WithNetmask` branch of the
  SAN-IP handling, previously reachable only through IPv4 literals.
- **`Times` exhaustion now has a direct passive-removal assertion, mirroring the existing time-to-live
  test.** `AbstractControlPlaneIntegrationTest` gains `shouldRemoveExhaustedTimesFromActiveExpectations`
  next to `shouldRemoveExpiredTimeToLiveFromActiveExpectations`: it registers an expectation with
  `Times.exactly(1)`, asserts `retrieveActiveExpectations(null)` reports one active expectation, makes the
  single matching request that exhausts the `Times`, then asserts the active list is now empty — WITHOUT a
  second request. Previously exhausted-`Times` removal from the active list was only observed indirectly
  via the wire 404 (a second request no longer matching); this pins that an exhausted `Times` expectation
  is dropped from the active list itself.
- **The OpenAPI forward-validate action's `LOG_ONLY` mode now has behavioural passthrough coverage,
  closing the gap where only the getter was asserted.** `HttpForwardValidateActionHandlerTest` gains two
  tests that drive `handle(...)` with `validationMode = LOG_ONLY`: one sends a schema-violating request
  and proves the bad request is still forwarded upstream (`verify(mockHttpClient).sendRequest(...)`) and
  the upstream 200 flows back unchanged (not a 400); the other stubs a schema-violating upstream response
  and proves it is returned unmodified (not a 502). These pin the "validate and log, but forward
  unmodified" behaviour that distinguishes `LOG_ONLY` from the already-covered `STRICT` reject branches.
- **The `Http2StreamIdAuditHandler` safety-net is now covered by a unit test, so the guard against the
  "HTTP/2 response head written without an `x-http2-stream-id`" defect class (GitHub issue #2419 and its
  SSE / streaming-body / metrics / MCP siblings) can no longer silently stop warning.** The handler is
  the only thing that turns a mis-routed, silently-dropped HTTP/2 response into a loud WARN, yet it had
  no test anywhere. A new `Http2StreamIdAuditHandlerTest` drives the handler on an `EmbeddedChannel` with
  a capturing logger and asserts the observable behaviour across three cases: an unstamped response head
  logs exactly one WARN naming the missing header, a correctly-stamped head logs nothing, and a second
  unstamped head on the same connection does NOT warn again (the per-connection dedup that stops a
  genuinely-broken write site from flooding the log). Suppressing the warn reddens the first and third
  assertions.
- **The Velocity `velocityDisallowClassLoading` sandbox now has coverage for taking effect when toggled
  on an ALREADY-CONSTRUCTED engine, not just when a fresh engine is built.** The existing test flipped
  the setting and then built a brand-new `VelocityTemplateEngine`, so the runtime rebuild-on-live-engine
  path (`currentEngineHolder()` rebuilding the underlying `VelocityEngine` with the `SecureUberspector`
  when the configured flag differs from the flag the current engine was built with) was never exercised
  — meaning a regression that made the setter/system-property/`PUT /mockserver/configuration` toggle
  inert on a cached engine would have reddened nothing. A new test builds ONE engine with class loading
  allowed, renders a class-loading template and asserts it genuinely EXECUTES the class-loading line
  (reaching `Runtime.exec`), then flips `velocityDisallowClassLoading(true)` on the SAME configuration
  and re-renders through the SAME engine, asserting the class-loading line is now BLOCKED (inert, empty
  body, no exception) — proving the live rebuild applies the new restriction.
- **The metrics endpoint's ENABLED path is now proven end-to-end over the handler, not just its
  disabled 404 and CORS behaviour.** Previously the only enabled-path coverage was a mock-`ctx` unit
  test (`MetricsHandlerTest`) that asserted the content-type header was non-null but never that
  `GET /mockserver/metrics` returns 200 with a real Prometheus exposition body. Two new tests in
  `HttpRequestHandlerTest` drive the request through the real `HttpRequestHandler` routing and
  `MetricsHandler`, with metrics enabled and the `mock_server_requests_received` counter incremented:
  they assert the response is 200 (mapping it through the same wire encoder the server uses, since the
  handler writes a status-less response the encoder resolves to 200 OK) and that the body carries the
  `mock_server_requests_received_total` series. A second case sends an OpenMetrics `Accept` header and
  asserts the negotiated OpenMetrics content-type, complementing the existing
  `shouldReserveMetricsPathWithCORSWhenMetricsDisabled` negative (disabled -> 404) so the enabled path
  is provably the difference.
- **The reflective cloud-blob-store auto-discovery path in `StateBackendFactory` now has direct test
  coverage.** Previously `StateBackendFactoryTest` only `instanceof`-checked the filesystem/memory blob
  stores, so `discoverBlobStoreBackend(...)` and the `BLOB_STORE_REGISTRARS` map — the reflective
  `blobStoreType=s3` → `Class.forName(...S3BlobStoreRegistrar)` → `register()` → factory `create()`
  chain — were exercised by no test, and a broken registrar-class name or map wiring would have redded
  nothing. Two layers now cover it: a new `S3BlobStoreDiscoveryTest` (in `mockserver-blob-s3`, which has
  the S3 module on its classpath) configures `blobStoreType=s3` and calls `StateBackendFactory.create(...)`
  with NO manual `register()`, asserting the resulting backend's `blobs()` is an `S3BlobStore` — provable
  only if discovery loaded the module reflectively (no network/Docker; the S3 client is built lazily); and
  `StateBackendFactoryTest` gains a core-only assertion that `blobStoreType=s3` with the module ABSENT
  fails hard with the documented `IllegalStateException` ("add the mockserver-blob-s3 dependency") plus a
  case proving an unrecognised type is rejected with the supported-types guidance.
- **The dashboard WebSocket frame now has a cross-boundary STRUCTURAL contract test, closing the gap
  where the server and the UI were tested against separately-authored payloads and could silently
  drift apart.** A single checked-in contract file (`mockserver-ui/src/__fixtures__/dashboardFrameContract.json`)
  lists, for every one of the four dashboard panels (log messages, active expectations, received and
  proxied requests), the fields the UI store/panels actually read and their JSON types. The server side
  (`DashboardWebSocketFrameContractTest`) drives the REAL `DashboardWebSocketHandler` across all four
  panels, captures the frame it emits, and asserts every required field is present with the correct type
  and that the server-assigned key correlations hold (a received-request row and its originating log
  entry share the same server log id). The UI side (`dashboardFrameContract.test.ts`) feeds the same
  file's representative frame through the real store `applyMessage` and asserts the resulting items
  expose those same fields. Because both read the one file, renaming or removing a field name reddens
  both tests; a server-side field rename reddens the Java test. Unlike the previous byte-equal captured
  golden (which passed locally but drifted in CI), the check is a per-field SUBSET assertion — immune to
  non-deterministic emission ordering, to timestamp/UUID/port/hostname values, and to
  environment-dependent extra fields — and a companion assertion captures the frame twice and proves a
  value-blind, order-independent structural fingerprint is identical across the two captures.
- **The REAL built dashboard bundle is now proven to be packaged and served, not just synthetic
  fixtures.** A new integration test starts a live MockServer, GETs `/mockserver/dashboard`, and
  asserts the response is the genuine React application shell (the `id="root"` mount point and the
  `MockServer Dashboard` title) that references a hashed JS entry chunk (`assets/index-<hash>.js`),
  then GETs that referenced asset and asserts it is served (200) with a JavaScript content-type.
  Previously the only dashboard-serving coverage used synthetic test fixtures placed at the same
  classpath path the `build-ui` Maven profile copies the real Vite output into, so a broken or missing
  real bundle (for example a Monaco-worker regression) reddened nothing. The test fails closed when the
  built bundle is present but broken (missing hashed reference, or the referenced asset is not served),
  and skips with a clear message only when the `build-ui` profile did not run and no real bundle is on
  the classpath.
- **The served dashboard now has real-browser end-to-end coverage against a live MockServer.** A new
  Playwright suite (`mockserver-ui/e2e/`) boots the runnable netty JAR — which serves the dashboard,
  the `/mockserver/*` control plane, and the `_mockserver_ui_websocket` feed on one origin — loads the
  dashboard in headless Chromium, and asserts real end-to-end behaviour: (1) an expectation authored in
  the composer UI matches a request fired over the wire, which then streams into the log panel live over
  the real WebSocket; and (2) expectation create, update, and clear driven through the dashboard change
  the server's own active-expectation list, verified over real REST (`PUT /mockserver/retrieve`).
  Previously the dashboard had no browser-level coverage at all — all 3,000+ UI tests run in jsdom with
  a mocked `fetch` and a hand-written WebSocket, so expectation CRUD and the live log stream were never
  exercised against the actual endpoints. Wired into the UI pipeline (`.buildkite/pipeline-ui.yml`) as a
  fail-closed CI gate that builds the current JAR, boots it, and runs the suite in the Playwright image.
- **Enabling `tlsMutualAuthenticationRequired` at runtime is now proven to be enforced over a real TLS
  socket.** A new integration test starts a live MockServer with mutual TLS OFF, confirms a
  certificateless client completes the handshake, then requires mutual authentication at runtime on the
  already-listening instance and asserts that a new certificateless connection is refused at the
  handshake (fatal alert), while a client presenting a certificate trusted by MockServer's CA still
  connects — proving the runtime change applies `ClientAuth.REQUIRE` selectively rather than being
  silently ignored or breaking TLS altogether. Previously the runtime-reconfiguration path was covered
  only by a unit test asserting the cached `SslContext` instance was replaced (which cannot assert the
  resulting `ClientAuth`), while the wire-level client-authentication tests all enabled mutual TLS at
  startup, so the enforcement outcome of a runtime enable was never asserted over the wire.
- **The OpenAPI validation-proxy enforce path is now proven end-to-end over a real socket.** A new
  integration test stands up a validation proxy (`validateProxyOpenAPISpec` + `validateProxyEnforce`)
  that forwards unmatched traffic to a second (upstream) MockServer, then drives real requests through
  it and asserts on the bytes the client receives: a schema-invalid `POST /pets` is rejected with `400`
  ("OpenAPI request validation failed") and never reaches the upstream, a conformant request is
  forwarded and served normally, and a non-conformant upstream response is rejected with `502`
  ("OpenAPI response validation failed"). Previously the enforce branch was only re-implemented inline
  in a unit test, so the production short-circuit in `HttpActionHandler.validateProxyRequest` /
  `validateProxyResponse` was never exercised over the wire.
- **The core mocking-action matrix is now proven over cleartext HTTP/2 (h2c) with a real client.** A new
  integration test drives a real prior-knowledge Netty HTTP/2 multiplex client over a socket against the
  insecure port and asserts on the bytes the client receives on its own stream for each action: a
  `respond` action delivers its status and body, a class `callback` delivers its produced body, a
  `forward` action relays the upstream body back, and an `error` action resets the stream with the
  configured HTTP/2 error code. Previously the full action matrix ran only over HTTP/1.1 and
  h2-over-TLS; h2c was exercised only by an `EmbeddedChannel` pipeline-shape test and gRPC-unary, so no
  test proved a real cleartext-HTTP/2 client actually received the response body for these actions (the
  streaming / stream-id sibling of gRPC issue #2419). The shared integration harness cannot cover this
  because its client has no h2c prior-knowledge path — an insecure request tagged HTTP/2 silently falls
  back to HTTP/1.1.
- **The core mocking-action matrix is now proven over HTTP/3 (QUIC) with a real client.** A new
  integration test drives a live Netty HTTP/3 client over QUIC against an HTTP/3-enabled MockServer and
  asserts on the bytes the client receives on its own request stream for each action: a `respond` action
  delivers its status and body, a class `callback` delivers its produced body, a `forward` action relays
  the upstream body back, a `forwardOverride` (overridden-forwarded-request) action rewrites the request
  and relays the overridden body back, and an `error` action resets the QUIC request stream (RFC 9114
  `RESET_STREAM`) instead of returning a response. Previously the HTTP/3 tests covered trace-context,
  mTLS capture, gRPC, streaming, MCP and lifecycle but none drove the forward / forwardOverride /
  callback / error matrix over QUIC or proved the forwarded/callback body reached the client over
  HTTP/3. The shared integration harness cannot cover this because its client has no HTTP/3 request path.
  Skips cleanly where the native QUIC transport (BoringSSL) is unavailable.
- **Interactive breakpoints are now proven end-to-end over a live transport.** A new integration test
  starts a running server, opens the real breakpoint callback WebSocket via `MockServerClient.addBreakpoint`,
  and drives a real JDK HTTP client through the full pause -> dispatch -> resolve loop: a RESPONSE-phase
  breakpoint whose client handler rewrites the matched mock response has the originating caller receive the
  modified status and body (not the original), and a REQUEST-phase breakpoint whose client handler returns a
  response ABORTs before the mock is generated so the caller receives the abort response instead. Assertions
  are made only on what the originating HTTP client reads back from the running server, so a pass proves the
  pause/resume/modify actually happened server-side. Previously breakpoints were exercised only by client
  unit tests (mocked HTTP client) and registry/handler tests over `EmbeddedChannel`; no test connected the
  real callback WebSocket client to a running server and drove a live request through a pause-resolve cycle.
- **VCR cassette replay is now covered end-to-end at the data plane.** A new integration test loads a
  cassette (recorded `request -> response` pairs) through the `load_expectations_from_file` tool into
  a running server, then drives real requests over a socket and asserts on the bytes the client
  receives: a matching request is served the recorded response body, a request matching no recorded
  entry falls through to `404` rather than borrowing another entry's response, and when a volatile
  request-body field (e.g. `request_id`) is normalised away a live request carrying a different
  volatile value still matches and is served the recorded body. Previously the cassette tests loaded a
  fixture and asserted only the control-plane `ACTIVE_EXPECTATIONS` echo, never proving a recorded
  response was actually served.
- **SNI-driven per-host server-certificate selection is now proven end-to-end over a real TLS
  handshake.** A new integration test opens an actual TLS connection presenting a chosen, non-default
  `SNIHostName`, then reads the served peer certificate and asserts its Subject Alternative Names
  contain that host — and repeats with a second distinct SNI host on the same running server to prove
  the certificate is regenerated per host. Previously this path was exercised only through
  `SniHandlerTest` (an `EmbeddedChannel` asserting the hostname was added to the SAN configuration
  set); no handshake test connected with a chosen SNI host and inspected the certificate the server
  actually served.
- **The forward/proxy-path GenAI span emission is now covered end-to-end through a running server.**
  A new test boots a real forwarding `MockServer` that proxies a chat-completions POST to an upstream
  MockServer stubbed as an OpenAI endpoint, and reads the emitted span back out of an in-process
  `InMemorySpanExporter` wired into the process-wide tracer — so the assertion exercises production
  `HttpActionHandler.emitForwardGenAiSpan` (provider sniffing, response parsing, span recording) rather
  than reconstructing it. Previously the forward path's span emission was only guarded by a core test
  that hand-mirrored the production logic and never drove the running server.
- **gRPC client-streaming and bidirectional-streaming are now covered by a real grpc-java client
  end-to-end.** A new integration test drives an actual `io.grpc` channel over h2c and asserts on the
  bytes the client deframes — a single collected response for client-streaming, and two interleaved
  replies plus the terminal `grpc-status` trailer for bidi. Previously these two RPC shapes were
  exercised only through `EmbeddedChannel`, the same mocked seam that let issue #2419 ship for the
  unary and server-streaming paths.
- **The SPY and CAPTURE operating modes are now covered end-to-end at the data plane.** A new
  integration test drives an unmatched request through each mode and asserts the documented
  behaviour: the request is proxied to the real upstream (the client receives the upstream body) and
  the exchange is recorded so it can be retrieved as an expectation. The test proves the operating
  mode is the decisive factor — the same request returns 404 in SIMULATE mode and is only proxied
  and recorded once the mode is switched to SPY or CAPTURE.
- **The breakpoint and verification forms accept the same search syntax as the Traffic view.** A quick
  scope box on both forms takes `method:`, `path:` and `host:` terms and fills the matcher fields from
  them, so the operator vocabulary learned in the Traffic search works when writing a breakpoint
  condition or a verification. It only ever fills the form — every existing field, including full
  regex paths and the header, query-parameter and cookie matchers, still works exactly as before, and
  a term using an operator the form cannot express (such as `status:`) applies nothing rather than
  half of itself. Path globs are translated to the regex form MockServer matches paths with, so
  `path:/api/*` selects the same requests in the form as it does in the search box.
- **Chaos host targeting now rejects targets that could never fire.** MockServer matches a chaos host
  exactly (case-insensitively, ignoring the port), so a wildcard, a pasted `host:` search operator, a
  URL scheme or a path silently produced a registration that appeared active and never faulted a
  request. All four places a chaos host can be entered — the HTTP and TCP register forms, the Quick
  Chaos strip and each stage of a chaos experiment — now refuse those with an explanation. The
  experiment case mattered most: a dead wildcard there produced a completed experiment reporting a
  clean resilience verdict having injected no faults at all.
- **The dashboard supports multiple workspaces in one browser tab.** Investigating two things at once
  meant losing your filters every time you switched between them, because the whole window shared one
  view and one set of search terms. A workspace now bundles the current view and the five panel search
  terms, so you can keep a filtered Traffic investigation in one and a Log Messages search in another
  and switch between them without either losing state. Workspaces can be named, and are restored on
  reload. The switcher row appears only once a second workspace exists, so a single-workspace user
  sees no change beyond one new app-bar icon, and existing persisted view and search settings carry
  over into the first workspace on upgrade. Captured data, the connection target, the request filter
  and the theme stay shared — a workspace is a lens over one server's data, not a second connection,
  and targeting a different MockServer instance per workspace is not yet supported.
- **The dashboard recognises GraphQL operations in captured traffic.** Every GraphQL request is a
  `POST /graphql`, so the Traffic and Log views showed a wall of identical rows and the operation name
  — the only thing distinguishing them — was buried in the body. Rows carrying a GraphQL request now
  show the operation type and name as a chip, and the shared `operation:` search operator filters by
  name (globs supported), so `operation:Get*` narrows to the queries you care about. The name is read
  from the `operationName` member when present and otherwise parsed out of the query document itself,
  which is where it usually lives. Detection is deliberately strict — an ordinary JSON body that
  happens to carry a `query` key is not treated as GraphQL — and parsing is bounded and never throws,
  so a large, binary or malformed body degrades to no chip rather than an error.
- **The dashboard Traffic view can focus on a single upstream host.** In proxy mode a session can
  capture traffic from dozens of hosts. A collapsible host list at the top of the traffic list shows
  each distinct host with its request count, busiest first; clicking one pins `host:<value>` into the
  search box and narrows the list, and clicking it again unpins. Pinning composes with whatever else
  is in the search box rather than replacing it, and because the pin is just a search term it persists
  across a view switch and a reload like any other search. The list appears only when captured traffic
  spans more than one host, so mock-only sessions — where everything targets localhost — are
  unaffected. Hosts are grouped by the same value the row displays and the `host:` operator matches.
- **The dashboard expectation composer can fire a real request against the draft matcher and show the
  live response.** A "Try It" button beside "Test Matcher" opens an inline panel that derives an
  editable HTTP request from the expectation being authored, sends it to MockServer, and renders the
  status, headers, body and round-trip time. Because a matcher is a pattern rather than a request,
  only exact non-negated values are pre-filled: regex, glob, schema, JSON-path, XPath and negated
  matcher forms — and the numeric-comparison and content-negotiation forms used by header and query
  matchers — are left blank and listed as underivable, so a pattern is never fired verbatim as though
  it were a literal. Headers the browser forbids a page from setting (`Cookie`, `Host`,
  `Content-Length` and the rest of the Fetch forbidden list) are named as unexercisable from the
  dashboard rather than silently stripped by `fetch`. The dashboard is served by the same listener
  that serves mock traffic, so the default target is same-origin; selecting one of the server's other
  bound ports raises a CORS warning up front and distinguishes a CORS block from an unreachable port
  when a send fails.
- **The dashboard TCP chaos form offers named network-condition presets.** Seven one-click presets —
  dial-up, Slow/Fast 3G (throughput and latency variants), satellite and a fragmented link — fill the
  TCP chaos latency, bandwidth or fragmentation field for the host being registered. Throughput and
  latency figures are anchored to Chrome DevTools' throttling profiles and every preset shows its
  concrete numbers in the picker, since names like "3G" carry era-dependent implicit values. Because
  MockServer's TCP chaos engine applies only the highest-priority configured fault
  (`down > reset_peer > limit_data > slicer > bandwidth > latency`) rather than composing them, each
  preset sets exactly one fault, so the panel never advertises a number the engine would discard;
  throughput presets also show the read size below which the bandwidth ceiling has no effect. The
  panel notes that TCP faults shape inbound request bytes only, not the response, and that latency is
  charged per read rather than per round trip.
- **The dashboard search operators are now a shared, extensible filter DSL, and a search box no longer
  offers an operator it cannot honour.** The `status:`/`method:`/`path:` vocabulary was hard-coded into
  the traffic/expectation/request search matcher; it is now a field registry (`lib/filterDSL.ts`) where
  each field declares how to resolve its value and whether it supports numeric comparison or glob
  matching. The three existing operators behave exactly as before. Two new fields ship with it —
  `host:` (glob, from the request `Host` header, resolved identically to the Traffic view's own host
  column) and `operation:` (glob, from a request body `operationName`). A call site can now declare
  which subset of operators it supports: the Log Messages panel declares none, so its placeholder
  advertises only `/regex/`, and typing `status:>=400 error` there marks the field invalid and explains
  that no field operators apply, instead of silently returning an empty list.
- **The declarative `rateLimit` expectation clause is now enforced on streaming response actions.**
  Previously the general-purpose `rateLimit` clause was applied only to buffered `RESPONSE`/`FORWARD`
  actions, so a matched `SSE_RESPONSE`, `GRPC_STREAM_RESPONSE` or `WEBSOCKET_RESPONSE` was never
  throttled. The same `rateLimitResponseOrNull` check now runs once per matched request at the top of
  each of those three stream cases, so an over-limit request receives the deterministic `429` (with
  `Retry-After` and `X-RateLimit-*` headers) instead of opening the stream; within the limit the stream
  proceeds unchanged. Reuses the existing `RateLimitRegistry` (no second implementation). The
  `LLM_RESPONSE` action keeps its own token-based TPM/TPD limiter and is unaffected.
- **The generic CRUD simulation GET-list endpoint now supports pagination, sorting and field filtering.**
  The list path accepts optional query parameters — `filterField`+`filterValue` (case-insensitive
  equality on a dot-separated attribute path), `sortBy`+`sortOrder` (`asc`/`desc`, missing values sort
  last, stable), and `page`+`size` (0-based page, `size`≤0 means no limit) — applied in the order
  filter → sort → paginate. Malformed parameters return a 400. When any list parameter is active the
  response adds `X-Total-Count`, `X-Page` and `X-Page-Size` headers; a plain list request with no
  parameters returns the exact legacy response (unchanged body, no extra headers). This is the generic
  CRUD store's own query surface and is independent of the SCIM list callback's sorting/filtering.
- **Interactive breakpoints support an optional `maxHits` one-shot / bounded budget.** A breakpoint
  registered with `"maxHits": 1` pauses once and then auto-deregisters, so the next matching request
  is no longer intercepted; `"maxHits": 3` fires three times then removes itself. Only real pauses
  count against the budget, so `maxHits` composes with `skipCount` (hits skipped by a `skipCount`
  window do not consume the budget). Absent (or `0`/negative) keeps the legacy behaviour of never
  auto-deregistering. `maxHits` is validated as a positive integer (400 otherwise) and is echoed by
  `PUT /mockserver/breakpoint/matcher` and listed by `GET /mockserver/breakpoint/matchers`.
- **Sixteen implemented control-plane endpoints are now described by the OpenAPI specification**, and
  therefore by the published Postman and Bruno collections, which are generated from it:
  `GET /mockserver/ready`, `GET /mockserver/config`, `GET /mockserver/proxyConfiguration`,
  `GET /mockserver/http3status`, `GET /mockserver/metrics`, `GET /mockserver/cluster`,
  `GET /mockserver/chaosExperiment/history`, `PUT /mockserver/recordings/promote`,
  `PUT /mockserver/pact/import`, `PUT /mockserver/baseline/compare`, `PUT /mockserver/trafficValidate`,
  `GET /mockserver/llm/optimisationReport`, `PUT /mockserver/llm/diffRuns`, `POST /mockserver/wasm/test`,
  `DELETE /mockserver/wasm/modules` and the MCP endpoint `/mockserver/mcp`. All of these were
  implemented and reachable but documented nowhere, so they were absent from the collections and from
  any client generated from the spec. `GET /mockserver/http3status` had no documentation at all
  anywhere. Each signature was verified against its handler rather than transcribed, which corrected
  four things a plausible reading would have got wrong: `GET /mockserver/metrics` deliberately has
  **no** bare `/metrics` alias (unlike its siblings, because `/metrics` is a plausible path for a
  user's own mocked API and reserving it would shadow their expectation); `PUT /mockserver/trafficValidate`
  accepts `specUrlOrPayload` as an alias for `spec` and can answer 403 and 503, not just 200/400;
  `PUT /mockserver/llm/diffRuns` treats an empty body as an empty filter rather than rejecting it; and
  the MCP endpoint reports a missing or invalid session as a JSON-RPC error inside a **200**, with
  `GET /mockserver/mcp` a hard 405 rather than an SSE stream.
- **The `Expectation` schema now declares all eleven properties it was missing** — `httpLlmResponse`,
  `grpcStreamResponse`, `grpcBidiResponse`, `binaryResponse`, `dnsResponse`,
  `httpForwardValidateAction`, `httpForwardWithFallback`, `beforeActions`, `afterActions`, `steps` and
  `capture` — together with the supporting component schemas. The published specification described a
  substantially smaller API than MockServer implements, so the LLM, gRPC streaming, gRPC bidi, binary
  and DNS actions could not be expressed by any client generated from it. Note this was a
  documentation gap only: the OpenAPI document is served verbatim and never parsed at runtime, and
  incoming expectation JSON is validated against `org/mockserver/model/schema/expectation.json`, which
  already declared all eleven — so these expectations were always accepted on the wire.
- **New `OpenApiSpecExpectationSchemaTest` guards the specification against the Java model.** The
  existing `OpenApiSpecSyncTest` asserts the two copies of the spec are byte-identical, which makes
  them one document but is blind to both copies being wrong together — which is exactly how the eleven
  properties above went missing. The new test drives the comparison from `ExpectationDTO`, using the
  same Jackson `ObjectMapper` that serialises expectations at runtime, so it fails when the server
  gains a property the spec does not declare. It deliberately does not enumerate the schema and look
  for matching Java fields: a test whose cases come from the artefact it polices cannot detect an
  omission in that artefact. The reverse direction is asserted too, which is the shape that would have
  caught `HttpChaosProfile.connectionDrop` — documented, implemented nowhere, and propagated into the
  Go client where users set a property the server silently ignored.
- **New `OpenApiSpecEndpointCoverageTest` asserts every control-plane route the server dispatches is
  described by the specification.** This is the guard whose absence let the sixteen endpoints above go
  undocumented: nothing compared the routes to the document. It extracts the route literals from the
  canonical `request.matches("METHOD", PATH_PREFIX + "/path", "/path")` dispatch shape in
  `HttpState` and `HttpRequestHandler` and checks each against the spec's `paths`. Because the control
  plane is dispatched by an `if / else if` chain rather than a route registry, there is no structured
  object to enumerate and the extraction has to read source text — so the test also asserts a floor on
  the number of routes it finds. That floor is the point: a refactor that changes the call shape then
  fails loudly, instead of silently extracting zero routes and passing while guarding nothing. Three
  dispatch mechanisms are deliberately out of scope rather than approximated (`/mockserver/metrics`,
  matched by regex; `/mockserver/mcp`, matched by prefix; and the four templated `{name}` routes); all
  are documented, just not machine-checked. Covering them cleanly needs a route registry the
  dispatcher and the test can both read, which is the durable fix.
- **New end-to-end tests for `PUT /mockserver/crud` and `PUT /mockserver/debugMismatch`**, the two
  least-defended endpoints in the control plane, both of which previously had no test reaching the
  server's HTTP dispatch at all. `/crud` is covered behaviourally rather than by status code: the test
  registers a resource and then drives POST/GET/PUT/PATCH/DELETE against the registered base path,
  asserting auto-increment continues past seeded ids, PATCH merges without clobbering, insertion order
  holds, deletes 404 afterwards, and the UUID strategy yields non-numeric ids under a custom `idField`.
  Both endpoints' bare aliases (`/crud`, `/debugMismatch`) are covered, as are their error paths.
- **CI now fires every generated API-collection example at a live MockServer.** The existing
  collections gate regenerates the Postman and Bruno collections and diffs them against the committed
  copies, which proves the generator is deterministic and the artifacts are current — but proves
  nothing about whether the documented examples actually work. An endpoint whose `requestBody` is
  `required: true` with no `example` generates a bodyless request; the committed collection contains
  it, regeneration reproduces it exactly, and the gate is green while every user who imports the
  collection gets a 400. That had happened to `/mockserver/baseline/compare` and
  `/mockserver/pact/import`, both now fixed with examples. `scripts/collections/test_collections.py`
  already existed and was wired into no pipeline; it now runs as its own step. The step starts
  MockServer on the agent and runs the checker over `--network host` rather than mounting the Docker
  socket, because `run-in-docker.sh` always withholds the socket from PR builds — a socket-based
  wiring would have silently degraded to "cannot start a server" on exactly the builds that most need
  checking, which is the same defect as the cloud-storage contract suites that skipped on 100% of CI
  builds while reporting green. Examples that are known to be rejected today are listed in
  `KNOWN_FAILING` as a ratchet rather than an exemption list: each entry carries a reason, and an entry
  that stops failing fails the run, so the list can only shrink.
- **New `javascriptAllowedClasses` — an ALLOW-list for the classes JavaScript templates may resolve via
  `Java.type(...)`.** When set it takes precedence over `javascriptDisallowedClasses` and nothing outside the
  list can be resolved. Entries match a class name exactly or, when they end in `.*`, as a package prefix
  (e.g. `java.util.*`). An allow-list is the only form that is safe by construction: the existing deny-list
  matched class names by exact string equality, so denying `java.lang.Runtime` still left
  `java.lang.ProcessBuilder` — and `Class.forName` reach-through — available. Both lists now also support
  package prefixes. The default is unchanged (no restrictions) so existing templates keep working; setting
  `javascriptAllowedClasses` is the recommended hardening step for any instance that renders templates from
  a source you do not fully control. Behavioural tests cover all three semantics: only listed classes
  resolve (`java.lang.Runtime`, `java.lang.ProcessBuilder`, `java.lang.Class.forName(...)` and the explicit
  `Java.type('java.lang.Runtime')` form are all refused at render time), the allow-list wins when a class is
  on both lists, and a `.*`/`.` package prefix matches the package it names without leaking into a sibling
  package that merely shares its leading characters.
- **New `wasmExecutionTimeoutMillis` (default 5000) — a wall-clock execution budget for WASM custom rules.**
  WASM modules ran with no fuel, timeout or interrupt, so a module containing an unbounded loop pinned the
  calling thread permanently; because WASM rules are evaluated during request matching this could wedge
  matcher threads. An invocation exceeding the budget is now aborted and fails closed (treated as a
  non-match). Set to 0 to restore the previous unbounded behaviour. Both this and the existing
  `wasmMaxMemoryPages` are now read from the live configuration at the point of use, so setting either on a
  `Configuration` instance or via `PUT /mockserver/configuration` takes effect — previously both were read
  from the static property store, so only the system-property route worked while the others were accepted
  and ignored. `wasmEnabled` is read the same way for the same reason.
- **CI now guards that every client library pins the same MockServer binary version.** Each client
  decides for itself which server binary its launcher downloads, through seven different mechanisms,
  and three of them had no release-time bump at all: the Python and PHP launchers sat at `7.1.0` and
  the Rust crate at `7.3.0` while the project released `7.4.0`, so those clients silently downloaded
  a three-minor-old server and the shared binary cache the documentation promises was never shared.
  All three are now corrected to `7.4.0`, `scripts/release/prepare.sh` bumps them (hard-failing if a
  pattern no longer matches), and `.buildkite/scripts/steps/clients-version-consistency.sh` asserts
  agreement so drift is caught between releases rather than at the next one. The check is emitted
  unconditionally by `generate-pipeline.sh` rather than behind a changed-path filter: the pins it
  guards live in per-client directories, so a commit that drifts one routes only to that client's own
  pipeline and would never reach a path-filtered gate — the drift vector and the guard would never
  meet. The expected version is read from the topmost released `changelog.md` heading, so it also
  works on shallow, tagless CI checkouts.
- **Event-log eviction is now observable.** `MockServerEventLog.getEvictedLogEntryCount()` reports how many
  entries have been discarded because the log reached `maxLogEntries` (or `maxEventLogSizeInBytes`), a WARN is
  logged once on the first eviction (naming the current `maxLogEntries` and the fact that verifications are
  affected), and the count is mirrored to the `mock_server_evicted_log_entries` Prometheus counter when
  metrics are enabled. Previously eviction was completely silent — no counter, no log line, no metric.
  The count includes only true evictions: an explicit `reset()`/`clear()` resets it to zero.
- **The cassette-registry control-plane endpoints now have end-to-end test coverage.** A new
  over-the-wire integration test drives `GET`/`PUT`/`DELETE /mockserver/cassettes` against a running
  server and pins the documented contract: `PUT` registers a cassette and returns `201` with the stored
  entry, `GET` lists cassettes most-recently-used first (and re-registering an existing cassette moves it
  to the front without duplicating it), `DELETE` (by query parameter or JSON body) removes a cassette so a
  later `GET` no longer lists it, a server reset empties the registry, and — when control-plane
  authentication is required — every verb is rejected with `401`. No production behaviour changed.
  authentication is required — every verb is rejected with `401`. The bare `/cassettes` aliases are
  exercised alongside the `/mockserver`-prefixed paths, each rejected-input branch (`PUT` with no body,
  `PUT` with no `path`, `DELETE` with neither a `path` query parameter nor a body `path`) is pinned to its
  `400` and its message, and the CORS headers that let the dashboard call these endpoints cross-origin are
  asserted. No production behaviour changed.

### Changed
- **Editor and dashboard package lockfiles refreshed to clear three open advisories.** `dompurify`
  (`3.4.11` &rarr; `3.4.12`) in `mockserver-ui`, where the fix matters most: the dashboard renders
  captured request and response bodies it did not author, so a sanitiser bypass through
  `CUSTOM_ELEMENT_HANDLING` is a cross-site-scripting vector rather than the low-severity issue its
  rating suggests. `monaco-editor` pins `dompurify` to an exact version, so the existing `overrides`
  floor was raised to `^3.4.12` rather than downgrading the editor. Also `fast-uri`
  (`3.1.2` &rarr; `3.1.4`, host confusion from a literal backslash and failed international-domain
  canonicalisation) and `linkify-it` (`5.0.1` &rarr; `5.0.2`, quadratic-time `mailto:` validation) in
  `mockserver-vscode`, both transitive build/packaging tooling that is not shipped to extension users,
  and both reachable by a lockfile refresh with no manifest change.
- **Dependabot now watches the VS Code extension's npm dependencies.** `mockserver-vscode` has a
  `package-lock.json` but was missing from the npm `directories` list, so unlike every other Node
  project it never received routine minor and patch update pull requests and drifted until its
  dependencies raised security alerts.
- **The S3 blob-store config-to-client wiring is now covered by a behavioural unit test.** A new
  network-free test exercises `S3BlobStoreRegistrar.createS3BlobStore(...)` directly and asserts the
  resulting client/store reflects the configuration: a missing bucket throws, the region defaults to
  `us-east-1` when unset (and honours an explicit region), an explicit endpoint override is applied
  (and left unset otherwise), static credentials are used when supplied (falling back to the default
  AWS credential chain when not), and the bucket and key prefix are passed through. Previously only
  registration idempotency and a Docker-gated MinIO contract test (which hand-built its own client)
  were covered, so a mis-wired property could pass unnoticed.
- **Node package lockfiles refreshed to clear six open denial-of-service advisories.** `brace-expansion`
  (`1.1.15` &rarr; `1.1.16`, `2.1.1` &rarr; `2.1.2`) in `mockserver-client-node`, `mockserver-node` and
  `mockserver-testcontainers/node`, plus `js-yaml` (`4.2.0` &rarr; `4.3.0`) and `protobufjs`
  (`7.6.4` &rarr; `7.6.5`) in `mockserver-testcontainers/node`. All are transitive dev/test-tooling
  dependencies, and every fixed version was already inside the existing declared ranges, so this is a
  lockfile refresh only — no `package.json` dependency bump and no new `overrides` entry was required.
- **Chaos testing doc navigation refreshed for the multi-stage experiment features.** The "On this page"
  feature map on `chaos_testing.html` now surfaces the scheduled-experiment sub-capabilities that were
  documented in the body but not linked from the top of the page: recurring/scheduled (cron and delayed)
  starts, the steady-state baseline pre-check, and experiment history. Two missing section anchors were
  added so the new links resolve, and a pre-existing broken in-page link (`#tcp_chaos` &rarr;
  `#tcp_layer_chaos`) was fixed.
- **The control-plane trust anchor is now mutable at runtime rather than frozen at startup.** The
  control-plane authentication handler (mTLS CA chain, JWT JWK source, OIDC issuer/audience/JWKS) is derived
  from the LIVE configuration on every request instead of being built once during server bootstrap. This is
  what makes enabling, disabling or re-pointing control-plane authentication on an already-running instance
  actually take effect, instead of returning success and being silently ignored — but it is a genuine
  widening versus immutable-after-bootstrap and is worth understanding. **Any configuration route can move
  the trust anchor of a running server**: a system property, a `Configuration` setter, or
  `PUT /mockserver/configuration`. Critically, a `Configuration` instance reads through to the process-global
  static `ConfigurationProperties` store for any field it has not set itself, so a server whose CA chain was
  never pinned on its own instance will follow later mutations of the global store — including mutations made
  by unrelated code sharing the JVM. **To pin a trust anchor that unrelated code cannot move, set it on the
  `Configuration` instance you start the server with** (an explicitly-set instance field wins over the static
  store); embedded and test usage should not treat the global store as a client-configuration vehicle. If the
  control plane is reachable by parties who should not be able to change its own trust anchor, keep
  control-plane authentication enabled — `PUT /mockserver/configuration` routes through the same gate. See
  [tls-and-security.md](docs/code/tls-and-security.md#runtime-mutability-of-the-control-plane-trust-anchor).
- **WIRE FORMAT: a matcher *value* whose first character is `!` or `?` is now sent as an object rather
  than a bare string.** In the plain-string form a leading `!` means "not" and a leading `?` means
  "optional", and the receiver strips those markers unconditionally — so asking for "path **is**
  `!foo`" was transmitted as `"!foo"` and read back as "path is **NOT** `foo`", the exact opposite of
  what was requested, with no way to escape it. Such values are now serialised as
  `{"not": false, "value": "!foo"}`, which the server already read verbatim. **This only affects
  values that were previously impossible to express correctly**; every value that round-tripped
  before is byte-for-byte unchanged on the wire, so existing expectations, recordings and fixtures
  are unaffected. The object form is already permitted by the published JSON schema
  (`stringOrJsonSchema`), and `httpWebSocketResponse.matchers[].textMatcher` and
  `grpcBidiResponse.rules[].matchJson` have been updated to reference it. The negated direction was,
  and remains, expressible as a string: `!!foo` still means "not `!foo`". Generated Java code is
  fixed the same way, emitting `string("!foo", false)` instead of a bare literal that would be
  re-parsed as a negation when the generated code runs.
  **Scope: matcher values only, not header/parameter/cookie *names*.** A name is a JSON field name,
  which cannot carry the object form, so `header(string("!X-Foo", false), "bar")` still inverts. That
  is pre-existing rather than a regression, needs a schema change to fix, and is recorded with the
  reasoning in `test-fixtures/expectations/known-gaps.json`.
- **A DNS record that cannot be encoded on the wire now returns `SERVFAIL` instead of being silently dropped
  or emitted as corrupt bytes.** Previously an unparseable IP address dropped that one record and still
  returned `NOERROR` (so the client saw a successful, empty answer), and an over-long label or mismatched
  address width was written to the wire unchecked. Configuration that cannot produce a conformant response is
  now reported as a server failure, with the reason logged at ERROR. If a suite depended on a malformed
  record being quietly skipped, it will now see `SERVFAIL` — the record needs correcting.
- **DNS TXT values longer than 255 octets are now split across multiple character-strings rather than
  truncated.** Resolvers concatenate them, so the value a client reads is now the full configured value. A
  test that asserted on the truncated 255-octet prefix will need updating — it was asserting on corruption.
- **BREAKING BEHAVIOUR: `verify(never())` and other upper-bound verifications now FAIL instead of passing once
  the event log has evicted entries. Suites that are green today may legitimately go red — that is the point.**
  Previously, when the event log rolled over, the entries proving a request had happened were silently
  discarded and `verify(never())` began passing on its own: "this endpoint was never called" turned green
  because the evidence was gone, with no warning anywhere. For a verification tool this is the worst possible
  failure mode, and it got more reachable the more expectations were loaded (see the log-entry accounting fix
  below). A verification can no longer claim more than it knows: when the log has evicted, any verification
  asserting an **upper** bound fails with an explicit message naming the eviction count and `maxLogEntries`,
  instead of passing on an incomplete record. **This includes `once()`** — `once()` asserts *exactly* one call,
  so eviction could be hiding a second one; if you use the common `verify(request("/orders"), once())` idiom,
  this is the change that affects you. The strictness is deliberately asymmetric: eviction can only ever make
  the observed count too *low*, so `atLeast(n)` — and therefore the bare `verify(request)`, which defaults to
  `atLeast(1)` — is unaffected and keeps passing, and a verification that already failed can never be turned
  into a pass. In short: **affected** = `never()`, `atMost(n)`, `exactly(n)`, `once()`, `between(a,b)`;
  **unaffected** = `atLeast(n)` and bare `verify(request)`. If a verification starts failing after this
  upgrade, the log was already incomplete and the previous pass was not trustworthy — increase `maxLogEntries`,
  or `reset()` the event log between tests (a reset, and a clear-everything, clear the eviction state; a
  filtered clear deliberately does not). The new `failVerificationOnEvictedLog` property (default `true`)
  restores the previous, unsound behaviour when set to `false`.
- **BREAKING BEHAVIOUR: OpenAPI expectations now respect `Times` and `TimeToLive`, so expired OpenAPI
  expectations stop being served. Suites relying on an expired OpenAPI expectation still responding will
  correctly go red.** An expectation created from an `OpenAPIDefinition` was never lifecycle-gated: every
  other matcher checks `isActive()`, but the OpenAPI matcher delegates to per-operation matchers built
  without an expectation attached, so their `isActive()` was trivially true and the outer expectation's TTL
  and remaining-match count were consulted nowhere on the serving path. An OpenAPI expectation set up with
  `Times.exactly(1)` or a one-minute TTL therefore kept matching for the life of the server. Plain
  (non-OpenAPI) expectations were always gated correctly and are unaffected.
- **BREAKING BEHAVIOUR: a data-plane OpenAPI expectation whose spec is blank or null no longer matches every
  request. A suite that is green today because of a mistyped spec will correctly go red.** When no operations
  could be derived from the spec, the matcher fell through to matching *everything*, so a single expectation
  with an empty or mistyped spec silently hijacked all traffic on the server and served its action for every
  request — including requests that other expectations were meant to handle. It now matches nothing. Anyone
  relying on the old behaviour was almost certainly doing so by accident: a blank spec is not a way to express
  "match everything" (use a plain expectation with no request definition for that), and an expectation sent
  over the REST API with a blank `specUrlOrPayload` is deserialised as a plain request rather than an OpenAPI
  definition (`RequestDefinitionDTODeserializer` only builds an `OpenAPIDefinitionDTO` when the spec is
  non-blank), so this was only reachable through the Java client. **Control-plane filters are unaffected** —
  `clear`, `retrieve` and `verify` by request definition keep the "empty filter matches all" semantic, which
  is correct and intended there. A spec that fails to *parse* was already safe and is unchanged.
- **BREAKING BEHAVIOUR: the mock OIDC provider's `/introspect` endpoint now validates the presented token.
  Tests that are green today may correctly go red — that is the point.** Previously, when the provider issued
  JWT access tokens (the default), introspection **ignored the token entirely** and reported `active` from
  static configuration, so *any* string — garbage, an expired token, a tampered token, a token minted by a
  different provider, or one that had just been revoked — introspected as `active: true`. A test asserting
  "my application rejects a revoked token" therefore passed while proving nothing. Introspection now fails
  closed: a token is active only if it verifies against the provider's signing key and is inside its validity
  window (or, for opaque providers, resolves to a recorded unexpired token). Additionally:
  - **`/revoke` now actually revokes.** It previously returned `200` and did nothing, so a revoked token kept
    introspecting as active. Revoked tokens are now recorded and report `active: false` (RFC 7009).
  - **Inactive responses no longer leak claims.** An inactive result now contains `{"active": false}` and
    nothing else; previously it still returned `sub`, `iss`, `aud`, `scope` and every configured additional
    claim (RFC 7662 §2.2).
  - **A request with no `token` parameter now returns `400 invalid_request`** rather than an introspection
    result (RFC 7662 §2.1).
  - **`/token`, `/introspect` and `/revoke` now send `Cache-Control: no-store` and `Pragma: no-cache`**
    (RFC 6749 §5.1), so an intermediary cannot replay a token or a stale `active: true`.

  If a test starts failing, it was asserting against a fabricated success and the application behaviour it
  claimed to cover was never exercised.
- **BREAKING BEHAVIOUR: the mock OIDC provider's `/userinfo` endpoint now requires a valid bearer access
  token.** It was previously a static response that returned the subject and every configured additional
  claim to *any* caller, with no inspection of the `Authorization` header at all — the same defect as
  introspection, one endpoint over. Two common tests could therefore never fail: "my application handles a
  `401` from userinfo when the access token has expired" never saw a `401`, and "my application only calls
  userinfo with a valid token" passed unconditionally. Userinfo is an OAuth2 protected resource (OIDC Core
  §5.3), so it now returns `401` with `WWW-Authenticate: Bearer error="invalid_token"` when the token is
  missing, malformed, expired, revoked, or issued by a different provider, and the `401` body carries no
  claims. The `sub` in a successful response is taken from the presented token rather than from static
  configuration. Token validation for `/userinfo` and `/introspect` is now a single shared code path, so the
  two endpoints cannot drift into disagreeing about the same token.
- **BREAKING BEHAVIOUR: the mock OIDC provider's `issuer` is now derived per request from the `Host` header
  instead of being hardcoded to `http://localhost:1080`.** OIDC Discovery §4.3 requires the advertised issuer
  to be identical to the URL the relying party used to fetch the discovery document, and every conformant
  client validates it — so the hardcoded default broke the most common way people run a mock OIDC provider:
  a Testcontainers-mapped random port, where Spring Security, nimbus and pac4j all rejected the mismatch.
  The `iss` claim minted into tokens, and the device-authorization `verification_uri`, are derived the same
  way, and `X-Forwarded-Proto` is honoured so a provider behind a TLS-terminating ingress advertises `https`.
  **Setting `issuer` explicitly still wins**, so pin it if you need a stable, externally-meaningful value.
  Also, discovery now advertises only `response_types_supported: ["code"]` — the implicit and hybrid flows
  were advertised but rejected by `/authorize`, so a conformant client that selected one from the list failed.

### Fixed
- **Startup no longer stalls for two minutes when a cloud blob store is unreachable.** The restore of
  cloud-persisted expectations runs from the `HttpState` constructor, which the netty `LifeCycle`
  constructor builds *before* any listening port is bound, and the blob-store read had no timeout. An
  endpoint that accepts connections but drops requests — a typo, a VPC egress rule, a DNS black hole —
  delayed the port bind for the cloud SDK's entire retry budget: measured at 121 seconds against the
  AWS SDK v2 defaults (4 attempts x a 30 second socket timeout), long enough to fail readiness probes,
  Testcontainers wait strategies and CI harnesses. The read is now bounded by a new
  `blobStoreRestoreTimeoutSeconds` property (default 10 seconds), after which MockServer logs a WARN
  and starts with no restored expectations; set it to `0` to skip the startup restore entirely. Like
  the rest of the `blobStore*` family the property is reported by `GET /mockserver/configuration`; it
  is read once during startup, so a runtime `PUT` cannot change the restore it governs. When the
  deadline expires the abandoned read is left to finish on its daemon thread so that the real
  underlying cause (credentials denied, DNS failure, endpoint typo) is still logged at DEBUG, rather
  than leaving an operator with nothing but "timed out".
- **Expectations restored from a cloud blob store are no longer silently deleted when
  `initializationJsonPath` points at `persistedExpectationsPath`.** That combination is exactly what
  the long-standing *filesystem* persistence guidance recommends, so a user migrating to
  `blobStoreType=s3` naturally keeps it. The restore tagged expectations with a `Cause` whose source
  was the persisted path; `Cause` has value equality, `RequestMatchers.update` removes every matcher
  whose source equals the incoming cause but is absent from the incoming array, and the expectation
  initializer — constructed *after* the persistence — called `update` unconditionally with an empty
  array after reading the (empty) local file. Every restored expectation was dropped, with no warning.
  The restore's cause source is now prefixed so it can never collide with an initialization path, and
  the combination logs a WARN under a non-filesystem blob store, where the initializer reads a local
  file the bucket never populates.
- **A blob-store key miss during the startup restore is now reported instead of passing silently.** The
  blob key embeds the *absolute* `persistedExpectationsPath`, and the default for that property is the
  relative `persistedExpectations.json`, so the object name silently varies with the working directory
  a MockServer instance was started from. A miss now logs at INFO with the key it looked for and the
  fact that restore requires the same bucket, the same `blobStoreKeyPrefix` and the same
  absolutely-resolved `persistedExpectationsPath`. The consumer documentation now states that
  requirement rather than promising that pointing a fresh instance at the same bucket is sufficient.
- **A failing blob-store restore no longer risks crashing startup when no logger was supplied.** The
  restore's failure branch logged unconditionally while the surrounding code null-checked the logger,
  so the four-argument `ExpectationFileSystemPersistence` constructor could turn a logged, recoverable
  restore failure into a `NullPointerException` during startup.
- **Expectations persisted to a cloud blob store (S3, GCS or Azure) are now restored on restart.**
  With `persistExpectations` enabled and `blobStoreType` set to a cloud backend, MockServer wrote the
  persisted expectations document to the bucket on every change but never read it back, so a restart
  came up empty — cloud persistence was effectively write-only. The filesystem blob store already
  reloaded via the `initializationJsonPath` mechanism (pointing it at `persistedExpectationsPath`),
  but that path reads the local disk, which a cloud bucket never populates. On startup MockServer now
  reads the persisted document straight from the configured blob store and loads it through the same
  code path a JSON initialization file uses, making cloud persistence symmetric: what is written on
  change is restored on restart. The filesystem store is unchanged — it keeps reloading through
  `initializationJsonPath` exactly as before, with no double loading.
- **Pasting a redacted credential back into `PUT /mockserver/configuration` no longer destroys the
  credential it stands for.** `GET /mockserver/configuration` leaves credentials such as
  `proxyAuthenticationPassword`, `dataPlaneBearerAuthenticationToken`, `blobStoreSecretAccessKey` and
  `clusterFanInPeerAuthToken` out of its response entirely, and returns others (such as
  `privateKeyPath`) in clear, so a round trip of *that* endpoint was already safe. The diagnostic views
  are different: `GET /mockserver/config`, `--print-config` and the dashboard's Server Info tab
  show every credential-named property as `***REDACTED***`. An operator who read one of those, edited a
  neighbouring setting and sent the result back stored the literal `***REDACTED***` as the credential —
  the working secret was gone, every call authenticated with it started failing, nothing was logged,
  and the `PUT` answered `200 OK`. A value carrying the mask is now refused on both write paths, and on
  the one that merges into a running server the refusal is logged with the property named — so a `PUT`
  that wrote nothing no longer answers `200 OK` in silence. The credential in force is left untouched,
  and a freshly built configuration leaves the property unset so a property file or environment
  variable can still supply it. Only a value carrying the mask is affected — supplying a real new
  credential, and clearing one with an empty value, behave exactly as before. Applies to fifteen
  properties: the proxy and data-plane credentials, the certificate-authority and forward-proxy private
  keys, the server and control-plane TLS private-key paths, the blob-store access key, secret key and
  connection string, the cluster fan-in token, the API-key header name and the dashboard analytics key.
- **A credential-named property that is never masked by `GET /mockserver/configuration` now warns when
  a mask is sent for it.** Refusing the pasted mask silently is indistinguishable, from the operator's
  side, from having applied it: the `PUT` answers `200 OK` either way. Silence is kept only for the
  three properties that endpoint really does return as `***REDACTED***`, where sending the mask back
  unchanged is the normal round trip and warning on it would log once per credential on every
  config-as-code apply. For every other credential-named property the mask can only have been copied
  out of a diagnostic view or typed by hand, so it is reported.
- **A rejected credential no longer leaves `PUT /mockserver/configuration` half-applied.**
  `forwardProxyPrivateKey` and `controlPlanePrivateKeyPath` are validated as readable files when set,
  so a value they rejected threw from the middle of applying the configuration — after earlier settings
  in the same body had already been written to the running server. The `PUT` then failed with a `400`
  over a partly-changed configuration. Such a value is now refused before it reaches the setter, so the
  rest of the body still applies and the response no longer contradicts what was stored.
- **The editors no longer mark a valid expectation file as invalid when a header or cookie name uses
  the object form.** MockServer writes a header, query-parameter or cookie name as
  `{"name": {"not": false, "value": "!foo"}}` when the name itself begins with `!` or `?`, so that a
  literal marker character is not read back as a negation. The expectation JSON Schema bundled into
  the VS Code extension and the JetBrains plugin still typed that name — and the values in the same
  array form — as a plain string, so an expectation file MockServer itself had written was underlined
  as an error and completion stopped working inside the entry, even though the server accepted the
  file. The bundled schema is regenerated from `mockserver-core`, so both editors again accept exactly
  what the server accepts. Names and values written as plain strings are unaffected.
- **A build could fail with a `403` in a module that had nothing to do with the change being tested.**
  `central-portal-snapshots` was declared as a *resolution* repository in the maven-invoker settings,
  the `mockserver-maven-plugin` POM, the Gradle integration test and the CI image's global Maven
  settings — so the build reached over the network for `-SNAPSHOT` artifacts it had just produced
  itself. Any failure to fetch one (a Sonatype outage, or a TLS-inspection proxy holding a `.jar` for
  scanning) failed the whole reactor long after the affected module's own tests had passed, which reads
  as a test failure to anyone who does not open the log. It was also intermittent, because Maven only
  re-checks a snapshot once a day.
  Two further consequences are fixed with it. Maven picks the snapshot with the newest `lastUpdated`
  across all repositories, so whenever the locally installed artifact was *older* than the last
  published one — routine on a developer machine, or in any tree whose upstream modules had not been
  rebuilt — an integration test silently verified a previously published build instead of the code
  under test. And `<repositories>` is copied into the POM published to Maven Central, so every consumer
  of a released `mockserver-maven-plugin` inherited a third-party repository their own Maven would
  query for release artifacts too; that injection stops from the next release onwards.
  Nothing in the build needs a remote snapshot: every `-SNAPSHOT` it consumes is one it produces. The
  repository is now declared only where snapshots are *published*. Consuming MockServer snapshots from
  outside the repository is unaffected and still documented in `README.md`.
- **`mvn clean` removes every Tomcat scratch directory the WAR tests create, and `git status` stays
  clean if one is left behind.** The `maven-clean-plugin` filesets and the `.gitignore` patterns both
  predated the move into the `mockserver/` sub-directory, so the ignore rules matched nothing at all and
  the clean filesets missed the newer `tomcat_control_plane_smoke` and `tomcat_root_default_servlet`
  directories. Both now cover the whole `tomcat*` family under both servlet modules.
- **Editing a masked credential in a configuration read back from MockServer can no longer destroy it.**
  `GET /mockserver/configuration` returns `***REDACTED***` in place of `llmApiKey`,
  `prometheusRemoteWriteBearerToken` and `prometheusRemoteWriteBasicAuthPassword`, and sending that
  mask straight back was already ignored so the real credential survived. But a value that merely
  *contained* the mask — an operator typing around it, as in `sk-***REDACTED***` or
  `Bearer ***REDACTED***` — was read as a brand-new secret and saved verbatim: the working credential
  was gone and the literal text `***REDACTED***` became the credential MockServer sent upstream, while
  the `PUT` still answered `200 OK`. Any value carrying the mask is now refused, leaving the credential
  already in force untouched. (The credentials masked *inside* `prometheusRemoteWriteHeaders` and
  `llmBackendsConfig` are unaffected and keep their own rule: a mask you leave exactly as it came back
  is resolved to the real secret and the rest of your edit is applied — only a mask with text welded
  onto it is refused.) A value the operator *edited* around the mask
  is refused with a warning naming the property and how to recover; sending the mask back untouched is
  the normal round trip and stays silent, so a config-as-code tool applying the whole blob does not
  emit a warning per credential on every apply. Setting a real new credential, and clearing one with an
  empty value, work exactly as before. To replace a credential, type the new value on its own in place
  of the whole mask.
- **`mockserver-node` no longer deletes the MockServer jar another call is about to launch.** Because
  `mockServerVersion` can be set per call, starting a server for one version deleted the downloaded jar
  for every other version from the package directory. Two starts for different versions therefore raced:
  the second deleted the first's jar, and the first died with `Unable to access jarfile`, or was handed
  no jar at all and spent its whole startup timeout connecting to a server that was never launched.
  Alternating between two versions in sequence was no better — each start re-downloaded a ~100MB jar
  the previous one had just removed. No jar is deleted now, for any version: a re-fetched `SNAPSHOT` is
  renamed over the old one in one step, so there is no moment at which a concurrent start can find the
  jar missing.
- **`mockserver-node` finds its jar in a fixed location instead of searching the working directory.**
  The jar was located with a recursive wildcard rooted at the working directory, so it could match a
  copy belonging to something else entirely, could match a path that no longer existed by the time
  `java` opened it, and found nothing at all when the working directory was not the package's own —
  in which case `java` was invoked with an undefined jar argument and the failure only surfaced as a
  connection timeout. The jar is now resolved against known directories, checked for existence, and a
  missing jar is reported immediately by name. Downloads are written to the package directory (which is
  where they were already looked for) via a uniquely-named temporary file that is renamed into place only
  once complete, so a concurrent start can never see a half-written jar. Where the package directory is
  not writable — a root-owned global installation used by another user, or a read-only container layer —
  the working directory is used instead, which is one of the locations the jar is looked for anyway.
- **A failed `mockserver-node` jar download no longer poisons every later start.** A download is now
  rejected unless it is actually a jar, so a proxy or captive portal answering `200` with an error page
  fails the start it belongs to instead of being cached as the server jar forever. A connection that
  goes silent — established, then neither sending nor closing — is failed after a minute rather than
  leaving the caller waiting on it indefinitely; the timeout is re-armed on every chunk received, so a
  slow but progressing download is never interrupted, and `MOCKSERVER_DOWNLOAD_IDLE_TIMEOUT_MILLIS` adjusts
  the limit for a proxy that legitimately pauses for longer than that before it starts sending. A download
  that fails now stops transferring rather than only reporting the failure, so the rest of a ~100MB body is
  no longer pulled down and discarded while holding the process open. The temporary file a download is
  written to is named with random bytes and opened exclusively, so parallel containers sharing one
  installation cannot write over each other (process ids are not unique between them) and a file or symlink
  already at that path is left untouched rather than truncated, followed or deleted. Partial files left
  behind by a download that was killed outright are swept on the next attempt once they are too old to
  belong to a running one.
- **The `mockserver-node` jar downloader is now covered by unit tests.** Its behaviour was previously
  exercised only indirectly, by the tests that start a real server, which cannot tell a jar that was left
  alone from one that happened to be re-downloaded. The download path now has hermetic tests — no network,
  each using its own throwaway version — pinning that fetching one version leaves another version's jar
  untouched, that a response which is not a jar is rejected and never cached, that a failed `SNAPSHOT`
  re-fetch leaves the previous jar intact, that a file already at the temporary path is neither written
  through nor removed, that a stalled or failed download stops transferring instead of running on, and
  that a download which is merely slow — arriving in pieces spread over several times the idle limit —
  runs to completion rather than being cut off.
- **The Node client's forward-method-callback test now actually exercises a forward.** It returned the
  incoming request unchanged, which sends it straight back to MockServer — where the only expectation
  that could serve it was the single-use forward expectation just consumed, so the response could only
  ever be a `404`, which the test's own request helper turns into a rejection before its "any status
  code" assertion is reached. The callback now targets a real upstream started by the test and rewrites
  the path, so the assertions prove the callback saw the real request, that the request it returned is
  the one forwarded, and that the upstream's response is relayed back.
- **The strict forward-validate reject paths are now covered by behavioural tests.** `forwardValidate`
  in `STRICT` mode rejects a request that does not conform to its OpenAPI spec with a `400` (and never
  forwards it upstream) and rejects a non-conformant upstream response with a `502`. Both reject
  branches previously had no test; they are now pinned so a regression that silently forwarded an
  invalid request, or accepted an invalid upstream response, is caught.
- **Reading the configuration back and sending it straight to `PUT /mockserver/configuration` can no
  longer break a credential.** Header values in `prometheusRemoteWriteHeaders` are masked with
  `***REDACTED***` when the configuration is read, and MockServer puts the real value back when that
  masked list is sent in again. If a masked header was followed by a segment with no `=` in it — for
  example `Api-Key=***REDACTED***,junk,X-B=2` — the two were read as one header value, which then did
  not match the mask, so the whole thing was stored verbatim: the real API key was lost and the literal
  text `***REDACTED***` became the credential sent to the remote-write endpoint. The same could happen
  for a `llmBackendsConfig` document whose field merely contained the mask rather than being it. A
  resolved value is now re-checked before it is stored, and a value still carrying the mask is refused
  with a warning, leaving the credential MockServer already holds untouched.
- **A `PUT` that MockServer cannot make sense of no longer deletes a credential in silence.** When a
  masked header or backend key sent back to MockServer could not be matched to the real value it stands
  for — because the header name was re-cased, the backend was renamed or reordered, or nothing was held
  under that name at all — the unresolvable part was quietly dropped and the rest was stored. The mask
  did not leak, but the credential was simply gone while the `PUT` still answered `200 OK`, so the next
  outbound call failed to authenticate for no visible reason. A re-cased header name now still finds
  its value, since HTTP header names are case-insensitive — though where a list holds two names that
  differ only in case, they are two distinct headers that are both sent, so the mask is ambiguous and
  is not guessed. Anything unresolvable makes MockServer refuse the whole value with a logged warning
  and keep the configuration it already has.
- **The configuration API no longer discloses a secret hidden in a JSON document.** `llmBackendsConfig`
  normally holds the path of a backends JSON file, and a path is returned as you set it. If an inline
  JSON document was set instead, its `apiKey` fields were masked — but only when the value began with a
  brace or bracket and held exactly one document. A document behind a prefix (such as
  `backends=[{...}]`), one behind an invisible byte-order mark, and one placed *after* a first document
  (`{"a":1}{"apiKey":"sk-..."}`, where everything after the first document was silently ignored) were
  all returned in clear — including in the configuration line logged at startup. Any value that embeds
  JSON but cannot be read as exactly one document is now masked whole. File paths are unaffected.
- **More credential names are recognised.** Values whose property, header or JSON field name contains
  `passwd`, `pwd`, `signature`, `hmac`, `salt`, `session`, `otp` or `bearer` are now masked, as are
  header and field names containing `auth` (covering `Authentication`, `WWW-Authenticate` and a bare
  `X-Auth`) or `jwt`. Previously names such as `X-Hub-Signature-256` — the GitHub webhook signing
  convention — were shown in clear wherever configuration is displayed or logged. Property names
  containing `auth` or `jwt` are deliberately *not* masked, so settings such as
  `tlsMutualAuthenticationRequired` and `proxyAuthenticationUsername` stay readable in `--print-config`.
- **The cluster fan-in peer auth token is no longer returned by `GET /mockserver/configuration`.** The
  credential each node presents to its peers on cross-node verify/retrieve queries — sent verbatim as
  the control-plane `Authorization` header — was serialized in clear by the configuration endpoint, so
  anyone able to read the configuration could lift a token granting write access to every node in the
  cluster. `clusterFanInPeerAuthToken` is now write-only, like every other credential MockServer holds:
  still settable with `PUT /mockserver/configuration`, absent from the `GET` response, and a
  GET-then-PUT round trip leaves the token MockServer already holds untouched. The sibling
  `GET /mockserver/config` endpoint had always masked it, so this closes an inconsistency between the
  two. A new guard test derives the credential set by reflection over the configuration properties and
  fails the build for any future property with a credential-shaped name that is readable, so the class
  of bug cannot recur silently — it also documents, and asserts, the one property deliberately left
  readable (`dashboardAnalyticsKey`, an ingest-only analytics project key the browser dashboard must
  read back to start up, so masking it would break analytics while protecting nothing).
- **gRPC binary metadata (`-bin`) expectations now match regardless of base64 padding.** The gRPC wire
  format requires a metadata value whose key ends `-bin` to be base64, and MockServer passes that value
  through exactly as written — it never encodes or decodes it. But grpc-java strips the `=` padding when
  it writes the value, so what arrives is `AQIDBA`, never the `AQIDBA==` that `Base64.getEncoder()`
  produces and that a user naturally writes into an expectation. The padded form therefore never matched
  a real gRPC client, with no error and no diagnostic — just an unmatched request. Both spellings are now
  treated as the same value. This applies only to header names ending `-bin`; every other header is
  matched as before, and padding is only ignored for a structurally valid base64 value, so a regular
  expression or a JSON Schema matcher is unaffected.
- **gRPC expectations can now match inbound metadata on an HTTP/2 bidirectional-streaming request.** The
  bidi router built the request it matched against from the request path alone and discarded every
  header the client sent, so `withHeader("x-tenant-id", ...)` on a bidi expectation silently never
  matched over HTTP/2 — while the same expectation did match over HTTP/3. Inbound metadata is now mapped
  onto the request on both transports. Interactive breakpoints registered for the inbound-stream phase
  can likewise be qualified by metadata.
- **A client can no longer spoof the `x-grpc-service` / `x-grpc-method` headers MockServer derives.**
  These headers are set by MockServer from the gRPC request path so expectations can match on service
  and method. A client sending its own copy had it kept alongside the derived value rather than
  replaced, and because header matching is a subset match, an expectation qualified by the forged
  service name could match a request belonging to a different service. Any client-supplied
  `x-grpc-service`, `x-grpc-method`, `x-grpc-original-content-type` or `x-grpc-client-streaming` is now
  removed before the derived value is set, on every transport. As part of this, an empty-bodied gRPC
  request over HTTP/1.1 or HTTP/2 now receives the derived service/method headers it was previously
  missing, matching the HTTP/3 behaviour.
- **Percent-encoded request paths are now matched under the WAR / servlet deployment.** When MockServer
  runs as a WAR (e.g. in Tomcat), a request for a path such as `/ab%40c.de` was not decoded back to
  `/ab@c.de` whenever the container reports a `null` path-info — which a servlet container does for a
  default-servlet (`/`) mapping. The decoder then fell back to the still-percent-encoded raw request
  URI, so the request failed to match an expectation registered for the decoded path and returned `404`
  instead of the mocked response. The fallback now percent-decodes the raw request URI (preserving a
  literal `+`, which in a path is not a space), so encoded paths match consistently regardless of servlet
  container or context configuration, guarded end-to-end by a WAR/Tomcat regression test deploying the
  servlet with a default-servlet (`/`) mapping, for which the container does report a `null` path-info.
- **The Rust client can now match a header, query parameter, cookie or path parameter whose value starts
  with `!` or `?`.** MockServer's plain-string matcher form encodes negation as a leading `!` and
  optionality as a leading `?`, and the server strips those markers unconditionally when reading. A value
  whose own first character is a marker therefore could not be expressed: asking for "`X-Tag` is exactly
  `!foo`" went over the wire bare and was read back as "`X-Tag` is anything but `foo`" — which matches
  almost every request, so the expectation silently passed for the wrong reason rather than failing. A new
  `MatcherValue` type (with `literal`, `not_literal` and `optional_literal` constructors) holds the value
  and the flags apart, and the request builder gains `header_matcher`, `query_param_matcher`,
  `cookie_matcher` and `path_param_matcher`; a value that the plain form would misread is sent as the
  object form (`{"not":false,"value":"!foo"}`), which the server reads verbatim. This matches the escape
  the Java and Go clients already had. The change is additive — no existing public field changed type: the
  plain `headers`/`query_string_parameters`/`cookies` maps keep their `String` value types and their
  marker-parsing meaning, and new `header_matchers`/`query_string_parameter_matchers`/`cookie_matchers`
  fields carry the escaped values, with a hand-written `Serialize`/`Deserialize` on `HttpRequest` letting a
  matcher map stand in for its plain counterpart under the same wire key. Ambiguity is decided by
  re-parsing the plain form rather than by testing for a leading marker, so every value that already
  round-tripped stays byte-identical on the wire — including `not_literal("!foo")`, which still serialises
  as the shorter `"!!foo"`. The read path decodes both wire forms, so an expectation carrying an escaped
  value survives being read back through `retrieve_active_expectations` and `retrieve_recorded_requests` —
  including one written by a MockServer or another client that emits the object form itself.
- **The .NET client can now match a header, query parameter, cookie or path parameter whose value starts
  with `!` or `?`.** MockServer's plain-string matcher form encodes negation as a leading `!` and
  optionality as a leading `?`, and the server strips those markers unconditionally when reading. A value
  whose own first character is a marker therefore could not be expressed: asking for "`X-Tag` is exactly
  `!foo`" went over the wire bare and was read back as "`X-Tag` is anything but `foo`" — which matches
  almost every request, so the expectation silently passed for the wrong reason rather than failing. A new
  `MatcherValue` type (with `literal`, `not_literal` and `optional_literal` constructors) holds the value
  and the flags apart, and the request builder gains `header_matcher`, `query_param_matcher`,
  `cookie_matcher` and `path_param_matcher`; a value that the plain form would misread is sent as the
  object form (`{"not":false,"value":"!foo"}`), which the server reads verbatim. This matches the escape
  the Java and Go clients already had. The change is additive — no existing public field changed type: the
  plain `headers`/`query_string_parameters`/`cookies` maps keep their `String` value types and their
  marker-parsing meaning, and new `header_matchers`/`query_string_parameter_matchers`/`cookie_matchers`
  fields carry the escaped values, with a hand-written `Serialize`/`Deserialize` on `HttpRequest` letting a
  matcher map stand in for its plain counterpart under the same wire key. Ambiguity is decided by
  re-parsing the plain form rather than by testing for a leading marker, so every value that already
  round-tripped stays byte-identical on the wire — including `not_literal("!foo")`, which still serialises
  as the shorter `"!!foo"`. The read path decodes both wire forms, so an expectation carrying an escaped
  value survives being read back through `retrieve_active_expectations` and `retrieve_recorded_requests` —
  including one written by a MockServer or another client that emits the object form itself.
  almost every request, so the expectation silently passed for the wrong reason rather than failing. The
  .NET client gains `MatcherValue` with `Literal`, `NotLiteral` and `OptionalLiteral`, and the request
  builder gains `WithHeaderMatcher`, `WithQueryStringParameterMatcher`, `WithCookieMatcher` and
  `WithPathParameterMatcher`; a value that the plain form would misread is sent as the object form
  (`{"not":false,"value":"!foo"}`), which the server reads verbatim. This matches the escape the Java and
  Go clients already had. Ambiguity is decided by re-parsing the plain form rather than by testing for a
  leading marker, so every value that already round-tripped stays byte-identical on the wire — including
  `NotLiteral("!foo")`, which still serialises as the shorter `"!!foo"`. The existing plain-string maps and
  builder methods are unchanged and keep their current marker-parsing meaning, so this is additive: a new
  `JsonConverter<HttpRequest>` lets a matcher map stand in for the plain one under the same wire key.
  Retrieving active expectations and recorded requests decodes the object form too, so an escaped value
  survives being read back — including one written by a MockServer or another client that emits it.
- **The gRPC fail-safe diagnostics are no longer silent at global log level WARN or ERROR.** When decoding
  an upstream gRPC response fails, or a descriptor directory / proto file cannot be loaded, MockServer
  deliberately swallows the error and carries on — but logs a WARN so the fallback is diagnosable. Those
  three log entries set the message type to WARN but never set the log *level*, which defaults to INFO, and
  the logger filters on level rather than type — so at the WARN/ERROR log levels a production operator is
  most likely to run, the entries were dropped and the fail-safe was completely silent. They now log at WARN
  as intended.
- **The PHP client can now express four more action types the server accepts.** `grpcBidiResponse` (gRPC
  bidirectional streaming, with a `GrpcBidiRule` sub-builder for its per-inbound-message rules and a
  `GrpcBidiMessage` type that — unlike `GrpcStreamMessage` — carries the `templateType` the bidi wire shape
  allows), `httpForwardValidateAction` (forward and validate against an OpenAPI spec), `httpForwardWithFallback`
  (forward with a fallback response on failure), and the `httpTemplate` shape served under both
  `httpResponseTemplate` and `httpForwardTemplate`. Each is attachable via `Expectation` and the fluent
  `when(...)` chain. Two nested fields remain unmodelled and are called out in the source: `httpTemplate`'s
  `responseModifier`, and the `httpObjectCallback` action (which needs a callback WebSocket the REST-only PHP
  client does not implement).
- **A header, query-parameter or cookie whose name literally begins with `!` or `?` is no longer inverted
  when an expectation is serialised and re-read.** Collection keys go on the wire as JSON field names, and
  the plain-string encoding prefixes `!` for negation and `?` for optional — markers the reader strips
  unconditionally. A stored expectation whose header name was literally `!foo` was therefore written as the
  field name `"!foo"` and read back as "name is NOT foo", matching every request that does **not** carry
  that header: the exact inverse of what was asked. The same applied to `?`-prefixed names and to all three
  collections (headers, query parameters, cookies). Collections containing such a name are now written in
  the array form (`[{"name": {"value": "!foo"}, ...}]`), whose name the reader takes verbatim; every other
  collection is byte-identical to before, because the array form is used only where the compact form would
  corrupt the value. The cookie reader was extended to accept an object-form name inside an array item,
  which it previously could not.

  **Scope — this does not yet make such a name matchable end-to-end.** The fix covers serialisation and
  re-reading only. The inbound request parser still strips the marker, so a request carrying a header named
  `!foo` arrives as `foo` and a literal `!foo` matcher will not match it; a follow-up covering the
  request-parsing path is required for that. `?`-prefixed header names are unreachable regardless, since
  `?` is not a legal `tchar` in RFC 7230 and such a header does not survive the transport. Query-parameter
  names do arrive intact, but end-to-end behaviour there is **unverified**: a retrieved `"!q"` is
  byte-identical whether the stored key is a literal `!q` or a negated `q`, so the two cannot be told apart
  from the outside — the same blindness this fix addresses, one layer further out.

  **Note for anyone validating expectations against the published JSON schema:** the array forms of
  `keyToMultiValue` and `keyToValue` now type `name` and `values`/`value` as `stringOrJsonSchema` rather
  than plain strings, so a malformed entry reports both the failing branch and the enclosing `oneOf` — one
  extra line per bad entry, matching how the map form has always reported.
- **An incoming request whose header or cookie name (or value) begins with `!` or `?` is now recorded
  and matched literally, completing the round-trip the previous change opened.** The serialisation fix
  above let an expectation *express* a literal `!foo` name, but the inbound request parser still stripped
  the marker — a real request carrying a header named `!foo` was recorded as `foo`, so the literal matcher
  could never match it. The parser built header and cookie names and values through the same
  marker-parsing `NottableString.string(name)` used for matcher input, which is wrong for an actual HTTP
  message: a real request has literal names and values, never matchers. The netty and servlet request
  mappers now construct them as literals, so a header named `!foo` is recorded as `!foo` and matched by a
  literal `!foo` expectation while remaining distinct from a plain `foo` header — the two now match
  **differently**, which is the whole point. Query-parameter names were already recorded literally (they
  go through a different constructor) and are unchanged. `?`-prefixed header names remain unreachable, as
  `?` is not a legal `tchar` and such a header does not survive the HTTP transport. **Behaviour note:** this
  changes matching only for the rare case of a request whose actual header or cookie name starts with `!` or
  `?`. Such a name was previously recorded as the negation of the un-prefixed name — a `!foo` header was
  indistinguishable from a negated `foo` — and is now recorded literally, so it is matched by a literal
  `!foo` matcher and no longer conflated with `foo`. A "name is NOT `foo`" matcher still matches a `!foo`
  header, both before and after this change, since `!foo` is itself a name that is not `foo`.
- **The Go client can now match a header, query parameter, cookie or path parameter whose value starts
  with `!` or `?`.** MockServer's plain-string matcher form encodes negation as a leading `!` and
  optionality as a leading `?`, and the server strips those markers unconditionally when reading. A value
  whose own first character is a marker therefore could not be expressed: asking for "`X-Tag` is exactly
  `!foo`" went over the wire bare and was read back as "`X-Tag` is anything but `foo`" — which matches
  almost every request, so the expectation silently passed for the wrong reason rather than failing. The
  Go client gains `MatcherValue` with `Literal`, `NotLiteral` and `OptionalLiteral`, and the request
  builder gains `HeaderMatcher`, `QueryStringParameterMatcher`, `CookieMatcher` and
  `PathParameterMatcher`; a value that the plain form would misread is sent as the object form
  (`{"not":false,"value":"!foo"}`), which the server reads verbatim. This matches the escape the Java
  client already had. Ambiguity is decided by re-parsing the plain form rather than by testing for a
  leading marker, so every value that already round-tripped stays byte-identical on the wire — including
  `NotLiteral("!foo")`, which still serialises as the shorter `"!!foo"`. The existing plain-string maps
  and builder methods are unchanged and keep their current marker-parsing meaning, so this is additive.
  `RetrieveActiveExpectations` and `RetrieveRecordedRequests` decode the object form too, so an
  expectation carrying an escaped value survives being read back — including one written by a MockServer
  or another client that emits the object form itself.
- **The PHP client's action builders now carry nine further fields the server accepts.** `HttpError` and
  `HttpForward` could not express `delay`; `HttpResponse` could not express `trailers`,
  `generateFromSchema`, `statusCodeRange` or `recoverAfter`; `HttpSseResponse` and `HttpWebSocketResponse`
  could not express `templateType`, so a templated SSE or WebSocket body was sent without the engine that
  renders it; and `HttpLlmResponse` could not express `primary`, which the previous release note claimed was
  closed for every action builder — it was missed because that class lives under `src/Llm/` and the
  enumeration behind that change only looked at `src/`. A new `RecoverAfter` builder covers the
  retry/recovery primitive (`failTimes`, `failResponse`, `idempotencyHeader`); `failTimes` of 0 is emitted
  rather than dropped, because 0 makes the primitive deliberately inert and silently omitting it would turn
  a configured no-op into an absent field. These gaps were invisible to the round-trip fidelity harness,
  which replays raw JSON through `Expectation`'s `rawData` and never exercises a typed builder, so coverage
  comes from the per-class builder tests.
- **The PHP client's typed builders can now express `primary`, `streamError` and `graphqlSubscriptionFilter`.**
  `HttpResponse`, `HttpForward` and `HttpError` had no `primary()`, so a multi-action expectation built with
  them omitted the action selector entirely — the server requires exactly one action to be marked primary,
  so this could silently change which action a re-submitted expectation executes. `HttpError` also could not
  express `streamError` (reset the matched stream with an HTTP/2 `RST_STREAM` / HTTP/3 `RESET_STREAM` code
  instead of responding), and `HttpWebSocketResponse` could not express `graphqlSubscriptionFilter`, so PHP
  users could not build the `graphql-transport-ws` subscription filtering that became settable on a running
  server earlier in this release. A new `GraphQLSubscriptionFilter` builder covers the filter's own fields
  (`query`, `operationName`, `variablesSchema`, `selectionSetMatchType`, `fields`). The equivalent gaps were
  closed for Go, .NET and Rust earlier in this release; PHP was missed because its round-trip fidelity
  harness replays raw JSON through `Expectation`'s `rawData` and never touches the typed builders, so it
  reported zero gaps for fields the typed model could not express at all. Coverage for these fields
  therefore comes from the per-class builder tests, which were extended accordingly.
- **The dashboard no longer widens an HTTP-only expectation into a wildcard when you edit it.** `secure`
  is a tri-state request matcher on the server — `true` matches HTTPS only, `false` matches HTTP only, and
  an absent field matches either — but the Composer modelled it as a two-state switch labelled "HTTPS only"
  and emitted the field only when it was on. An expectation carrying `secure: false` therefore loaded as
  OFF and was re-saved with the field **absent**, silently changing an HTTP-only matcher into one that also
  matches HTTPS; merely opening such an expectation and saving it changed what it matched. The control is
  now a three-way **Any / HTTPS only / HTTP only** selector, and `false` round-trips as `false`. Four of the
  nine generated-code tabs — Python, Go, C# and Rust — additionally dropped `secure: false` through
  `=== true` or truthiness guards even once the shared builder emitted it, so the generated client code
  claimed a wildcard the dashboard did not; all four now emit the field whenever it is a boolean. Note the
  label change is part of the fix rather than cosmetic: with only two states, OFF genuinely meant "match
  either", so omitting the field was correct — it is the third state that makes `false` expressible.
- **The client-fidelity fixture gate now checks one level deeper.** It compared only the immediate
  properties of each action schema, so a field nested inside an inline object could be expressible by zero
  clients while the gap manifest read clean, because no fixture ever probed it —
  `httpWebSocketResponse.graphqlSubscriptionFilter.type` and `.variablesSchema` were both in that state.
  The gate now also requires every child property of an inline nested action object to be exercised, and
  the WebSocket GraphQL fixture covers both fields. Following `$ref` into other schemas reaches a further
  64 fields across 17 actions and is deliberately left as separate work rather than folded in here.
- **`/mockserver/debugMismatch` now reports the genuinely closest expectation instead of the first one
  registered.** The endpoint ranked candidates by the number of differing fields, but request matching
  fails fast on the first non-matching field — so every mismatched expectation recorded exactly one
  difference, every candidate tied on the count, and the comparison could only ever be won by the first
  mismatched expectation encountered. An expectation differing solely in one header was reported as no
  closer than one differing in method, path and header. `matchedFieldCount` was affected for the same
  reason, reporting `totalFields - 1` for every expectation however badly it missed; it now varies with
  how much actually matched, so expectations can be compared against one another. Note the **absolute**
  value remains approximate: `totalFieldCount` is the size of the whole match-field enum (18), six members
  of which — `operation`, `openapi`, `dnsName`, `dnsType`, `dnsClass`, `binaryBody` — belong to other
  matcher types and are never assessed for an HTTP expectation, so they are still counted as matched.
  Treat these counts as a relative ranking signal rather than an exact score. Diagnostic evaluations now opt
  out of fail-fast for the duration of that one evaluation (`MatchDifference.collectAllDifferences()`), so
  the endpoint sees every differing field and can rank on it. The opt-out is request-scoped rather than a
  change to the shared matcher configuration, so matching for real requests is unchanged — the fail-fast
  short-circuit that normal matching depends on still applies, and the match verdict is unaffected either
  way because it is decided by the same all-fields-evaluated calculation.
- **The Node client's type declarations are no longer generated from a schema three major versions stale,
  and CI now fails when they drift.** `mockserver-client-node/scripts/build_server_typescript.sh` generated
  `mockServer.d.ts` from a *remote* SwaggerHub schema pinned to `mock-server-openapi/5.15.x`, fetched over
  the network and diffed by nothing. Because no CI step ever regenerated it, the file quietly stopped being
  generated and became hand-maintained while the script still claimed to produce it — so running the script
  would have silently reverted the Node types to a 5.15-era contract. Generation now reads the in-repo spec,
  so the input is versioned alongside the code and reviewable in the same diff. Investigating this surfaced
  that the *spec*, not the types, is what is behind: the in-repo OpenAPI spec does not declare seven
  expectation actions the server accepts (`binaryResponse`, `dnsResponse`, `grpcBidiResponse`,
  `grpcStreamResponse`, `httpForwardValidateAction`, `httpForwardWithFallback`, `httpLlmResponse`), so
  regenerating from it today would *drop* those actions and break the type-level fidelity gate. The script
  therefore refuses to overwrite when regeneration would lose actions, naming them and pointing at the spec,
  and a new Node test pins the gap as a ratchet that fails both when it widens and when it closes without
  the pin being updated. A companion check keeps the two committed copies of the spec byte-identical and
  asserts `mockServer.d.ts` declares every action the server's authoritative `expectation.json` schema
  declares — and, in the other direction, declares nothing the server would reject. That reverse check
  found a real defect: `openAPIDefinition` was declared as a top-level expectation member, but it is a
  *request-level* concept and `expectation.json` sets `additionalProperties: false`, so an expectation
  using it typechecked green and was then rejected by the server with 400 "incorrect expectation json
  format". It has been removed; the OpenAPI form of a request matcher remains available where it belongs,
  through `httpRequest` (`RequestDefinition` already includes `OpenAPIDefinition`). A commit touching only
  the published copy of the spec now also triggers the Node pipeline, so the byte-identity assertion fires
  on the change most likely to break it rather than on the next unrelated commit.
- **The Node client's expectation actions are now proven against a real server, not just against the bytes
  the client emits.** The existing action-key test captured requests with a throwaway HTTP listener that
  replied `201` to anything, so it proved the client sent exactly one action but not that a real MockServer
  accepted the result — and its per-action payloads were in fact schema-invalid. A new test creates an
  expectation through the client for every action the server's schema declares and asserts the server both
  accepted and stored it, plus asserts the rejection path via `.then(onFulfilled, onRejected)` rather than
  `await`, so a regression of the single-argument `.then()` bug that once turned failed verifications into
  passes cannot hide behind `await`'s own reject path.
- **The dashboard code generator no longer drops a `false` for the booleans where absent and `false` mean
  opposite things — C#, Rust, and every language for `fallbackOnTimeout`.** A non-optional boolean guarded on
  truthiness silently omits `false`, which only matters when the server reads an absent field as something
  other than `false` — and for these fields it does. `fallbackOnTimeout` is read as
  `getFallbackOnTimeout() == null || getFallbackOnTimeout()`, so unticking "Fallback on timeout / connection
  error" produced code that still fell back; it was dropped by the shared payload builder and so by all nine
  tabs. `closeConnection` was fixed earlier for the shared builder and the Java tab, but the C# and Rust
  emitters are independent transducers over the generated JSON and each carried its own `=== true` guard, so
  both still dropped it for SSE, WebSocket, and gRPC streaming. The generator now emits the actual value at
  every site. Loading an existing expectation for editing was inverted the same way: an absent
  `closeConnection` (SSE/WebSocket) or `fallbackOnTimeout` loaded as OFF, which both misreported the live
  behaviour and — now that the value is always written back — would have silently flipped it on the next save;
  these now load as ON, matching the server. gRPC streaming is deliberately left reading absent as "don't
  close", which is what its handler does. `secure` was reviewed and deliberately left alone: its matcher
  treats absent as a wildcard and the switch means "HTTPS only", so omitting `false` is correct there.
- **`graphqlSubscriptionFilter` can now actually be set on a running server — previously it was unreachable
  by every route, making the GraphQL subscription frame-ordering fix latent.** Two independent blocks each
  made it impossible. The expectation schema declared the filter object with `additionalProperties: false`
  and no `type` property, so it rejected the `"type":"GRAPHQL"` discriminator that every client emits when
  serialising a GraphQL body; and behind that,
  `HttpWebSocketResponseDTO.graphqlSubscriptionFilter` is declared as the concrete `GraphQLBodyDTO` rather
  than the polymorphic `BodyDTO`, so the `BodyDTODeserializer` — registered against `BodyDTO` and matched by
  exact class — never ran for it, leaving Jackson to instantiate an all-final type with no creator, which it
  cannot do. Raw JSON therefore failed too, with an opaque parse error rather than a schema error. The
  schema now accepts the discriminator and `GraphQLBodyDTO` has a `@JsonCreator`, so both the typed clients
  and hand-written JSON work; a typeless filter (`{"query": ...}`, the form the documentation shows) keeps
  working unchanged. This was a single site, not a class of defect: every other body DTO shares the
  all-final/no-creator shape, but no other field anywhere declares a concrete body DTO subtype, so all of
  them are deserialised polymorphically and were never affected.
- **WASM custom rules now actually work in the standalone jar and the Docker images — previously they never
  matched.** The chicory WASM interpreter was declared an optional dependency of `mockserver-core`. Maven
  keeps an optional dependency on its own module's classpath but does not propagate it to consumers, so
  `mockserver-netty` — the module the `jar-with-dependencies` and every Docker image are assembled from —
  never received it. Each shipped artifact therefore carried the nine `org/mockserver/wasm` classes with no
  interpreter behind them: uploading a module and registering a `WASM` body matcher both succeeded, then
  every match failed closed with a `NoClassDefFoundError`, so a documented feature silently never matched.
  The interpreter is now bundled (about 360 KB, roughly 0.35% of the standalone jar) and WASM matching works
  out of the box. WASM remains gated off by default via `wasmEnabled=false`, so the bundled interpreter is
  inert until you opt in, and `mockserver-client-java` still excludes it. Note this only ever affected
  assembled artifacts — embedding `mockserver-core` directly and declaring chicory yourself worked
  throughout. The failure was already fail-closed and logged a `WARNING` naming the missing class; it did
  not hang or drop connections.
- **AsyncAPI MQTT wildcard subscriptions no longer verify as zero matches.** A message is delivered on a
  concrete topic, so it was recorded under that topic — but a subscription made with a wildcard filter was
  verified under the *filter*, and the two were compared by string equality. Any subscription containing `+`
  or `#` therefore recorded messages that could never be retrieved, and every verification against it
  reported zero matches while the mock appeared healthy. Retrieval now matches concrete topics against the
  filter per MQTT 3.1.1 §4.7 (`+` single level, `#` multi-level including the parent level, and no wildcard
  match for reserved `$` topics), for both the MQTT 3.1.1 and MQTT 5 subscribers. **Behaviour change:** a
  verification against a wildcard channel that previously found nothing may now legitimately match, so a
  suite that asserted `atMost`/`exactly 0` against a wildcard channel can start failing correctly.
- **AsyncAPI AMQP publishes that reach no queue are now reported instead of silently discarded.** Messages
  were published without the `mandatory` flag and without publisher confirms, so per AMQP 0-9-1 §3.1.3 a
  message routed to an exchange with no bound queue was dropped by the broker with no error — while
  MockServer reported a successful publish. Declaring an exchange does not bind any queue to it, so this was
  the default outcome for an exchange-routed channel with no consumer attached. Publishes now use
  `mandatory=true` on a channel in publisher-confirm mode and raise an error when the broker returns the
  message as unroutable. Publisher confirms are a RabbitMQ extension rather than part of AMQP 0-9-1, and
  a broker refusing `confirm.select` closes the channel; the publisher replaces the dead channel and
  continues without confirms rather than failing every publish, though that fallback is verified only
  against a modelled broker response and not a live non-RabbitMQ broker — RabbitMQ is the supported broker.
  The confirm wait uses `waitForConfirms` rather than `waitForConfirmsOrDie`, which closes the channel on a
  nack or timeout and would leave every later publish failing for the lifetime of the mock. **Behaviour change:** publishing to an exchange-routed channel
  with nothing bound now fails rather than silently succeeding. A load-time (`publishOnLoad`) failure no
  longer aborts the spec load — the mock stays loaded and the failure is reported under `validationIssues`,
  so a consumer that binds its queue after MockServer starts is not locked out — and a failed scheduled
  publish cycle is logged without cancelling the schedule, which would otherwise stop periodic publishing
  permanently and silently.
- **Kafka publish no longer reports success before delivery is known.** `KafkaProducer.send` is asynchronous
  and its failure callback only logged a warning, so the control plane could answer a successful publish for
  a message the broker never accepted. `MessagePublisher` gained a `flush()` which blocks until every
  in-flight send is acknowledged and rethrows the first delivery failure; the AsyncAPI orchestrator calls it
  before returning from `publishAll()`. Because a publish can now throw, both callers of `publishAll()` are
  guarded: a failed scheduled cycle no longer cancels the schedule (`scheduleAtFixedRate` suppresses all
  later executions once a task throws, which would have stopped periodic publishing permanently and
  silently), and a failed load-time publish no longer rolls back the whole mock. Failures are contained
  per message, so one unroutable channel no longer prevents every other channel in the spec from
  publishing; the remaining channels are published and an aggregate error names those that failed. The `asyncMessagePublished`
  metric is now incremented after delivery is confirmed rather than before, so a rejected send no longer
  counts as published. **Behaviour change:** a publish that fails at the broker now surfaces as an error
  rather than a logged warning behind a success response.
- **Registry-less Avro decoding no longer mis-decodes messages written with a different schema.** The schema
  id embedded in the Confluent wire-format header was ignored and every message was decoded with the
  configured inline schema. Avro binary carries no field names or types, so a message written with a
  different schema of the same shape decoded without error into **silently transposed values**. The inline
  schema is now only applied to messages carrying its schema id (`avroSchemaId`, default `1`, which the
  control plane also passes to the subscriber so both sides agree); a message framed with any other id is
  recorded undecoded with a warning rather than decoded incorrectly. **Behaviour change:** code constructing
  `KafkaAvroMessageSubscriber` directly while publishing under a non-default schema id must now pass the
  matching id to the new constructor overload.
- **gRPC server reflection now returns the full transitive closure of a proto file's imports.** The
  `file_containing_symbol` and `file_by_filename` responses carried only the requested file and its DIRECT
  dependencies. Reflection clients (`grpcurl`, and any consumer of grpc-java's `ProtoReflectionService`)
  build a descriptor pool from exactly the files in the response and resolve every `import` against that
  pool, so any import chain deeper than one level failed to link: for `a.proto` importing `b.proto`
  importing `c.proto` the client received `{a, b}` and could not resolve b's import of c. Reflection was
  therefore unusable against any realistic multi-file proto. The response now carries the whole closure,
  breadth-first and de-duplicated, so a diamond import appears once and an import cycle terminates.
- **BREAKING BEHAVIOUR: `grpc.health.v1.Health/Check` now fails with `NOT_FOUND` for an unregistered service.** Any
  service name resolved to the default status, so a `Check` for a service that was never registered — most
  commonly a typo'd service name — returned `SERVING`, and a test asserting "this dependency is reported
  unhealthy" passed while proving nothing. The health specification requires the RPC to fail with status
  `NOT_FOUND` when the server does not know the service. The empty service name is unchanged: it remains
  the overall-server health target and always answers. Note that setting the overall status (`PUT` with an
  empty service name) no longer makes arbitrary service names answerable — register each service whose
  health should be checkable.
- **HTTP/3 header handling is no longer sensitive to the default locale.** Six `String.toLowerCase()`
  calls in the HTTP/3 package ran without a locale — four folding response header and trailer FIELD
  NAMES, and two parsing the `content-type`. Under a Turkish default locale `"CONNECTION"` folds to
  `"connectıon"` (dotless i), producing non-ASCII field names on the wire and silently bypassing any
  filter that compares against a lowercase literal. All six now use `Locale.ROOT`. The fold guarding
  the forbidden-header filter was pinned as part of the RFC 9114 filter fix above; this covers the
  remaining five, including the trailer field names, where the same fold produces a malformed
  trailing HEADERS frame. Note that four of the six field names RFC 9114 forbids contain an `I`
  (`connection`, `keep-alive`, `proxy-connection`, `transfer-encoding`), so a locale-sensitive fold
  bypassed the filter for most of them — that interaction is now covered by a test.
- **`connectionOptions` set on a response served over HTTP/3 now logs a warning instead of silently doing
  nothing.** `ConnectionOptions` is honoured throughout the HTTP/1.1 response writer and was not read
  anywhere in the HTTP/3 path, so `closeSocket`, `chunkSize`, `chunkDelay`, `closeSocketDelay`,
  `suppressContentLengthHeader` and `contentLengthHeaderOverride` were accepted and ignored while the
  expectation still reported as created — fault injection and connection control appeared to apply and did
  not. These are now reported as not yet implemented on HTTP/3, and `suppressConnectionHeader` /
  `keepAliveOverride` are reported as inapplicable (HTTP/3 forbids the headers they govern). The response
  is still served rather than rejected, so a suite that sets `connectionOptions` globally and includes
  HTTP/3 keeps working.
- **An MCP tool that throws now returns a JSON-RPC error instead of hanging the client.** MCP `POST`
  processing runs on a separate executor, and only `JsonProcessingException` was caught. Any other
  exception escaping a tool handler propagated out of the executor task with no response ever written,
  so the client received nothing at all and blocked until its own timeout — the failure mode most likely
  to be misread as a network problem rather than a tool bug. Method dispatch now converts any exception
  into a `-32603 Internal error` response carrying the request's `id`, and the transport handler has a
  last-resort backstop for anything failing outside dispatch. The exception detail is logged but not
  returned, since tool arguments and internal state routinely appear in exception messages. Because the
  conversion happens per request rather than per POST, one failing entry in a batch no longer discards
  the responses to the entries beside it.
- **A JSON-RPC notification no longer produces an unparseable mocked response.** Every MCP and A2A mock
  builder, in all eight client languages, emits `"id": $!{request.jsonRpcRawId}`. A notification carries
  no `id`, so that rendered to nothing and produced `{"jsonrpc":"2.0","result":{},"id": }` — not valid
  JSON, meaning a client could not even parse the response to discover it was spurious. The id now
  renders as the JSON `null` literal. This is a partial fix: JSON-RPC says a notification should receive
  no response at all, which a matcher cannot currently express (matching and responding are not
  separable), so the mocked response is still sent — it is now merely well-formed. To be explicit,
  `"id": null` is **not** the conformant end state either: JSON-RPC 2.0 §5 requires a response id to
  equal the request id and reserves `null` for a response to a request whose id could not be
  determined. The conformant behaviour is to send no response at all, which remains outstanding.
  Requests carrying a real id, and non-JSON-RPC requests, are unaffected.
- **MCP now returns the specified HTTP status for an unusable session, so clients can recover.** A POST
  naming a session the server does not recognise — including one it has already terminated — returned
  `200` with a JSON-RPC error. MCP 2025-06-18 `basic/transports` requires `404 Not Found`, and states
  that a client receiving `404` MUST start a new session; with a `200` the client cannot distinguish a
  dead session from an application error, so it can never perform the mandated recovery and loops
  against a session that will never work again. Unknown and terminated sessions now return `404`, a
  request that omits a required `Mcp-Session-Id` returns `400`, and a request against a live session
  that has not yet completed the handshake returns `400` with a message saying so (previously all three
  were indistinguishable). `DELETE` already returned `404` for an unknown session; `POST` now matches it.
  A batch is validated once for the whole POST rather than per element, so a batch consisting only of
  notifications sent against a dead session is now rejected rather than silently accepted with `202`.
- **MCP `ping` is now answered before the handshake completes, as the lifecycle spec requires.** A client
  may open a session with `initialize` and, before sending `notifications/initialized`, `ping` to check
  liveness — MCP 2025-06-18 `basic/lifecycle` names `ping` as the explicit exception to the rule that a
  session must be initialized before it handles requests. MockServer wrongly gated it, so that `ping`
  returned the "session has not completed initialization" error instead of a pong. `ping` is now exempt
  from the initialized precondition. The exemption is only from that precondition: a `ping` with no
  session id still returns `400` and one naming an unknown session still returns `404`, and no method
  other than `ping` is affected.
- **`run_mcp_contract_test` no longer certifies a non-conformant notification response as conformant.**
  The shipped MCP conformance checker accepted HTTP `200`, `202` or `204` for a notification and
  inspected the body only for an `error` member, so a server answering `200` with a JSON-RPC result body
  passed — which is exactly the shape MockServer's own mock builders emit. MCP 2025-06-18
  `basic/transports` makes `202 Accepted` with no body a MUST, and the check now enforces that, citing
  the specification in the failure. Reports that previously passed against a lenient server will now
  show this check as failed, which is the intended correction: a conformance tool that tolerates the
  violation it exists to detect certifies nothing. The body requirement is scoped to the `202` response:
  the same section permits a rejected notification to carry a JSON-RPC error body under an HTTP error
  status, so that shape is not flagged.
- **Trailers on a body-less response no longer produce a malformed HTTP/1.1 message.** A response carrying
  trailers was unconditionally forced to `Transfer-Encoding: chunked` with a `Trailer` announcement header.
  For a body-less status (`1xx`, `204`, `205`, `304`) Netty's encoder emits neither a chunked body nor the
  terminating `0\r\n\r\n` chunk, and for `304` it does not strip the `Transfer-Encoding` header either — so a
  `304` with trailers went onto the wire advertising chunked framing that never terminated, which can leave a
  peer waiting and wedge a keep-alive connection. Body-less responses are no longer forced to chunked and no
  longer announce trailers they cannot deliver. The trailers remain attached to the response so HTTP/2 and
  HTTP/3, which can legitimately carry trailers on a body-less response via a trailing HEADERS frame, still
  deliver them.
- **Azure blob metadata keys containing characters above `U+00FF` are no longer corrupted.** The key escape
  formatted with `%02x`, which silently widens to four hex digits above `0xFF`, while the decoder always
  consumed exactly two — so a metadata key of `中文` was written as `_4e2d_6587` and read back as `N2de87`.
  Escapes are now a fixed four hex digits and round-trip for all keys, including non-ASCII and emoji. Keys
  written by an earlier version that contain escaped characters will not decode correctly; blob metadata is
  ephemeral mock state, so clear the container if this matters. The shared blob-store contract suite now
  covers non-ASCII metadata keys for every backend, asserting that a store either round-trips them exactly or
  rejects the write — never silently stores a different key. S3 legitimately rejects them, since it carries
  metadata in `x-amz-meta-*` HTTP headers whose field names are ASCII tokens.
- **Mocked WebSockets now answer PING and echo CLOSE (RFC 6455 §5.5.1/§5.5.2).** MockServer performs the
  WebSocket handshake by hand, which installs only the frame encoder and decoder and contributes no
  control-frame behaviour, so a mocked WebSocket silently ignored every PING and never echoed a CLOSE. Since
  browsers, OkHttp's `pingInterval` and Java-WebSocket's connection-lost detector all ping for keepalive,
  **every long-lived mocked WebSocket session eventually died**, typically appearing as an unexplained
  disconnect part-way through a test. A PONG carrying the PING's payload verbatim is now always sent, a client
  CLOSE is echoed with the client's own status code and reason (falling back to 1000 for reserved or absent
  codes), and server-initiated closes now send 1000 NORMAL_CLOSURE rather than an empty close frame that left
  clients reporting 1005 "no status received". PING frames are still forwarded to matchers, so `frameType:
  PING` matchers are unaffected.
- **GraphQL subscriptions no longer deliver zero messages when a delay is configured.** The subscription
  sequencer recursed through the whole payload list without waiting for each `next` frame to be sent, then
  wrote the terminal `complete` immediately — so with any delay set, `complete` reached the wire *before* every
  `next`, and both Apollo and `graphql-ws` discard messages received after `complete`. A subscription with
  delays therefore delivered nothing at all. Each `next` is now chained off the previous frame's actual send
  completion, so `complete` is always last and per-message delays are cumulative rather than all firing from
  the same instant.
  **Reachability:** the subscription handler is installed only when `graphqlSubscriptionFilter` is set, and
  that field currently cannot be set over the control plane at all — the JSON schema rejects the
  `"type":"GRAPHQL"` discriminator every client emits, and `GraphQLBodyDTO` has no deserialization path
  behind it — so this path is reachable only from the embedded Java API or an `initializationClass`. The
  fix is therefore latent for control-plane users rather than a live bug they can observe today; making
  `graphqlSubscriptionFilter` settable is tracked separately.
- **The legacy `graphql-ws` subprotocol is now actually implemented.** It was accepted at handshake but only
  the `graphql-transport-ws` vocabulary was ever handled, so a subscription driven by a legacy client (still
  Apollo Client's default) never advanced: its `start` message was silently ignored. The legacy `start`,
  `stop` and `connection_terminate` messages are now handled, `data` is emitted instead of `next`, and the
  legacy single-object `error` payload shape is used. Same reachability caveat as above — this affects
  subscriptions configured through the embedded Java API or an `initializationClass` only.
- **SSE data containing a lone carriage return no longer truncates the event.** `data` was split on LF only,
  so a bare CR was emitted raw inside a `data:` line; per WHATWG an event stream is split on CRLF, CR *or* LF,
  so a real client treated the CR as a line terminator and silently dropped everything after it (and a crafted
  payload could frame an additional event). All three terminators are now split into separate `data:` lines.
  `id` and `event` were already guarded.
- **A WebSocket text matcher no longer matches non-text frames.** The text-content comparison was guarded by
  `frame instanceof TextWebSocketFrame`, so a PING, PONG or BINARY frame skipped the check entirely and fell
  through to a match — a matcher configured with text content fired its responses on the client's keepalive
  traffic. This was reachable when the frame type was set *after* the text (e.g.
  `.withText("x").withFrameType(ANY)`); the text setters otherwise pin the frame type to TEXT.
- **SECURITY: enabling control-plane authentication at runtime now actually takes effect.** The mTLS, JWT and
  OIDC handler chain was built once during server bootstrap, so enabling
  `controlPlaneJWTAuthenticationRequired`, `controlPlaneTLSMutualAuthenticationRequired` or
  `controlPlaneOidcAuthenticationRequired` afterwards — through a system property, a `Configuration` setter,
  or `PUT /mockserver/configuration` — was accepted and silently ignored: the `PUT` returned 200, a
  subsequent `GET` echoed back `true`, and the enforcement point still saw no handler, which it treated as
  "authenticated". An operator hardening a running shared or CI instance was told it was locked while it
  remained fully open, including the recorded request log, which in proxy mode can hold real captured
  credentials. The handler is now derived from the live configuration and rebuilt whenever the
  authentication-relevant configuration changes, so every route takes effect — and disabling it works too. If
  authentication is required but the handler cannot be constructed (for example an unreachable JWKS source)
  the control plane now denies every request rather than falling open.
- **SECURITY: requiring mTLS at runtime now reaches the TLS layer.** `tlsMutualAuthenticationRequired` did not
  invalidate the cached server SSL context, so enabling it on a running instance left the context pinned at
  `ClientAuth.OPTIONAL` with a trust-all trust manager and certificateless clients kept connecting. The
  context is now rebuilt whenever the client-authentication settings change, via the `Configuration` setter,
  `PUT /mockserver/configuration`, or the system property. `preventCertificateDynamicUpdate` no longer
  suppresses this rebuild — it governs certificate regeneration, not client-authentication policy.
- **SECURITY: forward-proxy credentials are now compared exactly and in constant time.** Both the `CONNECT`
  and plain-HTTP proxy paths checked `Proxy-Authorization` through a case-INSENSITIVE, short-circuiting
  comparison. Base64 is case-sensitive, so this accepted credentials differing from the configured one in
  case — roughly one bit of entropy lost per alphabetic character — and reintroduced the timing side channel
  the constant-time helper exists to close. Both paths now share one validated comparison.
- **BEHAVIOUR: `GET /mockserver/metrics` and `GET /mockserver/http3status` now require control-plane
  credentials when control-plane authentication is enabled.** Both were served with no authentication gate
  at all, alone among the control-plane endpoints served alongside them (`/dashboard`, `/openapi.yaml`,
  `/llm/optimisationReport`, `/llm/diffRuns`). `/metrics` leaks more than it appears to: metric cardinality
  scales with the number of configured expectations, so an unauthenticated caller could infer the
  expectation surface of a running instance — a real disclosure on a shared CI or sidecar deployment.
  **Default behaviour is unchanged**: control-plane authentication is opt-in and off by default, so an
  instance that has not enabled it keeps serving both endpoints unauthenticated, and no existing scrape
  configuration breaks. **If you enable control-plane authentication, scrapers must now present
  control-plane credentials** or they will start receiving `401`. This affects the Prometheus
  `ServiceMonitor` shipped in the Helm chart (`helm/mockserver/templates/servicemonitor.yaml`, which
  scrapes `/mockserver/metrics`) and the dashboard's own metrics polling. There is deliberately no
  per-endpoint opt-out: a scraper reaching a locked control plane should authenticate like any other
  client. Note `/metrics` keeps its prefixed path only — unlike its siblings it has no bare `/metrics`
  alias, so it cannot shadow a user's own mocked `/metrics` API.
- **SECURITY: `dashboardAnalyticsKey` is no longer logged in clear.** The redaction predicate matched 13
  hard-coded substrings that omitted bare `key`, so this property matched none of them. Redaction is now
  driven by the name shape (any property ending in `key`, with documented exceptions), and is guarded by a
  test that enumerates the real property surface so a future credential-shaped property cannot slip through.
- **Enabling the Velocity template sandbox at runtime now takes effect.** `velocityDisallowClassLoading` was
  read once when the engine was constructed and the engine was cached for the process lifetime, so enabling
  the sandbox later was inert. The engine is now rebuilt when the setting changes. The default is unchanged
  (the sandbox remains off).
- **gRPC bidi and WebSocket matchers no longer invert on a control-plane round-trip.**
  `GrpcBidiRuleDTO` and `WebSocketMessageMatcherDTO` collapsed their matcher to a plain `String`,
  dropping the negation flag. `buildObject()` recovered it by re-parsing a leading `!`, but a
  `matchJson` value is JSON and never starts with `!`, so a negated gRPC bidi rule became its own
  opposite whenever an expectation was retrieved and re-submitted — and `GrpcBidiRuleMatcher`
  actively depends on that flag. Both now carry the full matcher. Relatedly, a negated WebSocket
  text matcher was accepted and then **ignored** by `BidirectionalWebSocketFrameHandler`, which never
  consulted the flag at all; it is now honoured, as the gRPC equivalent already was.
- **The Node client could not create gRPC bidi, forward-validate or forward-with-fallback
  expectations.** Its list of expectation action keys was hand-maintained and had fallen three
  entries behind the server, so for those actions it injected an empty `httpResponse` alongside the
  real action; the server then counted two actions with no primary and rejected the expectation with
  "exactly one must be marked as primary". The list is now defined once, checked against the server's
  own `expectation.json` schema by a test, and the client no longer materialises an empty
  `httpResponse` when it has no default headers to add — so a future unlisted action degrades to
  "default headers not applied" rather than a hard failure.
- **Unticking "close connection" in the dashboard generated code that closed the connection anyway,
  in all 8 languages.** The code generator guarded a non-optional boolean on truthiness, so
  `closeConnection: false` was dropped from the generated snippet; for SSE and WebSocket the server
  treats an absent value as "close", making the generated code do the opposite of what was selected.
  All six generation sites now emit the actual value. (The underlying server-side inconsistency —
  SSE and WebSocket treat absent as "close" while gRPC treats it as "don't close" — is documented
  with a recommendation in `docs/code/ai-protocol-mocking.md` and left for its own change.)
- **BREAKING (Go): `ServiceChaosProfile.ConnectionDrop` is replaced by `DropConnectionProbability`.**
  Following the removal of the phantom `connectionDrop` property from the OpenAPI specification
  (below), the Go client — which was generated against that specification — still sent
  `{"connectionDrop": true}` on `PUT /mockserver/serviceChaos`, so the server ignored it and **every
  Go user who set `ConnectionDrop` got no connection drops at all**. The real property is
  `dropConnectionProbability`, and the semantics differ: it is a `0.0`-`1.0` probability, not a
  boolean, so this is a genuine API change rather than a rename. Migrate `ConnectionDrop: ptr(true)`
  to `DropConnectionProbability: ptr(1.0)`. The change breaks compilation deliberately: silently
  reinterpreting the old field would leave users believing chaos was configured when it never was. A
  new test checks every `ServiceChaosProfile` JSON tag against the server's `httpChaosProfile.json`
  schema, so a phantom property cannot be reintroduced. The PHP client's docblock, which documented
  the same non-existent key, is corrected — PHP passes the profile through verbatim, so it never sent
  the property itself.
- **The Rust, Go and .NET clients silently dropped fields on a retrieve-then-resubmit cycle.** `HttpSseResponse`
  was missing `templateType` and `primary`, `HttpWebSocketResponse` was missing `templateType`,
  `graphqlSubscriptionFilter` and `primary`, and `GrpcStreamResponse`, `BinaryResponse` and
  `DnsResponse` were missing `primary`. All are now modelled, `graphqlSubscriptionFilter` gains a
  typed `GraphqlSubscriptionFilter`, and each of these types gains the `#[serde(flatten)]` catch-all
  the other response types already had, so unmodelled server fields survive rather than being
  discarded. The same `primary` action-selector drop was present in the Go client (`HttpError`,
  `HttpForward`, `HttpResponse`) and the .NET client (`HttpError`, `HttpForward`) and is fixed in both,
  with `WithPrimary(...)` builder methods on .NET matching the existing convention. `primary` selects
  which action runs when an expectation configures more than one, so losing it on a round-trip could
  silently change which action a re-submitted expectation executes. The .NET client was also missing
  `httpError.streamError` (the HTTP/2 `RST_STREAM` / HTTP/3 `RESET_STREAM` code, which takes precedence
  over `dropConnection`), now modelled with a `WithStreamError(...)` builder.
- **The client fidelity coverage gate only inspected top-level keys, so nested fields were never
  probed** — which is why `known-gaps.json` read clean while `graphqlSubscriptionFilter` was
  expressible by no client at all, including Java. The gate now also requires every property of every
  action schema to be exercised by some fixture, and requires action-level booleans to be exercised
  with **both** `true` and `false` (the `closeConnection` defect above was masked by an SSE fixture
  that only ever used `true`). The newly-probing fixtures exposed real gaps in four more clients;
  those are now recorded explicitly in `test-fixtures/expectations/known-gaps.json` rather than
  silently passing — `httpSseResponse.templateType`, `httpWebSocketResponse.templateType` and
  `httpWebSocketResponse.graphqlSubscriptionFilter` are dropped by Python, Ruby, Go and .NET
  (additive model additions, straightforward to close), and the NottableString object form is
  unrepresentable in Go, Rust and .NET (a breaking field-type change, deliberately deferred). The
  .NET harness gained the `<unrepresentable-expectation>` sentinel the Rust one already had, so a
  fixture the model cannot deserialize at all is a recorded gap rather than an unexcusable crash. The
  Python and Ruby harnesses' exact fixture-count assertions are now lower bounds, so growing the
  shared corpus no longer fails every client at once.
- **DNS mock responses were not RFC 1035 conformant on the wire, in six ways.** No test had ever encoded a
  single DNS byte — `DnsRequestHandlerTest` built its `EmbeddedChannel` without the response encoder the
  production pipeline installs and never read the outbound datagram, so every assertion was on an internal
  cache rather than on what a resolver receives. The defects this hid:
  - **A record value containing a label of 192 octets or more corrupted the entire packet.** The label length
    was written as a single unchecked octet; at 192+ the two high bits are set, which RFC 1035 §4.1.4 defines
    as a **compression pointer**, so a resolver reinterpreted the next 14 bits as a message offset and
    misparsed everything following. Labels are now validated against the 63-octet limit (RFC 1035 §2.3.4) and
    the whole name against the 255-octet limit. Netty validates individual *label* lengths on a record's own
    owner name, which is why the label half went unnoticed there; it does **not** validate *total* name
    length, and names embedded in RDATA (CNAME, PTR, MX and SRV targets) bypass its checks entirely. An owner
    name built from legal 63-octet labels but totalling more than 255 octets previously threw after the
    response had been handed to the pipeline, so the client received **no response at all** and timed out;
    it now returns `SERVFAIL`.
  - **TXT values longer than 255 octets were truncated rather than split.** RFC 1035 §3.3.14 requires a long
    value to be split across multiple `<character-string>`s, which resolvers concatenate. Truncating at 255
    silently corrupted the two commonest real TXT payloads — DKIM public keys and long SPF records — and could
    also cut through the middle of a multi-byte UTF-8 sequence.
  - **`A` records configured with an IPv6 value (and `AAAA` with IPv4) emitted a mismatched RDLENGTH.**
    Address parsing returns whichever width the literal happens to be, so an `A` record went out as
    `TYPE=A, RDLENGTH=16`, violating RFC 1035 §3.4.1.
  - **A record with no explicit `name` was published into the root zone.** It encoded as `""`, so the client
    received `NOERROR` with `ANCOUNT=1` and no answer for the name it had asked about — a response that looks
    successful and is useless. An absent owner name now defaults to the queried name.
  - **Oversized responses were sent with the TC bit clear.** A UDP response exceeding the client's payload
    limit is now sent truncated with TC set (RFC 1035 §4.2.1) so the resolver knows to retry, and an EDNS(0)
    client's advertised buffer size is honoured before truncating (RFC 6891 §6.1.2).
  - **`RA` was never set and `RD` was never echoed.** `systemd-resolved` and other stub resolvers read `RA=0`
    as "this server offers no recursion" and skip to the next configured server, so the DNS mock could be
    bypassed entirely. Responses now echo `RD` and set `RA` and `AA`.

  Non-ASCII characters in a name were also being silently replaced with `?` (US-ASCII encoding); they are now
  encoded as UTF-8 octets with a correct length octet. Note an asymmetry that remains: an **owner** name is
  punycoded by Netty (`héllo.example.com.` is emitted as `xn--hllo-bpa.example.com.`), whereas the same string
  used as **RDATA** — a CNAME, PTR, MX or SRV target — is emitted as raw UTF-8 octets. A CNAME whose target is
  an internationalised name therefore will not line up with the owner name of the record it points at. Supply
  IDNA/punycode names explicitly on both sides if you need internationalised names to chain correctly or to
  resolve against real infrastructure. One consequence worth calling out: length limits are now checked
  against the name as configured, before Netty applies punycoding, so a label longer than 63 octets in UTF-8
  whose punycoded form would have fitted (for example 40 accented characters, which punycode to a legal
  44-octet `xn--` label) is now rejected with `SERVFAIL` rather than emitted. This is deliberately
  fail-closed and the reason is logged; supply the `xn--` form directly if you need such a name.

  These paths are now covered by wire-level tests that parse MockServer's output with **dnsjava**, an
  independent resolver implementation, rather than with MockServer's own model objects.
- **SSE, streaming bodies, Prometheus metrics and MCP delivered NOTHING to an HTTP/2 client.** These are the
  direct siblings of the server-streaming gRPC bug in issue #2419, and they failed the same silent way: the
  server logged a perfectly normal successful response while the client received zero bytes and hung until its
  own timeout. Netty's HTTP/2 codec routes an outbound response onto a stream via an `x-http2-stream-id`
  header, and when that header is absent it does not fail - it quietly allocates a fresh *server-initiated*
  stream that the requesting client is not listening to. Only the response mapper ever added that header, so
  every handler that built a Netty response by hand bypassed it: the SSE action, the metrics endpoint and the
  MCP handler never had the id at all, and the streaming-body writer *had* it but dropped it, because it copied
  only the header map and the id lives in a separate field. The #2419 fix funnelled writes through one place in
  the gRPC module only; every known direct-write site — SSE, the streaming-body writer, metrics, MCP, the gRPC
  stream handler and the two WebSocket-upgrade rejections — now goes through a single shared `Http2StreamIds`
  helper, and a new `Http2StreamIdAuditHandler` logs a WARN (once per connection) whenever an HTTP/2 response
  head still goes out without a stream id, so the next instance of this class is discovered on the first test
  run rather than in production. If you use SSE, streaming responses, `GET /mockserver/metrics` or MCP over
  HTTP/2, these now work; over HTTP/1.1 nothing changes. **One limitation remains and is not fixed here:**
  Netty routes the continuation frames of a response using a stream id latched from the last response head
  written on that connection, so two responses interleaving on a single HTTP/2 connection can still cross.
  That is inherent to MockServer's non-multiplexed HTTP/2 pipeline rather than to this change — but it is
  more reachable now than before, because a long-lived SSE stream that previously delivered nothing at all
  now stays open long enough for another response head to flip the latched id mid-stream. It is bounded to a
  single connection; use separate connections for concurrent long-lived streams if this affects you.
- **HTTP/3 responses could be rejected as malformed by a conforming client.** The HTTP/3 header conversion
  stripped only two of the five connection-specific header fields RFC 9114 section 4.2 forbids, so
  `keep-alive`, `upgrade` and `proxy-connection` were emitted on the wire, and `TE` was passed through with any
  value rather than only `trailers`. The reachable case is proxy/forward mode: an upstream response's headers
  are copied onto the model response wholesale, so an HTTP/1.1 origin answering with
  `Keep-Alive: timeout=5, max=100` had it relayed verbatim onto an HTTP/3 response. An expectation setting such
  a header explicitly does the same. A receiver "MUST treat" such a message as malformed, so this could fail a whole
  response rather than merely adding a useless header. HTTP/3 streaming responses also announced a
  `content-length` copied from the model response, describing a different body than the one actually streamed;
  it is now omitted on the streaming path, and still sent for non-streaming responses.
- **Verification could miss a just-forwarded request (race on every forward path).** The visibility guarantee
  for `verify`/`retrieve` rests on disruptor FIFO ordering — `drainDisruptor()` waits only for entries already
  *published*, so it cannot wait for one that has not been published yet. The mocked-response path logs before
  writing to the client, but every forward/proxy path did the opposite: it wrote the response first and logged
  the `FORWARDED_REQUEST` entry afterwards, leaving a window in which a client that received the response and
  immediately verified or retrieved would not see the exchange. Plain `verify(once())` hid the bug because
  `RECEIVED_REQUEST` is published early in the pipeline, but record-then-retrieve, `withResponse`,
  `withDisposition` and verify-by-expectation-id genuinely raced. The three non-streaming forward paths now log
  before writing, matching the mocked path. The streaming path is a deliberate exception: its log entry carries
  the captured body and total stream duration, neither of which exists until the stream completes, so
  verifications against streamed exchanges should await stream completion.
- **Docs: log entries per request were understated by an order of magnitude, causing `maxLogEntries` to be
  under-sized.** `docs/code/memory-management.md` claimed a flat 2-3 entries per request. The matching scan also
  emits one `EXPECTATION_NOT_MATCHED` entry at `INFO` (the default level) for every expectation evaluated before
  a match, so the real cost is up to ~N+2 with N expectations loaded (~N+3 on a no-match with a closest-match
  diagnostic). The doc now gives the per-case breakdown and explains why under-sizing is a correctness concern,
  not just a memory one.
- **The OpenAPI specification served at `GET /mockserver/openapi.yaml` described 14 of MockServer's 68
  control-plane paths; it now describes all of them.** The specification exists in the repository twice —
  the copy published on the website and the copy bundled into the jar — and nothing compared the two, so
  they drifted apart in both directions. The served copy was missing 54 paths, including `/chaosExperiment`,
  `/loadScenario*`, `/breakpoint/*`, `/drift`, `/verifySLO`, `/files/*`, `/cassettes`, `/wasm/modules`,
  `/grpc/*`, `/crud`, `/mode`, `/preemption` and `/scenario/*`, so anything generated from it — client
  bindings, API explorers, request collections — silently omitted those endpoints. The website copy was
  in turn missing model properties the served copy had. Because every schema is declared
  `additionalProperties: false`, the gap was not merely cosmetic: the specification actively **rejected**
  valid MockServer payloads. Validating five realistic expectations against the published website copy
  failed all five (SSE, WebSocket, `namespace`/`scenarioName`, `chaos`, `statusCodeRange`); the bundled
  copy rejected two of the five. The two copies are now one reconciled specification, stored in both
  places and held identical by a test, so neither can drift again. The reconciliation is a pure union —
  no path, schema, operation, property, enum value or union branch was dropped from either side. The
  `/retrieve` query parameters `correlationId`, `namespace`, `fanInLocalOnly` and `forwardUnmatchedTo`,
  the `format=recording` import mode with its `source` and redaction parameters, and the format-by-type
  applicability constraints are all now documented.
- **BEHAVIOUR: the OpenAPI specification no longer documents `connectionDrop` on `HttpChaosProfile`.** That
  property does not exist in MockServer and never did; the real property is `dropConnectionProbability`
  (a probability between 0.0 and 1.0). The schema was also missing 18 further chaos properties that do
  exist, including `retryAfter`, `succeedFirst`, `failRequestCount`, `outageAfterMillis`, `malformedBody`,
  `slowResponseChunkSize` and the `quota*` family. Anything generated from the specification against
  `connectionDrop` was generating a field MockServer would ignore.
- **BEHAVIOUR: the Node client no longer silently converts a FAILED verification into a passing one.**
  Every `verify*` method (`verify`, `verifyById`, `verifySequence`, `verifySequenceById`, `verifyResponse`,
  `verifyRequestAndResponse`, `verifySequenceWithResponses`, `verifyZeroInteractions`) and `verifySLO`
  returns a thenable. When a verification failed, its rejection handler notified the caller's `error`
  callback if one had been supplied — but simply returned when one had not, which **fulfilled** the promise
  it returned. So a failure consumed with a single-argument `.then(onSuccess)` was swallowed: the success
  handler never ran, nothing threw, and the promise resolved as though the verification had passed. Code of
  the shape `return client.verify(...).then(() => { ... })` therefore passed unconditionally — a test suite
  could be green while proving nothing. Under Node a failed verification now **rejects** when no `error`
  callback is supplied, so the failure reaches `.then()` consumers and any framework that surfaces returned
  promises. In the browser build the transport is a hand-rolled thenable rather than a real promise, so the
  same failure surfaces as an **uncaught error** from the XHR handler instead of a catchable rejection —
  loud either way, but not `.catch()`-able there.
  **`await client.verify(...)` was never affected** — `await` supplies its own reject handler, which is why
  the existing tests did not catch this — and the two-argument `.then(success, error)` callback style is
  unchanged, so suites using either style behave exactly as before.
  **Users consuming a verification with a single-argument `.then(...)` may see tests that pass today start
  to fail; those failures are real and were previously being hidden.** Note that a fire-and-forget
  `client.verify(...).then(fn)` whose promise is discarded now produces an **unhandled rejection**, which on
  Node 15 and later terminates the process rather than merely failing a test. That is the intended
  consequence of a failed verification no longer being silent, but it is abrupt: either `await` the call,
  return its promise to your test framework, or pass an `error` callback.
  A related inverted guard in the same handlers (`if (error) { sucess(result) }`, which tested the error
  callback but invoked the success callback) was removed; it was unreachable, because the transport always
  rejects with a plain string rather than an object carrying a status code.
- **`verifySLO` now reports a PASS/INCONCLUSIVE verdict that arrives via the error path.** It previously
  returned early whenever no `error` callback had been supplied, and did so *before* parsing the response —
  so a PASS or INCONCLUSIVE verdict delivered through the rejection path (which the defensive branch below
  it existed to handle) never reached the success callback at all. The verdict is now parsed first and
  dispatched correctly, and `verifySLO` also returns its underlying promise so the result can be chained.
- **gRPC unary mock responses are now spec-compliant, so real gRPC clients can consume them (#2419).** Two
  defects meant a documented unary expectation was rejected by any real gRPC client over HTTP/1.1 and HTTP/2.
  (1) The JSON-to-protobuf conversion only ran when the *response* carried `x-grpc-service`/`x-grpc-method`,
  but those are set on the request only and are never propagated to the matched response — so a normal mock
  returned raw JSON on a stream the client expected to be framed protobuf. MockServer now remembers the
  service/method resolved from the request and converts automatically; explicit response headers still take
  precedence. (2) `grpc-status`/`grpc-message` were sent as response headers rather than the terminal trailer
  the gRPC wire format requires. They are now trailers on every path — unary mock responses, health check,
  reflection, and chaos fault injection (including `customTrailers`) — with `content-type: application/grpc`
  remaining a real header. gRPC-Web is unchanged for users: the status still travels in the in-body trailer
  frame. A non-OK `grpc-status` written as a plain numeric header on the response is now honoured instead of
  being silently replaced with `OK`, and is emitted verbatim on both the mock and forward-proxy paths — a
  non-standard or future status code such as `42` is no longer collapsed to `2` (`UNKNOWN`). Concurrent unary calls on a single gRPC channel are each
  converted against their own method's output type, so overlapping requests on one HTTP/2 connection no
  longer return raw JSON (or a response decoded as the wrong message type). Verified end-to-end with a real
  grpc-java client, including concurrent calls to two different methods.
- **BEHAVIOUR: a gRPC response with a non-2xx HTTP status and no gRPC status of its own is now returned as a
  gRPC error instead of `OK`.** The status is mapped per the gRPC-over-HTTP/2 specification (404 →
  `UNIMPLEMENTED`, 401 → `UNAUTHENTICATED`, 403 → `PERMISSION_DENIED`, 400 → `INTERNAL`,
  429/502/503/504 → `UNAVAILABLE`, anything else → `UNKNOWN`), the HTTP status becomes 200 so the client
  reads the trailers, and the body is dropped. This applies wherever a gRPC response is framed — an unmatched
  request (the common case: previously the absent status resolved to `OK` and a synthesized example body was
  invented, so a typo'd path returned a plausible success), a matched expectation that deliberately returns a
  non-2xx status without a gRPC status, and a non-2xx response from upstream on the gRPC forward-proxy path.
  Set `grpc-status` or `grpc-status-name` explicitly to keep full control — an explicit gRPC status always
  takes precedence over the HTTP status. Applies on HTTP/1.1, HTTP/2 and HTTP/3.
- **`grpc-message` is now percent-encoded per the gRPC wire specification, on every transport.** MockServer
  previously wrote the raw string, which corrupts ordinary input because clients percent-*decode* on
  receipt: a message echoing `%41` arrived as `A`, non-ASCII (`paiement refusé`) arrived as mojibake over
  HTTP/1.1 and HTTP/2 and as literal `?` characters in the gRPC-Web trailer frame, and multi-line messages
  broke the trailer block. Encoding is applied at all five emission sites (unary, HTTP/3, bidi streaming,
  server streaming, gRPC-Web) from one shared helper, and messages received from a real upstream server are
  percent-*decoded* into the response model on the gRPC forward-proxy path.
- **SECURITY: fixed CRLF injection into the gRPC-Web trailer frame via `grpc-message`.** The frame is a
  CRLF-delimited block and only `customTrailers` were checked, so a message containing
  `"denied\r\ngrpc-status: 0"` could inject a second `grpc-status` line and — depending on the client's
  first-wins/last-wins parse — turn an error into a success. Percent-encoding escapes CR/LF, and trailer
  names/values are CRLF-stripped as a second layer.
- A gRPC forward-proxy response that cannot be decoded is now logged at WARN with the service and method
  instead of falling back silently. The fallback itself is unchanged — the upstream response is still
  forwarded untouched — but a genuine decode failure previously left no trace at all, so it was
  indistinguishable from the upstream simply returning protobuf.
- gRPC requests whose message exceeds the maximum size now return `grpc-status: 8 RESOURCE_EXHAUSTED`
  instead of `13 INTERNAL`, matching the specification and grpc-java/grpc-go. The limit is now configurable
  via **`maxGrpcMessageSize`** (default 4 MiB, unchanged) instead of being hard-coded, and the constant is
  no longer duplicated between the two frame decoders.
- An unsupported `grpc-encoding` (for example `deflate` or `snappy`) now returns
  `grpc-status: 12 UNIMPLEMENTED` with a `grpc-accept-encoding: identity, gzip` response header telling the
  client what to retry with, instead of failing inside gzip and surfacing as an opaque `INTERNAL`. The
  request's `grpc-encoding` is now actually consulted rather than assuming any compressed frame is gzip,
  and `grpc-accept-encoding` is advertised on gRPC responses.
- **`grpc-timeout` is now honoured.** A client deadline (for example grpc-java's `withDeadlineAfter`) that
  elapses before the response is written returns `grpc-status: 4 DEADLINE_EXCEEDED`, and the late response
  is dropped rather than written onto a stream that already carries terminal trailers. The header is still
  passed through as an ordinary request header so it remains matchable. **This is a behaviour change:** an
  expectation whose `Delay` exceeds the client's deadline now returns DEADLINE_EXCEEDED from the server
  instead of the client timing out locally while MockServer kept writing to an abandoned stream. Timers are
  cancelled when the exchange is answered, when its record is evicted, when the connection goes inactive,
  and when an HTTP/3 QUIC stream closes. Enforcement covers **streaming RPCs mid-stream** as well as unary: a server-streaming,
  client-streaming or bidi RPC whose messages outlast the deadline is terminated with a DEADLINE_EXCEEDED
  trailer and stops emitting, rather than continuing to write messages to a stream the client has abandoned.
  Normal completion and deadline termination are mutually exclusive (compare-and-set on every terminal
  path), so exactly one terminal trailer is ever written and no message follows it.
- Fixed **server-streaming gRPC delivering nothing at all to a real gRPC client over HTTP/2**. The
  streaming handler writes Netty objects directly, bypassing the response mapper, so nothing stamped the
  HTTP/2 stream id — the initial HEADERS, every DATA frame and the trailers were written to a fresh
  server-initiated stream the client was not reading, and the call hung until its deadline. Measured
  before the fix: 0 of 2 messages received; after: 2 of 2. HTTP/1.1 was unaffected. This is the same
  defect class as the direct-response stream-id fix above, in the streaming path.
- gRPC responses without a body are now emitted as a **Trailers-Only** response on HTTP/2 (a single
  end-of-stream HEADERS frame), matching what HTTP/3 already did and what strict conformance suites expect.
  HTTP/1.1 continues to use real trailers, where Trailers-Only has no meaning.
- Fixed a gRPC response being returned as unframed JSON with no `grpc-status` when the proto descriptor was
  removed or reloaded between decoding the request and encoding the response — the same shape as #2419. It
  now returns `UNIMPLEMENTED` with an empty body.
- gRPC-Web responses now echo the negotiated `+proto` subtype instead of always returning the bare
  `application/grpc-web` content-type.
- Fixed gRPC direct responses (health check, server reflection, chaos faults, and request-decode errors)
  being written on a **fresh HTTP/2 stream** instead of the one the request arrived on. These are written
  directly by the gRPC request handler rather than through the matching engine, so nothing stamped the
  stream id; Netty then allocated a new server-initiated stream and the client's call hung until its
  deadline. Affected gRPC and gRPC-Web over HTTP/2; HTTP/1.1 was unaffected.
- Fixed three HTTP/3 gRPC gaps that made the same expectation behave differently there than on HTTP/1.1
  and HTTP/2: `grpc-status` is now read from response **trailers** as well as headers (the documented way
  to author it — previously a trailer-authored non-OK status arrived as `OK` over HTTP/3); an unmatched
  request no longer fabricates a successful response with the 404 body as its payload; and a unary
  response now carries the expectation's own headers instead of silently dropping them. Status resolution
  is now shared by all three transports (`GrpcResponseStatusResolver`) rather than reimplemented per
  transport, which is how these diverged.
- gRPC chaos `customTrailers` are now delivered to gRPC-Web clients. They are folded into the in-body
  trailer frame along with `grpc-status`/`grpc-message`, and no trailers are left as real HTTP trailers —
  browsers cannot read HTTP trailers, so a custom trailer left there was unreachable.
- MockServer now advertises `SETTINGS_MAX_CONCURRENT_STREAMS` (100) explicitly on every HTTP/2 connection
  instead of relying on the Netty default, which differs between Netty 4.1 (unset, meaning unlimited) and
  4.2 (100). The advertised value is unchanged from what Netty 4.2 already supplied, so there is no
  behaviour change; it is now MockServer's own limit and cannot shift under a Netty upgrade.
- **Fixed `MockServerClient.hasStopped()` reporting a running MockServer as stopped.** The status probe ignored
  errors, which collapsed both a connection refusal (genuinely stopped) and a read timeout (alive but slow to
  answer) into the same empty result, and treated that result as "stopped". A MockServer that was merely paused
  by GC or starved of CPU therefore reported itself stopped while it was still bound, so callers rebound the port
  and got a `BindException` raised far from the real cause. A refused connection still reports stopped; a timeout
  now reports **not** stopped and logs a warning. **Behaviour change:** `hasStopped()` returns `false` in cases
  where it previously returned `true`, and it no longer reports success for any failure it cannot interpret. Code
  that treated a `true` result as proof the port was free was relying on the defect and may now wait longer, which
  is the intended behaviour. Both the blocking `stop()` and the background wait behind `stopAsync()` now derive
  how long they wait for a confirmed stop from `stopDrainMillis` and `maxSocketTimeoutInMillis` instead of using
  a fixed 10 seconds, so raising the drain timeout no longer causes either to give up while the server is still
  draining and still holding its port. Note that `stopDrainMillis` is read from the client's JVM, so against a
  remote MockServer configured with a longer drain the client can still stop waiting early — it now logs a
  warning when it does, rather than reporting a stop that did not happen.
- **Stop failures are no longer silent.** `MockServerClient.stop()` and `LifeCycle.stop()` logged a failure to
  stop at DEBUG, so the first visible symptom was an unrelated `BindException` much later. Both now log at WARN,
  as does giving up while waiting for a stop to be confirmed. **Users may see new WARN messages** where a stop was
  previously failing silently.
- **`PUT /mockserver/configuration` no longer silently ignores capacity properties.** A set of capacity properties
  was accepted and echoed back in the response but resized nothing, because the value had been consumed once at
  construction. `maxLogEntries`, `maxEventLogSizeInBytes`, `maxExpectations` and `controlPlaneAuditMaxEntries` are
  now applied to the running server — the event-log deque, the expectation store and the control-plane audit ring
  are resized in place, and a shrink evicts the excess immediately rather than waiting for further traffic.
  `ringBufferSize` and `maxWebSocketExpectations` genuinely cannot be resized on a running server (an LMAX
  Disruptor ring is a fixed array allocated at startup; the local callback registries are built once), so a PUT
  that explicitly supplies a differing value now logs a WARN naming the ignored value and the value in force, and
  resets the field to the in-force value so the response and any later `GET /mockserver/configuration` report the
  truth. The request still succeeds, so a client that PUTs a whole configuration blob with unchanged values is
  unaffected. **If you set any of these over the config API and adapted to them being ignored, they now take
  effect** — in particular a reduced `maxLogEntries` or `maxExpectations` will now drop existing entries.
- **`ringBufferSize` no longer doubles on every configuration round trip.** The power-of-two rounding returned the
  next power STRICTLY greater than its input, so reading the resolved value and writing it back (as a
  `GET /mockserver/configuration` followed by a `PUT` of the same blob does) grew it each time — 1024 became 2048,
  then 4096. An exact power of two is now returned unchanged, as the documented "rounded up to the next power of
  two" behaviour implies. **A configuration that explicitly sets `ringBufferSize` to an exact power of two now
  allocates that many slots rather than twice as many.**
- **SECURITY: `redactSecretsInLog` now actually redacts.** Enabling redaction on a `Configuration` instance or via
  `PUT /mockserver/configuration` left `Authorization`, `Proxy-Authorization`, `Cookie`, `Set-Cookie`, `x-api-key`
  and `api-key` headers in clear in the event log, the dashboard, the retrieval/export surface and the persisted
  recorded-requests archive, because the redactor was built from the static store only. The redaction accessors now
  take the effective `Configuration`, wired through `MockServerEventLog`, `RecordedRequestsFileSystemPersistence`,
  the dashboard (`DashboardLogEntryDTO`) and the JSON log-message serializer.
  **Anyone who enabled this flag through the instance or REST API and assumed secrets were masked was not protected;
  they are now.** Setting it via system property, environment variable or property file was already effective and is
  unchanged. **Note redaction is not retroactive:** the rendered view of a log entry is memoised on first read, so
  entries already rendered before redaction was enabled keep their unredacted form. If secrets have already been
  captured, enable redaction *and* clear the event log rather than relying on redaction alone. The companion
  `fixtureBodyRedactFields` (which JSON body fields to mask) is resolved the same way — the instance value first,
  the static store as the fallback — so the set of masked body fields is settable over the config API too.
- **`llmCostBudgetUsd`, `rateLimitMaxNamedQuotas` and `maxLlmConversationBodySize` now take effect when set on a
  `Configuration` instance or via the config API.** All three were enforced against the static store only, so the
  LLM cost circuit-breaker never tripped, the named-quota memory cap was never applied, and the LLM conversation
  body-size cap was never enforced at the configured value. Each enforcement site now prefers the instance value.
- **`connectionLifecycleChaosEnabled` and `connectionLifecycleAutoHaltCountsRst` now take effect when set on a
  `Configuration` instance or via the config API.** `NettyResponseWriter` already held the effective configuration
  but read the static store for both flags.
- **28 properties that existed only as system properties are now settable on a `Configuration` instance and via
  `PUT /mockserver/configuration`.** The LLM backend settings (`llmProvider`, `llmModel`, `llmBaseUrl`,
  `llmBackendsConfig`, `llmRequestTimeoutMillis`, `llmSemanticMatchingEnabled`, `llmVcrStrict`,
  `llmInferUsageEnabled`, `llmOptimisationMaxCalls`), the OpenTelemetry settings (`otelEndpoint`, `otelTracesEnabled`,
  `otelMetricsEnabled`, `otelMetricsExportIntervalSeconds`, `otelMetricsTemporality`), the Prometheus remote-write
  settings (`prometheusRemoteWriteEnabled`, `…Url`, `…BasicAuthUsername`, `…Headers`, `…IntervalSeconds`,
  `…ProtocolVersion`), plus `stopDrainMillis`, `regexMatchingTimeoutMillis`, `xpathMatchingTimeoutMillis`,
  `customJsonUnitMatchersClass` and `fixtureBodyRedactFields`, had no `Configuration` accessor and no `ConfigurationDTO`
  field at all, so they could only ever be set at startup. They now round-trip through the config API like every other
  property. Setting them by system property, environment variable or properties file is unchanged.
- **The three name-obvious credential properties are write-only on the configuration API.** `llmApiKey`,
  `prometheusRemoteWriteBasicAuthPassword` and `prometheusRemoteWriteBearerToken` can be set via a `Configuration`
  instance or `PUT /mockserver/configuration`, but `GET /mockserver/configuration` returns `***REDACTED***` in their
  place, including a value originally supplied by system property or environment variable. Applying a previously
  retrieved (masked) configuration back is safe: a masked value is ignored rather than written, so a `GET`-then-`PUT`
  round trip cannot silently overwrite one of these credentials with the placeholder.
- **Credentials embedded *inside* `prometheusRemoteWriteHeaders` and `llmBackendsConfig` are now redacted per header /
  per field on every surface that discloses a configuration value** — `GET /mockserver/configuration`,
  `GET /mockserver/config` (the effective-configuration diagnostic), `--print-config`, and the startup property-file
  log dump, which all now share one redaction rule rather than one per endpoint. The write-only masking above is keyed on the whole property name, so
  it could not reach a secret nested in a structured value; these two properties are now masked field-by-field instead.
  For `prometheusRemoteWriteHeaders` the value of each credential-bearing header (`Authorization`, `Api-Key`,
  `X-Auth-Token`, …) becomes `***REDACTED***` while every other header — and the ordering and spacing of the list — is
  returned exactly as configured, so `Api-Key=secret,X-Scope-OrgID=tenant-a` reads back as
  `Api-Key=***REDACTED***,X-Scope-OrgID=tenant-a`. `llmBackendsConfig` is documented as a *path* to a backends JSON
  file — the `apiKey`s live in that file, which the configuration API never returns — so a path is returned unchanged;
  a value that is itself a JSON document has each `apiKey`-shaped field redacted as defence-in-depth, and an
  unparseable document is redacted whole rather than disclosed. Applying a previously retrieved (masked) configuration
  back is safe in the same way whole-value credentials are, and safe when only *part* of the value was masked: each
  masked header/field is restored from the value the server already holds (backends are matched by `name`, so
  reordering cannot transplant one backend's key onto another) while the edits around it are applied normally. A
  masked header or field with no held value is dropped rather than written, so `***REDACTED***` can never become a
  credential. **Remaining limitation — redaction is keyed on the header/field *name*, so a credential carried inside
  an otherwise ordinary value (classically a URL with an inline `user:pass@`) is still returned in clear.** If you
  configure a secret in that shape, protect the control plane (`controlPlane*Authentication*`) or do not rely on these
  endpoints keeping it private.
- **SLO tracking, chaos auto-halt and preemption-simulation properties now take effect when set on a
  `Configuration` instance or via the config API.** `sloTrackingEnabled`, `sloWindowMaxSamples` and
  `sloWindowRetentionMillis` (SLO sample store), `chaosAutoHaltEnabled`, `chaosAutoHaltErrorThreshold` and
  `chaosAutoHaltWindowMillis` (chaos auto-halt circuit-breaker), and `preemptionSimulationMaxDrainMillis` were all
  enforced against the static store only. In particular the auto-halt eviction window read the static value in two
  places, so even a partially-wired fix would have left eviction ignoring the configured window.
- Fixed FILE-type response bodies being silently dropped during serialization. The `HttpResponseSerializer` and
  `HttpResponseDTOSerializer` whitelist body types when writing the `body` field and had no branch for `FileBody`,
  so a response configured with a file body was serialized without it (issue #2430). Both serializers now preserve
  the file body, including its `filePath`, `contentType`, and `templateType`.
- Fixed a broken build caused by a Maven dependency convergence error: `netty-tcnative-boringssl-static` was
  pinned to `2.0.77.Final` while the Netty `4.2.16.Final` upgrade pulled its native classifier artifacts in
  transitively at `2.0.78.Final`. Aligned the pinned version (and the matching `NETTY_TCNATIVE` Docker build
  args) to `2.0.78.Final`.

## [7.4.0] - 2026-07-04

### Added
- **Dashboard: every language tab now generates typed client code.** The composer's Java, Node.js, Python, Go,
  C#, Ruby, and Rust tabs construct each client's typed model — fluent builders and typed constructors matching
  the website examples — instead of embedding raw JSON, including full LLM response actions in Java. Every
  language's generated output is proven equivalent by executing or compiling it against the real client and
  comparing the serialized expectation with the registered JSON, and a CI gate compiles the generated Java
  against the built client on every build.
- **Every client library now round-trips the full expectation model, proven by a shared fidelity harness.** The
  Go, Rust, C#, Python, Ruby, and Node clients gained typed support for every expectation feature they previously
  dropped silently — chaos profiles, rate limits, forward-with-fallback and forward-validate actions, gRPC bidi
  responses, before/after actions, steps, capture rules, namespaces, LLM response payloads including moderation,
  rerank, and content filters, WebSocket frame matchers, response trailers, DNS matchers, and all body matcher
  variants. Forty-four server-validated kitchen-sink fixtures now run as round-trip tests inside each client's own
  test suite, with a ratcheting known-gaps ledger that fails CI if a documented gap silently regresses or a fixed
  gap is still excused.
- **Dashboard: JWT and all-of body matchers are now authorable in the composer.** The Advanced request form gains a
  JWT section (claims, issuer, audience, algorithm) and an all-of body matcher composing multiple sub-matchers,
  emitted in the exact server wire format and round-tripping on edit.
- **Dashboard: the Java code tab is now complete and type-safe.** Generated Java uses the real client API for
  priority, times, and time-to-live, scenario bindings, namespaces, and capture rules — proven by compiling a
  kitchen-sink snippet against the built client — and the Java client gains matching withNamespace and withCapture
  fluent methods. Actions the Java builder preview cannot represent show an honest notice instead of fabricated code.

- **Dashboard: every captured flow is now a launchpad.** A "Create From This" menu on traffic detail panes and
  log rows fans out into every subsystem pre-filled from that flow — create a mock in the composer, set a
  breakpoint, prefill a verification, or add chaos for the host. The traffic inspector also gains structured
  Request/Response tabs (headers tables and pretty-printed bodies, with the raw JSON tree kept as a tab),
  "Copy as curl" with shell-safe quoting and masked credentials, a Charles-style "Repeat" action (iterations,
  bounded concurrency, delay, live progress and cancel), a Proxyman-style diff pool with an editable ignored-headers
  list, an unmatched-count chip with why-didn't-this-match and generate-stub actions, and bulk
  "Promote to Mocks" over recorded traffic (`PUT /mockserver/recordings/promote`).

- **Dashboard: previously server-only capabilities are now reachable from the UI.** Validate recorded traffic
  against an OpenAPI spec (`/trafficValidate`), import GraphQL SDL schemas and mock SCIM providers, import Pact
  contracts, generate HTTP expectations from AsyncAPI specs, dry-run WASM modules against a sample request,
  reload persisted recording archives (NDJSON or from server disk), a preemption-simulation card and experiment
  history in Service Chaos, a control-plane Audit view, a standalone Scenarios view in the navigation, and a
  read-only Server Info tab (effective configuration with source tiers, bound ports plus bind-additional-port,
  proxy setup with CA download) alongside a server-side decoded-prompt LLM run diff.

- **Dashboard: quick chaos and honest latency attribution.** A one-toggle Quick Chaos strip (percentage slider
  over real per-request fault probabilities, per upstream host) makes fault injection approachable, and the
  traffic timing waterfall now distinguishes latency MockServer injected (chaos latency, configured response
  delays including the global delay, breakpoint holds) from real upstream/processing time — mock-served
  responses carry a timing block for the first time when they inject latency (a plain mock's recorded
  output is unchanged).

- **Force a response-sequence variant per request.** A request matching an expectation with multiple responses
  can force which variant it receives via a 0-based `x-mockserver-response-index` header. Forced requests
  consume `times` but never advance the sequential/switch rotation for other callers; the header is retained
  in recordings and filtered from every outbound forward, including WebSocket passthrough. Invalid values are
  ignored.

- **Dashboard: faster first load, first-run onboarding, and one-click navigation.** All views load lazily
  (initial bundle down from 259 kB to about 164 kB gzip), a Try It Now path on the get-started view creates a
  first mock, returning users get an open-dashboard shortcut, keyboard shortcuts move off
  browser-conflicting bindings with a ? help overlay, and matcher testing is available at the point of need
  in the composer and on every expectation row. The scenarios view details each scenario's states, bound
  mocks, and transitions with edit-in-composer actions, and the advanced composer models scenario bindings
  directly. The audit view explains the opt-in audit trail (and the demo enables it).

### Fixed
- **Dashboard: editing an LLM or otherwise exotic expectation now generates complete typed client code in
  every language.** Fields the composer form cannot model — LLM responses with their full completion detail,
  response sequences, object callbacks, forward-validate, gRPC bidi, rate limits, and cross-protocol
  scenarios — were silently dropped from generated Python and Ruby code and embedded as raw JSON in C# and
  Rust; all languages now construct the client's typed model, guarded by a universal test asserting no wire
  field is ever absent from generated code.
- **Registering forward-validate, forward-with-fallback, or gRPC bidi expectations no longer fails with a
  metrics error.** The per-action counter constants for these action types were missing, and a guard test
  now asserts every action type has one.
- **Generated Go code now compiles.** All Go code generators — the dashboard expectation, verification, and
  load-scenario tabs, the retrieve-as-Go endpoint, and the website examples — emitted the client import without its
  /v7 semantic-import-versioning suffix, so generated code never resolved against the published module.
- **.NET client: reading an object-callback expectation back from the server no longer throws.** The
  responseCallback field was typed as a string while the server emits a boolean, losing the whole expectation.
- **Python client: multipart body field matchers no longer vanish on registration.** Multipart fields were emitted
  in an array form the server misroutes; they now use the server's canonical object form.
- **Dashboard: editing a mock no longer shows phantom changes.** An untouched edit round-trips to a zero diff — the
  explicit default forms of priority, times, and timeToLive are preserved instead of appearing as removals, and a
  genuine reset shows the explicit unlimited form.
- **Dashboard: the Promote to Mocks button no longer wraps in a narrow traffic pane.**

- **Dashboard: editing an expectation no longer silently strips fields the form does not model.** The composer
  now merges its form output onto the original expectation JSON, preserving scenario bindings, namespaces,
  response sequences, cross-protocol scenarios, and matcher fields such as `keepAlive` and `socketAddress` in
  both quick and advanced modes, with an alert listing the preserved fields.

- **Dashboard reliability fixes.** Paused breakpoint exchanges survive navigating away from the Breakpoints
  view; stream-frame editing is UTF-8 safe and surfaces encode failures; a reconnect re-sends the active
  request filter instead of silently streaming unfiltered data; an error-only push no longer blanks the
  panels; user-action errors persist until dismissed (and the banner is dismissible); mismatch dialogs report
  honest "differs on N field(s)" scores with remediation hints; and a response template rendering invalid
  output is now surfaced as a `TEMPLATE_GENERATION_FAILED` event instead of a silent 404 logged as a success.

- **Dashboard performance, measured.** Idle pushes no longer re-render every panel each second, hidden tabs
  buffer instead of processing WebSocket updates, and hot parse/search paths are cached — all verified by a
  committed benchmark suite (`npm run bench` in `mockserver-ui`) comparing against the pre-optimization
  implementations at small and large payload scales.

- **Automated release publishing for the Ruby and PHP Testcontainers modules.** The `mockserver-testcontainers/ruby`
  and `mockserver-testcontainers/php` modules now publish automatically as two new `soft_fail` release-pipeline
  components. `tc-ruby` (`scripts/release/components/tc-ruby.sh`) self-bumps the gem `version.rb`, builds inside the
  pinned Ruby image and `gem push`es `testcontainers-mockserver` to RubyGems (credential from the existing
  `mockserver-build/rubygems` secret; skips gracefully if absent). `tc-php` (`scripts/release/components/tc-php.sh`)
  subtree-splits the module and pushes `master` + a version tag to the `mock-server/mockserver-testcontainers-php`
  Packagist mirror repo, mirroring the PHP-client publish; it skips gracefully until that mirror repo is provisioned
  (one-time setup — see `mockserver-testcontainers/php/PUBLISHING.md`). Both support `--dry-run`, are release-type
  gated (`full`/`post-maven`), and have soft post-release verification checks. See
  `docs/operations/release-process.md`.

- **Go client: `jwt`/`allOf` matchers, a `/v7` module path, and a bundled Testcontainers client.** The Go
  client (`mockserver-client-go`) gains typed `jwt` request-matcher and `allOf` body-matcher builders (matching
  the other client libraries). Its module path now carries the required Semantic Import Versioning suffix —
  `github.com/mock-server/mockserver-monorepo/mockserver-client-go/v7` — so it is properly `go get`-able as a
  dependency (a future v8 becomes `/v8`); update imports to add `/v7`. With that fixed, the Go Testcontainers
  module now bundles the client and exposes `container.Client(ctx)` returning a ready-to-use client pointed at
  the container's mapped host and port, instead of only documenting manual construction.

- **Typed client-library support for the `jwt` request matcher and `allOf` body matcher.** The two new
  matchers are now first-class in the client libraries as well as the server: the OpenAPI spec, the generated
  Node/TypeScript types (`jwt`, `Jwt`, and the `ALL_OF` / `bodyAllOf` body variant), and the Java model
  (`request().withJwt(jwt()...)`, `withBody(allOf(...))`) all expose them, and every other client library can
  send them over the REST wire format. Consumer docs gain Java and Node examples for both matchers.

- **Automated package-manager release channels for the CLI (Homebrew, Scoop, winget, Chocolatey, SDKMAN!, asdf/mise).**
  Six new release-pipeline components (`scripts/release/components/{homebrew,scoop,winget,chocolatey,sdkman,asdf}.sh`)
  publish/update these channels automatically as a `soft_fail` group after the binary-bundle step, distributing the same
  self-contained jlink bundles (`mockserver-<version>-<os>-<arch>.tar.gz`/`.zip`, each carrying its own trimmed Java
  runtime) that the `binary` component uploads to the GitHub Release. A new tap formula (`mock-server/homebrew-tap`,
  `brew install mock-server/tap/mockserver`) is added — complementary to, and separate from, the JAR-based homebrew-core
  formula (still bumped by BrewTestBot). Each channel renders its manifest from the real published bundle checksums,
  supports `--dry-run`, and **skips itself gracefully** (never breaking the release) when its target repo or API secret is
  not yet configured. See `packaging/<channel>/release-component.md` and `docs/operations/release-process.md`.

- **WebSocket proxy passthrough + frame recording.** MockServer can now proxy WebSocket connections through to a real
  upstream server, not just mock them. When a WebSocket upgrade request (`GET` + `Upgrade: websocket`) arrives in proxy
  mode and no WebSocket mock expectation matches — or it matches a plain `FORWARD` expectation — MockServer completes
  the upstream connection (honouring `ws`/`wss` scheme and TLS), relays the `101 Switching Protocols` handshake, and
  relays frames bidirectionally (text, binary, ping, pong, close) until either side closes. This lets a system under
  test pointed at MockServer as a proxy reach a real WebSocket backend. The relayed traffic is recorded: the upgrade
  exchange is logged as a `FORWARDED_REQUEST` (request = the upgrade, response = `101` carrying a JSON transcript of the
  relayed frames with direction/opcode/payload), so `retrieveRecordedRequests` and the dashboard show the WebSocket
  traffic. The transcript is flushed to the log once, on connection close. Frame recording is bounded per connection by
  the new **`webSocketProxyMaxRecordedFrames`** configuration property (default `1000`; set to `0` to disable frame
  recording — the handshake is still recorded) plus an absolute 8MB transcript cap, following the `maxLogEntries`
  memory-management philosophy. The upstream TLS leg uses the forward-proxy trust manager
  (`forwardProxyTLSX509CertificatesTrustManagerType`, default `ANY`) so real `wss` backends are reachable, and the same
  `forwardProxyBlockPrivateNetworks` SSRF guard the matched-forward path enforces applies (a WS upgrade to a
  loopback / RFC1918 / cloud-metadata target is refused with `502`). A slow peer cannot exhaust memory — the relay
  applies standard writability-based backpressure. An opt-in **`webSocketProxyIdleTimeoutSeconds`** (default `0`, off)
  reaps abandoned relays. For a WS upgrade matched by a plain `FORWARD` expectation, `Times`/`verify` apply but
  response-shaping features (`delay`, `rateLimit`, `chaos`, breakpoints, drift) do not — use the `WEBSOCKET_RESPONSE`
  mock action for frame-level control. Scope (v1): HTTP/1.1 upgrade relay (plain and TLS upstream); HTTP/2
  extended-CONNECT WebSocket is not yet relayed. See
  [docs/code/netty-pipeline.md](docs/code/netty-pipeline.md#websocket-proxy-passthrough).

- **Seedable template `faker` for reproducible fixtures — new `templateFakerSeed` property.** The template
  `faker` sample-data helper (Velocity `$faker`, Mustache `{{faker.*}}`, JavaScript `faker`) can now be seeded
  deterministically. `templateFakerSeed` defaults to `0`, which leaves faker unseeded so it produces different,
  random values on every render (behaviour unchanged); a non-zero value seeds a per-engine `Faker` so
  faker-driven templates generate reproducible fixtures across runs — the template analogue of the OpenAPI
  example generator's fixed-seed model. Determinism is strongest for sequential (single-threaded) generation.
  Wired through the config trio (`ConfigurationProperties` / `Configuration` / `ConfigurationDTO`) and honoured
  by all three template engines. See [docs/code/configuration-reference.md](docs/code/configuration-reference.md).

- **Per-host upstream mTLS — new `forwardProxyClientCertificatesByHost` property.** Outbound client
  authentication (mTLS to the upstream) was global-only; it can now vary by upstream host. The property is a
  comma-separated list of `host=certificateChainPath;privateKeyPath` entries — a matching upstream host
  (case-insensitive) is sent that host's cert/key pair for client authentication, and any host without an entry
  falls back to the global `forwardProxyPrivateKey` / `forwardProxyCertificateChain` pair (default empty, so
  behaviour is unchanged). Contexts are cached per mapped host while all unmapped hosts share one context, so a
  forward proxy that sees many upstreams cannot grow the cache without bound. See
  [docs/code/tls-and-security.md](docs/code/tls-and-security.md#per-host-outbound-mtls).

- **Testcontainers modules for Ruby and PHP, plus a wired client on every polyglot module.** MockServer now
  ships official Testcontainers modules for **eight** languages — Ruby (`testcontainers-mockserver`, RubyGems)
  and PHP (`mock-server/mockserver-testcontainers`, Packagist) join the existing Java, .NET, Node.js, Python,
  Rust and Go modules. Both new modules start the `mockserver/mockserver` image, wait for
  `PUT /mockserver/status` → 200, and expose connection helpers (`endpoint` / `getEndpoint`, `secure_endpoint`,
  `server_port`, `host`).
  - **A wired client on every module.** Each polyglot container now returns a ready-wired MockServer client
    pointed at the mapped host/port, mirroring the Java module's `getClient()`: `get_client` (Python, Ruby),
    `getClient()` (Node, .NET), `getMockServerClient()` (PHP — the inherited Testcontainers `getClient()` is
    reserved for the Docker client), and `client()` / `async_client()` (Rust). The language's MockServer client
    is now a dependency of its Testcontainers module. (Go instead documents constructing the client from
    `ctr.Host(ctx)` / `ctr.ServerPort(ctx)` via `mockserver.New(host, port)`: `mockserver-client-go` is
    published at v7.x without a `/v7` module-path suffix, so bundling it would make the Go Testcontainers module
    itself unresolvable for downstream `go get`.)
  - **Version-matched default image, no more hard-pinned tags.** Every module now derives its default image tag
    (`mockserver-<version>`) from the MockServer client version — or, for Go, from the module's own version —
    falling back to the mutable `latest` tag when the version cannot be resolved, mirroring the Java module,
    instead of a hard-coded tag that goes stale.

- **WASM response shaping (host ABI v3).** WASM custom-rule modules can now compute the response, not just
  match the request. A module that exports an optional `shape_response(i32 ptr, i32 len) -> i64` function is
  invoked after a match with a JSON envelope `{version:3, request:{…v2 request…}, response:{statusCode,
  headers, body}}` describing the response the matched expectation would return; it returns a (possibly
  partial) response JSON `{statusCode?, headers?, body?}` that MockServer applies — replacing the status,
  merging headers, and replacing the body — enabling WASM-computed dynamic responses. Fully backward
  compatible: modules without the export stay pure predicates, and a module can export **both**
  `match_request` and `shape_response` to match first, then shape. Fail-safe: any trap, invalid JSON, or an
  over-sized return (capped at 1 MiB) leaves the response unshaped and logs once per module — a broken
  shaper never fails the request. The `examples/wasm/sdk-rust` authoring SDK gains `ShapeEnvelope`,
  `ShapeResponse` and a `ResponseBuilder`, plus `export_shape_response!` /
  `export_match_and_shape_response!` macros, and a new `examples/wasm/rust-shape-response` example module
  (matches `POST /shape`, sets `X-Shaped: true`, and rewrites a JSON body field). `POST /mockserver/wasm/test`
  accepts an optional candidate `response` and returns the shaped response so IDEs can preview shaping. See
  `docs/code/wasm-rules.md` and the [WASM Custom Rules](https://www.mock-server.com/mock_server/wasm_rules.html) page.

- **SCIM list endpoints now support sorting.** The mock SCIM 2.0 provider's `GET {basePath}/Users|Groups`
  listing accepts the standard `sortBy` and `sortOrder` query parameters (RFC 7644). `sortBy` is an
  attribute name or nested dotted path (e.g. `name.familyName`); `sortOrder` is `ascending` (default) or
  `descending`. Comparison is case-insensitive and resources with no value for the sort attribute are
  always ordered last. Sorting is applied after any `filter` and before `startIndex`/`count` pagination; a
  malformed `sortBy` path or an invalid `sortOrder` returns a `400` error envelope (matching how an invalid
  filter is rejected), and the `ServiceProviderConfig` now advertises `sort` as supported.
- **More realistic LLM token estimates and subword streaming by default.** The approximate `TokenCounter`
  behind inferred `usage` counts and token-based quotas now approximates GPT-style subword (BPE)
  segmentation instead of a plain characters÷4 blend, landing within roughly ±15% of a real tokenizer for
  ordinary English prose (validated against GPT-4 `cl100k_base` reference counts). Streaming LLM responses
  now emit finer, **subword-sized deltas by default** (via `streamingPhysics.subwordStreaming`), so a
  streamed response streams closer to a real provider's per-token cadence out of the box. **Behaviour
  change:** because a stream now carries more, smaller real-token deltas at the same `tokensPerSecond`, its
  total duration is slightly longer than before, and the per-provider streaming wire output is finer-grained.
  All streaming-physics timing semantics are preserved (each delta is still one physics event). To restore
  the previous whole-word (whitespace-boundary) streaming, set `streamingPhysics.subwordStreaming` to
  `false` explicitly.

- **Editor extensions can start MockServer without Docker.** Both the VS Code extension and the JetBrains/IntelliJ
  plugin gain a **Start (binary, no Docker)** command/action that launches MockServer from the self-contained binary
  bundle (a jlink-trimmed Java runtime + the shaded jar + launcher — see `scripts/build-binary-bundle.sh`), so
  corporate machines without a Docker daemon can run a local server straight from the editor. The bundle is taken
  from a configured local path (`mockserver.binaryPath` in VS Code, *Binary bundle path* in JetBrains settings) —
  either the `bin/mockserver` launcher or the unpacked bundle directory — or, when unset, downloaded on demand
  (after an explicit confirmation) for the current OS/architecture from the GitHub release matching the
  extension/plugin version and cached (under the extension's global storage / IDE system cache). Checksum
  verification against the published `.sha256` sidecar is **fail-closed**: a digest mismatch always aborts, and a
  sidecar that cannot be fetched (common behind a TLS-inspection proxy, where the small sidecar fetch fails while
  the large archive succeeds) aborts too unless the user explicitly confirms installing unverified. The launched
  process is tracked for a matching **Stop (binary)** and terminated on editor/IDE shutdown so it never outlives
  the editor holding the port: VS Code streams its output to the MockServer output channel and its download honours
  the editor's `http.proxy` / system-proxy settings; JetBrains runs it as a tracked background process registered
  for IDE-shutdown cleanup. Docker-based **Start (Docker)** is unchanged. See the
  [IDE Extensions](https://www.mock-server.com/mock_server/ide_extensions.html) page.

- **Match requests by the claims inside a JWT (`jwt` request matcher).** An expectation can now route on a
  JSON Web Token carried in a request header — `withJwt(jwt().withClaim("sub", "user-1").withClaim("scope",
  ".*admin.*"))` matches only requests whose bearer token carries those claims (each claim value is an exact
  string or a regex, with `!` negation supported). Convenience fields `issuer` (`iss`), `audience` (`aud`,
  string or array) and `algorithm` (JOSE header `alg`) are provided, and the header name (default
  `authorization`) and scheme prefix (default `Bearer`) are configurable. The token's `header.payload` is
  decoded with base64url + JSON and **its signature is deliberately not verified** — this is request matching
  for test routing, not authentication (the control-plane JWT auth stack is unchanged). A request with no such
  header, or a malformed token, simply does not match (it never raises an error). Exposed on `HttpRequest` and
  through the JSON wire format (`"jwt": { "claims": { ... }, "issuer": "...", "audience": "...", "algorithm":
  "..." }`) and JSON schema. (Java server + JSON wire format; typed client-library wrappers to follow.)

- **Compose several body matchers that must all match (`ALL_OF` body / `allOf`).** A request body can now be
  matched against several body matchers at once, where every one must match the same body — for example
  `withBody(allOf(jsonPath("$.name"), jsonSchema(schema), regex(".*value.*")))`. This reuses the existing body
  matcher implementations without changing any of their semantics; each component keeps its own `not` flag and
  the composite honours its own `not` (negate the whole conjunction) and `optional` flags. Serialised as
  `{"type":"ALL_OF","bodyAllOf":[ ... ]}` and accepted wherever a body matcher is accepted. (Java server + JSON
  wire format; typed client-library wrappers to follow.)

- **Fluent `MockServerClient.builder()`.** The Java client gains a discoverable fluent builder that
  covers every existing construction dimension in one place — `host` (default `localhost`), `port`
  (default `1080`), `contextPath`, `Configuration`/`ClientConfiguration`, `portFuture`, plus TLS
  (`secure`), `proxyConfiguration`, control plane JWT (`controlPlaneJWT`) and `requestOverride` that
  previously required post-construction `with...` setters. `MockServerClient.builder().host("localhost").port(1080).build()`
  is equivalent to the corresponding constructor call. The eight existing constructors remain fully
  supported and are **not** deprecated; the builder simply delegates to them, so it introduces no
  behaviour change. Misconfiguration stays loud (an empty `host` throws `IllegalArgumentException`, and
  `portFuture(...)` cannot be combined with `host`/`port`/`contextPath`). See
  `docs/code/client-and-integrations.md` and the
  [MockServer Clients](https://www.mock-server.com/mock_server/mockserver_clients.html) page.

- **Observability quick-win bundle — Grafana dashboard, Helm ServiceMonitor, and a durable audit file sink.** Three
  independent additions that make MockServer easier to monitor in production:
  - **Standalone Grafana dashboard for the server metric family.** `examples/grafana/mockserver-server.json` (with a
    README) is an importable dashboard charting request throughput and match outcomes (via the new `_total`
    counters), request-latency percentiles, registered expectations/actions, per-upstream forward/proxy health,
    dropped-log-events and chaos counters, and the JVM runtime gauges — every panel references a metric documented in
    `docs/code/metrics.md`. It exposes a `datasource` variable so it imports against any Prometheus data source, and
    is kept separate from the existing k6 load-injection dashboard.
  - **Optional Prometheus Operator `ServiceMonitor` in the Helm chart.** `serviceMonitor.enabled=true` (disabled by
    default) renders a `monitoring.coreos.com/v1` ServiceMonitor scraping `/mockserver/metrics`, with
    `namespace`/`interval`/`scrapeTimeout`/`path`/`scheme`/`honorLabels`/`labels`/`namespaceSelector`/relabelings
    values. User-supplied `labels` are merged over the chart labels (user wins) so a `release` label for the
    Prometheus `serviceMonitorSelector` overrides cleanly. Requires the Prometheus Operator CRDs and
    `mockserver.metricsEnabled=true`.
  - **Durable NDJSON control-plane audit file sink.** New `auditLogFile` property (empty default = off): when set,
    each recorded audit entry is *also* appended as one JSON object per line to the file, giving a restart- and
    `reset`-surviving trail that outlives the bounded in-memory ring. Implemented as a separate `AuditFileSink`
    writer that only observes the same entries — the in-memory ring is untouched (honouring the "never a sink"
    contract). Path resolved once on first write, parent dirs created, append-only (rotation out of scope), and
    fail-soft (a single WARN then self-disable on IO error, never crashing request handling).

- **OpenAPI request validation now checks `style`/`explode`-serialised array and object parameters.** When you
  validate traffic against an OpenAPI spec (contract/traffic validation, the `verify_traffic` MCP tool, or an
  OpenAPI-backed expectation returning `400` on non-conforming requests), `array`/`object` query, path and header
  parameters were previously skipped unless the value already looked like JSON — so a malformed list or object slipped
  through. MockServer now decodes each parameter from its `style`/`explode` serialisation before schema validation:
  query `form`/`spaceDelimited`/`pipeDelimited`/`deepObject`, path `simple`/`label`/`matrix`, and header `simple`,
  for both `explode` values, with the OpenAPI defaults applied when the spec omits them. Decoding is **type-aware**
  (each element/property is coerced to the JSON type its item/property schema declares) so a request that was valid
  before stays valid — only values the spec genuinely rejects (e.g. a non-integer element in an `items: integer`
  array, or a non-integer property in a `deepObject`) now fail. It is **fail-open**: any value that cannot be soundly
  reconstructed (a non-primitive item/property, or an unsupported style combination) skips the schema check exactly
  as before, while `required`-presence is still enforced. Note this is a (spec-conformant) behaviour change for
  traffic validation: non-conforming style serialisations that previously slipped through unchecked can now return
  `400` — e.g. a comma-delimited list sent where the spec declares the default `form`/`explode: true` (which expects
  repeated parameters) is decoded as a single element and validated as such. One known edge: an empty value for a
  non-explode `form` array decodes as a single empty-string element rather than an empty array.
- **OpenAPI example generation honours `discriminator`, `readOnly` and `writeOnly`.** Generated examples (OpenAPI
  import to expectations, `run_contract_test`/`run_resiliency_test`, load scenarios) are now more faithful: for a
  `oneOf`/`anyOf` schema with a `discriminator`, a concrete subschema is chosen and the discriminator property is set
  to the matching mapping key (or the referenced schema name when no explicit `mapping` is given), instead of blindly
  taking the first subschema; and `readOnly` properties are omitted from **request** examples while `writeOnly`
  properties are omitted from **response** examples, per the OpenAPI spec. Existing callers that do not specify a
  direction are unchanged (no `readOnly`/`writeOnly` filtering).

- **gRPC forward proxy + record/replay — bring the record-then-mock workflow to gRPC.** Until now gRPC
  support was mock-only: MockServer could decode inbound gRPC to JSON and serve mocked responses, but could
  not forward a gRPC call to a real upstream gRPC server. Now, when a gRPC request (HTTP/2 +
  `application/grpc`) matches a `FORWARD`-class expectation — or arrives in proxy mode with no matching
  expectation — MockServer re-encodes the decoded request back into gRPC-framed protobuf, relays it to the
  upstream gRPC service, decodes the framed protobuf response back to JSON, and re-frames it for the calling
  client. The forwarded exchange is recorded in the event log as a `FORWARDED_REQUEST` carrying the decoded
  gRPC method path, status, and (when a proto descriptor is registered) the decoded message JSON, so
  `retrieveRecordedExpectations` (and `promote_recordings`) produce a replayable gRPC mock — the same
  record → snapshot → replay loop already available for HTTP/SSE. A non-OK terminal `grpc-status`/`grpc-message`
  delivered by a real upstream in HTTP/2 (or chunked HTTP/1.1) trailers is preserved through the relay and the
  recording, rather than being defaulted to `OK`. Unary and client-streaming request bodies
  (single JSON object / JSON array) and unary or server-streaming responses (one or more frames) are handled.
  Decoding of the recorded exchange requires the proto descriptor to be loaded on the proxy (via
  `grpcDescriptorDirectory` / `grpcProtoDirectory` / `PUT /mockserver/grpc/descriptors`); without descriptors
  the gRPC bytes are still forwarded verbatim but recorded undecoded. Full bidirectional streaming forward is
  out of scope (it is driven by the multiplex bidi pipeline, not the request/response forward path). The
  transform is fail-safe — a non-gRPC request, an unknown method, or any conversion error leaves ordinary
  HTTP forwarding byte-for-byte unchanged (`GrpcForwardTranslator`, `org.mockserver.grpc`).

- **The dashboard now warns when log events are being silently evicted — the #1 cause of "verification
  intermittently fails".** When MockServer's log ring buffer fills up, the oldest events are dropped, so
  verifications and the dashboard silently miss requests. The Dashboard and Traffic views now show a
  dismissible warning banner whenever the server's `mock_server_dropped_log_events` counter is non-zero
  ("The log ring buffer is full, so the oldest events have been dropped (N so far)…"), pointing at
  `maxLogEntries` / `ringBufferSize` with a link to the performance docs. The banner reuses the existing
  Prometheus metrics endpoint the Metrics view already polls (no new server endpoint) and stays hidden on a
  healthy server or when metrics are disabled. It re-appears only if *more* events are dropped after a
  dismissal.
- **Bulk actions in the dashboard: multi-select expectations and captured requests to clear them in one go.**
  The Active Expectations list and the Traffic inspector each gained a "Select" mode with per-row checkboxes,
  a select-all toggle and a running count. "Delete selected" removes the chosen expectations (batched per-id
  clears) and "Clear selected" removes the chosen captured requests from the log, each behind a confirmation
  dialog. Compare mode in the Traffic inspector is unchanged (still capped at two rows for a diff) and is
  mutually exclusive with the new uncapped select mode.

- **Migration importers for WireMock, Mountebank and Mockoon.** Teams moving off another mock tool can now
  convert their existing stubs into MockServer expectations in one shot through the existing
  `PUT /mockserver/import` endpoint, via `?format=wiremock`, `?format=mountebank` or `?format=mockoon`
  (all three are also auto-detected from the JSON structure when no `format` is supplied). Each importer maps
  the foreign matcher/response model onto MockServer's:
  - **WireMock** stub JSON (single stub, `mappings` array, or bare array) — `method`/`urlPath`/`urlPathPattern`/
    `urlPattern`/`url`, `queryParameters`/`headers` predicates (`equalTo`/`matches`/`contains`), `bodyPatterns`
    (`equalToJson`/`matchesJsonPath`/`contains`/`matches`/`equalTo`), response `status`/`headers`/`body`/
    `base64Body`/`jsonBody`/`fixedDelayMilliseconds`, `fault` → connection error, `proxyBaseUrl` → forward,
    WireMock scenarios → MockServer scenarios, and `priority` (inverted, since WireMock 1 = highest).
  - **Mountebank** imposters (`http`/`https` only; `tcp`/`smtp` skipped with a warning) — `equals`/`deepEquals`/
    `contains`/`matches`/`exists`/`startsWith`/`endsWith` predicates → matchers, `is` → response, `proxy` →
    forward, `fault` → connection error, `_behaviors.wait` → delay, `_behaviors.repeat` → `Times`, and multiple
    `is` responses → one cycling multi-response expectation.
  - **Mockoon** environments — each `route` → expectation(s) with `:param` path segments converted to regex,
    response `statusCode`/`headers`/`body` and `latency` → delay, response `rules` → matchers (with descending
    priority so array order and the `default` catch-all are preserved), and `responseMode` `SEQUENTIAL`/`RANDOM`
    → the matching MockServer response mode.

  Every foreign construct with no faithful MockServer equivalent (TCP imposters, XPath/XML predicates, response
  templating/transformers, compound `and`/`or`/`not` predicates, JavaScript `inject`, unsupported rule
  operators, …) produces a **structured warning** in the response body — `{ "expectations": [...], "warnings":
  [...] }` — rather than being silently dropped. Secret redaction is on by default (as for HAR/Postman/Pact
  import). No new runtime dependencies (Jackson only). See `docs/code/request-processing.md` and the
  [Importing Expectations](https://www.mock-server.com/mock_server/importing_expectations.html) page.

- **AsyncAPI broker mocking — Kafka Avro/Confluent Schema Registry, AMQP subscribe/verify, and MQTT 5.** The
  `mockserver-async` module gains three enterprise-broker parity features, all driven from the existing
  `PUT /mockserver/asyncapi` `brokerConfig`:
  - **Kafka Avro in the Confluent Schema Registry wire format.** Set `kafkaValueFormat: "avro"` to publish and
    consume Kafka messages framed as `magic byte + schema id + Avro binary`, byte-compatible with real Confluent
    Avro producers/consumers. Two modes: **registry-backed** (`kafkaSchemaRegistryUrl` — the schema is registered
    under `<topic>-value` on publish and resolved by id on consume) and **registry-less** (an inline `avroSchema`
    plus a fixed `avroSchemaId`). Consumed Avro is decoded back to JSON so `.../asyncapi/verify` substring and
    JSON-path checks work unchanged. Implemented with **Apache Avro** (Apache 2.0) plus a hand-rolled 5-byte
    framing and a minimal JDK-`HttpClient` Schema Registry REST client — deliberately avoiding the
    Confluent Community License serde stack. Protobuf is deferred.
  - **AMQP (RabbitMQ) subscribe/verify.** AMQP is no longer publish-only: with `consume: true`, MockServer now
    subscribes to and records AMQP messages for verification, mirroring Kafka/MQTT. The queue is derived from the
    channel's `bindings.amqp` (queue-based consumes the named queue; routingKey-based declares the exchange and
    binds a private queue on the routing key).
  - **MQTT 5.** `mqttProtocolVersion: 5` selects the Paho v5 client for publish and subscribe (default `3`/3.1.1);
    v5 additionally delivers message headers (e.g. correlation IDs) as MQTT 5 user properties on publish and
    records them as headers on consume — which MQTT 3 cannot carry.

  New Docker-gated live-broker tests (Kafka, RabbitMQ, Mosquitto) plus non-Docker serde/wire-format unit tests
  cover all three. See [docs/code/async-messaging.md](docs/code/async-messaging.md).

- **Mock OpenAI Realtime & Gemini Live voice APIs over WebSocket — new `RealtimeMockBuilder`.** MockServer can
  now mock the two dominant realtime (voice) LLM protocols so agents/apps that use them can be tested offline,
  with no real API and no audio hardware. A new pure event codec pair in `mockserver-core`
  (`org.mockserver.llm.realtime.OpenAiRealtimeCodec` / `GeminiLiveCodec`) generates the provider-correct
  WebSocket event stream for one scripted assistant turn, and the Java client `RealtimeMockBuilder`
  (`org.mockserver.client`) wires it into a standard `httpWebSocketResponse` expectation — an initial pushed
  `session.created` plus per-incoming-frame matchers — so **no new action type, DTO, or JSON schema** is
  required (exactly as A2A streaming reuses `httpSseResponse`). **OpenAI Realtime** (GA 2025 event protocol,
  `wss://.../v1/realtime`): pushes `session.created` on connect, acknowledges `session.update` and
  `conversation.item.create`, and answers each `response.create` with the full lifecycle — `response.created`
  → `response.output_item.added` → `response.content_part.added` → per-token
  `response.output_audio_transcript.delta` + `response.output_audio.delta` (audio modality) or
  `response.output_text.delta` (text modality) → the matching `*.done` markers → `response.done` with usage.
  **Gemini Live** (`BidiGenerateContent`): answers `setup` → `setupComplete` and each `clientContent` turn with
  a streamed `serverContent` chunk sequence + `generationComplete`/`turnComplete` carrying `usageMetadata`.
  Streaming timing follows a deterministic `tokensPerSecond` / time-to-first-token model; audio bytes are opaque
  silence placeholders (the fidelity target is the event protocol, not audio DSP). Deferred protocol corners
  (server VAD / input-audio-buffer events, function-call output items, Gemini `toolCall`/`realtimeInput`, etc.)
  are documented in `docs/code/ai-protocol-mocking.md` rather than half-implemented.

- **Prometheus `_total` counters for the five monotonic metrics (correct `rate()`/`increase()`).** The five
  genuinely-monotonic counts — `requests_received_count`, `expectations_not_matched_count`,
  `response_expectations_matched_count`, `forward_expectations_matched_count`, and `llm_chaos_injected_count` —
  now additionally publish a proper Prometheus `Counter` alongside their legacy gauge:
  `mock_server_requests_received_total`, `mock_server_expectations_not_matched_total`,
  `mock_server_response_expectations_matched_total`, `mock_server_forward_expectations_matched_total`, and
  `mock_server_llm_chaos_injected_total`. This is non-breaking and additive — the legacy `_count` gauges are
  retained unchanged so the dashboard UI and existing Grafana dashboards keep working, while PromQL
  `rate()`/`increase()` queries can now use the true monotonic `_total` series (e.g.
  `rate(mock_server_requests_received_total[5m])`). The new counters are incremented in lock-step with the
  legacy gauges from the same call sites and are mirrored to the OTLP export as observable monotonic counters.

- **WASM matcher envelope v2 — query parameters and cookies in `match_request`.** The richer WASM ABI now
  exposes the request's **query-string parameters** and **cookies** to a module, so a rule can route on
  `?tenant=acme` or a `session` cookie, not just method/path/headers/body. The JSON envelope passed to
  `match_request` gained a top-level `version` field (currently `2`) plus `queryStringParameters` (name to
  array of values) and `cookies` (name to single value). The change is **additive and backward compatible**:
  every envelope version is a strict superset of the previous one, so existing version-1 modules (which read
  only method/path/headers/body and ignore unknown fields) keep working unchanged — guarded by a
  `WasmRuntimeRequestV2AbiTest` that runs the version-1 example module against a version-2 envelope. The
  `mockserver-wasm-sdk` Rust authoring crate gains `req.query_param(...)`, `req.cookie(...)` and
  `req.version()` accessors (returning `None` against an older envelope), and a new
  `examples/wasm/rust-request-v2/` sample module (with prebuilt `.wasm`) demonstrates query-parameter and
  cookie routing. The `POST /mockserver/wasm/test` endpoint accepts `queryStringParameters` and `cookies` in
  the sample request. See `docs/code/wasm-rules.md`.

- **Test a WASM rule from the editor extensions.** The VS Code and JetBrains MockServer extensions can now
  call `POST /mockserver/wasm/test` to check what a WASM module does against a sample request without
  uploading it or creating an expectation, complementing the existing WASM module upload/list wiring.

- **Deterministic embeddings are now semantically plausible, so offline RAG-retrieval tests can rank.** A mocked
  embeddings response with `deterministicFromInput: true` previously produced a hash-seeded uniform-random unit
  vector, so cosine similarity between related texts was meaningless — you could not test vector-search / RAG
  ranking against a mock. MockServer now builds the deterministic vector by **n-gram feature hashing**: the input
  is tokenised (Unicode-aware, lowercased) into word unigrams, word bigrams, and character 3-grams; each feature
  is hashed (seeded FNV-1a) into a bucket with a signed, sublinear-TF-weighted contribution; the result is
  L2-normalised. Texts that share vocabulary now have a **higher cosine similarity** (paraphrases ~0.3–0.6) while
  unrelated texts stay **near-orthogonal** (~0.0–0.1) — e.g. `"the cat sat on the mat"` ranks far above
  `"quarterly financial report"` against `"a cat sits on a mat"` — so retrieval code can rank related documents
  offline with no real embedding model. The vector stays deterministic for the same input, seed, and
  dimensions and unit-length (feature-less input falls back to a seeded non-zero vector); the
  `dimensions`/`seed` parameters, provider JSON envelopes, and the non-deterministic (default) random path are
  unchanged.

- **Authenticated cross-node cluster verify/retrieve fan-in.** The opt-in cluster fan-in
  (`clusterVerifyFanIn`) now works on a cluster with control-plane authentication enabled. A new
  `clusterFanInPeerAuthToken` property (env `MOCKSERVER_CLUSTER_FAN_IN_PEER_AUTH_TOKEN`, default empty)
  gives the peer accessor (`HttpClusterPeerAccessor`) a credential to present on every cross-node query:
  when set, it is sent **verbatim** as the control-plane `Authorization` header (include the scheme, e.g.
  `Bearer <jwt>`), so peers accept the fan-in query instead of rejecting it with 401/403. All nodes must
  share the same token. With no token (the default) no credential is sent — unchanged, non-breaking
  behaviour; fan-in remains off by default. The property is wired through the config trio
  (`ConfigurationProperties`/`Configuration`/`ConfigurationDTO`) and is covered by the reflective DTO
  round-trip drift guard. The programmatic `retrieve(REQUESTS/REQUEST_RESPONSES)` path that backs
  dashboard export / one-shot traffic queries already fans in when enabled (verified with a test). Still
  node-local by design (documented, no shared clock across nodes): `verifySequence` cross-node ordering,
  the **live** dashboard WebSocket log-view stream, rate-limit / chaos-quota counters, and mTLS
  client-certificate peer authentication. See `docs/code/clustered-state.md`.

- **Startup warm-up removes first-request latency — new `startupWarmup` property (default on).** The very
  first request handled by a freshly started MockServer was a few hundred milliseconds slower than every
  request after it because the request-handling path (Netty HTTP codec, JSON serialisation, response writers)
  only loads and initialises on first use — a cost paid by every readiness poll, including Testcontainers wait
  strategies. MockServer now sends itself a single background `PUT /mockserver/status` loopback request
  immediately after the ports bind, so that one-off cost is paid off the start-up thread and the first real
  request is fast. The warm-up never delays port binding, is fail-soft (any failure is ignored and logged only
  at TRACE), and uses a control-plane endpoint that creates no recorded requests or log events, so it never
  pollutes `verify`/`retrieve`. Disable with `-Dmockserver.startupWarmup=false` /
  `MOCKSERVER_STARTUP_WARMUP=false` (e.g. in a locked-down environment where MockServer must not connect to
  itself).

- **MCP spec 2025-06-18 negotiation with structured tool output and resource links.** MockServer's MCP
  server (`McpRequestProcessor`) now advertises and negotiates the **2025-06-18** MCP revision while staying
  backward compatible: a client that requests `2025-06-18` gets it, clients still on `2025-03-26`/`2024-11-05`
  keep getting their requested revision (echoed back), and an unknown/omitted version falls back to the latest
  `2025-06-18` (`negotiateProtocolVersion`, stored per-`McpSession`). For sessions that negotiated 2025-06-18+,
  `tools/call` results additionally carry **`structuredContent`** (the machine-readable tool-result object)
  alongside the existing text block; older sessions are unchanged. The Java `McpMockBuilder` gains
  `withOutputSchema(...)` (advertised in `tools/list`), `respondingWithStructured(text, structuredJson)`
  (emits `structuredContent`), and `respondingWithResourceLink(uri, name, description, mimeType)` (emits a
  `resource_link` content item), and now defaults `protocolVersion` to `2025-06-18`. The `McpContractTest`
  conformance tester defaults to `2025-06-18`, records the server's negotiated version, and validates the new
  `structuredContent`/`resource_link` shapes when present (optional — older servers still pass). `Mcp-Session-Id`
  emission/handling was already in place. Elicitation (`elicitation/create`) and the GET SSE server-push stream
  are not mocked (they require a server→client channel MockServer's request/response model does not have); JSON-RPC
  batching remains accepted for back-compat. No MCP tools were added or reclassified.

- **Expectation-authoring and record/replay control tools on the MCP server.** An AI coding agent
  (Claude Code, Cursor, etc.) can now stand up and drive mocks entirely through the MCP server at
  `/mockserver/mcp`, closing the "AI agents can only read, not author" gap. Three new tools are added,
  each delegating to the existing `HttpState` control-plane operation (no logic fork):
  `list_expectations` (READ — active expectations, optionally filtered by method/path;
  `PUT /retrieve?type=ACTIVE_EXPECTATIONS`), `set_operating_mode` (MUTATE — switch SIMULATE/SPY/CAPTURE;
  `PUT /mockserver/mode`), and `promote_recordings` (MUTATE — turn recorded traffic into active mocks with
  redaction/consolidation/parameterization; `PUT /mockserver/recordings/promote`). Each tool is classified
  read-vs-mutate so the control-plane authorization gate applies — a MUTATE tool requires the MUTATE role
  when `controlPlaneAuthorizationEnabled` is on. The pre-existing authoring/read tools (`create_expectation`,
  `raw_expectation`, `clear_expectations`, `verify_request`, `retrieve_recorded_requests`,
  `retrieve_request_responses`) are unchanged; the `/mockserver/mode` and `/mockserver/recordings/promote`
  REST handlers were refactored onto new shared `HttpState.setMode(...)` / `HttpState.promoteRecordings(...)`
  methods so REST and MCP share one code path.

- **OpenAI Responses API server-side state — `previous_response_id` chaining, `store`, and
  `GET /v1/responses/{id}`.** MockServer's Responses API mock (`OPENAI_RESPONSES`) is no longer stateless:
  each issued `POST /v1/responses` response is recorded (by default; honours the request's `store` flag) in a
  new process-wide `OpenAiResponsesStore`, so agents that chain turns via `previous_response_id` — sending only
  the new turn plus the prior response id — now run against the mock. `OpenAiResponsesCodec.decode` prepends the
  stored prior conversation when a request carries a `previous_response_id`, so conversation matchers and usage
  inference see the full dialogue, and `GET /v1/responses/{id}` returns the stored response body. The store is
  bounded (LRU), cleared on server reset, and fully back-compatible — a request with no `previous_response_id`
  and the default `store:true` behaves exactly as before (it only additionally records the response).

- **OpenAI-compatible provider aliases: Mistral, xAI (Grok), DeepSeek, Groq, and OpenRouter.** Five new
  `Provider` values whose codecs and runtime clients delegate to the OpenAI Chat Completions implementations
  (exactly as `AZURE_OPENAI` does), distinguished by host (`api.mistral.ai`, `api.x.ai`, `api.deepseek.com`,
  `api.groq.com`, `openrouter.ai`) in both `LlmProviderSniffer` and `ProviderDetector`. Proxy observability now
  classifies traffic to these gateways as LLM (with provider-correct GenAI spans and cost metrics) instead of
  dropping it as non-LLM. Approximate, clearly-flagged pricing rows were added for each in `LlmPricing`
  (OpenRouter routes vendor-prefixed model ids such as `openai/gpt-4o` to the underlying vendor's table).

- **Chaos experiment composition: recurring runs, staged TCP/lifecycle faults, steady-state pre-check, and
  history.** The `ChaosExperimentOrchestrator` now composes fault primitives beyond a single one-shot HTTP run,
  all as new optional fields that default to the previous behaviour:
  (1) **Recurring cron experiments** — set `"recurring": true` alongside a `cronSchedule` and, after each clean
  completion, the experiment records the run and re-arms itself for the next cron occurrence (e.g. a
  `"nightly-error-storm"` on `"0 2 * * *"`) instead of going terminal after one run; a stop, auto-halt, or SLO
  breach still ends it for good.
  (2) **Staged TCP / connection-lifecycle faults** — a stage may carry a `tcpProfiles` map (host →
  `TcpChaosProfile`) applied/reset with the same discipline as HTTP `profiles`, so transport-level faults
  (RST, GOAWAY, latency, bandwidth, preemption) get the same auto-halt and stage progression; a stage is valid
  with HTTP profiles, TCP profiles, or both.
  (3) **Steady-state baseline pre-check** — with an `sloCriteria`, an optional `baselineWindowMillis` evaluates
  the SLO over the pre-experiment lookback window before applying stage 0 and refuses to start
  (`aborted_baseline_unhealthy`, verdict attached) if the steady state does not already hold, instead of running
  and blaming the experiment.
  (4) **Bounded experiment history** — every terminal transition (including each recurring run and a
  baseline-refused start) is appended to a bounded ring (last 50, newest first) exposed at
  `GET /mockserver/chaosExperiment/history` for recurring-run trails and CI trend dashboards. The new
  `recurring`, `tcpProfiles`, and `baselineWindowMillis` fields round-trip through the experiment definition JSON.

- **Typed mock-drift client methods across all 8 client libraries.** Each client now exposes a typed wrapper
  for the drift-detection control plane — `retrieveDrift()` (`GET /mockserver/drift`, returns the parsed
  `{ count, drifts }` report) and `clearDrift()` (`PUT /mockserver/drift/clear`) — so programmatic users no
  longer have to hand-roll raw HTTP. Added to the Java (`retrieveDrift`/`clearDrift`), Node
  (`retrieveDrift`/`clearDrift`), Python (`retrieve_drift`/`clear_drift`), Ruby (`retrieve_drift`/`clear_drift`),
  Go (`RetrieveDrift`/`ClearDrift`), .NET (`RetrieveDrift`/`ClearDrift` + async variants), Rust
  (`retrieve_drift`/`clear_drift`) and PHP (`retrieveDrift`/`clearDrift`) clients, each following that client's
  existing control-plane conventions, with mocked-transport unit tests. The drift-detection documentation now
  shows client-library tabs alongside the REST examples.

- **Experimental JDK 25 AOT-cache Docker image variant (Project Leyden) for ~2x faster container startup.**
  New `docker/aot/Dockerfile` builds a MockServer image on a jlink-trimmed Temurin 25 runtime with an
  ahead-of-time cache (JEP 483/514) baked in via a training run at image build time. Measured time-to-ready
  roughly halves versus the standard image (~0.35 s vs ~0.7–0.8 s from `docker run` to a 200 from
  `PUT /mockserver/status`) with identical behaviour — it is the real HotSpot JVM, so 100% feature parity.
  Published from this release as opt-in `X.Y.Z-aot` / `latest-aot` tags (Docker Hub + ECR Public),
  error-isolated in the release pipeline like the clustered image, and selectable from the MockServer
  Testcontainers module via `new MockServerContainer(MockServerContainer.aotImage())`. The Testcontainers
  documentation now explains the fast-test hierarchy (suite-scoped container + `reset()`, in-process
  MockServer for Java tests) and the runtime-level options evaluated (AppCDS, AOT cache, GraalVM native
  image) with measured figures and the reasons native-image is not supported. (relates to #2385)

- **Mock-drift detection master switch and sampling.** New `driftDetectionEnabled` (boolean, default `true`) turns
  mock-drift analysis of forwarded responses on or off, and `driftSampleRate` (double `0.0`–`1.0`, default `1.0`)
  analyses only a sampled fraction of forwarded responses. Both defaults preserve the previous always-on behaviour;
  set `driftDetectionEnabled=false` (or lower `driftSampleRate`) to cut the per-forward overhead when proxying at
  high volume.

- **Runnable Kubernetes example: load-injection metrics visualised in Grafana over Prometheus *and* OpenTelemetry.**
  New `examples/kubernetes/load-injection-observability` stands up a local k3s cluster (via k3d) running MockServer,
  Prometheus, an OpenTelemetry Collector and Grafana with a provisioned dashboard that renders the full
  `mock_server_load_*` family — active VUs, throughput, latency percentiles, failures, status codes, throttling and
  data transfer — alongside JVM heap/GC/threads and real pod CPU/memory, driving the point that MockServer's
  first-class load-injection metrics can be charted next to the system under test on one dashboard. The Load
  Injection and Examples documentation pages now lead with this observability advantage and link the example.

- **Recorded expectations can be consolidated, parameterised, and promoted to active mocks in one REST call.**
  `GET /mockserver/retrieve?type=RECORDED_EXPECTATIONS&consolidate=true&parameterize=true` now post-processes
  captured recordings: `RecordedExpectationPostProcessor.consolidate()` groups exchanges by request shape into a
  single `Times.unlimited()` expectation, infers `{id}` path-parameter slots from varying URL segments, strips
  volatile headers, and sequences differing responses as a `SEQUENTIAL httpResponses` list. A new
  `PUT /mockserver/recordings/promote` REST endpoint filters, redacts, consolidates/parameterises and activates
  recorded traffic in one step — the REST equivalent of the MCP `create_expectations_from_recorded_traffic`
  tool. `PUT /mockserver/import?format=har` now also accepts `?consolidate` / `?parameterize` to collapse
  repetitive HAR captures. Default (non-`?consolidate`) retrieval is unchanged and non-breaking.

- **SSE, WebSocket, and gRPC stream messages can now be rendered as Velocity, Mustache, or JavaScript templates.**
  An optional `templateType` field (`VELOCITY` | `MUSTACHE` | `JAVASCRIPT`) on `httpSseResponse` /
  `httpWebSocketResponse` / `grpcStreamResponse` makes each event or message payload a response template rendered
  against the triggering request — using the same engines, context (request fields, `jsonPath`, built-in helpers,
  faker, scenario state), and lazy-caching as `HttpResponseTemplateActionHandler`. SSE renders a per-event copy so
  the stored event is never mutated; WebSocket renders text frames before breakpoint interception; binary frames are
  never templated. JavaScript streaming payloads require GraalJS and fail loudly with the same actionable error the
  response-template path uses when GraalJS is absent. Opt-in and non-breaking: without a `templateType` every
  payload is emitted byte-for-byte unchanged.

- **Load scenario steps can assert response correctness and abort when a check-failure threshold is exceeded.**
  Each `LoadStep` now accepts an optional `checks` list — each check tests the response `status` code,
  a `header` value, or a `jsonPath` expression — and a `checkFailureRate` threshold (0.0–1.0). When the
  observed failure rate for a step's checks breaches the threshold, MockServer can abort the scenario or record
  a threshold violation, so a load run generating incorrect responses (e.g. the upstream returning `500`s that
  the test was silently ignoring) fails the scenario rather than producing meaningless throughput numbers.

- **Expectations can now match on the client certificate presented in a mutual-TLS handshake.** A new
  `clientCertificate` request matcher selects expectations by the leaf certificate of the client's mTLS chain:
  `subject` (Common Name, full Distinguished Name, or any Subject Alternative Name — DNS / IP / email / URI),
  `issuer` (CN or full DN), or `fingerprintSha256` (SHA-256 of the DER encoding, colon/whitespace and case
  normalised). Each criterion is a `NottableString` (exact, regex, or `!`-negated); negation uses De Morgan
  semantics across candidate forms so `!X` only matches when no candidate equals `X`. Non-breaking: an
  expectation without a `clientCertificate` matcher behaves exactly as before, and a request that presents no
  chain never matches a non-blank criterion. Matching only — mTLS authentication (`MTLSAuthenticationHandler`)
  is unchanged.

- **Recorded traffic can now be persisted to disk and re-imported on demand (opt-in).** With
  `persistRecordedRequestsToDisk` enabled (default off), the append-only NDJSON archive captures both forwarded
  (`FORWARDED_REQUEST`) and mocked (`EXPECTATION_RESPONSE`) request/response pairs, flushed one JSON object per
  line, so the complete session survives ring-buffer eviction and server restarts; `redactSecretsInLog` redaction
  still masks credentials on write. New `PUT /mockserver/import?format=recording` reloads the archive
  (from the request body, or from `persistedRecordedRequestsPath` via `?source=disk`) via
  `RecordedTrafficImporter`, re-injecting each pair into the event log exactly as an in-memory recording —
  idempotent, since reloaded entries never grow the disk archive. The importer skips and counts malformed or
  crash-truncated lines (exposed in `x-mockserver-recorded-requests-skipped`) and only rejects a body where
  every non-blank line is unparseable; an empty archive imports 0 entries (`201`) rather than `400`.
  Re-imported `EXPECTATION_RESPONSE` exchanges are recorded as forwarded under disposition-based verification
  — a known limitation, documented. Both defaults preserve existing behaviour.

- **`verify()` and `retrieve()` can scatter-gather across all cluster members (opt-in).** The request/response
  event log is per-node, so behind a load balancer `verify()` and `retrieve(REQUESTS/REQUEST_RESPONSES)`
  previously saw only the traffic that hit the queried node — a silent correctness trap. Two new properties
  (`clusterVerifyFanIn`, `clusterVerifyFanInPeers`) enable scatter-gather: `ClusterFanIn` queries each peer's
  local log with a `fanInLocalOnly=true` recursion guard, merges results, and applies `VerificationTimes` to the
  combined count. Fail-closed: an unreachable peer yields a `502` verify failure rather than a partial result.
  Off by default (non-breaking). `verifySequence` cross-node ordering, response-aware verify, and dashboard log
  fan-in are deferred.

### Changed

- **The standard and local Docker images now run on a JDK 25 runtime.** The `mockserver/mockserver` image
  (built from `docker/local/Dockerfile`, and its `docker/Dockerfile` download-mode reference) now bakes a
  jlink-trimmed **Eclipse Temurin 25** runtime and its AppCDS archive, moving off JDK 17. The MockServer library
  itself is still compiled to the Java 17 bytecode floor — this is a runtime-only change (running the same jar on
  a newer JVM), with no API or behaviour changes. The jlink step uses the JDK-25 `--compress=zip-6` form (the
  legacy numeric `--compress=2` was removed after JDK 17). The baked startup-optimisation figure (~0.57 s) was
  measured on JDK 17 and should be re-measured on JDK 25; a single local container observation was comparable.

- **Standard Docker image starts ~34% faster — Application Class Data Sharing (AppCDS) archive baked in at
  image build.** The standard `mockserver/mockserver` image now trains an AppCDS class archive over MockServer's
  own classes during the image build (the same train-at-build approach as the `-aot` variant, on a
  jlink-trimmed JDK 17 runtime), so the JVM maps pre-parsed class data instead of re-loading ~5,800 classes on
  every container start. Measured launch-to-ready dropped from ~0.86 s to ~0.57 s on the same machine. This is
  the standard HotSpot JVM with full feature parity — no behaviour changes — and if the archive is ever
  missing or unreadable the JVM logs a warning and starts normally without it. Image size is unchanged
  (the trimmed runtime offsets the archive). The `-aot` variant (JDK 25 Leyden) remains the fastest-start
  option.

- **Fewer startup threads when not proxying — the forward-client event-loop group is now created lazily.**
  The Netty event-loop group used to forward/proxy requests to upstream services (5 threads by default,
  `clientNioEventLoopThreadCount`) was created at server construction even for pure-mock deployments that
  never proxy. It is now created on the first forward/proxy action, so mock-only servers start with fewer
  threads and less allocation. Proxy behaviour is unchanged, including the guarantee that forwarded requests
  never share event loops with the server's own worker threads (the deadlock-prevention isolation is
  preserved exactly).

- **Faster startup when TLS is not used — BouncyCastle security provider now registers lazily.** The
  BouncyCastle JCE provider (several hundred classes) was loaded and registered during server construction
  even when no TLS connection was ever made. Registration is now deferred to the first operation that
  actually needs it (dynamic certificate or key generation, typically the first HTTPS connection), removing
  that class-loading cost from start-up for plain-HTTP usage. Behaviour is unchanged for TLS users — the
  provider registers exactly as before on first use, and `proactivelyInitialiseTLS=true` still initialises
  everything eagerly at start-up.

### Fixed

- Fixed the Python client's `Body.regex(...)` factory producing a literal-string match instead of a real
  regex matcher. It previously emitted the wire form `{"type": "REGEX", "string": <value>}`; MockServer's
  `BodyDTODeserializer` treats a `string` value-key as a `STRING` body (overriding the `type` field), so
  `Body.regex(".*admin.*")` was silently deserialised to a `StringBody` and only matched the literal text
  `.*admin.*`, never as a regex. It now emits the schema-correct `{"type": "REGEX", "regex": <value>}` (the
  same object as the existing `RegexBody`), so a request body that merely *contains* the pattern matches as
  intended. (The parallel `Body.regex_match(...)` helper introduced in the same unreleased cycle has been
  removed as redundant — use the now-correct `Body.regex(...)`.)

- Fixed `JAVASCRIPT` response/forward templates silently degrading when the optional GraalJS engine
  (`org.graalvm.polyglot:polyglot` + `js`) is absent from the classpath — as it is in the standard netty
  jar-with-dependencies and Docker image. Previously such a template logged an error and returned an empty
  (`null`) response, so a user who wrote a JavaScript template got a confusing degraded result. It now
  **fails loudly** with a clear, actionable error: *"JavaScript response templates require the GraalJS
  engine, which is not on the classpath. Add the org.graalvm.polyglot:js (or js-community) dependency, or
  use the Velocity or Mustache template engine."* Behaviour is unchanged when GraalJS is present. The
  response-templates documentation now states that only Velocity and Mustache are available by default and
  that JavaScript requires adding the optional GraalJS dependency (or the `graaljs` Docker image variant).

- Fixed silent loss of expectations and log events on JVMs that report an undefined (`-1`) heap max via JMX
  (for example GraalVM native images or unusual servlet-container setups): the heap-based defaults for
  `maxExpectations` and `maxLogEntries` computed a negative capacity, so expectations were accepted with `201`
  but never stored and log events were dropped from startup. The sizing now falls back to `Runtime.maxMemory()`
  and floors both defaults at 1,000 when no usable heap ceiling is reported. (relates to #2385)

- **Header-only `FORWARD_REPLACE` modifications no longer collapse streaming responses, and content-type-less
  streams are relayed incrementally on all forward paths.** Any response override on a
  `forwardOverriddenRequest` previously forced full body aggregation (`disableStreaming=true`), so adding a
  single CORS or trace header to an SSE or LLM streaming upstream silently buffered the entire response and
  could cause the client to time out waiting for response headers. A header-only modification (status / headers
  / cookies, no body change) is now applied to the streamed response head while body chunks are relayed
  untouched; only a body-affecting override (body/schema replacement, JSON patch/merge-patch, or a response
  template) still disables streaming. Additionally, content-type-less streaming (SSE without
  `Content-Type: text/event-stream`) is now relayed incrementally on the transparent CONNECT relay and the
  HTTP/2 upstream forward path as well as the HTTP/1.1 path.

### Security

- **Control-plane mTLS now validates a full PKIX certificate path and enforces the clientAuth Extended
  Key Usage.** `MTLSAuthenticationHandler` previously validated a presented client certificate only with a
  single-level signature `verify()` plus a validity-window `checkValidity()` check. It now builds a proper
  PKIX `CertPath` for the presented certificate and validates it against the configured control-plane CA(s)
  as trust anchors (revocation checking disabled by default, consistent with the rest of the codebase, so
  validation stays fully offline). It additionally enforces Extended Key Usage: when the client certificate
  carries an EKU extension it must permit `clientAuth` (`id-kp-clientAuth` 1.3.6.1.5.5.7.3.2) or
  `anyExtendedKeyUsage`, so a certificate scoped to `serverAuth` only can no longer authenticate as a
  control-plane client. A certificate with no EKU extension remains unrestricted and is still accepted (RFC
  5280 practice), so this is backward compatible with existing client certificates. Enabled only when
  `controlPlaneTLSMutualAuthenticationCAChain` is configured.

- **SOCKS5 proxy authentication now compares the username and password in constant time.**
  `Socks5ProxyHandler` compared the configured proxy credentials with `String.equals`, whose early-exit on
  the first differing byte is a timing side channel an attacker could use to recover the credentials one
  byte at a time. Both the username and password are now compared with a shared constant-time helper
  (`ConstantTimeEquals`, `MessageDigest.isEqual` on UTF-8 bytes) — the same timing-safe comparison the
  data-plane authenticator already uses, now extracted so there is a single audited implementation. Correct
  credentials are still accepted and wrong credentials still rejected exactly as before.

- **Documented that the `/mockserver/metrics` Prometheus scrape endpoint is unauthenticated by design,
  and how to secure it.** The scrape endpoint is intentionally served outside the control-plane auth gate
  (Prometheus/OTEL scrapers cannot present a control-plane certificate or bearer token), so its labels
  (`upstream_host`; LLM `provider`/`model` token & cost counters) are readable by anyone with network reach.
  No behaviour change: `metricsEnabled=false` (the default) already fully disables it (returns `404`, exposes
  nothing), and the JSON snapshot `PUT /mockserver/retrieve?type=METRICS` remains behind the control-plane
  auth gate — only the scrape endpoint is open. The API Security page and internal docs now spell out the
  trade-off and the three ways to lock it down: disable it, restrict it at the network layer, or prefer
  PUSH-based export (OpenTelemetry OTLP metrics or Prometheus Remote-Write), which exposes no scrape endpoint.

- **The dashboard and UI WebSocket now require control-plane authentication when it is enabled.** With
  mTLS/JWT/OIDC control-plane auth configured, `GET /mockserver/dashboard*` and the
  `/_mockserver_ui_websocket` upgrade were previously served without credentials, so any network-reachable
  client could receive a live push of all captured traffic — including request and response bodies. Both are
  now gated by the same `HttpState.controlPlaneRequestAuthenticated` check as `PUT /mockserver/configuration`;
  the WebSocket upgrade returns a raw `401`/`403` handshake rejection when credentials are absent or
  insufficient. The dashboard is treated as a read, so a read-only control-plane role may view it. When no
  control-plane auth is configured (the default) the dashboard and WebSocket remain open, and `/status` /
  `/ready` are always credential-free. The SPA's `useWebSocket` hook now shows an actionable auth-required
  message on `401`/`403`.

- **Experimental HTTP/3: QUIC tokens now bind the client source address, and CONNECT-UDP relay targets can be
  restricted.** Two defence-in-depth fixes apply when `http3Port` is non-zero (off by default). (1)
  Source-address-validating retry tokens: `InsecureQuicTokenHandler` (plaintext, trivially forgeable) is
  replaced by `SourceAddressQuicTokenHandler` (HMAC-SHA256, per-server random key, client IP bound), so a
  forged-source Initial packet cannot obtain a valid token — mitigating QUIC address-spoofing and traffic
  amplification across IPv4 and IPv6. (2) CONNECT-UDP relay restriction: new `http3ConnectUdpAllowedTargets`
  (comma-separated host / `host:port` allowlist, default empty) limits MASQUE relay targets; non-listed
  targets are refused with `403`. The existing `forwardProxyBlockPrivateNetworks` policy (private, loopback,
  link-local, cloud-metadata ranges) is also honoured on the QUIC relay path. Both default to the previous
  open behaviour unless a restriction is opted into.

### Documentation

- **Consumer doc navigation improvements on four pages.** `configuration_properties.html` gains a searchable property index (filter input + full table of all ~150 properties with section links, client-side JS, links open the accordion automatically) and anchors for the Clustering and Cloud Blob Store sections. `using_openapi.html` gains a capability overview table (generate expectations / use as request matcher / verify / clear / contract test — per spec format). `debugging_issues.html` gains a retrieval methods quick-reference table (REST path + Java client method + return type for each retrieve type). `proxy/configuring_sut.html` gains a proxy-type comparison TOC table (code changes required, multi-host support per proxy type).

## [7.3.0] - 2026-07-01

### Added

- **Typed client methods for control-plane operations that previously needed a hand-written REST call.** The
  client libraries gain first-class methods for clock control (freeze / advance / reset / status), metrics
  (the JSON counter snapshot and the Prometheus scrape), configuration read/update, Pact import / export / verify,
  the file store (store / retrieve / list / delete), HAR and Postman import, the high-level operating mode
  (`SIMULATE` / `SPY` / `CAPTURE`), and generating expectations from a WSDL — so these no longer require a manual
  `PUT /mockserver/…` request. Rolling out across the Java, Node, Python, Ruby, Go, .NET, Rust and PHP clients.

### Security

- **Fixture redaction now also masks credentials in query strings and streamed bodies, and fails closed on
  unparseable secrets.** When redacting recorded traffic (HAR/Postman imports, the LLM optimisation report, the
  MCP capture tools) the redactor previously only masked sensitive headers and named JSON body fields. It now also
  (a) masks the values of credential-bearing query parameters by default (such as `key`, `api_key`, `apikey`,
  `access_token`, `token`, `signature`, `sig`, and the AWS SigV4 `X-Amz-Signature`/`X-Amz-Security-Token`) —
  e.g. Gemini's `?key=` API key; (b) redacts configured fields inside each Server-Sent-Events `data:` payload of
  a streamed body, leaving non-JSON markers such as `[DONE]` intact (and failing closed on a `data:` payload it
  cannot parse that still mentions a configured field); and (c) when a body is configured for field redaction but
  cannot be parsed yet still mentions a configured field name, replaces the whole body rather than risk leaking it.
  Ordinary unstructured bodies (plain text, HTML, decoded binary) that mention no configured field are left
  unchanged.
- **A2A client builders: the custom-handler regex `messagePattern` is now escaped completely.** Every client
  library (Java, Node, Python, Ruby, Go, Rust, PHP, .NET) inlines `messagePattern` into a JSONPath `=~ /…/` regex
  literal but previously escaped only the `/` delimiter, so a pattern ending in a lone backslash (or containing
  `\/`) could escape the closing delimiter and break out of the regex literal into the surrounding JSONPath/JSON
  (CodeQL `rb/incomplete-sanitization`). The escaping now preserves valid regex escape sequences (e.g. `\d`) while
  neutralising the delimiter-breakout; normal patterns are unaffected.
- **Dashboard load-scenario report download now validates the URL scheme.** The "download report" action passed a
  URL assembled from the user-configured connection to `window.open` without checking its scheme; it now opens the
  report only when the URL resolves to `http`/`https`, ruling out `javascript:`/`data:` redirection (CodeQL
  `js/client-side-unvalidated-url-redirection`).
- **`/bind` and `/stop` now honour control-plane authentication/authorization.** These mutating lifecycle
  endpoints were serviced before the auth gate; they now require the same control-plane auth as
  `/mockserver/configuration`. Default deployments with no control-plane auth configured are unaffected, and
  `/status` / `/ready` remain open for health probes. Closes the lifecycle-endpoint gap noted in 7.2.0.
- **MCP tool calls now honour control-plane authorization.** With `controlPlaneAuthorizationEnabled`, each MCP
  tool is classified read vs mutate (fail-closed) and checked against the same role model as the HTTP control
  plane, so a read-only principal can no longer invoke mutating MCP tools (create/clear/reset/…). Default
  (authorization disabled) behaviour is unchanged; enforced across HTTP and HTTP/3, single and batch. Closes
  the per-tool MCP gap noted in 7.2.0.
- **Control-plane JWT validation cross-request race fixed.** A single shared `JWTValidator` reconfigured the
  Nimbus processor (key selector + claims verifier) on every call, so concurrent control-plane requests could be
  verified against another request's policy. The processor is now configured once and `validate()` is stateless.
- **Remote JWKS / OIDC discovery fetches are now bounded.** JWKS-key-set and OIDC discovery-document fetches on
  the authentication path used the JOSE library defaults (infinite connect/read timeout, no size limit); they now
  use finite timeouts and a size cap, so a slow or hostile identity-provider endpoint can no longer hang the auth
  path or be used as an amplification vector.
- **Velocity templates can no longer fetch arbitrary URLs or read local files.** The Apache Velocity
  `ImportTool` (which exposes `$import.read(url|file)`) was registered in the template toolbox; it has been
  removed, closing an SSRF / local-file-disclosure vector in response templates.
- **mTLS control-plane authentication rejects expired client certificates.** Client-certificate authentication
  validated only that the certificate chained to the configured CA; it now also enforces the certificate
  validity window, so an expired or not-yet-valid (but correctly signed) client certificate is rejected.
- **Mock OIDC client-secret comparison is now constant-time.**

### Added

#### Load injection, chaos & SRE
- **Chaos experiments can assert an SLO and emit a verdict.** A chaos experiment may now carry an optional
  `sloCriteria`; on termination MockServer attaches a terminal `experimentVerdict` (`PASS` / `FAIL` /
  `INCONCLUSIVE`) evaluated strictly over the experiment's window — `PASS` only if every objective held
  throughout, `FAIL` on any breach or auto-halt, `INCONCLUSIVE` below the minimum sample count. Turns
  "inject faults" into "verify resilience held."
- **SLO-breach auto-halt for chaos experiments.** An experiment carrying `sloCriteria` is halted immediately
  (status `halted_by_slo_breach`, verdict `FAIL`) when an SLO objective is breached mid-run. No behaviour
  change when `sloCriteria` is absent. The dashboard's chaos panel now shows the terminal `experimentVerdict`
  (PASS / FAIL / INCONCLUSIVE) with per-objective observed-vs-threshold detail.

#### Request matching & response generation
- **JavaScript response templates now have a configurable execution timeout.** A runaway or malicious
  JavaScript template (for example one containing an infinite loop) could previously pin the data-plane
  worker thread handling that request indefinitely. A new `javascriptTemplateExecutionTimeout` property
  (milliseconds) caps how long a template may run; on expiry a watchdog cancels the evaluation and the
  request fails fast with a clear, logged timeout error. The default is `5000` (5 seconds), far longer
  than any legitimate template needs. Set it to `0` (or a negative value) to disable the timeout and
  restore the previous unbounded behaviour. NOTE: this introduces a bounded behaviour change — templates
  that genuinely run longer than 5 seconds (previously allowed) will now be cancelled unless the timeout
  is raised or disabled.
- **Mustache response templates can now read scenario state by name.** Velocity
  (`$scenario.get('orderId')`) and JavaScript (`scenario.get('orderId')`) could already read
  scenario/captured state in a response template; the Mustache engine now exposes the same through a
  section lambda — `{{#scenario.get}}orderId{{/scenario.get}}`, where the state name is the section
  body (jmustache cannot pass a method argument inline the way Velocity and JavaScript can). This
  completes `capture` → template value reuse across all three template engines, so an id captured
  from one request can be returned in the response body of a later request regardless of template
  engine. Documented on the Stateful Scenarios page with a per-engine example.
- **Closest-match hint on unmatched requests** (`closestMatchHintEnabled`, default **on**). When a request
  matches no expectation, the `404` response now carries a compact, length-bounded
  `x-mockserver-closest-match-hint` header naming the closest expectation and the first field that differed —
  answering "why didn't my mock match?" without enabling verbose diagnostics. Set `closestMatchHintEnabled=false`
  to suppress. (The opt-in `attachMismatchDiagnosticToResponse`, which adds a full JSON diagnostic body, is
  unchanged and still off by default.)

#### OpenAPI & contract testing
- **Validate recorded traffic against an OpenAPI spec** (`PUT /mockserver/trafficValidate`). A new
  control-plane endpoint validates the request/response traffic MockServer has already recorded against a
  provided OpenAPI spec (URL, file path, or inline), returning a structured pass/fail report
  (`totalRequests` / `passed` / `failed` / `allPassed` plus per-request `matchedOperation`, `requestErrors`,
  and `responseErrors`) — mirroring the `/contractTest` report. The endpoint is gated by the same
  control-plane authentication as its siblings, and a spec URL is fetched only after passing the same SSRF
  policy enforced on proxy/forward paths.
- **Java client helpers for contract testing & Pact.** The Java `MockServerClient` now exposes fluent, typed
  methods for the contract-testing endpoints: `contractTest(spec, baseUrl[, operationId])`,
  `trafficValidate(spec)`, `pactImport(json)`, `pactExport(consumer, provider)`, and `pactVerify(json)`. The
  contract-test and traffic-validation reports parse into typed `ContractReport` / `ContractResult` objects so
  callers no longer hand-roll raw HTTP.
- **Per-import realistic example generation.** OpenAPI imports can now request realistic (Datafaker) example
  values for a single import via a `"realisticValues": true` entry in the reserved `__generationOptions__`
  map (alongside the existing `seed` and `fieldOverrides` options), without changing the global
  `generateRealisticExampleValues` configuration. When the entry is absent, behaviour is unchanged and the
  global default still applies.
#### Dashboard UI
- **New "MCP Health" dashboard panel.** When a coding-assistant CLI is proxied through MockServer, its MCP
  servers (e.g. `chrome-devtools`, `devbot`) are frequently the real latency bottleneck. The panel aggregates
  captured MCP (JSON-RPC) traffic per server and shows, worst-first, each server's call count, error count and
  rate (JSON-RPC errors or non-2xx responses), median / p95 / max latency, and its slowest method — with slow
  (≥5s) and erroring servers flagged — so it is obvious which MCP server is stalling, rather than guessing.
- **Anonymous, cookieless dashboard usage analytics (PostHog Cloud EU).** The dashboard reports coarse, enumerated usage events (`app_open`, `view_change`, `feature_used`, `error_shown`) to a cookieless, EU-hosted PostHog project to help improve the UI. No request URLs, hostnames, headers, bodies, or expectation data are ever sent, and no tracking cookie is set. The **official Docker images** ship with this enabled; it is **inactive in any build without `dashboardAnalyticsEndpoint` + `dashboardAnalyticsKey`** (so plain JARs/WARs and source/fork builds send nothing). Disable globally with `dashboardAnalyticsEnabled=false` (or `MOCKSERVER_DASHBOARD_ANALYTICS_ENABLED=false`); respects Do Not Track, Global Privacy Control, and a per-browser opt-out banner. See [dashboard privacy](https://www.mock-server.com/mock_server/dashboard_privacy.html).
- **Official binary launcher bundles now also report anonymous cookieless dashboard usage analytics**, joining the Docker images and Helm deployments. The plain downloadable JAR and any embedded/library/dependency use remain inert (no endpoint or key configured). Analytics events from all official artefacts now include a `distribution` label (from the new `dashboardAnalyticsDistribution` config property) identifying which artefact produced the event (`docker-standard`, `docker-graaljs`, `docker-clustered`, `helm`, or `binary`); values outside the closed allow-list are normalised to `unknown` — free text is never forwarded.
- **SLO verification dashboard panel.** A new dashboard view authors service-level objectives (latency
  p50/p95/p99, error-rate) and runs them against the existing `/mockserver/verifySLO` endpoint, showing
  observed-vs-threshold per objective and an overall PASS / FAIL / INCONCLUSIVE verdict.
- **Dashboard remembers where you were.** The active view and per-panel search/filter terms persist across
  reloads, and the view is reflected in the URL hash (e.g. `#/contract`) so views are linkable. A first visit
  still opens Get Started.
- **Dashboard search-operator hints.** The search box now advertises its operators (`status:>=400`,
  `method:POST`, `path:/api/*`, `/regex/`) via the placeholder and an accessible help tooltip.

#### Client libraries
- **All client libraries now expose the full load-scenario surface.** The Java, Node, Python, Ruby, Go,
  .NET, PHP, and Rust clients gained the new scenario fields (`thresholds`, `abortOnFail`, `abortGraceMillis`,
  `pacing`, `feeder`, `stepSelection`, per-step `captures`/`weight`, profile `shape`), the new run-status
  fields (`p999Millis`, `droppedIterations`, `verdict`, `abortedByThreshold`, `thresholdResults`), and three
  new methods — `getLoadScenarioReport` (with optional `junit` format), `generateLoadScenarioFromOpenAPI`,
  and `generateLoadScenarioFromRecording`.
- **Fluent `when().respond()` DSL in the Node client.** The Node client now offers a chainable
  `when(request).respond(response)` — plus `.forward()`, `.error()`, `.callback()`, and
  `.withTimes()` / `.withTimeToLive()` / `.withPriority()` builders — mirroring the Java client, alongside the
  existing procedural methods (which are unchanged).
- **Opt-in per-test reset for the JUnit 5 extension.** `@MockServerSettings(resetBeforeEach = true)` resets the
  shared MockServer before each test (matching the JUnit 4 rule and Spring listener). Default off, so existing
  behaviour is unchanged.

#### Clustering & observability
- **New `mock_server_forward_upstream_protocol` metric.** A Prometheus counter labeled by `upstream_host` and
  `protocol` records the protocol each forward/proxy connection actually negotiated to the upstream (`http2` via
  ALPN, or `http1_1`), with a matching DEBUG log. This is the authoritative way to confirm whether
  `forwardProxyHttp2Upgrade` is taking effect — the recorded request only carries the inbound protocol, not the
  upstream-negotiated one, so a forward stuck on `http1_1` to a backend that withholds its streaming SSE head
  over HTTP/1.1 (a cause of high forward time-to-first-byte) was previously invisible.
- **Standard OTLP endpoint fallback.** When `mockserver.otelEndpoint` / `MOCKSERVER_OTEL_ENDPOINT` is unset,
  MockServer now falls back to the OpenTelemetry-standard `OTEL_EXPORTER_OTLP_ENDPOINT` environment variable.
#### Proxy & TLS setup
- **New `forwardProxyHttp2Upgrade` setting (default off).** Forwards a secure request to the upstream over HTTP/2 even when the inbound client is HTTP/1.1 (ALPN-negotiated, automatic fallback to HTTP/1.1 if the upstream does not offer HTTP/2; TLS only). This fixes a header-timeout some streaming upstreams exhibit, where the Server-Sent Events response head is sent immediately over HTTP/2 but withheld over HTTP/1.1.
- **Copy-paste proxy setup at startup.** The new `mockserver.proxySetupLogging` property
  (env `MOCKSERVER_PROXY_SETUP_LOGGING`, default `false`; auto-enabled by the standalone JAR, Docker image,
  and `mockserver` CLI) writes the active CA certificate to `mockserver-ca.pem` in the dynamic-SSL directory
  at startup and prints a "Proxy Setup" block with ready-to-paste environment variable exports (`HTTPS_PROXY`,
  `NODE_EXTRA_CA_CERTS`, `SSL_CERT_FILE`, `REQUESTS_CA_BUNDLE`) for both Unix and Windows PowerShell. The
  block includes a security warning when the default public CA is in use. Embedded usage
  (`new ClientAndServer(...)`) stays silent by default to avoid polluting test output; when `proxySetupLogging`
  is off, the CA file is written on the first `GET /mockserver/proxyConfiguration` call instead. The endpoint
  itself is always available regardless of this setting.
- **`GET /mockserver/proxyConfiguration` endpoint.** Returns the CA certificate path, CA PEM, proxy address,
  environment variable exports, and a flag indicating whether the default public CA is in use. Responds with
  JSON by default or a plain copy-paste text block when called with `Accept: text/plain`. Never exposes the
  private key.
- **`--proxy-setup` flag for a unique, secure CA.** The new `--proxy-setup` CLI flag (property
  `mockserver.proxySetup`, env `MOCKSERVER_PROXY_SETUP`, default `false`) forces generation of a unique local
  CA on first startup, equivalent to `dynamicallyCreateCertificateAuthorityCertificate=true`. Recommended for
  any shared, persistent, or team-facing proxy deployment. Without it, MockServer uses the built-in default CA
  whose private key is published in the git repository (safe only for isolated local development).
- **Bounded-memory event log + disk capture for proxying LLM / large-body traffic without running out of
  memory.** Proxying large request/response bodies (LLM tool schemas, growing conversation context, accumulated
  SSE) previously retained every exchange in full in the in-memory event log, which is bounded only by entry
  *count* (`maxLogEntries`), never by size — so a long capture session could exhaust the heap and crash the
  proxy. Three new opt-in properties address this: `mockserver.maxEventLogSizeInBytes` (env
  `MOCKSERVER_MAX_EVENT_LOG_SIZE_IN_BYTES`, default `0` = disabled) caps the retained body bytes and evicts the
  oldest entries from memory once exceeded; `mockserver.persistRecordedRequestsToDisk` (env
  `MOCKSERVER_PERSIST_RECORDED_REQUESTS_TO_DISK`, default `false`) with `mockserver.persistedRecordedRequestsPath`
  (default `recordedRequests.ndjson`) appends every proxied exchange — full request and response — as one compact
  JSON object per line (NDJSON) to disk as it completes, flushed per line, so the complete session survives even
  as the in-memory window evicts; and `mockserver.maxLoggedBodyBytes` (env `MOCKSERVER_MAX_LOGGED_BODY_BYTES`,
  default `0` = unlimited) truncates bodies kept in memory beyond a byte limit (marking the copy with an
  `x-mockserver-body-truncated` header) without affecting the disk archive. The NDJSON archive honours
  `redactSecretsInLog`, masking credentials on disk exactly as the dashboard does. The recommended pairing —
  byte budget plus disk capture — keeps memory bounded while disk holds everything; the
  `mockserver-ui/scripts/launch-with-llm-capture.sh` capture launcher now enables it by default (2 GB heap,
  256 MB byte budget, NDJSON disk capture).

### Changed

- **TLS/decoder fault logs now name the SNI host.** When a client's TLS handshake through the proxy fails
  (e.g. `SSLHandshakeException: Received fatal alert: unknown_ca`, meaning the client does not trust
  MockServer's CA), the WARN log now appends the SNI hostname the connection was for — e.g.
  `… closing pipeline [id: 0x…] (SNI: chatgpt.com)` — across the relay, SOCKS, port-unification, binary-proxy,
  MCP and dashboard/websocket handlers, so the failing target/client is identifiable instead of anonymous. The
  message is unchanged when no SNI was negotiated.
- **Dashboard UI titles are now consistently Title Case.** Page/view headings, section headings, dialog
  titles, tab labels, navigation labels, and the tools/clear menu items now use Title Case throughout
  (e.g. "Server configuration" → "Server Configuration", "MCP server health" → "MCP Server Health"),
  so a menu item and the dialog it opens always match. Acronyms and brand names (MockServer, AsyncAPI,
  OpenAPI, gRPC, OIDC, SAML, SLO, CRUD, MCP, LLM, Pact) are preserved, and descriptive/help text, tooltips,
  and form labels are unchanged.
- **`generateFromRecording` in `TEMPLATIZED` mode now reproduces the recorded traffic mix.** Each generated
  step's `weight` is set to the route's observed hit count and the scenario uses `stepSelection: WEIGHTED`,
  so replaying picks routes in proportion to how often they appeared in the recording (instead of plain
  ordered steps). `VERBATIM` mode is unchanged.
- **Docker images cap the JVM heap at 75% of the container memory limit** (`-XX:MaxRAMPercentage=75.0`, in
  every published image that runs the server — standard, snapshot, root, root-snapshot, graaljs, local, and
  clustered), making memory use predictable and avoiding OOM-kills that looked
  like hangs. Always run with an explicit container memory limit. To set a fixed heap, pass an explicit `-Xmx`
  (a second `MaxRAMPercentage` via `JAVA_TOOL_OPTIONS` has no effect — it is applied before the image's flag).
  A build-time guard (`.buildkite/scripts/steps/docker-validate-sync.sh`) now fails the build if any
  server image's entrypoint is missing the cap, so it cannot drift back out of one variant.
  The Helm chart now ships commented `resources` and `app.jvmOptions` examples.
- **Generated TLS certificate validity extended to 10 years** (was 365 days) for the dynamically generated CA,
  leaf/server, and HTTP/3 self-signed certificates, so pinned-CA test setups no longer expire after a year.
- **Dashboard navigation reorganised into grouped menus.** The dashboard's views are now organised into six
  groups (Mock / Observe / Verify / Resilience / AI / Inspect) with submenus, replacing the flat overflow tab
  bar, so features are easier to discover.
- **The Trace view is now reachable from the AI menu as well as Observe.** Trace groups related requests —
  including LLM agent runs — so it is now listed under AI alongside LLM Optimise, while remaining under Observe,
  making it easier to find when debugging multi-step AI flows.
- **The Trace view collapses a multi-turn LLM conversation into one growing thread.** A stateless coding-assistant
  CLI (e.g. the OpenAI Codex backend used by `opencode`) resends its entire growing conversation/reasoning context
  on every turn, so consecutive recorded requests each "contained everything so far" and the view read as endless
  duplicates. Consecutive requests whose message history is a growing prefix of the next (same provider and host)
  now render as a single conversation showing each turn's *new* content, instead of N full-history blobs. Grouping
  is conservative (edited history, a different provider, or a different host never merge) and non-destructive — the
  raw per-request data is still reachable.
- **Expectation matching scales to large expectation sets.** A candidate index buckets literal
  `(method, exact-path)` expectations so a request evaluates only plausible candidates instead of scanning
  every expectation; non-literal matchers (regex/notted/optional/schema/path-param) are always checked, so
  matching is byte-for-byte unchanged. The index engages automatically only above a size threshold (default
  64, overridable via `-Dmockserver.candidateIndexThreshold`); small expectation sets run the unchanged
  linear scan, so there is no regression at small scale and a large speed-up at thousands of expectations.

### Fixed

#### Correctness & reliability
- **HTTP/2 clients through the forward/CONNECT proxy no longer hang when the upstream is also HTTP/2.** When a client
  connected to MockServer's HTTPS forward proxy over HTTP/2 and MockServer forwarded to an upstream that also served
  HTTP/2, Netty's inbound HTTP/2→HTTP adapter tagged the decoded upstream response with a synthetic
  `x-http2-stream-id` header carrying the *upstream* stream id. That internal header leaked through the response
  mappers and was re-emitted to the client, so the response was written on the wrong (upstream) stream id — the
  client's HTTP/2 codec rejected it with a `PROTOCOL_ERROR`/`GO_AWAY` and the request hung until timeout. The
  response mappers now strip the Netty `x-http2-*` extension-header family so the outbound stream id is governed
  solely by the inbound request's stream id. HTTP/1.1 clients and directly-mocked HTTP/2 responses were never
  affected; captured/recorded responses also no longer carry the internal `x-http2-stream-id` header.
- **Millisecond timeouts are now settable under their unit-bearing `…InMillis` names, fixing silently-ignored overrides.**
  The Java API (e.g. `Configuration.maxSocketTimeoutInMillis()`) and the `/mockserver/configuration` JSON expose these
  settings under `…InMillis` names, but the system property / environment variable were only read under the unit-less
  `mockserver.maxSocketTimeout` / `MOCKSERVER_MAX_SOCKET_TIMEOUT` form. Setting the natural
  `-Dmockserver.maxSocketTimeoutInMillis=…` (the name shown everywhere else) was therefore silently dropped and the
  20s default stood — long enough to 502 a healthy but slow first-byte response (e.g. a reasoning LLM backend that
  takes longer than 20s to emit its first token when proxied/forwarded). MockServer now also accepts the unit-bearing
  `mockserver.maxSocketTimeoutInMillis`, `mockserver.socketConnectionTimeoutInMillis` and
  `mockserver.maxFutureTimeoutInMillis` names (and their `MOCKSERVER_*_IN_MILLIS` environment-variable forms) as exact
  synonyms for the existing names — set whichever you prefer. The primary (unit-less) name is read first, so a value
  applied at runtime via the programmatic setter is never silently overridden by a launch-time alias.
- **Recorded streaming responses no longer pin the live streaming sink in memory.** Each captured streaming
  (SSE) forward/proxy exchange stored a log entry whose response still referenced the live streaming body — its
  in-memory capture buffer, the upstream event loop, and the per-chunk callbacks — for the entry's whole lifetime
  in the log ring buffer, roughly doubling per-entry memory and pinning event-loop-adjacent objects. The retained
  log copy now holds only the fixed captured bytes and releases the live streaming reference.
- **`forwardProxyHttp2Upgrade` now applies to every forward route, fixing slow streaming captures.** The
  HTTP/2-upgrade setting was honoured only by matched `forward()` expectations; it now also covers the
  transparent (unmatched) proxy path that most LLM/agent capture uses and the `proxyPassMappings` reverse-proxy
  route. Previously a coding-assistant CLI proxied over HTTP/1.1 was always forwarded upstream over HTTP/1.1, and
  some streaming backends (notably the OpenAI Codex SSE endpoint used by the `opencode` CLI) withhold the
  response head over HTTP/1.1 and only flush at completion, so time-to-first-byte collapsed to total time and
  surfaced as a client-side streaming timeout. With `forwardProxyHttp2Upgrade` enabled, a secure request on any
  forward route is now forwarded upstream over HTTP/2 via ALPN (falling back to HTTP/1.1 if the upstream
  declines), so the backend streams the head immediately. Off by default; only the opt-in flag with a secure
  (`https`) target triggers it.
- **Streamed responses with no `Content-Type` are no longer buffered, fixing a streaming header-timeout (notably
  for `opencode`).** MockServer previously relayed a response incrementally only when the upstream advertised
  `Content-Type: text/event-stream`; a backend that streams Server-Sent Events with no content-type at all —
  notably the OpenAI Codex endpoint used by `opencode` — was buffered to completion before any headers were sent,
  so the client failed with "Provider response headers timed out after 10000ms". Streaming is now driven by the
  **client's** streaming intent (an `Accept: text/event-stream` header or a `"stream": true` request body),
  propagated per request to both the forward path and the transparent (CONNECT) loopback relay, so the response
  head reaches the client immediately regardless of the upstream's content-type. Ordinary buffered responses
  (including chunked-without-`Content-Length` servlet responses) and `FORWARD_REPLACE` overrides are unaffected.
- **A stalled upstream on a reused pooled keep-alive connection now times out instead of hanging.** With the
  opt-in forward connection pool (`forwardConnectionPoolKeepAlive`) enabled, a pooled keep-alive connection
  carries no read timeout while it sits idle in the pool (a blanket one would fire during legitimate idle
  keep-alive). But a request dispatched on such a channel — a reused connection, or a fresh pooled channel's
  first request — was left with nothing to bound it, so an upstream that connected/kept-alive but then went
  silent hung the request until the far larger forward future timeout. An in-flight read timeout
  (`maxSocketTimeout`) is now armed when a request is dispatched on a pooled channel and removed again when the
  channel is returned to the pool, so a stalled reuse fails promptly. The default (pooling off) path is
  unchanged.
- **A streamed response is bounded by the streaming idle timeout, not the 20s socket read timeout.** When a
  response switches to streaming, the per-request socket read timeout (`maxSocketTimeout`, default 20s) is now
  always replaced by the stream-appropriate idle bound (`streamIdleTimeoutSeconds`, default 60s), so a streaming
  LLM response that pauses longer than 20s between chunks (model reasoning) is not cut off mid-stream. Setting
  `streamIdleTimeoutSeconds=0` now genuinely runs the stream unbounded as documented (previously the 20s socket
  timeout was left armed, truncating long inter-chunk gaps). The default (60s) is unchanged.
- **Large `PUT /mockserver/retrieve` and the LLM optimisation report no longer stall logging or time out.**
  Retrieving logs, requests, recorded expectations, or request-responses serialized the (potentially large, e.g.
  captured streaming bodies) result *inside* the single log-consumer thread's callback, which could exceed the
  retrieve future timeout and — worse — block all further logging (filling the ring buffer and dropping events)
  while it ran. Every retrieve branch — `LOGS`, `REQUESTS`, `RECORDED_EXPECTATIONS`, and `REQUEST_RESPONSES` in
  all its formats (JSON, log entries, HAR, OpenAPI, Postman, Bruno, cURL) — now materializes the (cheap, redacted)
  result on the consumer thread and runs the expensive serialization on the caller thread; the LLM
  optimisation-report endpoint likewise builds its report off the Netty event loop. Output is byte-for-byte
  identical; only the thread doing the work changed.
- **Load-scenario status no longer reports a transient `null` while a run is completing.** The orchestrator
  removed a finishing run from its active map before publishing the run's terminal status, so a status poll
  landing in that brief window saw neither and returned `null`. The terminal status is now published before the
  run is de-registered, so `statusFor`/`getStatus` always observe either the live or the completed status.
- **SSL/decoder faults in the proxy/relay handlers are now logged at WARN** instead of being silently dropped,
  so genuine TLS/decoder problems are visible without the noise of benign connection closes.
- **LLM streaming pacing above 1000 tokens/sec is preserved.** Sub-millisecond per-token delays were
  integer-truncated to 0 ms (flattening fast streams); pacing now accumulates with fractional carry so
  cumulative timing stays accurate.
- **Coding-assistant LLM traffic is recognised resiliently, including opencode's OpenAI Codex backend.** The
  `opencode` CLI calls the OpenAI Responses API through its Codex backend at
  `chatgpt.com/backend-api/codex/responses`, a non-standard path the detectors did not match — so its calls were
  recorded under the generic Traffic view but never appeared in the LLM Traces or LLM Optimise views. Responses-API
  detection (`LlmProviderSniffer`, `ProviderDetector`, and the dashboard's `llmTraffic.ts`) now matches the Codex
  path alongside the hosted `/v1/responses`, and the `chatgpt.com` host on it. Detection also gains a host/path-
  independent **body-shape fallback** (read-only analysis only — Traffic, LLM Traces, LLM Optimise; never the live
  forward/cost path): LLM traffic is recognised from its wire format, so a coding assistant that moves to a new
  endpoint or a private gateway, or a new tool, stays classified without a code change. Claude Code (`/v1/messages`)
  and Tabnine CLI (`…/chat/completions`) were already recognised.
- **A streamed proxy response with no `Content-Type` is logged as readable text, not empty binary.** The captured
  body of a streamed forward response with no content-type (opencode's OpenAI Codex SSE backend) was stored as a
  `BINARY` body, so it appeared empty in the dashboard's LLM Traces / Optimise text views. The captured bytes are
  now sniffed when no content-type is present — UTF-8 text (SSE/JSON) is stored as a readable `STRING`, while
  genuinely binary streams stay `BINARY`. Content-typed responses are unchanged.
- **HTTP/2 forwarded responses now stream incrementally instead of being buffered.** The HTTP/2 forward client was
  rebuilt on the same multiplex stack the server uses (`Http2FrameCodec` + `Http2MultiplexHandler`), reusing the
  existing HTTP/1.1 streaming relay per stream — a streamed upstream response (SSE) now has its head relayed to the
  client as soon as it arrives rather than after the whole body. Non-streaming HTTP/2 responses are still aggregated.
- **More consistent LLM provider detection across the proxy, traces and optimise views.** Embeddings/moderations
  requests are no longer mis-classified as the OpenAI Responses API; the MCP `provider=AUTO` analysis now uses the
  same host + body-shape detection as the dashboard and optimisation report; and Cohere, Voyage, Vertex AI Gemini,
  and the AWS Bedrock Converse API are now recognised.
- **The LLM optimisation report classifies and prices calls more honestly.** It now uses the response body when
  detecting the provider (a header-less Anthropic call is no longer mis-labelled OpenAI), and a call whose model
  has no known price — or only a placeholder rate — is flagged as unpriced/approximate instead of being shown as a
  confident `$0.00`. The copy-paste optimisation brief also masks obvious credential shapes in prompt text.
- **The dashboard renders more LLM responses correctly.** Streamed OpenAI Chat Completions and Gemini responses
  that carry no `Content-Type` header now reassemble and display instead of showing empty; Anthropic prompt-cache
  tokens are surfaced; a hostile/malformed Server-Sent Events index can no longer exhaust browser memory; and a
  truncated or unparseable response body now shows a clear notice rather than a silent blank.
- **Captured credentials are masked in the dashboard.** API keys and bearer tokens in `Authorization`, `x-api-key`,
  `api-key`, cookies and similar headers are masked in the Traffic raw/diff views (the original value is still used
  for replay), so a shared or screen-shared dashboard no longer exposes live credentials.
- **Forward DNS resolution moved off the calling thread.** Forward actions hand the connect path an unresolved
  address so DNS runs on the Netty event loop; SSRF validation still resolves and rejects private/loopback
  targets first, and a missing SSRF guard was added to the forward-validate path.
- **Code-review hardening sweep — correctness, concurrency, resources and performance.** A repo-wide review
  surfaced and fixed a set of latent defects:
  - **Stale `hashCode` broke matching.** `KeyToMultiValue.replaceValues()`/`addNottableValues()` mutated the
    value list without refreshing the cached `hashCode` (unlike `addValue()`), so a header/parameter object
    reused on the matching hot path (e.g. via `ExpandedParameterDecoder`) could violate the `equals`/`hashCode`
    contract. The cache is now refreshed on every mutation, and the `0`-sentinel hashCode caches on
    `HttpRequest`/`HttpResponse`/`Action`/`Not` no longer defeat themselves when a hash legitimately computes to 0.
  - **`NullPointerException` serialising a chunked response with no body** — the chunked body encoder now guards a
    null body.
  - **WebSocket object-callback disconnect bug.** When a callback client disconnected mid-exchange the
    forward-object-callback handler wrote the HTTP response twice and left a `CompletableFuture` that never
    completed (pinning a scheduler thread until the future timeout); the disconnect path now writes once,
    unregisters the callback, and returns. Response/forward callback registry entries are also unregistered on
    every disconnect branch.
  - **JavaScript response templates were fully serialised** through an engine-wide lock even though each call
    already builds its own GraalVM context; the lock was removed so concurrent JS templates run in parallel.
  - **Numerous unsynchronised lazy-init / check-then-act races hardened** (template-engine and body-deserializer
    `ObjectMapper`s, the OpenAPI parse cache via `computeIfAbsent`, `JsonStringMatcher`, the Java client's Netty
    client and event bus, action-handler template engines, `LogEntry` override cache, scheduler thread numbering).
  - **Configuration round-trip gaps.** `controlPlaneScopeMapping`, the proxy-pass mappings, and
    `proxyRemoteHost`/`proxyRemotePort` now round-trip through `PUT /mockserver/config`; an unrecognised
    `logLevel` now fails fast with a clear message instead of an NPE during start-up; the conventional
    `mockserver.perExpectationMetricsEnabled` property key is accepted (the legacy key still works).
  - **Event loop no longer blocked.** Connection-delay sleeps and `awaitUninterruptibly()` calls were removed
    from the proxy/SOCKS/relay event-loop paths; the outbound HTTP client now applies a read timeout so a
    stalled upstream cannot pin a connection/future indefinitely; CONNECT-relay aggregators are bounded to the
    configured maximum body size instead of ~2 GB.
  - **Resource & memory leaks fixed.** `MemoryMonitoring` now unregisters its log/expectation listeners on stop
    (and writes its CSV via try-with-resources); the LLM completion cache and quota registry are now bounded;
    gRPC gzip frames are capped on *decompressed* size (decompression-bomb guard).
  - **Async broker mocking** publish/subscribe lifecycle is synchronised, Kafka send failures are logged, and
    subscribers expose a health flag after a broker disconnect.
  - **Clustered in-memory CAS** no longer loses a concurrent write when an entry is swapped under the same key
    (identity-conditional remove/replace).
  - **Hot-path allocations removed** (case-insensitive header/parameter lookups, matcher-listener notification,
    load-metric label arrays), and generated TLS certificates are now anchored to issuance time rather than the
    JVM start time.
  - **Control-plane endpoints can no longer be hijacked by an early (`respondBeforeBody`) expectation.** A
    catch-all `respondBeforeBody` expectation (for example one seeded from an initialization file) was matched
    before the control-plane dispatch, so it could answer the server's own management requests (e.g.
    `PUT /mockserver/reset`). Early header matching now excludes the reserved `/mockserver` control-plane path
    prefix, so management endpoints always reach the control plane.

#### Dashboard UI
- **Dashboard LLM pricing corrected.** The dashboard cost estimates were ~1 year stale and up to ~3× too high
  (e.g. Opus 4.8 shown at 15/75 instead of 5/25); the table is now synced to the server's pricing and guarded
  by a drift test.

#### IDE extensions (VS Code & JetBrains)
- **JetBrains plugin no longer uses internal/deprecated IntelliJ Platform APIs.** A blocking IntelliJ Plugin
  Verifier gate now runs in CI against the full recommended IDE set (IntelliJ IDEA 2024.3 through the 2026.2 EAP)
  and rejects internal, deprecated, and scheduled-for-removal API usages — the same classes the Marketplace
  flags. The plugin's self-version lookup is resolved from its own plugin class loader
  (`PluginAwareClassLoader.pluginDescriptor.version`), because the id-based `PluginManager.getPluginByClass(...)` /
  `PluginManagerCore.getPlugin(PluginId)` lookups are both marked internal on newer platforms; the tool-window
  buttons fire their actions via the stable `AnActionEvent.createEvent(...)` + `update`/`actionPerformed`
  primitives instead of the deprecated `ActionUtil.invokeAction(...)`; and the deprecated `JBCefBrowser(...)`
  constructors use the `JBCefBrowser.createBuilder()...build()` API. No behaviour change; keeps the plugin
  installable on current and future IDE builds.

#### OpenAPI & contract testing
- **OpenAPI `format: date`/`date-time` examples render as ISO strings again** ([#2370](https://github.com/mock-server/mockserver-monorepo/issues/2370)).
  An inline `example: '2021-01-30'` on a `type: string, format: date` property was serialised in generated
  responses as epoch-millis (`1611964800000`) instead of the ISO string, because swagger-parser deserialises
  the example into a `java.util.Date` that the explicit-example path handed straight to Jackson. Date/date-time
  examples are now normalised back to their schema string form before serialisation (regression since 6.0.0).

#### Client libraries & integrations
- **Spring `@MockServerTest` works with JUnit 5 `@Nested` classes again** ([#2371](https://github.com/mock-server/mockserver-monorepo/issues/2371)).
  Injecting the `MockServerClient` declared on an outer test class into a `@Nested` inner test instance threw
  `IllegalArgumentException` because the field was set on the inner instance rather than the enclosing instance
  that declares it. Injection now resolves the correct enclosing instance via the synthetic outer reference
  (regression since 6.0.0).

#### Build & dependencies
- **`mockserver-core` no longer triggers dependency-convergence errors in downstream builds**
  ([#1970](https://github.com/mock-server/mockserver-monorepo/issues/1970)). Projects that depend on
  `mockserver-core` and run `maven-enforcer`'s `dependencyConvergence` rule saw conflicts for guava, jsr305,
  rhino, libphonenumber, snakeyaml, commons-*, slf4j-api, jackson-* and jakarta.xml.bind-api, because those
  versions are pinned in MockServer's parent `dependencyManagement` (which is not transitive) while
  swagger-parser, json-patch, velocity and protobuf-java-util dragged in older transitive copies. The stale
  transitive edges are now pruned with `<exclusion>`s (the resolved classpath is unchanged — the pinned/newer
  versions already won nearest-wins), and `jackson-dataformat-yaml` and `jsr305` are declared directly so a
  single version of each reaches consumers. (The `mockserver-client-java` half of this was fixed in 7.1.0.)

## [7.2.0] - 2026-06-22

### Security

- **Control-plane role-based authorization** (off by default). With `controlPlaneAuthorizationEnabled`
  and a `controlPlaneScopeMapping` (e.g. `platform-admins=admin,qa-team=mutate,viewers=read`), an
  authenticated principal's scopes/groups are mapped to one of three hierarchical roles
  (`admin` ⊇ `mutate` ⊇ `read`): reads require `read`, every mutating operation requires `mutate`, and a
  principal lacking the role gets `403 Forbidden` (recorded in the audit log). Fail-closed — use together
  with control-plane OIDC authentication. Covers all `HttpState.handle` operations plus the Netty-serviced
  `/mockserver/configuration`, `/openapi.yaml` and `/llm/optimisationReport` reads/writes. Not yet covered:
  the lifecycle endpoints (`/bind`, `/stop`, `/status`) and per-tool MCP authorization. See
  `docs/code/tls-and-security.md`.
- **JWT control-plane validation rejects HMAC algorithms.** `JWTValidator` verifies against a public-key
  JWK set, so it now accepts only asymmetric algorithms (`RS*`/`ES*`/`PS*`/`EdDSA`) and rejects HMAC
  (`HS256/384/512`), closing an algorithm-confusion forgery vector. Switch to an asymmetric key if you
  relied on HMAC.
- **SCIM bearer-token enforcement now fails closed.** When enforcement is enabled but no expected token is
  configured, requests are rejected instead of accepting any token, and the comparison is constant-time.
- **Opt-in secret redaction in the event log and dashboard** (`redactSecretsInLog`, default off). Masks
  sensitive header values (`Authorization`, `Cookie`, `x-api-key`, …) and configured JSON body fields in
  retrieved/exported logs and the dashboard event view. Matching and verification still see the original
  values, so behaviour is unchanged.
- **Dashboard `dompurify` pinned to `3.4.11`** via an npm `overrides` entry, clearing all 16 open
  Dependabot DOMPurify advisories (mXSS / DOM-clobbering / prototype-pollution).


### Added

#### AI, LLM & agent protocols (LLM / MCP / A2A)
- **LLM and MCP mock builders in every client.** Idiomatic LLM-mocking (completions, tool calls, streaming
  physics, usage, embeddings, multi-turn conversations, provider failover) and MCP-server-mocking (tools,
  resources, prompts over JSON-RPC 2.0) builders are now available in all eight clients (Java, Node, Python,
  Ruby, Go, Rust, .NET, PHP), all producing the same wire JSON.
- **LLM optimisation export.** Proxy your agent's LLM calls through MockServer, then export a one-click
  optimisation brief (Markdown) or structured JSON bundle (`LlmOptimisationReport`) from captured traffic.
  Nine deterministic signals detect repeated system prompts, low cache-hit rates, unused tool schema,
  model overspend, large resent context, deterministic tool calls, oversized tool results, output-token
  bloat and duplicate calls — each with token counts, estimated USD saving, and structured fix guidance
  (copy-paste config snippet or example expectation where applicable). An in-product **verdict** (A–F grade
  and "$X recoverable" headline computed via per-call MAX attribution so the total is always ≤ actual spend)
  and two new session KPIs (**cache-hit rate** and **one-shot rate**) appear in the dashboard and the
  Markdown brief. New **LLM Optimise** dashboard screen (with verdict banner, "Copy verdict" button, and
  updated hero cards), `GET /mockserver/llm/optimisationReport` endpoint, and `export_optimisation_report`
  MCP tool. Export-only and deterministic; secrets are redacted. The Anthropic codec now maps the top-level
  `system` field so cache and repeated-prompt signals fire on Anthropic traffic.
- **More embedding providers and rerank mocking.** `httpLlmResponse` embeddings now cover Gemini, Ollama and
  Bedrock (Titan / Cohere-on-Bedrock) in addition to OpenAI/Azure, all deterministic and L2-normalised. A new
  rerank action mocks Cohere and Voyage rerank endpoints in the provider-correct envelope.
- **MockServer's MCP control plane gains `prompts/list`, `prompts/get` and `sampling/createMessage`** over
  HTTP/1.1, HTTP/2 and HTTP/3, configured via a new `McpPromptRegistry`.
- **A2A mock builder: streaming and push notifications** (opt-in). `withStreaming()` generates an SSE stream
  of task status/artifact events; `withPushNotifications(webhookUrl)` POSTs each completed task to a webhook.
- **Strict structured-output enforcement** (`enforceOutputSchema`, opt-in). A mocked completion whose body
  doesn't conform to its `outputSchema` fails loudly (`502` + diagnostic header) instead of returning the
  non-conforming body — modelling a real provider's strict `json_schema` mode. Checked before streaming begins.
- **Provider-correct LLM chaos error bodies.** Error injection emits each provider's real error shape
  (Anthropic `overloaded_error`, OpenAI `server_error`/`rate_limit_exceeded`, Gemini, Ollama) so SDK
  retry/backoff can be tested realistically. An optional `errorKind` (`OVERLOAD` / `RATE_LIMIT` /
  `SERVER_ERROR`) emits the provider's distinct body and natural HTTP status without picking the code yourself.
- **Multimodal request recognition.** Conversation decoders recognise image content parts (OpenAI `image_url`,
  Anthropic `image`, Gemini `inline_data`) and audio parts (OpenAI `input_audio`), so a request matcher can
  assert on image/audio presence; `ParsedMessage` exposes `hasImage()`/`hasAudio()` etc. A new response-side
  `toolChoice` field (`auto`/`none`/`required`/named) drives `finish_reason`. Request recognition only —
  MockServer does not store the bytes.
- **Cached / reasoning token usage fields.** `Usage` gains optional `cachedInputTokens`,
  `cacheCreationTokens` and `reasoningTokens`, decoded from each provider's usage shape and emitted on GenAI
  telemetry spans, so cost dashboards can split cached-input and reasoning spend.
- **LLM model/pricing catalog refresh** — current Claude (Opus 4.5–4.8, Sonnet 4.5/4.6, Haiku 4.5, Fable 5),
  OpenAI (gpt-4.1, o3/o4) and Gemini 2.5 families, with most-specific-prefix matching. `gpt-5*` entries are
  flagged placeholders — confirm against the provider price list.
- **Approximate token-count utility and opt-in usage inference** (`llmInferUsageEnabled`, default off). A
  mocked completion that omits `usage` can be auto-populated with estimated token counts (documented as an
  estimate, not a real BPE tokenizer); existing responses are unchanged.
- **AMQP 0.9.1 (RabbitMQ) broker mocking** in the AsyncAPI module, alongside the existing Kafka and MQTT
  support (configure via `asyncAmqpUri`).
- **Agent framework recipes** (docs): a new `ai_agent_frameworks.html` page with recipes for pointing
  LlamaIndex and the OpenAI Agents SDK at MockServer to mock LLM provider calls.

#### Identity provider mocking (OIDC / OAuth2 / SAML / SCIM)
- **One-call mock OIDC / OAuth2 provider.** `PUT /mockserver/oidc` (or `mockOpenIdProvider()`) stands up a
  complete IdP — discovery, JWKS, token, authorize, userinfo, introspection, revocation, logout — with the
  full OAuth2 authorization-code flow (PKCE S256/plain), client-credentials, refresh-token, and the device
  authorization grant (RFC 8628). Tokens are minted at request time (correct `nonce`/`at_hash`, `id_token`
  split from `access_token`); signing is configurable (RS/ES 256/384/512). Optional token-endpoint client
  authentication (`enforceClientAuthentication`) and opaque access tokens with working `/introspect`.
- **Verified OIDC bearer authentication for the control plane** (`controlPlaneOidcAuthenticationRequired`,
  off by default). Verifies the `Authorization: Bearer` token against an external IdP's JWK set (direct or
  discovered), asserting issuer, audience, `exp`/`nbf` and required scopes, and records the verified `sub`
  as the audit principal. Combinable with mTLS and JWT control-plane auth.
- **One-call mock SAML 2.0 IdP.** `PUT /mockserver/saml` stands up a mock IdP (metadata + SP-initiated POST
  SSO) returning an XML-DSig-signed assertion with configurable subject/attributes. Configurable signing
  algorithm (RS/ES 256/384/512), Single Logout, and negative-test flags (`expiredAssertion`, `wrongAudience`,
  `tamperedSignature`) to exercise an SP's rejection paths. Typed `mockSamlProvider(...)` Java API; inbound
  parsing is XXE-hardened.
- **One-call mock SCIM 2.0 provider.** `PUT /mockserver/scim` (or `mockScimProvider(...)`) generates an
  in-memory SCIM provider: CRUD over `Users`/`Groups`, discovery documents, `application/scim+json` shapes,
  single-attribute filtering (`eq`/`co`/`sw`/`pr`), `PatchOp`, pagination, an optional bearer-token gate and
  configurable base path/seed data.

#### Load injection, chaos & SRE
- **API-driven load generation via Load Scenarios** (`loadGenerationEnabled`, off by default). A named,
  registry-based control plane (`PUT/GET/DELETE /mockserver/loadScenario`, `/start`, `/stop`) drives outbound
  traffic at a target: load a scenario by name, then trigger one or many to run **concurrently**, each with
  its own `startDelayMillis`. A scenario is a list of request steps (template-rendered per iteration with an
  `iteration` context) with per-step think-time and a `profile` of ordered **stages** — closed-model VU
  stages, open-model arrival-rate (iterations/sec) stages with `LINEAR`/`EXPONENTIAL`/`QUADRATIC` ramp curves,
  and pauses — composing step/spike/soak/stress shapes. Scenarios can be preloaded at startup
  (`loadScenarioInitializationJsonPath`). Bounded by hard caps on VUs, rate, stages and concurrent scenarios.
  Full registry API and runnable examples in all eight clients.
- **First-class load-injection metrics** (Prometheus + OTEL). A load run exposes a dedicated
  `mock_server_load_*` family — request duration histogram (with `trace_id` exemplars), iterations, bytes,
  throttles, errors-by-kind, and live `active_vus`/`inflight` gauges — labelled by
  `scenario, run_id, step, route, method, status_class` (with auto-templatized low-cardinality routes and
  opt-in custom labels). Zero-cost when metrics are off; `mock_server_forward_*` is unchanged.
- **SLO resilience verdicts** (`sloTrackingEnabled`, off by default). A windowed sample store records latency
  and error per forwarded round-trip; `PUT /mockserver/verifySLO` evaluates latency-percentile and error-rate
  objectives and returns a structured verdict (`200` PASS / `406` FAIL / `400` malformed). Pairs with chaos:
  drive faults, then assert the system stayed within objectives.
- **Connection-lifecycle fault injection and preemption simulation.** The per-host TCP chaos profile gains
  mid-response RST, jittered slow-close and HTTP/2 GOAWAY faults. A new `PUT/GET/DELETE /mockserver/preemption`
  simulates a Kubernetes rolling-update / spot-reclaim drain — cordoning new exchanges, reporting in-flight
  count, and auto-uncordoning after a TTL — without stopping the JVM.
- **Saved chaos profile library.** Save/apply/list/delete chaos experiments by name
  (`/mockserver/chaosExperiment/profiles/{name}`, `/apply/{name}`). Profiles persist in the `StateBackend`,
  so they survive a reset and replicate across a cluster. The dashboard Chaos panel gains a Saved Profiles list.
- **Scheduled chaos experiment start.** A chaos experiment can carry `startDelayMillis` (fixed delay) and/or
  `cronSchedule` (5-field cron, JVM time zone, minute granularity); it sits in a `scheduled` status until the
  scheduled time. No scheduling fields = immediate start (unchanged).
- **General-purpose rate limiting** (`rateLimit` expectation clause, off by default). A protocol-agnostic
  clause returns a deterministic `429` with `Retry-After` and `X-RateLimit-*` headers once a matched
  expectation exceeds its rate, via `fixed_window` or `token_bucket` algorithms, with an optional named shared
  counter — so a test can exercise client backoff without a chaos profile.
- **Retry/backoff recovery primitive** (`recoverAfter` on `httpResponse`, opt-in). Returns a failure response
  (default `503`) for the first `failTimes` matches and then the success response, so a test can deterministically
  exercise client retry/backoff. An optional `idempotencyHeader` scopes the counter per request-header value.
- **Stream-level error injection** (HTTP/2 / HTTP/3). `httpError().withStreamError(...)` resets a matched
  request stream with a given error code (HTTP/2 `RST_STREAM`, HTTP/3 `RESET_STREAM`) without affecting other
  multiplexed streams; HTTP/1.1 falls back to dropping the connection. Also on the Node, Python and Ruby clients.
- **Conditional breakpoints.** Breakpoint matchers accept `skipCount` (pause only after N matching hits) and,
  on the RESPONSE phase, `responseStatusCodeMin`/`Max` and `responseBodyContains` so a breakpoint can pause
  only on, e.g., `5xx` responses or a body containing a particular message.

#### Request matching & response generation
- **Per-expectation hit-count response branching** (`SWITCH` response mode + optional `switchAfter`). With an
  index-aligned `httpResponses` list, an expectation serves the first response for its first `N` matches then
  advances — ideal for "succeed, then start failing" on a single endpoint without a full scenario.
- **Weighted/probabilistic response selection** (`WEIGHTED` response mode + `responseWeights`, e.g. `[90, 10]`).
- **Generate a schema-valid response body from an inline JSON Schema** (`generateFromSchema`). Synthesises a
  schema-valid body at response time, reusing the OpenAPI example engine; fires only when the response has no
  explicit body.
- **Regex path capture groups exposed to templates** via `request.pathGroups` (numbered) and
  `request.namedPathGroups`, usable from Mustache, Velocity and JavaScript.
- **Request-driven (template) response delay** — a `delay` may carry a `template`+`templateType` rendered
  against the request, so e.g. larger payloads respond slower.
- **Conditional (if-then-else) request matcher** (`conditionalRequestDefinition` with `if`/`then`/`else`).
- **Accept-header content-negotiation matching** — an opt-in `accept:<media-type>` header-matcher directive
  matches per RFC 7231 (q-weights, wildcards, specificity).
- **Conditional and chainable response modifiers** — a forward/override modifier may carry a `condition`
  (status code / range / header presence) and/or an ordered `modifiers` chain where each sees the previous output.
- **Deterministic fuzzy body matcher** (`FuzzyBody`) — matches when the request body is similar enough to an
  expected string by Jaro-Winkler ratio at or above a configurable threshold (a non-LLM similarity match).
- **Case-sensitive matching opt-in** (`matchExactCase`, default off). When enabled, method, path and regex
  string-body matching become case-sensitive; header/cookie/query matching always stays case-insensitive.
- **Default response headers** (`defaultResponseHeaders`) — stamp organisation-wide headers (`Server`,
  trace id, …) onto every response (mock, forwarded, proxied), applied add-if-absent.
- **Match and verify by negotiated protocol** (HTTP/1.1, HTTP/2, HTTP/3). `withProtocol(...)` on an
  expectation or `verify(...)` matches/asserts on the protocol a request arrived over; the new `HTTP_3` value
  (experimental) is server-trusted via the `h3` ALPN identifier, and protocol now round-trips through
  recorded requests.
- **HTTP response trailers** — `httpResponse().withTrailers(...)` emits protocol-appropriate trailing headers
  (chunked + `Trailer` on HTTP/1.1, a trailing HEADERS frame on HTTP/2/3). gRPC responses are unaffected.
- **Expectation namespacing / multi-tenancy** — an optional `namespace` field plus a configurable match header
  (`matchNamespaceHeader`, default `X-MockServer-Namespace`) lets teams share one instance without colliding;
  scoped `clear`/`retrieve` and Java `clearByNamespace`/`retrieveActiveExpectations(...)`.
- **`multipart/form-data` request-body matching** (`MultipartBody`) — match individual parts by field
  name/value, filename and content-type; OpenAPI multipart bodies build field matchers from the schema.
- **Numeric comparison operators** (`> 60`, `>= 60`, `< 100`, `<= 30`, `== 5`, `!== 5`) for header, cookie and
  query-string values.
- **Declarative `capture` rules and scenario-state templates.** A `capture` rule extracts a value from the
  matched request (jsonPath/xpath/header/query/cookie/pathParameter) into scenario state; templates can read
  and write scenario state via a `scenario` helper — enabling auth→resource→confirm journeys.
- **New response-template helpers** — `crypto` (md5/sha1/sha256/sha512/hmacSha256), `regex`
  (matches/replaceAll/group), `html`, `csv`, `xpath` (XXE-hardened) and `yaml`, plus `jsonPath`/`xPath`
  request-body extraction now in the Velocity and JavaScript engines (previously Mustache only).

#### Proxying, forwarding & recording
- **Upstream forward retry policy and per-upstream circuit breaker** (opt-in, off by default). Retry
  re-issues idempotent (GET/HEAD/OPTIONS/PUT/DELETE/TRACE) calls on a connection error or 502/503/504 with
  linear back-off; the circuit breaker trips open (fail-fast `503`) after N consecutive failures to a
  `host:port`, then half-opens. Open upstreams export `mock_server_upstream_circuit_open` when metrics are on.
- **Upstream connection pooling** (`forwardConnectionPoolEnabled`, default `true`). Idle HTTP/1.1 keep-alive
  upstream connections are pooled and reused, eliminating per-request TCP/TLS handshakes and the ephemeral-port
  exhaustion that caused request errors under sustained forward load (a k6 baseline of 21%/68% errors at
  750/1500 rps dropped to ~0%). Safe by default: the forward client runs on its own event-loop group (no
  self-deadlock in synchronous local callbacks) and a channel is only pooled when its codec is genuinely
  quiescent. Only plain HTTP/1.1 keep-alive is pooled — HTTP/2, HTTP/3, binary, streaming, tunnelled and
  `Connection: close` connections always use a fresh connection. Set to `false` to restore the old behaviour.
- **One-command record round-trip.** `GET/PUT /mockserver/retrieve?type=RECORDED_EXPECTATIONS&format=...` now
  accepts `forwardUnmatchedTo=<upstream>`, arming record-and-forward of unmatched requests and returning the
  recorded expectations (in any supported language/JSON) in one call — removing the multi-step proxy setup.
  The upstream is SSRF-validated before any state is mutated.
- **JSON Patch / JSON Merge Patch on forwarded responses.** A response modifier may carry an inline `jsonPatch`
  (RFC 6902) and/or `jsonMergePatch` (RFC 7386) applied to a forwarded/proxied JSON response body, so one field
  of a real upstream response can be changed without replacing the whole body. `jsonPatch` runs first; a
  non-JSON body or failed patch leaves the body unchanged.
- **Redact secrets in recorded traffic.** `redactSecretsInRecordedExpectations` (off by default) masks
  sensitive request headers when recorded expectations are retrieved, generated as code, or persisted; HAR and
  Postman imports redact sensitive headers and common secret body fields by default. Redaction preserves
  `times`/`timeToLive`/`priority`/`id` so recordings still replay.
- **Smart deduplication and templatization of recorded traffic.** Collapse many recorded requests that differ
  only by an id segment (`/users/123`, `/users/456`) into one `/users/{id}` expectation and drop exact
  duplicates. With `templatizeRecordedValues` (opt-in), volatile query/header/JSON-body values (UUIDs, ids,
  dates, JWTs, opaque tokens) are also generalized into matchers, while stable values are kept verbatim.
- **Baseline traffic drift comparison.** `PUT /mockserver/baseline/compare` diffs current recorded
  interactions against a saved baseline and returns a structured added/removed/changed report (value-insensitive
  JSON-shape comparison), usable from CI.

#### Verification
- **Timeout-aware verification** (Java client). `verify(..., Duration timeout)` polls until the verification
  passes or times out (for async / fire-and-forget code), and `verifyNever(..., Duration window)` asserts a
  condition stays unmet for the whole window. Implemented client-side; existing snapshot `verify(...)` is unchanged.
- **Soft/collecting verification and verify-by-disposition.** `verifyAll(...)` runs every supplied
  verification and throws one error listing all mismatches instead of failing on the first.
  `Verification.withDisposition(FORWARDED | MOCKED)` narrows a count to requests that were forwarded vs matched
  a mock.
- **Response verification: status-code range / operator matching** — a response template may match by class
  range (`statusCodeRange: "2XX"`) or operator (`">= 400"`); verification-only, never written to the wire.
- **Field-level closest-match diff for failures.** When `detailedVerificationFailures` is enabled (default),
  a failed sequence verification — and response verification — now appends a per-step "closest match diff"
  naming the fields that differ. Response reason-phrase matching honours `matchExactCase`, and response cookies
  use the same sub-set/notted semantics as the request side. Diagnostic only; pass/fail is unchanged.

#### OpenAPI & contract testing
- **Opt-in OpenAPI request validation during mock matching** (`validateRequestsAgainstOpenApiSpec`, off by
  default). A request matched by a spec-backed expectation is validated against that spec before the action is
  dispatched; a violation is rejected with `400` and an `OPENAPI_REQUEST_VALIDATION_FAILED` event. Previously
  validation only ran on the proxy/forward path.
- **OpenAPI contract testing endpoint** (`PUT /mockserver/contractTest`). Runs a spec as contract tests against
  a live service: builds a representative request per operation, sends it (with the same SSRF protection as
  forwarding), validates the response, and returns a pass/fail-per-operation report. Optional `operationId`
  restricts the run.
- **Enforce OpenAPI response validation for mocks** (`enforceResponseValidationForMocks`, off by default). When
  enabled alongside response validation, a mock response that fails validation is replaced with a `502`,
  matching the proxy-path enforcement; default stays advisory-only.
- **Pact provider-state preconditions and v3 import.** Pact `providerState(s)` round-trip on import/verify/export
  and map onto a MockServer scenario, so an imported interaction only matches once its state is active.
  `PUT /mockserver/import?format=pact` (or `/pact/import`) imports Pact v3 consumer contracts as expectations.
- **Deterministic OpenAPI example generation** — an optional reproducibility seed and per-field value overrides
  via a reserved `__generationOptions__` entry in the operations map.
- **Auth in generated Postman & Bruno collections** — the collection generator now emits collection-level auth
  (bearer / API key / basic from `securitySchemes`, else a placeholder JWT bearer) with blank placeholder
  credentials, so the collections still work against an unauthenticated MockServer.

#### gRPC & GraphQL
- **GraphQL and AsyncAPI spec import.** `PUT /mockserver/graphql` imports SDL / introspection and generates
  schema-valid expectations per root operation; `PUT /mockserver/asyncapi/http` turns AsyncAPI channels into
  GET expectations serving schema-aware payloads.
- **GraphQL schema-driven response synthesis.** A GraphQL body may carry a `schema` (SDL or introspection JSON);
  MockServer then synthesises a schema-valid `{"data": {...}}` for a matched query with no hand-authored
  response — honouring types, nullability, lists, enums, aliases, `__typename`, and fragments. Backed by
  `graphql-java` (22.x, Java-17-compatible).
- **gRPC example synthesis from descriptors.** A matched gRPC expectation with a successful (`grpc-status: 0`)
  response and no hand-authored body returns a schema-valid example synthesised from the proto descriptor's
  response type (scalars, enums, nested/repeated/map fields, `oneof`, well-known types) instead of an empty
  frame. Explicit bodies are never overwritten.
- **gRPC bidi-stream response templating** — a `grpcBidiResponse` may set `templateType` (`VELOCITY`/`MUSTACHE`)
  so its `json` renders against the matched inbound message.
- **gRPC Connect protocol** (buf.build) unary mocking via `ConnectResponse.success(json)` /
  `ConnectResponse.error(code, message)`; real `application/grpc` traffic is unaffected.
- **gRPC descriptor management in all clients** — upload a compiled descriptor set, list services, and clear,
  bringing every client to parity with Java.

#### Dashboard UI
- **Performance panel for load scenarios.** Author, run, monitor, stop and edit load scenarios from the UI.
  A shared named-scenario registry (lifecycle-state badges, multi-select start, per-row edit/start/stop/delete)
  sits above two sub-tabs: **Run & Monitor** (live "Running now" cards, status, the multi-scenario chart and
  post-run summary) and **Create / Edit** (the stage-builder form with generated register-and-start client
  code rendered inline below it). The code uses each client's idiomatic load-scenario builders
  (`loadScenario(...).withProfile(LoadProfile.of(LoadStage.constantVus(...)))`, etc.) rather than raw JSON —
  matching the Mock and Verification code generators — across Java, Node, Python, Go, C#, Ruby and Rust (plus
  raw JSON and curl), and regenerates live as you fill in the form. The view follows the task — editing a
  scenario switches to Create / Edit, starting a run switches to Run & Monitor. The chart plots every
  concurrently-running scenario at once — a
  line per scenario plus an aggregate "all scenarios" total — with independent toggles for which metrics to
  show (RPS, VUs, in-flight, p50/p95/p99, error rate) and which scenarios to include (all enabled by default).
  Each run shows a determinate progress bar (elapsed / total profile duration), green while driving load and
  amber while paused.
- **Contract and Cluster panels.** **Contract** runs an OpenAPI spec against a live service and renders a
  pass/fail-per-operation table; **Cluster** shows state-backend cluster status (node id, coordinator,
  members), auto-refreshing.
- **Monaco code editor for body matchers** with syntax highlighting, per-type language modes (JSON, XML,
  GraphQL, plaintext) and live JSON / JSON-Schema validation (inline red squiggles before submit). Monaco and
  its workers are bundled and served locally (no runtime CDN).
- **Before→after preview diff** when creating or editing a mock — the "Capture as Mock" dialog and the
  Composer's Review step show a side-by-side JSON diff of what will be created/changed, via a bundled Monaco
  `JsonDiffViewer`.
- **gRPC services view** listing loaded services and methods with per-service health, auto-refreshing.
- **Scenario state-machine diagram** — the selected scenario's states and transitions render as a live Mermaid
  `stateDiagram-v2` with the current state highlighted, built from what the panel observes.
- **Named-example picker for OpenAPI imports** — when a pasted inline spec declares multiple named response
  examples, a per-operation dropdown chooses which the generated mock returns (sent as `operationsAndResponses`).
- **Set breakpoint from a log row** — a log entry's pause action pre-fills a breakpoint matcher from that
  request's method and path and jumps to the Breakpoints form.
- **Duplicate an expectation, plus a priority column** — per-row Duplicate opens the Composer with an id-stripped
  copy; a `P<n>` chip and a sortable Priority header show match order.
- **Usability, responsiveness and new surfaces** (an adversarial-review pass): per-row delete/edit of a single
  mock; auto-refreshing live panels (Drift, Breakpoints, AsyncAPI, MCP); a Quick/Advanced Composer toggle with
  plain-language tooltips; SAML provider mocking; a responsive layout that works on tablet/mobile (collapsing
  grid, adaptive "More" navigation, full-screen dialogs); resizable panels; a keyboard-shortcuts help dialog;
  baseline-compare; real Mermaid agent-run graphs; and inspect/edit-restart of a running chaos experiment.
- **Request-log enhancements** — timestamps on each entry, regex filtering on method/path with saved named
  filter presets, a side-by-side visual diff in "Why didn't this match?", a matcher test playground, and
  authoring of `capture` rules in the Composer.

#### IDE extensions (VS Code & JetBrains)
- **Expectation-file schema support.** `*.mockserver.json(c)` files get inline schema validation, autocompletion
  and hover docs, driven by the same schema MockServer validates against (generated from `mockserver-core`).
- **In-IDE breakpoint debugger** over the callback WebSocket — register a matcher, receive paused exchanges, and
  Continue / Modify / Abort on requests and responses, including per-frame stream editing. Breakpoints fire only
  on traffic through MockServer.
- **Author, verify and record against a running server** — load expectations, save recorded expectations (as
  JSON or DSL — record-to-code), generate expectations from an OpenAPI spec, run scratch-request match analysis,
  send ad-hoc test requests, view the request log, and reset.
- **Mock-drift surfacing** — a drift report, inline drift diagnostics on the expectation file (VS Code), and a
  "update stub to match upstream" quick-fix.
- **Distributed-trace tooling** — Find Requests by Trace (trace id → received requests) and View Trace in Backend
  (trace id → open the correlated trace in Jaeger/Tempo/Grafana via a configurable URL template).
- **LLM authoring and agent-run call graph**, an OpenAPI contract-test runner, and WASM module upload/list — in
  both extensions.
- **In-IDE dashboard** embedded via JCEF / a webview, with graceful fallback to an external browser.
- The Docker image, container name and port are configurable, and the image tag now defaults to the extension's
  own version so it can't drift behind the release.

#### Client libraries
- **Callbacks across the clients.** Class callbacks (`httpResponseClassCallback` / `httpForwardClassCallback`)
  are now available in Go, .NET, Rust, PHP, Node, Ruby and Python; object/closure callbacks
  (`mockWithCallback(...)`, response written in your own language over the callback WebSocket) are in Go, .NET,
  Rust, Node and Python. PHP supports class callbacks only (REST-only).
- **Control-plane auth and TLS/mTLS across the clients.** Go, .NET, Rust, PHP, Node and Python clients can now
  connect to a secured MockServer — a static or per-request bearer token, a CA certificate to trust the
  server's TLS, and a client certificate + key for mutual TLS. Default behaviour is unchanged.
- **Advanced response builders across the clients.** SSE, WebSocket, DNS, binary and gRPC-stream response
  builders, OpenAPI import, and verify-zero-interactions are now in the Go, Rust, .NET, PHP and Node clients,
  moving them toward parity with Java/Python.
- **Retrieve expectations as generated client code in every language.** `retrieve?format=<language>` now
  produces copy-paste-ready upsert code (and verification code for recorded requests) in Java, JavaScript,
  Python, Go, C#, Ruby, Rust and PHP, with correct per-language string escaping; the non-Java clients expose
  `retrieveExpectationsAsCode(format)` / `retrieveRecordedExpectationsAsCode(format)`. The dashboard
  Library → Export tab offers all eight languages plus a verification-code option.
- **Client test-framework fixtures and idiomatic auto-cleanup** that reset the server between tests — Go
  (`MockServerT` / `t.Cleanup`), Node (`await using` via `Symbol.asyncDispose`), Ruby (RSpec shared context),
  .NET (`MockServerFixture` / `IAsyncLifetime`), PHP (`MockServerTestTrait`). A new `client_compatibility.html`
  page documents an 8×8 feature matrix and per-language test-fixture snippets.
- **Clearer launcher errors** — the Go/Node/Python/Ruby/Rust/PHP auto-download launchers detect a 404 on the
  release bundle and fail with an actionable message (naming a version that ships bundles, the Docker image, or
  the Maven Central jar) instead of a raw 404.

#### CLI & configuration
- **`--watch` live-reload and a `mockserver demo` subcommand.** `run --watch` live-reloads expectations when
  the `--init`/`--openapi` file changes (a CLI surface over `watchInitializationJson`); `mockserver demo`
  starts a server pre-loaded with example expectations and prints getting-started/dashboard URLs and a sample
  `curl`.
- **`mockserver import <file>` subcommand and client `importExpectations(...)`** — load a JSON expectations file
  into an already-running server without restarting it.
- **Effective-configuration diagnostic** — `--print-config` prints every known property as `name = value [source]`
  (with sensitive values redacted) and exits; the same report is available at runtime from the authenticated
  `GET /mockserver/config`.
- **Readiness endpoint** (`GET /mockserver/ready`) — returns `503` until initializers and OpenAPI seeding
  complete, then `200`, distinct from the always-`200` liveness/status endpoints; the Helm chart now uses it
  for the readiness probe.
- **Fail-fast and typo detection** — `failOnInitializationError` fails startup on a malformed init file instead
  of silently continuing with zero expectations, and MockServer now logs a `WARN` for unrecognised
  `mockserver.*` / `MOCKSERVER_*` keys (e.g. a typo) instead of silently ignoring them.
- **Graceful shutdown drains in-flight requests** — on stop, MockServer waits up to `stopDrainMillis`
  (default 15000) for active requests to complete, avoiding cut connections during rolling restarts.
- More configuration properties (matching/proxying, logging, CORS) are editable at runtime from the dashboard
  configuration dialog.

#### WASM custom rules
- **Richer WASM matcher ABI, authoring SDK, and a test endpoint.** A module exporting `match_request(ptr, len)`
  now receives the request method, path and headers (as a JSON envelope) in addition to the body, with
  fallback to the legacy body-only `match(...)`. A new dependency-free Rust authoring crate
  (`mockserver-wasm-sdk`) gives typed accessors, and `POST /mockserver/wasm/test` runs a module against a
  sample request and returns `{ "matched": … }` so a module can be validated without creating a live expectation.

#### Clustering & observability
- **Cluster status endpoint and metric.** `GET /mockserver/cluster` reports cluster membership/health
  (`clustered`, `nodeId`, `coordinator`, `clusterName`, members), degenerate-but-valid on a single node and
  real JGroups membership with the Infinispan backend; a `mock_server_cluster_members` gauge exports the count.
- **Drift alerting webhook** (`driftAlertWebhookEnabled`, off by default). Fires a fire-and-forget `POST`
  carrying the drift record whenever a stored drift meets the configured severity threshold, with a
  per-signature cooldown. Fully fail-soft — a bad endpoint can never affect drift analysis or the served response.
- **Control-plane audit logging** (`controlPlaneAuditEnabled`, off by default). An append-only, bounded,
  in-memory log of control-plane mutations (who/what/when/where/outcome) recording redacted structural metadata
  only — never headers or bodies. Retrieve via `GET /mockserver/audit`; cleared on reset.
- **Per-upstream forward/proxy observability** — `mock_server_forward_request_duration_seconds` and
  `mock_server_forward_requests` labelled by `upstream_host` (and `status_class`), plus `server.address`/
  `server.port` attributes on the forward span. Host-only labels keep cardinality bounded.
- **Dropped-log-event visibility** — when the event-log ring buffer is full, dropped events are counted and
  exported as `mock_server_dropped_log_events` (previously INFO/DEBUG drops vanished silently), with a single
  WARN on the first drop.
- **Optional per-expectation metrics** (`perExpectationMetricsEnabled`, off by default) — a
  `mock_server_expectation_matched` counter labelled by stable expectation id.


### Changed

- **Demo now showcases LLM cost optimisation.** `npm run demo` seeds a crafted seven-call support-agent run
  designed to fire all six optimisation signals, so the **LLM Optimise** tab is populated out of the box. An
  optional documented recipe shows how to capture real agent traffic by proxying a headless OpenCode run.
- **Dashboard navigation.** The **Optimise** tab is renamed **LLM Optimise** and sits after **Chaos**; the
  **Sessions** tab is renamed **Trace** and sits after **Traffic**; the **Scenarios** state-machine panel moved
  from Trace to a tab on the **Mocks** page. Each tab now shows a one-line description bar, and the Get Started
  page leads with the same six features (including LLM Optimise and Performance Testing tiles).
- **Dashboard visual refresh and scale.** A real design system (consistent spacing/shadows/typography,
  dark-mode-aware log colours), KPI hero cards and a real time axis on Metrics, skeleton loaders, and humanised
  server-error messages. Long lists (Log Messages, Active Expectations, Requests) are now viewport-virtualized
  so panels with tens of thousands of entries scroll smoothly, and the dashboard is usable on small screens and
  the IDE-embedded view (driven by CSS container queries).
- **Performance.** WASM modules, Mustache templates and OpenAPI schema validators are now parsed/compiled once
  and cached (measured ~50–66% less time and allocation on the OpenAPI validation path), and per-request object
  churn in the OIDC, SAML and LLM endpoints is reduced. Behaviour and security settings are unchanged.
- **Faster request matching with many expectations** — the incoming request's headers, cookies and query/path
  parameters are converted to matcher form once per request and reused across every candidate expectation,
  cutting per-request allocations and CPU. Matching behaviour is unchanged.
- **`HttpRequest.withBody((String) null)` now leaves the body unset** (matching `HttpResponse`), so
  `getBodyAsString()` returns `null` and the request serializes with no `body` field. Body matching is
  unchanged — a null string body still matches any body. `withBody("")` is unaffected.
- **JSON Schema body matching no longer resolves remote `$ref`s** (http/https/file/jar/ftp) by default — an
  SSRF hardening. Internal/inline refs are unaffected; set `jsonSchemaAllowRemoteRefs=true` to restore.
- **Client default MockServer version aligned to the released version** across the Node, Rust, Python and PHP
  clients, so none defaults to downloading a stale server binary. Several client connection/error-handling
  leaks were also fixed (Python/Ruby now always close the HTTP response; the Node client rejects with the real
  error message instead of an empty `{}`).
- IDE extension polish — Marketplace-ready icons and landing pages, grouped/iconified actions, a VS Code
  Activity Bar side panel and status-bar item, configurable port, and clearer validation warnings before
  submitting a file.


### Fixed

#### Correctness & reliability
- **`crossProtocolScenarios` was rejected by the expectation schema** — present in the model and honoured at
  runtime but missing from the validation schema, so any expectation using it was rejected with `400`. Added to
  the expectation and embedded-OpenAPI schemas.
- **`not(...)` expectations now match correctly with fail-fast matching enabled (the default).** A negated
  matcher could wrongly report a non-match when a non-method field matched before the first mismatching field
  (any expectation with an odd number of NOT flags). The fix only short-circuits when no NOT operator is in play
  and evaluates all fields otherwise, so the verdict always equals a full evaluation. Affected path, header and body.
- **Response body matching now has full parity with request body matching.** Matching a proxied/forwarded
  response body used a stripped-down dispatch missing several behaviours (XML/form→JSON conversion, template
  bodies, multipart routing, compressed-byte binary matching) and could swallow an internal NullPointer on a
  bodyless response into a silent non-match. Request and response body matching now share a single dispatch;
  request matching is unchanged.
- **Scenario state no longer advances when a matching expectation is skipped** by a `withPercentage` gate
  (a consume-then-skip bug); the transition now applies only when the response is actually served, atomically
  (compare-and-set) so a clustered backend preserves the "exactly one winner" guarantee.
- **Configuration round-trip no longer drops properties.** `ConfigurationDTO` mirrored only about half of the
  configuration, so many settings (SLO tracking, load generation, drift alerting, HTTP/3, gRPC, DNS, WASM,
  clustering, OpenTelemetry, audit, forward pool/retry/circuit-breaker, redaction, and more) were silently lost
  when configuration was serialized and reloaded; all are now mirrored, guarded by a reflection-driven test.
- **Load-injection traffic no longer floods the request log.** A running load run filled the bounded event log
  and evicted real/LLM traffic (emptying the Traffic/Trace/LLM views); load requests are now kept out of the
  driver's event log via an in-process-only flag (gated by `loadGenerationSuppressEventLog`, default `true`).
  Metrics and SLO samples are unaffected.
- **Concurrency hardening** (code-quality review): thread-safe log timestamps (immutable `DateTimeFormatter`),
  safely-published compiled regexes and lazily-built LLM conversation matchers (`volatile`), a thread-safe
  callback WebSocket registry, exact load-scenario VU accounting, a race-free OIDC device-code poll counter,
  atomic SCIM resource updates, gRPC chaos honouring its configured probability, and recycled log entries fully
  reset on reuse.
- **Other correctness fixes** — generated curl/Java/HAR output is now correctly escaped; expectation
  persistence writes atomically (temp-file + rename); path/matrix parameter names with regex metacharacters
  match literally; matchers prefixed with only `?`/`!` no longer throw; `VerificationTimes` rejects negative
  counts; a CONNECT/SOCKS tunnel buffer leak is fixed; one client's `reset()`/`stop()` no longer tears down
  other clients on the same port; a control-plane body filter no longer matches a request with no body via a
  literal `"null"` (stringification removed); and S3 persistence no longer throws on an empty/missing prefix
  listing.
- **GraalVM Engine leak in the JavaScript template engine** — a per-instance native `Engine` was never closed
  and accumulated under per-call construction, exhausting CI forks; it is now a single process-wide shared
  engine with a disposing `close()` on the thread-local context. Output and the `Java.type(...)` security
  boundary are unchanged.
- **Dashboard `favicon.svg` (and any SVG) now serves a valid `Content-Type: image/svg+xml`** — the missing
  `svg` MIME mapping produced a `null` header value that crashed Netty's encoder; the mapper now skips
  null-valued headers and falls back to `application/octet-stream` (issue #2358).
- **mTLS startup with a supplied full-chain certificate on Java 17** — a leaf+CA PEM was appending the CA twice
  (`[leaf, CA, CA]`), which Java 17's PKCS12 keystore rejects; the chain is now de-duplicated to `[leaf, CA]`.
- **Rust client** — expectations with a finite `times`/`timeToLive` no longer fail with `missing field
  'unlimited'`, and `VerificationTimes::at_least(n)` now serializes the unbounded `atMost: -1` sentinel instead
  of an impossible `between(n, 0)`.

#### Dashboard UI
- An error boundary keeps the dashboard from crashing to a blank screen when a view fails to load; the Drift
  panel surfaces failures instead of reporting false success; the import dialog no longer reports a misleading
  "Imported 0 expectations"; the traffic comparison counter/button no longer disagree; non-HTTP expectations no
  longer render their id twice; and a "Capture as mock" body matcher can be added when the captured request had
  no body. Plus efficiency fixes (single serialization per row on each WebSocket push, memoized traffic rows,
  TTL-only countdown timer) and consistent error humanisation.

#### IDE extensions (VS Code & JetBrains)
- The JetBrains plugin is no longer capped to IDE build 253 (`untilBuild` removed, so it stays available in
  current and future IDEs) and no longer risks an `AlreadyDisposedException` when a project is closed while an
  HTTP request is in flight; JetBrains JSON-schema completion/validation for `*.mockserver.json(c)` now works
  in IntelliJ (registered under the correct extension point, with a navigable root and no network schema
  fetch). The VS Code extension now activates on `onStartupFinished`, so the status-bar item and CodeLens
  appear immediately on a fresh window.

#### Request matching & verification
- **Notted key in `MATCHING_KEY` mode now asserts key-absence** (`!X` means "no key `X` present") instead of
  aggregating values from every other key.
- **Closest-expectation diagnostics** no longer count non-HTTP fields in the denominator for an HTTP request or
  collapse the matched-field count under fail-fast (diagnostic-only).
- **Faster expectation registration** — registering large numbers of expectations on the in-memory backend was
  O(n²) (two full reconciliation passes per add); the non-clustered path now does an eviction-only trim,
  restoring linear time.
- **Response-modifier fidelity in codegen** — `retrieve?format=JAVA` now emits a modifier's `condition`,
  `modifiers`, `jsonPatch` and `jsonMergePatch`, and the Node `responseModifier` typedef declares them.
- **Verification fixes** — response verification no longer counts MockServer's own auto-generated no-match
  `404`s; response-aware sequences with mismatched request/response list lengths are rejected instead of padding
  with always-matching nulls; an entirely-empty sequence is rejected; a recorded pair with a null request is a
  non-match instead of an NPE; failing response-sequence messages now show the responses; and a verification
  whose request filter fails to build now completes instead of hanging.

#### OpenAPI & contract testing
- **`allOf: [ $ref to a scalar ]` example generation** no longer wraps the scalar in a single-element array
  (`{"baz": ["hello"]}` → `{"baz": "hello"}`), which broke clients typed against the spec (#2357).
- **OpenAPI handling hardened across both directions** (audit follow-up to #2357): range status-code keys
  (`2XX`) no longer crash import and validate correctly; distinct specs sharing an `info.title` no longer delete
  each other's expectations (namespace now keyed by a SHA-256 of the source); expectations→OpenAPI export is
  now schema-valid and faithful (path parameters templated, negated/schema matchers preserved, same path+method
  responses merged, correct media types); `contextPathPrefix` is accepted by its schema; pinning an undefined
  `statusCode`/`exampleName` warns and falls back instead of silently returning an empty `200`; a webhooks-only
  3.1 spec no longer NPEs; and a re-imported URL/file spec now evicts the cache so it picks up current content.
- **XML response bodies are now real, spec-correct XML** for `application/xml`/`text/xml`/`+xml` responses,
  serialised using the schema's `xml` metadata (name/namespace/prefix/attribute/wrapped) per the OpenAPI XML
  Object rules, fixing earlier malformed pluralised/recursive output. OAS 3.1 multi-type `type` arrays are
  preserved (`["string","null"]` → `string` + `nullable`). (Behaviour change for XML responses; JSON unchanged.)
- **OpenAPI example generation honours more JSON-Schema constraints** — `minItems`/`maxItems`, string `pattern`,
  `exclusiveMinimum`/`Maximum`, the `time` format, `minProperties`, and `default`/`enum` on format-less
  integer/number schemas. Unconstrained schemas are unchanged.

#### Build & dependencies
- **Stop leaking the vulnerable `commons-beanutils`** (GHSA-wxr5-93ph-8wr9 / CVE-2025-48734) to downstream
  consumers through `velocity-tools-generic` — the 1.11.0 pin lived only in `dependencyManagement` (not
  transitive); it is now excluded from `velocity-tools-generic` and declared directly so the fixed version
  propagates (#1981).

#### Performance under load
- **CPU no longer climbs as the request/event log fills under `/retrieve` and `clear`** (issue #2359, a
  follow-up to #2329). The read paths ran the expensive request matcher on every log entry — including deleted
  tombstones and wrong-type entries — before the cheap type/not-deleted filter, so each `/retrieve` cost grew
  with total log size. The filters are now ordered cheap-predicate-first, and `clear` skips already-deleted
  entries. No behaviour change. Tip for high-throughput users: also clear the log (`?type=LOG`/`ALL` or
  `/reset`), not just expectations, or lower `maxLogEntries`.

## [7.1.0] - 2026-06-15

### Added

#### Verification
- **Verify responses received from proxied/forwarded systems** — verification now optionally matches the **response** of a recorded request-response exchange, not just the request. Add an `httpResponse` matcher to a verification (`PUT /mockserver/verify` with `{httpRequest?, httpResponse, times}`) and MockServer counts recorded request-response pairs (proxied/forwarded exchanges) whose response matches — by status code, reason phrase (regex), headers, and body (JSON, JSON schema, JSONPath, XML, XPath, regex, etc., reusing the existing request body matchers). When `httpRequest` is also supplied, both must match. `verifySequence` gains an index-aligned `httpResponses` list so an ordered sequence can assert on responses too. The `verify`/`verifySequence` call shape and `VerificationTimes` are unchanged — the presence of a response matcher is what switches verification from "request received" to "response received". When no response matcher is supplied, behaviour is identical to before.

#### Breakpoints & request replay
- **Matcher-driven breakpoints** — breakpoints are toggled per-request via a matcher rather than by global config flags. You register a **request matcher** (works exactly like an expectation request matcher) together with the phases to break at: `PUT /mockserver/breakpoint/matcher` with `{httpRequest, phases:["REQUEST"|"RESPONSE"|"RESPONSE_STREAM"|"INBOUND_STREAM"], clientId:"..."}`. A forwarded/proxied exchange whose request matches a registered breakpoint pauses at the selected phase(s). Manage matchers via `GET`/`PUT /mockserver/breakpoint/matchers`, `PUT /mockserver/breakpoint/matcher/remove` (`{id}`), and `PUT /mockserver/breakpoint/matcher/clear`; the registry is cleared on `/mockserver/reset`. The `breakpointTimeoutMillis` (30000) and `breakpointMaxHeld` (50) safety rails are retained.
- **`clientId` required for breakpoint registration; callback WebSocket is the resolution transport** — `PUT /mockserver/breakpoint/matcher` requires a `clientId` field (the callback WebSocket client id); omitting it returns 400. Breakpoints are resolved interactively over the callback WebSocket only — all clients (including the dashboard) resolve breakpoints over that channel.
- **Interactive breakpoint resolution over the callback WebSocket** — a matching forwarded REQUEST or RESPONSE exchange is dispatched to the owning callback-WebSocket client (the same `/_mockserver_callback_websocket` channel `forwardObject`/`responseObject` clients use) for interactive resolution: the client replies with a modified request (forward), a response (abort/replace), or the original (continue). Shares the `breakpointTimeoutMillis` auto-continue and `breakpointMaxHeld` cap rails; a client disconnect removes its breakpoints and auto-continues anything it was holding.
- **Per-frame streaming breakpoints over the callback WebSocket** — RESPONSE_STREAM (outbound) and INBOUND_STREAM (client→server) breakpoints resolve interactively over the callback WebSocket across all nine streaming hold points (SSE/chunked, HTTP/3 gRPC, gRPC server-streaming, WebSocket eager/bidi, GraphQL-subscription, and the WebSocket/GraphQL/gRPC-bidi inbound paths). Two WS message types form the frozen per-frame protocol: a server→client `PausedStreamFrameDTO` (`correlationId`, `streamId`, `sequenceNumber`, `direction`, `phase`, base64 `body`, request method/path) and a client→server `StreamFrameDecisionDTO` (`correlationId`, `action` ∈ CONTINUE/MODIFY/DROP/INJECT/CLOSE, optional base64 `body`). Event-loop safe (decisions marshalled onto the channel event loop, frame bytes copied to `byte[]`), with ordering and backpressure preserved and the shared timeout/max-held rails + client-disconnect auto-continue. The per-server WebSocket registry is injected per-channel (no process-global state).
- **Java client breakpoint API (matcher + callback handlers)** — `MockServerClient.addBreakpoint(matcher, phases…, handlers…)` registers a breakpoint matcher and resolves paused exchanges interactively over the callback WebSocket, with typed handlers per phase: `BreakpointRequestHandler` (return a request to forward/modify or a response to abort), `BreakpointResponseHandler` (return the response to write), and `BreakpointStreamFrameHandler` (return a CONTINUE/MODIFY/DROP/INJECT/CLOSE decision). Plus `listBreakpointMatchers()`, `removeBreakpointMatcher(id)`, `clearBreakpointMatchers()`. The client lazily opens one callback-WS connection (reused across breakpoints) and tears it down on stop/reset. **Per-matcher handler routing:** each pushed paused item carries the matched breakpoint's id (a new `X-MockServer-BreakpointId` header for request/response and a `breakpointId` field on the stream-frame message), so each breakpoint routes to its own handler rather than a single shared per-phase handler. This is the reference API the other language clients mirror.
- **Node, Python & Ruby client breakpoint APIs** — the Node, Python, and Ruby clients gain the same matcher-driven breakpoint API as the Java client (`addBreakpoint`/`add_breakpoint` + convenience overloads, `list`/`remove`/`clear` breakpoint matchers), resolving paused request/response/stream-frame exchanges interactively over each client's existing callback WebSocket with per-matcher handler routing (by the `X-MockServer-BreakpointId` header / `breakpointId` frame field). Idiomatic per language (typed objects in Node, dicts in Python, hashes in Ruby); handlers auto-continue on error or missing handler so a buggy handler can't hang the exchange.
- **Go, .NET & Rust client breakpoint APIs (new callback-WebSocket stacks)** — the Go, .NET, and Rust clients gain a full callback-WebSocket stack (Go `gorilla/websocket`, .NET built-in `ClientWebSocket`, Rust `tungstenite`) plus the matcher-driven breakpoint API (`addBreakpoint`/`AddBreakpoint`/`add_breakpoint` + convenience overloads, list/remove/clear breakpoint matchers). Each connects to `/_mockserver_callback_websocket`, registers a `clientId`, and resolves paused request/response/stream-frame exchanges over the callback WebSocket with per-matcher handler routing, auto-continuing on handler error/panic. Concurrency-safe (serialised WS writes + lazy init; Go verified with `-race`) and reconnect-on-dead-connection. PHP is excluded (no WebSocket support). This completes breakpoint support across seven clients (Java, Node, Python, Ruby, Go, .NET, Rust).
- **Stream frame breakpoints (backend)** — per-frame hold/modify/drop/inject/close for all streaming response types: forwarded SSE/HTTP/1.1 chunked, gRPC server-streaming, WebSocket, GraphQL-subscription, and HTTP/3 gRPC. Each frame is intercepted at its hold point, parked in `StreamFrameBreakpointRegistry`, and resolved over the callback WebSocket. Fully non-blocking (event-loop safe), with backpressure, ordered frame resolution, stream-close eviction, timeout auto-continue, and the shared `breakpointMaxHeld` cap. Activated when a matching `RESPONSE_STREAM` breakpoint matcher is registered (zero overhead otherwise).
- **Inbound (client→server) breakpoints for gRPC bidi over HTTP/3 (QUIC)** — extends `INBOUND_STREAM` breakpoints to bidirectional gRPC streaming over HTTP/3, the QUIC analogue of the HTTP/2 gRPC-bidi inbound path (`Http3GrpcBidiStreamHandler`). Each inbound gRPC DATA frame is parked before decoding and resolved over the callback WebSocket (continue/modify/drop/inject/close); default-off (only when an `INBOUND_STREAM` matcher matches the stream). Because the QUIC driver copies each frame to `byte[]` and releases it before handing off, no `ByteBuf` is held and the QUIC flow-control window is never pinned; per-frame ordering is preserved by dispatching one frame at a time and buffering the rest (bounded by `maxRequestBodySize`). This completes interactive breakpoints across HTTP/1.1, HTTP/2, and HTTP/3.
- **Dashboard Breakpoints panel (callback-WebSocket client)** — the dashboard is a real callback client: it connects to `/_mockserver_callback_websocket` (the server assigns it a `clientId`, since a browser WebSocket can't send the registration header) and resolves paused exchanges live over the callback WebSocket — no REST polling. The panel has three tabs: **Matchers** (register a breakpoint matcher with a method/path matcher + phase checkboxes; list/remove/clear), **Live Exchanges** (paused requests/responses arrive in real time — Continue / Modify the JSON / Abort), and **Live Streams** (paused stream frames — Continue / Modify / Drop / Inject / Close; direction badge distinguishes INBOUND from OUTBOUND frames). A connection-state indicator shows the callback-WS status.
- **Request replay from the dashboard** — a new `PUT /mockserver/replay` control-plane endpoint re-issues a previously recorded/proxied request to its original target and returns the upstream response (reuses the existing `NettyHttpClient`/forward client; 10 MB body-size cap; behind control-plane auth). The dashboard Traffic view gains a Replay button on every selected request that opens a dialog to re-issue the request with one click and inspect the live response. The Java client exposes a typed `replay(HttpRequest)` method wrapping the endpoint.
- **Inbound bidirectional frame breakpoints (backend)** — intercepts client-to-server frames on WebSocket, GraphQL-subscription, and gRPC-bidi connections before MockServer processes them. Each inbound frame is copied to byte[], the original ByteBuf/Http2DataFrame is released immediately (refunding the HTTP/2 flow-control window), and the copy is parked in `StreamFrameBreakpointRegistry` with `direction=INBOUND`. Resolved over the callback WebSocket. Fully non-blocking with backpressure (autoRead paused for WebSocket/GraphQL; pull-based ctx.read() withholding for gRPC-bidi), channel-close eviction. Activated when a matching `INBOUND_STREAM` breakpoint matcher is registered (zero overhead otherwise).

#### OpenAPI
- **Full OpenAPI 3.1 support** — MockServer now fully supports OpenAPI 3.1 specifications, including the three constructs previously documented as partially handled: `type` as an array (e.g. `type: [string, "null"]`) now generates correct example values for the primary non-null type; `$ref` siblings (description alongside `$ref`) are resolved by the parser; and the `webhooks` top-level key is parsed and its operations are included when generating expectations, matching requests, and validating responses. No specification changes or version downgrades are required.

#### Chaos engineering
- **Scheduled multi-stage chaos experiments** — a new `PUT /mockserver/chaosExperiment` endpoint starts an ordered sequence of chaos stages, each applying service-scoped chaos profiles for a configurable duration before automatically advancing to the next stage. Supports looping, status polling via `GET /mockserver/chaosExperiment`, graceful stop via `DELETE /mockserver/chaosExperiment`, and integrates with the C1 auto-halt circuit-breaker (an experiment halts if the safety threshold is exceeded mid-stage). Max 50 stages, 24 h per stage, one active experiment at a time.
- **Chaos auto-halt circuit-breaker** — when enabled (`chaosAutoHaltEnabled=true`), MockServer automatically disables all active service-scoped chaos profiles if the number of chaos-injected errors within a sliding window exceeds a configurable threshold, preventing chaos experiments from causing cascading outages. Reflected in the `mock_server_chaos_auto_halt_total` Prometheus counter and a WARN log event.
- **Dashboard Chaos tab — full HTTP fault-type controls** — the HTTP Service Chaos register/edit form now exposes every `HttpChaosProfile` field: Retry-After header, body truncation fraction, malformed body toggle, slow (dribbled) response chunk size/delay, quota rate-limiting (name/limit/window/error status), degradation ramp, and outage time window — so users can configure the complete fault set without writing JSON.

#### LLM observability & cost control
- **LLM proxy/forward observability** — observability that previously fired only for *mocked* LLM responses now also covers LLM traffic **forwarded/proxied** through MockServer. With `otelTracesEnabled`, MockServer emits a GenAI OpenTelemetry span (provider, model, token usage, finish reason) for forwarded LLM responses, using a new provider sniffer that detects the upstream from the target host (with a path-gated fallback to `llmProvider`); all forward paths (matched-forward, unmatched proxy-pass, breakpoint-continuation) now also emit the generic request span consistently. The agent-run analysis tools (`explain_agent_run`, `verify_tool_call`) accept `provider:"AUTO"` for provider auto-detection from recorded request paths, and the dashboard Sessions view renders the call graph for proxy-only sessions, grouping unscoped traffic by upstream host. Off by default; fully fail-soft (telemetry never affects the forwarded response).
- **LLM token/cost Prometheus metrics** — when `llmMetricsEnabled=true` (alongside `metricsEnabled`), three new Prometheus counters track cumulative LLM token usage and estimated cost across all served and forwarded completions: `mock_server_llm_input_tokens`, `mock_server_llm_output_tokens`, `mock_server_llm_cost_usd`, each labeled by `provider` and `model`. The forward-path response parse is gated on metrics OR tracing OR budget, so token tracking works without requiring full OTLP tracing. Default off to avoid parsing forwarded response bodies unless asked.
- **LLM cost-budget circuit-breaker** — `mockserver.llmCostBudgetUsd` sets a cumulative USD ceiling across all LLM completions (mocked + forwarded). When the running cost total exceeds the budget, unmatched LLM proxy forwards are blocked with a 429 response including the cumulative and budget amounts (mocked LLM responses are never blocked). Deterministic and fail-open (a negative, unset, or malformed budget never blocks traffic). Resets on `HttpState.reset()`. Tracked by the `mock_server_llm_cost_budget_tripped` Prometheus counter.
- **Per-session token/cost totals in Sessions view** — the dashboard Sessions view now displays per-session aggregate token usage (total input/output tokens) and estimated USD cost as chips in each session lane header, computed purely client-side from the already-parsed response bodies.
- **First-class LLM failover/retry scenario builder** — `LlmFailoverBuilder` and the `mock_llm_failover` MCP tool generate an ordered set of expectations that simulate a provider returning failures (e.g. 503, 429) for the first N attempts, then succeeding with a provider-correct `httpLlmResponse`. Uses `Times.exactly(n)` on failure expectations so they are consumed in order before falling through to the unlimited success expectation. Consecutive same-status failures are coalesced for efficiency. Point LiteLLM, Envoy AI Gateway, or an SDK's retry config at MockServer and assert failover logic deterministically.
- **Token-based (TPM/TPD) LLM rate-limit simulation** — `LlmChaosProfile` now supports token-based quota enforcement via `tokenQuotaLimit` and `tokenQuotaWindowMillis`, modelling real provider TPM/TPD limits. Each response's token count (from `Usage` or estimated from text length) is charged against an independent fixed-window counter in `LlmQuotaRegistry`; when the cumulative in-window total exceeds the limit, a 429 (`token_quota_exceeded`) is returned. Both request-count and token quotas can coexist on the same profile.
- **Provider-correct LLM rate-limit response headers** — when MockServer returns a rate-limit or quota error on the LLM response path (probabilistic chaos `errorStatus` or stateful quota 429), it now emits the provider-correct rate-limit HTTP headers that real LLM providers send (OpenAI `x-ratelimit-limit-requests`/`x-ratelimit-remaining-requests`/`x-ratelimit-reset-requests`, Anthropic `anthropic-ratelimit-requests-*` with RFC 3339 timestamps, Gemini/Bedrock `retry-after`). Successful responses also carry the headers when a quota is configured, so client SDK retry/backoff logic can be exercised against a mock. Ollama returns no rate-limit headers (local inference). Implemented by the pure helper `LlmRateLimitHeaders` (`org.mockserver.llm`).

#### Mock creation & matching feedback
- **Generalised capture-to-expectation** — the dashboard "Capture as Mock" dialog now works for **any** recorded or proxied request (plain HTTP, gRPC, GraphQL), not just LLM traffic. A three-level **matcher precision toggle** (Exact / Moderate / Loose) controls how tightly the generated `httpRequest` matcher binds: from method+path+query+headers+body down to method+path only. Generic captures register via `PUT /mockserver/expectation` with `httpResponse`; the existing LLM capture path is unchanged.
- **Create expectation from unmatched request** — the "Why Didn't This Match?" mismatch diagnostic dialog now includes a "Create Expectation" button that opens the capture-as-mock dialog pre-filled with the unmatched request, letting users turn a near-miss into a working stub in one click.
- **Client-visible match feedback** — new opt-in config property `attachMismatchDiagnosticToResponse` (default `false`) attaches closest-match diagnostic info (header `x-mockserver-closest-match` + JSON body with per-field diffs) to 404 responses for unmatched requests, so test authors can see why their mock didn't match without checking the dashboard or logs.
- **Opt-in realistic OpenAPI example data** — new config property `generateRealisticExampleValues` (default `false`) makes OpenAPI example generation produce schema/format-aware values via Datafaker (email, UUID, date, date-time, URI, hostname, IPv4/IPv6, byte, password, integers/numbers respecting min/max) instead of static placeholders, with a fixed seed for deterministic output. Existing behaviour is unchanged when the flag is off.

#### Response templates
- **Templates can be loaded from a file** — `httpResponseTemplate` and `httpForwardTemplate` accept a new `templateFile` field (a classpath-or-filesystem path) as an alternative to the inline `template`, keeping large templates out of the expectation JSON. When both are set the inline `template` takes precedence. Works with all three engines (Velocity, Mustache, JavaScript).
- **Templated response body files** — a static `httpResponse` whose body is a `FILE` body can set a `templateType` of `MUSTACHE` or `VELOCITY`, in which case the file contents are rendered as a template against the request before being returned (the status code, headers and content type still come from the static response). This combines externally stored response bodies (issue #2163) with response templating, as requested in discussion #2350. JavaScript is not supported for body files (its templates return a full response object rather than text) — use `httpResponseTemplate` for that.
- **Client-library support for `templateFile` and templated FILE bodies** — the Node, Python, Go, .NET, Ruby and Rust clients gain `templateFile` on their template models and `templateType` on FILE response bodies, so the two features above can be driven from each client (the PHP client, which has no template model, gains a `fileBody()` helper).
- **Velocity templates are parsed once and cached** — the Velocity engine previously re-parsed the template string on every render. It now caches the parsed template (via Velocity's native string-resource cache) and reuses it, so a repeatedly rendered template (response templating, forward templating, and especially load-scenario steps that render every iteration) is rendered without re-parsing. Output is unchanged. Measured with JMH (`-prof gc`): 55–79% faster and 46–74% less allocation per render across simple-to-complex templates, with the biggest wins on complex templates under sustained load.
- **Velocity render allocates less per request** — the stateless built-in template functions and helpers (`$uuid`, `$faker`, `$json`, etc.) are now shared across renders via a single immutable context layer instead of being copied into a fresh context on every render. Request-scoped state (the request, the per-iteration values, and request-scoped tools like `$json`/`$xml`) is still built fresh per render, so output and thread-safety are unchanged. Measured with JMH: a further ~1 KB/op less allocation and 30–67% faster per render on top of the parse-once caching above.

#### Dashboard & UI
- **Editable runtime config in the dashboard** — the Configuration dialog now exposes editable controls for `devMode`, `generateRealisticExampleValues`, `attachMismatchDiagnosticToResponse`, `validateProxyOpenAPISpec`, `validateProxyEnforce`, `chaosAutoHaltEnabled`, `chaosAutoHaltErrorThreshold`, and `chaosAutoHaltWindowMillis` (booleans as switches, strings and numbers as text/number fields), driven by a declarative descriptor list in `configuration.ts`. Existing `logLevel`/`detailedMatchFailures`/`metricsEnabled` controls are unchanged; properties not in the descriptor list remain visible read-only.
- **Dashboard Composer — template snippet palette** — the Response Template and Forward Template panels now include an "Insert snippet" button that opens a categorised palette of curated template snippets (request echoes, dynamic data, structure patterns). The palette is engine-aware, showing the correct Velocity / Mustache / JavaScript syntax for the selected template engine and including a live preview of each snippet's output.
- **Dashboard Composer — multi-language code preview** — the Review step's read-only code preview now generates idiomatic client snippets for **Node.js, Python, Go, C#, Ruby and Rust** alongside Java, with JSON and curl shown last. Each client-library tab hydrates the same expectation JSON through that client's native facility (Node `mockAnyResponse`, Python `Expectation.from_dict`, Go/Rust deserialize-and-`Upsert`, C# `Deserialize<Expectation>`, Ruby `Expectation.from_hash`), so every action type is representable without reimplementing each language's builder API. The Composer also gains a "Load template from file" field on the template panels and a "Body source: from file" option (with an optional template engine) on the static-response panel, surfacing the `templateFile` and templated-FILE-body features.
- **Dashboard Library view — Import tab** — the Library view now opens on an Import tab (alongside Export) that lets users paste, upload, or URL-import specs and collections directly from the dashboard (Expectation JSON, OpenAPI, WSDL, HAR, Postman), wiring to the existing server endpoints without any new backend changes.
- **Dashboard "Get Started" onboarding panel** — new users land on a guided first-run view with action cards to import an OpenAPI spec, set up proxy recording, try docker-compose quick-start recipes, and explore the dashboard docs. The view is the default when no expectations or traffic exist; it auto-transitions to the dashboard once data arrives and remains accessible via the nav bar.
- **Dashboard request diffing from the Traffic view** — a "Compare" toggle in the Traffic inspector lets you pick two recorded or proxied requests and open the field-level diff inline (reusing the existing `PUT /mockserver/diff` endpoint and diff dialog), pre-populated with the two selected requests.
- **LLM streaming-physics controls in the Composer** — the conversation builder now exposes streaming-physics fields (time-to-first-token, tokens-per-second, jitter) when a turn is marked as streaming, so users can shape the timing of mocked streamed completions without hand-writing the `streaming` block.
- **LLM structured-output field in the Composer** — the conversation builder now has an `outputSchema` field so a mocked completion can declare a JSON schema for structured/tool-style output.
- **WASM rule body matcher in the Composer** — the expectation Composer now offers a `wasm` body-matcher option with a module-name dropdown sourced from the uploaded WASM modules, so a custom WASM rule can be wired into an expectation from the dashboard (it previously could only be uploaded, not referenced).
- **Chaos auto-halt controls in the Chaos tab** — the dashboard Chaos tab now surfaces the auto-halt circuit-breaker inline (arm/disarm switch, error threshold, and sliding-window size) so users can see and adjust the safety cut-off where they configure chaos, rather than only in the Configuration dialog.

#### CLI & self-contained binary
- **Redesigned command-line interface** — a `mockserver` CLI (built on picocli) with `run` (default), `proxy`, `openapi`, `version` and `help` subcommands, per-command `--help`, short flags (`-p`/`--port`, `--proxy-to`, `--openapi`, `--init`, `--persist`, `-l`/`--log-level`) and scheme-aware proxy targets (`--proxy-to https://host` infers the port). The `org.mockserver.cli.Main` entry point, all existing flags (`-serverPort`, `-proxyRemotePort`, `-proxyRemoteHost`, `-logLevel`) and the configuration precedence (command line > system property > environment variable > properties file) remain fully supported. Documented in `docs/code/cli.md` and the *Running MockServer* site page.
- **CLI validation-proxy flags** — `--validate-openapi <spec>` and `--validate-enforce` on the `run` and `proxy` subcommands let users launch a validating proxy in one command, wiring directly to the existing `validateProxyOpenAPISpec` / `validateProxyEnforce` configuration properties.
- **Developer-friendly `--dev` mode** — opt-in `--dev` CLI flag (or `MOCKSERVER_DEV_MODE=true` / `-Dmockserver.devMode=true`) applies laptop-appropriate defaults: `maxLogEntries=1000` and `maxExpectations=1000`, reducing memory usage for local development and test suites. Explicit configuration always overrides dev-mode defaults. Default behaviour (without `--dev`) is completely unchanged.
- **`ui` subcommand** — `mockserver ui [-p <port>]` starts MockServer (default port 1080) and opens the dashboard (`/mockserver/dashboard`) in the default browser, printing the URL and degrading gracefully to just the URL on a headless host (server/CI/SSH). To start without opening a browser, use `run`.
- **`-D<key>=<value>` CLI property passthrough** — `run`/`ui`/`proxy`/`openapi` accept repeatable `-D` options (e.g. `mockserver run -p 1080 -Dmockserver.metricsEnabled=true`), applied as JVM system properties before startup, so the launcher and jar can set any configuration property without a JVM `-D` before `-jar`.
- **Clearer CLI errors & help** — starting without a resolvable port (no `-p`/`--port`, `MOCKSERVER_SERVER_PORT`, `mockserver.serverPort`, or properties file) now prints a concise picocli usage plus a one-line actionable error instead of the legacy `java -jar …` block and an empty configuration dump. Usage text reflects how MockServer was launched (`mockserver …` from the binary bundle, `java -jar …` otherwise), and `-help`/`-version` now behave the same as `--help`/`--version` (top-level overview).
- **Self-contained binary distribution (no JVM, no Docker)** — every release now publishes downloadable MockServer bundles (a jlink-trimmed Java runtime + the server + a `mockserver` launcher) for Linux, macOS and Windows (x86_64 + aarch64) as assets on the GitHub Release, each with a SHA-256. Download, extract, and run `bin/mockserver run -p 1080` — no pre-installed JVM or Docker required. Built from one host via `scripts/build-binary-bundle.sh` / `scripts/build-all-bundles.sh`.
- **`mockserver-node` binary launcher** — `npx -p mockserver-node mockserver run -p 1080` downloads the JVM-less binary bundle for the current platform (no Java, no Docker), verifies its SHA-256, caches it per-user, and runs it. Honours `MOCKSERVER_BINARY_BASE_URL` (mirror), `MOCKSERVER_SKIP_BINARY_DOWNLOAD`, `MOCKSERVER_BINARY_CACHE` and `NODE_EXTRA_CA_CERTS`. Reference implementation of the on-demand-binary pattern for the client libraries.

#### Client libraries & integrations
- **Multi-language client libraries** — hand-written idiomatic clients for the MockServer control plane in **Go** (`mockserver-client-go`, pkg.go.dev), **.NET** (`MockServerClient`, NuGet), **Rust** (`mockserver-client`, crates.io) and **PHP** (`mock-server/mockserver-client`, Packagist), covering create-expectation, verify/verifySequence, clear, reset and retrieve. Each ships unit tests plus a skippable integration test.
- **Testcontainers modules** — a `MockServerContainer` for **Node**, **Python**, **.NET**, **Go** and **Rust** (under `mockserver-testcontainers/`) that starts the `mockserver/mockserver` image, waits on `/mockserver/status` and exposes the mapped URL.
- **Editor integrations** — a **VS Code** extension (`mockserver-vscode`: start/stop the Docker container, open the dashboard, expectation snippets) and an initial **JetBrains/IntelliJ Platform** plugin scaffold (`mockserver-jetbrains`).

#### Packaging & distribution channels
- **GHCR image mirror** — every release now mirrors the multi-arch images to `ghcr.io/mock-server/mockserver` (copied from Docker Hub by digest, cosign-signed). Error-isolated: a GHCR failure never affects the Docker Hub / ECR publish.
- **Automated MCP registry publishing** — the release pipeline publishes `server.json` to `registry.modelcontextprotocol.io` under the DNS-verified `com.mock-server/mockserver` namespace (non-interactive auth via an ed25519 key in Secrets Manager + an apex TXT record). Soft-fail — never blocks a release.
- **Release pipeline distribution channels** — soft-fail release components that publish the new clients, Testcontainers modules and editor extensions (NuGet, crates.io, Packagist, pkg.go.dev, npm, PyPI, VS Code Marketplace / Open VSX, JetBrains Marketplace), with post-release liveness checks.
- **`mockserver-bom` (Bill of Materials)** — a new published artifact consumers can import into their `dependencyManagement` to pin every MockServer module **and** every third-party dependency MockServer relies on to a single, mutually consistent version. This makes downstream builds reproducible and satisfies strict version-alignment checks such as the Maven Enforcer `dependencyConvergence` rule, which previously flagged the differing transitive versions MockServer resolves internally (via its parent POM's `dependencyManagement`) but did not export to consumers. Usage: import `org.mock-server:mockserver-bom` with `<type>pom</type>` and `<scope>import</scope>`.

#### Onboarding & guides
- **One-command quick-start recipes** — curated `docker compose up` recipes under `examples/docker-compose/` for the most common use cases (`mock-from-openapi`, `record-replay-proxy`, `validation-proxy`, `chaos-proxy`), each self-contained with a short README and a "Getting started in 60 seconds" path in the repository README.
- **Consolidated "Self-Hosting MockServer" guide** — a single task-oriented site page (`/mock_server/self_hosting_mockserver.html`) that brings together every way to run MockServer yourself with copy-paste commands: Docker and the one-command docker-compose recipes, the `mockserver` CLI and the JVM-less binary bundle, Helm/Kubernetes, the executable JAR, Testcontainers, initializers/persistence, and bootstrapping from a browser HAR. Linked from the repository README.
- **MockServer UI docs — Traffic compare/diff and full Chaos fault set** — the *MockServer UI* site page (`/mock_server/mockserver_ui.html`) now documents the Traffic view's "Compare" toggle for diffing two captured requests (`PUT /mockserver/diff`) and the Chaos tab's complete HTTP service-chaos fault set wired to `PUT /mockserver/serviceChaos` (error/connection faults, body corruption, slow-response chunking, quota/rate limit, count and time windows, gradual degradation, GraphQL error envelope, and TTL).

### Changed

- **CI** — the build pipeline now runs unit tests for the new Go, .NET, Rust and PHP libraries, the five Testcontainers modules and the editor extensions (each in its language toolchain Docker image), triggered by changes under their paths.
- **Slimmer `mockserver-client-java` classpath** — the Java client no longer drags the server-only engines (Velocity/Mustache templating, GraalVM JavaScript, WASM/Chicory, DataFaker, protobuf/gRPC transcoding **and the Swagger/OpenAPI parser**) onto a consumer's classpath when it is the only MockServer artifact depended upon. Those all run inside the server, never in the client JVM, so they are excluded from the client's `mockserver-core` dependency. `mockserver-core`'s object mapper now registers its Swagger-coupled serializers only when swagger-core is present (see Fixed), so the client serialises OpenAPI expectations as plain spec strings without the parser on its classpath. In-process-server usages (e.g. `mockserver-junit-jupiter` → `mockserver-netty`) are unaffected — the engines still arrive via the server module. Verified by the full 155-test client suite, 718 core serialization/OpenAPI tests, and a runtime check that round-trips expectations with swagger genuinely absent.

### Fixed

- **Dashboard rendered a blank page when the server ran on a non-UTF-8 platform** ([#2347](https://github.com/mock-server/mockserver-monorepo/issues/2347)) — the dashboard's static assets (JS/CSS/HTML) are always written to the wire as UTF-8, but the `Content-Length` header was computed with the JVM's default charset. On a platform whose default charset is not UTF-8 (e.g. Windows, where the legacy default is `windows-1252`), any asset containing multi-byte characters got a `Content-Length` shorter than the actual body, so the browser truncated the bundle and the dashboard showed a white page. A JAR built on macOS (UTF-8) therefore worked there but failed on Windows. `Content-Length` is now computed from the UTF-8 byte length, matching the bytes sent.
- **Diagnostic match endpoints flooded the dashboard log with spurious unmatched entries** — the "Why Didn't This Match?" debug-mismatch path and the `explain_unmatched_requests` MCP tool re-ran the live request matchers purely to compute field-level diffs, but that match wrote one `EXPECTATION_NOT_MATCHED` event per expectation into the event log as a side-effect. Those entries had no request correlationId, so the dashboard could not group them, and repeated calls filled the bounded dashboard log window and evicted matched/response/received entries — making the dashboard appear to show only unmatched traffic. Read-only diagnostics now suppress match-result logging (a request-scoped flag on `MatchDifference`), so they no longer mutate the log they inspect.
- **Dashboard Library → Import format radios mis-aligned** — the format radio buttons (Expectation JSON / OpenAPI / WSDL / HAR / Postman) now top-align with their option titles instead of centring on the whole title+description block.
- **Dashboard Composer connection-options row clipping/overlap** — in the response "Connection options (advanced)" row the "Content-Length override" field no longer clips its label and the "Close socket" dropdown arrow no longer overlaps the text; the "Suppress Content-Length"/"Suppress Connection" switches now have clear spacing from the override field instead of crowding it.
- **Build-time guard for global-state-mutating tests missing from sequential Surefire phase** — added `GlobalStateMutationGuardTest` that scans all test classes for high-signal static-state mutation patterns (`ConfigurationProperties` setter calls, `System.setProperty`/`clearProperty`, singleton `.getInstance().reset()`/`.clear()`, `Metrics.resetAdditionalMetricsForTesting`, `PrometheusRegistry.defaultRegistry`) and fails the build if any matched class is not in the sequential phase. Moved 17 test classes that were running in the parallel phase despite mutating global state to sequential (with symmetric exclude/include, validated by `ParallelStaticStateGuardTest`). This closes the gap where `ParallelStaticStateGuardTest` only checked list symmetry but could not detect a new stateful test missing from both lists — the root cause of 4 separate CI flake incidents.
- **LLM config-mutating tests flake under parallel Surefire** — `LlmBackendResolverTest`, `LlmProviderSnifferTest`, and `ForwardPathGenAiSpansTest` mutate JVM-global `ConfigurationProperties.llm*` statics but were not in the sequential Surefire phase, causing intermittent cross-test contamination under `parallel=classes`. Moved all three to the sequential phase (symmetric exclude/include lists, validated by `ParallelStaticStateGuardTest`).
- **Chaos auto-halt unbounded accumulation when threshold is non-positive** — when `chaosAutoHaltEnabled=true` but `chaosAutoHaltErrorThreshold` was 0 or negative, `recordError()` appended timestamps to the sliding window without ever evicting them (the early-return skipped eviction but ran after the `addLast`). The threshold check now runs before recording, so a non-positive threshold is a no-op (no timestamps accumulated, no halt). Also removed dead `Sparkline.tsx` component (zero production imports) and corrected stale consumer docs that said gRPC-bidi inbound breakpoints were "not yet intercepted (future work)" — they shipped in `a8f4bb0e2`.
- **Dashboard Chaos/Composer polish + demo Experiments** — the Chaos → Experiments stage fields (Error status, Error prob, Latency ms, Drop prob) were widened so their labels are no longer truncated; the Composer "Editing … changes update this expectation." info box now vertically centres its text with the (i) icon; the operating-mode (SPY/SIMULATE/CAPTURE) dropdown tooltip suppresses itself while the menu is open so it no longer overlays the menu items; and the demo-data populate script (`npm run demo`) now registers a multi-stage looping chaos experiment so the Chaos → Experiments section shows live data out of the box.
- **Dashboard correctness and UX fixes** — a batch of dashboard fixes: the action-type / LLM-provider filter chips are now labelled "expectations only" so they no longer look like a no-op on the request and traffic panels; request-panel row numbers are correct while a search filter is active (numbered against the filtered list, not the full list); the "Generate Stub" dialog now shows **all** returned suggestions instead of silently keeping only the first; panel count chips show the post-filter count (e.g. `2 / 50`) when a filter or search is active; clearing server logs no longer blanks the local expectations/recorded lists without refetching them; panel search now matches field values rather than serialised JSON keys (so searching `value`/`id`/`type` no longer matches every row); the ⌘L "clear logs" shortcut now asks for confirmation like the menu action; copy-to-clipboard failures surface a "Copy failed" tooltip instead of failing silently; the dashboard honours a `?secure=true|false` query-param override so it can target an HTTPS MockServer when itself served over HTTP; the Traffic "Replay" dialog warns that it makes a real, side-effecting call to the original target (with an extra warning for non-GET methods); and the Drift, Breakpoints and Chaos panels degrade gracefully (an "unavailable on this server" notice) instead of showing a raw error when pointed at an older MockServer that lacks those endpoints. Editing an existing LLM conversation and changing the number of turns no longer leaves a duplicate orphaned scenario on the server — the old turns are now cleared before the replacement is registered, and the action is clearly labelled as a replacement. The dashboard service-chaos form now validates `errorStatus` (100–599) and `errorProbability` (0.0–1.0) inline and blocks submission of out-of-range values rather than failing with a server 400.
- **Dashboard adversarial-review correctness fixes (batch 1)** — five defensive fixes from a full adversarial review of the dashboard UI: (1) the Breakpoints panel held paused exchanges in an **unbounded** list that was never cleared on reconnect, so a broad breakpoint matcher (e.g. path `.*`) could exhaust browser memory — the list is now capped (oldest dropped) and cleared when the callback WebSocket disconnects, since held items reference a clientId the server replaces on reconnect; (2) the SSE parser split only on `\n`, so real CRLF-terminated streams mishandled the `[DONE]` sentinel and leaked stray carriage returns into reassembled text — line endings are now normalised first; (3) the Prometheus metrics parser retained non-finite (`+Inf`/`-Inf`/`NaN`) sample values that poisoned chart auto-scaling and numeric formatting (`toFixed` → `"Infinity"`) — non-finite values are now skipped (histogram `le="+Inf"` is unaffected, as it lives in the label, not the value); (4) the TCP and gRPC service-chaos TTL countdowns decremented against the HTTP poll's timestamp (a different poll loop that kept advancing while those sections were collapsed and their data frozen), making the countdowns drift — each dataset now tracks its own poll timestamp; (5) the Traffic detail pane is wrapped in an error boundary so a parser exception on a malformed captured body shows an inline error instead of unmounting the whole inspector.
- **Dashboard Composer round-trip + validation fixes (batch 2)** — editing an existing expectation in the Mocks composer silently lost some body matchers: a **GraphQL** matcher was read back from the non-existent JSON field `graphql` instead of `query` (the actual wire field), so the query was wiped on every edit; and a **WASM** body matcher had no read-back branch at all, so it fell through to a raw JSON dump. Both now round-trip correctly (covered by a new reader↔writer round-trip test). In addition, the Register button now validates **base64** inline for the binary body matcher, the Error action's response bytes, and the Binary response action — malformed base64 is blocked with a clear reason instead of failing as an opaque server 400 (or throwing in the generated Java `Base64.getDecoder().decode(...)`).
- **Dashboard performance fixes (batch 3)** — three rendering/polling efficiency fixes from the adversarial UI review: (1) the **Log Messages** panel re-ran its grouped-entry text computation for every log group on every ~1/sec WebSocket snapshot because `LogGroup` was not memoised and received a fresh per-row toggle closure — it's now `React.memo`-wrapped and the panel passes a single stable toggle callback, so unchanged groups skip the work; (2) all interval-**polling** views (Metrics, Drift, Chaos, Breakpoints, AsyncAPI) now **pause while the browser tab is hidden** and resume on return, instead of scraping/parsing in the background indefinitely (with an in-flight guard so returning to the tab can't fork a duplicate poll loop); (3) the **Traffic** inspector caches each captured request's parsed summary (SSE reassembly + base64 decode) keyed on the item reference, so it no longer re-parses every row on every snapshot and every search keystroke.
- **Dashboard accessibility fixes (batch 4a)** — keyboard and screen-reader fixes from the adversarial UI review: the expand/collapse chevrons on log entries, request/expectation rows, log groups, and match-failure ("because") sections are now real focusable controls with `aria-label` (Expand/Collapse) and `aria-expanded`, so they are keyboard-operable and announce their state (previously they were unlabelled icons inside mouse-only rows); the AppBar clear/reset button gained an `aria-label`; the connection-error banner and notification toasts are now `role="alert"` live regions; and ten Tools-menu dialogs (Clock, Configuration, OIDC, CRUD, AsyncAPI, OpenAPI/WSDL import, Pact, Explain-unmatched, Generate-stub) now expose an accessible name via `aria-labelledby`.
- **Dashboard destructive-action confirmations + dialog reset (batch 4b)** — bulk/irreversible dashboard actions that previously fired on a single click now route through the existing confirmation dialog: clear-all breakpoint matchers (which orphans paused exchanges), clear-all HTTP/TCP/gRPC service chaos, clear drift records, delete a server-filesystem file, and delete a WASM module / clear gRPC descriptors. Per-item Remove on low-stakes lists is unchanged. Separately, several Tools-menu dialogs (AsyncAPI, OIDC, CRUD, File store, and a stale-error clear on Clock/Configuration) now reset their form fields and success/error banners on close, so reopening no longer shows stale pasted content or outcome messages.
- **Dashboard Composer generated-Java formatting (batch 6)** — the "Forward with override" action produced badly mis-indented Java in the Composer's Java preview (the inner `request()` landed at column 0 with its builder calls jammed far to the right) because the override block was indented once when built and again by the outer re-indent pass. It now emits cleanly nested, consistently-indented Java. Added a compile-time exhaustiveness guard to the action-to-Java generator so a future action type can't silently emit `undefined`.
- **Dashboard text-clipping / truncation fixes (batch 7)** — across the dense data views, values that were silently clipped with no way to read the full text now ellipsis-truncate with a tooltip showing the complete value, via a new reusable `TruncatedText` component. Sites fixed: the Breakpoints panel's stream-frame body (which was double-truncated — cut to 40 chars *and* CSS-clipped) plus its id / clientId / matcher / stream-id cells (full UUIDs now recoverable), the Sessions request chips / lane headers / token-cost chips, the Drift expected/actual value cells, the Traffic master-list host+path, the Conversation model/predicate chips, and the collapsed log-entry summary. Also added `minWidth:0` flex fixes so a long host/FQDN in the service-chaos rows and the filter panel no longer forces controls to wrap.
- **Dashboard Composer feature completeness (batch 8)** — the Mocks composer can now author expectation fields that previously could only be set via JSON/the API (and were silently dropped when editing such an expectation in place): a **static response delay**, **reason phrase**, and **response cookies**; a dedicated **JSON body matcher** with a **STRICT / all-matching-fields** match type; and a **substring** toggle for string body matchers. Each is wired through the form, the Java/JSON/curl preview, and the edit-existing round-trip, with the correct server field names. Editing an existing JSON-body expectation stored in the server's default form (a bare JSON object) now correctly comes back as a JSON matcher instead of an exact string.
- **Dashboard responsive form layouts** — the dense multi-field forms that previously went ragged and clipped on narrow viewports now reflow cleanly: the HTTP/TCP/gRPC service-chaos register & edit forms, and the Composer's chaos and side-effect panels, lay their fields out in a responsive CSS grid (`auto-fit` equal columns) instead of fixed-width flex-wrap rows, so columns stay aligned and fields fill the available width at any size. The AppBar's 12-view toggle strip now scrolls horizontally as a unit on narrow windows instead of wrapping mid-group.
- **Dashboard review polish** — four UI fixes from a full review pass: the "Diff two requests" dialog now shows the **diff result at the top** (above the editable request JSON) so it's the most visible thing, and **runs the diff automatically** when opened from the Traffic inspector's Compare flow (both requests already selected) instead of requiring a second button press; the Mocks composer's **Body type** dropdown is wider so "String (exact / subString)" is no longer truncated; and the **Sessions** view now shows a collapsible **Conversation** transcript per session (reusing the Traffic tab's provider chat-bubble views, rendering the last request in the session which carries the full accumulated message history), with a compact **Show Mermaid** link beneath it that opens the correlated agent-run call graph on demand.
- **ReDoS in the Ruby client binary launcher** (CodeQL `rb/polynomial-redos`, CWE-1333) — the trailing-slash strip in `BinaryLauncher.asset_url` used `base.sub(%r{/+\z}, '')`, whose `/+\z` sub-expression can restart at every `/` and backtrack quadratically on a base URL with a long slash run that doesn't end in `/` (relevant on Ruby < 3.2, which lacks the regex match cache). The base URL is operator-supplied via `MOCKSERVER_BINARY_BASE_URL`, so real-world exploitability is low. The trailing-slash strip is now done with a single linear non-regex scan (the regex is removed entirely), eliminating the ReDoS surface — an earlier attempt that merely anchored the regex with a negative look-behind (`%r{(?<!/)/+\z}`) kept the strip linear but did not clear the CodeQL alert. Behaviour is unchanged; added regression tests for interior-slash preservation and a 100k-slash pathological input.
- **Parallel-test isolation for new singleton tests + post-review polish for streaming breakpoints and chaos experiments** — moved `StreamFrameBreakpointRegistryTest`, `ChaosExperimentOrchestratorTest`, and `BreakpointRegistryTest` into the sequential Surefire phase (they mutate JVM-global singletons and flaked under `parallel=classes`); added a `default` case to the stream-frame decision switch in `NettyResponseWriter` to prevent unrecognised actions from hanging the stream; moved `streamId`/`reqMethod`/`reqPath` allocation inside the `streamBreakpointsActive` guard for zero overhead on the default-off path; added `lastTerminatedStatus` to `ChaosExperimentOrchestrator` so `getStatus()` reports `completed`/`stopped`/`halted_by_auto_halt` after an experiment ends; added stream breakpoint and chaos experiment endpoints to the OpenAPI spec; added consumer-facing docs for chaos experiments; fixed the BreakpointsPanel response "Path / Reason" column to show `'-'` instead of the request path when `reasonPhrase` is absent.
- **Startup crash when a properties file has entries** ([#2338](https://github.com/mock-server/mockserver-monorepo/issues/2338)) — MockServer 7.0.0 failed to start with `NoClassDefFoundError: Could not initialize class org.mockserver.configuration.ConfigurationProperties` (caused by a `NullPointerException` during static initialisation) whenever a `mockserver.properties` file — or the Helm chart's `app.config.properties` — contained any entries. The startup property-dump redaction added in 7.0.0 read its `SENSITIVE_SUBSTRINGS` set from the `PROPERTIES` static initialiser but declared it ~3000 lines later in the class, so it was still `null` when class initialisation ran (a static-init ordering bug). The redaction fields are now initialised before the property file is read, with a regression test that initialises `ConfigurationProperties` afresh against a populated property file.
- **Downstream `dependencyConvergence` failures** — consuming MockServer (e.g. `mockserver-client-java` with `MockServerContainer`) under the Maven Enforcer `dependencyConvergence` rule failed with multiple version-conflict errors, because MockServer's transitive version pins lived in the parent POM's `dependencyManagement`, which Maven does not export to consumers. Three changes address this: a new **`mockserver-bom`** to import (above); the slimmer client classpath (above); and pruning the stale `velocity-engine-core 2.3` that `velocity-tools-generic` dragged in alongside the `2.4.1` the build already uses (all 21 Velocity engine tests still pass). With the BOM imported, a client-only consumer's convergence errors drop from 17 to 0.
- **Latent undefined `${jetty.version}` in the parent POM** — three Jetty HTTP-client `dependencyManagement` entries referenced a `jetty.version` property that was only ever defined in the `examples/java` module, so the managed versions were unresolved for any other consumer of the published parent POM. The dead entries were removed from the parent and the `examples` module now declares its Jetty client versions explicitly.
- **Object mapper Swagger coupling made optional** — `ObjectMapperFactory` registered its Swagger/OpenAPI-coupled serializers (the schema serializers and the OpenAPI-derived `HttpRequestsPropertiesMatcher` serializer) unconditionally, so initialising the object mapper loaded `io.swagger.v3.oas.models.*` even on a client that never produces those objects. They are now isolated in a `SwaggerSerializers` helper and registered only when swagger-core is on the classpath, which is what lets `mockserver-client-java` exclude the Swagger/OpenAPI parser (eliminating the bulk of a client-only consumer's remaining `dependencyConvergence` conflicts). The single `com.github.fge` (json-tools) pretty-print call on the client-reachable path was replaced with a small `JsonPrettyPrinter`, and `jackson-datatype-jsr310` — used directly by the object mapper but previously only arriving transitively via the Swagger parser — is now a direct `mockserver-core` dependency. Server behaviour is unchanged (swagger-core is always present there).
- **Remaining non-Swagger convergence conflicts pruned** — with the Swagger parser excluded from the client, three transitive version splits remained for a client-only consumer: `slf4j-api` (older versions via `java-uuid-generator`, `json-path` and `com.networknt:json-schema-validator`), `jackson-annotations` (2.21 via the validator's Jackson 3 transitive) and `jakarta.xml.bind-api` (2.3.3 via `xmlunit-core`). `mockserver-core` now excludes those stale transitive edges; in every case it already declares the winning version directly (`slf4j-api` 2.0.18, `jackson-annotations` 2.22, `jakarta.xml.bind-api` 4.0.5), so its own resolved classpath is unchanged (255 XML/JSON-schema/JSON-path core tests still pass). A consumer depending only on `mockserver-client-java` now passes the Maven Enforcer `dependencyConvergence` rule with **zero** errors even without importing the BOM.

### Documentation

- **Interactive Breakpoints guide rewritten for the matcher + callback-WebSocket model** — the *Interactive Breakpoints* consumer page now documents the final feature: registering a request matcher with phases, resolving paused request/response/stream-frame exchanges interactively over the callback WebSocket (with the per-frame `PausedStreamFrameDTO`/`StreamFrameDecisionDTO` protocol and the `X-MockServer-BreakpointId` routing), the dashboard Breakpoints panel, the safety rails, and idiomatic examples for all seven supported clients (Java, Node, Python, Ruby, Go, .NET, Rust — PHP is not supported). The OpenAPI spec carries `clientId` on the matcher endpoints, and `docs/code/breakpoints.md` was consolidated (TL;DR + flow diagram, WS-callback-only resolution).
- **New consumer guides for the newest features** — added three site pages: *LLM Response Mocking* (`/mock_server/llm_response_mocking.html`) showing how to mock OpenAI / Anthropic / Gemini / Bedrock / Azure OpenAI / Ollama responses via plain expectations — including conversations, streaming and cost budgets — without needing an AI agent or MCP; *Interactive Breakpoints* (`/mock_server/interactive_breakpoints.html`) walking through pausing, inspecting, modifying and resuming requests/responses; and *Observability* (`/mock_server/observability.html`) covering Prometheus metrics (including LLM token/cost counters) and OpenTelemetry trace export with W3C context propagation. Each is linked into the site navigation.
- **Consumer doc corrections** — corrected the *HTTPS & TLS* page to state the real default TLS protocols (`TLSv1,TLSv1.1,TLSv1.2`, not "TLS 1.2 and 1.3"), matching the configuration-properties page; clarified that `disableLogging` disables **all** logging (not just system-out) on the *Performance* page; fixed the *Running MockServer* meta description ("Grunt", not "Gradle"); noted that the Kubernetes `httpGet` liveness probe example requires `MOCKSERVER_LIVENESS_HTTP_GET_PATH` to be set (the path is off by default); reordered *Getting Started* so the common-path "Next Steps" precede the upgrade notes; and simplified the configuration-property precedence wording. Also corrected the internal `docs/code/configuration-reference.md` precedence order (properties file beats environment variable) to match the code.
- **Internal docs** — added `docs/code/chaos.md` (chaos experiments: ChaosExperimentOrchestrator, ordered stages, looping, auto-halt integration, safety limits, endpoints); documented `PUT /mockserver/replay` (request replay) and `PUT/GET/DELETE /mockserver/chaosExperiment` in `docs/code/request-processing.md`; updated `docs/code/dashboard-ui.md` to reflect twelve views (Breakpoints + Get-Started), the Breakpoints panel (request/response/stream phases), the Get-Started onboarding view, Traffic-view Replay and Compare buttons, and the Composer snippet palette; added `generateRealisticExampleValues`/`SampleDataGenerator` coverage to `docs/code/domain-model.md`; added `chaos.md` and `breakpoints.md` rows to `docs/README.md`; added chaos.md and broadened breakpoints row in `AGENTS.md`.
- **Internal docs corrections** — corrected `docs/code/breakpoints.md`: removed stale "Future work" section (all four items shipped — HTTP/3-gRPC, gRPC-bidi inbound, and both dashboard UI features); added `GrpcBidiStreamHandler.handleData` and `GrpcBidiRouterHandler` to the Inbound frame breakpoints key-classes; updated `docs/README.md` doc counts (code: 21→24, operations: 13→15); replaced "error-class" with "destructive" in `docs/code/metrics.md` to match `ChaosAutoHaltMonitor.DESTRUCTIVE_FAULT_TYPES`; updated `docs/code/dashboard-ui.md` Streams tab description to reflect the shipped direction badge and gRPC-bidi inbound frames; added three missing code-doc rows (`ai-protocol-mocking.md`, `llm-codec-fixtures.md`, `llm-security-audit.md`) to the `AGENTS.md` reference table.

## [7.0.0] - 2026-06-06

This cycle centres on **first-class LLM / AI-agent mocking** and a major **platform modernisation**, alongside broader resilience-testing and dashboard improvements. Highlights (see the per-item entries below for detail):

- **HTTP/3 streaming responses** — SSE, chunked proxy forwarding, and LLM streaming are now fully supported over HTTP/3 (QUIC). Each body chunk is sent as an HTTP/3 DATA frame with backpressure via `StreamingBody.requestMore()`; the QUIC stream is cleanly shut down on completion or error. Bundled native QUIC removes the need for a separately downloaded BoringSSL library.
- **TPROXY (IP_TRANSPARENT) transparent proxy** — a new default-off `transparentProxyTproxy` configuration property enables `IP_TRANSPARENT` socket binding so that with iptables TPROXY rules the kernel preserves the original destination as the listening socket's local address, which MockServer reads via `channel.localAddress()` — avoiding the conntrack `SO_ORIGINAL_DST` lookup used with REDIRECT rules. Requires Linux, `epoll` transport, and `CAP_NET_ADMIN`. Verified end-to-end with a real Docker `NET_ADMIN` integration test.
- **Testcontainers 1.21.4** — upgrades from 1.20.6, fixing `DockerClientFactory.isDockerAvailable()` returning `false` on Docker Desktop 4.67 / Engine API 1.54 (docker-java 3.4.2 probe fix).
- **Clustered MockServer state (opt-in)** — a new `mockserver-state-infinispan` module provides an embedded Infinispan `StateBackend` that can replicate expectations and scenario state across a JGroups cluster. Single-node behaviour is completely unchanged (the in-memory `StateBackend` remains the default). New configuration properties: `stateBackend`, `clusterEnabled`, `clusterName`, `clusterTransportConfig`, `blobStoreType`.
- **LLM / AI-agent mocking suite** — provider-correct mock completions and streaming for seven providers (Anthropic, OpenAI, OpenAI Responses, Azure OpenAI, Gemini, Bedrock, Ollama), with embeddings for OpenAI and Azure OpenAI; multi-turn scripted conversations with per-session isolation and deterministic prompt normalisation; and a runtime-LLM client SPI (off unless configured, fails closed) that powers the opt-in features. A broad MCP toolset drives it from an agent: `mock_llm_completion`, `create_llm_conversation`, `verify_tool_call`, `explain_agent_run` (with a correlated call graph), `verify_structured_output`, `verify_cost_budget`, `detect_llm_drift`, `mock_adversarial_llm_response`, and `run_mcp_contract_test`.
- **Agent resilience & correctness testing** — structured-output (JSON-Schema) validation on both the response path (`outputSchema`, fail-soft) and the verification path (`verify_structured_output`); a deterministic CI **cost-budget gate** (`verify_cost_budget`) over a built-in pricing table; declarative **LLM fault/chaos profiles** (probabilistic provider errors, mid-stream truncation, malformed SSE) plus a **stateful request-quota** rate limit; VCR record/replay with strict mode and body/header redaction; a prompt-injection / adversarial-response harness; and OpenTelemetry GenAI span + metrics export. The dashboard surfaces all of it (conversation wizard, sessions & call-graph, metrics view, export).
- **HTTP chaos/fault injection** — a general `HttpChaosProfile` (probabilistic error status + latency) attachable to any mocked **or forwarded** response, making MockServer usable as a chaos proxy for unreliable upstreams.
- **Platform modernisation (breaking)** — minimum runtime raised to **Java 17**; full **Jakarta EE 10 / Servlet 6** migration (Spring 7 / Boot 4, Tomcat 11, Jetty 12, Jersey 4, Netty 4.2); `json-schema-validator` 3.x; a bundled DataFaker template helper; and ZGC tuning guidance.

### Security

- **Released Docker images are now cosign-signed by digest** (Docker Hub and ECR Public), using the same signing key infrastructure as the Helm OCI chart. Consumers can verify image provenance with `cosign verify`. Signing is non-fatal in the pipeline if the key is unavailable, so it never blocks a release.
- **Website security hardening** — the documentation site (mock-server.com) now sends `Strict-Transport-Security`, `Content-Security-Policy`, `X-Content-Type-Options`, `X-Frame-Options`, and `Referrer-Policy` response headers via CloudFront, and the domain publishes CAA records pinning certificate issuance to Amazon.
- **Build/release infrastructure hardening (internal)** — least-privilege scoping of CI secrets per Buildkite agent queue, removal of release-only permissions (ECR push) from the PR-build queue, secrets passed to release containers via `0600` files instead of `docker -e` environment variables, robust git-push-token cleanup, scoped cross-account `AssumeRole` (ExternalId) and tfstate IAM, full VPC flow logging, GuardDuty→SNS alerting, CloudTrail data-events on secrets/state, and SSE-KMS on the state and AWS Config buckets. See `docs/infrastructure/aws-infrastructure.md`, `docs/infrastructure/ci-cd.md`, and `docs/operations/website.md`.

### Added

- Added a **daily performance-regression pipeline** (notify-only) that guards response latency, throughput, and CPU/memory against drift across releases. It runs on a dedicated, pinned, on-demand, scale-to-zero Buildkite `perf` queue and fires once per day only when `master` moved since the last run. Each run measures four behaviours (mock match, forward/proxy, Velocity template, large-body) over HTTP and HTTPS/HTTP-2 (`k6/regression.js`), a sustained resource-growth run that surfaces "increases over time" regressions such as the issue #2329 O(n) log-eviction CPU climb (`k6/growth.js`, CPU/heap/latency slope ratios), and the JMH `MatchingBenchmark` allocation backstop. Results are persisted to S3 and each run is compared against a rolling median+MAD baseline of recent runs, posting a Buildkite annotation table when a metric regresses. See `docs/operations/performance-tuning.md`.

#### LLM & AI-agent mocking
- Added a dedicated **`retrieve_logs` MCP tool** so an AI assistant debugging a failing test can pull MockServer's recorded log messages (request matching, mismatches, actions and errors) directly. It is a thin, discoverable wrapper over the existing LOGS retrieval path (shared with `raw_retrieve`), with an optional `correlationId` filter (trace one request's full lifecycle) and a `limit` (most-recent N, default 100, max 500). This fills the gap left by its sibling tools `retrieve_recorded_requests` / `retrieve_request_responses`, which already existed. See the AI/MCP tools page.
- Added a **runtime-LLM client SPI** (`org.mockserver.llm.client`) that lets MockServer call a real LLM you already run, as the foundation for opt-in features such as drift detection and exploratory semantic matching. Mirrors the existing codec registry: an `LlmClient` per provider (Ollama, OpenAI, OpenAI Responses, Azure OpenAI, Anthropic, Gemini, Bedrock) registered in `LlmClientRegistry`, an immutable `LlmBackend` config (with the API key redacted in logs), and a three-layer `LlmBackendResolver` (provider env vars → `mockserver.llmProvider`/`llmApiKey`/`llmModel`/`llmBaseUrl` → named-backends JSON via `mockserver.llmBackendsConfig`). All runtime-LLM use goes through `LlmCompletionService`, which is **off unless a backend is configured**, **fails closed** on any timeout/error/non-2xx (never flipping a deterministic result), and caches per normalised prompt for reproducibility. Ollama is the reference backend (no key, local); Bedrock builds the Anthropic-on-Bedrock request and relies on the `headers` escape hatch pending automatic SigV4 signing. See the configuration properties page and `docs/code/llm-mocking.md`.
- LLM conversation mocks can now opt into deterministic **prompt normalisation** before the `latestMessageContains` / `latestMessageMatches` predicates are evaluated, so a match is not blocked by cosmetic differences in dynamically-assembled agent prompts. A new `normalization` block on `conversationPredicates` (also exposed per-turn in the `create_llm_conversation` MCP tool and the dashboard conversation wizard) supports collapsing whitespace, lowercasing, sorting JSON object keys, dropping built-in volatile values (ISO-8601 timestamps, UUIDs, `req_`/`msg_`/`call_` ids), and dropping named JSON fields. Normalisation is pure and idempotent — it never makes a test flaky — and has no effect unless a text predicate is set. See the AI/MCP tools page and `docs/code/llm-mocking.md`.
- Added two MCP tools for **agent-run analysis and tool-call assertions**, both backed by a new deterministic `org.mockserver.llm.analysis.AgentRunAnalyzer` that reconstructs an agent run by decoding the LLM requests MockServer recorded. `verify_tool_call` asserts that an agent called a named tool a given number of times (`atLeast`/`atMost`, with an optional regex over the tool-call arguments); `explain_agent_run` summarises the run's structure (message and assistant-turn counts, the ordered tool-call sequence, tool results, and the latest message role). Read-only and offline — no LLM call. See the AI/MCP tools page and `docs/code/llm-mocking.md`.
- Added a **correlated agent-run call graph**. `AgentRunAnalyzer.buildCallGraph` reconstructs a recorded run as a graph — a node per message and per assistant tool call, with `NEXT` (sequence), `INVOKES` (turn→tool call), and `RESULT` (tool call→its result, correlated by tool-call id) edges — exposed in the `explain_agent_run` MCP result as a `callGraph` field. The dashboard **Sessions** view renders it per session (a "Call graph" button loads it via `explain_agent_run`): each step shows the message role and the tool calls it made, with a result indicator, plus a copyable Mermaid `flowchart` source. Deterministic and read-only. See `docs/code/llm-mocking.md`.
- Added opt-in, **exploratory semantic prompt matching** for LLM conversations: a `semanticMatch` turn predicate (the intent the latest message should express) judged by a runtime LLM via the client SPI. It is **off by default and never on the assertion path** — the predicate is ignored unless `mockserver.llmSemanticMatchingEnabled` is set *and* a runtime backend resolves, so deterministic matching is never affected by default. Non-deterministic by nature (a live LLM judge), so it is documented for exploration only, never for CI assertions; fails closed (a non-affirmative/empty/errored judge does not match). Exposed in the Java `TurnBuilder.whenSemanticMatch`, the `create_llm_conversation` MCP tool, and the dashboard wizard (clearly flagged exploratory). See `docs/code/llm-mocking.md`.

#### LLM resilience, validation & cost testing
- Added a **`verify_structured_output` MCP tool**: validate that the structured (JSON) output of recorded LLM responses conforms to a JSON Schema. It decodes each recorded response for a given provider (via the runtime-LLM client SPI), extracts the assistant's output text, and checks it against the schema — so you can assert that an agent (or a mocked model) produced schema-valid structured output. Read-only and deterministic; responses with no text output are reported separately as skipped, and the result gives per-response conformance with validation errors. See the AI/MCP tools page and `docs/code/llm-mocking.md`.
- A mock LLM completion can now declare an **`outputSchema`** (a JSON Schema) that its response `text` is expected to conform to. As the response is encoded, MockServer validates the configured text against the schema and, on a mismatch, **fail-soft**: the response body is returned exactly as configured but an `x-mockserver-structured-output-invalid` diagnostic header is added and a warning logged — so a malformed structured-output fixture is surfaced immediately while a deliberately non-conforming fixture still returns unchanged. A blank schema, absent text, or a malformed schema are all treated as "nothing to check" and never affect the response. Exposed on the Java `Completion.withOutputSchema(...)`, the `outputSchema` field in expectation JSON, and the `mock_llm_completion` MCP tool (string or inline object). Complements the read-side `verify_structured_output` tool. See the AI/MCP tools page and `docs/code/llm-mocking.md`.
- Added a **`verify_cost_budget` MCP tool**: a deterministic, read-only cost gate for agent runs. It decodes each recorded LLM response for a provider (via the runtime-LLM client SPI), sums the input/output tokens from each response's usage, prices them with a new built-in pricing table (`org.mockserver.llm.cost.LlmPricing`, mirroring the dashboard's `llmPricing.ts` — same prefixes/rates), and asserts the total estimated USD cost is at or below `maxCostUsd`. The model can be pinned via a `model` param or read per-response from the recorded request body; responses with no usage are skipped and responses whose model has no known price are reported as `unpriceable` and excluded from the total. The result gives token/cost totals, `withinBudget`, and a per-response breakdown. Pricing is public list pricing captured 2025-Q4 (an estimate, not an invoice). See the AI/MCP tools page and `docs/code/llm-mocking.md`.
- Added declarative **LLM fault/chaos profiles** for resilience testing, attachable to any mock LLM response (`mock_llm_completion`, each `create_llm_conversation` turn, the Java `LlmConversationBuilder`, and raw expectation JSON via a `chaos` block). Supports probabilistic provider errors (e.g. 429/529 with a `Retry-After` header), mid-stream truncation of an SSE stream (keep a leading fraction of events), and appending a malformed (broken-JSON) SSE chunk. Errors are deterministic at probability 0.0/1.0 and reproducible at fractional probabilities via a `seed`; truncation and malformed-SSE are always deterministic. A new `LLM_CHAOS_INJECTED_COUNT` metric tracks injections. The dashboard conversation wizard exposes the profile per turn. See the AI/MCP tools page and `docs/code/llm-mocking.md`.
- Added a **stateful request quota** to the LLM chaos profile — a deterministic fixed-window rate limit, the stateful counterpart to the existing probabilistic 429. Set `quotaName`, `quotaLimit`, and `quotaWindowMillis` (optional `quotaErrorStatus`, default 429) on a `chaos` block and requests beyond the limit within the window are rejected with that status and the `retryAfter` header. Expectations sharing a `quotaName` share one counter (model an upstream account limit across several mocks); the count resets when the window elapses and on server reset. Backed by a new process-wide, thread-safe `org.mockserver.llm.LlmQuotaRegistry` (injectable clock for deterministic tests). Exposed in expectation JSON, the `mock_llm_completion`/`create_llm_conversation` `chaos` MCP parameter, and the Java `LlmChaosProfile`. A misconfigured/partial quota fails open (never rate-limits). See the AI/MCP tools page and `docs/code/llm-mocking.md`.
- Added a **prompt-injection / adversarial-response harness** for testing agent resilience. A new `mock_adversarial_llm_response` MCP tool returns a curated adversarial payload as the mock LLM response — prompt-injection ("ignore previous instructions…"), jailbreak persona-swaps, data-exfiltration requests, malformed/truncated JSON, an empty response, and an over-long repetition — so you can verify your agent *resists* hostile or malformed model/tool output. Backed by `AdversarialResponseLibrary` (deterministic; the payloads are benign test fixtures, not working exploits). A defensive testing aid. See the AI/MCP tools page and `docs/code/llm-mocking.md`.
- Added **drift detection** for LLM fixtures (`detect_llm_drift` MCP tool): replays a recorded cassette's exchanges against the live provider (via the runtime-LLM client SPI) and reports **structural** drift — new/removed fields and type changes in the responses — not semantic differences, so benign wording changes never flag. Built on a reusable, pure `StructuralShapeDiff` and a `DriftDetector` that **fails closed** per exchange (a network error or non-2xx live response is reported as could-not-check, never as drift, never thrown). Off unless a runtime backend is configured. Intended for an opt-in/scheduled CI lane (real API keys + tokens), never the per-commit build. See the AI/MCP tools page and `docs/code/llm-mocking.md`.
- Completed the **VCR (record/replay) toolkit** for LLM fixtures with three additions. (1) **Strict mode** — `load_expectations_from_file` accepts `strict` (or set `mockserver.llmVcrStrict`), which registers a low-priority catch-all per cassette path so a request matching no recorded fixture returns HTTP 599 instead of silently falling through. (2) **Body-field redaction** — `record_llm_fixtures` accepts `redactBodyFields` (or set `mockserver.fixtureBodyRedactFields`) to redact named JSON fields from recorded request/response bodies, complementing the existing header redaction. (3) **Replay field normalisation** — `load_expectations_from_file` accepts `normalizeRequestBodyFields` to drop volatile JSON fields from each recorded request body and match the remainder loosely (ignoring extra fields), so per-run values (request ids, timestamps) do not block replay. These are operational settings exposed via config and MCP. See the AI/MCP tools and configuration properties pages.

#### HTTP chaos & protocol contract testing
- Added a **time-to-live (auto-revert) to service-scoped chaos** — an optional `ttlMillis` on a `PUT /mockserver/serviceChaos` registration makes the chaos automatically revert after that many milliseconds (a "dead-man's switch" so a fault self-heals even if the matching clear is never sent — e.g. an external chaos orchestrator crashes mid-experiment). It is also the one-shot time-box form: a single call breaks a host for a bounded window. Expiry is measured with the controllable clock (real-time by default, deterministic under `PUT /mockserver/clock`) and is applied lazily on the next lookup. Exposed via the endpoint, the Java/Node/Python/Ruby clients (`setServiceChaos(host, chaos, ttlMillis)` / `ttl_millis`), and the `manage_service_chaos` MCP tool. See the [Chaos Testing](/mock_server/chaos_testing.html#service_scoped_chaos) page.
- Added **service-scoped chaos** — register one `HttpChaosProfile` for an upstream host and have it applied to all matched forwards to that host, instead of attaching a `chaos` block to every forwarding expectation (the "break service X" control for running MockServer as a chaos proxy). Manage it through a new control-plane endpoint `PUT/GET /mockserver/serviceChaos` (`{"host":...,"chaos":{...}}` to register, `{"host":...,"remove":true}` to remove, `{"clear":true}` to clear all), protected by control-plane authentication. Resolution happens only on the matched-forward path keyed by the request `Host` header (case-insensitive, port-ignored); an expectation's own `chaos` always takes precedence, the anonymous proxy fall-through is unaffected, and registrations clear on server reset. Backed by a new process-wide `org.mockserver.mock.action.http.ServiceChaosRegistry`. Convenience wrappers are exposed in all four clients (`setServiceChaos`/`removeServiceChaos`/`clearServiceChaos`/`serviceChaosStatus` in Java/Node, the snake-case equivalents in Python/Ruby) and via the `manage_service_chaos` MCP tool. See the [Chaos Testing](/mock_server/chaos_testing.html#service_scoped_chaos) page.
- Added **gradual degradation** to the HTTP `chaos` block — a `degradationRampMillis` that linearly ramps `errorProbability` and `dropConnectionProbability` from 0 up to their configured values over the window from the expectation's first match, modelling a dependency that deteriorates over time (for alerting / SLO-burn tests). The ramp is measured with MockServer's controllable clock, so it is deterministic under clock freeze/advance with no real-time waiting; only the probabilistic rates ramp (latency, body corruption, slow response and quota are unaffected). Exposed in expectation JSON, the Java/Node/Python/Ruby clients, and the `create_expectation` `chaos` MCP parameter. See the [Chaos Testing](/mock_server/chaos_testing.html#gradual_degradation) page.
- Added a **stateful request quota** to the HTTP `chaos` block — a deterministic fixed-window rate limit, the HTTP counterpart of the existing probabilistic 429 and of the LLM quota. Set `quotaName`, `quotaLimit` and `quotaWindowMillis` (optional `quotaErrorStatus`, default 429) and requests beyond the limit within the window are rejected with that status and the `retryAfter` header. Expectations sharing a `quotaName` share one counter (model an upstream account limit across several mocks); the count resets when the window elapses and on server reset. The quota gate takes priority over the probabilistic error and the body/slow faults (after connection-drop). Backed by a new process-wide, thread-safe `org.mockserver.mock.action.http.HttpQuotaRegistry` (separate from the LLM quota registry). Exposed in expectation JSON, the Java/Node/Python/Ruby clients, and the `create_expectation` `chaos` MCP parameter; metered as `fault_type=quota`. See the [Chaos Testing](/mock_server/chaos_testing.html#request_quota) page.
- Added a **slow (dribbled) response** fault to `HttpChaosProfile` — `slowResponseChunkSize` + `slowResponseChunkDelay` trickle the response body to the client in small chunks with a delay between each (via chunked transfer-encoding), for testing read timeouts and slow-network handling (distinct from `latency`, which delays the whole response by a fixed amount). Both fields are required; deterministic; applies to the real mocked or forwarded response within the active count and outage windows; skipped for streaming bodies; metered as `fault_type=slow`. Exposed in expectation JSON, the Java/Node/Python/Ruby clients, and the `create_expectation` `chaos` MCP parameter. See the [Chaos Testing](/mock_server/chaos_testing.html#slow_response) page.
- Added **response-body corruption** faults to `HttpChaosProfile` — `truncateBodyAtFraction` keeps only a leading fraction of the body bytes (e.g. `0.5` returns the first half, `0.0` empties it) and `malformedBody` appends a broken-JSON fragment so the payload fails to parse, for testing client-side body-parsing and partial-response resilience. Both are deterministic (no probability draw), apply to the real mocked or forwarded response within the active count and outage windows, preserve the `Content-Type` and drop any stale `Content-Length` (the encoder then sets the correct length) so the response stays well-framed, and are skipped for streaming bodies. Connection-drop and error injection still take priority (an injected error body is never corrupted). Exposed in expectation JSON, the Java/Node/Python/Ruby clients, and the `create_expectation` `chaos` MCP parameter; metered as `fault_type=truncate` / `fault_type=malformed`. See the [Chaos Testing](/mock_server/chaos_testing.html#body_corruption) page.
- Added **time-based outage windows** (`outageAfterMillis` / `outageDurationMillis`) to `HttpChaosProfile` — chaos becomes active a configurable time after the expectation's first match and (optionally) self-heals after a bounded duration, modelling a dependency that degrades for a transient window then recovers. The window is measured with MockServer's controllable clock, so it is deterministic under clock freeze/advance (`PUT /mockserver/clock`) with no real-time waiting; it composes with the count window and the probability fields.
- Added **connection-drop chaos fault** (`dropConnectionProbability`) to `HttpChaosProfile` — probabilistic TCP connection drops (no response sent) on both mocked and forwarded responses, simulating hard network failures. Drop faults take priority over error and latency injection (drop > error > latency). Uses a derived seed for independent but reproducible draws alongside `errorProbability`.
- Added declarative **HTTP chaos/fault injection** (`HttpChaosProfile`) for resilience testing, attachable to any expectation via a top-level `chaos` block. Supports probabilistic error-status injection (e.g. 500, 503, 429 with an optional `Retry-After` header) and latency injection. Works on **both mocked responses** (RESPONSE, RESPONSE_TEMPLATE, RESPONSE_CLASS_CALLBACK) **and forwarded/proxied responses** (FORWARD, FORWARD_TEMPLATE, FORWARD_CLASS_CALLBACK, FORWARD_REPLACE, FORWARD_VALIDATE), making MockServer usable as a chaos proxy for testing how applications handle unreliable upstream dependencies. Deterministic at `errorProbability` 0.0/1.0; reproducible at fractional probabilities via a `seed`. Exposed in the Java client (`ForwardChainExpectation.withChaos()`), REST API, and expectation JSON. See the new [Chaos Testing & Fault Injection](/mock_server/chaos_testing.html) documentation page.
- Added **count-based stateful faults** to the HTTP `chaos` block — a `succeedFirst` / `failRequestCount` request-count window so an expectation can succeed the first N matches, then fault the next M, then recover. Expresses fail-first-N-then-recover (retry/backoff testing), succeed-N-then-fail, and fail-only-the-Nth, on both mocked and forwarded responses; deterministic by match index, composes with `errorProbability`, and is backward compatible (no window fields = unchanged). See the [Chaos Testing](/mock_server/chaos_testing.html#stateful_count_based_faults) page.
- Added a **Driving MockServer from Chaos Orchestrators** guide showing how external chaos-engineering tools drive MockServer's service-scoped chaos through the control-plane endpoint — concrete inject/verify/revert recipes for Chaos Toolkit, AWS FIS (SSM RunShellScript), Azure Chaos Studio (Automation runbook / pipeline), LitmusChaos (BYOC cmdProbe/httpProbe), and any cron/CI/Step Functions scheduler — all using the `ttlMillis` dead-man's switch so a fault auto-reverts even if the orchestrator never sends the clear. See the [Chaos Orchestrators](/mock_server/chaos_testing_orchestrators.html) page.
- Added a **Chaos Proxy in Kubernetes** guide showing how to deploy MockServer as a chaos proxy in Kubernetes to inject faults into real service-to-service and external API calls — reverse-proxy, egress/forward-proxy, and sidecar deployment patterns with concrete Kubernetes manifests and expectation JSON examples. See the [Chaos Proxy in Kubernetes](/mock_server/chaos_testing_kubernetes.html) page.
- Added a **chaos-proxy example to the Helm chart** — a commented reverse-proxy + chaos `initializerJson` block in `values.yaml` and a "Chaos Proxy (fault injection)" section in the chart README, showing how to deploy MockServer in front of an upstream Service and inject faults through the chart's inline configuration. Links to the Chaos Testing and Chaos Proxy in Kubernetes guides.
- Added an **MCP server conformance tester** (`run_mcp_contract_test` MCP tool): point it at a target MCP (Model Context Protocol) server's Streamable HTTP endpoint and it runs the required JSON-RPC handshake and core methods — `initialize`, `notifications/initialized`, `ping`, `tools/list`, and unknown-method rejection (expects error code `-32601`) — validating the **shape** of each response (JSON-RPC 2.0 envelope and required result fields), never the semantics of any tool. Optionally exercises one `tools/call` (skipped by default, since a call may have side effects on the target). Fully deterministic and offline-from-LLMs (no model is involved); each request has a 10-second timeout. Backed by a network-free, unit-testable `McpContractTest` orchestrator with an injected transport. See the AI/MCP tools page and `docs/code/llm-mocking.md`.

#### Observability & dashboard
- Added an **active service-scoped chaos gauge** — a Prometheus `mock_server_active_service_chaos` gauge (when `metricsEnabled`) labeled by `fault_type` (`drop`/`error`/`latency`/`truncate`/`malformed`/`slow`/`quota`), reporting per fault type how many currently-active service-scoped chaos profiles are configured with that fault (a profile with several faults counts under each). It is a callback gauge that reads `ServiceChaosRegistry` at scrape time, so each series drops to 0 as profiles are cleared or their TTLs lapse (making `sum(mock_server_active_service_chaos) > 0` a natural "chaos still live" alert and letting you alert on a specific fault type), and it is mirrored over OTLP alongside the chaos-fault-injection counter. See the [Chaos Testing](/mock_server/chaos_testing.html) page.
- The dashboard **Metrics view "HTTP Chaos Faults" section now shows every fault type** the server emits (`drop`, `error`, `latency`, `truncate`, `malformed`, `slow`, `quota`) — previously only `error` and `latency` — with a per-fault-type chart of cumulative injections and a separate per-fault-type chart of the active service-scoped chaos gauge (plotted by type rather than as a single counter). Fault types are discovered from the scrape, so a future type renders automatically without a UI change. See `docs/code/dashboard-ui.md`.
- Added a **Chaos tab to the dashboard UI** for managing service-scoped chaos interactively (`ServiceChaosPanel`): register a host with an error status / error probability / drop probability / latency (and an optional TTL), see every active registration with a summary of its faults, watch the live TTL auto-revert countdown, and remove a single host or clear them all. It polls `GET /mockserver/serviceChaos` and drives the same control-plane endpoint as the clients and the `manage_service_chaos` MCP tool. The `/mockserver/serviceChaos` responses now carry CORS headers unconditionally (matching the metrics and MCP endpoints), so the dashboard works when served from a different origin (e.g. the UI dev server) without needing `enableCORSForAPI`. See the [Chaos Testing](/mock_server/chaos_testing.html#service_scoped_chaos) page and `docs/code/dashboard-ui.md`.
- Added optional **OpenTelemetry (OTLP) export**, in two independent, off-by-default parts. (1) **Metrics export** — MockServer's existing metrics (the same explicitly-defined gauges already exposed for Prometheus: `REQUESTS_RECEIVED_COUNT`, `RESPONSE_EXPECTATIONS_MATCHED_COUNT`, the LLM/SSE/chaos counters, etc.) can also be pushed to an OTLP collector as an alternative to Prometheus (`mockserver.otelMetricsEnabled`). Implemented as OTel observable gauges reading the current values, so the Prometheus and OTLP views stay in lock-step. (2) **GenAI span export** — MockServer emits one explicit OpenTelemetry GenAI semantic-convention span per LLM completion it serves (`gen_ai.system`, `gen_ai.request.model`, `gen_ai.usage.input_tokens`/`output_tokens`, `gen_ai.response.finish_reasons`, tool-call count) (`mockserver.otelTracesEnabled`). These are spans MockServer codes deliberately — **no auto-instrumentation** is added. Both use the OTLP HTTP/protobuf exporter with the JDK HttpClient sender (no gRPC/OkHttp), share `mockserver.otelEndpoint`, and are fail-soft (a setup error logs one line and never stops the server or affects a response). `io.opentelemetry.*` is relocated in the shaded JAR. See the configuration properties page.
- Added **JVM runtime metrics** to MockServer's Prometheus endpoint (`GET /mockserver/metrics`, when `metricsEnabled`): heap and non-heap memory (used / committed / max, labelled by `area`), live and daemon thread counts, and total GC collection count and time. Exposed via a dependency-free collector that reads JDK MX beans, so Grafana and the dashboard Metrics view can chart process health alongside the existing request/action counters.
- Added a **request-latency histogram** to MockServer's Prometheus endpoint (`mock_server_request_duration_seconds`, when `metricsEnabled`): classic histogram buckets from 0.5 ms to 10 s, recorded per request from receipt to response. Enables latency percentiles (p50 / p95 / p99 via `histogram_quantile`) in Grafana and the dashboard. Recording is fully gated behind `metricsEnabled`, so it adds nothing to the request path when metrics are off.
- Added a **Metrics view** to the dashboard UI: a new top-bar tab that polls MockServer's Prometheus endpoint (`GET /mockserver/metrics`) and renders live activity — request / matched / not-matched / forwarded counts with inline sparklines, a derived requests-per-second throughput chart, a per-action breakdown, **JVM heap / thread / GC panels**, and **request-latency percentiles (p50 / p95 / p99)** — the JVM and latency panels appear only when the server exposes those metrics — plus the served MockServer version. Time-series charts use `@mui/x-charts`, lazy-loaded so they add nothing to the initial dashboard load. It degrades gracefully: when MockServer is started without `metricsEnabled` the endpoint returns 404 and the view shows guidance to enable it (`-Dmockserver.metricsEnabled=true` / `MOCKSERVER_METRICS_ENABLED=true`). See `docs/code/dashboard-ui.md`.
- Recorded requests can now be exported as **cURL commands**. A new `CURL` value for the `/mockserver/retrieve` `format` parameter (valid for `type=REQUESTS` and `type=REQUEST_RESPONSES`) renders one `curl` command per recorded request via the existing `HttpRequestToCurlSerializer`; the expectation scopes return a clear "not supported" message. Surfaced in the dashboard Export page. See the configuration/retrieve docs.

#### Templating & runtime
- Added a **clock-control endpoint** (`PUT /mockserver/clock`, `GET /mockserver/clock`) for deterministic time-based testing. Freeze the server clock at a specific ISO-8601 instant, advance it by a duration in milliseconds, or reset it to real wall-clock time. The controllable clock affects response template date/time helpers (`now_iso_8601`, `now_epoch`, `now_rfc_1123`, and the `dates` helper object) and **expectation TimeToLive expiry**, so frozen time prevents expectations from expiring mid-test. Protected by control-plane authentication (JWT/mTLS) when configured. Limitation: event-log timestamps and JWT token issuance use a separate time source and are not affected. See the [Clearing, Resetting & Clock Control](/mock_server/clearing_and_resetting.html#clock_control) page.
- DataFaker (`net.datafaker:datafaker:2.5.4`) is now bundled as a template helper. A single shared `Faker` instance is exposed as `faker` in all three response-template engines (Velocity, Mustache, JavaScript) via `TemplateFunctions.BUILT_IN_HELPERS`, giving templates access to 250+ realistic-fake-data providers (`faker.name().firstName()`, `faker.internet().emailAddress()`, `faker.address().city()`, etc.). The instance is thread-safe and produces fresh random values on each call. See the consumer docs (response templates page) for the full provider list and per-engine syntax. Java 17 unlocked this — DataFaker 2.x requires Java 17; the previous Java 11 floor pinned us to the abandoned 1.9.0 line.
- Documented ZGC (`-XX:+UseZGC`) as a recommended GC for deployments with large heaps (≥ 4 GB) or deep `maxLogEntries` ring buffers. Java 17 ships production-ready ZGC; for matcher-path latency this can reduce p99 pauses from tens or hundreds of milliseconds (G1 under sustained allocation) into single-digit milliseconds. ZGC is not the default because typical MockServer fixtures run small heaps where Parallel/G1 are fine and ZGC's fixed memory overhead hurts sub-2 GB scenarios. Includes container-memory headroom guidance (size container limit at ~1.5× heap when using ZGC). See the performance tuning page on the website.

#### HTTP/3, transparent proxy & infrastructure

- **HTTP/3 streaming / SSE responses** (`Http3ResponseWriter`): `StreamingBody` responses (Server-Sent Events, chunked proxy forwarding, LLM streaming) are now fully supported over HTTP/3. `Http3ResponseWriter` subscribes to the `StreamingBody`, sends HTTP/3 headers immediately, and forwards each chunk as an HTTP/3 DATA frame with backpressure via `StreamingBody.requestMore()`. The QUIC stream output is shut down on completion or error. Resolves the previous limitation where only static response bodies could be returned over HTTP/3. See `docs/code/http3.md`.
- **gRPC streaming over HTTP/3 — server-streaming and bidi-streaming** (completes the gRPC-over-HTTP/3 work). A `grpcStreamResponse` expectation now streams each message as its own HTTP/3 DATA frame (with per-message delays) followed by a trailing `grpc-status` HEADERS frame; `HttpActionHandler` routes the `GRPC_STREAM_RESPONSE` action to the new transport-neutral `GrpcStreamResponseWriter` seam (implemented by `Http3GrpcResponseWriter`) for HTTP/3, while HTTP/2 is unchanged. A `grpcBidiResponse` expectation now drives true bidirectional streaming over a single full-duplex QUIC stream via the new `Http3GrpcBidiStreamHandler` (gated by the existing `grpcBidiStreamingEnabled` flag, same two-phase peek-then-consume matching and `responseInProgress` lifecycle as the HTTP/2 path). Message encoding and rule matching are shared across transports via new `GrpcStreamMessageEncoder` / `GrpcBidiRuleMatcher` core helpers. Covered by native-QUIC integration tests (`Http3GrpcStreamingIntegrationTest`). With this, gRPC over HTTP/3 reaches full parity with HTTP/2 (unary, server-streaming, bidi-streaming). See `docs/code/http3.md`.
- **Bundled native QUIC** — the `netty-incubator-codec-http3` dependency pulls in `netty-incubator-codec-native-quic` classifiers for all five supported platforms (`linux-x86_64`, `linux-aarch_64`, `osx-x86_64`, `osx-aarch_64`, `windows-x86_64`) automatically; no separately downloaded BoringSSL library is required. An in-JVM Netty QUIC-client integration test verifies the full pipeline parity including streaming, gated on `Quic.isAvailable()` so the suite degrades gracefully where native QUIC is absent.
- **TPROXY (`IP_TRANSPARENT`) transparent-proxy strategy** — a new default-off `transparentProxyTproxy` configuration property (`-Dmockserver.transparentProxyTproxy=true` / `MOCKSERVER_TRANSPARENT_PROXY_TPROXY=true`) enables `IP_TRANSPARENT` socket binding so that, with iptables TPROXY rules, the kernel preserves the original destination as the listening socket's local address — which MockServer reads directly via `channel.localAddress()`, as an alternative to the existing conntrack `SO_ORIGINAL_DST` strategy (REDIRECT rules). Requires Linux, the `epoll` transport (NIO unsupported), and `CAP_NET_ADMIN`. The transparent proxy `enabled` flag (`transparentProxyEnabled`) is unchanged; the new property selects the kernel mechanism only. Verified end-to-end with a real Docker `NET_ADMIN` integration test for both `SO_ORIGINAL_DST` and TPROXY paths. eBPF sockmap-based redirection is deferred (placeholder added). See `docs/infrastructure/service-mesh.md`.
- **Testcontainers 1.21.4** — upgraded from 1.20.6, picking up docker-java 3.4.2 which fixes `DockerClientFactory.isDockerAvailable()` returning `false` on Docker Desktop 4.67 / Engine API 1.54 (the 3.4.1 `/info` probe sent the wrong Content-Type header and received HTTP 400, causing a false-negative result). No API or behaviour change for callers; tests that previously skipped on Docker Desktop 4.67+ now run correctly.

#### Clustered state (opt-in, `mockserver-state-infinispan`)

- Added a **`StateBackend` SPI** in `mockserver-core` (`org.mockserver.state.StateBackend`) — a pluggable interface that abstracts all shared MockServer state into three store types: a versioned `KeyValueStore<ExpectationEntry>` (expectations), a `KeyValueStore<String>` (scenario states), `KeyValueStore<ObjectNode>` (CRUD entities per namespace), and a `BlobStore` (persisted cassettes and fixtures). `InvalidationListener` callbacks allow clustered implementations to trigger node-local rebuilds when a remote write arrives. The default implementation is `InMemoryStateBackend`, which wraps the existing concurrent data structures — single-node behaviour and performance are completely unchanged.
- Added `mockserver-state-infinispan`, a new optional Maven module providing an embedded Infinispan `StateBackend` that can replicate MockServer expectations and scenario state across a JGroups cluster. Classpath-auto-discovered when `mockserver.stateBackend=infinispan` is configured (via `StateBackendFactory` reflection — `mockserver-core` has no compile-time dependency on Infinispan). Two modes: **LOCAL** (single-node, no JGroups, heap-only Infinispan cache, permissive serialization allow-list) and **CLUSTERED** (`clusterEnabled=true`, REPL_SYNC caches, JGroups transport, explicit serialization allow-list covering exactly the MockServer domain types). Expectations and scenario states use `REPL_SYNC` so all writes are synchronously replicated to every cluster member. An Infinispan `@Listener(clustered=true)` fires `InvalidationListener.onChanged()` on remote writes, triggering `RequestMatchers.reconcileFromBackend()` on the receiving node to rebuild its local `HttpRequestMatcher` cache. Approximate eviction (`maxCount`) on the expectations cache matches the `maxExpectations` configuration property. See `docs/code/clustered-state.md`.
- New configuration properties for state clustering:

  | Property | Env var | Default | Description |
  |----------|---------|---------|-------------|
  | `mockserver.stateBackend` | `MOCKSERVER_STATE_BACKEND` | `memory` | Backend type: `memory` or `infinispan` |
  | `mockserver.blobStoreType` | `MOCKSERVER_BLOB_STORE_TYPE` | `filesystem` | Blob store type: `filesystem` or `memory` |
  | `mockserver.clusterEnabled` | `MOCKSERVER_CLUSTER_ENABLED` | `false` | Enable JGroups cluster transport |
  | `mockserver.clusterName` | `MOCKSERVER_CLUSTER_NAME` | `mockserver-cluster` | JGroups cluster identifier |
  | `mockserver.clusterTransportConfig` | `MOCKSERVER_CLUSTER_TRANSPORT_CONFIG` | _(built-in loopback)_ | Path to a custom JGroups XML transport config |

  Setting `stateBackend=infinispan` without `clusterEnabled=true` starts Infinispan in LOCAL mode (single-node, functionally equivalent to the default in-memory backend but adds Infinispan on the classpath). A misconfigured `stateBackend=infinispan` where the module is absent fails fast with `IllegalStateException` rather than silently falling through to in-memory (which would cause split-brain). Scenario-state transitions are atomic cluster-wide (versioned compare-and-set), and shared `Times` counters (per-expectation match limits) are enforced cluster-wide via backend CAS (exactly-once across nodes). Remaining node-local aspects: the request/event log and `verify()` are per-node (verification queries a single node's log). See `docs/code/clustered-state.md`.

### Changed
- Upgraded the Prometheus metrics client (`io.prometheus:prometheus-metrics-core`, `-exposition-formats`, `-model`) from `1.6.1` to `1.7.0`. Source- and behaviour-compatible (metrics are emitted only when `metricsEnabled`); the metrics exposition format is unchanged. `io.netty:netty-tcnative-boringssl-static` is deliberately **not** bumped alongside it — tcnative is version-locked to Netty (its per-platform classifier artifacts arrive transitively at Netty's tcnative version, so an independent bump breaks Maven `dependencyConvergence`); it is now in the Dependabot ignore list and is upgraded manually in lockstep with the `netty.version` bump.
- `LlmChaosProfile` now validates its numeric fields in its `withX` builder methods, matching the validation `HttpChaosProfile` already enforces: `errorProbability` / `truncateAtFraction` must be in `[0.0, 1.0]`, `errorStatus` / `quotaErrorStatus` in `[100, 599]`, and `quotaLimit` / `quotaWindowMillis` ≥ 1. An out-of-range value now throws `IllegalArgumentException` with a clear message when a profile is built via the Java client or parsed from the `chaos` MCP parameter, instead of being silently accepted.
- Reworked the dashboard **Export** page: choose the scope (Active expectations / Recorded requests) with a radio and the file format with a dropdown, instead of one long combined list. Added **JAVA** (expectations), **log-entries** (requests) and **cURL** (requests) formats, filtered by the chosen scope, and the best-effort caveat is now shown only when it applies. Export is now the first Library tab. The **run comparison** tool moved out of Library into a new **Compare** tab under **Sessions** (where it belongs, since it diffs sessions).
- Upgraded the **chicory** WASM interpreter (`com.dylibso.chicory:runtime`) from `0.0.12` to `1.7.5`, moving off the old pre-1.0 release onto the stable 1.x line. `WasmRuntime` is migrated to the new API (`Parser.parse(bytes)` → `WasmModule`, `Instance.builder(module).build()`, and `ExportFunction.apply(long…)` returning `long[]`). The experimental WASM custom-rule feature's behaviour and module ABI (`match(i32 ptr, i32 len) -> i32`) are unchanged.
- Upgraded `com.networknt:json-schema-validator` from 1.5.9 to 3.0.3. The 3.x line uses the `tools.jackson` (Jackson 3.x) namespace internally and `snakeyaml-engine` for YAML schemas. MockServer's external Jackson usage stays on 2.22.0; the two Jackson namespaces coexist because they are in different Java packages. `JsonSchemaValidator` is rewritten against the new `Schema` / `SchemaRegistry` / `SpecificationVersion` API and uses the string-based `getSchema(String, InputFormat.JSON)` and `validate(String, InputFormat.JSON)` entry points to avoid passing Jackson 2.x `JsonNode` objects into Jackson 3.x APIs. `PathType.JSON_PATH` is configured so validation messages keep the existing `$.property` format and no test fixture had to change. The shaded uber-JAR adds two new relocations (`tools.jackson` and `org.snakeyaml`).
- BREAKING: minimum supported Java runtime raised from **Java 11** to **Java 17**. `mockserver/pom.xml` `maven.compiler.source` and `maven.compiler.target` are now `17`, so published artifacts are Java 17 bytecode and will not run on a Java 11 JVM. The CodeQL workflow, Buildkite build agent image, and local dev scripts have all been aligned to JDK 17.
- BREAKING: coordinated upgrade to the Jakarta EE 10 / Servlet 6 stack and the upstream dependencies that required it. The full `javax.*` → `jakarta.*` namespace migration (servlet, ws.rs, annotation, inject, persistence) is now complete. Library bumps: Spring Framework 5.3 → 7.0, Spring Boot 2.7 → 4.0, Tomcat embed 9 → 11, Jetty 9.4 → 12, Jersey 3.1 → 4 (`jersey-apache-connector` → `jersey-apache5-connector` with Apache HttpClient 5), `jakarta.xml.bind-api` 3 → 4, `jakarta.servlet-api` 4 → 6, `jakarta.ws.rs-api` 2.1 → 4, `jakarta.annotation-api` 1.3 → 3, JUnit Jupiter 5.14 → 6.1, json-unit 2 → 5, json-path 2 → 3, Netty 4.1 → 4.2.15.Final (introduced via `netty-bom` so the new `netty-codec-base` / `netty-codec-compression` / `netty-codec-http3` sub-modules stay aligned).
  - Runtime deployment in a servlet container now requires a Servlet 6 / Jakarta EE 10 host: Tomcat 11+, Jetty 12+, WildFly 32+, or equivalent. Servlet 5 / Jakarta EE 9 containers are no longer supported.
  - `MockServerServlet` and `ProxyServlet` runtime contract is unchanged for consumers using `jakarta.servlet.*`. Consumers still importing `javax.servlet.*` must update their imports.
  - WAR test scaffolding that configured TLS via the removed `Connector.setAttribute("keystoreFile"/"keystorePass"/…)` API must migrate to the Tomcat 11 `SSLHostConfig` + `SSLHostConfigCertificate` pattern. The four WAR/proxy-war integration test classes in this repo show the working shape.
  - Servlet 6 preserves RFC 6265 surrounding double quotes on cookie values returned by `Cookie.getValue()`. MockServer's request decoder now strips them so cookie semantics are unchanged for clients.
  - Spring 7 requires the `-parameters` javac flag for `@PathVariable` / `@RequestParam` name resolution; this is now enabled project-wide in `maven-compiler-plugin`.
  - Spring 7's `MappingJackson2HttpMessageConverter` is deprecated for removal in favour of `JacksonJsonHttpMessageConverter`. MockServer keeps Jackson at 2.22.0 for now because `swagger-parser` is still locked to Jackson 2; Jackson 3 upgrade will land once `swagger-parser` ships a Jackson 3 line (see #1970).
- BREAKING: Nashorn (`org.openjdk.nashorn:nashorn-core:15.7`) removed as a managed dependency. `JavaScriptTemplateEngine` now uses the GraalVM Polyglot API directly (`org.graalvm.polyglot.Context` with `HostAccess.ALL` + `allowHostClassLookup` for the existing class-deny-list security policy). GraalJS 25.x dropped the JSR-223 `javax.script` bridge, so the previous Nashorn-or-GraalJS-via-JSR-223 fallback would have silently returned a null engine and broken every JavaScript template at runtime. Downstream consumers that previously relied on Nashorn arriving transitively must add `org.openjdk.nashorn:nashorn-core` to their own dependencies, or migrate to GraalVM polyglot directly.
- Drop the `--add-exports=java.base/sun.security.{x509,util}=ALL-UNNAMED` javac flags inherited from the Java 11 era. Repo-wide audit found zero `sun.security.*` references after the Java 17 / jakarta migration, so the flags were dead weight.
- Performance: the request-matching hot path no longer builds the human-readable "did not match because…" diagnostic string (the per-field message assembly and per-field hint generation) when it would only be discarded — i.e. when the log level is below `INFO`. The match evaluation, the match-difference data behind `detailedMatchFailures` / debugMismatch / explainUnmatched / verification, and the match result are unchanged; only the discarded narrative is skipped, and the per-matcher `StringBuilder` is no longer allocated in that case. For a server with many registered expectations running below `INFO` under sustained load this measurably cuts per-request allocation and GC pressure (JMH `-prof gc`: ~36% less matching-path allocation at 1000 expectations and log level `WARN`; no change at the default `INFO`). See the performance documentation's note on `logLevel` and matching throughput. A new on-demand `mockserver-benchmark` JMH module (excluded from the default build) backs these numbers.

### Fixed
- **CPU no longer climbs as the request/event log fills (issue #2329).** `CircularConcurrentLinkedDeque` — the bounded ring used for the request/event log — checked capacity on every insert with `ConcurrentLinkedDeque.size()`, which is **O(n)** (it walks the whole list). Once the log reached `maxLogEntries` (default 100,000) each request paid an O(n) traversal per log entry, so CPU rose as the log filled and stayed high (and clearing *expectations* does not clear the *log*, so it never recovered). Size is now tracked in an `AtomicInteger`, making the eviction check and `size()` **O(1)**. Measured per-insert cost at the default capacity dropped from ~210µs to ~15ns (~14,000× at 100k entries; the old cost scaled linearly with `maxLogEntries`). No behaviour change — same bounded FIFO semantics and eviction callback. Tip for high-throughput users: also clear the log (`PUT /mockserver/clear?type=LOG` or `?type=ALL`, or `PUT /mockserver/reset`), not just expectations, or lower `maxLogEntries`.
- **Regex matching in the GraphQL, JSON-RPC and LLM-conversation matchers is now ReDoS-bounded.** User-supplied regular expressions for a GraphQL `operationName`, a JSON-RPC `method`, and an LLM conversation's `latestMessageMatches` are now evaluated under the shared `mockserver.regexMatchingTimeoutMillis` timeout via `MatchingTimeoutExecutor` — the same protection `RegexStringMatcher` already applies to path/header/body regexes — so a pathological pattern can no longer pin a worker thread (ReDoS). A timed-out evaluation is treated as a non-match. (Resolves CodeQL alert for `GraphQLMatcher`; the same fix is applied to the two sibling matchers.)
- Dashboard **Log Messages** panel: a non-breaking space is now rendered after each expandable JSON block, so the text that follows (e.g. `} matched expectation:`) no longer butts directly against the closing brace.
- **CORS for the dashboard served cross-origin.** When `mockserver.corsAllowOrigin` is blank (the default) MockServer now reflects the request's `Origin` in `Access-Control-Allow-Origin` instead of emitting an empty (invalid) header, and falls back to sensible `Access-Control-Allow-Methods` / `Access-Control-Allow-Headers` when those are blank (reflecting the requested headers on preflight). The MCP endpoint (`/mockserver/mcp`) now answers the CORS preflight and exposes `Mcp-Session-Id` via `Access-Control-Expose-Headers`. Together these let the dashboard (and any browser client) call the control-plane API and MCP endpoint from a different port or domain. An explicit `corsAllowOrigin` is still honoured as an allow-list, and `*` is never combined with `Access-Control-Allow-Credentials: true`.
- **CORS for the metrics endpoint (`/mockserver/metrics`).** The endpoint now adds the same `Access-Control-Allow-Origin` headers as the rest of the API, so the dashboard's Metrics view can fetch metrics when served cross-origin (e.g. the UI dev server on a different port). The disabled-state `404` carries the headers too, so the UI reads it cleanly and shows its "metrics disabled" guidance instead of a browser CORS fetch error.
- Helm chart downloads for older versions: every chart listed in `index.yaml` now returns a valid `.tgz` from `https://www.mock-server.com/`. Previously, releases that created a new versioned site could leave older chart archives missing from the live bucket while `index.yaml` still referenced them, so `helm pull` / `helm install` failed for any version other than the latest. The release pipeline now syncs the full set of charts on every run, making the bucket self-healing (fixes #2282).
- **`Content-Encoding` no longer leaks across requests on a reused (pooled) connection.** When a compressed request (e.g. `Content-Encoding: gzip`) was followed by an uncompressed request on the same keep-alive connection, the second request was incorrectly recorded with the first request's `Content-Encoding` header. The preserved-headers state is now reset per request, so each recorded request carries only its own encoding headers (fixes #2322).
- **Compressed request bodies now retain their original on-the-wire bytes.** When an HTTP/1.1 request arrives with a `Content-Encoding` (e.g. gzip), MockServer still decompresses it for matching/recording as before, but now also keeps the original compressed bytes alongside the decompressed body. A new `HttpRequest#getBodyAsOriginalRawBytes()` returns the exact bytes the client sent (the compressed payload when compressed, otherwise the decompressed bytes), so you can verify a client actually compressed its body; `getBodyAsRawBytes()` is unchanged (decompressed). A `BinaryBody` expectation now matches against **either** the decompressed body or the original compressed bytes, so a mixture of compressed and uncompressed requests matches automatically with no configuration. The original bytes are serialised (as `originalBody`) so they survive `retrieveRecordedRequests` and persistence (fixes #2326).
- **WASM custom-rule security controls are now enforced.** The `wasmEnabled` (default `false`) and `wasmMaxMemoryPages` (default `256`) configuration properties were documented as gating the experimental WASM custom-rule feature but were never actually read. WASM support is now disabled by default and fails closed: the WASM module control-plane endpoints (`PUT`/`GET`/`DELETE /mockserver/wasm/modules`) return `403` and `WasmBodyMatcher` does not match unless `mockserver.wasmEnabled=true`, and a loaded module's linear memory is now capped at `wasmMaxMemoryPages` via chicory `MemoryLimits` at instance creation. Set `wasmEnabled=true` to opt in.

### Removed
- Removed the **xDS route discovery** feature (REST endpoint `GET /mockserver/xds/routes`, gRPC RDS server, `xdsEnabled`/`xdsPort` configuration properties, and Helm `sidecar.xdsEnabled`/`sidecar.xdsPort` values). The feature shipped behind default-off flags and saw no adoption; real service mesh integration routes traffic to MockServer via an Istio VirtualService rather than having MockServer act as an RDS server. The **transparent proxy / sidecar mode** (`transparentProxyEnabled`, conntrack `SO_ORIGINAL_DST`, iptables init container) is fully retained.

## [6.1.0] - 2026-05-27

### Security
- SSRF protection for forward and forward-template actions: new `mockserver.forwardProxyBlockPrivateNetworks` property (default `false` for backwards compatibility) rejects forward targets that resolve to loopback, link-local, RFC 1918 private, or cloud metadata addresses (e.g. `169.254.169.254`). Enable in hardened or multi-tenant deployments where untrusted callers can register expectations. A future major release is expected to flip the default to `true`.
- ReDoS protection in regex matchers: regex evaluation now runs on a shared cached daemon-thread pool with a configurable timeout `mockserver.regexMatchingTimeoutMillis` (default `5000`ms). Patterns that exceed the budget are treated as non-matches and a WARN log entry is written, so a pathological pattern cannot wedge a Netty worker.
- XPath DoS protection: XPath evaluation in body matching now uses the same shared timeout executor with `mockserver.xpathMatchingTimeoutMillis` (default `5000`ms).
- Cryptographically secure randomness: `UUIDService` and `TemplateFunctions` now use `SecureRandom` instead of `java.util.Random` for UUID generation, `rand_int`/`rand_int_10`/`rand_int_100`, and `rand_bytes` template helpers.
- Loud insecure-mode warning logs at startup / SSL-context init: a WARN is emitted when (a) the forward proxy trusts all TLS certificates (`forwardProxyTLSX509CertificatesTrustManagerType=ANY`), (b) Velocity class loading is enabled (`velocityDisallowClassLoading=false`), (c) JavaScript templates have no class restrictions (`javascriptDisallowedClasses` empty), or (d) `tlsProtocols` includes the deprecated TLSv1 / TLSv1.1.
- `mockserver.tlsAllowInsecureProtocols` configuration property (default `true` for backwards compatibility): when set to `false`, any `TLSv1` or `TLSv1.1` entries in `mockserver.tlsProtocols` are filtered out before the SSL context is built, giving users an opt-in hardened TLS profile without having to rewrite their existing `tlsProtocols` value. A future major release is expected to flip this default to `false`.
- Secrets are no longer logged in plaintext: the startup property dump now redacts the values of properties whose name indicates a secret (password, secret, access key, API key, connection string, token, private key, credential, passphrase) as `***REDACTED***`. This covers the cloud blob credentials (`blobStoreSecretAccessKey`, `blobStoreConnectionString`), `llmApiKey`, `proxyAuthenticationPassword`, and similar, so they are not leaked to log aggregation.
- Kubernetes admission-webhook Helm hardening: fixed a shell-injection vector where the `webhook.tls.certValidityDays` value was interpolated unquoted into the self-signed-cert bootstrap Job (now quoted and integer-coerced); narrowed the TLS-bootstrap RBAC from cluster-wide Secret access to a namespace-scoped `Role` plus a `resourceNames`-restricted `ClusterRole` for the `MutatingWebhookConfiguration` caBundle patch only; and removed the running webhook's unused Kubernetes API RBAC (the webhook is a pure HTTPS server) in favour of `automountServiceAccountToken: false`.
- HTTP/3 CONNECT-UDP (MASQUE) open-relay risk documented: when `http3ConnectUdpEnabled=true` the relay forwards to any target the client names (SSRF-equivalent); it is default-off and now clearly flagged as test-only in the configuration and HTTP/3 documentation.

### Fixed
- HTTP/3 request bodies are now capped at `maxRequestBodySize` (default 10 MiB), matching the HTTP/1.1 and HTTP/2 paths; an over-cap HTTP/3 request is rejected (413 / QUIC stream shutdown) instead of being accumulated unboundedly in memory.
- Cloud BlobStore backends: cloud SDK clients (S3/GCS) are now closed on server shutdown (the `BlobStore` SPI is `AutoCloseable`, closed via the state backend) instead of leaking connection pools and threads; the Azure backend now encodes metadata keys reversibly so keys such as `x-custom-type` round-trip exactly and no longer collide with `x_custom_type` (previously both were silently mapped to the same key), and writes data + metadata atomically; the S3 and GCS `get()` paths no longer make a redundant second network call per read.
- Release pipeline now downloads the `mockserver-k8s-webhook` jar artifact before building its image, so the webhook image is published reliably on multi-agent CI.

### Added
- First-class LLM and agent mocking: new `httpLlmResponse` action type lets you mock LLM provider APIs at the semantic level — describe the model's reply (text, tool calls, stop reason, usage) and MockServer produces the byte-correct provider wire format. Supports all 7 major providers: Anthropic Messages, OpenAI Chat Completions, OpenAI Responses, Google Gemini, AWS Bedrock, Azure OpenAI, and Ollama. Non-streaming responses return provider-correct JSON; streaming responses generate the full SSE event sequence (e.g. `message_start` through `message_stop` for Anthropic, `chat.completion.chunk` with `finish_reason` for OpenAI) with configurable timing physics (`timeToFirstToken`, `tokensPerSecond`, `jitter`). OpenAI embeddings are also supported with deterministic vector generation via `deterministicFromInput()`.
- Conversation-aware matchers for multi-turn agent testing: `whenTurnIndex(n)`, `whenLatestMessageContains(text)`, `whenLatestMessageRole(role)`, and `whenContainsToolResultFor(toolName)` predicates match against the parsed `messages` array in the inbound request body, enabling scripted multi-turn conversations where turn 1 returns a `tool_use` and turn 2 (after the agent sends a `tool_result`) returns the final answer. All predicates compose with AND semantics and integrate with the scenario state machine for automatic turn advancement.
- Per-session conversation isolation via `isolateBy(header("x-session-id"))`, `isolateBy(queryParameter("agent"))`, or `isolateBy(cookie("sid"))`: each unique value of the configured attribute gets independent scenario state, so concurrent agents sharing the same mocked endpoint do not interfere. Missing attributes fall back to shared state gracefully.
- `mock_llm_completion` MCP tool: set up a single-turn LLM expectation from the MCP control plane, specifying provider, path, model, text, tool calls, and streaming mode
- `create_llm_conversation` MCP tool: build a multi-turn scenario-chained LLM conversation with optional per-session isolation from the MCP control plane; returns the generated scenario name and per-turn state values
- LLM Response badge in the dashboard expectation row showing provider, model, and text preview; Conversation view extended with a scripted-turns panel
- `mockserver.maxLlmConversationBodySize` configuration property (default 1 MiB; clamped to 16 KiB - 64 MiB; env var `MOCKSERVER_MAX_LLM_CONVERSATION_BODY_SIZE`): request bodies larger than this limit skip conversation-aware parsing and are treated as no-match, preventing DoS via oversized JSON payloads
- Custom json-unit matcher support for JSON body matching: implement `org.mockserver.matchers.CustomJsonUnitMatcherProvider` and point `mockserver.customJsonUnitMatchersClass` at it to register named Hamcrest matchers that JSON body expectations can reference via the `${json-unit.matches:name}` placeholder (e.g. `{ "price": "${json-unit.matches:largerThan}" }`); misconfigured providers are logged at WARN and ignored, so matching never fails because of an unloadable extension (fixes #2279)
- `http2Enabled` configuration property to disable HTTP/2: when set to false ALPN no longer advertises `h2` (and h2c is not detected) so HTTP/2 capable clients fall back to HTTP/1.1
- Agent-friendly mismatch diagnostics: `explain_unmatched_requests` MCP tool and `PUT /mockserver/explainUnmatched` REST endpoint return recent requests that matched no expectation, each with ranked closest-expectation diffs and actionable remediation hints (e.g., "use method POST not GET", "add missing header Authorization"); `debug_request_mismatch` results are now ranked by closeness and include remediation hints; new `mockserver://unmatched` MCP resource
- `create_expectations_from_recorded_traffic` MCP tool: converts traffic recorded by MockServer's forwarding/proxy mode into active mock expectations in one call, enabling an "observe then mock" workflow; supports `method`/`path` filtering and `preview` mode to inspect expectations before activating them
- OpenAPI contract verification MCP tools: `verify_traffic_against_openapi` validates recorded request-response pairs against an OpenAPI spec (passive conformance checking); `run_contract_test` sends example requests derived from an OpenAPI spec to a running service and validates the responses (active contract testing); both return structured per-operation pass/fail results with validation errors
- OpenAPI resiliency testing MCP tool: `run_resiliency_test` sends deliberately malformed and boundary-case requests derived from an OpenAPI spec to a running service (omitting required fields, type violations, numeric/string boundary violations, oversized strings, malformed JSON) and classifies each outcome as HANDLED (4xx) or UNEXPECTED (5xx/2xx/error); returns per-mutation results with operation summaries
- Deterministic LLM record/replay: `record_llm_fixtures` MCP tool snapshots LLM/MCP traffic recorded through MockServer's forwarding proxy into a committable JSON fixture file with secrets automatically redacted (Authorization, api-key, Cookie, etc.); SSE streaming responses (Anthropic, OpenAI, etc.) are converted to `HttpSseResponse` actions for faithful event-by-event replay; `load_expectations_from_file` MCP tool loads fixture files as active expectations for offline, deterministic, zero-cost test replay

### Changed
- **BREAKING** Inbound HTTP/1.1 and HTTP/2 request bodies are now capped at 10 MiB by default (`mockserver.maxRequestBodySize`). Previously unbounded. Requests larger than the limit are rejected with `413 Payload Too Large`. Raise the limit (e.g. `-Dmockserver.maxRequestBodySize=52428800`) if you intentionally mock large uploads.
- **BREAKING** Upstream response bodies received when MockServer is acting as a proxy or forwarder are now capped at 50 MiB by default (`mockserver.maxResponseBodySize`). Previously unbounded. Raise if you forward to services that legitimately return larger payloads.
- Each published JAR (including the `-no-dependencies` shaded artifacts) now declares a stable `Automatic-Module-Name` in its `MANIFEST.MF`, so downstream JPMS consumers can `requires` MockServer modules with names that no longer change with each version: `org.mockserver.core` (`mockserver-core`), `org.mockserver.client` (`mockserver-client-java`), `org.mockserver.netty` (`mockserver-netty`), `org.mockserver.test` (`mockserver-testing`), `org.mockserver.testing` (`mockserver-integration-testing`), `org.mockserver.junit.rule` (`mockserver-junit-rule`), `org.mockserver.junit.jupiter` (`mockserver-junit-jupiter`), `org.mockserver.springtest` (`mockserver-spring-test-listener`), `org.mockserver.examples` (`mockserver-examples`), `org.mockserver.maven` (`mockserver-maven-plugin`); each `*-no-dependencies` shaded variant shares its unshaded counterpart's module name and is an alternative packaging (place only one on the JPMS module path)

### Fixed
- Dynamic CA / SSL certificate generation no longer fails when `dynamicallyCreateCertificateAuthorityCertificate=true` (or any auto-generated server certificate path) is used: the four `Configuration` fluent setters for `certificateAuthorityCertificate`, `certificateAuthorityPrivateKey`, `privateKeyPath`, and `x509CertificatePath` no longer file-existence-check at set-time, because the internal generator sets these to the destination path before the file is written. User-supplied path typos are still surfaced by `CertificateConfigurationValidator` at TLS-init time.
- HTTP/2 requests through the HTTPS CONNECT forward proxy no longer hang and emit a GOAWAY after ~30s; the internal relay now negotiates HTTP/1.1 or HTTP/2 per connection via ALPN instead of mismatching its TLS layer and codec (fixes #2260)
- Docker image and standalone executable JAR produced no log output because the shaded server JAR did not include an SLF4J logging provider (fixes #2097)
- `*-no-dependencies` shaded artifacts leaked their un-shaded source module (and its transitive dependencies) onto consumers' classpaths; these artifacts are now truly dependency-free

## [6.0.0] - 2026-05-20

### Added

**Protocol & transport**
- gRPC protocol mocking without a grpc-java dependency: upload a Protobuf descriptor and mock unary, client-streaming, server-streaming, and bidirectional-streaming RPCs; `GrpcStreamResponse` supports multi-frame streaming responses
- GraphQL body matching: whitespace-normalised query comparison, `operationName` matching, and `variablesSchema` JSON Schema validation for variables
- binary request/response mocking via `BinaryRequestDefinition` and `BinaryResponse` for non-HTTP protocols
- DNS mocking with `dnsEnabled`/`dnsPort` configuration and support for A, AAAA, CNAME, MX, SRV, TXT, and PTR record types
- IPv6 CONNECT proxy support including correctly bracketed IPv6 address handling in the `CONNECT` tunnel

**Request matching**
- probabilistic expectation matching: set a `percentage` field (0–100) on an expectation so only a fraction of matching requests are served by it, enabling fault-injection scenarios (fixes #2122)
- HTTP method factory methods on `HttpRequest`: `HttpRequest.get(path)`, `.post(path)`, `.put(path)`, `.delete(path)`, `.patch(path)`, `.head(path)`, `.options(path)` for more concise expectation definitions (fixes #1509)

**Responses & actions**
- multi-response expectations: define an `httpResponses` list with a `responseMode` of `SEQUENTIAL` (cycle repeatedly through the list in order) or `RANDOM` (pick at random) to serve different responses on successive matched requests
- multi-action expectations: compose response, forward, and callback actions in a single expectation with a primary action and post-action callbacks
- stateful scenarios with atomic state transitions: gate expectations behind named states and advance through them by setting `newScenarioState` on the expectation, making it straightforward to model multi-step protocols
- CRUD simulation via `PUT /mockserver/crud`: supply a data model and MockServer auto-generates a fully stateful REST API (list, create, read, update, delete) backed by an in-memory store
- `FileBody` response body type that loads content from a file path at response time, useful for large or binary payloads (fixes #2163)
- in-memory file store: upload files via `PUT /mockserver/files/store`, retrieve via `PUT /mockserver/files/retrieve`, list via `PUT /mockserver/files/list`, and delete via `PUT /mockserver/files/delete`; stored files can be referenced by `FileBody` (fixes #1652)
- `respondBeforeBody` flag on the request matcher to dispatch the configured response (and optionally close the connection) before MockServer reads the request body, useful for reproducing client behaviour when a server responds and closes mid-upload (fixes #1831)

**Delays & timing**
- response delays with statistical distributions (uniform, Gaussian, log-normal) for realistic latency simulation (fixes #1688)
- global response delay via `mockserver.globalResponseDelayMillis` configuration property to add a baseline delay to every response
- connection timeout emulation via `mockserver.connectionDelayMillis` configuration property: a configurable delay before protocol detection fires, so slow-connect scenarios can be tested without a real network (fixes #1604)
- chunked dribble delay via `ConnectionOptions.withChunkSize()` / `withChunkDelay()` to drip-feed any response body in configurable-size chunks at a configurable rate

**Response templates**
- template helper functions: JWT generation, string manipulation, JSON path extraction, date arithmetic, and math operations available inside JavaScript, Velocity, and Mustache templates

**Record & replay**
- HAR 1.2 export: pass `format=HAR` to the retrieve API to get a standard HAR file of all recorded requests and responses (fixes #2175)
- automatic persistence of recorded expectations: `persistRecordedExpectations` and `persistedRecordedExpectationsPath` configuration properties save recorded traffic to disk so it survives restarts (fixes #2175)

**Debugging & diagnostics**
- per-expectation match count tracking: each expectation now exposes an invocation counter so tests can assert exactly how many times an endpoint was hit
- closest-match tracking: when a request does not match any expectation, MockServer identifies the expectation with the most fields satisfied and surfaces it via the API and dashboard
- `debugMismatch()` client method and `PUT /mockserver/debugMismatch` endpoint to programmatically retrieve the closest-match analysis for the last unmatched request
- match failure hints: actionable suggestions attached to `EXPECTATION_NOT_MATCHED` log events to guide correction of common mistakes
- "Why didn't this match?" debug dialog in the dashboard: click any unmatched request to see a field-by-field comparison against the closest expectation with per-field pass/fail indicators
- expectation ID included in `EXPECTATION_NOT_MATCHED` log messages to make it easier to correlate log output with the intended expectation (fixes #1937)

**Logging**
- compact log format: set `mockserver.compactLogFormat=true` to emit single-line JSON log entries instead of multi-line formatted output (fixes #1510)
- per-category log level overrides via `mockserver.logLevelOverrides` so individual event types can have different log levels (fixes #1694)
- correlation ID retrieval: `retrieveLogsByCorrelationId()` client method and a correlationId chip in the dashboard for tracing a single request across all related log events
- `retrieveLogEntries()` client method returning typed `LogEntry` objects with optional time-range filtering; pass `LOG_ENTRIES` as the format to the retrieve API for programmatic access
- custom log event listener via a `Consumer<LogEntry>` callback registered with the `Configuration` object, enabling integration with external observability tools (fixes #1960)

**Proxy & forwarding configuration**
- `mockserver.forwardDefaultHostHeader` configuration property: set a specific `Host` header value to send on all forwarded requests, overriding the original client `Host` header (fixes #1782)
- `mockserver.proxyRemoteHost` and `mockserver.proxyRemotePort` configuration properties to route all proxy traffic through an upstream proxy (fixes #1753)
- request forwarding timings captured per forwarded request: both connect time and total round-trip time are available in the log and dashboard (fixes #1574)

**OpenAPI**
- OpenAPI callback support: MockServer reads `callbacks` entries in an OpenAPI specification and automatically creates `AfterAction` webhook expectations (fixes #1483)

**TLS & security**
- BouncyCastle FIPS provider support for environments that require FIPS 140-2 compliant cryptography (fixes #1769)
- support for custom TLS protocols TLSv1.2 and TLSv1.3
- better error messages when MockServerClient fails due to TLS or networking errors

**Client & test integration**
- `@MockServerTest` now applies `mockserver.*` prefixed properties to the per-instance MockServer `Configuration` object, enabling declarative configuration of `initializationClass`, `logLevel`, `maxExpectations`, and other settings directly in the annotation (fixes #1554)
- Jackson `StreamReadConstraints` maximum string length raised to 100 MB to handle large JSON bodies without `StreamConstraintsException` (fixes #1754)

**Build & deployment**
- Maven plugin `initializationJson` now accepts glob patterns to load multiple expectation files from a directory (fixes #2231)
- `mockserver/mockserver:graaljs` Docker image tag that bundles the GraalJS engine JARs, enabling native ECMAScript 2022 support in response templates without Nashorn
- Docker HEALTHCHECK instruction added to all official images so container orchestrators can determine readiness without an external probe
- Helm chart `podLabels` value to attach arbitrary labels to MockServer pods, useful for service-mesh injection and internal routing rules (fixes #1884)

### Changed
- BREAKING: removed implicit reliance on internal java-certificate-classes (thanks to @Arkinator)
- BREAKING: the `classifier=shaded` form of `mockserver-client-java`, `mockserver-netty`, `mockserver-junit-jupiter`, `mockserver-junit-rule`, and `mockserver-spring-test-listener` is no longer published. Use the corresponding `*-no-dependencies` artifactId instead (e.g. depend on `mockserver-netty-no-dependencies` rather than `mockserver-netty` with `<classifier>shaded</classifier>`). The `*-no-dependencies` variants are now proper Maven modules and are the supported way to consume a shaded MockServer jar.

### Fixed

**Proxy & forwarding**
- proxy forwarding failures now return `502 Bad Gateway` instead of `404 Not Found`, making it clearer to clients that the upstream could not be reached (fixes #1519)
- `Host` header updated to match the forwarding target to prevent `421 Misdirected Request` errors from strict servers (fixes #1897)
- request/response bodies with `Content-Encoding` are now re-compressed correctly when forwarding, preventing garbled bodies on the upstream (fixes #1668)
- `Transfer-Encoding` header preserved on forwarded responses; spurious `Content-Length` header no longer added when `Transfer-Encoding` is present (fixes #1733)

**Request & response handling**
- cookie values starting with `!` were corrupted in forwarded responses (fixes #1875)
- duplicate query parameter values are now preserved instead of being deduplicated (fixes #1866)
- binary response bodies (e.g. `application/octet-stream; charset=utf-8`) were corrupted because a `charset` parameter in `Content-Type` caused the body to be treated as a string; now correctly treated as binary (fixes #1910)
- JSON body serialization preserved numeric precision — `0.00` was incorrectly serialized as `0.0` (fixes #1740)

**OpenAPI**
- `ByteArraySchema` (`string` format `byte`) properties were omitted from generated OpenAPI examples (fixes #1788)
- `$ref` inside OpenAPI example values was not resolved, leading to raw `$ref` strings in generated responses (fixes #1474)
- `allOf`/`anyOf`/`oneOf` composed schemas now generate merged example responses (fixes #1852)
- OAS 3.0 boolean `exclusiveMinimum`/`exclusiveMaximum` now correctly translated to JSON Schema Draft-07 numeric format (fixes #1896)
- OpenAPI 3.1 `types` array field now correctly preserved during schema serialization (fixes #1940)

**XML**
- XSD schemas with `xs:include` or `xs:import` using relative paths now resolve correctly (fixes #2118)

**JUnit & Spring integration**
- `@MockServerTest` field injection now works in `@Nested` JUnit 5 test classes (fixes #1979)
- double server start when `@MockServerSettings` (carrying `@ExtendWith`) is combined with explicit `MockServerExtension` registration is now prevented (fixes #1977)
- `clientCertificateChain`, `localAddress`, and `remoteAddress` fields on `HttpRequest` were serialized but not deserialized — both directions now work (fixes #1973)
- `MockServerClient` parameter injection now works with `@TestInstance(PER_CLASS)` where the test instance is created before `@BeforeAll` (fixes #1621)
- `ClassNotFoundException` for callback classes when running in a Spring Boot uber JAR (fixes #1571)

**Dashboard & WebSocket**
- dashboard WebSocket returned 404 when MockServer was running behind a reverse proxy with a path prefix (fixes #1693)
- HTTP/2 `CONNECT` proxy no longer hangs when the client advertises `h2` via ALPN (fixes #1933)
- WebSocket upgrade over HTTP/2 is now rejected cleanly instead of hanging the dashboard (fixes #1803)

**Concurrency & thread safety**
- `Times.remainingTimes()` made thread-safe with `AtomicInteger` to prevent race conditions under concurrent load (fixes #1834)
- `XmlStringMatcher` made thread-safe by creating a new `DiffBuilder` per match instead of sharing one (fixes #1796)
- Disruptor ring buffer is drained before `verify()` to prevent false-positive or false-negative results under high throughput (fixes #1757)
- expired TTL expectations are now filtered from the event bus and event bus subscribers are cleared after publish to prevent stale matches (fixes #1847, #1874)

**TLS & mTLS**
- mTLS (data-plane) enforcement moved from transport layer to application layer, fixing scenarios where client certificate validation was applied to non-mTLS connections (fixes #1766)

**Docker & deployment**
- `netty-tcnative` native libraries no longer bundled in the shaded JAR, preventing native library conflicts (fixes #1778)
- Helm chart sub-chart deployments generated conflicting Kubernetes resource names when chart name was omitted (fixes #1752)

**Glob & file initialization**
- glob brace expansion in `initializationJson` path failed to find the starting directory in some environments (fixes #1715)
- `WebSocket` channel leak when the `CircularHashMap` evicted the oldest callback client (fixes #1543)
- verify failure message incorrectly said "was not found" even when matching requests existed; message now accurately describes the mismatch (fixes #1789)

## [5.15.0] - 2023-01-11

### Added
- an image tag that allows container to run as root
- HTTP2 protocol support for mocking
- ability to proxy multiple binary messages without waiting for response 
- support to disallow loading of specific class in javascript templates 
- support to disallow specific text in javascript templates 
- support to disallow loading of any class in velocity templates
- support to disallow specific text in velocity templates
- support to disallow specific text in mustache templates
- support to velocity templates to load files via $import.read(...)

### Changed
- improved error message for not valid HTTP requests that are not being proxied
- improved error message when client doesn't trust MockServer's CA

### Fixed
- references to globally-scoped values within Ingress template
- fixed error passing configuration in MockServerClient
- fixed handling of additional content-type parameter and special characters in the content-type such as '+'
- removed invalid extra content-encoding header add when forwarding if content-encoding was not present

## [5.14.0] - 2022-08-22

### Added
- added support for json serialisation and de-serialisation java date time
- support for server urls in OpenAPI specification, by adding server url path as path prefix to operations
- improved documentation of clear functionality and type parameter and added examples
- local ip and port exposed to callbacks and log, useful when bound on multiple ports
- ability to match on content-encoding header
- added support for custom HTTP methods (via assumeAllRequestsAreHttp)

### Changed
- used helm release name in K8s resources to avoid conflicts for multiple deployments in same namespace (without extra values being set)
- tlsMutualAuthenticationCertificateChain is used if configured, even if tlsMutualAuthenticationRequired is false, so clients can choose correct certificate for optional client auth

### Fixed
- error matching header or parameters using array schema
- updated Ingress apiVersion in helm chart to non deprecated value
- removed the jdk14 slf4j bindings from the shaded and no-dependencies jars
- fixed NullPointerException and added more context information for match failures
- fixed NullPointerException during matcher logging
- fixed override logic for query and path parameters
- fixed verification of path parameters with multiple path parameter expectations
- fixed matching for array parameters using OpenAPI or a schema based parameter matcher
- resolved errors matching path by regex against expectations with path parameters
- resolved error with some deleted logs still appearing in the dashboard
- Content-Length is not added if a mock response set Transfer-Encoding

## [5.13.2] - 2022-04-05

### Fixed
- fixed artefact name in no-dependencies pom which caused issue with gradle builds
- added support for yml in addition to yaml for yaml files

## [5.13.1] - 2022-04-02

### Added
- simplified JSON format accepted for headers and other multi-value maps by allowing single values to be used as value list
- added warning message when content-length in expectation response is shorter than the body length
- improved log output for multimap failures, especially when using schema matcher (i.e. with OpenAPI) for parameters, headers, etc
- added support for endpoints examples in addition to existing schemas examples in an OpenAPI specifications

### Changed
- improved error messages from main method
- always serialise default fields for StringBody and JsonBody when retrieving recorded expectations for consistency even when the charset changes
- allow (and ignore) additional timestamp field for expectation JSON to support record request and responses to be submitted as JSON expectations
- upgraded JVM version in docker (and helm) to 17
- reduced memory footprint from log and simplified calculation of maximum log size
- use JVM trust store in addition to MockServer CA for MockServerClient to allow control plane requests to go via proxies or load balancers that terminate TLS

### Fixed
- allow callback which is nested inside initializer class for maven plugin initializer
- fixed HttpClassCallback static builder signature
- improved parsing of media type parameters to handle parameter values with equal symbol
- fixed serialising certificate chain to dashboard UI
- used absolute URI form for requests to an HTTP proxy as per [rfc2068 section 5.1.2](https://www.rfc-editor.org/rfc/rfc2068#section-5.1.2)
- removed content-length and other hop by hop response headers for forward actions
- fixed handling of headers and parameters specified without any values
- fixed logLevel in MockServer instance Configuration, so it now sets the SystemProperty read by the logging configuration
- fixed parallel execution of MockServerExtension to prevent port bind errors
- fixed error parsing body parameters containing '/'
- removed external references to schema specification to remove required network connectivity
- fixed docker latest tag by worked around bug in sonatype not updating the LATEST metadata for snapshots
- fixed partial deletion of expectations from watched file initialiser
- resolved small memory leak during proxy authentication
- updated verify by expectation id so it uses expectation match log events instead of the request matcher from the expectation

## [5.13.0] - 2022-03-17

### Added
- added support for configuring log level via properties file
- allow proactively initialisation of TLS so dynamic TLS CA key pair is created at start up
- added control plane authorisation using mTLS
- added control plane authorisation using JWT
- added support for control plane JWTs supplier to client
- added support for control plane JWT authorisation to specify required audience, matching claims and required claims
- added control plane authorisation using both JWT and mTLS
- added property to control maximum number of requests to return in verification failure, defaults to 10
- added field to verifications to control maximum number of requests to return in verification failure, defaults to configuration property - item above
- added remote address field to http requests that can be used by class or method callbacks
- exposed remote address (i.e. client address) to method and class callbacks, logs and dashboard
- exposed client certificate chain to method and class callbacks, logs and dashboard
- added simpler mustache style response templates (in addition to existing javascript and velocity support)
- added response template variables and functions for date, uuid, random, xPath and jsonPath for mustache
- added response template variables for date, uuid and random for velocity
- added response template variables for date, uuid and random for javascript
- added path parameters, remote address and client certificate chain to response template model
- added support for EMCAScript 6 in JavaScript response templates for Java versions between 9 and 15
- added support for numerous velocity tools for example for JSON and XML parsing to velocity response templates

### Changed
- included Bouncy Castle now used by default to resolve issues with modules in Java 16+ and backwards compatibility for Java 8
- improved configuration for dynamically creating CA so the directory is defaulted if not set and log output is clearer
- improved UI handling of match failures with a because section and more complex log events
- improved log configuration during startup when loading of properties file
- simplified support for multiline regex by allow . to match newlines
- improved regex matching by support Unicode (instead of US-ASCII) and native case-insensitive matching
- improved performance of negative matches by reducing the number of regex matches when not matching
- disabled privilege escalation in helm chart
- added setting of command line flags (i.e. serverPort) via system properties and properties file in addition to environment variables
- improved log output for command line flags, environment variables and system properties
- removed deprecated configuration properties for forward proxying
- changed docker distroless base image to distroless image for nonroot user
- changed docker distroless base image for snapshot to distroless image for debugging
- changed client to launch dashboard in HTTP (not HTTPS) to avoid issues with self-signed certificates
- simplified the body field for response template model
- improved XML matching by ignoring element order
- improved security by change CORS defaults to more secure values that prevent cross-site requests by default

### Fixed
- worked around JDK error 'flip()Ljava/nio/ByteBuffer; does not exist in class java.nio.ByteBuffer'
- null pointer exception when serialising string bodies with non string content types (i.e. image/png)
- disabled native TLS for netty to improve TLS resilience
- fixed handling of circular references in OpenAPI specifications to be as gracefully as possible

## [5.12.0] - 2022-02-12

### Added
- index.yaml to www.mock-server.com so it can be used as a helm chart repository
- command line flags can now be set as environment variables simplifying some container deployment scenarios
- glob support for initialisation files to allow multiple files to be specified
- request and response modifiers to dynamically update path, query parameters, headers, and cookies
- custom factory for key and certificates to provide more flexibility
- support for Open API expectations in json initialisation file
- improved @MockServerTest to support inheritance
- more flexibility over semicolon parsing for query parameters
- shaded jar for mockserver-netty and mockserver-client-java to reduce impact of dependency version mismatches with projects including these dependencies

### Changed
- ensured that TCP connections are closed immediately when shutting down to improved time before operating system frees the port
- reduce noise from Netty INFO logs that were not correct or misleading
- retrieveRecordedRequests now returns HttpRequest[]
- made it easier to set priority and id both in Java and Node clients in multiple places
- changed default charset for JSON and XML to UTF-8 from ISO 8859-1
- error handling for Open API so only single operation is skipped on failure not the entire file
- reduced over resolution of OpenAPI that triggered bugs in Swagger Parser V3 library
- replaces JDK FileWatcher with custom solution for watch file changes to work around multiple JDK bugs
- improved helm chart by supporting more configuration options
- remove explicit calls to System.gc()

### Fixed
- resolved multiple issues with clearing by expectation id
- resolved multiple issues with verifying by expectation id
- resolved multiple NullPointerExceptions in backend for UI
- ensure exact query parameter string is proxied allowing for empty values, leading `!` or or other special scenarios
- improved expectation updates from FileWatcher so only expectation from matching source are updated resolving multiple bugs
- ensured socket protocol of HTTPS is honoured resulting in forwarded requests using TLS
- fixed logging of exceptions such as port already bound at startup
- fixed retrieval of active exceptions where expectations were no longer active but not yet removed from expectations list
- no longer treat ndjson as json
- accessing UI via a reverse proxy or load balancer

## [5.11.2] - 2020-10-08

### Added
- clearing by expectation id
- verifying by expectation id

### Changed
- improved reliability and performance around stopping especially when stop is called multiple times for the same instance
- improved grouping of logs and stopped TRACE level logs from being grouped which caused inconsistency in the UI

### Fixed
- fixed recursive loop on stopAsync for ClientAndServer
- header matching for subsets to ensure notted header keys don't exist

## [5.11.1] - 2020-07-22

### Added
- port is now printed at start of each log line
- shutdown log message specifying port
- UI updated prior to stopping MockServer to ensure all pending log messages are sent over UI web socket
- added listener for expectation modifications that can be used with ExpectationInitializer for custom expectation persistence

### Changed
- performance improvements of expectation sorting and comparisons
- reduced creation of objects at WARN log level
- ensured all threads are daemon threads (except port binding thread)
- simplified and improve performance of matching for headers, query string parameters, path parameters, cookies and body parameters
- only mark log events as deleted for log level of TRACE, DEBUG, or INFO so log can be view in UI
- improved performance of handling large OpenAPI specifications
- improved error message format for errors when loading OpenAPI specifications
- changed name of `optionalString` static factory method to `optional` to improve consistency with `not`

### Fixed
- fixed field name error when serializing ParameterBody
- error when log level DEBUG cleared log events were returned from the API

## [5.11.0] - 2020-07-08

### Added
- added basic support to proxy binary requests that are not HTTP
- dynamic maximum log events and maximum expectations based on available memory
- added ability to switch between BouncyCastle and vanilla JDK for key and certificate generation
- added support for TLS over SOCKS4 or SOCKS5
- request matching and expectations using OpenAPI or Swagger specification
- create expectation using OpenAPI or Swagger specification with automatic example responses
- verifications of requests or request sequences using OpenAPI or Swagger specification
- clear log, clear expectations, retrieve logs and retrieve requests using OpenAPI or Swagger specification
- json schema matchers for method, path, headers, query string parameters and cookies
- path variables matched by nottable string, regex or json schema (as per query string parameters)
- support for optional query parameters, header and cookies
- support for nullable keyword in JSON Schemas (part of Open API specification not JSON Schema specification)
- matching xml bodies against JSON Schema matchers
- matching parameter bodies against JSON Schema matchers
- support to match path parameters, query parameters and header either by sub set or by matching key
- grouping of log events in UI to simplify analysis of expectation matches / non matches for a request
- added extra log messages to indicate progress for large json expectation initializers
- added log messages for invalid control plane request to make control plane errors clearer in the UI
- added support for easily mapping jar and config into the docker container
- added support for easily mapping jar and config into the helm chart

### Changed
- reduced time range of CA certificates to increase likelihood they will be accepted by strict systems (i.e. VMWare vCenter Server)
- improved error message when exception loading or reading certificates or keys (i.e. file not found)
- certificate and private key are saved to directoryToSaveDynamicSSLCertificate when preventCertificateDynamicUpdate is enabled
- returns created expectations from /mockserver/expectation so that it is possible to view the id for new (or updated) expectations
- added ability to inherit @MockServerSettings for Junit5 tests
- switched to distroless container base for security and size
- added explicit gc suggestion after reset and clear
- upgraded docker container to Java 11 to ensure JVM honours container memory constraints (i.e. inside kubernetes)
- improved parsing of invalid content-type header parameters by handling error gracefully and outputting a clear error message
- improved performance through multiple minor tweaks around handling of expectations
- added version to log output to improve resolution of github issues with logs attached
- improved logic around proxies to make HTTP CONNECT, SOCKS4 and SOCKS5 more reliable and faster
- reduced object creation (and therefore GCs) for log especially during request matching
- print logs timestamp with milliseconds
- reduced expiry of certification to one year to avoid errors from modern systems that don't like long lived certificates (such as Chrome or VMWare)
- defaulted charset for XML and JSON to UTF8 as per rfc3470 and rfc8259
- version matching logic for client now only matches on major and minor version and not bug fix version
- improved handling of body matching for control plane to clearly separate control plane and data plan matching
- simplified and improved stability for UI by moving all data processing into back-end and other simplifications

### Fixed
- fixed but with environment variable configuration for long, integer and integer list values
- removed call to ReflectionToStringBuilder.setDefaultStyle to avoid impacting toString globally for JVM
- fixed destination port and ip in Socks5CommandResponse which prevented SOCKS5 proxied connections
- fixed Subject Alternative Names with wildcards or other valid DNS name formats not supported by certain versions of the JDK (<= 1.8)
- fixed json body responses by returning blank or null fields, objects and arrays
- fixed generics for withCallbackClass to allow ExpectationResponseCallback to be specified as a Class (not only a string)

## [5.10.0] - 2020-03-24

### Added
- closure / object callbacks uses local method invocation (instead of Web Socket) when both the client in same JVM (i.e. ClientAndServer, JUnit Rule, etc)
- support to specify a fixed TLS X509 Certificate and Private Key for inbound TLS connections (HTTPS or SOCKS)
- ability to prioritise expectations such that the matching happens according to the specified priority (highest first) then creation order
- ability to create or update (if id matches) expectations from the client using upsert method
- ability to return chunked responses where each chunk is a specific size by using response connection options
- support for XmlUnit placeholders https://github.com/xmlunit/user-guide/wiki/Placeholders
- added ability to control (via configuration) whether matches fail fast or show all mismatching fields
- configuration to disable automatically attempted proxying of request that don't match an expectation and look like they should be proxied

### Changed
- improved X509 certificates by adding Subject Key Identifier and Authority Key Identifier
- stopped delay being applied twice on response actions (#721)
- improve support for clients making initial SOCKS or HTTP CONNECT requests over TLS
- replaced JSONAssert with JsonUnit to improve JSON matching and remove problematic transitive dependencies
- added more detail of cause of match failure

### Fixed
- fixed null point for expectation initialiser with file watcher in working directory specified with relative path
- fixed error resulting in enum not found exception for log events
- fixed error with parsing of json arrays for expectation responses with json body as json object not escaped string
- fixed meaning of disableSystemOut property so that only system out is disabled not all logging
- fixed key store type in key store factory to avoid issue with the JVM changing the defaults

## [5.9.0] - 2020-02-01

### Added
- added stopAsync method to ClientAndServer to allow stop without waiting
- log events for UPDATED_EXPECTATION and REMOVED_EXPECTATION
- ability to update existing expectation by id
- hot re-loading of expectation initialiser file
- addition configuration for web socket client event loop size
- addition configuration for action handler thread pool size
- exposed request raw bytes to object callbacks (allows forwarded requests body parsing that is inconsistent with Content-Type header)
- added support to delay socket closure using connection options
- added support to control trusted certificate authorities (trust store) for proxied & forwarded requests
- added support for two-way TLS (mTLS), also called client authentication
- now sends TLS X509 certificate from proxy (i.e. support forward client authentication / mTLS)
- added ability to dynamically create local unique Certificate Authority (CA) X.509 and Private Key to improve security of clients trusting the CA

### Changed
- performance improvements for header and cookie handling
- improved JSON validation errors by adding link to OpenAPI Specification
- removed duplicate packages between modules to prepare for java modules
- caught Jackson configuration exception to improve resilience with other Jackson versions in classpath
- moved Junit4 to separate module to reduce size of jar-with-dependencies, simplify code and increase build speed
- enabled case insensitive matching for regex matches
- improved documentation (i.e. on website)
- switched from Bouncy Castle to JDK for certificate and private key generation

### Fixed
- fixed error where ClientAndServer does fully wait for client to stop
- fixed ability to specific a log level of OFF
- fixed bug with keystore type configuration not being used in all places
- added file locking and jvm locking for expectation persistence file to avoid file corruption
- fixed verification incorrectly matching verifier non-empty bodies against empty request bodies
- stopped response callbacks for proxied requests blocking threads
- fixed bug that caused JSON bodies in specified expectations as raw JSON to ignore empty arrays and empty strings

### Security
- updated tomcat (used in integration tests) to version without vulnerabilities

## [5.8.1] - 2019-12-23

### Added
- changelog
- added configuration for all CORS headers
- added support for forward proxy authentication (via configuration)
- added support for overriding forward responses by class or closure
- requests sent to MockServerClient can be updated / enhanced i.e. to support proxies
- dynamic creation of a unique (i.e. local) Certificate Authority X509 Certificate and Private Key instead of using the fixed Certificate Authority X509 Certificate and Private Key in the git repo.
- configuration to require mTLS (also called client authentication or two-way TLS) for all TLS connections / HTTPS requests to MockServer
- configuration of trust store and client X.509 used during forwarded and proxied requests to endpoints requiring mTLS
- extended TLS documentation significantly

### Changed
- reduced default number of fail handles used by nio event loop
- improved performance and scalability of logging ring buffer
- improved performance of json serialisation
- deprecated isRunning and replaced with hasStopped and hasStarted to make behaviour more explicit and faster
- improved, simplified and unified handling of Content-Type for bodies
- remove closure callback clients and connections for expectation that no longer exist
- ensure WebSockets for closure callback auto re-connect for unreliable networks
- simplified XML and JSON of bodies in the log and UI
- improved logging for CORS
- added support for TLS with closure / WebSocket callbacks
- simplified handling of TLS and HTTP CONNECT (which is always TLS)
- improved JSON format for expectation to support objects instead of escaped strings

### Fixed
- fixed reading logLevel from system property or environment variable
- ensure all errors are printed to console
- removed TLSv1.3 to avoid any issues with JVM version that do not support TLSv1.3
- handle proxying requests without Content-Length header
- added support for JSON array for raw JSON in requests or responses body

### Security
- updated jetty (used in code examples) to version without vulnerabilities

## [5.8.0] - 2019-12-01

### Added
- added support for configuration via environment variables
- added support for overriding responses which an forward overridden request
- added persistence of expectations to file (as json)

### Changed
- ensured all Netty threads are marked as daemon to ensure MockServer does not prevent / delay JVM shutdown
- improved docker-compose example
- improved helm document & example to show how to provide configuration file or expectation initialiser
- improved performance and throttled load for UI

### Fixed
- WARN and ERROR is logged even if logLevel not yet initialised
- ensured exceptions thrown in Main method are always logged
- separated control plane and data plane matching to avoid reverse regex matches and other similar strange behaviour
- fixed handling of multiple parameters in Content-Type header
- autodetect WS or WSS for UI update WebSocket depending on HTTP or HTTPS
- stopped usage being printed multiple time under certain error scenarios

### Removed
- removed reentrant WebSocket prevention by creating WebSocket client per expectation to improve resilience

## [5.7.2] - 2019-11-16

### Added
- added setting to control maximum size of event log

### Changed
- performance enhancements
- improved matcher failure log messages to output detail at DEBUG level
- made log level configuration more resilient
- allowed exceptions to be thrown from all types of callback methods

### Fixed
- fixed duplicate logging or request when optimistic proxying
- added missing exception on bind error
- ensured client event bus is not static so it not shared across multiple client instances except were server port is identical

## [5.7.1] - 2019-11-09

### Added
- added disruptor ring buffer in front of log to improve performance
- added configuration to ensure MockServer certificate is not updated once created

### Changed
- improved performance with request matcher fast failure
- refactored CPU or memory hot spots
- switched logging to simpler more resilient approach without external dependencies

### Fixed
- fixed log levels to support disabling the log completely without impacting verifications
- ensured clear, reset and verify guarantee all pending log events are completed
- ensured all thread pools (i.e. added disruptor, etc) are stopped with stopping MockServer or Servlets
- respond with not found response (instead of hanging) when failure during template rendering

## [5.7.0] - 2019-11-01

### Added
- added support for retrieving requests and associated responses from log
- added support for access-control-request-headers with CORS

### Changed
- updated to Java 8
- made Jackson more relaxed when parsing JSON already validated by JSON Schema
- improved resilience of request and response parsing, such as when Content-Type is blank string
- improved proxy loop prevention to only break loops within a single instance of MockServer
- increased length of TLS keys to RSA 2048
- increased default request log size and maximum number of expectation

### Fixed
- added global thread-safety to javascript templates for local variables defined without keyword var

## [5.6.1] - 2019-07-21

### Changed
- delayed creation of Nashorn JS engine

### Fixed
- fixed multi-threaded handling of javascript templates
- fixed duplicate logging errors

## [5.6.0] - 2019-06-21

### Added
- added delay to actions that did not already have it
- added configuration for certificate authority private key and x509
- added support for large HTTP headers

### Changed
- simplified the certificate generation
- configured logback file appender programmatically

### Fixed
- ensure port binding exception are thrown and MockServer stops if port already allocated
- fixed log configuration to ensure no class loading exception thrown
- fixed control plane matching of expectations with notted entries




