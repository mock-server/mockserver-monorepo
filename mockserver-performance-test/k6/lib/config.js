// Shared k6 configuration for the MockServer performance suite.
//
// All tunables are environment-driven (k6 `-e KEY=value` or real env vars via
// `__ENV`) so the same scripts run locally, in the docker-compose stack, and in
// CI without edits. Defaults reproduce the historical Locust target
// (MockServer on localhost:1080, 4 seeded expectations, the request matching
// the last expectation).
//
// Connection target resolution (first match wins):
//   1. BASE_URL                      e.g. https://mockserver:1080
//   2. MOCKSERVER_PROTOCOL + MOCKSERVER_HOST   e.g. http + localhost:1080
//
// MOCKSERVER_HOST mirrors the Locust harness variable (host[:port]); a bare
// host gets :1080 appended.

function env(name, fallback) {
  const value = __ENV[name];
  return value === undefined || value === '' ? fallback : value;
}

function num(name, fallback) {
  const value = env(name, undefined);
  if (value === undefined) {
    return fallback;
  }
  const parsed = Number(value);
  return Number.isFinite(parsed) ? parsed : fallback;
}

function bool(name, fallback) {
  const value = env(name, undefined);
  if (value === undefined) {
    return fallback;
  }
  return ['1', 'true', 'yes', 'on'].includes(value.toLowerCase());
}

function resolveBaseUrl() {
  const explicit = env('BASE_URL', undefined);
  if (explicit) {
    return explicit.replace(/\/+$/, '');
  }
  const protocol = env('MOCKSERVER_PROTOCOL', 'http');
  let host = env('MOCKSERVER_HOST', 'localhost:1080');
  if (!host.includes(':')) {
    host = `${host}:1080`;
  }
  return `${protocol}://${host}`;
}

const baseUrl = resolveBaseUrl();

// A MockServer under test uses a self-signed CA, so HTTPS perf runs against a
// LOCAL/private instance legitimately need TLS verification skipped. Public
// targets should NOT silently skip verification — so the default is insecure
// only for loopback/private hosts, and explicit (INSECURE_SKIP_TLS_VERIFY) for
// anything else. This removes the "accidentally hit prod with verify off"
// footgun while keeping local HTTPS runs working out of the box.
function isLocalOrPrivateTarget(url) {
  const rawHost = url.replace(/^[a-z]+:\/\//i, '').split('/')[0];
  // Strip IPv6 bracket notation ([::1]:1080) before taking the host; otherwise
  // split(':')[0] would yield "[" and miss the loopback check.
  const host = (rawHost.startsWith('[') ? rawHost.slice(1, rawHost.indexOf(']')) : rawHost.split(':')[0]).toLowerCase();
  return (
    host === 'localhost' ||
    host === '127.0.0.1' ||
    host === '::1' ||
    host === 'host.docker.internal' ||
    host === 'mockserver' ||
    host === 'mockserver-cov' ||
    host.endsWith('.local') ||
    host.endsWith('.internal') ||
    /^10\./.test(host) ||
    /^192\.168\./.test(host) ||
    /^172\.(1[6-9]|2\d|3[01])\./.test(host)
  );
}

const insecureSkipTLSVerify = bool('INSECURE_SKIP_TLS_VERIFY', isLocalOrPrivateTarget(baseUrl));

// Never let insecure verification be silently active against a non-local host.
if (insecureSkipTLSVerify && baseUrl.startsWith('https') && !isLocalOrPrivateTarget(baseUrl)) {
  console.warn(`[k6] WARNING: TLS verification is DISABLED for non-local target ${baseUrl} (INSECURE_SKIP_TLS_VERIFY).`);
}

export const CONFIG = {
  baseUrl,
  // MockServer control plane is reachable with or without the /mockserver
  // prefix; use the canonical prefixed form (matches the dashboard + metrics).
  controlPlane: `${baseUrl}/mockserver`,
  // Keep-Alive headers preserved for parity with the Locust harness. k6 reuses
  // connections per VU by default, so these are belt-and-braces.
  keepAliveHeaders: {
    Connection: 'Keep-Alive',
    'Keep-Alive': 'timeout=120, max=1000',
  },
  insecureSkipTLSVerify,
};

// Load-shape tunables (used by the scenario files). Defaults are deliberately
// conservative so an accidental local run does not saturate a workstation.
export const LOAD = {
  // ramping-arrival-rate (load.js): requests/sec per stage.
  startRate: num('K6_START_RATE', 50),
  peakRate: num('K6_PEAK_RATE', 500),
  rampUp: env('K6_RAMP_UP', '30s'),
  hold: env('K6_HOLD', '1m'),
  rampDown: env('K6_RAMP_DOWN', '15s'),
  preAllocatedVUs: num('K6_PRE_VUS', 50),
  maxVUs: num('K6_MAX_VUS', 600),
  // create-expectation churn rate run alongside matching (control-plane load).
  createRate: num('K6_CREATE_RATE', 10),
  // stress.js peak target.
  stressPeakRate: num('K6_STRESS_PEAK_RATE', 5000),
  // soak.js sustained rate + duration.
  soakRate: num('K6_SOAK_RATE', 200),
  soakDuration: env('K6_SOAK_DURATION', '30m'),
};

// Threshold values become CI pass/fail gates. Tunable so a slow CI agent can
// relax them via env without editing scripts. p95/p99 are in milliseconds.
export const LIMITS = {
  p95: num('K6_P95_MS', 25),
  p99: num('K6_P99_MS', 100),
  errorRate: num('K6_MAX_ERROR_RATE', 0.01),
  checkRate: num('K6_MIN_CHECK_RATE', 0.99),
};

// Standard thresholds shared by the load/stress/soak scenarios. Global
// http_req_duration/http_req_failed gates plus a check-pass-rate gate.
export function baseThresholds() {
  return {
    http_req_failed: [`rate<${LIMITS.errorRate}`],
    http_req_duration: [`p(95)<${LIMITS.p95}`, `p(99)<${LIMITS.p99}`],
    checks: [`rate>${LIMITS.checkRate}`],
  };
}

// Regression scenario tunables (regression.js). Unlike load.js, the regression
// run offers a FIXED rate per behaviour (constant-arrival-rate) so the recorded
// number is "latency under fixed load" — the only thing comparable across runs
// when the goal is detecting a change, not finding peak throughput. A warmup
// window runs first (tagged op:warmup) so JIT/GC reach steady state before the
// measured window; only the measured window feeds the result JSON.
export const REGRESSION = {
  rate: num('K6_REG_RATE', 200), // offered req/s PER behaviour
  duration: env('K6_REG_DURATION', '2m'), // measured window (per behaviour)
  warmup: env('K6_REG_WARMUP', '30s'), // pre-measurement server warmup window
  // preAllocatedVUs == maxVUs ON PURPOSE: with no headroom the executor can never
  // allocate a VU (open a connection) mid-measurement, which on a core-limited SUT
  // compounds into a connection storm and a multi-second tail. A transient beyond
  // the pool is dropped and counted instead. Keep K6_REG_PRE_VUS and K6_REG_MAX_VUS
  // equal, and modest: the tail grows with pool size. See
  // docs/code/performance-measurement.md ("k6 load-shape sizing").
  preAllocatedVUs: num('K6_REG_PRE_VUS', 50),
  maxVUs: num('K6_REG_MAX_VUS', 50),
  // Scenario starts are staggered (op index x stagger) so their connection-open
  // transients do not superimpose on the shared, core-limited SUT.
  stagger: env('K6_REG_STAGGER', '5s'),
  // Per-scenario settle window at the start of the measured window whose requests
  // are tagged op:<op>_settle and thus EXCLUDED from the measured latency
  // percentiles. Load still runs during it (the server is exercised and any
  // residual transient is genuinely traversed), so this discards only the known
  // client-side start artefact, not real steady-state latency. dropped_iterations
  // still counts the WHOLE scenario, so client starvation is never hidden.
  settle: env('K6_REG_SETTLE', '10s'),
  // Self-test knob (default 0 = off, production behaviour unchanged): when >0, a
  // fixed response delay (ms) is seeded onto the /simple (match) response so a
  // run can PROVE the measured percentiles still track a real server slowdown
  // (the medians and tail must move by ~this delay). Used by the sensitivity
  // check that guards against the settle exclusion silently hiding regressions.
  matchDelayMs: num('K6_REG_MATCH_DELAY_MS', 0),
  // --- template-engine arms --------------------------------------------------
  // The JavaScript arm needs GraalJS, present only in the -graaljs image, so it is
  // off by default (a stock server would give a 100%-error arm). perf-test-run.sh
  // turns it on with the -graaljs SUT; regression.js fails loudly if it is absent.
  jsTemplate: bool('K6_REG_JS_TEMPLATE', false),
  // The Mustache + JavaScript arms run below the 200 rps velocity arm so they do not
  // perturb the historical arms on the core-limited SUT; each keeps its own history.
  templateArmRate: num('K6_REG_TEMPLATE_ARM_RATE', 50),
  // --- body-size axis: `large` stays 4 KB; 1 MB / 10 MB POST a JSON body matched on
  // a small marker, so decode cost scales with size and match cost does not.
  // Keep 10 MB DECIMAL: buildLargeJson overshoots its target, so a 10 MiB target
  // exceeds MOCKSERVER_MAX_REQUEST_BODY_SIZE (default 10 MiB) and every request 413s.
  // Override K6_REG_LARGE_10MB_BYTES only with a raised limit on the SUT.
  large1mbBytes: num('K6_REG_LARGE_1MB_BYTES', 1000 * 1000),
  large10mbBytes: num('K6_REG_LARGE_10MB_BYTES', 10 * 1000 * 1000),
  // These rates are NOT the OOM guard. The event-log ring holds full bodies and is
  // count-bounded, so retained bytes scale with residence (maxLogEntries / achieved
  // rps), which grows without bound as the server slows. The SUT's
  // maxEventLogSizeInBytes budget bounds retention; the rates stay low only to limit
  // contention and run length. The warmup never touches these arms. Arithmetic and
  // the growth-phase budget override: docs/code/performance-measurement.md.
  large1mbRate: num('K6_REG_LARGE_1MB_RATE', 1),
  large1mbTimeUnit: env('K6_REG_LARGE_1MB_TIME_UNIT', '2s'), // 0.5 rps
  large10mbRate: num('K6_REG_LARGE_10MB_RATE', 1),
  large10mbTimeUnit: env('K6_REG_LARGE_10MB_TIME_UNIT', '10s'), // 0.1 rps
  large1mbVUs: num('K6_REG_LARGE_1MB_VUS', 8),
  large10mbVUs: num('K6_REG_LARGE_10MB_VUS', 8),
  // File-backed RESPONSE body arm (FileBodyMaterialiser path). OFF unless a
  // server-side file path is given: the file must exist on the SUT filesystem,
  // which perf-test-run.sh provisions (generates + mounts read-only) and then
  // passes here. Empty => the arm is absent (an explicit, documented omission,
  // not a silent zero). When set, regression.js probes it in setup() and fails
  // loudly if the server cannot read the file (a FILE body 500s when unreadable).
  fileBodyPath: env('K6_REG_FILE_BODY_PATH', ''),
  // Low for the same contention reason as the large_* arms (~1 MB response each).
  fileBodyRate: num('K6_REG_FILE_BODY_RATE', 1),
  fileBodyTimeUnit: env('K6_REG_FILE_BODY_TIME_UNIT', '2s'), // 0.5 rps
  fileBodyVUs: num('K6_REG_FILE_BODY_VUS', 8),
  // Comma-separated arm ops to run (e.g. "match,forward"); empty runs every enabled arm.
  // perf-test-run.sh's pipelining A/B uses it to run only the HTTP/1.1 arms it compares.
  arms: env('K6_REG_ARMS', ''),
  // Transport label recorded in the result key (<op>_<proto>). Defaults from the
  // BASE_URL scheme; HTTPS auto-negotiates HTTP/2 with MockServer via ALPN, so
  // the https run is labelled https_h2 unless K6_HTTP2=false.
  proto: env('PROTO', baseUrl.startsWith('https') ? (bool('K6_HTTP2', true) ? 'https_h2' : 'https') : 'http'),
  // handleSummary() writes the machine-readable result here (mapped to a file by
  // k6); the perf-test-run.sh step merges the http + https_h2 runs into one JSON.
  resultPath: env('K6_RESULT_PATH', 'regression-result.json'),
};

// Throughput-vs-latency sweep tunables (sweep.js): an ascending ladder of fixed
// arrival-rate rungs, each tagged with its offered rate, recording achieved rps,
// latency percentiles and errors per rung. k6 dropping iterations at the top of the
// ladder is expected (it shows the knee), so there are NO aborting thresholds.
export const SWEEP = {
  // Ascending ladder of offered arrival rates (req/s). Comma-separated.
  rates: env('K6_SWEEP_RATES', '500,1000,2000,4000,8000,16000,32000')
    .split(',')
    .map((r) => Number(r.trim()))
    .filter((r) => Number.isFinite(r) && r > 0),
  step: env('K6_SWEEP_STEP', '20s'), // duration each rate step holds
  gap: env('K6_SWEEP_GAP', '5s'), // quiet gap between steps (no requests)
  // Leading slice of each rung excluded from its latency percentiles (load still
  // runs and still counts toward achieved/error/drop figures). Sized to cover the
  // rung-onset transient; see docs/code/performance-measurement.md.
  settle: env('K6_SWEEP_SETTLE', '3s'),
  // Per-rung fixed pool (preAllocatedVUs == maxVUs, sized by sweep.js poolForRate):
  //   pool = clamp(ceil(rate * vuPerRps), vuFloor, vuCeiling)
  // One flat pool cannot work: sized for low rungs it starves near-knee rungs into
  // drops (rig-invalid); sized for the top it storms the low rungs. The factor,
  // floor and ceiling were derived from measured per-rung VU peaks; re-derive them
  // before changing any of the three (docs/code/performance-measurement.md).
  vuPerRps: num('K6_SWEEP_VUS_PER_KRPS', 80) / 1000, // VUs per offered rps (0.08)
  vuFloor: num('K6_SWEEP_VU_FLOOR', 96),
  vuCeiling: num('K6_SWEEP_VU_CEILING', 2048),
  // Optional FLAT override: set BOTH equal to force a single pool on every rung
  // (bypasses per-rung sizing — e.g. for a controlled experiment). Left unset by
  // default so per-rung sizing applies; sweep.js throws if they are set unequal.
  preAllocatedVUs: num('K6_SWEEP_PRE_VUS', undefined),
  maxVUs: num('K6_SWEEP_MAX_VUS', undefined),
  // Transport label recorded in the result; HTTPS to MockServer negotiates HTTP/2
  // via ALPN, but the sweep is HTTP by default (the headline knee curve).
  proto: env('PROTO', baseUrl.startsWith('https') ? (bool('K6_HTTP2', true) ? 'https_h2' : 'https') : 'http'),
  resultPath: env('K6_SWEEP_RESULT_PATH', 'sweep-result.json'),
  // Opt-in knobs for scripts/rw-multi-k6-sweep.sh; defaults are the published method.
  windowMode: env('K6_SWEEP_WINDOW_MODE', 'vu_tag'), // vu_tag | wallclock (no window tag)
  leanSummary: bool('K6_SWEEP_LEAN_SUMMARY', false), // counts only; no percentile submetrics
  vuDiagnostics: bool('K6_SWEEP_VU_DIAGNOSTICS', true),
  startAtMs: num('K6_SWEEP_START_AT_MS', 0), // epoch ms the ladder starts; 0 = after setup
  quiet: env('K6_SWEEP_QUIET', '0s'), // idle tail after the last rung
  manageSut: bool('K6_SWEEP_MANAGE_SUT', true), // seed in setup, reset in teardown
  // false leaves the event log full after the ladder, for the INFO arm's live-heap reset probe.
  resetOnTeardown: bool('K6_SWEEP_RESET_ON_TEARDOWN', true),
  // Each rung's gracefulStop; null = k6's 30s default. At or below the gap, rung reservations no
  // longer overlap, so k6 initialises the largest pool instead of the sum of ~3 adjacent ones.
  gracefulStop: String(env('K6_SWEEP_GRACEFUL_STOP', '')).trim() || null,
};

// Resource-growth scenario tunables (growth.js): sustained load that fills the
// default ~115.5k-entry request log, with latency probes at the start and end, to
// surface "X increases over time" regressions (e.g. O(n) eviction once the log is
// full). Do NOT shrink maxLogEntries for this run: a log that never fills hides it.
export const GROWTH = {
  rate: num('K6_GROWTH_RATE', 800), // fill load (req/s) — fills the ~115.5k log in ~70s
  // Keep K6_GROWTH_DURATION >= 3 × K6_GROWTH_PROBE so the first/last probe
  // windows have a clear gap between them and the ratio measures a real slope.
  duration: env('K6_GROWTH_DURATION', '6m'),
  probeWindow: env('K6_GROWTH_PROBE', '30s'), // first/last latency-probe window
  probeRate: num('K6_GROWTH_PROBE_RATE', 20),
  preAllocatedVUs: num('K6_GROWTH_PRE_VUS', 50),
  maxVUs: num('K6_GROWTH_MAX_VUS', 400),
  resultPath: env('K6_GROWTH_RESULT_PATH', 'growth-result.json'),
};

// Forward/proxy behaviour target. The forward action routes /forward to a
// DEDICATED upstream (a second MockServer) so the forward latency is not
// contaminated by the matching load on the instance under measurement. Set
// K6_FORWARD_SELF=true to keep the legacy self-forward (127.0.0.1:1080) for a
// quick single-container local smoke.
export const FORWARD = {
  upstreamHost: env('FORWARD_UPSTREAM_HOST', 'mockserver-upstream:1080'),
  forwardSelf: bool('K6_FORWARD_SELF', false),
};

// Forward-path load-shape tunables (forward.js): a regression guard for the
// upstream connection pool (mockserver.forwardConnectionPoolEnabled). Unpooled, each
// forwarded request opens an outbound connection, and at the 1500 rps peak that
// exhausts ephemeral ports (BindException). The error-rate gate is the signal;
// latency bounds are looser than the match path because of the extra hop.
export const FORWARD_LOAD = {
  startRate: num('K6_FWD_START_RATE', 100),
  peakRate: num('K6_FWD_PEAK_RATE', 1500),
  rampUp: env('K6_FWD_RAMP_UP', '30s'),
  hold: env('K6_FWD_HOLD', '1m'),
  rampDown: env('K6_FWD_RAMP_DOWN', '15s'),
  preAllocatedVUs: num('K6_FWD_PRE_VUS', 200),
  maxVUs: num('K6_FWD_MAX_VUS', 2000),
  // Forward adds an upstream network hop, so the data-plane p95/p99 (25/100 ms)
  // are too tight; these forward-specific bounds are overridable. The error-rate
  // gate (the actual pool-regression signal) reuses K6_MAX_ERROR_RATE.
  p95: num('K6_FWD_P95_MS', 50),
  p99: num('K6_FWD_P99_MS', 200),
};

// Proxy-path + TLS-handshake scenario tunables (proxy.js). Two modes, selected by
// K6_PROXY_MODE, because they need mutually exclusive process-global settings:
//   forward   — k6 targets the upstream THROUGH MockServer via HTTP_PROXY/HTTPS_PROXY
//               (absolute-URI GET for http://, CONNECT tunnel for https://), keep-alive.
//               Emits .behaviours, so perf-test-compare.sh picks the arms up unchanged.
//   handshake — direct HTTPS with noConnectionReuse, so every iteration pays a fresh
//               TCP+TLS handshake. No proxy env (it would tunnel and defeat that).
// Both clone regression.js's measured-window shape (warmup, stagger, equal VU pool,
// settle exclusion, MIN_TAIL_SAMPLES); copy from the current regression.js, not an
// older revision, or the connection-storm tail comes back.
export const PROXY = {
  mode: env('K6_PROXY_MODE', 'forward'),
  // --- forward mode ---------------------------------------------------------
  // Offered req/s per proxy arm. Bodies are deliberately tiny, so log-ring retention
  // is negligible at this rate; a proxy arm relaying MB bodies would need the large_*
  // arms' treatment.
  rate: num('K6_PROXY_RATE', 200),
  duration: env('K6_PROXY_DURATION', '2m'),
  warmup: env('K6_PROXY_WARMUP', '30s'),
  stagger: env('K6_PROXY_STAGGER', '5s'),
  settle: env('K6_PROXY_SETTLE', '10s'),
  // preAllocatedVUs == maxVUs: no mid-run VU allocation (see REGRESSION).
  preAllocatedVUs: num('K6_PROXY_PRE_VUS', 50),
  maxVUs: num('K6_PROXY_MAX_VUS', 50),
  // The upstream the proxy forwards/tunnels to (host:port), reachable on the run's
  // docker network. Shared with the FORWARD block so the run seeds one upstream.
  upstreamHost: env('FORWARD_UPSTREAM_HOST', 'mockserver-upstream:1080'),
  // Self-test knob (default 0 = off): a fixed server-side delay (ms) on the upstream
  // /simple response so a run can prove the proxy percentiles still track a real
  // slowdown. Applied by seeding the UPSTREAM, so it exercises the relay path.
  matchDelayMs: num('K6_PROXY_MATCH_DELAY_MS', 0),
  // --- handshake mode --------------------------------------------------------
  // Fresh-handshake rate per arm. Kept modest (each iteration opens a new TCP+TLS
  // connection, so this is also the connection-open rate) to avoid ephemeral-port
  // exhaustion on the client while still yielding thousands of handshakes over the
  // window. Bodies are header-only GET /simple, so log-ring retention is trivially
  // bounded exactly as the forward arms above.
  handshakeRate: num('K6_HS_RATE', 50),
  handshakeDuration: env('K6_HS_DURATION', '1m'),
  // Per-arm DIRECT-TLS targets (distinct SUT containers: server-only TLS 1.3, mTLS
  // required, and native-provider-absent). Empty => that arm is absent (an explicit,
  // documented omission, not a silent zero); proxy.js probes each enabled arm in
  // setup() and fails loud if it cannot handshake.
  tls13Url: env('K6_HS_TLS13_URL', ''),
  mtlsUrl: env('K6_HS_MTLS_URL', ''),
  jdkUrl: env('K6_HS_JDK_URL', ''),
  // Client cert + key (PEM paths, read at init) presented to the mTLS arm's SUT.
  // Required only when mtlsUrl is set; without them the mTLS handshake is rejected
  // by the server (which is exactly the negative control the run also exercises).
  clientCertPath: env('K6_HS_CLIENT_CERT', ''),
  clientKeyPath: env('K6_HS_CLIENT_KEY', ''),
  // --- shared ---------------------------------------------------------------
  // Transport label folded into the result key (<op>_<proto>). Fixed ('proxy')
  // rather than derived from a scheme: each proxy/handshake arm has ONE transport,
  // so unlike regression.js there is no http-vs-https axis to disambiguate.
  proto: env('PROTO', 'proxy'),
  resultPath: env('K6_PROXY_RESULT_PATH', 'proxy-result.json'),
};

// LLM/SSE streaming-under-concurrency scenario tunables (streaming.js). Each SSE
// token's delay is scheduled on the SHARED action-handler pool, so many concurrent
// streams can delay tokens (a fidelity loss) and steal event-loop time from `match`.
// k6 cannot read SSE incrementally, so it only SUSTAINS the concurrency (constant
// VUs, one open stream each) and runs the match A/B (match alone, then under the
// streams). Token-timing error and heap per stream are measured server-side by
// perf-test-run.sh. The streaming arm is `heavy` and deliberately never warmed: it
// is the load, and warming would open thousands of streams at once. Raise
// concurrency/tokens/delay only after re-checking retained heap
// (docs/code/performance-measurement.md, "k6 load-shape sizing").
export const STREAMING = {
  // The SSE endpoint (seeded by expectations.js with `tokens` events each carrying
  // a DETERMINISTIC per-event delay — no jitter — so inter-token delay error is a
  // clean actual-minus-requested, not confounded by a random physics jitter term).
  streamPath: env('K6_STREAM_PATH', '/stream'),
  matchPath: env('K6_STREAM_MATCH_PATH', '/simple'),
  // tokens/events per stream, and the per-event delay (ms). delay=20 ⇒ 50 tokens/s,
  // the LLM streaming default the plan calls out. stream_duration ≈ tokens × delay.
  tokens: num('K6_STREAM_TOKENS', 200),
  delayMs: num('K6_STREAM_DELAY_MS', 20),
  // Streams held open at once (= the streaming arm's VUs); the knob that floods the
  // scheduler pool. perf-test-run.sh overrides the light local default and runs a
  // dedicated SUT with both pools constrained on 1 CPU, because on a many-core SUT
  // the pool never saturates and the metrics cannot move. The A/B and drift are
  // therefore RELATIVE tripwires against that SUT, not production figures.
  concurrency: num('K6_STREAM_CONCURRENCY', 100),
  // The concurrent plain-match arm (the within-run A/B), at a FIXED rate, measured
  // alone (match_baseline) then during the streams (match_under_stream).
  matchRate: num('K6_STREAM_MATCH_RATE', 200),
  // preAllocatedVUs == maxVUs for the fixed-rate match arms (see REGRESSION). The
  // streaming arm is constant-VUs, so it has no ramp to forbid.
  matchPreAllocatedVUs: num('K6_STREAM_MATCH_PRE_VUS', 50),
  matchMaxVUs: num('K6_STREAM_MATCH_MAX_VUS', 50),
  // Per-phase durations. The baseline match phase runs alone first; then the stream
  // load + under-stream match phase overlap. Kept modest so the run fits the perf
  // slot alongside the other phases; the server-side reader/heap sampling windows
  // (perf-test-run.sh) live INSIDE the stream-load window.
  matchBaselineDuration: env('K6_STREAM_MATCH_BASELINE_DURATION', '45s'),
  loadDuration: env('K6_STREAM_LOAD_DURATION', '90s'),
  warmup: env('K6_STREAM_WARMUP', '20s'),
  settle: env('K6_STREAM_SETTLE', '10s'),
  // Self-test knob (default 0 = off): a fixed server-side delay (ms) added to the
  // /simple match response so a run can PROVE the match percentiles still track a
  // real slowdown (degrade-and-confirm-red positive control for the match A/B).
  matchDelayMs: num('K6_STREAM_MATCH_DELAY_MS', 0),
  // Transport label folded into the result key. Fixed 'stream' (one transport).
  proto: env('PROTO_STREAM', 'stream'),
  resultPath: env('K6_STREAM_RESULT_PATH', 'streaming-result.json'),
};

// Path-coverage arms (coverage.js): request paths no other script drives. One k6
// invocation per mode, because the modes need incompatible process-global options
// (noConnectionReuse for churn, HTTP_PROXY for capture/download). Every arm keeps
// the regression.js measured-window shape: warmup, stagger, preAllocatedVUs ==
// maxVUs, settle exclusion, MIN_TAIL_SAMPLES suppression.
export const COVERAGE = {
  mode: env('K6_COV_MODE', 'keepalive'),
  // httpUrl carries the control plane and the matcher arms; tlsUrl is the same SUT
  // over TLS. The *_mtls modes load the client certificate. They are separate k6
  // invocations because k6 presents a configured certificate to EVERY server that
  // requests one, and MockServer requests one on every handshake (ClientAuth.OPTIONAL).
  httpUrl: env('K6_COV_HTTP_URL', baseUrl),
  tlsUrl: env('K6_COV_TLS_URL', baseUrl.replace(/^http:/i, 'https:')),
  // Same rule as CONFIG, applied to the TLS target the coverage arms actually hit.
  insecureSkipTLSVerify: bool(
    'INSECURE_SKIP_TLS_VERIFY',
    isLocalOrPrivateTarget(env('K6_COV_TLS_URL', baseUrl.replace(/^http:/i, 'https:'))),
  ),
  clientCertPath: env('K6_COV_CLIENT_CERT', ''),
  clientKeyPath: env('K6_COV_CLIENT_KEY', ''),
  // The mtls expectation requires a client certificate whose subject matches this,
  // so a request that arrives without the certificate cannot be served (404/502).
  clientCertSubject: env('K6_COV_CLIENT_SUBJECT', 'perf-cov-client'),
  // churn: each iteration opens a fresh TCP+TLS connection, so the rate is also the
  // client's connection-open rate. 200/s over a ~75 s invocation leaves ~15k sockets
  // in TIME_WAIT, inside the ~28k ephemeral range of one k6 container.
  churnRate: num('K6_COV_CHURN_RATE', 200),
  keepaliveRate: num('K6_COV_KEEPALIVE_RATE', 200),
  matcherRate: num('K6_COV_MATCHER_RATE', 100),
  // Expectations registered per matcher type, all on one literal method+path so the
  // candidate index puts them in ONE bucket; only the last-registered matches, so
  // every request scans all of them.
  matcherCandidates: num('K6_COV_MATCHER_CANDIDATES', 100),
  captureRate: num('K6_COV_CAPTURE_RATE', 1000),
  captureVUs: num('K6_COV_CAPTURE_VUS', 100),
  upstreamHost: env('FORWARD_UPSTREAM_HOST', 'mockserver-upstream:1080'),
  // download: absolute http:// URL of a large body on a dedicated upstream, fetched
  // through the SUT as a forward proxy. Empty => the arm is absent.
  downloadUrl: env('K6_COV_DOWNLOAD_URL', ''),
  downloadBytes: num('K6_COV_DOWNLOAD_BYTES', 32 * 1024 * 1024),
  downloadRate: num('K6_COV_DOWNLOAD_RATE', 2),
  downloadTimeUnit: env('K6_COV_DOWNLOAD_TIME_UNIT', '1s'),
  downloadVUs: num('K6_COV_DOWNLOAD_VUS', 8),
  duration: env('K6_COV_DURATION', '45s'),
  // The matchers mode only: 110 s leaves a 100 s measured window, 10,000 requests per arm at
  // 100 rps, so each p99 rests on its 100 slowest requests. At 45 s it rested on 35, and one
  // jitter burst decided it (compare judges a path_coverage p99 only from 5,000+ samples).
  matcherDuration: env('K6_COV_MATCHER_DURATION', '110s'),
  warmup: env('K6_COV_WARMUP', '15s'),
  stagger: env('K6_COV_STAGGER', '5s'),
  settle: env('K6_COV_SETTLE', '10s'),
  vus: num('K6_COV_VUS', 50),
  resultPath: env('K6_COV_RESULT_PATH', 'coverage-result.json'),
};

export { env, num, bool };
