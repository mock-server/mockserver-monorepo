'use strict';

// explain, debugMismatch and explainUnmatched against a local HTTP server
// standing in for MockServer, so the request sent and the answer are both
// under the test's control.

var { describe, it } = require('node:test');
var assert = require('node:assert/strict');
var fs = require('fs');
var http = require('http');
var path = require('path');
var yaml = require('js-yaml');
var mockServerClient = require('../../').mockServerClient;

function startStubServer(statusCode, body) {
    return new Promise(function (resolve) {
        var received = [];
        var server = http.createServer(function (req, res) {
            var data = '';
            req.on('data', function (chunk) {
                data += chunk;
            });
            req.on('end', function () {
                received.push({method: req.method, url: req.url, body: data});
                res.writeHead(statusCode, {'Content-Type': 'application/json', 'Connection': 'close'});
                res.end(body);
            });
        });
        server.listen(0, '127.0.0.1', function () {
            resolve({server: server, port: server.address().port, received: received});
        });
    });
}

// Settles with {resolved: value} or {rejected: reason}, or {pending: true} when
// neither callback fires within the deadline.
function outcomeOf(thenable) {
    return new Promise(function (resolve) {
        var timer = setTimeout(resolve, 5000, {pending: true});
        thenable.then(function (value) {
            clearTimeout(timer);
            resolve({resolved: value});
        }, function (reason) {
            clearTimeout(timer);
            resolve({rejected: reason});
        });
    });
}

async function callAgainst(statusCode, body, call) {
    var stub = await startStubServer(statusCode, body);
    try {
        var outcome = await outcomeOf(call(mockServerClient('127.0.0.1', stub.port)));
        return {outcome: outcome, received: stub.received};
    } finally {
        stub.server.close();
    }
}

var EXPLANATION = {
    correlationId: 'abc 123/x',
    timestamp: '2026-10-14T10:00:00Z',
    recomputed: true,
    note: 'recomputed now',
    request: {timestamp: '2026-10-14 10:00:00.000', method: 'GET', path: '/orders'},
    atArrival: {outcome: 'MATCHED', expectationId: 'catch-all', expectationStillStored: true},
    sameOutcomeAsAtArrival: true,
    expectationsChangedSince: false,
    matchedExpectation: {expectationId: 'catch-all', position: 2, priority: 0, matches: true},
    skippedExpectations: [{
        expectationId: 'gold', position: 1, priority: 10, reason: 'FIELDS_DIFFER', explanation: 'header differs',
        matches: false, differingFieldCount: 1, differences: {headers: ['X-Tenant differs']}, remediation: {headers: 'add X-Tenant'}
    }],
    shadowedExpectations: [],
    totalExpectations: 2,
    evaluatedExpectations: 2,
    truncated: false
};

describe('explain(correlationId)', function () {
    it('sends PUT /mockserver/explain with the encoded correlation id and resolves with the explanation', async function () {
        var result = await callAgainst(200, JSON.stringify(EXPLANATION), function (client) {
            return client.explain('abc 123/x');
        });
        assert.deepEqual(result.received, [{method: 'PUT', url: '/mockserver/explain?correlationId=abc%20123%2Fx', body: ''}]);
        assert.deepEqual(result.outcome, {resolved: EXPLANATION});
    });

    it('resolves with the reason a failed forward gave at arrival', async function () {
        var failed = Object.assign({}, EXPLANATION, {
            atArrival: {
                outcome: 'MATCHED', expectationId: 'stock-forward', expectationStillStored: true,
                forwardFailure: {statusCode: 502, reason: 'Connection refused', causeNamed: false, forwardLoopSuspected: false, hint: 'retrieve_logs'}
            }
        });
        var result = await callAgainst(200, JSON.stringify(failed), function (client) {
            return client.explain('abc');
        });
        assert.deepEqual(result.outcome.resolved.atArrival.forwardFailure,
            {statusCode: 502, reason: 'Connection refused', causeNamed: false, forwardLoopSuspected: false, hint: 'retrieve_logs'});
    });

    it('rejects with the server error when no request has that correlation id', async function () {
        var result = await callAgainst(404, JSON.stringify({error: 'no request is recorded with correlationId unknown'}), function (client) {
            return client.explain('unknown');
        });
        assert.deepEqual(result.outcome, {rejected: 'explain failed for correlationId "unknown": no request is recorded with correlationId unknown'});
    });

    it('rejects with the body when the answer is not JSON', async function () {
        var result = await callAgainst(500, 'boom', function (client) {
            return client.explain('abc');
        });
        assert.deepEqual(result.outcome, {rejected: 'explain failed for correlationId "abc": boom'});
    });

    it('throws for a blank correlation id without calling the server', function () {
        var client = mockServerClient('127.0.0.1', 9);
        assert.throws(function () {
            client.explain('  ');
        }, /explain\(correlationId\) requires a non blank correlationId/);
        assert.throws(function () {
            client.explain(undefined);
        }, /explain\(correlationId\) requires a non blank correlationId/);
    });
});

describe('debugMismatch(requestDefinition)', function () {
    var ANALYSIS = {
        correlationId: 'c1',
        timestamp: '2026-10-14T10:00:00Z',
        totalExpectations: 1,
        evaluatedExpectations: 1,
        closestMatch: {expectationId: 'e1', matchedFields: 17, totalFields: 18},
        results: [{expectationId: 'e1', expectationPath: '/api/users', expectationMethod: 'POST', matches: false,
            matchedFieldCount: 17, totalFieldCount: 18, differences: {method: ['expected POST found GET']}}]
    };

    it('sends the request definition to PUT /mockserver/debugMismatch and resolves with the analysis', async function () {
        var request = {method: 'GET', path: '/api/users', headers: {'Content-Type': ['application/json']}};
        var result = await callAgainst(200, JSON.stringify(ANALYSIS), function (client) {
            return client.debugMismatch(request);
        });
        assert.equal(result.received.length, 1);
        assert.equal(result.received[0].method, 'PUT');
        assert.equal(result.received[0].url, '/mockserver/debugMismatch');
        assert.deepEqual(JSON.parse(result.received[0].body), request);
        assert.deepEqual(result.outcome, {resolved: ANALYSIS});
    });

    it('rejects with the server error when the definition is not an HTTP request', async function () {
        var result = await callAgainst(400, JSON.stringify({error: 'debugMismatch only supports HttpRequest definitions'}), function (client) {
            return client.debugMismatch({specUrlOrPayload: 'x'});
        });
        assert.deepEqual(result.outcome, {rejected: 'debugMismatch failed: debugMismatch only supports HttpRequest definitions'});
    });

    it('throws for a missing request definition', function () {
        assert.throws(function () {
            mockServerClient('127.0.0.1', 9).debugMismatch(null);
        }, /debugMismatch\(requestDefinition\) requires a non null requestDefinition/);
    });
});

describe('explainUnmatched(options)', function () {
    var REPORT = {
        correlationId: 'c2',
        timestamp: '2026-10-14T10:00:00Z',
        unmatchedRequestCount: 1,
        truncated: false,
        unmatchedRequests: [{
            timestamp: '2026-10-14 10:00:00.000', method: 'GET', path: '/api/order', totalExpectationsEvaluated: 1,
            closestExpectations: [{expectationId: 'e1', matches: false, differingFieldCount: 1,
                suggestedFix: {patch: [{op: 'replace', path: '/httpRequest/path', value: '/api/order'}],
                    patchedFields: ['path'], unpatchedFields: [], matchesAfterPatch: true}}]
        }]
    };

    it('sends limit and suggestFixes to PUT /mockserver/explainUnmatched and resolves with the report', async function () {
        var result = await callAgainst(200, JSON.stringify(REPORT), function (client) {
            return client.explainUnmatched({limit: 5, suggestFixes: true});
        });
        assert.equal(result.received.length, 1);
        assert.equal(result.received[0].url, '/mockserver/explainUnmatched');
        assert.deepEqual(JSON.parse(result.received[0].body), {limit: 5, suggestFixes: true});
        assert.deepEqual(result.outcome, {resolved: REPORT});
    });

    it('sends no body without options, so the server defaults apply', async function () {
        var result = await callAgainst(200, JSON.stringify(REPORT), function (client) {
            return client.explainUnmatched();
        });
        assert.deepEqual(result.received, [{method: 'PUT', url: '/mockserver/explainUnmatched', body: ''}]);
    });

    it('rejects with the body when the server fails', async function () {
        var result = await callAgainst(500, 'failure', function (client) {
            return client.explainUnmatched();
        });
        assert.deepEqual(result.outcome, {rejected: 'explainUnmatched failed: failure'});
    });
});

describe('explain types follow the OpenAPI schemas', function () {
    var REPO_ROOT = path.resolve(__dirname, '..', '..', '..');
    var spec = yaml.load(fs.readFileSync(path.join(REPO_ROOT, 'mockserver', 'mockserver-core', 'src', 'main',
        'resources', 'org', 'mockserver', 'openapi', 'mock-server-openapi-embedded-model.yaml'), 'utf8'));
    var dts = fs.readFileSync(path.resolve(__dirname, '..', '..', 'mockServer.d.ts'), 'utf8');

    function declaredMembers(name) {
        var match = new RegExp('export interface ' + name + ' \\{([\\s\\S]*?)\\n\\}').exec(dts);
        assert.ok(match, 'mockServer.d.ts declares interface ' + name);
        var members = [];
        match[1].replace(/^ {2}(\w+)\??:/gm, function (all, member) {
            members.push(member);
        });
        return members.sort();
    }

    it('RequestExplanation declares every schema property', function () {
        assert.deepEqual(declaredMembers('RequestExplanation'), Object.keys(spec.components.schemas.RequestExplanation.properties).sort());
    });

    it('Arrival and ArrivalForwardFailure declare every atArrival and forwardFailure schema property', function () {
        var atArrival = spec.components.schemas.RequestExplanation.properties.atArrival;
        assert.deepEqual(declaredMembers('Arrival'), Object.keys(atArrival.properties).sort());
        assert.deepEqual(declaredMembers('ArrivalForwardFailure'), Object.keys(atArrival.properties.forwardFailure.properties).sort());
    });

    ['ExplainedExpectation', 'SuggestedFix', 'DebugMismatchResult', 'ExplainUnmatchedResult', 'UnmatchedRequest',
        'ExplanationNarration'].forEach(function (name) {
        it(name + ' declares every schema property', function () {
            assert.deepEqual(declaredMembers(name), Object.keys(spec.components.schemas[name].properties).sort());
        });
    });

    function inlineMembers(name, member) {
        var body = new RegExp('export interface ' + name + ' \\{([\\s\\S]*?)\\n\\}').exec(dts)[1];
        var inline = new RegExp('^ {2}' + member + '\\??: \\{([^}]*)\\}', 'm').exec(body);
        assert.ok(inline, name + '.' + member + ' is declared as an inline object type');
        return inline[1].split(';').map(function (part) {
            return part.trim().replace(/\??:[\s\S]*$/, '');
        }).filter(Boolean).sort();
    }

    it('the inline closest match, patch operation and unpatched field declare every schema property', function () {
        var schemas = spec.components.schemas;
        assert.deepEqual(inlineMembers('DebugMismatchResult', 'closestMatch'), Object.keys(schemas.ClosestMatch.properties).sort());
        assert.deepEqual(inlineMembers('SuggestedFix', 'patch'), Object.keys(schemas.SuggestedFix.properties.patch.items.properties).sort());
        assert.deepEqual(inlineMembers('SuggestedFix', 'unpatchedFields'), Object.keys(schemas.SuggestedFix.properties.unpatchedFields.items.properties).sort());
    });

    it('the reason and outcome unions list every schema enum value', function () {
        var reasons = /export type ExplainedExpectationReason =([^;]*);/.exec(dts)[1].match(/'(\w+)'/g).map(function (v) {
            return v.replace(/'/g, '');
        });
        assert.deepEqual(reasons.sort(), spec.components.schemas.ExplainedExpectation.properties.reason.enum.slice().sort());
        var outcomes = /export type ArrivalOutcome =([^;]*);/.exec(dts)[1].match(/'(\w+)'/g).map(function (v) {
            return v.replace(/'/g, '');
        });
        assert.deepEqual(outcomes.sort(), spec.components.schemas.RequestExplanation.properties.atArrival.properties.outcome.enum.slice().sort());
    });
});
