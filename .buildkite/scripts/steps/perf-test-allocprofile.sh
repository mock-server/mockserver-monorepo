#!/usr/bin/env bash
set -euo pipefail

# Allocation-profile step (perf queue). Answers "what is actually allocating?" on
# every dispatched perf run, WITHOUT contaminating the publishable figures.
#
# It runs the SAME harness as the clean `perf-run` step (perf-test-run.sh) but with
# PERF_JVM_DIAGNOSTICS=deep — tier-2 instrumentation (GC file logging, NMT, and a
# JFR `profile` recording that samples allocation + CPU hotspots). That instrumentation
# depresses throughput BY DESIGN, which is exactly why this is a SEPARATE step and MUST
# NOT feed the baseline:
#
#   1. PERF_RUN_NAME=allocprofile prefixes EVERY artifact this run uploads
#      (allocprofile-perf-result.json, allocprofile-perf-jvm-diagnostics.tgz, ...).
#      perf-test-compare.sh downloads `perf-result.json` build-wide by EXACT name and
#      persists what it downloads to S3; a prefixed name cannot match, so the degraded
#      throughput can never enter the rolling baseline. (Belt and braces: a deep run
#      also stamps baseline_eligible:false, which compare honours — but compare never
#      even sees this run's result, because the name does not match.)
#   2. This step is NOT a dependency of perf-compare (see perf-test-guard.sh), so it
#      cannot be waited on, gated on, or baselined.
#   3. soft_fail:true at the pipeline level — a throughput number here never reds the
#      build; only a genuine harness fault shows (visibly) as a soft-fail.
#
# A short ladder + trimmed durations keep it cheap: this measures WHAT allocates, not
# how fast. The auxiliary profiles the daily run carries (INFO-log arm, laptop,
# streaming, proxy, clustered, path coverage) are turned off — they add wall-clock without adding
# allocation-attribution value on the main serving paths (match / template / large
# bodies / event-log), which regression.js + growth.js + a 4-rung sweep already cover.
#
# After the run it emits a compact annotation: allocation per request over the load
# window, peak direct-buffer memory, the last live-heap histogram (and whether any heap
# inspection landed inside a sweep rung), the top allocation sites/classes from the SUT's
# load-window JFR dump (sut/load.jfr), and a CPU / lock / GC / VM-operation profile of the
# ceiling window alone (sut/ceiling.jfr: the knee rung to the top of the ladder), read with a
# JDK 25 `jfr` sidecar. A recording below its floors is reported INVALID instead of being
# summarised. Best-effort: it never changes the step's exit code.

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../../.." && pwd)"

RUN_NAME="allocprofile"
ARTIFACT_PREFIX="${RUN_NAME}-"
ANNOTATE_CONTEXT="perf-${RUN_NAME}"
# shellcheck source=lib/perf-jfr-image.sh
. "$SCRIPT_DIR/lib/perf-jfr-image.sh"
JDK_IMAGE="$PERF_JFR_JDK_IMAGE"
JFR_VIEW_DEADLINE_S="${PERF_ALLOCPROFILE_JFR_DEADLINE_S:-120}"
# Sanity floor for the recording the views are read from: a recording shorter than this, or
# with fewer allocation samples, cannot describe the load and is reported INVALID.
MIN_JFR_DURATION_S="${PERF_ALLOCPROFILE_MIN_JFR_DURATION_S:-60}"
MIN_ALLOC_SAMPLES="${PERF_ALLOCPROFILE_MIN_ALLOC_SAMPLES:-1000}"
MIN_CEILING_S="${PERF_ALLOCPROFILE_MIN_CEILING_S:-30}"
MIN_CEILING_EXEC_SAMPLES="${PERF_ALLOCPROFILE_MIN_CEILING_EXEC_SAMPLES:-500}"
CEILING_TOP_THREADS="${PERF_ALLOCPROFILE_TOP_THREADS:-12}"
CEILING_TOP_ROLES="${PERF_ALLOCPROFILE_TOP_ROLES:-4}"
CEILING_ROLE_METHODS="${PERF_ALLOCPROFILE_ROLE_METHODS:-8}"
# Flag an application thread whose busiest in-rung period (see thread_cpu_table) reached this "% of
# one core"; the window average cannot, since the gaps between rungs dilute it.
HOT_THREAD_PCT="${PERF_ALLOCPROFILE_HOT_THREAD_PCT:-90}"
# Buildkite rejects an annotation body over 1 MiB; this leaves headroom for the header line.
ANNOTATION_MAX_BYTES="${PERF_ALLOCPROFILE_ANNOTATION_MAX_BYTES:-1000000}"

annotate() { # style, body
  if command -v buildkite-agent >/dev/null 2>&1; then
    printf '%s\n' "$2" | buildkite-agent annotate --style "$1" --context "$ANNOTATE_CONTEXT" || true
  fi
  printf '\n%s\n' "$2"
}

# After one call times out every later call is skipped, so a wedged Docker costs one deadline, not one
# per view. The marker is a file because most calls run in a command substitution or a pipeline.
# -k: the docker CLI can outlive SIGTERM while the daemon is wedged.
jfr_tool() { # work_dir, jfr args... (paths under /w)
  local w="$1" rc=0; shift
  [ ! -e "$w/.jfr-timed-out" ] || return 124
  timeout -k 10 "$JFR_VIEW_DEADLINE_S" docker run --rm -v "$w:/w" "$JDK_IMAGE" jfr "$@" 2>/dev/null || rc=$?
  case "$rc" in 124|137) : > "$w/.jfr-timed-out" ;; esac
  return "$rc"
}

# Keep an annotation body under the byte limit: whole lines only, an open code fence closed, and
# a note saying where the rest is.
cap_annotation() { # max_bytes < body
  LC_ALL=C awk -v max="$1" '
    !cut && bytes + length($0) + 1 > max - 200 { cut = 1 }
    cut { next }
    { bytes += length($0) + 1; print; if ($0 ~ /^```/) fence = !fence }
    END {
      if (cut) {
        if (fence) print "```"
        print ""
        print "_Annotation truncated at " bytes " bytes to stay under the Buildkite limit; the full views are in the bundle._"
      }
    }'
}

# Assemble the readable chunks of a JFR repository into /w/assembled.jfr. The chunk a live (or
# hard-killed) JVM was still writing is unreadable and would make the whole assembly unreadable.
assemble_finished_chunks() { # work_dir, repository dir under it
  local w="$1" rel="${2#"$1"/}"
  # shellcheck disable=SC2016
  # As the agent's uid, so the step's own cleanup can remove what the sidecar writes into $w.
  timeout -k 10 "$JFR_VIEW_DEADLINE_S" docker run --rm --user "$(id -u):$(id -g)" -v "$w:/w" "$JDK_IMAGE" sh -c '
    mkdir -p /w/finished-chunks
    for f in "$1"/*.jfr; do jfr summary "$f" >/dev/null 2>&1 && cp "$f" /w/finished-chunks/; done
    ls /w/finished-chunks/*.jfr >/dev/null 2>&1 && jfr assemble /w/finished-chunks /w/assembled.jfr' _ "/w/$rel" >/dev/null 2>&1 || true
}

largest_repo_dir() { # jfr repository root -> the per-JVM subdirectory holding the most chunk data
  local d best="" best_kb=-1 kb
  for d in "$1"/*/; do
    [ -d "$d" ] || continue
    kb="$(du -sk "$d" | cut -f1)"
    [ "$kb" -gt "$best_kb" ] && { best="${d%/}"; best_kb="$kb"; }
  done
  printf '%s' "$best"
}

# Allocation per request and peak direct-buffer memory over the recorded load window, from
# diag-samples.csv (columns located by header name, so older bundles degrade to "unavailable").
load_window_figures() { # work_dir
  local w="$1" start end
  if [ ! -s "$w/load-window.json" ] || [ ! -s "$w/diag-samples.csv" ]; then
    echo "- allocation per request: unavailable (no recorded load window or resource samples)"
    return 0
  fi
  start="$(jq -r '.start_epoch // empty' "$w/load-window.json" 2>/dev/null || true)"
  end="$(jq -r '.end_epoch // empty' "$w/load-window.json" 2>/dev/null || true)"
  awk -F, -v s="${start:-0}" -v e="${end:-0}" '
    NR==1 { for (i=1;i<=NF;i++) col[$i]=i; next }
    !("jvm_allocated_bytes" in col) || !("req_dur_count" in col) { next }
    $1+0 >= s && $1+0 <= e && $col["jvm_allocated_bytes"] != "" && $col["req_dur_count"] != "" {
      a=$col["jvm_allocated_bytes"]+0; r=$col["req_dur_count"]+0
      if (!n++) { a0=a; r0=r; t0=$1 } a1=a; r1=r; t1=$1
    }
    ("direct_buffer_used_bytes" in col) && $1+0 >= s && $1+0 <= e && $col["direct_buffer_used_bytes"] != "" {
      d=$col["direct_buffer_used_bytes"]+0; if (d>dmax) dmax=d; dn++
    }
    ("netty_direct_used_bytes" in col) && $1+0 >= s && $1+0 <= e && $col["netty_direct_used_bytes"] != "" {
      d=$col["netty_direct_used_bytes"]+0; if (d>nmax) nmax=d; nn++
    }
    END {
      if (n>1 && r1>r0) printf "- allocation per request: **%.1f KB** (%.2f GB allocated over %d requests, %d s load window; includes JFR and scrape overhead)\n", (a1-a0)/(r1-r0)/1000, (a1-a0)/1e9, r1-r0, t1-t0
      else print "- allocation per request: unavailable (no allocation/request samples inside the load window)"
      if (dn>0 || nn>0) {
        printf "- direct memory peak during the load window: NIO direct pool **%s**, Netty-tracked **%s**\n", \
          (dn>0 ? sprintf("%.1f MiB", dmax/1048576) : "n/a"), (nn>0 ? sprintf("%.1f MiB", nmax/1048576) : "n/a (Netty not tracking)")
      } else print "- direct memory: unavailable (the SUT image predates jvm_buffer_pool_used_bytes)"
    }' "$w/diag-samples.csv"
}

# Every GC_HeapInspection (the live-heap histogram's stop-the-world pause) in the load recording,
# and how many overlapped a sweep rung (sweep-rungs.json). Expected: none since histograms moved
# to growth.js. Prints one markdown line; returns 1 when an inspection overlapped a rung.
heap_inspection_placement() { # work_dir jfr_rel
  local w="$1" line rc=0
  [ -s "$w/sweep-rungs.json" ] || { echo "- heap inspections vs sweep rungs: unavailable (no sweep-rungs.json in the bundle)"; return 0; }
  line="$(jfr_tool "$w" print --json --events jdk.ExecuteVMOperation "/w/$2" | jq -r --slurpfile rungs "$w/sweep-rungs.json" '
    def ms: capture("^(?<s>[^.Z]+)(\\.(?<f>[0-9]+))?Z$") | ((.s + "Z") | fromdate) * 1000 + (("0." + (.f // "0")) | tonumber) * 1000;
    def dur_ms: (capture("^PT(?<d>[0-9.]+)S$").d | tonumber * 1000) // 0;
    [.recording.events[] | select(.values.operation == "GC_HeapInspection")
     | (.values.startTime | ms) as $s | ($s + (.values.duration | dur_ms)) as $e
     | {s: $s, e: $e, rung: ([$rungs[0][] | select(.start_epoch_ms < $e and .end_epoch_ms > $s) | .offered_rps] | first)}]
    | "\(length) \([.[] | select(.rung != null)] | length) \([.[] | select(.rung != null) | .rung] | unique | join(","))"' 2>/dev/null || true)"
  if [ -z "$line" ]; then echo "- heap inspections vs sweep rungs: unavailable (could not read jdk.ExecuteVMOperation)"; return 0; fi
  read -r total inside rungs <<<"$line"
  if [ "${inside:-0}" -gt 0 ]; then
    echo "- :warning: **${inside} of ${total} heap inspections landed inside a sweep rung** (${rungs} rps) — each is a ~0.5 s stop-the-world pause in that rung's p99.9"; rc=1
  else
    echo "- heap inspections (live-heap histogram samples): ${total}, none inside a sweep rung"
  fi
  return "$rc"
}

# The jdk.ThreadCPULoad period in seconds from a recording's active settings ("Thread CPU Load ... 10 s"),
# or nothing when the recording has no settings events.
thread_cpu_period() { # work_dir jfr_rel
  jfr_tool "$1" view --width 200 active-settings "/w/$2" | awk '
    BEGIN { u["ms"] = 0.001; u["s"] = 1; u["m"] = 60; u["min"] = 60; u["h"] = 3600 }
    /^Thread CPU Load / { for (i = 4; i <= NF; i++) {
      v = $i; unit = (i < NF ? $(i + 1) : "")
      if (match(v, /^[0-9]+(\.[0-9]+)?/) && RLENGTH < length(v)) { unit = substr(v, RLENGTH + 1); v = substr(v, 1, RLENGTH) }
      else if (v !~ /^[0-9]+(\.[0-9]+)?$/) continue
      if (unit in u) { print v * u[unit]; exit } } }' || true
}

# Per-thread CPU over the ceiling window as "% of one core": a thread's summed jdk.ThreadCPULoad (a
# fraction of the JVM's effective CPUs) over the window's sampling periods, times that CPU count, so a
# thread alive for part of the window is not averaged over its own samples only. Grouped by name.
# A sample covers the period before it, which can straddle a rung edge (15 s rung + 5 s gap is exactly
# two 10 s periods, so the phase never moves). "Peak in-rung" divides each sample by the share of its
# period inside a rung (sweep-rungs.json), assuming the thread idles in the gaps, and the flag reads it.
# jdk.CPULoad's jvmUser/jvmSystem are fractions of the HOST's hardware threads, not the container's.
thread_cpu_table() { # work_dir jfr_rel
  local rungs='[[]]' sched=none events period
  if [ -e "$1/sweep-rungs.json" ]; then
    sched=bad
    if rungs="$(jq -c -s -e 'select(length == 1 and (.[0] | type == "array" and length > 0 and all(.[];
                 (.start_epoch_ms | type) == "number" and (.end_epoch_ms | type) == "number")))' "$1/sweep-rungs.json" 2>/dev/null)"
    then sched=ok; else rungs='[[]]'; fi
  fi
  if ! events="$(jfr_tool "$1" print --json --events jdk.ThreadCPULoad,jdk.CPULoad,jdk.ContainerConfiguration,jdk.CPUInformation "/w/$2")" \
     || [ -z "$events" ]; then echo "(per-thread CPU unavailable: jfr print failed)"; return 0; fi
  # ceiling.jfr from an older harness has no settings events; the load recording is the same JVM's.
  period="$(thread_cpu_period "$1" "$2")"
  if [ -z "$period" ] && [ -s "$1/sut/load.jfr" ]; then period="$(thread_cpu_period "$1" sut/load.jfr)"; fi
  [[ "$period" =~ ^[0-9]+(\.[0-9]+)?$ ]] || period=null
  jq -r --argjson top "$CEILING_TOP_THREADS" --argjson hot "$HOT_THREAD_PCT" --argjson rungs "$rungs" \
        --argjson setp "$period" --arg sched "$sched" '
      def at: capture("^(?<s>[^.Z]+)(\\.(?<f>[0-9]+))?Z$") | ((.s + "Z") | fromdate) + (("0." + (.f // "0")) | tonumber);
      def inrung($t; $p): [$rungs[0][] | ([$t, .end_epoch_ms / 1000] | min) - ([$t - $p, .start_epoch_ms / 1000] | max)
                           | select(. > 0)] | add // 0;
      def internal: test("^C[12] CompilerThread[0-9]+$|^JFR |^Service Thread$|^Monitor Deflation Thread$|^Common-Cleaner$|^Signal Dispatcher$|^Attach Listener$");
      # Scaled past a full core by more than tail work at a rung end (5% is ~0.25 s at share 0.5): the
      # thread also worked between rungs, so its in-rung load is only bounded, from below by assuming
      # it ran flat out there: (l - (1 - f)) / f.
      def broken: . != null and round > 105;
      [.recording.events[]] as $ev
      | ([$ev[] | select(.type == "jdk.ContainerConfiguration") | .values.effectiveCpuCount] | max) as $cc
      | ([$ev[] | select(.type == "jdk.CPUInformation") | .values.hwThreads] | max) as $hw
      | ($cc // $hw) as $cpus
      | [$ev[] | select(.type == "jdk.ThreadCPULoad")] as $tl
      # One batch per emission (samples under 1 s apart; the period is over 1 s). A thread that exits
      # emits a lone off-cadence sample, so only batches of two or more samples count as periods.
      | ([$tl[] | .values.startTime | at] | sort
         | reduce .[] as $x ([]; if length > 0 and $x - .[-1].last <= 1 then .[-1].last = $x | .[-1].n += 1
                                 else . + [{at: $x, last: $x, n: 1}] end)
         | [.[] | select(.n >= 2)] as $multi | (if ($multi | length) > 0 then $multi else . end) | map(.at)) as $cand
      # The recorded period when it is usable (over 1 s, the batching gap), else the median batch gap.
      | (if $setp == null then [range(1; $cand | length) | $cand[.] - $cand[. - 1]] | sort | (if length == 0 then null else .[length / 2 | floor] end)
         elif $setp > 1 then $setp else null end) as $p
      # A batch counts as a period only about $p after the last counted one, so off-cadence batches do not.
      | (if $p == null then $cand else reduce $cand[] as $x ([]; if length == 0 or $x - .[-1] >= $p - 1 then . + [$x] else . end) end) as $batches
      | ($batches | length) as $periods
      | (($rungs[0] | length) > 0 and $p != null) as $scaled
      | ([$ev[] | select(.type == "jdk.CPULoad") | ((.values.jvmUser // 0) + (.values.jvmSystem // 0))]
         | if length == 0 then null else add / length end) as $jvm
      | if $cpus == null or ($tl | length) == 0 then "(no jdk.ThreadCPULoad events or no CPU count in the window)"
        else ([$tl[] | {t: (.values.eventThread.javaName // .values.eventThread.osName // "?"),
                        l: ((.values.user // 0) + (.values.system // 0)), at: (.values.startTime | at)}]
              | group_by(.t) | map({t: .[0].t, n: length, pct: ((map(.l) | add) / $periods * $cpus * 100),
                  peak: ((map(.l) | max) * $cpus * 100),
                  in: (if $scaled then [.[] | (inrung(.at; $p) / $p) as $f | select($f >= 0.5) | {s: (.l * $cpus / $f), f: $f, c: (.l * $cpus)}] else [] end)})
              | map(. + {inrung: (if (.in | length) == 0 then null else .in | map(.s) | max * 100 end),
                         lo: (.in | map((.c - (1 - .f)) / .f | [., 0] | max | [., 1] | min) | max // 0 | . * 100 | round)} | del(.in))
              | sort_by(-.pct)) as $rows
        # Each application thread is judged on what its samples support: a scaled in-rung peak, a lower
        # bound when it also worked between rungs, or its raw peak, which flags only when no thread has
        # an in-rung figure (otherwise its samples sat mostly outside the rungs the others ran in).
        | [$rows[] | select(.t | internal | not)
           | . + (if .inrung == null then {kind: "raw", key: ([.peak, 100] | min | round)}
                  elif .inrung | broken then {kind: "broken", key: .lo}
                  else {kind: "scaled", key: ([.inrung, 100] | min | round)} end)] as $app
        | ([$app[] | select(.key >= $hot and (.kind != "raw" or all($app[]; .kind == "raw")))] | max_by([.key, .peak])) as $f
        | ([$app[] | select(.kind == "scaled")] | max_by([.key, .inrung])) as $s
        | ([$app[] | select(.kind != "scaled")] | sort_by(-.peak)) as $o
        | (if $sched == "none" then "No rung schedule in the bundle"
           elif $sched == "bad" then "The rung schedule in the bundle (sweep-rungs.json) is empty or malformed"
           elif $p == null then "The sampling period could not be told"
           else "No application thread had a sample at least half inside a rung" end) as $why
        | "Java threads (\($rows | length)): **\($rows | map(.pct) | add | round)% of one core**; whole JVM incl. GC threads (jdk.CPULoad): **\(if $jvm == null or $hw == null then "n/a" else ($jvm * $hw * 100 | round | tostring) + "%" end) of one core**; \($cpus) effective CPUs (\($cc | if . == null then "host hardware threads" else "as the container sees them" end)), \($periods) sampling periods.\n",
          "| Thread | % of one core | Peak period | Peak in-rung | Samples |", "|---|---:|---:|---:|---:|",
          ($rows[:$top][] | "| \(.t) | \(.pct * 10 | round / 10) | \(.peak * 10 | round / 10) | \(if .inrung == null then "n/a" elif .inrung | broken then (if .lo >= 100 then "≥100" else "\(.lo)–100" end) else [.inrung, 100] | min | . * 10 | round / 10 end) | \(.n) |"),
          (if any($rows[:$top][]; (.inrung | broken) and .lo < 100) then "\nA range in Peak in-rung: scaled to its in-rung part, that period passed a full core by more than tail work at a rung end allows, so the thread also worked between rungs (assumption broken); the lower figure assumes it ran flat out there." else empty end),
          "",
          (if ($app | length) == 0 then "(no application threads to flag; JVM-internal threads such as compiler and JFR threads are not flagged)"
           elif $f != null then
             (if $f.kind == "scaled"
              then ":warning: **\($f.t)** reached **\($f.key)% of one core** while a rung ran (its busiest period, scaled to the part inside a rung; flagged at \($hot)%): one thread was close to a full core, so it may cap throughput."
              elif $f.kind == "broken"
              then ":warning: **\($f.t)** reached at least **\($f.key)% of one core** while a rung ran (its busiest period, bounded assuming it ran flat out between rungs, where it also worked; flagged at \($hot)%): one thread was close to a full core, so it may cap throughput."
              else ":warning: **\($f.t)** reached **\($f.key)% of one core** in its busiest sampling period (flagged at \($hot)%): one thread was close to a full core, so it may cap throughput." end)
           elif $s == null and all($o[]; .kind == "raw") then
             "Busiest sampling period: \($o[0].t) at \($o[0].key)% of one core (flagged at \($hot)%). \($why), so periods are not scaled: one that straddles a rung edge reads as little as ~75% of the in-rung load, and this is not proof that no thread was near a full core."
           else
             (if $s == null then "No application thread had a scaled in-rung figure (flagged at \($hot)%)"
              else "Busiest in-rung period: \($s.t) at \($s.key)% of one core (its busiest period, scaled to the part inside a rung; flagged at \($hot)%)" end)
             + (if ($o | length) == 0 then ": no application thread was near a full core."
                else ". Not judged on a scaled figure: "
                  + ([$o[:3][] | if .kind == "broken"
                       then "\(.t) (worked between rungs too, so its busiest in-rung period was \(.key)–100% of one core; raw peak \(.peak | round)%)"
                       else "\(.t) (no sample at least half inside a rung; raw peak \(.peak | round)%)" end] | join("; "))
                  + (if ($o | length) > 3 then "; and \(($o | length) - 3) more" else "" end)
                  + ". This is not proof that no thread was near a full core." end)
           end)
        end' <<<"$events" 2>/dev/null || echo "(per-thread CPU unavailable: the events could not be read)"
}

# Top methods per thread role over the ceiling window, from one `jfr print --json --stack-depth 1`
# dump. CPU-time samples when the recording has them (they count native socket time, which
# execution samples miss), else execution samples. A role is the thread name minus its number.
role_hot_methods() { # events_json
  [ -s "$1" ] || { echo "(per-role hot methods unavailable: the sample events could not be read)"; return 0; }
  jq -r --argjson roles "$CEILING_TOP_ROLES" --argjson rows "$CEILING_ROLE_METHODS" '
    def short: if length > 90 then .[:87] + "..." else . end;
    [.recording.events[] | select(.type == "jdk.CPUTimeSample" and .values.failed != true)] as $cpu
    | (if ($cpu | length) > 0 then {k: "CPU-time samples (on-CPU time, native frames included)", s: $cpu}
       else {k: "execution samples (Java frames only)", s: [.recording.events[] | select(.type == "jdk.ExecutionSample")]} end) as $src
    | [$src.s[] | select(.values.stackTrace.frames[0] != null)
       | {role: ((.values.eventThread // .values.sampledThread // {}) | (.javaName // .osName // "?") | sub("[0-9]+$"; "")),
          m: (.values.stackTrace.frames[0].method | ((.type.name // "?") | gsub("/"; ".")) + "." + (.name // "?") | short)}] as $s
    | ($s | length) as $n
    | if $n == 0 then "(no samples with a stack trace in the window)"
      else "Top \($rows) methods for each of the busiest \($roles) thread roles, from \($n) \($src.k); a role is the thread name without its trailing number.\n",
        "| Thread role | Method | % of role | Samples |", "|---|---|---:|---:|",
        ($s | group_by(.role)
            | map({role: .[0].role, n: length, ms: (group_by(.m) | map({m: .[0].m, n: length}) | sort_by(-.n))})
            | sort_by(-.n) | .[:$roles][]
            | "| **\(.role)** (\(.n * 1000 / $n | round / 10)% of all samples) | | | \(.n) |",
              (.n as $rn | .ms[:$rows][] | "| | `\(.m)` | \(.n * 1000 / $rn | round / 10) | \(.n) |"))
      end' "$1" 2>/dev/null || echo "(per-role hot methods unavailable: the sample events could not be read)"
}

# The jdk.JavaMonitorEnter threshold the recording actually ran with ("1 ms", "disabled", or empty).
# JDK 25 labels the event "Java Monitor Blocked" in the active-settings view.
monitor_threshold() { # work_dir jfr_rel
  jfr_tool "$1" view --width 200 active-settings "/w/$2" \
    | awk '/^Java Monitor Blocked / { if ($4 == "true") print $5 " " $6; else if ($4 == "false") print "disabled"; exit }' || true
}

# Monitor waits over the threshold in the window: a count is meaningful only beside the threshold.
monitor_enter_summary() { # events_json threshold
  [ -s "$1" ] || { echo "- \`jdk.JavaMonitorEnter\`: unavailable (the events could not be read)"; return 0; }
  jq -r --arg t "${2:-}" '
    def secs: . as $d | ([$d | capture("(?<v>[0-9.]+)H") | .v | tonumber * 3600] + [$d | capture("(?<v>[0-9.]+)M") | .v | tonumber * 60]
                         + [$d | capture("(?<v>[0-9.]+)S") | .v | tonumber]) | add // 0;
    [.recording.events[] | select(.type == "jdk.JavaMonitorEnter")
     | {d: (.values.duration | secs), c: ((.values.monitorClass.name // "?") | gsub("/"; ".")),
        r: ((.values.eventThread.javaName // "?") | sub("[0-9]+$"; ""))}] as $e
    | "- `jdk.JavaMonitorEnter` events"
      + (if $t == "" then " (no event settings in the recording, so a zero cannot be told from a disabled event)"
         elif $t == "disabled" then " (the event was **disabled** in this recording, so a zero means nothing)"
         else " (a thread blocked on a monitor for at least the recorded threshold, **\($t)**)" end)
      + ": **\($e | length)**"
      + (if ($e | length) == 0 then "" else
         ", longest \(($e | map(.d) | max) * 1000 | . * 100 | round / 100) ms, total \(($e | map(.d) | add) * 1000 | round) ms; by monitor class: "
         + ($e | group_by(.c) | map({c: .[0].c, n: length}) | sort_by(-.n) | .[:3] | map("`\(.c)` ×\(.n)") | join(", "))
         + "; by thread role: "
         + ($e | group_by(.r) | map({r: .[0].r, n: length}) | sort_by(-.n) | .[:3] | map("\(.r) ×\(.n)") | join(", ")) end)' "$1" 2>/dev/null \
    || echo "- \`jdk.JavaMonitorEnter\`: unavailable (the events could not be read)"
}

# GC cycles in the window by collector. Under ZGC the cycle time is concurrent work; only the pause
# columns stopped application threads.
gc_cycle_summary() { # events_json
  [ -s "$1" ] || { echo "(GC cycle summary unavailable: the events could not be read)"; return 0; }
  jq -r '
    def secs: . as $d | ([$d | capture("(?<v>[0-9.]+)H") | .v | tonumber * 3600] + [$d | capture("(?<v>[0-9.]+)M") | .v | tonumber * 60]
                         + [$d | capture("(?<v>[0-9.]+)S") | .v | tonumber]) | add // 0;
    def ms: . * 1000 | if . >= 100 then round else . * 1000 | round / 1000 end;
    [.recording.events[] | select(.type == "jdk.GarbageCollection")
     | {name: (.values.name // "?"), cause: (.values.cause // "?"), d: (.values.duration | secs),
        p: ((.values.sumOfPauses // "PT0S") | secs), lp: ((.values.longestPause // "PT0S") | secs)}] as $g
    | if ($g | length) == 0 then "(no jdk.GarbageCollection events in the window)"
      else "| Collector | Cycles | Cycle time total (ms) | Longest cycle (ms) | Pauses total (ms) | Longest pause (ms) | Causes |",
        "|---|---:|---:|---:|---:|---:|---|",
        ($g | group_by(.name) | sort_by(-length)[]
         | "| \(.[0].name) | \(length) | \(map(.d) | add | ms) | \(map(.d) | max | ms) | \(map(.p) | add | ms) | \(map(.lp) | max | ms) | "
           + (group_by(.cause) | map({c: .[0].cause, n: length}) | sort_by(-.n) | .[:3] | map("\(.c) ×\(.n)") | join(", ")) + " |"),
        "", "Cycle time is the collector'"'"'s whole cycle (concurrent under ZGC); only the pause columns stopped application threads."
      end' "$1" 2>/dev/null || echo "(GC cycle summary unavailable: the events could not be read)"
}

jfr_view_block() { # work_dir view rows jfr_rel
  echo "#### $2"
  echo '```'
  jfr_tool "$1" view --width 120 "$2" "/w/$4" | awk -v n="$3" 'NF && ++k <= n' || true
  echo '```'
}

# The CPU / lock / GC / VM-operation / allocation profile of the ceiling window alone (sut/ceiling.jfr,
# cut to the knee rung .. top rung by perf-test-run.sh), which the whole-load recording dilutes with
# the idle and low rungs. Every table is row-bounded. Returns 1 (a one-line note, never a step
# failure) when the window is missing or below its floor, or `jfr` cannot run or read it.
ceiling_profile() { # work_dir
  local w="$1" meta secs summary execs cpu_samples alloc_samples view rows threshold monitors
  local jfr_rel="sut/ceiling.jfr"
  echo "### ceiling window (CPU, locks, GC, VM operations)"
  meta="$(jq -c '.' "$w/ceiling-window.json" 2>/dev/null || true)"
  if [ -z "$meta" ] || [ "$(jq -r '.jfr // empty' <<<"$meta")" != "$jfr_rel" ] || [ ! -s "$w/$jfr_rel" ]; then
    echo "No ceiling recording in the bundle$(jq -r '(.error // empty) | " (" + . + ")"' <<<"${meta:-null}" 2>/dev/null)."
    return 1
  fi
  if ! command -v docker >/dev/null 2>&1; then
    echo "Docker is unavailable on this agent, so \`jfr\` could not read \`$jfr_rel\`; run \`jfr view hot-methods $jfr_rel\` on the bundle."
    return 1
  fi
  if [ -e "$w/.jfr-timed-out" ]; then
    echo "An earlier \`jfr\` call timed out (${JFR_VIEW_DEADLINE_S} s) or was killed, so the ceiling views were skipped; read them from the bundle."
    return 1
  fi
  if ! jfr_tool "$w" version >/dev/null; then
    echo "The \`jfr\` tool could not run (image \`${JDK_IMAGE%%@*}\`), so the ceiling views were skipped."
    return 1
  fi
  if ! summary="$(jfr_tool "$w" summary "/w/$jfr_rel")" || ! grep -q '^ *Duration:' <<<"$summary"; then
    echo "\`jfr summary\` could not read \`$jfr_rel\` (corrupt or truncated recording), so the ceiling views were skipped."
    return 1
  fi
  secs="$(jq -r '((.end_epoch_ms - .begin_epoch_ms) / 1000 | floor)' <<<"$meta")"
  execs="$(printf '%s\n' "$summary" | awk '$1=="jdk.ExecutionSample"{print $2; exit}')"
  cpu_samples="$(printf '%s\n' "$summary" | awk '$1=="jdk.CPUTimeSample"{print $2; exit}')"
  alloc_samples="$(printf '%s\n' "$summary" | awk '$1=="jdk.ObjectAllocationSample"{print $2; exit}')"
  case "$execs" in ''|*[!0-9]*) execs=0 ;; esac
  case "$cpu_samples" in ''|*[!0-9]*) cpu_samples=0 ;; esac
  case "$alloc_samples" in ''|*[!0-9]*) alloc_samples=0 ;; esac
  jq -r '"Rungs \(.first_rung_rps)–\(.last_rung_rps) rps (knee: saturation_rps \(.saturation_rps)), \(.begin_iso) to \(.end_iso)"' <<<"$meta"
  echo "— ${secs} s including the gaps between rungs, ${execs} execution samples, ${cpu_samples} CPU-time samples."
  if [ "${secs:-0}" -lt "$MIN_CEILING_S" ] || [ "$execs" -lt "$MIN_CEILING_EXEC_SAMPLES" ]; then
    echo
    echo "**Ceiling recording INVALID — not summarised** (floor: ${MIN_CEILING_S} s and ${MIN_CEILING_EXEC_SAMPLES} execution samples)."
    return 1
  fi
  echo
  echo "#### per-thread CPU"
  thread_cpu_table "$w" "$jfr_rel"
  echo
  jfr_tool "$w" print --json --stack-depth 1 \
    --events jdk.CPUTimeSample,jdk.ExecutionSample,jdk.JavaMonitorEnter,jdk.GarbageCollection \
    "/w/$jfr_rel" > "$w/ceiling-events.json" || : > "$w/ceiling-events.json"
  echo "#### hot methods by thread role"
  role_hot_methods "$w/ceiling-events.json"
  echo
  for view in hot-methods cpu-time-hot-methods cpu-time-statistics; do
    case "$view" in cpu-time-*) [ "$cpu_samples" -gt 0 ] || continue ;; esac
    case "$view" in *hot-methods) rows=22 ;; *) rows=16 ;; esac
    jfr_view_block "$w" "$view" "$rows" "$jfr_rel"
  done
  echo "#### monitor contention"
  # ceiling.jfr from an older harness has no settings events; the load recording is the same JVM's.
  threshold="$(monitor_threshold "$w" "$jfr_rel")"
  if [ -z "$threshold" ] && [ -s "$w/sut/load.jfr" ]; then threshold="$(monitor_threshold "$w" sut/load.jfr)"; fi
  monitor_enter_summary "$w/ceiling-events.json" "$threshold"
  monitors="$(jq '[.recording.events[] | select(.type == "jdk.JavaMonitorEnter")] | length' "$w/ceiling-events.json" 2>/dev/null || echo 0)"
  echo
  [ "${monitors:-0}" = 0 ] || jfr_view_block "$w" contention-by-site 16 "$jfr_rel"
  echo "#### GC cycles"
  gc_cycle_summary "$w/ceiling-events.json"
  echo
  for view in gc-pauses gc-concurrent-phases allocation-by-site latencies-by-type vm-operations native-methods exception-count; do
    case "$view" in allocation-by-site) [ "$alloc_samples" -gt 0 ] || continue ;; esac
    case "$view" in native-methods|gc-concurrent-phases) rows=12 ;; *) rows=16 ;; esac
    jfr_view_block "$w" "$view" "$rows" "$jfr_rel"
  done
  [ ! -e "$w/.jfr-timed-out" ] || echo "_A \`jfr\` call timed out (${JFR_VIEW_DEADLINE_S} s) or was killed, so the views after it are empty; read them from the bundle._"
  return 0
}

# Post the load-window figures, the retained-heap histogram, and the top allocation sites/classes
# from the SUT's load-window JFR dump inside the diagnostics bundle. Never fails the step.
emit_allocation_annotation() {
  local tgz="$REPO_ROOT/${ARTIFACT_PREFIX}perf-jvm-diagnostics.tgz"
  if [ ! -f "$tgz" ]; then
    annotate "warning" ":microscope: **Allocation profile ran (deep JFR), not baselined.** No \`${ARTIFACT_PREFIX}perf-jvm-diagnostics.tgz\` was produced this run, so no allocation summary could be extracted (see the step log for the packaging error)."
    return 0
  fi
  local work; work="$(mktemp -d "${TMPDIR:-/tmp}/allocprofile.XXXXXX")" || return 0
  # shellcheck disable=SC2064
  trap "rm -rf '$work'" RETURN
  tar xzf "$tgz" -C "$work" 2>/dev/null || true

  local out="$work/alloc.md" style="info" jfr="" source="" verdict="" repo
  {
    echo "### load window"
    load_window_figures "$work"
    echo
    echo "### live heap — last sample (what the heap RETAINS)"
    echo '```'
    if [ -s "$work/sut/live-heap-histogram.txt" ]; then
      awk '/^===== elapsed_s=/{buf=""} {buf = buf $0 "\n"} END{printf "%s", buf}' "$work/sut/live-heap-histogram.txt"
    else
      echo "(no live-heap histogram — no sample landed in the growth.js window, or the jcmd sidecar could not attach; see the step log)"
    fi
    echo '```'
    echo
  } > "$out"
  [ -s "$work/sut/live-heap-histogram.txt" ] || style="warning"

  # The load-window dump is the only recording that describes the load. Without it (e.g. the SUT
  # died first) the repository chunks are assembled instead; the floor below judges either.
  if [ "$(jq -r '.jfr // empty' "$work/load-window.json" 2>/dev/null)" = "sut/load.jfr" ] && [ -s "$work/sut/load.jfr" ]; then
    jfr="sut/load.jfr"; source="load-window dump"
  elif command -v docker >/dev/null 2>&1 && repo="$(largest_repo_dir "$work/sut/jfr-repo")" && [ -n "$repo" ]; then
    assemble_finished_chunks "$work" "$repo"
    [ -s "$work/assembled.jfr" ] && { jfr="assembled.jfr"; source="finished repository chunks (no load-window dump)"; }
  fi

  if [ -z "$jfr" ]; then
    style="warning"
    verdict="No SUT JFR recording in the bundle$(jq -r '.error // empty | " (" + . + ")"' "$work/load-window.json" 2>/dev/null) — allocation sites unavailable."
  elif ! command -v docker >/dev/null 2>&1; then
    verdict="Docker is unavailable on this agent, so \`jfr\` could not run. Run \`jfr view allocation-by-site $jfr\` on the bundle."
  else
    local summary duration samples
    summary="$(jfr_tool "$work" summary "/w/$jfr" || true)"
    duration="$(printf '%s\n' "$summary" | awk '/^ *Duration:/{print $2; exit}')"
    samples="$(printf '%s\n' "$summary" | awk '$1=="jdk.ObjectAllocationSample"{print $2; exit}')"
    case "$duration" in ''|*[!0-9]*) duration=0 ;; esac
    case "$samples" in ''|*[!0-9]*) samples=0 ;; esac
    if [ "$duration" -lt "$MIN_JFR_DURATION_S" ] || [ "$samples" -lt "$MIN_ALLOC_SAMPLES" ]; then
      style="warning"
      verdict="**Recording INVALID — not summarised.** The ${source} covers ${duration} s with ${samples} allocation samples (floor: ${MIN_JFR_DURATION_S} s and ${MIN_ALLOC_SAMPLES} samples), so allocation-by-site would describe too little of the load to be meaningful."
    else
      verdict="Allocation sites from the ${source}: ${duration} s, ${samples} allocation samples."
      local view
      for view in allocation-by-site allocation-by-class; do
        {
          echo "### ${view}"
          echo '```'
          jfr_tool "$work" view --width 120 "$view" "/w/$jfr" | head -24 || true
          echo '```'
          echo
        } >> "$out"
      done
    fi
    if [ "$jfr" = "sut/load.jfr" ]; then
      { echo "### live-heap histogram placement"; heap_inspection_placement "$work" "$jfr"; } >> "$out" || style="warning"
      echo >> "$out"
    fi
  fi

  ceiling_profile "$work" >> "$out" || style="warning"

  local header=":microscope: **Allocation profile — deep JFR run (NOT baselined).** Throughput this run is deliberately depressed by JFR/NMT/GC-logging, so its figures are excluded from the baseline. ${verdict} Bundle: \`${ARTIFACT_PREFIX}perf-jvm-diagnostics.tgz\`."
  annotate "$style" "$({ printf '%s\n\n' "$header"; cat "$out"; } | cap_annotation "$ANNOTATION_MAX_BYTES")"
}

echo "--- :microscope: allocation profile — deep JFR run (PERF_RUN_NAME=${RUN_NAME}); artifacts are prefixed and NOT baselined"

# Short + modest: a small sweep ladder and trimmed durations, deep diagnostics on, and
# the throughput-only auxiliary profiles off. Everything is overridable so a deeper
# investigation can widen the ladder without editing this file.
# The ladder climbs past the healthy ceiling in 8k steps so the ceiling profile (knee rung to the
# top, at least two rungs) covers the knee region rather than one rung at the top.
# The opt-in arms are forced off, NOT defaulted: this step inherits the build env, so a build
# that opts the clean run into one (e.g. PERF_SERVING_HW_MATRIX=true) would re-run it here.
rc=0
PERF_RUN_NAME="$RUN_NAME" \
PERF_JVM_DIAGNOSTICS=deep \
PERF_SERVER_MEMORY="${PERF_SERVER_MEMORY:-4g}" \
PERF_CLUSTERED=false \
PERF_PIPELINING=false \
PERF_FILE_BODY_AB=false \
PERF_DATAPLANE_AB=false \
PERF_SERVING_HW_MATRIX=false \
PERF_SERVING_RW_MULTIK6=false \
PERF_XL=false \
PERF_RUN_ARM='' \
PERF_SERVING_PERCORE=false \
PERF_SERVING_MULTIPROC=false \
PERF_LAPTOP_PARALLEL=false \
PERF_LARGE_HEAP_PROFILE=false \
PERF_WORKLOAD='' \
PERF_STEADY_RATE='' \
PERF_INFO_ARM="${PERF_INFO_ARM:-false}" \
PERF_LAPTOP_PROFILE="${PERF_LAPTOP_PROFILE:-false}" \
PERF_STREAMING="${PERF_STREAMING:-false}" \
PERF_PROXY_PROFILE="${PERF_PROXY_PROFILE:-false}" \
PERF_COVERAGE="${PERF_COVERAGE:-false}" \
K6_SWEEP_RATES="${K6_SWEEP_RATES:-8000,24000,40000,48000,56000,64000}" \
PERF_LIVE_HISTO_INTERVAL_S="${PERF_LIVE_HISTO_INTERVAL_S:-30}" \
K6_REG_DURATION="${K6_REG_DURATION:-45s}" \
K6_GROWTH_DURATION="${K6_GROWTH_DURATION:-2m}" \
  "$SCRIPT_DIR/perf-test-run.sh" || rc=$?

emit_allocation_annotation || true

# Preserve the harness exit code so a genuine fault is visible as a soft-fail. The
# step is soft_fail:true in perf-test-guard.sh, so a non-zero rc NEVER reds the build.
exit "$rc"
