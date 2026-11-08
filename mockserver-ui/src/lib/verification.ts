/**
 * Client for MockServer's verification control plane:
 *   PUT /mockserver/verify          — assert a request was received, and/or a response was
 *                                      returned (mocked or forwarded), the expected number of
 *                                      times
 *   PUT /mockserver/verifySequence  — assert an ordered sequence of requests/responses occurred
 *
 * A verification carries an optional request matcher and an optional response matcher; at least
 * one must be present. When both are present they are correlated against the same recorded
 * request-response exchange. Both endpoints return 202 Accepted when the assertion holds, or
 * 406 Not Acceptable with a plain-text failure report (the closest matches / count) otherwise;
 * any other status is an error, not a verdict.
 */
import { buildBaseUrl } from './mcpClient';
import type { ConnectionParams } from '../hooks/useConnectionParams';

export type VerificationTimesMode = 'atLeast' | 'atMost' | 'exactly' | 'between';

export interface VerificationTimesSpec {
  mode: VerificationTimesMode;
  count: number;
  /** Upper bound for the 'between' mode. */
  atMost?: number;
}

/** The largest count the server accepts (VerificationTimes holds a Java int). */
export const MAX_VERIFICATION_COUNT = 2147483647;

/** The 'between' upper bound as entered — never silently raised to the lower bound. */
export function betweenUpperBound(spec: VerificationTimesSpec): number {
  return Math.max(0, Math.floor(spec.atMost ?? spec.count));
}

/**
 * Why a times spec cannot be sent, or null when it is valid. A count above the server's
 * integer range is rejected by the server as unparseable, and a 'between' range whose
 * max is below its min can never pass, so both are flagged before sending.
 */
export function timesSpecProblem(spec: VerificationTimesSpec): { field: 'count' | 'atMost'; message: string } | null {
  if (!(spec.count <= MAX_VERIFICATION_COUNT)) {
    return { field: 'count', message: `At most ${MAX_VERIFICATION_COUNT}` };
  }
  if (spec.mode === 'between') {
    const upper = spec.atMost ?? spec.count;
    if (!(upper <= MAX_VERIFICATION_COUNT)) {
      return { field: 'atMost', message: `At most ${MAX_VERIFICATION_COUNT}` };
    }
    if (betweenUpperBound(spec) < Math.max(0, Math.floor(spec.count))) {
      return { field: 'atMost', message: 'Must not be less than min' };
    }
  }
  return null;
}

/**
 * Translate a UI times spec to the server's VerificationTimes wire shape. The server treats an
 * absent atLeast/atMost as -1 = unbounded (VerificationTimes.matches), so an open bound is omitted
 * rather than sent as a large sentinel.
 */
export function timesToWire(spec: VerificationTimesSpec): { atLeast?: number; atMost?: number } {
  const count = Math.max(0, Math.floor(spec.count));
  switch (spec.mode) {
    case 'atLeast':
      return { atLeast: count };
    case 'atMost':
      return { atMost: count };
    case 'exactly':
      return { atLeast: count, atMost: count };
    case 'between':
      return { atLeast: count, atMost: betweenUpperBound(spec) };
  }
}

/**
 * The body matcher for a "substring or JSON" body field: text that parses as a JSON object or
 * array is a JSON matcher (the server's default ONLY_MATCHING_FIELDS mode, so a fragment matches
 * a larger document); anything else is a substring match. A plain string on the wire would be an
 * EXACT whole-body match, which is not what the field promises.
 */
export function bodyMatcher(text: string): Record<string, unknown> | undefined {
  if (!text.trim()) return undefined;
  const trimmed = text.trim();
  if (trimmed.startsWith('{') || trimmed.startsWith('[')) {
    try {
      JSON.parse(trimmed);
      return { type: 'JSON', json: trimmed };
    } catch {
      // not JSON: fall through to a substring match
    }
  }
  return { type: 'STRING', string: text, subString: true };
}

export interface VerifyResult {
  verified: boolean;
  /** Server failure report when not verified; null on success. */
  failureMessage: string | null;
}

async function putVerify(
  params: ConnectionParams,
  path: string,
  body: Record<string, unknown>,
): Promise<VerifyResult> {
  const res = await fetch(`${buildBaseUrl(params)}${path}`, {
    method: 'PUT',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(body),
  });
  // 202 Accepted = verified; 406 Not Acceptable = failed (with a plain-text report body).
  // Anything else (400 bad input, 5xx, a proxy error) is not a verdict, so it is thrown.
  if (res.status === 202) {
    return { verified: true, failureMessage: null };
  }
  const text = await res.text().catch(() => '');
  if (res.status === 406) {
    return { verified: false, failureMessage: text || `Verification failed (HTTP ${res.status} ${res.statusText})` };
  }
  throw new Error(`MockServer returned ${res.status}: ${text}`);
}

/**
 * Build the JSON body for PUT /mockserver/verify from the UI's form state.
 * Exported so the verification codegen module can produce the same wire body
 * the panel actually posts.
 */
export function buildVerifyBody(
  httpRequest: Record<string, unknown>,
  times: VerificationTimesSpec,
  httpResponse?: Record<string, unknown>,
): Record<string, unknown> {
  const hasRequest = !!httpRequest && Object.keys(httpRequest).length > 0;
  const hasResponse = !!httpResponse && Object.keys(httpResponse).length > 0;
  const body: Record<string, unknown> = {};
  if (hasRequest) {
    body.httpRequest = httpRequest;
  } else if (!hasResponse) {
    // No matcher entered → default to a request verification that matches any request,
    // so the body is valid (the server's schema requires at least one matcher).
    body.httpRequest = {};
  }
  body.times = timesToWire(times);
  if (hasResponse) {
    body.httpResponse = httpResponse;
  }
  return body;
}

/**
 * Build the JSON body for PUT /mockserver/verifySequence from the UI's form state.
 * Exported so the verification codegen module can produce the same wire body
 * the panel actually posts.
 */
export function buildVerifySequenceBody(
  httpRequests: Record<string, unknown>[],
  httpResponses?: (Record<string, unknown> | undefined)[],
): Record<string, unknown> {
  const hasAnyResponse = !!httpResponses && httpResponses.some((r) => r && Object.keys(r).length > 0);
  const hasAnyRequest = httpRequests.some((r) => r && Object.keys(r).length > 0);
  const body: Record<string, unknown> = {};
  // Include httpRequests when any step has a request, or when there are no responses at all
  // (default to a request sequence so the body is valid). Empty steps become {} = match any.
  if (hasAnyRequest || !hasAnyResponse) {
    body.httpRequests = httpRequests.map((r) => (r && Object.keys(r).length > 0 ? r : {}));
  }
  if (hasAnyResponse) {
    body.httpResponses = httpResponses!.map((r) => (r && Object.keys(r).length > 0 ? r : {}));
  }
  return body;
}

/**
 * Verify a request matcher and/or a response matcher was matched the expected number of times.
 * Empty matchers are omitted from the wire body; at least one of httpRequest / httpResponse must
 * be non-empty (the caller is responsible for enforcing that). When both are present they are
 * correlated against the same recorded request-response exchange.
 */
export function verifyRequest(
  params: ConnectionParams,
  httpRequest: Record<string, unknown>,
  times: VerificationTimesSpec,
  httpResponse?: Record<string, unknown>,
): Promise<VerifyResult> {
  return putVerify(params, '/mockserver/verify', buildVerifyBody(httpRequest, times, httpResponse));
}

/**
 * Verify an ordered sequence of request and/or response matchers occurred (in order, allowing
 * gaps). httpResponses is index-aligned with httpRequests; either list is omitted from the wire
 * body when every entry is empty, so a request-only, response-only, or correlated sequence can all
 * be expressed.
 */
export function verifySequence(
  params: ConnectionParams,
  httpRequests: Record<string, unknown>[],
  httpResponses?: (Record<string, unknown> | undefined)[],
): Promise<VerifyResult> {
  return putVerify(params, '/mockserver/verifySequence', buildVerifySequenceBody(httpRequests, httpResponses));
}
