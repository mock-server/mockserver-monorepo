# TLS, Certificates & Security

## TLS Architecture

MockServer dynamically generates TLS certificates using BouncyCastle, enabling transparent HTTPS interception without pre-configured certificates.

```mermaid
graph TB
    subgraph "Certificate Generation"
        KCF["KeyAndCertificateFactory
Interface"]
        BCF["BCKeyAndCertificateFactory
BouncyCastle implementation"]
        KCF -.-> BCF
    end

    subgraph "SSL Context"
        NSCF["NettySslContextFactory
Creates & caches SslContext"]
        KSF["KeyStoreFactory
JKS KeyStore + SSLContext"]
    end

    subgraph "Netty Pipeline"
        SNI["SniHandler
SNI extraction + ALPN"]
        SSL["SslHandler
Netty built-in"]
    end

    BCF -->|provides certs| NSCF
    NSCF -->|server SslContext| SNI
    SNI -->|replaces self| SSL
    BCF -->|provides certs| KSF
```

### Dynamic Certificate Generation

When a TLS connection arrives:

```mermaid
sequenceDiagram
    participant C as Client
    participant SNI as SniHandler
    participant BCF as BCKeyAndCertificateFactory
    participant NSCF as NettySslContextFactory

    C->>SNI: TLS ClientHello (SNI: api.example.com)
    SNI->>SNI: Extract hostname from SNI extension
    SNI->>BCF: Add SAN: api.example.com
    BCF->>BCF: Generate leaf certificate signed by MockServer CA
    BCF-->>NSCF: Private key + certificate chain
    NSCF->>NSCF: Build SslContext (server-side, with ALPN)
    NSCF-->>SNI: SslContext
    SNI->>SNI: Replace self with SslHandler
    SNI->>SNI: Store ALPN result on channel (HTTP_1_1 or HTTP_2)
    SNI-->>C: TLS ServerHello + Certificate
```

### Certificate Authority

MockServer maintains an in-memory CA with default DN:
- **CN**: `www.mockserver.com`
- **O**: `MockServer`
- **L**: `London`
- **ST**: `England`
- **C**: `UK`

**Certificate validity periods** (CA and leaf are now split):

| Certificate | Validity | Anchored to | Constant / property |
|-------------|----------|-------------|---------------------|
| Dynamically-generated **CA** | **10 years** (3655 days including the 5-day back-dated `notBefore`) | issuance of the CA | `KeyAndCertificateFactory.CERTIFICATE_VALIDITY_YEARS = 10` |
| **Leaf** (server) certificate | **397 days** by default (total window `notAfter - notBefore`) | its own last (re)generation, *not* server startup | `sslCertificateLeafValidityInDays` (default `KeyAndCertificateFactory.LEAF_CERTIFICATE_VALIDITY_DAYS_DEFAULT = 397`) |
| HTTP/3 legacy echo-mode self-signed fallback | **10 years** | issuance | `KeyAndCertificateFactory.CERTIFICATE_VALIDITY_YEARS` |

The CA is the trust anchor users pin into their trust stores, so it keeps a long life. The **leaf was shortened to 397 days** so it stays inside Apple's **825-day** maximum for TLS server certificates (iOS 13 / macOS 10.15, [support.apple.com/en-us/103769](https://support.apple.com/en-us/103769)) — the old 10-year leaf blew straight through that cap and is the likely cause of TLS handshake failures on Apple platforms (issue #2531). Note the operative rule here is the 825-day server-certificate cap, **not** Apple's better-known 398-day ATS limit: ATS explicitly exempts certificates issued from user-added / administrator-added roots, which is exactly MockServer's dynamically generated CA, so ATS does not apply. 397 days also sits inside the CA/Browser Forum's tightening trend.

The HTTP/3 legacy echo-mode self-signed fallback is simultaneously trust anchor *and* server certificate with no renewal loop behind it, so it deliberately keeps the long CA-style validity rather than the short leaf validity — a short-lived self-signed anchor with nothing to renew it would simply expire the echo endpoint.

To restore the previous long-lived leaf, set `mockserver.sslCertificateLeafValidityInDays` (e.g. `3650`). A non-positive value falls back to the default.

**Leaf certificate extensions.** The generated leaf carries a `serverAuth` + `clientAuth` `extendedKeyUsage` (Apple requires `serverAuth` on the leaf independently of validity — without it iOS/macOS reject the certificate even with a compliant validity period), a critical `keyUsage` of `digitalSignature | keyEncipherment`, an `authorityKeyIdentifier` derived from the CA (so chain builders that match a leaf's AKI to the issuer's `subjectKeyIdentifier` can find the CA), the subject alternative names, and a positive serial number (RFC 5280 §4.1.2.2). The root CA carries **no** `extendedKeyUsage` — EKU on a trust anchor is non-idiomatic and only newly-generated CAs are affected (an existing dynamic CA on disk is never regenerated).

**Proactive leaf renewal (self-healing cache).** The dynamic TLS certificate cache is expiry-aware: the leaf is proactively regenerated once **80% of its validity window has elapsed** (`KeyAndCertificateFactory.RENEWAL_ELAPSED_FRACTION = 0.8`), which for the 397-day default is ~318 days after issuance. A renewed leaf is minted with a fresh full validity window anchored to its new `notBefore`, so renewal happens once per window rather than thrashing on every handshake. This is what makes the short leaf safe: a long-running server never keeps serving an expired leaf out of its cached `SslContext`. A dynamically-generated **CA** nearing its own expiry is *warned about once* rather than auto-rotated — silently rotating a CA would invalidate every client trust store that imported it, so rotation is left to the operator. User-supplied fixed certificates never self-renew; they are validated (and loudly rejected on expiry) by `CertificateConfigurationValidator`.

**Bounded dynamic SAN list (`maxSubjectAlternativeNames`).** MockServer adds a Subject Alternative Name for every distinct SNI hostname and `Host` header it observes and re-mints the leaf. Without a cap a hostile client could grow the SAN list without bound (a memory/CPU denial-of-service, and eventually a certificate too large to fit a handshake). `maxSubjectAlternativeNames` (default `100`) caps the retained **dynamically-discovered** entries; eviction is FIFO and configured/default SANs (e.g. `localhost`, anything set via `sslSubjectAlternativeNameDomains` / `sslSubjectAlternativeNameIps`) are never evicted. Hostnames are normalised and validated before being added, and an eviction logs a WARN.

Custom CA certificates can be loaded from PEM files via configuration.

**Clustered / multi-replica deployments must NOT use dynamic CA generation.** The CA is node-local — it is generated per node and is not shared or replicated by the `StateBackend`. With `dynamicallyCreateCertificateAuthorityCertificate=true`, every node in a cluster mints its own distinct CA, so a client that trusts one node's `mockserver-ca.pem` gets a TLS validation failure when a load balancer routes it to a different node (an intermittent trust error — the intermittency is the tell). Instead, supply **one shared CA** to every node via `certificateAuthorityCertificate` + `certificateAuthorityPrivateKey` and set `dynamicallyCreateCertificateAuthorityCertificate=false`. `StateBackendFactory.create()` logs a WARN when it detects `clusterEnabled=true` together with dynamic CA generation. See [docs/code/clustered-state.md → Per-Node Dynamic CA (TLS Trust)](clustered-state.md#per-node-dynamic-ca-tls-trust).

### Proxy Setup — CA Materialisation and Copy-Paste Block

The `proxySetupLogging` property (env `MOCKSERVER_PROXY_SETUP_LOGGING`, default `false`) gates both the startup CA file write and the "Proxy Setup" log block. The block contains ready-to-paste environment variable exports for both Unix and Windows PowerShell:

```
HTTPS_PROXY=http://localhost:<port>
NODE_EXTRA_CA_CERTS=<ca path>       # Node.js
SSL_CERT_FILE=<ca path>             # Python (httpx, standard library)
REQUESTS_CA_BUNDLE=<ca path>        # Python requests
```

When `proxySetupLogging` is enabled, MockServer writes the active CA certificate to `<directoryToSaveDynamicSSLCertificate>/mockserver-ca.pem` at startup and prints the block. The standalone launcher (executable JAR, Docker image, `mockserver` CLI) automatically enables `proxySetupLogging`, so proxy users see both without any extra configuration. Embedded usage (`new ClientAndServer(...)`) stays silent by default; when `proxySetupLogging` is disabled, the CA file is instead written on the first call to `GET /mockserver/proxyConfiguration`.

The `GET /mockserver/proxyConfiguration` endpoint is always available regardless of `proxySetupLogging`. It returns JSON by default or a plain copy-paste text block with `Accept: text/plain`. It never exposes the private key.

The `--proxy-setup` CLI flag (property `mockserver.proxySetup`, env `MOCKSERVER_PROXY_SETUP`, default `false`) is a convenience switch: when set, it forces `dynamicallyCreateCertificateAuthorityCertificate=true`, generating a unique local CA whose private key never leaves the machine. Without it, MockServer uses the built-in default CA whose private key is published in the git repository — safe only for isolated local development. The startup block includes a security warning when the default public CA is in use.

### Key Classes

| Class | Package | Purpose |
|-------|---------|---------|
| `KeyAndCertificateFactory` | `o.m.socket.tls` | Interface for cert generation |
| `BCKeyAndCertificateFactory` | `o.m.socket.tls.bouncycastle` | BouncyCastle implementation: generates CA + leaf X.509 certs, supports dynamic SANs, reads custom PEM certs (including intermediate chains) |
| `KeyAndCertificateFactoryFactory` | `o.m.socket.tls` | Factory with pluggable supplier |
| `NettySslContextFactory` | `o.m.socket.tls` | Creates and caches Netty `SslContext` for server and client sides; supports mTLS, HTTP/2 ALPN; throws on failure instead of returning null |
| `CertificateConfigurationValidator` | `o.m.socket.tls` | Validates TLS certificate configuration at startup: key/cert pairing, CA chain verification, expiry, file existence |
| `KeyStoreFactory` | `o.m.socket.tls` | Creates JKS `KeyStore` and `SSLContext` for non-Netty use |
| `SniHandler` | `o.m.socket.tls` | Extends Netty's `AbstractSniHandler`; extracts SNI hostname, provisions certificate, negotiates ALPN |
| `PEMToFile` | `o.m.socket.tls` | PEM format utilities (read/write private keys and X.509 chains); properly closes InputStreams |

### BouncyCastle FIPS Support

MockServer supports both standard BouncyCastle (`bcprov-jdk18on`) and BouncyCastle FIPS (`bc-fips`) as the JCE provider for certificate generation. The provider is selected automatically at runtime:

1. `KeyAndCertificateFactoryFactory.isBouncyCastleAvailable()` checks if `org.bouncycastle.jce.provider.BouncyCastleProvider` is on the classpath
2. If available, `BCKeyAndCertificateFactory` is used; otherwise, MockServer falls back to JDK default crypto

`BCKeyAndCertificateFactory` uses lazy initialization:
- The provider name `"BC"` is hardcoded as a string constant (not referencing `BouncyCastleProvider.PROVIDER_NAME`) to avoid triggering class loading of the provider class at factory construction time
- `ensureProviderRegistered()` is called on first use (synchronized) and registers the provider via `Security.addProvider(new BouncyCastleProvider())`
- This design supports both `bcprov-jdk18on` (standard) and `bc-fips` (FIPS) since both register under the `"BC"` provider name

To use FIPS mode, replace the `bcprov-jdk18on` dependency with `bc-fips` in your classpath. No configuration changes are needed.

### Startup Certificate Validation

When custom TLS certificates are configured (`privateKeyPath` and `x509CertificatePath`), `CertificateConfigurationValidator` runs eagerly during `NettySslContextFactory.createServerSslContext()` and performs these checks:

| Check | Behaviour on Failure |
|-------|---------------------|
| Both `privateKeyPath` and `x509CertificatePath` must be set together | **Hard failure** with message naming both properties |
| Private key file must be valid PEM | **Hard failure** |
| Certificate file must be valid PEM | **Hard failure** |
| Certificate must not be expired or not-yet-valid | **Hard failure** with expiry/notBefore date |
| Private key must match certificate (sign-verify challenge) | **Hard failure** |
| Leaf certificate must be signed by configured CA | **Hard failure** |
| CA certificate file must be valid PEM (when non-default) | **Hard failure** |
| CA private key file must be valid PEM (when non-default) | **Hard failure** |
| Certificate should include `serverAuth` EKU | **WARN log** (not hard failure) |

Validation only runs when custom certs are provided. Default/auto-generated certificate deployments are unaffected.

### Intermediate CA Chain Support

When `x509CertificatePath` contains multiple PEM-encoded certificates, `BCKeyAndCertificateFactory` loads the full chain using `PEMToFile.x509ChainFromPEMFile()`. The first certificate is the leaf; subsequent certificates are intermediates. The full chain `[leaf, intermediate₁, ..., intermediateₙ, CA]` is sent during TLS handshake.

### SSL Context Caching

`NettySslContextFactory` caches `SslContext` objects to avoid regenerating them for every connection. It creates separate contexts for:
- **Server-side**: For accepting client connections (with the dynamically-generated certificate)
- **Client-side**: For forwarding to upstream servers (with configurable trust)

### Forward Proxy Trust

When forwarding requests, MockServer's `NettyHttpClient` needs to trust upstream servers. Three modes are supported via `ForwardProxyTLSX509CertificatesTrustManager`:

| Mode | Behaviour |
|------|-----------|
| `ANY` | Trust all certificates and do **not** verify the host name (insecure, the default, useful for testing) |
| `JVM` | Use the JVM's default truststore, **and** verify the upstream host name |
| `CUSTOM` | Use a custom CA chain from configuration, **and** verify the upstream host name |

**Outbound host name verification (Wave 3).** For the validating trust managers (`JVM` / `CUSTOM`), chain validation alone is not enough: a certificate signed by a trusted CA *for the wrong host* would still be accepted, leaving a user who opted into real upstream validation open to a man-in-the-middle. The Netty in use *already* enables RFC 2818 / HTTPS endpoint identification for a **client** context created via `newHandler(host, port)` — but **not** for the no-host `newHandler` overloads (e.g. the original reverse-proxy relay), so verification was inconsistent across outbound paths and, crucially, could not be turned off. MockServer now forces it uniformly at the single chokepoint every outbound path shares: `NettySslContextFactory.createClientSslContext(...)` wraps the built client `SslContext` (for JVM/CUSTOM only) in a `DelegatingSslContext` whose `initEngine` sets `SSLParameters.setEndpointIdentificationAlgorithm(...)` on every engine — to `"HTTPS"` when verification is enabled (covering the no-host paths too), or explicitly to `null` when disabled (so Netty's default cannot leave it on). Every `newHandler`/`newEngine` overload funnels through `initEngine` (the handler overloads via `DelegatingSslContext`'s default `initHandler`, which calls `initEngine(handler.engine())`), so overriding only `initEngine` is sufficient. HTTP/1.1, HTTP/2, the `CONNECT`-tunnelled relay (`RelayConnectHandler`), the reverse-proxy relay (`UpstreamProxyRelayHandler`, which verifies against the **CONNECT target host/port** — not the connected socket address, whose `getHostString()` is the MockServer loopback on the common forward-proxy path and only coincidentally the target on a reverse-proxy path), the websocket relay and the LLM forward paths are all covered uniformly. `ANY` is never wrapped (its insecure trust manager makes endpoint identification a no-op anyway), so it is left exactly as-is. `forwardProxyTLSHostnameVerificationEnabled` (default `true`) turns off just the host-name check while keeping chain validation, for the legitimate case of an upstream whose certificate host name does not match the address connected to; it is folded into the client `SslContext` cache key so a runtime change self-invalidates.

**Bundled-CA warning (Wave 3).** MockServer ships a default CA *and its private key* in the jar. When that bundled CA is the trust anchor signing served traffic (dynamic CA generation off, no fixed leaf supplied, default CA paths), `NettySslContextFactory` logs a single startup WARN (once per JVM) naming the two fixes — `dynamicallyCreateCertificateAuthorityCertificate=true` or `--proxy-setup`. Shipping the key is intentional and the default is unchanged; the warning only makes the trade-off visible.

**Fixed-certificate re-check (Wave 3).** Self-generated leaves self-renew (see *SSL Context Caching*), but a user-supplied fixed leaf was previously validated only at startup and then pinned into the cached context. `createServerSslContext` now re-checks it on a cheap, time-bounded schedule (at most once a minute, `stat` only — never a per-handshake re-parse): a certificate rotated in place on disk (changed mtime/length) forces a rebuild that re-runs `CertificateConfigurationValidator` and picks up the replacement, while an unchanged-but-expired certificate is surfaced with a single WARN rather than served silently.

**Control-plane TLS-posture audit (Wave 3).** `HttpState.warnIfLoweringTlsPosture(...)` runs on `PUT /mockserver/configuration` (before the DTO is applied, while the old values are still readable) and logs a single audit WARN when the change downgrades the forward-proxy trust manager to `ANY`, turns off `tlsMutualAuthenticationRequired` or `forwardProxyTLSHostnameVerificationEnabled`, or repoints the TLS key/certificate/CA paths. It audits, it does not block (control-plane auth is off by default, so the downgrade would otherwise be silent).

### Per-Host Outbound mTLS

Outbound client authentication (mTLS to the upstream) is global by default: the single
`forwardProxyPrivateKey` / `forwardProxyCertificateChain` pair (or MockServer's own generated key/cert) is
presented to every upstream. `forwardProxyClientCertificatesByHost` adds a per-host override — a
comma-separated list of `host=certificateChainPath;privateKeyPath` entries. When MockServer opens an outbound
TLS connection whose target host matches an entry (case-insensitive), that host's cert/key pair is presented;
any host without an entry falls back to the global pair. The default is empty (global pair only, unchanged).

Selection and caching live in `NettySslContextFactory.createClientSslContext(forwardProxyClient, enableHttp2, host)`:

- The pure, unit-tested resolver `resolveForwardProxyClientCertificate(mapping, host)` parses the map and returns
  the matching `[certificateChainPath, privateKeyPath]` (or `null` to fall back). Malformed entries (missing `=`
  or `;`, or a blank cert/key) are skipped.
- The `SslContext` cache is keyed by host **only for hosts that have a mapping**; every unmapped host shares one
  global-pair context, so a forward proxy that sees many upstream hosts cannot grow the cache without bound.
- The target host is taken from the upstream socket (`REMOTE_SOCKET`) at pipeline-init time in
  `HttpClientInitializer`, the same host already used for SNI. The `CONNECT`-tunnel loopback
  (`RelayConnectHandler`) is MockServer talking to itself, not an upstream, so it keeps the global pair.

### Forward Target SSRF Validation

When `forwardProxyBlockPrivateNetworks` is `true` (default `false`), MockServer validates the target host before opening any outbound connection. `InetAddressValidator.validateForwardTarget` resolves the hostname and rejects addresses in these ranges:

| Blocked range | Reason |
|--------------|--------|
| `169.254.169.254` / `fd00:ec2::254` | Cloud instance metadata (AWS/GCP/Azure/Oracle) |
| Loopback (`127.0.0.0/8`, `::1`) | Localhost |
| Link-local (`169.254.0.0/16`, `fe80::/10`) | Link-local |
| RFC 1918 private / RFC 4193 unique-local (`fc00::/7`) | Private network |
| Wildcard / any-local | Bind-all addresses |

The validation runs in `HttpForwardActionHandler`, `HttpForwardValidateActionHandler`, `HttpForwardWithFallbackActionHandler`, `HttpForwardTemplateActionHandler`, and several `HttpState` call sites that accept an explicit forward target. After validation, the address is passed as an **unresolved** `InetSocketAddress` (`InetSocketAddress.createUnresolved`), so the actual DNS lookup happens on the Netty event loop rather than the calling thread — this guards against a TOCTOU window between validation and connection.

The feature is opt-in because MockServer is most commonly used to mock services running on localhost, Docker bridge networks, or Kubernetes service IPs, where blocking private addresses would prevent normal usage.

## Mutual TLS (mTLS)

MockServer supports mTLS for both incoming connections and the control plane.

A complete end-to-end mTLS example with self-generated certificates, Docker Compose configuration, and curl commands is available in [`examples/docker-compose/docker_compose_with_mtls/`](../../examples/docker-compose/docker_compose_with_mtls/). The consumer-facing documentation is on the [HTTPS & TLS page](https://www.mock-server.com/mock_server/HTTPS_TLS.html#mtls_examples).

### Incoming Connection mTLS

When `tlsMutualAuthenticationRequired` is configured, `PortUnificationHandler` checks for TLS on the channel. If the connection is not TLS, it returns **426 Upgrade Required** and disconnects.

Client certificates are extracted from the SSL session via `SniHandler.retrieveClientCertificates()` and stored as a channel attribute (`UPSTREAM_CLIENT_CERTIFICATES`), then mapped onto the request's `clientCertificateChain` by `JDKCertificateToMockServerX509Certificate` (the same path is used for HTTP/3 — see [http3.md](http3.md)). On the gRPC-bidi HTTP/2 multiplex pipeline every stream is a child channel that does not inherit this attribute from the connection channel, so `ConnectionScopeHandler` copies `UPSTREAM_CLIENT_CERTIFICATES` (and `UPSTREAM_SSL_ENGINE`) onto each child; without it a live `retrieveClientCertificates()` read on a stream (e.g. MCP control-plane auth) would see no certificate — see [netty-pipeline.md](netty-pipeline.md) (GitHub issue #2669).

### Matching Expectations on the Client Certificate

Beyond authentication, an expectation can **match** on the presented client-certificate chain via the
`clientCertificate` request matcher (`ClientCertificateMatcher`, `org.mockserver.matchers`). Matching is
performed against the **leaf certificate** (index `0` — the client's own certificate) of the request's
`clientCertificateChain`:

- `subject` — leaf Common Name, full subject Distinguished Name, or any Subject Alternative Name.
- `issuer` — leaf issuer Common Name or full issuer Distinguished Name.
- `fingerprintSha256` — SHA-256 fingerprint of the leaf's DER encoding (colons/whitespace and case ignored).

Each criterion is a `NottableString` (regex / `!` negation / optional). This is **matching only** — it does
not perform or replace mTLS authentication (`MTLSAuthenticationHandler`, below), which independently
validates the chain against the configured trust store. A non-blank `clientCertificate` criterion never
matches a request that presented no client certificate. See
[domain-model.md](domain-model.md) for the field table.

### Control Plane mTLS

Control plane endpoints (`/mockserver/expectation`, `/mockserver/verify`, etc.) can require mTLS authentication. When configured, `HttpState.controlPlaneRequestAuthenticated()` delegates to `MTLSAuthenticationHandler`, which validates the presented client-certificate chain against the configured trust store (`controlPlaneTLSMutualAuthenticationCAChain`).

For each presented certificate, paired with each configured CA, the handler:

| Check | How | Failure |
|-------|-----|---------|
| PKIX path validation | Builds a single-certificate `CertPath` for the presented certificate and validates it against that CA as the sole `TrustAnchor` (`CertPathValidator` "PKIX", `setRevocationEnabled(false)`). This performs signature verification, validity-window (`notBefore`/`notAfter`) enforcement and basic X.509 path processing in one JDK-audited step. | Rejected (unknown issuer, bad signature, expired/not-yet-valid) |
| Extended Key Usage | If the presented certificate carries an EKU extension it must include `clientAuth` (`id-kp-clientAuth` `1.3.6.1.5.5.7.3.2`) or `anyExtendedKeyUsage` (`2.5.29.37.0`). A certificate with **no** EKU extension is unrestricted and is allowed (RFC 5280 practice). | Rejected when EKU present but lacks clientAuth |

A presented certificate authenticates if any (certificate, CA) pair passes both checks; otherwise the handler throws `AuthenticationException`. Revocation (CRL/OCSP) is intentionally disabled so validation never makes a network call, consistent with the rest of the codebase. Because a certificate with no EKU is accepted, existing client certificates (including those that carry `serverAuth`+`clientAuth`, as MockServer's own generated certificates do) keep working unchanged.

## Control Plane Authentication

```mermaid
flowchart TD
    REQ([Control Plane Request]) --> AUTH{"Authentication
configured?"}
    AUTH -->|No| ALLOW([Proceed])
    AUTH -->|Yes| TYPE{Auth type?}
    TYPE -->|mTLS| MTLS["Validate client
certificate chain"]
    TYPE -->|JWT| JWT["Validate Bearer token
nimbus-jose-jwt"]
    TYPE -->|Both| CHAIN["mTLS AND JWT
both must pass"]
    MTLS -->|Pass| ALLOW
    MTLS -->|Fail| DENY([401/403])
    JWT -->|Pass| ALLOW
    JWT -->|Fail| DENY
    CHAIN -->|Both pass| ALLOW
    CHAIN -->|Either fails| DENY
```

Authentication is **derived from the live `Configuration` on every request** by `ControlPlaneAuthenticationHandlerFactory.build()` (cached against `signature(Configuration)` and rebuilt whenever an auth-relevant value changes) and validated in `HttpState.controlPlaneRequestAuthenticated()`. It is no longer built once during `MockServer.createServerBootstrap()` and pushed into `HttpState` — see [Runtime Mutability of the Control-Plane Trust Anchor](#runtime-mutability-of-the-control-plane-trust-anchor) for what that implies.

| Configuration | Handler | Mechanism |
|---------------|---------|-----------|
| `controlPlaneTLSMutualAuthenticationCAChain` | mTLS handler | Validates client cert against CA chain |
| `controlPlaneJWTAuthenticationJWKSource` | JWT handler | Validates Bearer token using JWK source |
| `controlPlaneOidcAuthenticationRequired` | OIDC handler | Verifies an external-IdP Bearer token (issuer + audience + scopes) and surfaces a verified principal |
| Two or more configured | Chained handler | Every configured handler must succeed (logical AND) |

### Runtime Mutability of the Control-Plane Trust Anchor

**The control-plane trust anchor is NOT frozen at startup. It is mutable at runtime, including through the process-global static `ConfigurationProperties` store.** Pinning a CA chain, JWKS source or issuer before starting a server does not fix it for that server's lifetime.

This is intended: it is what makes enabling control-plane authentication on an already-running instance actually take effect, rather than being accepted and silently ignored (the defect fixed in `efc0d256c`, where the handler was built once at bootstrap so every later configuration route returned success and left the handler `null` — and a `null` handler means "authenticated"). The trade-off is deliberate but it is a genuine widening versus immutable-after-bootstrap, so it needs to be understood.

```mermaid
flowchart LR
    SYS["System property
-Dmockserver.controlPlane*"] --> STATIC["ConfigurationProperties
process-global static store"]
    PUT["PUT /mockserver/configuration"] --> INST
    SETTER["Configuration setter
on the server's instance"] --> INST["Configuration instance"]
    STATIC -->|"read-through when the
instance field is unset"| INST
    INST --> FACTORY["ControlPlaneAuthenticationHandlerFactory
build() keyed by signature()"]
    FACTORY --> GATE["Enforcement point
every control-plane request"]
```

Consequences to be aware of:

- **A `Configuration` reads through to the static store for any field it has not set itself.** So a server started with an unset `controlPlaneTLSMutualAuthenticationCAChain` on its own instance will follow *later* mutations of the global store — the running server's trust anchor changes underneath it, with no restart and no log line announcing a trust change.
- **Any route can do it**: a system property, a `Configuration` setter, `PUT /mockserver/configuration`, or unrelated code in the same process. The handler is rebuilt as soon as `signature(Configuration)` changes.
- **Cross-test contamination hazard in a shared JVM.** Two tests in one JVM share the static store, so one test's control-plane configuration can re-point another's running server. This is not hypothetical: `AuthenticatedControlPlaneUsingMTLSClientNotAuthenticatedIntegrationTest` used to set the global CA chain to `ca.pem`, start a server, then rewrite the global store to a *different* CA purely to build a differently-signed client — which, once the handler became live-derived, re-pointed the running server's trust anchor at the very CA that signed the supposedly-unauthorised client, and it authenticated. **Tests (and any embedded use) that need a fixed trust anchor must pin it on the server's own `Configuration` instance and must not use the global store as a client-config vehicle.**

To pin a trust anchor that cannot be moved by unrelated code, set it on the server's own `Configuration` instance and start the server with it:

```java
Configuration serverConfiguration = configuration()
    .controlPlaneTLSMutualAuthenticationCAChain("path/to/ca.pem")
    .controlPlaneTLSMutualAuthenticationRequired(true);
ClientAndServer server = ClientAndServer.startClientAndServer(serverConfiguration);
```

An instance field that has been explicitly set wins over the static store, so this is stable against global mutation. It is still mutable through that instance and through `PUT /mockserver/configuration`; if the control plane is reachable by anyone you do not trust to change its own trust anchor, that endpoint must itself be authenticated (it routes through the same gate).

### Verified OIDC Control-Plane Authentication

`OidcAuthenticationHandler` (`o.m.authentication.oidc`) lets an external OIDC IdP govern the control plane. It is off by default — with no `controlPlaneOidc*` configuration the control plane behaves byte-for-byte as before. When `controlPlaneOidcAuthenticationRequired` is enabled it:

1. extracts the single `Authorization: Bearer <jwt>` access token (missing or non-Bearer → `AuthenticationException` → 401);
2. resolves the IdP JWK set — directly from `controlPlaneOidcJwksUri`, or by fetching `{controlPlaneOidcIssuer}/.well-known/openid-configuration` and reading its `jwks_uri`;
3. verifies the token signature and asserts issuer (`controlPlaneOidcIssuer`), audience (`controlPlaneOidcAudience`), `exp`/`nbf` (60s skew), and that the granted scopes contain every `controlPlaneOidcRequiredScopes` entry. Scopes are read from `controlPlaneOidcScopeClaim` (default `scope`, space-delimited; array claims such as `scp`/`roles`/`groups` are also supported);
4. returns an `AuthenticationResult` carrying the **verified** principal (`sub`), source `verified-oidc`, a redaction-safe claim subset (`sub`/`iss`/`aud`/`scope`/`groups`/`email` — never the raw token) and the normalised scope set.

The verified principal flows into the control-plane audit log (`AuditEntry.principalSource == "verified-oidc"`, `principal == sub`) instead of the unverified best-effort extraction. Wave 1 authenticates only; scope-based authorization/403 enforcement is a later wave.

**Secure-by-default hardening** (the OIDC handler only — the legacy `JWTAuthenticationHandler` is unchanged):

- **Asymmetric algorithms only.** The OIDC validator accepts only asymmetric JWS families (`RS*`, `PS*`, `ES*`, `EdDSA`). HMAC (`HS256/384/512`) and the unsecured `alg=none` are rejected — accepting HMAC against a public JWK set is the classic algorithm-confusion attack (forge an HMAC token using the public key bytes as the shared secret).
- **`exp` required.** A token without an `exp` claim is rejected (nimbus only checks expiry when the claim is present, so without this a no-`exp` token would be valid forever). Real OIDC tokens always carry `exp`.
- **`iss` or `aud` required.** At least one of `controlPlaneOidcIssuer` / `controlPlaneOidcAudience` must be configured. If both are blank the handler **fails construction** (logs an error, leaves the validator null so every request 401s fail-closed) — with neither set, any validly-signed token from the configured JWKS would be accepted regardless of who it was minted for.
- **HTTPS JWKS required.** A remote `controlPlaneOidcJwksUri` / `controlPlaneOidcIssuer` (used for discovery) must use `https://`. Plaintext `http://` is permitted **only** to `localhost`/loopback (local testing); file/classpath JWKS paths are unaffected. An `http://` URL to any other host fails construction (fail-closed), preventing MITM on plaintext key retrieval.
- **Generic 401 body.** On an OIDC authentication failure the client receives a generic `Unauthorized for control plane` body; the detailed reason (expected issuer/audience/scopes, signature failure) is logged **server-side only**. The legacy JWT/mTLS path still echoes its detailed reason to the client (unchanged). This is driven by `AuthenticationException.isClientSafeMessage()` — `false` for OIDC-originated exceptions, `true` (the default) for all others.

### Control-Plane Authorization (claims→scopes)

Authentication answers *who* a caller is; **authorization** answers *what they may do*. On top of verified OIDC authentication, MockServer adds a coarse, hierarchical role model — "RBAC by standards conformance" — that maps a verified principal's scopes/groups to one of three roles and enforces a read/mutate split on the control plane.

Off by default: with `controlPlaneAuthorizationEnabled=false` (the default) no authorization check runs and the control plane behaves byte-for-byte as before (verified authentication only). Authentication is unaffected by this switch.

**Role model.** Roles are strictly hierarchical — `ADMIN ⊇ MUTATE ⊇ READ`:

| Role | Grants |
|------|--------|
| `read` | Read-only control-plane operations (every `GET`, plus the read `PUT`s: `retrieve`, `verify`, `verifySequence`, `verifySLO`, `diff`, `explainUnmatched`, `debugMismatch`, `files/retrieve`, `files/list`) |
| `mutate` | Everything `read` grants **plus** all mutations (creating expectations, clearing, resetting, binding ports, etc.) |
| `admin` | Everything `mutate` grants (ceiling for future admin-only operations; currently a strict superset of `mutate`) |

**Mapping.** `controlPlaneScopeMapping` maps a verified scope/group **value** to a role. Serialized form is a comma-separated list of `value=role` pairs, e.g. `platform-admins=admin,qa-team=mutate,viewers=read`. The scope values come from the same verified scope set as authentication (`controlPlaneOidcScopeClaim` — `scope`/`scp`/`roles`/`groups`). Unrecognised roles and malformed pairs are skipped at parse time so a typo can never silently widen access.

**Enforcement** (in `HttpState.controlPlaneRequestAuthenticated`, after authentication succeeds and before the operation runs):

1. the operation's **required role** is derived from the existing read/mutate split (`isControlPlaneRead` → `READ`; otherwise `MUTATE`);
2. the principal's verified scopes are mapped through `controlPlaneScopeMapping` into its **granted roles**;
3. if no granted role `satisfies` the required role, the request is denied with a generic **`403 Forbidden`** (`Forbidden for control plane`) and an audit entry is recorded with **`outcome=FORBIDDEN`**. The denial detail (granted vs required role) is logged at INFO **server-side only**, so authorization policy is not disclosed to the client.
4. otherwise the operation proceeds and is audited with `outcome=AUTHORIZED` (as before).

**Fail-closed, requires a verified principal.** Authorization maps the *verified* scope set, so it requires control-plane OIDC authentication to be enabled. A principal with no scopes, or whose scopes map to no role, is granted nothing and is **denied every mutation** (and every read unless it has a `READ`-or-higher role). A `read`-only principal passes reads but is `403`'d on mutations; an `admin` principal passes everything. FORBIDDEN denials are always recorded when auditing is enabled, even for reads (unlike AUTHORIZED reads, which honour `controlPlaneAuditReads`).

Classes: `ControlPlaneRole` (enum, `o.m.authentication.authorization`) with `satisfies(required)`; `ControlPlaneAuthorizer` (same package) maps scopes→roles and decides allow/deny.

**Coverage — exactly what authorization protects.** The authorization decision runs in `HttpState.controlPlaneRequestAuthenticated`. Every operation dispatched through `HttpState.handle` is covered: all expectation CRUD (`PUT/POST /expectation`), `clear`, `reset`, retrieve/verify, mode/bind-config, drift/chaos/SLO, replay, contract-test, and so on. A handful of routes are serviced directly in the Netty layer (`HttpRequestHandler`) outside `HttpState.handle`; their coverage is:

| Route | Authn | Authz | Notes |
|-------|-------|-------|-------|
| `PUT /mockserver/configuration` (mutates live config) | yes | **yes** | Routed through the shared `HttpState.controlPlaneRequestAuthenticated` gate, so it takes the same read/mutate authorization as `handle`-dispatched mutations (classified `MUTATE`). A read-only principal is `403`'d; mutate/admin proceed. |
| `GET /mockserver/configuration` | yes | yes | Same gate, classified `READ`. |
| `GET /mockserver/openapi.yaml`, `GET /mockserver/llm/optimisationReport` | yes | yes | Reads; routed through the shared gate. |
| `GET /mockserver/dashboard*` (dashboard SPA + assets) | yes | yes | Routed through the shared `controlPlaneRequestAuthenticated` gate in `HttpRequestHandler`, classified `READ` — the dashboard streams all captured traffic, so a read-only role may view it but an unauthenticated caller is `401`'d. Default (no auth configured) is a no-op: the gate returns true and the dashboard stays open. |
| `/_mockserver_ui_websocket` (dashboard UI WebSocket upgrade) | yes | yes | Gated in `DashboardWebSocketHandler` via the non-writing `HttpState.evaluateControlPlaneAuthentication` (the upgrade must render a raw `401`/`403` handshake rejection, not a MockServer `HttpResponse`), classified `READ`. On a non-`ALLOWED` decision the upgrade is refused. Default (no auth configured) short-circuits to allow, so the open dashboard is unchanged. Browsers cannot attach a bearer token to a WebSocket, so a token/OIDC-authenticated dashboard must sit behind an authenticating proxy (or use mutual TLS). |
| `GET /mockserver/status`, `GET /mockserver/ready` | **no** | **no** | Deliberately open: these are liveness/readiness probes and must be reachable by health-check infrastructure without credentials. |
| `GET /mockserver/metrics` (Prometheus scrape) | **no** | **no** | Deliberately open: Prometheus/OTEL scrapers cannot present a control-plane certificate or bearer token while scraping, so the endpoint is served by `MetricsHandler` outside the `controlPlaneRequestAuthenticated` gate. Its labels expose operational metadata (`upstream_host`; LLM `provider`/`model` token & cost counters). Secure it by disabling (`metricsEnabled=false`, the default → `404`, nothing exposed), restricting at the network layer, or preferring PUSH export (OTLP / Prometheus Remote-Write) which has no scrape endpoint. **Do not add control-plane auth here.** The JSON snapshot `PUT /mockserver/retrieve?type=METRICS` *is* gated (dispatched through `HttpState.handle`). See [metrics.md → Scrape Endpoint](metrics.md). |
| `PUT /mockserver/bind` | yes | **yes (MUTATE)** | Auth-gated in `HttpRequestHandler` via the same `controlPlaneRequestAuthenticated` call as every other mutation. An unauthenticated caller receives 401/403 before any port is rebound. Default (no auth configured) is a no-op: the gate returns true and binding proceeds. |
| `PUT /mockserver/stop` | yes | **yes (MUTATE)** | Auth-gated identically to `/bind` — an unauthenticated caller cannot stop the server. Default (no auth configured) is a no-op: the gate returns true and `/stop` proceeds. |
| MCP control plane (`POST /mockserver/mcp` over HTTP/1.1, HTTP/2, HTTP/3, and JSON-RPC batch) | yes | **yes — per-tool read/mutate** | MCP requests are **authenticated** (same mTLS/JWT/OIDC as every control-plane route). When `controlPlaneAuthorizationEnabled` is true, per-tool **read/mutate authorization** is enforced: `McpToolRegistry` classifies each tool as read or mutate (fail-closed — an unclassified tool defaults to MUTATE), and `McpRequestProcessor` calls `HttpState.controlPlaneToolAuthorized` before executing the tool. A read-only principal is `403`'d on mutating tools (`create_expectation`, `clear_expectations`, `reset`, etc.). When `controlPlaneAuthorizationEnabled` is false (the default), no authorization check runs. |

HTTP/3 non-MCP control-plane requests re-dispatch into `HttpState.handle`, so they inherit full authorization automatically.

### Enriched Authentication SPI (`AuthenticationResult`)

The `AuthenticationHandler` SPI gained a richer, default-adapted method alongside the legacy boolean:

```java
default AuthenticationResult authenticate(HttpRequest request) {
    return controlPlaneRequestAuthenticated(request)
        ? AuthenticationResult.authenticated(null, "none", Map.of(), Set.of())
        : AuthenticationResult.unauthenticated();
}
```

`AuthenticationResult` is immutable and carries `authenticated`, `principal` (null = anonymous), `principalSource`, a read-only `claims` map and a read-only `scopes` set. Existing and third-party handlers that implement only the boolean method keep working unchanged — the default adapter treats their `true` as authenticated-but-anonymous. `ChainedAuthenticationHandler.authenticate()` ANDs every delegate, returns unauthenticated if any fails, and otherwise selects the first delegate with a non-null principal (so an OIDC/JWT principal wins over an mTLS-only null) while unioning all delegates' scopes.

### MCP Endpoint Authentication

The MCP endpoint (`/mockserver/mcp`) enforces the same control-plane **authentication** as all other control-plane routes. When `controlPlaneTLSMutualAuthenticationCAChain` and/or `controlPlaneJWTAuthenticationJWKSource` are configured, MCP requests must satisfy the same mTLS and/or JWT requirements. Unauthenticated MCP requests receive a `401 Unauthorized` response with a JSON-RPC error body. This ensures that enabling MCP does not widen the attack surface of a secured MockServer instance.

**Per-tool authorization.** When `controlPlaneAuthorizationEnabled=true`, per-tool read/mutate authorization is enforced at the MCP layer (see the coverage table above): `McpToolRegistry` classifies each tool as read or mutate (fail-closed), and `McpRequestProcessor` enforces the role check via `HttpState.controlPlaneToolAuthorized` before executing any tool call.

### Authentication Classes

| Class | Package | Purpose |
|-------|---------|---------|
| `AuthenticationHandler` | `o.m.authentication` | Core interface: legacy `controlPlaneRequestAuthenticated(HttpRequest): boolean` plus default-adapted `authenticate(HttpRequest): AuthenticationResult` |
| `AuthenticationResult` | `o.m.authentication` | Immutable enriched outcome: authenticated flag, verified principal, principalSource, read-only claims/scopes |
| `ChainedAuthenticationHandler` | `o.m.authentication` | Chains multiple `AuthenticationHandler` instances (logical AND — all must pass); combines results selecting the first verified principal and unioning scopes |
| `AuthenticationException` | `o.m.authentication` | Thrown on authentication failure |
| `MTLSAuthenticationHandler` | `o.m.authentication.mtls` | Validates client certificate chain against configured CA certificates via a PKIX `CertPath` (revocation disabled) plus a `clientAuth` Extended Key Usage check (absent EKU allowed) |
| `JWTAuthenticationHandler` | `o.m.authentication.jwt` | Loads JWK keys from URL (`RemoteJWKSet`) or file (`ImmutableJWKSet`), extracts Bearer token from `Authorization` header, delegates to `JWTValidator` |
| `OidcAuthenticationHandler` | `o.m.authentication.oidc` | Verifies an external-IdP OIDC Bearer token (signature + issuer + audience + exp/nbf + required scopes) and returns a verified-principal `AuthenticationResult`; resolves the JWK set directly or via OIDC discovery |
| `ControlPlaneRole` | `o.m.authentication.authorization` | Coarse hierarchical role enum (`READ` < `MUTATE` < `ADMIN`) with `satisfies(required)` |
| `ControlPlaneAuthorizer` | `o.m.authentication.authorization` | Maps a principal's verified scopes through `controlPlaneScopeMapping` into granted roles and decides allow/deny against the operation's required role |
| `JWTValidator` | `o.m.authentication.jwt` | Validates JWT tokens using nimbus-jose-jwt; supports `withExpectedAudience()`, `withMatchingClaims()`, `withRequiredClaims()` |
| `JWTGenerator` | `o.m.authentication.jwt` | Generates JWT tokens with configurable claims (used in tests) |
| `JWKGenerator` | `o.m.authentication.jwt` | Generates JWK sets from `AsymmetricKeyPair` objects (RSA and EC key types) |

### Supported JWS Algorithms

`JWTValidator` supports 11 JWS algorithms — **asymmetric families only**. HMAC (`HS256/384/512`)
is rejected: the validator verifies against a public-key JWK set loaded from a URL or file, and
accepting HMAC there is the classic algorithm-confusion forgery vector (an attacker signs an HMAC
token using the public key bytes as the shared secret). This matches `OidcJWTValidator`.

| Family | Algorithms |
|--------|-----------|
| RSA PKCS#1 | `RS256`, `RS384`, `RS512` |
| ECDSA | `ES256`, `ES256K`, `ES384`, `ES512` |
| RSA-PSS | `PS256`, `PS384`, `PS512` |
| EdDSA | `EdDSA` |

### JWT Authentication

Uses `nimbus-jose-jwt` library. The JWT handler:
1. Extracts the `Authorization: Bearer <token>` header
2. Validates the token against the configured JWK source
3. Checks required claims (issuer, audience, etc.)

## Proxy Authentication

For HTTP CONNECT proxy requests, MockServer supports Basic authentication:

1. `HttpRequestHandler` checks the `Proxy-Authorization` header
2. Validates against configured username/password
3. On failure: returns **407 Proxy Authentication Required** with `Proxy-Authenticate: Basic` header

SOCKS5 proxy also supports username/password authentication (configured separately).

## Data Plane Authentication

All of the authentication above protects the **control plane** (`/mockserver/*`) or the `CONNECT`
proxy. The **data plane** — the mocked endpoints themselves — is open by default. An opt-in,
default-off gate (`dataPlaneAuthenticationRequired`) can require credentials on every mocked request.

```mermaid
flowchart TD
    REQ([Request reaches data-plane dispatch]) --> EN{"dataPlaneAuthenticationRequired?"}
    EN -->|false default| PROC([processAction — serve mock])
    EN -->|true| CFG{"Any scheme configured?"}
    CFG -->|No| DENY([401 — fail closed])
    CFG -->|Yes| ANY{"Request satisfies ANY configured scheme? (Basic / Bearer / API-key)"}
    ANY -->|Yes| PROC
    ANY -->|No| DENY401([401 + WWW-Authenticate])
```

The gate sits at the top of the data-plane `else` branch in `HttpRequestHandler.channelRead0`, just
before the existing mTLS-upgrade check and `httpActionHandler.processAction(...)`. Because control-plane
routes, health/status/ready probes and `CONNECT` are all matched in **earlier** branches, reaching this
branch already means the request is a genuine data-plane request — so control-plane administration,
liveness/readiness probes and the proxy `CONNECT` handshake are never gated by data-plane auth.

| Aspect | Behaviour |
|--------|-----------|
| Default | `dataPlaneAuthenticationRequired=false` — no gate, byte-identical to a server without the feature |
| Schemes | HTTP Basic, Bearer token, API-key header — any combination |
| Multi-scheme | **Accept-any** (logical OR): a request is accepted if it satisfies any one configured scheme. Adding a scheme can only widen the accepted set |
| Required-but-unconfigured | **Fail-closed**: every data-plane request is rejected (401) rather than allowed |
| Failure response | `401 Unauthorized`, body `Unauthorized for data plane`; `WWW-Authenticate: Basic realm="…"` when Basic is configured, else `Bearer` when Bearer is configured, else no challenge (API-key-only) |
| Secret comparison | Constant-time (`MessageDigest.isEqual` on UTF-8 bytes) for password / token / API-key value; credential values never logged |

The policy/decision lives in core (`DataPlaneAuthenticator`, `o.m.authentication.dataplane`) so it is unit
testable; the Netty handlers only invoke it and write the 401. The invocation + 401-writing is itself
factored into a single shared netty helper (`DataPlaneAuthenticationGate.isAuthenticated(...)`) so that
**every** data-plane dispatch path enforces it identically — there is no transport on which the gate can be
skipped. `configuration.dataPlaneAuthenticationRequired()` is a single boolean read, so the default-off
path adds nothing measurable to the hot path.

**Scope.** The gate covers every HTTP data-plane dispatch path:

- **HTTP/1.1, HTTP/2 and gRPC-over-h2** — `HttpRequestHandler`, just before `httpActionHandler.processAction(...)`.
- **HTTP/3 (QUIC), including gRPC-over-HTTP/3** — `Http3MockServerHandler`, at both the normal and the
  gRPC data-plane dispatch sites, before `processAction(...)`. HTTP/3 carries the same HTTP
  `Authorization` / api-key headers, so it is the same request type and the same gate applies. (This path
  previously bypassed the gate — a fail-open — and is now closed.)
- Requests tunnelled through a `CONNECT` proxy are decrypted and re-dispatched back through
  `HttpRequestHandler`, so they are gated too once enabled.

On all of the above, control-plane (`/mockserver/*`), liveness/status/ready probes and `CONNECT` are routed
through `httpState.handle(...)` (or earlier branches) **before** the gate, so they remain reachable without
data-plane credentials — an operator can still administer a locked-down server.

**Out of scope.** Raw-binary proxy traffic handled by `BinaryRequestProxyingHandler` is a non-HTTP byte
stream (no HTTP request structure), so HTTP credentials are not meaningful there and the gate does not
apply. mTLS for incoming connections (`tlsMutualAuthenticationRequired`) is an orthogonal transport-layer
check handled earlier in `PortUnificationHandler` and is unaffected.

### Data-Plane Authentication Properties

| Property | Default | Purpose |
|----------|---------|---------|
| `dataPlaneAuthenticationRequired` | false | Master switch — require auth on mocked endpoints |
| `dataPlaneBasicAuthenticationUsername` | (none) | HTTP Basic username (Basic active only when both username and password are set) |
| `dataPlaneBasicAuthenticationPassword` | (none) | HTTP Basic password |
| `dataPlaneBasicAuthenticationRealm` | MockServer | Realm advertised in the `WWW-Authenticate: Basic` challenge |
| `dataPlaneBearerAuthenticationToken` | (none) | Expected `Authorization: Bearer <token>` value |
| `dataPlaneApiKeyAuthenticationHeader` | (none) | Header name carrying the API key (e.g. `X-API-Key`) |
| `dataPlaneApiKeyAuthenticationValue` | (none) | Expected API-key value (API-key active only when both header and value are set) |

## TLS Configuration Properties

| Property | Default | Purpose |
|----------|---------|---------|
| `tlsProtocols` | TLSv1.2,TLSv1.3 | Enabled TLS protocol versions. TLSv1/TLSv1.1 are no longer in the default (RFC 8996); TLSv1.3 is now included. Restore legacy protocols by adding them here **and** setting `tlsAllowInsecureProtocols=true` |
| `tlsAllowInsecureProtocols` | false | Whether deprecated TLSv1/TLSv1.1 entries in `tlsProtocols` are honoured (otherwise stripped) |
| `tlsMutualAuthenticationRequired` | false | Require client certificates |
| `tlsMutualAuthenticationCertificateChain` | (none) | PEM file with trusted CA chain for client certs |
| `dynamicallyCreateCertificateAuthorityCertificate` | false | Auto-generate CA cert |
| `certificateAuthorityPrivateKey` | (auto) | PEM file for custom CA private key |
| `certificateAuthorityCertificate` | (auto) | PEM file for custom CA certificate |
| `forwardProxyTLSX509CertificatesTrustManagerType` | ANY | Trust mode for upstream connections (ANY = trust-all + no host-name check; JVM/CUSTOM = validate chain + verify host name) |
| `forwardProxyTLSHostnameVerificationEnabled` | true | Verify the upstream host name against its certificate for the JVM/CUSTOM trust managers; no effect for ANY |
| `forwardProxyTLSCustomTrustX509Certificates` | (none) | PEM file for custom upstream trust |
| `forwardProxyPrivateKey` | (none) | Global outbound mTLS client private key (PKCS#8/PKCS#1 PEM) |
| `forwardProxyCertificateChain` | (none) | Global outbound mTLS client certificate chain (X.509 PEM) |
| `forwardProxyClientCertificatesByHost` | (none) | Per-host outbound mTLS cert/key map: `host=certChainPath;keyPath,...`; falls back to the global pair |
| `controlPlaneTLSMutualAuthenticationRequired` | false | Require mTLS for control plane |
| `controlPlaneTLSMutualAuthenticationCAChain` | (none) | CA chain for control plane mTLS |
| `controlPlaneJWTAuthenticationJWKSource` | (none) | JWK source URL for JWT validation |
| `controlPlaneJWTAuthenticationRequired` | false | Require JWT for control plane |
| `controlPlaneOidcAuthenticationRequired` | false | Require verified external-IdP OIDC token for control plane |
| `controlPlaneOidcIssuer` | (none) | Required `iss`; also used for OIDC discovery of the JWKS URI |
| `controlPlaneOidcJwksUri` | (none) | JWK set URI (skips discovery when set) |
| `controlPlaneOidcAudience` | (none) | Required `aud` on control-plane tokens |
| `controlPlaneOidcRequiredScopes` | (empty) | Scopes that must all be present |
| `controlPlaneOidcScopeClaim` | scope | Claim holding granted scopes (`scope`/`scp`/`roles`/`groups`) |
| `controlPlaneAuthorizationEnabled` | false | Enforce coarse role-based authorization of control-plane requests (requires a verified principal) |
| `controlPlaneScopeMapping` | (empty) | Map verified scope/group values to roles, e.g. `platform-admins=admin,qa-team=mutate,viewers=read` |
