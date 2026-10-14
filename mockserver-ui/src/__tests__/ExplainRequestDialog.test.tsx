import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, waitFor, within } from '@testing-library/react';
import ExplainRequestDialog from '../components/ExplainRequestDialog';
import { explainRequest, ExplainRequestError, type RequestExplanation } from '../lib/explainRequest';

vi.mock('../lib/explainRequest', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../lib/explainRequest')>();
  return { ...actual, explainRequest: vi.fn() };
});

const connectionParams = { host: 'localhost', port: '1080', secure: false };
const mockExplain = vi.mocked(explainRequest);

const explanation: RequestExplanation = {
  correlationId: 'corr-1',
  note: 'recomputed now against the current expectations',
  request: { method: 'GET', path: '/orders', timestamp: '2026-10-13 10:00:00.000' },
  atArrival: { outcome: 'MATCHED', expectationId: 'low', expectationStillStored: true },
  sameOutcomeAsAtArrival: true,
  expectationsChangedSince: false,
  matchedExpectation: { expectationId: 'low', expectationMethod: 'GET', expectationPath: '/orders', position: 2, priority: 0 },
  skippedExpectations: [{
    expectationId: 'high',
    expectationMethod: 'GET',
    expectationPath: '/orders',
    position: 1,
    priority: 10,
    reason: 'FIELDS_DIFFER',
    explanation: 'the request differs on: headers',
    differences: { headers: ['header X-Tenant expected gold but was silver'] },
    remediation: { headers: 'check the X-Tenant header' },
  }],
  shadowedExpectations: [{
    expectationId: 'later',
    expectationPath: '/orders',
    position: 3,
    reason: 'LOWER_PRIORITY',
    explanation: "it also matches the request, but expectation 'low' is evaluated first",
  }],
  totalExpectations: 3,
  evaluatedExpectations: 3,
  truncated: false,
};

beforeEach(() => {
  vi.clearAllMocks();
});

function section(name: RegExp): HTMLElement {
  return screen.getByRole('heading', { name }).parentElement!;
}

describe('ExplainRequestDialog', () => {
  it('asks the server about the given correlation ID and shows matched, skipped and shadowed expectations', async () => {
    mockExplain.mockResolvedValue(explanation);

    render(<ExplainRequestDialog open onClose={() => {}} correlationId="corr-1" connectionParams={connectionParams} />);

    await waitFor(() => expect(screen.getByRole('heading', { name: /Matched \(1\)/ })).toBeInTheDocument());
    expect(mockExplain).toHaveBeenCalledWith(connectionParams, 'corr-1', expect.any(AbortSignal));
    expect(within(section(/Matched/)).getByText('low')).toBeInTheDocument();
    const skipped = section(/Skipped \(1\)/);
    expect(within(skipped).getByText('high')).toBeInTheDocument();
    expect(within(skipped).getByText('Fields Differ')).toBeInTheDocument();
    expect(within(skipped).getByText('the request differs on: headers')).toBeInTheDocument();
    expect(within(skipped).getByText(/header X-Tenant expected gold but was silver/)).toBeInTheDocument();
    expect(within(skipped).getByText(/check the X-Tenant header/)).toBeInTheDocument();
    const shadowed = section(/Shadowed \(1\)/);
    expect(within(shadowed).getByText('later')).toBeInTheDocument();
    expect(within(shadowed).getByText('Lower Priority')).toBeInTheDocument();
    expect(screen.getByText(/When it arrived, the request matched expectation low/)).toBeInTheDocument();
    expect(screen.getByText(/Evaluated 3 of 3 expectations/)).toBeInTheDocument();
  });

  it('says when nothing would serve the request now and names the closest expectation', async () => {
    mockExplain.mockResolvedValue({
      ...explanation,
      atArrival: { outcome: 'NO_MATCH' },
      matchedExpectation: undefined,
      closestExpectationId: 'near',
      shadowedExpectations: [],
      expectationsChangedSince: true,
    });

    render(<ExplainRequestDialog open onClose={() => {}} correlationId="corr-2" connectionParams={connectionParams} />);

    await waitFor(() => expect(screen.getByText(/the closest is/)).toBeInTheDocument());
    expect(screen.getByText('near')).toBeInTheDocument();
    expect(screen.getByText('No expectation matches this request now.')).toBeInTheDocument();
    expect(screen.getByText(/The expectations have changed since this request arrived/)).toBeInTheDocument();
  });

  it('says when the matched forward expectation failed to forward, with the reason the 502 gave', async () => {
    mockExplain.mockResolvedValue({
      ...explanation,
      atArrival: {
        outcome: 'MATCHED',
        expectationId: 'stock-forward',
        expectationStillStored: true,
        forwardFailure: { statusCode: 502, reason: 'the upstream refused the connection', causeNamed: true, forwardLoopSuspected: false },
      },
    });

    render(<ExplainRequestDialog open onClose={() => {}} correlationId="corr-3" connectionParams={connectionParams} />);

    await waitFor(() => expect(screen.getByText(/When it arrived, the request matched expectation stock-forward, and its forward failed/)).toBeInTheDocument());
    expect(screen.getByText(/MockServer answered 502 because the forward failed: the upstream refused the connection/)).toBeInTheDocument();
  });

  it('says on its own line, without naming MCP tools, when MockServer could not name the cause', async () => {
    mockExplain.mockResolvedValue({
      ...explanation,
      atArrival: {
        outcome: 'FORWARDED_WITHOUT_EXPECTATION',
        forwardFailure: {
          statusCode: 502,
          reason: 'Connection reset',
          causeNamed: false,
          hint: "MockServer could not name this failure's cause, so the reason is the raw error; retrieve_logs with this correlationId shows the request's whole lifecycle",
        },
      },
    });

    render(<ExplainRequestDialog open onClose={() => {}} correlationId="corr-4" connectionParams={connectionParams} />);

    await waitFor(() => expect(screen.getByText(/because the forward failed: Connection reset/)).toBeInTheDocument());
    const causeLine = screen.getByText(/MockServer could not name the cause, so this is the raw error/);
    expect(causeLine).toHaveTextContent(/The log entries with this correlation ID show/);
    expect(causeLine).not.toHaveTextContent(/Connection reset/);
    expect(screen.queryByText(/retrieve_logs/)).not.toBeInTheDocument();
  });

  it('does not mention a failed forward when the request was served', async () => {
    mockExplain.mockResolvedValue(explanation);

    render(<ExplainRequestDialog open onClose={() => {}} correlationId="corr-1" connectionParams={connectionParams} />);

    await waitFor(() => expect(screen.getByText(/When it arrived, the request matched expectation low/)).toBeInTheDocument());
    expect(screen.queryByText(/forward failed/)).not.toBeInTheDocument();
  });

  it("shows the server's reason when the request is no longer recorded", async () => {
    mockExplain.mockRejectedValue(new ExplainRequestError('no request is recorded with this correlationId', 404));

    render(<ExplainRequestDialog open onClose={() => {}} correlationId="gone" connectionParams={connectionParams} />);

    await waitFor(() => expect(screen.getByText('no request is recorded with this correlationId')).toBeInTheDocument());
  });

  it('is named by its title alone', async () => {
    mockExplain.mockResolvedValue(explanation);

    render(<ExplainRequestDialog open onClose={() => {}} correlationId="corr-1" connectionParams={connectionParams} />);

    expect(await screen.findByRole('dialog', { name: 'Explain Request' })).toBeInTheDocument();
  });
});
