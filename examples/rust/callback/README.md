# Callbacks

## What it demonstrates

How to use the MockServer Rust client's two callback kinds. It runs both cases
in sequence, resetting the server before each, and asserts every outcome:

1. **object_callback** — an in-process Rust closure produces the response.
   `client.mock_with_callback(matcher, |req| resp)` opens the shared callback
   WebSocket, MockServer hands it a `clientId`, and on each matching request the
   closure runs with the request and returns the response. The example derives
   the response from the request (`GET /greet?name=Ada` → `hello Ada`,
   `?name=Grace` → `hello Grace`) and sets an `X-Handled-By: rust-closure`
   header, so the response could only have come from running the closure.

2. **class_callback** — a declarative, REST-only callback naming a server-side
   class (`respond_with_class_callback("com.example.MyResponseCallback")`). No
   WebSocket is involved. The class need not exist on the server to validate the
   wire shape; the assertion is that the server accepts the expectation and
   echoes `httpResponseClassCallback.callbackClass` back.

The same pattern works for forward callbacks via `forward_with_class_callback`
and `forward_object_callback`.

## Prerequisites

- Rust 1.88+ (and Cargo)
- MockServer running, discovered from the environment:
  - `MOCKSERVER_HOST` (default `localhost`)
  - `MOCKSERVER_PORT` (default `1080`)

  e.g. `docker run -d -p 1080:1080 mockserver/mockserver`

## Run

```bash
cargo run
```

Point at a different server with environment variables:

```bash
MOCKSERVER_HOST=mockserver.local MOCKSERVER_PORT=1080 cargo run
```

The program exits `0` only if both cases pass, non-zero otherwise.

## Expected output

```
PASS: object_callback
PASS: class_callback

All callback examples passed.
```
