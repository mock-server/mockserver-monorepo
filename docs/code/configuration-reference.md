# Configuration Reference

MockServer is configured through a single mechanism — a flat set of named properties — exposed in four forms: three equivalent static-store routes (system property, environment variable, properties file) and one per-instance form (`Configuration` object) that is not equivalent to the others. The authoritative list of every property, with defaults and inline documentation, is the example file checked into the repo:

**[`mockserver/mockserver.example.properties`](../../mockserver/mockserver.example.properties)**

That file is the source of truth. When a property is added, removed, or renamed it MUST be reflected there with a short comment explaining what it does. This doc explains the mechanism around it — *how* a value reaches the running server, *how* the layers interact, and *which* code does the loading.

For the user-facing rendition of the same properties (with examples and cross-links), see [Configuration Properties on the website](https://www.mock-server.com/mock_server/configuration_properties.html).

## How values are loaded

`ConfigurationProperties` is a static holder in `mockserver/mockserver-core/src/main/java/org/mockserver/configuration/`. Every property has a typed getter (e.g. `serverPort()`, `tlsMutualAuthenticationRequired()`) that resolves the value in this order — first hit wins:

1. **JVM system property** — `-Dmockserver.serverPort=1080`
2. **Properties file** — pointed to by `-Dmockserver.propertyFile=…` (default: `./mockserver.properties` if present)
3. **Environment variable** — `MOCKSERVER_SERVER_PORT=1080` (upper-snake form of the system-property suffix)
4. **Built-in default** — coded into the typed getter

This means system properties override properties file entries, which override environment variables, which override the built-in default. The resolution is implemented in `readPropertyHierarchically`: `System.getProperty(key, properties.getProperty(key, envOrDefault))`.

The loader logs the resolved property source on startup at `TRACE` — useful when a value isn't what you expect.

## Unknown-key warning

A misspelled property (e.g. `-Dmockserver.maxExpectatons=…` or `MOCKSERVER_METRICS_ENABLE=…`) is silently ignored by the per-key resolution above — the typo never matches a getter, so the default is used and "my config did nothing". To catch this, `ConfigurationProperties` runs a one-time validation pass at startup (in its static initialiser, so it fires regardless of whether a properties file is present, covering env-only and `-D`-only deployments). It logs a `WARN` naming any key that is in the `mockserver.` / `MOCKSERVER_` namespace but is **not** a recognised property:

- a JVM system property whose name starts with `mockserver.` but isn't a known key,
- an environment variable whose name starts with `MOCKSERVER_` but isn't a known key,
- a `mockserver.*` key in the loaded properties file that isn't a known key.

The recognised-key set is derived **reflectively from the `MOCKSERVER_*` constant fields** in `ConfigurationProperties` (the constant's value is the `mockserver.*` key; the constant's field name is the `MOCKSERVER_*` env-var key), so it can never drift from the actual properties — adding a new property automatically extends recognition. Keys outside the `mockserver.`/`MOCKSERVER_` namespace (e.g. `JAVA_HOME`, `PATH`) are never flagged, so unrelated environment variables do not produce false positives. The check can never throw, so it cannot break startup. Only key **names** are logged, never values.

## Configuration forms

| Form | Use when | Example |
|------|----------|---------|
| Properties file | Shipping reproducible config alongside the JAR / Docker image; CI default; what `mockserver.example.properties` documents | `mockserver.serverPort=1080` |
| Environment variable | Container deployments where each setting is its own knob | `MOCKSERVER_SERVER_PORT=1080` |
| JVM system property | Overriding a single value at launch time (CLI, IDE) | `-Dmockserver.serverPort=1080` |
| Programmatic — `Configuration` instance | Embedded MockServer in tests; per-instance overrides for properties whose enforcement sites read the instance | `new Configuration().serverPort(1080)` |

The first three are equivalent routes into the **static** `ConfigurationProperties` store and differ only in precedence (system property > properties file > environment variable > built-in default). The fourth, the `Configuration` instance, is **not equivalent**: setting a field on an instance (including via `PUT /mockserver/configuration`) does **not** write the static store. An instance value takes effect **only** at enforcement sites that read the `Configuration` instance rather than calling `ConfigurationProperties` directly. A property whose enforcement site reads `ConfigurationProperties` directly cannot be overridden via the instance form, regardless of what the DTO exposes. The `Configuration` instance falls back to `ConfigurationProperties` for any field that has not been explicitly set on the instance. Use the instance form for tests that run multiple MockServers in the same JVM with different settings, for properties whose enforcement sites read the instance.

### Capacity properties fixed at startup

`PUT /mockserver/configuration` writes the live `Configuration` instance, but a capacity property only takes effect if the subsystem it sizes can be resized. MockServer resizes what it can and reports what it cannot — it never silently accepts a value it will ignore.

| Property | Runtime behaviour on `PUT /mockserver/configuration` |
|----------|------------------------------------------------------|
| `maxLogEntries`, `maxEventLogSizeInBytes` | **Resized.** `MockServerEventLog.applyConfigurationCapacity()` resizes the bounded event-log deque; a shrink evicts the oldest entries immediately. |
| `maxExpectations` | **Resized.** `RequestMatchers.applyConfigurationCapacity()` resizes the expectation store (the state backend's queue when one is wired, otherwise the node-local queue) and the request-definition shadow map; a shrink evicts the eldest expectations immediately. |
| `controlPlaneAuditMaxEntries` | **Resized.** `AuditStore.setMaxSize(int)`; a shrink discards the oldest entries immediately. |
| `ringBufferSize` | **Fixed at startup.** The LMAX Disruptor ring is a fixed power-of-two array allocated when the server starts; resizing it would mean draining in-flight events and rebuilding every handler. |
| `maxWebSocketExpectations` | **Fixed at startup.** `LocalCallbackRegistry` builds its bounded registries lazily and exactly once. |

For the two fixed properties, a PUT that **explicitly supplies a value differing from the one in force** logs a WARN naming the ignored value and the value actually in force, and resets the field to the in-force value so the echoed response — and any later `GET /mockserver/configuration` — reports the truth. The request still succeeds, so a client that PUTs a whole configuration blob with unchanged values is unaffected and logs nothing. Because `ringBufferSize` *derives* from `maxLogEntries` when not set explicitly, the check is driven by what the client actually sent (`ConfigurationDTO`), not by the resolved getter — changing only `maxLogEntries` never produces a `ringBufferSize` warning.

Implemented in `HttpState.applyConfigurationUpdate(ConfigurationDTO)`, called from the `PUT /mockserver/configuration` route in `HttpRequestHandler`.

## Property categories

`mockserver.example.properties` groups the core properties into blocks. `ConfigurationProperties.java` defines the full set — there are currently **~170 properties** (one `private static final String MOCKSERVER_*` key constant per property). The categories below cover both the blocks in the example file and the additional groups defined only in `ConfigurationProperties.java`:

| Category | Representative properties |
|----------|--------------------------|
| Ports & proxy | `serverPort`, `proxyRemoteHost`, `proxyRemotePort` |
| Logging | `logLevel`, `disableSystemOut`, `detailedMatchFailures`, `compactLogFormat`, `metricsEnabled`, `slowRequestThresholdMillis`, `attachMismatchDiagnosticToResponse`, `closestMatchHintEnabled` |
| Dev mode | `devMode` |
| Memory usage | `maxExpectations`, `maxLogEntries`, `ringBufferSize` (in-flight log event ring buffer size, decoupled from `maxLogEntries` retention; default `min(maxLogEntries, 16384)`, rounded up to a power of two — see [memory-management.md](memory-management.md#ring-buffer-sizing)), `maxWebSocketExpectations`, `webSocketProxyMaxRecordedFrames` (max relayed WebSocket frames recorded per proxied passthrough connection; default `1000`, `0` disables frame recording — see [netty-pipeline.md](netty-pipeline.md#websocket-proxy-passthrough)), `webSocketProxyIdleTimeoutSeconds` (idle timeout in seconds for a proxied passthrough WebSocket relay; default `0` = disabled — see [netty-pipeline.md](netty-pipeline.md#websocket-proxy-passthrough)), `outputMemoryUsageCsv` |
| HTTP behaviour | `nioEventLoopThreadCount`, `actionHandlerThreadCount`, `webSocketClientEventLoopThreadCount`, `clientNioEventLoopThreadCount`, `streamingResponsesEnabled`, `maxStreamingCaptureBytes` |
| gRPC | `maxGrpcMessageSize`, `grpcBidiStreamingEnabled` |
| Matching | `matchersFailFast`, `matchExactCase` (when `true`, method/path/string-body matching is case-sensitive, and response reason-phrase matching in verification is also case-sensitive; header/cookie/query matching always stays case-insensitive — default `false`) |
| JSON Schema matching (internal tuning) | `jsonSchemaAllowRemoteRefs`, `mockserver.candidateIndexThreshold` — **JVM system-property-only tuning knobs**, not part of the standard static-store property set; see [Internal Tuning-Only System Properties](#internal-tuning-only-system-properties) below |
| Initialisation / OpenAPI | `initializationClass`, `initializationJsonPath`, `persistExpectations`, `persistedExpectationsPath`, `openAPIContextPathPrefix`, `openAPIResponseValidation`, `enforceResponseValidationForMocks`, `validateRequestsAgainstOpenApiSpec` (when `true`, requests matched by an OpenAPI-backed mock that violate the spec are rejected with a `400` instead of serving the mock response — default `false`; OpenAPI-backed expectations only), `generateRealisticExampleValues`, `validateProxyOpenAPISpec`, `validateProxyEnforce`, `failOnInitializationError` |
| CORS | `enableCORSForAPI`, `enableCORSForAllResponses`, `corsAllowOrigin`, `corsAllowMethods`, `corsAllowHeaders`, `corsAllowCredentials` |
| Default response headers | `defaultResponseHeaders` |
| Proxy auth | `forwardHttpsProxy`, `forwardSocksProxy`, `proxyAuthenticationUsername`, `proxyAuthenticationPassword`, `proxyAuthenticationRealm` |
| Data-plane auth | `dataPlaneAuthenticationRequired`, `dataPlaneBasicAuthenticationUsername`, `dataPlaneBasicAuthenticationPassword`, `dataPlaneBasicAuthenticationRealm`, `dataPlaneBearerAuthenticationToken`, `dataPlaneApiKeyAuthenticationHeader`, `dataPlaneApiKeyAuthenticationValue` (opt-in, default off — require Basic / Bearer / API-key credentials on the **mocked endpoints**, separate from control-plane and proxy auth; multi-scheme is accept-any, missing-but-required is fail-closed; see [tls-and-security.md](tls-and-security.md#data-plane-authentication)) |
| Forward resilience | `forwardConnectionPoolEnabled`, `forwardConnectionPoolMaxIdlePerKey`, `forwardConnectionPoolIdleTimeoutMillis`, `forwardConnectionPoolKeepAlive`, `forwardConnectionPoolMaxTotalPerKey`, `forwardSocketKeepAlive`, `forwardSocketKeepAliveIdleSeconds`, `forwardSocketKeepAliveIntervalSeconds`, `forwardSocketKeepAliveCount`, `forwardProxyRetryCount`, `forwardProxyRetryBackoffMillis`, `forwardProxyCircuitBreakerEnabled`, `forwardProxyCircuitBreakerFailureThreshold`, `forwardProxyCircuitBreakerWindowMillis` (upstream keep-alive connection pooling plus retry + per-upstream circuit breaker for matched FORWARD-class actions; `forwardConnectionPoolEnabled` defaults to `true` — idle HTTP/1.1 keep-alive upstream connections are pooled and reused (avoids ephemeral-port exhaustion under sustained forward load); set `false` to open a fresh upstream connection per request (the historical behaviour). Pooling is safe on by default: the forward client runs on a dedicated event-loop group disjoint from the server workers (so a loopback object-callback can never self-deadlock) and a channel is only pooled when its codec is genuinely quiescent — an `error()`/raw-bytes/drop-connection reply is never pooled. `forwardConnectionPoolKeepAlive` (opt-in, default `false`) raises the per-upstream idle ceiling from `forwardConnectionPoolMaxIdlePerKey` to `forwardConnectionPoolMaxTotalPerKey` (default 2000) so warm connections are RETAINED on release instead of closed — this eliminates the connection churn that otherwise caps a single instance under sustained high-rate, low-latency forwarding/injection (releases lag dispatch under fast turnover, so each request would otherwise open a fresh connection and the surplus be closed). With keep-warm off the release close-decision is byte-for-byte unchanged; warm connections are still reaped by `forwardConnectionPoolIdleTimeoutMillis` so the pool drains when load stops. `forwardSocketKeepAlive` (default `true`, a benign change from older versions that set none) enables TCP `SO_KEEPALIVE` on upstream connections so dead/half-open peers are detected faster and NAT/firewall mappings stay warm; on the native epoll transport the timers are tuned via `forwardSocketKeepAliveIdleSeconds` (60), `forwardSocketKeepAliveIntervalSeconds` (15), `forwardSocketKeepAliveCount` (4) for ~120s dead-peer detection, on NIO only `SO_KEEPALIVE` is set (timer tuning needs epoll); epoll is detected by reusing `NettyTransport.socketChannelClassFor(group)`; complements the pool's liveness checks/idle reaper (for the default 30s idle reaper, idle pooled connections are reaped before the 60s keepalive idle fires, so the wins are half-open detection during active/streaming requests and when `forwardConnectionPoolIdleTimeoutMillis` is raised); set `false` to restore no socket keepalive. Retry and breaker still default to off) |
| Forward upstream protocol | `forwardProxyHttp2Enabled` (default `false`; when `true`, matched FORWARD-class actions preserve the inbound request's protocol when forwarding, so an HTTP/2 inbound request is forwarded to the upstream as HTTP/2 instead of being forced to HTTP/1.1. HTTP/2 forwarding only happens over TLS+ALPN — a non-secure HTTP/2 forward is downgraded to HTTP/1.1 (no h2c) — and HTTP/2 forward connections are not pooled or multiplexed (the forward connection pool is HTTP/1.1-only). Default `false` is byte-identical to the historical always-HTTP/1.1 forward behaviour) |
| Control-plane JWT auth | `controlPlaneJWTAuthenticationRequired`, `controlPlaneJWTAuthenticationJWKSource`, `controlPlaneJWTAuthenticationExpectedAudience` |
| Control-plane OIDC auth | `controlPlaneOidcAuthenticationRequired`, `controlPlaneOidcIssuer`, `controlPlaneOidcJwksUri`, `controlPlaneOidcAudience`, `controlPlaneOidcRequiredScopes`, `controlPlaneOidcScopeClaim` (verify an external-IdP OIDC Bearer token — issuer/audience/exp/nbf/required-scopes — and record the verified `sub` as the control-plane audit principal; all off by default) |
| Control-plane authorization | `controlPlaneAuthorizationEnabled`, `controlPlaneScopeMapping` (coarse role-based authorization: map verified scope/group values to `read`/`mutate`/`admin` roles — `platform-admins=admin,qa-team=mutate,viewers=read` — and enforce a read/mutate split, returning `403` + audit `outcome=FORBIDDEN` on denial; off by default, requires a verified principal) |
| TLS inbound — dynamic | `certificateAuthorityPrivateKey`, `certificateAuthorityCertificate`, `dynamicallyCreateCertificateAuthorityCertificate`, `directoryToSaveDynamicSSLCertificate`, `preventCertificateDynamicUpdate`, `sslCertificateDomainName`, `sslSubjectAlternativeNameDomains`, `sslSubjectAlternativeNameIps` |
| TLS inbound — fixed | `privateKeyPath`, `x509CertificatePath` |
| mTLS | `tlsMutualAuthenticationRequired`, `tlsMutualAuthenticationCertificateChain` |
| TLS outbound | `forwardProxyTLSX509CertificatesTrustManagerType`, `forwardProxyTLSCustomTrustX509Certificates`, `forwardProxyPrivateKey`, `forwardProxyCertificateChain`, `forwardProxyClientCertificatesByHost` (per-host outbound mTLS cert/key map — see [tls-and-security.md](tls-and-security.md#per-host-outbound-mtls)) |
| Protocol selection | `tlsProtocols`, `proactivelyInitialiseTLS`, `useBouncyCastleForKeyAndCertificateGeneration`, `useSemicolonAsQueryParameterSeparator` |
| MCP | `mcpEnabled` |
| WASM rules | `wasmEnabled`, `wasmMaxMemoryPages` |
| gRPC | `grpcEnabled`, `grpcDescriptorDirectory`, `grpcProtoDirectory`, `grpcProtocPath` |
| DNS | `dnsEnabled`, `dnsPort` |
| HTTP/3 (QUIC) | `http3Port`, `http3AltSvcMaxAge`, `http3AdvertiseAltSvc`, `http3ConnectUdpEnabled`, `http3MaxIdleTimeout`, `http3InitialMaxData`, `http3InitialMaxStreamDataBidirectional`, `http3InitialMaxStreamsBidirectional`, `http3QpackMaxTableCapacity` |
| Service mesh / transparent proxy | `transparentProxyEnabled`, `transparentProxyTproxy`, `transparentProxyEbpf`, `transparentProxyEbpfMapPath` |
| OpenTelemetry | `otelMetricsEnabled`, `otelTracesEnabled`, `otelEndpoint`, `otelMetricsExportIntervalSeconds`, `otelMetricsTemporality`, `otelPropagateTraceContext`, `otelGenerateTraceId` |
| Prometheus Remote Write | `prometheusRemoteWriteEnabled`, `prometheusRemoteWriteUrl`, `prometheusRemoteWriteProtocolVersion`, `prometheusRemoteWriteIntervalSeconds`, `prometheusRemoteWriteBearerToken`, `prometheusRemoteWriteBasicAuthUsername`, `prometheusRemoteWriteBasicAuthPassword`, `prometheusRemoteWriteHeaders` |
| Chaos auto-halt | `chaosAutoHaltEnabled`, `chaosAutoHaltErrorThreshold`, `chaosAutoHaltWindowMillis`, `connectionLifecycleChaosEnabled` (master switch for connection-lifecycle fault injection — mid-response RST, host-scoped slow close, HTTP/2 GOAWAY, preemption simulator; default `true`), `connectionLifecycleAutoHaltCountsRst` (when `true`, a connection-lifecycle RST counts as a destructive fault for the chaos auto-halt circuit-breaker; default `true`) |
| Preemption simulation | `preemptionSimulationMaxDrainMillis` (hard upper bound in milliseconds on a preemption simulation's drain window and TTL, clamping values from `PUT /mockserver/preemption`; default `86400000` — 24 hours; `0` disables the cap) |
| Rate limiting | `rateLimitMaxNamedQuotas` |
| SLO verdicts | `sloTrackingEnabled`, `sloWindowRetentionMillis`, `sloWindowMaxSamples` |
| Load generation | `loadGenerationEnabled`, `loadGenerationSuppressEventLog`, `loadGenerationMaxVirtualUsers`, `loadGenerationMaxInFlightRequests`, `loadGenerationMaxRequestsPerSecond`, `loadGenerationMaxDurationMillis`, `loadGenerationMaxSteps` |
| Breakpoints | `breakpointTimeoutMillis`, `breakpointMaxHeld` (breakpoint activation is now via the matcher-based registry REST API) |
| Template restrictions | `javascriptAllowedClasses` (empty default = NO host class resolves; `*` restores unrestricted), `javascriptDisallowedClasses` (legacy deny-list; setting it *widens* the safe default), `javascriptDisallowedText`, `javascriptTemplateExecutionTimeout` (millis; default 5000, 0/negative disables — wall-clock cancellation of runaway JS templates), `velocityDisallowClassLoading` (**default `true`** — SecureUberspector installed), `velocityDisallowedText`, `mustacheDisallowedText` |
| Template sample data | `templateFakerSeed` (long; default 0 = unseeded/random. Non-zero seeds the template `faker` helper — Velocity `$faker`, Mustache `{{faker.*}}`, JavaScript `faker` — deterministically so faker-driven templates produce reproducible fixtures across runs. The seed initialises a per-engine `Faker`; its value sequence is deterministic for a given order of renders, strongest for sequential generation. The template analogue of the OpenAPI `SampleDataGenerator` fixed seed) |
| Drift detection | `driftSemanticAnalysisEnabled`, `driftResponseTimeThresholdMs`, `driftAlertWebhookEnabled`, `driftAlertWebhookUrl`, `driftAlertSeverityThreshold`, `driftAlertCooldownMillis` |
| Control-plane audit | `controlPlaneAuditEnabled`, `controlPlaneAuditMaxEntries`, `controlPlaneAuditReads`, `auditLogFile` |
| Clustered state | `stateBackend`, `clusterEnabled`, `clusterName`, `clusterTransportConfig`, `clusterSharedTimesEnabled` |
| Blob store | `blobStoreType`, `blobStoreBucket`, `blobStoreRegion`, `blobStoreEndpoint`, `blobStoreKeyPrefix`, `blobStoreAccessKeyId`, `blobStoreSecretAccessKey`, `blobStoreContainer`, `blobStoreConnectionString`, `blobStoreProjectId`, `blobStoreRestoreTimeoutSeconds` (seconds MockServer waits at startup for the persisted-expectations document to be read back from a cloud blob store before continuing without it; default `10`; `0` or negative skips the startup restore entirely. The read happens before any port is bound, so this bounds how long an unreachable blob-store endpoint can delay startup — see [event-system.md](event-system.md#startup-restore-cloud-blob-stores-only)). Like the rest of the `blobStore*` family it is **mirrored in `ConfigurationDTO`** so `GET /mockserver/configuration` reports it, but it is read once during startup, so a runtime `PUT` cannot change the restore it governs |
| Async messaging | `asyncKafkaBootstrapServers`, `asyncMqttBrokerUrl`, `asyncRecordedMessageMaxEntries`, `asyncAmqpUri` (default AMQP connection URI used when a `PUT /mockserver/asyncapi` request does not include `brokerConfig.amqpUri`, e.g. `amqp://guest:guest@localhost:5672/`; empty by default — broker must be specified per-request; enforcement site reads the static store directly) |
| JSON Schema matching | `jsonSchemaAllowRemoteRefs` — JVM system property only (see [Internal Tuning-Only System Properties](#internal-tuning-only-system-properties)) |
| LLM mocking | `llmProvider`, `llmApiKey`, `llmModel`, `llmBaseUrl`, `llmBackendsConfig`, `llmSemanticMatchingEnabled`, `llmVcrStrict`, `fixtureBodyRedactFields`, `maxLlmConversationBodySize` (maximum request body size in bytes for LLM conversation matching; default `1048576` — 1 MiB; clamped to [`16384`, `67108864`]; bodies exceeding the limit are treated as no-match) |
| LLM metrics & budget | `llmMetricsEnabled`, `llmCostBudgetUsd`, `perExpectationMetricsEnabled` |
| Recorded expectations | `deduplicateRecordedExpectations`, `redactSecretsInRecordedExpectations` |
| Event log / dashboard | `redactSecretsInLog` |
| Lifecycle | `stopDrainMillis` (maximum milliseconds the graceful shutdown waits for in-flight requests to drain before tearing down the event loops; default `15000`; `0` disables draining — the pre-7.2 stop-immediately behaviour; negative values are clamped to `0`. Also the default drain window a preemption simulation uses when `PUT /mockserver/preemption` omits `drainMillis`) |

The example file documents the most commonly tuned properties (≈220 lines). For the complete list including newer subsystems, read `ConfigurationProperties.java` or the consumer reference page.

### Properties wired onto the `Configuration` instance

The 27 properties below previously existed **only** on `ConfigurationProperties`, with no `Configuration` accessor and no `ConfigurationDTO` field. That made them unreachable from the instance, DTO and REST routes *by construction*: `PUT /mockserver/configuration` could not set them at all. Each now has a `Configuration` getter/fluent-setter pair and a `ConfigurationDTO` field, so all three routes work and the usual resolution order applies (instance field → static store → env var / property file → default).

Three of them are **write-only**: settable over `PUT /mockserver/configuration` and on the instance, but `GET /mockserver/configuration` returns `***REDACTED***` (`ConfigurationProperties.REDACTED_VALUE`) instead of the real value. A `PUT` whose value **contains** that mask — echoed back untouched, or with text typed around it (`sk-***REDACTED***`) — is ignored rather than applied, so a GET-then-PUT of the whole configuration blob never destroys a working credential. `contains`, not `equals`: an equals-only guard covered the untouched round trip and wrote every edited-around form through verbatim, which both destroyed the held credential and made the literal mask the outbound one. A value carrying no mask is a real credential and is applied, including the empty value that clears it.

Two further properties carry a credential *inside* a structured value — `prometheusRemoteWriteHeaders` (a `k=v,k2=v2` list) and `llmBackendsConfig` (a file path, or an inline JSON document) — and are masked per header / per field by `EmbeddedCredentialRedaction`, so the surrounding non-secret configuration stays readable.

The write path is the inverse: `ConfigurationProperties.restoreRedactedValue(...)` rebuilds each masked part from the value the target already holds. Its contract has **two** halves, and only enforcing one of them was the original defect:

| Failure | Rule |
|---|---|
| the mask becomes the outbound credential | the result never contains the mask — checked in each `restore*` method and again in `restoreRedactedValue` |
| the credential is silently deleted instead | a mask that cannot be resolved makes the **whole value** unmergeable — never "drop that part and write the rest" |

Both return `null`, meaning "leave the held value untouched", and **every** refusal is logged: a `PUT` that writes nothing must not answer `200 OK` in silence. The *untouched* round trip is not a refusal and is deliberately silent for both shapes — the embedded restores return the held value verbatim when the incoming value is exactly its masked form, and `restoreRedactedValue` short-circuits a credential-named property whose incoming value is exactly the mask. Otherwise a config-as-code tool that GETs, edits one unrelated property and PUTs the whole blob would log a security-flavoured warning per credential on every apply. A mask is unresolvable when it is buried inside a value, welded to extra text (`***REDACTED***-my-new-key`, which reads as a *new* credential typed over the mask), in a field that is not credential-named, names a header/backend nothing is held under, or is ambiguous (below). A held value that itself contains the literal mask is also unresolvable, which locks that property against edited `PUT`s until a real value is set — accepted, logged, and recoverable, rather than weakening the "never store the mask" invariant with an exception.

Header names are matched **exactly first**, falling back to a case-insensitive match only when it is unambiguous — exactly one held name and one incoming name share that spelling. The fallback exists because re-casing a header name is a legitimate edit; the exact-match-first rule exists because `PrometheusRemoteWriteExporter#parseHeaders` is case-*sensitive* and applies its result additively, so `X-Api-Key` and `x-api-key` are two headers and both are sent. Folding them into one case-insensitive key resolved one credential onto both names and destroyed the other.

Two parsing rules are load-bearing for disclosure. A JSON document is detected more widely than "starts with `{` or `[`" — a quoted member name counts and a leading BOM is stripped — so a document embedded behind a prefix is masked whole rather than disclosed; and the mapper enables `FAIL_ON_TRAILING_TOKENS`, without which Jackson parses only the first document and the rest of the value (which may hold the credential) is judged to contain no secret and returned verbatim. A file path matches neither detection rule and passes through byte-identical.

Both shapes go through the same door. A **whole-value** credential matches neither structured shape, so past the untouched-round-trip short-circuit it falls through the per-header / per-field restore and is decided entirely by the trailing `containsRedactionMask` check — refused (and logged) if it still carries the mask anywhere, applied otherwise. That is why the check has to live in `restoreRedactedValue` rather than in each `restore*` method: it is the *only* thing standing between a whole-value credential and the mask being written through.

`restoreRedactedValue` is not the only write path: `ConfigurationDTO.applyTo` merges against the held value and comes through it, while `ConfigurationDTO.buildObject` builds a *fresh* configuration with nothing to merge against and applies the simpler `containsRedactionMask` → leave-unset rule directly, and **silently** — it has no held value whose loss it could be warning about. Leaving it **unset** (rather than storing the mask, or an empty value) is what lets the static store, property file or environment variable still resolve the credential. That silence is safe only while `buildObject` builds a throwaway configuration: `ConfigurationSerializer.deserialize` has no production caller today, and wiring it to the "save a `GET` and reload it as a JSON configuration file" flow would turn a mask-carrying value into silent credential loss. A third credential property of either shape must be wired into **both**.

| Property | Type | Default | Notes |
|----------|------|---------|-------|
| `customJsonUnitMatchersClass` | String | `""` | Custom json-unit matcher provider class |
| `fixtureBodyRedactFields` | String | `""` | Comma-separated JSON field names redacted from recorded fixtures |
| `llmBackendsConfig` | String | `""` | Path to the named-backends JSON file |
| `llmBaseUrl` | String | `""` | Base URL override for the default LLM backend |
| `llmInferUsageEnabled` | Boolean | `false` | Approximate token-usage inference |
| `llmModel` | String | `""` | Model for the default LLM backend |
| `llmOptimisationMaxCalls` | Integer | `200` | Cap on calls included in an optimisation report |
| `llmProvider` | String | `""` | Provider for the default LLM backend |
| `llmRequestTimeoutMillis` | Long | `30000` | Per-request timeout for outbound LLM calls |
| `llmSemanticMatchingEnabled` | Boolean | `false` | Fuzzy LLM-judged prompt matching |
| `llmVcrStrict` | Boolean | `false` | Strict VCR mode for LLM fixtures |
| `otelEndpoint` | String | `""` | OTLP endpoint; falls back to `OTEL_EXPORTER_OTLP_ENDPOINT` |
| `otelMetricsEnabled` | Boolean | `false` | Export metrics via OTLP |
| `otelMetricsExportIntervalSeconds` | Long | `60` | **Clamped to ≥ 1** on both the instance and static routes |
| `otelMetricsTemporality` | String | `cumulative` | `cumulative` or `delta`; unknown values fail safe to cumulative |
| `otelTracesEnabled` | Boolean | `false` | Emit GenAI semantic-convention spans |
| `prometheusRemoteWriteBasicAuthUsername` | String | `""` | Basic-auth username (not a secret; not masked) |
| `prometheusRemoteWriteEnabled` | Boolean | `false` | Push metrics to a remote-write endpoint |
| `prometheusRemoteWriteHeaders` | String | `""` | Extra headers as a `key=value` list |
| `prometheusRemoteWriteIntervalSeconds` | Long | `60` | **Clamped to ≥ 1** on both the instance and static routes |
| `prometheusRemoteWriteProtocolVersion` | String | `v1` | `v1` or `v2`; unknown values fall back to `v1` |
| `prometheusRemoteWriteUrl` | String | `""` | Remote-write endpoint URL |
| `regexMatchingTimeoutMillis` | Long | `5000` | Regex evaluation budget; `0`/negative disables |
| `xpathMatchingTimeoutMillis` | Long | `5000` | XPath evaluation budget; `0`/negative disables |
| `llmApiKey` | String | `""` | **Write-only** — settable, masked on read |
| `prometheusRemoteWriteBasicAuthPassword` | String | `""` | **Write-only** — settable, masked on read |
| `prometheusRemoteWriteBearerToken` | String | `""` | **Write-only** — settable, masked on read |

Masking lives in `ConfigurationDTO`: the JSON getters return the mask while `buildObject()`/`applyTo()` read the private fields, so the wire is clean without breaking the write path. `@JsonIgnore`-d `get…RawValue()` accessors expose the real value in-process only. `ConfigurationDTOCredentialMaskingTest` asserts non-leakage, the round-trip guard, and that the write path still works.

### The write-path mask guard

Whether a property is readable, masked or omitted on the way **out** says nothing about what may arrive on the way **in**. Assuming otherwise produced the same defect twice, so the write-path rule is now applied to the whole credential-shaped surface: **every** `ConfigurationDTO` property whose name is credential-shaped under `isSensitivePropertyName(...)` refuses a value carrying the redaction mask.

Three read shapes, one write rule:

| Read shape | Properties | Why the mask still reaches the write path |
|---|---|---|
| **masked** — getter returns the mask | `llmApiKey`, `prometheusRemoteWriteBearerToken`, `prometheusRemoteWriteBasicAuthPassword` | the mask is exactly what `GET /mockserver/configuration` hands back |
| **omitted** — `@JsonIgnore`-d getter | `proxyAuthenticationPassword`, `forwardProxyAuthenticationPassword`, `dataPlaneBasicAuthenticationPassword`, `dataPlaneBearerAuthenticationToken`, `dataPlaneApiKeyAuthenticationValue`, `certificateAuthorityPrivateKey`, `forwardProxyPrivateKey`, `blobStoreAccessKeyId`, `blobStoreSecretAccessKey`, `blobStoreConnectionString`, `clusterFanInPeerAuthToken` | absent from a `/mockserver/configuration` round trip, but the diagnostic views still print the mask |
| **readable** — returned in clear, because the value is not a secret | `privateKeyPath`, `controlPlanePrivateKeyPath`, `dataPlaneApiKeyAuthenticationHeader`, `dashboardAnalyticsKey` (see `READABLE_DESPITE_CREDENTIAL_SHAPED_NAME`) | same — being readable on one endpoint does not stop the mask arriving from another |

The reachable path is not `/mockserver/configuration` at all. `ConfigurationProperties.redactSensitiveValue(...)` masks every credential-*shaped* **name**, so `GET /mockserver/config`, `--print-config` and the dashboard's Server Info tab render all of the above as `***REDACTED***`. An operator who reads one of those, edits a neighbouring setting and `PUT`s the result supplies the literal mask; storing it destroyed the working credential, broke every call authenticated with it, logged nothing, and answered `200 OK`.

| Path | Rule |
|---|---|
| `applyTo` | routed through `restoreRedactedValue(...)`, which refuses a value containing the mask and **logs** why (`dropUnmergeableValue`), leaving the credential in force untouched. The bare mask warns too for these properties — see *When the refusal is silent* below |
| `buildObject` | `maskFreeOrUnset(...)` leaves the field **unset** — there is nothing held to restore from, and storing `""` would pin an empty value that shadows the property file / environment variable behind it. It does **not** log: no held value is being discarded and no live configuration left inconsistent, matching the rule the value-embedded credentials already apply here |

Refusal is by *containment*, not equality: `***REDACTED***-my-new-key` reads as a new credential typed over the mask, and welding the two together persists neither. Every other value is untouched — a real credential is applied, and `""` still clears, over the JSON wire path as well as in process (`""` binds as `""`, reaches `applyTo` and clears; there is no empty-string-to-null coercion on `String` fields).

Refusing **before** the setter also matters for the properties that validate on write. `forwardProxyPrivateKey` and `controlPlanePrivateKeyPath` call `fileExists(...)`, so the mask used to be rejected by *throwing* from the middle of `applyTo` — after neighbouring edits in the same body had already been committed to the live `Configuration`, which `HttpRequestHandler` then turns into a `400` over half-mutated state. The whole body is all-or-nothing again.

**When the refusal is silent.** Refusing the mask is only half the contract; the other half is whether the operator is told. `restoreRedactedValue` stays silent for the **untouched round trip** — the bare mask sent straight back — because a config-as-code tool that `GET`s, edits one unrelated property and `PUT`s the whole blob would otherwise log a security-flavoured warning per credential on every apply, and anyone alerting on `WARN` would read it as a credential having been lost.

That silence is keyed on `WHOLE_VALUE_MASKED_CREDENTIAL_PROPERTIES` — the three properties `GET /mockserver/configuration` actually returns as the bare mask — and **not** on `isSensitivePropertyName`. The wider predicate matches every credential-shaped name, including all fifteen above, none of which has a round trip that can produce the bare mask: the only surfaces that print it for them are the diagnostic views, which are not `PUT` bodies. Silencing those silenced the exact operator mistake this section exists for — credential destroyed, nothing logged, `200 OK`. `ConfigurationDTOCredentialMaskingTest` keeps the set in step with the DTO by deriving it from the getters that really do return the mask, and asserts the warning end to end over `applyTo`.

`ConfigurationDTOCredentialMaskingTest` section 4 enumerates this surface **reflectively** — every credential-shaped `String` property, with no exclusions — rather than from a list, so a credential-shaped property added later is covered the day it is added. It also asserts, through `effectiveConfiguration()` rather than by re-applying its own filter, that the diagnostic surfaces really do render each one as the mask, so the round trip it guards is one that can actually occur; a property whose system-property key diverged from its DTO property name would fail there rather than silently drop out of the guard. Both non-vacuity floors in that class are **ratchets** — raise them when a property is added, never lower them.

**Reachability ≠ enforcement.** Wiring a property onto `Configuration` makes it *settable*; the enforcement sites for these 27 still read the static store, so they are recorded in `ConfigurationCallSiteGuardTest.KNOWN_INSTANCE_UNREACHABLE_DEFECTS` until each site is changed to consult the instance. That map is a ratchet: fixing a site makes its entry stale and fails the build until the line is deleted.

### `redactSecretsInRecordedExpectations`

Opt-in (default `false`). When `true`, MockServer masks sensitive header values — `Authorization`, `Proxy-Authorization`, `Cookie`, `Set-Cookie`, `x-api-key`, `api-key` (which also covers bearer/token credentials carried in those headers) — with `***REDACTED***` on the recorded-expectation retrieval path (`HttpState.postProcessRecordedExpectations`). Because that path feeds every retrieve format, redaction covers retrieve-as-JSON, retrieve-as-code (generated client code), and persisted recorded JSON. Redaction reuses `FixtureRedactor` (the same masking already applied on the import path) and operates on copies, so the live event log is never mutated. On this path it uses the constraint-preserving variant (`redact(..., preserveConstraints=true)`), so the redacted recordings keep their original `times`, `timeToLive`, `priority`, and `id` — only the sensitive values are masked.

**Trade-off:** a redacted recorded expectation can no longer replay against an upstream that requires the masked credential, so this is off by default — enable it only when you want to share or persist recordings without leaking proxied secrets.

### `redactSecretsInLog`

Opt-in (default `false`). When `true`, MockServer masks the same sensitive header values — `Authorization`, `Proxy-Authorization`, `Cookie`, `Set-Cookie`, `x-api-key`, `api-key` — with `***REDACTED***` in the **live event log and dashboard**: the requests/responses returned by `retrieveLogMessages`, `retrieveRecordedRequests` and `retrieveRecordedRequestsAndResponses` (and the JSON/HAR/cURL/OpenAPI/Postman export formats derived from them) and the request/response panes shown in the dashboard event view. JSON body fields named in `fixtureBodyRedactFields` are masked too. Reuses `FixtureRedactor` and operates only on clones produced by `LogEntry`'s display/retrieve getters (`getHttpUpdatedRequests()`/`getHttpUpdatedResponse()` for the log/dashboard view, `getRedactedHttpRequests()`/`getRedactedHttpResponse()` for the recorded-request/export retrieval paths) — the underlying log entry read by request matching and verification keeps the original, unredacted values, so enabling this does **not** change matching or verification behaviour. Off by default so the log is byte-for-byte unchanged.

This complements `redactSecretsInRecordedExpectations` (which masks the recorded-expectation *export* path); set both when you want secrets masked everywhere they could be observed.

**Redaction is not retroactive.** `getHttpUpdatedRequests()`/`getHttpUpdatedResponse()` memoise the rendered view of an entry on first read, so a log entry that was already rendered before redaction was enabled keeps its unredacted form for the remainder of its life in the ring. Enabling `redactSecretsInLog` protects entries rendered *after* the change; it does not scrub secrets already captured. If secrets have already reached the log, enable redaction **and** clear the event log rather than relying on redaction alone.

**Enabling it on a `Configuration` instance now works.** Until recently the redactor was resolved only from the static `ConfigurationProperties` store, so enabling redaction programmatically or via `PUT /mockserver/configuration` had no effect and secrets stayed in clear on every surface above. The redaction accessors now take the effective `Configuration`, wired through the event log, the recorded-requests disk archive, the dashboard and the JSON log-message serializer. Note `fixtureBodyRedactFields` has no `Configuration` accessor and is read from the static store only — that is its sole source, not a gap.

### `controlPlaneAuditEnabled`, `controlPlaneAuditMaxEntries`, `controlPlaneAuditReads`, `auditLogFile`

Opt-in, append-only, bounded, in-memory audit log of control-plane *mutations* (who/what/when/where/outcome) that backs `GET /mockserver/audit` (see [docs/code/event-system.md](event-system.md#control-plane-audit-log)). It records redacted, structural metadata only — never request headers or bodies.

| Property | Default | Meaning |
|----------|---------|---------|
| `controlPlaneAuditEnabled` | `false` | When `false`, no audit entries are recorded and control-plane operations behave byte-for-byte identically (the audit emit returns immediately). Set to `true` to opt in. |
| `controlPlaneAuditMaxEntries` | `1000` | Maximum entries retained in the bounded ring; the oldest is evicted once full. The singleton's *initial* capacity is read once at `AuditStore` class initialization (`new AuditStore(maxFromConfig())`), so a system property, environment variable or properties-file value set **after** the class loads does not change it. The capacity is **resizable at runtime via `PUT /mockserver/configuration`**, which calls `AuditStore.setMaxSize(int)` — a shrink discards the oldest entries immediately. |
| `controlPlaneAuditReads` | `false` | When `false`, only mutations (and `reset`) are audited. Set to `true` to also audit control-plane reads (GET requests and read-only PUTs such as `/retrieve`, `/verify`, `/diff`). No effect unless `controlPlaneAuditEnabled` is `true`. |
| `auditLogFile` | `""` (off) | Optional path to a durable NDJSON audit file. When set, each recorded entry is *also* appended (one compact JSON object per line) by `AuditFileSink` — a separate writer that observes the same entries, leaving the in-memory ring untouched — giving a restart-and-`reset`-surviving trail. Path resolved once on first write; parent dirs created; append-only (rotation out of scope); fail-soft (one WARN then self-disable on IO error). No effect unless `controlPlaneAuditEnabled` is `true`. |

The recorded principal is **best-effort and unverified** in this version: the JWT `sub` is read with no signature verification, or the mTLS client-certificate CN, else `anonymous`. The raw token is never stored.

### `sloTrackingEnabled`, `sloWindowRetentionMillis`, `sloWindowMaxSamples`

Opt-in SLO sample tracking that backs `PUT /mockserver/verifySLO` (see [docs/code/slo-verdicts.md](slo-verdicts.md)).

| Property | Default | Meaning |
|----------|---------|---------|
| `sloTrackingEnabled` | `false` | When `true`, MockServer records a windowed sample (latency, error flag, upstream host) for every forwarded upstream round-trip so a verdict can be computed. When `false` the recording funnel is a no-op, so the forward hot path pays nothing. |
| `sloWindowRetentionMillis` | `600000` (10 min) | Maximum age of a retained sample, measured relative to the newest recorded sample. Older samples are evicted lazily on record and on query. Set the trailing window of history you want verdicts to draw on. |
| `sloWindowMaxSamples` | `50000` | Hard cap on retained samples; the oldest is evicted when the cap is exceeded. Bounds memory regardless of throughput. |

Because tracking is off by default, the `verifySLO` endpoint returns `400` with `SLO tracking not enabled (set sloTrackingEnabled=true)` until you enable it.

### `loadGeneration*`

Opt-in API-driven load generation that backs `PUT /mockserver/loadScenario` (see [docs/code/load-generation.md](load-generation.md)).

| Property | Default | Meaning |
|----------|---------|---------|
| `loadGenerationEnabled` | `false` | When `false`, `PUT /mockserver/loadScenario` returns `403`. Must be set to `true` to opt in — MockServer never self-generates traffic unless explicitly enabled. |
| `loadGenerationSuppressEventLog` | `true` | Keep the server's own load-generation traffic out of the request event log. The marker is an in-process flag, never a wire header, so it stays on the driver and never reaches an upstream target. Set to `false` to record load-generation traffic in the driver's event log too. |
| `loadGenerationMaxVirtualUsers` | `50` | Hard cap on the concurrent virtual users a scenario may drive; a profile requesting more is rejected at validation. |
| `loadGenerationMaxInFlightRequests` | `200` | Hard cap on outstanding (not-yet-completed) requests, enforced live by an in-flight semaphore so a slow target cannot let the scenario queue unbounded work. |
| `loadGenerationMaxRequestsPerSecond` | `500` | Hard cap on dispatch rate, enforced live by a token bucket. |
| `loadGenerationMaxDurationMillis` | `3600000` (1 h) | Hard cap on how long a scenario may run; a longer profile is rejected at validation, so a forgotten scenario cannot drive traffic indefinitely. |
| `loadGenerationMaxSteps` | `50` | Hard cap on the number of request steps a single scenario may define. |

The caps are enforced both at validation (VU count, duration, step count) and live at dispatch (in-flight, RPS), so the feature cannot self-DoS the server even when enabled.

### `maxGrpcMessageSize`

Maximum size in bytes of a single **decoded** gRPC message, checked both against the declared frame length and against the running total while decompressing (so a compression bomb cannot exceed it either). Default `4194304` (4 MiB), matching grpc-java's and grpc-go's default `maxReceiveMessageSize`.

A request message above the limit is rejected with `grpc-status: 8 RESOURCE_EXHAUSTED` — the status the gRPC specification and both reference implementations use for exceeding the receive-message-size limit, so a client can tell "too big" from "server broke". (It was previously reported as `13 INTERNAL`.)

This is deliberately separate from `maxRequestBodySize`, which bounds the whole aggregated HTTP body: one HTTP body may carry several gRPC messages (client streaming), and the gRPC limit applies per message. The limit is what stops a declared frame length from allocating unbounded memory, so raise it only if you intentionally mock large gRPC messages.

Defined once in `GrpcFrameCodec.maxMessageSize()` and read from there by `IncrementalGrpcFrameDecoder`; the two previously kept separate hard-coded copies that could drift.

## Internal Tuning-Only System Properties

The properties in this section bypass the standard four-equivalent-forms mechanism (`ConfigurationProperties` / `Configuration` instance / environment variable / properties file). They are read directly via `System.getProperty` at the point of first use and have **no** environment-variable form, no properties-file form, no `Configuration` DTO setter, and no entry in `mockserver.example.properties`. They exist for low-level tuning and testing, and are intentionally absent from the standard property surface.

### `mockserver.candidateIndexThreshold`

**Default** `64`. **Clamp**: values less than `2` are silently clamped to `2`.

Controls when `RequestMatchers.firstMatchingExpectation` switches from the full linear scan of the `CircularPriorityQueue` to the `CandidateIndex` matching-acceleration structure. When the number of active expectations is **less than** this threshold, the untouched linear scan always runs (no change in behaviour or performance at any expectation count below the threshold). When the expectation count **reaches or exceeds** the threshold, the candidate index engages and narrows the scan to a `(method, path)` bucket union the fallthrough list (see [CandidateIndex](request-processing.md#candidateindex--matching-acceleration-for-large-expectation-sets) in `request-processing.md`).

**Read in**: `RequestMatchers.resolveCandidateIndexThreshold()` via `System.getProperty("mockserver.candidateIndexThreshold")`, called once per `RequestMatchers` construction. Changing it at runtime after construction has no effect (the resolved value is stored as an instance field). In tests, use `RequestMatchers.withCandidateIndexThreshold(int)` to inject a low threshold without mutating global state.

**How to set**: pass `-Dmockserver.candidateIndexThreshold=N` on the JVM command line. This property is **not** recognised by the unknown-key warning scan (it is not a `MOCKSERVER_*` constant in `ConfigurationProperties`), so no warning is emitted if it is misspelled.

**When to change**: the default of `64` is set above the measured ≈16 no-regression floor and at the n=100 clear-win point. Raising it delays index engagement (more expectations are handled by the linear scan). Lowering it engages the index earlier — useful only for benchmarking or high-expectation-count workloads where the index is observed to help but the default threshold is not reached.

### `rateLimitMaxNamedQuotas`

Default `10000` (env `MOCKSERVER_RATE_LIMIT_MAX_NAMED_QUOTAS`). Caps the number of distinct named counters held in the in-process `RateLimitRegistry` that backs the declarative `rateLimit` expectation clause (see [docs/code/request-processing.md](request-processing.md) and [docs/code/domain-model.md](domain-model.md)). Each distinct `rateLimit.name` (or, when `name` is omitted, each distinct expectation id) is one counter. Once the cap is reached, a request for a *new* counter key **fails open** (is allowed) rather than evicting an existing counter, so an unbounded set of keys can never exhaust memory and can never silently start rate-limiting a previously-unseen key. There is no enable flag — the registry is inert until an expectation carries a `rateLimit` clause.

### `failOnInitializationError`

Opt-in (default `false`). By default a malformed `initializationJsonPath` / `initializationOpenAPIPath` file or a broken `initializationClass` logs a single `WARN` and yields **zero** expectations from that source while the server still finishes starting — a silent, hard-to-notice failure mode in CI and Kubernetes. When `true`, any such load failure throws an `ExpectationInitializerException` from the `HttpState` constructor, so startup fails (non-zero exit / propagated exception) instead of continuing with missing expectations. Use it in pipelines and orchestrated deployments where a half-initialised mock is worse than a crash. The throw happens inside `ExpectationInitializerLoader.failFastIfConfigured(...)`, immediately after the existing WARN log.

This pairs with the readiness signal (below): fail-fast catches a *broken* initializer; the readiness probe gates traffic until a *slow* (but valid) initializer finishes.

## Readiness vs liveness

`PUT /mockserver/status` and the configurable `livenessHttpGetPath` (`GET`) both answer `200` the instant the server port binds — **before** expectation initializers / OpenAPI seeding complete. That is the correct liveness semantic (the process is alive) but the wrong readiness semantic (it should not receive traffic yet).

`GET /mockserver/ready` (alias `GET /ready`) is the readiness signal:

- returns `503 {"status":"NOT_READY"}` until the synchronous `HttpState` constructor (expectation initializers, OpenAPI seeding, gRPC descriptor loading) has completed, then
- returns `200 {"status":"READY"}` thereafter.

It is backed by a thread-safe `volatile boolean` flipped as the **last** action of the `HttpState` constructor (`HttpState.isInitializationComplete()`), so a partially-constructed server never reports ready. The Helm chart points the readiness probe at `/mockserver/ready` and the liveness probe at the always-200 liveness path (see [helm.md](../infrastructure/helm.md)).

## Adding a property

Five places to touch — there are no implicit registrations.

1. **Typed getter + private setter** in `mockserver/mockserver-core/src/main/java/org/mockserver/configuration/ConfigurationProperties.java` — define the constant key, the system-property name, the env-var alias, and the default.
2. **Instance-scoped fluent setter** in `mockserver/mockserver-core/src/main/java/org/mockserver/configuration/Configuration.java`, with `fileExists(...)` if the value is a path (see the existing patterns; this guard is part of the [TLS validation contract](tls-and-security.md)).
3. **Documentation** in `mockserver/mockserver.example.properties` — same section ordering as above.
4. **Tests** in `mockserver/mockserver-core/src/test/java/org/mockserver/configuration/ConfigurationTest.java` covering: env-var → property, system-property → property, fluent setter → property, default, and any validation guard.
5. **Consumer docs** at `jekyll-www.mock-server.com/mock_server/configuration_properties.html` — keep the user-facing description aligned with the inline comment.

See [docs/code/domain-model.md](domain-model.md) for the wider configuration architecture and [docs/code/memory-management.md](memory-management.md) for the memory-ring-buffer properties specifically (they need extra care because the wrong values can OOM the JVM).
