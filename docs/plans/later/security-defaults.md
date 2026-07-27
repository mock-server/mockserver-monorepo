# Security Defaults — Next Major Release

**Status:** Partly landed — **DEF-2 and DEF-3 shipped 2026-07-27** in response to
[GHSA-7pwj-xvc2-hfpc](https://github.com/mock-server/mockserver-monorepo/security/advisories/GHSA-7pwj-xvc2-hfpc)
(template RCE); the remaining six items are still open (last verified 2026-06-19: every other property and
mitigation is wired in `ConfigurationProperties.java` / `Configuration.java`, and every other default is still
the insecure value below). The "ship every flip in one release" goal below was deliberately broken for the two
template items only, because leaving a reported RCE default in place until the next major release was not
defensible; the rest still travel together. Insecure-by-default configuration values to flip in a single major
release.
**Created:** 2026-05-26 (supersedes `security-remediation.md`)
**Last verified against code:** 2026-06-19
**Scope:** Configuration default changes only. All underlying mitigations (timeouts, validators, body limits, secure RNG, XXE blocks) have already shipped and are wired behind these properties; only the defaults remain.

## Goals

1. Ship every insecure default flip in one release so users perform a single migration.
2. Each flip is **fully reversible** by setting one property — no functionality is removed.
3. DEF-1, DEF-2, DEF-3, and DEF-4 already emit a WARN today when set to their insecure values, so users of those properties have had a deprecation window. DEF-5, DEF-6, DEF-7, and DEF-8 do **not** currently warn — each implementation PR for those four items must add a startup WARN in a prior minor release before the default is flipped.
4. One consolidated migration guide. One major version bump.

## Items

| ID | Property | Old default | New default | Breaking? |
|----|----------|-------------|-------------|-----------|
| DEF-1 | `forwardProxyTLSX509CertificatesTrustManagerType` | `ANY` | `JVM` | Yes |
| ~~DEF-2~~ | ~~`velocityDisallowClassLoading`~~ | ~~`false`~~ | `true` | **LANDED 2026-07-27** |
| ~~DEF-3~~ | ~~JavaScript template class access~~ | ~~deny-list (empty)~~ | deny everything | **LANDED 2026-07-27** |
| DEF-4 | `tlsAllowInsecureProtocols` | `true` | `false` | Yes (drops TLSv1/1.1) |
| DEF-5 | `forwardProxyBlockPrivateNetworks` | `false` | `true` | Yes |
| DEF-6 | `localBoundIP` | `""` (all interfaces, `0.0.0.0`) | `127.0.0.1` | Yes (large impact) |
| DEF-7 | `attemptToProxyIfNoMatchingExpectation` | `true` | `false` | Yes |
| DEF-8 | `maxInitialLineLength` / `maxHeaderSize` / `maxChunkSize` | `Integer.MAX_VALUE` | `8 KiB` / `16 KiB` / `16 KiB` | Hardening; breaks only pathological clients |

---

## DEF-1 — Forward-proxy trusts JVM trust store, not everything

**Property:** `mockserver.forwardProxyTLSX509CertificatesTrustManagerType`
**Defined:** `mockserver-core/.../ConfigurationProperties.java`
**Today:** Defaults to `ANY`, which installs `InsecureTrustManagerFactory.INSTANCE` and accepts self-signed, expired, hostname-mismatched, and otherwise invalid certificates when MockServer acts as an HTTPS forward proxy.
**Flip:** Default to `JVM` so the JDK trust store validates upstream certificates the same way any standard Java HTTPS client would.
**Opt-out:** `mockserver.forwardProxyTLSX509CertificatesTrustManagerType=ANY` (a WARN is logged when this is in effect — already implemented). Users with a small set of trusted self-signed CAs should prefer `=CUSTOM` plus `mockserver.forwardProxyTLSCustomTrustX509Certificates` pointing at the CA bundle, rather than disabling validation entirely.
**Why it's safe to flip now:** the WARN has been in place since the prior security remediation pass; no functionality is removed.

---

## DEF-2 — Velocity templates block class loading — ✅ LANDED 2026-07-27

**Property:** `mockserver.velocityDisallowClassLoading`
**Today:** Defaults to `false`. A user-supplied Velocity template can call `$Class.forName("java.lang.Runtime").getRuntime().exec(...)`.
**Flip:** Default to `true`, installing `SecureUberspector` so templates cannot reach arbitrary classes.
**Opt-out:** `mockserver.velocityDisallowClassLoading=false` (WARN already logged).
**Notes:** Velocity templates that legitimately need class loading are rare; the opt-out covers them.

---

## DEF-3 — JavaScript templates use an allowlist, not a deny-list — ✅ LANDED 2026-07-27 (stricter than planned)

**As shipped, this went further than the plan below:** rather than a built-in allowlist of "safe core types",
the default allowlist is EMPTY and resolves nothing, and `javascriptDisallowedClasses` was kept rather than
removed (setting it now *widens* the safe default, and it is documented as not a security boundary). A curated
"safe types" default would have to be re-audited every time the JDK grows a new reachable class, and templates
rarely need Java at all given the full ES2023 standard library; `javascriptAllowedClasses=*` is the documented
escape hatch for the previous unrestricted behaviour. The original plan text follows for the record.

**Today:** `javascriptDisallowedClasses` is a deny-list defaulting to empty, so all classes (`java.lang.Runtime`, `java.io.File`, etc.) are reachable via `Java.type(...)`. A WARN is logged when the deny-list is empty.
**Flip:** Introduce `mockserver.javascriptAllowedClasses` (new property) and switch to allowlist semantics. Default allowlist covers common safe types only:

```
java.lang.String, java.lang.Integer, java.lang.Long, java.lang.Double, java.lang.Boolean,
java.util.List, java.util.Map, java.util.Set, java.util.ArrayList, java.util.HashMap
```

**Opt-out:** Extend the allowlist via `mockserver.javascriptAllowedClasses=...`. The legacy `javascriptDisallowedClasses` property is removed (deprecation period was the WARN log).
**Why this is more invasive than DEF-1/2:** the property name itself changes. Migration guide must call this out explicitly.

---

## DEF-4 — Drop TLSv1 and TLSv1.1 by default

**Property:** `mockserver.tlsAllowInsecureProtocols`
**Today:** Defaults to `true`. The `tlsProtocols` default is `"TLSv1,TLSv1.1,TLSv1.2"`, so deprecated TLSv1 and TLSv1.1 (vulnerable to BEAST, POODLE; deprecated per RFC 8996) are still honoured. A WARN is logged when `tlsProtocols` includes them.
**Flip:** Default `tlsAllowInsecureProtocols` to `false`. TLSv1 and TLSv1.1 entries are stripped from `tlsProtocols`, leaving **TLSv1.2 only** out of the box. TLSv1.3 is *not* added by this flip — users who want TLSv1.3 must add it to `tlsProtocols` explicitly.
**Opt-out:** `mockserver.tlsAllowInsecureProtocols=true`.
**Affected users:** Anyone whose tests still target ancient TLS stacks (very rare in 2026). Opt-out is one property.
**Consider also:** flipping the `tlsProtocols` default to `"TLSv1.2,TLSv1.3"` in the same release so TLSv1.3 is on by default. Out of scope of this plan as written, but worth deciding at implementation time.

---

## DEF-5 — Block SSRF to private networks by default

**Property:** `mockserver.forwardProxyBlockPrivateNetworks`
**Today:** Defaults to `false` (was originally introduced as opt-in to avoid breaking localhost / Docker / k8s setups). The validator (`InetAddressValidator`) is already wired into both forward action handlers.
**Flip:** Default to `true`. Forward actions whose target resolves to RFC 1918, loopback, link-local, or AWS metadata (`169.254.169.254`, `fd00:ec2::254`) are rejected.
**Opt-out:** `mockserver.forwardProxyBlockPrivateNetworks=false`.
**Compatibility risk:** This is the highest-impact flip for tests that use MockServer to forward between containers. The migration guide must document opt-out clearly with example Docker Compose / k8s usage.

---

## DEF-6 — Bind to loopback by default

**Property:** `mockserver.localBoundIP`
**Today:** Defaults to `""` which resolves to `0.0.0.0`, listening on every interface. A developer on a shared / corporate network exposes their MockServer instance to the LAN by default.
**Flip:** Default to `127.0.0.1` (IPv4 loopback). Inside containers users must set `0.0.0.0` explicitly to reach MockServer from the host.
**Opt-out:** `mockserver.localBoundIP=0.0.0.0` or any specific interface.
**Compatibility risk:** **This is the most disruptive flip in this release.** Every Docker / Kubernetes / Helm deployment will need to set `MOCKSERVER_LOCAL_BOUND_IP=0.0.0.0`. Bundle this change with:
- An updated Dockerfile `ENV MOCKSERVER_LOCAL_BOUND_IP=0.0.0.0` (so the container image keeps working out-of-the-box; the loopback default only takes effect for raw JAR / WAR users)
- Updated `docker-compose` and Helm chart examples
- A prominent section in the migration guide

If the disruption is judged too high, an alternative is to keep `0.0.0.0` and add a startup banner warning when not on loopback. **Recommendation: flip the default; bake `0.0.0.0` into the container ENV.**

---

## DEF-7 — Don't proxy unmatched requests by default

**Property:** `mockserver.attemptToProxyIfNoMatchingExpectation`
**Today:** Defaults to `true`. Any inbound request that doesn't match an expectation is forwarded to the request's `Host:` header, turning MockServer into an open proxy if exposed.
**Flip:** Default to `false`. Unmatched requests return `404 Not Found` (existing fallback path).
**Opt-out:** `mockserver.attemptToProxyIfNoMatchingExpectation=true`.
**Compatibility risk:** Affects users running MockServer as a forward proxy that records or selectively mocks traffic. The opt-out preserves the exact previous behaviour. Document the opt-out alongside the proxy / recording examples in the consumer docs.

---

## DEF-8 — Bound HTTP parsing limits

**Properties:** `mockserver.maxInitialLineLength`, `mockserver.maxHeaderSize`, `mockserver.maxChunkSize`
**Today:** All default to `Integer.MAX_VALUE` (~2 GiB). A single malicious client can exhaust the heap by sending an unbounded header or request line — none of the already-shipped body limits cover the request line or headers.
**Flip:** Defaults aligned with Netty's own defaults and standard reverse proxies:

| Property | New default |
|----------|-------------|
| `maxInitialLineLength` | 8192 (8 KiB) |
| `maxHeaderSize` | 16384 (16 KiB) |
| `maxChunkSize` | 16384 (16 KiB) |

**Opt-out:** Set the relevant property higher. The existing `maxRequestBodySize` (10 MiB) and `maxResponseBodySize` (50 MiB) defaults are unchanged.
**Compatibility risk:** Negligible for real-world traffic. Tests that intentionally craft huge headers/URIs to exercise edge cases must opt out.

---

## Items considered and rejected

For the record so future contributors don't re-litigate:

- **`tlsMutualAuthenticationRequired` → `true`**: rejected. Forcing every inbound connection to present a client certificate would break virtually every user; mTLS remains opt-in.
- **`controlPlaneTLSMutualAuthenticationRequired` → `true`**: rejected for the same reason; the control-plane mTLS path is unchanged.
- **`dynamicallyCreateCertificateAuthorityCertificate` → `true`**: rejected. Flipping breaks every client that has already trusted the bundled MockServer CA. The default static CA is documented as a test-only convenience.
- **`preventCertificateDynamicUpdate` → `true`**: rejected. Dynamic SAN updates are part of the documented proxy flow; flipping would silently break in-flight tests.
- **`initializationClass` allowlist**: not in this release. The property is empty by default, so the attack surface only opens if a user already configures arbitrary class loading. Track separately if a real exploit path emerges.
- **CORS defaults**: already safe — `corsAllowOrigin`, `corsAllowMethods`, `corsAllowHeaders` all default to empty and `corsAllowCredentials` to `false`. Nothing to flip.

---

## Release & migration

- **Version:** Single major version bump.
- **Migration guide:** One document with one section per `DEF-*` ID, each containing: *what changed*, *how to detect if you're affected*, and *one-line opt-out*.
- **Release notes:** Top-line summary "secure-by-default" framing; explicit BREAKING block listing all eight flips.
- **Container image:** Set `ENV MOCKSERVER_LOCAL_BOUND_IP=0.0.0.0` in the Dockerfile so the container experience is unchanged. Document this carve-out clearly so users running the raw JAR/WAR know they're getting the loopback default.
- **Helm chart:** Pass `MOCKSERVER_LOCAL_BOUND_IP=0.0.0.0` (already needed for in-cluster reachability).
- **Pre-release validation:** Run the full Buildkite suite plus a manual smoke test against the official Docker image and the Helm chart to confirm `DEF-6` does not break the default container path.

## Implementation order

All flips are independent property changes; order does not matter functionally. Suggested PR sequencing for review clarity:

0. **Prerequisite (prior minor release):** add startup WARN logs for DEF-5, DEF-6, DEF-7, and DEF-8 when set to their insecure defaults. These four items have no existing deprecation log, so a release window with a WARN must precede the default flip.
1. DEF-1, DEF-2, DEF-4 — single-character default changes; trivial review.
2. DEF-5, DEF-7 — boolean flips on existing well-tested validators.
3. DEF-8 — three numeric defaults; verify all integration tests still pass.
4. DEF-6 — bind-address change; bundled with Dockerfile ENV and Helm chart updates.
5. DEF-3 — property rename + allowlist semantics; needs the migration guide entry written before merging.

Each PR must update `jekyll-www.mock-server.com/mock_server/configuration_properties.html` and the relevant `docs/` entry under `code/` or `operations/`.
