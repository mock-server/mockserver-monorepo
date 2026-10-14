# frozen_string_literal: true

require 'yaml'

RSpec.describe MockServer::Client do
  let(:base_url) { 'http://localhost:1080' }
  let(:client) { MockServer::Client.new('localhost', 1080) }

  after { client.close }

  let(:explanation) do
    {
      'correlationId' => 'abc 123/x',
      'timestamp' => '2026-10-14T10:00:00Z',
      'recomputed' => true,
      'note' => 'recomputed now',
      'request' => { 'timestamp' => '2026-10-14 10:00:00.000', 'method' => 'GET', 'path' => '/orders',
                     'bodyTruncated' => true, 'bodyTruncatedNote' => 'kept the start' },
      'atArrival' => { 'outcome' => 'MATCHED', 'expectationId' => 'catch-all', 'expectationStillStored' => true },
      'sameOutcomeAsAtArrival' => true,
      'expectationsChangedSince' => false,
      'expectationsLastChanged' => '2026-10-14T09:00:00Z',
      'matchedExpectation' => { 'expectationId' => 'catch-all', 'position' => 2, 'priority' => 0, 'matches' => true },
      'skippedExpectations' => [{
        'expectationId' => 'gold', 'expectationMethod' => 'GET', 'expectationPath' => '/orders',
        'position' => 1, 'priority' => 10, 'reason' => 'FIELDS_DIFFER', 'explanation' => 'header differs',
        'matches' => false, 'matchedFieldCount' => 17, 'totalFieldCount' => 18, 'differingFieldCount' => 1,
        'differences' => { 'headers' => ['X-Tenant differs'] }, 'remediation' => { 'headers' => 'add X-Tenant' }
      }],
      'shadowedExpectations' => [{ 'expectationId' => 'late', 'position' => 3, 'priority' => 0,
                                   'reason' => 'LOWER_PRIORITY', 'explanation' => 'also matches' }],
      'totalExpectations' => 3,
      'evaluatedExpectations' => 3,
      'truncated' => true,
      'maxExpectationsEvaluated' => 500,
      'narration' => { 'available' => true, 'aiGenerated' => true, 'label' => 'AI-generated, can be wrong',
                       'provider' => 'OPENAI', 'model' => 'gpt-test', 'text' => 'gold needs X-Tenant' }
    }
  end

  describe '#explain' do
    it 'sends PUT /mockserver/explain with the correlation id and returns a typed explanation' do
      stub_request(:put, "#{base_url}/mockserver/explain")
        .with(query: { 'correlationId' => 'abc 123/x' })
        .to_return(status: 200, body: JSON.generate(explanation))

      result = client.explain('abc 123/x')

      expect(WebMock).to have_requested(:put, "#{base_url}/mockserver/explain")
        .with(query: { 'correlationId' => 'abc 123/x' }).once
      expect(result).to be_a(MockServer::RequestExplanation)
      expect(result.correlation_id).to eq('abc 123/x')
      expect(result.recomputed).to be(true)
      expect(result.note).to eq('recomputed now')
      expect([result.request.method, result.request.path]).to eq(%w[GET /orders])
      expect(result.request.body_truncated).to be(true)
      expect(result.request.body_truncated_note).to eq('kept the start')
      expect(result.at_arrival.outcome).to eq('MATCHED')
      expect(result.at_arrival.expectation_id).to eq('catch-all')
      expect(result.at_arrival.expectation_still_stored).to be(true)
      expect(result.at_arrival.forward_failure).to be_nil
      expect(result.same_outcome_as_at_arrival).to be(true)
      expect(result.expectations_changed_since).to be(false)
      expect(result.expectations_last_changed).to eq('2026-10-14T09:00:00Z')
      expect(result.matched_expectation.expectation_id).to eq('catch-all')
      expect(result.matched_expectation.reason).to be_nil
      skipped = result.skipped_expectations.first
      expect(skipped).to be_a(MockServer::ExplainedExpectation)
      expect([skipped.expectation_id, skipped.expectation_method, skipped.expectation_path]).to eq(%w[gold GET /orders])
      expect([skipped.position, skipped.priority, skipped.reason]).to eq([1, 10, 'FIELDS_DIFFER'])
      expect(skipped.explanation).to eq('header differs')
      expect(skipped.matches).to be(false)
      expect([skipped.matched_field_count, skipped.total_field_count, skipped.differing_field_count]).to eq([17, 18, 1])
      expect(skipped.differences).to eq('headers' => ['X-Tenant differs'])
      expect(skipped.remediation).to eq('headers' => 'add X-Tenant')
      expect(result.shadowed_expectations.first.reason).to eq('LOWER_PRIORITY')
      expect(result.closest_expectation_id).to be_nil
      expect([result.total_expectations, result.evaluated_expectations]).to eq([3, 3])
      expect(result.truncated).to be(true)
      expect(result.max_expectations_evaluated).to eq(500)
      narration = result.narration
      expect(narration).to be_a(MockServer::ExplanationNarration)
      expect([narration.available, narration.ai_generated, narration.label]).to eq([true, true, 'AI-generated, can be wrong'])
      expect([narration.provider, narration.model, narration.text]).to eq(['OPENAI', 'gpt-test', 'gold needs X-Tenant'])
      expect(narration.reason).to be_nil
    end

    it 'returns the reason a failed forward gave at arrival' do
      failed = explanation.merge(
        'atArrival' => {
          'outcome' => 'MATCHED', 'expectationId' => 'stock-forward', 'expectationStillStored' => true,
          'forwardFailure' => { 'statusCode' => 502, 'reason' => 'Connection refused', 'reasonTruncated' => true,
                                'causeNamed' => false, 'forwardLoopSuspected' => true, 'hint' => 'retrieve_logs' }
        }
      )
      stub_request(:put, "#{base_url}/mockserver/explain")
        .with(query: { 'correlationId' => 'abc' })
        .to_return(status: 200, body: JSON.generate(failed))

      failure = client.explain('abc').at_arrival.forward_failure

      expect(failure).to be_a(MockServer::ArrivalForwardFailure)
      expect([failure.status_code, failure.reason, failure.reason_truncated]).to eq([502, 'Connection refused', true])
      expect([failure.cause_named, failure.forward_loop_suspected, failure.hint]).to eq([false, true, 'retrieve_logs'])
    end

    it 'leaves out the forward failure fields a server did not send' do
      arrival = MockServer::Arrival.from_hash(
        'outcome' => 'FORWARDED_WITHOUT_EXPECTATION',
        'forwardFailure' => { 'reason' => 'timed out', 'causeNamed' => true }
      )

      failure = arrival.forward_failure
      expect([failure.reason, failure.cause_named, failure.reason_truncated]).to eq(['timed out', true, false])
      expect([failure.status_code, failure.forward_loop_suspected, failure.hint]).to eq([nil, nil, nil])
    end

    it 'raises Error with the server reason for an unknown correlation id' do
      stub_request(:put, "#{base_url}/mockserver/explain")
        .with(query: { 'correlationId' => 'unknown' })
        .to_return(status: 404, body: JSON.generate('error' => 'no request is recorded with correlationId unknown'))

      expect { client.explain('unknown') }.to raise_error(
        MockServer::Error,
        'explain failed for correlationId "unknown" (status=404): no request is recorded with correlationId unknown'
      )
    end

    it 'raises ArgumentError for a blank correlation id without calling the server' do
      ['', '  ', nil].each do |blank|
        expect { client.explain(blank) }
          .to raise_error(ArgumentError, 'explain requires a non blank correlation_id')
      end
      expect(WebMock).not_to have_requested(:any, /.*/)
    end
  end

  describe '#debug_mismatch' do
    let(:analysis) do
      {
        'correlationId' => 'c1', 'timestamp' => '2026-10-14T10:00:00Z',
        'totalExpectations' => 1, 'evaluatedExpectations' => 1,
        'closestMatch' => { 'expectationId' => 'e1', 'matchedFields' => 17, 'totalFields' => 18 },
        'results' => [{ 'expectationId' => 'e1', 'expectationPath' => '/api/users', 'expectationMethod' => 'POST',
                        'matches' => false, 'matchedFieldCount' => 17, 'totalFieldCount' => 18,
                        'differences' => { 'method' => ['expected POST found GET'] } }]
      }
    end

    it 'sends the request to PUT /mockserver/debugMismatch and returns a typed analysis' do
      stub_request(:put, "#{base_url}/mockserver/debugMismatch")
        .with(body: { 'method' => 'GET', 'path' => '/api/users' })
        .to_return(status: 200, body: JSON.generate(analysis))

      result = client.debug_mismatch(MockServer::HttpRequest.new(method: 'GET', path: '/api/users'))

      expect(result).to be_a(MockServer::DebugMismatchResult)
      expect([result.total_expectations, result.evaluated_expectations, result.truncated]).to eq([1, 1, false])
      expect(result.closest_match.expectation_id).to eq('e1')
      expect([result.closest_match.matched_fields, result.closest_match.total_fields]).to eq([17, 18])
      expect(result.results.first.differences).to eq('method' => ['expected POST found GET'])
      expect(result.results.first.matched_field_count).to eq(17)
    end

    it 'accepts a Hash request' do
      stub_request(:put, "#{base_url}/mockserver/debugMismatch")
        .with(body: { 'method' => 'GET', 'path' => '/x' })
        .to_return(status: 200, body: JSON.generate(analysis))

      expect(client.debug_mismatch('method' => 'GET', 'path' => '/x').results.size).to eq(1)
    end

    it 'raises Error with the server reason for a non-HTTP definition' do
      stub_request(:put, "#{base_url}/mockserver/debugMismatch")
        .to_return(status: 400, body: JSON.generate('error' => 'debugMismatch only supports HttpRequest definitions'))

      expect { client.debug_mismatch('specUrlOrPayload' => 'x') }
        .to raise_error(MockServer::Error, /debugMismatch only supports HttpRequest definitions/)
    end
  end

  describe '#explain_unmatched' do
    let(:report) do
      {
        'correlationId' => 'c2', 'timestamp' => '2026-10-14T10:00:00Z', 'unmatchedRequestCount' => 1, 'truncated' => false,
        'unmatchedRequests' => [{
          'timestamp' => '2026-10-14 10:00:00.000', 'method' => 'GET', 'path' => '/api/order',
          'totalExpectationsEvaluated' => 1,
          'closestExpectations' => [{
            'expectationId' => 'e1', 'matches' => false, 'differingFieldCount' => 1,
            'suggestedFix' => { 'patch' => [{ 'op' => 'replace', 'path' => '/httpRequest/path', 'value' => '/api/order' }],
                                'patchedFields' => ['path'],
                                'unpatchedFields' => [{ 'field' => 'body', 'reason' => 'redacted' }],
                                'matchesAfterPatch' => true }
          }]
        }],
        'narration' => { 'available' => false, 'reason' => 'llmExplainNarrationEnabled is false' }
      }
    end

    it 'sends limit and suggestFixes and returns a typed report' do
      stub_request(:put, "#{base_url}/mockserver/explainUnmatched")
        .with(body: { 'limit' => 5, 'suggestFixes' => true })
        .to_return(status: 200, body: JSON.generate(report))

      result = client.explain_unmatched(limit: 5, suggest_fixes: true)

      expect(result).to be_a(MockServer::ExplainUnmatchedResult)
      expect([result.unmatched_request_count, result.truncated]).to eq([1, false])
      unmatched = result.unmatched_requests.first
      expect([unmatched.method, unmatched.path, unmatched.total_expectations_evaluated]).to eq(['GET', '/api/order', 1])
      fix = unmatched.closest_expectations.first.suggested_fix
      expect(fix.patch).to eq([{ 'op' => 'replace', 'path' => '/httpRequest/path', 'value' => '/api/order' }])
      expect(fix.patched_fields).to eq(['path'])
      expect(fix.unpatched_fields).to eq([{ 'field' => 'body', 'reason' => 'redacted' }])
      expect(fix.matches_after_patch).to be(true)
      expect([result.narration.available, result.narration.reason]).to eq([false, 'llmExplainNarrationEnabled is false'])
      expect(result.narration.text).to be_nil
    end

    it 'sends no body without options' do
      stub_request(:put, "#{base_url}/mockserver/explainUnmatched").to_return(status: 200, body: JSON.generate(report))

      client.explain_unmatched

      expect(WebMock).to(have_requested(:put, "#{base_url}/mockserver/explainUnmatched")
        .with { |req| req.body.nil? || req.body.empty? })
    end

    it 'raises Error on a server failure' do
      stub_request(:put, "#{base_url}/mockserver/explainUnmatched").to_return(status: 500, body: 'boom')

      expect { client.explain_unmatched }
        .to raise_error(MockServer::Error, 'explainUnmatched failed (status=500): boom')
    end
  end

  describe 'the explain models follow the OpenAPI schemas' do
    let(:schemas) do
      spec = File.expand_path('../../../mockserver/mockserver-core/src/main/resources/org/mockserver/openapi/' \
                              'mock-server-openapi-embedded-model.yaml', __dir__)
      YAML.safe_load(File.read(spec), aliases: true)['components']['schemas']
    end

    def readers(model)
      model.public_instance_methods(false).map(&:to_s).sort
    end

    def snake(name)
      name.gsub(/([A-Z])/) { "_#{Regexp.last_match(1).downcase}" }
    end

    it 'RequestExplanation reads every schema property' do
      expect(readers(MockServer::RequestExplanation))
        .to eq(schemas['RequestExplanation']['properties'].keys.map { |k| snake(k) }.sort)
    end

    it 'Arrival and ArrivalForwardFailure read every atArrival and forwardFailure schema property' do
      at_arrival = schemas['RequestExplanation']['properties']['atArrival']
      expect(readers(MockServer::Arrival)).to eq(at_arrival['properties'].keys.map { |k| snake(k) }.sort)
      expect(readers(MockServer::ArrivalForwardFailure))
        .to eq(at_arrival['properties']['forwardFailure']['properties'].keys.map { |k| snake(k) }.sort)
    end

    %w[ExplainedExpectation SuggestedFix DebugMismatchResult ClosestMatch ExplainUnmatchedResult UnmatchedRequest
       ExplanationNarration].each do |name|
      it "#{name} reads every schema property" do
        expect(readers(MockServer.const_get(name)))
          .to eq(schemas[name]['properties'].keys.map { |k| snake(k) }.sort)
      end
    end
  end
end
