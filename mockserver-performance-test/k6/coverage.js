// Path-coverage arms — request paths the regression, sweep and proxy scripts never
// drive. Run by perf-test-run.sh's path-coverage phase; each result lands under
// .path_coverage.arms in perf-result.json and is compared NOTIFY-ONLY.
//
// One k6 invocation per mode (K6_COV_MODE), because the modes need incompatible
// process-global options (noConnectionReuse, a client certificate, HTTP_PROXY):
//   churn / churn_mtls
//              noConnectionReuse: every request is a fresh TCP+TLS handshake, without
//              (tls_churn) or with (mtls_churn) a client certificate. Reports request
//              AND handshake percentiles and the handshakes completed per second.
//   keepalive / keepalive_mtls
//              reused connections: tls_keepalive vs mtls_keepalive, the per-request
//              client-certificate cost on a server left at its default
//              ClientAuth.OPTIONAL. The run step drives each pair as two concurrent
//              k6 containers, so both halves see the same server conditions.
//   matchers   JSONPath / XPath / JSON-schema body matchers, each scanning
//              COVERAGE.matcherCandidates expectations on plain HTTP.
//   capture   HTTP_PROXY at the SUT: absolute-URI proxy load (capture_proxy) while
//              the SUT persists recorded requests to disk; the run step reads the
//              event-log ring and dropped-event counters around it.
//   download   HTTP_PROXY at the SUT: large proxied (non-SSE) response bodies
//              (download_<n>mib); the run step samples RSS and direct memory.
//
// Measured-window shape is regression.js's: warmup, staggered starts,
// preAllocatedVUs == maxVUs, a settle window tagged op:<op>_settle and excluded from
// the percentiles, and MIN_TAIL_SAMPLES suppression.
//
//   k6 run -e K6_COV_MODE=matchers -e K6_COV_HTTP_URL=http://localhost:1080 .../coverage.js
//
import exec from 'k6/execution';
import { Counter } from 'k6/metrics';
import { COVERAGE } from './lib/config.js';
import {
  COVERAGE_MATCHER_TYPES,
  seedCoverage,
  getCoverage,
  postCoverageMatcher,
  verifyCoverageTls,
  verifyCoverageMatchers,
  verifyCoverageProxy,
} from './lib/expectations.js';

const MODE = COVERAGE.mode;
const MIN_TAIL_SAMPLES = 30;

// Handshakes are COUNTED (a request whose tls_handshaking > 0), not inferred from
// noConnectionReuse, so handshakes_per_s is an observation.
const handshakes = new Counter('cov_handshakes');
const downloadBytes = new Counter('cov_download_bytes');

function toSeconds(d) {
  const str = String(d).trim();
  const tokenRe = /(\d+)(ms|s|m|h)/g;
  let total = 0;
  let matched = false;
  let token;
  while ((token = tokenRe.exec(str)) !== null) {
    matched = true;
    total += Number(token[1]) * { ms: 0.001, s: 1, m: 60, h: 3600 }[token[2]];
  }
  if (!matched) {
    throw new Error(`toSeconds: cannot parse duration "${d}"`);
  }
  return total;
}

function round(v, dp = 3) {
  if (v === undefined || v === null || Number.isNaN(v) || !Number.isFinite(v)) {
    return null;
  }
  const f = 10 ** dp;
  return Math.round(v * f) / f;
}

const N = COVERAGE.matcherCandidates;
const DOWNLOAD_MIB = Math.round(COVERAGE.downloadBytes / (1024 * 1024));

// Each arm's `exec` names one of the exported functions below (k6 resolves a
// scenario's exec by export name).
const WITH_CERT = MODE === 'churn_mtls' || MODE === 'keepalive_mtls';
if (WITH_CERT && !(COVERAGE.clientCertPath && COVERAGE.clientKeyPath)) {
  throw new Error(`coverage.js ${MODE}: K6_COV_CLIENT_CERT and K6_COV_CLIENT_KEY are required`);
}
const TLS_BASE = COVERAGE.tlsUrl;
let ARMS;
if (MODE === 'churn') {
  ARMS = [{ op: 'tls_churn', exec: 'tlsChurnOp', url: `${TLS_BASE}/cov/tls`, tls: true, rate: COVERAGE.churnRate }];
} else if (MODE === 'churn_mtls') {
  ARMS = [{ op: 'mtls_churn', exec: 'mtlsChurnOp', url: `${TLS_BASE}/cov/mtls`, tls: true, rate: COVERAGE.churnRate }];
} else if (MODE === 'keepalive') {
  ARMS = [{ op: 'tls_keepalive', exec: 'tlsKeepaliveOp', url: `${TLS_BASE}/cov/tls`, rate: COVERAGE.keepaliveRate }];
} else if (MODE === 'matchers') {
  ARMS = COVERAGE_MATCHER_TYPES.map((type) => ({ op: `${type}_${N}`, exec: `${type}Op`, matcher: type, rate: COVERAGE.matcherRate }));
} else if (MODE === 'keepalive_mtls') {
  ARMS = [{ op: 'mtls_keepalive', exec: 'mtlsKeepaliveOp', url: `${TLS_BASE}/cov/mtls`, rate: COVERAGE.keepaliveRate }];
} else if (MODE === 'capture') {
  ARMS = [{
    op: 'capture_proxy', exec: 'captureProxyOp', url: `http://${COVERAGE.upstreamHost}/simple`,
    rate: COVERAGE.captureRate, vus: COVERAGE.captureVUs,
  }];
} else if (MODE === 'download') {
  if (!COVERAGE.downloadUrl) {
    throw new Error('coverage.js download mode: K6_COV_DOWNLOAD_URL is empty');
  }
  // heavy: an MB-scale body is never driven at the warmup rate (the regression.js
  // large_* rule); its cold first cohort falls inside the excluded settle window.
  ARMS = [{
    op: `download_${DOWNLOAD_MIB}mib`, exec: 'downloadOp', url: COVERAGE.downloadUrl, heavy: true, download: true,
    rate: COVERAGE.downloadRate, timeUnit: COVERAGE.downloadTimeUnit, vus: COVERAGE.downloadVUs,
  }];
} else {
  throw new Error(`coverage.js: unknown K6_COV_MODE "${MODE}" (expected churn, churn_mtls, keepalive, keepalive_mtls, matchers, capture or download)`);
}

function armRequest(arm, tags) {
  if (arm.matcher) {
    return postCoverageMatcher(arm.matcher, tags.op, tags);
  }
  const params = arm.download ? { responseType: 'none', timeout: '120s' } : {};
  return getCoverage(arm.url, tags.op, tags, params);
}

for (const arm of ARMS) {
  if (!arm.heavy) {
    arm.warm = () => armRequest(arm, { op: 'warmup' });
  }
}

const OPS = ARMS.map((a) => a.op);
const WARMED = ARMS.some((a) => a.warm);
const WARMUP_SEC = WARMED ? toSeconds(COVERAGE.warmup) : 0;
const STAGGER_SEC = toSeconds(COVERAGE.stagger);
const SETTLE_SEC = toSeconds(COVERAGE.settle);
const DURATION = MODE === 'matchers' ? COVERAGE.matcherDuration : COVERAGE.duration;
const DURATION_SEC = toSeconds(DURATION);
const MEASURED_WINDOW_SEC = DURATION_SEC - SETTLE_SEC;
if (MEASURED_WINDOW_SEC <= 0) {
  throw new Error(`coverage.js: K6_COV_SETTLE (${COVERAGE.settle}) must be shorter than the ${MODE} duration (${DURATION})`);
}
const OFFERED = Object.fromEntries(ARMS.map((a) => [a.op, a.rate / toSeconds(a.timeUnit || '1s')]));

function startOffsetSec(op) {
  return WARMUP_SEC + OPS.indexOf(op) * STAGGER_SEC;
}

// Scenario startTimes count from the END of setup(), but the test-run clock also
// counts setup, which here seeds hundreds of expectations. Measuring from setup's
// returned timestamp keeps the settle boundary where the scenario really starts.
function phaseTag(op, setupEndMs) {
  const elapsedSec = setupEndMs
    ? (Date.now() - setupEndMs) / 1000
    : exec.instance.currentTestRunDuration / 1000;
  return elapsedSec >= startOffsetSec(op) + SETTLE_SEC ? op : `${op}_settle`;
}

function coverageThresholds() {
  const t = {};
  for (const arm of ARMS) {
    const op = arm.op;
    t[`http_req_duration{op:${op}}`] = ['p(50)>=0', 'p(95)>=0', 'p(99)>=0'];
    t[`http_req_failed{op:${op}}`] = ['rate>=0'];
    t[`http_reqs{op:${op}}`] = ['count>=0'];
    t[`http_reqs{op:${op}_settle}`] = ['count>=0'];
    t[`dropped_iterations{scenario:${op}}`] = ['count>=0'];
    if (arm.tls) {
      t[`http_req_tls_handshaking{op:${op}}`] = ['p(50)>=0', 'p(99)>=0'];
      t[`cov_handshakes{op:${op}}`] = ['count>=0'];
    }
    if (arm.download) {
      t[`cov_download_bytes{op:${op}}`] = ['count>=0'];
    }
  }
  return t;
}

function measuredScenario(arm) {
  const vus = arm.vus || COVERAGE.vus;
  return {
    executor: 'constant-arrival-rate',
    exec: arm.exec,
    rate: arm.rate,
    timeUnit: arm.timeUnit || '1s',
    duration: DURATION,
    startTime: `${startOffsetSec(arm.op)}s`,
    preAllocatedVUs: vus,
    maxVUs: vus,
  };
}

const tlsAuth = WITH_CERT
  ? [{ cert: open(COVERAGE.clientCertPath), key: open(COVERAGE.clientKeyPath) }]
  : undefined;

const scenarios = Object.fromEntries(ARMS.map((arm) => [arm.op, measuredScenario(arm)]));
if (WARMED) {
  // Each warmup iteration touches every warmable arm once, so the rate is a quarter
  // of the SLOWEST arm's measured rate (regression.js uses rate/4 the same way).
  const slowest = Math.min(...ARMS.filter((a) => a.warm).map((a) => OFFERED[a.op]));
  scenarios.warmup = {
    executor: 'constant-arrival-rate',
    exec: 'warmupOp',
    rate: Math.max(10, Math.round(slowest / 4)),
    timeUnit: '1s',
    duration: COVERAGE.warmup,
    preAllocatedVUs: 50,
    maxVUs: 50,
  };
}

export const options = {
  // True for the rig's self-signed SUT aliases (config.js COVERAGE); overridable.
  insecureSkipTLSVerify: COVERAGE.insecureSkipTLSVerify,
  noConnectionReuse: MODE === 'churn' || MODE === 'churn_mtls',
  ...(tlsAuth ? { tlsAuth } : {}),
  summaryTrendStats: ['avg', 'min', 'med', 'p(50)', 'p(90)', 'p(95)', 'p(99)', 'max'],
  scenarios,
  thresholds: coverageThresholds(),
};

export function setup() {
  if (MODE !== 'capture' && MODE !== 'download') {
    seedCoverage(MODE === 'matchers');
    verifyCoverageTls(WITH_CERT);
    if (MODE === 'matchers') {
      verifyCoverageMatchers();
    }
  } else if (MODE === 'capture') {
    verifyCoverageProxy(ARMS[0].url);
  } else {
    const res = verifyCoverageProxy(ARMS[0].url, { responseType: 'binary', timeout: '120s' });
    const got = res.body ? res.body.byteLength : 0;
    if (got !== COVERAGE.downloadBytes) {
      throw new Error(`coverage download: proxied body was ${got} bytes, expected ${COVERAGE.downloadBytes} - the SUT is not relaying the upstream body intact`);
    }
  }
  return { setupEndMs: Date.now() };
}

export function warmupOp() {
  for (const arm of ARMS) {
    if (arm.warm) {
      arm.warm();
    }
  }
}

function drive(arm, data) {
  const op = phaseTag(arm.op, data && data.setupEndMs);
  const res = armRequest(arm, { op });
  if (op !== arm.op) {
    return;
  }
  if (arm.tls && res.timings && res.timings.tls_handshaking > 0) {
    handshakes.add(1, { op });
  }
  if (arm.download && res.status === 200) {
    const len = Number(res.headers['Content-Length'] || 0);
    if (len > 0) {
      downloadBytes.add(len, { op });
    }
  }
}

const ARM_BY_EXEC = Object.fromEntries(ARMS.map((a) => [a.exec, a]));
export function tlsChurnOp(data) { drive(ARM_BY_EXEC.tlsChurnOp, data); }
export function mtlsChurnOp(data) { drive(ARM_BY_EXEC.mtlsChurnOp, data); }
export function tlsKeepaliveOp(data) { drive(ARM_BY_EXEC.tlsKeepaliveOp, data); }
export function mtlsKeepaliveOp(data) { drive(ARM_BY_EXEC.mtlsKeepaliveOp, data); }
export function jsonpathOp(data) { drive(ARM_BY_EXEC.jsonpathOp, data); }
export function xpathOp(data) { drive(ARM_BY_EXEC.xpathOp, data); }
export function jsonschemaOp(data) { drive(ARM_BY_EXEC.jsonschemaOp, data); }
export function captureProxyOp(data) { drive(ARM_BY_EXEC.captureProxyOp, data); }
export function downloadOp(data) { drive(ARM_BY_EXEC.downloadOp, data); }

export function handleSummary(data) {
  const arms = {};
  for (const arm of ARMS) {
    const op = arm.op;
    const dur = data.metrics[`http_req_duration{op:${op}}`];
    if (!dur || !dur.values) {
      continue;
    }
    const metricCount = (name) => {
      const m = data.metrics[name];
      return m && m.values ? m.values.count : 0;
    };
    const count = metricCount(`http_reqs{op:${op}}`);
    // An arm with no measured request (setup() aborted, or the scenario never ran)
    // measured nothing: omit it rather than emit a zero error rate for it.
    if (count === 0) {
      continue;
    }
    const failed = data.metrics[`http_req_failed{op:${op}}`];
    const throughput = count / MEASURED_WINDOW_SEC;
    const offered = OFFERED[op];
    const enough = count >= MIN_TAIL_SAMPLES;
    const v = dur.values;
    const out = {
      p50_ms: enough ? round(v['p(50)'] !== undefined ? v['p(50)'] : v.med) : null,
      p95_ms: enough ? round(v['p(95)']) : null,
      p99_ms: enough ? round(v['p(99)']) : null,
      sample_count: count,
      throughput_rps: round(throughput),
      offered_rps: round(offered, 4),
      delivery_ratio: enough && offered > 0 ? round(throughput / offered, 4) : null,
      dropped_iterations: metricCount(`dropped_iterations{scenario:${op}}`),
      error_rate: failed && failed.values ? round(failed.values.rate, 5) : null,
      settle_excluded: metricCount(`http_reqs{op:${op}_settle}`),
      measured_window_s: round(MEASURED_WINDOW_SEC),
    };
    if (arm.tls) {
      const hs = data.metrics[`http_req_tls_handshaking{op:${op}}`];
      const hv = hs && hs.values ? hs.values : {};
      out.handshake_p50_ms = enough ? round(hv['p(50)']) : null;
      out.handshake_p99_ms = enough ? round(hv['p(99)']) : null;
      out.handshakes_per_s = round(metricCount(`cov_handshakes{op:${op}}`) / MEASURED_WINDOW_SEC);
    }
    if (arm.download) {
      out.body_bytes = COVERAGE.downloadBytes;
      out.received_mib_per_s = round(metricCount(`cov_download_bytes{op:${op}}`) / MEASURED_WINDOW_SEC / 1048576);
    }
    arms[op] = out;
  }
  const json = JSON.stringify({ mode: MODE, arms }, null, 2);
  return {
    [COVERAGE.resultPath]: json,
    stdout: `\ncoverage result (mode=${MODE}):\n${json}\n`,
  };
}
