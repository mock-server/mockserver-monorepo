<?php

declare(strict_types=1);

namespace MockServer\Tests\Unit;

use MockServer\BinaryResponse;
use MockServer\Delay;
use MockServer\DnsRecord;
use MockServer\DnsResponse;
use MockServer\GrpcBidiMessage;
use MockServer\GrpcBidiResponse;
use MockServer\GrpcBidiRule;
use MockServer\GrpcStreamMessage;
use MockServer\GrpcStreamResponse;
use MockServer\HttpForward;
use MockServer\HttpForwardValidateAction;
use MockServer\HttpForwardWithFallback;
use MockServer\HttpResponse;
use MockServer\HttpSseResponse;
use MockServer\HttpTemplate;
use MockServer\GraphQLSubscriptionFilter;
use MockServer\HttpWebSocketResponse;
use MockServer\Tests\Support\SharedFixtures;
use MockServer\OpenAPIExpectation;
use MockServer\SseEvent;
use MockServer\WebSocketMessage;
use PHPUnit\Framework\TestCase;

/**
 * Pure builder tests asserting the wire JSON keys produced by toArray()
 * for each advanced response builder, independent of any HTTP transport.
 */
class ResponseBuildersTest extends TestCase
{
    public function testSseEventToArray(): void
    {
        $event = SseEvent::event()
            ->withEvent('message')
            ->withData('payload')
            ->withId('42')
            ->withRetry(3000)
            ->withDelay(Delay::milliseconds(100));

        $this->assertSame([
            'event' => 'message',
            'data' => 'payload',
            'id' => '42',
            'retry' => 3000,
            'delay' => ['timeUnit' => 'MILLISECONDS', 'value' => 100],
        ], $event->toArray());
    }

    public function testSseEventCommentToArray(): void
    {
        $this->assertSame(['comment' => 'heartbeat'], SseEvent::event()->withComment('heartbeat')->toArray());
        $this->assertSame(
            ['data' => 'payload', 'comment' => 'note'],
            SseEvent::event()->withComment('note')->withData('payload')->toArray()
        );
    }

    public function testHttpSseResponseToArray(): void
    {
        $sse = HttpSseResponse::response()
            ->statusCode(200)
            ->header('Content-Type', 'text/event-stream')
            ->event(SseEvent::event()->withData('a'))
            ->closeConnection(true)
            ->primary(false);

        $arr = $sse->toArray();
        $this->assertSame(200, $arr['statusCode']);
        $this->assertSame(['text/event-stream'], $arr['headers']['Content-Type']);
        $this->assertSame([['data' => 'a']], $arr['events']);
        $this->assertTrue($arr['closeConnection']);
        $this->assertFalse($arr['primary']);
    }

    public function testWebSocketMessageTextAndBinary(): void
    {
        $this->assertSame(['text' => 'hello'], WebSocketMessage::text('hello')->toArray());

        $binary = WebSocketMessage::binary("\x00\xFF")->toArray();
        $this->assertSame(base64_encode("\x00\xFF"), $binary['binary']);
        $this->assertArrayNotHasKey('text', $binary);

        $this->assertSame(['binary' => 'YWJj'], WebSocketMessage::binaryBase64('YWJj')->toArray());
    }

    public function testHttpWebSocketResponseToArray(): void
    {
        $ws = HttpWebSocketResponse::response()
            ->subprotocol('graphql-ws')
            ->message(WebSocketMessage::text('ping'))
            ->closeConnection(false);

        $arr = $ws->toArray();
        $this->assertSame('graphql-ws', $arr['subprotocol']);
        $this->assertSame([['text' => 'ping']], $arr['messages']);
        $this->assertFalse($arr['closeConnection']);
    }

    public function testGrpcStreamMessageJsonArrayAndString(): void
    {
        $this->assertSame(['json' => ['a' => 1]], GrpcStreamMessage::message(['a' => 1])->toArray());
        $this->assertSame(['json' => '{"a":1}'], GrpcStreamMessage::message('{"a":1}')->toArray());
    }

    public function testGrpcStreamResponseToArray(): void
    {
        $grpc = GrpcStreamResponse::response()
            ->statusName('OK')
            ->statusMessage('done')
            ->header('grpc-status', '0')
            ->message(GrpcStreamMessage::message(['n' => 1]))
            ->primary(true);

        $arr = $grpc->toArray();
        $this->assertSame('OK', $arr['statusName']);
        $this->assertSame('done', $arr['statusMessage']);
        $this->assertSame(['0'], $arr['headers']['grpc-status']);
        $this->assertSame([['json' => ['n' => 1]]], $arr['messages']);
        $this->assertTrue($arr['primary']);
    }

    public function testBinaryResponseFromBytesAndBase64(): void
    {
        $this->assertSame(
            ['binaryData' => base64_encode("\x01\x02")],
            BinaryResponse::response()->fromBytes("\x01\x02")->toArray()
        );
        $this->assertSame(
            ['binaryData' => 'YWJj'],
            BinaryResponse::response()->binaryData('YWJj')->toArray()
        );
    }

    public function testBinaryResponseUpstreamRoundTrips(): void
    {
        $fixture = ['binaryData' => 'VXBzdHJlYW0=', 'upstream' => 'FORWARD_AND_REPLACE'];
        $built = BinaryResponse::response()
            ->binaryData('VXBzdHJlYW0=')
            ->upstream(BinaryResponse::FORWARD_AND_REPLACE)
            ->toArray();
        $this->assertSame($fixture, $built);
        $this->assertSame($fixture, json_decode((string) json_encode(BinaryResponse::response()
            ->binaryData('VXBzdHJlYW0=')->upstream(BinaryResponse::FORWARD_AND_REPLACE)), true));
        $this->assertSame('ANSWER_ONLY', BinaryResponse::ANSWER_ONLY);
        $this->assertSame('ANSWER_AND_FORWARD', BinaryResponse::ANSWER_AND_FORWARD);
        $this->assertArrayNotHasKey('upstream', BinaryResponse::response()->binaryData('AQID')->toArray());
    }

    public function testDnsRecordFactories(): void
    {
        $this->assertSame(
            ['name' => 'host', 'type' => 'A', 'value' => '1.2.3.4'],
            DnsRecord::aRecord('host', '1.2.3.4')->toArray()
        );
        $this->assertSame(
            ['name' => 'host', 'type' => 'AAAA', 'value' => '::1'],
            DnsRecord::aaaaRecord('host', '::1')->toArray()
        );
        $this->assertSame(
            ['name' => 'alias', 'type' => 'CNAME', 'value' => 'target'],
            DnsRecord::cnameRecord('alias', 'target')->toArray()
        );
        $this->assertSame(
            ['name' => 'd', 'type' => 'MX', 'value' => 'mail', 'priority' => 5],
            DnsRecord::mxRecord('d', 5, 'mail')->toArray()
        );
        $this->assertSame(
            [
                'name' => '_sip._tcp',
                'type' => 'SRV',
                'value' => 'sipserver',
                'priority' => 1,
                'weight' => 2,
                'port' => 5060,
            ],
            DnsRecord::srvRecord('_sip._tcp', 1, 2, 5060, 'sipserver')->toArray()
        );
        $this->assertSame(
            ['name' => 'd', 'type' => 'TXT', 'value' => 'v=spf1'],
            DnsRecord::txtRecord('d', 'v=spf1')->toArray()
        );
    }

    public function testDnsResponseToArray(): void
    {
        $dns = DnsResponse::response()
            ->responseCode('NXDOMAIN')
            ->answer(DnsRecord::aRecord('a', '1.1.1.1'))
            ->authority(DnsRecord::txtRecord('b', 'note'))
            ->additional(DnsRecord::aaaaRecord('c', '::1'));

        $arr = $dns->toArray();
        $this->assertSame('NXDOMAIN', $arr['responseCode']);
        $this->assertCount(1, $arr['answerRecords']);
        $this->assertCount(1, $arr['authorityRecords']);
        $this->assertCount(1, $arr['additionalRecords']);
        $this->assertSame('1.1.1.1', $arr['answerRecords'][0]['value']);
    }

    public function testOpenApiExpectationToArray(): void
    {
        $this->assertSame(
            ['specUrlOrPayload' => 'spec.yaml'],
            OpenAPIExpectation::openAPI('spec.yaml')->toArray()
        );

        $this->assertSame(
            [
                'specUrlOrPayload' => 'spec.yaml',
                'operationsAndResponses' => ['op' => '200'],
            ],
            OpenAPIExpectation::openAPI('spec.yaml', ['op' => '200'])->toArray()
        );
    }

    public function testHttpWebSocketResponseGraphqlSubscriptionFilter(): void
    {
        $ws = HttpWebSocketResponse::response()
            ->subprotocol('graphql-transport-ws')
            ->graphqlSubscriptionFilter(
                GraphQLSubscriptionFilter::query('subscription OnMessage { messageAdded { id body } }')
                    ->operationName('OnMessage')
                    ->selectionSetMatchType(GraphQLSubscriptionFilter::AST_SUBSET)
                    ->fields(['messageAdded']),
            );

        $arr = $ws->toArray();
        $this->assertArrayHasKey('graphqlSubscriptionFilter', $arr);
        $this->assertSame(
            [
                'query' => 'subscription OnMessage { messageAdded { id body } }',
                'operationName' => 'OnMessage',
                'selectionSetMatchType' => 'AST_SUBSET',
                'fields' => ['messageAdded'],
            ],
            $arr['graphqlSubscriptionFilter'],
        );
    }

    public function testGraphqlSubscriptionFilterOmittedWhenNotSet(): void
    {
        $arr = HttpWebSocketResponse::response()->subprotocol('chat')->toArray();

        $this->assertArrayNotHasKey('graphqlSubscriptionFilter', $arr);
    }

    public function testGraphqlSubscriptionFilterEmitsQueryEvenWhenNothingElseSet(): void
    {
        // query is the server's only required property, so it must be emitted
        // unconditionally rather than through an "if set" guard.
        $arr = GraphQLSubscriptionFilter::query('subscription { t }')->toArray();

        $this->assertSame(['query' => 'subscription { t }'], $arr);
    }

    public function testGraphqlSubscriptionFilterMatchesTheSharedFixture(): void
    {
        // test-fixtures/expectations/action_websocket_graphql_filter.json
        $built = GraphQLSubscriptionFilter::query('subscription OnMessage { messageAdded { id body } }')
            ->type('GRAPHQL')
            ->operationName('OnMessage')
            ->variablesSchema(
                '{"type":"object","properties":{"roomId":{"type":"string"}},"required":["roomId"]}',
            )
            ->selectionSetMatchType(GraphQLSubscriptionFilter::AST_SUBSET)
            ->fields(['messageAdded'])
            ->toArray();

        $this->assertSame(
            SharedFixtures::sortedDeep(
                SharedFixtures::action('action_websocket_graphql_filter.json', 'httpWebSocketResponse', 6)
                    ['graphqlSubscriptionFilter'],
            ),
            SharedFixtures::sortedDeep($built),
        );
    }

    public function testHttpSseResponseTemplateType(): void
    {
        $arr = HttpSseResponse::response()->statusCode(200)->templateType('mustache')->toArray();

        $this->assertSame('MUSTACHE', $arr['templateType']);
    }

    public function testHttpWebSocketResponseTemplateType(): void
    {
        $arr = HttpWebSocketResponse::response()->subprotocol('chat')->templateType('velocity')->toArray();

        $this->assertSame('VELOCITY', $arr['templateType']);
    }

    public function testTemplateTypeOmittedWhenNotSet(): void
    {
        $this->assertArrayNotHasKey('templateType', HttpSseResponse::response()->statusCode(200)->toArray());
        $this->assertArrayNotHasKey('templateType', HttpWebSocketResponse::response()->subprotocol('c')->toArray());
    }

    // ------------------------------------------------------------------
    // gRPC bidirectional-streaming response — grpcBidiResponse
    // ------------------------------------------------------------------

    public function testGrpcBidiResponseMatchesTheSharedFixture(): void
    {
        // test-fixtures/expectations/action_grpc_bidi.json — exercises rules[] with a
        // templated response, which GrpcStreamMessage cannot express (no templateType).
        $built = GrpcBidiResponse::response()
            ->statusName('OK')
            ->header('x-meta', 'v')
            ->message(GrpcBidiMessage::message('{"greeting":"hi"}'))
            ->rule(
                GrpcBidiRule::matchJson('{"name":"world"}')
                    ->response(
                        GrpcBidiMessage::message('{"reply":"hello world"}')
                            ->templateType(GrpcBidiMessage::MUSTACHE),
                    ),
            )
            ->closeConnection(true)
            ->statusMessage('success')
            ->delay(Delay::milliseconds(10))
            ->primary(true)
            ->toArray();

        $this->assertSame(
            SharedFixtures::sortedDeep(
                SharedFixtures::action('action_grpc_bidi.json', 'grpcBidiResponse', 8),
            ),
            SharedFixtures::sortedDeep($built),
        );
    }

    public function testGrpcBidiKeepOpenMatchesTheSharedFixture(): void
    {
        // test-fixtures/expectations/action_grpc_bidi_keep_open.json — closeConnection and
        // primary are BOTH false here, so this fixture only round-trips if those are emitted
        // rather than dropped by a truthiness guard.
        $built = GrpcBidiResponse::response()
            ->statusName('OK')
            ->message(GrpcBidiMessage::message('{"greeting":"hi"}'))
            ->rule(
                GrpcBidiRule::matchJson('!{"name":"ignored"}')
                    ->response(GrpcBidiMessage::message('{"reply":"anything but ignored"}')),
            )
            ->closeConnection(false)
            ->primary(false)
            ->toArray();

        $this->assertSame(
            SharedFixtures::sortedDeep(
                SharedFixtures::action('action_grpc_bidi_keep_open.json', 'grpcBidiResponse', 5),
            ),
            SharedFixtures::sortedDeep($built),
        );
    }

    // ------------------------------------------------------------------
    // forward + OpenAPI validation — httpForwardValidateAction
    // ------------------------------------------------------------------

    public function testHttpForwardValidateMatchesTheSharedFixture(): void
    {
        // test-fixtures/expectations/action_forward_validate.json
        $built = HttpForwardValidateAction::forward('https://example.com/openapi.json', 'backend.example.com')
            ->port(443)
            ->scheme(HttpForwardValidateAction::HTTPS)
            ->validateRequest(true)
            ->validateResponse(true)
            ->validationMode(HttpForwardValidateAction::STRICT)
            ->delay(Delay::milliseconds(10))
            ->primary(true)
            ->toArray();

        $this->assertSame(
            SharedFixtures::sortedDeep(
                SharedFixtures::action('action_forward_validate.json', 'httpForwardValidateAction', 9),
            ),
            SharedFixtures::sortedDeep($built),
        );
    }

    public function testHttpForwardValidateResponseOnlyMatchesTheSharedFixture(): void
    {
        // test-fixtures/expectations/action_forward_validate_response_only.json — validateRequest,
        // validateResponse and primary are all false (falsy-survival), and validationMode is
        // LOG_ONLY (validate but do not fail the exchange), the non-STRICT half of the server enum.
        $built = HttpForwardValidateAction::forward('https://example.com/openapi.json', 'backend.example.com')
            ->port(443)
            ->scheme(HttpForwardValidateAction::HTTPS)
            ->validateRequest(false)
            ->validateResponse(false)
            ->validationMode(HttpForwardValidateAction::LOG_ONLY)
            ->primary(false)
            ->toArray();

        $this->assertSame(
            SharedFixtures::sortedDeep(
                SharedFixtures::action('action_forward_validate_response_only.json', 'httpForwardValidateAction', 8),
            ),
            SharedFixtures::sortedDeep($built),
        );
    }

    public function testValidationModePassesArbitraryStringsThroughUppercased(): void
    {
        // validationMode() defers enum validity to the server (like scheme() / templateType()):
        // it uppercases and forwards whatever it is given rather than restricting to the two
        // constants. Probed with a deliberately-fictional value so this property is NOT pinned to
        // a shared cross-client fixture — the server would reject "made_up_mode" with a 400, which
        // is the point: the client does not pre-validate.
        $arr = HttpForwardValidateAction::forward('https://example.com/openapi.json', 'h')
            ->validationMode('made_up_mode')
            ->toArray();

        $this->assertSame('MADE_UP_MODE', $arr['validationMode']);
    }

    // ------------------------------------------------------------------
    // forward with fallback — httpForwardWithFallback
    // ------------------------------------------------------------------

    public function testHttpForwardWithFallbackMatchesTheSharedFixture(): void
    {
        // test-fixtures/expectations/action_forward_fallback.json
        $built = HttpForwardWithFallback::forward(
            HttpForward::forward()->scheme('HTTPS')->host('backend.example.com')->port(443),
            HttpResponse::response()->statusCode(503)->body('unavailable'),
        )
            ->fallbackOnStatusCodes(500, 502, 503)
            ->fallbackOnTimeout(true)
            ->delay(Delay::milliseconds(10))
            ->primary(true)
            ->toArray();

        $this->assertSame(
            SharedFixtures::sortedDeep(
                SharedFixtures::action('action_forward_fallback.json', 'httpForwardWithFallback', 6),
            ),
            SharedFixtures::sortedDeep($built),
        );
    }

    public function testHttpForwardWithFallbackStatusOnlyMatchesTheSharedFixture(): void
    {
        // test-fixtures/expectations/action_forward_fallback_status_only.json — fallbackOnTimeout
        // and primary are both false (falsy-survival).
        $built = HttpForwardWithFallback::forward(
            HttpForward::forward()->scheme('HTTPS')->host('backend.example.com')->port(443),
            HttpResponse::response()->statusCode(503)->body('unavailable'),
        )
            ->fallbackOnStatusCodes(500)
            ->fallbackOnTimeout(false)
            ->primary(false)
            ->toArray();

        $this->assertSame(
            SharedFixtures::sortedDeep(
                SharedFixtures::action('action_forward_fallback_status_only.json', 'httpForwardWithFallback', 5),
            ),
            SharedFixtures::sortedDeep($built),
        );
    }

    // ------------------------------------------------------------------
    // template action — httpTemplate (no bare-httpTemplate shared fixture;
    // the shape is served under httpResponseTemplate / httpForwardTemplate)
    // ------------------------------------------------------------------

    public function testHttpTemplateToArrayEmitsExactlyTheSetFields(): void
    {
        $arr = HttpTemplate::template(HttpTemplate::MUSTACHE, '{"statusCode": 200, "body": "{{request.path}}"}')
            ->delay(Delay::milliseconds(5))
            ->primary(true)
            ->responseOverride(HttpResponse::response()->statusCode(200))
            ->toArray();

        $this->assertSame([
            'delay' => ['timeUnit' => 'MILLISECONDS', 'value' => 5],
            'templateType' => 'MUSTACHE',
            'template' => '{"statusCode": 200, "body": "{{request.path}}"}',
            'primary' => true,
            'responseOverride' => ['statusCode' => 200],
        ], $arr);
    }

    public function testHttpTemplateParametersAreEmittedAsAJsonObject(): void
    {
        $tmpl = HttpTemplate::template(HttpTemplate::MUSTACHE, '{{ parameters.greeting }}')
            ->withParameter('greeting', 'hello')
            ->withParameters(['names' => ['ann', 'bob'], 'limits' => ['max' => 5, 'none' => null], 'empty' => '']);

        $this->assertSame([
            'greeting' => 'hello',
            'names' => ['ann', 'bob'],
            'limits' => ['max' => 5, 'none' => null],
            'empty' => '',
        ], $tmpl->toArray()['parameters']);
        $this->assertSame(
            '{"templateType":"MUSTACHE","template":"{{ parameters.greeting }}","parameters":{"greeting":"hello","names":["ann","bob"],"limits":{"max":5,"none":null},"empty":""}}',
            json_encode($tmpl, JSON_UNESCAPED_SLASHES)
        );
        $this->assertArrayNotHasKey('parameters', HttpTemplate::template(HttpTemplate::VELOCITY, 'x')->toArray());
    }

    public function testHttpTemplateFileFactoryAndFalsyPrimary(): void
    {
        $arr = HttpTemplate::templateFile(HttpTemplate::VELOCITY, '/tmpl/response.vm')
            ->primary(false)
            ->toArray();

        // templateFile rather than template; primary false must survive.
        $this->assertSame([
            'templateType' => 'VELOCITY',
            'templateFile' => '/tmpl/response.vm',
            'primary' => false,
        ], $arr);
    }

    public function testHttpTemplateWiresUnderBothActionKeys(): void
    {
        // The one shape serves two distinct expectation action keys; both must round-trip.
        $tmpl = HttpTemplate::template(HttpTemplate::MUSTACHE, '{"body":"x"}');

        $response = (new \MockServer\Expectation())->httpResponseTemplate($tmpl)->toArray();
        $this->assertArrayHasKey('httpResponseTemplate', $response);
        $this->assertArrayNotHasKey('httpForwardTemplate', $response);

        $forward = (new \MockServer\Expectation())->httpForwardTemplate($tmpl)->toArray();
        $this->assertArrayHasKey('httpForwardTemplate', $forward);
        $this->assertArrayNotHasKey('httpResponseTemplate', $forward);
    }
}
