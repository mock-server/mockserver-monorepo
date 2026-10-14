from __future__ import annotations

from dataclasses import dataclass, field
from typing import Any, Union


_FIELD_MAP = {
    "error_status": "errorStatus",
    "error_probability": "errorProbability",
    "drop_connection_probability": "dropConnectionProbability",
    "retry_after": "retryAfter",
    "succeed_first": "succeedFirst",
    "fail_request_count": "failRequestCount",
    "outage_after_millis": "outageAfterMillis",
    "outage_duration_millis": "outageDurationMillis",
    "truncate_body_at_fraction": "truncateBodyAtFraction",
    "malformed_body": "malformedBody",
    "slow_response_chunk_size": "slowResponseChunkSize",
    "slow_response_chunk_delay": "slowResponseChunkDelay",
    "quota_name": "quotaName",
    "quota_limit": "quotaLimit",
    "quota_window_millis": "quotaWindowMillis",
    "quota_error_status": "quotaErrorStatus",
    "degradation_ramp_millis": "degradationRampMillis",
    "status_code": "statusCode",
    "reason_phrase": "reasonPhrase",
    "keep_alive": "keepAlive",
    "respond_before_body": "respondBeforeBody",
    "query_string_parameters": "queryStringParameters",
    "path_parameters": "pathParameters",
    "socket_address": "socketAddress",
    "time_unit": "timeUnit",
    "time_to_live": "timeToLive",
    "remaining_times": "remainingTimes",
    "close_socket": "closeSocket",
    "close_socket_delay": "closeSocketDelay",
    "suppress_content_length_header": "suppressContentLengthHeader",
    "content_length_header_override": "contentLengthHeaderOverride",
    "suppress_connection_header": "suppressConnectionHeader",
    "keep_alive_override": "keepAliveOverride",
    "connection_options": "connectionOptions",
    "callback_class": "callbackClass",
    "client_id": "clientId",
    "response_callback": "responseCallback",
    "drop_connection": "dropConnection",
    "response_bytes": "responseBytes",
    "stream_error": "streamError",
    "http_request": "httpRequest",
    "http_response": "httpResponse",
    "http_response_template": "httpResponseTemplate",
    "http_response_class_callback": "httpResponseClassCallback",
    "http_response_object_callback": "httpResponseObjectCallback",
    "http_forward": "httpForward",
    "http_forward_template": "httpForwardTemplate",
    "http_forward_class_callback": "httpForwardClassCallback",
    "http_forward_object_callback": "httpForwardObjectCallback",
    "http_override_forwarded_request": "httpOverrideForwardedRequest",
    "http_error": "httpError",
    "template_type": "templateType",
    "template_file": "templateFile",
    "file_path": "filePath",
    "base64_bytes": "base64Bytes",
    "not_body": "not",
    "content_type": "contentType",
    "at_least": "atLeast",
    "at_most": "atMost",
    "expectation_id": "expectationId",
    "expectation_ids": "expectationIds",
    "http_requests": "httpRequests",
    "spec_url_or_payload": "specUrlOrPayload",
    "operations_and_responses": "operationsAndResponses",
    "operation_id": "operationId",
    "request_modifier": "requestModifier",
    "response_modifier": "responseModifier",
    "maximum_number_of_request_to_return_in_verification_failure": "maximumNumberOfRequestToReturnInVerificationFailure",
    "http_sse_response": "httpSseResponse",
    "http_websocket_response": "httpWebSocketResponse",
    "before_actions": "beforeActions",
    "after_actions": "afterActions",
    "base_path": "basePath",
    "id_field": "idField",
    "id_strategy": "idStrategy",
    "initial_data": "initialData",
    "http_class_callback": "httpClassCallback",
    "http_object_callback": "httpObjectCallback",
    "failure_policy": "failurePolicy",
    "http_llm_response": "httpLlmResponse",
    "response_weights": "responseWeights",
    "switch_after": "switchAfter",
    "cross_protocol_scenarios": "crossProtocolScenarios",
    "match_pattern": "matchPattern",
    "scenario_name": "scenarioName",
    "target_state": "targetState",
}


def _to_camel(snake_str: str) -> str:
    if snake_str in _FIELD_MAP:
        return _FIELD_MAP[snake_str]
    parts = snake_str.split("_")
    return parts[0] + "".join(p.capitalize() for p in parts[1:])


def _strip_none(d: dict) -> dict:
    return {k: v for k, v in d.items() if v is not None}


def _serialize_value(value: Any) -> Any:
    if hasattr(value, "to_dict"):
        return value.to_dict()
    if isinstance(value, list):
        return [_serialize_value(item) for item in value]
    return value


_REVERSE_FIELD_MAP = {v: k for k, v in _FIELD_MAP.items()}


def _from_camel(camel_str: str) -> str:
    if camel_str in _REVERSE_FIELD_MAP:
        return _REVERSE_FIELD_MAP[camel_str]
    result = []
    for ch in camel_str:
        if ch.isupper():
            result.append("_")
            result.append(ch.lower())
        else:
            result.append(ch)
    return "".join(result)


def _serialize_body(body: Body | str | dict | None) -> Any:
    if body is None:
        return None
    if isinstance(body, str):
        return body
    if isinstance(body, dict):
        return body
    if hasattr(body, "to_dict"):
        return body.to_dict()
    return body


_BODY_TYPES = {"STRING", "JSON", "REGEX", "XML", "BINARY", "JSON_SCHEMA", "JSON_PATH", "XPATH", "XML_SCHEMA", "JSON_RPC", "GRAPHQL", "FILE"}


def _deserialize_body(data: Any) -> Body | JsonRpcBody | str | dict | None:
    if data is None:
        return None
    if isinstance(data, str):
        return data
    if isinstance(data, dict):
        if data.get("type") == "JSON_RPC":
            return JsonRpcBody(
                method=data.get("method", ""),
                params_schema=data.get("paramsSchema"),
                not_body=data.get("not", False),
                optional=data.get("optional", False),
            )
        if data.get("type") == "JSON_PATH":
            return JsonPathBody(
                json_path=data.get("jsonPath", ""),
                not_body=data.get("not", False),
                optional=data.get("optional", False),
            )
        if data.get("type") == "ALL_OF":
            return AllOfBody.from_dict(data)
        if data.get("type") == "XPATH":
            return XPathBody.from_dict(data)
        if data.get("type") == "XML_SCHEMA":
            return XmlSchemaBody.from_dict(data)
        if data.get("type") == "JSON_SCHEMA":
            return JsonSchemaBody.from_dict(data)
        if data.get("type") == "PARAMETERS":
            return ParameterBody.from_dict(data)
        if data.get("type") == "MULTIPART":
            return MultipartBody.from_dict(data)
        if data.get("type") == "WASM":
            return WasmBody.from_dict(data)
        # An XML body carrying an "xml" field is the canonical MockServer wire form
        # -> XmlBody. Legacy XML bodies carrying "string" fall through to the generic
        # Body deserializer below (unchanged behaviour).
        if data.get("type") == "XML" and "xml" in data:
            return XmlBody.from_dict(data)
        # A REGEX body carrying a "regex" field is the canonical MockServer wire
        # form -> RegexBody. Legacy REGEX bodies that carry "string" fall through
        # to the generic Body deserializer below (unchanged behaviour).
        if data.get("type") == "REGEX" and "regex" in data:
            return RegexBody.from_dict(data)
        if data.get("type") == "GRAPHQL":
            return GraphQLBody(
                query=data.get("query", ""),
                operation_name=data.get("operationName"),
                variables_schema=data.get("variablesSchema"),
                selection_set_match_type=data.get("selectionSetMatchType"),
                fields=data.get("fields"),
                schema=data.get("schema"),
                not_body=data.get("not", False),
                optional=data.get("optional", False),
            )
        if data.get("type") in _BODY_TYPES:
            return Body.from_dict(data)
        return data
    return data


def _serialize_key_multi_values(
    items: list[KeyToMultiValue] | None, key_match_style: str | None = None, field_name: str = ""
) -> list[dict] | dict | None:
    # keyMatchStyle can only be carried by the {name: [values]} object form.
    if key_match_style is not None:
        result: dict = {"keyMatchStyle": key_match_style}
        for item in items or []:
            if not isinstance(item.name, str):
                raise ValueError(
                    f"{field_name} with keyMatchStyle needs plain string names, got {item.name!r}"
                )
            result.setdefault(item.name, []).extend(item.values)
        return result
    if items is None:
        return None
    return [item.to_dict() for item in items]


def _key_match_style(value: Any) -> str | None:
    return value.get("keyMatchStyle") if isinstance(value, dict) else None


def _coerce_key_multi_values(value: Any) -> list[KeyToMultiValue] | None:
    # Accept the forms the server's JSON accepts: {name: [values]} or
    # {name: value}, a list of {"name": .., "values": [..]} dicts, or a list
    # of KeyToMultiValue.
    if value is None:
        return None
    if isinstance(value, dict):
        return _deserialize_key_multi_values(value)
    if not isinstance(value, (list, tuple)):
        raise TypeError(f"expected a dict or a list of KeyToMultiValue, got {type(value).__name__}")
    if isinstance(value, list) and not any(isinstance(item, dict) for item in value):
        return value
    return [KeyToMultiValue.from_dict(item) if isinstance(item, dict) else item for item in value]


def _coerce_cookies(value: Any) -> list[KeyToMultiValue] | None:
    # Cookies as {name: value}, a list of {"name": .., "value": ..} dicts, or
    # a list of KeyToMultiValue.
    if value is None:
        return None
    if isinstance(value, dict):
        return _deserialize_cookies(value)
    if not isinstance(value, (list, tuple)):
        raise TypeError(f"expected a dict or a list of KeyToMultiValue, got {type(value).__name__}")
    if isinstance(value, list) and not any(isinstance(item, dict) for item in value):
        return value
    result = []
    for item in value:
        if isinstance(item, dict):
            values = [item["value"]] if "value" in item else item.get("values", [])
            item = KeyToMultiValue(name=item.get("name", ""), values=values)
        result.append(item)
    return result


def _serialize_key_multi_values_map(items: list[KeyToMultiValue] | None) -> dict | None:
    # Multi-value {name: [values]} object-map form. MockServer's multipart body
    # deserializer only recognises fields/filenames/partContentTypes in this map
    # form (an array-valued "fields" is diverted to the GraphQL handler and the
    # multipart matcher is silently dropped), so this is the canonical wire shape.
    if items is None:
        return None
    return {item.name: list(item.values) for item in items}


def _deserialize_key_multi_values(data: list | dict | None) -> list[KeyToMultiValue] | None:
    if data is None:
        return None
    if isinstance(data, dict):
        return [
            KeyToMultiValue(name=k, values=v if isinstance(v, list) else [v])
            for k, v in data.items()
            if k != "keyMatchStyle"
        ]
    result = []
    for item in data:
        if isinstance(item, dict):
            result.append(KeyToMultiValue.from_dict(item))
        elif isinstance(item, str):
            result.append(KeyToMultiValue(name=item, values=[]))
        else:
            result.append(KeyToMultiValue.from_dict(item))
    return result


def _serialize_cookies(items: list[KeyToMultiValue] | None) -> dict | None:
    # Unlike headers / query parameters, MockServer represents cookies as a
    # single-value {name: value} object map, not a [{name, values}] array.
    if items is None:
        return None
    return {item.name: (item.values[0] if item.values else "") for item in items}


def _deserialize_cookies(data: dict | list | None) -> list[KeyToMultiValue] | None:
    if data is None:
        return None
    if isinstance(data, dict):
        return [
            KeyToMultiValue(name=k, values=v if isinstance(v, list) else [v])
            for k, v in data.items()
        ]
    # tolerate the legacy array form on read
    return _deserialize_key_multi_values(data)


@dataclass
class DelayDistribution:
    type: str | None = None
    min: int | None = None
    max: int | None = None
    median: int | None = None
    p99: int | None = None
    mean: int | None = None
    std_dev: int | None = None

    def to_dict(self) -> dict:
        return _strip_none({
            "type": self.type,
            "min": self.min,
            "max": self.max,
            "median": self.median,
            "p99": self.p99,
            "mean": self.mean,
            "stdDev": self.std_dev,
        })

    @classmethod
    def from_dict(cls, data: dict) -> DelayDistribution:
        if data is None:
            return None
        return cls(
            type=data.get("type"),
            min=data.get("min"),
            max=data.get("max"),
            median=data.get("median"),
            p99=data.get("p99"),
            mean=data.get("mean"),
            std_dev=data.get("stdDev"),
        )


@dataclass
class Delay:
    time_unit: str = "MILLISECONDS"
    value: int = 0
    distribution: DelayDistribution | None = None
    # Rendered against the request and read as milliseconds; falls back to time_unit/value.
    template: str | None = None
    # VELOCITY or MUSTACHE.
    template_type: str | None = None

    def to_dict(self) -> dict:
        return _strip_none({
            "timeUnit": self.time_unit,
            "value": self.value,
            "distribution": self.distribution.to_dict() if self.distribution else None,
            "template": self.template,
            "templateType": self.template_type,
        })

    @classmethod
    def from_dict(cls, data: dict) -> Delay:
        if data is None:
            return None
        dist_data = data.get("distribution")
        return cls(
            time_unit=data.get("timeUnit", "MILLISECONDS"),
            value=data.get("value", 0),
            distribution=DelayDistribution.from_dict(dist_data) if dist_data else None,
            template=data.get("template"),
            template_type=data.get("templateType"),
        )


@dataclass
class Times:
    remaining_times: int | None = None
    unlimited: bool | None = None

    def to_dict(self) -> dict:
        return _strip_none({
            "remainingTimes": self.remaining_times,
            "unlimited": self.unlimited,
        })

    @classmethod
    def from_dict(cls, data: dict) -> Times:
        if data is None:
            return None
        return cls(
            remaining_times=data.get("remainingTimes"),
            unlimited=data.get("unlimited"),
        )


def _times_unlimited() -> Times:
    return Times(unlimited=True)


def _times_exactly(count: int) -> Times:
    return Times(remaining_times=count, unlimited=False)


Times.unlimited = staticmethod(_times_unlimited)
Times.exactly = staticmethod(_times_exactly)


@dataclass
class TimeToLive:
    time_unit: str | None = None
    time_to_live: int | None = None
    unlimited: bool | None = None

    def to_dict(self) -> dict:
        return _strip_none({
            "timeUnit": self.time_unit,
            "timeToLive": self.time_to_live,
            "unlimited": self.unlimited,
        })

    @classmethod
    def from_dict(cls, data: dict) -> TimeToLive:
        if data is None:
            return None
        return cls(
            time_unit=data.get("timeUnit"),
            time_to_live=data.get("timeToLive"),
            unlimited=data.get("unlimited"),
        )


def _ttl_unlimited() -> TimeToLive:
    return TimeToLive(unlimited=True)


def _ttl_exactly(time_to_live: int, time_unit: str) -> TimeToLive:
    return TimeToLive(
        time_unit=time_unit,
        time_to_live=time_to_live,
        unlimited=False,
    )


TimeToLive.unlimited = staticmethod(_ttl_unlimited)
TimeToLive.exactly = staticmethod(_ttl_exactly)


@dataclass
class HttpChaosProfile:
    error_status: int | None = None
    error_probability: float | None = None
    drop_connection_probability: float | None = None
    retry_after: str | None = None
    latency: Delay | None = None
    seed: int | None = None
    succeed_first: int | None = None
    fail_request_count: int | None = None
    outage_after_millis: int | None = None
    outage_duration_millis: int | None = None
    truncate_body_at_fraction: float | None = None
    malformed_body: bool | None = None
    slow_response_chunk_size: int | None = None
    slow_response_chunk_delay: Delay | None = None
    quota_name: str | None = None
    quota_limit: int | None = None
    quota_window_millis: int | None = None
    quota_error_status: int | None = None
    degradation_ramp_millis: int | None = None
    # GraphQL error-injection: rewrite a matched response as a GraphQL error envelope.
    graphql_errors: bool | None = None
    graphql_error_message: str | None = None
    graphql_error_code: str | None = None
    graphql_nullify_data: bool | None = None

    def to_dict(self) -> dict:
        return _strip_none({
            "errorStatus": self.error_status,
            "errorProbability": self.error_probability,
            "dropConnectionProbability": self.drop_connection_probability,
            "retryAfter": self.retry_after,
            "latency": self.latency.to_dict() if self.latency else None,
            "seed": self.seed,
            "succeedFirst": self.succeed_first,
            "failRequestCount": self.fail_request_count,
            "outageAfterMillis": self.outage_after_millis,
            "outageDurationMillis": self.outage_duration_millis,
            "truncateBodyAtFraction": self.truncate_body_at_fraction,
            "malformedBody": self.malformed_body,
            "slowResponseChunkSize": self.slow_response_chunk_size,
            "slowResponseChunkDelay": self.slow_response_chunk_delay.to_dict() if self.slow_response_chunk_delay else None,
            "quotaName": self.quota_name,
            "quotaLimit": self.quota_limit,
            "quotaWindowMillis": self.quota_window_millis,
            "quotaErrorStatus": self.quota_error_status,
            "degradationRampMillis": self.degradation_ramp_millis,
            "graphqlErrors": self.graphql_errors,
            "graphqlErrorMessage": self.graphql_error_message,
            "graphqlErrorCode": self.graphql_error_code,
            "graphqlNullifyData": self.graphql_nullify_data,
        })

    @classmethod
    def from_dict(cls, data: dict) -> HttpChaosProfile:
        if data is None:
            return None
        return cls(
            error_status=data.get("errorStatus"),
            error_probability=data.get("errorProbability"),
            drop_connection_probability=data.get("dropConnectionProbability"),
            retry_after=data.get("retryAfter"),
            latency=Delay.from_dict(data.get("latency")),
            seed=data.get("seed"),
            succeed_first=data.get("succeedFirst"),
            fail_request_count=data.get("failRequestCount"),
            outage_after_millis=data.get("outageAfterMillis"),
            outage_duration_millis=data.get("outageDurationMillis"),
            truncate_body_at_fraction=data.get("truncateBodyAtFraction"),
            malformed_body=data.get("malformedBody"),
            slow_response_chunk_size=data.get("slowResponseChunkSize"),
            slow_response_chunk_delay=Delay.from_dict(data.get("slowResponseChunkDelay")),
            quota_name=data.get("quotaName"),
            quota_limit=data.get("quotaLimit"),
            quota_window_millis=data.get("quotaWindowMillis"),
            quota_error_status=data.get("quotaErrorStatus"),
            degradation_ramp_millis=data.get("degradationRampMillis"),
            graphql_errors=data.get("graphqlErrors"),
            graphql_error_message=data.get("graphqlErrorMessage"),
            graphql_error_code=data.get("graphqlErrorCode"),
            graphql_nullify_data=data.get("graphqlNullifyData"),
        )


@dataclass
class KeyToMultiValue:
    name: str = ""
    values: list[str] = field(default_factory=list)

    # name and values are always emitted (not stripped via _strip_none) because
    # the MockServer protocol requires both fields on every header/cookie/parameter.
    def to_dict(self) -> dict:
        return {
            "name": self.name,
            "values": self.values,
        }

    @classmethod
    def from_dict(cls, data: dict) -> KeyToMultiValue:
        if data is None:
            return None
        return cls(
            name=data.get("name", ""),
            values=data.get("values", []),
        )


# What HttpRequest and HttpResponse accept for a keyToMultiValue collection
# ({name: [values]}, {name: value}, a list of dicts or of KeyToMultiValue) and
# for cookies ({name: value}, a list of dicts or of KeyToMultiValue).
KeyToMultiValuesInput = Union[list[KeyToMultiValue], list[dict[str, Any]], dict[str, Any]]
CookiesInput = Union[list[KeyToMultiValue], list[dict[str, Any]], dict[str, Any]]


@dataclass
class Body:
    type: str | None = None
    string: str | None = None
    json: Any | None = None
    base64_bytes: str | None = None
    not_body: bool | None = None
    content_type: str | None = None
    charset: str | None = None
    file_path: str | None = None
    template_type: str | None = None
    optional: bool | None = None
    # STRING body matcher: treat ``string`` as a substring rather than an exact match.
    sub_string: bool | None = None
    # JSON body matcher: how strictly the JSON must match (STRICT / ONLY_MATCHING_FIELDS).
    match_type: str | None = None
    # JSON body matcher: compare numbers as strings (avoids float/int coercion differences).
    match_numbers_as_strings: bool | None = None

    def to_dict(self) -> dict:
        result = {}
        if self.not_body is not None:
            result["not"] = self.not_body
        if self.optional is not None:
            result["optional"] = self.optional
        if self.type is not None:
            result["type"] = self.type
        if self.string is not None:
            result["string"] = self.string
        if self.json is not None:
            result["json"] = self.json
        if self.base64_bytes is not None:
            result["base64Bytes"] = self.base64_bytes
        if self.content_type is not None:
            result["contentType"] = self.content_type
        if self.charset is not None:
            result["charset"] = self.charset
        if self.file_path is not None:
            result["filePath"] = self.file_path
        if self.template_type is not None:
            result["templateType"] = self.template_type
        if self.sub_string is not None:
            result["subString"] = self.sub_string
        if self.match_type is not None:
            result["matchType"] = self.match_type
        if self.match_numbers_as_strings is not None:
            result["matchNumbersAsStrings"] = self.match_numbers_as_strings
        return result

    @classmethod
    def from_dict(cls, data: dict) -> Body:
        if data is None:
            return None
        return cls(
            type=data.get("type"),
            string=data.get("string"),
            json=data.get("json"),
            base64_bytes=data.get("base64Bytes"),
            not_body=data.get("not"),
            content_type=data.get("contentType"),
            charset=data.get("charset"),
            file_path=data.get("filePath"),
            template_type=data.get("templateType"),
            optional=data.get("optional"),
            sub_string=data.get("subString"),
            match_type=data.get("matchType"),
            match_numbers_as_strings=data.get("matchNumbersAsStrings"),
        )


@dataclass
class JsonRpcBody:
    method: str = ""
    params_schema: str | None = None
    not_body: bool = False
    optional: bool = False

    def to_dict(self) -> dict:
        result: dict = {"type": "JSON_RPC", "method": self.method}
        if self.params_schema is not None:
            result["paramsSchema"] = self.params_schema
        if self.not_body:
            result["not"] = True
        if self.optional:
            result["optional"] = True
        return result


@dataclass
class JsonPathBody:
    json_path: str = ""
    not_body: bool = False
    optional: bool = False

    def to_dict(self) -> dict:
        result: dict = {}
        if self.not_body:
            result["not"] = True
        if self.optional:
            result["optional"] = True
        result["type"] = "JSON_PATH"
        result["jsonPath"] = self.json_path
        return result


@dataclass
class GraphQLBody:
    query: str = ""
    operation_name: str | None = None
    variables_schema: str | None = None
    # AST match strictness (NORMALISED_STRING / AST_EXACT / AST_SUBSET).
    selection_set_match_type: str | None = None
    # Restrict matching/synthesis to these top-level selection-set field names.
    fields: list[str] | None = None
    # SDL text or introspection JSON used to synthesize schema-valid responses.
    schema: str | None = None
    not_body: bool = False
    optional: bool = False

    def to_dict(self) -> dict:
        result: dict = {"type": "GRAPHQL", "query": self.query}
        if self.operation_name is not None:
            result["operationName"] = self.operation_name
        if self.variables_schema is not None:
            result["variablesSchema"] = self.variables_schema
        if self.selection_set_match_type is not None:
            result["selectionSetMatchType"] = self.selection_set_match_type
        if self.fields is not None:
            result["fields"] = self.fields
        if self.schema is not None:
            result["schema"] = self.schema
        if self.not_body:
            result["not"] = True
        if self.optional:
            result["optional"] = True
        return result


@dataclass
class RegexBody:
    # Serialises to {"type": "REGEX", "regex": <value>} — the field name MockServer
    # expects for a regex body matcher. Use this for ALL_OF sub-bodies and anywhere
    # the exact wire shape matters. Body.regex(...) is a convenience factory that
    # returns an instance of this class.
    regex: str = ""
    not_body: bool = False
    optional: bool = False

    def to_dict(self) -> dict:
        result: dict = {}
        if self.not_body:
            result["not"] = True
        if self.optional:
            result["optional"] = True
        result["type"] = "REGEX"
        result["regex"] = self.regex
        return result

    @classmethod
    def from_dict(cls, data: dict) -> RegexBody:
        if data is None:
            return None
        return cls(
            regex=data.get("regex", ""),
            not_body=bool(data.get("not", False)),
            optional=bool(data.get("optional", False)),
        )


@dataclass
class AllOfBody:
    # Composite body matcher that requires ALL sub-bodies to match. Each sub-body is
    # any existing body matcher (Body, JsonPathBody, RegexBody, GraphQLBody, a raw
    # dict, ...) serialised via the same _serialize_body path.
    # Serialises to {"type": "ALL_OF", "bodyAllOf": [ <body>, <body>, ... ]}.
    body_all_of: list = field(default_factory=list)
    not_body: bool = False
    optional: bool = False

    def to_dict(self) -> dict:
        result: dict = {}
        if self.not_body:
            result["not"] = True
        if self.optional:
            result["optional"] = True
        result["type"] = "ALL_OF"
        result["bodyAllOf"] = [_serialize_body(b) for b in self.body_all_of]
        return result

    @classmethod
    def from_dict(cls, data: dict) -> AllOfBody:
        if data is None:
            return None
        sub = data.get("bodyAllOf") or []
        return cls(
            body_all_of=[_deserialize_body(b) for b in sub],
            not_body=bool(data.get("not", False)),
            optional=bool(data.get("optional", False)),
        )


@dataclass
class XmlBody:
    # Serialises to {"type": "XML", "xml": <value>} — the wire field name MockServer
    # expects for an XML body matcher (distinct from the "string" key used by a STRING
    # body). Use this whenever the exact XML wire shape matters.
    xml: str = ""
    content_type: str | None = None
    not_body: bool = False
    optional: bool = False

    def to_dict(self) -> dict:
        result: dict = {}
        if self.not_body:
            result["not"] = True
        if self.optional:
            result["optional"] = True
        result["type"] = "XML"
        result["xml"] = self.xml
        if self.content_type is not None:
            result["contentType"] = self.content_type
        return result

    @classmethod
    def from_dict(cls, data: dict) -> XmlBody:
        if data is None:
            return None
        return cls(
            xml=data.get("xml", ""),
            content_type=data.get("contentType"),
            not_body=bool(data.get("not", False)),
            optional=bool(data.get("optional", False)),
        )


@dataclass
class XPathBody:
    # Serialises to {"type": "XPATH", "xpath": <value>, "namespacePrefixes": {...}}.
    # ``namespace_prefixes`` maps a prefix to its namespace URI and is omitted when empty.
    xpath: str = ""
    namespace_prefixes: dict | None = None
    not_body: bool = False
    optional: bool = False

    def to_dict(self) -> dict:
        result: dict = {}
        if self.not_body:
            result["not"] = True
        if self.optional:
            result["optional"] = True
        result["type"] = "XPATH"
        result["xpath"] = self.xpath
        if self.namespace_prefixes is not None:
            result["namespacePrefixes"] = dict(self.namespace_prefixes)
        return result

    @classmethod
    def from_dict(cls, data: dict) -> XPathBody:
        if data is None:
            return None
        namespace_prefixes = data.get("namespacePrefixes")
        return cls(
            xpath=data.get("xpath", ""),
            namespace_prefixes=dict(namespace_prefixes) if namespace_prefixes is not None else None,
            not_body=bool(data.get("not", False)),
            optional=bool(data.get("optional", False)),
        )


@dataclass
class XmlSchemaBody:
    # Serialises to {"type": "XML_SCHEMA", "xmlSchema": <schema>}. ``xml_schema`` carries
    # the XSD payload as a string.
    xml_schema: str = ""
    not_body: bool = False
    optional: bool = False

    def to_dict(self) -> dict:
        result: dict = {}
        if self.not_body:
            result["not"] = True
        if self.optional:
            result["optional"] = True
        result["type"] = "XML_SCHEMA"
        result["xmlSchema"] = self.xml_schema
        return result

    @classmethod
    def from_dict(cls, data: dict) -> XmlSchemaBody:
        if data is None:
            return None
        return cls(
            xml_schema=data.get("xmlSchema", ""),
            not_body=bool(data.get("not", False)),
            optional=bool(data.get("optional", False)),
        )


@dataclass
class JsonSchemaBody:
    # Serialises to {"type": "JSON_SCHEMA", "jsonSchema": <schema>, "parameterStyles": {...}}.
    # ``json_schema`` is the JSON Schema payload as a parsed object (dict) — MockServer emits
    # the schema as a JSON tree, not a string. ``parameter_styles`` is optional.
    json_schema: Any = None
    parameter_styles: dict | None = None
    not_body: bool = False
    optional: bool = False

    def to_dict(self) -> dict:
        result: dict = {}
        if self.not_body:
            result["not"] = True
        if self.optional:
            result["optional"] = True
        result["type"] = "JSON_SCHEMA"
        result["jsonSchema"] = self.json_schema
        if self.parameter_styles is not None:
            result["parameterStyles"] = dict(self.parameter_styles)
        return result

    @classmethod
    def from_dict(cls, data: dict) -> JsonSchemaBody:
        if data is None:
            return None
        parameter_styles = data.get("parameterStyles")
        return cls(
            json_schema=data.get("jsonSchema"),
            parameter_styles=dict(parameter_styles) if parameter_styles is not None else None,
            not_body=bool(data.get("not", False)),
            optional=bool(data.get("optional", False)),
        )


@dataclass
class ParameterBody:
    # Matches a form-url-encoded / query body by its parameters. MockServer accepts
    # both a {name: [values]} object-map (its canonical emission) and a
    # [{name, values}] array wire form; ``parameters_as_map`` records which form was
    # read so the round-trip is byte-identical. Builder-created bodies default to the
    # array form.
    parameters: list[KeyToMultiValue] | None = None
    not_body: bool = False
    optional: bool = False
    parameters_as_map: bool = False

    def to_dict(self) -> dict:
        result: dict = {}
        if self.not_body:
            result["not"] = True
        if self.optional:
            result["optional"] = True
        result["type"] = "PARAMETERS"
        if self.parameters is not None:
            result["parameters"] = (
                _serialize_key_multi_values_map(self.parameters)
                if self.parameters_as_map
                else _serialize_key_multi_values(self.parameters)
            )
        return result

    @classmethod
    def from_dict(cls, data: dict) -> ParameterBody:
        if data is None:
            return None
        raw = data.get("parameters")
        return cls(
            parameters=_deserialize_key_multi_values(raw),
            not_body=bool(data.get("not", False)),
            optional=bool(data.get("optional", False)),
            parameters_as_map=isinstance(raw, dict),
        )


@dataclass
class MultipartBody:
    # Serialises to {"type": "MULTIPART", "fields": {name: [values]}, "filenames":
    # {..}, "partContentTypes": {..}} — each a {name: [values]} object map (the
    # canonical MockServer wire shape; the array form is silently dropped server-side).
    # Matches a multipart form-data body by its part fields, filenames and per-part
    # content types. from_dict accepts both the object-map and the legacy array form.
    fields: list[KeyToMultiValue] | None = None
    filenames: list[KeyToMultiValue] | None = None
    part_content_types: list[KeyToMultiValue] | None = None
    not_body: bool = False
    optional: bool = False

    def to_dict(self) -> dict:
        result: dict = {}
        if self.not_body:
            result["not"] = True
        if self.optional:
            result["optional"] = True
        result["type"] = "MULTIPART"
        if self.fields is not None:
            result["fields"] = _serialize_key_multi_values_map(self.fields)
        if self.filenames is not None:
            result["filenames"] = _serialize_key_multi_values_map(self.filenames)
        if self.part_content_types is not None:
            result["partContentTypes"] = _serialize_key_multi_values_map(self.part_content_types)
        return result

    @classmethod
    def from_dict(cls, data: dict) -> MultipartBody:
        if data is None:
            return None
        return cls(
            fields=_deserialize_key_multi_values(data.get("fields")),
            filenames=_deserialize_key_multi_values(data.get("filenames")),
            part_content_types=_deserialize_key_multi_values(data.get("partContentTypes")),
            not_body=bool(data.get("not", False)),
            optional=bool(data.get("optional", False)),
        )


@dataclass
class WasmBody:
    # Serialises to {"type": "WASM", "moduleName": <name>}. Delegates matching to a WASM
    # module registered in the server's WasmStore (exports match(ptr, len) -> i32).
    module_name: str = ""
    not_body: bool = False
    optional: bool = False

    def to_dict(self) -> dict:
        result: dict = {}
        if self.not_body:
            result["not"] = True
        if self.optional:
            result["optional"] = True
        result["type"] = "WASM"
        result["moduleName"] = self.module_name
        return result

    @classmethod
    def from_dict(cls, data: dict) -> WasmBody:
        if data is None:
            return None
        return cls(
            module_name=data.get("moduleName", ""),
            not_body=bool(data.get("not", False)),
            optional=bool(data.get("optional", False)),
        )


def _body_string(value: str) -> Body:
    return Body(type="STRING", string=value)


def _body_json(value: Any) -> Body:
    return Body(type="JSON", json=value)


def _body_regex(value: str) -> RegexBody:
    # Emits {"type": "REGEX", "regex": <value>} — the wire form MockServer's
    # BodyDTODeserializer parses as a regex matcher. (Emitting a "string" key
    # instead makes the server treat it as a literal STRING body.)
    return RegexBody(regex=value)


def _body_exact(value: str) -> Body:
    return Body(type="STRING", string=value)


def _body_xml(value: str) -> Body:
    return Body(type="XML", string=value)


def _body_xml_matcher(value: str, content_type: str | None = None) -> XmlBody:
    # Emits {"type": "XML", "xml": <value>} — the wire form MockServer's
    # XmlBodyDTODeserializer parses as an XML body matcher (the "xml" key, distinct
    # from the legacy Body.xml(...) which emits a "string" key).
    return XmlBody(xml=value, content_type=content_type)


def _body_xpath(xpath: str, namespace_prefixes: dict | None = None) -> XPathBody:
    return XPathBody(xpath=xpath, namespace_prefixes=namespace_prefixes)


def _body_xml_schema(xml_schema: str) -> XmlSchemaBody:
    return XmlSchemaBody(xml_schema=xml_schema)


def _body_json_schema(json_schema: Any, parameter_styles: dict | None = None) -> JsonSchemaBody:
    return JsonSchemaBody(json_schema=json_schema, parameter_styles=parameter_styles)


def _body_parameters(parameters: list[KeyToMultiValue] | None = None) -> ParameterBody:
    return ParameterBody(parameters=parameters)


def _body_multipart(
    fields: list[KeyToMultiValue] | None = None,
    filenames: list[KeyToMultiValue] | None = None,
    part_content_types: list[KeyToMultiValue] | None = None,
) -> MultipartBody:
    return MultipartBody(fields=fields, filenames=filenames, part_content_types=part_content_types)


def _body_wasm(module_name: str) -> WasmBody:
    return WasmBody(module_name=module_name)


def _body_json_rpc(method: str, params_schema: str | None = None) -> JsonRpcBody:
    return JsonRpcBody(method=method, params_schema=params_schema)


def _body_graphql(query: str, operation_name: str | None = None, variables_schema: str | None = None) -> GraphQLBody:
    return GraphQLBody(query=query, operation_name=operation_name, variables_schema=variables_schema)


def _body_file(file_path: str, content_type: str | None = None, template_type: str | None = None) -> Body:
    return Body(type="FILE", file_path=file_path, content_type=content_type, template_type=template_type)


Body.string = staticmethod(_body_string)
Body.json = staticmethod(_body_json)
Body.regex = staticmethod(_body_regex)
Body.exact = staticmethod(_body_exact)
Body.xml = staticmethod(_body_xml)
Body.json_rpc = staticmethod(_body_json_rpc)
Body.graphql = staticmethod(_body_graphql)
Body.file = staticmethod(_body_file)
Body.xml_matcher = staticmethod(_body_xml_matcher)
Body.xpath = staticmethod(_body_xpath)
Body.xml_schema = staticmethod(_body_xml_schema)
Body.json_schema = staticmethod(_body_json_schema)
Body.parameters = staticmethod(_body_parameters)
Body.multipart = staticmethod(_body_multipart)
Body.wasm = staticmethod(_body_wasm)


def _body_all_of(*bodies) -> AllOfBody:
    # Accept either all_of(body1, body2, ...) or all_of([body1, body2, ...]).
    if len(bodies) == 1 and isinstance(bodies[0], (list, tuple)):
        return AllOfBody(body_all_of=list(bodies[0]))
    return AllOfBody(body_all_of=list(bodies))


Body.all_of = staticmethod(_body_all_of)


@dataclass
class SocketAddress:
    host: str | None = None
    port: int | None = None
    scheme: str | None = None

    def to_dict(self) -> dict:
        return _strip_none({
            "host": self.host,
            "port": self.port,
            "scheme": self.scheme,
        })

    @classmethod
    def from_dict(cls, data: dict) -> SocketAddress:
        if data is None:
            return None
        return cls(
            host=data.get("host"),
            port=data.get("port"),
            scheme=data.get("scheme"),
        )


@dataclass
class Jwt:
    # JWT request matcher. ``claims`` is a map of claim-name -> exact-or-regex
    # string; a leading "!" negates the match (NottableString convention). The
    # remaining fields are optional and are omitted from the wire form when unset.
    claims: dict = field(default_factory=dict)
    issuer: str | None = None
    audience: str | None = None
    algorithm: str | None = None
    header: str | None = None
    scheme: str | None = None

    def to_dict(self) -> dict:
        result: dict = {"claims": dict(self.claims)}
        if self.issuer is not None:
            result["issuer"] = self.issuer
        if self.audience is not None:
            result["audience"] = self.audience
        if self.algorithm is not None:
            result["algorithm"] = self.algorithm
        if self.header is not None:
            result["header"] = self.header
        if self.scheme is not None:
            result["scheme"] = self.scheme
        return result

    @classmethod
    def from_dict(cls, data: dict) -> Jwt:
        if data is None:
            return None
        return cls(
            claims=dict(data.get("claims") or {}),
            issuer=data.get("issuer"),
            audience=data.get("audience"),
            algorithm=data.get("algorithm"),
            header=data.get("header"),
            scheme=data.get("scheme"),
        )


@dataclass
class HttpRequest:
    method: str | None = None
    path: str | None = None
    query_string_parameters: KeyToMultiValuesInput | None = None
    headers: KeyToMultiValuesInput | None = None
    cookies: CookiesInput | None = None
    body: Body | str | dict | None = None
    secure: bool | None = None
    keep_alive: bool | None = None
    respond_before_body: bool | None = None
    path_parameters: KeyToMultiValuesInput | None = None
    socket_address: SocketAddress | None = None
    jwt: Jwt | None = None
    # Negates the whole request matcher (wire key "not"). When true a request matches
    # this definition only if it does NOT satisfy the specified properties.
    not_request: bool | None = None
    # Protocol matcher: HTTP_1_1 / HTTP_2 / HTTP_3 (see the ``Protocol`` constants).
    protocol: str | None = None
    # SUB_SET (the server's default: one value of a key must match) or
    # MATCHING_KEY (every value must). A dict given for the collection with a
    # "keyMatchStyle" key sets it too.
    headers_key_match_style: str | None = None
    query_string_parameters_key_match_style: str | None = None
    path_parameters_key_match_style: str | None = None

    # headers, query_string_parameters, path_parameters and cookies also accept
    # a dict ({name: [values]} or {name: value}; cookies {name: value}) or a
    # list of dicts, as the server's JSON does.
    def __post_init__(self) -> None:
        self._normalise()

    def _normalise(self) -> None:
        if self.headers_key_match_style is None:
            self.headers_key_match_style = _key_match_style(self.headers)
        if self.query_string_parameters_key_match_style is None:
            self.query_string_parameters_key_match_style = _key_match_style(self.query_string_parameters)
        if self.path_parameters_key_match_style is None:
            self.path_parameters_key_match_style = _key_match_style(self.path_parameters)
        self.headers = _coerce_key_multi_values(self.headers)
        self.query_string_parameters = _coerce_key_multi_values(self.query_string_parameters)
        self.path_parameters = _coerce_key_multi_values(self.path_parameters)
        self.cookies = _coerce_cookies(self.cookies)

    def to_dict(self) -> dict:
        self._normalise()
        if self.path_parameters_key_match_style is not None:
            path_parameters = _serialize_key_multi_values(
                _coerce_key_multi_values(self.path_parameters), self.path_parameters_key_match_style, "path_parameters"
            )
        else:
            path_parameters = _serialize_key_multi_values_map(_coerce_key_multi_values(self.path_parameters))
        return _strip_none({
            "not": self.not_request,
            "method": self.method,
            "path": self.path,
            "queryStringParameters": _serialize_key_multi_values(
                _coerce_key_multi_values(self.query_string_parameters),
                self.query_string_parameters_key_match_style,
                "query_string_parameters",
            ),
            "headers": _serialize_key_multi_values(
                _coerce_key_multi_values(self.headers), self.headers_key_match_style, "headers"
            ),
            "cookies": _serialize_cookies(_coerce_cookies(self.cookies)),
            "body": _serialize_body(self.body),
            "secure": self.secure,
            "keepAlive": self.keep_alive,
            "respondBeforeBody": self.respond_before_body,
            # pathParameters is emitted by MockServer as a {name: [values]} object-map
            # (its canonical keyToMultiValue form). Unlike headers/queryStringParameters
            # this map is NOT dual-encoding-normalised on comparison, and the values may
            # be schema matchers ({"schema": {..}}) rather than plain strings, so we must
            # preserve the object-map shape verbatim for a faithful round-trip.
            "pathParameters": path_parameters,
            "socketAddress": self.socket_address.to_dict() if self.socket_address else None,
            "jwt": self.jwt.to_dict() if self.jwt else None,
            "protocol": self.protocol,
        })

    @classmethod
    def from_dict(cls, data: dict) -> HttpRequest:
        if data is None:
            return None
        return cls(
            method=data.get("method"),
            path=data.get("path"),
            query_string_parameters=_deserialize_key_multi_values(data.get("queryStringParameters")),
            headers=_deserialize_key_multi_values(data.get("headers")),
            cookies=_deserialize_cookies(data.get("cookies")),
            body=_deserialize_body(data.get("body")),
            secure=data.get("secure"),
            keep_alive=data.get("keepAlive"),
            respond_before_body=data.get("respondBeforeBody"),
            path_parameters=_deserialize_key_multi_values(data.get("pathParameters")),
            socket_address=SocketAddress.from_dict(data.get("socketAddress")),
            jwt=Jwt.from_dict(data.get("jwt")),
            not_request=data.get("not"),
            protocol=data.get("protocol"),
            headers_key_match_style=_key_match_style(data.get("headers")),
            query_string_parameters_key_match_style=_key_match_style(data.get("queryStringParameters")),
            path_parameters_key_match_style=_key_match_style(data.get("pathParameters")),
        )

    @staticmethod
    def request(path: str | None = None) -> HttpRequest:
        return HttpRequest(path=path)

    def with_method(self, method: str) -> HttpRequest:
        self.method = method
        return self

    def with_path(self, path: str) -> HttpRequest:
        self.path = path
        return self

    def with_header(self, name: str, *values: str) -> HttpRequest:
        self._normalise()
        items = _coerce_key_multi_values(self.headers)
        if items is None:
            items = []
        items.append(KeyToMultiValue(name=name, values=list(values)))
        self.headers = items
        return self

    def with_query_param(self, name: str, *values: str) -> HttpRequest:
        self._normalise()
        items = _coerce_key_multi_values(self.query_string_parameters)
        if items is None:
            items = []
        items.append(KeyToMultiValue(name=name, values=list(values)))
        self.query_string_parameters = items
        return self

    def with_cookie(self, name: str, value: str) -> HttpRequest:
        self._normalise()
        items = _coerce_cookies(self.cookies)
        if items is None:
            items = []
        items.append(KeyToMultiValue(name=name, values=[value]))
        self.cookies = items
        return self

    def with_body(self, body: Body | str | dict) -> HttpRequest:
        self.body = body
        return self

    def with_jwt(
        self,
        jwt: Jwt | None = None,
        *,
        claims: dict | None = None,
        issuer: str | None = None,
        audience: str | None = None,
        algorithm: str | None = None,
        header: str | None = None,
        scheme: str | None = None,
    ) -> HttpRequest:
        if jwt is None:
            jwt = Jwt(
                claims=dict(claims) if claims else {},
                issuer=issuer,
                audience=audience,
                algorithm=algorithm,
                header=header,
                scheme=scheme,
            )
        self.jwt = jwt
        return self

    def with_secure(self, secure: bool) -> HttpRequest:
        self.secure = secure
        return self

    def with_keep_alive(self, keep_alive: bool) -> HttpRequest:
        self.keep_alive = keep_alive
        return self

    def with_respond_before_body(self, respond_before_body: bool) -> HttpRequest:
        self.respond_before_body = respond_before_body
        return self


class Protocol:
    """Transport protocol matcher constants for :attr:`HttpRequest.protocol`.
    Mirrors the core ``Protocol`` enum.
    """

    HTTP_1_1 = "HTTP_1_1"
    HTTP_2 = "HTTP_2"
    HTTP_3 = "HTTP_3"


class DnsRecordType:
    """DNS record type constants for :attr:`DnsRequestDefinition.dns_type`.
    Mirrors the core ``DnsRecordType`` enum.
    """

    A = "A"
    AAAA = "AAAA"
    CNAME = "CNAME"
    MX = "MX"
    SRV = "SRV"
    TXT = "TXT"
    PTR = "PTR"


class DnsRecordClass:
    """DNS record class constants for :attr:`DnsRequestDefinition.dns_class`.
    Mirrors the core ``DnsRecordClass`` enum.
    """

    IN = "IN"
    CH = "CH"
    HS = "HS"
    ANY = "ANY"


@dataclass
class DnsRequestDefinition:
    """A DNS query request matcher — an alternative to :class:`HttpRequest` as the
    ``http_request`` of an expectation (paired with a ``dns_response`` action).

    Serialises to ``{"dnsName": .., "dnsType": .., "dnsClass": ..}``. MockServer
    discriminates a DNS request matcher from an HTTP one by the presence of
    ``dnsName``, so no ``type`` field is emitted.
    """

    dns_name: str | None = None
    dns_type: str | None = None
    dns_class: str | None = None

    def to_dict(self) -> dict:
        return _strip_none({
            "dnsName": self.dns_name,
            "dnsType": self.dns_type,
            "dnsClass": self.dns_class,
        })

    @classmethod
    def from_dict(cls, data: dict) -> DnsRequestDefinition:
        if data is None:
            return None
        return cls(
            dns_name=data.get("dnsName"),
            dns_type=data.get("dnsType"),
            dns_class=data.get("dnsClass"),
        )

    @staticmethod
    def dns_request(dns_name: str, dns_type: str | None = None, dns_class: str | None = None) -> DnsRequestDefinition:
        return DnsRequestDefinition(dns_name=dns_name, dns_type=dns_type, dns_class=dns_class)


@dataclass
class ConditionalRequestDefinition:
    """A conditional (if/then/else) request matcher — an alternative to
    :class:`HttpRequest` as the ``http_request`` of an expectation.

    When ``if_request`` matches, ``then_request`` must match too; otherwise
    ``else_request`` must (with no ``else_request`` the matcher matches whenever
    ``if_request`` does not). Each branch is an :class:`HttpRequest`, an
    :class:`OpenAPIDefinition` or another ``ConditionalRequestDefinition``.
    ``not_condition`` inverts the whole match.

    Serialises to ``{"if": .., "then": .., "else": .., "not": ..}``; MockServer
    recognises a conditional matcher by the presence of ``if``.
    """

    if_request: HttpRequest | OpenAPIDefinition | ConditionalRequestDefinition | None = None
    then_request: HttpRequest | OpenAPIDefinition | ConditionalRequestDefinition | None = None
    else_request: HttpRequest | OpenAPIDefinition | ConditionalRequestDefinition | None = None
    not_condition: bool | None = None

    def to_dict(self) -> dict:
        return _strip_none({
            "not": self.not_condition,
            "if": self.if_request.to_dict() if self.if_request is not None else None,
            "then": self.then_request.to_dict() if self.then_request is not None else None,
            "else": self.else_request.to_dict() if self.else_request is not None else None,
        })

    @classmethod
    def from_dict(cls, data: dict) -> ConditionalRequestDefinition:
        if data is None:
            return None
        return cls(
            if_request=_deserialize_conditional_branch(data.get("if")),
            then_request=_deserialize_conditional_branch(data.get("then")),
            else_request=_deserialize_conditional_branch(data.get("else")),
            not_condition=data.get("not"),
        )

    @staticmethod
    def request_if(
        if_request: HttpRequest | OpenAPIDefinition | ConditionalRequestDefinition,
        then_request: HttpRequest | OpenAPIDefinition | ConditionalRequestDefinition | None = None,
        else_request: HttpRequest | OpenAPIDefinition | ConditionalRequestDefinition | None = None,
    ) -> ConditionalRequestDefinition:
        return ConditionalRequestDefinition(
            if_request=if_request, then_request=then_request, else_request=else_request
        )


def _deserialize_conditional_branch(data: Any) -> Any | None:
    """Deserialize one branch of a :class:`ConditionalRequestDefinition`: a dict
    carrying ``if`` is a nested conditional, one carrying ``specUrlOrPayload`` an
    :class:`OpenAPIDefinition`, anything else an :class:`HttpRequest`.
    """
    if data is None:
        return None
    if isinstance(data, dict) and "if" in data:
        return ConditionalRequestDefinition.from_dict(data)
    if isinstance(data, dict) and "specUrlOrPayload" in data:
        return OpenAPIDefinition.from_dict(data)
    return HttpRequest.from_dict(data)


def _deserialize_request_definition(data: Any) -> Any | None:
    """Deserialize an expectation ``httpRequest`` into the correct request-matcher
    type. A dict carrying ``dnsName`` is a :class:`DnsRequestDefinition`, one
    carrying ``if`` a :class:`ConditionalRequestDefinition`, one carrying
    ``specUrlOrPayload`` an :class:`OpenAPIDefinition`; anything else is an
    :class:`HttpRequest`.
    """
    if data is None:
        return None
    if isinstance(data, dict) and "dnsName" in data:
        return DnsRequestDefinition.from_dict(data)
    if isinstance(data, dict) and "if" in data:
        return ConditionalRequestDefinition.from_dict(data)
    if isinstance(data, dict) and "specUrlOrPayload" in data:
        return OpenAPIDefinition.from_dict(data)
    return HttpRequest.from_dict(data)


@dataclass
class ConnectionOptions:
    close_socket: bool | None = None
    close_socket_delay: Delay | None = None
    suppress_content_length_header: bool | None = None
    content_length_header_override: int | None = None
    suppress_connection_header: bool | None = None
    chunk_size: int | None = None
    chunk_delay: Delay | None = None
    keep_alive_override: bool | None = None

    def to_dict(self) -> dict:
        return _strip_none({
            "closeSocket": self.close_socket,
            "closeSocketDelay": self.close_socket_delay.to_dict() if self.close_socket_delay else None,
            "suppressContentLengthHeader": self.suppress_content_length_header,
            "contentLengthHeaderOverride": self.content_length_header_override,
            "suppressConnectionHeader": self.suppress_connection_header,
            "chunkSize": self.chunk_size,
            "chunkDelay": self.chunk_delay.to_dict() if self.chunk_delay else None,
            "keepAliveOverride": self.keep_alive_override,
        })

    @classmethod
    def from_dict(cls, data: dict) -> ConnectionOptions:
        if data is None:
            return None
        return cls(
            close_socket=data.get("closeSocket"),
            close_socket_delay=Delay.from_dict(data.get("closeSocketDelay")),
            suppress_content_length_header=data.get("suppressContentLengthHeader"),
            content_length_header_override=data.get("contentLengthHeaderOverride"),
            suppress_connection_header=data.get("suppressConnectionHeader"),
            chunk_size=data.get("chunkSize"),
            chunk_delay=Delay.from_dict(data.get("chunkDelay")),
            keep_alive_override=data.get("keepAliveOverride"),
        )


@dataclass
class RecoverAfter:
    """Serve ``fail_response`` for the first ``fail_times`` matches, then the real response.

    ``idempotency_header`` keys an independent failure window per value of that request header.
    """

    fail_times: int | None = None
    fail_response: HttpResponse | None = None
    idempotency_header: str | None = None

    def to_dict(self) -> dict:
        return _strip_none({
            "failTimes": self.fail_times,
            "failResponse": self.fail_response.to_dict() if self.fail_response else None,
            "idempotencyHeader": self.idempotency_header,
        })

    @classmethod
    def from_dict(cls, data: dict) -> RecoverAfter:
        if data is None:
            return None
        return cls(
            fail_times=data.get("failTimes"),
            fail_response=HttpResponse.from_dict(data.get("failResponse")),
            idempotency_header=data.get("idempotencyHeader"),
        )


@dataclass
class HttpResponse:
    status_code: int | None = None
    reason_phrase: str | None = None
    headers: KeyToMultiValuesInput | None = None
    cookies: CookiesInput | None = None
    body: Body | str | dict | None = None
    delay: Delay | None = None
    connection_options: ConnectionOptions | None = None
    primary: bool | None = None
    # HTTP trailing headers (sent after the body); same keyToMultiValue shape as headers.
    trailers: KeyToMultiValuesInput | None = None
    # A status drawn from a range, e.g. "2xx" or "200-299".
    status_code_range: str | None = None
    # A JSON schema the body is generated from.
    generate_from_schema: str | None = None
    recover_after: RecoverAfter | None = None

    # headers, trailers and cookies also accept a dict or a list of dicts, as
    # the server's JSON does (see HttpRequest).
    def __post_init__(self) -> None:
        self._normalise()

    def _normalise(self) -> None:
        self.headers = _coerce_key_multi_values(self.headers)
        self.trailers = _coerce_key_multi_values(self.trailers)
        self.cookies = _coerce_cookies(self.cookies)

    def to_dict(self) -> dict:
        self._normalise()
        return _strip_none({
            "statusCode": self.status_code,
            "reasonPhrase": self.reason_phrase,
            "headers": _serialize_key_multi_values(_coerce_key_multi_values(self.headers)),
            "cookies": _serialize_cookies(_coerce_cookies(self.cookies)),
            "body": _serialize_body(self.body),
            "delay": self.delay.to_dict() if self.delay else None,
            "connectionOptions": self.connection_options.to_dict() if self.connection_options else None,
            "primary": self.primary,
            "trailers": _serialize_key_multi_values(_coerce_key_multi_values(self.trailers)),
            "statusCodeRange": self.status_code_range,
            "generateFromSchema": self.generate_from_schema,
            "recoverAfter": self.recover_after.to_dict() if self.recover_after else None,
        })

    @classmethod
    def from_dict(cls, data: dict) -> HttpResponse:
        if data is None:
            return None
        return cls(
            status_code=data.get("statusCode"),
            reason_phrase=data.get("reasonPhrase"),
            headers=_deserialize_key_multi_values(data.get("headers")),
            cookies=_deserialize_cookies(data.get("cookies")),
            body=_deserialize_body(data.get("body")),
            delay=Delay.from_dict(data.get("delay")),
            connection_options=ConnectionOptions.from_dict(data.get("connectionOptions")),
            primary=data.get("primary"),
            trailers=_deserialize_key_multi_values(data.get("trailers")),
            status_code_range=data.get("statusCodeRange"),
            generate_from_schema=data.get("generateFromSchema"),
            recover_after=RecoverAfter.from_dict(data.get("recoverAfter")),
        )

    @staticmethod
    def response(body: str | None = None, status_code: int | None = None) -> HttpResponse:
        resp = HttpResponse()
        if body is not None:
            resp.body = body
            if status_code is None:
                resp.status_code = 200
                resp.reason_phrase = "OK"
            else:
                resp.status_code = status_code
        elif status_code is not None:
            resp.status_code = status_code
        return resp

    @staticmethod
    def not_found_response() -> HttpResponse:
        return HttpResponse(status_code=404, reason_phrase="Not Found")

    def with_status_code(self, status_code: int) -> HttpResponse:
        self.status_code = status_code
        return self

    def with_header(self, name: str, *values: str) -> HttpResponse:
        items = _coerce_key_multi_values(self.headers)
        if items is None:
            items = []
        items.append(KeyToMultiValue(name=name, values=list(values)))
        self.headers = items
        return self

    def with_cookie(self, name: str, value: str) -> HttpResponse:
        items = _coerce_cookies(self.cookies)
        if items is None:
            items = []
        items.append(KeyToMultiValue(name=name, values=[value]))
        self.cookies = items
        return self

    def with_body(self, body: Body | str | dict) -> HttpResponse:
        self.body = body
        return self

    def with_delay(self, delay: Delay) -> HttpResponse:
        self.delay = delay
        return self

    def with_reason_phrase(self, reason_phrase: str) -> HttpResponse:
        self.reason_phrase = reason_phrase
        return self

    def with_trailer(self, name: str, *values: str) -> HttpResponse:
        items = _coerce_key_multi_values(self.trailers)
        if items is None:
            items = []
        items.append(KeyToMultiValue(name=name, values=list(values)))
        self.trailers = items
        return self

    def with_status_code_range(self, status_code_range: str) -> HttpResponse:
        self.status_code_range = status_code_range
        return self

    def with_generate_from_schema(self, schema: str) -> HttpResponse:
        self.generate_from_schema = schema
        return self

    def with_recover_after(self, recover_after: RecoverAfter) -> HttpResponse:
        self.recover_after = recover_after
        return self


@dataclass
class HttpForward:
    host: str | None = None
    port: int | None = None
    scheme: str | None = None
    delay: Delay | None = None
    primary: bool | None = None

    def to_dict(self) -> dict:
        return _strip_none({
            "host": self.host,
            "port": self.port,
            "scheme": self.scheme,
            "delay": self.delay.to_dict() if self.delay else None,
            "primary": self.primary,
        })

    @classmethod
    def from_dict(cls, data: dict) -> HttpForward:
        if data is None:
            return None
        return cls(
            host=data.get("host"),
            port=data.get("port"),
            scheme=data.get("scheme"),
            delay=Delay.from_dict(data.get("delay")),
            primary=data.get("primary"),
        )

    @staticmethod
    def forward() -> HttpForward:
        return HttpForward()


@dataclass
class HttpTemplate:
    template_type: str = "JAVASCRIPT"
    template: str | None = None
    template_file: str | None = None
    delay: Delay | None = None
    primary: bool | None = None
    # Response fields applied over the rendered template's output.
    response_override: HttpResponse | None = None
    # The server's responseModifier object (headers / cookies add, replace, remove; condition; patches).
    response_modifier: dict | None = None

    def to_dict(self) -> dict:
        return _strip_none({
            "templateType": self.template_type,
            "template": self.template,
            "templateFile": self.template_file,
            "delay": self.delay.to_dict() if self.delay else None,
            "primary": self.primary,
            "responseOverride": self.response_override.to_dict() if self.response_override else None,
            "responseModifier": self.response_modifier,
        })

    @classmethod
    def from_dict(cls, data: dict) -> HttpTemplate:
        if data is None:
            return None
        return cls(
            template_type=data.get("templateType", "JAVASCRIPT"),
            template=data.get("template"),
            template_file=data.get("templateFile"),
            delay=Delay.from_dict(data.get("delay")),
            primary=data.get("primary"),
            response_override=HttpResponse.from_dict(data.get("responseOverride")),
            response_modifier=data.get("responseModifier"),
        )


def _http_template_factory(template_type: str, template: str | None = None, template_file: str | None = None) -> HttpTemplate:
    return HttpTemplate(template_type=template_type, template=template, template_file=template_file)


HttpTemplate.template = staticmethod(_http_template_factory)


@dataclass
class HttpClassCallback:
    callback_class: str | None = None
    delay: Delay | None = None
    primary: bool | None = None

    def to_dict(self) -> dict:
        return _strip_none({
            "callbackClass": self.callback_class,
            "delay": self.delay.to_dict() if self.delay else None,
            "primary": self.primary,
        })

    @classmethod
    def from_dict(cls, data: dict) -> HttpClassCallback:
        if data is None:
            return None
        return cls(
            callback_class=data.get("callbackClass"),
            delay=Delay.from_dict(data.get("delay")),
            primary=data.get("primary"),
        )

    @staticmethod
    def callback(callback_class: str | None = None) -> HttpClassCallback:
        return HttpClassCallback(callback_class=callback_class)


@dataclass
class HttpObjectCallback:
    client_id: str | None = None
    response_callback: bool | None = None
    delay: Delay | None = None
    primary: bool | None = None

    def to_dict(self) -> dict:
        return _strip_none({
            "clientId": self.client_id,
            "responseCallback": self.response_callback,
            "delay": self.delay.to_dict() if self.delay else None,
            "primary": self.primary,
        })

    @classmethod
    def from_dict(cls, data: dict) -> HttpObjectCallback:
        if data is None:
            return None
        return cls(
            client_id=data.get("clientId"),
            response_callback=data.get("responseCallback"),
            delay=Delay.from_dict(data.get("delay")),
            primary=data.get("primary"),
        )


@dataclass
class HttpError:
    drop_connection: bool | None = None
    response_bytes: str | None = None
    # reset the matched request stream with this error code (HTTP/2 RST_STREAM / HTTP/3 RESET_STREAM)
    # instead of returning a response; HTTP/1.1 has no stream concept so this falls back to dropping
    # the connection. Takes precedence over drop_connection when both are set.
    stream_error: int | None = None
    delay: Delay | None = None
    primary: bool | None = None

    def to_dict(self) -> dict:
        return _strip_none({
            "dropConnection": self.drop_connection,
            "responseBytes": self.response_bytes,
            "streamError": self.stream_error,
            "delay": self.delay.to_dict() if self.delay else None,
            "primary": self.primary,
        })

    @classmethod
    def from_dict(cls, data: dict) -> HttpError:
        if data is None:
            return None
        return cls(
            drop_connection=data.get("dropConnection"),
            response_bytes=data.get("responseBytes"),
            stream_error=data.get("streamError"),
            delay=Delay.from_dict(data.get("delay")),
            primary=data.get("primary"),
        )

    @staticmethod
    def error() -> HttpError:
        return HttpError()


@dataclass
class SseEvent:
    event: str | None = None
    data: str | None = None
    id: str | None = None
    retry: int | None = None
    delay: Delay | None = None

    def to_dict(self) -> dict:
        result: dict = {}
        if self.event is not None:
            result["event"] = self.event
        if self.data is not None:
            result["data"] = self.data
        if self.id is not None:
            result["id"] = self.id
        if self.retry is not None:
            result["retry"] = self.retry
        if self.delay is not None:
            result["delay"] = self.delay.to_dict()
        return result

    @classmethod
    def from_dict(cls, data: dict) -> SseEvent:
        if data is None:
            return None
        return cls(
            event=data.get("event"),
            data=data.get("data"),
            id=data.get("id"),
            retry=data.get("retry"),
            delay=Delay.from_dict(data.get("delay")),
        )


@dataclass
class HttpSseResponse:
    status_code: int | None = None
    headers: list[KeyToMultiValue] | None = None
    events: list[SseEvent] | None = None
    close_connection: bool | None = None
    delay: Delay | None = None
    primary: bool | None = None
    # Renders each event's fields with this engine (VELOCITY / JAVASCRIPT / MUSTACHE).
    template_type: str | None = None

    def to_dict(self) -> dict:
        result: dict = {}
        if self.status_code is not None:
            result["statusCode"] = self.status_code
        if self.headers is not None:
            result["headers"] = _serialize_key_multi_values(self.headers)
        if self.events is not None:
            result["events"] = [e.to_dict() if hasattr(e, 'to_dict') else e for e in self.events]
        if self.close_connection is not None:
            result["closeConnection"] = self.close_connection
        if self.template_type is not None:
            result["templateType"] = self.template_type
        if self.delay is not None:
            result["delay"] = self.delay.to_dict()
        if self.primary is not None:
            result["primary"] = self.primary
        return result

    @classmethod
    def from_dict(cls, data: dict) -> HttpSseResponse:
        if data is None:
            return None
        events_data = data.get("events")
        events = None
        if events_data is not None:
            events = [SseEvent.from_dict(e) if isinstance(e, dict) else e for e in events_data]
        return cls(
            status_code=data.get("statusCode"),
            headers=_deserialize_key_multi_values(data.get("headers")),
            events=events,
            close_connection=data.get("closeConnection"),
            delay=Delay.from_dict(data.get("delay")),
            primary=data.get("primary"),
            template_type=data.get("templateType"),
        )


@dataclass
class WebSocketMessage:
    text: str | None = None
    binary: bytes | None = None
    delay: Delay | None = None

    def to_dict(self) -> dict:
        result: dict = {}
        if self.text is not None:
            result["text"] = self.text
        if self.binary is not None:
            import base64
            result["binary"] = base64.b64encode(self.binary).decode("utf-8")
        if self.delay is not None:
            result["delay"] = self.delay.to_dict()
        return result

    @classmethod
    def from_dict(cls, data: dict) -> WebSocketMessage:
        if data is None:
            return None
        import base64
        binary_data = data.get("binary")
        binary = base64.b64decode(binary_data) if binary_data is not None else None
        return cls(
            text=data.get("text"),
            binary=binary,
            delay=Delay.from_dict(data.get("delay")),
        )


@dataclass
class WebSocketFrameMatcher:
    """A per-incoming-frame response rule: when an inbound frame matches, its
    ``responses`` are sent. Mirrors the ``matchers`` items of a WebSocket response.
    """

    frame_type: str | None = None
    text_matcher: str | None = None
    responses: list[WebSocketMessage] | None = None

    def to_dict(self) -> dict:
        result: dict = {}
        if self.frame_type is not None:
            result["frameType"] = self.frame_type
        if self.text_matcher is not None:
            result["textMatcher"] = self.text_matcher
        if self.responses is not None:
            result["responses"] = [r.to_dict() if hasattr(r, 'to_dict') else r for r in self.responses]
        return result

    @classmethod
    def from_dict(cls, data: dict) -> WebSocketFrameMatcher:
        if data is None:
            return None
        responses_data = data.get("responses")
        responses = None
        if responses_data is not None:
            responses = [WebSocketMessage.from_dict(r) if isinstance(r, dict) else r for r in responses_data]
        return cls(
            frame_type=data.get("frameType"),
            text_matcher=data.get("textMatcher"),
            responses=responses,
        )


@dataclass
class GraphQLSubscriptionFilter:
    """Matches a ``graphql-transport-ws`` subscribe message against this GraphQL query."""

    query: str | None = None
    operation_name: str | None = None
    variables_schema: str | None = None
    # NORMALISED_STRING, AST_EXACT or AST_SUBSET.
    selection_set_match_type: str | None = None
    fields: list[str] | None = None
    # Optional body-type discriminator; only "GRAPHQL" is meaningful.
    type: str | None = None

    def to_dict(self) -> dict:
        return _strip_none({
            "type": self.type,
            "query": self.query,
            "operationName": self.operation_name,
            "variablesSchema": self.variables_schema,
            "selectionSetMatchType": self.selection_set_match_type,
            "fields": list(self.fields) if self.fields is not None else None,
        })

    @classmethod
    def from_dict(cls, data: dict) -> GraphQLSubscriptionFilter:
        if data is None:
            return None
        return cls(
            query=data.get("query"),
            operation_name=data.get("operationName"),
            variables_schema=data.get("variablesSchema"),
            selection_set_match_type=data.get("selectionSetMatchType"),
            fields=data.get("fields"),
            type=data.get("type"),
        )


@dataclass
class HttpWebSocketResponse:
    subprotocol: str | None = None
    messages: list[WebSocketMessage] | None = None
    matchers: list[WebSocketFrameMatcher] | None = None
    close_connection: bool | None = None
    delay: Delay | None = None
    primary: bool | None = None
    # Renders each message with this engine (VELOCITY / JAVASCRIPT / MUSTACHE).
    template_type: str | None = None
    graphql_subscription_filter: GraphQLSubscriptionFilter | None = None

    def to_dict(self) -> dict:
        result: dict = {}
        if self.subprotocol is not None:
            result["subprotocol"] = self.subprotocol
        if self.messages is not None:
            result["messages"] = [m.to_dict() if hasattr(m, 'to_dict') else m for m in self.messages]
        if self.matchers is not None:
            result["matchers"] = [m.to_dict() if hasattr(m, 'to_dict') else m for m in self.matchers]
        if self.close_connection is not None:
            result["closeConnection"] = self.close_connection
        if self.delay is not None:
            result["delay"] = self.delay.to_dict()
        if self.primary is not None:
            result["primary"] = self.primary
        if self.template_type is not None:
            result["templateType"] = self.template_type
        if self.graphql_subscription_filter is not None:
            result["graphqlSubscriptionFilter"] = self.graphql_subscription_filter.to_dict()
        return result

    @classmethod
    def from_dict(cls, data: dict) -> HttpWebSocketResponse:
        if data is None:
            return None
        messages_data = data.get("messages")
        messages = None
        if messages_data is not None:
            messages = [WebSocketMessage.from_dict(m) if isinstance(m, dict) else m for m in messages_data]
        matchers_data = data.get("matchers")
        matchers = None
        if matchers_data is not None:
            matchers = [WebSocketFrameMatcher.from_dict(m) if isinstance(m, dict) else m for m in matchers_data]
        return cls(
            subprotocol=data.get("subprotocol"),
            messages=messages,
            matchers=matchers,
            close_connection=data.get("closeConnection"),
            delay=Delay.from_dict(data.get("delay")),
            primary=data.get("primary"),
            template_type=data.get("templateType"),
            graphql_subscription_filter=GraphQLSubscriptionFilter.from_dict(data.get("graphqlSubscriptionFilter")),
        )


@dataclass
class GrpcStreamMessage:
    json: str | None = None
    delay: Delay | None = None
    # Template engine for the message JSON (VELOCITY / JAVASCRIPT / MUSTACHE).
    template_type: str | None = None

    def to_dict(self) -> dict:
        result: dict = {}
        if self.json is not None:
            result["json"] = self.json
        if self.template_type is not None:
            result["templateType"] = self.template_type
        if self.delay is not None:
            result["delay"] = self.delay.to_dict()
        return result

    @classmethod
    def from_dict(cls, data: dict) -> GrpcStreamMessage:
        if data is None:
            return None
        return cls(
            json=data.get("json"),
            delay=Delay.from_dict(data.get("delay")),
            template_type=data.get("templateType"),
        )


@dataclass
class GrpcStreamResponse:
    status_name: str | None = None
    status_message: str | None = None
    headers: list[KeyToMultiValue] | None = None
    messages: list[GrpcStreamMessage] | None = None
    close_connection: bool | None = None
    delay: Delay | None = None
    primary: bool | None = None

    def to_dict(self) -> dict:
        result: dict = {}
        if self.status_name is not None:
            result["statusName"] = self.status_name
        if self.status_message is not None:
            result["statusMessage"] = self.status_message
        if self.headers is not None:
            result["headers"] = _serialize_key_multi_values(self.headers)
        if self.messages is not None:
            result["messages"] = [m.to_dict() if hasattr(m, 'to_dict') else m for m in self.messages]
        if self.close_connection is not None:
            result["closeConnection"] = self.close_connection
        if self.delay is not None:
            result["delay"] = self.delay.to_dict()
        if self.primary is not None:
            result["primary"] = self.primary
        return result

    @classmethod
    def from_dict(cls, data: dict) -> GrpcStreamResponse:
        if data is None:
            return None
        messages_data = data.get("messages")
        messages = None
        if messages_data is not None:
            messages = [GrpcStreamMessage.from_dict(m) if isinstance(m, dict) else m for m in messages_data]
        return cls(
            status_name=data.get("statusName"),
            status_message=data.get("statusMessage"),
            headers=_deserialize_key_multi_values(data.get("headers")),
            messages=messages,
            close_connection=data.get("closeConnection"),
            delay=Delay.from_dict(data.get("delay")),
            primary=data.get("primary"),
        )


@dataclass
class GrpcBidiRule:
    match_json: str | None = None
    responses: list[GrpcStreamMessage] | None = None

    def to_dict(self) -> dict:
        result: dict = {}
        if self.match_json is not None:
            result["matchJson"] = self.match_json
        if self.responses is not None:
            result["responses"] = [r.to_dict() if hasattr(r, 'to_dict') else r for r in self.responses]
        return result

    @classmethod
    def from_dict(cls, data: dict) -> GrpcBidiRule:
        if data is None:
            return None
        responses_data = data.get("responses")
        responses = None
        if responses_data is not None:
            responses = [GrpcStreamMessage.from_dict(r) if isinstance(r, dict) else r for r in responses_data]
        return cls(
            match_json=data.get("matchJson"),
            responses=responses,
        )


@dataclass
class GrpcBidiResponse:
    status_name: str | None = None
    status_message: str | None = None
    headers: list[KeyToMultiValue] | None = None
    messages: list[GrpcStreamMessage] | None = None
    rules: list[GrpcBidiRule] | None = None
    close_connection: bool | None = None
    delay: Delay | None = None
    primary: bool | None = None

    def to_dict(self) -> dict:
        result: dict = {}
        if self.status_name is not None:
            result["statusName"] = self.status_name
        if self.status_message is not None:
            result["statusMessage"] = self.status_message
        if self.headers is not None:
            result["headers"] = _serialize_key_multi_values(self.headers)
        if self.messages is not None:
            result["messages"] = [m.to_dict() if hasattr(m, 'to_dict') else m for m in self.messages]
        if self.rules is not None:
            result["rules"] = [r.to_dict() if hasattr(r, 'to_dict') else r for r in self.rules]
        if self.close_connection is not None:
            result["closeConnection"] = self.close_connection
        if self.delay is not None:
            result["delay"] = self.delay.to_dict()
        if self.primary is not None:
            result["primary"] = self.primary
        return result

    @classmethod
    def from_dict(cls, data: dict) -> GrpcBidiResponse:
        if data is None:
            return None
        messages_data = data.get("messages")
        messages = None
        if messages_data is not None:
            messages = [GrpcStreamMessage.from_dict(m) if isinstance(m, dict) else m for m in messages_data]
        rules_data = data.get("rules")
        rules = None
        if rules_data is not None:
            rules = [GrpcBidiRule.from_dict(r) if isinstance(r, dict) else r for r in rules_data]
        return cls(
            status_name=data.get("statusName"),
            status_message=data.get("statusMessage"),
            headers=_deserialize_key_multi_values(data.get("headers")),
            messages=messages,
            rules=rules,
            close_connection=data.get("closeConnection"),
            delay=Delay.from_dict(data.get("delay")),
            primary=data.get("primary"),
        )


class BinaryUpstream:
    """Constants for :attr:`BinaryResponse.upstream`: what happens upstream to a message a
    binary expectation matches on a relayed connection (forwardBinaryRequestsMatchExpectations).

    ``ANSWER_ONLY`` (the server's default) writes the binary data and does not forward the
    message; ``ANSWER_AND_FORWARD`` also forwards it and drops the upstream's reply;
    ``FORWARD_AND_REPLACE`` forwards it and writes the binary data in place of the upstream's
    reply. The last two need binaryMessageFraming POSTGRESQL on the server.
    """

    ANSWER_ONLY = "ANSWER_ONLY"
    ANSWER_AND_FORWARD = "ANSWER_AND_FORWARD"
    FORWARD_AND_REPLACE = "FORWARD_AND_REPLACE"


@dataclass
class BinaryResponse:
    binary_data: str | None = None
    delay: Delay | None = None
    primary: bool | None = None
    upstream: str | None = None

    def to_dict(self) -> dict:
        result: dict = {}
        if self.binary_data is not None:
            result["binaryData"] = self.binary_data
        if self.delay is not None:
            result["delay"] = self.delay.to_dict()
        if self.primary is not None:
            result["primary"] = self.primary
        if self.upstream is not None:
            result["upstream"] = self.upstream
        return result

    @classmethod
    def from_dict(cls, data: dict) -> BinaryResponse:
        if data is None:
            return None
        return cls(
            binary_data=data.get("binaryData"),
            delay=Delay.from_dict(data.get("delay")),
            primary=data.get("primary"),
            upstream=data.get("upstream"),
        )


@dataclass
class DnsRecord:
    name: str | None = None
    type: str | None = None
    dns_class: str | None = None
    ttl: int | None = None
    value: str | None = None
    priority: int | None = None
    weight: int | None = None
    port: int | None = None

    def to_dict(self) -> dict:
        result: dict = {}
        if self.name is not None:
            result["name"] = self.name
        if self.type is not None:
            result["type"] = self.type
        if self.dns_class is not None:
            result["dnsClass"] = self.dns_class
        if self.ttl is not None:
            result["ttl"] = self.ttl
        if self.value is not None:
            result["value"] = self.value
        if self.priority is not None:
            result["priority"] = self.priority
        if self.weight is not None:
            result["weight"] = self.weight
        if self.port is not None:
            result["port"] = self.port
        return result

    @classmethod
    def from_dict(cls, data: dict) -> DnsRecord:
        if data is None:
            return None
        return cls(
            name=data.get("name"),
            type=data.get("type"),
            dns_class=data.get("dnsClass"),
            ttl=data.get("ttl"),
            value=data.get("value"),
            priority=data.get("priority"),
            weight=data.get("weight"),
            port=data.get("port"),
        )

    @staticmethod
    def a_record(name: str, ip: str) -> DnsRecord:
        return DnsRecord(name=name, type="A", value=ip)

    @staticmethod
    def aaaa_record(name: str, ip: str) -> DnsRecord:
        return DnsRecord(name=name, type="AAAA", value=ip)

    @staticmethod
    def cname_record(name: str, cname: str) -> DnsRecord:
        return DnsRecord(name=name, type="CNAME", value=cname)

    @staticmethod
    def mx_record(name: str, priority: int, exchange: str) -> DnsRecord:
        return DnsRecord(name=name, type="MX", priority=priority, value=exchange)

    @staticmethod
    def srv_record(name: str, priority: int, weight: int, port: int, target: str) -> DnsRecord:
        return DnsRecord(name=name, type="SRV", priority=priority, weight=weight, port=port, value=target)

    @staticmethod
    def txt_record(name: str, text: str) -> DnsRecord:
        return DnsRecord(name=name, type="TXT", value=text)

    @staticmethod
    def ptr_record(name: str, pointer: str) -> DnsRecord:
        return DnsRecord(name=name, type="PTR", value=pointer)


@dataclass
class DnsResponse:
    response_code: str | None = None
    answer_records: list[DnsRecord] | None = None
    authority_records: list[DnsRecord] | None = None
    additional_records: list[DnsRecord] | None = None
    delay: Delay | None = None
    primary: bool | None = None

    def to_dict(self) -> dict:
        result: dict = {}
        if self.response_code is not None:
            result["responseCode"] = self.response_code
        if self.answer_records is not None:
            result["answerRecords"] = [r.to_dict() for r in self.answer_records]
        if self.authority_records is not None:
            result["authorityRecords"] = [r.to_dict() for r in self.authority_records]
        if self.additional_records is not None:
            result["additionalRecords"] = [r.to_dict() for r in self.additional_records]
        if self.delay is not None:
            result["delay"] = self.delay.to_dict()
        if self.primary is not None:
            result["primary"] = self.primary
        return result

    @classmethod
    def from_dict(cls, data: dict) -> DnsResponse:
        if data is None:
            return None
        answer_data = data.get("answerRecords")
        authority_data = data.get("authorityRecords")
        additional_data = data.get("additionalRecords")
        return cls(
            response_code=data.get("responseCode"),
            answer_records=[DnsRecord.from_dict(r) for r in answer_data] if answer_data else None,
            authority_records=[DnsRecord.from_dict(r) for r in authority_data] if authority_data else None,
            additional_records=[DnsRecord.from_dict(r) for r in additional_data] if additional_data else None,
            delay=Delay.from_dict(data.get("delay")),
            primary=data.get("primary"),
        )


@dataclass
class HttpOverrideForwardedRequest:
    """Forward the matched request after overriding or modifying it.

    ``request_override`` is merged over the forwarded request and
    ``response_override`` over the response; ``response_template`` renders the
    response instead. ``http_request`` and ``http_response`` are deprecated
    aliases of the two overrides, kept for existing code: set one name of each
    pair, not both.
    """

    http_request: HttpRequest | None = None
    http_response: HttpResponse | None = None
    response_template: HttpTemplate | None = None
    delay: Delay | None = None
    request_modifier: dict | None = None
    response_modifier: dict | None = None
    primary: bool | None = None
    request_override: HttpRequest | None = None
    response_override: HttpResponse | None = None

    def to_dict(self) -> dict:
        if self.http_request is not None and self.request_override is not None:
            raise ValueError("set request_override or its deprecated alias http_request, not both")
        if self.http_response is not None and self.response_override is not None:
            raise ValueError("set response_override or its deprecated alias http_response, not both")
        request = self.request_override if self.request_override is not None else self.http_request
        response = self.response_override if self.response_override is not None else self.http_response
        # The server accepts httpRequest / httpResponse only alongside delay and primary.
        alias_form = (
            self.request_override is None and self.response_override is None
            and self.request_modifier is None and self.response_modifier is None
            and self.response_template is None
        )
        request_key = "httpRequest" if alias_form else "requestOverride"
        response_key = "httpResponse" if alias_form else "responseOverride"
        return _strip_none({
            request_key: request.to_dict() if request else None,
            "requestModifier": self.request_modifier,
            response_key: response.to_dict() if response else None,
            "responseModifier": self.response_modifier,
            "responseTemplate": self.response_template.to_dict() if self.response_template else None,
            "delay": self.delay.to_dict() if self.delay else None,
            "primary": self.primary,
        })

    @classmethod
    def from_dict(cls, data: dict) -> HttpOverrideForwardedRequest:
        if data is None:
            return None
        return cls(
            http_request=HttpRequest.from_dict(data.get("httpRequest")),
            http_response=HttpResponse.from_dict(data.get("httpResponse")),
            response_template=HttpTemplate.from_dict(data.get("responseTemplate")),
            delay=Delay.from_dict(data.get("delay")),
            request_modifier=data.get("requestModifier"),
            response_modifier=data.get("responseModifier"),
            primary=data.get("primary"),
            request_override=HttpRequest.from_dict(data.get("requestOverride")),
            response_override=HttpResponse.from_dict(data.get("responseOverride")),
        )

    @staticmethod
    def forward_overridden_request(request: HttpRequest | None = None) -> HttpOverrideForwardedRequest:
        return HttpOverrideForwardedRequest(http_request=request)


@dataclass
class HttpRequestAndHttpResponse:
    http_request: HttpRequest | None = None
    http_response: HttpResponse | None = None

    def to_dict(self) -> dict:
        return _strip_none({
            "httpRequest": self.http_request.to_dict() if self.http_request else None,
            "httpResponse": self.http_response.to_dict() if self.http_response else None,
        })

    @classmethod
    def from_dict(cls, data: dict) -> HttpRequestAndHttpResponse:
        if data is None:
            return None
        return cls(
            http_request=HttpRequest.from_dict(data.get("httpRequest")),
            http_response=HttpResponse.from_dict(data.get("httpResponse")),
        )


@dataclass
class ExpectationId:
    id: str = ""

    def to_dict(self) -> dict:
        return {"id": self.id}

    @classmethod
    def from_dict(cls, data: dict) -> ExpectationId:
        if data is None:
            return None
        return cls(id=data.get("id", ""))


@dataclass
class AfterAction:
    http_request: HttpRequest | None = None
    http_class_callback: HttpClassCallback | None = None
    http_object_callback: HttpObjectCallback | None = None
    delay: Delay | None = None
    # before-actions only (ignored for after-actions)
    blocking: bool | None = None
    timeout: Delay | None = None
    failure_policy: str | None = None

    def to_dict(self) -> dict:
        return _strip_none({
            "httpRequest": self.http_request.to_dict() if self.http_request else None,
            "httpClassCallback": self.http_class_callback.to_dict() if self.http_class_callback else None,
            "httpObjectCallback": self.http_object_callback.to_dict() if self.http_object_callback else None,
            "delay": self.delay.to_dict() if self.delay else None,
            "blocking": self.blocking,
            "timeout": self.timeout.to_dict() if self.timeout else None,
            "failurePolicy": self.failure_policy,
        })

    @classmethod
    def from_dict(cls, data: dict) -> AfterAction:
        if data is None:
            return None
        return cls(
            http_request=HttpRequest.from_dict(data.get("httpRequest")),
            http_class_callback=HttpClassCallback.from_dict(data.get("httpClassCallback")),
            http_object_callback=HttpObjectCallback.from_dict(data.get("httpObjectCallback")),
            delay=Delay.from_dict(data.get("delay")),
            blocking=data.get("blocking"),
            timeout=Delay.from_dict(data.get("timeout")),
            failure_policy=data.get("failurePolicy"),
        )


@dataclass
class ExpectationStep:
    """A single step in an ordered multi-action expectation pipeline.

    Each step carries exactly ONE action target and a ``responder`` flag.
    Steps without ``responder = True`` are side-effects (fire-and-forget
    webhooks/callbacks). Exactly one step in the list must be marked as the
    responder; that step's action produces the HTTP response.
    """

    # Action targets (exactly one must be set)
    http_request: HttpRequest | None = None
    http_class_callback: HttpClassCallback | None = None
    http_object_callback: HttpObjectCallback | None = None
    http_forward: HttpForward | None = None
    http_override_forwarded_request: HttpOverrideForwardedRequest | None = None
    http_response: HttpResponse | None = None
    http_error: HttpError | None = None
    # Step metadata
    responder: bool | None = None
    delay: Delay | None = None
    blocking: bool | None = None
    timeout: Delay | None = None
    failure_policy: str | None = None

    def to_dict(self) -> dict:
        return _strip_none({
            "httpRequest": self.http_request.to_dict() if self.http_request else None,
            "httpClassCallback": self.http_class_callback.to_dict() if self.http_class_callback else None,
            "httpObjectCallback": self.http_object_callback.to_dict() if self.http_object_callback else None,
            "httpForward": self.http_forward.to_dict() if self.http_forward else None,
            "httpOverrideForwardedRequest": self.http_override_forwarded_request.to_dict() if self.http_override_forwarded_request else None,
            "httpResponse": self.http_response.to_dict() if self.http_response else None,
            "httpError": self.http_error.to_dict() if self.http_error else None,
            "responder": self.responder,
            "delay": self.delay.to_dict() if self.delay else None,
            "blocking": self.blocking,
            "timeout": self.timeout.to_dict() if self.timeout else None,
            "failurePolicy": self.failure_policy,
        })

    @classmethod
    def from_dict(cls, data: dict) -> ExpectationStep:
        if data is None:
            return None
        return cls(
            http_request=HttpRequest.from_dict(data.get("httpRequest")),
            http_class_callback=HttpClassCallback.from_dict(data.get("httpClassCallback")),
            http_object_callback=HttpObjectCallback.from_dict(data.get("httpObjectCallback")),
            http_forward=HttpForward.from_dict(data.get("httpForward")),
            http_override_forwarded_request=HttpOverrideForwardedRequest.from_dict(data.get("httpOverrideForwardedRequest")),
            http_response=HttpResponse.from_dict(data.get("httpResponse")),
            http_error=HttpError.from_dict(data.get("httpError")),
            responder=data.get("responder"),
            delay=Delay.from_dict(data.get("delay")),
            blocking=data.get("blocking"),
            timeout=Delay.from_dict(data.get("timeout")),
            failure_policy=data.get("failurePolicy"),
        )


def _deserialize_http_llm_response(data: Any) -> Any | None:
    """Deserialize an ``httpLlmResponse`` action.

    Late-imports ``mockserver.llm`` to avoid a circular import at module load
    (``mockserver.llm`` imports ``Expectation`` from this module).
    """
    if data is None:
        return None
    from mockserver.llm import HttpLlmResponse

    return HttpLlmResponse.from_dict(data)


class ResponseMode:
    """How the server selects a response when an expectation has multiple
    ``http_responses``. Mirrors the core ``ResponseMode`` enum; use the string
    constants as the ``Expectation.response_mode`` value.
    """

    SEQUENTIAL = "SEQUENTIAL"
    RANDOM = "RANDOM"
    WEIGHTED = "WEIGHTED"
    SWITCH = "SWITCH"


class MockMode:
    """The high-level operating mode of MockServer. Mirrors the core
    ``MockMode`` enum; use the string constants as the
    :meth:`MockServerClient.set_mode` argument.

    - ``SIMULATE`` — match expectations, unmatched requests return ``404``
      (proxy-on-no-match disabled; the default).
    - ``SPY`` — match expectations; unmatched requests are forwarded to the real
      upstream and recorded.
    - ``CAPTURE`` — forward and record (captures all traffic when no
      expectations are defined).
    """

    SIMULATE = "SIMULATE"
    SPY = "SPY"
    CAPTURE = "CAPTURE"


class CrossProtocolTrigger:
    """The protocol event that advances a cross-protocol scenario. Mirrors the
    core ``CrossProtocolTrigger`` enum; use the string constants as the
    :class:`CrossProtocolScenario` ``trigger`` value.
    """

    DNS_QUERY = "DNS_QUERY"
    WEBSOCKET_CONNECT = "WEBSOCKET_CONNECT"
    GRPC_REQUEST = "GRPC_REQUEST"
    HTTP_REQUEST = "HTTP_REQUEST"


@dataclass
class CrossProtocolScenario:
    """Correlates a protocol event with a scenario state transition.

    When an event matching ``trigger`` (and optionally ``match_pattern``, a
    substring filter on the event identifier — omit to match all) is observed,
    the scenario named ``scenario_name`` is advanced to ``target_state``.
    """

    trigger: str | None = None
    scenario_name: str | None = None
    target_state: str | None = None
    match_pattern: str | None = None

    def to_dict(self) -> dict:
        return _strip_none({
            "trigger": self.trigger,
            "matchPattern": self.match_pattern,
            "scenarioName": self.scenario_name,
            "targetState": self.target_state,
        })

    @classmethod
    def from_dict(cls, data: dict) -> CrossProtocolScenario:
        if data is None:
            return None
        return cls(
            trigger=data.get("trigger"),
            scenario_name=data.get("scenarioName"),
            target_state=data.get("targetState"),
            match_pattern=data.get("matchPattern"),
        )


class RateLimitAlgorithm:
    """Algorithm constants for :attr:`RateLimit.algorithm`."""

    FIXED_WINDOW = "fixed_window"
    TOKEN_BUCKET = "token_bucket"


@dataclass
class RateLimit:
    """A declarative, protocol-agnostic rate limit / quota on an expectation.

    Expectations sharing a ``name`` share one counter. ``fixed_window`` uses
    ``limit`` + ``window_millis``; ``token_bucket`` uses ``burst`` +
    ``refill_per_second``.
    """

    name: str | None = None
    algorithm: str | None = None
    limit: int | None = None
    window_millis: int | None = None
    burst: int | None = None
    refill_per_second: float | None = None
    error_status: int | None = None
    retry_after: str | None = None

    def to_dict(self) -> dict:
        return _strip_none({
            "name": self.name,
            "algorithm": self.algorithm,
            "limit": self.limit,
            "windowMillis": self.window_millis,
            "burst": self.burst,
            "refillPerSecond": self.refill_per_second,
            "errorStatus": self.error_status,
            "retryAfter": self.retry_after,
        })

    @classmethod
    def from_dict(cls, data: dict) -> RateLimit:
        if data is None:
            return None
        return cls(
            name=data.get("name"),
            algorithm=data.get("algorithm"),
            limit=data.get("limit"),
            window_millis=data.get("windowMillis"),
            burst=data.get("burst"),
            refill_per_second=data.get("refillPerSecond"),
            error_status=data.get("errorStatus"),
            retry_after=data.get("retryAfter"),
        )


@dataclass
class HttpForwardWithFallback:
    """Forward the request to an upstream, but fall back to a canned response when
    the upstream fails (matched status codes and/or a timeout).

    Serialises to ``{"httpForward": .., "fallbackResponse": .., "fallbackOnStatusCodes":
    [..], "fallbackOnTimeout": bool, "delay": .., "primary": bool}``.
    """

    http_forward: HttpForward | None = None
    fallback_response: HttpResponse | None = None
    fallback_on_status_codes: list[int] | None = None
    fallback_on_timeout: bool | None = None
    delay: Delay | None = None
    primary: bool | None = None

    def to_dict(self) -> dict:
        return _strip_none({
            "httpForward": self.http_forward.to_dict() if self.http_forward else None,
            "fallbackResponse": self.fallback_response.to_dict() if self.fallback_response else None,
            "fallbackOnStatusCodes": self.fallback_on_status_codes,
            "fallbackOnTimeout": self.fallback_on_timeout,
            "delay": self.delay.to_dict() if self.delay else None,
            "primary": self.primary,
        })

    @classmethod
    def from_dict(cls, data: dict) -> HttpForwardWithFallback:
        if data is None:
            return None
        return cls(
            http_forward=HttpForward.from_dict(data.get("httpForward")),
            fallback_response=HttpResponse.from_dict(data.get("fallbackResponse")),
            fallback_on_status_codes=data.get("fallbackOnStatusCodes"),
            fallback_on_timeout=data.get("fallbackOnTimeout"),
            delay=Delay.from_dict(data.get("delay")),
            primary=data.get("primary"),
        )


class ValidationMode:
    """Validation-mode constants for :attr:`HttpForwardValidateAction.validation_mode`."""

    STRICT = "STRICT"
    LOG_ONLY = "LOG_ONLY"


@dataclass
class HttpForwardValidateAction:
    """Forward the request to an upstream and validate the exchange against an
    OpenAPI specification.

    Serialises to ``{"specUrlOrPayload": .., "host": .., "port": .., "scheme": ..,
    "validateRequest": bool, "validateResponse": bool, "validationMode": .., "delay": ..,
    "primary": bool}``.
    """

    spec_url_or_payload: str | None = None
    host: str | None = None
    port: int | None = None
    scheme: str | None = None
    validate_request: bool | None = None
    validate_response: bool | None = None
    validation_mode: str | None = None
    delay: Delay | None = None
    primary: bool | None = None

    def to_dict(self) -> dict:
        return _strip_none({
            "specUrlOrPayload": self.spec_url_or_payload,
            "host": self.host,
            "port": self.port,
            "scheme": self.scheme,
            "validateRequest": self.validate_request,
            "validateResponse": self.validate_response,
            "validationMode": self.validation_mode,
            "delay": self.delay.to_dict() if self.delay else None,
            "primary": self.primary,
        })

    @classmethod
    def from_dict(cls, data: dict) -> HttpForwardValidateAction:
        if data is None:
            return None
        return cls(
            spec_url_or_payload=data.get("specUrlOrPayload"),
            host=data.get("host"),
            port=data.get("port"),
            scheme=data.get("scheme"),
            validate_request=data.get("validateRequest"),
            validate_response=data.get("validateResponse"),
            validation_mode=data.get("validationMode"),
            delay=Delay.from_dict(data.get("delay")),
            primary=data.get("primary"),
        )


class CaptureSource:
    """Source constants for :attr:`CaptureRule.source` (the part of the request a
    capture rule reads from). Mirrors the core ``CaptureRule.Source`` enum — note the
    values are camelCase, matching the wire form.
    """

    JSON_PATH = "jsonPath"
    XPATH = "xpath"
    HEADER = "header"
    QUERY_STRING_PARAMETER = "queryStringParameter"
    COOKIE = "cookie"
    PATH_PARAMETER = "pathParameter"


@dataclass
class CaptureRule:
    """Captures a value from the matched request into scenario/session state for use
    by later scenario-trigger calls.

    Serialises to ``{"source": .., "expression": .., "into": ..}``.
    """

    source: str | None = None
    expression: str | None = None
    into: str | None = None

    def to_dict(self) -> dict:
        return _strip_none({
            "source": self.source,
            "expression": self.expression,
            "into": self.into,
        })

    @classmethod
    def from_dict(cls, data: dict) -> CaptureRule:
        if data is None:
            return None
        return cls(
            source=data.get("source"),
            expression=data.get("expression"),
            into=data.get("into"),
        )


@dataclass
class Expectation:
    id: str | None = None
    priority: int | None = None
    percentage: int | None = None
    http_request: HttpRequest | DnsRequestDefinition | ConditionalRequestDefinition | None = None
    http_response: HttpResponse | None = None
    http_response_template: HttpTemplate | None = None
    http_response_class_callback: HttpClassCallback | None = None
    http_response_object_callback: HttpObjectCallback | None = None
    http_forward: HttpForward | None = None
    http_forward_template: HttpTemplate | None = None
    http_forward_class_callback: HttpClassCallback | None = None
    http_forward_object_callback: HttpObjectCallback | None = None
    http_override_forwarded_request: HttpOverrideForwardedRequest | None = None
    http_error: HttpError | None = None
    http_sse_response: HttpSseResponse | None = None
    http_websocket_response: HttpWebSocketResponse | None = None
    grpc_stream_response: GrpcStreamResponse | None = None
    grpc_bidi_response: GrpcBidiResponse | None = None
    binary_response: BinaryResponse | None = None
    dns_response: DnsResponse | None = None
    # http_llm_response is an mockserver.llm.HttpLlmResponse; typed as Any to
    # avoid a circular import (mockserver.llm imports Expectation from here).
    http_llm_response: Any | None = None
    http_forward_with_fallback: HttpForwardWithFallback | None = None
    http_forward_validate_action: HttpForwardValidateAction | None = None
    times: Times | None = None
    time_to_live: TimeToLive | None = None
    chaos: HttpChaosProfile | None = None
    rate_limit: RateLimit | None = None
    capture: list[CaptureRule] | None = None
    namespace: str | None = None
    before_actions: list[AfterAction] | None = None
    after_actions: list[AfterAction] | None = None
    http_responses: list[HttpResponse] | None = None
    response_mode: str | None = None
    response_weights: list[int] | None = None
    switch_after: int | None = None
    cross_protocol_scenarios: list[CrossProtocolScenario] | None = None
    steps: list[ExpectationStep] | None = None
    scenario_name: str | None = None
    scenario_state: str | None = None
    new_scenario_state: str | None = None
    # Server-assigned creation timestamp (ISO-8601), echoed back on retrieval.
    timestamp: str | None = None

    def to_dict(self) -> dict:
        return _strip_none({
            "id": self.id,
            "priority": self.priority,
            "percentage": self.percentage,
            "httpRequest": self.http_request.to_dict() if self.http_request else None,
            "httpResponse": self.http_response.to_dict() if self.http_response else None,
            "httpResponseTemplate": self.http_response_template.to_dict() if self.http_response_template else None,
            "httpResponseClassCallback": self.http_response_class_callback.to_dict() if self.http_response_class_callback else None,
            "httpResponseObjectCallback": self.http_response_object_callback.to_dict() if self.http_response_object_callback else None,
            "httpForward": self.http_forward.to_dict() if self.http_forward else None,
            "httpForwardTemplate": self.http_forward_template.to_dict() if self.http_forward_template else None,
            "httpForwardClassCallback": self.http_forward_class_callback.to_dict() if self.http_forward_class_callback else None,
            "httpForwardObjectCallback": self.http_forward_object_callback.to_dict() if self.http_forward_object_callback else None,
            "httpForwardWithFallback": self.http_forward_with_fallback.to_dict() if self.http_forward_with_fallback else None,
            "httpForwardValidateAction": self.http_forward_validate_action.to_dict() if self.http_forward_validate_action else None,
            "httpOverrideForwardedRequest": self.http_override_forwarded_request.to_dict() if self.http_override_forwarded_request else None,
            "httpError": self.http_error.to_dict() if self.http_error else None,
            "httpSseResponse": self.http_sse_response.to_dict() if self.http_sse_response else None,
            "httpWebSocketResponse": self.http_websocket_response.to_dict() if self.http_websocket_response else None,
            "grpcStreamResponse": self.grpc_stream_response.to_dict() if self.grpc_stream_response else None,
            "grpcBidiResponse": self.grpc_bidi_response.to_dict() if self.grpc_bidi_response else None,
            "binaryResponse": self.binary_response.to_dict() if self.binary_response else None,
            "dnsResponse": self.dns_response.to_dict() if self.dns_response else None,
            "httpLlmResponse": self.http_llm_response.to_dict() if self.http_llm_response else None,
            "times": self.times.to_dict() if self.times else None,
            "timeToLive": self.time_to_live.to_dict() if self.time_to_live else None,
            "chaos": self.chaos.to_dict() if self.chaos else None,
            "rateLimit": self.rate_limit.to_dict() if self.rate_limit else None,
            "capture": [c.to_dict() for c in self.capture] if self.capture else None,
            "namespace": self.namespace,
            "beforeActions": [a.to_dict() for a in self.before_actions] if self.before_actions else None,
            "afterActions": [a.to_dict() for a in self.after_actions] if self.after_actions else None,
            "httpResponses": [r.to_dict() for r in self.http_responses] if self.http_responses else None,
            "responseMode": self.response_mode,
            "responseWeights": self.response_weights,
            "switchAfter": self.switch_after,
            "crossProtocolScenarios": [c.to_dict() for c in self.cross_protocol_scenarios] if self.cross_protocol_scenarios else None,
            "steps": [s.to_dict() for s in self.steps] if self.steps else None,
            "scenarioName": self.scenario_name,
            "scenarioState": self.scenario_state,
            "newScenarioState": self.new_scenario_state,
            "timestamp": self.timestamp,
        })

    @classmethod
    def from_dict(cls, data: dict) -> Expectation:
        if data is None:
            return None
        before_actions_data = data.get("beforeActions")
        if isinstance(before_actions_data, dict):
            before_actions_data = [before_actions_data]
        after_actions_data = data.get("afterActions")
        if isinstance(after_actions_data, dict):
            after_actions_data = [after_actions_data]
        return cls(
            id=data.get("id"),
            priority=data.get("priority"),
            percentage=data.get("percentage"),
            http_request=_deserialize_request_definition(data.get("httpRequest")),
            http_response=HttpResponse.from_dict(data.get("httpResponse")),
            http_response_template=HttpTemplate.from_dict(data.get("httpResponseTemplate")),
            http_response_class_callback=HttpClassCallback.from_dict(data.get("httpResponseClassCallback")),
            http_response_object_callback=HttpObjectCallback.from_dict(data.get("httpResponseObjectCallback")),
            http_forward=HttpForward.from_dict(data.get("httpForward")),
            http_forward_template=HttpTemplate.from_dict(data.get("httpForwardTemplate")),
            http_forward_class_callback=HttpClassCallback.from_dict(data.get("httpForwardClassCallback")),
            http_forward_object_callback=HttpObjectCallback.from_dict(data.get("httpForwardObjectCallback")),
            http_forward_with_fallback=HttpForwardWithFallback.from_dict(data.get("httpForwardWithFallback")),
            http_forward_validate_action=HttpForwardValidateAction.from_dict(data.get("httpForwardValidateAction")),
            http_override_forwarded_request=HttpOverrideForwardedRequest.from_dict(data.get("httpOverrideForwardedRequest")),
            http_error=HttpError.from_dict(data.get("httpError")),
            http_sse_response=HttpSseResponse.from_dict(data.get("httpSseResponse")),
            http_websocket_response=HttpWebSocketResponse.from_dict(data.get("httpWebSocketResponse")),
            grpc_stream_response=GrpcStreamResponse.from_dict(data.get("grpcStreamResponse")),
            grpc_bidi_response=GrpcBidiResponse.from_dict(data.get("grpcBidiResponse")),
            binary_response=BinaryResponse.from_dict(data.get("binaryResponse")),
            dns_response=DnsResponse.from_dict(data.get("dnsResponse")),
            http_llm_response=_deserialize_http_llm_response(data.get("httpLlmResponse")),
            times=Times.from_dict(data.get("times")),
            time_to_live=TimeToLive.from_dict(data.get("timeToLive")),
            chaos=HttpChaosProfile.from_dict(data.get("chaos")),
            rate_limit=RateLimit.from_dict(data.get("rateLimit")),
            capture=[CaptureRule.from_dict(c) for c in data["capture"]] if data.get("capture") else None,
            namespace=data.get("namespace"),
            before_actions=[AfterAction.from_dict(a) for a in before_actions_data] if before_actions_data else None,
            after_actions=[AfterAction.from_dict(a) for a in after_actions_data] if after_actions_data else None,
            http_responses=[HttpResponse.from_dict(r) for r in data["httpResponses"]] if data.get("httpResponses") else None,
            response_mode=data.get("responseMode"),
            response_weights=data.get("responseWeights"),
            switch_after=data.get("switchAfter"),
            cross_protocol_scenarios=[CrossProtocolScenario.from_dict(c) for c in data["crossProtocolScenarios"]] if data.get("crossProtocolScenarios") else None,
            steps=[ExpectationStep.from_dict(s) for s in data["steps"]] if data.get("steps") else None,
            scenario_name=data.get("scenarioName"),
            scenario_state=data.get("scenarioState"),
            new_scenario_state=data.get("newScenarioState"),
            timestamp=data.get("timestamp"),
        )


@dataclass
class OpenAPIDefinition:
    spec_url_or_payload: str | None = None
    operation_id: str | None = None
    context_path_prefix: str | None = None
    # Negates the matcher (wire key "not"), as HttpRequest.not_request does.
    not_request: bool | None = None

    def to_dict(self) -> dict:
        return _strip_none({
            "not": self.not_request,
            "specUrlOrPayload": self.spec_url_or_payload,
            "operationId": self.operation_id,
            "contextPathPrefix": self.context_path_prefix,
        })

    @classmethod
    def from_dict(cls, data: dict) -> OpenAPIDefinition:
        if data is None:
            return None
        return cls(
            spec_url_or_payload=data.get("specUrlOrPayload"),
            operation_id=data.get("operationId"),
            context_path_prefix=data.get("contextPathPrefix"),
            not_request=data.get("not"),
        )


@dataclass
class OpenAPIExpectation:
    spec_url_or_payload: str | None = None
    operations_and_responses: dict | None = None

    def to_dict(self) -> dict:
        return _strip_none({
            "specUrlOrPayload": self.spec_url_or_payload,
            "operationsAndResponses": self.operations_and_responses,
        })

    @classmethod
    def from_dict(cls, data: dict) -> OpenAPIExpectation:
        if data is None:
            return None
        return cls(
            spec_url_or_payload=data.get("specUrlOrPayload"),
            operations_and_responses=data.get("operationsAndResponses"),
        )


@dataclass
class VerificationTimes:
    at_least: int | None = None
    at_most: int | None = None

    def to_dict(self) -> dict:
        return _strip_none({
            "atLeast": self.at_least,
            "atMost": self.at_most,
        })

    @classmethod
    def from_dict(cls, data: dict) -> VerificationTimes:
        if data is None:
            return None
        return cls(
            at_least=data.get("atLeast"),
            at_most=data.get("atMost"),
        )


def _vt_at_least(count: int) -> VerificationTimes:
    return VerificationTimes(at_least=count)


def _vt_at_most(count: int) -> VerificationTimes:
    return VerificationTimes(at_most=count)


def _vt_exactly(count: int) -> VerificationTimes:
    return VerificationTimes(at_least=count, at_most=count)


def _vt_once() -> VerificationTimes:
    return VerificationTimes(at_least=1, at_most=1)


def _vt_between(at_least: int, at_most: int) -> VerificationTimes:
    return VerificationTimes(at_least=at_least, at_most=at_most)


VerificationTimes.at_least = staticmethod(_vt_at_least)
VerificationTimes.at_most = staticmethod(_vt_at_most)
VerificationTimes.exactly = staticmethod(_vt_exactly)
VerificationTimes.once = staticmethod(_vt_once)
VerificationTimes.between = staticmethod(_vt_between)


@dataclass
class Verification:
    http_request: HttpRequest | None = None
    http_response: HttpResponse | None = None
    expectation_id: ExpectationId | None = None
    times: VerificationTimes | None = None
    maximum_number_of_request_to_return_in_verification_failure: int | None = None

    def to_dict(self) -> dict:
        return _strip_none({
            "httpRequest": self.http_request.to_dict() if self.http_request else None,
            "httpResponse": self.http_response.to_dict() if self.http_response else None,
            "expectationId": self.expectation_id.to_dict() if self.expectation_id else None,
            "times": self.times.to_dict() if self.times else None,
            "maximumNumberOfRequestToReturnInVerificationFailure": self.maximum_number_of_request_to_return_in_verification_failure,
        })

    @classmethod
    def from_dict(cls, data: dict) -> Verification:
        if data is None:
            return None
        return cls(
            http_request=_deserialize_conditional_branch(data.get("httpRequest")),
            http_response=HttpResponse.from_dict(data.get("httpResponse")),
            expectation_id=ExpectationId.from_dict(data.get("expectationId")),
            times=VerificationTimes.from_dict(data.get("times")),
            maximum_number_of_request_to_return_in_verification_failure=data.get("maximumNumberOfRequestToReturnInVerificationFailure"),
        )


@dataclass
class VerificationSequence:
    http_requests: list[HttpRequest] | None = None
    http_responses: list[HttpResponse] | None = None
    expectation_ids: list[ExpectationId] | None = None

    def to_dict(self) -> dict:
        return _strip_none({
            "httpRequests": [r.to_dict() for r in self.http_requests] if self.http_requests else None,
            "httpResponses": [r.to_dict() for r in self.http_responses] if self.http_responses else None,
            "expectationIds": [e.to_dict() for e in self.expectation_ids] if self.expectation_ids else None,
        })

    @classmethod
    def from_dict(cls, data: dict) -> VerificationSequence:
        if data is None:
            return None
        http_requests_data = data.get("httpRequests")
        http_responses_data = data.get("httpResponses")
        expectation_ids_data = data.get("expectationIds")
        return cls(
            http_requests=[HttpRequest.from_dict(r) for r in http_requests_data] if http_requests_data else None,
            http_responses=[HttpResponse.from_dict(r) for r in http_responses_data] if http_responses_data else None,
            expectation_ids=[ExpectationId.from_dict(e) for e in expectation_ids_data] if expectation_ids_data else None,
        )


@dataclass
class Ports:
    ports: list[int] = field(default_factory=list)

    def to_dict(self) -> dict:
        return {"ports": self.ports}

    @classmethod
    def from_dict(cls, data: dict) -> Ports:
        if data is None:
            return None
        return cls(ports=data.get("ports", []))


@dataclass
class CrudExpectationsDefinition:
    base_path: str = ""
    id_field: str = "id"
    id_strategy: str = "AUTO_INCREMENT"
    initial_data: list[dict] | None = None

    def to_dict(self) -> dict:
        return _strip_none({
            "basePath": self.base_path,
            "idField": self.id_field,
            "idStrategy": self.id_strategy,
            "initialData": self.initial_data,
        })

    @classmethod
    def from_dict(cls, data: dict) -> CrudExpectationsDefinition:
        if data is None:
            return None
        return cls(
            base_path=data.get("basePath", ""),
            id_field=data.get("idField", "id"),
            id_strategy=data.get("idStrategy", "AUTO_INCREMENT"),
            initial_data=data.get("initialData"),
        )


@dataclass
class LoadStage:
    """One stage of a :class:`LoadProfile`, run in sequence.

    Each stage holds or ramps a setpoint for ``duration_millis``:

    * ``VU`` (closed model) — hold ``vus`` virtual users, or ramp from
      ``start_vus`` to ``end_vus`` along ``curve``.
    * ``RATE`` (open model) — hold ``rate`` iterations/second, or ramp from
      ``start_rate`` to ``end_rate`` along ``curve``, optionally capping the
      auto-scaling virtual-user pool at ``max_vus``.
    * ``PAUSE`` — drive no load for ``duration_millis``.

    Prefer the :meth:`vu_stage`, :meth:`rate_stage` and :meth:`pause_stage`
    factories, which emit only the fields relevant to the stage type and mode.
    """

    type: str = "VU"
    duration_millis: int = 0
    curve: str | None = None
    vus: int | None = None
    start_vus: int | None = None
    end_vus: int | None = None
    rate: float | None = None
    start_rate: float | None = None
    end_rate: float | None = None
    max_vus: int | None = None

    @classmethod
    def vu_stage(
        cls,
        duration_millis: int,
        *,
        vus: int | None = None,
        start_vus: int | None = None,
        end_vus: int | None = None,
        curve: str | None = None,
    ) -> LoadStage:
        """A ``VU`` (closed-model) stage — hold ``vus`` or ramp ``start_vus``→``end_vus``."""
        return cls(
            type="VU",
            duration_millis=duration_millis,
            vus=vus,
            start_vus=start_vus,
            end_vus=end_vus,
            curve=curve,
        )

    @classmethod
    def rate_stage(
        cls,
        duration_millis: int,
        *,
        rate: float | None = None,
        start_rate: float | None = None,
        end_rate: float | None = None,
        max_vus: int | None = None,
        curve: str | None = None,
    ) -> LoadStage:
        """A ``RATE`` (open-model) stage — hold ``rate`` or ramp ``start_rate``→``end_rate`` (iterations/second)."""
        return cls(
            type="RATE",
            duration_millis=duration_millis,
            rate=rate,
            start_rate=start_rate,
            end_rate=end_rate,
            max_vus=max_vus,
            curve=curve,
        )

    @classmethod
    def pause_stage(cls, duration_millis: int) -> LoadStage:
        """A ``PAUSE`` stage — drive no load for ``duration_millis``."""
        return cls(type="PAUSE", duration_millis=duration_millis)

    def to_dict(self) -> dict:
        return _strip_none({
            "type": self.type,
            "durationMillis": self.duration_millis,
            "curve": self.curve,
            "vus": self.vus,
            "startVus": self.start_vus,
            "endVus": self.end_vus,
            "rate": self.rate,
            "startRate": self.start_rate,
            "endRate": self.end_rate,
            "maxVus": self.max_vus,
        })

    @classmethod
    def from_dict(cls, data: dict) -> LoadStage:
        if data is None:
            return None
        return cls(
            type=data.get("type", "VU"),
            duration_millis=data.get("durationMillis", 0),
            curve=data.get("curve"),
            vus=data.get("vus"),
            start_vus=data.get("startVus"),
            end_vus=data.get("endVus"),
            rate=data.get("rate"),
            start_rate=data.get("startRate"),
            end_rate=data.get("endRate"),
            max_vus=data.get("maxVus"),
        )


@dataclass
class LoadShape:
    """A declarative named load shape that expands into ordinary stages.

    Use a shape **or** an explicit ``stages`` list on a :class:`LoadProfile`, not
    both. Only the parameters the shape ``type`` needs are read:

    * ``SPIKE`` — ramp ``baseline``→``peak`` over ``ramp_up_millis``, hold for
      ``hold_millis``, ramp back down over ``ramp_down_millis`` and optionally
      hold the baseline again for ``recovery_hold_millis``.
    * ``STAIRS`` — a flight of pure-hold steps from ``start``, each ``step``
      higher, ``steps`` of them, each held for ``step_duration_millis``.
    * ``RAMP_HOLD`` — ramp 0→``target`` over ``ramp_millis`` then hold for
      ``hold_millis``.

    ``metric`` selects what the shape drives: ``VU`` (concurrent virtual users,
    closed model) or ``RATE`` (arrival rate, open model).
    """

    type: str = "RAMP_HOLD"
    metric: str | None = None
    curve: str | None = None
    baseline: float | None = None
    peak: float | None = None
    ramp_up_millis: int | None = None
    hold_millis: int | None = None
    ramp_down_millis: int | None = None
    recovery_hold_millis: int | None = None
    start: float | None = None
    step: float | None = None
    steps: int | None = None
    step_duration_millis: int | None = None
    target: float | None = None
    ramp_millis: int | None = None

    def to_dict(self) -> dict:
        return _strip_none({
            "type": self.type,
            "metric": self.metric,
            "curve": self.curve,
            "baseline": self.baseline,
            "peak": self.peak,
            "rampUpMillis": self.ramp_up_millis,
            "holdMillis": self.hold_millis,
            "rampDownMillis": self.ramp_down_millis,
            "recoveryHoldMillis": self.recovery_hold_millis,
            "start": self.start,
            "step": self.step,
            "steps": self.steps,
            "stepDurationMillis": self.step_duration_millis,
            "target": self.target,
            "rampMillis": self.ramp_millis,
        })

    @classmethod
    def from_dict(cls, data: dict) -> LoadShape:
        if data is None:
            return None
        return cls(
            type=data.get("type", "RAMP_HOLD"),
            metric=data.get("metric"),
            curve=data.get("curve"),
            baseline=data.get("baseline"),
            peak=data.get("peak"),
            ramp_up_millis=data.get("rampUpMillis"),
            hold_millis=data.get("holdMillis"),
            ramp_down_millis=data.get("rampDownMillis"),
            recovery_hold_millis=data.get("recoveryHoldMillis"),
            start=data.get("start"),
            step=data.get("step"),
            steps=data.get("steps"),
            step_duration_millis=data.get("stepDurationMillis"),
            target=data.get("target"),
            ramp_millis=data.get("rampMillis"),
        )


@dataclass
class LoadProfile:
    """The shape of load injected by a :class:`LoadScenario`.

    A profile is EITHER an ordered list of :class:`LoadStage` objects run in
    sequence, OR a single named :class:`LoadShape` (which expands into stages).
    Set one, not both; if both are set the explicit ``stages`` win. Each stage
    holds or ramps a setpoint (virtual users, an arrival rate, or a pause) for
    its duration; the total run length is the sum of stage durations.
    """

    stages: list[LoadStage] = field(default_factory=list)
    shape: LoadShape | None = None

    def to_dict(self) -> dict:
        return _strip_none({
            "stages": [s.to_dict() for s in self.stages] if self.stages else None,
            "shape": self.shape.to_dict() if self.shape else None,
        })

    @classmethod
    def from_dict(cls, data: dict) -> LoadProfile:
        if data is None:
            return None
        stages = data.get("stages") or []
        return cls(
            stages=[LoadStage.from_dict(s) for s in stages],
            shape=LoadShape.from_dict(data.get("shape")),
        )


@dataclass
class LoadCapture:
    """A cross-step capture / correlation rule for a :class:`LoadStep`.

    Extracts a value from a step's response and binds it to ``name``, visible to
    SUBSEQUENT steps in the same iteration via ``$iteration.captured.<name>``
    (Velocity) / ``{{iteration.captured.<name>}}`` (Mustache). ``source`` is one
    of ``BODY_JSONPATH`` (a JSONPath over the body), ``HEADER`` (a header value),
    or ``BODY_REGEX`` (a regex over the body string, capture group 1).
    ``expression`` drives the extraction; on no match it falls back to
    ``default_value`` (when set) and never fails the run.
    """

    name: str = ""
    source: str = "BODY_JSONPATH"
    expression: str = ""
    default_value: str | None = None

    def to_dict(self) -> dict:
        return _strip_none({
            "name": self.name,
            "source": self.source,
            "expression": self.expression,
            "defaultValue": self.default_value,
        })

    @classmethod
    def from_dict(cls, data: dict) -> LoadCapture:
        if data is None:
            return None
        return cls(
            name=data.get("name", ""),
            source=data.get("source", "BODY_JSONPATH"),
            expression=data.get("expression", ""),
            default_value=data.get("defaultValue"),
        )


@dataclass
class LoadCheck:
    """A per-step response assertion for a :class:`LoadStep`.

    Reads a value from the step's response — ``source`` ``STATUS``, ``HEADER``
    (with ``header_name``) or ``BODY_JSONPATH`` (with ``json_path``) — and
    compares it with ``value`` using ``comparator`` (``EQUALS``, ``NOT_EQUALS``,
    ``CONTAINS``, ``MATCHES``, ``GT``, ``LT``, ``GTE`` or ``LTE``). A failing
    check never fails the request; failures feed the ``CHECK_FAILURE_RATE``
    threshold.
    """

    source: str = "STATUS"
    comparator: str = "EQUALS"
    value: str | None = None
    header_name: str | None = None
    json_path: str | None = None

    def to_dict(self) -> dict:
        return _strip_none({
            "source": self.source,
            "headerName": self.header_name,
            "jsonPath": self.json_path,
            "comparator": self.comparator,
            "value": self.value,
        })

    @classmethod
    def from_dict(cls, data: dict) -> LoadCheck:
        if data is None:
            return None
        return cls(
            source=data.get("source", "STATUS"),
            comparator=data.get("comparator", "EQUALS"),
            value=data.get("value"),
            header_name=data.get("headerName"),
            json_path=data.get("jsonPath"),
        )


@dataclass
class LoadStep:
    """A single request issued on each iteration of a :class:`LoadScenario`.

    ``think_time`` is inter-step pacing (a :class:`Delay`) applied after the
    request before the next step. ``request`` reuses :class:`HttpRequest`; its
    template placeholders are rendered per iteration. ``captures`` bind values
    from this step's response for later steps in the same iteration (see
    :class:`LoadCapture`). ``weight`` is the relative selection weight used only
    when the scenario's ``step_selection`` is ``WEIGHTED``. ``checks`` assert on
    this step's response (see :class:`LoadCheck`).
    """

    request: HttpRequest | None = None
    think_time: Delay | None = None
    name: str | None = None
    labels: dict | None = None
    captures: list[LoadCapture] | None = None
    weight: float | None = None
    checks: list[LoadCheck] | None = None

    def to_dict(self) -> dict:
        return _strip_none({
            "request": self.request.to_dict() if self.request else None,
            "thinkTime": self.think_time.to_dict() if self.think_time else None,
            "name": self.name,
            "labels": self.labels,
            "captures": [c.to_dict() for c in self.captures] if self.captures else None,
            "checks": [c.to_dict() for c in self.checks] if self.checks else None,
            "weight": self.weight,
        })

    @classmethod
    def from_dict(cls, data: dict) -> LoadStep:
        if data is None:
            return None
        captures = data.get("captures")
        checks = data.get("checks")
        return cls(
            request=HttpRequest.from_dict(data.get("request")),
            think_time=Delay.from_dict(data.get("thinkTime")),
            name=data.get("name"),
            labels=data.get("labels"),
            captures=[LoadCapture.from_dict(c) for c in captures] if captures is not None else None,
            weight=data.get("weight"),
            checks=[LoadCheck.from_dict(c) for c in checks] if checks is not None else None,
        )


@dataclass
class LoadThreshold:
    """An in-run pass/fail threshold for a :class:`LoadScenario`.

    A per-run ``metric`` (``LATENCY_P50``/``LATENCY_P95``/``LATENCY_P99``/
    ``LATENCY_P999`` in milliseconds, ``ERROR_RATE`` as a 0.0-1.0 fraction, or
    ``THROUGHPUT_RPS`` in requests/second) compared via ``comparator``
    (``LESS_THAN``/``LESS_THAN_OR_EQUAL``/``GREATER_THAN``/
    ``GREATER_THAN_OR_EQUAL``) against ``threshold``. All thresholds must hold
    for the run verdict to be ``PASS``.
    """

    metric: str = "LATENCY_P95"
    comparator: str = "LESS_THAN"
    threshold: float = 0.0

    def to_dict(self) -> dict:
        return _strip_none({
            "metric": self.metric,
            "comparator": self.comparator,
            "threshold": self.threshold,
        })

    @classmethod
    def from_dict(cls, data: dict) -> LoadThreshold:
        if data is None:
            return None
        return cls(
            metric=data.get("metric", "LATENCY_P95"),
            comparator=data.get("comparator", "LESS_THAN"),
            threshold=data.get("threshold", 0.0),
        )


@dataclass
class LoadPacing:
    """Adaptive iteration pacing (think-time) for a :class:`LoadScenario`.

    A target per-virtual-user iteration cycle time, applied only to the
    closed-model VU loop (open-model RATE iterations ignore it). ``mode`` is one
    of ``NONE`` (no pacing), ``CONSTANT_PACING`` (``value`` is the target cycle
    in milliseconds), or ``CONSTANT_THROUGHPUT`` (``value`` is the target
    iterations/second per VU). ``value`` must be > 0 when ``mode`` is not
    ``NONE``.
    """

    mode: str = "NONE"
    value: float = 0.0

    def to_dict(self) -> dict:
        return _strip_none({
            "mode": self.mode,
            "value": self.value,
        })

    @classmethod
    def from_dict(cls, data: dict) -> LoadPacing:
        if data is None:
            return None
        return cls(
            mode=data.get("mode", "NONE"),
            value=data.get("value", 0.0),
        )


@dataclass
class LoadFeeder:
    """Parameterized test data (a data feeder) for a :class:`LoadScenario`.

    One row is selected per iteration and exposed to that iteration's templated
    request as ``$iteration.data.<column>`` (Velocity) /
    ``{{iteration.data.<column>}}`` (Mustache). Supply EITHER ``rows`` (an inline
    list of column→value maps, the primary form) OR ``data`` + ``format`` (raw
    inline ``CSV``/``JSON`` parsed server-side); when both are given ``rows``
    wins. ``strategy`` chooses how a row is picked each iteration: ``CIRCULAR``
    (default), ``RANDOM`` or ``SEQUENTIAL``.
    """

    rows: list[dict] | None = None
    data: str | None = None
    format: str | None = None
    strategy: str | None = None

    def to_dict(self) -> dict:
        return _strip_none({
            "rows": self.rows,
            "data": self.data,
            "format": self.format,
            "strategy": self.strategy,
        })

    @classmethod
    def from_dict(cls, data: dict) -> LoadFeeder:
        if data is None:
            return None
        return cls(
            rows=data.get("rows"),
            data=data.get("data"),
            format=data.get("format"),
            strategy=data.get("strategy"),
        )


@dataclass
class LoadScenario:
    """A load-injection scenario registered with the server's load generator.

    Registering a scenario (``load_scenario``) adds it to the server-side
    registry in the ``LOADED`` state but does **not** start generating load —
    registration is allowed even when ``loadGenerationEnabled`` is off. Starting
    a registered scenario (``start_load_scenarios``) requires the server to have
    been started with ``loadGenerationEnabled``; otherwise the start endpoint
    responds ``403`` and the client raises an error.

    ``start_delay_millis`` defers the start of load generation by the given
    number of milliseconds after the scenario is started.
    """

    name: str | None = None
    profile: LoadProfile | None = None
    steps: list[LoadStep] | None = None
    template_type: str | None = None
    max_requests: int | None = None
    start_delay_millis: int | None = None
    labels: dict | None = None
    thresholds: list[LoadThreshold] | None = None
    abort_on_fail: bool | None = None
    abort_grace_millis: int | None = None
    pacing: LoadPacing | None = None
    feeder: LoadFeeder | None = None
    step_selection: str | None = None

    def to_dict(self) -> dict:
        return _strip_none({
            "name": self.name,
            "profile": self.profile.to_dict() if self.profile else None,
            "steps": [s.to_dict() for s in self.steps] if self.steps is not None else None,
            "templateType": self.template_type,
            "maxRequests": self.max_requests,
            "startDelayMillis": self.start_delay_millis,
            "labels": self.labels,
            "thresholds": [t.to_dict() for t in self.thresholds] if self.thresholds else None,
            "abortOnFail": self.abort_on_fail,
            "abortGraceMillis": self.abort_grace_millis,
            "pacing": self.pacing.to_dict() if self.pacing else None,
            "feeder": self.feeder.to_dict() if self.feeder else None,
            "stepSelection": self.step_selection,
        })

    @classmethod
    def from_dict(cls, data: dict) -> LoadScenario:
        if data is None:
            return None
        steps = data.get("steps")
        thresholds = data.get("thresholds")
        return cls(
            name=data.get("name"),
            profile=LoadProfile.from_dict(data.get("profile")),
            steps=[LoadStep.from_dict(s) for s in steps] if steps is not None else None,
            template_type=data.get("templateType"),
            max_requests=data.get("maxRequests"),
            start_delay_millis=data.get("startDelayMillis"),
            labels=data.get("labels"),
            thresholds=[LoadThreshold.from_dict(t) for t in thresholds] if thresholds is not None else None,
            abort_on_fail=data.get("abortOnFail"),
            abort_grace_millis=data.get("abortGraceMillis"),
            pacing=LoadPacing.from_dict(data.get("pacing")),
            feeder=LoadFeeder.from_dict(data.get("feeder")),
            step_selection=data.get("stepSelection"),
        )



# ---------------------------------------------------------------------------
# Diagnostics: explain, debug_mismatch and explain_unmatched results. These
# follow the RequestExplanation and ExplainedExpectation OpenAPI schemas; an
# enum value a newer server sends is kept as its string.
# ---------------------------------------------------------------------------


def _from_list(cls, items: list | None) -> list:
    return [cls.from_dict(item) for item in items or []]


@dataclass
class SuggestedFix:
    """A JSON patch (RFC 6902) of an expectation that would make it match the
    request; nothing is applied. ``unpatched_fields`` lists each field that
    could not be patched, with a ``reason``."""

    patch: list[dict] = field(default_factory=list)
    patched_fields: list[str] = field(default_factory=list)
    unpatched_fields: list[dict] = field(default_factory=list)
    matches_after_patch: bool = False

    @classmethod
    def from_dict(cls, data: dict | None) -> SuggestedFix | None:
        if data is None:
            return None
        return cls(
            patch=list(data.get("patch") or []),
            patched_fields=list(data.get("patchedFields") or []),
            unpatched_fields=list(data.get("unpatchedFields") or []),
            matches_after_patch=bool(data.get("matchesAfterPatch", False)),
        )


@dataclass
class ExplainedExpectation:
    """One expectation as explain, debug_mismatch or explain_unmatched
    evaluated it. The matched expectation has no ``reason``; a skipped or
    shadowed one has a ``reason`` (``NAMESPACE``, ``INACTIVE``,
    ``FIELDS_DIFFER``, ``NOT_OPERATOR``, ``CONVERSATION``, ``SCENARIO_STATE``,
    ``PERCENTAGE`` or ``LOWER_PRIORITY``) and an ``explanation``.
    ``suggested_fix`` is set only by explain_unmatched with ``suggest_fixes``."""

    expectation_id: str | None = None
    expectation_method: str | None = None
    expectation_path: str | None = None
    position: int | None = None
    priority: int | None = None
    reason: str | None = None
    explanation: str | None = None
    matches: bool | None = None
    matched_field_count: int | None = None
    total_field_count: int | None = None
    differing_field_count: int | None = None
    differences: dict[str, list[str]] = field(default_factory=dict)
    remediation: dict[str, str] = field(default_factory=dict)
    suggested_fix: SuggestedFix | None = None

    @classmethod
    def from_dict(cls, data: dict | None) -> ExplainedExpectation | None:
        if data is None:
            return None
        return cls(
            expectation_id=data.get("expectationId"),
            expectation_method=data.get("expectationMethod"),
            expectation_path=data.get("expectationPath"),
            position=data.get("position"),
            priority=data.get("priority"),
            reason=data.get("reason"),
            explanation=data.get("explanation"),
            matches=data.get("matches"),
            matched_field_count=data.get("matchedFieldCount"),
            total_field_count=data.get("totalFieldCount"),
            differing_field_count=data.get("differingFieldCount"),
            differences={k: list(v) for k, v in (data.get("differences") or {}).items()},
            remediation=dict(data.get("remediation") or {}),
            suggested_fix=SuggestedFix.from_dict(data.get("suggestedFix")),
        )


@dataclass
class ExplainedRequest:
    """The recorded request that explain re-checked."""

    timestamp: str | None = None
    method: str | None = None
    path: str | None = None
    body_truncated: bool = False
    body_truncated_note: str | None = None

    @classmethod
    def from_dict(cls, data: dict | None) -> ExplainedRequest | None:
        if data is None:
            return None
        return cls(
            timestamp=data.get("timestamp"),
            method=data.get("method"),
            path=data.get("path"),
            body_truncated=bool(data.get("bodyTruncated", False)),
            body_truncated_note=data.get("bodyTruncatedNote"),
        )


@dataclass
class Arrival:
    """How a request was handled when it arrived: ``outcome`` is ``MATCHED``,
    ``NO_MATCH``, ``FORWARDED_WITHOUT_EXPECTATION``,
    ``RESPONDED_WITHOUT_EXPECTATION`` or ``UNKNOWN``; the expectation fields
    are set for ``MATCHED`` only."""

    outcome: str | None = None
    expectation_id: str | None = None
    expectation_still_stored: bool | None = None
    forward_failure: ArrivalForwardFailure | None = None

    @classmethod
    def from_dict(cls, data: dict | None) -> Arrival | None:
        if data is None:
            return None
        return cls(
            outcome=data.get("outcome"),
            expectation_id=data.get("expectationId"),
            expectation_still_stored=data.get("expectationStillStored"),
            forward_failure=ArrivalForwardFailure.from_dict(data.get("forwardFailure")),
        )


@dataclass
class ArrivalForwardFailure:
    """Set only when the request was forwarded and the forward failed, so
    MockServer answered 502: the same facts the ``explain_forward_failure``
    MCP tool gives. ``reason`` is redacted as the event log is and capped at
    2048 characters (``reason_truncated``); when ``cause_named`` is false the
    reason is the raw error and ``hint`` says what to look at next."""

    status_code: int | None = None
    reason: str | None = None
    reason_truncated: bool = False
    cause_named: bool | None = None
    forward_loop_suspected: bool | None = None
    hint: str | None = None

    @classmethod
    def from_dict(cls, data: dict | None) -> ArrivalForwardFailure | None:
        if data is None:
            return None
        return cls(
            status_code=data.get("statusCode"),
            reason=data.get("reason"),
            reason_truncated=bool(data.get("reasonTruncated", False)),
            cause_named=data.get("causeNamed"),
            forward_loop_suspected=data.get("forwardLoopSuspected"),
            hint=data.get("hint"),
        )


@dataclass
class ExplanationNarration:
    """Set only when the server was asked to narrate: a plain-language summary
    the configured LLM backend wrote, or why none is available (``reason``).
    It is AI-generated and can be wrong; the other fields are the facts."""

    available: bool | None = None
    ai_generated: bool | None = None
    label: str | None = None
    provider: str | None = None
    model: str | None = None
    text: str | None = None
    reason: str | None = None

    @classmethod
    def from_dict(cls, data: dict | None) -> ExplanationNarration | None:
        if data is None:
            return None
        return cls(
            available=data.get("available"),
            ai_generated=data.get("aiGenerated"),
            label=data.get("label"),
            provider=data.get("provider"),
            model=data.get("model"),
            text=data.get("text"),
            reason=data.get("reason"),
        )


@dataclass
class RequestExplanation:
    """Which expectation would serve a recorded request now, why each one
    evaluated ahead of it was passed over, which lower-priority ones it
    shadows, and how the request was handled when it arrived. Recomputed when
    asked for, against the current expectations, scenario states and Times."""

    correlation_id: str | None = None
    timestamp: str | None = None
    recomputed: bool | None = None
    note: str | None = None
    request: ExplainedRequest | None = None
    at_arrival: Arrival | None = None
    same_outcome_as_at_arrival: bool | None = None
    expectations_changed_since: bool = False
    expectations_last_changed: str | None = None
    matched_expectation: ExplainedExpectation | None = None
    closest_expectation_id: str | None = None
    skipped_expectations: list[ExplainedExpectation] = field(default_factory=list)
    shadowed_expectations: list[ExplainedExpectation] = field(default_factory=list)
    total_expectations: int = 0
    evaluated_expectations: int = 0
    truncated: bool = False
    max_expectations_evaluated: int | None = None
    narration: ExplanationNarration | None = None

    @classmethod
    def from_dict(cls, data: dict | None) -> RequestExplanation | None:
        if data is None:
            return None
        return cls(
            correlation_id=data.get("correlationId"),
            timestamp=data.get("timestamp"),
            recomputed=data.get("recomputed"),
            note=data.get("note"),
            request=ExplainedRequest.from_dict(data.get("request")),
            at_arrival=Arrival.from_dict(data.get("atArrival")),
            same_outcome_as_at_arrival=data.get("sameOutcomeAsAtArrival"),
            expectations_changed_since=bool(data.get("expectationsChangedSince", False)),
            expectations_last_changed=data.get("expectationsLastChanged"),
            matched_expectation=ExplainedExpectation.from_dict(data.get("matchedExpectation")),
            closest_expectation_id=data.get("closestExpectationId"),
            skipped_expectations=_from_list(ExplainedExpectation, data.get("skippedExpectations")),
            shadowed_expectations=_from_list(ExplainedExpectation, data.get("shadowedExpectations")),
            total_expectations=data.get("totalExpectations", 0),
            evaluated_expectations=data.get("evaluatedExpectations", 0),
            truncated=bool(data.get("truncated", False)),
            max_expectations_evaluated=data.get("maxExpectationsEvaluated"),
            narration=ExplanationNarration.from_dict(data.get("narration")),
        )


@dataclass
class ClosestMatch:
    """The expectation debug_mismatch found with the fewest differing fields."""

    expectation_id: str | None = None
    matched_fields: int | None = None
    total_fields: int | None = None

    @classmethod
    def from_dict(cls, data: dict | None) -> ClosestMatch | None:
        if data is None:
            return None
        return cls(
            expectation_id=data.get("expectationId"),
            matched_fields=data.get("matchedFields"),
            total_fields=data.get("totalFields"),
        )


@dataclass
class DebugMismatchResult:
    """The result of debug_mismatch: each active expectation compared with the
    request, and the closest match (``None`` when every expectation matches)."""

    correlation_id: str | None = None
    timestamp: str | None = None
    total_expectations: int = 0
    evaluated_expectations: int = 0
    truncated: bool = False
    max_expectations_evaluated: int | None = None
    closest_match: ClosestMatch | None = None
    results: list[ExplainedExpectation] = field(default_factory=list)

    @classmethod
    def from_dict(cls, data: dict | None) -> DebugMismatchResult | None:
        if data is None:
            return None
        return cls(
            correlation_id=data.get("correlationId"),
            timestamp=data.get("timestamp"),
            total_expectations=data.get("totalExpectations", 0),
            evaluated_expectations=data.get("evaluatedExpectations", 0),
            truncated=bool(data.get("truncated", False)),
            max_expectations_evaluated=data.get("maxExpectationsEvaluated"),
            closest_match=ClosestMatch.from_dict(data.get("closestMatch")),
            results=_from_list(ExplainedExpectation, data.get("results")),
        )


@dataclass
class UnmatchedRequest:
    """One recent unmatched request and its closest expectations, closest first."""

    timestamp: str | None = None
    method: str | None = None
    path: str | None = None
    closest_expectations: list[ExplainedExpectation] = field(default_factory=list)
    total_expectations_evaluated: int = 0

    @classmethod
    def from_dict(cls, data: dict | None) -> UnmatchedRequest | None:
        if data is None:
            return None
        return cls(
            timestamp=data.get("timestamp"),
            method=data.get("method"),
            path=data.get("path"),
            closest_expectations=_from_list(ExplainedExpectation, data.get("closestExpectations")),
            total_expectations_evaluated=data.get("totalExpectationsEvaluated", 0),
        )


@dataclass
class ExplainUnmatchedResult:
    """The result of explain_unmatched."""

    correlation_id: str | None = None
    timestamp: str | None = None
    unmatched_request_count: int = 0
    truncated: bool = False
    unmatched_requests: list[UnmatchedRequest] = field(default_factory=list)
    narration: ExplanationNarration | None = None

    @classmethod
    def from_dict(cls, data: dict | None) -> ExplainUnmatchedResult | None:
        if data is None:
            return None
        return cls(
            correlation_id=data.get("correlationId"),
            timestamp=data.get("timestamp"),
            unmatched_request_count=data.get("unmatchedRequestCount", 0),
            truncated=bool(data.get("truncated", False)),
            unmatched_requests=_from_list(UnmatchedRequest, data.get("unmatchedRequests")),
            narration=ExplanationNarration.from_dict(data.get("narration")),
        )


RequestDefinition = HttpRequest
