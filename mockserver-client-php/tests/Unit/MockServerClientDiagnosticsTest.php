<?php

declare(strict_types=1);

namespace MockServer\Tests\Unit;

use GuzzleHttp\Client as GuzzleClient;
use GuzzleHttp\Handler\MockHandler;
use GuzzleHttp\HandlerStack;
use GuzzleHttp\Middleware;
use GuzzleHttp\Psr7\Response;
use MockServer\Diagnostics\ArrivalForwardFailure;
use MockServer\Diagnostics\ExplainArrival;
use MockServer\Diagnostics\ExplainedExpectation;
use MockServer\Diagnostics\ExplanationNarration;
use MockServer\Exception\InvalidRequestException;
use MockServer\Exception\MockServerException;
use MockServer\HttpRequest;
use MockServer\MockServerClient;
use PHPUnit\Framework\TestCase;

/**
 * Tests for the typed match-diagnostic calls (explain, debugMismatch,
 * explainUnmatched). The response bodies follow what the server writes.
 */
class MockServerClientDiagnosticsTest extends TestCase
{
    private const EXPLAIN_RESPONSE = <<<'JSON'
        {
          "correlationId" : "abc-123",
          "timestamp" : "2026-10-14T10:00:00.000Z",
          "recomputed" : true,
          "note" : "recomputed against the current expectations",
          "request" : { "timestamp" : "2026-10-14 09:59:59.000", "method" : "GET", "path" : "/orders",
                        "bodyTruncated" : true, "bodyTruncatedNote" : "only the first 1024 bytes were logged" },
          "atArrival" : { "outcome" : "MATCHED", "expectationId" : "catch-all", "expectationStillStored" : true },
          "sameOutcomeAsAtArrival" : true,
          "expectationsChangedSince" : true,
          "expectationsLastChanged" : "2026-10-14T09:59:59.500Z",
          "matchedExpectation" : { "expectationId" : "catch-all", "expectationMethod" : "GET", "expectationPath" : "/orders",
                                   "position" : 3, "priority" : 0, "matches" : true },
          "skippedExpectations" : [ {
            "expectationId" : "gold-tenant", "expectationMethod" : "GET", "expectationPath" : "/orders",
            "position" : 1, "priority" : 10, "reason" : "FIELDS_DIFFER",
            "explanation" : "the request differs on: headers", "matches" : false,
            "matchedFieldCount" : 17, "totalFieldCount" : 18, "differingFieldCount" : 1,
            "differences" : { "headers" : [ "multimap match failed expected: gold found: silver" ] },
            "remediation" : { "headers" : "check the X-Tenant header value" }
          }, {
            "expectationId" : "future-reason", "position" : 2, "priority" : 5,
            "reason" : "SOME_REASON_ADDED_LATER", "explanation" : "a newer server"
          } ],
          "shadowedExpectations" : [ {
            "expectationId" : "fallback", "position" : 4, "priority" : 0, "reason" : "LOWER_PRIORITY",
            "explanation" : "also matches, but catch-all is evaluated first", "matches" : true
          } ],
          "totalExpectations" : 600,
          "evaluatedExpectations" : 500,
          "truncated" : true,
          "maxExpectationsEvaluated" : 500
        }
        JSON;

    private const DEBUG_MISMATCH_RESPONSE = <<<'JSON'
        {
          "correlationId" : "d-1",
          "timestamp" : "2026-10-14T10:00:00Z",
          "totalExpectations" : 150,
          "evaluatedExpectations" : 100,
          "truncated" : true,
          "maxExpectationsEvaluated" : 100,
          "closestMatch" : { "expectationId" : "abc-123", "matchedFields" : 17, "totalFields" : 18 },
          "results" : [ {
            "expectationId" : "abc-123", "expectationPath" : "/api/users", "expectationMethod" : "POST",
            "matches" : false, "matchedFieldCount" : 17, "totalFieldCount" : 18,
            "differences" : { "method" : [ "string or regex match failed expected: POST found: GET" ] }
          }, {
            "expectationId" : "def-456", "expectationPath" : "/api/users", "expectationMethod" : "GET",
            "matches" : true, "matchedFieldCount" : 18, "totalFieldCount" : 18
          } ]
        }
        JSON;

    private const EXPLAIN_UNMATCHED_RESPONSE = <<<'JSON'
        {
          "correlationId" : "u-1",
          "timestamp" : "2026-10-14T10:00:00Z",
          "unmatchedRequestCount" : 1,
          "truncated" : false,
          "unmatchedRequests" : [ {
            "timestamp" : "2026-10-14 09:59:00.000",
            "method" : "GET",
            "path" : "/api/order",
            "closestExpectations" : [ {
              "expectationId" : "orders", "expectationPath" : "/api/orders", "expectationMethod" : "GET",
              "matches" : false, "matchedFieldCount" : 17, "totalFieldCount" : 18, "differingFieldCount" : 1,
              "differences" : { "path" : [ "string or regex match failed expected: /api/orders found: /api/order" ] },
              "remediation" : { "path" : "check the path" },
              "suggestedFix" : {
                "patch" : [
                  { "op" : "test", "path" : "/httpRequest/path", "value" : "/api/orders" },
                  { "op" : "replace", "path" : "/httpRequest/path", "value" : "/api/order" },
                  { "op" : "remove", "path" : "/httpRequest/headers/X-Old" }
                ],
                "patchedFields" : [ "path" ],
                "unpatchedFields" : [ { "field" : "body", "reason" : "the event log kept only part of the body" } ],
                "matchesAfterPatch" : false
              }
            } ],
            "totalExpectationsEvaluated" : 1
          } ]
        }
        JSON;

    /**
     * @param array<Response> $responses
     * @param array<array> &$history
     */
    private function createClientWithMock(array $responses, array &$history = []): MockServerClient
    {
        $handlerStack = HandlerStack::create(new MockHandler($responses));
        $handlerStack->push(Middleware::history($history));

        $client = new MockServerClient('localhost', 1080);
        $prop = (new \ReflectionClass($client))->getProperty('httpClient');
        $prop->setValue($client, new GuzzleClient([
            'handler' => $handlerStack,
            'http_errors' => false,
            'headers' => ['Content-Type' => 'application/json; charset=utf-8'],
        ]));

        return $client;
    }

    public function testExplainSendsTheCorrelationIdAsAQueryParameterWithNoBody(): void
    {
        $history = [];
        $client = $this->createClientWithMock([new Response(200, [], self::EXPLAIN_RESPONSE)], $history);

        $client->explain('abc 123/&x');

        $request = $history[0]['request'];
        $this->assertSame('PUT', $request->getMethod());
        $this->assertSame('/mockserver/explain', $request->getUri()->getPath());
        $this->assertSame('correlationId=abc%20123%2F%26x', $request->getUri()->getQuery());
        $this->assertSame('', (string) $request->getBody());
    }

    public function testExplainParsesEveryFieldOfTheExplanation(): void
    {
        $client = $this->createClientWithMock([new Response(200, [], self::EXPLAIN_RESPONSE)]);

        $explanation = $client->explain('abc-123');

        $this->assertSame('abc-123', $explanation->correlationId);
        $this->assertSame('2026-10-14T10:00:00.000Z', $explanation->timestamp);
        $this->assertTrue($explanation->recomputed);
        $this->assertSame('recomputed against the current expectations', $explanation->note);
        $this->assertSame('2026-10-14 09:59:59.000', $explanation->request->timestamp);
        $this->assertSame('GET', $explanation->request->method);
        $this->assertSame('/orders', $explanation->request->path);
        $this->assertTrue($explanation->request->bodyTruncated);
        $this->assertSame('only the first 1024 bytes were logged', $explanation->request->bodyTruncatedNote);
        $this->assertSame(ExplainArrival::MATCHED, $explanation->atArrival->outcome);
        $this->assertSame('catch-all', $explanation->atArrival->expectationId);
        $this->assertTrue($explanation->atArrival->expectationStillStored);
        $this->assertTrue($explanation->sameOutcomeAsAtArrival);
        $this->assertTrue($explanation->expectationsChangedSince);
        $this->assertSame('2026-10-14T09:59:59.500Z', $explanation->expectationsLastChanged);
        $this->assertSame('catch-all', $explanation->matchedExpectation->expectationId);
        $this->assertSame(3, $explanation->matchedExpectation->position);
        $this->assertSame(0, $explanation->matchedExpectation->priority);
        $this->assertNull($explanation->matchedExpectation->reason);
        $this->assertNull($explanation->closestExpectationId);
        $this->assertSame(600, $explanation->totalExpectations);
        $this->assertSame(500, $explanation->evaluatedExpectations);
        $this->assertTrue($explanation->truncated);
        $this->assertSame(500, $explanation->maxExpectationsEvaluated);

        $skipped = $explanation->skippedExpectations[0];
        $this->assertInstanceOf(ExplainedExpectation::class, $skipped);
        $this->assertSame('gold-tenant', $skipped->expectationId);
        $this->assertSame('GET', $skipped->expectationMethod);
        $this->assertSame('/orders', $skipped->expectationPath);
        $this->assertSame(1, $skipped->position);
        $this->assertSame(10, $skipped->priority);
        $this->assertSame(ExplainedExpectation::REASON_FIELDS_DIFFER, $skipped->reason);
        $this->assertSame('the request differs on: headers', $skipped->explanation);
        $this->assertFalse($skipped->matches);
        $this->assertSame(17, $skipped->matchedFieldCount);
        $this->assertSame(18, $skipped->totalFieldCount);
        $this->assertSame(1, $skipped->differingFieldCount);
        $this->assertSame(['headers' => ['multimap match failed expected: gold found: silver']], $skipped->differences);
        $this->assertSame(['headers' => 'check the X-Tenant header value'], $skipped->remediation);
        $this->assertNull($skipped->suggestedFix);

        $this->assertSame('SOME_REASON_ADDED_LATER', $explanation->skippedExpectations[1]->reason);
        $this->assertCount(1, $explanation->shadowedExpectations);
        $this->assertSame(ExplainedExpectation::REASON_LOWER_PRIORITY, $explanation->shadowedExpectations[0]->reason);
    }

    public function testExplainLeavesOptionalFieldsNullAndListsEmptyWhenAbsent(): void
    {
        $client = $this->createClientWithMock([new Response(200, [], json_encode([
            'correlationId' => 'abc-123',
            'atArrival' => ['outcome' => 'NO_MATCH'],
            'closestExpectationId' => 'near',
            'totalExpectations' => 1,
            'evaluatedExpectations' => 1,
            'truncated' => false,
        ]))]);

        $explanation = $client->explain('abc-123');

        $this->assertNull($explanation->matchedExpectation);
        $this->assertSame('near', $explanation->closestExpectationId);
        $this->assertNull($explanation->sameOutcomeAsAtArrival);
        $this->assertNull($explanation->expectationsLastChanged);
        $this->assertNull($explanation->maxExpectationsEvaluated);
        $this->assertNull($explanation->narration);
        $this->assertSame(ExplainArrival::NO_MATCH, $explanation->atArrival->outcome);
        $this->assertNull($explanation->atArrival->expectationId);
        $this->assertNull($explanation->atArrival->expectationStillStored);
        $this->assertNull($explanation->atArrival->forwardFailure);
        $this->assertSame([], $explanation->skippedExpectations);
        $this->assertSame([], $explanation->shadowedExpectations);
        $this->assertNull($explanation->request->method);
    }

    public function testExplainRejectsABlankCorrelationIdWithoutCallingTheServer(): void
    {
        $history = [];
        $client = $this->createClientWithMock([], $history);

        try {
            $client->explain('  ');
            $this->fail('expected InvalidArgumentException');
        } catch (\InvalidArgumentException $e) {
            $this->assertStringContainsString('correlationId', $e->getMessage());
        }
        $this->assertCount(0, $history);
    }

    public function testExplainReportsTheServersReasonForAnUnknownCorrelationId(): void
    {
        $client = $this->createClientWithMock([
            new Response(404, [], '{ "error" : "no request is recorded with correlationId \"gone\"" }'),
        ]);

        $this->expectException(InvalidRequestException::class);
        $this->expectExceptionMessage('(HTTP 404): no request is recorded with correlationId "gone"');

        $client->explain('gone');
    }

    public function testExplainReportsAServerFailure(): void
    {
        $client = $this->createClientWithMock([new Response(500, [], 'boom')]);

        try {
            $client->explain('abc-123');
            $this->fail('expected MockServerException');
        } catch (InvalidRequestException $e) {
            $this->fail('a 500 is not an invalid request');
        } catch (MockServerException $e) {
            $this->assertStringContainsString('(HTTP 500): boom', $e->getMessage());
        }
    }

    public function testExplainRejectsAResponseThatIsNotAnExplanation(): void
    {
        $client = $this->createClientWithMock([new Response(200, [], 'not json')]);

        $this->expectException(MockServerException::class);
        $this->expectExceptionMessage('Unable to parse');

        $client->explain('abc-123');
    }

    public function testExplainRejectsAJsonArrayResponse(): void
    {
        $client = $this->createClientWithMock([new Response(200, [], '[{"a":1}]')]);

        $this->expectException(MockServerException::class);
        $this->expectExceptionMessage('Unable to parse');

        $client->explain('abc-123');
    }

    public function testDebugMismatchSendsTheRequestAsTheBody(): void
    {
        $history = [];
        $client = $this->createClientWithMock([new Response(200, [], self::DEBUG_MISMATCH_RESPONSE)], $history);

        $client->debugMismatch(HttpRequest::request()->method('GET')->path('/api/users'));

        $request = $history[0]['request'];
        $this->assertSame('PUT', $request->getMethod());
        $this->assertSame('/mockserver/debugMismatch', $request->getUri()->getPath());
        $this->assertSame(['method' => 'GET', 'path' => '/api/users'], json_decode((string) $request->getBody(), true));
    }

    public function testDebugMismatchSendsAnEmptyObjectForAnEmptyRequest(): void
    {
        $history = [];
        $client = $this->createClientWithMock([new Response(200, [], self::DEBUG_MISMATCH_RESPONSE)], $history);

        $client->debugMismatch(HttpRequest::request());

        $this->assertSame('{}', (string) $history[0]['request']->getBody());
    }

    public function testDebugMismatchParsesTheResultsAndClosestMatch(): void
    {
        $client = $this->createClientWithMock([new Response(200, [], self::DEBUG_MISMATCH_RESPONSE)]);

        $result = $client->debugMismatch(HttpRequest::request()->path('/api/users'));

        $this->assertSame('d-1', $result->correlationId);
        $this->assertSame('2026-10-14T10:00:00Z', $result->timestamp);
        $this->assertSame(150, $result->totalExpectations);
        $this->assertSame(100, $result->evaluatedExpectations);
        $this->assertTrue($result->truncated);
        $this->assertSame(100, $result->maxExpectationsEvaluated);
        $this->assertSame('abc-123', $result->closestMatch->expectationId);
        $this->assertSame(17, $result->closestMatch->matchedFields);
        $this->assertSame(18, $result->closestMatch->totalFields);
        $this->assertCount(2, $result->results);
        $this->assertSame('POST', $result->results[0]->expectationMethod);
        $this->assertFalse($result->results[0]->matches);
        $this->assertSame(17, $result->results[0]->matchedFieldCount);
        $this->assertSame(18, $result->results[0]->totalFieldCount);
        $this->assertSame(
            ['method' => ['string or regex match failed expected: POST found: GET']],
            $result->results[0]->differences
        );
        $this->assertTrue($result->results[1]->matches);
        $this->assertSame([], $result->results[1]->differences);
    }

    public function testDebugMismatchReportsTheServersReasonOnABadRequest(): void
    {
        $client = $this->createClientWithMock([
            new Response(400, [], '{ "error" : "debugMismatch only supports HttpRequest definitions" }'),
        ]);

        $this->expectException(InvalidRequestException::class);
        $this->expectExceptionMessage('(HTTP 400): debugMismatch only supports HttpRequest definitions');

        $client->debugMismatch(HttpRequest::request());
    }

    public function testExplainUnmatchedSendsLimitAndSuggestFixes(): void
    {
        $history = [];
        $client = $this->createClientWithMock([new Response(200, [], self::EXPLAIN_UNMATCHED_RESPONSE)], $history);

        $client->explainUnmatched(5, true);

        $request = $history[0]['request'];
        $this->assertSame('PUT', $request->getMethod());
        $this->assertSame('/mockserver/explainUnmatched', $request->getUri()->getPath());
        $this->assertSame(['limit' => 5, 'suggestFixes' => true], json_decode((string) $request->getBody(), true));
    }

    public function testExplainUnmatchedOmitsOptionsLeftAtTheServerDefault(): void
    {
        $history = [];
        $client = $this->createClientWithMock([new Response(200, [], self::EXPLAIN_UNMATCHED_RESPONSE)], $history);

        $client->explainUnmatched();

        $this->assertSame('{}', (string) $history[0]['request']->getBody());
    }

    public function testExplainUnmatchedRejectsALimitBelowOne(): void
    {
        $history = [];
        $client = $this->createClientWithMock([], $history);

        try {
            $client->explainUnmatched(0);
            $this->fail('expected InvalidArgumentException');
        } catch (\InvalidArgumentException $e) {
            $this->assertStringContainsString('limit', $e->getMessage());
        }
        $this->assertCount(0, $history);
    }

    public function testExplainUnmatchedParsesTheRequestsClosestExpectationsAndSuggestedFix(): void
    {
        $client = $this->createClientWithMock([new Response(200, [], self::EXPLAIN_UNMATCHED_RESPONSE)]);

        $result = $client->explainUnmatched(null, true);

        $this->assertSame('u-1', $result->correlationId);
        $this->assertSame('2026-10-14T10:00:00Z', $result->timestamp);
        $this->assertSame(1, $result->unmatchedRequestCount);
        $this->assertFalse($result->truncated);
        $this->assertCount(1, $result->unmatchedRequests);
        $request = $result->unmatchedRequests[0];
        $this->assertSame('2026-10-14 09:59:00.000', $request->timestamp);
        $this->assertSame('GET', $request->method);
        $this->assertSame('/api/order', $request->path);
        $this->assertSame(1, $request->totalExpectationsEvaluated);
        $this->assertCount(1, $request->closestExpectations);
        $closest = $request->closestExpectations[0];
        $this->assertSame('orders', $closest->expectationId);
        $this->assertSame(1, $closest->differingFieldCount);
        $this->assertSame(['path' => 'check the path'], $closest->remediation);

        $fix = $closest->suggestedFix;
        $this->assertSame(['test', 'replace', 'remove'], array_column($fix->patch, 'op'));
        $this->assertSame(
            ['op' => 'replace', 'path' => '/httpRequest/path', 'value' => '/api/order'],
            $fix->patch[1]
        );
        $this->assertSame(['path'], $fix->patchedFields);
        $this->assertSame(
            [['field' => 'body', 'reason' => 'the event log kept only part of the body']],
            $fix->unpatchedFields
        );
        $this->assertFalse($fix->matchesAfterPatch);
        $this->assertNull($result->narration);
    }

    public function testExplainParsesTheReasonAFailedForwardGaveAtArrival(): void
    {
        $client = $this->createClientWithMock([new Response(200, [], json_encode([
            'correlationId' => 'abc-123',
            'atArrival' => [
                'outcome' => 'MATCHED',
                'expectationId' => 'stock-forward',
                'expectationStillStored' => true,
                'forwardFailure' => [
                    'statusCode' => 502,
                    'reason' => 'Connection refused',
                    'reasonTruncated' => true,
                    'causeNamed' => false,
                    'forwardLoopSuspected' => true,
                    'hint' => 'retrieve_logs',
                ],
            ],
        ]))]);

        $arrival = $client->explain('abc-123')->atArrival;

        $this->assertSame('stock-forward', $arrival->expectationId);
        $this->assertEquals(
            new ArrivalForwardFailure(502, 'Connection refused', true, false, true, 'retrieve_logs'),
            $arrival->forwardFailure
        );
    }

    public function testExplainLeavesOutTheForwardFailureFieldsAServerDidNotSend(): void
    {
        $client = $this->createClientWithMock([new Response(200, [], json_encode([
            'correlationId' => 'abc-123',
            'atArrival' => [
                'outcome' => 'FORWARDED_WITHOUT_EXPECTATION',
                'forwardFailure' => ['reason' => 'timed out', 'causeNamed' => true],
            ],
        ]))]);

        $this->assertEquals(
            new ArrivalForwardFailure(null, 'timed out', false, true, null, null),
            $client->explain('abc-123')->atArrival->forwardFailure
        );
    }

    public function testExplainParsesTheNarration(): void
    {
        $client = $this->createClientWithMock([new Response(200, [], json_encode([
            'correlationId' => 'abc-123',
            'narration' => [
                'available' => true,
                'aiGenerated' => true,
                'label' => 'AI-generated, can be wrong',
                'provider' => 'OPENAI',
                'model' => 'gpt-test',
                'text' => 'gold needs X-Tenant',
            ],
        ]))]);

        $narration = $client->explain('abc-123')->narration;

        $this->assertEquals(
            new ExplanationNarration(true, true, 'AI-generated, can be wrong', 'OPENAI', 'gpt-test', 'gold needs X-Tenant', null),
            $narration
        );
    }

    public function testExplainUnmatchedParsesAnUnavailableNarration(): void
    {
        $client = $this->createClientWithMock([new Response(200, [], json_encode([
            'unmatchedRequestCount' => 0,
            'unmatchedRequests' => [],
            'narration' => ['available' => false, 'reason' => 'llmExplainNarrationEnabled is false'],
        ]))]);

        $narration = $client->explainUnmatched()->narration;

        $this->assertEquals(
            new ExplanationNarration(false, false, null, null, null, null, 'llmExplainNarrationEnabled is false'),
            $narration
        );
    }

    public function testDiagnosticCallsSendTheControlPlaneBearerToken(): void
    {
        $history = [];
        $client = $this->createClientWithMock([new Response(200, [], self::EXPLAIN_RESPONSE)], $history);
        $client->withControlPlaneBearerToken('secret');

        $client->explain('abc-123');

        $this->assertSame('Bearer secret', $history[0]['request']->getHeaderLine('Authorization'));
    }
}
