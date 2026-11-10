// Regression scenario — the periodic-pipeline workhorse. Measures response
// latency across the four core behaviours under a FIXED offered rate
// (constant-arrival-rate), so the recorded numbers are comparable across daily
// runs and feed the stored-history baseline comparison (perf-test-compare.sh).
//
// Behaviours (each its own scenario, tagged op:<name>):
//   match             — static mock match + response (data-plane hot path)
//   forward           — forward action to a DEDICATED upstream MockServer
//   template          — Velocity response template (dynamic response generation)
//   template_mustache — Mustache response template
//   template_javascript — GraalJS response template — only on a -graaljs image,
//                       enabled via K6_REG_JS_TEMPLATE
//   large             — ~4 KB JSON body decode + match
//   large_1mb         — 1 MB JSON body decode + marker match
//   large_10mb        — 10 MB JSON body decode + marker match
//   large_file        — response served from a file (FileBodyMaterialiser) —
//                       enabled via K6_REG_FILE_BODY_PATH
//
// Each arm's op becomes the metric key <op>_<proto>, and perf-test-compare.sh
// consumes it with NO change (its metric loop iterates over .behaviours), as a
// notify-only (non-gating) metric until it earns >=10 clean runs of history.
//
// A warmup scenario (op:warmup) runs first so JIT/GC reach steady state; the
// measured scenarios start after K6_REG_WARMUP and only their op submetrics feed
// the result JSON. Run once over HTTP and once over HTTPS+H2 (BASE_URL scheme +
// PROTO label); perf-test-run.sh merges the two result files.
//
//   k6 run mockserver-performance-test/k6/regression.js
//   k6 run -e BASE_URL=https://localhost:1080 -e PROTO=https_h2 .../regression.js
//
// Measured-window hygiene. The recorded percentiles must describe the SERVER, not a client-side rig
// artefact. Three coordinated defences, none of which throws away steady-state
// data:
//   1. STAGGER — the four scenarios start K6_REG_STAGGER apart (op index x gap)
//      so their VU-allocation / connection-open transients do not superimpose
//      into one connection storm on the shared, core-limited SUT.
//   2. PRE-ALLOCATE — preAllocatedVUs is sized so the executor never allocates
//      VUs (each opening a fresh connection) inside the measured window; the
//      brief start transient is absorbed by the pre-built pool.
//   3. SETTLE — the first K6_REG_SETTLE of each scenario's window is tagged
//      op:<op>_settle and excluded from the latency percentiles. Load still runs
//      (the transient is traversed, not skipped); only the known start artefact
//      is dropped. dropped_iterations still counts the WHOLE scenario, so client
//      starvation is never hidden, and settle_excluded is reported for audit.
// On HTTPS+H2 preAllocatedVUs is also the connection/handshake count, so a naive
// raise would relocate the storm into TLS-handshake `blocked` time. Stagger +
// settle handle that: any handshake burst falls inside the excluded settle
// window and behind reused H2 connections by the time measurement starts.
//
import exec from 'k6/execution';
import { CONFIG, REGRESSION } from './lib/config.js';
import {
  seedRegression,
  verifyRegressionArms,
  resetMockServer,
  getSimple,
  getForward,
  getTemplated,
  getTemplatedMustache,
  getTemplatedJavaScript,
  postLargeBody,
  postLarge1mb,
  postLarge10mb,
  getLargeFile,
} from './lib/expectations.js';

// Each measured behaviour is an "arm": an op name (which becomes the metric key
// <op>_<proto>, so perf-test-compare.sh picks it up with NO script change — its
// metric loop iterates .behaviours), the exec function k6 runs, and an optional
// per-arm offered rate / VU pool (the byte-heavy `large_*` arms run slower and
// with a small pool so the byte-rate and run length stay bounded). Optional arms
// (JavaScript template, file-backed body) are appended only when their
// server-side capability is enabled; seedRegression seeds exactly the same set.
//
// `warm` (when present) touches the arm's path during the warmup window so it is
// JIT-warm before measurement (a cold heavy path at FULL rate piles
// up VUs into a connection storm). CRITICAL: the warmup scenario runs at ~50/s
// with maxVUs:100 — far above any per-arm throttle — so an arm warmed there is
// driven at 50/s regardless of its measured rate. That is fine for the small-body
// full-rate arms (match/forward/template*/large-4KB), but it would drive the
// MB-scale `large_1mb`/`large_10mb`/`large_file` arms at 50/s for the whole 30 s
// warmup and, because the ~115.5k-entry log ring does not evict inside that window,
// accumulate multiple GB of retained bodies and OOM the SUT before measurement
// even starts. So the MB-scale arms are DELIBERATELY not warmed (no `warm`): at
// <=2 rps a cold first cohort cannot form a connection storm, and it lands inside the excluded settle window anyway, so the
// measured percentiles are unaffected.
const ARMS = [
  { op: 'match', exec: 'matchOp', warm: () => getSimple({ op: 'warmup' }) },
  { op: 'forward', exec: 'forwardOp', warm: () => getForward({ op: 'warmup' }) },
  { op: 'template', exec: 'templateOp', warm: () => getTemplated({ op: 'warmup' }) },
  {
    op: 'template_mustache', exec: 'templateMustacheOp',
    rate: REGRESSION.templateArmRate,
    warm: () => getTemplatedMustache({ op: 'warmup' }),
  },
  { op: 'large', exec: 'largeOp', warm: () => postLargeBody({ op: 'warmup' }) },
  {
    op: 'large_1mb', exec: 'large1mbOp', heavy: true,
    rate: REGRESSION.large1mbRate, timeUnit: REGRESSION.large1mbTimeUnit, vus: REGRESSION.large1mbVUs,
    // heavy: no warm — see the CRITICAL note above (MB-scale body would OOM the warmup)
  },
  {
    op: 'large_10mb', exec: 'large10mbOp', heavy: true,
    rate: REGRESSION.large10mbRate, timeUnit: REGRESSION.large10mbTimeUnit, vus: REGRESSION.large10mbVUs,
    // heavy: no warm — see the CRITICAL note above (MB-scale body would OOM the warmup)
  },
];
if (REGRESSION.jsTemplate) {
  // GraalJS carries a Truffle cold-start, so it IS warmed — but at the reduced
  // template-arm rate, and the warmup itself only ever drives it at ~50/s with a
  // tiny (few-KB) response, so there is no memory concern from warming it.
  ARMS.push({
    op: 'template_javascript', exec: 'templateJavaScriptOp',
    rate: REGRESSION.templateArmRate,
    warm: () => getTemplatedJavaScript({ op: 'warmup' }),
  });
}
if (REGRESSION.fileBodyPath) {
  ARMS.push({
    op: 'large_file', exec: 'largeFileOp', heavy: true,
    rate: REGRESSION.fileBodyRate, timeUnit: REGRESSION.fileBodyTimeUnit, vus: REGRESSION.fileBodyVUs,
    // heavy: no warm — see the CRITICAL note above (MB-scale file response would OOM the warmup)
  });
}
const OPS = ARMS.map((a) => a.op);

// Fail-loud contract check on the warm/heavy invariant. `heavy: true` marks the
// MB-scale arms that must NOT be warmed (their body at the ~50/s warmup rate would
// OOM the log ring). Every OTHER arm MUST declare a `warm()` — so a typo like
// `warmm:` on a full-rate arm cannot silently un-warm it and reintroduce the
// Finding-3 connection-storm tail. Both violations throw at init (k6 aborts before
// any load), turning a silent regression into a loud one.
for (const arm of ARMS) {
  if (arm.heavy && arm.warm) {
    throw new Error(`regression.js ARMS: '${arm.op}' is heavy and must NOT declare warm (warming an MB-scale arm at the ~50/s warmup rate OOMs the SUT log ring)`);
  }
  if (!arm.heavy && typeof arm.warm !== 'function') {
    throw new Error(`regression.js ARMS: '${arm.op}' must declare a warm() (every non-heavy arm is JIT-warmed; a missing warm would reintroduce the Finding-3 cold-start tail)`);
  }
}
// Per-arm offered rate (req/s). The byte-heavy large_* arms run slower than the
// default (and some below 1 rps via a multi-second timeUnit), so delivery_ratio
// and offered_rps in the summary must use the arm's OWN effective rate
// (rate / timeUnit-in-seconds), not the global REGRESSION.rate.
const OFFERED = Object.fromEntries(
  ARMS.map((a) => [a.op, (a.rate || REGRESSION.rate) / toSeconds(a.timeUnit || '1s')]),
);

// Minimum measured request count below which the LATENCY and DELIVERY statistics are
// not something the data can support, so they are suppressed (emitted null) rather
// than reported as if real. Two artefacts this stops, both observed on the sub-1-rps
// MB-scale arms:
//   - p95/p99 collapse to ~the max at low N (a p95 needs ~20 samples to be distinct
//     from the max, a p99 far more);
//   - p50 and delivery_ratio become NONSENSE when the handful of samples are mostly
//     FAILURES: large_10mb (N≈12) reported a p50 of 0 ms (fast connection failures
//     recorded as ~0) and a delivery_ratio of 1.09 (achieved > offered is impossible
//     — a settle-window boundary artefact at tiny N). A number that is physically
//     impossible must not be published as a measurement.
// So below this floor p50_ms/p95_ms/p99_ms AND delivery_ratio are all null; the HONEST
// facts an arm can always stand behind — sample_count, error_rate, throughput_rps,
// offered_rps, dropped_iterations — are always emitted, and sample_count makes it
// visible WHY the rest are null. perf-test-compare.sh filters null-valued metrics, so
// a suppressed figure is simply not compared, never compared as a bogus statistic.
// large_10mb (N≈12) stays a coverage/error-rate arm; large_1mb/large_file clear the
// bar once the SUT is healthy (the byte-budget guard, see config.js) and report real
// percentiles. This is the MIN_TAIL_SAMPLES rule the plan already mandates, applied to
// the delivery figures it was being violated in spirit by.
const MIN_TAIL_SAMPLES = 30;

// Sum a k6 duration string to seconds (supports compound forms like "1m30s").
function toSeconds(d) {
  const str = String(d).trim();
  const tokenRe = /(\d+)(ms|s|m|h)/g;
  let total = 0;
  let matched = false;
  let token;
  while ((token = tokenRe.exec(str)) !== null) {
    matched = true;
    const value = Number(token[1]);
    total += value * { ms: 0.001, s: 1, m: 60, h: 3600 }[token[2]];
  }
  if (!matched) {
    throw new Error(`toSeconds: cannot parse duration "${d}"`);
  }
  return total;
}

function round(v, dp = 3) {
  if (v === undefined || v === null || Number.isNaN(v)) {
    return null;
  }
  const f = 10 ** dp;
  return Math.round(v * f) / f;
}

// Derived timing (seconds). Each measured scenario starts at warmup + its
// stagger offset; its measured percentile window opens SETTLE seconds later.
const WARMUP_SEC = toSeconds(REGRESSION.warmup);
const STAGGER_SEC = toSeconds(REGRESSION.stagger);
const SETTLE_SEC = toSeconds(REGRESSION.settle);
const DURATION_SEC = toSeconds(REGRESSION.duration);
const MEASURED_WINDOW_SEC = DURATION_SEC - SETTLE_SEC;

function startOffsetSec(op) {
  return WARMUP_SEC + OPS.indexOf(op) * STAGGER_SEC;
}

// Requests before a scenario's settle boundary are tagged op:<op>_settle so they
// are excluded from the measured latency submetric. Uses whole-test elapsed time
// (ms) against the scenario's known measured-window start — robust regardless of
// which VU runs the iteration.
function phaseTag(op) {
  const elapsedSec = exec.instance.currentTestRunDuration / 1000;
  const measuredStartSec = startOffsetSec(op) + SETTLE_SEC;
  return elapsedSec >= measuredStartSec ? op : `${op}_settle`;
}

// Materialise per-op submetrics in the summary by declaring (always-passing)
// thresholds. The >=0 expressions never fail (notify-only) but force k6 to
// compute and expose p(50)/p(95)/p(99), the failed-rate, and the request count
// per op so handleSummary can read them.
function regressionThresholds(ops) {
  const t = {};
  for (const op of ops) {
    t[`http_req_duration{op:${op}}`] = ['p(50)>=0', 'p(95)>=0', 'p(99)>=0'];
    t[`http_req_failed{op:${op}}`] = ['rate>=0'];
    t[`http_reqs{op:${op}}`] = ['count>=0'];
    // throughput_rps = completed/duration is NOT pinned to the offered rate: k6
    // drops iterations when its VU pool cannot launch them on time, so a low
    // number is ambiguous — the server got slower OR the client ran out of VUs.
    // Materialise the per-scenario dropped_iterations counter (scenario name ==
    // the op) so handleSummary can report it alongside offered_rps and make that
    // ambiguity legible instead of hidden inside throughput_rps.
    t[`dropped_iterations{scenario:${op}}`] = ['count>=0'];
    // Materialise the excluded settle-window request count so the summary can
    // report exactly how many requests the settle exclusion discarded — the
    // exclusion is auditable, not silent.
    t[`http_reqs{op:${op}_settle}`] = ['count>=0'];
  }
  return t;
}

function measuredScenario(arm) {
  // preAllocatedVUs == maxVUs per arm: the Finding-3 no-mid-run-allocation
  // invariant, applied to each arm's own (possibly smaller) pool. The byte-heavy
  // large_* arms run a lower rate + smaller pool so their in-flight bytes stay
  // bounded; if a transient exceeds the pool k6 DROPS (counted), never ramps.
  const vus = arm.vus || REGRESSION.preAllocatedVUs;
  return {
    executor: 'constant-arrival-rate',
    exec: arm.exec,
    rate: arm.rate || REGRESSION.rate,
    timeUnit: arm.timeUnit || '1s',
    duration: REGRESSION.duration,
    // Staggered so the scenarios' start transients do not superimpose.
    startTime: `${startOffsetSec(arm.op)}s`,
    preAllocatedVUs: vus,
    maxVUs: vus,
  };
}

export const options = {
  insecureSkipTLSVerify: CONFIG.insecureSkipTLSVerify,
  // handleSummary reads these stats off each submetric's `values`; p(50)/p(99)
  // are NOT in k6's default set, so declare them or they come back null.
  summaryTrendStats: ['avg', 'min', 'med', 'p(50)', 'p(90)', 'p(95)', 'p(99)', 'max'],
  scenarios: {
    // Low-rate warmup across all paths; op:warmup keeps it out of the measured
    // submetrics. Runs during [0, K6_REG_WARMUP].
    warmup: {
      executor: 'constant-arrival-rate',
      exec: 'warmupOp',
      rate: Math.max(10, Math.round(REGRESSION.rate / 4)),
      timeUnit: '1s',
      duration: REGRESSION.warmup,
      preAllocatedVUs: 10,
      maxVUs: 100,
    },
    // One measured scenario per arm (built from ARMS so adding an arm needs no
    // edit here). The scenario name IS the op, which handleSummary maps to the
    // <op>_<proto> metric key.
    ...Object.fromEntries(ARMS.map((arm) => [arm.op, measuredScenario(arm)])),
  },
  thresholds: regressionThresholds(OPS),
};

export function setup() {
  seedRegression();
  // Fail loudly NOW if an enabled optional arm (JavaScript / file-backed body)
  // cannot actually serve — better an aborted run than a silent 100%-error arm
  // baselined as a result.
  verifyRegressionArms();
}

export function warmupOp() {
  // Touch each WARMED path so it is JIT-warm before measurement. A full-rate path
  // left cold (the 4 KB body decode+match, and the GraalJS template with its
  // Truffle cold-start) runs hundreds of ms to seconds on its first cohort, piles
  // up VUs and produces a multi-second tail that MORE VUs only worsen (the
  // connection storm) — warming it here collapses that tail.
  //
  // Warm every NON-heavy arm. The `heavy: true` MB-scale large_* arms are skipped:
  // this warmup scenario runs at ~50/s (far above their <=2 rps throttle), so
  // warming them would drive MB bodies at 50/s for 30 s and OOM the SUT's log ring
  // before measurement (see the ARMS note). Their cold first cohort is instead
  // absorbed by the settle exclusion. The load-time contract check above guarantees
  // every non-heavy arm has a warm(), so this is not a silent skip.
  for (const arm of ARMS) {
    if (!arm.heavy) {
      arm.warm();
    }
  }
}

export function matchOp() {
  getSimple({ op: phaseTag('match') });
}

export function forwardOp() {
  getForward({ op: phaseTag('forward') });
}

export function templateOp() {
  getTemplated({ op: phaseTag('template') });
}

export function templateMustacheOp() {
  getTemplatedMustache({ op: phaseTag('template_mustache') });
}

export function templateJavaScriptOp() {
  getTemplatedJavaScript({ op: phaseTag('template_javascript') });
}

export function largeOp() {
  postLargeBody({ op: phaseTag('large') });
}

export function large1mbOp() {
  postLarge1mb({ op: phaseTag('large_1mb') });
}

export function large10mbOp() {
  postLarge10mb({ op: phaseTag('large_10mb') });
}

export function largeFileOp() {
  getLargeFile({ op: phaseTag('large_file') });
}

export function teardown() {
  resetMockServer();
}

// Emit the machine-readable result consumed by perf-test-compare.sh. Throughput
// is computed from the request count over the KNOWN measured window (not k6's
// whole-test rate, which would include the warmup window).
export function handleSummary(data) {
  const proto = REGRESSION.proto;
  // Throughput is measured over the POST-SETTLE window only: the settle-window
  // requests are tagged op:<op>_settle and excluded from http_reqs{op:<op>}, so
  // dividing by the full duration would undercount and depress delivery_ratio.
  const measuredWindowSec = MEASURED_WINDOW_SEC > 0 ? MEASURED_WINDOW_SEC : DURATION_SEC;
  const behaviours = {};
  for (const op of OPS) {
    const dur = data.metrics[`http_req_duration{op:${op}}`];
    const failed = data.metrics[`http_req_failed{op:${op}}`];
    const reqs = data.metrics[`http_reqs{op:${op}}`];
    if (!dur || !dur.values) {
      continue;
    }
    const count = reqs && reqs.values ? reqs.values.count : 0;
    const settleReqs = data.metrics[`http_reqs{op:${op}_settle}`];
    const settleExcluded = settleReqs && settleReqs.values ? settleReqs.values.count : 0;
    const dropped = data.metrics[`dropped_iterations{scenario:${op}}`];
    const droppedCount = dropped && dropped.values ? dropped.values.count : 0;
    const throughput = round(measuredWindowSec > 0 ? count / measuredWindowSec : 0);
    const offered = OFFERED[op] || REGRESSION.rate;
    // Suppress the latency AND delivery statistics for a low-sample arm: below
    // MIN_TAIL_SAMPLES measured requests the tail percentiles are ~the max, the p50 is
    // dominated by fast failures (the observed 0 ms for a 10 MB transfer), and
    // delivery_ratio drifts past 1.0 on the settle-window boundary — none is a real
    // statistic. Null-valued metrics are filtered by perf-test-compare.sh, so a
    // suppressed figure is simply not compared rather than compared as a bogus one.
    // sample_count + error_rate + throughput + offered + dropped are always emitted, so
    // a low-N arm is honestly a coverage/error-rate arm, not a percentile it cannot back.
    const enoughSamples = count >= MIN_TAIL_SAMPLES;
    behaviours[`${op}_${proto}`] = {
      p50_ms: enoughSamples ? round(dur.values['p(50)'] !== undefined ? dur.values['p(50)'] : dur.values.med) : null,
      p95_ms: enoughSamples ? round(dur.values['p(95)']) : null,
      p99_ms: enoughSamples ? round(dur.values['p(99)']) : null,
      // Raw measured count so a reader can see WHY a tail percentile is null (and
      // audit throughput). Kept for every arm, not just the suppressed ones.
      sample_count: round(count, 0),
      throughput_rps: throughput,
      // offered_rps + dropped_iterations make throughput_rps legible: a shortfall
      // of throughput below offered with dropped_iterations > 0 is a CLIENT (VU)
      // limit, not a server regression. delivery_ratio = achieved/offered is the
      // at-a-glance figure surfaced in the annotation. throughput_rps is recorded
      // but NOT budgeted (perf-test-compare.sh) until the shortfall is understood.
      offered_rps: offered,
      dropped_iterations: round(droppedCount, 0),
      // Suppressed with the percentiles below the sample floor: a delivery_ratio > 1
      // (observed 1.09 on large_10mb) is a small-N settle-boundary artefact, not a
      // measurement. Above the floor it is the at-a-glance achieved/offered figure.
      delivery_ratio: enoughSamples ? round(offered > 0 ? throughput / offered : null, 4) : null,
      error_rate: failed && failed.values ? round(failed.values.rate, 5) : 0,
      // Transparency for the settle exclusion: how many start-transient requests
      // were dropped from the percentiles, and over what window the rest were
      // measured. Lets a reader see the exclusion is a small, fixed slice.
      settle_excluded: round(settleExcluded, 0),
      measured_window_s: round(measuredWindowSec, 3),
    };
  }
  const out = { proto, behaviours };
  const json = JSON.stringify(out, null, 2);
  const result = {};
  result[REGRESSION.resultPath] = json;
  result.stdout = `\nregression result (${proto}):\n${json}\n`;
  return result;
}
