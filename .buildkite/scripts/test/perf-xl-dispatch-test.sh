#!/usr/bin/env bash
# Docker-free checks for the perf-xl wiring: what perf-test-guard.sh uploads with and without
# PERF_XL (against a stub buildkite-agent), and perf-test-run.sh's PERF_RUN_ARM / PERF_XL switch.
# Run: .buildkite/scripts/test/perf-xl-dispatch-test.sh
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)"
# shellcheck source=lib/perf-test-env.sh
. "$REPO_ROOT/.buildkite/scripts/test/lib/perf-test-env.sh"
perf_test_scrub_env
GUARD="$REPO_ROOT/.buildkite/scripts/steps/perf-test-guard.sh"
RUN="$REPO_ROOT/.buildkite/scripts/steps/perf-test-run.sh"
TIMEOUTS="$REPO_ROOT/.buildkite/scripts/steps/check-pipeline-step-timeouts.sh"
FAILS=0
ok()   { echo "  ok   $1"; }
bad()  { echo "  FAIL $1" >&2; FAILS=$((FAILS + 1)); }
check() { # name expected actual
  if [ "$2" = "$3" ]; then ok "$1"; else bad "$1: expected '$2', got '$3'"; fi
}
WORK="$(mktemp -d "${TMPDIR:-/tmp}/perf-xl-dispatch-test.XXXXXX")"
trap 'rm -rf "$WORK"' EXIT

# Stub buildkite-agent records each `pipeline upload` body; aws fails so the commit lookup is quick.
mkdir -p "$WORK/bin"
cat > "$WORK/bin/buildkite-agent" <<'STUB'
#!/usr/bin/env bash
if [ "$1 $2" = "pipeline upload" ]; then
  n=$(( $(ls "$UPLOAD_DIR" | wc -l) + 1 )); cat > "$UPLOAD_DIR/upload-$n.yml"
fi
exit 0
STUB
printf '#!/usr/bin/env bash\nexit 1\n' > "$WORK/bin/aws"
chmod +x "$WORK/bin/buildkite-agent" "$WORK/bin/aws"

guard() { # case_name env... -> uploads in $WORK/<case_name>/
  local name="$1"; shift
  mkdir -p "$WORK/$name"
  ( cd "$REPO_ROOT" && env -i PATH="$WORK/bin:$PATH" HOME="${HOME:-/tmp}" UPLOAD_DIR="$WORK/$name" \
      BUILDKITE_SOURCE=ui BUILDKITE_BRANCH=master "$@" bash "$GUARD" ) >"$WORK/$name.log" 2>&1 \
    || bad "$name: guard exited non-zero ($(tail -2 "$WORK/$name.log" | tr '\n' ' '))"
}
uploads() { find "$WORK/$1" -name 'upload-*.yml' | wc -l | tr -d ' '; }
run_timeout() { awk '/key: "perf-run"/{f=1} f && /timeout_in_minutes:/{print $2; exit}' "$WORK/$1/upload-1.yml"; }

echo "--- 1. PERF_XL unset: one upload, no perf-xl step"
guard unset
guard unset_hwm PERF_SERVING_HW_MATRIX=true
guard unset_rw PERF_SERVING_RW_MULTIK6=true
check "unset: one upload" "1" "$(uploads unset)"
check "unset: no perf-xl reference" "0" "$(cat "$WORK"/unset/*.yml | grep -cE 'perf-xl|PERF_RUN_ARM' || true)"
check "unset: perf-run timeout 90" "90" "$(run_timeout unset)"
check "unset + hw matrix: perf-run timeout 180" "180" "$(run_timeout unset_hwm)"
check "unset + multi-k6: perf-run timeout 110" "110" "$(run_timeout unset_rw)"
check "unset + hw matrix: still one upload" "1" "$(uploads unset_hwm)"

echo "--- 2. PERF_XL=true: the arms move to perf-xl steps"
guard xl PERF_XL=true
guard xl_all PERF_XL=true PERF_SERVING_HW_MATRIX=true PERF_SERVING_RW_MULTIK6=true
guard xl_one PERF_XL=1
check "xl: two uploads" "2" "$(uploads xl)"
check "xl: main upload identical to unset" "same" "$(cmp -s "$WORK/unset/upload-1.yml" "$WORK/xl/upload-1.yml" && echo same || echo differs)"
check "xl: one perf-xl step (multi-k6 only)" "1" "$(grep -c 'queue: "perf-xl"' "$WORK/xl/upload-2.yml")"
check "xl: plus the arm's persist and publish steps" "3" "$(grep -c '^  - label:' "$WORK/xl/upload-2.yml")"
check "xl + both arms: two perf-xl steps" "2" "$(grep -c 'queue: "perf-xl"' "$WORK/xl_all/upload-2.yml")"
check "xl + both arms: four steps in all" "4" "$(grep -c '^  - label:' "$WORK/xl_all/upload-2.yml")"
check "xl + both arms: perf-run timeout stays 90" "90" "$(run_timeout xl_all)"
check "PERF_XL=1 is not the opt-in (run.sh reads exactly true)" "1" "$(uploads xl_one)"
check "PERF_XL=1 says it was not dispatched" "1" "$(grep -cF -- "--- :warning: PERF_XL='1' is not 'true'; perf-xl not dispatched" "$WORK/xl_one.log" || true)"
check "PERF_XL unset prints no warning" "0" "$(grep -c 'PERF_XL=' "$WORK/unset.log" || true)"
XL="$WORK/xl_all/upload-2.yml"
check "every perf-xl step targets the perf-xl queue" "2" "$(grep -c 'queue: "perf-xl"' "$XL")"
check "every perf-xl step has the standard retry" "4" "$(grep -cE 'exit_status: (-1|255)' "$XL")"
check "arms" "rw_multik6 hw_matrix" "$(awk -F'"' '/PERF_RUN_ARM:/ {print $2}' "$XL" | paste -sd' ' -)"
# A perf-xl artifact must never carry a name compare or publish downloads by exact match.
names="$(awk -F'"' '/PERF_RUN_NAME:/{print $2}' "$XL")"
check "every PERF_RUN_NAME is perfxl- prefixed" "2" "$(grep -c '^perfxl-' <<<"$names")"
# Item 44: the multi-k6 arm's result is persisted by its own compare and published from runs-perf-xl/.
step_of() { awk -v k="key: \"$2\"" '/^  - label:/{if (f) exit; buf=""} {buf=buf $0 "\n"} index($0, k){f=1} END{if (f) printf "%s", buf}' "$1"; }
persist="$(step_of "$XL" perfxl-rw-multik6-persist)"; publish="$(step_of "$XL" perfxl-rw-multik6-publish)"
check "persist: hard edge on the arm" "1" "$(grep -c 'depends_on: "perfxl-rw-multik6"$' <<<"$persist" || true)"
check "persist: compare reads the arm's own result" "1" \
  "$(grep -c 'PERF_COMPARE_RESULT_ARTIFACT: "perfxl-rw-multik6-perf-result.json"' <<<"$persist" || true)"
check "persist: on the perf queue (the S3 grant), never soft_fail" "1|0" \
  "$(grep -c 'queue: "perf"$' <<<"$persist" || true)|$(grep -c soft_fail <<<"$persist" || true)"
check "publish: hard edge on persist, soft_fail, multi-k6 source" "1|1|1" \
  "$(grep -c 'depends_on: "perfxl-rw-multik6-persist"' <<<"$publish" || true)|$(grep -c 'soft_fail: true' <<<"$publish" || true)|$(grep -c 'PERF_PUBLISH_SOURCE: "rw_multik6"' <<<"$publish" || true)"
check "perf-xl keys do not collide with the main steps" "" \
  "$(comm -12 <(awk -F'"' '/key:/{print $2}' "$WORK/xl_all/upload-1.yml" | sort) <(awk -F'"' '/key:/{print $2}' "$XL" | sort))"

echo "--- 3. every generated upload parses and declares timeout_in_minutes"
yaml_parses() {
  if python3 -c 'import yaml' 2>/dev/null; then python3 -c 'import sys, yaml; yaml.safe_load(open(sys.argv[1]))' "$1"
  elif command -v ruby >/dev/null 2>&1; then ruby -ryaml -e 'YAML.safe_load(File.read(ARGV[0]))' "$1"
  else echo "SKIP"; fi
}
mkdir -p "$WORK/tree/.buildkite/scripts/steps"
cp "$TIMEOUTS" "$WORK/tree/.buildkite/scripts/steps/"
for f in "$WORK"/*/upload-*.yml; do
  rel="${f#"$WORK"/}"
  r="$(yaml_parses "$f" 2>&1)" && { [ "$r" = SKIP ] && echo "  skip YAML parse of $rel (no python3 yaml or ruby)" || ok "$rel parses"; } \
    || bad "$rel does not parse: $r"
  check "$rel: one timeout_in_minutes per step" "$(grep -c '^  - label:' "$f")" "$(grep -cE '^    timeout_in_minutes: [0-9]+$' "$f")"
  cp "$f" "$WORK/tree/.buildkite/pipeline-gen-${rel//\//-}"
done
if ! command -v python3 >/dev/null 2>&1; then
  echo "  skip check-pipeline-step-timeouts.sh (it needs python3; the per-step count above still ran)"
elif bash "$WORK/tree/.buildkite/scripts/steps/check-pipeline-step-timeouts.sh" >"$WORK/timeouts.log" 2>&1; then
  ok "check-pipeline-step-timeouts.sh passes on every generated upload"
else
  bad "check-pipeline-step-timeouts.sh: $(cat "$WORK/timeouts.log")"
fi

echo "--- 4. perf-test-run.sh PERF_RUN_ARM / PERF_XL switch"
SWITCH="$(awk '/^# --- arm-only mode/ {p=1} p {print} p && /^arm_only\(\)/ {exit}' "$RUN")"
if [ -z "$SWITCH" ]; then
  bad "arm-only switch block not found in $RUN"
else
  switch() { # env... -> "rc rw hwm info name" after the block runs
    env -i PATH="$PATH" "$@" bash -c "set -euo pipefail; { $SWITCH
      } >/dev/null 2>&1"'
      echo "0 ${PERF_SERVING_RW_MULTIK6:-unset} ${PERF_SERVING_HW_MATRIX:-unset} ${PERF_INFO_ARM:-unset} ${PERF_RUN_NAME:-unset}"' \
      || echo "$? - - - -"
  }
  check "no env: untouched" "0 unset unset unset unset" "$(switch)"
  check "PERF_XL=true: both arms off in the full run" "0 false false unset unset" "$(switch PERF_XL=true PERF_SERVING_RW_MULTIK6=true PERF_SERVING_HW_MATRIX=true)"
  check "PERF_XL=1: not the opt-in" "0 true unset unset unset" "$(switch PERF_XL=1 PERF_SERVING_RW_MULTIK6=true)"
  check "rw_multik6: its arm only, default name" "0 true false false arm-rw_multik6" "$(switch PERF_RUN_ARM=rw_multik6 PERF_SERVING_HW_MATRIX=true)"
  check "hw_matrix: its arm only, step name kept" "0 false true false perfxl-hw-matrix" \
    "$(switch PERF_RUN_ARM=hw_matrix PERF_RUN_NAME=perfxl-hw-matrix PERF_SERVING_RW_MULTIK6=true)"
  check "an unknown arm is refused" "1 - - - -" "$(switch PERF_RUN_ARM=sweep)"
  check "rw_multik6 under host networking is refused" "1 - - - -" "$(switch PERF_RUN_ARM=rw_multik6 PERF_NETWORK_MODE=host)"
  out="$(env -i PATH="$PATH" PERF_RUN_ARM=rw_multik6 PERF_WORKLOAD=forward PERF_STEADY_RATE=100 PERF_STREAMING=true \
    bash -c "set -euo pipefail; { $SWITCH
    } >/dev/null"'
    echo "[${PERF_WORKLOAD}] [${PERF_STEADY_RATE}] ${PERF_STREAMING} ${PERF_CLUSTERED} ${PERF_PROXY_PROFILE} ${PERF_LAPTOP_PROFILE} ${PERF_SERVING_PERCORE}"' 2>/dev/null)"
  check "arm-only forces inherited workloads and phases off" "[] [] false false false false false" "$out"
fi

echo "--- 5. arm-only invariants, run from the real blocks of perf-test-run.sh"
ARMFN="$(grep -m1 '^arm_only()' "$RUN")"
ELIG="$(awk '/^BASELINE_ELIGIBLE="true"$/ {p=1} /^# --- image freshness:/ {exit} p' "$RUN")"
# The indented if-block holding the meta-data write, whatever conditions guard it.
RAN="$(awk '/^  if / {buf = ""; p = 1} p {buf = buf $0 "\n"} /^  fi$/ && p {p = 0; if (buf ~ /meta-data set "perf_regression_ran_commit"/) {printf "%s", buf; exit}}' "$RUN")"
MEASUREDFN="$(awk '/^harness_commit_measured\(\) *\{/ {p=1} p {print} p && /^}/ {exit}' "$RUN")"
MEASUREDFN="source '$REPO_ROOT/.buildkite/scripts/lib/snapshot-image-inputs.sh'"$'\n'"$MEASUREDFN"
FINAL="$(awk '/^# Arm-only: nothing downstream gates this result/ {p=1} p' "$RUN")"
for b in ARMFN ELIG RAN MEASUREDFN FINAL; do [ -n "${!b}" ] || bad "block $b not found in $RUN"; done
elig() { # PERF_RUN_ARM -> BASELINE_ELIGIBLE after the real eligibility block
  env -i PATH="$PATH" PERF_RUN_ARM="$1" PERF_JVM_DIAGNOSTICS=standard JAVA_TOOL_OPTS_VAL="" \
    CONFIG_PROFILE=default RIG_PROFILE=default K6_NUMA_NODE=other bash -c "set -euo pipefail; $ARMFN
$ELIG
echo \"\$BASELINE_ELIGIBLE\"" 2>/dev/null | tail -1
}
check "full run stays baseline-eligible" "true" "$(elig "")"
check "hw_matrix arm-only is not baseline-eligible" "false" "$(elig hw_matrix)"
ran() { # PERF_RUN_ARM [ATTRIBUTED_COMMIT] [DP_AB_OK] -> number of perf_regression_ran_commit writes
  env -i PATH="$PATH" PERF_RUN_ARM="$1" PERF_RELEASE_COMPARISON="" HARNESS_COMMIT=abc REPO_ROOT="$REPO_ROOT" \
    ATTRIBUTED_COMMIT="${2:-abc}" PROVENANCE_OK=true DP_AB_OK="${3:-true}" bash -c "set -euo pipefail
buildkite-agent() { echo \"META \$*\"; }
$ARMFN
$MEASUREDFN
$RAN" 2>/dev/null | grep -c '^META meta-data set perf_regression_ran_commit abc$' || true
}
check "full run records perf_regression_ran_commit" "1" "$(ran "")"
check "arm-only run does not record perf_regression_ran_commit" "0" "$(ran rw_multik6)"
check "full run on an image its commit is not served does not record perf_regression_ran_commit" "0" "$(ran "" older)"
check "full run whose requested data-plane A/B failed does not record perf_regression_ran_commit" "0" "$(ran "" abc false)"
final() { # PERF_RUN_ARM validity.valid -> exit code of the real final block
  local d="$WORK/final-${1:-full}-$2"; mkdir -p "$d"
  jq -n --argjson v "$2" '{run_arm: "hw_matrix", agent: {instance_type: "c6i.32xlarge", queue: "perf-xl"},
    validity: {valid: $v, checks: [{name: "fixture", ok: $v}]}}' > "$d/result.json"
  env -i PATH="$WORK/bin:$PATH" UPLOAD_DIR="$d" PERF_RUN_ARM="$1" PERF_RUN_NAME=perfxl-hw-matrix \
    ARTIFACT_PREFIX=perfxl-hw-matrix- RESULT_JSON="$d/result.json" bash -c "set -euo pipefail; $ARMFN
$FINAL" >/dev/null 2>&1 && echo 0 || echo "$?"
}
check "arm-only, validity false: the step fails" "1" "$(final hw_matrix false)"
check "arm-only, validity true: the step passes" "0" "$(final hw_matrix true)"
check "full run, validity false: the final block leaves it to compare" "0" "$(final "" false)"

echo "--- 6. an arm-only result records the arm's k6 cpusets, not the unused single-k6 pin"
K6FN="$(awk '/^arm_k6_cpusets\(\) \{/ {p=1} p {print} p && /^}/ {exit}' "$RUN")"
K6BLK="$(awk '/^# --- arm-only k6 placement/ {p=1} p {print} p && /^fi$/ {exit}' "$RUN")"
for b in K6FN K6BLK; do [ -n "${!b}" ] || bad "block $b not found in $RUN"; done
line_of() { grep -nF -- "$1" "$RUN" | tail -1 | cut -d: -f1 || true; }
L_BLK="$(line_of '# --- arm-only k6 placement')"
L_RW="$(line_of 'SERVING_RW_MULTIK6_JSON="')"
L_HW="$(line_of 'SERVING_HW_MATRIX_JSON="')"
L_ASM="$(line_of '--argjson k6_cpus "$AGENT_K6_CPUS_JSON"')"
check "the block runs after both arms and before the result is assembled" "yes" \
  "$([ -n "$L_BLK" ] && [ -n "$L_ASM" ] && [ "$L_BLK" -gt "${L_RW:-0}" ] && [ "$L_BLK" -gt "${L_HW:-0}" ] && [ "$L_BLK" -lt "$L_ASM" ] && echo yes || echo no)"
check "the result's .agent.k6_cpus is the block's value" "1" "$(grep -c '^ *server_cpus: \$server_cpus, k6_cpus: \$k6_cpus,' "$RUN" || true)"
placement() { # PERF_RUN_ARM rw_json hw_json -> "agent k6_cpus|config [k6, physical k6, pin pct]|K6_PHYS_CORES"
  env -i PATH="$PATH" PERF_RUN_ARM="$1" RW="$2" HW="$3" bash -c "set -euo pipefail
phys_core_count() { tr ',' '\n' <<<\"\$1\" | wc -l | tr -d ' '; }
$ARMFN
$K6FN
K6_CPUS=32-48; K6_PHYS_CORES=17; SERVING_RW_MULTIK6_JSON=\"\$RW\"; SERVING_HW_MATRIX_JSON=\"\$HW\"
CONFIG_JSON='{\"cpusets\":{\"server\":\"0-5\",\"k6\":\"32-48\",\"physical_cores\":{\"server\":6,\"k6\":17}},\"k6_cpu_pin_pct\":1700}'
$K6BLK
echo \"\$AGENT_K6_CPUS_JSON|\$(jq -c '[.cpusets.k6, .cpusets.physical_cores.k6, .k6_cpu_pin_pct]' <<<\"\$CONFIG_JSON\")|\$K6_PHYS_CORES\"" 2>/dev/null | tail -1
}
obs() { # procs role=cpuset... -> a rw_multik6 block with that observed placement
  local n="$1"; shift
  printf '%s\n' "$@" | jq -Rsc --argjson n "$n" 'split("\n") | map(select(. != "") | split("=") | {role: .[0], container: "c", cpuset_cpus: .[1]})
    | {method: {procs: $n}, placement: {observed: ([{role: "sut", cpuset_cpus: "0-5"}] + .)}}'
}
RW2="$(obs 2 k6_main_p0=32-39,96-103 k6_main_p1=40-47,104-111 k6_xcheck_p0=56-63 prometheus=7)"
check "full run: the single-k6 pin, as before" '"32-48"|["32-48",17,1700]|17' "$(placement "" "$RW2" '{}')"
check "rw_multik6: every measuring process's observed cpuset, in order" \
  '"32-39,96-103;40-47,104-111"|["32-39,96-103;40-47,104-111",4,null]|4' "$(placement rw_multik6 "$RW2" '{}')"
check "rw_multik6, a process ran unrestricted: null, not a partial list" 'null|[null,null,null]|null' \
  "$(placement rw_multik6 "$(obs 2 k6_main_p0=32-39 k6_main_p1=)" '{}')"
check "rw_multik6, fewer processes observed than ran: null" 'null|[null,null,null]|null' \
  "$(placement rw_multik6 "$(obs 2 k6_main_p0=32-39)" '{}')"
check "rw_multik6, the arm recorded no placement: null" 'null|[null,null,null]|null' "$(placement rw_multik6 '{}' '{}')"
check "hw_matrix: the multi-k6 client's cpusets" '"32-39;40-47"|["32-39;40-47",2,null]|2' \
  "$(placement hw_matrix "$RW2" '{"client_placement":{"k6_cpusets":["32-39","40-47"]}}')"
check "hw_matrix, single-k6 client (no fixed client placement): null" 'null|[null,null,null]|null' "$(placement hw_matrix '{}' '{}')"

echo "--- 7. the weekly perf-xl schedule (pipelines.tf) dispatches when the daily already measured HEAD"
TF="$REPO_ROOT/terraform/buildkite-pipelines/pipelines.tf"
SCHED="$(awk '/^resource "buildkite_pipeline_schedule" "perf_xl_weekly"/ {p=1} p {print} p && /^}/ {exit}' "$TF")"
XLW_MSG="$(sed -n 's/^  message *= "\(.*\)"$/\1/p' <<<"$SCHED")"
XLW_ENV="$(sed -n 's/^    PERF_XL *= "\(.*\)"$/\1/p' <<<"$SCHED")"
check "schedule found, with a message and PERF_XL" "1|1" "$([ -n "$XLW_MSG" ] && echo 1 || echo 0)|$([ -n "$XLW_ENV" ] && echo 1 || echo 0)"
mkdir -p "$WORK/bin7"
HEAD_SHA="$(git -C "$REPO_ROOT" rev-parse HEAD)"
printf '#!/usr/bin/env bash\necho fake-token\n' > "$WORK/bin7/aws"
printf '#!/usr/bin/env bash\necho %s\n' "'[{\"meta_data\":{\"perf_regression_ran_commit\":\"$HEAD_SHA\"}}]'" > "$WORK/bin7/curl"
chmod +x "$WORK/bin7/aws" "$WORK/bin7/curl"
guard weekly PATH="$WORK/bin7:$WORK/bin:$PATH" BUILDKITE_SOURCE=schedule BUILDKITE_MESSAGE="$XLW_MSG" PERF_XL="$XLW_ENV"
guard weekly_untagged PATH="$WORK/bin7:$WORK/bin:$PATH" BUILDKITE_SOURCE=schedule BUILDKITE_MESSAGE="Scheduled: daily performance regression run" PERF_XL="$XLW_ENV"
check "control: a scheduled build on a measured HEAD is skipped" "0" "$(uploads weekly_untagged)"
check "weekly: regression and perf-xl uploads" "2" "$(uploads weekly)"
check "weekly: the multi-k6 arm runs on perf-xl" "1" "$(grep -c 'PERF_RUN_ARM: "rw_multik6"' "$WORK/weekly/upload-2.yml" 2>/dev/null || true)"

if [ "$FAILS" -gt 0 ]; then echo "FAILED: $FAILS check(s)" >&2; exit 1; fi
echo "all perf-xl dispatch checks passed"
