#!/usr/bin/env bash
# Docker-free checks for the data-plane hooks A/B: lib/perf-dataplane-ab.sh's pair order, sizes, result
# assembly and fail-closed verdict, its orchestration against stubbed docker and a fake cgroup tree, the
# wiring in perf-test-run.sh, and that the compare step ignores the block.
# Run: .buildkite/scripts/test/perf-dataplane-ab-test.sh
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)"
# shellcheck source=lib/perf-test-env.sh
. "$REPO_ROOT/.buildkite/scripts/test/lib/perf-test-env.sh"
perf_test_scrub_env
LIB="$REPO_ROOT/.buildkite/scripts/steps/lib/perf-dataplane-ab.sh"
RUN="$REPO_ROOT/.buildkite/scripts/steps/perf-test-run.sh"
COMPARE="$REPO_ROOT/.buildkite/scripts/steps/perf-test-compare.sh"
FAILS=0
ok()   { echo "  ok   $1"; }
bad()  { echo "  FAIL $1" >&2; FAILS=$((FAILS + 1)); }
check() { # name expected actual
  if [ "$2" = "$3" ]; then ok "$1"; else bad "$1: expected '$2', got '$3'"; fi
}
WORK="$(mktemp -d "${TMPDIR:-/tmp}/perf-dataplane-ab-test.XXXXXX")"
trap 'rm -rf "$WORK"' EXIT
# shellcheck source=../steps/lib/perf-clustered-ab.sh
. "$REPO_ROOT/.buildkite/scripts/steps/lib/perf-clustered-ab.sh"
# shellcheck source=../steps/lib/perf-dataplane-ab.sh
. "$LIB"

echo "--- 1. pair order: a rotation, each arm in each position once over three pairs"
check "build 0, pair 1" "none h1 h2" "$(dp_pair_order 1 0)"
check "build 0, pair 2" "h1 h2 none" "$(dp_pair_order 2 0)"
check "build 0, pair 3" "h2 none h1" "$(dp_pair_order 3 0)"
check "build 1, pair 1 starts where build 0 pair 2 did" "h1 h2 none" "$(dp_pair_order 1 1)"
check "garbage build number reads as 0" "none h1 h2" "$(dp_pair_order 1 abc)"
check "every arm opens one pair" "h1 h2 none" "$(for p in 1 2 3; do dp_pair_order "$p" 7 | cut -d' ' -f1; done | sort | paste -sd' ' -)"

echo "--- 2. sizes, rates and extension classes"
check "1kb,1mb accepted" "0" "$(dp_validate_sizes 1kb,1mb && echo 0 || echo 1)"
check "8mb accepted" "0" "$(dp_validate_sizes 8mb && echo 0 || echo 1)"
for s in 9mb 0kb 1gb 64 "1kb,*" "" 10000kb; do
  check "size list '$s' refused" "1" "$(dp_validate_sizes "$s" 2>/dev/null && echo 0 || echo 1)"
done
check "1kb is 1024 bytes, 1mb is 1048576" "1024|1048576" "$(dp_size_bytes 1kb)|$(dp_size_bytes 1mb)"
check "a kb body at 200 rps, an mb body at 50" "200|50" "$(dp_rate_for 64kb)|$(dp_rate_for 1mb)"
check "  ... both overridable" "300|20" "$(K6_DP_RATE_KB=300 dp_rate_for 1kb)|$(K6_DP_RATE_MB=20 dp_rate_for 2mb)"
check "none loads nothing, h1 the filter, h2 the transformer" \
  "|org.mockserver.perf.dataplane.IdentityRequestFilter|org.mockserver.perf.dataplane.IdentityResponseTransformer" \
  "$(dp_extensions_for none)|$(dp_extensions_for h1)|$(dp_extensions_for h2)"
check "an unknown arm is refused" "1" "$(dp_extensions_for h3 >/dev/null && echo 0 || echo 1)"
check "the extension sources exist where the lib compiles them" "3" "$(find "$DP_EXT_SRC" -name '*.java' | wc -l | tr -d ' ')"

echo "--- 3. assembly: a genuine A/B"
window() { # pair size arm p50 p95 cpu_usec [error_rate] [probe_ok] [dropped] -> one dp-windows.ndjson line
  jq -nc --argjson pair "$1" --arg size "$2" --arg arm "$3" --argjson p50 "$4" --argjson p95 "$5" --argjson cpu "$6" \
    --argjson e "${7:-0}" --argjson pok "${8:-true}" --argjson dropped "${9:-0}" \
    '{pair: $pair, size: $size, arm: $arm, rc: 0,
      cpu_before: {usage_usec: 1000000}, cpu_after: (if $cpu == null then null else {usage_usec: (1000000 + $cpu)} end),
      result: {mode: "window", arm: $arm, size: $size, bytes: (if $size == "1kb" then 1024 else 1048576 end),
               probe: {ok: $pok}, p50_ms: $p50, p95_ms: $p95, p99_ms: (if $p95 == null then null else $p95 * 2 end), sample_count: 4000, settle_excluded: 1000,
               throughput_rps: 200, offered_rps: 200, dropped_iterations: $dropped, error_rate: $e}}'
}
genuine() { # file
  : > "$1"
  for p in 1 2 3; do
    window "$p" 1kb none 1.0 2.0 500000 >> "$1"; window "$p" 1kb h1 1.2 2.4 600000 >> "$1"; window "$p" 1kb h2 1.5 3.0 750000 >> "$1"
    window "$p" 1mb none 4.0 8.0 2500000 >> "$1"; window "$p" 1mb h1 "$((5 + p))" 12.0 4000000 >> "$1"; window "$p" 1mb h2 8.0 16.0 5000000 >> "$1"
  done
}
genuine "$WORK/genuine.ndjson"
G="$(dp_assemble_json "$WORK/genuine.ndjson" 0 1kb,1mb 3 "none,h1,h2 h1,h2,none h2,none,h1")"
check "measured, no problems, no warnings" "true|0|0" "$(jq -r '"\(.measured)|\(.problems | length)|\(.warnings | length)"' <<<"$G")"
check "the arms' absolutes are medians over the pairs" "1|1.2|7" \
  "$(jq -r '"\(.sizes["1kb"].arms.none.p50_ms)|\(.sizes["1kb"].arms.h1.p50_ms)|\(.sizes["1mb"].arms.h1.p50_ms)"' <<<"$G")"
check "CPU per request counts the settle requests too" "100|120|150" \
  "$(jq -r '.sizes["1kb"].arms | "\(.none.cpu_us_per_request)|\(.h1.cpu_us_per_request)|\(.h2.cpu_us_per_request)"' <<<"$G")"
check "ratios against none, per pair then the median" "1.2|1.5|1.75" \
  "$(jq -r '"\(.sizes["1kb"].arms.h1.p50_ratio)|\(.sizes["1kb"].arms.h2.p95_ratio)|\(.sizes["1mb"].arms.h1.p50_ratio)"' <<<"$G")"
check "deltas against none, and the CPU ratio" "0.2|50|1.6" \
  "$(jq -r '"\(.sizes["1kb"].arms.h1.p50_delta_ms)|\(.sizes["1kb"].arms.h2.cpu_us_per_request_delta)|\(.sizes["1mb"].arms.h1.cpu_per_request_ratio)"' <<<"$G")"
check "none carries no ratio" "null|null" "$(jq -r '.sizes["1kb"].arms.none | "\(.p50_ratio)|\(.cpu_us_per_request_delta)"' <<<"$G")"
check "counts are summed over the windows" "3|12000" "$(jq -r '.sizes["1mb"].arms.h2 | "\(.windows)|\(.sample_count)"' <<<"$G")"
check "the copies each hook makes are stated" "2|0|1|2" \
  "$(jq -r '.copies | "\(.h1.request_deep_copies)|\(.h1.response_deep_copies)|\(.h2.request_deep_copies)|\(.h2.response_deep_copies)"' <<<"$G")"
check "the pair orders are recorded" "3|h2,none,h1" "$(jq -r '"\(.pair_orders | length)|\(.pair_orders[2])"' <<<"$G")"

echo "--- 4. assembly: fail-closed"
grep -v '"pair":2,"size":"1mb","arm":"h2"' "$WORK/genuine.ndjson" > "$WORK/missing.ndjson"
M="$(dp_assemble_json "$WORK/missing.ndjson" 0 1kb,1mb 3 "")"
check "a missing window: not measured, no ratios" "false|null" "$(jq -r '"\(.measured)|\(.sizes["1kb"].arms.h1.p50_ratio)"' <<<"$M")"
check "  ... and the problem counts the windows" "1" "$(jq -r '.problems[]' <<<"$M" | grep -c '^17 windows ran, not 18$' || true)"
{ grep -v '"pair":1,"size":"1kb","arm":"h1"' "$WORK/genuine.ndjson"; window 1 1kb h1 1.2 2.4 600000 0 false; } > "$WORK/probe.ndjson"
check "a failed probe: not measured, named" "false|1" \
  "$(dp_assemble_json "$WORK/probe.ndjson" 0 1kb,1mb 3 "" | jq -r '"\(.measured)|\(.problems | map(select(test("pair 1 1kb h1: the setup probe"))) | length)"')"
{ grep -v '"pair":3,"size":"1mb","arm":"none"' "$WORK/genuine.ndjson"; window 3 1mb none 4.0 8.0 2500000 0.02; } > "$WORK/errors.ndjson"
check "2% errors on the control: not measured" "false|1" \
  "$(dp_assemble_json "$WORK/errors.ndjson" 0 1kb,1mb 3 "" | jq -r '"\(.measured)|\(.problems | map(select(test("over the 1% bound"))) | length)"')"
{ grep -v '"pair":1,"size":"1mb","arm":"h2"' "$WORK/genuine.ndjson"; window 1 1mb h2 8.0 16.0 5000000 0 true 3; } > "$WORK/dropped.ndjson"
check "dropped iterations: not measured, named" "false|1" \
  "$(dp_assemble_json "$WORK/dropped.ndjson" 0 1kb,1mb 3 "" | jq -r '"\(.measured)|\(.problems | map(select(test("pair 1 1mb h2: dropped 3 iterations"))) | length)"')"
{ grep -v '"pair":2,"size":"1kb","arm":"none"' "$WORK/genuine.ndjson"; window 2 1kb none 1.0 null 500000; } > "$WORK/nop95.ndjson"
check "no p95: not measured" "false" "$(dp_assemble_json "$WORK/nop95.ndjson" 0 1kb,1mb 3 "" | jq -r .measured)"
{ grep -v '"pair":2,"size":"1kb","arm":"none"' "$WORK/genuine.ndjson"; window 2 1kb none 1.0 2.0 null; } > "$WORK/nocpu.ndjson"
C="$(dp_assemble_json "$WORK/nocpu.ndjson" 0 1kb,1mb 3 "")"
check "no CPU reading: still measured, a warning, and that CPU ratio is null" "true|1|null|1.2" \
  "$(jq -r '"\(.measured)|\(.warnings | length)|\(.sizes["1kb"].arms.h1.cpu_per_request_ratio)|\(.sizes["1kb"].arms.h1.p50_ratio)"' <<<"$C")"
check "no jar: not measured, named" "false|1" \
  "$(dp_assemble_json /dev/null no_jar 1kb,1mb 3 "" | jq -r '"\(.measured)|\(.problems | map(select(test("could not be built"))) | length)"')"
check "a SUT not ready: named with its arm" "1" \
  "$(dp_assemble_json /dev/null sut_not_ready:h2 1kb,1mb 3 "" | jq -r '.problems[]' | grep -c 'sut_not_ready:h2' || true)"

echo "--- 5. orchestration on stubbed docker and a fake cgroup tree"
mkdir -p "$WORK/bin" "$WORK/out" "$WORK/cgroup"
cat > "$WORK/bin/docker" <<'STUB'
#!/usr/bin/env bash
echo "docker $*" >> "$STUB_LOG"
case "$1" in
  image) echo '["java","-cp","/mockserver.jar:/mockserver-deps.jar:/libs/*:/extensions/*","org.mockserver.cli.Main"]'; exit 0 ;;
  create) echo cid-1; exit 0 ;;
  cp) dest="${*: -1}"; touch "$dest/$(basename "${2#*:}")"; exit 0 ;;
  rm) exit 0 ;;
  inspect) [ "$2" = -f ] && { echo "id-${4##*-}"; exit 0; }; exit 1 ;;
esac
[ "$1" = run ] || exit 0
args=" $* "
if [[ "$args" == *" --entrypoint sh "* ]]; then
  [ -n "${STUB_NO_JAR:-}" ] && { echo "javac: error" >&2; exit 1; }
  ext="$(sed -nE 's/.* -v ([^ ]+):\/ext .*/\1/p' <<<"$args")"
  [[ "$args" == *" -v "*":/cp:ro "* ]] || { echo "the classpath is not mounted read-only at /cp" >&2; exit 1; }
  [ -n "${STUB_EMPTY_JAR:-}" ] && exit 0
  echo jar > "$ext/perf-dataplane-identity.jar"; exit 0
fi
mode="$(sed -nE 's/.* K6_DP_MODE=([a-z]+) .*/\1/p' <<<"$args")"
arm="$(sed -nE 's/.* K6_DP_ARM=([a-z0-9]+) .*/\1/p' <<<"$args")"
[ "$mode" = window ] || exit 0
size="$(sed -nE 's/.* K6_DP_SIZE=([0-9a-z]+) .*/\1/p' <<<"$args")"
out="$(sed -nE 's/.* K6_RESULT_PATH=\/out\/([^ ]+) .*/\1/p' <<<"$args")"
echo "window $size $arm" >> "$STUB_LOG"
[ -n "${STUB_K6_EXIT:-}" ] && exit "$STUB_K6_EXIT"
f="$PERF_CGROUP_ROOT/docker/id-$arm/cpu.stat"
u="$(awk '$1 == "usage_usec" { print $2 }' "$f")"
case "$arm" in none) d=500000 ;; h1) d=600000 ;; *) d=750000 ;; esac
printf 'usage_usec %s\nnr_periods 0\nnr_throttled 0\nthrottled_usec 0\n' "$((u + d))" > "$f"
pok=true; [ "${STUB_PROBE_FAIL_ARM:-}" = "$arm" ] && pok=false
case "$arm" in none) p50=1 ;; h1) p50=1.2 ;; *) p50=1.5 ;; esac
jq -nc --arg arm "$arm" --arg size "$size" --argjson p50 "$p50" --argjson pok "$pok" \
  '{mode: "window", arm: $arm, size: $size, bytes: 1024, probe: {ok: $pok}, p50_ms: $p50, p95_ms: ($p50 * 2), p99_ms: ($p50 * 3),
    sample_count: 4000, settle_excluded: 1000, throughput_rps: 200, offered_rps: 200, dropped_iterations: 0, error_rate: 0}' > "$OUT_DIR/$out"
exit 0
STUB
chmod +x "$WORK/bin/docker"
phase() { # build_number [VAR=value...] -> "<attempted>|<skip_reason>|<measured>"; logs in $WORK/stub.log
  : > "$WORK/stub.log"
  local build="$1"; shift
  rm -rf "$WORK/cgroup" "$WORK/out"; mkdir -p "$WORK/out"
  for a in none h1 h2; do mkdir -p "$WORK/cgroup/docker/id-$a"; printf 'usage_usec 1000000\n' > "$WORK/cgroup/docker/id-$a/cpu.stat"; done
  (
    export PATH="$WORK/bin:$PATH" STUB_LOG="$WORK/stub.log" OUT_DIR="$WORK/out" PERF_CGROUP_ROOT="$WORK/cgroup" TMPDIR="$WORK"
    for kv in "$@"; do export "${kv?}"; done
    # shellcheck disable=SC2034  # globals the sourced lib reads
    REPO_ROOT=/repo NETWORK=net K6_IMAGE=k6 K6_CPUS="" SERVER_CPUS=0-5 SERVER_MEMORY=2g MOCKSERVER_IMAGE=sut:snapshot
    # shellcheck disable=SC2034
    BUILDKITE_BUILD_NUMBER="$build"
    cpuset_arg() { :; }
    arm_only() { [ -n "${PERF_RUN_ARM:-}" ]; }
    wait_ready() { echo "wait_ready $1" >> "$STUB_LOG"; [ "${STUB_NOT_READY:-}" != "$1" ]; }
    start_mockserver() { echo "start_mockserver $1 cpus=$2 alias=$3 publish=$4 mem=$5 mount=$6 level=$7 port=${10} env=${START_EXTRA_ENV[*]:-} args=$#" >> "$STUB_LOG"; }
    dp_ab_phase >/dev/null 2>"$WORK/stderr.log"
    echo "$DATAPLANE_AB_ATTEMPTED|$DATAPLANE_AB_SKIP_REASON|$(jq -r 'if .measured == null then "none" else (.measured | tostring) end' <<<"$DATAPLANE_AB_JSON")"
    jq -c . <<<"$DATAPLANE_AB_JSON" > "$WORK/last.json"
  )
}
check "PERF_DATAPLANE_AB unset: skipped as disabled, nothing run" "false|disabled|none|0" "$(phase 4)|$(grep -c . "$WORK/stub.log" || true)"
check "on: attempted and measured" "true||true" "$(phase 4 PERF_DATAPLANE_AB=true)"
check "three SUTs on the main SUT's cpuset and memory, the jar mounted, ERROR level" "3" \
  "$(grep -cE '^start_mockserver mockserver-perf-dp-local-(none|h1|h2) cpus=0-5 alias=dp-ab-(none|h1|h2) publish= mem=2g mount=[^ ]+/ext:/extensions:ro level=ERROR port=1080 ' "$WORK/stub.log" || true)"
check "only h1 and h2 name an extension, each its own" "1|1|1" \
  "$(grep -c '^start_mockserver mockserver-perf-dp-local-none .* env= args=10$' "$WORK/stub.log" || true)|$(grep -c 'local-h1 .*env=-e MOCKSERVER_EXTENSIONS=org.mockserver.perf.dataplane.IdentityRequestFilter args' "$WORK/stub.log" || true)|$(grep -c 'local-h2 .*env=-e MOCKSERVER_EXTENSIONS=org.mockserver.perf.dataplane.IdentityResponseTransformer args' "$WORK/stub.log" || true)"
check "the jar compiles against the image's own -cp, wildcards skipped" "2|1" \
  "$(grep -cE '^docker cp cid-1:/mockserver(-deps)?\.jar ' "$WORK/stub.log" || true)|$(grep -c -- '--entrypoint sh' "$WORK/stub.log" || true)"
check "each SUT is warmed once, with both sizes" "3" "$(grep -c 'K6_DP_MODE=warmup .*K6_DP_SIZES=1kb,1mb' "$WORK/stub.log" || true)"
check "build 4: the windows in the rotated order" "window 1kb h1,window 1kb h2,window 1kb none,window 1mb h1,window 1mb h2,window 1mb none,window 1kb h2,window 1kb none" \
  "$(grep '^window ' "$WORK/stub.log" | head -8 | paste -sd, -)"
check "  ... 18 in all" "18" "$(grep -c '^window ' "$WORK/stub.log" || true)"
check "a 1mb window is offered 50 rps, a 1kb one 200" "9|9" \
  "$(grep -c 'K6_DP_SIZE=1mb -e K6_DP_RATE=50 ' "$WORK/stub.log" || true)|$(grep -c 'K6_DP_SIZE=1kb -e K6_DP_RATE=200 ' "$WORK/stub.log" || true)"
check "CPU per request read from each SUT's cgroup" "100|120|150" \
  "$(jq -r '.sizes["1kb"].arms | "\(.none.cpu_us_per_request)|\(.h1.cpu_us_per_request)|\(.h2.cpu_us_per_request)"' "$WORK/last.json")"
check "the work dir and its classpath copy are world-readable, the jar dir world-writable (userns-remapped dockerd)" "1|1|1|1" \
  "$(find "$WORK" -maxdepth 1 -name 'perf-dataplane.*' -type d -perm 755 | wc -l | tr -d ' ')|$(find "$WORK"/perf-dataplane.* -maxdepth 1 -name cp -type d -perm 755 | wc -l | tr -d ' ')|$(find "$WORK"/perf-dataplane.*/cp -name 'mockserver.jar' -perm 644 | wc -l | tr -d ' ')|$(find "$WORK"/perf-dataplane.* -maxdepth 1 -name ext -type d -perm 777 | wc -l | tr -d ' ')"
check "javac writes its classes inside the container, not into the mounted dirs" "1|0" \
  "$(grep -c -- '-d /tmp/classes' "$WORK/stub.log" || true)|$(grep -c -- ':/work' "$WORK/stub.log" || true)"
check "the SUTs are removed" "3" "$(grep -cE '^docker rm -f mockserver-perf-dp-local-(none|h1|h2)$' "$WORK/stub.log" || true)"
check "a probe failing on h2: not measured" "true||false" "$(phase 4 PERF_DATAPLANE_AB=true STUB_PROBE_FAIL_ARM=h2)"
check "k6 failing: not measured, SUTs still removed" "true||false|3" \
  "$(phase 4 PERF_DATAPLANE_AB=true STUB_K6_EXIT=107)|$(grep -cE '^docker rm -f mockserver-perf-dp-local-' "$WORK/stub.log" || true)"
check "javac exiting 0 with no jar: not measured, no SUT started" "true||false|0" \
  "$(phase 4 PERF_DATAPLANE_AB=true STUB_EMPTY_JAR=1)|$(grep -c '^start_mockserver' "$WORK/stub.log" || true)"
check "no jar: not measured, no SUT started" "true||false|0" \
  "$(phase 4 PERF_DATAPLANE_AB=true STUB_NO_JAR=1)|$(grep -c '^start_mockserver' "$WORK/stub.log" || true)"
check "a SUT never ready: not measured, no window" "true||false|0" \
  "$(phase 4 PERF_DATAPLANE_AB=true STUB_NOT_READY=mockserver-perf-dp-local-h1)|$(grep -c '^window ' "$WORK/stub.log" || true)"
check "a malformed size: not measured, no SUT" "true||false|0" \
  "$(phase 4 PERF_DATAPLANE_AB=true PERF_DATAPLANE_AB_SIZES=1gb)|$(grep -c '^start_mockserver' "$WORK/stub.log" || true)"
check "one pair, one size: 3 windows" "true||true|3" \
  "$(phase 4 PERF_DATAPLANE_AB=true PERF_DATAPLANE_AB_PAIRS=1 PERF_DATAPLANE_AB_SIZES=4kb)|$(grep -c '^window ' "$WORK/stub.log" || true)"
check "an arm-only run: skipped" "false|arm_only|none" "$(phase 4 PERF_DATAPLANE_AB=true PERF_RUN_ARM=rw_multik6)"
check "a release comparison: skipped" "false|release_comparison|none" "$(phase 4 PERF_DATAPLANE_AB=true PERF_RELEASE_COMPARISON=1.2.3)"
check "host network: skipped" "false|host_network|none" "$(phase 4 PERF_DATAPLANE_AB=true PERF_NETWORK_MODE=host)"

echo "--- 5b. the step's verdict when the A/B was asked for"
verdict() { # result_json [VAR=value...] -> exit code of dp_ab_require_measured
  local f="$1" rc=0; shift
  ( for kv in "$@"; do export "${kv?}"; done; dp_ab_require_measured "$f" ) >/dev/null 2>&1 || rc=$?
  echo "$rc"
}
jq -nc --argjson dp "$G" '{dataplane_hooks: $dp, dataplane_hooks_attempted: true, dataplane_hooks_skip_reason: ""}' > "$WORK/r-measured.json"
jq -nc --argjson dp "$M" '{dataplane_hooks: $dp, dataplane_hooks_attempted: true, dataplane_hooks_skip_reason: ""}' > "$WORK/r-unmeasured.json"
jq -nc '{dataplane_hooks: {}, dataplane_hooks_attempted: false, dataplane_hooks_skip_reason: "arm_only"}' > "$WORK/r-armonly.json"
jq -nc '{dataplane_hooks: {}, dataplane_hooks_attempted: false, dataplane_hooks_skip_reason: "host_network"}' > "$WORK/r-host.json"
printf 'not json' > "$WORK/r-broken.json"
printf '{}' > "$WORK/r-empty.json"
jq -nc --argjson dp "$G" '{dataplane_hooks: $dp, dataplane_hooks_attempted: "true"}' > "$WORK/r-string.json"
check "not asked for: never fails, whatever the result" "0|0" "$(verdict "$WORK/r-unmeasured.json")|$(verdict "$WORK/r-broken.json")"
check "asked for and measured: passes" "0" "$(verdict "$WORK/r-measured.json" PERF_DATAPLANE_AB=true)"
check "asked for, ran, not measured: fails the step" "1" "$(verdict "$WORK/r-unmeasured.json" PERF_DATAPLANE_AB=true)"
check "asked for, result unreadable: fails closed" "1" "$(verdict "$WORK/r-broken.json" PERF_DATAPLANE_AB=true)"
check "asked for, no dataplane_hooks_attempted field: fails closed" "1" "$(verdict "$WORK/r-empty.json" PERF_DATAPLANE_AB=true)"
check "asked for, a non-boolean dataplane_hooks_attempted: fails closed" "1" "$(verdict "$WORK/r-string.json" PERF_DATAPLANE_AB=true)"
check "asked for, an arm-only run: passes silently" "0|0" \
  "$(verdict "$WORK/r-armonly.json" PERF_DATAPLANE_AB=true)|$(PERF_DATAPLANE_AB=true dp_ab_require_measured "$WORK/r-armonly.json" 2>&1 | grep -c . || true)"
check "asked for, skipped for host network: passes, but says so" "0|1" \
  "$(verdict "$WORK/r-host.json" PERF_DATAPLANE_AB=true)|$(PERF_DATAPLANE_AB=true dp_ab_require_measured "$WORK/r-host.json" 2>&1 | grep -c 'skipped the A/B (host_network)' || true)"
check "the failure names the problems" "1" \
  "$(PERF_DATAPLANE_AB=true dp_ab_require_measured "$WORK/r-unmeasured.json" 2>&1 | grep -c '17 windows ran, not 18' || true)"

echo "--- 6. wiring"
check "the lib is sourced" "1" "$(grep -c '^\. "\$SCRIPT_DIR/lib/perf-dataplane-ab.sh"$' "$RUN" || true)"
check "the phase runs once, after a liveness check" "1" "$(grep -B1 '^dp_ab_phase$' "$RUN" | grep -c '^abort_if_sut_died$' || true)"
check "cleanup removes the SUTs" "1" "$(grep -c '^  declare -F dp_ab_cleanup >/dev/null && dp_ab_cleanup$' "$RUN" || true)"
check "the block is not a validity check" "0" "$(grep -c 'add_check "dataplane' "$RUN" || true)"
check "the result carries the block" "3" "$(grep -cE '^    dataplane_hooks(_attempted|_skip_reason)?: \$dataplane_hooks' "$RUN" || true)"
check "the allocation profile turns it off" "1" "$(grep -c '^PERF_DATAPLANE_AB=false \\$' "$REPO_ROOT/.buildkite/scripts/steps/perf-test-allocprofile.sh" || true)"
uploaded="$(grep -n 'bk_upload_artifact "perf-result.json"' "$RUN" | head -1 | cut -d: -f1)"
exit_at="$(grep -n '^\[ "\$DP_AB_OK" = true \] || exit 1$' "$RUN" | cut -d: -f1)"
check "the step verdict runs once, and fails the step only after perf-result.json is uploaded" "1|true" \
  "$(grep -c '^dp_ab_require_measured ' "$RUN" || true)|$([ -n "$exit_at" ] && [ -n "$uploaded" ] && [ "$exit_at" -gt "$uploaded" ] && echo true || echo false)"
check "the guard lengthens the run step when it is on" "1" "$(grep -c 'PERF_DATAPLANE_AB:-false}" = "true" ]; then' "$REPO_ROOT/.buildkite/scripts/steps/perf-test-guard.sh" || true)"

echo "--- 6b. a failed A/B leaves the commit unrecorded, so the next daily run is not guard-skipped"
sed -n '/^DP_AB_OK=true$/,/^\[ "\$DP_AB_OK" = true \] || exit 1$/p' "$RUN" > "$WORK/tail.sh"
check "the run's closing block is extracted" "1|1" \
  "$(grep -c '^DP_AB_OK=true$' "$WORK/tail.sh" || true)|$(grep -c 'meta-data set "perf_regression_ran_commit"' "$WORK/tail.sh" || true)"
mkdir -p "$WORK/tbin" "$WORK/troot"
cat > "$WORK/tbin/buildkite-agent" <<'STUB'
#!/usr/bin/env bash
[ "$1" = annotate ] && cat > /dev/null
echo "$*" >> "$TAIL_LOG"
STUB
chmod +x "$WORK/tbin/buildkite-agent"
closing() { # result_json [VAR=value...] -> "exit code|ran-commit recorded|perf-result.json uploaded"
  local f="$1" rc=0; shift
  : > "$WORK/tail.log"
  ( for kv in "$@"; do export "${kv?}"; done
    export PATH="$WORK/tbin:$PATH" TAIL_LOG="$WORK/tail.log"
    # shellcheck disable=SC2034  # read by the sourced closing block
    REPO_ROOT="$WORK/troot" RESULT_JSON="$f" HARNESS_COMMIT=0123456789abcdef PERF_RELEASE_COMPARISON=""
    arm_only() { [ -n "${PERF_RUN_ARM:-}" ]; }
    harness_commit_measured() { return 0; }
    bk_upload_artifact() { echo "upload $1" >> "$TAIL_LOG"; }
    upload_diag_bundle() { :; }
    # shellcheck disable=SC1091
    . "$WORK/tail.sh" ) >/dev/null 2>&1 || rc=$?
  echo "$rc|$(grep -c '^meta-data set perf_regression_ran_commit 0123456789abcdef$' "$WORK/tail.log" || true)|$(grep -c '^upload perf-result.json$' "$WORK/tail.log" || true)"
}
check "A/B asked for and not measured: the step fails, the result is uploaded, no ran-commit" "1|0|1" \
  "$(closing "$WORK/r-unmeasured.json" PERF_DATAPLANE_AB=true)"
check "A/B asked for, the field missing: the same" "1|0|1" "$(closing "$WORK/r-empty.json" PERF_DATAPLANE_AB=true)"
check "A/B asked for and measured: the ran-commit is recorded" "0|1|1" "$(closing "$WORK/r-measured.json" PERF_DATAPLANE_AB=true)"
check "A/B not asked for: the ran-commit is recorded" "0|1|1" "$(closing "$WORK/r-unmeasured.json")"

echo "--- 7. compare ignores the block"
if [ "$(bash -c 'echo "${BASH_VERSINFO[0]}"')" -lt 4 ]; then
  if [ "${BUILDKITE:-}" = "true" ]; then echo "FAIL: bash on PATH is older than 4; perf-test-compare.sh cannot run" >&2; exit 1; fi
  echo "  SKIP: bash on PATH is older than 4 (perf-test-compare.sh needs mapfile)"
else
  B="$WORK/bucket"; mkdir -p "$WORK/cbin" "$B/runs/master"
  cat > "$WORK/cbin/aws" <<'STUB'
#!/usr/bin/env bash
case "$1 $2" in
  "s3 cp")
    if [[ "$4" == s3://* ]]; then k="${4#s3://*/}"; mkdir -p "$FAKE_BUCKET/$(dirname "$k")"; cp "$3" "$FAKE_BUCKET/$k"
    else cp "$FAKE_BUCKET/${3#s3://*/}" "$4"; fi ;;
  "s3 ls") p="${3#s3://*/}"; [ -d "$FAKE_BUCKET/$p" ] && (cd "$FAKE_BUCKET" && find "$p" -type f | sort | sed 's/^/2026-10-01 00:00:00 100 /') ;;
  *) exit 1 ;;
esac
STUB
  cat > "$WORK/cbin/buildkite-agent" <<'STUB'
#!/usr/bin/env bash
if [ "$1 $2" = "artifact download" ]; then [ "$3" = perf-result.json ] && cp "$HEAD_RESULT" "$4/perf-result.json" && exit 0; exit 1; fi
[ "$1" = annotate ] && cat > /dev/null
exit 0
STUB
  chmod +x "$WORK/cbin/aws" "$WORK/cbin/buildkite-agent"
  result() { # timestamp dataplane_json
    jq -nc --arg ts "$1" --argjson dp "$2" '{
      schema_version: 3, commit: "0123456789abcdef0123456789abcdef01234567", branch: "master", timestamp_utc: $ts,
      agent: {instance_type: "c5.12xlarge", instance_type_source: "observed", queue: "perf"},
      config: {image_stale: false, config_profile: "default", rig_profile: "default", jvm_diagnostics: "standard"},
      validity: {valid: true, checks: [{name: "fixture", ok: true, detail: ""}]}, baseline_eligible: true,
      behaviours: {match: {p95_ms: 1.5}},
      dataplane_hooks: $dp, dataplane_hooks_attempted: ($dp != {}), dataplane_hooks_skip_reason: (if $dp == {} then "disabled" else "" end)}'
  }
  for i in 1 2 3 4 5 6; do result "2026-09-0${i}T04:00:00Z" '{}' > "$B/runs/master/2026-09-0${i}T04-00-00Z__000000000${i}.json"; done
  result 2026-10-01T04:00:00Z "$G" > "$WORK/head.json"
  rc=0
  env -i PATH="$WORK/cbin:$PATH" HOME="${HOME:-/tmp}" TMPDIR="$WORK" FAKE_BUCKET="$B" HEAD_RESULT="$WORK/head.json" \
    PERF_BUDGETS_COMMIT=fixture bash "$COMPARE" >"$WORK/compare.log" 2>&1 || rc=$?
  check "compare exits 0 on a run that carries the block" "0" "$rc"
  check "  ... reads no row from it and needs no budget for it" "0|0" \
    "$(grep -c 'dataplane' "$WORK/compare.log" || true)|$(grep -c 'Perf budget INCOMPLETE' "$WORK/compare.log" || true)"
fi

if [ "$FAILS" -gt 0 ]; then echo "FAILED: $FAILS check(s)" >&2; exit 1; fi
echo "all data-plane hooks A/B checks passed"
