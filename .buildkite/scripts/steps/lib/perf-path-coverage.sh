# shellcheck shell=bash
# Path-coverage phase, sourced by perf-test-run.sh. Drives request paths the rest of
# the daily run never exercises (k6/coverage.js plus an HTTPS sweep ladder) against
# FRESH, dedicated SUTs so each result starts from a known state, and assembles
# PATH_COVERAGE_JSON for result.json's `.path_coverage`.
#
# NOTIFY-ONLY by construction: nothing here calls add_check, so no coverage outcome
# can change validity.valid. A failed arm is left out of `.arms` and named in
# `.problems` (and a warning annotation), and compare budgets every metric without
# `gating`.
#
# Relies on the caller's rig: NETWORK, RUN_ID, OUT_DIR, REPO_ROOT, K6_IMAGE,
# MOCKSERVER_IMAGE, the cpusets, SERVER_MEMORY, UPSTREAM, and the helpers
# start_mockserver, wait_ready, cpuset_arg, to_secs, to_bytes, run_sweep and
# derive_saturation.

COV_ALL_ARMS="churn,keepalive,matchers,h2,capture,download"
PERF_COVERAGE="${PERF_COVERAGE:-true}"
# Whitespace stripped once, so "churn, h2" validates and matches the same as "churn,h2".
PERF_COVERAGE_ARMS="$(printf '%s' "${PERF_COVERAGE_ARMS:-$COV_ALL_ARMS}" | tr -d '[:space:]')"
# HTTPS (ALPN -> h2) ladder. Rungs sit below the plain-HTTP knee so each is a
# regression point; derive_saturation still labels any rung the server cannot keep up with.
COV_H2_RATES="${PERF_COVERAGE_H2_RATES:-2000,8000,16000}"
COV_H2_STEP="${PERF_COVERAGE_H2_STEP:-15s}"
# Longer than the HTTP ladder's 3 s: every rung opens its whole VU pool as fresh TLS
# connections at onset, and that handshake burst must stay out of the percentiles.
COV_H2_SETTLE_S="${PERF_COVERAGE_H2_SETTLE_S:-5}"
COV_DOWNLOAD_BYTES="${PERF_COVERAGE_DOWNLOAD_BYTES:-33554432}"
COV_SAMPLE_INTERVAL="${PERF_COVERAGE_SAMPLE_INTERVAL:-2}"

COV_SUT="mockserver-perf-cov-${RUN_ID}"
COV_CAPTURE_SUT="mockserver-perf-cov-capture-${RUN_ID}"
COV_DL_SUT="mockserver-perf-cov-dl-${RUN_ID}"
COV_DL_UPSTREAM="mockserver-perf-cov-dl-upstream-${RUN_ID}"
# The download upstream lives ONLY on this network, which k6 is not on: a download can
# reach it solely through the SUT, so a proxy bypass fails instead of measuring direct.
COV_DL_NET="mockserver-perf-cov-dl-${RUN_ID}"
COV_K6_PREFIX="mockserver-perf-k6-cov-${RUN_ID}"
COV_ALIAS="mockserver-cov"
COV_WORK_DIR=""
COV_SAMPLER_PID=""
PATH_COVERAGE_JSON='{}'
COV_PROBLEMS=()

cov_validate_env() {
  case "$PERF_COVERAGE" in true|false) : ;; *)
    echo "ERROR: PERF_COVERAGE='$PERF_COVERAGE' is not true|false" >&2; return 1 ;;
  esac
  local arm
  for arm in ${PERF_COVERAGE_ARMS//,/ }; do
    case ",$COV_ALL_ARMS," in *",$arm,"*) : ;; *)
      echo "ERROR: PERF_COVERAGE_ARMS contains '$arm'; allowed: $COV_ALL_ARMS" >&2; return 1 ;;
    esac
  done
  local pair name value pattern
  for pair in "PERF_COVERAGE_DOWNLOAD_BYTES|$COV_DOWNLOAD_BYTES|^[1-9][0-9]*$" \
              "PERF_COVERAGE_SAMPLE_INTERVAL|$COV_SAMPLE_INTERVAL|^[1-9][0-9]*$" \
              "PERF_COVERAGE_H2_SETTLE_S|$COV_H2_SETTLE_S|^[0-9]+$" \
              "PERF_COVERAGE_H2_STEP|$COV_H2_STEP|^[1-9][0-9]*s$" \
              "PERF_COVERAGE_H2_RATES|$COV_H2_RATES|^[1-9][0-9]*(,[1-9][0-9]*)*$"; do
    name="${pair%%|*}"; value="${pair#*|}"; pattern="${value#*|}"; value="${value%%|*}"
    if ! grep -Eq "$pattern" <<<"$value"; then
      echo "ERROR: ${name}='${value}' does not match ${pattern}" >&2; return 1
    fi
  done
  if [ "$COV_H2_SETTLE_S" -ge "${COV_H2_STEP%s}" ]; then
    echo "ERROR: PERF_COVERAGE_H2_SETTLE_S (${COV_H2_SETTLE_S}) must be shorter than PERF_COVERAGE_H2_STEP (${COV_H2_STEP})" >&2; return 1
  fi
}

cov_cleanup() {
  [ -n "$COV_SAMPLER_PID" ] && kill "$COV_SAMPLER_PID" >/dev/null 2>&1 || true
  COV_SAMPLER_PID=""
  docker rm -f "$COV_SUT" "$COV_CAPTURE_SUT" "$COV_DL_SUT" "$COV_DL_UPSTREAM" >/dev/null 2>&1 || true
  # shellcheck disable=SC2046
  docker rm -f $(docker ps -aq --filter "name=${COV_K6_PREFIX}" 2>/dev/null) >/dev/null 2>&1 || true
  docker network rm "$COV_DL_NET" >/dev/null 2>&1 || true
  [ -n "$COV_WORK_DIR" ] && rm -rf "$COV_WORK_DIR" >/dev/null 2>&1 || true
}

cov_enabled() {
  case ",${PERF_COVERAGE_ARMS}," in *",$1,"*) return 0 ;; esac
  return 1
}

# A SUT that died mid-arm would otherwise just look like a slow or empty arm.
cov_sut_alive() { # container label
  if [ "$(docker inspect --format '{{.State.Running}}' "$1" 2>/dev/null || echo missing)" != true ]; then
    cov_problem "$2: the SUT ($1) is no longer running - its numbers describe a dying server"
  fi
}

cov_problem() {
  COV_PROBLEMS+=("$1")
  echo "WARNING: path coverage - $1 (notify-only; the run continues)" >&2
}

cov_metrics_url() { # container -> its host-published /mockserver/metrics URL
  local hp
  hp="$(docker port "$1" 1080/tcp 2>/dev/null | head -1 || true)"
  if [ -n "$hp" ]; then printf 'http://%s/mockserver/metrics' "$hp"; fi
}

# One value from a Prometheus scrape; an exact series name, labels included.
# Integer output (the client may print 2.5E7); empty when the series is absent,
# which an older image legitimately is.
cov_metric() { # scrape series
  awk -v s="$2" '$1==s {printf "%.0f", $2+0; exit}' <<<"$1"
}

cov_counter_sum() { # scrape series -> the series summed over all its labels; empty when absent
  awk -v s="$2" '$1==s || index($1, s "{")==1 {t += $2; n = 1} END {if (n) printf "%.0f", t}' <<<"$1"
}

cov_scrape() { curl -s --max-time 4 "$1" 2>/dev/null || true; }

# Samples container RSS and the JVM's memory + event-log gauges every
# COV_SAMPLE_INTERVAL seconds. Blank columns mean the series is absent.
cov_sampler() { # container metrics_url csv
  local c="$1" url="$2" csv="$3" stats memu scrape
  echo "ts,rss_bytes,heap_used_bytes,netty_direct_bytes,direct_pool_bytes,ring_occupancy,ring_capacity,dropped_total,evicted_total" > "$csv"
  while true; do
    stats="$(docker stats --no-stream --format '{{.MemUsage}}' "$c" 2>/dev/null || true)"
    memu="${stats%% /*}"
    scrape="$(cov_scrape "$url")"
    printf '%s,%s,%s,%s,%s,%s,%s,%s,%s\n' "$(date -u +%s)" \
      "$([ -n "$memu" ] && to_bytes "$memu" || true)" \
      "$(cov_metric "$scrape" 'jvm_memory_used_bytes{area="heap"}')" \
      "$(cov_metric "$scrape" netty_direct_memory_used_bytes)" \
      "$(cov_metric "$scrape" 'jvm_buffer_pool_used_bytes{pool="direct"}')" \
      "$(cov_metric "$scrape" mock_server_event_log_ring_occupancy)" \
      "$(cov_metric "$scrape" mock_server_event_log_ring_capacity)" \
      "$(cov_counter_sum "$scrape" mock_server_dropped_log_events_total)" \
      "$(cov_metric "$scrape" mock_server_evicted_log_entries_total)" >> "$csv"
    sleep "$COV_SAMPLE_INTERVAL"
  done
}

cov_sampler_start() { # container metrics_url csv
  cov_sampler "$@" & COV_SAMPLER_PID=$!
}

cov_sampler_stop() {
  [ -n "$COV_SAMPLER_PID" ] && kill "$COV_SAMPLER_PID" >/dev/null 2>&1 || true
  COV_SAMPLER_PID=""
}

# Peak of one column over rows with ts >= $3; empty when no row carries a value.
cov_csv_peak() { # csv column_name [from_ts]
  awk -F',' -v col="$2" -v from="${3:-0}" '
    NR==1 { for (i=1;i<=NF;i++) if ($i==col) c=i; next }
    c && $1>=from && $c!="" { if (!seen || $c+0>m) m=$c+0; seen=1 }
    END { if (seen) printf "%.0f", m }' "$1"
}

# First value of one column at or after $3 (the pre-load baseline).
cov_csv_first() { # csv column_name from_ts
  awk -F',' -v col="$2" -v from="${3:-0}" '
    NR==1 { for (i=1;i<=NF;i++) if ($i==col) c=i; next }
    c && $1>=from && $c!="" { printf "%.0f", $c; exit }' "$1"
}

# k6 coverage.js against the coverage SUT alias. Extra docker-run args (mounts, env)
# go before the image. K6_COV_* tunables set in the environment pass through.
cov_k6() { # name_suffix mode result_file [extra docker run args...]
  local name="${COV_K6_PREFIX}-$1" mode="$2" result="$3"; shift 3
  local passthru=() v
  for v in K6_COV_DURATION K6_COV_MATCHER_DURATION K6_COV_WARMUP K6_COV_SETTLE K6_COV_STAGGER K6_COV_VUS \
           K6_COV_CHURN_RATE K6_COV_KEEPALIVE_RATE K6_COV_MATCHER_RATE K6_COV_MATCHER_CANDIDATES \
           K6_COV_CAPTURE_RATE K6_COV_CAPTURE_VUS K6_COV_DOWNLOAD_RATE K6_COV_DOWNLOAD_TIME_UNIT K6_COV_DOWNLOAD_VUS; do
    [ -n "${!v:-}" ] && passthru+=(-e "$v=${!v}")
  done
  # shellcheck disable=SC2046
  docker run --rm --name "$name" --network "$NETWORK" $(cpuset_arg "$K6_CPUS") \
    -v "$REPO_ROOT/mockserver-performance-test/k6:/k6:ro" -v "$OUT_DIR:/out" \
    -e "K6_COV_MODE=$mode" -e "K6_COV_RESULT_PATH=/out/$result" \
    -e "K6_COV_HTTP_URL=http://${COV_ALIAS}:1080" -e "K6_COV_TLS_URL=https://${COV_ALIAS}:1080" \
    ${passthru[@]+"${passthru[@]}"} \
    "$@" \
    "$K6_IMAGE" run --quiet /k6/coverage.js
}

# Folds one coverage.js result into COV_ARMS, ONLY when that k6 invocation exited 0.
# k6 still runs handleSummary after setup() fails, so a file existing proves nothing.
cov_merge() { # result_file label k6_exit_code
  local f="$OUT_DIR/$1"
  if [ "$3" -ne 0 ]; then
    cov_problem "$2: k6 exited $3 (a setup probe failed or the run aborted) - arm not recorded"
    return 1
  fi
  if [ -s "$f" ] && jq -e '.arms | type == "object" and length > 0' "$f" >/dev/null 2>&1; then
    COV_ARMS="$(jq -c --slurpfile r "$f" '. + $r[0].arms' <<<"$COV_ARMS")"
  else
    cov_problem "$2 produced no measured arm ($1)"
    return 1
  fi
}

# Two coverage.js invocations at once, so a tls/mtls pair sees the same server conditions.
cov_k6_pair() { # label mode_a result_a mode_b result_b
  local pa pb rca=0 rcb=0
  cov_k6 "$2" "$2" "$3" & pa=$!
  cov_k6 "$4" "$4" "$5" -v "$COV_WORK_DIR/certs:/cov-certs:ro" \
    -e K6_COV_CLIENT_CERT=/cov-certs/client.pem -e K6_COV_CLIENT_KEY=/cov-certs/client.key & pb=$!
  wait "$pa" || rca=$?
  wait "$pb" || rcb=$?
  cov_merge "$3" "$1 ($2)" "$rca" || true
  cov_merge "$5" "$1 ($4)" "$rcb" || true
}

cov_make_cert() {
  mkdir -p "$COV_WORK_DIR/certs" && chmod 0755 "$COV_WORK_DIR/certs"
  (
    cd "$COV_WORK_DIR/certs" || exit 1
    openssl req -x509 -newkey rsa:2048 -keyout ca.key -out ca.pem -days 2 -nodes -subj "/CN=perf-cov-ca" 2>/dev/null
    openssl req -newkey rsa:2048 -keyout client.key -out client.csr -nodes -subj "/CN=perf-cov-client" 2>/dev/null
    openssl x509 -req -in client.csr -CA ca.pem -CAkey ca.key -CAcreateserial -out client.pem -days 2 2>/dev/null
    chmod 0644 client.pem client.key
  ) && [ -s "$COV_WORK_DIR/certs/client.pem" ]
}

cov_start_sut() { # name alias mem mount log_level [extra env...]
  local name="$1" alias="$2" mem="$3" mount="$4" level="$5"; shift 5
  # shellcheck disable=SC2034  # read by start_mockserver in perf-test-run.sh
  START_EXTRA_ENV=("$@")
  local rc=0
  start_mockserver "$name" "$SERVER_CPUS" "$alias" "publish" "$mem" "$mount" "$level" "" "" 1080 || rc=$?
  # shellcheck disable=SC2034
  START_EXTRA_ENV=()
  [ "$rc" -eq 0 ] && wait_ready "$name"
}

# The coverage SUT: churn, keepalive, matchers and the HTTPS ladder, in that order
# (the ladder last: sweep.js resets the SUT in teardown).
cov_run_direct_arms() {
  if ! cov_start_sut "$COV_SUT" "$COV_ALIAS" "$SERVER_MEMORY" "" ERROR; then
    cov_problem "coverage SUT did not start - churn/keepalive/matchers/h2 arms skipped"
    return 0
  fi
  local have_cert=false rc
  if { cov_enabled churn || cov_enabled keepalive; } && command -v openssl >/dev/null 2>&1 && cov_make_cert; then
    have_cert=true
  elif cov_enabled churn || cov_enabled keepalive; then
    cov_problem "openssl could not generate a client certificate - mtls arms skipped"
  fi
  local pair
  for pair in churn keepalive; do
    cov_enabled "$pair" || continue
    echo "--- path coverage: ${pair} (tls vs mtls, two concurrent k6)"
    if [ "$have_cert" = true ]; then
      cov_k6_pair "$pair" "$pair" "cov-${pair}.json" "${pair}_mtls" "cov-${pair}-mtls.json"
    else
      rc=0; cov_k6 "$pair" "$pair" "cov-${pair}.json" || rc=$?
      cov_merge "cov-${pair}.json" "$pair" "$rc" || true
    fi
    cov_sut_alive "$COV_SUT" "$pair"
  done
  if cov_enabled matchers; then
    echo "--- path coverage: JSONPath / XPath / JSON-schema matchers"
    rc=0; cov_k6 matchers matchers cov-matchers.json || rc=$?
    cov_merge cov-matchers.json matchers "$rc" || true
    cov_sut_alive "$COV_SUT" matchers
  fi
  if cov_enabled h2; then
    cov_run_h2_ladder
    cov_sut_alive "$COV_SUT" h2
  fi
  docker rm -f "$COV_SUT" >/dev/null 2>&1 || true
}

# sweep.js over HTTPS (k6 negotiates h2 via ALPN) on the coverage SUT, with the
# daily ladder's own rig-validity rules (derive_saturation). The sweep globals are
# swapped for the call and restored after.
cov_run_h2_ladder() {
  echo "--- path coverage: HTTPS/h2 ladder ($COV_H2_RATES, ${COV_H2_STEP} per rung)"
  # The ladder is only an HTTP/2 measurement if k6 itself (the ladder's client, same
  # TLS settings as sweep.js) negotiates h2 with the SUT over ALPN.
  local alpn
  alpn="$(printf '%s\n' "import http from 'k6/http';" \
      "export const options = { insecureSkipTLSVerify: true, iterations: 1, vus: 1 };" \
      "export default function () { console.log('COV_H2_PROTO=' + http.put(__ENV.URL).proto); }" \
    | docker run --rm -i --name "${COV_K6_PREFIX}-h2probe" --network "$NETWORK" "$K6_IMAGE" run --quiet -e "URL=https://${COV_ALIAS}:1080/mockserver/status" - 2>&1 \
    | grep -oE 'COV_H2_PROTO=[^" ]*' | head -1 | sed 's/^COV_H2_PROTO=//' || true)"
  if [ "$alpn" != "HTTP/2.0" ]; then
    cov_problem "h2 ladder skipped: k6 negotiated '${alpn:-nothing}' with the coverage SUT over TLS, not HTTP/2.0"
    return 0
  fi
  local s_rates="$SWEEP_RATES" s_step="$SWEEP_STEP" s_step_s="$STEP_S" s_settle="$SETTLE_S" ok=true sat=""
  SWEEP_RATES="$COV_H2_RATES"; SWEEP_STEP="$COV_H2_STEP"; STEP_S="$(to_secs "$COV_H2_STEP")"; SETTLE_S="$COV_H2_SETTLE_S"
  run_sweep "${COV_K6_PREFIX}-h2" "$COV_ALIAS" "$OUT_DIR/cov-h2-sweep.json" "$OUT_DIR/cov-h2-k6-cpu.csv" https || ok=false
  if [ "$ok" = true ] && [ -s "$OUT_DIR/cov-h2-sweep.json" ]; then
    sat="$(derive_saturation "$OUT_DIR/cov-h2-sweep.json" "$OUT_DIR/cov-h2-k6-cpu.csv" "$LAST_SWEEP_T0" 2>/dev/null || true)"
  fi
  SWEEP_RATES="$s_rates"; SWEEP_STEP="$s_step"; STEP_S="$s_step_s"; SETTLE_S="$s_settle"
  if [ -z "$sat" ] || ! jq -e '.ladder | length > 0' <<<"$sat" >/dev/null 2>&1; then
    cov_problem "h2 ladder produced no rungs"
    return 0
  fi
  # One arm per rung. Latency and delivery are published only for a rig-valid rung
  # (derive_saturation's rules), and a tail only above the sweep's 30-sample floor.
  COV_ARMS="$(jq -c --slurpfile sweep "$OUT_DIR/cov-h2-sweep.json" --argjson sat "$sat" '
    . + ([ $sat.ladder[] as $r
          | (($sweep[0].points // []) | map(select(.offered_rps == $r.offered_rps)) | .[0]) as $p
          | select($p != null)
          | (($p.measured_sample_count // 0) >= 30) as $enough
          | { key: ("h2_" + ($r.offered_rps|tostring)),
              value: { offered_rps: $r.offered_rps, achieved_rps: $r.achieved_rps,
                       rig_valid: $r.rig_valid, exclude_reason: $r.exclude_reason,
                       measured_sample_count: $p.measured_sample_count,
                       p50_ms: (if $r.rig_valid and $enough then $p.p50_ms else null end),
                       p99_ms: (if $r.rig_valid and $enough then $p.p99_ms else null end),
                       delivery_ratio: (if $r.rig_valid and $r.offered_rps > 0
                                        then ($r.achieved_rps / $r.offered_rps * 10000 | round / 10000) else null end),
                       error_rate: $r.error_rate } } ] | from_entries)' <<<"$COV_ARMS")"
  if [ "$(jq -r '.rig_valid_rungs // 0' <<<"$sat")" -eq 0 ]; then
    cov_problem "h2 ladder: no rung was rig-valid, so its latency and peak are not measurements (per rung: $(jq -r '[.ladder[] | "\(.offered_rps): \(.exclude_reason // "?")"] | join("; ")' <<<"$sat"))"
  fi
  COV_H2_LADDER="$(jq -c --arg rates "$COV_H2_RATES" --arg step "$COV_H2_STEP" --argjson settle "$COV_H2_SETTLE_S" \
    '{rates:$rates, step:$step, settle_s:$settle, proto:"https_h2", k6_negotiated_proto:"HTTP/2.0",
      rig_valid_peak_achieved_rps, saturation_rps, rig_valid_rungs}' <<<"$sat")"
}

# Disk capture under proxy load, at WARN: a fresh SUT persisting every recorded
# request to a host-mounted NDJSON file while k6 drives absolute-URI proxy traffic.
cov_run_capture() {
  echo "--- path coverage: proxy load with persistRecordedRequestsToDisk at WARN"
  local dir="$COV_WORK_DIR/capture"
  mkdir -p "$dir" && chmod 0777 "$dir"
  if ! cov_start_sut "$COV_CAPTURE_SUT" mockserver-cov-capture "$SERVER_MEMORY" "$dir:/cov-capture" WARN \
       -e MOCKSERVER_PERSIST_RECORDED_REQUESTS_TO_DISK=true \
       -e MOCKSERVER_PERSISTED_RECORDED_REQUESTS_PATH=/cov-capture/recordedRequests.ndjson; then
    cov_problem "capture SUT did not start - capture arm skipped"
    return 0
  fi
  local url csv="$OUT_DIR/cov-capture-samples.csv" before after t0 rc=0
  url="$(cov_metrics_url "$COV_CAPTURE_SUT")"
  [ -n "$url" ] || cov_problem "capture: no host-published metrics port on the capture SUT - its ring, dropped and persisted figures will be null"
  before="$(cov_scrape "$url")"
  t0="$(date -u +%s)"
  cov_sampler_start "$COV_CAPTURE_SUT" "$url" "$csv"
  cov_k6 capture capture cov-capture.json \
    -e "HTTP_PROXY=http://mockserver-cov-capture:1080" -e "http_proxy=http://mockserver-cov-capture:1080" \
    -e "NO_PROXY=" -e "no_proxy=" -e "FORWARD_UPSTREAM_HOST=${UPSTREAM_ALIAS}:1080" || rc=$?
  # The recorded-request consumer drains the ring asynchronously; count the file
  # only once the ring is empty (bounded wait), and report how long that took.
  local drain_s=0 occ
  while [ "$drain_s" -lt 60 ]; do
    occ="$(cov_metric "$(cov_scrape "$url")" mock_server_event_log_ring_occupancy)"
    [ -z "$occ" ] || [ "$occ" -eq 0 ] && break
    sleep 1; drain_s=$((drain_s + 1))
  done
  cov_sampler_stop
  after="$(cov_scrape "$url")"
  if ! cov_merge cov-capture.json capture "$rc"; then
    docker rm -f "$COV_CAPTURE_SUT" >/dev/null 2>&1 || true
    return 0
  fi
  local file="$dir/recordedRequests.ndjson" lines=0 bytes=0 req0 req1 drop0 drop1 ev0 ev1
  [ -f "$file" ] && lines="$(wc -l < "$file" | tr -d ' ')" && bytes="$(wc -c < "$file" | tr -d ' ')"
  req0="$(cov_metric "$before" mock_server_requests_received_total)"; req1="$(cov_metric "$after" mock_server_requests_received_total)"
  if [ -z "$req1" ]; then
    req0="$(cov_metric "$before" requests_received_count)"; req1="$(cov_metric "$after" requests_received_count)"
  fi
  drop0="$(cov_counter_sum "$before" mock_server_dropped_log_events_total)"; drop1="$(cov_counter_sum "$after" mock_server_dropped_log_events_total)"
  ev0="$(cov_metric "$before" mock_server_evicted_log_entries_total)"; ev1="$(cov_metric "$after" mock_server_evicted_log_entries_total)"
  if [ -z "$req0" ] || [ -z "$req1" ]; then
    cov_problem "capture: the SUT request counter could not be scraped before and after the load - persisted_ratio and dropped_ratio will be null"
  else
    # k6 can reach mockserver-upstream directly, so prove the traffic went through the SUT.
    local sent
    sent="$(jq -r '(.capture_proxy.sample_count // 0) + (.capture_proxy.settle_excluded // 0)' <<<"$COV_ARMS")"
    if [ $((req1 - req0)) -lt "$sent" ]; then
      cov_problem "capture: the SUT received $((req1 - req0)) requests but k6 sent ${sent} - the load did not all go through the proxy"
    fi
  fi
  cov_sut_alive "$COV_CAPTURE_SUT" capture
  COV_ARMS="$(jq -c \
    --arg req0 "$req0" --arg req1 "$req1" --arg drop0 "$drop0" --arg drop1 "$drop1" --arg ev0 "$ev0" --arg ev1 "$ev1" \
    --argjson lines "$lines" --argjson bytes "$bytes" --argjson drain "$drain_s" \
    --arg occ_peak "$(cov_csv_peak "$csv" ring_occupancy "$t0")" --arg cap "$(cov_csv_peak "$csv" ring_capacity "$t0")" '
    def num: if . == "" then null else tonumber end;
    def delta($a; $b): if ($a|num) == null or ($b|num) == null then null else ($b|num) - ($a|num) end;
    if .capture_proxy == null then . else
    (delta($req0; $req1)) as $req | (delta($drop0; $drop1)) as $drop
    | .capture_proxy += {
        log_level: "WARN", requests_received: $req,
        persisted_records: $lines, persisted_bytes: $bytes,
        persisted_ratio: (if ($req // 0) > 0 then ($lines / $req * 10000 | round / 10000) else null end),
        dropped_log_events: $drop,
        dropped_ratio: (if $drop != null and ($req // 0) > 0 then ($drop / $req * 1000000 | round / 1000000) else null end),
        evicted_log_entries: delta($ev0; $ev1),
        ring_capacity: ($cap|num), ring_occupancy_peak: ($occ_peak|num),
        ring_occupancy_peak_ratio: (if ($cap|num) != null and ($cap|num) > 0 and ($occ_peak|num) != null
                                    then (($occ_peak|num) / ($cap|num) * 10000 | round / 10000) else null end),
        drain_s: $drain }
    end' <<<"$COV_ARMS")"
  docker rm -f "$COV_CAPTURE_SUT" >/dev/null 2>&1 || true
}

# Large proxied (non-SSE) downloads: a fresh SUT relaying a FILE body from a
# dedicated upstream, sampled for RSS and direct memory before, during and after.
cov_run_download() {
  echo "--- path coverage: large proxied downloads (${COV_DOWNLOAD_BYTES} bytes)"
  local fdir="$COV_WORK_DIR/files"
  mkdir -p "$fdir" && chmod 0755 "$fdir"
  if ! head -c "$COV_DOWNLOAD_BYTES" /dev/urandom > "$fdir/large.bin" 2>/dev/null; then
    cov_problem "download: could not generate the body file - download arm skipped"
    return 0
  fi
  chmod 0644 "$fdir/large.bin"
  local rc=0 seed=000
  # Started on the run network (a throwaway alias) so start_mockserver can be reused,
  # then moved onto the private network under the alias the download URL names.
  docker network create "$COV_DL_NET" >/dev/null 2>&1 || rc=1
  [ "$rc" -eq 0 ] && { start_mockserver "$COV_DL_UPSTREAM" "$UPSTREAM_CPUS" mockserver-cov-dl-boot "" "$SERVER_MEMORY" "$fdir:/cov-files:ro" ERROR "" "" 1080 || rc=1; }
  [ "$rc" -eq 0 ] && { wait_ready "$COV_DL_UPSTREAM" || rc=1; }
  [ "$rc" -eq 0 ] && { docker network connect --alias mockserver-cov-dl "$COV_DL_NET" "$COV_DL_UPSTREAM" >/dev/null 2>&1 || rc=1; }
  [ "$rc" -eq 0 ] && { docker network disconnect "$NETWORK" "$COV_DL_UPSTREAM" >/dev/null 2>&1 || rc=1; }
  if [ "$rc" -eq 0 ]; then
    seed="$(docker run --rm --name "${COV_K6_PREFIX}-dlseed" --network "$COV_DL_NET" curlimages/curl:8.11.1 -s --max-time 15 -o /dev/null -w '%{http_code}' -X PUT \
      "http://mockserver-cov-dl:1080/mockserver/expectation" -H 'Content-Type: application/json' \
      -d '[{"httpRequest":{"path":"/cov/large"},"httpResponse":{"statusCode":200,"body":{"type":"FILE","filePath":"/cov-files/large.bin","contentType":"application/octet-stream"}},"times":{"unlimited":true}}]' 2>/dev/null || true)"
  fi
  if [ "$seed" != 201 ] || ! cov_start_sut "$COV_DL_SUT" mockserver-cov-dl-sut "$SERVER_MEMORY" "" ERROR \
     || ! docker network connect "$COV_DL_NET" "$COV_DL_SUT" >/dev/null 2>&1; then
    cov_problem "download: private upstream setup failed (seed HTTP ${seed}) or the SUT did not start - download arm skipped"
    docker rm -f "$COV_DL_UPSTREAM" "$COV_DL_SUT" >/dev/null 2>&1 || true
    return 0
  fi
  local url csv="$OUT_DIR/cov-download-samples.csv" t_base t_load t_end k6rc=0 up0 up1
  url="$(cov_metrics_url "$COV_DL_SUT")"
  [ -n "$url" ] || cov_problem "download: no host-published metrics port on the download SUT - its RSS is sampled but heap and direct-memory figures will be null"
  up0="$(cov_upstream_requests before)"
  t_base="$(date -u +%s)"
  cov_sampler_start "$COV_DL_SUT" "$url" "$csv"
  sleep $((COV_SAMPLE_INTERVAL * 3))
  t_load="$(date -u +%s)"
  cov_k6 download download cov-download.json \
    -e "HTTP_PROXY=http://mockserver-cov-dl-sut:1080" -e "http_proxy=http://mockserver-cov-dl-sut:1080" \
    -e "NO_PROXY=" -e "no_proxy=" \
    -e "K6_COV_DOWNLOAD_URL=http://mockserver-cov-dl:1080/cov/large" -e "K6_COV_DOWNLOAD_BYTES=$COV_DOWNLOAD_BYTES" || k6rc=$?
  t_end="$(date -u +%s)"
  cov_sut_alive "$COV_DL_SUT" download
  up1="$(cov_upstream_requests after)"
  sleep $((COV_SAMPLE_INTERVAL * 5))
  cov_sampler_stop
  if ! cov_merge cov-download.json download "$k6rc"; then
    docker rm -f "$COV_DL_SUT" "$COV_DL_UPSTREAM" >/dev/null 2>&1 || true
    return 0
  fi
  local mb=1048576 key proxied_ratio
  key="download_$(( (COV_DOWNLOAD_BYTES + mb / 2) / mb ))mib"
  COV_ARMS="$(jq -c --arg key "$key" --arg up0 "$up0" --arg up1 "$up1" \
    --arg rss_base "$(cov_csv_first "$csv" rss_bytes "$t_base")" \
    --arg rss_peak "$(cov_csv_peak "$csv" rss_bytes "$t_load")" \
    --arg rss_after "$(awk -F',' -v t="$t_end" 'NR>1 && $1>t && $2!="" {v=$2} END{print v}' "$csv")" \
    --arg heap_peak "$(cov_csv_peak "$csv" heap_used_bytes "$t_load")" \
    --arg netty_peak "$(cov_csv_peak "$csv" netty_direct_bytes "$t_load")" \
    --arg netty_base "$(cov_csv_first "$csv" netty_direct_bytes "$t_base")" \
    --arg pool_peak "$(cov_csv_peak "$csv" direct_pool_bytes "$t_load")" '
    def mb: if . == "" then null else (tonumber / 1048576 * 10 | round / 10) end;
    if .[$key] == null then . else
    (if $up0 == "" or $up1 == "" then null else ($up1|tonumber) - ($up0|tonumber) end) as $up
    | ((.[$key].sample_count // 0) + (.[$key].settle_excluded // 0)) as $sent
    | .[$key] += { rss_baseline_mib: ($rss_base|mb), rss_peak_mib: ($rss_peak|mb), rss_after_mib: ($rss_after|mb),
                   heap_peak_mib: ($heap_peak|mb), netty_direct_baseline_mib: ($netty_base|mb),
                   netty_direct_peak_mib: ($netty_peak|mb), direct_pool_peak_mib: ($pool_peak|mb),
                   upstream_requests: $up,
                   proxied_ratio: (if $up != null and $sent > 0 then ($up / $sent * 1000 | round / 1000) else null end) }
    end' <<<"$COV_ARMS")"
  proxied_ratio="$(jq -r --arg key "$key" '.[$key].proxied_ratio // "null"' <<<"$COV_ARMS")"
  if ! awk -v r="$proxied_ratio" 'BEGIN{ exit !(r != "null" && r+0 >= 0.9) }'; then
    cov_problem "download: the private upstream served ${proxied_ratio} of the downloads k6 sent (expected ~1) - the arm may not have measured proxying"
  fi
  docker rm -f "$COV_DL_SUT" "$COV_DL_UPSTREAM" >/dev/null 2>&1 || true
}

# Requests the private download upstream has received (its own counter, read over
# the private network); empty when it cannot be read.
cov_upstream_requests() { # name_suffix
  docker run --rm --name "${COV_K6_PREFIX}-dlcount-$1" --network "$COV_DL_NET" curlimages/curl:8.11.1 -s --max-time 5 \
    "http://mockserver-cov-dl:1080/mockserver/metrics" 2>/dev/null \
    | awk '$1=="mock_server_requests_received_total" || $1=="requests_received_count" {printf "%.0f", $2+0; exit}' || true
}

cov_annotate_problems() {
  [ "${#COV_PROBLEMS[@]}" -gt 0 ] || return 0
  command -v buildkite-agent >/dev/null 2>&1 || return 0
  printf ':warning: **Path coverage (notify-only): %s arm problem(s)** - the affected arms are absent from `.path_coverage.arms` this run; nothing else is affected.\n\n%s\n' \
    "${#COV_PROBLEMS[@]}" "$(printf -- '- %s\n' "${COV_PROBLEMS[@]}")" \
    | buildkite-agent annotate --style warning --context "perf-path-coverage-${PERF_RUN_NAME:-default}" >/dev/null 2>&1 || true
}

run_path_coverage() {
  if [ "$PERF_COVERAGE" != true ]; then
    echo "--- path coverage: disabled (PERF_COVERAGE=$PERF_COVERAGE)"
    return 0
  fi
  echo "+++ path coverage phase (notify-only): arms=${PERF_COVERAGE_ARMS}"
  local t0; t0="$(date -u +%s)"
  COV_WORK_DIR="$(mktemp -d "${TMPDIR:-/tmp}/perf-cov.XXXXXX")"
  chmod 0755 "$COV_WORK_DIR"
  COV_ARMS='{}'; COV_H2_LADDER='null'
  if cov_enabled churn || cov_enabled keepalive || cov_enabled matchers || cov_enabled h2; then
    cov_run_direct_arms
  fi
  cov_enabled capture && cov_run_capture
  cov_enabled download && cov_run_download
  # Within-run client-certificate cost: each mtls arm against its no-certificate twin.
  COV_ARMS="$(jq -c '
    def r($a; $b; $k): if (.[$a][$k] // null) != null and ((.[$b][$k] // 0) > 0)
                         then (.[$a][$k] / .[$b][$k] * 10000 | round / 10000) else null end;
    reduce (["mtls_keepalive","tls_keepalive"], ["mtls_churn","tls_churn"]) as [$m, $t] (.;
      if .[$m] != null and .[$t] != null
      then .[$m] += {p50_ratio: r($m; $t; "p50_ms"), p99_ratio: r($m; $t; "p99_ms")}
           | (if .[$t].handshake_p50_ms != null then .[$m].handshake_p50_ratio = r($m; $t; "handshake_p50_ms") else . end)
      else . end)' <<<"$COV_ARMS")"
  local problems_json
  if [ "${#COV_PROBLEMS[@]}" -gt 0 ]; then
    problems_json="$(printf '%s\n' "${COV_PROBLEMS[@]}" | jq -R . | jq -sc .)"
  else
    problems_json='[]'
  fi
  PATH_COVERAGE_JSON="$(jq -nc --arg arms_requested "$PERF_COVERAGE_ARMS" --argjson arms "$COV_ARMS" \
    --argjson h2 "$COV_H2_LADDER" --argjson problems "$problems_json" \
    --argjson duration_s "$(( $(date -u +%s) - t0 ))" \
    '{attempted:true, arms_requested:$arms_requested, duration_s:$duration_s,
      arms:$arms, h2_ladder:$h2, problems:$problems}')"
  cov_annotate_problems
  cov_cleanup
  echo "--- path coverage: $(jq -c '{duration_s, arms:(.arms|keys), problems:(.problems|length)}' <<<"$PATH_COVERAGE_JSON")"
}
