#!/usr/bin/env bash
# Fixture tests for run-in-docker.sh's handling of a container a cancelled job left running
# on the same checkout, with `docker` stubbed on PATH. Nothing touches Docker.
# Run: .buildkite/scripts/test/run-in-docker-orphan-test.sh
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)"
RID="$REPO_ROOT/.buildkite/scripts/run-in-docker.sh"
T="$(mktemp -d "${TMPDIR:-/tmp}/run-in-docker-orphan-test.XXXXXX")"
trap 'rm -rf "$T"' EXIT
FAILS=0
ok()  { echo "  ok   $1"; }
bad() { echo "  FAIL $1" >&2; FAILS=$((FAILS + 1)); }
has() { if grep -qF -- "$2" "$3"; then ok "$1"; else bad "$1: '$2' not in $(cat "$3")"; fi; }
hasnt() { if grep -qF -- "$2" "$3"; then bad "$1: '$2' unexpectedly in $(cat "$3")"; else ok "$1"; fi; }

mkdir -p "$T/bin"
# `docker ps` prints FAKE_PS (id and job label per line) and exits FAKE_PS_RC; every call is
# logged one per line.
cat >"$T/bin/docker" <<'EOF'
#!/usr/bin/env bash
echo "$*" >>"$DOCKER_LOG"
if [ "$1" = ps ]; then printf '%b' "${FAKE_PS:-}"; exit "${FAKE_PS_RC:-0}"; fi
exit 0
EOF
chmod +x "$T/bin/docker"
export PATH="$T/bin:$PATH"

run() { # log-file, then env assignments; RID_FLAGS adds run-in-docker.sh options
  local log="$1"; shift
  : >"$log"
  # shellcheck disable=SC2086
  env -u BUILDKITE -u BUILDKITE_JOB_ID DOCKER_LOG="$log" "$@" "$RID" -i img ${RID_FLAGS:-} -- true >/dev/null 2>&1
}
line_of() { grep -n -m1 -E -- "$1" "$2" | cut -d: -f1; }

echo "--- CI: a container left by another job on this checkout is removed; this job's is kept"
run "$T/ci.log" BUILDKITE=true BUILDKITE_JOB_ID=job-2 FAKE_PS='old1 job-1\nmine job-2\nnolabel\n'
has "lists containers labelled with this checkout" "ps --filter label=mockserver.ci.checkout=$REPO_ROOT" "$T/ci.log"
has "removes the other job's container" "rm -f old1" "$T/ci.log"
has "removes a container with no job label" "rm -f nolabel" "$T/ci.log"
rm_line="$(line_of '^rm -f old1' "$T/ci.log")"; run_line="$(line_of '^run ' "$T/ci.log")"
if [ -n "$rm_line" ] && [ -n "$run_line" ] && [ "$rm_line" -lt "$run_line" ]; then
  ok "removes it before starting this job's container"
else
  bad "removes it before starting this job's container: rm at line '${rm_line}', run at line '${run_line}'"
fi
hasnt "keeps this job's own container" "rm -f mine" "$T/ci.log"
has "passes --init" " --init " "$T/ci.log"
has "labels the container with the checkout" "--label mockserver.ci.checkout=$REPO_ROOT" "$T/ci.log"
has "labels the container with the job" "--label mockserver.ci.job=job-2" "$T/ci.log"

echo "--- CI: a failing docker ps does not stop the run; a caller's --init is passed once"
RID_FLAGS=--init run "$T/ci-ps-fails.log" BUILDKITE=true BUILDKITE_JOB_ID=job-3 FAKE_PS_RC=1
has "still runs the container" "run --rm" "$T/ci-ps-fails.log"
inits="$(grep -m1 '^run ' "$T/ci-ps-fails.log" | grep -o -- ' --init' | wc -l | tr -d ' ')"
if [ "$inits" = 1 ]; then ok "passes --init once"; else bad "passes --init once: got $inits"; fi

echo "--- local: no labels, no reaping, no forced --init"
run "$T/local.log" FAKE_PS='old1 job-1\n'
hasnt "does not list containers" "ps " "$T/local.log"
hasnt "does not remove containers" "rm -f" "$T/local.log"
hasnt "does not force --init" "--init" "$T/local.log"
hasnt "does not label" "--label" "$T/local.log"

if [ "$FAILS" -ne 0 ]; then
  echo "+++ $FAILS check(s) failed" >&2
  exit 1
fi
echo "all run-in-docker orphan checks passed"
