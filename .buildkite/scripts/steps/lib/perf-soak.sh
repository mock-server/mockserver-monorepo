# shellcheck shell=bash
# Weekly-soak helpers for perf-test-soak.sh. None of them calls docker or curl, so
# .buildkite/scripts/test/perf-soak-test.sh runs each on fixtures.

# samples.csv: its header, and one row from a metrics scrape on stdin. Columns are only ever
# appended: readers index by position. A series the server does not export leaves its cell blank,
# except drops (absent = 0).
soak_sample_header() {
  echo "ts,requests_received,heap_bytes,dropped_log_events,threads,retained_entries,retained_bytes,evicted_log_entries"
}
soak_sample_row() { # ts < metrics_text
  awk -v ts="$1" '
    function n(v) { return v == "" ? "" : sprintf("%.0f", v) }
    $1 == "requests_received_count" {rr = $2}
    index($1, "jvm_memory_used_bytes{area=\"heap\"") == 1 {heap = $2}
    $1 == "mock_server_dropped_log_events_total" || index($1, "mock_server_dropped_log_events_total{") == 1 {dropped += $2}
    $1 == "jvm_threads_current" {threads = $2}
    $1 == "mock_server_event_log_retained_entries" {re = $2}
    $1 == "mock_server_event_log_retained_bytes" {rb = $2}
    $1 == "mock_server_evicted_log_entries_total" {ev = $2}
    END {printf "%s,%s,%s,%.0f,%s,%s,%s,%s\n", ts, n(rr), n(heap), dropped, n(threads), n(re), n(rb), n(ev)}'
}

# One series' value from metrics text on stdin, as an integer; empty when absent. Exact name match.
soak_metric_value() { # exact_series_name < metrics_text
  awk -v m="$1" '$1 == m && $2 != "" {printf "%.0f", $2 + 0; exit}' || true
}

# The JVM's resolved max heap in bytes (what -Xmx / MaxRAMPercentage and the container limit came to).
soak_heap_max_bytes() { # < metrics_text
  awk 'index($1, "jvm_memory_max_bytes{area=\"heap\"") == 1 && $2 != "" {printf "%.0f", $2 + 0; exit}' || true
}

# The end-of-load state from samples.csv, as JSON. soak.js resets the server in teardown, which
# zeroes requests_received and the retained gauges, so everything is read at the PEAK-received row
# (the last sample under load), never the literal last row. An unreadable cell is null, never 0.
soak_samples_json() { # samples_csv k6_start_epoch_s
  awk -F, -v start="${2:-}" '
    function j(v) { return v == "" ? "null" : sprintf("%.0f", v) }
    BEGIN {n = 0}
    NR > 1 {
      rr[n] = $2; hp[n] = $3; th[n] = $5; re[n] = $6; rb[n] = $7
      if ($2 != "" && $2 + 0 > maxrr) {maxrr = $2 + 0; P = n; seen = 1}
      if ($4 != "" && $4 + 0 > maxdr) maxdr = $4 + 0
      if ($8 != "") {evseen = 1; if ($8 + 0 > maxev) maxev = $8 + 0; if ($8 + 0 > 0 && firstev == "") firstev = $1}
      if (n == 0) hs = $3
      n++
    }
    END {
      low = ""; used = 0
      if (seen) for (i = (P > 4 ? P - 4 : 0); i <= P; i++) if (hp[i] + 0 > 0) {used++; if (low == "" || hp[i] + 0 < low) low = hp[i] + 0}
      elapsed = ""
      if (firstev != "" && start != "") {elapsed = firstev - start; if (elapsed < 0) elapsed = 0}
      printf "{\"rows\":%d,\"requests_received_total\":%s,\"heap_start_bytes\":%s,\"heap_end_bytes\":%s,", n, (seen ? j(maxrr) : "null"), j(hs), (seen ? j(hp[P]) : "null")
      printf "\"pre_teardown_heap_min_bytes\":%s,\"pre_teardown_heap_min_samples\":%d,", j(low), used
      printf "\"dropped_log_events\":%s,\"threads_end\":%s,", (n ? j(maxdr + 0) : "null"), (seen ? j(th[P]) : "null")
      printf "\"retained_entries_end\":%s,\"retained_bytes_end\":%s,", (seen ? j(re[P]) : "null"), (seen ? j(rb[P]) : "null")
      printf "\"evicted_log_entries\":%s,\"first_eviction_elapsed_s\":%s}\n", (evseen ? j(maxev + 0) : "null"), j(elapsed)
    }' "$1"
}

# Which event-log bound was binding at the end of the load, from the server's own counters. The log
# is `filled` only if the server evicted entries; the bound nearer its limit is the one evicting
# (a byte budget of 0 is disabled, so only the count can bind). binding is null when it cannot be
# told: no eviction counter, or no retained gauges.
soak_event_log_json() { # max_retained_entries max_retained_bytes samples_json
  jq -nc --arg me "$1" --arg mb "$2" --argjson s "$3" '
    def num: if . == "" then null else (tonumber? // null) end;
    def r4: if . == null then null else (. * 10000 | round) / 10000 end;
    ($me | num) as $me | ($mb | num) as $mb
    | $s.retained_entries_end as $re | $s.retained_bytes_end as $rb | $s.evicted_log_entries as $ev
    | (if $me != null and $me > 0 and $re != null then $re / $me else null end) as $cu
    | (if $mb != null and $mb > 0 and $rb != null then $rb / $mb else null end) as $bu
    | { max_retained_entries: $me, max_retained_bytes: $mb,
        retained_entries_end: $re, retained_bytes_end: $rb,
        count_utilisation: ($cu | r4), bytes_utilisation: ($bu | r4),
        evicted_log_entries: $ev, dropped_log_events: $s.dropped_log_events,
        filled: (if $ev == null then null else $ev > 0 end),
        binding: (if $ev == null then null
                  elif $ev == 0 then "neither"
                  elif $cu == null then null
                  elif $mb != null and $mb <= 0 then "count"
                  elif $bu == null then null
                  elif $cu >= $bu then "count" else "bytes" end),
        first_eviction_elapsed_s: $s.first_eviction_elapsed_s }'
}

# match | differs | absent | malformed | harness-unknown. An absent or malformed label fails the
# soak (it could not be tied to a commit). An image that trails the harness is measured and
# recorded as `differs`: the snapshot is only rebuilt for commits that change the server.
soak_revision_verdict() { # harness_commit image_revision
  local sha='^[0-9a-f]{40}$'
  if [ -z "$2" ]; then echo absent
  elif ! [[ "$2" =~ $sha ]]; then echo malformed
  elif ! [[ "$1" =~ $sha ]]; then echo harness-unknown
  elif [ "$1" = "$2" ]; then echo match
  else echo differs; fi
}

# Whether the image revision is an ancestor of the harness commit: true | false | unknown. Never
# fetches. A found ancestry holds even in a shallow checkout; a miss is only `false` when the
# checkout has its full history and the harness commit in it.
soak_revision_ancestry() { # repo_dir harness_commit image_revision
  local sha='^[0-9a-f]{40}$'
  if ! [[ "$2" =~ $sha ]] || ! [[ "$3" =~ $sha ]]; then echo unknown; return 0; fi
  if git -C "$1" merge-base --is-ancestor "$3" "$2" 2>/dev/null; then echo true; return 0; fi
  if [ "$(git -C "$1" rev-parse --is-shallow-repository 2>/dev/null || true)" = false ] \
     && git -C "$1" cat-file -e "$2^{commit}" 2>/dev/null; then echo false; else echo unknown; fi
}

# Lines in the server's stdout/stderr that name java.lang.OutOfMemoryError; 0 for a missing file.
# The class name is matched whole: the JVM's "Picked up JAVA_TOOL_OPTIONS" line names the
# ExitOnOutOfMemoryError flag on every start. Netty's io.netty.util.internal.OutOfDirectMemoryError
# is therefore not counted.
soak_oom_lines() { # server_log
  local n=0
  [ -f "$1" ] && n="$(grep -c 'java\.lang\.OutOfMemoryError' "$1" || true)"
  echo "${n:-0}"
}

# The Buildkite annotation for a completed soak, from perf-soak.json.
soak_annotation_md() { # perf_soak_json
  jq -r '
    def gib: if . == null then "unknown" else "\(. / 1073741824 * 100 | round / 100) GiB" end;
    def arm($name; $a; $d):
      "| \($name) | \($a.samples) | \($a.p50_ms) | \($a.p95_ms) | \($a.p99_ms) | " +
      (if $d.computed then "\($d.p99.ratio) | \($d.p99.ratio_worst) | \($d.p99.reference_over_quietest)"
       else "not computed | not computed | not computed" end) + " | \($a.error_rate) |\n";
    "**Weekly soak** (\(.config.image_digest), revision \(.config.image_revision[0:10]), " +
    "container memory limit \(.config.container_memory_limit_bytes | gib), max heap \(.config.heap_max_bytes | gib), " +
    "log level \(.config.log_level), k6_exit=\(.k6_exit))\n\n" +
    ( if .config.revision_check == "match" then ""
      else ":information_source: Image revision \(.config.image_revision) is not the harness commit \(.config.harness_commit) " +
           "(revision check: \(.config.revision_check); image revision an ancestor of the harness commit: \(.config.image_revision_ancestor_of_harness)).\n\n"
      end ) +
    "| arm | samples | p50 ms | p95 ms | p99 ms | p99 drift (late/reference) | worst late / quietest | reference / quietest | err |\n" +
    "|---|---|---|---|---|---|---|---|---|\n" +
    arm("match"; .soak.match; .drift.match) +
    arm("verify (10b)"; .soak.verify; .drift.verify) +
    arm("retrieve (10b)"; .soak.retrieve; .drift.retrieve) + "\n" +
    "Match gates p95 < \(.soak.gates.match_p95_ms) ms, p99 < \(.soak.gates.match_p99_ms) ms (the defaults are derived from eight counting soaks; " +
    "docs/code/performance-measurement.md, Absolute gates).\n\n" +
    ( if .drift_check.exceeded == true
      then ":warning: **Match p99 drift above its bound:** worst late / quietest \(.drift_check.value) is at or above \(.drift_check.bound). " +
           "Notify-only: read the per-window series in perf-soak.json before calling it a regression (a latency clump that starts late reads this way on unchanged code).\n\n"
      elif .drift_check.exceeded == false
      then "Match p99 drift (worst late / quietest) \(.drift_check.value), under its notify-only bound \(.drift_check.bound).\n\n"
      else "" end ) +
    ":card_file_box: **Event log** bound by its **\(.event_log.binding)** limit: \(.event_log.retained_entries_end) of \(.event_log.max_retained_entries) entries " +
    "and \(.event_log.retained_bytes_end) of \(.event_log.max_retained_bytes) bytes retained at the end of the load, " +
    "\(.event_log.evicted_log_entries) entries evicted, first eviction seen \(.event_log.first_eviction_elapsed_s) s in " +
    "(warm-up ends at \(.soak.warmup_s) s).\n\n" +
    ( if .ring.dropped_log_events == 0
      then ":lock: 0 dropped log events across \(.ring.requests_received_total) requests received. "
      else ":warning: **\(.ring.dropped_log_events) dropped log events** (\(.ring.requests_received_total) received) — the disruptor could not keep up; lower the log level (raising ringBufferSize only absorbs bursts). "
      end ) +
    "Used heap \(.ring.heap_start_bytes)→\(.ring.heap_end_bytes) bytes; lowest of the last \(.ring.pre_teardown_heap_min_samples) samples before teardown \(.ring.pre_teardown_heap_min_bytes) bytes " +
    "(a point on the GC saw-tooth; the GC log artifact has the heap after each collection).\n\n" +
    "_Drift columns: **late/reference** is the median p99 of the last \(.drift.match.late_windows | length) windows over the median of the first \(.drift.match.reference_windows | length) after the warm-up. " +
    "**Worst late / quietest** is the slowest late window over the quietest window before them, so a reference still inflated by warm-up cannot hide a late rise. " +
    "**Reference / quietest** near 1 means the reference had settled. The per-window series is in perf-soak.json. " +
    "verify and retrieve run at 1/s, so one window holds few samples and their p99 can swing on a single pause. Drift is notify-only._\n\n" +
    "_Only the match p95/p99, error and check rates are gated; no soak metric is fed to the daily baseline compare._"
  ' "$1"
}
