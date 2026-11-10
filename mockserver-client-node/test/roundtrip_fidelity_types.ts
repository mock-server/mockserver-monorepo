/*
 * TYPE-LEVEL fidelity gate for the Node client (cross-language fidelity harness,
 * manifest key "node").
 *
 * The Node client is a thin passthrough with NO runtime expectation model, so
 * fidelity can only be gated at the TYPE level: does the generated `Expectation`
 * type (mockServer.d.ts, produced by swagger-typescript-api from the server's
 * OpenAPI) actually declare every field the server accepts?
 *
 * How this file works as a self-arming gate:
 *
 *   - Each `const … : Expectation = { … }` below is a TYPED OBJECT LITERAL that
 *     mirrors a shared fixture (test-fixtures/expectations/*.json). TypeScript
 *     runs EXCESS-PROPERTY checks on object literals assigned to a typed target,
 *     so if a fixture uses a field the `Expectation` type is MISSING, this file
 *     fails to compile — that compile error IS the fidelity signal, surfaced by
 *     `npx tsc` (the CI lint step: grunt ts -> exec:typecheck -> tsc).
 *
 *   - Section 1 is POSITIVE coverage: literals built only from fields the type
 *     already declares. They must compile clean; if the type ever drops one of
 *     these fields, tsc breaks here.
 *
 *   - Section 2 is the KNOWN-GAP register: every field the type currently DROPS,
 *     each ISOLATED in its own literal behind a `// @ts-expect-error known gap:
 *     <field path>` marker. Isolation matters — this TypeScript reports only the
 *     FIRST excess property per object literal, so each gap gets its own literal
 *     to guarantee its marker is load-bearing. The markers are SELF-ARMING: when
 *     the type is later fixed to include a field, its `@ts-expect-error` becomes
 *     unused and tsc errors (TS2578), forcing the marker's removal. The harness
 *     therefore can never silently fall behind the type being fixed.
 *
 * NOTE on body matchers: `Body` / `BodyWithContentType` (and the KeyToMultiValue
 * / KeyToValue header, cookie & parameter maps) each include a
 * `Record<string, any>` (index-signature) member, so ANY object literal is
 * assignable to them and excess-property checking is effectively disabled there.
 * Body/header/cookie matcher literals are therefore POSITIVE coverage only —
 * they cannot surface a dropped nested body field. All real gap signals come
 * from fields on plain interfaces (Expectation itself, HttpRequest, HttpResponse,
 * HttpChaosProfile, HttpWebSocketResponse, GrpcStreamMessage, …), which have no
 * index signature.
 *
 * This file is compiled (not executed) — it is listed in tsconfig.json `include`.
 */

import { DateTimeMatcher, Expectation, JsonPathBodyMatcher, XPathBodyMatcher } from '../mockServer';

/* ================================================================== *
 * SECTION 1 — POSITIVE COVERAGE (must compile clean)
 * ================================================================== */

/* Static response + full request/response feature surface. */
export const responseStaticLiteral: Expectation = {
    id: 'static-full-1',
    priority: 10,
    httpRequest: {
        method: 'POST',
        path: '/api/orders',
        headers: { Accept: ['application/json'] },
        queryStringParameters: { trace: ['true'] },
        cookies: { session: 'abc123' },
        secure: true,
        keepAlive: true,
        body: { type: 'STRING', string: 'order-fragment', subString: true, contentType: 'text/plain' }
    },
    httpResponse: {
        statusCode: 201,
        reasonPhrase: 'Created',
        headers: { 'Content-Type': ['application/json'] },
        cookies: { issued: 'cookie-value' },
        body: 'created',
        delay: { timeUnit: 'MILLISECONDS', value: 50 },
        connectionOptions: {
            keepAliveOverride: true,
            closeSocket: false,
            contentLengthHeaderOverride: 12,
            suppressContentLengthHeader: false,
            suppressConnectionHeader: true
        }
    },
    times: { remainingTimes: 5, unlimited: false },
    timeToLive: { timeUnit: 'SECONDS', timeToLive: 3600, unlimited: false }
};

/* Response actions. */
export const responseTemplateJsLiteral: Expectation = {
    httpRequest: { path: '/tmpljs' },
    httpResponseTemplate: {
        templateType: 'JAVASCRIPT',
        template: 'return { statusCode: 200, body: JSON.stringify({t: request.method}) };'
    }
};

export const responseTemplateMustacheLiteral: Expectation = {
    httpRequest: { path: '/tmplm' },
    httpResponseTemplate: {
        templateType: 'MUSTACHE',
        template: '{"statusCode": 200, "body": "{{request.path}}"}',
        parameters: { greeting: 'hello', names: ['ann', 'bob'], limits: { max: 5 } }
    }
};

export const responseClassCallbackLiteral: Expectation = {
    httpRequest: { path: '/clscb' },
    httpResponseClassCallback: { callbackClass: 'org.mockserver.examples.ResponseCallback' }
};

export const responseObjectCallbackLiteral: Expectation = {
    httpRequest: { path: '/objcb' },
    httpResponseObjectCallback: { clientId: 'client-xyz', responseCallback: true }
};

/* Forward actions (present in the type). */
export const forwardLiteral: Expectation = {
    httpRequest: { path: '/fwd' },
    httpForward: { scheme: 'HTTPS', host: 'backend.example.com', port: 443, delay: { timeUnit: 'MILLISECONDS', value: 10 } }
};

export const forwardTemplateLiteral: Expectation = {
    httpRequest: { path: '/fwdtmpl' },
    httpForwardTemplate: { templateType: 'VELOCITY', template: "{'path':\"/somePath\",'headers':{'Host':[\"localhost:1081\"]}}" }
};

export const forwardClassCallbackLiteral: Expectation = {
    httpRequest: { path: '/fwdcb' },
    httpForwardClassCallback: { callbackClass: 'org.mockserver.examples.ForwardCallback', delay: { timeUnit: 'MILLISECONDS', value: 1 } }
};

export const forwardObjectCallbackLiteral: Expectation = {
    httpRequest: { path: '/fwdobj' },
    httpForwardObjectCallback: { clientId: 'client-123', responseCallback: true }
};

export const forwardOverrideLiteral: Expectation = {
    httpRequest: { path: '/ovr' },
    httpOverrideForwardedRequest: {
        httpRequest: {
            path: '/other',
            headers: { Host: ['target.host.com'] },
            socketAddress: { host: 'target.host.com', port: 1234, scheme: 'HTTPS' }
        },
        httpResponse: { body: 'overridden' },
        delay: { timeUnit: 'MILLISECONDS', value: 5 }
    }
};

/* Error, streaming, binary, DNS-response actions. */
export const errorLiteral: Expectation = {
    httpRequest: { path: '/err' },
    httpError: { responseBytes: 'SGVsbG8gV29ybGQh', delay: { timeUnit: 'MILLISECONDS', value: 20 } }
};

export const errorConnectionResetLiteral: Expectation = {
    httpRequest: { path: '/err/connection-reset' },
    httpError: { resetConnection: true }
};

export const sseLiteral: Expectation = {
    httpRequest: { path: '/events' },
    httpSseResponse: {
        statusCode: 200,
        headers: { 'Cache-Control': ['no-cache'] },
        events: [
            { event: 'message', data: 'one', id: '1' },
            { event: 'message', data: 'two', id: '2', retry: 5000, comment: 'keep-alive' }
        ],
        closeConnection: true
    }
};

export const webSocketLiteral: Expectation = {
    httpRequest: { path: '/ws/echo' },
    httpWebSocketResponse: {
        subprotocol: 'chat',
        messages: [
            { text: 'hello' },
            { binary: 'AQID', delay: { timeUnit: 'MILLISECONDS', value: 5 } }
        ],
        closeConnection: false
    }
};

export const grpcStreamLiteral: Expectation = {
    httpRequest: { path: '/grpc/StreamMethod' },
    grpcStreamResponse: {
        statusName: 'OK',
        statusMessage: 'success',
        headers: { 'x-meta': ['v'] },
        messages: [{ json: '{"id":1}' }, { json: '{"id":2}', delay: { timeUnit: 'MILLISECONDS', value: 100 } }],
        closeConnection: false
    }
};

export const grpcBidiLiteral: Expectation = {
    httpRequest: { path: '/grpc/BidiMethod' },
    grpcBidiResponse: {
        statusName: 'OK',
        headers: { 'x-meta': ['v'] },
        messages: [{ json: '{"greeting":"hi"}' }],
        rules: [{ matchJson: '{"name":"world"}', responses: [{ json: '{"reply":"hello world"}' }] }],
        closeConnection: true
    }
};

export const binaryLiteral: Expectation = {
    httpRequest: { path: '/download' },
    binaryResponse: { binaryData: 'SGVsbG8gV29ybGQh', delay: { timeUnit: 'MILLISECONDS', value: 3 } }
};

export const binaryUpstreamLiteral: Expectation = {
    httpRequest: { path: '/download/upstream' },
    binaryResponse: { binaryData: 'VXBzdHJlYW0=', upstream: 'FORWARD_AND_REPLACE' }
};

export const binaryUpstreamUnknownValueLiteral: Expectation = {
    httpRequest: { path: '/download/upstream' },
    // @ts-expect-error upstream is limited to the server's three names
    binaryResponse: { binaryData: 'VXBzdHJlYW0=', upstream: 'REPLACE' }
};

export const dnsResponseLiteral: Expectation = {
    httpRequest: { path: '/dns' },
    dnsResponse: {
        responseCode: 'NOERROR',
        answerRecords: [{ name: 'example.com', type: 'A', dnsClass: 'IN', ttl: 300, value: '93.184.216.34' }]
    }
};

/* Response sequences. */
export const sequenceSequentialLiteral: Expectation = {
    httpRequest: { path: '/seq' },
    httpResponses: [
        { statusCode: 200, body: 'first' },
        { statusCode: 503, body: 'second' }
    ],
    responseMode: 'SEQUENTIAL'
};

export const sequenceRandomLiteral: Expectation = {
    httpRequest: { path: '/random' },
    httpResponses: [
        { statusCode: 200, body: 'a' },
        { statusCode: 201, body: 'b' }
    ],
    responseMode: 'RANDOM'
};

export const sequenceWeightedLiteral: Expectation = {
    httpRequest: { path: '/weighted' },
    httpResponses: [
        { statusCode: 200, body: 'success' },
        { statusCode: 500, body: 'error' }
    ],
    responseMode: 'WEIGHTED',
    responseWeights: [90, 10]
};

export const sequenceSwitchLiteral: Expectation = {
    httpRequest: { path: '/switch' },
    httpResponses: [
        { statusCode: 200, body: 'healthy' },
        { statusCode: 503, body: 'unavailable' }
    ],
    responseMode: 'SWITCH',
    switchAfter: 3
};

/* Multi-action pipeline steps. */
export const stepsLiteral: Expectation = {
    steps: [
        {
            httpRequest: { path: '/step1', method: 'POST' },
            blocking: true,
            delay: { timeUnit: 'MILLISECONDS', value: 5 },
            timeout: { timeUnit: 'SECONDS', value: 2 },
            failurePolicy: 'FAIL_FAST'
        },
        { httpResponse: { statusCode: 200, body: 'done' }, responder: true },
        { httpClassCallback: { callbackClass: 'org.mockserver.examples.StepCallback' }, failurePolicy: 'BEST_EFFORT' }
    ]
};

/* Chaos (only the fields the type declares). */
export const chaosLiteral: Expectation = {
    percentage: 50,
    httpRequest: { path: '/chaos' },
    httpResponse: { body: 'ok' },
    chaos: {
        errorStatus: 500,
        retryAfter: '3',
        errorProbability: 0.3,
        dropConnectionProbability: 0.1,
        latency: { timeUnit: 'MILLISECONDS', value: 200 },
        seed: 9,
        succeedFirst: 2,
        failRequestCount: 5,
        outageAfterMillis: 1000,
        outageDurationMillis: 5000,
        truncateBodyAtFraction: 0.5,
        malformedBody: true,
        slowResponseChunkSize: 16,
        slowResponseChunkDelay: { timeUnit: 'MILLISECONDS', value: 10 },
        quotaName: 'q',
        quotaLimit: 10,
        quotaWindowMillis: 1000,
        quotaErrorStatus: 429,
        degradationRampMillis: 2000
    }
};

/* Scenario / state machine (only the fields the type declares). */
export const scenarioLiteral: Expectation = {
    httpRequest: { method: 'POST', path: '/login' },
    httpResponse: { statusCode: 200, body: '{"token":"abc"}' },
    scenarioName: 'LoginFlow',
    scenarioState: 'Started',
    newScenarioState: 'LoggedIn',
    crossProtocolScenarios: [
        { trigger: 'HTTP_REQUEST', matchPattern: '/login', scenarioName: 'LoginFlow', targetState: 'LoggedIn' }
    ],
    times: { unlimited: true },
    timeToLive: { unlimited: true }
};

/* Before/after side-effect actions (only the fields the type declares). */
export const sideEffectActionsLiteral: Expectation = {
    httpRequest: { path: '/side' },
    httpResponse: { body: 'ok' },
    beforeActions: [
        {
            httpRequest: { path: '/notify-before', method: 'POST' },
            blocking: true,
            timeout: { timeUnit: 'SECONDS', value: 2 },
            failurePolicy: 'FAIL_FAST',
            delay: { timeUnit: 'MILLISECONDS', value: 1 }
        }
    ],
    afterActions: [
        { httpClassCallback: { callbackClass: 'org.mockserver.examples.AfterCallback' }, blocking: false, failurePolicy: 'BEST_EFFORT' },
        { httpObjectCallback: { clientId: 'audit-client' } },
        {
            httpRequestTemplate: {
                templateType: 'VELOCITY',
                template: "{ \"method\": \"POST\", \"path\": \"/notify-after/$!esc.url($request.headers['x-id'][0])\" }"
            },
            delay: { timeUnit: 'MILLISECONDS', value: 5 }
        }
    ]
};

/* Request matchers. */
export const requestJwtLiteral: Expectation = {
    httpRequest: {
        path: '/secure',
        jwt: {
            header: 'Authorization',
            scheme: 'Bearer',
            claims: { role: 'admin' },
            issuer: 'https://issuer.example.com',
            audience: 'my-api',
            algorithm: 'RS256'
        }
    },
    httpResponse: { body: 'ok' }
};

export const requestPathParametersLiteral: Expectation = {
    httpRequest: {
        path: '/cart/{cartId}/{maxItemCount}',
        pathParameters: {
            cartId: [{ schema: { type: 'string', pattern: '^[A-Z0-9-]+$' } }],
            maxItemCount: [{ schema: { type: 'integer' } }]
        }
    },
    httpResponse: { body: 'ok' }
};

export const requestAdvancedLiteral: Expectation = {
    httpRequest: {
        method: 'GET',
        path: '/adv',
        secure: true,
        keepAlive: true,
        clientIp: '!10.0.0.0/8',
        socketAddress: { host: 'target.example.com', port: 443, scheme: 'HTTPS' },
        headers: { 'X-Env': ['prod'] },
        cookies: { tenant: 'acme' }
    },
    httpResponse: { body: 'ok' }
};

/* Body matchers — POSITIVE coverage only (Body is a permissive union that
 * includes Record<string, any>, so these cannot surface a dropped body field). */
export const bodyJsonStrictLiteral: Expectation = {
    httpRequest: { path: '/json', body: { type: 'JSON', json: '{"a":1}', matchType: 'STRICT', contentType: 'application/json' } },
    httpResponse: { body: 'ok' }
};

export const bodyJsonSchemaLiteral: Expectation = {
    httpRequest: {
        path: '/schema',
        body: { type: 'JSON_SCHEMA', jsonSchema: { type: 'object', properties: { id: { type: 'integer' } }, required: ['id'] } }
    },
    httpResponse: { body: 'ok' }
};

export const bodyGraphqlLiteral: Expectation = {
    httpRequest: {
        method: 'POST',
        path: '/graphql',
        body: {
            type: 'GRAPHQL',
            query: 'query GetUser($id: ID!) { user(id: $id) { name email } }',
            operationName: 'GetUser',
            variablesSchema: '{"type":"object","properties":{"id":{"type":"string"}}}'
        }
    },
    httpResponse: { body: 'ok' }
};

export const bodyAllOfLiteral: Expectation = {
    httpRequest: {
        path: '/allof',
        body: {
            type: 'ALL_OF',
            bodyAllOf: [
                { type: 'STRING', string: 'part', subString: true },
                { type: 'JSON_PATH', jsonPath: '$.ok' }
            ]
        }
    },
    httpResponse: { body: 'ok' }
};

export const bodyWasmLiteral: Expectation = {
    // "WASM" is not a declared Body member; it only compiles because Body includes
    // Record<string, any>. Kept as feature documentation, not a gate.
    httpRequest: { path: '/wasm', body: { type: 'WASM', moduleName: 'my-rule' } },
    httpResponse: { body: 'ok' }
};

/* FILE response body with a templated path (action_response_file_templated_path.json). */
export const responseFileTemplatedPathLiteral: Expectation = {
    httpRequest: { path: '/files' },
    httpResponse: {
        statusCode: 200,
        body: {
            type: 'FILE',
            filePath: 'responses/{{ request.queryStringParameters.name.0 }}.json',
            contentType: 'application/json',
            templateType: 'MUSTACHE',
            templatedFilePath: true
        }
    }
};

export const bodyXpathLiteral: Expectation = {
    httpRequest: { path: '/xpath', body: { type: 'XPATH', xpath: '/bookstore/book[price>30]/price' } },
    httpResponse: { body: 'ok' }
};

/* Date-time matcher values (matcher_request_date_time.json). The header, query and
 * cookie maps are permissive, so the DateTimeMatcher constants carry the type check. */
export const issuedAfter: DateTimeMatcher = { after: 'now -1 hours' };
export const modifiedBefore: DateTimeMatcher = { before: '2026-10-21T00:00:00Z', actualFormat: 'rfc1123' };
export const fromAfter: DateTimeMatcher = { after: '2026-01-01', actualFormat: 'dd/MM/yyyy', zone: 'Europe/London' };
export const seenNotEqual: DateTimeMatcher = {
    not: true,
    equalToDateTime: 'now +1 days',
    truncateExpected: 'first day of month',
    truncateActual: 'startOfDay',
    applyTruncationLast: true
};

/* Body date-time comparisons (matcher_body_json_path_date_time.json, matcher_body_xpath_date_time.json).
 * Body is permissive, so the typed body constants carry the excess-property check. */
export const jsonPathDateTimeBody: JsonPathBodyMatcher = {
    type: 'JSON_PATH',
    jsonPath: '$.order.created',
    dateTime: { after: 'now -1 days', actualFormat: 'iso', truncateExpected: 'startOfDay', zone: 'Europe/London' }
};
export const xpathDateTimeBody: XPathBodyMatcher = {
    type: 'XPATH',
    xpath: '/order/@expires',
    dateTime: {
        not: true,
        before: '2026-01-01T00:00:00Z',
        actualFormat: 'rfc1123',
        truncateActual: 'first day of month',
        applyTruncationLast: true
    }
};
export const matcherBodyJsonPathDateTimeLiteral: Expectation = {
    httpRequest: { method: 'POST', path: '/jsonpath-date-time', body: jsonPathDateTimeBody },
    httpResponse: { body: 'recent order' }
};
export const matcherBodyXpathDateTimeLiteral: Expectation = {
    httpRequest: { method: 'POST', path: '/xpath-date-time', body: xpathDateTimeBody },
    httpResponse: { body: 'unexpired order' }
};
// @ts-expect-error a body date-time comparison takes exactly one operator
export const jsonPathTwoOperators: JsonPathBodyMatcher = { jsonPath: '$.a', dateTime: { after: 'now', before: 'now' } };

export const matcherRequestDateTimeLiteral: Expectation = {
    httpRequest: {
        method: 'GET',
        path: '/date-time',
        headers: {
            'X-Issued-At': [{ after: 'now -1 hours' }],
            'If-Modified-Since': [{ before: '2026-10-21T00:00:00Z', actualFormat: 'rfc1123' }],
            'X-Plain': ['before:2026-01-01']
        },
        queryStringParameters: {
            from: [{ after: '2026-01-01', actualFormat: 'dd/MM/yyyy', zone: 'Europe/London' }]
        },
        cookies: {
            seen: {
                not: true,
                equalToDateTime: 'now +1 days',
                truncateExpected: 'first day of month',
                truncateActual: 'startOfDay',
                applyTruncationLast: true
            }
        }
    },
    httpResponse: { statusCode: 200, body: 'dated' }
};

export const matcherRequestDateTimeTypedLiteral: Expectation = {
    httpRequest: {
        path: '/date-time/{day}',
        pathParameters: { day: [seenNotEqual] },
        headers: { 'X-Issued-At': [issuedAfter], 'If-Modified-Since': [modifiedBefore] },
        queryStringParameters: { from: [fromAfter] },
        cookies: { seen: seenNotEqual }
    },
    httpResponse: { body: 'ok' }
};

// @ts-expect-error a date-time matcher needs exactly one of before, after or equalToDateTime
export const dateTimeTwoOperators: DateTimeMatcher = { before: 'now', after: 'now -1 days' };
// @ts-expect-error a date-time matcher needs exactly one of before, after or equalToDateTime
export const dateTimeNoOperator: DateTimeMatcher = { zone: 'UTC' };
// @ts-expect-error the server rejects a date-time matcher combined with value
export const dateTimeWithValue: DateTimeMatcher = { after: 'now', value: 'x' };
// @ts-expect-error applyTruncationLast is a boolean
export const dateTimeStringFlag: DateTimeMatcher = { after: 'now', applyTruncationLast: 'true' };

/* Mirrors top_metadata.json: metadata carries any JSON value. */
export const topMetadataLiteral: Expectation = {
    id: 'metadata-1',
    httpRequest: { path: '/metadata' },
    httpResponse: { body: 'ok' },
    metadata: {
        owner: 'team-a',
        ticket: 4211,
        ratio: 0.5,
        flaky: false,
        note: null,
        tags: ['smoke', 'checkout'],
        contact: { team: 'checkout', oncall: ['ann', 'bob'] },
        literal: '!not?optional 100%+a b'
    }
};

/* Logical OR of request matchers, nested and negated (matcher_request_any_of.json). */
export const matcherRequestAnyOfLiteral: Expectation = {
    httpRequest: {
        anyOf: [
            { method: 'GET', path: '/pets' },
            { method: 'POST', path: '/pets', headers: { 'X-Tenant': ['acme'] } },
            { not: true, anyOf: [{ path: '/admin/.*' }, { path: '/internal/.*' }] },
        ],
    },
    httpResponse: { statusCode: 200, body: 'matched one of the alternatives' },
};

/* ================================================================== *
 * SECTION 2 — KNOWN-GAP REGISTER
 *
 * Each fixture field the generated `Expectation` type currently DROPS,
 * ISOLATED in its own literal so its `@ts-expect-error` marker is the sole
 * (first) excess property and is therefore load-bearing + self-arming.
 * When any of these is added to the type, its marker becomes unused and tsc
 * fails (TS2578) — delete the marker (and move the field into Section 1).
 * ================================================================== */

/* --- Missing top-level Expectation keys --- */

export const gapRateLimit: Expectation = {
    httpRequest: { path: '/gap/ratelimit' },
    httpResponse: { body: 'ok' },
    rateLimit: { name: 'rl', algorithm: 'token_bucket', burst: 20, refillPerSecond: 5.0, errorStatus: 429, retryAfter: '1' }
};

export const gapForwardValidateAction: Expectation = {
    httpRequest: { path: '/gap/validate' },
    httpForwardValidateAction: {
        host: 'backend.example.com',
        port: 443,
        scheme: 'HTTPS',
        specUrlOrPayload: 'https://example.com/openapi.json',
        validateRequest: true,
        validateResponse: true,
        validationMode: 'STRICT'
    }
};

export const gapForwardWithFallback: Expectation = {
    httpRequest: { path: '/gap/fallback' },
    httpForwardWithFallback: {
        httpForward: { scheme: 'HTTPS', host: 'backend.example.com', port: 443 },
        fallbackResponse: { statusCode: 503, body: 'unavailable' },
        fallbackOnStatusCodes: [500, 502, 503],
        fallbackOnTimeout: true
    }
};

export const gapLlmResponse: Expectation = {
    httpRequest: { method: 'POST', path: '/gap/llm' },
    httpLlmResponse: {
        provider: 'ANTHROPIC',
        model: 'claude-sonnet-4-20250514',
        completion: { text: 'Hello, world!', stopReason: 'end_turn', streaming: true },
        primary: true
    }
};

export const gapNamespace: Expectation = {
    namespace: 'tenant-a',
    httpRequest: { path: '/gap/namespace' },
    httpResponse: { body: 'ok' }
};

export const gapCapture: Expectation = {
    httpRequest: { path: '/gap/capture' },
    httpResponse: { body: 'ok' },
    capture: [
        { source: 'jsonPath', expression: '$.orderId', into: 'orderId' },
        { source: 'header', expression: 'X-Request-Id', into: 'reqId' },
        { source: 'pathParameter', expression: 'cartId', into: 'cart' }
    ]
};

export const gapTimestamp: Expectation = {
    httpRequest: { path: '/gap/timestamp' },
    httpResponse: { body: 'ok' },
    timestamp: '2026-07-03T12:00:00.000Z'
};

/* --- Missing nested fields on declared interfaces --- */

export const gapHttpResponseTrailers: Expectation = {
    httpRequest: { path: '/gap/trailers' },
    httpResponse: {
        statusCode: 200,
        trailers: { 'X-Trailer': ['end'] }
    }
};

export const gapHttpRequestProtocol: Expectation = {
    httpRequest: {
        path: '/gap/protocol',
        protocol: 'HTTP_2'
    },
    httpResponse: { body: 'ok' }
};

export const gapDnsQueryName: Expectation = {
    httpRequest: {
        dnsName: 'example.com'
    },
    dnsResponse: { responseCode: 'NOERROR' }
};

export const gapDnsQueryType: Expectation = {
    httpRequest: {
        dnsType: 'A'
    },
    dnsResponse: { responseCode: 'NOERROR' }
};

export const gapDnsQueryClass: Expectation = {
    httpRequest: {
        dnsClass: 'IN'
    },
    dnsResponse: { responseCode: 'NOERROR' }
};

export const gapChaosGraphqlErrors: Expectation = {
    httpRequest: { path: '/gap/chaos/graphqlErrors' },
    chaos: {
        graphqlErrors: true
    }
};

export const gapChaosGraphqlErrorMessage: Expectation = {
    httpRequest: { path: '/gap/chaos/graphqlErrorMessage' },
    chaos: {
        graphqlErrorMessage: 'boom'
    }
};

export const gapChaosGraphqlErrorCode: Expectation = {
    httpRequest: { path: '/gap/chaos/graphqlErrorCode' },
    chaos: {
        graphqlErrorCode: 'INTERNAL_SERVER_ERROR'
    }
};

export const gapChaosGraphqlNullifyData: Expectation = {
    httpRequest: { path: '/gap/chaos/graphqlNullifyData' },
    chaos: {
        graphqlNullifyData: false
    }
};

export const gapWebSocketMatchers: Expectation = {
    httpRequest: { path: '/gap/ws/matchers' },
    httpWebSocketResponse: {
        matchers: [{ frameType: 'TEXT', textMatcher: 'ping', responses: [{ text: 'pong' }] }]
    }
};

export const gapGrpcStreamMessageTemplateType: Expectation = {
    httpRequest: { path: '/gap/grpc/templateType' },
    grpcStreamResponse: {
        messages: [
            {
                json: '{"id":2}',
                templateType: 'VELOCITY'
            }
        ]
    }
};
