#!/usr/bin/env bash
# Which commits share a :snapshot image. mockserver-java is the only pipeline that pushes the
# :snapshot images, and these are the paths that trigger it and can change the image, so a commit
# with no such change since an image's revision is served that same image. (generate-pipeline.sh
# also triggers it for examples/java/ and extensions.html, which are not in the image.)
# Sourced by generate-pipeline.sh, steps/perf-test-run.sh and scripts/release/check-perf-preflight.sh.

JAVA_TRIGGER_REGEX='^(mockserver/|mockserver-ui/|test-fixtures/)'
JAVA_TRIGGER_EXCLUDE_REGEX='^mockserver/mockserver-maven-plugin/'

# Changed paths on stdin -> the ones that trigger mockserver-java.
java_trigger_changes() { grep -E -- "$JAVA_TRIGGER_REGEX" | grep -vE -- "$JAVA_TRIGGER_EXCLUDE_REGEX" || true; }

# snapshot_equivalent <repo> <image revision> <commit>: succeeds when <commit> is served the image
# built from <image revision>: the same commit, or a descendant with no mockserver-java trigger
# change in between. Fails closed when a commit is missing from <repo> after one fetch.
snapshot_equivalent() {
  local repo="$1" rev="$2" commit="$3" c changes
  [ -n "$rev" ] && [ "$rev" = "$commit" ] && return 0
  [[ "$rev" =~ ^[0-9a-f]{40}$ ]] || return 1
  for c in "$rev" "$commit"; do
    git -C "$repo" cat-file -e "${c}^{commit}" 2>/dev/null && continue
    git -C "$repo" fetch -q --no-tags origin "$c" >/dev/null 2>&1 || return 1
    git -C "$repo" cat-file -e "${c}^{commit}" 2>/dev/null || return 1
  done
  git -C "$repo" merge-base --is-ancestor "$rev" "$commit" 2>/dev/null || return 1
  changes="$(git -C "$repo" diff --name-only "$rev" "$commit" 2>/dev/null)" || return 1
  [ -z "$(java_trigger_changes <<<"$changes")" ]
}
