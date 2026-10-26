# testcontainers-mockserver (Ruby)

A [Testcontainers](https://github.com/testcontainers/testcontainers-ruby) module for
[MockServer](https://www.mock-server.com) — the open-source HTTP(S) mock server and proxy.

It starts the official `mockserver/mockserver` Docker image, waits for MockServer to
become ready (`PUT /mockserver/status` → `200`), and exposes connection helpers plus a
ready-wired [`mockserver-client`](https://rubygems.org/gems/mockserver-client) instance.

## Install

```ruby
# Gemfile
gem "testcontainers-mockserver", group: :test
```

```sh
bundle install
```

Requires Ruby >= 3.0 and a running Docker daemon.

## Usage

```ruby
require "testcontainers/mockserver"

container = Testcontainers::MockServerContainer.new.start

# A ready-wired MockServer client pointed at the mapped host/port
client = container.client
client
  .when(MockServer::HttpRequest.request(path: "/hello"))
  .respond(MockServer::HttpResponse.response(status_code: 200, body: "world"))

# Point the system under test at the container endpoint
container.endpoint        # => "http://localhost:49152"
container.secure_endpoint # => "https://localhost:49152" (HTTP/HTTPS share the port)

container.stop            # also closes the cached client
```

Block form auto-stops the container:

```ruby
Testcontainers::MockServerContainer.new.use do |container|
  client = container.client
  # ...
end
```

By default the image tag is derived from the MockServer client library version on the
load path (`mockserver/mockserver:mockserver-<version>`), so the container and client
always match. Falls back to `:latest` when the version cannot be resolved. Pass an
explicit image to pin it:

```ruby
Testcontainers::MockServerContainer.new("mockserver/mockserver:mockserver-9.2.0")
```

## Configuration helpers

Each returns the container for chaining, and must be called before `start`.

| Helper | What it does |
|--------|--------------|
| `with_server_port(port)` | Change the port MockServer listens on inside the container. |
| `with_log_level(level)` | Set `MOCKSERVER_LOG_LEVEL` (e.g. `"DEBUG"`). |
| `with_property(key, value)` | Set any MockServer configuration property by its env-var name. |
| `with_initialization_json(path)` | Mount a JSON file and load its expectations at startup. |

## Connection helpers

| Method | Returns |
|--------|---------|
| `client` | A cached `MockServer::Client` connected to the container. |
| `endpoint` | `http://host:port` |
| `secure_endpoint` | `https://host:port` (same unified port) |
| `server_port` | The mapped host port. |
| `host` | The host MockServer is reachable on. |

## License

Apache-2.0
