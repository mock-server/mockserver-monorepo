#!/usr/bin/env bash
# Data-plane hooks A/B, sourced by perf-test-run.sh: what an identity request filter (H1) and an identity
# response transformer (H2) cost per request, against the same server with no extension loaded, at each
# body size. Three SUTs from the run's image (none, h1, h2) stay up on the main SUT's cpuset; their windows
# alternate pair by pair, and each window reads its SUT's cgroup CPU. Opt-in (PERF_DATAPLANE_AB=true),
# notify-only, and kept out of the run's validity block. Defines functions only.
# Docs: docs/code/performance-measurement.md. Tests: .buildkite/scripts/test/perf-dataplane-ab-test.sh

DP_SUT_PREFIX="mockserver-perf-dp-${RUN_ID:-local}"
DP_ARMS="none h1 h2"
DP_EXT_SRC="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../../.." && pwd)/mockserver-performance-test/extensions/dataplane-identity/src"
DP_FILTER_CLASS="org.mockserver.perf.dataplane.IdentityRequestFilter"
DP_TRANSFORMER_CLASS="org.mockserver.perf.dataplane.IdentityResponseTransformer"
DP_JAR_NAME="perf-dataplane-identity.jar"

dp_alias() { echo "dp-ab-$1"; }

dp_extensions_for() { # arm
  case "$1" in
    none) echo "" ;;
    h1) echo "$DP_FILTER_CLASS" ;;
    h2) echo "$DP_TRANSFORMER_CLASS" ;;
    *) return 1 ;;
  esac
}

# Fails on a malformed list: each size is <n>kb or <n>mb, at most 8mb (under the 10 MiB request body limit).
dp_validate_sizes() { # sizes
  local size list n
  IFS=',' read -ra list <<<"$1"
  [ "${#list[@]}" -gt 0 ] || { echo "WARNING: data-plane A/B: no sizes" >&2; return 1; }
  for size in "${list[@]}"; do
    if ! [[ "$size" =~ ^([1-9][0-9]{0,3})(kb|mb)$ ]]; then
      echo "WARNING: data-plane A/B: size '$size' is not <n>kb or <n>mb" >&2; return 1
    fi
    n="$(dp_size_bytes "$size")"
    [ "$n" -le $((8 * 1024 * 1024)) ] || { echo "WARNING: data-plane A/B: size '$size' is over 8mb" >&2; return 1; }
  done
}

dp_size_bytes() { # size
  local n="${1%[km]b}"
  case "$1" in *kb) echo $((n * 1024)) ;; *mb) echo $((n * 1024 * 1024)) ;; esac
}

# A kb body is offered at K6_DP_RATE_KB (200 rps, as regression.js), an mb body at K6_DP_RATE_MB (50 rps).
dp_rate_for() { # size
  case "$1" in *mb) echo "${K6_DP_RATE_MB:-50}" ;; *) echo "${K6_DP_RATE_KB:-200}" ;; esac
}

# Pair <i>'s arm order: a rotation of "none h1 h2" that moves one place per pair and starts from the
# build number, so over three pairs each arm takes each position once.
dp_pair_order() { # pair build_number
  local n="${2:-0}" i arms=(none h1 h2) rotated=()
  [[ "$n" =~ ^[0-9]+$ ]] || n=0
  for i in 0 1 2; do rotated+=("${arms[$(( (n + $1 - 1 + i) % 3 ))]}"); done
  echo "${rotated[*]}"
}

# Builds the identity extension jar into <dir>/ext against the jars on the SUT image's own -cp, so the
# SPI it compiles against is the one the measured image runs. Needs docker and a JDK image. The agents'
# dockerd remaps user namespaces, so the container's uid is not the host uid that owns <dir>: what it
# reads must be world-readable and what it writes world-writable, or javac sees an empty classpath.
dp_build_extension_jar() { # dir
  local dir="$1" cid cp entry entries jdk="${PERF_DATAPLANE_JDK_IMAGE:-${PERF_JFR_JDK_IMAGE:-eclipse-temurin:17-jdk}}" rc=0
  mkdir -p "$dir/cp" "$dir/ext" || return 1
  cp="$(docker image inspect -f '{{json .Config.Entrypoint}}' "$MOCKSERVER_IMAGE" 2>/dev/null \
        | jq -r 'index("-cp") as $i | if $i == null then empty else .[$i + 1] end' 2>/dev/null)" || cp=""
  [ -n "$cp" ] || { echo "WARNING: data-plane A/B: no -cp in the entrypoint of $MOCKSERVER_IMAGE" >&2; return 1; }
  cid="$(docker create "$MOCKSERVER_IMAGE" 2>/dev/null)" || { echo "WARNING: data-plane A/B: docker create $MOCKSERVER_IMAGE failed" >&2; return 1; }
  IFS=':' read -ra entries <<<"$cp"
  for entry in "${entries[@]}"; do
    case "$entry" in *'*'*) continue ;; esac
    docker cp "$cid:$entry" "$dir/cp/" >/dev/null 2>&1 || { echo "WARNING: data-plane A/B: could not copy $entry out of the image" >&2; rc=1; }
  done
  docker rm -f "$cid" >/dev/null 2>&1 || true
  [ "$rc" -eq 0 ] || return 1
  chmod 0755 "$dir" "$dir/cp" && chmod 0644 "$dir"/cp/*.jar && chmod 0777 "$dir/ext" || return 1
  # shellcheck disable=SC2016  # expanded by the container's sh
  docker run --rm --user "$(id -u):$(id -g)" -v "$DP_EXT_SRC:/src:ro" -v "$dir/cp:/cp:ro" -v "$dir/ext:/ext" --entrypoint sh "$jdk" \
    -c 'javac --release 17 -nowarn -cp "/cp/*" -d /tmp/classes $(find /src -name "*.java") && jar cf /ext/'"$DP_JAR_NAME"' -C /tmp/classes . && chmod 0644 /ext/'"$DP_JAR_NAME" \
    > "$dir/build.log" 2>&1 || { echo "WARNING: data-plane A/B: the identity extension did not compile:" >&2; head -20 "$dir/build.log" >&2 || true; return 1; }
  [ -s "$dir/ext/$DP_JAR_NAME" ] || { echo "WARNING: data-plane A/B: javac exited 0 but wrote no $DP_JAR_NAME" >&2; return 1; }
}

# One measured window: snapshot the arm's cgroup CPU, run dataplane_ab.js against it, snapshot again,
# and append {pair, size, arm, rc, cpu_before, cpu_after, result} to dp-windows.ndjson. Never fails.
dp_window() { # pair size arm
  local pair="$1" size="$2" arm="$3" out="dp-p$1-$2-$3.json" rc=0 before after stat_var stat_file result
  stat_var="DP_STAT_FILE_$arm"; stat_file="${!stat_var:-}"
  before="$( (declare -F clu_cpu_stat_json >/dev/null && clu_cpu_stat_json "$stat_file") 2>/dev/null || echo null)"
  rm -f "$OUT_DIR/$out"
  # shellcheck disable=SC2046
  docker run --rm --network "$NETWORK" $(cpuset_arg "$K6_CPUS") \
    -v "$REPO_ROOT/mockserver-performance-test/k6:/k6:ro" -v "$OUT_DIR:/out" \
    -e "BASE_URL=http://$(dp_alias "$arm"):1080" -e "K6_RESULT_PATH=/out/$out" \
    -e K6_DP_MODE=window -e "K6_DP_ARM=$arm" -e "K6_DP_SIZE=$size" -e "K6_DP_RATE=$(dp_rate_for "$size")" \
    ${K6_DP_VUS:+-e K6_DP_VUS="$K6_DP_VUS"} ${K6_DP_DURATION:+-e K6_DP_DURATION="$K6_DP_DURATION"} \
    ${K6_DP_SETTLE:+-e K6_DP_SETTLE="$K6_DP_SETTLE"} \
    "$K6_IMAGE" run /k6/dataplane_ab.js > "$OUT_DIR/dp-p$pair-$size-$arm-k6.log" 2>&1 || rc=$?
  after="$( (declare -F clu_cpu_stat_json >/dev/null && clu_cpu_stat_json "$stat_file") 2>/dev/null || echo null)"
  [ "$rc" -eq 0 ] || { echo "WARNING: data-plane A/B: pair $pair $size window on $arm exited $rc; its last lines:" >&2; tail -8 "$OUT_DIR/dp-p$pair-$size-$arm-k6.log" >&2 || true; }
  result="$(jq -ce 'select(type == "object")' "$OUT_DIR/$out" 2>/dev/null)" || result=null
  jq -e . >/dev/null 2>&1 <<<"$before" || before=null
  jq -e . >/dev/null 2>&1 <<<"$after" || after=null
  jq -nc --argjson pair "$pair" --arg size "$size" --arg arm "$arm" --argjson rc "$rc" \
    --argjson before "$before" --argjson after "$after" --argjson result "$result" \
    '{pair: $pair, size: $size, arm: $arm, rc: $rc, cpu_before: $before, cpu_after: $after, result: $result}' \
    >> "$OUT_DIR/dp-windows.ndjson" || true
}

# The .dataplane_hooks block from dp-windows.ndjson. Per size and arm, absolutes are medians over the
# pairs (error_rate a mean, counts a sum); for h1 and h2 each ratio and delta against none is the median
# of the per-pair values, and is null unless every pair has one. measured is false, and no ratio or delta
# is published, unless every window ran, its setup proved the arm's hook, it has a p95 and a p99 with at
# most 1% errors, and it dropped no iteration (a drop hides the slowest requests). A missing CPU reading is
# a warning, not a failure.
dp_assemble_json() { # windows_ndjson status sizes pairs pair_orders
  local w; w="$(jq -sc . "$1" 2>/dev/null)" || w='[]'
  jq -nc --argjson w "$w" --arg status "$2" --arg sizes "$3" --argjson pairs "$4" --arg orders "$5" '
    def median: map(select(. != null)) | sort | if length == 0 then null
      elif length % 2 == 1 then .[(length / 2 | floor)] else (.[length / 2 - 1] + .[length / 2]) / 2 end;
    def r3: if . == null then null else (. * 1000 | round) / 1000 end;
    def ratio(a; b): if a != null and (b // 0) > 0 then a / b else null end;
    def cpu_per_req: if .cpu_before.usage_usec != null and .cpu_after.usage_usec != null
        and ((.result.sample_count // 0) + (.result.settle_excluded // 0)) > 0
      then (.cpu_after.usage_usec - .cpu_before.usage_usec) / (.result.sample_count + .result.settle_excluded) else null end;
    def all_pairs(f): if (map(f) | map(select(. != null)) | length) == $pairs then (map(f) | median | r3) else null end;
    ($sizes | split(",") | map(select(. != ""))) as $want
    | ["none", "h1", "h2"] as $arms
    | ([ (if $status == "0" then empty
          elif $status == "no_jar" then "the identity extension jar could not be built against the SUT image"
          elif ($status | startswith("sut_not_ready")) then "a SUT did not become ready (\($status)), so nothing was measured"
          elif $status == "bad_sizes" then "the sizes \($sizes) are not a list of <n>kb or <n>mb up to 8mb"
          else "the A/B stopped: \($status)" end),
         (if $status == "0" and ($w | length) != (($want | length) * 3 * $pairs)
          then "\($w | length) windows ran, not \(($want | length) * 3 * $pairs)" else empty end),
         ($w[] | . as $x
          | if $x.rc != 0 or $x.result == null then "pair \($x.pair) \($x.size) \($x.arm): dataplane_ab.js exited \($x.rc) or wrote no result (its setup probe fails closed)"
            elif ($x.result.probe.ok // false) != true then "pair \($x.pair) \($x.size) \($x.arm): the setup probe did not prove the arm"
            elif $x.result.arm != $x.arm or $x.result.size != $x.size then "pair \($x.pair) \($x.size) \($x.arm): k6 measured \($x.result.arm) \($x.result.size)"
            elif $x.result.p95_ms == null or $x.result.p99_ms == null then "pair \($x.pair) \($x.size) \($x.arm): no p95 or p99 (\($x.result.sample_count) samples)"
            elif ($x.result.error_rate // 1) > 0.01 then "pair \($x.pair) \($x.size) \($x.arm): failed \($x.result.error_rate // "an unknown share") of its requests (over the 1% bound)"
            elif ($x.result.dropped_iterations // 1) > 0 then "pair \($x.pair) \($x.size) \($x.arm): dropped \($x.result.dropped_iterations // "an unknown number of") iterations (k6 ran out of VUs, so its percentiles miss the slowest requests)"
            else empty end) ]) as $problems
    | (($problems | length) == 0) as $measured
    | ([ $w[] | select(cpu_per_req == null) | "pair \(.pair) \(.size) \(.arm): no cgroup CPU reading" ]) as $warnings
    | { attempted: true, measured: $measured, problems: $problems, warnings: $warnings,
        ratio: "identity hook / no extension, same image, same cpuset, paired windows",
        pairs: $pairs, pair_orders: ($orders | split(" ") | map(select(. != ""))),
        copies: {
          h1: {request_deep_copies: 2, response_deep_copies: 0, value_comparisons: ["request"],
               note: "the filter gets a deep copy; proceedWith(copy) is normalised by a second deep copy and compared by value with the input"},
          h2: {request_deep_copies: 1, response_deep_copies: 2, value_comparisons: ["response"],
               note: "the transformer gets deep copies of the request and the response; the returned response is normalised by a second deep copy and compared by value"} },
        sizes: ([ $want[] as $s
          | ($w | map(select(.size == $s))) as $ws
          | { key: $s, value: (
              { bytes: ($ws[0].result.bytes // null), offered_rps: ($ws[0].result.offered_rps // null) }
              + { arms: ([ $arms[] as $a | ($ws | map(select(.arm == $a))) as $aw
                  | { key: $a, value: (
                      { windows: ($aw | length),
                        p50_ms: ($aw | map(.result.p50_ms) | median | r3),
                        p95_ms: ($aw | map(.result.p95_ms) | median | r3),
                        p99_ms: ($aw | map(.result.p99_ms) | median | r3),
                        throughput_rps: ($aw | map(.result.throughput_rps) | median | r3),
                        cpu_us_per_request: ($aw | map(cpu_per_req) | median | r3),
                        error_rate: (if ($aw | length) == 0 then null else ($aw | map(.result.error_rate // 0) | add / length) end),
                        sample_count: ($aw | map(.result.sample_count // 0) | add),
                        dropped_iterations: ($aw | map(.result.dropped_iterations // 0) | add) }
                      + (if $a == "none" or ($measured | not) then {} else
                          ([ range(1; $pairs + 1) as $p
                             | ($ws | map(select(.pair == $p and .arm == $a)) | .[0]) as $x
                             | ($ws | map(select(.pair == $p and .arm == "none")) | .[0]) as $n
                             | { p50: ratio($x.result.p50_ms; $n.result.p50_ms), p95: ratio($x.result.p95_ms; $n.result.p95_ms),
                                 p99: ratio($x.result.p99_ms; $n.result.p99_ms),
                                 tput: ratio($x.result.throughput_rps; $n.result.throughput_rps),
                                 cpu: ratio($x | cpu_per_req; $n | cpu_per_req),
                                 d50: (if $x.result.p50_ms != null and $n.result.p50_ms != null then $x.result.p50_ms - $n.result.p50_ms else null end),
                                 d95: (if $x.result.p95_ms != null and $n.result.p95_ms != null then $x.result.p95_ms - $n.result.p95_ms else null end),
                                 dcpu: (if ($x | cpu_per_req) != null and ($n | cpu_per_req) != null then ($x | cpu_per_req) - ($n | cpu_per_req) else null end) } ]) as $pp
                          | { p50_ratio: ($pp | all_pairs(.p50)), p95_ratio: ($pp | all_pairs(.p95)),
                              p99_ratio: ($pp | all_pairs(.p99)), throughput_ratio: ($pp | all_pairs(.tput)),
                              cpu_per_request_ratio: ($pp | all_pairs(.cpu)),
                              p50_delta_ms: ($pp | all_pairs(.d50)), p95_delta_ms: ($pp | all_pairs(.d95)),
                              cpu_us_per_request_delta: ($pp | all_pairs(.dcpu)) } end)) } ] | from_entries) }) } ] | from_entries) }'
}

# A Buildkite annotation of the per-size table, when buildkite-agent is present.
dp_annotate() { # json
  command -v buildkite-agent >/dev/null 2>&1 || return 0
  local table
  table="$(jq -r '.sizes | to_entries[] | .key as $s | .value.arms | to_entries[]
    | "| \($s) | \(.key) | \(.value.p50_ms) | \(.value.p95_ms) | \(.value.p99_ms) | \(.value.cpu_us_per_request) | \(.value.p50_ratio // "-") | \(.value.p95_ratio // "-") | \(.value.cpu_per_request_ratio // "-") | \(.value.cpu_us_per_request_delta // "-") |"' <<<"$1" 2>/dev/null)" || table=""
  printf '%s\n' ":electric_plug: **Data-plane hooks A/B** (identity H1 / H2 against no extension; notify-only) — measured: $(jq -r .measured <<<"$1")$(jq -r 'if (.problems | length) > 0 then "\n\nProblems:\n" + (.problems | map("- " + .) | join("\n")) else "" end' <<<"$1")

| size | arm | p50 ms | p95 ms | p99 ms | SUT CPU µs/req | p50 ratio | p95 ratio | CPU ratio | CPU µs/req delta |
|---|---|---:|---:|---:|---:|---:|---:|---:|---:|
${table}" | buildkite-agent annotate --style info --context perf-dataplane-ab || true
}

# Builds the jar, starts the three SUTs on the main SUT's cpuset, warms each, runs the paired windows and
# sets DATAPLANE_AB_JSON. Needs perf-test-run.sh's start_mockserver, wait_ready, cpuset_arg and run globals.
# shellcheck disable=SC2034  # START_EXTRA_ENV is read by perf-test-run.sh's start_mockserver
run_dataplane_ab() {
  local sizes="${PERF_DATAPLANE_AB_SIZES:-1kb,1mb}" pairs="${PERF_DATAPLANE_AB_PAIRS:-3}" status=0 arm size i order orders=""
  [[ "$pairs" =~ ^[1-9]$ ]] || pairs=3
  rm -f "$OUT_DIR"/dp-*.json "$OUT_DIR/dp-windows.ndjson"; : > "$OUT_DIR/dp-windows.ndjson"
  DP_DIR="$(mktemp -d "${TMPDIR:-/tmp}/perf-dataplane.XXXXXX")"
  echo "--- data-plane hooks A/B: sizes $sizes, $pairs pairs, arms '$DP_ARMS' on cpus=${SERVER_CPUS:-unpinned} mem=$SERVER_MEMORY"
  if ! dp_validate_sizes "$sizes"; then
    status=bad_sizes
  elif ! dp_build_extension_jar "$DP_DIR"; then
    status=no_jar
  else
    for arm in $DP_ARMS; do
      START_EXTRA_ENV=()
      [ -z "$(dp_extensions_for "$arm")" ] || START_EXTRA_ENV=(-e "MOCKSERVER_EXTENSIONS=$(dp_extensions_for "$arm")")
      start_mockserver "$DP_SUT_PREFIX-$arm" "$SERVER_CPUS" "$(dp_alias "$arm")" "" "$SERVER_MEMORY" "$DP_DIR/ext:/extensions:ro" "ERROR" "" "" 1080 \
        || echo "WARNING: data-plane A/B: the $arm SUT did not start" >&2
      START_EXTRA_ENV=()
    done
    for arm in $DP_ARMS; do
      if ! wait_ready "$DP_SUT_PREFIX-$arm"; then status="sut_not_ready:$arm"; break; fi
      declare -F clu_cpu_stat_file >/dev/null && printf -v "DP_STAT_FILE_$arm" '%s' "$(clu_cpu_stat_file "$DP_SUT_PREFIX-$arm" || true)"
    done
  fi
  if [ "$status" = 0 ]; then
    for arm in $DP_ARMS; do
      # shellcheck disable=SC2046
      docker run --rm --network "$NETWORK" $(cpuset_arg "$K6_CPUS") -v "$REPO_ROOT/mockserver-performance-test/k6:/k6:ro" \
        -e "BASE_URL=http://$(dp_alias "$arm"):1080" -e K6_DP_MODE=warmup -e "K6_DP_ARM=$arm" -e "K6_DP_SIZES=$sizes" \
        -e "K6_DP_RATE=${K6_DP_WARMUP_RATE:-100}" -e "K6_DP_DURATION=${K6_DP_WARMUP:-20s}" -e K6_RESULT_PATH=/tmp/warmup.json \
        "$K6_IMAGE" run /k6/dataplane_ab.js > "$OUT_DIR/dp-warmup-$arm-k6.log" 2>&1 \
        || echo "WARNING: data-plane A/B: the $arm warm-up failed (its windows' setup probes decide)" >&2
    done
    for ((i = 1; i <= pairs; i++)); do
      order="$(dp_pair_order "$i" "${BUILDKITE_BUILD_NUMBER:-0}")"
      orders="${orders:+$orders }${order// /,}"
      echo "--- data-plane hooks A/B: pair $i of $pairs ($order)"
      for size in ${sizes//,/ }; do
        for arm in $order; do dp_window "$i" "$size" "$arm"; done
      done
    done
  fi
  for arm in $DP_ARMS; do docker rm -f "$DP_SUT_PREFIX-$arm" >/dev/null 2>&1 || true; done
  DATAPLANE_AB_JSON="$(dp_assemble_json "$OUT_DIR/dp-windows.ndjson" "$status" "$sizes" "$pairs" "$orders")" || DATAPLANE_AB_JSON=""
  jq -e . >/dev/null 2>&1 <<<"$DATAPLANE_AB_JSON" || DATAPLANE_AB_JSON='{"attempted":true,"measured":false,"problems":["result assembly failed"],"sizes":{}}'
  echo "--- data-plane hooks A/B result: $(jq -c '{measured, problems, sizes: (.sizes | with_entries(.value |= (.arms | with_entries(.value |= {p50_ms, p95_ms, cpu_us_per_request, p95_ratio, cpu_per_request_ratio}))))}' <<<"$DATAPLANE_AB_JSON")"
  dp_annotate "$DATAPLANE_AB_JSON"
}

# The phase hook: sets DATAPLANE_AB_JSON, DATAPLANE_AB_ATTEMPTED and DATAPLANE_AB_SKIP_REASON (disabled:
# PERF_DATAPLANE_AB is not true; arm_only; host_network: the SUTs are reached by bridge alias;
# release_comparison: a release image may predate the extension SPI).
# shellcheck disable=SC2034  # the DATAPLANE_AB_* globals are read by perf-test-run.sh's result assembly
dp_ab_phase() {
  DATAPLANE_AB_JSON='{}'; DATAPLANE_AB_ATTEMPTED=false; DATAPLANE_AB_SKIP_REASON=disabled
  [ "${PERF_DATAPLANE_AB:-false}" = true ] || return 0
  if declare -F arm_only >/dev/null && arm_only; then DATAPLANE_AB_SKIP_REASON=arm_only; return 0; fi
  if [ -n "${PERF_RELEASE_COMPARISON:-}" ]; then DATAPLANE_AB_SKIP_REASON=release_comparison; return 0; fi
  if [ "${PERF_NETWORK_MODE:-bridge}" = host ]; then
    DATAPLANE_AB_SKIP_REASON=host_network
    echo "--- data-plane hooks A/B skipped: PERF_NETWORK_MODE=host (its SUTs are reached by bridge alias)"
    return 0
  fi
  DATAPLANE_AB_ATTEMPTED=true; DATAPLANE_AB_SKIP_REASON=""
  local t0; t0="$(date -u +%s)"
  run_dataplane_ab
  echo "--- data-plane hooks A/B done; added wall-clock $(( $(date -u +%s) - t0 ))s"
}

# The step's verdict, read after perf-result.json is uploaded: 1 when the build asked for the A/B
# (PERF_DATAPLANE_AB=true) and it ran but did not measure, or the result cannot be read. Nothing downstream
# reads the block, so a green step would hide an A/B that measured nothing. A requested A/B this run
# skipped (host network, a release comparison) is annotated, not failed; an arm-only run is silent.
dp_ab_require_measured() { # result_json
  [ "${PERF_DATAPLANE_AB:-false}" = true ] || return 0
  local attempted skip summary
  if ! attempted="$(jq -er '.dataplane_hooks_attempted | tostring' "$1" 2>/dev/null)"; then
    echo "+++ :x: PERF_DATAPLANE_AB=true, but $1 has no readable dataplane_hooks_attempted" >&2
    dp_ab_verdict_annotate error "the run's result has no readable \`dataplane_hooks_attempted\`, so the A/B cannot be shown to have measured"
    return 1
  fi
  if [ "$attempted" != true ]; then
    skip="$(jq -r '.dataplane_hooks_skip_reason // "unknown"' "$1" 2>/dev/null || echo unknown)"
    [ "$skip" = arm_only ] && return 0
    echo "--- :warning: PERF_DATAPLANE_AB=true, but this run skipped the A/B ($skip)"
    dp_ab_verdict_annotate warning "was requested but skipped by this run (\`$skip\`), so nothing was measured"
    return 0
  fi
  jq -e '.dataplane_hooks.measured == true' "$1" >/dev/null 2>&1 && return 0
  summary="$(jq -r '(.dataplane_hooks.problems // ["no problems recorded"]) | map("- " + .) | join("\n")' "$1" 2>/dev/null || echo "- result unreadable")"
  echo "+++ :x: PERF_DATAPLANE_AB=true, but the data-plane hooks A/B did not measure:" >&2
  echo "$summary" >&2
  dp_ab_verdict_annotate error "was requested and ran, but did NOT measure, so this step fails (its result is still uploaded):
${summary}"
  return 1
}

dp_ab_verdict_annotate() { # style message
  command -v buildkite-agent >/dev/null 2>&1 || return 0
  printf ':%s: **Data-plane hooks A/B (`PERF_DATAPLANE_AB=true`)** %s\n' "$([ "$1" = error ] && echo x || echo warning)" "$2" \
    | buildkite-agent annotate --style "$1" --context perf-dataplane-ab-verdict || true
}

dp_ab_cleanup() {
  local arm
  for arm in $DP_ARMS; do docker rm -f "$DP_SUT_PREFIX-$arm" >/dev/null 2>&1 || true; done
  [ -z "${DP_DIR:-}" ] || rm -rf "$DP_DIR" >/dev/null 2>&1 || true
}
