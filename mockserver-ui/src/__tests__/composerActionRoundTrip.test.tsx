/**
 * Edit round-trip for every action type the composer form loads.
 *
 * Each expectation carries `delay` and `primary` (which every action inherits on the
 * server) plus fields the form does not show. Opening it and saving it untouched must
 * send it back exactly as it was; editing one field must change only that field.
 */
import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { ThemeProvider } from '@mui/material/styles';
import { buildTheme } from '../theme';
import ComposerView from '../components/ComposerView';
import { useDashboardStore } from '../store';

vi.mock('../lib/mcpClient', () => ({
  buildBaseUrl: () => 'http://127.0.0.1:1080',
  callMcpTool: vi.fn().mockResolvedValue({ ok: true, result: { tools: [], count: 0 } }),
}));

vi.mock('../lib/conversationCodegen', () => ({
  listConversationScenarios: () => [],
}));

const params = { host: '127.0.0.1', port: '1080', secure: false };
const delay = { timeUnit: 'MILLISECONDS', value: 250 };
const http = (path: string) => ({ method: 'GET', path });

const CASES: [string, Record<string, unknown>][] = [
  ['httpResponse', {
    httpRequest: http('/static'),
    httpResponse: { statusCode: 201, body: 'ok', delay: { timeUnit: 'HOURS', value: 1 }, primary: true, trailers: { t: ['v'] } },
  }],
  ['httpForward', {
    httpRequest: http('/fwd'),
    httpForward: { host: 'up.example.com', port: 8080, scheme: 'HTTP', delay, primary: true },
  }],
  ['httpOverrideForwardedRequest', {
    httpRequest: http('/override'),
    httpOverrideForwardedRequest: {
      requestOverride: { path: '/elsewhere', keepAlive: true },
      responseOverride: { statusCode: 299 },
      delay,
      primary: true,
    },
  }],
  ['httpForwardWithFallback', {
    httpRequest: http('/fallback'),
    httpForwardWithFallback: {
      httpForward: { host: 'up.example.com', port: 80, scheme: 'HTTP', delay },
      fallbackResponse: { statusCode: 503, headers: { a: ['b'] } },
      fallbackOnTimeout: false,
      delay,
      primary: true,
    },
  }],
  ['httpResponseClassCallback', {
    httpRequest: http('/callback'),
    httpResponseClassCallback: { callbackClass: 'com.example.Callback', delay, primary: true },
  }],
  ['httpResponseTemplate', {
    httpRequest: http('/template'),
    httpResponseTemplate: { templateType: 'MUSTACHE', template: '{"a":1}', delay, primary: true },
  }],
  ['httpError', {
    httpRequest: http('/error'),
    httpError: { dropConnection: true, delay: { timeUnit: 'DAYS', value: 1 }, primary: true },
  }],
  ['httpError-resetConnection', {
    httpRequest: http('/reset'),
    httpError: { resetConnection: true },
  }],
  ['httpWebSocketResponse', {
    httpRequest: http('/ws'),
    httpWebSocketResponse: { messages: [{ text: 'hi', delay }], delay, primary: true },
  }],
  ['httpSseResponse', {
    httpRequest: http('/sse'),
    httpSseResponse: { statusCode: 200, events: [{ data: 'd', delay }], delay, primary: true },
  }],
  ['httpSseResponse-comments', {
    httpRequest: http('/sse-comments'),
    httpSseResponse: { statusCode: 200, events: [{ comment: 'heartbeat', delay }, { data: 'd', comment: ' two\nlines ' }, { comment: '' }], closeConnection: true },
  }],
  ['binaryResponse', {
    httpRequest: { path: '/bin', body: { type: 'BINARY', base64Bytes: 'AAEC' } },
    binaryResponse: { binaryData: 'SGVsbG8=', delay, primary: true },
  }],
  ['dnsResponse', {
    httpRequest: { dnsName: 'example.com', dnsType: 'A' },
    dnsResponse: { responseCode: 'NOERROR', answerRecords: [{ name: 'example.com', type: 'A', value: '1.2.3.4' }], authorityRecords: [{ name: 'example.com', type: 'NS', value: 'ns' }], delay, primary: true },
  }],
  ['httpForwardTemplate', {
    httpRequest: http('/ftemplate'),
    httpForwardTemplate: { templateType: 'VELOCITY', template: 'x', delay, primary: true },
  }],
  ['httpForwardClassCallback', {
    httpRequest: http('/fcallback'),
    httpForwardClassCallback: { callbackClass: 'com.example.Forward', delay, primary: true },
  }],
  ['grpcStreamResponse', {
    httpRequest: { method: 'POST', path: '/pkg.Svc/Method' },
    grpcStreamResponse: { statusName: 'OK', messages: [{ json: '{"a":1}', delay }], delay, primary: true },
  }],
];

function load(expectation: Record<string, unknown>) {
  useDashboardStore.setState({
    activeExpectations: [],
    pendingEditExpectation: expectation as never,
    view: 'composer',
  });
  render(
    <ThemeProvider theme={buildTheme('dark')}>
      <ComposerView connectionParams={params} />
    </ThemeProvider>,
  );
}

async function save(fetchMock: ReturnType<typeof vi.spyOn>): Promise<Record<string, unknown>> {
  const user = userEvent.setup({ delay: null });
  await user.click(screen.getByRole('button', { name: /Update expectation/ }));
  await waitFor(() => expect(fetchMock).toHaveBeenCalledTimes(1));
  const init = fetchMock.mock.calls[0]![1] as RequestInit | undefined;
  expect(init?.method).toBe('PUT');
  return JSON.parse(String(init?.body)) as Record<string, unknown>;
}

describe('editing an expectation in the composer', () => {
  beforeEach(() => {
    useDashboardStore.setState({ activeExpectations: [], pendingEditExpectation: null, view: 'composer' });
    try { globalThis.sessionStorage?.clear(); } catch { /* noop */ }
  });

  afterEach(() => {
    vi.restoreAllMocks();
  });

  it.each(CASES)('saves an untouched %s expectation back unchanged', async (key, body) => {
    const fetchMock = vi.spyOn(globalThis, 'fetch').mockResolvedValue(new Response('', { status: 201 }));
    const original = { id: `round-trip-${key}`, ...body };
    load(original);
    await waitFor(() => expect(screen.getByText('Expectation kind')).toBeInTheDocument());
    expect(await save(fetchMock)).toEqual(original);
  });

  it('an expectation picked from the list saves back unchanged', async () => {
    const fetchMock = vi.spyOn(globalThis, 'fetch').mockResolvedValue(new Response('', { status: 201 }));
    const original = {
      id: 'listed-sse',
      httpRequest: http('/sse'),
      httpSseResponse: { statusCode: 200, events: [{ data: 'd', delay }], delay, primary: true },
    };
    useDashboardStore.setState({
      activeExpectations: [{ key: 'listed-sse', value: original }] as never,
      pendingEditExpectation: original as never,
      view: 'composer',
    });
    render(
      <ThemeProvider theme={buildTheme('dark')}>
        <ComposerView connectionParams={params} />
      </ThemeProvider>,
    );
    await waitFor(() => expect(screen.getByText('Expectation kind')).toBeInTheDocument());
    expect(await save(fetchMock)).toEqual(original);
  });

  it('Quick mode saves an untouched response back unchanged', async () => {
    const user = userEvent.setup({ delay: null });
    const fetchMock = vi.spyOn(globalThis, 'fetch').mockResolvedValue(new Response('', { status: 201 }));
    const original = {
      id: 'quick-static',
      httpRequest: http('/static'),
      httpResponse: { statusCode: 200, body: 'ok', delay: { timeUnit: 'HOURS', value: 1 }, primary: true },
    };
    load(original);
    await waitFor(() => expect(screen.getByText('Expectation kind')).toBeInTheDocument());
    await user.click(screen.getByLabelText('Quick mock'));
    await user.click(screen.getByRole('button', { name: /Update mock/ }));
    await waitFor(() => expect(fetchMock).toHaveBeenCalledTimes(1));
    expect(JSON.parse(String((fetchMock.mock.calls[0]![1] as RequestInit).body))).toEqual(original);
  });

  it('an edited field changes while the untouched ones are kept', async () => {
    const user = userEvent.setup({ delay: null });
    const fetchMock = vi.spyOn(globalThis, 'fetch').mockResolvedValue(new Response('', { status: 201 }));
    const original = {
      id: 'edit-forward',
      httpRequest: http('/fwd'),
      httpForward: { host: 'up.example.com', port: 8080, scheme: 'HTTP', delay, primary: true },
    };
    load(original);
    const host = await screen.findByLabelText(/^Host/);
    await user.clear(host);
    await user.type(host, 'other.example.com');
    const sent = await save(fetchMock);
    expect(sent['httpForward']).toEqual({ host: 'other.example.com', port: 8080, scheme: 'HTTP', delay, primary: true });
  });

  it('an edit inside a nested object keeps that object\'s untouched fields', async () => {
    const user = userEvent.setup({ delay: null });
    const fetchMock = vi.spyOn(globalThis, 'fetch').mockResolvedValue(new Response('', { status: 201 }));
    load({
      id: 'edit-fallback',
      httpRequest: http('/fallback'),
      httpForwardWithFallback: {
        httpForward: { host: 'up.example.com', port: 80, scheme: 'HTTP', delay },
        fallbackResponse: { statusCode: 503 },
        primary: true,
      },
    });
    const host = await screen.findByLabelText(/^Host/);
    await user.clear(host);
    await user.type(host, 'other.example.com');
    const sent = await save(fetchMock);
    expect(sent['httpForwardWithFallback']).toEqual({
      httpForward: { host: 'other.example.com', port: 80, scheme: 'HTTP', delay },
      fallbackResponse: { statusCode: 503 },
      primary: true,
    });
  });

  // The form loads these delays lossily (1 HOURS as 60 MINUTES, 1 DAYS as 1 MILLISECONDS), so an
  // edit must save the delay the form shows as one value, never mixing in the original's unit or value.
  const lossyDelays: [string, Record<string, unknown>, string, string, 'number' | 'unit', Record<string, unknown>][] = [
    ['static 1 HOURS, number only', { httpResponse: { statusCode: 200, delay: { timeUnit: 'HOURS', value: 1 }, primary: true } },
      'httpResponse', 'Response delay', 'number', { statusCode: 200, delay: { timeUnit: 'MINUTES', value: 5 }, primary: true }],
    ['static 1 HOURS, unit only', { httpResponse: { statusCode: 200, delay: { timeUnit: 'HOURS', value: 1 }, primary: true } },
      'httpResponse', 'Response delay', 'unit', { statusCode: 200, delay: { timeUnit: 'SECONDS', value: 60 }, primary: true }],
    ['error 1 DAYS, number only', { httpError: { dropConnection: true, delay: { timeUnit: 'DAYS', value: 1 }, primary: true } },
      'httpError', 'Pre-action delay', 'number', { dropConnection: true, delay: { timeUnit: 'MILLISECONDS', value: 5 }, primary: true }],
    ['error 1 DAYS, unit only', { httpError: { dropConnection: true, delay: { timeUnit: 'DAYS', value: 1 }, primary: true } },
      'httpError', 'Pre-action delay', 'unit', { dropConnection: true, delay: { timeUnit: 'SECONDS', value: 1 }, primary: true }],
  ];

  it.each(lossyDelays)('editing a lossily loaded delay (%s) saves the shown delay whole', async (_name, action, key, label, edit, expected) => {
    const user = userEvent.setup({ delay: null });
    const fetchMock = vi.spyOn(globalThis, 'fetch').mockResolvedValue(new Response('', { status: 201 }));
    load({ id: `lossy-${key}-${edit}`, httpRequest: http('/d'), ...action });
    const valueField = await screen.findByLabelText(label);
    if (edit === 'number') {
      await user.clear(valueField);
      await user.type(valueField, '5');
    } else {
      const unit = valueField.closest('.MuiBox-root')!.querySelector('[role="combobox"]') as HTMLElement;
      await user.click(unit);
      await user.click(await screen.findByRole('option', { name: 'seconds' }));
    }
    const sent = await save(fetchMock);
    expect(sent[key]).toEqual(expected);
  });

  it('a field the user clears is removed', async () => {
    const user = userEvent.setup({ delay: null });
    const fetchMock = vi.spyOn(globalThis, 'fetch').mockResolvedValue(new Response('', { status: 201 }));
    load({
      id: 'clear-static-delay',
      httpRequest: http('/static'),
      httpResponse: { statusCode: 200, delay: { timeUnit: 'SECONDS', value: 5 }, primary: true },
    });
    const delayField = await screen.findByLabelText('Response delay');
    await user.clear(delayField);
    await user.type(delayField, '0');
    const sent = await save(fetchMock);
    expect(sent['httpResponse']).toEqual({ statusCode: 200, primary: true });
  });

  it('an SSE event comment can be edited and cleared', async () => {
    const user = userEvent.setup({ delay: null });
    const fetchMock = vi.spyOn(globalThis, 'fetch').mockResolvedValue(new Response('', { status: 201 }));
    load({
      id: 'edit-sse-comment',
      httpRequest: http('/sse-comment'),
      httpSseResponse: { statusCode: 200, events: [{ data: 'd', comment: 'old' }, { data: 'e', comment: 'gone' }], closeConnection: true },
    });
    const [first, second] = await screen.findAllByLabelText('Comment');
    await user.clear(first!);
    await user.type(first!, 'new');
    await user.clear(second!);
    const sent = await save(fetchMock);
    expect(sent['httpSseResponse']).toEqual({ statusCode: 200, events: [{ data: 'd', comment: 'new' }, { data: 'e' }], closeConnection: true });
  });
});
