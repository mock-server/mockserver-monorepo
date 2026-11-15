MockServer &nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp; [![Build status](https://badge.buildkite.com/3b6803f4fe98cb5ed7bf18292a1434f800b53d8fecb92811d8.svg?branch=master&style=square&theme=slack)](https://buildkite.com/mockserver/mockserver) 
[![OpenSSF Best Practices](https://www.bestpractices.dev/projects/16733/badge)](https://www.bestpractices.dev/projects/16733) 
[![GitHub license](https://img.shields.io/github/license/mock-server/mockserver-monorepo.svg)](https://github.com/mock-server/mockserver-monorepo/blob/master/LICENSE.md) 
[![GitHub stars](https://img.shields.io/github/stars/mock-server/mockserver-monorepo.svg)](https://github.com/mock-server/mockserver-monorepo/stargazers) 
[![Artifact Hub](https://img.shields.io/endpoint?url=https://artifacthub.io/badge/repository/mockserver)](https://artifacthub.io/packages/search?repo=mockserver) 
[![MCP Toplist](https://mcptoplist.com/badge/com.mock-server%2Fmockserver.svg)](https://mcptoplist.com/server/com.mock-server%2Fmockserver)
=====

**MockServer mocks, records, and chaos-tests any HTTP(S) dependency your application talks to — one binary, no per-protocol setup.**

## Why MockServer

- **One port, every protocol** — HTTP/1.1, HTTPS, HTTP/2, gRPC & gRPC-Web, WebSockets and raw TCP are auto-detected from the first bytes of each connection. Beyond that: HTTP/3 (QUIC, experimental), JSON-RPC for MCP/A2A mocking, and AsyncAPI-driven testing against Kafka/MQTT brokers.
- **Mock, proxy, or both** — return canned/templated/callback responses, or sit as a proxy (port forwarding, HTTP proxy, HTTPS tunneling, SOCKS) and record, inspect, and modify real traffic in flight, with full visibility into TLS-encrypted exchanges.
- **Chaos engineering built in** — inject latency, dropped connections, and errors on demand to see how your application copes when a dependency misbehaves.
- **Mock AI too** — chat-completion APIs for OpenAI, Anthropic, Gemini, Bedrock, Azure OpenAI and Ollama (including streaming), plus a built-in MCP server so AI coding assistants can drive MockServer directly.
- **Generate mocks, don't hand-write them** — from an OpenAPI/Swagger spec, with request matching on method, path, query, headers, cookies, and body (JSON, XML, JSONPath, XPath, regex).
- **Fits your stack** — Java, JavaScript/Node, Python, Ruby, Go, .NET, Rust, and PHP clients, plus JUnit and Spring support; runs as Docker, Helm/Kubernetes, a JAR, or a WAR, with a live dashboard and optional clustered state for multi-instance deployments.

See the [changelog](changelog.md) for what shipped in each version.

## Getting started in 60 seconds

Run MockServer with Docker, then mock an endpoint and call it:

```bash
# 1. Start MockServer
docker run -d --rm -p 1080:1080 mockserver/mockserver

# 2. Mock an endpoint: GET /hello -> 200 "Hello World"
#    (MockServer exposes a REST control plane on the same port)
curl -X PUT http://localhost:1080/mockserver/expectation \
  -H 'Content-Type: application/json' \
  -d '{
        "httpRequest":  { "method": "GET", "path": "/hello" },
        "httpResponse": { "statusCode": 200, "body": "Hello World" }
      }'

# 3. Call your mock
curl http://localhost:1080/hello
# -> Hello World
```

…or, on macOS / Linux, install with [Homebrew](https://brew.sh/) and run the `mockserver` command directly:

```bash
brew install mockserver
mockserver run --port 1080
```

For every way to run MockServer — Docker, docker-compose recipes, the `mockserver` CLI, a JVM-less binary bundle, Helm/Kubernetes, the JAR, and Testcontainers — see the [Self-Hosting MockServer guide](https://www.mock-server.com/mock_server/self_hosting_mockserver.html). The same setup can be driven from any client library or the dashboard at <http://localhost:1080/mockserver/dashboard>.

### One-command recipes

The [`examples/docker-compose`](examples/docker-compose) recipes are a single `docker compose up` each — mock from an OpenAPI spec, a record/replay proxy, a contract-validating proxy, or a chaos proxy:

```bash
cd examples/docker-compose/mock-from-openapi
docker compose up
curl http://localhost:1080/pets
```

### Drive it from Postman or Bruno

[![Run in Postman](https://run.pstmn.io/button.svg)](https://god.gw.postman.com/run-collection/3256712-63a2d67a-46d6-41fd-a544-0535e7393e7d?action=collection%2Ffork&source=rip_markdown&collection-url=entityId%3D3256712-63a2d67a-46d6-41fd-a544-0535e7393e7d%26entityType%3Dcollection%26workspaceId%3D1739eeee-5da1-4112-86a7-b6c094f2b527)

- **Postman** — click the button above, or import [`examples/postman`](examples/postman) ([guide](https://www.mock-server.com/where/postman.html)).
- **Bruno** (open-source, git-native) — open [`examples/bruno`](examples/bruno) in [Bruno](https://www.usebruno.com/) via **Open Collection** ([guide](https://www.mock-server.com/where/bruno.html)).

## Install

| Channel | Command |
|---------|---------|
| Docker | `docker run -d --rm -p 1080:1080 mockserver/mockserver` |
| Docker from GitHub Container Registry (avoids Docker Hub's pull limit) | `docker run -d --rm -p 1080:1080 ghcr.io/mock-server/mockserver` |
| Homebrew (macOS/Linux) | `brew install mockserver` |
| Helm ([chart guide](helm/mockserver/README.md)) | `helm upgrade --install --namespace mockserver mockserver oci://ghcr.io/mock-server/charts/mockserver` |
| Maven Central [![mockserver](https://img.shields.io/maven-central/v/org.mock-server/mockserver-netty.svg)](https://central.sonatype.com/search?q=g:org.mock-server) | `org.mock-server:mockserver-netty-no-dependencies` — see [all Maven artifacts](https://www.mock-server.com/where/maven_central.html) (server, WARs, JUnit 4/5, Spring, Maven plugin) |
| npm | [`mockserver-node`](https://www.npmjs.org/package/mockserver-node) (start/stop) |

**Client libraries** (create expectations, verify requests, drive the control plane from your tests):

[![Java](https://img.shields.io/maven-central/v/org.mock-server/mockserver-client-java.svg?label=Java)](https://central.sonatype.com/artifact/org.mock-server/mockserver-client-java)
[![Node](https://img.shields.io/npm/v/mockserver-client.svg?label=Node)](https://www.npmjs.org/package/mockserver-client)
[![PyPI](https://img.shields.io/pypi/v/mockserver-client.svg?label=Python)](https://pypi.org/project/mockserver-client/)
[![Gem](https://badge.fury.io/rb/mockserver-client.png)](https://rubygems.org/gems/mockserver-client)
[![Go](https://pkg.go.dev/badge/github.com/mock-server/mockserver-monorepo/mockserver-client-go/v9.svg)](https://pkg.go.dev/github.com/mock-server/mockserver-monorepo/mockserver-client-go/v9)
[![Packagist](https://img.shields.io/packagist/v/mock-server/mockserver-client.svg?label=PHP)](https://packagist.org/packages/mock-server/mockserver-client)
[![NuGet](https://img.shields.io/nuget/v/MockServerClient.svg?label=.NET)](https://www.nuget.org/packages/MockServerClient)
[![Crates.io](https://img.shields.io/crates/v/mockserver-client.svg?label=Rust)](https://crates.io/crates/mockserver-client)

Full install options (SNAPSHOT repository, WAR deployment, Testcontainers modules, package-manager channels) are on the [Self-Hosting guide](https://www.mock-server.com/mock_server/self_hosting_mockserver.html) and [Where to find MockServer](https://www.mock-server.com/where/maven_central.html).

## Requirements

**Runtime:** Java 17+ (raised from Java 11 in MockServer 6.0.0 — see the [Java 17 / Jakarta upgrade guide](docs/operations/migration-java17-jakarta.md); pin to `5.15.x` if you need Java 11). The official Docker image bundles its own JVM, so containerised users need no JVM of their own.

**Building from source:** JDK 17+.

**Security note:** MockServer is a **development and testing tool only** — see [SECURITY.md](SECURITY.md).

## Documentation

- Usage guide: [www.mock-server.com](https://www.mock-server.com/)
- Architecture, code structure, infrastructure, and operations docs: [docs/](docs/README.md)
- AI/MCP integration: built-in [MCP](https://modelcontextprotocol.io) server at `/mockserver/mcp` — see [llms.txt](https://www.mock-server.com/llms.txt) and the [AI Integration docs](https://www.mock-server.com/mock_server/ai_mcp_setup.html)

## Versions

[![Latest release](https://img.shields.io/maven-central/v/org.mock-server/mockserver-netty.svg?label=latest)](https://github.com/mock-server/mockserver-monorepo/releases)

- **What changed:** [changelog](changelog.md) and [GitHub releases](https://github.com/mock-server/mockserver-monorepo/releases) (every version is also a [git tag](https://github.com/mock-server/mockserver-monorepo/tags) `mockserver-<version>`).
- **API docs for a version:** Java API at `https://mock-server.com/versions/<version>/apidocs/index.html`; REST API on [SwaggerHub](https://app.swaggerhub.com/apis/jamesdbloom/mock-server-openapi) (one spec per `major.minor.x`).
- **Java 11:** the last Java 11-compatible release is [5.15.0](https://github.com/mock-server/mockserver-monorepo/tree/mockserver-5.15.0) (January 2023).

> **6.0.0 breaking change:** the `<classifier>shaded</classifier>` Maven form was removed. Replace `mockserver-netty:<version>:shaded` with `mockserver-netty-no-dependencies:<version>` (same shaded bytes, new coordinates).

## Community & Contributing

- **Issues / bugs / feature requests:** [GitHub Issues](https://github.com/mock-server/mockserver-monorepo/issues?state=open) — please include your MockServer version, how you're running it (Docker, Maven plugin, etc.), and INFO-level (or higher) log output.
- **Discussions:** [GitHub Discussions](https://github.com/mock-server/mockserver-monorepo/discussions)
- **Roadmap:** [GitHub Project](https://github.com/orgs/mock-server/projects/1)
- **Security policy:** [SECURITY.md](SECURITY.md)
- **Community tools:** [MockServer Browser Admin](https://github.com/johnnywang1994/mockserver-browser-admin), a React + TypeScript web UI for managing expectations
- **Contributing:** pull requests are very welcome — read [CONTRIBUTING.md](CONTRIBUTING.md) first, then check the [open issues](https://github.com/mock-server/mockserver-monorepo/issues?state=open) and let us know if you intend to work on something.

### Maintainers
* [James D Bloom](https://blog.jamesdbloom.com)
