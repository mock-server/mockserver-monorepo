# frozen_string_literal: true

RSpec.describe 'MockServer models' do
  # -------------------------------------------------------------------
  # DelayDistribution
  # -------------------------------------------------------------------
  describe MockServer::DelayDistribution do
    it 'has nil defaults' do
      dist = MockServer::DelayDistribution.new
      expect(dist.type).to be_nil
      expect(dist.min).to be_nil
      expect(dist.max).to be_nil
    end

    it 'serializes uniform to camelCase hash' do
      dist = MockServer::DelayDistribution.new(type: 'UNIFORM', min: 100, max: 500)
      expect(dist.to_h).to eq({ 'type' => 'UNIFORM', 'min' => 100, 'max' => 500 })
    end

    it 'serializes log_normal to camelCase hash' do
      dist = MockServer::DelayDistribution.new(type: 'LOG_NORMAL', median: 200, p99: 800)
      expect(dist.to_h).to eq({ 'type' => 'LOG_NORMAL', 'median' => 200, 'p99' => 800 })
    end

    it 'serializes gaussian to camelCase hash' do
      dist = MockServer::DelayDistribution.new(type: 'GAUSSIAN', mean: 200, std_dev: 50)
      expect(dist.to_h).to eq({ 'type' => 'GAUSSIAN', 'mean' => 200, 'stdDev' => 50 })
    end

    it 'deserializes from camelCase hash' do
      dist = MockServer::DelayDistribution.from_hash({ 'type' => 'UNIFORM', 'min' => 10, 'max' => 20 })
      expect(dist.type).to eq('UNIFORM')
      expect(dist.min).to eq(10)
      expect(dist.max).to eq(20)
    end

    it 'returns nil from_hash when data is nil' do
      expect(MockServer::DelayDistribution.from_hash(nil)).to be_nil
    end

    it 'round-trips correctly' do
      original = MockServer::DelayDistribution.new(type: 'GAUSSIAN', mean: 100, std_dev: 25)
      roundtrip = MockServer::DelayDistribution.from_hash(original.to_h)
      expect(roundtrip.to_h).to eq(original.to_h)
    end
  end

  # -------------------------------------------------------------------
  # Delay
  # -------------------------------------------------------------------
  describe MockServer::Delay do
    it 'has sensible defaults' do
      delay = MockServer::Delay.new
      expect(delay.time_unit).to eq('MILLISECONDS')
      expect(delay.value).to eq(0)
      expect(delay.distribution).to be_nil
    end

    it 'serializes to camelCase hash' do
      delay = MockServer::Delay.new(time_unit: 'SECONDS', value: 5)
      expect(delay.to_h).to eq({ 'timeUnit' => 'SECONDS', 'value' => 5 })
    end

    it 'deserializes from camelCase hash' do
      delay = MockServer::Delay.from_hash({ 'timeUnit' => 'SECONDS', 'value' => 3 })
      expect(delay.time_unit).to eq('SECONDS')
      expect(delay.value).to eq(3)
    end

    it 'returns nil from_hash when data is nil' do
      expect(MockServer::Delay.from_hash(nil)).to be_nil
    end

    it 'round-trips correctly' do
      original = MockServer::Delay.new(time_unit: 'SECONDS', value: 10)
      roundtrip = MockServer::Delay.from_hash(original.to_h)
      expect(roundtrip.to_h).to eq(original.to_h)
    end

    it 'serializes with distribution' do
      dist = MockServer::DelayDistribution.new(type: 'UNIFORM', min: 100, max: 500)
      delay = MockServer::Delay.new(time_unit: 'MILLISECONDS', distribution: dist)
      expect(delay.to_h).to eq({
        'timeUnit' => 'MILLISECONDS',
        'value' => 0,
        'distribution' => { 'type' => 'UNIFORM', 'min' => 100, 'max' => 500 }
      })
    end

    it 'deserializes with distribution' do
      delay = MockServer::Delay.from_hash({
        'timeUnit' => 'MILLISECONDS',
        'distribution' => { 'type' => 'LOG_NORMAL', 'median' => 200, 'p99' => 800 }
      })
      expect(delay.distribution).not_to be_nil
      expect(delay.distribution.type).to eq('LOG_NORMAL')
      expect(delay.distribution.median).to eq(200)
      expect(delay.distribution.p99).to eq(800)
    end

    it 'round-trips with distribution' do
      dist = MockServer::DelayDistribution.new(type: 'GAUSSIAN', mean: 200, std_dev: 50)
      original = MockServer::Delay.new(time_unit: 'MILLISECONDS', distribution: dist)
      roundtrip = MockServer::Delay.from_hash(original.to_h)
      expect(roundtrip.to_h).to eq(original.to_h)
    end
  end

  # -------------------------------------------------------------------
  # Times
  # -------------------------------------------------------------------
  describe MockServer::Times do
    it 'defaults all fields to nil' do
      times = MockServer::Times.new
      expect(times.remaining_times).to be_nil
      expect(times.unlimited).to be_nil
    end

    it 'serializes stripping nil values' do
      times = MockServer::Times.new(remaining_times: 3)
      expect(times.to_h).to eq({ 'remainingTimes' => 3 })
    end

    it 'deserializes from hash' do
      times = MockServer::Times.from_hash({ 'remainingTimes' => 5, 'unlimited' => false })
      expect(times.remaining_times).to eq(5)
      expect(times.unlimited).to eq(false)
    end

    it 'returns nil from_hash when data is nil' do
      expect(MockServer::Times.from_hash(nil)).to be_nil
    end

    describe '.unlimited' do
      it 'creates unlimited Times' do
        times = MockServer::Times.unlimited
        expect(times.unlimited).to eq(true)
        expect(times.remaining_times).to be_nil
      end
    end

    describe '.exactly' do
      it 'creates Times with exact count' do
        times = MockServer::Times.exactly(3)
        expect(times.remaining_times).to eq(3)
        expect(times.unlimited).to eq(false)
      end
    end

    it 'round-trips correctly' do
      original = MockServer::Times.exactly(5)
      roundtrip = MockServer::Times.from_hash(original.to_h)
      expect(roundtrip.to_h).to eq(original.to_h)
    end
  end

  # -------------------------------------------------------------------
  # TimeToLive
  # -------------------------------------------------------------------
  describe MockServer::TimeToLive do
    it 'defaults all fields to nil' do
      ttl = MockServer::TimeToLive.new
      expect(ttl.time_unit).to be_nil
      expect(ttl.time_to_live).to be_nil
      expect(ttl.unlimited).to be_nil
    end

    it 'serializes stripping nil values' do
      ttl = MockServer::TimeToLive.new(unlimited: true)
      expect(ttl.to_h).to eq({ 'unlimited' => true })
    end

    it 'deserializes from hash' do
      ttl = MockServer::TimeToLive.from_hash({
        'timeUnit' => 'SECONDS',
        'timeToLive' => 30,
        'unlimited' => false
      })
      expect(ttl.time_unit).to eq('SECONDS')
      expect(ttl.time_to_live).to eq(30)
      expect(ttl.unlimited).to eq(false)
    end

    it 'returns nil from_hash when data is nil' do
      expect(MockServer::TimeToLive.from_hash(nil)).to be_nil
    end

    describe '.unlimited' do
      it 'creates unlimited TimeToLive' do
        ttl = MockServer::TimeToLive.unlimited
        expect(ttl.unlimited).to eq(true)
      end
    end

    describe '.exactly' do
      it 'creates TimeToLive with exact duration' do
        ttl = MockServer::TimeToLive.exactly(60, 'SECONDS')
        expect(ttl.time_to_live).to eq(60)
        expect(ttl.time_unit).to eq('SECONDS')
        expect(ttl.unlimited).to eq(false)
      end
    end

    it 'round-trips correctly' do
      original = MockServer::TimeToLive.exactly(30, 'SECONDS')
      roundtrip = MockServer::TimeToLive.from_hash(original.to_h)
      expect(roundtrip.to_h).to eq(original.to_h)
    end
  end

  # -------------------------------------------------------------------
  # KeyToMultiValue
  # -------------------------------------------------------------------
  describe MockServer::KeyToMultiValue do
    it 'defaults to empty name and values' do
      kv = MockServer::KeyToMultiValue.new
      expect(kv.name).to eq('')
      expect(kv.values).to eq([])
    end

    it 'always emits name and values (server-required fields)' do
      kv = MockServer::KeyToMultiValue.new(name: 'Content-Type', values: ['application/json'])
      h = kv.to_h
      expect(h).to have_key('name')
      expect(h).to have_key('values')
      expect(h['name']).to eq('Content-Type')
      expect(h['values']).to eq(['application/json'])
    end

    it 'emits name and values even when empty' do
      kv = MockServer::KeyToMultiValue.new
      h = kv.to_h
      expect(h).to eq({ 'name' => '', 'values' => [] })
    end

    it 'deserializes from hash' do
      kv = MockServer::KeyToMultiValue.from_hash({ 'name' => 'Accept', 'values' => ['*/*'] })
      expect(kv.name).to eq('Accept')
      expect(kv.values).to eq(['*/*'])
    end

    it 'returns nil from_hash when data is nil' do
      expect(MockServer::KeyToMultiValue.from_hash(nil)).to be_nil
    end

    it 'round-trips correctly' do
      original = MockServer::KeyToMultiValue.new(name: 'X-Test', values: %w[a b])
      roundtrip = MockServer::KeyToMultiValue.from_hash(original.to_h)
      expect(roundtrip.to_h).to eq(original.to_h)
    end
  end

  # -------------------------------------------------------------------
  # Body
  # -------------------------------------------------------------------
  describe MockServer::Body do
    it 'defaults all fields to nil' do
      body = MockServer::Body.new
      expect(body.type).to be_nil
      expect(body.string).to be_nil
      expect(body.json).to be_nil
    end

    it 'serializes only non-nil fields' do
      body = MockServer::Body.new(type: 'STRING', string: 'hello')
      expect(body.to_h).to eq({ 'type' => 'STRING', 'string' => 'hello' })
    end

    it 'serializes not_body as "not" key' do
      body = MockServer::Body.new(type: 'STRING', string: 'hello', not_body: true)
      expect(body.to_h['not']).to eq(true)
    end

    it 'serializes base64_bytes as "base64Bytes"' do
      body = MockServer::Body.new(type: 'BINARY', base64_bytes: 'AAAA')
      expect(body.to_h['base64Bytes']).to eq('AAAA')
    end

    it 'serializes content_type as "contentType"' do
      body = MockServer::Body.new(type: 'STRING', string: 'x', content_type: 'text/plain')
      expect(body.to_h['contentType']).to eq('text/plain')
    end

    it 'deserializes from hash' do
      body = MockServer::Body.from_hash({
        'type' => 'JSON',
        'json' => { 'key' => 'value' },
        'not' => false,
        'contentType' => 'application/json'
      })
      expect(body.type).to eq('JSON')
      expect(body.json).to eq({ 'key' => 'value' })
      expect(body.not_body).to eq(false)
      expect(body.content_type).to eq('application/json')
    end

    it 'returns nil from_hash when data is nil' do
      expect(MockServer::Body.from_hash(nil)).to be_nil
    end

    describe 'factory methods' do
      it '.string creates STRING body' do
        body = MockServer::Body.string('hello')
        expect(body.type).to eq('STRING')
        expect(body.string).to eq('hello')
      end

      it '.json creates JSON body' do
        body = MockServer::Body.json({ 'key' => 'val' })
        expect(body.type).to eq('JSON')
        expect(body.json).to eq({ 'key' => 'val' })
      end

      it '.regex creates REGEX body' do
        body = MockServer::Body.regex('.*test.*')
        expect(body.type).to eq('REGEX')
        expect(body.regex).to eq('.*test.*')
        expect(body.to_h).to eq({ 'type' => 'REGEX', 'regex' => '.*test.*' })
      end

      it '.json_path creates JSON_PATH body serialising to jsonPath' do
        body = MockServer::Body.json_path('$.name')
        expect(body.type).to eq('JSON_PATH')
        expect(body.json_path).to eq('$.name')
        expect(body.to_h).to eq({ 'type' => 'JSON_PATH', 'jsonPath' => '$.name' })
      end

      it '.xpath creates XPATH body serialising to xpath' do
        body = MockServer::Body.xpath('/root/active')
        expect(body.type).to eq('XPATH')
        expect(body.to_h).to eq({ 'type' => 'XPATH', 'xpath' => '/root/active' })
      end

      it '.all_of combines sub-bodies under bodyAllOf' do
        body = MockServer::Body.all_of(
          MockServer::Body.json_path('$.name'),
          MockServer::Body.regex('.*active.*')
        )
        expect(body.type).to eq('ALL_OF')
        expect(body.to_h).to eq({
          'type' => 'ALL_OF',
          'bodyAllOf' => [
            { 'type' => 'JSON_PATH', 'jsonPath' => '$.name' },
            { 'type' => 'REGEX', 'regex' => '.*active.*' }
          ]
        })
      end

      it '.all_of round-trips through JSON' do
        body = MockServer::Body.all_of(
          MockServer::Body.json_path('$.name'),
          MockServer::Body.regex('.*active.*')
        )
        json = JSON.generate(body.to_h)
        expect(json).to eq(
          '{"type":"ALL_OF","bodyAllOf":[' \
          '{"type":"JSON_PATH","jsonPath":"$.name"},' \
          '{"type":"REGEX","regex":".*active.*"}]}'
        )
        roundtrip = MockServer::Body.from_hash(JSON.parse(json))
        expect(roundtrip.type).to eq('ALL_OF')
        expect(roundtrip.body_all_of.length).to eq(2)
        expect(roundtrip.body_all_of[0]).to be_a(MockServer::Body)
        expect(roundtrip.body_all_of[0].json_path).to eq('$.name')
        expect(roundtrip.body_all_of[1].regex).to eq('.*active.*')
        expect(roundtrip.to_h).to eq(body.to_h)
      end

      it '.exact creates STRING body' do
        body = MockServer::Body.exact('exact match')
        expect(body.type).to eq('STRING')
        expect(body.string).to eq('exact match')
      end

      it '.xml creates XML body' do
        body = MockServer::Body.xml('<root/>')
        expect(body.type).to eq('XML')
        expect(body.string).to eq('<root/>')
      end
    end

    it 'round-trips correctly' do
      original = MockServer::Body.new(
        type: 'JSON',
        json: { 'a' => 1 },
        content_type: 'application/json'
      )
      roundtrip = MockServer::Body.from_hash(original.to_h)
      expect(roundtrip.to_h).to eq(original.to_h)
    end

    describe 'FILE body with file_path and template_type' do
      it '.file factory creates FILE body with file_path' do
        body = MockServer::Body.file('/data/response.json')
        expect(body.type).to eq('FILE')
        expect(body.file_path).to eq('/data/response.json')
        expect(body.template_type).to be_nil
        expect(body.content_type).to be_nil
      end

      it '.file factory accepts content_type and template_type' do
        body = MockServer::Body.file(
          '/templates/response.vm',
          content_type: 'application/json',
          template_type: 'VELOCITY'
        )
        expect(body.type).to eq('FILE')
        expect(body.file_path).to eq('/templates/response.vm')
        expect(body.content_type).to eq('application/json')
        expect(body.template_type).to eq('VELOCITY')
      end

      it 'serializes file_path as filePath' do
        body = MockServer::Body.new(type: 'FILE', file_path: '/data/body.json')
        h = body.to_h
        expect(h['type']).to eq('FILE')
        expect(h['filePath']).to eq('/data/body.json')
      end

      it 'serializes template_type as templateType on FILE body' do
        body = MockServer::Body.new(
          type: 'FILE',
          file_path: '/tpl/response.mustache',
          template_type: 'MUSTACHE'
        )
        h = body.to_h
        expect(h['templateType']).to eq('MUSTACHE')
        expect(h['filePath']).to eq('/tpl/response.mustache')
      end

      it 'omits filePath and templateType when nil' do
        body = MockServer::Body.new(type: 'STRING', string: 'hello')
        h = body.to_h
        expect(h).not_to have_key('filePath')
        expect(h).not_to have_key('templateType')
      end

      it 'deserializes FILE body from hash' do
        body = MockServer::Body.from_hash({
          'type' => 'FILE',
          'filePath' => '/data/response.json',
          'contentType' => 'application/json'
        })
        expect(body.type).to eq('FILE')
        expect(body.file_path).to eq('/data/response.json')
        expect(body.content_type).to eq('application/json')
        expect(body.template_type).to be_nil
      end

      it 'deserializes FILE body with templateType from hash' do
        body = MockServer::Body.from_hash({
          'type' => 'FILE',
          'filePath' => '/templates/forward.vm',
          'templateType' => 'VELOCITY'
        })
        expect(body.type).to eq('FILE')
        expect(body.file_path).to eq('/templates/forward.vm')
        expect(body.template_type).to eq('VELOCITY')
      end

      it 'round-trips FILE body with template_type' do
        original = MockServer::Body.file(
          '/etc/mockserver/templates/body.mustache',
          content_type: 'text/html',
          template_type: 'MUSTACHE'
        )
        roundtrip = MockServer::Body.from_hash(original.to_h)
        expect(roundtrip.to_h).to eq(original.to_h)
        expect(roundtrip.file_path).to eq('/etc/mockserver/templates/body.mustache')
        expect(roundtrip.template_type).to eq('MUSTACHE')
        expect(roundtrip.content_type).to eq('text/html')
      end

      it '#with_template_type sets template_type fluently' do
        body = MockServer::Body.file('/data/response.vm')
        result = body.with_template_type('VELOCITY')
        expect(result).to be(body)
        expect(body.template_type).to eq('VELOCITY')
      end

      it 'round-trips FILE body with templated_file_path' do
        original = MockServer::Body.file(
          'responses/{{ request.pathParameters.name.0 }}.json',
          content_type: 'application/json',
          template_type: 'MUSTACHE',
          templated_file_path: true
        )
        expect(original.to_h).to eq(
          'type' => 'FILE',
          'contentType' => 'application/json',
          'filePath' => 'responses/{{ request.pathParameters.name.0 }}.json',
          'templateType' => 'MUSTACHE',
          'templatedFilePath' => true
        )
        roundtrip = MockServer.deserialize_body(original.to_h)
        expect(roundtrip.templated_file_path).to be(true)
        expect(roundtrip.to_h).to eq(original.to_h)
      end

      it 'omits templatedFilePath when unset or false' do
        [nil, false].each do |flag|
          body = MockServer::Body.file('/tpl/resp.vm', template_type: 'VELOCITY', templated_file_path: flag)
          expect(body.to_h).not_to have_key('templatedFilePath')
        end
      end

      it '#with_templated_file_path sets the flag fluently' do
        body = MockServer::Body.file('responses/$!request.path.vm', template_type: 'VELOCITY')
        expect(body.with_templated_file_path).to be(body)
        expect(body.to_h['templatedFilePath']).to be(true)
      end
    end
  end

  # -------------------------------------------------------------------
  # Body deserialization (via deserialize_body helper)
  # -------------------------------------------------------------------
  describe 'body deserialization' do
    it 'returns nil for nil' do
      expect(MockServer.deserialize_body(nil)).to be_nil
    end

    it 'returns string as-is' do
      expect(MockServer.deserialize_body('hello')).to eq('hello')
    end

    it 'returns Body for known BODY_TYPES' do
      result = MockServer.deserialize_body({ 'type' => 'STRING', 'string' => 'test' })
      expect(result).to be_a(MockServer::Body)
      expect(result.type).to eq('STRING')
    end

    it 'returns plain hash for unknown types' do
      result = MockServer.deserialize_body({ 'custom' => 'data' })
      expect(result).to be_a(Hash)
      expect(result['custom']).to eq('data')
    end

    it 'returns plain hash when type is not a known BODY_TYPE' do
      result = MockServer.deserialize_body({ 'type' => 'UNKNOWN', 'data' => 'x' })
      expect(result).to be_a(Hash)
    end

    it 'returns Body for FILE type' do
      result = MockServer.deserialize_body({
        'type' => 'FILE',
        'filePath' => '/data/body.json',
        'templateType' => 'VELOCITY'
      })
      expect(result).to be_a(MockServer::Body)
      expect(result.type).to eq('FILE')
      expect(result.file_path).to eq('/data/body.json')
      expect(result.template_type).to eq('VELOCITY')
    end
  end

  # -------------------------------------------------------------------
  # SocketAddress
  # -------------------------------------------------------------------
  describe MockServer::SocketAddress do
    it 'defaults all fields to nil' do
      sa = MockServer::SocketAddress.new
      expect(sa.host).to be_nil
      expect(sa.port).to be_nil
      expect(sa.scheme).to be_nil
    end

    it 'serializes stripping nil values' do
      sa = MockServer::SocketAddress.new(host: 'localhost', port: 8080)
      expect(sa.to_h).to eq({ 'host' => 'localhost', 'port' => 8080 })
    end

    it 'deserializes from hash' do
      sa = MockServer::SocketAddress.from_hash({ 'host' => 'example.com', 'port' => 443, 'scheme' => 'HTTPS' })
      expect(sa.host).to eq('example.com')
      expect(sa.port).to eq(443)
      expect(sa.scheme).to eq('HTTPS')
    end

    it 'returns nil from_hash when data is nil' do
      expect(MockServer::SocketAddress.from_hash(nil)).to be_nil
    end

    it 'round-trips correctly' do
      original = MockServer::SocketAddress.new(host: 'test.com', port: 9090, scheme: 'HTTP')
      roundtrip = MockServer::SocketAddress.from_hash(original.to_h)
      expect(roundtrip.to_h).to eq(original.to_h)
    end
  end

  # -------------------------------------------------------------------
  # Jwt
  # -------------------------------------------------------------------
  describe MockServer::Jwt do
    it 'serializes only non-nil fields' do
      jwt = MockServer::Jwt.new(claims: { 'sub' => 'abc' }, issuer: 'iss')
      expect(jwt.to_h).to eq({ 'claims' => { 'sub' => 'abc' }, 'issuer' => 'iss' })
    end

    it 'omits unset optional fields' do
      jwt = MockServer::Jwt.new(claims: { 'sub' => 'abc' })
      h = jwt.to_h
      expect(h).not_to have_key('issuer')
      expect(h).not_to have_key('audience')
      expect(h).not_to have_key('algorithm')
      expect(h).not_to have_key('header')
      expect(h).not_to have_key('scheme')
    end

    it 'keeps claim values as plain strings including "!" negation and regex' do
      jwt = MockServer::Jwt.new(claims: { 'role' => '!admin', 'email' => '^.+@example.com$' })
      expect(jwt.to_h['claims']).to eq({ 'role' => '!admin', 'email' => '^.+@example.com$' })
    end

    it 'with_claim appends claims fluently' do
      jwt = MockServer::Jwt.new.with_claim('sub', 'user-123').with_claim('role', '!admin')
      expect(jwt.to_h['claims']).to eq({ 'sub' => 'user-123', 'role' => '!admin' })
    end

    it 'round-trips through from_hash' do
      original = MockServer::Jwt.new(
        claims: { 'sub' => 'user-123', 'role' => '!admin' },
        issuer: 'https://issuer.example.com',
        audience: 'my-api',
        algorithm: 'RS256',
        header: 'authorization',
        scheme: 'Bearer'
      )
      roundtrip = MockServer::Jwt.from_hash(original.to_h)
      expect(roundtrip.to_h).to eq(original.to_h)
    end

    it 'returns nil from_hash when data is nil' do
      expect(MockServer::Jwt.from_hash(nil)).to be_nil
    end
  end

  # -------------------------------------------------------------------
  # HttpRequest
  # -------------------------------------------------------------------
  describe MockServer::HttpRequest do
    it 'defaults all fields to nil' do
      req = MockServer::HttpRequest.new
      expect(req.method).to be_nil
      expect(req.path).to be_nil
      expect(req.headers).to be_nil
    end

    describe '.request factory' do
      it 'creates a request with path' do
        req = MockServer::HttpRequest.request(path: '/test')
        expect(req.path).to eq('/test')
      end

      it 'creates a request without path' do
        req = MockServer::HttpRequest.request
        expect(req.path).to be_nil
      end
    end

    describe 'builder methods' do
      it '#with_method returns self' do
        req = MockServer::HttpRequest.new
        result = req.with_method('GET')
        expect(result).to be(req)
        expect(req.method).to eq('GET')
      end

      it '#with_path returns self' do
        req = MockServer::HttpRequest.new
        result = req.with_path('/api')
        expect(result).to be(req)
        expect(req.path).to eq('/api')
      end

      it '#with_header appends headers and returns self' do
        req = MockServer::HttpRequest.new
        result = req.with_header('Accept', 'application/json')
        expect(result).to be(req)
        expect(req.headers.length).to eq(1)
        expect(req.headers[0].name).to eq('Accept')
        expect(req.headers[0].values).to eq(['application/json'])
      end

      it '#with_header supports multiple values' do
        req = MockServer::HttpRequest.new
        req.with_header('Accept', 'text/html', 'application/json')
        expect(req.headers[0].values).to eq(['text/html', 'application/json'])
      end

      it '#with_query_param appends query params and returns self' do
        req = MockServer::HttpRequest.new
        result = req.with_query_param('page', '1')
        expect(result).to be(req)
        expect(req.query_string_parameters.length).to eq(1)
        expect(req.query_string_parameters[0].name).to eq('page')
      end

      it '#with_cookie appends cookies and returns self' do
        req = MockServer::HttpRequest.new
        result = req.with_cookie('session', 'abc123')
        expect(result).to be(req)
        expect(req.cookies.length).to eq(1)
        expect(req.cookies[0].name).to eq('session')
        expect(req.cookies[0].values).to eq(['abc123'])
      end

      it '#with_body sets body and returns self' do
        req = MockServer::HttpRequest.new
        result = req.with_body('test body')
        expect(result).to be(req)
        expect(req.body).to eq('test body')
      end

      it '#with_secure sets secure and returns self' do
        req = MockServer::HttpRequest.new
        result = req.with_secure(true)
        expect(result).to be(req)
        expect(req.secure).to eq(true)
      end

      it '#with_keep_alive sets keep_alive and returns self' do
        req = MockServer::HttpRequest.new
        result = req.with_keep_alive(true)
        expect(result).to be(req)
        expect(req.keep_alive).to eq(true)
      end

      it '#with_respond_before_body sets respond_before_body and returns self' do
        req = MockServer::HttpRequest.new
        result = req.with_respond_before_body(true)
        expect(result).to be(req)
        expect(req.respond_before_body).to eq(true)
      end
    end

    it 'round-trips clientIp and omits it when unset' do
      req = MockServer::HttpRequest.new(path: '/x').with_client_ip('!10.0.0.0/8')
      expect(req.to_h).to eq({ 'path' => '/x', 'clientIp' => '!10.0.0.0/8' })
      [{ 'path' => '/x', 'clientIp' => '10.0.0.0/8' }, { 'path' => '/x', 'clientIp' => '!2001:db8::/32' }].each do |data|
        expect(MockServer::HttpRequest.from_hash(data).to_h).to eq(data)
      end
      expect(MockServer::HttpRequest.new(path: '/x').to_h).not_to have_key('clientIp')
    end

    it 'serializes respond_before_body as camelCase respondBeforeBody' do
      req = MockServer::HttpRequest.new.with_respond_before_body(true)
      expect(req.to_h).to include('respondBeforeBody' => true)
      expect(req.to_h).not_to have_key('respond_before_body')
    end

    it 'serializes to camelCase hash stripping nils' do
      req = MockServer::HttpRequest.new(method: 'POST', path: '/api')
      h = req.to_h
      expect(h).to eq({ 'method' => 'POST', 'path' => '/api' })
      expect(h).not_to have_key('headers')
    end

    it 'serializes headers as array of hashes' do
      req = MockServer::HttpRequest.new
      req.with_header('Content-Type', 'application/json')
      h = req.to_h
      expect(h['headers']).to eq([{ 'name' => 'Content-Type', 'values' => ['application/json'] }])
    end

    it 'serializes body as string' do
      req = MockServer::HttpRequest.new(body: 'hello')
      expect(req.to_h['body']).to eq('hello')
    end

    it 'serializes Body object' do
      req = MockServer::HttpRequest.new(body: MockServer::Body.string('hello'))
      expect(req.to_h['body']).to eq({ 'type' => 'STRING', 'string' => 'hello' })
    end

    it 'serializes socket_address' do
      sa = MockServer::SocketAddress.new(host: 'localhost', port: 8080)
      req = MockServer::HttpRequest.new(socket_address: sa)
      expect(req.to_h['socketAddress']).to eq({ 'host' => 'localhost', 'port' => 8080 })
    end

    it 'serializes a jwt matcher under the "jwt" key alongside method/path' do
      jwt = MockServer::Jwt.new(
        claims: { 'sub' => 'user-123', 'role' => '!admin', 'email' => '^.+@example.com$' },
        issuer: 'https://issuer.example.com',
        audience: 'my-api',
        algorithm: 'RS256'
      )
      req = MockServer::HttpRequest.new(method: 'GET', path: '/api').with_jwt(jwt)
      expect(req.to_h).to eq({
        'method' => 'GET',
        'path' => '/api',
        'jwt' => {
          'claims' => {
            'sub' => 'user-123',
            'role' => '!admin',
            'email' => '^.+@example.com$'
          },
          'issuer' => 'https://issuer.example.com',
          'audience' => 'my-api',
          'algorithm' => 'RS256'
        }
      })
    end

    it 'omits the jwt key when no jwt matcher is set' do
      req = MockServer::HttpRequest.new(method: 'GET', path: '/api')
      expect(req.to_h).not_to have_key('jwt')
    end

    it 'round-trips a request carrying a jwt matcher' do
      original = MockServer::HttpRequest.new(method: 'GET', path: '/secure').with_jwt(
        MockServer::Jwt.new(claims: { 'sub' => '!blocked' }, header: 'authorization', scheme: 'Bearer')
      )
      roundtrip = MockServer::HttpRequest.from_hash(original.to_h)
      expect(roundtrip.jwt).to be_a(MockServer::Jwt)
      expect(roundtrip.jwt.claims).to eq({ 'sub' => '!blocked' })
      expect(roundtrip.jwt.header).to eq('authorization')
      expect(roundtrip.jwt.scheme).to eq('Bearer')
      expect(roundtrip.to_h).to eq(original.to_h)
    end

    it 'deserializes from hash' do
      data = {
        'method' => 'GET',
        'path' => '/test',
        'headers' => [{ 'name' => 'Accept', 'values' => ['*/*'] }],
        'body' => 'test body',
        'secure' => true,
        'keepAlive' => false,
        'respondBeforeBody' => true,
        'socketAddress' => { 'host' => 'example.com', 'port' => 443 }
      }
      req = MockServer::HttpRequest.from_hash(data)
      expect(req.method).to eq('GET')
      expect(req.path).to eq('/test')
      expect(req.headers.length).to eq(1)
      expect(req.headers[0].name).to eq('Accept')
      expect(req.body).to eq('test body')
      expect(req.secure).to eq(true)
      expect(req.keep_alive).to eq(false)
      expect(req.respond_before_body).to eq(true)
      expect(req.socket_address.host).to eq('example.com')
    end

    it 'returns nil from_hash when data is nil' do
      expect(MockServer::HttpRequest.from_hash(nil)).to be_nil
    end

    it 'deserializes body with known type as Body object' do
      data = {
        'body' => { 'type' => 'JSON', 'json' => { 'key' => 'val' } }
      }
      req = MockServer::HttpRequest.from_hash(data)
      expect(req.body).to be_a(MockServer::Body)
      expect(req.body.type).to eq('JSON')
    end

    it 'round-trips correctly' do
      original = MockServer::HttpRequest.request(path: '/test')
                                        .with_method('POST')
                                        .with_header('X-Test', 'val')
                                        .with_query_param('q', 'search')
                                        .with_body('data')
      roundtrip = MockServer::HttpRequest.from_hash(original.to_h)
      expect(roundtrip.to_h).to eq(original.to_h)
    end
  end

  # -------------------------------------------------------------------
  # ConnectionOptions
  # -------------------------------------------------------------------
  describe MockServer::ConnectionOptions do
    it 'defaults all fields to nil' do
      co = MockServer::ConnectionOptions.new
      expect(co.close_socket).to be_nil
    end

    it 'serializes correctly' do
      co = MockServer::ConnectionOptions.new(
        close_socket: true,
        suppress_content_length_header: true,
        keep_alive_override: false
      )
      h = co.to_h
      expect(h['closeSocket']).to eq(true)
      expect(h['suppressContentLengthHeader']).to eq(true)
      expect(h['keepAliveOverride']).to eq(false)
    end

    it 'serializes close_socket_delay' do
      delay = MockServer::Delay.new(time_unit: 'SECONDS', value: 1)
      co = MockServer::ConnectionOptions.new(close_socket: true, close_socket_delay: delay)
      expect(co.to_h['closeSocketDelay']).to eq({ 'timeUnit' => 'SECONDS', 'value' => 1 })
    end

    it 'deserializes from hash' do
      co = MockServer::ConnectionOptions.from_hash({
        'closeSocket' => true,
        'closeSocketDelay' => { 'timeUnit' => 'SECONDS', 'value' => 2 }
      })
      expect(co.close_socket).to eq(true)
      expect(co.close_socket_delay.time_unit).to eq('SECONDS')
      expect(co.close_socket_delay.value).to eq(2)
    end

    it 'returns nil from_hash when data is nil' do
      expect(MockServer::ConnectionOptions.from_hash(nil)).to be_nil
    end

    it 'round-trips correctly' do
      original = MockServer::ConnectionOptions.new(
        close_socket: true,
        content_length_header_override: 100
      )
      roundtrip = MockServer::ConnectionOptions.from_hash(original.to_h)
      expect(roundtrip.to_h).to eq(original.to_h)
    end
  end

  # -------------------------------------------------------------------
  # HttpResponse
  # -------------------------------------------------------------------
  describe MockServer::HttpResponse do
    it 'defaults all fields to nil' do
      resp = MockServer::HttpResponse.new
      expect(resp.status_code).to be_nil
      expect(resp.headers).to be_nil
    end

    describe '.response factory' do
      it 'sets body and defaults to 200 OK' do
        resp = MockServer::HttpResponse.response(body: 'hello')
        expect(resp.body).to eq('hello')
        expect(resp.status_code).to eq(200)
        expect(resp.reason_phrase).to eq('OK')
      end

      it 'sets body with custom status code' do
        resp = MockServer::HttpResponse.response(body: 'created', status_code: 201)
        expect(resp.body).to eq('created')
        expect(resp.status_code).to eq(201)
        expect(resp.reason_phrase).to be_nil
      end

      it 'sets only status_code when no body' do
        resp = MockServer::HttpResponse.response(status_code: 204)
        expect(resp.body).to be_nil
        expect(resp.status_code).to eq(204)
      end

      it 'creates empty response with no args' do
        resp = MockServer::HttpResponse.response
        expect(resp.body).to be_nil
        expect(resp.status_code).to be_nil
      end
    end

    describe '.not_found_response' do
      it 'creates 404 Not Found' do
        resp = MockServer::HttpResponse.not_found_response
        expect(resp.status_code).to eq(404)
        expect(resp.reason_phrase).to eq('Not Found')
      end
    end

    describe 'builder methods' do
      it '#with_status_code returns self' do
        resp = MockServer::HttpResponse.new
        result = resp.with_status_code(201)
        expect(result).to be(resp)
        expect(resp.status_code).to eq(201)
      end

      it '#with_header returns self' do
        resp = MockServer::HttpResponse.new
        result = resp.with_header('Content-Type', 'text/plain')
        expect(result).to be(resp)
        expect(resp.headers.length).to eq(1)
      end

      it '#with_cookie returns self' do
        resp = MockServer::HttpResponse.new
        result = resp.with_cookie('id', 'abc')
        expect(result).to be(resp)
        expect(resp.cookies.length).to eq(1)
      end

      it '#with_body returns self' do
        resp = MockServer::HttpResponse.new
        result = resp.with_body('test')
        expect(result).to be(resp)
        expect(resp.body).to eq('test')
      end

      it '#with_delay returns self' do
        resp = MockServer::HttpResponse.new
        delay = MockServer::Delay.new(time_unit: 'SECONDS', value: 1)
        result = resp.with_delay(delay)
        expect(result).to be(resp)
        expect(resp.delay).to eq(delay)
      end

      it '#with_reason_phrase returns self' do
        resp = MockServer::HttpResponse.new
        result = resp.with_reason_phrase('Created')
        expect(result).to be(resp)
        expect(resp.reason_phrase).to eq('Created')
      end
    end

    it 'serializes to camelCase hash' do
      resp = MockServer::HttpResponse.new(status_code: 200, reason_phrase: 'OK', body: 'test')
      h = resp.to_h
      expect(h['statusCode']).to eq(200)
      expect(h['reasonPhrase']).to eq('OK')
      expect(h['body']).to eq('test')
    end

    it 'serializes connection_options' do
      co = MockServer::ConnectionOptions.new(close_socket: true)
      resp = MockServer::HttpResponse.new(connection_options: co)
      expect(resp.to_h['connectionOptions']).to eq({ 'closeSocket' => true })
    end

    it 'deserializes from hash' do
      data = {
        'statusCode' => 200,
        'reasonPhrase' => 'OK',
        'body' => 'hello',
        'delay' => { 'timeUnit' => 'MILLISECONDS', 'value' => 100 },
        'connectionOptions' => { 'closeSocket' => false }
      }
      resp = MockServer::HttpResponse.from_hash(data)
      expect(resp.status_code).to eq(200)
      expect(resp.body).to eq('hello')
      expect(resp.delay.value).to eq(100)
      expect(resp.connection_options.close_socket).to eq(false)
    end

    it 'returns nil from_hash when data is nil' do
      expect(MockServer::HttpResponse.from_hash(nil)).to be_nil
    end

    it 'round-trips correctly' do
      original = MockServer::HttpResponse.response(body: 'test', status_code: 200)
                                          .with_header('X-Custom', 'val')
      roundtrip = MockServer::HttpResponse.from_hash(original.to_h)
      expect(roundtrip.to_h).to eq(original.to_h)
    end
  end

  # -------------------------------------------------------------------
  # HttpForward
  # -------------------------------------------------------------------
  describe MockServer::HttpForward do
    it 'defaults all fields to nil' do
      fwd = MockServer::HttpForward.new
      expect(fwd.host).to be_nil
    end

    describe '.forward factory' do
      it 'creates empty HttpForward' do
        fwd = MockServer::HttpForward.forward
        expect(fwd).to be_a(MockServer::HttpForward)
      end
    end

    it 'serializes correctly' do
      fwd = MockServer::HttpForward.new(host: 'backend.local', port: 8080, scheme: 'HTTP')
      expect(fwd.to_h).to eq({
        'host' => 'backend.local',
        'port' => 8080,
        'scheme' => 'HTTP'
      })
    end

    it 'serializes with delay' do
      delay = MockServer::Delay.new(value: 100)
      fwd = MockServer::HttpForward.new(host: 'x', delay: delay)
      expect(fwd.to_h['delay']).to eq({ 'timeUnit' => 'MILLISECONDS', 'value' => 100 })
    end

    it 'deserializes from hash' do
      fwd = MockServer::HttpForward.from_hash({
        'host' => 'test.com',
        'port' => 443,
        'delay' => { 'timeUnit' => 'SECONDS', 'value' => 1 }
      })
      expect(fwd.host).to eq('test.com')
      expect(fwd.delay.time_unit).to eq('SECONDS')
    end

    it 'returns nil from_hash when data is nil' do
      expect(MockServer::HttpForward.from_hash(nil)).to be_nil
    end

    it 'round-trips correctly' do
      original = MockServer::HttpForward.new(host: 'test.com', port: 8080, scheme: 'HTTPS')
      roundtrip = MockServer::HttpForward.from_hash(original.to_h)
      expect(roundtrip.to_h).to eq(original.to_h)
    end
  end

  # -------------------------------------------------------------------
  # HttpTemplate
  # -------------------------------------------------------------------
  describe MockServer::HttpTemplate do
    it 'defaults template_type to JAVASCRIPT' do
      tpl = MockServer::HttpTemplate.new
      expect(tpl.template_type).to eq('JAVASCRIPT')
    end

    describe '.template factory' do
      it 'creates template with type' do
        tpl = MockServer::HttpTemplate.template('VELOCITY', 'return ...')
        expect(tpl.template_type).to eq('VELOCITY')
        expect(tpl.template).to eq('return ...')
      end
    end

    it 'serializes correctly' do
      tpl = MockServer::HttpTemplate.new(template_type: 'JAVASCRIPT', template: 'return {};')
      expect(tpl.to_h).to eq({
        'templateType' => 'JAVASCRIPT',
        'template' => 'return {};'
      })
    end

    it 'deserializes from hash' do
      tpl = MockServer::HttpTemplate.from_hash({
        'templateType' => 'VELOCITY',
        'template' => '#set($x = 1)'
      })
      expect(tpl.template_type).to eq('VELOCITY')
      expect(tpl.template).to eq('#set($x = 1)')
    end

    it 'returns nil from_hash when data is nil' do
      expect(MockServer::HttpTemplate.from_hash(nil)).to be_nil
    end

    it 'round-trips correctly' do
      original = MockServer::HttpTemplate.new(template_type: 'JAVASCRIPT', template: 'code')
      roundtrip = MockServer::HttpTemplate.from_hash(original.to_h)
      expect(roundtrip.to_h).to eq(original.to_h)
    end

    describe 'parameters field' do
      it 'round-trips parameters, keeping nested nils and empty values' do
        parameters = { 'greeting' => 'hello', 'names' => %w[ann bob], 'limits' => { 'max' => 5, 'none' => nil }, 'empty' => '' }
        tpl = MockServer::HttpTemplate.new(template_type: 'MUSTACHE', template: '{{ parameters.greeting }}', parameters: parameters)
        h = tpl.to_h
        expect(h['parameters']).to eq(parameters)
        expect(MockServer::HttpTemplate.from_hash(h).to_h).to eq(h)
      end

      it 'adds parameters with with_parameter' do
        tpl = MockServer::HttpTemplate.template('VELOCITY', '$parameters.a').with_parameter(:a, 1).with_parameter('b', [true])
        expect(tpl.to_h['parameters']).to eq('a' => 1, 'b' => [true])
      end

      it 'omits parameters when unset or empty' do
        expect(MockServer::HttpTemplate.new(template_type: 'VELOCITY', template: 'x').to_h).not_to have_key('parameters')
        expect(MockServer::HttpTemplate.new(template_type: 'VELOCITY', template: 'x', parameters: {}).to_h).not_to have_key('parameters')
      end
    end

    describe 'template_file field' do
      it 'defaults template_file to nil' do
        tpl = MockServer::HttpTemplate.new
        expect(tpl.template_file).to be_nil
      end

      it 'serializes template_file as templateFile' do
        tpl = MockServer::HttpTemplate.new(
          template_type: 'VELOCITY',
          template_file: '/path/to/template.vm'
        )
        h = tpl.to_h
        expect(h['templateFile']).to eq('/path/to/template.vm')
        expect(h['templateType']).to eq('VELOCITY')
      end

      it 'omits templateFile when nil' do
        tpl = MockServer::HttpTemplate.new(template_type: 'JAVASCRIPT', template: 'code')
        expect(tpl.to_h).not_to have_key('templateFile')
      end

      it 'deserializes templateFile from hash' do
        tpl = MockServer::HttpTemplate.from_hash({
          'templateType' => 'MUSTACHE',
          'templateFile' => '/templates/response.mustache'
        })
        expect(tpl.template_type).to eq('MUSTACHE')
        expect(tpl.template_file).to eq('/templates/response.mustache')
        expect(tpl.template).to be_nil
      end

      it 'round-trips with template_file' do
        original = MockServer::HttpTemplate.new(
          template_type: 'VELOCITY',
          template_file: '/etc/mockserver/templates/forward.vm'
        )
        roundtrip = MockServer::HttpTemplate.from_hash(original.to_h)
        expect(roundtrip.to_h).to eq(original.to_h)
        expect(roundtrip.template_file).to eq('/etc/mockserver/templates/forward.vm')
      end

      it '.template factory accepts template_file keyword' do
        tpl = MockServer::HttpTemplate.template('MUSTACHE', nil, template_file: '/tpl.mustache')
        expect(tpl.template_type).to eq('MUSTACHE')
        expect(tpl.template).to be_nil
        expect(tpl.template_file).to eq('/tpl.mustache')
      end

      it '#with_template_file sets template_file fluently' do
        tpl = MockServer::HttpTemplate.new(template_type: 'VELOCITY')
        result = tpl.with_template_file('/path/to/file.vm')
        expect(result).to be(tpl)
        expect(tpl.template_file).to eq('/path/to/file.vm')
      end
    end
  end

  # -------------------------------------------------------------------
  # HttpClassCallback
  # -------------------------------------------------------------------
  describe MockServer::HttpClassCallback do
    it 'defaults all fields to nil' do
      cb = MockServer::HttpClassCallback.new
      expect(cb.callback_class).to be_nil
    end

    describe '.callback factory' do
      it 'creates callback with class name' do
        cb = MockServer::HttpClassCallback.callback(callback_class: 'com.example.MyCallback')
        expect(cb.callback_class).to eq('com.example.MyCallback')
      end
    end

    it 'serializes correctly' do
      cb = MockServer::HttpClassCallback.new(callback_class: 'com.test.Cb')
      expect(cb.to_h).to eq({ 'callbackClass' => 'com.test.Cb' })
    end

    it 'deserializes from hash' do
      cb = MockServer::HttpClassCallback.from_hash({ 'callbackClass' => 'com.test.X' })
      expect(cb.callback_class).to eq('com.test.X')
    end

    it 'returns nil from_hash when data is nil' do
      expect(MockServer::HttpClassCallback.from_hash(nil)).to be_nil
    end

    it 'round-trips correctly' do
      original = MockServer::HttpClassCallback.callback(callback_class: 'com.test.Y')
      roundtrip = MockServer::HttpClassCallback.from_hash(original.to_h)
      expect(roundtrip.to_h).to eq(original.to_h)
    end
  end

  # -------------------------------------------------------------------
  # HttpObjectCallback
  # -------------------------------------------------------------------
  describe MockServer::HttpObjectCallback do
    it 'defaults all fields to nil' do
      cb = MockServer::HttpObjectCallback.new
      expect(cb.client_id).to be_nil
    end

    it 'serializes correctly' do
      cb = MockServer::HttpObjectCallback.new(client_id: 'abc-123', response_callback: true)
      expect(cb.to_h).to eq({ 'clientId' => 'abc-123', 'responseCallback' => true })
    end

    it 'deserializes from hash' do
      cb = MockServer::HttpObjectCallback.from_hash({ 'clientId' => 'x', 'responseCallback' => false })
      expect(cb.client_id).to eq('x')
      expect(cb.response_callback).to eq(false)
    end

    it 'returns nil from_hash when data is nil' do
      expect(MockServer::HttpObjectCallback.from_hash(nil)).to be_nil
    end

    it 'round-trips correctly' do
      original = MockServer::HttpObjectCallback.new(client_id: 'test-id')
      roundtrip = MockServer::HttpObjectCallback.from_hash(original.to_h)
      expect(roundtrip.to_h).to eq(original.to_h)
    end
  end

  # -------------------------------------------------------------------
  # HttpError
  # -------------------------------------------------------------------
  describe MockServer::HttpError do
    it 'defaults all fields to nil' do
      err = MockServer::HttpError.new
      expect(err.drop_connection).to be_nil
    end

    describe '.error factory' do
      it 'creates empty HttpError' do
        err = MockServer::HttpError.error
        expect(err).to be_a(MockServer::HttpError)
      end
    end

    it 'serializes correctly' do
      err = MockServer::HttpError.new(drop_connection: true, response_bytes: 'AAAA')
      expect(err.to_h).to eq({
        'dropConnection' => true,
        'responseBytes' => 'AAAA'
      })
    end

    it 'deserializes from hash' do
      err = MockServer::HttpError.from_hash({ 'dropConnection' => true })
      expect(err.drop_connection).to eq(true)
    end

    it 'returns nil from_hash when data is nil' do
      expect(MockServer::HttpError.from_hash(nil)).to be_nil
    end

    it 'round-trips correctly' do
      original = MockServer::HttpError.new(drop_connection: true, response_bytes: 'XYZ')
      roundtrip = MockServer::HttpError.from_hash(original.to_h)
      expect(roundtrip.to_h).to eq(original.to_h)
    end

    it 'defaults stream_error to nil and omits it from the hash' do
      expect(MockServer::HttpError.new.stream_error).to be_nil
      expect(MockServer::HttpError.new(drop_connection: true).to_h).not_to have_key('streamError')
    end

    it 'serializes stream_error to the streamError key' do
      err = MockServer::HttpError.new(stream_error: 7)
      expect(err.to_h).to eq({ 'streamError' => 7 })
    end

    it 'deserializes streamError from a hash' do
      err = MockServer::HttpError.from_hash({ 'streamError' => 268 })
      expect(err.stream_error).to eq(268)
    end

    it 'round-trips stream_error correctly' do
      original = MockServer::HttpError.new(stream_error: 7)
      roundtrip = MockServer::HttpError.from_hash(original.to_h)
      expect(roundtrip.stream_error).to eq(7)
    end

    it 'round-trips reset_connection to the resetConnection key' do
      err = MockServer::HttpError.new(reset_connection: true)
      expect(err.to_h).to eq({ 'resetConnection' => true })
      expect(MockServer::HttpError.from_hash(err.to_h).reset_connection).to be(true)
      expect(MockServer::HttpError.new(drop_connection: true).to_h).not_to have_key('resetConnection')
    end
  end

  # -------------------------------------------------------------------
  # HttpOverrideForwardedRequest
  # -------------------------------------------------------------------
  describe MockServer::HttpOverrideForwardedRequest do
    it 'defaults all fields to nil' do
      override = MockServer::HttpOverrideForwardedRequest.new
      expect(override.http_request).to be_nil
    end

    describe '.forward_overridden_request factory' do
      it 'creates override with request' do
        req = MockServer::HttpRequest.new(path: '/test')
        override = MockServer::HttpOverrideForwardedRequest.forward_overridden_request(request: req)
        expect(override.http_request.path).to eq('/test')
      end
    end

    it 'serializes correctly' do
      req = MockServer::HttpRequest.new(path: '/test')
      resp = MockServer::HttpResponse.new(status_code: 200)
      override = MockServer::HttpOverrideForwardedRequest.new(
        http_request: req,
        http_response: resp,
        request_modifier: { 'path' => { 'regex' => '/old', 'substitution' => '/new' } }
      )
      h = override.to_h
      expect(h['httpRequest']['path']).to eq('/test')
      expect(h['httpResponse']['statusCode']).to eq(200)
      expect(h['requestModifier']).to be_a(Hash)
    end

    it 'deserializes from hash' do
      data = {
        'httpRequest' => { 'method' => 'GET' },
        'httpResponse' => { 'statusCode' => 201 },
        'requestModifier' => { 'x' => 'y' }
      }
      override = MockServer::HttpOverrideForwardedRequest.from_hash(data)
      expect(override.http_request.method).to eq('GET')
      expect(override.http_response.status_code).to eq(201)
      expect(override.request_modifier).to eq({ 'x' => 'y' })
    end

    it 'returns nil from_hash when data is nil' do
      expect(MockServer::HttpOverrideForwardedRequest.from_hash(nil)).to be_nil
    end

    it 'round-trips correctly' do
      original = MockServer::HttpOverrideForwardedRequest.new(
        http_request: MockServer::HttpRequest.new(path: '/a'),
        response_modifier: { 'test' => true }
      )
      roundtrip = MockServer::HttpOverrideForwardedRequest.from_hash(original.to_h)
      expect(roundtrip.to_h).to eq(original.to_h)
    end
  end

  # -------------------------------------------------------------------
  # HttpRequestAndHttpResponse
  # -------------------------------------------------------------------
  describe MockServer::HttpRequestAndHttpResponse do
    it 'defaults all fields to nil' do
      rr = MockServer::HttpRequestAndHttpResponse.new
      expect(rr.http_request).to be_nil
      expect(rr.http_response).to be_nil
    end

    it 'serializes correctly' do
      req = MockServer::HttpRequest.new(path: '/test')
      resp = MockServer::HttpResponse.new(status_code: 200)
      rr = MockServer::HttpRequestAndHttpResponse.new(http_request: req, http_response: resp)
      h = rr.to_h
      expect(h['httpRequest']['path']).to eq('/test')
      expect(h['httpResponse']['statusCode']).to eq(200)
    end

    it 'deserializes from hash' do
      data = {
        'httpRequest' => { 'path' => '/api' },
        'httpResponse' => { 'statusCode' => 201 }
      }
      rr = MockServer::HttpRequestAndHttpResponse.from_hash(data)
      expect(rr.http_request.path).to eq('/api')
      expect(rr.http_response.status_code).to eq(201)
    end

    it 'returns nil from_hash when data is nil' do
      expect(MockServer::HttpRequestAndHttpResponse.from_hash(nil)).to be_nil
    end

    it 'round-trips correctly' do
      original = MockServer::HttpRequestAndHttpResponse.new(
        http_request: MockServer::HttpRequest.new(path: '/x'),
        http_response: MockServer::HttpResponse.new(status_code: 404)
      )
      roundtrip = MockServer::HttpRequestAndHttpResponse.from_hash(original.to_h)
      expect(roundtrip.to_h).to eq(original.to_h)
    end
  end

  # -------------------------------------------------------------------
  # ExpectationId
  # -------------------------------------------------------------------
  describe MockServer::ExpectationId do
    it 'defaults id to empty string' do
      eid = MockServer::ExpectationId.new
      expect(eid.id).to eq('')
    end

    it 'serializes correctly' do
      eid = MockServer::ExpectationId.new(id: 'test-123')
      expect(eid.to_h).to eq({ 'id' => 'test-123' })
    end

    it 'deserializes from hash' do
      eid = MockServer::ExpectationId.from_hash({ 'id' => 'abc' })
      expect(eid.id).to eq('abc')
    end

    it 'returns nil from_hash when data is nil' do
      expect(MockServer::ExpectationId.from_hash(nil)).to be_nil
    end

    it 'round-trips correctly' do
      original = MockServer::ExpectationId.new(id: 'xyz')
      roundtrip = MockServer::ExpectationId.from_hash(original.to_h)
      expect(roundtrip.to_h).to eq(original.to_h)
    end
  end

  # -------------------------------------------------------------------
  # Expectation
  # -------------------------------------------------------------------
  describe MockServer::Expectation do
    it 'defaults all fields to nil' do
      exp = MockServer::Expectation.new
      expect(exp.id).to be_nil
      expect(exp.http_request).to be_nil
    end

    it 'serializes a complete expectation' do
      exp = MockServer::Expectation.new(
        id: 'test-1',
        priority: 10,
        http_request: MockServer::HttpRequest.new(path: '/api'),
        http_response: MockServer::HttpResponse.new(status_code: 200),
        times: MockServer::Times.exactly(3),
        time_to_live: MockServer::TimeToLive.unlimited
      )
      h = exp.to_h
      expect(h['id']).to eq('test-1')
      expect(h['priority']).to eq(10)
      expect(h['httpRequest']['path']).to eq('/api')
      expect(h['httpResponse']['statusCode']).to eq(200)
      expect(h['times']['remainingTimes']).to eq(3)
      expect(h['timeToLive']['unlimited']).to eq(true)
    end

    it 'serializes all action types' do
      exp = MockServer::Expectation.new(
        http_forward: MockServer::HttpForward.new(host: 'backend'),
        http_error: MockServer::HttpError.new(drop_connection: true),
        http_response_template: MockServer::HttpTemplate.new(template: 'code'),
        http_response_class_callback: MockServer::HttpClassCallback.new(callback_class: 'x'),
        http_response_object_callback: MockServer::HttpObjectCallback.new(client_id: 'y'),
        http_forward_template: MockServer::HttpTemplate.new(template: 'fwd'),
        http_forward_class_callback: MockServer::HttpClassCallback.new(callback_class: 'z'),
        http_forward_object_callback: MockServer::HttpObjectCallback.new(client_id: 'w'),
        http_override_forwarded_request: MockServer::HttpOverrideForwardedRequest.new(
          http_request: MockServer::HttpRequest.new(path: '/override')
        )
      )
      h = exp.to_h
      expect(h['httpForward']['host']).to eq('backend')
      expect(h['httpError']['dropConnection']).to eq(true)
      expect(h['httpResponseTemplate']['template']).to eq('code')
      expect(h['httpResponseClassCallback']['callbackClass']).to eq('x')
      expect(h['httpResponseObjectCallback']['clientId']).to eq('y')
      expect(h['httpForwardTemplate']['template']).to eq('fwd')
      expect(h['httpForwardClassCallback']['callbackClass']).to eq('z')
      expect(h['httpForwardObjectCallback']['clientId']).to eq('w')
      expect(h['httpOverrideForwardedRequest']['httpRequest']['path']).to eq('/override')
    end

    context 'class-callback coercion' do
      it 'serializes httpResponseClassCallback.callbackClass from an HttpClassCallback' do
        exp = MockServer::Expectation.new(
          http_response_class_callback: MockServer::HttpClassCallback.new(callback_class: 'com.example.MyResponseCallback')
        )
        expect(exp.http_response_class_callback).to be_a(MockServer::HttpClassCallback)
        expect(exp.to_h['httpResponseClassCallback']['callbackClass']).to eq('com.example.MyResponseCallback')
      end

      it 'wraps a class-name String passed to the constructor into an HttpClassCallback' do
        exp = MockServer::Expectation.new(http_response_class_callback: 'com.example.MyResponseCallback')
        expect(exp.http_response_class_callback).to be_a(MockServer::HttpClassCallback)
        expect(exp.http_response_class_callback.callback_class).to eq('com.example.MyResponseCallback')
        expect(exp.to_h['httpResponseClassCallback']).to eq({ 'callbackClass' => 'com.example.MyResponseCallback' })
      end

      it 'wraps a class-name String assigned via the setter into an HttpClassCallback' do
        exp = MockServer::Expectation.new
        exp.http_response_class_callback = 'com.example.MyResponseCallback'
        expect(exp.to_h['httpResponseClassCallback']).to eq({ 'callbackClass' => 'com.example.MyResponseCallback' })
      end

      it 'wraps a class-name String for the forward class callback' do
        exp = MockServer::Expectation.new(http_forward_class_callback: 'com.example.MyForwardCallback')
        expect(exp.http_forward_class_callback).to be_a(MockServer::HttpClassCallback)
        expect(exp.to_h['httpForwardClassCallback']).to eq({ 'callbackClass' => 'com.example.MyForwardCallback' })
      end

      it 'preserves delay and primary when given a pre-built HttpClassCallback' do
        exp = MockServer::Expectation.new(
          http_response_class_callback: MockServer::HttpClassCallback.new(
            callback_class: 'com.example.MyResponseCallback',
            delay: MockServer::Delay.new(time_unit: 'SECONDS', value: 2),
            primary: true
          )
        )
        h = exp.to_h['httpResponseClassCallback']
        expect(h['callbackClass']).to eq('com.example.MyResponseCallback')
        expect(h['delay']).to eq({ 'timeUnit' => 'SECONDS', 'value' => 2 })
        expect(h['primary']).to eq(true)
      end

      it 'raises TypeError for an unsupported class-callback type' do
        expect { MockServer::Expectation.new(http_response_class_callback: 123) }
          .to raise_error(TypeError, /class-name String or HttpClassCallback/)
      end
    end

    it 'deserializes from hash' do
      data = {
        'id' => 'exp-1',
        'priority' => 5,
        'httpRequest' => { 'path' => '/test' },
        'httpResponse' => { 'statusCode' => 200 },
        'times' => { 'unlimited' => true },
        'timeToLive' => { 'unlimited' => true }
      }
      exp = MockServer::Expectation.from_hash(data)
      expect(exp.id).to eq('exp-1')
      expect(exp.priority).to eq(5)
      expect(exp.http_request.path).to eq('/test')
      expect(exp.http_response.status_code).to eq(200)
      expect(exp.times.unlimited).to eq(true)
    end

    it 'returns nil from_hash when data is nil' do
      expect(MockServer::Expectation.from_hash(nil)).to be_nil
    end

    it 'round-trips correctly' do
      original = MockServer::Expectation.new(
        id: 'rt-test',
        http_request: MockServer::HttpRequest.new(method: 'GET', path: '/api'),
        http_response: MockServer::HttpResponse.new(status_code: 200, body: 'ok'),
        times: MockServer::Times.exactly(1)
      )
      roundtrip = MockServer::Expectation.from_hash(original.to_h)
      expect(roundtrip.to_h).to eq(original.to_h)
    end
  end

  # -------------------------------------------------------------------
  # SseEvent — falsy value handling
  # -------------------------------------------------------------------
  describe MockServer::SseEvent do
    it 'serializes retry of 0' do
      event = MockServer::SseEvent.new(event: 'ping', retry_ms: 0)
      h = event.to_h
      expect(h).to have_key('retry')
      expect(h['retry']).to eq(0)
    end

    it 'serializes empty string data' do
      event = MockServer::SseEvent.new(data: '')
      h = event.to_h
      expect(h).to have_key('data')
      expect(h['data']).to eq('')
    end

    it 'omits nil fields' do
      event = MockServer::SseEvent.new
      h = event.to_h
      expect(h).not_to have_key('event')
      expect(h).not_to have_key('data')
      expect(h).not_to have_key('id')
      expect(h).not_to have_key('retry')
    end

    it 'round-trips correctly' do
      original = MockServer::SseEvent.new(event: 'msg', data: 'hello', id: '1', retry_ms: 5000)
      roundtrip = MockServer::SseEvent.from_hash(original.to_h)
      expect(roundtrip.to_h).to eq(original.to_h)
    end

    it 'round-trips the end of an SSE stream and omits it when unset' do
      data = { 'events' => [{ 'data' => 'last' }], 'closeDelay' => { 'timeUnit' => 'SECONDS', 'value' => 15 },
               'keepStreamOpen' => true }
      response = MockServer::HttpSseResponse.from_hash(data)
      expect(response.close_delay.value).to eq(15)
      expect(response.keep_stream_open).to be(true)
      expect(response.to_h).to eq(data)
      expect(MockServer::HttpSseResponse.new.to_h).not_to have_key('closeDelay')
      expect(MockServer::HttpSseResponse.new.to_h).not_to have_key('keepStreamOpen')
    end

    it 'round-trips a comment and omits it when unset' do
      event = MockServer::SseEvent.from_hash('comment' => 'heartbeat')
      expect(event.comment).to eq('heartbeat')
      expect(event.to_h).to eq('comment' => 'heartbeat')
      expect(MockServer::SseEvent.new(data: 'd').to_h).not_to have_key('comment')
    end
  end

  # -------------------------------------------------------------------
  # HttpSseResponse — falsy value handling
  # -------------------------------------------------------------------
  describe MockServer::HttpSseResponse do
    it 'serializes status_code of 0' do
      resp = MockServer::HttpSseResponse.new(status_code: 0)
      h = resp.to_h
      expect(h).to have_key('statusCode')
      expect(h['statusCode']).to eq(0)
    end

    it 'serializes close_connection of false' do
      resp = MockServer::HttpSseResponse.new(close_connection: false)
      h = resp.to_h
      expect(h).to have_key('closeConnection')
      expect(h['closeConnection']).to eq(false)
    end

    it 'omits nil fields' do
      resp = MockServer::HttpSseResponse.new
      h = resp.to_h
      expect(h).not_to have_key('statusCode')
      expect(h).not_to have_key('closeConnection')
    end

    it 'round-trips correctly' do
      original = MockServer::HttpSseResponse.new(
        status_code: 200,
        close_connection: true,
        events: [MockServer::SseEvent.new(event: 'test', data: 'data')]
      )
      roundtrip = MockServer::HttpSseResponse.from_hash(original.to_h)
      expect(roundtrip.to_h).to eq(original.to_h)
    end
  end

  # -------------------------------------------------------------------
  # WebSocketMessage — falsy value handling
  # -------------------------------------------------------------------
  describe MockServer::WebSocketMessage do
    it 'serializes empty string text' do
      msg = MockServer::WebSocketMessage.new(text: '')
      h = msg.to_h
      expect(h).to have_key('text')
      expect(h['text']).to eq('')
    end

    it 'omits nil fields' do
      msg = MockServer::WebSocketMessage.new
      h = msg.to_h
      expect(h).not_to have_key('text')
      expect(h).not_to have_key('binary')
    end

    it 'round-trips correctly' do
      original = MockServer::WebSocketMessage.new(text: 'hello')
      roundtrip = MockServer::WebSocketMessage.from_hash(original.to_h)
      expect(roundtrip.to_h).to eq(original.to_h)
    end
  end

  # -------------------------------------------------------------------
  # HttpWebSocketResponse — falsy value handling
  # -------------------------------------------------------------------
  describe MockServer::HttpWebSocketResponse do
    it 'serializes close_connection of false' do
      resp = MockServer::HttpWebSocketResponse.new(close_connection: false)
      h = resp.to_h
      expect(h).to have_key('closeConnection')
      expect(h['closeConnection']).to eq(false)
    end

    it 'serializes empty string subprotocol' do
      resp = MockServer::HttpWebSocketResponse.new(subprotocol: '')
      h = resp.to_h
      expect(h).to have_key('subprotocol')
      expect(h['subprotocol']).to eq('')
    end

    it 'omits nil fields' do
      resp = MockServer::HttpWebSocketResponse.new
      h = resp.to_h
      expect(h).not_to have_key('subprotocol')
      expect(h).not_to have_key('closeConnection')
    end

    it 'round-trips correctly' do
      original = MockServer::HttpWebSocketResponse.new(
        subprotocol: 'graphql-ws',
        close_connection: true,
        messages: [MockServer::WebSocketMessage.new(text: 'hi')]
      )
      roundtrip = MockServer::HttpWebSocketResponse.from_hash(original.to_h)
      expect(roundtrip.to_h).to eq(original.to_h)
    end
  end

  # -------------------------------------------------------------------
  # OpenAPIDefinition
  # -------------------------------------------------------------------
  describe MockServer::OpenAPIDefinition do
    it 'defaults all fields to nil' do
      oad = MockServer::OpenAPIDefinition.new
      expect(oad.spec_url_or_payload).to be_nil
    end

    it 'serializes correctly' do
      oad = MockServer::OpenAPIDefinition.new(
        spec_url_or_payload: 'https://example.com/api.yaml',
        operation_id: 'getUser'
      )
      expect(oad.to_h).to eq({
        'specUrlOrPayload' => 'https://example.com/api.yaml',
        'operationId' => 'getUser'
      })
    end

    it 'deserializes from hash' do
      oad = MockServer::OpenAPIDefinition.from_hash({
        'specUrlOrPayload' => 'spec.yaml',
        'operationId' => 'createUser'
      })
      expect(oad.spec_url_or_payload).to eq('spec.yaml')
      expect(oad.operation_id).to eq('createUser')
    end

    it 'returns nil from_hash when data is nil' do
      expect(MockServer::OpenAPIDefinition.from_hash(nil)).to be_nil
    end

    it 'round-trips correctly' do
      original = MockServer::OpenAPIDefinition.new(
        spec_url_or_payload: 'http://spec.io',
        operation_id: 'op'
      )
      roundtrip = MockServer::OpenAPIDefinition.from_hash(original.to_h)
      expect(roundtrip.to_h).to eq(original.to_h)
    end
  end

  # -------------------------------------------------------------------
  # OpenAPIExpectation
  # -------------------------------------------------------------------
  describe MockServer::OpenAPIExpectation do
    it 'defaults all fields to nil' do
      oae = MockServer::OpenAPIExpectation.new
      expect(oae.spec_url_or_payload).to be_nil
    end

    it 'serializes correctly' do
      oae = MockServer::OpenAPIExpectation.new(
        spec_url_or_payload: 'http://spec.io',
        operations_and_responses: { 'getUser' => '200' }
      )
      expect(oae.to_h).to eq({
        'specUrlOrPayload' => 'http://spec.io',
        'operationsAndResponses' => { 'getUser' => '200' }
      })
    end

    it 'deserializes from hash' do
      oae = MockServer::OpenAPIExpectation.from_hash({
        'specUrlOrPayload' => 'spec.yaml',
        'operationsAndResponses' => { 'op1' => '201' }
      })
      expect(oae.spec_url_or_payload).to eq('spec.yaml')
      expect(oae.operations_and_responses).to eq({ 'op1' => '201' })
    end

    it 'returns nil from_hash when data is nil' do
      expect(MockServer::OpenAPIExpectation.from_hash(nil)).to be_nil
    end

    it 'round-trips correctly' do
      original = MockServer::OpenAPIExpectation.new(
        spec_url_or_payload: 'test.yaml',
        operations_and_responses: { 'a' => '200', 'b' => '404' }
      )
      roundtrip = MockServer::OpenAPIExpectation.from_hash(original.to_h)
      expect(roundtrip.to_h).to eq(original.to_h)
    end
  end

  # -------------------------------------------------------------------
  # VerificationTimes
  # -------------------------------------------------------------------
  describe MockServer::VerificationTimes do
    it 'defaults all fields to nil' do
      vt = MockServer::VerificationTimes.new
      expect(vt.at_least).to be_nil
      expect(vt.at_most).to be_nil
    end

    it 'serializes correctly' do
      vt = MockServer::VerificationTimes.new(at_least: 1, at_most: 5)
      expect(vt.to_h).to eq({ 'atLeast' => 1, 'atMost' => 5 })
    end

    describe 'factory methods' do
      it '.at_least creates VerificationTimes' do
        vt = MockServer::VerificationTimes.at_least(2)
        expect(vt.at_least).to eq(2)
        expect(vt.at_most).to be_nil
      end

      it '.at_most creates VerificationTimes' do
        vt = MockServer::VerificationTimes.at_most(5)
        expect(vt.at_most).to eq(5)
        expect(vt.at_least).to be_nil
      end

      it '.exactly creates VerificationTimes' do
        vt = MockServer::VerificationTimes.exactly(3)
        expect(vt.at_least).to eq(3)
        expect(vt.at_most).to eq(3)
      end

      it '.once creates VerificationTimes' do
        vt = MockServer::VerificationTimes.once
        expect(vt.at_least).to eq(1)
        expect(vt.at_most).to eq(1)
      end

      it '.between creates VerificationTimes' do
        vt = MockServer::VerificationTimes.between(2, 5)
        expect(vt.at_least).to eq(2)
        expect(vt.at_most).to eq(5)
      end
    end

    it 'deserializes from hash' do
      vt = MockServer::VerificationTimes.from_hash({ 'atLeast' => 1, 'atMost' => 3 })
      expect(vt.at_least).to eq(1)
      expect(vt.at_most).to eq(3)
    end

    it 'returns nil from_hash when data is nil' do
      expect(MockServer::VerificationTimes.from_hash(nil)).to be_nil
    end

    it 'round-trips correctly' do
      original = MockServer::VerificationTimes.between(2, 10)
      roundtrip = MockServer::VerificationTimes.from_hash(original.to_h)
      expect(roundtrip.to_h).to eq(original.to_h)
    end
  end

  # -------------------------------------------------------------------
  # Verification
  # -------------------------------------------------------------------
  describe MockServer::Verification do
    it 'defaults all fields to nil' do
      v = MockServer::Verification.new
      expect(v.http_request).to be_nil
    end

    it 'serializes correctly' do
      v = MockServer::Verification.new(
        http_request: MockServer::HttpRequest.new(path: '/test'),
        times: MockServer::VerificationTimes.at_least(1),
        maximum_number_of_request_to_return_in_verification_failure: 10
      )
      h = v.to_h
      expect(h['httpRequest']['path']).to eq('/test')
      expect(h['times']['atLeast']).to eq(1)
      expect(h['maximumNumberOfRequestToReturnInVerificationFailure']).to eq(10)
    end

    it 'serializes with expectation_id' do
      v = MockServer::Verification.new(
        expectation_id: MockServer::ExpectationId.new(id: 'exp-1')
      )
      expect(v.to_h['expectationId']).to eq({ 'id' => 'exp-1' })
    end

    it 'serializes with http_response' do
      v = MockServer::Verification.new(
        http_request: MockServer::HttpRequest.new(path: '/test'),
        http_response: MockServer::HttpResponse.new(status_code: 200)
      )
      h = v.to_h
      expect(h['httpRequest']['path']).to eq('/test')
      expect(h['httpResponse']['statusCode']).to eq(200)
    end

    it 'omits httpResponse when nil' do
      v = MockServer::Verification.new(
        http_request: MockServer::HttpRequest.new(path: '/test')
      )
      expect(v.to_h).not_to have_key('httpResponse')
    end

    it 'serializes response-only verification (no request)' do
      v = MockServer::Verification.new(
        http_response: MockServer::HttpResponse.new(status_code: 201)
      )
      h = v.to_h
      expect(h).not_to have_key('httpRequest')
      expect(h['httpResponse']['statusCode']).to eq(201)
    end

    it 'deserializes from hash' do
      data = {
        'httpRequest' => { 'path' => '/api' },
        'times' => { 'atLeast' => 2 },
        'expectationId' => { 'id' => 'x' }
      }
      v = MockServer::Verification.from_hash(data)
      expect(v.http_request.path).to eq('/api')
      expect(v.times.at_least).to eq(2)
      expect(v.expectation_id.id).to eq('x')
    end

    it 'deserializes http_response from hash' do
      data = {
        'httpRequest' => { 'path' => '/api' },
        'httpResponse' => { 'statusCode' => 200, 'reasonPhrase' => 'OK' },
        'times' => { 'atLeast' => 1 }
      }
      v = MockServer::Verification.from_hash(data)
      expect(v.http_request.path).to eq('/api')
      expect(v.http_response).not_to be_nil
      expect(v.http_response.status_code).to eq(200)
      expect(v.http_response.reason_phrase).to eq('OK')
    end

    it 'returns nil from_hash when data is nil' do
      expect(MockServer::Verification.from_hash(nil)).to be_nil
    end

    it 'round-trips correctly' do
      original = MockServer::Verification.new(
        http_request: MockServer::HttpRequest.new(path: '/x'),
        times: MockServer::VerificationTimes.once
      )
      roundtrip = MockServer::Verification.from_hash(original.to_h)
      expect(roundtrip.to_h).to eq(original.to_h)
    end

    it 'round-trips with http_response' do
      original = MockServer::Verification.new(
        http_request: MockServer::HttpRequest.new(path: '/x'),
        http_response: MockServer::HttpResponse.new(status_code: 200),
        times: MockServer::VerificationTimes.at_least(1)
      )
      roundtrip = MockServer::Verification.from_hash(original.to_h)
      expect(roundtrip.to_h).to eq(original.to_h)
    end
  end

  # -------------------------------------------------------------------
  # VerificationSequence
  # -------------------------------------------------------------------
  describe MockServer::VerificationSequence do
    it 'defaults all fields to nil' do
      vs = MockServer::VerificationSequence.new
      expect(vs.http_requests).to be_nil
      expect(vs.expectation_ids).to be_nil
    end

    it 'serializes http_requests' do
      vs = MockServer::VerificationSequence.new(
        http_requests: [
          MockServer::HttpRequest.new(path: '/a'),
          MockServer::HttpRequest.new(path: '/b')
        ]
      )
      h = vs.to_h
      expect(h['httpRequests'].length).to eq(2)
      expect(h['httpRequests'][0]['path']).to eq('/a')
    end

    it 'serializes expectation_ids' do
      vs = MockServer::VerificationSequence.new(
        expectation_ids: [
          MockServer::ExpectationId.new(id: 'e1'),
          MockServer::ExpectationId.new(id: 'e2')
        ]
      )
      h = vs.to_h
      expect(h['expectationIds'].length).to eq(2)
    end

    it 'serializes http_responses' do
      vs = MockServer::VerificationSequence.new(
        http_requests: [
          MockServer::HttpRequest.new(path: '/a'),
          MockServer::HttpRequest.new(path: '/b')
        ],
        http_responses: [
          MockServer::HttpResponse.new(status_code: 200),
          MockServer::HttpResponse.new(status_code: 201)
        ]
      )
      h = vs.to_h
      expect(h['httpRequests'].length).to eq(2)
      expect(h['httpResponses'].length).to eq(2)
      expect(h['httpResponses'][0]['statusCode']).to eq(200)
      expect(h['httpResponses'][1]['statusCode']).to eq(201)
    end

    it 'omits httpResponses when nil' do
      vs = MockServer::VerificationSequence.new(
        http_requests: [MockServer::HttpRequest.new(path: '/a')]
      )
      expect(vs.to_h).not_to have_key('httpResponses')
    end

    it 'deserializes from hash' do
      data = {
        'httpRequests' => [{ 'path' => '/x' }, { 'path' => '/y' }],
        'expectationIds' => [{ 'id' => 'a' }]
      }
      vs = MockServer::VerificationSequence.from_hash(data)
      expect(vs.http_requests.length).to eq(2)
      expect(vs.expectation_ids.length).to eq(1)
    end

    it 'deserializes http_responses from hash' do
      data = {
        'httpRequests' => [{ 'path' => '/a' }, { 'path' => '/b' }],
        'httpResponses' => [{ 'statusCode' => 200 }, { 'statusCode' => 404 }]
      }
      vs = MockServer::VerificationSequence.from_hash(data)
      expect(vs.http_requests.length).to eq(2)
      expect(vs.http_responses.length).to eq(2)
      expect(vs.http_responses[0].status_code).to eq(200)
      expect(vs.http_responses[1].status_code).to eq(404)
    end

    it 'returns nil from_hash when data is nil' do
      expect(MockServer::VerificationSequence.from_hash(nil)).to be_nil
    end

    it 'round-trips correctly' do
      original = MockServer::VerificationSequence.new(
        http_requests: [MockServer::HttpRequest.new(path: '/test')]
      )
      roundtrip = MockServer::VerificationSequence.from_hash(original.to_h)
      expect(roundtrip.to_h).to eq(original.to_h)
    end

    it 'round-trips with http_responses' do
      original = MockServer::VerificationSequence.new(
        http_requests: [
          MockServer::HttpRequest.new(path: '/a'),
          MockServer::HttpRequest.new(path: '/b')
        ],
        http_responses: [
          MockServer::HttpResponse.new(status_code: 200),
          MockServer::HttpResponse.new(status_code: 201)
        ]
      )
      roundtrip = MockServer::VerificationSequence.from_hash(original.to_h)
      expect(roundtrip.to_h).to eq(original.to_h)
    end
  end

  # -------------------------------------------------------------------
  # Ports
  # -------------------------------------------------------------------
  describe MockServer::Ports do
    it 'defaults to empty ports array' do
      ports = MockServer::Ports.new
      expect(ports.ports).to eq([])
    end

    it 'serializes correctly' do
      ports = MockServer::Ports.new(ports: [1080, 1081])
      expect(ports.to_h).to eq({ 'ports' => [1080, 1081] })
    end

    it 'deserializes from hash' do
      ports = MockServer::Ports.from_hash({ 'ports' => [8080] })
      expect(ports.ports).to eq([8080])
    end

    it 'returns nil from_hash when data is nil' do
      expect(MockServer::Ports.from_hash(nil)).to be_nil
    end

    it 'round-trips correctly' do
      original = MockServer::Ports.new(ports: [1080, 1081, 1082])
      roundtrip = MockServer::Ports.from_hash(original.to_h)
      expect(roundtrip.to_h).to eq(original.to_h)
    end
  end

  # -------------------------------------------------------------------
  # RequestDefinition alias
  # -------------------------------------------------------------------
  describe 'RequestDefinition alias' do
    it 'is the same as HttpRequest' do
      expect(MockServer::RequestDefinition).to eq(MockServer::HttpRequest)
    end
  end

  # -------------------------------------------------------------------
  # HttpChaosProfile
  # -------------------------------------------------------------------
  describe MockServer::HttpChaosProfile do
    it 'has nil defaults' do
      chaos = MockServer::HttpChaosProfile.new
      expect(chaos.error_status).to be_nil
      expect(chaos.error_probability).to be_nil
      expect(chaos.drop_connection_probability).to be_nil
      expect(chaos.retry_after).to be_nil
      expect(chaos.latency).to be_nil
      expect(chaos.seed).to be_nil
      expect(chaos.succeed_first).to be_nil
      expect(chaos.fail_request_count).to be_nil
    end

    it 'constructs with all seven fields' do
      chaos = MockServer::HttpChaosProfile.new(
        error_status: 503,
        error_probability: 0.5,
        retry_after: '30',
        latency: MockServer::Delay.new(time_unit: 'MILLISECONDS', value: 200),
        seed: 42,
        succeed_first: 3,
        fail_request_count: 5
      )
      expect(chaos.error_status).to eq(503)
      expect(chaos.error_probability).to eq(0.5)
      expect(chaos.retry_after).to eq('30')
      expect(chaos.latency.time_unit).to eq('MILLISECONDS')
      expect(chaos.latency.value).to eq(200)
      expect(chaos.seed).to eq(42)
      expect(chaos.succeed_first).to eq(3)
      expect(chaos.fail_request_count).to eq(5)
    end

    it 'serializes all fields to camelCase hash' do
      chaos = MockServer::HttpChaosProfile.new(
        error_status: 429,
        error_probability: 0.75,
        retry_after: '60',
        latency: MockServer::Delay.new(time_unit: 'SECONDS', value: 2),
        seed: 99,
        succeed_first: 5,
        fail_request_count: 10
      )
      expect(chaos.to_h).to eq({
        'errorStatus' => 429,
        'errorProbability' => 0.75,
        'retryAfter' => '60',
        'latency' => { 'timeUnit' => 'SECONDS', 'value' => 2 },
        'seed' => 99,
        'succeedFirst' => 5,
        'failRequestCount' => 10
      })
    end

    it 'strips nil fields from serialization' do
      chaos = MockServer::HttpChaosProfile.new(error_status: 500)
      h = chaos.to_h
      expect(h).to eq({ 'errorStatus' => 500 })
      expect(h).not_to have_key('errorProbability')
      expect(h).not_to have_key('dropConnectionProbability')
      expect(h).not_to have_key('retryAfter')
      expect(h).not_to have_key('latency')
      expect(h).not_to have_key('seed')
      expect(h).not_to have_key('succeedFirst')
      expect(h).not_to have_key('failRequestCount')
    end

    it 'deserializes all fields from camelCase hash' do
      chaos = MockServer::HttpChaosProfile.from_hash({
        'errorStatus' => 503,
        'errorProbability' => 0.5,
        'retryAfter' => '30',
        'latency' => { 'timeUnit' => 'MILLISECONDS', 'value' => 200 },
        'seed' => 42,
        'succeedFirst' => 3,
        'failRequestCount' => 5
      })
      expect(chaos.error_status).to eq(503)
      expect(chaos.error_probability).to eq(0.5)
      expect(chaos.retry_after).to eq('30')
      expect(chaos.latency.time_unit).to eq('MILLISECONDS')
      expect(chaos.latency.value).to eq(200)
      expect(chaos.seed).to eq(42)
      expect(chaos.succeed_first).to eq(3)
      expect(chaos.fail_request_count).to eq(5)
    end

    it 'returns nil from_hash when data is nil' do
      expect(MockServer::HttpChaosProfile.from_hash(nil)).to be_nil
    end

    it 'deserializes partial hash' do
      chaos = MockServer::HttpChaosProfile.from_hash({ 'errorStatus' => 500, 'errorProbability' => 1.0 })
      expect(chaos.error_status).to eq(500)
      expect(chaos.error_probability).to eq(1.0)
      expect(chaos.drop_connection_probability).to be_nil
      expect(chaos.retry_after).to be_nil
      expect(chaos.latency).to be_nil
      expect(chaos.seed).to be_nil
      expect(chaos.succeed_first).to be_nil
      expect(chaos.fail_request_count).to be_nil
    end

    it 'serializes and deserializes drop_connection_probability' do
      chaos = MockServer::HttpChaosProfile.new(drop_connection_probability: 0.75)
      h = chaos.to_h
      expect(h).to eq({ 'dropConnectionProbability' => 0.75 })
      restored = MockServer::HttpChaosProfile.from_hash(h)
      expect(restored.drop_connection_probability).to eq(0.75)
    end

    it 'serializes and deserializes the time-based outage window' do
      chaos = MockServer::HttpChaosProfile.new(outage_after_millis: 5000, outage_duration_millis: 10000)
      h = chaos.to_h
      expect(h).to eq({ 'outageAfterMillis' => 5000, 'outageDurationMillis' => 10000 })
      restored = MockServer::HttpChaosProfile.from_hash(h)
      expect(restored.outage_after_millis).to eq(5000)
      expect(restored.outage_duration_millis).to eq(10000)
    end

    it 'serializes and deserializes body-corruption faults' do
      chaos = MockServer::HttpChaosProfile.new(truncate_body_at_fraction: 0.25, malformed_body: true)
      h = chaos.to_h
      expect(h).to eq({ 'truncateBodyAtFraction' => 0.25, 'malformedBody' => true })
      restored = MockServer::HttpChaosProfile.from_hash(h)
      expect(restored.truncate_body_at_fraction).to eq(0.25)
      expect(restored.malformed_body).to be(true)
    end

    it 'serializes and deserializes the slow-response dribble' do
      chaos = MockServer::HttpChaosProfile.new(
        slow_response_chunk_size: 8,
        slow_response_chunk_delay: MockServer::Delay.new(time_unit: 'MILLISECONDS', value: 250)
      )
      h = chaos.to_h
      expect(h).to eq({ 'slowResponseChunkSize' => 8, 'slowResponseChunkDelay' => { 'timeUnit' => 'MILLISECONDS', 'value' => 250 } })
      restored = MockServer::HttpChaosProfile.from_hash(h)
      expect(restored.slow_response_chunk_size).to eq(8)
      expect(restored.slow_response_chunk_delay.time_unit).to eq('MILLISECONDS')
      expect(restored.slow_response_chunk_delay.value).to eq(250)
    end

    it 'serializes and deserializes the stateful request quota' do
      chaos = MockServer::HttpChaosProfile.new(
        quota_name: 'acct', quota_limit: 4, quota_window_millis: 60000, quota_error_status: 429
      )
      h = chaos.to_h
      expect(h).to eq({ 'quotaName' => 'acct', 'quotaLimit' => 4, 'quotaWindowMillis' => 60000, 'quotaErrorStatus' => 429 })
      restored = MockServer::HttpChaosProfile.from_hash(h)
      expect(restored.quota_name).to eq('acct')
      expect(restored.quota_limit).to eq(4)
      expect(restored.quota_window_millis).to eq(60000)
      expect(restored.quota_error_status).to eq(429)
    end

    it 'serializes and deserializes the gradual-degradation ramp' do
      chaos = MockServer::HttpChaosProfile.new(error_probability: 1.0, degradation_ramp_millis: 30000)
      h = chaos.to_h
      expect(h).to eq({ 'errorProbability' => 1.0, 'degradationRampMillis' => 30000 })
      restored = MockServer::HttpChaosProfile.from_hash(h)
      expect(restored.degradation_ramp_millis).to eq(30000)
    end

    it 'round-trips correctly' do
      original = MockServer::HttpChaosProfile.new(
        error_status: 503,
        error_probability: 0.5,
        retry_after: '30',
        latency: MockServer::Delay.new(time_unit: 'MILLISECONDS', value: 200),
        seed: 42,
        succeed_first: 3,
        fail_request_count: 5
      )
      roundtrip = MockServer::HttpChaosProfile.from_hash(original.to_h)
      expect(roundtrip.to_h).to eq(original.to_h)
    end
  end

  # -------------------------------------------------------------------
  # Expectation with chaos
  # -------------------------------------------------------------------
  describe 'MockServer::Expectation with chaos' do
    it 'serializes chaos into expectation' do
      exp = MockServer::Expectation.new(
        http_request: MockServer::HttpRequest.new(path: '/chaos'),
        http_response: MockServer::HttpResponse.new(status_code: 200),
        chaos: MockServer::HttpChaosProfile.new(error_status: 503, error_probability: 0.5)
      )
      h = exp.to_h
      expect(h['chaos']).to eq({ 'errorStatus' => 503, 'errorProbability' => 0.5 })
    end

    it 'deserializes chaos from hash' do
      exp = MockServer::Expectation.from_hash({
        'httpRequest' => { 'path' => '/chaos' },
        'httpResponse' => { 'statusCode' => 200 },
        'chaos' => {
          'errorStatus' => 503,
          'errorProbability' => 0.5,
          'retryAfter' => '30',
          'latency' => { 'timeUnit' => 'MILLISECONDS', 'value' => 200 },
          'seed' => 42,
          'succeedFirst' => 3,
          'failRequestCount' => 5
        }
      })
      expect(exp.chaos).not_to be_nil
      expect(exp.chaos.error_status).to eq(503)
      expect(exp.chaos.error_probability).to eq(0.5)
      expect(exp.chaos.retry_after).to eq('30')
      expect(exp.chaos.latency.time_unit).to eq('MILLISECONDS')
      expect(exp.chaos.latency.value).to eq(200)
      expect(exp.chaos.seed).to eq(42)
      expect(exp.chaos.succeed_first).to eq(3)
      expect(exp.chaos.fail_request_count).to eq(5)
    end

    it 'round-trips expectation with chaos' do
      original = MockServer::Expectation.new(
        http_request: MockServer::HttpRequest.new(path: '/chaos'),
        http_response: MockServer::HttpResponse.new(status_code: 200),
        chaos: MockServer::HttpChaosProfile.new(
          error_status: 503,
          error_probability: 0.5,
          retry_after: '30',
          latency: MockServer::Delay.new(time_unit: 'MILLISECONDS', value: 200),
          seed: 42,
          succeed_first: 3,
          fail_request_count: 5
        )
      )
      roundtrip = MockServer::Expectation.from_hash(original.to_h)
      expect(roundtrip.to_h).to eq(original.to_h)
    end
  end

  # -------------------------------------------------------------------
  # GrpcStreamMessage
  # -------------------------------------------------------------------
  describe MockServer::GrpcStreamMessage do
    it 'has nil defaults' do
      msg = MockServer::GrpcStreamMessage.new
      expect(msg.json).to be_nil
      expect(msg.delay).to be_nil
    end

    it 'serializes to hash' do
      msg = MockServer::GrpcStreamMessage.new(json: '{"id": 1}')
      expect(msg.to_h).to eq({ 'json' => '{"id": 1}' })
    end

    it 'serializes with delay' do
      msg = MockServer::GrpcStreamMessage.new(
        json: '{"id": 1}',
        delay: MockServer::Delay.new(time_unit: 'MILLISECONDS', value: 500)
      )
      expect(msg.to_h).to eq({
        'json' => '{"id": 1}',
        'delay' => { 'timeUnit' => 'MILLISECONDS', 'value' => 500 }
      })
    end

    it 'deserializes from hash' do
      msg = MockServer::GrpcStreamMessage.from_hash({
        'json' => '{"id": 2}',
        'delay' => { 'timeUnit' => 'SECONDS', 'value' => 1 }
      })
      expect(msg.json).to eq('{"id": 2}')
      expect(msg.delay.time_unit).to eq('SECONDS')
    end

    it 'returns nil from_hash when data is nil' do
      expect(MockServer::GrpcStreamMessage.from_hash(nil)).to be_nil
    end

    it 'round-trips correctly' do
      original = MockServer::GrpcStreamMessage.new(
        json: '{"test": true}',
        delay: MockServer::Delay.new(time_unit: 'SECONDS', value: 2)
      )
      roundtrip = MockServer::GrpcStreamMessage.from_hash(original.to_h)
      expect(roundtrip.to_h).to eq(original.to_h)
    end
  end

  # -------------------------------------------------------------------
  # GrpcStreamResponse
  # -------------------------------------------------------------------
  describe MockServer::GrpcStreamResponse do
    it 'has nil defaults' do
      resp = MockServer::GrpcStreamResponse.new
      expect(resp.status_name).to be_nil
      expect(resp.status_message).to be_nil
      expect(resp.headers).to be_nil
      expect(resp.messages).to be_nil
      expect(resp.close_connection).to be_nil
    end

    it 'serializes to hash' do
      resp = MockServer::GrpcStreamResponse.new(
        status_name: 'OK',
        messages: [
          MockServer::GrpcStreamMessage.new(json: '{"id": 1}'),
          MockServer::GrpcStreamMessage.new(
            json: '{"id": 2}',
            delay: MockServer::Delay.new(time_unit: 'MILLISECONDS', value: 100)
          )
        ],
        close_connection: true
      )
      result = resp.to_h
      expect(result['statusName']).to eq('OK')
      expect(result['closeConnection']).to be(true)
      expect(result['messages'].length).to eq(2)
      expect(result['messages'][0]).to eq({ 'json' => '{"id": 1}' })
    end

    it 'deserializes from hash' do
      resp = MockServer::GrpcStreamResponse.from_hash({
        'statusName' => 'OK',
        'statusMessage' => 'Done',
        'messages' => [
          { 'json' => '{"id": 1}' },
          { 'json' => '{"id": 2}', 'delay' => { 'timeUnit' => 'SECONDS', 'value' => 1 } }
        ],
        'closeConnection' => true
      })
      expect(resp.status_name).to eq('OK')
      expect(resp.status_message).to eq('Done')
      expect(resp.messages.length).to eq(2)
      expect(resp.messages[1].delay.time_unit).to eq('SECONDS')
      expect(resp.close_connection).to be(true)
    end

    it 'returns nil from_hash when data is nil' do
      expect(MockServer::GrpcStreamResponse.from_hash(nil)).to be_nil
    end

    it 'round-trips correctly' do
      original = MockServer::GrpcStreamResponse.new(
        status_name: 'OK',
        messages: [MockServer::GrpcStreamMessage.new(json: '{"r": 1}')],
        close_connection: false,
        delay: MockServer::Delay.new(time_unit: 'SECONDS', value: 1)
      )
      roundtrip = MockServer::GrpcStreamResponse.from_hash(original.to_h)
      expect(roundtrip.to_h).to eq(original.to_h)
    end

    it 'serializes under grpcStreamResponse key in expectation' do
      exp = MockServer::Expectation.new(
        http_request: MockServer::HttpRequest.new(path: '/grpc'),
        grpc_stream_response: MockServer::GrpcStreamResponse.new(
          status_name: 'OK',
          messages: [MockServer::GrpcStreamMessage.new(json: '{"id": 1}')]
        )
      )
      h = exp.to_h
      expect(h).to have_key('grpcStreamResponse')
      expect(h['grpcStreamResponse']['statusName']).to eq('OK')
      expect(h).not_to have_key('httpResponse')
    end

    it 'deserializes from grpcStreamResponse key in expectation' do
      exp = MockServer::Expectation.from_hash({
        'httpRequest' => { 'path' => '/grpc' },
        'grpcStreamResponse' => {
          'statusName' => 'OK',
          'messages' => [{ 'json' => '{"id": 1}' }]
        }
      })
      expect(exp.grpc_stream_response).not_to be_nil
      expect(exp.grpc_stream_response.status_name).to eq('OK')
    end
  end

  # -------------------------------------------------------------------
  # GrpcBidiRule
  # -------------------------------------------------------------------
  describe MockServer::GrpcBidiRule do
    it 'has nil defaults' do
      rule = MockServer::GrpcBidiRule.new
      expect(rule.match_json).to be_nil
      expect(rule.responses).to be_nil
    end

    it 'round-trips through to_h / from_hash' do
      rule = MockServer::GrpcBidiRule.new(
        match_json: '{"name": "Alice"}',
        responses: [MockServer::GrpcStreamMessage.new(json: '{"greeting": "Hello Alice"}')]
      )
      h = rule.to_h
      expect(h['matchJson']).to eq('{"name": "Alice"}')
      expect(h['responses'].length).to eq(1)
      restored = MockServer::GrpcBidiRule.from_hash(h)
      expect(restored.to_h).to eq(h)
    end

    it 'returns nil from_hash when data is nil' do
      expect(MockServer::GrpcBidiRule.from_hash(nil)).to be_nil
    end
  end

  # -------------------------------------------------------------------
  # GrpcBidiResponse
  # -------------------------------------------------------------------
  describe MockServer::GrpcBidiResponse do
    it 'has nil defaults' do
      resp = MockServer::GrpcBidiResponse.new
      expect(resp.status_name).to be_nil
      expect(resp.status_message).to be_nil
      expect(resp.headers).to be_nil
      expect(resp.messages).to be_nil
      expect(resp.rules).to be_nil
      expect(resp.close_connection).to be_nil
    end

    it 'round-trips through to_h / from_hash' do
      original = MockServer::GrpcBidiResponse.new(
        status_name: 'OK',
        messages: [MockServer::GrpcStreamMessage.new(json: '{"greeting": "Hello"}')],
        rules: [
          MockServer::GrpcBidiRule.new(
            match_json: '{"name": "Bob"}',
            responses: [MockServer::GrpcStreamMessage.new(json: '{"greeting": "Hello Bob"}')]
          )
        ],
        close_connection: false
      )
      h = original.to_h
      expect(h['statusName']).to eq('OK')
      expect(h['messages'].length).to eq(1)
      expect(h['rules'].length).to eq(1)
      expect(h['rules'][0]['matchJson']).to eq('{"name": "Bob"}')
      restored = MockServer::GrpcBidiResponse.from_hash(h)
      expect(restored.to_h).to eq(h)
    end

    it 'returns nil from_hash when data is nil' do
      expect(MockServer::GrpcBidiResponse.from_hash(nil)).to be_nil
    end

    it 'serializes under grpcBidiResponse key in expectation' do
      exp = MockServer::Expectation.new(
        http_request: MockServer::HttpRequest.new(path: '/grpc'),
        grpc_bidi_response: MockServer::GrpcBidiResponse.new(
          status_name: 'OK',
          messages: [MockServer::GrpcStreamMessage.new(json: '{"id": 1}')]
        )
      )
      h = exp.to_h
      expect(h).to have_key('grpcBidiResponse')
      expect(h['grpcBidiResponse']['statusName']).to eq('OK')
      expect(h).not_to have_key('httpResponse')
    end

    it 'deserializes from grpcBidiResponse key in expectation' do
      exp = MockServer::Expectation.from_hash({
        'httpRequest' => { 'path' => '/grpc' },
        'grpcBidiResponse' => {
          'statusName' => 'OK',
          'messages' => [{ 'json' => '{"id": 1}' }],
          'rules' => [
            { 'matchJson' => '{"name": "test"}', 'responses' => [{ 'json' => '{"reply": "ok"}' }] }
          ]
        }
      })
      expect(exp.grpc_bidi_response).not_to be_nil
      expect(exp.grpc_bidi_response.status_name).to eq('OK')
      expect(exp.grpc_bidi_response.rules.length).to eq(1)
      expect(exp.grpc_bidi_response.rules[0].match_json).to eq('{"name": "test"}')
    end
  end

  # -------------------------------------------------------------------
  # BinaryResponse
  # -------------------------------------------------------------------
  describe MockServer::BinaryResponse do
    it 'has nil defaults' do
      resp = MockServer::BinaryResponse.new
      expect(resp.binary_data).to be_nil
      expect(resp.delay).to be_nil
    end

    it 'serializes to hash' do
      resp = MockServer::BinaryResponse.new(binary_data: 'AQID')
      expect(resp.to_h).to eq({ 'binaryData' => 'AQID' })
    end

    it 'serializes with delay' do
      resp = MockServer::BinaryResponse.new(
        binary_data: 'AQID',
        delay: MockServer::Delay.new(time_unit: 'MILLISECONDS', value: 100)
      )
      result = resp.to_h
      expect(result).to eq({
        'binaryData' => 'AQID',
        'delay' => { 'timeUnit' => 'MILLISECONDS', 'value' => 100 }
      })
    end

    it 'deserializes from hash' do
      resp = MockServer::BinaryResponse.from_hash({ 'binaryData' => 'dGVzdA==' })
      expect(resp.binary_data).to eq('dGVzdA==')
    end

    it 'returns nil from_hash when data is nil' do
      expect(MockServer::BinaryResponse.from_hash(nil)).to be_nil
    end

    it 'round-trips correctly' do
      original = MockServer::BinaryResponse.new(
        binary_data: 'SGVsbG8=',
        delay: MockServer::Delay.new(time_unit: 'SECONDS', value: 1)
      )
      roundtrip = MockServer::BinaryResponse.from_hash(original.to_h)
      expect(roundtrip.to_h).to eq(original.to_h)
    end

    it 'round-trips upstream' do
      data = { 'binaryData' => 'VXBzdHJlYW0=', 'upstream' => 'FORWARD_AND_REPLACE' }
      resp = MockServer::BinaryResponse.from_hash(data)
      expect(resp.upstream).to eq(MockServer::BinaryUpstream::FORWARD_AND_REPLACE)
      expect(resp.to_h).to eq(data)
    end

    it 'names upstream values as the server does' do
      expect(MockServer::BinaryUpstream::ANSWER_ONLY).to eq('ANSWER_ONLY')
      expect(MockServer::BinaryUpstream::ANSWER_AND_FORWARD).to eq('ANSWER_AND_FORWARD')
      expect(MockServer::BinaryUpstream::FORWARD_AND_REPLACE).to eq('FORWARD_AND_REPLACE')
    end

    it 'omits upstream when unset' do
      expect(MockServer::BinaryResponse.new(binary_data: 'AQID').to_h).not_to have_key('upstream')
    end

    it 'serializes under binaryResponse key in expectation' do
      exp = MockServer::Expectation.new(
        http_request: MockServer::HttpRequest.new(path: '/bin'),
        binary_response: MockServer::BinaryResponse.new(binary_data: 'AQID')
      )
      h = exp.to_h
      expect(h).to have_key('binaryResponse')
      expect(h['binaryResponse']['binaryData']).to eq('AQID')
      expect(h).not_to have_key('httpResponse')
    end

    it 'deserializes from binaryResponse key in expectation' do
      exp = MockServer::Expectation.from_hash({
        'httpRequest' => { 'path' => '/bin' },
        'binaryResponse' => { 'binaryData' => 'AQID' }
      })
      expect(exp.binary_response).not_to be_nil
      expect(exp.binary_response.binary_data).to eq('AQID')
    end
  end

  # -------------------------------------------------------------------
  # DnsRecord
  # -------------------------------------------------------------------
  describe MockServer::DnsRecord do
    it 'has nil defaults' do
      rec = MockServer::DnsRecord.new
      expect(rec.name).to be_nil
      expect(rec.type).to be_nil
    end

    it 'creates an A record via factory' do
      rec = MockServer::DnsRecord.a_record('example.com', '1.2.3.4')
      expect(rec.name).to eq('example.com')
      expect(rec.type).to eq('A')
      expect(rec.value).to eq('1.2.3.4')
    end

    it 'creates a CNAME record via factory' do
      rec = MockServer::DnsRecord.cname_record('www.example.com', 'example.com')
      expect(rec.type).to eq('CNAME')
    end

    it 'creates an MX record via factory' do
      rec = MockServer::DnsRecord.mx_record('example.com', 10, 'mail.example.com')
      expect(rec.type).to eq('MX')
      expect(rec.priority).to eq(10)
    end

    it 'creates an SRV record via factory' do
      rec = MockServer::DnsRecord.srv_record('_sip._tcp.example.com', 10, 20, 5060, 'sip.example.com')
      expect(rec.type).to eq('SRV')
      expect(rec.weight).to eq(20)
      expect(rec.port).to eq(5060)
    end

    it 'serializes to hash' do
      rec = MockServer::DnsRecord.new(name: 'example.com', type: 'A', value: '1.2.3.4', ttl: 300, dns_class: 'IN')
      expect(rec.to_h).to eq({
        'name' => 'example.com',
        'type' => 'A',
        'dnsClass' => 'IN',
        'ttl' => 300,
        'value' => '1.2.3.4'
      })
    end

    it 'deserializes from hash' do
      rec = MockServer::DnsRecord.from_hash({
        'name' => 'example.com',
        'type' => 'A',
        'dnsClass' => 'IN',
        'ttl' => 300,
        'value' => '1.2.3.4'
      })
      expect(rec.name).to eq('example.com')
      expect(rec.type).to eq('A')
      expect(rec.dns_class).to eq('IN')
    end

    it 'returns nil from_hash when data is nil' do
      expect(MockServer::DnsRecord.from_hash(nil)).to be_nil
    end

    it 'round-trips correctly' do
      original = MockServer::DnsRecord.new(name: 'example.com', type: 'MX', priority: 10, value: 'mail.example.com', ttl: 600)
      roundtrip = MockServer::DnsRecord.from_hash(original.to_h)
      expect(roundtrip.to_h).to eq(original.to_h)
    end
  end

  # -------------------------------------------------------------------
  # DnsResponse
  # -------------------------------------------------------------------
  describe MockServer::DnsResponse do
    it 'has nil defaults' do
      resp = MockServer::DnsResponse.new
      expect(resp.response_code).to be_nil
      expect(resp.answer_records).to be_nil
    end

    it 'serializes to hash' do
      resp = MockServer::DnsResponse.new(
        response_code: 'NOERROR',
        answer_records: [
          MockServer::DnsRecord.new(name: 'example.com', type: 'A', value: '1.2.3.4')
        ]
      )
      result = resp.to_h
      expect(result['responseCode']).to eq('NOERROR')
      expect(result['answerRecords'].length).to eq(1)
      expect(result['answerRecords'][0]['type']).to eq('A')
    end

    it 'deserializes from hash' do
      resp = MockServer::DnsResponse.from_hash({
        'responseCode' => 'NXDOMAIN',
        'answerRecords' => [
          { 'name' => 'example.com', 'type' => 'A', 'value' => '1.2.3.4' }
        ]
      })
      expect(resp.response_code).to eq('NXDOMAIN')
      expect(resp.answer_records.length).to eq(1)
      expect(resp.answer_records[0].value).to eq('1.2.3.4')
    end

    it 'returns nil from_hash when data is nil' do
      expect(MockServer::DnsResponse.from_hash(nil)).to be_nil
    end

    it 'round-trips correctly' do
      original = MockServer::DnsResponse.new(
        response_code: 'NOERROR',
        answer_records: [MockServer::DnsRecord.a_record('example.com', '1.2.3.4')],
        authority_records: [MockServer::DnsRecord.cname_record('www.example.com', 'example.com')],
        additional_records: [MockServer::DnsRecord.txt_record('example.com', 'v=spf1')],
        delay: MockServer::Delay.new(time_unit: 'MILLISECONDS', value: 50)
      )
      roundtrip = MockServer::DnsResponse.from_hash(original.to_h)
      expect(roundtrip.to_h).to eq(original.to_h)
    end

    it 'serializes under dnsResponse key in expectation' do
      exp = MockServer::Expectation.new(
        http_request: MockServer::HttpRequest.new(path: '/dns'),
        dns_response: MockServer::DnsResponse.new(
          response_code: 'NOERROR',
          answer_records: [MockServer::DnsRecord.a_record('example.com', '1.2.3.4')]
        )
      )
      h = exp.to_h
      expect(h).to have_key('dnsResponse')
      expect(h['dnsResponse']['responseCode']).to eq('NOERROR')
      expect(h).not_to have_key('httpResponse')
    end

    it 'deserializes from dnsResponse key in expectation' do
      exp = MockServer::Expectation.from_hash({
        'httpRequest' => { 'path' => '/dns' },
        'dnsResponse' => {
          'responseCode' => 'NOERROR',
          'answerRecords' => [{ 'name' => 'example.com', 'type' => 'A', 'value' => '1.2.3.4' }]
        }
      })
      expect(exp.dns_response).not_to be_nil
      expect(exp.dns_response.response_code).to eq('NOERROR')
    end
  end

  # -------------------------------------------------------------------
  # ExpectationStep
  # -------------------------------------------------------------------
  describe MockServer::ExpectationStep do
    it 'has nil defaults' do
      s = MockServer::ExpectationStep.new
      expect(s.http_request).to be_nil
      expect(s.http_response).to be_nil
      expect(s.http_forward).to be_nil
      expect(s.http_error).to be_nil
      expect(s.http_class_callback).to be_nil
      expect(s.http_object_callback).to be_nil
      expect(s.http_override_forwarded_request).to be_nil
      expect(s.responder).to be_nil
      expect(s.delay).to be_nil
      expect(s.blocking).to be_nil
      expect(s.timeout).to be_nil
      expect(s.failure_policy).to be_nil
    end

    it 'serializes responder step with httpResponse' do
      s = MockServer::ExpectationStep.new(
        http_response: MockServer::HttpResponse.new(status_code: 200, body: 'ok'),
        responder: true
      )
      h = s.to_h
      expect(h).to eq({
        'httpResponse' => { 'statusCode' => 200, 'body' => 'ok' },
        'responder' => true
      })
    end

    it 'serializes side-effect step with controls' do
      s = MockServer::ExpectationStep.new(
        http_request: MockServer::HttpRequest.new(method: 'POST', path: '/webhook'),
        blocking: true,
        timeout: MockServer::Delay.new(time_unit: 'SECONDS', value: 5),
        failure_policy: 'FAIL_FAST'
      )
      h = s.to_h
      expect(h['httpRequest']).to eq({ 'method' => 'POST', 'path' => '/webhook' })
      expect(h['blocking']).to be true
      expect(h['timeout']).to eq({ 'timeUnit' => 'SECONDS', 'value' => 5 })
      expect(h['failurePolicy']).to eq('FAIL_FAST')
      expect(h).not_to have_key('responder')
    end

    it 'serializes step with httpForward' do
      s = MockServer::ExpectationStep.new(
        http_forward: MockServer::HttpForward.new(host: 'example.com', port: 8080),
        responder: true,
        delay: MockServer::Delay.new(time_unit: 'MILLISECONDS', value: 100)
      )
      h = s.to_h
      expect(h['httpForward']).to eq({ 'host' => 'example.com', 'port' => 8080 })
      expect(h['responder']).to be true
      expect(h['delay']).to eq({ 'timeUnit' => 'MILLISECONDS', 'value' => 100 })
    end

    it 'serializes step with httpClassCallback' do
      s = MockServer::ExpectationStep.new(
        http_class_callback: MockServer::HttpClassCallback.new(callback_class: 'com.example.Cb')
      )
      h = s.to_h
      expect(h['httpClassCallback']).to eq({ 'callbackClass' => 'com.example.Cb' })
    end

    it 'serializes step with httpObjectCallback' do
      s = MockServer::ExpectationStep.new(
        http_object_callback: MockServer::HttpObjectCallback.new(client_id: 'ws-1')
      )
      h = s.to_h
      expect(h['httpObjectCallback']).to eq({ 'clientId' => 'ws-1' })
    end

    it 'serializes step with httpOverrideForwardedRequest' do
      s = MockServer::ExpectationStep.new(
        http_override_forwarded_request: MockServer::HttpOverrideForwardedRequest.new(
          http_request: MockServer::HttpRequest.new(path: '/override')
        ),
        responder: true
      )
      h = s.to_h
      expect(h['httpOverrideForwardedRequest']).to eq({ 'httpRequest' => { 'path' => '/override' } })
      expect(h['responder']).to be true
    end

    it 'serializes step with httpError' do
      s = MockServer::ExpectationStep.new(
        http_error: MockServer::HttpError.new(drop_connection: true),
        responder: true
      )
      h = s.to_h
      expect(h['httpError']).to eq({ 'dropConnection' => true })
      expect(h['responder']).to be true
    end

    it 'strips nil values' do
      s = MockServer::ExpectationStep.new(
        http_response: MockServer::HttpResponse.new(status_code: 200),
        responder: true
      )
      h = s.to_h
      expect(h).not_to have_key('httpRequest')
      expect(h).not_to have_key('httpForward')
      expect(h).not_to have_key('blocking')
      expect(h).not_to have_key('timeout')
      expect(h).not_to have_key('failurePolicy')
      expect(h).not_to have_key('delay')
    end

    it 'deserializes from hash' do
      s = MockServer::ExpectationStep.from_hash({
        'httpResponse' => { 'statusCode' => 200, 'body' => 'ok' },
        'responder' => true,
        'delay' => { 'timeUnit' => 'SECONDS', 'value' => 1 }
      })
      expect(s.http_response.status_code).to eq(200)
      expect(s.responder).to be true
      expect(s.delay.time_unit).to eq('SECONDS')
      expect(s.delay.value).to eq(1)
    end

    it 'deserializes side-effect controls' do
      s = MockServer::ExpectationStep.from_hash({
        'httpRequest' => { 'path' => '/hook' },
        'blocking' => true,
        'timeout' => { 'timeUnit' => 'SECONDS', 'value' => 5 },
        'failurePolicy' => 'FAIL_FAST'
      })
      expect(s.http_request.path).to eq('/hook')
      expect(s.blocking).to be true
      expect(s.timeout.value).to eq(5)
      expect(s.failure_policy).to eq('FAIL_FAST')
    end

    it 'returns nil from_hash when data is nil' do
      expect(MockServer::ExpectationStep.from_hash(nil)).to be_nil
    end

    it 'round-trips correctly' do
      original = MockServer::ExpectationStep.new(
        http_request: MockServer::HttpRequest.new(method: 'POST', path: '/webhook'),
        blocking: true,
        timeout: MockServer::Delay.new(time_unit: 'SECONDS', value: 3),
        failure_policy: 'BEST_EFFORT',
        delay: MockServer::Delay.new(time_unit: 'MILLISECONDS', value: 50)
      )
      roundtrip = MockServer::ExpectationStep.from_hash(original.to_h)
      expect(roundtrip.to_h).to eq(original.to_h)
    end
  end

  # -------------------------------------------------------------------
  # Expectation with steps
  # -------------------------------------------------------------------
  describe 'Expectation with steps' do
    it 'serializes steps in to_h' do
      exp = MockServer::Expectation.new(
        http_request: MockServer::HttpRequest.new(path: '/api'),
        steps: [
          MockServer::ExpectationStep.new(
            http_request: MockServer::HttpRequest.new(method: 'POST', path: '/webhook'),
            blocking: true,
            timeout: MockServer::Delay.new(time_unit: 'SECONDS', value: 5),
            failure_policy: 'FAIL_FAST'
          ),
          MockServer::ExpectationStep.new(
            http_response: MockServer::HttpResponse.new(status_code: 200, body: 'ok'),
            responder: true
          )
        ]
      )
      h = exp.to_h
      expect(h).to have_key('steps')
      expect(h['steps'].length).to eq(2)
      expect(h['steps'][0]['httpRequest']).to eq({ 'method' => 'POST', 'path' => '/webhook' })
      expect(h['steps'][0]['blocking']).to be true
      expect(h['steps'][0]['failurePolicy']).to eq('FAIL_FAST')
      expect(h['steps'][1]['httpResponse']).to eq({ 'statusCode' => 200, 'body' => 'ok' })
      expect(h['steps'][1]['responder']).to be true
    end

    it 'deserializes steps from hash' do
      exp = MockServer::Expectation.from_hash({
        'httpRequest' => { 'path' => '/api' },
        'steps' => [
          {
            'httpRequest' => { 'method' => 'POST', 'path' => '/webhook' },
            'blocking' => true,
            'timeout' => { 'timeUnit' => 'SECONDS', 'value' => 5 },
            'failurePolicy' => 'FAIL_FAST'
          },
          {
            'httpResponse' => { 'statusCode' => 200, 'body' => 'ok' },
            'responder' => true
          }
        ]
      })
      expect(exp.steps).not_to be_nil
      expect(exp.steps.length).to eq(2)
      expect(exp.steps[0].http_request.path).to eq('/webhook')
      expect(exp.steps[0].blocking).to be true
      expect(exp.steps[0].failure_policy).to eq('FAIL_FAST')
      expect(exp.steps[1].http_response.status_code).to eq(200)
      expect(exp.steps[1].responder).to be true
    end

    it 'omits steps from to_h when not set' do
      exp = MockServer::Expectation.new(
        http_request: MockServer::HttpRequest.new(path: '/test'),
        http_response: MockServer::HttpResponse.new(status_code: 200)
      )
      h = exp.to_h
      expect(h).not_to have_key('steps')
    end

    it 'deserializes nil steps' do
      exp = MockServer::Expectation.from_hash({
        'httpRequest' => { 'path' => '/test' },
        'httpResponse' => { 'statusCode' => 200 }
      })
      expect(exp.steps).to be_nil
    end

    it 'round-trips with steps' do
      original = MockServer::Expectation.new(
        id: 'steps-test',
        http_request: MockServer::HttpRequest.new(path: '/api'),
        steps: [
          MockServer::ExpectationStep.new(
            http_request: MockServer::HttpRequest.new(method: 'POST', path: '/hook'),
            delay: MockServer::Delay.new(time_unit: 'MILLISECONDS', value: 100),
            blocking: true,
            timeout: MockServer::Delay.new(time_unit: 'SECONDS', value: 3),
            failure_policy: 'BEST_EFFORT'
          ),
          MockServer::ExpectationStep.new(
            http_forward: MockServer::HttpForward.new(host: 'backend.local', port: 9090),
            responder: true
          )
        ]
      )
      roundtrip = MockServer::Expectation.from_hash(original.to_h)
      expect(roundtrip.id).to eq('steps-test')
      expect(roundtrip.steps.length).to eq(2)
      expect(roundtrip.steps[0].http_request.path).to eq('/hook')
      expect(roundtrip.steps[0].delay.value).to eq(100)
      expect(roundtrip.steps[0].blocking).to be true
      expect(roundtrip.steps[0].timeout.value).to eq(3)
      expect(roundtrip.steps[0].failure_policy).to eq('BEST_EFFORT')
      expect(roundtrip.steps[1].http_forward.host).to eq('backend.local')
      expect(roundtrip.steps[1].responder).to be true
    end
  end

  # -------------------------------------------------------------------
  # Helper functions
  # -------------------------------------------------------------------
  describe 'MockServer.to_camel' do
    it 'converts known field names' do
      expect(MockServer.to_camel('status_code')).to eq('statusCode')
      expect(MockServer.to_camel('time_to_live')).to eq('timeToLive')
      expect(MockServer.to_camel('not_body')).to eq('not')
    end

    it 'converts generic snake_case' do
      expect(MockServer.to_camel('some_field')).to eq('someField')
    end
  end

  describe 'MockServer.from_camel' do
    it 'converts known camelCase names' do
      expect(MockServer.from_camel('statusCode')).to eq('status_code')
      expect(MockServer.from_camel('timeToLive')).to eq('time_to_live')
    end

    it 'converts generic camelCase' do
      expect(MockServer.from_camel('someField')).to eq('some_field')
    end
  end

  describe 'MockServer.strip_none' do
    it 'removes nil values from hash' do
      result = MockServer.strip_none({ 'a' => 1, 'b' => nil, 'c' => 'test' })
      expect(result).to eq({ 'a' => 1, 'c' => 'test' })
    end

    it 'keeps false and empty string values' do
      result = MockServer.strip_none({ 'a' => false, 'b' => '', 'c' => nil })
      expect(result).to eq({ 'a' => false, 'b' => '' })
    end
  end

  # -------------------------------------------------------------------
  # Cookie serialization (object map, not the header/query array form)
  # -------------------------------------------------------------------
  describe 'cookie serialization' do
    it 'serializes request cookies as a {name => value} object map' do
      req = MockServer::HttpRequest.new(path: '/c')
      req.with_cookie('session', 'abc123')
      expect(req.to_h['cookies']).to eq({ 'session' => 'abc123' })
    end

    it 'serializes response cookies as a {name => value} object map' do
      resp = MockServer::HttpResponse.new
      resp.with_cookie('set', 'v1')
      expect(resp.to_h['cookies']).to eq({ 'set' => 'v1' })
    end

    it 'deserializes request cookies from an object map' do
      req = MockServer::HttpRequest.from_hash({ 'path' => '/c', 'cookies' => { 'session' => 'abc123' } })
      expect(req.cookies[0].name).to eq('session')
      expect(req.cookies[0].values).to eq(['abc123'])
    end
  end

  # -------------------------------------------------------------------
  # Residual round-trip fidelity fields (closed known-gaps["ruby"] entries)
  # -------------------------------------------------------------------
  describe 'round-trip fidelity fields' do
    it 'HttpChaosProfile round-trips the GraphQL fault-injection knobs' do
      chaos = MockServer::HttpChaosProfile.new(
        graphql_errors: true,
        graphql_error_message: 'boom',
        graphql_error_code: 'INTERNAL_SERVER_ERROR',
        graphql_nullify_data: false
      )
      h = chaos.to_h
      expect(h['graphqlErrors']).to be(true)
      expect(h['graphqlErrorMessage']).to eq('boom')
      expect(h['graphqlErrorCode']).to eq('INTERNAL_SERVER_ERROR')
      # false must survive strip_none (only nil is dropped)
      expect(h).to have_key('graphqlNullifyData')
      expect(h['graphqlNullifyData']).to be(false)

      restored = MockServer::HttpChaosProfile.from_hash(h)
      expect(restored.graphql_errors).to be(true)
      expect(restored.graphql_error_message).to eq('boom')
      expect(restored.graphql_error_code).to eq('INTERNAL_SERVER_ERROR')
      expect(restored.graphql_nullify_data).to be(false)
    end

    it 'HttpChaosProfile omits GraphQL knobs when unset' do
      h = MockServer::HttpChaosProfile.new(error_status: 500).to_h
      %w[graphqlErrors graphqlErrorMessage graphqlErrorCode graphqlNullifyData].each do |k|
        expect(h).not_to have_key(k)
      end
    end

    it 'GrpcStreamMessage round-trips templateType' do
      msg = MockServer::GrpcStreamMessage.new(json: '{"id":2}', template_type: 'VELOCITY')
      h = msg.to_h
      expect(h['templateType']).to eq('VELOCITY')
      expect(MockServer::GrpcStreamMessage.from_hash(h).template_type).to eq('VELOCITY')
    end

    it 'GrpcStreamMessage omits templateType when unset' do
      expect(MockServer::GrpcStreamMessage.new(json: '{}').to_h).not_to have_key('templateType')
    end

    it 'GraphQLBody round-trips selectionSetMatchType and array fields' do
      body = MockServer::GraphQLBody.new(
        query: 'query GetUser($id: ID!) { user(id: $id) { name } }',
        operation_name: 'GetUser',
        selection_set_match_type: 'AST_SUBSET',
        fields: ['user']
      )
      h = body.to_h
      expect(h['selectionSetMatchType']).to eq('AST_SUBSET')
      expect(h['fields']).to eq(['user'])

      restored = MockServer::GraphQLBody.from_hash(h)
      expect(restored.selection_set_match_type).to eq('AST_SUBSET')
      expect(restored.fields).to eq(['user'])
    end

    it 'Body round-trips the XML wire key without disturbing string' do
      body = MockServer::Body.from_hash({ 'type' => 'XML', 'xml' => '<a><b>c</b></a>' })
      expect(body.xml).to eq('<a><b>c</b></a>')
      expect(body.string).to be_nil
      h = body.to_h
      expect(h['xml']).to eq('<a><b>c</b></a>')
      expect(h).not_to have_key('string')
    end

    it 'Body.xml factory still emits the legacy string key (not xml)' do
      h = MockServer::Body.xml('<root/>').to_h
      expect(h['string']).to eq('<root/>')
      expect(h).not_to have_key('xml')
    end

    it 'HttpRequest round-trips the protocol field' do
      req = MockServer::HttpRequest.from_hash({ 'path' => '/adv', 'protocol' => 'HTTP_2' })
      expect(req.protocol).to eq('HTTP_2')
      expect(req.to_h['protocol']).to eq('HTTP_2')
    end

    it 'HttpRequest serializes pathParameters in the schema-matcher object form' do
      path_params = { 'cartId' => [{ 'schema' => { 'type' => 'string', 'pattern' => '^[A-Z0-9-]+$' } }] }
      req = MockServer::HttpRequest.from_hash({ 'path' => '/c', 'pathParameters' => path_params })
      expect(req.to_h['pathParameters']).to eq(path_params)
    end

    it 'HttpResponse round-trips trailers' do
      resp = MockServer::HttpResponse.from_hash({ 'trailers' => { 'X-Trailer' => ['end'] } })
      expect(resp.trailers[0].name).to eq('X-Trailer')
      expect(resp.trailers[0].values).to eq(['end'])
      expect(resp.to_h['trailers']).to eq([{ 'name' => 'X-Trailer', 'values' => ['end'] }])
    end

    it 'HttpWebSocketResponse round-trips per-frame matchers' do
      data = {
        'subprotocol' => 'chat',
        'matchers' => [
          { 'frameType' => 'TEXT', 'textMatcher' => 'ping', 'responses' => [{ 'text' => 'pong' }] }
        ]
      }
      resp = MockServer::HttpWebSocketResponse.from_hash(data)
      matcher = resp.matchers[0]
      expect(matcher).to be_a(MockServer::WebSocketFrameMatcher)
      expect(matcher.frame_type).to eq('TEXT')
      expect(matcher.text_matcher).to eq('ping')
      expect(matcher.responses[0].text).to eq('pong')
      expect(resp.to_h['matchers']).to eq(data['matchers'])
    end

    it 'Expectation round-trips the timestamp field' do
      exp = MockServer::Expectation.from_hash({ 'timestamp' => '2026-07-03T12:00:00.000Z' })
      expect(exp.timestamp).to eq('2026-07-03T12:00:00.000Z')
      expect(exp.to_h['timestamp']).to eq('2026-07-03T12:00:00.000Z')
    end
  end

  # -------------------------------------------------------------------
  # Action fields the server accepts: each round-trips under its wire
  # name and is left out when unset.
  # -------------------------------------------------------------------
  describe 'action fields the server accepts' do
    it 'Delay round-trips template and templateType' do
      data = { 'timeUnit' => 'SECONDS', 'value' => 2, 'template' => '$!request.path.length()', 'templateType' => 'VELOCITY' }
      delay = MockServer::Delay.from_hash(data)
      expect(delay.template).to eq('$!request.path.length()')
      expect(delay.template_type).to eq('VELOCITY')
      expect(delay.to_h).to eq(data)
      expect(MockServer::Delay.new(time_unit: 'SECONDS', value: 2).to_h).to eq({ 'timeUnit' => 'SECONDS', 'value' => 2 })
    end

    it 'HttpResponse round-trips statusCodeRange, generateFromSchema and recoverAfter' do
      data = {
        'statusCode' => 200,
        'statusCodeRange' => '2xx',
        'generateFromSchema' => '{"type":"string"}',
        'recoverAfter' => {
          'failTimes' => 2,
          'failResponse' => { 'statusCode' => 503, 'body' => 'busy' },
          'idempotencyHeader' => 'X-Idempotency'
        }
      }
      resp = MockServer::HttpResponse.from_hash(data)
      expect(resp.recover_after).to be_a(MockServer::RecoverAfter)
      expect(resp.recover_after.fail_response).to be_a(MockServer::HttpResponse)
      expect(resp.to_h).to eq(data)
      built = MockServer::HttpResponse.response(status_code: 200)
                                      .with_status_code_range('2xx')
                                      .with_generate_from_schema('{"type":"string"}')
                                      .with_recover_after(MockServer::RecoverAfter.new(
                                        fail_times: 2,
                                        fail_response: MockServer::HttpResponse.new(status_code: 503, body: 'busy'),
                                        idempotency_header: 'X-Idempotency'
                                      ))
      expect(built.to_h).to eq(data)
      unset = MockServer::HttpResponse.new(status_code: 200).to_h
      expect(unset.keys & %w[statusCodeRange generateFromSchema recoverAfter]).to be_empty
      expect(MockServer::RecoverAfter.new.to_h).to eq({})
    end

    it 'HttpTemplate round-trips responseOverride and responseModifier' do
      data = {
        'templateType' => 'VELOCITY',
        'template' => '$!request.path',
        'responseOverride' => { 'statusCode' => 297, 'headers' => [{ 'name' => 'x-a', 'values' => ['b'] }] },
        'responseModifier' => { 'headers' => { 'remove' => ['x-gone'] }, 'cookies' => { 'add' => { 'c' => 'v' } } }
      }
      tmpl = MockServer::HttpTemplate.from_hash(data)
      expect(tmpl.response_override).to be_a(MockServer::HttpResponse)
      expect(tmpl.to_h).to eq(data)
      built = MockServer::HttpTemplate.template('VELOCITY', '$!request.path')
                                      .with_response_override(MockServer::HttpResponse.new(status_code: 297).with_header('x-a', 'b'))
                                      .with_response_modifier(data['responseModifier'])
      expect(built.to_h).to eq(data)
      unset = MockServer::HttpTemplate.template('VELOCITY', 'x').to_h
      expect(unset.keys & %w[responseOverride responseModifier]).to be_empty
    end

    it 'HttpWebSocketResponse round-trips templateType and graphqlSubscriptionFilter' do
      data = {
        'messages' => [{ 'text' => 'hi' }],
        'templateType' => 'MUSTACHE',
        'graphqlSubscriptionFilter' => {
          'type' => 'GRAPHQL',
          'query' => 'subscription { ticks }',
          'operationName' => 'Ticks',
          'variablesSchema' => '{"type":"object"}',
          'selectionSetMatchType' => 'AST_SUBSET',
          'fields' => ['ticks']
        }
      }
      resp = MockServer::HttpWebSocketResponse.from_hash(data)
      expect(resp.graphql_subscription_filter).to be_a(MockServer::GraphQLSubscriptionFilter)
      expect(resp.to_h).to eq(data)
      unset = MockServer::HttpWebSocketResponse.new(messages: [MockServer::WebSocketMessage.new(text: 'hi')]).to_h
      expect(unset.keys & %w[templateType graphqlSubscriptionFilter]).to be_empty
      expect(MockServer::GraphQLSubscriptionFilter.new(query: 'subscription { a }').to_h).to eq({ 'query' => 'subscription { a }' })
    end

    it 'HttpSseResponse round-trips templateType' do
      data = { 'statusCode' => 200, 'events' => [{ 'data' => 'd' }], 'templateType' => 'MUSTACHE' }
      expect(MockServer::HttpSseResponse.from_hash(data).to_h).to eq(data)
      expect(MockServer::HttpSseResponse.new(status_code: 200).to_h).not_to have_key('templateType')
    end

    it 'Expectation carries the fields through' do
      data = {
        'httpRequest' => { 'path' => '/x' },
        'httpResponseTemplate' => {
          'templateType' => 'MUSTACHE',
          'template' => '{}',
          'delay' => { 'timeUnit' => 'MILLISECONDS', 'value' => 1, 'template' => '{{ request.path.length }}', 'templateType' => 'MUSTACHE' },
          'responseOverride' => { 'statusCode' => 201 },
          'responseModifier' => { 'headers' => { 'remove' => ['x'] } }
        }
      }
      expect(MockServer::Expectation.from_hash(data).to_h).to include(data)
    end
  end

  describe 'templated after-action webhook' do
    it 'round-trips httpRequestTemplate and omits it when unset' do
      data = {
        'httpRequest' => { 'path' => '/api' },
        'httpResponse' => { 'statusCode' => 200 },
        'afterActions' => [
          {
            'httpRequestTemplate' => { 'templateType' => 'VELOCITY', 'template' => '{ "path": "/hook" }' },
            'delay' => { 'timeUnit' => 'MILLISECONDS', 'value' => 5 }
          }
        ]
      }
      expectation = MockServer::Expectation.from_hash(data)
      expect(expectation.after_actions.first.http_request_template.template_type).to eq('VELOCITY')
      expect(expectation.after_actions.first.http_request).to be_nil
      expect(expectation.to_h['afterActions']).to eq(data['afterActions'])
      expect(MockServer::AfterAction.new(http_request: MockServer::HttpRequest.new(path: '/x')).to_h.keys).to eq(['httpRequest'])
    end
  end
end
