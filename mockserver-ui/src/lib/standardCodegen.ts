/**
 * Codegen for "standard" (non-LLM) MockServer expectations — emits Java
 * client snippets, the raw JSON payload sent to PUT /mockserver/expectation,
 * and an equivalent curl command. Used by the Composer's Review section so
 * the user can copy / share the expectation outside the UI.
 *
 * The shape of the JSON payload mirrors what ComposerView.tsx builds before
 * the PUT request; keep the two in sync.
 */

import {
  bodyDateTimeFromText,
  dateTimeBuilderExpr,
  dateTimeMatcherOf,
  nottableObjectOf,
  parseMatcherKeyValueLines,
  type WireMatcherValue,
} from './matcherValue.ts';

// ---------------------------------------------------------------------------
// Types — mirror ComposerView's per-action state contracts.
// ---------------------------------------------------------------------------

export type StandardActionType =
  | 'static'
  | 'forward'
  | 'forward_override'
  | 'forward_fallback'
  | 'callback'
  | 'template'
  | 'error'
  | 'websocket'
  | 'sse'
  | 'binary_response'
  | 'dns_response'
  | 'forward_template'
  | 'forward_class_callback'
  | 'grpc_stream';

export type JsonMatchType = 'ONLY_MATCHING_FIELDS' | 'STRICT';

export interface StandardMatcher {
  id: string;
  method: string;
  path: string;
  headers: string;
  queryString: string;
  cookies: string;
  pathParams: string;
  body: string;
  bodyBinary: boolean;
  bodyMatcherType: BodyMatcherType;
  graphqlOptions?: GraphQLMatcherOptions;
  /** When bodyMatcherType is 'json', the JSON match semantics. ONLY_MATCHING_FIELDS (default) is omitted from the payload. */
  jsonMatchType?: JsonMatchType;
  /** When bodyMatcherType is 'string', whether to use subString matching. */
  bodySubString?: boolean;
  /** Sub-matchers composed by an `allOf` body — used only when bodyMatcherType is 'allOf'. */
  bodyAllOf?: StandardBodyAllOfEntry[];
  /**
   * When bodyMatcherType is 'json-path' or 'xpath', an optional date-time comparison on the
   * selected value, as compact JSON text such as `{"after":"now -1 days"}` (wire `dateTime`).
   */
  bodyDateTime?: string;
  /** JWT request-matcher criteria (httpRequest.jwt); omitted entirely when nothing is set. */
  jwt?: StandardJwtMatcher;
  /**
   * Tri-state TLS matcher. `true` matches HTTPS only, `false` matches HTTP only, and
   * `undefined` omits the field so the expectation matches either. Must not be collapsed to a
   * plain boolean: `false` is a real constraint on the server, so emitting nothing for it
   * silently widens an HTTP-only matcher into a wildcard.
   */
  secure?: boolean;
  /**
   * Client IP address or CIDR range the request must come from (httpRequest.clientIp), IPv4 or IPv6, with an
   * optional leading `!` to match any other address; omitted when blank.
   */
  clientIp?: string;
  priority: number;
  times: number;
  /** Time-to-live in seconds before the expectation auto-expires; 0/undefined = unlimited. */
  ttlSeconds?: number;
  /** DNS-specific matcher fields — populated only when the expectation kind is 'dns'. */
  dns?: StandardDnsMatcher;
}

// ---------------------------------------------------------------------------
// DNS request matcher — the server routes to DnsRequestDefinition when
// the httpRequest object contains a `dnsName` field.
// ---------------------------------------------------------------------------

export type DnsRecordType = 'A' | 'AAAA' | 'CNAME' | 'MX' | 'SRV' | 'TXT' | 'PTR';
export type DnsRecordClass = 'IN' | 'CH' | 'HS' | 'ANY';

export interface StandardDnsMatcher {
  dnsName: string;
  dnsType: DnsRecordType | '';
  dnsClass: DnsRecordClass | '';
}

/**
 * Response connection-level controls (httpResponse.connectionOptions). Tri-state booleans use
 * undefined = "leave to the server default". Mirrors the common ConnectionOptions fields.
 */
export interface StandardConnectionOptions {
  keepAliveOverride?: boolean;
  closeSocket?: boolean;
  contentLengthHeaderOverride?: number;
  suppressContentLengthHeader?: boolean;
  suppressConnectionHeader?: boolean;
}

export interface StandardStaticState {
  statusCode: number;
  body: string;
  contentType: string;
  /** When true the response body is served from a file (a FILE body) rather than the inline `body`. */
  bodyFromFile?: boolean;
  /** Path to the response body file (classpath or filesystem), used when `bodyFromFile` is true. */
  filePath?: string;
  /** Optional template engine applied to the body file's contents against the request.
   *  Empty = serve the file verbatim. Only the text engines (MUSTACHE/VELOCITY) are supported. */
  fileTemplateType?: '' | 'MUSTACHE' | 'VELOCITY';
  /** When true (and a fileTemplateType is set) the file PATH is also rendered against the request,
   *  e.g. `responses/{{ request.pathParameters.name.0 }}.json`. Emitted only when true. */
  templatedFilePath?: boolean;
  /** Additional response headers as "Name: value" lines, beyond Content-Type. */
  headers?: string;
  /** Connection-level response controls (keep-alive, close socket, Content-Length override, …). */
  connectionOptions?: StandardConnectionOptions;
  /** Custom HTTP reason phrase (e.g. "Not Found"). Omitted when empty. */
  reasonPhrase?: string;
  /** Response cookies as "name=value" lines (one per line). Omitted when empty. */
  cookies?: string;
  /** Pre-response delay value. 0 = no delay (omitted). */
  delayValue?: number;
  /** Pre-response delay time unit. */
  delayUnit?: 'MILLISECONDS' | 'SECONDS' | 'MINUTES';
}

/** Build the connectionOptions JSON object, or undefined when nothing is set. */
function buildConnectionOptionsJson(co: StandardConnectionOptions | undefined): Record<string, unknown> | undefined {
  if (!co) return undefined;
  const out: Record<string, unknown> = {};
  if (co.keepAliveOverride != null) out['keepAliveOverride'] = co.keepAliveOverride;
  if (co.closeSocket != null) out['closeSocket'] = co.closeSocket;
  if (co.contentLengthHeaderOverride != null) out['contentLengthHeaderOverride'] = co.contentLengthHeaderOverride;
  if (co.suppressContentLengthHeader) out['suppressContentLengthHeader'] = true;
  if (co.suppressConnectionHeader) out['suppressConnectionHeader'] = true;
  return Object.keys(out).length > 0 ? out : undefined;
}

export interface StandardForwardState {
  scheme: 'HTTP' | 'HTTPS';
  host: string;
  port: number;
}

export interface StandardForwardOverrideState {
  overrideMethod: string;
  overrideHost: string;
  overrideScheme: 'HTTP' | 'HTTPS' | '';
  overridePath: string;
  overrideQueryString: string;
  overrideHeaders: string;
  overrideBody: string;
}

export interface StandardCallbackState {
  callbackClass: string;
}

export interface StandardTemplateState {
  templateType: 'VELOCITY' | 'JAVASCRIPT' | 'MUSTACHE';
  template: string;
  /** Optional path to a file holding the template (classpath or filesystem). When set and the
   *  inline template is empty, the template is loaded from this file. Inline template wins. */
  templateFile?: string;
  /** Optional JSON object the template reads as `parameters` (see {@link parseTemplateParameters}). */
  parameters?: string;
}

/**
 * The template `parameters` typed in the composer: a JSON object, or undefined when the text is blank,
 * not valid JSON, not an object, or an empty object (see {@link templateParametersError}).
 */
export function parseTemplateParameters(text: string | undefined): Record<string, unknown> | undefined {
  if (!text || !text.trim()) return undefined;
  try {
    const parsed: unknown = JSON.parse(text);
    return parsed && typeof parsed === 'object' && !Array.isArray(parsed) && Object.keys(parsed).length > 0
      ? (parsed as Record<string, unknown>)
      : undefined;
  } catch {
    return undefined;
  }
}

/** Why the composer's template `parameters` text cannot be saved, or null when it can (blank is fine). */
export function templateParametersError(text: string | undefined): string | null {
  if (!text || !text.trim()) return null;
  try {
    const parsed: unknown = JSON.parse(text);
    return parsed && typeof parsed === 'object' && !Array.isArray(parsed) ? null : 'Template parameters must be a JSON object, such as {"greeting": "hello"}';
  } catch {
    return 'Template parameters must be valid JSON, such as {"greeting": "hello"}';
  }
}

/** The JSON text the composer shows for a loaded template's `parameters`, or '' when it has none. */
export function templateParametersText(parameters: unknown): string {
  return parameters && typeof parameters === 'object' && !Array.isArray(parameters) && Object.keys(parameters).length > 0
    ? JSON.stringify(parameters, null, 2)
    : '';
}

export interface StandardErrorState {
  dropConnection: boolean;
  resetConnection?: boolean;
  responseBytesB64: string;
  delayValue: number;
  delayUnit: 'MILLISECONDS' | 'SECONDS' | 'MINUTES';
}

export type SelectionSetMatchType = 'NORMALISED_STRING' | 'AST_EXACT' | 'AST_SUBSET';

export type BodyMatcherType =
  | 'string'
  | 'json'
  | 'graphql'
  | 'binary'
  | 'json-schema'
  | 'json-path'
  | 'xml'
  | 'xml-schema'
  | 'xpath'
  | 'regex'
  | 'parameters'
  | 'wasm'
  | 'allOf';

/**
 * The body matcher types that can be composed inside an `allOf` conjunction. A
 * deliberately minimal subset of {@link BodyMatcherType} — the value-carrying,
 * single-string matchers — so the composed sub-matchers map 1:1 onto a wire body
 * object and a Java body factory without needing per-row option state.
 */
export type AllOfSubBodyType =
  | 'string'
  | 'json'
  | 'json-schema'
  | 'json-path'
  | 'xml'
  | 'xml-schema'
  | 'xpath'
  | 'regex';

/** One component of an `allOf` body conjunction. */
export interface StandardBodyAllOfEntry {
  type: AllOfSubBodyType;
  value: string;
}

/**
 * JWT request-matcher criteria (httpRequest.jwt). Mirrors the server-side
 * {@link org.mockserver.model.Jwt} model: the token is read from the named header
 * (default `authorization`), the scheme prefix (default `Bearer`) is stripped, and
 * the decoded claims / JOSE header are matched. Only non-blank fields are emitted;
 * default header/scheme are omitted from the wire payload.
 */
export interface StandardJwtMatcher {
  /** Header carrying the token (default `authorization`); omitted from JSON when blank/default. */
  header?: string;
  /** Scheme prefix stripped before decoding (default `Bearer`); omitted when blank/default. */
  scheme?: string;
  /** Claim criteria as `name=value` lines; value is a NottableString (`!` negates, regex allowed). */
  claims?: string;
  /** Convenience criterion for the `iss` claim. */
  issuer?: string;
  /** Convenience criterion for the `aud` claim. */
  audience?: string;
  /** Convenience criterion for the JOSE header `alg`. */
  algorithm?: string;
}

export interface GraphQLMatcherOptions {
  selectionSetMatchType: SelectionSetMatchType;
  fields: string; // comma-separated field names
}

export interface StandardForwardFallbackState {
  scheme: 'HTTP' | 'HTTPS';
  host: string;
  port: number;
  fallbackStatusCode: number;
  fallbackBody: string;
  fallbackOnStatusCodes: string; // comma-separated, e.g. "500,502,503"
  fallbackOnTimeout: boolean;
}

export type WebSocketFrameType = 'TEXT' | 'BINARY' | 'PING' | 'PONG' | 'ANY';

/**
 * One row of a list the composer edits row by row. `itemId` is a hidden identity the
 * composer gives every row, so an edit merges each item with the one it was loaded from
 * (see {@link mergeItems}); it is never emitted.
 */
export interface StandardListItemDraft {
  itemId?: number;
  text: string;
}

/** A list of messages: one per line of text, or one per row. */
export type StandardTextList = string | StandardListItemDraft[];

export interface WebSocketMatcherDraft {
  /** See {@link StandardListItemDraft.itemId}. */
  itemId?: number;
  frameType: WebSocketFrameType;
  textMatcher: string;
  responses: StandardTextList;
}

export interface StandardWebSocketState {
  subprotocol: string;
  messages: StandardTextList; // text frames
  closeConnection: boolean;
  matchers: WebSocketMatcherDraft[];
}

// ---------------------------------------------------------------------------
// SSE response state (JSON key: httpSseResponse)
// SseEvent fields: event, data, id, retry, comment, delay
// ---------------------------------------------------------------------------

export interface StandardSseEventDraft {
  /** See {@link StandardListItemDraft.itemId}. */
  itemId?: number;
  event: string;
  data: string;
  id: string;
  retry: string; // numeric string or empty
  /**
   * Sent before the other fields as `:` plus each line exactly as written. Absent for none; `''` is a bare `:`
   * heartbeat, so a comment-only event with an empty comment is still saved.
   */
  comment?: string;
}

export interface StandardSseState {
  statusCode: number;
  headers: string; // "Name: value" lines
  events: StandardSseEventDraft[];
  closeConnection: boolean;
}

// ---------------------------------------------------------------------------
// Binary response state (JSON key: binaryResponse)
// BinaryResponse fields: binaryData (byte[], serialised as base64), upstream (enum)
// ---------------------------------------------------------------------------

/** BinaryResponse.Upstream: what happens upstream to the matched message on a relayed connection. */
export type BinaryUpstreamName = 'ANSWER_ONLY' | 'ANSWER_AND_FORWARD' | 'FORWARD_AND_REPLACE';

export const BINARY_UPSTREAM_NAMES: readonly BinaryUpstreamName[] = ['ANSWER_ONLY', 'ANSWER_AND_FORWARD', 'FORWARD_AND_REPLACE'];

export interface StandardBinaryResponseState {
  binaryData: string; // base64-encoded
  /** Absent means the server default, ANSWER_ONLY. */
  upstream?: BinaryUpstreamName;
  /** Carried verbatim from a loaded expectation (the form does not edit it). */
  delay?: Record<string, unknown>;
  /** Carried verbatim from a loaded expectation (the form does not edit it). */
  primary?: boolean;
}

// ---------------------------------------------------------------------------
// DNS response state (JSON key: dnsResponse)
// DnsResponse fields: responseCode (enum), answerRecords, authorityRecords, additionalRecords
// ---------------------------------------------------------------------------

export type DnsResponseCodeName = 'NOERROR' | 'FORMERR' | 'SERVFAIL' | 'NXDOMAIN' | 'NOTIMP' | 'REFUSED';

export interface StandardDnsState {
  responseCode: DnsResponseCodeName;
  answerRecords: string; // JSON array text for simplicity
}

// ---------------------------------------------------------------------------
// Forward template state (JSON key: httpForwardTemplate)
// Same shape as httpResponseTemplate: templateType + template
// ---------------------------------------------------------------------------

export interface StandardForwardTemplateState {
  templateType: 'VELOCITY' | 'JAVASCRIPT' | 'MUSTACHE';
  template: string;
  /** Optional path to a file holding the template (classpath or filesystem). When set and the
   *  inline template is empty, the template is loaded from this file. Inline template wins. */
  templateFile?: string;
  /** Optional JSON object the template reads as `parameters` (see {@link parseTemplateParameters}). */
  parameters?: string;
}

// ---------------------------------------------------------------------------
// Forward class callback state (JSON key: httpForwardClassCallback)
// Same shape as httpResponseClassCallback: callbackClass FQCN
// ---------------------------------------------------------------------------

export interface StandardForwardClassCallbackState {
  callbackClass: string;
}

// ---------------------------------------------------------------------------
// gRPC stream response state (JSON key: grpcStreamResponse)
// GrpcStreamResponse fields: statusName, statusMessage, headers, messages[], closeConnection
// GrpcStreamMessage fields: json, delay
// ---------------------------------------------------------------------------

export interface StandardGrpcStreamState {
  statusName: string;
  statusMessage: string;
  headers: string; // "Name: value" lines
  messages: StandardTextList; // JSON messages
  closeConnection: boolean;
}

export type ChaosDelayUnit = 'MILLISECONDS' | 'SECONDS' | 'MINUTES';

// ---------------------------------------------------------------------------
// Side-effect actions (before / after actions) — webhook (httpRequest) target
// only in this increment. Class/object callbacks are a future increment.
// ---------------------------------------------------------------------------

export type SideEffectPosition = 'before' | 'after';
export type SideEffectDelayUnit = 'MILLISECONDS' | 'SECONDS' | 'MINUTES';
export type SideEffectFailurePolicy = 'BEST_EFFORT' | 'FAIL_FAST';

export type SideEffectTemplateType = '' | 'VELOCITY' | 'MUSTACHE' | 'JAVASCRIPT';

export interface SideEffectPreserved {
  action?: Record<string, unknown>;
  httpRequest?: Record<string, unknown>;
  httpRequestTemplate?: Record<string, unknown>;
}

export interface StandardSideEffectAction {
  position: SideEffectPosition;
  /** When set, the webhook is an httpRequestTemplate rendered from the triggering request and
   *  `template` replaces method / path / host / body. */
  templateType?: SideEffectTemplateType;
  template?: string;
  /** Fields of a loaded action the composer does not edit (e.g. a template's templateFile), re-emitted
   *  unchanged on save so editing an expectation never silently drops them. */
  preserved?: SideEffectPreserved;
  method: string;
  path: string;
  host: string;
  body: string;
  delayValue: number;
  delayUnit: SideEffectDelayUnit;
  // before-only fields:
  blocking: boolean;
  timeoutValue: number;
  timeoutUnit: SideEffectDelayUnit;
  failurePolicy: SideEffectFailurePolicy;
}

// ---------------------------------------------------------------------------
// Capture rules — extract a value from the matched request into scenario state.
// Each rule reads one request value (via a source-specific expression) and
// stores it under `into` so response templates can read it via the `scenario`
// helper. The wire shape is an array under the top-level `capture` key, omitted
// entirely when empty (backward compatible).
// ---------------------------------------------------------------------------

export type CaptureSource =
  | 'jsonPath'
  | 'xpath'
  | 'header'
  | 'queryStringParameter'
  | 'cookie'
  | 'pathParameter';

/** Human-readable labels for capture sources. */
export const CAPTURE_SOURCE_LABELS: Record<CaptureSource, string> = {
  jsonPath: 'JSON path (body)',
  xpath: 'XPath (body)',
  header: 'Header',
  queryStringParameter: 'Query parameter',
  cookie: 'Cookie',
  pathParameter: 'Path parameter',
};

/** All capture sources exposed in the UI, in display order. */
export const CAPTURE_SOURCES: CaptureSource[] = [
  'jsonPath',
  'xpath',
  'header',
  'queryStringParameter',
  'cookie',
  'pathParameter',
];

export interface StandardCaptureRule {
  source: CaptureSource;
  /** The source-specific expression (a JSON path, XPath, header name, etc.). */
  expression: string;
  /** The scenario-state key the captured value is stored under. */
  into: string;
}

/**
 * Draft state for the HTTP chaos profile panel. Maps 1:1 to the seven
 * HttpChaosProfile fields. `undefined` means "not set / omit from JSON".
 */
export interface StandardChaosDraft {
  errorStatus?: number;
  errorProbability?: number;
  retryAfter?: string;
  latencyUnit?: ChaosDelayUnit;
  latencyValue?: number;
  seed?: number;
  succeedFirst?: number;
  failRequestCount?: number;
}

// ---------------------------------------------------------------------------
// Standard chaos range validation — bounds verified against server source:
//   HttpChaosProfile.java: errorStatus 100–599, errorProbability 0.0–1.0
// ---------------------------------------------------------------------------

/** Returns an error hint when errorStatus is outside the server's accepted range (100–599). */
export function standardChaosErrorStatusError(v: number | undefined): string | undefined {
  if (v == null) return undefined;
  if (!Number.isInteger(v) || v < 100 || v > 599) return '100–599';
  return undefined;
}

/**
 * Why a response status code cannot be registered, or undefined when it can. The
 * server accepts any three-digit code (100–999) — the range an HTTP status line
 * can carry — so a blank field (NaN), 0, a negative or a four-digit value is refused.
 */
export function responseStatusCodeError(v: number): string | undefined {
  if (!Number.isInteger(v) || v < 100 || v > 999) return 'Status code must be a whole number from 100 to 999';
  return undefined;
}

/** Returns an error hint when errorProbability is outside 0.0–1.0. */
export function standardChaosErrorProbabilityError(v: number | undefined): string | undefined {
  if (v == null) return undefined;
  if (!Number.isFinite(v) || v < 0 || v > 1) return '0.0–1.0';
  return undefined;
}

/** Returns true when the standard chaos draft has any range error the server would reject. */
export function hasStandardChaosRangeErrors(chaos: StandardChaosDraft): boolean {
  return !!standardChaosErrorStatusError(chaos.errorStatus) ||
    !!standardChaosErrorProbabilityError(chaos.errorProbability);
}

// ---------------------------------------------------------------------------
// Expectation steps — ordered multi-action pipeline (M1 increment-2)
// ---------------------------------------------------------------------------

/**
 * Action target types supported by ExpectationStep. Each step carries exactly
 * ONE action target.
 *
 * Response-capable targets (can be the responder):
 *   httpResponse, httpForward, httpOverrideForwardedRequest, httpError,
 *   httpClassCallback, httpObjectCallback
 *
 * Side-effect-only targets:
 *   httpRequest (webhook)
 *
 * Note: httpObjectCallback requires live WebSocket registration and is NOT
 * form-authorable, so the UI does not offer it.
 */
export type StepActionType =
  | 'httpResponse'
  | 'httpForward'
  | 'httpOverrideForwardedRequest'
  | 'httpError'
  | 'httpRequest'
  | 'httpClassCallback';

/** Which step action types can serve as responders (produce the HTTP response). */
export const RESPONDER_CAPABLE_ACTIONS: ReadonlySet<StepActionType> = new Set<StepActionType>([
  'httpResponse',
  'httpForward',
  'httpOverrideForwardedRequest',
  'httpError',
  'httpClassCallback',
]);

/** Human-readable labels for step action types. */
export const STEP_ACTION_LABELS: Record<StepActionType, string> = {
  httpResponse: 'Static HTTP response',
  httpForward: 'Forward to upstream',
  httpOverrideForwardedRequest: 'Forward with override',
  httpError: 'Error / fault injection',
  httpRequest: 'Webhook (HTTP request)',
  httpClassCallback: 'Class callback',
};

/** All step action types exposed in the UI (excludes httpObjectCallback). */
export const STEP_ACTION_TYPES: StepActionType[] = [
  'httpResponse',
  'httpForward',
  'httpOverrideForwardedRequest',
  'httpError',
  'httpRequest',
  'httpClassCallback',
];

/**
 * Draft state for a single expectation step in the composer.
 * Each step has an action type, a responder flag, and a free-text JSON body
 * for the action payload.
 */
export interface StandardExpectationStep {
  actionType: StepActionType;
  responder: boolean;
  /** Free-text JSON for the action payload (e.g. the httpResponse object body). */
  actionBody: string;
  /** Side-effect step controls (non-responder steps only). */
  blocking: boolean;
  delayValue: number;
  delayUnit: SideEffectDelayUnit;
  timeoutValue: number;
  timeoutUnit: SideEffectDelayUnit;
  failurePolicy: SideEffectFailurePolicy;
}

export interface StandardActionPayload {
  type: StandardActionType;
  static?: StandardStaticState;
  forward?: StandardForwardState;
  forwardOverride?: StandardForwardOverrideState;
  forwardFallback?: StandardForwardFallbackState;
  callback?: StandardCallbackState;
  template?: StandardTemplateState;
  error?: StandardErrorState;
  websocket?: StandardWebSocketState;
  sse?: StandardSseState;
  binaryResponse?: StandardBinaryResponseState;
  dnsResponse?: StandardDnsState;
  forwardTemplate?: StandardForwardTemplateState;
  forwardClassCallback?: StandardForwardClassCallbackState;
  grpcStream?: StandardGrpcStreamState;
  chaos?: StandardChaosDraft;
  sideEffects?: StandardSideEffectAction[];
  /** When present, the expectation uses the `steps` pipeline instead of a
   *  top-level action + before/after side-effects. */
  steps?: StandardExpectationStep[];
  /** Capture rules — extract request values into scenario state. Omitted from
   *  the payload when empty (backward compatible). */
  capture?: StandardCaptureRule[];
  /**
   * When editing an EXISTING expectation, the original expectation JSON exactly
   * as it lives on the server. The form models only a subset of an expectation's
   * fields; without this, re-registering would silently drop every unmodeled
   * field (scenario bindings, response sequences, cross-protocol scenarios,
   * request-matcher extras like keepAlive/socketAddress/protocol, …).
   *
   * When present, `buildExpectationJson` deep-merges the form output ONTO this
   * original via {@link mergeUnmodeledFields}, so the form is authoritative for
   * the fields it models and everything else passes through unchanged. Absent
   * on the new-compose flow, which is therefore completely unaffected.
   */
  editOriginal?: Record<string, unknown>;
  /**
   * True when the form loaded (and therefore owns) the original's action slot,
   * so the form's action replaces the original's. False when the original
   * carries an action the form cannot represent (e.g. a `httpResponses`
   * sequence): the original action is then preserved rather than clobbered by
   * the form's default. Only meaningful alongside {@link editOriginal}.
   */
  editActionModeled?: boolean;
  /**
   * The action-family JSON the form produced for {@link editOriginal} right after
   * loading it. An action field whose form output still equals this baseline was
   * not touched, so the original's value (or absence) is kept: unedited fields the
   * form loads lossily or not at all (delay, primary, extra message fields, …)
   * survive a save. Only meaningful alongside {@link editOriginal}.
   */
  editActionBaseline?: Record<string, unknown>;
  /**
   * Scenario state-machine bindings, as entered in the Advanced form's optional
   * Scenario section. Blank fields are omitted from the payload.
   */
  scenario?: StandardScenarioBinding;
  /**
   * True only when the Advanced form rendered the Scenario section, making the
   * three scenario keys (scenarioName / scenarioState / newScenarioState) form
   * AUTHORITATIVE — set from the form when non-empty, and removed from the merged
   * result when cleared. On the Quick path (and any caller that leaves this
   * false/undefined) the three keys stay UNMODELED and are preserved by
   * {@link mergeUnmodeledFields} passthrough — so a quick edit of a
   * scenario-bound expectation can never drop its bindings.
   */
  scenarioModeled?: boolean;
  /**
   * True when the Advanced form's JWT section faithfully owns the request's
   * {@code httpRequest.jwt} on edit — i.e. the original jwt (if any) round-trips
   * losslessly through the form model. When true the form is AUTHORITATIVE for
   * jwt (an added/edited jwt is emitted; a cleared jwt is removed). When
   * false/undefined the original jwt is left as {@link mergeUnmodeledFields}
   * passthrough, so an unrelated edit of an expectation whose jwt the form cannot
   * faithfully represent (e.g. object-form NottableString claims) never rewrites
   * it. Mirrors the {@link #editActionModeled} pattern.
   */
  jwtModeled?: boolean;
}

/** The Advanced form's optional Scenario section. */
export interface StandardScenarioBinding {
  /** Scenario name this expectation is bound to (top-level `scenarioName`). */
  name?: string;
  /** State the scenario must be in for this to match (top-level `scenarioState`). */
  requiredState?: string;
  /** State the scenario moves to when matched (top-level `newScenarioState`). */
  transitionTo?: string;
}

// ---------------------------------------------------------------------------
// Internal helpers
// ---------------------------------------------------------------------------

export function parseKeyValueLines(
  text: string,
  separator: ':' | '=',
): Record<string, string[]> | undefined {
  const lines = text.split(/\r?\n/).map((l) => l.trim()).filter(Boolean);
  if (lines.length === 0) return undefined;
  const out: Record<string, string[]> = {};
  for (const line of lines) {
    const idx = line.indexOf(separator);
    if (idx < 0) continue;
    const key = line.slice(0, idx).trim();
    const value = line.slice(idx + 1).trim();
    if (!key) continue;
    const arr = out[key] ?? [];
    arr.push(value);
    out[key] = arr;
  }
  return Object.keys(out).length > 0 ? out : undefined;
}

/**
 * The non-empty lines `parseKeyValueLines` drops: no separator, or an empty key
 * before it. Kept beside the parser so a caller that warns about or rejects
 * these lines sees exactly what would be dropped.
 */
export function ignoredKeyValueLines(text: string, separator: ':' | '='): string[] {
  return text
    .split(/\r?\n/)
    .map((l) => l.trim())
    .filter(Boolean)
    .filter((line) => {
      const idx = line.indexOf(separator);
      return idx < 0 || line.slice(0, idx).trim().length === 0;
    });
}

export function escapeJava(s: string): string {
  return s
    .replace(/\\/g, '\\\\')
    .replace(/"/g, '\\"')
    .replace(/\n/g, '\\n')
    .replace(/\r/g, '\\r')
    .replace(/\t/g, '\\t');
}

// ---------------------------------------------------------------------------
// JWT + allOf body — shared JSON/Java helpers (used by both the matcher JSON
// builder and the Java code generator so the two stay in lockstep).
// ---------------------------------------------------------------------------

/**
 * Build the `httpRequest.jwt` wire object, or undefined when nothing meaningful
 * is set. Default header (`authorization`) / scheme (`Bearer`) are omitted, matching
 * the server-side {@link JwtSerializer}. Claim values, issuer, audience and algorithm
 * are emitted as raw strings so a leading `!` (NottableString negation) or a regex is
 * preserved for the server to interpret.
 */
export function buildJwtJson(jwt: StandardJwtMatcher | undefined): Record<string, unknown> | undefined {
  if (!jwt) return undefined;
  const out: Record<string, unknown> = {};
  const header = jwt.header?.trim();
  const scheme = jwt.scheme?.trim();
  if (header && header !== 'authorization') out['header'] = header;
  if (scheme && scheme !== 'Bearer') out['scheme'] = scheme;
  const claims = parseKeyValueLines(jwt.claims ?? '', '=');
  if (claims) {
    const flat: Record<string, string> = {};
    for (const [k, vs] of Object.entries(claims)) flat[k] = vs[0] ?? '';
    if (Object.keys(flat).length > 0) out['claims'] = flat;
  }
  const issuer = jwt.issuer?.trim();
  const audience = jwt.audience?.trim();
  const algorithm = jwt.algorithm?.trim();
  if (issuer) out['issuer'] = issuer;
  if (audience) out['audience'] = audience;
  if (algorithm) out['algorithm'] = algorithm;
  return Object.keys(out).length > 0 ? out : undefined;
}

/** `body` with the matcher's JSONPath / XPath date-time comparison, when it has one. */
function withBodyDateTime(body: Record<string, unknown>, matcher: StandardMatcher): Record<string, unknown> {
  const dateTime = bodyDateTimeFromText(matcher.bodyDateTime);
  return dateTime ? { ...body, dateTime } : body;
}

/** Build the wire body object for one `allOf` sub-matcher entry. */
export function allOfEntryToWire(entry: StandardBodyAllOfEntry): Record<string, unknown> {
  const v = entry.value.trim();
  switch (entry.type) {
    case 'json': {
      let jv: unknown;
      try { jv = JSON.parse(v); } catch { jv = v; }
      return { type: 'JSON', json: jv };
    }
    case 'json-schema': return { type: 'JSON_SCHEMA', jsonSchema: v };
    case 'json-path': return { type: 'JSON_PATH', jsonPath: v };
    case 'xml': return { type: 'XML', xml: v };
    case 'xml-schema': return { type: 'XML_SCHEMA', xmlSchema: v };
    case 'xpath': return { type: 'XPATH', xpath: v };
    case 'regex': return { type: 'REGEX', regex: v };
    case 'string':
    default: return { type: 'STRING', string: entry.value };
  }
}

/** The non-blank sub-matcher entries of an `allOf` body, or [] when none. */
export function allOfEntries(matcher: StandardMatcher): StandardBodyAllOfEntry[] {
  if (matcher.bodyMatcherType !== 'allOf') return [];
  return (matcher.bodyAllOf ?? []).filter((e) => e.value.trim());
}

/** Unify a plain string and the `{ value, not }` NottableString object form into the
 *  `!`-prefix string convention used by the composer's text fields. */
function denottableToString(field: unknown): string {
  if (typeof field === 'string') return field;
  if (field && typeof field === 'object' && 'value' in (field as Record<string, unknown>)) {
    const f = field as Record<string, unknown>;
    const prefix = f['not'] === true ? '!' : '';
    return prefix + String(f['value'] ?? '');
  }
  return field == null ? '' : String(field);
}

/** Round-trip one `bodyAllOf` wire sub-body back into a composer sub-matcher entry;
 *  returns undefined for shapes the composer's allOf form cannot represent. */
export function allOfEntryFromWire(wire: unknown): StandardBodyAllOfEntry | undefined {
  if (!wire || typeof wire !== 'object') return undefined;
  const b = wire as Record<string, unknown>;
  switch (b['type']) {
    case 'JSON': return { type: 'json', value: typeof b['json'] === 'string' ? (b['json'] as string) : JSON.stringify(b['json'], null, 2) };
    case 'JSON_SCHEMA': return { type: 'json-schema', value: String(b['jsonSchema'] ?? '') };
    case 'JSON_PATH': return { type: 'json-path', value: String(b['jsonPath'] ?? '') };
    case 'XML': return { type: 'xml', value: String(b['xml'] ?? '') };
    case 'XML_SCHEMA': return { type: 'xml-schema', value: String(b['xmlSchema'] ?? '') };
    case 'XPATH': return { type: 'xpath', value: String(b['xpath'] ?? '') };
    case 'REGEX': return { type: 'regex', value: String(b['regex'] ?? '') };
    case 'STRING': return { type: 'string', value: typeof b['string'] === 'string' ? (b['string'] as string) : '' };
    default: return undefined;
  }
}

/** Order-insensitive structural equality via canonical (sorted-key) JSON. */
function canonicalize(v: unknown): unknown {
  if (Array.isArray(v)) return v.map(canonicalize);
  if (v && typeof v === 'object') {
    const out: Record<string, unknown> = {};
    for (const k of Object.keys(v as Record<string, unknown>).sort()) {
      out[k] = canonicalize((v as Record<string, unknown>)[k]);
    }
    return out;
  }
  return v;
}

function deepEqualCanonical(a: unknown, b: unknown): boolean {
  return JSON.stringify(canonicalize(a)) === JSON.stringify(canonicalize(b));
}

/**
 * Whether the Advanced JWT form can faithfully own the request's jwt on edit.
 * True when there is no jwt (the form owns the empty slot, so a newly added jwt
 * is emitted), or when the original jwt round-trips losslessly through the form
 * model — `buildJwtJson(jwtFromRequest(req))` deep-equals the original jwt. When
 * false the original jwt is preserved as passthrough (the form's lossy prefill —
 * e.g. object-form NottableString claims flattened to `name=value`, dropping
 * `optional`/negation structure — must not silently rewrite it on an unrelated
 * edit). Mirrors the composer's `editActionModeled` faithfulness gate.
 */
export function jwtFaithfullyModeled(httpRequest: unknown): boolean {
  if (!isPlainObject(httpRequest)) return true;
  const originalJwt = httpRequest['jwt'];
  if (originalJwt == null) return true;
  const rebuilt = buildJwtJson(jwtFromRequest(httpRequest));
  return deepEqualCanonical(rebuilt, originalJwt);
}

/** Round-trip the `httpRequest.jwt` wire object back into the composer's JWT form model,
 *  or undefined when there is no jwt criterion. */
export function jwtFromRequest(req: Record<string, unknown>): StandardJwtMatcher | undefined {
  const raw = req['jwt'];
  if (!raw || typeof raw !== 'object') return undefined;
  const j = raw as Record<string, unknown>;
  const out: StandardJwtMatcher = {};
  if (typeof j['header'] === 'string') out.header = j['header'];
  if (typeof j['scheme'] === 'string') out.scheme = j['scheme'];
  if (j['claims'] && typeof j['claims'] === 'object' && !Array.isArray(j['claims'])) {
    const lines: string[] = [];
    for (const [k, v] of Object.entries(j['claims'] as Record<string, unknown>)) {
      lines.push(`${k}=${denottableToString(v)}`);
    }
    if (lines.length > 0) out.claims = lines.join('\n');
  }
  if (j['issuer'] != null) out.issuer = denottableToString(j['issuer']);
  if (j['audience'] != null) out.audience = denottableToString(j['audience']);
  if (j['algorithm'] != null) out.algorithm = denottableToString(j['algorithm']);
  return out;
}

/** Java body-factory expression for one `allOf` sub-matcher entry. */
function allOfEntryToJava(entry: StandardBodyAllOfEntry): string {
  const v = escapeJava(entry.value.trim());
  switch (entry.type) {
    case 'json': return `json("${v}")`;
    case 'json-schema': return `jsonSchema("${v}")`;
    case 'json-path': return `jsonPath("${v}")`;
    case 'xml': return `xml("${v}")`;
    case 'xml-schema': return `xmlSchema("${v}")`;
    case 'xpath': return `xpath("${v}")`;
    case 'regex': return `regex("${v}")`;
    case 'string':
    default: return `exact("${escapeJava(entry.value)}")`;
  }
}

/** Java `jwt()...` builder expression (multi-line, 8-space continuation). */
function jwtToJava(jwt: StandardJwtMatcher): string {
  const parts: string[] = ['jwt()'];
  const header = jwt.header?.trim();
  const scheme = jwt.scheme?.trim();
  if (header && header !== 'authorization') parts.push(`.withHeader("${escapeJava(header)}")`);
  if (scheme && scheme !== 'Bearer') parts.push(`.withScheme("${escapeJava(scheme)}")`);
  const claims = parseKeyValueLines(jwt.claims ?? '', '=');
  if (claims) {
    for (const [k, vs] of Object.entries(claims)) {
      parts.push(`.withClaim("${escapeJava(k)}", "${escapeJava(vs[0] ?? '')}")`);
    }
  }
  const issuer = jwt.issuer?.trim();
  const audience = jwt.audience?.trim();
  const algorithm = jwt.algorithm?.trim();
  if (issuer) parts.push(`.withIssuer("${escapeJava(issuer)}")`);
  if (audience) parts.push(`.withAudience("${escapeJava(audience)}")`);
  if (algorithm) parts.push(`.withAlgorithm("${escapeJava(algorithm)}")`);
  return parts.join('\n        ');
}

interface WhenArgs {
  useFullWhen: boolean;
  timesExpr: string;
  ttlExpr: string;
  priority: number;
}

/** The exact java.util.concurrent.TimeUnit constant names — an editOriginal with an
 *  exotic/misspelled timeUnit must not emit non-compiling Java, so fall back to SECONDS. */
const JAVA_TIME_UNITS: ReadonlySet<string> = new Set([
  'NANOSECONDS', 'MICROSECONDS', 'MILLISECONDS', 'SECONDS', 'MINUTES', 'HOURS', 'DAYS',
]);

/**
 * `TimeUnit.X, value` — the `withDelay(TimeUnit, long)` arguments for a wire delay,
 * or undefined when it is absent or carries more than a unit and a numeric value.
 */
function javaDelayArgs(delay: unknown): string | undefined {
  if (!delay || typeof delay !== 'object' || Array.isArray(delay)) return undefined;
  const d = delay as Record<string, unknown>;
  if (Object.keys(d).some((k) => k !== 'timeUnit' && k !== 'value')) return undefined;
  const value = d['value'];
  if (typeof value !== 'number' || !isFinite(value)) return undefined;
  const rawUnit = d['timeUnit'];
  const unit = typeof rawUnit === 'string' && JAVA_TIME_UNITS.has(rawUnit) ? rawUnit : 'MILLISECONDS';
  return `TimeUnit.${unit}, ${value}`;
}

/** The wire key each composer action type is saved under. */
const ACTION_WIRE_KEY: Record<StandardActionType, string> = {
  static: 'httpResponse',
  forward: 'httpForward',
  forward_override: 'httpOverrideForwardedRequest',
  forward_fallback: 'httpForwardWithFallback',
  callback: 'httpResponseClassCallback',
  template: 'httpResponseTemplate',
  error: 'httpError',
  websocket: 'httpWebSocketResponse',
  sse: 'httpSseResponse',
  binary_response: 'binaryResponse',
  dns_response: 'dnsResponse',
  forward_template: 'httpForwardTemplate',
  forward_class_callback: 'httpForwardClassCallback',
  grpc_stream: 'grpcStreamResponse',
};

/**
 * What the Java action builder renders from the saved action JSON rather than from
 * the form's state, so fields kept through an edit appear in the snippet: the delay
 * and primary every action inherits, and the message lists with their per-item fields.
 */
interface JavaWireAction {
  /** The saved action object (`json[ACTION_WIRE_KEY[type]]`). */
  wire: Record<string, unknown>;
  imports: Set<string>;
  /** Wire fields the builder could not render, named in a NOTE. */
  omitted: Set<string>;
}

/** Fields of the saved action the Java builder renders from the wire (see {@link JavaWireAction}). */
const JAVA_WIRE_RENDERED_FIELDS: Partial<Record<StandardActionType, readonly string[]>> = {
  template: ['parameters'],
  forward_template: ['parameters'],
  websocket: ['messages', 'matchers'],
  sse: ['events', 'closeDelay', 'keepStreamOpen'],
  grpc_stream: ['messages'],
};

/** `.withDelay(…)` / `.withPrimary(…)` for the delay and primary of a saved action. */
function javaDelayPrimaryCalls(w: JavaWireAction, renderDelay: boolean): string[] {
  const calls: string[] = [];
  if (renderDelay && w.wire['delay'] !== undefined) {
    const args = javaDelayArgs(w.wire['delay']);
    if (args) {
      calls.push(`.withDelay(${args})`);
      w.imports.add('import java.util.concurrent.TimeUnit;');
    } else {
      w.omitted.add('delay');
    }
  }
  if (typeof w.wire['primary'] === 'boolean') calls.push(`.withPrimary(${w.wire['primary']})`);
  return calls;
}

/** A WebSocket message builder for a saved message; fields it cannot set are added to `w.omitted`. */
function javaWebSocketMessage(msg: Record<string, unknown>, w: JavaWireAction, where: string): string {
  for (const k of Object.keys(msg)) if (!['text', 'binary', 'delay'].includes(k)) w.omitted.add(`${where}.${k}`);
  let expr = typeof msg['text'] === 'string' ? `webSocketMessage("${escapeJava(msg['text'])}")` : 'webSocketMessage()';
  if (typeof msg['binary'] === 'string') {
    expr += `.withBinary(Base64.getDecoder().decode("${escapeJava(msg['binary'])}"))`;
    w.imports.add('import java.util.Base64;');
  }
  if (msg['delay'] !== undefined) {
    const args = javaDelayArgs(msg['delay']);
    if (args) {
      expr += `.withDelay(${args})`;
      w.imports.add('import java.util.concurrent.TimeUnit;');
    } else {
      w.omitted.add(`${where}.delay`);
    }
  }
  return expr;
}

function wireItems(w: JavaWireAction, field: string): Record<string, unknown>[] {
  const items = w.wire[field];
  return Array.isArray(items) ? items.filter((i): i is Record<string, unknown> => isPlainObject(i)) : [];
}

/**
 * Derive the arguments for the 4-arg `when(request, Times, TimeToLive, priority)`
 * overload from the built expectation JSON. `useFullWhen` is false when all three
 * are at their neutral defaults, so the plain `when(request)` overload is used.
 *
 * Exported for direct unit testing of the timeUnit hardening (an exotic timeUnit
 * is normalised away by the edit-overlay merge before it reaches this function in
 * normal use, so the SECONDS fallback is only observable by calling this directly).
 */
export function whenArgsFromJson(json: Record<string, unknown>): WhenArgs {
  const timesVal = json['times'];
  let timesExpr = 'Times.unlimited()';
  let timesLimited = false;
  if (timesVal && typeof timesVal === 'object' && (timesVal as Record<string, unknown>)['unlimited'] !== true) {
    const rt = (timesVal as Record<string, unknown>)['remainingTimes'];
    if (typeof rt === 'number') { timesExpr = `Times.exactly(${rt})`; timesLimited = true; }
  }
  const ttlVal = json['timeToLive'];
  let ttlExpr = 'TimeToLive.unlimited()';
  let ttlLimited = false;
  if (ttlVal && typeof ttlVal === 'object' && (ttlVal as Record<string, unknown>)['unlimited'] !== true) {
    const t = (ttlVal as Record<string, unknown>)['timeToLive'];
    if (typeof t === 'number') {
      const rawUnit = (ttlVal as Record<string, unknown>)['timeUnit'];
      const unit = typeof rawUnit === 'string' && JAVA_TIME_UNITS.has(rawUnit) ? rawUnit : 'SECONDS';
      ttlExpr = `TimeToLive.exactly(TimeUnit.${unit}, ${t}L)`;
      ttlLimited = true;
    }
  }
  const priority = typeof json['priority'] === 'number' ? (json['priority'] as number) : 0;
  return { useFullWhen: timesLimited || ttlLimited || priority !== 0, timesExpr, ttlExpr, priority };
}

/** The org.mockserver.mock.ResponseMode enum constants. */
const JAVA_RESPONSE_MODES: ReadonlySet<string> = new Set(['SEQUENTIAL', 'RANDOM', 'WEIGHTED', 'SWITCH']);

/** The org.mockserver.model.CrossProtocolTrigger enum constants. */
const JAVA_CROSS_PROTOCOL_TRIGGERS: ReadonlySet<string> = new Set([
  'DNS_QUERY', 'WEBSOCKET_CONNECT', 'GRPC_REQUEST', 'HTTP_REQUEST',
]);

/**
 * Top-level preserved wire keys the Java client API genuinely cannot set on a
 * ForwardChainExpectation (no fluent setter exists): `rateLimit` (no
 * withRateLimit) and `timestamp` (server-assigned). When present alongside a
 * representable action they are named in an honest trailing NOTE rather than
 * silently dropped — the JSON / curl / client-library tabs remain faithful.
 */
const JAVA_UNREPRESENTABLE_TOPLEVEL: readonly string[] = ['rateLimit', 'timestamp'];

/** A `crossProtocolScenario()...` builder expression (from a wire object). */
function crossProtocolScenarioToJava(cp: Record<string, unknown>): string {
  const parts: string[] = ['crossProtocolScenario()'];
  const trigger = typeof cp['trigger'] === 'string' ? cp['trigger'] : '';
  if (trigger && JAVA_CROSS_PROTOCOL_TRIGGERS.has(trigger)) {
    parts.push(`    .withTrigger(CrossProtocolTrigger.${trigger})`);
  }
  if (typeof cp['scenarioName'] === 'string') parts.push(`    .withScenarioName("${escapeJava(cp['scenarioName'] as string)}")`);
  if (typeof cp['targetState'] === 'string') parts.push(`    .withTargetState("${escapeJava(cp['targetState'] as string)}")`);
  if (typeof cp['matchPattern'] === 'string') parts.push(`    .withMatchPattern("${escapeJava(cp['matchPattern'] as string)}")`);
  return parts.join('\n');
}

/**
 * A `response()...` builder expression from a WIRE httpResponse object (used for
 * the members of a preserved `httpResponses` sequence, which come from the edit
 * overlay JSON rather than the form's static state). Covers the response fields a
 * sequence commonly carries; any field without a mapping is named in `notes`.
 */
function wireResponseToJava(resp: Record<string, unknown>, notes: Set<string>): string {
  const parts: string[] = ['response()'];
  const handled = new Set<string>();
  if (typeof resp['statusCode'] === 'number') { parts.push(`    .withStatusCode(${resp['statusCode']})`); handled.add('statusCode'); }
  if (typeof resp['reasonPhrase'] === 'string') { parts.push(`    .withReasonPhrase("${escapeJava(resp['reasonPhrase'] as string)}")`); handled.add('reasonPhrase'); }
  const headers = resp['headers'];
  if (headers && typeof headers === 'object' && !Array.isArray(headers)) {
    for (const [k, vs] of Object.entries(headers as Record<string, unknown>)) {
      const values = (Array.isArray(vs) ? vs : [vs]).map((v) => `"${escapeJava(String(v))}"`).join(', ');
      parts.push(`    .withHeader("${escapeJava(k)}", ${values})`);
    }
    handled.add('headers');
  }
  const cookies = resp['cookies'];
  if (cookies && typeof cookies === 'object' && !Array.isArray(cookies)) {
    for (const [k, v] of Object.entries(cookies as Record<string, unknown>)) {
      parts.push(`    .withCookie("${escapeJava(k)}", "${escapeJava(String(v))}")`);
    }
    handled.add('cookies');
  }
  if (typeof resp['body'] === 'string') { parts.push(`    .withBody("${escapeJava(resp['body'] as string)}")`); handled.add('body'); }
  const delay = resp['delay'];
  if (delay && typeof delay === 'object' && !Array.isArray(delay)) {
    const d = delay as Record<string, unknown>;
    const rawUnit = typeof d['timeUnit'] === 'string' ? d['timeUnit'] : 'MILLISECONDS';
    const unit = JAVA_TIME_UNITS.has(rawUnit as string) ? rawUnit : 'MILLISECONDS';
    if (typeof d['value'] === 'number') { parts.push(`    .withDelay(TimeUnit.${unit}, ${d['value']})`); handled.add('delay'); }
  }
  for (const k of Object.keys(resp)) if (!handled.has(k)) notes.add(`httpResponses[].${k}`);
  return parts.join('\n');
}

/** The expectation's `metadata` object, or undefined when absent / not an object. */
function metadataObject(json: Record<string, unknown>): Record<string, unknown> | undefined {
  const m = json['metadata'];
  return m && typeof m === 'object' && !Array.isArray(m) ? (m as Record<string, unknown>) : undefined;
}

/** True when a metadata value has no Java literal and must be carried as JSON text. */
function metadataNeedsJson(v: unknown): boolean {
  return v !== null && typeof v === 'object';
}

/** A Java literal for a scalar metadata value, typed as the server's Java generator types it. */
function metadataScalarJava(v: unknown): string {
  if (v === null || v === undefined) return '(Object) null';
  if (typeof v === 'string') return `"${escapeJava(v)}"`;
  if (typeof v === 'boolean') return String(v);
  const n = Number(v);
  if (!Number.isInteger(n)) return String(n);
  if (Math.abs(n) <= 2147483647) return String(n);
  if (Math.abs(n) < 9.2e18 && !String(n).includes('e')) return `${n}L`;
  return String(n).includes('e') ? String(n) : `${n}.0`;
}

/**
 * `.withMetadata(key, value)` per metadata entry. Objects and arrays have no Java
 * literal, so they are carried as their JSON text through `withMetadataJson`.
 */
function metadataToJava(json: Record<string, unknown>): string[] {
  const metadata = metadataObject(json);
  if (!metadata) return [];
  return Object.entries(metadata).map(([k, v]) => metadataNeedsJson(v)
    ? `  .withMetadataJson("${escapeJava(k)}", "${escapeJava(JSON.stringify(v))}")`
    : `  .withMetadata("${escapeJava(k)}", ${metadataScalarJava(v)})`);
}

/**
 * Fluent modifier lines that sit on the ForwardChainExpectation returned by
 * `when(...)`, before the terminal action: namespace, percentage, scenario
 * bindings, capture rules, response-sequence selection controls and
 * cross-protocol scenarios. Read from the built JSON so they faithfully mirror
 * exactly what is emitted to the server (including fields preserved via an edit
 * overlay).
 */
function chainModifierLines(json: Record<string, unknown>): string[] {
  const out: string[] = [];
  if (typeof json['namespace'] === 'string' && (json['namespace'] as string).trim()) {
    out.push(`  .withNamespace("${escapeJava(json['namespace'] as string)}")`);
  }
  out.push(...metadataToJava(json));
  if (typeof json['percentage'] === 'number') {
    out.push(`  .withPercentage(${json['percentage']})`);
  }
  // Response-sequence selection controls — fluent on the ForwardChainExpectation,
  // before the terminal .respond(List<HttpResponse>).
  if (typeof json['responseMode'] === 'string' && JAVA_RESPONSE_MODES.has(json['responseMode'] as string)) {
    out.push(`  .withResponseMode(ResponseMode.${json['responseMode']})`);
  }
  if (Array.isArray(json['responseWeights']) && (json['responseWeights'] as unknown[]).length > 0) {
    out.push(`  .withResponseWeights(Arrays.asList(${(json['responseWeights'] as unknown[]).map((n) => Number(n)).join(', ')}))`);
  }
  if (typeof json['switchAfter'] === 'number') {
    out.push(`  .withSwitchAfter(${json['switchAfter']})`);
  }
  const crossProtocol = json['crossProtocolScenarios'];
  if (Array.isArray(crossProtocol)) {
    for (const cp of crossProtocol as unknown[]) {
      out.push('  .withCrossProtocolScenario(');
      out.push('    ' + crossProtocolScenarioToJava(cp as Record<string, unknown>).split('\n').join('\n    '));
      out.push('  )');
    }
  }
  if (typeof json['scenarioName'] === 'string' && json['scenarioName']) {
    out.push(`  .withScenarioName("${escapeJava(json['scenarioName'] as string)}")`);
  }
  if (typeof json['scenarioState'] === 'string' && json['scenarioState']) {
    out.push(`  .withScenarioState("${escapeJava(json['scenarioState'] as string)}")`);
  }
  if (typeof json['newScenarioState'] === 'string' && json['newScenarioState']) {
    out.push(`  .withNewScenarioState("${escapeJava(json['newScenarioState'] as string)}")`);
  }
  const capture = json['capture'];
  if (Array.isArray(capture) && capture.length > 0) {
    const args = (capture as unknown[]).map((c) => {
      const cc = (c ?? {}) as Record<string, unknown>;
      const source = typeof cc['source'] === 'string' ? cc['source'] : '';
      const expr = typeof cc['expression'] === 'string' ? (cc['expression'] as string) : '';
      const into = typeof cc['into'] === 'string' ? (cc['into'] as string) : '';
      return `capture(CaptureRule.Source.${source}, "${escapeJava(expr)}", "${escapeJava(into)}")`;
    });
    if (args.length === 1) {
      out.push(`  .withCapture(${args[0]})`);
    } else {
      out.push('  .withCapture(');
      out.push(args.map((a) => '    ' + a).join(',\n'));
      out.push('  )');
    }
  }
  return out;
}

interface ParsedDnsRecord {
  name?: string;
  type?: string;
  dnsClass?: string;
  ttl?: number;
  value?: string;
}

/**
 * Parse the DNS answer-records free-text JSON array (as entered in the composer) into
 * structured records for Java codegen. Returns [] when the text is blank or not a JSON
 * array of objects, so the Java snippet simply omits answer records rather than emitting
 * something uncompilable.
 */
function parseDnsRecords(text: string): ParsedDnsRecord[] {
  if (!text.trim()) return [];
  let parsed: unknown;
  try {
    parsed = JSON.parse(text.trim());
  } catch {
    return [];
  }
  if (!Array.isArray(parsed)) return [];
  const out: ParsedDnsRecord[] = [];
  for (const entry of parsed) {
    if (!entry || typeof entry !== 'object') continue;
    const e = entry as Record<string, unknown>;
    const rec: ParsedDnsRecord = {};
    if (typeof e['name'] === 'string') rec.name = e['name'];
    if (typeof e['type'] === 'string') rec.type = e['type'];
    if (typeof e['dnsClass'] === 'string') rec.dnsClass = e['dnsClass'];
    if (typeof e['ttl'] === 'number') rec.ttl = e['ttl'];
    if (typeof e['value'] === 'string') rec.value = e['value'];
    out.push(rec);
  }
  return out;
}

// ---------------------------------------------------------------------------
// JSON codegen — produces the exact payload PUT /mockserver/expectation
// ---------------------------------------------------------------------------

export function buildExpectationJson(
  matcher: StandardMatcher,
  action: StandardActionPayload,
): Record<string, unknown> {
  // DNS expectations use a completely different request matcher shape:
  // { dnsName: "...", dnsType?: "...", dnsClass?: "..." }
  // The JSON key is still "httpRequest" (the server routes to
  // DnsRequestDefinition when the object contains a dnsName field).
  let httpRequest: Record<string, unknown>;

  if (matcher.dns && matcher.dns.dnsName.trim()) {
    httpRequest = { dnsName: matcher.dns.dnsName.trim() };
    if (matcher.dns.dnsType) httpRequest['dnsType'] = matcher.dns.dnsType;
    if (matcher.dns.dnsClass) httpRequest['dnsClass'] = matcher.dns.dnsClass;
  } else {
    httpRequest = { path: matcher.path };
    if (matcher.method) httpRequest['method'] = matcher.method;

    // Matcher values: a line whose value is a JSON matcher object (date-time, schema,
    // nottable) is sent as that object, so a loaded object survives a save unchanged.
    const headers = parseMatcherKeyValueLines(matcher.headers, ':');
    if (headers) httpRequest['headers'] = headers;

    const query = parseMatcherKeyValueLines(matcher.queryString, '=');
    if (query) httpRequest['queryStringParameters'] = query;

    const cookies = parseMatcherKeyValueLines(matcher.cookies, '=');
    if (cookies) {
      const flat: Record<string, WireMatcherValue> = {};
      for (const [k, vs] of Object.entries(cookies)) flat[k] = vs[0] ?? '';
      httpRequest['cookies'] = flat;
    }

    const pathParams = parseMatcherKeyValueLines(matcher.pathParams, '=');
    if (pathParams) httpRequest['pathParameters'] = pathParams;

    if (matcher.bodyMatcherType === 'allOf') {
      // Composite conjunction — driven by the bodyAllOf sub-matcher list, not the
      // single `body` string. Emitted only when at least one non-blank sub-matcher
      // exists (blank rows are dropped so placeholder rows produce no output).
      const entries = allOfEntries(matcher);
      if (entries.length > 0) {
        httpRequest['body'] = { type: 'ALL_OF', bodyAllOf: entries.map(allOfEntryToWire) };
      }
    } else if (matcher.body.trim()) {
      if (matcher.bodyMatcherType === 'binary' || matcher.bodyBinary) {
        httpRequest['body'] = { type: 'BINARY', base64Bytes: matcher.body.trim() };
      } else if (matcher.bodyMatcherType === 'graphql') {
        const gqlBody: Record<string, unknown> = { type: 'GRAPHQL', query: matcher.body.trim() };
        if (matcher.graphqlOptions) {
          if (matcher.graphqlOptions.selectionSetMatchType !== 'NORMALISED_STRING') {
            gqlBody['selectionSetMatchType'] = matcher.graphqlOptions.selectionSetMatchType;
          }
          const fields = matcher.graphqlOptions.fields
            .split(',')
            .map((f) => f.trim())
            .filter(Boolean);
          if (fields.length > 0) gqlBody['fields'] = fields;
        }
        httpRequest['body'] = gqlBody;
      } else if (matcher.bodyMatcherType === 'json') {
        // JSON body matcher — prefer parsed JSON for the json field when valid
        const trimmed = matcher.body.trim();
        let jsonValue: unknown;
        try { jsonValue = JSON.parse(trimmed); } catch { jsonValue = trimmed; }
        const jsonBody: Record<string, unknown> = { type: 'JSON', json: jsonValue };
        if (matcher.jsonMatchType === 'STRICT') jsonBody['matchType'] = 'STRICT';
        httpRequest['body'] = jsonBody;
      } else if (matcher.bodyMatcherType === 'json-schema') {
        httpRequest['body'] = { type: 'JSON_SCHEMA', jsonSchema: matcher.body.trim() };
      } else if (matcher.bodyMatcherType === 'json-path') {
        httpRequest['body'] = withBodyDateTime({ type: 'JSON_PATH', jsonPath: matcher.body.trim() }, matcher);
      } else if (matcher.bodyMatcherType === 'xml') {
        httpRequest['body'] = { type: 'XML', xml: matcher.body.trim() };
      } else if (matcher.bodyMatcherType === 'xml-schema') {
        httpRequest['body'] = { type: 'XML_SCHEMA', xmlSchema: matcher.body.trim() };
      } else if (matcher.bodyMatcherType === 'xpath') {
        httpRequest['body'] = withBodyDateTime({ type: 'XPATH', xpath: matcher.body.trim() }, matcher);
      } else if (matcher.bodyMatcherType === 'regex') {
        httpRequest['body'] = { type: 'REGEX', regex: matcher.body.trim() };
      } else if (matcher.bodyMatcherType === 'parameters') {
        const params = parseKeyValueLines(matcher.body, '=');
        httpRequest['body'] = { type: 'PARAMETERS', parameters: params ?? {} };
      } else if (matcher.bodyMatcherType === 'wasm') {
        httpRequest['body'] = { type: 'WASM', moduleName: matcher.body.trim() };
      } else {
        // Default 'string' type — optionally with subString
        if (matcher.bodySubString) {
          httpRequest['body'] = { type: 'STRING', string: matcher.body, subString: true };
        } else {
          httpRequest['body'] = matcher.body;
        }
      }
    }
    const jwtJson = buildJwtJson(matcher.jwt);
    if (jwtJson) httpRequest['jwt'] = jwtJson;
    // != null, not truthiness: `secure: false` is an HTTP-only matcher and must be emitted.
    if (matcher.secure != null) httpRequest['secure'] = matcher.secure;
    const clientIp = matcher.clientIp?.trim();
    if (clientIp) httpRequest['clientIp'] = clientIp;
  }

  const out: Record<string, unknown> = { httpRequest };

  switch (action.type) {
    case 'static':
      if (action.static) {
        const payload: Record<string, unknown> = { statusCode: action.static.statusCode };
        const fromFile = action.static.bodyFromFile && !!action.static.filePath?.trim();
        if (fromFile) {
          // Serve the body from a file. When a template engine is selected the file is rendered
          // as a template against the request; the content type is carried on the FILE body itself.
          const fileBody: Record<string, unknown> = { type: 'FILE', filePath: action.static.filePath!.trim() };
          if (action.static.fileTemplateType) fileBody['templateType'] = action.static.fileTemplateType;
          if (action.static.fileTemplateType && action.static.templatedFilePath) fileBody['templatedFilePath'] = true;
          if (action.static.contentType) fileBody['contentType'] = action.static.contentType;
          payload['body'] = fileBody;
        } else if (action.static.body) {
          payload['body'] = action.static.body;
        }
        const staticHeaders: Record<string, string[]> = {};
        const extraHeaders = parseKeyValueLines(action.static.headers ?? '', ':');
        if (extraHeaders) {
          // The dedicated contentType field owns content-type; drop any the user also typed
          // into the headers textarea so it is not emitted twice (case-insensitive).
          for (const [k, vs] of Object.entries(extraHeaders)) {
            if (k.toLowerCase() === 'content-type') continue;
            staticHeaders[k] = vs;
          }
        }
        // For a FILE body the content type lives on the body object, so it is not also emitted as a header.
        if (action.static.contentType && !fromFile) {
          staticHeaders['content-type'] = [action.static.contentType];
        }
        if (Object.keys(staticHeaders).length > 0) {
          payload['headers'] = staticHeaders;
        }
        const connectionOptions = buildConnectionOptionsJson(action.static.connectionOptions);
        if (connectionOptions) payload['connectionOptions'] = connectionOptions;
        // reasonPhrase — only emit when non-empty
        if (action.static.reasonPhrase?.trim()) {
          payload['reasonPhrase'] = action.static.reasonPhrase.trim();
        }
        // response cookies — name=value lines → { name: value, ... }
        if (action.static.cookies?.trim()) {
          const cookiePairs = parseKeyValueLines(action.static.cookies, '=');
          if (cookiePairs) {
            const flat: Record<string, string> = {};
            for (const [k, vs] of Object.entries(cookiePairs)) flat[k] = vs[0] ?? '';
            payload['cookies'] = flat;
          }
        }
        // delay — only emit when > 0
        if (action.static.delayValue != null && action.static.delayValue > 0 && isFinite(action.static.delayValue)) {
          payload['delay'] = {
            timeUnit: action.static.delayUnit ?? 'MILLISECONDS',
            value: action.static.delayValue,
          };
        }
        out['httpResponse'] = payload;
      }
      break;
    case 'forward':
      if (action.forward) {
        out['httpForward'] = {
          scheme: action.forward.scheme,
          host: action.forward.host,
          port: action.forward.port,
        };
      }
      break;
    case 'forward_override':
      if (action.forwardOverride) {
        const override: Record<string, unknown> = {};
        if (action.forwardOverride.overrideMethod) override['method'] = action.forwardOverride.overrideMethod;
        if (action.forwardOverride.overrideScheme) override['secure'] = action.forwardOverride.overrideScheme === 'HTTPS';
        if (action.forwardOverride.overridePath) override['path'] = action.forwardOverride.overridePath;
        const overrideHeaders = parseKeyValueLines(action.forwardOverride.overrideHeaders, ':') ?? {};
        if (action.forwardOverride.overrideHost) {
          overrideHeaders['Host'] = [action.forwardOverride.overrideHost];
        }
        if (Object.keys(overrideHeaders).length > 0) override['headers'] = overrideHeaders;
        const overrideQuery = parseKeyValueLines(action.forwardOverride.overrideQueryString, '=');
        if (overrideQuery) override['queryStringParameters'] = overrideQuery;
        if (action.forwardOverride.overrideBody) override['body'] = action.forwardOverride.overrideBody;
        out['httpOverrideForwardedRequest'] = { requestOverride: override };
      }
      break;
    case 'callback':
      if (action.callback) {
        out['httpResponseClassCallback'] = { callbackClass: action.callback.callbackClass.trim() };
      }
      break;
    case 'template':
      if (action.template) {
        const tpl: Record<string, unknown> = { templateType: action.template.templateType };
        const tplFile = action.template.templateFile?.trim();
        if (tplFile) {
          tpl['templateFile'] = tplFile;
          // inline template still wins on the server, so only include it when the user typed one
          if (action.template.template.trim()) tpl['template'] = action.template.template;
        } else {
          tpl['template'] = action.template.template;
        }
        const tplParameters = parseTemplateParameters(action.template.parameters);
        if (tplParameters) tpl['parameters'] = tplParameters;
        out['httpResponseTemplate'] = tpl;
      }
      break;
    case 'error':
      if (action.error) {
        const payload: Record<string, unknown> = {};
        if (action.error.dropConnection) payload['dropConnection'] = true;
        if (action.error.resetConnection) payload['resetConnection'] = true;
        if (action.error.responseBytesB64.trim()) payload['responseBytes'] = action.error.responseBytesB64.trim();
        if (action.error.delayValue > 0) {
          payload['delay'] = { timeUnit: action.error.delayUnit, value: action.error.delayValue };
        }
        out['httpError'] = payload;
      }
      break;
    case 'forward_fallback':
      if (action.forwardFallback) {
        const fb = action.forwardFallback;
        const fwdPayload: Record<string, unknown> = {
          httpForward: { scheme: fb.scheme, host: fb.host, port: fb.port },
        };
        const fallbackResp: Record<string, unknown> = { statusCode: fb.fallbackStatusCode };
        if (fb.fallbackBody.trim()) fallbackResp['body'] = fb.fallbackBody;
        fwdPayload['fallbackResponse'] = fallbackResp;
        const codes = fb.fallbackOnStatusCodes
          .split(',')
          .map((s) => parseInt(s.trim(), 10))
          .filter((n) => !isNaN(n));
        if (codes.length > 0) fwdPayload['fallbackOnStatusCodes'] = codes;
        // Always emitted, including `false`. HttpForwardWithFallbackActionHandler reads
        // `getFallbackOnTimeout() == null || getFallbackOnTimeout()`, so an ABSENT value means
        // TRUE — omitting an explicit `false` would generate code that still falls back on
        // timeout, the opposite of the unticked switch. Same asymmetry as closeConnection.
        fwdPayload['fallbackOnTimeout'] = fb.fallbackOnTimeout;
        out['httpForwardWithFallback'] = fwdPayload;
      }
      break;
    case 'websocket':
      if (action.websocket) {
        const ws = action.websocket;
        const wsPayload: Record<string, unknown> = {};
        if (ws.subprotocol.trim()) wsPayload['subprotocol'] = ws.subprotocol.trim();
        const wsMessages = wireTextItems(ws.messages, 'text');
        if (wsMessages.length > 0) wsPayload['messages'] = wsMessages;
        wsPayload['closeConnection'] = ws.closeConnection;
        if (ws.matchers.length > 0) {
          wsPayload['matchers'] = withItemIds(ws.matchers.map((m) => {
            const matcherObj: Record<string, unknown> = { frameType: m.frameType };
            if (m.textMatcher.trim()) matcherObj['textMatcher'] = m.textMatcher.trim();
            const responses = wireTextItems(m.responses, 'text');
            if (responses.length > 0) matcherObj['responses'] = responses;
            return matcherObj;
          }), ws.matchers.map((m) => m.itemId));
        }
        out['httpWebSocketResponse'] = wsPayload;
      }
      break;
    case 'sse':
      if (action.sse) {
        const sse = action.sse;
        const ssePayload: Record<string, unknown> = {};
        if (sse.statusCode) ssePayload['statusCode'] = sse.statusCode;
        const sseHeaders = parseKeyValueLines(sse.headers, ':');
        if (sseHeaders) ssePayload['headers'] = sseHeaders;
        if (sse.events.length > 0) {
          const events = sse.events.filter((ev) => ev.data.trim() || ev.event.trim() || ev.comment !== undefined);
          ssePayload['events'] = withItemIds(events.map((ev) => {
            const evObj: Record<string, unknown> = {};
            if (ev.event.trim()) evObj['event'] = ev.event.trim();
            if (ev.data.trim()) evObj['data'] = ev.data.trim();
            if (ev.id.trim()) evObj['id'] = ev.id.trim();
            const retryNum = parseInt(ev.retry, 10);
            if (!isNaN(retryNum) && retryNum > 0) evObj['retry'] = retryNum;
            if (ev.comment !== undefined) evObj['comment'] = ev.comment;
            return evObj;
          }), events.map((ev) => ev.itemId));
        }
        ssePayload['closeConnection'] = sse.closeConnection;
        out['httpSseResponse'] = ssePayload;
      }
      break;
    case 'binary_response':
      if (action.binaryResponse) {
        const binPayload: Record<string, unknown> = {};
        if (action.binaryResponse.binaryData.trim()) {
          binPayload['binaryData'] = action.binaryResponse.binaryData.trim();
        }
        if (action.binaryResponse.upstream) binPayload['upstream'] = action.binaryResponse.upstream;
        if (action.binaryResponse.delay) binPayload['delay'] = action.binaryResponse.delay;
        if (action.binaryResponse.primary !== undefined) binPayload['primary'] = action.binaryResponse.primary;
        out['binaryResponse'] = binPayload;
      }
      break;
    case 'dns_response':
      if (action.dnsResponse) {
        const dnsPayload: Record<string, unknown> = {};
        if (action.dnsResponse.responseCode) {
          dnsPayload['responseCode'] = action.dnsResponse.responseCode;
        }
        if (action.dnsResponse.answerRecords.trim()) {
          try {
            dnsPayload['answerRecords'] = JSON.parse(action.dnsResponse.answerRecords.trim());
          } catch {
            // leave raw if not valid JSON
          }
        }
        out['dnsResponse'] = dnsPayload;
      }
      break;
    case 'forward_template':
      if (action.forwardTemplate) {
        const ftpl: Record<string, unknown> = { templateType: action.forwardTemplate.templateType };
        const ftplFile = action.forwardTemplate.templateFile?.trim();
        if (ftplFile) {
          ftpl['templateFile'] = ftplFile;
          if (action.forwardTemplate.template.trim()) ftpl['template'] = action.forwardTemplate.template;
        } else {
          ftpl['template'] = action.forwardTemplate.template;
        }
        const ftplParameters = parseTemplateParameters(action.forwardTemplate.parameters);
        if (ftplParameters) ftpl['parameters'] = ftplParameters;
        out['httpForwardTemplate'] = ftpl;
      }
      break;
    case 'forward_class_callback':
      if (action.forwardClassCallback) {
        out['httpForwardClassCallback'] = {
          callbackClass: action.forwardClassCallback.callbackClass.trim(),
        };
      }
      break;
    case 'grpc_stream':
      if (action.grpcStream) {
        const grpc = action.grpcStream;
        const grpcPayload: Record<string, unknown> = {};
        if (grpc.statusName.trim()) grpcPayload['statusName'] = grpc.statusName.trim();
        if (grpc.statusMessage.trim()) grpcPayload['statusMessage'] = grpc.statusMessage.trim();
        const grpcHeaders = parseKeyValueLines(grpc.headers, ':');
        if (grpcHeaders) grpcPayload['headers'] = grpcHeaders;
        const grpcMessages = wireTextItems(grpc.messages, 'json');
        if (grpcMessages.length > 0) grpcPayload['messages'] = grpcMessages;
        grpcPayload['closeConnection'] = grpc.closeConnection;
        out['grpcStreamResponse'] = grpcPayload;
      }
      break;
  }

  // Chaos profile — top-level sibling of httpRequest / httpResponse.
  if (action.chaos) {
    const chaos = buildChaosJson(action.chaos);
    if (chaos) out['chaos'] = chaos;
  }

  // Side-effect actions — beforeActions / afterActions
  if (action.sideEffects && action.sideEffects.length > 0) {
    const beforeActions = action.sideEffects
      .filter((se) => se.position === 'before' && isSideEffectComplete(se))
      .map(buildSideEffectActionJson);
    const afterActions = action.sideEffects
      .filter((se) => se.position === 'after' && isSideEffectComplete(se))
      .map(buildSideEffectActionJson);
    if (beforeActions.length > 0) out['beforeActions'] = beforeActions;
    if (afterActions.length > 0) out['afterActions'] = afterActions;
  }

  // Steps pipeline — when present, the `steps` array replaces the top-level
  // action + beforeActions/afterActions. The server validates that exactly one
  // step is the responder, but we enforce it client-side too.
  if (action.steps && action.steps.length > 0) {
    // When steps are used, remove the top-level action and side-effects
    // that were already emitted above — they conflict with the steps model.
    delete out['httpResponse'];
    delete out['httpForward'];
    delete out['httpOverrideForwardedRequest'];
    delete out['httpResponseClassCallback'];
    delete out['httpResponseTemplate'];
    delete out['httpError'];
    delete out['httpForwardWithFallback'];
    delete out['httpWebSocketResponse'];
    delete out['httpSseResponse'];
    delete out['binaryResponse'];
    delete out['dnsResponse'];
    delete out['httpForwardTemplate'];
    delete out['httpForwardClassCallback'];
    delete out['grpcStreamResponse'];
    delete out['beforeActions'];
    delete out['afterActions'];

    out['steps'] = action.steps.map(buildExpectationStepJson);
  }

  // Capture rules — top-level sibling of httpRequest / httpResponse. Each rule
  // needs a non-empty expression and target key to be meaningful; blank rows are
  // dropped so the panel can keep placeholder rows without emitting them. The key
  // is omitted entirely when nothing survives (backward compatible).
  if (action.capture && action.capture.length > 0) {
    const captureRules = action.capture
      .filter((c) => c.expression.trim() && c.into.trim())
      .map((c) => ({
        source: c.source,
        expression: c.expression.trim(),
        into: c.into.trim(),
      }));
    if (captureRules.length > 0) out['capture'] = captureRules;
  }

  // Scenario bindings — only emitted (and only made form-authoritative) on the
  // Advanced path, which sets `scenarioModeled`. On the Quick path these keys
  // stay unmodeled and are preserved by mergeUnmodeledFields passthrough. Blank
  // fields are omitted, so new-compose with an empty Scenario section produces
  // byte-identical output to before this section existed.
  if (action.scenarioModeled) {
    const name = action.scenario?.name?.trim();
    const requiredState = action.scenario?.requiredState?.trim();
    const transitionTo = action.scenario?.transitionTo?.trim();
    if (name) out['scenarioName'] = name;
    if (requiredState) out['scenarioState'] = requiredState;
    if (transitionTo) out['newScenarioState'] = transitionTo;
  }

  if (matcher.id.trim()) out['id'] = matcher.id.trim();
  if (matcher.priority !== 0) out['priority'] = matcher.priority;
  if (matcher.times > 0) {
    out['times'] = { remainingTimes: matcher.times, unlimited: false };
  }
  if (matcher.ttlSeconds != null && matcher.ttlSeconds > 0) {
    out['timeToLive'] = { timeUnit: 'SECONDS', timeToLive: matcher.ttlSeconds, unlimited: false };
  }

  // Editing an existing expectation: overlay the form output onto the retained
  // original so fields the form does not model are preserved (not silently
  // dropped) while the form stays authoritative for what it does model. The
  // new-compose flow sets no `editOriginal`, so this is a no-op there and both
  // the preview (StandardReview → buildExpectationJson) and the wire payload
  // (registerExpectation → buildExpectationJson) show the identical merged JSON.
  if (action.editOriginal) {
    return mergeUnmodeledFields(action.editOriginal, out, {
      actionModeled: action.editActionModeled,
      scenarioModeled: action.scenarioModeled,
      jwtModeled: action.jwtModeled,
      actionBaseline: action.editActionBaseline,
    });
  }

  return out;
}

// ---------------------------------------------------------------------------
// Edit-overlay merge — preserve fields the Composer form does not model
// ---------------------------------------------------------------------------

/**
 * Top-level expectation keys the Composer form fully models (apart from the
 * request matcher, handled by a nested merge, and the action family, handled as
 * a mutually-exclusive group below). For each of these keys the form output is
 * authoritative: present ⇒ taken from the form; absent from the form output ⇒
 * removed from the merged result (so a removal the form can express is honoured).
 */
export const FORM_MODELED_TOP_LEVEL_KEYS: readonly string[] = [
  'chaos', 'beforeActions', 'afterActions', 'steps', 'capture',
  'id', 'priority', 'times', 'timeToLive',
];

/**
 * Scenario binding keys the Advanced form models — but ONLY when its Scenario
 * section was rendered (signalled by `scenarioModeled`). They are handled
 * separately from {@link FORM_MODELED_TOP_LEVEL_KEYS} precisely because the Quick
 * path must NOT treat them as modeled: on that path they stay unmodeled and are
 * preserved by passthrough, so a quick edit never drops a scenario binding.
 */
export const FORM_MODELED_SCENARIO_KEYS: readonly string[] = [
  'scenarioName', 'scenarioState', 'newScenarioState',
];

/**
 * The mutually-exclusive action / response slot. When the form recognised (and
 * therefore loaded) the original action, the whole group is replaced by the
 * form's action — so switching action type, or clearing sub-fields, works. When
 * the form did NOT model the original action (e.g. a `httpResponses` sequence),
 * the group is preserved so an unrelated edit (a status-code or matcher tweak)
 * cannot silently delete it. Includes fields the form cannot model but that
 * occupy the same slot, so they never coexist with a form-emitted action.
 */
export const ACTION_FAMILY_KEYS: readonly string[] = [
  'httpResponse', 'httpForward', 'httpOverrideForwardedRequest',
  'httpResponseClassCallback', 'httpResponseTemplate', 'httpError',
  'httpForwardWithFallback', 'httpWebSocketResponse', 'httpSseResponse',
  'binaryResponse', 'dnsResponse', 'httpForwardTemplate',
  'httpForwardClassCallback', 'grpcStreamResponse',
  // Same slot, not modeled by the form (must be listed so the mutual-exclusion
  // slot-clear holds structurally against every action Expectation.java accepts):
  'httpResponseObjectCallback', 'httpForwardObjectCallback',
  'httpForwardValidateAction', 'httpLlmResponse',
  'httpResponses', 'responseMode', 'responseWeights', 'switchAfter',
  'grpcBidiResponse',
];

/**
 * `httpRequest` sub-fields the matcher form UNCONDITIONALLY models. Everything
 * else on the original request (keepAlive, socketAddress, protocol,
 * clientCertificate, clientCertificateChain, …) is passed through unchanged so
 * editing a modeled field does not drop an unmodeled one.
 *
 * `jwt` is modeled too, but only CONDITIONALLY (via {@link MergeUnmodeledOptions.jwtModeled}):
 * the form owns it only when it can faithfully round-trip the original — see
 * {@link jwtFaithfullyModeled}. It is therefore handled separately in
 * {@link mergeUnmodeledFields} rather than listed here.
 */
export const FORM_MODELED_REQUEST_KEYS: readonly string[] = [
  'method', 'path', 'headers', 'queryStringParameters', 'cookies',
  'pathParameters', 'body', 'secure', 'clientIp', 'dnsName', 'dnsType', 'dnsClass',
];

export interface MergeUnmodeledOptions {
  /**
   * True (default) when the form owns the original's action slot, so the form's
   * action replaces the original's. Pass false when the original carries an
   * action the form could not load, to preserve that action instead.
   */
  actionModeled?: boolean;
  /**
   * True only when the Advanced form's Scenario section was rendered, making the
   * scenario keys ({@link FORM_MODELED_SCENARIO_KEYS}) form-authoritative (set
   * from the form, deleted when cleared). Default false: the keys are left as
   * unmodeled passthrough (the Quick path), so bindings are never dropped.
   */
  scenarioModeled?: boolean;
  /**
   * True when the JWT form faithfully owns `httpRequest.jwt` (see
   * {@link jwtFaithfullyModeled}): the form is authoritative — an added/edited
   * jwt is emitted, a cleared jwt is removed. Default false: the original jwt is
   * left as passthrough so an unrelated edit never rewrites a jwt the form cannot
   * faithfully represent.
   */
  jwtModeled?: boolean;
  /** See {@link StandardActionPayload.editActionBaseline}. */
  actionBaseline?: Record<string, unknown>;
}

function isPlainObject(value: unknown): value is Record<string, unknown> {
  return !!value && typeof value === 'object' && !Array.isArray(value);
}

/**
 * A request matcher the form cannot show field by field: an anyOf (logical OR) or a conditional (if/then/else).
 * Its fields sit inside its branches, so overlaying the form's method or path beside them would make the
 * matcher invalid; a save keeps it exactly as loaded.
 */
export function isCompositeRequestMatcher(request: unknown): boolean {
  return isPlainObject(request) && ('anyOf' in request || 'if' in request);
}

/**
 * Top-level keys the edit form models but whose SERVER DEFAULT it prefills as its
 * neutral value: priority `0`, times `{unlimited:true}`, timeToLive `{unlimited:true}`.
 * `buildExpectationJson` omits these when the form is at its default (priority 0,
 * times 0, ttl 0), so a blanket delete-when-omitted merge would strip an original
 * that already carried the explicit default — producing a spurious diff for a
 * semantic no-op (server default ≡ explicit default form).
 *
 * These are therefore handled specially in {@link mergeUnmodeledFields}: on an
 * untouched round-trip (original already at default) the original's explicit key
 * is PRESERVED verbatim; only a genuine reset of a NON-default value re-emits the
 * default — as an explicit `{unlimited:true}` form for times / timeToLive (so the
 * intent is visible and re-editable), or as a deletion for priority (0 and absent
 * are identical to the server, and a preserved existing test pins that shape).
 */
interface DefaultPreservingKey {
  key: string;
  /** True when `v` (the original's value for `key`) is the server default form. */
  isDefault: (v: unknown) => boolean;
  /**
   * The explicit default form to emit when the user resets a non-default value to
   * default. Undefined ⇒ delete the key on reset (priority 0 ≡ absent to the server).
   */
  resetForm?: () => unknown;
}

function priorityIsDefault(v: unknown): boolean {
  return v == null || v === 0;
}

/** Absent or an explicit `{ unlimited: true }` object is the server "unlimited" default. */
function unlimitedIsDefault(v: unknown): boolean {
  if (v == null) return true;
  return isPlainObject(v) && v['unlimited'] === true;
}

const DEFAULT_PRESERVING_KEYS: readonly DefaultPreservingKey[] = [
  { key: 'priority', isDefault: priorityIsDefault },
  { key: 'times', isDefault: unlimitedIsDefault, resetForm: () => ({ unlimited: true }) },
  { key: 'timeToLive', isDefault: unlimitedIsDefault, resetForm: () => ({ unlimited: true }) },
];

const DEFAULT_PRESERVING_KEY_SET: ReadonlySet<string> = new Set(
  DEFAULT_PRESERVING_KEYS.map((d) => d.key),
);

/** A duration (`{timeUnit, value}`-shaped: delay, timeout, timeToLive, …) is one value, never merged per field. */
function isDuration(v: unknown): boolean {
  return isPlainObject(v) && 'timeUnit' in v;
}

function isMergeableObject(v: unknown): v is Record<string, unknown> {
  return isPlainObject(v) && !isDuration(v);
}

/**
 * The form's hidden item ids for each list {@link buildExpectationJson} emitted from rows,
 * keyed by the emitted array, so the ids never appear in the JSON itself.
 */
const LIST_ITEM_IDS = new WeakMap<unknown[], (number | undefined)[]>();

function withItemIds(wire: unknown[], ids: (number | undefined)[]): unknown[] {
  LIST_ITEM_IDS.set(wire, ids);
  return wire;
}

/** The non-blank items of `list` as `{[key]: text}`, trimmed; a list of rows records its item ids. */
function wireTextItems(list: StandardTextList, key: 'text' | 'json'): unknown[] {
  if (typeof list === 'string') {
    return list.split(/\r?\n/).map((l) => l.trim()).filter(Boolean).map((text) => ({ [key]: text }));
  }
  const items = list.filter((item) => item.text.trim());
  return withItemIds(items.map((item) => ({ [key]: item.text.trim() })), items.map((item) => item.itemId));
}

/** Whether every list in `form` holds the same items, in the same order, as in `baseline`. */
function sameItemIds(form: unknown, baseline: unknown): boolean {
  if (Array.isArray(form) && Array.isArray(baseline)) {
    const formIds = LIST_ITEM_IDS.get(form);
    const baselineIds = LIST_ITEM_IDS.get(baseline);
    if (formIds && baselineIds && formIds.some((id, i) => id !== baselineIds[i])) return false;
    return form.every((item, i) => sameItemIds(item, baseline[i]));
  }
  if (isPlainObject(form) && isPlainObject(baseline)) {
    return Object.keys(form).every((k) => sameItemIds(form[k], baseline[k]));
  }
  return true;
}

/** Whether the form left this value as it loaded it: equal, with no list items moved. */
function unchangedByForm(form: unknown, baseline: unknown): boolean {
  return deepEqualCanonical(form, baseline) && sameItemIds(form, baseline);
}

type FieldPath = (string | number)[];

/** A value the edit merge keeps from the original although the form does not show it as saved. */
interface KeptLeaf {
  /** Where the value sits in the merged (saved) value. */
  path: FieldPath;
  /** Where the value sits in the original. */
  originalPath: FieldPath;
  value: unknown;
  /** What the form produced there on load; undefined when it produced nothing. */
  baselineValue: unknown;
}

function keptLeaf(path: FieldPath, value: unknown, baselineValue: unknown): KeptLeaf {
  return { path, originalPath: path, value, baselineValue };
}

function underKey(kept: KeptLeaf[], key: string | number, originalKey: string | number): KeptLeaf[] {
  return kept.map((k) => ({ ...k, path: [key, ...k.path], originalPath: [originalKey, ...k.originalPath] }));
}

/** Whether `baselineItem` is what the form loaded from `originalItem` (every field it shows, at any depth, is equal). */
function loadedFrom(originalItem: unknown, baselineItem: unknown): boolean {
  if (isMergeableObject(originalItem) && isMergeableObject(baselineItem)) {
    return Object.keys(baselineItem).every((k) => loadedFrom(originalItem[k], baselineItem[k]));
  }
  if (Array.isArray(originalItem) && Array.isArray(baselineItem)) return alignItems(originalItem, baselineItem) !== undefined;
  return deepEqualCanonical(originalItem, baselineItem);
}

/**
 * For each baseline item, the index of the original item it was loaded from, in order;
 * original items the form could not load are skipped. Undefined when they do not line up.
 */
function alignItems(original: unknown[], baseline: unknown[]): number[] | undefined {
  const pairs: number[] = [];
  let j = 0;
  for (const item of baseline) {
    while (j < original.length && !loadedFrom(original[j], item)) j++;
    if (j === original.length) return undefined;
    pairs.push(j++);
  }
  return pairs;
}

/** The parts of an untouched `original` that differ from what the form loaded (`baseline`). */
function keptDiff(original: unknown, baseline: unknown): KeptLeaf[] {
  if (deepEqualCanonical(original, baseline)) return [];
  if (isMergeableObject(original) && isMergeableObject(baseline)) {
    return Object.keys(original).flatMap((k) => (k in baseline
      ? underKey(keptDiff(original[k], baseline[k]), k, k)
      : [keptLeaf([k], original[k], undefined)]));
  }
  if (Array.isArray(original) && Array.isArray(baseline)) {
    const pairs = alignItems(original, baseline);
    if (pairs) {
      const byOriginal = new Map(pairs.map((j, k) => [j, k]));
      return original.flatMap((item, j) => {
        const k = byOriginal.get(j);
        return k === undefined ? [keptLeaf([j], item, undefined)] : underKey(keptDiff(item, baseline[k]), j, j);
      });
    }
  }
  return [keptLeaf([], original, baseline)];
}

interface Merged {
  value: unknown;
  kept: KeptLeaf[];
}

/**
 * Keeps `original` except where the form changed a field: a field whose `form`
 * value differs from the `baseline` (what the form produced on load) takes the
 * form's value, or is removed when the form dropped it. Nested objects merge the
 * same way and so do the form's lists of rows, item by item (see {@link mergeItems});
 * scalars, durations and other arrays are replaced whole when changed. `kept` lists
 * the original's values the result carries that the form does not show.
 */
function mergeTracked(original: unknown, form: unknown, baseline: unknown): Merged {
  if (unchangedByForm(form, baseline)) return { value: structuredClone(original), kept: keptDiff(original, baseline) };
  // The form showed nothing here on load (e.g. connectionOptions holding only chunkSize), so
  // what it now emits is added to the original object rather than replacing its hidden fields.
  if (baseline === undefined && isMergeableObject(original) && isMergeableObject(form)) return mergeTracked(original, form, {});
  if (isMergeableObject(original) && isMergeableObject(form) && isMergeableObject(baseline)) {
    const value: Record<string, unknown> = structuredClone(original);
    const kept: KeptLeaf[] = [];
    const formKeys = new Set([...Object.keys(form), ...Object.keys(baseline)]);
    for (const k of formKeys) {
      if (unchangedByForm(form[k], baseline[k])) {
        if (k in original) kept.push(...underKey(keptDiff(original[k], baseline[k]), k, k));
      } else if (!(k in form)) {
        delete value[k];
      } else {
        const merged = mergeTracked(original[k], form[k], baseline[k]);
        value[k] = merged.value;
        kept.push(...underKey(merged.kept, k, k));
      }
    }
    for (const k of Object.keys(original)) if (!formKeys.has(k)) kept.push(keptLeaf([k], original[k], undefined));
    return { value, kept };
  }
  if (Array.isArray(original) && Array.isArray(form) && Array.isArray(baseline)) {
    const merged = mergeItems(original, form, baseline);
    if (merged) return merged;
  }
  return { value: form, kept: [] };
}

/**
 * Merges an edited list of rows item by item, so an item keeps the fields the form does
 * not show. A form item is the loaded item with the same hidden item id (wherever it
 * moved, and whatever was edited); an item with no loaded id is new and taken as is.
 * Original items the form could not load stay after their original predecessor.
 * Undefined when the list carries no item ids or the baseline does not line up with the original.
 */
function mergeItems(original: unknown[], form: unknown[], baseline: unknown[]): Merged | undefined {
  const formIds = LIST_ITEM_IDS.get(form);
  const baselineIds = LIST_ITEM_IDS.get(baseline);
  if (!formIds || !baselineIds) return undefined;
  const pairs = alignItems(original, baseline);
  if (!pairs) return undefined;
  const loadedAt = new Map<number, number>();
  baselineIds.forEach((id, k) => { if (id !== undefined) loadedAt.set(id, k); });

  const items: { value: unknown; kept: KeptLeaf[]; from?: number }[] = form.map((item, i) => {
    const id = formIds[i];
    const k = id === undefined ? undefined : loadedAt.get(id);
    if (k === undefined) return { value: item, kept: [] };
    loadedAt.delete(id!);
    const merged = mergeTracked(original[pairs[k]!], item, baseline[k]);
    return { ...merged, from: pairs[k] };
  });
  const loaded = new Set(pairs);
  original.forEach((item, j) => {
    if (loaded.has(j)) return;
    let at = 0;
    items.forEach((it, pos) => { if (it.from !== undefined && it.from < j) at = pos + 1; });
    items.splice(at, 0, { value: structuredClone(item), kept: [keptLeaf([], item, undefined)], from: j });
  });
  return {
    value: items.map((it) => it.value),
    kept: items.flatMap((it, pos) => (it.from === undefined ? [] : underKey(it.kept, pos, it.from))),
  };
}

/**
 * Deep-merge the form-generated expectation JSON (`formJson`) onto the retained
 * `original`, so that:
 *  - fields the form models are authoritative from the form (including removals
 *    the form can express — a modeled key absent from `formJson` is deleted);
 *  - top-level and request-matcher fields the form does NOT model pass through
 *    unchanged.
 *
 * The action/response slot is treated as one mutually-exclusive group (see
 * {@link ACTION_FAMILY_KEYS}); when `actionModeled` is false the original action
 * is preserved rather than replaced by the form's default.
 *
 * Pure and side-effect free: `original` and `formJson` are not mutated.
 */
export function mergeUnmodeledFields(
  original: Record<string, unknown>,
  formJson: Record<string, unknown>,
  opts: MergeUnmodeledOptions = {},
): Record<string, unknown> {
  const actionModeled = opts.actionModeled !== false;
  const scenarioModeled = opts.scenarioModeled === true;
  const result: Record<string, unknown> = structuredClone(original);

  // Request matcher — nested merge: modeled sub-fields authoritative, the rest
  // (keepAlive / socketAddress / protocol / clientCertificate / …) pass through.
  // buildExpectationJson always emits an httpRequest.
  if (isPlainObject(formJson['httpRequest']) && !isCompositeRequestMatcher(result['httpRequest'])) {
    const formReq = formJson['httpRequest'];
    const mergedReq: Record<string, unknown> = isPlainObject(result['httpRequest'])
      ? structuredClone(result['httpRequest'])
      : {};
    for (const k of FORM_MODELED_REQUEST_KEYS) {
      if (k in formReq) mergedReq[k] = formReq[k];
      else delete mergedReq[k];
    }
    // jwt is form-authoritative only when the form faithfully owns it
    // (jwtModeled). Otherwise the original's jwt survives via the clone above,
    // so a lossy prefill can never rewrite an unfaithfully-representable jwt.
    if (opts.jwtModeled) {
      if ('jwt' in formReq) mergedReq['jwt'] = formReq['jwt'];
      else delete mergedReq['jwt'];
    }
    result['httpRequest'] = mergedReq;
  }

  // Simple form-modeled top-level keys — set when the form emits them, delete
  // when it omits them. The default-preserving keys (priority / times /
  // timeToLive) are handled separately below so an untouched round-trip of an
  // explicit server default does not produce a spurious diff.
  for (const k of FORM_MODELED_TOP_LEVEL_KEYS) {
    if (DEFAULT_PRESERVING_KEY_SET.has(k)) continue;
    if (k in formJson) result[k] = formJson[k];
    else delete result[k];
  }

  // Default-preserving keys — see {@link DEFAULT_PRESERVING_KEYS}. The form is
  // authoritative whenever it emits a (non-default) value; otherwise the form is
  // at its default and we distinguish an untouched round-trip from a deliberate
  // reset using the ORIGINAL's value:
  //   • original already at default (absent or explicit default) → preserve the
  //     clone untouched, so the diff is EMPTY for a semantic no-op;
  //   • original was a real non-default value → the user reset it, so emit the
  //     explicit default form (times / timeToLive) or delete it (priority).
  for (const { key, isDefault, resetForm } of DEFAULT_PRESERVING_KEYS) {
    if (key in formJson) {
      result[key] = formJson[key];
    } else if (key in original && !isDefault(original[key])) {
      if (resetForm) result[key] = resetForm();
      else delete result[key];
    }
    // else: leave the clone as-is (preserve the original's explicit default / absence).
  }

  // Scenario binding keys — form-authoritative ONLY on the Advanced path
  // (scenarioModeled). Otherwise the keys are left as they were cloned from the
  // original, i.e. preserved by passthrough (the Quick path never emits them).
  if (scenarioModeled) {
    for (const k of FORM_MODELED_SCENARIO_KEYS) {
      if (k in formJson) result[k] = formJson[k];
      else delete result[k];
    }
  }

  // Action / response family. `steps` (handled above) is also an action slot,
  // so the form "provides an action" when it emits either an action-family key
  // or a steps pipeline.
  const formProvidesAction =
    ACTION_FAMILY_KEYS.some((k) => k in formJson) || 'steps' in formJson;
  if (actionModeled && formProvidesAction) {
    for (const k of ACTION_FAMILY_KEYS) delete result[k];
    for (const k of ACTION_FAMILY_KEYS) {
      if (!(k in formJson)) continue;
      const orig = original[k];
      const base = opts.actionBaseline?.[k];
      const form = formJson[k];
      result[k] = isPlainObject(orig) && isPlainObject(base) && isPlainObject(form)
        ? mergeTracked(orig, form, base).value
        : form;
    }
  }
  // else: leave the original action family untouched (preserve unmodeled action).

  return result;
}

/** An action field a save keeps from the loaded expectation although the form does not show it. */
export interface KeptActionField {
  /** The action-family key, e.g. `httpResponse`. */
  actionKey: string;
  /** Where the field sits in the saved action. */
  path: (string | number)[];
  /** Where the field sits in the original action (for {@link clearKeptActionField}). */
  originalPath: (string | number)[];
  /** The value that will be saved. */
  value: unknown;
  /** What the form produced there on load; undefined when it produced nothing. */
  baselineValue: unknown;
}

/**
 * The action fields {@link buildExpectationJson} keeps from `action.editOriginal`
 * that the form does not show: fields the form does not model, values it loads
 * lossily, and list items it cannot load. Empty when not editing.
 */
export function keptActionFields(matcher: StandardMatcher, action: StandardActionPayload): KeptActionField[] {
  const original = action.editOriginal;
  const baseline = action.editActionBaseline;
  if (!original || !baseline || action.editActionModeled === false) return [];
  const formJson = buildExpectationJson(matcher, { ...action, editOriginal: undefined });
  const fields: KeptActionField[] = [];
  for (const actionKey of ACTION_FAMILY_KEYS) {
    const orig = original[actionKey];
    const base = baseline[actionKey];
    const form = formJson[actionKey];
    if (!isPlainObject(orig) || !isPlainObject(base) || !isPlainObject(form)) continue;
    for (const leaf of mergeTracked(orig, form, base).kept) fields.push({ actionKey, ...leaf });
  }
  return fields;
}

/** `path` as `delay`, `messages[1].delay`, … for display. */
export function keptFieldLabel(field: Pick<KeptActionField, 'path'>): string {
  return field.path.reduce<string>((label, seg) => (typeof seg === 'number' ? `${label}[${seg}]` : label ? `${label}.${seg}` : seg), '');
}

/**
 * The original with `field` reset to what the form showed on load (or removed when
 * the form showed nothing), so the next save no longer keeps it.
 */
export function clearKeptActionField(
  original: Record<string, unknown>,
  field: KeptActionField,
): Record<string, unknown> {
  const next = structuredClone(original);
  const path = [field.actionKey, ...field.originalPath];
  let parent: unknown = next;
  for (const seg of path.slice(0, -1)) parent = (parent as Record<string | number, unknown> | undefined)?.[seg];
  if (!parent || typeof parent !== 'object') return next;
  const last = path[path.length - 1]!;
  if (field.baselineValue !== undefined) (parent as Record<string | number, unknown>)[last] = structuredClone(field.baselineValue);
  else if (Array.isArray(parent)) parent.splice(last as number, 1);
  else delete (parent as Record<string, unknown>)[last as string];
  return next;
}

/**
 * Names of the fields that {@link mergeUnmodeledFields} would preserve from
 * `original` because the Composer form does not model them — used to show the
 * "Preserving N fields not shown in this form: …" indicator in the editor.
 * Nested request-matcher extras are reported as `httpRequest.<field>`.
 */
export function unmodeledFieldNames(
  original: Record<string, unknown>,
  opts: MergeUnmodeledOptions = {},
): string[] {
  const actionModeled = opts.actionModeled !== false;
  const scenarioModeled = opts.scenarioModeled === true;
  const jwtModeled = opts.jwtModeled === true;
  const modeledTop = new Set<string>([...FORM_MODELED_TOP_LEVEL_KEYS, 'httpRequest']);
  // When the Advanced Scenario section is rendered, the three scenario keys are
  // modeled and must NOT be reported as preserved-passthrough. On the Quick path
  // they remain unmodeled and keep appearing in the "Preserving N fields" chip.
  if (scenarioModeled) for (const k of FORM_MODELED_SCENARIO_KEYS) modeledTop.add(k);
  const actionFamily = new Set<string>(ACTION_FAMILY_KEYS);
  const names: string[] = [];
  for (const key of Object.keys(original)) {
    if (key === 'httpRequest') {
      const req = original['httpRequest'];
      if (isPlainObject(req)) {
        for (const sub of Object.keys(req)) {
          if (FORM_MODELED_REQUEST_KEYS.includes(sub)) continue;
          // jwt is modeled (and hence not preserved-passthrough) only when the
          // form faithfully owns it; otherwise it IS preserved, so report it.
          if (sub === 'jwt' && jwtModeled) continue;
          names.push(`httpRequest.${sub}`);
        }
      }
      continue;
    }
    if (actionFamily.has(key)) {
      // Replaced by the form when it owns the slot; preserved (and hence
      // reported) only when the original action is unmodeled.
      if (!actionModeled) names.push(key);
      continue;
    }
    if (modeledTop.has(key)) continue;
    names.push(key);
  }
  return names;
}

/**
 * Build the JSON object for a chaos profile draft. Returns `undefined` when
 * all fields are empty/unset so the caller can skip emitting the key entirely.
 */
export function buildChaosJson(draft: StandardChaosDraft): Record<string, unknown> | undefined {
  const out: Record<string, unknown> = {};
  if (draft.errorStatus != null) out['errorStatus'] = draft.errorStatus;
  if (draft.errorProbability != null) out['errorProbability'] = draft.errorProbability;
  if (draft.retryAfter) out['retryAfter'] = draft.retryAfter;
  if (draft.latencyValue != null && draft.latencyValue > 0) {
    out['latency'] = { timeUnit: draft.latencyUnit ?? 'MILLISECONDS', value: draft.latencyValue };
  }
  if (draft.seed != null) out['seed'] = draft.seed;
  if (draft.succeedFirst != null) out['succeedFirst'] = draft.succeedFirst;
  if (draft.failRequestCount != null) out['failRequestCount'] = draft.failRequestCount;
  return Object.keys(out).length > 0 ? out : undefined;
}

/**
 * Round-trip: parse a top-level `chaos` JSON object from an existing
 * expectation back into a `StandardChaosDraft` for repopulating the composer.
 */
export function chaosFromExpectation(value: Record<string, unknown>): StandardChaosDraft | undefined {
  const raw = value['chaos'];
  if (!raw || typeof raw !== 'object') return undefined;
  const c = raw as Record<string, unknown>;
  const draft: StandardChaosDraft = {};
  if (typeof c['errorStatus'] === 'number') draft.errorStatus = c['errorStatus'] as number;
  if (typeof c['errorProbability'] === 'number') draft.errorProbability = c['errorProbability'] as number;
  if (typeof c['retryAfter'] === 'string') draft.retryAfter = c['retryAfter'] as string;
  if (c['latency'] && typeof c['latency'] === 'object') {
    const lat = c['latency'] as Record<string, unknown>;
    if (typeof lat['value'] === 'number') {
      draft.latencyValue = lat['value'] as number;
      const tu = lat['timeUnit'];
      draft.latencyUnit = tu === 'SECONDS' ? 'SECONDS' : tu === 'MINUTES' ? 'MINUTES' : 'MILLISECONDS';
    }
  }
  if (typeof c['seed'] === 'number') draft.seed = c['seed'] as number;
  if (typeof c['succeedFirst'] === 'number') draft.succeedFirst = c['succeedFirst'] as number;
  if (typeof c['failRequestCount'] === 'number') draft.failRequestCount = c['failRequestCount'] as number;
  // Only return if at least one field was populated
  return Object.keys(draft).length > 0 ? draft : undefined;
}

/**
 * Round-trip: parse a top-level `capture` array from an existing expectation
 * back into `StandardCaptureRule[]` for repopulating the composer. Returns
 * `undefined` when there is no usable capture array, so the caller can leave the
 * panel collapsed.
 */
export function captureFromExpectation(value: Record<string, unknown>): StandardCaptureRule[] | undefined {
  const raw = value['capture'];
  if (!Array.isArray(raw)) return undefined;
  const validSources = new Set<string>(CAPTURE_SOURCES);
  const result: StandardCaptureRule[] = [];
  for (const entry of raw as unknown[]) {
    if (!entry || typeof entry !== 'object') continue;
    const c = entry as Record<string, unknown>;
    const source = typeof c['source'] === 'string' && validSources.has(c['source'])
      ? (c['source'] as CaptureSource)
      : 'jsonPath';
    const expression = typeof c['expression'] === 'string' ? (c['expression'] as string) : '';
    const into = typeof c['into'] === 'string' ? (c['into'] as string) : '';
    result.push({ source, expression, into });
  }
  return result.length > 0 ? result : undefined;
}

// ---------------------------------------------------------------------------
// Side-effect actions — JSON helpers
// ---------------------------------------------------------------------------

/**
 * Build a single side-effect action object for the JSON payload.
 * Shared by beforeActions and afterActions emission.
 */
function buildSideEffectActionJson(
  se: StandardSideEffectAction,
): Record<string, unknown> {
  const out: Record<string, unknown> = { ...se.preserved?.action };
  if (se.templateType) {
    const tpl: Record<string, unknown> = { ...se.preserved?.httpRequestTemplate, templateType: se.templateType };
    // a file-backed template needs no inline text; otherwise always send the (possibly empty) template
    if (se.template?.trim() || !tpl['templateFile']) tpl['template'] = se.template ?? '';
    out['httpRequestTemplate'] = tpl;
  } else {
    const httpReq: Record<string, unknown> = { ...se.preserved?.httpRequest, path: se.path };
    if (se.method.trim()) httpReq['method'] = se.method.trim();
    // the webhook destination is derived from the Host header (HttpRequest has no
    // top-level host field); this matches the .withHeader("Host", ...) Java preview
    if (se.host.trim()) httpReq['headers'] = { Host: [se.host.trim()] };
    if (se.body.trim()) httpReq['body'] = se.body.trim();
    out['httpRequest'] = httpReq;
  }
  if (se.delayValue > 0) {
    out['delay'] = { timeUnit: se.delayUnit, value: se.delayValue };
  }
  if (se.position === 'before') {
    // blocking defaults to true — only emit when false to keep JSON minimal
    if (!se.blocking) out['blocking'] = false;
    if (se.timeoutValue > 0) {
      out['timeout'] = { timeUnit: se.timeoutUnit, value: se.timeoutValue };
    }
    // failurePolicy defaults to BEST_EFFORT — only emit when FAIL_FAST
    if (se.failurePolicy === 'FAIL_FAST') out['failurePolicy'] = 'FAIL_FAST';
  }
  return out;
}

/** A side-effect row is emitted once it names a webhook: a path, or a template when templated. */
export function isSideEffectComplete(se: StandardSideEffectAction): boolean {
  return se.templateType
    ? !!se.template?.trim() || typeof se.preserved?.httpRequestTemplate?.['templateFile'] === 'string'
    : !!se.path.trim();
}

/**
 * Round-trip: parse `beforeActions` and `afterActions` from an existing
 * expectation back into `StandardSideEffectAction[]` for the composer.
 * Normalises single-object and array forms. Ignores callback-only entries
 * (no httpRequest).
 */
export function sideEffectsFromExpectation(
  value: Record<string, unknown>,
): StandardSideEffectAction[] | undefined {
  const result: StandardSideEffectAction[] = [];

  function normalise(raw: unknown): Record<string, unknown>[] {
    if (!raw) return [];
    if (Array.isArray(raw)) return raw as Record<string, unknown>[];
    if (typeof raw === 'object') return [raw as Record<string, unknown>];
    return [];
  }

  function parseDelayUnit(unit: unknown): SideEffectDelayUnit {
    if (unit === 'SECONDS') return 'SECONDS';
    if (unit === 'MINUTES') return 'MINUTES';
    return 'MILLISECONDS';
  }

  // the webhook destination is carried in the Host header; tolerate both the
  // object form ({ Host: ["host:port"] }) and the name/values array form, plus
  // a legacy top-level host field
  function extractHost(r: Record<string, unknown>): string {
    if (typeof r['host'] === 'string') return r['host'] as string;
    const headers = r['headers'];
    if (headers && typeof headers === 'object' && !Array.isArray(headers)) {
      const h = headers as Record<string, unknown>;
      const v = h['Host'] ?? h['host'];
      if (Array.isArray(v) && typeof v[0] === 'string') return v[0] as string;
      if (typeof v === 'string') return v;
    } else if (Array.isArray(headers)) {
      for (const entry of headers) {
        if (entry && typeof entry === 'object') {
          const e = entry as Record<string, unknown>;
          const name = typeof e['name'] === 'string' ? (e['name'] as string) : '';
          if (name.toLowerCase() === 'host') {
            const values = e['values'];
            if (Array.isArray(values) && typeof values[0] === 'string') return values[0] as string;
          }
        }
      }
    }
    return '';
  }

  function without(o: Record<string, unknown>, keys: string[]): Record<string, unknown> | undefined {
    const rest = Object.fromEntries(Object.entries(o).filter(([k]) => !keys.includes(k)));
    return Object.keys(rest).length > 0 ? rest : undefined;
  }

  function parseTemplateType(t: unknown): SideEffectTemplateType {
    return t === 'VELOCITY' || t === 'MUSTACHE' || t === 'JAVASCRIPT' ? t : '';
  }

  function parseOne(obj: Record<string, unknown>, position: SideEffectPosition): StandardSideEffectAction | null {
    const tpl = obj['httpRequestTemplate'];
    const templated = !!tpl && typeof tpl === 'object' && !!parseTemplateType((tpl as Record<string, unknown>)['templateType']);
    const req = templated ? {} : obj['httpRequest'];
    if (!req || typeof req !== 'object') return null;
    const r = req as Record<string, unknown>;
    const t = (templated ? tpl : {}) as Record<string, unknown>;
    const delay = obj['delay'] as Record<string, unknown> | undefined;
    const timeout = obj['timeout'] as Record<string, unknown> | undefined;
    const preserved: SideEffectPreserved = {};
    const actionRest = without(obj, ['httpRequest', 'httpRequestTemplate', 'delay', 'blocking', 'timeout', 'failurePolicy']);
    if (actionRest) preserved.action = actionRest;
    const requestRest = templated ? undefined : without(r, typeof r['body'] === 'string' ? ['path', 'method', 'headers', 'body'] : ['path', 'method', 'headers']);
    if (requestRest) preserved.httpRequest = requestRest;
    const templateRest = templated ? without(t, ['templateType', 'template']) : undefined;
    if (templateRest) preserved.httpRequestTemplate = templateRest;
    return {
      position,
      ...(Object.keys(preserved).length > 0 ? { preserved } : {}),
      ...(templated ? {
        templateType: parseTemplateType(t['templateType']),
        template: typeof t['template'] === 'string' ? (t['template'] as string) : '',
      } : {}),
      method: typeof r['method'] === 'string' ? (r['method'] as string) : '',
      path: typeof r['path'] === 'string' ? (r['path'] as string) : '',
      host: extractHost(r),
      body: typeof r['body'] === 'string' ? (r['body'] as string) : '',
      delayValue: typeof delay?.['value'] === 'number' ? (delay['value'] as number) : 0,
      delayUnit: parseDelayUnit(delay?.['timeUnit']),
      blocking: obj['blocking'] !== false, // default true
      timeoutValue: typeof timeout?.['value'] === 'number' ? (timeout['value'] as number) : 0,
      timeoutUnit: parseDelayUnit(timeout?.['timeUnit']),
      failurePolicy: obj['failurePolicy'] === 'FAIL_FAST' ? 'FAIL_FAST' : 'BEST_EFFORT',
    };
  }

  for (const obj of normalise(value['beforeActions'])) {
    const parsed = parseOne(obj, 'before');
    if (parsed) result.push(parsed);
  }
  for (const obj of normalise(value['afterActions'])) {
    const parsed = parseOne(obj, 'after');
    if (parsed) result.push(parsed);
  }

  return result.length > 0 ? result : undefined;
}

// ---------------------------------------------------------------------------
// Expectation steps — JSON helpers (M1 increment-2)
// ---------------------------------------------------------------------------

/**
 * Build a single ExpectationStep JSON object from the composer's draft state.
 * The action payload is a free-text JSON string that the user enters; we parse
 * it and embed it under the action-type key (e.g. `httpResponse`).
 */
function buildExpectationStepJson(step: StandardExpectationStep): Record<string, unknown> {
  const out: Record<string, unknown> = {};

  // Parse the free-text action body JSON. Fall back to an empty object.
  let actionPayload: unknown = {};
  if (step.actionBody.trim()) {
    try {
      actionPayload = JSON.parse(step.actionBody.trim());
    } catch {
      // If the JSON is invalid, send it as a string body for the server to reject.
      actionPayload = step.actionBody.trim();
    }
  }

  out[step.actionType] = actionPayload;

  if (step.responder) {
    out['responder'] = true;
  }

  // Side-effect step controls (only for non-responder steps)
  if (!step.responder) {
    if (step.delayValue > 0) {
      out['delay'] = { timeUnit: step.delayUnit, value: step.delayValue };
    }
    // blocking defaults to true — only emit when false
    if (!step.blocking) out['blocking'] = false;
    if (step.timeoutValue > 0) {
      out['timeout'] = { timeUnit: step.timeoutUnit, value: step.timeoutValue };
    }
    if (step.failurePolicy === 'FAIL_FAST') out['failurePolicy'] = 'FAIL_FAST';
  }

  return out;
}

/**
 * The action-type keys that an ExpectationStep can carry, in priority order.
 * Used for detecting which action a step JSON object contains.
 */
const STEP_ACTION_KEYS: StepActionType[] = [
  'httpResponse',
  'httpForward',
  'httpOverrideForwardedRequest',
  'httpError',
  'httpRequest',
  'httpClassCallback',
];

/**
 * Round-trip: parse a `steps` array from an existing expectation back into
 * `StandardExpectationStep[]` for the composer.
 */
export function stepsFromExpectation(
  value: Record<string, unknown>,
): StandardExpectationStep[] | undefined {
  const raw = value['steps'];
  if (!Array.isArray(raw) || raw.length === 0) return undefined;

  const result: StandardExpectationStep[] = [];

  for (const entry of raw as Record<string, unknown>[]) {
    if (!entry || typeof entry !== 'object') continue;

    // Detect which action key is present
    let actionType: StepActionType | null = null;
    let actionPayload: unknown = {};
    for (const key of STEP_ACTION_KEYS) {
      if (key in entry) {
        actionType = key;
        actionPayload = entry[key];
        break;
      }
    }
    if (!actionType) continue;

    const delay = entry['delay'] as Record<string, unknown> | undefined;
    const timeout = entry['timeout'] as Record<string, unknown> | undefined;

    function parseDelayUnit(unit: unknown): SideEffectDelayUnit {
      if (unit === 'SECONDS') return 'SECONDS';
      if (unit === 'MINUTES') return 'MINUTES';
      return 'MILLISECONDS';
    }

    result.push({
      actionType,
      responder: entry['responder'] === true,
      actionBody: typeof actionPayload === 'string'
        ? actionPayload
        : JSON.stringify(actionPayload, null, 2),
      blocking: entry['blocking'] !== false,
      delayValue: typeof delay?.['value'] === 'number' ? (delay['value'] as number) : 0,
      delayUnit: parseDelayUnit(delay?.['timeUnit']),
      timeoutValue: typeof timeout?.['value'] === 'number' ? (timeout['value'] as number) : 0,
      timeoutUnit: parseDelayUnit(timeout?.['timeUnit']),
      failurePolicy: entry['failurePolicy'] === 'FAIL_FAST' ? 'FAIL_FAST' : 'BEST_EFFORT',
    });
  }

  return result.length > 0 ? result : undefined;
}

export function standardToJson(matcher: StandardMatcher, action: StandardActionPayload): string {
  return JSON.stringify(buildExpectationJson(matcher, action), null, 2);
}

// ---------------------------------------------------------------------------
// Java client codegen — emits a snippet usable with MockServerClient
// ---------------------------------------------------------------------------

const JAVA_STRING_IMPORT = 'import static org.mockserver.model.NottableString.string;';

/** A Java NottableString expression for one matcher value; undefined when the Java builder cannot express it. */
function matcherValueToJava(v: WireMatcherValue, imports: Set<string>): string | undefined {
  if (typeof v === 'string') {
    imports.add(JAVA_STRING_IMPORT);
    return `string("${escapeJava(v)}")`;
  }
  const dt = dateTimeMatcherOf(v);
  if (dt) {
    imports.add('import org.mockserver.model.NottableDateTimeString;');
    return dateTimeBuilderExpr(dt, {
      head: 'NottableDateTimeString.', naming: 'camel', str: (x) => `"${escapeJava(x)}"`, bool: String,
    });
  }
  // The two-argument factories take the value verbatim, so a leading ! or ? stays literal.
  const nottable = nottableObjectOf(v);
  if (nottable?.optional) {
    imports.add('import static org.mockserver.model.NottableOptionalString.optional;');
    return `optional("${escapeJava(nottable.value)}", ${nottable.not})`;
  }
  if (nottable) {
    imports.add(JAVA_STRING_IMPORT);
    return `string("${escapeJava(nottable.value)}", ${nottable.not})`;
  }
  if ('schema' in v && Object.keys(v).every((k) => k === 'schema' || (k === 'not' && typeof v[k] === 'boolean'))) {
    imports.add('import static org.mockserver.model.NottableSchemaString.schemaString;');
    const schema = typeof v['schema'] === 'string' ? v['schema'] : JSON.stringify(v['schema']);
    return `schemaString("${escapeJava(schema)}", ${v['not'] === true})`;
  }
  return undefined;
}

/**
 * Builder calls for one header / query / cookie / path-parameter field. Plain string values keep the
 * String overload; a key with any object value uses the NottableString overload, which needs the name
 * as `string("..")` too. Values the builder cannot express are recorded in `omitted`.
 */
function javaMatcherFieldLines(
  values: Record<string, WireMatcherValue[]> | undefined,
  method: string,
  wireField: string,
  plain: (k: string, vs: string[]) => string[],
  single: boolean,
  extras: JavaMatcherExtras,
): string[] {
  if (!values) return [];
  const lines: string[] = [];
  for (const [k, all] of Object.entries(values)) {
    const vs = single ? all.slice(0, 1) : all;
    if (vs.every((v): v is string => typeof v === 'string')) {
      lines.push(...plain(k, vs));
      continue;
    }
    const args: string[] = [];
    for (const v of vs) {
      const expr = matcherValueToJava(v, extras.imports);
      if (expr === undefined) extras.omitted.push(`httpRequest.${wireField}.${k}`);
      else args.push(expr);
    }
    if (args.length === 0) continue;
    extras.imports.add(JAVA_STRING_IMPORT);
    lines.push(`    .${method}(string("${escapeJava(k)}"), ${args.join(', ')})`);
  }
  return lines;
}

/** Imports the request matcher needs beyond the form-driven ones, and the wire values it could not express. */
interface JavaMatcherExtras {
  imports: Set<string>;
  omitted: string[];
}

/**
 * A loaded anyOf request matcher as Java: requestAnyOf(...) of request() branches built from their wire JSON.
 * A branch field the builder below does not write is named in {@link JavaMatcherExtras.omitted}.
 */
function anyOfRequestToJava(request: Record<string, unknown>, extras: JavaMatcherExtras, at = 'httpRequest'): string {
  extras.imports.add('import static org.mockserver.model.AnyOfRequestDefinition.requestAnyOf;');
  const branches = (request['anyOf'] as unknown[]).map((branch, n) =>
    '    ' + wireBranchToJava(isPlainObject(branch) ? branch : {}, extras, `${at}.anyOf[${n}]`).split('\n').join('\n    '));
  for (const key of Object.keys(request)) {
    if (key !== 'anyOf' && key !== 'not') extras.omitted.push(`${at}.${key}`);
  }
  const anyOf = ['requestAnyOf(', branches.join(',\n'), ')'].join('\n');
  if (request['not'] !== true) return anyOf;
  extras.imports.add('import static org.mockserver.model.Not.not;');
  return 'not(' + anyOf + ')';
}

function wireBranchToJava(branch: Record<string, unknown>, extras: JavaMatcherExtras, at: string): string {
  if (Array.isArray(branch['anyOf'])) return anyOfRequestToJava(branch, extras, at);
  const lines: string[] = ['request()'];
  const javaString = (v: unknown) => `"${escapeJava(String(v))}"`;
  const multiValues = (key: string, setter: string) => {
    const values = branch[key];
    if (!isPlainObject(values) || !Object.values(values).every((vs) => Array.isArray(vs) && vs.every((v) => typeof v === 'string'))) {
      extras.omitted.push(`${at}.${key}`);
      return;
    }
    for (const [name, vs] of Object.entries(values)) {
      lines.push(`    .${setter}(${javaString(name)}, ${(vs as string[]).map(javaString).join(', ')})`);
    }
  };
  for (const key of Object.keys(branch)) {
    const value = branch[key];
    switch (key) {
      case 'method':
      case 'path':
      case 'clientIp':
        if (typeof value === 'string') lines.push(`    .with${key === 'clientIp' ? 'ClientIp' : key[0]!.toUpperCase() + key.slice(1)}(${javaString(value)})`);
        else extras.omitted.push(`${at}.${key}`);
        break;
      case 'headers': multiValues(key, 'withHeader'); break;
      case 'queryStringParameters': multiValues(key, 'withQueryStringParameter'); break;
      case 'cookies':
        if (isPlainObject(value) && Object.values(value).every((v) => typeof v === 'string')) {
          for (const [name, v] of Object.entries(value)) lines.push(`    .withCookie(${javaString(name)}, ${javaString(v)})`);
        } else extras.omitted.push(`${at}.${key}`);
        break;
      case 'body':
        if (typeof value === 'string') lines.push(`    .withBody(${javaString(value)})`);
        else extras.omitted.push(`${at}.${key}`);
        break;
      case 'secure':
      case 'keepAlive':
        if (typeof value === 'boolean') lines.push(`    .with${key[0]!.toUpperCase() + key.slice(1)}(${value})`);
        else extras.omitted.push(`${at}.${key}`);
        break;
      case 'not':
        break;
      default:
        extras.omitted.push(`${at}.${key}`);
    }
  }
  const request = lines.join('\n');
  if (branch['not'] !== true) return request;
  extras.imports.add('import static org.mockserver.model.Not.not;');
  return 'not(' + request + ')';
}

/**
 * `, NottableDateTimeString.after(..)` for a JSONPath / XPath body's date-time comparison, else empty;
 * a comparison the builder cannot express is named in `omitted` rather than dropped silently.
 */
function javaBodyDateTimeArg(matcher: StandardMatcher, extras: JavaMatcherExtras): string {
  const dt = dateTimeMatcherOf(bodyDateTimeFromText(matcher.bodyDateTime));
  if (!dt) {
    if (matcher.bodyDateTime?.trim()) extras.omitted.push('httpRequest.body.dateTime');
    return '';
  }
  extras.imports.add('import org.mockserver.model.NottableDateTimeString;');
  return ', ' + dateTimeBuilderExpr(dt, {
    head: 'NottableDateTimeString.', naming: 'camel', str: (x) => `"${escapeJava(x)}"`, bool: String,
  });
}

function matcherToJava(matcher: StandardMatcher, extras: JavaMatcherExtras = { imports: new Set(), omitted: [] }): string {
  // DNS expectations use a DnsRequestDefinition matcher, not an HttpRequest.
  if (matcher.dns && matcher.dns.dnsName.trim()) {
    const dnsLines: string[] = ['dnsRequest()'];
    dnsLines.push(`    .withDnsName("${escapeJava(matcher.dns.dnsName.trim())}")`);
    if (matcher.dns.dnsType) dnsLines.push(`    .withDnsType(DnsRecordType.${matcher.dns.dnsType})`);
    if (matcher.dns.dnsClass) dnsLines.push(`    .withDnsClass(DnsRecordClass.${matcher.dns.dnsClass})`);
    return dnsLines.join('\n');
  }
  const lines: string[] = ['request()'];
  if (matcher.method) lines.push(`    .withMethod("${escapeJava(matcher.method)}")`);
  if (matcher.path) lines.push(`    .withPath("${escapeJava(matcher.path)}")`);

  lines.push(...javaMatcherFieldLines(
    parseMatcherKeyValueLines(matcher.headers, ':'), 'withHeader', 'headers',
    (k, vs) => vs.map((v) => `    .withHeader("${escapeJava(k)}", "${escapeJava(v)}")`), false, extras,
  ));
  lines.push(...javaMatcherFieldLines(
    parseMatcherKeyValueLines(matcher.queryString, '='), 'withQueryStringParameter', 'queryStringParameters',
    (k, vs) => [`    .withQueryStringParameter("${escapeJava(k)}", ${vs.map((v) => `"${escapeJava(v)}"`).join(', ')})`], false, extras,
  ));
  lines.push(...javaMatcherFieldLines(
    parseMatcherKeyValueLines(matcher.cookies, '='), 'withCookie', 'cookies',
    (k, vs) => [`    .withCookie("${escapeJava(k)}", "${escapeJava(vs[0] ?? '')}")`], true, extras,
  ));
  lines.push(...javaMatcherFieldLines(
    parseMatcherKeyValueLines(matcher.pathParams, '='), 'withPathParameter', 'pathParameters',
    (k, vs) => [`    .withPathParameter("${escapeJava(k)}", "${escapeJava(vs[0] ?? '')}")`], false, extras,
  ));

  if (matcher.bodyMatcherType === 'allOf') {
    const entries = allOfEntries(matcher);
    if (entries.length > 0) {
      lines.push(`    .withBody(allOf(${entries.map(allOfEntryToJava).join(', ')}))`);
    }
  } else if (matcher.body.trim()) {
    if (matcher.bodyMatcherType === 'binary' || matcher.bodyBinary) {
      lines.push(`    .withBody(binary(Base64.getDecoder().decode("${escapeJava(matcher.body.trim())}")))`);
    } else if (matcher.bodyMatcherType === 'graphql') {
      let gql = `graphQL("${escapeJava(matcher.body.trim())}")`;
      if (matcher.graphqlOptions && matcher.graphqlOptions.selectionSetMatchType !== 'NORMALISED_STRING') {
        gql += `\n        .withSelectionSetMatchType(SelectionSetMatchType.${matcher.graphqlOptions.selectionSetMatchType})`;
        const fields = matcher.graphqlOptions.fields.split(',').map((f) => f.trim()).filter(Boolean);
        if (fields.length > 0) {
          gql += `\n        .withFields(${fields.map((f) => `"${escapeJava(f)}"`).join(', ')})`;
        }
      }
      lines.push(`    .withBody(${gql})`);
    } else if (matcher.bodyMatcherType === 'json-schema') {
      lines.push(`    .withBody(jsonSchema("${escapeJava(matcher.body.trim())}"))`);
    } else if (matcher.bodyMatcherType === 'json-path') {
      lines.push(`    .withBody(jsonPath("${escapeJava(matcher.body.trim())}"${javaBodyDateTimeArg(matcher, extras)}))`);
    } else if (matcher.bodyMatcherType === 'xml') {
      lines.push(`    .withBody(xml("${escapeJava(matcher.body.trim())}"))`);
    } else if (matcher.bodyMatcherType === 'xml-schema') {
      lines.push(`    .withBody(xmlSchema("${escapeJava(matcher.body.trim())}"))`);
    } else if (matcher.bodyMatcherType === 'xpath') {
      lines.push(`    .withBody(xpath("${escapeJava(matcher.body.trim())}"${javaBodyDateTimeArg(matcher, extras)}))`);
    } else if (matcher.bodyMatcherType === 'regex') {
      lines.push(`    .withBody(regex("${escapeJava(matcher.body.trim())}"))`);
    } else if (matcher.bodyMatcherType === 'parameters') {
      const params = parseKeyValueLines(matcher.body, '=');
      if (params) {
        const paramEntries = Object.entries(params)
          .map(([k, vs]) => `param("${escapeJava(k)}", ${vs.map((v) => `"${escapeJava(v)}"`).join(', ')})`)
          .join(', ');
        lines.push(`    .withBody(params(${paramEntries}))`);
      }
    } else if (matcher.bodyMatcherType === 'json') {
      if (matcher.jsonMatchType === 'STRICT') {
        lines.push(`    .withBody(json("${escapeJava(matcher.body.trim())}", MatchType.STRICT))`);
      } else {
        lines.push(`    .withBody(json("${escapeJava(matcher.body.trim())}"))`);
      }
    } else if (matcher.bodyMatcherType === 'wasm') {
      lines.push(`    .withBody(WasmBody.wasmBody("${escapeJava(matcher.body.trim())}"))`);
    } else {
      // Default 'string' type — optionally with subString
      if (matcher.bodySubString) {
        lines.push(`    .withBody(subString("${escapeJava(matcher.body)}"))`);
      } else {
        lines.push(`    .withBody("${escapeJava(matcher.body)}")`);
      }
    }
  }
  const jwtJson = buildJwtJson(matcher.jwt);
  if (jwtJson) {
    lines.push(`    .withJwt(${jwtToJava(matcher.jwt!)})`);
  }
  if (matcher.secure != null) lines.push(`    .withSecure(${matcher.secure})`);
  const clientIp = matcher.clientIp?.trim();
  if (clientIp) lines.push(`    .withClientIp("${escapeJava(clientIp)}")`);
  return lines.join('\n');
}

/**
 * A JSON value as a Java expression, as HttpTemplateToJavaSerializer writes a template parameter: maps as
 * `Map.of` / `Map.ofEntries`, or a `LinkedHashMap` filled in place when a value is null, and lists as
 * `Arrays.<Object>asList` with a null element cast so it is not read as the varargs array.
 */
export function javaJsonValue(v: unknown): string {
  if (v === null || v === undefined) return 'null';
  if (typeof v === 'string') return `"${escapeJava(v)}"`;
  if (typeof v === 'boolean') return String(v);
  if (typeof v === 'number') {
    if (!Number.isFinite(v)) return 'null';
    if (Number.isInteger(v)) return Math.abs(v) <= 2147483647 ? String(v) : `${v}L`;
    return String(v);
  }
  if (Array.isArray(v)) {
    return `java.util.Arrays.<Object>asList(${v.map((e) => (e === null ? '(Object) null' : javaJsonValue(e))).join(', ')})`;
  }
  const entries = Object.entries(v as Record<string, unknown>);
  if (entries.some(([, e]) => e === null)) {
    return `new java.util.LinkedHashMap<String, Object>() {{ ${entries.map(([k, e]) => `put("${escapeJava(k)}", ${javaJsonValue(e)});`).join(' ')} }}`;
  }
  if (entries.length > 10) {
    return `java.util.Map.ofEntries(${entries.map(([k, e]) => `java.util.Map.entry("${escapeJava(k)}", ${javaJsonValue(e)})`).join(', ')})`;
  }
  return `java.util.Map.of(${entries.map(([k, e]) => `"${escapeJava(k)}", ${javaJsonValue(e)}`).join(', ')})`;
}

/** `.withParameter(...)` calls for a saved template's `parameters`. */
function javaTemplateParameterCalls(w: JavaWireAction): string[] {
  const parameters = w.wire['parameters'];
  if (!parameters || typeof parameters !== 'object' || Array.isArray(parameters)) return [];
  return Object.entries(parameters as Record<string, unknown>).map(([k, v]) => `.withParameter("${escapeJava(k)}", ${javaJsonValue(v)})`);
}

/** Builds the Java `template(...)` expression, appending `.withTemplateFile(...)` when a file is set.
 *  When only a file is set the no-template builder `template(TemplateType.X)` is used. */
function templateJavaExpr(templateType: string, template: string, templateFile?: string): string {
  const tf = templateFile?.trim();
  if (tf && !template.trim()) {
    return `template(TemplateType.${templateType})\n        .withTemplateFile("${escapeJava(tf)}")`;
  }
  if (tf) {
    return `template(TemplateType.${templateType}, "${escapeJava(template)}")\n        .withTemplateFile("${escapeJava(tf)}")`;
  }
  return `template(TemplateType.${templateType}, "${escapeJava(template)}")`;
}

function actionToJava(action: StandardActionPayload, w: JavaWireAction): string {
  // Builder calls for the delay and primary every action inherits, placed before the closing `)`.
  const withInherited = (code: string, renderDelay = true): string => {
    const calls = javaDelayPrimaryCalls(w, renderDelay);
    if (calls.length === 0) return code;
    const lines = code.split('\n');
    const close = lines.pop()!;
    return [...lines, ...calls.map((c) => `        ${c}`), close].join('\n');
  };
  switch (action.type) {
    case 'static': {
      const s = action.static;
      if (!s) return '.respond(response())';
      const fromFile = !!s.bodyFromFile && !!s.filePath?.trim();
      const lines = ['.respond('];
      lines.push('    response()');
      lines.push(`        .withStatusCode(${s.statusCode})`);
      if (s.reasonPhrase?.trim()) lines.push(`        .withReasonPhrase("${escapeJava(s.reasonPhrase.trim())}")`);
      // For a FILE body the content type is carried on the body itself, not emitted as a header.
      if (s.contentType && !fromFile) lines.push(`        .withHeader("Content-Type", "${escapeJava(s.contentType)}")`);
      const staticHeaders = parseKeyValueLines(s.headers ?? '', ':');
      if (staticHeaders) {
        for (const [k, vs] of Object.entries(staticHeaders)) {
          if (k.toLowerCase() === 'content-type') continue; // owned by the contentType field above
          const values = vs.map((v) => `"${escapeJava(v)}"`).join(', ');
          lines.push(`        .withHeader("${escapeJava(k)}", ${values})`);
        }
      }
      // Response cookies — one .withCookie() call per name=value
      if (s.cookies?.trim()) {
        const cookiePairs = parseKeyValueLines(s.cookies, '=');
        if (cookiePairs) {
          for (const [k, vs] of Object.entries(cookiePairs)) {
            lines.push(`        .withCookie("${escapeJava(k)}", "${escapeJava(vs[0] ?? '')}")`);
          }
        }
      }
      if (fromFile) {
        const path = `"${escapeJava(s.filePath!.trim())}"`;
        const templatedPath = s.fileTemplateType && s.templatedFilePath ? '.withTemplatedFilePath(true)' : '';
        if (s.fileTemplateType && s.contentType) {
          lines.push(`        .withBody(file(${path}, MediaType.parse("${escapeJava(s.contentType)}"), TemplateType.${s.fileTemplateType})${templatedPath})`);
        } else if (s.fileTemplateType) {
          lines.push(`        .withBody(file(${path}, TemplateType.${s.fileTemplateType})${templatedPath})`);
        } else if (s.contentType) {
          lines.push(`        .withBody(file(${path}, MediaType.parse("${escapeJava(s.contentType)}")))`);
        } else {
          lines.push(`        .withBody(file(${path}))`);
        }
      } else if (s.body) {
        lines.push(`        .withBody("${escapeJava(s.body)}")`);
      }
      // Delay — withDelay(TimeUnit, long), from the saved JSON so a delay kept through an edit is shown
      const delayArgs = javaDelayArgs(w.wire['delay']);
      if (delayArgs) {
        lines.push(`        .withDelay(${delayArgs})`);
        w.imports.add('import java.util.concurrent.TimeUnit;');
      } else if (w.wire['delay'] !== undefined) {
        w.omitted.add('delay');
      }
      const co = s.connectionOptions;
      if (buildConnectionOptionsJson(co) && co) {
        const coParts: string[] = ['connectionOptions()'];
        if (co.keepAliveOverride != null) coParts.push(`.withKeepAliveOverride(${co.keepAliveOverride})`);
        if (co.closeSocket != null) coParts.push(`.withCloseSocket(${co.closeSocket})`);
        if (co.contentLengthHeaderOverride != null) coParts.push(`.withContentLengthHeaderOverride(${co.contentLengthHeaderOverride})`);
        if (co.suppressContentLengthHeader) coParts.push('.withSuppressContentLengthHeader(true)');
        if (co.suppressConnectionHeader) coParts.push('.withSuppressConnectionHeader(true)');
        lines.push('        .withConnectionOptions(');
        lines.push(`            ${coParts[0]}`);
        for (const part of coParts.slice(1)) lines.push(`                ${part}`);
        lines.push('        )');
      }
      lines.push(')');
      return withInherited(lines.join('\n'), false);
    }
    case 'forward': {
      const f = action.forward;
      if (!f) return '.forward(forward())';
      return withInherited([
        '.forward(',
        '    forward()',
        `        .withScheme(Scheme.${f.scheme})`,
        `        .withHost("${escapeJava(f.host)}")`,
        `        .withPort(${f.port})`,
        ')',
      ].join('\n'));
    }
    case 'forward_override': {
      const o = action.forwardOverride;
      if (!o) return '.forward(forwardOverriddenRequest(request()))';
      // Collect the builder calls without indentation, then indent each line
      // uniformly when assembling. (Pre-indenting here and joining with a second
      // indent — as an earlier version did — double-indented every line and left
      // the leading `request()` at column 0.)
      const overrideCalls: string[] = [];
      if (o.overrideMethod) overrideCalls.push(`.withMethod("${escapeJava(o.overrideMethod)}")`);
      if (o.overridePath) overrideCalls.push(`.withPath("${escapeJava(o.overridePath)}")`);
      if (o.overrideHost) overrideCalls.push(`.withHeader("Host", "${escapeJava(o.overrideHost)}")`);
      if (o.overrideScheme) overrideCalls.push(`.withSecure(${o.overrideScheme === 'HTTPS'})`);
      const overrideHeaders = parseKeyValueLines(o.overrideHeaders, ':');
      if (overrideHeaders) {
        for (const [k, vs] of Object.entries(overrideHeaders)) {
          for (const v of vs) overrideCalls.push(`.withHeader("${escapeJava(k)}", "${escapeJava(v)}")`);
        }
      }
      const overrideQuery = parseKeyValueLines(o.overrideQueryString, '=');
      if (overrideQuery) {
        for (const [k, vs] of Object.entries(overrideQuery)) {
          const values = vs.map((v) => `"${escapeJava(v)}"`).join(', ');
          overrideCalls.push(`.withQueryStringParameter("${escapeJava(k)}", ${values})`);
        }
      }
      if (o.overrideBody) overrideCalls.push(`.withBody("${escapeJava(o.overrideBody)}")`);
      const inherited = javaDelayPrimaryCalls(w, true);
      return [
        '.forward(',
        '    forwardOverriddenRequest(',
        '      request()',
        ...overrideCalls.map((c) => `        ${c}`),
        inherited.length > 0 ? `    )${inherited.join('')}` : '    )',
        ')',
      ].join('\n');
    }
    case 'callback': {
      const c = action.callback;
      if (!c) return '.respond(callback())';
      return withInherited([
        '.respond(',
        '    callback()',
        `        .withCallbackClass("${escapeJava(c.callbackClass)}")`,
        ')',
      ].join('\n'));
    }
    case 'template': {
      const t = action.template;
      if (!t) return '.respond(template(TemplateType.VELOCITY, ""))';
      return withInherited([
        '.respond(',
        '    ' + templateJavaExpr(t.templateType, t.template, t.templateFile),
        ...javaTemplateParameterCalls(w).map((c) => `        ${c}`),
        ')',
      ].join('\n'));
    }
    case 'error': {
      const e = action.error;
      if (!e) return '.error(error())';
      const lines = ['.error(', '    error()'];
      if (e.dropConnection) lines.push('        .withDropConnection(true)');
      if (e.resetConnection) lines.push('        .withResetConnection(true)');
      if (e.responseBytesB64.trim()) lines.push(`        .withResponseBytes(Base64.getDecoder().decode("${escapeJava(e.responseBytesB64.trim())}"))`);
      const errorDelay = javaDelayArgs(w.wire['delay']);
      if (errorDelay) {
        lines.push(`        .withDelay(new Delay(${errorDelay}))`);
        w.imports.add('import org.mockserver.model.Delay;');
        w.imports.add('import java.util.concurrent.TimeUnit;');
      } else if (w.wire['delay'] !== undefined) {
        w.omitted.add('delay');
      }
      lines.push(')');
      return withInherited(lines.join('\n'), false);
    }
    case 'forward_fallback': {
      const fb = action.forwardFallback;
      if (!fb) return '.forwardWithFallback(forwardWithFallback())';
      const lines = ['.forwardWithFallback(', '    forwardWithFallback()'];
      lines.push(`        .withForward(forward().withScheme(Scheme.${fb.scheme}).withHost("${escapeJava(fb.host)}").withPort(${fb.port}))`);
      const respParts = [`response().withStatusCode(${fb.fallbackStatusCode})`];
      if (fb.fallbackBody.trim()) respParts.push(`.withBody("${escapeJava(fb.fallbackBody)}")`);
      lines.push(`        .withFallback(${respParts.join('')})`);
      const codes = fb.fallbackOnStatusCodes.split(',').map((s) => parseInt(s.trim(), 10)).filter((n) => !isNaN(n));
      if (codes.length > 0) lines.push(`        .withFallbackOnStatusCodes(${codes.join(', ')})`);
      lines.push(`        .withFallbackOnTimeout(${fb.fallbackOnTimeout})`);
      lines.push(')');
      return withInherited(lines.join('\n'));
    }
    case 'websocket': {
      const ws = action.websocket;
      if (!ws) return '.respondWithWebSocket(webSocketResponse())';
      const lines = ['.respondWithWebSocket(', '    webSocketResponse()'];
      if (ws.subprotocol.trim()) lines.push(`        .withSubprotocol("${escapeJava(ws.subprotocol.trim())}")`);
      // Messages and matchers come from the saved JSON so per-message fields kept through an edit are shown.
      wireItems(w, 'messages').forEach((m, i) => {
        lines.push(`        .withMessage(${javaWebSocketMessage(m, w, `messages[${i}]`)})`);
      });
      wireItems(w, 'matchers').forEach((m, i) => {
        // Emit the nested webSocketMessageMatcher() builder across indented lines.
        lines.push('        .withMatcher(');
        lines.push(`            webSocketMessageMatcher()`);
        if (typeof m['frameType'] === 'string') lines.push(`                .withFrameType(WebSocketFrameType.${m['frameType']})`);
        if (typeof m['textMatcher'] === 'string') lines.push(`                .withTextMatcher(string("${escapeJava(m['textMatcher'])}"))`);
        else if (m['textMatcher'] !== undefined) w.omitted.add(`matchers[${i}].textMatcher`);
        for (const k of Object.keys(m)) if (!['frameType', 'textMatcher', 'responses'].includes(k)) w.omitted.add(`matchers[${i}].${k}`);
        wireItems({ ...w, wire: m }, 'responses').forEach((r, j) => {
          lines.push(`                .withResponse(${javaWebSocketMessage(r, w, `matchers[${i}].responses[${j}]`)})`);
        });
        lines.push('        )');
      });
      lines.push(`        .withCloseConnection(${ws.closeConnection})`);
      lines.push(')');
      return withInherited(lines.join('\n'));
    }
    case 'sse': {
      const sse = action.sse;
      if (!sse) return '.respondWithSse(sseResponse())';
      const lines = ['.respondWithSse(', '    sseResponse()'];
      if (sse.statusCode) lines.push(`        .withStatusCode(${sse.statusCode})`);
      // Events come from the saved JSON so per-event fields kept through an edit are shown.
      wireItems(w, 'events').forEach((ev, i) => {
        let evChain = 'sseEvent()';
        if (typeof ev['event'] === 'string') evChain += `.withEvent("${escapeJava(ev['event'])}")`;
        if (typeof ev['data'] === 'string') evChain += `.withData("${escapeJava(ev['data'])}")`;
        if (typeof ev['id'] === 'string') evChain += `.withId("${escapeJava(ev['id'])}")`;
        if (typeof ev['retry'] === 'number') evChain += `.withRetry(${ev['retry']})`;
        if (typeof ev['comment'] === 'string') evChain += `.withComment("${escapeJava(ev['comment'])}")`;
        if (ev['delay'] !== undefined) {
          const args = javaDelayArgs(ev['delay']);
          if (args) {
            evChain += `.withDelay(${args})`;
            w.imports.add('import java.util.concurrent.TimeUnit;');
          } else {
            w.omitted.add(`events[${i}].delay`);
          }
        }
        for (const k of Object.keys(ev)) if (!['event', 'data', 'id', 'retry', 'comment', 'delay'].includes(k)) w.omitted.add(`events[${i}].${k}`);
        lines.push(`        .withEvent(${evChain})`);
      });
      lines.push(`        .withCloseConnection(${sse.closeConnection})`);
      if (w.wire['closeDelay'] !== undefined) {
        const args = javaDelayArgs(w.wire['closeDelay']);
        if (args) {
          lines.push(`        .withCloseDelay(${args})`);
          w.imports.add('import java.util.concurrent.TimeUnit;');
        } else {
          w.omitted.add('closeDelay');
        }
      }
      if (typeof w.wire['keepStreamOpen'] === 'boolean') lines.push(`        .withKeepStreamOpen(${w.wire['keepStreamOpen']})`);
      lines.push(')');
      return withInherited(lines.join('\n'));
    }
    case 'binary_response': {
      const bin = action.binaryResponse;
      if (!bin) return '.respondWithBinary(binaryResponse())';
      const lines = ['.respondWithBinary(', '    binaryResponse()'];
      if (bin.binaryData.trim()) {
        lines.push(`        .withBinaryData(Base64.getDecoder().decode("${escapeJava(bin.binaryData.trim())}"))`);
      }
      if (bin.upstream) lines.push(`        .withUpstream(Upstream.${bin.upstream})`);
      lines.push(')');
      return withInherited(lines.join('\n'));
    }
    case 'dns_response': {
      const dns = action.dnsResponse;
      if (!dns) return '.respondWithDns(dnsResponse())';
      const lines = ['.respondWithDns(', '    dnsResponse()'];
      if (dns.responseCode) lines.push(`        .withResponseCode(DnsResponseCode.${dns.responseCode})`);
      for (const rec of parseDnsRecords(dns.answerRecords)) {
        // Emit the nested dnsRecord() builder across indented lines rather than one long
        // call, so the snippet stays readable.
        lines.push('        .withAnswerRecord(');
        lines.push('            dnsRecord()');
        if (rec.name) lines.push(`                .withName("${escapeJava(rec.name)}")`);
        if (rec.type) lines.push(`                .withType(DnsRecordType.${rec.type})`);
        if (rec.dnsClass) lines.push(`                .withDnsClass(DnsRecordClass.${rec.dnsClass})`);
        if (rec.ttl != null) lines.push(`                .withTtl(${rec.ttl})`);
        if (rec.value) lines.push(`                .withValue("${escapeJava(rec.value)}")`);
        lines.push('        )');
      }
      lines.push(')');
      return withInherited(lines.join('\n'));
    }
    case 'forward_template': {
      const ft = action.forwardTemplate;
      if (!ft) return '.forward(template(TemplateType.VELOCITY, ""))';
      return withInherited([
        '.forward(',
        '    ' + templateJavaExpr(ft.templateType, ft.template, ft.templateFile),
        ...javaTemplateParameterCalls(w).map((c) => `        ${c}`),
        ')',
      ].join('\n'));
    }
    case 'forward_class_callback': {
      const fc = action.forwardClassCallback;
      if (!fc) return '.forward(callback())';
      return withInherited([
        '.forward(',
        '    callback()',
        `        .withCallbackClass("${escapeJava(fc.callbackClass)}")`,
        ')',
      ].join('\n'));
    }
    case 'grpc_stream': {
      const grpc = action.grpcStream;
      if (!grpc) return '.respondWithGrpcStream(grpcStreamResponse())';
      const lines = ['.respondWithGrpcStream(', '    grpcStreamResponse()'];
      if (grpc.statusName.trim()) lines.push(`        .withStatusName("${escapeJava(grpc.statusName.trim())}")`);
      if (grpc.statusMessage.trim()) lines.push(`        .withStatusMessage("${escapeJava(grpc.statusMessage.trim())}")`);
      const grpcHeaders = parseKeyValueLines(grpc.headers, ':');
      if (grpcHeaders) {
        for (const [k, vs] of Object.entries(grpcHeaders)) {
          const values = vs.map((v) => `"${escapeJava(v)}"`).join(', ');
          lines.push(`        .withHeader("${escapeJava(k)}", ${values})`);
        }
      }
      // Messages come from the saved JSON so per-message fields kept through an edit are shown.
      wireItems(w, 'messages').forEach((m, i) => {
        const json = `"${escapeJava(String(m['json'] ?? ''))}"`;
        let msg = `grpcStreamMessage(${json})`;
        let typed = false;
        if (typeof m['templateType'] === 'string') {
          msg += `.withTemplateType(TemplateType.${m['templateType']})`;
          w.imports.add('import org.mockserver.model.HttpTemplate.TemplateType;');
          typed = true;
        }
        if (m['delay'] !== undefined) {
          const args = javaDelayArgs(m['delay']);
          if (args) {
            msg += `.withDelay(${args})`;
            w.imports.add('import java.util.concurrent.TimeUnit;');
            typed = true;
          } else {
            w.omitted.add(`messages[${i}].delay`);
          }
        }
        for (const k of Object.keys(m)) if (!['json', 'templateType', 'delay'].includes(k)) w.omitted.add(`messages[${i}].${k}`);
        if (typed) w.imports.add('import static org.mockserver.model.GrpcStreamMessage.grpcStreamMessage;');
        lines.push(`        .withMessage(${typed ? msg : json})`);
      });
      lines.push(`        .withCloseConnection(${grpc.closeConnection})`);
      lines.push(')');
      return withInherited(lines.join('\n'));
    }
    default:
      // Exhaustiveness guard: if a new StandardActionType is added without a
      // case above, `action.type` is no longer `never` here and this fails to
      // compile — preventing a silent `undefined` from leaking into the Java.
      return assertNever(action.type);
  }
}

function assertNever(x: never): never {
  throw new Error(`Unhandled standard action type: ${JSON.stringify(x)}`);
}

function chaosToJava(chaos: StandardChaosDraft): string {
  const lines: string[] = ['.withChaos(', '    httpChaosProfile()'];
  if (chaos.errorStatus != null) lines.push(`        .withErrorStatus(${chaos.errorStatus})`);
  if (chaos.errorProbability != null) {
    const prob = chaos.errorProbability % 1 === 0 ? chaos.errorProbability.toFixed(1) : String(chaos.errorProbability);
    lines.push(`        .withErrorProbability(${prob})`);
  }
  if (chaos.retryAfter) lines.push(`        .withRetryAfter("${escapeJava(chaos.retryAfter)}")`);
  if (chaos.latencyValue != null && chaos.latencyValue > 0) {
    const unit = chaos.latencyUnit ?? 'MILLISECONDS';
    lines.push(`        .withLatency(new Delay(TimeUnit.${unit}, ${chaos.latencyValue}))`);
  }
  if (chaos.seed != null) lines.push(`        .withSeed(${chaos.seed}L)`);
  if (chaos.succeedFirst != null) lines.push(`        .withSucceedFirst(${chaos.succeedFirst})`);
  if (chaos.failRequestCount != null) lines.push(`        .withFailRequestCount(${chaos.failRequestCount})`);
  lines.push(')');
  return lines.join('\n');
}

// ---------------------------------------------------------------------------
// LLM response Java codegen — transpile a PRESERVED httpLlmResponse action
// (carried through an edit overlay because the standard composer form cannot
// model it) into the type-safe org.mockserver.model fluent builder chain,
// ending in `.respondWithLlm(...)`. Driven from the built expectation JSON (the
// same object the JSON / curl / client-library tabs render) so the Java tab is
// faithful rather than a whole-action fallback notice.
//
// The Java LLM client is fully typed: HttpLlmResponse.llmResponse() with
// withProvider/withModel/withCompletion/withEmbedding/withRerank/withModeration/
// withContentFilter/withConversationPredicates/withChaos, and the model builders
// Completion, Usage, StreamingPhysics, ToolUse, ConversationPredicates,
// NormalizationOptions, EmbeddingResponse, RerankResponse, ModerationResponse,
// LlmContentFilter and LlmChaosProfile — so every field the wire JSON carries has
// a setter. Any key without one is named in a single honest NOTE comment.
// ---------------------------------------------------------------------------

interface LlmJavaCall {
  method: string;
  /** A single-line argument expression, or a nested builder to render multi-line. */
  arg: string | LlmJavaChain;
}
interface LlmJavaChain {
  factory: string;
  calls: LlmJavaCall[];
}

/** Render a fluent builder chain, one `.withX(...)` per line, nesting sub-builders. */
function renderLlmChain(chain: LlmJavaChain, indent: string): string[] {
  const lines: string[] = [indent + chain.factory];
  for (const call of chain.calls) {
    if (typeof call.arg === 'string') {
      lines.push(`${indent}    .${call.method}(${call.arg})`);
    } else {
      lines.push(`${indent}    .${call.method}(`);
      for (const l of renderLlmChain(call.arg, indent + '        ')) lines.push(l);
      lines.push(`${indent}    )`);
    }
  }
  return lines;
}

/** A Java `double` literal — integral values get a `.0` so they bind to a Double
 *  parameter (Java will not widen an int literal to Double). Mirrors chaosToJava. */
function javaDoubleLiteral(n: number): string {
  return n % 1 === 0 ? n.toFixed(1) : String(n);
}

const asJsonObject = (v: unknown): Record<string, unknown> | undefined =>
  v != null && typeof v === 'object' && !Array.isArray(v) ? (v as Record<string, unknown>) : undefined;

/**
 * Transpile a preserved `httpLlmResponse` action JSON object into the
 * `.respondWithLlm(llmResponse()...)` terminal-action snippet plus the imports it
 * needs. `note` names any fields the builder chain could not carry (normally none).
 */
function llmResponseToJava(llm: Record<string, unknown>): { code: string; imports: string[]; note?: string } {
  const imports = new Set<string>(['import static org.mockserver.model.HttpLlmResponse.llmResponse;']);
  const calls: LlmJavaCall[] = [];
  const handled = new Set<string>();
  const unmapped: string[] = [];

  const str = (o: Record<string, unknown>, k: string): string | undefined =>
    typeof o[k] === 'string' ? (o[k] as string) : undefined;
  const num = (o: Record<string, unknown>, k: string): number | undefined =>
    typeof o[k] === 'number' ? (o[k] as number) : undefined;
  const bool = (o: Record<string, unknown>, k: string): boolean | undefined =>
    typeof o[k] === 'boolean' ? (o[k] as boolean) : undefined;

  // provider / model on the response
  const provider = str(llm, 'provider');
  if (provider && provider.trim()) {
    imports.add('import org.mockserver.model.Provider;');
    calls.push({ method: 'withProvider', arg: `Provider.${provider.trim()}` });
    handled.add('provider');
  }
  const model = str(llm, 'model');
  if (model !== undefined) {
    calls.push({ method: 'withModel', arg: `"${escapeJava(model)}"` });
    handled.add('model');
  }

  // completion — the main text/tool/usage/streaming payload
  const completion = asJsonObject(llm['completion']);
  if (completion) {
    imports.add('import static org.mockserver.model.Completion.completion;');
    const cCalls: LlmJavaCall[] = [];
    const cHandled = new Set<string>();
    const text = str(completion, 'text');
    if (text !== undefined) { cCalls.push({ method: 'withText', arg: `"${escapeJava(text)}"` }); cHandled.add('text'); }
    const toolCalls = completion['toolCalls'];
    if (Array.isArray(toolCalls) && toolCalls.length > 0) {
      imports.add('import static org.mockserver.model.ToolUse.toolUse;');
      for (const raw of toolCalls) {
        const tc = asJsonObject(raw);
        if (!tc) continue;
        let expr = `toolUse("${escapeJava(str(tc, 'name') ?? '')}")`;
        const id = str(tc, 'id');
        if (id !== undefined) expr += `.withId("${escapeJava(id)}")`;
        const args = str(tc, 'arguments');
        if (args !== undefined) expr += `.withArguments("${escapeJava(args)}")`;
        cCalls.push({ method: 'withToolCall', arg: expr });
      }
      cHandled.add('toolCalls');
    }
    const stopReason = str(completion, 'stopReason');
    if (stopReason !== undefined) { cCalls.push({ method: 'withStopReason', arg: `"${escapeJava(stopReason)}"` }); cHandled.add('stopReason'); }
    const usage = asJsonObject(completion['usage']);
    if (usage) {
      imports.add('import static org.mockserver.model.Usage.usage;');
      const uCalls: LlmJavaCall[] = [];
      const usageFields = [
        ['inputTokens', 'withInputTokens'], ['outputTokens', 'withOutputTokens'],
        ['cachedInputTokens', 'withCachedInputTokens'], ['cacheCreationTokens', 'withCacheCreationTokens'],
        ['reasoningTokens', 'withReasoningTokens'],
      ] as const;
      for (const [k, m] of usageFields) {
        const v = num(usage, k);
        if (v !== undefined) uCalls.push({ method: m, arg: `${v}` });
      }
      const usageKeys = usageFields.map(([k]) => k as string);
      for (const k of Object.keys(usage).filter((k) => !usageKeys.includes(k))) unmapped.push(`completion.usage.${k}`);
      cCalls.push({ method: 'withUsage', arg: { factory: 'usage()', calls: uCalls } });
      cHandled.add('usage');
    }
    const streaming = bool(completion, 'streaming');
    if (streaming !== undefined) { cCalls.push({ method: 'withStreaming', arg: `${streaming}` }); cHandled.add('streaming'); }
    const sp = asJsonObject(completion['streamingPhysics']);
    if (sp) {
      imports.add('import static org.mockserver.model.StreamingPhysics.streamingPhysics;');
      const spCalls: LlmJavaCall[] = [];
      const spHandled = new Set<string>();
      const ttft = asJsonObject(sp['timeToFirstToken']);
      if (ttft && num(ttft, 'value') !== undefined) {
        imports.add('import org.mockserver.model.Delay;');
        imports.add('import java.util.concurrent.TimeUnit;');
        const rawUnit = str(ttft, 'timeUnit');
        const unit = rawUnit && JAVA_TIME_UNITS.has(rawUnit) ? rawUnit : 'MILLISECONDS';
        spCalls.push({ method: 'withTimeToFirstToken', arg: `new Delay(TimeUnit.${unit}, ${num(ttft, 'value')})` });
        spHandled.add('timeToFirstToken');
      }
      const tps = num(sp, 'tokensPerSecond');
      if (tps !== undefined) { spCalls.push({ method: 'withTokensPerSecond', arg: `${tps}` }); spHandled.add('tokensPerSecond'); }
      const jitter = num(sp, 'jitter');
      if (jitter !== undefined) { spCalls.push({ method: 'withJitter', arg: javaDoubleLiteral(jitter) }); spHandled.add('jitter'); }
      const seed = num(sp, 'seed');
      if (seed !== undefined) { spCalls.push({ method: 'withSeed', arg: `${seed}L` }); spHandled.add('seed'); }
      const subword = bool(sp, 'subwordStreaming');
      if (subword !== undefined) { spCalls.push({ method: 'withSubwordStreaming', arg: `${subword}` }); spHandled.add('subwordStreaming'); }
      for (const k of Object.keys(sp).filter((k) => !spHandled.has(k))) unmapped.push(`completion.streamingPhysics.${k}`);
      cCalls.push({ method: 'withStreamingPhysics', arg: { factory: 'streamingPhysics()', calls: spCalls } });
      cHandled.add('streamingPhysics');
    }
    const outputSchema = str(completion, 'outputSchema');
    if (outputSchema !== undefined) { cCalls.push({ method: 'withOutputSchema', arg: `"${escapeJava(outputSchema)}"` }); cHandled.add('outputSchema'); }
    const enforce = bool(completion, 'enforceOutputSchema');
    if (enforce !== undefined) { cCalls.push({ method: 'withEnforceOutputSchema', arg: `${enforce}` }); cHandled.add('enforceOutputSchema'); }
    const cModel = str(completion, 'model');
    if (cModel !== undefined) { cCalls.push({ method: 'withModel', arg: `"${escapeJava(cModel)}"` }); cHandled.add('model'); }
    const toolChoice = str(completion, 'toolChoice');
    if (toolChoice !== undefined) { cCalls.push({ method: 'withToolChoice', arg: `"${escapeJava(toolChoice)}"` }); cHandled.add('toolChoice'); }
    const reasoningText = str(completion, 'reasoningText');
    if (reasoningText !== undefined) { cCalls.push({ method: 'withReasoningText', arg: `"${escapeJava(reasoningText)}"` }); cHandled.add('reasoningText'); }
    const reasoningSignature = str(completion, 'reasoningSignature');
    if (reasoningSignature !== undefined) { cCalls.push({ method: 'withReasoningSignature', arg: `"${escapeJava(reasoningSignature)}"` }); cHandled.add('reasoningSignature'); }
    for (const k of Object.keys(completion).filter((k) => !cHandled.has(k))) unmapped.push(`completion.${k}`);
    calls.push({ method: 'withCompletion', arg: { factory: 'completion()', calls: cCalls } });
    handled.add('completion');
  }

  // conversationPredicates (+ normalization)
  const cp = asJsonObject(llm['conversationPredicates']);
  if (cp) {
    imports.add('import static org.mockserver.model.ConversationPredicates.conversationPredicates;');
    const cpCalls: LlmJavaCall[] = [];
    const cpHandled = new Set<string>();
    const turnIndex = num(cp, 'turnIndex');
    if (turnIndex !== undefined) { cpCalls.push({ method: 'withTurnIndex', arg: `${turnIndex}` }); cpHandled.add('turnIndex'); }
    for (const [k, m] of [
      ['latestMessageContains', 'withLatestMessageContains'],
      ['latestMessageMatches', 'withLatestMessageMatches'],
      ['containsToolResultFor', 'withContainsToolResultFor'],
      ['semanticMatchAgainst', 'withSemanticMatchAgainst'],
    ] as const) {
      const v = str(cp, k);
      if (v !== undefined) { cpCalls.push({ method: m, arg: `"${escapeJava(v)}"` }); cpHandled.add(k); }
    }
    const role = str(cp, 'latestMessageRole');
    if (role !== undefined) {
      imports.add('import org.mockserver.llm.ParsedMessage;');
      cpCalls.push({ method: 'withLatestMessageRole', arg: `ParsedMessage.Role.${role}` });
      cpHandled.add('latestMessageRole');
    }
    const norm = asJsonObject(cp['normalization']);
    if (norm) {
      imports.add('import static org.mockserver.model.NormalizationOptions.normalizationOptions;');
      const nCalls: LlmJavaCall[] = [];
      const nHandled = new Set<string>();
      for (const [k, m] of [
        ['collapseWhitespace', 'withCollapseWhitespace'], ['lowercase', 'withLowercase'],
        ['sortJsonKeys', 'withSortJsonKeys'], ['dropBuiltInVolatileFields', 'withDropBuiltInVolatileFields'],
      ] as const) {
        const v = bool(norm, k);
        if (v !== undefined) { nCalls.push({ method: m, arg: `${v}` }); nHandled.add(k); }
      }
      const dropFields = norm['dropVolatileFields'];
      if (Array.isArray(dropFields)) {
        imports.add('import java.util.Arrays;');
        const items = dropFields.filter((x) => typeof x === 'string').map((x) => `"${escapeJava(x as string)}"`).join(', ');
        nCalls.push({ method: 'withDropVolatileFields', arg: `Arrays.asList(${items})` });
        nHandled.add('dropVolatileFields');
      }
      for (const k of Object.keys(norm).filter((k) => !nHandled.has(k))) unmapped.push(`conversationPredicates.normalization.${k}`);
      cpCalls.push({ method: 'withNormalization', arg: { factory: 'normalizationOptions()', calls: nCalls } });
      cpHandled.add('normalization');
    }
    for (const k of Object.keys(cp).filter((k) => !cpHandled.has(k))) unmapped.push(`conversationPredicates.${k}`);
    calls.push({ method: 'withConversationPredicates', arg: { factory: 'conversationPredicates()', calls: cpCalls } });
    handled.add('conversationPredicates');
  }

  // embedding / rerank
  const emb = asJsonObject(llm['embedding']);
  if (emb) {
    imports.add('import static org.mockserver.model.EmbeddingResponse.embedding;');
    const eCalls: LlmJavaCall[] = [];
    const dims = num(emb, 'dimensions');
    if (dims !== undefined) eCalls.push({ method: 'withDimensions', arg: `${dims}` });
    const det = bool(emb, 'deterministicFromInput');
    if (det !== undefined) eCalls.push({ method: 'withDeterministicFromInput', arg: `${det}` });
    const seed = num(emb, 'seed');
    if (seed !== undefined) eCalls.push({ method: 'withSeed', arg: `${seed}L` });
    for (const k of Object.keys(emb).filter((k) => !['dimensions', 'deterministicFromInput', 'seed'].includes(k))) unmapped.push(`embedding.${k}`);
    calls.push({ method: 'withEmbedding', arg: { factory: 'embedding()', calls: eCalls } });
    handled.add('embedding');
  }
  const rer = asJsonObject(llm['rerank']);
  if (rer) {
    imports.add('import static org.mockserver.model.RerankResponse.rerank;');
    const rCalls: LlmJavaCall[] = [];
    const topN = num(rer, 'topN');
    if (topN !== undefined) rCalls.push({ method: 'withTopN', arg: `${topN}` });
    const det = bool(rer, 'deterministicFromInput');
    if (det !== undefined) rCalls.push({ method: 'withDeterministicFromInput', arg: `${det}` });
    const seed = num(rer, 'seed');
    if (seed !== undefined) rCalls.push({ method: 'withSeed', arg: `${seed}L` });
    for (const k of Object.keys(rer).filter((k) => !['topN', 'deterministicFromInput', 'seed'].includes(k))) unmapped.push(`rerank.${k}`);
    calls.push({ method: 'withRerank', arg: { factory: 'rerank()', calls: rCalls } });
    handled.add('rerank');
  }

  // moderation
  const mod = asJsonObject(llm['moderation']);
  if (mod) {
    imports.add('import static org.mockserver.model.ModerationResponse.moderationResponse;');
    const mCalls: LlmJavaCall[] = [];
    const flagged = mod['flaggedCategories'];
    if (Array.isArray(flagged)) {
      for (const cat of flagged) if (typeof cat === 'string') mCalls.push({ method: 'withFlaggedCategory', arg: `"${escapeJava(cat)}"` });
    }
    const mModel = str(mod, 'model');
    if (mModel !== undefined) mCalls.push({ method: 'withModel', arg: `"${escapeJava(mModel)}"` });
    for (const k of Object.keys(mod).filter((k) => !['flaggedCategories', 'model'].includes(k))) unmapped.push(`moderation.${k}`);
    calls.push({ method: 'withModeration', arg: { factory: 'moderationResponse()', calls: mCalls } });
    handled.add('moderation');
  }

  // contentFilter
  const cf = asJsonObject(llm['contentFilter']);
  if (cf) {
    imports.add('import static org.mockserver.model.LlmContentFilter.llmContentFilter;');
    const cfCalls: LlmJavaCall[] = [];
    for (const [k, m] of [['hate', 'withHate'], ['sexual', 'withSexual'], ['violence', 'withViolence'], ['selfHarm', 'withSelfHarm']] as const) {
      const v = str(cf, k);
      if (v !== undefined) cfCalls.push({ method: m, arg: `"${escapeJava(v)}"` });
    }
    for (const k of Object.keys(cf).filter((k) => !['hate', 'sexual', 'violence', 'selfHarm'].includes(k))) unmapped.push(`contentFilter.${k}`);
    calls.push({ method: 'withContentFilter', arg: { factory: 'llmContentFilter()', calls: cfCalls } });
    handled.add('contentFilter');
  }

  // chaos
  const chaos = asJsonObject(llm['chaos']);
  if (chaos) {
    imports.add('import static org.mockserver.model.LlmChaosProfile.llmChaosProfile;');
    const chCalls: LlmJavaCall[] = [];
    const chHandled = new Set<string>();
    const intField = (k: string, m: string) => { const v = num(chaos, k); if (v !== undefined) { chCalls.push({ method: m, arg: `${v}` }); chHandled.add(k); } };
    const longField = (k: string, m: string) => { const v = num(chaos, k); if (v !== undefined) { chCalls.push({ method: m, arg: `${v}L` }); chHandled.add(k); } };
    const dblField = (k: string, m: string) => { const v = num(chaos, k); if (v !== undefined) { chCalls.push({ method: m, arg: javaDoubleLiteral(v) }); chHandled.add(k); } };
    const strField = (k: string, m: string) => { const v = str(chaos, k); if (v !== undefined) { chCalls.push({ method: m, arg: `"${escapeJava(v)}"` }); chHandled.add(k); } };
    const boolField = (k: string, m: string) => { const v = bool(chaos, k); if (v !== undefined) { chCalls.push({ method: m, arg: `${v}` }); chHandled.add(k); } };
    intField('errorStatus', 'withErrorStatus');
    strField('retryAfter', 'withRetryAfter');
    dblField('errorProbability', 'withErrorProbability');
    const truncateMode = str(chaos, 'truncateMode');
    if (truncateMode !== undefined) {
      imports.add('import org.mockserver.model.LlmChaosProfile;');
      chCalls.push({ method: 'withTruncateMode', arg: `LlmChaosProfile.TruncateMode.${truncateMode}` });
      chHandled.add('truncateMode');
    }
    dblField('truncateAtFraction', 'withTruncateAtFraction');
    boolField('malformedSse', 'withMalformedSse');
    longField('seed', 'withSeed');
    strField('quotaName', 'withQuotaName');
    intField('quotaLimit', 'withQuotaLimit');
    longField('quotaWindowMillis', 'withQuotaWindowMillis');
    intField('quotaErrorStatus', 'withQuotaErrorStatus');
    longField('tokenQuotaLimit', 'withTokenQuotaLimit');
    longField('tokenQuotaWindowMillis', 'withTokenQuotaWindowMillis');
    strField('errorKind', 'withErrorKind');
    dblField('contentFilterBlockProbability', 'withContentFilterBlockProbability');
    for (const k of Object.keys(chaos).filter((k) => !chHandled.has(k))) unmapped.push(`chaos.${k}`);
    calls.push({ method: 'withChaos', arg: { factory: 'llmChaosProfile()', calls: chCalls } });
    handled.add('chaos');
  }

  // delay (Action base) — .withDelay(TimeUnit, long)
  const delay = asJsonObject(llm['delay']);
  if (delay && num(delay, 'value') !== undefined) {
    imports.add('import java.util.concurrent.TimeUnit;');
    const rawUnit = str(delay, 'timeUnit');
    const unit = rawUnit && JAVA_TIME_UNITS.has(rawUnit) ? rawUnit : 'SECONDS';
    calls.push({ method: 'withDelay', arg: `TimeUnit.${unit}, ${num(delay, 'value')}` });
    handled.add('delay');
  }

  // primary (Action base)
  if (llm['primary'] === true) { calls.push({ method: 'withPrimary', arg: 'true' }); handled.add('primary'); }

  for (const k of Object.keys(llm).filter((k) => !handled.has(k))) unmapped.push(k);

  const chain: LlmJavaChain = { factory: 'llmResponse()', calls };
  const code = ['.respondWithLlm(', ...renderLlmChain(chain, '    '), ')'].join('\n');
  const note = unmapped.length > 0
    ? `// NOTE: the Java builder preview omits httpLlmResponse field(s) it cannot carry: ${unmapped.join(', ')} -- see the JSON tab.`
    : undefined;
  return { code, imports: Array.from(imports), note };
}

/**
 * Compute the exact set of imports the generated Java snippet needs, based on the matcher
 * and action actually emitted. Returned sorted (static imports first) and de-duplicated so
 * the snippet compiles as a standalone paste.
 */
function collectJavaImports(
  matcher: StandardMatcher,
  action: StandardActionPayload,
  hasChaos: boolean,
  json: Record<string, unknown>,
  /**
   * When set, the emitted terminal action is a preserved `httpLlmResponse`
   * (see {@link llmResponseToJava}) rather than the form's modeled `action`.
   * These imports replace the `switch (action.type)` action-family imports.
   */
  llmActionImports?: string[],
  /** Imports the emitted action code needs beyond the form-driven ones. */
  actionImports: ReadonlySet<string> = new Set(),
): string[] {
  const imp = new Set<string>(actionImports);
  const isDns = !!(matcher.dns && matcher.dns.dnsName.trim());

  // Request matcher
  if (isDns) {
    imp.add('import static org.mockserver.model.DnsRequestDefinition.dnsRequest;');
    if (matcher.dns!.dnsType) imp.add('import org.mockserver.model.DnsRecordType;');
    if (matcher.dns!.dnsClass) imp.add('import org.mockserver.model.DnsRecordClass;');
  } else {
    imp.add('import static org.mockserver.model.HttpRequest.request;');
    if (matcher.body.trim()) {
      if (matcher.bodyMatcherType === 'binary' || matcher.bodyBinary) {
        imp.add('import static org.mockserver.model.BinaryBody.binary;');
        imp.add('import java.util.Base64;');
      } else if (matcher.bodyMatcherType === 'graphql') {
        imp.add('import static org.mockserver.model.GraphQLBody.graphQL;');
        if (matcher.graphqlOptions && matcher.graphqlOptions.selectionSetMatchType !== 'NORMALISED_STRING') {
          imp.add('import org.mockserver.model.SelectionSetMatchType;');
        }
      } else if (matcher.bodyMatcherType === 'json-schema') {
        imp.add('import static org.mockserver.model.JsonSchemaBody.jsonSchema;');
      } else if (matcher.bodyMatcherType === 'json-path') {
        imp.add('import static org.mockserver.model.JsonPathBody.jsonPath;');
      } else if (matcher.bodyMatcherType === 'xml') {
        imp.add('import static org.mockserver.model.XmlBody.xml;');
      } else if (matcher.bodyMatcherType === 'xml-schema') {
        imp.add('import static org.mockserver.model.XmlSchemaBody.xmlSchema;');
      } else if (matcher.bodyMatcherType === 'xpath') {
        imp.add('import static org.mockserver.model.XPathBody.xpath;');
      } else if (matcher.bodyMatcherType === 'regex') {
        imp.add('import static org.mockserver.model.RegexBody.regex;');
      } else if (matcher.bodyMatcherType === 'parameters') {
        imp.add('import static org.mockserver.model.ParameterBody.params;');
        imp.add('import static org.mockserver.model.Parameter.param;');
      } else if (matcher.bodyMatcherType === 'json') {
        imp.add('import static org.mockserver.model.JsonBody.json;');
        if (matcher.jsonMatchType === 'STRICT') {
          imp.add('import org.mockserver.matchers.MatchType;');
        }
      } else if (matcher.bodyMatcherType === 'wasm') {
        imp.add('import org.mockserver.model.WasmBody;');
      } else if (matcher.bodySubString) {
        imp.add('import static org.mockserver.model.StringBody.subString;');
      }
    }
    // allOf composite body — the AllOfBody factory plus each sub-matcher's factory.
    if (matcher.bodyMatcherType === 'allOf') {
      const entries = allOfEntries(matcher);
      if (entries.length > 0) {
        imp.add('import static org.mockserver.model.AllOfBody.allOf;');
        for (const e of entries) {
          switch (e.type) {
            case 'json': imp.add('import static org.mockserver.model.JsonBody.json;'); break;
            case 'json-schema': imp.add('import static org.mockserver.model.JsonSchemaBody.jsonSchema;'); break;
            case 'json-path': imp.add('import static org.mockserver.model.JsonPathBody.jsonPath;'); break;
            case 'xml': imp.add('import static org.mockserver.model.XmlBody.xml;'); break;
            case 'xml-schema': imp.add('import static org.mockserver.model.XmlSchemaBody.xmlSchema;'); break;
            case 'xpath': imp.add('import static org.mockserver.model.XPathBody.xpath;'); break;
            case 'regex': imp.add('import static org.mockserver.model.RegexBody.regex;'); break;
            case 'string': imp.add('import static org.mockserver.model.StringBody.exact;'); break;
          }
        }
      }
    }
    // JWT request-matcher criteria.
    if (buildJwtJson(matcher.jwt)) {
      imp.add('import static org.mockserver.model.Jwt.jwt;');
    }
  }

  // Action — a preserved httpLlmResponse carries its own imports; otherwise the
  // form's modeled action drives the action-family imports.
  if (llmActionImports) {
    for (const i of llmActionImports) imp.add(i);
  } else
  switch (action.type) {
    case 'static':
      imp.add('import static org.mockserver.model.HttpResponse.response;');
      if (action.static?.bodyFromFile && action.static.filePath?.trim()) {
        imp.add('import static org.mockserver.model.FileBody.file;');
        if (action.static.contentType) imp.add('import org.mockserver.model.MediaType;');
        if (action.static.fileTemplateType) imp.add('import org.mockserver.model.HttpTemplate.TemplateType;');
      }
      if (buildConnectionOptionsJson(action.static?.connectionOptions)) {
        imp.add('import static org.mockserver.model.ConnectionOptions.connectionOptions;');
      }
      if (action.static?.delayValue != null && action.static.delayValue > 0) {
        imp.add('import java.util.concurrent.TimeUnit;');
      }
      break;
    case 'forward':
      imp.add('import static org.mockserver.model.HttpForward.forward;');
      imp.add('import org.mockserver.model.HttpForward.Scheme;');
      break;
    case 'forward_override':
      imp.add('import static org.mockserver.model.HttpOverrideForwardedRequest.forwardOverriddenRequest;');
      imp.add('import static org.mockserver.model.HttpRequest.request;');
      break;
    case 'forward_fallback':
      imp.add('import static org.mockserver.model.HttpForwardWithFallback.forwardWithFallback;');
      imp.add('import static org.mockserver.model.HttpForward.forward;');
      imp.add('import org.mockserver.model.HttpForward.Scheme;');
      imp.add('import static org.mockserver.model.HttpResponse.response;');
      break;
    case 'callback':
    case 'forward_class_callback':
      imp.add('import static org.mockserver.model.HttpClassCallback.callback;');
      break;
    case 'template':
    case 'forward_template':
      imp.add('import static org.mockserver.model.HttpTemplate.template;');
      imp.add('import org.mockserver.model.HttpTemplate.TemplateType;');
      break;
    case 'error':
      imp.add('import static org.mockserver.model.HttpError.error;');
      if (action.error?.responseBytesB64.trim()) imp.add('import java.util.Base64;');
      if (action.error?.delayValue) {
        imp.add('import org.mockserver.model.Delay;');
        imp.add('import java.util.concurrent.TimeUnit;');
      }
      break;
    case 'websocket':
      imp.add('import static org.mockserver.model.HttpWebSocketResponse.webSocketResponse;');
      imp.add('import static org.mockserver.model.WebSocketMessage.webSocketMessage;');
      if (action.websocket && action.websocket.matchers.length > 0) {
        imp.add('import static org.mockserver.model.WebSocketMessageMatcher.webSocketMessageMatcher;');
        imp.add('import org.mockserver.model.WebSocketFrameType;');
        if (action.websocket.matchers.some((m) => m.textMatcher.trim())) {
          imp.add('import static org.mockserver.model.NottableString.string;');
        }
      }
      break;
    case 'sse':
      imp.add('import static org.mockserver.model.HttpSseResponse.sseResponse;');
      imp.add('import static org.mockserver.model.SseEvent.sseEvent;');
      break;
    case 'binary_response':
      imp.add('import static org.mockserver.model.BinaryResponse.binaryResponse;');
      if (action.binaryResponse?.binaryData.trim()) imp.add('import java.util.Base64;');
      if (action.binaryResponse?.upstream) imp.add('import org.mockserver.model.BinaryResponse.Upstream;');
      break;
    case 'dns_response':
      imp.add('import static org.mockserver.model.DnsResponse.dnsResponse;');
      imp.add('import org.mockserver.model.DnsResponseCode;');
      if (action.dnsResponse) {
        const recs = parseDnsRecords(action.dnsResponse.answerRecords);
        if (recs.length > 0) {
          imp.add('import static org.mockserver.model.DnsRecord.dnsRecord;');
          if (recs.some((r) => r.type)) imp.add('import org.mockserver.model.DnsRecordType;');
          if (recs.some((r) => r.dnsClass)) imp.add('import org.mockserver.model.DnsRecordClass;');
        }
      }
      break;
    case 'grpc_stream':
      imp.add('import static org.mockserver.model.GrpcStreamResponse.grpcStreamResponse;');
      break;
  }

  // Chaos
  if (hasChaos) {
    imp.add('import static org.mockserver.model.HttpChaosProfile.httpChaosProfile;');
    if (action.chaos?.latencyValue != null && action.chaos.latencyValue > 0) {
      imp.add('import org.mockserver.model.Delay;');
      imp.add('import java.util.concurrent.TimeUnit;');
    }
  }

  // Side-effect (before / after) actions — webhook (httpRequest) target only this increment
  const sideEffects = (action.sideEffects ?? []).filter(isSideEffectComplete);
  const beforeActions = sideEffects.filter((se) => se.position === 'before');
  const afterActions = sideEffects.filter((se) => se.position === 'after');
  if (sideEffects.some((se) => !se.templateType)) imp.add('import static org.mockserver.model.HttpRequest.request;');
  if (sideEffects.some((se) => se.templateType)) {
    imp.add('import static org.mockserver.model.HttpTemplate.template;');
    imp.add('import org.mockserver.model.HttpTemplate.TemplateType;');
  }
  if (beforeActions.length > 0) imp.add('import static org.mockserver.model.AfterAction.beforeAction;');
  if (afterActions.length > 0) imp.add('import static org.mockserver.model.AfterAction.afterAction;');
  if (beforeActions.some((se) => se.failurePolicy === 'FAIL_FAST')) {
    imp.add('import org.mockserver.model.FailurePolicy;');
  }
  if (sideEffects.some((se) => se.delayValue > 0) || beforeActions.some((se) => se.timeoutValue > 0)) {
    imp.add('import org.mockserver.model.Delay;');
    imp.add('import java.util.concurrent.TimeUnit;');
  }

  // Steps pipeline (M1 increment-2) — the steps Java codegen uses
  // buildExpectationJson → standardToJson, so the Java snippet just shows
  // a comment directing the user to the JSON tab; the steps builder API is
  // complex and the Java client doesn't have a fluent steps builder yet.
  // We still add the step() static import for the generated comment block.
  if (action.steps && action.steps.length > 0) {
    imp.add('import static org.mockserver.model.ExpectationStep.step;');
    imp.add('import org.mockserver.model.ExpectationStep;');
    // Import per-step action target factories
    for (const step of action.steps) {
      switch (step.actionType) {
        case 'httpResponse': imp.add('import static org.mockserver.model.HttpResponse.response;'); break;
        case 'httpForward': imp.add('import static org.mockserver.model.HttpForward.forward;'); break;
        case 'httpOverrideForwardedRequest': imp.add('import static org.mockserver.model.HttpOverrideForwardedRequest.forwardOverriddenRequest;'); break;
        case 'httpError': imp.add('import static org.mockserver.model.HttpError.error;'); break;
        case 'httpRequest': imp.add('import static org.mockserver.model.HttpRequest.request;'); break;
        case 'httpClassCallback': imp.add('import static org.mockserver.model.HttpClassCallback.callback;'); break;
      }
      if (!step.responder) {
        if (step.delayValue > 0 || step.timeoutValue > 0) {
          imp.add('import org.mockserver.model.Delay;');
          imp.add('import java.util.concurrent.TimeUnit;');
        }
        if (step.failurePolicy === 'FAIL_FAST') {
          imp.add('import org.mockserver.model.FailurePolicy;');
        }
      }
    }
  }

  // Cross-cutting expectation modifiers driven by the built JSON.
  // 4-arg when(request, Times, TimeToLive, priority) when any is non-default.
  const when = whenArgsFromJson(json);
  if (when.useFullWhen) {
    imp.add('import org.mockserver.matchers.Times;');
    imp.add('import org.mockserver.matchers.TimeToLive;');
    if (when.ttlExpr.includes('TimeUnit.')) imp.add('import java.util.concurrent.TimeUnit;');
  }
  // Capture rules → .withCapture(capture(CaptureRule.Source.X, "...", "..."))
  if (Array.isArray(json['capture']) && (json['capture'] as unknown[]).length > 0) {
    imp.add('import static org.mockserver.model.CaptureRule.capture;');
    imp.add('import org.mockserver.model.CaptureRule;');
  }
  // Preserved response SEQUENCE → .respond(Arrays.asList(response()..., ...)) with
  // the selection controls (responseMode / responseWeights / switchAfter).
  if (Array.isArray(json['httpResponses'])) {
    imp.add('import static org.mockserver.model.HttpResponse.response;');
    imp.add('import java.util.Arrays;');
    if ((json['httpResponses'] as unknown[]).some((r) => {
      const d = (r as Record<string, unknown> | null)?.['delay'];
      return d && typeof d === 'object';
    })) {
      imp.add('import java.util.concurrent.TimeUnit;');
    }
  }
  if (typeof json['responseMode'] === 'string') imp.add('import org.mockserver.mock.ResponseMode;');
  if (Array.isArray(json['responseWeights']) && (json['responseWeights'] as unknown[]).length > 0) {
    imp.add('import java.util.Arrays;');
  }
  // Cross-protocol scenarios → .withCrossProtocolScenario(crossProtocolScenario()...)
  if (Array.isArray(json['crossProtocolScenarios']) && (json['crossProtocolScenarios'] as unknown[]).length > 0) {
    imp.add('import static org.mockserver.model.CrossProtocolScenario.crossProtocolScenario;');
    if ((json['crossProtocolScenarios'] as unknown[]).some((c) => typeof (c as Record<string, unknown>)?.['trigger'] === 'string')) {
      imp.add('import org.mockserver.model.CrossProtocolTrigger;');
    }
  }

  const all = Array.from(imp);
  const statics = all.filter((i) => i.startsWith('import static ')).sort();
  const plains = all.filter((i) => !i.startsWith('import static ')).sort();
  return [...statics, ...plains];
}

function sideEffectToJava(se: StandardSideEffectAction): string {
  const isBefore = se.position === 'before';
  const factoryMethod = isBefore ? 'beforeAction' : 'afterAction';
  const lines: string[] = [];
  lines.push(`${factoryMethod}()`);
  if (se.templateType) {
    const file = se.preserved?.httpRequestTemplate?.['templateFile'];
    const expr = templateJavaExpr(se.templateType, se.template ?? '', typeof file === 'string' ? file : undefined);
    lines.push(`        .withHttpRequestTemplate(${expr.split('\n').join('\n    ')})`);
  } else {
    lines.push(`        .withHttpRequest(`);
    lines.push(`            request()`);
    if (se.method.trim()) lines.push(`                .withMethod("${escapeJava(se.method.trim())}")`);
    lines.push(`                .withPath("${escapeJava(se.path)}")`);
    if (se.host.trim()) lines.push(`                .withHeader("Host", "${escapeJava(se.host.trim())}")`);
    if (se.body.trim()) lines.push(`                .withBody("${escapeJava(se.body.trim())}")`);
    lines.push('        )');
  }
  if (se.delayValue > 0) {
    lines.push(`        .withDelay(new Delay(TimeUnit.${se.delayUnit}, ${se.delayValue}))`);
  }
  if (isBefore) {
    if (!se.blocking) lines.push('        .withBlocking(false)');
    if (se.timeoutValue > 0) {
      lines.push(`        .withTimeout(new Delay(TimeUnit.${se.timeoutUnit}, ${se.timeoutValue}))`);
    }
    if (se.failurePolicy === 'FAIL_FAST') lines.push('        .withFailurePolicy(FailurePolicy.FAIL_FAST)');
  }
  return lines.join('\n');
}

function stepToJava(step: StandardExpectationStep): string {
  const lines: string[] = ['step()'];
  // Action target — for the Java preview, use a comment with the JSON body
  // since each action type has its own builder and the free-text body may
  // not be trivially representable as builder calls.
  switch (step.actionType) {
    case 'httpResponse': lines.push('        .withHttpResponse(response())'); break;
    case 'httpForward': lines.push('        .withHttpForward(forward())'); break;
    case 'httpOverrideForwardedRequest': lines.push('        .withHttpOverrideForwardedRequest(forwardOverriddenRequest(request()))'); break;
    case 'httpError': lines.push('        .withHttpError(error())'); break;
    case 'httpRequest': lines.push('        .withHttpRequest(request())'); break;
    case 'httpClassCallback': lines.push('        .withHttpClassCallback(callback())'); break;
  }
  if (step.responder) {
    lines.push('        .withResponder(true)');
  }
  if (!step.responder) {
    if (!step.blocking) lines.push('        .withBlocking(false)');
    if (step.delayValue > 0) {
      lines.push(`        .withDelay(new Delay(TimeUnit.${step.delayUnit}, ${step.delayValue}))`);
    }
    if (step.timeoutValue > 0) {
      lines.push(`        .withTimeout(new Delay(TimeUnit.${step.timeoutUnit}, ${step.timeoutValue}))`);
    }
    if (step.failurePolicy === 'FAIL_FAST') lines.push('        .withFailurePolicy(FailurePolicy.FAIL_FAST)');
  }
  return lines.join('\n');
}

/**
 * Preserved action-family keys the Java builder tab CAN represent faithfully from
 * the built JSON, even though the standard composer form cannot model them:
 * `httpLlmResponse` is transpiled into the fully-typed `llmResponse()` builder
 * chain by {@link llmResponseToJava}. These must be excluded from the
 * unrepresentable-fallback test so the Java tab emits real code rather than a
 * whole-action notice.
 */
const JAVA_REPRESENTABLE_PRESERVED_KEYS: ReadonlySet<string> = new Set([
  'httpLlmResponse', 'httpResponses', 'responseMode', 'responseWeights', 'switchAfter',
]);

/**
 * When editing an expectation whose ORIGINAL action the form could not model
 * (e.g. an `httpResponses` sequence, an `*ObjectCallback`),
 * {@link buildExpectationJson} PRESERVES that original action verbatim, but the
 * fluent Java builder preview builds from the form's action model — which is the
 * form's default, NOT the preserved action. Emitting that snippet would show Java
 * that does something DIFFERENT from the registered expectation.
 *
 * Returns the preserved action-family key name (so the caller can name it in an
 * honest notice) when the Java preview cannot faithfully represent the action;
 * otherwise undefined. Signal: `editOriginal` present AND the form did NOT model
 * the action (`editActionModeled === false`, the same flag the merge keys on),
 * AND the original actually carries an action-family key to preserve that the Java
 * tab cannot transpile ({@link JAVA_REPRESENTABLE_PRESERVED_KEYS} are excluded —
 * `httpLlmResponse` is emitted as a real builder chain). The JSON / curl /
 * client-library tabs render the merged JSON and stay faithful regardless.
 */
export function unrepresentableJavaActionKey(action: StandardActionPayload): string | undefined {
  if (!action.editOriginal || action.editActionModeled !== false) return undefined;
  return ACTION_FAMILY_KEYS.find((k) => k in action.editOriginal! && !JAVA_REPRESENTABLE_PRESERVED_KEYS.has(k));
}

export function standardToJava(matcher: StandardMatcher, action: StandardActionPayload): string {
  const unrepresentable = unrepresentableJavaActionKey(action);
  if (unrepresentable) {
    return [
      `// This expectation uses ${unrepresentable}, which the Java builder API`,
      '// preview cannot represent. Use the JSON or curl tab — the registered',
      '// JSON shown there is the exact, faithful payload that will be sent.',
    ].join('\n');
  }

  const hasChaos = !!(action.chaos && buildChaosJson(action.chaos));
  const hasSteps = !!(action.steps && action.steps.length > 0);
  const sideEffects = (action.sideEffects ?? []).filter(isSideEffectComplete);
  const beforeActions = sideEffects.filter((se) => se.position === 'before');
  const afterActions = sideEffects.filter((se) => se.position === 'after');
  // Build the exact expectation JSON once and drive the cross-cutting modifiers
  // (times / timeToLive / priority via the 4-arg when(...), plus the namespace,
  // scenario and capture setters) from it, so the Java tab faithfully mirrors the
  // JSON tab — including fields carried through an edit overlay.
  const json = buildExpectationJson(matcher, action);
  // A preserved httpLlmResponse action (carried verbatim through an edit overlay
  // because the standard composer form cannot model it) is transpiled into the
  // type-safe llmResponse() builder chain rather than the form's default action.
  const preservedLlm = asJsonObject(json['httpLlmResponse']);
  const llmEmit = preservedLlm ? llmResponseToJava(preservedLlm) : undefined;
  // A preserved response SEQUENCE (httpResponses) is emitted as the typed
  // terminal `.respond(Arrays.asList(response()..., ...))`; the selection
  // controls (responseMode / responseWeights / switchAfter) ride on the chain
  // modifiers above.
  const preservedSequence = Array.isArray(json['httpResponses']) ? (json['httpResponses'] as unknown[]) : undefined;
  const sequenceNotes = new Set<string>();
  // Emit the terminal action. actionToJava (or llmResponseToJava for a preserved
  // httpLlmResponse) bundles the call (.respond(/.forward(/.respondWithLlm(...) with
  // its argument indented 4 spaces; dedent the inner argument lines by 2 so that, after
  // the 2-space wrapper that nests the call under mockServerClient, the argument aligns at
  // the same depth (4 spaces) as the matcher inside .when( ... ) — keeping request() and
  // response()/llmResponse() flush.
  const wireAction: JavaWireAction = {
    wire: asJsonObject(json[ACTION_WIRE_KEY[action.type]]) ?? {},
    imports: new Set(),
    omitted: new Set(),
  };
  let terminalCode: string;
  if (llmEmit) {
    terminalCode = llmEmit.code;
  } else if (preservedSequence) {
    const responses = preservedSequence
      .map((r) => wireResponseToJava((r ?? {}) as Record<string, unknown>, sequenceNotes))
      .map((r) => '    ' + r.split('\n').join('\n    '));
    terminalCode = ['.respond(Arrays.asList(', responses.join(',\n'), '))'].join('\n');
  } else {
    terminalCode = actionToJava(action, wireAction);
  }
  const lines: string[] = [];
  const matcherExtras: JavaMatcherExtras = { imports: wireAction.imports, omitted: [] };
  const wireRequest = json['httpRequest'];
  const matcherJava = '    ' + (isPlainObject(wireRequest) && Array.isArray(wireRequest['anyOf'])
    ? anyOfRequestToJava(wireRequest, matcherExtras)
    : matcherToJava(matcher, matcherExtras)).split('\n').join('\n    ');
  const matcherNote = matcherExtras.omitted.length > 0
    ? `// NOTE: the Java preview omits matcher value(s) the builder cannot express: ${matcherExtras.omitted.join(', ')} -- see the JSON tab.`
    : undefined;
  for (const imp of collectJavaImports(matcher, action, hasChaos, json, llmEmit?.imports, wireAction.imports)) lines.push(imp);
  lines.push('');
  lines.push('mockServerClient');
  const when = whenArgsFromJson(json);
  if (when.useFullWhen) {
    lines.push('  .when(');
    lines.push(matcherJava + ',');
    lines.push('    ' + when.timesExpr + ',');
    lines.push('    ' + when.ttlExpr + ',');
    lines.push('    ' + when.priority);
    lines.push('  )');
  } else {
    lines.push('  .when(');
    lines.push(matcherJava);
    lines.push('  )');
  }
  // Namespace / scenario / capture modifiers sit on the ForwardChainExpectation
  // returned by when(...), before the terminal action.
  for (const modifier of chainModifierLines(json)) lines.push(modifier);

  if (hasSteps) {
    // Steps pipeline — emit .withSteps(step().with...(), step().with...(), ...)
    lines.push('  .withSteps(');
    const stepSnippets = action.steps!.map((s) => '    ' + stepToJava(s).split('\n').join('\n    '));
    lines.push(stepSnippets.join(',\n'));
    lines.push('  );');
    lines.push('');
    lines.push('// NOTE: the step action payloads are simplified in the Java preview.');
    lines.push('// See the JSON tab for the full step bodies.');
    if (matcherNote) lines.push(matcherNote);
    return lines.join('\n');
  }

  // Chaos is fluent on the ForwardChainExpectation returned by when(...), so it must come
  // BEFORE the terminal action (respond/forward/error), which returns Expectation[].
  if (hasChaos) {
    lines.push('  ' + chaosToJava(action.chaos!).split('\n').join('\n  '));
  }
  // before/after actions are also fluent on ForwardChainExpectation -> before the terminal action
  for (const se of beforeActions) {
    lines.push('  .withBeforeAction(');
    lines.push('    ' + sideEffectToJava(se).split('\n').join('\n    '));
    lines.push('  )');
  }
  for (const se of afterActions) {
    lines.push('  .withAfterAction(');
    lines.push('    ' + sideEffectToJava(se).split('\n').join('\n    '));
    lines.push('  )');
  }
  const actionLines = terminalCode.split('\n');
  const alignedAction = actionLines
    .map((ln, i) => (i === 0 || i === actionLines.length - 1 ? ln : ln.replace(/^ {2}/, '')))
    .map((ln) => '  ' + ln)
    .join('\n');
  lines.push(alignedAction + ';');
  // Honest one-line notice naming any preserved LLM field the typed builder cannot
  // carry (normally none — every wire field has a setter).
  if (llmEmit?.note) lines.push(llmEmit.note);
  if (sequenceNotes.size > 0) {
    lines.push(`// NOTE: the Java preview omits response-sequence field(s) it cannot carry: ${Array.from(sequenceNotes).join(', ')} -- see the JSON tab.`);
  }
  // Fields kept through an edit that the builder above renders from form state, not the saved JSON.
  const wireRendered = new Set(['delay', 'primary', ...(JAVA_WIRE_RENDERED_FIELDS[action.type] ?? [])]);
  const keptOmitted = keptActionFields(matcher, action)
    .filter((f) => !wireRendered.has(String(f.path[0])))
    .map((f) => keptFieldLabel(f));
  const omitted = [...keptOmitted, ...wireAction.omitted];
  if (omitted.length > 0 && !llmEmit && !preservedSequence) {
    const actionKey = ACTION_WIRE_KEY[action.type];
    lines.push(`// NOTE: the Java preview omits field(s) kept from the loaded expectation: ${omitted.map((f) => `${actionKey}.${f}`).join(', ')} -- see the JSON tab.`);
  }
  // Honest NOTE for preserved top-level fields the Java client API cannot set
  // (rateLimit / timestamp) — never a silent drop.
  const unrep = JAVA_UNREPRESENTABLE_TOPLEVEL.filter((k) => k in json && json[k] != null);
  if (unrep.length > 0) {
    lines.push(`// NOTE: the Java client API cannot set these preserved field(s): ${unrep.join(', ')} -- see the JSON tab.`);
  }
  if (matcherNote) lines.push(matcherNote);
  return lines.join('\n');
}

// ---------------------------------------------------------------------------
// curl codegen — equivalent shell command for registering the expectation
// ---------------------------------------------------------------------------

export function standardToCurl(
  matcher: StandardMatcher,
  action: StandardActionPayload,
  baseUrl: string,
): string {
  const json = JSON.stringify(buildExpectationJson(matcher, action));
  // Single-quote the JSON payload; escape any embedded single quotes for shell.
  const safe = json.replace(/'/g, `'\\''`);
  return `curl -X PUT '${baseUrl}/mockserver/expectation' \\\n  -H 'Content-Type: application/json' \\\n  -d '${safe}'`;
}

// ---------------------------------------------------------------------------
// Client-library codegen — Node / Python / Go / C# / Ruby / Rust
//
// Every MockServer client ultimately registers the same expectation JSON that
// the JSON tab shows. Rather than reimplement each language's fluent builder
// matrix, these generators hydrate the expectation from that JSON via each
// client's native facility and register it through the native client.
//
// Fidelity note: the Node client is JSON-native (mockAnyResponse takes the raw
// object), so it represents EVERY field faithfully regardless of client version.
// Python/Go/C#/Ruby/Rust hydrate into typed model objects, so a field the
// installed client version's model does not yet declare (e.g. a newly added one)
// is dropped on hydration — the JSON tab remains the authoritative, lossless
// source.
//
// The emitters and their shared helpers live in per-language modules under
// ./codegen/; they are re-exported here under their original names so every
// existing import site (and test) that pulls them from './standardCodegen.ts' keeps
// working unchanged.
// ---------------------------------------------------------------------------

export { clientHostPort, indentAfterFirst, toPythonLiteral, rustRawString, rustClientVersionRequirement } from './codegen/shared.ts';
export { standardToNode } from './codegen/node.ts';
export { standardToPython } from './codegen/python.ts';
export { standardToGo } from './codegen/go.ts';
export { standardToCsharp } from './codegen/csharp.ts';
export { standardToRuby } from './codegen/ruby.ts';
export { standardToRust } from './codegen/rust.ts';
