# frozen_string_literal: true

require 'base64'
require 'json'
require 'set'
require 'erb'

module MockServer
  # Explicit mapping from Ruby snake_case field names to the camelCase
  # keys expected by the MockServer JSON protocol.
  FIELD_MAP = {
    'status_code'                    => 'statusCode',
    'reason_phrase'                  => 'reasonPhrase',
    'keep_alive'                     => 'keepAlive',
    'respond_before_body'            => 'respondBeforeBody',
    'query_string_parameters'        => 'queryStringParameters',
    'path_parameters'                => 'pathParameters',
    'socket_address'                 => 'socketAddress',
    'time_unit'                      => 'timeUnit',
    'time_to_live'                   => 'timeToLive',
    'remaining_times'                => 'remainingTimes',
    'close_socket'                   => 'closeSocket',
    'close_socket_delay'             => 'closeSocketDelay',
    'suppress_content_length_header' => 'suppressContentLengthHeader',
    'content_length_header_override' => 'contentLengthHeaderOverride',
    'suppress_connection_header'     => 'suppressConnectionHeader',
    'keep_alive_override'            => 'keepAliveOverride',
    'connection_options'             => 'connectionOptions',
    'callback_class'                 => 'callbackClass',
    'client_id'                      => 'clientId',
    'response_callback'              => 'responseCallback',
    'drop_connection'                => 'dropConnection',
    'response_bytes'                 => 'responseBytes',
    'stream_error'                   => 'streamError',
    'http_request'                   => 'httpRequest',
    'http_response'                  => 'httpResponse',
    'http_response_template'         => 'httpResponseTemplate',
    'http_response_class_callback'   => 'httpResponseClassCallback',
    'http_response_object_callback'  => 'httpResponseObjectCallback',
    'http_forward'                   => 'httpForward',
    'http_forward_template'          => 'httpForwardTemplate',
    'http_forward_class_callback'    => 'httpForwardClassCallback',
    'http_forward_object_callback'   => 'httpForwardObjectCallback',
    'http_override_forwarded_request' => 'httpOverrideForwardedRequest',
    'http_error'                     => 'httpError',
    'http_sse_response'              => 'httpSseResponse',
    'http_websocket_response'        => 'httpWebSocketResponse',
    'template_type'                  => 'templateType',
    'template_file'                  => 'templateFile',
    'file_path'                      => 'filePath',
    'base64_bytes'                   => 'base64Bytes',
    'not_body'                       => 'not',
    'content_type'                   => 'contentType',
    'at_least'                       => 'atLeast',
    'at_most'                        => 'atMost',
    'expectation_id'                 => 'expectationId',
    'expectation_ids'                => 'expectationIds',
    'http_requests'                  => 'httpRequests',
    'spec_url_or_payload'            => 'specUrlOrPayload',
    'operations_and_responses'       => 'operationsAndResponses',
    'operation_id'                   => 'operationId',
    'request_modifier'               => 'requestModifier',
    'response_modifier'              => 'responseModifier',
    'maximum_number_of_request_to_return_in_verification_failure' => 'maximumNumberOfRequestToReturnInVerificationFailure',
    'base_path'                      => 'basePath',
    'id_field'                       => 'idField',
    'id_strategy'                    => 'idStrategy',
    'initial_data'                   => 'initialData',
    'error_status'                   => 'errorStatus',
    'error_probability'              => 'errorProbability',
    'drop_connection_probability'    => 'dropConnectionProbability',
    'retry_after'                    => 'retryAfter',
    'succeed_first'                  => 'succeedFirst',
    'fail_request_count'             => 'failRequestCount',
    'outage_after_millis'            => 'outageAfterMillis',
    'outage_duration_millis'         => 'outageDurationMillis',
    'truncate_body_at_fraction'      => 'truncateBodyAtFraction',
    'malformed_body'                 => 'malformedBody',
    'slow_response_chunk_size'       => 'slowResponseChunkSize',
    'slow_response_chunk_delay'      => 'slowResponseChunkDelay',
    'quota_name'                     => 'quotaName',
    'quota_limit'                    => 'quotaLimit',
    'quota_window_millis'            => 'quotaWindowMillis',
    'quota_error_status'             => 'quotaErrorStatus',
    'degradation_ramp_millis'        => 'degradationRampMillis',
    'http_class_callback'            => 'httpClassCallback',
    'http_object_callback'           => 'httpObjectCallback',
    'failure_policy'                 => 'failurePolicy',
    'http_responses'                 => 'httpResponses',
    'response_mode'                  => 'responseMode',
    'response_weights'               => 'responseWeights',
    'switch_after'                   => 'switchAfter',
    'cross_protocol_scenarios'       => 'crossProtocolScenarios',
    'scenario_name'                  => 'scenarioName',
    'scenario_state'                 => 'scenarioState',
    'new_scenario_state'             => 'newScenarioState',
    'match_pattern'                  => 'matchPattern',
    'target_state'                   => 'targetState'
  }.freeze

  REVERSE_FIELD_MAP = FIELD_MAP.invert.freeze

  # Known Body type strings used to distinguish Body objects from plain hashes
  # during deserialization.
  BODY_TYPES = Set.new(%w[
    STRING JSON REGEX XML BINARY JSON_SCHEMA JSON_PATH XPATH XML_SCHEMA JSON_RPC GRAPHQL FILE ALL_OF
    PARAMETERS WASM
  ]).freeze

  # -------------------------------------------------------------------
  # Helper functions
  # -------------------------------------------------------------------

  # @api private
  def self.to_camel(snake_str)
    return FIELD_MAP[snake_str] if FIELD_MAP.key?(snake_str)

    parts = snake_str.split('_')
    parts[0] + parts[1..].map(&:capitalize).join
  end

  # @api private
  def self.from_camel(camel_str)
    return REVERSE_FIELD_MAP[camel_str] if REVERSE_FIELD_MAP.key?(camel_str)

    camel_str.gsub(/([A-Z])/) { "_#{$1.downcase}" }
  end

  # @api private
  def self.strip_none(hash)
    hash.reject { |_k, v| v.nil? }
  end

  # @api private
  def self.symbol_keys?(value)
    case value
    when Hash then value.any? { |k, v| k.is_a?(Symbol) || symbol_keys?(v) }
    when Array then value.any? { |v| symbol_keys?(v) }
    else false
    end
  end

  # @api private
  # Returns a copy of +value+ with every Symbol hash key, at any depth, turned
  # into a String; values and other keys are unchanged.
  def self.stringify_keys(value)
    case value
    when Hash then value.each_with_object({}) { |(k, v), h| h[k.is_a?(Symbol) ? k.to_s : k] = stringify_keys(v) }
    when Array then value.map { |v| stringify_keys(v) }
    else value
    end
  end

  # @api private
  # Prepended to every model's from_hash. A hash literal written with JSON-style
  # keys ({ "httpRequest": {...} }) has Symbol keys in Ruby, while the models read
  # the String keys JSON.parse produces, so normalise before reading. Only the
  # outermost call scans: nested calls receive parts of the normalised hash.
  module SymbolKeyTolerantFromHash
    def from_hash(data)
      return super if Thread.current[:mockserver_from_hash_normalised]

      Thread.current[:mockserver_from_hash_normalised] = true
      begin
        super(MockServer.symbol_keys?(data) ? MockServer.stringify_keys(data) : data)
      ensure
        Thread.current[:mockserver_from_hash_normalised] = false
      end
    end
  end

  # @api private
  # Coerce a class-callback value into an {HttpClassCallback}. Accepts:
  #   * +nil+              -> +nil+
  #   * a +String+         -> +HttpClassCallback.new(callback_class: <string>)+
  #   * an HttpClassCallback -> returned unchanged
  # Any other type raises a TypeError. This lets the Expectation setters and the
  # fluent builder accept either a fully-qualified class-name String or a
  # pre-built {HttpClassCallback} (carrying +delay+ / +primary+).
  def self.coerce_class_callback(value)
    return nil if value.nil?
    return HttpClassCallback.new(callback_class: value) if value.is_a?(String)
    return value if value.is_a?(HttpClassCallback)

    raise TypeError,
          "Expected a class-name String or HttpClassCallback, got #{value.class.name}"
  end

  # @api private
  # Percent-encode a single URL path segment (e.g. a scenario name), encoding
  # spaces as %20 (not +) so the segment is safe inside +/mockserver/scenario/{name}+.
  def self.encode_path_segment(value)
    ERB::Util.url_encode(value.to_s)
  end

  # @api private
  def self.serialize_value(value)
    case value
    when ->(v) { v.respond_to?(:to_h) && v.class.ancestors.any? { |a| a.to_s.start_with?('MockServer::') } }
      value.to_h
    when Array
      value.map { |item| serialize_value(item) }
    else
      value
    end
  end

  # @api private
  def self.serialize_body(body)
    return nil if body.nil?
    return body if body.is_a?(String)
    return body if body.is_a?(Hash)
    return body.to_h if body.is_a?(Body)
    return body.to_h if body.is_a?(JsonRpcBody)
    return body.to_h if body.is_a?(GraphQLBody)

    body
  end

  # @api private
  def self.deserialize_body(data)
    return nil if data.nil?
    return data if data.is_a?(String)

    if data.is_a?(Hash)
      if data['type'] == 'JSON_RPC'
        return JsonRpcBody.from_hash(data)
      end
      if data['type'] == 'GRAPHQL'
        return GraphQLBody.from_hash(data)
      end
      return Body.from_hash(data) if BODY_TYPES.include?(data['type'])

      return data
    end
    data
  end

  # @api private
  # With a key_match_style (MATCHING_KEY or SUB_SET) the collection is written in
  # the object form, the only form that carries keyMatchStyle.
  def self.serialize_key_multi_values(items, key_match_style = nil)
    if key_match_style
      map = { 'keyMatchStyle' => key_match_style }
      (items || []).each { |item| (map[item.name] ||= []).concat(Array(item.values)) }
      return map
    end
    return nil if items.nil?

    items.map(&:to_h)
  end

  # @api private
  # An httpRequest holding if is a conditional matcher, one naming an OpenAPI
  # spec (specUrlOrPayload) is an OpenAPI matcher; anything else is an HTTP (or
  # DNS) request matcher.
  def self.deserialize_request_definition(data)
    return ConditionalRequestDefinition.from_hash(data) if data.is_a?(Hash) && data.key?('if')
    return OpenAPIDefinition.from_hash(data) if data.is_a?(Hash) && data.key?('specUrlOrPayload')

    HttpRequest.from_hash(data)
  end

  # @api private
  def self.key_match_style(data)
    data['keyMatchStyle'] || data[:keyMatchStyle] if data.is_a?(Hash)
  end

  # @api private
  # Accept a keyToMultiValue collection as the server's JSON does, so headers,
  # query parameters and trailers can be given as { name => values }, as
  # [{ name:, values: }] or as KeyToMultiValue objects.
  def self.coerce_key_multi_values(value)
    return value if value.nil?
    return deserialize_key_multi_values(stringify_keys(value)) if value.is_a?(Hash)
    raise TypeError, "Expected a Hash or an Array of KeyToMultiValue, got #{value.class.name}" unless value.is_a?(Array)

    value.map { |item| item.is_a?(Hash) ? KeyToMultiValue.from_hash(stringify_keys(item)) : item }
  end

  # @api private
  # Cookies as { name => value }, [{ name:, value: }] or KeyToMultiValue objects.
  def self.coerce_cookies(value)
    return value if value.nil?
    return deserialize_cookies(stringify_keys(value)) if value.is_a?(Hash)
    raise TypeError, "Expected a Hash or an Array of KeyToMultiValue, got #{value.class.name}" unless value.is_a?(Array)

    value.map do |item|
      next item unless item.is_a?(Hash)

      item = stringify_keys(item)
      KeyToMultiValue.new(name: item.fetch('name', ''), values: item.key?('value') ? [item['value']] : item.fetch('values', []))
    end
  end

  # @api private
  # Serialise a keyToMultiValue collection in the { name => [values] } object form
  # (rather than the [{name, values}] array form). MockServer accepts both wire
  # encodings for keyToMultiValue fields, but pathParameters (a schema-matcher map
  # whose values are themselves { "schema": {...} } objects) is conventionally
  # emitted in the object form, so this preserves the caller's shape on round-trip.
  def self.serialize_key_multi_values_object(items)
    return nil if items.nil?

    items.each_with_object({}) { |item, map| map[item.name] = item.values }
  end

  # @api private
  def self.deserialize_key_multi_values(data)
    return nil if data.nil?

    if data.is_a?(Hash)
      return data.reject { |k, _v| k == 'keyMatchStyle' }
                 .map { |k, v| KeyToMultiValue.new(name: k, values: v.is_a?(Array) ? v : [v]) }
    end

    data.map do |item|
      if item.is_a?(Hash)
        KeyToMultiValue.from_hash(item)
      elsif item.is_a?(String)
        KeyToMultiValue.new(name: item, values: [])
      else
        KeyToMultiValue.from_hash(item)
      end
    end
  end

  # @api private
  # Unlike headers / query parameters, MockServer represents cookies as a
  # single-value {name => value} object map, not a [{name, values}] array.
  def self.serialize_cookies(items)
    return nil if items.nil?

    items.each_with_object({}) do |item, map|
      map[item.name] = item.values.is_a?(Array) ? item.values.first : item.values
    end
  end

  # @api private
  def self.deserialize_cookies(data)
    return nil if data.nil?

    if data.is_a?(Hash)
      return data.map { |k, v| KeyToMultiValue.new(name: k, values: v.is_a?(Array) ? v : [v]) }
    end

    # tolerate the legacy array form on read
    deserialize_key_multi_values(data)
  end

  # -------------------------------------------------------------------
  # Model classes
  # -------------------------------------------------------------------

  class DelayDistribution
    attr_accessor :type, :min, :max, :median, :p99, :mean, :std_dev

    def initialize(type: nil, min: nil, max: nil, median: nil, p99: nil, mean: nil, std_dev: nil)
      @type = type
      @min = min
      @max = max
      @median = median
      @p99 = p99
      @mean = mean
      @std_dev = std_dev
    end

    def to_h
      MockServer.strip_none({
        'type'   => @type,
        'min'    => @min,
        'max'    => @max,
        'median' => @median,
        'p99'    => @p99,
        'mean'   => @mean,
        'stdDev' => @std_dev
      })
    end

    def self.from_hash(data)
      return nil if data.nil?

      new(
        type:    data['type'],
        min:     data['min'],
        max:     data['max'],
        median:  data['median'],
        p99:     data['p99'],
        mean:    data['mean'],
        std_dev: data['stdDev']
      )
    end
  end

  class Delay
    # template is rendered against the request and read as milliseconds (falling
    # back to time_unit/value); template_type is VELOCITY or MUSTACHE.
    attr_accessor :time_unit, :value, :distribution, :template, :template_type

    def initialize(time_unit: 'MILLISECONDS', value: 0, distribution: nil, template: nil, template_type: nil)
      @time_unit = time_unit
      @value = value
      @distribution = distribution
      @template = template
      @template_type = template_type
    end

    def to_h
      MockServer.strip_none({
        'timeUnit'     => @time_unit,
        'value'        => @value,
        'distribution' => @distribution&.to_h,
        'template'     => @template,
        'templateType' => @template_type
      })
    end

    def self.from_hash(data)
      return nil if data.nil?

      dist_data = data['distribution']
      new(
        time_unit:     data.fetch('timeUnit', 'MILLISECONDS'),
        value:         data.fetch('value', 0),
        distribution:  dist_data ? DelayDistribution.from_hash(dist_data) : nil,
        template:      data['template'],
        template_type: data['templateType']
      )
    end
  end

  class Times
    attr_accessor :remaining_times, :unlimited

    def initialize(remaining_times: nil, unlimited: nil)
      @remaining_times = remaining_times
      @unlimited = unlimited
    end

    def to_h
      MockServer.strip_none({
        'remainingTimes' => @remaining_times,
        'unlimited'      => @unlimited
      })
    end

    def self.from_hash(data)
      return nil if data.nil?

      new(
        remaining_times: data['remainingTimes'],
        unlimited:       data['unlimited']
      )
    end

    def self.unlimited
      new(unlimited: true)
    end

    def self.exactly(count)
      new(remaining_times: count, unlimited: false)
    end
  end

  class TimeToLive
    attr_accessor :time_unit, :time_to_live, :unlimited

    def initialize(time_unit: nil, time_to_live: nil, unlimited: nil)
      @time_unit = time_unit
      @time_to_live = time_to_live
      @unlimited = unlimited
    end

    def to_h
      MockServer.strip_none({
        'timeUnit'   => @time_unit,
        'timeToLive' => @time_to_live,
        'unlimited'  => @unlimited
      })
    end

    def self.from_hash(data)
      return nil if data.nil?

      new(
        time_unit:    data['timeUnit'],
        time_to_live: data['timeToLive'],
        unlimited:    data['unlimited']
      )
    end

    def self.unlimited
      new(unlimited: true)
    end

    def self.exactly(time_to_live, time_unit)
      new(
        time_unit:    time_unit,
        time_to_live: time_to_live,
        unlimited:    false
      )
    end
  end

  class KeyToMultiValue
    attr_accessor :name, :values

    def initialize(name: '', values: [])
      @name = name
      @values = values
    end

    # name and values are always emitted (not stripped via strip_none) because
    # the MockServer protocol requires both fields on every header/cookie/parameter.
    def to_h
      {
        'name'   => @name,
        'values' => @values
      }
    end

    def self.from_hash(data)
      return nil if data.nil?

      new(
        name:   data.fetch('name', ''),
        values: data.fetch('values', [])
      )
    end
  end

  class Body
    # sub_string / match_type / match_numbers_as_strings / json_schema /
    # parameter_styles / xml_schema / parameters / module_name / optional are the
    # per-type matcher sub-fields carried by the server body DTOs
    # (StringBodyDTO.subString, JsonBodyDTO.matchType/matchNumbersAsStrings,
    # JsonSchemaBodyDTO.jsonSchema/parameterStyles, XmlSchemaBodyDTO.xmlSchema,
    # ParameterBodyDTO.parameters, WasmBodyDTO.moduleName, BodyDTO.optional).
    # xml carries the XML body's own +xml+ wire key (XmlBodyDTO.xml). It is distinct
    # from +string+: the legacy Body.xml(...) factory populates +string+ (pinned by a
    # test), whereas a server-produced XML body serialises its content under +xml+.
    attr_accessor :type, :string, :xml, :json, :regex, :json_path, :xpath, :base64_bytes,
                  :not_body, :content_type, :charset, :file_path, :template_type, :body_all_of,
                  :sub_string, :match_type, :match_numbers_as_strings, :json_schema,
                  :parameter_styles, :xml_schema, :parameters, :module_name, :optional

    def initialize(type: nil, string: nil, xml: nil, json: nil, regex: nil, json_path: nil, xpath: nil,
                   base64_bytes: nil, not_body: nil, content_type: nil, charset: nil,
                   file_path: nil, template_type: nil, body_all_of: nil,
                   sub_string: nil, match_type: nil, match_numbers_as_strings: nil,
                   json_schema: nil, parameter_styles: nil, xml_schema: nil,
                   parameters: nil, module_name: nil, optional: nil)
      @type = type
      @string = string
      @xml = xml
      @json = json
      @regex = regex
      @json_path = json_path
      @xpath = xpath
      @base64_bytes = base64_bytes
      @not_body = not_body
      @content_type = content_type
      @charset = charset
      @file_path = file_path
      @template_type = template_type
      @body_all_of = body_all_of
      @sub_string = sub_string
      @match_type = match_type
      @match_numbers_as_strings = match_numbers_as_strings
      @json_schema = json_schema
      @parameter_styles = parameter_styles
      @xml_schema = xml_schema
      @parameters = parameters
      @module_name = module_name
      @optional = optional
    end

    def to_h
      result = {}
      result['type']         = @type          unless @type.nil?
      result['string']       = @string        unless @string.nil?
      result['xml']          = @xml           unless @xml.nil?
      result['json']         = @json          unless @json.nil?
      result['regex']        = @regex         unless @regex.nil?
      result['jsonPath']     = @json_path     unless @json_path.nil?
      result['xpath']        = @xpath         unless @xpath.nil?
      result['base64Bytes']  = @base64_bytes  unless @base64_bytes.nil?
      result['not']          = @not_body      unless @not_body.nil?
      result['contentType']  = @content_type  unless @content_type.nil?
      result['charset']      = @charset       unless @charset.nil?
      result['filePath']     = @file_path     unless @file_path.nil?
      result['templateType'] = @template_type unless @template_type.nil?
      result['subString']    = @sub_string    unless @sub_string.nil?
      result['matchType']    = @match_type    unless @match_type.nil?
      result['matchNumbersAsStrings'] = @match_numbers_as_strings unless @match_numbers_as_strings.nil?
      result['jsonSchema']   = @json_schema   unless @json_schema.nil?
      result['parameterStyles'] = @parameter_styles unless @parameter_styles.nil?
      result['xmlSchema']    = @xml_schema    unless @xml_schema.nil?
      result['parameters']   = @parameters    unless @parameters.nil?
      result['moduleName']   = @module_name   unless @module_name.nil?
      result['optional']     = @optional      unless @optional.nil?
      unless @body_all_of.nil?
        result['bodyAllOf'] = @body_all_of.map { |b| MockServer.serialize_body(b) }
      end
      result
    end

    def self.from_hash(data)
      return nil if data.nil?

      all_of = data['bodyAllOf']
      new(
        type:          data['type'],
        string:        data['string'],
        xml:           data['xml'],
        json:          data['json'],
        regex:         data['regex'],
        json_path:     data['jsonPath'],
        xpath:         data['xpath'],
        base64_bytes:  data['base64Bytes'],
        not_body:      data['not'],
        content_type:  data['contentType'],
        charset:       data['charset'],
        file_path:     data['filePath'],
        template_type: data['templateType'],
        sub_string:               data['subString'],
        match_type:               data['matchType'],
        match_numbers_as_strings: data['matchNumbersAsStrings'],
        json_schema:              data['jsonSchema'],
        parameter_styles:         data['parameterStyles'],
        xml_schema:               data['xmlSchema'],
        parameters:               data['parameters'],
        module_name:              data['moduleName'],
        optional:                 data['optional'],
        body_all_of:   all_of&.map { |b| MockServer.deserialize_body(b) }
      )
    end

    def self.string(value)
      new(type: 'STRING', string: value)
    end

    def self.json(value)
      new(type: 'JSON', json: value)
    end

    def self.regex(value)
      new(type: 'REGEX', regex: value)
    end

    def self.exact(value)
      new(type: 'STRING', string: value)
    end

    def self.xml(value)
      new(type: 'XML', string: value)
    end

    # Combine several body matchers into a single ALL_OF matcher; all of the
    # supplied sub-bodies must match the request body. Each sub-body is any
    # existing body matcher (e.g. +Body.json_path+, +Body.regex+) and is
    # serialised through the normal body-serialisation path.
    def self.all_of(*bodies)
      new(type: 'ALL_OF', body_all_of: bodies.flatten)
    end

    def self.json_path(value)
      new(type: 'JSON_PATH', json_path: value)
    end

    def self.xpath(value)
      new(type: 'XPATH', xpath: value)
    end

    # A JSON_SCHEMA body matcher. +parameter_styles+ is an optional
    # { parameter-name => style } map (serialised as +parameterStyles+).
    def self.json_schema(schema, parameter_styles: nil)
      new(type: 'JSON_SCHEMA', json_schema: schema, parameter_styles: parameter_styles)
    end

    # An XML_SCHEMA body matcher.
    def self.xml_schema(schema)
      new(type: 'XML_SCHEMA', xml_schema: schema)
    end

    # A STRING body matcher that matches when +value+ appears as a substring.
    def self.sub_string(value)
      new(type: 'STRING', string: value, sub_string: true)
    end

    # A PARAMETERS (form / query) body matcher. +parameters+ is the wire form
    # of the parameter collection (either a { name => [values] } map or the
    # [{name, values}] array form).
    def self.parameters(parameters)
      new(type: 'PARAMETERS', parameters: parameters)
    end

    # A WASM body matcher that delegates matching to the named WASM module.
    def self.wasm(module_name)
      new(type: 'WASM', module_name: module_name)
    end

    def self.file(file_path, content_type: nil, template_type: nil)
      new(type: 'FILE', file_path: file_path, content_type: content_type, template_type: template_type)
    end

    def self.json_rpc(method_name, params_schema: nil)
      JsonRpcBody.new(method_name: method_name, params_schema: params_schema)
    end

    def self.graphql(query, operation_name: nil, variables_schema: nil)
      GraphQLBody.new(query: query, operation_name: operation_name, variables_schema: variables_schema)
    end

    def with_template_type(template_type)
      @template_type = template_type
      self
    end
  end

  class JsonRpcBody
    attr_accessor :method_name, :params_schema, :not_body, :optional

    def initialize(method_name:, params_schema: nil, not_body: false, optional: false)
      @method_name = method_name
      @params_schema = params_schema
      @not_body = not_body
      @optional = optional
    end

    def to_h
      result = { 'type' => 'JSON_RPC', 'method' => @method_name }
      result['paramsSchema'] = @params_schema if @params_schema
      result['not'] = true if @not_body
      result['optional'] = true if @optional
      result
    end

    def self.from_hash(data)
      return nil if data.nil?

      new(
        method_name:   data['method'] || '',
        params_schema: data['paramsSchema'],
        not_body:      data.fetch('not', false),
        optional:      data.fetch('optional', false)
      )
    end
  end

  class GraphQLBody
    # selection_set_match_type selects how the query's selection set is matched
    # (NORMALISED_STRING / AST_EXACT / AST_SUBSET) and +fields+ is the array of
    # top-level field names the matcher restricts to, mirroring the server
    # GraphQLBodyDTO +selectionSetMatchType+ / +fields+ (the GRAPHQL body's +fields+
    # is a plain string array, distinct from the MULTIPART body's keyToMultiValue
    # +fields+ map — the server discriminates the two by the body +type+).
    attr_accessor :query, :operation_name, :variables_schema,
                  :selection_set_match_type, :fields, :not_body, :optional

    def initialize(query:, operation_name: nil, variables_schema: nil,
                   selection_set_match_type: nil, fields: nil, not_body: false, optional: false)
      @query = query
      @operation_name = operation_name
      @variables_schema = variables_schema
      @selection_set_match_type = selection_set_match_type
      @fields = fields
      @not_body = not_body
      @optional = optional
    end

    def to_h
      result = { 'type' => 'GRAPHQL', 'query' => @query }
      result['operationName'] = @operation_name if @operation_name
      result['variablesSchema'] = @variables_schema if @variables_schema
      result['selectionSetMatchType'] = @selection_set_match_type unless @selection_set_match_type.nil?
      result['fields'] = @fields unless @fields.nil?
      result['not'] = true if @not_body
      result['optional'] = true if @optional
      result
    end

    def self.from_hash(data)
      return nil if data.nil?

      new(
        query:                    data['query'] || '',
        operation_name:           data['operationName'],
        variables_schema:         data['variablesSchema'],
        selection_set_match_type: data['selectionSetMatchType'],
        fields:                   data['fields'],
        not_body:                 data.fetch('not', false),
        optional:                 data.fetch('optional', false)
      )
    end
  end

  class SocketAddress
    attr_accessor :host, :port, :scheme

    def initialize(host: nil, port: nil, scheme: nil)
      @host = host
      @port = port
      @scheme = scheme
    end

    def to_h
      MockServer.strip_none({
        'host'   => @host,
        'port'   => @port,
        'scheme' => @scheme
      })
    end

    def self.from_hash(data)
      return nil if data.nil?

      new(
        host:   data['host'],
        port:   data['port'],
        scheme: data['scheme']
      )
    end
  end

  # JWT (bearer-token) request matcher. Matches a request whose JWT — carried by
  # default in the +Authorization: Bearer <token>+ header — satisfies every
  # constraint below.
  #
  # +claims+ is a { claim-name => value } map where each value is an exact string
  # or a regular expression; a leading +!+ negates the match (the NottableString
  # convention shared across MockServer matchers). +issuer+, +audience+,
  # +algorithm+, +header+ and +scheme+ are optional; unset keys are omitted.
  class Jwt
    attr_accessor :claims, :issuer, :audience, :algorithm, :header, :scheme

    def initialize(claims: nil, issuer: nil, audience: nil, algorithm: nil, header: nil, scheme: nil)
      @claims = claims
      @issuer = issuer
      @audience = audience
      @algorithm = algorithm
      @header = header
      @scheme = scheme
    end

    def to_h
      result = {}
      result['claims']    = @claims    unless @claims.nil?
      result['issuer']    = @issuer    unless @issuer.nil?
      result['audience']  = @audience  unless @audience.nil?
      result['algorithm'] = @algorithm unless @algorithm.nil?
      result['header']    = @header    unless @header.nil?
      result['scheme']    = @scheme    unless @scheme.nil?
      result
    end

    def self.from_hash(data)
      return nil if data.nil?

      new(
        claims:    data['claims'],
        issuer:    data['issuer'],
        audience:  data['audience'],
        algorithm: data['algorithm'],
        header:    data['header'],
        scheme:    data['scheme']
      )
    end

    def with_claim(name, value)
      @claims ||= {}
      @claims[name] = value
      self
    end
  end

  class HttpRequest
    # dns_name / dns_type / dns_class carry a DNS request matcher
    # (org.mockserver.model.DnsRequestDefinition). The server discriminates a DNS
    # request definition from an HTTP one purely by the presence of a non-blank
    # +dnsName+ on the +httpRequest+ object, so these fields round-trip through
    # the same +httpRequest+ slot as the HTTP matcher fields.
    # protocol pins the transport the matcher requires
    # (org.mockserver.model.Protocol: HTTP_1_1 / HTTP_2 / HTTP_3 / SOCKS / ...),
    # round-tripping the httpRequest +protocol+ wire key.
    # headers, query_string_parameters, path_parameters and cookies also accept a
    # Hash ({ name => values }) or an Array of Hashes, as in the server's JSON.
    # *_key_match_style is SUB_SET (the server's default: one value of a key
    # must match) or MATCHING_KEY (every value must); a Hash with a
    # 'keyMatchStyle' key sets it too.
    attr_accessor :method, :path, :body, :secure, :protocol, :keep_alive, :respond_before_body,
                  :socket_address, :jwt, :dns_name, :dns_type, :dns_class,
                  :headers_key_match_style, :query_string_parameters_key_match_style,
                  :path_parameters_key_match_style
    attr_reader :query_string_parameters, :headers, :cookies, :path_parameters

    def initialize(method: nil, path: nil, query_string_parameters: nil, headers: nil,
                   cookies: nil, body: nil, secure: nil, protocol: nil, keep_alive: nil,
                   respond_before_body: nil, path_parameters: nil, socket_address: nil,
                   jwt: nil, dns_name: nil, dns_type: nil, dns_class: nil,
                   headers_key_match_style: nil, query_string_parameters_key_match_style: nil,
                   path_parameters_key_match_style: nil)
      @method = method
      @path = path
      self.query_string_parameters = query_string_parameters
      self.headers = headers
      self.cookies = cookies
      @body = body
      @secure = secure
      @protocol = protocol
      @keep_alive = keep_alive
      @respond_before_body = respond_before_body
      self.path_parameters = path_parameters
      @socket_address = socket_address
      @jwt = jwt
      @dns_name = dns_name
      @dns_type = dns_type
      @dns_class = dns_class
      @headers_key_match_style = headers_key_match_style if headers_key_match_style
      @query_string_parameters_key_match_style = query_string_parameters_key_match_style if query_string_parameters_key_match_style
      @path_parameters_key_match_style = path_parameters_key_match_style if path_parameters_key_match_style
    end

    def headers=(value)
      style = MockServer.key_match_style(value)
      @headers_key_match_style = style if style
      @headers = MockServer.coerce_key_multi_values(value)
    end

    def query_string_parameters=(value)
      style = MockServer.key_match_style(value)
      @query_string_parameters_key_match_style = style if style
      @query_string_parameters = MockServer.coerce_key_multi_values(value)
    end

    def path_parameters=(value)
      style = MockServer.key_match_style(value)
      @path_parameters_key_match_style = style if style
      @path_parameters = MockServer.coerce_key_multi_values(value)
    end

    def cookies=(value)
      @cookies = MockServer.coerce_cookies(value)
    end

    def to_h
      path_parameters = if @path_parameters_key_match_style
                          MockServer.serialize_key_multi_values(@path_parameters, @path_parameters_key_match_style)
                        else
                          MockServer.serialize_key_multi_values_object(@path_parameters)
                        end
      MockServer.strip_none({
        'method'                => @method,
        'path'                  => @path,
        'queryStringParameters' => MockServer.serialize_key_multi_values(@query_string_parameters,
                                                                         @query_string_parameters_key_match_style),
        'headers'               => MockServer.serialize_key_multi_values(@headers, @headers_key_match_style),
        'cookies'               => MockServer.serialize_cookies(@cookies),
        'body'                  => MockServer.serialize_body(@body),
        'secure'                => @secure,
        'protocol'              => @protocol,
        'keepAlive'             => @keep_alive,
        'respondBeforeBody'     => @respond_before_body,
        'pathParameters'        => path_parameters,
        'socketAddress'         => @socket_address&.to_h,
        'jwt'                   => @jwt&.to_h,
        'dnsName'               => @dns_name,
        'dnsType'               => @dns_type,
        'dnsClass'              => @dns_class
      })
    end

    def self.from_hash(data)
      return nil if data.nil?

      new(
        method:                  data['method'],
        path:                    data['path'],
        query_string_parameters: MockServer.deserialize_key_multi_values(data['queryStringParameters']),
        headers:                 MockServer.deserialize_key_multi_values(data['headers']),
        cookies:                 MockServer.deserialize_cookies(data['cookies']),
        body:                    MockServer.deserialize_body(data['body']),
        secure:                  data['secure'],
        protocol:                data['protocol'],
        keep_alive:              data['keepAlive'],
        respond_before_body:     data['respondBeforeBody'],
        path_parameters:         MockServer.deserialize_key_multi_values(data['pathParameters']),
        socket_address:          SocketAddress.from_hash(data['socketAddress']),
        jwt:                     Jwt.from_hash(data['jwt']),
        dns_name:                data['dnsName'],
        dns_type:                data['dnsType'],
        dns_class:               data['dnsClass'],
        headers_key_match_style: MockServer.key_match_style(data['headers']),
        query_string_parameters_key_match_style: MockServer.key_match_style(data['queryStringParameters']),
        path_parameters_key_match_style: MockServer.key_match_style(data['pathParameters'])
      )
    end

    # Build a DNS request matcher (org.mockserver.model.DnsRequestDefinition).
    def self.dns_request(dns_name, dns_type: nil, dns_class: nil)
      new(dns_name: dns_name, dns_type: dns_type, dns_class: dns_class)
    end

    def self.request(path: nil)
      new(path: path)
    end

    def with_method(method)
      @method = method
      self
    end

    def with_path(path)
      @path = path
      self
    end

    def with_header(name, *values)
      @headers ||= []
      @headers << KeyToMultiValue.new(name: name, values: values.flatten)
      self
    end

    def with_query_param(name, *values)
      @query_string_parameters ||= []
      @query_string_parameters << KeyToMultiValue.new(name: name, values: values.flatten)
      self
    end

    def with_cookie(name, value)
      @cookies ||= []
      @cookies << KeyToMultiValue.new(name: name, values: [value])
      self
    end

    def with_body(body)
      @body = body
      self
    end

    def with_jwt(jwt)
      @jwt = jwt
      self
    end

    def with_secure(secure)
      @secure = secure
      self
    end

    def with_keep_alive(keep_alive)
      @keep_alive = keep_alive
      self
    end

    def with_respond_before_body(respond_before_body)
      @respond_before_body = respond_before_body
      self
    end
  end

  class ConnectionOptions
    attr_accessor :close_socket, :close_socket_delay, :suppress_content_length_header,
                  :content_length_header_override, :suppress_connection_header,
                  :chunk_size, :chunk_delay, :keep_alive_override

    def initialize(close_socket: nil, close_socket_delay: nil,
                   suppress_content_length_header: nil, content_length_header_override: nil,
                   suppress_connection_header: nil, chunk_size: nil, chunk_delay: nil,
                   keep_alive_override: nil)
      @close_socket = close_socket
      @close_socket_delay = close_socket_delay
      @suppress_content_length_header = suppress_content_length_header
      @content_length_header_override = content_length_header_override
      @suppress_connection_header = suppress_connection_header
      @chunk_size = chunk_size
      @chunk_delay = chunk_delay
      @keep_alive_override = keep_alive_override
    end

    def to_h
      MockServer.strip_none({
        'closeSocket'                 => @close_socket,
        'closeSocketDelay'            => @close_socket_delay&.to_h,
        'suppressContentLengthHeader' => @suppress_content_length_header,
        'contentLengthHeaderOverride' => @content_length_header_override,
        'suppressConnectionHeader'    => @suppress_connection_header,
        'chunkSize'                   => @chunk_size,
        'chunkDelay'                  => @chunk_delay&.to_h,
        'keepAliveOverride'           => @keep_alive_override
      })
    end

    def self.from_hash(data)
      return nil if data.nil?

      new(
        close_socket:                  data['closeSocket'],
        close_socket_delay:            Delay.from_hash(data['closeSocketDelay']),
        suppress_content_length_header: data['suppressContentLengthHeader'],
        content_length_header_override: data['contentLengthHeaderOverride'],
        suppress_connection_header:     data['suppressConnectionHeader'],
        chunk_size:                     data['chunkSize'],
        chunk_delay:                    Delay.from_hash(data['chunkDelay']),
        keep_alive_override:            data['keepAliveOverride']
      )
    end
  end

  # Serves +fail_response+ for the first +fail_times+ matches, then the real
  # response; +idempotency_header+ keys an independent window per header value.
  class RecoverAfter
    attr_accessor :fail_times, :fail_response, :idempotency_header

    def initialize(fail_times: nil, fail_response: nil, idempotency_header: nil)
      @fail_times = fail_times
      @fail_response = fail_response
      @idempotency_header = idempotency_header
    end

    def to_h
      MockServer.strip_none({
        'failTimes'         => @fail_times,
        'failResponse'      => @fail_response&.to_h,
        'idempotencyHeader' => @idempotency_header
      })
    end

    def self.from_hash(data)
      return nil if data.nil?

      new(
        fail_times:         data['failTimes'],
        fail_response:      HttpResponse.from_hash(data['failResponse']),
        idempotency_header: data['idempotencyHeader']
      )
    end
  end

  class HttpResponse
    # trailers carries HTTP trailing headers (org.mockserver.model.Headers on the
    # response's +trailers+ slot), a keyToMultiValue collection like +headers+.
    # status_code_range serves a status from a range such as "2xx";
    # generate_from_schema is a JSON schema the body is generated from.
    # headers, trailers and cookies also accept a Hash or an Array of Hashes,
    # as in the server's JSON (see HttpRequest).
    attr_accessor :status_code, :reason_phrase, :body, :delay, :connection_options, :primary,
                  :status_code_range, :generate_from_schema, :recover_after
    attr_reader :headers, :cookies, :trailers

    def initialize(status_code: nil, reason_phrase: nil, headers: nil, cookies: nil,
                   body: nil, delay: nil, connection_options: nil, trailers: nil, primary: nil,
                   status_code_range: nil, generate_from_schema: nil, recover_after: nil)
      @status_code = status_code
      @reason_phrase = reason_phrase
      self.headers = headers
      self.cookies = cookies
      @body = body
      @delay = delay
      @connection_options = connection_options
      self.trailers = trailers
      @primary = primary
      @status_code_range = status_code_range
      @generate_from_schema = generate_from_schema
      @recover_after = recover_after
    end

    def headers=(value)
      @headers = MockServer.coerce_key_multi_values(value)
    end

    def trailers=(value)
      @trailers = MockServer.coerce_key_multi_values(value)
    end

    def cookies=(value)
      @cookies = MockServer.coerce_cookies(value)
    end

    def to_h
      MockServer.strip_none({
        'statusCode'       => @status_code,
        'reasonPhrase'     => @reason_phrase,
        'headers'          => MockServer.serialize_key_multi_values(@headers),
        'cookies'          => MockServer.serialize_cookies(@cookies),
        'body'             => MockServer.serialize_body(@body),
        'delay'            => @delay&.to_h,
        'connectionOptions' => @connection_options&.to_h,
        'trailers'         => MockServer.serialize_key_multi_values(@trailers),
        'primary'          => @primary,
        'statusCodeRange'  => @status_code_range,
        'generateFromSchema' => @generate_from_schema,
        'recoverAfter'     => @recover_after&.to_h
      })
    end

    def self.from_hash(data)
      return nil if data.nil?

      new(
        status_code:       data['statusCode'],
        reason_phrase:     data['reasonPhrase'],
        headers:           MockServer.deserialize_key_multi_values(data['headers']),
        cookies:           MockServer.deserialize_cookies(data['cookies']),
        body:              MockServer.deserialize_body(data['body']),
        delay:             Delay.from_hash(data['delay']),
        connection_options: ConnectionOptions.from_hash(data['connectionOptions']),
        trailers:          MockServer.deserialize_key_multi_values(data['trailers']),
        primary:           data['primary'],
        status_code_range: data['statusCodeRange'],
        generate_from_schema: data['generateFromSchema'],
        recover_after:     RecoverAfter.from_hash(data['recoverAfter'])
      )
    end

    def self.response(body: nil, status_code: nil)
      resp = new
      if body
        resp.body = body
        if status_code.nil?
          resp.status_code = 200
          resp.reason_phrase = 'OK'
        else
          resp.status_code = status_code
        end
      elsif status_code
        resp.status_code = status_code
      end
      resp
    end

    def self.not_found_response
      new(status_code: 404, reason_phrase: 'Not Found')
    end

    def with_status_code(status_code)
      @status_code = status_code
      self
    end

    def with_header(name, *values)
      @headers ||= []
      @headers << KeyToMultiValue.new(name: name, values: values.flatten)
      self
    end

    def with_cookie(name, value)
      @cookies ||= []
      @cookies << KeyToMultiValue.new(name: name, values: [value])
      self
    end

    def with_body(body)
      @body = body
      self
    end

    def with_delay(delay)
      @delay = delay
      self
    end

    def with_reason_phrase(reason_phrase)
      @reason_phrase = reason_phrase
      self
    end

    def with_status_code_range(status_code_range)
      @status_code_range = status_code_range
      self
    end

    def with_generate_from_schema(schema)
      @generate_from_schema = schema
      self
    end

    def with_recover_after(recover_after)
      @recover_after = recover_after
      self
    end
  end

  class HttpForward
    attr_accessor :host, :port, :scheme, :delay, :primary

    def initialize(host: nil, port: nil, scheme: nil, delay: nil, primary: nil)
      @host = host
      @port = port
      @scheme = scheme
      @delay = delay
      @primary = primary
    end

    def to_h
      MockServer.strip_none({
        'host'    => @host,
        'port'    => @port,
        'scheme'  => @scheme,
        'delay'   => @delay&.to_h,
        'primary' => @primary
      })
    end

    def self.from_hash(data)
      return nil if data.nil?

      new(
        host:    data['host'],
        port:    data['port'],
        scheme:  data['scheme'],
        delay:   Delay.from_hash(data['delay']),
        primary: data['primary']
      )
    end

    def self.forward
      new
    end
  end

  class HttpTemplate
    # response_override (an {HttpResponse}) is applied over the rendered output;
    # response_modifier is the server's responseModifier object, as a Hash.
    attr_accessor :template_type, :template, :template_file, :delay, :primary,
                  :response_override, :response_modifier

    def initialize(template_type: 'JAVASCRIPT', template: nil, template_file: nil, delay: nil, primary: nil,
                   response_override: nil, response_modifier: nil)
      @template_type = template_type
      @template = template
      @template_file = template_file
      @delay = delay
      @primary = primary
      @response_override = response_override
      @response_modifier = response_modifier
    end

    def to_h
      MockServer.strip_none({
        'templateType'     => @template_type,
        'template'         => @template,
        'templateFile'     => @template_file,
        'delay'            => @delay&.to_h,
        'primary'          => @primary,
        'responseOverride' => @response_override&.to_h,
        'responseModifier' => @response_modifier
      })
    end

    def self.from_hash(data)
      return nil if data.nil?

      new(
        template_type: data.fetch('templateType', 'JAVASCRIPT'),
        template:      data['template'],
        template_file: data['templateFile'],
        delay:         Delay.from_hash(data['delay']),
        primary:       data['primary'],
        response_override: HttpResponse.from_hash(data['responseOverride']),
        response_modifier: data['responseModifier']
      )
    end

    def self.template(template_type, template = nil, template_file: nil)
      new(template_type: template_type, template: template, template_file: template_file)
    end

    def with_template_file(template_file)
      @template_file = template_file
      self
    end

    def with_response_override(response_override)
      @response_override = response_override
      self
    end

    def with_response_modifier(response_modifier)
      @response_modifier = response_modifier
      self
    end
  end

  class HttpClassCallback
    attr_accessor :callback_class, :delay, :primary

    def initialize(callback_class: nil, delay: nil, primary: nil)
      @callback_class = callback_class
      @delay = delay
      @primary = primary
    end

    def to_h
      MockServer.strip_none({
        'callbackClass' => @callback_class,
        'delay'         => @delay&.to_h,
        'primary'       => @primary
      })
    end

    def self.from_hash(data)
      return nil if data.nil?

      new(
        callback_class: data['callbackClass'],
        delay:          Delay.from_hash(data['delay']),
        primary:        data['primary']
      )
    end

    def self.callback(callback_class: nil)
      new(callback_class: callback_class)
    end
  end

  class HttpObjectCallback
    attr_accessor :client_id, :response_callback, :delay, :primary

    def initialize(client_id: nil, response_callback: nil, delay: nil, primary: nil)
      @client_id = client_id
      @response_callback = response_callback
      @delay = delay
      @primary = primary
    end

    def to_h
      MockServer.strip_none({
        'clientId'         => @client_id,
        'responseCallback' => @response_callback,
        'delay'            => @delay&.to_h,
        'primary'          => @primary
      })
    end

    def self.from_hash(data)
      return nil if data.nil?

      new(
        client_id:         data['clientId'],
        response_callback: data['responseCallback'],
        delay:             Delay.from_hash(data['delay']),
        primary:           data['primary']
      )
    end
  end

  class HttpError
    # stream_error: reset the matched request stream with this error code (HTTP/2 RST_STREAM /
    # HTTP/3 RESET_STREAM) instead of returning a response; HTTP/1.1 has no stream concept so this
    # falls back to dropping the connection. Takes precedence over drop_connection when both are set.
    attr_accessor :drop_connection, :response_bytes, :stream_error, :delay, :primary

    def initialize(drop_connection: nil, response_bytes: nil, stream_error: nil, delay: nil, primary: nil)
      @drop_connection = drop_connection
      @response_bytes = response_bytes
      @stream_error = stream_error
      @delay = delay
      @primary = primary
    end

    def to_h
      MockServer.strip_none({
        'dropConnection' => @drop_connection,
        'responseBytes'  => @response_bytes,
        'streamError'    => @stream_error,
        'delay'          => @delay&.to_h,
        'primary'        => @primary
      })
    end

    def self.from_hash(data)
      return nil if data.nil?

      new(
        drop_connection: data['dropConnection'],
        response_bytes:  data['responseBytes'],
        stream_error:    data['streamError'],
        delay:           Delay.from_hash(data['delay']),
        primary:         data['primary']
      )
    end

    def self.error
      new
    end
  end

  class HttpOverrideForwardedRequest
    # request_override (an {HttpRequest}) is merged over the forwarded request and
    # response_override (an {HttpResponse}) over the response; response_template
    # (an {HttpTemplate}) renders the response from the request and the forwarded
    # response. http_request / http_response are the older names of the overrides;
    # the server accepts either set, not both.
    attr_accessor :http_request, :http_response, :delay,
                  :request_override, :request_modifier,
                  :response_override, :response_modifier, :response_template, :primary

    def initialize(http_request: nil, http_response: nil, delay: nil,
                   request_override: nil, request_modifier: nil,
                   response_override: nil, response_modifier: nil, response_template: nil,
                   primary: nil)
      @http_request = http_request
      @http_response = http_response
      @delay = delay
      @request_override = request_override
      @request_modifier = request_modifier
      @response_override = response_override
      @response_modifier = response_modifier
      @response_template = response_template
      @primary = primary
    end

    def to_h
      MockServer.strip_none({
        'httpRequest'      => @http_request&.to_h,
        'httpResponse'     => @http_response&.to_h,
        'delay'            => @delay&.to_h,
        'requestOverride'  => @request_override&.to_h,
        'requestModifier'  => @request_modifier,
        'responseOverride' => @response_override&.to_h,
        'responseModifier' => @response_modifier,
        'responseTemplate' => @response_template&.to_h,
        'primary'          => @primary
      })
    end

    def self.from_hash(data)
      return nil if data.nil?

      new(
        http_request:      HttpRequest.from_hash(data['httpRequest']),
        http_response:     HttpResponse.from_hash(data['httpResponse']),
        delay:             Delay.from_hash(data['delay']),
        request_override:  HttpRequest.from_hash(data['requestOverride']),
        request_modifier:  data['requestModifier'],
        response_override: HttpResponse.from_hash(data['responseOverride']),
        response_modifier: data['responseModifier'],
        response_template: HttpTemplate.from_hash(data['responseTemplate']),
        primary:           data['primary']
      )
    end

    def self.forward_overridden_request(request: nil)
      new(http_request: request)
    end

    def with_request_override(request_override)
      @request_override = request_override
      self
    end

    def with_response_override(response_override)
      @response_override = response_override
      self
    end

    def with_response_template(response_template)
      @response_template = response_template
      self
    end
  end

  class HttpRequestAndHttpResponse
    attr_accessor :http_request, :http_response

    def initialize(http_request: nil, http_response: nil)
      @http_request = http_request
      @http_response = http_response
    end

    def to_h
      MockServer.strip_none({
        'httpRequest'  => @http_request&.to_h,
        'httpResponse' => @http_response&.to_h
      })
    end

    def self.from_hash(data)
      return nil if data.nil?

      new(
        http_request:  HttpRequest.from_hash(data['httpRequest']),
        http_response: HttpResponse.from_hash(data['httpResponse'])
      )
    end
  end

  class ExpectationId
    attr_accessor :id

    def initialize(id: '')
      @id = id
    end

    def to_h
      { 'id' => @id }
    end

    def self.from_hash(data)
      return nil if data.nil?

      new(id: data.fetch('id', ''))
    end
  end

  class SseEvent
    attr_accessor :event, :data, :id, :retry, :delay

    def initialize(event: nil, data: nil, id: nil, retry_ms: nil, delay: nil)
      @event = event
      @data = data
      @id = id
      @retry = retry_ms
      @delay = delay
    end

    def to_h
      result = {}
      result['event'] = @event unless @event.nil?
      result['data'] = @data unless @data.nil?
      result['id'] = @id unless @id.nil?
      result['retry'] = @retry unless @retry.nil?
      result['delay'] = @delay.to_h if @delay
      result
    end

    def self.from_hash(data)
      return nil if data.nil?

      new(
        event:    data['event'],
        data:     data['data'],
        id:       data['id'],
        retry_ms: data['retry'],
        delay:    Delay.from_hash(data['delay'])
      )
    end
  end

  class HttpSseResponse
    # template_type renders each event with that engine (VELOCITY / JAVASCRIPT / MUSTACHE).
    attr_accessor :status_code, :headers, :events, :close_connection, :delay, :primary, :template_type

    def initialize(status_code: nil, headers: nil, events: nil, close_connection: nil, delay: nil, primary: nil,
                   template_type: nil)
      @status_code = status_code
      @headers = headers
      @events = events
      @close_connection = close_connection
      @delay = delay
      @primary = primary
      @template_type = template_type
    end

    def to_h
      result = {}
      result['statusCode'] = @status_code unless @status_code.nil?
      result['headers'] = MockServer.serialize_key_multi_values(@headers) if @headers
      result['events'] = @events&.map(&:to_h) if @events
      result['closeConnection'] = @close_connection unless @close_connection.nil?
      result['delay'] = @delay.to_h if @delay
      result['primary'] = @primary unless @primary.nil?
      result['templateType'] = @template_type unless @template_type.nil?
      result
    end

    def self.from_hash(data)
      return nil if data.nil?

      events_data = data['events']
      events = events_data&.map { |e| SseEvent.from_hash(e) }
      new(
        status_code:      data['statusCode'],
        headers:          MockServer.deserialize_key_multi_values(data['headers']),
        events:           events,
        close_connection: data['closeConnection'],
        delay:            Delay.from_hash(data['delay']),
        primary:          data['primary'],
        template_type:    data['templateType']
      )
    end
  end

  class WebSocketMessage
    attr_accessor :text, :binary, :delay

    def initialize(text: nil, binary: nil, delay: nil)
      @text = text
      @binary = binary
      @delay = delay
    end

    def to_h
      result = {}
      result['text'] = @text unless @text.nil?
      result['binary'] = Base64.strict_encode64(@binary) unless @binary.nil?
      result['delay'] = @delay.to_h if @delay
      result
    end

    def self.from_hash(data)
      return nil if data.nil?

      binary_data = data['binary']
      binary = binary_data ? Base64.strict_decode64(binary_data) : nil
      new(
        text:   data['text'],
        binary: binary,
        delay:  Delay.from_hash(data['delay'])
      )
    end
  end

  # A per-incoming-frame response rule for a WebSocket mock (mirrors the server
  # WebSocketMessageMatcher). When +frame_type+ (TEXT / BINARY / PING / PONG / ANY)
  # and the optional +text_matcher+ match an inbound frame, the rule's +responses+
  # (each a {WebSocketMessage}) are sent back.
  class WebSocketFrameMatcher
    attr_accessor :frame_type, :text_matcher, :responses

    def initialize(frame_type: nil, text_matcher: nil, responses: nil)
      @frame_type = frame_type
      @text_matcher = text_matcher
      @responses = responses
    end

    def to_h
      result = {}
      result['frameType'] = @frame_type unless @frame_type.nil?
      result['textMatcher'] = @text_matcher unless @text_matcher.nil?
      result['responses'] = @responses&.map(&:to_h) if @responses
      result
    end

    def self.from_hash(data)
      return nil if data.nil?

      responses_data = data['responses']
      responses = responses_data&.map { |r| WebSocketMessage.from_hash(r) }
      new(
        frame_type:   data['frameType'],
        text_matcher: data['textMatcher'],
        responses:    responses
      )
    end
  end

  # Matches a graphql-transport-ws subscribe message against this GraphQL query.
  # +type+ is an optional body-type discriminator; only "GRAPHQL" is meaningful.
  class GraphQLSubscriptionFilter
    attr_accessor :query, :operation_name, :variables_schema, :selection_set_match_type, :fields, :type

    def initialize(query: nil, operation_name: nil, variables_schema: nil, selection_set_match_type: nil,
                   fields: nil, type: nil)
      @query = query
      @operation_name = operation_name
      @variables_schema = variables_schema
      @selection_set_match_type = selection_set_match_type
      @fields = fields
      @type = type
    end

    def to_h
      MockServer.strip_none({
        'type'                  => @type,
        'query'                 => @query,
        'operationName'         => @operation_name,
        'variablesSchema'       => @variables_schema,
        'selectionSetMatchType' => @selection_set_match_type,
        'fields'                => @fields
      })
    end

    def self.from_hash(data)
      return nil if data.nil?

      new(
        query:                    data['query'],
        operation_name:           data['operationName'],
        variables_schema:         data['variablesSchema'],
        selection_set_match_type: data['selectionSetMatchType'],
        fields:                   data['fields'],
        type:                     data['type']
      )
    end
  end

  class HttpWebSocketResponse
    # matchers carries per-incoming-frame response rules (server
    # HttpWebSocketResponseDTO.matchers); each is a {WebSocketFrameMatcher}.
    # template_type renders each message with that engine.
    attr_accessor :subprotocol, :messages, :matchers, :close_connection, :delay, :primary,
                  :template_type, :graphql_subscription_filter

    def initialize(subprotocol: nil, messages: nil, matchers: nil, close_connection: nil, delay: nil, primary: nil,
                   template_type: nil, graphql_subscription_filter: nil)
      @subprotocol = subprotocol
      @messages = messages
      @matchers = matchers
      @close_connection = close_connection
      @delay = delay
      @primary = primary
      @template_type = template_type
      @graphql_subscription_filter = graphql_subscription_filter
    end

    def to_h
      result = {}
      result['subprotocol'] = @subprotocol unless @subprotocol.nil?
      result['messages'] = @messages&.map(&:to_h) if @messages
      result['matchers'] = @matchers&.map(&:to_h) if @matchers
      result['closeConnection'] = @close_connection unless @close_connection.nil?
      result['delay'] = @delay.to_h if @delay
      result['primary'] = @primary unless @primary.nil?
      result['templateType'] = @template_type unless @template_type.nil?
      result['graphqlSubscriptionFilter'] = @graphql_subscription_filter.to_h if @graphql_subscription_filter
      result
    end

    def self.from_hash(data)
      return nil if data.nil?

      messages_data = data['messages']
      messages = messages_data&.map { |m| WebSocketMessage.from_hash(m) }
      matchers_data = data['matchers']
      matchers = matchers_data&.map { |m| WebSocketFrameMatcher.from_hash(m) }
      new(
        subprotocol:      data['subprotocol'],
        messages:         messages,
        matchers:         matchers,
        close_connection: data['closeConnection'],
        delay:            Delay.from_hash(data['delay']),
        primary:          data['primary'],
        template_type:    data['templateType'],
        graphql_subscription_filter: GraphQLSubscriptionFilter.from_hash(data['graphqlSubscriptionFilter'])
      )
    end
  end

  class GrpcStreamMessage
    # template_type marks the message +json+ as a response template rendered by the
    # named engine (VELOCITY / JAVASCRIPT / MUSTACHE), mirroring the server
    # GrpcStreamResponseDTO / GrpcBidiResponseDTO message +templateType+.
    attr_accessor :json, :template_type, :delay

    def initialize(json: nil, template_type: nil, delay: nil)
      @json = json
      @template_type = template_type
      @delay = delay
    end

    def to_h
      result = {}
      result['json'] = @json unless @json.nil?
      result['templateType'] = @template_type unless @template_type.nil?
      result['delay'] = @delay.to_h if @delay
      result
    end

    def self.from_hash(data)
      return nil if data.nil?

      new(
        json:          data['json'],
        template_type: data['templateType'],
        delay:         Delay.from_hash(data['delay'])
      )
    end
  end

  class GrpcStreamResponse
    attr_accessor :status_name, :status_message, :headers, :messages,
                  :close_connection, :delay, :primary

    def initialize(status_name: nil, status_message: nil, headers: nil,
                   messages: nil, close_connection: nil, delay: nil, primary: nil)
      @status_name = status_name
      @status_message = status_message
      @headers = headers
      @messages = messages
      @close_connection = close_connection
      @delay = delay
      @primary = primary
    end

    def to_h
      result = {}
      result['statusName'] = @status_name unless @status_name.nil?
      result['statusMessage'] = @status_message unless @status_message.nil?
      result['headers'] = MockServer.serialize_key_multi_values(@headers) if @headers
      result['messages'] = @messages&.map(&:to_h) if @messages
      result['closeConnection'] = @close_connection unless @close_connection.nil?
      result['delay'] = @delay.to_h if @delay
      result['primary'] = @primary unless @primary.nil?
      result
    end

    def self.from_hash(data)
      return nil if data.nil?

      messages_data = data['messages']
      messages = messages_data&.map { |m| GrpcStreamMessage.from_hash(m) }
      new(
        status_name:      data['statusName'],
        status_message:   data['statusMessage'],
        headers:          MockServer.deserialize_key_multi_values(data['headers']),
        messages:         messages,
        close_connection: data['closeConnection'],
        delay:            Delay.from_hash(data['delay']),
        primary:          data['primary']
      )
    end
  end

  class GrpcBidiRule
    attr_accessor :match_json, :responses

    def initialize(match_json: nil, responses: nil)
      @match_json = match_json
      @responses = responses
    end

    def to_h
      result = {}
      result['matchJson'] = @match_json unless @match_json.nil?
      result['responses'] = @responses&.map(&:to_h) if @responses
      result
    end

    def self.from_hash(data)
      return nil if data.nil?

      responses_data = data['responses']
      responses = responses_data&.map { |r| GrpcStreamMessage.from_hash(r) }
      new(
        match_json: data['matchJson'],
        responses:  responses
      )
    end
  end

  class GrpcBidiResponse
    attr_accessor :status_name, :status_message, :headers, :messages,
                  :rules, :close_connection, :delay, :primary

    def initialize(status_name: nil, status_message: nil, headers: nil,
                   messages: nil, rules: nil, close_connection: nil, delay: nil, primary: nil)
      @status_name = status_name
      @status_message = status_message
      @headers = headers
      @messages = messages
      @rules = rules
      @close_connection = close_connection
      @delay = delay
      @primary = primary
    end

    def to_h
      result = {}
      result['statusName'] = @status_name unless @status_name.nil?
      result['statusMessage'] = @status_message unless @status_message.nil?
      result['headers'] = MockServer.serialize_key_multi_values(@headers) if @headers
      result['messages'] = @messages&.map(&:to_h) if @messages
      result['rules'] = @rules&.map(&:to_h) if @rules
      result['closeConnection'] = @close_connection unless @close_connection.nil?
      result['delay'] = @delay.to_h if @delay
      result['primary'] = @primary unless @primary.nil?
      result
    end

    def self.from_hash(data)
      return nil if data.nil?

      messages_data = data['messages']
      messages = messages_data&.map { |m| GrpcStreamMessage.from_hash(m) }
      rules_data = data['rules']
      rules = rules_data&.map { |r| GrpcBidiRule.from_hash(r) }
      new(
        status_name:      data['statusName'],
        status_message:   data['statusMessage'],
        headers:          MockServer.deserialize_key_multi_values(data['headers']),
        messages:         messages,
        rules:            rules,
        close_connection: data['closeConnection'],
        delay:            Delay.from_hash(data['delay']),
        primary:          data['primary']
      )
    end
  end

  # Values for BinaryResponse#upstream: what happens upstream to a message a binary
  # expectation matches on a relayed connection (forwardBinaryRequestsMatchExpectations).
  # ANSWER_ONLY is the server's default; the other two need binaryMessageFraming POSTGRESQL.
  module BinaryUpstream
    ANSWER_ONLY         = 'ANSWER_ONLY'
    ANSWER_AND_FORWARD  = 'ANSWER_AND_FORWARD'
    FORWARD_AND_REPLACE = 'FORWARD_AND_REPLACE'
  end

  class BinaryResponse
    attr_accessor :binary_data, :delay, :primary, :upstream

    def initialize(binary_data: nil, delay: nil, primary: nil, upstream: nil)
      @binary_data = binary_data
      @delay = delay
      @primary = primary
      @upstream = upstream
    end

    def to_h
      result = {}
      result['binaryData'] = @binary_data unless @binary_data.nil?
      result['delay'] = @delay.to_h if @delay
      result['primary'] = @primary unless @primary.nil?
      result['upstream'] = @upstream unless @upstream.nil?
      result
    end

    def self.from_hash(data)
      return nil if data.nil?

      new(
        binary_data: data['binaryData'],
        delay:       Delay.from_hash(data['delay']),
        primary:     data['primary'],
        upstream:    data['upstream']
      )
    end
  end

  class DnsRecord
    attr_accessor :name, :type, :dns_class, :ttl, :value,
                  :priority, :weight, :port

    def initialize(name: nil, type: nil, dns_class: nil, ttl: nil,
                   value: nil, priority: nil, weight: nil, port: nil)
      @name = name
      @type = type
      @dns_class = dns_class
      @ttl = ttl
      @value = value
      @priority = priority
      @weight = weight
      @port = port
    end

    def to_h
      result = {}
      result['name'] = @name unless @name.nil?
      result['type'] = @type unless @type.nil?
      result['dnsClass'] = @dns_class unless @dns_class.nil?
      result['ttl'] = @ttl unless @ttl.nil?
      result['value'] = @value unless @value.nil?
      result['priority'] = @priority unless @priority.nil?
      result['weight'] = @weight unless @weight.nil?
      result['port'] = @port unless @port.nil?
      result
    end

    def self.from_hash(data)
      return nil if data.nil?

      new(
        name:      data['name'],
        type:      data['type'],
        dns_class: data['dnsClass'],
        ttl:       data['ttl'],
        value:     data['value'],
        priority:  data['priority'],
        weight:    data['weight'],
        port:      data['port']
      )
    end

    def self.a_record(name, ip)
      new(name: name, type: 'A', value: ip)
    end

    def self.aaaa_record(name, ip)
      new(name: name, type: 'AAAA', value: ip)
    end

    def self.cname_record(name, cname)
      new(name: name, type: 'CNAME', value: cname)
    end

    def self.mx_record(name, priority, exchange)
      new(name: name, type: 'MX', priority: priority, value: exchange)
    end

    def self.srv_record(name, priority, weight, port, target)
      new(name: name, type: 'SRV', priority: priority, weight: weight, port: port, value: target)
    end

    def self.txt_record(name, text)
      new(name: name, type: 'TXT', value: text)
    end

    def self.ptr_record(name, pointer)
      new(name: name, type: 'PTR', value: pointer)
    end
  end

  class DnsResponse
    attr_accessor :response_code, :answer_records, :authority_records,
                  :additional_records, :delay, :primary

    def initialize(response_code: nil, answer_records: nil, authority_records: nil,
                   additional_records: nil, delay: nil, primary: nil)
      @response_code = response_code
      @answer_records = answer_records
      @authority_records = authority_records
      @additional_records = additional_records
      @delay = delay
      @primary = primary
    end

    def to_h
      result = {}
      result['responseCode'] = @response_code unless @response_code.nil?
      result['answerRecords'] = @answer_records.map(&:to_h) if @answer_records
      result['authorityRecords'] = @authority_records.map(&:to_h) if @authority_records
      result['additionalRecords'] = @additional_records.map(&:to_h) if @additional_records
      result['delay'] = @delay.to_h if @delay
      result['primary'] = @primary unless @primary.nil?
      result
    end

    def self.from_hash(data)
      return nil if data.nil?

      answer_data = data['answerRecords']
      authority_data = data['authorityRecords']
      additional_data = data['additionalRecords']
      new(
        response_code:    data['responseCode'],
        answer_records:   answer_data&.map { |r| DnsRecord.from_hash(r) },
        authority_records: authority_data&.map { |r| DnsRecord.from_hash(r) },
        additional_records: additional_data&.map { |r| DnsRecord.from_hash(r) },
        delay:            Delay.from_hash(data['delay']),
        primary:          data['primary']
      )
    end
  end

  class HttpChaosProfile
    # graphql_errors / graphql_error_message / graphql_error_code /
    # graphql_nullify_data carry the GraphQL fault-injection knobs from the server
    # HttpChaosProfileDTO: when graphqlErrors is set the mocked GraphQL response is
    # rewritten to carry an "errors" array (message/code from graphqlErrorMessage /
    # graphqlErrorCode) and, when graphqlNullifyData is true, a null "data" field.
    attr_accessor :error_status, :error_probability, :drop_connection_probability,
                  :retry_after, :latency, :seed, :succeed_first, :fail_request_count,
                  :outage_after_millis, :outage_duration_millis,
                  :truncate_body_at_fraction, :malformed_body,
                  :slow_response_chunk_size, :slow_response_chunk_delay,
                  :quota_name, :quota_limit, :quota_window_millis, :quota_error_status,
                  :degradation_ramp_millis,
                  :graphql_errors, :graphql_error_message, :graphql_error_code,
                  :graphql_nullify_data

    def initialize(error_status: nil, error_probability: nil, drop_connection_probability: nil,
                   retry_after: nil, latency: nil, seed: nil, succeed_first: nil, fail_request_count: nil,
                   outage_after_millis: nil, outage_duration_millis: nil,
                   truncate_body_at_fraction: nil, malformed_body: nil,
                   slow_response_chunk_size: nil, slow_response_chunk_delay: nil,
                   quota_name: nil, quota_limit: nil, quota_window_millis: nil, quota_error_status: nil,
                   degradation_ramp_millis: nil,
                   graphql_errors: nil, graphql_error_message: nil, graphql_error_code: nil,
                   graphql_nullify_data: nil)
      @error_status = error_status
      @error_probability = error_probability
      @drop_connection_probability = drop_connection_probability
      @retry_after = retry_after
      @latency = latency
      @seed = seed
      @succeed_first = succeed_first
      @fail_request_count = fail_request_count
      @outage_after_millis = outage_after_millis
      @outage_duration_millis = outage_duration_millis
      @truncate_body_at_fraction = truncate_body_at_fraction
      @malformed_body = malformed_body
      @slow_response_chunk_size = slow_response_chunk_size
      @slow_response_chunk_delay = slow_response_chunk_delay
      @quota_name = quota_name
      @quota_limit = quota_limit
      @quota_window_millis = quota_window_millis
      @quota_error_status = quota_error_status
      @degradation_ramp_millis = degradation_ramp_millis
      @graphql_errors = graphql_errors
      @graphql_error_message = graphql_error_message
      @graphql_error_code = graphql_error_code
      @graphql_nullify_data = graphql_nullify_data
    end

    def to_h
      MockServer.strip_none({
        'errorStatus'               => @error_status,
        'errorProbability'           => @error_probability,
        'dropConnectionProbability'  => @drop_connection_probability,
        'retryAfter'                 => @retry_after,
        'latency'                    => @latency&.to_h,
        'seed'                       => @seed,
        'succeedFirst'               => @succeed_first,
        'failRequestCount'           => @fail_request_count,
        'outageAfterMillis'          => @outage_after_millis,
        'outageDurationMillis'       => @outage_duration_millis,
        'truncateBodyAtFraction'     => @truncate_body_at_fraction,
        'malformedBody'              => @malformed_body,
        'slowResponseChunkSize'      => @slow_response_chunk_size,
        'slowResponseChunkDelay'     => @slow_response_chunk_delay&.to_h,
        'quotaName'                  => @quota_name,
        'quotaLimit'                 => @quota_limit,
        'quotaWindowMillis'          => @quota_window_millis,
        'quotaErrorStatus'           => @quota_error_status,
        'degradationRampMillis'      => @degradation_ramp_millis,
        'graphqlErrors'              => @graphql_errors,
        'graphqlErrorMessage'        => @graphql_error_message,
        'graphqlErrorCode'           => @graphql_error_code,
        'graphqlNullifyData'         => @graphql_nullify_data
      })
    end

    def self.from_hash(data)
      return nil if data.nil?

      new(
        error_status:               data['errorStatus'],
        error_probability:           data['errorProbability'],
        drop_connection_probability: data['dropConnectionProbability'],
        retry_after:                 data['retryAfter'],
        latency:                     Delay.from_hash(data['latency']),
        seed:                        data['seed'],
        succeed_first:               data['succeedFirst'],
        fail_request_count:          data['failRequestCount'],
        outage_after_millis:         data['outageAfterMillis'],
        outage_duration_millis:      data['outageDurationMillis'],
        truncate_body_at_fraction:   data['truncateBodyAtFraction'],
        malformed_body:              data['malformedBody'],
        slow_response_chunk_size:    data['slowResponseChunkSize'],
        slow_response_chunk_delay:   Delay.from_hash(data['slowResponseChunkDelay']),
        quota_name:                  data['quotaName'],
        quota_limit:                 data['quotaLimit'],
        quota_window_millis:         data['quotaWindowMillis'],
        quota_error_status:          data['quotaErrorStatus'],
        degradation_ramp_millis:     data['degradationRampMillis'],
        graphql_errors:              data['graphqlErrors'],
        graphql_error_message:       data['graphqlErrorMessage'],
        graphql_error_code:          data['graphqlErrorCode'],
        graphql_nullify_data:        data['graphqlNullifyData']
      )
    end
  end

  class AfterAction
    # blocking, timeout and failure_policy are only meaningful for before-actions
    attr_accessor :http_request, :http_class_callback, :http_object_callback, :delay,
                  :blocking, :timeout, :failure_policy

    def initialize(http_request: nil, http_class_callback: nil, http_object_callback: nil, delay: nil,
                   blocking: nil, timeout: nil, failure_policy: nil)
      @http_request = http_request
      @http_class_callback = http_class_callback
      @http_object_callback = http_object_callback
      @delay = delay
      @blocking = blocking
      @timeout = timeout
      @failure_policy = failure_policy
    end

    def to_h
      MockServer.strip_none({
        'httpRequest'        => @http_request&.to_h,
        'httpClassCallback'  => @http_class_callback&.to_h,
        'httpObjectCallback' => @http_object_callback&.to_h,
        'delay'              => @delay&.to_h,
        'blocking'           => @blocking,
        'timeout'            => @timeout&.to_h,
        'failurePolicy'      => @failure_policy
      })
    end

    def self.from_hash(data)
      return nil if data.nil?

      new(
        http_request:        HttpRequest.from_hash(data['httpRequest']),
        http_class_callback: HttpClassCallback.from_hash(data['httpClassCallback']),
        http_object_callback: HttpObjectCallback.from_hash(data['httpObjectCallback']),
        delay:               Delay.from_hash(data['delay']),
        blocking:            data['blocking'],
        timeout:             Delay.from_hash(data['timeout']),
        failure_policy:      data['failurePolicy']
      )
    end
  end

  # A single step in an ordered multi-action expectation pipeline.
  #
  # Each step carries exactly ONE action target and a +responder+ flag.
  # Steps without +responder = true+ are side-effects (fire-and-forget
  # webhooks/callbacks). Exactly one step in the list must be marked as the
  # responder; that step's action produces the HTTP response.
  class ExpectationStep
    attr_accessor :http_request, :http_class_callback, :http_object_callback,
                  :http_forward, :http_override_forwarded_request,
                  :http_response, :http_error,
                  :responder, :delay, :blocking, :timeout, :failure_policy

    def initialize(http_request: nil, http_class_callback: nil, http_object_callback: nil,
                   http_forward: nil, http_override_forwarded_request: nil,
                   http_response: nil, http_error: nil,
                   responder: nil, delay: nil, blocking: nil, timeout: nil, failure_policy: nil)
      @http_request = http_request
      @http_class_callback = http_class_callback
      @http_object_callback = http_object_callback
      @http_forward = http_forward
      @http_override_forwarded_request = http_override_forwarded_request
      @http_response = http_response
      @http_error = http_error
      @responder = responder
      @delay = delay
      @blocking = blocking
      @timeout = timeout
      @failure_policy = failure_policy
    end

    def to_h
      MockServer.strip_none({
        'httpRequest'                  => @http_request&.to_h,
        'httpClassCallback'            => @http_class_callback&.to_h,
        'httpObjectCallback'           => @http_object_callback&.to_h,
        'httpForward'                  => @http_forward&.to_h,
        'httpOverrideForwardedRequest' => @http_override_forwarded_request&.to_h,
        'httpResponse'                 => @http_response&.to_h,
        'httpError'                    => @http_error&.to_h,
        'responder'                    => @responder,
        'delay'                        => @delay&.to_h,
        'blocking'                     => @blocking,
        'timeout'                      => @timeout&.to_h,
        'failurePolicy'                => @failure_policy
      })
    end

    def self.from_hash(data)
      return nil if data.nil?

      new(
        http_request:                  HttpRequest.from_hash(data['httpRequest']),
        http_class_callback:           HttpClassCallback.from_hash(data['httpClassCallback']),
        http_object_callback:          HttpObjectCallback.from_hash(data['httpObjectCallback']),
        http_forward:                  HttpForward.from_hash(data['httpForward']),
        http_override_forwarded_request: HttpOverrideForwardedRequest.from_hash(data['httpOverrideForwardedRequest']),
        http_response:                 HttpResponse.from_hash(data['httpResponse']),
        http_error:                    HttpError.from_hash(data['httpError']),
        responder:                     data['responder'],
        delay:                         Delay.from_hash(data['delay']),
        blocking:                      data['blocking'],
        timeout:                       Delay.from_hash(data['timeout']),
        failure_policy:                data['failurePolicy']
      )
    end
  end

  # The strategy used to pick which of an expectation's +http_responses+ is
  # returned for each matching request. Mirrors the core +ResponseMode+ enum.
  module ResponseMode
    SEQUENTIAL = 'SEQUENTIAL'
    RANDOM     = 'RANDOM'
    WEIGHTED   = 'WEIGHTED'
    SWITCH     = 'SWITCH'
  end

  # The protocol event that advances a cross-protocol scenario. Mirrors the
  # core +CrossProtocolTrigger+ enum.
  module CrossProtocolTrigger
    DNS_QUERY         = 'DNS_QUERY'
    WEBSOCKET_CONNECT = 'WEBSOCKET_CONNECT'
    GRPC_REQUEST      = 'GRPC_REQUEST'
    HTTP_REQUEST      = 'HTTP_REQUEST'
  end

  # Describes a cross-protocol scenario correlation: when a protocol event
  # matching +trigger+ (and optionally +match_pattern+) is observed, the named
  # scenario +scenario_name+ is advanced to +target_state+.
  class CrossProtocolScenario
    attr_accessor :trigger, :match_pattern, :scenario_name, :target_state

    def initialize(trigger: nil, match_pattern: nil, scenario_name: nil, target_state: nil)
      @trigger = trigger
      @match_pattern = match_pattern
      @scenario_name = scenario_name
      @target_state = target_state
    end

    def to_h
      MockServer.strip_none({
        'trigger'      => @trigger,
        'matchPattern' => @match_pattern,
        'scenarioName' => @scenario_name,
        'targetState'  => @target_state
      })
    end

    def self.from_hash(data)
      return nil if data.nil?

      new(
        trigger:       data['trigger'],
        match_pattern: data['matchPattern'],
        scenario_name: data['scenarioName'],
        target_state:  data['targetState']
      )
    end
  end

  # The state of a single named scenario as reported by the server's scenario
  # control plane. +scenario_name+ is the state-machine name and +current_state+
  # is its current state (+nil+ if not yet set).
  class ScenarioState
    attr_accessor :scenario_name, :current_state, :next_state, :transition_after_ms

    def initialize(scenario_name: nil, current_state: nil, next_state: nil, transition_after_ms: nil)
      @scenario_name = scenario_name
      @current_state = current_state
      @next_state = next_state
      @transition_after_ms = transition_after_ms
    end

    def to_h
      MockServer.strip_none({
        'scenarioName'      => @scenario_name,
        'currentState'      => @current_state,
        'nextState'         => @next_state,
        'transitionAfterMs' => @transition_after_ms
      })
    end

    def self.from_hash(data)
      return nil if data.nil?

      new(
        scenario_name:       data['scenarioName'],
        current_state:       data['currentState'],
        next_state:          data['nextState'],
        transition_after_ms: data['transitionAfterMs']
      )
    end
  end

  # A handle to a single named stateful scenario on the server, wrapping the
  # +/mockserver/scenario/{name}+ control-plane endpoints.
  #
  # Obtained via {Client#scenario}:
  #   client.scenario('Deploy').set('Deploying', transition_after_ms: 5000, next_state: 'Deployed')
  #   client.scenario('Deploy').trigger('Failed')
  #   client.scenario('Deploy').state # => "Failed"
  class ScenarioHandle
    attr_reader :name

    def initialize(client, name)
      @client = client
      @name = name
    end

    # GET the current state of this scenario (+nil+ if not yet set).
    # @return [String, nil]
    def state
      result = @client.scenario_request('GET', scenario_path)
      result['currentState']
    end

    # PUT to set this scenario's state, optionally scheduling a timed transition
    # to +next_state+ after +transition_after_ms+ milliseconds.
    #
    # @param state [String] the state to set
    # @param transition_after_ms [Integer, nil] delay before auto-transitioning
    # @param next_state [String, nil] the state to auto-transition to
    # @return [ScenarioState]
    def set(state, transition_after_ms: nil, next_state: nil)
      payload = { 'state' => state }
      payload['transitionAfterMs'] = transition_after_ms unless transition_after_ms.nil?
      payload['nextState'] = next_state unless next_state.nil?
      result = @client.scenario_request('PUT', scenario_path, JSON.generate(payload))
      ScenarioState.from_hash(result)
    end

    # PUT an external trigger advancing this scenario to +new_state+.
    #
    # @param new_state [String]
    # @return [ScenarioState]
    def trigger(new_state)
      body = JSON.generate({ 'newState' => new_state })
      result = @client.scenario_request('PUT', "#{scenario_path}/trigger", body)
      ScenarioState.from_hash(result)
    end

    private

    def scenario_path
      "/mockserver/scenario/#{MockServer.encode_path_segment(@name)}"
    end
  end

  # Per-expectation rate limit (org.mockserver.model.RateLimit). When the counter
  # keyed by +name+ (or the expectation id when +name+ is nil) exceeds the
  # configured limit, the server returns +error_status+ (default 429).
  #
  # +algorithm+ is the wire enum value: "fixed_window" (default) or
  # "token_bucket" (the server serialises it lower-case). FIXED_WINDOW uses
  # +limit+ / +window_millis+; TOKEN_BUCKET uses +burst+ / +refill_per_second+.
  class RateLimit
    attr_accessor :name, :algorithm, :limit, :window_millis, :burst,
                  :refill_per_second, :error_status, :retry_after

    def initialize(name: nil, algorithm: nil, limit: nil, window_millis: nil, burst: nil,
                   refill_per_second: nil, error_status: nil, retry_after: nil)
      @name = name
      @algorithm = algorithm
      @limit = limit
      @window_millis = window_millis
      @burst = burst
      @refill_per_second = refill_per_second
      @error_status = error_status
      @retry_after = retry_after
    end

    def to_h
      MockServer.strip_none({
        'name'            => @name,
        'algorithm'       => @algorithm,
        'limit'           => @limit,
        'windowMillis'    => @window_millis,
        'burst'           => @burst,
        'refillPerSecond' => @refill_per_second,
        'errorStatus'     => @error_status,
        'retryAfter'      => @retry_after
      })
    end

    def self.from_hash(data)
      return nil if data.nil?

      new(
        name:              data['name'],
        algorithm:         data['algorithm'],
        limit:             data['limit'],
        window_millis:     data['windowMillis'],
        burst:             data['burst'],
        refill_per_second: data['refillPerSecond'],
        error_status:      data['errorStatus'],
        retry_after:       data['retryAfter']
      )
    end

    def self.fixed_window(limit, window_millis, name: nil)
      new(name: name, algorithm: 'fixed_window', limit: limit, window_millis: window_millis)
    end

    def self.token_bucket(burst, refill_per_second, name: nil)
      new(name: name, algorithm: 'token_bucket', burst: burst, refill_per_second: refill_per_second)
    end
  end

  # A single expectation-level capture rule (org.mockserver.model.CaptureRule):
  # extract a value from the matched request via +source+/+expression+ and store
  # it under the +into+ key for use by later actions/templates.
  #
  # +source+ is the wire enum value, one of: "jsonPath", "xpath", "header",
  # "queryStringParameter", "cookie", "pathParameter".
  class CaptureRule
    attr_accessor :source, :expression, :into

    def initialize(source: nil, expression: nil, into: nil)
      @source = source
      @expression = expression
      @into = into
    end

    def to_h
      MockServer.strip_none({
        'source'     => @source,
        'expression' => @expression,
        'into'       => @into
      })
    end

    def self.from_hash(data)
      return nil if data.nil?

      new(
        source:     data['source'],
        expression: data['expression'],
        into:       data['into']
      )
    end
  end

  # Forward-and-validate action (org.mockserver.model.HttpForwardValidateAction):
  # forwards the request to +host+/+port+ (+scheme+) and validates the request
  # and/or response against the OpenAPI +spec_url_or_payload+.
  #
  # +validation_mode+ is the wire enum value "STRICT" (default) or "LOG_ONLY".
  class HttpForwardValidateAction
    attr_accessor :spec_url_or_payload, :host, :port, :scheme,
                  :validate_request, :validate_response, :validation_mode,
                  :delay, :primary

    def initialize(spec_url_or_payload: nil, host: nil, port: nil, scheme: nil,
                   validate_request: nil, validate_response: nil, validation_mode: nil,
                   delay: nil, primary: nil)
      @spec_url_or_payload = spec_url_or_payload
      @host = host
      @port = port
      @scheme = scheme
      @validate_request = validate_request
      @validate_response = validate_response
      @validation_mode = validation_mode
      @delay = delay
      @primary = primary
    end

    def to_h
      MockServer.strip_none({
        'specUrlOrPayload' => @spec_url_or_payload,
        'host'             => @host,
        'port'             => @port,
        'scheme'           => @scheme,
        'validateRequest'  => @validate_request,
        'validateResponse' => @validate_response,
        'validationMode'   => @validation_mode,
        'delay'            => @delay&.to_h,
        'primary'          => @primary
      })
    end

    def self.from_hash(data)
      return nil if data.nil?

      new(
        spec_url_or_payload: data['specUrlOrPayload'],
        host:                data['host'],
        port:                data['port'],
        scheme:              data['scheme'],
        validate_request:    data['validateRequest'],
        validate_response:   data['validateResponse'],
        validation_mode:     data['validationMode'],
        delay:               Delay.from_hash(data['delay']),
        primary:             data['primary']
      )
    end
  end

  # Forward-with-fallback action (org.mockserver.model.HttpForwardWithFallback):
  # forwards via +http_forward+, but returns +fallback_response+ when the
  # upstream responds with one of +fallback_on_status_codes+ or (when
  # +fallback_on_timeout+ is true) times out.
  class HttpForwardWithFallback
    attr_accessor :http_forward, :fallback_response, :fallback_on_status_codes,
                  :fallback_on_timeout, :delay, :primary

    def initialize(http_forward: nil, fallback_response: nil, fallback_on_status_codes: nil,
                   fallback_on_timeout: nil, delay: nil, primary: nil)
      @http_forward = http_forward
      @fallback_response = fallback_response
      @fallback_on_status_codes = fallback_on_status_codes
      @fallback_on_timeout = fallback_on_timeout
      @delay = delay
      @primary = primary
    end

    def to_h
      MockServer.strip_none({
        'httpForward'          => @http_forward&.to_h,
        'fallbackResponse'     => @fallback_response&.to_h,
        'fallbackOnStatusCodes' => @fallback_on_status_codes,
        'fallbackOnTimeout'    => @fallback_on_timeout,
        'delay'                => @delay&.to_h,
        'primary'              => @primary
      })
    end

    def self.from_hash(data)
      return nil if data.nil?

      new(
        http_forward:             HttpForward.from_hash(data['httpForward']),
        fallback_response:        HttpResponse.from_hash(data['fallbackResponse']),
        fallback_on_status_codes: data['fallbackOnStatusCodes'],
        fallback_on_timeout:      data['fallbackOnTimeout'],
        delay:                    Delay.from_hash(data['delay']),
        primary:                  data['primary']
      )
    end
  end

  class Expectation
    attr_accessor :id, :priority, :percentage, :http_request, :http_response,
                  :http_response_template, :http_response_class_callback,
                  :http_response_object_callback, :http_forward,
                  :http_forward_template, :http_forward_class_callback,
                  :http_forward_object_callback, :http_override_forwarded_request,
                  :http_error, :times, :time_to_live, :chaos, :rate_limit,
                  :http_sse_response, :http_llm_response, :http_websocket_response,
                  :grpc_stream_response, :grpc_bidi_response,
                  :binary_response, :dns_response,
                  :http_forward_with_fallback, :http_forward_validate_action,
                  :before_actions, :after_actions,
                  :http_responses, :response_mode, :response_weights, :switch_after,
                  :cross_protocol_scenarios, :steps, :capture, :namespace,
                  :scenario_name, :scenario_state, :new_scenario_state, :timestamp

    def initialize(id: nil, priority: nil, percentage: nil, http_request: nil, http_response: nil,
                   http_response_template: nil, http_response_class_callback: nil,
                   http_response_object_callback: nil, http_forward: nil,
                   http_forward_template: nil, http_forward_class_callback: nil,
                   http_forward_object_callback: nil, http_override_forwarded_request: nil,
                   http_error: nil, times: nil, time_to_live: nil, chaos: nil, rate_limit: nil,
                   http_sse_response: nil, http_llm_response: nil, http_websocket_response: nil,
                   grpc_stream_response: nil, grpc_bidi_response: nil,
                   binary_response: nil, dns_response: nil,
                   http_forward_with_fallback: nil, http_forward_validate_action: nil,
                   before_actions: nil, after_actions: nil,
                   http_responses: nil, response_mode: nil, response_weights: nil,
                   switch_after: nil, cross_protocol_scenarios: nil, steps: nil,
                   capture: nil, namespace: nil,
                   scenario_name: nil, scenario_state: nil, new_scenario_state: nil,
                   timestamp: nil)
      @id = id
      @priority = priority
      @percentage = percentage
      @http_request = http_request
      @http_response = http_response
      @http_response_template = http_response_template
      # Route the two class-callback fields through their setters so a bare
      # class-name String passed to the constructor is coerced to an
      # HttpClassCallback (matching the setter / fluent-builder behaviour).
      self.http_response_class_callback = http_response_class_callback
      @http_response_object_callback = http_response_object_callback
      @http_forward = http_forward
      @http_forward_template = http_forward_template
      self.http_forward_class_callback = http_forward_class_callback
      @http_forward_object_callback = http_forward_object_callback
      @http_override_forwarded_request = http_override_forwarded_request
      @http_error = http_error
      @times = times
      @time_to_live = time_to_live
      @chaos = chaos
      @rate_limit = rate_limit
      @http_sse_response = http_sse_response
      @http_llm_response = http_llm_response
      @http_websocket_response = http_websocket_response
      @grpc_stream_response = grpc_stream_response
      @grpc_bidi_response = grpc_bidi_response
      @binary_response = binary_response
      @dns_response = dns_response
      @http_forward_with_fallback = http_forward_with_fallback
      @http_forward_validate_action = http_forward_validate_action
      @before_actions = before_actions
      @after_actions = after_actions
      @http_responses = http_responses
      @response_mode = response_mode
      @response_weights = response_weights
      @switch_after = switch_after
      @cross_protocol_scenarios = cross_protocol_scenarios
      @steps = steps
      @capture = capture
      @namespace = namespace
      @scenario_name = scenario_name
      @scenario_state = scenario_state
      @new_scenario_state = new_scenario_state
      @timestamp = timestamp
    end

    # Set the response class-callback action. Accepts either a fully-qualified
    # class-name String (e.g. "com.example.MyResponseCallback") or a pre-built
    # {HttpClassCallback} (carrying an optional +delay+ / +primary+). A String is
    # wrapped into an {HttpClassCallback} so +to_h+ always emits
    # +httpResponseClassCallback.callbackClass+.
    def http_response_class_callback=(value)
      @http_response_class_callback = MockServer.coerce_class_callback(value)
    end

    # Set the forward class-callback action. Accepts either a fully-qualified
    # class-name String or a pre-built {HttpClassCallback}; serialized as
    # +httpForwardClassCallback+.
    def http_forward_class_callback=(value)
      @http_forward_class_callback = MockServer.coerce_class_callback(value)
    end

    def to_h
      before_actions_h = nil
      if @before_actions.is_a?(Array)
        before_actions_h = @before_actions.map(&:to_h) unless @before_actions.empty?
      elsif @before_actions
        before_actions_h = @before_actions.to_h
      end

      after_actions_h = nil
      if @after_actions.is_a?(Array)
        after_actions_h = @after_actions.map(&:to_h) unless @after_actions.empty?
      elsif @after_actions
        after_actions_h = @after_actions.to_h
      end

      MockServer.strip_none({
        'id'                           => @id,
        'priority'                     => @priority,
        'percentage'                   => @percentage,
        'httpRequest'                  => @http_request&.to_h,
        'httpResponse'                 => @http_response&.to_h,
        'httpResponseTemplate'         => @http_response_template&.to_h,
        'httpResponseClassCallback'    => @http_response_class_callback&.to_h,
        'httpResponseObjectCallback'   => @http_response_object_callback&.to_h,
        'httpForward'                  => @http_forward&.to_h,
        'httpForwardTemplate'          => @http_forward_template&.to_h,
        'httpForwardClassCallback'     => @http_forward_class_callback&.to_h,
        'httpForwardObjectCallback'    => @http_forward_object_callback&.to_h,
        'httpOverrideForwardedRequest' => @http_override_forwarded_request&.to_h,
        'httpForwardValidateAction'    => @http_forward_validate_action&.to_h,
        'httpForwardWithFallback'      => @http_forward_with_fallback&.to_h,
        'httpError'                    => @http_error&.to_h,
        'httpSseResponse'              => @http_sse_response&.to_h,
        'httpLlmResponse'              => MockServer.serialize_value(@http_llm_response),
        'httpWebSocketResponse'        => @http_websocket_response&.to_h,
        'grpcStreamResponse'           => @grpc_stream_response&.to_h,
        'grpcBidiResponse'             => @grpc_bidi_response&.to_h,
        'binaryResponse'               => @binary_response&.to_h,
        'dnsResponse'                  => @dns_response&.to_h,
        'beforeActions'                => before_actions_h,
        'afterActions'                 => after_actions_h,
        'httpResponses'                => @http_responses&.map(&:to_h),
        'responseMode'                 => @response_mode,
        'responseWeights'              => @response_weights,
        'switchAfter'                  => @switch_after,
        'crossProtocolScenarios'       => @cross_protocol_scenarios&.map(&:to_h),
        'steps'                        => @steps&.map(&:to_h),
        'capture'                      => @capture&.map(&:to_h),
        'rateLimit'                    => @rate_limit&.to_h,
        'namespace'                    => @namespace,
        'times'                        => @times&.to_h,
        'timeToLive'                   => @time_to_live&.to_h,
        'chaos'                        => @chaos&.to_h,
        'scenarioName'                 => @scenario_name,
        'scenarioState'                => @scenario_state,
        'newScenarioState'             => @new_scenario_state,
        'timestamp'                    => @timestamp
      })
    end

    def self.from_hash(data)
      return nil if data.nil?

      before_actions_data = data['beforeActions']
      before_actions = if before_actions_data.is_a?(Array)
                         before_actions_data.map { |a| AfterAction.from_hash(a) }
                       elsif before_actions_data
                         [AfterAction.from_hash(before_actions_data)]
                       end

      after_actions_data = data['afterActions']
      after_actions = if after_actions_data.is_a?(Array)
                        after_actions_data.map { |a| AfterAction.from_hash(a) }
                      elsif after_actions_data
                        [AfterAction.from_hash(after_actions_data)]
                      end

      new(
        id:                              data['id'],
        priority:                        data['priority'],
        percentage:                      data['percentage'],
        http_request:                    MockServer.deserialize_request_definition(data['httpRequest']),
        http_response:                   HttpResponse.from_hash(data['httpResponse']),
        http_response_template:          HttpTemplate.from_hash(data['httpResponseTemplate']),
        http_response_class_callback:    HttpClassCallback.from_hash(data['httpResponseClassCallback']),
        http_response_object_callback:   HttpObjectCallback.from_hash(data['httpResponseObjectCallback']),
        http_forward:                    HttpForward.from_hash(data['httpForward']),
        http_forward_template:           HttpTemplate.from_hash(data['httpForwardTemplate']),
        http_forward_class_callback:     HttpClassCallback.from_hash(data['httpForwardClassCallback']),
        http_forward_object_callback:    HttpObjectCallback.from_hash(data['httpForwardObjectCallback']),
        http_override_forwarded_request: HttpOverrideForwardedRequest.from_hash(data['httpOverrideForwardedRequest']),
        http_forward_validate_action:    HttpForwardValidateAction.from_hash(data['httpForwardValidateAction']),
        http_forward_with_fallback:      HttpForwardWithFallback.from_hash(data['httpForwardWithFallback']),
        http_error:                      HttpError.from_hash(data['httpError']),
        http_sse_response:               HttpSseResponse.from_hash(data['httpSseResponse']),
        http_llm_response:               data['httpLlmResponse'],
        http_websocket_response:         HttpWebSocketResponse.from_hash(data['httpWebSocketResponse']),
        grpc_stream_response:            GrpcStreamResponse.from_hash(data['grpcStreamResponse']),
        grpc_bidi_response:              GrpcBidiResponse.from_hash(data['grpcBidiResponse']),
        binary_response:                 BinaryResponse.from_hash(data['binaryResponse']),
        dns_response:                    DnsResponse.from_hash(data['dnsResponse']),
        before_actions:                  before_actions,
        after_actions:                   after_actions,
        http_responses:                  data['httpResponses']&.map { |r| HttpResponse.from_hash(r) },
        response_mode:                   data['responseMode'],
        response_weights:                data['responseWeights'],
        switch_after:                    data['switchAfter'],
        cross_protocol_scenarios:        data['crossProtocolScenarios']&.map { |c| CrossProtocolScenario.from_hash(c) },
        steps:                           data['steps']&.map { |s| ExpectationStep.from_hash(s) },
        capture:                         data['capture']&.map { |c| CaptureRule.from_hash(c) },
        rate_limit:                      RateLimit.from_hash(data['rateLimit']),
        namespace:                       data['namespace'],
        times:                           Times.from_hash(data['times']),
        time_to_live:                    TimeToLive.from_hash(data['timeToLive']),
        chaos:                           HttpChaosProfile.from_hash(data['chaos']),
        scenario_name:                   data['scenarioName'],
        scenario_state:                  data['scenarioState'],
        new_scenario_state:              data['newScenarioState'],
        timestamp:                       data['timestamp']
      )
    end
  end

  # An OpenAPI request matcher: as an expectation's or verification's
  # httpRequest it matches the requests an OpenAPI operation accepts.
  class OpenAPIDefinition
    # not_operation inverts the match (the server's +not+ field).
    attr_accessor :spec_url_or_payload, :operation_id, :context_path_prefix, :not_operation

    def initialize(spec_url_or_payload: nil, operation_id: nil, context_path_prefix: nil, not_operation: nil)
      @spec_url_or_payload = spec_url_or_payload
      @operation_id = operation_id
      @context_path_prefix = context_path_prefix
      @not_operation = not_operation
    end

    def to_h
      MockServer.strip_none({
        'not'               => @not_operation,
        'specUrlOrPayload'  => @spec_url_or_payload,
        'operationId'       => @operation_id,
        'contextPathPrefix' => @context_path_prefix
      })
    end

    def self.from_hash(data)
      return nil if data.nil?

      new(
        spec_url_or_payload: data['specUrlOrPayload'],
        operation_id:        data['operationId'],
        context_path_prefix: data['contextPathPrefix'],
        not_operation:       data['not']
      )
    end
  end

  # A conditional (if/then/else) request matcher, written as an httpRequest
  # holding if, then and else. When +if_request+ matches, +then_request+ must
  # match too; otherwise +else_request+ must (with none, the matcher matches
  # whenever +if_request+ does not). Each branch is an HttpRequest, an
  # OpenAPIDefinition or another ConditionalRequestDefinition.
  class ConditionalRequestDefinition
    # not_condition inverts the match (the server's +not+ field).
    attr_accessor :if_request, :then_request, :else_request, :not_condition

    def initialize(if_request: nil, then_request: nil, else_request: nil, not_condition: nil)
      @if_request = if_request
      @then_request = then_request
      @else_request = else_request
      @not_condition = not_condition
    end

    def to_h
      MockServer.strip_none({
        'not'  => @not_condition,
        'if'   => @if_request&.to_h,
        'then' => @then_request&.to_h,
        'else' => @else_request&.to_h
      })
    end

    def self.from_hash(data)
      return nil if data.nil?

      new(
        if_request:    MockServer.deserialize_request_definition(data['if']),
        then_request:  MockServer.deserialize_request_definition(data['then']),
        else_request:  MockServer.deserialize_request_definition(data['else']),
        not_condition: data['not']
      )
    end
  end

  class OpenAPIExpectation
    attr_accessor :spec_url_or_payload, :operations_and_responses

    def initialize(spec_url_or_payload: nil, operations_and_responses: nil)
      @spec_url_or_payload = spec_url_or_payload
      @operations_and_responses = operations_and_responses
    end

    def to_h
      MockServer.strip_none({
        'specUrlOrPayload'      => @spec_url_or_payload,
        'operationsAndResponses' => @operations_and_responses
      })
    end

    def self.from_hash(data)
      return nil if data.nil?

      new(
        spec_url_or_payload:      data['specUrlOrPayload'],
        operations_and_responses: data['operationsAndResponses']
      )
    end
  end

  class VerificationTimes
    attr_accessor :at_least, :at_most

    def initialize(at_least: nil, at_most: nil)
      @at_least = at_least
      @at_most = at_most
    end

    def to_h
      MockServer.strip_none({
        'atLeast' => @at_least,
        'atMost'  => @at_most
      })
    end

    def self.from_hash(data)
      return nil if data.nil?

      new(
        at_least: data['atLeast'],
        at_most:  data['atMost']
      )
    end

    def self.at_least(count)
      new(at_least: count)
    end

    def self.at_most(count)
      new(at_most: count)
    end

    def self.exactly(count)
      new(at_least: count, at_most: count)
    end

    def self.once
      new(at_least: 1, at_most: 1)
    end

    def self.between(at_least, at_most)
      new(at_least: at_least, at_most: at_most)
    end
  end

  class Verification
    attr_accessor :http_request, :http_response, :expectation_id, :times,
                  :maximum_number_of_request_to_return_in_verification_failure

    def initialize(http_request: nil, http_response: nil, expectation_id: nil, times: nil,
                   maximum_number_of_request_to_return_in_verification_failure: nil)
      @http_request = http_request
      @http_response = http_response
      @expectation_id = expectation_id
      @times = times
      @maximum_number_of_request_to_return_in_verification_failure = maximum_number_of_request_to_return_in_verification_failure
    end

    def to_h
      MockServer.strip_none({
        'httpRequest'    => @http_request&.to_h,
        'httpResponse'   => @http_response&.to_h,
        'expectationId'  => @expectation_id&.to_h,
        'times'          => @times&.to_h,
        'maximumNumberOfRequestToReturnInVerificationFailure' => @maximum_number_of_request_to_return_in_verification_failure
      })
    end

    def self.from_hash(data)
      return nil if data.nil?

      new(
        http_request:    MockServer.deserialize_request_definition(data['httpRequest']),
        http_response:   HttpResponse.from_hash(data['httpResponse']),
        expectation_id:  ExpectationId.from_hash(data['expectationId']),
        times:           VerificationTimes.from_hash(data['times']),
        maximum_number_of_request_to_return_in_verification_failure: data['maximumNumberOfRequestToReturnInVerificationFailure']
      )
    end
  end

  class VerificationSequence
    attr_accessor :http_requests, :http_responses, :expectation_ids

    def initialize(http_requests: nil, http_responses: nil, expectation_ids: nil)
      @http_requests = http_requests
      @http_responses = http_responses
      @expectation_ids = expectation_ids
    end

    def to_h
      MockServer.strip_none({
        'httpRequests'   => @http_requests&.map(&:to_h),
        'httpResponses'  => @http_responses&.map(&:to_h),
        'expectationIds' => @expectation_ids&.map(&:to_h)
      })
    end

    def self.from_hash(data)
      return nil if data.nil?

      http_requests_data = data['httpRequests']
      http_responses_data = data['httpResponses']
      expectation_ids_data = data['expectationIds']
      new(
        http_requests:   http_requests_data&.map { |r| MockServer.deserialize_request_definition(r) },
        http_responses:  http_responses_data&.map { |r| HttpResponse.from_hash(r) },
        expectation_ids: expectation_ids_data&.map { |e| ExpectationId.from_hash(e) }
      )
    end
  end

  class Ports
    attr_accessor :ports

    def initialize(ports: [])
      @ports = ports
    end

    def to_h
      { 'ports' => @ports }
    end

    def self.from_hash(data)
      return nil if data.nil?

      new(ports: data.fetch('ports', []))
    end
  end

  class CrudExpectationsDefinition
    attr_accessor :base_path, :id_field, :id_strategy, :initial_data

    def initialize(base_path: '', id_field: 'id', id_strategy: 'AUTO_INCREMENT', initial_data: nil)
      @base_path = base_path
      @id_field = id_field
      @id_strategy = id_strategy
      @initial_data = initial_data
    end

    def to_h
      MockServer.strip_none({
        'basePath'    => @base_path,
        'idField'     => @id_field,
        'idStrategy'  => @id_strategy,
        'initialData' => @initial_data
      })
    end

    def self.from_hash(data)
      return nil if data.nil?

      new(
        base_path:    data.fetch('basePath', ''),
        id_field:     data.fetch('idField', 'id'),
        id_strategy:  data.fetch('idStrategy', 'AUTO_INCREMENT'),
        initial_data: data['initialData']
      )
    end
  end

  # One stage of a {LoadProfile}, run in sequence. Each stage holds or ramps a
  # setpoint for +duration_millis+:
  #
  # * +VU+ (closed model) - hold +vus+ virtual users, or ramp +start_vus+ to
  #   +end_vus+ along +curve+.
  # * +RATE+ (open model) - hold +rate+ iterations/second, or ramp +start_rate+
  #   to +end_rate+ along +curve+, optionally capping the auto-scaling
  #   virtual-user pool at +max_vus+.
  # * +PAUSE+ - drive no load for +duration_millis+.
  #
  # Prefer the {.vu}, {.rate} and {.pause} factories, which emit only the fields
  # relevant to the stage type and mode.
  class LoadStage
    attr_accessor :type, :duration_millis, :curve, :vus, :start_vus, :end_vus,
                  :rate, :start_rate, :end_rate, :max_vus

    def initialize(type:, duration_millis:, curve: nil, vus: nil, start_vus: nil,
                   end_vus: nil, rate: nil, start_rate: nil, end_rate: nil, max_vus: nil)
      @type = type
      @duration_millis = duration_millis
      @curve = curve
      @vus = vus
      @start_vus = start_vus
      @end_vus = end_vus
      @rate = rate
      @start_rate = start_rate
      @end_rate = end_rate
      @max_vus = max_vus
    end

    # A +VU+ (closed-model) stage - hold +vus+ or ramp +start_vus+ to +end_vus+.
    def self.vu(duration_millis, vus: nil, start_vus: nil, end_vus: nil, curve: nil)
      new(type: 'VU', duration_millis: duration_millis, vus: vus,
          start_vus: start_vus, end_vus: end_vus, curve: curve)
    end

    # A +RATE+ (open-model) stage - hold +rate+ or ramp +start_rate+ to
    # +end_rate+ (iterations/second).
    def self.rate(duration_millis, rate: nil, start_rate: nil, end_rate: nil, max_vus: nil, curve: nil)
      new(type: 'RATE', duration_millis: duration_millis, rate: rate,
          start_rate: start_rate, end_rate: end_rate, max_vus: max_vus, curve: curve)
    end

    # A +PAUSE+ stage - drive no load for +duration_millis+.
    def self.pause(duration_millis)
      new(type: 'PAUSE', duration_millis: duration_millis)
    end

    def to_h
      MockServer.strip_none({
        'type'           => @type,
        'durationMillis' => @duration_millis,
        'curve'          => @curve,
        'vus'            => @vus,
        'startVus'       => @start_vus,
        'endVus'         => @end_vus,
        'rate'           => @rate,
        'startRate'      => @start_rate,
        'endRate'        => @end_rate,
        'maxVus'         => @max_vus
      })
    end

    def self.from_hash(data)
      return nil if data.nil?

      new(
        type:            data['type'],
        duration_millis: data['durationMillis'],
        curve:           data['curve'],
        vus:             data['vus'],
        start_vus:       data['startVus'],
        end_vus:         data['endVus'],
        rate:            data['rate'],
        start_rate:      data['startRate'],
        end_rate:        data['endRate'],
        max_vus:         data['maxVus']
      )
    end
  end

  # A declarative named load shape that expands into ordinary {LoadStage} stages.
  # Set this as a {LoadProfile}'s +shape+ instead of an explicit +stages+ list;
  # only the parameters its +type+ needs are read. +type+ is one of +SPIKE+,
  # +STAIRS+ or +RAMP_HOLD+; +metric+ (+VU+ or +RATE+) selects what the shape
  # drives.
  class LoadShape
    attr_accessor :type, :metric, :curve, :baseline, :peak, :ramp_up_millis,
                  :hold_millis, :ramp_down_millis, :recovery_hold_millis,
                  :start, :step, :steps, :step_duration_millis, :target, :ramp_millis

    def initialize(type:, metric: nil, curve: nil, baseline: nil, peak: nil,
                   ramp_up_millis: nil, hold_millis: nil, ramp_down_millis: nil,
                   recovery_hold_millis: nil, start: nil, step: nil, steps: nil,
                   step_duration_millis: nil, target: nil, ramp_millis: nil)
      @type = type
      @metric = metric
      @curve = curve
      @baseline = baseline
      @peak = peak
      @ramp_up_millis = ramp_up_millis
      @hold_millis = hold_millis
      @ramp_down_millis = ramp_down_millis
      @recovery_hold_millis = recovery_hold_millis
      @start = start
      @step = step
      @steps = steps
      @step_duration_millis = step_duration_millis
      @target = target
      @ramp_millis = ramp_millis
    end

    def to_h
      MockServer.strip_none({
        'type'               => @type,
        'metric'             => @metric,
        'curve'              => @curve,
        'baseline'           => @baseline,
        'peak'               => @peak,
        'rampUpMillis'       => @ramp_up_millis,
        'holdMillis'         => @hold_millis,
        'rampDownMillis'     => @ramp_down_millis,
        'recoveryHoldMillis' => @recovery_hold_millis,
        'start'              => @start,
        'step'               => @step,
        'steps'              => @steps,
        'stepDurationMillis' => @step_duration_millis,
        'target'             => @target,
        'rampMillis'         => @ramp_millis
      })
    end

    def self.from_hash(data)
      return nil if data.nil?

      new(
        type:                 data['type'],
        metric:               data['metric'],
        curve:                data['curve'],
        baseline:             data['baseline'],
        peak:                 data['peak'],
        ramp_up_millis:       data['rampUpMillis'],
        hold_millis:          data['holdMillis'],
        ramp_down_millis:     data['rampDownMillis'],
        recovery_hold_millis: data['recoveryHoldMillis'],
        start:                data['start'],
        step:                 data['step'],
        steps:                data['steps'],
        step_duration_millis: data['stepDurationMillis'],
        target:               data['target'],
        ramp_millis:          data['rampMillis']
      )
    end
  end

  # The traffic-shaping profile of a load scenario: EITHER an ordered list of
  # {LoadStage} objects run in sequence, each holding or ramping a setpoint
  # (virtual users, an arrival rate, or a pause) for its duration, OR a single
  # named {LoadShape} that expands into stages. Set one, not both; if both are
  # set the explicit stages win. The total run length is the sum of the stage
  # durations.
  class LoadProfile
    attr_accessor :stages, :shape

    def initialize(stages: nil, shape: nil)
      @stages = stages
      @shape = shape
    end

    def to_h
      MockServer.strip_none({
        'stages' => @stages.nil? ? nil : @stages.map(&:to_h),
        'shape'  => @shape&.to_h
      })
    end

    def self.from_hash(data)
      return nil if data.nil?

      stages_data = data['stages']
      new(
        stages: stages_data ? stages_data.map { |s| LoadStage.from_hash(s) } : nil,
        shape:  LoadShape.from_hash(data['shape'])
      )
    end
  end

  # A declarative cross-step capture / correlation rule: extracts a value from a
  # step's response and binds it to +name+, which a later step in the same
  # iteration can reference from its templated request fields. +source+ is one of
  # +BODY_JSONPATH+, +HEADER+ or +BODY_REGEX+; +expression+ drives the extraction;
  # +default_value+ is an optional fallback when extraction yields nothing.
  class LoadCapture
    attr_accessor :name, :source, :expression, :default_value

    def initialize(name:, source:, expression:, default_value: nil)
      @name = name
      @source = source
      @expression = expression
      @default_value = default_value
    end

    def to_h
      MockServer.strip_none({
        'name'         => @name,
        'source'       => @source,
        'expression'   => @expression,
        'defaultValue' => @default_value
      })
    end

    def self.from_hash(data)
      return nil if data.nil?

      new(
        name:          data['name'],
        source:        data['source'],
        expression:    data['expression'],
        default_value: data['defaultValue']
      )
    end
  end

  # An in-run pass/fail threshold for a load scenario: a per-run +metric+ compared
  # with +comparator+ against +threshold+. All thresholds must hold for the run
  # verdict to be +PASS+; any breach makes it +FAIL+. +metric+ is one of
  # +LATENCY_P50+/+LATENCY_P95+/+LATENCY_P99+/+LATENCY_P999+/+ERROR_RATE+/
  # +THROUGHPUT_RPS+; +comparator+ is one of +LESS_THAN+/+LESS_THAN_OR_EQUAL+/
  # +GREATER_THAN+/+GREATER_THAN_OR_EQUAL+.
  class LoadThreshold
    attr_accessor :metric, :comparator, :threshold

    def initialize(metric:, comparator:, threshold:)
      @metric = metric
      @comparator = comparator
      @threshold = threshold
    end

    def to_h
      MockServer.strip_none({
        'metric'     => @metric,
        'comparator' => @comparator,
        'threshold'  => @threshold
      })
    end

    def self.from_hash(data)
      return nil if data.nil?

      new(
        metric:     data['metric'],
        comparator: data['comparator'],
        threshold:  data['threshold']
      )
    end
  end

  # Adaptive iteration pacing (think-time) for a load scenario: a target
  # per-virtual-user iteration cycle time. +mode+ is one of +NONE+,
  # +CONSTANT_PACING+ (+value+ is the target cycle in milliseconds) or
  # +CONSTANT_THROUGHPUT+ (+value+ is the target iterations/second per VU).
  class LoadPacing
    attr_accessor :mode, :value

    def initialize(mode:, value: nil)
      @mode = mode
      @value = value
    end

    def to_h
      MockServer.strip_none({
        'mode'  => @mode,
        'value' => @value
      })
    end

    def self.from_hash(data)
      return nil if data.nil?

      new(
        mode:  data['mode'],
        value: data['value']
      )
    end
  end

  # Parameterized test data (a data feeder) for a load scenario: an inline dataset
  # from which one row is selected per iteration and exposed to the templated
  # request. Supply EITHER +rows+ (inline list of column-name to value maps, the
  # primary form) OR +data+ + +format+ (raw CSV/JSON parsed server-side).
  # +strategy+ (+CIRCULAR+, +RANDOM+ or +SEQUENTIAL+) chooses how a row is picked.
  class LoadFeeder
    attr_accessor :rows, :data, :format, :strategy

    def initialize(rows: nil, data: nil, format: nil, strategy: nil)
      @rows = rows
      @data = data
      @format = format
      @strategy = strategy
    end

    def to_h
      MockServer.strip_none({
        'rows'     => @rows,
        'data'     => @data,
        'format'   => @format,
        'strategy' => @strategy
      })
    end

    def self.from_hash(data)
      return nil if data.nil?

      new(
        rows:     data['rows'],
        data:     data['data'],
        format:   data['format'],
        strategy: data['strategy']
      )
    end
  end

  # A per-step response assertion for a load scenario: reads a value from the
  # step's response (+source+ +STATUS+, +HEADER+ with +header_name+, or
  # +BODY_JSONPATH+ with +json_path+) and compares it with +value+ using
  # +comparator+ (+EQUALS+, +NOT_EQUALS+, +CONTAINS+, +MATCHES+, +GT+, +LT+,
  # +GTE+ or +LTE+). A failing check never fails the request; failures feed the
  # +CHECK_FAILURE_RATE+ threshold.
  class LoadCheck
    attr_accessor :source, :header_name, :json_path, :comparator, :value

    def initialize(source:, comparator:, header_name: nil, json_path: nil, value: nil)
      @source = source
      @comparator = comparator
      @header_name = header_name
      @json_path = json_path
      @value = value
    end

    def to_h
      MockServer.strip_none({
        'source'     => @source,
        'headerName' => @header_name,
        'jsonPath'   => @json_path,
        'comparator' => @comparator,
        'value'      => @value
      })
    end

    def self.from_hash(data)
      return nil if data.nil?

      new(
        source:      data['source'],
        header_name: data['headerName'],
        json_path:   data['jsonPath'],
        comparator:  data['comparator'],
        value:       data['value']
      )
    end
  end

  # A single step within a load scenario. Each step fires +request+ (an HttpRequest)
  # against the target, optionally pausing for +think_time+ (a Delay) afterwards.
  # +captures+ binds values from this step's response for later steps in the same
  # iteration; +checks+ (LoadCheck) assert on that response; +weight+ is the
  # relative selection weight when the scenario's +step_selection+ is +WEIGHTED+.
  class LoadStep
    attr_accessor :name, :labels, :think_time, :request, :captures, :checks, :weight

    def initialize(request:, name: nil, labels: nil, think_time: nil, captures: nil, checks: nil, weight: nil)
      @request = request
      @name = name
      @labels = labels
      @think_time = think_time
      @captures = captures
      @checks = checks
      @weight = weight
    end

    def to_h
      MockServer.strip_none({
        'name'      => @name,
        'labels'    => @labels,
        'thinkTime' => @think_time&.to_h,
        'request'   => @request&.to_h,
        'captures'  => @captures.nil? ? nil : @captures.map(&:to_h),
        'checks'    => @checks.nil? ? nil : @checks.map(&:to_h),
        'weight'    => @weight
      })
    end

    def self.from_hash(data)
      return nil if data.nil?

      captures_data = data['captures']
      checks_data = data['checks']
      new(
        name:       data['name'],
        labels:     data['labels'],
        think_time: Delay.from_hash(data['thinkTime']),
        request:    HttpRequest.from_hash(data['request']),
        captures:   captures_data ? captures_data.map { |c| LoadCapture.from_hash(c) } : nil,
        checks:     checks_data ? checks_data.map { |c| LoadCheck.from_hash(c) } : nil,
        weight:     data['weight']
      )
    end
  end

  # A load-injection scenario: a named set of +steps+ driven by a traffic +profile+.
  # +template_type+ selects the templating engine (+VELOCITY+ or +MUSTACHE+) used to
  # render step requests; +max_requests+ caps the total requests issued.
  # +thresholds+ are in-run pass/fail checks; +abort_on_fail+/+abort_grace_millis+
  # control early-abort behaviour; +pacing+ shapes inter-iteration timing; +feeder+
  # supplies per-iteration data; +step_selection+ (+SEQUENTIAL+ or +WEIGHTED+)
  # controls how each iteration selects steps.
  class LoadScenario
    attr_accessor :name, :template_type, :labels, :max_requests, :start_delay_millis,
                  :profile, :steps, :thresholds, :abort_on_fail, :abort_grace_millis,
                  :pacing, :feeder, :step_selection

    def initialize(name:, profile:, steps:, template_type: nil, labels: nil, max_requests: nil,
                   start_delay_millis: nil, thresholds: nil, abort_on_fail: nil,
                   abort_grace_millis: nil, pacing: nil, feeder: nil, step_selection: nil)
      @name = name
      @profile = profile
      @steps = steps
      @template_type = template_type
      @labels = labels
      @max_requests = max_requests
      @start_delay_millis = start_delay_millis
      @thresholds = thresholds
      @abort_on_fail = abort_on_fail
      @abort_grace_millis = abort_grace_millis
      @pacing = pacing
      @feeder = feeder
      @step_selection = step_selection
    end

    def to_h
      MockServer.strip_none({
        'name'             => @name,
        'templateType'     => @template_type,
        'labels'           => @labels,
        'maxRequests'      => @max_requests,
        'startDelayMillis' => @start_delay_millis,
        'thresholds'       => @thresholds.nil? ? nil : @thresholds.map(&:to_h),
        'abortOnFail'      => @abort_on_fail,
        'abortGraceMillis' => @abort_grace_millis,
        'pacing'           => @pacing&.to_h,
        'feeder'           => @feeder&.to_h,
        'stepSelection'    => @step_selection,
        'profile'          => @profile&.to_h,
        'steps'            => @steps&.map(&:to_h)
      })
    end

    def self.from_hash(data)
      return nil if data.nil?

      steps_data = data['steps']
      thresholds_data = data['thresholds']
      new(
        name:               data['name'],
        template_type:      data['templateType'],
        labels:             data['labels'],
        max_requests:       data['maxRequests'],
        start_delay_millis: data['startDelayMillis'],
        thresholds:         thresholds_data ? thresholds_data.map { |t| LoadThreshold.from_hash(t) } : nil,
        abort_on_fail:      data['abortOnFail'],
        abort_grace_millis: data['abortGraceMillis'],
        pacing:             LoadPacing.from_hash(data['pacing']),
        feeder:             LoadFeeder.from_hash(data['feeder']),
        step_selection:     data['stepSelection'],
        profile:            LoadProfile.from_hash(data['profile']),
        steps:              steps_data ? steps_data.map { |s| LoadStep.from_hash(s) } : nil
      )
    end
  end

  # -------------------------------------------------------------------
  # Diagnostics: explain, debug_mismatch and explain_unmatched results.
  # They follow the RequestExplanation and ExplainedExpectation OpenAPI
  # schemas; an enum value a newer server sends is kept as its String.
  # -------------------------------------------------------------------

  # A JSON patch (RFC 6902) of an expectation that would make it match the
  # request; nothing is applied. +unpatched_fields+ lists each field that
  # could not be patched, with a +reason+.
  class SuggestedFix
    attr_reader :patch, :patched_fields, :unpatched_fields, :matches_after_patch

    def initialize(patch: [], patched_fields: [], unpatched_fields: [], matches_after_patch: false)
      @patch = patch
      @patched_fields = patched_fields
      @unpatched_fields = unpatched_fields
      @matches_after_patch = matches_after_patch
    end

    def self.from_hash(data)
      return nil if data.nil?

      new(
        patch:               data['patch'] || [],
        patched_fields:      data['patchedFields'] || [],
        unpatched_fields:    data['unpatchedFields'] || [],
        matches_after_patch: data['matchesAfterPatch'] == true
      )
    end
  end

  # One expectation as explain, debug_mismatch or explain_unmatched evaluated
  # it. The matched expectation has no +reason+; a skipped or shadowed one has
  # a +reason+ (NAMESPACE, INACTIVE, FIELDS_DIFFER, NOT_OPERATOR, CONVERSATION,
  # SCENARIO_STATE, PERCENTAGE or LOWER_PRIORITY) and an +explanation+.
  # +suggested_fix+ is set only by explain_unmatched with +suggest_fixes+.
  class ExplainedExpectation
    attr_reader :expectation_id, :expectation_method, :expectation_path, :position, :priority,
                :reason, :explanation, :matches, :matched_field_count, :total_field_count,
                :differing_field_count, :differences, :remediation, :suggested_fix

    def initialize(expectation_id: nil, expectation_method: nil, expectation_path: nil, position: nil,
                   priority: nil, reason: nil, explanation: nil, matches: nil, matched_field_count: nil,
                   total_field_count: nil, differing_field_count: nil, differences: {}, remediation: {},
                   suggested_fix: nil)
      @expectation_id = expectation_id
      @expectation_method = expectation_method
      @expectation_path = expectation_path
      @position = position
      @priority = priority
      @reason = reason
      @explanation = explanation
      @matches = matches
      @matched_field_count = matched_field_count
      @total_field_count = total_field_count
      @differing_field_count = differing_field_count
      @differences = differences
      @remediation = remediation
      @suggested_fix = suggested_fix
    end

    def self.from_hash(data)
      return nil if data.nil?

      new(
        expectation_id:        data['expectationId'],
        expectation_method:    data['expectationMethod'],
        expectation_path:      data['expectationPath'],
        position:              data['position'],
        priority:              data['priority'],
        reason:                data['reason'],
        explanation:           data['explanation'],
        matches:               data['matches'],
        matched_field_count:   data['matchedFieldCount'],
        total_field_count:     data['totalFieldCount'],
        differing_field_count: data['differingFieldCount'],
        differences:           data['differences'] || {},
        remediation:           data['remediation'] || {},
        suggested_fix:         SuggestedFix.from_hash(data['suggestedFix'])
      )
    end

    # @api private
    def self.list(items)
      (items || []).map { |item| from_hash(item) }
    end
  end

  # The recorded request that explain re-checked.
  class ExplainedRequest
    attr_reader :timestamp, :method, :path, :body_truncated, :body_truncated_note

    def initialize(timestamp: nil, method: nil, path: nil, body_truncated: false, body_truncated_note: nil)
      @timestamp = timestamp
      @method = method
      @path = path
      @body_truncated = body_truncated
      @body_truncated_note = body_truncated_note
    end

    def self.from_hash(data)
      return nil if data.nil?

      new(
        timestamp:           data['timestamp'],
        method:              data['method'],
        path:                data['path'],
        body_truncated:      data['bodyTruncated'] == true,
        body_truncated_note: data['bodyTruncatedNote']
      )
    end
  end

  # How a request was handled when it arrived: +outcome+ is MATCHED, NO_MATCH,
  # FORWARDED_WITHOUT_EXPECTATION, RESPONDED_WITHOUT_EXPECTATION or UNKNOWN;
  # the expectation fields are set for MATCHED only.
  # +forward_failure+ is set only when the request was forwarded and the
  # forward failed.
  class Arrival
    attr_reader :outcome, :expectation_id, :expectation_still_stored, :forward_failure

    def initialize(outcome: nil, expectation_id: nil, expectation_still_stored: nil, forward_failure: nil)
      @outcome = outcome
      @expectation_id = expectation_id
      @expectation_still_stored = expectation_still_stored
      @forward_failure = forward_failure
    end

    def self.from_hash(data)
      return nil if data.nil?

      new(
        outcome:                  data['outcome'],
        expectation_id:           data['expectationId'],
        expectation_still_stored: data['expectationStillStored'],
        forward_failure:          ArrivalForwardFailure.from_hash(data['forwardFailure'])
      )
    end
  end

  # Why a request's forward failed, so MockServer answered 502: the same
  # facts the explain_forward_failure MCP tool gives. +reason+ is redacted as
  # the event log is and capped at 2048 characters (+reason_truncated+); when
  # +cause_named+ is false the reason is the raw error and +hint+ says what to
  # look at next.
  class ArrivalForwardFailure
    attr_reader :status_code, :reason, :reason_truncated, :cause_named, :forward_loop_suspected, :hint

    def initialize(status_code: nil, reason: nil, reason_truncated: false, cause_named: nil,
                   forward_loop_suspected: nil, hint: nil)
      @status_code = status_code
      @reason = reason
      @reason_truncated = reason_truncated
      @cause_named = cause_named
      @forward_loop_suspected = forward_loop_suspected
      @hint = hint
    end

    def self.from_hash(data)
      return nil if data.nil?

      new(
        status_code:            data['statusCode'],
        reason:                 data['reason'],
        reason_truncated:       data['reasonTruncated'] == true,
        cause_named:            data['causeNamed'],
        forward_loop_suspected: data['forwardLoopSuspected'],
        hint:                   data['hint']
      )
    end
  end

  # Set only when narration was asked for: a plain-language summary the
  # configured LLM backend wrote, or why none is available (+reason+). It is
  # AI-generated and can be wrong; the other fields are the facts.
  class ExplanationNarration
    attr_reader :available, :ai_generated, :label, :provider, :model, :text, :reason

    def initialize(available: nil, ai_generated: nil, label: nil, provider: nil, model: nil, text: nil,
                   reason: nil)
      @available = available
      @ai_generated = ai_generated
      @label = label
      @provider = provider
      @model = model
      @text = text
      @reason = reason
    end

    def self.from_hash(data)
      return nil if data.nil?

      new(
        available:    data['available'],
        ai_generated: data['aiGenerated'],
        label:        data['label'],
        provider:     data['provider'],
        model:        data['model'],
        text:         data['text'],
        reason:       data['reason']
      )
    end
  end

  # Which expectation would serve a recorded request now, why each one
  # evaluated ahead of it was passed over, which lower-priority ones it
  # shadows, and how the request was handled when it arrived. Recomputed when
  # asked for, against the current expectations, scenario states and Times.
  class RequestExplanation
    attr_reader :correlation_id, :timestamp, :recomputed, :note, :request, :at_arrival,
                :same_outcome_as_at_arrival, :expectations_changed_since, :expectations_last_changed,
                :matched_expectation, :closest_expectation_id, :skipped_expectations,
                :shadowed_expectations, :total_expectations, :evaluated_expectations, :truncated,
                :max_expectations_evaluated, :narration

    def initialize(correlation_id: nil, timestamp: nil, recomputed: nil, note: nil, request: nil,
                   at_arrival: nil, same_outcome_as_at_arrival: nil, expectations_changed_since: false,
                   expectations_last_changed: nil, matched_expectation: nil, closest_expectation_id: nil,
                   skipped_expectations: [], shadowed_expectations: [], total_expectations: 0,
                   evaluated_expectations: 0, truncated: false, max_expectations_evaluated: nil,
                   narration: nil)
      @correlation_id = correlation_id
      @timestamp = timestamp
      @recomputed = recomputed
      @note = note
      @request = request
      @at_arrival = at_arrival
      @same_outcome_as_at_arrival = same_outcome_as_at_arrival
      @expectations_changed_since = expectations_changed_since
      @expectations_last_changed = expectations_last_changed
      @matched_expectation = matched_expectation
      @closest_expectation_id = closest_expectation_id
      @skipped_expectations = skipped_expectations
      @shadowed_expectations = shadowed_expectations
      @total_expectations = total_expectations
      @evaluated_expectations = evaluated_expectations
      @truncated = truncated
      @max_expectations_evaluated = max_expectations_evaluated
      @narration = narration
    end

    def self.from_hash(data)
      return nil if data.nil?

      new(
        correlation_id:             data['correlationId'],
        timestamp:                  data['timestamp'],
        recomputed:                 data['recomputed'],
        note:                       data['note'],
        request:                    ExplainedRequest.from_hash(data['request']),
        at_arrival:                 Arrival.from_hash(data['atArrival']),
        same_outcome_as_at_arrival: data['sameOutcomeAsAtArrival'],
        expectations_changed_since: data['expectationsChangedSince'] == true,
        expectations_last_changed:  data['expectationsLastChanged'],
        matched_expectation:        ExplainedExpectation.from_hash(data['matchedExpectation']),
        closest_expectation_id:     data['closestExpectationId'],
        skipped_expectations:       ExplainedExpectation.list(data['skippedExpectations']),
        shadowed_expectations:      ExplainedExpectation.list(data['shadowedExpectations']),
        total_expectations:         data['totalExpectations'] || 0,
        evaluated_expectations:     data['evaluatedExpectations'] || 0,
        truncated:                  data['truncated'] == true,
        max_expectations_evaluated: data['maxExpectationsEvaluated'],
        narration:                  ExplanationNarration.from_hash(data['narration'])
      )
    end
  end

  # The expectation debug_mismatch found with the fewest differing fields.
  class ClosestMatch
    attr_reader :expectation_id, :matched_fields, :total_fields

    def initialize(expectation_id: nil, matched_fields: nil, total_fields: nil)
      @expectation_id = expectation_id
      @matched_fields = matched_fields
      @total_fields = total_fields
    end

    def self.from_hash(data)
      return nil if data.nil?

      new(expectation_id: data['expectationId'], matched_fields: data['matchedFields'],
          total_fields: data['totalFields'])
    end
  end

  # The result of debug_mismatch: each active expectation compared with the
  # request, and the closest match (nil when every expectation matches).
  class DebugMismatchResult
    attr_reader :correlation_id, :timestamp, :total_expectations, :evaluated_expectations,
                :truncated, :max_expectations_evaluated, :closest_match, :results

    def initialize(correlation_id: nil, timestamp: nil, total_expectations: 0, evaluated_expectations: 0,
                   truncated: false, max_expectations_evaluated: nil, closest_match: nil, results: [])
      @correlation_id = correlation_id
      @timestamp = timestamp
      @total_expectations = total_expectations
      @evaluated_expectations = evaluated_expectations
      @truncated = truncated
      @max_expectations_evaluated = max_expectations_evaluated
      @closest_match = closest_match
      @results = results
    end

    def self.from_hash(data)
      return nil if data.nil?

      new(
        correlation_id:             data['correlationId'],
        timestamp:                  data['timestamp'],
        total_expectations:         data['totalExpectations'] || 0,
        evaluated_expectations:     data['evaluatedExpectations'] || 0,
        truncated:                  data['truncated'] == true,
        max_expectations_evaluated: data['maxExpectationsEvaluated'],
        closest_match:              ClosestMatch.from_hash(data['closestMatch']),
        results:                    ExplainedExpectation.list(data['results'])
      )
    end
  end

  # One recent unmatched request and its closest expectations, closest first.
  class UnmatchedRequest
    attr_reader :timestamp, :method, :path, :closest_expectations, :total_expectations_evaluated

    def initialize(timestamp: nil, method: nil, path: nil, closest_expectations: [],
                   total_expectations_evaluated: 0)
      @timestamp = timestamp
      @method = method
      @path = path
      @closest_expectations = closest_expectations
      @total_expectations_evaluated = total_expectations_evaluated
    end

    def self.from_hash(data)
      return nil if data.nil?

      new(
        timestamp:                    data['timestamp'],
        method:                       data['method'],
        path:                         data['path'],
        closest_expectations:         ExplainedExpectation.list(data['closestExpectations']),
        total_expectations_evaluated: data['totalExpectationsEvaluated'] || 0
      )
    end
  end

  # The result of explain_unmatched.
  class ExplainUnmatchedResult
    attr_reader :correlation_id, :timestamp, :unmatched_request_count, :truncated, :unmatched_requests,
                :narration

    def initialize(correlation_id: nil, timestamp: nil, unmatched_request_count: 0, truncated: false,
                   unmatched_requests: [], narration: nil)
      @correlation_id = correlation_id
      @timestamp = timestamp
      @unmatched_request_count = unmatched_request_count
      @truncated = truncated
      @unmatched_requests = unmatched_requests
      @narration = narration
    end

    def self.from_hash(data)
      return nil if data.nil?

      new(
        correlation_id:          data['correlationId'],
        timestamp:               data['timestamp'],
        unmatched_request_count: data['unmatchedRequestCount'] || 0,
        truncated:               data['truncated'] == true,
        unmatched_requests:      (data['unmatchedRequests'] || []).map { |r| UnmatchedRequest.from_hash(r) },
        narration:               ExplanationNarration.from_hash(data['narration'])
      )
    end
  end

  # Alias matching the Python client
  RequestDefinition = HttpRequest

  constants.map { |name| const_get(name) }.each do |model|
    model.singleton_class.prepend(SymbolKeyTolerantFromHash) if model.is_a?(Class) && model.respond_to?(:from_hash)
  end
end
