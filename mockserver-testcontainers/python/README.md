# testcontainers-mockserver

A [Testcontainers](https://testcontainers.com) module for [MockServer](https://www.mock-server.com) in Python.

Starts a `mockserver/mockserver` Docker container, waits for readiness, and provides
convenient accessors for the mapped host, port, and URL.

## Installation

```bash
pip install testcontainers-mockserver
```

## Usage

```python
from testcontainers_mockserver import MockServerContainer
import requests

with MockServerContainer() as mockserver:
    url = mockserver.get_url()  # e.g. "http://localhost:49152"

    # Create an expectation
    requests.put(f"{url}/mockserver/expectation", json={
        "httpRequest": {"method": "GET", "path": "/hello"},
        "httpResponse": {"statusCode": 200, "body": "world"},
    })

    # Match it
    resp = requests.get(f"{url}/hello")
    assert resp.text == "world"
```

## API

### `MockServerContainer(image=..., port=1080)`

- `image` — Docker image to use. Defaults to `mockserver/mockserver:mockserver-9.2.0`.
- `port` — Container port MockServer listens on. Defaults to `1080`.

### Methods

| Method | Returns | Description |
|--------|---------|-------------|
| `get_client()` | `MockServerClient` | A cached, ready-wired `mockserver` client pointed at the mapped host/port |
| `get_url()` | `str` | HTTP base URL (e.g. `http://localhost:49152`) |
| `get_secure_url()` | `str` | HTTPS base URL (same port, `https://` scheme) |
| `get_host()` | `str` | Mapped host IP |
| `get_port()` | `int` | Mapped host port |
| `with_server_port(port)` | `self` | Override the listen port |
| `with_log_level(level)` | `self` | Set `MOCKSERVER_LOG_LEVEL` |
| `with_property(key, value)` | `self` | Set any MockServer env var |
| `with_initialization_json(path)` | `self` | Point to a startup expectations JSON file |

## Building and Testing

```bash
# Install in editable mode with test dependencies
pip install -e .[test]

# Run unit tests (no Docker needed)
pytest tests/test_container_config.py

# Run all tests including integration (requires Docker)
pytest

# Skip Docker-dependent tests
pytest -m "not docker"
```

## Requirements

- Python >= 3.9
- Docker (for integration tests and actual usage)
- `testcontainers` >= 4.0.0
