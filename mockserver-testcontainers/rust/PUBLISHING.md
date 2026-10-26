# Publishing testcontainers-mockserver to crates.io

## Registry

- **Registry:** [crates.io](https://crates.io)
- **Crate name:** `testcontainers-mockserver`
- **Package URL:** https://crates.io/crates/testcontainers-mockserver

## Secret

The publish token is stored in AWS Secrets Manager:

```
Secret: mockserver-release/crates
Key:    CARGO_TOKEN
Region: eu-west-2
```

Retrieve with:

```bash
CARGO_TOKEN=$(aws secretsmanager get-secret-value \
  --secret-id mockserver-release/crates \
  --query SecretString --output text \
  --profile mockserver-build --region eu-west-2 | jq -r .CARGO_TOKEN)
```

## Publish Command (non-interactive)

```bash
cd mockserver-testcontainers/rust
cargo publish --token "$CARGO_TOKEN"
```

## Pre-publish Checklist

1. Ensure `version` in `Cargo.toml` matches the release version
2. Run `cargo test` (unit tests pass without Docker)
3. Run `cargo test -- --ignored` (integration test passes with Docker)
4. Run `cargo clippy -- -D warnings` (no lint warnings)
5. Run `cargo package --list` to verify no unwanted files are included

## Version Sync

The crate version in `Cargo.toml` and the `MOCKSERVER_VERSION` constant in `src/lib.rs`
must both match the MockServer release version (e.g. `9.2.0`). Update both when releasing.
