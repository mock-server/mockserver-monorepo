namespace MockServer.Client.Models;

/// <summary>
/// Fluent builder for <see cref="HttpSseResponse"/>.
/// </summary>
public sealed class SseResponseBuilder
{
    private readonly HttpSseResponse _response = new();

    public SseResponseBuilder WithStatusCode(int statusCode)
    {
        _response.StatusCode = statusCode;
        return this;
    }

    public SseResponseBuilder WithHeader(string name, params string[] values)
    {
        _response.Headers ??= new Dictionary<string, List<string>>();
        _response.Headers[name] = new List<string>(values);
        return this;
    }

    /// <summary>
    /// Appends a Server-Sent Event to the stream.
    /// </summary>
    public SseResponseBuilder WithEvent(SseEvent sseEvent)
    {
        _response.Events ??= new List<SseEvent>();
        _response.Events.Add(sseEvent);
        return this;
    }

    /// <summary>
    /// Appends a Server-Sent Event built from its component fields.
    /// </summary>
    public SseResponseBuilder WithEvent(string? data, string? eventName = null, string? id = null, int? retry = null)
        => WithEvent(new SseEvent { Data = data, Event = eventName, Id = id, Retry = retry });

    public SseResponseBuilder WithCloseConnection(bool closeConnection = true)
    {
        _response.CloseConnection = closeConnection;
        return this;
    }

    public SseResponseBuilder WithDelay(TimeUnit timeUnit, long value)
    {
        _response.Delay = new Delay { TimeUnit = timeUnit, Value = value };
        return this;
    }

    /// <summary>
    /// Waits this long after the last event before ending the stream.
    /// </summary>
    public SseResponseBuilder WithCloseDelay(TimeUnit timeUnit, long value)
    {
        _response.CloseDelay = new Delay { TimeUnit = timeUnit, Value = value };
        return this;
    }

    /// <summary>
    /// Keeps the stream open after its last event until the client leaves.
    /// </summary>
    public SseResponseBuilder WithKeepStreamOpen(bool keepStreamOpen = true)
    {
        _response.KeepStreamOpen = keepStreamOpen;
        return this;
    }

    public SseResponseBuilder WithPrimary(bool primary = true)
    {
        _response.Primary = primary;
        return this;
    }

    public HttpSseResponse Build() => _response;

    public static implicit operator HttpSseResponse(SseResponseBuilder builder) => builder.Build();
}
