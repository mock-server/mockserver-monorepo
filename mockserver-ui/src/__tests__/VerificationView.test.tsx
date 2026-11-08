import { describe, it, expect, vi, afterEach } from 'vitest';
import { render, screen, waitFor, cleanup } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { ThemeProvider } from '@mui/material/styles';
import { buildTheme } from '../theme';
import VerificationView from '../components/VerificationView';
import { useDashboardStore } from '../store';

const params = { host: '127.0.0.1', port: '1080', secure: false };

function renderView() {
  return render(
    <ThemeProvider theme={buildTheme('dark')}>
      <VerificationView connectionParams={params} />
    </ThemeProvider>,
  );
}

afterEach(() => {
  cleanup();
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
});

describe('VerificationView', () => {
  it('renders in single-request mode by default', () => {
    renderView();
    expect(screen.getByText('Verification')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Single request', pressed: true })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Verify' })).toBeInTheDocument();
  });

  it('shows the times selector with at-least selected by default', () => {
    renderView();
    // The Select should show "at least"
    expect(screen.getByText('at least')).toBeInTheDocument();
  });

  it('switches to sequence mode and back', async () => {
    const user = userEvent.setup();
    renderView();

    await user.click(screen.getByRole('button', { name: 'Ordered sequence' }));
    expect(screen.getByText(/requests must have been received in this order/)).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Verify sequence' })).toBeInTheDocument();
    // Sequence mode starts with 2 steps
    expect(screen.getByText('1.')).toBeInTheDocument();
    expect(screen.getByText('2.')).toBeInTheDocument();

    await user.click(screen.getByRole('button', { name: 'Single request' }));
    expect(screen.getByRole('button', { name: 'Verify' })).toBeInTheDocument();
  });

  it('adds a step in sequence mode', async () => {
    const user = userEvent.setup();
    renderView();

    await user.click(screen.getByRole('button', { name: 'Ordered sequence' }));
    await user.click(screen.getByRole('button', { name: /Add step/i }));

    expect(screen.getByText('3.')).toBeInTheDocument();
  });

  it('shows success alert when verification passes (202)', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn().mockResolvedValue({
        status: 202,
        text: async () => '',
      }),
    );

    const user = userEvent.setup();
    renderView();

    // Fill in a path so the Verify button is enabled (request matcher is now optional but at least one matcher required)
    const pathField = screen.getByLabelText('Path');
    await user.type(pathField, '/test');

    await user.click(screen.getByRole('button', { name: 'Verify' }));

    await waitFor(() => {
      expect(screen.getByText(/Verified/)).toBeInTheDocument();
    });
  });

  it('shows failure alert when verification fails (406)', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn().mockResolvedValue({
        status: 406,
        statusText: 'Not Acceptable',
        text: async () => 'Request not found exactly 1 times',
      }),
    );

    const user = userEvent.setup();
    renderView();

    // Fill in a path so the Verify button is enabled
    const pathField = screen.getByLabelText('Path');
    await user.type(pathField, '/test');

    await user.click(screen.getByRole('button', { name: 'Verify' }));

    await waitFor(() => {
      expect(screen.getByText('Verification failed')).toBeInTheDocument();
    });
    expect(screen.getByText(/Request not found/)).toBeInTheDocument();
  });

  it('shows error alert on network failure', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn().mockRejectedValue(new Error('network down')),
    );

    const user = userEvent.setup();
    renderView();

    // Fill in a path so the Verify button is enabled
    const pathField = screen.getByLabelText('Path');
    await user.type(pathField, '/test');

    await user.click(screen.getByRole('button', { name: 'Verify' }));

    await waitFor(() => {
      expect(screen.getByText('network down')).toBeInTheDocument();
    });
  });

  it('sends the correct body for sequence verification', async () => {
    const fetchMock = vi.fn().mockResolvedValue({
      status: 202,
      text: async () => '',
    });
    vi.stubGlobal('fetch', fetchMock);

    const user = userEvent.setup();
    renderView();

    await user.click(screen.getByRole('button', { name: 'Ordered sequence' }));

    // Fill in a path in the first step so the Verify sequence button is enabled
    const pathFields = screen.getAllByLabelText('Path');
    await user.type(pathFields[0]!, '/a');

    await user.click(screen.getByRole('button', { name: 'Verify sequence' }));

    await waitFor(() => {
      expect(fetchMock).toHaveBeenCalledOnce();
    });

    const [url, init] = fetchMock.mock.calls[0]!;
    expect(url).toContain('/mockserver/verifySequence');
    const body = JSON.parse(init.body as string);
    expect(body.httpRequests).toBeInstanceOf(Array);
    expect(body.httpRequests).toHaveLength(2);
  });

  // --- Response matcher section tests ---

  it('shows collapsed response matcher section in single mode', () => {
    renderView();
    expect(screen.getByText('Response matcher (optional)')).toBeInTheDocument();
    // Status code field is rendered but hidden (MUI Collapse keeps content in the DOM)
    const statusField = screen.getByLabelText('Status code');
    expect(statusField.closest('.MuiCollapse-root')).toHaveStyle({ height: '0px' });
  });

  it('expands response matcher section on click and shows fields', async () => {
    const user = userEvent.setup();
    renderView();

    await user.click(screen.getByText('Response matcher (optional)'));
    expect(screen.getByLabelText('Status code')).toBeInTheDocument();
    expect(screen.getByLabelText(/Response body/)).toBeInTheDocument();
    expect(screen.getByLabelText(/Response headers/)).toBeInTheDocument();
    // response verification counts mocked responses too, not only proxied/forwarded ones
    expect(screen.getByText(/Match against the responses MockServer returned: mocked by an expectation or forwarded from upstream/)).toBeInTheDocument();
    expect(screen.queryByText(/proxied\/forwarded traffic/)).not.toBeInTheDocument();
  });

  it('sends httpResponse when status code is filled in single mode', async () => {
    const fetchMock = vi.fn().mockResolvedValue({
      status: 202,
      text: async () => '',
    });
    vi.stubGlobal('fetch', fetchMock);

    const user = userEvent.setup();
    renderView();

    // Expand the response matcher section
    await user.click(screen.getByText('Response matcher (optional)'));

    // Fill in a status code
    const statusField = screen.getByLabelText('Status code');
    await user.type(statusField, '200');

    await user.click(screen.getByRole('button', { name: 'Verify' }));

    await waitFor(() => {
      expect(fetchMock).toHaveBeenCalledOnce();
    });

    const [, init] = fetchMock.mock.calls[0]!;
    const body = JSON.parse(init.body as string);
    expect(body.httpResponse).toEqual({ statusCode: 200 });
  });

  it('does NOT send httpResponse when response fields are empty', async () => {
    const fetchMock = vi.fn().mockResolvedValue({
      status: 202,
      text: async () => '',
    });
    vi.stubGlobal('fetch', fetchMock);

    const user = userEvent.setup();
    renderView();

    // Fill in a path so the Verify button is enabled (need at least one matcher)
    const pathField = screen.getByLabelText('Path');
    await user.type(pathField, '/test');

    // Click verify without expanding/filling response matcher
    await user.click(screen.getByRole('button', { name: 'Verify' }));

    await waitFor(() => {
      expect(fetchMock).toHaveBeenCalledOnce();
    });

    const [, init] = fetchMock.mock.calls[0]!;
    const body = JSON.parse(init.body as string);
    expect(body).not.toHaveProperty('httpResponse');
  });

  it('sends httpResponse with body when response body is filled', async () => {
    const fetchMock = vi.fn().mockResolvedValue({
      status: 202,
      text: async () => '',
    });
    vi.stubGlobal('fetch', fetchMock);

    const user = userEvent.setup();
    renderView();

    // Expand and fill response body
    await user.click(screen.getByText('Response matcher (optional)'));
    const bodyField = screen.getByLabelText(/Response body/);
    await user.type(bodyField, '{{"result":"ok"}');

    await user.click(screen.getByRole('button', { name: 'Verify' }));

    await waitFor(() => {
      expect(fetchMock).toHaveBeenCalledOnce();
    });

    const [, init] = fetchMock.mock.calls[0]!;
    const body = JSON.parse(init.body as string);
    expect(body.httpResponse.body).toEqual({ type: 'JSON', json: '{"result":"ok"}' });
  });

  it('shows response matcher in sequence mode steps', async () => {
    const user = userEvent.setup();
    renderView();

    await user.click(screen.getByRole('button', { name: 'Ordered sequence' }));

    // Should have 2 response matcher toggles (one per step)
    const toggles = screen.getAllByText('Response matcher (optional)');
    expect(toggles).toHaveLength(2);
    expect(screen.getAllByText(/mocked by an expectation or forwarded from upstream/)).toHaveLength(2);
    expect(screen.queryByText(/proxied\/forwarded traffic/)).not.toBeInTheDocument();
  });

  it('does NOT send httpResponses in sequence mode when no response fields are filled', async () => {
    const fetchMock = vi.fn().mockResolvedValue({
      status: 202,
      text: async () => '',
    });
    vi.stubGlobal('fetch', fetchMock);

    const user = userEvent.setup();
    renderView();

    await user.click(screen.getByRole('button', { name: 'Ordered sequence' }));

    // Fill in a path in the first step so the Verify sequence button is enabled
    const pathFields = screen.getAllByLabelText('Path');
    await user.type(pathFields[0]!, '/a');

    await user.click(screen.getByRole('button', { name: 'Verify sequence' }));

    await waitFor(() => {
      expect(fetchMock).toHaveBeenCalledOnce();
    });

    const [, init] = fetchMock.mock.calls[0]!;
    const body = JSON.parse(init.body as string);
    expect(body).not.toHaveProperty('httpResponses');
  });

  // --- Optional request matcher behaviour ---

  it('enables Verify button with an empty form (verify-any-request default)', () => {
    renderView();
    const verifyBtn = screen.getByRole('button', { name: 'Verify' });
    expect(verifyBtn).toBeEnabled();
    // No hint caption — both matchers are optional and the empty form is valid
    expect(screen.queryByText(/Add a request matcher/)).not.toBeInTheDocument();
  });

  it('enables Verify sequence button with empty steps', async () => {
    const user = userEvent.setup();
    renderView();

    await user.click(screen.getByRole('button', { name: 'Ordered sequence' }));
    const seqBtn = screen.getByRole('button', { name: 'Verify sequence' });
    expect(seqBtn).toBeEnabled();
    expect(screen.queryByText(/Add a request matcher/)).not.toBeInTheDocument();
  });

  it('sends httpRequest:{} when form is empty (verify-any-request default)', async () => {
    const fetchMock = vi.fn().mockResolvedValue({
      status: 202,
      text: async () => '',
    });
    vi.stubGlobal('fetch', fetchMock);

    const user = userEvent.setup();
    renderView();

    await user.click(screen.getByRole('button', { name: 'Verify' }));

    await waitFor(() => {
      expect(fetchMock).toHaveBeenCalledOnce();
    });

    const [, init] = fetchMock.mock.calls[0]!;
    const body = JSON.parse(init.body as string);
    // Empty form defaults to a request verification matching any request
    expect(body.httpRequest).toEqual({});
    expect(body.times).toBeDefined();
    expect(body).not.toHaveProperty('httpResponse');
  });

  it('sends response-only body when only a response matcher is filled', async () => {
    const fetchMock = vi.fn().mockResolvedValue({
      status: 202,
      text: async () => '',
    });
    vi.stubGlobal('fetch', fetchMock);

    const user = userEvent.setup();
    renderView();

    // Expand the response matcher section and fill a status code
    await user.click(screen.getByText('Response matcher (optional)'));
    const statusField = screen.getByLabelText('Status code');
    await user.type(statusField, '200');

    await user.click(screen.getByRole('button', { name: 'Verify' }));

    await waitFor(() => {
      expect(fetchMock).toHaveBeenCalledOnce();
    });

    const [, init] = fetchMock.mock.calls[0]!;
    const body = JSON.parse(init.body as string);
    // Response-only: no httpRequest key in the body
    expect(body).not.toHaveProperty('httpRequest');
    expect(body.httpResponse).toEqual({ statusCode: 200 });
    expect(body.times).toBeDefined();
  });

  // --- Code generation panel tests ---

  it('shows generated code panel even when no matchers are filled (empty form = verify any request)', () => {
    renderView();
    expect(screen.getByText('Generated Code')).toBeInTheDocument();
    // Java tab should be present and selected by default
    expect(screen.getByRole('tab', { name: 'Java' })).toBeInTheDocument();
    // The empty-form code should contain a verify call with request()
    expect(screen.getByText(/mockServerClient/)).toBeInTheDocument();
  });

  it('shows generated code panel with Java tab when a request path is filled', async () => {
    const user = userEvent.setup();
    renderView();

    const pathField = screen.getByLabelText('Path');
    await user.type(pathField, '/api/orders');

    expect(screen.getByText('Generated Code')).toBeInTheDocument();
    // Java tab should be present and selected by default
    expect(screen.getByRole('tab', { name: 'Java' })).toBeInTheDocument();
    // The code should contain the Java verify call
    expect(screen.getByText(/mockServerClient/)).toBeInTheDocument();
    expect(screen.getByText(/request\(\)/)).toBeInTheDocument();
  });

  it('shows generated code panel in sequence mode even with empty steps', () => {
    renderView();
    // The panel is always present, even before switching to sequence mode
    expect(screen.getByText('Generated Code')).toBeInTheDocument();
  });

  it('shows all 9 language tabs in the generated code panel', () => {
    renderView();

    const tabLabels = ['Java', 'Node.js', 'Python', 'Go', 'C#', 'Ruby', 'Rust', 'JSON', 'curl'];
    for (const label of tabLabels) {
      expect(screen.getByRole('tab', { name: label })).toBeInTheDocument();
    }
  });

  it('sends response-only sequence body when only a response matcher is filled in one step', async () => {
    const fetchMock = vi.fn().mockResolvedValue({
      status: 202,
      text: async () => '',
    });
    vi.stubGlobal('fetch', fetchMock);

    const user = userEvent.setup();
    renderView();

    await user.click(screen.getByRole('button', { name: 'Ordered sequence' }));

    // Expand the response matcher in the first step and fill a status code
    const toggles = screen.getAllByText('Response matcher (optional)');
    await user.click(toggles[0]!);
    const statusFields = screen.getAllByLabelText('Status code');
    await user.type(statusFields[0]!, '201');

    await user.click(screen.getByRole('button', { name: 'Verify sequence' }));

    await waitFor(() => {
      expect(fetchMock).toHaveBeenCalledOnce();
    });

    const [, init] = fetchMock.mock.calls[0]!;
    const body = JSON.parse(init.body as string);
    // Response-only sequence: no httpRequests key
    expect(body).not.toHaveProperty('httpRequests');
    expect(body.httpResponses).toBeDefined();
  });
});

describe('VerificationView — launchpad draft consumption', () => {
  afterEach(() => {
    useDashboardStore.setState({ pendingVerificationDraft: null });
  });

  it('prefills the single-request matcher from a pending verification draft and clears it', () => {
    useDashboardStore.setState({
      pendingVerificationDraft: { method: 'POST', path: '/api/orders' },
    });
    renderView();

    // Path prefilled from the draft.
    expect(screen.getByLabelText('Path')).toHaveValue('/api/orders');
    // Method prefilled (shown in the method Select's rendered value).
    expect(screen.getByText('POST')).toBeInTheDocument();
    // Single-request mode is active and the request matcher is expanded.
    expect(screen.getByRole('button', { name: 'Single request', pressed: true })).toBeInTheDocument();
    // The one-shot draft is consumed exactly once.
    expect(useDashboardStore.getState().pendingVerificationDraft).toBeNull();
  });

  it('prefills only the path when the draft carries no method', () => {
    useDashboardStore.setState({ pendingVerificationDraft: { path: '/health' } });
    renderView();
    expect(screen.getByLabelText('Path')).toHaveValue('/health');
    expect(useDashboardStore.getState().pendingVerificationDraft).toBeNull();
  });
});

function stubVerify(status: number, text = '') {
  const fetchMock = vi.fn().mockResolvedValue({ status, statusText: '', text: async () => text });
  vi.stubGlobal('fetch', fetchMock);
  return fetchMock;
}

interface SentVerify {
  httpRequest: { body?: unknown; headers?: Record<string, string[]> };
}

function sentBody(fetchMock: ReturnType<typeof vi.fn>): SentVerify {
  const [, init] = fetchMock.mock.calls[0]!;
  return JSON.parse((init as RequestInit).body as string) as SentVerify;
}

describe('VerificationView body matching (E2E-VERIFY-1)', () => {
  it('sends plain body text as a substring matcher, not an exact string', async () => {
    const fetchMock = stubVerify(202);
    const user = userEvent.setup();
    renderView();
    await user.type(screen.getByLabelText('Body (substring/JSON match)'), 'widget');
    await user.click(screen.getByRole('button', { name: 'Verify' }));
    await waitFor(() => expect(fetchMock).toHaveBeenCalledOnce());
    expect(sentBody(fetchMock).httpRequest.body).toEqual({ type: 'STRING', string: 'widget', subString: true });
  });

  it('sends a JSON body as a partial JSON matcher', async () => {
    const fetchMock = stubVerify(202);
    const user = userEvent.setup();
    renderView();
    await user.type(screen.getByLabelText('Body (substring/JSON match)'), '{{"order":"widget"}');
    await user.click(screen.getByRole('button', { name: 'Verify' }));
    await waitFor(() => expect(fetchMock).toHaveBeenCalledOnce());
    expect(sentBody(fetchMock).httpRequest.body).toEqual({ type: 'JSON', json: '{"order":"widget"}' });
  });
});

describe('VerificationView input validation', () => {
  it('rejects a header line without ":" inline instead of dropping it (E2E-VERIFY-2)', async () => {
    const fetchMock = stubVerify(202);
    const user = userEvent.setup();
    renderView();
    await user.type(screen.getByLabelText('Path'), '/y');
    await user.type(screen.getByLabelText('Headers (Name: value per line)'), 'X-Required-Header abc');

    expect(screen.getByText('"X-Required-Header abc" is not Name: value')).toBeInTheDocument();
    expect(screen.getByText('Fix the fields marked in red to verify.')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Verify' })).toBeDisabled();
    expect(fetchMock).not.toHaveBeenCalled();

    await user.clear(screen.getByLabelText('Headers (Name: value per line)'));
    await user.type(screen.getByLabelText('Headers (Name: value per line)'), 'X-Required-Header: abc');
    expect(screen.queryByText(/is not Name: value/)).not.toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: 'Verify' }));
    await waitFor(() => expect(fetchMock).toHaveBeenCalledOnce());
    expect(sentBody(fetchMock).httpRequest.headers).toEqual({ 'X-Required-Header': ['abc'] });
  });

  it('rejects a query line without "=" and counts further bad lines', async () => {
    stubVerify(202);
    const user = userEvent.setup();
    renderView();
    await user.type(screen.getByLabelText('Query (key=value per line)'), 'page{Enter}=2{Enter}size=3');
    expect(screen.getByText('"page" is not key=value (and 1 more)')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Verify' })).toBeDisabled();
  });

  it('keeps a section holding an invalid response header open so the error stays visible', async () => {
    stubVerify(202);
    const user = userEvent.setup();
    renderView();
    await user.click(screen.getByRole('button', { name: 'Expand Response matcher (optional)' }));
    await user.type(screen.getByLabelText('Response headers (Name: value per line)'), 'Broken');
    const header = screen.getByRole('button', { name: 'Collapse Response matcher (optional)' });
    expect(header).toBeDisabled();
    expect(screen.getByText('"Broken" is not Name: value')).toBeVisible();
    expect(screen.getByRole('button', { name: 'Verify' })).toBeDisabled();
  });

  it('rejects an invalid step in sequence mode', async () => {
    stubVerify(202);
    const user = userEvent.setup();
    renderView();
    await user.click(screen.getByRole('button', { name: 'Ordered sequence' }));
    await user.type(screen.getAllByLabelText('Headers (Name: value per line)')[1]!, 'nocolon');
    expect(screen.getByRole('button', { name: 'Verify sequence' })).toBeDisabled();
  });

  it('flags "between 3 and 1" instead of silently verifying "exactly 3" (E2E-VERIFY-5)', async () => {
    const fetchMock = stubVerify(202);
    const user = userEvent.setup();
    renderView();
    await user.click(screen.getByRole('combobox', { name: 'Times mode' }));
    await user.click(screen.getByRole('option', { name: 'between' }));
    await user.clear(screen.getByLabelText('min'));
    await user.type(screen.getByLabelText('min'), '3');
    await user.clear(screen.getByLabelText('max'));
    await user.type(screen.getByLabelText('max'), '1');

    expect(screen.getByText('Must not be less than min')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Verify' })).toBeDisabled();
    expect(fetchMock).not.toHaveBeenCalled();
  });

  it('rejects a count the server cannot parse before sending it (E2E-VERIFY-4)', async () => {
    const fetchMock = stubVerify(202);
    const user = userEvent.setup();
    renderView();
    await user.clear(screen.getByLabelText('times'));
    await user.type(screen.getByLabelText('times'), '3000000000');
    expect(screen.getByText('At most 2147483647')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Verify' })).toBeDisabled();
    expect(fetchMock).not.toHaveBeenCalled();
  });

  it('shows a 400 as invalid input, not as a failed verification (E2E-VERIFY-4)', async () => {
    stubVerify(400, 'incorrect verification json format');
    const user = userEvent.setup();
    renderView();
    await user.type(screen.getByLabelText('Path'), '/y');
    await user.click(screen.getByRole('button', { name: 'Verify' }));
    await waitFor(() => expect(screen.getByText('The request was rejected as invalid.')).toBeInTheDocument());
    expect(screen.queryByText('Verification failed')).not.toBeInTheDocument();
  });
});

describe('VerificationView accessibility (E2E-VERIFY-3)', () => {
  it('names the method and times-mode selects and the quick-scope input', () => {
    renderView();
    expect(screen.getByRole('combobox', { name: 'Method' })).toHaveTextContent('Any method');
    expect(screen.getByRole('combobox', { name: 'Times mode' })).toHaveTextContent('at least');
    expect(screen.getByRole('textbox', { name: 'Quick scope' })).toBeInTheDocument();
  });

  it('renders each matcher-section header as one button with no button nested inside', () => {
    renderView();
    for (const name of ['Collapse Request matcher (optional)', 'Expand Response matcher (optional)']) {
      const header = screen.getByRole('button', { name });
      expect(header.tagName).toBe('BUTTON');
      expect(header.querySelector('button, [role="button"]')).toBeNull();
    }
  });

  it('toggles a matcher section from the keyboard', async () => {
    const user = userEvent.setup();
    renderView();
    screen.getByRole('button', { name: 'Expand Response matcher (optional)' }).focus();
    await user.keyboard('{Enter}');
    expect(screen.getByRole('button', { name: 'Collapse Response matcher (optional)' })).toHaveAttribute('aria-expanded', 'true');
  });
});
