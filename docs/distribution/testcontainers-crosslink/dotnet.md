# .NET — Testcontainers Cross-Link Draft

## Publish Gate

**Do not post until `MockServer.Testcontainers` is live on NuGet.**

Verify:
```bash
dotnet add package MockServer.Testcontainers --version 9.2.0
# or: curl -sf "https://api.nuget.org/v3/registration5-gz-semver2/mockserver.testcontainers/index.json" | python3 -c "import sys,json,gzip; data=json.loads(gzip.decompress(sys.stdin.buffer.read())); print(data['items'][0]['items'][-1]['catalogEntry']['version'])"
```

The NuGet package version tracks the MockServer release (e.g. `9.2.0`); the
in-repo default lives in `Directory.Build.props` and is bumped by the release script.

---

## Target

**Repository:** https://github.com/testcontainers/testcontainers-dotnet

The testcontainers-dotnet project ships first-party modules in
`src/Testcontainers.<Module>/`. A `Testcontainers.MockServer` module may exist there.
The PR either:
- redirects the existing entry to the official MockServer-maintained NuGet package, or
- adds a new docs/README entry for the official module.

File most likely to PR against: the module list in `README.md` or the dedicated
`src/Testcontainers.MockServer/` directory (if present).

**Fallback:** open an issue at https://github.com/testcontainers/testcontainers-dotnet/issues.

---

## PR Title

```
docs: add official MockServer module — MockServer.Testcontainers (NuGet)
```

---

## PR / Issue Body

```markdown
## Summary

MockServer now ships an officially maintained Testcontainers module for .NET:
[`MockServer.Testcontainers`](https://www.nuget.org/packages/MockServer.Testcontainers/) (C# namespace stays `Testcontainers.MockServer`).

The module is maintained by the MockServer project and tracks each MockServer release.
It targets .NET 8+ and wraps `Testcontainers` 4.12.0.

## Install

```bash
dotnet add package MockServer.Testcontainers
```

## Quick start

```csharp
using Testcontainers.MockServer;

await using var container = new MockServerBuilder()
    .WithLogLevel("INFO")
    .Build();

await container.StartAsync();

var url = container.GetUrl();  // http://localhost:xxxxx

using var httpClient = new HttpClient();

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

var response = await httpClient.GetStringAsync($"{url}/hello");
// response == "world"
```

## Links

- NuGet: https://www.nuget.org/packages/MockServer.Testcontainers/
- Source: https://github.com/mock-server/mockserver/tree/master/mockserver-testcontainers/dotnet
- MockServer docs: https://www.mock-server.com
```

---

## Notes for the Submitter

- NuGet package ID: `MockServer.Testcontainers` (namespace/assembly stay `Testcontainers.MockServer`); version in this release: `9.2.0`.
- Targets `net8.0`; depends on `Testcontainers` 4.12.0.
- The builder class is `MockServerBuilder`; the started container exposes `GetUrl()`,
  `GetSecureUrl()`, and `GetConnectionString()`.
- File source: `mockserver-testcontainers/dotnet/src/Testcontainers.MockServer/Testcontainers.MockServer.csproj`
  and `mockserver-testcontainers/dotnet/Directory.Build.props` (PackageId, version verified).
