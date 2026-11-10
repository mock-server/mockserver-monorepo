/**
 * Ruby client-library emitter.
 *
 * Emits idiomatic, TYPED construction against the hand-written `mockserver-client`
 * gem (mockserver-client-ruby/lib/mockserver): a `MockServer::Expectation` built
 * from keyword-argument constructors (`MockServer::HttpRequest.new(...)`,
 * `MockServer::HttpResponse.new(...)`, `MockServer::Body`/`Jwt`/`Delay`/
 * `KeyToMultiValue`/`SseEvent`/`WebSocketFrameMatcher`/`DnsRecord`/… ), registered
 * with the client's canonical `client.upsert(expectation)` idiom (the same call
 * the fluent `client.when(...).respond(...)` DSL funnels into — see
 * ForwardChainExpectation#respond → @client.upsert).
 *
 * The emitter walks the exact wire payload produced by {@link buildExpectationJson}
 * (so it can never drift from what the JSON tab / the PUT request send) and maps
 * each wire object onto the gem model class that serialises back to it. The result
 * is semantically equivalent to the wire JSON: `expectation.to_h` reproduces
 * `buildExpectationJson(...)` up to the keyToMultiValue encoding the gem models use
 * for headers / query parameters (the gem emits the `[{name, values}]` array form;
 * the composer emits the `{name: [values]}` object form — MockServer accepts both,
 * see MockServer.serialize_key_multi_values_object).
 *
 * MODEL GAP: the composer's `httpOverrideForwardedRequest` uses a `requestOverride`
 * sub-object, but the gem's HttpOverrideForwardedRequest model serialises to
 * `httpRequest`/`requestModifier` instead and cannot carry `requestOverride`. That
 * one slot is therefore emitted as a raw Ruby Hash (which the model's `to_h`
 * passes through unchanged), preserving the exact wire shape until the gem grows a
 * typed field for it.
 */
import { buildExpectationJson, type StandardMatcher, type StandardActionPayload } from '../standardCodegen.ts';
import { clientHostPort, OmittedFields } from './shared.ts';
import { dateTimeBuilderExpr, dateTimeMatcherOf } from '../matcherValue.ts';

type Json = unknown;
type Obj = Record<string, Json>;

/** What the snippet being rendered leaves out; {@link standardToRuby} starts each snippet afresh. */
let omitted = new OmittedFields();

/** Records each key of `w` outside `keys`, so the snippet names it rather than drop it. */
function known(at: string, w: Obj, keys: readonly string[]): void {
  omitted.unknown(at, w, keys);
}

// ---------------------------------------------------------------------------
// Low-level Ruby literal helpers
// ---------------------------------------------------------------------------

/** A Ruby double-quoted string literal for an arbitrary JS string. Escapes the
 *  backslash / quote / control chars, and neutralises `#{`/`#@`/`#$` so the
 *  literal can never trigger Ruby string interpolation. */
function rb(s: string): string {
  const escaped = s
    .replace(/\\/g, '\\\\')
    .replace(/"/g, '\\"')
    .replace(/\n/g, '\\n')
    .replace(/\r/g, '\\r')
    .replace(/\t/g, '\\t')
    .replace(/#([{@$])/g, '\\#$1')
    // a raw ^D or ^Z ends a Ruby script, and other control characters are unreadable raw
    .replace(/[\p{Cc}]/gu, (c) => (c.charCodeAt(0) < 0x80 ? '\\x' + c.charCodeAt(0).toString(16).padStart(2, '0') : c));
  return `"${escaped}"`;
}

/** A Ruby scalar literal (string / number / boolean / nil) for a wire scalar. */
function scalar(v: Json): string {
  if (v === null || v === undefined) return 'nil';
  if (typeof v === 'boolean') return v ? 'true' : 'false';
  if (typeof v === 'number') return String(v);
  return rb(String(v));
}

/** A Ruby literal for an ARBITRARY JSON value (hash → `{ "k" => v }`, array →
 *  `[ v ]`, scalar → {@link scalar}). Used for opaque payloads the gem stores
 *  verbatim (a JSON body's `json:` value, a PARAMETERS map, the requestOverride
 *  raw-hash fallback). `indent` is the column of this value's CLOSING bracket. */
function rbValue(v: Json, indent: number): string {
  const pad = ' '.repeat(indent);
  const pad2 = ' '.repeat(indent + 2);
  if (v === null || v === undefined) return 'nil';
  if (typeof v === 'boolean') return v ? 'true' : 'false';
  if (typeof v === 'number') return String(v);
  if (typeof v === 'string') return rb(v);
  if (Array.isArray(v)) {
    if (v.length === 0) return '[]';
    return '[\n' + v.map((e) => pad2 + rbValue(e, indent + 2)).join(',\n') + '\n' + pad + ']';
  }
  const entries = Object.entries(v as Obj);
  if (entries.length === 0) return '{}';
  return '{\n' + entries.map(([k, val]) => pad2 + rb(k) + ' => ' + rbValue(val, indent + 2)).join(',\n') + '\n' + pad + '}';
}

// ---------------------------------------------------------------------------
// Constructor / array assembly. `indent` is always the column at which THIS
// node's closing token (`)` / `]`) sits — i.e. the column of the keyword-arg
// name that holds it in the parent; children are rendered at `indent + 2`.
// ---------------------------------------------------------------------------

interface Arg { name: string; value: string; comment?: string; }

/** `MockServer::<Class>.new(\n  arg: value,\n  ...\n)`; bare `.new` when empty.
 *  An arg's optional `comment` is emitted as a Ruby `#` line above it. */
function ctor(className: string, args: Arg[], indent: number): string {
  if (args.length === 0) return `MockServer::${className}.new`;
  const pad = ' '.repeat(indent);
  const pad2 = ' '.repeat(indent + 2);
  const body = args
    .map((a) => (a.comment ? `${pad2}# ${a.comment}\n` : '') + `${pad2}${a.name}: ${a.value}`)
    .join(',\n');
  return `MockServer::${className}.new(\n${body}\n${pad})`;
}

/** A multi-line Ruby array literal of already-rendered item expressions. */
function arrayOf(items: string[], indent: number): string {
  if (items.length === 0) return '[]';
  const pad = ' '.repeat(indent);
  const pad2 = ' '.repeat(indent + 2);
  return '[\n' + items.map((it) => pad2 + it).join(',\n') + '\n' + pad + ']';
}

// ---------------------------------------------------------------------------
// keyToMultiValue / cookie collections
// ---------------------------------------------------------------------------

/** A JSON value as a single-line Ruby literal. */
function rbInline(v: Json): string {
  if (v === null || v === undefined || typeof v !== 'object') return scalar(v);
  if (Array.isArray(v)) return '[' + v.map(rbInline).join(', ') + ']';
  return '{ ' + Object.entries(v as Obj).map(([k, val]) => `${rb(k)} => ${rbInline(val)}`).join(', ') + ' }';
}

/** One matcher value: a string literal, a `MockServer::DateTimeMatcher`, or any other object form
 *  (nottable / schema) as the Hash the gem sends verbatim. */
function matcherValue(v: Json): string {
  if (v === null || typeof v !== 'object') return rb(String(v));
  const dt = dateTimeMatcherOf(v);
  if (!dt) return rbInline(v);
  return dateTimeBuilderExpr(dt, { head: 'MockServer::DateTimeMatcher.', naming: 'snake', str: rb, bool: (b) => String(b) });
}

/** Object-form wire map ({name: [values]}) → `[MockServer::KeyToMultiValue.new(...)]`. */
function kmv(map: Obj, indent: number): string {
  const items = Object.entries(map).map(([name, values]) => {
    const vals = Array.isArray(values) ? values : [values];
    const valExpr = '[' + vals.map(matcherValue).join(', ') + ']';
    return `MockServer::KeyToMultiValue.new(name: ${rb(name)}, values: ${valExpr})`;
  });
  return arrayOf(items, indent);
}

/** Single-value cookie wire map ({name: value}) → `[MockServer::KeyToMultiValue.new(...)]`. */
function cookies(map: Obj, indent: number): string {
  const items = Object.entries(map).map(
    ([name, value]) => `MockServer::KeyToMultiValue.new(name: ${rb(name)}, values: [${matcherValue(value)}])`,
  );
  return arrayOf(items, indent);
}

// ---------------------------------------------------------------------------
// Small typed leaves
// ---------------------------------------------------------------------------

function delay(w: Obj, indent: number, at: string): string {
  known(at, w, ['timeUnit', 'value', 'distribution', 'template', 'templateType']);
  const args: Arg[] = [];
  if ('timeUnit' in w) args.push({ name: 'time_unit', value: rb(String(w['timeUnit'])) });
  if ('value' in w) args.push({ name: 'value', value: scalar(w['value']) });
  if ('distribution' in w) args.push({ name: 'distribution', value: delayDistribution(w['distribution'] as Obj, indent + 2, `${at}.distribution`) });
  if ('template' in w) args.push({ name: 'template', value: rb(String(w['template'])) });
  if ('templateType' in w) args.push({ name: 'template_type', value: rb(String(w['templateType'])) });
  return ctor('Delay', args, indent);
}

function delayDistribution(w: Obj, indent: number, at: string): string {
  const spec: [string, string][] = [
    ['type', 'type'], ['min', 'min'], ['max', 'max'], ['median', 'median'],
    ['p99', 'p99'], ['mean', 'mean'], ['stdDev', 'std_dev'],
  ];
  known(at, w, spec.map(([wk]) => wk));
  const args: Arg[] = [];
  for (const [wk, arg] of spec) if (wk in w) args.push({ name: arg, value: scalar(w[wk]) });
  return ctor('DelayDistribution', args, indent);
}

function connectionOptions(w: Obj, indent: number, at: string): string {
  const spec: [string, string][] = [
    ['keepAliveOverride', 'keep_alive_override'],
    ['closeSocket', 'close_socket'],
    ['closeSocketDelay', 'close_socket_delay'],
    ['contentLengthHeaderOverride', 'content_length_header_override'],
    ['suppressContentLengthHeader', 'suppress_content_length_header'],
    ['suppressConnectionHeader', 'suppress_connection_header'],
    ['chunkSize', 'chunk_size'],
    ['chunkDelay', 'chunk_delay'],
  ];
  known(at, w, spec.map(([wk]) => wk));
  const args: Arg[] = [];
  for (const [wk, arg] of spec) {
    if (!(wk in w)) continue;
    const isDelay = wk === 'closeSocketDelay' || wk === 'chunkDelay';
    args.push({ name: arg, value: isDelay ? delay(w[wk] as Obj, indent + 2, `${at}.${wk}`) : scalar(w[wk]) });
  }
  return ctor('ConnectionOptions', args, indent);
}

function jwt(w: Obj, indent: number): string {
  const spec: [string, string][] = [
    ['header', 'header'],
    ['scheme', 'scheme'],
    ['issuer', 'issuer'],
    ['audience', 'audience'],
    ['algorithm', 'algorithm'],
  ];
  const args: Arg[] = [];
  for (const [wk, arg] of spec) if (wk in w) args.push({ name: arg, value: scalar(w[wk]) });
  if ('claims' in w) args.push({ name: 'claims', value: rbValue(w['claims'], indent + 2) });
  return ctor('Jwt', args, indent);
}

// ---------------------------------------------------------------------------
// Body — string bodies stay bare strings; typed bodies map onto Body /
// GraphQLBody / JsonRpcBody; ALL_OF recurses.
// ---------------------------------------------------------------------------

/** Wire body key → { rubyArg, opaque } where opaque values (JSON / parameters)
 *  are rendered with {@link rbValue} rather than as scalars. */
const BODY_FIELDS: Record<string, { arg: string; opaque?: boolean }> = {
  string: { arg: 'string' },
  json: { arg: 'json', opaque: true },
  regex: { arg: 'regex' },
  jsonPath: { arg: 'json_path' },
  xpath: { arg: 'xpath' },
  xml: { arg: 'xml' },
  xmlSchema: { arg: 'xml_schema' },
  jsonSchema: { arg: 'json_schema' },
  base64Bytes: { arg: 'base64_bytes' },
  matchType: { arg: 'match_type' },
  matchNumbersAsStrings: { arg: 'match_numbers_as_strings' },
  subString: { arg: 'sub_string' },
  parameters: { arg: 'parameters', opaque: true },
  parameterStyles: { arg: 'parameter_styles', opaque: true },
  moduleName: { arg: 'module_name' },
  contentType: { arg: 'content_type' },
  filePath: { arg: 'file_path' },
  templateType: { arg: 'template_type' },
  templatedFilePath: { arg: 'templated_file_path' },
  charset: { arg: 'charset' },
  not: { arg: 'not_body' },
  optional: { arg: 'optional' },
};

function body(w: Json, indent: number, at: string): string {
  if (typeof w === 'string') return rb(w);
  if (w === null || typeof w !== 'object' || Array.isArray(w)) return rbValue(w, indent);
  const b = w as Obj;
  const type = b['type'];

  if (type === 'GRAPHQL') {
    known(at, b, ['type', 'query', 'operationName', 'variablesSchema', 'selectionSetMatchType', 'fields']);
    const args: Arg[] = [];
    if ('query' in b) args.push({ name: 'query', value: rb(String(b['query'])) });
    if ('operationName' in b) args.push({ name: 'operation_name', value: rb(String(b['operationName'])) });
    if ('variablesSchema' in b) args.push({ name: 'variables_schema', value: rbValue(b['variablesSchema'], indent + 2) });
    if ('selectionSetMatchType' in b) args.push({ name: 'selection_set_match_type', value: rb(String(b['selectionSetMatchType'])) });
    if ('fields' in b) args.push({ name: 'fields', value: rbValue(b['fields'], indent + 2) });
    return ctor('GraphQLBody', args, indent);
  }
  if (type === 'JSON_RPC') {
    known(at, b, ['type', 'method', 'paramsSchema']);
    const args: Arg[] = [];
    if ('method' in b) args.push({ name: 'method_name', value: rb(String(b['method'])) });
    if ('paramsSchema' in b) args.push({ name: 'params_schema', value: rbValue(b['paramsSchema'], indent + 2) });
    return ctor('JsonRpcBody', args, indent);
  }
  if (type === 'ALL_OF') {
    known(at, b, ['type', 'bodyAllOf']);
    const subs = ((b['bodyAllOf'] as Json[]) ?? []).map((sb, n) => body(sb, indent + 2, `${at}.bodyAllOf[${n}]`));
    return ctor('Body', [
      { name: 'type', value: rb('ALL_OF') },
      { name: 'body_all_of', value: arrayOf(subs, indent + 2) },
    ], indent);
  }

  const dateTimeBody = type === 'JSON_PATH' || type === 'XPATH';
  known(at, b, ['type', ...Object.keys(BODY_FIELDS), ...(dateTimeBody ? ['dateTime'] : [])]);
  const args: Arg[] = [{ name: 'type', value: rb(String(type)) }];
  for (const [wk, val] of Object.entries(b)) {
    if (wk === 'type') continue;
    if (wk === 'dateTime' && dateTimeBody) {
      args.push({ name: 'date_time', value: matcherValue(val) });
      continue;
    }
    const spec = BODY_FIELDS[wk];
    if (!spec) continue;
    args.push({ name: spec.arg, value: spec.opaque ? rbValue(val, indent + 2) : scalar(val) });
  }
  return ctor('Body', args, indent);
}

// ---------------------------------------------------------------------------
// Request / response
// ---------------------------------------------------------------------------

function httpRequest(w: Obj, indent: number, at: string): string {
  if (Array.isArray(w['anyOf'])) return anyOfRequest(w, indent, at);
  const args: Arg[] = [];
  const keys: string[] = [];
  const push = (wk: string, arg: string, render: (v: Json) => string) => {
    keys.push(wk);
    if (wk in w) args.push({ name: arg, value: render(w[wk]) });
  };
  push('method', 'method', (v) => rb(String(v)));
  push('path', 'path', (v) => rb(String(v)));
  push('headers', 'headers', (v) => kmv(v as Obj, indent + 2));
  push('queryStringParameters', 'query_string_parameters', (v) => kmv(v as Obj, indent + 2));
  push('cookies', 'cookies', (v) => cookies(v as Obj, indent + 2));
  push('pathParameters', 'path_parameters', (v) => kmv(v as Obj, indent + 2));
  push('body', 'body', (v) => body(v, indent + 2, `${at}.body`));
  push('secure', 'secure', (v) => scalar(v));
  push('protocol', 'protocol', (v) => rb(String(v)));
  push('keepAlive', 'keep_alive', (v) => scalar(v));
  push('respondBeforeBody', 'respond_before_body', (v) => scalar(v));
  push('clientIp', 'client_ip', (v) => rb(String(v)));
  push('jwt', 'jwt', (v) => jwt(v as Obj, indent + 2));
  push('dnsName', 'dns_name', (v) => rb(String(v)));
  push('dnsType', 'dns_type', (v) => rb(String(v)));
  push('dnsClass', 'dns_class', (v) => rb(String(v)));
  push('socketAddress', 'socket_address', (v) => socketAddress(v as Obj, indent + 2, `${at}.socketAddress`));
  known(at, w, keys);
  return ctor('HttpRequest', args, indent);
}

function anyOfRequest(w: Obj, indent: number, at: string): string {
  known(at, w, ['anyOf', 'not']);
  const branches = (w['anyOf'] as Json[]).map((b, n) => httpRequest(b as Obj, indent + 4, `${at}.anyOf[${n}]`));
  const args: Arg[] = [{ name: 'any_of', value: arrayOf(branches, indent + 2) }];
  if (typeof w['not'] === 'boolean') args.push({ name: 'not_any_of', value: scalar(w['not']) });
  return ctor('AnyOfRequestDefinition', args, indent);
}

function socketAddress(w: Obj, indent: number, at: string): string {
  known(at, w, ['host', 'port', 'scheme']);
  const args: Arg[] = [];
  for (const wk of ['host', 'port', 'scheme']) if (wk in w) args.push({ name: wk, value: scalar(w[wk]) });
  return ctor('SocketAddress', args, indent);
}

function httpResponse(className: 'HttpResponse', w: Obj, indent: number, at: string): string {
  const args: Arg[] = [];
  const keys: string[] = [];
  const push = (wk: string, arg: string, render: (v: Json) => string) => {
    keys.push(wk);
    if (wk in w) args.push({ name: arg, value: render(w[wk]) });
  };
  push('statusCode', 'status_code', (v) => scalar(v));
  push('reasonPhrase', 'reason_phrase', (v) => rb(String(v)));
  push('body', 'body', (v) => body(v, indent + 2, `${at}.body`));
  push('headers', 'headers', (v) => kmv(v as Obj, indent + 2));
  push('cookies', 'cookies', (v) => cookies(v as Obj, indent + 2));
  push('connectionOptions', 'connection_options', (v) => connectionOptions(v as Obj, indent + 2, `${at}.connectionOptions`));
  push('trailers', 'trailers', (v) => kmv(v as Obj, indent + 2));
  push('delay', 'delay', (v) => delay(v as Obj, indent + 2, `${at}.delay`));
  push('primary', 'primary', (v) => scalar(v));
  push('statusCodeRange', 'status_code_range', (v) => rb(String(v)));
  push('generateFromSchema', 'generate_from_schema', (v) => rb(String(v)));
  push('recoverAfter', 'recover_after', (v) => recoverAfter(v as Obj, indent + 2, `${at}.recoverAfter`));
  known(at, w, keys);
  return ctor(className, args, indent);
}

function recoverAfter(w: Obj, indent: number, at: string): string {
  known(at, w, ['failTimes', 'failResponse', 'idempotencyHeader']);
  const args: Arg[] = [];
  if ('failTimes' in w) args.push({ name: 'fail_times', value: scalar(w['failTimes']) });
  if ('failResponse' in w) args.push({ name: 'fail_response', value: httpResponse('HttpResponse', w['failResponse'] as Obj, indent + 2, `${at}.failResponse`) });
  if ('idempotencyHeader' in w) args.push({ name: 'idempotency_header', value: rb(String(w['idempotencyHeader'])) });
  return ctor('RecoverAfter', args, indent);
}

// ---------------------------------------------------------------------------
// Action renderers
// ---------------------------------------------------------------------------

/** The delay and primary every action inherits. */
function inherited(w: Obj, args: Arg[], indent: number, at: string): Arg[] {
  if ('delay' in w) args.push({ name: 'delay', value: delay(w['delay'] as Obj, indent + 2, `${at}.delay`) });
  if ('primary' in w) args.push({ name: 'primary', value: scalar(w['primary']) });
  return args;
}

function httpForward(w: Obj, indent: number, at: string): string {
  known(at, w, ['host', 'port', 'scheme', 'delay', 'primary']);
  const args: Arg[] = [];
  for (const [wk, arg] of [['host', 'host'], ['port', 'port'], ['scheme', 'scheme']] as [string, string][]) {
    if (wk in w) args.push({ name: arg, value: scalar(w[wk]) });
  }
  return ctor('HttpForward', inherited(w, args, indent, at), indent);
}

function httpTemplate(w: Obj, indent: number, at: string): string {
  known(at, w, ['templateType', 'template', 'templateFile', 'delay', 'primary', 'responseOverride', 'responseModifier', 'parameters']);
  const args: Arg[] = [];
  if ('templateType' in w) args.push({ name: 'template_type', value: rb(String(w['templateType'])) });
  if ('template' in w) args.push({ name: 'template', value: rb(String(w['template'])) });
  if ('templateFile' in w) args.push({ name: 'template_file', value: rb(String(w['templateFile'])) });
  if ('responseOverride' in w) {
    args.push({ name: 'response_override', value: httpResponse('HttpResponse', w['responseOverride'] as Obj, indent + 2, `${at}.responseOverride`) });
  }
  if ('responseModifier' in w) args.push({ name: 'response_modifier', value: rbValue(w['responseModifier'], indent + 2) });
  if ('parameters' in w) args.push({ name: 'parameters', value: rbValue(w['parameters'], indent + 2) });
  return ctor('HttpTemplate', inherited(w, args, indent, at), indent);
}

function httpClassCallback(w: Obj, indent: number, at: string): string {
  known(at, w, ['callbackClass', 'delay', 'primary']);
  const args: Arg[] = [];
  if ('callbackClass' in w) args.push({ name: 'callback_class', value: rb(String(w['callbackClass'])) });
  return ctor('HttpClassCallback', inherited(w, args, indent, at), indent);
}

function httpError(w: Obj, indent: number, at: string): string {
  known(at, w, ['dropConnection', 'resetConnection', 'responseBytes', 'streamError', 'delay', 'primary']);
  const args: Arg[] = [];
  if ('dropConnection' in w) args.push({ name: 'drop_connection', value: scalar(w['dropConnection']) });
  if ('resetConnection' in w) args.push({ name: 'reset_connection', value: scalar(w['resetConnection']) });
  if ('responseBytes' in w) args.push({ name: 'response_bytes', value: rb(String(w['responseBytes'])) });
  if ('streamError' in w) args.push({ name: 'stream_error', value: scalar(w['streamError']) });
  return ctor('HttpError', inherited(w, args, indent, at), indent);
}

function httpForwardWithFallback(w: Obj, indent: number, at: string): string {
  known(at, w, ['httpForward', 'fallbackResponse', 'fallbackOnStatusCodes', 'fallbackOnTimeout', 'delay', 'primary']);
  const args: Arg[] = [];
  if ('httpForward' in w) args.push({ name: 'http_forward', value: httpForward(w['httpForward'] as Obj, indent + 2, `${at}.httpForward`) });
  if ('fallbackResponse' in w) args.push({ name: 'fallback_response', value: httpResponse('HttpResponse', w['fallbackResponse'] as Obj, indent + 2, `${at}.fallbackResponse`) });
  if ('fallbackOnStatusCodes' in w) args.push({ name: 'fallback_on_status_codes', value: rbValue(w['fallbackOnStatusCodes'], indent + 2) });
  if ('fallbackOnTimeout' in w) args.push({ name: 'fallback_on_timeout', value: scalar(w['fallbackOnTimeout']) });
  return ctor('HttpForwardWithFallback', inherited(w, args, indent, at), indent);
}

/** The gem holds a binary message as raw bytes, which it base64-encodes back onto the wire. */
function webSocketMessage(w: Obj, indent: number, at: string): string {
  known(at, w, ['text', 'binary', 'delay']);
  const args: Arg[] = [];
  if ('text' in w) args.push({ name: 'text', value: rb(String(w['text'])) });
  if ('binary' in w) args.push({ name: 'binary', value: `Base64.strict_decode64(${rb(String(w['binary']))})` });
  if ('delay' in w) args.push({ name: 'delay', value: delay(w['delay'] as Obj, indent + 2, `${at}.delay`) });
  return ctor('WebSocketMessage', args, indent);
}

function httpWebSocketResponse(w: Obj, indent: number, at: string): string {
  known(at, w, ['subprotocol', 'messages', 'matchers', 'closeConnection', 'templateType', 'graphqlSubscriptionFilter', 'delay', 'primary']);
  const args: Arg[] = [];
  if ('subprotocol' in w) args.push({ name: 'subprotocol', value: rb(String(w['subprotocol'])) });
  if ('messages' in w) {
    const msgs = (w['messages'] as Json[]).map((m, n) => webSocketMessage(m as Obj, indent + 4, `${at}.messages[${n}]`));
    args.push({ name: 'messages', value: arrayOf(msgs, indent + 2) });
  }
  if ('matchers' in w) {
    const matchers = (w['matchers'] as Json[]).map((m, n) => {
      const mo = m as Obj;
      const mat = `${at}.matchers[${n}]`;
      known(mat, mo, ['frameType', 'textMatcher', 'responses']);
      const ma: Arg[] = [];
      if ('frameType' in mo) ma.push({ name: 'frame_type', value: rb(String(mo['frameType'])) });
      if ('textMatcher' in mo) ma.push({ name: 'text_matcher', value: rb(String(mo['textMatcher'])) });
      if ('responses' in mo) {
        const resps = (mo['responses'] as Json[]).map((r, k) => webSocketMessage(r as Obj, indent + 6, `${mat}.responses[${k}]`));
        ma.push({ name: 'responses', value: arrayOf(resps, indent + 4) });
      }
      return ctor('WebSocketFrameMatcher', ma, indent + 4);
    });
    args.push({ name: 'matchers', value: arrayOf(matchers, indent + 2) });
  }
  if ('closeConnection' in w) args.push({ name: 'close_connection', value: scalar(w['closeConnection']) });
  if ('templateType' in w) args.push({ name: 'template_type', value: rb(String(w['templateType'])) });
  if ('graphqlSubscriptionFilter' in w) {
    args.push({
      name: 'graphql_subscription_filter',
      value: graphqlSubscriptionFilter(w['graphqlSubscriptionFilter'] as Obj, indent + 2, `${at}.graphqlSubscriptionFilter`),
    });
  }
  return ctor('HttpWebSocketResponse', inherited(w, args, indent, at), indent);
}

function graphqlSubscriptionFilter(w: Obj, indent: number, at: string): string {
  const spec: [string, string][] = [
    ['type', 'type'], ['query', 'query'], ['operationName', 'operation_name'],
    ['variablesSchema', 'variables_schema'], ['selectionSetMatchType', 'selection_set_match_type'], ['fields', 'fields'],
  ];
  known(at, w, spec.map(([wk]) => wk));
  const args: Arg[] = [];
  for (const [wk, arg] of spec) if (wk in w) args.push({ name: arg, value: rbValue(w[wk], indent + 2) });
  return ctor('GraphQLSubscriptionFilter', args, indent);
}

function httpSseResponse(w: Obj, indent: number, at: string): string {
  known(at, w, ['statusCode', 'headers', 'events', 'closeConnection', 'closeDelay', 'keepStreamOpen', 'templateType', 'delay', 'primary']);
  const args: Arg[] = [];
  if ('statusCode' in w) args.push({ name: 'status_code', value: scalar(w['statusCode']) });
  if ('headers' in w) args.push({ name: 'headers', value: kmv(w['headers'] as Obj, indent + 2) });
  if ('events' in w) {
    const events = (w['events'] as Json[]).map((e, n) => {
      const eo = e as Obj;
      known(`${at}.events[${n}]`, eo, ['event', 'data', 'id', 'retry', 'comment', 'delay']);
      const ea: Arg[] = [];
      if ('event' in eo) ea.push({ name: 'event', value: rb(String(eo['event'])) });
      if ('data' in eo) ea.push({ name: 'data', value: rb(String(eo['data'])) });
      if ('id' in eo) ea.push({ name: 'id', value: rb(String(eo['id'])) });
      if ('retry' in eo) ea.push({ name: 'retry_ms', value: scalar(eo['retry']) });
      if ('comment' in eo) ea.push({ name: 'comment', value: rb(String(eo['comment'])) });
      if ('delay' in eo) ea.push({ name: 'delay', value: delay(eo['delay'] as Obj, indent + 6, `${at}.events[${n}].delay`) });
      return ctor('SseEvent', ea, indent + 4);
    });
    args.push({ name: 'events', value: arrayOf(events, indent + 2) });
  }
  if ('closeConnection' in w) args.push({ name: 'close_connection', value: scalar(w['closeConnection']) });
  if ('closeDelay' in w) args.push({ name: 'close_delay', value: delay(w['closeDelay'] as Obj, indent + 2, `${at}.closeDelay`) });
  if ('keepStreamOpen' in w) args.push({ name: 'keep_stream_open', value: scalar(w['keepStreamOpen']) });
  if ('templateType' in w) args.push({ name: 'template_type', value: rb(String(w['templateType'])) });
  return ctor('HttpSseResponse', inherited(w, args, indent, at), indent);
}

function binaryResponse(w: Obj, indent: number, at: string): string {
  known(at, w, ['binaryData', 'upstream', 'delay', 'primary']);
  const args: Arg[] = [];
  if ('binaryData' in w) args.push({ name: 'binary_data', value: rb(String(w['binaryData'])) });
  if (typeof w['upstream'] === 'string') args.push({ name: 'upstream', value: rb(w['upstream']) });
  return ctor('BinaryResponse', inherited(w, args, indent, at), indent);
}

const DNS_RECORD_FIELDS: [string, string][] = [
  ['name', 'name'], ['type', 'type'], ['dnsClass', 'dns_class'], ['ttl', 'ttl'],
  ['value', 'value'], ['priority', 'priority'], ['weight', 'weight'], ['port', 'port'],
];

function dnsResponse(w: Obj, indent: number, at: string): string {
  known(at, w, ['responseCode', 'answerRecords', 'authorityRecords', 'additionalRecords', 'delay', 'primary']);
  const args: Arg[] = [];
  if ('responseCode' in w) args.push({ name: 'response_code', value: rb(String(w['responseCode'])) });
  const records = (key: string) => (w[key] as Json[]).map((r, n) => {
    const ro = r as Obj;
    known(`${at}.${key}[${n}]`, ro, DNS_RECORD_FIELDS.map(([wk]) => wk));
    const ra: Arg[] = [];
    for (const [wk, arg] of DNS_RECORD_FIELDS) if (wk in ro) ra.push({ name: arg, value: scalar(ro[wk]) });
    return ctor('DnsRecord', ra, indent + 4);
  });
  if ('answerRecords' in w) args.push({ name: 'answer_records', value: arrayOf(records('answerRecords'), indent + 2) });
  if ('authorityRecords' in w) args.push({ name: 'authority_records', value: arrayOf(records('authorityRecords'), indent + 2) });
  if ('additionalRecords' in w) args.push({ name: 'additional_records', value: arrayOf(records('additionalRecords'), indent + 2) });
  return ctor('DnsResponse', inherited(w, args, indent, at), indent);
}

function grpcStreamResponse(w: Obj, indent: number, at: string): string {
  known(at, w, ['statusName', 'statusMessage', 'headers', 'messages', 'closeConnection', 'delay', 'primary']);
  const args: Arg[] = [];
  if ('statusName' in w) args.push({ name: 'status_name', value: rb(String(w['statusName'])) });
  if ('statusMessage' in w) args.push({ name: 'status_message', value: rb(String(w['statusMessage'])) });
  if ('headers' in w) args.push({ name: 'headers', value: kmv(w['headers'] as Obj, indent + 2) });
  if ('messages' in w) {
    const msgs = (w['messages'] as Json[]).map((m, n) => grpcStreamMessage(m as Obj, indent + 4, `${at}.messages[${n}]`));
    args.push({ name: 'messages', value: arrayOf(msgs, indent + 2) });
  }
  if ('closeConnection' in w) args.push({ name: 'close_connection', value: scalar(w['closeConnection']) });
  return ctor('GrpcStreamResponse', inherited(w, args, indent, at), indent);
}

/** camelCase wire key → snake_case attr, for the flat HttpChaosProfile fields. */
function camelToSnake(s: string): string {
  return s.replace(/([A-Z])/g, (_, c: string) => '_' + c.toLowerCase());
}

function httpChaosProfile(w: Obj, indent: number): string {
  const args: Arg[] = [];
  for (const [wk, val] of Object.entries(w)) {
    if (wk === 'latency' && val && typeof val === 'object') {
      args.push({ name: 'latency', value: delay(val as Obj, indent + 2, 'chaos.latency') });
    } else if (wk === 'slowResponseChunkDelay' && val && typeof val === 'object') {
      args.push({ name: 'slow_response_chunk_delay', value: delay(val as Obj, indent + 2, 'chaos.slowResponseChunkDelay') });
    } else {
      args.push({ name: camelToSnake(wk), value: scalar(val) });
    }
  }
  return ctor('HttpChaosProfile', args, indent);
}

function afterAction(w: Obj, indent: number, at: string): string {
  const args: Arg[] = [];
  if ('httpRequest' in w) args.push({ name: 'http_request', value: httpRequest(w['httpRequest'] as Obj, indent + 2, `${at}.httpRequest`) });
  if ('httpRequestTemplate' in w) args.push({ name: 'http_request_template', value: httpTemplate(w['httpRequestTemplate'] as Obj, indent + 2, `${at}.httpRequestTemplate`) });
  if ('httpClassCallback' in w) args.push({ name: 'http_class_callback', value: httpClassCallback(w['httpClassCallback'] as Obj, indent + 2, `${at}.httpClassCallback`) });
  if ('delay' in w) args.push({ name: 'delay', value: delay(w['delay'] as Obj, indent + 2, `${at}.delay`) });
  if ('blocking' in w) args.push({ name: 'blocking', value: scalar(w['blocking']) });
  if ('timeout' in w) args.push({ name: 'timeout', value: delay(w['timeout'] as Obj, indent + 2, `${at}.timeout`) });
  if ('failurePolicy' in w) args.push({ name: 'failure_policy', value: rb(String(w['failurePolicy'])) });
  return ctor('AfterAction', args, indent);
}

function expectationStep(w: Obj, indent: number, at: string): string {
  const args: Arg[] = [];
  if ('httpRequest' in w) args.push({ name: 'http_request', value: httpRequest(w['httpRequest'] as Obj, indent + 2, `${at}.httpRequest`) });
  if ('httpResponse' in w) args.push({ name: 'http_response', value: httpResponse('HttpResponse', w['httpResponse'] as Obj, indent + 2, `${at}.httpResponse`) });
  if ('httpForward' in w) args.push({ name: 'http_forward', value: httpForward(w['httpForward'] as Obj, indent + 2, `${at}.httpForward`) });
  // requestOverride model gap (see file header) — raw-hash passthrough.
  if ('httpOverrideForwardedRequest' in w) args.push({ name: 'http_override_forwarded_request', value: rbValue(w['httpOverrideForwardedRequest'], indent + 2) });
  if ('httpClassCallback' in w) args.push({ name: 'http_class_callback', value: httpClassCallback(w['httpClassCallback'] as Obj, indent + 2, `${at}.httpClassCallback`) });
  if ('httpError' in w) args.push({ name: 'http_error', value: httpError(w['httpError'] as Obj, indent + 2, `${at}.httpError`) });
  if ('responder' in w) args.push({ name: 'responder', value: scalar(w['responder']) });
  if ('delay' in w) args.push({ name: 'delay', value: delay(w['delay'] as Obj, indent + 2, `${at}.delay`) });
  if ('blocking' in w) args.push({ name: 'blocking', value: scalar(w['blocking']) });
  if ('timeout' in w) args.push({ name: 'timeout', value: delay(w['timeout'] as Obj, indent + 2, `${at}.timeout`) });
  if ('failurePolicy' in w) args.push({ name: 'failure_policy', value: rb(String(w['failurePolicy'])) });
  return ctor('ExpectationStep', args, indent);
}

function captureRule(w: Obj, indent: number): string {
  const args: Arg[] = [];
  if ('source' in w) args.push({ name: 'source', value: rb(String(w['source'])) });
  if ('expression' in w) args.push({ name: 'expression', value: rb(String(w['expression'])) });
  if ('into' in w) args.push({ name: 'into', value: rb(String(w['into'])) });
  return ctor('CaptureRule', args, indent);
}

function times(w: Obj, indent: number): string {
  const args: Arg[] = [];
  if ('remainingTimes' in w) args.push({ name: 'remaining_times', value: scalar(w['remainingTimes']) });
  if ('unlimited' in w) args.push({ name: 'unlimited', value: scalar(w['unlimited']) });
  return ctor('Times', args, indent);
}

function timeToLive(w: Obj, indent: number): string {
  const args: Arg[] = [];
  if ('timeUnit' in w) args.push({ name: 'time_unit', value: rb(String(w['timeUnit'])) });
  if ('timeToLive' in w) args.push({ name: 'time_to_live', value: scalar(w['timeToLive']) });
  if ('unlimited' in w) args.push({ name: 'unlimited', value: scalar(w['unlimited']) });
  return ctor('TimeToLive', args, indent);
}

// ---------------------------------------------------------------------------
// Edit-preserved actions/siblings — typed keyword-arg classes (the gem models
// every one except the LLM action, which it stores as a verbatim Hash by design
// — see llm() below).
// ---------------------------------------------------------------------------

function grpcStreamMessage(mo: Obj, indent: number, at: string): string {
  known(at, mo, ['json', 'templateType', 'delay']);
  const ma: Arg[] = [];
  if ('json' in mo) ma.push({ name: 'json', value: rb(String(mo['json'])) });
  if ('templateType' in mo) ma.push({ name: 'template_type', value: rb(String(mo['templateType'])) });
  if ('delay' in mo) ma.push({ name: 'delay', value: delay(mo['delay'] as Obj, indent + 2, `${at}.delay`) });
  return ctor('GrpcStreamMessage', ma, indent);
}

function grpcBidiResponse(w: Obj, indent: number, at: string): string {
  known(at, w, ['statusName', 'statusMessage', 'headers', 'messages', 'rules', 'closeConnection', 'delay', 'primary']);
  const args: Arg[] = [];
  if ('statusName' in w) args.push({ name: 'status_name', value: rb(String(w['statusName'])) });
  if ('statusMessage' in w) args.push({ name: 'status_message', value: rb(String(w['statusMessage'])) });
  if ('headers' in w) args.push({ name: 'headers', value: kmv(w['headers'] as Obj, indent + 2) });
  if ('messages' in w) {
    const msgs = (w['messages'] as Json[]).map((m, n) => grpcStreamMessage(m as Obj, indent + 4, `${at}.messages[${n}]`));
    args.push({ name: 'messages', value: arrayOf(msgs, indent + 2) });
  }
  if ('rules' in w) {
    const rules = (w['rules'] as Json[]).map((r, n) => {
      const ro = r as Obj;
      known(`${at}.rules[${n}]`, ro, ['matchJson', 'responses']);
      const ra: Arg[] = [];
      if ('matchJson' in ro) ra.push({ name: 'match_json', value: rb(String(ro['matchJson'])) });
      if ('responses' in ro) {
        const resps = (ro['responses'] as Json[]).map((rr, k) => grpcStreamMessage(rr as Obj, indent + 6, `${at}.rules[${n}].responses[${k}]`));
        ra.push({ name: 'responses', value: arrayOf(resps, indent + 4) });
      }
      return ctor('GrpcBidiRule', ra, indent + 4);
    });
    args.push({ name: 'rules', value: arrayOf(rules, indent + 2) });
  }
  if ('closeConnection' in w) args.push({ name: 'close_connection', value: scalar(w['closeConnection']) });
  return ctor('GrpcBidiResponse', inherited(w, args, indent, at), indent);
}

function httpObjectCallback(w: Obj, indent: number, at: string): string {
  known(at, w, ['clientId', 'responseCallback', 'delay', 'primary']);
  const args: Arg[] = [];
  if ('clientId' in w) args.push({ name: 'client_id', value: rb(String(w['clientId'])) });
  if ('responseCallback' in w) args.push({ name: 'response_callback', value: scalar(w['responseCallback']) });
  return ctor('HttpObjectCallback', inherited(w, args, indent, at), indent);
}

function httpForwardValidateAction(w: Obj, indent: number, at: string): string {
  const spec: [string, string, 'str' | 'scalar'][] = [
    ['specUrlOrPayload', 'spec_url_or_payload', 'str'], ['host', 'host', 'str'],
    ['port', 'port', 'scalar'], ['scheme', 'scheme', 'str'],
    ['validateRequest', 'validate_request', 'scalar'], ['validateResponse', 'validate_response', 'scalar'],
    ['validationMode', 'validation_mode', 'str'], ['primary', 'primary', 'scalar'],
  ];
  known(at, w, [...spec.map(([wk]) => wk), 'delay']);
  const args: Arg[] = [];
  for (const [wk, arg, kind] of spec) {
    if (wk in w) args.push({ name: arg, value: kind === 'str' ? rb(String(w[wk])) : scalar(w[wk]) });
  }
  if ('delay' in w) args.push({ name: 'delay', value: delay(w['delay'] as Obj, indent + 2, `${at}.delay`) });
  return ctor('HttpForwardValidateAction', args, indent);
}

function rateLimit(w: Obj, indent: number): string {
  const spec: [string, string, 'str' | 'scalar'][] = [
    ['name', 'name', 'str'], ['algorithm', 'algorithm', 'str'], ['limit', 'limit', 'scalar'],
    ['windowMillis', 'window_millis', 'scalar'], ['burst', 'burst', 'scalar'],
    ['refillPerSecond', 'refill_per_second', 'scalar'], ['errorStatus', 'error_status', 'scalar'],
    ['retryAfter', 'retry_after', 'str'],
  ];
  const args: Arg[] = [];
  for (const [wk, arg, kind] of spec) {
    if (wk in w) args.push({ name: arg, value: kind === 'str' ? rb(String(w[wk])) : scalar(w[wk]) });
  }
  return ctor('RateLimit', args, indent);
}

function crossProtocolScenario(w: Obj, indent: number): string {
  const spec: [string, string][] = [
    ['trigger', 'trigger'], ['matchPattern', 'match_pattern'],
    ['scenarioName', 'scenario_name'], ['targetState', 'target_state'],
  ];
  const args: Arg[] = [];
  for (const [wk, arg] of spec) if (wk in w) args.push({ name: arg, value: rb(String(w[wk])) });
  return ctor('CrossProtocolScenario', args, indent);
}

/**
 * The LLM action. The gem intentionally models `httpLlmResponse` as a verbatim
 * Ruby Hash (MockServer::LLM builders emit Hashes; Expectation#http_llm_response
 * stores and serialises it unchanged — see lib/mockserver/llm.rb). Its typed
 * builders cover only a subset of the wire fields (no rerank/moderation/
 * contentFilter, partial completion/usage), so a Hash literal — the gem's
 * canonical, lossless input — is the faithful representation. This is a native
 * typed Hash, NOT a JSON string / heredoc / JSON.parse blob.
 */
function llm(v: Json, indent: number): string {
  return rbValue(v, indent);
}

// ---------------------------------------------------------------------------
// Top-level Expectation assembly — wire key → { Expectation kwarg, renderer }.
// Rendered in this fixed order for a stable, readable snippet.
// ---------------------------------------------------------------------------

type Renderer = (v: Json, indent: number, at: string) => string;

const EXPECTATION_FIELDS: [string, string, Renderer][] = [
  ['httpRequest', 'http_request', (v, i, at) => httpRequest(v as Obj, i, at)],
  ['httpResponse', 'http_response', (v, i, at) => httpResponse('HttpResponse', v as Obj, i, at)],
  ['httpResponseTemplate', 'http_response_template', (v, i, at) => httpTemplate(v as Obj, i, at)],
  ['httpResponseClassCallback', 'http_response_class_callback', (v, i, at) => httpClassCallback(v as Obj, i, at)],
  ['httpForward', 'http_forward', (v, i, at) => httpForward(v as Obj, i, at)],
  ['httpForwardTemplate', 'http_forward_template', (v, i, at) => httpTemplate(v as Obj, i, at)],
  ['httpForwardClassCallback', 'http_forward_class_callback', (v, i, at) => httpClassCallback(v as Obj, i, at)],
  // requestOverride model gap (see file header) — raw-hash passthrough.
  ['httpOverrideForwardedRequest', 'http_override_forwarded_request', (v, i) => rbValue(v, i)],
  ['httpForwardWithFallback', 'http_forward_with_fallback', (v, i, at) => httpForwardWithFallback(v as Obj, i, at)],
  ['httpError', 'http_error', (v, i, at) => httpError(v as Obj, i, at)],
  ['httpWebSocketResponse', 'http_websocket_response', (v, i, at) => httpWebSocketResponse(v as Obj, i, at)],
  ['httpSseResponse', 'http_sse_response', (v, i, at) => httpSseResponse(v as Obj, i, at)],
  ['binaryResponse', 'binary_response', (v, i, at) => binaryResponse(v as Obj, i, at)],
  ['dnsResponse', 'dns_response', (v, i, at) => dnsResponse(v as Obj, i, at)],
  ['grpcStreamResponse', 'grpc_stream_response', (v, i, at) => grpcStreamResponse(v as Obj, i, at)],
  // Edit-preserved actions/siblings the standard composer form cannot model.
  ['httpResponses', 'http_responses', (v, i, at) => arrayOf((v as Json[]).map((r, n) => httpResponse('HttpResponse', r as Obj, i + 2, `${at}[${n}]`)), i)],
  ['httpResponseObjectCallback', 'http_response_object_callback', (v, i, at) => httpObjectCallback(v as Obj, i, at)],
  ['httpForwardObjectCallback', 'http_forward_object_callback', (v, i, at) => httpObjectCallback(v as Obj, i, at)],
  ['httpForwardValidateAction', 'http_forward_validate_action', (v, i, at) => httpForwardValidateAction(v as Obj, i, at)],
  ['grpcBidiResponse', 'grpc_bidi_response', (v, i, at) => grpcBidiResponse(v as Obj, i, at)],
  ['httpLlmResponse', 'http_llm_response', (v, i) => llm(v, i)],
  ['rateLimit', 'rate_limit', (v, i) => rateLimit(v as Obj, i)],
  ['crossProtocolScenarios', 'cross_protocol_scenarios', (v, i) => arrayOf((v as Json[]).map((c) => crossProtocolScenario(c as Obj, i + 2)), i)],
  ['responseMode', 'response_mode', (v) => rb(String(v))],
  ['responseWeights', 'response_weights', (v, i) => rbValue(v, i)],
  ['switchAfter', 'switch_after', (v) => scalar(v)],
  ['percentage', 'percentage', (v) => scalar(v)],
  ['timestamp', 'timestamp', (v) => rb(String(v))],
  ['chaos', 'chaos', (v, i) => httpChaosProfile(v as Obj, i)],
  ['beforeActions', 'before_actions', (v, i, at) => arrayOf((v as Json[]).map((a, n) => afterAction(a as Obj, i + 2, `${at}[${n}]`)), i)],
  ['afterActions', 'after_actions', (v, i, at) => arrayOf((v as Json[]).map((a, n) => afterAction(a as Obj, i + 2, `${at}[${n}]`)), i)],
  ['steps', 'steps', (v, i, at) => arrayOf((v as Json[]).map((s, n) => expectationStep(s as Obj, i + 2, `${at}[${n}]`)), i)],
  ['capture', 'capture', (v, i) => arrayOf((v as Json[]).map((c) => captureRule(c as Obj, i + 2)), i)],
  ['id', 'id', (v) => rb(String(v))],
  ['priority', 'priority', (v) => scalar(v)],
  ['namespace', 'namespace', (v) => rb(String(v))],
  ['metadata', 'metadata', (v, i) => rbValue(v, i)],
  ['scenarioName', 'scenario_name', (v) => rb(String(v))],
  ['scenarioState', 'scenario_state', (v) => rb(String(v))],
  ['newScenarioState', 'new_scenario_state', (v) => rb(String(v))],
  ['times', 'times', (v, i) => times(v as Obj, i)],
  ['timeToLive', 'time_to_live', (v, i) => timeToLive(v as Obj, i)],
];

export function standardToRuby(matcher: StandardMatcher, action: StandardActionPayload, baseUrl: string): string {
  const { host, port } = clientHostPort(baseUrl);
  const wire = buildExpectationJson(matcher, action) as Obj;

  omitted = new OmittedFields();
  // Expectation.new(...) sits at indent 4 (two levels in: client.upsert( <exp> )),
  // so its keyword args live at indent 4 and its children at indent 6.
  const args: Arg[] = [];
  for (const [wireKey, arg, render] of EXPECTATION_FIELDS) {
    if (wireKey in wire) {
      const a: Arg = { name: arg, value: render(wire[wireKey], 4, wireKey) };
      // The gem models the LLM action as a verbatim Hash (see llm()); flag why.
      if (wireKey === 'httpLlmResponse') a.comment = "LLM action: the gem carries httpLlmResponse as a verbatim Hash (MockServer::LLM builders emit Hashes)";
      args.push(a);
    }
  }
  const expectation = ctor('Expectation', args, 2);
  const note = omitted.note('#', 'Ruby');

  return [
    "require 'mockserver-client'",
    '',
    `client = MockServer::Client.new('${host}', ${port})`,
    '',
    'client.upsert(',
    '  ' + expectation,
    ')',
    ...(note ? [note] : []),
  ].join('\n');
}
