# mockserver-testcontainers (PHP)

A [Testcontainers](https://github.com/testcontainers/testcontainers-php) module for
[MockServer](https://www.mock-server.com) — the open-source HTTP(S) mock server and proxy.

It starts the official `mockserver/mockserver` Docker image, waits for MockServer to
become ready (`PUT /mockserver/status` → `200`), and exposes connection helpers plus a
ready-wired [`mock-server/mockserver-client`](https://packagist.org/packages/mock-server/mockserver-client)
instance.

## Install

```sh
composer require --dev mock-server/mockserver-testcontainers
```

Requires PHP >= 8.1 and a running Docker daemon.

## Usage

```php
use MockServer\Testcontainers\MockServerContainer;
use MockServer\HttpRequest;
use MockServer\HttpResponse;

$container = (new MockServerContainer())->start();

// A ready-wired MockServer client pointed at the mapped host/port.
// (getMockServerClient() is the PHP analogue of Java's getClient() — the
// inherited getClient() name is reserved by Testcontainers for the Docker client.)
$client = $container->getMockServerClient();
$client
    ->when(HttpRequest::request()->method('GET')->path('/hello'))
    ->respond(HttpResponse::response()->statusCode(200)->body('world'));

// Point the system under test at the container endpoint
$container->getEndpoint();        // "http://localhost:49152"
$container->getSecureEndpoint();  // "https://localhost:49152" (HTTP/HTTPS share the port)

$container->stop();
```

By default the image tag is derived from the installed MockServer PHP client version
(`mockserver/mockserver:mockserver-<version>`), so the container and client always match.
It falls back to `:latest` when the version cannot be resolved to a release version. Pass
an explicit image to pin it:

```php
new MockServerContainer('mockserver/mockserver:mockserver-9.2.0');
```

## Configuration helpers

Each returns the container for chaining, and must be called before `start()`.

| Method | What it does |
|--------|--------------|
| `withServerPort(int)` | Change the port MockServer listens on inside the container. |
| `withLogLevel(string)` | Set `MOCKSERVER_LOG_LEVEL` (e.g. `"DEBUG"`). |
| `withMockServerProperty(string, string)` | Set any MockServer configuration property by its env-var name. |
| `withInitializationJson(string)` | Copy a JSON file into the container and load its expectations at startup. |

Standard Testcontainers `GenericContainer` helpers (`withNetwork`, `withEnvironment`,
`withExposedPorts`, …) are also available via inheritance.

## Connection helpers (on the started container)

| Method | Returns |
|--------|---------|
| `getMockServerClient()` | A cached `MockServer\MockServerClient` connected to the container. |
| `getEndpoint()` | `http://host:port` |
| `getSecureEndpoint()` | `https://host:port` (same unified port) |
| `getServerPort()` | The mapped host port. |
| `getHost()` | The host MockServer is reachable on. |

## License

Apache-2.0
