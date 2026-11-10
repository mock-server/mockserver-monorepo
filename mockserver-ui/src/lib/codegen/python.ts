/**
 * Python client-library emitter.
 *
 * Emits IDIOMATIC, TYPED client code — the same style as the published website
 * examples and the `mockserver-client-python` test-suite:
 *
 *     client.upsert(
 *         Expectation(
 *             http_request=HttpRequest(method="POST", path="/order"),
 *             http_response=HttpResponse(status_code=201, body="..."),
 *         )
 *     )
 *
 * i.e. every expectation field is built with the type-specific dataclass /
 * matcher / body object it maps to (HttpRequest, HttpResponse, Body, Jwt,
 * ConnectionOptions, AfterAction, ExpectationStep, DnsRecord, …) rather than one
 * opaque `Expectation.from_dict({ ...JSON... })` blob.
 *
 * The translator walks the canonical wire object produced by
 * {@link buildExpectationJson} and maps EVERY key the composer can emit onto a
 * typed constructor (see the coverage table in the python-specific test). There
 * is deliberately NO raw-dict passthrough of an expectation field — the only raw
 * Python literals emitted are the genuine *content* of typed fields (a JSON body
 * value, a JSON-schema string, DNS record data), which are data, not structure.
 *
 * Two encodings the typed client emits differ cosmetically from the JSON tab but
 * are accepted identically by MockServer (proven by the round-trip execution
 * test): header/query lists (`[KeyToMultiValue(...)]`) vs the JSON object-map
 * form, and `HttpOverrideForwardedRequest(http_request=...)` which serialises to
 * the `httpRequest` alias of the wire `requestOverride` field.
 */
import { buildExpectationJson, type StandardMatcher, type StandardActionPayload } from '../standardCodegen.ts';
import { clientHostPort, OmittedFields, toPythonLiteral } from './shared.ts';
import { dateTimeBuilderExpr, dateTimeMatcherOf } from '../matcherValue.ts';

type Json = Record<string, unknown>;
type Kw = [string, string];

/** A Python string literal. JSON string escaping is a subset of Python's, so a
 *  JSON.stringify result is always a valid Python double-quoted literal. */
function pyStr(s: unknown): string {
  return JSON.stringify(String(s));
}

function pyNum(n: unknown): string {
  return String(n);
}

function pyBool(b: unknown): string {
  return b ? 'True' : 'False';
}

/** Render a constructor call across multiple lines (used for container objects). */
function renderCall(name: string, kwargs: Kw[], indent: number): string {
  if (kwargs.length === 0) return `${name}()`;
  const pad = ' '.repeat(indent);
  const pad2 = ' '.repeat(indent + 4);
  const body = kwargs.map(([k, v]) => `${pad2}${k}=${v}`).join(',\n');
  return `${name}(\n${body},\n${pad})`;
}

/** Render a constructor call on a single line (used for small leaf objects like
 *  KeyToMultiValue / Delay / SseEvent whose args are all scalars). */
function renderInline(name: string, kwargs: Kw[]): string {
  return `${name}(${kwargs.map(([k, v]) => `${k}=${v}`).join(', ')})`;
}

/** Render a list; single-element lists stay on one line, longer lists break. */
function renderList(items: string[], indent: number): string {
  if (items.length === 0) return '[]';
  const only = items.length === 1 ? items[0] : undefined;
  if (only !== undefined && !only.includes('\n')) return `[${only}]`;
  const pad = ' '.repeat(indent);
  const pad2 = ' '.repeat(indent + 4);
  return `[\n${items.map((i) => pad2 + i).join(',\n')},\n${pad}]`;
}

/** A JSON value as a single-line Python literal. */
function pyInline(v: unknown): string {
  if (v === null || v === undefined) return 'None';
  if (typeof v === 'boolean') return pyBool(v);
  if (typeof v === 'number') return pyNum(v);
  if (typeof v === 'string') return pyStr(v);
  if (Array.isArray(v)) return `[${v.map(pyInline).join(', ')}]`;
  return `{${Object.entries(v as Json).map(([k, val]) => `${pyStr(k)}: ${pyInline(val)}`).join(', ')}}`;
}

function strArray(values: unknown): string {
  const arr = Array.isArray(values) ? values : [];
  return `[${arr.map((v) => pyStr(v)).join(', ')}]`;
}

function intArray(values: unknown): string {
  const arr = Array.isArray(values) ? values : [];
  return `[${arr.map((v) => pyNum(v)).join(', ')}]`;
}

// ---------------------------------------------------------------------------
// Emitter — walks buildExpectationJson output into typed constructors.
// ---------------------------------------------------------------------------

/** `Body` keyword arguments after the ones each body type renders first, in output order. */
const BODY_KWARGS: [string, string, 'str' | 'bool'][] = [
  ['string', 'string', 'str'], ['base64Bytes', 'base64_bytes', 'str'], ['filePath', 'file_path', 'str'],
  ['templateType', 'template_type', 'str'], ['templatedFilePath', 'templated_file_path', 'bool'],
  ['contentType', 'content_type', 'str'], ['charset', 'charset', 'str'],
  ['matchType', 'match_type', 'str'], ['subString', 'sub_string', 'bool'],
  ['matchNumbersAsStrings', 'match_numbers_as_strings', 'bool'], ['not', 'not_body', 'bool'], ['optional', 'optional', 'bool'],
];

/** The wire keys each `Body` type renders before the rest of {@link BODY_KWARGS}. */
const BODY_FIRST: Record<string, string[]> = {
  JSON: ['json', 'matchType'],
  STRING: ['string', 'subString'],
  BINARY: ['base64Bytes'],
  FILE: ['filePath', 'templateType', 'templatedFilePath', 'contentType'],
};

class PyBuilder {
  readonly imports = new Set<string>();
  /** Set when a binary WebSocket message needs `base64.b64decode`. */
  usesBase64 = false;
  readonly omitted = new OmittedFields();

  private use(name: string): void {
    this.imports.add(name);
  }

  private known(at: string, o: Json, keys: readonly string[]): void {
    this.omitted.unknown(at, o, keys);
  }

  /** `{name: [values]}` object-map (headers / query params) → `[KeyToMultiValue(...)]`. */
  private keyMultiList(map: unknown, indent: number): string {
    this.use('KeyToMultiValue');
    const entries = Object.entries((map ?? {}) as Json);
    const items = entries.map(([k, vals]) =>
      renderInline('KeyToMultiValue', [['name', pyStr(k)], ['values', this.matcherValues(vals)]]),
    );
    return renderList(items, indent);
  }

  /** `{name: value}` single-value cookie map → `[KeyToMultiValue(name=.., values=[..])]`. */
  private cookieList(map: unknown, indent: number): string {
    this.use('KeyToMultiValue');
    const entries = Object.entries((map ?? {}) as Json);
    const items = entries.map(([k, v]) =>
      renderInline('KeyToMultiValue', [['name', pyStr(k)], ['values', this.matcherValues([v])]]),
    );
    return renderList(items, indent);
  }

  /** A matcher-value list: strings as literals, a date-time matcher via `DateTimeMatcher`, any other
   *  object form (nottable / schema) as the dict the client sends verbatim. */
  private matcherValues(values: unknown): string {
    const arr = Array.isArray(values) ? values : [];
    return `[${arr.map((v) => this.matcherValue(v)).join(', ')}]`;
  }

  private matcherValue(v: unknown): string {
    if (v === null || typeof v !== 'object') return pyStr(v);
    const dt = dateTimeMatcherOf(v);
    if (!dt) return pyInline(v);
    this.use('DateTimeMatcher');
    return dateTimeBuilderExpr(dt, { head: 'DateTimeMatcher.', naming: 'snake', str: pyStr, bool: pyBool });
  }

  /** The `date_time` keyword of a JSONPath / XPath body, when it has a date-time comparison the
   *  `DateTimeMatcher` builder can express; any other `dateTime` is reported as omitted. */
  private dateTimeKw(o: Json): Kw[] {
    return dateTimeMatcherOf(o['dateTime']) ? [['date_time', this.matcherValue(o['dateTime'])]] : [];
  }

  private dateTimeKey(o: Json): string[] {
    return dateTimeMatcherOf(o['dateTime']) ? ['dateTime'] : [];
  }

  private delay(v: unknown, at: string): string {
    this.use('Delay');
    const o = v as Json;
    this.known(at, o, ['timeUnit', 'value', 'distribution', 'template', 'templateType']);
    const kw: Kw[] = [];
    if (o['timeUnit'] != null) kw.push(['time_unit', pyStr(o['timeUnit'])]);
    if (o['value'] != null) kw.push(['value', pyNum(o['value'])]);
    if (o['distribution'] != null) kw.push(['distribution', this.delayDistribution(o['distribution'], `${at}.distribution`)]);
    if (o['template'] != null) kw.push(['template', pyStr(o['template'])]);
    if (o['templateType'] != null) kw.push(['template_type', pyStr(o['templateType'])]);
    return renderInline('Delay', kw);
  }

  private delayDistribution(v: unknown, at: string): string {
    this.use('DelayDistribution');
    const o = v as Json;
    const fields: [string, string][] = [
      ['type', 'type'], ['min', 'min'], ['max', 'max'], ['median', 'median'],
      ['p99', 'p99'], ['mean', 'mean'], ['stdDev', 'std_dev'],
    ];
    this.known(at, o, fields.map(([wire]) => wire));
    const kw: Kw[] = [];
    for (const [wire, py] of fields) {
      if (o[wire] != null) kw.push([py, typeof o[wire] === 'string' ? pyStr(o[wire]) : pyNum(o[wire])]);
    }
    return renderInline('DelayDistribution', kw);
  }

  /** The delay and primary every action inherits. */
  private inherited(o: Json, kw: Kw[], at: string): Kw[] {
    if (o['delay'] != null) kw.push(['delay', this.delay(o['delay'], `${at}.delay`)]);
    if (typeof o['primary'] === 'boolean') kw.push(['primary', pyBool(o['primary'])]);
    return kw;
  }

  /**
   * A JSON, STRING, BINARY or FILE body → `Body(...)`: the type's own fields first, then
   * every other field the `Body` model holds.
   */
  private plainBody(o: Json, type: string, indent: number, at: string): string {
    this.use('Body');
    const first = BODY_FIRST[type] ?? [];
    this.known(at, o, ['type', 'json', ...BODY_KWARGS.map(([wire]) => wire)]);
    const kw: Kw[] = [['type', pyStr(type)]];
    const arg = (wire: string): Kw | undefined => {
      if (o[wire] == null) return undefined;
      if (wire === 'json') return ['json', toPythonLiteral(o['json'], indent + 4)];
      const [, py, kind] = BODY_KWARGS.find(([w]) => w === wire)!;
      return [py, kind === 'bool' ? pyBool(o[wire]) : pyStr(o[wire])];
    };
    for (const wire of first) {
      const a = arg(wire);
      if (a) kw.push(a);
    }
    for (const [wire] of BODY_KWARGS) {
      if (first.includes(wire)) continue;
      const a = arg(wire);
      if (a) kw.push(a);
    }
    if (type !== 'JSON' && o['json'] != null) kw.push(arg('json')!);
    return type === 'JSON' ? renderCall('Body', kw, indent) : renderInline('Body', kw);
  }

  /** A request/response body wire value → the typed Body / *Body matcher. */
  private body(v: unknown, indent: number, at: string): string {
    if (typeof v === 'string') return pyStr(v);
    if (!v || typeof v !== 'object') return pyStr(v);
    const o = v as Json;
    const type = o['type'];
    const known = (...keys: string[]) => this.known(at, o, ['type', ...keys]);
    switch (type) {
      case 'JSON':
      case 'STRING':
      case 'BINARY':
      case 'FILE':
        return this.plainBody(o, type, indent, at);
      case 'GRAPHQL': {
        known('query', 'selectionSetMatchType', 'fields');
        this.use('GraphQLBody');
        const kw: Kw[] = [['query', pyStr(o['query'])]];
        if (o['selectionSetMatchType'] != null) kw.push(['selection_set_match_type', pyStr(o['selectionSetMatchType'])]);
        if (o['fields'] != null) kw.push(['fields', strArray(o['fields'])]);
        return renderCall('GraphQLBody', kw, indent);
      }
      case 'JSON_SCHEMA':
        known('jsonSchema');
        this.use('JsonSchemaBody');
        return renderCall('JsonSchemaBody', [['json_schema', pyStr(o['jsonSchema'])]], indent);
      case 'JSON_PATH':
        known('jsonPath', ...this.dateTimeKey(o));
        this.use('JsonPathBody');
        return renderInline('JsonPathBody', [['json_path', pyStr(o['jsonPath'])], ...this.dateTimeKw(o)]);
      case 'XML':
        known('xml');
        this.use('XmlBody');
        return renderInline('XmlBody', [['xml', pyStr(o['xml'])]]);
      case 'XML_SCHEMA':
        known('xmlSchema');
        this.use('XmlSchemaBody');
        return renderCall('XmlSchemaBody', [['xml_schema', pyStr(o['xmlSchema'])]], indent);
      case 'XPATH':
        known('xpath', ...this.dateTimeKey(o));
        this.use('XPathBody');
        return renderInline('XPathBody', [['xpath', pyStr(o['xpath'])], ...this.dateTimeKw(o)]);
      case 'REGEX':
        known('regex');
        this.use('RegexBody');
        return renderInline('RegexBody', [['regex', pyStr(o['regex'])]]);
      case 'WASM':
        known('moduleName');
        this.use('WasmBody');
        return renderInline('WasmBody', [['module_name', pyStr(o['moduleName'])]]);
      case 'PARAMETERS':
        known('parameters');
        this.use('ParameterBody');
        return renderCall('ParameterBody', [
          ['parameters', this.keyMultiList(o['parameters'], indent + 4)],
          ['parameters_as_map', 'True'],
        ], indent);
      case 'ALL_OF': {
        known('bodyAllOf');
        this.use('AllOfBody');
        const subs = (o['bodyAllOf'] as unknown[]) ?? [];
        const items = subs.map((s, n) => this.body(s, indent + 8, `${at}.bodyAllOf[${n}]`));
        return renderCall('AllOfBody', [['body_all_of', renderList(items, indent + 4)]], indent);
      }
      default:
        // Should never occur for composer output; fall back to a Python literal
        // so no data is silently lost.
        return toPythonLiteral(v, indent);
    }
  }

  private jwt(v: unknown, indent: number): string {
    this.use('Jwt');
    const o = v as Json;
    const kw: Kw[] = [];
    if (o['claims'] != null) kw.push(['claims', toPythonLiteral(o['claims'], indent + 4)]);
    if (o['issuer'] != null) kw.push(['issuer', pyStr(o['issuer'])]);
    if (o['audience'] != null) kw.push(['audience', pyStr(o['audience'])]);
    if (o['algorithm'] != null) kw.push(['algorithm', pyStr(o['algorithm'])]);
    if (o['header'] != null) kw.push(['header', pyStr(o['header'])]);
    if (o['scheme'] != null) kw.push(['scheme', pyStr(o['scheme'])]);
    return renderCall('Jwt', kw, indent);
  }

  private request(v: Json, indent: number, at: string): string {
    if (Array.isArray(v['anyOf'])) return this.anyOfRequest(v, indent, at);
    this.use('HttpRequest');
    this.known(at, v, [
      'method', 'path', 'headers', 'queryStringParameters', 'cookies', 'pathParameters', 'body', 'jwt', 'secure',
      'keepAlive', 'respondBeforeBody', 'protocol', 'socketAddress', 'not', 'clientIp',
    ]);
    const kw: Kw[] = [];
    if (v['method'] != null) kw.push(['method', pyStr(v['method'])]);
    if (v['path'] != null) kw.push(['path', pyStr(v['path'])]);
    if (v['headers'] != null) kw.push(['headers', this.keyMultiList(v['headers'], indent + 4)]);
    if (v['queryStringParameters'] != null) kw.push(['query_string_parameters', this.keyMultiList(v['queryStringParameters'], indent + 4)]);
    if (v['cookies'] != null) kw.push(['cookies', this.cookieList(v['cookies'], indent + 4)]);
    if (v['pathParameters'] != null) kw.push(['path_parameters', this.keyMultiList(v['pathParameters'], indent + 4)]);
    if (v['body'] != null) kw.push(['body', this.body(v['body'], indent + 4, `${at}.body`)]);
    if (v['jwt'] != null) kw.push(['jwt', this.jwt(v['jwt'], indent + 4)]);
    // typeof, not truthiness: secure:false is an HTTP-only matcher, not an absent field
    if (typeof v['secure'] === 'boolean') kw.push(['secure', v['secure'] ? 'True' : 'False']);
    if (typeof v['keepAlive'] === 'boolean') kw.push(['keep_alive', pyBool(v['keepAlive'])]);
    if (typeof v['respondBeforeBody'] === 'boolean') kw.push(['respond_before_body', pyBool(v['respondBeforeBody'])]);
    if (v['protocol'] != null) kw.push(['protocol', pyStr(v['protocol'])]);
    if (v['socketAddress'] != null) kw.push(['socket_address', this.socketAddress(v['socketAddress'] as Json, `${at}.socketAddress`)]);
    if (typeof v['not'] === 'boolean') kw.push(['not_request', pyBool(v['not'])]);
    if (v['clientIp'] != null) kw.push(['client_ip', pyStr(v['clientIp'])]);
    return renderCall('HttpRequest', kw, indent);
  }

  private anyOfRequest(v: Json, indent: number, at: string): string {
    this.use('AnyOfRequestDefinition');
    this.known(at, v, ['anyOf', 'not']);
    const branches = (v['anyOf'] as unknown[]).map((b, n) => this.request(b as Json, indent + 8, `${at}.anyOf[${n}]`));
    const kw: Kw[] = [['any_of', renderList(branches, indent + 4)]];
    if (typeof v['not'] === 'boolean') kw.push(['not_any_of', pyBool(v['not'])]);
    return renderCall('AnyOfRequestDefinition', kw, indent);
  }

  private socketAddress(o: Json, at: string): string {
    this.use('SocketAddress');
    this.known(at, o, ['host', 'port', 'scheme']);
    const kw: Kw[] = [];
    if (o['host'] != null) kw.push(['host', pyStr(o['host'])]);
    if (o['port'] != null) kw.push(['port', pyNum(o['port'])]);
    if (o['scheme'] != null) kw.push(['scheme', pyStr(o['scheme'])]);
    return renderInline('SocketAddress', kw);
  }

  private dnsRequest(v: Json, indent: number): string {
    this.use('DnsRequestDefinition');
    const kw: Kw[] = [['dns_name', pyStr(v['dnsName'])]];
    if (v['dnsType'] != null) kw.push(['dns_type', pyStr(v['dnsType'])]);
    if (v['dnsClass'] != null) kw.push(['dns_class', pyStr(v['dnsClass'])]);
    return renderCall('DnsRequestDefinition', kw, indent);
  }

  private requestOrDns(v: Json, indent: number): string {
    return 'dnsName' in v ? this.dnsRequest(v, indent) : this.request(v, indent, 'httpRequest');
  }

  private connectionOptions(v: unknown, indent: number, at: string): string {
    this.use('ConnectionOptions');
    const o = v as Json;
    const map: [string, string][] = [
      ['keepAliveOverride', 'keep_alive_override'],
      ['closeSocket', 'close_socket'],
      ['contentLengthHeaderOverride', 'content_length_header_override'],
      ['suppressContentLengthHeader', 'suppress_content_length_header'],
      ['suppressConnectionHeader', 'suppress_connection_header'],
      ['chunkSize', 'chunk_size'],
    ];
    const delays: [string, string][] = [['closeSocketDelay', 'close_socket_delay'], ['chunkDelay', 'chunk_delay']];
    this.known(at, o, [...map, ...delays].map(([wire]) => wire));
    const kw: Kw[] = [];
    for (const [wire, py] of map) {
      if (o[wire] == null) continue;
      const val = o[wire];
      kw.push([py, typeof val === 'boolean' ? pyBool(val) : pyNum(val)]);
    }
    for (const [wire, py] of delays) {
      if (o[wire] != null) kw.push([py, this.delay(o[wire], `${at}.${wire}`)]);
    }
    return renderCall('ConnectionOptions', kw, indent);
  }

  private response(v: unknown, indent: number, at: string): string {
    this.use('HttpResponse');
    const o = v as Json;
    this.known(at, o, [
      'statusCode', 'reasonPhrase', 'body', 'headers', 'cookies', 'delay', 'connectionOptions', 'primary', 'trailers',
      'statusCodeRange', 'generateFromSchema', 'recoverAfter',
    ]);
    const kw: Kw[] = [];
    if (o['statusCode'] != null) kw.push(['status_code', pyNum(o['statusCode'])]);
    if (o['reasonPhrase'] != null) kw.push(['reason_phrase', pyStr(o['reasonPhrase'])]);
    if (o['body'] != null) kw.push(['body', this.body(o['body'], indent + 4, `${at}.body`)]);
    if (o['headers'] != null) kw.push(['headers', this.keyMultiList(o['headers'], indent + 4)]);
    if (o['cookies'] != null) kw.push(['cookies', this.cookieList(o['cookies'], indent + 4)]);
    if (o['delay'] != null) kw.push(['delay', this.delay(o['delay'], `${at}.delay`)]);
    if (o['connectionOptions'] != null) kw.push(['connection_options', this.connectionOptions(o['connectionOptions'], indent + 4, `${at}.connectionOptions`)]);
    if (typeof o['primary'] === 'boolean') kw.push(['primary', pyBool(o['primary'])]);
    if (o['trailers'] != null) kw.push(['trailers', this.keyMultiList(o['trailers'], indent + 4)]);
    if (o['statusCodeRange'] != null) kw.push(['status_code_range', pyStr(o['statusCodeRange'])]);
    if (o['generateFromSchema'] != null) kw.push(['generate_from_schema', pyStr(o['generateFromSchema'])]);
    if (o['recoverAfter'] != null) kw.push(['recover_after', this.recoverAfter(o['recoverAfter'], indent + 4, `${at}.recoverAfter`)]);
    return renderCall('HttpResponse', kw, indent);
  }

  private recoverAfter(v: unknown, indent: number, at: string): string {
    this.use('RecoverAfter');
    const o = v as Json;
    this.known(at, o, ['failTimes', 'failResponse', 'idempotencyHeader']);
    const kw: Kw[] = [];
    if (o['failTimes'] != null) kw.push(['fail_times', pyNum(o['failTimes'])]);
    if (o['failResponse'] != null) kw.push(['fail_response', this.response(o['failResponse'], indent + 4, `${at}.failResponse`)]);
    if (o['idempotencyHeader'] != null) kw.push(['idempotency_header', pyStr(o['idempotencyHeader'])]);
    return renderCall('RecoverAfter', kw, indent);
  }

  private forward(v: unknown, indent: number, at: string): string {
    this.use('HttpForward');
    const o = v as Json;
    this.known(at, o, ['scheme', 'host', 'port', 'delay', 'primary']);
    const kw: Kw[] = [];
    if (o['scheme'] != null) kw.push(['scheme', pyStr(o['scheme'])]);
    if (o['host'] != null) kw.push(['host', pyStr(o['host'])]);
    if (o['port'] != null) kw.push(['port', pyNum(o['port'])]);
    return renderCall('HttpForward', this.inherited(o, kw, at), indent);
  }

  /**
   * The model serialises its request and response under the `httpRequest` / `httpResponse`
   * aliases the server also accepts for `requestOverride` / `responseOverride`.
   */
  private override(v: unknown, indent: number, at: string): string {
    this.use('HttpOverrideForwardedRequest');
    const o = v as Json;
    const reqKey = 'requestOverride' in o ? 'requestOverride' : 'httpRequest';
    const respKey = 'responseOverride' in o ? 'responseOverride' : 'httpResponse';
    this.known(at, o, [reqKey, respKey, 'responseTemplate', 'requestModifier', 'responseModifier', 'delay', 'primary']);
    const kw: Kw[] = [];
    if (o[reqKey] != null) kw.push(['http_request', this.request(o[reqKey] as Json, indent + 4, `${at}.${reqKey}`)]);
    if (o[respKey] != null) kw.push(['http_response', this.response(o[respKey], indent + 4, `${at}.${respKey}`)]);
    if (o['responseTemplate'] != null) kw.push(['response_template', this.template(o['responseTemplate'], indent + 4, `${at}.responseTemplate`)]);
    if (o['requestModifier'] != null) kw.push(['request_modifier', toPythonLiteral(o['requestModifier'], indent + 4)]);
    if (o['responseModifier'] != null) kw.push(['response_modifier', toPythonLiteral(o['responseModifier'], indent + 4)]);
    return renderCall('HttpOverrideForwardedRequest', this.inherited(o, kw, at), indent);
  }

  private classCallback(v: unknown, indent: number, at: string): string {
    this.use('HttpClassCallback');
    const o = v as Json;
    this.known(at, o, ['callbackClass', 'delay', 'primary']);
    return renderCall('HttpClassCallback', this.inherited(o, [['callback_class', pyStr(o['callbackClass'])]], at), indent);
  }

  private template(v: unknown, indent: number, at: string): string {
    this.use('HttpTemplate');
    const o = v as Json;
    this.known(at, o, ['templateType', 'template', 'templateFile', 'delay', 'primary', 'responseOverride', 'responseModifier', 'parameters']);
    const kw: Kw[] = [['template_type', pyStr(o['templateType'])]];
    if (o['template'] != null) kw.push(['template', pyStr(o['template'])]);
    if (o['templateFile'] != null) kw.push(['template_file', pyStr(o['templateFile'])]);
    if (o['responseOverride'] != null) kw.push(['response_override', this.response(o['responseOverride'], indent + 4, `${at}.responseOverride`)]);
    if (o['responseModifier'] != null) kw.push(['response_modifier', toPythonLiteral(o['responseModifier'], indent + 4)]);
    if (o['parameters'] != null) kw.push(['parameters', toPythonLiteral(o['parameters'], indent + 4)]);
    return renderCall('HttpTemplate', this.inherited(o, kw, at), indent);
  }

  private error(v: unknown, indent: number, at: string): string {
    this.use('HttpError');
    const o = v as Json;
    this.known(at, o, ['dropConnection', 'resetConnection', 'responseBytes', 'streamError', 'delay', 'primary']);
    const kw: Kw[] = [];
    if (o['dropConnection'] != null) kw.push(['drop_connection', pyBool(o['dropConnection'])]);
    if (o['resetConnection'] != null) kw.push(['reset_connection', pyBool(o['resetConnection'])]);
    if (o['responseBytes'] != null) kw.push(['response_bytes', pyStr(o['responseBytes'])]);
    if (o['streamError'] != null) kw.push(['stream_error', pyNum(o['streamError'])]);
    return renderCall('HttpError', this.inherited(o, kw, at), indent);
  }

  private forwardWithFallback(v: unknown, indent: number, at: string): string {
    this.use('HttpForwardWithFallback');
    const o = v as Json;
    this.known(at, o, ['httpForward', 'fallbackResponse', 'fallbackOnStatusCodes', 'fallbackOnTimeout', 'delay', 'primary']);
    const kw: Kw[] = [];
    if (o['httpForward'] != null) kw.push(['http_forward', this.forward(o['httpForward'], indent + 4, `${at}.httpForward`)]);
    if (o['fallbackResponse'] != null) kw.push(['fallback_response', this.response(o['fallbackResponse'], indent + 4, `${at}.fallbackResponse`)]);
    if (o['fallbackOnStatusCodes'] != null) kw.push(['fallback_on_status_codes', intArray(o['fallbackOnStatusCodes'])]);
    if (o['fallbackOnTimeout'] != null) kw.push(['fallback_on_timeout', pyBool(o['fallbackOnTimeout'])]);
    return renderCall('HttpForwardWithFallback', this.inherited(o, kw, at), indent);
  }

  private webSocket(v: unknown, indent: number, at: string): string {
    this.use('HttpWebSocketResponse');
    const o = v as Json;
    this.known(at, o, ['subprotocol', 'messages', 'closeConnection', 'matchers', 'templateType', 'graphqlSubscriptionFilter', 'delay', 'primary']);
    const kw: Kw[] = [];
    if (o['subprotocol'] != null) kw.push(['subprotocol', pyStr(o['subprotocol'])]);
    if (o['messages'] != null) kw.push(['messages', this.wsMessages(o['messages'], indent + 4, `${at}.messages`)]);
    if (o['closeConnection'] != null) kw.push(['close_connection', pyBool(o['closeConnection'])]);
    if (o['matchers'] != null) {
      this.use('WebSocketFrameMatcher');
      const matchers = (o['matchers'] as Json[]).map((m, n) => {
        this.known(`${at}.matchers[${n}]`, m, ['frameType', 'textMatcher', 'responses']);
        const mk: Kw[] = [];
        if (m['frameType'] != null) mk.push(['frame_type', pyStr(m['frameType'])]);
        if (m['textMatcher'] != null) mk.push(['text_matcher', pyStr(m['textMatcher'])]);
        if (m['responses'] != null) mk.push(['responses', this.wsMessages(m['responses'], indent + 12, `${at}.matchers[${n}].responses`)]);
        return renderCall('WebSocketFrameMatcher', mk, indent + 8);
      });
      kw.push(['matchers', renderList(matchers, indent + 4)]);
    }
    if (o['templateType'] != null) kw.push(['template_type', pyStr(o['templateType'])]);
    if (o['graphqlSubscriptionFilter'] != null) {
      kw.push(['graphql_subscription_filter', this.graphqlFilter(o['graphqlSubscriptionFilter'] as Json, indent + 4, `${at}.graphqlSubscriptionFilter`)]);
    }
    return renderCall('HttpWebSocketResponse', this.inherited(o, kw, at), indent);
  }

  private graphqlFilter(o: Json, indent: number, at: string): string {
    this.use('GraphQLSubscriptionFilter');
    const fields: [string, string][] = [
      ['type', 'type'], ['query', 'query'], ['operationName', 'operation_name'],
      ['variablesSchema', 'variables_schema'], ['selectionSetMatchType', 'selection_set_match_type'],
    ];
    this.known(at, o, [...fields.map(([wire]) => wire), 'fields']);
    const kw: Kw[] = [];
    for (const [wire, py] of fields) if (o[wire] != null) kw.push([py, pyStr(o[wire])]);
    if (o['fields'] != null) kw.push(['fields', strArray(o['fields'])]);
    return renderCall('GraphQLSubscriptionFilter', kw, indent);
  }

  /** The model holds a binary message as bytes, which it base64-encodes back onto the wire. */
  private wsMessages(v: unknown, indent: number, at: string): string {
    this.use('WebSocketMessage');
    const items = (v as Json[]).map((m, n) => {
      this.known(`${at}[${n}]`, m, ['text', 'binary', 'delay']);
      const mk: Kw[] = [];
      if (m['text'] != null) mk.push(['text', pyStr(m['text'])]);
      if (m['binary'] != null) {
        this.usesBase64 = true;
        mk.push(['binary', `base64.b64decode(${pyStr(m['binary'])})`]);
      }
      if (m['delay'] != null) mk.push(['delay', this.delay(m['delay'], `${at}[${n}].delay`)]);
      return renderInline('WebSocketMessage', mk);
    });
    return renderList(items, indent);
  }

  private sse(v: unknown, indent: number, at: string): string {
    this.use('HttpSseResponse');
    const o = v as Json;
    this.known(at, o, ['statusCode', 'headers', 'events', 'closeConnection', 'closeDelay', 'keepStreamOpen', 'templateType', 'delay', 'primary']);
    const kw: Kw[] = [];
    if (o['statusCode'] != null) kw.push(['status_code', pyNum(o['statusCode'])]);
    if (o['headers'] != null) kw.push(['headers', this.keyMultiList(o['headers'], indent + 4)]);
    if (o['events'] != null) {
      this.use('SseEvent');
      const events = (o['events'] as Json[]).map((e, n) => {
        this.known(`${at}.events[${n}]`, e, ['event', 'data', 'id', 'retry', 'comment', 'delay']);
        const ek: Kw[] = [];
        if (e['event'] != null) ek.push(['event', pyStr(e['event'])]);
        if (e['data'] != null) ek.push(['data', pyStr(e['data'])]);
        if (e['id'] != null) ek.push(['id', pyStr(e['id'])]);
        if (e['retry'] != null) ek.push(['retry', pyNum(e['retry'])]);
        if (e['comment'] != null) ek.push(['comment', pyStr(e['comment'])]);
        if (e['delay'] != null) ek.push(['delay', this.delay(e['delay'], `${at}.events[${n}].delay`)]);
        return renderInline('SseEvent', ek);
      });
      kw.push(['events', renderList(events, indent + 4)]);
    }
    if (o['closeConnection'] != null) kw.push(['close_connection', pyBool(o['closeConnection'])]);
    if (o['closeDelay'] != null) kw.push(['close_delay', this.delay(o['closeDelay'], `${at}.closeDelay`)]);
    if (o['keepStreamOpen'] != null) kw.push(['keep_stream_open', pyBool(o['keepStreamOpen'])]);
    if (o['templateType'] != null) kw.push(['template_type', pyStr(o['templateType'])]);
    return renderCall('HttpSseResponse', this.inherited(o, kw, at), indent);
  }

  private binary(v: unknown, indent: number, at: string): string {
    this.use('BinaryResponse');
    const o = v as Json;
    this.known(at, o, ['binaryData', 'upstream', 'delay', 'primary']);
    // No data is a valid binary response: the message has no reply.
    const kw: Kw[] = typeof o['binaryData'] === 'string' && o['binaryData'] !== ''
      ? [['binary_data', pyStr(o['binaryData'])]]
      : [];
    if (typeof o['upstream'] === 'string') kw.push(['upstream', pyStr(o['upstream'])]);
    return renderCall('BinaryResponse', this.inherited(o, kw, at), indent);
  }

  private dnsResponse(v: unknown, indent: number, at: string): string {
    this.use('DnsResponse');
    const o = v as Json;
    const sections: [string, string][] = [
      ['answerRecords', 'answer_records'], ['authorityRecords', 'authority_records'], ['additionalRecords', 'additional_records'],
    ];
    this.known(at, o, ['responseCode', ...sections.map(([wire]) => wire), 'delay', 'primary']);
    const fieldMap: [string, string][] = [
      ['name', 'name'], ['type', 'type'], ['dnsClass', 'dns_class'], ['ttl', 'ttl'],
      ['value', 'value'], ['priority', 'priority'], ['weight', 'weight'], ['port', 'port'],
    ];
    const kw: Kw[] = [];
    if (o['responseCode'] != null) kw.push(['response_code', pyStr(o['responseCode'])]);
    for (const [wire, py] of sections) {
      if (!Array.isArray(o[wire])) continue;
      this.use('DnsRecord');
      const records = (o[wire] as Json[]).map((r, n) => {
        this.known(`${at}.${wire}[${n}]`, r, fieldMap.map(([w]) => w));
        const rk: Kw[] = [];
        for (const [rw, rp] of fieldMap) {
          if (r[rw] == null) continue;
          rk.push([rp, typeof r[rw] === 'number' ? pyNum(r[rw]) : pyStr(r[rw])]);
        }
        return renderInline('DnsRecord', rk);
      });
      kw.push([py, renderList(records, indent + 4)]);
    }
    return renderCall('DnsResponse', this.inherited(o, kw, at), indent);
  }

  private grpc(v: unknown, indent: number, at: string): string {
    this.use('GrpcStreamResponse');
    const o = v as Json;
    this.known(at, o, ['statusName', 'statusMessage', 'headers', 'messages', 'closeConnection', 'delay', 'primary']);
    const kw: Kw[] = [];
    if (o['statusName'] != null) kw.push(['status_name', pyStr(o['statusName'])]);
    if (o['statusMessage'] != null) kw.push(['status_message', pyStr(o['statusMessage'])]);
    if (o['headers'] != null) kw.push(['headers', this.keyMultiList(o['headers'], indent + 4)]);
    if (o['messages'] != null) {
      this.use('GrpcStreamMessage');
      kw.push(['messages', this.grpcMessages(o['messages'], indent + 4, `${at}.messages`)]);
    }
    if (o['closeConnection'] != null) kw.push(['close_connection', pyBool(o['closeConnection'])]);
    return renderCall('GrpcStreamResponse', this.inherited(o, kw, at), indent);
  }

  private chaos(v: unknown, indent: number): string {
    this.use('HttpChaosProfile');
    const o = v as Json;
    const kw: Kw[] = [];
    if (o['errorStatus'] != null) kw.push(['error_status', pyNum(o['errorStatus'])]);
    if (o['errorProbability'] != null) kw.push(['error_probability', pyNum(o['errorProbability'])]);
    if (o['retryAfter'] != null) kw.push(['retry_after', pyStr(o['retryAfter'])]);
    if (o['latency'] != null) kw.push(['latency', this.delay(o['latency'], 'chaos.latency')]);
    if (o['seed'] != null) kw.push(['seed', pyNum(o['seed'])]);
    if (o['succeedFirst'] != null) kw.push(['succeed_first', pyNum(o['succeedFirst'])]);
    if (o['failRequestCount'] != null) kw.push(['fail_request_count', pyNum(o['failRequestCount'])]);
    return renderCall('HttpChaosProfile', kw, indent);
  }

  private actions(v: unknown, indent: number, at: string): string {
    this.use('AfterAction');
    const items = (v as Json[]).map((a, n) => {
      const ak: Kw[] = [];
      if (a['httpRequest'] != null) ak.push(['http_request', this.request(a['httpRequest'] as Json, indent + 8, `${at}[${n}].httpRequest`)]);
      if (a['httpRequestTemplate'] != null) ak.push(['http_request_template', this.template(a['httpRequestTemplate'], indent + 8, `${at}[${n}].httpRequestTemplate`)]);
      if (a['delay'] != null) ak.push(['delay', this.delay(a['delay'], `${at}[${n}].delay`)]);
      if (a['blocking'] != null) ak.push(['blocking', pyBool(a['blocking'])]);
      if (a['timeout'] != null) ak.push(['timeout', this.delay(a['timeout'], `${at}[${n}].timeout`)]);
      if (a['failurePolicy'] != null) ak.push(['failure_policy', pyStr(a['failurePolicy'])]);
      return renderCall('AfterAction', ak, indent + 4);
    });
    return renderList(items, indent);
  }

  private steps(v: unknown, indent: number): string {
    this.use('ExpectationStep');
    const stepActions: [string, string, (x: unknown, i: number, at: string) => string][] = [
      ['httpResponse', 'http_response', (x, i, p) => this.response(x, i, p)],
      ['httpForward', 'http_forward', (x, i, p) => this.forward(x, i, p)],
      ['httpOverrideForwardedRequest', 'http_override_forwarded_request', (x, i, p) => this.override(x, i, p)],
      ['httpError', 'http_error', (x, i, p) => this.error(x, i, p)],
      ['httpRequest', 'http_request', (x, i, p) => this.request(x as Json, i, p)],
      ['httpClassCallback', 'http_class_callback', (x, i, p) => this.classCallback(x, i, p)],
    ];
    const items = (v as Json[]).map((s, n) => {
      const sk: Kw[] = [];
      for (const [wire, py, emit] of stepActions) {
        if (s[wire] != null) {
          sk.push([py, emit(s[wire], indent + 8, `steps[${n}].${wire}`)]);
          break;
        }
      }
      if (s['responder'] != null) sk.push(['responder', pyBool(s['responder'])]);
      if (s['delay'] != null) sk.push(['delay', this.delay(s['delay'], `steps[${n}].delay`)]);
      if (s['blocking'] != null) sk.push(['blocking', pyBool(s['blocking'])]);
      if (s['timeout'] != null) sk.push(['timeout', this.delay(s['timeout'], `steps[${n}].timeout`)]);
      if (s['failurePolicy'] != null) sk.push(['failure_policy', pyStr(s['failurePolicy'])]);
      return renderCall('ExpectationStep', sk, indent + 4);
    });
    return renderList(items, indent);
  }

  /**
   * Generic typed-constructor renderer: emit `ClassName(kw=...)` from a field
   * spec `[wireKey, pythonKwarg, convert]`, skipping absent fields. Used by the
   * LLM / rate-limit / cross-protocol / object-callback / forward-validate /
   * gRPC-bidi builders so every edit-preserved wire field maps onto a typed
   * dataclass argument (never a raw dict passthrough).
   */
  private typed(
    className: string,
    o: Json,
    spec: Array<[string, string, (v: unknown, indent: number, at: string) => string]>,
    indent: number,
    at: string,
  ): string {
    this.use(className);
    this.known(at, o, spec.map(([wire]) => wire));
    const kw: Kw[] = [];
    for (const [wire, py, fn] of spec) {
      if (o[wire] != null) kw.push([py, fn(o[wire], indent + 4, `${at}.${wire}`)]);
    }
    return renderCall(className, kw, indent);
  }

  // --- LLM response (httpLlmResponse) — fully typed tree ---------------------

  private llm(v: unknown, indent: number, at: string): string {
    const S = (x: unknown) => pyStr(x);
    const B = (x: unknown) => pyBool(x);
    return this.typed('HttpLlmResponse', v as Json, [
      ['provider', 'provider', S],
      ['model', 'model', S],
      ['completion', 'completion', (x, i, p) => this.completion(x, i, p)],
      ['embedding', 'embedding', (x, i, p) => this.embedding(x, i, p)],
      ['rerank', 'rerank', (x, i, p) => this.rerank(x, i, p)],
      ['moderation', 'moderation', (x, i, p) => this.moderation(x, i, p)],
      ['contentFilter', 'content_filter', (x, i, p) => this.contentFilter(x, i, p)],
      ['conversationPredicates', 'conversation_predicates', (x, i, p) => this.conversationPredicates(x, i, p)],
      ['chaos', 'chaos', (x, i, p) => this.llmChaos(x, i, p)],
      ['delay', 'delay', (x, _i, p) => this.delay(x, p)],
      ['primary', 'primary', B],
    ], indent, at);
  }

  private completion(v: unknown, indent: number, at: string): string {
    const S = (x: unknown) => pyStr(x);
    const B = (x: unknown) => pyBool(x);
    return this.typed('Completion', v as Json, [
      ['text', 'text', S],
      ['toolCalls', 'tool_calls', (x, i, p) => this.toolCalls(x, i, p)],
      ['stopReason', 'stop_reason', S],
      ['usage', 'usage', (x, i, p) => this.usage(x, i, p)],
      ['streaming', 'streaming', B],
      ['streamingPhysics', 'streaming_physics', (x, i, p) => this.streamingPhysics(x, i, p)],
      ['outputSchema', 'output_schema', S],
      ['enforceOutputSchema', 'enforce_output_schema', B],
      ['toolChoice', 'tool_choice', S],
      ['reasoningText', 'reasoning_text', S],
      ['reasoningSignature', 'reasoning_signature', S],
      ['model', 'model', S],
    ], indent, at);
  }

  private toolCalls(v: unknown, indent: number, at: string): string {
    this.use('ToolUse');
    const items = (v as Json[]).map((t, n) => {
      this.known(`${at}[${n}]`, t, ['name', 'id', 'arguments']);
      const kw: Kw[] = [];
      if (t['name'] != null) kw.push(['name', pyStr(t['name'])]);
      if (t['id'] != null) kw.push(['id', pyStr(t['id'])]);
      if (t['arguments'] != null) kw.push(['arguments', pyStr(t['arguments'])]);
      return renderInline('ToolUse', kw);
    });
    return renderList(items, indent);
  }

  private usage(v: unknown, indent: number, at: string): string {
    void indent;
    this.use('Usage');
    const o = v as Json;
    const fields: [string, string][] = [
      ['inputTokens', 'input_tokens'], ['outputTokens', 'output_tokens'],
      ['cachedInputTokens', 'cached_input_tokens'], ['cacheCreationTokens', 'cache_creation_tokens'],
      ['reasoningTokens', 'reasoning_tokens'],
    ];
    this.known(at, o, fields.map(([wire]) => wire));
    const kw: Kw[] = [];
    for (const [wire, py] of fields) {
      if (o[wire] != null) kw.push([py, pyNum(o[wire])]);
    }
    return renderInline('Usage', kw);
  }

  private streamingPhysics(v: unknown, indent: number, at: string): string {
    const N = (x: unknown) => pyNum(x);
    const B = (x: unknown) => pyBool(x);
    return this.typed('StreamingPhysics', v as Json, [
      ['timeToFirstToken', 'time_to_first_token', (x, _i, p) => this.delay(x, p)],
      ['tokensPerSecond', 'tokens_per_second', N],
      ['jitter', 'jitter', N],
      ['seed', 'seed', N],
      ['subwordStreaming', 'subword_streaming', B],
    ], indent, at);
  }

  private conversationPredicates(v: unknown, indent: number, at: string): string {
    const S = (x: unknown) => pyStr(x);
    const N = (x: unknown) => pyNum(x);
    return this.typed('ConversationPredicates', v as Json, [
      ['turnIndex', 'turn_index', N],
      ['latestMessageContains', 'latest_message_contains', S],
      ['latestMessageMatches', 'latest_message_matches', S],
      ['latestMessageRole', 'latest_message_role', S],
      ['containsToolResultFor', 'contains_tool_result_for', S],
      ['semanticMatchAgainst', 'semantic_match_against', S],
      ['normalization', 'normalization', (x, i, p) => this.normalization(x, i, p)],
    ], indent, at);
  }

  private normalization(v: unknown, indent: number, at: string): string {
    const B = (x: unknown) => pyBool(x);
    const SL = (x: unknown) => strArray(x);
    return this.typed('NormalizationOptions', v as Json, [
      ['collapseWhitespace', 'collapse_whitespace', B],
      ['lowercase', 'lowercase', B],
      ['sortJsonKeys', 'sort_json_keys', B],
      ['dropBuiltInVolatileFields', 'drop_built_in_volatile_fields', B],
      ['dropVolatileFields', 'drop_volatile_fields', SL],
    ], indent, at);
  }

  private embedding(v: unknown, indent: number, at: string): string {
    const N = (x: unknown) => pyNum(x);
    const B = (x: unknown) => pyBool(x);
    return this.typed('EmbeddingResponse', v as Json, [
      ['dimensions', 'dimensions', N],
      ['deterministicFromInput', 'deterministic_from_input', B],
      ['seed', 'seed', N],
    ], indent, at);
  }

  private rerank(v: unknown, indent: number, at: string): string {
    const N = (x: unknown) => pyNum(x);
    const B = (x: unknown) => pyBool(x);
    return this.typed('RerankResponse', v as Json, [
      ['topN', 'top_n', N],
      ['deterministicFromInput', 'deterministic_from_input', B],
      ['seed', 'seed', N],
    ], indent, at);
  }

  private moderation(v: unknown, indent: number, at: string): string {
    const S = (x: unknown) => pyStr(x);
    const SL = (x: unknown) => strArray(x);
    return this.typed('ModerationResponse', v as Json, [
      ['flaggedCategories', 'flagged_categories', SL],
      ['model', 'model', S],
    ], indent, at);
  }

  private contentFilter(v: unknown, indent: number, at: string): string {
    const S = (x: unknown) => pyStr(x);
    return this.typed('ContentFilter', v as Json, [
      ['hate', 'hate', S], ['sexual', 'sexual', S], ['violence', 'violence', S], ['selfHarm', 'self_harm', S],
    ], indent, at);
  }

  private llmChaos(v: unknown, indent: number, at: string): string {
    const S = (x: unknown) => pyStr(x);
    const N = (x: unknown) => pyNum(x);
    const B = (x: unknown) => pyBool(x);
    return this.typed('LlmChaosProfile', v as Json, [
      ['errorStatus', 'error_status', N],
      ['retryAfter', 'retry_after', S],
      ['errorProbability', 'error_probability', N],
      ['truncateMode', 'truncate_mode', S],
      ['truncateAtFraction', 'truncate_at_fraction', N],
      ['malformedSse', 'malformed_sse', B],
      ['seed', 'seed', N],
      ['quotaName', 'quota_name', S],
      ['quotaLimit', 'quota_limit', N],
      ['quotaWindowMillis', 'quota_window_millis', N],
      ['quotaErrorStatus', 'quota_error_status', N],
      ['tokenQuotaLimit', 'token_quota_limit', N],
      ['tokenQuotaWindowMillis', 'token_quota_window_millis', N],
      ['contentFilterBlockProbability', 'content_filter_block_probability', N],
      ['errorKind', 'error_kind', S],
    ], indent, at);
  }

  // --- Non-LLM edit-preserved siblings / actions ----------------------------

  private httpResponses(v: unknown, indent: number): string {
    const items = (v as Json[]).map((r, n) => this.response(r, indent + 4, `httpResponses[${n}]`));
    return renderList(items, indent);
  }

  private rateLimit(v: unknown, indent: number, at: string): string {
    const S = (x: unknown) => pyStr(x);
    const N = (x: unknown) => pyNum(x);
    return this.typed('RateLimit', v as Json, [
      ['name', 'name', S],
      ['algorithm', 'algorithm', S],
      ['limit', 'limit', N],
      ['windowMillis', 'window_millis', N],
      ['burst', 'burst', N],
      ['refillPerSecond', 'refill_per_second', N],
      ['errorStatus', 'error_status', N],
      ['retryAfter', 'retry_after', S],
    ], indent, at);
  }

  private crossProtocol(v: unknown, indent: number): string {
    const S = (x: unknown) => pyStr(x);
    const items = (v as Json[]).map((c, n) => this.typed('CrossProtocolScenario', c, [
      ['trigger', 'trigger', S],
      ['matchPattern', 'match_pattern', S],
      ['scenarioName', 'scenario_name', S],
      ['targetState', 'target_state', S],
    ], indent + 4, `crossProtocolScenarios[${n}]`));
    return renderList(items, indent);
  }

  private objectCallback(v: unknown, indent: number, at: string): string {
    const S = (x: unknown) => pyStr(x);
    const B = (x: unknown) => pyBool(x);
    return this.typed('HttpObjectCallback', v as Json, [
      ['clientId', 'client_id', S],
      ['responseCallback', 'response_callback', B],
      ['delay', 'delay', (x, _i, p) => this.delay(x, p)],
      ['primary', 'primary', B],
    ], indent, at);
  }

  private forwardValidate(v: unknown, indent: number, at: string): string {
    const S = (x: unknown) => pyStr(x);
    const N = (x: unknown) => pyNum(x);
    const B = (x: unknown) => pyBool(x);
    return this.typed('HttpForwardValidateAction', v as Json, [
      ['specUrlOrPayload', 'spec_url_or_payload', S],
      ['host', 'host', S],
      ['port', 'port', N],
      ['scheme', 'scheme', S],
      ['validateRequest', 'validate_request', B],
      ['validateResponse', 'validate_response', B],
      ['validationMode', 'validation_mode', S],
      ['delay', 'delay', (x, _i, p) => this.delay(x, p)],
      ['primary', 'primary', B],
    ], indent, at);
  }

  private grpcMessages(v: unknown, indent: number, at: string): string {
    this.use('GrpcStreamMessage');
    const items = (v as Json[]).map((m, n) => {
      this.known(`${at}[${n}]`, m, ['json', 'delay', 'templateType']);
      const mk: Kw[] = [['json', pyStr(m['json'])]];
      if (m['delay'] != null) mk.push(['delay', this.delay(m['delay'], `${at}[${n}].delay`)]);
      if (m['templateType'] != null) mk.push(['template_type', pyStr(m['templateType'])]);
      return renderInline('GrpcStreamMessage', mk);
    });
    return renderList(items, indent);
  }

  private grpcBidi(v: unknown, indent: number, at: string): string {
    const S = (x: unknown) => pyStr(x);
    const B = (x: unknown) => pyBool(x);
    return this.typed('GrpcBidiResponse', v as Json, [
      ['statusName', 'status_name', S],
      ['statusMessage', 'status_message', S],
      ['headers', 'headers', (x, i) => this.keyMultiList(x, i)],
      ['messages', 'messages', (x, i, p) => this.grpcMessages(x, i, p)],
      ['rules', 'rules', (x, i, p) => this.grpcBidiRules(x, i, p)],
      ['closeConnection', 'close_connection', B],
      ['delay', 'delay', (x, _i, p) => this.delay(x, p)],
      ['primary', 'primary', B],
    ], indent, at);
  }

  private grpcBidiRules(v: unknown, indent: number, at: string): string {
    const items = (v as Json[]).map((r, n) => this.typed('GrpcBidiRule', r, [
      ['matchJson', 'match_json', (x) => pyStr(x)],
      ['responses', 'responses', (x, i, p) => this.grpcMessages(x, i, p)],
    ], indent + 4, `${at}[${n}]`));
    return renderList(items, indent);
  }

  private capture(v: unknown, indent: number): string {
    this.use('CaptureRule');
    const items = (v as Json[]).map((c) =>
      renderInline('CaptureRule', [
        ['source', pyStr(c['source'])],
        ['expression', pyStr(c['expression'])],
        ['into', pyStr(c['into'])],
      ]),
    );
    return renderList(items, indent);
  }

  private times(v: unknown): string {
    this.use('Times');
    const o = v as Json;
    const kw: Kw[] = [];
    if (o['remainingTimes'] != null) kw.push(['remaining_times', pyNum(o['remainingTimes'])]);
    if (o['unlimited'] != null) kw.push(['unlimited', pyBool(o['unlimited'])]);
    return renderInline('Times', kw);
  }

  private ttl(v: unknown): string {
    this.use('TimeToLive');
    const o = v as Json;
    const kw: Kw[] = [];
    if (o['timeUnit'] != null) kw.push(['time_unit', pyStr(o['timeUnit'])]);
    if (o['timeToLive'] != null) kw.push(['time_to_live', pyNum(o['timeToLive'])]);
    if (o['unlimited'] != null) kw.push(['unlimited', pyBool(o['unlimited'])]);
    return renderInline('TimeToLive', kw);
  }

  /** Build the ordered kwargs of the top-level Expectation(...) constructor. */
  expectation(json: Json, indent: number): Kw[] {
    this.use('Expectation');
    const kw: Kw[] = [];
    const emit = (wire: string, py: string, fn: (x: unknown, i: number, at: string) => string) => {
      if (json[wire] != null) kw.push([py, fn(json[wire], indent + 4, wire)]);
    };

    // Request matcher first, then the (mutually-exclusive) action, then modifiers —
    // matching the reading order of the website examples.
    emit('httpRequest', 'http_request', (x, i) => this.requestOrDns(x as Json, i));
    emit('httpResponse', 'http_response', (x, i, p) => this.response(x, i, p));
    emit('httpForward', 'http_forward', (x, i, p) => this.forward(x, i, p));
    emit('httpOverrideForwardedRequest', 'http_override_forwarded_request', (x, i, p) => this.override(x, i, p));
    emit('httpResponseClassCallback', 'http_response_class_callback', (x, i, p) => this.classCallback(x, i, p));
    emit('httpResponseTemplate', 'http_response_template', (x, i, p) => this.template(x, i, p));
    emit('httpError', 'http_error', (x, i, p) => this.error(x, i, p));
    emit('httpForwardWithFallback', 'http_forward_with_fallback', (x, i, p) => this.forwardWithFallback(x, i, p));
    emit('httpWebSocketResponse', 'http_websocket_response', (x, i, p) => this.webSocket(x, i, p));
    emit('httpSseResponse', 'http_sse_response', (x, i, p) => this.sse(x, i, p));
    emit('binaryResponse', 'binary_response', (x, i, p) => this.binary(x, i, p));
    emit('dnsResponse', 'dns_response', (x, i, p) => this.dnsResponse(x, i, p));
    emit('httpForwardTemplate', 'http_forward_template', (x, i, p) => this.template(x, i, p));
    emit('httpForwardClassCallback', 'http_forward_class_callback', (x, i, p) => this.classCallback(x, i, p));
    emit('grpcStreamResponse', 'grpc_stream_response', (x, i, p) => this.grpc(x, i, p));
    // Edit-preserved actions the standard composer form cannot model but an edit
    // overlay carries through verbatim — emitted TYPED, never dropped or blobbed.
    emit('httpLlmResponse', 'http_llm_response', (x, i, p) => this.llm(x, i, p));
    emit('httpResponses', 'http_responses', (x, i) => this.httpResponses(x, i));
    emit('httpResponseObjectCallback', 'http_response_object_callback', (x, i, p) => this.objectCallback(x, i, p));
    emit('httpForwardObjectCallback', 'http_forward_object_callback', (x, i, p) => this.objectCallback(x, i, p));
    emit('httpForwardValidateAction', 'http_forward_validate_action', (x, i, p) => this.forwardValidate(x, i, p));
    emit('grpcBidiResponse', 'grpc_bidi_response', (x, i, p) => this.grpcBidi(x, i, p));
    emit('steps', 'steps', (x, i) => this.steps(x, i));
    emit('beforeActions', 'before_actions', (x, i, p) => this.actions(x, i, p));
    emit('afterActions', 'after_actions', (x, i, p) => this.actions(x, i, p));
    emit('chaos', 'chaos', (x, i) => this.chaos(x, i));
    emit('capture', 'capture', (x, i) => this.capture(x, i));
    emit('scenarioName', 'scenario_name', (x) => pyStr(x));
    emit('scenarioState', 'scenario_state', (x) => pyStr(x));
    emit('newScenarioState', 'new_scenario_state', (x) => pyStr(x));
    emit('id', 'id', (x) => pyStr(x));
    emit('priority', 'priority', (x) => pyNum(x));
    emit('percentage', 'percentage', (x) => pyNum(x));
    emit('responseMode', 'response_mode', (x) => pyStr(x));
    emit('responseWeights', 'response_weights', (x) => intArray(x));
    emit('switchAfter', 'switch_after', (x) => pyNum(x));
    emit('rateLimit', 'rate_limit', (x, i, p) => this.rateLimit(x, i, p));
    emit('crossProtocolScenarios', 'cross_protocol_scenarios', (x, i) => this.crossProtocol(x, i));
    emit('times', 'times', (x) => this.times(x));
    emit('timeToLive', 'time_to_live', (x) => this.ttl(x));
    emit('namespace', 'namespace', (x) => pyStr(x));
    emit('metadata', 'metadata', (x, i) => toPythonLiteral(x, i));
    emit('timestamp', 'timestamp', (x) => pyStr(x));
    return kw;
  }
}

/** Top-level names that are re-exported from the `mockserver` package. Everything
 *  this emitter references is importable directly from `mockserver`. */
export function standardToPython(matcher: StandardMatcher, action: StandardActionPayload, baseUrl: string): string {
  const { host, port } = clientHostPort(baseUrl);
  const json = buildExpectationJson(matcher, action) as Json;

  const builder = new PyBuilder();
  const kwargs = builder.expectation(json, 4);
  const expectation = renderCall('Expectation', kwargs, 4);

  const names = ['MockServerClient', ...builder.imports];
  const importBlock = renderImports(names);
  const note = builder.omitted.note('#', 'Python');

  return [
    ...(builder.usesBase64 ? ['import base64', ''] : []),
    importBlock,
    '',
    `MockServerClient("${host}", ${port}).upsert(`,
    `    ${expectation}`,
    ')',
    ...(note ? [note] : []),
  ].join('\n');
}

/** Render the `from mockserver import (...)` block, one sorted name per line. */
function renderImports(names: string[]): string {
  const sorted = Array.from(new Set(names)).sort((a, b) => a.localeCompare(b));
  if (sorted.length <= 3) {
    return `from mockserver import ${sorted.join(', ')}`;
  }
  return `from mockserver import (\n${sorted.map((n) => `    ${n},`).join('\n')}\n)`;
}
