# Modify Proxied Response (Interactive Breakpoint)

## What it demonstrates

How to use MockServer's **interactive breakpoints** from Rust to modify a
proxied response in-flight:

- Create an "upstream" mock expectation that returns a JSON response
- Create a loopback forward expectation (`httpOverrideForwardedRequest` with
  `socketAddress` pointing back to the same MockServer) so the request is
  proxied through MockServer itself
- Register a **RESPONSE-phase breakpoint** on the proxy path using
  `add_request_response_breakpoint` -- the handler modifies the response body
  and adds a custom header before it reaches the caller
- Send a request through the proxy and print the modified response

This is the "modify proxied exchanges" feature: your Rust program acts as the
breakpoint callback client via a WebSocket connection.

## Prerequisites

- Rust 1.88+ (and Cargo)
- MockServer running on `localhost:1080` **with breakpoint support** (build from
  this repo; the public Docker image may not include breakpoint endpoints)

## Run

```bash
cargo run
```

## Expected output

```
1. Created upstream expectation: GET /upstream -> 200
2. Created loopback forward: GET /proxy -> forward to /upstream (localhost:1080)
3. Registered RESPONSE breakpoint (id=<uuid>) on GET /proxy

4. Sending GET /proxy ...

   [breakpoint] RESPONSE phase fired!
   [breakpoint] Original response body: {"source":"upstream","modified":false}
   [breakpoint] Modified response body: {"source":"upstream","modified":true,"breakpoint":"rust-client"}

--- Response from GET /proxy ---
Status:          200
Body:            {"source":"upstream","modified":true,"breakpoint":"rust-client"}
X-Modified-By:   rust-breakpoint-example

All expectations and breakpoints cleared.
```
