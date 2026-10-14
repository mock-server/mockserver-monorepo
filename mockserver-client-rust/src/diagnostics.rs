//! Response types of the matching diagnostics:
//! [`MockServerClient::explain`](crate::MockServerClient::explain),
//! [`MockServerClient::debug_mismatch`](crate::MockServerClient::debug_mismatch) and
//! [`MockServerClient::explain_unmatched`](crate::MockServerClient::explain_unmatched).
//! They follow the `RequestExplanation` and `ExplainedExpectation` schemas of
//! MockServer's OpenAPI specification.

use serde::{Deserialize, Serialize};
use std::collections::HashMap;

/// Why an expectation was passed over, in the order the gates are applied
/// when a request is served.
#[derive(Debug, Clone, Copy, Serialize, Deserialize, PartialEq, Eq, Hash)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
#[non_exhaustive]
pub enum ExplainReason {
    /// The expectation belongs to a different namespace from the request.
    Namespace,
    /// Its `Times` are used up or its time to live has expired.
    Inactive,
    /// The request differs on one or more fields.
    FieldsDiffer,
    /// The expectation is wrapped in `not(...)` and the request matches everything inside it.
    NotOperator,
    /// The request matches, but its LLM conversation does not.
    Conversation,
    /// It needs its scenario to be in a different state.
    ScenarioState,
    /// It matches, but is only served for a percentage of requests.
    Percentage,
    /// It also matches, but the expectation that serves the request shadows it.
    LowerPriority,
    /// A value from a newer MockServer that this client version does not
    /// recognise, so reading it does not fail the whole response. It cannot be
    /// sent: serialising it returns an error.
    #[serde(other, skip_serializing)]
    Unknown,
}

/// How a request was handled when it arrived, read from the event log.
#[derive(Debug, Clone, Copy, Serialize, Deserialize, PartialEq, Eq, Hash)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
#[non_exhaustive]
pub enum ArrivalOutcome {
    /// An expectation served it.
    Matched,
    /// No expectation matched it.
    NoMatch,
    /// It was forwarded without an expectation (proxying).
    ForwardedWithoutExpectation,
    /// It was answered without an expectation.
    RespondedWithoutExpectation,
    /// The server's own `UNKNOWN` outcome, or a value from a newer MockServer
    /// that this client does not recognise. It cannot be sent: serialising it
    /// returns an error.
    #[serde(other, skip_serializing)]
    Unknown,
}

/// One expectation as a diagnostic evaluated it (`ExplainedExpectation`).
///
/// From [`explain`](crate::MockServerClient::explain), the matched expectation
/// has no `reason` and every skipped or shadowed one has a `reason` and an
/// `explanation`. `debug_mismatch` and `explain_unmatched` fill the field
/// comparison only.
#[derive(Debug, Clone, Default, Serialize, Deserialize, PartialEq)]
#[serde(rename_all = "camelCase")]
pub struct ExplainedExpectation {
    #[serde(skip_serializing_if = "Option::is_none")]
    pub expectation_id: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub expectation_method: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub expectation_path: Option<String>,
    /// Its 1-based position in the order MockServer evaluates expectations.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub position: Option<u32>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub priority: Option<i32>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub reason: Option<ExplainReason>,
    /// Why it was passed over, in plain language.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub explanation: Option<String>,
    /// Whether the request matches its request matcher; `None` when an earlier gate decided.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub matches: Option<bool>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub matched_field_count: Option<u32>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub total_field_count: Option<u32>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub differing_field_count: Option<u32>,
    /// For each differing field, the differences found.
    #[serde(default, skip_serializing_if = "HashMap::is_empty")]
    pub differences: HashMap<String, Vec<String>>,
    /// For each differing field, a hint on how to change the expectation.
    #[serde(default, skip_serializing_if = "HashMap::is_empty")]
    pub remediation: HashMap<String, String>,
}

/// The recorded request that [`explain`](crate::MockServerClient::explain) re-checked.
#[derive(Debug, Clone, Default, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "camelCase")]
pub struct ExplainedRequest {
    /// When it was recorded, as the event log formats it.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub timestamp: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub method: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub path: Option<String>,
    /// Whether the event log kept only the start of the body, so body matching used that copy.
    #[serde(default, skip_serializing_if = "std::ops::Not::not")]
    pub body_truncated: bool,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub body_truncated_note: Option<String>,
}

/// How the request was handled when it arrived. `expectation_id` and
/// `expectation_still_stored` are set for [`ArrivalOutcome::Matched`] only;
/// `forward_failure` is set only when the request was forwarded and the
/// forward failed.
#[derive(Debug, Clone, Default, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "camelCase")]
pub struct ExplanationArrival {
    #[serde(skip_serializing_if = "Option::is_none")]
    pub outcome: Option<ArrivalOutcome>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub expectation_id: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub expectation_still_stored: Option<bool>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub forward_failure: Option<ArrivalForwardFailure>,
}

/// Why a request's forward failed, so MockServer answered 502: the same facts
/// the `explain_forward_failure` MCP tool gives. `reason` is redacted as the
/// event log is and capped at 2048 characters (`reason_truncated`); when
/// `cause_named` is false the reason is the raw error and `hint` says what to
/// look at next.
#[derive(Debug, Clone, Default, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "camelCase")]
pub struct ArrivalForwardFailure {
    #[serde(skip_serializing_if = "Option::is_none")]
    pub status_code: Option<u16>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub reason: Option<String>,
    #[serde(default, skip_serializing_if = "std::ops::Not::not")]
    pub reason_truncated: bool,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub cause_named: Option<bool>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub forward_loop_suspected: Option<bool>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub hint: Option<String>,
}

/// Set only when narration was asked for (`ExplanationNarration`): a
/// plain-language summary the configured LLM backend wrote, or, when
/// `available` is false, the `reason` none is available. The text is
/// AI-generated and can be wrong; the other fields are the facts.
#[derive(Debug, Clone, Default, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "camelCase")]
pub struct ExplanationNarration {
    #[serde(default)]
    pub available: bool,
    #[serde(default)]
    pub ai_generated: bool,
    /// A label to show beside the text, saying it is AI-generated and can be wrong.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub label: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub provider: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub model: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub text: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub reason: Option<String>,
}

/// Which expectation would serve a recorded request now, why each expectation
/// evaluated ahead of it was passed over, and how the request was handled when
/// it arrived (`RequestExplanation`). Recomputed when asked for, against the
/// current expectations, scenario states and remaining `Times`.
#[derive(Debug, Clone, Default, Serialize, Deserialize, PartialEq)]
#[serde(rename_all = "camelCase")]
pub struct RequestExplanation {
    #[serde(skip_serializing_if = "Option::is_none")]
    pub correlation_id: Option<String>,
    /// When the explanation was computed (ISO-8601).
    #[serde(skip_serializing_if = "Option::is_none")]
    pub timestamp: Option<String>,
    #[serde(default)]
    pub recomputed: bool,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub note: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub request: Option<ExplainedRequest>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub at_arrival: Option<ExplanationArrival>,
    /// Whether it would be handled the same way now; `None` when that cannot be told.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub same_outcome_as_at_arrival: Option<bool>,
    #[serde(default)]
    pub expectations_changed_since: bool,
    /// When the expectations last changed; `None` if they never have.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub expectations_last_changed: Option<String>,
    /// The expectation that would serve the request now; `None` if none would.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub matched_expectation: Option<ExplainedExpectation>,
    /// When none would, the expectation with the fewest differing fields.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub closest_expectation_id: Option<String>,
    #[serde(default)]
    pub skipped_expectations: Vec<ExplainedExpectation>,
    #[serde(default)]
    pub shadowed_expectations: Vec<ExplainedExpectation>,
    #[serde(default)]
    pub total_expectations: u32,
    #[serde(default)]
    pub evaluated_expectations: u32,
    #[serde(default)]
    pub truncated: bool,
    /// The evaluation budget; set only when `truncated`.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub max_expectations_evaluated: Option<u32>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub narration: Option<ExplanationNarration>,
}

/// The expectation with the fewest differing fields.
#[derive(Debug, Clone, Default, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "camelCase")]
pub struct ClosestMatch {
    #[serde(skip_serializing_if = "Option::is_none")]
    pub expectation_id: Option<String>,
    #[serde(default)]
    pub matched_fields: u32,
    #[serde(default)]
    pub total_fields: u32,
}

/// The result of [`debug_mismatch`](crate::MockServerClient::debug_mismatch):
/// for each active expectation, whether the request matches it and the
/// per-field differences. Field counts compare expectations with each other;
/// they are not an absolute score.
#[derive(Debug, Clone, Default, Serialize, Deserialize, PartialEq)]
#[serde(rename_all = "camelCase")]
pub struct MismatchReport {
    #[serde(skip_serializing_if = "Option::is_none")]
    pub correlation_id: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub timestamp: Option<String>,
    #[serde(default)]
    pub total_expectations: u32,
    #[serde(default)]
    pub evaluated_expectations: u32,
    #[serde(default)]
    pub truncated: bool,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub max_expectations_evaluated: Option<u32>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub closest_match: Option<ClosestMatch>,
    #[serde(default)]
    pub results: Vec<ExplainedExpectation>,
}

/// Options for [`explain_unmatched`](crate::MockServerClient::explain_unmatched).
#[derive(Debug, Clone, Default, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "camelCase")]
pub struct ExplainUnmatchedOptions {
    /// How many recent unmatched requests to analyse; `None` leaves the server default (10).
    #[serde(skip_serializing_if = "Option::is_none")]
    pub limit: Option<u32>,
    /// Add a [`SuggestedFix`] to each of the five closest non-matching expectations.
    #[serde(default, skip_serializing_if = "std::ops::Not::not")]
    pub suggest_fixes: bool,
}

impl ExplainUnmatchedOptions {
    /// Options with the server defaults.
    pub fn new() -> Self {
        Self::default()
    }

    /// Analyse at most `limit` recent unmatched requests.
    pub fn limit(mut self, limit: u32) -> Self {
        self.limit = Some(limit);
        self
    }

    /// Add suggested fixes.
    pub fn suggest_fixes(mut self, suggest_fixes: bool) -> Self {
        self.suggest_fixes = suggest_fixes;
        self
    }
}

/// One RFC 6902 JSON patch operation.
#[derive(Debug, Clone, Default, Serialize, Deserialize, PartialEq)]
pub struct JsonPatchOperation {
    pub op: String,
    pub path: String,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub value: Option<serde_json::Value>,
}

/// A differing field a suggested fix could not change, and why.
#[derive(Debug, Clone, Default, Serialize, Deserialize, PartialEq, Eq)]
pub struct UnpatchedField {
    pub field: String,
    pub reason: String,
}

/// A JSON patch of the expectation, as `PUT /mockserver/retrieve?type=active_expectations`
/// returns it, that would make it match the request. The server applies nothing.
#[derive(Debug, Clone, Default, Serialize, Deserialize, PartialEq)]
#[serde(rename_all = "camelCase")]
pub struct SuggestedFix {
    #[serde(default)]
    pub patch: Vec<JsonPatchOperation>,
    #[serde(default)]
    pub patched_fields: Vec<String>,
    #[serde(default)]
    pub unpatched_fields: Vec<UnpatchedField>,
    #[serde(default)]
    pub matches_after_patch: bool,
}

/// One expectation compared with an unmatched request, with a [`SuggestedFix`]
/// when [`ExplainUnmatchedOptions::suggest_fixes`] was set.
#[derive(Debug, Clone, Default, Serialize, Deserialize, PartialEq)]
#[serde(rename_all = "camelCase")]
pub struct ClosestExpectation {
    #[serde(flatten)]
    pub expectation: ExplainedExpectation,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub suggested_fix: Option<SuggestedFix>,
}

/// A recent request that matched no expectation, with the expectations
/// compared to it, closest (fewest differing fields) first.
#[derive(Debug, Clone, Default, Serialize, Deserialize, PartialEq)]
#[serde(rename_all = "camelCase")]
pub struct UnmatchedRequest {
    #[serde(skip_serializing_if = "Option::is_none")]
    pub timestamp: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub method: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub path: Option<String>,
    #[serde(default)]
    pub closest_expectations: Vec<ClosestExpectation>,
    #[serde(default)]
    pub total_expectations_evaluated: u32,
}

/// The result of [`explain_unmatched`](crate::MockServerClient::explain_unmatched).
/// `truncated` is true when the server's evaluation budget ran out.
#[derive(Debug, Clone, Default, Serialize, Deserialize, PartialEq)]
#[serde(rename_all = "camelCase")]
pub struct UnmatchedExplanation {
    #[serde(skip_serializing_if = "Option::is_none")]
    pub correlation_id: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub timestamp: Option<String>,
    #[serde(default)]
    pub unmatched_request_count: u32,
    #[serde(default)]
    pub truncated: bool,
    #[serde(default)]
    pub unmatched_requests: Vec<UnmatchedRequest>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub narration: Option<ExplanationNarration>,
}
