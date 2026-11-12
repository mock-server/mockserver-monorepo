//! Unit tests for breakpoint models, DTOs, message routing, and envelope format.
//! These run with NO server — they test serialization, deserialization, header
//! extraction, and the decision helpers.

use base64::{engine::general_purpose::STANDARD as BASE64, Engine};
use mockserver_client::*;
use std::collections::HashMap;
use std::sync::{Arc, Mutex};

// ---------------------------------------------------------------------------
// BreakpointMatcherRegistration serialization
// ---------------------------------------------------------------------------

#[test]
fn test_breakpoint_matcher_registration_serializes() {
    let reg = BreakpointMatcherRegistration {
        http_request: HttpRequest::new().method("GET").path("/api/.*"),
        phases: vec!["REQUEST".to_string(), "RESPONSE".to_string()],
        client_id: Some("my-client".to_string()),
    };
    let json = serde_json::to_value(&reg).unwrap();
    assert_eq!(json["httpRequest"]["method"], "GET");
    assert_eq!(json["httpRequest"]["path"], "/api/.*");
    assert_eq!(json["phases"][0], "REQUEST");
    assert_eq!(json["phases"][1], "RESPONSE");
    assert_eq!(json["clientId"], "my-client");
}

#[test]
fn test_breakpoint_matcher_registration_omits_null_client_id() {
    let reg = BreakpointMatcherRegistration {
        http_request: HttpRequest::new().path("/test"),
        phases: vec!["REQUEST".to_string()],
        client_id: None,
    };
    let json = serde_json::to_value(&reg).unwrap();
    assert!(json.get("clientId").is_none());
}

// ---------------------------------------------------------------------------
// BreakpointMatcherResponse deserialization
// ---------------------------------------------------------------------------

#[test]
fn test_breakpoint_matcher_response_deserializes() {
    let json = r#"{"id":"bp-123","phases":["REQUEST","RESPONSE"]}"#;
    let result: BreakpointMatcherResponse = serde_json::from_str(json).unwrap();
    assert_eq!(result.id, "bp-123");
    assert_eq!(result.phases, vec!["REQUEST", "RESPONSE"]);
}

// ---------------------------------------------------------------------------
// BreakpointMatcherList deserialization
// ---------------------------------------------------------------------------

#[test]
fn test_breakpoint_matcher_list_deserializes() {
    let json = r#"{"matchers":[{"id":"bp-1","httpRequest":{"path":"/test"},"phases":["REQUEST"],"clientId":"c1"}]}"#;
    let list: BreakpointMatcherList = serde_json::from_str(json).unwrap();
    assert_eq!(list.matchers.len(), 1);
    assert_eq!(list.matchers[0].id, "bp-1");
    assert_eq!(list.matchers[0].client_id, Some("c1".to_string()));
}

// ---------------------------------------------------------------------------
// PausedStreamFrame
// ---------------------------------------------------------------------------

#[test]
fn test_paused_stream_frame_deserializes() {
    let body_b64 = BASE64.encode(b"hello stream");
    let json = format!(
        r#"{{
            "correlationId": "corr-1",
            "streamId": "stream-1",
            "sequenceNumber": 3,
            "direction": "OUTBOUND",
            "phase": "RESPONSE_STREAM",
            "body": "{body_b64}",
            "requestMethod": "GET",
            "requestPath": "/data",
            "breakpointId": "bp-42"
        }}"#
    );
    let frame: PausedStreamFrame = serde_json::from_str(&json).unwrap();
    assert_eq!(frame.correlation_id, "corr-1");
    assert_eq!(frame.stream_id, "stream-1");
    assert_eq!(frame.sequence_number, 3);
    assert_eq!(frame.direction, "OUTBOUND");
    assert_eq!(frame.phase, "RESPONSE_STREAM");
    assert_eq!(frame.breakpoint_id, Some("bp-42".to_string()));
    assert_eq!(frame.body_bytes().unwrap(), b"hello stream");
}

#[test]
fn test_paused_stream_frame_invalid_body_is_client_decode_error() {
    let frame: PausedStreamFrame =
        serde_json::from_str(r#"{"correlationId": "corr-1", "body": "not base64!"}"#).unwrap();

    let err = frame.body_bytes().unwrap_err();

    assert!(matches!(err, Error::Decode(_)), "expected Error::Decode, got {err:?}");
    assert!(err.to_string().starts_with("decode error: "), "{err}");
    let source = std::error::Error::source(&err).expect("decoder error is the source");
    assert!(err.to_string().ends_with(&source.to_string()), "{err} / {source}");
}

#[test]
fn test_client_error_is_send_and_sync() {
    fn assert_send_sync<T: Send + Sync + 'static>() {}
    assert_send_sync::<Error>();
}

// ---------------------------------------------------------------------------
// StreamFrameDecision
// ---------------------------------------------------------------------------

#[test]
fn test_decision_continue() {
    let d = StreamFrameDecision::continue_frame("corr-1");
    let json = serde_json::to_value(&d).unwrap();
    assert_eq!(json["correlationId"], "corr-1");
    assert_eq!(json["action"], "CONTINUE");
    assert!(json.get("body").is_none());
}

#[test]
fn test_decision_modify() {
    let d = StreamFrameDecision::modify("corr-2", b"new data");
    let json = serde_json::to_value(&d).unwrap();
    assert_eq!(json["action"], "MODIFY");
    let body_bytes = BASE64
        .decode(json["body"].as_str().unwrap())
        .unwrap();
    assert_eq!(body_bytes, b"new data");
}

#[test]
fn test_decision_drop() {
    let d = StreamFrameDecision::drop_frame("corr-3");
    let json = serde_json::to_value(&d).unwrap();
    assert_eq!(json["action"], "DROP");
}

#[test]
fn test_decision_inject() {
    let d = StreamFrameDecision::inject("corr-4", b"extra");
    let json = serde_json::to_value(&d).unwrap();
    assert_eq!(json["action"], "INJECT");
    let body_bytes = BASE64
        .decode(json["body"].as_str().unwrap())
        .unwrap();
    assert_eq!(body_bytes, b"extra");
}

#[test]
fn test_decision_close() {
    let d = StreamFrameDecision::close("corr-5");
    let json = serde_json::to_value(&d).unwrap();
    assert_eq!(json["action"], "CLOSE");
}

// ---------------------------------------------------------------------------
// WsEnvelope (double-encoded format)
// ---------------------------------------------------------------------------

#[test]
fn test_ws_envelope_serializes_double_encoded() {
    let env = WsEnvelope {
        msg_type: "org.mockserver.model.HttpRequest".to_string(),
        value: r#"{"method":"GET","path":"/test"}"#.to_string(),
    };
    let json = serde_json::to_value(&env).unwrap();
    assert_eq!(json["type"], "org.mockserver.model.HttpRequest");

    // Value is a JSON string (double-encoded)
    let value_str = json["value"].as_str().unwrap();
    let inner: serde_json::Value = serde_json::from_str(value_str).unwrap();
    assert_eq!(inner["method"], "GET");
}

#[test]
fn test_ws_envelope_deserializes() {
    let json = r#"{"type":"org.mockserver.serialization.model.WebSocketClientIdDTO","value":"{\"clientId\":\"test-id\"}"}"#;
    let env: WsEnvelope = serde_json::from_str(json).unwrap();
    assert_eq!(
        env.msg_type,
        "org.mockserver.serialization.model.WebSocketClientIdDTO"
    );
    assert!(env.value.contains("test-id"));
}

// ---------------------------------------------------------------------------
// Header extraction helpers
// ---------------------------------------------------------------------------

#[test]
fn test_extract_header_array_value() {
    let obj = serde_json::json!({
        "headers": {
            "X-Custom": ["val1", "val2"]
        }
    });
    assert_eq!(extract_header(&obj, "X-Custom"), Some("val1".to_string()));
}

#[test]
fn test_extract_header_string_value() {
    let obj = serde_json::json!({
        "headers": {
            "X-Custom": "single"
        }
    });
    assert_eq!(extract_header(&obj, "X-Custom"), Some("single".to_string()));
}

#[test]
fn test_extract_header_case_insensitive() {
    let obj = serde_json::json!({
        "headers": {
            "websocketcorrelationid": ["corr-123"]
        }
    });
    assert_eq!(
        extract_header(&obj, "WebSocketCorrelationId"),
        Some("corr-123".to_string())
    );
}

#[test]
fn test_extract_header_no_headers() {
    let obj = serde_json::json!({ "method": "GET" });
    assert_eq!(extract_header(&obj, "X-Missing"), None);
}

// ---------------------------------------------------------------------------
// set_header
// ---------------------------------------------------------------------------

#[test]
fn test_set_header_creates_headers() {
    let mut obj = serde_json::json!({ "method": "GET" });
    set_header(&mut obj, "WebSocketCorrelationId", "corr-1");
    assert_eq!(
        extract_header(&obj, "WebSocketCorrelationId"),
        Some("corr-1".to_string())
    );
}

#[test]
fn test_set_header_updates_existing() {
    let mut obj = serde_json::json!({
        "headers": {
            "WebSocketCorrelationId": ["old"]
        }
    });
    set_header(&mut obj, "WebSocketCorrelationId", "new");
    assert_eq!(
        extract_header(&obj, "WebSocketCorrelationId"),
        Some("new".to_string())
    );
}

// ---------------------------------------------------------------------------
// Phase constants
// ---------------------------------------------------------------------------

#[test]
fn test_phase_constants() {
    assert_eq!(phase::REQUEST, "REQUEST");
    assert_eq!(phase::RESPONSE, "RESPONSE");
    assert_eq!(phase::RESPONSE_STREAM, "RESPONSE_STREAM");
    assert_eq!(phase::INBOUND_STREAM, "INBOUND_STREAM");
}

// ---------------------------------------------------------------------------
// BreakpointMatcherEntry deserialization
// ---------------------------------------------------------------------------

#[test]
fn test_breakpoint_matcher_entry_without_client_id() {
    let json = r#"{"id":"bp-1","httpRequest":{"path":"/test"},"phases":["REQUEST"]}"#;
    let entry: BreakpointMatcherEntry = serde_json::from_str(json).unwrap();
    assert_eq!(entry.id, "bp-1");
    assert!(entry.client_id.is_none());
}

// ---------------------------------------------------------------------------
// StreamFrameDecision serialization round-trip
// ---------------------------------------------------------------------------

#[test]
fn test_decision_round_trip() {
    let original = StreamFrameDecision::modify("corr-rt", b"payload");
    let json = serde_json::to_string(&original).unwrap();
    let restored: StreamFrameDecision = serde_json::from_str(&json).unwrap();
    assert_eq!(restored.correlation_id, "corr-rt");
    assert_eq!(restored.action, "MODIFY");
    let body_bytes = BASE64
        .decode(restored.body.unwrap())
        .unwrap();
    assert_eq!(body_bytes, b"payload");
}

// ---------------------------------------------------------------------------
// Routing function tests (no live WebSocket needed)
// ---------------------------------------------------------------------------

fn make_request_json(breakpoint_id: &str, correlation_id: &str) -> String {
    serde_json::json!({
        "method": "GET",
        "path": "/test",
        "headers": {
            "WebSocketCorrelationId": [correlation_id],
            "X-MockServer-BreakpointId": [breakpoint_id]
        }
    })
    .to_string()
}

fn make_response_json(breakpoint_id: &str, correlation_id: &str) -> String {
    serde_json::json!({
        "httpRequest": {
            "method": "GET",
            "path": "/test",
            "headers": {
                "WebSocketCorrelationId": [correlation_id],
                "X-MockServer-BreakpointId": [breakpoint_id]
            }
        },
        "httpResponse": {
            "statusCode": 200,
            "body": "hello"
        }
    })
    .to_string()
}

fn make_stream_frame_json(breakpoint_id: &str, correlation_id: &str) -> String {
    serde_json::json!({
        "correlationId": correlation_id,
        "streamId": "stream-1",
        "sequenceNumber": 0,
        "direction": "OUTBOUND",
        "phase": "RESPONSE_STREAM",
        "body": BASE64.encode(b"original"),
        "breakpointId": breakpoint_id
    })
    .to_string()
}

#[test]
fn test_route_request_dispatches_to_correct_handler() {
    let handlers: Arc<Mutex<HashMap<String, BreakpointRequestHandler>>> =
        Arc::new(Mutex::new(HashMap::new()));

    let called = Arc::new(Mutex::new(false));
    let called_clone = Arc::clone(&called);

    handlers.lock().unwrap().insert(
        "bp-42".to_string(),
        Box::new(move |req| {
            *called_clone.lock().unwrap() = true;
            Some(req)
        }),
    );

    let json = make_request_json("bp-42", "corr-1");
    let result = route_request(&json, &handlers);

    assert!(result.is_some());
    let (type_name, value) = result.unwrap();
    assert_eq!(type_name, "org.mockserver.model.HttpRequest");
    assert_eq!(extract_header(&value, "WebSocketCorrelationId"), Some("corr-1".to_string()));
    assert!(*called.lock().unwrap());
}

#[test]
fn test_route_request_per_breakpoint_id_selection() {
    let handlers: Arc<Mutex<HashMap<String, BreakpointRequestHandler>>> =
        Arc::new(Mutex::new(HashMap::new()));

    let ids = Arc::new(Mutex::new(Vec::<String>::new()));

    let ids_a = Arc::clone(&ids);
    handlers.lock().unwrap().insert(
        "bp-A".to_string(),
        Box::new(move |req| {
            ids_a.lock().unwrap().push("A".to_string());
            Some(req)
        }),
    );

    let ids_b = Arc::clone(&ids);
    handlers.lock().unwrap().insert(
        "bp-B".to_string(),
        Box::new(move |req| {
            ids_b.lock().unwrap().push("B".to_string());
            Some(req)
        }),
    );

    // Dispatch to B first, then A
    let json_b = make_request_json("bp-B", "corr-B");
    route_request(&json_b, &handlers);

    let json_a = make_request_json("bp-A", "corr-A");
    route_request(&json_a, &handlers);

    let handled = ids.lock().unwrap();
    assert_eq!(*handled, vec!["B", "A"]);
}

#[test]
fn test_route_request_correlation_id_echoed() {
    let handlers: Arc<Mutex<HashMap<String, BreakpointRequestHandler>>> =
        Arc::new(Mutex::new(HashMap::new()));

    handlers.lock().unwrap().insert(
        "bp-echo".to_string(),
        Box::new(Some),
    );

    let json = make_request_json("bp-echo", "corr-echo-test");
    let (_, value) = route_request(&json, &handlers).unwrap();
    assert_eq!(
        extract_header(&value, "WebSocketCorrelationId"),
        Some("corr-echo-test".to_string())
    );
}

#[test]
fn test_route_request_abort_with_response() {
    let handlers: Arc<Mutex<HashMap<String, BreakpointRequestHandler>>> =
        Arc::new(Mutex::new(HashMap::new()));

    handlers.lock().unwrap().insert(
        "bp-abort".to_string(),
        Box::new(|_req| {
            Some(serde_json::json!({
                "statusCode": 502,
                "reasonPhrase": "Bad Gateway"
            }))
        }),
    );

    let json = make_request_json("bp-abort", "corr-abort");
    let (type_name, _) = route_request(&json, &handlers).unwrap();
    assert_eq!(type_name, "org.mockserver.model.HttpResponse");
}

#[test]
fn test_route_request_auto_continue_no_handler() {
    let handlers: Arc<Mutex<HashMap<String, BreakpointRequestHandler>>> =
        Arc::new(Mutex::new(HashMap::new()));

    let json = make_request_json("unknown-bp", "corr-miss");
    let (type_name, value) = route_request(&json, &handlers).unwrap();
    assert_eq!(type_name, "org.mockserver.model.HttpRequest");
    // Should echo the correlation id even without a handler
    assert_eq!(
        extract_header(&value, "WebSocketCorrelationId"),
        Some("corr-miss".to_string())
    );
}

#[test]
fn test_route_request_panic_auto_continues() {
    let handlers: Arc<Mutex<HashMap<String, BreakpointRequestHandler>>> =
        Arc::new(Mutex::new(HashMap::new()));

    handlers.lock().unwrap().insert(
        "bp-panic".to_string(),
        Box::new(|_req| {
            panic!("test panic");
        }),
    );

    let json = make_request_json("bp-panic", "corr-panic");
    let result = route_request(&json, &handlers);
    assert!(result.is_some());
    let (type_name, _) = result.unwrap();
    // On panic, auto-continues with original request (not a response)
    assert_eq!(type_name, "org.mockserver.model.HttpRequest");
}

#[test]
fn test_route_response_dispatches_to_handler() {
    let handlers: Arc<Mutex<HashMap<String, BreakpointResponseHandler>>> =
        Arc::new(Mutex::new(HashMap::new()));

    let called = Arc::new(Mutex::new(false));
    let called_clone = Arc::clone(&called);

    handlers.lock().unwrap().insert(
        "bp-resp".to_string(),
        Box::new(move |_req, resp| {
            *called_clone.lock().unwrap() = true;
            Some(resp)
        }),
    );

    let json = make_response_json("bp-resp", "corr-resp");
    let result = route_response(&json, &handlers);

    assert!(result.is_some());
    let (type_name, value) = result.unwrap();
    assert_eq!(type_name, "org.mockserver.model.HttpResponse");
    assert_eq!(
        extract_header(&value, "WebSocketCorrelationId"),
        Some("corr-resp".to_string())
    );
    assert!(*called.lock().unwrap());
}

#[test]
fn test_route_response_auto_continue_no_handler() {
    let handlers: Arc<Mutex<HashMap<String, BreakpointResponseHandler>>> =
        Arc::new(Mutex::new(HashMap::new()));

    let json = make_response_json("unknown-bp", "corr-miss-resp");
    let (type_name, value) = route_response(&json, &handlers).unwrap();
    assert_eq!(type_name, "org.mockserver.model.HttpResponse");
    // Returns original response body
    assert_eq!(value["statusCode"], 200);
}

#[test]
fn test_route_response_panic_auto_continues() {
    let handlers: Arc<Mutex<HashMap<String, BreakpointResponseHandler>>> =
        Arc::new(Mutex::new(HashMap::new()));

    handlers.lock().unwrap().insert(
        "bp-panic-resp".to_string(),
        Box::new(|_req, _resp| {
            panic!("test panic in response handler");
        }),
    );

    let json = make_response_json("bp-panic-resp", "corr-panic-resp");
    let result = route_response(&json, &handlers);
    assert!(result.is_some());
    let (type_name, value) = result.unwrap();
    assert_eq!(type_name, "org.mockserver.model.HttpResponse");
    // Auto-continues with original response
    assert_eq!(value["statusCode"], 200);
}

#[test]
fn test_route_stream_frame_dispatches_to_handler() {
    let handlers: Arc<Mutex<HashMap<String, BreakpointStreamFrameHandler>>> =
        Arc::new(Mutex::new(HashMap::new()));

    handlers.lock().unwrap().insert(
        "bp-stream".to_string(),
        Box::new(|frame| {
            Some(StreamFrameDecision::modify(&frame.correlation_id, b"modified"))
        }),
    );

    let json = make_stream_frame_json("bp-stream", "corr-frame-1");
    let result = route_stream_frame(&json, &handlers);
    assert!(result.is_some());
    let (type_name, decision) = result.unwrap();
    assert_eq!(
        type_name,
        "org.mockserver.serialization.model.StreamFrameDecisionDTO"
    );
    assert_eq!(decision.action, "MODIFY");
    assert_eq!(decision.correlation_id, "corr-frame-1");
    let body_bytes = BASE64.decode(decision.body.unwrap()).unwrap();
    assert_eq!(body_bytes, b"modified");
}

#[test]
fn test_route_stream_frame_auto_continue_no_handler() {
    let handlers: Arc<Mutex<HashMap<String, BreakpointStreamFrameHandler>>> =
        Arc::new(Mutex::new(HashMap::new()));

    let json = make_stream_frame_json("unknown-bp", "corr-no-handler");
    let (_, decision) = route_stream_frame(&json, &handlers).unwrap();
    assert_eq!(decision.action, "CONTINUE");
    assert_eq!(decision.correlation_id, "corr-no-handler");
}

#[test]
fn test_route_stream_frame_panic_auto_continues() {
    let handlers: Arc<Mutex<HashMap<String, BreakpointStreamFrameHandler>>> =
        Arc::new(Mutex::new(HashMap::new()));

    handlers.lock().unwrap().insert(
        "bp-panic-stream".to_string(),
        Box::new(|_frame| {
            panic!("test panic in stream handler");
        }),
    );

    let json = make_stream_frame_json("bp-panic-stream", "corr-panic-stream");
    let result = route_stream_frame(&json, &handlers);
    assert!(result.is_some());
    let (_, decision) = result.unwrap();
    // Panic -> auto-continue
    assert_eq!(decision.action, "CONTINUE");
    assert_eq!(decision.correlation_id, "corr-panic-stream");
}

#[test]
fn test_route_stream_frame_all_actions() {
    // Verify each action type is correctly routed
    let handlers: Arc<Mutex<HashMap<String, BreakpointStreamFrameHandler>>> =
        Arc::new(Mutex::new(HashMap::new()));

    // DROP
    handlers.lock().unwrap().insert(
        "bp-drop".to_string(),
        Box::new(|frame| Some(StreamFrameDecision::drop_frame(&frame.correlation_id))),
    );
    let json = make_stream_frame_json("bp-drop", "corr-drop");
    let (_, decision) = route_stream_frame(&json, &handlers).unwrap();
    assert_eq!(decision.action, "DROP");

    // INJECT
    handlers.lock().unwrap().insert(
        "bp-inject".to_string(),
        Box::new(|frame| Some(StreamFrameDecision::inject(&frame.correlation_id, b"extra"))),
    );
    let json = make_stream_frame_json("bp-inject", "corr-inject");
    let (_, decision) = route_stream_frame(&json, &handlers).unwrap();
    assert_eq!(decision.action, "INJECT");

    // CLOSE
    handlers.lock().unwrap().insert(
        "bp-close".to_string(),
        Box::new(|frame| Some(StreamFrameDecision::close(&frame.correlation_id))),
    );
    let json = make_stream_frame_json("bp-close", "corr-close");
    let (_, decision) = route_stream_frame(&json, &handlers).unwrap();
    assert_eq!(decision.action, "CLOSE");
}

#[test]
fn test_route_request_vs_response_reply_distinction() {
    // A request handler returning a value without statusCode -> HttpRequest type
    let req_handlers: Arc<Mutex<HashMap<String, BreakpointRequestHandler>>> =
        Arc::new(Mutex::new(HashMap::new()));

    req_handlers.lock().unwrap().insert(
        "bp-dist".to_string(),
        Box::new(Some),
    );

    let json = make_request_json("bp-dist", "corr-dist");
    let (type_name, _) = route_request(&json, &req_handlers).unwrap();
    assert_eq!(type_name, "org.mockserver.model.HttpRequest");

    // A response handler always returns HttpResponse type
    let resp_handlers: Arc<Mutex<HashMap<String, BreakpointResponseHandler>>> =
        Arc::new(Mutex::new(HashMap::new()));

    resp_handlers.lock().unwrap().insert(
        "bp-dist".to_string(),
        Box::new(|_req, resp| Some(resp)),
    );

    let json = make_response_json("bp-dist", "corr-dist");
    let (type_name, _) = route_response(&json, &resp_handlers).unwrap();
    assert_eq!(type_name, "org.mockserver.model.HttpResponse");
}

#[test]
fn test_route_request_missing_handler_id_auto_continues() {
    // No handler for given breakpoint id => auto-continue with original request
    let handlers: Arc<Mutex<HashMap<String, BreakpointRequestHandler>>> =
        Arc::new(Mutex::new(HashMap::new()));

    // Register handler for bp-A but dispatch bp-B
    handlers.lock().unwrap().insert(
        "bp-A".to_string(),
        Box::new(|_req| {
            panic!("should not be called");
        }),
    );

    let json = make_request_json("bp-B", "corr-B");
    let (type_name, _) = route_request(&json, &handlers).unwrap();
    assert_eq!(type_name, "org.mockserver.model.HttpRequest");
}
