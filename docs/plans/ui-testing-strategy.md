# UI Testing Strategy

## TL;DR

P0 (Playwright live-update row-readability test) has shipped, and since 2026-10-10 the CI
end-to-end step runs every dashboard area's spec (10 spec files, about 240 tests). Three small
items remain:

1. **P1** — Re-aim the existing jsdom auto-scroll test with a comment naming what it does not cover.
2. **P2** — Add a layer-boundary comment block near the top of `mockserver-ui/src/test-setup.ts`.
3. **P3** — Pin jsdom to the exact minor version that works and document the upgrade protocol.

---

## Current State

```mermaid
flowchart TD
    subgraph CI ["CI — pipeline-ui.yml"]
        UT[":jest: UI tests\n10 min\nnpm test"]
        E2E[":playwright: UI end-to-end\n30 min\nui-e2e.sh"]
    end

    subgraph jsdom ["Vitest + jsdom (196 files, 3084+ tests)"]
        U1["State / logic"]
        U2["Component render"]
        U3["User interactions\n(click, type, filter)"]
        U4["Perf guards\n(DOM count, render count)"]
    end

    subgraph pw ["Playwright / Chromium (10 files, ~240 tests)"]
        E1["Live stream, follow, scroll anchor"]
        E2["Mock, Verify, Observe areas"]
        E3["Resilience, Library, Shell areas"]
    end

    UT --> jsdom
    E2E --> pw
```

The Playwright harness (`mockserver-ui/e2e/`) is already production-grade:
`playwright.config.ts` boots the real runnable JAR via `start-mockserver.mjs`,
runs headless Chromium on the served dashboard against real REST and the real
WebSocket, and is a hard CI gate (fail-closed: non-zero exit on any failure or
zero tests found). Since 2026-10-10 it also fails if any listed spec ran no tests, if fewer than
236 tests ran, or if any test was skipped other than as `test.fixme`.

---

## The Blind Spot — What Each Layer Cannot See

| What the test exercises | jsdom | Playwright |
|-------------------------|-------|------------|
| React state and derived values | yes | yes |
| User interactions (click, type, keyboard) | yes | yes |
| API calls and error handling | yes (mocked fetch) | yes (real server) |
| CSS-computed values, `getComputedStyle` | partial (no calc(), no CSS vars with layout) | yes |
| `scrollTop`, `scrollHeight`, `offsetHeight` | no (always 0) | yes |
| Virtualisation mount/unmount | no (viewport = 0, every row mounts) | yes |
| A row scrolled off-screen being unmounted | no | yes |
| Live WebSocket arrival triggering a side-effect | no (WebSocket is mocked) | yes |
| Monaco editor, workers, web APIs | no (mocked to textarea) | yes |
| Focus restoration after DOM removal | partial (shim required) | yes |

**The Panel.tsx bug specifically required all three of the bottom rows
simultaneously**: scroll-driven unmount, AND live-data arrival changing `count`,
AND a real viewport so virtualisation actually fires. jsdom fails all three.

### Why the passing selection test was the most dangerous failure

A test asserting "the expansion state survives a live update" passed while the
bug existed — because state genuinely was preserved. The row was unmounted by
virtualisation after the auto-scroll moved the viewport, not because state was
lost. The test asserted a true implementation invariant at the wrong layer. The
user-visible invariant ("the row I opened is still on screen and readable") is
categorically different from "the expansion state boolean remains true in the
store". This distinction is the core of what needs to change.

---

## Where to Use Each Layer

```mermaid
flowchart LR
    Q1{"Does the test\nrequire a real\nlayout engine?"}
    Q2{"Does it involve\nWebSocket live\ndata arrival?"}
    Q3{"Does it require\na real server\ncontract?"}

    jsdom["Use jsdom / Vitest\n(fast, no infra)"]
    pw["Use Playwright\n(real browser + real server)"]

    Q1 -->|no| Q2
    Q1 -->|yes| pw
    Q2 -->|no| Q3
    Q2 -->|yes| pw
    Q3 -->|no| jsdom
    Q3 -->|yes| pw
```

The default is jsdom. The trigger to use Playwright is any one of: real layout
(scroll, viewport, virtualisation), live-update side-effects, or real server
contracts. A test that says "run everything in Playwright" adds 20+ minutes to
CI per test file; the discipline is to reach for it only when jsdom is
structurally incapable.

---

## Rationale and Trade-offs

**Why not add a layout stub to jsdom?** The `perf-domWeight.test.tsx` suite
already stubs `offsetHeight` to 600px to exercise the virtualised code path.
That works for counting DOM elements. It does not work for the Panel.tsx bug
because the bug is an interaction between scroll position (set by the effect),
the virtualisation engine reading `scrollTop` to decide which rows are in view,
and a user whose scroll position matters. Stubbing `scrollTop` is feasible but
the resulting test is fragile (it tests the stub's behaviour, not the real
scroll/virtualisation interaction) and breaks on any refactor of
`ProgressiveList`. Playwright costs one test file and catches the real
behaviour.

**Why not a mid-tier option (Storybook, Cypress component tests)?** The
dashboard's live-update path goes through a real WebSocket from a real server.
A component harness that mounts the component in isolation would need to fake
the WebSocket — which is exactly what the existing jsdom suite already does.
The gap is the full-stack interaction, not the component in isolation. Only the
Playwright harness closes it.

**Accepted limitation of the current Playwright suite:** it runs serially
(one worker, shared server state), is slow (JAR build + browser image, 30 min
timeout), and has one retry on agent loss. This is by design — the server has
global ring buffers and the tests depend on clean state (`PUT /mockserver/reset`
in `beforeEach`). Parallelism would require per-test server isolation
(containers) which is disproportionate at the current scale.

---

## What to Add

### P1 — Re-aim the existing jsdom auto-scroll test

**Files:** the test(s) in `mockserver-ui/src/__tests__/` that assert "expansion
state survives a live update".

Locate the test(s) that assert the expansion boolean holds after a store push.
These tests are not wrong; they prove the store does not reset state. The risk
is that they are the only coverage and give false confidence that the row stays
visible. Add a comment to each such test explicitly naming what it does NOT
cover:

```
// NOTE: this asserts state is retained in the store (correct and valuable).
// It does NOT assert the row stays visible on screen — that depends on
// virtualisation and scroll, which jsdom cannot model. The Playwright test
// in e2e/dashboard.spec.ts covers the on-screen invariant.
```

This is documentation, not code change, but it prevents the next contributor
from reading a passing test and concluding the bug class is covered.

### P2 — Layer boundary documentation in test-setup.ts

Add a comment block near the top of `mockserver-ui/src/test-setup.ts`
summarising the jsdom blind spots that are known from real bugs in this repo:

```
// jsdom DOES NOT MODEL:
//   - scroll position (scrollTop / scrollHeight / offsetHeight = 0)
//   - virtualisation mount/unmount (ProgressiveList renders all rows; see
//     perf-domWeight.test.tsx for the offsetHeight stub that enables windowing tests)
//   - live WebSocket arrival side-effects (WebSocket is globally mocked)
//   - real CSS calc() and getComputedStyle with layout (see jsdom shim below)
// For tests that require any of these, use the Playwright suite in e2e/.
```

### P3 — jsdom upgrade protocol (environment resilience)

jsdom 30.0.1 → 30.1.0 broke 109 tests (all `*Dialog.test.tsx`). `package.json` now allows
`^30.1.2`, and the shim in `test-setup.ts` recovers the suite, but it is fragile: it patches an internal
jsdom module path (`jsdom/lib/jsdom/living/helpers/focusing.js`) that is not a
public API and can disappear silently.

Recommended approach:

1. Pin jsdom to the exact minor version that works (currently `30.1.x`) in
   `mockserver-ui/package.json` using a pinned range, not `^`. Let Dependabot
   surface the upgrade as a PR.
2. When a jsdom minor bump PR arrives, check whether the shim is still needed:
   if the Dialog tests pass without it, remove it; if not, update the shim and
   explain the new internal path in the comment.
3. Document this in the Dependabot PR template as a known fragility point.

The nvm coupling (v22.21.1, Homebrew node v26 breaks rolldown) is a separate
issue. The `.nvmrc` should pin the exact Node version, and CI's `node:22` image
provides a stable reference. The local mismatch happens when developers use
Homebrew node; the current workaround (`unset NVM_DIR` before the build) is
documented in `docs/code/dashboard-ui.md` under the Local Development section.

---

## Cost and Sequencing

| Priority | Work item | Estimated effort | CI impact |
|----------|-----------|-----------------|-----------|
| P1 | Comment on existing jsdom auto-scroll tests | 1 hour | none |
| P2 | Layer boundary comment in test-setup.ts | 30 min | none |
| P3 | jsdom version pin + upgrade protocol | 1 hour | none (prevents future breakage) |

P1 and P2 are documentation that prevents future contributors from drawing the wrong
conclusion from a passing suite. P3 reduces the environmental fragility that
causes CI breaks on routine Dependabot bumps. All three are independent and can
land in any order.

---

## What This Plan Does Not Propose

- **Rewriting the existing 3,084 jsdom tests.** They have value. The problem is
  not that they exist but that they are the only layer.
- **Moving all interaction tests to Playwright.** That would add 20+ minutes to
  the critical path for every state/logic change. The split described above is
  defensible: jsdom for fast unit-level coverage, Playwright only for the tests
  that jsdom structurally cannot run.
- **A component harness (Storybook, Cypress CT).** Neither closes the gap that
  matters — the full-stack WebSocket → virtualisation → scroll interaction — and
  both add a third environment to maintain.
- **Eliminating the jsdom-upgrade shim.** The shim is the correct response to an
  internal jsdom change. The protocol in P3 is the improvement: ensure the shim
  is re-evaluated on each upgrade rather than silently accumulating.

---

## Appendix — Test Topology Reference

| Suite | Command | Environment | Server | Gate |
|-------|---------|-------------|--------|------|
| Unit / component (196 files) | `npm test` | jsdom (node:22 in Docker) | mocked fetch + mocked WebSocket | hard, 10 min |
| End-to-end (10 files, ~240 tests) | `npm run test:e2e` + `test:e2e:anchor` | headless Chromium (Playwright image) | real JAR in the Playwright container | hard |
| Benchmarks | `npm run bench` | jsdom | mocked | not CI-gated |
| Screenshots (docs site) | `npm run screenshots` | real browser (local) | real JAR | not CI-gated |

Key files:
- `mockserver-ui/vitest.config.ts` — jsdom config, coverage thresholds, exclude pattern for `e2e/`
- `mockserver-ui/src/test-setup.ts` — jsdom patches, Monaco mock, ResizeObserver stub
- `mockserver-ui/e2e/playwright.config.ts` — Playwright config, JAR topology, CI external-server mode
- `mockserver-ui/e2e/*.spec.ts` — real-browser tests for every dashboard area
- `mockserver-ui/e2e/scroll-anchor.pw.ts` — scroll-anchor / live-update row tests (P0, shipped)
- `mockserver-ui/e2e/start-mockserver.mjs` — JAR locator / builder for local Playwright runs
- `.buildkite/scripts/steps/ui-test.sh` — CI jsdom step
- `.buildkite/scripts/steps/ui-e2e.sh` — CI Playwright step (builds JAR, boots servers in the Playwright container, runs browser, checks the suite ran)
- `mockserver-ui/src/components/Panel.tsx` — auto-scroll effect fixed in `b00228d8d`
- `mockserver-ui/src/__tests__/perf-domWeight.test.tsx` — virtualisation DOM-count guard (includes offsetHeight stub)
