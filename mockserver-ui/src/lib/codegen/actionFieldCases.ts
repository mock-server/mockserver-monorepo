/**
 * One edit per action type whose loaded action carries every field the server schema
 * allows, so the Python, Ruby and Rust code tabs are checked against all of them: each
 * field is either rendered through the client's model or named in a NOTE.
 */
import type { StandardActionPayload } from '../standardCodegen.ts';
import { keptFieldsEdit, type Combo } from './extractParityCases.ts';

type Wire = Record<string, unknown>;

const DELAY = { timeUnit: 'MILLISECONDS', value: 4101, distribution: { type: 'UNIFORM', min: 4102, max: 4103 } };
const TEMPLATED_DELAY = { timeUnit: 'SECONDS', value: 4104, template: '$!request.path.length()', templateType: 'VELOCITY' };
const MESSAGE_DELAY = { timeUnit: 'MILLISECONDS', value: 4105 };

/** Every field of each action, keyed by its wire key. */
export const FULL_ACTIONS: Record<string, Wire> = {
  httpResponse: {
    statusCode: 299,
    reasonPhrase: 'Full Reason',
    headers: { 'x-full': ['header-value'] },
    cookies: { full_cookie: 'cookie-value' },
    body: { type: 'STRING', string: 'full body', contentType: 'text/plain; charset=utf-8' },
    delay: DELAY,
    connectionOptions: {
      keepAliveOverride: true,
      closeSocket: true,
      closeSocketDelay: { timeUnit: 'MILLISECONDS', value: 4106 },
      contentLengthHeaderOverride: 4107,
      suppressContentLengthHeader: true,
      suppressConnectionHeader: true,
      chunkSize: 4108,
      chunkDelay: { timeUnit: 'MILLISECONDS', value: 4109 },
    },
    primary: true,
    trailers: { 'x-trailer': ['trailer-value'] },
    statusCodeRange: '2xx',
    generateFromSchema: '{"type":"string"}',
    recoverAfter: { failTimes: 2, idempotencyHeader: 'X-Idempotency' },
  },
  httpForward: { host: 'full.example.com', port: 4201, scheme: 'HTTPS', delay: TEMPLATED_DELAY, primary: true },
  httpOverrideForwardedRequest: {
    requestOverride: {
      method: 'PATCH',
      path: '/full-override',
      headers: { 'x-override': ['override-value'] },
      keepAlive: true,
      socketAddress: { host: 'socket.example.com', port: 4301, scheme: 'HTTPS' },
    },
    responseOverride: { statusCode: 298, headers: { 'x-response-override': ['response-value'] } },
    requestModifier: { path: { regex: '^/full', substitution: '/rewritten' }, headers: { remove: ['x-request-gone'] } },
    responseModifier: { headers: { remove: ['x-response-gone'] } },
    responseTemplate: { templateType: 'MUSTACHE', template: '{{ request.path }}' },
    delay: DELAY,
    primary: true,
  },
  httpResponseClassCallback: { callbackClass: 'com.example.FullCallback', delay: DELAY, primary: true },
  httpResponseTemplate: {
    templateType: 'VELOCITY',
    template: '$!request.path',
    delay: DELAY,
    primary: true,
    responseOverride: { statusCode: 297 },
    responseModifier: { headers: { remove: ['x-template-gone'] } },
    parameters: { greeting: 'hello', names: ['ann', 'bob'], limits: { max: 5, none: null } },
  },
  httpError: { dropConnection: false, resetConnection: true, responseBytes: 'AQID', streamError: 8, delay: DELAY, primary: true },
  httpForwardWithFallback: {
    httpForward: { host: 'fallback.example.com', port: 4401, scheme: 'HTTP', primary: false },
    fallbackResponse: { statusCode: 503, body: 'fallback body', trailers: { 'x-fallback': ['fallback-trailer'] } },
    fallbackOnStatusCodes: [500, 502],
    fallbackOnTimeout: false,
    delay: DELAY,
    primary: true,
  },
  httpWebSocketResponse: {
    subprotocol: 'full-protocol',
    messages: [{ text: 'full text', delay: MESSAGE_DELAY }, { binary: 'AAEC', delay: MESSAGE_DELAY }],
    matchers: [{ frameType: 'TEXT', textMatcher: 'ping', responses: [{ text: 'pong' }, { binary: 'AwQF' }] }],
    closeConnection: false,
    templateType: 'MUSTACHE',
    graphqlSubscriptionFilter: {
      type: 'GRAPHQL',
      query: 'subscription { full }',
      operationName: 'Full',
      variablesSchema: '{"type":"object"}',
      selectionSetMatchType: 'AST_SUBSET',
      fields: ['full'],
    },
    delay: DELAY,
    primary: true,
  },
  httpSseResponse: {
    statusCode: 200,
    headers: { 'x-sse': ['sse-value'] },
    events: [{ event: 'full-event', data: 'full data', id: 'full-id', retry: 4501, comment: 'full comment', delay: MESSAGE_DELAY }],
    closeConnection: false,
    closeDelay: { timeUnit: 'MILLISECONDS', value: 4502 },
    keepStreamOpen: true,
    templateType: 'MUSTACHE',
    delay: DELAY,
    primary: true,
  },
  binaryResponse: { binaryData: 'AQIDBA==', upstream: 'FORWARD_AND_REPLACE', delay: DELAY, primary: true },
  dnsResponse: {
    responseCode: 'NOERROR',
    answerRecords: [{ name: 'answer.example.com', type: 'A', dnsClass: 'IN', ttl: 4601, value: '10.0.0.1' }],
    authorityRecords: [{ name: 'example.com', type: 'NS', ttl: 4602, value: 'ns1.example.com' }],
    additionalRecords: [{ name: 'srv.example.com', type: 'SRV', ttl: 4603, value: 'target.example.com', priority: 1, weight: 2, port: 4604 }],
    delay: DELAY,
    primary: true,
  },
  httpForwardTemplate: {
    templateType: 'MUSTACHE',
    templateFile: 'templates/full.mustache',
    delay: TEMPLATED_DELAY,
    primary: true,
    parameters: { target: 'upstream' },
  },
  httpForwardClassCallback: { callbackClass: 'com.example.FullForward', delay: TEMPLATED_DELAY, primary: true },
  grpcStreamResponse: {
    statusName: 'OK',
    statusMessage: 'full status',
    headers: { 'x-grpc': ['grpc-value'] },
    messages: [{ json: '{"full":1}', templateType: 'MUSTACHE', delay: MESSAGE_DELAY }],
    closeConnection: true,
    delay: DELAY,
    primary: true,
  },
};

/** The form each action loads into: the edit baseline its full original replaces. */
const FORMS: Record<string, StandardActionPayload> = {
  httpResponse: { type: 'static', static: { statusCode: 200, body: 'ok', contentType: '', bodyFromFile: false, filePath: '', fileTemplateType: '' } },
  httpForward: { type: 'forward', forward: { scheme: 'HTTP', host: 'up.example.com', port: 8080 } },
  httpOverrideForwardedRequest: {
    type: 'forward_override',
    forwardOverride: { overrideMethod: '', overrideHost: '', overrideScheme: '', overridePath: '/v2', overrideQueryString: '', overrideHeaders: '', overrideBody: '' },
  },
  httpResponseClassCallback: { type: 'callback', callback: { callbackClass: 'com.example.MyCallback' } },
  httpResponseTemplate: { type: 'template', template: { templateType: 'MUSTACHE', template: '{"a":1}' } },
  httpError: { type: 'error', error: { dropConnection: true, responseBytesB64: '', delayValue: 0, delayUnit: 'MILLISECONDS' } },
  httpForwardWithFallback: {
    type: 'forward_fallback',
    forwardFallback: { scheme: 'HTTP', host: 'up.example.com', port: 80, fallbackStatusCode: 503, fallbackBody: '', fallbackOnStatusCodes: '', fallbackOnTimeout: true },
  },
  httpWebSocketResponse: {
    type: 'websocket',
    websocket: { subprotocol: '', messages: 'hello', closeConnection: true, matchers: [{ frameType: 'TEXT', textMatcher: 'ping', responses: 'pong' }] },
  },
  httpSseResponse: { type: 'sse', sse: { statusCode: 200, headers: '', events: [{ event: 'tick', data: 'd', id: '', retry: '' }], closeConnection: true } },
  binaryResponse: { type: 'binary_response', binaryResponse: { binaryData: 'SGk=' } },
  dnsResponse: { type: 'dns_response', dnsResponse: { responseCode: 'NOERROR', answerRecords: '' } },
  httpForwardTemplate: { type: 'forward_template', forwardTemplate: { templateType: 'VELOCITY', template: 'x' } },
  httpForwardClassCallback: { type: 'forward_class_callback', forwardClassCallback: { callbackClass: 'com.example.Forward' } },
  grpcStreamResponse: { type: 'grpc_stream', grpcStream: { statusName: 'OK', statusMessage: '', headers: '', messages: '{"a":1}', closeConnection: false } },
};

export const actionFieldCombos: Combo[] = Object.entries(FULL_ACTIONS).map(([key, full]) =>
  keptFieldsEdit(`all-fields-${key}`, FORMS[key]!, key, () => structuredClone(full)),
);
