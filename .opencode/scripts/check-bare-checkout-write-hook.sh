#!/usr/bin/env bash
# Claude Code PreToolUse guard: refuse edits to the MAIN checkout.
#
# Every session is supposed to work in its own linked worktree (see
# .opencode/rules/worktree-workflow.md). An agent that edits the main checkout
# instead is a silent hazard rather than a loud one: the main checkout usually
# sits on an older commit, so the edit is made against stale content, it is
# invisible to the session's own verification, and a concurrent session can
# rebase or reset it away. This repository has lost committed, gate-passed work
# to that exact mistake once already, recovered only via the reflog, and two
# agents made the same mistake again in a single session (both caught, neither
# by a control).
#
# HOW IT DECIDES, without hard-coding any path: git already knows the
# difference. For the MAIN worktree, `--show-toplevel` equals the directory
# holding the shared git dir (`--git-common-dir`). For a LINKED worktree the two
# differ. So "is this the main checkout" is asked of git, and the guard keeps
# working for any clone, any worktree layout, and any other repository.
#
# Scratch under the main checkout's .tmp/ and .worktrees/ is still allowed:
# .tmp/ is the sanctioned scratch location and is where worktrees are created,
# so blocking it would break the very workflow this rule exists to protect.
#
# WHAT IT DOES NOT SEE. It is wired to the edit tools, which declare the file
# they touch. A write performed through Bash instead — a heredoc, `sed -i`, a
# python one-liner — carries no declared path, and inferring one from arbitrary
# shell is unreliable enough that it would produce false blocks on ordinary
# read-only commands (`git -C <main> log`). So this narrows the mistake, it does
# not eliminate it. Treat a green run as "no edit-tool call targeted the main
# checkout", not as proof that nothing did.
#
# FAIL OPEN, deliberately. Any ambiguity — no path in the payload, a file
# outside a git repo, git unavailable, a parse failure — exits 0 and lets the
# tool proceed. A per-tool-call gate must never be able to wedge a session
# because the gate itself broke; the same reasoning as check-halt-hook.sh. This
# is a guard against an easy mistake, not a security boundary.
set -uo pipefail

payload="$(cat 2>/dev/null || true)"
[ -n "$payload" ] || exit 0

# Claude passes the tool input as JSON; the edit tools carry `file_path`.
target="$(printf '%s' "$payload" | python3 -c '
import json, sys
try:
    d = json.load(sys.stdin)
except Exception:
    sys.exit(0)
i = d.get("tool_input") or d
p = i.get("file_path") or i.get("notebook_path") or ""
print(p if isinstance(p, str) else "")
' 2>/dev/null || true)"

[ -n "$target" ] || exit 0
case "$target" in /*) ;; *) exit 0 ;; esac   # only reason about absolute paths

# Canonicalise before any prefix test. Without this, "<main>/.tmp/../changelog.md"
# would match the .tmp/ allowlist as a literal string while actually resolving into
# the main checkout. realpath does not require the file to exist, so this is safe
# for a Write that creates a new file.
canon="$(python3 -c 'import os,sys; print(os.path.realpath(sys.argv[1]))' "$target" 2>/dev/null || true)"
[ -n "$canon" ] && target="$canon"

# A Write may create a file that does not exist yet — ask about its directory.
probe="$target"
while [ ! -e "$probe" ] && [ "$probe" != "/" ]; do probe="$(dirname "$probe")"; done
[ -d "$probe" ] || probe="$(dirname "$probe")"
[ -d "$probe" ] || exit 0

toplevel="$(git -C "$probe" rev-parse --path-format=absolute --show-toplevel 2>/dev/null || true)"
common="$(git -C "$probe" rev-parse --path-format=absolute --git-common-dir 2>/dev/null || true)"
if [ -z "$toplevel" ] || [ -z "$common" ]; then
  # Not a git repo at all is the ordinary case and stays silent. But if the path
  # LOOKS like it is inside a repo and git still could not answer (git older than
  # 2.31, which lacks --path-format, or a transient failure), say so once: this
  # guard is fail-open, so an unreported evaluation failure disables it silently
  # and permanently. See the FAIL OPEN note above.
  if git -C "$probe" rev-parse --is-inside-work-tree >/dev/null 2>&1; then
    echo "check-bare-checkout-write-hook: could not resolve worktree layout for $target — guard skipped (needs git >= 2.31)" >&2
  fi
  exit 0
fi

main_root="$(cd "$(dirname "$common")" 2>/dev/null && pwd || true)"
[ -n "$main_root" ] || exit 0

# In a linked worktree the toplevel differs from the main root → always allowed.
[ "$toplevel" = "$main_root" ] || exit 0

# Sanctioned areas inside the main checkout.
case "$target" in
  "$main_root"/.tmp/*|"$main_root"/.worktrees/*) exit 0 ;;
esac

cat >&2 <<EOF
BLOCKED: refusing to edit the main checkout.

  path: $target
  main checkout: $main_root

Work in a dedicated worktree, not here. The main checkout is usually on an
older commit, so an edit made here is written against stale content, is not
covered by this session's verification, and another session can rebase or
reset it away without warning.

  git -C "$main_root" fetch origin master
  git -C "$main_root" worktree add -b <branch> .tmp/<name> origin/master

Then redo the edit under that worktree. Scratch files under .tmp/ are fine.
EOF
exit 2
