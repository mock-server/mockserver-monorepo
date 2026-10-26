# MockServer.Testcontainers

A [Testcontainers](https://dotnet.testcontainers.org/) module for [MockServer](https://www.mock-server.com) that starts a `mockserver/mockserver` Docker container, waits for readiness, and provides connection helpers.

> **Package id:** `MockServer.Testcontainers`. The namespace stays `Testcontainers.MockServer` (NuGet's `Testcontainers.*` ID prefix is reserved), so the install id and the `using` differ by design.

## Installation

```bash
dotnet add package MockServer.Testcontainers
```

## Usage

```csharp
using Testcontainers.MockServer;

// Create and start a MockServer container
await using var container = new MockServerBuilder()
    .WithLogLevel("INFO")
    .Build();

await container.StartAsync();

// Get the connection URL
var url = container.GetUrl();        // http://localhost:xxxxx
var host = container.GetConnectionString(); // localhost:xxxxx

// Use MockServer via its REST API
using var httpClient = new HttpClient();

// Create an expectation
var expectation = """
    {
        "httpRequest": { "method": "GET", "path": "/hello" },
        "httpResponse": { "statusCode": 200, "body": "world" }
    }
    """;

await httpClient.SendAsync(new HttpRequestMessage(HttpMethod.Put, $"{url}/mockserver/expectation")
{
    Content = new StringContent(expectation, System.Text.Encoding.UTF8, "application/json")
});

// Issue a request that matches the expectation
var response = await httpClient.GetStringAsync($"{url}/hello");
// response == "world"
```

## Builder Configuration

| Method | Description |
|--------|-------------|
| `WithLogLevel(string)` | Set the MockServer log level (INFO, DEBUG, WARN, ERROR, TRACE) |
| `WithMockServerProperty(string, string)` | Set any MockServer configuration property as an env var |
| `WithImage(string)` | Override the Docker image (default: `mockserver/mockserver:mockserver-9.2.0`) |
| `WithPortBinding(int, int)` | Bind a specific host port to the container port |

## Container Helpers

| Method | Returns |
|--------|---------|
| `GetClient()` | A cached, ready-wired `MockServer.Client.MockServerClient` pointed at the mapped host/port |
| `GetUrl()` | HTTP endpoint: `http://host:port` |
| `GetSecureUrl()` | HTTPS endpoint: `https://host:port` |
| `GetConnectionString()` | `host:port` |
| `GetMappedPort()` | The host port mapped to container port 1080 |

## Building

```bash
cd mockserver-testcontainers/dotnet
dotnet build
```

## Testing

```bash
# Unit tests (no Docker required)
dotnet test --filter "Category!=Integration"

# All tests (Docker required)
dotnet test
```

## Requirements

- .NET 8.0 SDK
- Docker (for integration tests and actual usage)
