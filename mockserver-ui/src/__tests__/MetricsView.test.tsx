import { describe, it, expect, vi, afterEach } from 'vitest';
import { render, screen, waitFor, cleanup } from '@testing-library/react';
import MetricsView from '../components/MetricsView';

const params = { host: '127.0.0.1', port: '1080', secure: false };

function stubFetch(status: number, body = '') {
  vi.stubGlobal(
    'fetch',
    vi.fn(async () => ({
      status,
      ok: status >= 200 && status < 300,
      statusText: 'stub',
      text: async () => body,
    })),
  );
}

afterEach(() => {
  cleanup();
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
});

describe('MetricsView', () => {
  it('shows the disabled guidance when /mockserver/metrics returns 404', async () => {
    stubFetch(404);
    render(<MetricsView connectionParams={params} />);
    await waitFor(() => expect(screen.getByText(/Metrics are disabled/i)).toBeInTheDocument());
    expect(screen.getByText(/MOCKSERVER_METRICS_ENABLED=true/)).toBeInTheDocument();
  });

  it('renders the metric panels from parsed metrics', async () => {
    stubFetch(
      200,
      'requests_received_count 42.0\nresponse_expectations_matched_count 7.0\nexpectations_not_matched_count 3.0\n',
    );
    render(<MetricsView connectionParams={params} />);
    await waitFor(() => expect(screen.getByText('Throughput (derived)')).toBeInTheDocument());
    expect(screen.getByText('HTTP request activity (cumulative)')).toBeInTheDocument();
    // the headline counters are now presented as KPI hero stat cards at the top
    expect(screen.getByText('Requests received')).toBeInTheDocument();
    expect(screen.getByText('42')).toBeInTheDocument();
  });

  it('renders a separate Async message activity panel when async metrics are present', async () => {
    stubFetch(
      200,
      'requests_received_count 42.0\n' +
        'mock_server_async_messages_published_total{channel="orders/placed"} 6.0\n' +
        'mock_server_async_messages_published_total{channel="payments/processed"} 4.0\n' +
        'mock_server_async_messages_consumed_total{channel="orders/placed"} 6.0\n',
    );
    render(<MetricsView connectionParams={params} />);
    await waitFor(() => expect(screen.getByText('Async message activity (cumulative)')).toBeInTheDocument());
    // summed across channels: published 6+4 = 10, consumed 6
    expect(screen.getByText('10')).toBeInTheDocument();
    expect(screen.getByText('published')).toBeInTheDocument();
    expect(screen.getByText('consumed')).toBeInTheDocument();
  });

  it('hides the Async message activity panel when no async metrics are present', async () => {
    stubFetch(200, 'requests_received_count 42.0\n');
    render(<MetricsView connectionParams={params} />);
    await waitFor(() => expect(screen.getByText('Throughput (derived)')).toBeInTheDocument());
    expect(screen.queryByText('Async message activity (cumulative)')).not.toBeInTheDocument();
  });

  it('renders the request-activity and actions-executed graph panels', async () => {
    stubFetch(
      200,
      'requests_received_count 42.0\nresponse_expectations_matched_count 7.0\n' +
        'expectations_not_matched_count 3.0\nforward_expectations_matched_count 1.0\n' +
        'response_actions_count 5.0\n',
    );
    render(<MetricsView connectionParams={params} />);
    // the request-activity graph is the second-last panel, actions-executed the last
    await waitFor(() => expect(screen.getByText('HTTP request activity (cumulative)')).toBeInTheDocument());
    expect(screen.getByText('Actions executed')).toBeInTheDocument();
    // with an action counter present, the actions panel shows the chart, not the empty state
    expect(screen.queryByText('No actions executed yet.')).not.toBeInTheDocument();
  });

  it('surfaces the MockServer version from build_info', async () => {
    stubFetch(200, 'requests_received_count 1.0\nmock_server_build_info{version="6.1.0"} 1.0\n');
    render(<MetricsView connectionParams={params} />);
    await waitFor(() => expect(screen.getByText('MockServer 6.1.0')).toBeInTheDocument());
  });

  it('renders the JVM section when JVM metrics are present', async () => {
    stubFetch(
      200,
      [
        'requests_received_count 5.0',
        'jvm_memory_used_bytes{area="heap"} 1048576',
        'jvm_memory_committed_bytes{area="heap"} 2097152',
        'jvm_threads_current 12.0',
        'jvm_threads_daemon 8.0',
        'jvm_gc_collection_count 3.0',
        'jvm_gc_collection_seconds_sum 0.5',
        '',
      ].join('\n'),
    );
    render(<MetricsView connectionParams={params} />);
    await waitFor(() => expect(screen.getByText('JVM heap memory')).toBeInTheDocument());
    expect(screen.getByText('Threads & GC')).toBeInTheDocument();
  });

  it('hides the JVM section when JVM metrics are absent', async () => {
    stubFetch(200, 'requests_received_count 5.0\n');
    render(<MetricsView connectionParams={params} />);
    await waitFor(() => expect(screen.getByText('Throughput (derived)')).toBeInTheDocument());
    expect(screen.queryByText('JVM heap memory')).not.toBeInTheDocument();
  });

  it('renders the expectation store memory panel and marks a disabled (0) byte budget as unbounded', async () => {
    stubFetch(
      200,
      [
        'requests_received_count 5.0',
        'mock_server_expectations_bytes 4096',
        'mock_server_max_expectations_bytes 0',
        '',
      ].join('\n'),
    );
    render(<MetricsView connectionParams={params} />);
    // budget 0 = disabled -> the caption says "no limit set", not "0 bytes" or a full bar
    await waitFor(() =>
      expect(screen.getByText('Expectation store memory (no limit set)')).toBeInTheDocument(),
    );
  });

  it('shows the expectation store budget line when a byte budget is set', async () => {
    stubFetch(
      200,
      [
        'requests_received_count 5.0',
        'mock_server_expectations_bytes 4096',
        'mock_server_max_expectations_bytes 268435456',
        '',
      ].join('\n'),
    );
    render(<MetricsView connectionParams={params} />);
    // budget in force -> plain title, no "(no limit set)" suffix
    await waitFor(() => expect(screen.getByText('Expectation store memory')).toBeInTheDocument());
    expect(screen.queryByText('Expectation store memory (no limit set)')).not.toBeInTheDocument();
  });

  it('hides the expectation store memory panel when the byte gauge is absent', async () => {
    stubFetch(200, 'requests_received_count 5.0\n');
    render(<MetricsView connectionParams={params} />);
    await waitFor(() => expect(screen.getByText('Throughput (derived)')).toBeInTheDocument());
    expect(screen.queryByText(/Expectation store memory/)).not.toBeInTheDocument();
  });

  it('renders all four event-log capacity panels when the gauges are present', async () => {
    stubFetch(
      200,
      [
        'requests_received_count 5.0',
        'mock_server_event_log_retained_bytes 134217728',
        'mock_server_event_log_max_retained_bytes 268435456',
        'mock_server_event_log_retained_entries 5000',
        'mock_server_event_log_max_retained_entries 60000',
        'mock_server_event_log_in_flight_bytes 1048576',
        'mock_server_event_log_max_in_flight_bytes 268435456',
        'mock_server_event_log_ring_occupancy 1200',
        'mock_server_event_log_ring_capacity 16384',
        '',
      ].join('\n'),
    );
    render(<MetricsView connectionParams={params} />);
    await waitFor(() => expect(screen.getByText('Event log — retained memory')).toBeInTheDocument());
    expect(screen.getByText('Event log — retained entries')).toBeInTheDocument();
    expect(screen.getByText('Event log — in-flight memory')).toBeInTheDocument();
    expect(screen.getByText('Event log — ring buffer')).toBeInTheDocument();
    // every budget in force -> none marked unbounded
    expect(screen.queryByText(/\(no limit set\)/)).not.toBeInTheDocument();
  });

  it('marks a disabled (0) event-log byte budget as unbounded, leaving positive budgets bounded', async () => {
    stubFetch(
      200,
      [
        'requests_received_count 5.0',
        'mock_server_event_log_retained_bytes 134217728',
        'mock_server_event_log_max_retained_bytes 0',
        'mock_server_event_log_retained_entries 5000',
        'mock_server_event_log_max_retained_entries 60000',
        'mock_server_event_log_in_flight_bytes 1048576',
        'mock_server_event_log_max_in_flight_bytes 268435456',
        'mock_server_event_log_ring_occupancy 1200',
        'mock_server_event_log_ring_capacity 16384',
        '',
      ].join('\n'),
    );
    render(<MetricsView connectionParams={params} />);
    // only the disabled retained-byte budget is marked unbounded; the entry cap stays bounded
    await waitFor(() =>
      expect(screen.getByText('Event log — retained memory (no limit set)')).toBeInTheDocument(),
    );
    expect(screen.getByText('Event log — retained entries')).toBeInTheDocument();
    expect(screen.getByText('Event log — in-flight memory')).toBeInTheDocument();
  });

  it('hides the event-log capacity panels when the gauges are absent', async () => {
    stubFetch(200, 'requests_received_count 5.0\n');
    render(<MetricsView connectionParams={params} />);
    await waitFor(() => expect(screen.getByText('Throughput (derived)')).toBeInTheDocument());
    expect(screen.queryByText(/Event log —/)).not.toBeInTheDocument();
  });

  it('renders the accept queue backlog with configured and effective when both are present', async () => {
    stubFetch(
      200,
      [
        'requests_received_count 5.0',
        'mock_server_accept_queue_backlog_configured 1024',
        'mock_server_accept_queue_backlog_effective 128',
        '',
      ].join('\n'),
    );
    render(<MetricsView connectionParams={params} />);
    await waitFor(() => expect(screen.getByText('Accept queue backlog')).toBeInTheDocument());
    expect(screen.getByText('configured (soBacklog)')).toBeInTheDocument();
    expect(screen.getByText('effective (kernel-capped)')).toBeInTheDocument();
    expect(screen.getByText('1,024')).toBeInTheDocument();
    expect(screen.getByText('128')).toBeInTheDocument();
  });

  it('omits the effective backlog stat when only the configured gauge is present', async () => {
    stubFetch(
      200,
      ['requests_received_count 5.0', 'mock_server_accept_queue_backlog_configured 1024', ''].join('\n'),
    );
    render(<MetricsView connectionParams={params} />);
    await waitFor(() => expect(screen.getByText('Accept queue backlog')).toBeInTheDocument());
    expect(screen.getByText('configured (soBacklog)')).toBeInTheDocument();
    expect(screen.queryByText('effective (kernel-capped)')).not.toBeInTheDocument();
  });

  it('renders the latency panel when the duration histogram is present', async () => {
    stubFetch(
      200,
      [
        'requests_received_count 100.0',
        'mock_server_request_duration_seconds_bucket{le="0.05"} 50',
        'mock_server_request_duration_seconds_bucket{le="0.1"} 90',
        'mock_server_request_duration_seconds_bucket{le="+Inf"} 100',
        'mock_server_request_duration_seconds_count 100',
        'mock_server_request_duration_seconds_sum 5.0',
        '',
      ].join('\n'),
    );
    render(<MetricsView connectionParams={params} />);
    await waitFor(() => expect(screen.getByText('Request latency — cumulative since server start')).toBeInTheDocument());
    // p50/p95/p99 are surfaced as hero stat cards above the chart stack.
    expect(screen.getByText('latency p95')).toBeInTheDocument();
    expect(screen.getByText('latency p50')).toBeInTheDocument();
    expect(screen.getByText('latency p99')).toBeInTheDocument();
  });

  it('shows skeleton placeholders (not a bare spinner) while the first scrape is in flight', async () => {
    // A fetch that never resolves keeps the view in the initial loading state.
    vi.stubGlobal('fetch', vi.fn(() => new Promise(() => {})));
    const { container } = render(<MetricsView connectionParams={params} />);
    await waitFor(() =>
      expect(screen.getByTestId('metrics-loading-skeleton')).toBeInTheDocument(),
    );
    // MUI Skeletons render with the MuiSkeleton-root class; the old CircularProgress is gone.
    expect(container.querySelector('.MuiSkeleton-root')).not.toBeNull();
    expect(container.querySelector('.MuiCircularProgress-root')).toBeNull();
  });

  it('leads with KPI hero stat cards showing the headline request counters', async () => {
    stubFetch(
      200,
      'requests_received_count 42.0\nresponse_expectations_matched_count 7.0\n' +
        'expectations_not_matched_count 3.0\nforward_expectations_matched_count 1.0\n',
    );
    render(<MetricsView connectionParams={params} />);
    // The four headline counters appear as labelled hero cards with their values.
    await waitFor(() => expect(screen.getByText('Requests received')).toBeInTheDocument());
    expect(screen.getByText('Matched')).toBeInTheDocument();
    expect(screen.getByText('Not matched')).toBeInTheDocument();
    expect(screen.getByText('Forwarded')).toBeInTheDocument();
    // The headline value now appears in the hero card (it lived nowhere visible before).
    expect(screen.getByText('42')).toBeInTheDocument();
  });

  it('hides the latency panel when the histogram is absent', async () => {
    stubFetch(200, 'requests_received_count 5.0\n');
    render(<MetricsView connectionParams={params} />);
    await waitFor(() => expect(screen.getByText('Throughput (derived)')).toBeInTheDocument());
    expect(screen.queryByText('Request latency — cumulative since server start')).not.toBeInTheDocument();
  });

  it('renders a stat for every HTTP chaos fault type the server emits', async () => {
    stubFetch(
      200,
      [
        'requests_received_count 10.0',
        'mock_server_http_chaos_injected_total{fault_type="drop"} 1.0',
        'mock_server_http_chaos_injected_total{fault_type="error"} 2.0',
        'mock_server_http_chaos_injected_total{fault_type="latency"} 3.0',
        'mock_server_http_chaos_injected_total{fault_type="truncate"} 4.0',
        'mock_server_http_chaos_injected_total{fault_type="malformed"} 5.0',
        'mock_server_http_chaos_injected_total{fault_type="slow"} 6.0',
        'mock_server_http_chaos_injected_total{fault_type="quota"} 7.0',
        '',
      ].join('\n'),
    );
    render(<MetricsView connectionParams={params} />);
    await waitFor(() => expect(screen.getByText('HTTP Chaos Faults')).toBeInTheDocument());
    const expected: [string, string][] = [
      ['drop', '1'], ['error', '2'], ['latency', '3'], ['truncate', '4'],
      ['malformed', '5'], ['slow', '6'], ['quota', '7'],
    ];
    for (const [fault, value] of expected) {
      const caption = screen.getByText(`${fault} faults`);
      expect(caption).toBeInTheDocument();
      // the count is the sibling stat above the caption within the same Box
      expect(caption.parentElement?.textContent).toContain(value);
    }
  });

  it('charts active service-scoped chaos by fault type', async () => {
    stubFetch(
      200,
      [
        'requests_received_count 10.0',
        'mock_server_active_service_chaos{fault_type="error"} 2.0',
        'mock_server_active_service_chaos{fault_type="latency"} 1.0',
        'mock_server_active_service_chaos{fault_type="drop"} 0.0',
        '',
      ].join('\n'),
    );
    render(<MetricsView connectionParams={params} />);
    await waitFor(() => expect(screen.getByText('HTTP Chaos Faults')).toBeInTheDocument());
    // rendered as a by-type chart, not a single "active services" counter
    expect(screen.getByText('Active service-scoped chaos by type')).toBeInTheDocument();
    expect(screen.queryByText('active services')).not.toBeInTheDocument();
  });

  it('hides the HTTP Chaos Faults section when no chaos metric is present', async () => {
    stubFetch(200, 'requests_received_count 5.0\n');
    render(<MetricsView connectionParams={params} />);
    await waitFor(() => expect(screen.getByText('Throughput (derived)')).toBeInTheDocument());
    expect(screen.queryByText('HTTP Chaos Faults')).not.toBeInTheDocument();
  });

  it('hides the HTTP Chaos Faults section when all chaos metrics are zero', async () => {
    stubFetch(
      200,
      [
        'requests_received_count 5.0',
        'mock_server_http_chaos_injected_total{fault_type="error"} 0.0',
        'mock_server_http_chaos_injected_total{fault_type="latency"} 0.0',
        'mock_server_active_service_chaos{fault_type="error"} 0.0',
        'mock_server_active_service_chaos{fault_type="drop"} 0.0',
        '',
      ].join('\n'),
    );
    render(<MetricsView connectionParams={params} />);
    await waitFor(() => expect(screen.getByText('Throughput (derived)')).toBeInTheDocument());
    expect(screen.queryByText('HTTP Chaos Faults')).not.toBeInTheDocument();
  });

  it('renders the Expectations by type chart with per-label lines', async () => {
    stubFetch(
      200,
      [
        'requests_received_count 10.0',
        'mock_server_expectations_by_type{action_type="RESPONSE"} 3.0',
        'mock_server_expectations_by_type{action_type="FORWARD"} 1.0',
        'mock_server_expectations_by_type{action_type="LLM_RESPONSE"} 0.0',
        '',
      ].join('\n'),
    );
    render(<MetricsView connectionParams={params} />);
    await waitFor(() => expect(screen.getByText('Expectations by type')).toBeInTheDocument());
    // zero-value types are filtered out, so LLM_RESPONSE should not appear
    expect(screen.queryByText('No expectations configured.')).not.toBeInTheDocument();
  });

  it('shows empty state for Expectations by type when no expectations exist', async () => {
    stubFetch(200, 'requests_received_count 5.0\n');
    render(<MetricsView connectionParams={params} />);
    await waitFor(() => expect(screen.getByText('Expectations by type')).toBeInTheDocument());
    expect(screen.getByText('No expectations configured.')).toBeInTheDocument();
  });

  it('renders the MCP tool calls chart with per-tool lines', async () => {
    stubFetch(
      200,
      [
        'requests_received_count 10.0',
        'mock_server_mcp_tool_calls_total{tool="list_mock_tools"} 5.0',
        'mock_server_mcp_tool_calls_total{tool="create_expectation"} 2.0',
        'mock_server_mcp_tool_calls_total{tool="unused_tool"} 0.0',
        '',
      ].join('\n'),
    );
    render(<MetricsView connectionParams={params} />);
    await waitFor(() => expect(screen.getByText('MCP tool calls')).toBeInTheDocument());
    // zero-value tools are filtered out
    expect(screen.queryByText('No MCP tool calls recorded.')).not.toBeInTheDocument();
  });

  it('shows empty state for MCP tool calls when no calls exist', async () => {
    stubFetch(200, 'requests_received_count 5.0\n');
    render(<MetricsView connectionParams={params} />);
    await waitFor(() => expect(screen.getByText('MCP tool calls')).toBeInTheDocument());
    expect(screen.getByText('No MCP tool calls recorded.')).toBeInTheDocument();
  });

  it('renders auto-halt circuit breaker with non-zero count', async () => {
    stubFetch(
      200,
      [
        'requests_received_count 10.0',
        'mock_server_chaos_auto_halt 3.0',
        '',
      ].join('\n'),
    );
    render(<MetricsView connectionParams={params} />);
    await waitFor(() => expect(screen.getByText('Chaos auto-halt')).toBeInTheDocument());
    expect(screen.getByText('3 halts')).toBeInTheDocument();
  });

  it('renders auto-halt circuit breaker with zero count showing "no halts"', async () => {
    stubFetch(
      200,
      [
        'requests_received_count 10.0',
        'mock_server_chaos_auto_halt 0.0',
        '',
      ].join('\n'),
    );
    render(<MetricsView connectionParams={params} />);
    await waitFor(() => expect(screen.getByText('Chaos auto-halt')).toBeInTheDocument());
    expect(screen.getByText('no halts')).toBeInTheDocument();
  });

  it('shows singular "halt" for count of 1', async () => {
    stubFetch(
      200,
      [
        'requests_received_count 10.0',
        'mock_server_chaos_auto_halt 1.0',
        '',
      ].join('\n'),
    );
    render(<MetricsView connectionParams={params} />);
    await waitFor(() => expect(screen.getByText('Chaos auto-halt')).toBeInTheDocument());
    expect(screen.getByText('1 halt')).toBeInTheDocument();
  });

  it('hides auto-halt section when metric is absent', async () => {
    stubFetch(200, 'requests_received_count 5.0\n');
    render(<MetricsView connectionParams={params} />);
    await waitFor(() => expect(screen.getByText('Throughput (derived)')).toBeInTheDocument());
    expect(screen.queryByText('Chaos auto-halt')).not.toBeInTheDocument();
  });

  it('renders LLM cost-budget circuit breaker with trips', async () => {
    stubFetch(
      200,
      [
        'requests_received_count 10.0',
        'mock_server_llm_cost_budget_tripped 2.0',
        'mock_server_llm_cost_usd{provider="openai",model="gpt-4"} 1.5',
        '',
      ].join('\n'),
    );
    render(<MetricsView connectionParams={params} />);
    await waitFor(() => expect(screen.getByText('LLM cost-budget')).toBeInTheDocument());
    expect(screen.getByText('2 trips')).toBeInTheDocument();
    expect(screen.getByText('$1.5000 spent')).toBeInTheDocument();
  });

  it('renders LLM cost-budget within budget when no trips', async () => {
    stubFetch(
      200,
      [
        'requests_received_count 10.0',
        'mock_server_llm_cost_budget_tripped 0.0',
        '',
      ].join('\n'),
    );
    render(<MetricsView connectionParams={params} />);
    await waitFor(() => expect(screen.getByText('LLM cost-budget')).toBeInTheDocument());
    expect(screen.getByText('within budget')).toBeInTheDocument();
  });

  it('shows singular "trip" for LLM cost-budget count of 1', async () => {
    stubFetch(
      200,
      [
        'requests_received_count 10.0',
        'mock_server_llm_cost_budget_tripped 1.0',
        '',
      ].join('\n'),
    );
    render(<MetricsView connectionParams={params} />);
    await waitFor(() => expect(screen.getByText('LLM cost-budget')).toBeInTheDocument());
    expect(screen.getByText('1 trip')).toBeInTheDocument();
  });

  it('hides zero-value expectation types from the chart', async () => {
    stubFetch(
      200,
      [
        'requests_received_count 10.0',
        'mock_server_expectations_by_type{action_type="RESPONSE"} 3.0',
        'mock_server_expectations_by_type{action_type="FORWARD"} 0.0',
        '',
      ].join('\n'),
    );
    render(<MetricsView connectionParams={params} />);
    await waitFor(() => expect(screen.getByText('Expectations by type')).toBeInTheDocument());
    // Only RESPONSE (non-zero) should appear; FORWARD (zero) should be filtered
    expect(screen.queryByText('No expectations configured.')).not.toBeInTheDocument();
  });
});
