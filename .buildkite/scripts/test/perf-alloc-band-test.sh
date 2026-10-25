#!/usr/bin/env bash
# perf-test-compare.sh judges an alloc_bytes_per_op metric listed under its budget's `bands` against
# median + its own band instead of median * (1 + min_pct). Synthetic windows pin each rule; the real
# allocation history of builds 502-741 (fixtures/perf-alloc-bands, written by its generate-history.py)
# is replayed through the real compare program with and without the bands.
# Run: .buildkite/scripts/test/perf-alloc-band-test.sh   (PERF_COMPARE_SCRIPT=<path> to test another copy)
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)"
# shellcheck source=lib/perf-test-env.sh
. "$REPO_ROOT/.buildkite/scripts/test/lib/perf-test-env.sh"
perf_test_scrub_env PERF_COMPARE_SCRIPT
CMP="${PERF_COMPARE_SCRIPT:-$REPO_ROOT/.buildkite/scripts/steps/perf-test-compare.sh}"
BUDGETS="$REPO_ROOT/mockserver-performance-test/perf-budgets.json"
FIXTURES="$REPO_ROOT/.buildkite/scripts/test/fixtures/perf-alloc-bands"
HISTORY="$FIXTURES/alloc-history-502-741.json"
# Builds whose heads derive-bands.py leaves out: a real regression later fixed, so not noise.
EXCLUDE_BUILDS="724"
FAILS=0
ok()   { echo "  ok   $1"; }
bad()  { echo "  FAIL $1" >&2; FAILS=$((FAILS + 1)); }
check() { # name expected actual
  if [ "$2" = "$3" ]; then ok "$1"; else bad "$1: expected '$2', got '$3'"; fi
}
WORK="$(mktemp -d "${TMPDIR:-/tmp}/perf-alloc-band-test.XXXXXX")"
trap 'rm -rf "$WORK"' EXIT
[ -r "$HISTORY" ] || { echo "FAIL: $HISTORY not readable" >&2; exit 1; }

COMPARE="$(awk '/^COMPARE='"'"'$/ {on = 1; next} on && /^'"'"'$/ {exit} on' "$CMP")"
[ -n "$COMPARE" ] || { echo "FAIL: COMPARE program not found in $CMP" >&2; exit 1; }
check "the COMPARE program holds no single quote" "0" "$(tr -cd "'" <<<"$COMPARE" | wc -c | tr -d ' ')"
NOBANDS="$WORK/budgets-without-bands.json"
jq '.budgets |= with_entries(if (.value | type) == "object" then .value |= del(.bands) else . end)' "$BUDGETS" > "$NOBANDS"
WIDE="$WORK/budgets-wide-band.json"
jq '.budgets["microbench_extra.*.alloc_bytes_per_op"].bands.metrics["ForwardPathBenchmark.renderSimple.alloc_bytes_per_op"].band = 1000' "$BUDGETS" > "$WIDE"

# A run carrying one alloc_bytes_per_op value: mk("<family>"; "<benchmark>"; value).
MK='def mk($f; $k; $v): {agent: {instance_type: "c5.12xlarge"}, ($f): {($k): {alloc_bytes_per_op: $v}}};'
row() { # family benchmark head baseline_csv [budgets] -> "regression|threshold|band|gating"
  jq -nr --arg f "$1" --arg k "$2" --argjson h "$3" --argjson bv "[$4]" --slurpfile b "${5:-$BUDGETS}" --argjson minbaseline 5 \
    "$MK"' ($b[0].budgets) as $budgets | ($bv | map(mk($f; $k; .))) as $baseline | mk($f; $k; $h) | '"$COMPARE"'
      | .rows[] | select(.name == ($k + ".alloc_bytes_per_op"))
      | "\(.regression)|\(.threshold * 1000 | round / 1000)|\(.band // "none")|\(.gating)"'
}
field() { cut -d'|' -f"$1" <<<"$2"; }
X=microbench_extra
FLAT="5208.01,5208.01,5208.01,5208.01,5208.01,5208.01,5208.01,5208.01,5208.01,5208.01"

echo "--- 1. a listed metric is judged against median + its own band"
R="$(row $X ForwardPathBenchmark.renderSimple 5224.01 "$FLAT")"
check "renderSimple +16 B/op over a flat window is flagged" "true" "$(field 1 "$R")"
check "  at median + its 12.1 B/op band" "5220.11|12.1" "$(field 2-3 "$R")"
check "  and stays notify-only" "false" "$(field 4 "$R")"
check "the same head without bands is not flagged (the 5% band, 260 B/op)" "false|5468.411|none" "$(field 1-3 "$(row $X ForwardPathBenchmark.renderSimple 5224.01 "$FLAT" "$NOBANDS")")"
check "+12 B/op, inside its band, is not flagged" "false" "$(field 1 "$(row $X ForwardPathBenchmark.renderSimple 5220.01 "$FLAT")")"
check "a band never widens the threshold past min_pct" "true|5468.411" "$(field 1-2 "$(row $X ForwardPathBenchmark.renderSimple 5500 "$FLAT" "$WIDE")")"
check "a noisy window keeps median + 3 sigma when that is wider" "false|5430.39" \
  "$(field 1-2 "$(row $X ForwardPathBenchmark.renderSimple 5400 "5100,5150,5200,5208,5208,5208,5250,5300,5350,5400")")"
check "a window median more than level_pct from the band's median falls back to min_pct" "false|6090|none" \
  "$(field 1-3 "$(row $X ForwardPathBenchmark.renderSimple 5816 "5800,5800,5800,5800,5800,5800,5800,5800,5800,5800")")"
check "an unlisted microbench_extra benchmark keeps min_pct" "$(row $X LocalCallbackDispatchBenchmark.inline 0.0000589 "0.0000588,0.0000588,0.0000588,0.0000588,0.0000588" "$NOBANDS")" \
  "$(row $X LocalCallbackDispatchBenchmark.inline 0.0000589 "0.0000588,0.0000588,0.0000588,0.0000588,0.0000588")"
check "the gating microbench.* alloc metric is unchanged and still gating" "false|152250|none|true" \
  "$(row microbench EXACT_100_detailed 147180 "145000,145000,145000,145000,145000,145000")"
check "a listed name under another family does not apply (bands are per budget entry)" "false|5468.411|none" \
  "$(field 1-3 "$(row microbench ForwardPathBenchmark.renderSimple 5224.01 "$FLAT")")"

echo "--- 1b. a band that does not apply is visible"
cmp_of() { # family benchmark head baseline_csv -> the whole compare result
  jq -nc --arg f "$1" --arg k "$2" --argjson h "$3" --argjson bv "[$4]" --slurpfile b "$BUDGETS" --argjson minbaseline 5 \
    "$MK"' ($b[0].budgets) as $budgets | ($bv | map(mk($f; $k; .))) as $baseline | mk($f; $k; $h) | '"$COMPARE"
}
MOVED="5800,5800,5800,5800,5800,5800,5800,5800,5800,5800"
check "a band bypassed by the level guard marks its row stale" '{"band":12.1,"median":5208.01}' \
  "$(cmp_of $X ForwardPathBenchmark.renderSimple 5816 "$MOVED" | jq -c '.rows[] | select(.name == "ForwardPathBenchmark.renderSimple.alloc_bytes_per_op") | .band_stale')"
check "  an applied band does not" "null" \
  "$(cmp_of $X ForwardPathBenchmark.renderSimple 5210 "$FLAT" | jq -c '.rows[0].band_stale')"
TABLE_STMT="$(awk '/^TABLE="\$\(printf/ {p=1} p {print} p && /\)'"'"'\)"$/ {exit}' "$CMP")"
NOTE_STMT="$(awk '/^BANDS_NOTE="\$\(printf/ {p=1} p {print} p && /end'"'"'\)"$/ {exit}' "$CMP")"
[ -n "$TABLE_STMT" ] && [ -n "$NOTE_STMT" ] || bad "TABLE or BANDS_NOTE statement not found in $CMP"
render() { # compare result -> the table plus the bands note
  # shellcheck disable=SC2034  # read by the eval'd statements
  ( RESULT_CMP="$1"; eval "$TABLE_STMT"; eval "$NOTE_STMT"; printf '%s\n%s' "$TABLE" "$BANDS_NOTE" )
}
STALE="$(render "$(cmp_of $X ForwardPathBenchmark.renderSimple 5816 "$MOVED")")"
check "  the table says the band was not applied" "1" "$(grep -c 'renderSimple.alloc_bytes_per_op .*(band not applied: median moved past level_pct)' <<<"$STALE" || true)"
check "  and the annotation names it" "1" "$(grep -c 'Level moved past level_pct: `ForwardPathBenchmark.renderSimple.alloc_bytes_per_op`' <<<"$STALE" || true)"
R="$(cmp_of $X ForwardPathBenchmark.renderSimple 5210 "$FLAT")"
check "a listed benchmark the run did not report is named (a rename loses its band)" "true|false|32" \
  "$(jq -r '.bands_unused | "\(index("ForwardPathBenchmark.cloneLarge.alloc_bytes_per_op") != null)|\(index("ForwardPathBenchmark.renderSimple.alloc_bytes_per_op") != null)|\(length)"' <<<"$R")"
check "  in the annotation" "1" "$(render "$R" | grep -c 'Listed but not reported this run (renamed or removed?): .*cloneLarge' || true)"
check "  but not when the run reports no microbench_extra row at all" "[]" \
  "$(cmp_of microbench EXACT_100_detailed 145000 "145000,145000,145000,145000,145000" | jq -c '.bands_unused')"
check "no note when every band applies and every listed benchmark reports" "" \
  "$(render '{"rows":[],"bands_unused":[]}' | sed '1,2d')"

echo "--- 2. the real allocation history (builds 502-741), replayed"
# Every head is judged against the 10 runs before it (5 needed), as the compare step builds its window.
HIST_MK='def obj($names; $x): {agent: {instance_type: "c5.12xlarge"}}
  + ([range(0; $names | length) as $j | select($x.alloc[$j] != null)
      | {($names[$j][0]): {($names[$j][1]): {alloc_bytes_per_op: ($x.alloc[$j])}}}] | reduce .[] as $o ({}; . * $o));'
rows() { # budgets -> one compact row per judged head and alloc metric, "<build> <row json>"
  jq -nr --slurpfile f "$HISTORY" --slurpfile b "$1" --argjson minbaseline 5 \
    "$HIST_MK"' ($b[0].budgets) as $budgets | $f[0].metrics as $names | $f[0].runs as $r
    | range(0; $r | length) as $i
    | ([ $r[([0, $i - 10] | max):$i][] | obj($names; .) ]) as $baseline
    | obj($names; $r[$i]) | '"$COMPARE"' | .rows[] | "\($r[$i].build) \(tojson)"'
}
rows "$NOBANDS" > "$WORK/rows-before.txt"
rows "$BUDGETS" > "$WORK/rows-after.txt"
LISTED="$(jq -c '.budgets["microbench_extra.*.alloc_bytes_per_op"].bands.metrics | keys' "$BUDGETS")"
split_rows() { # rows file -> <file>.listed ("benchmark@build" of each flagged listed metric) and <file>.other (other rows)
  jq -Rr --argjson l "$LISTED" 'capture("^(?<b>[0-9]+) (?<r>.*)$") | (.r | fromjson) as $row
    | if ($l | index($row.name)) != null
      then (if $row.regression == true then "L \($row.name | sub("[.]alloc_bytes_per_op$"; ""))@\(.b)" else empty end)
      else "O \(.b) \(.r)" end' "$1" > "$1.all"
  sed -n 's/^L //p' "$1.all" | sort > "$1.listed"; sed -n 's/^O //p' "$1.all" > "$1.other"
}
split_rows "$WORK/rows-before.txt"; split_rows "$WORK/rows-after.txt"
B="$WORK/rows-before.txt.listed"; A="$WORK/rows-after.txt.listed"
check "every row of an unlisted metric is byte-identical with and without bands ($(wc -l < "$WORK/rows-after.txt.other" | tr -d ' ') rows)" "" \
  "$(diff "$WORK/rows-before.txt.other" "$WORK/rows-after.txt.other" | head -3)"
check "without bands the listed metrics flag 10 heads: two level changes over 5%" \
  "Http3RequestBridgeBenchmark.mapRequest_bodySize-1024_protocol-HTTP3:5 RelayByteCopyBenchmark.relayResponse_bodySize-262144_readSize-1460:5" \
  "$(cut -d@ -f1 "$B" | uniq -c | awk '{printf "%s%s:%s", (NR > 1 ? " " : ""), $2, $1}')"
check "with bands: every flag without them is still raised" "" "$(comm -23 "$B" "$A")"
check "the real +16 B/op renderSimple change (76fcf7d2a, build 724) is flagged" "1" "$(grep -cx 'ForwardPathBenchmark.renderSimple@724' "$A" || true)"
check "  as are the same change's other ForwardPath rows in build 724, none of which the 5% band flagged" \
  "ForwardPathBenchmark.renderMedium@724 ForwardPathBenchmark.renderPathAndBodyMedium@724 ForwardPathBenchmark.renderPathSmall@724 ForwardPathBenchmark.renderSimple@724|" \
  "$(grep '@724$' "$A" | tr '\n' ' ' | sed 's/ $//')|$(grep '@724$' "$B" || true)"
# Each added flag is a head at a new level while its window still spans the step (a real allocation
# change, flagged on each run until 6 runs at the new level move the window median), or build 724.
check "with bands: 101 flags; the 91 added ones by benchmark" \
  "ForwardPathBenchmark.cloneLarge:10 ForwardPathBenchmark.cloneSmall:10 ForwardPathBenchmark.renderComplex:3 ForwardPathBenchmark.renderMedium:6 ForwardPathBenchmark.renderPathAndBodyComplex:4 ForwardPathBenchmark.renderPathAndBodyMedium:20 ForwardPathBenchmark.renderPathSmall:6 ForwardPathBenchmark.renderSimple:6 Http3RequestBridgeBenchmark.mapRequest_bodySize-1024_protocol-HTTP3:1 Http3RequestBridgeBenchmark.mapRequest_bodySize-16384_protocol-HTTP3:5 InboundDecodeBenchmark.mapRequestWithBody_bodySize-1024:10 InboundDecodeBenchmark.mapRequestWithBody_bodySize-16384:10 total:101" \
  "$(comm -13 "$B" "$A" | cut -d@ -f1 | uniq -c | awk '{printf "%s:%s ", $2, $1}')total:$(wc -l < "$A" | tr -d ' ')"

echo "--- 3. injected regressions on every head the bands do not already flag"
inject() { # jq expression applied to each head value, budgets judging it -> "flagged/judged" over the listed benchmarks
  jq -nr --slurpfile f "$HISTORY" --slurpfile b "$BUDGETS" --slurpfile hb "$2" --argjson minbaseline 5 \
    "$HIST_MK"' def bump($x): $x | .alloc |= map(if . == null then null else ('"$1"') end);
    ($b[0].budgets) as $budgets | $f[0].metrics as $names | $f[0].runs as $r
    | ($budgets["microbench_extra.*.alloc_bytes_per_op"].bands.metrics) as $listed
    | [ range(0; $r | length) as $i
        | ([ $r[([0, $i - 10] | max):$i][] | obj($names; .) ]) as $baseline
        | ([obj($names; $r[$i]) | '"$COMPARE"' | .rows[] | select($listed[.name] != null and .threshold != null)]) as $base
        | ([obj($names; bump($r[$i])) | ($hb[0].budgets) as $budgets | '"$COMPARE"' | .rows[]] | map({key: .name, value: .regression}) | from_entries) as $hit
        | $base[] | select(.regression != true) | $hit[.name] ]
    | "\(map(select(. == true)) | length)/\(length)"'
}
check "+16 B/op is flagged on 613 of the 1,648 judged heads of the listed benchmarks" "613/1648" "$(inject '. + 16' "$BUDGETS")"
check "  against 108 under the 5% band (offLoopDispatch*, where 16 B/op is 9%, and 2 cloneSmall)" "108/1648" "$(inject '. + 16' "$NOBANDS")"
check "+1% is flagged on 1,362 of 1,648" "1362/1648" "$(inject '. * 1.01' "$BUDGETS")"
check "  against none under the 5% band" "0/1648" "$(inject '. * 1.01' "$NOBANDS")"

echo "--- 4. the committed bands match their history"
check "every listed metric is a microbench_extra benchmark the fixture's newest run reports" "[]" \
  "$(jq -c --slurpfile f "$HISTORY" '($f[0].runs[-1].alloc) as $last
      | [$f[0].metrics | to_entries[] | select(.value[0] == "microbench_extra" and $last[.key] != null) | .value[1] + ".alloc_bytes_per_op"] as $reported
      | [.budgets["microbench_extra.*.alloc_bytes_per_op"].bands.metrics | keys[] | select(. as $n | $reported | index($n) | not)]' "$BUDGETS")"
check "no other budget lists bands" '["microbench_extra.*.alloc_bytes_per_op"]' "$(jq -c '[.budgets | to_entries[] | select((.value | type) == "object" and (.value | has("bands"))) | .key]' "$BUDGETS")"
if command -v python3 >/dev/null 2>&1; then
  EXCL=""; for b in $EXCLUDE_BUILDS; do EXCL="$EXCL --exclude-build $b"; done
  # shellcheck disable=SC2086  # EXCL is a list of flags
  if python3 -I "$FIXTURES/derive-bands.py" "$HISTORY" $EXCL > "$WORK/derived.json" 2>/dev/null; then
    check "derive-bands.py re-derives exactly the committed bands from the fixture" \
      "$(jq -cS '.budgets | with_entries(select((.value | type) == "object" and (.value | has("bands")))) | map_values(.bands)' "$BUDGETS")" \
      "$(jq -cS '.' "$WORK/derived.json")"
  else bad "derive-bands.py failed on the fixture"; fi
  if python3 -c 'import ast, sys; ast.parse(open(sys.argv[1]).read())' "$FIXTURES/generate-history.py"; then
    ok "the fixture generator parses"
  else bad "the fixture generator does not parse"; fi
fi

if [ "$FAILS" -gt 0 ]; then echo "FAILED: $FAILS check(s)" >&2; exit 1; fi
echo "all allocation band checks passed"
