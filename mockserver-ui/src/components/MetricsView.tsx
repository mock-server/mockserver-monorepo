import Box from '@mui/material/Box';
import Paper from '@mui/material/Paper';
import Typography from '@mui/material/Typography';
import Chip from '@mui/material/Chip';
import Alert from '@mui/material/Alert';
import AlertTitle from '@mui/material/AlertTitle';
import IconButton from '@mui/material/IconButton';
import Tooltip from '@mui/material/Tooltip';
import Card from '@mui/material/Card';
import Skeleton from '@mui/material/Skeleton';
import RefreshIcon from '@mui/icons-material/Refresh';
import { transitions, monospaceFontFamily } from '../theme';
import type { ConnectionParams } from '../hooks/useConnectionParams';
import { useMetricsPolling } from '../hooks/useMetricsPolling';
import { findSample, metricValue, metricValueByLabel, metricSum, hasMetric, labelValues } from '../lib/prometheusParser';
import { gaugeSeries, gaugeSeriesByLabel, gaugeSeriesSum, ratePerSecond, latestRate } from '../lib/metricsDerive';
import type { MetricsSnapshot } from '../lib/metricsDerive';
import { histogramQuantile } from '../lib/histogramQuantile';
import MetricsLineChart from './MetricsLineChart';

const LATENCY_METRIC = 'mock_server_request_duration_seconds';
const CHAOS_METRIC = 'mock_server_http_chaos_injected_total';
const ACTIVE_CHAOS_METRIC = 'mock_server_active_service_chaos';
const EXPECTATIONS_BY_TYPE_METRIC = 'mock_server_expectations_by_type';
const MCP_TOOL_CALLS_METRIC = 'mock_server_mcp_tool_calls_total';
const AUTO_HALT_METRIC = 'mock_server_chaos_auto_halt';
const LLM_COST_BUDGET_TRIPPED_METRIC = 'mock_server_llm_cost_budget_tripped';
const LLM_COST_USD_METRIC = 'mock_server_llm_cost_usd';
const ASYNC_PUBLISHED_METRIC = 'mock_server_async_messages_published_total';
const ASYNC_CONSUMED_METRIC = 'mock_server_async_messages_consumed_total';
const EXPECTATIONS_BYTES_METRIC = 'mock_server_expectations_bytes';
const MAX_EXPECTATIONS_BYTES_METRIC = 'mock_server_max_expectations_bytes';
const ACCEPT_QUEUE_CONFIGURED_METRIC = 'mock_server_accept_queue_backlog_configured';
const ACCEPT_QUEUE_EFFECTIVE_METRIC = 'mock_server_accept_queue_backlog_effective';
const EVENT_LOG_RETAINED_BYTES_METRIC = 'mock_server_event_log_retained_bytes';
const EVENT_LOG_MAX_RETAINED_BYTES_METRIC = 'mock_server_event_log_max_retained_bytes';
const EVENT_LOG_RETAINED_ENTRIES_METRIC = 'mock_server_event_log_retained_entries';
const EVENT_LOG_MAX_RETAINED_ENTRIES_METRIC = 'mock_server_event_log_max_retained_entries';
const EVENT_LOG_IN_FLIGHT_BYTES_METRIC = 'mock_server_event_log_in_flight_bytes';
const EVENT_LOG_MAX_IN_FLIGHT_BYTES_METRIC = 'mock_server_event_log_max_in_flight_bytes';
const EVENT_LOG_RING_OCCUPANCY_METRIC = 'mock_server_event_log_ring_occupancy';
const EVENT_LOG_RING_CAPACITY_METRIC = 'mock_server_event_log_ring_capacity';

// All HTTP chaos fault types, in display order. Any fault_type the server emits
// that is not listed here still renders (appended, title-cased) so the UI never
// silently drops a future fault type.
const CHAOS_FAULT_ORDER = ['drop', 'error', 'latency', 'truncate', 'malformed', 'slow', 'quota'];

function chaosFaultLabel(faultType: string): string {
  return faultType.charAt(0).toUpperCase() + faultType.slice(1);
}

/** Converts e.g. "RESPONSE_TEMPLATE" to "Response template". */
function prettyEnumLabel(raw: string): string {
  return raw
    .replace(/_/g, ' ')
    .toLowerCase()
    .replace(/^./, (c) => c.toUpperCase());
}

/** fault_type values present in the metric, ordered by CHAOS_FAULT_ORDER then first-seen. */
function orderedFaultTypes(present: string[]): string[] {
  const known = CHAOS_FAULT_ORDER.filter((ft) => present.includes(ft));
  const extra = present.filter((ft) => !CHAOS_FAULT_ORDER.includes(ft));
  return [...known, ...extra];
}

interface MetricsViewProps {
  connectionParams: ConnectionParams;
}

const SUMMARY: { name: string; label: string }[] = [
  { name: 'requests_received_count', label: 'Requests received' },
  { name: 'response_expectations_matched_count', label: 'Matched' },
  { name: 'expectations_not_matched_count', label: 'Not matched' },
  { name: 'forward_expectations_matched_count', label: 'Forwarded' },
];

function prettyActionName(metric: string): string {
  return metric
    .replace(/_count$/, '')
    .replace(/_/g, ' ')
    .trim();
}

function formatBytes(bytes: number): string {
  if (bytes < 0 || !Number.isFinite(bytes)) return '—';
  const units = ['B', 'KB', 'MB', 'GB'];
  let value = bytes;
  let i = 0;
  while (value >= 1024 && i < units.length - 1) {
    value /= 1024;
    i += 1;
  }
  return `${value.toFixed(i === 0 ? 0 : 1)} ${units[i] ?? 'B'}`;
}

/**
 * A "running out of room" panel: the live value charted against its budget so a reader sees
 * "256 MB of a 256 MB budget", not a bare "256 MB" that looks informative and tells them nothing.
 * A budget of 0 means the bound is DISABLED (not "a budget of zero"), so the budget line is dropped
 * and the caption says the limit is off rather than drawing a misleading full bar.
 */
function CapacityChart({
  title,
  budgetLabel,
  history,
  timestamps,
  valueMetric,
  budgetMetric,
  budgetValue,
  valueFormatter,
}: {
  title: string;
  budgetLabel: string;
  history: MetricsSnapshot[];
  timestamps: number[];
  valueMetric: string;
  budgetMetric: string;
  budgetValue: number;
  valueFormatter: (v: number) => string;
}) {
  const bounded = budgetValue > 0;
  return (
    <Paper variant="outlined" sx={{ p: 1.25, mb: 1.5 }}>
      <Typography variant="caption" color="text.secondary">
        {`${title}${bounded ? '' : ' (no limit set)'}`}
      </Typography>
      <MetricsLineChart
        timestamps={timestamps}
        height={200}
        valueFormatter={valueFormatter}
        series={
          bounded
            ? [
                { data: gaugeSeries(history, valueMetric), label: 'used' },
                { data: gaugeSeries(history, budgetMetric), label: budgetLabel },
              ]
            : [{ data: gaugeSeries(history, valueMetric), label: 'used' }]
        }
      />
    </Paper>
  );
}

export default function MetricsView({ connectionParams }: MetricsViewProps) {
  const { status, history, latest, error, intervalMs, refresh } = useMetricsPolling(connectionParams, {
    intervalMs: 3000,
    historySize: 60,
  });

  const version = latest ? findSample(latest.samples, 'mock_server_build_info')?.labels.version : undefined;
  const rps = latestRate(history, 'requests_received_count');

  // One wall-clock timestamp per history snapshot, in lockstep with every
  // derived series, so the charts can render a real time x-axis.
  const timestamps = history.map((h) => h.at);

  // KPI "hero" stats — the four headline request counters surfaced as prominent
  // stat cards above the chart stack. Latency p50/p95/p99 join when the server
  // exposes the duration histogram (added below once latencyEnabled is known).
  const heroCounters = latest
    ? SUMMARY.map(({ name, label }) => ({ label, value: metricValue(latest.samples, name) }))
    : [];

  const actionRows = latest
    ? latest.samples
        .filter((s) => s.name.endsWith('_actions_count'))
        .map((s) => ({ name: s.name, label: prettyActionName(s.name), value: s.value }))
        .sort((a, b) => b.value - a.value)
    : [];
  const maxAction = actionRows.reduce((m, r) => Math.max(m, r.value), 0);
  const jvmEnabled = latest ? hasMetric(latest.samples, 'jvm_memory_used_bytes') : false;
  const latencyEnabled = latest ? hasMetric(latest.samples, `${LATENCY_METRIC}_count`) : false;
  // Latency quantiles for the hero stat row (ms). Only meaningful when the
  // duration histogram is exposed; otherwise omitted from the hero cards.
  const latencyHeroStats = latencyEnabled && latest
    ? ([['p50', 0.5], ['p95', 0.95], ['p99', 0.99]] as const).map(([label, q]) => {
        const seconds = histogramQuantile(latest.samples, LATENCY_METRIC, q);
        return { label, text: seconds == null ? '—' : `${(seconds * 1000).toFixed(1)} ms` };
      })
    : [];
  const chaosFaultTypes = latest ? orderedFaultTypes(labelValues(latest.samples, CHAOS_METRIC, 'fault_type')) : [];
  const chaosFaultTotals = latest
    ? chaosFaultTypes.map((ft) => ({ faultType: ft, value: metricValueByLabel(latest.samples, CHAOS_METRIC, 'fault_type', ft) }))
    : [];
  // Active service-scoped chaos is a gauge labeled by fault_type (one series per
  // type), so it is charted by type rather than shown as a single counter.
  const activeChaosFaultTypes = latest ? orderedFaultTypes(labelValues(latest.samples, ACTIVE_CHAOS_METRIC, 'fault_type')) : [];
  const activeServiceChaosEnabled = activeChaosFaultTypes.length > 0;
  const activeChaosTotal = activeChaosFaultTypes.reduce(
    (sum, ft) => sum + (latest ? metricValueByLabel(latest.samples, ACTIVE_CHAOS_METRIC, 'fault_type', ft) : 0),
    0,
  );
  // Auto-halt circuit-breaker counter
  const autoHaltTotal = latest ? metricValue(latest.samples, AUTO_HALT_METRIC) : 0;
  const autoHaltEnabled = latest ? hasMetric(latest.samples, AUTO_HALT_METRIC) : false;

  // LLM cost-budget circuit-breaker counter and cumulative cost
  const llmCostBudgetTrippedTotal = latest ? metricValue(latest.samples, LLM_COST_BUDGET_TRIPPED_METRIC) : 0;
  const llmCostBudgetEnabled = latest ? hasMetric(latest.samples, LLM_COST_BUDGET_TRIPPED_METRIC) : false;
  const llmCostUsdTotal = latest ? metricSum(latest.samples, LLM_COST_USD_METRIC) : 0;

  const chaosEnabled = latest ? (hasMetric(latest.samples, CHAOS_METRIC) || activeServiceChaosEnabled || autoHaltEnabled || llmCostBudgetEnabled) : false;
  // The auto-halt and LLM cost-budget counters are meaningful even at zero (they
  // mean the circuit-breaker is configured and has not fired), so their presence
  // alone qualifies as "has data".
  const chaosHasData = chaosFaultTotals.some((f) => f.value > 0) || activeChaosTotal > 0 || autoHaltEnabled || llmCostBudgetEnabled;

  // Expectations by type — gauge labeled by action_type
  const expectationActionTypes = latest
    ? labelValues(latest.samples, EXPECTATIONS_BY_TYPE_METRIC, 'action_type')
    : [];
  const expectationsByType = latest
    ? expectationActionTypes
        .map((at) => ({ actionType: at, value: metricValueByLabel(latest.samples, EXPECTATIONS_BY_TYPE_METRIC, 'action_type', at) }))
        .filter((r) => r.value > 0)
        .sort((a, b) => b.value - a.value)
    : [];
  const expectationsByTypeEnabled = expectationsByType.length > 0;

  // MCP tool calls — counter labeled by tool (shown cumulative like actions-executed)
  const mcpToolNames = latest
    ? labelValues(latest.samples, MCP_TOOL_CALLS_METRIC, 'tool')
    : [];
  const mcpToolRows = latest
    ? mcpToolNames
        .map((t) => ({ tool: t, value: metricValueByLabel(latest.samples, MCP_TOOL_CALLS_METRIC, 'tool', t) }))
        .filter((r) => r.value > 0)
        .sort((a, b) => b.value - a.value)
    : [];
  const mcpToolCallsEnabled = mcpToolRows.length > 0;

  // Async message activity — two counters (published / consumed) labeled by channel,
  // summed across channels into two cumulative lines on a chart separate from HTTP.
  const asyncPublishedTotal = latest ? metricSum(latest.samples, ASYNC_PUBLISHED_METRIC) : 0;
  const asyncConsumedTotal = latest ? metricSum(latest.samples, ASYNC_CONSUMED_METRIC) : 0;
  const asyncEnabled = latest
    ? hasMetric(latest.samples, ASYNC_PUBLISHED_METRIC) || hasMetric(latest.samples, ASYNC_CONSUMED_METRIC)
    : false;
  const asyncHasData = asyncPublishedTotal > 0 || asyncConsumedTotal > 0;

  // Capacity ("running out of room") signals, alongside JVM heap below. The expectation-store byte
  // total is live whether or not a byte budget is set, so the budget line is drawn only when a bound
  // is in force (> 0). The accept-queue backlog is a configuration ceiling, not a time series, so it
  // is shown as stat numbers; the "effective" (kernel-capped) figure is only present when the server
  // could read the kernel ceiling (Linux) — on other platforms the server omits it and so does this.
  const expectationBytesEnabled = latest ? hasMetric(latest.samples, EXPECTATIONS_BYTES_METRIC) : false;
  const maxExpectationsBytes = latest ? metricValue(latest.samples, MAX_EXPECTATIONS_BYTES_METRIC) : 0;
  const acceptQueueEnabled = latest ? hasMetric(latest.samples, ACCEPT_QUEUE_CONFIGURED_METRIC) : false;
  const acceptQueueConfigured = latest ? metricValue(latest.samples, ACCEPT_QUEUE_CONFIGURED_METRIC) : 0;
  const acceptQueueEffectiveEnabled = latest ? hasMetric(latest.samples, ACCEPT_QUEUE_EFFECTIVE_METRIC) : false;
  const acceptQueueEffective = latest ? metricValue(latest.samples, ACCEPT_QUEUE_EFFECTIVE_METRIC) : 0;

  // Event-log gauges — the same "running out of room" question as the expectation store, so charted
  // in the same section. All eight register together, so one presence check gates the whole group.
  // The byte budgets (max_retained_bytes, max_in_flight_bytes) can be 0 = disabled; the entry cap and
  // ring capacity are always positive. Each latest budget value decides whether its budget line is drawn.
  const eventLogEnabled = latest ? hasMetric(latest.samples, EVENT_LOG_RETAINED_BYTES_METRIC) : false;
  const eventLogMaxRetainedBytes = latest ? metricValue(latest.samples, EVENT_LOG_MAX_RETAINED_BYTES_METRIC) : 0;
  const eventLogMaxRetainedEntries = latest ? metricValue(latest.samples, EVENT_LOG_MAX_RETAINED_ENTRIES_METRIC) : 0;
  const eventLogMaxInFlightBytes = latest ? metricValue(latest.samples, EVENT_LOG_MAX_IN_FLIGHT_BYTES_METRIC) : 0;
  const eventLogRingCapacity = latest ? metricValue(latest.samples, EVENT_LOG_RING_CAPACITY_METRIC) : 0;

  const countFormatter = (v: number) => Math.round(v).toLocaleString();

  return (
    <Box sx={{ flex: 1, overflow: 'auto', p: 1.5 }}>
      <Box sx={{ display: 'flex', alignItems: 'center', gap: 1, mb: 1.5 }}>
        <Typography variant="subtitle1" sx={{ fontWeight: 700 }}>
          Metrics
        </Typography>
        <Chip
          size="small"
          label={status === 'ok' ? 'live' : status}
          color={status === 'ok' ? 'success' : status === 'disabled' ? 'default' : status === 'error' ? 'error' : 'warning'}
          variant="outlined"
        />
        {version && <Chip size="small" label={`MockServer ${version}`} variant="outlined" />}
        <Box sx={{ flex: 1 }} />
        {latest && (
          <Typography variant="caption" color="text.secondary">
            updated {new Date(latest.at).toLocaleTimeString()} · every {Math.round(intervalMs / 1000)}s
          </Typography>
        )}
        <Tooltip title="Refresh now">
          <IconButton size="small" onClick={refresh} aria-label="Refresh metrics">
            <RefreshIcon fontSize="small" />
          </IconButton>
        </Tooltip>
      </Box>

      {status === 'disabled' && (
        <Alert severity="info" sx={{ mb: 1.5 }}>
          <AlertTitle>Metrics are disabled</AlertTitle>
          Start MockServer with metrics enabled to view live metrics here:
          <Box component="pre" sx={{ mt: 1, mb: 0, p: 1, bgcolor: 'action.hover', borderRadius: 1, typography: 'subtitle2', fontWeight: 400, fontFamily: monospaceFontFamily, overflow: 'auto' }}>
{`-Dmockserver.metricsEnabled=true
# or environment variable:
MOCKSERVER_METRICS_ENABLED=true`}
          </Box>
        </Alert>
      )}

      {status === 'error' && (
        <Alert severity="error" sx={{ mb: 1.5 }} action={
          <IconButton color="inherit" size="small" onClick={refresh} aria-label="Retry"><RefreshIcon fontSize="small" /></IconButton>
        }>
          <AlertTitle>Could not load metrics</AlertTitle>
          {error}
        </Alert>
      )}

      {status === 'loading' && history.length === 0 && (
        <Box data-testid="metrics-loading-skeleton">
          {/* Hero stat-card row placeholder */}
          <Box sx={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(140px, 1fr))', gap: 1, mb: 1.5 }}>
            {[0, 1, 2, 3].map((i) => (
              <Card key={i} elevation={1} sx={{ p: 1.25 }}>
                <Skeleton variant="text" width="55%" height={14} />
                <Skeleton variant="text" width="70%" height={32} />
              </Card>
            ))}
          </Box>
          {/* Chart-block placeholders */}
          {[0, 1, 2].map((i) => (
            <Paper key={i} variant="outlined" sx={{ p: 1.25, mb: 1.5 }}>
              <Skeleton variant="text" width="40%" height={16} />
              <Skeleton variant="rounded" height={i === 0 ? 180 : 160} sx={{ mt: 1 }} />
            </Paper>
          ))}
        </Box>
      )}

      {latest && (
        <>
          {/* KPI hero stat cards — headline request counters (and latency
              quantiles when available) presented as prominent cards above the
              chart stack, rather than a monotonous list of identical papers. */}
          <Box
            sx={{
              display: 'grid',
              gridTemplateColumns: 'repeat(auto-fit, minmax(140px, 1fr))',
              gap: 1,
              mb: 1.5,
            }}
          >
            {heroCounters.map(({ label, value }) => (
              <Card
                key={label}
                elevation={1}
                sx={{
                  p: 1.25,
                  transition: transitions.forProps(['box-shadow', 'transform']),
                  '&:hover': {
                    boxShadow: (t) => t.shadows[4],
                    transform: 'translateY(-1px)',
                  },
                }}
              >
                <Typography variant="caption" color="text.secondary">
                  {label}
                </Typography>
                <Typography variant="h5" sx={{ fontWeight: 700, lineHeight: 1.2, mt: 0.25 }}>
                  {value.toLocaleString()}
                </Typography>
              </Card>
            ))}
            {latencyHeroStats.map(({ label, text }) => (
              <Card
                key={`latency-${label}`}
                elevation={1}
                sx={{
                  p: 1.25,
                  transition: transitions.forProps(['box-shadow', 'transform']),
                  '&:hover': {
                    boxShadow: (t) => t.shadows[4],
                    transform: 'translateY(-1px)',
                  },
                }}
              >
                <Typography variant="caption" color="text.secondary">
                  latency {label}
                </Typography>
                <Typography variant="h5" sx={{ fontWeight: 700, lineHeight: 1.2, mt: 0.25 }}>
                  {text}
                </Typography>
              </Card>
            ))}
          </Box>

          {/* Throughput */}
          <Paper variant="outlined" sx={{ p: 1.25, mb: 1.5 }}>
            <Box sx={{ display: 'flex', alignItems: 'baseline', gap: 1 }}>
              <Typography variant="caption" color="text.secondary">Throughput (derived)</Typography>
              <Typography variant="h6" sx={{ fontWeight: 700 }}>{rps.toFixed(1)} req/s</Typography>
            </Box>
            <MetricsLineChart
              timestamps={timestamps}
              height={180}
              series={[{ data: ratePerSecond(history, 'requests_received_count'), label: 'req/s' }]}
              valueFormatter={(v) => `${v.toFixed(1)}/s`}
            />
          </Paper>

          {/* Request latency (only when the server exposes the duration histogram) */}
          {latencyEnabled && latest && (
            <Paper variant="outlined" sx={{ p: 1.25, mb: 1.5 }}>
              <Typography variant="caption" color="text.secondary">Request latency — cumulative since server start</Typography>
              <Box sx={{ mt: 1 }}>
                <MetricsLineChart
                  timestamps={timestamps}
                  height={140}
                  valueFormatter={(v) => `${v.toFixed(1)} ms`}
                  series={[{
                    data: history.map((snapshot) => {
                      const seconds = histogramQuantile(snapshot.samples, LATENCY_METRIC, 0.95);
                      return seconds == null ? 0 : seconds * 1000;
                    }),
                    label: 'p95 ms (cumulative)',
                  }]}
                />
              </Box>
            </Paper>
          )}

          {/* HTTP Chaos Faults (only when a chaos metric is present and has non-zero data) */}
          {chaosEnabled && chaosHasData && (
            <Paper variant="outlined" sx={{ p: 1.25, mb: 1.5 }}>
              <Typography variant="caption" color="text.secondary">HTTP Chaos Faults</Typography>
              {chaosFaultTotals.length > 0 && (
                <Box sx={{ display: 'flex', gap: 3, mt: 0.5, flexWrap: 'wrap' }}>
                  {chaosFaultTotals.map(({ faultType, value }) => (
                    <Box key={faultType}>
                      <Typography variant="h5" sx={{ fontWeight: 700, lineHeight: 1.2 }}>
                        {value.toLocaleString()}
                      </Typography>
                      <Typography variant="caption" color="text.secondary">{faultType} faults</Typography>
                    </Box>
                  ))}
                </Box>
              )}
              {chaosFaultTypes.length > 0 && (
                <Box sx={{ mt: 1 }}>
                  <Typography variant="caption" color="text.secondary">Faults injected by type (cumulative)</Typography>
                  <MetricsLineChart
                    timestamps={timestamps}
                    height={180}
                    valueFormatter={(v) => Math.round(v).toLocaleString()}
                    series={chaosFaultTypes.map((ft) => ({
                      data: gaugeSeriesByLabel(history, CHAOS_METRIC, 'fault_type', ft),
                      label: chaosFaultLabel(ft),
                    }))}
                  />
                </Box>
              )}
              {activeServiceChaosEnabled && (
                <Box sx={{ mt: 1 }}>
                  <Typography variant="caption" color="text.secondary">Active service-scoped chaos by type</Typography>
                  <MetricsLineChart
                    timestamps={timestamps}
                    height={180}
                    valueFormatter={(v) => Math.round(v).toLocaleString()}
                    series={activeChaosFaultTypes.map((ft) => ({
                      data: gaugeSeriesByLabel(history, ACTIVE_CHAOS_METRIC, 'fault_type', ft),
                      label: chaosFaultLabel(ft),
                    }))}
                  />
                </Box>
              )}
              {(autoHaltEnabled || llmCostBudgetEnabled) && (
                <Box sx={{ mt: 1 }}>
                  <Typography variant="caption" color="text.secondary" sx={{ fontWeight: 600 }}>Circuit Breakers</Typography>
                  {autoHaltEnabled && (
                    <Box sx={{ mt: 0.5 }}>
                      <Box sx={{ display: 'flex', alignItems: 'baseline', gap: 1 }}>
                        <Typography variant="caption" color="text.secondary">Chaos auto-halt</Typography>
                        <Chip
                          size="small"
                          label={autoHaltTotal > 0 ? `${autoHaltTotal} halt${autoHaltTotal === 1 ? '' : 's'}` : 'no halts'}
                          color={autoHaltTotal > 0 ? 'error' : 'success'}
                          variant="outlined"
                        />
                      </Box>
                      <MetricsLineChart
                        timestamps={timestamps}
                        height={100}
                        valueFormatter={(v) => Math.round(v).toLocaleString()}
                        series={[{
                          data: gaugeSeries(history, AUTO_HALT_METRIC),
                          label: 'auto-halt count',
                        }]}
                      />
                    </Box>
                  )}
                  {llmCostBudgetEnabled && (
                    <Box sx={{ mt: 0.5 }}>
                      <Box sx={{ display: 'flex', alignItems: 'baseline', gap: 1 }}>
                        <Typography variant="caption" color="text.secondary">LLM cost-budget</Typography>
                        <Chip
                          size="small"
                          label={llmCostBudgetTrippedTotal > 0 ? `${llmCostBudgetTrippedTotal} trip${llmCostBudgetTrippedTotal === 1 ? '' : 's'}` : 'within budget'}
                          color={llmCostBudgetTrippedTotal > 0 ? 'error' : 'success'}
                          variant="outlined"
                        />
                        {llmCostUsdTotal > 0 && (
                          <Typography variant="caption" color="text.secondary">
                            ${llmCostUsdTotal.toFixed(4)} spent
                          </Typography>
                        )}
                      </Box>
                      <MetricsLineChart
                        timestamps={timestamps}
                        height={100}
                        valueFormatter={(v) => Math.round(v).toLocaleString()}
                        series={[{
                          data: gaugeSeries(history, LLM_COST_BUDGET_TRIPPED_METRIC),
                          label: 'budget trips',
                        }]}
                      />
                    </Box>
                  )}
                </Box>
              )}
            </Paper>
          )}

          {/* HTTP request activity over time — the four request counters as separate lines */}
          <Paper variant="outlined" sx={{ p: 1.25, mb: 1.5 }}>
            <Typography variant="caption" color="text.secondary">HTTP request activity (cumulative)</Typography>
            <MetricsLineChart
              timestamps={timestamps}
              height={200}
              valueFormatter={(v) => Math.round(v).toLocaleString()}
              series={SUMMARY.map(({ name, label }) => ({ data: gaugeSeries(history, name), label }))}
            />
          </Paper>

          {/* Async message activity — broker messages published/consumed, kept separate from HTTP
              request counts (different semantics/units). Only shown when async metrics have data. */}
          {asyncEnabled && asyncHasData && (
            <Paper variant="outlined" sx={{ p: 1.25, mb: 1.5 }}>
              <Typography variant="caption" color="text.secondary">Async message activity (cumulative)</Typography>
              <Box sx={{ display: 'flex', gap: 3, mt: 0.5, flexWrap: 'wrap' }}>
                <Box>
                  <Typography variant="h5" sx={{ fontWeight: 700, lineHeight: 1.2 }}>
                    {asyncPublishedTotal.toLocaleString()}
                  </Typography>
                  <Typography variant="caption" color="text.secondary">published</Typography>
                </Box>
                <Box>
                  <Typography variant="h5" sx={{ fontWeight: 700, lineHeight: 1.2 }}>
                    {asyncConsumedTotal.toLocaleString()}
                  </Typography>
                  <Typography variant="caption" color="text.secondary">consumed</Typography>
                </Box>
              </Box>
              <Box sx={{ mt: 1 }}>
                <MetricsLineChart
                  timestamps={timestamps}
                  height={200}
                  valueFormatter={(v) => Math.round(v).toLocaleString()}
                  series={[
                    { data: gaugeSeriesSum(history, ASYNC_PUBLISHED_METRIC), label: 'Published' },
                    { data: gaugeSeriesSum(history, ASYNC_CONSUMED_METRIC), label: 'Consumed' },
                  ]}
                />
              </Box>
            </Paper>
          )}

          {/* Actions executed over time — one line per action type */}
          <Paper variant="outlined" sx={{ p: 1.25, mb: 1.5 }}>
            <Typography variant="caption" color="text.secondary">Actions executed</Typography>
            {maxAction === 0 ? (
              <Typography variant="body2" sx={{ mt: 0.5 }} color="text.secondary">
                No actions executed yet.
              </Typography>
            ) : (
              <MetricsLineChart
                timestamps={timestamps}
                height={200}
                valueFormatter={(v) => Math.round(v).toLocaleString()}
                series={actionRows
                  .filter((r) => r.value > 0)
                  .map((r) => ({
                    data: gaugeSeries(history, r.name),
                    label: r.label.charAt(0).toUpperCase() + r.label.slice(1),
                  }))}
              />
            )}
          </Paper>

          {/* Expectations by type — one line per action_type label (gauge) */}
          <Paper variant="outlined" sx={{ p: 1.25, mb: 1.5 }}>
            <Typography variant="caption" color="text.secondary">Expectations by type</Typography>
            {!expectationsByTypeEnabled ? (
              <Typography variant="body2" sx={{ mt: 0.5 }} color="text.secondary">
                No expectations configured.
              </Typography>
            ) : (
              <MetricsLineChart
                timestamps={timestamps}
                height={200}
                valueFormatter={(v) => Math.round(v).toLocaleString()}
                series={expectationsByType.map((r) => ({
                  data: gaugeSeriesByLabel(history, EXPECTATIONS_BY_TYPE_METRIC, 'action_type', r.actionType),
                  label: prettyEnumLabel(r.actionType),
                }))}
              />
            )}
          </Paper>

          {/* MCP tool calls — one line per tool label (counter, shown cumulative) */}
          <Paper variant="outlined" sx={{ p: 1.25, mb: 1.5 }}>
            <Typography variant="caption" color="text.secondary">MCP tool calls</Typography>
            {!mcpToolCallsEnabled ? (
              <Typography variant="body2" sx={{ mt: 0.5 }} color="text.secondary">
                No MCP tool calls recorded.
              </Typography>
            ) : (
              <MetricsLineChart
                timestamps={timestamps}
                height={200}
                valueFormatter={(v) => Math.round(v).toLocaleString()}
                series={mcpToolRows.map((r) => ({
                  data: gaugeSeriesByLabel(history, MCP_TOOL_CALLS_METRIC, 'tool', r.tool),
                  label: r.tool,
                }))}
              />
            )}
          </Paper>

          {/* Capacity ("running out of room") — the event-log and expectation-store sites charted
              against their budgets, plus the accept-queue backlog, shown just above the JVM heap
              section they sit alongside. Each card appears only when the server exposes its metric. */}
          {eventLogEnabled && (
            <>
              <CapacityChart
                title="Event log — retained memory"
                budgetLabel="budget"
                history={history}
                timestamps={timestamps}
                valueMetric={EVENT_LOG_RETAINED_BYTES_METRIC}
                budgetMetric={EVENT_LOG_MAX_RETAINED_BYTES_METRIC}
                budgetValue={eventLogMaxRetainedBytes}
                valueFormatter={formatBytes}
              />
              <CapacityChart
                title="Event log — retained entries"
                budgetLabel="limit"
                history={history}
                timestamps={timestamps}
                valueMetric={EVENT_LOG_RETAINED_ENTRIES_METRIC}
                budgetMetric={EVENT_LOG_MAX_RETAINED_ENTRIES_METRIC}
                budgetValue={eventLogMaxRetainedEntries}
                valueFormatter={countFormatter}
              />
              <CapacityChart
                title="Event log — in-flight memory"
                budgetLabel="budget"
                history={history}
                timestamps={timestamps}
                valueMetric={EVENT_LOG_IN_FLIGHT_BYTES_METRIC}
                budgetMetric={EVENT_LOG_MAX_IN_FLIGHT_BYTES_METRIC}
                budgetValue={eventLogMaxInFlightBytes}
                valueFormatter={formatBytes}
              />
              <CapacityChart
                title="Event log — ring buffer"
                budgetLabel="capacity"
                history={history}
                timestamps={timestamps}
                valueMetric={EVENT_LOG_RING_OCCUPANCY_METRIC}
                budgetMetric={EVENT_LOG_RING_CAPACITY_METRIC}
                budgetValue={eventLogRingCapacity}
                valueFormatter={countFormatter}
              />
            </>
          )}
          {expectationBytesEnabled && (
            <CapacityChart
              title="Expectation store memory"
              budgetLabel="budget"
              history={history}
              timestamps={timestamps}
              valueMetric={EXPECTATIONS_BYTES_METRIC}
              budgetMetric={MAX_EXPECTATIONS_BYTES_METRIC}
              budgetValue={maxExpectationsBytes}
              valueFormatter={formatBytes}
            />
          )}

          {acceptQueueEnabled && (
            <Paper variant="outlined" sx={{ p: 1.25, mb: 1.5 }}>
              <Typography variant="caption" color="text.secondary">Accept queue backlog</Typography>
              <Box sx={{ display: 'flex', gap: 3, mt: 0.5, flexWrap: 'wrap' }}>
                <Box>
                  <Typography variant="h5" sx={{ fontWeight: 700, lineHeight: 1.2 }}>
                    {acceptQueueConfigured.toLocaleString()}
                  </Typography>
                  <Typography variant="caption" color="text.secondary">configured (soBacklog)</Typography>
                </Box>
                {acceptQueueEffectiveEnabled && (
                  <Box>
                    <Typography variant="h5" sx={{ fontWeight: 700, lineHeight: 1.2 }}>
                      {acceptQueueEffective.toLocaleString()}
                    </Typography>
                    <Typography variant="caption" color="text.secondary">effective (kernel-capped)</Typography>
                  </Box>
                )}
              </Box>
            </Paper>
          )}

          {/* JVM runtime (Memory, Threads & GC) — at the bottom; only when the server exposes JVM metrics */}
          {jvmEnabled && latest && (
            <Box sx={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(280px, 1fr))', gap: 1 }}>
              <Paper variant="outlined" sx={{ p: 1.25 }}>
                <Typography variant="caption" color="text.secondary">JVM heap memory</Typography>
                <MetricsLineChart
                  timestamps={timestamps}
                  height={200}
                  valueFormatter={formatBytes}
                  series={[
                    { data: gaugeSeriesByLabel(history, 'jvm_memory_used_bytes', 'area', 'heap'), label: 'used' },
                    { data: gaugeSeriesByLabel(history, 'jvm_memory_committed_bytes', 'area', 'heap'), label: 'committed' },
                  ]}
                />
              </Paper>
              <Paper variant="outlined" sx={{ p: 1.25 }}>
                <Typography variant="caption" color="text.secondary">Threads &amp; GC</Typography>
                <Box sx={{ display: 'flex', gap: 3, mt: 0.5, flexWrap: 'wrap' }}>
                  <Box>
                    <Typography variant="h5" sx={{ fontWeight: 700, lineHeight: 1.2 }}>
                      {metricValue(latest.samples, 'jvm_threads_current').toLocaleString()}
                    </Typography>
                    <Typography variant="caption" color="text.secondary">
                      threads ({metricValue(latest.samples, 'jvm_threads_daemon')} daemon)
                    </Typography>
                  </Box>
                  <Box>
                    <Typography variant="h5" sx={{ fontWeight: 700, lineHeight: 1.2 }}>
                      {metricValue(latest.samples, 'jvm_gc_collection_count').toLocaleString()}
                    </Typography>
                    <Typography variant="caption" color="text.secondary">
                      GC collections ({metricValue(latest.samples, 'jvm_gc_collection_seconds_sum').toFixed(1)}s)
                    </Typography>
                  </Box>
                </Box>
                <Box sx={{ mt: 1 }}>
                  <MetricsLineChart
                    timestamps={timestamps}
                    height={110}
                    series={[{ data: gaugeSeries(history, 'jvm_threads_current'), label: 'threads' }]}
                  />
                </Box>
              </Paper>
            </Box>
          )}
        </>
      )}
    </Box>
  );
}
