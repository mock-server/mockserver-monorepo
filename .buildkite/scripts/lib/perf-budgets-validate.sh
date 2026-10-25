#!/usr/bin/env bash
# Schema validation for mockserver-performance-test/perf-budgets.json.
#
# WHY THIS EXISTS. Both perf gates already checked that the budget file exists,
# parses, and has a `budgets` object. None of them checked the one property the
# gates actually depend on: that each entry's numbers are NUMBERS. jq has a total
# ordering across types — null < false < true < numbers < strings — so a single
# pair of quotes in a hand-edited budget silently rewrites the gate:
#
#   perf-alloc-gate.sh   `$alloc <= $floor`  with floor "5000"  -> ALWAYS true.
#                        Every allocation passes. The blocking gate is off.
#   perf-test-compare.sh `[$med + 3*$sigma, $floor] | max`      -> the STRING wins
#                        (strings sort above numbers), so the threshold becomes a
#                        string and `$value > $threshold` is ALWAYS false. The
#                        regression can never fire.
#   perf-test-compare.sh `[$med - 3*$sigma, $floor] | min`      -> the NUMBER wins,
#                        so the floor is silently dropped and only the MAD band
#                        applies.
#
# Every one of those is a false ALL-CLEAR: the gate reports green while proving
# nothing, which is worse than having no gate, because nobody looks again. The
# type is therefore validated at the file boundary, and the consuming predicates
# guard their own operands as well — the boundary check alone is not enough,
# because a predicate can be reached by a path that did not come through here.
#
# `dir` is validated for the same reason: perf-test-compare.sh branches on
# `if $m.dir=="up" ... else <down> end`, so a missing or misspelled direction does
# not error — it silently takes the "down" branch and compares a bigger-is-worse
# metric as though smaller were worse, which for most metrics means it can never
# fire either.
#
# Usage:
#   source .buildkite/scripts/lib/perf-budgets-validate.sh
#   if ! reason="$(validate_perf_budgets "$BUDGETS_FILE")"; then ... fail ... fi
#
#   bash .buildkite/scripts/lib/perf-budgets-validate.sh --self-test
#     Proves the validator rejects each hostile shape it claims to reject, and
#     accepts valid ones. It deliberately takes NO file argument and never reads
#     the real budget file: "the validator works" and "this run's budget file is
#     valid" are separate questions, and a self-test that also judged the real file
#     would report a bad budget as a broken validator. The caller asks the second
#     question itself, with validate_perf_budgets.

# THE ENTRY CONTRACT. `dir` (\"up\"/\"down\"), `min_pct` (number >= 0) and `floor`
# (number, or null for \"no absolute floor\") are REQUIRED on every entry; `gating`,
# `provisional` and `hw` are optional booleans, `min_abs` an optional number >= 0,
# `min_samples` an optional whole number (path_coverage.* only) and `bimodal` an optional object. No other key is permitted -- an entry
# is rejected if it carries one, because every value check here is conditional on
# its key being present, so a misspelled key name would pass them all while
# reverting that setting to its default. `floor` must be spelled out as null
# rather than omitted for the same reason: absence and \"deliberately no floor\"
# must not look alike.
#
# Keys beginning with `_` are prose/comment entries (e.g. `_comment_streaming`,
# whose value is an array of strings), never budgets. They are skipped.
#
# Prints a human-readable reason for the FIRST problem found, and returns 1.
# Prints nothing and returns 0 when the file is well-formed.
validate_perf_budgets() {
  local file="$1"

  if [ ! -f "$file" ]; then
    echo "budget file not found: $file"
    return 1
  fi
  if ! jq empty "$file" >/dev/null 2>&1; then
    echo "budget file is not valid JSON: $file"
    return 1
  fi
  if [ "$(jq -r '(.budgets | type) // "null"' "$file" 2>/dev/null)" != "object" ]; then
    echo "budget file has no \`budgets\` object: $file"
    return 1
  fi

  local problems
  problems="$(jq -r '
    [ .budgets
      | to_entries[]
      | select(.key | startswith("_") | not)
      | .key as $k
      | .value as $v
      | if ($v | type) != "object" then
          "\($k): entry is \($v|type), expected an object"
        # Reject UNKNOWN keys before checking any value. Every other check is
        # conditional on the key being PRESENT, so a misspelled key name would
        # sail through all of them while silently reverting that setting to its
        # default -- and the default for `gating` is notify-only, i.e. a typo
        # turns a build-failing metric into one that only annotates. The value
        # checks cannot see that; only the key set can.
        elif (($v | keys) - ["dir","min_pct","floor","gating","provisional","hw","bimodal","min_abs","min_samples","bands"] | length) > 0 then
          "\($k): unrecognised key(s) \((($v | keys) - ["dir","min_pct","floor","gating","provisional","hw","bimodal","min_abs","min_samples","bands"]) | tojson) -- a misspelled key does not error, it silently reverts that setting to its default (a typo of `gating` makes a build-failing metric notify-only)"
        elif (($v | has("dir")) and ($v | has("min_pct")) and ($v | has("floor"))) | not then
          ((["dir","min_pct","floor"] - ($v | keys)) as $missing
           | "\($k): missing required key(s) \($missing | tojson) -- absence is not a usable default"
             + (if ($missing | index("floor")) then ", state `\"floor\": null` explicitly to mean no absolute floor" else "" end))
        elif ($v.dir != "up" and $v.dir != "down") then
          "\($k): dir is \($v.dir | tojson), expected \"up\" or \"down\" (an unrecognised dir silently takes the bigger-is-better branch)"
        elif (($v.min_pct | type) != "number") then
          "\($k): min_pct is \($v.min_pct | tojson) of type \($v.min_pct|type), expected a number"
        elif ($v.min_pct < 0) then
          "\($k): min_pct is \($v.min_pct), expected >= 0"
        elif (($v | has("floor")) and $v.floor != null and (($v.floor | type) != "number")) then
          "\($k): floor is \($v.floor | tojson) of type \($v.floor|type), expected a number or null -- a quoted floor silently disables this budget"
        elif (($v | has("gating")) and (($v.gating | type) != "boolean")) then
          "\($k): gating is \($v.gating | tojson), expected true or false"
        elif (($v | has("provisional")) and (($v.provisional | type) != "boolean")) then
          "\($k): provisional is \($v.provisional | tojson), expected true or false"
        elif (($v | has("hw")) and (($v.hw | type) != "boolean")) then
          "\($k): hw is \($v.hw | tojson), expected true or false -- jq treats ANY non-null, non-false value as true, so the string \"false\" would mark the metric hardware-sensitive and silently stop it comparing across machines"
        # min_abs widens the band to at least median +/- min_abs. A quoted value would be
        # ignored by compare, and the bimodal branch does not apply it, so both are refused.
        elif (($v | has("min_abs")) and ((($v.min_abs | type) != "number") or $v.min_abs < 0)) then
          "\($k): min_abs is \($v.min_abs | tojson), expected a number >= 0"
        elif (($v | has("min_abs")) and ($v | has("bimodal"))) then
          "\($k): min_abs is not applied by the bimodal per-mode bands, so it may not be combined with bimodal"
        # min_samples drops a value measured from fewer requests. Only the path_coverage arms carry
        # a sample count to compare, so anywhere else it would be silently ignored.
        elif (($v | has("min_samples")) and ((($v.min_samples | type) != "number") or $v.min_samples < 1 or $v.min_samples != ($v.min_samples | floor))) then
          "\($k): min_samples is \($v.min_samples | tojson), expected a whole number >= 1"
        elif (($v | has("min_samples")) and ($k | startswith("path_coverage.*.") | not)) then
          "\($k): min_samples is honoured only for path_coverage.* metrics, which carry their sample count"
        # `bimodal` judges a head against the baseline mode it falls in. It is a
        # looser rule than one median+MAD band, so it is notify-only and up-only,
        # and its numbers are type-checked for the same jq-ordering reason as floor.
        elif ($v | has("bimodal")) then
          ($v.bimodal) as $bm
          | ($k | split(".") | last) as $leaf
          | if ($bm | type) != "object" then "\($k): bimodal is \($bm | tojson), expected an object"
            elif ((($bm | keys) - ["metrics","split_ratio","upper_band_pct","share_runs","share_alpha"]) | length) > 0
                 or ((["metrics","split_ratio","upper_band_pct","share_runs","share_alpha"] - ($bm | keys)) | length) > 0 then
              "\($k): bimodal needs exactly the keys [\"metrics\",\"split_ratio\",\"upper_band_pct\",\"share_runs\",\"share_alpha\"], got \($bm | keys | tojson)"
            elif (($bm.metrics | type) != "object") or (($bm.metrics | length) == 0)
                 or ([$bm.metrics | to_entries[] | select((.key | endswith("." + $leaf) | not) or ((.value | type) != "number") or .value <= 0 or .value >= 1)] | length) > 0 then
              "\($k): bimodal.metrics is \($bm.metrics | tojson), expected a non-empty object of metric names ending in .\($leaf), each mapped to its historical upper-mode share in (0, 1)"
            elif (($bm.share_runs | type) != "number") or ($bm.share_runs < 1) or ($bm.share_runs != ($bm.share_runs | floor)) then
              "\($k): bimodal.share_runs is \($bm.share_runs | tojson), expected a whole number >= 1"
            elif (($bm.share_alpha | type) != "number") or ($bm.share_alpha <= 0) or ($bm.share_alpha >= 1) then
              "\($k): bimodal.share_alpha is \($bm.share_alpha | tojson), expected a number in (0, 1)"
            elif (($bm.split_ratio | type) != "number") or ($bm.split_ratio <= 1) then
              "\($k): bimodal.split_ratio is \($bm.split_ratio | tojson), expected a number > 1"
            elif (($bm.upper_band_pct | type) != "number") or ($bm.upper_band_pct <= 0) or ($bm.upper_band_pct >= 1) then
              "\($k): bimodal.upper_band_pct is \($bm.upper_band_pct | tojson), expected a number in (0, 1)"
            elif ($v | has("bands")) then
              "\($k): bimodal may not be combined with bands -- the per-mode bands do not apply a per-metric band"
            elif $v.dir != "up" then
              "\($k): bimodal is only defined for dir \"up\", got \($v.dir | tojson)"
            elif ($v.gating == true) then
              "\($k): bimodal may not be set on a gating budget -- its per-mode band is looser than one median+MAD band, so making it gate needs its own reviewed change"
            else empty end
        # `bands` gives each listed metric its own absolute band in place of the relative min_pct
        # term. Compare only ever lets it tighten that term, and its numbers are type-checked for
        # the same jq-ordering reason as floor: a quoted band would silently never apply.
        elif ($v | has("bands")) then
          ($v.bands) as $bd
          | ($k | split(".") | last) as $leaf
          | ([($bd | objects | .metrics | objects | to_entries[])
               | select((.key | endswith("." + $leaf) | not) or ((.value | type) != "object")
                        or ((.value | keys) != ["band","median"])
                        or ((.value.band | type) != "number") or ((.value.median | type) != "number")
                        or (.value.band <= 0) or (.value.median <= 0))
               | .key]) as $bad
          | if ($bd | type) != "object" then "\($k): bands is \($bd | tojson), expected an object"
            elif ((($bd | keys) - ["level_pct","metrics"]) | length) > 0 or ((["level_pct","metrics"] - ($bd | keys)) | length) > 0 then
              "\($k): bands needs exactly the keys [\"level_pct\",\"metrics\"], got \($bd | keys | tojson)"
            elif (($bd.level_pct | type) != "number") or ($bd.level_pct <= 0) or ($bd.level_pct >= 1) then
              "\($k): bands.level_pct is \($bd.level_pct | tojson), expected a number in (0, 1)"
            elif (($bd.metrics | type) != "object") or (($bd.metrics | length) == 0) then
              "\($k): bands.metrics is \($bd.metrics | tojson), expected a non-empty object"
            elif ($bad | length) > 0 then
              "\($k): bands.metrics \($bad | tojson) must each be a metric name ending in .\($leaf) mapped to exactly {\"band\": <number > 0>, \"median\": <number > 0>}"
            elif $v.dir != "up" then
              "\($k): bands is only defined for dir \"up\", got \($v.dir | tojson)"
            elif $v.floor != null then
              "\($k): bands replaces the relative min_pct term, so it may not be combined with a floor (the floor would be silently ignored)"
            else empty end
        else empty end
    ] | .[]' "$file" 2>&1)"

  if [ -n "$problems" ]; then
    echo "$problems"
    return 1
  fi
  return 0
}

# --- self-test ----------------------------------------------------------------
# A validator nobody degrades is just more code that looks like a check. Each
# fixture below is a budget file the gates would MIS-read, and the self-test
# fails unless the validator rejects it. Valid shapes are asserted too, so a
# validator that rejects everything cannot masquerade as working either.
_perf_budgets_self_test() {
  local tmp failures=0
  tmp="$(mktemp -d)"
  trap 'rm -rf "$tmp"' EXIT

  _expect_reject() {
    local name="$1" json="$2" reason
    printf '%s' "$json" > "$tmp/b.json"
    if reason="$(validate_perf_budgets "$tmp/b.json")"; then
      echo "  FAIL  $name -- validator ACCEPTED a file it must reject"
      failures=$((failures + 1))
    else
      echo "  ok    $name -- rejected: ${reason%%$'\n'*}"
    fi
  }

  echo "--- perf-budgets validator self-test"
  _expect_reject "quoted floor (disables alloc gate and up-direction compare)" \
    '{"budgets":{"a.b.c":{"dir":"up","min_pct":0.1,"floor":"5000"}}}'
  _expect_reject "quoted min_pct" \
    '{"budgets":{"a.b.c":{"dir":"up","min_pct":"0.1","floor":5000}}}'
  # `hw` routes a metric to a same-instance-type baseline. jq treats ANY
  # non-null, non-false value as true, so a quoted "false" would mark the metric
  # hardware-sensitive and silently stop it comparing across machines -- the
  # metric would sit at `no-baseline` looking like a warm-up rather than a typo.
  _expect_reject "quoted hw (silently marks the metric hardware-sensitive)" \
    '{"budgets":{"a.b.c":{"dir":"up","min_pct":0.1,"floor":5000,"hw":"false"}}}'
  # Honest about what this one proves: `hwe` is caught by the generic unknown-key
  # branch whether or not `hw` exists, so it does NOT discriminate the hw feature.
  # It is kept as a regression check on the allow-list EDIT itself — that widening
  # the permitted set to include `hw` did not accidentally stop unknown keys being
  # rejected.
  _expect_reject "unknown key still rejected after widening the allow-list for hw" \
    '{"budgets":{"a.b.c":{"dir":"up","min_pct":0.1,"floor":5000,"hwe":true}}}'
  _expect_reject "missing dir (silently takes the down branch)" \
    '{"budgets":{"a.b.c":{"min_pct":0.1,"floor":5000}}}'
  _expect_reject "misspelled dir" \
    '{"budgets":{"a.b.c":{"dir":"UP","min_pct":0.1,"floor":5000}}}'
  _expect_reject "string gating flag (\"false\" is truthy nowhere but reads as set)" \
    '{"budgets":{"a.b.c":{"dir":"up","min_pct":0.1,"floor":5000,"gating":"true"}}}'
  _expect_reject "negative min_pct" \
    '{"budgets":{"a.b.c":{"dir":"down","min_pct":-0.1,"floor":5000}}}'
  _expect_reject "TYPO'd gating key (silently makes a gating metric notify-only)" \
    '{"budgets":{"microbench.x.time_per_op":{"dir":"up","min_pct":0.05,"floor":null,"gatng":true}}}'
  _expect_reject "unknown sibling key" \
    '{"budgets":{"a.b.c":{"dir":"up","min_pct":0.1,"floor":5000,"unexpected":123}}}'
  _expect_reject "missing floor (absence must be stated as null, not omitted)" \
    '{"budgets":{"a.b.c":{"dir":"up","min_pct":0.1}}}'
  _expect_reject "quoted bimodal split_ratio" \
    '{"budgets":{"b.*.p95_ms":{"dir":"up","min_pct":0.1,"floor":null,"bimodal":{"metrics":{"m.p95_ms":0.3},"split_ratio":"2","upper_band_pct":0.25,"share_runs":5,"share_alpha":0.05}}}}'
  _expect_reject "bimodal upper_band_pct of 1 or more" \
    '{"budgets":{"b.*.p95_ms":{"dir":"up","min_pct":0.1,"floor":null,"bimodal":{"metrics":{"m.p95_ms":0.3},"split_ratio":2,"upper_band_pct":1,"share_runs":5,"share_alpha":0.05}}}}'
  _expect_reject "bimodal metric name for another leaf (a typo would silently never apply)" \
    '{"budgets":{"b.*.p95_ms":{"dir":"up","min_pct":0.1,"floor":null,"bimodal":{"metrics":{"m.p99_ms":0.3},"split_ratio":2,"upper_band_pct":0.25,"share_runs":5,"share_alpha":0.05}}}}'
  _expect_reject "misspelled bimodal key" \
    '{"budgets":{"b.*.p95_ms":{"dir":"up","min_pct":0.1,"floor":null,"bimodal":{"metrics":{"m.p95_ms":0.3},"split_ratio":2,"upper_pct":0.25,"share_runs":5,"share_alpha":0.05}}}}'
  _expect_reject "quoted historical upper-mode share" \
    '{"budgets":{"b.*.p95_ms":{"dir":"up","min_pct":0.1,"floor":null,"bimodal":{"metrics":{"m.p95_ms":"0.3"},"split_ratio":2,"upper_band_pct":0.25,"share_runs":5,"share_alpha":0.05}}}}'
  _expect_reject "fractional share_runs" \
    '{"budgets":{"b.*.p95_ms":{"dir":"up","min_pct":0.1,"floor":null,"bimodal":{"metrics":{"m.p95_ms":0.3},"split_ratio":2,"upper_band_pct":0.25,"share_runs":4.5,"share_alpha":0.05}}}}'
  _expect_reject "share_alpha of 1 (the share check could never stay quiet)" \
    '{"budgets":{"b.*.p95_ms":{"dir":"up","min_pct":0.1,"floor":null,"bimodal":{"metrics":{"m.p95_ms":0.3},"split_ratio":2,"upper_band_pct":0.25,"share_runs":5,"share_alpha":1}}}}'
  _expect_reject "bimodal on a gating budget" \
    '{"budgets":{"b.*.p95_ms":{"dir":"up","min_pct":0.1,"floor":null,"gating":true,"bimodal":{"metrics":{"m.p95_ms":0.3},"split_ratio":2,"upper_band_pct":0.25,"share_runs":5,"share_alpha":0.05}}}}'
  _expect_reject "bimodal on a down budget" \
    '{"budgets":{"b.*.rps":{"dir":"down","min_pct":0.1,"floor":null,"bimodal":{"metrics":{"m.rps":0.3},"split_ratio":2,"upper_band_pct":0.25,"share_runs":5,"share_alpha":0.05}}}}'
  _expect_reject "quoted min_abs (compare would ignore it)" \
    '{"budgets":{"a.b.c":{"dir":"up","min_pct":0.05,"floor":null,"min_abs":"1"}}}'
  _expect_reject "negative min_abs" \
    '{"budgets":{"a.b.c":{"dir":"up","min_pct":0.05,"floor":null,"min_abs":-1}}}'
  _expect_reject "min_abs with bimodal (the per-mode bands do not apply it)" \
    '{"budgets":{"b.*.p95_ms":{"dir":"up","min_pct":0.1,"floor":null,"min_abs":1,"bimodal":{"metrics":{"m.p95_ms":0.3},"split_ratio":2,"upper_band_pct":0.25,"share_runs":5,"share_alpha":0.05}}}}'
  _expect_reject "quoted min_samples" \
    '{"budgets":{"path_coverage.*.p99_ms":{"dir":"up","min_pct":0.25,"floor":null,"min_samples":"5000"}}}'
  _expect_reject "fractional min_samples" \
    '{"budgets":{"path_coverage.*.p99_ms":{"dir":"up","min_pct":0.25,"floor":null,"min_samples":50.5}}}'
  _expect_reject "min_samples outside path_coverage (no sample count there, so silently ignored)" \
    '{"budgets":{"behaviours.*.p99_ms":{"dir":"up","min_pct":0.1,"floor":null,"min_samples":5000}}}'
  _expect_reject "entry is not an object" \
    '{"budgets":{"a.b.c":[1,2,3]}}'
  _expect_reject "no budgets object" \
    '{"nope":{}}'
  _expect_reject "quoted per-metric band (it would silently never apply)" \
    '{"budgets":{"m.*.alloc":{"dir":"up","min_pct":0.05,"floor":null,"bands":{"level_pct":0.1,"metrics":{"x.alloc":{"band":"12","median":5208}}}}}}'
  _expect_reject "per-metric band of 0" \
    '{"budgets":{"m.*.alloc":{"dir":"up","min_pct":0.05,"floor":null,"bands":{"level_pct":0.1,"metrics":{"x.alloc":{"band":0,"median":5208}}}}}}'
  _expect_reject "band metric name for another leaf (a typo would silently never apply)" \
    '{"budgets":{"m.*.alloc":{"dir":"up","min_pct":0.05,"floor":null,"bands":{"level_pct":0.1,"metrics":{"x.time":{"band":12,"median":5208}}}}}}'
  _expect_reject "band entry without the median it was derived at" \
    '{"budgets":{"m.*.alloc":{"dir":"up","min_pct":0.05,"floor":null,"bands":{"level_pct":0.1,"metrics":{"x.alloc":{"band":12}}}}}}'
  _expect_reject "bands level_pct of 1 or more" \
    '{"budgets":{"m.*.alloc":{"dir":"up","min_pct":0.05,"floor":null,"bands":{"level_pct":1,"metrics":{"x.alloc":{"band":12,"median":5208}}}}}}'
  _expect_reject "misspelled bands key" \
    '{"budgets":{"m.*.alloc":{"dir":"up","min_pct":0.05,"floor":null,"bands":{"level":0.1,"metrics":{"x.alloc":{"band":12,"median":5208}}}}}}'
  _expect_reject "empty bands.metrics" \
    '{"budgets":{"m.*.alloc":{"dir":"up","min_pct":0.05,"floor":null,"bands":{"level_pct":0.1,"metrics":{}}}}}'
  _expect_reject "bands with a floor (the floor would be ignored)" \
    '{"budgets":{"m.*.alloc":{"dir":"up","min_pct":0.05,"floor":100,"bands":{"level_pct":0.1,"metrics":{"x.alloc":{"band":12,"median":5208}}}}}}'
  _expect_reject "bands on a down budget" \
    '{"budgets":{"m.*.rps":{"dir":"down","min_pct":0.05,"floor":null,"bands":{"level_pct":0.1,"metrics":{"x.rps":{"band":12,"median":5208}}}}}}'
  _expect_reject "bands with bimodal" \
    '{"budgets":{"b.*.p95_ms":{"dir":"up","min_pct":0.1,"floor":null,"bands":{"level_pct":0.1,"metrics":{"m.p95_ms":{"band":1,"median":3}}},"bimodal":{"metrics":{"m.p95_ms":0.3},"split_ratio":2,"upper_band_pct":0.25,"share_runs":5,"share_alpha":0.05}}}}'

  # Shapes that MUST be accepted, so the validator cannot pass by rejecting all.
  _expect_accept() {
    local name="$1" json="$2" reason
    printf '%s' "$json" > "$tmp/b.json"
    if reason="$(validate_perf_budgets "$tmp/b.json")"; then
      echo "  ok    $name -- accepted"
    else
      echo "  FAIL  $name -- validator REJECTED a valid file: $reason"
      failures=$((failures + 1))
    fi
  }
  _expect_accept "null floor (MAD band only)" \
    '{"budgets":{"a.b.c":{"dir":"up","min_pct":0.1,"floor":null,"gating":false}}}'
  _expect_accept "well-formed hw:true (the real shape this change adds)" \
    '{"budgets":{"a.b.c":{"dir":"up","min_pct":0.1,"floor":5000,"hw":true}}}'
  _expect_accept "well-formed notify-only bimodal" \
    '{"budgets":{"b.*.p95_ms":{"dir":"up","min_pct":0.1,"floor":null,"gating":false,"bimodal":{"metrics":{"m.p95_ms":0.3},"split_ratio":2,"upper_band_pct":0.25,"share_runs":5,"share_alpha":0.05}}}}'
  _expect_accept "well-formed min_abs" \
    '{"budgets":{"a.b.c":{"dir":"up","min_pct":0.05,"floor":null,"min_abs":1}}}'
  _expect_accept "well-formed path_coverage min_samples" \
    '{"budgets":{"path_coverage.*.p99_ms":{"dir":"up","min_pct":0.25,"floor":null,"min_samples":5000}}}'
  _expect_accept "comment entry holding an array" \
    '{"budgets":{"_comment_x":["prose","more prose"],"a.b.c":{"dir":"down","min_pct":0,"floor":1}}}'
  _expect_accept "well-formed per-metric bands, on a gating budget too" \
    '{"budgets":{"m.*.alloc":{"dir":"up","min_pct":0.05,"floor":null,"gating":true,"bands":{"level_pct":0.1,"metrics":{"x.alloc":{"band":12.1,"median":5208.01}}}}}}'

  if [ "$failures" -ne 0 ]; then
    echo "--- perf-budgets validator self-test: $failures failure(s)" >&2
    return 1
  fi
  echo "--- perf-budgets validator self-test: all checks passed"
  return 0
}

# Run the self-test when invoked directly rather than sourced.
if [ "${BASH_SOURCE[0]}" = "${0}" ]; then
  case "${1:-}" in
    --self-test)
      _perf_budgets_self_test
      exit $?
      ;;
    *)
      echo "usage: $0 --self-test" >&2
      echo "       (or source this file and call validate_perf_budgets <file>)" >&2
      exit 2
      ;;
  esac
fi
