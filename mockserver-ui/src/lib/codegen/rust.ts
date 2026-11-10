/**
 * Rust client-library emitter.
 *
 * Emits **typed** `mockserver-client` construction — `Expectation::new(HttpRequest…)`
 * with `HttpRequest` / `HttpResponse` / `Body` / `HttpForward` / `HttpChaosProfile`
 * (etc.) builders and typed struct literals — rather than deserialising an embedded
 * JSON blob via `serde_json::from_str`. The transpiler walks the canonical wire
 * object produced by {@link buildExpectationJson} and maps every field onto a builder
 * call, a typed struct literal, or (only for genuinely opaque JSON — a JSON-body
 * matcher's value, a free-text step/DNS payload, or an edit-passthrough field the
 * composer does not model) a `serde_json::json!` fragment carried through the model's
 * typed escape hatches (`Body::object`, the public `extra` catch-all maps).
 *
 * The generated source serialises (`serde_json::to_value`) to a value structurally
 * identical to {@link buildExpectationJson}; the crate's own model is the single
 * source of truth for field names and shapes (see `mockserver-client-rust/src/model.rs`).
 */
import { buildExpectationJson, type StandardMatcher, type StandardActionPayload } from '../standardCodegen.ts';
import { clientHostPort, rustClientVersionRequirement } from './shared.ts';
import { dateTimeBuilderExpr, dateTimeMatcherOf, nottableObjectOf } from '../matcherValue.ts';

// ---------------------------------------------------------------------------
// Low-level literal helpers
// ---------------------------------------------------------------------------

/** Render a Rust double-quoted string literal. Backtick and `#` are literal in a
 *  Rust string so they need no escaping (unlike the old raw-string form). */
function rustStr(s: string): string {
  let out = '"';
  for (const ch of s) {
    const code = ch.codePointAt(0)!;
    if (ch === '\\') out += '\\\\';
    else if (ch === '"') out += '\\"';
    else if (ch === '\n') out += '\\n';
    else if (ch === '\r') out += '\\r';
    else if (ch === '\t') out += '\\t';
    else if (ch === '\0') out += '\\0';
    else if (code < 0x20) out += '\\u{' + code.toString(16) + '}';
    // a lone surrogate cannot be held in a Rust str, so it becomes U+FFFD
    else if (code >= 0xd800 && code <= 0xdfff) out += '\\u{fffd}';
    else out += ch;
  }
  return out + '"';
}

/** Render a JSON number as a Rust/`json!` numeric token. */
function numLit(n: number): string {
  return Number.isFinite(n) ? String(n) : '0';
}

/** Render a number as a Rust `f64` literal (integers get a `.0` suffix so the
 *  literal is not inferred as an integer where a float is required). */
function floatLit(n: number): string {
  if (!Number.isFinite(n)) return '0.0';
  return Number.isInteger(n) ? `${n}.0` : String(n);
}

const pad = (n: number): string => ' '.repeat(n);

// ---------------------------------------------------------------------------
// serde_json::json! literal rendering (for the few opaque-JSON fragments)
// ---------------------------------------------------------------------------

/** Render an arbitrary JSON value as the token stream accepted by the
 *  `serde_json::json!` macro, indented for readability. */
function jsonMacroArg(v: unknown, indent: number): string {
  if (v === null || v === undefined) return 'null';
  if (typeof v === 'boolean') return v ? 'true' : 'false';
  if (typeof v === 'number') return numLit(v);
  if (typeof v === 'string') return rustStr(v);
  if (Array.isArray(v)) {
    if (v.length === 0) return '[]';
    const inner = pad(indent + 4);
    return '[\n' + v.map((x) => inner + jsonMacroArg(x, indent + 4)).join(',\n') + '\n' + pad(indent) + ']';
  }
  const entries = Object.entries(v as Record<string, unknown>);
  if (entries.length === 0) return '{}';
  const inner = pad(indent + 4);
  return (
    '{\n' +
    entries.map(([k, val]) => inner + rustStr(k) + ': ' + jsonMacroArg(val, indent + 4)).join(',\n') +
    '\n' +
    pad(indent) +
    '}'
  );
}

/** `serde_json::json!(<value>)` expression whose head sits at column `indent`. */
function jsonMacro(v: unknown, indent: number): string {
  return 'serde_json::json!(' + jsonMacroArg(v, indent) + ')';
}

// ---------------------------------------------------------------------------
// Builder-chain assembly
// ---------------------------------------------------------------------------

/**
 * A rendered value: the expression to use, plus any statements that must precede
 * it (a `let [mut] name = …;` binding and follow-up `name.extra.insert(…)` calls
 * for fields that have no dedicated builder).
 */
interface Rendered {
  setup: string[];
  expr: string;
}

const inline = (expr: string): Rendered => ({ setup: [], expr });

/** Assemble a fluent builder chain: `head` then each call on its own line,
 *  continuation lines indented to `indent + 4`. */
function chain(head: string, calls: string[], indent: number): string {
  if (calls.length === 0) return head;
  const cont = pad(indent + 4);
  return head + '\n' + calls.map((c) => cont + c).join('\n');
}

/** Monotonic temp-name source so nested pre-bound values never collide. */
class Ctx {
  private n = 0;
  temp(): string {
    return `v${this.n++}`;
  }
}

// ---------------------------------------------------------------------------
// Delay / Times / TimeToLive
// ---------------------------------------------------------------------------

/**
 * An inline NOTE comment naming each field of `o` outside `known`, for a model with no
 * `extra` map to carry it; empty when there is none.
 */
function gapNote(model: string, o: Record<string, unknown>, known: readonly string[]): string {
  const missing = Object.keys(o).filter((k) => !known.includes(k));
  return missing.length > 0 ? `/* NOTE: the Rust ${model} model has no ${missing.join(', ')}; omitted */ ` : '';
}

function delayExpr(d: Record<string, unknown>): string {
  const note = gapNote('Delay', d, ['timeUnit', 'value', 'distribution', 'template', 'templateType']);
  const unit = String(d['timeUnit'] ?? 'MILLISECONDS');
  const value = Number(d['value'] ?? 0);
  let expr = unit === 'MILLISECONDS' ? `Delay::milliseconds(${numLit(value)})`
    : unit === 'SECONDS' ? `Delay::seconds(${numLit(value)})`
    : `Delay::new(${rustStr(unit)}, ${numLit(value)})`;
  if (isObjR(d['distribution'])) expr += `.distribution(${delayDistributionExpr(d['distribution'])})`;
  if (typeof d['template'] === 'string') expr += `.template(${rustStr(d['template'])})`;
  if (typeof d['templateType'] === 'string') expr += `.template_type(${rustStr(d['templateType'])})`;
  return note + expr;
}

const DISTRIBUTION_BOUNDS = ['min', 'max', 'median', 'p99', 'mean', 'stdDev'] as const;

/** A named constructor when the distribution has exactly its type's two bounds, else a struct literal. */
function delayDistributionExpr(d: Record<string, unknown>): string {
  const note = gapNote('DelayDistribution', d, ['type', ...DISTRIBUTION_BOUNDS]);
  const ctors: Record<string, [string, string, string]> = {
    UNIFORM: ['uniform', 'min', 'max'], LOG_NORMAL: ['log_normal', 'median', 'p99'], GAUSSIAN: ['gaussian', 'mean', 'stdDev'],
  };
  const ctor = ctors[String(d['type'])];
  const bounds = DISTRIBUTION_BOUNDS.filter((k) => typeof d[k] === 'number');
  if (ctor && bounds.length === 2 && bounds.includes(ctor[1] as never) && bounds.includes(ctor[2] as never)) {
    return `${note}DelayDistribution::${ctor[0]}(${numLit(d[ctor[1]] as number)}, ${numLit(d[ctor[2]] as number)})`;
  }
  const fields = [`distribution_type: ${rustStr(String(d['type'] ?? ''))}.to_string()`];
  for (const k of bounds) fields.push(`${k === 'stdDev' ? 'std_dev' : k}: Some(${numLit(d[k] as number)})`);
  return `${note}DelayDistribution { ${fields.join(', ')}, ..Default::default() }`;
}

function timesExpr(t: Record<string, unknown>): string {
  if (t['unlimited'] === true && t['remainingTimes'] == null) return 'Times::unlimited()';
  if (typeof t['remainingTimes'] === 'number') return `Times::exactly(${numLit(t['remainingTimes'] as number)})`;
  return 'Times::unlimited()';
}

function ttlExpr(t: Record<string, unknown>): string {
  if (t['unlimited'] === true && t['timeToLive'] == null) return 'TimeToLive::unlimited()';
  const value = t['timeToLive'];
  if (typeof value === 'number') {
    const unit = String(t['timeUnit'] ?? 'SECONDS');
    if (unit === 'MILLISECONDS') return `TimeToLive::milliseconds(${numLit(value)})`;
    return `TimeToLive::seconds(${numLit(value)})`;
  }
  return 'TimeToLive::unlimited()';
}

// ---------------------------------------------------------------------------
// Body matcher (request side)
// ---------------------------------------------------------------------------

/** True when `o` has exactly the given keys (no more, no less). */
function hasExactKeys(o: Record<string, unknown>, keys: string[]): boolean {
  const ks = Object.keys(o);
  return ks.length === keys.length && keys.every((k) => k in o);
}

/** Render a typed `Body` expression for a request-body matcher object. */
function bodyValueExpr(o: Record<string, unknown>, indent: number): string {
  const type = o['type'];
  if (type === 'ALL_OF' && Array.isArray(o['bodyAllOf'])) {
    const subs = o['bodyAllOf'] as unknown[];
    const inner = pad(indent + 4);
    const items = subs.map((s) => inner + bodyValueExpr(s as Record<string, unknown>, indent + 4) + ',').join('\n');
    return 'Body::all_of(vec![\n' + items + '\n' + pad(indent) + '])';
  }
  if (type === 'REGEX' && hasExactKeys(o, ['type', 'regex'])) return `Body::regex(${rustStr(String(o['regex']))})`;
  if (type === 'JSON_PATH' && hasExactKeys(o, ['type', 'jsonPath'])) return `Body::json_path(${rustStr(String(o['jsonPath']))})`;
  if (type === 'XPATH' && hasExactKeys(o, ['type', 'xpath'])) return `Body::xpath(${rustStr(String(o['xpath']))})`;
  const dt = dateTimeMatcherOf(o['dateTime']);
  if (dt && type === 'JSON_PATH' && hasExactKeys(o, ['type', 'jsonPath', 'dateTime'])) {
    return `Body::json_path(${rustStr(String(o['jsonPath']))}).with_date_time(${dateTimeBuilderExpr(dt, { head: 'MatcherValue::', naming: 'snake', str: rustStr, bool: String })})`;
  }
  if (dt && type === 'XPATH' && hasExactKeys(o, ['type', 'xpath', 'dateTime'])) {
    return `Body::xpath(${rustStr(String(o['xpath']))}).with_date_time(${dateTimeBuilderExpr(dt, { head: 'MatcherValue::', naming: 'snake', str: rustStr, bool: String })})`;
  }
  if (type === 'XML' && hasExactKeys(o, ['type', 'xml'])) return `Body::xml(${rustStr(String(o['xml']))})`;
  if (type === 'XML_SCHEMA' && hasExactKeys(o, ['type', 'xmlSchema'])) return `Body::xml_schema(${rustStr(String(o['xmlSchema']))})`;
  if (type === 'JSON_SCHEMA' && hasExactKeys(o, ['type', 'jsonSchema'])) return `Body::json_schema(${rustStr(String(o['jsonSchema']))})`;
  if (type === 'STRING' && hasExactKeys(o, ['type', 'string', 'subString'])) {
    return `Body::string(${rustStr(String(o['string']))}, ${o['subString'] === true ? 'true' : 'false'})`;
  }
  // JSON (value is inherently JSON data), GRAPHQL-with-options, PARAMETERS, BINARY,
  // WASM, or any matcher carrying extra keys: the typed escape hatch Body::object,
  // which serialises the object verbatim.
  return (
    'Body::object(\n' +
    pad(indent + 4) +
    jsonMacro(o, indent + 4) +
    '\n' +
    pad(indent + 4) +
    '.as_object()\n' +
    pad(indent + 4) +
    '.expect("body object")\n' +
    pad(indent + 4) +
    '.clone(),\n' +
    pad(indent) +
    ')'
  );
}

/** The `.body(...)` / `.body_value(...)` call for a request body value. */
function requestBodyCall(body: unknown, indent: number): string {
  if (typeof body === 'string') return `.body(${rustStr(body)})`;
  return `.body_value(${bodyValueExpr(body as Record<string, unknown>, indent + 4)})`;
}

// ---------------------------------------------------------------------------
// Jwt
// ---------------------------------------------------------------------------

function jwtCall(jwt: Record<string, unknown>, indent: number): string {
  const calls: string[] = [];
  if (typeof jwt['header'] === 'string') calls.push(`.header(${rustStr(jwt['header'])})`);
  if (typeof jwt['scheme'] === 'string') calls.push(`.scheme(${rustStr(jwt['scheme'])})`);
  const claims = jwt['claims'];
  if (claims && typeof claims === 'object' && !Array.isArray(claims)) {
    for (const [k, v] of Object.entries(claims as Record<string, unknown>)) {
      calls.push(`.claim(${rustStr(k)}, ${rustStr(String(v))})`);
    }
  }
  if (typeof jwt['issuer'] === 'string') calls.push(`.issuer(${rustStr(jwt['issuer'])})`);
  if (typeof jwt['audience'] === 'string') calls.push(`.audience(${rustStr(jwt['audience'])})`);
  if (typeof jwt['algorithm'] === 'string') calls.push(`.algorithm(${rustStr(jwt['algorithm'])})`);
  return '.jwt(' + chain('Jwt::new()', calls, indent + 4) + ')';
}

// ---------------------------------------------------------------------------
// HttpRequest
// ---------------------------------------------------------------------------

/** Request keys the typed HttpRequest builder models directly. */
const REQUEST_BUILDER_KEYS = new Set([
  'method',
  'path',
  'headers',
  'queryStringParameters',
  'cookies',
  'pathParameters',
  'body',
  'jwt',
  'secure',
  'clientIp',
]);

const isPlainValue = (v: unknown): boolean => v === null || typeof v !== 'object';

/** A `MatcherValue` expression; undefined for a form MatcherValue cannot hold (a schema). A plain
 *  string goes through `From`, which parses it as the server would ("!foo" is not foo). */
function rustMatcherValue(v: unknown): string | undefined {
  if (isPlainValue(v)) return `MatcherValue::from(${rustStr(String(v))})`;
  const dt = dateTimeMatcherOf(v);
  if (dt) return dateTimeBuilderExpr(dt, { head: 'MatcherValue::', naming: 'snake', str: rustStr, bool: String });
  const n = nottableObjectOf(v);
  if (!n) return undefined;
  return `MatcherValue { value: ${rustStr(n.value)}.to_string(), not: ${n.not}, optional: ${n.optional} }`;
}

/** Header / query / cookie / path-parameter wire fields and their plain and matcher builder methods. */
const RUST_MATCHER_FAMILIES: [wire: string, plain: string, matcher: string, single: boolean][] = [
  ['headers', 'header', 'header_matcher', false],
  ['queryStringParameters', 'query_param', 'query_param_matcher', false],
  ['cookies', 'cookie', 'cookie_matcher', true],
  ['pathParameters', 'path_param', 'path_param_matcher', false],
];

function renderHttpRequest(req: Record<string, unknown>, ctx: Ctx, indent: number, name?: string): Rendered {
  const calls: string[] = [];
  if (typeof req['method'] === 'string') calls.push(`.method(${rustStr(req['method'])})`);
  if (typeof req['path'] === 'string') calls.push(`.path(${rustStr(req['path'])})`);

  // A key holding a non-string value uses the *_matcher method. Once called it replaces the plain
  // map for that field, so every plain call is emitted first. A field with a value MatcherValue
  // cannot hold is carried whole through `extra` instead.
  const rawFields = new Set<string>();
  const matcherCalls: string[] = [];
  for (const [wire, plain, matcher, single] of RUST_MATCHER_FAMILIES) {
    const map = req[wire];
    if (!map || typeof map !== 'object' || Array.isArray(map)) continue;
    const entries = Object.entries(map as Record<string, unknown>).map(
      ([k, v]) => [k, single ? [v] : Array.isArray(v) ? v : [v]] as [string, unknown[]],
    );
    if (entries.some(([, vs]) => vs.some((v) => !isPlainValue(v) && rustMatcherValue(v) === undefined))) {
      rawFields.add(wire);
      continue;
    }
    for (const [k, vs] of entries) {
      if (vs.every(isPlainValue)) {
        for (const v of vs) calls.push(`.${plain}(${rustStr(k)}, ${rustStr(String(v))})`);
      } else {
        for (const v of vs) matcherCalls.push(`.${matcher}(${rustStr(k)}, ${rustMatcherValue(v)})`);
      }
    }
  }
  calls.push(...matcherCalls);
  if ('body' in req) calls.push(requestBodyCall(req['body'], indent));

  // A JWT with a `claims` object round-trips faithfully via the typed builder. A
  // claims-less JWT cannot (Jwt always serialises `claims: {}`), so it is carried
  // verbatim through `extra` below.
  const jwt = req['jwt'] as Record<string, unknown> | undefined;
  const jwtTyped = !!(jwt && jwt['claims'] && typeof jwt['claims'] === 'object');
  if (jwtTyped) calls.push(jwtCall(jwt!, indent));

  // typeof, not === true: secure:false is an HTTP-only matcher, not an absent field
  if (typeof req['secure'] === 'boolean') calls.push(`.secure(${req['secure']})`);
  const clientIpTyped = typeof req['clientIp'] === 'string';
  if (clientIpTyped) calls.push(`.client_ip(${rustStr(req['clientIp'] as string)})`);

  // Fields with no dedicated builder (dnsName/dnsType/dnsClass DNS matcher, a
  // claims-less jwt, protocol, keepAlive, socketAddress, edit-passthrough fields,
  // …) go into the public `extra` catch-all map verbatim.
  const extras: [string, unknown][] = [];
  for (const [k, v] of Object.entries(req)) {
    if (REQUEST_BUILDER_KEYS.has(k)) {
      if (k === 'jwt' && !jwtTyped) extras.push([k, v]); // claims-less jwt → raw
      if (rawFields.has(k)) extras.push([k, v]);
      if (k === 'clientIp' && !clientIpTyped) extras.push([k, v]);
      continue;
    }
    extras.push([k, v]);
  }

  if (extras.length === 0) {
    return calls.length === 0 ? inline('HttpRequest::new()') : inline(chain('HttpRequest::new()', calls, indent));
  }
  const varName = name ?? ctx.temp();
  const setup: string[] = [];
  setup.push(pad(indent) + `let mut ${varName} = ` + chain('HttpRequest::new()', calls, indent) + ';');
  for (const [k, v] of extras) {
    setup.push(pad(indent) + `${varName}.extra.insert(${rustStr(k)}.to_string(), ${jsonMacro(v, indent)});`);
  }
  return { setup, expr: varName };
}

// ---------------------------------------------------------------------------
// ConnectionOptions
// ---------------------------------------------------------------------------

const CONNECTION_OPTION_FIELDS: Record<string, string> = {
  keepAliveOverride: 'keep_alive_override',
  closeSocket: 'close_socket',
  contentLengthHeaderOverride: 'content_length_header_override',
  suppressContentLengthHeader: 'suppress_content_length_header',
  suppressConnectionHeader: 'suppress_connection_header',
  chunkSize: 'chunk_size',
};

const CONNECTION_OPTION_DELAYS: Record<string, string> = {
  closeSocketDelay: 'close_socket_delay',
  chunkDelay: 'chunk_delay',
};

function connectionOptionsExpr(co: Record<string, unknown>, indent: number): string {
  const fields: string[] = [];
  for (const [wireKey, rustField] of Object.entries(CONNECTION_OPTION_FIELDS)) {
    if (wireKey in co) {
      const v = co[wireKey];
      const lit = typeof v === 'boolean' ? (v ? 'true' : 'false') : numLit(Number(v));
      fields.push(`${rustField}: Some(${lit}),`);
    }
  }
  for (const [wireKey, rustField] of Object.entries(CONNECTION_OPTION_DELAYS)) {
    if (isObjR(co[wireKey])) fields.push(`${rustField}: Some(${delayExpr(co[wireKey])}),`);
  }
  const extra = extraField(unmodelled(co, [...Object.keys(CONNECTION_OPTION_FIELDS), ...Object.keys(CONNECTION_OPTION_DELAYS)]), indent + 4);
  if (extra) fields.push(extra);
  const inner = pad(indent + 4);
  return 'ConnectionOptions {\n' + fields.map((f) => inner + f).join('\n') + '\n' + inner + '..Default::default()\n' + pad(indent) + '}';
}

// ---------------------------------------------------------------------------
// HttpResponse
// ---------------------------------------------------------------------------

/** Response keys with a dedicated HttpResponse builder / typed field. */
const RESPONSE_BUILDER_KEYS = new Set([
  'statusCode',
  'headers',
  'body',
  'delay',
  'cookies',
  'reasonPhrase',
  'statusCodeRange',
  'trailers',
  'generateFromSchema',
  'connectionOptions',
]);

function renderHttpResponse(resp: Record<string, unknown>, ctx: Ctx, indent: number, name?: string): Rendered {
  const calls: string[] = [];
  if (typeof resp['statusCode'] === 'number') calls.push(`.status_code(${numLit(resp['statusCode'])})`);

  // A string body uses `.body(..)`, an object body (FILE, BINARY, JSON, …)
  // `.body_value(..)`, and a JSON array or scalar body `.json_body(..)`, which
  // sends it as a typed JSON body.
  const body = resp['body'];
  if (typeof body === 'string') calls.push(`.body(${rustStr(body)})`);
  else if (body != null && typeof body === 'object' && !Array.isArray(body)) {
    calls.push(`.body_value(${bodyValueExpr(body as Record<string, unknown>, indent + 4)})`);
  } else if (body != null) calls.push(`.json_body(${jsonMacro(body, indent + 4)})`);

  const headers = resp['headers'];
  if (headers && typeof headers === 'object') {
    for (const [k, vs] of Object.entries(headers as Record<string, string[]>)) {
      for (const v of vs) calls.push(`.header(${rustStr(k)}, ${rustStr(String(v))})`);
    }
  }
  const trailers = resp['trailers'];
  if (trailers && typeof trailers === 'object') {
    for (const [k, vs] of Object.entries(trailers as Record<string, string[]>)) {
      for (const v of vs) calls.push(`.trailer(${rustStr(k)}, ${rustStr(String(v))})`);
    }
  }
  const cookies = resp['cookies'];
  if (cookies && typeof cookies === 'object') {
    for (const [k, v] of Object.entries(cookies as Record<string, string>)) {
      calls.push(`.cookie(${rustStr(k)}, ${rustStr(String(v))})`);
    }
  }
  if (typeof resp['reasonPhrase'] === 'string') calls.push(`.reason_phrase(${rustStr(resp['reasonPhrase'])})`);
  if (typeof resp['statusCodeRange'] === 'string') calls.push(`.status_code_range(${rustStr(resp['statusCodeRange'])})`);
  if (typeof resp['generateFromSchema'] === 'string') calls.push(`.generate_from_schema(${rustStr(resp['generateFromSchema'])})`);
  if (resp['connectionOptions'] && typeof resp['connectionOptions'] === 'object') {
    calls.push(`.connection_options(${connectionOptionsExpr(resp['connectionOptions'] as Record<string, unknown>, indent + 4)})`);
  }
  if (resp['delay'] && typeof resp['delay'] === 'object') {
    calls.push(`.delay(${delayExpr(resp['delay'] as Record<string, unknown>)})`);
  }

  const extras: [string, unknown][] = [];
  for (const [k, v] of Object.entries(resp)) {
    if (RESPONSE_BUILDER_KEYS.has(k)) continue;
    extras.push([k, v]);
  }

  if (extras.length === 0) {
    return calls.length === 0 ? inline('HttpResponse::new()') : inline(chain('HttpResponse::new()', calls, indent));
  }
  const varName = name ?? ctx.temp();
  const setup: string[] = [];
  setup.push(pad(indent) + `let mut ${varName} = ` + chain('HttpResponse::new()', calls, indent) + ';');
  for (const [k, v] of extras) {
    setup.push(pad(indent) + `${varName}.extra.insert(${rustStr(k)}.to_string(), ${jsonMacro(v, indent)});`);
  }
  return { setup, expr: varName };
}

// ---------------------------------------------------------------------------
// HttpForward / HttpTemplate / HttpError
// ---------------------------------------------------------------------------

/** `.delay(…)` / `.primary(…)` for the delay and primary every action inherits. */
function inheritedCalls(o: Record<string, unknown>): string[] {
  const calls: string[] = [];
  if (o['delay'] && typeof o['delay'] === 'object') calls.push(`.delay(${delayExpr(o['delay'] as Record<string, unknown>)})`);
  if (typeof o['primary'] === 'boolean') calls.push(`.primary(${o['primary']})`);
  return calls;
}

/** The fields of `o` outside `known`, which a model with an `extra` map carries verbatim. */
function unmodelled(o: Record<string, unknown>, known: readonly string[]): [string, unknown][] {
  return Object.entries(o).filter(([k]) => !known.includes(k));
}

/** An `extra: { … },` struct field holding `extras`, or undefined when there are none. */
function extraField(extras: [string, unknown][], indent: number): string | undefined {
  if (extras.length === 0) return undefined;
  const inner = pad(indent + 4);
  const inserts = extras.map(([k, v]) => `${inner}m.insert(${rustStr(k)}.to_string(), ${jsonMacro(v, indent + 4)});`).join('\n');
  return `extra: {\n${inner}let mut m = serde_json::Map::new();\n${inserts}\n${inner}m\n${pad(indent)}},`;
}

/**
 * `base` with `fields` set through struct-update syntax, for model fields that have no
 * builder method (including the `extra` map).
 */
function withFields(typeName: string, base: (indent: number) => string, fields: string[], indent: number): string {
  if (fields.length === 0) return base(indent);
  const inner = pad(indent + 4);
  return `${typeName} {\n${fields.map((f) => inner + f).join('\n')}\n${inner}..${base(indent + 4)}\n${pad(indent)}}`;
}

/** {@link withFields} with only the `extra` map: the fields of `o` outside `known`. */
function withExtra(typeName: string, base: (indent: number) => string, o: Record<string, unknown>, known: readonly string[], indent: number): string {
  const extra = extraField(unmodelled(o, known), indent + 4);
  return withFields(typeName, base, extra ? [extra] : [], indent);
}

/** The delay and primary every action inherits, as struct fields. */
function inheritedFields(o: Record<string, unknown>): string[] {
  const fields: string[] = [];
  if (o['delay'] && typeof o['delay'] === 'object') fields.push(`delay: Some(${delayExpr(o['delay'] as Record<string, unknown>)}),`);
  if (typeof o['primary'] === 'boolean') fields.push(`primary: Some(${o['primary']}),`);
  return fields;
}

const TEMPLATE_FIELDS = ['templateType', 'template', 'templateFile', 'delay', 'primary', 'responseOverride', 'responseModifier'];

function forwardExpr(f: Record<string, unknown>, indent: number): string {
  const host = rustStr(String(f['host'] ?? ''));
  const port = typeof f['port'] === 'number' ? numLit(f['port']) : '0';
  const calls: string[] = [];
  if (typeof f['scheme'] === 'string') calls.push(`.scheme(${rustStr(f['scheme'])})`);
  calls.push(...inheritedCalls(f));
  const base = (ind: number) => chain(`HttpForward::new(${host}, ${port})`, calls, ind);
  return withExtra('HttpForward', base, f, ['host', 'port', 'scheme', 'delay', 'primary'], indent);
}

/**
 * The HttpTemplate model has no `extra` map, so this names what it lacks with {@link gapNote}. `parameters`
 * is not on it either: pass `parametersHandled` where the caller sets them through the Expectation instead.
 */
function templateExpr(t: Record<string, unknown>, ctx: Ctx, indent: number, parametersHandled = false): Rendered {
  const note = gapNote('HttpTemplate', t, parametersHandled ? [...TEMPLATE_FIELDS, 'parameters'] : TEMPLATE_FIELDS);
  const type = rustStr(String(t['templateType'] ?? ''));
  const file = t['templateFile'];
  const calls: string[] = [];
  const setup: string[] = [];
  let head: string;
  if (typeof file === 'string') {
    if (typeof t['template'] === 'string') calls.push(`.template(${rustStr(t['template'])})`);
    head = `HttpTemplate::from_file(${type}, ${rustStr(file)})`;
  } else {
    head = `HttpTemplate::new(${type}, ${rustStr(String(t['template'] ?? ''))})`;
  }
  calls.push(...inheritedCalls(t));
  if (isObjR(t['responseOverride'])) {
    const r = renderHttpResponse(t['responseOverride'], ctx, indent + 4, 'template_response_override');
    setup.push(...r.setup);
    calls.push(`.response_override(${r.expr})`);
  }
  if (t['responseModifier'] != null) calls.push(`.response_modifier(${jsonMacro(t['responseModifier'], indent + 4)})`);
  return { setup, expr: note + chain(head, calls, indent) };
}

function errorExpr(e: Record<string, unknown>, indent: number): string {
  const calls: string[] = [];
  if (typeof e['dropConnection'] === 'boolean') calls.push(`.drop_connection(${e['dropConnection']})`);
  if (typeof e['resetConnection'] === 'boolean') calls.push(`.reset_connection(${e['resetConnection']})`);
  if (typeof e['responseBytes'] === 'string') calls.push(`.response_bytes(${rustStr(e['responseBytes'])})`);
  if (typeof e['streamError'] === 'number') calls.push(`.stream_error(${numLit(e['streamError'])})`);
  calls.push(...inheritedCalls(e));
  const base = (ind: number) => chain('HttpError::new()', calls, ind);
  return withExtra('HttpError', base, e, ['dropConnection', 'resetConnection', 'responseBytes', 'streamError', 'delay', 'primary'], indent);
}

// ---------------------------------------------------------------------------
// SSE / WebSocket / DNS / Binary / gRPC-stream
// ---------------------------------------------------------------------------

function sseEventExpr(ev: Record<string, unknown>, indent: number): string {
  const note = gapNote('SseEvent', ev, ['event', 'data', 'id', 'retry', 'comment', 'delay']);
  const calls: string[] = [];
  if (typeof ev['event'] === 'string') calls.push(`.event(${rustStr(ev['event'])})`);
  if (typeof ev['data'] === 'string') calls.push(`.data(${rustStr(ev['data'])})`);
  if (typeof ev['id'] === 'string') calls.push(`.id(${rustStr(ev['id'])})`);
  if (typeof ev['retry'] === 'number') calls.push(`.retry(${numLit(ev['retry'])})`);
  if (typeof ev['comment'] === 'string') calls.push(`.comment(${rustStr(ev['comment'])})`);
  if (ev['delay'] && typeof ev['delay'] === 'object') calls.push(`.delay(${delayExpr(ev['delay'] as Record<string, unknown>)})`);
  return note + chain('SseEvent::new()', calls, indent);
}

function sseExpr(sse: Record<string, unknown>, indent: number): string {
  const calls: string[] = [];
  if (typeof sse['statusCode'] === 'number') calls.push(`.status_code(${numLit(sse['statusCode'])})`);
  const headers = sse['headers'];
  if (headers && typeof headers === 'object') {
    for (const [k, vs] of Object.entries(headers as Record<string, string[]>)) {
      for (const v of vs) calls.push(`.header(${rustStr(k)}, ${rustStr(String(v))})`);
    }
  }
  const events = sse['events'];
  if (Array.isArray(events)) for (const ev of events) calls.push(`.event(${sseEventExpr(ev as Record<string, unknown>, indent + 4)})`);
  // Emitted whenever present, including `false`. An ABSENT closeConnection is not
  // equivalent to `false`: the server reads absent as "close" for SSE and WebSocket
  // (and as "don't close" for gRPC streaming), so dropping an explicit `false` would
  // generate code that closes the connection the user asked to keep open.
  if (typeof sse['closeConnection'] === 'boolean') calls.push(`.close_connection(${sse['closeConnection'] ? 'true' : 'false'})`);
  calls.push(...inheritedCalls(sse));
  if (typeof sse['templateType'] === 'string') calls.push(`.template_type(${rustStr(sse['templateType'])})`);
  const base = (ind: number) => chain('HttpSseResponse::new()', calls, ind);
  return withExtra('HttpSseResponse', base, sse, ['statusCode', 'headers', 'events', 'closeConnection', 'delay', 'primary', 'templateType'], indent);
}

function wsMessageExpr(m: Record<string, unknown>): string {
  const note = gapNote('WebSocketMessage', m, ['text', 'binary', 'delay']);
  const base = typeof m['binary'] === 'string'
    ? `WebSocketMessage::binary_base64(${rustStr(m['binary'])})`
    : `WebSocketMessage::text(${rustStr(String(m['text'] ?? ''))})`;
  return note + (m['delay'] && typeof m['delay'] === 'object' ? `${base}.delay(${delayExpr(m['delay'] as Record<string, unknown>)})` : base);
}

function wsMatcherExpr(m: Record<string, unknown>, indent: number): string {
  const calls: string[] = [];
  if (typeof m['frameType'] === 'string') calls.push(`.frame_type(${rustStr(m['frameType'])})`);
  if (typeof m['textMatcher'] === 'string') calls.push(`.text_matcher(${rustStr(m['textMatcher'])})`);
  const responses = m['responses'];
  if (Array.isArray(responses)) for (const r of responses) calls.push(`.response(${wsMessageExpr(r as Record<string, unknown>)})`);
  const base = (ind: number) => chain('WebSocketMatcher::new()', calls, ind);
  return withExtra('WebSocketMatcher', base, m, ['frameType', 'textMatcher', 'responses'], indent);
}

function graphqlFilterExpr(f: Record<string, unknown>, indent: number): string {
  const calls: string[] = [];
  if (typeof f['operationName'] === 'string') calls.push(`.operation_name(${rustStr(f['operationName'])})`);
  if (typeof f['variablesSchema'] === 'string') calls.push(`.variables_schema(${rustStr(f['variablesSchema'])})`);
  if (typeof f['selectionSetMatchType'] === 'string') calls.push(`.selection_set_match_type(${rustStr(f['selectionSetMatchType'])})`);
  if (Array.isArray(f['fields'])) calls.push(`.fields(vec![${f['fields'].map((x) => `${rustStr(String(x))}.to_string()`).join(', ')}])`);
  const base = (ind: number) => chain(`GraphqlSubscriptionFilter::new(${rustStr(String(f['query'] ?? ''))})`, calls, ind);
  return withExtra('GraphqlSubscriptionFilter', base, f, ['query', 'operationName', 'variablesSchema', 'selectionSetMatchType', 'fields'], indent);
}

function webSocketExpr(ws: Record<string, unknown>, indent: number): string {
  const calls: string[] = [];
  if (typeof ws['subprotocol'] === 'string') calls.push(`.subprotocol(${rustStr(ws['subprotocol'])})`);
  const messages = ws['messages'];
  if (Array.isArray(messages)) for (const m of messages) calls.push(`.message(${wsMessageExpr(m as Record<string, unknown>)})`);
  const matchers = ws['matchers'];
  if (Array.isArray(matchers)) for (const m of matchers) calls.push(`.matcher(${wsMatcherExpr(m as Record<string, unknown>, indent + 4)})`);
  // Emitted whenever present, including `false` — see sseExpr.
  if (typeof ws['closeConnection'] === 'boolean') calls.push(`.close_connection(${ws['closeConnection'] ? 'true' : 'false'})`);
  calls.push(...inheritedCalls(ws));
  if (typeof ws['templateType'] === 'string') calls.push(`.template_type(${rustStr(ws['templateType'])})`);
  if (isObjR(ws['graphqlSubscriptionFilter'])) {
    calls.push(`.graphql_subscription_filter(${graphqlFilterExpr(ws['graphqlSubscriptionFilter'], indent + 4)})`);
  }
  const base = (ind: number) => chain('HttpWebSocketResponse::new()', calls, ind);
  const known = ['subprotocol', 'messages', 'matchers', 'closeConnection', 'delay', 'primary', 'templateType', 'graphqlSubscriptionFilter'];
  return withExtra('HttpWebSocketResponse', base, ws, known, indent);
}

const DNS_RECORD_FIELDS = new Set(['name', 'type', 'ttl', 'value', 'class', 'dnsClass', 'priority', 'weight', 'port']);

/** A DNS record maps to typed DnsRecord builders only when every field is one the
 *  typed model names; otherwise the caller carries the whole dnsResponse as raw JSON. */
function dnsRecordExpr(rec: Record<string, unknown>, indent: number): string | undefined {
  for (const k of Object.keys(rec)) if (!DNS_RECORD_FIELDS.has(k)) return undefined;
  const calls: string[] = [];
  if (typeof rec['name'] === 'string') calls.push(`.name(${rustStr(rec['name'])})`);
  if (typeof rec['type'] === 'string') calls.push(`.record_type(${rustStr(rec['type'])})`);
  const cls = rec['dnsClass'] ?? rec['class'];
  if (typeof cls === 'string') calls.push(`.dns_class(${rustStr(cls)})`);
  if (typeof rec['ttl'] === 'number') calls.push(`.ttl(${numLit(rec['ttl'])})`);
  if (typeof rec['value'] === 'string') calls.push(`.value(${rustStr(rec['value'])})`);
  if (typeof rec['priority'] === 'number') calls.push(`.priority(${numLit(rec['priority'])})`);
  if (typeof rec['weight'] === 'number') calls.push(`.weight(${numLit(rec['weight'])})`);
  if (typeof rec['port'] === 'number') calls.push(`.port(${numLit(rec['port'])})`);
  return chain('DnsRecord::new()', calls, indent);
}

/** Returns a typed DnsResponse expression, or undefined when a record carries a
 *  field the typed DnsRecord cannot represent (then the caller emits raw JSON). */
function dnsResponseExpr(dns: Record<string, unknown>, indent: number): string | undefined {
  const calls: string[] = [];
  if (typeof dns['responseCode'] === 'string') calls.push(`.response_code(${rustStr(dns['responseCode'])})`);
  const sections: [string, string][] = [
    ['answerRecords', 'answer_record'],
    ['authorityRecords', 'authority_record'],
    ['additionalRecords', 'additional_record'],
  ];
  for (const [wireKey, method] of sections) {
    const arr = dns[wireKey];
    if (arr === undefined) continue;
    if (!Array.isArray(arr)) return undefined;
    for (const rec of arr) {
      const recExpr = dnsRecordExpr(rec as Record<string, unknown>, indent + 4);
      if (recExpr === undefined) return undefined;
      calls.push(`.${method}(${recExpr})`);
    }
  }
  calls.push(...inheritedCalls(dns));
  const base = (ind: number) => chain('DnsResponse::new()', calls, ind);
  return withExtra('DnsResponse', base, dns, ['responseCode', ...sections.map(([wireKey]) => wireKey), 'delay', 'primary'], indent);
}

function grpcStreamExpr(grpc: Record<string, unknown>, indent: number): string {
  const calls: string[] = [];
  if (typeof grpc['statusName'] === 'string') calls.push(`.status_name(${rustStr(grpc['statusName'])})`);
  if (typeof grpc['statusMessage'] === 'string') calls.push(`.status_message(${rustStr(grpc['statusMessage'])})`);
  const headers = grpc['headers'];
  if (headers && typeof headers === 'object') {
    for (const [k, vs] of Object.entries(headers as Record<string, string[]>)) {
      for (const v of vs) calls.push(`.header(${rustStr(k)}, ${rustStr(String(v))})`);
    }
  }
  const messages = grpc['messages'];
  if (Array.isArray(messages)) {
    for (const m of messages) {
      const mo = m as Record<string, unknown>;
      let call = gapNote('GrpcStreamMessage', mo, ['json', 'templateType', 'delay']) + `GrpcStreamMessage::json(${rustStr(String(mo['json'] ?? ''))})`;
      if (typeof mo['templateType'] === 'string') call += `.template_type(${rustStr(mo['templateType'])})`;
      if (mo['delay'] && typeof mo['delay'] === 'object') call += `.delay(${delayExpr(mo['delay'] as Record<string, unknown>)})`;
      calls.push(`.message(${call})`);
    }
  }
  // Emitted whenever present, including `false` — see sseExpr.
  if (typeof grpc['closeConnection'] === 'boolean') calls.push(`.close_connection(${grpc['closeConnection'] ? 'true' : 'false'})`);
  calls.push(...inheritedCalls(grpc));
  const base = (ind: number) => chain('GrpcStreamResponse::new()', calls, ind);
  return withExtra('GrpcStreamResponse', base, grpc, ['statusName', 'statusMessage', 'headers', 'messages', 'closeConnection', 'delay', 'primary'], indent);
}

// ---------------------------------------------------------------------------
// Chaos — a typed struct literal (only 5 of its fields have builder methods).
// ---------------------------------------------------------------------------

function chaosExpr(c: Record<string, unknown>, indent: number): string {
  const fields: string[] = [];
  if (typeof c['errorStatus'] === 'number') fields.push(`error_status: Some(${numLit(c['errorStatus'])}),`);
  if (typeof c['errorProbability'] === 'number') fields.push(`error_probability: Some(${floatLit(c['errorProbability'])}),`);
  if (typeof c['retryAfter'] === 'string') fields.push(`retry_after: Some(${rustStr(c['retryAfter'])}.to_string()),`);
  if (c['latency'] && typeof c['latency'] === 'object') fields.push(`latency: Some(${delayExpr(c['latency'] as Record<string, unknown>)}),`);
  if (typeof c['seed'] === 'number') fields.push(`seed: Some(${numLit(c['seed'])}),`);
  if (typeof c['succeedFirst'] === 'number') fields.push(`succeed_first: Some(${numLit(c['succeedFirst'])}),`);
  if (typeof c['failRequestCount'] === 'number') fields.push(`fail_request_count: Some(${numLit(c['failRequestCount'])}),`);
  const inner = pad(indent + 4);
  return 'HttpChaosProfile {\n' + fields.map((f) => inner + f).join('\n') + '\n' + inner + '..Default::default()\n' + pad(indent) + '}';
}

// ---------------------------------------------------------------------------
// Side-effect actions (before/after) + capture
// ---------------------------------------------------------------------------

/** Render an ExpectationAction; pre-binds a nested HttpRequest when needed. */
function renderExpectationAction(se: Record<string, unknown>, ctx: Ctx, indent: number): Rendered {
  const tpl = se['httpRequestTemplate'];
  if (tpl && typeof tpl === 'object') return renderTemplatedExpectationAction(se, tpl as Record<string, unknown>, ctx, indent);
  const setup: string[] = [];
  let reqExpr = 'HttpRequest::new()';
  const req = se['httpRequest'];
  if (req && typeof req === 'object') {
    const r = renderHttpRequest(req as Record<string, unknown>, ctx, indent);
    setup.push(...r.setup);
    reqExpr = r.expr;
  }

  const hasEnvelope =
    (se['delay'] && typeof se['delay'] === 'object') ||
    'blocking' in se ||
    (se['timeout'] && typeof se['timeout'] === 'object') ||
    typeof se['failurePolicy'] === 'string';

  if (!hasEnvelope) return { setup, expr: `ExpectationAction::request(${reqExpr})` };

  // Envelope fields have no builder methods — use a typed struct literal.
  const inner = pad(indent + 4);
  const fields: string[] = [`http_request: Some(${reqExpr}),`];
  if (se['delay'] && typeof se['delay'] === 'object') fields.push(`delay: Some(${delayExpr(se['delay'] as Record<string, unknown>)}),`);
  if ('blocking' in se) fields.push(`blocking: Some(${se['blocking'] === true ? 'true' : 'false'}),`);
  if (se['timeout'] && typeof se['timeout'] === 'object') fields.push(`timeout: Some(${delayExpr(se['timeout'] as Record<string, unknown>)}),`);
  if (typeof se['failurePolicy'] === 'string') fields.push(`failure_policy: Some(${rustStr(se['failurePolicy'])}.to_string()),`);
  const expr = 'ExpectationAction {\n' + fields.map((f) => inner + f).join('\n') + '\n' + inner + '..Default::default()\n' + pad(indent) + '}';
  return { setup, expr };
}

/** A templated webhook: the template is carried in `extra`, so envelope fields use struct update over the builder. */
function renderTemplatedExpectationAction(se: Record<string, unknown>, tpl: Record<string, unknown>, ctx: Ctx, indent: number): Rendered {
  const t = templateExpr(tpl, ctx, indent + 4);
  const base = `ExpectationAction::request_template(${t.expr})`;
  const fields: string[] = [];
  if (se['delay'] && typeof se['delay'] === 'object') fields.push(`delay: Some(${delayExpr(se['delay'] as Record<string, unknown>)}),`);
  if ('blocking' in se) fields.push(`blocking: Some(${se['blocking'] === true ? 'true' : 'false'}),`);
  if (se['timeout'] && typeof se['timeout'] === 'object') fields.push(`timeout: Some(${delayExpr(se['timeout'] as Record<string, unknown>)}),`);
  if (typeof se['failurePolicy'] === 'string') fields.push(`failure_policy: Some(${rustStr(se['failurePolicy'])}.to_string()),`);
  if (fields.length === 0) return { setup: t.setup, expr: base };
  const inner = pad(indent + 4);
  const expr = 'ExpectationAction {\n' + fields.map((f) => inner + f).join('\n') + '\n' + inner + '..' + base + '\n' + pad(indent) + '}';
  return { setup: t.setup, expr };
}

function captureRuleExpr(c: Record<string, unknown>): string {
  return `CaptureRule::new(${rustStr(String(c['source'] ?? ''))}, ${rustStr(String(c['expression'] ?? ''))}, ${rustStr(String(c['into'] ?? ''))})`;
}

// ---------------------------------------------------------------------------
// Steps
// ---------------------------------------------------------------------------

/** Action-payload key → the ExpectationStep field it maps to (typed). */
const STEP_ACTION_FIELD: Record<string, { field: string; render: (o: Record<string, unknown>, ctx: Ctx, indent: number) => Rendered }> = {
  httpResponse: { field: 'http_response', render: renderHttpResponse },
  httpRequest: { field: 'http_request', render: renderHttpRequest },
};

function renderStep(step: Record<string, unknown>, ctx: Ctx, indent: number): Rendered {
  const setup: string[] = [];
  const fields: string[] = [];
  const rawExtras: [string, unknown][] = [];

  for (const [k, v] of Object.entries(step)) {
    if (k === 'responder') {
      if (v === true) fields.push('responder: Some(true),');
      continue;
    }
    if (k === 'delay' && v && typeof v === 'object') {
      fields.push(`delay: Some(${delayExpr(v as Record<string, unknown>)}),`);
      continue;
    }
    if (k === 'timeout' && v && typeof v === 'object') {
      fields.push(`timeout: Some(${delayExpr(v as Record<string, unknown>)}),`);
      continue;
    }
    if (k === 'blocking') {
      fields.push(`blocking: Some(${v === true ? 'true' : 'false'}),`);
      continue;
    }
    if (k === 'failurePolicy' && typeof v === 'string') {
      fields.push(`failure_policy: Some(${rustStr(v)}.to_string()),`);
      continue;
    }
    const mapping = STEP_ACTION_FIELD[k];
    if (mapping && v && typeof v === 'object' && !Array.isArray(v)) {
      const r = mapping.render(v as Record<string, unknown>, ctx, indent);
      setup.push(...r.setup);
      fields.push(`${mapping.field}: Some(${r.expr}),`);
    } else {
      // httpForward/httpError/httpClassCallback/httpOverrideForwardedRequest or a
      // free-text payload the typed step fields cannot structurally guarantee:
      // carry it verbatim through the step's `extra` catch-all.
      rawExtras.push([k, v]);
    }
  }

  const inner = pad(indent + 4);
  const structFields = [...fields];
  if (rawExtras.length > 0) {
    const mapEntries = rawExtras
      .map(([k, v]) => inner + '    ' + `m.insert(${rustStr(k)}.to_string(), ${jsonMacro(v, indent + 8)});`)
      .join('\n');
    structFields.push(
      'extra: {\n' + inner + '    let mut m = serde_json::Map::new();\n' + mapEntries + '\n' + inner + '    m\n' + inner + '},',
    );
  }
  const expr =
    'ExpectationStep {\n' + structFields.map((f) => inner + f).join('\n') + '\n' + inner + '..Default::default()\n' + pad(indent) + '}';
  return { setup, expr };
}

// ---------------------------------------------------------------------------
// Edit-preserved actions/siblings — typed struct literals / builders. Every
// wire key an edit overlay can carry is emitted through the crate's typed model
// (see model.rs) rather than an `extra.insert(serde_json::json!(...))` blob. The
// only fields carried as `serde_json::json!` are the LLM sub-objects the model
// itself types as `serde_json::Value` (embedding/rerank/moderation/contentFilter/
// conversationPredicates/chaos) — that IS their typed field type.
// ---------------------------------------------------------------------------

const isObjR = (v: unknown): v is Record<string, unknown> => !!v && typeof v === 'object' && !Array.isArray(v);
const optStr = (v: unknown): string => `Some(${rustStr(String(v))}.to_string())`;
const optNum = (v: unknown): string => `Some(${numLit(Number(v))})`;
const optFloat = (v: unknown): string => `Some(${floatLit(Number(v))})`;
const optBool = (v: unknown): string => `Some(${v === true ? 'true' : 'false'})`;

/** SCREAMING_SNAKE_CASE wire enum value → Rust PascalCase variant. */
function pascalEnum(s: string): string {
  return s.split('_').map((p) => p.charAt(0).toUpperCase() + p.slice(1).toLowerCase()).join('');
}

type StructSpec = Array<[wireKey: string, rustField: string, fn: (v: unknown, indent: number) => string]>;

/** A `Name { field: val, ... ..Default::default() }` struct literal (for Default-deriving types). */
function structLit(name: string, o: Record<string, unknown>, spec: StructSpec, indent: number): string {
  const inner = pad(indent + 4);
  const fields: string[] = [];
  for (const [wire, field, fn] of spec) {
    if (o[wire] != null) fields.push(`${field}: ${fn(o[wire], indent + 4)},`);
  }
  if (fields.length === 0) return `${name}::default()`;
  return `${name} {\n` + fields.map((f) => inner + f).join('\n') + '\n' + inner + '..Default::default()\n' + pad(indent) + '}';
}

function llmUsageExpr(o: Record<string, unknown>, indent: number): string {
  return structLit('LlmUsage', o, [
    ['inputTokens', 'input_tokens', optNum], ['outputTokens', 'output_tokens', optNum],
    ['cachedInputTokens', 'cached_input_tokens', optNum], ['cacheCreationTokens', 'cache_creation_tokens', optNum],
    ['reasoningTokens', 'reasoning_tokens', optNum],
  ], indent);
}

function llmStreamingPhysicsExpr(o: Record<string, unknown>, indent: number): string {
  return structLit('LlmStreamingPhysics', o, [
    ['timeToFirstToken', 'time_to_first_token', (v) => `Some(${delayExpr(v as Record<string, unknown>)})`],
    ['tokensPerSecond', 'tokens_per_second', optNum], ['jitter', 'jitter', optFloat],
    ['seed', 'seed', optNum], ['subwordStreaming', 'subword_streaming', optBool],
  ], indent);
}

function llmToolCallsExpr(v: unknown[], indent: number): string {
  const inner = pad(indent + 4);
  const items = v.map((t) => inner + structLit('LlmToolCall', t as Record<string, unknown>, [
    ['id', 'id', optStr], ['name', 'name', optStr], ['arguments', 'arguments', optStr],
  ], indent + 4) + ',').join('\n');
  return `vec![\n${items}\n${pad(indent)}]`;
}

function llmCompletionExpr(o: Record<string, unknown>, indent: number): string {
  return structLit('LlmCompletion', o, [
    ['text', 'text', optStr],
    ['toolCalls', 'tool_calls', (v, i) => `Some(${llmToolCallsExpr(v as unknown[], i)})`],
    ['stopReason', 'stop_reason', optStr],
    ['usage', 'usage', (v, i) => `Some(${llmUsageExpr(v as Record<string, unknown>, i)})`],
    ['streaming', 'streaming', optBool],
    ['streamingPhysics', 'streaming_physics', (v, i) => `Some(${llmStreamingPhysicsExpr(v as Record<string, unknown>, i)})`],
    ['outputSchema', 'output_schema', optStr],
    ['enforceOutputSchema', 'enforce_output_schema', optBool],
    ['toolChoice', 'tool_choice', optStr],
    ['reasoningText', 'reasoning_text', optStr],
    ['reasoningSignature', 'reasoning_signature', optStr],
    ['model', 'model', optStr],
  ], indent);
}

function llmResponseExpr(o: Record<string, unknown>, indent: number): string {
  // completion is the one typed sub-struct; embedding/rerank/moderation/
  // content_filter/conversation_predicates/chaos are Option<serde_json::Value>.
  return structLit('HttpLlmResponse', o, [
    ['provider', 'provider', optStr],
    ['model', 'model', optStr],
    ['completion', 'completion', (v, i) => `Some(${llmCompletionExpr(v as Record<string, unknown>, i)})`],
    ['embedding', 'embedding', (v, i) => `Some(${jsonMacro(v, i)})`],
    ['rerank', 'rerank', (v, i) => `Some(${jsonMacro(v, i)})`],
    ['moderation', 'moderation', (v, i) => `Some(${jsonMacro(v, i)})`],
    ['contentFilter', 'content_filter', (v, i) => `Some(${jsonMacro(v, i)})`],
    ['conversationPredicates', 'conversation_predicates', (v, i) => `Some(${jsonMacro(v, i)})`],
    ['chaos', 'chaos', (v, i) => `Some(${jsonMacro(v, i)})`],
    ['delay', 'delay', (v) => `Some(${delayExpr(v as Record<string, unknown>)})`],
    ['primary', 'primary', optBool],
  ], indent);
}

function rateLimitExpr(o: Record<string, unknown>, indent: number): string {
  return structLit('RateLimit', o, [
    ['name', 'name', optStr], ['algorithm', 'algorithm', optStr], ['limit', 'limit', optNum],
    ['windowMillis', 'window_millis', optNum], ['burst', 'burst', optNum],
    ['refillPerSecond', 'refill_per_second', optFloat], ['errorStatus', 'error_status', optNum],
    ['retryAfter', 'retry_after', optStr],
  ], indent);
}

/** HttpObjectCallback — no Default; uses the `::new(client_id)` builder. */
function objectCallbackExpr(o: Record<string, unknown>, indent: number): string {
  const calls: string[] = [];
  if (typeof o['responseCallback'] === 'boolean') calls.push(`.response_callback(${o['responseCallback']})`);
  if (isObjR(o['delay'])) calls.push(`.delay(${delayExpr(o['delay'])})`);
  if (typeof o['primary'] === 'boolean') calls.push(`.primary(${o['primary']})`);
  return chain(`HttpObjectCallback::new(${rustStr(String(o['clientId'] ?? ''))})`, calls, indent);
}

/** HttpForwardValidateAction — no Default; all fields listed (public fields). */
function forwardValidateExpr(o: Record<string, unknown>, indent: number): string {
  const inner = pad(indent + 4);
  const fields: string[] = [
    `spec_url_or_payload: ${rustStr(String(o['specUrlOrPayload'] ?? ''))}.to_string(),`,
    `host: ${rustStr(String(o['host'] ?? ''))}.to_string(),`,
    `port: ${typeof o['port'] === 'number' ? `Some(${numLit(o['port'])})` : 'None'},`,
    `scheme: ${typeof o['scheme'] === 'string' ? optStr(o['scheme']) : 'None'},`,
    `validate_request: ${typeof o['validateRequest'] === 'boolean' ? optBool(o['validateRequest']) : 'None'},`,
    `validate_response: ${typeof o['validateResponse'] === 'boolean' ? optBool(o['validateResponse']) : 'None'},`,
    `validation_mode: ${typeof o['validationMode'] === 'string' ? optStr(o['validationMode']) : 'None'},`,
    `delay: ${isObjR(o['delay']) ? `Some(${delayExpr(o['delay'])})` : 'None'},`,
    `primary: ${typeof o['primary'] === 'boolean' ? optBool(o['primary']) : 'None'},`,
    'extra: Default::default(),',
  ];
  return 'HttpForwardValidateAction {\n' + fields.map((f) => inner + f).join('\n') + '\n' + pad(indent) + '}';
}

/** `vec![ item, ... ]` across lines. */
function vecExpr(arr: unknown[], indent: number, itemFn: (o: Record<string, unknown>, i: number) => string): string {
  const inner = pad(indent + 4);
  const items = arr.map((x) => inner + itemFn(x as Record<string, unknown>, indent + 4) + ',').join('\n');
  return `vec![\n${items}\n${pad(indent)}]`;
}

/** `std::collections::HashMap::from([(k, vec![...]), ...])` for a header multimap. */
function hashMapExpr(o: Record<string, unknown>, indent: number): string {
  const inner = pad(indent + 4);
  const entries = Object.entries(o).map(([k, vs]) => {
    const vals = (Array.isArray(vs) ? vs : [vs]).map((v) => `${rustStr(String(v))}.to_string()`).join(', ');
    return inner + `(${rustStr(k)}.to_string(), vec![${vals}]),`;
  }).join('\n');
  return `std::collections::HashMap::from([\n${entries}\n${pad(indent)}])`;
}

// GrpcBidiResponse/Rule/Message expose only new/message/rule/json builders (no
// per-field setters), so the field-carrying shapes use typed struct literals.
function grpcBidiMessageExpr(o: Record<string, unknown>, indent: number): string {
  if (typeof o['json'] === 'string' && o['templateType'] == null && o['delay'] == null) {
    return `GrpcBidiMessage::json(${rustStr(String(o['json']))})`;
  }
  return structLit('GrpcBidiMessage', o, [
    ['json', 'json', optStr], ['templateType', 'template_type', optStr],
    ['delay', 'delay', (v) => `Some(${delayExpr(v as Record<string, unknown>)})`],
  ], indent);
}

function grpcBidiRuleExpr(o: Record<string, unknown>, indent: number): string {
  return structLit('GrpcBidiRule', o, [
    ['matchJson', 'match_json', optStr],
    ['responses', 'responses', (v, i) => `Some(${vecExpr(v as unknown[], i, grpcBidiMessageExpr)})`],
  ], indent);
}

function grpcBidiExpr(o: Record<string, unknown>, indent: number): string {
  return structLit('GrpcBidiResponse', o, [
    ['statusName', 'status_name', optStr], ['statusMessage', 'status_message', optStr],
    ['headers', 'headers', (v, i) => `Some(${hashMapExpr(v as Record<string, unknown>, i)})`],
    ['messages', 'messages', (v, i) => `Some(${vecExpr(v as unknown[], i, grpcBidiMessageExpr)})`],
    ['rules', 'rules', (v, i) => `Some(${vecExpr(v as unknown[], i, grpcBidiRuleExpr)})`],
    ['closeConnection', 'close_connection', optBool],
    ['delay', 'delay', (v) => `Some(${delayExpr(v as Record<string, unknown>)})`],
    ['primary', 'primary', optBool],
  ], indent);
}

function crossProtocolExpr(arr: unknown[], indent: number): string {
  const inner = pad(indent + 4);
  const items = arr.map((c) => {
    const o = c as Record<string, unknown>;
    const trigger = `CrossProtocolTrigger::${pascalEnum(String(o['trigger'] ?? 'HTTP_REQUEST'))}`;
    let expr = `CrossProtocolScenario::new(${trigger}, ${rustStr(String(o['scenarioName'] ?? ''))}, ${rustStr(String(o['targetState'] ?? ''))})`;
    if (typeof o['matchPattern'] === 'string') expr += `.match_pattern(${rustStr(o['matchPattern'])})`;
    return inner + expr + ',';
  }).join('\n');
  return `vec![\n${items}\n${pad(indent)}]`;
}

// ---------------------------------------------------------------------------
// Primary action dispatch
// ---------------------------------------------------------------------------

type PrimaryRendered = Rendered | { expectationExtra: [string, unknown] };

const PRIMARY_ACTION_KEYS = [
  'httpResponse',
  'httpForward',
  'httpResponseTemplate',
  'httpForwardTemplate',
  'httpError',
  'httpResponseClassCallback',
  'httpForwardClassCallback',
  'httpSseResponse',
  'httpWebSocketResponse',
  'binaryResponse',
  'grpcStreamResponse',
  'dnsResponse',
  'httpOverrideForwardedRequest',
  'httpForwardWithFallback',
  'httpLlmResponse',
  'grpcBidiResponse',
  'httpResponseObjectCallback',
  'httpForwardObjectCallback',
  'httpForwardValidateAction',
];

/** Map a top-level action-family key onto the Expectation terminal builder call. */
function renderPrimaryAction(key: string, value: unknown, ctx: Ctx, indent: number): PrimaryRendered {
  const obj = value as Record<string, unknown>;
  switch (key) {
    case 'httpLlmResponse':
      return inline(`.respond_llm(${llmResponseExpr(obj, indent + 4)})`);
    case 'grpcBidiResponse':
      return inline(`.respond_grpc_bidi(${grpcBidiExpr(obj, indent + 4)})`);
    case 'httpResponseObjectCallback':
      return inline(`.respond_object_callback(${objectCallbackExpr(obj, indent + 4)})`);
    case 'httpForwardObjectCallback':
      return inline(`.forward_object_callback(${objectCallbackExpr(obj, indent + 4)})`);
    case 'httpForwardValidateAction':
      return inline(`.forward_validate(${forwardValidateExpr(obj, indent + 4)})`);
    case 'httpResponse': {
      const r = renderHttpResponse(obj, ctx, indent + 4, 'response');
      return { setup: r.setup, expr: `.respond(${r.expr})` };
    }
    case 'httpForward':
      return inline(`.forward(${forwardExpr(obj, indent + 4)})`);
    case 'httpResponseTemplate':
    case 'httpForwardTemplate': {
      const method = key === 'httpResponseTemplate' ? 'respond_template' : 'forward_template';
      const parameters = obj['parameters'];
      if (parameters && typeof parameters === 'object' && !Array.isArray(parameters) && Object.keys(parameters).length > 0) {
        // the Rust HttpTemplate has no parameters field; the Expectation carries them instead
        const r = templateExpr(obj, ctx, indent + 8, true);
        const arg = ' '.repeat(indent + 8);
        const map = `${jsonMacro(parameters, indent + 8)}\n${arg}.as_object()\n${arg}.cloned()\n${arg}.unwrap_or_default()`;
        return { setup: r.setup, expr: `.${method}_with_parameters(\n${arg}${r.expr},\n${arg}${map},\n${' '.repeat(indent + 4)})` };
      }
      const r = templateExpr(obj, ctx, indent + 4);
      return { setup: r.setup, expr: `.${method}(${r.expr})` };
    }
    case 'httpError':
      return inline(`.error(${errorExpr(obj, indent + 4)})`);
    case 'httpResponseClassCallback':
    case 'httpForwardClassCallback': {
      const cls = rustStr(String(obj['callbackClass'] ?? ''));
      const calls = inheritedCalls(obj);
      const respond = key === 'httpResponseClassCallback';
      const note = gapNote('HttpClassCallback', obj, ['callbackClass', 'delay', 'primary']);
      if (calls.length === 0) return inline(`.${respond ? 'respond_with' : 'forward_with'}_class_callback(${note}${cls})`);
      return inline(`.${respond ? 'respond' : 'forward'}_class_callback(${note}${chain(`HttpClassCallback::new(${cls})`, calls, indent + 4)})`);
    }
    case 'httpSseResponse':
      return inline(`.respond_sse(${sseExpr(obj, indent + 4)})`);
    case 'httpWebSocketResponse':
      return inline(`.respond_web_socket(${webSocketExpr(obj, indent + 4)})`);
    case 'binaryResponse': {
      const calls = inheritedCalls(obj);
      const base = typeof obj['binaryData'] === 'string' && obj['binaryData'] !== ''
        ? `BinaryResponse::from_base64(${rustStr(obj['binaryData'])})`
        : 'BinaryResponse::new()';
      const upstream = typeof obj['upstream'] === 'string' ? `.upstream(BinaryUpstream::${pascalEnum(obj['upstream'])})` : '';
      const binary = (ind: number) => chain(base + upstream, calls, ind);
      return inline(`.respond_binary(${withExtra('BinaryResponse', binary, obj, ['binaryData', 'upstream', 'delay', 'primary'], indent + 4)})`);
    }
    case 'grpcStreamResponse':
      return inline(`.respond_grpc_stream(${grpcStreamExpr(obj, indent + 4)})`);
    case 'dnsResponse': {
      const typed = dnsResponseExpr(obj, indent + 4);
      if (typed) return inline(`.respond_dns(${typed})`);
      return { expectationExtra: ['dnsResponse', obj] }; // record shape the typed model cannot name
    }
    case 'httpOverrideForwardedRequest': {
      const setup: string[] = [];
      const calls: string[] = [];
      const ro = obj['requestOverride'];
      if (ro && typeof ro === 'object') {
        const r = renderHttpRequest(ro as Record<string, unknown>, ctx, indent, 'override_request');
        setup.push(...r.setup);
        calls.push(`.request_override(${r.expr})`);
      }
      const rso = obj['responseOverride'];
      if (rso && typeof rso === 'object') {
        const r = renderHttpResponse(rso as Record<string, unknown>, ctx, indent, 'override_response');
        setup.push(...r.setup);
        calls.push(`.response_override(${r.expr})`);
      }
      // No builder methods for these: set as fields through struct-update syntax.
      const fields = inheritedFields(obj);
      for (const [wireKey, field] of [['requestModifier', 'request_modifier'], ['responseModifier', 'response_modifier']] as const) {
        if (obj[wireKey] != null) fields.push(`${field}: Some(${jsonMacro(obj[wireKey], indent + 8)}),`);
      }
      const rt = obj['responseTemplate'];
      if (isObjR(rt)) {
        const r = templateExpr(rt, ctx, indent + 8);
        setup.push(...r.setup);
        fields.push(`response_template: Some(${r.expr}),`);
      }
      if (isObjR(obj['httpRequest'])) {
        const r = renderHttpRequest(obj['httpRequest'], ctx, indent, 'override_http_request');
        setup.push(...r.setup);
        fields.push(`http_request: Some(${r.expr}),`);
      }
      if (isObjR(obj['httpResponse'])) {
        const r = renderHttpResponse(obj['httpResponse'], ctx, indent, 'override_http_response');
        setup.push(...r.setup);
        fields.push(`http_response: Some(${r.expr}),`);
      }
      const known = ['requestOverride', 'responseOverride', 'requestModifier', 'responseModifier', 'responseTemplate', 'httpRequest', 'httpResponse', 'delay', 'primary'];
      const extra = extraField(unmodelled(obj, known), indent + 8);
      if (extra) fields.push(extra);
      const override = (ind: number) => chain('HttpOverrideForwardedRequest::new()', calls, ind);
      return { setup, expr: `.override_forwarded_request(${withFields('HttpOverrideForwardedRequest', override, fields, indent + 4)})` };
    }
    case 'httpForwardWithFallback': {
      const setup: string[] = [];
      const fwd = (obj['httpForward'] as Record<string, unknown>) ?? {};
      const fbResp = (obj['fallbackResponse'] as Record<string, unknown>) ?? {};
      const fbRendered = renderHttpResponse(fbResp, ctx, indent + 4, 'fallback_response');
      setup.push(...fbRendered.setup);
      const calls: string[] = [];
      const codes = obj['fallbackOnStatusCodes'];
      if (Array.isArray(codes)) calls.push(`.fallback_on_status_codes(vec![${codes.map((c) => numLit(Number(c))).join(', ')}])`);
      // Emitted whenever present, including `false` — an absent fallbackOnTimeout means TRUE
      // on the server, so dropping an explicit `false` would invert the selection.
      if (typeof obj['fallbackOnTimeout'] === 'boolean') calls.push(`.fallback_on_timeout(${obj['fallbackOnTimeout'] ? 'true' : 'false'})`);
      const head = `HttpForwardWithFallback::new(${forwardExpr(fwd, indent + 4)}, ${fbRendered.expr})`;
      const fallback = (ind: number) => chain(head, calls, ind);
      const fields = inheritedFields(obj);
      const known = ['httpForward', 'fallbackResponse', 'fallbackOnStatusCodes', 'fallbackOnTimeout', 'delay', 'primary'];
      const extra = extraField(unmodelled(obj, known), indent + 8);
      if (extra) fields.push(extra);
      return { setup, expr: `.forward_with_fallback(${withFields('HttpForwardWithFallback', fallback, fields, indent + 4)})` };
    }
    default:
      return { expectationExtra: [key, value] };
  }
}

const KNOWN_TOP_LEVEL_KEYS = new Set([
  'httpRequest',
  ...PRIMARY_ACTION_KEYS,
  'chaos',
  'beforeActions',
  'afterActions',
  'steps',
  'capture',
  'scenarioName',
  'scenarioState',
  'newScenarioState',
  'namespace',
  'id',
  'priority',
  'times',
  'timeToLive',
  // Edit-preserved siblings/actions now emitted typed (below / via primary dispatch).
  'httpResponses',
  'responseMode',
  'responseWeights',
  'switchAfter',
  'rateLimit',
  'crossProtocolScenarios',
  'percentage',
  'timestamp',
  'metadata',
]);

// ---------------------------------------------------------------------------
// Top-level emitter
// ---------------------------------------------------------------------------

export function standardToRust(matcher: StandardMatcher, action: StandardActionPayload, baseUrl: string): string {
  const { host, port } = clientHostPort(baseUrl);
  const exp = buildExpectationJson(matcher, action);
  const ctx = new Ctx();

  const setup: string[] = [];
  const expCalls: string[] = [];
  const expectationExtras: [string, unknown][] = [];

  // Request matcher.
  const reqObj = (exp['httpRequest'] as Record<string, unknown>) ?? {};
  const reqRendered = renderHttpRequest(reqObj, ctx, 4, 'request');
  let requestExpr: string;
  if (reqRendered.setup.length > 0) {
    setup.push(...reqRendered.setup);
    requestExpr = reqRendered.expr;
  } else {
    setup.push(pad(4) + `let request = ${reqRendered.expr};`);
    requestExpr = 'request';
  }

  // Primary (mutually-exclusive) action.
  for (const key of PRIMARY_ACTION_KEYS) {
    if (key in exp) {
      const rendered = renderPrimaryAction(key, exp[key], ctx, 4);
      if ('expectationExtra' in rendered) expectationExtras.push(rendered.expectationExtra);
      else {
        setup.push(...rendered.setup);
        expCalls.push(rendered.expr);
      }
      break;
    }
  }

  // Response sequence — httpResponses + responseMode + responseWeights + switchAfter.
  if (Array.isArray(exp['httpResponses'])) {
    const seqInner = pad(8);
    const items = (exp['httpResponses'] as unknown[]).map((r) => {
      const rr = renderHttpResponse(r as Record<string, unknown>, ctx, 8);
      setup.push(...rr.setup);
      return seqInner + rr.expr + ',';
    });
    expCalls.push('.http_responses(vec![\n' + items.join('\n') + '\n' + pad(4) + '])');
  }
  if (typeof exp['responseMode'] === 'string') expCalls.push(`.response_mode(ResponseMode::${pascalEnum(exp['responseMode'])})`);
  if (Array.isArray(exp['responseWeights'])) {
    expCalls.push(`.response_weights(vec![${(exp['responseWeights'] as unknown[]).map((n) => numLit(Number(n))).join(', ')}])`);
  }
  if (typeof exp['switchAfter'] === 'number') expCalls.push(`.switch_after(${numLit(exp['switchAfter'])})`);

  // Non-action siblings.
  if (isObjR(exp['rateLimit'])) expCalls.push(`.rate_limit(${rateLimitExpr(exp['rateLimit'], 8)})`);
  if (Array.isArray(exp['crossProtocolScenarios'])) {
    expCalls.push(`.cross_protocol_scenarios(${crossProtocolExpr(exp['crossProtocolScenarios'], 8)})`);
  }
  if (typeof exp['percentage'] === 'number') expCalls.push(`.percentage(${numLit(exp['percentage'])})`);

  // Steps replace the primary action when present.
  if (Array.isArray(exp['steps'])) {
    const inner = pad(8);
    const stepExprs: string[] = [];
    for (const step of exp['steps'] as unknown[]) {
      const r = renderStep(step as Record<string, unknown>, ctx, 8);
      setup.push(...r.setup);
      stepExprs.push(inner + r.expr + ',');
    }
    expCalls.length = 0; // steps take priority over any primary action
    expCalls.push('.steps(vec![\n' + stepExprs.join('\n') + '\n' + pad(4) + '])');
  }

  // Side-effect actions.
  for (const [wireKey, method] of [
    ['beforeActions', 'before_action'],
    ['afterActions', 'after_action'],
  ] as const) {
    const arr = exp[wireKey];
    if (Array.isArray(arr)) {
      for (const se of arr) {
        const r = renderExpectationAction(se as Record<string, unknown>, ctx, 8);
        setup.push(...r.setup);
        expCalls.push(`.${method}(${r.expr})`);
      }
    }
  }

  // Capture rules.
  if (Array.isArray(exp['capture'])) {
    for (const c of exp['capture'] as unknown[]) expCalls.push(`.capture_rule(${captureRuleExpr(c as Record<string, unknown>)})`);
  }

  // Chaos.
  if (exp['chaos'] && typeof exp['chaos'] === 'object') expCalls.push(`.chaos(${chaosExpr(exp['chaos'] as Record<string, unknown>, 8)})`);

  // Scenario bindings.
  if (typeof exp['scenarioName'] === 'string') expCalls.push(`.scenario_name(${rustStr(exp['scenarioName'])})`);
  if (typeof exp['scenarioState'] === 'string') expCalls.push(`.scenario_state(${rustStr(exp['scenarioState'])})`);
  if (typeof exp['newScenarioState'] === 'string') expCalls.push(`.new_scenario_state(${rustStr(exp['newScenarioState'])})`);

  // Namespace / id / priority / times / ttl.
  if (typeof exp['namespace'] === 'string') expCalls.push(`.namespace(${rustStr(exp['namespace'])})`);
  if (isObjR(exp['metadata'])) {
    for (const [k, v] of Object.entries(exp['metadata'])) expCalls.push(`.metadata_entry(${rustStr(k)}, ${jsonMacro(v, 8)})`);
  }
  if (typeof exp['id'] === 'string') expCalls.push(`.id(${rustStr(exp['id'])})`);
  if (typeof exp['priority'] === 'number') expCalls.push(`.priority(${numLit(exp['priority'])})`);
  if (exp['times'] && typeof exp['times'] === 'object') expCalls.push(`.times(${timesExpr(exp['times'] as Record<string, unknown>)})`);
  if (exp['timeToLive'] && typeof exp['timeToLive'] === 'object') expCalls.push(`.time_to_live(${ttlExpr(exp['timeToLive'] as Record<string, unknown>)})`);

  // `timestamp` has a public field but no builder method — set it typed after
  // construction (Option<String>), not via the raw `extra` map.
  const typedPostAssigns: string[] = [];
  if (typeof exp['timestamp'] === 'string') {
    typedPostAssigns.push(pad(4) + `expectation.timestamp = Some(${rustStr(exp['timestamp'])}.to_string());`);
  }

  // Any remaining unmodelled top-level key → the expectation's `extra` catch-all.
  for (const [k, v] of Object.entries(exp)) {
    if (!KNOWN_TOP_LEVEL_KEYS.has(k)) expectationExtras.push([k, v]);
  }

  // Assemble the Expectation binding.
  const needsMut = expectationExtras.length > 0 || typedPostAssigns.length > 0;
  const usesSerde =
    expectationExtras.length > 0 || [...setup, ...expCalls].some((l) => l.includes('serde_json::'));

  const body: string[] = [];
  body.push(`// Cargo.toml: mockserver-client = "${rustClientVersionRequirement()}"` + (usesSerde ? ', serde_json = "1"' : ''));
  body.push('use mockserver_client::*;');
  body.push('');
  body.push('fn main() -> mockserver_client::Result<()> {');
  body.push(pad(4) + `let client = ClientBuilder::new(${rustStr(host)}, ${port}).build()?;`);
  body.push('');
  for (const line of setup) body.push(line);
  body.push('');
  body.push(pad(4) + `let ${needsMut ? 'mut ' : ''}expectation = ` + chain(`Expectation::new(${requestExpr})`, expCalls, 4) + ';');
  for (const line of typedPostAssigns) body.push(line);
  for (const [k, v] of expectationExtras) {
    body.push(pad(4) + `expectation.extra.insert(${rustStr(k)}.to_string(), ${jsonMacro(v, 4)});`);
  }
  body.push(pad(4) + 'client.upsert(&[expectation])?;');
  body.push(pad(4) + 'Ok(())');
  body.push('}');
  return body.join('\n');
}
