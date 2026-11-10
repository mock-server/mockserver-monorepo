// Soak test — sustained moderate load over a long duration (default 2h) to
// surface slow degradation: memory growth from the event log / expectation
// churn, GC pressure, file-descriptor or connection leaks, and — the reason a
// soak matters more than a short regression run — whether the DATA-PLANE p99
// DRIFTS upward as the in-memory event log fills and stays full. Pair with the
// docker-compose stack so Grafana shows JVM heap/GC trends across the
// soak.
//
//   k6 run mockserver-performance-test/k6/soak.js
//   k6 run -e K6_SOAK_DURATION=2h -e K6_SOAK_RATE=200 .../soak.js
//
// TWO failure signals (thresholds, i.e. the pass/fail gate):
//   1. http_req_duration{op:match} p95/p99 over the WHOLE soak — the data-plane hot
//      path must stay under LIMITS for the whole run, not just cold.
//   2. http_req_failed{op:match} error rate — connection/fd leaks surface as
//      climbing data-plane errors over hours.
//
// DRIFT is measured, not gated: every request is tagged with the window it was sent
// in (win:wNNN) and handleSummary emits each arm's per-window latency series, which
// the soak step turns into drift figures (.buildkite/scripts/steps/lib/perf-soak-drift.jq).
//
// EVENT-LOG VERIFICATION COST AS THE LOG FILLS. `verify` and
// `retrieveRecordedRequests` are issued at a LOW fixed rate throughout. Both
// scan the event log, so a query against a FULL ring is where an O(n)
// regression bites hardest — and this is the central-deployment pattern
// (pipelines assert / retrieve recorded traffic). Their latency is MEASURED, not
// gated (notify-only; their feed to the baseline compare is still deferred), and the soak step
// samples requests_received_count / heap alongside so the recorded verify /
// retrieve latency can be read AGAINST log occupancy. This is what finally
// DEMONSTRATES the ring-buffer bound under load rather than asserting it: the log
// stays at whichever bound binds first (at the default budgets the byte budget, not
// maxLogEntries; the step records it as event_log.binding) while requests_received
// climbs unboundedly, so query latency and live-set stay flat.
//
import http from 'k6/http';
import { check } from 'k6';
import { Counter } from 'k6/metrics';
import exec from 'k6/execution';
import { CONFIG, LOAD, LIMITS, num, env } from './lib/config.js';
import {
  seedExpectations,
  resetMockServer,
  getSimple,
  createSimpleExpectation,
} from './lib/expectations.js';

// --- soak tunables (local to soak.js; all knobs are __ENV-driven via the shared
// num()/env() helpers so the same script runs locally, in compose, and in CI). ----
const SOAK = {
  // Low fixed rates for the two event-log queries. Deliberately ~1 rps: this is a
  // COST probe against a filling log, not a load arm — it must not perturb the
  // data-plane p99 it runs beside. Over a 2h soak, 1 rps is ~7200 samples per
  // query type, ample for a p95/p99 against a long-full ring.
  verifyRate: num('K6_SOAK_VERIFY_RATE', 1),
  retrieveRate: num('K6_SOAK_RETRIEVE_RATE', 1),
  // Width of the consecutive windows the run is cut into (seconds for a local run).
  window: env('K6_SOAK_WINDOW', '5m'),
  // Windows starting before this are warm-up (JIT, connection ramp, the event log's
  // first fill) and never a drift reference. Emitted for the step; not used for tagging.
  warmup: env('K6_SOAK_WARMUP', '15m'),
  // How many windows after the warm-up the caller's drift figures need (the soak step
  // passes it). 0: not checked.
  driftWindows: num('K6_SOAK_DRIFT_WINDOWS', 0),
  proto: env('PROTO', CONFIG.baseUrl.startsWith('https') ? 'https_h2' : 'http'),
  resultPath: env('K6_SOAK_RESULT_PATH', 'soak-result.json'),
};

// k6 duration string -> seconds: "90s", "5m", "2h", "500ms", "1.5h", and the compound
// forms "1h30m" and "1d12h". Anything else throws, so a typo cannot become a 0 s window.
// A bare number is refused too: k6 reads it as milliseconds.
const UNIT_SECONDS = { ms: 0.001, s: 1, m: 60, h: 3600 };
function toSeconds(name, spec) {
  const text = String(spec).trim();
  const m = text.match(/^(?:(\d+)d)?((?:\d+(?:\.\d+)?(?:ms|h|m|s))*)$/);
  if (text === '' || !m) {
    throw new Error(
      `${name}=${spec} is not a duration this script can read: use d, h, m, s or ms with a number each, e.g. 2h, 90s, 1h30m`,
    );
  }
  let seconds = Number(m[1] || 0) * 86400;
  const part = /(\d+(?:\.\d+)?)(ms|h|m|s)/g;
  for (let c = part.exec(m[2]); c !== null; c = part.exec(m[2])) {
    seconds += Number(c[1]) * UNIT_SECONDS[c[2]];
  }
  return seconds;
}

// TRANSPORT errors on the match arm — a request that never completed an HTTP
// round trip (connection refused/dropped, dial/read timeout, TLS failure), i.e. a
// non-zero k6 error_code. This is DISTINCT from http_req_failed{op:match}, which
// counts a COMPLETED response with a non-2xx status (e.g. a 404) as a failure. The
// pair lets the soak step tell "the SUT is DOWN" (high transport errors) apart
// from "the SUT is UP but answering 404" (high http_req_failed, ~zero transport
// errors) — the shape of a harness that evicted its own match seed. See the guard in
// .buildkite/scripts/steps/perf-test-soak.sh.
const matchTransportErrors = new Counter('soak_match_transport_errors');

const DURATION_SEC = toSeconds('K6_SOAK_DURATION', LOAD.soakDuration);
const WINDOW_SEC = toSeconds('K6_SOAK_WINDOW', SOAK.window);
const WARMUP_SEC = toSeconds('K6_SOAK_WARMUP', SOAK.warmup);
// Whole windows only: a trailing remainder is folded into the last window. Each
// window costs one sub-metric per arm, so "2h in 1s windows" is refused.
const MAX_WINDOWS = 96;
const WINDOW_COUNT = WINDOW_SEC > 0 ? Math.max(1, Math.floor(DURATION_SEC / WINDOW_SEC)) : 0;
if (WINDOW_COUNT < 1 || WINDOW_COUNT > MAX_WINDOWS) {
  throw new Error(
    `K6_SOAK_WINDOW=${SOAK.window} cuts K6_SOAK_DURATION=${LOAD.soakDuration} into ${WINDOW_COUNT} windows; it must give 1 to ${MAX_WINDOWS}`,
  );
}
// A run too short for its warm-up is refused here, not found out when it ends.
const SETTLED_WINDOWS = Math.max(0, WINDOW_COUNT - Math.ceil(WARMUP_SEC / WINDOW_SEC));
if (SETTLED_WINDOWS < SOAK.driftWindows) {
  throw new Error(
    `K6_SOAK_DURATION=${LOAD.soakDuration} has ${SETTLED_WINDOWS} window(s) of K6_SOAK_WINDOW=${SOAK.window} starting at or after K6_SOAK_WARMUP=${SOAK.warmup}; K6_SOAK_DRIFT_WINDOWS=${SOAK.driftWindows} are needed`,
  );
}
const WINDOW_OPS = ['match', 'verify', 'retrieve'];

function windowName(index) {
  return `w${`00${index}`.slice(-3)}`;
}

// The window a request is sent in, from the wall-clock elapsed time (robust
// regardless of which VU runs the iteration — same approach as regression.js
// phaseTag). Requests still in flight after the run's duration (gracefulStop)
// count towards the last window.
function windowTag() {
  const elapsedSec = exec.instance.currentTestRunDuration / 1000;
  const index = Math.floor(elapsedSec / WINDOW_SEC);
  return windowName(Math.min(WINDOW_COUNT - 1, Math.max(0, index)));
}

// One always-pass threshold per arm and window, to MATERIALISE its sub-metric.
function windowThresholds() {
  const thresholds = {};
  for (const op of WINDOW_OPS) {
    for (let i = 0; i < WINDOW_COUNT; i++) {
      thresholds[`http_req_duration{op:${op},win:${windowName(i)}}`] = ['p(99)>=0'];
    }
  }
  return thresholds;
}

// A verification body that ALWAYS matches (the /simple path is hit continuously
// by the match scenario, so it is always in the log) — verify therefore returns
// 202, never 406. atLeast:1 keeps the check cheap and deterministic.
const VERIFY_BODY = JSON.stringify({
  httpRequest: { path: '/simple' },
  times: { atLeast: 1 },
});

function verifyLog(win) {
  const res = http.put(`${CONFIG.controlPlane}/verify`, VERIFY_BODY, {
    headers: { 'Content-Type': 'application/json', ...CONFIG.keepAliveHeaders },
    tags: { op: 'verify', name: 'PUT /mockserver/verify', win },
  });
  // 202 = verification satisfied. Anything else (406 not-matched, 400, 5xx) is a
  // real fault and must drag the checks rate below the gate.
  check(res, { 'verify: 202': (r) => r.status === 202 });
  return res;
}

function retrieveLog(win) {
  // type=REQUESTS is the full recorded-request scan over the event log — the O(n)
  // read the central-deployment pattern performs and the one a ring regression
  // punishes as occupancy grows.
  const res = http.put(`${CONFIG.controlPlane}/retrieve?type=REQUESTS&format=JSON`, null, {
    headers: { 'Content-Type': 'application/json', ...CONFIG.keepAliveHeaders },
    tags: { op: 'retrieve', name: 'PUT /mockserver/retrieve', win },
  });
  check(res, { 'retrieve: 200': (r) => r.status === 200 });
  return res;
}

export const options = {
  insecureSkipTLSVerify: CONFIG.insecureSkipTLSVerify,
  // p(50)/p(99) and count are not in k6's default summary set — declare them so
  // handleSummary can read real quantiles and sample counts off the sub-metrics.
  summaryTrendStats: ['avg', 'min', 'med', 'p(50)', 'p(90)', 'p(95)', 'p(99)', 'max', 'count'],
  scenarios: {
    // Steady data-plane matching for the whole soak (the p99-drift subject).
    match: {
      executor: 'constant-arrival-rate',
      exec: 'match',
      rate: LOAD.soakRate,
      timeUnit: '1s',
      duration: LOAD.soakDuration,
      preAllocatedVUs: LOAD.preAllocatedVUs,
      maxVUs: LOAD.maxVUs,
    },
    // Continuous control-plane churn — this is what grows the event log over
    // time, so it is the interesting signal for a memory soak.
    create: {
      executor: 'constant-arrival-rate',
      exec: 'create',
      rate: LOAD.createRate,
      timeUnit: '1s',
      duration: LOAD.soakDuration,
      preAllocatedVUs: 5,
      maxVUs: 50,
    },
    // Low-rate event-log queries throughout the soak.
    verify: {
      executor: 'constant-arrival-rate',
      exec: 'verify',
      rate: SOAK.verifyRate,
      timeUnit: '1s',
      duration: LOAD.soakDuration,
      preAllocatedVUs: 2,
      maxVUs: 20,
    },
    retrieve: {
      executor: 'constant-arrival-rate',
      exec: 'retrieve',
      rate: SOAK.retrieveRate,
      timeUnit: '1s',
      duration: LOAD.soakDuration,
      preAllocatedVUs: 2,
      maxVUs: 20,
    },
  },
  thresholds: {
    // --- THE soak gate: data-plane latency + errors ------------------------------
    // p95/p99 of the match hot path over the WHOLE soak must stay under the bound,
    // and match errors must stay low (a connection/fd leak over hours trips this).
    'http_req_duration{op:match}': [`p(99)<${LIMITS.p99}`, `p(95)<${LIMITS.p95}`],
    'http_req_failed{op:match}': [`rate<${LIMITS.errorRate}`],
    // Control-plane churn must not start erroring either.
    'http_req_failed{op:create}': [`rate<${LIMITS.errorRate}`],
    // Every scenario's checks (match 200, create 201, verify 202, retrieve 200)
    // must pass — a systemic control-plane failure (e.g. verify starts 406-ing)
    // drops this below the gate and fails the soak loudly.
    'checks': [`rate>${LIMITS.checkRate}`],
    // --- MEASURE, do NOT gate ------------------------------------------------------
    // verify / retrieve latency against a full log is EXPECTED to be higher than a
    // sub-ms match and is NOTIFY-ONLY (no history yet). Declare always-pass
    // thresholds purely to MATERIALISE the p50/p95/p99 + counts for handleSummary.
    'http_req_duration{op:verify}': ['p(50)>=0', 'p(95)>=0', 'p(99)>=0'],
    'http_reqs{op:verify}': ['count>=0'],
    'http_req_duration{op:retrieve}': ['p(50)>=0', 'p(95)>=0', 'p(99)>=0'],
    'http_reqs{op:retrieve}': ['count>=0'],
    // Per-window sub-metrics for all three arms (notify-only): the latency series
    // the drift figures are derived from.
    ...windowThresholds(),
    'http_reqs{op:match}': ['count>=0'],
    'http_req_failed{op:verify}': ['rate>=0'],
    'http_req_failed{op:retrieve}': ['rate>=0'],
    // Materialise the match transport-error counter so handleSummary always emits
    // it (present as 0 when no transport errors occurred — the healthy case).
    'soak_match_transport_errors': ['count>=0'],
  },
};

export function setup() {
  seedExpectations();
}

export function match() {
  const res = getSimple({ win: windowTag() });
  // Record ONLY genuine transport failures (error_code != 0 / status 0). A 404 is
  // a completed response (error_code 0) and is NOT counted here — it is already in
  // http_req_failed{op:match}. Keeping the two separate is what lets the soak step
  // distinguish a dead SUT from a SUT answering 404s.
  if (res.error_code && res.error_code !== 0) {
    matchTransportErrors.add(1);
  }
}

export function create() {
  createSimpleExpectation();
}

export function verify() {
  verifyLog(windowTag());
}

export function retrieve() {
  retrieveLog(windowTag());
}

export function teardown() {
  resetMockServer();
}

// Round to `d` decimals; null-safe (a missing metric stays null so the consumer
// can tell "not measured" from "measured zero").
function round(v, d = 3) {
  if (v === undefined || v === null || Number.isNaN(v)) return null;
  const f = Math.pow(10, d);
  return Math.round(v * f) / f;
}

// Emit the machine-readable soak result. This is uploaded as its OWN artifact
// (perf-soak.json) and is DELIBERATELY not shaped like the daily regression
// result: the daily perf-test-compare.sh must never ingest it, because no soak
// budget keys exist (only the absolute match p95/p99 gates are derived; feeding soak
// metrics to the baseline compare is still deferred). The soak STEP posts a
// human annotation from this and the perf agent's occupancy samples. The shape is
// chosen so a future `.soak` enumeration in compare.sh maps cleanly to
// `soak.<arm>.<metric>` budget keys (reported by this unit for later sequencing).
export function handleSummary(data) {
  const stat = (key, s) => {
    const m = data.metrics[key];
    return m && m.values ? round(m.values[s]) : null;
  };
  const count = (key) => {
    const m = data.metrics[key];
    return m && m.values ? round(m.values.count, 0) : 0;
  };
  const rate = (key) => {
    const m = data.metrics[key];
    return m && m.values ? round(m.values.rate, 5) : null;
  };

  // One arm's latency per window, in run order. A window that carried no request
  // reports null percentiles, never a zero that would read as a measurement.
  const windowsFor = (op) => {
    const series = [];
    for (let i = 0; i < WINDOW_COUNT; i++) {
      const key = `http_req_duration{op:${op},win:${windowName(i)}}`;
      const samples = count(key);
      const at = (s) => (samples > 0 ? stat(key, s) : null);
      series.push({
        index: i,
        start_s: round(i * WINDOW_SEC, 3),
        end_s: round(i === WINDOW_COUNT - 1 ? DURATION_SEC : (i + 1) * WINDOW_SEC, 3),
        samples,
        p50_ms: at('p(50)'),
        p95_ms: at('p(95)'),
        p99_ms: at('p(99)'),
      });
    }
    return series;
  };

  const soak = {
    proto: SOAK.proto,
    duration_s: round(DURATION_SEC, 0),
    window_s: round(WINDOW_SEC, 3),
    warmup_s: round(WARMUP_SEC, 3),
    window_count: WINDOW_COUNT,
    // The rates and gates this script ran with (not what the step meant to pass in).
    rates_rps: {
      match: LOAD.soakRate,
      create: LOAD.createRate,
      verify: SOAK.verifyRate,
      retrieve: SOAK.retrieveRate,
    },
    gates: { match_p95_ms: LIMITS.p95, match_p99_ms: LIMITS.p99 },
    match: {
      samples: count('http_reqs{op:match}'),
      p50_ms: stat('http_req_duration{op:match}', 'p(50)'),
      p95_ms: stat('http_req_duration{op:match}', 'p(95)'),
      p99_ms: stat('http_req_duration{op:match}', 'p(99)'),
      error_rate: rate('http_req_failed{op:match}'),
      // TRANSPORT errors only (see the Counter's definition). error_rate above
      // counts completed non-2xx responses (404s); this counts requests that never
      // completed. The soak step reads BOTH to classify a high error_rate as
      // "SUT answering 404s" (this ~0) vs "SUT down" (this high).
      transport_errors: count('soak_match_transport_errors'),
      windows: windowsFor('match'),
    },
    // Event-log query cost, with the same per-window series as the
    // match arm so the scans can be read against how long the log has been full.
    verify: {
      samples: count('http_reqs{op:verify}'),
      p50_ms: stat('http_req_duration{op:verify}', 'p(50)'),
      p95_ms: stat('http_req_duration{op:verify}', 'p(95)'),
      p99_ms: stat('http_req_duration{op:verify}', 'p(99)'),
      error_rate: rate('http_req_failed{op:verify}'),
      windows: windowsFor('verify'),
    },
    retrieve: {
      samples: count('http_reqs{op:retrieve}'),
      p50_ms: stat('http_req_duration{op:retrieve}', 'p(50)'),
      p95_ms: stat('http_req_duration{op:retrieve}', 'p(95)'),
      p99_ms: stat('http_req_duration{op:retrieve}', 'p(99)'),
      error_rate: rate('http_req_failed{op:retrieve}'),
      windows: windowsFor('retrieve'),
    },
  };

  const out = { soak };
  const json = JSON.stringify(out, null, 2);
  const result = {};
  result[SOAK.resultPath] = json;
  result.stdout = `\nsoak result (${SOAK.proto}):\n${json}\n`;
  return result;
}
