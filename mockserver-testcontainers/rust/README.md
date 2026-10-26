# testcontainers-mockserver

A [Testcontainers](https://crates.io/crates/testcontainers) module for [MockServer](https://www.mock-server.com) — start a `mockserver/mockserver` Docker container from your Rust integration tests.

## Install

Add to your `Cargo.toml`:

```toml
[dev-dependencies]
testcontainers-mockserver = "7.0"
testcontainers = { version = "0.23", features = ["blocking"] }
reqwest = { version = "0.13", features = ["blocking"] }
```

## Usage

```rust
use testcontainers::runners::SyncRunner;
use testcontainers_mockserver::MockServer;

#[test]
fn test_with_mockserver() {
    let container = MockServer::default().start().unwrap();
    let base_url = testcontainers_mockserver::base_url(&container);

    // Create an expectation
    let client = reqwest::blocking::Client::new();
    client.put(format!("{base_url}/mockserver/expectation"))
        .header("Content-Type", "application/json")
        .body(r#"[{
            "httpRequest": { "method": "GET", "path": "/hello" },
            "httpResponse": { "statusCode": 200, "body": "world" }
        }]"#)
        .send()
        .unwrap();

    // Call the mocked endpoint
    let resp = client.get(format!("{base_url}/hello")).send().unwrap();
    assert_eq!(resp.status(), 200);
    assert_eq!(resp.text().unwrap(), "world");
}
```

### Async

```rust
use testcontainers::runners::AsyncRunner;
use testcontainers_mockserver::MockServer;

#[tokio::test]
async fn test_with_mockserver_async() {
    let container = MockServer::default().start().await.unwrap();
    let base_url = testcontainers_mockserver::async_base_url(&container).await;
    // ... use base_url with an async HTTP client
}
```

### Wired client

`client(&container)` (sync) and `async_client(&container).await` return a ready-wired
[`mockserver-client`](https://crates.io/crates/mockserver-client) `MockServerClient`
pointed at the container's mapped host/port — the Rust analogue of the Java module's
`getClient()`:

```rust
use testcontainers::runners::SyncRunner;
use testcontainers_mockserver::MockServer;

let container = MockServer::default().start().unwrap();
let client = testcontainers_mockserver::client(&container).unwrap();
// client.when(...).respond(...);
```

### Configuration

```rust
use testcontainers_mockserver::MockServer;

let image = MockServer::new("mockserver-9.2.0")
    .with_env("MOCKSERVER_LOG_LEVEL", "DEBUG")
    .with_env("MOCKSERVER_MAX_EXPECTATIONS", "500")
    .with_server_port(9090);
```

## Build and Test

```bash
# Unit tests (no Docker required)
cargo test

# Integration test (requires Docker)
cargo test -- --ignored

# Lint
cargo clippy -- -D warnings
```

## Requirements

- Rust 1.70+
- Docker (for integration tests only)

## License

Apache-2.0
