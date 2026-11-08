# UI competitor-inspired feature ideas — residue after the 2026-07 build

**Outcome.** All six items from the original backlog shipped in 2026-07, and the last deferred
sub-feature (per-workspace MockServer targets) and the review follow-ups (`status:` on the
verification scope, two-browser-tab workspace persistence) shipped in 2026-10, and the four
follow-ups found while building workspace targets shipped in 2026-11. What remains: two
instructions that turned out to be wrong and must not be retried, one engine constraint, and some
low-priority notes.

---

## Do not retry: chaos operator search — REFUTED

The original plan said to add an `OperatorSearchField` to the chaos host-select. **This is
wrong and was deliberately not done.**

`ServiceChaosRegistry.get(host)` is `byHost.get(normalizeHost(host))` on a
`ConcurrentHashMap` — trimmed, port-split, lower-cased, **exact key**, no pattern matching
anywhere in the class. `TcpChaosRegistry` is the same shape, and both call sites
(`HttpActionHandler.java:529`, `TcpChaosHandler.java:132`) are exact-key. Meanwhile the
shared DSL registers `host` with `glob: true` and an advertised example of
`host:*.example.com`, which `OperatorSearchField` renders into the placeholder and help
tooltip.

So the swap would have offered a wildcard the server stores as a literal key that can never
fire — precisely the "operators that silently do nothing in a given context" failure the
plan itself warned about. The chaos panel keeps a plain Host field and now **rejects**
wildcards, pasted `host:` operators, URL schemes and paths on all four entry points
(HTTP form, Quick Chaos, TCP form, and each chaos-experiment stage).

Making this viable would require pattern matching in the registry itself — a server-side
change with its own design questions (precedence between an exact and a glob entry, cost per
request), not a UI change.

---

## Do not retry: planting cookies via `document.cookie`

Tried and withdrawn while building inline execute-and-show-response: an unescaped `; domain=`
in a matcher value created a domain-scoped cookie the cleanup could not delete, which survived
and leaked to sibling subdomains. The dashboard names cookie matchers as unexercisable from the
UI rather than retrying this. Revisiting this needs a same-origin-only design with fail-closed
name/value validation; it is not worth it for a convenience affordance.

---

## Note: network-condition presets set one fault, not a combination

`TcpChaosHandler.channelRead` is first-match-wins with an early return per branch, documented as
contract in `TcpChaosProfile.java` and `chaos_testing.html`. A preset carrying both a latency and
a bandwidth value would display a number the engine never applies, so presets set exactly one
fault, enforced by a discriminated union and a test citing the handler's order. **If the engine
ever composes faults, that test breaks deliberately** — at which point combination presets become
possible.

---

## LOW / trivial notes (still unshipped, still low priority)

- **List virtualization** — `LogPanel.tsx`, `SessionInspector.tsx` and `AuditPanel.tsx` use plain
  MUI lists with no windowing. This only matters if the server-side WebSocket cap (~100 items)
  is raised. Do not virtualize preemptively.
- **Monaco idle prefetch** — `JsonEditorLazy.tsx` loads Monaco on first use with no
  `requestIdleCallback` prefetch. Marginal, since it is already behind a lazy import.
- **`ConfigurationDialog`** edits 17 curated properties plus 3 bespoke controls (20 total) out
  of ~100. This is deliberate: only
  properties safe to change at runtime and meaningful to a typical user are exposed.

---

## Explicitly rejected — do not reopen

**Pinned / recent view tabs** — implemented in `d536433f1` and deliberately reverted in
`d55d7077d` because labelled tabs in the app bar's flexible nav region stopped the bar
fitting at typical widths. This is a closed decision.

The workspace tab bar (Multi-tab workspaces) is a different concept and respects that constraint: it is its
own row below the app bar, does not exist until a second workspace does, and costs the app
bar a single bare icon button in the fixed utility cluster.
