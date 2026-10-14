//! Matching diagnostics: `explain`, `debug_mismatch` and `explain_unmatched`.
//!
//! Stub-server tests (no running MockServer): each asserts what the client
//! sends and that the server's response, as MockServer returns it with the
//! long per-field difference text shortened, reads into the typed model.

use std::sync::mpsc;
use std::thread;
use std::time::Duration;

use mockserver_client::*;

struct Captured {
    method: String,
    url: String,
    body: Vec<u8>,
}

fn stub(status: u16, resp_body: &'static str) -> (MockServerClient, mpsc::Receiver<Captured>) {
    let server = tiny_http::Server::http("127.0.0.1:0").expect("bind stub server");
    let port = match server.server_addr() {
        tiny_http::ListenAddr::IP(addr) => addr.port(),
        #[allow(unreachable_patterns)]
        _ => panic!("expected IP listen address"),
    };
    let (tx, rx) = mpsc::channel();

    thread::spawn(move || {
        let mut request = server.recv().expect("stub recv");
        let method = request.method().as_str().to_string();
        let url = request.url().to_string();
        let mut body = Vec::new();
        request.as_reader().read_to_end(&mut body).ok();
        tx.send(Captured { method, url, body }).ok();

        let response = tiny_http::Response::from_string(resp_body)
            .with_status_code(tiny_http::StatusCode(status));
        request.respond(response).ok();
    });

    let client = ClientBuilder::new("127.0.0.1", port)
        .build()
        .expect("build client");
    (client, rx)
}

const EXPLAIN_MATCHED: &str = r#"{
  "correlationId" : "9d3a95f5-1b5c-4f73-8d4a-98096f4950ab",
  "timestamp" : "2026-10-13T23:31:57.947590Z",
  "recomputed" : true,
  "note" : "recomputed now against the current expectations",
  "request" : { "timestamp" : "2026-10-14 00:31:48.871", "method" : "GET", "path" : "/orders" },
  "atArrival" : { "outcome" : "MATCHED", "expectationId" : "catch-all", "expectationStillStored" : true },
  "expectationsChangedSince" : false,
  "expectationsLastChanged" : "2026-10-13T23:31:48.848Z",
  "sameOutcomeAsAtArrival" : true,
  "matchedExpectation" : {
    "expectationId" : "catch-all", "expectationPath" : "/orders", "expectationMethod" : "GET",
    "matches" : true, "matchedFieldCount" : 18, "totalFieldCount" : 18, "differingFieldCount" : 0,
    "position" : 2, "priority" : 0
  },
  "skippedExpectations" : [ {
    "expectationId" : "gold-tenant", "expectationPath" : "/orders", "expectationMethod" : "GET",
    "matches" : false, "matchedFieldCount" : 17, "totalFieldCount" : 18, "differingFieldCount" : 1,
    "differences" : { "headers" : [ "expected gold found silver", "multimap is not a subset" ] },
    "remediation" : { "headers" : "check request headers" },
    "position" : 1, "priority" : 10, "reason" : "FIELDS_DIFFER",
    "explanation" : "the request differs on: headers"
  } ],
  "shadowedExpectations" : [ ],
  "totalExpectations" : 3,
  "evaluatedExpectations" : 3,
  "truncated" : false
}"#;

#[test]
fn explain_sends_the_correlation_id_and_reads_the_explanation() {
    let (client, rx) = stub(200, EXPLAIN_MATCHED);

    let explanation = client.explain("a b&c").expect("explain");

    let cap = rx.recv_timeout(Duration::from_secs(5)).expect("request");
    assert_eq!(cap.method, "PUT");
    assert_eq!(cap.url, "/mockserver/explain?correlationId=a+b%26c");
    assert!(cap.body.is_empty(), "explain sent a body");

    assert_eq!(
        explanation.correlation_id.as_deref(),
        Some("9d3a95f5-1b5c-4f73-8d4a-98096f4950ab")
    );
    assert!(explanation.recomputed);
    let request = explanation.request.as_ref().expect("request");
    assert_eq!(request.method.as_deref(), Some("GET"));
    assert_eq!(request.path.as_deref(), Some("/orders"));
    assert!(!request.body_truncated);
    let arrival = explanation.at_arrival.as_ref().expect("atArrival");
    assert_eq!(arrival.outcome, Some(ArrivalOutcome::Matched));
    assert_eq!(arrival.expectation_id.as_deref(), Some("catch-all"));
    assert_eq!(arrival.expectation_still_stored, Some(true));
    assert_eq!(arrival.forward_failure, None);
    assert_eq!(explanation.same_outcome_as_at_arrival, Some(true));
    assert!(!explanation.expectations_changed_since);

    let matched = explanation.matched_expectation.as_ref().expect("matched");
    assert_eq!(matched.expectation_id.as_deref(), Some("catch-all"));
    assert_eq!(matched.position, Some(2));
    assert_eq!(matched.reason, None);
    assert_eq!(matched.matches, Some(true));

    assert_eq!(explanation.skipped_expectations.len(), 1);
    let skipped = &explanation.skipped_expectations[0];
    assert_eq!(skipped.expectation_id.as_deref(), Some("gold-tenant"));
    assert_eq!(skipped.priority, Some(10));
    assert_eq!(skipped.reason, Some(ExplainReason::FieldsDiffer));
    assert_eq!(
        skipped.explanation.as_deref(),
        Some("the request differs on: headers")
    );
    assert_eq!(skipped.differing_field_count, Some(1));
    assert_eq!(
        skipped.differences["headers"],
        vec!["expected gold found silver", "multimap is not a subset"]
    );
    assert_eq!(skipped.remediation["headers"], "check request headers");

    assert!(explanation.shadowed_expectations.is_empty());
    assert_eq!(explanation.total_expectations, 3);
    assert_eq!(explanation.evaluated_expectations, 3);
    assert!(!explanation.truncated);
    assert_eq!(explanation.max_expectations_evaluated, None);
}

#[test]
fn explain_reads_values_from_a_newer_server_as_unknown() {
    let (client, _rx) = stub(
        200,
        r#"{"skippedExpectations":[{"expectationId":"x","reason":"SOMETHING_NEW"}],
            "atArrival":{"outcome":"LATER_OUTCOME"},"truncated":true,"maxExpectationsEvaluated":500}"#,
    );

    let explanation = client.explain("id").expect("explain");

    assert_eq!(
        explanation.skipped_expectations[0].reason,
        Some(ExplainReason::Unknown)
    );
    assert_eq!(
        explanation.at_arrival.unwrap().outcome,
        Some(ArrivalOutcome::Unknown)
    );
    assert!(explanation.truncated);
    assert_eq!(explanation.max_expectations_evaluated, Some(500));
}

#[test]
fn explain_with_a_blank_correlation_id_sends_nothing() {
    let (client, rx) = stub(200, EXPLAIN_MATCHED);

    let err = client.explain("  ").expect_err("blank id must fail");

    assert!(matches!(err, Error::InvalidRequest(_)), "got {err:?}");
    assert!(
        rx.recv_timeout(Duration::from_secs(1)).is_err(),
        "no request may reach the server"
    );
}

#[test]
fn explain_of_an_unknown_correlation_id_is_not_found() {
    let (client, _rx) = stub(
        404,
        r#"{"error":"no request is recorded with this correlationId","correlationId":"nope"}"#,
    );

    match client.explain("nope") {
        Err(Error::NotFound(body)) => {
            assert!(body.contains("no request is recorded with this correlationId"))
        }
        other => panic!("expected NotFound, got {other:?}"),
    }
}

const DEBUG_MISMATCH: &str = r#"{
  "correlationId" : "9d9509ef-e568-4add-9a7d-b5544ade6703",
  "timestamp" : "2026-10-13T23:31:58.028874Z",
  "totalExpectations" : 3,
  "evaluatedExpectations" : 3,
  "closestMatch" : { "expectationId" : "gold-tenant", "matchedFields" : 17, "totalFields" : 18 },
  "results" : [ {
    "expectationId" : "gold-tenant", "expectationPath" : "/orders", "expectationMethod" : "GET",
    "matches" : false, "matchedFieldCount" : 17, "totalFieldCount" : 18,
    "differences" : { "headers" : [ "expected gold found silver" ] }
  }, {
    "expectationId" : "catch-all", "expectationPath" : "/orders", "expectationMethod" : "GET",
    "matches" : true, "matchedFieldCount" : 18, "totalFieldCount" : 18
  } ]
}"#;

#[test]
fn debug_mismatch_sends_the_request_and_reads_the_report() {
    let (client, rx) = stub(200, DEBUG_MISMATCH);

    let report = client
        .debug_mismatch(
            HttpRequest::new()
                .method("GET")
                .path("/orders")
                .header("X-Tenant", "silver"),
        )
        .expect("debug_mismatch");

    let cap = rx.recv_timeout(Duration::from_secs(5)).expect("request");
    assert_eq!(cap.method, "PUT");
    assert_eq!(cap.url, "/mockserver/debugMismatch");
    let sent: serde_json::Value = serde_json::from_slice(&cap.body).expect("JSON body");
    assert_eq!(
        sent,
        serde_json::json!({"method":"GET","path":"/orders","headers":{"X-Tenant":["silver"]}})
    );

    assert_eq!(report.total_expectations, 3);
    assert_eq!(report.evaluated_expectations, 3);
    assert!(!report.truncated);
    let closest = report.closest_match.as_ref().expect("closestMatch");
    assert_eq!(closest.expectation_id.as_deref(), Some("gold-tenant"));
    assert_eq!((closest.matched_fields, closest.total_fields), (17, 18));
    assert_eq!(report.results.len(), 2);
    assert_eq!(report.results[0].matches, Some(false));
    assert_eq!(report.results[0].matched_field_count, Some(17));
    assert_eq!(
        report.results[0].differences["headers"],
        vec!["expected gold found silver"]
    );
    assert_eq!(report.results[1].matches, Some(true));
    assert!(report.results[1].differences.is_empty());
}

#[test]
fn debug_mismatch_of_a_rejected_definition_is_an_invalid_request() {
    let (client, _rx) = stub(
        400,
        r#"{"error":"debugMismatch only supports HttpRequest definitions"}"#,
    );

    match client.debug_mismatch(HttpRequest::new()) {
        Err(Error::InvalidRequest(body)) => {
            assert!(body.contains("debugMismatch only supports HttpRequest definitions"))
        }
        other => panic!("expected InvalidRequest, got {other:?}"),
    }
}

const EXPLAIN_UNMATCHED: &str = r#"{
  "correlationId" : "39ee8eb8-13d7-4c36-8826-cc909088f775",
  "timestamp" : "2026-10-13T23:32:11.645636Z",
  "unmatchedRequestCount" : 1,
  "truncated" : false,
  "unmatchedRequests" : [ {
    "timestamp" : "2026-10-14 00:32:11.573",
    "method" : "POST",
    "path" : "/api/order",
    "closestExpectations" : [ {
      "expectationId" : "create-order", "expectationPath" : "/api/orders", "expectationMethod" : "POST",
      "matches" : false, "matchedFieldCount" : 16, "totalFieldCount" : 18, "differingFieldCount" : 2,
      "differences" : { "path" : [ "expected /api/orders found /api/order" ], "headers" : [ "expected blue found green" ] },
      "remediation" : { "path" : "check the request path", "headers" : "check request headers" },
      "suggestedFix" : {
        "patch" : [
          { "op" : "test", "path" : "/httpRequest/path", "value" : "/api/orders" },
          { "op" : "replace", "path" : "/httpRequest/path", "value" : "/api/order" },
          { "op" : "test", "path" : "/httpRequest/headers/X-Tenant", "value" : [ "blue" ] },
          { "op" : "replace", "path" : "/httpRequest/headers/X-Tenant", "value" : [ "green" ] }
        ],
        "patchedFields" : [ "path", "headers" ],
        "unpatchedFields" : [ { "field" : "jwt", "reason" : "edit it by hand" } ],
        "matchesAfterPatch" : true
      }
    } ],
    "totalExpectationsEvaluated" : 3
  } ]
}"#;

#[test]
fn explain_unmatched_sends_the_options_and_reads_suggested_fixes() {
    let (client, rx) = stub(200, EXPLAIN_UNMATCHED);

    let result = client
        .explain_unmatched(Some(
            &ExplainUnmatchedOptions::new().limit(5).suggest_fixes(true),
        ))
        .expect("explain_unmatched");

    let cap = rx.recv_timeout(Duration::from_secs(5)).expect("request");
    assert_eq!(cap.method, "PUT");
    assert_eq!(cap.url, "/mockserver/explainUnmatched");
    let sent: serde_json::Value = serde_json::from_slice(&cap.body).expect("JSON body");
    assert_eq!(sent, serde_json::json!({"limit": 5, "suggestFixes": true}));

    assert_eq!(result.unmatched_request_count, 1);
    assert!(!result.truncated);
    let unmatched = &result.unmatched_requests[0];
    assert_eq!(unmatched.method.as_deref(), Some("POST"));
    assert_eq!(unmatched.path.as_deref(), Some("/api/order"));
    assert_eq!(unmatched.total_expectations_evaluated, 3);
    let closest = &unmatched.closest_expectations[0];
    assert_eq!(
        closest.expectation.expectation_id.as_deref(),
        Some("create-order")
    );
    assert_eq!(closest.expectation.differing_field_count, Some(2));
    assert_eq!(
        closest.expectation.remediation["path"],
        "check the request path"
    );
    let fix = closest.suggested_fix.as_ref().expect("suggestedFix");
    assert!(fix.matches_after_patch);
    assert_eq!(fix.patched_fields, vec!["path", "headers"]);
    assert_eq!(fix.patch.len(), 4);
    assert_eq!(fix.patch[3].op, "replace");
    assert_eq!(fix.patch[3].path, "/httpRequest/headers/X-Tenant");
    assert_eq!(fix.patch[3].value, Some(serde_json::json!(["green"])));
    assert_eq!(
        fix.unpatched_fields,
        vec![UnpatchedField {
            field: "jwt".into(),
            reason: "edit it by hand".into()
        }]
    );
}

#[test]
fn explain_unmatched_without_options_sends_no_body() {
    let (client, rx) = stub(
        200,
        r#"{"unmatchedRequestCount":0,"truncated":false,"unmatchedRequests":[]}"#,
    );

    let result = client.explain_unmatched(None).expect("explain_unmatched");

    let cap = rx.recv_timeout(Duration::from_secs(5)).expect("request");
    assert!(
        cap.body.is_empty(),
        "sent {:?}",
        String::from_utf8_lossy(&cap.body)
    );
    assert_eq!(result.unmatched_request_count, 0);
    assert!(result.unmatched_requests.is_empty());
}

#[test]
fn explain_unmatched_server_failure_is_an_unexpected_status() {
    let (client, _rx) = stub(500, r#"{"error":"boom"}"#);

    match client.explain_unmatched(None) {
        Err(Error::UnexpectedStatus { status, .. }) => assert_eq!(status, 500),
        other => panic!("expected UnexpectedStatus, got {other:?}"),
    }
}

#[test]
fn explain_reads_the_reason_a_failed_forward_gave_at_arrival() {
    let (client, _rx) = stub(
        200,
        r#"{"correlationId":"c1","atArrival":{"outcome":"MATCHED","expectationId":"stock-forward","forwardFailure":{"statusCode":502,"reason":"Connection refused","reasonTruncated":true,"causeNamed":false,"forwardLoopSuspected":true,"hint":"retrieve_logs"}}}"#,
    );

    let explanation = client.explain("c1").expect("explain");

    assert_eq!(
        explanation
            .at_arrival
            .and_then(|arrival| arrival.forward_failure),
        Some(ArrivalForwardFailure {
            status_code: Some(502),
            reason: Some("Connection refused".to_string()),
            reason_truncated: true,
            cause_named: Some(false),
            forward_loop_suspected: Some(true),
            hint: Some("retrieve_logs".to_string()),
        })
    );
}

#[test]
fn explain_leaves_out_the_forward_failure_fields_a_server_did_not_send() {
    let (client, _rx) = stub(
        200,
        r#"{"correlationId":"c1","atArrival":{"outcome":"FORWARDED_WITHOUT_EXPECTATION","forwardFailure":{"reason":"timed out","causeNamed":true}}}"#,
    );

    let explanation = client.explain("c1").expect("explain");

    assert_eq!(
        explanation
            .at_arrival
            .and_then(|arrival| arrival.forward_failure),
        Some(ArrivalForwardFailure {
            reason: Some("timed out".to_string()),
            cause_named: Some(true),
            ..ArrivalForwardFailure::default()
        })
    );
}

#[test]
fn explain_reads_the_narration() {
    let (client, _rx) = stub(
        200,
        r#"{"correlationId":"c1","narration":{"available":true,"aiGenerated":true,"label":"AI-generated, can be wrong","provider":"OPENAI","model":"gpt-test","text":"gold needs X-Tenant"}}"#,
    );

    let explanation = client.explain("c1").expect("explain");

    assert_eq!(
        explanation.narration,
        Some(ExplanationNarration {
            available: true,
            ai_generated: true,
            label: Some("AI-generated, can be wrong".to_string()),
            provider: Some("OPENAI".to_string()),
            model: Some("gpt-test".to_string()),
            text: Some("gold needs X-Tenant".to_string()),
            reason: None,
        })
    );
}

#[test]
fn explain_unmatched_reads_an_unavailable_narration() {
    let (client, _rx) = stub(
        200,
        r#"{"unmatchedRequestCount":0,"unmatchedRequests":[],"narration":{"available":false,"reason":"llmExplainNarrationEnabled is false"}}"#,
    );

    let result = client.explain_unmatched(None).expect("explain_unmatched");

    assert_eq!(
        result.narration,
        Some(ExplanationNarration {
            reason: Some("llmExplainNarrationEnabled is false".to_string()),
            ..ExplanationNarration::default()
        })
    );
}
