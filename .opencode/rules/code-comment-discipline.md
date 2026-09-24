# Code Comment Discipline

## Purpose

Code comments accumulate. Every investigation that touches a line is tempted to
leave its findings there, and the result is source files where the narrative
outweighs the code and the signal is buried. Comments cost maintenance too: a
comment describing a past experiment goes stale the moment anything changes, and
nothing fails when it does.

This rule sets what a comment must earn, and where the rest of the material
belongs.

## The Test

A comment is justified only if a reader **of that code** would otherwise get it
wrong. In practice that means one of:

- a **non-obvious invariant** the code relies on but cannot state
- a **constraint from outside the file** (a protocol limit, an OS ceiling, an
  upstream bug, an API that must be called in a particular order)
- a **"don't do the obvious thing, because X"** — the single most valuable kind,
  because it stops the next person reverting a deliberate choice
- **Javadoc / docstrings on public API** — documentation, not commentary

If a comment does none of those, delete it. Code that needs a paragraph to
explain what it does usually needs better names, not a paragraph.

## Never Put In A Comment

- **Build, run, or ticket narrative** — CI build numbers, measured throughput or
  latency figures, heap sizes, "build #416 showed…", "this was found when…"
- **What was tried and reverted**, and why that experiment was inconclusive
- **Argument or justification aimed at a reviewer** rather than at a future reader
- **Restatements of the line below it**
- **Historical defaults** and the story of how the current value was reached

All of that is real and worth keeping — it just belongs somewhere that ages
gracefully and is read deliberately:

| Material | Home |
|---|---|
| Why this change, what it fixes, what was verified | commit message |
| User-visible behaviour or default change | `changelog.md` |
| Measurements, experiments, inconclusive results | `docs/plans/*.md`, `docs/code/*.md` |
| How a property behaves, what values mean | Javadoc + `jekyll-www.mock-server.com/` |
| Architecture and rationale | `docs/code/`, ADRs |

## Size

- **No comment block over ~6 lines.** Past that, it is documentation — move it to
  `docs/` and leave a one-line pointer if the link genuinely helps. **Javadoc and
  docstrings on public API are exempt** (see The Test): an SPI interface that is mostly
  Javadoc is correct as it stands.
- **Keep added comment lines well under 25% of added lines** in a change. A diff
  approaching half comments is a signal to cut, not a sign of thoroughness.
- Shell and CI scripts are **not** exempt. They attract narrative the most.

## Worked Example

A single `ServerBootstrap` option carried nine lines recounting a perf run, the
heap figure it died at, that the experiment was confounded, and what that implied
about shipping. None of it helped a reader of that line. It reduced to four:

```java
// Accept-queue depth, configurable via mockserver.soBacklog. The 1024 default is
// deliberate: a deeper queue admits connections the server may not be able to
// serve, so it can act as backpressure rather than as a ceiling. Raising it also
// needs net.core.somaxconn raised to match.
.option(ChannelOption.SO_BACKLOG, configuration.soBacklog())
```

The invariant and the "don't just raise this" warning survive. The run narrative
moved to the commit message, where it is still fully available and cannot go
stale against the code.

## Applies To

Every agent writing code, and every code review. A reviewer should raise
over-commenting as a finding in its own right — the same way they would raise
dead code — rather than treating volume of explanation as diligence.

See also `.opencode/rules/coding-principles.md` (§3 Surgical Changes: don't
"improve" adjacent comments) and `.opencode/rules/documentation-style.md` (how the
prose that gets moved out should be structured).
