# perf-website-figures.jq
# ---------------------------------------------------------------------------
# Transform ONE self-describing perf run (schema_version >= 2, produced by
# .buildkite/scripts/steps/perf-test-run.sh) into the PUBLISHABLE figures the
# website renders from jekyll-www.mock-server.com/_data/perf_figures.json.
#
# This filter decides WHAT reaches the public page. It deliberately publishes
# ONLY the two families the performance programme has cleared as honest,
# absolute, customer-facing claims:
#
#   * the throughput/latency knee curve, with healthy_ceiling_rps as the
#     headline and peak_achieved_rps beside it EXPLICITLY labelled degraded
#     (docs/plans/performance-programme.md, Finding 1); and
#   * per-behaviour percentiles from the FIXED regression.js (Finding 3,
#     resolved 2026-09-16) — emitted only when the run actually carries them.
#
# It NEVER emits the internal-only regression detectors the plan marks
# "gated internally, never published": the JMH absolute backstops, growth/soak
# live-set slope and absolute, event-log verification cost, forward-pool guard,
# AppCDS boolean, leak gate, the streaming match-A/B ratio (an A/B tripwire
# measured against a DELIBERATELY constrained 1-CPU SUT — misleading as an
# absolute public figure), the startup median-of-9, and the baseline-freshness
# assertion. Those metrics exist to move under regression, not to be quoted.
#
# healthy_ceiling_rps is computed here to Finding 1's definition — the highest
# offered rung where achieved stayed within (1 - keep) of offered, errors were
# zero, AND p50 stayed within lat_mult x the flat-region p50 — rather than
# trusting the run's own saturation_rps, so a reader can see exactly which rung
# it is and that latency was still flat there.
#
# Args (all via --argjson / --arg):
#   $now       ISO-8601 timestamp to stamp as published_utc
#   $lat_mult  latency multiple defining "still flat" (default caller: 3)
#   $keep      achieved/offered floor for a healthy rung (default caller: 0.95)
#
# The caller (perf-website-publish.sh) is responsible for REFUSING to publish a
# run whose sweep is missing/invalid (headline null); this filter only shapes a
# run it is given. behaviours is null when the run carries none — the page
# renders the behaviours section only when it is present.

def round2: (. * 100 | round) / 100;
def round3: (. * 1000 | round) / 1000;
# Thousands separators for the display strings the page renders (Jekyll has no
# number-delimiter filter). Integer part only — latencies are small and unformatted.
def commafy: (. // 0 | floor | tostring) | gsub("(?<=\\d)(?=(\\d{3})+$)"; ",");

# Rungs the RUN ITSELF judged rig-valid, keyed by offered_rps. A rung excluded by
# derive_saturation measured the load generator, not the server - a k6 scheduling stall
# with an idle VU pool - so publishing it states a server figure the run declined to
# stand behind. Everything below is computed over rig-valid rungs only, which matters
# for the headline as much as the table: peak is a max over these, so an excluded rung
# can no longer become the published peak. An artifact with no saturation.ladder (an
# older producer) carries no rig-validity to filter on, so all rungs are kept.
(.sweep.points // []) as $pts
| ((.saturation.ladder // []) | map(select(.rig_valid == true) | .offered_rps)) as $rv_offered
| (((.saturation.ladder // []) | length) == 0) as $no_rig_info
| ($pts | map(select(type == "object" and (.offered_rps != null) and (.achieved_rps != null)))
        | map(select($no_rig_info or (.offered_rps | IN($rv_offered[]))))
        | sort_by(.offered_rps)) as $s
# flat-region p50 = median p50 of the lowest (up to) four rungs, the part of the
# curve before any knee. Used only to decide where latency stops being flat.
| ([ $s[0:4][] | .p50_ms ] | map(select(. != null)) | sort) as $flat
| (($flat | length) as $n
   | if $n == 0 then null
     elif ($n % 2) == 1 then $flat[($n / 2 | floor)]
     else (($flat[$n/2 - 1] + $flat[$n/2]) / 2) end) as $flat_p50
| (if $flat_p50 == null then null else ($flat_p50 * $lat_mult) end) as $lat_thresh
# healthy rungs: kept up, no errors, latency still flat.
| [ $s[]
    | select(.offered_rps > 0
             and (.error_rate // 0) == 0
             and .achieved_rps >= ($keep * .offered_rps)
             and ($lat_thresh != null) and (.p50_ms != null) and (.p50_ms <= $lat_thresh)) ] as $healthy
| ($healthy | max_by(.offered_rps)) as $hc
# peak ACHIEVED rung across the whole ladder — the top of the overload curve.
| ($s | max_by(.achieved_rps)) as $pk
| .config as $c
| .agent as $a
# POST-FIX gate for per-behaviour percentiles. schema_version (item 0) and the
# regression.js tail fix (Finding 3, 2026-09-16) are INDEPENDENT axes — a run can
# be schema>=2 yet pre-fix (perf-test-run.sh notes the same), and a pre-fix run's
# p95 is a NON-NULL ~1014 ms client-side rig artefact. So a p95-presence check is
# NOT a fix check. Publish behaviours only when the run is provably post-fix:
#   * an explicit producer stamp `config.regression_js_fixed == true` (preferred,
#     forward-compatible for when the producer stamps it), OR
#   * a date floor on run_timestamp_utc >= $fix_date (the fix's merge date), which
#     is a sound floor because the stored timestamp is same-format ISO-8601 UTC so
#     lexicographic >= is chronological >=. Chosen because stamping the producer is
#     out of this unit's lane; the stamp path is honoured first for when it lands.
# A bare lexicographic >= fails OPEN on a malformed timestamp: "today" beats
#     "2026-..." because "t" > "2". Anchor the format first so an un-dateable run
#     is withheld rather than published.
| (($c.regression_js_fixed == true)
   or (((.timestamp_utc // "") | test("^[0-9]{4}-[0-9]{2}-[0-9]{2}"))
       and ((.timestamp_utc // "") >= $fix_date))) as $postfix
| ((.behaviours // {}) | to_entries | map(select(.value.p95_ms != null))) as $beh_present
| {
    # ---- provenance: every published figure carries what it was measured on --
    source: {
      published_utc: $now,
      run_timestamp_utc: .timestamp_utc,
      build_number: (.build_number // null),
      build_url: (.build_url // null),
      commit: ((.commit // "") | .[0:10]),
      mockserver_version: ($c.mockserver_version // null),
      instance_type: ($a.instance_type // null),
      server_cpus: ($a.server_cpus // null),
      gc: ($c.gc // null),
      heap_max_mib: (if ($c.heap_max_bytes // null) == null then null
                     else (($c.heap_max_bytes) / 1048576 | floor) end),
      jdk: ($c.jdk // null),
      log_level: ($c.log_level // null),
      disable_system_out: ($c.disable_system_out // null),
      image_digest: ($c.image_digest // null),
      # A self-describing run resolves these from the running JVM (item 0), so a
      # published provenance line is checkable rather than asserted.
      config_recorded: (($c // null) != null),
      # The CI perf profile reduces logging below the shipped INFO default; the
      # page must say so rather than imply default-configuration figures.
      note: "Measured on the pinned CI perf host; the load generator runs on separate cores. Logging is reduced below the shipped INFO default for measurement (see log_level)."
    },
    # ---- headline: healthy ceiling FIRST, degraded peak clearly labelled ------
    headline: (if $hc == null then null else {
      healthy_ceiling_rps: ($hc.offered_rps),
      healthy_ceiling_rps_display: ($hc.offered_rps | commafy),
      healthy_ceiling_achieved_rps: ($hc.achieved_rps | round2),
      healthy_ceiling_p50_ms: ($hc.p50_ms | round3),
      healthy_ceiling_p95_ms: ($hc.p95_ms | round3),
      peak_achieved_rps: (if $pk == null then null else ($pk.achieved_rps | round2) end),
      peak_achieved_rps_display: (if $pk == null then null else ($pk.achieved_rps | commafy) end),
      peak_offered_rps: (if $pk == null then null else ($pk.offered_rps) end),
      peak_p50_ms: (if $pk == null then null else ($pk.p50_ms | round3) end),
      peak_p95_ms: (if $pk == null then null else ($pk.p95_ms | round3) end),
      server_cores: ($a.server_cpus // null)
    } end),
    # ---- the full ladder, each rung flagged degraded past the healthy ceiling -
    throughput_ladder: [ $s[]
      | {
          offered_rps: .offered_rps,
          offered_display: (.offered_rps | commafy),
          achieved_rps: (.achieved_rps | round2),
          achieved_display: (.achieved_rps | commafy),
          p50_ms: (.p50_ms | round3),
          p95_ms: (.p95_ms | round3),
          p99_ms: (.p99_ms | round3),
          error_rate: (.error_rate // 0),
          error_pct: ((.error_rate // 0) * 100 | round),
          degraded: (($hc != null) and (.offered_rps > $hc.offered_rps))
        } ],
    # ---- per-behaviour percentiles — published ONLY from a post-fix run --------
    # null unless $postfix (see the gate above). A p95-presence check alone would
    # publish the pre-fix ~1014 ms artefact; the gate is the fix marker, not p95.
    behaviours: (if ($postfix and (($beh_present | length) > 0))
      then ($beh_present | map({
              key: .key,
              op: (.key | sub("_(https_h2|http)$"; "")),
              proto: (if (.key | endswith("_https_h2")) then "HTTPS + HTTP/2" else "HTTP/1.1" end),
              p50_ms: (.value.p50_ms | round3),
              p95_ms: (.value.p95_ms | round3),
              p99_ms: (.value.p99_ms | round3),
              throughput_rps: (.value.throughput_rps | round2)
            }))
      else null end),
    # Documentary keys the page and reviewers rely on — EMITTED here so an
    # auto-refresh never silently drops what the seed data file carries.
    behaviours_status: (if ($postfix | not)
      then "withheld: this run predates the regression.js tail fix (performance programme Finding 3, resolved 2026-09-16), so its per-behaviour p95/p99 are a client-side rig artefact, not server latency. Per-behaviour percentiles are published only from a post-fix run."
      elif (($beh_present | length) == 0)
      then "not measured this run (no behaviour arm carried a p95)"
      else "published from a post-fix regression.js run" end),
    withheld_internal: [
      "JMH allocation/time backstops (regression detectors, not absolute claims)",
      "growth/soak live-set slope and absolute",
      "event-log verification cost",
      "forward-pool guard",
      "AppCDS boolean",
      "ByteBuf leak gate",
      "streaming match-A/B ratio (measured against a deliberately constrained 1-CPU SUT — an internal tripwire, misleading as an absolute public figure)",
      "startup median-of-9",
      "baseline-freshness assertion"
    ]
  }
