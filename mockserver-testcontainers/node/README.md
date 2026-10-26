# @mockserver/testcontainers

A [Testcontainers](https://node.testcontainers.org/) module for [MockServer](https://www.mock-server.com) — starts a `mockserver/mockserver` Docker container for integration testing in Node.js/TypeScript.

## Install

```bash
npm install --save-dev @mockserver/testcontainers
```

Requires Docker to be running and Node.js >= 18.

## Usage

```typescript
import { MockServerContainer } from "@mockserver/testcontainers";

// Start a MockServer container (pulls the image if needed)
const container = await MockServerContainer.start();

// Get the URL to connect to
const url = container.getUrl(); // e.g. http://localhost:32789

// Create an expectation via the REST API
await fetch(`${url}/mockserver/expectation`, {
  method: "PUT",
  headers: { "Content-Type": "application/json" },
  body: JSON.stringify({
    httpRequest: { method: "GET", path: "/hello" },
    httpResponse: { statusCode: 200, body: "world" },
  }),
});

// Make requests against the mock
const response = await fetch(`${url}/hello`);
console.log(await response.text()); // "world"

// Stop the container when done
await container.stop();
```

## API

### `MockServerContainer.start(options?)`

Static factory that creates and starts a MockServer container. Returns a `StartedMockServerContainer`.

**Options:**

| Option | Type | Default | Description |
|--------|------|---------|-------------|
| `image` | `string` | `mockserver/mockserver:mockserver-9.2.0` | Docker image to use |
| `serverPort` | `number` | `1080` | Port MockServer listens on inside the container |
| `env` | `Record<string, string>` | `{}` | Environment variables (e.g. `MOCKSERVER_LOG_LEVEL`) |

### `StartedMockServerContainer`

| Method | Returns | Description |
|--------|---------|-------------|
| `getUrl()` | `string` | HTTP URL (`http://<host>:<port>`) |
| `getEndpoint()` | `string` | Alias for `getUrl()` |
| `getSecureEndpoint()` | `string` | HTTPS URL (`https://<host>:<port>`) |
| `getHost()` | `string` | Container host |
| `getPort()` | `number` | Mapped port on the host |
| `getMappedPort(port)` | `number` | Mapped port for a specific internal port |
| `getClient()` | `unknown` | A cached, ready-wired `mockserver-client` pointed at the mapped host/port (untyped — the client ships no TS types) |
| `stop()` | `Promise<void>` | Stops and removes the container |

## Build

```bash
npm install
npm run build
npm test
```

## License

Apache-2.0
