using System.Net;
using System.Text;
using System.Text.Json;
using FluentAssertions;
using MockServer.Client.Exceptions;
using MockServer.Client.Models;
using Xunit;

namespace MockServer.Client.Tests;

/// <summary>
/// Unit tests for the typed match-diagnostic calls (Explain, DebugMismatch, ExplainUnmatched). The response
/// bodies follow what the server writes (HttpState.explain / debugMismatch / explainUnmatched).
/// </summary>
public class DiagnosticsTests
{
    private sealed class FakeHandler : HttpMessageHandler
    {
        public HttpRequestMessage? LastRequest { get; private set; }
        public string? LastRequestBody { get; private set; }
        public HttpStatusCode ResponseStatusCode { get; set; } = HttpStatusCode.OK;
        public string ResponseBody { get; set; } = "";

        protected override async Task<HttpResponseMessage> SendAsync(HttpRequestMessage request, CancellationToken cancellationToken)
        {
            LastRequest = request;
            LastRequestBody = request.Content != null
                ? await request.Content.ReadAsStringAsync(cancellationToken)
                : null;
            return new HttpResponseMessage(ResponseStatusCode)
            {
                Content = new StringContent(ResponseBody, Encoding.UTF8, "application/json")
            };
        }
    }

    private static (MockServerClient Client, FakeHandler Handler) CreateClient(string responseBody = "{}")
    {
        var handler = new FakeHandler { ResponseBody = responseBody };
        return (new MockServerClient("http://localhost:1080", new HttpClient(handler)), handler);
    }

    private const string ExplainResponse = """
        {
          "correlationId" : "abc-123",
          "timestamp" : "2026-10-14T10:00:00.000Z",
          "recomputed" : true,
          "note" : "recomputed against the current expectations",
          "request" : { "timestamp" : "2026-10-14 09:59:59.000", "method" : "GET", "path" : "/orders",
                        "bodyTruncated" : true, "bodyTruncatedNote" : "only the first 1024 bytes were logged" },
          "atArrival" : { "outcome" : "MATCHED", "expectationId" : "catch-all", "expectationStillStored" : true },
          "sameOutcomeAsAtArrival" : true,
          "expectationsChangedSince" : true,
          "expectationsLastChanged" : "2026-10-14T09:59:59.500Z",
          "matchedExpectation" : { "expectationId" : "catch-all", "expectationMethod" : "GET", "expectationPath" : "/orders",
                                   "position" : 3, "priority" : 0, "matches" : true },
          "skippedExpectations" : [ {
            "expectationId" : "gold-tenant", "expectationMethod" : "GET", "expectationPath" : "/orders",
            "position" : 1, "priority" : 10, "reason" : "FIELDS_DIFFER",
            "explanation" : "the request differs on: headers", "matches" : false,
            "matchedFieldCount" : 17, "totalFieldCount" : 18, "differingFieldCount" : 1,
            "differences" : { "headers" : [ "multimap match failed expected: gold found: silver" ] },
            "remediation" : { "headers" : "check the X-Tenant header value" }
          }, {
            "expectationId" : "future-reason", "position" : 2, "priority" : 5,
            "reason" : "SOME_REASON_ADDED_LATER", "explanation" : "a newer server"
          } ],
          "shadowedExpectations" : [ {
            "expectationId" : "fallback", "position" : 4, "priority" : 0, "reason" : "LOWER_PRIORITY",
            "explanation" : "also matches, but catch-all is evaluated first", "matches" : true
          } ],
          "totalExpectations" : 600,
          "evaluatedExpectations" : 500,
          "truncated" : true,
          "maxExpectationsEvaluated" : 500
        }
        """;

    [Fact]
    public void Explain_SendsCorrelationIdAsQueryParameterWithNoBody()
    {
        var (client, handler) = CreateClient(ExplainResponse);

        client.Explain("abc 123/&x");

        handler.LastRequest!.Method.Should().Be(HttpMethod.Put);
        handler.LastRequest!.RequestUri!.AbsolutePath.Should().Be("/mockserver/explain");
        handler.LastRequest!.RequestUri!.Query.Should().Be("?correlationId=abc%20123%2F%26x");
        handler.LastRequestBody.Should().BeNull();
    }

    [Fact]
    public void Explain_ParsesEveryFieldOfTheExplanation()
    {
        var (client, _) = CreateClient(ExplainResponse);

        var explanation = client.Explain("abc-123");

        explanation.CorrelationId.Should().Be("abc-123");
        explanation.Timestamp.Should().Be("2026-10-14T10:00:00.000Z");
        explanation.Recomputed.Should().BeTrue();
        explanation.Note.Should().Be("recomputed against the current expectations");
        explanation.Request!.Method.Should().Be("GET");
        explanation.Request.Path.Should().Be("/orders");
        explanation.Request.Timestamp.Should().Be("2026-10-14 09:59:59.000");
        explanation.Request.BodyTruncated.Should().BeTrue();
        explanation.Request.BodyTruncatedNote.Should().Be("only the first 1024 bytes were logged");
        explanation.AtArrival!.Outcome.Should().Be(ArrivalOutcome.MATCHED);
        explanation.AtArrival.ExpectationId.Should().Be("catch-all");
        explanation.AtArrival.ExpectationStillStored.Should().BeTrue();
        explanation.SameOutcomeAsAtArrival.Should().BeTrue();
        explanation.ExpectationsChangedSince.Should().BeTrue();
        explanation.ExpectationsLastChanged.Should().Be("2026-10-14T09:59:59.500Z");
        explanation.MatchedExpectation!.ExpectationId.Should().Be("catch-all");
        explanation.MatchedExpectation.Position.Should().Be(3);
        explanation.MatchedExpectation.Priority.Should().Be(0);
        explanation.MatchedExpectation.Reason.Should().BeNull();
        explanation.ClosestExpectationId.Should().BeNull();
        explanation.TotalExpectations.Should().Be(600);
        explanation.EvaluatedExpectations.Should().Be(500);
        explanation.Truncated.Should().BeTrue();
        explanation.MaxExpectationsEvaluated.Should().Be(500);

        var skipped = explanation.SkippedExpectations[0];
        skipped.ExpectationId.Should().Be("gold-tenant");
        skipped.ExpectationMethod.Should().Be("GET");
        skipped.ExpectationPath.Should().Be("/orders");
        skipped.Position.Should().Be(1);
        skipped.Priority.Should().Be(10);
        skipped.Reason.Should().Be(ExplainReason.FIELDS_DIFFER);
        skipped.Explanation.Should().Be("the request differs on: headers");
        skipped.Matches.Should().BeFalse();
        skipped.MatchedFieldCount.Should().Be(17);
        skipped.TotalFieldCount.Should().Be(18);
        skipped.DifferingFieldCount.Should().Be(1);
        skipped.Differences["headers"].Should().Equal("multimap match failed expected: gold found: silver");
        skipped.Remediation["headers"].Should().Be("check the X-Tenant header value");

        explanation.ShadowedExpectations.Should().ContainSingle()
            .Which.Reason.Should().Be(ExplainReason.LOWER_PRIORITY);
    }

    [Fact]
    public void Explain_ReadsAReasonOrOutcomeItDoesNotKnowAsUnknown()
    {
        var (client, _) = CreateClient("""
            { "atArrival" : { "outcome" : "SOMETHING_NEW" },
              "skippedExpectations" : [ { "expectationId" : "x", "reason" : "SOME_REASON_ADDED_LATER" } ] }
            """);

        var explanation = client.Explain("abc-123");

        explanation.AtArrival!.Outcome.Should().Be(ArrivalOutcome.UNKNOWN);
        explanation.SkippedExpectations[0].Reason.Should().Be(ExplainReason.UNKNOWN);
    }

    [Fact]
    public void Explain_LeavesOptionalFieldsNullAndListsEmptyWhenAbsent()
    {
        var (client, _) = CreateClient("""
            { "correlationId" : "abc-123", "atArrival" : { "outcome" : "NO_MATCH" },
              "closestExpectationId" : "near", "totalExpectations" : 1, "evaluatedExpectations" : 1, "truncated" : false }
            """);

        var explanation = client.Explain("abc-123");

        explanation.MatchedExpectation.Should().BeNull();
        explanation.ClosestExpectationId.Should().Be("near");
        explanation.SameOutcomeAsAtArrival.Should().BeNull();
        explanation.ExpectationsLastChanged.Should().BeNull();
        explanation.MaxExpectationsEvaluated.Should().BeNull();
        explanation.Narration.Should().BeNull();
        explanation.AtArrival!.ExpectationId.Should().BeNull();
        explanation.AtArrival.ExpectationStillStored.Should().BeNull();
        explanation.AtArrival.ForwardFailure.Should().BeNull();
        explanation.SkippedExpectations.Should().BeEmpty();
        explanation.ShadowedExpectations.Should().BeEmpty();
    }

    [Theory]
    [InlineData(null)]
    [InlineData("")]
    [InlineData("  ")]
    public void Explain_RejectsABlankCorrelationIdWithoutCallingTheServer(string? correlationId)
    {
        var (client, handler) = CreateClient();

        var act = () => client.Explain(correlationId!);

        act.Should().Throw<ArgumentException>();
        handler.LastRequest.Should().BeNull();
    }

    [Fact]
    public void Explain_ThrowsWithTheServersReasonForAnUnknownCorrelationId()
    {
        var (client, handler) = CreateClient("""{ "error" : "no request is recorded with correlationId \"gone\"" }""");
        handler.ResponseStatusCode = HttpStatusCode.NotFound;

        var act = () => client.Explain("gone");

        act.Should().Throw<MockServerClientException>()
            .WithMessage("*HTTP 404*no request is recorded with correlationId \"gone\"*");
    }

    [Fact]
    public void Explain_ThrowsWhenTheResponseIsNotAnExplanation()
    {
        var (client, _) = CreateClient("not json");

        var act = () => client.Explain("abc-123");

        act.Should().Throw<MockServerClientException>().WithMessage("Unable to parse explain response*");
    }

    [Fact]
    public async Task ExplainAsync_ReturnsTheExplanation()
    {
        var (client, _) = CreateClient(ExplainResponse);

        var explanation = await client.ExplainAsync("abc-123");

        explanation.SkippedExpectations.Should().HaveCount(2);
    }

    private const string DebugMismatchResponse = """
        {
          "correlationId" : "d-1",
          "timestamp" : "2026-10-14T10:00:00Z",
          "totalExpectations" : 150,
          "evaluatedExpectations" : 100,
          "truncated" : true,
          "maxExpectationsEvaluated" : 100,
          "closestMatch" : { "expectationId" : "abc-123", "matchedFields" : 17, "totalFields" : 18 },
          "results" : [ {
            "expectationId" : "abc-123", "expectationPath" : "/api/users", "expectationMethod" : "POST",
            "matches" : false, "matchedFieldCount" : 17, "totalFieldCount" : 18,
            "differences" : { "method" : [ "string or regex match failed expected: POST found: GET" ] }
          }, {
            "expectationId" : "def-456", "expectationPath" : "/api/users", "expectationMethod" : "GET",
            "matches" : true, "matchedFieldCount" : 18, "totalFieldCount" : 18
          } ]
        }
        """;

    [Fact]
    public void DebugMismatch_SendsTheRequestAsTheBody()
    {
        var (client, handler) = CreateClient(DebugMismatchResponse);

        client.DebugMismatch(HttpRequest.Request().WithMethod("GET").WithPath("/api/users"));

        handler.LastRequest!.Method.Should().Be(HttpMethod.Put);
        handler.LastRequest!.RequestUri!.PathAndQuery.Should().Be("/mockserver/debugMismatch");
        using var body = JsonDocument.Parse(handler.LastRequestBody!);
        body.RootElement.GetProperty("method").GetString().Should().Be("GET");
        body.RootElement.GetProperty("path").GetString().Should().Be("/api/users");
    }

    [Fact]
    public void DebugMismatch_ParsesTheResultsAndClosestMatch()
    {
        var (client, _) = CreateClient(DebugMismatchResponse);

        var result = client.DebugMismatch(HttpRequest.Request().WithPath("/api/users"));

        result.CorrelationId.Should().Be("d-1");
        result.Timestamp.Should().Be("2026-10-14T10:00:00Z");
        result.TotalExpectations.Should().Be(150);
        result.EvaluatedExpectations.Should().Be(100);
        result.Truncated.Should().BeTrue();
        result.MaxExpectationsEvaluated.Should().Be(100);
        result.ClosestMatch!.ExpectationId.Should().Be("abc-123");
        result.ClosestMatch.MatchedFields.Should().Be(17);
        result.ClosestMatch.TotalFields.Should().Be(18);
        result.Results.Should().HaveCount(2);
        result.Results[0].ExpectationMethod.Should().Be("POST");
        result.Results[0].Matches.Should().BeFalse();
        result.Results[0].MatchedFieldCount.Should().Be(17);
        result.Results[0].TotalFieldCount.Should().Be(18);
        result.Results[0].Differences["method"].Should().Equal("string or regex match failed expected: POST found: GET");
        result.Results[1].Matches.Should().BeTrue();
        result.Results[1].Differences.Should().BeEmpty();
    }

    [Fact]
    public void DebugMismatch_ThrowsWithTheServersReasonOnABadRequest()
    {
        var (client, handler) = CreateClient("""{ "error" : "debugMismatch only supports HttpRequest definitions" }""");
        handler.ResponseStatusCode = HttpStatusCode.BadRequest;

        var act = () => client.DebugMismatch(HttpRequest.Request());

        act.Should().Throw<MockServerClientException>()
            .WithMessage("*HTTP 400*debugMismatch only supports HttpRequest definitions*");
    }

    [Fact]
    public void DebugMismatch_RejectsANullRequest()
    {
        var (client, handler) = CreateClient();

        var act = () => client.DebugMismatch(null!);

        act.Should().Throw<ArgumentNullException>();
        handler.LastRequest.Should().BeNull();
    }

    private const string ExplainUnmatchedResponse = """
        {
          "correlationId" : "u-1",
          "timestamp" : "2026-10-14T10:00:00Z",
          "unmatchedRequestCount" : 1,
          "truncated" : false,
          "unmatchedRequests" : [ {
            "timestamp" : "2026-10-14 09:59:00.000",
            "method" : "GET",
            "path" : "/api/order",
            "closestExpectations" : [ {
              "expectationId" : "orders", "expectationPath" : "/api/orders", "expectationMethod" : "GET",
              "matches" : false, "matchedFieldCount" : 17, "totalFieldCount" : 18, "differingFieldCount" : 1,
              "differences" : { "path" : [ "string or regex match failed expected: /api/orders found: /api/order" ] },
              "remediation" : { "path" : "check the path" },
              "suggestedFix" : {
                "patch" : [
                  { "op" : "test", "path" : "/httpRequest/path", "value" : "/api/orders" },
                  { "op" : "replace", "path" : "/httpRequest/path", "value" : "/api/order" },
                  { "op" : "remove", "path" : "/httpRequest/headers/X-Old" }
                ],
                "patchedFields" : [ "path" ],
                "unpatchedFields" : [ { "field" : "body", "reason" : "the event log kept only part of the body" } ],
                "matchesAfterPatch" : false
              }
            } ],
            "totalExpectationsEvaluated" : 1
          } ]
        }
        """;

    [Fact]
    public void ExplainUnmatched_SendsLimitAndSuggestFixes()
    {
        var (client, handler) = CreateClient(ExplainUnmatchedResponse);

        client.ExplainUnmatched(limit: 5, suggestFixes: true);

        handler.LastRequest!.Method.Should().Be(HttpMethod.Put);
        handler.LastRequest!.RequestUri!.PathAndQuery.Should().Be("/mockserver/explainUnmatched");
        using var body = JsonDocument.Parse(handler.LastRequestBody!);
        body.RootElement.GetProperty("limit").GetInt32().Should().Be(5);
        body.RootElement.GetProperty("suggestFixes").GetBoolean().Should().BeTrue();
    }

    [Fact]
    public void ExplainUnmatched_OmitsOptionsLeftAtTheServerDefault()
    {
        var (client, handler) = CreateClient(ExplainUnmatchedResponse);

        client.ExplainUnmatched();

        handler.LastRequestBody.Should().Be("{}");
    }

    [Fact]
    public void ExplainUnmatched_RejectsALimitBelowOne()
    {
        var (client, handler) = CreateClient();

        var act = () => client.ExplainUnmatched(limit: 0);

        act.Should().Throw<ArgumentOutOfRangeException>();
        handler.LastRequest.Should().BeNull();
    }

    [Fact]
    public void ExplainUnmatched_ParsesTheRequestsClosestExpectationsAndSuggestedFix()
    {
        var (client, _) = CreateClient(ExplainUnmatchedResponse);

        var result = client.ExplainUnmatched(suggestFixes: true);

        result.CorrelationId.Should().Be("u-1");
        result.UnmatchedRequestCount.Should().Be(1);
        result.Truncated.Should().BeFalse();
        var request = result.UnmatchedRequests.Should().ContainSingle().Subject;
        request.Timestamp.Should().Be("2026-10-14 09:59:00.000");
        request.Method.Should().Be("GET");
        request.Path.Should().Be("/api/order");
        request.TotalExpectationsEvaluated.Should().Be(1);
        var closest = request.ClosestExpectations.Should().ContainSingle().Subject;
        closest.ExpectationId.Should().Be("orders");
        closest.DifferingFieldCount.Should().Be(1);
        closest.Remediation["path"].Should().Be("check the path");

        var fix = closest.SuggestedFix!;
        fix.Patch.Select(o => o.Op).Should().Equal("test", "replace", "remove");
        fix.Patch[1].Path.Should().Be("/httpRequest/path");
        fix.Patch[1].Value!.Value.GetString().Should().Be("/api/order");
        fix.Patch[2].Value.Should().BeNull();
        fix.PatchedFields.Should().Equal("path");
        fix.UnpatchedFields.Should().ContainSingle().Which.Field.Should().Be("body");
        fix.UnpatchedFields[0].Reason.Should().Be("the event log kept only part of the body");
        fix.MatchesAfterPatch.Should().BeFalse();
    }

    [Fact]
    public void Explain_ParsesTheReasonAFailedForwardGaveAtArrival()
    {
        var (client, _) = CreateClient("""
            { "correlationId" : "abc-123",
              "atArrival" : { "outcome" : "MATCHED", "expectationId" : "stock-forward", "expectationStillStored" : true,
                              "forwardFailure" : { "statusCode" : 502, "reason" : "Connection refused", "reasonTruncated" : true,
                                                   "causeNamed" : false, "forwardLoopSuspected" : true, "hint" : "retrieve_logs" } } }
            """);

        var failure = client.Explain("abc-123").AtArrival!.ForwardFailure!;

        failure.StatusCode.Should().Be(502);
        failure.Reason.Should().Be("Connection refused");
        failure.ReasonTruncated.Should().BeTrue();
        failure.CauseNamed.Should().BeFalse();
        failure.ForwardLoopSuspected.Should().BeTrue();
        failure.Hint.Should().Be("retrieve_logs");
    }

    [Fact]
    public void Explain_LeavesOutTheForwardFailureFieldsAServerDidNotSend()
    {
        var (client, _) = CreateClient("""
            { "correlationId" : "abc-123",
              "atArrival" : { "outcome" : "FORWARDED_WITHOUT_EXPECTATION",
                              "forwardFailure" : { "reason" : "timed out", "causeNamed" : true } } }
            """);

        var failure = client.Explain("abc-123").AtArrival!.ForwardFailure!;

        failure.Reason.Should().Be("timed out");
        failure.CauseNamed.Should().BeTrue();
        failure.StatusCode.Should().BeNull();
        failure.ReasonTruncated.Should().BeFalse();
        failure.ForwardLoopSuspected.Should().BeNull();
        failure.Hint.Should().BeNull();
    }

    [Fact]
    public void Explain_ParsesTheNarration()
    {
        var (client, _) = CreateClient("""
            { "correlationId" : "abc-123",
              "narration" : { "available" : true, "aiGenerated" : true, "label" : "AI-generated, can be wrong",
                              "provider" : "OPENAI", "model" : "gpt-test", "text" : "gold needs X-Tenant" } }
            """);

        var narration = client.Explain("abc-123").Narration!;

        narration.Available.Should().BeTrue();
        narration.AiGenerated.Should().BeTrue();
        narration.Label.Should().Be("AI-generated, can be wrong");
        narration.Provider.Should().Be("OPENAI");
        narration.Model.Should().Be("gpt-test");
        narration.Text.Should().Be("gold needs X-Tenant");
        narration.Reason.Should().BeNull();
    }

    [Fact]
    public void ExplainUnmatched_ParsesAnUnavailableNarration()
    {
        var (client, _) = CreateClient("""
            { "unmatchedRequestCount" : 0, "unmatchedRequests" : [ ],
              "narration" : { "available" : false, "reason" : "llmExplainNarrationEnabled is false" } }
            """);

        var narration = client.ExplainUnmatched().Narration!;

        narration.Available.Should().BeFalse();
        narration.AiGenerated.Should().BeFalse();
        narration.Text.Should().BeNull();
        narration.Reason.Should().Be("llmExplainNarrationEnabled is false");
    }

    [Fact]
    public void ExplainUnmatched_ThrowsOnAServerFailure()
    {
        var (client, handler) = CreateClient("boom");
        handler.ResponseStatusCode = HttpStatusCode.InternalServerError;

        var act = () => client.ExplainUnmatched();

        act.Should().Throw<MockServerClientException>().WithMessage("*HTTP 500*boom*");
    }

    [Fact]
    public void ControlPlaneBearerToken_IsSentOnDiagnosticCalls()
    {
        var (client, handler) = CreateClient(ExplainResponse);
        client.WithControlPlaneBearerToken("secret");

        client.Explain("abc-123");

        handler.LastRequest!.Headers.Authorization!.Parameter.Should().Be("secret");
    }
}
