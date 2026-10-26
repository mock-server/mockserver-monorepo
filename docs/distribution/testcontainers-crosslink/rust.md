# Rust — Testcontainers Cross-Link Draft

## Publish Gate

**Do not post until `testcontainers-mockserver` is live on crates.io.**

Verify:
```bash
cargo add testcontainers-mockserver --dev
# or: curl -sf https://crates.io/api/v1/crates/testcontainers-mockserver | python3 -c "import sys,json; print(json.load(sys.stdin)['crate']['newest_version'])"
```

---

## Target

**Repository:** https://github.com/testcontainers/testcontainers-rs

The testcontainers-rs crate is the core Rust Testcontainers library. Community modules
are typically shipped as separate crates or listed in the README/wiki. A `mockserver`
module/image descriptor may exist in the repo. The PR either:
- adds a note in the modules listing pointing at the official maintained crate, or
- adds a new docs entry for `testcontainers-mockserver`.

File most likely to PR against: `README.md` or the modules section of the documentation.

**Fallback:** open an issue at https://github.com/testcontainers/testcontainers-rs/issues.

---

## PR Title

```
docs: add official MockServer module — testcontainers-mockserver (crates.io)
```

---

## PR / Issue Body

```markdown
## Summary

MockServer now ships an officially maintained Testcontainers module for Rust:
[`testcontainers-mockserver`](https://crates.io/crates/testcontainers-mockserver).

The crate is maintained by the MockServer project, targets Rust 1.70+, and wraps
`testcontainers` 0.23.

## Install

Add to `Cargo.toml`:

```toml
[dev-dependencies]
testcontainers-mockserver = "7.0"
testcontainers = { version = "0.23", features = ["blocking"] }
```

## Quick start

```rust
use testcontainers::runners::SyncRunner;
use testcontainers_mockserver::MockServer;

#[test]
fn test_with_mockserver() {
    let container = MockServer::default().start().unwrap();
    let base_url = testcontainers_mockserver::base_url(&container);

    let client = reqwest::blocking::Client::new();
    client.put(format!("{base_url}/mockserver/expectation"))
        .header("Content-Type", "application/json")
        .body(r#"[{"httpRequest":{"method":"GET","path":"/hello"},"httpResponse":{"statusCode":200,"body":"world"}}]"#)
        .send().unwrap();

    let resp = client.get(format!("{base_url}/hello")).send().unwrap();
    assert_eq!(resp.status(), 200);
    assert_eq!(resp.text().unwrap(), "world");
}
```

Async variant (tokio):

```rust
use testcontainers::runners::AsyncRunner;
use testcontainers_mockserver::MockServer;

#[tokio::test]
async fn test_async() {
    let container = MockServer::default().start().await.unwrap();
    let base_url = testcontainers_mockserver::async_base_url(&container).await;
    // ... use base_url
}
```

## Links

- crates.io: https://crates.io/crates/testcontainers-mockserver
- Source: https://github.com/mock-server/mockserver/tree/master/mockserver-testcontainers/rust
- MockServer docs: https://www.mock-server.com
```

---

## Notes for the Submitter

- Crate name: `testcontainers-mockserver`; version in this release: `9.2.0`.
- Rust 1.70+; testcontainers 0.23 (blocking feature).
- Sync API: `MockServer::default().start()` + `base_url(&container)`.
- Async API: `MockServer::default().start().await` + `async_base_url(&container).await`.
- File source: `mockserver-testcontainers/rust/Cargo.toml` (name, version, deps verified).
