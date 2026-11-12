//! Unit tests — JSON building and model construction.
//! These run with NO server.

use mockserver_client::*;

// ---------------------------------------------------------------------------
// HttpRequest builder tests
// ---------------------------------------------------------------------------

#[test]
fn test_empty_request_serializes_to_empty_object() {
    let req = HttpRequest::new();
    let json = serde_json::to_value(&req).unwrap();
    assert_eq!(json, serde_json::json!({}));
}

#[test]
fn test_request_method_and_path() {
    let req = HttpRequest::new().method("GET").path("/hello");
    let json = serde_json::to_value(&req).unwrap();
    assert_eq!(json["method"], "GET");
    assert_eq!(json["path"], "/hello");
}

#[test]
fn test_request_query_params() {
    let req = HttpRequest::new()
        .path("/search")
        .query_param("q", "rust")
        .query_param("page", "1");
    let json = serde_json::to_value(&req).unwrap();
    assert_eq!(
        json["queryStringParameters"]["q"],
        serde_json::json!(["rust"])
    );
    assert_eq!(
        json["queryStringParameters"]["page"],
        serde_json::json!(["1"])
    );
}

#[test]
fn test_request_multiple_values_same_key() {
    let req = HttpRequest::new()
        .query_param("tag", "a")
        .query_param("tag", "b");
    let json = serde_json::to_value(&req).unwrap();
    let tags = json["queryStringParameters"]["tag"].as_array().unwrap();
    assert_eq!(tags.len(), 2);
    assert!(tags.contains(&serde_json::json!("a")));
    assert!(tags.contains(&serde_json::json!("b")));
}

#[test]
fn test_request_headers() {
    let req = HttpRequest::new()
        .header("Content-Type", "application/json")
        .header("Accept", "text/plain");
    let json = serde_json::to_value(&req).unwrap();
    assert_eq!(
        json["headers"]["Content-Type"],
        serde_json::json!(["application/json"])
    );
    assert_eq!(json["headers"]["Accept"], serde_json::json!(["text/plain"]));
}

#[test]
fn test_request_plain_body() {
    let req = HttpRequest::new().body("hello world");
    let json = serde_json::to_value(&req).unwrap();
    assert_eq!(json["body"], "hello world");
}

#[test]
fn test_request_json_body() {
    let req = HttpRequest::new().json_body(serde_json::json!({"key": "value"}));
    let json = serde_json::to_value(&req).unwrap();
    assert_eq!(json["body"]["type"], "JSON");
    // The json field is the serialized string of the value
    let json_str: &str = json["body"]["json"].as_str().unwrap();
    let parsed: serde_json::Value = serde_json::from_str(json_str).unwrap();
    assert_eq!(parsed, serde_json::json!({"key": "value"}));
}

#[test]
fn test_request_jwt_matcher() {
    let req = HttpRequest::new().method("GET").path("/secure").jwt(
        Jwt::new()
            .claim("sub", "user-123")
            .claim("role", "!admin")
            .claim("email", "^.+@example.com$")
            .issuer("https://issuer.example.com")
            .audience("my-api")
            .algorithm("RS256"),
    );
    let json = serde_json::to_value(&req).unwrap();
    assert_eq!(json["method"], "GET");
    assert_eq!(json["path"], "/secure");
    // Claims map: exact, negated, and regex values are plain strings.
    assert_eq!(
        json["jwt"],
        serde_json::json!({
            "claims": {
                "sub": "user-123",
                "role": "!admin",
                "email": "^.+@example.com$"
            },
            "issuer": "https://issuer.example.com",
            "audience": "my-api",
            "algorithm": "RS256"
        })
    );
    // Unset optional fields must be omitted entirely.
    assert!(json["jwt"].get("header").is_none());
    assert!(json["jwt"].get("scheme").is_none());
}

#[test]
fn test_request_jwt_full_wire_shape() {
    let req = HttpRequest::new().jwt(
        Jwt::new()
            .claim("sub", "user-123")
            .claim("role", "!admin")
            .claim("email", "^.+@example.com$")
            .issuer("https://issuer.example.com")
            .audience("my-api")
            .algorithm("RS256")
            .header("authorization")
            .scheme("Bearer"),
    );
    let json = serde_json::to_value(&req).unwrap();
    assert_eq!(
        json,
        serde_json::json!({
            "jwt": {
                "claims": {
                    "sub": "user-123",
                    "role": "!admin",
                    "email": "^.+@example.com$"
                },
                "issuer": "https://issuer.example.com",
                "audience": "my-api",
                "algorithm": "RS256",
                "header": "authorization",
                "scheme": "Bearer"
            }
        })
    );
}

#[test]
fn test_request_all_of_body() {
    let req = HttpRequest::new().body_value(Body::all_of(vec![
        Body::json_path("$.name"),
        Body::regex(".*active.*"),
    ]));
    let json = serde_json::to_value(&req).unwrap();
    assert_eq!(
        json["body"],
        serde_json::json!({
            "type": "ALL_OF",
            "bodyAllOf": [
                { "type": "JSON_PATH", "jsonPath": "$.name" },
                { "type": "REGEX", "regex": ".*active.*" }
            ]
        })
    );
}

#[test]
fn test_all_of_body_round_trips() {
    let body = Body::all_of(vec![Body::json_path("$.id"), Body::regex("^ok$")]);
    let value = serde_json::to_value(&body).unwrap();
    let parsed: Body = serde_json::from_value(value).unwrap();
    assert_eq!(parsed, body);
}

// ---------------------------------------------------------------------------
// HttpResponse builder tests
// ---------------------------------------------------------------------------

#[test]
fn test_response_status_code() {
    let resp = HttpResponse::new().status_code(200);
    let json = serde_json::to_value(&resp).unwrap();
    assert_eq!(json["statusCode"], 200);
}

#[test]
fn test_response_with_body_and_headers() {
    let resp = HttpResponse::new()
        .status_code(201)
        .header("Location", "/api/42")
        .body("{\"id\": 42}");
    let json = serde_json::to_value(&resp).unwrap();
    assert_eq!(json["statusCode"], 201);
    assert_eq!(json["body"], "{\"id\": 42}");
    assert_eq!(json["headers"]["Location"], serde_json::json!(["/api/42"]));
}

#[test]
fn test_response_with_delay() {
    let resp = HttpResponse::new()
        .status_code(200)
        .delay(Delay::milliseconds(500));
    let json = serde_json::to_value(&resp).unwrap();
    assert_eq!(json["delay"]["timeUnit"], "MILLISECONDS");
    assert_eq!(json["delay"]["value"], 500);
}

#[test]
fn test_response_string_body_is_unchanged_on_the_wire() {
    let resp = HttpResponse::new().status_code(200).body("plain text");
    let json = serde_json::to_string(&resp).unwrap();
    assert_eq!(json, r#"{"statusCode":200,"body":"plain text"}"#);

    let back: HttpResponse = serde_json::from_str(&json).unwrap();
    assert_eq!(back.body.as_deref(), Some("plain text"));
    assert!(
        back.extra.is_empty(),
        "a string body must not leak into extra"
    );
    assert_eq!(back.typed_body(), Some(Body::Plain("plain text".into())));
    assert_eq!(back, resp);
}

#[test]
fn test_response_binary_body_serialises_base64_shape() {
    let bytes = [0x00u8, 0x01, 0x7F, 0x80, 0xFE, 0xFF];
    let resp = HttpResponse::new()
        .status_code(200)
        .binary_body(bytes, Some("application/octet-stream".to_string()));
    let json = serde_json::to_value(&resp).unwrap();
    assert_eq!(
        json,
        serde_json::json!({
            "statusCode": 200,
            "body": {
                "type": "BINARY",
                "base64Bytes": "AAF/gP7/",
                "contentType": "application/octet-stream"
            }
        })
    );
    assert_eq!(resp.body, None);
}

#[test]
fn test_response_binary_body_round_trips_and_decodes() {
    use base64::Engine;
    let bytes = b"\x00PNG\xff".to_vec();
    let resp = HttpResponse::new().binary_body(&bytes, None);
    let text = serde_json::to_string(&resp).unwrap();
    let back: HttpResponse = serde_json::from_str(&text).unwrap();
    assert_eq!(back, resp);
    assert_eq!(back.body, None);
    let Some(Body::Object(map)) = back.typed_body() else {
        panic!("expected an object body, got {:?}", back.typed_body());
    };
    assert_eq!(map["type"], "BINARY");
    assert!(!map.contains_key("contentType"));
    let decoded = base64::engine::general_purpose::STANDARD
        .decode(map["base64Bytes"].as_str().unwrap())
        .unwrap();
    assert_eq!(decoded, bytes);
}

#[test]
fn test_response_deserialises_every_server_body_shape() {
    // The shapes MockServer returns from retrieveActiveExpectations for a
    // response body: a plain string, typed objects, and a raw JSON object or
    // array for a JSON body.
    fn object(v: serde_json::Value) -> Body {
        Body::Object(v.as_object().unwrap().clone())
    }
    let json_typed = |json: &str| Body::Typed {
        body_type: "JSON".to_string(),
        json: json.to_string(),
    };
    let shapes = [
        (
            serde_json::json!({"type": "BINARY", "base64Bytes": "AP8=", "contentType": "image/png"}),
            object(
                serde_json::json!({"type": "BINARY", "base64Bytes": "AP8=", "contentType": "image/png"}),
            ),
        ),
        (
            serde_json::json!({"type": "JSON", "json": "{\"a\":1}"}),
            json_typed("{\"a\":1}"),
        ),
        (
            serde_json::json!({"type": "STRING", "string": "hi", "contentType": "text/plain; charset=utf-8"}),
            object(
                serde_json::json!({"type": "STRING", "string": "hi", "contentType": "text/plain; charset=utf-8"}),
            ),
        ),
        (
            serde_json::json!({"type": "XML", "xml": "<a/>"}),
            Body::xml("<a/>"),
        ),
        (
            serde_json::json!({"type": "FILE", "filePath": "/f.json", "templateType": "VELOCITY"}),
            Body::file("/f.json").with_template_type("VELOCITY"),
        ),
        (
            serde_json::json!({"type": "PARAMETERS", "parameters": {"k": ["v"]}}),
            object(serde_json::json!({"type": "PARAMETERS", "parameters": {"k": ["v"]}})),
        ),
        (
            serde_json::json!({"id": 1, "tags": ["x"]}),
            object(serde_json::json!({"id": 1, "tags": ["x"]})),
        ),
        (serde_json::json!([1, 2, 3]), json_typed("[1,2,3]")),
        (serde_json::json!(42), json_typed("42")),
    ];
    for (shape, expected) in shapes {
        let wire = serde_json::json!({"statusCode": 200, "body": shape.clone()});
        let resp: HttpResponse = serde_json::from_value(wire.clone())
            .unwrap_or_else(|e| panic!("could not read body {shape}: {e}"));
        assert_eq!(resp.body, None);
        assert_eq!(resp.typed_body(), Some(expected), "{shape}");
        assert_eq!(
            serde_json::to_value(&resp).unwrap(),
            wire,
            "round-trip of {shape}"
        );
    }
}

#[test]
fn test_json_array_body_is_resent_as_a_json_body_not_a_string() {
    let resp: HttpResponse =
        serde_json::from_value(serde_json::json!({"body": [1, 2, 3]})).unwrap();
    let resent = HttpResponse::new().body_value(resp.typed_body().unwrap());
    assert_eq!(
        serde_json::to_value(&resent).unwrap(),
        serde_json::json!({"body": {"type": "JSON", "json": "[1,2,3]"}})
    );

    let req: HttpRequest = serde_json::from_value(serde_json::json!({"body": [1, 2]})).unwrap();
    assert_eq!(
        req.body,
        Some(Body::Typed {
            body_type: "JSON".into(),
            json: "[1,2]".into()
        })
    );
}

#[test]
fn test_with_content_type_keeps_an_inline_json_payload_intact() {
    let inline = Body::object(
        serde_json::json!({"id": 1, "name": "x"})
            .as_object()
            .unwrap()
            .clone(),
    )
    .with_content_type("application/vnd.api+json");
    assert_eq!(
        serde_json::to_value(&inline).unwrap(),
        serde_json::json!({
            "type": "JSON",
            "json": {"id": 1, "name": "x"},
            "contentType": "application/vnd.api+json"
        }),
        "the content type must not be injected into the JSON payload"
    );

    for typed in ["BINARY", "JSON", "STRING", "XML", "FILE"] {
        let body = Body::object(
            serde_json::json!({"type": typed})
                .as_object()
                .unwrap()
                .clone(),
        )
        .with_content_type("a/b");
        assert_eq!(
            serde_json::to_value(&body).unwrap(),
            serde_json::json!({"type": typed, "contentType": "a/b"})
        );
    }

    let params = Body::parameters(std::collections::HashMap::from([(
        "k".to_string(),
        vec!["v".to_string()],
    )]));
    assert_eq!(params.clone().with_content_type("a/b"), params);

    // An object whose "type" is not a MockServer body type is a JSON payload.
    let geojson = serde_json::json!({"type": "FeatureCollection", "features": []});
    let wrapped = Body::object(geojson.as_object().unwrap().clone())
        .with_content_type("application/geo+json");
    assert_eq!(
        serde_json::to_value(&wrapped).unwrap(),
        serde_json::json!({
            "type": "JSON",
            "json": geojson,
            "contentType": "application/geo+json"
        })
    );
}

#[test]
fn test_response_body_setters_replace_each_other() {
    let resp = HttpResponse::new().binary_body([1u8, 2], None).body("text");
    assert_eq!(
        serde_json::to_value(&resp).unwrap(),
        serde_json::json!({"body": "text"})
    );

    let resp = HttpResponse::new()
        .body("text")
        .json_body(serde_json::json!({"a": 1}));
    assert_eq!(
        serde_json::to_value(&resp).unwrap(),
        serde_json::json!({"body": {"type": "JSON", "json": "{\"a\":1}"}})
    );

    let resp = HttpResponse::new().file_body("/f").body_value("plain");
    assert_eq!(
        serde_json::to_value(&resp).unwrap(),
        serde_json::json!({"body": "plain"})
    );
}

#[test]
fn test_response_string_body_field_wins_over_extra_body() {
    let mut resp = HttpResponse::new().binary_body([1u8], None);
    resp.body = Some("wins".to_string());
    assert_eq!(
        serde_json::to_string(&resp).unwrap(),
        r#"{"body":"wins"}"#,
        "a set string body must suppress extra[\"body\"] rather than emit a duplicate key"
    );
}

#[test]
fn test_response_body_value_with_content_type() {
    let resp =
        HttpResponse::new().body_value(Body::xml("<a/>").with_content_type("application/xml"));
    assert_eq!(
        serde_json::to_value(&resp).unwrap()["body"],
        serde_json::json!({"type": "XML", "xml": "<a/>", "contentType": "application/xml"})
    );
    let typed = Body::Typed {
        body_type: "JSON".into(),
        json: "{}".into(),
    }
    .with_content_type("application/vnd.api+json");
    assert_eq!(
        serde_json::to_value(&typed).unwrap(),
        serde_json::json!({"type": "JSON", "json": "{}", "contentType": "application/vnd.api+json"})
    );
}

#[test]
fn test_request_binary_data_matcher() {
    let req = HttpRequest::new().binary_data(b"\x00\x01PING\xff");
    let json = serde_json::to_value(&req).unwrap();
    assert_eq!(json, serde_json::json!({"binaryData": "AAFQSU5H/w=="}));
    let back: HttpRequest = serde_json::from_value(json).unwrap();
    assert_eq!(back.binary_data_bytes(), Some(b"\x00\x01PING\xff".to_vec()));
    assert_eq!(HttpRequest::new().binary_data_bytes(), None);
}

// ---------------------------------------------------------------------------
// HttpForward builder tests
// ---------------------------------------------------------------------------

#[test]
fn test_forward_basic() {
    let fwd = HttpForward::new("backend.local", 8080);
    let json = serde_json::to_value(&fwd).unwrap();
    assert_eq!(json["host"], "backend.local");
    assert_eq!(json["port"], 8080);
    assert!(json.get("scheme").is_none());
}

#[test]
fn test_forward_with_scheme() {
    let fwd = HttpForward::new("secure.local", 443).scheme("HTTPS");
    let json = serde_json::to_value(&fwd).unwrap();
    assert_eq!(json["scheme"], "HTTPS");
}

// ---------------------------------------------------------------------------
// HttpError builder tests
// ---------------------------------------------------------------------------

#[test]
fn test_error_drop_connection() {
    let err = HttpError::new().drop_connection(true);
    let json = serde_json::to_value(&err).unwrap();
    assert_eq!(json["dropConnection"], true);
}

#[test]
fn test_error_reset_connection() {
    let err = HttpError::new().reset_connection(true);
    let json = serde_json::to_value(&err).unwrap();
    assert_eq!(json, serde_json::json!({ "resetConnection": true }));
    let parsed: HttpError = serde_json::from_value(json).unwrap();
    assert_eq!(parsed.reset_connection_value(), Some(true));
    assert_eq!(HttpError::new().reset_connection_value(), None);
}

// ---------------------------------------------------------------------------
// Times and TimeToLive tests
// ---------------------------------------------------------------------------

#[test]
fn test_times_unlimited() {
    let t = Times::unlimited();
    let json = serde_json::to_value(&t).unwrap();
    assert_eq!(json["unlimited"], true);
    assert!(json.get("remainingTimes").is_none());
}

#[test]
fn test_times_exactly() {
    let t = Times::exactly(3);
    let json = serde_json::to_value(&t).unwrap();
    assert_eq!(json["unlimited"], false);
    assert_eq!(json["remainingTimes"], 3);
}

#[test]
fn test_times_once() {
    let t = Times::once();
    let json = serde_json::to_value(&t).unwrap();
    assert_eq!(json["remainingTimes"], 1);
    assert_eq!(json["unlimited"], false);
}

#[test]
fn test_ttl_unlimited() {
    let ttl = TimeToLive::unlimited();
    let json = serde_json::to_value(&ttl).unwrap();
    assert_eq!(json["unlimited"], true);
    assert!(json.get("timeUnit").is_none());
}

#[test]
fn test_ttl_seconds() {
    let ttl = TimeToLive::seconds(30);
    let json = serde_json::to_value(&ttl).unwrap();
    assert_eq!(json["unlimited"], false);
    assert_eq!(json["timeUnit"], "SECONDS");
    assert_eq!(json["timeToLive"], 30);
}

// ---------------------------------------------------------------------------
// VerificationTimes tests
// ---------------------------------------------------------------------------

#[test]
fn test_verification_times_at_least() {
    let vt = VerificationTimes::at_least(2);
    let json = serde_json::to_value(&vt).unwrap();
    assert_eq!(json["atLeast"], 2);
    // atMost is always sent as the -1 "unbounded" sentinel so the server does
    // not default it to 0 (which would make at_least(2) an impossible between(2,0)).
    assert_eq!(json["atMost"], -1);
}

#[test]
fn test_verification_times_at_most() {
    let vt = VerificationTimes::at_most(3);
    let json = serde_json::to_value(&vt).unwrap();
    // atLeast is sent as the -1 "unbounded" sentinel; atMost carries the bound.
    assert_eq!(json["atLeast"], -1);
    assert_eq!(json["atMost"], 3);
}

#[test]
fn test_verification_times_exactly() {
    let vt = VerificationTimes::exactly(5);
    let json = serde_json::to_value(&vt).unwrap();
    assert_eq!(json["atLeast"], 5);
    assert_eq!(json["atMost"], 5);
}

#[test]
fn test_verification_times_between() {
    let vt = VerificationTimes::between(1, 10);
    let json = serde_json::to_value(&vt).unwrap();
    assert_eq!(json["atLeast"], 1);
    assert_eq!(json["atMost"], 10);
}

// ---------------------------------------------------------------------------
// Full Expectation tests
// ---------------------------------------------------------------------------

#[test]
fn test_full_expectation_json() {
    let expectation = Expectation::new(
        HttpRequest::new()
            .method("GET")
            .path("/hello")
            .query_param("q", "x")
            .header("H", "v"),
    )
    .respond(
        HttpResponse::new()
            .status_code(200)
            .header("Content-Type", "application/json")
            .body("{\"k\":1}")
            .delay(Delay::milliseconds(0)),
    )
    .times(Times::once())
    .time_to_live(TimeToLive::unlimited())
    .priority(0);

    let json = serde_json::to_value(&expectation).unwrap();

    // Verify request
    assert_eq!(json["httpRequest"]["method"], "GET");
    assert_eq!(json["httpRequest"]["path"], "/hello");
    assert_eq!(
        json["httpRequest"]["queryStringParameters"]["q"],
        serde_json::json!(["x"])
    );
    assert_eq!(
        json["httpRequest"]["headers"]["H"],
        serde_json::json!(["v"])
    );

    // Verify response
    assert_eq!(json["httpResponse"]["statusCode"], 200);
    assert_eq!(json["httpResponse"]["body"], "{\"k\":1}");
    assert_eq!(
        json["httpResponse"]["headers"]["Content-Type"],
        serde_json::json!(["application/json"])
    );
    assert_eq!(json["httpResponse"]["delay"]["timeUnit"], "MILLISECONDS");
    assert_eq!(json["httpResponse"]["delay"]["value"], 0);

    // Verify times
    assert_eq!(json["times"]["remainingTimes"], 1);
    assert_eq!(json["times"]["unlimited"], false);

    // Verify TTL
    assert_eq!(json["timeToLive"]["unlimited"], true);

    // Verify priority
    assert_eq!(json["priority"], 0);
}

#[test]
fn test_expectation_with_forward() {
    let expectation = Expectation::new(HttpRequest::new().method("GET").path("/proxy"))
        .forward(HttpForward::new("backend", 8080).scheme("HTTP"));

    let json = serde_json::to_value(&expectation).unwrap();
    assert_eq!(json["httpForward"]["host"], "backend");
    assert_eq!(json["httpForward"]["port"], 8080);
    assert_eq!(json["httpForward"]["scheme"], "HTTP");
    assert!(json.get("httpResponse").is_none());
}

#[test]
fn test_expectation_with_error() {
    let expectation = Expectation::new(HttpRequest::new().path("/fail"))
        .error(HttpError::new().drop_connection(true));

    let json = serde_json::to_value(&expectation).unwrap();
    assert_eq!(json["httpError"]["dropConnection"], true);
    assert!(json.get("httpResponse").is_none());
    assert!(json.get("httpForward").is_none());
}

#[test]
fn test_expectation_with_id() {
    let expectation = Expectation::new(HttpRequest::new().path("/x"))
        .id("my-expectation-1")
        .respond(HttpResponse::new().status_code(204));

    let json = serde_json::to_value(&expectation).unwrap();
    assert_eq!(json["id"], "my-expectation-1");
}

// ---------------------------------------------------------------------------
// Verification JSON tests
// ---------------------------------------------------------------------------

#[test]
fn test_verification_json() {
    let v = Verification {
        http_request: Some(HttpRequest::new().method("GET").path("/hello")),
        http_response: None,
        times: Some(VerificationTimes::at_least(1)),
        maximum_number_of_request_to_return_in_verification_failure: None,
    };
    let json = serde_json::to_value(&v).unwrap();
    assert_eq!(json["httpRequest"]["method"], "GET");
    assert_eq!(json["httpRequest"]["path"], "/hello");
    assert_eq!(json["times"]["atLeast"], 1);
    // httpResponse should be absent (not null)
    assert!(json.get("httpResponse").is_none());
    // maximumNumberOfRequestToReturnInVerificationFailure should be absent
    assert!(json
        .get("maximumNumberOfRequestToReturnInVerificationFailure")
        .is_none());
}

#[test]
fn test_verification_with_response_json() {
    let v = Verification {
        http_request: Some(HttpRequest::new().method("POST").path("/api")),
        http_response: Some(
            HttpResponse::new()
                .status_code(201)
                .header("Location", "/api/42"),
        ),
        times: Some(VerificationTimes::exactly(1)),
        maximum_number_of_request_to_return_in_verification_failure: None,
    };
    let json = serde_json::to_value(&v).unwrap();
    assert_eq!(json["httpRequest"]["method"], "POST");
    assert_eq!(json["httpRequest"]["path"], "/api");
    assert_eq!(json["httpResponse"]["statusCode"], 201);
    assert_eq!(
        json["httpResponse"]["headers"]["Location"],
        serde_json::json!(["/api/42"])
    );
    assert_eq!(json["times"]["atLeast"], 1);
    assert_eq!(json["times"]["atMost"], 1);
}

#[test]
fn test_verification_response_only_json() {
    let v = Verification {
        http_request: None,
        http_response: Some(HttpResponse::new().status_code(500)),
        times: Some(VerificationTimes::at_most(0)),
        maximum_number_of_request_to_return_in_verification_failure: None,
    };
    let json = serde_json::to_value(&v).unwrap();
    // httpRequest should be ABSENT (not null) for response-only verification
    assert!(
        json.get("httpRequest").is_none(),
        "httpRequest must be absent for response-only verification"
    );
    assert_eq!(json["httpResponse"]["statusCode"], 500);
    assert_eq!(json["times"]["atMost"], 0);
}

#[test]
fn test_verification_with_max_failures() {
    let v = Verification {
        http_request: Some(HttpRequest::new().path("/test")),
        http_response: None,
        times: Some(VerificationTimes::at_least(1)),
        maximum_number_of_request_to_return_in_verification_failure: Some(5),
    };
    let json = serde_json::to_value(&v).unwrap();
    assert_eq!(
        json["maximumNumberOfRequestToReturnInVerificationFailure"],
        5
    );
}

#[test]
fn test_verification_sequence_json() {
    let vs = VerificationSequence {
        http_requests: Some(vec![
            HttpRequest::new().path("/first"),
            HttpRequest::new().path("/second"),
        ]),
        http_responses: None,
    };
    let json = serde_json::to_value(&vs).unwrap();
    let requests = json["httpRequests"].as_array().unwrap();
    assert_eq!(requests.len(), 2);
    assert_eq!(requests[0]["path"], "/first");
    assert_eq!(requests[1]["path"], "/second");
    // httpResponses should be absent
    assert!(json.get("httpResponses").is_none());
}

#[test]
fn test_verification_sequence_with_responses_json() {
    let vs = VerificationSequence {
        http_requests: Some(vec![
            HttpRequest::new().method("GET").path("/a"),
            HttpRequest::new().method("POST").path("/b"),
        ]),
        http_responses: Some(vec![
            HttpResponse::new().status_code(200),
            HttpResponse::new().status_code(201).body("{\"id\":1}"),
        ]),
    };
    let json = serde_json::to_value(&vs).unwrap();

    let requests = json["httpRequests"].as_array().unwrap();
    assert_eq!(requests.len(), 2);
    assert_eq!(requests[0]["method"], "GET");
    assert_eq!(requests[1]["method"], "POST");

    let responses = json["httpResponses"].as_array().unwrap();
    assert_eq!(responses.len(), 2);
    assert_eq!(responses[0]["statusCode"], 200);
    assert_eq!(responses[1]["statusCode"], 201);
    assert_eq!(responses[1]["body"], "{\"id\":1}");
}

#[test]
fn test_verification_sequence_responses_only_json() {
    // Edge case: verify a sequence of responses without constraining requests
    let vs = VerificationSequence {
        http_requests: None,
        http_responses: Some(vec![
            HttpResponse::new().status_code(200),
            HttpResponse::new().status_code(404),
        ]),
    };
    let json = serde_json::to_value(&vs).unwrap();
    // httpRequests should be absent
    assert!(
        json.get("httpRequests").is_none(),
        "httpRequests must be absent when None"
    );
    let responses = json["httpResponses"].as_array().unwrap();
    assert_eq!(responses.len(), 2);
    assert_eq!(responses[0]["statusCode"], 200);
    assert_eq!(responses[1]["statusCode"], 404);
}

#[test]
fn test_verification_deserialization_with_response() {
    let json = r#"{
        "httpRequest": {"method": "GET", "path": "/x"},
        "httpResponse": {"statusCode": 200},
        "times": {"atLeast": 1}
    }"#;
    let v: Verification = serde_json::from_str(json).unwrap();
    assert_eq!(
        v.http_request.as_ref().unwrap().path,
        Some("/x".to_string())
    );
    assert_eq!(v.http_response.as_ref().unwrap().status_code, Some(200));
    assert_eq!(v.times.as_ref().unwrap().at_least, Some(1));
}

#[test]
fn test_verification_sequence_deserialization_with_responses() {
    let json = r#"{
        "httpRequests": [{"path": "/a"}],
        "httpResponses": [{"statusCode": 200}]
    }"#;
    let vs: VerificationSequence = serde_json::from_str(json).unwrap();
    assert_eq!(vs.http_requests.as_ref().unwrap().len(), 1);
    assert_eq!(vs.http_responses.as_ref().unwrap().len(), 1);
    assert_eq!(
        vs.http_responses.as_ref().unwrap()[0].status_code,
        Some(200)
    );
}

// ---------------------------------------------------------------------------
// Ports JSON tests
// ---------------------------------------------------------------------------

#[test]
fn test_ports_serialization() {
    let p = Ports {
        ports: vec![1080, 1081],
    };
    let json = serde_json::to_value(&p).unwrap();
    assert_eq!(json["ports"], serde_json::json!([1080, 1081]));
}

#[test]
fn test_ports_deserialization() {
    let json = r#"{"ports":[1080,1081]}"#;
    let p: Ports = serde_json::from_str(json).unwrap();
    assert_eq!(p.ports, vec![1080, 1081]);
}

// ---------------------------------------------------------------------------
// Body deserialization tests
// ---------------------------------------------------------------------------

#[test]
fn test_body_plain_deserialization() {
    let json = r#"{"method":"GET","path":"/x","body":"hello"}"#;
    let req: HttpRequest = serde_json::from_str(json).unwrap();
    assert_eq!(req.body, Some(Body::Plain("hello".to_string())));
}

#[test]
fn test_body_typed_deserialization() {
    let json = r#"{"body":{"type":"JSON","json":"{\"key\":\"value\"}"}}"#;
    let req: HttpRequest = serde_json::from_str(json).unwrap();
    match req.body {
        Some(Body::Typed { body_type, json }) => {
            assert_eq!(body_type, "JSON");
            assert_eq!(json, "{\"key\":\"value\"}");
        }
        other => panic!("Expected Body::Typed, got {other:?}"),
    }
}

// ---------------------------------------------------------------------------
// ClientBuilder URL construction tests
// ---------------------------------------------------------------------------

#[test]
fn test_client_builder_default() {
    // Builder succeeds and produces a valid client
    let client = ClientBuilder::new("localhost", 1080).build();
    assert!(client.is_ok());
}

#[test]
fn test_client_builder_with_context_path() {
    // Context path with leading slash
    let client = ClientBuilder::new("myhost", 9090)
        .context_path("/api")
        .build();
    assert!(client.is_ok());
}

#[test]
fn test_client_builder_context_path_without_slash() {
    // Context path without leading slash gets one prepended
    let client = ClientBuilder::new("myhost", 8080)
        .context_path("mock")
        .build();
    assert!(client.is_ok());
}

#[test]
fn test_client_builder_secure() {
    let client = ClientBuilder::new("secure.local", 443)
        .secure(true)
        .tls_verify(false)
        .build();
    assert!(client.is_ok());
}

#[test]
fn test_client_connection_refused() {
    // Connecting to a port nothing listens on should produce a transport error
    let client = ClientBuilder::new("127.0.0.1", 19999).build().unwrap();
    let result = client.status();
    assert!(result.is_err());
    let err_msg = format!("{}", result.unwrap_err());
    assert!(
        err_msg.contains("transport")
            || err_msg.contains("error")
            || err_msg.contains("Connection refused"),
        "Should get a transport error: {err_msg}"
    );
}

// ---------------------------------------------------------------------------
// Delay tests
// ---------------------------------------------------------------------------

#[test]
fn test_delay_seconds() {
    let d = Delay::seconds(5);
    let json = serde_json::to_value(&d).unwrap();
    assert_eq!(json["timeUnit"], "SECONDS");
    assert_eq!(json["value"], 5);
}

#[test]
fn test_delay_new_takes_any_time_unit() {
    let json = serde_json::to_value(Delay::new("DAYS", 1)).unwrap();
    assert_eq!(json, serde_json::json!({"timeUnit": "DAYS", "value": 1}));
}

#[test]
fn test_delay_distribution_roundtrip() {
    for (distribution, wire) in [
        (DelayDistribution::uniform(10, 20), serde_json::json!({"type": "UNIFORM", "min": 10, "max": 20})),
        (DelayDistribution::log_normal(30, 90), serde_json::json!({"type": "LOG_NORMAL", "median": 30, "p99": 90})),
        (DelayDistribution::gaussian(50, 5), serde_json::json!({"type": "GAUSSIAN", "mean": 50, "stdDev": 5})),
    ] {
        let delay = Delay::milliseconds(1).distribution(distribution);
        let json = serde_json::to_value(&delay).unwrap();
        assert_eq!(json, serde_json::json!({"timeUnit": "MILLISECONDS", "value": 1, "distribution": wire}));
        let back: Delay = serde_json::from_value(json.clone()).unwrap();
        assert_eq!(back, delay);
        assert_eq!(serde_json::to_value(&back).unwrap(), json);
    }
}

#[test]
fn test_delay_template_roundtrip() {
    let delay = Delay::seconds(2).template("$!request.path.length()").template_type("VELOCITY");
    let json = serde_json::to_value(&delay).unwrap();
    assert_eq!(
        json,
        serde_json::json!({
            "timeUnit": "SECONDS",
            "value": 2,
            "template": "$!request.path.length()",
            "templateType": "VELOCITY"
        })
    );
    let back: Delay = serde_json::from_value(json.clone()).unwrap();
    assert_eq!(back, delay);
    let unset = serde_json::to_value(Delay::seconds(2)).unwrap();
    assert_eq!(unset, serde_json::json!({"timeUnit": "SECONDS", "value": 2}));
}

// ---------------------------------------------------------------------------
// Expectation array serialization (upsert payload)
// ---------------------------------------------------------------------------

#[test]
fn test_expectation_array_serialization() {
    let expectations = vec![
        Expectation::new(HttpRequest::new().path("/a"))
            .respond(HttpResponse::new().status_code(200)),
        Expectation::new(HttpRequest::new().path("/b"))
            .respond(HttpResponse::new().status_code(201)),
    ];
    let json = serde_json::to_value(&expectations).unwrap();
    let arr = json.as_array().unwrap();
    assert_eq!(arr.len(), 2);
    assert_eq!(arr[0]["httpRequest"]["path"], "/a");
    assert_eq!(arr[0]["httpResponse"]["statusCode"], 200);
    assert_eq!(arr[1]["httpRequest"]["path"], "/b");
    assert_eq!(arr[1]["httpResponse"]["statusCode"], 201);
}

// ---------------------------------------------------------------------------
// HttpTemplate builder tests
// ---------------------------------------------------------------------------

#[test]
fn test_http_template_inline() {
    let tmpl = HttpTemplate::new("VELOCITY", "{ statusCode: 200 }");
    let json = serde_json::to_value(&tmpl).unwrap();
    assert_eq!(json["templateType"], "VELOCITY");
    assert_eq!(json["template"], "{ statusCode: 200 }");
    assert!(json.get("templateFile").is_none());
}

#[test]
fn test_http_template_from_file() {
    let tmpl = HttpTemplate::from_file("MUSTACHE", "/templates/response.mustache");
    let json = serde_json::to_value(&tmpl).unwrap();
    assert_eq!(json["templateType"], "MUSTACHE");
    assert_eq!(json["templateFile"], "/templates/response.mustache");
    assert!(json.get("template").is_none());
}

#[test]
fn test_http_template_with_inline_and_file() {
    let tmpl = HttpTemplate::new("VELOCITY", "inline body").template_file("/path/to/override.vm");
    let json = serde_json::to_value(&tmpl).unwrap();
    assert_eq!(json["templateType"], "VELOCITY");
    assert_eq!(json["template"], "inline body");
    assert_eq!(json["templateFile"], "/path/to/override.vm");
}

#[test]
fn test_http_template_roundtrip() {
    let json_str = r#"{"templateType":"VELOCITY","template":"body","templateFile":"/a/b.vm"}"#;
    let tmpl: HttpTemplate = serde_json::from_str(json_str).unwrap();
    assert_eq!(tmpl.template_type, Some("VELOCITY".to_string()));
    assert_eq!(tmpl.template, Some("body".to_string()));
    assert_eq!(tmpl.template_file, Some("/a/b.vm".to_string()));
    // Re-serialize
    let reserialized = serde_json::to_value(&tmpl).unwrap();
    assert_eq!(reserialized["templateFile"], "/a/b.vm");
}

#[test]
fn test_http_template_delay_and_primary_roundtrip() {
    for primary in [true, false] {
        let tmpl = HttpTemplate::new("MUSTACHE", "{}")
            .delay(Delay::seconds(2))
            .primary(primary);
        let json = serde_json::to_value(&tmpl).unwrap();
        assert_eq!(
            json,
            serde_json::json!({
                "templateType": "MUSTACHE",
                "template": "{}",
                "delay": {"timeUnit": "SECONDS", "value": 2},
                "primary": primary
            })
        );
        let back: HttpTemplate = serde_json::from_value(json.clone()).unwrap();
        assert_eq!(back, tmpl);
        assert_eq!(serde_json::to_value(&back).unwrap(), json);
    }
    let unset = serde_json::to_value(HttpTemplate::new("MUSTACHE", "{}")).unwrap();
    assert!(unset.get("delay").is_none());
    assert!(unset.get("primary").is_none());
}

#[test]
fn test_http_template_response_override_and_modifier_roundtrip() {
    let modifier = serde_json::json!({"headers": {"remove": ["x-gone"]}, "cookies": {"add": {"c": "v"}}});
    let tmpl = HttpTemplate::new("VELOCITY", "$!request.path")
        .response_override(HttpResponse::new().status_code(297))
        .response_modifier(modifier.clone());
    let json = serde_json::to_value(&tmpl).unwrap();
    assert_eq!(
        json,
        serde_json::json!({
            "templateType": "VELOCITY",
            "template": "$!request.path",
            "responseOverride": {"statusCode": 297},
            "responseModifier": modifier
        })
    );
    let back: HttpTemplate = serde_json::from_value(json.clone()).unwrap();
    assert_eq!(back, tmpl);
    assert_eq!(serde_json::to_value(&back).unwrap(), json);
    let unset = serde_json::to_value(HttpTemplate::from_file("MUSTACHE", "t.mustache")).unwrap();
    assert!(unset.get("responseOverride").is_none());
    assert!(unset.get("responseModifier").is_none());
}

#[test]
fn test_http_template_deserialization_without_template_file() {
    let json_str = r#"{"templateType":"MUSTACHE","template":"{{name}}"}"#;
    let tmpl: HttpTemplate = serde_json::from_str(json_str).unwrap();
    assert_eq!(tmpl.template_type, Some("MUSTACHE".to_string()));
    assert_eq!(tmpl.template, Some("{{name}}".to_string()));
    assert_eq!(tmpl.template_file, None);
}

// ---------------------------------------------------------------------------
// Body::File tests
// ---------------------------------------------------------------------------

#[test]
fn test_body_file_serialization_minimal() {
    let body = Body::file("/data/response.json");
    let json = serde_json::to_value(&body).unwrap();
    assert_eq!(json["type"], "FILE");
    assert_eq!(json["filePath"], "/data/response.json");
    assert!(json.get("contentType").is_none());
    assert!(json.get("templateType").is_none());
}

#[test]
fn test_body_file_serialization_with_content_type() {
    let body = Body::file("/data/response.xml").with_content_type("application/xml");
    let json = serde_json::to_value(&body).unwrap();
    assert_eq!(json["type"], "FILE");
    assert_eq!(json["filePath"], "/data/response.xml");
    assert_eq!(json["contentType"], "application/xml");
    assert!(json.get("templateType").is_none());
}

#[test]
fn test_body_file_serialization_with_template_type() {
    let body = Body::file("/data/response.vm")
        .with_content_type("application/json")
        .with_template_type("VELOCITY");
    let json = serde_json::to_value(&body).unwrap();
    assert_eq!(json["type"], "FILE");
    assert_eq!(json["filePath"], "/data/response.vm");
    assert_eq!(json["contentType"], "application/json");
    assert_eq!(json["templateType"], "VELOCITY");
}

#[test]
fn test_body_file_deserialization() {
    let json_str = r#"{"type":"FILE","filePath":"/data/resp.json","contentType":"application/json","templateType":"MUSTACHE"}"#;
    let req_json = format!(r#"{{"body":{json_str}}}"#);
    let req: HttpRequest = serde_json::from_str(&req_json).unwrap();
    match req.body {
        Some(Body::File {
            file_path,
            content_type,
            template_type,
        }) => {
            assert_eq!(file_path, "/data/resp.json");
            assert_eq!(content_type, Some("application/json".to_string()));
            assert_eq!(template_type, Some("MUSTACHE".to_string()));
        }
        other => panic!("Expected Body::File, got {other:?}"),
    }
}

#[test]
fn test_body_file_deserialization_minimal() {
    let json_str = r#"{"body":{"type":"FILE","filePath":"/data/x.txt"}}"#;
    let req: HttpRequest = serde_json::from_str(json_str).unwrap();
    match req.body {
        Some(Body::File {
            file_path,
            content_type,
            template_type,
        }) => {
            assert_eq!(file_path, "/data/x.txt");
            assert_eq!(content_type, None);
            assert_eq!(template_type, None);
        }
        other => panic!("Expected Body::File, got {other:?}"),
    }
}

#[test]
fn test_body_file_roundtrip() {
    let body = Body::File {
        file_path: "/templates/resp.vm".to_string(),
        content_type: Some("text/html".to_string()),
        template_type: Some("VELOCITY".to_string()),
    };
    let json = serde_json::to_value(&body).unwrap();
    // Wrap in a request for deserialization
    let req_json = serde_json::json!({"body": json});
    let req: HttpRequest = serde_json::from_value(req_json).unwrap();
    assert_eq!(req.body, Some(body));
}

#[test]
fn test_body_file_templated_file_path_roundtrip() {
    let body = Body::file("responses/{{ request.pathParameters.name.0 }}.json")
        .with_content_type("application/json")
        .with_template_type("MUSTACHE")
        .with_templated_file_path(true);
    let json = serde_json::to_value(&body).unwrap();
    assert_eq!(
        json,
        serde_json::json!({
            "type": "FILE",
            "filePath": "responses/{{ request.pathParameters.name.0 }}.json",
            "contentType": "application/json",
            "templateType": "MUSTACHE",
            "templatedFilePath": true
        })
    );

    let resp = HttpResponse::new()
        .status_code(200)
        .body_value(body.clone());
    let wire = serde_json::to_value(&resp).unwrap();
    let decoded: HttpResponse = serde_json::from_value(wire.clone()).unwrap();
    assert_eq!(decoded.typed_body(), Some(body));
    assert_eq!(serde_json::to_value(&decoded).unwrap(), wire);
}

#[test]
fn test_body_file_templated_file_path_omitted_when_false() {
    let body = Body::file("/tpl/resp.vm")
        .with_template_type("VELOCITY")
        .with_templated_file_path(false);
    assert!(matches!(body, Body::File { .. }));
    let json = serde_json::to_value(&body).unwrap();
    assert!(json.get("templatedFilePath").is_none());

    let cleared = Body::file("/tpl/resp.vm")
        .with_templated_file_path(true)
        .with_templated_file_path(false);
    assert!(serde_json::to_value(&cleared)
        .unwrap()
        .get("templatedFilePath")
        .is_none());
}

// ---------------------------------------------------------------------------
// Expectation with template actions
// ---------------------------------------------------------------------------

#[test]
fn test_expectation_with_response_template() {
    let expectation = Expectation::new(HttpRequest::new().path("/templated"))
        .respond_template(HttpTemplate::new("VELOCITY", "{ \"statusCode\": 200 }"));

    let json = serde_json::to_value(&expectation).unwrap();
    assert_eq!(json["httpResponseTemplate"]["templateType"], "VELOCITY");
    assert_eq!(
        json["httpResponseTemplate"]["template"],
        "{ \"statusCode\": 200 }"
    );
    assert!(json.get("httpResponse").is_none());
    assert!(json.get("httpForwardTemplate").is_none());
}

#[test]
fn test_expectation_with_response_template_parameters() {
    let mut parameters = serde_json::Map::new();
    parameters.insert("greeting".into(), serde_json::json!("hello"));
    parameters.insert("names".into(), serde_json::json!(["ann", "bob"]));
    parameters.insert("limits".into(), serde_json::json!({"max": 5, "none": null}));
    let expectation = Expectation::new(HttpRequest::new().path("/templated"))
        .respond_template_with_parameters(
            HttpTemplate::new("MUSTACHE", "{{ parameters.greeting }}").primary(true),
            parameters.clone(),
        );

    let json = serde_json::to_value(&expectation).unwrap();
    assert_eq!(json["httpResponseTemplate"]["templateType"], "MUSTACHE");
    assert_eq!(json["httpResponseTemplate"]["primary"], true);
    assert_eq!(
        json["httpResponseTemplate"]["parameters"],
        serde_json::Value::Object(parameters.clone())
    );
    assert!(json.get("httpResponseTemplate.parameters").is_none());
    assert_eq!(
        expectation
            .http_response_template
            .as_ref()
            .unwrap()
            .template_type
            .as_deref(),
        Some("MUSTACHE")
    );
    assert_eq!(
        expectation.response_template_parameters(),
        Some(&parameters)
    );

    // replacing the template drops its parameters
    let replaced = expectation
        .clone()
        .respond_template(HttpTemplate::new("VELOCITY", "x"));
    assert!(replaced.response_template_parameters().is_none());
    assert!(
        serde_json::to_value(&replaced).unwrap()["httpResponseTemplate"]
            .get("parameters")
            .is_none()
    );

    let forward = Expectation::new(HttpRequest::new().path("/f")).forward_template_with_parameters(
        HttpTemplate::new("VELOCITY", "x"),
        serde_json::Map::new(),
    );
    let forward_json = serde_json::to_value(&forward).unwrap();
    assert_eq!(forward_json["httpForwardTemplate"]["template"], "x");
    assert!(forward_json["httpForwardTemplate"]
        .get("parameters")
        .is_none());
}

#[test]
fn test_template_parameters_survive_deserialize_then_serialize() {
    let wire = serde_json::json!({
        "httpRequest": { "path": "/greet" },
        "httpResponseTemplate": {
            "templateType": "MUSTACHE",
            "template": "{{ parameters.greeting }}",
            "parameters": { "greeting": "hello", "names": ["ann", null], "limits": { "max": 5 } }
        },
        "httpForwardTemplate": {
            "templateType": "VELOCITY",
            "template": "x",
            "parameters": { "target": "upstream" }
        }
    });
    let expectation: Expectation = serde_json::from_value(wire.clone()).unwrap();
    assert_eq!(
        expectation
            .http_response_template
            .as_ref()
            .unwrap()
            .template
            .as_deref(),
        Some("{{ parameters.greeting }}")
    );
    assert_eq!(
        expectation.response_template_parameters().unwrap()["greeting"],
        "hello"
    );
    assert_eq!(
        expectation.forward_template_parameters().unwrap()["target"],
        "upstream"
    );
    assert_eq!(serde_json::to_value(&expectation).unwrap(), wire);
    let text = serde_json::to_string(&expectation).unwrap();
    let again: Expectation = serde_json::from_str(&text).unwrap();
    assert_eq!(again, expectation);

    // a list of expectations, as retrieve returns, and an override's response template
    let override_wire = serde_json::json!([{
        "httpRequest": { "path": "/o" },
        "httpOverrideForwardedRequest": {
            "responseTemplate": { "templateType": "VELOCITY", "template": "y", "parameters": { "a": 1 } }
        }
    }]);
    let list: Vec<Expectation> = serde_json::from_value(override_wire.clone()).unwrap();
    assert_eq!(
        list[0]
            .http_override_forwarded_request
            .as_ref()
            .unwrap()
            .response_template_parameters()
            .unwrap()["a"],
        1
    );
    assert_eq!(serde_json::to_value(&list).unwrap(), override_wire);

    // without parameters the wire form is unchanged
    let plain = serde_json::json!({
        "httpRequest": { "path": "/p" },
        "httpResponseTemplate": { "templateType": "VELOCITY", "template": "z" }
    });
    let plain_expectation: Expectation = serde_json::from_value(plain.clone()).unwrap();
    assert!(plain_expectation.extra.is_empty());
    assert_eq!(serde_json::to_value(&plain_expectation).unwrap(), plain);
}

#[test]
fn test_expectation_with_forward_template() {
    let expectation = Expectation::new(HttpRequest::new().path("/proxy")).forward_template(
        HttpTemplate::from_file("MUSTACHE", "/templates/forward.mustache"),
    );

    let json = serde_json::to_value(&expectation).unwrap();
    assert_eq!(json["httpForwardTemplate"]["templateType"], "MUSTACHE");
    assert_eq!(
        json["httpForwardTemplate"]["templateFile"],
        "/templates/forward.mustache"
    );
    assert!(json.get("httpResponseTemplate").is_none());
    assert!(json.get("httpForward").is_none());
}

#[test]
fn test_expectation_with_response_template_and_template_file() {
    let expectation = Expectation::new(HttpRequest::new().path("/t")).respond_template(
        HttpTemplate::new("VELOCITY", "fallback").template_file("/path/to/template.vm"),
    );

    let json = serde_json::to_value(&expectation).unwrap();
    assert_eq!(json["httpResponseTemplate"]["templateType"], "VELOCITY");
    assert_eq!(json["httpResponseTemplate"]["template"], "fallback");
    assert_eq!(
        json["httpResponseTemplate"]["templateFile"],
        "/path/to/template.vm"
    );
}

#[test]
fn test_expectation_template_roundtrip() {
    let json_str = r#"{
        "httpRequest": {"path": "/api"},
        "httpResponseTemplate": {
            "templateType": "VELOCITY",
            "template": "body here",
            "templateFile": "/file.vm"
        }
    }"#;
    let exp: Expectation = serde_json::from_str(json_str).unwrap();
    assert_eq!(
        exp.http_request.as_ref().unwrap().path,
        Some("/api".to_string())
    );
    let tmpl = exp.http_response_template.unwrap();
    assert_eq!(tmpl.template_type, Some("VELOCITY".to_string()));
    assert_eq!(tmpl.template, Some("body here".to_string()));
    assert_eq!(tmpl.template_file, Some("/file.vm".to_string()));
}

// ---------------------------------------------------------------------------
// HttpRequest file_body builder
// ---------------------------------------------------------------------------

#[test]
fn test_request_file_body_builder() {
    let req = HttpRequest::new().file_body("/data/request.json");
    let json = serde_json::to_value(&req).unwrap();
    assert_eq!(json["body"]["type"], "FILE");
    assert_eq!(json["body"]["filePath"], "/data/request.json");
}

#[test]
fn test_request_body_value_builder() {
    let body = Body::file("/data/resp.vm")
        .with_content_type("text/html")
        .with_template_type("VELOCITY");
    let req = HttpRequest::new().body_value(body);
    let json = serde_json::to_value(&req).unwrap();
    assert_eq!(json["body"]["type"], "FILE");
    assert_eq!(json["body"]["filePath"], "/data/resp.vm");
    assert_eq!(json["body"]["contentType"], "text/html");
    assert_eq!(json["body"]["templateType"], "VELOCITY");
}

// ---------------------------------------------------------------------------
// RetrieveType / ClearType / RetrieveFormat string values
// ---------------------------------------------------------------------------

#[test]
fn test_retrieve_type_strings() {
    assert_eq!(RetrieveType::Requests.as_str(), "REQUESTS");
    assert_eq!(
        RetrieveType::ActiveExpectations.as_str(),
        "ACTIVE_EXPECTATIONS"
    );
    assert_eq!(
        RetrieveType::RecordedExpectations.as_str(),
        "RECORDED_EXPECTATIONS"
    );
    assert_eq!(RetrieveType::Logs.as_str(), "LOGS");
    assert_eq!(RetrieveType::RequestResponses.as_str(), "REQUEST_RESPONSES");
}

#[test]
fn test_retrieve_format_strings() {
    assert_eq!(RetrieveFormat::Json.as_str(), "JSON");
    assert_eq!(RetrieveFormat::LogEntries.as_str(), "LOG_ENTRIES");
}

#[test]
fn test_clear_type_strings() {
    assert_eq!(ClearType::All.as_str(), "ALL");
    assert_eq!(ClearType::Log.as_str(), "LOG");
    assert_eq!(ClearType::Expectations.as_str(), "EXPECTATIONS");
}

// ---------------------------------------------------------------------------
// HttpSseResponse (Server-Sent Events) tests
// ---------------------------------------------------------------------------

#[test]
fn test_sse_response_serialization() {
    let sse = HttpSseResponse::new()
        .status_code(200)
        .header("Content-Type", "text/event-stream")
        .event(
            SseEvent::new()
                .event("message")
                .data("hello")
                .id("1")
                .retry(3000),
        )
        .close_connection(true);
    let json = serde_json::to_value(&sse).unwrap();
    assert_eq!(json["statusCode"], 200);
    assert_eq!(
        json["headers"]["Content-Type"],
        serde_json::json!(["text/event-stream"])
    );
    let events = json["events"].as_array().unwrap();
    assert_eq!(events.len(), 1);
    assert_eq!(events[0]["event"], "message");
    assert_eq!(events[0]["data"], "hello");
    assert_eq!(events[0]["id"], "1");
    assert_eq!(events[0]["retry"], 3000);
    assert_eq!(json["closeConnection"], true);
}

#[test]
fn test_sse_event_omits_unset_fields() {
    let event = SseEvent::new().data("only-data");
    let json = serde_json::to_value(&event).unwrap();
    assert_eq!(json["data"], "only-data");
    assert!(json.get("event").is_none());
    assert!(json.get("id").is_none());
    assert!(json.get("retry").is_none());
    assert!(json.get("comment").is_none());
    assert!(json.get("delay").is_none());
}

#[test]
fn test_sse_event_comment_round_trip() {
    let event = SseEvent::new().comment("heartbeat");
    let json = serde_json::to_value(&event).unwrap();
    assert_eq!(json, serde_json::json!({"comment": "heartbeat"}));
    let decoded: SseEvent =
        serde_json::from_value(serde_json::json!({"comment": "a\nb", "data": "x"})).unwrap();
    assert_eq!(decoded, SseEvent::new().comment("a\nb").data("x"));
}

#[test]
fn test_sse_response_end_of_stream_round_trip() {
    let response = HttpSseResponse::new()
        .event(SseEvent::new().data("last"))
        .close_delay(Delay::seconds(15))
        .keep_stream_open(true);
    let json = serde_json::to_value(&response).unwrap();
    assert_eq!(json["closeDelay"], serde_json::json!({"timeUnit": "SECONDS", "value": 15}));
    assert_eq!(json["keepStreamOpen"], serde_json::json!(true));
    let decoded: HttpSseResponse = serde_json::from_value(json).unwrap();
    assert_eq!(decoded, response);
    let unset = serde_json::to_value(HttpSseResponse::new()).unwrap();
    assert!(unset.get("closeDelay").is_none() && unset.get("keepStreamOpen").is_none());
}

#[test]
fn test_expectation_with_sse_response() {
    let expectation = Expectation::new(HttpRequest::new().method("GET").path("/events"))
        .respond_sse(HttpSseResponse::new().event(SseEvent::new().data("tick")));
    let json = serde_json::to_value(&expectation).unwrap();
    assert_eq!(json["httpSseResponse"]["events"][0]["data"], "tick");
    assert!(json.get("httpResponse").is_none());
}

// ---------------------------------------------------------------------------
// HttpWebSocketResponse tests
// ---------------------------------------------------------------------------

#[test]
fn test_websocket_response_text_message() {
    let ws = HttpWebSocketResponse::new()
        .subprotocol("chat")
        .message(WebSocketMessage::text("hello"))
        .close_connection(true);
    let json = serde_json::to_value(&ws).unwrap();
    assert_eq!(json["subprotocol"], "chat");
    let messages = json["messages"].as_array().unwrap();
    assert_eq!(messages.len(), 1);
    assert_eq!(messages[0]["text"], "hello");
    assert!(messages[0].get("binary").is_none());
    assert_eq!(json["closeConnection"], true);
}

#[test]
fn test_websocket_binary_message_is_base64() {
    // bytes [0x01, 0x02, 0x03] -> base64 "AQID"
    let msg = WebSocketMessage::binary([0x01, 0x02, 0x03]);
    let json = serde_json::to_value(&msg).unwrap();
    assert_eq!(json["binary"], "AQID");
    assert!(json.get("text").is_none());
}

#[test]
fn test_websocket_binary_base64_passthrough() {
    let msg = WebSocketMessage::binary_base64("AQID");
    let json = serde_json::to_value(&msg).unwrap();
    assert_eq!(json["binary"], "AQID");
}

#[test]
fn test_expectation_with_websocket_response() {
    let expectation = Expectation::new(HttpRequest::new().path("/ws"))
        .respond_web_socket(HttpWebSocketResponse::new().message(WebSocketMessage::text("hi")));
    let json = serde_json::to_value(&expectation).unwrap();
    assert_eq!(json["httpWebSocketResponse"]["messages"][0]["text"], "hi");
}

// ---------------------------------------------------------------------------
// DnsResponse tests
// ---------------------------------------------------------------------------

#[test]
fn test_dns_response_serialization() {
    let dns = DnsResponse::new()
        .response_code("NOERROR")
        .answer_record(DnsRecord::a("example.com", "1.2.3.4").ttl(300));
    let json = serde_json::to_value(&dns).unwrap();
    assert_eq!(json["responseCode"], "NOERROR");
    let answers = json["answerRecords"].as_array().unwrap();
    assert_eq!(answers.len(), 1);
    assert_eq!(answers[0]["name"], "example.com");
    assert_eq!(answers[0]["type"], "A");
    assert_eq!(answers[0]["value"], "1.2.3.4");
    assert_eq!(answers[0]["ttl"], 300);
}

#[test]
fn test_dns_record_type_field_name() {
    // The Rust field is `record_type` but the wire key must be `type`.
    let record = DnsRecord::cname("www.example.com", "example.com");
    let json = serde_json::to_value(&record).unwrap();
    assert_eq!(json["type"], "CNAME");
    assert!(json.get("recordType").is_none());
}

#[test]
fn test_dns_srv_record_full_fields() {
    let record = DnsRecord::new()
        .name("_sip._tcp.example.com")
        .record_type("SRV")
        .priority(10)
        .weight(60)
        .port(5060)
        .value("sipserver.example.com")
        .dns_class("IN");
    let json = serde_json::to_value(&record).unwrap();
    assert_eq!(json["priority"], 10);
    assert_eq!(json["weight"], 60);
    assert_eq!(json["port"], 5060);
    assert_eq!(json["dnsClass"], "IN");
}

#[test]
fn test_expectation_with_dns_response() {
    let expectation = Expectation::new(HttpRequest::new())
        .respond_dns(DnsResponse::new().answer_record(DnsRecord::a("host", "10.0.0.1")));
    let json = serde_json::to_value(&expectation).unwrap();
    assert_eq!(json["dnsResponse"]["answerRecords"][0]["value"], "10.0.0.1");
}

// ---------------------------------------------------------------------------
// BinaryResponse tests
// ---------------------------------------------------------------------------

#[test]
fn test_binary_response_is_base64() {
    // bytes [0xDE, 0xAD, 0xBE, 0xEF] -> base64 "3q2+7w=="
    let resp = BinaryResponse::from_bytes([0xDE, 0xAD, 0xBE, 0xEF]);
    let json = serde_json::to_value(&resp).unwrap();
    assert_eq!(json["binaryData"], "3q2+7w==");
}

#[test]
fn test_binary_response_from_base64_passthrough() {
    let resp = BinaryResponse::from_base64("3q2+7w==");
    let json = serde_json::to_value(&resp).unwrap();
    assert_eq!(json["binaryData"], "3q2+7w==");
}

#[test]
fn test_binary_response_upstream_round_trip() {
    let resp = BinaryResponse::from_base64("VXBzdHJlYW0=").upstream(BinaryUpstream::ForwardAndReplace);
    let json = serde_json::to_value(&resp).unwrap();
    assert_eq!(json, serde_json::json!({"binaryData": "VXBzdHJlYW0=", "upstream": "FORWARD_AND_REPLACE"}));
    let decoded: BinaryResponse = serde_json::from_value(json).unwrap();
    assert_eq!(decoded.upstream, Some(BinaryUpstream::ForwardAndReplace));
    assert!(decoded.extra.is_empty());
    for (value, name) in [
        (BinaryUpstream::AnswerOnly, "ANSWER_ONLY"),
        (BinaryUpstream::AnswerAndForward, "ANSWER_AND_FORWARD"),
        (BinaryUpstream::ForwardAndReplace, "FORWARD_AND_REPLACE"),
    ] {
        assert_eq!(serde_json::to_value(value).unwrap(), name);
    }
    let unset = serde_json::to_value(BinaryResponse::from_base64("AQID")).unwrap();
    assert!(unset.get("upstream").is_none());
}

#[test]
fn test_expectation_with_binary_response() {
    let expectation = Expectation::new(HttpRequest::new().path("/raw"))
        .respond_binary(BinaryResponse::from_bytes([0x00, 0xFF]));
    let json = serde_json::to_value(&expectation).unwrap();
    assert_eq!(json["binaryResponse"]["binaryData"], "AP8=");
}

// ---------------------------------------------------------------------------
// GrpcStreamResponse tests
// ---------------------------------------------------------------------------

#[test]
fn test_grpc_stream_response_serialization() {
    let grpc = GrpcStreamResponse::new()
        .status_name("OK")
        .status_message("done")
        .header("grpc-meta", "v")
        .message(GrpcStreamMessage::json("{\"id\":1}"))
        .message(GrpcStreamMessage::json("{\"id\":2}").delay(Delay::milliseconds(50)))
        .close_connection(true);
    let json = serde_json::to_value(&grpc).unwrap();
    assert_eq!(json["statusName"], "OK");
    assert_eq!(json["statusMessage"], "done");
    assert_eq!(json["headers"]["grpc-meta"], serde_json::json!(["v"]));
    let messages = json["messages"].as_array().unwrap();
    assert_eq!(messages.len(), 2);
    assert_eq!(messages[0]["json"], "{\"id\":1}");
    assert_eq!(messages[1]["delay"]["timeUnit"], "MILLISECONDS");
    assert_eq!(messages[1]["delay"]["value"], 50);
    assert_eq!(json["closeConnection"], true);
}

#[test]
fn test_expectation_with_grpc_stream_response() {
    let expectation = Expectation::new(HttpRequest::new().path("/grpc"))
        .respond_grpc_stream(GrpcStreamResponse::new().message(GrpcStreamMessage::json("{}")));
    let json = serde_json::to_value(&expectation).unwrap();
    assert_eq!(json["grpcStreamResponse"]["messages"][0]["json"], "{}");
}

// ---------------------------------------------------------------------------
// `respond_with_*` fluent-alias existence (compile-time guard)
//
// The Python/PHP/.NET clients expose the advanced response builders under
// `respond_with_*` names. This test references each Rust alias inside a
// never-called closure so it only compiles if every alias exists with the
// expected argument type — a regression guard requiring no running server.
// (The aliases consume the chain and call `upsert`, so they cannot be
// invoked without a live server; referencing them is enough to lock the
// public surface.)
// ---------------------------------------------------------------------------

#[test]
fn test_respond_with_aliases_exist_on_fluent_chain() {
    // These helpers are intentionally never invoked; they exist purely so the
    // compiler type-checks that each `respond_with_*` alias is present on
    // `ForwardChainExpectation` and accepts the matching response type. The
    // aliases consume the chain and call `upsert`, so they cannot run without
    // a live server — referencing them is enough to lock the public surface.
    fn _sse(c: ForwardChainExpectation<'_>) -> Result<Vec<Expectation>> {
        c.respond_with_sse(HttpSseResponse::new())
    }
    fn _ws(c: ForwardChainExpectation<'_>) -> Result<Vec<Expectation>> {
        c.respond_with_web_socket(HttpWebSocketResponse::new())
    }
    fn _dns(c: ForwardChainExpectation<'_>) -> Result<Vec<Expectation>> {
        c.respond_with_dns(DnsResponse::new())
    }
    fn _bin(c: ForwardChainExpectation<'_>) -> Result<Vec<Expectation>> {
        c.respond_with_binary(BinaryResponse::new())
    }
    fn _grpc(c: ForwardChainExpectation<'_>) -> Result<Vec<Expectation>> {
        c.respond_with_grpc_stream(GrpcStreamResponse::new())
    }
}

// ---------------------------------------------------------------------------
// OpenApiExpectation tests
// ---------------------------------------------------------------------------

#[test]
fn test_openapi_expectation_serialization() {
    let exp = OpenApiExpectation::new("https://example.com/petstore.yaml")
        .operation("listPets", "200")
        .operation("showPetById", "default")
        .context_path_prefix("/v1");
    let json = serde_json::to_value(&exp).unwrap();
    assert_eq!(
        json["specUrlOrPayload"],
        "https://example.com/petstore.yaml"
    );
    assert_eq!(json["operationsAndResponses"]["listPets"], "200");
    assert_eq!(json["operationsAndResponses"]["showPetById"], "default");
    assert_eq!(json["contextPathPrefix"], "/v1");
}

#[test]
fn test_openapi_expectation_minimal() {
    // Only the spec is required; operations and context path are omitted.
    let exp = OpenApiExpectation::new("file:///specs/api.json");
    let json = serde_json::to_value(&exp).unwrap();
    assert_eq!(json["specUrlOrPayload"], "file:///specs/api.json");
    assert!(json.get("operationsAndResponses").is_none());
    assert!(json.get("contextPathPrefix").is_none());
}

#[test]
fn test_openapi_expectation_inline_payload() {
    let payload = r#"{"openapi":"3.0.0","info":{"title":"x","version":"1"}}"#;
    let exp = OpenApiExpectation::new(payload);
    let json = serde_json::to_value(&exp).unwrap();
    assert_eq!(json["specUrlOrPayload"], payload);
}

#[test]
fn test_retrieve_format_code_query_values() {
    assert_eq!(RetrieveFormat::Java.as_str(), "JAVA");
    assert_eq!(RetrieveFormat::JavaScript.as_str(), "JAVASCRIPT");
    assert_eq!(RetrieveFormat::Python.as_str(), "PYTHON");
    assert_eq!(RetrieveFormat::Go.as_str(), "GO");
    assert_eq!(RetrieveFormat::CSharp.as_str(), "CSHARP");
    assert_eq!(RetrieveFormat::Ruby.as_str(), "RUBY");
    assert_eq!(RetrieveFormat::Rust.as_str(), "RUST");
    assert_eq!(RetrieveFormat::Php.as_str(), "PHP");
}
