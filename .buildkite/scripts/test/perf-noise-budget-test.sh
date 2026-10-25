#!/usr/bin/env bash
# shellcheck disable=SC2034,SC2154  # SAMPLE_LOG is read by the eval'd run-step block; OLD_/NEW_/JUDGED_<arm> are set by printf -v
# Three notify-only metrics that flapped on noise: growth.cpu_ratio (a first-sample denominator),
# microbench_extra.*.alloc_bytes_per_op near zero (no absolute minimum move), and path_coverage
# p99s from a 35-request tail. The real history of builds 643-741 (fixtures/perf-noise, written by
# its generate-history.py) is replayed through the real compare program before and after each fix,
# and a synthetic regression is injected to show each still flags.
# Run: .buildkite/scripts/test/perf-noise-budget-test.sh
#   (PERF_COMPARE_SCRIPT / PERF_RUN_SCRIPT=<path> to test another copy)
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)"
# shellcheck source=lib/perf-test-env.sh
. "$REPO_ROOT/.buildkite/scripts/test/lib/perf-test-env.sh"
perf_test_scrub_env PERF_COMPARE_SCRIPT PERF_RUN_SCRIPT
CMP="${PERF_COMPARE_SCRIPT:-$REPO_ROOT/.buildkite/scripts/steps/perf-test-compare.sh}"
RUN="${PERF_RUN_SCRIPT:-$REPO_ROOT/.buildkite/scripts/steps/perf-test-run.sh}"
BUDGETS="$REPO_ROOT/mockserver-performance-test/perf-budgets.json"
HISTORY="$REPO_ROOT/.buildkite/scripts/test/fixtures/perf-noise/history-643-741.json"
FAILS=0
ok()   { echo "  ok   $1"; }
bad()  { echo "  FAIL $1" >&2; FAILS=$((FAILS + 1)); }
check() { # name expected actual
  if [ "$2" = "$3" ]; then ok "$1"; else bad "$1: expected '$2', got '$3'"; fi
}
WORK="$(mktemp -d "${TMPDIR:-/tmp}/perf-noise-test.XXXXXX")"
trap 'rm -rf "$WORK"' EXIT
[ -r "$HISTORY" ] || { echo "FAIL: $HISTORY not readable" >&2; exit 1; }

COMPARE="$(awk '/^COMPARE='"'"'$/ {on = 1; next} on && /^'"'"'$/ {exit} on' "$CMP")"
[ -n "$COMPARE" ] || { echo "FAIL: COMPARE program not found in $CMP" >&2; exit 1; }
check "the COMPARE program holds no single quote" "0" "$(tr -cd "'" <<<"$COMPARE" | wc -c | tr -d ' ')"
OLD_BUDGETS="$WORK/budgets-before.json"
jq '.budgets |= with_entries(if (.value | type) == "object" then .value |= del(.min_abs, .min_samples) else . end)' "$BUDGETS" > "$OLD_BUDGETS"

# Run objects. cpu($r): a run carrying only growth.cpu_ratio; alloc($b; $v) one microbench_extra
# row; pc($a; $n; $p99) one path_coverage arm.
MK='def base: {agent: {instance_type: "c5.12xlarge"}};
  def cpu($r): base + {growth: {cpu_pct: {ratio: $r, ratio_method: "occupancy_window"}}};
  def alloc($b; $v): base + {microbench_extra: {($b): {alloc_bytes_per_op: $v}}};
  def pc($a; $n; $p): base + {path_coverage: {arms: {($a): {sample_count: $n, p99_ms: $p}}}};'
# replay <budgets> <jq: run object from fixture run $x> <metric name> [jq applied to the head] [jq: head object]
#   -> the builds whose head is flagged, each judged against the 10 runs before it (5 needed)
replay() {
  jq -nr --slurpfile f "${HIST:-$HISTORY}" --slurpfile b "$1" --arg k "$3" --argjson minbaseline 5 \
    "$MK"' def obj($x): '"$2"'; def hobj($x): '"${5:-$2}"'; def hit: '"${4:-.}"';
    ($b[0].budgets) as $budgets | $f[0].runs as $r
    | [ range(5; $r | length) as $i
        | ([ $r[([0, $i - 10] | max):$i][] | obj(.) ]) as $baseline
        | (hobj($r[$i]) | hit | '"$COMPARE"' | .rows[] | select(.name == $k and .regression == true))
        | $r[$i].build ] | map(tostring) | join(" ")'
}
judged() { # same arguments -> how many heads got a threshold
  jq -nr --slurpfile f "${HIST:-$HISTORY}" --slurpfile b "$1" --arg k "$3" --argjson minbaseline 5 \
    "$MK"' def obj($x): '"$2"'; def hit: '"${4:-.}"';
    ($b[0].budgets) as $budgets | $f[0].runs as $r
    | [ range(5; $r | length) as $i
        | ([ $r[([0, $i - 10] | max):$i][] | obj(.) ]) as $baseline
        | (obj($r[$i]) | hit | '"$COMPARE"' | .rows[] | select(.name == $k and (.threshold | type) == "number")) ] | length'
}
one() { # head_json baseline_json_array metric -> "regression|threshold" or the status
  jq -nr --argjson h "$1" --argjson baseline "$2" --arg k "$3" --slurpfile b "$BUDGETS" --argjson minbaseline 5 \
    '($b[0].budgets) as $budgets | $h | '"$COMPARE"' | [.rows[] | select(.name == $k)] | if length == 0 then "absent"
     else .[0] | if .status then .status else "\(.regression)|\(.threshold)" end end'
}

echo "--- 1. perf-test-run.sh: growth CPU ratio over event-log occupancy windows"
GCW="$(awk '/^growth_cpu_window\(\) \{/ {p=1} p {print} p && /^}/ {exit}' "$RUN")
$(grep -E '^CPU_START_OCC_LO=|^CPU_START_OCC_HI=|^CPU_END_WINDOW_S=' "$RUN")
$(grep -E '^ratio\(\) \{' "$RUN")"
grep -q 'growth_cpu_window()' <<<"$GCW" && [ "$(grep -c '^CPU_' <<<"$GCW")" -eq 3 ] || bad "growth_cpu_window or its defaults not found in $RUN"
unset PERF_CPU_START_OCC_LO PERF_CPU_START_OCC_HI PERF_CPU_END_WINDOW_S
eval "$GCW"
to_diag() { # "seconds,cpu_pct,occupancy" lines -> a diag-samples.csv (ts col 1, cpu 5, entries 17, bound 20)
  awk -F',' 'BEGIN { h = "ts"; for (i = 2; i <= 33; i++) h = h ",c" i; print h }
    { r = 1000000 + $1; for (i = 2; i <= 33; i++) r = r "," (i == 5 ? $2 : (i == 17 ? sprintf("%.0f", $3 * 1000000) : (i == 20 ? "1000000" : ""))); print r }'
}
cpu_of() { # "seconds,cpu_pct,occupancy" lines on stdin -> "ratio start_median end_median start_n end_n"
  to_diag > "$WORK/diag.csv"
  local t0 t1 sm em ks ke
  t0="$(awk -F',' 'NR == 2 {print $1}' "$WORK/diag.csv")"; t1="$(awk -F',' 'END {print $1}' "$WORK/diag.csv")"
  read -r sm em ks ke <<<"$(growth_cpu_window "$WORK/diag.csv" "$t0" "$t1" "$CPU_START_OCC_LO" "$CPU_START_OCC_HI" "$CPU_END_WINDOW_S")"
  if [ "$sm" != null ] && [ "$em" != null ]; then echo "$(ratio "$em" "$sm") $sm $em $ks $ke"; else echo "null $sm $em $ks $ke"; fi
}
check "defaults: start window 10-30% full, end window 40 s" "0.10 0.30 40" "$CPU_START_OCC_LO $CPU_START_OCC_HI $CPU_END_WINDOW_S"
# Synthetic growth phase: idle 8 s, then 1,600 entries/s into a 115,456-entry log (full at ~80 s),
# load to 368 s, then the teardown reset; one row every 4 s, CPU alternating 16.2 / 16.6.
synth() { # g -> rows with CPU x (1 + g x occupancy); a 40% spike rides on the post-teardown row
  awk -v g="$1" 'BEGIN { for (t = 0; t <= 376; t += 4) {
    o = (t < 8 || t > 368) ? (t > 368 ? 0.2 : 0) : ((t - 8) * 1600 / 115456); if (o > 1) o = 1
    c = (t < 8) ? 0.3 : ((t > 368) ? 40 : (((t / 4) % 2) ? 16.2 : 16.6) * (1 + g * o))
    printf "%d,%.4f,%.4f\n", t, c, o } }'
}
check "steady CPU reads about 1.0 whatever the first sample" "1.0122 16.4000 16.6000 4 11" "$(synth 0 | cpu_of)"
check "a cost growing with occupancy, +50% at a full log, reads above the 1.30 floor" "1.3838 17.9937 24.9000 4 11" "$(synth 0.5 | cpu_of)"
check "the #2329 shape (CPU climbing once the log is full) reads above it" "true" \
  "$(synth 0 | awk -F',' '{ if ($3 >= 1 && $1 > 150) $2 = 16.4 * (1 + ($1 - 150) / 300); print $1 "," $2 "," $3 }' | cpu_of | awk '{print ($1 > 1.30) ? "true" : $1}')"
check "a log that never fills gives no ratio" "null null null 0 0" "$(synth 0 | awk -F',' '{print $1 "," $2 "," ($3 > 0.5 ? 0.5 : $3)}' | cpu_of)"
check "no growth window (an arm-only run) gives no ratio" "null null 0 0" \
  "$(synth 0 | to_diag > "$WORK/d2.csv"; growth_cpu_window "$WORK/d2.csv" "" "" 0.10 0.30 40)"
check "an absent sample file gives no ratio" "null null 0 0" "$(growth_cpu_window "$WORK/absent.csv" 1 2 0.10 0.30 40)"

echo "--- 2. the real growth traces (builds 643-741) through that function and compare"
NRUNS="$(jq '.runs | length' "$HISTORY")"
ratios() { # g -> one windowed ratio per fixture run, the injected cost x occupancy applied to every row
  local i
  for ((i = 0; i < NRUNS; i++)); do
    jq -r --argjson i "$i" --argjson g "$1" '.runs[$i].growth_trace[] | "\(.[0]),\(.[1] * (1 + $g * .[2])),\(.[2])"' "$HISTORY" \
      | cpu_of | cut -d' ' -f1
  done | paste -sd, -
}
R0="$(ratios 0)"; R50="$(ratios 0.5)"; R75="$(ratios 0.75)"
over() { tr ',' '\n' <<<"$1" | awk -v f="$2" '$1 > f' | wc -l | tr -d ' '; }
check "every run gives a ratio" "$NRUNS" "$(tr ',' '\n' <<<"$R0" | grep -c '^[0-9]')"
check "as measured, the 36 ratios span 0.93-1.04" "0.9325 1.0341" "$(tr ',' '\n' <<<"$R0" | sort -n | sed -n '1p;$p' | paste -sd' ' -)"
check "  none above the 1.30 floor" "0" "$(over "$R0" 1.30)"
check "+50% CPU at a full log: 33 of 36 above the floor" "33" "$(over "$R50" 1.30)"
check "+75% CPU at a full log: all 36" "36" "$(over "$R75" 1.30)"
HIST="$WORK/history-ratios.json"
jq --argjson r0 "[$R0]" --argjson r50 "[$R50]" --argjson r75 "[$R75]" \
  '.runs |= [to_entries[] | .value + {occ: {g0: $r0[.key], g50: $r50[.key], g75: $r75[.key]}}]' "$HISTORY" > "$HIST"
check "a first-sample run (no ratio_method) emits no cpu_ratio" "absent" \
  "$(one '{"growth":{"cpu_pct":{"ratio":49.0}}}' '[]' growth.cpu_ratio)"
check "growth.js ran but no ratio came out: a visible not-measured row" "not-measured" \
  "$(one '{"growth":{"duration_s":360,"cpu_pct":{"ratio":null,"ratio_method":"occupancy_window"}}}' '[]' growth.cpu_ratio)"
check "  which is neither a regression nor a schema error" "0 0 0" \
  "$(jq -nr --slurpfile b "$BUDGETS" --argjson minbaseline 5 '($b[0].budgets) as $budgets | [] as $baseline
     | {"growth":{"duration_s":360,"cpu_pct":{"ratio":null,"ratio_method":"occupancy_window"}}} | '"$COMPARE"' | "\(.count) \(.gating_count) \(.schema_error_count)"')"
check "  and is never a baseline value" "1.03" \
  "$(jq -nr --slurpfile b "$BUDGETS" --argjson minbaseline 5 '($b[0].budgets) as $budgets
     | [{"growth":{"duration_s":360,"cpu_pct":{"ratio":null,"ratio_method":"occupancy_window"}}},{"growth":{"duration_s":360,"cpu_pct":{"ratio":1.03,"ratio_method":"occupancy_window"}}}] as $baseline
     | {"growth":{"duration_s":360,"cpu_pct":{"ratio":1.03,"ratio_method":"occupancy_window"}}} | '"$COMPARE"' | .rows[] | select(.name == "growth.cpu_ratio") | .baseline')"
check "an arm-only run (no growth phase) has no cpu_ratio row" "absent" \
  "$(one '{"growth":{"duration_s":null,"cpu_pct":{"ratio":null,"ratio_method":"occupancy_window"}}}' '[]' growth.cpu_ratio)"
TABLE_STMT="$(awk '/^TABLE="\$\(printf/ {p=1} p {print} p && /\)'"'"'\)"$/ {exit}' "$CMP")"
[ -n "$TABLE_STMT" ] || bad "TABLE statement not found in $CMP"
check "  the annotation says it was not measured" "1" \
  "$(RESULT_CMP='{"rows":[{"name":"growth.cpu_ratio","head":null,"gating":false,"status":"not-measured"}]}'; eval "$TABLE_STMT"; grep -c 'not measured this run' <<<"$TABLE" || true)"
check "first-sample history is no baseline for an occupancy-window head" "no-baseline" \
  "$(one '{"growth":{"cpu_pct":{"ratio":1.03,"ratio_method":"occupancy_window"}}}' '[{"growth":{"cpu_pct":{"ratio":49.0}}},{"growth":{"cpu_pct":{"ratio":5.0}}}]' growth.cpu_ratio)"
check "first-sample ratios: the flags production raised" "654 685 690 698 721 724" \
  "$(HIST="$HIST" replay "$BUDGETS" 'cpu($x.cpu.first_sample_ratio)' growth.cpu_ratio)"
check "occupancy-window ratios: no flag" "" "$(HIST="$HIST" replay "$BUDGETS" 'cpu($x.occ.g0)' growth.cpu_ratio)"
check "occupancy-window ratios: every head judged" "31" "$(HIST="$HIST" judged "$BUDGETS" 'cpu($x.occ.g0)' growth.cpu_ratio)"
check "+50% at a full log on the head, against measured history: flagged in 28 of 31 heads" "28" \
  "$(HIST="$HIST" replay "$BUDGETS" 'cpu($x.occ.g0)' growth.cpu_ratio . 'cpu($x.occ.g50)' | wc -w | tr -d ' ')"
check "+75%: every head" "31" \
  "$(HIST="$HIST" replay "$BUDGETS" 'cpu($x.occ.g0)' growth.cpu_ratio . 'cpu($x.occ.g75)' | wc -w | tr -d ' ')"

echo "--- 3. microbench_extra alloc: an absolute minimum move of min_abs B/op"
check "the budget sets min_abs 0.01" "0.01" "$(jq -r '.budgets["microbench_extra.*.alloc_bytes_per_op"].min_abs' "$BUDGETS")"
MI=MetricsIncrementBenchmark.incrementDistinctCounterPerThread
for bench in "$MI" MetricsIncrementBenchmark.incrementSharedCounter LocalCallbackDispatchBenchmark.inline \
             SocksHandshakeBenchmark.detect_scenario-SOCKS5_PASSWORD; do
  O="alloc(\"$bench\"; \$x.alloc[\"$bench\"])"
  check "$bench: no flag with min_abs" "" "$(replay "$BUDGETS" "$O" "$bench.alloc_bytes_per_op")"
  for add in 16 1 0.05; do
    check "$bench: +$add B/op flags every head" "31" \
      "$(replay "$BUDGETS" "$O" "$bench.alloc_bytes_per_op" ".microbench_extra[\"$bench\"].alloc_bytes_per_op += $add" | wc -w | tr -d ' ')"
  done
done
check "$MI: without min_abs the noise flagged" "723" "$(replay "$OLD_BUDGETS" "alloc(\"$MI\"; \$x.alloc[\"$MI\"])" "$MI.alloc_bytes_per_op")"
check "$MI: +0.005 B/op (one 16 B object per 3,200 ops) is below min_abs, not flagged (the documented blind spot)" "" \
  "$(replay "$BUDGETS" "alloc(\"$MI\"; \$x.alloc[\"$MI\"])" "$MI.alloc_bytes_per_op" ".microbench_extra[\"$MI\"].alloc_bytes_per_op += 0.005")"
RS=ForwardPathBenchmark.renderSimple
thresholds() { # budgets -> every window's threshold for renderSimple
  jq -nr --slurpfile f "$HISTORY" --slurpfile b "$1" --arg k "$RS.alloc_bytes_per_op" --argjson minbaseline 5 \
    "$MK"' ($b[0].budgets) as $budgets | $f[0].runs as $r
    | [ range(5; $r | length) as $i | ([ $r[([0, $i - 10] | max):$i][] | alloc("'"$RS"'"; .alloc["'"$RS"'"]) ]) as $baseline
        | (alloc("'"$RS"'"; $r[$i].alloc["'"$RS"'"]) | '"$COMPARE"' | .rows[] | select(.name == $k) | .threshold) ] | tojson'
}
check "a 5 KB/op benchmark's thresholds are identical with and without min_abs" "$(thresholds "$OLD_BUDGETS")" "$(thresholds "$BUDGETS")"
check "the real +16 B/op on $RS (5,208 to 5,224, build 724) is inside its 5% band before and after (a known gap)" "|" \
  "$(replay "$OLD_BUDGETS" "alloc(\"$RS\"; \$x.alloc[\"$RS\"])" "$RS.alloc_bytes_per_op")|$(replay "$BUDGETS" "alloc(\"$RS\"; \$x.alloc[\"$RS\"])" "$RS.alloc_bytes_per_op")"

echo "--- 4. path_coverage p99: judged only from min_samples requests"
check "the three p99 budgets set min_samples 5000" "5000 5000 5000" \
  "$(jq -r '[.budgets["path_coverage.*.p99_ms","path_coverage.*.handshake_p99_ms","path_coverage.*.p99_ratio"].min_samples] | map(tostring) | join(" ")' "$BUDGETS")"
P='pc($a; $x.pc[$a].n; $x.pc[$a].p99_ms)'
for arm in jsonpath_100 jsonschema_100 xpath_100 download_32mib tls_keepalive mtls_churn h2_2000; do
  O="(\"$arm\") as \$a | $P"
  printf -v "OLD_$arm" '%s' "$(replay "$OLD_BUDGETS" "$O" "path_coverage.$arm.p99_ms")"
  printf -v "NEW_$arm" '%s' "$(replay "$BUDGETS" "$O" "path_coverage.$arm.p99_ms")"
  printf -v "JUDGED_$arm" '%s' "$(judged "$BUDGETS" "$O" "path_coverage.$arm.p99_ms")"
done
check "jsonpath_100 (3,501 samples): flagged before" "672 690 724 729" "$OLD_jsonpath_100"
check "jsonschema_100 (3,501 samples): flagged before" "731" "$OLD_jsonschema_100"
check "download_32mib (71 samples): flagged before" "690" "$OLD_download_32mib"
for arm in jsonpath_100 jsonschema_100 xpath_100 download_32mib; do
  v="JUDGED_$arm"; check "$arm: not judged now" "0" "${!v}"
done
SHORTROW="$(jq -nc --slurpfile f "$HISTORY" --slurpfile b "$BUDGETS" --argjson minbaseline 5 "$MK"'
  ($b[0].budgets) as $budgets | $f[0].runs as $r | ([ $r[-11:-1][] | pc("jsonpath_100"; .pc.jsonpath_100.n; .pc.jsonpath_100.p99_ms) ]) as $baseline
  | pc("jsonpath_100"; $r[-1].pc.jsonpath_100.n; $r[-1].pc.jsonpath_100.p99_ms) | '"$COMPARE"' | {nongating_count, gating_count, row: (.rows[] | select(.name == "path_coverage.jsonpath_100.p99_ms") | {status, samples, min_samples})}')"
check "a short arm still shows a row, marked not judged" '{"nongating_count":0,"gating_count":0,"row":{"status":"insufficient-samples","samples":3500,"min_samples":5000}}' "$SHORTROW"
check "  and the annotation says why" "1" "$(RESULT_CMP="$(jq -c '{rows: [.row + {name: "path_coverage.jsonpath_100.p99_ms", head: 11.8, gating: false}]}' <<<"$SHORTROW")"; eval "$TABLE_STMT"; grep -c 'not judged: 3500 samples, under 5000' <<<"$TABLE" || true)"
NOCOUNT="$(jq -nc --slurpfile b "$BUDGETS" --argjson minbaseline 5 '($b[0].budgets) as $budgets | [] as $baseline
  | {agent: {instance_type: "c5.12xlarge"}, path_coverage: {arms: {tls_churn: {p99_ms: 0.5, p50_ms: 0.4}}}} | '"$COMPARE"'
  | {gating_count, schema_error_count, p99: (.rows[] | select(.name == "path_coverage.tls_churn.p99_ms") | .status),
     p50: (.rows[] | select(.name == "path_coverage.tls_churn.p50_ms") | .status)}')"
check "a p99 with no sample count is a schema error that fails the build, not a silent drop" \
  '{"gating_count":1,"schema_error_count":1,"p99":"no-sample-count","p50":"no-baseline"}' "$NOCOUNT"
for arm in tls_keepalive mtls_churn h2_2000; do
  o="OLD_$arm"; n="NEW_$arm"; v="JUDGED_$arm"
  check "$arm (5,000+ samples): verdicts unchanged" "${!o}" "${!n}"
  check "$arm: still judged" "true" "$([ "${!v}" -ge 29 ] && echo true || echo "${!v}")"
done
check "a 30% p99 regression on tls_keepalive still flags every judged head" "$JUDGED_tls_keepalive" \
  "$(replay "$BUDGETS" '("tls_keepalive") as $a | '"$P" path_coverage.tls_keepalive.p99_ms '.path_coverage.arms.tls_keepalive.p99_ms *= 1.3' | wc -w | tr -d ' ')"
LONG="$(jq -nc '[range(5) | {agent:{instance_type:"c5.12xlarge"},path_coverage:{arms:{jsonpath_100:{sample_count:10001,p99_ms:(6 + . * 0.1)}}}}]')"
check "a 10,001-sample matcher arm is judged against 10,001-sample history" "false|7.75" \
  "$(one '{"agent":{"instance_type":"c5.12xlarge"},"path_coverage":{"arms":{"jsonpath_100":{"sample_count":10001,"p99_ms":6.5}}}}' "$LONG" path_coverage.jsonpath_100.p99_ms)"
check "  and flags a 30% move" "true|7.75" \
  "$(one '{"agent":{"instance_type":"c5.12xlarge"},"path_coverage":{"arms":{"jsonpath_100":{"sample_count":10001,"p99_ms":8.0}}}}' "$LONG" path_coverage.jsonpath_100.p99_ms)"
SHORT="$(jq -nc '[range(5) | {agent:{instance_type:"c5.12xlarge"},path_coverage:{arms:{jsonpath_100:{sample_count:3501,p99_ms:(6 + . * 0.1)}}}}]')"
check "  but 3,501-sample history is no baseline for it" "no-baseline" \
  "$(one '{"agent":{"instance_type":"c5.12xlarge"},"path_coverage":{"arms":{"jsonpath_100":{"sample_count":10001,"p99_ms":6.5}}}}' "$SHORT" path_coverage.jsonpath_100.p99_ms)"
check "the p50 of a short arm is still judged" "false|3.125" \
  "$(one '{"agent":{"instance_type":"c5.12xlarge"},"path_coverage":{"arms":{"jsonpath_100":{"sample_count":3501,"p50_ms":2.5}}}}' \
     "$(jq -nc '[range(5) | {agent:{instance_type:"c5.12xlarge"},path_coverage:{arms:{jsonpath_100:{sample_count:3501,p50_ms:2.5}}}}]')" path_coverage.jsonpath_100.p50_ms)"
check "an h2 rung counts its measured_sample_count" "insufficient-samples" \
  "$(one '{"agent":{"instance_type":"c5.12xlarge"},"path_coverage":{"arms":{"h2_8000":{"measured_sample_count":40,"p99_ms":1}}}}' '[]' path_coverage.h2_8000.p99_ms)"

echo "--- 5. the matcher arms measure 100 s"
CFG="$REPO_ROOT/mockserver-performance-test/k6/lib/config.js"
check "K6_COV_MATCHER_DURATION defaults to 110s (10 s settle, 100 s measured)" "1" \
  "$(grep -c "matcherDuration: env('K6_COV_MATCHER_DURATION', '110s')" "$CFG")"
check "K6_COV_SETTLE still defaults to 10s" "1" "$(grep -c "settle: env('K6_COV_SETTLE', '10s')" "$CFG")"
check "coverage.js uses it for the matchers mode" "1" \
  "$(grep -c "MODE === 'matchers' ? COVERAGE.matcherDuration : COVERAGE.duration" "$REPO_ROOT/mockserver-performance-test/k6/coverage.js")"
check "the run step passes an override through" "1" \
  "$(grep -c 'K6_COV_DURATION K6_COV_MATCHER_DURATION' "$REPO_ROOT/.buildkite/scripts/steps/lib/perf-path-coverage.sh")"

if [ "$FAILS" -ne 0 ]; then echo "FAILED: $FAILS check(s)" >&2; exit 1; fi
echo "all perf noise-budget checks passed"
