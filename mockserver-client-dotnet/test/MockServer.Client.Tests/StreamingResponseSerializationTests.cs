using System.Text.Json;
using FluentAssertions;
using MockServer.Client.Models;
using Xunit;

namespace MockServer.Client.Tests;

/// <summary>
/// Serialization tests for the streaming / protocol response actions
/// (SSE, WebSocket, gRPC-stream, binary, DNS). Each asserts the exact MockServer
/// wire-format JSON keys, sourced from the Python reference client
/// (mockserver-client-python/mockserver/models.py) and mockserver-core model classes.
/// </summary>
public class StreamingResponseSerializationTests
{
    private static readonly JsonSerializerOptions JsonOptions = new()
    {
        DefaultIgnoreCondition = System.Text.Json.Serialization.JsonIgnoreCondition.WhenWritingNull,
        PropertyNamingPolicy = JsonNamingPolicy.CamelCase
    };

    // ---------------------------------------------------------------- SSE

    [Fact]
    public void SseResponse_SerializesUnderHttpSseResponse()
    {
        var expectation = new Expectation
        {
            HttpRequest = HttpRequest.Request().WithPath("/events").Build(),
            HttpSseResponse = HttpSseResponse.Response()
                .WithStatusCode(200)
                .WithHeader("Content-Type", "text/event-stream")
                .WithEvent(data: "hello", eventName: "greeting", id: "1")
                .WithEvent(new SseEvent { Data = "bye", Retry = 3000, Delay = new Delay { TimeUnit = TimeUnit.MILLISECONDS, Value = 100 } })
                .WithCloseConnection(true)
                .WithPrimary()
                .Build()
        };

        var json = JsonSerializer.Serialize(expectation, JsonOptions);
        var doc = JsonDocument.Parse(json);

        doc.RootElement.TryGetProperty("httpResponse", out _).Should().BeFalse();
        var sse = doc.RootElement.GetProperty("httpSseResponse");
        sse.GetProperty("statusCode").GetInt32().Should().Be(200);
        sse.GetProperty("headers").GetProperty("Content-Type")[0].GetString().Should().Be("text/event-stream");
        var events = sse.GetProperty("events");
        events.GetArrayLength().Should().Be(2);
        events[0].GetProperty("event").GetString().Should().Be("greeting");
        events[0].GetProperty("data").GetString().Should().Be("hello");
        events[0].GetProperty("id").GetString().Should().Be("1");
        events[1].GetProperty("retry").GetInt32().Should().Be(3000);
        events[1].GetProperty("delay").GetProperty("timeUnit").GetString().Should().Be("MILLISECONDS");
        sse.GetProperty("closeConnection").GetBoolean().Should().BeTrue();
        sse.GetProperty("primary").GetBoolean().Should().BeTrue();
    }

    [Fact]
    public void SseEvent_RoundTripsComment()
    {
        var sse = HttpSseResponse.Response()
            .WithEvent(new SseEvent { Comment = "heartbeat" })
            .WithEvent(data: "d")
            .Build();

        var json = JsonSerializer.Serialize(sse, JsonOptions);
        var events = JsonDocument.Parse(json).RootElement.GetProperty("events");
        events[0].GetProperty("comment").GetString().Should().Be("heartbeat");
        events[1].TryGetProperty("comment", out _).Should().BeFalse();

        var decoded = JsonSerializer.Deserialize<SseEvent>("{\"comment\":\"a\\nb\",\"data\":\"x\"}", JsonOptions)!;
        decoded.Comment.Should().Be("a\nb");
        decoded.Data.Should().Be("x");
    }

    [Fact]
    public void SseResponse_RoundTripsTheEndOfTheStream()
    {
        var sse = HttpSseResponse.Response()
            .WithEvent(data: "last")
            .WithCloseDelay(TimeUnit.SECONDS, 15)
            .WithKeepStreamOpen()
            .Build();

        var json = JsonSerializer.Serialize(sse, JsonOptions);
        var root = JsonDocument.Parse(json).RootElement;
        root.GetProperty("closeDelay").GetProperty("value").GetInt64().Should().Be(15);
        root.GetProperty("keepStreamOpen").GetBoolean().Should().BeTrue();

        var decoded = JsonSerializer.Deserialize<HttpSseResponse>(json, JsonOptions)!;
        decoded.CloseDelay!.Value.Should().Be(15);
        decoded.KeepStreamOpen.Should().BeTrue();

        var unset = JsonDocument.Parse(JsonSerializer.Serialize(HttpSseResponse.Response().Build(), JsonOptions)).RootElement;
        unset.TryGetProperty("closeDelay", out _).Should().BeFalse();
        unset.TryGetProperty("keepStreamOpen", out _).Should().BeFalse();
    }

    // ---------------------------------------------------------- WebSocket

    [Fact]
    public void WebSocketResponse_SerializesUnderHttpWebSocketResponse()
    {
        var binary = new byte[] { 1, 2, 3, 4 };
        var expectation = new Expectation
        {
            HttpRequest = HttpRequest.Request().WithPath("/ws").Build(),
            HttpWebSocketResponse = HttpWebSocketResponse.Response()
                .WithSubprotocol("chat")
                .WithTextMessage("hi")
                .WithBinaryMessage(binary)
                .WithCloseConnection(false)
                .Build()
        };

        var json = JsonSerializer.Serialize(expectation, JsonOptions);
        var doc = JsonDocument.Parse(json);

        var ws = doc.RootElement.GetProperty("httpWebSocketResponse");
        ws.GetProperty("subprotocol").GetString().Should().Be("chat");
        var messages = ws.GetProperty("messages");
        messages.GetArrayLength().Should().Be(2);
        messages[0].GetProperty("text").GetString().Should().Be("hi");
        messages[1].GetProperty("binary").GetString().Should().Be(Convert.ToBase64String(binary));
        ws.GetProperty("closeConnection").GetBoolean().Should().BeFalse();
    }

    // -------------------------------------------------------- gRPC stream

    [Fact]
    public void GrpcStreamResponse_SerializesUnderGrpcStreamResponse()
    {
        var expectation = new Expectation
        {
            HttpRequest = HttpRequest.Request().WithPath("/grpc").Build(),
            GrpcStreamResponse = GrpcStreamResponse.Response()
                .WithStatusName("OK")
                .WithStatusMessage("done")
                .WithHeader("grpc-encoding", "identity")
                .WithMessage("{\"id\":1}")
                .WithMessage(GrpcStreamMessage.OfJson("{\"id\":2}"))
                .Build()
        };

        var json = JsonSerializer.Serialize(expectation, JsonOptions);
        var doc = JsonDocument.Parse(json);

        var grpc = doc.RootElement.GetProperty("grpcStreamResponse");
        grpc.GetProperty("statusName").GetString().Should().Be("OK");
        grpc.GetProperty("statusMessage").GetString().Should().Be("done");
        grpc.GetProperty("headers").GetProperty("grpc-encoding")[0].GetString().Should().Be("identity");
        var messages = grpc.GetProperty("messages");
        messages.GetArrayLength().Should().Be(2);
        messages[0].GetProperty("json").GetString().Should().Be("{\"id\":1}");
        messages[1].GetProperty("json").GetString().Should().Be("{\"id\":2}");
    }

    // ------------------------------------------------------------- Binary

    [Fact]
    public void BinaryResponse_SerializesUnderBinaryResponseWithBinaryDataKey()
    {
        var bytes = new byte[] { 0xDE, 0xAD, 0xBE, 0xEF };
        var expectation = new Expectation
        {
            HttpRequest = HttpRequest.Request().WithPath("/raw").Build(),
            BinaryResponse = BinaryResponse.Response()
                .WithBytes(bytes)
                .WithDelay(TimeUnit.SECONDS, 1)
                .Build()
        };

        var json = JsonSerializer.Serialize(expectation, JsonOptions);
        var doc = JsonDocument.Parse(json);

        var bin = doc.RootElement.GetProperty("binaryResponse");
        bin.GetProperty("binaryData").GetString().Should().Be(Convert.ToBase64String(bytes));
        bin.GetProperty("delay").GetProperty("timeUnit").GetString().Should().Be("SECONDS");
    }

    [Fact]
    public void BinaryResponse_UpstreamRoundTrips()
    {
        var response = BinaryResponse.Response()
            .WithBase64("VXBzdHJlYW0=")
            .WithUpstream(BinaryUpstream.FORWARD_AND_REPLACE)
            .Build();

        var json = JsonSerializer.Serialize(response, JsonOptions);
        json.Should().Be(@"{""binaryData"":""VXBzdHJlYW0="",""upstream"":""FORWARD_AND_REPLACE""}");

        var decoded = JsonSerializer.Deserialize<BinaryResponse>(json, JsonOptions)!;
        decoded.Upstream.Should().Be(BinaryUpstream.FORWARD_AND_REPLACE);
        JsonSerializer.Serialize(decoded, JsonOptions).Should().Be(json);

        Enum.GetNames<BinaryUpstream>().Should().Equal("ANSWER_ONLY", "ANSWER_AND_FORWARD", "FORWARD_AND_REPLACE");
        JsonSerializer.Serialize(BinaryResponse.Response().WithBase64("AQID").Build(), JsonOptions)
            .Should().NotContain("upstream");
    }

    // ---------------------------------------------------------------- DNS

    [Fact]
    public void DnsResponse_SerializesUnderDnsResponse()
    {
        var expectation = new Expectation
        {
            HttpRequest = HttpRequest.Request().WithPath("/dns").Build(),
            DnsResponse = DnsResponse.Response()
                .WithResponseCode("NOERROR")
                .WithAnswerRecord(DnsRecord.ARecord("example.com", "1.2.3.4"))
                .WithAnswerRecord(DnsRecord.MxRecord("example.com", 10, "mail.example.com"))
                .WithAdditionalRecord(DnsRecord.AaaaRecord("example.com", "::1"))
                .Build()
        };

        var json = JsonSerializer.Serialize(expectation, JsonOptions);
        var doc = JsonDocument.Parse(json);

        var dns = doc.RootElement.GetProperty("dnsResponse");
        dns.GetProperty("responseCode").GetString().Should().Be("NOERROR");

        var answers = dns.GetProperty("answerRecords");
        answers.GetArrayLength().Should().Be(2);
        answers[0].GetProperty("name").GetString().Should().Be("example.com");
        answers[0].GetProperty("type").GetString().Should().Be("A");
        answers[0].GetProperty("value").GetString().Should().Be("1.2.3.4");
        answers[1].GetProperty("type").GetString().Should().Be("MX");
        answers[1].GetProperty("priority").GetInt32().Should().Be(10);
        answers[1].GetProperty("value").GetString().Should().Be("mail.example.com");

        var additional = dns.GetProperty("additionalRecords");
        additional[0].GetProperty("type").GetString().Should().Be("AAAA");
        additional[0].GetProperty("value").GetString().Should().Be("::1");
    }

    [Fact]
    public void DnsRecord_SrvRecord_SerializesAllFields()
    {
        var record = DnsRecord.SrvRecord("_sip._tcp.example.com", 10, 20, 5060, "sipserver.example.com");

        var json = JsonSerializer.Serialize(record, JsonOptions);
        var doc = JsonDocument.Parse(json);

        doc.RootElement.GetProperty("type").GetString().Should().Be("SRV");
        doc.RootElement.GetProperty("priority").GetInt32().Should().Be(10);
        doc.RootElement.GetProperty("weight").GetInt32().Should().Be(20);
        doc.RootElement.GetProperty("port").GetInt32().Should().Be(5060);
        doc.RootElement.GetProperty("value").GetString().Should().Be("sipserver.example.com");
    }

    // ----------------------------------------------- null-field omission

    [Fact]
    public void Responses_NullFields_AreOmitted()
    {
        var sse = JsonSerializer.Serialize(HttpSseResponse.Response().WithStatusCode(200).Build(), JsonOptions);
        JsonDocument.Parse(sse).RootElement.TryGetProperty("events", out _).Should().BeFalse();

        var binary = JsonSerializer.Serialize(BinaryResponse.Response().WithBase64("AAA=").Build(), JsonOptions);
        var binDoc = JsonDocument.Parse(binary).RootElement;
        binDoc.TryGetProperty("delay", out _).Should().BeFalse();
        binDoc.TryGetProperty("primary", out _).Should().BeFalse();
        binDoc.GetProperty("binaryData").GetString().Should().Be("AAA=");
    }
}
