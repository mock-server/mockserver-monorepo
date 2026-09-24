# Comment Hygiene Sweep

## Outcome

Source files across the repo carry investigation narrative in comments — CI build
numbers, measured throughput figures, accounts of what was tried and reverted. It
went in one commit at a time and is now dense enough to hurt readability, and it
goes stale silently because nothing fails when the code moves on.

`.opencode/rules/code-comment-discipline.md` stops new narrative arriving.
**This plan covers the historical backlog**, which the rule does not touch.

Not started. No deadline — this is hygiene, not a defect.

## What "too much" means here

The rule's test: a comment earns its place only if a reader **of that code** would
otherwise get it wrong — a non-obvious invariant, an external constraint, a
"don't do the obvious thing, because X", or Javadoc on public API.

The material being removed is not worthless; it moves:

| Material | Moves to |
|---|---|
| Why the change, what was verified | already in the commit message — just delete |
| Measured figures, experiments, inconclusive results | `docs/plans/performance-programme.md` or `docs/code/` |
| Behaviour of a property or API | Javadoc + `jekyll-www.mock-server.com/` |

## Scope, worst first

Ranked by comment share of file (files ≥100 lines). **Density alone is not the
signal** — an SPI interface that is mostly Javadoc is correct as it stands. Each
file needs the rule's test applied, not a threshold.

| File | Comment share | Likely verdict |
|---|---|---|
| `.buildkite/scripts/steps/java-cloud-store-test.sh` | 74% | sweep — CI scripts attract narrative |
| `mockserver-testing/.../test/DockerAvailability.java` | 73% | partly justified — the probe's hazard is genuinely non-obvious |
| `.buildkite/scripts/steps/java-async-broker-test.sh` | 70% | sweep |
| `mockserver-core/.../state/StateBackend.java` | 68% | likely fine — SPI Javadoc |
| `mockserver-performance-test/k6/lib/config.js` | 66% | sweep — carries the most run narrative |
| `mockserver-core/.../state/KeyValueStore.java` | 64% | likely fine — SPI Javadoc |
| `mockserver-netty/.../InFlightRequest.java` | 60% | assess |
| `mockserver-netty/.../proxy/CompositeOriginalDestinationResolver.java` | 59% | assess |
| `mockserver-core/.../socket/tls/KeyAndCertificateFactory.java` | 59% | assess |
| `.buildkite/scripts/steps/perf-test-guard.sh` | 58% | sweep |

Regenerate the ranking before starting — it will have moved:

```bash
for f in $(git ls-files '*.java' '*.sh' '*.js' | grep -v node_modules); do
  tot=$(wc -l < "$f"); [ "$tot" -lt 100 ] && continue
  c=$(grep -cE '^[[:space:]]*(//|#|\*|/\*)' "$f" || true)
  [ "${c:-0}" -gt 0 ] && echo "$((100*c/tot))% $c/$tot $f"
done | sort -rn | head -40
```

## Approach

- **Comments only.** No behaviour change in the same commit, so the diff is
  reviewable by reading it.
- One commit per area (CI scripts / k6 / netty / core), not one big sweep.
- Where narrative is worth keeping, move it in the same commit — do not delete
  a measurement that exists nowhere else.
- `.buildkite/**` is control-class: gated approval, not autonomous commit.

## Risk

Low, with one real trap: a comment that looks like narrative may be the only
record of a **deliberate** choice, and deleting it invites someone to undo that
choice later. When in doubt, compress to the constraint and the warning rather
than removing the block — the `SO_BACKLOG` example in the rule shows the shape.

## Done when

The ranking above holds no file whose comments fail the rule's test, and
`docs/plans/comment-hygiene-sweep.md` is deleted in the commit that finishes it.
