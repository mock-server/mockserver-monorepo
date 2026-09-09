# Worktree Workflow — Default Per-Session Isolation With Rebase Lock

## Rule

Every **independent session** — the primary interactive session, any
parallel Claude/opencode window, and every long autonomous run — works
inside its **own dedicated, freshly-created git worktree** on a
**local-only branch**, not the main checkout. Start in a worktree (via
`/worktree`) at session start. **This holds even when the session makes
no changes** — read-only investigation, analysis, and review work in a
worktree too; the rule is *no work runs in the bare main checkout*, not
*only change-making work is isolated*.

**One session, one worktree — never reuse or adopt another session's.**
Each independent session **creates its own new worktree** and works only
there. A session **MUST NOT** start work in a worktree it did not create,
even if one already exists. Two sessions sharing a single tree overwrite
each other's uncommitted files and can `git rebase`/`reset` each other's
commits away — this has actually happened: a shared tree silently dropped
a committed, gate-passed unit that was only recovered from the reflog. The
`.tmp/active-worktree` pointer is a **resume marker for the session that
wrote it**, so it is safe to reuse *within the same session* (to resume
after a restart), but a **pre-existing pointer left by a different session
must never be treated as "already in worktree mode"** — verify it is
*yours* (you created that worktree/branch this session). The per-session
`SHORT_ID` embedded in the recorded path/branch is that ownership signal:
a resuming session recognises the ID it generated, and anything else is
another session's. If in doubt, create a fresh one — a unique per-session
`SHORT_ID` keeps worktrees and branches from ever colliding. Changes return to `master` only via a
gated **rebase + fast-forward** using `flock` to serialize concurrent
rebases — **never a merge commit** (see *Linear History — No Merge Commits* below).
Isolation-by-default is what lets concurrent sessions operate on the
same repo without stepping on each other, and matches the AI-in-SDLC
spec (`docs/operations/ai-sdlc-integration-spec.md` §8.3).

**Delegate, don't do — and delegating is how routing happens.** The
primary session's job is to *orchestrate* (see [[operating-model]] and
[[subagent-routing]]): it should hand almost all execution —
implementation *and* investigation — to subagents rather than doing it
inline, because a subagent is where the correct **model, temperature,
and reasoning effort** are selected for the task. Those subagents are
**helper subagents** and so share this session's worktree (next
paragraph); the point is that the work still lands in *this* worktree,
just run by a right-sized subagent.

**Helper subagents are the one deliberate exception.** A subagent
spawned by a primary via the `Agent` tool **shares the primary's tree**
and is *not* isolated — this is required for correctness, not
convenience: helper subagents (reviewers, investigators) read the
primary's **uncommitted in-flight diff**; a worktree branched off
`origin/master` would see only committed state and miss the very changes
they exist to analyse, silently breaking the commit gate chain.
Isolation is **between independent sessions, not within one**.

## Linear History — No Merge Commits

**`master` is kept a strictly linear history.** A worktree branch returns
to `master` **only** by being **rebased onto the current `master` tip and
fast-forwarded** — i.e. `git rebase origin/master` then a fast-forward
`git push origin HEAD:master`. The rebase replays the worktree's commits on
top of `master`, so the push is always a fast-forward and **no merge commit
is ever created**. This is safe precisely because worktree branches are
**local-only and unpushed** (§8.3 of the spec) — rebasing only rewrites
commits no one else has.

**Forbidden — anything that creates a merge commit on `master`:**

- `git merge <branch>` into `master` (including `git merge --no-ff`)
- a non-rebasing `git pull` (always use `git pull --rebase`)
- pushing an intermediate **"integration branch"** that several worktree
  branches were `git merge`-d into. When **multiple** parallel worktrees are
  ready, rebase them **one at a time** under the merge lock — each onto the
  result of the previous — so the final history is a flat sequence of each
  unit's commits, not a fan of merges. (Earlier parallel-closeout runs used
  integration branches and left merge commits in history; that pattern is
  retired.)

The goal is a clean, bisectable, easy-to-follow history. Platform-side, the
repository **should** also enable GitHub branch protection's
*Require linear history* on `master` to enforce this (it rejects any push
that would introduce a merge commit). See `[[git-safety]]` for the
destructive-command guardrails that interact with this.

## Beyond the Filesystem — Shared State (§8.5)

Worktree isolation covers the **filesystem only**. Where parallel work touches
shared mutable resources *outside* the tree — databases, cloud infrastructure
(AWS), CI (Buildkite), container registries (Docker Hub), Secrets Manager,
ticket systems, message queues — those have **no worktree to isolate them**, so
concurrent mutation can still corrupt shared state. Apply the same
anti-concurrent-mutation discipline: **partition** the resource per session,
**lock**/serialise mutations (as the rebase lock does for `master`, and as
Terraform's DynamoDB state lock does), or target **dedicated non-production**
instances. Identify such resources during decomposition ([[operating-model]]
Decompose); their contention risk **feeds the dynamic concurrency limit**
([[operating-model]] Parallelism Limits, §8.2); and their side effects must be
**replay-safe** (idempotent or guarded — [[decision-log]] reproducibility). The
serialisation this forces is recorded as a `serialisation.*` cause in telemetry
([[metrics]] §18.7).

## When To Use This

| Situation | Use worktree? |
|-----------|---------------|
| **Primary interactive session** doing substantive work | **Yes (default)** — start in a worktree via `/worktree`. (This previously stayed in the main checkout for IDE visibility; that exception is gone now that IntelliJ integration has been dropped.) |
| **Primary session doing read-only work** (investigation, analysis, answering a question, reviewing) that makes no changes | **Yes** — still a worktree. The rule is *no work in the bare checkout*; isolation is not contingent on whether files change. The merge ceremony simply has nothing to merge (skip to cleanup). |

> **Enforced, not just stated.** `.opencode/scripts/check-bare-checkout-write-hook.sh` is wired as a
> `PreToolUse` hook in `.claude/settings.json` and refuses an edit whose target resolves inside the main
> checkout instead of a linked worktree. It exists because this mistake is silent: the main checkout is
> usually on an older commit, so the edit is made against stale content, is invisible to the session's own
> verification, and another session can rebase it away. The hook fails open and only covers the edit tools —
> a write performed through Bash still gets through — so it narrows the mistake rather than removing it.
| **Subagents spawned from the main session** via the `Agent` tool | **No separate worktree — share the spawning session's checkout.** Helper subagents read/analyse in-flight work (uncommitted edits the primary just made). A worktree based on `origin/master` would only see committed state and miss the live changes — and would break the review gate, which reviews the primary's uncommitted diff. They are still "in a worktree" — the primary's. |
| **A second, independent Claude/opencode window** for parallel work on the same repo | **Yes** — that session invokes `/worktree` at start. Each independent session gets its own worktree |
| Long autonomous task (`/loop`, `/schedule`, "go work on X and come back when done") | **Yes** — invoke `/worktree` at session start |
| Trivial one-off (typo, comment, doc one-liner) in a quick session | **Yes — still a worktree** (cheap: it shares the `.git` object store). What scales down for a trivial change is the *merge ceremony* (the 4-gate chain), not the isolation — see [[operating-model]], Scale The Ceremony |

**Key principle**: worktrees provide isolation **between independent sessions** (different Claude windows / autonomous runs), not **within a session** (a primary + its helper subagents). Helper subagents inherit the primary's filesystem state so they can see its uncommitted work.

## Workflow

```
1. /worktree            ──→  Agent creates worktree, switches CWD into it
2. Agent makes changes        (commits as it goes, all on the worktree branch)
3. Agent runs tests           (verification gate 1)
4. Agent runs lint/checkstyle (verification gate 2)
5. Agent spawns review-final  (verification gate 3)
6. Agent shows diff summary    (verification gate 4 — summarise & proceed)
7. flock + rebase + cleanup   (atomic merge to master)
```

### Step 1 — Create the worktree

```bash
SHORT_ID="$(date +%Y%m%d-%H%M%S)-$(uuidgen | head -c 6)"
WORKTREE_DIR=".worktrees/agent-${SHORT_ID}"
BRANCH="agent/${SHORT_ID}"

# Ensure master is current
git fetch origin master --quiet
git worktree add --quiet -b "${BRANCH}" "${WORKTREE_DIR}" origin/master
cd "${WORKTREE_DIR}"
```

`.worktrees/` is gitignored (add to `.gitignore` if absent). The
worktree shares the `.git/` object store with main, so disk cost is
minimal. The agent records the worktree path in `.tmp/active-worktree`
so a session resumption can find it again.

### Step 2 — Work in the worktree

All edits, commits, builds, tests happen inside `WORKTREE_DIR`. The
agent commits incrementally on the worktree branch (no rebase to master
yet).

#### Activity recording for `/agent-status`

When multiple agent sessions run in parallel, the user can run
`/agent-status` to see a table of all active worktrees. To surface
**what each agent is currently doing** in that table, write a short
one-line description to `.tmp/agent-activity` inside the worktree
whenever the focus changes:

```bash
mkdir -p .tmp
echo "Running mvn verify on mockserver-netty" > .tmp/agent-activity
# ... later ...
echo "Reviewing diff for commit" > .tmp/agent-activity
```

The convention is intentionally lightweight: a single line, free
text, overwritten on each update. No state schema, no JSON. The
dashboard truncates to 32 characters so keep the message tight.
If the file is absent, the dashboard shows `(idle)` — the agent
need not write it, but doing so makes parallel work observable.

### Steps 3–6 — Gates (run by `/worktree-merge`)

All four must pass; any failure stops the merge and leaves the worktree
intact for inspection.

**Gate 1 — Tests.** Run targeted Maven tests for the modules the
worktree touched, plus the full mockserver-core suite (always run, it's
the blast-radius gate):

```bash
CHANGED_MODULES="$(git diff --name-only origin/master... \
    | sed -n 's|^mockserver/\([^/]*\)/.*|\1|p' | sort -u)"
./mvnw verify -pl :mockserver-core \
    $(printf ' -pl :%s' ${CHANGED_MODULES}) \
    -DforkCount=1 -DreuseForks=false -fae \
    -Djacoco.skip=true
```

**Full module, not just the unit's test.** `-Dtest=<ClassName>` scoping
runs only one test class and misses related/dependent tests in the same
module that the change may have broken. Always run the **full module
test suite** (`./mvnw test -pl <module>`) as the merge gate; use
`-Dtest=` only for rapid iteration DURING development, not as a
substitute for the pre-merge run.

**Concurrency limit.** Launch at most **2 `isolation:worktree` agents
in parallel** — more creates disk and CPU contention that causes
spurious test failures and build timeouts.

**Gate 2 — Lint / checkstyle / type checks.** Maven's `validate` phase
already runs checkstyle. For frontend changes in `mockserver-ui` or
`mockserver-client-node`, also run `npm run lint` and `npm run build`
(strict tsc).

**Gate 3 — Adversarial review.** Spawn the `review-final` agent on the
worktree's diff vs master. Block on BLOCK verdict, proceed on PASS.

```
Agent(
    subagent_type="review-final",
    description="Adversarial review for worktree merge",
    prompt="Review the diff from `git diff origin/master...HEAD` in this worktree. Verdict must be PASS or BLOCK. Focus areas: correctness, security, MockServer conventions, missing tests."
)
```

**Gate 4 — Summary & proceed.** Under the [[operating-model]] (DVRR),
gates 1–3 (tests, lint, adversarial review PASS) are the authority to
merge — they replace human pre-approval. Show the diff summary (`git
diff --stat origin/master...`, list of changed files, gate-1/2/3
results), then **proceed automatically** to the merge. A user can
interject at any point to halt or amend. This gate is **fail-closed**:
if any of gates 1–3 did not return a clean PASS, do NOT merge — leave
the worktree unmerged for inspection.

### Step 7 — Atomic merge via flock

**Pre-merge scope check.** Before acquiring the lock, confirm ONLY this
session's commits are present on the branch:

```bash
git log --oneline origin/master..HEAD
```

If commits from another session appear (two sessions inadvertently
shared a tree), do NOT merge — cherry-pick only this session's commits
onto a fresh branch. Two sessions sharing a tree have silently rebased
away gate-passed commits that were recovered only via reflog.

```bash
# Respect the operator halt before any reintegration (see [[operator-halt]]).
.opencode/scripts/check-halt.sh || { echo "operator halt engaged — not merging"; exit 1; }

# Resolve the SHARED .git common dir so every worktree contends on the SAME
# lock — in a linked worktree `.git` is a file, so a relative `.git/…` path
# resolves per-worktree and the mutex would not actually serialise.
LOCK_FILE="$(git rev-parse --git-common-dir)/agent-rebase.lock"
flock --timeout 300 "${LOCK_FILE}" bash -c '
    set -euo pipefail
    git fetch origin master --quiet
    git rebase origin/master   # rebase (never merge) — may resolve interactively if conflicts
    git push origin HEAD:master   # always a fast-forward after the rebase; no merge commit
'
```

5-minute (`--timeout 300`) wait if another agent holds the lock. If
timeout exceeded, fail with a clear error: *"Rebase lock held for >5m
by another session — retry in a few minutes or check `lsof
"$(git rev-parse --git-common-dir)/agent-rebase.lock"` for the holder."*

**Post-push changelog verification.** `changelog.md` uses a union merge
driver that silently drops a bullet added in this session when a
`Rebasing (n/n) skip` line appears during rebase. After any
rebase+push that touched `changelog.md`, verify the bullet actually
landed:

```bash
git show origin/master:changelog.md | grep -F "<your bullet text>"
```

If the bullet is absent, re-add it with a single-file fixup commit and
push immediately.

**Re-verify the integrated result (spec §8.4).** If the rebase pulled in
other units' commits (master advanced since this branch was cut), re-run the
test gate (Gate 1) against the integrated tip **before** cleanup — defects can
emerge from the *combination* even when each unit passed in isolation. A clean
fast-forward (no intervening commits) needs no re-run. Record the lock wait as
`serialisation.merge_lock_s` (and a conflicting rebase as
`serialisation.contention_s`) in the unit's decision-log telemetry block
([[decision-log]], §18.7).

`flock` is POSIX, present by default on macOS and Linux. On older macOS
without `flock`, fall back to `mkdir`-based mutex (atomic on POSIX):

```bash
LOCK_DIR="$(git rev-parse --git-common-dir)/agent-rebase.lockdir"
while ! mkdir "${LOCK_DIR}" 2>/dev/null; do
    [ "$(($(date +%s) - START))" -gt 300 ] && { echo "Lock timeout"; exit 1; }
    sleep 2
done
trap "rmdir ${LOCK_DIR}" EXIT
# ... rebase + push ...
```

### Step 8 — Cleanup

```bash
cd "$(git rev-parse --show-toplevel)/.."  # navigate to the parent of the worktree directory
git worktree remove "${WORKTREE_DIR}" --force
git branch -D "${BRANCH}"
rm -f .tmp/active-worktree
```

## Concurrency Examples

### Two agents working in parallel

- Agent A creates `.worktrees/agent-20260528-141500-abc123`
- Agent B creates `.worktrees/agent-20260528-141512-def456`
- Both edit files independently in their own worktrees — no interference
- A finishes first, acquires `flock`, rebases onto master, pushes, releases lock
- B finishes second, acquires `flock` (waits if A still rebasing),
  rebases onto **updated** master (includes A's changes), pushes
- If B's rebase finds conflicts with A's changes, B resolves them
  inside the worktree before pushing

These are **serialisation-recording sites** for the parallelism metrics (§18.7,
[[metrics]]): B's wait for the lock is `serialisation.merge_lock_s`, and a
conflicting rebase is `serialisation.contention_s` — recorded in B's decision-log
telemetry block ([[decision-log]]) so the dominant reason parallelism is lost can
be ranked.

### Lock contention timeout

- Agent C tries to rebase while Agent D's rebase has been running >5m
  (something pathological — e.g., D is blocking on an interactive
  conflict resolution)
- C's `flock --timeout 300` exits non-zero
- C surfaces a clear message and stops; user investigates D's worktree

## Failure Modes

| Failure | Outcome |
|---------|---------|
| Gate 1 (tests) fails | Worktree preserved; agent shows test failures; rebase blocked |
| Gate 2 (lint) fails | Same — fix lint and re-run merge |
| Gate 3 (review-final) returns BLOCK | Worktree preserved; agent shows review verdict; user decides |
| Gate 4 (a gate 1–3 not a clean PASS, or user interjects) | Worktree preserved; merge halted; user keeps the diff to iterate |
| flock timeout | Worktree preserved; agent reports lock holder; retry later |
| Rebase conflict | Worktree preserved; agent attempts resolution or hands back to user |
| Two sessions share one worktree (a session reused another's tree / stale `.tmp/active-worktree`) | Uncommitted work overwritten; one session's `rebase`/`reset` can drop the other's committed unit. **Recover from the reflog** (`git reflog`), re-`cherry-pick` the lost commit, re-verify. Prevent by creating a fresh per-session worktree and only resuming a pointer *this* session wrote (see *Rule*). |

The invariant: **no failed merge ever destroys work**. The worktree is
deleted only after a successful push. **Correlated invariant:** a session
only ever operates in a worktree it created — reusing another session's
tree is the one way in-flight or committed work *can* be lost, so it is
prohibited (see *Rule*).

## What This Replaces (Partially)

The "Parallel Session Safety" section in `AGENTS.md` lists rules that
exist because multiple sessions might step on each other in a shared
checkout:

- "Stage explicit paths only (never `git add .`)" — still good practice
- "Re-read files before editing" — still good, race conditions remain
- "Commit only files changed in this session" — still good
- "Run `git pull --rebase` before push" — obsolete inside a worktree
  (the merge step does this implicitly under the lock)

When using `/worktree`, the rebase-lock makes the last point automatic.
The first three remain good hygiene.

## Open Questions / Future Work

- **A real script wrapper.** The bash snippets above should be packaged
  as `scripts/agent-worktree-create.sh` and `scripts/agent-worktree-
  merge.sh`. Today the agent inlines them; later they become reusable.
- **Per-module test selection in Gate 1.** Currently runs core + touched
  modules. Could be smarter by parsing the dependency graph.
- **Diff size cap on Gate 4.** For very large diffs the summary
  presentation is awkward. Consider a "diff summary + risk score"
  presentation rather than raw diff dump.
