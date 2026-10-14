/*
 * mockserver
 * http://mock-server.com
 *
 * Copyright (c) 2014 James Bloom
 * Licensed under the Apache License, Version 2.0
 */

/* eslint-disable */
/* tslint:disable */
/*
 * ---------------------------------------------------------------
 * ## THIS FILE WAS GENERATED VIA SWAGGER-TYPESCRIPT-API        ##
 * ##                                                           ##
 * ## AUTHOR: acacode                                           ##
 * ## SOURCE: https://github.com/acacode/swagger-typescript-api ##
 * ---------------------------------------------------------------
 */

export type Expectations = Expectation | Expectation[];

export type Expectation = {
  id?: string;
  priority?: number;
  percentage?: number;
  httpRequest?: RequestDefinition;
  httpResponse?: HttpResponse;
  httpResponseTemplate?: HttpTemplate;
  httpResponseClassCallback?: HttpClassCallback;
  httpResponseObjectCallback?: HttpObjectCallback;
  httpForward?: HttpForward;
  httpForwardTemplate?: HttpTemplate;
  httpForwardClassCallback?: HttpClassCallback;
  httpForwardObjectCallback?: HttpObjectCallback;
  httpOverrideForwardedRequest?: HttpOverrideForwardedRequest;
  /** forward the request upstream, but validate the request and/or response against an OpenAPI spec */
  httpForwardValidateAction?: HttpForwardValidateAction;
  /** forward the request upstream, returning a fallback mock response on a configured status code or timeout */
  httpForwardWithFallback?: HttpForwardWithFallback;
  httpError?: HttpError;
  httpSseResponse?: HttpSseResponse;
  /** respond with a mocked LLM (chat/completion/embedding/rerank/moderation) response */
  httpLlmResponse?: HttpLlmResponse;
  httpWebSocketResponse?: HttpWebSocketResponse;
  grpcStreamResponse?: GrpcStreamResponse;
  grpcBidiResponse?: GrpcBidiResponse;
  binaryResponse?: BinaryResponse;
  dnsResponse?: DnsResponse;
  times?: Times;
  timeToLive?: TimeToLive;
  chaos?: HttpChaosProfile;
  /** declarative protocol-agnostic rate limit / quota applied to matching requests */
  rateLimit?: RateLimit;
  beforeActions?: AfterAction | AfterAction[];
  afterActions?: AfterAction | AfterAction[];
  httpResponses?: HttpResponse[];
  responseMode?: "SEQUENTIAL" | "RANDOM" | "WEIGHTED" | "SWITCH";
  /** index-aligned with httpResponses; relative weights used when responseMode is WEIGHTED */
  responseWeights?: number[];
  /** requests served per response block before advancing when responseMode is SWITCH (default 1) */
  switchAfter?: number;
  steps?: ExpectationStep[];
  /** optional namespace (tenant) this expectation belongs to; when set, the expectation only matches requests carrying the configured matchNamespaceHeader with this value, isolated from other namespaces */
  namespace?: string;
  scenarioName?: string;
  scenarioState?: string;
  newScenarioState?: string;
  /** advance a (possibly different) scenario when a non-HTTP protocol event occurs */
  crossProtocolScenarios?: CrossProtocolScenario[];
  /** expectation-level capture rules that extract request values into named variables for later template reference */
  capture?: CaptureRule[];
  /** added to allow request and response log output to be used to create expectations */
  timestamp?: string;
};

/**
 * Advances a named scenario to a target state when a non-HTTP protocol event
 * occurs (a DNS query, WebSocket connect, gRPC request, or HTTP request),
 * letting a single expectation drive a cross-protocol state machine.
 */
export interface CrossProtocolScenario {
  /** the protocol event that triggers the transition */
  trigger: "DNS_QUERY" | "WEBSOCKET_CONNECT" | "GRPC_REQUEST" | "HTTP_REQUEST";
  /** optional substring filter on the event identifier; omit to match all events */
  matchPattern?: string;
  /** the scenario to advance */
  scenarioName: string;
  /** the state to transition the scenario to */
  targetState: string;
}

export interface ExpectationId {
  id: string;
}

export type OpenAPIExpectations = OpenAPIExpectation | OpenAPIExpectation[];

export interface OpenAPIExpectation {
  specUrlOrPayload: string | object;
  operationsAndResponses?: Record<string, string>;
}

export type RequestDefinition =
  | HttpRequest
  | OpenAPIDefinition
  | DnsRequestDefinition
  | BinaryRequestDefinition
  | ConditionalRequestDefinition;

/** DNS record type a {@link DnsRequestDefinition} matches on. */
export type DnsRecordType = "A" | "AAAA" | "CNAME" | "MX" | "SRV" | "TXT" | "PTR";

/** DNS record class a {@link DnsRequestDefinition} matches on. */
export type DnsRecordClass = "IN" | "CH" | "HS" | "ANY";

/**
 * A DNS request matcher — matches an inbound DNS query (used with a
 * `dnsResponse` action). Discriminated on the wire by the presence of `dnsName`.
 */
export interface DnsRequestDefinition {
  /** negate the whole matcher */
  not?: boolean;
  /** the queried DNS name to match (e.g. "example.com") */
  dnsName?: string;
  /** the queried record type to match */
  dnsType?: DnsRecordType;
  /** the queried record class to match (defaults to IN) */
  dnsClass?: DnsRecordClass;
}

/**
 * A raw binary (non-HTTP) request matcher — discriminated on the wire by the
 * presence of `binaryData`.
 */
export interface BinaryRequestDefinition {
  not?: boolean;
  /** base64-encoded binary payload to match */
  binaryData?: string;
  socketAddress?: SocketAddress;
}

/**
 * A conditional request matcher: match `if`, otherwise fall through to `then` /
 * `else`. Discriminated on the wire by the presence of an `if` sub-matcher.
 */
export interface ConditionalRequestDefinition {
  not?: boolean;
  if?: RequestDefinition;
  then?: RequestDefinition;
  else?: RequestDefinition;
}

/** HTTP protocol version a {@link HttpRequest} matches on. */
export type Protocol = "HTTP_1_1" | "HTTP_2" | "HTTP_3";

export interface HttpRequest {
  secure?: boolean;
  keepAlive?: boolean;
  respondBeforeBody?: boolean;
  /** HTTP protocol version the request was received over */
  protocol?: Protocol;
  method?: StringOrJsonSchema;
  path?: StringOrJsonSchema;
  pathParameters?: KeyToMultiValue;
  queryStringParameters?: KeyToMultiValue;

  /** request body matcher */
  body?: Body;
  headers?: KeyToMultiValue;
  cookies?: KeyToValue;
  socketAddress?: SocketAddress;

  /** JSON Web Token (JWT) request matcher */
  jwt?: Jwt;
}

/**
 * JSON Web Token (JWT) request matcher — decodes the token carried in a request header (no
 * signature verification) and matches its claims
 */
export interface Jwt {
  /** name of the request header carrying the JWT (default "authorization") */
  header?: string;
  /** authentication scheme prefix stripped from the header value before decoding (default "Bearer") */
  scheme?: string;
  /** claim-value criteria; each value may be an exact string, regex or "!"-negated form */
  claims?: Record<string, StringOrJsonSchema>;
  issuer?: StringOrJsonSchema;
  audience?: StringOrJsonSchema;
  algorithm?: StringOrJsonSchema;
}

export interface OpenAPIDefinition {
  specUrlOrPayload?: string;
  operationId?: string;
}

export interface HttpResponse {
  /** response delay */
  delay?: Delay;
  primary?: boolean;

  /** response body */
  body?: BodyWithContentType;
  cookies?: KeyToValue;

  /** connection options */
  connectionOptions?: ConnectionOptions;
  headers?: KeyToMultiValue;
  /** trailing headers sent after the response body (HTTP/2 / chunked HTTP/1.1 trailers) */
  trailers?: KeyToMultiValue;
  statusCode?: number;
  reasonPhrase?: string;
}

export interface HttpTemplate {
  /** response delay */
  delay?: Delay;
  primary?: boolean;
  templateType?: "VELOCITY" | "JAVASCRIPT" | "MUSTACHE";
  template?: string;
  templateFile?: string;
}

export interface HttpForward {
  /** response delay */
  delay?: Delay;
  primary?: boolean;
  host?: string;
  port?: number;
  scheme?: "HTTP" | "HTTPS";
}

/**
 * Forward the request to an upstream host and validate the request and/or the
 * response against an OpenAPI specification.
 */
export interface HttpForwardValidateAction {
  /** response delay */
  delay?: Delay;
  primary?: boolean;
  /** the OpenAPI spec to validate against, as an inline JSON/YAML payload, a URL, or a file/classpath reference */
  specUrlOrPayload?: string;
  host?: string;
  /** upstream port (defaults to 80) */
  port?: number;
  /** upstream scheme (defaults to HTTP) */
  scheme?: "HTTP" | "HTTPS";
  /** validate the outgoing request against the spec (default true) */
  validateRequest?: boolean;
  /** validate the upstream response against the spec (default true) */
  validateResponse?: boolean;
  /** STRICT fails the exchange on a validation error; LOG_ONLY only logs it (default STRICT) */
  validationMode?: "STRICT" | "LOG_ONLY";
}

/**
 * Forward a request to an upstream host; if upstream returns a configured status
 * code (default 500-599) or times out, return the fallback mock response instead.
 */
export interface HttpForwardWithFallback {
  /** response delay */
  delay?: Delay;
  primary?: boolean;
  /** the upstream forward target */
  httpForward?: HttpForward;
  /** the mock response returned when the upstream call fails over */
  fallbackResponse?: HttpResponse;
  /** upstream status codes that trigger the fallback (defaults to 500-599) */
  fallbackOnStatusCodes?: number[];
  /** whether an upstream timeout triggers the fallback */
  fallbackOnTimeout?: boolean;
}

export interface HttpClassCallback {
  /** response delay */
  delay?: Delay;
  primary?: boolean;
  callbackClass?: string;
}

/**
 * The part of the request a {@link CaptureRule} reads its value from.
 */
export type CaptureSource =
  | "jsonPath"
  | "xpath"
  | "header"
  | "queryStringParameter"
  | "cookie"
  | "pathParameter";

/**
 * An expectation-level capture rule: extracts a value from the matched request
 * and binds it to a named variable that response templates can reference.
 */
export interface CaptureRule {
  /** where in the request to read from */
  source?: CaptureSource;
  /** the JSONPath, XPath, or key/name driving the extraction */
  expression?: string;
  /** the variable name the extracted value is bound to */
  into?: string;
}

/**
 * A declarative, protocol-agnostic rate limit / quota applied to matching
 * requests. `fixed_window` uses `limit` + `windowMillis`; `token_bucket` uses
 * `burst` + `refillPerSecond`.
 */
export interface RateLimit {
  /** shared counter key; expectations with the same name share one counter (defaults to the expectation id) */
  name?: string;
  /** rate-limiting algorithm (defaults to fixed_window) */
  algorithm?: "fixed_window" | "token_bucket";
  /** fixed_window: max requests allowed per window before requests are rejected */
  limit?: number;
  /** fixed_window: window length in milliseconds */
  windowMillis?: number;
  /** token_bucket: bucket capacity in tokens */
  burst?: number;
  /** token_bucket: token refill rate per second */
  refillPerSecond?: number;
  /** status returned when over-limit (default 429) */
  errorStatus?: number;
  /** literal Retry-After header value; computed from the reset time when omitted */
  retryAfter?: string;
}

export interface HttpObjectCallback {
  /** response delay */
  delay?: Delay;
  primary?: boolean;
  clientId?: string;
  responseCallback?: boolean;
}

export type HttpOverrideForwardedRequest =
  | {
      delay?: Delay;
      primary?: boolean;
      requestOverride?: HttpRequest;
      requestModifier?: {
        path?: { regex?: string; substitution?: string };
        queryStringParameters?: { add?: KeyToMultiValue; replace?: KeyToMultiValue; remove?: string[] };
        headers?: { add?: KeyToMultiValue; replace?: KeyToMultiValue; remove?: string[] };
        cookies?: { add?: KeyToValue; replace?: KeyToValue; remove?: string[] };
      };
      responseOverride?: HttpResponse;
      responseModifier?: HttpResponseModifier;
    }
  | { delay?: Delay; primary?: boolean; httpRequest?: HttpRequest; httpResponse?: HttpResponse };

export interface HttpResponseModifierCondition {
  /** apply only when the response status code equals this value */
  statusCode?: number;
  /** apply only when the response status code is in this class range, e.g. "2xx" or "5xx" */
  statusCodeRange?: string;
  /** apply only when the response has this header */
  responseHasHeader?: string;
  /** apply only when the request had this header */
  requestHasHeader?: string;
}

export interface HttpResponseModifier {
  headers?: { add?: KeyToMultiValue; replace?: KeyToMultiValue; remove?: string[] };
  cookies?: { add?: KeyToValue; replace?: KeyToValue; remove?: string[] };
  /** gate this modifier on a condition over the request/response */
  condition?: HttpResponseModifierCondition;
  /** ordered chain of modifiers, each applied to the output of the previous */
  modifiers?: HttpResponseModifier[];
  /** RFC 6902 JSON Patch document applied to a forwarded JSON response body */
  jsonPatch?: unknown[];
  /** RFC 7386 JSON Merge Patch document applied to a forwarded JSON response body */
  jsonMergePatch?: object;
}

export interface HttpError {
  /** response delay */
  delay?: Delay;
  primary?: boolean;
  /** drop the connection; ignored when streamError is set (streamError takes precedence) */
  dropConnection?: boolean;
  responseBytes?: string;
  /** reset the matched request stream with this error code (HTTP/2 RST_STREAM / HTTP/3 RESET_STREAM) instead of returning a response; HTTP/1.1 has no stream concept so this falls back to dropping the connection */
  streamError?: number;
}

export interface Times {
  remainingTimes?: number;
  unlimited?: boolean;
}

export interface TimeToLive {
  timeUnit?: "DAYS" | "HOURS" | "MINUTES" | "SECONDS" | "MILLISECONDS" | "MICROSECONDS" | "NANOSECONDS";
  timeToLive?: number;
  unlimited?: boolean;
}

export type KeyToMultiValue =
  | { name?: string; values?: string[] }[]
  | { keyMatchStyle?: "MATCHING_KEY" | "SUB_SET"; [key: string]: any };

export type KeyToValue = { name?: string; value?: string }[] | Record<string, any>;

export type StringOrJsonSchema =
  | string
  | {
      not?: boolean;
      optional?: boolean;
      value?: string;
      schema?: any;
      parameterStyle?:
        | "SIMPLE"
        | "SIMPLE_EXPLODED"
        | "LABEL"
        | "LABEL_EXPLODED"
        | "MATRIX"
        | "MATRIX_EXPLODED"
        | "FORM_EXPLODED"
        | "FORM"
        | "SPACE_DELIMITED_EXPLODED"
        | "SPACE_DELIMITED"
        | "PIPE_DELIMITED_EXPLODED"
        | "PIPE_DELIMITED"
        | "DEEP_OBJECT";
    };

export interface SocketAddress {
  host?: string;
  port?: number;
  scheme?: "HTTP" | "HTTPS";
}

/**
 * request body matcher
 */
export type Body =
  | { not?: boolean; type?: "BINARY"; base64Bytes?: string; contentType?: string }
  | { not?: boolean; type?: "JSON"; json?: string; contentType?: string; matchType?: "STRICT" | "ONLY_MATCHING_FIELDS" }
  | Record<string, any>
  | { not?: boolean; type?: "JSON_SCHEMA"; jsonSchema?: any }
  | { not?: boolean; type?: "JSON_PATH"; jsonPath?: string }
  | { not?: boolean; type?: "PARAMETERS"; parameters?: KeyToMultiValue }
  | { not?: boolean; type?: "REGEX"; regex?: string }
  | { not?: boolean; type?: "STRING"; string?: string; contentType?: string; subString?: boolean }
  | string
  | { not?: boolean; type?: "XML"; xml?: string; contentType?: string }
  | { not?: boolean; type?: "XML_SCHEMA"; xmlSchema?: string }
  | { not?: boolean; type?: "XPATH"; xpath?: string }
  | { not?: boolean; type?: "FILE"; filePath?: string; contentType?: string; templateType?: "VELOCITY" | "MUSTACHE" }
  | {
      type: 'JSON_RPC';
      method: string;
      paramsSchema?: string;
      not?: boolean;
      optional?: boolean;
    }
  | {
      type: 'GRAPHQL';
      query: string;
      operationName?: string;
      variablesSchema?: string;
      not?: boolean;
      optional?: boolean;
    }
  | { not?: boolean; optional?: boolean; type: "ALL_OF"; bodyAllOf: Body[] }
  | ({ not?: boolean; type?: "BINARY"; base64Bytes?: string; contentType?: string } & {
      not?: boolean;
      type?: "JSON";
      json?: string;
      contentType?: string;
      matchType?: "STRICT" | "ONLY_MATCHING_FIELDS";
    } & Record<string, any> & { not?: boolean; type?: "JSON_SCHEMA"; jsonSchema?: any } & {
        not?: boolean;
        type?: "JSON_PATH";
        jsonPath?: string;
      } & { not?: boolean; type?: "PARAMETERS"; parameters?: KeyToMultiValue } & {
        not?: boolean;
        type?: "REGEX";
        regex?: string;
      } & { not?: boolean; type?: "STRING"; string?: string; contentType?: string; subString?: boolean } & {
        not?: boolean;
        type?: "XML";
        xml?: string;
        contentType?: string;
      } & { not?: boolean; type?: "XML_SCHEMA"; xmlSchema?: string } & {
        not?: boolean;
        type?: "XPATH";
        xpath?: string;
      });

/**
 * response body
 */
export type BodyWithContentType =
  | { not?: boolean; type?: "BINARY"; base64Bytes?: string; contentType?: string }
  | { not?: boolean; type?: "JSON"; json?: string; contentType?: string }
  | Record<string, any>
  | { not?: boolean; type?: "STRING"; string?: string; contentType?: string }
  | string
  | { not?: boolean; type?: "XML"; xml?: string; contentType?: string }
  | ({ not?: boolean; type?: "BINARY"; base64Bytes?: string; contentType?: string } & {
      not?: boolean;
      type?: "JSON";
      json?: string;
      contentType?: string;
    } & Record<string, any> & { not?: boolean; type?: "STRING"; string?: string; contentType?: string } & {
        not?: boolean;
        type?: "XML";
        xml?: string;
        contentType?: string;
      });

/**
 * delay distribution for variable response delays
 */
export interface DelayDistribution {
  type?: "UNIFORM" | "LOG_NORMAL" | "GAUSSIAN";
  min?: number;
  max?: number;
  median?: number;
  p99?: number;
  mean?: number;
  stdDev?: number;
}

/**
 * response delay
 */
export interface Delay {
  timeUnit?: string;
  value?: number;
  distribution?: DelayDistribution;
}

export interface SseEvent {
  event?: string;
  data?: string;
  id?: string;
  retry?: number;
  delay?: Delay;
}

export interface HttpSseResponse {
  statusCode?: number;
  headers?: KeyToMultiValue;
  events?: SseEvent[];
  closeConnection?: boolean;
  delay?: Delay;
  primary?: boolean;
}

export interface WebSocketMessage {
  text?: string;
  binary?: string;
  delay?: Delay;
}

/**
 * Per-incoming-frame response rule for a {@link HttpWebSocketResponse}; when a
 * received frame matches, the rule's {@link WebSocketMessage} responses are sent.
 */
export interface WebSocketFrameMatcher {
  frameType?: "TEXT" | "BINARY" | "PING" | "PONG" | "ANY";
  textMatcher?: string;
  responses?: WebSocketMessage[];
}

export interface HttpWebSocketResponse {
  subprotocol?: string;
  messages?: WebSocketMessage[];
  /** per-incoming-frame response rules; when set, an incoming frame matching a rule triggers that rule's responses */
  matchers?: WebSocketFrameMatcher[];
  closeConnection?: boolean;
  delay?: Delay;
  primary?: boolean;
}

export interface GrpcStreamMessage {
  json?: string;
  templateType?: "VELOCITY" | "JAVASCRIPT" | "MUSTACHE";
  delay?: Delay;
}

export interface GrpcStreamResponse {
  statusName?: string;
  statusMessage?: string;
  headers?: KeyToMultiValue;
  messages?: GrpcStreamMessage[];
  closeConnection?: boolean;
  delay?: Delay;
  primary?: boolean;
}

export interface GrpcBidiRule {
  matchJson?: string;
  responses?: GrpcStreamMessage[];
}

export interface GrpcBidiResponse {
  statusName?: string;
  statusMessage?: string;
  headers?: KeyToMultiValue;
  messages?: GrpcStreamMessage[];
  rules?: GrpcBidiRule[];
  closeConnection?: boolean;
  delay?: Delay;
  primary?: boolean;
}

export interface BinaryResponse {
  /** base64-encoded binary payload */
  binaryData?: string;
  delay?: Delay;
  primary?: boolean;
  /**
   * What happens upstream to the matched message on a connection MockServer relays to an upstream
   * (forwardBinaryRequestsMatchExpectations). Absent means ANSWER_ONLY. ANSWER_AND_FORWARD and
   * FORWARD_AND_REPLACE need binaryMessageFraming POSTGRESQL on the server.
   */
  upstream?: "ANSWER_ONLY" | "ANSWER_AND_FORWARD" | "FORWARD_AND_REPLACE";
}

export interface DnsRecord {
  name?: string;
  type?: "A" | "AAAA" | "CNAME" | "MX" | "SRV" | "TXT" | "PTR";
  dnsClass?: string;
  ttl?: number;
  value?: string;
  priority?: number;
  weight?: number;
  port?: number;
}

export interface DnsResponse {
  answerRecords?: DnsRecord[];
  authorityRecords?: DnsRecord[];
  additionalRecords?: DnsRecord[];
  responseCode?: "NOERROR" | "FORMERR" | "SERVFAIL" | "NXDOMAIN" | "NOTIMP" | "REFUSED";
  delay?: Delay;
  primary?: boolean;
}

export interface HttpChaosProfile {
  errorStatus?: number;
  errorProbability?: number;
  dropConnectionProbability?: number;
  retryAfter?: string;
  latency?: Delay;
  seed?: number;
  succeedFirst?: number;
  failRequestCount?: number;
  outageAfterMillis?: number;
  outageDurationMillis?: number;
  truncateBodyAtFraction?: number;
  malformedBody?: boolean;
  slowResponseChunkSize?: number;
  slowResponseChunkDelay?: Delay;
  quotaName?: string;
  quotaLimit?: number;
  quotaWindowMillis?: number;
  quotaErrorStatus?: number;
  degradationRampMillis?: number;
  /** rewrite the response body as a GraphQL error envelope (HTTP 200, {"data":null,"errors":[...]}) */
  graphqlErrors?: boolean;
  /** the error message in errors[0].message; defaults to 'simulated GraphQL error' when graphqlErrors is true and this is unset */
  graphqlErrorMessage?: string;
  /** optional value for errors[0].extensions.code (e.g. 'INTERNAL_SERVER_ERROR'); extensions object is omitted when unset */
  graphqlErrorCode?: string;
  /** when true (default), data is null; when false, attempt to preserve the original response body JSON as the data value */
  graphqlNullifyData?: boolean;
}

/** LLM provider whose wire format the mocked response is encoded in. */
export type LlmProvider =
  | "ANTHROPIC"
  | "OPENAI"
  | "OPENAI_RESPONSES"
  | "GEMINI"
  | "BEDROCK"
  | "AZURE_OPENAI"
  | "OLLAMA"
  | "COHERE"
  | "VOYAGE"
  | "MISTRAL"
  | "XAI"
  | "DEEPSEEK"
  | "GROQ"
  | "OPENROUTER"
  | "ORCAROUTER";

/** Role of the latest parsed conversation message, used by conversation predicates. */
export type LlmRole = "USER" | "ASSISTANT" | "TOOL" | "SYSTEM";

/** A single tool/function call the mocked assistant should emit. */
export interface LlmToolUse {
  id?: string;
  name?: string;
  /** the tool-call arguments as a JSON string */
  arguments?: string;
}

/** Token accounting reported on a mocked completion. */
export interface LlmUsage {
  inputTokens?: number;
  outputTokens?: number;
  cachedInputTokens?: number;
  cacheCreationTokens?: number;
  reasoningTokens?: number;
}

/** Streaming timing physics for a mocked streamed completion. */
export interface LlmStreamingPhysics {
  timeToFirstToken?: Delay;
  tokensPerSecond?: number;
  jitter?: number;
  seed?: number;
  /** stream sub-word tokens rather than whole words */
  subwordStreaming?: boolean;
}

/** A mocked LLM chat/completion response. */
export interface LlmCompletion {
  text?: string;
  toolCalls?: LlmToolUse[];
  stopReason?: string;
  usage?: LlmUsage;
  streaming?: boolean;
  streamingPhysics?: LlmStreamingPhysics;
  /** a JSON-schema string constraining structured output */
  outputSchema?: string;
  /** reject/repair output that does not conform to outputSchema */
  enforceOutputSchema?: boolean;
  model?: string;
  toolChoice?: string;
  reasoningText?: string;
  reasoningSignature?: string;
}

/** A mocked embedding response. */
export interface LlmEmbeddingResponse {
  dimensions?: number;
  deterministicFromInput?: boolean;
  seed?: number;
}

/** A mocked rerank response. */
export interface LlmRerankResponse {
  topN?: number;
  deterministicFromInput?: boolean;
  seed?: number;
}

/** A mocked moderation response. */
export interface LlmModerationResponse {
  flaggedCategories?: string[];
  model?: string;
}

/** A mocked content-filter annotation (per-category severity). */
export interface LlmContentFilter {
  hate?: string;
  sexual?: string;
  violence?: string;
  selfHarm?: string;
}

/**
 * Text normalization applied to the latest parsed message before the
 * contains/matches conversation predicates are evaluated.
 */
export interface LlmNormalizationOptions {
  collapseWhitespace?: boolean;
  lowercase?: boolean;
  sortJsonKeys?: boolean;
  /** drop a built-in set of volatile fields (ids, timestamps, etc.) before comparison */
  dropBuiltInVolatileFields?: boolean;
  /** additional field names to drop before comparison */
  dropVolatileFields?: string[];
}

/**
 * Predicates over the decoded LLM conversation that gate whether this mocked
 * response matches a given turn.
 */
export interface LlmConversationPredicates {
  /** match only at this zero-based turn index */
  turnIndex?: number;
  latestMessageContains?: string;
  /** regex source matched against the latest message */
  latestMessageMatches?: string;
  latestMessageRole?: LlmRole;
  /** match when the latest message contains a tool result for this tool name */
  containsToolResultFor?: string;
  /** opt-in fuzzy LLM-judged semantic match (off by default) */
  semanticMatchAgainst?: string;
  /** normalization applied before contains/matches */
  normalization?: LlmNormalizationOptions;
}

/**
 * Chaos/fault-injection profile for a mocked LLM response (errors, streaming
 * truncation, quotas, provider-specific error bodies).
 */
export interface LlmChaosProfile {
  errorStatus?: number;
  retryAfter?: string;
  /** 0.0-1.0; null/0 = never inject an error */
  errorProbability?: number;
  /** streaming: how the SSE event stream is truncated */
  truncateMode?: "NONE" | "MID_STREAM";
  /** 0.0-1.0 fraction of events to keep before truncating */
  truncateAtFraction?: number;
  /** streaming: emit a malformed (broken-JSON) SSE chunk */
  malformedSse?: boolean;
  /** makes a fractional errorProbability reproducible */
  seed?: number;
  /** stateful request quota: shared counter key */
  quotaName?: string;
  /** stateful request quota: max requests allowed per window */
  quotaLimit?: number;
  /** stateful request quota: window length in milliseconds */
  quotaWindowMillis?: number;
  /** stateful request quota: status when exceeded (default 429) */
  quotaErrorStatus?: number;
  /** stateful token quota: max tokens allowed per window (TPM/TPD) */
  tokenQuotaLimit?: number;
  /** stateful token quota: window length in milliseconds */
  tokenQuotaWindowMillis?: number;
  /** OVERLOAD | RATE_LIMIT | SERVER_ERROR -> emit the active provider's error body/status */
  errorKind?: "OVERLOAD" | "RATE_LIMIT" | "SERVER_ERROR";
  /** 0.0-1.0; null/0 = never inject a content-filter block */
  contentFilterBlockProbability?: number;
}

/**
 * A mocked LLM response action: encodes a completion, embedding, rerank, or
 * moderation response in the given provider's wire format, optionally gated by
 * conversation predicates and shaped by an LLM chaos profile.
 */
export interface HttpLlmResponse {
  /** response delay */
  delay?: Delay;
  primary?: boolean;
  provider?: LlmProvider;
  model?: string;
  completion?: LlmCompletion;
  embedding?: LlmEmbeddingResponse;
  rerank?: LlmRerankResponse;
  moderation?: LlmModerationResponse;
  contentFilter?: LlmContentFilter;
  conversationPredicates?: LlmConversationPredicates;
  chaos?: LlmChaosProfile;
}

/** The service-level indicator a single SLO objective is evaluated over. */
export type SloIndicator = "LATENCY_P50" | "LATENCY_P95" | "LATENCY_P99" | "ERROR_RATE";

/** How an observed SLI value is compared against an objective threshold. */
export type SloComparator = "LESS_THAN" | "LESS_THAN_OR_EQUAL" | "GREATER_THAN" | "GREATER_THAN_OR_EQUAL";

/** Which sample set an SLO objective is evaluated over. */
export type SloScope = "FORWARD" | "INBOUND";

/** A single service-level objective over the recorded SLI samples. */
export interface SloObjective {
  sli: SloIndicator;
  comparator: SloComparator;
  /** Threshold value (ms for latency, 0.0-1.0 for ERROR_RATE). */
  threshold: number;
  /** Sample scope (default "FORWARD"). */
  scope?: SloScope;
}

/** The time window an SloCriteria is evaluated over. */
export interface SloWindow {
  type?: "LOOKBACK" | "EXPLICIT";
  lookbackMillis?: number;
  fromEpochMillis?: number;
  toEpochMillis?: number;
}

/** A named set of service-level objectives over a time window. */
export interface SloCriteria {
  name?: string;
  window?: SloWindow;
  minimumSampleCount?: number;
  upstreamHosts?: string[];
  objectives: SloObjective[];
}

/** The evaluated result of a single SLO objective. */
export interface SloObjectiveResult {
  sli?: SloIndicator;
  comparator?: SloComparator;
  threshold?: number;
  observedValue?: number;
  result?: "PASS" | "FAIL" | "INCONCLUSIVE";
  detail?: string;
}

/** The overall verdict of an SLO evaluation (the AND of all objectives). */
export interface SloVerdict {
  name?: string;
  result?: "PASS" | "FAIL" | "INCONCLUSIVE";
  windowFromEpochMillis?: number;
  windowToEpochMillis?: number;
  sampleCount?: number;
  objectiveResults?: SloObjectiveResult[];
}

/** A single stage of a chaos experiment: host -> chaos profiles for a duration. */
export interface ChaosExperimentStage {
  durationMillis: number;
  profiles: { [host: string]: HttpChaosProfile };
}

/** A scheduled multi-stage chaos experiment definition. */
export interface ChaosExperiment {
  name?: string;
  loop?: boolean;
  stages: ChaosExperimentStage[];
}

/** Why explain passed over an expectation, in the order the gates are applied. */
export type ExplainedExpectationReason = 'NAMESPACE' | 'INACTIVE' | 'FIELDS_DIFFER' | 'NOT_OPERATOR' | 'CONVERSATION' | 'SCENARIO_STATE' | 'PERCENTAGE' | 'LOWER_PRIORITY';

/** How a request was handled when it arrived. */
export type ArrivalOutcome = 'MATCHED' | 'NO_MATCH' | 'FORWARDED_WITHOUT_EXPECTATION' | 'RESPONDED_WITHOUT_EXPECTATION' | 'UNKNOWN';

/** A suggested JSON patch (RFC 6902) of an expectation that would make it match; nothing is applied. */
export interface SuggestedFix {
  patch?: { op: string; path: string; value?: any }[];
  patchedFields?: string[];
  unpatchedFields?: { field: string; reason: string }[];
  matchesAfterPatch?: boolean;
}

/**
 * One expectation as explain, debugMismatch or explainUnmatched evaluated it (OpenAPI schema
 * ExplainedExpectation). The matched expectation has no reason; a skipped or shadowed one has a
 * reason and an explanation.
 */
export interface ExplainedExpectation {
  expectationId?: string;
  expectationMethod?: string;
  expectationPath?: string;
  /** 1-based position in the order MockServer evaluates expectations */
  position?: number;
  priority?: number;
  reason?: ExplainedExpectationReason;
  explanation?: string;
  /** whether the request matches the request matcher; absent when an earlier gate decided */
  matches?: boolean;
  matchedFieldCount?: number;
  totalFieldCount?: number;
  differingFieldCount?: number;
  /** for each differing field, the differences found */
  differences?: { [field: string]: string[] };
  /** for each differing field, a hint on how to change the expectation */
  remediation?: { [field: string]: string };
  /** explainUnmatched with suggestFixes only */
  suggestedFix?: SuggestedFix;
}

/** The recorded request explain re-checked. */
export interface ExplainedRequest {
  timestamp?: string;
  method?: string;
  path?: string;
  /** true when the event log kept only the start of the body */
  bodyTruncated?: boolean;
  bodyTruncatedNote?: string;
}

/** How the request was handled when it arrived, read from the event log. */
export interface Arrival {
  outcome?: ArrivalOutcome;
  /** outcome MATCHED only */
  expectationId?: string;
  /** outcome MATCHED only */
  expectationStillStored?: boolean;
  /** present only when the request was forwarded and the forward failed, so MockServer answered 502 */
  forwardFailure?: ArrivalForwardFailure;
}

/** Why a forward failed: the same facts the MCP explain_forward_failure tool gives for the request. */
export interface ArrivalForwardFailure {
  /** the status MockServer answered with (502) */
  statusCode?: number;
  /** why the forward failed, redacted as the event log is and capped at 2048 characters */
  reason?: string;
  /** present and true when the reason was capped */
  reasonTruncated?: boolean;
  /** whether MockServer named the cause; when false the reason is the raw error */
  causeNamed?: boolean;
  /** whether the forward appears to have gone back to MockServer itself */
  forwardLoopSuspected?: boolean;
  /** what to look at next, present when causeNamed is false */
  hint?: string;
}

/**
 * Present only when narration was asked for: a plain-language summary written by the configured LLM
 * backend, or why none is available. It is AI-generated and can be wrong; the other fields are the facts.
 */
export interface ExplanationNarration {
  /** whether the LLM returned a summary */
  available?: boolean;
  /** always true when available; the text was written by an LLM, not by MockServer */
  aiGenerated?: boolean;
  /** a label to show beside the text, saying it is AI-generated and can be wrong */
  label?: string;
  provider?: string;
  /** the model that wrote the text, when known */
  model?: string;
  text?: string;
  /** when not available, why */
  reason?: string;
}

/** Which expectation would serve a recorded request now, and why (OpenAPI schema RequestExplanation). */
export interface RequestExplanation {
  correlationId?: string;
  timestamp?: string;
  recomputed?: boolean;
  note?: string;
  request?: ExplainedRequest;
  atArrival?: Arrival;
  /** absent when the outcome at arrival is RESPONDED_WITHOUT_EXPECTATION or UNKNOWN */
  sameOutcomeAsAtArrival?: boolean;
  expectationsChangedSince?: boolean;
  expectationsLastChanged?: string;
  matchedExpectation?: ExplainedExpectation;
  closestExpectationId?: string;
  skippedExpectations?: ExplainedExpectation[];
  shadowedExpectations?: ExplainedExpectation[];
  totalExpectations?: number;
  evaluatedExpectations?: number;
  truncated?: boolean;
  /** present only when truncated is true */
  maxExpectationsEvaluated?: number;
  narration?: ExplanationNarration;
}

/** The result of debugMismatch: each active expectation compared with the request. */
export interface DebugMismatchResult {
  correlationId?: string;
  timestamp?: string;
  totalExpectations?: number;
  evaluatedExpectations?: number;
  truncated?: boolean;
  maxExpectationsEvaluated?: number;
  /** the expectation with the fewest differing fields; absent when every expectation matches */
  closestMatch?: { expectationId: string; matchedFields: number; totalFields: number };
  results?: ExplainedExpectation[];
}

/** One recent unmatched request and its closest expectations, closest first. */
export interface UnmatchedRequest {
  timestamp?: string;
  method?: string;
  path?: string;
  closestExpectations?: ExplainedExpectation[];
  totalExpectationsEvaluated?: number;
}

/** The result of explainUnmatched. */
export interface ExplainUnmatchedResult {
  correlationId?: string;
  timestamp?: string;
  unmatchedRequestCount?: number;
  truncated?: boolean;
  unmatchedRequests?: UnmatchedRequest[];
  narration?: ExplanationNarration;
}

/** Options for explainUnmatched. */
export interface ExplainUnmatchedOptions {
  /** how many recent unmatched requests to analyse (server default 10) */
  limit?: number;
  /** add a suggestedFix to each of the five closest non-matching expectations */
  suggestFixes?: boolean;
}

/**
 * The kind of a {@link LoadStage} in a {@link LoadProfile}.
 *
 * - `VU` — closed model: hold or ramp the number of concurrent virtual users.
 * - `RATE` — open model: hold or ramp a target arrival rate (iterations/second).
 * - `PAUSE` — drive no load for the stage's `durationMillis`.
 */
export type LoadStageType = "VU" | "RATE" | "PAUSE";

/**
 * The interpolation curve used to ramp a value (virtual users or arrival rate)
 * from a start setpoint to an end setpoint across a ramping {@link LoadStage}.
 */
export type RampCurve = "LINEAR" | "EXPONENTIAL" | "QUADRATIC";

/**
 * One stage of a {@link LoadProfile}: a contiguous slice of the run holding or
 * ramping a setpoint for `durationMillis`. Stages run in sequence.
 *
 * - `VU` stages hold `vus`, or ramp from `startVus` to `endVus` along `curve`.
 * - `RATE` stages hold `rate`, or ramp from `startRate` to `endRate`
 *   (iterations/second) along `curve`, optionally capped at `maxVus`.
 * - `PAUSE` stages drive no load and use only `durationMillis`.
 */
export interface LoadStage {
  type: LoadStageType;
  /** length of this stage in milliseconds (> 0) */
  durationMillis: number;
  /** ramp curve for ramping VU/RATE stages (defaults to LINEAR) */
  curve?: RampCurve;
  /** VU hold: the fixed number of virtual users */
  vus?: number;
  /** VU ramp: virtual users at the start of the ramp */
  startVus?: number;
  /** VU ramp: virtual users at the end of the ramp */
  endVus?: number;
  /** RATE hold: the fixed arrival rate in iterations/second */
  rate?: number;
  /** RATE ramp: arrival rate at the start of the ramp (iterations/second) */
  startRate?: number;
  /** RATE ramp: arrival rate at the end of the ramp (iterations/second) */
  endRate?: number;
  /** RATE only: optional cap on virtual users used to drive the rate */
  maxVus?: number;
}

/**
 * A named, declarative load shape that expands into ordinary {@link LoadStage}s.
 * Use a shape OR an explicit `stages` list on a {@link LoadProfile}, not both —
 * only the parameters its `type` needs are read, the rest are ignored.
 *
 * - `SPIKE` — ramp from `baseline` to `peak`, hold, ramp back down, and
 *   optionally hold `baseline` again (`baseline`, `peak`, `rampUpMillis`,
 *   `holdMillis`, `rampDownMillis`, `recoveryHoldMillis`).
 * - `STAIRS` — a flight of pure-hold steps, each one `step` higher (`start`,
 *   `step`, `steps`, `stepDurationMillis`).
 * - `RAMP_HOLD` — ramp from 0 to `target` then hold (`target`, `rampMillis`,
 *   `holdMillis`).
 */
export type LoadShapeType = "SPIKE" | "STAIRS" | "RAMP_HOLD";

/**
 * What a {@link LoadShape} drives: `VU` = concurrent virtual users (closed
 * model); `RATE` = arrival rate in iterations/second (open model). The shape
 * expands into ordinary {@link LoadStage}s of this type.
 */
export type LoadShapeMetric = "VU" | "RATE";

/**
 * A declarative named load shape that expands into ordinary {@link LoadStage}s.
 * Set a `shape` OR an explicit `stages` list on a {@link LoadProfile}, not both.
 */
export interface LoadShape {
  type: LoadShapeType;
  /** what the shape drives (defaults to VU) */
  metric?: LoadShapeMetric;
  /** ramp curve used for the shape's ramps (defaults to LINEAR) */
  curve?: RampCurve;
  /** SPIKE: the level held before and after the spike */
  baseline?: number;
  /** SPIKE: the level held at the top of the spike */
  peak?: number;
  /** SPIKE: duration of the baseline to peak ramp */
  rampUpMillis?: number;
  /** SPIKE: duration to hold at the peak; RAMP_HOLD: duration to hold at the target */
  holdMillis?: number;
  /** SPIKE: duration of the peak to baseline ramp */
  rampDownMillis?: number;
  /** SPIKE (optional): duration to hold at baseline after the down ramp */
  recoveryHoldMillis?: number;
  /** STAIRS: the level of the first step */
  start?: number;
  /** STAIRS: how much each step rises above the previous one */
  step?: number;
  /** STAIRS: the number of steps */
  steps?: number;
  /** STAIRS: how long each step holds at its level */
  stepDurationMillis?: number;
  /** RAMP_HOLD: the level ramped up to (from 0) and then held */
  target?: number;
  /** RAMP_HOLD: duration of the 0 to target ramp */
  rampMillis?: number;
}

/**
 * Traffic profile that shapes a {@link LoadScenario} over time as EITHER an
 * ordered list of {@link LoadStage}s run in sequence OR a single named
 * {@link LoadShape} (which expands into stages). Set one, not both; if both are
 * set the explicit `stages` win. Each stage holds or ramps virtual users
 * (`VU`), an arrival rate (`RATE`), or drives no load (`PAUSE`).
 */
export interface LoadProfile {
  stages?: LoadStage[];
  /** a declarative named shape, as an alternative to an explicit `stages` list */
  shape?: LoadShape;
}

/**
 * Where a {@link LoadCapture} extracts its value from a step's response.
 *
 * - `BODY_JSONPATH` — a JSONPath over the response body.
 * - `HEADER` — a response header value (by name).
 * - `BODY_REGEX` — a regex over the response body string (capture group 1).
 */
export type LoadCaptureSource = "BODY_JSONPATH" | "HEADER" | "BODY_REGEX";

/**
 * A declarative cross-step capture / correlation rule: extracts a value from a
 * step's response and binds it to a variable name that a later step in the same
 * iteration can reference from its templated request fields via
 * `$iteration.captured.<name>` (Velocity) / `{{iteration.captured.<name>}}`
 * (Mustache). Best-effort: on no match it falls back to `defaultValue` (when
 * set) or leaves the variable unset, never failing the run.
 */
export interface LoadCapture {
  /** the variable name later steps reference (e.g. 'token') */
  name: string;
  source: LoadCaptureSource;
  /** the JSONPath, header name, or regex driving the extraction */
  expression: string;
  /** optional fallback value bound when extraction yields nothing */
  defaultValue?: string;
}

/**
 * A single request step within a {@link LoadScenario} iteration.
 */
export interface LoadStep {
  name?: string;
  labels?: { [key: string]: string };
  /** optional pause before issuing this step's request */
  thinkTime?: Delay;
  request: HttpRequest;
  /** optional cross-step capture rules applied to this step's response */
  captures?: LoadCapture[];
  /** relative selection weight, used only when stepSelection is WEIGHTED (> 0) */
  weight?: number;
}

/**
 * The per-run metric a {@link LoadThreshold} evaluates.
 *
 * Latency percentiles are in milliseconds; `ERROR_RATE` is a 0.0-1.0 fraction;
 * `THROUGHPUT_RPS` is requests/second over the run's elapsed time.
 */
export type LoadThresholdMetric =
  | "LATENCY_P50"
  | "LATENCY_P95"
  | "LATENCY_P99"
  | "LATENCY_P999"
  | "ERROR_RATE"
  | "THROUGHPUT_RPS";

/**
 * How the observed per-run value is compared to a {@link LoadThreshold}'s value.
 */
export type LoadThresholdComparator =
  | "LESS_THAN"
  | "LESS_THAN_OR_EQUAL"
  | "GREATER_THAN"
  | "GREATER_THAN_OR_EQUAL";

/**
 * An in-run pass/fail threshold for a load scenario: a per-run metric compared
 * against a value. All thresholds must hold for the run verdict to be PASS
 * (logical AND); any breach makes the verdict FAIL.
 */
export interface LoadThreshold {
  metric: LoadThresholdMetric;
  comparator: LoadThresholdComparator;
  /** the threshold value (ms for latency, 0.0-1.0 for ERROR_RATE, rps for THROUGHPUT_RPS) */
  threshold: number;
}

/**
 * A single per-threshold result behind a load run's verdict.
 */
export interface LoadThresholdResult {
  metric: LoadThresholdMetric;
  comparator: LoadThresholdComparator;
  threshold: number;
  /** the observed per-run value at evaluation time */
  observed: number;
  satisfied: boolean;
}

/**
 * How a target per-virtual-user iteration cycle (think-time pacing) is derived
 * for the closed-model VU loop.
 *
 * - `NONE` — no pacing (immediate reschedule).
 * - `CONSTANT_PACING` — `value` is the target cycle in milliseconds.
 * - `CONSTANT_THROUGHPUT` — `value` is the target iterations/second per VU.
 */
export type LoadPacingMode = "NONE" | "CONSTANT_PACING" | "CONSTANT_THROUGHPUT";

/**
 * Adaptive iteration pacing (think-time) for a load scenario. Applies only to
 * the closed-model VU loop — open-model RATE iterations ignore it.
 */
export interface LoadPacing {
  mode: LoadPacingMode;
  /** ms (CONSTANT_PACING) or iterations/second per VU (CONSTANT_THROUGHPUT); > 0 when mode is not NONE */
  value: number;
}

/**
 * The format of a {@link LoadFeeder}'s raw inline `data`.
 */
export type LoadFeederFormat = "CSV" | "JSON";

/**
 * How a {@link LoadFeeder} chooses a row each iteration.
 *
 * - `CIRCULAR` (default) — cycle rows, never exhausting.
 * - `RANDOM` — pick a uniformly random row each iteration.
 * - `SEQUENTIAL` — use each row once in order, completing the run when exhausted.
 */
export type LoadFeederStrategy = "CIRCULAR" | "RANDOM" | "SEQUENTIAL";

/**
 * Parameterized test data (a data feeder) for a load scenario: an inline
 * dataset from which one row is selected per iteration and exposed to that
 * iteration's templated request as `$iteration.data.<column>` (Velocity) /
 * `{{iteration.data.<column>}}` (Mustache). Supply EITHER `rows` (the primary
 * form) OR `data` + `format`; when both are given `rows` wins.
 */
export interface LoadFeeder {
  /** inline dataset: a list of column-name to value maps, one per row */
  rows?: { [column: string]: string }[];
  /** optional raw inline dataset parsed server-side into rows per `format` */
  data?: string;
  /** the format of `data` (required when `data` is set) */
  format?: LoadFeederFormat;
  /** how a row is chosen each iteration (defaults to CIRCULAR) */
  strategy?: LoadFeederStrategy;
}

/**
 * How each iteration of a {@link LoadScenario} selects which steps to run.
 *
 * - `SEQUENTIAL` (default) — run ALL steps in declared order (a user journey).
 * - `WEIGHTED` — run exactly ONE step per iteration chosen at random
 *   proportional to each step's `weight` (mixed-workload modelling).
 */
export type LoadStepSelection = "SEQUENTIAL" | "WEIGHTED";

/**
 * A server-driven load-injection scenario. MockServer drives the `steps`
 * against the configured traffic `profile`. Register a scenario with
 * `loadScenario(...)`; run registered scenarios with `startLoadScenarios(...)`.
 * Registration is allowed at any time, but starting a scenario requires the
 * server to be started with `loadGenerationEnabled=true`.
 */
export interface LoadScenario {
  /** unique name used to address the scenario in the registry */
  name: string;
  templateType?: "VELOCITY" | "MUSTACHE";
  labels?: { [key: string]: string };
  /** optional hard cap on the total number of requests sent */
  maxRequests?: number;
  /** optional delay before the scenario begins once started */
  startDelayMillis?: number;
  /** optional in-run pass/fail thresholds; the run carries a verdict iff all hold */
  thresholds?: LoadThreshold[];
  /** when true, a FAIL verdict aborts the run early (defaults to false) */
  abortOnFail?: boolean;
  /** suppress abortOnFail for the first N milliseconds of the run */
  abortGraceMillis?: number;
  /** adaptive per-VU iteration pacing (think-time) */
  pacing?: LoadPacing;
  /** parameterized inline test data exposed to each iteration's templates */
  feeder?: LoadFeeder;
  /** how each iteration selects which steps to run (defaults to SEQUENTIAL) */
  stepSelection?: LoadStepSelection;
  profile: LoadProfile;
  steps: LoadStep[];
}

/**
 * Lifecycle state of a registered load scenario in the registry.
 *   LOADED    - registered, not yet started
 *   PENDING   - started, waiting out its startDelayMillis
 *   RUNNING   - actively driving load
 *   COMPLETED - finished naturally
 *   STOPPED   - stopped before completion
 */
export type LoadScenarioState = "LOADED" | "PENDING" | "RUNNING" | "COMPLETED" | "STOPPED";

/**
 * Live runtime status of a load scenario run (carried on a registry entry under
 * `status` while/after the scenario runs).
 */
export interface LoadScenarioStatus {
  state: string;
  name?: string;
  elapsedMillis?: number;
  currentVus?: number;
  requestsSent?: number;
  succeeded?: number;
  failed?: number;
  p50Millis?: number;
  p95Millis?: number;
  p99Millis?: number;
  /** 99.9th-percentile coordinated-omission-corrected latency (ms) */
  p999Millis?: number;
  /** iterations due but never dispatched because a safety cap was hit */
  droppedIterations?: number;
  /** in-run threshold verdict; absent when no thresholds were evaluated */
  verdict?: LoadVerdict;
  /** true when this run was terminated early by an abortOnFail threshold breach */
  abortedByThreshold?: boolean;
  /** per-threshold results behind the verdict (present when thresholds were evaluated) */
  thresholdResults?: LoadThresholdResult[];
  runId?: string;
  startedAt?: number;
  endedAt?: number;
  labels?: { [key: string]: string };
  definition?: LoadScenario;
}

/**
 * In-run threshold verdict for a load scenario run: `PASS` (all thresholds
 * satisfied) or `FAIL` (any breached). A terminal `FAIL` should be mapped by
 * clients to a non-zero CI exit code.
 */
export type LoadVerdict = "PASS" | "FAIL";

/**
 * End-of-run summary report for a load scenario run (the JSON form returned by
 * `getLoadScenarioReport(name)`). A JUnit-XML rendering of the same data is
 * returned as a string when the report endpoint is called with `format=junit`.
 */
export interface LoadScenarioReport {
  scenario?: string;
  runId?: string;
  state?: LoadScenarioState;
  /** in-run threshold verdict; absent when no thresholds were evaluated */
  verdict?: LoadVerdict;
  /** true when this run was terminated early by an abortOnFail threshold breach */
  abortedByThreshold?: boolean;
  timing?: {
    startedAtEpochMillis?: number;
    /** epoch-millis the run ended; null while still running */
    endedAtEpochMillis?: number | null;
    durationMillis?: number;
  };
  counts?: {
    requestsSent?: number;
    succeeded?: number;
    failed?: number;
    droppedIterations?: number;
    /** failed / max(1, requestsSent) */
    errorRate?: number;
  };
  latencyMillis?: {
    p50?: number;
    p95?: number;
    p99?: number;
    p999?: number;
  };
  /** per-threshold results behind the verdict */
  thresholdResults?: LoadThresholdResult[];
}

/**
 * Explicit network target for the steps generated by
 * `generateLoadScenarioFromOpenAPI(...)` / `generateLoadScenarioFromRecording(...)`.
 * Overrides the spec's `servers[0]` (OpenAPI) or each recorded request's own
 * Host/secure routing (recording).
 */
export interface LoadGenerationTarget {
  host?: string;
  port?: number;
  scheme?: "http" | "https";
}

/**
 * Request body for `generateLoadScenarioFromOpenAPI(...)`: seed a load scenario
 * from an OpenAPI specification (one step per operation).
 */
export interface GenerateLoadScenarioFromOpenAPIRequest {
  /** the generated scenario name (the unique registry key) */
  name: string;
  /** the OpenAPI spec as an inline JSON/YAML payload, a URL, or a file/classpath reference */
  specUrlOrPayload: string;
  /** explicit network target for every generated step (overrides the spec's servers[0]) */
  target?: LoadGenerationTarget;
  /** optional traffic profile; a conservative default is applied when omitted */
  profile?: LoadProfile;
}

/**
 * How `generateLoadScenarioFromRecording(...)` turns recorded requests into steps.
 *
 * - `VERBATIM` (default) — one step per recorded request, in recorded order.
 * - `TEMPLATIZED` — one step per unique (method, templatised-path) route,
 *   ordered by descending hit frequency.
 */
export type LoadRecordingMode = "VERBATIM" | "TEMPLATIZED";

/**
 * Request body for `generateLoadScenarioFromRecording(...)`: seed a load
 * scenario from traffic previously recorded by MockServer in proxy mode.
 */
export interface GenerateLoadScenarioFromRecordingRequest {
  /** the generated scenario name (the unique registry key) */
  name: string;
  /** VERBATIM (one step per recorded request) or TEMPLATIZED (one step per route) */
  mode?: LoadRecordingMode;
  /** optional matcher selecting which recorded requests to include (absent = all) */
  requestFilter?: HttpRequest;
  /** optional cap on the number of VERBATIM steps (keeps the first N requests) */
  maxSteps?: number;
  /** explicit network target applied to every generated step */
  target?: LoadGenerationTarget;
  /** optional traffic profile; a conservative default is applied when omitted */
  profile?: LoadProfile;
}

/**
 * Result of `generateLoadScenarioFromOpenAPI(...)` /
 * `generateLoadScenarioFromRecording(...)`: the generated scenario was loaded
 * (registered) and is returned so it can be shown and edited before a run.
 */
export interface LoadScenarioGenerationResult {
  status: "loaded";
  name: string;
  state: LoadScenarioState;
  scenario: LoadScenario;
}

/**
 * A single entry in the load scenario registry, as returned by
 * `getLoadScenario(name)` and within `loadScenarios()`.
 */
export interface LoadScenarioEntry {
  name: string;
  state: LoadScenarioState;
  definition?: LoadScenario;
  /** present once the scenario has been started */
  status?: LoadScenarioStatus;
}

/**
 * Result of registering a load scenario via `loadScenario(...)`.
 */
export interface LoadScenarioRegistration {
  name: string;
  state: LoadScenarioState;
}

/**
 * Result of listing the registry via `loadScenarios()`.
 */
export interface LoadScenarioList {
  scenarios: LoadScenarioEntry[];
}

/**
 * Result of starting scenarios via `startLoadScenarios(...)` /
 * `runLoadScenario(...)`.
 */
export interface LoadScenarioStartResult {
  started: LoadScenarioRegistration[];
  status: string;
}

/**
 * Result of stopping scenarios via `stopLoadScenarios(...)`.
 */
export interface LoadScenarioStopResult {
  stopped: LoadScenarioRegistration[];
  status: string;
}

export interface AfterAction {
  httpRequest?: HttpRequest;
  httpClassCallback?: HttpClassCallback;
  httpObjectCallback?: HttpObjectCallback;
  delay?: Delay;
  /** before-actions only: when true (the default) the response waits for this action */
  blocking?: boolean;
  /** before-actions only: max wait for a blocking action */
  timeout?: Delay;
  /** before-actions only: outcome when a blocking action fails or times out */
  failurePolicy?: "FAIL_FAST" | "BEST_EFFORT";
}

/**
 * A single step in an ordered multi-action expectation pipeline.
 *
 * Each step carries exactly ONE action target and a {@link responder} flag.
 * Steps without `responder = true` are side-effects (fire-and-forget webhooks/callbacks).
 * Exactly one step in the list must be marked as the responder; that step's action
 * produces the HTTP response.
 */
export interface ExpectationStep {
  /** Side-effect / webhook target */
  httpRequest?: HttpRequest;
  httpClassCallback?: HttpClassCallback;
  httpObjectCallback?: HttpObjectCallback;
  /** Forward targets (usable as side-effect or responder) */
  httpForward?: HttpForward;
  httpOverrideForwardedRequest?: HttpOverrideForwardedRequest;
  /** Response targets (responder only) */
  httpResponse?: HttpResponse;
  httpError?: HttpError;
  /** When true, this step's action produces the HTTP response */
  responder?: boolean;
  delay?: Delay;
  /** Side-effect steps only: when true (the default) the pipeline waits for completion */
  blocking?: boolean;
  /** Side-effect steps only: maximum time to wait for a blocking step */
  timeout?: Delay;
  /** Side-effect steps only: what to do when a blocking step fails or times out */
  failurePolicy?: "FAIL_FAST" | "BEST_EFFORT";
}

/**
 * connection options
 */
export interface ConnectionOptions {
  suppressContentLengthHeader?: boolean;
  contentLengthHeaderOverride?: number;
  suppressConnectionHeader?: boolean;
  chunkSize?: number;
  chunkDelay?: Delay;
  keepAliveOverride?: boolean;
  closeSocket?: boolean;

  /** response delay */
  closeSocketDelay?: Delay;
}

/**
 * verification
 */
export type Verification = {
  expectationId?: ExpectationId;
  httpRequest?: RequestDefinition;
  times?: VerificationTimes;
  maximumNumberOfRequestToReturnInVerificationFailure?: number;
};

/**
 * number of request to verify
 */
export interface VerificationTimes {
  atLeast?: number;
  atMost?: number;
}

/**
 * verification sequence
 */
export type VerificationSequence = {
  expectationIds?: ExpectationId[];
  httpRequests?: RequestDefinition[];
  maximumNumberOfRequestToReturnInVerificationFailure?: number;
};

/**
 * list of ports
 */
export interface Ports {
  ports?: number[];
}

/**
 * verification sequence
 */
export type HttpRequestAndHttpResponse = {
    httpRequest?: HttpRequest;
    httpResponse?: HttpResponse;
    timestamp?: string;
};

export interface CrudExpectationsDefinition {
  basePath: string;
  idField?: string;
  idStrategy?: "AUTO_INCREMENT" | "UUID";
  initialData?: object[];
}

export type ClearUpdatePayload = RequestDefinition | ExpectationId;

export interface RetrieveUpdateParams {
  /** changes response format, default if not specificed is "json", supported values are "java", "json", "log_entries" */
  format?: "java" | "json" | "log_entries";

  /** specifies the type of object that is retrieve, default if not specified is "requests", supported values are "logs", "requests", "recorded_expectations", "active_expectations" */
  type?: "logs" | "requests" | "request_responses" | "recorded_expectations" | "active_expectations";
}
