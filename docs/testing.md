# Testing

## Overview

MockServer uses a multi-layered testing strategy spanning unit tests, integration tests, packaging verification tests, container integration tests, and performance tests. Tests are predominantly written in **JUnit 4** with a small number of **JUnit 5** tests in the `mockserver-junit-jupiter` module.

```mermaid
graph TB
    subgraph "Unit Tests (Surefire)"
        UT["*Test.java — ~251 files"]
    end
    subgraph "Integration Tests (Failsafe)"
        IT["*IntegrationTest.java — ~184 files"]
    end
    subgraph "Packaging Integration Tests"
        MI["Maven Invoker — 3 sub-builds"]
        GI["Gradle — 2 sub-builds"]
    end
    subgraph "Container Integration Tests"
        DC["Docker Compose — 16 tests"]
        HT["Helm/k3d — 8 tests"]
    end
    subgraph "Performance Tests"
        PT["k6"]
    end
    UT --> IT --> MI & GI --> DC & HT --> PT
```

## Test Frameworks and Dependencies

| Framework | Version | Usage |
|-----------|---------|-------|
| JUnit 4 | 4.13.2 | Primary test framework across almost all modules |
| JUnit Jupiter (JUnit 5) | 5.14.4 | Used exclusively in `mockserver-junit-jupiter` |
| Mockito Core | 5.23.0 | Mocking framework used in most modules |
| Mockito JUnit Jupiter | 5.23.0 | JUnit 5 Mockito integration (`mockserver-junit-jupiter` only) |
| Hamcrest | 3.0 | Assertion matchers used across all modules |
| JSONAssert | 1.5.3 | JSON assertion library (`mockserver-core`) |
| Spring Test | 5.3.39 | `mockserver-core`, `mockserver-war`, `mockserver-proxy-war`, `mockserver-spring-test-listener`, `mockserver-examples` |
| XMLUnit | 2.11.0 | XML comparison (production dependency, also supports test assertions) |

No TestNG is used anywhere in the project.

## Test Inventory by Module

| Module | Unit Tests | Integration Tests | Total Test Files | Description |
|--------|-----------|-------------------|-----------------|-------------|
| `mockserver-core` | ~220 | ~62 | ~282 | Largest suite: matchers, serialization, validators, actions, collections, auth, logging |
| `mockserver-netty` | ~13 | ~64 | ~77 | Server lifecycle, TLS, proxy, CORS, authenticated control plane |
| `mockserver-examples` | 0 | ~25 | ~25 | End-to-end examples with various HTTP clients (sample code, built + tested standalone — not a reactor module) |
| `mockserver-junit-jupiter` | ~6 | ~12 | ~18 | JUnit 5 extension tests (injection, parallel safety, settings) |
| `mockserver-junit-rule` | ~4 | ~4 | ~8 | JUnit 4 Rule/ClassRule tests |
| `mockserver-war` | 0 | ~8 | ~8 | WAR deployment integration tests (embedded Tomcat) |
| `mockserver-client-java` | ~5 | ~2 | ~7 | Client API unit tests, event bus, serialization |
| `mockserver-spring-test-listener` | ~2 | ~4 | ~6 | Spring TestExecutionListener tests |
| `mockserver-proxy-war` | 0 | ~3 | ~3 | Proxy WAR integration tests (embedded Tomcat) |
| `mockserver-testing` | 1 | 0 | 1 | Self-test for the `Assert` utility class |
| `mockserver-integration-testing` | 0 | 0 | 0 | No tests of its own — it IS the shared test infrastructure |
| **Total** | **~251** | **~184** | **~435** | |

## Test Architecture

### Naming Convention

Test filtering is done entirely by naming convention — no `@Category` or `@Tag` annotations are used:

| Pattern | Runner | Maven Phase | Plugin |
|---------|--------|-------------|--------|
| `**/*Test.java` | Surefire | `test` | `maven-surefire-plugin` 3.5.6 |
| `**/*IntegrationTest.java` | Failsafe | `integration-test` / `verify` | `maven-failsafe-plugin` 3.5.6 |

### Abstract Base Class Hierarchy

The project uses a **template method pattern** for integration tests. Abstract base classes in `mockserver-integration-testing` define hundreds of test cases, and concrete classes in each module provide the server wiring.

```mermaid
classDiagram
    class AbstractMockingIntegrationTestBase {
        <<abstract>>
        #MockServerClient mockServerClient
        #EchoServer insecureEchoServer
        #EchoServer secureEchoServer
        #NettyHttpClient httpClient
        +beforeClass()
        +afterClass()
    }

    class AbstractBasicMockingIntegrationTest {
        <<abstract>>
        +shouldReturnResponseByMatchingPath()
        +shouldReturnResponseByMatchingMethod()
        ...~50 test methods
    }

    class AbstractExtendedMockingIntegrationTest {
        <<abstract>>
        +shouldForwardRequestInHTTPS()
        +shouldCallbackToSpecifiedClass()
        ...~123 test methods
    }

    class AbstractExtendedSameJVMMockingIntegrationTest {
        <<abstract>>
        +shouldCallbackToSpecifiedObject()
        ...same-JVM callback tests
    }

    class AbstractProxyIntegrationTest {
        <<abstract>>
        +shouldForwardRequestsUsingSocketDirectly()
        ...proxy test methods
    }

    AbstractMockingIntegrationTestBase <|-- AbstractBasicMockingIntegrationTest
    AbstractBasicMockingIntegrationTest <|-- AbstractBasicMockingSameJVMIntegrationTest
    AbstractBasicMockingSameJVMIntegrationTest <|-- AbstractExtendedMockingIntegrationTest
    AbstractExtendedMockingIntegrationTest <|-- AbstractExtendedSameJVMMockingIntegrationTest
    AbstractMockingIntegrationTestBase <|-- AbstractProxyIntegrationTest

    class AbstractBasicMockingSameJVMIntegrationTest {
        <<abstract>>
        Basic tests for same-JVM scenarios
    }

    class ClientAndServerMockingIntegrationTest {
        +startServer()
        Concrete: starts Netty server
    }
    class AbstractExtendedDeployableWARMockingIntegrationTest {
        <<abstract>>
        Intermediate: WAR deployment setup
    }
    class ExtendedWARMockingIntegrationTest {
        +startServer()
        Concrete: deploys to embedded Tomcat
    }
    class JUnitClassRuleIntegrationTest {
        +startServer()
        Concrete: uses JUnit 4 ClassRule
    }

    AbstractBasicMockingSameJVMIntegrationTest <|-- ClientAndServerMockingIntegrationTest
    AbstractExtendedSameJVMMockingIntegrationTest <|-- AbstractExtendedDeployableWARMockingIntegrationTest
    AbstractExtendedDeployableWARMockingIntegrationTest <|-- ExtendedWARMockingIntegrationTest
    AbstractBasicMockingSameJVMIntegrationTest <|-- JUnitClassRuleIntegrationTest
```

This architecture means a single abstract test class change can affect tests across multiple modules. The `mockserver-integration-testing` module has no tests of its own — it exists solely to provide these shared base classes.

### Assertion Patterns

Many tests use **private helper methods** or **abstract base classes** for assertions. Assertions are present but not always visible in the `@Test` method body. For example:

- `AuthenticatedControlPlaneUsing*IntegrationTest` classes delegate assertions to `clientOperationIsAuthenticated()` (uses `assertThrows` + `assertThat`) and `httpAPIOperationIsAuthenticated()` (asserts HTTP 401 + error message)
- `ConcurrencyBasicResponseMockingIntegrationTest` runs assertions inside scheduled tasks via `sendRequestAndVerifyResponse()` which calls `Assert.assertEquals`
- `NettyHttpProxySOCKSIntegrationTest` delegates to helpers like `proxyRequestsUsingSocketViaSOCKS5()` containing multiple assertions

### Precanned Callbacks

The `mockserver-integration-testing` module provides precanned callback implementations in `org.mockserver.testing.integration.callback`:

| Class | Purpose |
|-------|---------|
| `PrecannedTestExpectationForwardCallbackRequest` | Forward callback with request override |
| `PrecannedTestExpectationForwardCallbackRequestAndResponse` | Forward callback with request and response override |
| `PrecannedTestExpectationResponseCallback` | Response callback returning precanned response |

## Unit Tests

Unit tests use JUnit 4 (or JUnit 5 in `mockserver-junit-jupiter`) and run via the Maven Surefire plugin during the `test` phase.

| Property | Value |
|----------|-------|
| Naming convention | `*Test.java` |
| Excludes | `*IntegrationTest.java` |
| Maven phase | `test` |
| Plugin | `maven-surefire-plugin` 3.5.6 |
| Log level | `mockserver.logLevel=${mockserver.testLogLevel}` (default: `ERROR`) |
| Locale | `en-GB` (`-Duser.language=en -Duser.country=GB`) |
| Test listener | `org.mockserver.test.PrintOutCurrentTestRunListener` |
| XML reports | Controlled by `${disableXmlReport}` (default: `true`) |
| Forked process timeout | 1800 seconds |
| Fork count | Default (1 fork per module; `forkCount=0` commented out for debugging) |

### Running Unit Tests

```bash
./mvnw test

./mvnw test -pl mockserver-core

./mvnw test -pl mockserver-netty -Dtest=MockServerRuleTest
```

## Integration Tests

Integration tests use JUnit 4 and run via the Maven Failsafe plugin during the `integration-test` and `verify` phases. They start real servers (Netty, embedded Tomcat) and exercise full request/response flows.

| Property | Value |
|----------|-------|
| Naming convention | `*IntegrationTest.java` |
| Maven phase | `integration-test` / `verify` |
| Plugin | `maven-failsafe-plugin` 3.5.6 |
| Log level | `mockserver.logLevel=${mockserver.testLogLevel}` (default: `ERROR`) |
| Extra system properties | `project.version`, `project.basedir` |
| Locale | `en-GB` |
| Test listener | `org.mockserver.test.PrintOutCurrentTestRunListener` |
| XML reports | Controlled by `${disableXmlReport}` (default: `true`) |
| Forked process timeout | 1800 seconds; 2400 seconds in `mockserver-netty`, whose integration tests all share one fork (see [build-system.md](operations/build-system.md#test-configuration)) |

### Running Integration Tests

```bash
./mvnw verify

./mvnw verify -pl mockserver-netty
```

### Integration Test Infrastructure

The `mockserver-integration-testing` module provides shared abstract test base classes:

| Class | Purpose |
|-------|---------|
| `AbstractMockingIntegrationTestBase` | Base class with common setup/teardown (MockServerClient, EchoServer, NettyHttpClient) |
| `AbstractBasicMockingIntegrationTest` | Basic mocking test cases (~50 methods) |
| `AbstractExtendedMockingIntegrationTest` | Extended mocking with all action types (~123 methods) |
| `AbstractExtendedSameJVMMockingIntegrationTest` | Same-JVM callback testing |
| `AbstractBasicMockingSameJVMIntegrationTest` | Basic tests for same-JVM scenarios |
| `AbstractProxyIntegrationTest` | Proxy mode integration tests |

### Embedded Server Infrastructure

Integration tests use real embedded servers rather than mocks:

| Server | Module | Purpose |
|--------|--------|---------|
| `EchoServer` (Netty) | `mockserver-core` | Returns request details as response body (secure + insecure instances) |
| Embedded Tomcat 9.2.x | `mockserver-war`, `mockserver-proxy-war` | WAR deployment testing |
| MockServer (Netty) | `mockserver-netty` | Full server lifecycle testing |

### Downstream Dependency Integration Tests

`mockserver/mockserver-netty/src/integration-tests/` contains Maven Invoker and Gradle-based tests that verify the published `mockserver-netty` artifacts work correctly when consumed by a downstream project:

| Directory | Purpose |
|-----------|---------|
| `maven-netty-jar-with-dependencies-dependency/` | Tests the `:jar-with-dependencies` classifier as a Maven dependency |
| `maven-netty-no-dependencies-dependency/` | Tests `mockserver-netty-no-dependencies` as a Maven dependency |
| `gradle-netty-no-dependencies-dependencies/` | Tests `mockserver-netty-no-dependencies` from Gradle |

These are run by `maven-invoker-plugin` (Maven variants, `parallelThreads=2`) and `exec-maven-plugin` calling `gradle_integration_tests.sh` (Gradle variant) during the `integration-test`/`install` phases. The legacy `maven-netty-shaded-dependency` and `gradle-netty-shaded-dependencies` directories were removed in 6.0.0 along with the `<classifier>shaded</classifier>` artifact form.

## Container Integration Tests

Located in `container_integration_tests/`, these tests verify MockServer behaviour when running as a Docker container or Kubernetes pod. **These are NOT run in CI** — they are only invoked by `local_quick_build.sh` and `local_online_build.sh`. Estimated runtime is ~5-10 minutes (with pre-built JAR).

### Running Container Integration Tests

```bash
container_integration_tests/integration_tests.sh

SKIP_HELM_TESTS=true container_integration_tests/integration_tests.sh

SKIP_JAVA_BUILD=true SKIP_DOCKER_BUILD_MOCKSERVER=true container_integration_tests/integration_tests.sh
```

### Environment Variable Controls

| Variable | Default | Purpose |
|----------|---------|---------|
| `SKIP_JAVA_BUILD` | unset | Skip `mvnw package` step |
| `SKIP_DOCKER_BUILD_MOCKSERVER` | unset | Skip building MockServer Docker image |
| `SKIP_DOCKER_REBUILD_CLIENT` | unset | Skip rebuilding the curl client image |
| `SKIP_ALL_TESTS` | unset | Skip all tests (build only) |
| `SKIP_DOCKER_TESTS` | unset | Skip Docker Compose tests |
| `SKIP_HELM_TESTS` | unset | Skip Helm/k3d tests |

### Docker Compose Tests (16)

Each test has its own directory containing a `docker-compose.yml` and `integration_test.sh`:

| Test | Validates |
|------|-----------|
| `docker_compose_forward_with_override` | Forward with request/response override |
| `docker_compose_remote_host_and_port_by_environment_variable` | Remote host/port via `MOCKSERVER_PROXY_REMOTE_HOST`/`PORT` |
| `docker_compose_server_port_by_command` | Server port via command-line argument |
| `docker_compose_server_port_by_environment_variable_long_name` | Server port via `MOCKSERVER_SERVER_PORT` |
| `docker_compose_server_port_by_environment_variable_short_name` | Server port via `SERVER_PORT` |
| `docker_compose_without_server_port` | Default port (1080) |
| `docker_compose_with_expectation_initialiser` | Expectation initialiser class |
| `docker_compose_with_persisted_expectations` | Persisted expectations file |
| `docker_compose_with_server_port_from_default_properties_file` | Port from `mockserver.properties` |
| `docker_compose_with_server_port_from_custom_properties_file` | Port from custom properties file |
| `docker_compose_with_mtls` | Mutual TLS (mTLS) client certificate verification |
| `docker_compose_jvm_options` | Custom JVM options via `JAVA_OPTS` |
| `docker_compose_libs_classpath` | Additional JARs on the `/libs` classpath |
| `docker_compose_graceful_shutdown` | Graceful shutdown on SIGTERM |
| `docker_compose_metrics` | Prometheus metrics endpoint |
| `docker_compose_war_tomcat` | WAR deployment on Tomcat |

### Helm Tests (8)

Helm tests use k3d (k3s in Docker) to create a local cluster:

| Test | Validates |
|------|-----------|
| `helm_default_config` | Default Helm chart values |
| `helm_local_docker_container` | Local Docker image loaded into k3d |
| `helm_custom_server_port` | Custom server port via Helm values |
| `helm_remote_host_and_port` | Remote host/port via Helm values |
| `helm_inline_config` | Inline config via Helm values (`app.config.enabled`, `app.config.initializerJson`) |
| `helm_configmap_injection` | External config via ConfigMap |
| `helm_mockserver_config_chart` | Standalone MockServer config Helm chart |
| `helm_clustered_convergence` | Clustered state convergence (Infinispan, non-blocking) |

### Helper Scripts

| Script | Purpose |
|--------|---------|
| `integration_tests.sh` | Main orchestrator: builds Docker image, runs all tests, prints summary |
| `docker-compose.sh` | Docker Compose helper functions (`start-up`, `tear-down`, `docker-exec`, `container-logs`) |
| `helm-deploy.sh` | k3d cluster lifecycle (`start-up-k8s`, `tear-down-k8s`), Helm install/uninstall |
| `logging.sh` | Coloured terminal output, `runCommand`, `retryCommand`, `logTestResult` |

### Test Flow

```mermaid
sequenceDiagram
    participant R as integration_tests.sh
    participant D as Docker
    participant T as integration_test.sh

    R->>R: Source logging.sh, helm-deploy.sh, docker-compose.sh
    R->>D: Build MockServer image (mockserver/mockserver:integration_testing)
    R->>D: Build curl client image

    loop For each Docker Compose test
        R->>T: cd test_dir && ./integration_test.sh
        T->>D: docker-compose -p $TEST_CASE up -d
        T->>D: docker-exec client "curl ..." (create expectation)
        T->>D: docker-exec client "curl ..." (verify behaviour)
        T->>R: logTestResult $? $TEST_CASE
        T->>D: docker-compose -p $TEST_CASE down
    end

    R->>R: Start k3d cluster
    loop For each Helm test
        R->>T: cd test_dir && ./integration_test.sh
        T->>D: helm install ... && kubectl wait ...
        T->>D: kubectl exec client -- curl ...
        T->>R: logTestResult $? $TEST_CASE
        T->>D: helm uninstall
    end
    R->>R: Tear down k3d cluster

    R->>R: Print PASSED/FAILED summary
```

### Docker Image Variant Coverage

There are 6 production Docker image variants. Only the main nonroot variant is tested:

| Variant | Dockerfile | Base Image | Tested? |
|---------|-----------|------------|---------|
| Main (nonroot) | `docker/Dockerfile` | `distroless/java-base` + jlink Temurin 26 | YES — built `source=copy` + native tcnative/TLS verified by `docker-build-verify.sh` in the developer pre-commit gate (not yet wired into CI), plus the `integration_testing` HTTP suite on master |
| GraalJS | `docker/graaljs/Dockerfile` | `distroless/java25:nonroot` | NO |
| Root | `docker/root/Dockerfile` | `distroless/java25` | NO |
| Snapshot (debug) | `docker/snapshot/Dockerfile` | `distroless/java25:debug-nonroot` | NO |
| Root Snapshot | `docker/root-snapshot/Dockerfile` | `distroless/java25` | NO |
| Local build | `docker/local/Dockerfile` | `distroless/java-base` + jlink Temurin 26 | NO |

**Build coverage of the tcnative path.** `docker-build-verify.sh` builds the image with `--build-arg source=copy` from a locally-built jar. It passes no `--build-arg TARGETARCH`, so BuildKit supplies the arch as it does for the release pushes. It then asserts the runtime actually works: the arch-correct tcnative `.so` is baked in and has the host arch's ELF machine, the **native** TLS provider and epoll transport load in the image JVM (`OpenSsl.isAvailable`, `Epoll.isAvailable`), and the container serves a real TLS handshake. It runs in the developer pre-commit gate (`.opencode/rules/commit-workflow.md`); its **runtime assertions are not yet in CI**, because `pipeline-container-tests.yml` leaves the step out until it has had a green run on a real amd64 `default`-queue agent. CI does **build** images on a `docker/**` change: the `mockserver-container-tests` Helm step (`helm-integration-test.sh`) builds `docker/Dockerfile` (`source=copy`) and `docker/clustered` on an amd64 agent, so their in-build arch and ELF checks run there. The `mockserver-infra` pipeline runs the static `docker-validate-sync.sh` lint. Separately, the fast `verify-tcnative-stamp.sh` gate guards **both** stamped jars (the `source=download` assembly jar and the `source=copy` shaded jar) — non-empty, well-formed, mutually consistent, and equal to the Maven-resolved `netty-tcnative-boringssl-static` version — which is what catches the actual failure mode (a jar shipped without its stamp).

**Residual gap — stated, not implied.** The default `source=download` mode (which pulls the assembly jar from Sonatype) is **not built by CI**: it cannot use a published jar because a pre-stamp release fails the derive check by design, so building it would require standing up a locally-served jar — disproportionate machinery. Instead it is covered two ways short of a full download-mode build: (1) `verify-tcnative-stamp.sh` derive-checks the exact assembly jar that `source=download` consumes (the one broken by the last defect), and (2) `docker-build-verify.sh`, run locally, builds `source=copy` **from that same assembly jar**, faithfully reproducing `source=download`'s native behaviour (the assembly jar bundles the per-platform natives, so the native provider loads exactly as it would in download mode). The `mockserver-infra` pipeline additionally runs the static `docker-validate-sync.sh` lint on every `docker/**` change.

### What Docker Features Are Tested

- Basic container startup and HTTP API responsiveness
- Environment variable handling (`MOCKSERVER_SERVER_PORT`, `SERVER_PORT`, `PROXY_REMOTE_HOST`, `PROXY_REMOTE_PORT`, `MOCKSERVER_LOG_LEVEL`, `MOCKSERVER_INITIALIZATION_JSON_PATH`, `MOCKSERVER_PROPERTY_FILE`, `MOCKSERVER_PERSIST_EXPECTATIONS`, `MOCKSERVER_PERSISTED_EXPECTATIONS_PATH`)
- Volume mounts for configuration files and persisted state
- Command-line argument passing (`-serverPort`)
- Multi-container networking (bridge network between client and server)
- HTTP/2 support (via `nghttp` in the forward-with-override test)
- The image `HEALTHCHECK` reaching healthy (`docker_healthcheck`), and the shipped image surviving sustained load inside the documented 512 MiB floor (`docker_memory_floor_512m`)

### What Docker Features Are NOT Tested

| Feature | Status |
|---------|--------|
| Graceful shutdown (signal handling) | No test verifies `docker stop` drains connections or persists state. |
| Multi-arch (ARM64) | CI builds `linux/amd64,linux/arm64` but integration tests only run on native arch. |
| JVM options (`JVM_OPTIONS` env var) | Supported by Helm chart but never tested via Docker Compose. |
| `/libs/*` classpath extension | Entrypoint includes `/libs/*` on classpath for custom JARs but no test mounts into `/libs/`. `/extensions/*` is covered by `docker-build-verify.sh` (developer pre-commit gate, not CI), which mounts a compiled extension JAR and checks it serves under `-Xshare:on`. |
| Custom TLS certificates | Forward-with-override uses HTTPS via `nghttp` but no test exercises custom certs or mTLS. |
| Resource limits / memory constraints | No test verifies behaviour under memory pressure. |

### What Helm Features Are Tested

The 5 Helm tests cover basic deployment, custom image tags, custom server port, inline configuration, and inter-service proxy forwarding. All use the same validation pattern: create expectation via PUT, verify response via GET.

### What Helm Features Are NOT Tested

| Feature | In Chart? | Tested? |
|---------|-----------|---------|
| Ingress (`ingress.yaml`, 52 lines) | YES | NO |
| Service types (ClusterIP, LoadBalancer) | YES | NO (only NodePort) |
| Resource limits | YES | NO |
| ConfigMap (`app.config.enabled`) | YES | NO |
| Probes (readiness/liveness) | YES (hardcoded) | Implicit only (via `--wait`) |
| Affinity / tolerations / node selectors | YES | NO |
| Pod annotations / security context | YES | NO |
| Replica count >1 | YES | NO |
| `helm test` hook (`service-test.yaml`) | YES | NO (never invoked) |
| `helm lint` / `helm template` | N/A | NO (not run anywhere) |
| `mockserver-config` chart | YES (separate chart) | NO (zero tests) |
| `mountedLibsConfigMapName` | YES | NO |

## Performance Tests

The `docker_build/performance/Dockerfile` provides a k6-based performance testing image built on `grafana/k6`. The scenarios (smoke / load / stress / soak) and their threshold gates live in [`mockserver-performance-test/k6/`](../mockserver-performance-test/k6/README.md); CI lints them on every change and runs an opt-in load test on manual/scheduled builds.

## Test Utilities

### mockserver-testing Module

Shared test utilities available to all modules:

| Class | Purpose |
|-------|---------|
| `PrintOutCurrentTestRunListener` | JUnit `RunListener` that prints STARTED/FINISHED/FAILED/IGNORED with timing |
| `Assert` | Custom assertion helpers: `assertContains`, `assertDoesNotContain`, `assertSameEntries` |
| `Retries` | Retry logic for flaky/async operations: `tryWaitForSuccess(runnable, maxAttempts, interval)` |
| `TempFileWriter` | Temporary file creation for tests |
| `IsDebug` | Detects if running under a debugger; adjusts timeout units (seconds vs. minutes) |

### PrintOutCurrentTestRunListener

Configured globally via Surefire/Failsafe in the root `pom.xml`:

```xml
<properties>
    <listener>org.mockserver.test.PrintOutCurrentTestRunListener</listener>
</properties>
```

Supports three output modes controlled by `-Dmockserver.testOutput`:

| Mode | Default | Behaviour |
|------|---------|-----------|
| `verbose` | Yes (local) | Prints `STARTED:` and `FINISHED:` with duration for every test |
| `quiet` | CI | Prints dots (`.`) for pass, `F` for failures, `S` for skipped, with per-class summaries |
| `summary` | No | Prints per-class pass/fail counts with failure details only |

## Test Configuration

### Surefire Configuration (Root `pom.xml`)

```xml
<plugin>
    <groupId>org.apache.maven.plugins</groupId>
    <artifactId>maven-surefire-plugin</artifactId>
    <version>${maven-surefire-plugin.version}</version>
    <configuration>
        <forkedProcessTimeoutInSeconds>1800</forkedProcessTimeoutInSeconds>
        <includes>
            <include>**/*Test.java</include>
        </includes>
        <excludes>
            <exclude>**/*IntegrationTest.java</exclude>
        </excludes>
        <systemPropertyVariables>
            <mockserver.logLevel>${mockserver.testLogLevel}</mockserver.logLevel>
        </systemPropertyVariables>
        <argLine>@{argLine} -Duser.language=en -Duser.country=GB
                 -Dmockserver.testOutput=${mockserver.testOutput}
                 -Dmockserver.maxLogEntries=1000
                 ${mockserver.leakArgLine} ${mockserver.testArgLine}</argLine>
        <disableXmlReport>${disableXmlReport}</disableXmlReport>
        <redirectTestOutputToFile>${redirectTestOutputToFile}</redirectTestOutputToFile>
        <properties>
            <property>
                <name>listener</name>
                <value>org.mockserver.test.PrintOutCurrentTestRunListener</value>
            </property>
        </properties>
    </configuration>
</plugin>
```

### Failsafe Configuration (Root `pom.xml`)

```xml
<plugin>
    <groupId>org.apache.maven.plugins</groupId>
    <artifactId>maven-failsafe-plugin</artifactId>
    <version>${maven-surefire-plugin.version}</version>
    <configuration>
        <forkedProcessTimeoutInSeconds>1800</forkedProcessTimeoutInSeconds>
        <includes>
            <include>**/*IntegrationTest.java</include>
        </includes>
        <systemPropertyVariables>
            <mockserver.logLevel>${mockserver.testLogLevel}</mockserver.logLevel>
            <project.version>${project.version}</project.version>
            <project.basedir>${project.basedir}</project.basedir>
        </systemPropertyVariables>
        <argLine>@{argLine} -Duser.language=en -Duser.country=GB
                 -Dmockserver.testOutput=${mockserver.testOutput}
                 -Dmockserver.maxLogEntries=1000
                 ${mockserver.leakArgLine} ${mockserver.testArgLine}</argLine>
        <disableXmlReport>${disableXmlReport}</disableXmlReport>
        <redirectTestOutputToFile>${redirectTestOutputToFile}</redirectTestOutputToFile>
        <properties>
            <property>
                <name>listener</name>
                <value>org.mockserver.test.PrintOutCurrentTestRunListener</value>
            </property>
        </properties>
    </configuration>
    <executions>
        <execution>
            <goals>
                <goal>integration-test</goal>
                <goal>verify</goal>
            </goals>
        </execution>
    </executions>
</plugin>
```

### Parallelization

| Level | Mechanism | CI | Local |
|-------|-----------|-----|-------|
| Module-level | Maven `-T` flag | `-T 1C` (1 thread/core) | `-T 3C` (3 threads/core) |
| Maven Invoker tests | `<parallelThreads>` | 2 threads | 2 threads |
| `mockserver-core` unit tests | Two-phase Surefire: phase 1 `parallel=classes/threadCount=4`; phase 2 sequential | 4 threads (phase 1) | 4 threads (phase 1) |
| Other modules | Default (1 fork/module) | Default | Default |

`mockserver-core` uses a two-phase Surefire configuration: the default execution runs most unit tests in parallel (`parallel=classes`, `threadCount=4`), while a second execution (`sequential-tests`) runs a curated list of state-sensitive or timing-sensitive tests single-threaded. Tests excluded from the parallel phase include `MockServerEventLogTest`, `HttpStateTest`, template engine tests, and others that have wall-clock timing assertions or shared static state. All other modules run Surefire with its default (single-threaded) settings.

### Maven Profiles (Test-Related)

| Profile | Activation | Purpose |
|---------|-----------|---------|
| `kill_mockserver_instances` | Auto on Unix (`/usr/bin/env` exists) | Kills stray MockServer processes during `clean` phase via `scripts/stop_MockServer.sh` |

### Test Skip Flags

| Flag | Where Used | Effect |
|------|-----------|--------|
| `-DskipTests` | Various scripts | Skips Surefire + Failsafe + Invoker + Gradle tests |
| `-Dmaven.test.skip=true` | `local_release.sh` | Skips test compilation and execution |
| `${skipTests}` | `mockserver-netty/pom.xml` | Controls Invoker (`<skipInvocation>`) and Gradle tests (`<skip>`) |

## Test Data and Fixtures

Test resources are located in `src/test/resources/` across modules:

| Module | Resource Types |
|--------|---------------|
| `mockserver-core` | OpenAPI specs (YAML/JSON), JSON schemas, XML schemas, TLS certificates/keys, initializer JSON files, binary files (PNG) |
| `mockserver-netty` | TLS certificates and keys (RSA and EC), CSR configs, initializer JSON |
| `mockserver-war` | XML schema, Tomcat `catalina.properties` |
| `mockserver-proxy-war` | Tomcat `catalina.properties` |
| `mockserver-junit-jupiter` | Mockito `mock-maker-inline` config |
| `mockserver-integration-testing` | XML schema |

## Test Coverage Tooling

**JaCoCo** (`jacoco-maven-plugin`) is configured in the parent `mockserver/pom.xml` with four execution goals:

| Goal | Phase | Purpose |
|------|-------|---------|
| `prepare-agent` | `initialize` | Instruments classes for unit test coverage |
| `prepare-agent-integration` | `pre-integration-test` | Instruments classes for integration test coverage |
| `report` | `verify` | Generates per-module unit test coverage report |
| `report-integration` | `verify` | Generates per-module integration test coverage report |

Reports are generated in each module's `target/site/jacoco/` directory. See [AI-Assisted Development](operations/ai-assisted-development.md#code-coverage-unit-tests-jacoco) for current coverage numbers by module.

## Known Issues

### Test Anti-Patterns

| Pattern | Count | Details |
|---------|-------|---------|
| Mega-test methods (>200 lines) | 6 | Largest: `shouldHandleInvalidOpenAPIJsonRequest()` at 1994 lines in `HttpStateTest` |
| Excessive mocking (>100 mocks) | 2 | `MockServerClientTest` (130), `HttpActionHandlerTest` (109) |
| TODO comments about missing coverage | 4 | `NottableStringMultiMapContainAllTest`, `HttpRequestsPropertiesMatcherTest` |

### Known Coverage Limits

The July 2026 coverage audit's gaps are closed, except gap 1, which was declined; gap 82 was reopened
and is enforced as Rule 8 of the false-green guard (see
[False-Green Guards → Other coverage-audit proposals](operations/false-green-guards.md#other-coverage-audit-proposals));
one further gap no longer applies. Method note: CI execution for the closed CI-truth gaps was inferred
from pom/pipeline wiring, not from reading build logs. The transparent-proxy end-to-end suites
(gaps 19–21) are the exception, confirmed from `mockserver-java` build logs 3279 and 3282. Some of the
coverage that closed the gaps has limits:

- The UI Playwright e2e suite runs only when `mockserver-ui/` changes, so a server-only change that breaks the dashboard does not trigger it.
- Cloud persist-and-reload is tested end to end for S3 only; GCS and Azure have factory-wiring tests only.
- The h2c suite is bespoke rather than a subclass of the abstract mocking suite, so the matcher matrix is not re-run over h2c.
- HTTP/3 covers the streaming forward relay, but not a mocked `httpSseResponse` over QUIC.
- The AsyncAPI live-broker control-plane test covers Kafka. The MQTT load path is not driven.
- The IPv6 SAN case is covered; the netmask/CIDR branch is not.
- **Gap 47:** the Go, .NET and Rust live tests do not drive a forward **class** callback, because the server image ships no `ExpectationForwardCallback` class to name. The Rust client also has no closure API for a forward object callback, so only Go and .NET drive that one.
- **Gap 47:** a Go `DnsRecord` cannot set TTL 0. `TTL` is an `int` with `omitempty`, so 0 is served as the server default of 300. Fixing it means making the field `*int`, which is a breaking change.
- **Gap 6:** embedded-secret masking is asserted at the configuration serializer, not through an HTTP GET.
- **Gap 5:** runtime mTLS enforcement is toggled through the live `Configuration` object, not an HTTP PUT. The enforcement itself is still asserted over the wire.
- **Gap 73:** cassette auto-registration is proven in two halves: one test shows the MCP tool registers it, and a separate test shows `GET` lists it. No single test spans both.
- **Gap 62:** `ConnectionLifecycleChaos` relies on the Failsafe shard configuration to run. No CI log was read to confirm it actually executes.
- `Http3ConnectUdpIntegrationTest.shouldNotInterceptConnectWhenFlagDisabled` does not prove that the flag removes the CONNECT-UDP handler. It sends a plain CONNECT, which reaches the normal pipeline (and answers 404) whether the flag is on or off, because the handler only takes extended-CONNECT `connect-udp` requests. A test that sends a `connect-udp` request with the flag off would close this. The gap predates Rule 8.

## CI Test Execution

### Buildkite

The `scripts/buildkite_quick_build.sh` script runs the full build inside a `mockserver/mockserver:maven` Docker container:

```bash
./mvnw -T 1C clean install -Djava.security.egd=file:/dev/./urandom \
  -P "$PROFILES" -Dmockserver.shadeSourcesJar=false \
  -Dmockserver.testOutput=quiet -DdisableXmlReport=false \
  -DredirectTestOutputToFile=true -Dmockserver.testLogLevel=INFO \
  "-Dmockserver.testArgLine=-Dmockserver.maxLogEntries=10000 -Dmockserver.maxExpectations=5000"
```

| Property | Value |
|----------|-------|
| JVM heap | `-Xms2048m -Xmx6144m` |
| Maven parallelism | `-T 1C` (1 thread per CPU core) |
| Profiles (`$PROFILES`) | `clustered-libs`, plus `netty-it-skip` when `MOCKSERVER_NETTY_ITS_IN_SHARDS=true` (CI), whose netty integration tests run in the shard steps |
| Test output | `quiet` (dots + failure details) |
| XML reports | Enabled (`-DdisableXmlReport=false`) |
| Test output redirection | Enabled (`-DredirectTestOutputToFile=true`) |
| Test log level | `INFO` (`-Dmockserver.testLogLevel=INFO`) |
| Event log and expectation caps | 10,000 log entries and 5,000 expectations per test server, through `-Dmockserver.testArgLine`, which overrides the poms' `-Dmockserver.maxLogEntries=1000` |
| Netty leak gate | On: the forks of `mockserver-core` and `mockserver-netty` run the leak detector at `paranoid` (allocation sites only, `targetRecords=0`) and `check-netty-leaks` fails the build on a leaked buffer, or when tests ran but no fork installed the detector. `-Dmockserver.testArgLine` does not remove it (see [ByteBuf Leak Detection in Tests](code/netty-pipeline.md#bytebuf-leak-detection-in-tests)) |
| `-no-dependencies` source jars | Not built (`-Dmockserver.shadeSourcesJar=false`); the snapshot deploy and the release build them |
| Timeout | 90 minutes |
| Build artefacts | `**/*.log` files collected |

All tests (unit + integration + Maven Invoker + Gradle integration) run in a **single monolithic Buildkite step**. There is no separation into different CI jobs for different test types.

### Local Development

The `scripts/local_quick_build.sh` script:
1. Sets 8GB heap allocation
2. Uses Java 17 (`/usr/libexec/java_home -v 17`)
3. Runs parallel Maven build (`-T 3C`)
4. Runs container integration tests with `SKIP_JAVA_BUILD=true`

```bash
./scripts/local_quick_build.sh

./scripts/local_single_test.sh

./scripts/local_single_module.sh
```
