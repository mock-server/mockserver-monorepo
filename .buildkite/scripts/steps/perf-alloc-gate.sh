#!/usr/bin/env bash
set -euo pipefail

# =============================================================================
# PER-MERGE allocation backstop.
# =============================================================================
#
# WHAT: runs the four JMH ALLOCATION benchmarks at PINNED representative params
# — MatchingBenchmark (matching), InboundDecodeBenchmark (request decode),
# ResponseWriteBenchmark (response serialise + write), ProcessActionBenchmark
# (processAction with no data-plane hook loaded) — captures each one's
# gc.alloc.rate.norm (bytes/op), and FAILS the build if any exceeds its ABSOLUTE
# committed floor in mockserver-performance-test/perf-budgets.json
# (premerge_alloc.<Class>.alloc_bytes_per_op). A row whose budget lacks
# `gating: true` is NOTIFY-ONLY: a breach is annotated as a warning, not a failure.
# Every row still needs a numeric floor — a missing budget fails closed either way.
#
# WHY it is separate from the daily perf-test-microbench.sh + perf-test-compare.sh:
#   * Coverage. MatchingBenchmark alone measures MATCHING only — not Netty decode
#     or response serialisation/write. An allocation regression that moves bytes
#     OUT of the matcher and INTO decode/response-write reads as an IMPROVEMENT
#     against a matcher-only gate. Adding the decode + response-write arms with
#     absolute floors makes "bytes moved into decode/write" trip a floor.
#   * Absolute, not rolling. The daily compare uses a rolling median + MAD over
#     per-merge history, which ABSORBS the slow drift the gate exists to catch. A
#     committed absolute floor cannot be normalised down by a quiet window.
#   * Per merge is a property of WIRING. This step is wired UNCONDITIONALLY into
#     .buildkite/pipeline-java.yml (no `if: build.branch == 'master'`), BEFORE the
#     `wait` that precedes the master-gated block, so it runs PRE-merge and blocks
#     the PR. A branch-conditioned step, or one in the master-only container suite,
#     would silently be a POST-merge gate. (Note: pipeline-java.yml is itself
#     orchestrator-path-filtered to mockserver/ + mockserver-ui/ changes, so a JDK
#     or base-image bump that shifts allocation reaches this gate only via the
#     daily run — this step does NOT cover that class of change.)
#
# gc.alloc.rate.norm is bytes allocated per op, independent of CPU speed, so an
# absolute regression here is trustworthy on cloud CI. It is stable within a fork,
# but a JIT escape-analysis outcome can differ ACROSS forks (the HEADERS_MISS
# rows are multimodal on amd64), so a floor must clear every mode. Some floors
# are PROVISIONAL (see perf-budgets.json): derived from local measurement with
# headroom rather than from >=10 notify-only runs, because these benchmarks have
# no history yet. ResponseWrite is ALSO promoted notify-only into the daily
# microbench step so history accrues and the floors can graduate.
#
# Heavy (builds mockserver-netty + upstream reactor deps, then forks a JVM per
# benchmark), same prep as perf-test-microbench.sh. Runs on the default queue.
#
# LOCAL / TEST HOOK: set PERF_ALLOC_JMH_RESULT to a pre-produced JMH -rf json to
# skip the Docker build+run and only evaluate that file against the floors. Used
# by the degrade-and-confirm-red verification and for fast local iteration.

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../../.." && pwd)"

source "$SCRIPT_DIR/../lib/perf-budgets-validate.sh"

MAVEN_IMAGE="${MAVEN_IMAGE:-mockserver/mockserver:maven}"
BUDGETS_FILE="${PERF_BUDGETS_FILE:-$REPO_ROOT/mockserver-performance-test/perf-budgets.json}"

# The four allocation benchmarks and the params that pin each to a DETERMINISTIC
# row count. MatchingBenchmark reads matcherType/expectationCount/logLevel AND
# detailedMatchFailures; InboundDecode reads bodySize; ResponseWrite reads
# responseSize AND declareBodyCharset. JMH applies each -p only to the benchmarks
# that declare it. EVERY param a benchmark declares must be pinned here: an
# UNPINNED param is expanded over ALL its declared values, which multiplies that
# class's row count non-deterministically and breaks the EXPECTED_ROWS assertion
# below (fail-closed, but a red build rather than a measurement).
#
# MatchingBenchmark's detailedMatchFailures is pinned to BOTH values
# (`false,true`) DELIBERATELY — the gate measures and floors each arm separately:
#   * The `false` arm is the detailedMatchFailures opt-out; its committed floor
#     (premerge_alloc.MatchingBenchmark.alloc_bytes_per_op) was derived from that
#     non-detailed shape and MUST keep describing it.
#   * The `true` arm is the SHIPPED DEFAULT (detailedMatchFailures defaults to true)
#     and exercises the MatchDifference -> StringFormatter formatting
#     path a sustained-load JFR profile found to be the #1/#2 production allocation
#     sites (~28-33% of sampled allocation), made lazy by a8898b263. Left
#     un-measured, a regression re-introducing eager formatting would sail straight
#     through this gate. It is routed to its OWN key
#     (premerge_alloc.MatchingBenchmark_detailed.alloc_bytes_per_op) by evaluate()
#     below, so the two arms never share a floor.
# `false,true` is an EXPLICIT two-value pin (a comma list), NOT an open expansion:
# the row count it produces is exactly known (2), so the EXPECTED_ROWS guard stays
# deterministic. declareBodyCharset is pinned to `false` for the same
# floor-describes-its-own-workload reason (ResponseWrite's floor came from the
# implicit-charset shape; the explicit arm is measured by perf-test-microbench.sh).
#
# ProcessActionBenchmark declares no @Param, so every -p above passes it by and it adds one row.
# Pinned-run row count: MatchingBenchmark 1x1x1x{false,true}=2 + InboundDecode 1 + ResponseWrite
# 1x1 + ProcessAction 1 = 5 rows.
JMH_INCLUDE="${PERF_ALLOC_INCLUDE:-org\.mockserver\.benchmark\.(MatchingBenchmark|InboundDecodeBenchmark|ResponseWriteBenchmark|ProcessActionBenchmark)\.}"
JMH_ARGS="${PERF_ALLOC_JMH_ARGS:--bm avgt -prof gc -f 1 -wi 3 -i 5 -r 1 -w 1 -p matcherType=EXACT -p expectationCount=100 -p logLevel=INFO -p detailedMatchFailures=false,true -p bodySize=16384 -p responseSize=16384 -p declareBodyCharset=false}"

# SCAN arm (4 rows): EXACT narrows to an EMPTY candidate bucket, so the rows above
# never run the per-candidate matching scan; HEADERS_MISS puts all 100 expectations
# in one bucket. A separate invocation because JMH crosses every -p list (adding
# HEADERS_MISS/WARN above would also add EXACT/WARN rows). Its budgets gate, with
# floors derived from this step's own artifact history — see perf-budgets.json.
SCAN_JMH_INCLUDE="${PERF_ALLOC_SCAN_INCLUDE:-org\.mockserver\.benchmark\.MatchingBenchmark\.}"
SCAN_JMH_ARGS="${PERF_ALLOC_SCAN_JMH_ARGS:--bm avgt -prof gc -f 1 -wi 3 -i 5 -r 1 -w 1 -p matcherType=HEADERS_MISS -p expectationCount=100 -p logLevel=INFO,WARN -p detailedMatchFailures=false,true}"

# EXACT expected row count — a fail-closed guard against include/param drift (a
# renamed class, or a param that stops pinning, silently drops a row). Nine rows:
# the five pinned rows above plus the four scan-arm rows.
EXPECTED_ROWS="${PERF_ALLOC_EXPECTED_ROWS:-9}"

RESULT_RAW="mockserver/mockserver-benchmark/target/jmh-alloc-gate.json"
RESULT_PINNED="mockserver/mockserver-benchmark/target/jmh-alloc-gate-pinned.json"
RESULT_SCAN="mockserver/mockserver-benchmark/target/jmh-alloc-gate-scan.json"

annotate_on_failure() {
  local ec=$?
  if [ "$ec" -ne 0 ] && command -v buildkite-agent >/dev/null 2>&1; then
    printf '%s\n' ":x: **Per-merge allocation gate FAILED** (exit ${ec}) — a gating benchmark allocated more bytes/op than its committed floor in perf-budgets.json, OR the gate could not measure (build/resolve failure, include matched wrong row count, missing budget). See this step's log for the per-benchmark table." \
      | buildkite-agent annotate --style error --context perf-alloc-gate || true
  fi
}
trap annotate_on_failure EXIT

# -----------------------------------------------------------------------------
# evaluate <jmh-result.json> : compare each row's alloc_bytes_per_op to its floor.
# Exit 0 = all within floor; exit 1 = a regression OR a fail-closed violation.
# -----------------------------------------------------------------------------
evaluate() {
  local result="$1"
  if [ ! -f "$result" ]; then
    echo "ERROR: JMH result not found: $result" >&2
    return 1
  fi
  if [ ! -f "$BUDGETS_FILE" ]; then
    echo "ERROR: perf-budgets.json missing: $BUDGETS_FILE (fail-closed: a missing budget must never mean 'no floor')" >&2
    return 1
  fi
  # Existence is not readability and readability is not usability. `$alloc <= $floor`
  # below is decided by TYPE before value when the operands differ: jq sorts every
  # string above every number, so a floor of "5000" makes the comparison true for
  # any allocation whatsoever and this blocking gate silently passes everything.
  # Validate the schema, and prove on every run that the validator still rejects
  # that shape -- a guard nobody degrades is just more code that looks like a check.
  if ! SELF_TEST_OUT="$(bash "$SCRIPT_DIR/../lib/perf-budgets-validate.sh" --self-test 2>&1)"; then
    echo "ERROR: perf-budgets validator self-test FAILED -- the schema check that keeps a mistyped budget from disabling a floor does not itself work:" >&2
    echo "$SELF_TEST_OUT" >&2
    return 1
  fi
  local schema_problems
  if ! schema_problems="$(validate_perf_budgets "$BUDGETS_FILE")"; then
    echo "ERROR: perf-budgets.json has invalid entries (fail-closed -- a quoted number does not error in jq, it silently turns the budget off):" >&2
    echo "$schema_problems" | sed 's/^/  /' >&2
    return 1
  fi

  # Reshape JMH rows -> one record per benchmark class with its measured bytes/op,
  # its budget key, floor and verdict. jq -e sets exit status from the last value:
  # true (all ok, correct row count, no missing/absent floor) -> 0, else -> 1.
  local report
  report="$(jq -n \
    --slurpfile jmh "$result" \
    --slurpfile budgets "$BUDGETS_FILE" \
    --argjson expected "$EXPECTED_ROWS" '
      ($budgets[0].budgets) as $b
      | [ $jmh[0][]
          | (.benchmark | sub("^org\\.mockserver\\.benchmark\\.";"") | sub("\\..*$";"")) as $cls
          # The detailed-match-failure arm of a benchmark is a SEPARATE workload with
          # its own committed floor: params.detailedMatchFailures=="true" routes to a
          # distinct "<Class>_detailed" budget key so the base <Class> floor keeps
          # describing the NON-detailed shape it was derived from. Benchmarks that do
          # not declare the param (InboundDecode, ResponseWrite, ProcessAction) have
          # params.detailedMatchFailures==null, so they take the base key unchanged.
          # The pinned EXACT/INFO shape keeps the base key; any other MatchingBenchmark
          # shape (the scan arm) is qualified by its matcherType and logLevel.
          | (if ((.params.matcherType // "EXACT") == "EXACT" and (.params.logLevel // "INFO") == "INFO")
             then $cls else ($cls + "_" + .params.matcherType + "_" + .params.logLevel) end) as $shape
          | (if (.params.detailedMatchFailures == "true") then ($shape + "_detailed") else $shape end) as $label
          | ("premerge_alloc." + $label + ".alloc_bytes_per_op") as $bkey
          | (.secondaryMetrics["gc.alloc.rate.norm"].score) as $alloc
          | ($b[$bkey]) as $budget
          | ($budget.floor) as $floor
          | {
              cls: $cls,
              label: $label,
              alloc: $alloc,
              bkey: $bkey,
              floor: $floor,
              provisional: ($budget.provisional == true),
              # Only an explicit `gating: true` fails the build; absent or false is
              # notify-only, the same default perf-test-compare.sh applies.
              gating: ($budget.gating == true),
              # fail-closed: a row with no bytes/op reading (gc profiler absent) or
              # no committed floor cannot be judged, so it is a HARD failure even
              # when notify-only.
              measured: (($alloc | type) == "number"),
              # A floor is only a floor if it is a NUMBER; a quoted one would make
              # every comparison below unconditionally true. The budget file is
              # schema-checked before this runs -- this is the second line, because
              # a predicate can be reached by a path that skipped that check.
              haveFloor: ($budget != null and ($floor | type) == "number"),
              ok: (($alloc | type) == "number" and $budget != null and ($floor | type) == "number" and ($alloc <= $floor))
            }
          | .fails = (((.measured and .haveFloor) | not) or (.gating and (.ok | not)))
          | .notifyBreach = (.measured and .haveFloor and (.gating | not) and (.ok | not))
        ] as $rows
      # Two rows routed to one key would let one shape mask the other under a
      # single floor; the row count alone cannot see a dropped-plus-duplicated pair.
      | ($rows | map(.label) | group_by(.) | map(select(length > 1) | .[0])) as $dupes
      | {
          rows: $rows,
          rowCount: ($rows | length),
          expected: $expected,
          countOk: (($rows | length) == $expected),
          dupes: $dupes,
          allOk: (($rows | all(.fails | not)) and (($rows | length) == $expected) and ($dupes | length) == 0)
        }
    ')"

  # Human-readable table.
  echo "--- per-merge allocation gate — measured bytes/op vs committed floor"
  echo "$report" | jq -r '
    .rows[]
    | "  " + (if .ok then ":white_check_mark:" elif .notifyBreach then ":warning:" else ":x:" end)
      + " " + .label
      + "  alloc=" + ((.alloc // 0) | floor | tostring) + " B/op"
      + "  floor=" + ((.floor // "MISSING") | tostring)
      + (if .provisional then "  (provisional)" else "" end)
      + (if .gating then "" else "  (notify-only)" end)
      + (if (.measured | not) then "  [NO gc.alloc.rate.norm — profiler absent]" else "" end)
      + (if (.haveFloor | not) then "  [NO FLOOR in perf-budgets.json — fail-closed]" else "" end)'

  local rowCount expected countOk
  rowCount="$(echo "$report" | jq -r '.rowCount')"
  expected="$(echo "$report" | jq -r '.expected')"
  countOk="$(echo "$report" | jq -r '.countOk')"
  if [ "$countOk" != "true" ]; then
    echo "ERROR: allocation gate measured ${rowCount} benchmark row(s), expected ${expected} — include/param drift, a crashed fork, or a deliberate surface change that did not bump PERF_ALLOC_EXPECTED_ROWS." >&2
    return 1
  fi
  if [ "$(echo "$report" | jq -r '.dupes | length')" != "0" ]; then
    echo "ERROR: more than one benchmark row routed to the same budget key: $(echo "$report" | jq -c '.dupes') — a param that the routing in evaluate() does not qualify." >&2
    return 1
  fi

  # A notify-only breach never fails the build, but it is never silent either:
  # it gets its own warning annotation on every run it persists.
  local breaches
  breaches="$(echo "$report" | jq -r '.rows[] | select(.notifyBreach)
    | "- " + .label + ": alloc=" + ((.alloc // 0) | floor | tostring) + " B/op > floor=" + (.floor | tostring)')"
  if [ -n "$breaches" ]; then
    echo "--- :warning: notify-only allocation budget(s) exceeded (not failing the build):"
    echo "$breaches" | sed 's/^/  /'
    if command -v buildkite-agent >/dev/null 2>&1; then
      printf '%s\n' ":warning: **Per-merge allocation gate: notify-only budget exceeded** — these rows have no \`gating: true\` in perf-budgets.json yet, so they do not fail the build.

${breaches}" | buildkite-agent annotate --style warning --context perf-alloc-gate-notify || true
    fi
  fi

  if [ "$(echo "$report" | jq -r '.allOk')" = "true" ]; then
    echo "--- :white_check_mark: allocation gate PASSED — every gating benchmark within its floor"
    if command -v buildkite-agent >/dev/null 2>&1; then
      local tbl
      tbl="$(echo "$report" | jq -r '.rows[] | "| " + .label + " | " + ((.alloc // 0) | floor | tostring) + " | " + ((.floor // "MISSING") | tostring) + (if .provisional then " (prov.)" else "" end) + (if .gating then "" else " (notify-only)" end) + " |"')"
      printf '%s\n' ":white_check_mark: **Per-merge allocation gate PASSED** — every gating allocation benchmark within its committed floor.

| benchmark | bytes/op | floor |
|---|---:|---:|
${tbl}" | buildkite-agent annotate --style success --context perf-alloc-gate || true
    fi
    return 0
  fi

  echo "ERROR: allocation gate FAILED — a gating benchmark over its floor, or a row with no reading or no numeric floor:" >&2
  echo "$report" | jq -r '.rows[] | select(.fails)
    | "  - " + .label + ": alloc=" + ((.alloc // 0) | floor | tostring)
      + " B/op floor=" + ((.floor // "MISSING") | tostring)' >&2
  return 1
}

# -----------------------------------------------------------------------------
# Fast path: evaluate a pre-produced JMH result (local test / degrade-and-confirm).
# -----------------------------------------------------------------------------
if [ -n "${PERF_ALLOC_JMH_RESULT:-}" ]; then
  echo "--- evaluating pre-produced JMH result: ${PERF_ALLOC_JMH_RESULT}"
  evaluate "$PERF_ALLOC_JMH_RESULT"
  exit $?
fi

# -----------------------------------------------------------------------------
# Full path: build the benchmark's compile deps, compile, run JMH, then evaluate.
# Mirrors perf-test-microbench.sh's build prep (mockserver-benchmark is out of the
# reactor, so its in-reactor deps must be installed via a named module + -am).
# -----------------------------------------------------------------------------
echo "--- building mockserver-netty + upstream (benchmark compile deps), then running the allocation benchmarks"
# shellcheck disable=SC2016
"$SCRIPT_DIR/../run-in-docker.sh" \
  -i "$MAVEN_IMAGE" \
  -m "${MAVEN_MEMORY:-7g}" \
  --entrypoint bash \
  -w /build \
  -e "JMH_ARGS=$JMH_ARGS" \
  -e "JMH_INCLUDE=$JMH_INCLUDE" \
  -e "SCAN_JMH_ARGS=$SCAN_JMH_ARGS" \
  -e "SCAN_JMH_INCLUDE=$SCAN_JMH_INCLUDE" \
  -- -c '
    set -euo pipefail
    cd /build/mockserver
    mvn -q -pl mockserver-netty -am install -DskipTests -DskipITs -P '!build-ui' -Djacoco.skip=true -Dcheckstyle.skip=true
    cd mockserver-benchmark
    mvn -q compile dependency:build-classpath -Dmdep.outputFile=target/classpath.txt -Djacoco.skip=true
    CP="target/classes:$(cat target/classpath.txt)"
    rm -f target/jmh-alloc-gate-pinned.json target/jmh-alloc-gate-scan.json
    # shellcheck disable=SC2086
    java -cp "$CP" org.openjdk.jmh.Main "$JMH_INCLUDE" $JMH_ARGS -rf json -rff target/jmh-alloc-gate-pinned.json
    # shellcheck disable=SC2086
    java -cp "$CP" org.openjdk.jmh.Main "$SCAN_JMH_INCLUDE" $SCAN_JMH_ARGS -rf json -rff target/jmh-alloc-gate-scan.json
  '

for part in "$RESULT_PINNED" "$RESULT_SCAN"; do
  if [ ! -f "$REPO_ROOT/$part" ]; then
    echo "ERROR: JMH did not produce $part" >&2
    exit 1
  fi
done
# One result file, so evaluate() and the PERF_ALLOC_JMH_RESULT hook see every row.
jq -s 'add' "$REPO_ROOT/$RESULT_PINNED" "$REPO_ROOT/$RESULT_SCAN" > "$REPO_ROOT/$RESULT_RAW"

if command -v buildkite-agent >/dev/null 2>&1; then
  buildkite-agent artifact upload "$RESULT_RAW" || true
fi

evaluate "$REPO_ROOT/$RESULT_RAW"
