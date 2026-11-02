#!/usr/bin/env bash
# Runs one mockserver-netty IT class or Class#method TEST_REPEAT_COUNT times under busy-loop
# CPU load, optionally on TEST_REPEAT_REF's code; fails if any run does not pass.
# Variables and usage: "Repeating One Integration Test" in docs/infrastructure/ci-cd.md.
# The container must die with the step on cancel, or its busy loops load the next job:
# hence --init, a named container killed by the outer trap, and background child + wait.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../../.." && pwd)"
OUT_REL="test-repeat"
SRC_REL=".test-repeat-src"

SELECTOR="${TEST_REPEAT_IT:-}"
COUNT="${TEST_REPEAT_COUNT:-12}"
REF="${TEST_REPEAT_REF:-}"
BUSY_LOOPS="${TEST_REPEAT_BUSY_LOOPS:-12}"

BUSY_LOOP_MAX_SECONDS=$((4 * 60 * 60))
BUSY_PIDS=()
CHILD_PID=""

fail() { echo "+++ :bangbang: $*" >&2; exit 2; }

stop_load() { [ "${#BUSY_PIDS[@]}" -eq 0 ] || kill "${BUSY_PIDS[@]}" 2>/dev/null || true; }

# Runs a command in the background and waits, so a TERM trap runs at once rather than after it.
run_child() {
  "$@" &
  CHILD_PID=$!
  local rc=0
  wait "$CHILD_PID" || rc=$?
  CHILD_PID=""
  return "$rc"
}

# shellcheck disable=SC2329  # called from the traps
stop_child() { [ -z "$CHILD_PID" ] || kill "$CHILD_PID" 2>/dev/null || true; }

validate() {
  [[ "$SELECTOR" =~ ^[A-Z][A-Za-z0-9_]*(#[A-Za-z_][A-Za-z0-9_]*)?$ ]] \
    || fail "TEST_REPEAT_IT must be Class or Class#method, got '${SELECTOR}'"
  [[ "$COUNT" =~ ^[0-9]+$ ]] && [ "$COUNT" -ge 1 ] && [ "$COUNT" -le 50 ] \
    || fail "TEST_REPEAT_COUNT must be 1 to 50, got '${COUNT}'"
  [[ "$BUSY_LOOPS" =~ ^[0-9]+$ ]] && [ "$BUSY_LOOPS" -le 64 ] \
    || fail "TEST_REPEAT_BUSY_LOOPS must be 0 to 64, got '${BUSY_LOOPS}'"
  [ -z "$REF" ] || [[ "$REF" =~ ^[0-9a-f]{40}$ ]] \
    || fail "TEST_REPEAT_REF must be a full 40-character commit SHA, got '${REF}'"
}

# In the container: build the source tree once, then run the selector COUNT times under load.
in_container() {
  local src="$1" class="${SELECTOR%%#*}" out="${REPO_ROOT}/${OUT_REL}"
  local repo_local="$src/.test-repeat-m2"
  local -a repos=(-Dmaven.repo.local="$repo_local" -Dmaven.repo.local.tail="${HOME}/.m2/repository")
  local -a common=(-B --no-transfer-progress -Djacoco.skip=true -Dmaven.javadoc.skip=true
    -Dmaven.gitcommitid.skip=true -P '!build-ui')
  cd "$src/mockserver"
  trap 'stop_load; stop_child' EXIT
  trap 'stop_load; stop_child; exit 143' TERM INT

  echo "--- :maven: build mockserver-netty and its upstream modules (no tests)"
  run_child ./mvnw "${common[@]}" "${repos[@]}" -T 1C install -pl mockserver-netty -am \
    -DskipTests -DskipITs -Dinvoker.skip=true

  local i
  for ((i = 0; i < BUSY_LOOPS; i++)); do
    timeout "$BUSY_LOOP_MAX_SECONDS" bash -c 'while :; do :; done' &
    BUSY_PIDS+=("$!")
  done
  echo "nproc $(nproc), ${BUSY_LOOPS} busy loops started" | tee "$out/summary.txt"

  local reports="mockserver-netty/target/failsafe-reports" failed=0 rc started secs verdict
  for ((i = 1; i <= COUNT; i++)); do
    echo "--- :repeat: run ${i}/${COUNT}: ${SELECTOR}"
    rm -rf "$reports"
    started=$(date +%s)
    rc=0
    # verify, not bare failsafe goals: the commit under test may predate today's executions.
    run_child ./mvnw "${common[@]}" "${repos[@]}" -nsu -pl mockserver-netty verify \
      -Dtest=NONE -Dsurefire.failIfNoSpecifiedTests=false -Dit.test="$SELECTOR" \
      -Dfailsafe.rerunFailingTestsCount=0 \
      -Dmockserver.testOutput=quiet -DredirectTestOutputToFile=true -Dmockserver.testLogLevel=INFO \
      "-Dmockserver.testArgLine=-Dmockserver.maxLogEntries=10000 -Dmockserver.maxExpectations=5000" \
      > "$out/run-${i}.log" 2>&1 || rc=$?
    secs=$(( $(date +%s) - started ))
    mkdir -p "$out/run-${i}"
    cp "$reports"/*"${class}"* "$out/run-${i}/" 2>/dev/null || true
    if ! "$SCRIPT_DIR/assert-suite-ran.sh" "$out/run-${i}/TEST-*${class}.xml" >/dev/null 2>&1; then
      verdict="NO-TESTS"
    elif [ "$rc" -ne 0 ]; then
      verdict="FAIL"
    else
      verdict="PASS"
    fi
    [ "$verdict" = "PASS" ] || failed=$((failed + 1))
    {
      echo "run ${i}: ${verdict} rc=${rc} ${secs}s loadavg=$(cut -d' ' -f1-3 /proc/loadavg)"
      grep -h '<<< \(FAILURE\|ERROR\)!' "$out/run-${i}"/*.txt 2>/dev/null | sed 's/^/    /' || true
    } | tee -a "$out/summary.txt"
  done
  stop_load
  echo "${failed} of ${COUNT} runs did not pass" | tee -a "$out/summary.txt"
  [ "$failed" -eq 0 ]
}

if [ "${1:-}" = "--in-container" ]; then
  validate
  in_container "$2"
  exit $?
fi

validate
cd "$REPO_ROOT"
rm -rf "$OUT_REL" "$SRC_REL"
mkdir -p "$OUT_REL"

src="/build"
if [ -n "$REF" ]; then
  git cat-file -e "${REF}^{commit}" 2>/dev/null || git fetch --no-tags origin "$REF"
  REF="$(git rev-parse --verify "${REF}^{commit}")"
  mkdir -p "$SRC_REL"
  git archive "$REF" | tar -x -C "$SRC_REL"
  src="/build/${SRC_REL}"
fi
echo "--- :repeat: ${SELECTOR} x${COUNT}, ${BUSY_LOOPS} busy loops, code at ${REF:-$(git rev-parse HEAD)}"

CONTAINER="test-repeat-${BUILDKITE_JOB_ID:-$$}"
# shellcheck disable=SC2329  # called from the traps
kill_container() { docker kill "$CONTAINER" >/dev/null 2>&1 || true; }
trap kill_container EXIT
trap 'kill_container; stop_child; exit 143' TERM INT

rc=0
run_child "$SCRIPT_DIR/../run-in-docker.sh" \
  -i mockserver/mockserver:maven \
  -m 12g \
  --cache maven \
  --name "$CONTAINER" \
  --init \
  -e "TEST_REPEAT_IT=${SELECTOR}" \
  -e "TEST_REPEAT_COUNT=${COUNT}" \
  -e "TEST_REPEAT_BUSY_LOOPS=${BUSY_LOOPS}" \
  -- /build/.buildkite/scripts/steps/java-test-repeat.sh --in-container "$src" || rc=$?

if [ -f "$OUT_REL/summary.txt" ]; then
  style="success"
  [ "$rc" -eq 0 ] || style="error"
  {
    echo "**${SELECTOR}** x${COUNT}, ${BUSY_LOOPS} busy loops, code at \`${REF:-$(git rev-parse HEAD)}\`"
    echo '```'
    cat "$OUT_REL/summary.txt"
    echo '```'
  } | buildkite-agent annotate --style "$style" --context test-repeat || true
fi
exit "$rc"
