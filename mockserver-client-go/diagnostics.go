package mockserver

import (
	"encoding/json"
	"fmt"
	"net/url"
	"strings"
)

// This file adds the matching diagnostics: Explain (PUT /mockserver/explain),
// DebugMismatch (PUT /mockserver/debugMismatch) and ExplainUnmatched
// (PUT /mockserver/explainUnmatched). The response types follow the
// RequestExplanation and ExplainedExpectation schemas of
// jekyll-www.mock-server.com/mockserver-openapi.yaml; each call is read-only on
// the server: it consumes no Times and moves no scenario.

// ExplainReason says why an expectation was passed over. A newer server may
// send a reason this client does not list; it is kept as sent.
type ExplainReason string

const (
	// ExplainReasonNamespace: the expectation belongs to a different namespace.
	ExplainReasonNamespace ExplainReason = "NAMESPACE"
	// ExplainReasonInactive: its Times are used up or its time to live expired.
	ExplainReasonInactive ExplainReason = "INACTIVE"
	// ExplainReasonFieldsDiffer: the request differs on one or more fields.
	ExplainReasonFieldsDiffer ExplainReason = "FIELDS_DIFFER"
	// ExplainReasonNotOperator: the expectation is wrapped in not(...) and the
	// request matches everything inside it.
	ExplainReasonNotOperator ExplainReason = "NOT_OPERATOR"
	// ExplainReasonConversation: the request matches but its LLM conversation
	// does not.
	ExplainReasonConversation ExplainReason = "CONVERSATION"
	// ExplainReasonScenarioState: the expectation needs a different scenario state.
	ExplainReasonScenarioState ExplainReason = "SCENARIO_STATE"
	// ExplainReasonPercentage: it matches but is served for a percentage of
	// requests only.
	ExplainReasonPercentage ExplainReason = "PERCENTAGE"
	// ExplainReasonLowerPriority: it also matches but is shadowed by the one
	// that serves the request.
	ExplainReasonLowerPriority ExplainReason = "LOWER_PRIORITY"
)

// ArrivalOutcome says how a request was handled when it arrived.
type ArrivalOutcome string

const (
	ArrivalMatched                     ArrivalOutcome = "MATCHED"
	ArrivalNoMatch                     ArrivalOutcome = "NO_MATCH"
	ArrivalForwardedWithoutExpectation ArrivalOutcome = "FORWARDED_WITHOUT_EXPECTATION"
	ArrivalRespondedWithoutExpectation ArrivalOutcome = "RESPONDED_WITHOUT_EXPECTATION"
	ArrivalUnknown                     ArrivalOutcome = "UNKNOWN"
)

// ExplainedExpectation is one expectation as a diagnostic evaluated it
// (schema ExplainedExpectation). From Explain, the matched expectation has no
// Reason and every skipped or shadowed one has a Reason and an Explanation.
// DebugMismatch and ExplainUnmatched fill the field comparison only.
type ExplainedExpectation struct {
	ExpectationID       string              `json:"expectationId,omitempty"`
	ExpectationMethod   string              `json:"expectationMethod,omitempty"`
	ExpectationPath     string              `json:"expectationPath,omitempty"`
	Position            int                 `json:"position,omitempty"`
	Priority            int                 `json:"priority,omitempty"`
	Reason              ExplainReason       `json:"reason,omitempty"`
	Explanation         string              `json:"explanation,omitempty"`
	Matches             *bool               `json:"matches,omitempty"`
	MatchedFieldCount   *int                `json:"matchedFieldCount,omitempty"`
	TotalFieldCount     *int                `json:"totalFieldCount,omitempty"`
	DifferingFieldCount *int                `json:"differingFieldCount,omitempty"`
	Differences         map[string][]string `json:"differences,omitempty"`
	Remediation         map[string]string   `json:"remediation,omitempty"`
}

// ExplainedRequest is the recorded request that Explain re-checked.
type ExplainedRequest struct {
	Timestamp         string `json:"timestamp,omitempty"`
	Method            string `json:"method,omitempty"`
	Path              string `json:"path,omitempty"`
	BodyTruncated     bool   `json:"bodyTruncated,omitempty"`
	BodyTruncatedNote string `json:"bodyTruncatedNote,omitempty"`
}

// ExplanationArrival is how the request was handled when it arrived.
// ExpectationID and ExpectationStillStored are set for ArrivalMatched only;
// ForwardFailure is set only when the request was forwarded and the forward
// failed.
type ExplanationArrival struct {
	Outcome                ArrivalOutcome         `json:"outcome,omitempty"`
	ExpectationID          string                 `json:"expectationId,omitempty"`
	ExpectationStillStored *bool                  `json:"expectationStillStored,omitempty"`
	ForwardFailure         *ArrivalForwardFailure `json:"forwardFailure,omitempty"`
}

// ArrivalForwardFailure is why a request's forward failed, so MockServer
// answered 502: the same facts the explain_forward_failure MCP tool gives.
// Reason is redacted as the event log is and capped at 2048 characters
// (ReasonTruncated); when CauseNamed is false the Reason is the raw error and
// Hint says what to look at next.
type ArrivalForwardFailure struct {
	StatusCode           int    `json:"statusCode,omitempty"`
	Reason               string `json:"reason,omitempty"`
	ReasonTruncated      bool   `json:"reasonTruncated,omitempty"`
	CauseNamed           *bool  `json:"causeNamed,omitempty"`
	ForwardLoopSuspected *bool  `json:"forwardLoopSuspected,omitempty"`
	Hint                 string `json:"hint,omitempty"`
}

// ExplanationNarration is set only when narration was asked for (schema
// ExplanationNarration): a plain-language summary the configured LLM backend
// wrote, or, when Available is false, the Reason none is available. The text
// is AI-generated and can be wrong; the other fields are the facts.
type ExplanationNarration struct {
	Available   bool   `json:"available,omitempty"`
	AIGenerated bool   `json:"aiGenerated,omitempty"`
	Label       string `json:"label,omitempty"`
	Provider    string `json:"provider,omitempty"`
	Model       string `json:"model,omitempty"`
	Text        string `json:"text,omitempty"`
	Reason      string `json:"reason,omitempty"`
}

// RequestExplanation is the result of Explain (schema RequestExplanation):
// which expectation would serve a recorded request now, why each one
// evaluated ahead of it was passed over, and how it was handled on arrival.
type RequestExplanation struct {
	CorrelationID            string                 `json:"correlationId,omitempty"`
	Timestamp                string                 `json:"timestamp,omitempty"`
	Recomputed               bool                   `json:"recomputed,omitempty"`
	Note                     string                 `json:"note,omitempty"`
	Request                  *ExplainedRequest      `json:"request,omitempty"`
	AtArrival                *ExplanationArrival    `json:"atArrival,omitempty"`
	SameOutcomeAsAtArrival   *bool                  `json:"sameOutcomeAsAtArrival,omitempty"`
	ExpectationsChangedSince bool                   `json:"expectationsChangedSince,omitempty"`
	ExpectationsLastChanged  string                 `json:"expectationsLastChanged,omitempty"`
	MatchedExpectation       *ExplainedExpectation  `json:"matchedExpectation,omitempty"`
	ClosestExpectationID     string                 `json:"closestExpectationId,omitempty"`
	SkippedExpectations      []ExplainedExpectation `json:"skippedExpectations,omitempty"`
	ShadowedExpectations     []ExplainedExpectation `json:"shadowedExpectations,omitempty"`
	TotalExpectations        int                    `json:"totalExpectations,omitempty"`
	EvaluatedExpectations    int                    `json:"evaluatedExpectations,omitempty"`
	Truncated                bool                   `json:"truncated,omitempty"`
	MaxExpectationsEvaluated *int                   `json:"maxExpectationsEvaluated,omitempty"`
	Narration                *ExplanationNarration  `json:"narration,omitempty"`
}

// Explain re-checks the recorded request with the given correlation ID (see
// the correlationId of its log entries) against the current expectations, in
// the order MockServer evaluates them. Mirrors the Java client
// explain(correlationId). Sends PUT /mockserver/explain?correlationId=<id>.
// An empty correlation ID is rejected without a request; an ID that is not in
// the event log (never received, cleared or evicted) returns the server's 404
// reason as an error.
func (c *Client) Explain(correlationID string) (*RequestExplanation, error) {
	if strings.TrimSpace(correlationID) == "" {
		return nil, fmt.Errorf("mockserver: explain requires a non-blank correlation ID")
	}
	params := url.Values{}
	params.Set("correlationId", correlationID)
	respBody, statusCode, err := c.doRequest("PUT", "/mockserver/explain", nil, params)
	if err != nil {
		return nil, err
	}
	if statusCode != 200 {
		return nil, fmt.Errorf("mockserver: explain failed for correlation ID %q (status %d): %s",
			correlationID, statusCode, serverError(respBody))
	}
	var explanation RequestExplanation
	if err := unmarshalDiagnostic(respBody, &explanation, "explanation"); err != nil {
		return nil, err
	}
	return &explanation, nil
}

// ClosestMatch names the expectation with the fewest differing fields.
type ClosestMatch struct {
	ExpectationID string `json:"expectationId,omitempty"`
	MatchedFields int    `json:"matchedFields,omitempty"`
	TotalFields   int    `json:"totalFields,omitempty"`
}

// MismatchReport is the result of DebugMismatch: for each active expectation
// whether the request matches it and the per-field differences.
// MatchedFieldCount is for comparing expectations with each other, not an
// absolute score.
type MismatchReport struct {
	CorrelationID            string                 `json:"correlationId,omitempty"`
	Timestamp                string                 `json:"timestamp,omitempty"`
	TotalExpectations        int                    `json:"totalExpectations,omitempty"`
	EvaluatedExpectations    int                    `json:"evaluatedExpectations,omitempty"`
	Truncated                bool                   `json:"truncated,omitempty"`
	MaxExpectationsEvaluated *int                   `json:"maxExpectationsEvaluated,omitempty"`
	ClosestMatch             *ClosestMatch          `json:"closestMatch,omitempty"`
	Results                  []ExplainedExpectation `json:"results,omitempty"`
}

// DebugMismatch evaluates a request against every active expectation and
// reports, for each, whether it matched and which fields differ. Mirrors the
// Java client debugMismatch(request). Sends PUT /mockserver/debugMismatch with
// the request as the body; a nil builder sends no body (a request with no
// fields set).
func (c *Client) DebugMismatch(rb *RequestBuilder) (*MismatchReport, error) {
	var body []byte
	if rb != nil {
		var err error
		if body, err = json.Marshal(rb.Build()); err != nil {
			return nil, fmt.Errorf("mockserver: marshal debug mismatch request: %w", err)
		}
	}
	respBody, statusCode, err := c.doRequest("PUT", "/mockserver/debugMismatch", body, nil)
	if err != nil {
		return nil, err
	}
	if statusCode != 200 {
		return nil, fmt.Errorf("mockserver: debug mismatch failed (status %d): %s", statusCode, serverError(respBody))
	}
	var report MismatchReport
	if err := unmarshalDiagnostic(respBody, &report, "mismatch report"); err != nil {
		return nil, err
	}
	return &report, nil
}

// ExplainUnmatchedOptions bounds ExplainUnmatched. Limit is the number of
// recent unmatched requests to analyse (0 leaves the server default, 10).
// SuggestFixes adds a SuggestedFix to each of the five closest non-matching
// expectations of every request.
type ExplainUnmatchedOptions struct {
	Limit        int  `json:"limit,omitempty"`
	SuggestFixes bool `json:"suggestFixes,omitempty"`
}

// JSONPatchOperation is one RFC 6902 operation. Value holds the JSON value
// verbatim and is empty for an operation without one.
type JSONPatchOperation struct {
	Op    string          `json:"op"`
	Path  string          `json:"path"`
	Value json.RawMessage `json:"value,omitempty"`
}

// UnpatchedField is a differing field a suggested fix could not change, and why.
type UnpatchedField struct {
	Field  string `json:"field"`
	Reason string `json:"reason"`
}

// SuggestedFix is a JSON patch of the expectation, as
// PUT /mockserver/retrieve?type=active_expectations returns it, that would
// make it match the request. Nothing is applied by the server.
type SuggestedFix struct {
	Patch             []JSONPatchOperation `json:"patch"`
	PatchedFields     []string             `json:"patchedFields"`
	UnpatchedFields   []UnpatchedField     `json:"unpatchedFields"`
	MatchesAfterPatch bool                 `json:"matchesAfterPatch"`
}

// ClosestExpectation is one expectation compared with an unmatched request,
// with a SuggestedFix when ExplainUnmatchedOptions.SuggestFixes was set.
type ClosestExpectation struct {
	ExplainedExpectation
	SuggestedFix *SuggestedFix `json:"suggestedFix,omitempty"`
}

// UnmatchedRequest is a recent request that matched no expectation, with the
// expectations compared to it, closest (fewest differing fields) first.
type UnmatchedRequest struct {
	Timestamp                  string               `json:"timestamp,omitempty"`
	Method                     string               `json:"method,omitempty"`
	Path                       string               `json:"path,omitempty"`
	ClosestExpectations        []ClosestExpectation `json:"closestExpectations,omitempty"`
	TotalExpectationsEvaluated int                  `json:"totalExpectationsEvaluated,omitempty"`
}

// UnmatchedExplanation is the result of ExplainUnmatched. Truncated is true
// when the server's evaluation budget ran out.
type UnmatchedExplanation struct {
	CorrelationID         string                `json:"correlationId,omitempty"`
	Timestamp             string                `json:"timestamp,omitempty"`
	UnmatchedRequestCount int                   `json:"unmatchedRequestCount,omitempty"`
	Truncated             bool                  `json:"truncated,omitempty"`
	UnmatchedRequests     []UnmatchedRequest    `json:"unmatchedRequests,omitempty"`
	Narration             *ExplanationNarration `json:"narration,omitempty"`
}

// ExplainUnmatched re-checks recent requests that matched no expectation
// against the current expectations and ranks the closest ones. Sends
// PUT /mockserver/explainUnmatched; nil options send no body (server
// defaults).
func (c *Client) ExplainUnmatched(opts *ExplainUnmatchedOptions) (*UnmatchedExplanation, error) {
	var body []byte
	if opts != nil {
		var err error
		if body, err = json.Marshal(opts); err != nil {
			return nil, fmt.Errorf("mockserver: marshal explain unmatched options: %w", err)
		}
	}
	respBody, statusCode, err := c.doRequest("PUT", "/mockserver/explainUnmatched", body, nil)
	if err != nil {
		return nil, err
	}
	if statusCode != 200 {
		return nil, fmt.Errorf("mockserver: explain unmatched failed (status %d): %s", statusCode, serverError(respBody))
	}
	var explanation UnmatchedExplanation
	if err := unmarshalDiagnostic(respBody, &explanation, "unmatched explanation"); err != nil {
		return nil, err
	}
	return &explanation, nil
}

// serverError returns the "error" text of a diagnostic error body, or the
// body itself when it has none.
func serverError(respBody []byte) string {
	var e struct {
		Error string `json:"error"`
	}
	if json.Unmarshal(respBody, &e) == nil && e.Error != "" {
		return e.Error
	}
	return string(respBody)
}

func unmarshalDiagnostic(respBody []byte, into interface{}, what string) error {
	if err := json.Unmarshal(respBody, into); err != nil {
		return fmt.Errorf("mockserver: unmarshal %s: %w", what, err)
	}
	return nil
}
