# Client API & Test Integrations

## MockServerClient

`MockServerClient` (`mockserver-client-java`) is the primary Java API for interacting with a running MockServer. All operations are performed via HTTP requests to the MockServer REST API.

### Communication Mechanism

```mermaid
sequenceDiagram
    participant T as Test Code
    participant C as MockServerClient
    participant NH as NettyHttpClient
    participant S as MockServer

    T->>C: when(request).respond(response)
    C->>C: Build Expectation JSON
    C->>NH: PUT /mockserver/expectation
    NH->>S: HTTP request
    S->>S: HttpState.handle()
    S-->>NH: 201 Created
    NH-->>C: HttpResponse
    C-->>T: ForwardChainExpectation
```

### Fluent API

```java
MockServerClient client = new MockServerClient("localhost", 1080);

// Create expectation
client.when(
    request().withMethod("GET").withPath("/api/users")
).respond(
    response().withStatusCode(200).withBody("{\"users\": []}")
);

// Verify
client.verify(
    request().withPath("/api/users"),
    VerificationTimes.exactly(1)
);

// Retrieve
HttpRequest[] requests = client.retrieveRecordedRequests(
    request().withPath("/api/users")
);
```

### Construction

`MockServerClient` exposes eight constructor overloads spanning `host`/`port`/`contextPath`,
`Configuration`/`ClientConfiguration`, and a `CompletableFuture<Integer>` port. These remain fully
supported and are not deprecated. In addition, `MockServerClient.builder()` returns a fluent `Builder`
that covers every construction dimension in one discoverable place, with sensible defaults
(`host` = `localhost`, `port` = `1080`, empty context path):

```java
MockServerClient client = MockServerClient.builder()
    .host("localhost")
    .port(1080)
    .contextPath("/mockserver")
    .secure(true)                       // withSecure(...)
    .proxyConfiguration(proxy)          // withProxyConfiguration(...)
    .controlPlaneJWT("token")           // withControlPlaneJWT(...)
    .requestOverride(request())         // withRequestOverride(...)
    .configuration(clientConfiguration())
    .build();
```

The builder introduces no new behaviour: `build()` selects the matching constructor and applies the
existing `with...` setters, so a builder-produced client is identical to a directly-constructed one.
Constructor validation is inherited (an empty `host` throws `IllegalArgumentException` from `build()`),
and `portFuture(...)` is mutually exclusive with `host`/`port`/`contextPath` (the future-based
constructor always targets `localhost` with an empty context path). The `Builder` is a nested class in
`mockserver/mockserver-client-java/src/main/java/org/mockserver/client/MockServerClient.java`.

### API Methods

#### Expectation Setup

| Method | Description |
|--------|-------------|
| `when(RequestDefinition)` | Create expectation with unlimited matches |
| `when(RequestDefinition, Times)` | Create expectation with limited matches |
| `when(RequestDefinition, Times, TimeToLive)` | Create with match limit and TTL |
| `when(RequestDefinition, Times, TimeToLive, Integer)` | Create with priority |
| `upsert(Expectation...)` | Create or update expectations (by ID) |
| `upsert(OpenAPIExpectation...)` | Create expectations from OpenAPI specs |

#### Verification

| Method | Description |
|--------|-------------|
| `verify(RequestDefinition, VerificationTimes)` | Verify request count |
| `verify(RequestDefinition...)` | Verify requests received in order |
| `verify(ExpectationId, VerificationTimes)` | Verify by expectation ID |
| `verify(ExpectationId...)` | Verify sequence by expectation IDs |
| `verifyZeroInteractions()` | Verify no requests received |

#### Retrieval

| Method | Return Type | Description |
|--------|-------------|-------------|
| `retrieveRecordedRequests(RequestDefinition)` | `HttpRequest[]` | Received requests |
| `retrieveRecordedRequests(RequestDefinition, Format)` | `String` | Received requests as JSON/Java |
| `retrieveRecordedRequestsAndResponses(RequestDefinition)` | `LogEventRequestAndResponse[]` | Request/response pairs |
| `retrieveRecordedExpectations(RequestDefinition)` | `Expectation[]` | Recorded proxy expectations |
| `retrieveActiveExpectations(RequestDefinition)` | `Expectation[]` | Currently active expectations |
| `retrieveActiveExpectations(RequestDefinition, Format)` | `String` | Active expectations as JSON/Java |
| `retrieveLogMessages(RequestDefinition)` | `String` | Log messages as text |
| `retrieveLogMessagesArray(RequestDefinition)` | `String[]` | Log messages as array |

#### Clear & Reset

| Method | Description |
|--------|-------------|
| `clear(RequestDefinition)` | Clear expectations and logs matching request |
| `clear(RequestDefinition, ClearType)` | Clear specific type (`EXPECTATIONS`, `LOG`, `ALL`) |
| `clear(ExpectationId)` | Clear by expectation ID |
| `clear(String)` | Clear by expectation ID string |
| `reset()` | Reset all state (expectations, logs, WebSocket registry) |

#### Lifecycle

| Method | Description |
|--------|-------------|
| `hasStarted()` | Whether server has started |
| `hasStopped()` | Whether server has stopped |
| `isRunning()` | (deprecated) Use `hasStarted()`/`hasStopped()` |
| `stop()` | Stop server synchronously |
| `stopAsync()` | Stop server asynchronously |
| `close()` | Alias for `stop()` |
| `bind(Integer...)` | Bind additional ports |
| `openUI()` | Launch dashboard UI in browser |

#### Configuration

| Method | Description |
|--------|-------------|
| `withSecure(boolean)` | Enable TLS for client communication |
| `withControlPlaneJWT(String)` | Set static JWT token |
| `withControlPlaneJWT(Supplier<String>)` | Set dynamic JWT supplier |
| `withRequestOverride(HttpRequest)` | Default headers for control-plane requests |
| `withProxyConfiguration(ProxyConfiguration)` | Route via proxy |

#### gRPC

| Method | Description |
|--------|-------------|
| `uploadGrpcDescriptor(byte[])` | Upload a compiled proto descriptor set to the server |
| `retrieveGrpcServices()` | List all loaded gRPC services and their methods |
| `clearGrpcDescriptors()` | Clear all loaded gRPC descriptors |

#### Contract Testing and Pact

| Method | Description |
|--------|-------------|
| `contractTest(String spec, String baseUrl)` | Exercise every operation in an OpenAPI spec against a live service at `baseUrl`; returns a `ContractReport`. Wraps `PUT /mockserver/contractTest`. |
| `contractTest(String spec, String baseUrl, String operationId)` | Same as above but restricted to a single `operationId`. |
| `trafficValidate(String spec)` | Validate recorded traffic against an OpenAPI spec; returns a `ContractReport`. Wraps `PUT /mockserver/trafficValidate`. |
| `pactImport(String pactJson)` | Import a Pact v3 consumer contract as expectations. Wraps `PUT /mockserver/pact/import`. |
| `pactExport(String consumer, String provider)` | Export active expectations as a Pact v3 contract. Wraps `PUT /mockserver/pact`. |
| `pactVerify(String pactJson)` | Verify a Pact v3 contract against active expectations. Wraps `PUT /mockserver/pact/verify`. |
| `verifySLO(SloCriteria criteria)` | Verify recorded samples against SLO criteria; returns the `SloVerdict` on PASS/INCONCLUSIVE, throws `SloVerdictAssertionError` (an `AssertionError` carrying the verdict) on FAIL. Wraps `PUT /mockserver/verifySLO`. |
| `startChaosExperiment(String experimentJson)` | Start a chaos experiment from its JSON definition; returns the started-status JSON. Wraps `PUT /mockserver/chaosExperiment`. |

`ContractReport` is a `record` on `MockServerClient` with fields: `int total`, `int passed` (count), `int failed`, `boolean allPassed`, `List<ContractResult> results`. Each `ContractResult` carries `operationId`, `method`, `path`, `matchedOperation`, `int statusCode`, `boolean passed`, `List<String> requestErrors`, `List<String> responseErrors`. These types and methods are defined in `mockserver/mockserver-client-java/src/main/java/org/mockserver/client/MockServerClient.java` (around line 3340).

### ForwardChainExpectation

Returned by `when()`, provides terminal methods to define the action:

| Category | Methods |
|----------|---------|
| Response | `respond(HttpResponse)`, `respond(HttpTemplate)`, `respond(HttpClassCallback)`, `respond(ExpectationResponseCallback)` |
| Forward | `forward(HttpForward)`, `forward(HttpTemplate)`, `forward(HttpClassCallback)`, `forward(ExpectationForwardCallback)`, `forward(HttpOverrideForwardedRequest)` |
| Error | `error(HttpError)` |
| gRPC | `respondWithGrpcStream(GrpcStreamResponse)` |
| Configuration | `withId(String)`, `withPriority(int)` |

### Authentication Support

| Method | Purpose |
|--------|---------|
| `withControlPlaneJWT(String)` | Static JWT token |
| `withControlPlaneJWT(Supplier<String>)` | Dynamic JWT supplier |
| `withSecure(boolean)` | Enable TLS for client-to-server |
| `withRequestOverride(HttpRequest)` | Default headers for all control-plane requests |

## ClientAndServer

`ClientAndServer` (`mockserver-netty`) combines an embedded `MockServer` with a `MockServerClient`, used by all test framework integrations:

```mermaid
classDiagram
    class MockServerClient {
        -host: String
        -port: int
        +when(RequestDefinition): ForwardChainExpectation
        +verify(RequestDefinition, VerificationTimes)
        +reset()
        +stop()
    }

    class ClientAndServer {
        -mockServer: MockServer
        +startClientAndServer(ports): ClientAndServer
        +isRunning(): boolean
        +registerListener(ExpectationsListener)
    }

    class MockServer {
        +createServerBootstrap()
        +getLocalPort(): int
    }

    MockServerClient <|-- ClientAndServer
    ClientAndServer o-- MockServer
```

```java
// Embedded usage
ClientAndServer server = ClientAndServer.startClientAndServer(1080);
server.when(request().withPath("/test")).respond(response().withBody("OK"));
// ... run tests ...
server.stop();
```

## Test Framework Integrations

### JUnit 4 Rule

```mermaid
sequenceDiagram
    participant JU as JUnit Runner
    participant MR as MockServerRule
    participant CAS as ClientAndServer
    participant T as Test Instance

    JU->>MR: apply(Statement, Description)
    MR->>MR: Check perTestSuite mode
    alt Per-test-suite (first run)
        MR->>CAS: ClientAndServer.startClientAndServer()
        MR->>MR: Store in static field
    else Per-test-suite (subsequent)
        MR->>CAS: reset()
    else Per-test-class
        MR->>CAS: ClientAndServer.startClientAndServer()
    end
    MR->>T: Inject into MockServerClient fields
    MR->>JU: Evaluate test
    alt Per-test-class
        MR->>CAS: stop()
    end
```

**Usage:**

```java
public class MyTest {
    @Rule
    public MockServerRule mockServerRule = new MockServerRule(this);

    private MockServerClient mockServerClient;  // Auto-injected

    @Test
    public void test() {
        mockServerClient.when(request()).respond(response().withBody("OK"));
    }
}
```

**Modes:**
- `new MockServerRule(this)` — auto-allocates port, per-test-class lifecycle
- `new MockServerRule(this, true)` — per-test-suite (static, shared across tests)
- `new MockServerRule(this, 1080)` — specific port, per-test-suite

### JUnit 5 Extension

```java
@MockServerSettings(ports = {1080})
class MyTest {
    @Test
    void test(MockServerClient client) {
        client.when(request()).respond(response().withBody("OK"));
    }
}
```

**`@MockServerSettings` attributes:**
- `perTestSuite` — boolean, default false. If true, single server per JVM.
- `ports` — int[], default empty (auto-allocate).
- `resetBeforeEach` — boolean, default false. When true, the extension calls `reset()` on the shared server in its `BeforeEachCallback`, clearing all expectations and recorded requests before each test method. This fires before any `@BeforeEach` methods, so expectations registered in `@BeforeEach` are applied after the reset and are present for the test. Combined with `perTestSuite = true`, this gives test isolation even across multiple test classes sharing one JVM-wide server. Without `resetBeforeEach`, the historic behaviour is preserved: the shared server is never reset between tests, leaving expectations registered in `@BeforeAll` intact for the whole class.

**Parameter resolution**: Injects `MockServerClient` (or `ClientAndServer`) as test method parameters.

**Lifecycle:**
- `beforeAll`: Creates `ClientAndServer`, optionally registers JVM shutdown hook
- `beforeEach`: Calls `reset()` when `resetBeforeEach = true`
- `afterAll`: Stops server (unless per-test-suite mode)

### Spring Test Integration

```mermaid
sequenceDiagram
    participant SB as Spring Boot Test
    participant CF as MockServerTestCustomizerFactory
    participant PC as MockServerPropertyCustomizer
    participant EL as MockServerTestExecutionListener
    participant T as Test Instance

    SB->>CF: createContextCustomizer()
    CF->>CF: Find @MockServerTest annotation
    CF->>PC: new MockServerPropertyCustomizer(properties)
    PC->>PC: Split properties into Spring vs mockserver.*
    PC->>PC: Build Configuration from mockserver.* properties
    PC->>PC: Create ClientAndServer with Configuration on free port
    PC->>SB: Add mockServerPort to Environment
    PC->>SB: Replace ${mockServerPort} in Spring property values

    SB->>EL: prepareTestInstance()
    EL->>EL: Find MockServerClient fields
    EL->>T: Inject client into fields

    SB->>T: Run test

    SB->>EL: afterTestMethod()
    EL->>EL: mockServerClient.reset()
```

**Usage:**

```java
@MockServerTest({
    "my.service.url=http://localhost:${mockServerPort}",
    "mockserver.initializationClass=com.example.MyInit",
    "mockserver.logLevel=WARN"
})
@SpringBootTest
class MyTest {
    private MockServerClient mockServerClient;  // Auto-injected

    @MockServerPort
    private int serverPort;  // Injected via @Value

    @Test
    void test() { ... }
}
```

**How it works:**
1. `MockServerTestCustomizerFactory` (loaded via `spring.factories`) scans for `@MockServerTest`
2. `MockServerPropertyCustomizer` splits annotation properties: `mockserver.*`-prefixed properties are applied to a per-instance `Configuration` object; other properties go to the Spring `Environment`
3. `MockServerPropertyCustomizer` creates a `ClientAndServer` with the `Configuration` on a free port and injects `mockServerPort` into the Spring `Environment`
4. `MockServerTestExecutionListener` injects the `ClientAndServer` into `MockServerClient` fields
5. After each test, `reset()` clears state

### Spring Boot Starter

The `mockserver-spring-boot-starter` module provides **Spring Boot auto-configuration** for main-application
(non-test-runner) use: adding it to the classpath and setting `mockserver.enabled=true` starts a
`ClientAndServer` inside the application context and exposes it as an injectable `MockServerClient` bean. It is
intended for **local development and integration testing** (it starts a real embedded MockServer), and is
disabled by default so its mere presence never changes behaviour.

It uses the Spring Boot 3/4 auto-configuration mechanism (an
`org.springframework.boot.autoconfigure.AutoConfiguration.imports` file under `META-INF/spring/`, not the legacy
`spring.factories`), targeting Spring Boot 4.0.x / Spring Framework 7 (the version already used elsewhere in the
build) on the `jakarta` namespace.

```mermaid
sequenceDiagram
    participant CTX as Spring Context Refresh
    participant AC as MockServerAutoConfiguration
    participant P as MockServerProperties
    participant CS as ClientAndServer

    CTX->>AC: @ConditionalOnProperty mockserver.enabled=true
    AC->>P: bind mockserver.* properties
    AC->>CS: startClientAndServer(configuration, port)
    CS-->>AC: running server (also a MockServerClient)
    AC-->>CTX: register ClientAndServer bean
    Note over CTX,CS: on context close, bean destroyMethod=stop shuts the server down
```

**Configuration properties** (`mockserver.*`):

| Property | Default | Purpose |
|----------|---------|---------|
| `mockserver.enabled` | `false` | Master switch — start MockServer and register the bean |
| `mockserver.port` | `0` | Bind port; `0` picks a free ephemeral port (read the actual port from the bean) |
| `mockserver.initialization-json` | — | Path to an initialization JSON file of expectations to preload |
| `mockserver.log-level` | — | MockServer log level (e.g. `WARN`, `INFO`, `DEBUG`, `OFF`) |

**Usage:**

```java
@SpringBootApplication
public class MyDevApp { }

// application.properties (dev / test profile only):
// mockserver.enabled=true
// mockserver.port=1080

@Service
class MyClient {
    MyClient(MockServerClient mockServerClient) { // injected; also injectable as ClientAndServer
        mockServerClient
            .when(request().withPath("/hello"))
            .respond(response().withBody("world"));
    }
}
```

**How it works:**
1. `MockServerAutoConfiguration` is listed in `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports` and is gated by `@ConditionalOnProperty(prefix = "mockserver", name = "enabled", havingValue = "true")`
2. `MockServerProperties` (`@ConfigurationProperties(prefix = "mockserver")`) binds the `mockserver.*` values
3. A single `ClientAndServer` bean is created (`@ConditionalOnMissingBean`, so you can override it) with `destroyMethod = "stop"`; because `ClientAndServer extends MockServerClient` the one bean satisfies both `ClientAndServer` and `MockServerClient` injection points
4. The server starts on context refresh and stops on context close

### Maven Plugin Glob Support

The Maven plugin's `initializationJson` parameter now supports glob patterns for loading multiple expectation files. The plugin uses `FilePath.expandFilePathGlobs()` to resolve glob characters (`*`, `**`, `?`, `[]`, `{}`) into matching file paths.

Example Maven configuration:
```xml
<initializationJson>src/test/resources/expectations/**/*.json</initializationJson>
```

When a glob pattern is detected (by checking for glob characters in the path), the plugin expands it and concatenates the contents of all matching files into a single JSON array, which is then passed to `initializationJsonPath`. Non-glob paths are passed through unchanged.

## Testcontainers

The `mockserver-testcontainers` module (`org.mock-server:mockserver-testcontainers`) is the **canonical, MockServer-maintained** Testcontainers integration. It supersedes the thin upstream `org.testcontainers:mockserver` module (which exposes only a single TCP port, pins an ancient default image, and offers no configuration helpers).

`MockServerContainer` extends Testcontainers' `GenericContainer` and adds:

| Capability | Method | Mechanism |
|------------|--------|-----------|
| Version-lockstep image | (default constructor) | Image tag derived from `MockServerClient`'s `Implementation-Version` (`mockserver/mockserver:mockserver-<version>`), falling back to `:latest` when run from unpackaged classes |
| Server port | `withServerPort(int)` | Sets `SERVER_PORT`, *replaces* the exposed port (not append), and re-targets the readiness wait at the chosen port |
| Readiness | (constructor) | Waits on `Wait.forHttp("/mockserver/status").withMethod("PUT").forStatusCode(200)` so `start()` returns only once the request pipeline is serving — **not** a listening-port wait, which is satisfied at port-bind while Netty still accepts-then-resets during init (a ~0.2–0.3s race that reset the first request under load) |
| Direct client wiring | `getClient()` | `new MockServerClient(getHost(), getServerPort())` |
| Endpoints | `getEndpoint()` / `getSecureEndpoint()` | HTTP and HTTPS are served on the same unified port |
| DNS | `withDnsPort(int)` | `MOCKSERVER_DNS_ENABLED` + `MOCKSERVER_DNS_PORT`, UDP port exposed |
| Transparent proxy | `withTransparentProxy()` | `MOCKSERVER_TRANSPARENT_PROXY_ENABLED` + `NET_ADMIN` capability (iptables/redirect rules remain the operator's responsibility) |
| HTTP/3 (experimental) | `withHttp3(int)` | `MOCKSERVER_HTTP3_PORT`, UDP port exposed |
| Initialization JSON | `withInitializationJson(String)` | Copies an initialization JSON into the container and sets `MOCKSERVER_INITIALIZATION_JSON_PATH` (startup loading, distinct from `persistExpectations`) |
| Broker networking | `withNetwork(Network)` / `withNetworkAliases(String)` (inherited from `GenericContainer`) | For AsyncAPI broker connectivity / inter-container comms |
| Arbitrary properties | `withProperty(k,v)` / `withProperties(Map)` | Passed through as MockServer env vars |
| Log level | `withLogLevel(String)` | `MOCKSERVER_LOG_LEVEL` |

The builder helpers configure env vars, exposed ports, and Linux capabilities **before** the container starts, so they are introspectable in unit tests without a running Docker daemon (`MockServerContainerConfigTest`).

The Docker-dependent `MockServerContainerIntegrationTest` is gated on `DockerAvailability.isAvailable(() -> DockerClientFactory.instance().isDockerAvailable())`. Gate on that wrapper (from `mockserver-testing`) rather than calling `DockerClientFactory.instance().isDockerAvailable()` directly: the raw probe converts only `IllegalStateException` into `false` and **throws** for every other failure — Ryuk rejection on a user-namespace-remapped daemon, an unpullable Ryuk image, or an `Error` from an incomplete test classpath — which turns "no usable Docker" into a hard ERROR instead of a skip. See `org.mockserver.test.DockerAvailability`.

> **`MockServerContainerIntegrationTest` executes in CI via a dedicated socket-bearing step.** Surefire is configured for `**/*Test.java` and Failsafe for `**/*IntegrationTest.java`; a class ending in `IT` matches neither, and `mockserver-testcontainers` declares no override. The suite was historically named `MockServerContainerIT` and so matched nothing — it compiled but ran nowhere, locally or in CI. It was renamed to `MockServerContainerIntegrationTest` so Failsafe collects it. Because it is Docker-gated and the main `:maven:` build runs without a Docker socket (where it would silently SKIP), it runs in its own step, `.buildkite/scripts/steps/java-testcontainers-test.sh`, which supplies the socket and then asserts the suite actually executed via `assert-suite-ran.sh` — the same fail-closed pattern as the async live-broker and cloud blob-store Docker-gated suites.

Typical usage:
```java
try (MockServerContainer mockServer = new MockServerContainer()) {
    mockServer.start();
    MockServerClient client = mockServer.getClient();
    client.when(request().withPath("/hello")).respond(response().withBody("world"));
    // ... point the system under test at mockServer.getEndpoint() ...
}
```

## On-Demand Binary Launcher (turnkey, no Java/Docker)

Each language client can **start a real MockServer on demand** by downloading the self-contained,
JVM-less binary bundle for the user's OS/arch — no Java install, no Docker. A user adds only the
client; the server is fetched, cached, launched, and stopped programmatically. This mirrors the
esbuild / Playwright "managed binary" model. `mockserver-node/downloadBinary.js` is the reference
implementation; every other client mirrors the same contract.

**Shared contract (identical across all clients):**

| Aspect | Value |
|--------|-------|
| Bundle | `mockserver-<version>-<os>-<arch>.<ext>` — os ∈ {linux, darwin, windows}, arch ∈ {x86_64, aarch64}, ext = tar.gz (linux/darwin) / zip (windows) |
| Source | `${MOCKSERVER_BINARY_BASE_URL:-https://github.com/mock-server/mockserver-monorepo/releases/download/mockserver-<version>}/<file>` |
| Cache | `${MOCKSERVER_BINARY_CACHE:-<OS user cache>}/mockserver/binaries/<version>/<bundle>/bin/mockserver` (`mockserver.bat` on Windows); OS cache = `%LOCALAPPDATA%` (Windows) else `$XDG_CACHE_HOME` or `~/.cache` |
| Integrity | downloads to a `.part` temp, verifies the published `<file>.sha256` (**fail-closed** on missing/empty/mismatch), atomic rename, then `tar -xf` extract |
| Versioned cache GC | after a successful install, prunes other version dirs keeping the current + **one** previous (`maxPrevious = 1`); semver-aware so a **release outranks its `-SNAPSHOT`** (a stable release is never pruned in favour of a pre-release) |
| Env knobs | `MOCKSERVER_BINARY_BASE_URL` (mirror / air-gap), `MOCKSERVER_BINARY_CACHE`, `MOCKSERVER_SKIP_BINARY_DOWNLOAD` (fail instead of download — pre-seeded caches) |
| Default version | the last released MockServer version (`7.6.0`) — see *Version pinning* below |

**Version pinning.** All clients must resolve to the same default version so they fetch the same
bundle and share one cache, but there is no single shared constant: each client records the version
in its own idiomatic place, and the release bump has to touch every one of them.

| Client | Where the version lives | Kind |
|--------|------------------------|------|
| Node | `mockserver-node/package.json` → `version` (passed in by `bin/mockserver.js`; `ensureBinary` has no default) | package manifest |
| Python | `mockserver-client-python/mockserver/launcher.py` → `_CLIENT_VERSION` | source constant |
| PHP | `mockserver-client-php/src/BinaryLauncher.php` → `DEFAULT_VERSION` | source constant |
| Ruby | `mockserver-client-ruby/lib/mockserver/version.rb` → `MockServer::VERSION` (default for the `version:` kwarg) | source constant |
| Go | `mockserver-client-go/VERSION`, embedded via `//go:embed` | build artefact |
| Rust | `Cargo.toml` → `version`, read via `env!("CARGO_PKG_VERSION")` | package manifest |
| .NET | assembly `AssemblyInformationalVersionAttribute` (from the csproj `<Version>`), with `FallbackVersion` in `MockServerBinaryLauncher.cs` if reflection fails | assembly metadata |

Two guards keep these in step, because they have drifted before — Python and PHP sat at `7.1.0` and
Rust at `7.3.0` across three releases, so those clients silently downloaded a stale server:

- `scripts/release/prepare.sh` bumps every path above and **hard-fails** if any pattern does not
  match exactly once, so a renamed constant breaks the release rather than being skipped.
- `.buildkite/scripts/steps/clients-version-consistency.sh` (infra pipeline) asserts on every build
  that all pins equal the last released version, catching drift between releases.

When adding a client, add its version location to **both**.

**Per-client location & API:**

| Client | File(s) | Entry point |
|--------|---------|-------------|
| Node | `mockserver-node/downloadBinary.js` | `ensureBinary` / `runBinary` (reference) |
| Python | `mockserver-client-python/mockserver/launcher.py` | `start(port)` → handle, `stop()` |
| Ruby | `mockserver-client-ruby/lib/mockserver/binary_launcher.rb` | `BinaryLauncher` start/stop |
| Go | `mockserver-client-go/launcher.go` | `StartServer` / handle `Stop` |
| .NET | `mockserver-client-dotnet/.../MockServerBinaryLauncher.cs` | `Start` / `Stop` |
| Rust | `mockserver-client-rust/src/launcher.rs` | `start` / handle `stop` |
| PHP | `mockserver-client-php/src/BinaryLauncher.php` (+ `BinaryHandle.php`) | `BinaryLauncher::start` / `BinaryHandle::stop` |

Hardening common to all: version strings are validated and resolved cache paths are asserted to stay
within the cache root (no path traversal); SHA-256 verification is mandatory on the public path; the
Windows `.bat` launcher is spawned with safe quoting; child process stdout/stderr are drained to avoid
pipe-buffer deadlock; HTTP downloads use timeouts and stream to disk. Tests are hermetic (no live
network) using `file://` fixtures and a stubbed downloader, plus one integration test that runs only
when a real bundle is available. *(Known minor follow-up: the PHP pruner relies on `version_compare`,
which can treat `7.6.0` and `7.6.0-SNAPSHOT` as equal — prune order between those two is not
guaranteed; tracked as a follow-up.)*

### Test-Runner Fixtures & Helpers

Thin, framework-native on-ramps wrap the launchers above so users get a ready client with one line of
setup. All follow the same shape: start (or connect to) a server, hand back a reset client, tear down after.

| Client | Framework | Entry point | File |
|--------|-----------|-------------|------|
| Ruby | RSpec | `require 'mockserver/rspec'` → `mockserver` (shared context, `:mockserver` tag) | `mockserver-client-ruby/lib/mockserver/rspec.rb` |
| Python | pytest | `mockserver` fixture (auto-registered via the `pytest11` entry point) + `mockserver_server`/`mockserver_host`/`mockserver_port` | `mockserver-client-python/mockserver/pytest_plugin.py` |
| Node | jest / vitest / mocha / node:test | `setupMockServer(options)` → `{ client, host, serverPort, stop }` | `mockserver-client-node/setupMockServer.js` |

* **pytest** — the `mockserver` fixture connects to an external server when `MOCKSERVER_HOST`/`MOCKSERVER_PORT`
  are set, otherwise launches a self-contained binary (via `launcher.start`) for the session; it resets the
  server before and after every test. Registration via `[project.entry-points.pytest11]` in `pyproject.toml`
  means `pip install`ed users get the fixtures automatically, opt-in by requesting them.
* **Node** — `setupMockServer()` lazily requires `mockserver-node` (kept a devDependency, so importing the
  client never pulls it in) to start/stop the server, and returns a `mockServerClient` plus an idempotent
  `stop()`. Framework-agnostic: usable from `beforeAll`/`afterAll`, a vitest/jest `globalSetup` module, or a
  plain script.

## Node.js Client

The Node.js client (`mockserver-client-node/`) mirrors the Java client's API surface in JavaScript/TypeScript. The primary factory is `mockServerClient(host, port, contextPath?, tls?, caCertPemFilePath?, options?)`, which returns a `MockServerClient` object.

### Fluent `when()` DSL

The client exposes a `when(requestMatcher, times?, timeToLive?, priority?)` method that returns a `ForwardChainExpectation` — a fluent builder mirroring Java's `ForwardChainExpectation`. The optional builder methods refine the expectation before a terminal action sends it to MockServer:

```javascript
const client = mockServerClient('localhost', 1080);

// Fluent style — plain number is treated as remainingTimes
await client
  .when({ path: '/api/users' }, 3)
  .withPriority(10)
  .respond({ statusCode: 200, body: '{"users":[]}' });

// Procedural style (unchanged)
await client.mockSimpleResponse('/api/health', 'OK', 200);
```

**Argument-order caveat:** `when(matcher, times, timeToLive, priority)` takes `times` before `timeToLive` before `priority` — the opposite order from `mockWithCallback` and the other procedural helpers, which take `(matcher, handler, times, priority, timeToLive, id)`. Prefer the fluent builder methods (`.withTimes()`, `.withTimeToLive()`, `.withPriority()`) over positional arguments to avoid confusion.

| Builder method | Effect |
|----------------|--------|
| `.withTimes(times)` | Accepts a `Times` object or a plain number (treated as `remainingTimes`) |
| `.withTimeToLive(ttl)` | Accepts a `TimeToLive` object |
| `.withPriority(n)` | Higher number wins on ties |
| `.withId(id)` | Set a stable expectation ID (enables upsert) |

Terminal action methods:

| Method | Action |
|--------|--------|
| `.respond(response)` | Static HTTP response, template, or class callback |
| `.forward(forward)` | HTTP forward, template, class callback, or override |
| `.error(error)` | HTTP error / connection drop |
| `.callback(fn)` | Local JS callback over the callback WebSocket |
| `.forwardCallback(fn)` | Local JS forward callback over the callback WebSocket |

### TypeScript Types

Full TypeScript definitions are in `mockServerClient.d.ts` and `mockServer.d.ts`. The `ForwardChainExpectation` interface and all request/response types are exported. The client ships type definitions as part of the package; no separate `@types/mockserver` package is needed.

## WebSocket Callback System

For object/closure callbacks, a WebSocket connection between the client JVM and MockServer enables the callback to execute on the client side:

```mermaid
graph TB
    subgraph "Client JVM"
        TEST[Test Code]
        FCE[ForwardChainExpectation]
        LCR[LocalCallbackRegistry]
        WSC[WebSocketClient]
    end

    subgraph "MockServer"
        CWSH[CallbackWebSocketServerHandler]
        WSCR[WebSocketClientRegistry]
        AH[HttpActionHandler]
    end

    TEST -->|respond(callback)| FCE
    FCE -->|store| LCR
    FCE -->|connect| WSC
    WSC <-->|WebSocket| CWSH
    CWSH -->|register| WSCR

    AH -->|RESPONSE_OBJECT_CALLBACK| WSCR
    WSCR -->|send request| CWSH
    CWSH -->|forward to client| WSC
    WSC -->|invoke callback| LCR
    LCR -->|return response| WSC
    WSC -->|send response| CWSH
    CWSH -->|dispatch| WSCR
    WSCR -->|return to| AH
```

### Registration Flow

1. `ForwardChainExpectation.respond(callback)` generates a UUID `clientId`
2. Callback stored in `LocalCallbackRegistry` keyed by `clientId`
3. `WebSocketClient` connects to `/_mockserver_callback_websocket`
4. Server's `CallbackWebSocketServerHandler` performs WebSocket handshake
5. Server's `WebSocketClientRegistry.registerClient(clientId, channel)` stores the mapping
6. Expectation created with `HttpObjectCallback(clientId)`

### Invocation Flow

1. Request arrives, matches expectation with `RESPONSE_OBJECT_CALLBACK`
2. `HttpResponseObjectCallbackActionHandler` calls `WebSocketClientRegistry.sendClientMessage(clientId, request)`
3. Server sends `HttpRequest` JSON to client via WebSocket
4. Client's `WebSocketClient.receivedTextWebSocketFrame()` deserializes request
5. Client looks up callback by `clientId`, invokes `callback.handle(request)`
6. Client sends `HttpResponse` JSON back via WebSocket
7. Server's `WebSocketClientRegistry.receivedTextWebSocketFrame()` dispatches response
8. Original request handler receives response and writes it to the client channel

### Cleanup

`MockServerEventBus` (per-port) publishes `STOP` and `RESET` events. `ForwardChainExpectation` subscribes to these events to close WebSocket connections and unregister callbacks.

## Callback Interfaces

| Interface | Method | Used By |
|-----------|--------|---------|
| `ExpectationResponseCallback` | `handle(HttpRequest): HttpResponse` | `respond(callback)` |
| `ExpectationForwardCallback` | `handle(HttpRequest): HttpRequest` | `forward(callback)` |
| `ExpectationForwardAndResponseCallback` | `handle(HttpRequest, HttpResponse): HttpResponse` | `forward(fwdCallback, respCallback)` |

## Class Reference

| Class | Module | Role |
|-------|--------|------|
| `MockServerClient` | client-java | Java client API (1621 lines) |
| `ForwardChainExpectation` | client-java | Fluent API action builder |
| `MockServerEventBus` | client-java | Internal pub/sub for stop/reset events |
| `ClientAndServer` | netty | Combined embedded server + client |
| `MockServerRule` | junit-rule | JUnit 4 `TestRule` |
| `MockServerExtension` | junit-jupiter | JUnit 5 `Extension` |
| `MockServerSettings` | junit-jupiter | Configuration annotation |
| `MockServerTest` | spring-test-listener | Spring test annotation |
| `MockServerPropertyCustomizer` | spring-test-listener | Spring context customizer |
| `MockServerTestExecutionListener` | spring-test-listener | Spring test lifecycle |
| `MockServerPort` | spring-test-listener | Port injection annotation |
| `MockServerAutoConfiguration` | spring-boot-starter | Spring Boot auto-configuration (main app) |
| `MockServerProperties` | spring-boot-starter | `mockserver.*` `@ConfigurationProperties` |
| `WebSocketClient` | core | Client-side WebSocket connector |
| `WebSocketClientHandler` | core | Client-side WebSocket handshake |
| `WebSocketClientRegistry` | core | Server-side client registry |
| `CallbackWebSocketServerHandler` | netty | Server-side WebSocket handler |
| `LocalCallbackRegistry` | core | In-JVM callback storage |
| `MockServerServlet` | war | Servlet bridge for WAR deployment |
| `ProxyServlet` | proxy-war | Proxy servlet for WAR deployment |

## IDE Extensions

MockServer ships two IDE extensions that let developers start, stop, and inspect MockServer without leaving their editor. Both are published as part of the standard MockServer release and versioned in lockstep with the server.

### VS Code Extension

**Source:** `mockserver-vscode/`
**Publisher ID:** `mockserver` (extension ID `mockserver.mockserver`)
**Registries:** VS Code Marketplace and Open VSX Registry

The extension is built with TypeScript and bundled via `vsce`. It contributes three commands and a set of JSON snippets.

| Feature | Detail |
|---------|--------|
| Commands | `mockserver.start` (Start Docker), `mockserver.stop`, `mockserver.openDashboard` |
| Snippets | `mockserver-expectation`, `mockserver-forward`, `mockserver-verify` — expand in any `.json` file |
| VS Code minimum | 1.80 |
| Runtime dependency | Docker Desktop (pulls `mockserver/mockserver:<version>` on demand) |

**Release publishing** (`scripts/release/components/vscode.sh`): bumps the version in `package.json`, builds and packages a `.vsix` inside a pinned Node.js Docker container (no host toolchain required), then publishes to VS Code Marketplace via `vsce publish` and to Open VSX via `ovsx publish`. Secrets are loaded from `mockserver-release/vsce` and `mockserver-release/ovsx` in AWS Secrets Manager.

### JetBrains / IntelliJ Plugin

**Source:** `mockserver-jetbrains/`
**Plugin ID:** `com.mock-server.mockserver`
**Registry:** JetBrains Marketplace

The plugin is written in Kotlin and built with the IntelliJ Platform Gradle Plugin 2.x. It targets IntelliJ Platform 2023.3+ (build 233) and is compatible through 2025.3.x (build 253).

| Feature | Detail |
|---------|--------|
| Actions | `MockServer.OpenDashboard`, `MockServer.StartDocker` — added to the **Tools > MockServer** menu group |
| Tool window | Bottom-panel **MockServer** window with buttons for the same two actions |
| Notifications | Balloon notification group `MockServer Notifications` |
| Minimum platform | 2023.3 (build `sinceBuild=243`) |
| Language | Kotlin 2.1.x, JVM toolchain 17 |

**Plugin Verifier (`verifyPlugin`).** The `./gradlew verifyPlugin` gate (IntelliJ Plugin Verifier) is stricter than the JetBrains Marketplace upload check and must pass before publishing. The `untilBuild` target in `gradle.properties` drifts as new IDE EAP builds are released, making this a recurring breakage risk — bump `untilBuild` whenever verifyPlugin fails on a new EAP range. Two stable patterns avoid common verifyPlugin failures:

- **Plugin version**: derive the version at runtime via `(MockServerSettings::class.java.classLoader as? PluginAwareClassLoader)?.pluginDescriptor?.version` (`MockServerSettings.kt`) rather than a hardcoded build constant. `PluginAwareClassLoader` is a public, stable IntelliJ API that exposes the plugin descriptor.
- **Action dispatch**: invoke actions programmatically via `AnActionEvent.createEvent(action, dataContext, place, ...)` (`MockServerToolWindowFactory.kt`) rather than constructing an `AnActionEvent` directly. The factory method is the stable API surface the verifier checks.

**Release publishing** (`scripts/release/components/jetbrains.sh`): bumps `pluginVersion` in `gradle.properties`, builds the plugin ZIP (`./gradlew clean buildPlugin`) inside the pinned Maven Docker image (which ships JDK 17; Gradle is downloaded by the wrapper), then publishes via `./gradlew publishPlugin` with the `JETBRAINS_TOKEN` environment variable set from `mockserver-release/jetbrains` in AWS Secrets Manager.

## MCP (Model Context Protocol) Integration

MockServer exposes its control-plane capabilities via the [Model Context Protocol](https://modelcontextprotocol.io/) (MCP), enabling AI agents and LLM-based tools to interact with MockServer programmatically.

### Protocol Details

| Property | Value |
|----------|-------|
| Transport | Streamable HTTP (POST for JSON-RPC, DELETE for session termination) |
| MCP version | `2025-03-26` |
| Endpoint path | `/mockserver/mcp` |
| Session management | Sessions created by `initialize` JSON-RPC request; tracked via `Mcp-Session-Id` header |
| Enable/disable | `mcpEnabled` configuration property (default: `true`) |

### Available Tools

MCP tools map to MockServer control-plane operations:

| Tool | Description |
|------|-------------|
| `create_expectation` | Create a mock expectation (high-level, simplified parameters) |
| `create_forward_expectation` | Create a forwarding proxy expectation |
| `create_expectation_from_openapi` | Create expectations from an OpenAPI/Swagger specification |
| `verify_request` | Verify that requests matching criteria were received a specific number of times |
| `verify_request_sequence` | Verify that requests were received in a specific order |
| `retrieve_recorded_requests` | Retrieve requests that MockServer has received |
| `retrieve_request_responses` | Retrieve request/response pairs |
| `clear_expectations` | Clear expectations and/or logs matching a request |
| `reset` | Reset all MockServer state |
| `get_status` | Check MockServer running status and bound ports |
| `debug_request_mismatch` | Diagnose why a request did not match any expectation |
| `stop_server` | Stop the MockServer instance |
| `raw_expectation` | Full expectation JSON passthrough |
| `raw_retrieve` | Full retrieve parameters passthrough |
| `raw_verify` | Full verification JSON passthrough |

### Available Resources

MCP resources provide read-only access to MockServer state:

| Resource URI | Description |
|--------------|-------------|
| `mockserver://expectations` | All currently active expectations |
| `mockserver://requests` | All recorded requests |
| `mockserver://logs` | Current MockServer log messages |
| `mockserver://configuration` | Current MockServer configuration properties |

### Authentication

The MCP endpoint enforces the same control-plane authentication (mTLS and/or JWT) as the REST API. See [TLS & Security — MCP Endpoint Authentication](tls-and-security.md#mcp-endpoint-authentication).

### Key Classes

| Class | Module | Role |
|-------|--------|------|
| `McpStreamableHttpHandler` | netty | Netty channel handler; intercepts `/mockserver/mcp` requests, handles JSON-RPC 2.0, auth, session management |
| `McpToolRegistry` | netty | Defines and implements 15 MCP tools (high-level and low-level) by delegating to `HttpState` |
| `McpResourceRegistry` | netty | Implements 4 MCP resource reads by querying `HttpState` |
| `McpSessionManager` | netty | Singleton session store with LRU eviction, TTL, and executor lifecycle |
| `McpSession` | netty | Session state: ID, initialization flag, last-accessed timestamp |
| `JsonRpcMessage` | netty | JSON-RPC 2.0 request/response/error/notification types |
