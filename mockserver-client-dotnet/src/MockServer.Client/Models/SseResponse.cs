using System.Text.Json.Serialization;

namespace MockServer.Client.Models;

/// <summary>
/// A single Server-Sent Event within an <see cref="HttpSseResponse"/>.
/// </summary>
public sealed class SseEvent
{
    [JsonPropertyName("event")]
    [JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)]
    public string? Event { get; set; }

    [JsonPropertyName("data")]
    [JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)]
    public string? Data { get; set; }

    [JsonPropertyName("id")]
    [JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)]
    public string? Id { get; set; }

    [JsonPropertyName("retry")]
    [JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)]
    public int? Retry { get; set; }

    /// <summary>
    /// A comment, sent before the other fields as <c>:</c> followed by each line exactly as written
    /// (<c>" hb"</c> sends <c>: hb</c>); an event with only a comment is a heartbeat that dispatches no event.
    /// </summary>
    [JsonPropertyName("comment")]
    [JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)]
    public string? Comment { get; set; }

    [JsonPropertyName("delay")]
    [JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)]
    public Delay? Delay { get; set; }
}

/// <summary>
/// Represents a Server-Sent Events (SSE) response action for MockServer.
/// The matched request is held open and the configured events are streamed
/// to the client as an <c>text/event-stream</c>.
/// </summary>
public sealed class HttpSseResponse
{
    [JsonPropertyName("statusCode")]
    [JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)]
    public int? StatusCode { get; set; }

    [JsonPropertyName("headers")]
    [JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)]
    public Dictionary<string, List<string>>? Headers { get; set; }

    [JsonPropertyName("events")]
    [JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)]
    public List<SseEvent>? Events { get; set; }

    [JsonPropertyName("closeConnection")]
    [JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)]
    public bool? CloseConnection { get; set; }

    /// <summary>
    /// The wait after the last event before the stream ends; without it the stream ends after the last event.
    /// </summary>
    [JsonPropertyName("closeDelay")]
    [JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)]
    public Delay? CloseDelay { get; set; }

    /// <summary>
    /// When true the stream stays open after its last event until the client leaves; <see cref="CloseDelay"/> is then ignored.
    /// </summary>
    [JsonPropertyName("keepStreamOpen")]
    [JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)]
    public bool? KeepStreamOpen { get; set; }

    [JsonPropertyName("delay")]
    [JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)]
    public Delay? Delay { get; set; }

    [JsonPropertyName("primary")]
    [JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)]
    public bool? Primary { get; set; }

    /// <summary>
    /// Creates a new SSE response builder.
    /// </summary>
    public static SseResponseBuilder Response() => new();
}
