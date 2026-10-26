// Data-plane hooks A/B: one window against one SUT, which has no extension, the identity request filter (H1)
// or the identity response transformer (H2) loaded. Each request POSTs a body of K6_DP_SIZE bytes to
// /dp_ab/<size>, and its expectation answers with a body of the same size, so H1 copies a request body and H2
// a response body of that size. Driven by .buildkite/scripts/steps/lib/perf-dataplane-ab.sh, which runs one
// k6 process per (pair, size, arm) window so it can read the SUT's cgroup CPU between windows.
//
// setup() seeds the expectations, then refuses to measure (exit 107) unless the probe path shows that the
// SUT has exactly the hook K6_DP_ARM names, and the measured path answers the exact body with no hook mark.
//
//   k6 run -e BASE_URL=http://dp-ab-h1:1080 -e K6_DP_ARM=h1 -e K6_DP_SIZE=1kb dataplane_ab.js
//   k6 run -e BASE_URL=http://dp-ab-h1:1080 -e K6_DP_ARM=h1 -e K6_DP_MODE=warmup -e K6_DP_SIZES=1kb,1mb dataplane_ab.js
//
import exec from 'k6/execution';
import http from 'k6/http';
import { fail } from 'k6';
import { Rate } from 'k6/metrics';
import { CONFIG } from './lib/config.js';

function env(name, fallback) {
  const v = __ENV[name];
  return v === undefined || v === '' ? fallback : v;
}
function posInt(name, fallback) {
  const raw = env(name, String(fallback));
  if (!/^[1-9][0-9]*$/.test(raw)) throw new Error(`dataplane_ab.js: ${name}='${raw}' is not a positive integer`);
  return Number(raw);
}
function toSeconds(name, d) {
  const re = /(\d+)(ms|s|m|h)/g;
  let total = 0; let matched = false; let t;
  while ((t = re.exec(String(d))) !== null) {
    matched = true;
    total += Number(t[1]) * { ms: 0.001, s: 1, m: 60, h: 3600 }[t[2]];
  }
  if (!matched) throw new Error(`dataplane_ab.js: ${name}='${d}' is not a duration`);
  return total;
}
function round(v, dp = 3) {
  if (v === undefined || v === null || Number.isNaN(v)) return null;
  const f = 10 ** dp;
  return Math.round(v * f) / f;
}

// <n>kb or <n>mb, at most 8 MiB, which stays under MockServer's default 10 MiB maxRequestBodySize.
export function sizeBytes(size) {
  const m = /^([1-9][0-9]{0,3})(kb|mb)$/.exec(size);
  if (!m) throw new Error(`dataplane_ab.js: size '${size}' is not <n>kb or <n>mb`);
  const bytes = Number(m[1]) * (m[2] === 'kb' ? 1024 : 1024 * 1024);
  if (bytes > 8 * 1024 * 1024) throw new Error(`dataplane_ab.js: size '${size}' is over 8mb`);
  return bytes;
}

export function dpContent(label, bytes) {
  const head = `{"marker":"dp-ab-${label}","pad":"`;
  const tail = '"}';
  return head + 'x'.repeat(bytes - head.length - tail.length) + tail;
}

const ARMS = { none: null, h1: 'request-filter', h2: 'response-transformer' };
const PROBE_PATH = '/dp_ab/probe';
const PROBE_HEADER = 'x-dp-ab-hook';

const MODE = env('K6_DP_MODE', 'window');
if (MODE !== 'window' && MODE !== 'warmup') throw new Error(`dataplane_ab.js: K6_DP_MODE='${MODE}' must be window or warmup`);
const ARM = env('K6_DP_ARM', '');
if (!(ARM in ARMS)) throw new Error(`dataplane_ab.js: K6_DP_ARM='${ARM}' must be none, h1 or h2`);
const SIZES = (MODE === 'warmup' ? env('K6_DP_SIZES', '1kb,1mb') : env('K6_DP_SIZE', '1kb'))
  .split(',').map((s) => s.trim()).filter((s) => s);
if (SIZES.length === 0 || new Set(SIZES).size !== SIZES.length) throw new Error('dataplane_ab.js: the sizes must be distinct and not empty');
const BYTES = Object.fromEntries(SIZES.map((s) => [s, sizeBytes(s)]));
const RATE = posInt('K6_DP_RATE', 200);
const VUS = posInt('K6_DP_VUS', 40);
const DURATION_SEC = toSeconds('K6_DP_DURATION', env('K6_DP_DURATION', MODE === 'warmup' ? '20s' : '25s'));
const SETTLE_SEC = MODE === 'warmup' ? 0 : toSeconds('K6_DP_SETTLE', env('K6_DP_SETTLE', '5s'));
if (SETTLE_SEC >= DURATION_SEC) throw new Error('dataplane_ab.js: K6_DP_SETTLE must be shorter than K6_DP_DURATION');
const MEASURED_SEC = DURATION_SEC - SETTLE_SEC;
// Below this many measured requests a p95 / p99 is close to the max, so it is reported as null.
const MIN_TAIL_SAMPLES = 30;
const RESULT_PATH = env('K6_RESULT_PATH', 'dataplane-ab-result.json');

const REQUEST_BODY = Object.fromEntries(SIZES.map((s) => [s, dpContent(`request-${s}`, BYTES[s])]));
const RESPONSE_BODY = Object.fromEntries(SIZES.map((s) => [s, dpContent(`response-${s}`, BYTES[s])]));
const OP = MODE === 'warmup' ? 'warmup' : `${SIZES[0]}_${ARM}`;

const dpErrors = new Rate('dp_ab_errors');

export const options = {
  summaryTrendStats: ['avg', 'min', 'med', 'p(50)', 'p(95)', 'p(99)', 'max'],
  scenarios: {
    dp: {
      executor: 'constant-arrival-rate', exec: 'dpOp', rate: RATE, timeUnit: '1s',
      duration: `${DURATION_SEC}s`, preAllocatedVUs: VUS, maxVUs: VUS,
    },
  },
  thresholds: {
    [`http_req_duration{op:${OP}}`]: ['p(50)>=0', 'p(95)>=0', 'p(99)>=0'],
    [`dp_ab_errors{op:${OP}}`]: ['rate>=0'],
    [`http_reqs{op:${OP}}`]: ['count>=0'],
    [`http_reqs{op:${OP}_settle}`]: ['count>=0'],
    'dropped_iterations{scenario:dp}': ['count>=0'],
  },
};

function hookMark(res) {
  for (const name of Object.keys(res.headers || {})) {
    if (name.toLowerCase() === PROBE_HEADER) return res.headers[name];
  }
  return null;
}

function post(size, tags) {
  return http.post(`${CONFIG.baseUrl}/dp_ab/${size}`, REQUEST_BODY[size], {
    headers: Object.assign({ 'Content-Type': 'application/json' }, CONFIG.keepAliveHeaders), tags,
  });
}

export function setup() {
  const expectations = SIZES.map((s) => ({
    id: `dp-ab-${s}`, httpRequest: { method: 'POST', path: `/dp_ab/${s}` },
    httpResponse: { statusCode: 200, body: { type: 'STRING', string: RESPONSE_BODY[s], contentType: 'application/json' } },
    times: { unlimited: true },
  }));
  expectations.push({
    id: 'dp-ab-probe', httpRequest: { method: 'GET', path: PROBE_PATH },
    httpResponse: { statusCode: 200, body: 'probe' }, times: { unlimited: true },
  });
  const seeded = http.put(`${CONFIG.controlPlane}/expectation`, JSON.stringify(expectations),
    { headers: { 'Content-Type': 'application/json' } });
  if (seeded.status !== 201 && seeded.status !== 200) fail(`dataplane_ab.js: seeding returned HTTP ${seeded.status}: ${seeded.body}`);
  const probe = http.get(`${CONFIG.baseUrl}${PROBE_PATH}`);
  const result = { arm: ARM, probe_status: probe.status, probe_hook: hookMark(probe), expected_hook: ARMS[ARM], sizes: {} };
  result.ok = probe.status === 200 && result.probe_hook === ARMS[ARM];
  for (const s of SIZES) {
    const res = post(s, { op: 'setup' });
    const v = { status: res.status, bytes: res.body ? res.body.length : 0, hook: hookMark(res) };
    v.identical = res.status === 200 && res.body === RESPONSE_BODY[s] && v.hook === null;
    result.sizes[s] = v;
    result.ok = result.ok && v.identical;
  }
  if (!result.ok) fail(`dataplane_ab.js: the ${ARM} SUT is not configured as its arm says: ${JSON.stringify(result)}`);
  return result;
}

// The first K6_DP_SETTLE of a window is tagged <op>_settle and kept out of the percentiles.
export function dpOp() {
  const size = SIZES[exec.scenario.iterationInTest % SIZES.length];
  const op = MODE === 'window' && Date.now() - exec.scenario.startTime < SETTLE_SEC * 1000 ? `${OP}_settle` : OP;
  const res = post(size, { op, name: `POST /dp_ab/${size}` });
  dpErrors.add(!(res.status === 200 && typeof res.body === 'string' && res.body.length === BYTES[size]), { op });
}

export function handleSummary(data) {
  const value = (name, field) => {
    const m = data.metrics[name];
    return m && m.values ? m.values[field] : 0;
  };
  const dur = data.metrics[`http_req_duration{op:${OP}}`];
  const count = value(`http_reqs{op:${OP}}`, 'count');
  const enough = count >= MIN_TAIL_SAMPLES && dur && dur.values;
  const errors = data.metrics[`dp_ab_errors{op:${OP}}`];
  const out = {
    mode: MODE, arm: ARM, size: MODE === 'window' ? SIZES[0] : null, sizes: SIZES,
    bytes: MODE === 'window' ? BYTES[SIZES[0]] : null,
    probe: data.setup_data || null,
    p50_ms: enough ? round(dur.values['p(50)']) : null,
    p95_ms: enough ? round(dur.values['p(95)']) : null,
    p99_ms: enough ? round(dur.values['p(99)']) : null,
    sample_count: count,
    settle_excluded: value(`http_reqs{op:${OP}_settle}`, 'count'),
    throughput_rps: round(count / MEASURED_SEC),
    offered_rps: RATE,
    dropped_iterations: value('dropped_iterations{scenario:dp}', 'count'),
    error_rate: count > 0 && errors && errors.values ? round(errors.values.rate, 5) : null,
    config: { rate_rps: RATE, vus: VUS, duration_s: DURATION_SEC, settle_s: SETTLE_SEC, measured_window_s: MEASURED_SEC },
  };
  const json = JSON.stringify(out, null, 2);
  return { [RESULT_PATH]: json, stdout: `\ndata-plane hooks A/B ${MODE} result:\n${json}\n` };
}
