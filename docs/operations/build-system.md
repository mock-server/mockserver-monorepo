# Build System

## Monorepo Build Landscape

The monorepo contains multiple projects with different build tools:

| Directory | Build Tool | Build Command (from repo root) |
|-----------|-----------|-------------------------------|
| `mockserver/` | Maven (`./mvnw`) | `cd mockserver && ./mvnw clean install` |
| `mockserver-ui/` | Vite + npm | `cd mockserver-ui && npm ci && npm run build` |
| `mockserver-node/` | Grunt + npm | `cd mockserver-node && npm ci && npx grunt` |
| `mockserver-client-node/` | npm + TypeScript | `cd mockserver-client-node && npm ci && npm test` |
| `mockserver-client-python/` | pip + pytest | `cd mockserver-client-python && pip install -e '.[dev]' && pytest` |
| `mockserver-client-ruby/` | Bundler + RSpec | `cd mockserver-client-ruby && bundle install && bundle exec rspec` |
| `mockserver/mockserver-maven-plugin/` | Maven | `cd mockserver && ./mvnw clean install -DskipTests && ./mvnw -f mockserver-maven-plugin/pom.xml clean verify` |
| `mockserver-performance-test/` | k6 (JavaScript) | `cd mockserver-performance-test && for f in k6/*.js; do k6 inspect "$f"; done` |

> **Running the regression and growth scripts locally:** see [Performance regression pipeline — local runs](#performance-regression-pipeline-local-runs) below.

CI builds are orchestrated by `.buildkite/scripts/generate-pipeline.sh` which selects pipelines based on changed files. See [CI/CD](../infrastructure/ci-cd.md) for details.

## Local Development: Keep Tests Off the OS Ephemeral Port Range

**If the test suite fails locally in ways that make no sense — a server that cannot bind, a
response from software that is not MockServer — set a test port band before blaming the code.**

```bash
./mvnw verify -Dmockserver.testArgLine="-Dmockserver.testPortRangeStart=20000 -Dmockserver.testPortRangeEnd=40000"
```

### Why

`PortFactory.findFreePort()` asks the OS for a free port by binding port 0, which draws from the
ephemeral range (macOS `net.inet.ip.portrange.hifirst`..`hilast`, typically 49152-65535). Every other
application on the machine draws from the same pool. On a developer laptop with IDEs, sync agents and
corporate services running, a dozen or more foreign processes may be listening inside it. Two things
then go wrong: a port chosen a moment ago is taken before the test binds it, and — worse — a test
connects to whatever is already there and gets a reply from an unrelated program.

Both have been observed here: a bind failure on a port adjacent to a running application, a
`500 Server: Kestrel` from a .NET service answering a control-plane call, and a response body of
`{"TYPE":"TIER1","VERSION":"1.0"}` — a string that appears nowhere in this repository or any of its
dependencies.

### What it measurably does

Two full runs of `mockserver-core` + `mockserver-netty` on the same machine, same commit:

| | without the band | with the band |
|---|---|---|
| Integration tests executed | 2199 | 2268 |
| Bind failure (`Exception while binding ... to port 57002`) | yes | none |
| Cascaded failures from the server that never started | 3 | none |

The test count *rises* with the band because classes that previously died in `@BeforeClass` now run.

### What it does not do

It only helps where a port is chosen ahead of binding. Tests that start on port `0` and read the port
back never call `PortFactory`, so the band cannot reach them — those are already immune to losing a
port, and their occasional failures are a loaded machine rather than a stolen port. It covers TCP
only; the HTTP/3 suites bind UDP and are unaffected.

Both properties must be set, and they must reach the **forked test JVM** — hence `mockserver.testArgLine`
above. Passing `-Dmockserver.testPortRangeStart=...` directly to Maven sets it in the Maven JVM, where
`PortFactory` does not run, and silently does nothing. Leave them unset and behaviour is unchanged.
Passing `mockserver.testArgLine` does not switch off the Netty leak detector of `mockserver-core` and
`mockserver-netty`: its flags are in a separate property (`mockserver.leakArgLine`).

## Local Development Behind a Corporate TLS-Inspection Proxy

**TL;DR — if dependency downloads fail locally with TLS/`certificate verify failed`/`unable to get local issuer certificate` errors, your machine is behind a TLS-inspection proxy and the toolchains don't trust the corporate root CA. Point each toolchain at a *combined* CA bundle (system roots + corporate root). This is configured ONLY in your user/shell environment — never in repo files or pipeline scripts. CI agents have no proxy, so pipelines are unaffected and must stay that way.**

### Why this happens

A corporate TLS-inspection (MITM) proxy re-signs HTTPS traffic with a certificate chained to a corporate root CA. Package managers (npm, pip, cargo, composer, gem, go) and HTTP clients reject it because the corporate root is not in their default trust store. CI build agents run outside the proxy, so they never see this — which is exactly why the fix must live outside the repo.

### One-time setup (new laptop / new engineer)

1. Obtain the corporate root CA (PEM) from IT, e.g. `tesco_root_ca.pem`.
2. Build a **combined** bundle (system roots + corporate root) once and keep it at a stable path, e.g. `~/.tesco-ca/ca-bundle-with-tesco.pem`:
   ```bash
   mkdir -p ~/.tesco-ca
   # system roots from the OpenSSL/certifi default + the corporate root, concatenated:
   cat "$(python3 -m certifi 2>/dev/null || echo /etc/ssl/cert.pem)" ~/path/to/tesco_root_ca.pem \
     > ~/.tesco-ca/ca-bundle-with-tesco.pem
   ```
3. Add the per-toolchain settings below to your **shell profile** (`~/.zshrc`) and user configs. Nothing here is committed.

### Per-toolchain settings (user/shell scope only)

| Toolchain | Where it runs | Setting (local only) |
|-----------|---------------|----------------------|
| Anything in Docker via `.buildkite/scripts/run-in-docker.sh` (PHP/Python/Ruby/Go/Rust/.NET/Node) | container | `export LOCAL_DOCKER_CA_BUNDLE=~/.tesco-ca/ca-bundle-with-tesco.pem` — the wrapper mounts it read-only and points every in-container tool at it (opt-in; unset in CI). |
| Building **MockServer's own Docker images** (`docker/*/Dockerfile`, whose alpine download/copy stages `apk add` + `wget` the tcnative native `.so` from Maven Central through the proxy) | image build context | `export MOCKSERVER_LOCAL_CA_BUNDLE=~/.tesco-ca/ca-bundle-with-tesco.pem` — `docker/ensure-ca-bundle.sh <context-dir>` stages it into the context as `ca-bundle.pem` (which the stage COPYs in and trusts before the downloads). Prefer the **combined** bundle here: it becomes the alpine stage's entire trust store, so system roots must be included or Maven Central itself fails to verify. Falls back to `LOCAL_CA_BUNDLE`, then `NODE_EXTRA_CA_CERTS`, then `AWS_CA_BUNDLE` if unset — but those may be the corporate root **alone**, so set `MOCKSERVER_LOCAL_CA_BUNDLE` explicitly to the combined bundle. This is a **different** mechanism from `LOCAL_DOCKER_CA_BUNDLE` above (that one is `run-in-docker.sh` for client-language toolchains; this one is `ensure-ca-bundle.sh` for MockServer's image builds). The staged `ca-bundle.pem` is a build-context scratch file — never commit it. |
| npm — direct **and** the `mockserver/mockserver-netty` Maven `build-ui` step (frontend-maven-plugin) | host | `~/.npmrc` with `cafile=/Users/<you>/.tesco-ca/ca-bundle-with-tesco.pem` (npm always reads user `~/.npmrc`, so the Maven plugin's bundled node picks it up too). |
| Node (direct scripts) | host | `export NODE_EXTRA_CA_CERTS=~/.tesco-ca/ca-bundle-with-tesco.pem` |
| Python / pip / requests | host | `export PIP_CERT=~/.tesco-ca/ca-bundle-with-tesco.pem REQUESTS_CA_BUNDLE=~/.tesco-ca/ca-bundle-with-tesco.pem SSL_CERT_FILE=~/.tesco-ca/ca-bundle-with-tesco.pem` |
| Ruby / bundler / rubygems | host | `export SSL_CERT_FILE=~/.tesco-ca/ca-bundle-with-tesco.pem BUNDLE_SSL_CA_CERT=~/.tesco-ca/ca-bundle-with-tesco.pem` |
| Rust / cargo | host | `export CARGO_HTTP_CAINFO=~/.tesco-ca/ca-bundle-with-tesco.pem` |
| Go (and git clone over HTTPS) | host | `export SSL_CERT_FILE=~/.tesco-ca/ca-bundle-with-tesco.pem GIT_SSL_CAINFO=~/.tesco-ca/ca-bundle-with-tesco.pem` |
| AWS CLI / SDK / Terraform | host | `export AWS_CA_BUNDLE=~/.tesco-ca/ca-bundle-with-tesco.pem` (see [AGENTS.md](../../AGENTS.md) → AWS Prerequisites) |
| **k3d / k3s** — the local Kubernetes cluster the Helm integration tests (`container_integration_tests/helm-deploy.sh`) stand up | in-node containerd | `export K3D_LOCAL_CA_BUNDLE=~/.tesco-ca/ca-bundle-with-tesco.pem` — `helm-deploy.sh`'s `start-up-k8s` then overmounts it as the k3s node's containerd trust store when it creates the cluster (opt-in; unset in CI, where the created command is byte-identical). **Two trust stores, not one — this is the trap that made it look impossible:** the *host* Docker daemon already trusts the corporate root, so `k3d cluster create` pulls the k3s node image fine and "Docker works, so k3d should" seems true. But **containerd *inside* the k3s node has its own separate trust store** at `/etc/ssl/certs/ca-certificates.crt` (public roots only). Without the corporate root *there*, containerd cannot pull even the `rancher/mirrored-pause` sandbox image, so **every** pod fails at sandbox creation with `x509: certificate signed by unknown authority` — the symptom to recognise. Overmounting `${K3D_LOCAL_CA_BUNDLE}` at that path (`--volume ...:/etc/ssl/certs/ca-certificates.crt@server:*`) replaces containerd's *entire* trust store, so it **must** be the **combined** bundle (system/public roots + corporate root) — the corporate root alone would leave containerd unable to verify Docker Hub itself. |

The Java/Maven build itself usually works because the JDK trust store already chains Maven Central correctly; if a JVM HTTPS download fails (e.g. the frontend-maven-plugin downloading node from nodejs.org on a *cold* build), import the corporate root into a JDK trust store and pass `-Djavax.net.ssl.trustStore=...`, or pre-warm `mockserver-netty/target/frontend` once on a network without the proxy.

### Non-negotiable: keep proxy config out of the repo and pipelines

- **Never** add `cafile`/`strict-ssl`/proxy settings to a committed `.npmrc`, `pom.xml`, `pip.conf`, `Cargo` config, or any `.buildkite/scripts/**` step — that would break or skew CI, which has no proxy.
- The three repo-side hooks are all **opt-in / env-gated** and all no-op in CI: the `LOCAL_DOCKER_CA_BUNDLE` branch in `run-in-docker.sh` (does nothing unless set); `docker/ensure-ca-bundle.sh`, which stages `MOCKSERVER_LOCAL_CA_BUNDLE` (or its fallbacks) into an image build context but writes an **empty placeholder** when none is set — the Dockerfiles' `[ -s ]` guards then skip all trust changes, so CI and published images are byte-identical to a no-CA build; and the `K3D_LOCAL_CA_BUNDLE` branch in `container_integration_tests/helm-deploy.sh` (appends the containerd trust-store overmount only when set, warns-not-fails if the file is missing, and leaves the `k3d cluster create` command byte-identical when unset).
- All other settings live in your shell profile and user-level config files (`~/.npmrc`, `~/.zshrc`).

## Java Server Build (mockserver/)

### Maven Configuration

MockServer uses Maven 3.9.16 via the Maven Wrapper (`mvnw`). The project targets Java 17 source/target compatibility — produced bytecode runs on Java 17+, and building from source requires JDK 17+.

`mockserver/.mvn/maven.config` sets `-T 1C` so the reactor builds with the parallel (one-thread-per-core) `MultiThreadedBuilder` by default, matching CI (`./mvnw -T 1C clean install`). This is a free speed-up on the multi-module compile/package phases and composes with the within-module parallel unit tests (see [performance-tuning.md](performance-tuning.md)). Pass `-T 1` on the command line to force a single-threaded build when debugging reactor ordering or interleaved log output.

The same file also sets Maven Resolver HTTP-transport retry flags so a transient Maven Central failure (`Connection reset` / timeout / 5xx during artifact or plugin resolution) is retried in-process instead of failing the build:

```
-Daether.connector.http.retryHandler.count=5
-Daether.connector.http.retryHandler.requestSentEnabled=true
-Daether.connector.http.connectionMaxTtl=120
```

These are the **native** transport property names. Maven 3.9 defaults to the native Maven Resolver HTTP transport, which **ignores** the legacy `maven.wagon.http.*` properties — using the wagon names here would be a silent no-op. Because every Maven invocation runs with its working directory inside the `mockserver/` reactor, all of them inherit these flags through this one file (the isolated `maven-invoker-plugin` child builds, which have their own base directory, are the only exception and resolve against the already-populated local repo). This is the in-process layer of the broader transient-Central resilience described in [ci-cd.md → Transient Maven Central Resilience](../infrastructure/ci-cd.md#transient-maven-central-resilience).

### Modules

The project comprises 27 Maven reactor modules (the table also lists the standalone examples project):

| Module | Packaging | Purpose |
|--------|-----------|---------|
| `mockserver-bom` | pom (flattened) | Bill of Materials — import to align MockServer's own modules to one version (org.mock-server artifacts only; no third-party pins) |
| `mockserver-testing` | jar | Shared test utilities |
| `mockserver-client-java` | jar | Java client API (`MockServerClient`) |
| `mockserver-client-java-no-dependencies` | jar (shaded) | Client with all dependencies shaded |
| `mockserver-client-kotlin` | jar | Kotlin DSL over the Java client; compiled by `kotlin-maven-plugin` (the reactor's only Kotlin module) |
| `mockserver-core` | jar | Domain model, matching, TLS, templates, codecs, event log, action handlers |
| `mockserver-integration-testing` | jar | Integration test base classes |
| `mockserver-integration-testing-no-dependencies` | jar (shaded) | Integration test base classes, shaded |
| `mockserver-war` | war | Servlet WAR deployment |
| `mockserver-proxy-war` | war | Proxy-only WAR deployment |
| `mockserver-netty` | jar (+fat, shaded) | Netty server, CLI, dashboard, proxy relay |
| `mockserver-netty-no-dependencies` | jar (shaded) | Netty server with all dependencies shaded |
| `mockserver-netty-docker` | jar (shaded, not published) | The no-dependencies jar with JNA unrelocated; the published Docker images are built from it |
| `mockserver-junit-rule` | jar | JUnit 4 `@Rule` integration |
| `mockserver-junit-rule-no-dependencies` | jar (shaded) | JUnit 4 rule, shaded |
| `mockserver-junit-jupiter` | jar | JUnit 5 `@ExtendWith` integration |
| `mockserver-junit-jupiter-no-dependencies` | jar (shaded) | JUnit 5 extension, shaded |
| `mockserver-spring-test-listener` | jar | Spring Test integration |
| `mockserver-spring-test-listener-no-dependencies` | jar (shaded) | Spring Test integration, shaded |
| `mockserver-examples` (at `examples/java`, built standalone — not a reactor module) | jar | Usage examples |
| `mockserver-async` | jar | AsyncAPI broker mocking |
| `mockserver-testcontainers` | jar | Testcontainers integration module |
| `mockserver-state-infinispan` | jar | Infinispan-backed clustered state backend |
| `mockserver-blob-s3` | jar | S3 blob storage backend |
| `mockserver-blob-gcs` | jar | GCS blob storage backend |
| `mockserver-blob-azure` | jar | Azure blob storage backend |
| `mockserver-k8s-webhook` | jar (+fat) | Kubernetes admission webhook for sidecar injection |

#### What the `-no-dependencies` jars leave unrelocated

The six `-no-dependencies` jars share one shade configuration (`mockserver/pom.xml` pluginManagement), whose relocations are an explicit list of package prefixes. A dependency whose package is not on the list is bundled **unrelocated** and clashes with the user's own copy (issue #2772: GraphQL Java, picocli, Chicory, the RabbitMQ and Paho clients, HdrHistogram and others shipped that way in 8.0.0). `mockserver-netty-no-dependencies/src/packaging/assert-only-relocated-classes.sh` runs at `package` in all six modules and fails the build on any class outside `org/mockserver/` and `shaded_package/`, or any `META-INF/services` file for an unrelocated interface, that is not on its allow-list. **Adding a dependency to `mockserver-core`/`mockserver-netty` therefore means adding a relocation**, or an allow-list entry with its reason. <!-- version-ref:keep -->

Shade applies a relocation to string constants as well as class references, in MockServer's own classes too. Write a one-segment package with a trailing dot (`<pattern>graphql.</pattern>`): a bare `graphql` matched every string starting with `graphql` or `/graphql`, and turned MockServer's `/graphql` endpoint, its `graphql-ws` subprotocols and its metric labels into `shaded_package...` strings. A relocation that compiles and passes the guard can still break a feature this way, so exercise the feature from the shaded jar (`ExtendedNoDependenciesJarMockingIntegrationTest` forks it with `java -jar`).

**Native libraries and system properties under relocation.** Relocated Netty loads each JNI library (epoll, tcnative, QUIC) by a package-mangled name, so the shared configuration renames every `META-INF/native/libnetty_*` and `netty_*` resource to `libshaded_1package_netty_*` / `shaded_1package_netty_*`; before issue #2869 only epoll was renamed, and the bundled QUIC native never loaded, so `http3Port` refused to start the `mockserver-netty-no-dependencies` jar. Libraries whose natives bind to unrelocated class names stay unrelocated (table below). JDK-owned `com.sun` packages are excluded from the `com.sun` relocation (`assert-jdk-com-sun-not-relocated.sh`), including `com.sun.jndi`, `com.sun.security`, `com.sun.rowset` and `com.sun.org.apache`, which bundled libraries name as strings (Kafka's default disallowed JAAS login modules, Jackson's deserialization deny-list). Shade also rewrites system property names that start with a relocated package, so relocated Netty reads `shaded_package.io.netty.allocator.type`; `RelocatedLibraryProperties`, called by `Main` before Netty initialises, copies a user's `io.netty.*`, `org.apache.*`, `org.bouncycastle.*`, `io.prometheus.*`, `io.opentelemetry.*`, `com.fasterxml.*`, `com.google.*` and `jakarta.activation.*` system properties to the relocated names (CLI and images only, not embedded use). Three guards run at `package`: `assert-jni-libraries-unrelocated.sh` (all six jars and `mockserver-netty-docker`: no relocated zstd-jni, snappy-java or lz4-java class or reference, both linux natives present, no Netty native under Netty's own name), `assert-shaded-natives-load.sh` (the netty and docker jars: runs the jar for a zstd, snappy and lz4 round trip, QUIC, epoll and OpenSSL where bundled for the build machine, and checks `-Dio.netty.leakDetection.level` reaches relocated Netty) and the extended `assert-jdk-com-sun-not-relocated.sh`.

| Left unrelocated | Where | Why |
|---|---|---|
| `org.slf4j` (the API only) | all | MockServer logs through the user's SLF4J provider. The netty jar's `slf4j-jdk14` provider (issue #2097) is relocated to `shaded_package.org.slf4j.jul` with its `META-INF/services` file dropped, so SLF4J never finds it beside the user's; `Slf4jProviderFallback` (called first by `Main` and by `MockServerLogger`'s static init) sets `slf4j.provider` to it only when no provider is registered and the property is unset. The guard denies unrelocated provider packages |
| `org.apache.velocity` | all but client | its bundled `.properties` files name its classes and shade does not rewrite resource contents |
| `org.mozilla` (Rhino) | all but client | its compiler generates classes at runtime that name Rhino's own classes (relocated, `evaluateString` fails with `NoClassDefFoundError: org/mozilla/javascript/Context`) |
| `org.xerial.snappy`, `net.jpountz`, `com.github.luben.zstd` | snappy all; lz4 and zstd-jni netty (and the Docker jar) | JNI natives bind to the unrelocated class names. zstd-jni was relocated (with the rest of `com.github`) until issue #2869: its library still loaded and Netty's `Zstd.isAvailable()` was `true`, but every native call threw `UnsatisfiedLinkError`, so a zstd request closed the connection |

The JUnit and Spring integration jars do **not** bundle their framework: `artifactSet` excludes in `mockserver-junit-rule-no-dependencies` and `mockserver-integration-testing-no-dependencies` (JUnit 4), `mockserver-junit-jupiter-no-dependencies` (JUnit Jupiter / Platform, apiguardian) and `mockserver-spring-test-listener-no-dependencies` (Spring, Micrometer) leave it out, and each POM declares it `provided`, which consumers do not inherit. Bundled unrelocated it clashed with the user's own copy; relocated it would not work, because the user's test engine or Spring container drives these classes. MockServer's own classes in those jars call the framework only with JDK or framework types (checked with `javap`: no reference to a framework member carries a `shaded_package` type), so the relocated Hamcrest, opentest4j and `jakarta.annotation` they bundle do not leak into those calls.

#### Their `-sources.jar`, and the CI build that skips it

The shared shade configuration also builds each `-no-dependencies` jar's `-sources.jar` from the sources of what it bundles (`createSourcesJar`, `shadeSourcesContent`). These modules have no sources of their own, so on a release that jar is the one Maven Central requires. `createSourcesJar` reads the property `mockserver.shadeSourcesJar`, `true` by default. The CI `:maven: build` step (`scripts/buildkite_quick_build.sh`) passes `-Dmockserver.shadeSourcesJar=false`, because every later step that takes a jar from it skips `*-sources.jar` and shading the sources was several minutes of the step. The snapshot deploy step (`java-deploy-snapshot.sh`) and the release (`scripts/release/components/maven-central.sh`) do not pass it, so they still build and publish the source jars.

### Dependency management and the BOM

MockServer pins all of its third-party transitive versions in the **parent POM's `<dependencyManagement>`**, and the reactor's own Enforcer `dependencyConvergence` rule guards that everything resolves to a single version. That management is, by design, **not inherited by downstream consumers**.

`mockserver-bom` exists so consumers can declare any MockServer module without repeating its version and keep them mutually consistent. It **manages only MockServer's own `org.mock-server` artifacts.** Although the module inherits the parent POM's `<dependencyManagement>` (its third-party pins), those pins **must not** leak into the published BOM: expanding them silently overrode a consumer's own third-party versions **and scopes** when they imported the BOM — e.g. MockServer's test-scoped `oauth2-oidc-sdk` pin dragging a consumer's compile dependency onto the test classpath, breaking their production build ([GitHub issue #2684](https://github.com/mock-server/mockserver/issues/2684)).

To achieve this the `flatten-maven-plugin` (`flattenMode=bom`) uses **`dependencyManagement=extended_interpolate`** (not `expand`): `extended_interpolate` takes the `dependencyManagement` from this module's **raw** POM — only the hand-listed `org.mock-server` entries — and resolves their `${project.*}` placeholders against the effective model, **without** expanding the inherited parent management. The published, self-contained POM (no parent reference) therefore pins every MockServer module to `${project.version}` and nothing else. (`expand` and `resolve` would both re-inline the ~190 inherited third-party entries; `keep`/`interpolate` keep only our entries but leave `${project.version}` unresolved — `extended_interpolate` is the one handling that does both.)

**Consumers that need their third-party tree to converge** (e.g. under the Enforcer `dependencyConvergence` rule) must manage those versions themselves, or import each upstream project's own BOM; the MockServer BOM no longer does it for them. A guard test (`mockserver-bom/src/integration-tests/`, run via `maven-invoker-plugin`) fails the build if the published BOM ever manages a non-`org.mock-server` artifact again, or if importing it changes a consumer's oauth2-oidc-sdk version or scope.

Consumers import the BOM in `dependencyManagement`:

```xml
<dependencyManagement>
  <dependencies>
    <dependency>
      <groupId>org.mock-server</groupId>
      <artifactId>mockserver-bom</artifactId>
      <version>${mockserver.version}</version>
      <type>pom</type>
      <scope>import</scope>
    </dependency>
  </dependencies>
</dependencyManagement>
```

Two related choices reduce convergence pressure even without the BOM: `mockserver-client-java` **excludes the server-only engines** from its `mockserver-core` dependency (a client never executes templates/scripts/WASM/gRPC), and `mockserver-core` **prunes the stale `velocity-engine-core 2.3`** that `velocity-tools-generic` drags in alongside the `2.4.1` the build already uses. The parent's `flatten-maven-plugin` execution is declared `<inherited>false</inherited>`, so it applies only to the root POM; `mockserver-bom` carries its own copy.

#### Gotcha: `netty-tcnative` and other classifier-shipped artifacts

`netty-tcnative-boringssl-static` has **no version property of its own** — the imported `netty-bom` is the single source of truth, pinning the base artifact **and** every OS/arch classifier (`linux-x86_64`, `linux-aarch_64`, `osx-*`, `windows-x86_64`) in lockstep with `<netty.version>`. This is deliberate: an earlier explicit `<netty-tcnative-boringssl-static.version>` override contradicted the BOM and was a recurring convergence trap — every Netty bump moved the BOM-governed classifier jars while the override kept the base artifact pinned to the old version, failing the Enforcer `dependencyConvergence` rule in `mockserver-core` (a red master on Buildkite/CodeQL, not a test failure). Removing the override means a Netty bump now moves tcnative automatically and can never diverge. **Do not re-add a version or a property for this artifact.**

The tcnative **native `.so`** is not shipped in the shaded server jar — the natives cannot be shade-relocated and would clash with a consumer's own Netty (issue #1778), so `mockserver-netty-no-dependencies` strips `META-INF/native/**` and the jar ships tcnative **classes only**. The Docker images therefore fetch the matching native out-of-band. To keep that download at the exact same version as the classes **with no manual step**, the build stamps the Maven-resolved version at `META-INF/mockserver-tcnative.version` (via `maven-dependency-plugin:properties` exposing the resolved tcnative jar path + `src/packaging/stamp-tcnative-version.sh`), and each `docker/*/Dockerfile` reads it back from the jar it will ship to build the download URL.

The stamp must be present in **whichever jar the Dockerfile actually consumes**, and there are two, fed by the `source` build arg:

- **`source=copy`** (release and snapshot images — `--build-arg source=copy`) COPYs the shaded **`mockserver-netty-docker-<ver>.jar`**, which is the `mockserver-netty-no-dependencies` shade output with JNA unrelocated, so it carries that module's stamp unchanged. (The container-integration-test images copy the assembly jar instead.)
- **`source=download`** (the Dockerfiles' **default**, the public reference path a user gets from a plain `docker build`) downloads the **`mockserver-netty-<ver>-jar-with-dependencies.jar`** assembly artifact from Sonatype/Maven Central. That is the `mockserver-netty` module's maven-**assembly** output — a *different* jar that does not depend on the no-dependencies module — so `mockserver-netty` stamps its own `target/classes` at `prepare-package` (reusing the same script) and the assembly's `useProjectArtifact=true` unpack carries the stamp into the jar-with-dependencies.

Both stampings derive from the same netty-bom-governed resolution, so the two jars can never carry different versions. The read-back line each Dockerfile runs:

```dockerfile
RUN NETTY_TCNATIVE="$(unzip -p mockserver-netty-jar-with-dependencies.jar META-INF/mockserver-tcnative.version | tr -d '[:space:]')" \
    && if [ -z "$NETTY_TCNATIVE" ]; then echo "ERROR: ... missing ..." >&2; exit 1; fi \
    && ...   # build URL, wget, SHA256-verify, unzip the .so
```

There is no longer any hardcoded `NETTY_TCNATIVE=` build arg to keep in sync, so a Netty bump needs no Docker edit at all. Verify a bump locally with:

```bash
mvn -pl mockserver-core -am validate   # Enforcer runs at the validate phase
# look for: "Rule 0: ...DependencyConvergence passed"
```

Then confirm **both** shipped jars actually carry the stamp — this is the check that catches a stamp wired into the wrong artifact (the `source=download` assembly jar and the `source=copy` shade jar are built by different modules, so it is possible to stamp one and not the other):

```bash
# copy path (shade output) and download path (assembly output)
unzip -p mockserver/mockserver-netty-no-dependencies/target/mockserver-netty-no-dependencies-*.jar META-INF/mockserver-tcnative.version
unzip -p mockserver/mockserver-netty/target/mockserver-netty-*-jar-with-dependencies.jar     META-INF/mockserver-tcnative.version
# both must print the same non-empty version, equal to the netty-bom-resolved tcnative version.
# An empty read is exactly what makes the Dockerfile derive `exit 1`.
```

The same classifier-convergence discipline applies to any dependency that ships OS/arch classifier variants: let its umbrella BOM govern the version rather than pinning it independently.

#### The `COPY ... /usr/lib/` line and the `--read-only` guard

After the `.so` is fetched/unzipped, every `docker/*/Dockerfile` also does:

```dockerfile
COPY --from=intermediate META-INF/native/libnetty_tcnative_linux_*.so /usr/lib/
```

This looks redundant — the assembly jar already bundles the per-platform natives — but it is a **native-TLS fallback for read-only-rootfs deployments** (`docker run --read-only`), introduced by `e4dd3a61b`:

- **Default case (writable `/tmp`):** Netty's `NativeLibraryLoader` extracts the bundled native from the jar into `java.io.tmpdir` and loads it there. The `/usr/lib` copy is **never touched** — it is dormant.
- **`--read-only` case:** extraction to a read-only `/tmp` fails, so BoringSSL native TLS survives **only** because `System.loadLibrary` finds `/usr/lib/libnetty_tcnative_linux_<arch>.so` on the default `java.library.path` (`…:/usr/lib`). Remove the copy and those deployments **silently** downgrade to the JDK TLS provider — MockServer still serves TLS, so nothing looks broken.
- **Inert for the shaded `no-dependencies` jar:** that jar relocates tcnative to `shaded_package.io.netty.internal.tcnative`, while the stock `/usr/lib` `.so` exports `Java_io_netty_…` symbols, so it cannot satisfy the relocated classes. Only the **assembly** jar-with-dependencies (which keeps `io.netty.*`) can use it.

**What guards it:** `.buildkite/scripts/steps/docker-build-verify.sh`'s `assert_native_provider_readonly` starts the built image with `docker run --read-only` (deliberately **no** `--tmpfs /tmp`) and asserts `OpenSsl.isAvailable=true`. This is the *only* check that exercises the copy's purpose: `assert_so_present` merely confirms the file exists (it cannot tell a working `.so` from a broken one), and the plain `assert_native_provider` runs on a writable `/tmp` where the jar-bundled native masks a broken `/usr/lib` copy. Proven by replacing the image's `/usr/lib` `.so` with an empty file: under `--read-only` the probe goes red (`isAvailable=false`, "Failed to load any of the given libraries"), while the writable-`/tmp` run stays green — exactly the silent regression the guard exists to catch. **Do not add `--tmpfs /tmp` to that probe** — it would re-enable jar extraction and defeat the test.

### Quick Reference

All Maven commands run from within the `mockserver/` directory:

```bash
cd mockserver

# Full build (clean + compile + test + package)
./mvnw clean install

# Build without tests
./mvnw clean install -DskipTests

# Build a single module
./mvnw clean install -pl mockserver-core

# Run unit tests only
./mvnw test -pl mockserver-netty

# Run integration tests
./mvnw verify -pl mockserver-netty
```

### Build Scripts

| Script | Purpose |
|--------|---------|
| `scripts/buildkite_quick_build.sh` | CI build — `mvnw clean install` with 8GB heap |
| `scripts/buildkite_netty_it.sh` | CI `mockserver-netty` integration-test shards: `prebuild` (test-free install and archives) and `shard <proxy-http\|mock\|rest>` (failsafe and the leak gate only) — see [ci-cd.md](../infrastructure/ci-cd.md#step-3b-netty-integration-test-shards) |
| `scripts/buildkite_deploy_snapshot.sh` | CI deploy — `mvnw clean deploy` to Sonatype snapshots |
| `scripts/local_quick_build.sh` | Local build — Java 17, 3 threads, includes integration tests |
| `scripts/local_online_build.sh` | Local build — Java 17, includes integration tests |
| `scripts/local_buildkite_build.sh` | Run Buildkite build locally inside Docker |
| `scripts/local_build_module_by_module.sh` | Build each module sequentially |
| `scripts/local_release.sh` | Maven release (prepare + perform) to Sonatype staging |
| `scripts/local_deploy_snapshot.sh` | Deploy SNAPSHOT via Docker container |
| `scripts/local_single_test.sh` | Run a single integration test |
| `scripts/local_single_module.sh` | Build a single module |
| `scripts/stop_MockServer.sh` | Kill running MockServer processes |
| `scripts/bash_functions.sh` | Shared shell functions library |
| `scripts/download_maven_jars.sh` | Download Maven JARs from repositories |
| `scripts/install_ca_certificate.sh` | Install CA certificates into trust stores |
| `scripts/jekyll_server.sh` | Start Jekyll development server |
| `scripts/local_docker_launch.sh` | Launch interactive Docker Maven container |
| `scripts/local_docker_push_tag.sh` | Push Docker image with tag |
| `scripts/local_generate_web_site.sh` | Generate Jekyll documentation website |
| `scripts/local_javadoc_build_all_versions.sh` | Build Javadoc for all versions |
| `scripts/local_list_versions.sh` | List project versions |
| `scripts/log_event_size_test_*.sh` | Log event size test variants (4 scripts) |

## Maven Profiles

```mermaid
graph LR
    subgraph "Profiles"
        KM["kill_mockserver_instances
Auto-activates on Unix"]
        DJ["disable-java8-doclint
Auto-activates on JDK 8+"]
        RL["release
Manual activation"]
    end

    KM -->|clean phase| STOP["scripts/stop_MockServer.sh"]
    DJ -->|sets| DOCLINT["-Xdoclint:none"]
    RL -->|adds| SRC[maven-source-plugin]
    RL -->|adds| JD[maven-javadoc-plugin]
    RL -->|adds| GPG[maven-gpg-plugin]
    RL -->|adds| REL[maven-release-plugin]
```

| Profile | Activation | Purpose |
|---------|-----------|---------|
| `kill_mockserver_instances` | Auto on Unix (`/usr/bin/env` exists) | Kills existing MockServer processes during `clean` phase |
| `disable-java8-doclint` | Auto on JDK 8+ | Disables strict Javadoc linting |
| `release` | Manual (`-P release`) | Adds source JARs, Javadoc JARs, GPG signing, Maven release plugin |
| `git-worktree-commit-hash` (`mockserver-core`) | Auto when the repo root has no `.git/HEAD` (a linked git worktree, where `.git` is a file; also a source tarball with no `.git`) | Skips `git-commit-id-maven-plugin` and runs `git rev-parse --verify --short=8 HEAD` from `maven-antrun-plugin` in the module directory, so a worktree build reports its own HEAD (the plugin's JGit maps a worktree to the main checkout's HEAD). Any git failure leaves the hash empty and logs a warning instead of failing the build: a container that mounts only the worktree (its `.git` file points at an unmounted host path), no `git` binary, or no `.git` at all. The hash reaches the version class as a build filter (`target/git-worktree-commit-hash.properties`); `-Dmaven.gitcommitid.skip=true` also leaves it empty. Clones activate nothing and keep the plugin's JGit path |
| `netty-it-skip` (`mockserver-netty`) | Manual; CI's `:maven: build` when `MOCKSERVER_NETTY_ITS_IN_SHARDS=true` | Skips the module's integration tests, which the CI shard steps run instead |
| `netty-it-shard-proxy-http`, `netty-it-shard-mock`, `netty-it-shard-rest` (`mockserver-netty`) | Manual (`-P`); the CI shard steps | Narrow Failsafe to one shard: the packages in `mockserver.nettyItShard.proxyHttp`, those in `mockserver.nettyItShard.mock`, or everything else. Together they run every integration test once. See [ci-cd.md](../infrastructure/ci-cd.md#step-3b-netty-integration-test-shards) |

## Build Plugins

| Plugin | Version | Phase | Purpose |
|--------|---------|-------|---------|
| `maven-compiler-plugin` | 3.15.0 | compile | Java 17 compilation with `-Xlint:all` |
| `git-commit-id-maven-plugin` | 10.0.1 | initialize | Resolves the abbreviated git commit hash into `${git.commit.id.abbrev}` for the version class (mockserver-core only) with JGit; degrades to an empty hash when no git metadata is present. In a linked git worktree the `git-worktree-commit-hash` profile replaces it. `failOnUnableToExtractRepoInfo` is declared but never read by 10.0.1, so it cannot stop a native git failure from failing the build |
| `templating-maven-plugin` | 3.1.1 | generate-sources | Generates version class from templates (version, group/artifact id, git hash) |
| `maven-jar-plugin` | 3.5.0 | package | JAR packaging with MANIFEST.MF metadata |
| `maven-clean-plugin` | 3.5.0 | clean | Removes `.log`, keystore, and temp files |
| `maven-surefire-plugin` | 3.6.0 | test | Unit tests (`*Test.java`, excludes `*IntegrationTest.java`) |
| `maven-failsafe-plugin` | 3.6.0 | integration-test | Integration tests (`*IntegrationTest.java`) |
| `maven-checkstyle-plugin` | 3.6.0 | validate | Code style enforcement via `checkstyle.xml` |
| `maven-enforcer-plugin` | 3.6.3 | validate | Dependency convergence checks |
| `exec-maven-plugin` | 3.6.3 | clean | Runs `stop_MockServer.sh` (Unix profile) |

## Test Configuration

- **Unit tests:** `*Test.java` — run during `test` phase via Surefire
- **Integration tests:** `*IntegrationTest.java` — run during `integration-test`/`verify` phases via Failsafe
- **Netty leak gate (`mockserver-core`, `mockserver-netty`).** The parent pom's antrun executions `clean-netty-leaks`, `print-netty-leaks` and `check-netty-leaks` are active in any module that sets `mockserver.leakArgLine` (to `${mockserver.nettyLeakDetectorArgLine}`); elsewhere they do nothing. `check-netty-leaks` runs at `verify` and fails the build on a recorded leak (`-Dmockserver.failOnNettyLeak=false` downgrades that to a warning), and also when a test fork ran in the module but none wrote the detector's `installed-*.marker`. The forks load the detector when they start (through the JUnit run listener), so a fork whose tests allocate no buffer (a run narrowed with `-Dtest`, or the integration tests failsafe still runs in a `-DskipTests` build) satisfies that check. `-DskipTests` skips surefire only: a jar-only build passes `-DskipTests -DskipITs`, as the release scripts do. See [ByteBuf Leak Detection in Tests](../code/netty-pipeline.md#bytebuf-leak-detection-in-tests). The detector runs at `paranoid` with `-Dio.netty.leakDetection.targetRecords=0`: every buffer is still tracked and a leak still fails the build, but a leak report gives only where the buffer was allocated, not its later accesses. For those, re-run the module with `"-Dmockserver.testArgLine=-Dio.netty.leakDetection.targetRecords=4"`. Under `paranoid`, `CoalescingHttpObjectAggregatorTest` and `CoalescingHttpObjectAggregatorSlicedInputTest` (`mockserver-core`) skip their cases of more than 4,096 body pieces, which run whenever the detector is off.
- **Running tests by invoking the plugin goal directly.** Always pair `failsafe:integration-test` with `failsafe:verify`, for example `./mvnw -pl mockserver-netty failsafe:integration-test failsafe:verify -Dit.test=<Class>` (after a `test-compile` or `install`). The `integration-test` goal does not fail on test failures, errors or a crashed fork: it records them in `target/failsafe-reports/failsafe-summary.xml`, and only `verify` turns that into a build failure, so `integration-test` on its own prints `BUILD SUCCESS` even when the fork never started. It does fail by itself when no test is found.
  - **Two properties are appended to every fork's `argLine`, and they are not interchangeable.** `mockserver.testArgLine` is for the command line (CI's ring-buffer caps, the [port band](#local-development-keep-tests-off-the-os-ephemeral-port-range) above); no pom may set it, because a `-D` on the command line replaces a pom value outright. `mockserver.leakArgLine` carries a module's Netty leak-detector flags (`mockserver-core` and `mockserver-netty` set it to the parent's `${mockserver.nettyLeakDetectorArgLine}`) and comes first, so passing `mockserver.testArgLine` cannot remove the detector (it can still override a single flag, such as the level, by naming it). A surefire or failsafe execution that declares its own `argLine` must end with `${mockserver.leakArgLine} ${mockserver.testArgLine}`. See [ByteBuf Leak Detection in Tests](../code/netty-pipeline.md#bytebuf-leak-detection-in-tests).
  - **Test-fork defaults that the command line may override go in `argLine`, not `systemPropertyVariables`.** Surefire and Failsafe set `systemPropertyVariables` inside the fork after the JVM has started, so they replace a `-D` of the same name in `mockserver.testArgLine`. The test cap `-Dmockserver.maxLogEntries=1000` is therefore in each `argLine`, before `${mockserver.testArgLine}`, where the later `-D` wins: a plain build runs its forks with 1,000 entries, and CI's `-Dmockserver.maxLogEntries=10000` reaches them as 10,000. (While the cap was a `systemPropertyVariables` entry, CI's value never reached a fork.) An execution that declares its own `argLine` must keep the cap.
  - **`argLine` defaults to empty.** The parent pom defines an empty `argLine` property, so the Surefire/Failsafe `@{argLine}` token resolves whether or not JaCoCo's `prepare-agent` has run. A direct `surefire:test` or `failsafe:integration-test` (with or without `-Djacoco.skip=true`) gets a working fork, just without the coverage agent. Before this default, `@{argLine}` reached the JVM literally and the fork died with ``Error: could not open `{argLine}'``.
  - **Zero ITs fails the build in `mockserver-netty` and `mockserver-core`.** Failsafe's `failIfNoTests` is bound to `mockserver.failIfNoIntegrationTests`. The parent pom defaults it to `false`, and these two modules set it to `true`, so a run that finds or executes no integration test fails (`No tests were executed!`), including a `-Dit.test` pattern that matches nothing under `-Dfailsafe.failIfNoSpecifiedTests=false`. A `-Dit.test` pattern that matches nothing already fails by default, through `failIfNoSpecifiedTests`. Failsafe's message suggests `-DfailIfNoTests=false`, but that is overridden here. Use `-Dmockserver.failIfNoIntegrationTests=false` instead.
  - **Stale summary.** When nothing runs, Failsafe does not rewrite `failsafe-summary.xml`, and `failsafe:verify` would read the previous run's result. A lifecycle run (`verify`, `install`) is covered: the parent pom's `delete-stale-failsafe-summary` execution of `maven-clean-plugin` removes the file in `pre-integration-test` in every module, with or without `clean`. A direct goal invocation skips that phase, so name the execution first: `./mvnw -pl mockserver-netty clean:clean@delete-stale-failsafe-summary failsafe:integration-test failsafe:verify -Dit.test=<Class>`. `-Dmaven.clean.skip=true` also skips the deletion.
- **Skipping unit tests to run only ITs.** Pass `-Dtest=<a name that matches nothing> -Dsurefire.failIfNoSpecifiedTests=false`. Once `-Dtest` is set, Surefire ignores `-DfailIfNoTests=false` and fails with `No tests matching pattern` unless `surefire.failIfNoSpecifiedTests` is false. A multi-module run (`-am`, or the whole reactor as in `scripts/local_single_test.sh`) applies the same `-Dit.test` to every module, so it also needs `-Dfailsafe.failIfNoSpecifiedTests=false -Dmockserver.failIfNoIntegrationTests=false` for the modules where the pattern matches nothing. With those off, a pattern that matches nowhere passes, so check that the IT ran (`local_single_test.sh` fails when no Failsafe report was written).
- **Parallel unit tests (`mockserver-core`):** Surefire runs in two phases — a parallel phase (`parallel=classes`, `threadCount=4`) for the bulk of the suite, and a `sequential-tests` execution (`parallel=none`) for the classes that mutate JVM-global state (`ConfigurationProperties` system properties, the static Prometheus `Metrics` registry, the controllable clock, or globally-fixed time). A third execution, `allocation-tests-without-c2`, runs the allocation-bound classes it names with `<test>` (`HttpStateRetrieveAllocationTest`, the `*CostTest` classes, `DataPlaneHooksAbsentCostTest` and others) again in its own fork with C1 only (`-XX:TieredStopAtLevel=1`), so an allocation bound that holds only once C2's escape analysis has compiled the code fails every run; the classes stay in the parallel phase too. `mockserver-netty` has an execution of the same name and shape for `NettyDataPlaneHooksAbsentCostTest`, which pins the bytes and fields of each Netty and HTTP/3 response writer. Two guard tests enforce the parallel/sequential split:
  - `ParallelStaticStateGuardTest` (`mockserver-core/src/test/java/org/mockserver/testing/ParallelStaticStateGuardTest.java`) fails the build if the parallel-phase `<excludes>` and the sequential-tests `<includes>` in `mockserver-core/pom.xml` are not the exact same set — preventing the silent "class runs twice or never" failure.
  - `GlobalStateMutationGuardTest` (`mockserver-core/src/test/java/org/mockserver/testing/GlobalStateMutationGuardTest.java`) scans every test source file for mutation-pattern calls (`ConfigurationProperties.<setter>`, `System.setProperty`, singleton `.reset()`/`.clear()`/`.register()`, Prometheus registry access, `ExpectationStoreFactory`/`StateBackendFactory` `register*`/`resetToDefault`, `SemanticMatching.install`/`clear`, `Metrics.register*`/`unregister*`, and any static `Type.set*(...)` setter) and fails the build if the class is not listed in the sequential phase. To suppress a genuine false positive, add a class-level comment `// @ParallelStateGuardSuppress: <reason>` — the guard skips any file containing that marker.

  **Rule:** any new test that mutates JVM-global static state must be added to BOTH the Surefire `<excludes>` (parallel phase) AND the `<includes>` (sequential-tests execution) in `mockserver-core/pom.xml`. See [performance-tuning.md](performance-tuning.md) for the rationale.

  **`-Dtest` defeats the split.** Surefire's `test` parameter replaces the `<includes>` *and* the `<excludes>` of **every** execution, so `./mvnw test -pl mockserver-core -Dtest=<Class>` runs the named class in both phases — a sequential-phase class then also runs in the parallel phase and reports failures that are artefacts of the wrong phase (and the clean second run is what the tail of the log shows). Verify a sequential-phase class with a full-module run, or with `./mvnw -o -pl mockserver-core test-compile surefire:test@sequential-tests -Dtest=<Class>` which runs that execution only. `allocation-tests-without-c2` names its class with `<test>`, which `-Dtest` does not replace, so a `-Dtest` run of the `test` phase also runs `HttpStateRetrieveAllocationTest` under C1 (about 35 more tests). See `.opencode/rules/testing-policy.md` → "Quarantined (Sequential-Phase) Tests".

- **Troubleshooting — parallel-phase build hangs to ~1800s with zero test failures:** the `forkedProcessTimeoutInSeconds=1800` in `mockserver/pom.xml` (both Surefire and Failsafe) is the hard kill for a hung ForkedBooter JVM. `mockserver-netty` raises it to 2,400 s for its Failsafe fork only: in a local `verify` all its integration tests share that one fork, which under the paranoid leak detector takes about 1,500 to 1,600 s on a CI agent. CI splits them across [three shard steps](../infrastructure/ci-cd.md#step-3b-netty-integration-test-shards), one fork each. When `mockserver-core` unit tests hang to exactly that limit with no reported failures, the most common cause is **GraalJS interpreter-only CPU starvation**: under parallel CPU contention, GraalJS runs in interpreter mode and a JS template parse/execute can spin for minutes rather than seconds — the ForkedBooter looks alive (consuming CPU) but makes no test progress. This is NOT a deadlock and is usually NOT caused by the commit under investigation. Diagnose with `jstack <ForkedBooter-PID>` — a GraalJS hang shows deep Truffle/polyglot frames, not a Java lock cycle. Mitigations already in place: `StreamTemplateRendererTest` is moved to the sequential phase (pom comment at that exclude); `JavaScriptTemplateEngineTest` disables the production execution timeout (`javascriptTemplateExecutionTimeout(0L)`) to prevent premature kills. Keep JS test loops small and avoid per-test GraalJS context creation.
- **Log level:** `mockserver.logLevel=ERROR` during tests
- **Locale:** Forced to `en-GB` (`-Duser.language=en -Duser.country=GB`)
- **Test listener:** `org.mockserver.test.PrintOutCurrentTestRunListener` for progress output

## Packaging Outputs

The `mockserver-netty` module produces multiple artifacts:

```mermaid
graph TD
    MN[mockserver-netty]
    MN --> JAR[Standard JAR]
    MN --> FAT["jar-with-dependencies
Fat JAR with all deps"]
    MN --> SHADED["Shaded JAR
Relocated packages"]
    MN --> BREW["brew-tar
Homebrew tarball"]
    MN --> DEB["Debian Package
.deb with init.d"]
```

| Artifact | Classifier | Description |
|----------|-----------|-------------|
| Standard JAR | (none) | Module classes only |
| Fat JAR | `jar-with-dependencies` | All dependencies bundled (used by Docker) |
| Shaded JAR | `shaded` | Dependencies relocated to avoid conflicts |
| Homebrew tarball | `brew-tar` | Tarball for Homebrew formula |
| Debian package | (none) | `.deb` with SysV init.d and Upstart configs |

#### Gotcha: `jar-with-dependencies` bundles `mockserver-core` from `~/.m2`, not from `target/`

The fat JAR is assembled by `maven-assembly-plugin` (`assembly:single`) using the descriptor at `mockserver-netty/src/main/assembly/jar-with-dependencies/jar-with-dependencies.xml`. The descriptor uses `<dependencySets>` with `<scope>runtime</scope>` and `<scope>provided</scope>` — Maven resolves these from the **local repository (`~/.m2`)**, not from sibling modules' `target/` directories.

**Consequence:** if you edit `mockserver-core` source and then run `mvn -pl mockserver-netty package` without first installing the updated core, the fat JAR will silently bundle the **old** `mockserver-core` from `~/.m2`. The symptom is that your core change has no effect when running the fat JAR (including in Docker), even though the class files in `mockserver-core/target/` are up to date.

**Fix:** install core first, then build the fat JAR:

```bash
cd mockserver
mvn -o -pl mockserver-core install   # or without -o if offline deps are missing
mvn -pl mockserver-netty package
```

A full reactor build (`mvn clean install`) is unaffected because Maven processes modules in dependency order and installs each before moving to the next.

## Distribution

Artifacts are published to:

- **Central Portal** (snapshots): `https://central.sonatype.com/repository/maven-snapshots/`
- **Maven Central** (releases): via Central Portal at `https://central.sonatype.com/repository/maven-releases/`

GPG signing is required for releases (configured in the `release` profile).

### The build never *resolves* from the snapshot repository

`central.sonatype.com/repository/maven-snapshots` is a **publish-only** destination for this
repository. It is declared in `mockserver/pom.xml` `<distributionManagement>` (plus the matching
`central-portal` credentials in `mockserver/.buildkite-settings.xml`) and **nowhere else**.

Do not add it as a `<repository>`/`<pluginRepository>` — in a POM, in a maven-invoker `settings.xml`,
in `docker_build/maven/settings.xml`, or as a Gradle `maven { url ... }`. Every `-SNAPSHOT` the build
consumes is one it produces, and no third-party SNAPSHOT dependency exists anywhere in the reactor, so
the repository can only ever return *our own* artifacts from a previous CI build.

Maven resolves a `-SNAPSHOT` by merging `maven-metadata.xml` from every configured repository and taking
the newest `lastUpdated`. Two failure modes follow:

| Failure mode | When it fires | Symptom |
|---|---|---|
| **False red** | Whenever the remote copy is chosen and cannot be fetched | Sonatype outage, or a corporate TLS-inspection proxy holding a `.jar` for sandbox analysis and answering `403`. The reactor fails in whatever module happened to trigger the refresh — typically long after that module's own tests passed, so it reads as a test failure. |
| **False green** | Whenever the **locally installed** snapshot is **older** than the last published one | The remote wins and an integration test verifies a *previously published* build rather than the code under test. Routine on a developer machine, and in any tree whose upstream modules have not been rebuilt. |

Both are intermittent because Maven's default snapshot `updatePolicy` is `daily`: a build only re-checks
the remote once the interval has elapsed, or when `-U` is passed.

The false-green mode needs a *stale* local install, so CI's normal ordering avoids it: `java-build.sh`
runs `./mvnw -T 1C clean install` over the whole reactor before any module reaches `integration-test`,
`invoker:install` stages the module's own artifacts before `invoker:run`, and `java-deploy-snapshot`
runs after the build behind a `wait` in `.buildkite/pipeline-java.yml` — so the newest remote snapshot
during a build is always from a *previous* build and loses to the just-installed copy. Declaring the
repository was still a latent hazard there: it applied to every Maven process in the CI image (see
[CI/CD → Build Docker Image](../infrastructure/ci-cd.md#build-docker-image)) and would take effect the
moment a partial reactor was built or the ordering changed.

Reproduce/verify the difference with the maven-invoker test that first exposed it — `-Dinvoker.updateSnapshots=true`
forces the child build to refresh:

```bash
cd mockserver
./mvnw -pl mockserver-netty invoker:run -Dinvoker.updateSnapshots=true
```

Note that `-Dinvoker.settingsFile=...` does **not** override the `<settingsFile>` configured in the POM,
so A/B testing this requires editing `mockserver-netty/src/integration-tests/settings.xml` itself.

#### Consequence: build the reactor with `install`, not `verify`

With no remote fallback, the maven-invoker and Gradle integration tests can only get MockServer
artifacts from the local repository, so on a clean `~/.m2` the reactor must be built with `install`.
`./mvnw verify` on a fresh clone fails inside a **child** Maven process with an opaque
`Could not resolve … org.mock-server:mockserver-integration-testing:<version>-SNAPSHOT` and no remote
repository in the log to explain it — the fix is `./mvnw install` (or `-DskipTests` first). This was
previously masked by a silent remote fallback that fetched the last published snapshot instead.

Consuming MockServer snapshots from *outside* this repository is a different thing and is unaffected —
see `README.md` and `jekyll-www.mock-server.com/where/maven_central.html` for the repository declaration
users should add.

## Performance regression pipeline — local runs

`mockserver-performance-test/k6/regression.js` and `growth.js` can be run locally against the compose stack in `mockserver-performance-test/stack/`. The `forward` behaviour requires a dedicated upstream MockServer because it proxies to a separate instance (not itself). Use `K6_FORWARD_SELF=true` to skip the upstream requirement on a single-container local smoke run.

```bash
# Start the compose stack (includes mockserver + mockserver-upstream)
cd mockserver-performance-test/stack
docker compose up -d

# Run regression over HTTP (default)
k6 run \
  -e BASE_URL=http://localhost:1080 \
  -e K6_RESULT_PATH=/tmp/perf-result.json \
  mockserver-performance-test/k6/regression.js

# Run regression over HTTPS + HTTP/2
k6 run \
  -e BASE_URL=https://localhost:1080 \
  -e PROTO=https_h2 \
  -e K6_RESULT_PATH=/tmp/perf-result-h2.json \
  mockserver-performance-test/k6/regression.js

# Run growth (fills maxLogEntries, measures latency slope)
k6 run \
  -e BASE_URL=http://localhost:1080 \
  mockserver-performance-test/k6/growth.js

# Single-container smoke (no upstream needed)
k6 run \
  -e K6_FORWARD_SELF=true \
  -e BASE_URL=http://localhost:1080 \
  mockserver-performance-test/k6/regression.js
```

The upstream container is named `mockserver-upstream` and listens on port 1080 inside the stack network (`FORWARD_UPSTREAM_HOST` defaults to `mockserver-upstream:1080`). For local runs where k6 is on the host, set `FORWARD_UPSTREAM_HOST=localhost:<exposed-port>` or use `K6_FORWARD_SELF=true`.

### Result JSON schema

The pipeline compare step (`perf-test-compare.sh`) merges two artifacts and persists the result to S3. The schemas are:

**`perf-result.json`**
```json
{
  "metadata": { "sha": "...", "branch": "...", "timestamp": "..." },
  "behaviours": {
    "<op>_<proto>": { "p50_ms": 0, "p95_ms": 0, "p99_ms": 0, "throughput_rps": 0, "error_rate": 0 }
  },
  "growth": {
    "cpu_peak": 0, "heap_start": 0, "heap_end": 0, "heap_peak": 0, "heap_ratio": 0,
    "gc_seconds_delta": 0, "threads_peak": 0, "p95_start": 0, "p95_end": 0, "p95_ratio": 0
  }
}
```

**`perf-microbench.json`**
```json
{
  "microbench": {
    "<matcherType>_<count>_detailed": { "time_per_op": 0, "time_per_op_jmh_mean": 0, "time_unit": "us/op", "alloc_bytes_per_op": 0 }
  }
}
```

`time_per_op` is the trimmed mean of the measured iterations (the gated score); `time_per_op_jmh_mean` is JMH's own mean, recorded only. See [performance-measurement.md](../code/performance-measurement.md#how-time_per_op-is-scored-a-trimmed-mean-not-jmhs-mean).

`<op>_<proto>` keys are `match_http`, `forward_http`, `template_http`, `large_http`, and their `_https_h2` counterparts. The merged run is stored at `s3://mockserver-ci-perf-results/runs/<branch>/<iso>__<sha>.json`.
