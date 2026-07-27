# Security Policy

## Supported Versions

MockServer requires **Java 17** as the minimum supported runtime (raised from Java 11 in the 6.x line as part of the Jakarta EE 10 / Spring 7 platform modernisation). Currently supported versions:

| Version | Supported          |
| ------- | ------------------ |
| 7.4.x   | :white_check_mark: |
| 6.0.x   | :white_check_mark: |
| < 6.0   | :x:                |

The latest minor in the current major line receives fixes; older minors are best-effort. The `5.15.x` line is the last Java 11-compatible release and no longer receives security updates.

## Security Posture

### MockServer is a Development and Testing Tool

**Important:** MockServer is designed for **development, testing, and QA environments only**. It should **never** be deployed in production or exposed to untrusted networks.

### Java 17 Platform and Security Updates

MockServer targets **Java 17** as the minimum supported runtime. The 6.x line completed the Jakarta EE 10 / Servlet 6 modernisation, so the dependency stack now tracks current, actively-patched major versions:

- **Minimum Java version:** Java 17
- **Spring Framework:** 7.x
- **Spring Boot:** 4.x (test/example dependency only)
- **Jetty:** 12.x · **Tomcat embed:** 11.x · **Jersey:** 4.x (Jetty/Tomcat are test/example dependencies, not server runtime)

Because these are current major lines, security fixes that previously required dropping Java 11 are now available and applied through normal dependency upgrades. The earlier Java-11 ceiling — which had pinned Spring to 5.3.x and Jetty to 9.4.x and blocked roughly 20 security fixes — no longer applies; those ignores have been removed from the [`.snyk`](.snyk) policy.

### Dependabot Alerts

Dependency alerts are now resolved through normal upgrades rather than suppressed for compatibility. The only remaining version ceiling is `com.puppycrawl.tools:checkstyle < 13.0.0` (checkstyle 13.x requires a Java 21 runtime); it is build-tooling only, not shipped in any artifact. See [docs/operations/security.md](docs/operations/security.md) for the full scanning and triage workflow.

### Risk Assessment

Most reported vulnerabilities require **attacker-controlled input** to exploit:

- **Unsafe deserialization** - Requires attacker to control serialized data sent to MockServer
- **Path traversal** - Requires attacker to control file paths in requests
- **DataBinder issues** - Requires specific attack patterns against Spring MVC/WebFlux

In a **development/testing environment** where:
- MockServer is not exposed to untrusted networks
- Only developers/testers have access
- No sensitive production data is processed

...these vulnerabilities pose **minimal practical risk**.

### Dismissed Alerts

The following types of alerts are dismissed as **not applicable** to MockServer's use case:

1. **Spring deserialization vulnerabilities** - MockServer does not deserialize untrusted user data through Spring's mechanisms
2. **Spring MVC path traversal** - MockServer uses its own request routing, not Spring MVC's file serving
3. **DataBinder case sensitivity** - Not exploitable in MockServer's mocking/proxying use case

## Intentional Security Behaviors

MockServer intentionally includes capabilities that would be **dangerous in a production service** but are **required features for a testing tool**. These are **not vulnerabilities** — they are essential for MockServer's purpose.

### Why These Features Exist

As a testing tool, MockServer must allow developers to:
- Mock internal services and APIs
- Test security controls in their own applications
- Simulate various network conditions and edge cases
- Create realistic test scenarios including "dangerous" inputs

**Important:** All expectations and templates are created by **trusted developers/testers**, not by external attackers. MockServer assumes:
- ✅ All users are trusted (developers, testers, CI/CD systems)
- ✅ All expectations are trusted (you control what you mock)
- ✅ The network is trusted (isolated dev/test environment)
- ✅ No production data or systems are involved

### Intentional Behaviors (Features, Not Bugs)

#### 1. Server-Side Request Forgery (SSRF) - **INTENTIONAL**

**What it does:** MockServer's forward action can send requests to any host/port, including:
- Internal IPs: `127.0.0.1`, `localhost`, `10.0.0.0/8`, `192.168.0.0/16`
- Cloud metadata endpoints: `169.254.169.254`
- Private networks and internal services

**Why it's needed:**
```java
// Valid use case: Mock localhost microservice
mockServerClient
    .when(request().withPath("/orders"))
    .forward(forward().withHost("localhost").withPort(8080));

// Valid use case: Test AWS SDK behavior with metadata service
mockServerClient
    .when(request().withPath("/api/config"))
    .forward(forward().withHost("169.254.169.254").withPath("/latest/meta-data/"));
```

**Security control:** MockServer should never be exposed to untrusted networks.

---

#### 2. Regular Expression Denial of Service (ReDoS) - **INTENTIONAL**

**What it does:** Regex matchers accept user-defined patterns without timeout or complexity limits.

**Why it's needed:**
```java
// Valid use case: Test how your app handles expensive regex
mockServerClient
    .when(request().withPath("(a+)+"))  // Pathological regex pattern in path
    .respond(response().withStatusCode(200));

// Testing scenario: Validate your system's regex timeout logic
```

**Security control:** Users control all matchers (trusted input). Optional timeout can be configured if needed.

---

#### 3. Trust-All TLS Certificates (Default) - **INTENTIONAL**

**What it does:** Forward proxy accepts all TLS certificates by default:
- Self-signed certificates
- Expired certificates
- Wrong hostnames
- Invalid certificate chains

**Default setting:** `forwardProxyTLSX509CertificatesTrustManagerType=ANY`

**Why it's needed:**
```java
// Valid use case: Forward to staging environment with self-signed cert
mockServerClient
    .when(request().withPath("/api"))
    .forward(forward()
        .withHost("staging.internal")
        .withScheme(HTTPS));
```

**Security control:** Users can configure `JVM` or `CUSTOM` trust modes if validation is needed for specific test scenarios.

---

#### 4. Template Java class access (Velocity/JavaScript) - **OPT-IN, BLOCKED BY DEFAULT**

**What it does:** a response template can reach Java classes, but only when an operator grants it. Reaching
`java.lang.Runtime` or `java.lang.ProcessBuilder` from a template means OS command execution in the
MockServer process, so anyone who can register an expectation on an exposed control plane could run code on
the host. That was possible out of the box before 7.4.1 (GHSA-7pwj-xvc2-hfpc) and is now blocked by default:

- Velocity: `velocityDisallowClassLoading` defaults to `true`, so `$request.class.classLoader.loadClass(...)`
  resolves nothing.
- JavaScript: `javascriptAllowedClasses` defaults to empty, meaning NO class resolves via `Java.type(...)`.

**Why the capability still exists:**
```velocity
## Valid use case: a template that needs a Java type
## Requires -Dmockserver.velocityDisallowClassLoading=false
#set($uuid = $request.class.classLoader.loadClass("java.util.UUID"))
Response ID: $uuid.randomUUID().toString()
```

```javascript
// Valid use case: complex response generation logic
// Requires -Dmockserver.javascriptAllowedClasses="java.util.UUID"
var UUID = Java.type("java.util.UUID");
return { requestId: UUID.randomUUID().toString() };
```

Neither example needs Java in practice — the built-in `$uuid` helper and JavaScript's own standard library
cover them — so most deployments never grant class access at all.

**Security control:** blocked by default; granting it is an explicit, per-class opt-in that logs a WARN when
switched off wholesale. Templates that do not touch Java classes are unaffected.

---

#### 5. JsonPath / XPath Denial of Service - **INTENTIONAL**

**What it does:** JsonPath and XPath expressions can use recursive descent (`..`) and complex queries without timeout.

**Why it's needed:**
```java
// Valid use case: Test expensive JsonPath evaluation
mockServerClient
    .when(request().withBody(jsonPath("$..book[?(@.price < 10)]")))
    .respond(response().withBody("found"));

// Testing scenario: Validate your system handles complex JSON queries
```

**Security control:** Users control all matchers. Optional timeout can be configured if needed.

---

#### 6. XML External Entity (XXE) Processing - **INTENTIONAL**

**What it does:** XML matchers do not disable external entity processing by default.

**Why it's needed:**
```xml
<!-- Valid use case: Test your app's XXE defenses -->
<!DOCTYPE foo [<!ENTITY xxe SYSTEM "file:///etc/passwd">]>
<request>&xxe;</request>
```

**Security control:** All XML is user-controlled test data, not attacker input.

---

#### 7. Legacy TLS Protocol Support (TLSv1.0/1.1) - **INTENTIONAL**

**What it does:** MockServer supports TLSv1.0 and TLSv1.1 (deprecated by RFC 8996).

**Default setting:** `tlsProtocols=TLSv1,TLSv1.1,TLSv1.2`

**Why it's needed:**
- Testing applications that must support legacy clients
- Testing embedded devices with old TLS stacks
- Validating TLS version negotiation logic
- Testing against systems that haven't upgraded yet

**Security control:** Modern TLS (1.2/1.3) is still supported. Users can restrict to modern versions if desired.

---

#### 8. Unbounded Request/Response Bodies - **INTENTIONAL**

**What it does:** HTTP object aggregators accept up to `Integer.MAX_VALUE` (2GB) body size.

**Why it's needed:**
```java
// Valid use case: Test large file upload handling
mockServerClient
    .when(request().withPath("/upload"))
    .respond(response().withBody(new byte[1024 * 1024 * 100]));  // 100MB response
```

**Security control:** Configure JVM heap size appropriately for your test scenarios.

---

### Summary: Features vs Vulnerabilities

| Behavior | In Production Service | In Testing Tool |
|----------|----------------------|-----------------|
| Forward to localhost | 🔴 SSRF vulnerability | ✅ Required feature |
| Accept all TLS certs | 🔴 MITM vulnerability | ✅ Required feature |
| Execute template code | 🔴 RCE vulnerability | ✅ Required feature |
| Process XXE | 🔴 Information disclosure | ✅ Required feature |
| No regex timeout | 🔴 DoS vulnerability | ✅ Required feature |
| Legacy TLS versions | 🔴 Weak encryption | ✅ Required feature |

### What IS a Real Vulnerability?

MockServer **will fix** actual security issues such as:
- ✅ **Correctness bugs** (e.g., logic errors in matchers)
- ✅ **Memory leaks** (e.g., ByteBuf leaks)
- ✅ **Unintended information disclosure** (e.g., logging secrets)
- ✅ **Weak cryptography in certs** (e.g., predictable serial numbers) — **Fixed in 6.0.x**

MockServer **will not restrict** intentional features that enable testing.

## Best Practices

To use MockServer securely:

### ✅ DO:
- Run MockServer only in **development, testing, or QA environments**
- Restrict network access to **trusted users only** (developers, testers, CI/CD)
- Use MockServer behind a **firewall or VPN**
- Stop MockServer instances when not in use
- Keep MockServer updated to the latest version for bug fixes

### ❌ DO NOT:
- Deploy MockServer in **production environments**
- Expose MockServer directly to the **public internet**
- Use MockServer to handle **sensitive production data**
- Rely on MockServer for **security-critical operations**
- Keep MockServer running unnecessarily

## Reporting a Vulnerability

If you discover a security vulnerability in MockServer itself (not dependency alerts), please report it via:

- **GitHub Security Advisories:** https://github.com/mock-server/mockserver-monorepo/security/advisories/new
- **Email:** Contact the maintainers through GitHub

Please **do not** open public issues for security vulnerabilities.

### What to include:

1. Description of the vulnerability
2. Steps to reproduce
3. Potential impact
4. Suggested fix (if available)

We will respond within **7 days** and work with you to understand and address the issue.

## Upgrade Path

MockServer 6.x requires **Java 17 or later**. If you are still on Java 11:

1. **Upgrade your environment to Java 17+** to run MockServer 6.x (and pick up the patched Spring 7 / Jetty 12 dependency stack).
2. If you cannot move off Java 11 yet, **pin to MockServer 5.15.x** (the last Java 11-compatible line) — note it no longer receives security updates.

See the [Java 17 / Jakarta EE 10 upgrade guide](docs/operations/migration-java17-jakarta.md) for the consumer-facing breaking changes (servlet namespace, container requirements, removed shaded classifier).

## Further Reading

For a consolidated overview of all security scanning and tooling (CodeQL, Dependabot, Snyk, AI security review, and SNAPSHOT security policy), see [docs/operations/security.md](docs/operations/security.md).

For details on how AI-assisted development is governed, including adversarial code review by independent AI models, see [docs/operations/ai-assisted-development.md](docs/operations/ai-assisted-development.md).

## Questions?

For security-related questions, see:
- [GitHub Discussions](https://github.com/mock-server/mockserver-monorepo/discussions)
- [GitHub Issues](https://github.com/mock-server/mockserver-monorepo/issues)
