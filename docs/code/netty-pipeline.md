# Netty Pipeline & Protocol Handling

## Server Bootstrap

The server is started from `Main.java` (CLI) or `ClientAndServer` (embedded). Both create a `MockServer` instance which extends `LifeCycle`.

```mermaid
sequenceDiagram
    participant CLI as Main.java / ClientAndServer
    participant MS as MockServer
    participant LC as LifeCycle
    participant NT as NettyTransport
    participant NIO as Netty NIO / Epoll

    CLI->>MS: new MockServer(ports)
    MS->>LC: super(configuration)
    LC->>NT: newEventLoopGroup(useNativeTransport)
    NT-->>LC: EpollEventLoopGroup (Linux) or NioEventLoopGroup (other)
    MS->>MS: createServerBootstrap()
    MS->>NT: serverSocketChannelClass(useNativeTransport)
    NT-->>MS: EpollServerSocketChannel or NioServerSocketChannel
    MS->>NIO: ServerBootstrap.group(bossGroup, workerGroup)
    MS->>NIO: .channel(selected channel class)
    MS->>NIO: .childHandler(MockServerUnificationInitializer)
    MS->>LC: bindServerPorts(ports)
    LC->>NIO: serverBootstrap.bind(port) [per port, on dedicated thread]
    NIO-->>LC: ChannelFuture completes
    LC->>MS: startedServer(boundPorts)
```

### Transport Selection

`NettyTransport` (`mockserver-core`, `org.mockserver.socket`) selects the highest-performance transport available at startup. On Linux with the native epoll library present and `useNativeTransport=true` (the default), it creates `EpollEventLoopGroup` and uses `EpollServerSocketChannel` / `EpollSocketChannel`. On all other platforms (macOS, Windows) or when the opt-out flag is set, it falls back to NIO transparently.

The transport selection is consistent across the entire data path: server bootstrap (boss + worker groups), outbound HTTP client (`NettyHttpClient`), and relay connect handler (`RelayConnectHandler`). This ensures the EventLoopGroup type always matches the channel type (epoll group with epoll channels, NIO group with NIO channels).

Epoll transport is required for transparent-proxy `SO_ORIGINAL_DST` resolution, which needs `EpollSocketChannel` children to extract the raw file descriptor.

**Intentionally left on NIO:** `Http3Server` (QUIC/datagram, separate experimental transport with its own `NioEventLoopGroup`), `McpToolRegistry`'s internal client, and `EchoServer` (test infrastructure).

| Property | Default | Env var | System property |
|----------|---------|---------|-----------------|
| `useNativeTransport` | `true` | `MOCKSERVER_USE_NATIVE_TRANSPORT` | `-Dmockserver.useNativeTransport` |

#### CI Test Coverage

On Linux CI the **full existing integration-test suite** exercises the epoll transport automatically because:

1. `useNativeTransport` defaults to `true`
2. The `netty-transport-native-epoll` JARs (linux-x86_64, linux-aarch_64) are declared as `runtime`-scoped dependencies in `mockserver-netty/pom.xml`, which Maven includes on the test classpath
3. On Linux the native `.so` loads successfully, so `Epoll.isAvailable()` returns `true`

To force NIO on Linux for comparison testing, set `useNativeTransport=false` via system property (`-Dmockserver.useNativeTransport=false`) or environment variable (`MOCKSERVER_USE_NATIVE_TRANSPORT=false`).

Dedicated activation tests in `EpollTransportIntegrationTest` (`mockserver-netty`) verify the channel and event-loop-group types at runtime. These tests are gated by `Assume.assumeTrue(Epoll.isAvailable())` and skip cleanly on macOS/Windows.

### Key Bootstrap Configuration

| Setting | Value | Purpose |
|---------|-------|---------|
| Boss group | `EpollEventLoopGroup(5)` or `NioEventLoopGroup(5)` | Accept connections |
| Worker group | `EpollEventLoopGroup(configurable)` or `NioEventLoopGroup(configurable)` | Handle I/O |
| Channel | `EpollServerSocketChannel` or `NioServerSocketChannel` | Server socket (transport-matched) |
| SO_BACKLOG | 1024 | Connection queue depth |
| AUTO_READ | true | Automatic read on new channels |
| ALLOCATOR | `PooledByteBufAllocator.DEFAULT` | Memory-efficient buffer allocation |
| WRITE_BUFFER_WATER_MARK | 8KB - 32KB | Backpressure control |

### Channel Attributes

| Attribute | Type | Purpose |
|-----------|------|---------|
| `REMOTE_SOCKET` | `InetSocketAddress` | Remote proxy target (port-forwarding mode) |
| `PROXYING` | `Boolean` | Whether channel is in proxy mode |
| `TLS_ENABLED_UPSTREAM` | `Boolean` | TLS active on client side |
| `TLS_ENABLED_DOWNSTREAM` | `Boolean` | TLS needed for upstream connections |
| `HTTP_ENABLED` | `Boolean` | HTTP pipeline configured |
| `HTTP2_ENABLED` | `Boolean` | HTTP/2 pipeline configured |
| `TRANSPARENT_ORIGINAL_DST_RESOLVED` | `Boolean` | Whether original-dst was resolved (conntrack/PROXY protocol) |
| `NETTY_SSL_CONTEXT_FACTORY` | `NettySslContextFactory` | SSL context for this channel |

## Channel Initializer

`MockServerUnificationInitializer` is a `@Sharable` `ChannelHandlerAdapter` that replaces itself with a `PortUnificationHandler` on `handlerAdded()`. This thin adapter ensures each new channel gets its own `PortUnificationHandler` instance (since the decoder maintains per-channel state).

When `transparentProxyEnabled` is true, the initializer adds two handlers before the port unification handler:

1. **`ProxyProtocolOriginalDestinationHandler`** (`"proxy-protocol"`) — inspects the first inbound bytes for a PROXY protocol header, dispatching on the first byte: `0x0D` → v2 (binary), `'P'` → v1 (text). If a recognised header is found, sets `REMOTE_SOCKET` + `PROXYING` + `TRANSPARENT_ORIGINAL_DST_RESOLVED` (v2: for the PROXY command on INET/INET6; LOCAL/UNIX defer to downstream resolution), consumes the header bytes, and removes itself. If not found, removes itself and passes bytes through unchanged.
2. **`TransparentProxyHandler`** (`"transparent-proxy"`) — fires at `channelActive` and runs the pluggable `CompositeOriginalDestinationResolver` chain (default: TPROXY → eBPF → SO_ORIGINAL_DST → conntrack → dns-intent). Skips resolution if `TRANSPARENT_ORIGINAL_DST_RESOLVED` is already set (e.g., by the PROXY protocol handler).

### Original Destination Resolver Chain

`CompositeOriginalDestinationResolver.defaultChain(Configuration)` tries strategies in order (first non-null wins):

| Order | Strategy | Class | Notes |
|-------|----------|-------|-------|
| 1 | TPROXY (IP_TRANSPARENT) | `TproxyOriginalDestinationResolver` | Returns `channel.localAddress()` when `transparentProxyTproxy=true`; null otherwise |
| 2 | eBPF socket metadata | `EbpfOriginalDestinationResolver` | O(1) BPF hash-map lookup; requires Linux + `CAP_BPF` + external cgroup BPF program; enabled via `transparentProxyEbpf=true` |
| 3 | SO_ORIGINAL_DST getsockopt | `SoOriginalDstResolver` | O(1) JNA `getsockopt`; requires Linux + Netty epoll transport |
| 4 | Linux conntrack table | `ConntrackOriginalDestinationResolver` | O(n) conntrack table scan; fallback when SO_ORIGINAL_DST is unavailable |
| 5 | DNS-intent (recover hostname MockServer's DNS answered) | `DnsIntentOriginalDestinationResolver` | Consults `DnsIntentRegistry`; last resort when all others return null |

The DNS-intent resolver consults `DnsIntentRegistry` (`mockserver-core`, `org.mockserver.mock.dns`), which records the `answeredIP → hostname` mappings MockServer's own DNS server hands out (A/AAAA answers). When a connection arrives at such an IP and all earlier strategies return null, the resolver returns an *unresolved* `InetSocketAddress` carrying the recovered hostname, so downstream forwarding/matching works by name (loop-prevention guards against a DNS-to-self loop). The registry is cleared by `HttpState.reset()`.

Note: PROXY protocol is handled separately in the pipeline (it reads bytes, not channel metadata).

## Port Unification Handler

`PortUnificationHandler` extends Netty's `ReplayingDecoder<Void>` and is **the heart of protocol detection**. It inspects the first bytes of every connection and routes to the appropriate protocol pipeline.

### Protocol Detection Order

```mermaid
flowchart TD
    START(["New Connection
First bytes arrive"]) --> SOCKS4{"SOCKS4?
byte 0 == 0x04"}
    SOCKS4 -->|Yes| EN_S4["enableSocks4
Add SOCKS4 decoders"]
    SOCKS4 -->|No| SOCKS5{"SOCKS5?
byte 0 == 0x05"}
    SOCKS5 -->|Yes| EN_S5["enableSocks5
Add SOCKS5 decoders"]
    SOCKS5 -->|No| TLS{"TLS?
SslHandler.isEncrypted"}
    TLS -->|Yes| EN_TLS["enableTls
Add SniHandler"]
    TLS -->|No| H2{"HTTP/2?
ALPN negotiated h2"}
    H2 -->|Yes| SW_H2["switchToHttp2
Add HTTP/2 pipeline"]
    H2 -->|No| HTTP{"HTTP?
GET/POST/PUT/..."}
    HTTP -->|Yes| SW_HTTP["switchToHttp
Add HTTP/1.1 pipeline"]
    HTTP -->|No| PROXY{"PROXIED_
prefix?"}
    PROXY -->|Yes| SW_PROXY["switchToProxyConnected
Internal relay setup"]
    PROXY -->|No| ASSUME{"Config:
assumeAllRequestsAreHttp?"}
    ASSUME -->|Yes| SW_HTTP
    ASSUME -->|No| SW_BIN["switchToBinaryRequestProxying
Raw binary handler"]

    EN_TLS -->|Re-fire decrypted bytes| START
    EN_S4 -->|Re-fire bytes| START
    EN_S5 -->|Re-fire bytes| START

    style EN_TLS fill:#f9f,stroke:#333
    style EN_S4 fill:#bbf,stroke:#333
    style EN_S5 fill:#bbf,stroke:#333
```

**Recursive detection**: When TLS or SOCKS is detected, the handler adds protocol-specific decoders, re-fires the bytes through the pipeline, and runs detection again on the decoded data. This enables arbitrary nesting (e.g., SOCKS5 → TLS → HTTP/2).

### Connection Delay

A configurable connection delay can be applied before protocol detection begins. When `connectionDelayMillis` is set to a non-zero value, `PortUnificationHandler.channelActive()` suppresses auto-read on the new channel and schedules the first read to resume after the configured duration, so the first inbound bytes (and protocol detection) are deferred without blocking the event loop. This simulates slow connection establishment for testing timeout handling in clients.

Configuration: `ConfigurationProperties.connectionDelayMillis(long millis)`, system property `mockserver.connectionDelayMillis`, environment variable `MOCKSERVER_CONNECTION_DELAY_MILLIS`. Default: 0 (no delay).

**Non-blocking:** The delay defers the first read via the event loop's scheduler instead of sleeping, so it does not stall other channels sharing the same worker thread. The delay is applied once per channel at `channelActive`.

### TCP Chaos Handler

When TCP-layer chaos is active (at least one host registered in `TcpChaosRegistry`), a `TcpChaosHandler` is inserted at the front of the pipeline before HTTP codecs. This handler operates on raw `ByteBuf` data and can inject transport-layer faults that mirror Toxiproxy's named toxics:

| Fault Type | Field | Behaviour |
|-----------|-------|-----------|
| latency | `latencyMs` | Delays all inbound data by the configured milliseconds |
| down | `down` | Silently drops all inbound data (service appears down) |
| bandwidth | `bandwidthBytesPerSec` | Throttles inbound data to the configured bytes/sec |
| slow_close | `slowClose` | Delays the TCP FIN by 2 seconds on close |
| timeout | `timeout` | Never sends FIN; connection hangs on close |
| reset_peer | `resetPeer` | Sends TCP RST and closes immediately |
| slicer | `slicerChunkSize` | Fragments inbound data into chunks of the configured size |
| limit_data | `limitDataBytes` | Closes the connection after the configured bytes received |

The handler is **not sharable** (each channel gets its own instance) because it maintains per-connection state (`bytesConsumed` for `limitData`).

Profiles are managed via the REST API:

- `PUT /mockserver/tcpChaos` -- register, remove, or clear TCP chaos profiles
- `GET /mockserver/tcpChaos` -- list all active TCP chaos profiles
- `PATCH /mockserver/tcpChaos` -- merge-patch an existing profile

Profiles support optional TTL-based auto-expiry (dead-man's switch), identical to the `ServiceChaosRegistry` pattern.

### Protocol-Specific Pipelines

#### HTTP/1.1 Pipeline

```mermaid
graph LR
    TCH["TcpChaosHandler
(conditional)"] --> A[HttpServerCodec]
    A --> B[PreserveHeadersNettyRemoves]
    B --> C[HttpContentDecompressor]
    C --> D[HttpContentLengthRemover]
    D --> EMH[EarlyMatchingHandler]
    EMH --> E[HttpObjectAggregator]
    E --> F[CallbackWebSocketServerHandler]
    F --> G[DashboardWebSocketHandler]
    G --> MCP["McpStreamableHttpHandler
(conditional)"]
    MCP --> H[MockServerHttpServerCodec]
    H --> I[HttpRequestHandler]
```

| Handler | Class | Purpose |
|---------|-------|---------|
| TcpChaosHandler | `o.m.netty.unification` | (Conditional) Injects TCP-layer faults (latency, down, bandwidth, slicer, etc.) on raw bytes before HTTP decoding. Only added when `TcpChaosRegistry` has active entries |
| HttpServerCodec | Netty built-in | HTTP/1.1 request decoding / response encoding |
| PreserveHeadersNettyRemoves | `o.m.codec` | Preserves `Content-Encoding`/`Transfer-Encoding` headers that the downstream `HttpContentDecompressor`/`HttpObjectAggregator` strip (reset per request so they cannot leak across a pooled connection — issue #2322). Also captures the original (still compressed) request body bytes before decompression onto a channel attribute, so the decompressed body and the original on-the-wire bytes are both available (issue #2326) |
| HttpContentDecompressor | Netty built-in | Decompresses gzipped request bodies. The original compressed bytes are still preserved by `PreserveHeadersNettyRemoves` above and exposed via `HttpRequest#getBodyAsOriginalRawBytes()` |
| HttpContentLengthRemover | `o.m.netty.unification` | Strips empty Content-Length headers |
| EarlyMatchingHandler | `o.m.netty.unification` | On the first `HttpRequest` (headers only), checks for an expectation with `respondBeforeBody=true` whose matcher has no body component. If found, dispatches the response (and any close) and discards remaining `HttpContent`, so the response can be sent before the body is read. Reproduces scenarios like okhttp/okhttp#1001 (issue #1831). Skipped for `CONNECT` and HTTP/2 |
| HttpObjectAggregator | Netty built-in | Aggregates HTTP chunks into `FullHttpRequest` |
| Http2StreamIdAuditHandler | `o.m.netty.unification` | **HTTP/2 pipelines only.** Sits immediately downstream of `HttpToHttp2ConnectionHandler`, so every outbound response head from the handlers below passes through it. Logs a WARN (once per connection) when a head lacks `x-http2-stream-id`, which the HTTP/2 codec would otherwise silently route onto a new server-initiated stream — delivering nothing to the client. Detection only: it never repairs the head, because the correct stream id cannot be inferred safely on a multiplexed connection (see below) |
| CallbackWebSocketServerHandler | `o.m.netty.websocketregistry` | Intercepts `/_mockserver_callback_websocket` |
| DashboardWebSocketHandler | `o.m.dashboard` | Intercepts `/_mockserver_ui_websocket` |
| McpStreamableHttpHandler | `o.m.netty.mcp` | Intercepts `/mockserver/mcp` for MCP (Model Context Protocol) Streamable HTTP transport. Only added when `ConfigurationProperties.mcpEnabled()` is true. POST requests are offloaded to a dedicated executor (`McpSessionManager.getExecutor()`) to avoid blocking the Netty event loop during blocking tool calls (e.g., `Future.get()`) |
| MockServerHttpServerCodec | `o.m.codec` | Converts Netty HTTP ↔ MockServer model |
| HttpRequestHandler | `o.m.netty` | Main request processing |

#### HTTP/2 Pipeline

```mermaid
graph LR
    SSL[SslHandler] --> TCH["TcpChaosHandler
(conditional)"]
    TCH --> H2C["HttpToHttp2ConnectionHandler
with InboundHttp2ToHttpAdapter"]
    H2C --> AUDIT[Http2StreamIdAuditHandler]
    AUDIT --> F[CallbackWebSocketServerHandler]
    F --> G[DashboardWebSocketHandler]
    G --> MCP["McpStreamableHttpHandler
(conditional)"]
    MCP --> H[MockServerHttpServerCodec]
    H --> I[HttpRequestHandler]
```

HTTP/2 frames are converted to HTTP/1.1 objects via `InboundHttp2ToHttpAdapter`, allowing the same `HttpRequestHandler` to process both protocols uniformly. When MCP is enabled (`ConfigurationProperties.mcpEnabled()`), the `McpStreamableHttpHandler` is also inserted in the HTTP/2 pipeline.

##### Outbound stream-id routing

Because this pipeline uses `HttpToHttp2ConnectionHandler` on a **single connection channel** (rather
than `Http2MultiplexHandler`), an outbound response is routed onto its HTTP/2 stream by the
`x-http2-stream-id` header. When that header is absent Netty does not fail — it allocates a fresh
*server-initiated* stream, so the requesting client receives nothing and hangs while the server logs
a normal successful response. This silent failure mode caused issue #2419 (server-streaming gRPC
delivering zero messages) and the same bug in the SSE, streaming-body, metrics and MCP handlers.

Any handler that writes a **raw Netty response** rather than a MockServer model object must therefore
stamp the id itself, via `org.mockserver.mappers.Http2StreamIds`. Model responses are stamped for
free by `MockServerHttpResponseToFullHttpResponse`.

`Http2StreamIdAuditHandler` detects a missing id but deliberately does **not** repair it. Repair is
unsafe here: one connection multiplexes many concurrent streams, and a streaming response is written
asynchronously long after its request was read, so any id inferred from "the most recent inbound
request" would sometimes belong to another client's stream — leaking one client's data to another,
which is worse than the hang it would fix.

Stamping the head is sufficient only while one stream is in flight. Netty's stock
`HttpToHttp2ConnectionHandler` routes continuation `HttpContent` frames using a `currentStreamId`
latched from the last `HttpMessage` (head) written — a bare content frame carries no id — so two
streaming responses interleaving on one connection would cross: a later chunk of stream A, written
after stream B's head, went out on B's (often already-closed) stream, and A's client hung forever
(issue #2667). MockServer therefore installs `StreamRoutingHttpToHttp2ConnectionHandler` (built via
`StreamRoutingHttpToHttp2ConnectionHandlerBuilder`) in place of the stock handler: a streaming write
site wraps each per-event chunk and the terminal frame in a `StreamAddressedHttpContent`
(`org.mockserver.codec`) that carries the originating stream id out-of-band — a channel attribute is
unusable because the channel is shared by every stream — and the routing handler writes each such
frame directly onto that stream via `encoder().writeData(ctx, streamId, ...)`, bypassing
`currentStreamId`. Every other message (the response head, settings, pings) is delegated unchanged to
the superclass, so HTTP/1.1 and the head-writing path are untouched. A streaming write that fails for
any reason additionally resets its own stream, so a client is never left hanging. Migrating this
non-multiplex pipeline to `Http2MultiplexHandler` (the `TODO(jamesdbloom)` in
`PortUnificationHandler#switchToHttp2`) remains the longer-term direction, but is no longer a
correctness prerequisite for concurrent streaming.

#### gRPC Pipeline (over HTTP/2)

When gRPC is enabled and the `GrpcProtoDescriptorStore` has loaded services, two additional handlers are inserted into both the h2c and TLS-negotiated HTTP/2 pipelines:

```mermaid
graph LR
    H2C["HttpToHttp2ConnectionHandler\nwith InboundHttp2ToHttpAdapter"] --> AUDIT[Http2StreamIdAuditHandler]
    AUDIT --> CB[CallbackWebSocketServerHandler]
    CB --> DASH[DashboardWebSocketHandler]
    DASH --> MCP["McpStreamableHttpHandler\n(conditional)"]
    MCP --> CODEC[MockServerHttpServerCodec]
    CODEC --> GRPC_RESP["GrpcToHttpResponseHandler\n(conditional)"]
    GRPC_RESP --> GRPC_REQ["GrpcToHttpRequestHandler\n(conditional)"]
    GRPC_REQ --> HANDLER[HttpRequestHandler]
```

| Handler | Class | Purpose |
|---------|-------|---------|
| GrpcToHttpResponseHandler | `o.m.netty.grpc` | Outbound encoder — intercepts responses with `x-grpc-service` header, encodes JSON body back to gRPC-framed protobuf, appends `grpc-status` trailers; also converts gRPC-Web responses (trailers-in-body) when `x-grpc-web-content-type` header is present |
| GrpcToHttpRequestHandler | `o.m.netty.grpc` | Inbound handler — intercepts `application/grpc` requests, decodes protobuf body to JSON using descriptors, rewrites as `POST /<service>/<method>` with `x-grpc-*` headers; also translates `application/grpc-web*` requests to standard gRPC before processing |

The handlers are placed after `MockServerHttpServerCodec` so they operate on MockServer model objects (`HttpRequest`/`HttpResponse`), not raw Netty HTTP objects.

h2c (HTTP/2 cleartext) is detected by `isH2cPreface()` in `PortUnificationHandler`, which checks for the HTTP/2 connection preface (`PRI * HTTP/2.0\r\n\r\nSM\r\n\r\n`). Both `switchToH2c()` and `switchToHttp2()` conditionally wire gRPC handlers when descriptors are loaded. The `switchToHttp()` method also adds gRPC handlers to the HTTP/1.1 pipeline to support gRPC-Web over HTTP/1.1.

##### Multiplex Pipeline (default OFF)

When `grpcBidiStreamingEnabled` is `true` **and** gRPC descriptors are loaded, `switchToHttp2()` / `switchToH2c()` use an alternate HTTP/2 pipeline based on `Http2FrameCodec` + `Http2MultiplexHandler` instead of the connection-level `HttpToHttp2ConnectionHandler` + `InboundHttp2ToHttpAdapter`. Each HTTP/2 stream gets its own child channel initialized by `GrpcMultiplexChildInitializer`:

```mermaid
graph LR
    FC[Http2FrameCodec] --> MUX[Http2MultiplexHandler]
    MUX -->|per-stream child| CS["ConnectionScopeHandler\n(copies parent attrs, then removes self)"]
    CS --> SF["Http2StreamFrameToHttpObjectCodec\n(server=true)"]
    SF --> AGG[HttpObjectAggregator]
    AGG --> CB[CallbackWebSocketServerHandler]
    CB --> DASH[DashboardWebSocketHandler]
    DASH --> MCP["McpStreamableHttpHandler\n(conditional)"]
    MCP --> CODEC[MockServerHttpServerCodec]
    CODEC --> GRPC_RESP[GrpcToHttpResponseHandler]
    GRPC_RESP --> GRPC_REQ[GrpcToHttpRequestHandler]
    GRPC_REQ --> HANDLER[HttpRequestHandler]
```

`Http2StreamFrameToHttpObjectCodec` + `HttpObjectAggregator` re-aggregate inbound stream frames into `FullHttpRequest` objects, so the downstream handler chain sees the same objects as the connection-level adapter produces. This means inbound behaviour is byte-for-byte equivalent to the default pipeline for unary RPCs. The flag defaults to `false`; when off, the existing connection-level adapter path is used unchanged.

**Connection-scoped attributes (`ConnectionScopeHandler`).** An `Http2StreamChannel` child does **not** inherit its parent connection channel's attribute map (`AbstractHttp2StreamChannel` delegates `localAddress()`/`remoteAddress()` to the parent, but not attributes). Every downstream handler reads connection-scoped state from `ctx.channel()` — which on a child is the stream channel — so `ConnectionScopeHandler` is installed as the **first** handler of every child pipeline (before `GrpcBidiRouterHandler`, omitted from the diagram) and copies the connection-scoped attributes from the parent once, then removes itself. It references each owning class's `public` `AttributeKey` constant directly (single source of truth), so the list stays in lockstep with the readers. The copied attributes are exactly (`ConnectionScopeHandler.CONNECTION_SCOPED_ATTRIBUTES`): `LOCAL_HOST_HEADERS`, `PROXYING`, `REMOTE_SOCKET`, `HTTP2_ENABLED`, `TLS_ENABLED_UPSTREAM`, `TLS_ENABLED_DOWNSTREAM`, `NETTY_SSL_CONTEXT_FACTORY`, `NEGOTIATED_APPLICATION_PROTOCOL`, `UPSTREAM_SSL_HANDLER`, `UPSTREAM_SSL_ENGINE`, `UPSTREAM_CLIENT_CERTIFICATES` and `SNI_HOSTNAME` — i.e. every attribute set on the connection channel during port unification / TLS handshake / proxy detection that a child-pipeline handler reads. (`HTTP_ENABLED` and `TRANSPARENT_ORIGINAL_DST_RESOLVED` are read only by connection-level handlers and are excluded; `TRACE_CONTEXT`, `WS_REGISTRY_KEY` and the CORS attributes self-initialise on the child.) Without this, `SniHandler.getALPNProtocol` (protocol detection / stream-id capture / `withProtocol(HTTP_2)` matching), `PortUnificationHandler.isHttp2Enabled` (the WebSocket-over-HTTP/2 `501` gate), `SniHandler.retrieveClientCertificates` (mTLS control-plane auth), and `HttpRequestHandler.isProxyingRequest` / `HttpActionHandler.getRemoteAddress` (proxied-forward routing — the proxying flag *and* the remote target) all misbehave on multiplexed streams (GitHub issue #2669). This mirrors the forward-client side, where `Http2ForwardStreamChildInitializer` copies `EXPECT_STREAMING_RESPONSE` onto its stream children for the same reason.

**Server-streaming:** `GrpcStreamResponseActionHandler` writes raw Netty HTTP objects (`DefaultHttpResponse`, per-message `DefaultHttpContent`, `DefaultLastHttpContent` with grpc-status/grpc-message trailers) directly to the `ChannelHandlerContext`. On the multiplex path, `Http2StreamFrameToHttpObjectCodec` is bidirectional and converts these outbound objects to HTTP/2 stream frames: initial HEADERS (with `Transfer-Encoding: chunked` automatically stripped by `HttpConversionUtil`), per-message DATA frames (byte-for-byte identical gRPC framing), and a trailing HEADERS frame with `grpc-status`/`grpc-message` and `endStream=true`. The `MockServerHttpServerCodec` encoder and `GrpcToHttpResponseHandler` do not intercept raw Netty objects (they only match `org.mockserver.model.HttpResponse`), so the objects pass through cleanly. No production code changes were needed -- the codec handles everything correctly.

| Property | Default | Env var | System property |
|----------|---------|---------|-----------------|
| `grpcBidiStreamingEnabled` | `false` | `MOCKSERVER_GRPC_BIDI_STREAMING_ENABLED` | `mockserver.grpcBidiStreamingEnabled` |

**Client-streaming (collect-then-respond):** For client-streaming RPCs, a client sends HEADERS followed by N DATA frames (each containing a gRPC length-prefixed message) then END_STREAM. On the multiplex path, `Http2StreamFrameToHttpObjectCodec` + `HttpObjectAggregator` re-aggregate all DATA frame bytes into a single `FullHttpRequest` body (byte-for-byte concatenation). `GrpcToHttpRequestHandler.convertGrpcRequest()` then decodes the concatenated body via `GrpcFrameCodec.decode()` into N messages, producing a JSON array body with the `x-grpc-client-streaming: true` header. This is identical to how the connection-level adapter handles client-streaming. Single-message requests (unary) decode as a single JSON object with no client-streaming header, preserving the distinction. No production code changes were needed -- the existing re-aggregation + decode pipeline handles this correctly.

Phase 3 will add true interleaved/reactive bidirectional streaming by removing the inbound re-aggregation and handling individual DATA frames with per-inbound-message reactive responses.

#### TLS Pipeline

```mermaid
graph LR
    SNI[SniHandler] -->|replaces self with| SSL[SslHandler]
    SSL -->|decrypted bytes re-enter| PU["PortUnificationHandler
detects inner protocol"]
```

`SniHandler` (in `mockserver-core`) extends Netty's `AbstractSniHandler`. It extracts the hostname from the TLS ClientHello SNI extension, dynamically generates a certificate with that hostname as a Subject Alternative Name, and negotiates ALPN (HTTP/1.1 or HTTP/2).

#### SOCKS4 Pipeline

```mermaid
graph LR
    S4D[Socks4ServerDecoder] --> S4E[Socks4ServerEncoder] --> S4H[Socks4ProxyHandler]
```

#### SOCKS5 Pipeline

```mermaid
graph LR
    S5D[Socks5InitialRequestDecoder] --> S5E[Socks5ServerEncoder] --> S5H[Socks5ProxyHandler]
```

SOCKS5 is multi-phase: initial handshake → optional password auth → CONNECT command.

## Streaming Relay

When `streamingResponsesEnabled` is `true` (default), the `HttpObjectAggregator` in the **forward-path client pipeline** (inside `NettyHttpClient`) and in the relay pipelines inside `RelayConnectHandler` is replaced by `StreamingAwareHttpObjectAggregator`.

`StreamingAwareHttpObjectAggregator` is a subclass of `HttpObjectAggregator` (`mockserver-core` `org.mockserver.codec`). It inspects the first response head and switches to streaming when **either** signal is present:

- **Response says so** — `Content-Type: text/event-stream`.
- **The client asked for a stream** — `NettyHttpClient` sets an `EXPECT_STREAMING_RESPONSE` channel attribute when the forwarded *request* declares streaming intent (an `Accept: text/event-stream` header, or a JSON body with `"stream": true`). This covers streaming backends that omit the content type — notably the OpenAI **Codex** backend used by the opencode CLI (`chatgpt.com/backend-api/codex/responses`), whose SSE response carries **no content type at all**. Without it MockServer would aggregate the whole (10–30s) response before sending any headers, and the client would time out waiting for response headers.

Then:

- **Non-streaming** (neither signal): delegates to `super` — behaviour is byte-for-byte identical to before. Ordinary chunked responses without an SSE content type or a streaming request are always aggregated normally. `DISABLE_RESPONSE_STREAMING` (set for a **body-affecting** `FORWARD_REPLACE` response modification — see below) wins over both signals.
- **Streaming**: removes itself from the pipeline and installs `StreamingResponseRelayHandler` in its place, positioned before `MockServerHttpClientCodec`. It also removes the per-request `ReadTimeoutHandler` (sized from `maxSocketTimeout`, ~20s, armed on non-pooled channels) so the longer stream-appropriate idle bound (`streamIdleTimeoutSeconds`, default 60s) governs — a streaming LLM response can legitimately pause longer than 20s between chunks. The relay handler then processes unaggregated `HttpObject` events.

The request-intent (`EXPECT_STREAMING_RESPONSE`) signal is threaded onto **all three** upstream paths, so a content-type-less streaming backend (e.g. the OpenAI **Codex** endpoint) is relayed incrementally regardless of which one it takes:

| Path | Where intent is set | Aggregator |
|------|---------------------|------------|
| HTTP/1.1 forward action | `NettyHttpClient` (bootstrap / pooled-channel attribute) | `StreamingAwareHttpObjectAggregator` on the h1 client pipeline |
| HTTP/2 forward action | `NettyHttpClient` sets it on the parent channel; `Http2ForwardStreamChildInitializer` copies it to the per-stream child (child channels do not inherit parent attributes) | `StreamingAwareHttpObjectAggregator` on the h2 stream child pipeline |
| Transparent CONNECT relay | `UpstreamProxyRelayHandler` sets it per-request on the loopback channel from `requestExpectsStreamingResponse(request)` | `StreamingAwareHttpObjectAggregator` in `relayOnly` mode on the loopback pipeline (`RelayConnectHandler.configureHttp1LoopbackPipeline`) |

> **`FORWARD_REPLACE` and streaming.** `DISABLE_RESPONSE_STREAMING` is set only when the response modification actually needs the fully-buffered body — a body/schema response override, a JSON body patch/merge-patch modifier, or a response template. A **header-only** modification (status / headers / cookies, no body change) is applied to the streamed response **head** while the body chunks are relayed untouched, so it does **not** disable streaming. This means a header-only rewrite (adding a CORS or trace header) on an SSE / LLM upstream keeps streaming intact. See `HttpOverrideForwardedRequestActionHandler.isHeaderOnlyResponseModification`.

### StreamingResponseRelayHandler

`StreamingResponseRelayHandler` (`mockserver-core` `org.mockserver.httpclient`) is a `ChannelInboundHandler` that consumes the raw `HttpObject` stream from the upstream server:

| Event | Action |
|-------|--------|
| `HttpResponse` (head) | Builds a head-only `org.mockserver.model.HttpResponse` with a `StreamingBody` sink. Completes `RESPONSE_FUTURE` immediately. |
| `HttpContent` | Forwards the chunk to the downstream (client) channel. Appends to `StreamingBody` capture buffer (bounded to `maxStreamingCaptureBytes`). |
| `LastHttpContent` | Closes the sink. Signals `HttpActionHandler` to write the `FORWARDED_REQUEST` log entry using the captured bytes. |
| `channelInactive` (mid-stream) | Calls `onError` on the sink. Emits a `FORWARDED_REQUEST` log entry flagged as truncated/aborted. |

An `IdleStateHandler(0, 0, streamIdleTimeoutSeconds)` is added to the streaming channel so stalled upstream connections are detected without the fixed global socket timeout cutting live streams.

### StreamingBody

`StreamingBody` (`mockserver-core` `org.mockserver.model`) is a chunk sink used to bridge the Netty handler with `HttpResponse`. It holds:

- A `subscribe(onChunk, onComplete, onError)` API consumed by the server-side `NettyResponseWriter` to write chunks to the downstream client.
- A bounded byte capture buffer (`capturedBytes()`) with a `truncated` flag.

The server-side `NettyResponseWriter` checks `response.getStreamingBody() != null` and, when true, writes a `DefaultHttpResponse` head followed by `DefaultHttpContent` frames per chunk and `LastHttpContent.EMPTY_LAST_CONTENT` at stream end — mirroring the existing `HttpSseResponseActionHandler` pattern.

## Response Trailers (Trailing Headers)

A `HttpResponse` can carry **general HTTP trailers** (trailing headers) via
`withTrailers(...)` / `withTrailer(name, values...)`, serialised in JSON as a `trailers`
object that mirrors `headers`. When present they are emitted as protocol-appropriate
trailing headers; when absent (null/empty — the default) the response is byte-for-byte
identical to before.

```mermaid
flowchart TD
    R["HttpResponse with trailers"] --> ENC["MockServerHttpToNettyHttpResponseEncoder\nMockServerHttpResponseToFullHttpResponse"]
    ENC -->|"DefaultHttpResponse (chunked) + DefaultHttpContent +\nDefaultLastHttpContent.trailingHeaders()"| H1["HTTP/1.1: chunked body + Trailer header +\ntrailing header block"]
    ENC -->|same LastHttpContent trailing headers| H2["HTTP/2: HttpToHttp2ConnectionHandler /\nHttp2StreamFrameToHttpObjectCodec ⇒ trailing HEADERS frame"]
    W3["Http3ResponseWriter"] -->|"Http3RequestBridge.toHttp3TrailersFrame()"| H3["HTTP/3: trailing HEADERS frame after DATA"]
```

| Protocol | Where | How trailers are emitted |
|----------|-------|--------------------------|
| HTTP/1.1 | `MockServerHttpResponseToFullHttpResponse.mapResponseWithTrailers()` (`mockserver-core`) | Emits a `DefaultHttpResponse` head with **chunked** transfer-encoding and an automatic `Trailer` header listing the field names (RFC 9110 §6.5.1), the body as `DefaultHttpContent`, and a `DefaultLastHttpContent` whose `trailingHeaders()` carry the trailers. A body-less status (204/304/HEAD) yields an empty `LastHttpContent` that still carries the trailers. Trailers **force chunked encoding**: RFC 7230 §3.3.1 makes a fixed `Content-Length` and chunked transfer-encoding mutually exclusive, and Netty's `HttpObjectEncoder` only writes the trailing-header block while in its chunked state — so any explicit `Content-Length` (and `contentLengthHeaderOverride`) is **dropped** from a trailer-carrying HTTP/1.1 response. Streaming-body responses (`NettyResponseWriter.writeStreamingResponse`) are already chunked and emit the same trailing-header block on a `DefaultLastHttpContent` at stream completion. |
| HTTP/2 | Netty `HttpToHttp2ConnectionHandler` (default pipeline) / `Http2StreamFrameToHttpObjectCodec` (gRPC-multiplex child pipeline) | Both adapters strip transfer-encoding and convert the same `LastHttpContent.trailingHeaders()` into a trailing HEADERS frame with `endStream=true`. No MockServer-specific wiring is needed beyond the HTTP/1.1 mapping. |
| HTTP/3 | `Http3ResponseWriter` + `Http3RequestBridge.toHttp3TrailersFrame()` (`mockserver-netty`) | After the DATA frame(s), a trailing `Http3HeadersFrame` is written before the QUIC stream output is shut down — for both static and streaming responses. Field names are lower-cased per HTTP/2/3 conventions. |
| Servlet (WAR) | `MockServerHttpResponseToHttpServletResponseEncoder.setTrailers()` (`mockserver-core`) | Sets `HttpServletResponse.setTrailerFields(...)`; the container handles framing. The Servlet API models one string value per name, so multi-valued trailers are joined with `", "` (HTTP list semantics) and duplicate names collapse to the last write — a WAR-path-only limitation. |

### Precedence vs gRPC trailers

gRPC responses carry their own status trailers (`grpc-status` / `grpc-message`), built by the
gRPC layer independently of the general-trailer field:

- On the **gRPC HTTP/2 path** (`GrpcToHttpResponseHandler`) the gRPC status is set as a
  response header on a cloned `HttpResponse`; the gRPC writers (`Http3GrpcResponseWriter`,
  `GrpcStreamResponseActionHandler`) build the trailing HEADERS frame directly from
  `grpc-status`/`grpc-message`.
- The gRPC writers do **not** read the general `trailers` field, so on a gRPC response general
  trailers are simply **not emitted** — there is no name collision to resolve, because the
  general-trailer block is never produced on the gRPC path.

ByteBuf safety: the trailer path keeps the body buffer's refcount at exactly one across all
branches — it is handed to a `DefaultHttpContent` only when non-empty and released in a
`finally` otherwise (and on any exception between allocation and transfer), and a body-less
response attaches an empty `Unpooled.EMPTY_BUFFER` `LastHttpContent`. The existing chunk-delay
and HTTP/3 writer paths retain/release as before.

## HTTP/2 Extension Header Stripping

**Invariant: every mapper that decodes an HTTP/2 upstream response must strip the entire `x-http2-*` extension-header family before the response enters the MockServer model.**

Netty's `InboundHttp2ToHttpAdapter` injects synthetic `x-http2-*` headers — `x-http2-stream-id`, `x-http2-scheme`, `x-http2-path`, `x-http2-stream-dependency-id`, `x-http2-stream-weight`, `x-http2-stream-promise-id` — when it converts an HTTP/2 frame sequence into a `FullHttpResponse`. These are internal Netty plumbing, not real response headers. If they escape into the response model and are later serialised back onto an outbound HTTP/2 connection, the upstream stream id (`x-http2-stream-id`) is written on a foreign stream. The HTTP/2 peer sees a HEADERS frame carrying a stream id that does not match any open stream on the write-back channel and responds with a connection-level PROTOCOL_ERROR / GOAWAY, hanging both legs of the proxy.

### Where the strip happens

| Site | Class / method | What is stripped |
|------|----------------|-----------------|
| Upstream response decode | `FullHttpResponseToMockServerHttpResponse.setHeaders()` (`mockserver-core`) | All six `ExtensionHeaderNames` values in the static `HTTP2_EXTENSION_HEADER_NAMES` set are excluded during header iteration. The same set is re-checked when folding in HTTP trailers (`trailingHeaders()`), so neither the header block nor the trailer block can carry these names into the model. |
| Write-back to client | `MockServerHttpResponseToFullHttpResponse` (`mockserver-core`) | Belt-and-braces: `response.headers().remove(STREAM_ID.text())` is called unconditionally before the outbound stream id is set from the protocol-guarded `HttpResponse.getStreamId()` field. This prevents a foreign upstream stream id from leaking onto the write path even if an upstream stripping step is bypassed. |

`HTTP2_EXTENSION_HEADER_NAMES` is built once from `HttpConversionUtil.ExtensionHeaderNames.*` at class load time and stored as a `Set<String>` of lower-cased names for O(1) lookup.

### Inbound request path

On the **inbound request** side, `FullHttpRequestToMockServerHttpRequest` does not strip `x-http2-stream-id` during header iteration — instead it reads the value with `headers().getInt(STREAM_ID.text())` and places it in the trusted `HttpRequest.streamId` field only when `request.getProtocol() == HTTP_2` (preventing an HTTP/1.1 client from forging it). Forwarded requests never carry `x-http2-*` into upstream headers because `MockServerHttpRequestToFullHttpRequest` re-derives the outbound stream id from `request.getStreamId()` directly, not from the header map.

## Connection-Lifecycle Response-Path Faults

These faults fire at response/dispatch time (not connect time) and are distinct from
the connect-time `TcpChaosHandler` faults. They are implemented in `NettyResponseWriter`
and `Http2GoAwayEmitter`, and gated by `HttpRequestHandler`'s L6 cordon check.

### Hot-path guarantee

`NettyResponseWriter.resolveLifecycleProfile(HttpRequest)` resolves the host-scoped
`TcpChaosProfile` keyed on the request `Host` header. It returns `null` immediately when:

1. `ConfigurationProperties.connectionLifecycleChaosEnabled()` is `false`, OR
2. `TcpChaosRegistry.getInstance().activeCount() == 0` (a single volatile read)

When either condition holds the normal write-and-close path is taken byte-for-byte
unchanged. There is no allocation and no additional branching on the hot path in the
common (no-lifecycle-chaos) case.

### L1 — Mid-response RST (`resetMidResponse`)

`NettyResponseWriter.writeHeadThenReset()` writes the response head via `ctx.writeAndFlush(response)`,
then on the write-complete future sets `SO_LINGER 0` and calls `channel.close()` — the same proven RST
mechanism as `TcpChaosHandler` (zero linger makes the close emit a TCP RST rather than a FIN). The
client sees "connection reset" while reading the body — the "server crashed mid-reply" fault.

When `connectionLifecycleAutoHaltCountsRst` is true (default), the RST records
`Metrics.incrementHttpChaosInjected("drop")` so a RST storm trips the auto-halt circuit-breaker.

**Streaming carve-out (v1):** these L1/L2/L3 faults are applied only in the non-streaming
`writeAndCloseSocket()` path. The streaming response path (`writeStreamingResponse`, SSE / chunked
streaming) ignores lifecycle faults in v1 and completes normally even when a host profile is registered.

**Host-scoping is not control-plane-exempt:** like `TcpChaosHandler`, these faults are keyed on the
request `Host` header and are *not* control-plane-exempt — a profile registered against the MockServer
host itself can RST a control-plane response on that host. Register lifecycle profiles against the
mocked-upstream host. (The L6 preemption cordon below *is* control-plane-exempt.)

### L2 — Host-scoped slow close (`slowCloseDelay`)

`NettyResponseWriter.addCloseSocketListener()` applies the close delay in priority order:

1. `ConnectionOptions.closeSocketDelay` (per-expectation)
2. `TcpChaosProfile.slowCloseDelay` (host-scoped, L2 — only reached when no per-expectation delay is set)
3. Immediate close (default)

This means a single `PUT /mockserver/tcpChaos` registration can make every response to a given
host linger on close without touching individual expectations.

### L3 — HTTP/2 GOAWAY on the response path (`http2GoAway`)

`Http2GoAwayEmitter.emit(ctx, lastStreamId, errorCode)` emits a connection-level GOAWAY frame
so the client stops opening new streams. It is called in `NettyResponseWriter.writeAndCloseSocket()`
before the response head is written, so the client receives the GOAWAY and can avoid opening
further streams while the current stream still completes normally.

**Implementation:** `Http2GoAwayEmitter` resolves the `Http2ConnectionHandler` via
`ctx.pipeline().context(Http2ConnectionHandler.class)` — the same lookup pattern as
`HttpErrorActionHandler.resetHttp2Stream`. A negative `lastStreamId` argument is converted to
`Integer.MAX_VALUE` and clamped down by the connection handler to the actual last-processed stream.

**v1 scope:** connection-level HTTP/2 pipeline only. The multiplex pipeline (per-stream child
channels used for gRPC bidi streaming) is deferred — see [chaos.md](chaos.md).

**HTTP/1.1 degradation:** when no `Http2ConnectionHandler` is found on the pipeline (HTTP/1.1
connection), `Http2GoAwayEmitter.emit()` returns `false` and callers degrade to
`Connection: close` + 503. GOAWAY is benign (graceful drain signal) and is NOT counted toward
the auto-halt window.

### L6 — Preemption cordon check in `HttpRequestHandler`

`HttpRequestHandler.channelRead0()` checks the preemption cordon early in request processing,
before any expectation matching:

```mermaid
flowchart TD
    REQ["Incoming request"] --> FEAT{"connectionLifecycleChaosEnabled?"}
    FEAT -->|No| NORMAL["Normal processing"]
    FEAT -->|Yes| CP{"path starts with /mockserver/?"}
    CP -->|Yes control-plane| NORMAL
    CP -->|No| CORD{"PreemptionSimulator.isCordoned()?"}
    CORD -->|No| NORMAL
    CORD -->|Yes| GA{"emitsGoAway() and HTTP/2?\n(Http2GoAwayEmitter.emit returns true)"}
    GA -->|Yes| EMIT["emit connection-level GOAWAY"]
    GA -->|No| REJ
    EMIT --> REJ{"rejectsNewExchanges()?"}
    REJ -->|Yes| REJECT["503 + Retry-After + Connection: close\ncompleteInFlight() + CLOSE listener"]
    REJ -->|No| NORMAL2["serve request normally\n(goaway-only: GOAWAY already sent / HTTP/1.1 no-op)"]
```

The control plane (`/mockserver/...`) is always exempt so the operator can observe state and issue
`DELETE /mockserver/preemption` to uncordon. The GOAWAY is emitted lazily on a cordoned HTTP/2
connection's next request — `Http2GoAwayEmitter.emit()` is the HTTP/2 detection (it returns `false`,
a no-op, on HTTP/1.1). The in-flight token is completed on every branch so the drain counter cannot
leak, and `GET /mockserver/preemption` reports the live in-flight count from `LifeCycle.getRequestsInFlight()`.
The `isCordoned()` probe is a single volatile read when no simulation is active, so this branch adds
nothing measurable to the hot path in the common case. GOAWAY is HTTP/2-only; HTTP/1.1 has no GOAWAY
and falls back to the 503 path (or is served normally in goaway-only mode).

## Stream-Level Error Injection (HttpError streamError)

An `HttpError` action can reset the individual request stream instead of returning a response, for
resilience testing of clients that must handle mid-stream resets. It is configured with
`HttpError.withStreamError(long errorCode)` (or the `StreamErrorCode` enum / `withStreamErrorCodeName`
convenience), serialised as a `streamError` integer. When `streamError` is null (the default) the
existing `dropConnection` / `responseBytes` behaviour is unchanged.

```mermaid
flowchart TD
    A["HttpError with streamError"] --> D{"Action dispatch\nHttpActionHandler.dispatchErrorAction"}
    D -->|"responseWriter is StreamErrorWriter\n(HTTP/3)"| H3["Http3ResponseWriter.writeStreamError()\nQuicStreamChannel.shutdownOutput(code)\n→ QUIC RESET_STREAM"]
    D -->|"otherwise"| HEH["HttpErrorActionHandler.handle()"]
    HEH -->|"channel is Http2StreamChannel\n(multiplex child)"| MUX["write DefaultHttp2ResetFrame(code)\n→ RST_STREAM"]
    HEH -->|"Http2ConnectionHandler present\n+ request.streamId (default h2 path)"| CONN["Http2ConnectionHandler.resetStream(streamId, code)\n→ RST_STREAM"]
    HEH -->|"HTTP/1.1 (no stream)"| DROP["ctx.disconnect + close\n(connection drop fallback)"]
```

| Transport | Where | How the stream is reset |
|-----------|-------|--------------------------|
| HTTP/2 (default connection-level pipeline) | `HttpErrorActionHandler.resetHttp2Stream()` (`mockserver-core`) | Resolves the `Http2ConnectionHandler` (`HttpToHttp2ConnectionHandler`) via `ctx.pipeline().context(...)` and calls `resetStream(ctx, streamId, errorCode, promise)`. The stream id is carried on the `HttpRequest` (set from the `x-http2-stream-id` extension header that `InboundHttp2ToHttpAdapter` adds — now captured for both TLS h2 and cleartext h2c). |
| HTTP/2 (gRPC multiplex pipeline) | `HttpErrorActionHandler.resetHttp2Stream()` | When the request is processed on a per-stream `Http2StreamChannel` child channel, writes a `DefaultHttp2ResetFrame(errorCode)` on that child channel; the parent `Http2MultiplexHandler`/`Http2FrameCodec` emits the `RST_STREAM`. |
| HTTP/3 | `Http3ResponseWriter.writeStreamError()` (`mockserver-netty`), reached via the `StreamErrorWriter` seam | Calls `QuicStreamChannel.shutdownOutput(errorCode)`, sending a QUIC `RESET_STREAM` for just this stream. The QUIC types live only in the netty module, so dispatch delegates through the transport-neutral `StreamErrorWriter` seam in core (mirroring the `GrpcStreamResponseWriter` pattern). |
| HTTP/1.1 | `HttpErrorActionHandler.handle()` | **No stream concept** — falls back to dropping the whole connection (`ctx.disconnect()` + `ctx.close()`), the same as the existing `dropConnection` behaviour. Documented caveat: a `streamError` on HTTP/1.1 closes the connection rather than resetting a single stream. |

`HttpActionHandler.dispatchErrorAction()` is the single funnel for the `ERROR` action (both the
early-match and main paths). It first checks whether the active `ResponseWriter` implements
`StreamErrorWriter` (the HTTP/3 case) and delegates; otherwise it hands the `HttpError` plus the
`HttpRequest` (for the stream id) to `HttpErrorActionHandler`. ByteBuf safety: the HTTP/2 reset uses
`resetStream(...)`/a single `DefaultHttp2ResetFrame` (no body buffer), and the HTTP/3 reset allocates
no buffer, so there is nothing to leak. Resetting one stream leaves the rest of the multiplexed
connection intact.

## Relay Connect Pattern

When HTTP CONNECT or SOCKS tunneling is established, MockServer uses a **self-loopback relay** rather than connecting directly to the target:

```mermaid
sequenceDiagram
    participant C as Client
    participant H as HttpConnectHandler / SocksConnectHandler
    participant MS as MockServer (loopback)
    participant PU as PortUnificationHandler

    C->>H: CONNECT target.com:443
    H->>MS: New Bootstrap connection to localhost:1080
    H->>MS: Send "PROXIED_SECURE_target.com:443"
    MS->>PU: PortUnificationHandler.decode()
    PU->>PU: Detect PROXIED_ prefix
    PU->>PU: Set remote address = target.com:443
    PU->>PU: Set TLS flags
    MS->>H: "PROXIED_RESPONSE_..."
    H->>C: 200 Connection Established
    H->>H: Strip HTTP codecs from both pipelines
    H->>H: Install relay handlers

    Note over C,MS: Bidirectional relay established
    C->>H: Encrypted traffic
    H->>MS: UpstreamProxyRelayHandler → MockServer pipeline
    MS->>MS: Decrypt, match expectations, forward
    MS->>H: DownstreamProxyRelayHandler → response
    H->>C: Response to client
```

This pattern allows MockServer to:
- Intercept and log tunneled HTTPS traffic
- Match expectations against tunneled requests
- Generate dynamic TLS certificates for the target hostname

### IPv6 Support

The relay connect protocol and CONNECT handler support IPv6 addresses in bracket notation (e.g., `[::1]:443`, `[2001:db8::1]:8443`). Host:port parsing uses `HttpRequest.splitHostPort()` which correctly handles both IPv4 and IPv6 formats.

The local address detection (`calculateLocalAddresses()`) explicitly includes `127.0.0.1` and `localhost`, plus the bound interface address (via `InetAddress.getHostAddress()`), which may include IPv6 addresses depending on the network configuration. This ensures requests sent to MockServer's bound address are correctly identified as control-plane requests rather than proxy targets.

### Relay Handler Hierarchy

```mermaid
classDiagram
    class RelayConnectHandler~T~ {
        <<abstract>>
        +channelRead0(ctx, msg)
        #removeCodecSupport(ctx)*
        #successResponse(request)*
        #failureResponse(request)*
    }

    class HttpConnectHandler {
        +removeCodecSupport()
        +successResponse(): HTTP 200
        +failureResponse(): HTTP 502
    }

    class SocksConnectHandler~T~ {
        <<abstract>>
        +removeCodecSupport()
    }

    class Socks4ConnectHandler {
        +successResponse(): SOCKS4 SUCCESS
        +failureResponse(): SOCKS4 REJECTED
    }

    class Socks5ConnectHandler {
        +successResponse(): SOCKS5 SUCCESS
        +failureResponse(): SOCKS5 FAILURE
    }

    RelayConnectHandler <|-- HttpConnectHandler
    RelayConnectHandler <|-- SocksConnectHandler
    SocksConnectHandler <|-- Socks4ConnectHandler
    SocksConnectHandler <|-- Socks5ConnectHandler
```

### Relay Data Flow

Once the relay is established, two handler pairs shuttle data:

```mermaid
graph LR
    subgraph "Client-facing pipeline"
        UPR["UpstreamProxyRelayHandler
reads requests from client"]
    end

    subgraph "MockServer-facing pipeline"
        DPR["DownstreamProxyRelayHandler
reads responses from MockServer"]
    end

    UPR -->|writes requests to| MS["MockServer
Internal Channel"]
    MS -->|responses flow to| DPR
    DPR -->|writes responses to| CLIENT[Client Channel]
```

### Relay Protocol Selection (HTTP/1.1 vs HTTP/2)

`RelayConnectHandler.configurePipelines()` builds both relay pipelines to match the protocol
negotiated with the proxy client (`http2EnabledDownstream`, derived from the proxy-client TLS
ALPN result):

- the **client-facing** pipeline uses an `HttpToHttp2ConnectionHandler` when the client negotiated
  HTTP/2, otherwise an `HttpServerCodec`;
- the **internal loopback** pipeline mirrors that choice — a client-mode `HttpToHttp2ConnectionHandler`
  for HTTP/2, otherwise an `HttpClientCodec` — and its client TLS context advertises `h2` via ALPN
  only when the loopback codec is HTTP/2.

Keeping the loopback's TLS layer and codec in agreement makes the relay a transparent passthrough.
Before this was fixed, the loopback hard-wired an HTTP/1.1 codec while its TLS could negotiate
`h2`, so HTTP/2 requests through the CONNECT proxy were never decoded and hung (#2260).

When the `http2Enabled` configuration property is `false`, `NettySslContextFactory` never advertises
`h2` via ALPN and `PortUnificationHandler` ignores the h2c cleartext preface, so every connection —
direct or relayed — falls back to HTTP/1.1.

## WebSocket Proxy Passthrough

MockServer can **proxy** a WebSocket connection through to a real upstream server, in addition to **mocking** one
(the `WEBSOCKET_RESPONSE` action — see [ai-protocol-mocking.md](ai-protocol-mocking.md#websocket-mocking)). When a
WebSocket upgrade request arrives in proxy mode and **no** WebSocket mock expectation matches — or it matches a plain
`FORWARD` expectation — MockServer opens the upstream WebSocket connection, relays the `101 Switching Protocols`
handshake, then relays frames bidirectionally until either side closes.

```mermaid
sequenceDiagram
    participant C as WebSocket Client (SUT)
    participant MS as MockServer (proxy)
    participant U as Upstream WS Server

    C->>MS: GET /path Upgrade: websocket
    Note over MS: no WS mock matches → passthrough
    MS->>U: client-side WS handshake (ws/wss)
    U-->>MS: 101 Switching Protocols
    MS-->>C: 101 Switching Protocols (server-side handshake)
    Note over C,U: bidirectional frame relay
    C->>MS: text / binary / ping frame
    MS->>U: same frame (recorded CLIENT_TO_UPSTREAM)
    U->>MS: text / binary / pong frame
    MS->>C: same frame (recorded UPSTREAM_TO_CLIENT)
    C->>MS: close
    MS->>U: close
    Note over MS: on close, flush FORWARDED_REQUEST transcript
```

**Placement.** The interception lives in `HttpActionHandler` (`mockserver-core`), where the mock-vs-forward decision
is already made: `WebSocketProxyRelayHandler.isWebSocketUpgrade(request)` is checked in the unmatched-proxy branch of
`processAction` (before `handleUnmatchedProxyForward`) and, for a matched plain `HttpForward` action, at the top of
`dispatchPrimaryActionInternal`. The relay itself is `WebSocketProxyRelayHandler` (`mockserver-core`), which:

1. Resolves the upstream host/port (the `REMOTE_SOCKET` port-forward target, the configured `proxyRemoteHost`, or the
   request `Host` header for a reverse proxy) and the scheme (`wss` when `request.isSecure()`, else `ws`).
2. Opens the upstream connection on the **same event loop** as the inbound client channel
   (`NettyTransport.socketChannelClassFor(...)`), so both relay halves run single-threaded with no cross-thread races.
   TLS uses `NettySslContextFactory.createClientSslContext(...)`.
3. Drives the **client-side** handshake to the upstream (`WebSocketClientHandshaker`), forwarding the client's custom
   headers (e.g. `Authorization`, `Cookie`) and requested subprotocol.
4. On upstream `101`, completes the **server-side** handshake back to the original client (reusing the same
   `WebSocketServerHandshakerFactory` approach as the WS mock handler), strips the HTTP server handlers, and installs a
   `FrameRelayHandler` on each channel.

**Backpressure.** Each `FrameRelayHandler` mirrors its channel's writability onto the *peer's* `autoRead` in
`channelWritabilityChanged` (the standard Netty proxy pattern): when the channel it writes to saturates, reads on the
source channel are paused and resumed when it drains, so a slow peer cannot grow the other side's
`ChannelOutboundBuffer` without bound.

**TLS to the upstream.** The upstream leg uses `NettySslContextFactory.createClientSslContext(true, …)` — the
**forward-proxy** client context — so `forwardProxyTLSX509CertificatesTrustManagerType` (default `ANY`) governs which
upstream certificates are trusted, exactly like the matched-forward path. (Using the non-forward context would trust
only MockServer's own CA and fail `wss` to any real upstream.)

**SSRF guard.** Before connecting, `relay()` calls `InetAddressValidator.validateForwardTarget(configuration, host)` —
the same guard every matched-forward handler enforces — so with `forwardProxyBlockPrivateNetworks=true` a WS upgrade to
a loopback / link-local / RFC1918 / cloud-metadata (`169.254.169.254`) target is rejected with a `502` instead of being
relayed. No-op when the feature is disabled (the default).

**Recording (flush-on-close only).** Each relayed frame is captured into a bounded, per-connection `FrameTranscript`
(direction, opcode, payload — text as a UTF-8 string, other opcodes as base64, per-frame payload capped at 32KB). The
transcript is flushed to the event log **once, when the connection closes** — a long-lived relay does not appear in
`retrieveRecordedRequests` until it closes. It is written as a single `FORWARDED_REQUEST`: request = the upgrade `GET`,
response = `101` with an `x-mockserver-websocket-frames` count header, an `x-mockserver-websocket-transcript-truncated`
flag, and the transcript as the JSON body — so `retrieveRecordedRequests` / `retrieveRecordedRequestsAndResponses` and
the dashboard show the WebSocket traffic. Two independent caps bound memory: the frame-count cap
`webSocketProxyMaxRecordedFrames` (default `1000`, `0` disables frame recording — the handshake is still recorded and is
*not* flagged truncated) and an absolute 8MB cap on the accumulated transcript JSON. Because base64 inflates payload by
~1.33× and Java `String`/`StringBuilder` store UTF-16 (~2 bytes/char), the frame-count cap alone could otherwise pin
~85MB for a connection of 1000 maximally-sized (32KB) frames; the 8MB JSON cap is the real bound. Beyond either cap
frames are relayed but not recorded and the transcript is flagged truncated.

**Idle reaping (opt-in).** With `webSocketProxyIdleTimeoutSeconds > 0`, an `IdleStateHandler` on each relay channel
closes the relay (flushing the transcript) once neither side has sent a frame for that period. Default `0` (off) —
legitimately idle long-lived WebSocket connections are left to TCP keep-alive and peer-close propagation.

**Matched-`HttpForward` semantics.** A WS upgrade matched by a plain `HttpForward` expectation is relayed to the
forward target, but only the **connect** is driven by that expectation: `Times`/`TimeToLive` selection and `verify`
work as usual, but the response-shaping features that assume a single buffered HTTP response — `delay`, `rateLimit`,
`chaos`, request/response/stream **breakpoints**, and **drift** analysis — do **not** apply to a passthrough WebSocket
(there is no single response to delay, rate-limit, mutate, or compare). Use the `WEBSOCKET_RESPONSE` mock action for
frame-level control.

**Boundary (v1).** HTTP/1.1 upgrade relay only, plain and TLS upstream. HTTP/2 extended-CONNECT (RFC 8441) WebSocket is
**not** relayed — such an upgrade falls through to the normal HTTP forward path. Only the plain `HttpForward` action
(static host/port/scheme) routes a matched WS upgrade to passthrough; the template/callback/replace forward variants
fall through to their normal handlers.

## DNS UDP Server

When `dnsEnabled=true`, `MockServer.bindDnsPort()` creates a separate Netty `Bootstrap` with `NioDatagramChannel` for UDP DNS:

```mermaid
graph LR
    UDP["NioDatagramChannel\n(UDP)"] --> DEC["DatagramDnsQueryDecoder"]
    DEC --> ENC["DatagramDnsResponseEncoder"]
    ENC --> HANDLER["DnsRequestHandler"]
```

| Handler | Class | Purpose |
|---------|-------|---------|
| DatagramDnsQueryDecoder | Netty built-in (`netty-codec-dns`) | Decodes UDP datagrams into `DatagramDnsQuery` |
| DatagramDnsResponseEncoder | Netty built-in (`netty-codec-dns`) | Encodes `DatagramDnsResponse` to UDP datagrams |
| DnsRequestHandler | `o.m.netty.dns` | Matches DNS queries against expectations via `HttpState`, returns `DnsResponse` records |

The DNS channel uses the same `workerGroup` as the TCP server. It is managed separately from TCP `serverChannelFutures` — closed explicitly in `MockServer.stopAsync()`.

## Binary Protocol Handling

When no known protocol is detected, `BinaryRequestProxyingHandler` handles the raw bytes. The handler first checks for matching expectations via `HttpState.firstMatchingExpectation(BinaryRequestDefinition)`. If a match with a `BinaryResponse` action is found, the response bytes are written directly to the channel. Otherwise, in proxy mode (when a remote address is configured on the channel), raw bytes are forwarded via `NettyHttpClient.sendRequest(BinaryMessage, ...)`:

- **Waiting mode**: Blocks until upstream response arrives, writes it back
- **Non-waiting mode**: Fire-and-forget with optional `BinaryProxyListener` callback. `BinaryProxyListener` (`o.m.model.BinaryProxyListener`) is a functional interface with `onProxy(BinaryMessage binaryRequest, CompletableFuture<BinaryMessage> binaryResponse, SocketAddress serverAddress, SocketAddress clientAddress)` invoked when binary data is proxied

## SOCKS Protocol Detection

`SocksDetector` provides static detection methods:

**SOCKS4 detection** (`isSocks4`):
- Byte 0 = `0x04` (version)
- Byte 1 = valid command (CONNECT or BIND)
- Validates null-terminated username (max 256 chars)
- Optionally validates SOCKS4a hostname

**SOCKS5 detection** (`isSocks5`):
- Byte 0 = `0x05` (version)
- Byte 1 = auth method count
- Each auth method is NO_AUTH, PASSWORD, or GSSAPI

**SOCKS5 handshake lifecycle**:

```mermaid
stateDiagram-v2
    [*] --> InitialRequest: Client sends version + auth methods
    InitialRequest --> PasswordAuth: Server selects PASSWORD
    InitialRequest --> CommandRequest: Server selects NO_AUTH
    PasswordAuth --> CommandRequest: Credentials valid
    PasswordAuth --> [*]: Credentials invalid (close)
    CommandRequest --> RelayEstablished: CONNECT command
    CommandRequest --> [*]: Unsupported command (close)
    RelayEstablished --> [*]: Connection closed
```

## SSL and Decoder Fault Logging

Netty's `exceptionCaught` fires for both benign connection closes and genuine faults. MockServer distinguishes these two categories using `ExceptionHandling.isSslOrDecoderFault(Throwable)`:

| Exception type | Classification | Logged at |
|---------------|----------------|-----------|
| `SSLException` (as `cause`) | SSL/decoder fault | **WARN** |
| `DecoderException` | SSL/decoder fault | **WARN** |
| `NotSslRecordException` | SSL/decoder fault | **WARN** |
| Connection reset / broken pipe (regex + stack match) | Benign close | silent |
| Other unexpected exceptions | Unexpected | **ERROR** |

The `isSslOrDecoderFault` predicate is wired into the `exceptionCaught` handler of every handler that could receive these exceptions:

- `PortUnificationHandler` (protocol detection)
- `HttpRequestHandler` (main request dispatcher)
- `BinaryRequestProxyingHandler` (raw binary proxy)
- `SocksProxyHandler` (SOCKS4/5)
- `UpstreamProxyRelayHandler` / `DownstreamProxyRelayHandler` / `RelayConnectHandler` (relay handlers)
- `CallbackWebSocketServerHandler` (WebSocket callback channel)
- `McpStreamableHttpHandler` (MCP streaming)
- `DashboardWebSocketHandler` (dashboard WebSocket)

This means genuine SSL negotiation failures (e.g., client sends plain HTTP to a TLS port, or a non-TLS client probes a TLS port) surface at WARN and are visible in logs, while normal connection teardowns remain silent. `ExceptionHandling.isSslOrDecoderFault` mirrors the predicate already in `connectionClosedException` but as a positive match so callers can route specifically to WARN rather than silently drop.

## Class Reference

| Class | File | Role |
|-------|------|------|
| `Main` | `mockserver-netty/.../cli/Main.java` | CLI entry point, argument parsing |
| `LifeCycle` | `mockserver-netty/.../lifecycle/LifeCycle.java` | Abstract server lifecycle (event loops, port binding, shutdown) |
| `MockServer` | `mockserver-netty/.../netty/MockServer.java` | Concrete server, configures `ServerBootstrap` |
| `MockServerUnificationInitializer` | `mockserver-netty/.../netty/MockServerUnificationInitializer.java` | Replaces self with `PortUnificationHandler` |
| `PortUnificationHandler` | `mockserver-netty/.../netty/unification/PortUnificationHandler.java` | Protocol detection and pipeline assembly |
| `HttpRequestHandler` | `mockserver-netty/.../netty/HttpRequestHandler.java` | Main request dispatcher |
| `NettyResponseWriter` | `mockserver-netty/.../netty/responsewriter/NettyResponseWriter.java` | Writes responses to Netty channels |
| `HttpErrorActionHandler` | `mockserver-core/.../mock/action/http/HttpErrorActionHandler.java` | Applies an `HttpError` action: raw response bytes, HTTP/2 stream reset (RST_STREAM), and/or connection drop (also the HTTP/1.1 stream-error fallback) |
| `StreamErrorWriter` | `mockserver-core/.../responsewriter/StreamErrorWriter.java` | Transport-neutral seam for resetting the request stream; implemented by `Http3ResponseWriter` for the QUIC RESET_STREAM |
| `HttpConnectHandler` | `mockserver-netty/.../netty/proxy/connect/HttpConnectHandler.java` | HTTP CONNECT tunnel handler |
| `RelayConnectHandler` | `mockserver-netty/.../netty/proxy/relay/RelayConnectHandler.java` | Abstract relay establishment |
| `UpstreamProxyRelayHandler` | `mockserver-netty/.../netty/proxy/relay/UpstreamProxyRelayHandler.java` | Client → MockServer relay |
| `DownstreamProxyRelayHandler` | `mockserver-netty/.../netty/proxy/relay/DownstreamProxyRelayHandler.java` | MockServer → client relay |
| `BinaryRequestProxyingHandler` | `mockserver-netty/.../netty/proxy/BinaryRequestProxyingHandler.java` | Raw binary proxying |
| `SocksDetector` | `mockserver-netty/.../netty/proxy/socks/SocksDetector.java` | SOCKS4/5 protocol detection |
| `SocksProxyHandler` | `mockserver-netty/.../netty/proxy/socks/SocksProxyHandler.java` | Abstract SOCKS handler base |
| `Socks4ProxyHandler` | `mockserver-netty/.../netty/proxy/socks/Socks4ProxyHandler.java` | SOCKS4 CONNECT handling |
| `Socks5ProxyHandler` | `mockserver-netty/.../netty/proxy/socks/Socks5ProxyHandler.java` | SOCKS5 multi-phase handshake |
| `SocksConnectHandler` | `mockserver-netty/.../netty/proxy/socks/SocksConnectHandler.java` | Abstract SOCKS relay base |
| `SniHandler` | `mockserver-core/.../socket/tls/SniHandler.java` | TLS SNI extraction, dynamic cert generation |
| `HttpContentLengthRemover` | `mockserver-netty/.../netty/unification/HttpContentLengthRemover.java` | Strips empty Content-Length |
| `MockServerHttpServerCodec` | `mockserver-core/.../codec/MockServerHttpServerCodec.java` | Netty HTTP ↔ MockServer model codec |
| `GrpcToHttpRequestHandler` | `mockserver-netty/.../netty/grpc/GrpcToHttpRequestHandler.java` | gRPC request decode (protobuf→JSON); gRPC-Web translation |
| `GrpcToHttpResponseHandler` | `mockserver-netty/.../netty/grpc/GrpcToHttpResponseHandler.java` | gRPC response encode (JSON→protobuf); gRPC-Web re-framing |
| `GrpcWebTranslator` | `mockserver-core/.../grpc/GrpcWebTranslator.java` | gRPC-Web framing utilities (trailer frame, base64, content-type detection) |
| `DnsRequestHandler` | `mockserver-netty/.../netty/dns/DnsRequestHandler.java` | DNS query matching and response |
| `StreamingAwareHttpObjectAggregator` | `mockserver-core/.../codec/StreamingAwareHttpObjectAggregator.java` | Replaces `HttpObjectAggregator` in forward-path client pipelines; detects streaming responses and switches to `StreamingResponseRelayHandler` |
| `StreamingResponseRelayHandler` | `mockserver-core/.../httpclient/StreamingResponseRelayHandler.java` | Consumes unaggregated `HttpObject` events; relays chunks immediately; captures bounded body; signals `HttpActionHandler` on completion |
| `StreamingBody` | `mockserver-core/.../model/StreamingBody.java` | Chunk sink bridging relay handler to server-side response writer; holds bounded capture buffer |
| `WebSocketProxyRelayHandler` | `mockserver-core/.../mock/action/http/WebSocketProxyRelayHandler.java` | WebSocket proxy passthrough: opens the upstream WS connection (plain/TLS), relays the `101` handshake and frames bidirectionally, and records the upgrade + a bounded frame transcript as a `FORWARDED_REQUEST` |
| `AltSvcHeaderHandler` | `mockserver-netty/.../netty/unification/AltSvcHeaderHandler.java` | Outbound handler that adds `Alt-Svc` header to TCP responses when HTTP/3 is enabled; does not clobber user-set values |
