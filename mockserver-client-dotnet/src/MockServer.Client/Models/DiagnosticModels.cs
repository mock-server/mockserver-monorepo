using System.Text.Json;
using System.Text.Json.Serialization;

namespace MockServer.Client.Models;

// ---------------------------------------------------------------------------
// Match-diagnostic results: PUT /mockserver/explain, /debugMismatch and
// /explainUnmatched. Field names follow the RequestExplanation and
// ExplainedExpectation schemas in jekyll-www.mock-server.com/mockserver-openapi.yaml.
// ---------------------------------------------------------------------------

/// <summary>
/// Why an expectation was passed over, in the order the gates are applied when a request is served.
/// <see cref="UNKNOWN"/> is a reason this client does not know, sent by a newer server.
/// </summary>
[JsonConverter(typeof(UnknownTolerantEnumConverter<ExplainReason>))]
public enum ExplainReason
{
    /// <summary>The expectation belongs to another namespace.</summary>
    NAMESPACE,
    /// <summary>The expectation is used up or expired.</summary>
    INACTIVE,
    /// <summary>One or more request fields differ.</summary>
    FIELDS_DIFFER,
    /// <summary>A <c>not</c> request matcher excluded the request.</summary>
    NOT_OPERATOR,
    /// <summary>An LLM conversation matcher did not match.</summary>
    CONVERSATION,
    /// <summary>The scenario is not in the required state.</summary>
    SCENARIO_STATE,
    /// <summary>The expectation's percentage roll did not select it.</summary>
    PERCENTAGE,
    /// <summary>It also matches, but a higher-priority expectation serves the request.</summary>
    LOWER_PRIORITY,
    /// <summary>A reason this client does not know.</summary>
    UNKNOWN
}

/// <summary>
/// How a request was handled when it arrived. <see cref="UNKNOWN"/> also covers an outcome
/// this client does not know.
/// </summary>
[JsonConverter(typeof(UnknownTolerantEnumConverter<ArrivalOutcome>))]
public enum ArrivalOutcome
{
    /// <summary>An expectation served it.</summary>
    MATCHED,
    /// <summary>No expectation matched.</summary>
    NO_MATCH,
    /// <summary>It was proxied without an expectation.</summary>
    FORWARDED_WITHOUT_EXPECTATION,
    /// <summary>It was answered without an expectation.</summary>
    RESPONDED_WITHOUT_EXPECTATION,
    /// <summary>The outcome is not recorded, or is one this client does not know.</summary>
    UNKNOWN
}

/// <summary>
/// Which expectation would serve a recorded request now, why each expectation evaluated ahead of it was
/// passed over, and how the request was handled when it arrived; returned by
/// <see cref="MockServerClient.Explain"/>. Recomputed when asked for, against the current expectations,
/// scenario states and remaining Times.
/// </summary>
public sealed class RequestExplanation
{
    /// <summary>The correlation ID of the explained request's log entries.</summary>
    public string? CorrelationId { get; set; }
    /// <summary>When the explanation was computed (ISO-8601).</summary>
    public string? Timestamp { get; set; }
    /// <summary>Always true: the explanation is recomputed, not recorded when the request arrived.</summary>
    public bool Recomputed { get; set; }
    /// <summary>The server's reminder that the result is recomputed.</summary>
    public string? Note { get; set; }
    /// <summary>The recorded request that was explained.</summary>
    public ExplainedRequest? Request { get; set; }
    /// <summary>How the request was handled when it arrived.</summary>
    public ExplainArrival? AtArrival { get; set; }
    /// <summary>Whether it would be handled the same way now, or null when that cannot be told.</summary>
    public bool? SameOutcomeAsAtArrival { get; set; }
    /// <summary>Whether the expectations changed at or after the moment the request arrived.</summary>
    public bool ExpectationsChangedSince { get; set; }
    /// <summary>When the expectations last changed (ISO-8601), or null if they never have.</summary>
    public string? ExpectationsLastChanged { get; set; }
    /// <summary>The expectation that would serve the request now, or null if none would.</summary>
    public ExplainedExpectation? MatchedExpectation { get; set; }
    /// <summary>When none would, the expectation with the fewest differing fields, or null.</summary>
    public string? ClosestExpectationId { get; set; }
    /// <summary>The expectations evaluated ahead of the matched one, each with a reason.</summary>
    public List<ExplainedExpectation> SkippedExpectations { get; set; } = new();
    /// <summary>Lower-priority expectations that also match but are never reached.</summary>
    public List<ExplainedExpectation> ShadowedExpectations { get; set; } = new();
    /// <summary>How many expectations are stored.</summary>
    public int TotalExpectations { get; set; }
    /// <summary>How many were evaluated.</summary>
    public int EvaluatedExpectations { get; set; }
    /// <summary>Whether the evaluation budget ran out before every expectation was evaluated.</summary>
    public bool Truncated { get; set; }
    /// <summary>The evaluation budget when <see cref="Truncated"/>, otherwise null.</summary>
    public int? MaxExpectationsEvaluated { get; set; }
    /// <summary>The AI-generated summary when narration was asked for, otherwise null.</summary>
    public ExplanationNarration? Narration { get; set; }
}

/// <summary>
/// A plain-language summary of an explanation written by the configured LLM backend, or why none is
/// available; set only when narration was asked for. It is AI-generated and can be wrong; the other
/// fields of the result are the deterministic facts.
/// </summary>
public sealed class ExplanationNarration
{
    /// <summary>Whether the LLM returned a summary.</summary>
    public bool Available { get; set; }
    /// <summary>Always true when available: the text was written by an LLM, not by MockServer.</summary>
    public bool AiGenerated { get; set; }
    /// <summary>A label to show beside the text, saying it is AI-generated and can be wrong.</summary>
    public string? Label { get; set; }
    /// <summary>The configured LLM provider that wrote the text.</summary>
    public string? Provider { get; set; }
    /// <summary>The model that wrote the text, when known.</summary>
    public string? Model { get; set; }
    /// <summary>The summary, at most 4,000 characters.</summary>
    public string? Text { get; set; }
    /// <summary>When not available, why.</summary>
    public string? Reason { get; set; }
}

/// <summary>The recorded request a <see cref="RequestExplanation"/> explains.</summary>
public sealed class ExplainedRequest
{
    /// <summary>When the request was recorded, as the event log formats it.</summary>
    public string? Timestamp { get; set; }
    /// <summary>The request method.</summary>
    public string? Method { get; set; }
    /// <summary>The request path.</summary>
    public string? Path { get; set; }
    /// <summary>Whether the event log kept only the start of the body, so body matching used that copy.</summary>
    public bool BodyTruncated { get; set; }
    /// <summary>The server's note on the truncated body, or null.</summary>
    public string? BodyTruncatedNote { get; set; }
}

/// <summary>How a request was handled when it arrived, read from the event log.</summary>
public sealed class ExplainArrival
{
    /// <summary>How the request was handled.</summary>
    public ArrivalOutcome Outcome { get; set; } = ArrivalOutcome.UNKNOWN;
    /// <summary>The expectation that served it; set only when <see cref="Outcome"/> is MATCHED.</summary>
    public string? ExpectationId { get; set; }
    /// <summary>Whether that expectation is still stored; set only when <see cref="Outcome"/> is MATCHED.</summary>
    public bool? ExpectationStillStored { get; set; }
    /// <summary>Why the forward failed; set only when the request was forwarded and the forward failed.</summary>
    public ArrivalForwardFailure? ForwardFailure { get; set; }
}

/// <summary>
/// Why a request's forward failed, so MockServer answered 502: the same facts the explain_forward_failure MCP
/// tool gives.
/// </summary>
public sealed class ArrivalForwardFailure
{
    /// <summary>The status MockServer answered with (502).</summary>
    public int? StatusCode { get; set; }
    /// <summary>Why the forward failed, redacted as the event log is and capped at 2048 characters.</summary>
    public string? Reason { get; set; }
    /// <summary>Whether <see cref="Reason"/> was capped.</summary>
    public bool ReasonTruncated { get; set; }
    /// <summary>Whether MockServer named the cause; when false the reason is the raw error.</summary>
    public bool? CauseNamed { get; set; }
    /// <summary>Whether the forward appears to have gone back to MockServer itself.</summary>
    public bool? ForwardLoopSuspected { get; set; }
    /// <summary>What to look at next; set when <see cref="CauseNamed"/> is false.</summary>
    public string? Hint { get; set; }
}

/// <summary>
/// One expectation as a diagnostic call evaluated it. From <see cref="MockServerClient.Explain"/>, the matched
/// expectation has no <see cref="Reason"/> and every skipped or shadowed one has a reason and an explanation.
/// One compared field by field also carries the field counts, differences and remediation hints.
/// </summary>
public sealed class ExplainedExpectation
{
    /// <summary>The expectation's id.</summary>
    public string? ExpectationId { get; set; }
    /// <summary>The method it matches, or null.</summary>
    public string? ExpectationMethod { get; set; }
    /// <summary>The path it matches, or null.</summary>
    public string? ExpectationPath { get; set; }
    /// <summary>Its 1-based position in the order expectations are evaluated (explain only).</summary>
    public int? Position { get; set; }
    /// <summary>Its priority (explain only).</summary>
    public int? Priority { get; set; }
    /// <summary>Why it was passed over, or null for the matched expectation.</summary>
    public ExplainReason? Reason { get; set; }
    /// <summary>Why it was passed over, in plain language, or null.</summary>
    public string? Explanation { get; set; }
    /// <summary>Whether the request matches its request matcher, or null when an earlier gate decided.</summary>
    public bool? Matches { get; set; }
    /// <summary>How many fields matched, when it was compared field by field.</summary>
    public int? MatchedFieldCount { get; set; }
    /// <summary>How many fields were counted, when it was compared field by field.</summary>
    public int? TotalFieldCount { get; set; }
    /// <summary>How many fields differ, or null when it was not compared field by field.</summary>
    public int? DifferingFieldCount { get; set; }
    /// <summary>For each differing field, the differences found.</summary>
    public Dictionary<string, List<string>> Differences { get; set; } = new();
    /// <summary>For each differing field, a hint on how to change the expectation.</summary>
    public Dictionary<string, string> Remediation { get; set; } = new();
    /// <summary>
    /// A JSON patch that would make the expectation match; only from
    /// <see cref="MockServerClient.ExplainUnmatched"/> with <c>suggestFixes</c>, otherwise null.
    /// </summary>
    public SuggestedFix? SuggestedFix { get; set; }
}

/// <summary>
/// The smallest change to an expectation that would make it match an unmatched request. Nothing is
/// applied: patch the expectation retrieved as active and send it back with <see cref="MockServerClient.Upsert"/>.
/// </summary>
public sealed class SuggestedFix
{
    /// <summary>An RFC 6902 JSON patch of the expectation, each change guarded by a <c>test</c> operation.</summary>
    public List<JsonPatchOperation> Patch { get; set; } = new();
    /// <summary>The fields the patch changes.</summary>
    public List<string> PatchedFields { get; set; } = new();
    /// <summary>The fields that could not be patched, and why.</summary>
    public List<UnpatchedField> UnpatchedFields { get; set; } = new();
    /// <summary>Whether the patched expectation matches the request.</summary>
    public bool MatchesAfterPatch { get; set; }
}

/// <summary>One RFC 6902 JSON patch operation.</summary>
public sealed class JsonPatchOperation
{
    /// <summary>The operation: test, add, replace or remove.</summary>
    public string? Op { get; set; }
    /// <summary>The JSON pointer the operation applies to.</summary>
    public string? Path { get; set; }
    /// <summary>The operation's value, or null when it has none.</summary>
    public JsonElement? Value { get; set; }
}

/// <summary>A field a <see cref="SuggestedFix"/> could not change, and why.</summary>
public sealed class UnpatchedField
{
    /// <summary>The field name.</summary>
    public string? Field { get; set; }
    /// <summary>Why it could not be patched automatically.</summary>
    public string? Reason { get; set; }
}

/// <summary>
/// How a request compares with each active expectation; returned by <see cref="MockServerClient.DebugMismatch"/>.
/// </summary>
public sealed class MismatchDebugResult
{
    /// <summary>The correlation ID of this analysis.</summary>
    public string? CorrelationId { get; set; }
    /// <summary>When the analysis was computed (ISO-8601).</summary>
    public string? Timestamp { get; set; }
    /// <summary>How many expectations are stored.</summary>
    public int TotalExpectations { get; set; }
    /// <summary>How many were evaluated.</summary>
    public int EvaluatedExpectations { get; set; }
    /// <summary>Whether the evaluation budget ran out before every expectation was evaluated.</summary>
    public bool Truncated { get; set; }
    /// <summary>The evaluation budget when <see cref="Truncated"/>, otherwise null.</summary>
    public int? MaxExpectationsEvaluated { get; set; }
    /// <summary>The non-matching expectation with the fewest differing fields, or null when none differ.</summary>
    public ClosestMatch? ClosestMatch { get; set; }
    /// <summary>Each evaluated expectation, whether it matches, and its differences.</summary>
    public List<ExplainedExpectation> Results { get; set; } = new();
}

/// <summary>The closest non-matching expectation in a <see cref="MismatchDebugResult"/>.</summary>
public sealed class ClosestMatch
{
    /// <summary>The expectation's id.</summary>
    public string? ExpectationId { get; set; }
    /// <summary>How many fields matched.</summary>
    public int MatchedFields { get; set; }
    /// <summary>How many fields were counted.</summary>
    public int TotalFields { get; set; }
}

/// <summary>
/// Recent requests that matched no expectation, each with its closest expectations; returned by
/// <see cref="MockServerClient.ExplainUnmatched"/>.
/// </summary>
public sealed class UnmatchedExplanation
{
    /// <summary>The correlation ID of this analysis.</summary>
    public string? CorrelationId { get; set; }
    /// <summary>When the analysis was computed (ISO-8601).</summary>
    public string? Timestamp { get; set; }
    /// <summary>How many unmatched requests are explained.</summary>
    public int UnmatchedRequestCount { get; set; }
    /// <summary>Whether the evaluation budget ran out before every request was explained.</summary>
    public bool Truncated { get; set; }
    /// <summary>The unmatched requests, most recent first.</summary>
    public List<UnmatchedRequest> UnmatchedRequests { get; set; } = new();
    /// <summary>The AI-generated summary when narration was asked for, otherwise null.</summary>
    public ExplanationNarration? Narration { get; set; }
}

/// <summary>One unmatched request in an <see cref="UnmatchedExplanation"/>.</summary>
public sealed class UnmatchedRequest
{
    /// <summary>When the request was recorded, as the event log formats it.</summary>
    public string? Timestamp { get; set; }
    /// <summary>The request method.</summary>
    public string? Method { get; set; }
    /// <summary>The request path.</summary>
    public string? Path { get; set; }
    /// <summary>The evaluated expectations, closest (fewest differing fields) first.</summary>
    public List<ExplainedExpectation> ClosestExpectations { get; set; } = new();
    /// <summary>How many expectations were evaluated for this request.</summary>
    public int TotalExpectationsEvaluated { get; set; }
}

/// <summary>
/// Reads an enum from its name, mapping a name this client does not know to the enum's
/// <c>UNKNOWN</c> member rather than failing, so a newer server cannot break parsing.
/// </summary>
internal sealed class UnknownTolerantEnumConverter<T> : JsonConverter<T> where T : struct, Enum
{
    private static readonly T Unknown = (T)Enum.Parse(typeof(T), "UNKNOWN");

    public override T Read(ref Utf8JsonReader reader, Type typeToConvert, JsonSerializerOptions options)
    {
        var name = reader.TokenType == JsonTokenType.String ? reader.GetString() : null;
        foreach (T value in Enum.GetValues(typeof(T)))
        {
            if (string.Equals(value.ToString(), name, StringComparison.Ordinal))
                return value;
        }
        if (reader.TokenType != JsonTokenType.String)
            reader.Skip();
        return Unknown;
    }

    public override void Write(Utf8JsonWriter writer, T value, JsonSerializerOptions options)
        => writer.WriteStringValue(value.ToString());
}
