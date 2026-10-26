# MockServer

[![Docker Pulls](https://img.shields.io/docker/pulls/mockserver/mockserver.svg)](https://hub.docker.com/r/mockserver/mockserver/)
[![License](https://img.shields.io/github/license/mock-server/mockserver-monorepo.svg)](https://github.com/mock-server/mockserver-monorepo/blob/master/LICENSE.md)

## Websites

- **Website & docs:** https://www.mock-server.com
- **Source & issues:** https://github.com/mock-server/mockserver-monorepo
- **Changelog:** https://github.com/mock-server/mockserver-monorepo/blob/master/changelog.md

## Main Features

MockServer is an HTTP(S) **mock server** and **proxy** for testing. **Mock** the APIs your application depends on, so you can develop and test against systems that are unavailable, incomplete, or hard to reproduce. **Proxy** real traffic to record, inspect, and modify requests and responses in flight, pausing them at interactive **proxy breakpoints** to step through, edit, or abort each exchange like a debugger for network traffic. And with built-in **chaos engineering**, make dependencies misbehave on demand — injecting latency, dropped connections, and errors — to test how your application copes when the systems it relies on degrade or fail.

**Speaks every protocol your stack uses.** HTTP/1.1 & HTTPS, HTTP/2, gRPC & gRPC-Web, WebSockets and raw TCP are auto-detected from the first bytes of each connection, so one MockServer port handles them all with no per-protocol configuration. Beyond that core: HTTP/3 (QUIC — experimental, on its own UDP port), JSON-RPC for MCP/A2A mocking, AsyncAPI-driven message-broker testing against external Kafka and MQTT brokers, and mocked AI/LLM chat-completion APIs (OpenAI, Anthropic, Gemini, Bedrock, Azure OpenAI, Ollama).

- **Mock any API** — HTTP/1.1, HTTPS, HTTP/2, HTTP/3, gRPC, gRPC-Web, JSON-RPC, WebSockets, raw TCP, and message brokers (Kafka, MQTT). Match requests on method, path, query, headers, cookies and body (JSON, XML, JSONPath, XPath, regex, OpenAPI) and return configured responses.
- **Proxy & record** — port forwarding, web (HTTP) proxy, HTTPS tunneling (CONNECT) and SOCKS, with full visibility of even TLS-encrypted traffic.
- **Dynamic responses** — response templating (Velocity, Mustache, JavaScript), class/closure callbacks and webhooks.
- **OpenAPI** — generate expectations directly from an OpenAPI/Swagger specification.
- **Verification** — assert which requests were received, in what order, and how many times.
- **Chaos & resilience testing** — inject latency, dropped/slow connections and failures to test how your system copes with a misbehaving dependency.
- **LLM / AI mocking** — mock chat-completion APIs for OpenAI, Anthropic, Gemini, Bedrock, Azure OpenAI and Ollama (including streaming), plus a built-in MCP server for AI coding assistants.
- **Live dashboard** — watch requests, expectations and logs in real time at `/mockserver/dashboard`.
- **Clients & integrations** — Java, JavaScript/Node, Python and Ruby clients, plus JUnit and Spring support.
- **Run anywhere** — Docker, Helm/Kubernetes, JAR or WAR, with optional clustered state for multi-instance deployments.

## Quick Start

```bash
docker run -d --rm --name mockserver -p 1080:1080 mockserver/mockserver
```

- REST/control API and mocked endpoints: `http://localhost:1080`
- Dashboard (live request & expectation log): `http://localhost:1080/mockserver/dashboard`

Stop it with `docker stop mockserver`.

## Which image should I use?

The same software is published as several **image variants**, distinguished by **tag prefix/suffix**. For most users the default image is correct.

| Tag pattern (examples) | Variant | Use it when |
|---|---|---|
| `latest`, `9.1.0` | **Default** — distroless, runs as **non-root**. Smallest and most secure; no shell. | The normal choice for almost everyone. |
| `latest-graaljs`, `9.1.0-graaljs` | **GraalJS** — adds the GraalVM JavaScript engine. | You use **JavaScript** response/forward **templates** (the default image only supports Velocity/Mustache + JS-free use). |
| `latest-http3`, `<version>-http3` | **HTTP/3 (deprecated)** — now the same as the default image, which serves HTTP/3 itself. | Nothing: kept for one more release so existing users don't break. Use the default image with `http3Port` set (experimental HTTP/3 over UDP) instead. |
| `clustered-latest`, `clustered-9.1.0` | **Clustered** — bundles the Infinispan state backend (JGroups). | You run **multiple MockServer instances** that must share expectations/state (`MOCKSERVER_STATE_BACKEND=infinispan`). |
| `root`, `root-snapshot` | **Root** — same as default/snapshot but runs as **root**. | A platform requires the container to run as root (most don't). |
| `snapshot`, `snapshot-graaljs`, `snapshot-clustered`, `snapshot-http3`, `mockserver-snapshot` | **Snapshot** — built from the latest `master`, one per merge. | You want the bleeding-edge unreleased build (not for production). `snapshot-clustered` is the clustered variant of the same build. |

A separate image, **`mockserver/mockserver-webhook`**, is the Kubernetes admission webhook that auto-injects a MockServer proxy sidecar — see the [service-mesh docs](https://www.mock-server.com).

### Tag forms

Every released version is available under two equivalent tag forms — pick whichever you prefer:

- bare version: `mockserver/mockserver:9.1.0`
- `mockserver-` prefixed: `mockserver/mockserver:mockserver-9.1.0`

`latest` always points at the most recent release of the default variant. **Pin to an explicit version (e.g. `9.1.0`) in production**; `latest`/`snapshot` are moving tags.

### Registries

Images are published to **Docker Hub** and copied to the **GitHub Container Registry (GHCR)** and **AWS ECR Public**:

```bash
docker pull mockserver/mockserver:9.1.0
docker pull ghcr.io/mock-server/mockserver:9.1.0
docker pull public.ecr.aws/t2x9c0i6/mockserver:9.1.0
```

Each tag has the **same image digest** on all three registries. Docker Hub limits anonymous pulls, so if your CI builds hit that limit, pull from GHCR or ECR Public instead; GHCR is the natural choice for GitHub Actions. The webhook image is at `ghcr.io/mock-server/mockserver-webhook` and `public.ecr.aws/t2x9c0i6/mockserver-webhook`.

GHCR carries every release from 7.1.0 onwards, with the same tags as Docker Hub except the experimental `-aot` tags and the `snapshot`/`root` tags, which are not on GHCR.

## How to use

### Run in the foreground (logs to console)

```bash
docker run --rm --name mockserver -p 1080:1080 mockserver/mockserver
```

### Change the port

The server listens on `1080` inside the container; map it to any host port:

```bash
docker run -d --rm --name mockserver -p 9090:1080 mockserver/mockserver
```

### Pass command-line options

The default command is `-logLevel INFO -serverPort 1080`. Append your own to override — for example, run as a port-forwarding proxy:

```bash
docker run --rm --name mockserver -p 1090:1090 mockserver/mockserver \
  -logLevel INFO -serverPort 1090 -proxyRemotePort 443 -proxyRemoteHost www.mock-server.com
```

### Configure via environment variables

Any [configuration property](https://www.mock-server.com/mock_server/configuration_properties.html) can be set as an env var (`MOCKSERVER_` prefix, upper-snake-case):

```bash
docker run -d --rm --name mockserver -p 1080:1080 \
  -e MOCKSERVER_MAX_EXPECTATIONS=200 \
  -e MOCKSERVER_LOG_LEVEL=WARN \
  mockserver/mockserver
```

### docker-compose

```yaml
services:
  mockServer:
    image: mockserver/mockserver:9.1.0
    ports:
      - 1080:1080
    environment:
      - MOCKSERVER_MAX_EXPECTATIONS=100
      - MOCKSERVER_MAX_HEADER_SIZE=8192
```

### Mount a properties file and/or initial expectations

The container reads `/config/mockserver.properties` and extra classpath jars from `/libs`:

```bash
docker run -d --rm --name mockserver -p 1080:1080 \
  -v "$(pwd)/config:/config" \
  mockserver/mockserver
```

Point `mockserver.properties` at an initialization JSON to pre-load expectations on startup — see [initializing expectations](https://www.mock-server.com/mock_server/initializing_expectations.html).

## Verifying image signatures

Release images are signed with [cosign](https://github.com/sigstore/cosign) using MockServer's signing key, published at **https://www.mock-server.com/mockserver-cosign.pub**:

```bash
cosign verify \
  --key https://www.mock-server.com/mockserver-cosign.pub \
  mockserver/mockserver:9.1.0
```

The same key signs every image variant (`-graaljs`, `-http3`, `clustered-…`), the GHCR and ECR Public copies (for example `cosign verify --key https://www.mock-server.com/mockserver-cosign.pub ghcr.io/mock-server/mockserver:9.1.0`), and the Helm chart.

## More

Full documentation, client libraries (Java, JavaScript, Python, Ruby), the Helm chart, and examples are at **[www.mock-server.com](https://www.mock-server.com)**. Report issues on [GitHub](https://github.com/mock-server/mockserver-monorepo/issues).
