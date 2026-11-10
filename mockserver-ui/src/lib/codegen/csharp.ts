/**
 * C# client-library emitter.
 *
 * Emits idiomatic, fully-typed MockServer.Client construction — an
 * `Expectation` object initializer whose request/response/action graph is built
 * from the typed model classes and fluent `WithX` builders (mirrors the website
 * examples) — instead of the old approach of `JsonSerializer.Deserialize`-ing an
 * embedded JSON blob.
 *
 * The emitter is a pure transducer over the wire JSON produced by
 * {@link buildExpectationJson}: it walks that object and renders the equivalent
 * typed C#. Every feature the composer (or an edit overlay) can express is
 * covered — all 14 action families, every request-matcher field (jwt / allOf /
 * dns / path parameters / …), chaos, capture, scenario, before/after actions,
 * step pipelines, response sequences, priority/times/ttl and namespace.
 *
 * Fidelity: for the small number of edit-only fragments the composer never
 * authors (LLM / gRPC-bidi / rate-limit / validate / object-callback actions,
 * and any exotic request/response sub-field), the emitter degrades to a typed
 * per-fragment `JsonSerializer.Deserialize<T>(...)` assigned to the correct
 * strongly-typed property — never a whole-`Expectation` blob — so nothing is
 * ever silently dropped.
 *
 * Two representations are server-accepted equivalences but not byte-identical to
 * {@link buildExpectationJson} because the .NET client models them differently
 * (both are documented, server-side-aliased, and excused in the client's own
 * round-trip fidelity ledger):
 *   1. a JSON body matcher's `json` is emitted as a string (the client's
 *      `Body.OfJson` convention) rather than an inline JSON object;
 *   2. an override-forwarded-request's request override serialises under
 *      `httpRequest` (the server `@JsonAlias`) rather than `requestOverride`.
 */
import { buildExpectationJson, type StandardMatcher, type StandardActionPayload } from '../standardCodegen.ts';
import { clientHostPort } from './shared.ts';
import { dateTimeBuilderExpr, dateTimeMatcherOf, nottableObjectOf } from '../matcherValue.ts';

// ---------------------------------------------------------------------------
// Small literal / formatting helpers
// ---------------------------------------------------------------------------

/** A C# regular (escaped) string literal. */
function csStr(s: string): string {
  return '"' + s
    // a lone surrogate is not valid UTF-8 source text, so it becomes U+FFFD
    .replace(/\p{Cs}/gu, '\ufffd')
    .replace(/\\/g, '\\\\')
    .replace(/"/g, '\\"')
    .replace(/\n/g, '\\n')
    .replace(/\r/g, '\\r')
    .replace(/\t/g, '\\t')
    // C# ends a regular string literal at NEL, LS and PS too, and other control characters are unreadable raw
    .replace(/[\p{Cc}\u2028\u2029]/gu, (c) => '\\u' + c.charCodeAt(0).toString(16).padStart(4, '0')) + '"';
}

/** A C# verbatim string literal (@"...") — used to carry raw JSON fragments. */
function csVerbatim(s: string): string {
  return '@"' + s.replace(/"/g, '""') + '"';
}

function csNumber(n: number): string {
  return String(n);
}

function stableJson(v: unknown): string {
  return JSON.stringify(v);
}

function pad(n: number): string {
  return ' '.repeat(n);
}

interface Ctx {
  usings: Set<string>;
}

/**
 * Render an object initializer `new Type { ... }`. Braces align at `indent`;
 * each property line sits at `indent + 4`. Property values must already have
 * been rendered at `indent + 4` so their continuation lines align.
 */
function csObjectInit(type: string, propLines: string[], indent: number): string {
  if (propLines.length === 0) return `new ${type}()`;
  const p = pad(indent);
  const pIn = pad(indent + 4);
  return `new ${type}\n${p}{\n` + propLines.map((l) => pIn + l + ',').join('\n') + `\n${p}}`;
}

/** Render a fluent builder chain: `head` then each call on its own `indent + 4` line. */
function csFluent(head: string, calls: string[], indent: number): string {
  if (calls.length === 0) return head;
  const pIn = pad(indent + 4);
  return head + '\n' + calls.map((c) => pIn + c).join('\n');
}

/** Render a `new Dictionary<...> { ["k"] = <v>, ... }` collection initializer. */
function csDict(typeGeneric: string, entryLines: string[], indent: number): string {
  if (entryLines.length === 0) return `new ${typeGeneric}()`;
  const p = pad(indent);
  const pIn = pad(indent + 4);
  return `new ${typeGeneric}\n${p}{\n` + entryLines.map((l) => pIn + l + ',').join('\n') + `\n${p}}`;
}

function isObject(v: unknown): v is Record<string, unknown> {
  return !!v && typeof v === 'object' && !Array.isArray(v);
}

// ---------------------------------------------------------------------------
// Value renderers
// ---------------------------------------------------------------------------

/** `new() { "a", "b" }` list-of-strings initializer (single line). */
function renderStringList(values: unknown[]): string {
  return `new() { ${values.map((v) => csStr(String(v))).join(', ')} }`;
}

/** keyToMultiValue: `Dictionary<string, List<string>>`. */
function renderMultiMap(obj: Record<string, unknown>, indent: number): string {
  const entries = Object.entries(obj).map(([k, v]) => {
    const arr = Array.isArray(v) ? v : [v];
    return `[${csStr(k)}] = ${renderStringList(arr)}`;
  });
  return csDict('Dictionary<string, List<string>>', entries, indent);
}

/** keyToValue: `Dictionary<string, string>`. */
function renderSingleMap(obj: Record<string, unknown>, indent: number): string {
  const entries = Object.entries(obj).map(([k, v]) => `[${csStr(k)}] = ${csStr(String(v))}`);
  return csDict('Dictionary<string, string>', entries, indent);
}

/** pathParameters: `Dictionary<string, List<JsonElement>>` (values are JSON-serialised strings). */
function renderPathParameters(obj: Record<string, unknown>, indent: number, ctx: Ctx): string {
  ctx.usings.add('System.Text.Json');
  const entries = Object.entries(obj).map(([k, v]) => {
    const arr = Array.isArray(v) ? v : [v];
    const els = arr.map((x) => (isObject(x)
      ? `JsonSerializer.Deserialize<JsonElement>(${csVerbatim(stableJson(x))})`
      : `JsonSerializer.SerializeToElement(${csStr(String(x))})`)).join(', ');
    return `[${csStr(k)}] = new() { ${els} }`;
  });
  return csDict('Dictionary<string, List<JsonElement>>', entries, indent);
}

/** A `MatcherValue` expression; undefined for a form MatcherValue cannot hold (a schema). A plain
 *  string relies on the implicit conversion, which parses it as the server would ("!foo" is not foo). */
function csMatcherValue(v: unknown): string | undefined {
  if (!isObject(v)) return csStr(String(v));
  const dt = dateTimeMatcherOf(v);
  if (dt) return dateTimeBuilderExpr(dt, { head: 'MatcherValue.', naming: 'pascal', str: csStr, bool: (b) => (b ? 'true' : 'false') });
  const n = nottableObjectOf(v);
  if (!n) return undefined;
  return `new MatcherValue(${csStr(n.value)}${n.not ? ', not: true' : ''}${n.optional ? ', optional: true' : ''})`;
}

function hasObjectValue(obj: Record<string, unknown>): boolean {
  return Object.values(obj).some((v) => (Array.isArray(v) ? v : [v]).some(isObject));
}

/** The `*Matchers` dictionary for a header / query / cookie / path-parameter map; undefined when a value
 *  is a form MatcherValue cannot hold. */
function renderMatcherMap(obj: Record<string, unknown>, single: boolean, indent: number): string | undefined {
  const entries: string[] = [];
  for (const [k, v] of Object.entries(obj)) {
    const exprs = (single ? [v] : Array.isArray(v) ? v : [v]).map(csMatcherValue);
    if (exprs.some((e) => e === undefined)) return undefined;
    entries.push(single ? `[${csStr(k)}] = ${exprs[0]}` : `[${csStr(k)}] = new() { ${exprs.join(', ')} }`);
  }
  return csDict(single ? 'Dictionary<string, MatcherValue>' : 'Dictionary<string, List<MatcherValue>>', entries, indent);
}

/** A `new Delay { TimeUnit = TimeUnit.X, Value = n }` (single line). */
function renderDelay(delay: Record<string, unknown>): string {
  const unit = typeof delay['timeUnit'] === 'string' ? (delay['timeUnit'] as string) : 'MILLISECONDS';
  const value = typeof delay['value'] === 'number' ? (delay['value'] as number) : 0;
  return `new Delay { TimeUnit = TimeUnit.${unit}, Value = ${csNumber(value)} }`;
}

/**
 * `.WithDateTime(MatcherValue.After(..))` for a JSONPath / XPath body's date-time comparison, empty
 * without one, or null when it holds a shape the builder cannot express (the caller falls back).
 */
function csBodyDateTime(body: Record<string, unknown>): string | null {
  if (body['dateTime'] == null) return '';
  const dt = dateTimeMatcherOf(body['dateTime']);
  if (!dt) return null;
  return `.WithDateTime(${dateTimeBuilderExpr(dt, { head: 'MatcherValue.', naming: 'pascal', str: csStr, bool: (b) => (b ? 'true' : 'false') })})`;
}

/**
 * Render a request/response body value into a typed C# expression, or null when
 * the shape is not one this emitter models typed (the caller then falls back to
 * a whole-object typed Deserialize).
 */
function renderBody(body: unknown, indent: number): string | null {
  if (typeof body === 'string') return csStr(body);
  if (!isObject(body)) return null;
  const type = body['type'];
  if (typeof type !== 'string') return null;

  const jsonArg = (v: unknown): string => (typeof v === 'string' ? v : stableJson(v));

  switch (type) {
    case 'STRING': {
      const s = typeof body['string'] === 'string' ? (body['string'] as string) : '';
      if (body['subString'] === true) return `Body.OfString(${csStr(s)}, true)`;
      return `Body.OfString(${csStr(s)})`;
    }
    case 'JSON': {
      const arg = csStr(jsonArg(body['json']));
      if (body['matchType'] === 'STRICT') return `Body.OfJson(${arg}, "STRICT")`;
      return `Body.OfJson(${arg})`;
    }
    case 'JSON_SCHEMA':
      return `Body.OfJsonSchema(${csStr(String(body['jsonSchema'] ?? ''))})`;
    case 'JSON_PATH': {
      const dateTime = csBodyDateTime(body);
      return dateTime === null ? null : `Body.OfJsonPath(${csStr(String(body['jsonPath'] ?? ''))})${dateTime}`;
    }
    case 'XML':
      return `Body.OfXml(${csStr(String(body['xml'] ?? ''))})`;
    case 'XML_SCHEMA':
      return `Body.OfXmlSchema(${csStr(String(body['xmlSchema'] ?? ''))})`;
    case 'XPATH': {
      const dateTime = csBodyDateTime(body);
      return dateTime === null ? null : `Body.OfXPath(${csStr(String(body['xpath'] ?? ''))})${dateTime}`;
    }
    case 'REGEX':
      return `Body.OfRegex(${csStr(String(body['regex'] ?? ''))})`;
    case 'BINARY':
      return `Body.OfBinary(${csStr(String(body['base64Bytes'] ?? ''))})`;
    case 'WASM':
      return `Body.OfWasm(${csStr(String(body['moduleName'] ?? ''))})`;
    case 'PARAMETERS': {
      const params = isObject(body['parameters']) ? (body['parameters'] as Record<string, unknown>) : {};
      return `Body.OfParameters(${renderMultiMap(params, indent)})`;
    }
    case 'GRAPHQL': {
      // OfGraphQl only sets the query; selection-set/fields need the object form.
      if (body['selectionSetMatchType'] == null && body['fields'] == null) {
        return `Body.OfGraphQl(${csStr(String(body['query'] ?? ''))})`;
      }
      const props: string[] = ['Type = "GRAPHQL"', `Query = ${csStr(String(body['query'] ?? ''))}`];
      if (typeof body['selectionSetMatchType'] === 'string') {
        props.push(`SelectionSetMatchType = ${csStr(body['selectionSetMatchType'] as string)}`);
      }
      if (Array.isArray(body['fields'])) {
        props.push(`GraphQlFields = ${renderStringList(body['fields'] as unknown[])}`);
      }
      return csObjectInit('Body', props, indent);
    }
    case 'FILE': {
      const props: string[] = [];
      if (typeof body['filePath'] === 'string') props.push(`FilePath = ${csStr(body['filePath'] as string)}`);
      if (typeof body['contentType'] === 'string') props.push(`ContentType = ${csStr(body['contentType'] as string)}`);
      if (typeof body['templateType'] === 'string') props.push(`TemplateType = FileTemplateType.${body['templateType']}`);
      if (body['templatedFilePath'] === true) props.push('TemplatedFilePath = true');
      return csObjectInit('FileBody', props, indent);
    }
    case 'ALL_OF': {
      const subs = Array.isArray(body['bodyAllOf']) ? (body['bodyAllOf'] as unknown[]) : [];
      const rendered = subs.map((s) => renderAllOfSub(s));
      if (rendered.some((r) => r === null)) return null;
      const pIn = pad(indent + 4);
      return 'Body.OfAllOf(\n' + rendered.map((r) => pIn + r).join(',\n') + ')';
    }
    default:
      return null;
  }
}

/** Render one ALL_OF sub-matcher (a subset of body types) into a `Body.OfX(...)` factory call. */
function renderAllOfSub(sub: unknown): string | null {
  if (!isObject(sub)) return null;
  const type = sub['type'];
  const jsonArg = (v: unknown): string => (typeof v === 'string' ? v : stableJson(v));
  switch (type) {
    case 'STRING':
      return `Body.OfString(${csStr(String(sub['string'] ?? ''))})`;
    case 'JSON':
      return `Body.OfJson(${csStr(jsonArg(sub['json']))})`;
    case 'JSON_SCHEMA':
      return `Body.OfJsonSchema(${csStr(String(sub['jsonSchema'] ?? ''))})`;
    case 'JSON_PATH': {
      const dateTime = csBodyDateTime(sub);
      return dateTime === null ? null : `Body.OfJsonPath(${csStr(String(sub['jsonPath'] ?? ''))})${dateTime}`;
    }
    case 'XML':
      return `Body.OfXml(${csStr(String(sub['xml'] ?? ''))})`;
    case 'XML_SCHEMA':
      return `Body.OfXmlSchema(${csStr(String(sub['xmlSchema'] ?? ''))})`;
    case 'XPATH': {
      const dateTime = csBodyDateTime(sub);
      return dateTime === null ? null : `Body.OfXPath(${csStr(String(sub['xpath'] ?? ''))})${dateTime}`;
    }
    case 'REGEX':
      return `Body.OfRegex(${csStr(String(sub['regex'] ?? ''))})`;
    default:
      return null;
  }
}

/**
 * `Dictionary<string, object?>` metadata: scalars as C# literals, objects and arrays as a
 * `JsonElement` parsed from their JSON text (System.Text.Json writes a JsonElement verbatim).
 */
function renderMetadata(metadata: Record<string, unknown>, indent: number, ctx: Ctx): string {
  const entries = Object.entries(metadata).map(([k, v]) => {
    let value: string;
    if (v === null || v === undefined) value = 'null';
    else if (typeof v === 'string') value = csStr(v);
    else if (typeof v === 'boolean') value = v ? 'true' : 'false';
    else if (typeof v === 'number') value = csNumber(v);
    else value = rawTyped('JsonElement', v, ctx);
    return `[${csStr(k)}] = ${value}`;
  });
  return csDict('Dictionary<string, object?>', entries, indent);
}

/** Deserialize a fragment into a typed model (fidelity fallback for shapes not modelled inline). */
function rawTyped(type: string, value: unknown, ctx: Ctx): string {
  ctx.usings.add('System.Text.Json');
  return `JsonSerializer.Deserialize<${type}>(${csVerbatim(stableJson(value))})`;
}

// ---------------------------------------------------------------------------
// Request matcher
// ---------------------------------------------------------------------------

const KNOWN_REQUEST_KEYS = new Set([
  'method', 'path', 'headers', 'queryStringParameters', 'cookies', 'pathParameters',
  'body', 'jwt', 'secure', 'dnsName', 'dnsType', 'dnsClass', 'keepAlive', 'clientIp',
]);

function renderJwt(jwt: Record<string, unknown>, indent: number): string {
  const props: string[] = [];
  if (typeof jwt['header'] === 'string') props.push(`Header = ${csStr(jwt['header'] as string)}`);
  if (typeof jwt['scheme'] === 'string') props.push(`Scheme = ${csStr(jwt['scheme'] as string)}`);
  if (isObject(jwt['claims'])) {
    props.push(`Claims = ${renderSingleMap(jwt['claims'] as Record<string, unknown>, indent + 4)}`);
  }
  if (typeof jwt['issuer'] === 'string') props.push(`Issuer = ${csStr(jwt['issuer'] as string)}`);
  if (typeof jwt['audience'] === 'string') props.push(`Audience = ${csStr(jwt['audience'] as string)}`);
  if (typeof jwt['algorithm'] === 'string') props.push(`Algorithm = ${csStr(jwt['algorithm'] as string)}`);
  return csObjectInit('Jwt', props, indent);
}

function renderRequest(req: Record<string, unknown>, indent: number, ctx: Ctx): string {
  let bodyExpr: string | null = null;
  if ('body' in req) {
    bodyExpr = renderBody(req['body'], indent + 4);
    if (bodyExpr === null) return rawTyped('HttpRequest', req, ctx);
  }
  if (Object.keys(req).some((k) => !KNOWN_REQUEST_KEYS.has(k)) || ('clientIp' in req && typeof req['clientIp'] !== 'string')) {
    return rawTyped('HttpRequest', req, ctx);
  }

  const props: string[] = [];
  if (typeof req['method'] === 'string') props.push(`Method = ${csStr(req['method'] as string)}`);
  if (typeof req['path'] === 'string') props.push(`Path = ${csStr(req['path'] as string)}`);
  // A map holding an object value (date-time / nottable matcher) goes through the *Matchers property,
  // which replaces the plain map on the wire.
  const matcherMaps: [string, string, string, boolean, (o: Record<string, unknown>) => string][] = [
    ['headers', 'Headers', 'HeaderMatchers', false, (o) => renderMultiMap(o, indent + 4)],
    ['queryStringParameters', 'QueryStringParameters', 'QueryStringParameterMatchers', false, (o) => renderMultiMap(o, indent + 4)],
    ['cookies', 'Cookies', 'CookieMatchers', true, (o) => renderSingleMap(o, indent + 4)],
    ['pathParameters', 'PathParameters', 'PathParameterMatchers', false, (o) => renderPathParameters(o, indent + 4, ctx)],
  ];
  for (const [wire, prop, matcherProp, single, plain] of matcherMaps) {
    const map = req[wire];
    if (!isObject(map)) continue;
    const matchers = hasObjectValue(map) ? renderMatcherMap(map, single, indent + 4) : undefined;
    if (matchers !== undefined) {
      props.push(`${matcherProp} = ${matchers}`);
    } else if (hasObjectValue(map) && wire !== 'pathParameters') {
      // a schema value: only the typed deserializer can carry it
      return rawTyped('HttpRequest', req, ctx);
    } else {
      props.push(`${prop} = ${plain(map)}`);
    }
  }
  if (typeof req['dnsName'] === 'string') props.push(`DnsName = ${csStr(req['dnsName'] as string)}`);
  if (typeof req['dnsType'] === 'string') props.push(`DnsType = ${csStr(req['dnsType'] as string)}`);
  if (typeof req['dnsClass'] === 'string') props.push(`DnsClass = ${csStr(req['dnsClass'] as string)}`);
  if (bodyExpr !== null) props.push(`Body = ${bodyExpr}`);
  if (isObject(req['jwt'])) props.push(`Jwt = ${renderJwt(req['jwt'] as Record<string, unknown>, indent + 4)}`);
  // typeof, not === true: secure:false is an HTTP-only matcher, not an absent field
  if (typeof req['secure'] === 'boolean') props.push(`Secure = ${req['secure'] ? 'true' : 'false'}`);
  if (typeof req['keepAlive'] === 'boolean') props.push(`KeepAlive = ${req['keepAlive'] ? 'true' : 'false'}`);
  if (typeof req['clientIp'] === 'string') props.push(`ClientIp = ${csStr(req['clientIp'] as string)}`);
  return csObjectInit('HttpRequest', props, indent);
}

// ---------------------------------------------------------------------------
// Response
// ---------------------------------------------------------------------------

const KNOWN_RESPONSE_KEYS = new Set([
  'statusCode', 'reasonPhrase', 'headers', 'cookies', 'body', 'delay', 'connectionOptions',
]);

function renderConnectionOptions(co: Record<string, unknown>): string {
  const props: string[] = [];
  if (typeof co['keepAliveOverride'] === 'boolean') props.push(`KeepAliveOverride = ${co['keepAliveOverride'] ? 'true' : 'false'}`);
  if (typeof co['closeSocket'] === 'boolean') props.push(`CloseSocket = ${co['closeSocket'] ? 'true' : 'false'}`);
  if (typeof co['contentLengthHeaderOverride'] === 'number') props.push(`ContentLengthHeaderOverride = ${csNumber(co['contentLengthHeaderOverride'] as number)}`);
  if (co['suppressContentLengthHeader'] === true) props.push('SuppressContentLengthHeader = true');
  if (co['suppressConnectionHeader'] === true) props.push('SuppressConnectionHeader = true');
  return `new ConnectionOptions { ${props.join(', ')} }`;
}

function renderResponse(resp: Record<string, unknown>, indent: number, ctx: Ctx): string {
  let bodyExpr: string | null = null;
  if ('body' in resp) {
    bodyExpr = renderBody(resp['body'], indent + 4);
    if (bodyExpr === null) return rawTyped('HttpResponse', resp, ctx);
  }
  if (Object.keys(resp).some((k) => !KNOWN_RESPONSE_KEYS.has(k))) {
    return rawTyped('HttpResponse', resp, ctx);
  }

  const props: string[] = [];
  if (typeof resp['statusCode'] === 'number') props.push(`StatusCode = ${csNumber(resp['statusCode'] as number)}`);
  if (typeof resp['reasonPhrase'] === 'string') props.push(`ReasonPhrase = ${csStr(resp['reasonPhrase'] as string)}`);
  if (isObject(resp['headers'])) props.push(`Headers = ${renderMultiMap(resp['headers'] as Record<string, unknown>, indent + 4)}`);
  if (isObject(resp['cookies'])) props.push(`Cookies = ${renderSingleMap(resp['cookies'] as Record<string, unknown>, indent + 4)}`);
  if (bodyExpr !== null) props.push(`Body = ${bodyExpr}`);
  if (isObject(resp['delay'])) props.push(`Delay = ${renderDelay(resp['delay'] as Record<string, unknown>)}`);
  if (isObject(resp['connectionOptions'])) props.push(`ConnectionOptions = ${renderConnectionOptions(resp['connectionOptions'] as Record<string, unknown>)}`);
  return csObjectInit('HttpResponse', props, indent);
}

// ---------------------------------------------------------------------------
// Action families
// ---------------------------------------------------------------------------

function delayCall(d: Record<string, unknown>): string {
  const unit = typeof d['timeUnit'] === 'string' ? (d['timeUnit'] as string) : 'MILLISECONDS';
  const value = typeof d['value'] === 'number' ? (d['value'] as number) : 0;
  return `.WithDelay(TimeUnit.${unit}, ${csNumber(value)})`;
}

function renderForward(fwd: Record<string, unknown>, indent: number, ctx: Ctx): string {
  const known = new Set(['scheme', 'host', 'port', 'delay']);
  if (Object.keys(fwd).some((k) => !known.has(k))) return rawTyped('HttpForward', fwd, ctx);
  const calls: string[] = [];
  if (typeof fwd['scheme'] === 'string') calls.push(`.WithScheme(${csStr(fwd['scheme'] as string)})`);
  if (typeof fwd['host'] === 'string') calls.push(`.WithHost(${csStr(fwd['host'] as string)})`);
  if (typeof fwd['port'] === 'number') calls.push(`.WithPort(${csNumber(fwd['port'] as number)})`);
  if (isObject(fwd['delay'])) calls.push(delayCall(fwd['delay'] as Record<string, unknown>));
  calls.push('.Build()');
  return csFluent('HttpForward.Forward()', calls, indent);
}

function renderError(err: Record<string, unknown>, indent: number, ctx: Ctx): string {
  const known = new Set(['dropConnection', 'resetConnection', 'responseBytes', 'delay']);
  if (Object.keys(err).some((k) => !known.has(k))) return rawTyped('HttpError', err, ctx);
  const calls: string[] = [];
  if (err['dropConnection'] === true) calls.push('.WithDropConnection(true)');
  if (typeof err['resetConnection'] === 'boolean') calls.push(`.WithResetConnection(${err['resetConnection']})`);
  if (typeof err['responseBytes'] === 'string') calls.push(`.WithResponseBytes(${csStr(err['responseBytes'] as string)})`);
  if (isObject(err['delay'])) calls.push(delayCall(err['delay'] as Record<string, unknown>));
  calls.push('.Build()');
  return csFluent('HttpError.Error()', calls, indent);
}

function renderTemplate(tpl: Record<string, unknown>, indent: number, ctx: Ctx): string {
  const known = new Set(['templateType', 'template', 'templateFile', 'delay', 'primary', 'parameters']);
  const parameters = tpl['parameters'];
  if (Object.keys(tpl).some((k) => !known.has(k)) || typeof tpl['templateType'] !== 'string'
    || (parameters !== undefined && !isObject(parameters))) {
    return rawTyped('HttpTemplate', tpl, ctx);
  }
  const calls: string[] = [];
  if (typeof tpl['template'] === 'string') calls.push(`.WithTemplate(${csStr(tpl['template'] as string)})`);
  if (typeof tpl['templateFile'] === 'string') calls.push(`.WithTemplateFile(${csStr(tpl['templateFile'] as string)})`);
  if (isObject(tpl['delay'])) calls.push(delayCall(tpl['delay'] as Record<string, unknown>));
  if (typeof tpl['primary'] === 'boolean') calls.push(`.WithPrimary(${tpl['primary']})`);
  if (isObject(parameters)) {
    for (const [name, value] of Object.entries(parameters as Record<string, unknown>)) {
      calls.push(`.WithParameter(${csStr(name)}, ${csParameterValue(value, ctx)})`);
    }
  }
  calls.push('.Build()');
  return csFluent(`HttpTemplate.OfType(TemplateType.${tpl['templateType']})`, calls, indent);
}

/** A template parameter value: a C# literal for text, numbers, booleans and null, otherwise a parsed JsonElement. */
function csParameterValue(value: unknown, ctx: Ctx): string {
  if (value === null || value === undefined) return 'null';
  if (typeof value === 'string') return csStr(value);
  if (typeof value === 'boolean') return value ? 'true' : 'false';
  if (typeof value === 'number' && Number.isFinite(value)) return Number.isInteger(value) && Math.abs(value) > 2147483647 ? `${value}L` : String(value);
  ctx.usings.add('System.Text.Json');
  return `JsonSerializer.Deserialize<JsonElement>(${csVerbatim(stableJson(value))})`;
}

function renderClassCallback(cb: Record<string, unknown>, indent: number, ctx: Ctx): string {
  const known = new Set(['callbackClass', 'delay']);
  if (Object.keys(cb).some((k) => !known.has(k))) return rawTyped('HttpClassCallback', cb, ctx);
  const props: string[] = [];
  if (typeof cb['callbackClass'] === 'string') props.push(`CallbackClass = ${csStr(cb['callbackClass'] as string)}`);
  if (isObject(cb['delay'])) props.push(`Delay = ${renderDelay(cb['delay'] as Record<string, unknown>)}`);
  return csObjectInit('HttpClassCallback', props, indent);
}

function renderOverrideForwarded(ovr: Record<string, unknown>, indent: number, ctx: Ctx): string {
  // buildExpectationJson wraps the override under `requestOverride`; the .NET model
  // exposes it as `HttpRequest` (server @JsonAlias). Same meaning, different key.
  const known = new Set(['requestOverride', 'httpRequest', 'httpResponse', 'responseTemplate', 'delay', 'primary']);
  if (Object.keys(ovr).some((k) => !known.has(k))) return rawTyped('HttpOverrideForwardedRequest', ovr, ctx);
  const props: string[] = [];
  const reqOverride = ovr['requestOverride'] ?? ovr['httpRequest'];
  if (isObject(reqOverride)) props.push(`HttpRequest = ${renderRequest(reqOverride as Record<string, unknown>, indent + 4, ctx)}`);
  if (isObject(ovr['httpResponse'])) props.push(`HttpResponse = ${renderResponse(ovr['httpResponse'] as Record<string, unknown>, indent + 4, ctx)}`);
  if (isObject(ovr['responseTemplate'])) props.push(`ResponseTemplate = ${renderTemplate(ovr['responseTemplate'] as Record<string, unknown>, indent + 4, ctx)}`);
  if (isObject(ovr['delay'])) props.push(`Delay = ${renderDelay(ovr['delay'] as Record<string, unknown>)}`);
  if (typeof ovr['primary'] === 'boolean') props.push(`Primary = ${ovr['primary']}`);
  return csObjectInit('HttpOverrideForwardedRequest', props, indent);
}

function renderForwardWithFallback(fb: Record<string, unknown>, indent: number, ctx: Ctx): string {
  const known = new Set(['httpForward', 'fallbackResponse', 'fallbackOnStatusCodes', 'fallbackOnTimeout', 'delay']);
  if (Object.keys(fb).some((k) => !known.has(k))) return rawTyped('HttpForwardWithFallback', fb, ctx);
  const props: string[] = [];
  if (isObject(fb['httpForward'])) props.push(`HttpForward = ${renderForward(fb['httpForward'] as Record<string, unknown>, indent + 4, ctx)}`);
  if (isObject(fb['fallbackResponse'])) props.push(`FallbackResponse = ${renderResponse(fb['fallbackResponse'] as Record<string, unknown>, indent + 4, ctx)}`);
  if (Array.isArray(fb['fallbackOnStatusCodes'])) {
    props.push(`FallbackOnStatusCodes = new List<int> { ${(fb['fallbackOnStatusCodes'] as unknown[]).map((n) => csNumber(Number(n))).join(', ')} }`);
  }
  // Emitted whenever present, including `false` — an absent fallbackOnTimeout means TRUE
  // on the server, so dropping an explicit `false` would invert the selection.
  if (typeof fb['fallbackOnTimeout'] === 'boolean') props.push(`FallbackOnTimeout = ${fb['fallbackOnTimeout'] ? 'true' : 'false'}`);
  if (isObject(fb['delay'])) props.push(`Delay = ${renderDelay(fb['delay'] as Record<string, unknown>)}`);
  return csObjectInit('HttpForwardWithFallback', props, indent);
}

function renderWebSocketMessage(msg: Record<string, unknown>, indent: number): string {
  if (typeof msg['text'] === 'string' && Object.keys(msg).length === 1) {
    return `WebSocketMessage.OfText(${csStr(msg['text'] as string)})`;
  }
  const props: string[] = [];
  if (typeof msg['text'] === 'string') props.push(`Text = ${csStr(msg['text'] as string)}`);
  if (typeof msg['binary'] === 'string') props.push(`Binary = ${csStr(msg['binary'] as string)}`);
  if (isObject(msg['delay'])) props.push(`Delay = ${renderDelay(msg['delay'] as Record<string, unknown>)}`);
  return csObjectInit('WebSocketMessage', props, indent);
}

function renderList(
  items: unknown[],
  typeName: string,
  render: (item: Record<string, unknown>, indent: number) => string,
  indent: number,
): string {
  if (items.length === 0) return `new List<${typeName}>()`;
  const p = pad(indent);
  const pIn = pad(indent + 4);
  const lines = items.map((it) => pIn + render(isObject(it) ? (it as Record<string, unknown>) : {}, indent + 4) + ',');
  return `new List<${typeName}>\n${p}{\n` + lines.join('\n') + `\n${p}}`;
}

function renderWsMatcher(m: Record<string, unknown>, indent: number): string {
  const props: string[] = [];
  if (typeof m['frameType'] === 'string') props.push(`FrameType = ${csStr(m['frameType'] as string)}`);
  if (typeof m['textMatcher'] === 'string') props.push(`TextMatcher = ${csStr(m['textMatcher'] as string)}`);
  if (Array.isArray(m['responses'])) {
    props.push(`Responses = ${renderList(m['responses'] as unknown[], 'WebSocketMessage', renderWebSocketMessage, indent + 4)}`);
  }
  return csObjectInit('WebSocketFrameMatcher', props, indent);
}

function renderWebSocket(ws: Record<string, unknown>, indent: number, ctx: Ctx): string {
  const known = new Set(['subprotocol', 'messages', 'matchers', 'closeConnection', 'delay']);
  if (Object.keys(ws).some((k) => !known.has(k))) return rawTyped('HttpWebSocketResponse', ws, ctx);
  const props: string[] = [];
  if (typeof ws['subprotocol'] === 'string') props.push(`Subprotocol = ${csStr(ws['subprotocol'] as string)}`);
  if (Array.isArray(ws['messages'])) {
    props.push(`Messages = ${renderList(ws['messages'] as unknown[], 'WebSocketMessage', renderWebSocketMessage, indent + 4)}`);
  }
  if (Array.isArray(ws['matchers'])) {
    props.push(`Matchers = ${renderList(ws['matchers'] as unknown[], 'WebSocketFrameMatcher', renderWsMatcher, indent + 4)}`);
  }
  // Emitted whenever present, including `false`. An ABSENT closeConnection is not
  // equivalent to `false`: the server reads absent as "close" for WebSocket and SSE
  // (and as "don't close" for gRPC streaming), so dropping an explicit `false` would
  // generate code that closes the connection the user asked to keep open.
  if (typeof ws['closeConnection'] === 'boolean') props.push(`CloseConnection = ${ws['closeConnection'] ? 'true' : 'false'}`);
  if (isObject(ws['delay'])) props.push(`Delay = ${renderDelay(ws['delay'] as Record<string, unknown>)}`);
  return csObjectInit('HttpWebSocketResponse', props, indent);
}

function renderSseEvent(ev: Record<string, unknown>, indent: number): string {
  const props: string[] = [];
  if (typeof ev['event'] === 'string') props.push(`Event = ${csStr(ev['event'] as string)}`);
  if (typeof ev['data'] === 'string') props.push(`Data = ${csStr(ev['data'] as string)}`);
  if (typeof ev['id'] === 'string') props.push(`Id = ${csStr(ev['id'] as string)}`);
  if (typeof ev['retry'] === 'number') props.push(`Retry = ${csNumber(ev['retry'] as number)}`);
  if (typeof ev['comment'] === 'string') props.push(`Comment = ${csStr(ev['comment'] as string)}`);
  return csObjectInit('SseEvent', props, indent);
}

function renderSse(sse: Record<string, unknown>, indent: number, ctx: Ctx): string {
  const known = new Set(['statusCode', 'headers', 'events', 'closeConnection', 'delay']);
  if (Object.keys(sse).some((k) => !known.has(k))) return rawTyped('HttpSseResponse', sse, ctx);
  const props: string[] = [];
  if (typeof sse['statusCode'] === 'number') props.push(`StatusCode = ${csNumber(sse['statusCode'] as number)}`);
  if (isObject(sse['headers'])) props.push(`Headers = ${renderMultiMap(sse['headers'] as Record<string, unknown>, indent + 4)}`);
  if (Array.isArray(sse['events'])) {
    props.push(`Events = ${renderList(sse['events'] as unknown[], 'SseEvent', renderSseEvent, indent + 4)}`);
  }
  // Emitted whenever present, including `false` — see renderWebSocket.
  if (typeof sse['closeConnection'] === 'boolean') props.push(`CloseConnection = ${sse['closeConnection'] ? 'true' : 'false'}`);
  if (isObject(sse['delay'])) props.push(`Delay = ${renderDelay(sse['delay'] as Record<string, unknown>)}`);
  return csObjectInit('HttpSseResponse', props, indent);
}

function renderBinary(bin: Record<string, unknown>, indent: number, ctx: Ctx): string {
  const known = new Set(['binaryData', 'delay', 'upstream', 'primary']);
  if (Object.keys(bin).some((k) => !known.has(k))) return rawTyped('BinaryResponse', bin, ctx);
  const props: string[] = [];
  if (typeof bin['binaryData'] === 'string') props.push(`BinaryData = ${csStr(bin['binaryData'] as string)}`);
  if (isObject(bin['delay'])) props.push(`Delay = ${renderDelay(bin['delay'] as Record<string, unknown>)}`);
  if (typeof bin['upstream'] === 'string') props.push(`Upstream = BinaryUpstream.${bin['upstream']}`);
  if (typeof bin['primary'] === 'boolean') props.push(`Primary = ${bin['primary'] ? 'true' : 'false'}`);
  return csObjectInit('BinaryResponse', props, indent);
}

function renderDnsRecord(rec: Record<string, unknown>, indent: number): string {
  const props: string[] = [];
  if (typeof rec['name'] === 'string') props.push(`Name = ${csStr(rec['name'] as string)}`);
  if (typeof rec['type'] === 'string') props.push(`Type = ${csStr(rec['type'] as string)}`);
  if (typeof rec['dnsClass'] === 'string') props.push(`DnsClass = ${csStr(rec['dnsClass'] as string)}`);
  if (typeof rec['ttl'] === 'number') props.push(`Ttl = ${csNumber(rec['ttl'] as number)}`);
  if (typeof rec['value'] === 'string') props.push(`Value = ${csStr(rec['value'] as string)}`);
  if (typeof rec['priority'] === 'number') props.push(`Priority = ${csNumber(rec['priority'] as number)}`);
  if (typeof rec['weight'] === 'number') props.push(`Weight = ${csNumber(rec['weight'] as number)}`);
  if (typeof rec['port'] === 'number') props.push(`Port = ${csNumber(rec['port'] as number)}`);
  return csObjectInit('DnsRecord', props, indent);
}

function renderDns(dns: Record<string, unknown>, indent: number, ctx: Ctx): string {
  const known = new Set(['responseCode', 'answerRecords', 'authorityRecords', 'additionalRecords', 'delay']);
  if (Object.keys(dns).some((k) => !known.has(k))) return rawTyped('DnsResponse', dns, ctx);
  const props: string[] = [];
  if (typeof dns['responseCode'] === 'string') props.push(`ResponseCode = ${csStr(dns['responseCode'] as string)}`);
  const recordMaps: Array<[string, string]> = [
    ['answerRecords', 'AnswerRecords'],
    ['authorityRecords', 'AuthorityRecords'],
    ['additionalRecords', 'AdditionalRecords'],
  ];
  for (const [wire, csp] of recordMaps) {
    if (Array.isArray(dns[wire])) {
      props.push(`${csp} = ${renderList(dns[wire] as unknown[], 'DnsRecord', renderDnsRecord, indent + 4)}`);
    }
  }
  if (isObject(dns['delay'])) props.push(`Delay = ${renderDelay(dns['delay'] as Record<string, unknown>)}`);
  return csObjectInit('DnsResponse', props, indent);
}

function renderGrpcMessage(msg: Record<string, unknown>, indent: number): string {
  if (typeof msg['json'] === 'string' && Object.keys(msg).length === 1) {
    return `GrpcStreamMessage.OfJson(${csStr(msg['json'] as string)})`;
  }
  const props: string[] = [];
  if (typeof msg['json'] === 'string') props.push(`Json = ${csStr(msg['json'] as string)}`);
  if (typeof msg['templateType'] === 'string') props.push(`TemplateType = ${csStr(msg['templateType'] as string)}`);
  if (isObject(msg['delay'])) props.push(`Delay = ${renderDelay(msg['delay'] as Record<string, unknown>)}`);
  return csObjectInit('GrpcStreamMessage', props, indent);
}

function renderGrpc(grpc: Record<string, unknown>, indent: number, ctx: Ctx): string {
  const known = new Set(['statusName', 'statusMessage', 'headers', 'messages', 'closeConnection', 'delay']);
  if (Object.keys(grpc).some((k) => !known.has(k))) return rawTyped('GrpcStreamResponse', grpc, ctx);
  const props: string[] = [];
  if (typeof grpc['statusName'] === 'string') props.push(`StatusName = ${csStr(grpc['statusName'] as string)}`);
  if (typeof grpc['statusMessage'] === 'string') props.push(`StatusMessage = ${csStr(grpc['statusMessage'] as string)}`);
  if (isObject(grpc['headers'])) props.push(`Headers = ${renderMultiMap(grpc['headers'] as Record<string, unknown>, indent + 4)}`);
  if (Array.isArray(grpc['messages'])) {
    props.push(`Messages = ${renderList(grpc['messages'] as unknown[], 'GrpcStreamMessage', renderGrpcMessage, indent + 4)}`);
  }
  // Emitted whenever present, including `false` — see renderWebSocket.
  if (typeof grpc['closeConnection'] === 'boolean') props.push(`CloseConnection = ${grpc['closeConnection'] ? 'true' : 'false'}`);
  if (isObject(grpc['delay'])) props.push(`Delay = ${renderDelay(grpc['delay'] as Record<string, unknown>)}`);
  return csObjectInit('GrpcStreamResponse', props, indent);
}

// ---------------------------------------------------------------------------
// Side-effect actions, steps, capture, scenario, chaos
// ---------------------------------------------------------------------------

function renderAfterAction(a: Record<string, unknown>, indent: number, ctx: Ctx): string {
  const known = new Set(['httpRequest', 'httpRequestTemplate', 'httpClassCallback', 'httpObjectCallback', 'delay', 'blocking', 'timeout', 'failurePolicy']);
  if (Object.keys(a).some((k) => !known.has(k))) return rawTyped('AfterAction', a, ctx);
  const props: string[] = [];
  if (isObject(a['httpRequest'])) props.push(`HttpRequest = ${renderRequest(a['httpRequest'] as Record<string, unknown>, indent + 4, ctx)}`);
  if (isObject(a['httpRequestTemplate'])) props.push(`HttpRequestTemplate = ${renderTemplate(a['httpRequestTemplate'] as Record<string, unknown>, indent + 4, ctx)}`);
  if (isObject(a['httpClassCallback'])) props.push(`HttpClassCallback = ${renderClassCallback(a['httpClassCallback'] as Record<string, unknown>, indent + 4, ctx)}`);
  if (isObject(a['httpObjectCallback'])) props.push(`HttpObjectCallback = ${rawTyped('HttpObjectCallback', a['httpObjectCallback'], ctx)}`);
  if (isObject(a['delay'])) props.push(`Delay = ${renderDelay(a['delay'] as Record<string, unknown>)}`);
  if (typeof a['blocking'] === 'boolean') props.push(`Blocking = ${a['blocking'] ? 'true' : 'false'}`);
  if (isObject(a['timeout'])) props.push(`Timeout = ${renderDelay(a['timeout'] as Record<string, unknown>)}`);
  if (typeof a['failurePolicy'] === 'string') props.push(`FailurePolicy = ActionFailurePolicy.${a['failurePolicy']}`);
  return csObjectInit('AfterAction', props, indent);
}

const STEP_ACTIONS: Array<[string, string, string]> = [
  ['httpResponse', 'HttpResponse', 'HttpResponse'],
  ['httpRequest', 'HttpRequest', 'HttpRequest'],
  ['httpForward', 'HttpForward', 'HttpForward'],
  ['httpOverrideForwardedRequest', 'HttpOverrideForwardedRequest', 'HttpOverrideForwardedRequest'],
  ['httpError', 'HttpError', 'HttpError'],
  ['httpClassCallback', 'HttpClassCallback', 'HttpClassCallback'],
];

function renderStep(s: Record<string, unknown>, indent: number, ctx: Ctx): string {
  const props: string[] = [];
  for (const [wire, csp, type] of STEP_ACTIONS) {
    if (wire in s) props.push(`${csp} = ${rawTyped(type, s[wire], ctx)}`);
  }
  if (s['responder'] === true) props.push('Responder = true');
  if (isObject(s['delay'])) props.push(`Delay = ${renderDelay(s['delay'] as Record<string, unknown>)}`);
  if (typeof s['blocking'] === 'boolean') props.push(`Blocking = ${s['blocking'] ? 'true' : 'false'}`);
  if (isObject(s['timeout'])) props.push(`Timeout = ${renderDelay(s['timeout'] as Record<string, unknown>)}`);
  if (typeof s['failurePolicy'] === 'string') props.push(`FailurePolicy = ActionFailurePolicy.${s['failurePolicy']}`);
  return csObjectInit('ExpectationStep', props, indent);
}

function renderCaptureRule(c: Record<string, unknown>): string {
  const source = String(c['source'] ?? '');
  const expr = String(c['expression'] ?? '');
  const into = String(c['into'] ?? '');
  return `CaptureRule.Of(${csStr(source)}, ${csStr(expr)}, ${csStr(into)})`;
}

const CHAOS_FIELDS: Array<[string, string, 'num' | 'str']> = [
  ['errorStatus', 'ErrorStatus', 'num'],
  ['errorProbability', 'ErrorProbability', 'num'],
  ['retryAfter', 'RetryAfter', 'str'],
  ['seed', 'Seed', 'num'],
  ['succeedFirst', 'SucceedFirst', 'num'],
  ['failRequestCount', 'FailRequestCount', 'num'],
];

function renderChaos(chaos: Record<string, unknown>, indent: number, ctx: Ctx): string {
  const known = new Set(['latency', ...CHAOS_FIELDS.map((f) => f[0])]);
  if (Object.keys(chaos).some((k) => !known.has(k))) return rawTyped('HttpChaosProfile', chaos, ctx);
  const props: string[] = [];
  for (const [wire, csp, kind] of CHAOS_FIELDS) {
    if (wire in chaos && chaos[wire] != null) {
      props.push(`${csp} = ${kind === 'num' ? csNumber(chaos[wire] as number) : csStr(String(chaos[wire]))}`);
    }
  }
  if (isObject(chaos['latency'])) props.push(`Latency = ${renderDelay(chaos['latency'] as Record<string, unknown>)}`);
  return csObjectInit('HttpChaosProfile', props, indent);
}

function renderCrossProtocol(cp: Record<string, unknown>, indent: number): string {
  const props: string[] = [];
  if (typeof cp['trigger'] === 'string') props.push(`Trigger = CrossProtocolTrigger.${cp['trigger']}`);
  if (typeof cp['matchPattern'] === 'string') props.push(`MatchPattern = ${csStr(cp['matchPattern'] as string)}`);
  if (typeof cp['scenarioName'] === 'string') props.push(`ScenarioName = ${csStr(cp['scenarioName'] as string)}`);
  if (typeof cp['targetState'] === 'string') props.push(`TargetState = ${csStr(cp['targetState'] as string)}`);
  return csObjectInit('CrossProtocolScenario', props, indent);
}

// ---------------------------------------------------------------------------
// Edit-preserved actions / siblings — typed object initializers (previously a
// per-fragment JsonSerializer.Deserialize<T>). Every field the composer's edit
// overlay can carry maps onto a strongly-typed model property. The one genuinely
// untyped leaf is HttpLlmResponse.Chaos (declared `object?` in the client model,
// with no LLM-specific chaos class), which stays a typed JsonElement fragment —
// mirroring the Go `raw` / Rust `serde_json::Value` treatment of the same field.
// ---------------------------------------------------------------------------

type CsSpec = Array<[wireKey: string, prop: string, fn: (v: unknown, indent: number, ctx: Ctx) => string]>;
const cS = (v: unknown): string => csStr(String(v));
const cN = (v: unknown): string => csNumber(Number(v));
const cB = (v: unknown): string => (v ? 'true' : 'false');
/** decimal literal (RateLimit.RefillPerSecond is `decimal?`, not double). */
const cDec = (v: unknown): string => `${csNumber(Number(v))}m`;
const cStrList = (v: unknown): string => renderStringList(Array.isArray(v) ? v : []);

function csTyped(type: string, o: Record<string, unknown>, spec: CsSpec, indent: number, ctx: Ctx): string {
  const props: string[] = [];
  for (const [wire, prop, fn] of spec) {
    if (wire in o && o[wire] != null) props.push(`${prop} = ${fn(o[wire], indent + 4, ctx)}`);
  }
  return csObjectInit(type, props, indent);
}

function renderUsage(o: Record<string, unknown>, indent: number, ctx: Ctx): string {
  return csTyped('Usage', o, [
    ['inputTokens', 'InputTokens', cN], ['outputTokens', 'OutputTokens', cN],
    ['cachedInputTokens', 'CachedInputTokens', cN], ['cacheCreationTokens', 'CacheCreationTokens', cN],
    ['reasoningTokens', 'ReasoningTokens', cN],
  ], indent, ctx);
}

function renderToolUse(o: Record<string, unknown>, indent: number, ctx: Ctx): string {
  return csTyped('ToolUse', o, [
    ['id', 'Id', cS], ['name', 'Name', cS], ['arguments', 'Arguments', cS],
  ], indent, ctx);
}

function renderStreamingPhysics(o: Record<string, unknown>, indent: number, ctx: Ctx): string {
  return csTyped('StreamingPhysics', o, [
    ['timeToFirstToken', 'TimeToFirstToken', (v) => renderDelay(v as Record<string, unknown>)],
    ['tokensPerSecond', 'TokensPerSecond', cN], ['jitter', 'Jitter', cN],
    ['seed', 'Seed', cN], ['subwordStreaming', 'SubwordStreaming', cB],
  ], indent, ctx);
}

function renderCompletion(o: Record<string, unknown>, indent: number, ctx: Ctx): string {
  return csTyped('Completion', o, [
    ['text', 'Text', cS],
    ['toolCalls', 'ToolCalls', (v, i) => renderList(v as unknown[], 'ToolUse', (t, ti) => renderToolUse(t, ti, ctx), i)],
    ['stopReason', 'StopReason', cS],
    ['usage', 'Usage', (v, i) => renderUsage(v as Record<string, unknown>, i, ctx)],
    ['streaming', 'Streaming', cB],
    ['streamingPhysics', 'StreamingPhysics', (v, i) => renderStreamingPhysics(v as Record<string, unknown>, i, ctx)],
    ['outputSchema', 'OutputSchema', cS],
    ['enforceOutputSchema', 'EnforceOutputSchema', cB],
    ['toolChoice', 'ToolChoice', cS],
    ['reasoningText', 'ReasoningText', cS],
    ['reasoningSignature', 'ReasoningSignature', cS],
    ['model', 'Model', cS],
  ], indent, ctx);
}

function renderNormalization(o: Record<string, unknown>, indent: number, ctx: Ctx): string {
  return csTyped('NormalizationOptions', o, [
    ['collapseWhitespace', 'CollapseWhitespace', cB], ['lowercase', 'Lowercase', cB],
    ['sortJsonKeys', 'SortJsonKeys', cB], ['dropBuiltInVolatileFields', 'DropBuiltInVolatileFields', cB],
    ['dropVolatileFields', 'DropVolatileFields', cStrList],
  ], indent, ctx);
}

function renderConversationPredicates(o: Record<string, unknown>, indent: number, ctx: Ctx): string {
  return csTyped('ConversationPredicates', o, [
    ['turnIndex', 'TurnIndex', cN], ['latestMessageContains', 'LatestMessageContains', cS],
    ['latestMessageMatches', 'LatestMessageMatches', cS], ['latestMessageRole', 'LatestMessageRole', cS],
    ['containsToolResultFor', 'ContainsToolResultFor', cS], ['semanticMatchAgainst', 'SemanticMatchAgainst', cS],
    ['normalization', 'Normalization', (v, i) => renderNormalization(v as Record<string, unknown>, i, ctx)],
  ], indent, ctx);
}

function renderLlm(o: Record<string, unknown>, indent: number, ctx: Ctx): string {
  ctx.usings.add('MockServer.Client.Llm');
  return csTyped('HttpLlmResponse', o, [
    ['provider', 'Provider', cS],
    ['model', 'Model', cS],
    ['completion', 'Completion', (v, i) => renderCompletion(v as Record<string, unknown>, i, ctx)],
    ['embedding', 'Embedding', (v, i) => csTyped('EmbeddingResponse', v as Record<string, unknown>, [
      ['dimensions', 'Dimensions', cN], ['deterministicFromInput', 'DeterministicFromInput', cB], ['seed', 'Seed', cN],
    ], i, ctx)],
    ['rerank', 'Rerank', (v, i) => csTyped('RerankResponse', v as Record<string, unknown>, [
      ['topN', 'TopN', cN], ['deterministicFromInput', 'DeterministicFromInput', cB], ['seed', 'Seed', cN],
    ], i, ctx)],
    ['moderation', 'Moderation', (v, i) => csTyped('ModerationResponse', v as Record<string, unknown>, [
      ['flaggedCategories', 'FlaggedCategories', cStrList], ['model', 'Model', cS],
    ], i, ctx)],
    ['contentFilter', 'ContentFilter', (v, i) => csTyped('ContentFilterResponse', v as Record<string, unknown>, [
      ['hate', 'Hate', cS], ['sexual', 'Sexual', cS], ['violence', 'Violence', cS], ['selfHarm', 'SelfHarm', cS],
    ], i, ctx)],
    ['conversationPredicates', 'ConversationPredicates', (v, i) => renderConversationPredicates(v as Record<string, unknown>, i, ctx)],
    // No LLM-specific chaos class exists — the model types this `object?`.
    ['chaos', 'Chaos', (v, _i, c) => { c.usings.add('System.Text.Json'); return `JsonSerializer.Deserialize<JsonElement>(${csVerbatim(stableJson(v))})`; }],
    ['delay', 'Delay', (v) => renderDelay(v as Record<string, unknown>)],
    ['primary', 'Primary', cB],
  ], indent, ctx);
}

function renderObjectCallback(o: Record<string, unknown>, indent: number, ctx: Ctx): string {
  return csTyped('HttpObjectCallback', o, [
    ['clientId', 'ClientId', cS], ['responseCallback', 'ResponseCallback', cB],
    ['delay', 'Delay', (v) => renderDelay(v as Record<string, unknown>)], ['primary', 'Primary', cB],
  ], indent, ctx);
}

function renderForwardValidate(o: Record<string, unknown>, indent: number, ctx: Ctx): string {
  return csTyped('HttpForwardValidateAction', o, [
    ['specUrlOrPayload', 'SpecUrlOrPayload', cS], ['host', 'Host', cS], ['port', 'Port', cN], ['scheme', 'Scheme', cS],
    ['validateRequest', 'ValidateRequest', cB], ['validateResponse', 'ValidateResponse', cB],
    ['validationMode', 'ValidationMode', cS], ['delay', 'Delay', (v) => renderDelay(v as Record<string, unknown>)],
    ['primary', 'Primary', cB],
  ], indent, ctx);
}

function renderGrpcBidiMessage(o: Record<string, unknown>, indent: number, ctx: Ctx): string {
  return csTyped('GrpcBidiMessage', o, [
    ['json', 'Json', cS], ['templateType', 'TemplateType', cS],
    ['delay', 'Delay', (v) => renderDelay(v as Record<string, unknown>)],
  ], indent, ctx);
}

function renderGrpcBidiRule(o: Record<string, unknown>, indent: number, ctx: Ctx): string {
  return csTyped('GrpcBidiRule', o, [
    ['matchJson', 'MatchJson', cS],
    ['responses', 'Responses', (v, i) => renderList(v as unknown[], 'GrpcBidiMessage', (m, mi) => renderGrpcBidiMessage(m, mi, ctx), i)],
  ], indent, ctx);
}

function renderGrpcBidi(o: Record<string, unknown>, indent: number, ctx: Ctx): string {
  return csTyped('GrpcBidiResponse', o, [
    ['statusName', 'StatusName', cS], ['statusMessage', 'StatusMessage', cS],
    ['headers', 'Headers', (v, i) => renderMultiMap(v as Record<string, unknown>, i)],
    ['messages', 'Messages', (v, i) => renderList(v as unknown[], 'GrpcBidiMessage', (m, mi) => renderGrpcBidiMessage(m, mi, ctx), i)],
    ['rules', 'Rules', (v, i) => renderList(v as unknown[], 'GrpcBidiRule', (r, ri) => renderGrpcBidiRule(r, ri, ctx), i)],
    ['closeConnection', 'CloseConnection', cB],
    ['delay', 'Delay', (v) => renderDelay(v as Record<string, unknown>)], ['primary', 'Primary', cB],
  ], indent, ctx);
}

function renderRateLimit(o: Record<string, unknown>, indent: number, ctx: Ctx): string {
  return csTyped('RateLimit', o, [
    ['name', 'Name', cS], ['algorithm', 'Algorithm', cS], ['limit', 'Limit', cN],
    ['windowMillis', 'WindowMillis', cN], ['burst', 'Burst', cN], ['refillPerSecond', 'RefillPerSecond', cDec],
    ['errorStatus', 'ErrorStatus', cN], ['retryAfter', 'RetryAfter', cS],
  ], indent, ctx);
}

/** Edit-only top-level actions/siblings → their typed renderer + Expectation property. */
const TYPED_TOPLEVEL: Record<string, { prop: string; render: (o: Record<string, unknown>, indent: number, ctx: Ctx) => string }> = {
  httpResponseObjectCallback: { prop: 'HttpResponseObjectCallback', render: renderObjectCallback },
  httpForwardObjectCallback: { prop: 'HttpForwardObjectCallback', render: renderObjectCallback },
  httpForwardValidateAction: { prop: 'HttpForwardValidateAction', render: renderForwardValidate },
  httpLlmResponse: { prop: 'HttpLlmResponse', render: renderLlm },
  grpcBidiResponse: { prop: 'GrpcBidiResponse', render: renderGrpcBidi },
  rateLimit: { prop: 'RateLimit', render: renderRateLimit },
};

function renderTimes(times: unknown): string[] {
  if (!isObject(times)) return [];
  if (times['unlimited'] === true) return ['Times = Times.Unlimited()'];
  if (typeof times['remainingTimes'] === 'number') return [`Times = Times.Exactly(${csNumber(times['remainingTimes'] as number)})`];
  return [];
}

function renderTtl(ttl: unknown): string[] {
  if (!isObject(ttl)) return [];
  if (ttl['unlimited'] === true) return ['TimeToLive = TimeToLive.UnlimitedTtl()'];
  if (typeof ttl['timeToLive'] === 'number') {
    const unit = typeof ttl['timeUnit'] === 'string' ? (ttl['timeUnit'] as string) : 'SECONDS';
    return [`TimeToLive = TimeToLive.ExactlyTtl(TimeUnit.${unit}, ${csNumber(ttl['timeToLive'] as number)})`];
  }
  return [];
}

function renderExpectation(json: Record<string, unknown>, ctx: Ctx): string {
  const props: string[] = [];
  const I = 4; // Expectation-property indent

  if (typeof json['id'] === 'string') props.push(`Id = ${csStr(json['id'] as string)}`);
  if (typeof json['priority'] === 'number') props.push(`Priority = ${csNumber(json['priority'] as number)}`);
  if (typeof json['percentage'] === 'number') props.push(`Percentage = ${csNumber(json['percentage'] as number)}`);
  if (typeof json['namespace'] === 'string') props.push(`Namespace = ${csStr(json['namespace'] as string)}`);

  if (isObject(json['httpRequest'])) props.push(`HttpRequest = ${renderRequest(json['httpRequest'] as Record<string, unknown>, I, ctx)}`);

  // Response / action family (mutually exclusive on the composer path, but an
  // edit overlay may carry several — render each present one).
  if (isObject(json['httpResponse'])) props.push(`HttpResponse = ${renderResponse(json['httpResponse'] as Record<string, unknown>, I, ctx)}`);
  if (Array.isArray(json['httpResponses'])) props.push(`HttpResponses = ${renderList(json['httpResponses'] as unknown[], 'HttpResponse', (r, i) => renderResponse(r, i, ctx), I)}`);
  if (isObject(json['httpForward'])) props.push(`HttpForward = ${renderForward(json['httpForward'] as Record<string, unknown>, I, ctx)}`);
  if (isObject(json['httpOverrideForwardedRequest'])) props.push(`HttpOverrideForwardedRequest = ${renderOverrideForwarded(json['httpOverrideForwardedRequest'] as Record<string, unknown>, I, ctx)}`);
  if (isObject(json['httpForwardWithFallback'])) props.push(`HttpForwardWithFallback = ${renderForwardWithFallback(json['httpForwardWithFallback'] as Record<string, unknown>, I, ctx)}`);
  if (isObject(json['httpResponseClassCallback'])) props.push(`HttpResponseClassCallback = ${renderClassCallback(json['httpResponseClassCallback'] as Record<string, unknown>, I, ctx)}`);
  if (isObject(json['httpForwardClassCallback'])) props.push(`HttpForwardClassCallback = ${renderClassCallback(json['httpForwardClassCallback'] as Record<string, unknown>, I, ctx)}`);
  if (isObject(json['httpResponseTemplate'])) props.push(`HttpResponseTemplate = ${renderTemplate(json['httpResponseTemplate'] as Record<string, unknown>, I, ctx)}`);
  if (isObject(json['httpForwardTemplate'])) props.push(`HttpForwardTemplate = ${renderTemplate(json['httpForwardTemplate'] as Record<string, unknown>, I, ctx)}`);
  if (isObject(json['httpError'])) props.push(`HttpError = ${renderError(json['httpError'] as Record<string, unknown>, I, ctx)}`);
  if (isObject(json['httpWebSocketResponse'])) props.push(`HttpWebSocketResponse = ${renderWebSocket(json['httpWebSocketResponse'] as Record<string, unknown>, I, ctx)}`);
  if (isObject(json['httpSseResponse'])) props.push(`HttpSseResponse = ${renderSse(json['httpSseResponse'] as Record<string, unknown>, I, ctx)}`);
  if (isObject(json['binaryResponse'])) props.push(`BinaryResponse = ${renderBinary(json['binaryResponse'] as Record<string, unknown>, I, ctx)}`);
  if (isObject(json['dnsResponse'])) props.push(`DnsResponse = ${renderDns(json['dnsResponse'] as Record<string, unknown>, I, ctx)}`);
  if (isObject(json['grpcStreamResponse'])) props.push(`GrpcStreamResponse = ${renderGrpc(json['grpcStreamResponse'] as Record<string, unknown>, I, ctx)}`);

  // Edit-only actions/siblings — typed object initializers into their property.
  for (const [wire, { prop, render }] of Object.entries(TYPED_TOPLEVEL)) {
    if (wire in json && json[wire] != null) props.push(`${prop} = ${render(json[wire] as Record<string, unknown>, I, ctx)}`);
  }

  // Multi-response selection controls.
  if (typeof json['responseMode'] === 'string') props.push(`ResponseMode = ResponseMode.${json['responseMode']}`);
  if (Array.isArray(json['responseWeights'])) props.push(`ResponseWeights = new List<int> { ${(json['responseWeights'] as unknown[]).map((n) => csNumber(Number(n))).join(', ')} }`);
  if (typeof json['switchAfter'] === 'number') props.push(`SwitchAfter = ${csNumber(json['switchAfter'] as number)}`);
  if (Array.isArray(json['crossProtocolScenarios'])) props.push(`CrossProtocolScenarios = ${renderList(json['crossProtocolScenarios'] as unknown[], 'CrossProtocolScenario', renderCrossProtocol, I)}`);

  // Side-effects, steps, capture.
  if (Array.isArray(json['beforeActions'])) props.push(`BeforeActions = ${renderList(json['beforeActions'] as unknown[], 'AfterAction', (a, i) => renderAfterAction(a, i, ctx), I)}`);
  if (Array.isArray(json['afterActions'])) props.push(`AfterActions = ${renderList(json['afterActions'] as unknown[], 'AfterAction', (a, i) => renderAfterAction(a, i, ctx), I)}`);
  if (Array.isArray(json['steps'])) props.push(`Steps = ${renderList(json['steps'] as unknown[], 'ExpectationStep', (s, i) => renderStep(s, i, ctx), I)}`);
  if (Array.isArray(json['capture'])) {
    const items = json['capture'] as unknown[];
    if (items.length === 0) {
      props.push('Capture = new List<CaptureRule>()');
    } else {
      const p = pad(I);
      const pIn = pad(I + 4);
      const body = items.map((c) => pIn + renderCaptureRule(isObject(c) ? (c as Record<string, unknown>) : {}) + ',').join('\n');
      props.push(`Capture = new List<CaptureRule>\n${p}{\n${body}\n${p}}`);
    }
  }

  // Chaos, scenario bindings, times / ttl.
  if (isObject(json['chaos'])) props.push(`Chaos = ${renderChaos(json['chaos'] as Record<string, unknown>, I, ctx)}`);
  if (typeof json['scenarioName'] === 'string') props.push(`ScenarioName = ${csStr(json['scenarioName'] as string)}`);
  if (typeof json['scenarioState'] === 'string') props.push(`ScenarioState = ${csStr(json['scenarioState'] as string)}`);
  if (typeof json['newScenarioState'] === 'string') props.push(`NewScenarioState = ${csStr(json['newScenarioState'] as string)}`);
  if (typeof json['timestamp'] === 'string') props.push(`Timestamp = ${csStr(json['timestamp'] as string)}`);
  if (isObject(json['metadata'])) props.push(`Metadata = ${renderMetadata(json['metadata'] as Record<string, unknown>, I, ctx)}`);

  props.push(...renderTimes(json['times']));
  props.push(...renderTtl(json['timeToLive']));

  return csObjectInit('Expectation', props, 0);
}

// ---------------------------------------------------------------------------
// Entry point
// ---------------------------------------------------------------------------

export function standardToCsharp(matcher: StandardMatcher, action: StandardActionPayload, baseUrl: string): string {
  const { host, port } = clientHostPort(baseUrl);
  const json = buildExpectationJson(matcher, action);
  const ctx: Ctx = { usings: new Set(['MockServer.Client', 'MockServer.Client.Models']) };

  const expectation = renderExpectation(json, ctx);

  const usings = Array.from(ctx.usings).sort().map((u) => `using ${u};`);
  return [
    ...usings,
    '',
    `using var client = new MockServerClient(${csStr(host)}, ${port});`,
    `client.Upsert(${expectation});`,
  ].join('\n');
}
