/**
 * Client for MockServer's PUT /mockserver/explain?correlationId= — re-checks one recorded request
 * against the current expectations, in serving order, and says which expectation would serve it
 * now, why each one ahead of it was skipped, and which ones it shadows.
 */
import { buildBaseUrl } from './mcpClient';
import type { ConnectionParams } from '../hooks/useConnectionParams';
import type { LogGroup, LogEntryValue } from '../types';

export type ExplainReason =
  | 'NAMESPACE'
  | 'INACTIVE'
  | 'FIELDS_DIFFER'
  | 'NOT_OPERATOR'
  | 'CONVERSATION'
  | 'SCENARIO_STATE'
  | 'PERCENTAGE'
  | 'LOWER_PRIORITY';

export interface ExplainedExpectation {
  expectationId?: string;
  expectationMethod?: string;
  expectationPath?: string;
  position?: number;
  priority?: number;
  reason?: ExplainReason;
  explanation?: string;
  matches?: boolean;
  matchedFieldCount?: number;
  totalFieldCount?: number;
  differingFieldCount?: number;
  differences?: Record<string, string[]>;
  remediation?: Record<string, string>;
}

export type ArrivalOutcome =
  | 'MATCHED'
  | 'NO_MATCH'
  | 'FORWARDED_WITHOUT_EXPECTATION'
  | 'RESPONDED_WITHOUT_EXPECTATION'
  | 'UNKNOWN';

/** Present when the request was forwarded and the forward failed, so MockServer answered 502. */
export interface ArrivalForwardFailure {
  statusCode?: number;
  reason?: string;
  reasonTruncated?: boolean;
  causeNamed?: boolean;
  forwardLoopSuspected?: boolean;
  hint?: string;
}

export interface RequestExplanation {
  correlationId: string;
  timestamp?: string;
  note?: string;
  request: { timestamp?: string; method?: string; path?: string; bodyTruncated?: boolean; bodyTruncatedNote?: string };
  atArrival: {
    outcome: ArrivalOutcome;
    expectationId?: string;
    expectationStillStored?: boolean;
    forwardFailure?: ArrivalForwardFailure;
  };
  sameOutcomeAsAtArrival?: boolean;
  expectationsChangedSince: boolean;
  expectationsLastChanged?: string;
  matchedExpectation?: ExplainedExpectation;
  closestExpectationId?: string;
  skippedExpectations: ExplainedExpectation[];
  shadowedExpectations: ExplainedExpectation[];
  totalExpectations: number;
  evaluatedExpectations: number;
  truncated: boolean;
  maxExpectationsEvaluated?: number;
}

/** A failure the server explained in words (an unknown or evicted correlation ID, or a non-HTTP request). */
export class ExplainRequestError extends Error {
  constructor(message: string, readonly status: number) {
    super(message);
    this.name = 'ExplainRequestError';
  }
}

export async function explainRequest(
  params: ConnectionParams,
  correlationId: string,
  signal?: AbortSignal,
): Promise<RequestExplanation> {
  const url = `${buildBaseUrl(params)}/mockserver/explain?correlationId=${encodeURIComponent(correlationId)}`;
  const res = await fetch(url, { method: 'PUT', signal });
  const text = await res.text().catch(() => '');
  if (!res.ok) {
    let reason: string | undefined;
    try {
      const parsed = JSON.parse(text) as { error?: unknown };
      if (typeof parsed.error === 'string') reason = parsed.error;
    } catch {
      reason = undefined;
    }
    if (reason && (res.status === 400 || res.status === 404)) throw new ExplainRequestError(reason, res.status);
    throw new Error(`MockServer returned ${res.status}: ${text}`);
  }
  const body = JSON.parse(text) as Partial<RequestExplanation>;
  return {
    ...body,
    correlationId: body.correlationId ?? correlationId,
    request: body.request ?? {},
    atArrival: body.atArrival ?? { outcome: 'UNKNOWN' },
    expectationsChangedSince: body.expectationsChangedSince ?? false,
    skippedExpectations: Array.isArray(body.skippedExpectations) ? body.skippedExpectations : [],
    shadowedExpectations: Array.isArray(body.shadowedExpectations) ? body.shadowedExpectations : [],
    totalExpectations: body.totalExpectations ?? 0,
    evaluatedExpectations: body.evaluatedExpectations ?? 0,
    truncated: body.truncated ?? false,
  };
}

/** The log types from which the server can recover the request to explain. */
const EXPLAINABLE_TYPES = ['RECEIVED_REQUEST', 'EXPECTATION_RESPONSE', 'FORWARDED_REQUEST', 'NO_MATCH_RESPONSE'];

function entryType(entry: LogEntryValue): string {
  const description = entry.description;
  if (!description) return '';
  return typeof description === 'string' ? description : description.second;
}

/** True when the group carries a correlation ID and one of its entries holds an explainable request. */
export function isExplainableGroup(group: LogGroup): boolean {
  if (!group.correlationId) return false;
  const entries = [group.group.value, ...group.value.map((item) => item.value)];
  return entries.some((entry) => {
    const type = entryType(entry);
    return EXPLAINABLE_TYPES.some((explainable) => type.includes(explainable));
  });
}
