#!/usr/bin/env bash
# Clustered-state A/B helpers, sourced by perf-test-run.sh: CPU pinning for the control and the two
# cluster nodes, cgroup CPU-throttling snapshots, the paired window loop (control and cluster
# alternate, pair by pair) and the .clustered_state assembly. Defines functions only.
# Docs: docs/code/performance-measurement.md#clustered-state-ab--clustered_state-notify-only.
# Tests: .buildkite/scripts/test/perf-clustered-ab-test.sh

# Pinning ------------------------------------------------------------------------------------------
# Three non-overlapping cpusets of <width> cpus carved, in order, from the main SUT's cpuset (idle
# during this phase, as for the FILE-body A/B). Prints "<control> <node_a> <node_b>"; fails when
# the width is not a whole number or the cpuset is too small.
clu_split_cpusets() { # server_cpus width
  local w="$2" ids
  [[ "$w" =~ ^[1-9][0-9]*$ ]] || return 1
  read -ra ids <<<"$(expand_cpuset "$1")"
  [ "${#ids[@]}" -ge $((3 * w)) ] || return 1
  echo "$(compress_cpulist "${ids[*]:0:w}") $(compress_cpulist "${ids[*]:w:w}") $(compress_cpulist "${ids[*]:$((2 * w)):w}")"
}

# Sets CLU_PIN_MODE (cpuset | cpu-quota), CLU_PIN_REASON and CLU_{CTRL,A,B}_CPUS. cpu-quota is the
# fallback: --cpus <CLU_CPUS> on every node, which CFS enforces per 100 ms period, so a burst is
# throttled. Pinned nodes get a cpuset and no quota, like the main SUT.
# shellcheck disable=SC2034  # CLU_PIN_REASON is read by perf-test-run.sh
clu_resolve_pinning() { # server_cpus width
  local split out
  CLU_PIN_MODE=cpu-quota; CLU_CTRL_CPUS=""; CLU_A_CPUS=""; CLU_B_CPUS=""
  if [ "${PERF_CLUSTERED_PIN:-true}" != "true" ]; then CLU_PIN_REASON=disabled; return 0; fi
  if [ -n "${PERF_CLUSTERED_CTRL_CPUS:-}${PERF_CLUSTERED_A_CPUS:-}${PERF_CLUSTERED_B_CPUS:-}" ]; then
    if [ -z "${PERF_CLUSTERED_CTRL_CPUS:-}" ] || [ -z "${PERF_CLUSTERED_A_CPUS:-}" ] || [ -z "${PERF_CLUSTERED_B_CPUS:-}" ]; then
      CLU_PIN_REASON=partial_override
      echo "WARNING: clustered A/B: set all of PERF_CLUSTERED_CTRL_CPUS, _A_CPUS and _B_CPUS or none; falling back to --cpus" >&2
      return 0
    fi
    split="$PERF_CLUSTERED_CTRL_CPUS $PERF_CLUSTERED_A_CPUS $PERF_CLUSTERED_B_CPUS"; CLU_PIN_REASON=override
  elif [ -z "$1" ]; then
    CLU_PIN_REASON=host_unpinned; return 0
  elif ! [[ "$2" =~ ^[1-9][0-9]*$ ]]; then
    CLU_PIN_REASON=fractional_cpus
    echo "WARNING: clustered A/B: PERF_CLUSTERED_CPUS='$2' is not a whole number of cpus to pin; falling back to --cpus" >&2
    return 0
  elif ! split="$(clu_split_cpusets "$1" "$2")"; then
    CLU_PIN_REASON=server_cpuset_too_small
    echo "WARNING: clustered A/B: cannot carve three ${2}-cpu sets from the SUT cpuset '$1'; falling back to --cpus" >&2
    return 0
  else
    CLU_PIN_REASON=server_cpuset_split
  fi
  read -r CLU_CTRL_CPUS CLU_A_CPUS CLU_B_CPUS <<<"$split"
  if ! out="$(cpusets_physically_disjoint clu_control "$CLU_CTRL_CPUS" clu_node_a "$CLU_A_CPUS" clu_node_b "$CLU_B_CPUS" \
                upstream "${UPSTREAM_CPUS:-}" k6 "${K6_CPUS:-}" 2>&1)"; then
    echo "WARNING: clustered A/B: the node cpusets share physical cores; falling back to --cpus:" >&2
    grep -v '^\^\^\^' <<<"$out" | sed 's/^/    /' >&2 || true
    CLU_PIN_MODE=cpu-quota; CLU_PIN_REASON=not_physically_disjoint; CLU_CTRL_CPUS=""; CLU_A_CPUS=""; CLU_B_CPUS=""
    return 0
  fi
  CLU_PIN_MODE=cpuset
}

# Sets CLU_CPU_FLAGS, the docker run CPU flags for one node: its cpuset (and its NUMA node's memory)
# when pinned, else --cpus.
clu_cpu_flags() { # cpuset_or_empty
  local mems
  if [ "$CLU_PIN_MODE" = cpuset ] && [ -n "$1" ]; then
    CLU_CPU_FLAGS=("--cpuset-cpus=$1")
    mems="$(numa_mems_flag "$1" 2>/dev/null || true)"
    [ -z "$mems" ] || CLU_CPU_FLAGS+=("$mems")
  else
    CLU_CPU_FLAGS=(--cpus "$CLU_CPUS")
  fi
}

# Throttling ---------------------------------------------------------------------------------------
# The container's cgroup cpu.stat on the host: cgroup v2 under the systemd or cgroupfs driver, then
# v1. The images are distroless (no cat), so docker exec cannot read it from inside.
clu_cpu_stat_file() { # container
  local id root="${PERF_CGROUP_ROOT:-/sys/fs/cgroup}" f
  id="$(docker inspect -f '{{.Id}}' "$1" 2>/dev/null)" || return 1
  [ -n "$id" ] || return 1
  for f in "$root/system.slice/docker-$id.scope/cpu.stat" "$root/docker/$id/cpu.stat" \
           "$root/cpu,cpuacct/docker/$id/cpu.stat" "$root/cpu/docker/$id/cpu.stat" \
           "$root/cpu,cpuacct/system.slice/docker-$id.scope/cpu.stat"; do
    [ -r "$f" ] && { echo "$f"; return 0; }
  done
  f="$(find "$root" -maxdepth 5 -path "*$id*" -name cpu.stat 2>/dev/null | head -1)"
  [ -n "$f" ] && echo "$f"
}

# One cpu.stat reading as JSON, or null. v1 reports throttled_time in ns and no usage_usec.
clu_cpu_stat_json() { # file
  [ -n "$1" ] && [ -r "$1" ] || { echo null; return 0; }
  awk '
    $1 == "nr_periods" { p = $2 } $1 == "nr_throttled" { t = $2 }
    $1 == "throttled_usec" { tu = $2 } $1 == "throttled_time" { tu = int($2 / 1000) } $1 == "usage_usec" { u = $2 }
    function v(x) { return x == "" ? "null" : x }
    END { printf "{\"nr_periods\":%s,\"nr_throttled\":%s,\"throttled_usec\":%s,\"usage_usec\":%s}\n", v(p), v(t), v(tu), v(u) }' "$1"
}

# Resolves each node's cpu.stat path once, into CLU_STAT_FILE_{control,node_a,node_b}.
clu_locate_cpu_stats() { # control_container node_a_container node_b_container
  CLU_STAT_FILE_control="$(clu_cpu_stat_file "$1" || true)"
  CLU_STAT_FILE_node_a="$(clu_cpu_stat_file "$2" || true)"
  CLU_STAT_FILE_node_b="$(clu_cpu_stat_file "$3" || true)"
  echo "--- clustered A/B: cgroup cpu.stat control=${CLU_STAT_FILE_control:-<not found>} node_a=${CLU_STAT_FILE_node_a:-<not found>} node_b=${CLU_STAT_FILE_node_b:-<not found>}"
}

clu_snapshot() { # role
  local v="CLU_STAT_FILE_$1" j
  j="$(clu_cpu_stat_json "${!v:-}" 2>/dev/null)" || j=null
  if jq -e 'type == "object" or . == null' >/dev/null 2>&1 <<<"$j"; then echo "$j"; else echo null; fi
}

# Windows ------------------------------------------------------------------------------------------
# Which arm opens pair <i>: alternates pair by pair, and the starting arm alternates with the build
# number, so drift over the phase falls on both arms equally.
clu_pair_order() { # pair build_number
  local n="${2:-0}"
  [[ "$n" =~ ^[0-9]+$ ]] || n=0
  if [ $(( (n + $1) % 2 )) -eq 1 ]; then echo "control cluster"; else echo "cluster control"; fi
}

# One measured window: snapshot the arm's containers, run the k6 script, snapshot again, and append
# the readings to clu-throttle.ndjson. Needs run_clu_regression / run_clu_crossing from the caller.
# perf-test-run.sh runs under set -e, so nothing here may fail the run: a bad reading is null.
clu_window() { # pair kind(reg|cross) arm(control|cluster) warmup
  local pair="$1" kind="$2" arm="$3" warmup="$4" proto url roles role t0 t1 rc=0 before="{}" after="{}"
  local out="clu-p${pair}-${kind}-${arm}.json"
  if [ "$arm" = control ]; then proto=clustered_control; url="http://clu-ctrl:1080"; roles="control"
  else proto=clustered; url="http://${CLU_A_ALIAS:-clu-a}:1080"; roles="node_a node_b"; fi
  for role in $roles; do before="$(jq -c --arg r "$role" --argjson s "$(clu_snapshot "$role")" '.[$r] = $s' <<<"$before")" || before="{}"; done
  t0="$(date +%s)"
  if [ "$kind" = reg ]; then run_clu_regression "$proto" "$url" "$out" "$warmup" || rc=$?
  else run_clu_crossing "$proto" "$url" "$out" "$warmup" || rc=$?; fi
  t1="$(date +%s)"
  [ "$rc" -eq 0 ] || echo "WARNING: clustered A/B: pair $pair $kind window on the $arm arm failed (exit $rc)" >&2
  for role in $roles; do after="$(jq -c --arg r "$role" --argjson s "$(clu_snapshot "$role")" '.[$r] = $s' <<<"$after")" || after="{}"; done
  jq -nc --argjson pair "$pair" --arg kind "$kind" --arg arm "$arm" --argjson wall "$((t1 - t0))" \
    --argjson before "$before" --argjson after "$after" \
    '{pair: $pair, kind: $kind, arm: $arm, wall_s: $wall, before: $before, after: $after}' >> "$OUT_DIR/clu-throttle.ndjson" || true
}

# All pairs. Pair i runs regression on its first arm then its second, and crossing in the reverse
# order, so each window sits next to its partner. Pairs after the first reuse warm servers, so they
# get the shorter warm-up. Sets CLU_PAIR_ORDERS (one "first,second" per pair, space-separated).
clu_run_pairs() { # pairs
  local i order first second warmup
  rm -f "$OUT_DIR"/clu-p*-reg-*.json "$OUT_DIR"/clu-p*-cross-*.json "$OUT_DIR/clu-throttle.ndjson"
  : > "$OUT_DIR/clu-throttle.ndjson"
  CLU_PAIR_ORDERS=""
  for ((i = 1; i <= $1; i++)); do
    order="$(clu_pair_order "$i" "${BUILDKITE_BUILD_NUMBER:-0}")"; read -r first second <<<"$order"
    warmup="$CLU_WARMUP"; if [ "$i" -gt 1 ]; then warmup="$CLU_PAIR_WARMUP"; fi
    CLU_PAIR_ORDERS="${CLU_PAIR_ORDERS:+$CLU_PAIR_ORDERS }$first,$second"
    echo "--- clustered A/B: pair $i of $1 ($first first, warm-up $warmup)"
    clu_window "$i" reg "$first" "$warmup"
    clu_window "$i" reg "$second" "$warmup"
    clu_window "$i" cross "$second" "$warmup"
    clu_window "$i" cross "$first" "$warmup"
  done
}

clu_json_or_null() { jq -ce 'select(type == "object")' "$1" 2>/dev/null || echo null; }

# Assembly -----------------------------------------------------------------------------------------
# {state, control_behaviours, cluster_behaviours} from the window files in <dir>. <meta> carries the
# cluster proof, image and pinning fields, merged into state. Per arm, each ratio is the median of
# the per-pair ratios (cluster / control) and is null unless every pair has one; the absolutes are
# medians over the windows (error_rate a mean, counts a sum). measured is false when any window
# produced nothing or the cluster was not genuine, and compare then reads no clustered_state rows.
clu_assemble_json() { # dir pairs meta_json
  local d="$1" n="$2" i kind arm windows="[]"
  for ((i = 1; i <= n; i++)); do
    for kind in reg cross; do
      for arm in control cluster; do
        windows="$(jq -c --argjson p "$i" --arg k "$kind" --arg a "$arm" --argjson r "$(clu_json_or_null "$d/clu-p$i-$kind-$arm.json")" \
          '. + [{pair: $p, kind: $k, arm: $a, behaviours: (if ($r | type) == "object" and (($r.behaviours // {}) | length) > 0 then $r.behaviours else null end)}]' <<<"$windows")"
      done
    done
  done
  jq -nc --argjson windows "$windows" --argjson n "$n" --argjson meta "$3" \
    --argjson material_pct "${PERF_CLUSTERED_THROTTLE_MATERIAL_PCT:-1}" \
    --slurpfile throttle <(cat "$d/clu-throttle.ndjson" 2>/dev/null || true) '
    def median: map(select(. != null)) | sort | length as $l
      | if $l == 0 then null elif $l % 2 == 1 then .[($l - 1) / 2] else (.[$l / 2 - 1] + .[$l / 2]) / 2 end;
    def r3: if . == null then null else (. * 1000 | round) / 1000 end;
    def strict(f): if length > 0 and all(.[]; (. | type) == "number") then f else null end;
    def op(k; p): k | sub("_" + p + "$"; "");
    def side($arm; $p): [ $windows[] | select(.arm == $arm and .pair == $p) | (.behaviours // {}) ] | add // {};
    def field($f): [ .[] | select(. != null) ] as $nn
      | if ($nn | length) > 0 and all($nn[]; type == "boolean" or type == "string") then $nn[0]
        elif IN($f; "sample_count", "dropped_iterations", "settle_excluded", "measured_window_s") then strict(add)
        elif $f == "error_rate" then strict(add / length | . * 100000 | round / 100000)
        else strict(median | r3) end;
    def combine: . as $ws | ([ $ws[] | keys[] ] | unique) as $fields
      | reduce $fields[] as $f ({}; . + { ($f): ([ $ws[] | .[$f] ] | field($f)) })
      + { windows: ($ws | length) };
    def aggregate($arm): [ range(1; $n + 1) as $p | side($arm; $p) ] as $per
      | ([ $per[] | keys[] ] | unique) as $keys
      | reduce $keys[] as $k ({}; . + { ($k): ([ $per[] | .[$k] // {} ] | combine) });
    def byop($b; $p): $b | to_entries | map({key: op(.key; $p), value: .value}) | from_entries;
    def pair_ratio($c; $k): if ($c | type) == "number" and $c > 0 and ($k | type) == "number" then $k / $c else null end;
    def pair_ratios($op; $f): [ range(1; $n + 1) as $p
      | pair_ratio((byop(side("control"; $p); "clustered_control"))[$op][$f]; (byop(side("cluster"; $p); "clustered"))[$op][$f]) ];
    def ratio_of($rs): if ($rs | length) > 0 and all($rs[]; . != null) then ($rs | median | r3) else null end;
    def script: if .kind == "reg" then "regression.js" else "clustered_crossing.js" end;
    def tdelta($b; $a; $f): if ($b[$f] | type) == "number" and ($a[$f] | type) == "number" then $a[$f] - $b[$f] else null end;
    aggregate("control") as $CB | aggregate("cluster") as $KB
    | byop($CB; "clustered_control") as $C | byop($KB; "clustered") as $K
    | ([ $K | keys[] | select($C[.] != null) | . as $op | ($C[$op]) as $c | ($K[$op]) as $k
          | pair_ratios($op; "p50_ms") as $p50s | pair_ratios($op; "p95_ms") as $p95s | pair_ratios($op; "throughput_rps") as $tps
          | { key: $op, value: {
              p50_control_ms: $c.p50_ms, p50_clustered_ms: $k.p50_ms, p50_ratio: ratio_of($p50s),
              p95_control_ms: $c.p95_ms, p95_clustered_ms: $k.p95_ms, p95_ratio: ratio_of($p95s),
              throughput_control_rps: $c.throughput_rps, throughput_clustered_rps: $k.throughput_rps,
              throughput_ratio: ratio_of($tps),
              p50_pair_ratios: ($p50s | map(r3)), p95_pair_ratios: ($p95s | map(r3)), throughput_pair_ratios: ($tps | map(r3)),
              control_error_rate: $c.error_rate, clustered_error_rate: $k.error_rate,
              sample_count_control: $c.sample_count, sample_count_clustered: $k.sample_count,
              min_window_samples: ([ range(1; $n + 1) as $p
                | (byop(side("control"; $p); "clustered_control"))[$op].sample_count, (byop(side("cluster"; $p); "clustered"))[$op].sample_count ]
                | if all(.[]; type == "number") then min else null end),
              crosses_network: ($k.crosses_network // false) } } ] | from_entries) as $arms
    | ($meta.cluster // {}) as $cl
    | def wops: (.behaviours // {}) | keys | map(sub("_clustered(_control)?$"; ""));
    ([ $windows[] | select(.behaviours != null) | . as $w | (([ $windows[] | select(.kind == $w.kind and .behaviours != null) | wops[] ] | unique) - ($w | wops))[]
       | {op: ., required: IN(.; "match", "forward", "crossing"),
          text: "pair \($w.pair) \($w | script) window on the \($w.arm) arm has no \(.) arm, which other windows of that script have" } ]) as $absent
    | ([ $windows[] | select(.behaviours == null) | "pair \(.pair) \(script) window on the \(.arm) arm produced no result" ]
       + [ $windows[] | select(.behaviours != null) | . as $w | .behaviours | to_entries[]
           | select((.value.error_rate // 1) > 0.01)
           | "pair \($w.pair) \($w | script) window on the \($w.arm) arm: \(.key) failed \(.value.error_rate // "an unknown share") of its requests (over the 1% bound), so its latency describes failures" ]
       + (if ($cl.member_count_node_a // 0) >= 2 then [] else ["the cluster view did not reach two members"] end)
       + (if $cl.state_crossed_network == true then [] else ["state did not cross from node A to node B"] end)
       + (if ($cl.control_member_count // 0) == 1 then [] else ["the control was not a single node"] end)
       + (if ($cl.member_count_node_a_after // 0) >= 2 and ($cl.member_count_node_b_after // 0) >= 2 then []
          else ["the cluster view was below two members after the windows (A \($cl.member_count_node_a_after // "unread"), B \($cl.member_count_node_b_after // "unread")), so some windows may have measured a single node"] end)
       + (if ($cl.control_member_count_after // 0) == 1 then [] else ["the control was not a single node after the windows (\($cl.control_member_count_after // "unread"))"] end)
       + [ $absent[] | select(.required) | .text ]
       + [ "match", "forward", "crossing" | select(($arms[.].p50_ratio // null) == null) | "arm \(.) produced no p50 ratio in every pair" ]
       + [ $arms | to_entries[] | select((.value.min_window_samples // 0) >= 30)
           | .key as $op | .value as $v | ("p50_ratio", "p95_ratio", "throughput_ratio") | select($v[.] == null)
           | "arm \($op) cleared the 30-sample tail floor in every window but has no \(.)" ]) as $problems
    | ([ $throttle[] | .arm as $arm | .pair as $p | .kind as $kind | .wall_s as $wall | .before as $b | .after as $a
         | ($b | keys[]) as $role | ($b[$role] // {}) as $rb | ($a[$role] // {}) as $ra
         | { pair: $p, kind: $kind, arm: $arm, role: $role, wall_s: $wall,
             nr_periods: tdelta($rb; $ra; "nr_periods"), nr_throttled: tdelta($rb; $ra; "nr_throttled"),
             throttled_usec: tdelta($rb; $ra; "throttled_usec"), usage_usec: tdelta($rb; $ra; "usage_usec") }
         | . + { throttled_period_pct: (if .nr_periods == null or .nr_throttled == null then null
                                         elif .nr_periods == 0 then 0 else (100 * .nr_throttled / .nr_periods | r3) end),
                 cpu_util_pct: (if .usage_usec == null or $wall <= 0 then null else (.usage_usec / ($wall * 10000) | r3) end) } ]) as $tw
    | ($tw | length == 6 * $n and all(.[]; .throttled_period_pct != null)) as $tavail
    | {
        attempted: true,
        measured: (($problems | length) == 0),
        problems: $problems,
        # Arms absent from some windows that do not unmeasure the run (their ratios are null).
        warnings: [ $absent[] | select(.required | not) | .text ],
        method: (if $meta.cpu_pinning.mode == "cpuset" then "paired-cpuset" else "paired-cpu-quota" end),
        estimator: "per arm, each ratio is the median over pairs of (cluster / control) from adjacent windows; absolutes are medians over windows",
        pairs: $n,
        control_backend: "memory",
        candidate_backend: "infinispan",
        throttling: {
          available: $tavail,
          material_threshold_pct: $material_pct,
          material: (if $tavail then any($tw[]; .throttled_period_pct >= $material_pct) else null end),
          by_role: ($tw | group_by(.role) | map({ key: .[0].role, value: {
              windows: length,
              nr_periods: (map(.nr_periods) | strict(add)), nr_throttled: (map(.nr_throttled) | strict(add)),
              throttled_usec: (map(.throttled_usec) | strict(add)),
              max_window_throttled_period_pct: (map(.throttled_period_pct) | strict(max)),
              median_window_cpu_util_pct: (map(.cpu_util_pct) | strict(median | r3)) } }) | from_entries),
          windows: $tw
        },
        arms: $arms
      } as $state
    | { state: ($meta + $state), control_behaviours: $CB, cluster_behaviours: $KB }'
}
