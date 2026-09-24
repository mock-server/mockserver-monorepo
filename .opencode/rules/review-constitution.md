# Adversarial Review Constitution

These principles govern the adversarial review process for MockServer. The reviewer MUST evaluate the spec or code against every applicable principle. When a principle is violated, it becomes a finding.

## Core Axioms

1. **The spec/code is wrong until proven right.** Do not extend the benefit of the doubt. If something is unclear, it is a defect.
2. **Silence is a bug.**
   - **2a (Content silence):** If the spec does not address a concern (error handling, rollback, security, operability), that concern is unaddressed — not implicitly handled.
   - **2b (Inventory silence):** If the spec claims to enumerate all affected artifacts (files, endpoints, configs, tests) but omits an artifact that matches the same criteria, the omission is a finding — not evidence that the artifact is unaffected.
3. **Every requirement must be testable.** If you cannot write a test for a requirement, the requirement is defective.
4. **Every test must trace to a requirement.** Orphan tests indicate scope creep or missing requirements.
5. **Failure is the default.** Assume every external call fails, every input is malformed, every user is confused, and every attacker is motivated.
6. **LLM-generated code has systematic blind spots.** The developer and reviewer may share training data and reasoning patterns — actively hunt for hallucinated names, plausible-but-incorrect logic, and incomplete error handling.

## Review Constitutions — Baseline + Per-Artefact Profiles

The **8 lenses below** (Ambiguity, Incompleteness, Inconsistency, Infeasibility,
Insecurity, Inoperability, Incorrectness, Overcomplexity), the Core Axioms, and the
MockServer-Specific Review Triggers are the **baseline** every review applies — the
floor, never removed. A review is *also* driven by a **per-artefact-type profile**
that **extends** the baseline with the focus areas, key evidence, and PASS criteria
that matter most for that artefact (spec §14.3). This raises signal (probe what
actually breaks this artefact type) and cuts noise (don't force-fit irrelevant
generic concerns) — but a profile may never excuse skipping a baseline lens.

**Profile set version: 1.1** (2026-09-24). Changes to the baseline or any profile
require a version bump and a control-integrity review ([[control-integrity]]); the
version applied is recorded in the decision log.

Rules:

- **Pick a profile per artefact** from its type — infer the type from the file
  categories / paths (`.java` + tests → review-coding; `docs/**.md` →
  review-documentation; `.tf` / Dockerfile / pipeline YAML → review-deployment; a
  spec / plan / ADR doc → the matching profile). If **no profile matches**, apply
  the **baseline only** and note why. Record which profile + version (and any
  composition) was applied in the decision log ([[decision-log]]) for significant
  or control-change reviews.
- **Baseline always included** — a profile sharpens the lenses; it never removes one.
- **Composition** — an artefact spanning types (code + its tests, an ADR inside a
  plan) is reviewed under a composition of profiles; record the composition.

| Profile | Governs | Sharpens (beyond the baseline lenses) | Key evidence | PASS emphasises |
|---------|---------|----------------------------------------|--------------|------------------|
| **review-coding** | code changes + tests | correctness vs intent, edge cases, concurrency/resource safety (ByteBuf, handlers), convention conformance, meaningful test coverage, diff security, observability of new paths | diff + test results + static analysis | behaviour matches intent and is covered by meaningful tests |
| **review-specification** | specs, requirements | testability of each requirement, measurability of outcomes, scope/non-goal clarity, normative precision, internal contradiction, hidden assumptions | the spec + its source intent | every requirement is verifiable and unambiguous |
| **review-plan** | implementation / migration plans | decomposition into independent units, dependency/sequencing, parallelisability & contention, rollback per step, blast radius, cancellation points | plan + repo/contention map | each unit is independently executable and reversible |
| **review-adr** | architecture decision records | alternatives genuinely considered, trade-offs explicit, reversibility/lock-in, coupling impact, consistency with existing architecture, decay conditions | ADR + affected architecture | decision, alternatives, consequences explicit and justified |
| **review-investigation** | diagnoses, RCAs, ops analyses | causal soundness (correlation vs causation), evidence completeness, alternatives ruled out, reproducible evidence trail, actionability | logs / metrics / traces + change records | conclusion is evidence-backed and the trail is reproducible |
| **review-documentation** | docs, READMEs, runbooks | accuracy vs current behaviour, completeness, staleness, audience fit, runnable instructions, dead links/commands | doc + the code/system it describes | content is accurate, current, and executable as written |
| **review-deployment** | deployment / infra changes | rollback characteristics, blast radius, progressive-delivery safety, observability/alerting, policy/security conformance, capacity impact | change + infra validation + policy checks | change is reversible, observable, and within policy |
| **review-periodic** | scheduled health / drift / security sweeps | drift from standards, accumulating risk/debt, coverage gaps, regressions vs baseline, recurrence of known failure patterns | trend / baseline comparisons | no unflagged regression, drift, or recurring failure pattern |

The **MockServer-Specific Review Triggers** section below applies to **all
reviews as part of the baseline** (the floor) — e.g. a *spec* or *plan* that
touches ByteBuf lifecycle, Netty pipeline order, or ring-buffer sizing must still
fire those triggers. The **review-coding** profile additionally lists them as
primary key evidence, because code changes are the most common context in which
they fire. Every other profile shares the same lens machinery, sharpened per the
table above.

## Lens 1 Principles: Ambiguity

| ID | Principle | Anti-Pattern |
|----|-----------|-------------|
| AMB-01 | Every domain term must be defined exactly once | Using "expectation", "action", and "response" interchangeably when they mean different things |
| AMB-02 | Requirements must use RFC 2119 language (MUST/SHOULD/MAY) | "The system will try to..." or "The system handles..." |
| AMB-03 | Numeric thresholds must have explicit units and bounds | "Response time should be fast" without ms/percentile; "maxLogEntries" without clarifying heap implications |
| AMB-04 | Conditional logic must cover all branches | "If request matches expectation, return response" (what about no match in mock mode vs proxy mode?) |
| AMB-05 | Error messages must specify exact content or format | "Display an appropriate error message" |
| AMB-06 | Time references must be absolute or relative with a defined anchor | "Recently created", "old records", "stale data" |
| AMB-07 | Quantities must be explicit | "Multiple retries", "a few seconds", "several items" |
| AMB-08 | MockServer-specific: Control plane vs data plane must be distinguished | Spec says "HTTP handler" without clarifying whether it's HttpState (control plane) or HttpActionHandler (data plane) |

## Lens 2 Principles: Incompleteness

| ID | Principle | Anti-Pattern |
|----|-----------|-------------|
| INC-01 | Every external dependency must have a failure mode scenario | Assuming the proxied origin server is always available |
| INC-02 | Every user input must have validation rules specified | Accepting expectation JSON without defining valid matcher types |
| INC-03 | Every state machine must show all transitions, including error states | Happy path only state diagrams for protocol detection |
| INC-04 | Data lifecycle must be complete: create, read, update, delete, archive | Specifying log entry creation but not ring buffer eviction or clear() |
| INC-05 | Concurrency model must be specified for shared resources | Assuming single-threaded access to HttpState or MockServerEventLog |
| INC-06 | Idempotency requirements must be stated for retryable operations | PUT /mockserver/expectation without duplicate detection |
| INC-07 | Timeout values must be specified for every blocking operation | "Forward request to origin" without specifying socketConnectionTimeout or maxSocketTimeout |
| INC-08 | Pagination must be specified for any list/query operation | Returning unbounded result sets from /mockserver/retrieve |
| INC-09 | Rate limiting must be specified for any public-facing endpoint | No throttling on control plane REST API |
| INC-10 | Migration strategy must be specified for schema/data changes | Adding new expectation fields without specifying backward compatibility or serialization |
| INC-11 | When a spec modifies a system with developer-facing tooling (CLIs, dashboards, test harnesses, setup scripts), the tooling layer MUST be included in the file inventory | Only considering server code while ignoring mockserver-client-java, JUnit rules, or dashboard UI |
| INC-12 | When a spec claims to cover "all instances of pattern X", the reviewer MUST search for semantic variants of the pattern | Accepting "all == checks" without also searching for switch/case, map lookups, or .equals() |
| INC-13 | MockServer-specific: Netty ByteBuf lifecycle must be explicit | Adding/modifying Netty handlers without specifying when buffers are released or retained |
| INC-14 | MockServer-specific: Ring buffer sizing must account for power-of-two rounding | Setting maxLogEntries=10000 without noting ring buffer allocates nextPowerOfTwo(10000) = 16384 slots |
| INC-15 | MockServer-specific: Protocol pipeline modifications must specify handler order | Adding new handler without specifying insertion point relative to PortUnificationHandler, SniHandler, MockServerHttpServerCodec |
| INC-16 | MockServer-specific: Memory bounds must specify both log entries AND expectations | Spec changes memory configuration without considering shared heap pool impact |

## Lens 3 Principles: Inconsistency

| ID | Principle | Anti-Pattern |
|----|-----------|-------------|
| CON-01 | The same concept must use the same name everywhere | "user ID" in stories, "userId" in tests, "user_id" in datasets |
| CON-02 | Traceability must be bidirectional with no orphans | Requirements without scenarios, scenarios without tests |
| CON-03 | Priority ordering must be consistent across dependencies | P0 feature depending on P3 prerequisite |
| CON-04 | Data types must be consistent across all references | String in one place, integer in another for the same field |
| CON-05 | Error codes/messages must be consistent across scenarios | Different error messages for the same failure condition |
| CON-06 | Acceptance criteria must not contradict each other | "MUST allow special characters" and "input MUST be alphanumeric" |
| CON-07 | MockServer-specific: Domain model serialization must round-trip | Spec adds field to Java model but doesn't update Jackson serializer/deserializer |
| CON-08 | MockServer-specific: Client library must mirror server API changes | Spec adds new expectation type or action but doesn't update mockserver-client-java |

## Lens 4 Principles: Infeasibility

| ID | Principle | Anti-Pattern |
|----|-----------|-------------|
| FEA-01 | Requirements must be achievable with the stated tech stack | Requiring Java 21+ features when MockServer targets Java 17 minimum |
| FEA-02 | Performance targets must be realistic for the architecture | Sub-millisecond response with templating engine evaluation |
| FEA-03 | Test scenarios must be reproducible in CI/CD | Tests requiring manual certificate trust, specific network conditions, or time-of-day |
| FEA-04 | Success criteria must be measurable with available tooling | Metrics requiring instrumentation that doesn't exist |
| FEA-05 | Ordering guarantees must be achievable in distributed systems | Assuming global ordering without coordination mechanism |
| FEA-06 | MockServer-specific: Dependency upgrades must not force the `jakarta` namespace until the javax→jakarta migration is scheduled | Accepting Spring 6.x, Jetty 10+, or jakarta.* namespace upgrades against the current `javax` codebase |
| FEA-07 | MockServer-specific: Netty version changes must preserve protocol detection | Upgrading Netty without verifying PortUnificationHandler compatibility |

## Lens 5 Principles: Insecurity (STRIDE)

| ID | Principle | Anti-Pattern |
|----|-----------|-------------|
| SEC-01 | Every entry point must specify authentication mechanism | Endpoints without auth requirements |
| SEC-02 | Every operation must specify authorization rules | "Authenticated users can..." without role/permission checks |
| SEC-03 | Every sensitive operation must produce an audit log entry | Clearing expectations without logging who/when |
| SEC-04 | Error responses must not leak internal details | Stack traces, internal IPs, or database schemas in error messages |
| SEC-05 | All inputs must be validated at the system boundary | Trusting expectation JSON without schema validation |
| SEC-06 | Secrets must never appear in logs, URLs, or error messages | API keys in query parameters, tokens in MockServerLogger output |
| SEC-07 | Data at rest and in transit must specify encryption requirements | Storing sensitive request bodies in log entries without encryption specification |
| SEC-08 | Session/token management must specify expiry and revocation | Tokens without TTL or invalidation mechanism |
| SEC-09 | Resource limits must be specified to prevent exhaustion | Unbounded request body size, no connection limits, no maxExpectations enforcement |
| SEC-10 | MockServer-specific: TLS certificate validation must be explicit | Trusting all upstream certificates in proxy mode without configuration option |
| SEC-11 | MockServer-specific: Control plane must be protectable | Adding control plane endpoint without respecting controlPlaneJWTAuthenticationRequired |
| SEC-12 | MockServer-specific: Template injection must be prevented | JavaScript/Velocity templates accepting user input without sandboxing or validation |
| SEC-13 | MockServer-specific: CORS headers must not weaken security | Setting Access-Control-Allow-Origin: * without justification |

## Lens 6 Principles: Inoperability

| ID | Principle | Anti-Pattern |
|----|-----------|-------------|
| OPS-01 | Every new component must specify health check endpoints | Services without liveness/readiness probes |
| OPS-02 | Every failure mode must specify an observable indicator | Failures that are silent or only visible in logs |
| OPS-03 | Rollback procedure must be specified or feature-flagged | Big-bang deployments with no rollback plan |
| OPS-04 | Structured logging must include correlation IDs | Log messages without request context |
| OPS-05 | Alerting thresholds must be specified for key metrics | Monitoring without actionable alerts |
| OPS-06 | Graceful degradation behaviour must be specified | Feature fails completely instead of degrading |
| OPS-07 | Configuration must be externalized, not hardcoded | Magic numbers, embedded URLs, inline credentials |
| OPS-08 | Startup and shutdown behaviour must be specified | No graceful shutdown, no dependency readiness checks |
| OPS-09 | MockServer-specific: Configuration properties must be documented | Adding new ConfigurationProperties field without updating consumer docs at jekyll-www.mock-server.com/mock_server/configuration_properties.html |
| OPS-10 | MockServer-specific: Default values must be justified | Changing default maxLogEntries formula without heap analysis |
| OPS-11 | MockServer-specific: Dashboard UI must reflect server state changes | Server-side change without corresponding WebSocket event or UI update |
| OPS-12 | MockServer-specific: Docker image changes must preserve environment variable contracts | Changing entrypoint script behavior without documenting MOCKSERVER_* environment variable impact |

## Lens 7 Principles: Incorrectness

| ID | Principle | Anti-Pattern |
|----|-----------|-------------|
| COR-01 | Business rules must match source-of-truth documentation | Spec contradicts GitHub issue, consumer docs, or existing code |
| COR-02 | Boundary values in test data must be mathematically correct | Off-by-one errors in ring buffer index calculations |
| COR-03 | Given preconditions must be achievable from a clean state | Acceptance scenarios assuming state that no scenario creates |
| COR-04 | Time zone and locale assumptions must be explicit | Assuming UTC, assuming English, assuming Gregorian calendar |
| COR-05 | Existing code behaviour assumed by the spec must be verified | Spec assumes an API returns X but it actually returns Y |
| COR-06 | Race conditions between concurrent operations must be identified | Two threads modifying HttpState simultaneously |
| COR-07 | When a spec references specific file paths, line numbers, function names, or code snippets, the reviewer MUST verify a representative sample (minimum 3 or 20%, whichever is larger) against the actual codebase. If ANY verification fails, flag ALL unverified claims as suspect | Trusting line numbers, method names, or code structure claims without opening the actual files |
| COR-08 | MockServer-specific: Module boundaries must be respected | mockserver-netty directly depending on test utilities from mockserver-core/test |
| COR-09 | MockServer-specific: Netty pipeline order must be correct | SslHandler added AFTER HttpServerCodec (TLS decryption must happen first) |
| COR-10 | MockServer-specific: ByteBuf reference counting must be balanced | Calling retain() without corresponding release(), or releasing buffers still in use |
| COR-11 | MockServer-specific: Ring buffer power-of-two invariant must hold | RingBufferSize calculation using non-power-of-two values (LMAX Disruptor requires 2^n) |
| COR-12 | MockServer-specific: Jackson serialization must handle all domain model fields | Adding field to Expectation without @JsonProperty or custom serializer |

## Lens 8 Principles: Overcomplexity

| ID | Principle | Anti-Pattern |
|----|-----------|-------------|
| CPX-01 | Every abstraction must have at least two concrete implementations or a stated reason to exist | Interface with one implementation "for testability" when a concrete type and simple test double would suffice |
| CPX-02 | Configuration options must correspond to values that will realistically change | Externalizing a retry count that has been 3 for five years and nobody has ever changed |
| CPX-03 | The number of architectural layers must be justified by the problem's complexity | Adding new service layer when logic belongs in existing HttpActionHandler |
| CPX-04 | Requirements must solve the current problem, not hypothetical future ones | "MAY support pluggable storage backends" when the only backend is in-memory |
| CPX-05 | Error handling complexity must match error likelihood and impact | Circuit breakers for an internal synchronous call that never fails |
| CPX-06 | Test infrastructure must not exceed the complexity of the code under test | Test factories, builders, and fixtures more complex than the production code they test |
| CPX-07 | The simplest solution that satisfies all stated requirements is the correct one | Introducing event-driven architecture when a direct method call achieves the same result |
| CPX-08 | Feature flags, toggles, and gradual rollout mechanisms must justify their maintenance cost | Feature flag for a feature that will never be toggled off after initial release |
| CPX-09 | New concepts (types, services, tables, queues) must each solve a distinct stated problem | Creating new matcher type when existing JsonPathMatcher or XPathMatcher already covers the use case |
| CPX-10 | Performance optimizations must target measured bottlenecks, not theoretical ones | Adding caching, connection pooling, or async processing without evidence of a performance problem |
| CPX-11 | MockServer-specific: Avoid premature Netty handler abstraction | Creating new ChannelInboundHandler subclass when logic fits in existing handler's channelRead() |
| CPX-12 | MockServer-specific: Templating engine choice must justify complexity | Adding new template engine (beyond existing Velocity/JavaScript) without demonstrating deficiency |
| CPX-13 | Comments must earn their place — an invariant, an external constraint, a "don't do the obvious thing because X", or public-API docs. Raise over-commenting as a finding; volume of explanation is not diligence (see `code-comment-discipline.md`) | Nine lines recounting a CI run, its throughput figures and what was tried and reverted, attached to one configuration line; added lines approaching half comment |

## Review Completeness Check

Before finalizing the review, verify:

- [ ] Every lens has been applied (or explicitly marked as not applicable with justification)
- [ ] Every finding has a specific section reference from the spec/code
- [ ] Every finding has a concrete, actionable recommendation
- [ ] Findings are classified by severity (CRITICAL, MAJOR, MINOR, OBSERVATION)
- [ ] No false reassurance language appears in the report ("looks good", "seems fine", "probably works")
- [ ] The STRIDE analysis covers every component/data flow in the spec
- [ ] The unasked questions section identifies genuine gaps, not rhetorical questions
- [ ] For code reviews: verified that referenced classes/methods/packages actually exist
- [ ] For code reviews: checked for Netty ByteBuf leaks (retain/release balance)
- [ ] For code reviews: verified module dependencies respect architecture (see docs/code/overview.md)
- [ ] For spec reviews: verified file inventory includes consumer docs, client library, and integration layer when applicable

## MockServer-Specific Review Triggers

These patterns in code or specs MUST trigger deep inspection:

| Pattern | Required Checks |
|---------|----------------|
| `ByteBuf`, `.retain()`, `.release()` | Verify reference counting is balanced, especially in error paths |
| `ChannelHandler`, `ChannelInboundHandler`, `ChannelOutboundHandler` | Verify pipeline order, protocol detection flow, and handler removal logic |
| `ConfigurationProperties.` | Verify default value calculation, consumer docs update, environment variable mapping |
| `MockServerEventLog`, `maxLogEntries`, `maxExpectations` | Verify ring buffer sizing (power-of-two), heap analysis, eviction logic |
| `HttpState`, `HttpActionHandler` | Verify control plane vs data plane separation, concurrency safety |
| `KeyAndCertificateFactory`, `NettySslContextFactory` | Verify certificate validation, expiry checks, CA chain verification |
| `@JsonProperty`, `ObjectMapper`, serialization | Verify round-trip serialization, client library update, backward compatibility |
| `pom.xml` dependency version change | Verify javax-compatibility (reject Spring 6+, Jetty 10+/12+, jakarta.* namespace until javax→jakarta migration is scheduled) |
| Control plane endpoint (`/mockserver/*`) | Verify JWT authentication enforcement, audit logging, input validation |
| Template evaluation (Velocity, JavaScript) | Verify input sanitization, sandbox enforcement, injection prevention |

## Finding Format

Every finding MUST follow this structure:

```
[PRINCIPLE-ID] Severity: CRITICAL|MAJOR|MINOR|OBSERVATION

Location: file/path/or/spec/section:line (or N/A for spec-level findings)

Finding: <Concise description of what is wrong>

Evidence: <Quote or reference from code/spec, or "verified in codebase" for existence checks>

Recommendation: <Specific, actionable fix>
```

### Example Finding

```
[COR-10] Severity: CRITICAL

Location: mockserver-core/src/main/java/org/mockserver/netty/proxy/ProxyHandler.java:142

Finding: ByteBuf released in success path but not in catch block, causing memory leak on exceptions

Evidence:
```java
try {
    ctx.writeAndFlush(buffer);
    buffer.release();  // Line 142
} catch (Exception e) {
    log.error("Failed to write", e);
    // Missing buffer.release() here
}
```

Recommendation: Add `buffer.release()` in finally block or use try-with-resources pattern
```

## Verdict

After applying all lenses and completing the checklist, return ONE of:

- **PASS** — All findings are OBSERVATION or MINOR with low risk; code/spec is ready
- **BLOCK** — One or more CRITICAL or MAJOR findings exist; code/spec must not proceed until fixed

Do NOT use "PASS with reservations" or similar hedging language. Either it passes or it blocks.

## Iteration Protocol

Adversarial review is **iterative** — the author fixes BLOCK findings and the
artefact is re-reviewed on a fresh context until it converges. The loop is
bounded. **One iteration = one review subagent invocation**: the initial review
is iteration 1, and each re-review after a fix is the next iteration.

- Each iteration **must** produce explicit findings (or an explicit PASS).
- Each MAJOR/CRITICAL finding **must** be addressed or **consciously
  dispositioned** (accepted with a recorded rationale) before the next iteration.
  Dispositioning anything above MINOR **requires user approval** — an agent must
  not self-approve findings to advance toward the cap.
- After any material change, **re-verify** (re-run the affected validations)
  before re-reviewing — fixes regress.
- Terminate when **either** the review returns PASS (no new major findings)
  **or** **8 review iterations** have completed — whichever comes first.
- If the **8-iteration cap** is reached **without** a PASS, do **not** proceed as
  if converged: **record the unresolved residual risk explicitly** — the
  outstanding findings and why they remain, in `docs/plans/<task>.local.md` or
  inline in the escalation message — and **escalate to the user**. The
  8-iteration ceiling is a hard cap. (Spec: `docs/operations/ai-sdlc-integration-spec.md` §14.5.)
- The **iteration count and time-to-PASS** are part of **rework cost** (§18.6 T10):
  record them in the decision-log telemetry block (`review_iterations`, `rework_s`
  — see [[decision-log]]) so review effort can be aggregated and tracked in the
  learning loop ([[metrics]]). High iteration counts argue for better first-pass
  quality (context/model); slow iterations argue for faster review cycles.
