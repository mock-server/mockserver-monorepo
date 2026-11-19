---
name: pr-monitor
description: >
  Reports the build status of Dependabot and Snyk dependency upgrade PRs, and merges
  passing ones only when the user explicitly asks for merging. Use when the user says
  "monitor PRs", "watch builds" or "watch dependency PRs" (report only), or
  "auto-merge PRs", "merge passing PRs" or "merge when passing" (report and merge).

---

# PR Monitor Skill

## Purpose

Reports the build status of Dependabot and Snyk dependency upgrade PRs, and merges passing ones when the user has asked for that. Handles javax/jakarta compatibility validation.

**Watching is read-only.** "Monitor", "watch" or "check" PRs or builds means report only:
never merge. Merge only when the user's request explicitly asks for merging ("merge",
"auto-merge", "merge when passing"); only then pass `--merge` to the script. If unsure,
report and ask.

With `--merge`, the script re-checks the repository variable `RELEASE_IN_PROGRESS` before
every merge, using the same rule as the Dependabot auto-merge workflow: an epoch younger than
the repository variable `RELEASE_GATE_MAX_AGE_HOURS` (default 12 hours), a future value, a
value that is not an epoch, or any error reading the variable other than "not found" means
a release may be in progress, so the PR is reported as held and left unmerged; a merge
would land in the release unreviewed. An epoch at least that old is a stranded flag
from a failed release: the script says so and merges, and you should tell the user the
variable needs clearing.

## When to Use

- After creating/updating dependency upgrade PRs
- When dependency PRs are failing and you've pushed fixes
- When user says "monitor PRs" or "watch builds" (report only)
- When user says "merge when passing" or "auto-merge PRs" (report and merge)
- After fixing build issues for Dependabot/Snyk PRs

## Prerequisites

- Repository must be a git repository with GitHub remote
- `gh` CLI must be authenticated
- PRs must exist on GitHub
- Working directory must be the repository root

## Workflow

### 1. Identify PRs to Monitor

Get PRs from today or specific PR numbers:

```bash
# Get all PRs from today
gh pr list --json number,title,createdAt,author --jq '.[] | select(.createdAt | startswith("'$(date +%Y-%m-%d)'"))'

# Or use specific PR numbers provided by user
```

### 2. Check Current Status

For each PR, check:
- Build status (Buildkite, GitHub Actions)
- Java compatibility (if dependency upgrade)
- PR state (open, closed, merged)

Use the bundled `pr-monitor.sh` script (see Tool Usage below).

### 3. Handle Different States

**PASSING builds:**
- Report-only request: report it as passing; do not merge
- Merge request: merge only by running the script with `--merge`, which applies the
  author and release checks; never call `gh pr merge` directly

**PENDING builds:**
- Continue monitoring
- Report current status

**FAILING builds:**
- Check if failure is due to Java version incompatibility
- Report failure details to user
- Do NOT merge

**CLOSED/MERGED:**
- Skip and report status
- Remove from monitoring list

### 4. Monitor Loop

Poll every 2 minutes (120 seconds) for up to 2 hours:
- Check status of all pending PRs
- Merge any that have passed, only when running with `--merge`
- Report status changes
- Exit when no PR is still pending (passing, merged, failing or closed)

## Tool Usage

Use the bundled `pr-monitor.sh` script:

```bash
.opencode/skills/pr-monitor/pr-monitor.sh <pr_numbers_or_'today'>
```

Examples:
```bash
# Monitor all PRs from today
.opencode/skills/pr-monitor/pr-monitor.sh today

# Monitor specific PRs
.opencode/skills/pr-monitor/pr-monitor.sh 2001 2005 2006

# One-time check (no loop)
.opencode/skills/pr-monitor/pr-monitor.sh --check-once 2001

# Merge passing PRs, only when the user asked for merging
.opencode/skills/pr-monitor/pr-monitor.sh --merge 2001 2005 2006
```

Without `--merge` the script only reports; it never merges.

## Output Format

The script provides real-time status:

```
[2026-11-19 20:38:36] Checking PR status...
-------------------------------------------
  PR #2017: ✅ PASSING - not merged (report only; pass --merge to merge)
  PR #2001: ⏳ PENDING - Buildkite still running
  PR #2005: ❌ FAILING - Buildkite: FAILURE
  PR #2006: ✅ MERGED - <title>

Summary: ✅ 1 merged | 🟢 1 passing (not merged) | ⏳ 1 pending | ❌ 1 failing | 🚫 0 closed

Next check in 120s... (Iteration 1/60)
```

With `--merge`, a passing PR shows `✅ PASSING - Attempting to merge...` then `🎉 MERGED`,
or `⏸️  HELD - release in progress` while a release holds merges.

## Error Handling

**Cannot fetch PR status:**
- Verify `gh` authentication: `gh auth status`
- Check PR number exists: `gh pr view <number>`

**Merge fails:**
- Check if PR has merge conflicts
- Verify branch protection rules
- Check if PR requires reviews

**jakarta-namespace incompatibility:**
- Do NOT merge
- Report to user which dependency forces the jakarta namespace (e.g. Spring 6, Jetty 10+, Tomcat 10+)
- Suggest closing the PR until the javax→jakarta migration is scheduled

## Integration with Dependabot

After fixing build issues (like in today's session):

1. Push fixes to master
2. Trigger Dependabot rebase: `@dependabot rebase` in PR comments
3. Start monitoring: `.opencode/skills/pr-monitor/pr-monitor.sh today`
4. If the user asked for merging, add `--merge` so the script merges PRs as their builds pass

## Notes

- The script uses `--squash` merge by default (matches MockServer convention)
- Merges only with `--merge`, and only Dependabot/Snyk PRs
- Maximum monitoring time: 2 hours (set `MAX_ITERATIONS` at top of `pr-monitor.sh`, default 60)
- Check interval: 2 minutes (set `SLEEP_INTERVAL` at top of `pr-monitor.sh`, default 120)
- Exits successfully when no PR is still pending (all passing, merged, failing or closed)
- Exits with error code 1 if timeout reached with pending PRs
- GitHub API rate limit: authenticated `gh` CLI allows 5,000 requests/hour; each PR check uses ~2-3 API calls, so monitoring up to ~20 PRs at 2-minute intervals is well within limits

## Example Session

```
User: "monitor the PRs raised today and merge them when builds pass"

Agent: I'll monitor all dependency PRs from today and merge them when builds pass.

[Runs: .opencode/skills/pr-monitor/pr-monitor.sh --merge today]

[Output shows monitoring progress...]

Agent: All PRs processed:
- PR #2017: ✅ MERGED
- PR #2001: ✅ MERGED  
- PR #2005: ✅ MERGED
- PR #2006: ✅ MERGED
```
