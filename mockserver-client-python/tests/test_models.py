from __future__ import annotations

import json

import pytest

from mockserver.models import (
    AfterAction,
    AllOfBody,
    BinaryResponse,
    BinaryUpstream,
    Body,
    CaptureRule,
    CaptureSource,
    DnsRecordClass,
    DnsRecordType,
    DnsRequestDefinition,
    GraphQLBody,
    GraphQLSubscriptionFilter,
    HttpForwardValidateAction,
    HttpForwardWithFallback,
    HttpSseResponse,
    HttpWebSocketResponse,
    JsonSchemaBody,
    MultipartBody,
    ParameterBody,
    Protocol,
    RateLimit,
    RateLimitAlgorithm,
    RecoverAfter,
    ValidationMode,
    WasmBody,
    XPathBody,
    XmlBody,
    XmlSchemaBody,
    ConnectionOptions,
    CrossProtocolScenario,
    CrossProtocolTrigger,
    Delay,
    DelayDistribution,
    DnsRecord,
    DnsResponse,
    Expectation,
    ExpectationId,
    GrpcBidiResponse,
    GrpcBidiRule,
    GrpcStreamMessage,
    GrpcStreamResponse,
    HttpChaosProfile,
    HttpClassCallback,
    HttpError,
    HttpForward,
    HttpObjectCallback,
    HttpOverrideForwardedRequest,
    HttpRequest,
    HttpRequestAndHttpResponse,
    HttpResponse,
    HttpTemplate,
    JsonPathBody,
    Jwt,
    KeyToMultiValue,
    RegexBody,
    OpenAPIDefinition,
    OpenAPIExpectation,
    Ports,
    RequestDefinition,
    ResponseMode,
    SocketAddress,
    TimeToLive,
    Times,
    Verification,
    VerificationSequence,
    VerificationTimes,
    WebSocketFrameMatcher,
    WebSocketMessage,
    _deserialize_body,
    _from_camel,
    _serialize_body,
    _serialize_value,
    _strip_none,
    _to_camel,
)


class TestToCamel:
    def test_mapped_field(self):
        assert _to_camel("status_code") == "statusCode"

    def test_mapped_field_not_body(self):
        assert _to_camel("not_body") == "not"

    def test_mapped_field_query_string_parameters(self):
        assert _to_camel("query_string_parameters") == "queryStringParameters"

    def test_unmapped_single_word(self):
        assert _to_camel("method") == "method"

    def test_unmapped_two_words(self):
        assert _to_camel("my_field") == "myField"

    def test_unmapped_three_words(self):
        assert _to_camel("my_long_field") == "myLongField"

    def test_mapped_time_to_live(self):
        assert _to_camel("time_to_live") == "timeToLive"

    def test_mapped_keep_alive(self):
        assert _to_camel("keep_alive") == "keepAlive"


class TestFromCamel:
    def test_mapped_field(self):
        assert _from_camel("statusCode") == "status_code"

    def test_mapped_field_not(self):
        assert _from_camel("not") == "not_body"

    def test_unmapped_single_word(self):
        assert _from_camel("method") == "method"

    def test_unmapped_camel(self):
        assert _from_camel("myField") == "my_field"


class TestStripNone:
    def test_removes_none_values(self):
        assert _strip_none({"a": 1, "b": None, "c": 3}) == {"a": 1, "c": 3}

    def test_keeps_false(self):
        assert _strip_none({"a": False}) == {"a": False}

    def test_keeps_zero(self):
        assert _strip_none({"a": 0}) == {"a": 0}

    def test_keeps_empty_string(self):
        assert _strip_none({"a": ""}) == {"a": ""}

    def test_keeps_empty_list(self):
        assert _strip_none({"a": []}) == {"a": []}

    def test_all_none(self):
        assert _strip_none({"a": None, "b": None}) == {}

    def test_empty_dict(self):
        assert _strip_none({}) == {}


class TestSerializeValue:
    def test_with_to_dict(self):
        d = Delay(time_unit="SECONDS", value=5)
        assert _serialize_value(d) == {"timeUnit": "SECONDS", "value": 5}

    def test_with_list(self):
        items = [Delay(time_unit="SECONDS", value=1), Delay(time_unit="SECONDS", value=2)]
        result = _serialize_value(items)
        assert result == [
            {"timeUnit": "SECONDS", "value": 1},
            {"timeUnit": "SECONDS", "value": 2},
        ]

    def test_with_primitive(self):
        assert _serialize_value("hello") == "hello"
        assert _serialize_value(42) == 42


class TestSerializeBody:
    def test_none(self):
        assert _serialize_body(None) is None

    def test_string(self):
        assert _serialize_body("hello") == "hello"

    def test_dict(self):
        assert _serialize_body({"key": "val"}) == {"key": "val"}

    def test_body_object(self):
        b = Body(type="STRING", string="hello")
        assert _serialize_body(b) == {"type": "STRING", "string": "hello"}


class TestDelayDistribution:
    def test_defaults(self):
        d = DelayDistribution()
        assert d.type is None
        assert d.min is None
        assert d.max is None

    def test_uniform(self):
        d = DelayDistribution(type="UNIFORM", min=100, max=500)
        assert d.to_dict() == {"type": "UNIFORM", "min": 100, "max": 500}

    def test_log_normal(self):
        d = DelayDistribution(type="LOG_NORMAL", median=200, p99=800)
        assert d.to_dict() == {"type": "LOG_NORMAL", "median": 200, "p99": 800}

    def test_gaussian(self):
        d = DelayDistribution(type="GAUSSIAN", mean=200, std_dev=50)
        assert d.to_dict() == {"type": "GAUSSIAN", "mean": 200, "stdDev": 50}

    def test_from_dict(self):
        d = DelayDistribution.from_dict({"type": "UNIFORM", "min": 10, "max": 20})
        assert d.type == "UNIFORM"
        assert d.min == 10
        assert d.max == 20

    def test_from_dict_none(self):
        assert DelayDistribution.from_dict(None) is None

    def test_round_trip(self):
        original = DelayDistribution(type="GAUSSIAN", mean=100, std_dev=25)
        restored = DelayDistribution.from_dict(original.to_dict())
        assert restored.type == original.type
        assert restored.mean == original.mean
        assert restored.std_dev == original.std_dev


class TestDelay:
    def test_defaults(self):
        d = Delay()
        assert d.time_unit == "MILLISECONDS"
        assert d.value == 0
        assert d.distribution is None

    def test_construction(self):
        d = Delay(time_unit="SECONDS", value=5)
        assert d.time_unit == "SECONDS"
        assert d.value == 5

    def test_to_dict(self):
        d = Delay(time_unit="SECONDS", value=10)
        assert d.to_dict() == {"timeUnit": "SECONDS", "value": 10}

    def test_from_dict(self):
        d = Delay.from_dict({"timeUnit": "SECONDS", "value": 3})
        assert d.time_unit == "SECONDS"
        assert d.value == 3

    def test_from_dict_none(self):
        assert Delay.from_dict(None) is None

    def test_from_dict_defaults(self):
        d = Delay.from_dict({})
        assert d.time_unit == "MILLISECONDS"
        assert d.value == 0

    def test_round_trip(self):
        original = Delay(time_unit="MINUTES", value=2)
        restored = Delay.from_dict(original.to_dict())
        assert restored.time_unit == original.time_unit
        assert restored.value == original.value

    def test_with_distribution(self):
        dist = DelayDistribution(type="UNIFORM", min=100, max=500)
        d = Delay(time_unit="MILLISECONDS", distribution=dist)
        result = d.to_dict()
        assert result == {
            "timeUnit": "MILLISECONDS",
            "value": 0,
            "distribution": {"type": "UNIFORM", "min": 100, "max": 500},
        }

    def test_from_dict_with_distribution(self):
        d = Delay.from_dict({
            "timeUnit": "MILLISECONDS",
            "value": 0,
            "distribution": {"type": "LOG_NORMAL", "median": 200, "p99": 800},
        })
        assert d.distribution is not None
        assert d.distribution.type == "LOG_NORMAL"
        assert d.distribution.median == 200
        assert d.distribution.p99 == 800

    def test_round_trip_with_distribution(self):
        dist = DelayDistribution(type="GAUSSIAN", mean=200, std_dev=50)
        original = Delay(time_unit="MILLISECONDS", distribution=dist)
        restored = Delay.from_dict(original.to_dict())
        assert restored.distribution is not None
        assert restored.distribution.type == original.distribution.type
        assert restored.distribution.mean == original.distribution.mean
        assert restored.distribution.std_dev == original.distribution.std_dev


class TestTimes:
    def test_defaults(self):
        t = Times()
        assert t.remaining_times is None
        assert t.unlimited is None

    def test_construction(self):
        t = Times(remaining_times=5, unlimited=False)
        assert t.remaining_times == 5
        assert t.unlimited is False

    def test_unlimited_factory(self):
        t = Times.unlimited()
        assert t.unlimited is True
        assert t.remaining_times is None

    def test_exactly_factory(self):
        t = Times.exactly(3)
        assert t.remaining_times == 3
        assert t.unlimited is False

    def test_to_dict(self):
        t = Times(remaining_times=2, unlimited=False)
        assert t.to_dict() == {"remainingTimes": 2, "unlimited": False}

    def test_to_dict_strips_none(self):
        t = Times(unlimited=True)
        assert t.to_dict() == {"unlimited": True}

    def test_from_dict(self):
        t = Times.from_dict({"remainingTimes": 7, "unlimited": False})
        assert t.remaining_times == 7
        assert t.unlimited is False

    def test_from_dict_none(self):
        assert Times.from_dict(None) is None

    def test_round_trip(self):
        original = Times.exactly(10)
        restored = Times.from_dict(original.to_dict())
        assert restored.remaining_times == 10
        assert restored.unlimited is False


class TestTimeToLive:
    def test_defaults(self):
        ttl = TimeToLive()
        assert ttl.time_unit is None
        assert ttl.time_to_live is None
        assert ttl.unlimited is None

    def test_unlimited_factory(self):
        ttl = TimeToLive.unlimited()
        assert ttl.unlimited is True
        assert ttl.time_unit is None
        assert ttl.time_to_live is None

    def test_exactly_factory(self):
        ttl = TimeToLive.exactly(60, "SECONDS")
        assert ttl.time_to_live == 60
        assert ttl.time_unit == "SECONDS"
        assert ttl.unlimited is False

    def test_to_dict(self):
        ttl = TimeToLive(time_unit="SECONDS", time_to_live=30, unlimited=False)
        assert ttl.to_dict() == {"timeUnit": "SECONDS", "timeToLive": 30, "unlimited": False}

    def test_to_dict_strips_none(self):
        ttl = TimeToLive(unlimited=True)
        assert ttl.to_dict() == {"unlimited": True}

    def test_from_dict(self):
        ttl = TimeToLive.from_dict({"timeUnit": "MINUTES", "timeToLive": 5, "unlimited": False})
        assert ttl.time_unit == "MINUTES"
        assert ttl.time_to_live == 5
        assert ttl.unlimited is False

    def test_from_dict_none(self):
        assert TimeToLive.from_dict(None) is None

    def test_round_trip(self):
        original = TimeToLive.exactly(120, "SECONDS")
        restored = TimeToLive.from_dict(original.to_dict())
        assert restored.time_to_live == 120
        assert restored.time_unit == "SECONDS"
        assert restored.unlimited is False


class TestKeyToMultiValue:
    def test_defaults(self):
        kv = KeyToMultiValue()
        assert kv.name == ""
        assert kv.values == []

    def test_construction(self):
        kv = KeyToMultiValue(name="Content-Type", values=["application/json"])
        assert kv.name == "Content-Type"
        assert kv.values == ["application/json"]

    def test_to_dict(self):
        kv = KeyToMultiValue(name="Accept", values=["text/html", "text/plain"])
        assert kv.to_dict() == {"name": "Accept", "values": ["text/html", "text/plain"]}

    def test_to_dict_always_includes_name_and_values(self):
        kv = KeyToMultiValue()
        assert kv.to_dict() == {"name": "", "values": []}

    def test_from_dict(self):
        kv = KeyToMultiValue.from_dict({"name": "X-Custom", "values": ["v1"]})
        assert kv.name == "X-Custom"
        assert kv.values == ["v1"]

    def test_from_dict_none(self):
        assert KeyToMultiValue.from_dict(None) is None

    def test_from_dict_defaults(self):
        kv = KeyToMultiValue.from_dict({})
        assert kv.name == ""
        assert kv.values == []

    def test_round_trip(self):
        original = KeyToMultiValue(name="key", values=["a", "b"])
        restored = KeyToMultiValue.from_dict(original.to_dict())
        assert restored.name == original.name
        assert restored.values == original.values


class TestBody:
    def test_defaults(self):
        b = Body()
        assert b.type is None
        assert b.string is None
        assert b.json is None
        assert b.not_body is None

    def test_string_factory(self):
        b = Body.string("hello")
        assert b.type == "STRING"
        assert b.string == "hello"

    def test_json_factory_with_dict(self):
        b = Body.json({"key": "value"})
        assert b.type == "JSON"
        assert b.json == {"key": "value"}

    def test_json_factory_with_list(self):
        b = Body.json([1, 2, 3])
        assert b.type == "JSON"
        assert b.json == [1, 2, 3]

    def test_regex_factory(self):
        # Body.regex must emit the canonical {"type": "REGEX", "regex": ...} wire
        # form so the server parses it as a regex matcher, not a literal STRING body.
        b = Body.regex("^/api/.*")
        assert isinstance(b, RegexBody)
        assert b.regex == "^/api/.*"
        assert b.to_dict() == {"type": "REGEX", "regex": "^/api/.*"}
        # Guard against regressing to the old broken shape.
        assert "string" not in b.to_dict()

    def test_exact_factory(self):
        b = Body.exact("exact match")
        assert b.type == "STRING"
        assert b.string == "exact match"

    def test_xml_factory(self):
        b = Body.xml("<root><child/></root>")
        assert b.type == "XML"
        assert b.string == "<root><child/></root>"

    def test_to_dict_string_body(self):
        b = Body(type="STRING", string="hello")
        assert b.to_dict() == {"type": "STRING", "string": "hello"}

    def test_to_dict_json_body(self):
        b = Body(type="JSON", json={"a": 1})
        assert b.to_dict() == {"type": "JSON", "json": {"a": 1}}

    def test_to_dict_not_body_key_mapping(self):
        b = Body(type="STRING", string="test", not_body=True)
        result = b.to_dict()
        assert result["not"] is True
        assert "not_body" not in result

    def test_to_dict_with_content_type(self):
        b = Body(type="JSON", json={}, content_type="application/json")
        result = b.to_dict()
        assert result["contentType"] == "application/json"

    def test_to_dict_with_base64_bytes(self):
        b = Body(type="BINARY", base64_bytes="AQID")
        result = b.to_dict()
        assert result["base64Bytes"] == "AQID"

    def test_to_dict_with_charset(self):
        b = Body(type="STRING", string="hi", charset="UTF-8")
        result = b.to_dict()
        assert result["charset"] == "UTF-8"

    def test_to_dict_excludes_none_fields(self):
        b = Body(type="STRING", string="hello")
        result = b.to_dict()
        assert "json" not in result
        assert "base64Bytes" not in result
        assert "not" not in result
        assert "contentType" not in result
        assert "charset" not in result

    def test_from_dict(self):
        b = Body.from_dict({"type": "STRING", "string": "hello"})
        assert b.type == "STRING"
        assert b.string == "hello"

    def test_from_dict_with_not(self):
        b = Body.from_dict({"type": "STRING", "string": "x", "not": True})
        assert b.not_body is True

    def test_from_dict_with_base64_bytes(self):
        b = Body.from_dict({"type": "BINARY", "base64Bytes": "AQID"})
        assert b.base64_bytes == "AQID"

    def test_from_dict_none(self):
        assert Body.from_dict(None) is None

    def test_round_trip_string(self):
        original = Body.string("test-string")
        restored = Body.from_dict(original.to_dict())
        assert restored.type == "STRING"
        assert restored.string == "test-string"

    def test_round_trip_json(self):
        original = Body.json({"nested": {"data": [1, 2]}})
        restored = Body.from_dict(original.to_dict())
        assert restored.type == "JSON"
        assert restored.json == {"nested": {"data": [1, 2]}}

    def test_round_trip_not_body(self):
        original = Body(type="REGEX", string=".*", not_body=True, content_type="text/plain")
        restored = Body.from_dict(original.to_dict())
        assert restored.not_body is True
        assert restored.content_type == "text/plain"
        assert restored.type == "REGEX"
        assert restored.string == ".*"


class TestSocketAddress:
    def test_defaults(self):
        sa = SocketAddress()
        assert sa.host is None
        assert sa.port is None
        assert sa.scheme is None

    def test_construction(self):
        sa = SocketAddress(host="localhost", port=443, scheme="HTTPS")
        assert sa.host == "localhost"
        assert sa.port == 443
        assert sa.scheme == "HTTPS"

    def test_to_dict(self):
        sa = SocketAddress(host="example.com", port=80, scheme="HTTP")
        assert sa.to_dict() == {"host": "example.com", "port": 80, "scheme": "HTTP"}

    def test_to_dict_strips_none(self):
        sa = SocketAddress(host="example.com")
        assert sa.to_dict() == {"host": "example.com"}

    def test_from_dict(self):
        sa = SocketAddress.from_dict({"host": "h", "port": 9090, "scheme": "HTTPS"})
        assert sa.host == "h"
        assert sa.port == 9090
        assert sa.scheme == "HTTPS"

    def test_from_dict_none(self):
        assert SocketAddress.from_dict(None) is None

    def test_round_trip(self):
        original = SocketAddress(host="localhost", port=8080, scheme="HTTP")
        restored = SocketAddress.from_dict(original.to_dict())
        assert restored.host == original.host
        assert restored.port == original.port
        assert restored.scheme == original.scheme


class TestHttpRequest:
    def test_request_factory_no_path(self):
        r = HttpRequest.request()
        assert r.path is None
        assert r.method is None

    def test_request_factory_with_path(self):
        r = HttpRequest.request("/api/test")
        assert r.path == "/api/test"

    def test_with_method(self):
        r = HttpRequest.request().with_method("POST")
        assert r.method == "POST"

    def test_with_path(self):
        r = HttpRequest.request().with_path("/new")
        assert r.path == "/new"

    def test_with_header(self):
        r = HttpRequest.request().with_header("Content-Type", "application/json")
        assert len(r.headers) == 1
        assert r.headers[0].name == "Content-Type"
        assert r.headers[0].values == ["application/json"]

    def test_with_header_multiple_values(self):
        r = HttpRequest.request().with_header("Accept", "text/html", "text/plain")
        assert r.headers[0].values == ["text/html", "text/plain"]

    def test_with_header_accumulates(self):
        r = HttpRequest.request().with_header("H1", "v1").with_header("H2", "v2")
        assert len(r.headers) == 2

    def test_with_query_param(self):
        r = HttpRequest.request().with_query_param("q", "search")
        assert len(r.query_string_parameters) == 1
        assert r.query_string_parameters[0].name == "q"
        assert r.query_string_parameters[0].values == ["search"]

    def test_with_query_param_multiple_values(self):
        r = HttpRequest.request().with_query_param("tags", "a", "b", "c")
        assert r.query_string_parameters[0].values == ["a", "b", "c"]

    def test_with_cookie(self):
        r = HttpRequest.request().with_cookie("session", "abc123")
        assert len(r.cookies) == 1
        assert r.cookies[0].name == "session"
        assert r.cookies[0].values == ["abc123"]

    def test_with_body_string(self):
        r = HttpRequest.request().with_body("plain text")
        assert r.body == "plain text"

    def test_with_body_dict(self):
        r = HttpRequest.request().with_body({"key": "value"})
        assert r.body == {"key": "value"}

    def test_with_body_object(self):
        b = Body.json({"test": True})
        r = HttpRequest.request().with_body(b)
        assert r.body is b

    def test_with_secure(self):
        r = HttpRequest.request().with_secure(True)
        assert r.secure is True

    def test_with_secure_false(self):
        r = HttpRequest.request().with_secure(False)
        assert r.secure is False

    def test_with_keep_alive(self):
        r = HttpRequest.request().with_keep_alive(True)
        assert r.keep_alive is True

    def test_with_respond_before_body(self):
        r = HttpRequest.request().with_respond_before_body(True)
        assert r.respond_before_body is True

    def test_client_ip_round_trips_and_is_omitted_when_unset(self):
        r = HttpRequest.request("/x").with_client_ip("!10.0.0.0/8")
        assert r.to_dict() == {"path": "/x", "clientIp": "!10.0.0.0/8"}
        for data in ({"path": "/x", "clientIp": "10.0.0.0/8"}, {"path": "/x", "clientIp": "!2001:db8::/32"}):
            assert HttpRequest.from_dict(data).to_dict() == data
        assert "clientIp" not in HttpRequest.request("/x").to_dict()

    def test_builder_chaining_returns_self(self):
        r = HttpRequest.request()
        assert r.with_method("GET") is r
        assert r.with_path("/p") is r
        assert r.with_header("H", "v") is r
        assert r.with_query_param("q", "v") is r
        assert r.with_cookie("c", "v") is r
        assert r.with_body("b") is r
        assert r.with_secure(True) is r
        assert r.with_keep_alive(True) is r
        assert r.with_respond_before_body(True) is r

    def test_full_chaining(self):
        r = (
            HttpRequest.request("/api")
            .with_method("POST")
            .with_header("Content-Type", "application/json")
            .with_query_param("page", "1")
            .with_cookie("token", "xyz")
            .with_body(Body.json({"data": 1}))
            .with_secure(True)
            .with_keep_alive(False)
        )
        assert r.path == "/api"
        assert r.method == "POST"
        assert r.headers[0].name == "Content-Type"
        assert r.query_string_parameters[0].name == "page"
        assert r.cookies[0].name == "token"
        assert r.body.type == "JSON"
        assert r.secure is True
        assert r.keep_alive is False

    def test_to_dict_minimal(self):
        r = HttpRequest.request("/test")
        result = r.to_dict()
        assert result == {"path": "/test"}

    def test_to_dict_string_body(self):
        r = HttpRequest(path="/test", body="hello")
        result = r.to_dict()
        assert result["body"] == "hello"

    def test_to_dict_dict_body(self):
        r = HttpRequest(path="/test", body={"key": "val"})
        result = r.to_dict()
        assert result["body"] == {"key": "val"}

    def test_to_dict_body_object(self):
        r = HttpRequest(path="/test", body=Body.string("text"))
        result = r.to_dict()
        assert result["body"] == {"type": "STRING", "string": "text"}

    def test_to_dict_with_headers(self):
        r = HttpRequest.request().with_header("Accept", "text/html")
        result = r.to_dict()
        assert result["headers"] == [{"name": "Accept", "values": ["text/html"]}]

    def test_to_dict_with_query_params(self):
        r = HttpRequest.request().with_query_param("q", "val")
        result = r.to_dict()
        assert result["queryStringParameters"] == [{"name": "q", "values": ["val"]}]

    def test_to_dict_with_cookies(self):
        r = HttpRequest.request().with_cookie("sid", "abc")
        result = r.to_dict()
        # cookies serialize as a {name: value} object map (not the header/query array form)
        assert result["cookies"] == {"sid": "abc"}

    def test_to_dict_camel_case_keys(self):
        r = HttpRequest(
            keep_alive=True,
            secure=False,
            respond_before_body=True,
            query_string_parameters=[KeyToMultiValue(name="q", values=["v"])],
        )
        result = r.to_dict()
        assert "keepAlive" in result
        assert "respondBeforeBody" in result
        assert "queryStringParameters" in result
        assert "keep_alive" not in result
        assert "respond_before_body" not in result
        assert "query_string_parameters" not in result

    def test_to_dict_with_socket_address(self):
        r = HttpRequest(
            path="/p",
            socket_address=SocketAddress(host="host", port=443, scheme="HTTPS"),
        )
        result = r.to_dict()
        assert result["socketAddress"] == {"host": "host", "port": 443, "scheme": "HTTPS"}

    def test_to_dict_excludes_none(self):
        r = HttpRequest(path="/only-path")
        result = r.to_dict()
        assert "method" not in result
        assert "headers" not in result
        assert "cookies" not in result
        assert "body" not in result
        assert "secure" not in result
        assert "keepAlive" not in result

    def test_from_dict(self):
        r = HttpRequest.from_dict({
            "method": "GET",
            "path": "/test",
            "keepAlive": True,
            "respondBeforeBody": True,
        })
        assert r.method == "GET"
        assert r.path == "/test"
        assert r.keep_alive is True
        assert r.respond_before_body is True

    def test_from_dict_with_headers(self):
        r = HttpRequest.from_dict({
            "headers": [{"name": "Accept", "values": ["text/html"]}],
        })
        assert len(r.headers) == 1
        assert r.headers[0].name == "Accept"

    def test_from_dict_with_body_string(self):
        r = HttpRequest.from_dict({"body": "plain text"})
        assert r.body == "plain text"

    def test_from_dict_with_body_typed(self):
        r = HttpRequest.from_dict({"body": {"type": "JSON", "json": {"a": 1}}})
        assert isinstance(r.body, Body)
        assert r.body.type == "JSON"
        assert r.body.json == {"a": 1}

    def test_from_dict_with_body_dict_no_type(self):
        r = HttpRequest.from_dict({"body": {"key": "value"}})
        assert r.body == {"key": "value"}
        assert not isinstance(r.body, Body)

    def test_from_dict_with_body_dict_unknown_type(self):
        r = HttpRequest.from_dict({"body": {"type": "user", "name": "Alice"}})
        assert r.body == {"type": "user", "name": "Alice"}
        assert not isinstance(r.body, Body)

    def test_from_dict_none(self):
        assert HttpRequest.from_dict(None) is None

    def test_from_dict_missing_optional_fields(self):
        r = HttpRequest.from_dict({})
        assert r.method is None
        assert r.path is None
        assert r.headers is None
        assert r.cookies is None
        assert r.body is None
        assert r.secure is None
        assert r.keep_alive is None

    def test_round_trip(self):
        original = (
            HttpRequest.request("/api/users")
            .with_method("POST")
            .with_header("Content-Type", "application/json")
            .with_query_param("page", "1")
            .with_cookie("auth", "tok")
            .with_body(Body.json({"name": "test"}))
            .with_secure(True)
            .with_keep_alive(False)
        )
        restored = HttpRequest.from_dict(original.to_dict())
        assert restored.path == "/api/users"
        assert restored.method == "POST"
        assert restored.headers[0].name == "Content-Type"
        assert restored.query_string_parameters[0].name == "page"
        assert restored.cookies[0].name == "auth"
        assert isinstance(restored.body, Body)
        assert restored.body.json == {"name": "test"}
        assert restored.secure is True
        assert restored.keep_alive is False


class TestConnectionOptions:
    def test_defaults(self):
        co = ConnectionOptions()
        assert co.close_socket is None
        assert co.close_socket_delay is None

    def test_construction(self):
        co = ConnectionOptions(
            close_socket=True,
            suppress_content_length_header=True,
            content_length_header_override=100,
            suppress_connection_header=False,
            keep_alive_override=True,
        )
        assert co.close_socket is True
        assert co.content_length_header_override == 100

    def test_to_dict(self):
        co = ConnectionOptions(close_socket=True, keep_alive_override=False)
        result = co.to_dict()
        assert result == {"closeSocket": True, "keepAliveOverride": False}

    def test_to_dict_with_delay(self):
        co = ConnectionOptions(
            close_socket=True,
            close_socket_delay=Delay(time_unit="SECONDS", value=1),
        )
        result = co.to_dict()
        assert result["closeSocketDelay"] == {"timeUnit": "SECONDS", "value": 1}

    def test_from_dict(self):
        co = ConnectionOptions.from_dict({
            "closeSocket": True,
            "suppressContentLengthHeader": False,
            "contentLengthHeaderOverride": 50,
            "suppressConnectionHeader": True,
            "keepAliveOverride": False,
        })
        assert co.close_socket is True
        assert co.suppress_content_length_header is False
        assert co.content_length_header_override == 50
        assert co.suppress_connection_header is True
        assert co.keep_alive_override is False

    def test_from_dict_with_delay(self):
        co = ConnectionOptions.from_dict({
            "closeSocket": True,
            "closeSocketDelay": {"timeUnit": "SECONDS", "value": 2},
        })
        assert co.close_socket_delay.time_unit == "SECONDS"
        assert co.close_socket_delay.value == 2

    def test_from_dict_none(self):
        assert ConnectionOptions.from_dict(None) is None

    def test_round_trip(self):
        original = ConnectionOptions(
            close_socket=True,
            close_socket_delay=Delay(time_unit="SECONDS", value=3),
            suppress_content_length_header=True,
            content_length_header_override=200,
            suppress_connection_header=False,
            keep_alive_override=True,
        )
        restored = ConnectionOptions.from_dict(original.to_dict())
        assert restored.close_socket is True
        assert restored.close_socket_delay.time_unit == "SECONDS"
        assert restored.close_socket_delay.value == 3
        assert restored.suppress_content_length_header is True
        assert restored.content_length_header_override == 200
        assert restored.suppress_connection_header is False
        assert restored.keep_alive_override is True


class TestHttpResponse:
    def test_response_factory_no_args(self):
        r = HttpResponse.response()
        assert r.status_code is None
        assert r.body is None

    def test_response_factory_with_body(self):
        r = HttpResponse.response("hello")
        assert r.body == "hello"
        assert r.status_code == 200
        assert r.reason_phrase == "OK"

    def test_response_factory_with_body_and_status(self):
        r = HttpResponse.response("created", 201)
        assert r.body == "created"
        assert r.status_code == 201
        assert r.reason_phrase is None

    def test_response_factory_with_status_only(self):
        r = HttpResponse.response(status_code=204)
        assert r.status_code == 204
        assert r.body is None

    def test_not_found_response(self):
        r = HttpResponse.not_found_response()
        assert r.status_code == 404
        assert r.reason_phrase == "Not Found"

    def test_with_status_code(self):
        r = HttpResponse().with_status_code(201)
        assert r.status_code == 201

    def test_with_header(self):
        r = HttpResponse().with_header("Content-Type", "application/json")
        assert len(r.headers) == 1
        assert r.headers[0].name == "Content-Type"
        assert r.headers[0].values == ["application/json"]

    def test_with_header_accumulates(self):
        r = HttpResponse().with_header("H1", "v1").with_header("H2", "v2")
        assert len(r.headers) == 2

    def test_with_cookie(self):
        r = HttpResponse().with_cookie("session", "abc")
        assert r.cookies[0].name == "session"
        assert r.cookies[0].values == ["abc"]

    def test_with_body_string(self):
        r = HttpResponse().with_body("text")
        assert r.body == "text"

    def test_with_body_object(self):
        b = Body.json({"result": True})
        r = HttpResponse().with_body(b)
        assert r.body is b

    def test_with_delay(self):
        d = Delay(time_unit="SECONDS", value=2)
        r = HttpResponse().with_delay(d)
        assert r.delay is d

    def test_with_reason_phrase(self):
        r = HttpResponse().with_reason_phrase("Custom Reason")
        assert r.reason_phrase == "Custom Reason"

    def test_builder_chaining_returns_self(self):
        r = HttpResponse()
        assert r.with_status_code(200) is r
        assert r.with_header("H", "v") is r
        assert r.with_cookie("c", "v") is r
        assert r.with_body("b") is r
        assert r.with_delay(Delay()) is r
        assert r.with_reason_phrase("OK") is r

    def test_full_chaining(self):
        r = (
            HttpResponse.response()
            .with_status_code(200)
            .with_reason_phrase("OK")
            .with_header("Content-Type", "text/plain")
            .with_cookie("sid", "123")
            .with_body("response body")
            .with_delay(Delay(time_unit="SECONDS", value=1))
        )
        assert r.status_code == 200
        assert r.reason_phrase == "OK"
        assert r.headers[0].name == "Content-Type"
        assert r.cookies[0].name == "sid"
        assert r.body == "response body"
        assert r.delay.value == 1

    def test_to_dict(self):
        r = HttpResponse(status_code=200, reason_phrase="OK", body="hello")
        result = r.to_dict()
        assert result == {"statusCode": 200, "reasonPhrase": "OK", "body": "hello"}

    def test_to_dict_with_body_object(self):
        r = HttpResponse(body=Body.string("text"))
        result = r.to_dict()
        assert result["body"] == {"type": "STRING", "string": "text"}

    def test_to_dict_with_delay(self):
        r = HttpResponse(status_code=200, delay=Delay(time_unit="SECONDS", value=5))
        result = r.to_dict()
        assert result["delay"] == {"timeUnit": "SECONDS", "value": 5}

    def test_to_dict_with_connection_options(self):
        r = HttpResponse(
            status_code=200,
            connection_options=ConnectionOptions(close_socket=True),
        )
        result = r.to_dict()
        assert result["connectionOptions"] == {"closeSocket": True}

    def test_to_dict_excludes_none(self):
        r = HttpResponse(status_code=200)
        result = r.to_dict()
        assert "headers" not in result
        assert "cookies" not in result
        assert "body" not in result
        assert "delay" not in result

    def test_to_dict_camel_case_keys(self):
        r = HttpResponse(status_code=200, reason_phrase="OK")
        result = r.to_dict()
        assert "statusCode" in result
        assert "reasonPhrase" in result
        assert "status_code" not in result
        assert "reason_phrase" not in result

    def test_from_dict(self):
        r = HttpResponse.from_dict({"statusCode": 200, "reasonPhrase": "OK", "body": "hello"})
        assert r.status_code == 200
        assert r.reason_phrase == "OK"
        assert r.body == "hello"

    def test_from_dict_with_typed_body(self):
        r = HttpResponse.from_dict({"body": {"type": "JSON", "json": {"a": 1}}})
        assert isinstance(r.body, Body)
        assert r.body.type == "JSON"

    def test_from_dict_with_delay(self):
        r = HttpResponse.from_dict({
            "statusCode": 200,
            "delay": {"timeUnit": "SECONDS", "value": 3},
        })
        assert r.delay.time_unit == "SECONDS"
        assert r.delay.value == 3

    def test_from_dict_with_connection_options(self):
        r = HttpResponse.from_dict({
            "connectionOptions": {"closeSocket": True},
        })
        assert r.connection_options.close_socket is True

    def test_from_dict_none(self):
        assert HttpResponse.from_dict(None) is None

    def test_from_dict_missing_optional_fields(self):
        r = HttpResponse.from_dict({})
        assert r.status_code is None
        assert r.reason_phrase is None
        assert r.headers is None
        assert r.body is None
        assert r.delay is None

    def test_round_trip(self):
        original = (
            HttpResponse.response()
            .with_status_code(201)
            .with_reason_phrase("Created")
            .with_header("Location", "/resource/1")
            .with_cookie("session", "abc")
            .with_body(Body.json({"id": 1}))
            .with_delay(Delay(time_unit="MILLISECONDS", value=100))
        )
        restored = HttpResponse.from_dict(original.to_dict())
        assert restored.status_code == 201
        assert restored.reason_phrase == "Created"
        assert restored.headers[0].name == "Location"
        assert restored.cookies[0].name == "session"
        assert isinstance(restored.body, Body)
        assert restored.body.json == {"id": 1}
        assert restored.delay.value == 100


class TestHttpForward:
    def test_forward_factory(self):
        f = HttpForward.forward()
        assert f.host is None
        assert f.port is None
        assert f.scheme is None

    def test_construction(self):
        f = HttpForward(host="example.com", port=8080, scheme="HTTPS")
        assert f.host == "example.com"
        assert f.port == 8080
        assert f.scheme == "HTTPS"

    def test_to_dict(self):
        f = HttpForward(host="example.com", port=8080, scheme="HTTP")
        assert f.to_dict() == {"host": "example.com", "port": 8080, "scheme": "HTTP"}

    def test_to_dict_with_delay(self):
        f = HttpForward(host="h", delay=Delay(time_unit="SECONDS", value=1))
        result = f.to_dict()
        assert result["delay"] == {"timeUnit": "SECONDS", "value": 1}

    def test_to_dict_strips_none(self):
        f = HttpForward(host="example.com")
        result = f.to_dict()
        assert "port" not in result
        assert "scheme" not in result

    def test_from_dict(self):
        f = HttpForward.from_dict({"host": "h", "port": 9090, "scheme": "HTTPS"})
        assert f.host == "h"
        assert f.port == 9090
        assert f.scheme == "HTTPS"

    def test_from_dict_with_delay(self):
        f = HttpForward.from_dict({
            "host": "h",
            "delay": {"timeUnit": "SECONDS", "value": 2},
        })
        assert f.delay.time_unit == "SECONDS"
        assert f.delay.value == 2

    def test_from_dict_none(self):
        assert HttpForward.from_dict(None) is None

    def test_round_trip(self):
        original = HttpForward(
            host="proxy.local",
            port=3128,
            scheme="HTTP",
            delay=Delay(time_unit="MILLISECONDS", value=500),
        )
        restored = HttpForward.from_dict(original.to_dict())
        assert restored.host == "proxy.local"
        assert restored.port == 3128
        assert restored.scheme == "HTTP"
        assert restored.delay.value == 500


class TestHttpTemplate:
    def test_defaults(self):
        t = HttpTemplate()
        assert t.template_type == "JAVASCRIPT"
        assert t.template is None

    def test_template_factory(self):
        t = HttpTemplate.template("VELOCITY", "template-content")
        assert t.template_type == "VELOCITY"
        assert t.template == "template-content"

    def test_template_factory_no_template(self):
        t = HttpTemplate.template("JAVASCRIPT")
        assert t.template_type == "JAVASCRIPT"
        assert t.template is None

    def test_to_dict(self):
        t = HttpTemplate(template_type="VELOCITY", template="$req.path")
        assert t.to_dict() == {"templateType": "VELOCITY", "template": "$req.path"}

    def test_to_dict_with_delay(self):
        t = HttpTemplate(
            template_type="JAVASCRIPT",
            template="return {};",
            delay=Delay(time_unit="SECONDS", value=1),
        )
        result = t.to_dict()
        assert result["delay"] == {"timeUnit": "SECONDS", "value": 1}

    def test_from_dict(self):
        t = HttpTemplate.from_dict({"templateType": "VELOCITY", "template": "content"})
        assert t.template_type == "VELOCITY"
        assert t.template == "content"

    def test_from_dict_defaults(self):
        t = HttpTemplate.from_dict({})
        assert t.template_type == "JAVASCRIPT"

    def test_from_dict_none(self):
        assert HttpTemplate.from_dict(None) is None

    def test_round_trip(self):
        original = HttpTemplate(
            template_type="VELOCITY",
            template="$req.method",
            delay=Delay(time_unit="SECONDS", value=2),
        )
        restored = HttpTemplate.from_dict(original.to_dict())
        assert restored.template_type == "VELOCITY"
        assert restored.template == "$req.method"
        assert restored.delay.value == 2


class TestHttpClassCallback:
    def test_callback_factory(self):
        cb = HttpClassCallback.callback("com.example.MyCallback")
        assert cb.callback_class == "com.example.MyCallback"

    def test_callback_factory_no_args(self):
        cb = HttpClassCallback.callback()
        assert cb.callback_class is None

    def test_to_dict(self):
        cb = HttpClassCallback(callback_class="com.example.Cb")
        assert cb.to_dict() == {"callbackClass": "com.example.Cb"}

    def test_to_dict_with_delay(self):
        cb = HttpClassCallback(
            callback_class="com.example.Cb",
            delay=Delay(time_unit="SECONDS", value=1),
        )
        result = cb.to_dict()
        assert result["delay"] == {"timeUnit": "SECONDS", "value": 1}

    def test_from_dict(self):
        cb = HttpClassCallback.from_dict({"callbackClass": "com.example.Cb"})
        assert cb.callback_class == "com.example.Cb"

    def test_from_dict_none(self):
        assert HttpClassCallback.from_dict(None) is None

    def test_round_trip(self):
        original = HttpClassCallback(
            callback_class="com.example.Handler",
            delay=Delay(time_unit="MILLISECONDS", value=50),
        )
        restored = HttpClassCallback.from_dict(original.to_dict())
        assert restored.callback_class == "com.example.Handler"
        assert restored.delay.value == 50


class TestHttpObjectCallback:
    def test_defaults(self):
        cb = HttpObjectCallback()
        assert cb.client_id is None
        assert cb.response_callback is None

    def test_construction(self):
        cb = HttpObjectCallback(client_id="client-1", response_callback=True)
        assert cb.client_id == "client-1"
        assert cb.response_callback is True

    def test_to_dict(self):
        cb = HttpObjectCallback(client_id="c1", response_callback=False)
        assert cb.to_dict() == {"clientId": "c1", "responseCallback": False}

    def test_to_dict_with_delay(self):
        cb = HttpObjectCallback(
            client_id="c1",
            delay=Delay(time_unit="SECONDS", value=1),
        )
        result = cb.to_dict()
        assert result["delay"] == {"timeUnit": "SECONDS", "value": 1}

    def test_from_dict(self):
        cb = HttpObjectCallback.from_dict({
            "clientId": "c2",
            "responseCallback": True,
        })
        assert cb.client_id == "c2"
        assert cb.response_callback is True

    def test_from_dict_none(self):
        assert HttpObjectCallback.from_dict(None) is None

    def test_round_trip(self):
        original = HttpObjectCallback(
            client_id="ws-client",
            response_callback=True,
            delay=Delay(time_unit="SECONDS", value=3),
        )
        restored = HttpObjectCallback.from_dict(original.to_dict())
        assert restored.client_id == "ws-client"
        assert restored.response_callback is True
        assert restored.delay.value == 3


class TestHttpError:
    def test_error_factory(self):
        e = HttpError.error()
        assert e.drop_connection is None
        assert e.response_bytes is None

    def test_construction(self):
        e = HttpError(drop_connection=True, response_bytes="AQID")
        assert e.drop_connection is True
        assert e.response_bytes == "AQID"

    def test_to_dict(self):
        e = HttpError(drop_connection=True, response_bytes="YWJj")
        assert e.to_dict() == {"dropConnection": True, "responseBytes": "YWJj"}

    def test_to_dict_with_delay(self):
        e = HttpError(drop_connection=True, delay=Delay(time_unit="SECONDS", value=1))
        result = e.to_dict()
        assert result["delay"] == {"timeUnit": "SECONDS", "value": 1}

    def test_to_dict_strips_none(self):
        e = HttpError(drop_connection=True)
        result = e.to_dict()
        assert "responseBytes" not in result

    def test_from_dict(self):
        e = HttpError.from_dict({"dropConnection": True, "responseBytes": "YQ=="})
        assert e.drop_connection is True
        assert e.response_bytes == "YQ=="

    def test_from_dict_none(self):
        assert HttpError.from_dict(None) is None

    def test_round_trip(self):
        original = HttpError(
            drop_connection=True,
            response_bytes="YWJj",
            delay=Delay(time_unit="MILLISECONDS", value=200),
        )
        restored = HttpError.from_dict(original.to_dict())
        assert restored.drop_connection is True
        assert restored.response_bytes == "YWJj"
        assert restored.delay.value == 200

    def test_stream_error_none_by_default(self):
        assert HttpError.error().stream_error is None
        assert "streamError" not in HttpError(drop_connection=True).to_dict()

    def test_stream_error_serializes_to_camel_case(self):
        e = HttpError(stream_error=7)
        assert e.to_dict() == {"streamError": 7}

    def test_stream_error_from_dict(self):
        e = HttpError.from_dict({"streamError": 268})
        assert e.stream_error == 268

    def test_stream_error_round_trip(self):
        original = HttpError(stream_error=7)
        restored = HttpError.from_dict(original.to_dict())
        assert restored.stream_error == 7

    def test_reset_connection_round_trip(self):
        e = HttpError(reset_connection=True)
        assert e.to_dict() == {"resetConnection": True}
        assert HttpError.from_dict({"resetConnection": True}).reset_connection is True
        assert "resetConnection" not in HttpError(drop_connection=True).to_dict()


class TestHttpOverrideForwardedRequest:
    def test_factory(self):
        o = HttpOverrideForwardedRequest.forward_overridden_request()
        assert o.http_request is None

    def test_factory_with_request(self):
        req = HttpRequest(path="/override")
        o = HttpOverrideForwardedRequest.forward_overridden_request(req)
        assert o.http_request is req

    def test_construction(self):
        o = HttpOverrideForwardedRequest(
            http_request=HttpRequest(path="/req"),
            http_response=HttpResponse(status_code=200),
            request_modifier={"headers": {"add": {"X-Test": ["v"]}}},
            response_modifier={"headers": {"remove": ["X-Remove"]}},
        )
        assert o.http_request.path == "/req"
        assert o.http_response.status_code == 200
        assert o.request_modifier is not None
        assert o.response_modifier is not None

    def test_to_dict(self):
        o = HttpOverrideForwardedRequest(
            http_request=HttpRequest(path="/p"),
            http_response=HttpResponse(status_code=200),
        )
        result = o.to_dict()
        assert result["httpRequest"] == {"path": "/p"}
        assert result["httpResponse"] == {"statusCode": 200}

    def test_to_dict_with_modifiers(self):
        o = HttpOverrideForwardedRequest(
            request_modifier={"headers": {"add": {}}},
            response_modifier={"headers": {"remove": []}},
        )
        result = o.to_dict()
        assert result["requestModifier"] == {"headers": {"add": {}}}
        assert result["responseModifier"] == {"headers": {"remove": []}}

    def test_to_dict_with_delay(self):
        o = HttpOverrideForwardedRequest(delay=Delay(time_unit="SECONDS", value=5))
        result = o.to_dict()
        assert result["delay"] == {"timeUnit": "SECONDS", "value": 5}

    def test_from_dict(self):
        o = HttpOverrideForwardedRequest.from_dict({
            "httpRequest": {"path": "/fwd"},
            "httpResponse": {"statusCode": 201},
            "delay": {"timeUnit": "SECONDS", "value": 1},
            "requestModifier": {"headers": {}},
            "responseModifier": {"headers": {}},
        })
        assert o.http_request.path == "/fwd"
        assert o.http_response.status_code == 201
        assert o.delay.value == 1
        assert o.request_modifier is not None
        assert o.response_modifier is not None

    def test_from_dict_none(self):
        assert HttpOverrideForwardedRequest.from_dict(None) is None

    def test_round_trip(self):
        original = HttpOverrideForwardedRequest(
            http_request=HttpRequest(method="POST", path="/api"),
            http_response=HttpResponse(status_code=202),
            delay=Delay(time_unit="MILLISECONDS", value=100),
            request_modifier={"path": {"regex": "/old/(.*)", "substitution": "/new/$1"}},
        )
        # Next to a modifier the aliases are written, and so read back, as the overrides.
        restored = HttpOverrideForwardedRequest.from_dict(original.to_dict())
        assert restored.request_override.method == "POST"
        assert restored.request_override.path == "/api"
        assert restored.response_override.status_code == 202
        assert restored.delay.value == 100
        assert restored.request_modifier["path"]["regex"] == "/old/(.*)"


class TestHttpRequestAndHttpResponse:
    def test_defaults(self):
        rr = HttpRequestAndHttpResponse()
        assert rr.http_request is None
        assert rr.http_response is None

    def test_construction_with_nested(self):
        rr = HttpRequestAndHttpResponse(
            http_request=HttpRequest(method="GET", path="/test"),
            http_response=HttpResponse(status_code=200, body="ok"),
        )
        assert rr.http_request.method == "GET"
        assert rr.http_response.status_code == 200

    def test_to_dict(self):
        rr = HttpRequestAndHttpResponse(
            http_request=HttpRequest(path="/p"),
            http_response=HttpResponse(status_code=200),
        )
        result = rr.to_dict()
        assert result == {
            "httpRequest": {"path": "/p"},
            "httpResponse": {"statusCode": 200},
        }

    def test_to_dict_strips_none(self):
        rr = HttpRequestAndHttpResponse(http_request=HttpRequest(path="/only"))
        result = rr.to_dict()
        assert "httpResponse" not in result

    def test_from_dict(self):
        rr = HttpRequestAndHttpResponse.from_dict({
            "httpRequest": {"method": "POST", "path": "/api"},
            "httpResponse": {"statusCode": 201},
        })
        assert rr.http_request.method == "POST"
        assert rr.http_response.status_code == 201

    def test_from_dict_none(self):
        assert HttpRequestAndHttpResponse.from_dict(None) is None

    def test_round_trip(self):
        original = HttpRequestAndHttpResponse(
            http_request=HttpRequest.request("/test").with_method("GET"),
            http_response=HttpResponse.response("response body"),
        )
        restored = HttpRequestAndHttpResponse.from_dict(original.to_dict())
        assert restored.http_request.path == "/test"
        assert restored.http_request.method == "GET"
        assert restored.http_response.body == "response body"
        assert restored.http_response.status_code == 200


class TestExpectationId:
    def test_defaults(self):
        eid = ExpectationId()
        assert eid.id == ""

    def test_construction(self):
        eid = ExpectationId(id="exp-123")
        assert eid.id == "exp-123"

    def test_to_dict(self):
        eid = ExpectationId(id="my-id")
        assert eid.to_dict() == {"id": "my-id"}

    def test_to_dict_empty(self):
        eid = ExpectationId()
        assert eid.to_dict() == {"id": ""}

    def test_from_dict(self):
        eid = ExpectationId.from_dict({"id": "restored-id"})
        assert eid.id == "restored-id"

    def test_from_dict_none(self):
        assert ExpectationId.from_dict(None) is None

    def test_from_dict_missing_id(self):
        eid = ExpectationId.from_dict({})
        assert eid.id == ""

    def test_round_trip(self):
        original = ExpectationId(id="abc-def")
        restored = ExpectationId.from_dict(original.to_dict())
        assert restored.id == "abc-def"


class TestExpectation:
    def test_defaults(self):
        e = Expectation()
        assert e.id is None
        assert e.priority is None
        assert e.http_request is None
        assert e.http_response is None

    def test_with_response_action(self):
        e = Expectation(
            id="exp-1",
            priority=5,
            http_request=HttpRequest(method="GET", path="/test"),
            http_response=HttpResponse(status_code=200, body="ok"),
            times=Times.exactly(3),
            time_to_live=TimeToLive.exactly(60, "SECONDS"),
        )
        assert e.id == "exp-1"
        assert e.priority == 5
        assert e.http_request.path == "/test"
        assert e.http_response.status_code == 200
        assert e.times.remaining_times == 3
        assert e.time_to_live.time_to_live == 60

    def test_with_forward_action(self):
        e = Expectation(
            http_request=HttpRequest(path="/fwd"),
            http_forward=HttpForward(host="target.com", port=8080),
        )
        assert e.http_forward.host == "target.com"

    def test_with_error_action(self):
        e = Expectation(
            http_request=HttpRequest(path="/err"),
            http_error=HttpError(drop_connection=True),
        )
        assert e.http_error.drop_connection is True

    def test_with_template_actions(self):
        e = Expectation(
            http_response_template=HttpTemplate(template_type="JAVASCRIPT", template="return {};"),
            http_forward_template=HttpTemplate(template_type="VELOCITY", template="$req"),
        )
        assert e.http_response_template.template_type == "JAVASCRIPT"
        assert e.http_forward_template.template_type == "VELOCITY"

    def test_with_callback_actions(self):
        e = Expectation(
            http_response_class_callback=HttpClassCallback(callback_class="com.Resp"),
            http_forward_class_callback=HttpClassCallback(callback_class="com.Fwd"),
            http_response_object_callback=HttpObjectCallback(client_id="r1"),
            http_forward_object_callback=HttpObjectCallback(client_id="f1"),
        )
        assert e.http_response_class_callback.callback_class == "com.Resp"
        assert e.http_forward_class_callback.callback_class == "com.Fwd"
        assert e.http_response_object_callback.client_id == "r1"
        assert e.http_forward_object_callback.client_id == "f1"

    def test_with_override(self):
        e = Expectation(
            http_override_forwarded_request=HttpOverrideForwardedRequest(
                http_request=HttpRequest(path="/new"),
            ),
        )
        assert e.http_override_forwarded_request.http_request.path == "/new"

    def test_to_dict_minimal(self):
        e = Expectation(
            http_request=HttpRequest(path="/test"),
            http_response=HttpResponse(status_code=200),
        )
        result = e.to_dict()
        assert result == {
            "httpRequest": {"path": "/test"},
            "httpResponse": {"statusCode": 200},
        }

    def test_to_dict_full(self):
        e = Expectation(
            id="full-id",
            priority=10,
            http_request=HttpRequest(method="POST", path="/api"),
            http_response=HttpResponse(status_code=201),
            times=Times.exactly(5),
            time_to_live=TimeToLive.exactly(300, "SECONDS"),
        )
        result = e.to_dict()
        assert result["id"] == "full-id"
        assert result["priority"] == 10
        assert result["httpRequest"] == {"method": "POST", "path": "/api"}
        assert result["httpResponse"] == {"statusCode": 201}
        assert result["times"] == {"remainingTimes": 5, "unlimited": False}
        assert result["timeToLive"] == {"timeUnit": "SECONDS", "timeToLive": 300, "unlimited": False}

    def test_to_dict_strips_none(self):
        e = Expectation(http_request=HttpRequest(path="/test"))
        result = e.to_dict()
        assert "httpResponse" not in result
        assert "httpForward" not in result
        assert "httpError" not in result
        assert "times" not in result
        assert "id" not in result

    def test_from_dict(self):
        e = Expectation.from_dict({
            "id": "parsed-id",
            "priority": 3,
            "httpRequest": {"method": "GET", "path": "/get"},
            "httpResponse": {"statusCode": 200, "body": "ok"},
        })
        assert e.id == "parsed-id"
        assert e.priority == 3
        assert e.http_request.method == "GET"
        assert e.http_request.path == "/get"
        assert e.http_response.status_code == 200
        assert e.http_response.body == "ok"

    def test_from_dict_with_forward(self):
        e = Expectation.from_dict({
            "httpForward": {"host": "example.com", "port": 8080},
        })
        assert e.http_forward.host == "example.com"
        assert e.http_forward.port == 8080

    def test_from_dict_with_error(self):
        e = Expectation.from_dict({
            "httpError": {"dropConnection": True},
        })
        assert e.http_error.drop_connection is True

    def test_from_dict_with_times(self):
        e = Expectation.from_dict({
            "times": {"remainingTimes": 2, "unlimited": False},
            "timeToLive": {"timeUnit": "SECONDS", "timeToLive": 60, "unlimited": False},
        })
        assert e.times.remaining_times == 2
        assert e.time_to_live.time_to_live == 60

    def test_from_dict_with_templates(self):
        e = Expectation.from_dict({
            "httpResponseTemplate": {"templateType": "JAVASCRIPT", "template": "return {};"},
            "httpForwardTemplate": {"templateType": "VELOCITY", "template": "$req"},
        })
        assert e.http_response_template.template_type == "JAVASCRIPT"
        assert e.http_forward_template.template_type == "VELOCITY"

    def test_from_dict_with_callbacks(self):
        e = Expectation.from_dict({
            "httpResponseClassCallback": {"callbackClass": "com.Resp"},
            "httpForwardClassCallback": {"callbackClass": "com.Fwd"},
            "httpResponseObjectCallback": {"clientId": "r1"},
            "httpForwardObjectCallback": {"clientId": "f1"},
        })
        assert e.http_response_class_callback.callback_class == "com.Resp"
        assert e.http_forward_class_callback.callback_class == "com.Fwd"
        assert e.http_response_object_callback.client_id == "r1"
        assert e.http_forward_object_callback.client_id == "f1"

    def test_from_dict_with_override(self):
        e = Expectation.from_dict({
            "httpOverrideForwardedRequest": {
                "httpRequest": {"path": "/new"},
                "httpResponse": {"statusCode": 200},
            },
        })
        assert e.http_override_forwarded_request.http_request.path == "/new"
        assert e.http_override_forwarded_request.http_response.status_code == 200

    def test_from_dict_none(self):
        assert Expectation.from_dict(None) is None

    def test_from_dict_missing_optional_fields(self):
        e = Expectation.from_dict({})
        assert e.id is None
        assert e.http_request is None
        assert e.http_response is None
        assert e.times is None

    def test_round_trip_response(self):
        original = Expectation(
            id="rt-1",
            priority=7,
            http_request=HttpRequest.request("/api").with_method("POST").with_header("CT", "json"),
            http_response=HttpResponse.response("created", 201),
            times=Times.exactly(10),
            time_to_live=TimeToLive.exactly(300, "SECONDS"),
        )
        restored = Expectation.from_dict(original.to_dict())
        assert restored.id == "rt-1"
        assert restored.priority == 7
        assert restored.http_request.path == "/api"
        assert restored.http_request.method == "POST"
        assert restored.http_request.headers[0].name == "CT"
        assert restored.http_response.status_code == 201
        assert restored.http_response.body == "created"
        assert restored.times.remaining_times == 10
        assert restored.time_to_live.time_to_live == 300

    def test_round_trip_forward(self):
        original = Expectation(
            http_request=HttpRequest(path="/fwd"),
            http_forward=HttpForward(host="target.com", port=9090, scheme="HTTPS"),
        )
        restored = Expectation.from_dict(original.to_dict())
        assert restored.http_forward.host == "target.com"
        assert restored.http_forward.port == 9090
        assert restored.http_forward.scheme == "HTTPS"

    def test_round_trip_error(self):
        original = Expectation(
            http_request=HttpRequest(path="/err"),
            http_error=HttpError(drop_connection=True, response_bytes="YWJj"),
        )
        restored = Expectation.from_dict(original.to_dict())
        assert restored.http_error.drop_connection is True
        assert restored.http_error.response_bytes == "YWJj"

    def test_round_trip_before_and_after_actions(self):
        original = Expectation(
            http_request=HttpRequest(path="/api"),
            http_response=HttpResponse(status_code=200, body="ok"),
            before_actions=[
                AfterAction(
                    http_request=HttpRequest(path="/hook"),
                    blocking=True,
                    timeout=Delay(time_unit="SECONDS", value=2),
                    failure_policy="FAIL_FAST",
                )
            ],
            after_actions=[AfterAction(http_request=HttpRequest(path="/after"))],
        )
        d = original.to_dict()
        assert d["beforeActions"][0]["blocking"] is True
        assert d["beforeActions"][0]["failurePolicy"] == "FAIL_FAST"
        restored = Expectation.from_dict(d)
        before = restored.before_actions[0]
        assert before.http_request.path == "/hook"
        assert before.blocking is True
        assert before.timeout.value == 2
        assert before.failure_policy == "FAIL_FAST"
        assert restored.after_actions[0].http_request.path == "/after"

    def test_round_trip_templated_after_action(self):
        original = Expectation(
            http_request=HttpRequest(path="/api"),
            http_response=HttpResponse(status_code=200),
            after_actions=[
                AfterAction(
                    http_request_template=HttpTemplate(template_type="VELOCITY", template='{ "path": "/hook" }'),
                    delay=Delay(time_unit="MILLISECONDS", value=5),
                )
            ],
        )
        d = original.to_dict()
        assert d["afterActions"][0] == {
            "httpRequestTemplate": {"templateType": "VELOCITY", "template": '{ "path": "/hook" }'},
            "delay": {"timeUnit": "MILLISECONDS", "value": 5},
        }
        restored = Expectation.from_dict(d)
        assert restored.after_actions[0].http_request_template.template_type == "VELOCITY"
        assert restored.after_actions[0].http_request is None
        assert restored.to_dict() == d

    def test_before_action_controls_omitted_when_unset(self):
        e = Expectation(
            http_request=HttpRequest(path="/api"),
            http_response=HttpResponse(status_code=200),
            before_actions=[AfterAction(http_request=HttpRequest(path="/hook"))],
        )
        before = e.to_dict()["beforeActions"][0]
        assert "blocking" not in before
        assert "timeout" not in before
        assert "failurePolicy" not in before

    def test_with_chaos(self):
        chaos = HttpChaosProfile(
            error_status=503,
            error_probability=0.5,
            retry_after="30",
            latency=Delay(time_unit="MILLISECONDS", value=200),
            seed=42,
            succeed_first=3,
            fail_request_count=5,
        )
        e = Expectation(
            http_request=HttpRequest(path="/chaos"),
            http_response=HttpResponse(status_code=200),
            chaos=chaos,
        )
        assert e.chaos.error_status == 503
        assert e.chaos.succeed_first == 3
        assert e.chaos.fail_request_count == 5

    def test_to_dict_with_chaos(self):
        e = Expectation(
            http_request=HttpRequest(path="/chaos"),
            http_response=HttpResponse(status_code=200),
            chaos=HttpChaosProfile(error_status=429, error_probability=0.25),
        )
        result = e.to_dict()
        assert result["chaos"] == {"errorStatus": 429, "errorProbability": 0.25}

    def test_from_dict_with_chaos(self):
        e = Expectation.from_dict({
            "httpRequest": {"path": "/chaos"},
            "httpResponse": {"statusCode": 200},
            "chaos": {
                "errorStatus": 503,
                "errorProbability": 0.5,
                "retryAfter": "30",
                "latency": {"timeUnit": "MILLISECONDS", "value": 200},
                "seed": 42,
                "succeedFirst": 3,
                "failRequestCount": 5,
            },
        })
        assert e.chaos is not None
        assert e.chaos.error_status == 503
        assert e.chaos.error_probability == 0.5
        assert e.chaos.retry_after == "30"
        assert e.chaos.latency.time_unit == "MILLISECONDS"
        assert e.chaos.latency.value == 200
        assert e.chaos.seed == 42
        assert e.chaos.succeed_first == 3
        assert e.chaos.fail_request_count == 5

    def test_round_trip_with_chaos(self):
        original = Expectation(
            http_request=HttpRequest(path="/chaos"),
            http_response=HttpResponse(status_code=200),
            chaos=HttpChaosProfile(
                error_status=503,
                error_probability=0.5,
                retry_after="30",
                latency=Delay(time_unit="MILLISECONDS", value=200),
                seed=42,
                succeed_first=3,
                fail_request_count=5,
            ),
        )
        restored = Expectation.from_dict(original.to_dict())
        assert restored.chaos is not None
        assert restored.chaos.to_dict() == original.chaos.to_dict()


class TestCrossProtocolScenario:
    def test_defaults_emit_nothing(self):
        assert CrossProtocolScenario().to_dict() == {}

    def test_to_dict_camel_case(self):
        c = CrossProtocolScenario(
            trigger=CrossProtocolTrigger.DNS_QUERY,
            match_pattern="api.example.com",
            scenario_name="Deploy",
            target_state="DnsObserved",
        )
        assert c.to_dict() == {
            "trigger": "DNS_QUERY",
            "matchPattern": "api.example.com",
            "scenarioName": "Deploy",
            "targetState": "DnsObserved",
        }

    def test_match_pattern_omitted_when_none(self):
        c = CrossProtocolScenario(
            trigger=CrossProtocolTrigger.WEBSOCKET_CONNECT,
            scenario_name="Deploy",
            target_state="Connected",
        )
        d = c.to_dict()
        assert "matchPattern" not in d
        assert d == {
            "trigger": "WEBSOCKET_CONNECT",
            "scenarioName": "Deploy",
            "targetState": "Connected",
        }

    def test_from_dict(self):
        c = CrossProtocolScenario.from_dict({
            "trigger": "GRPC_REQUEST",
            "matchPattern": "MyService",
            "scenarioName": "Deploy",
            "targetState": "GrpcSeen",
        })
        assert c.trigger == "GRPC_REQUEST"
        assert c.match_pattern == "MyService"
        assert c.scenario_name == "Deploy"
        assert c.target_state == "GrpcSeen"

    def test_from_dict_none(self):
        assert CrossProtocolScenario.from_dict(None) is None

    def test_round_trip(self):
        original = CrossProtocolScenario(
            trigger=CrossProtocolTrigger.HTTP_REQUEST,
            match_pattern="/health",
            scenario_name="Deploy",
            target_state="HttpSeen",
        )
        assert CrossProtocolScenario.from_dict(original.to_dict()).to_dict() == original.to_dict()


class TestExpectationScenarioParity:
    """Serialization of the typed stateful-scenario fields against the
    scenario-parity contract (camelCase JSON must match the core contract).
    """

    def test_response_mode_constants(self):
        assert ResponseMode.SEQUENTIAL == "SEQUENTIAL"
        assert ResponseMode.RANDOM == "RANDOM"
        assert ResponseMode.WEIGHTED == "WEIGHTED"
        assert ResponseMode.SWITCH == "SWITCH"

    def test_cross_protocol_trigger_constants(self):
        assert CrossProtocolTrigger.DNS_QUERY == "DNS_QUERY"
        assert CrossProtocolTrigger.WEBSOCKET_CONNECT == "WEBSOCKET_CONNECT"
        assert CrossProtocolTrigger.GRPC_REQUEST == "GRPC_REQUEST"
        assert CrossProtocolTrigger.HTTP_REQUEST == "HTTP_REQUEST"

    def test_new_fields_emit_camel_case(self):
        e = Expectation(
            http_request=HttpRequest(path="/api"),
            http_responses=[
                HttpResponse(status_code=200, body="a"),
                HttpResponse(status_code=500, body="b"),
            ],
            response_mode=ResponseMode.WEIGHTED,
            response_weights=[3, 1],
            switch_after=2,
            cross_protocol_scenarios=[
                CrossProtocolScenario(
                    trigger=CrossProtocolTrigger.DNS_QUERY,
                    match_pattern="api.example.com",
                    scenario_name="Deploy",
                    target_state="DnsObserved",
                )
            ],
            scenario_name="Deploy",
            scenario_state="Pending",
            new_scenario_state="Deploying",
        )
        d = e.to_dict()
        assert d["responseMode"] == "WEIGHTED"
        assert d["responseWeights"] == [3, 1]
        assert d["switchAfter"] == 2
        assert d["crossProtocolScenarios"] == [{
            "trigger": "DNS_QUERY",
            "matchPattern": "api.example.com",
            "scenarioName": "Deploy",
            "targetState": "DnsObserved",
        }]
        assert d["scenarioName"] == "Deploy"
        assert d["scenarioState"] == "Pending"
        assert d["newScenarioState"] == "Deploying"
        assert len(d["httpResponses"]) == 2

    def test_new_fields_omitted_when_unset(self):
        d = Expectation(
            http_request=HttpRequest(path="/api"),
            http_response=HttpResponse(status_code=200),
        ).to_dict()
        assert "responseWeights" not in d
        assert "switchAfter" not in d
        assert "crossProtocolScenarios" not in d

    def test_round_trip(self):
        original = Expectation(
            http_request=HttpRequest(path="/api"),
            http_responses=[HttpResponse(status_code=200), HttpResponse(status_code=500)],
            response_mode=ResponseMode.SWITCH,
            response_weights=[5, 2],
            switch_after=3,
            cross_protocol_scenarios=[
                CrossProtocolScenario(
                    trigger=CrossProtocolTrigger.WEBSOCKET_CONNECT,
                    scenario_name="Deploy",
                    target_state="Connected",
                )
            ],
        )
        restored = Expectation.from_dict(original.to_dict())
        assert restored.response_mode == "SWITCH"
        assert restored.response_weights == [5, 2]
        assert restored.switch_after == 3
        assert len(restored.cross_protocol_scenarios) == 1
        assert restored.cross_protocol_scenarios[0].trigger == "WEBSOCKET_CONNECT"
        assert restored.cross_protocol_scenarios[0].scenario_name == "Deploy"
        assert restored.cross_protocol_scenarios[0].target_state == "Connected"
        assert restored.to_dict() == original.to_dict()


class TestOpenAPIDefinition:
    def test_defaults(self):
        d = OpenAPIDefinition()
        assert d.spec_url_or_payload is None
        assert d.operation_id is None

    def test_construction(self):
        d = OpenAPIDefinition(
            spec_url_or_payload="https://example.com/spec.json",
            operation_id="getUser",
        )
        assert d.spec_url_or_payload == "https://example.com/spec.json"
        assert d.operation_id == "getUser"

    def test_to_dict(self):
        d = OpenAPIDefinition(spec_url_or_payload="spec.yaml", operation_id="op1")
        assert d.to_dict() == {"specUrlOrPayload": "spec.yaml", "operationId": "op1"}

    def test_to_dict_strips_none(self):
        d = OpenAPIDefinition(spec_url_or_payload="spec.yaml")
        result = d.to_dict()
        assert "operationId" not in result

    def test_from_dict(self):
        d = OpenAPIDefinition.from_dict({
            "specUrlOrPayload": "https://api.io/spec",
            "operationId": "listItems",
        })
        assert d.spec_url_or_payload == "https://api.io/spec"
        assert d.operation_id == "listItems"

    def test_from_dict_none(self):
        assert OpenAPIDefinition.from_dict(None) is None

    def test_round_trip(self):
        original = OpenAPIDefinition(
            spec_url_or_payload="https://example.com/openapi.json",
            operation_id="createUser",
        )
        restored = OpenAPIDefinition.from_dict(original.to_dict())
        assert restored.spec_url_or_payload == original.spec_url_or_payload
        assert restored.operation_id == original.operation_id


class TestOpenAPIExpectation:
    def test_defaults(self):
        e = OpenAPIExpectation()
        assert e.spec_url_or_payload is None
        assert e.operations_and_responses is None

    def test_construction(self):
        e = OpenAPIExpectation(
            spec_url_or_payload="https://example.com/spec",
            operations_and_responses={"getUser": "200", "createUser": "201"},
        )
        assert e.operations_and_responses["getUser"] == "200"

    def test_to_dict(self):
        e = OpenAPIExpectation(
            spec_url_or_payload="spec.yaml",
            operations_and_responses={"op": "200"},
        )
        assert e.to_dict() == {
            "specUrlOrPayload": "spec.yaml",
            "operationsAndResponses": {"op": "200"},
        }

    def test_to_dict_strips_none(self):
        e = OpenAPIExpectation(spec_url_or_payload="spec.yaml")
        result = e.to_dict()
        assert "operationsAndResponses" not in result

    def test_from_dict(self):
        e = OpenAPIExpectation.from_dict({
            "specUrlOrPayload": "https://api.io/spec",
            "operationsAndResponses": {"list": "200"},
        })
        assert e.spec_url_or_payload == "https://api.io/spec"
        assert e.operations_and_responses == {"list": "200"}

    def test_from_dict_none(self):
        assert OpenAPIExpectation.from_dict(None) is None

    def test_round_trip(self):
        original = OpenAPIExpectation(
            spec_url_or_payload="https://example.com/spec.json",
            operations_and_responses={"getItems": "200", "deleteItem": "204"},
        )
        restored = OpenAPIExpectation.from_dict(original.to_dict())
        assert restored.spec_url_or_payload == original.spec_url_or_payload
        assert restored.operations_and_responses == original.operations_and_responses


class TestVerificationTimes:
    def test_defaults(self):
        vt = VerificationTimes()
        assert vt.at_least is None
        assert vt.at_most is None

    def test_at_least_factory(self):
        vt = VerificationTimes.at_least(3)
        assert vt.at_least == 3
        assert vt.at_most is None

    def test_at_most_factory(self):
        vt = VerificationTimes.at_most(5)
        assert vt.at_most == 5
        assert vt.at_least is None

    def test_exactly_factory(self):
        vt = VerificationTimes.exactly(2)
        assert vt.at_least == 2
        assert vt.at_most == 2

    def test_once_factory(self):
        vt = VerificationTimes.once()
        assert vt.at_least == 1
        assert vt.at_most == 1

    def test_between_factory(self):
        vt = VerificationTimes.between(2, 5)
        assert vt.at_least == 2
        assert vt.at_most == 5

    def test_to_dict(self):
        vt = VerificationTimes(at_least=1, at_most=3)
        assert vt.to_dict() == {"atLeast": 1, "atMost": 3}

    def test_to_dict_strips_none(self):
        vt = VerificationTimes(at_least=1)
        result = vt.to_dict()
        assert result == {"atLeast": 1}
        assert "atMost" not in result

    def test_from_dict(self):
        vt = VerificationTimes.from_dict({"atLeast": 2, "atMost": 10})
        assert vt.at_least == 2
        assert vt.at_most == 10

    def test_from_dict_none(self):
        assert VerificationTimes.from_dict(None) is None

    def test_round_trip_at_least(self):
        original = VerificationTimes.at_least(5)
        restored = VerificationTimes.from_dict(original.to_dict())
        assert restored.at_least == 5
        assert restored.at_most is None

    def test_round_trip_between(self):
        original = VerificationTimes.between(3, 7)
        restored = VerificationTimes.from_dict(original.to_dict())
        assert restored.at_least == 3
        assert restored.at_most == 7

    def test_round_trip_once(self):
        original = VerificationTimes.once()
        restored = VerificationTimes.from_dict(original.to_dict())
        assert restored.at_least == 1
        assert restored.at_most == 1


class TestVerification:
    def test_defaults(self):
        v = Verification()
        assert v.http_request is None
        assert v.http_response is None
        assert v.expectation_id is None
        assert v.times is None

    def test_construction(self):
        v = Verification(
            http_request=HttpRequest(path="/verify"),
            times=VerificationTimes.exactly(2),
            maximum_number_of_request_to_return_in_verification_failure=10,
        )
        assert v.http_request.path == "/verify"
        assert v.times.at_least == 2
        assert v.maximum_number_of_request_to_return_in_verification_failure == 10

    def test_with_expectation_id(self):
        v = Verification(expectation_id=ExpectationId(id="exp-1"))
        assert v.expectation_id.id == "exp-1"

    def test_to_dict(self):
        v = Verification(
            http_request=HttpRequest(path="/v"),
            times=VerificationTimes.once(),
        )
        result = v.to_dict()
        assert result == {
            "httpRequest": {"path": "/v"},
            "times": {"atLeast": 1, "atMost": 1},
        }

    def test_to_dict_with_max_requests(self):
        v = Verification(
            http_request=HttpRequest(path="/test"),
            maximum_number_of_request_to_return_in_verification_failure=5,
        )
        result = v.to_dict()
        assert result["maximumNumberOfRequestToReturnInVerificationFailure"] == 5

    def test_to_dict_with_expectation_id(self):
        v = Verification(expectation_id=ExpectationId(id="eid"))
        result = v.to_dict()
        assert result["expectationId"] == {"id": "eid"}

    def test_with_http_response(self):
        v = Verification(
            http_request=HttpRequest(path="/test"),
            http_response=HttpResponse(status_code=200),
        )
        assert v.http_response.status_code == 200
        result = v.to_dict()
        assert result["httpResponse"] == {"statusCode": 200}

    def test_to_dict_response_only(self):
        v = Verification(http_response=HttpResponse(status_code=404))
        result = v.to_dict()
        assert "httpRequest" not in result
        assert result["httpResponse"] == {"statusCode": 404}

    def test_to_dict_strips_none(self):
        v = Verification(http_request=HttpRequest(path="/p"))
        result = v.to_dict()
        assert "expectationId" not in result
        assert "httpResponse" not in result
        assert "times" not in result

    def test_from_dict(self):
        v = Verification.from_dict({
            "httpRequest": {"path": "/test"},
            "times": {"atLeast": 1, "atMost": 1},
            "maximumNumberOfRequestToReturnInVerificationFailure": 15,
        })
        assert v.http_request.path == "/test"
        assert v.times.at_least == 1
        assert v.maximum_number_of_request_to_return_in_verification_failure == 15

    def test_from_dict_with_expectation_id(self):
        v = Verification.from_dict({
            "expectationId": {"id": "eid-123"},
        })
        assert v.expectation_id.id == "eid-123"

    def test_from_dict_with_http_response(self):
        v = Verification.from_dict({
            "httpRequest": {"path": "/test"},
            "httpResponse": {"statusCode": 200},
        })
        assert v.http_request.path == "/test"
        assert v.http_response.status_code == 200

    def test_from_dict_none(self):
        assert Verification.from_dict(None) is None

    def test_round_trip(self):
        original = Verification(
            http_request=HttpRequest.request("/api").with_method("GET"),
            times=VerificationTimes.between(2, 5),
            maximum_number_of_request_to_return_in_verification_failure=20,
        )
        restored = Verification.from_dict(original.to_dict())
        assert restored.http_request.path == "/api"
        assert restored.http_request.method == "GET"
        assert restored.times.at_least == 2
        assert restored.times.at_most == 5
        assert restored.maximum_number_of_request_to_return_in_verification_failure == 20

    def test_round_trip_with_response(self):
        original = Verification(
            http_request=HttpRequest.request("/api").with_method("POST"),
            http_response=HttpResponse(status_code=201),
            times=VerificationTimes.once(),
        )
        restored = Verification.from_dict(original.to_dict())
        assert restored.http_request.path == "/api"
        assert restored.http_response.status_code == 201
        assert restored.times.at_least == 1


class TestVerificationSequence:
    def test_defaults(self):
        vs = VerificationSequence()
        assert vs.http_requests is None
        assert vs.http_responses is None
        assert vs.expectation_ids is None

    def test_with_requests(self):
        vs = VerificationSequence(
            http_requests=[
                HttpRequest(path="/a"),
                HttpRequest(path="/b"),
            ],
        )
        assert len(vs.http_requests) == 2

    def test_with_expectation_ids(self):
        vs = VerificationSequence(
            expectation_ids=[
                ExpectationId(id="e1"),
                ExpectationId(id="e2"),
            ],
        )
        assert len(vs.expectation_ids) == 2

    def test_with_http_responses(self):
        vs = VerificationSequence(
            http_requests=[HttpRequest(path="/a"), HttpRequest(path="/b")],
            http_responses=[HttpResponse(status_code=200), HttpResponse(status_code=201)],
        )
        assert len(vs.http_responses) == 2
        assert vs.http_responses[0].status_code == 200
        assert vs.http_responses[1].status_code == 201

    def test_to_dict(self):
        vs = VerificationSequence(
            http_requests=[HttpRequest(path="/a"), HttpRequest(path="/b")],
        )
        result = vs.to_dict()
        assert result == {
            "httpRequests": [{"path": "/a"}, {"path": "/b"}],
        }

    def test_to_dict_with_expectation_ids(self):
        vs = VerificationSequence(
            expectation_ids=[ExpectationId(id="e1"), ExpectationId(id="e2")],
        )
        result = vs.to_dict()
        assert result == {
            "expectationIds": [{"id": "e1"}, {"id": "e2"}],
        }

    def test_to_dict_with_http_responses(self):
        vs = VerificationSequence(
            http_requests=[HttpRequest(path="/a"), HttpRequest(path="/b")],
            http_responses=[HttpResponse(status_code=200), HttpResponse(status_code=201)],
        )
        result = vs.to_dict()
        assert result == {
            "httpRequests": [{"path": "/a"}, {"path": "/b"}],
            "httpResponses": [{"statusCode": 200}, {"statusCode": 201}],
        }

    def test_to_dict_strips_none(self):
        vs = VerificationSequence(http_requests=[HttpRequest(path="/a")])
        result = vs.to_dict()
        assert "expectationIds" not in result
        assert "httpResponses" not in result

    def test_from_dict(self):
        vs = VerificationSequence.from_dict({
            "httpRequests": [
                {"method": "GET", "path": "/first"},
                {"method": "POST", "path": "/second"},
            ],
        })
        assert len(vs.http_requests) == 2
        assert vs.http_requests[0].path == "/first"
        assert vs.http_requests[1].method == "POST"

    def test_from_dict_with_expectation_ids(self):
        vs = VerificationSequence.from_dict({
            "expectationIds": [{"id": "e1"}, {"id": "e2"}],
        })
        assert len(vs.expectation_ids) == 2
        assert vs.expectation_ids[0].id == "e1"

    def test_from_dict_none(self):
        assert VerificationSequence.from_dict(None) is None

    def test_from_dict_with_http_responses(self):
        vs = VerificationSequence.from_dict({
            "httpRequests": [{"path": "/a"}, {"path": "/b"}],
            "httpResponses": [{"statusCode": 200}, {"statusCode": 201}],
        })
        assert len(vs.http_requests) == 2
        assert len(vs.http_responses) == 2
        assert vs.http_responses[0].status_code == 200
        assert vs.http_responses[1].status_code == 201

    def test_from_dict_empty(self):
        vs = VerificationSequence.from_dict({})
        assert vs.http_requests is None
        assert vs.http_responses is None
        assert vs.expectation_ids is None

    def test_round_trip(self):
        original = VerificationSequence(
            http_requests=[
                HttpRequest.request("/a").with_method("GET"),
                HttpRequest.request("/b").with_method("POST"),
            ],
            expectation_ids=[
                ExpectationId(id="ea"),
                ExpectationId(id="eb"),
            ],
        )
        restored = VerificationSequence.from_dict(original.to_dict())
        assert len(restored.http_requests) == 2
        assert restored.http_requests[0].path == "/a"
        assert restored.http_requests[1].method == "POST"
        assert len(restored.expectation_ids) == 2
        assert restored.expectation_ids[0].id == "ea"

    def test_round_trip_with_responses(self):
        original = VerificationSequence(
            http_requests=[
                HttpRequest.request("/a").with_method("GET"),
                HttpRequest.request("/b").with_method("POST"),
            ],
            http_responses=[
                HttpResponse(status_code=200),
                HttpResponse(status_code=201),
            ],
        )
        restored = VerificationSequence.from_dict(original.to_dict())
        assert len(restored.http_requests) == 2
        assert len(restored.http_responses) == 2
        assert restored.http_responses[0].status_code == 200
        assert restored.http_responses[1].status_code == 201


class TestPorts:
    def test_defaults(self):
        p = Ports()
        assert p.ports == []

    def test_construction(self):
        p = Ports(ports=[8080, 8443])
        assert p.ports == [8080, 8443]

    def test_to_dict(self):
        p = Ports(ports=[1080])
        assert p.to_dict() == {"ports": [1080]}

    def test_to_dict_empty(self):
        p = Ports()
        assert p.to_dict() == {"ports": []}

    def test_from_dict(self):
        p = Ports.from_dict({"ports": [8080, 9090]})
        assert p.ports == [8080, 9090]

    def test_from_dict_none(self):
        assert Ports.from_dict(None) is None

    def test_from_dict_missing_ports(self):
        p = Ports.from_dict({})
        assert p.ports == []

    def test_round_trip(self):
        original = Ports(ports=[1080, 1443, 8080])
        restored = Ports.from_dict(original.to_dict())
        assert restored.ports == [1080, 1443, 8080]


class TestHttpChaosProfile:
    def test_defaults(self):
        chaos = HttpChaosProfile()
        assert chaos.error_status is None
        assert chaos.error_probability is None
        assert chaos.drop_connection_probability is None
        assert chaos.retry_after is None
        assert chaos.latency is None
        assert chaos.seed is None
        assert chaos.succeed_first is None
        assert chaos.fail_request_count is None
        assert chaos.outage_after_millis is None
        assert chaos.outage_duration_millis is None
        assert chaos.truncate_body_at_fraction is None
        assert chaos.malformed_body is None

    def test_body_corruption_round_trip(self):
        chaos = HttpChaosProfile(truncate_body_at_fraction=0.25, malformed_body=True)
        result = chaos.to_dict()
        assert result == {"truncateBodyAtFraction": 0.25, "malformedBody": True}
        restored = HttpChaosProfile.from_dict(result)
        assert restored.truncate_body_at_fraction == 0.25
        assert restored.malformed_body is True

    def test_slow_response_round_trip(self):
        chaos = HttpChaosProfile(
            slow_response_chunk_size=8,
            slow_response_chunk_delay=Delay(time_unit="MILLISECONDS", value=250),
        )
        result = chaos.to_dict()
        assert result == {
            "slowResponseChunkSize": 8,
            "slowResponseChunkDelay": {"timeUnit": "MILLISECONDS", "value": 250},
        }
        restored = HttpChaosProfile.from_dict(result)
        assert restored.slow_response_chunk_size == 8
        assert restored.slow_response_chunk_delay.time_unit == "MILLISECONDS"
        assert restored.slow_response_chunk_delay.value == 250

    def test_quota_round_trip(self):
        chaos = HttpChaosProfile(
            quota_name="acct",
            quota_limit=4,
            quota_window_millis=60000,
            quota_error_status=429,
        )
        result = chaos.to_dict()
        assert result == {
            "quotaName": "acct",
            "quotaLimit": 4,
            "quotaWindowMillis": 60000,
            "quotaErrorStatus": 429,
        }
        restored = HttpChaosProfile.from_dict(result)
        assert restored.quota_name == "acct"
        assert restored.quota_limit == 4
        assert restored.quota_window_millis == 60000
        assert restored.quota_error_status == 429

    def test_degradation_ramp_round_trip(self):
        chaos = HttpChaosProfile(error_probability=1.0, degradation_ramp_millis=30000)
        result = chaos.to_dict()
        assert result == {"errorProbability": 1.0, "degradationRampMillis": 30000}
        restored = HttpChaosProfile.from_dict(result)
        assert restored.degradation_ramp_millis == 30000

    def test_construction_all_fields(self):
        chaos = HttpChaosProfile(
            error_status=503,
            error_probability=0.5,
            retry_after="30",
            latency=Delay(time_unit="MILLISECONDS", value=200),
            seed=42,
            succeed_first=3,
            fail_request_count=5,
            truncate_body_at_fraction=0.25,
            malformed_body=True,
        )
        assert chaos.error_status == 503
        assert chaos.error_probability == 0.5
        assert chaos.retry_after == "30"
        assert chaos.latency.time_unit == "MILLISECONDS"
        assert chaos.latency.value == 200
        assert chaos.seed == 42
        assert chaos.succeed_first == 3
        assert chaos.fail_request_count == 5
        assert chaos.truncate_body_at_fraction == 0.25
        assert chaos.malformed_body is True

    def test_to_dict_all_fields(self):
        chaos = HttpChaosProfile(
            error_status=429,
            error_probability=0.75,
            retry_after="60",
            latency=Delay(time_unit="SECONDS", value=2),
            seed=99,
            succeed_first=5,
            fail_request_count=10,
            truncate_body_at_fraction=0.5,
            malformed_body=True,
        )
        result = chaos.to_dict()
        assert result == {
            "errorStatus": 429,
            "errorProbability": 0.75,
            "retryAfter": "60",
            "latency": {"timeUnit": "SECONDS", "value": 2},
            "seed": 99,
            "succeedFirst": 5,
            "failRequestCount": 10,
            "truncateBodyAtFraction": 0.5,
            "malformedBody": True,
        }

    def test_to_dict_strips_none(self):
        chaos = HttpChaosProfile(error_status=500)
        result = chaos.to_dict()
        assert result == {"errorStatus": 500}
        assert "errorProbability" not in result
        assert "dropConnectionProbability" not in result
        assert "retryAfter" not in result
        assert "latency" not in result
        assert "seed" not in result
        assert "succeedFirst" not in result
        assert "failRequestCount" not in result

    def test_from_dict_all_fields(self):
        chaos = HttpChaosProfile.from_dict({
            "errorStatus": 503,
            "errorProbability": 0.5,
            "retryAfter": "30",
            "latency": {"timeUnit": "MILLISECONDS", "value": 200},
            "seed": 42,
            "succeedFirst": 3,
            "failRequestCount": 5,
        })
        assert chaos.error_status == 503
        assert chaos.error_probability == 0.5
        assert chaos.retry_after == "30"
        assert chaos.latency.time_unit == "MILLISECONDS"
        assert chaos.latency.value == 200
        assert chaos.seed == 42
        assert chaos.succeed_first == 3
        assert chaos.fail_request_count == 5

    def test_from_dict_none(self):
        assert HttpChaosProfile.from_dict(None) is None

    def test_from_dict_partial(self):
        chaos = HttpChaosProfile.from_dict({"errorStatus": 500, "errorProbability": 1.0})
        assert chaos.error_status == 500
        assert chaos.error_probability == 1.0
        assert chaos.drop_connection_probability is None
        assert chaos.retry_after is None
        assert chaos.latency is None
        assert chaos.seed is None
        assert chaos.succeed_first is None
        assert chaos.fail_request_count is None

    def test_round_trip(self):
        original = HttpChaosProfile(
            error_status=503,
            error_probability=0.5,
            retry_after="30",
            latency=Delay(time_unit="MILLISECONDS", value=200),
            seed=42,
            succeed_first=3,
            fail_request_count=5,
        )
        restored = HttpChaosProfile.from_dict(original.to_dict())
        assert restored.error_status == original.error_status
        assert restored.error_probability == original.error_probability
        assert restored.retry_after == original.retry_after
        assert restored.latency.time_unit == original.latency.time_unit
        assert restored.latency.value == original.latency.value
        assert restored.seed == original.seed
        assert restored.succeed_first == original.succeed_first
        assert restored.fail_request_count == original.fail_request_count
        assert restored.to_dict() == original.to_dict()

    def test_drop_connection_probability(self):
        chaos = HttpChaosProfile(drop_connection_probability=0.75)
        result = chaos.to_dict()
        assert result == {"dropConnectionProbability": 0.75}

        restored = HttpChaosProfile.from_dict(result)
        assert restored.drop_connection_probability == 0.75

    def test_outage_window(self):
        chaos = HttpChaosProfile(outage_after_millis=5000, outage_duration_millis=10000)
        result = chaos.to_dict()
        assert result == {"outageAfterMillis": 5000, "outageDurationMillis": 10000}

        restored = HttpChaosProfile.from_dict(result)
        assert restored.outage_after_millis == 5000
        assert restored.outage_duration_millis == 10000


class TestGrpcStreamMessage:
    def test_defaults(self):
        msg = GrpcStreamMessage()
        assert msg.json is None
        assert msg.delay is None

    def test_construction(self):
        msg = GrpcStreamMessage(json='{"id": 1}', delay=Delay(time_unit="SECONDS", value=1))
        assert msg.json == '{"id": 1}'
        assert msg.delay.time_unit == "SECONDS"

    def test_to_dict(self):
        msg = GrpcStreamMessage(json='{"hello": "world"}')
        assert msg.to_dict() == {"json": '{"hello": "world"}'}

    def test_to_dict_with_delay(self):
        msg = GrpcStreamMessage(json='{"id": 1}', delay=Delay(time_unit="MILLISECONDS", value=500))
        result = msg.to_dict()
        assert result == {
            "json": '{"id": 1}',
            "delay": {"timeUnit": "MILLISECONDS", "value": 500},
        }

    def test_to_dict_empty(self):
        msg = GrpcStreamMessage()
        assert msg.to_dict() == {}

    def test_from_dict(self):
        msg = GrpcStreamMessage.from_dict({"json": '{"id": 2}', "delay": {"timeUnit": "SECONDS", "value": 1}})
        assert msg.json == '{"id": 2}'
        assert msg.delay.time_unit == "SECONDS"
        assert msg.delay.value == 1

    def test_from_dict_none(self):
        assert GrpcStreamMessage.from_dict(None) is None

    def test_round_trip(self):
        original = GrpcStreamMessage(json='{"test": true}', delay=Delay(time_unit="SECONDS", value=2))
        restored = GrpcStreamMessage.from_dict(original.to_dict())
        assert restored.to_dict() == original.to_dict()


class TestGrpcStreamResponse:
    def test_defaults(self):
        resp = GrpcStreamResponse()
        assert resp.status_name is None
        assert resp.status_message is None
        assert resp.headers is None
        assert resp.messages is None
        assert resp.close_connection is None
        assert resp.delay is None
        assert resp.primary is None

    def test_construction(self):
        resp = GrpcStreamResponse(
            status_name="OK",
            status_message="Success",
            messages=[GrpcStreamMessage(json='{"id": 1}')],
            close_connection=True,
        )
        assert resp.status_name == "OK"
        assert resp.status_message == "Success"
        assert len(resp.messages) == 1
        assert resp.close_connection is True

    def test_to_dict(self):
        resp = GrpcStreamResponse(
            status_name="OK",
            messages=[
                GrpcStreamMessage(json='{"id": 1}'),
                GrpcStreamMessage(json='{"id": 2}', delay=Delay(time_unit="MILLISECONDS", value=100)),
            ],
            close_connection=True,
        )
        result = resp.to_dict()
        assert result["statusName"] == "OK"
        assert result["closeConnection"] is True
        assert len(result["messages"]) == 2
        assert result["messages"][0] == {"json": '{"id": 1}'}
        assert result["messages"][1] == {
            "json": '{"id": 2}',
            "delay": {"timeUnit": "MILLISECONDS", "value": 100},
        }

    def test_to_dict_empty(self):
        resp = GrpcStreamResponse()
        assert resp.to_dict() == {}

    def test_from_dict(self):
        resp = GrpcStreamResponse.from_dict({
            "statusName": "OK",
            "statusMessage": "Done",
            "messages": [
                {"json": '{"id": 1}'},
                {"json": '{"id": 2}', "delay": {"timeUnit": "SECONDS", "value": 1}},
            ],
            "closeConnection": True,
        })
        assert resp.status_name == "OK"
        assert resp.status_message == "Done"
        assert len(resp.messages) == 2
        assert resp.messages[1].delay.time_unit == "SECONDS"
        assert resp.close_connection is True

    def test_from_dict_none(self):
        assert GrpcStreamResponse.from_dict(None) is None

    def test_round_trip(self):
        original = GrpcStreamResponse(
            status_name="OK",
            status_message="Completed",
            messages=[
                GrpcStreamMessage(json='{"result": "a"}'),
                GrpcStreamMessage(json='{"result": "b"}', delay=Delay(time_unit="MILLISECONDS", value=200)),
            ],
            close_connection=False,
            delay=Delay(time_unit="SECONDS", value=1),
        )
        restored = GrpcStreamResponse.from_dict(original.to_dict())
        assert restored.to_dict() == original.to_dict()

    def test_expectation_to_dict_key(self):
        """Verify the expectation serialises under the 'grpcStreamResponse' key."""
        e = Expectation(
            http_request=HttpRequest(path="/grpc"),
            grpc_stream_response=GrpcStreamResponse(
                status_name="OK",
                messages=[GrpcStreamMessage(json='{"id": 1}')],
            ),
        )
        result = e.to_dict()
        assert "grpcStreamResponse" in result
        assert result["grpcStreamResponse"]["statusName"] == "OK"
        assert "httpResponse" not in result

    def test_expectation_from_dict_key(self):
        """Verify the expectation deserialises from 'grpcStreamResponse' key."""
        e = Expectation.from_dict({
            "httpRequest": {"path": "/grpc"},
            "grpcStreamResponse": {
                "statusName": "OK",
                "messages": [{"json": '{"id": 1}'}],
            },
        })
        assert e.grpc_stream_response is not None
        assert e.grpc_stream_response.status_name == "OK"
        assert len(e.grpc_stream_response.messages) == 1


class TestGrpcBidiRule:
    def test_defaults(self):
        rule = GrpcBidiRule()
        assert rule.match_json is None
        assert rule.responses is None

    def test_round_trip(self):
        rule = GrpcBidiRule(
            match_json='{"name": "Alice"}',
            responses=[GrpcStreamMessage(json='{"greeting": "Hello Alice"}')],
        )
        d = rule.to_dict()
        assert d["matchJson"] == '{"name": "Alice"}'
        assert len(d["responses"]) == 1
        restored = GrpcBidiRule.from_dict(d)
        assert restored.to_dict() == d

    def test_from_dict_none(self):
        assert GrpcBidiRule.from_dict(None) is None


class TestGrpcBidiResponse:
    def test_defaults(self):
        resp = GrpcBidiResponse()
        assert resp.status_name is None
        assert resp.status_message is None
        assert resp.headers is None
        assert resp.messages is None
        assert resp.rules is None
        assert resp.close_connection is None
        assert resp.primary is None

    def test_construction_and_round_trip(self):
        original = GrpcBidiResponse(
            status_name="OK",
            messages=[GrpcStreamMessage(json='{"greeting": "Hello"}')],
            rules=[
                GrpcBidiRule(
                    match_json='{"name": "Bob"}',
                    responses=[GrpcStreamMessage(json='{"greeting": "Hello Bob"}')],
                ),
            ],
            close_connection=False,
        )
        d = original.to_dict()
        assert d["statusName"] == "OK"
        assert len(d["messages"]) == 1
        assert len(d["rules"]) == 1
        assert d["rules"][0]["matchJson"] == '{"name": "Bob"}'
        restored = GrpcBidiResponse.from_dict(d)
        assert restored.to_dict() == d

    def test_from_dict_none(self):
        assert GrpcBidiResponse.from_dict(None) is None

    def test_expectation_to_dict_key(self):
        """Verify the expectation serialises under the 'grpcBidiResponse' key."""
        e = Expectation(
            http_request=HttpRequest(path="/grpc"),
            grpc_bidi_response=GrpcBidiResponse(
                status_name="OK",
                messages=[GrpcStreamMessage(json='{"id": 1}')],
            ),
        )
        result = e.to_dict()
        assert "grpcBidiResponse" in result
        assert result["grpcBidiResponse"]["statusName"] == "OK"
        assert "httpResponse" not in result

    def test_expectation_from_dict_key(self):
        """Verify the expectation deserialises from 'grpcBidiResponse' key."""
        e = Expectation.from_dict({
            "httpRequest": {"path": "/grpc"},
            "grpcBidiResponse": {
                "statusName": "OK",
                "messages": [{"json": '{"id": 1}'}],
                "rules": [
                    {"matchJson": '{"name": "test"}', "responses": [{"json": '{"reply": "ok"}'}]},
                ],
            },
        })
        assert e.grpc_bidi_response is not None
        assert e.grpc_bidi_response.status_name == "OK"
        assert len(e.grpc_bidi_response.messages) == 1
        assert len(e.grpc_bidi_response.rules) == 1
        assert e.grpc_bidi_response.rules[0].match_json == '{"name": "test"}'


class TestBinaryResponse:
    def test_defaults(self):
        resp = BinaryResponse()
        assert resp.binary_data is None
        assert resp.delay is None
        assert resp.primary is None

    def test_construction(self):
        resp = BinaryResponse(binary_data="AQID")
        assert resp.binary_data == "AQID"

    def test_to_dict(self):
        resp = BinaryResponse(binary_data="AQID")
        assert resp.to_dict() == {"binaryData": "AQID"}

    def test_to_dict_with_delay(self):
        resp = BinaryResponse(
            binary_data="AQID",
            delay=Delay(time_unit="MILLISECONDS", value=100),
        )
        result = resp.to_dict()
        assert result == {
            "binaryData": "AQID",
            "delay": {"timeUnit": "MILLISECONDS", "value": 100},
        }

    def test_to_dict_empty(self):
        resp = BinaryResponse()
        assert resp.to_dict() == {}

    def test_from_dict(self):
        resp = BinaryResponse.from_dict({"binaryData": "dGVzdA=="})
        assert resp.binary_data == "dGVzdA=="

    def test_from_dict_none(self):
        assert BinaryResponse.from_dict(None) is None

    def test_upstream_round_trip(self):
        data = {"binaryData": "VXBzdHJlYW0=", "upstream": "FORWARD_AND_REPLACE"}
        resp = BinaryResponse.from_dict(data)
        assert resp.upstream == BinaryUpstream.FORWARD_AND_REPLACE
        assert resp.to_dict() == data

    def test_upstream_constants_match_server_names(self):
        assert BinaryUpstream.ANSWER_ONLY == "ANSWER_ONLY"
        assert BinaryUpstream.ANSWER_AND_FORWARD == "ANSWER_AND_FORWARD"
        assert BinaryUpstream.FORWARD_AND_REPLACE == "FORWARD_AND_REPLACE"

    def test_upstream_omitted_when_unset(self):
        assert "upstream" not in BinaryResponse(binary_data="AQID").to_dict()

    def test_round_trip(self):
        original = BinaryResponse(
            binary_data="SGVsbG8=",
            delay=Delay(time_unit="SECONDS", value=1),
        )
        restored = BinaryResponse.from_dict(original.to_dict())
        assert restored.to_dict() == original.to_dict()

    def test_expectation_to_dict_key(self):
        """Verify the expectation serialises under the 'binaryResponse' key."""
        e = Expectation(
            http_request=HttpRequest(path="/bin"),
            binary_response=BinaryResponse(binary_data="AQID"),
        )
        result = e.to_dict()
        assert "binaryResponse" in result
        assert result["binaryResponse"]["binaryData"] == "AQID"
        assert "httpResponse" not in result

    def test_expectation_from_dict_key(self):
        """Verify the expectation deserialises from 'binaryResponse' key."""
        e = Expectation.from_dict({
            "httpRequest": {"path": "/bin"},
            "binaryResponse": {"binaryData": "AQID"},
        })
        assert e.binary_response is not None
        assert e.binary_response.binary_data == "AQID"


class TestDnsRecord:
    def test_defaults(self):
        rec = DnsRecord()
        assert rec.name is None
        assert rec.type is None
        assert rec.dns_class is None
        assert rec.ttl is None
        assert rec.value is None
        assert rec.priority is None
        assert rec.weight is None
        assert rec.port is None

    def test_a_record_factory(self):
        rec = DnsRecord.a_record("example.com", "1.2.3.4")
        assert rec.name == "example.com"
        assert rec.type == "A"
        assert rec.value == "1.2.3.4"

    def test_aaaa_record_factory(self):
        rec = DnsRecord.aaaa_record("example.com", "::1")
        assert rec.type == "AAAA"

    def test_cname_record_factory(self):
        rec = DnsRecord.cname_record("www.example.com", "example.com")
        assert rec.type == "CNAME"
        assert rec.value == "example.com"

    def test_mx_record_factory(self):
        rec = DnsRecord.mx_record("example.com", 10, "mail.example.com")
        assert rec.type == "MX"
        assert rec.priority == 10
        assert rec.value == "mail.example.com"

    def test_srv_record_factory(self):
        rec = DnsRecord.srv_record("_sip._tcp.example.com", 10, 20, 5060, "sip.example.com")
        assert rec.type == "SRV"
        assert rec.priority == 10
        assert rec.weight == 20
        assert rec.port == 5060
        assert rec.value == "sip.example.com"

    def test_txt_record_factory(self):
        rec = DnsRecord.txt_record("example.com", "v=spf1 include:_spf.google.com ~all")
        assert rec.type == "TXT"

    def test_ptr_record_factory(self):
        rec = DnsRecord.ptr_record("4.3.2.1.in-addr.arpa", "host.example.com")
        assert rec.type == "PTR"

    def test_to_dict(self):
        rec = DnsRecord(name="example.com", type="A", value="1.2.3.4", ttl=300, dns_class="IN")
        result = rec.to_dict()
        assert result == {
            "name": "example.com",
            "type": "A",
            "dnsClass": "IN",
            "ttl": 300,
            "value": "1.2.3.4",
        }

    def test_to_dict_empty(self):
        rec = DnsRecord()
        assert rec.to_dict() == {}

    def test_from_dict(self):
        rec = DnsRecord.from_dict({
            "name": "example.com",
            "type": "A",
            "dnsClass": "IN",
            "ttl": 300,
            "value": "1.2.3.4",
        })
        assert rec.name == "example.com"
        assert rec.type == "A"
        assert rec.dns_class == "IN"
        assert rec.ttl == 300
        assert rec.value == "1.2.3.4"

    def test_from_dict_none(self):
        assert DnsRecord.from_dict(None) is None

    def test_round_trip(self):
        original = DnsRecord(name="example.com", type="MX", priority=10, value="mail.example.com", ttl=600)
        restored = DnsRecord.from_dict(original.to_dict())
        assert restored.to_dict() == original.to_dict()


class TestDnsResponse:
    def test_defaults(self):
        resp = DnsResponse()
        assert resp.response_code is None
        assert resp.answer_records is None
        assert resp.authority_records is None
        assert resp.additional_records is None
        assert resp.delay is None
        assert resp.primary is None

    def test_construction(self):
        resp = DnsResponse(
            response_code="NOERROR",
            answer_records=[DnsRecord.a_record("example.com", "1.2.3.4")],
        )
        assert resp.response_code == "NOERROR"
        assert len(resp.answer_records) == 1

    def test_to_dict(self):
        resp = DnsResponse(
            response_code="NOERROR",
            answer_records=[
                DnsRecord(name="example.com", type="A", value="1.2.3.4"),
            ],
            authority_records=[
                DnsRecord(name="example.com", type="CNAME", value="other.com"),
            ],
        )
        result = resp.to_dict()
        assert result["responseCode"] == "NOERROR"
        assert len(result["answerRecords"]) == 1
        assert result["answerRecords"][0]["type"] == "A"
        assert len(result["authorityRecords"]) == 1

    def test_to_dict_empty(self):
        resp = DnsResponse()
        assert resp.to_dict() == {}

    def test_from_dict(self):
        resp = DnsResponse.from_dict({
            "responseCode": "NXDOMAIN",
            "answerRecords": [
                {"name": "example.com", "type": "A", "value": "1.2.3.4"},
            ],
        })
        assert resp.response_code == "NXDOMAIN"
        assert len(resp.answer_records) == 1
        assert resp.answer_records[0].value == "1.2.3.4"

    def test_from_dict_none(self):
        assert DnsResponse.from_dict(None) is None

    def test_round_trip(self):
        original = DnsResponse(
            response_code="NOERROR",
            answer_records=[DnsRecord.a_record("example.com", "1.2.3.4")],
            authority_records=[DnsRecord.cname_record("www.example.com", "example.com")],
            additional_records=[DnsRecord.txt_record("example.com", "v=spf1")],
            delay=Delay(time_unit="MILLISECONDS", value=50),
        )
        restored = DnsResponse.from_dict(original.to_dict())
        assert restored.to_dict() == original.to_dict()

    def test_expectation_to_dict_key(self):
        """Verify the expectation serialises under the 'dnsResponse' key."""
        e = Expectation(
            http_request=HttpRequest(path="/dns"),
            dns_response=DnsResponse(
                response_code="NOERROR",
                answer_records=[DnsRecord.a_record("example.com", "1.2.3.4")],
            ),
        )
        result = e.to_dict()
        assert "dnsResponse" in result
        assert result["dnsResponse"]["responseCode"] == "NOERROR"
        assert "httpResponse" not in result

    def test_expectation_from_dict_key(self):
        """Verify the expectation deserialises from 'dnsResponse' key."""
        e = Expectation.from_dict({
            "httpRequest": {"path": "/dns"},
            "dnsResponse": {
                "responseCode": "NOERROR",
                "answerRecords": [{"name": "example.com", "type": "A", "value": "1.2.3.4"}],
            },
        })
        assert e.dns_response is not None
        assert e.dns_response.response_code == "NOERROR"
        assert len(e.dns_response.answer_records) == 1


class TestRequestDefinitionAlias:
    def test_is_http_request(self):
        assert RequestDefinition is HttpRequest

    def test_creates_http_request(self):
        r = RequestDefinition(path="/test")
        assert isinstance(r, HttpRequest)
        assert r.path == "/test"

    def test_has_request_factory(self):
        r = RequestDefinition.request("/alias")
        assert isinstance(r, HttpRequest)
        assert r.path == "/alias"


class TestExpectationStep:
    def test_defaults(self):
        from mockserver.models import ExpectationStep
        s = ExpectationStep()
        assert s.http_request is None
        assert s.http_response is None
        assert s.http_forward is None
        assert s.http_error is None
        assert s.http_class_callback is None
        assert s.http_object_callback is None
        assert s.http_override_forwarded_request is None
        assert s.responder is None
        assert s.delay is None
        assert s.blocking is None
        assert s.timeout is None
        assert s.failure_policy is None

    def test_responder_step_with_http_response(self):
        from mockserver.models import ExpectationStep
        s = ExpectationStep(
            http_response=HttpResponse(status_code=200, body="ok"),
            responder=True,
        )
        result = s.to_dict()
        assert result == {
            "httpResponse": {"statusCode": 200, "body": "ok"},
            "responder": True,
        }

    def test_side_effect_step_with_http_request(self):
        from mockserver.models import ExpectationStep
        s = ExpectationStep(
            http_request=HttpRequest(method="POST", path="/webhook"),
            blocking=True,
            timeout=Delay(time_unit="SECONDS", value=5),
            failure_policy="FAIL_FAST",
        )
        result = s.to_dict()
        assert result["httpRequest"] == {"method": "POST", "path": "/webhook"}
        assert result["blocking"] is True
        assert result["timeout"] == {"timeUnit": "SECONDS", "value": 5}
        assert result["failurePolicy"] == "FAIL_FAST"
        assert "responder" not in result

    def test_step_with_http_forward(self):
        from mockserver.models import ExpectationStep
        s = ExpectationStep(
            http_forward=HttpForward(host="example.com", port=8080),
            responder=True,
            delay=Delay(time_unit="MILLISECONDS", value=100),
        )
        result = s.to_dict()
        assert result["httpForward"] == {"host": "example.com", "port": 8080}
        assert result["responder"] is True
        assert result["delay"] == {"timeUnit": "MILLISECONDS", "value": 100}

    def test_step_with_http_class_callback(self):
        from mockserver.models import ExpectationStep
        s = ExpectationStep(
            http_class_callback=HttpClassCallback(callback_class="com.example.Cb"),
        )
        result = s.to_dict()
        assert result["httpClassCallback"] == {"callbackClass": "com.example.Cb"}

    def test_step_with_http_object_callback(self):
        from mockserver.models import ExpectationStep
        s = ExpectationStep(
            http_object_callback=HttpObjectCallback(client_id="ws-1"),
        )
        result = s.to_dict()
        assert result["httpObjectCallback"] == {"clientId": "ws-1"}

    def test_step_with_http_override_forwarded_request(self):
        from mockserver.models import ExpectationStep
        s = ExpectationStep(
            http_override_forwarded_request=HttpOverrideForwardedRequest(
                http_request=HttpRequest(path="/override"),
            ),
            responder=True,
        )
        result = s.to_dict()
        assert result["httpOverrideForwardedRequest"] == {"httpRequest": {"path": "/override"}}
        assert result["responder"] is True

    def test_step_with_http_error(self):
        from mockserver.models import ExpectationStep
        s = ExpectationStep(
            http_error=HttpError(drop_connection=True),
            responder=True,
        )
        result = s.to_dict()
        assert result["httpError"] == {"dropConnection": True}
        assert result["responder"] is True

    def test_to_dict_strips_none(self):
        from mockserver.models import ExpectationStep
        s = ExpectationStep(
            http_response=HttpResponse(status_code=200),
            responder=True,
        )
        result = s.to_dict()
        assert "httpRequest" not in result
        assert "httpForward" not in result
        assert "httpError" not in result
        assert "blocking" not in result
        assert "timeout" not in result
        assert "failurePolicy" not in result
        assert "delay" not in result

    def test_to_dict_failure_policy_best_effort(self):
        from mockserver.models import ExpectationStep
        s = ExpectationStep(
            http_request=HttpRequest(path="/hook"),
            blocking=False,
            failure_policy="BEST_EFFORT",
        )
        result = s.to_dict()
        assert result["failurePolicy"] == "BEST_EFFORT"
        assert result["blocking"] is False

    def test_from_dict(self):
        from mockserver.models import ExpectationStep
        s = ExpectationStep.from_dict({
            "httpResponse": {"statusCode": 200, "body": "ok"},
            "responder": True,
            "delay": {"timeUnit": "SECONDS", "value": 1},
        })
        assert s.http_response.status_code == 200
        assert s.responder is True
        assert s.delay.time_unit == "SECONDS"
        assert s.delay.value == 1

    def test_from_dict_with_side_effect_controls(self):
        from mockserver.models import ExpectationStep
        s = ExpectationStep.from_dict({
            "httpRequest": {"path": "/hook"},
            "blocking": True,
            "timeout": {"timeUnit": "SECONDS", "value": 5},
            "failurePolicy": "FAIL_FAST",
        })
        assert s.http_request.path == "/hook"
        assert s.blocking is True
        assert s.timeout.value == 5
        assert s.failure_policy == "FAIL_FAST"

    def test_from_dict_with_http_forward(self):
        from mockserver.models import ExpectationStep
        s = ExpectationStep.from_dict({
            "httpForward": {"host": "example.com"},
            "responder": True,
        })
        assert s.http_forward.host == "example.com"
        assert s.responder is True

    def test_from_dict_with_callbacks(self):
        from mockserver.models import ExpectationStep
        s = ExpectationStep.from_dict({
            "httpClassCallback": {"callbackClass": "com.example.Cb"},
        })
        assert s.http_class_callback.callback_class == "com.example.Cb"

    def test_from_dict_none(self):
        from mockserver.models import ExpectationStep
        assert ExpectationStep.from_dict(None) is None

    def test_round_trip(self):
        from mockserver.models import ExpectationStep
        original = ExpectationStep(
            http_request=HttpRequest(method="POST", path="/webhook"),
            blocking=True,
            timeout=Delay(time_unit="SECONDS", value=3),
            failure_policy="BEST_EFFORT",
            delay=Delay(time_unit="MILLISECONDS", value=50),
        )
        restored = ExpectationStep.from_dict(original.to_dict())
        assert restored.http_request.method == "POST"
        assert restored.http_request.path == "/webhook"
        assert restored.blocking is True
        assert restored.timeout.value == 3
        assert restored.failure_policy == "BEST_EFFORT"
        assert restored.delay.value == 50

    def test_round_trip_responder(self):
        from mockserver.models import ExpectationStep
        original = ExpectationStep(
            http_response=HttpResponse(status_code=201, body="created"),
            responder=True,
        )
        restored = ExpectationStep.from_dict(original.to_dict())
        assert restored.http_response.status_code == 201
        assert restored.http_response.body == "created"
        assert restored.responder is True


class TestExpectationWithSteps:
    def test_expectation_with_steps_to_dict(self):
        from mockserver.models import ExpectationStep
        e = Expectation(
            http_request=HttpRequest(path="/api"),
            steps=[
                ExpectationStep(
                    http_request=HttpRequest(method="POST", path="/webhook"),
                    blocking=True,
                    timeout=Delay(time_unit="SECONDS", value=5),
                    failure_policy="FAIL_FAST",
                ),
                ExpectationStep(
                    http_response=HttpResponse(status_code=200, body="ok"),
                    responder=True,
                ),
            ],
        )
        result = e.to_dict()
        assert "steps" in result
        assert len(result["steps"]) == 2
        assert result["steps"][0]["httpRequest"] == {"method": "POST", "path": "/webhook"}
        assert result["steps"][0]["blocking"] is True
        assert result["steps"][0]["failurePolicy"] == "FAIL_FAST"
        assert result["steps"][1]["httpResponse"] == {"statusCode": 200, "body": "ok"}
        assert result["steps"][1]["responder"] is True

    def test_expectation_with_steps_from_dict(self):
        from mockserver.models import ExpectationStep
        e = Expectation.from_dict({
            "httpRequest": {"path": "/api"},
            "steps": [
                {
                    "httpRequest": {"method": "POST", "path": "/webhook"},
                    "blocking": True,
                    "timeout": {"timeUnit": "SECONDS", "value": 5},
                    "failurePolicy": "FAIL_FAST",
                },
                {
                    "httpResponse": {"statusCode": 200, "body": "ok"},
                    "responder": True,
                },
            ],
        })
        assert e.steps is not None
        assert len(e.steps) == 2
        assert e.steps[0].http_request.path == "/webhook"
        assert e.steps[0].blocking is True
        assert e.steps[0].failure_policy == "FAIL_FAST"
        assert e.steps[1].http_response.status_code == 200
        assert e.steps[1].responder is True

    def test_expectation_without_steps(self):
        e = Expectation(
            http_request=HttpRequest(path="/test"),
            http_response=HttpResponse(status_code=200),
        )
        result = e.to_dict()
        assert "steps" not in result

    def test_expectation_from_dict_without_steps(self):
        e = Expectation.from_dict({
            "httpRequest": {"path": "/test"},
            "httpResponse": {"statusCode": 200},
        })
        assert e.steps is None

    def test_round_trip_with_steps(self):
        from mockserver.models import ExpectationStep
        original = Expectation(
            id="steps-test",
            http_request=HttpRequest(path="/api"),
            steps=[
                ExpectationStep(
                    http_request=HttpRequest(method="POST", path="/hook"),
                    delay=Delay(time_unit="MILLISECONDS", value=100),
                    blocking=True,
                    timeout=Delay(time_unit="SECONDS", value=3),
                    failure_policy="BEST_EFFORT",
                ),
                ExpectationStep(
                    http_forward=HttpForward(host="backend.local", port=9090),
                    responder=True,
                ),
            ],
        )
        restored = Expectation.from_dict(original.to_dict())
        assert restored.id == "steps-test"
        assert len(restored.steps) == 2
        assert restored.steps[0].http_request.path == "/hook"
        assert restored.steps[0].delay.value == 100
        assert restored.steps[0].blocking is True
        assert restored.steps[0].timeout.value == 3
        assert restored.steps[0].failure_policy == "BEST_EFFORT"
        assert restored.steps[1].http_forward.host == "backend.local"
        assert restored.steps[1].responder is True


class TestHttpTemplateTemplateFile:
    """Tests for the templateFile field on HttpTemplate."""

    def test_template_file_default_is_none(self):
        t = HttpTemplate()
        assert t.template_file is None

    def test_template_file_construction(self):
        t = HttpTemplate(template_type="VELOCITY", template_file="/templates/response.vm")
        assert t.template_file == "/templates/response.vm"
        assert t.template_type == "VELOCITY"

    def test_template_file_to_dict(self):
        t = HttpTemplate(template_type="MUSTACHE", template_file="/tpl/resp.mustache")
        result = t.to_dict()
        assert result["templateFile"] == "/tpl/resp.mustache"
        assert result["templateType"] == "MUSTACHE"

    def test_template_file_not_emitted_when_none(self):
        t = HttpTemplate(template_type="VELOCITY", template="inline content")
        result = t.to_dict()
        assert "templateFile" not in result

    def test_template_file_from_dict(self):
        t = HttpTemplate.from_dict({
            "templateType": "VELOCITY",
            "templateFile": "/path/to/template.vm",
        })
        assert t.template_file == "/path/to/template.vm"
        assert t.template_type == "VELOCITY"

    def test_template_file_round_trip(self):
        original = HttpTemplate(
            template_type="MUSTACHE",
            template_file="/templates/forward.mustache",
            delay=Delay(time_unit="SECONDS", value=1),
        )
        restored = HttpTemplate.from_dict(original.to_dict())
        assert restored.template_type == "MUSTACHE"
        assert restored.template_file == "/templates/forward.mustache"
        assert restored.template is None
        assert restored.delay.value == 1

    def test_template_file_factory(self):
        t = HttpTemplate.template("VELOCITY", template_file="/tpl/fwd.vm")
        assert t.template_type == "VELOCITY"
        assert t.template_file == "/tpl/fwd.vm"
        assert t.template is None

    def test_template_file_factory_with_inline_template(self):
        t = HttpTemplate.template("JAVASCRIPT", "return {};", template_file="/fallback.js")
        assert t.template == "return {};"
        assert t.template_file == "/fallback.js"

    def test_template_file_coexists_with_template(self):
        """Both template and templateFile can be set (server decides precedence)."""
        t = HttpTemplate(
            template_type="VELOCITY",
            template="inline $var",
            template_file="/path/to/file.vm",
        )
        result = t.to_dict()
        assert result["template"] == "inline $var"
        assert result["templateFile"] == "/path/to/file.vm"


class TestBodyFileType:
    """Tests for the FILE body type with filePath and templateType."""

    def test_file_body_construction(self):
        b = Body(type="FILE", file_path="/data/response.json", content_type="application/json")
        assert b.type == "FILE"
        assert b.file_path == "/data/response.json"
        assert b.content_type == "application/json"

    def test_file_body_with_template_type(self):
        b = Body(
            type="FILE",
            file_path="/templates/body.mustache",
            template_type="MUSTACHE",
            content_type="text/html",
        )
        assert b.template_type == "MUSTACHE"
        assert b.file_path == "/templates/body.mustache"

    def test_file_body_to_dict(self):
        b = Body(
            type="FILE",
            file_path="/data/response.json",
            content_type="application/json",
        )
        result = b.to_dict()
        assert result == {
            "type": "FILE",
            "filePath": "/data/response.json",
            "contentType": "application/json",
        }

    def test_file_body_to_dict_with_template_type(self):
        b = Body(
            type="FILE",
            file_path="/tpl/body.vm",
            template_type="VELOCITY",
            content_type="text/plain",
        )
        result = b.to_dict()
        assert result == {
            "type": "FILE",
            "filePath": "/tpl/body.vm",
            "templateType": "VELOCITY",
            "contentType": "text/plain",
        }

    def test_file_body_template_type_not_emitted_when_none(self):
        b = Body(type="FILE", file_path="/data/resp.json")
        result = b.to_dict()
        assert "templateType" not in result

    def test_file_path_not_emitted_for_non_file_body(self):
        b = Body(type="STRING", string="hello")
        result = b.to_dict()
        assert "filePath" not in result
        assert "templateType" not in result

    def test_file_body_from_dict(self):
        b = Body.from_dict({
            "type": "FILE",
            "filePath": "/responses/body.json",
            "contentType": "application/json",
        })
        assert b.type == "FILE"
        assert b.file_path == "/responses/body.json"
        assert b.content_type == "application/json"
        assert b.template_type is None

    def test_file_body_from_dict_with_template_type(self):
        b = Body.from_dict({
            "type": "FILE",
            "filePath": "/tpl/resp.mustache",
            "templateType": "MUSTACHE",
            "contentType": "text/html",
        })
        assert b.type == "FILE"
        assert b.file_path == "/tpl/resp.mustache"
        assert b.template_type == "MUSTACHE"
        assert b.content_type == "text/html"

    def test_file_body_round_trip(self):
        original = Body(
            type="FILE",
            file_path="/templates/response.vm",
            template_type="VELOCITY",
            content_type="application/json",
        )
        restored = Body.from_dict(original.to_dict())
        assert restored.type == "FILE"
        assert restored.file_path == "/templates/response.vm"
        assert restored.template_type == "VELOCITY"
        assert restored.content_type == "application/json"

    def test_file_body_factory(self):
        b = Body.file("/data/response.json")
        assert b.type == "FILE"
        assert b.file_path == "/data/response.json"
        assert b.content_type is None
        assert b.template_type is None

    def test_file_body_factory_with_content_type(self):
        b = Body.file("/data/resp.xml", content_type="application/xml")
        assert b.type == "FILE"
        assert b.file_path == "/data/resp.xml"
        assert b.content_type == "application/xml"

    def test_file_body_factory_with_template_type(self):
        b = Body.file("/tpl/body.mustache", content_type="text/html", template_type="MUSTACHE")
        assert b.type == "FILE"
        assert b.file_path == "/tpl/body.mustache"
        assert b.content_type == "text/html"
        assert b.template_type == "MUSTACHE"

    def test_file_body_factory_round_trip(self):
        original = Body.file("/tpl/resp.vm", content_type="text/plain", template_type="VELOCITY")
        restored = Body.from_dict(original.to_dict())
        assert restored.type == "FILE"
        assert restored.file_path == "/tpl/resp.vm"
        assert restored.content_type == "text/plain"
        assert restored.template_type == "VELOCITY"

    def test_file_body_templated_file_path_round_trip(self):
        original = Body.file(
            "responses/{{ request.pathParameters.name.0 }}.json",
            content_type="application/json",
            template_type="MUSTACHE",
            templated_file_path=True,
        )
        assert original.to_dict() == {
            "type": "FILE",
            "contentType": "application/json",
            "filePath": "responses/{{ request.pathParameters.name.0 }}.json",
            "templateType": "MUSTACHE",
            "templatedFilePath": True,
        }
        restored = Body.from_dict(original.to_dict())
        assert restored.templated_file_path is True
        assert restored.to_dict() == original.to_dict()

    def test_file_body_templated_file_path_omitted_when_unset_or_false(self):
        for flag in (None, False):
            b = Body.file("/tpl/resp.vm", template_type="VELOCITY", templated_file_path=flag)
            assert "templatedFilePath" not in b.to_dict()

    def test_file_body_deserialized_from_body_in_request(self):
        """FILE bodies in HTTP request/response are correctly deserialized."""
        from mockserver.models import _deserialize_body
        data = {
            "type": "FILE",
            "filePath": "/mock-data/payload.json",
            "templateType": "MUSTACHE",
            "contentType": "application/json",
        }
        body = _deserialize_body(data)
        assert isinstance(body, Body)
        assert body.type == "FILE"
        assert body.file_path == "/mock-data/payload.json"
        assert body.template_type == "MUSTACHE"
        assert body.content_type == "application/json"

    def test_field_map_entries(self):
        """Ensure the _FIELD_MAP has entries for the new fields."""
        from mockserver.models import _to_camel, _from_camel
        assert _to_camel("template_file") == "templateFile"
        assert _to_camel("file_path") == "filePath"
        assert _from_camel("templateFile") == "template_file"
        assert _from_camel("filePath") == "file_path"


class TestCookieSerialization:
    # MockServer represents cookies as a {name: value} object map, not the
    # [{name, values}] array used for headers / query parameters.
    def test_request_cookie_serialized_as_object_map(self):
        req = HttpRequest().with_path("/c").with_cookie("session", "abc123")
        assert req.to_dict()["cookies"] == {"session": "abc123"}

    def test_response_cookie_serialized_as_object_map(self):
        resp = HttpResponse().with_cookie("set", "v1")
        assert resp.to_dict()["cookies"] == {"set": "v1"}

    def test_request_cookie_round_trip_from_object_map(self):
        req = HttpRequest.from_dict({"path": "/c", "cookies": {"session": "abc123"}})
        assert req.cookies[0].name == "session"
        assert req.cookies[0].values == ["abc123"]


class TestJwt:
    def test_full_matcher_serialisation(self):
        req = (
            HttpRequest.request("/secure")
            .with_method("GET")
            .with_jwt(
                claims={
                    "sub": "user-123",
                    "role": "!admin",
                    "email": "^.+@example.com$",
                },
                issuer="https://issuer.example.com",
                audience="my-api",
                algorithm="RS256",
            )
        )
        assert req.to_dict() == {
            "method": "GET",
            "path": "/secure",
            "jwt": {
                "claims": {
                    "sub": "user-123",
                    "role": "!admin",
                    "email": "^.+@example.com$",
                },
                "issuer": "https://issuer.example.com",
                "audience": "my-api",
                "algorithm": "RS256",
            },
        }

    def test_json_matches_exact_wire_shape(self):
        req = HttpRequest.request("/secure").with_jwt(
            Jwt(
                claims={"sub": "user-123", "role": "!admin", "email": "^.+@example.com$"},
                issuer="https://issuer.example.com",
                audience="my-api",
                algorithm="RS256",
                header="authorization",
                scheme="Bearer",
            )
        )
        assert json.dumps(req.to_dict()["jwt"], sort_keys=True) == json.dumps(
            {
                "claims": {
                    "sub": "user-123",
                    "role": "!admin",
                    "email": "^.+@example.com$",
                },
                "issuer": "https://issuer.example.com",
                "audience": "my-api",
                "algorithm": "RS256",
                "header": "authorization",
                "scheme": "Bearer",
            },
            sort_keys=True,
        )

    def test_omits_unset_optionals(self):
        jwt = Jwt(claims={"sub": "abc"})
        assert jwt.to_dict() == {"claims": {"sub": "abc"}}

    def test_round_trip(self):
        original = {
            "path": "/secure",
            "jwt": {
                "claims": {"sub": "user-123", "role": "!admin"},
                "issuer": "https://issuer.example.com",
                "audience": "my-api",
                "algorithm": "RS256",
            },
        }
        req = HttpRequest.from_dict(original)
        assert isinstance(req.jwt, Jwt)
        assert req.jwt.claims == {"sub": "user-123", "role": "!admin"}
        assert req.jwt.issuer == "https://issuer.example.com"
        assert req.to_dict() == original


class TestAllOfBody:
    def test_serialisation_json_path_and_regex(self):
        body = AllOfBody(
            body_all_of=[
                JsonPathBody(json_path="$.name"),
                RegexBody(regex=".*active.*"),
            ]
        )
        assert body.to_dict() == {
            "type": "ALL_OF",
            "bodyAllOf": [
                {"type": "JSON_PATH", "jsonPath": "$.name"},
                {"type": "REGEX", "regex": ".*active.*"},
            ],
        }

    def test_json_matches_exact_wire_shape(self):
        body = Body.all_of(
            JsonPathBody(json_path="$.name"),
            RegexBody(regex=".*active.*"),
        )
        assert json.dumps(body.to_dict()) == (
            '{"type": "ALL_OF", "bodyAllOf": ['
            '{"type": "JSON_PATH", "jsonPath": "$.name"}, '
            '{"type": "REGEX", "regex": ".*active.*"}]}'
        )

    def test_on_request_body(self):
        req = HttpRequest.request("/api").with_body(
            AllOfBody(
                body_all_of=[
                    JsonPathBody(json_path="$.name"),
                    RegexBody(regex=".*active.*"),
                ]
            )
        )
        assert req.to_dict()["body"] == {
            "type": "ALL_OF",
            "bodyAllOf": [
                {"type": "JSON_PATH", "jsonPath": "$.name"},
                {"type": "REGEX", "regex": ".*active.*"},
            ],
        }

    def test_accepts_raw_dict_sub_bodies(self):
        body = AllOfBody(body_all_of=[{"type": "REGEX", "regex": "x"}])
        assert body.to_dict() == {
            "type": "ALL_OF",
            "bodyAllOf": [{"type": "REGEX", "regex": "x"}],
        }

    def test_round_trip(self):
        data = {
            "type": "ALL_OF",
            "bodyAllOf": [
                {"type": "JSON_PATH", "jsonPath": "$.name"},
                {"type": "REGEX", "regex": ".*active.*"},
            ],
        }
        body = AllOfBody.from_dict(data)
        assert isinstance(body, AllOfBody)
        assert body.to_dict() == data


# ---------------------------------------------------------------------------
# Round-trip coverage for previously-dropped Expectation features.
#
# Each test drives the strongest round-trip: from_dict(wire) -> to_dict() == wire,
# proving the whitelist deserializer no longer silently drops the field. Dict
# equality in Python is order-insensitive, so key order is irrelevant.
# ---------------------------------------------------------------------------


class TestBodySubFieldsRoundTrip:
    def test_string_sub_string(self):
        data = {"type": "STRING", "string": "frag", "subString": True}
        assert Body.from_dict(data).to_dict() == data

    def test_json_match_type(self):
        data = {"type": "JSON", "json": {"a": 1}, "matchType": "ONLY_MATCHING_FIELDS"}
        assert Body.from_dict(data).to_dict() == data

    def test_json_match_numbers_as_strings(self):
        data = {"type": "JSON", "json": {"a": 1}, "matchNumbersAsStrings": True}
        assert Body.from_dict(data).to_dict() == data

    def test_body_optional_flag(self):
        data = {"type": "STRING", "string": "x", "optional": True}
        assert Body.from_dict(data).to_dict() == data

    def test_not_and_sub_string_together(self):
        data = {"not": True, "type": "STRING", "string": "x", "subString": True}
        assert Body.from_dict(data).to_dict() == data

    def test_sub_string_absent_when_unset(self):
        # subString / matchType must be omitted (not emitted as null) when unset.
        assert Body(type="STRING", string="x").to_dict() == {"type": "STRING", "string": "x"}


class TestXmlBodyRoundTrip:
    def test_minimal(self):
        data = {"type": "XML", "xml": "<root/>"}
        body = XmlBody.from_dict(data)
        assert isinstance(body, XmlBody)
        assert body.to_dict() == data

    def test_with_content_type_and_flags(self):
        data = {"not": True, "optional": True, "type": "XML", "xml": "<a/>", "contentType": "application/xml"}
        assert XmlBody.from_dict(data).to_dict() == data

    def test_deserialized_via_body_dispatch(self):
        # A wire XML body carrying the "xml" key must resolve to XmlBody, not the
        # legacy generic Body (which keys off "string").
        body = _deserialize_body({"type": "XML", "xml": "<a/>"})
        assert isinstance(body, XmlBody)

    def test_legacy_string_form_still_generic_body(self):
        body = _deserialize_body({"type": "XML", "string": "<a/>"})
        assert isinstance(body, Body)
        assert body.type == "XML"

    def test_factory(self):
        assert Body.xml_matcher("<a/>").to_dict() == {"type": "XML", "xml": "<a/>"}


class TestXPathBodyRoundTrip:
    def test_minimal(self):
        data = {"type": "XPATH", "xpath": "/root/child"}
        assert XPathBody.from_dict(data).to_dict() == data

    def test_with_namespace_prefixes(self):
        data = {
            "not": True,
            "type": "XPATH",
            "xpath": "//ns:name",
            "namespacePrefixes": {"ns": "http://example.com/ns"},
        }
        assert XPathBody.from_dict(data).to_dict() == data

    def test_dispatch(self):
        assert isinstance(_deserialize_body({"type": "XPATH", "xpath": "/a"}), XPathBody)


class TestXmlSchemaBodyRoundTrip:
    def test_round_trip(self):
        data = {"type": "XML_SCHEMA", "xmlSchema": "<xs:schema/>"}
        assert XmlSchemaBody.from_dict(data).to_dict() == data

    def test_dispatch(self):
        assert isinstance(_deserialize_body({"type": "XML_SCHEMA", "xmlSchema": "s"}), XmlSchemaBody)


class TestJsonSchemaBodyRoundTrip:
    def test_round_trip_object_schema(self):
        data = {
            "type": "JSON_SCHEMA",
            "jsonSchema": {"type": "object", "properties": {"id": {"type": "integer"}}},
        }
        assert JsonSchemaBody.from_dict(data).to_dict() == data

    def test_with_parameter_styles_and_flags(self):
        data = {
            "optional": True,
            "type": "JSON_SCHEMA",
            "jsonSchema": {"type": "string"},
            "parameterStyles": {"id": "SIMPLE"},
        }
        assert JsonSchemaBody.from_dict(data).to_dict() == data

    def test_dispatch(self):
        body = _deserialize_body({"type": "JSON_SCHEMA", "jsonSchema": {"type": "object"}})
        assert isinstance(body, JsonSchemaBody)


class TestParameterBodyRoundTrip:
    def test_round_trip(self):
        data = {
            "type": "PARAMETERS",
            "parameters": [{"name": "q", "values": ["a", "b"]}],
        }
        assert ParameterBody.from_dict(data).to_dict() == data

    def test_with_flags(self):
        data = {
            "not": True,
            "optional": True,
            "type": "PARAMETERS",
            "parameters": [{"name": "k", "values": ["v"]}],
        }
        assert ParameterBody.from_dict(data).to_dict() == data

    def test_dispatch(self):
        body = _deserialize_body({"type": "PARAMETERS", "parameters": [{"name": "k", "values": ["v"]}]})
        assert isinstance(body, ParameterBody)


class TestMultipartBodyRoundTrip:
    def test_round_trip_all_parts_object_map(self):
        # Canonical server wire form: {name: [values]} object maps, NOT arrays.
        data = {
            "type": "MULTIPART",
            "fields": {"field1": ["v1"]},
            "filenames": {"file1": ["a.txt"]},
            "partContentTypes": {"file1": ["text/plain"]},
        }
        assert MultipartBody.from_dict(data).to_dict() == data

    def test_partial_object_map(self):
        data = {"type": "MULTIPART", "fields": {"f": ["v"]}}
        assert MultipartBody.from_dict(data).to_dict() == data

    def test_emits_object_map_not_array(self):
        # Wire-shape assertion: a client-built MultipartBody MUST emit the object-map
        # form so the server's multipart deserializer reads it (an array-valued
        # "fields" is diverted to the GraphQL handler and silently dropped).
        body = MultipartBody(
            fields=[KeyToMultiValue(name="f", values=["v"])],
            filenames=[KeyToMultiValue(name="file", values=["a.txt"])],
            part_content_types=[KeyToMultiValue(name="file", values=["text/plain"])],
        )
        assert body.to_dict() == {
            "type": "MULTIPART",
            "fields": {"f": ["v"]},
            "filenames": {"file": ["a.txt"]},
            "partContentTypes": {"file": ["text/plain"]},
        }

    def test_server_form_round_trip(self):
        # from_dict(object-map wire) -> to_dict() reproduces the same object-map wire.
        wire = {"type": "MULTIPART", "fields": {"a": ["1", "2"], "b": ["3"]}}
        assert MultipartBody.from_dict(wire).to_dict() == wire

    def test_accepts_legacy_array_form_on_read(self):
        # from_dict tolerates the legacy array form but normalises output to the map.
        body = MultipartBody.from_dict(
            {"type": "MULTIPART", "fields": [{"name": "f", "values": ["v"]}]}
        )
        assert body.to_dict() == {"type": "MULTIPART", "fields": {"f": ["v"]}}

    def test_dispatch(self):
        assert isinstance(_deserialize_body({"type": "MULTIPART", "fields": {}}), MultipartBody)


class TestWasmBodyRoundTrip:
    def test_round_trip(self):
        data = {"type": "WASM", "moduleName": "my-rule"}
        assert WasmBody.from_dict(data).to_dict() == data

    def test_with_flags(self):
        data = {"not": True, "optional": True, "type": "WASM", "moduleName": "r"}
        assert WasmBody.from_dict(data).to_dict() == data

    def test_dispatch(self):
        assert isinstance(_deserialize_body({"type": "WASM", "moduleName": "r"}), WasmBody)

    def test_factory(self):
        assert Body.wasm("r").to_dict() == {"type": "WASM", "moduleName": "r"}


class TestHttpRequestNotAndProtocol:
    def test_not_flag_round_trip(self):
        data = {"not": True, "path": "/api"}
        assert HttpRequest.from_dict(data).to_dict() == data

    def test_protocol_round_trip(self):
        data = {"path": "/api", "protocol": Protocol.HTTP_2}
        assert HttpRequest.from_dict(data).to_dict() == data

    def test_not_and_protocol_together(self):
        data = {"not": True, "method": "GET", "path": "/x", "protocol": "HTTP_3"}
        assert HttpRequest.from_dict(data).to_dict() == data


class TestDnsRequestDefinitionRoundTrip:
    def test_round_trip(self):
        data = {"dnsName": "example.com", "dnsType": DnsRecordType.A, "dnsClass": DnsRecordClass.IN}
        assert DnsRequestDefinition.from_dict(data).to_dict() == data

    def test_minimal(self):
        data = {"dnsName": "example.com"}
        assert DnsRequestDefinition.from_dict(data).to_dict() == data

    def test_factory(self):
        rd = DnsRequestDefinition.dns_request("example.com", DnsRecordType.AAAA)
        assert rd.to_dict() == {"dnsName": "example.com", "dnsType": "AAAA"}

    def test_expectation_dispatches_dns_request(self):
        # An expectation whose httpRequest carries dnsName must deserialize into a
        # DnsRequestDefinition, not an HttpRequest, and survive a round-trip.
        data = {
            "httpRequest": {"dnsName": "example.com", "dnsType": "A"},
            "dnsResponse": {"responseCode": "NO_ERROR"},
        }
        exp = Expectation.from_dict(data)
        assert isinstance(exp.http_request, DnsRequestDefinition)
        assert exp.to_dict() == data

    def test_expectation_still_dispatches_http_request(self):
        data = {"httpRequest": {"path": "/api"}, "httpResponse": {"statusCode": 200}}
        exp = Expectation.from_dict(data)
        assert isinstance(exp.http_request, HttpRequest)
        assert exp.to_dict() == data


class TestRateLimitRoundTrip:
    def test_fixed_window(self):
        data = {
            "name": "shared",
            "algorithm": RateLimitAlgorithm.FIXED_WINDOW,
            "limit": 100,
            "windowMillis": 1000,
            "errorStatus": 429,
            "retryAfter": "1",
        }
        assert RateLimit.from_dict(data).to_dict() == data

    def test_token_bucket(self):
        data = {"algorithm": "token_bucket", "burst": 10, "refillPerSecond": 2.5}
        assert RateLimit.from_dict(data).to_dict() == data


class TestHttpForwardWithFallbackRoundTrip:
    def test_round_trip(self):
        data = {
            "httpForward": {"host": "upstream", "port": 8080},
            "fallbackResponse": {"statusCode": 503},
            "fallbackOnStatusCodes": [500, 502, 503],
            "fallbackOnTimeout": True,
        }
        assert HttpForwardWithFallback.from_dict(data).to_dict() == data

    def test_via_expectation(self):
        data = {
            "httpRequest": {"path": "/x"},
            "httpForwardWithFallback": {
                "httpForward": {"host": "h", "port": 80},
                "fallbackResponse": {"statusCode": 500},
                "fallbackOnTimeout": True,
            },
        }
        exp = Expectation.from_dict(data)
        assert isinstance(exp.http_forward_with_fallback, HttpForwardWithFallback)
        assert exp.to_dict() == data


class TestHttpForwardValidateActionRoundTrip:
    def test_round_trip(self):
        data = {
            "specUrlOrPayload": "https://example.com/openapi.json",
            "host": "upstream",
            "port": 443,
            "scheme": "HTTPS",
            "validateRequest": True,
            "validateResponse": False,
            "validationMode": ValidationMode.STRICT,
        }
        assert HttpForwardValidateAction.from_dict(data).to_dict() == data

    def test_via_expectation(self):
        data = {
            "httpRequest": {"path": "/x"},
            "httpForwardValidateAction": {
                "specUrlOrPayload": "spec",
                "host": "h",
                "validationMode": "LOG_ONLY",
            },
        }
        exp = Expectation.from_dict(data)
        assert isinstance(exp.http_forward_validate_action, HttpForwardValidateAction)
        assert exp.to_dict() == data


class TestCaptureRuleRoundTrip:
    def test_round_trip(self):
        data = {"source": CaptureSource.JSON_PATH, "expression": "$.id", "into": "orderId"}
        assert CaptureRule.from_dict(data).to_dict() == data

    def test_source_constants_are_camel_case(self):
        assert CaptureSource.QUERY_STRING_PARAMETER == "queryStringParameter"
        assert CaptureSource.PATH_PARAMETER == "pathParameter"


class TestExpectationNewFieldsRoundTrip:
    def test_namespace(self):
        data = {"httpRequest": {"path": "/x"}, "httpResponse": {"statusCode": 200}, "namespace": "tenant-a"}
        assert Expectation.from_dict(data).to_dict() == data

    def test_rate_limit_field(self):
        data = {
            "httpRequest": {"path": "/x"},
            "httpResponse": {"statusCode": 200},
            "rateLimit": {"name": "n", "limit": 5, "windowMillis": 1000},
        }
        exp = Expectation.from_dict(data)
        assert isinstance(exp.rate_limit, RateLimit)
        assert exp.to_dict() == data

    def test_capture_field(self):
        data = {
            "httpRequest": {"path": "/x"},
            "httpResponse": {"statusCode": 200},
            "capture": [
                {"source": "jsonPath", "expression": "$.id", "into": "id"},
                {"source": "header", "expression": "X-Trace", "into": "trace"},
            ],
        }
        exp = Expectation.from_dict(data)
        assert all(isinstance(c, CaptureRule) for c in exp.capture)
        assert exp.to_dict() == data


class TestExpectationKitchenSink:
    def test_full_round_trip(self):
        # A maximal expectation exercising every previously-dropped feature at once:
        # request not/protocol, a PARAMETERS body matcher, chaos, rate limit, capture,
        # namespace, cross-protocol scenarios, before/after actions, and steps.
        data = {
            "id": "kitchen-sink",
            "priority": 10,
            "percentage": 50,
            "httpRequest": {
                "not": True,
                "method": "POST",
                "path": "/orders",
                "protocol": "HTTP_2",
                "headers": [{"name": "X-Api", "values": ["v1"]}],
                "body": {
                    "type": "PARAMETERS",
                    "parameters": [{"name": "status", "values": ["active"]}],
                },
            },
            "httpResponse": {"statusCode": 201, "body": "created"},
            "namespace": "tenant-x",
            "rateLimit": {
                "name": "orders",
                "algorithm": "token_bucket",
                "burst": 20,
                "refillPerSecond": 5.0,
            },
            "chaos": {"errorStatus": 500, "errorProbability": 0.1},
            "capture": [{"source": "jsonPath", "expression": "$.id", "into": "orderId"}],
            "times": {"remainingTimes": 3, "unlimited": False},
            "timeToLive": {"timeUnit": "SECONDS", "timeToLive": 60, "unlimited": False},
            "crossProtocolScenarios": [
                {"trigger": "HTTP_REQUEST", "scenarioName": "s", "targetState": "done"}
            ],
            "beforeActions": [{"httpRequest": {"path": "/warm"}}],
            "afterActions": [{"httpRequest": {"path": "/cleanup"}}],
        }
        exp = Expectation.from_dict(data)
        assert isinstance(exp.http_request.body, ParameterBody)
        assert isinstance(exp.rate_limit, RateLimit)
        assert exp.to_dict() == data

    def test_forward_with_fallback_kitchen_sink(self):
        data = {
            "id": "fwd",
            "httpRequest": {"path": "/proxy", "secure": True},
            "httpForwardWithFallback": {
                "httpForward": {"host": "upstream", "port": 8443, "scheme": "HTTPS"},
                "fallbackResponse": {"statusCode": 503, "body": "unavailable"},
                "fallbackOnStatusCodes": [500, 502, 503, 504],
                "fallbackOnTimeout": True,
                "delay": {"timeUnit": "MILLISECONDS", "value": 100},
            },
        }
        assert Expectation.from_dict(data).to_dict() == data


# ---------------------------------------------------------------------------
# Round-trip fidelity fills — fields added to close the known-gaps manifest.
# Each test asserts an exact wire-shaped round-trip (from_dict -> to_dict).
# ---------------------------------------------------------------------------


class TestChaosGraphqlErrors:
    def test_graphql_error_fields_round_trip(self):
        data = {
            "errorProbability": 0.3,
            "graphqlErrors": True,
            "graphqlErrorMessage": "boom",
            "graphqlErrorCode": "INTERNAL_SERVER_ERROR",
            "graphqlNullifyData": False,
        }
        assert HttpChaosProfile.from_dict(data).to_dict() == data


class TestGraphQLBodyMatchExtras:
    def test_fields_and_selection_set_match_type_round_trip(self):
        data = {
            "type": "GRAPHQL",
            "query": "query GetUser($id: ID!) { user(id: $id) { name } }",
            "operationName": "GetUser",
            "selectionSetMatchType": "AST_SUBSET",
            "fields": ["user"],
            "variablesSchema": "{\"type\":\"object\"}",
        }
        body = _deserialize_body(data)
        assert isinstance(body, GraphQLBody)
        assert body.fields == ["user"]
        assert body.selection_set_match_type == "AST_SUBSET"
        assert body.to_dict() == data

    def test_schema_field_round_trip(self):
        data = {"type": "GRAPHQL", "query": "{ hello }", "schema": "type Query { hello: String }"}
        assert _deserialize_body(data).to_dict() == data


class TestParameterBodyWireForms:
    def test_object_map_form_preserved(self):
        # MockServer's canonical emission: {name: [values]} object-map.
        data = {
            "type": "PARAMETERS",
            "parameters": {"email": ["joe@example.com"], "password": ["secret"]},
        }
        body = ParameterBody.from_dict(data)
        assert body.parameters_as_map is True
        assert body.to_dict() == data

    def test_array_form_preserved(self):
        # Legacy [{name, values}] array wire form must still round-trip verbatim.
        data = {"type": "PARAMETERS", "parameters": [{"name": "email", "values": ["a", "b"]}]}
        body = ParameterBody.from_dict(data)
        assert body.parameters_as_map is False
        assert body.to_dict() == data


class TestPathParametersSchemaMatcher:
    def test_schema_matcher_map_round_trips(self):
        data = {
            "path": "/cart/{cartId}",
            "pathParameters": {
                "cartId": [{"schema": {"type": "string", "pattern": "^[A-Z0-9-]+$"}}],
                "maxItemCount": [{"schema": {"type": "integer"}}],
            },
        }
        req = HttpRequest.from_dict(data)
        assert req.to_dict() == data

    def test_plain_string_values_round_trip(self):
        data = {"path": "/x/{id}", "pathParameters": {"id": ["abc"]}}
        assert HttpRequest.from_dict(data).to_dict() == data


class TestGrpcMessageTemplateType:
    def test_stream_message_template_type(self):
        data = {"json": "{\"id\":1}", "templateType": "VELOCITY", "delay": {"timeUnit": "MILLISECONDS", "value": 100}}
        assert GrpcStreamMessage.from_dict(data).to_dict() == data

    def test_bidi_rule_response_template_type(self):
        data = {
            "statusName": "OK",
            "rules": [
                {"matchJson": "{\"name\":\"world\"}", "responses": [{"json": "{\"reply\":\"hi\"}", "templateType": "MUSTACHE"}]}
            ],
        }
        assert GrpcBidiResponse.from_dict(data).to_dict() == data


class TestHttpResponseTrailers:
    def test_trailers_round_trip(self):
        data = {"statusCode": 200, "trailers": [{"name": "X-Trailer", "values": ["end"]}]}
        resp = HttpResponse.from_dict({"statusCode": 200, "trailers": {"X-Trailer": ["end"]}})
        assert resp.to_dict() == data

    def test_with_trailer_builder(self):
        resp = HttpResponse.response("ok").with_trailer("X-Checksum", "abc")
        assert resp.to_dict()["trailers"] == [{"name": "X-Checksum", "values": ["abc"]}]


class TestWebSocketFrameMatchers:
    def test_matchers_round_trip(self):
        data = {
            "subprotocol": "chat",
            "messages": [{"text": "hello"}],
            "matchers": [
                {"frameType": "TEXT", "textMatcher": "ping", "responses": [{"text": "pong"}]}
            ],
            "closeConnection": False,
        }
        resp = HttpWebSocketResponse.from_dict(data)
        assert isinstance(resp.matchers[0], WebSocketFrameMatcher)
        assert isinstance(resp.matchers[0].responses[0], WebSocketMessage)
        assert resp.to_dict() == data


class TestExpectationTimestamp:
    def test_timestamp_round_trip(self):
        data = {
            "httpRequest": {"path": "/x"},
            "httpResponse": {"body": "ok"},
            "timestamp": "2026-07-03T12:00:00.000Z",
        }
        assert Expectation.from_dict(data).to_dict() == data


class TestActionFieldsTheServerAccepts:
    """Each field round-trips under the server's wire name and is left out when unset."""

    def test_delay_template_round_trip(self):
        data = {"timeUnit": "SECONDS", "value": 2, "template": "$!request.path.length()", "templateType": "VELOCITY"}
        delay = Delay.from_dict(data)
        assert delay.template == "$!request.path.length()"
        assert delay.template_type == "VELOCITY"
        assert delay.to_dict() == data
        assert Delay(time_unit="SECONDS", value=2).to_dict() == {"timeUnit": "SECONDS", "value": 2}

    def test_response_status_code_range_schema_and_recover_after_round_trip(self):
        data = {
            "statusCode": 200,
            "statusCodeRange": "2xx",
            "generateFromSchema": '{"type":"string"}',
            "recoverAfter": {
                "failTimes": 2,
                "failResponse": {"statusCode": 503, "body": "busy"},
                "idempotencyHeader": "X-Idempotency",
            },
        }
        resp = HttpResponse.from_dict(data)
        assert isinstance(resp.recover_after, RecoverAfter)
        assert isinstance(resp.recover_after.fail_response, HttpResponse)
        assert resp.to_dict() == data
        built = (
            HttpResponse.response(status_code=200)
            .with_status_code_range("2xx")
            .with_generate_from_schema('{"type":"string"}')
            .with_recover_after(RecoverAfter(
                fail_times=2,
                fail_response=HttpResponse(status_code=503, body="busy"),
                idempotency_header="X-Idempotency",
            ))
        )
        assert built.to_dict() == data
        unset = HttpResponse(status_code=200).to_dict()
        assert not {"statusCodeRange", "generateFromSchema", "recoverAfter"} & unset.keys()
        assert RecoverAfter().to_dict() == {}

    def test_template_response_override_and_modifier_round_trip(self):
        data = {
            "templateType": "VELOCITY",
            "template": "$!request.path",
            "responseOverride": {"statusCode": 297, "headers": [{"name": "x-a", "values": ["b"]}]},
            "responseModifier": {"headers": {"remove": ["x-gone"]}, "cookies": {"add": {"c": "v"}}},
        }
        tmpl = HttpTemplate.from_dict(data)
        assert isinstance(tmpl.response_override, HttpResponse)
        assert tmpl.to_dict() == data
        unset = HttpTemplate(template_type="VELOCITY", template="x").to_dict()
        assert not {"responseOverride", "responseModifier"} & unset.keys()

    def test_websocket_template_type_and_graphql_filter_round_trip(self):
        data = {
            "messages": [{"text": "hi"}],
            "templateType": "MUSTACHE",
            "graphqlSubscriptionFilter": {
                "type": "GRAPHQL",
                "query": "subscription { ticks }",
                "operationName": "Ticks",
                "variablesSchema": '{"type":"object"}',
                "selectionSetMatchType": "AST_SUBSET",
                "fields": ["ticks"],
            },
        }
        resp = HttpWebSocketResponse.from_dict(data)
        assert isinstance(resp.graphql_subscription_filter, GraphQLSubscriptionFilter)
        assert resp.to_dict() == data
        unset = HttpWebSocketResponse(messages=[WebSocketMessage(text="hi")]).to_dict()
        assert not {"templateType", "graphqlSubscriptionFilter"} & unset.keys()
        assert GraphQLSubscriptionFilter(query="subscription { a }").to_dict() == {"query": "subscription { a }"}

    def test_sse_template_type_round_trip(self):
        data = {"statusCode": 200, "events": [{"data": "d"}], "templateType": "MUSTACHE"}
        assert HttpSseResponse.from_dict(data).to_dict() == data
        assert "templateType" not in HttpSseResponse(status_code=200).to_dict()

    def test_sse_event_comment_round_trip(self):
        data = {"statusCode": 200, "events": [{"comment": "heartbeat"}, {"data": "d", "comment": "a\nb"}]}
        resp = HttpSseResponse.from_dict(data)
        assert resp.events[0].comment == "heartbeat"
        assert resp.to_dict() == data
        assert "comment" not in HttpSseResponse.from_dict({"events": [{"data": "d"}]}).to_dict()["events"][0]

    def test_expectation_carries_the_fields_through(self):
        data = {
            "httpRequest": {"path": "/x"},
            "httpResponseTemplate": {
                "templateType": "MUSTACHE",
                "template": "{}",
                "delay": {"timeUnit": "MILLISECONDS", "value": 1, "template": "{{ request.path.length }}", "templateType": "MUSTACHE"},
                "responseOverride": {"statusCode": 201},
                "responseModifier": {"headers": {"remove": ["x"]}},
            },
        }
        assert Expectation.from_dict(data).to_dict() == data


class TestHttpTemplateParameters:
    """Tests for the parameters field on HttpTemplate."""

    def test_parameters_round_trip_keeping_nulls_and_empty_values(self):
        parameters = {"greeting": "hello", "names": ["ann", "bob"], "limits": {"max": 5, "none": None}, "empty": ""}
        t = HttpTemplate(template_type="MUSTACHE", template="{{ parameters.greeting }}", parameters=parameters)
        result = t.to_dict()
        assert result["parameters"] == parameters
        assert HttpTemplate.from_dict(result).to_dict() == result

    def test_with_parameter_adds_values(self):
        t = HttpTemplate(template_type="VELOCITY", template="$parameters.a").with_parameter("a", 1).with_parameter("b", [True])
        assert t.to_dict()["parameters"] == {"a": 1, "b": [True]}

    def test_parameters_not_emitted_when_unset_or_empty(self):
        assert "parameters" not in HttpTemplate(template_type="VELOCITY", template="x").to_dict()
        assert "parameters" not in HttpTemplate(template_type="VELOCITY", template="x", parameters={}).to_dict()
