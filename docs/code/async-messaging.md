# Async Messaging Module (`mockserver-async`)

## Overview

The `mockserver-async` module provides **AsyncAPI-driven message-broker mocking** for Kafka, MQTT, and AMQP 0.9.1 (RabbitMQ). Given an AsyncAPI 2.x or 3.x specification document, it parses the channels and message definitions, generates schema-validated example payloads, publishes them to a message broker, and can subscribe to channels to record incoming messages for verification.

> **Publish + subscribe on all three brokers:** Kafka, MQTT, and AMQP/RabbitMQ each support both publishing example messages **and** subscribing to record incoming messages for verification.
>
> **Kafka value formats:** JSON (default) and **Avro in the Confluent Schema Registry wire format** (magic byte + schema id + Avro binary), with a registry-backed mode (schemas registered/resolved against a configured Schema Registry URL) and a registry-less mode (fixed schema id + inline schema). Protobuf is deferred.
>
> **MQTT protocol versions:** MQTT 3.1.1 (default) and **MQTT 5** (`mqttProtocolVersion: 5`), the latter adding user-property (header) delivery that v3 cannot carry.

## Architecture

```mermaid
flowchart LR
    A["AsyncAPI Spec\n(JSON / YAML)"] --> B["AsyncApiParser"]
    B --> C["AsyncApiSpec\n(channels + messages)"]
    C --> D["MessageExampleGenerator"]
    D --> E["Example payloads\n(schema-aware)"]
    E --> F["AsyncApiMockOrchestrator"]
    F --> G["MessagePublisher"]
    G --> H["Kafka / MQTT / AMQP broker"]
    H --> I["MessageSubscriber"]
    I --> J["RecordedMessages"]
```

### Control-Plane Integration

```mermaid
flowchart TD
    Client["HTTP Client"] -->|PUT /mockserver/asyncapi| HS["HttpState\n(mockserver-core)"]
    Client -->|GET /mockserver/asyncapi| HS
    Client -->|PUT /mockserver/asyncapi/verify| HS
    HS --> REG["AsyncApiControlPlaneRegistry\n(SPI holder in core)"]
    REG --> IMPL["AsyncApiControlPlaneImpl\n(mockserver-async)"]
    IMPL --> Parser["AsyncApiParser"]
    IMPL --> Pub["Publishers"]
    IMPL --> Sub["Subscribers"]
    IMPL --> Val["Schema Validator"]
```

The control-plane uses an **SPI/registry pattern** (Option A from the design spec): a lightweight `AsyncApiControlPlane` interface and `AsyncApiControlPlaneRegistry` holder live in `mockserver-core`, keeping core free of async module dependencies. The actual implementation (`AsyncApiControlPlaneImpl`) lives in `mockserver-async` and self-registers at server startup via reflection from `MockServer.createServerBootstrap()`. This mirrors the pattern established by `GrpcHealthRegistry`, `WasmStore`, `DriftStore`, and other optional subsystem registries in core.

### Key Classes

| Class | Package | Responsibility |
|-------|---------|----------------|
| `AsyncApiControlPlane` | `o.m.async` (core) | SPI interface for the control-plane |
| `AsyncApiControlPlaneRegistry` | `o.m.async` (core) | Singleton holder; routes HttpState calls to the implementation |
| `AsyncApiControlPlaneImpl` | `o.m.async.controlplane` | Full implementation: load, status, reset, verify, broker lifecycle |
| `AsyncApiParser` | `o.m.async.asyncapi` | Parses AsyncAPI 2.x/3.x JSON or YAML into an `AsyncApiSpec` model |
| `AsyncApiSpec` | `o.m.async.asyncapi` | Immutable model: version, title, list of `AsyncApiChannel` |
| `AsyncApiChannel` | `o.m.async.asyncapi` | A channel name, payload examples, optional JSON Schema, parsed bindings (MQTT qos/retain, Kafka key), and optional multi-message list |
| `AsyncApiMessage` | `o.m.async.asyncapi` | A single message definition: name, payload schema, payload examples, Kafka key binding, correlation ID location |
| `MessageExampleGenerator` | `o.m.async` | Schema-aware example generation (enum, default, format, min/max, minLength, const); per-channel and per-message |
| `AsyncApiSchemaValidator` | `o.m.async.validation` | Validates payloads against channel JSON Schemas using core's `JsonSchemaValidator` |
| `PublishOptions` | `o.m.async.publish` | Immutable carrier for per-message publish-time options: Kafka key, MQTT qos, MQTT retain, message headers (e.g. correlation ID) |
| `MessagePublisher` | `o.m.async.publish` | Interface: `publish(channel, payload)`, `publish(channel, key, payload, headers)`, `publish(channel, payload, options)`, `flush()`, `close()`. `flush()` blocks until the broker has acknowledged every send and rethrows the first delivery failure — a caller reporting publish success must call it first, since Kafka sends asynchronously |
| `KafkaMessagePublisher` | `o.m.async.publish` | Wraps `KafkaProducer`; supports keys and headers |
| `MqttMessagePublisher` | `o.m.async.publish` | Wraps Paho **v3** `MqttClient`; supports configurable QoS (0/1/2) and binary payloads |
| `Mqtt5MessagePublisher` | `o.m.async.publish` | Wraps Paho **v5** `MqttClient`; QoS/retain/binary as v3 plus MQTT 5 user properties (headers) from `PublishOptions.getHeaders()` |
| `KafkaAvroMessagePublisher` | `o.m.async.publish` | Wraps `KafkaProducer<String,byte[]>`; encodes JSON payloads to Confluent-wire-format Avro (magic byte + schema id + Avro binary); registry-backed or registry-less schema id |
| `AmqpMessagePublisher` | `o.m.async.publish` | Wraps the RabbitMQ `com.rabbitmq.client.Channel`; derives the exchange + routing key from each channel's `AmqpBinding`, declares the exchange/queue idempotently, and emits headers (e.g. correlation IDs) as AMQP message properties |
| `AmqpBinding` | `o.m.async.asyncapi` | Immutable model of the AsyncAPI AMQP channel binding (`is`, exchange name/type/durable, queue name/durable, routing key) |
| `MessageSubscriber` | `o.m.async.subscribe` | Interface: `subscribe(channel)`, `unsubscribe(channel)`, `getRecordedMessages()`, `close()` |
| `MqttTopicFilter` | `o.m.async.subscribe` | MQTT 3.1.1 §4.7 topic-filter matching (`+`, `#`, reserved `$` topics), so wildcard subscriptions are retrievable by the filter they were subscribed with |
| `KafkaMessageSubscriber` | `o.m.async.subscribe` | Wraps `KafkaConsumer` with background poll loop; all consumer access confined to the poll thread via a queued-ops pattern; records messages in bounded stores |
| `KafkaAvroMessageSubscriber` | `o.m.async.subscribe` | Wraps `KafkaConsumer<String,byte[]>`; strips the Confluent wire-format header, resolves the schema (registry by id, or inline), and decodes Avro binary to JSON before recording; falls back to raw string for non-Avro bytes |
| `MqttMessageSubscriber` | `o.m.async.subscribe` | Wraps Paho **v3** `MqttClient` callback; records messages in bounded stores |
| `Mqtt5MessageSubscriber` | `o.m.async.subscribe` | Wraps Paho **v5** `MqttClient` callback; records MQTT 5 user properties as message headers |
| `AmqpMessageSubscriber` | `o.m.async.subscribe` | Consumes from RabbitMQ; resolves the queue from the channel's `AmqpBinding` (queue-based: the named queue; routingKey-based: declares the exchange + a private queue bound on the routing key); records message-property headers |
| `ConfluentWireFormat` | `o.m.async.serde` | Encodes/decodes the Confluent wire-format header (magic byte `0x00` + 4-byte big-endian schema id + payload); codec-agnostic and broker-free |
| `AvroPayloadCodec` | `o.m.async.serde` | Converts payloads between JSON text and Avro binary via Apache Avro `GenericDatum` reader/writer (no code generation, no Confluent serde stack) |
| `SchemaRegistryClient` | `o.m.async.serde` | Minimal Confluent Schema Registry REST client over the JDK `HttpClient`: `getSchemaById(id)` and `register(subject, schema)`, both cached |
| `Mqtt5SecurityOptions` | `o.m.async.security` | Builds an MQTT v5 `MqttConnectionOptions` from `MqttSecurity` (username/password/SSL) — the v5 counterpart of `MqttSecurityOptions` |
| `BoundedMessageStore` | `o.m.async.subscribe` | Thread-safe, bounded FIFO store for `RecordedMessage` instances (default 1000 per channel); evicts oldest when full |
| `RecordedMessage` | `o.m.async.subscribe` | Immutable record: channel, key, payload, headers, timestamp |
| `AsyncApiMockOrchestrator` | `o.m.async` | Publishes examples once (`publishAll()`) or on a schedule (`startPublishing(interval)` / `stop()`) |

## REST Control-Plane

### `PUT /mockserver/asyncapi`

Load an AsyncAPI spec and start mocking. The request body can be either:

1. **Plain spec**: the AsyncAPI document as JSON or YAML
2. **Wrapped body**: `{"spec": <spec>, "brokerConfig": {...}}`

Broker configuration options (`brokerConfig`):

| Field | Type | Default | Description |
|-------|------|---------|-------------|
| `kafkaBootstrapServers` | string | null | Kafka bootstrap servers (e.g. `localhost:9092`) |
| `kafkaGroupId` | string | `mockserver-async-consumer` | Consumer group ID for Kafka subscribers |
| `mqttBrokerUrl` | string | null | MQTT broker URL (e.g. `tcp://localhost:1883`) |
| `mqttClientId` | string | `mockserver-mqtt-pub/sub` | MQTT client ID prefix |
| `mqttQos` | int | 1 | MQTT QoS level (0, 1, or 2) |
| `mqttProtocolVersion` | int | 3 | MQTT protocol version: `3` (3.1.1) or `5` (adds user-property/header delivery) |
| `amqpUri` | string | null | AMQP (RabbitMQ) connection URI (e.g. `amqp://guest:guest@localhost:5672/`) |
| `kafkaValueFormat` | string | `json` | Kafka value serialization: `json` or `avro` (Confluent wire format) |
| `kafkaSchemaRegistryUrl` | string | null | Confluent Schema Registry URL (Avro only); when present, schema ids are registered on publish and resolved by id on consume |
| `avroSchema` | string \| object | null | Inline Avro schema (Avro only): the schema used to encode published payloads and to decode consumed payloads in registry-less mode. May be a JSON string or an inline JSON object |
| `avroSchemaId` | int | 1 | Fixed schema id embedded in published messages in registry-less mode (ignored when a registry URL is set) |
| `publishOnLoad` | boolean | true | Publish examples immediately on load |
| `publishIntervalMillis` | long | 0 | Schedule periodic publishing (0 = disabled) |
| `consume` | boolean | false | Enable consumer/subscriber for each channel (Kafka, MQTT, and AMQP) |
| `kafkaSecurity` | object | null | Kafka SASL/SSL security config (see [Broker Security](#broker-security)) |
| `mqttSecurity` | object | null | MQTT username/password/SSL security config (see [Broker Security](#broker-security)) |

**Response** (201 Created):
```json
{
  "loaded": true,
  "specTitle": "My API",
  "specVersion": "2.6.0",
  "channelCount": 2,
  "channels": [{"name": "orders", "hasSchema": true}],
  "publishers": 1,
  "subscribers": 1
}
```

### `GET /mockserver/asyncapi`

Returns current status including loaded spec info, active channels, and recorded messages from subscribers.

**Response** (200 OK):
```json
{
  "loaded": true,
  "specTitle": "My API",
  "specVersion": "2.6.0",
  "channels": [{"name": "orders", "hasSchema": true, "exampleCount": 1}],
  "publishers": 1,
  "subscribers": 1,
  "recordedMessages": [
    {
      "channel": "orders",
      "key": "order-123",
      "payload": "{\"orderId\":42}",
      "headers": {"trace-id": "abc"},
      "timestamp": "2024-01-01T00:00:00Z",
      "schemaValid": true
    }
  ]
}
```

### `PUT /mockserver/asyncapi/verify`

Verify that recorded messages match the given criteria. Mirrors the semantics of `PUT /mockserver/verify` for HTTP requests.

**Request body** (JSON):

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| `channel` | string | yes | The channel/topic to check |
| `payloadSubstring` | string | no | Payload must contain this substring |
| `payloadJsonPath` | string | no | Dot-notation JSON path to extract from the payload (e.g. `user.name`) |
| `expectedValue` | string | no | Expected value at the JSON path (used with `payloadJsonPath`) |
| `count` | object | no | Count constraints: `{atLeast, atMost, exactly}`, each a whole number of at least 0. Default: `{atLeast: 1}` |

Any other field is refused with a 400 rather than ignored (`rejectUnknownFields` in `AsyncApiControlPlaneImpl`): an ignored `atMost` at the top level, for example, would leave the check at the default "at least 1". A count field at the top level gets a message saying it belongs under `count`.

**Responses:**

| Status | Meaning |
|--------|---------|
| 202 Accepted | Verification passed |
| 406 Not Acceptable | Verification failed (body contains human-readable failure reason) |
| 400 Bad Request | Malformed request (missing channel, invalid JSON, an unknown field, a count that is not a whole number of at least 0); the body is `{"error": "..."}` |
| 501 Not Implemented | mockserver-async module is not on the classpath; the response body names the artifact, where its version comes from, and the container mount point, so a caller can act on it without leaving the error |

**Example — verify at least 1 message on "orders" with a specific user name:**
```json
{
  "channel": "orders",
  "payloadJsonPath": "user.name",
  "expectedValue": "Alice",
  "count": { "atLeast": 1 }
}
```

**Example — verify exactly 0 messages on a channel (negative assertion):**
```json
{
  "channel": "errors",
  "count": { "exactly": 0 }
}
```

### `PUT /mockserver/asyncapi/http`

Import an AsyncAPI spec as **HTTP mock expectations** instead of loading it into the broker-publishing mock. One `GET` expectation is created per channel, returning that channel's schema-aware example payload (the same payload `MessageExampleGenerator` produces for broker publishing). This lets a consumer poll example messages over plain HTTP with no live Kafka/MQTT/AMQP broker.

It reuses the same `AsyncApiParser` and `MessageExampleGenerator` as the broker path (in `AsyncApiHttpExpectationGenerator`); the generated expectations are serialized with core's `ExpectationSerializer` and returned across the SPI as a JSON array so `HttpState` can deserialize and add them without a compile-time dependency on the parser.

**Request body:** a plain AsyncAPI spec (JSON/YAML), or a wrapper `{"spec": "...", "channelPathPrefix": "/events"}`. Channel names are mapped to URL paths (dots become slashes; collisions get a numeric suffix); `channelPathPrefix` is prepended when supplied.

**Responses:**

| Status | Meaning |
|--------|---------|
| 201 Created | One GET expectation created per channel (body is the upserted expectation array) |
| 400 Bad Request | Missing or unparseable spec, or a spec with no channels |
| 501 Not Implemented | mockserver-async module is not on the classpath; the response body names the artifact, where its version comes from, and the container mount point, so a caller can act on it without leaving the error |

### Reset

All async mocking state (publishers, subscribers, recorded messages) is cleared on `PUT /mockserver/reset`.

Server `stop()` resets the control plane too, and returns within a few seconds even when a broker is slow to close.

```mermaid
sequenceDiagram
    participant Caller as stop() or PUT /mockserver/reset
    participant CP as control plane monitor
    participant Closer as daemon closer thread
    participant Load as a later load()
    Caller->>CP: detach brokers, register the close
    Caller->>Closer: start closing (orchestrators, publishers, subscribers)
    Caller->>Caller: wait up to 5 s
    Caller-->>Caller: still closing, so WARN once from its own thread and return
    Load->>CP: detach the replaced brokers, register their close
    Load->>Closer: wait up to 30 s for every registered close, without the monitor
    Closer-->>Load: closed
    Load->>CP: retake the monitor, no reset meanwhile, so connect the new brokers
```

- `reset()` takes the orchestrators, publishers and subscribers out of the control plane under its monitor, registers their close in a JVM-wide set, and closes them on a daemon thread (`MockServer-AsyncAPI-broker-close`) after releasing the monitor. It waits up to `CLOSE_WAIT` (5 s, the budget the orchestrator and the Kafka subscribers already give their own shutdown, well inside the 30 s after which `stop()` gives up) and then returns. Closes still running finish in the background, and the thread ends when they do.
- When it stops waiting, `reset()` logs one WARN saying the brokers are still closing. A separate short-lived daemon thread writes that line: console logging is synchronous, and closing brokers can log (the orchestrator does, and so can the broker client libraries), so a line written on the caller's thread would make `stop()` wait for a blocked console after all.
- `load()` takes the brokers of the spec it replaces out under the monitor and closes them on a daemon thread too. It then waits, up to `EARLIER_CLOSE_WAIT` (30 s) in all and without holding the monitor, for every registered close on any control plane in the JVM, and connects only once it retakes the monitor and finds nothing loaded and nothing still closing: a restarted server registers a new control plane, and its brokers can reuse the client ids of ones still closing (an MQTT broker disconnects the older session of a client id). A close still running at the deadline is logged once at WARN and the load connects anyway: broker connections are clients and bind nothing an old one still holds, and failing would leave every later load in the JVM failing behind one stuck close. Because the monitor is free while it waits, a `reset()` (so `stop()`), `status` or `verify` meanwhile is not held up; a reset or stop meanwhile cancels the load, which fails rather than connect brokers to a stopped server. A load that fails part-way closes the brokers it had connected the same way, so nothing closes on a caller's thread while it holds the monitor.
- A console that drains slowly therefore no longer holds `load`, `status` or `verify` behind a reset, a reset closes only the brokers it took out, never ones a concurrent `load` has just created, and `reset()` writes nothing else of its own.

`AsyncApiControlPlaneResetConsoleTest` (mockserver-async) guards the monitor and the hand-over; `AsyncApiControlPlaneBoundedCloseTest` (mockserver-async) guards the bound, the daemon thread ending, a load (also on a second control plane, as after a restart) waiting for an earlier close or, once that outlasts its wait, connecting anyway, a reset and a status during a waiting load returning promptly and cancelling it, and a load closing the brokers it replaces without holding the monitor. `StopWithBlockedConsoleIntegrationTest` (mockserver-netty) stops an INFO-level server with no spec loaded and a client still connected while every line reaching the root console blocks its writer (MockServer's own loggers at every `java.util.logging` level), and fails if `stop()` does not finish; `StopWithSlowAsyncApiBrokerCloseIntegrationTest` (mockserver-netty) does the same with a broker whose close logs to that console and then blocks, and checks the notice and that the closer thread ends. The TRACE variant in `StopWithBlockedConsoleIntegrationTest` covers the per-connection wire trace, which logs on the event loops: `stop()` waits for them to terminate at most `LifeCycle.EVENT_LOOP_TERMINATION_WAIT_MILLIS` (5 s), then writes a WARN notice from a short-lived daemon thread straight to the console (the event log has stopped by then) and returns, the listening sockets already closed; each loop ends when its blocked task returns. The test checks the notice once the console drains.

## AsyncAPI Parsing

The parser auto-detects JSON vs YAML (by leading `{` character) and supports:

- **AsyncAPI 2.x**: `channels.<name>.publish|subscribe.message.payload` for schema; `.payload.example` for inline examples; `.message.examples[].payload` for the examples array; **`message.oneOf`** for multi-message channels (each variant becomes a separate `AsyncApiMessage`)
- **AsyncAPI 3.x**: `channels.<name>.messages.<msgName>.payload` for schema; `.examples[].payload` for examples; basic `$ref` resolution to `#/components/messages/<name>`; **all messages** under `channels.<name>.messages` are parsed (not just the first)

Missing or incomplete structures are tolerated gracefully (channels appear with empty examples).

### Multi-Message Channels

A channel may define multiple message types:

- **AsyncAPI 3.x**: multiple entries under `channels.<name>.messages` (e.g. `userCreated`, `orderPlaced`)
- **AsyncAPI 2.x**: `message.oneOf` array in the operation (e.g. `publish.message.oneOf: [{...}, {...}]`)

When a channel has multiple messages:

1. Each message is parsed into an `AsyncApiMessage` with its own payload schema, examples, and Kafka key binding
2. The channel's `getMessages()` method returns the full list of `AsyncApiMessage` instances
3. The orchestrator publishes **one example per message** (not just one per channel)
4. Per-message `PublishOptions` combine the message's Kafka key with the channel-level MQTT qos/retain
5. Legacy single-message accessors (`getPayloadExamples()`, `getPayloadSchema()`, `getKafkaKey()`) continue to return the **first** message's values for backward compatibility

For single-message channels, `getMessages()` synthesizes a single-element list from the channel's existing fields, so all callers can use the uniform per-message API without conditional logic.

## Example Generation (Schema-Aware)

The `MessageExampleGenerator` follows this precedence per channel:

1. First explicit example from the spec
2. Schema-aware synthesis from JSON Schema, respecting:
   - `default` values
   - `enum` (uses first value)
   - `const` values
   - `minimum`/`maximum` and `exclusiveMinimum`/`exclusiveMaximum`
   - `minLength` (pads string to required length)
   - `minItems` (pads array to required size)
   - `format` (generates format-appropriate values: date-time, email, uuid, uri, ipv4, ipv6)
   - `pattern` (heuristic matching for common patterns like email, numeric)
3. Fallback: `{}`

## Schema Validation

The `AsyncApiSchemaValidator` reuses core's `JsonSchemaValidator` (backed by `com.networknt:json-schema-validator`) to validate:

- **Generated examples** before publishing (warnings logged for non-conforming examples)
- **First spec-provided message example per channel** at load time (see below)
- **Consumed/recorded messages** from broker subscriptions (validation result included in status response)

### First-Message Example Validation (Load-Time)

When an AsyncAPI spec is loaded via `PUT /mockserver/asyncapi`, the control plane validates the **first payload example** of each message in each channel against that message's declared payload schema. This catches malformed spec examples early, before any broker interaction.

- **Scope**: only the first example per message is validated (not all examples).
- **Skip conditions**: messages with no payload schema or no explicit examples are skipped cleanly.
- **Multi-message channels** (v3 multiple messages, v2 `oneOf`): each message's first example is validated independently.
- **Surfacing**: non-conforming examples are surfaced as:
  - A `WARN`-level log line (matching the existing generated-example warning pattern)
  - A `validationIssues` entry in the load response and status response, with context `first_message_example` (or `first_message_example:<messageName>` when the message has a name)
- **No hard failure**: the load succeeds even when examples fail validation, matching the existing convention for generated-example validation.
- **No publish-time impact**: this validation is purely at load time and does not affect publish-time behaviour.

## Consumer/Subscriber Mocking

MockServer can **subscribe** to Kafka topics, MQTT topics, and AMQP queues/exchanges to record incoming messages, mirroring how HTTP requests are recorded for verification. Subscribers are created when `consume: true` is set in the broker config.

**AMQP subscribe:** the queue to consume from is derived from the channel's `AmqpBinding`, mirroring the publisher's destination resolution: a queue-based channel (or a channel with no binding) consumes the named queue directly; a routingKey-based channel declares the exchange, declares a private (server-named, exclusive, auto-delete) queue, and binds it to the exchange on the routing key. AMQP message-property headers are recorded as the `RecordedMessage` headers.

Recorded messages include:
- Channel/topic name
- Message key (Kafka) or null (MQTT)
- Payload (string)
- Headers (Kafka) or empty map (MQTT)
- Timestamp
- Schema validation result (when a schema is defined)

Recorded messages are stored in a **bounded** `BoundedMessageStore` per channel (default 1000 messages). When the cap is reached, the oldest message is evicted (FIFO). This prevents unbounded memory growth under high message volume.

**MQTT wildcard subscriptions.** A broker delivers each message on a **concrete** topic, so messages are recorded under that topic — but a subscription may have been made with a wildcard **filter**, and verification asks for the filter. `MqttTopicFilter` reconciles the two by matching concrete topics against the filter per MQTT 3.1.1 §4.7 (`+` matches exactly one level; `#` must be the last level and matches that level and everything beneath it, so `sport/#` also matches `sport`; a leading wildcard does not match reserved `$` topics). Both the MQTT 3.1.1 and MQTT 5 subscribers apply it in `getRecordedMessages(channel)`, and the recorded `channel` remains the concrete topic. Without this a wildcard subscription records messages that can never be retrieved and every verification against it silently reports zero matches.

## Message Keys, QoS, and Headers

- **Kafka**: `KafkaMessagePublisher.publish(channel, key, payload, headers)` supports configurable record keys and arbitrary headers
- **MQTT**: `MqttMessagePublisher` supports configurable QoS (0, 1, or 2) and binary payloads via `publishBytes()`
- **Kafka Consumer**: `KafkaMessageSubscriber` records message keys and headers from consumed records

## Kafka Avro / Confluent Schema Registry

Set `kafkaValueFormat: "avro"` to have MockServer publish and consume Kafka messages in the **Confluent Schema Registry wire format** (`[0x00][schemaId:4][Avro binary]`) so real Confluent Avro producers/consumers interoperate with the mock.

```mermaid
flowchart LR
    J["JSON example\n(from spec)"] --> C["AvroPayloadCodec\njsonToAvro"]
    C --> W["ConfluentWireFormat\nencode(schemaId, avro)"]
    W --> K["Kafka topic\n(byte[] value)"]
    K --> D["ConfluentWireFormat\ndecode"]
    D --> R["resolve schema\n(registry by id / inline)"]
    R --> A["AvroPayloadCodec\navroToJson"]
    A --> M["RecordedMessage\n(JSON payload)"]
```

**Design decision — Apache Avro, not the Confluent serde stack.** The Confluent `kafka-avro-serializer`/`kafka-schema-registry-client` artifacts are under the Confluent Community License and pull in a heavy dependency tree. MockServer instead uses **Apache Avro** (Apache 2.0) for the JSON&lt;-&gt;binary codec (`AvroPayloadCodec`), a hand-rolled 5-byte framing (`ConfluentWireFormat`), and a minimal JDK-`HttpClient` Schema Registry REST client (`SchemaRegistryClient`). This keeps licensing clean and dependencies small while remaining byte-compatible with Confluent producers/consumers.

**Two modes:**

| Mode | Trigger | Publish schema id | Consume schema resolution |
|------|---------|-------------------|---------------------------|
| Registry-backed | `kafkaSchemaRegistryUrl` set | schema registered under `<topic>-value`, returned id embedded | fetched by embedded id via `GET /schemas/ids/{id}` (cached) |
| Registry-less | no `kafkaSchemaRegistryUrl` | fixed `avroSchemaId` (default 1) embedded | the inline `avroSchema`, **only for messages carrying `avroSchemaId`** |

Both modes require an `avroSchema` (inline) for the **publish** path (encoding needs a schema) and for **registry-less consume** (decoding without a registry needs the inline schema). Consumed bytes that are not wire-format-framed, or that fail to decode, are recorded as their raw UTF-8 string rather than dropped.

In registry-less mode the inline schema describes exactly one writer schema id, so the embedded id **must** match `avroSchemaId`. Avro binary carries no field names or types — only values in schema order — so decoding a message written with a different schema of the same shape succeeds and yields **silently transposed values**. A message framed with any other id is therefore recorded undecoded (raw UTF-8) with a warning, rather than decoded incorrectly; configure a Schema Registry, or align `avroSchemaId` with the producer, to decode it. Because the recorded payload is JSON, the existing `PUT /mockserver/asyncapi/verify` substring / JSON-path checks work unchanged against Avro messages.

**JSON encoding note:** Avro's JSON representation is stricter than plain JSON for union-typed (nullable/optional) fields, which need the `{"fieldType": value}` form. Flat records of primitive fields — the common case for AsyncAPI example payloads — map straight across.

## MQTT Protocol Version (3.1.1 and 5)

`mqttProtocolVersion` selects the Paho client: `3` (default, `MqttMessagePublisher`/`MqttMessageSubscriber`, Paho `mqttv3`) or `5` (`Mqtt5MessagePublisher`/`Mqtt5MessageSubscriber`, Paho `mqttv5`). QoS, retain, and binary payloads behave identically across versions. The v5 advantage is **user properties**: `PublishOptions.getHeaders()` (e.g. header-location correlation IDs) are delivered as MQTT 5 user properties on publish and recorded as `RecordedMessage` headers on consume — neither of which MQTT 3 can carry. Security (`mqttSecurity`: username/password/SSL) applies to both versions via `MqttSecurityOptions` (v3) / `Mqtt5SecurityOptions` (v5).

> **Paho v5 subscribe caveat:** Paho `mqttv5` 1.2.5's `MqttClient.subscribe(topic, qos, listener)` overload self-recurses (StackOverflow). `Mqtt5MessageSubscriber` deliberately uses the safe 2-arg `subscribe(topic, qos)` together with a `setCallback(...)` message callback, and the live tests observe messages through that subscriber rather than a per-subscription listener.

## AsyncAPI Channel Bindings

The parser extracts publish-time bindings from the AsyncAPI spec and threads them through to the publishers via an immutable `PublishOptions` carrier. The following bindings are supported:

### Supported Bindings

| Binding | AsyncAPI Location | Applies To | Effect |
|---------|-------------------|------------|--------|
| MQTT QoS | `publish.bindings.mqtt.qos` (v2), `channels.<n>.bindings.mqtt.qos` (v3 best-effort) | `MqttMessagePublisher` | Overrides the instance-level QoS for the message |
| MQTT retain | `publish.bindings.mqtt.retain` (v2), `channels.<n>.bindings.mqtt.retain` (v3 best-effort) | `MqttMessagePublisher` | Sets `MqttMessage.setRetained()` |
| Kafka message key | `publish.message.bindings.kafka.key` (v2), `messages.<n>.bindings.kafka.key` (v3) | `KafkaMessagePublisher` | Sets the `ProducerRecord` key |

### AMQP Channel Bindings

For AMQP, the destination is **not** the channel name directly — it is derived from the channel's `bindings.amqp` definition. The parser reads `channels.<name>.bindings.amqp` (channel-level in both v2 and v3) into an `AmqpBinding`, and `AmqpMessagePublisher` resolves it to an (exchange, routing-key) pair:

| `is` value | Exchange | Routing key | Notes |
|------------|----------|-------------|-------|
| `routingKey` (default) | `exchange.name` (or the default exchange `""` when absent) | the binding's `routingKey`, else the channel name | Exchange is declared idempotently using `exchange.type` (default `direct`) and `exchange.durable` (default `true`) before the first publish |
| `queue` | default exchange (`""`) | `queue.name` (or the channel name) | The named queue is declared (durable per `queue.durable`) so the message is not dropped against a fresh broker |

When a channel has **no** `bindings.amqp`, the publisher falls back to the default exchange with the channel name as the routing key — mirroring the topic-name-as-destination convention of the Kafka/MQTT publishers.

**Supported binding fields:** `is`, `exchange.name`, `exchange.type`, `exchange.durable`, `queue.name`, `queue.durable`, and an explicit `routingKey` extension. Correlation-ID/`PublishOptions` headers are emitted as AMQP message properties.

**Unroutable messages.** Declaring an exchange does not bind any queue to it, so an exchange-routed publish can still reach no consumer — and per AMQP 0-9-1 §3.1.3 the broker discards such a message *silently* unless `mandatory` is set. Every publish is therefore sent with `mandatory=true` on a channel in publisher-confirm mode: the publish blocks for the confirm, and a `basic.return` (nothing bound for the routing key) raises an error rather than being reported as a successful publish.

**Publisher confirms are a RabbitMQ extension**, not part of AMQP 0-9-1. A broker that does not implement `confirm.select` answers `540 NOT_IMPLEMENTED`, which is a **channel-level** error — the broker closes the channel, and a closed AMQP channel can never be reopened, only replaced. The publisher therefore records `confirmsEnabled = false` *and* replaces the dead channel from the connection (`replaceClosedChannel()`, clearing `declaredExchanges`/`declaredQueues` since topology does not carry over), then publishes without `mandatory` and without waiting for a confirm. Both the flag and the wait are guarded by `confirmsEnabled`; waiting for a confirm that was never selected would otherwise throw `IllegalStateException("Confirms not selected")` on *every* publish. If the channel cannot be replaced the constructor fails loudly rather than returning a publisher that cannot publish.

> **Verification status:** this fallback is covered by a unit test that models the broker's response (`confirmSelect()` raising, channel reporting closed, replacement obtained from the connection). It is **not** exercised against a real confirm-less broker — there is no non-RabbitMQ AMQP 0-9-1 container in the test suite. Treat RabbitMQ as the supported broker and this path as best-effort degradation.

**Never `waitForConfirmsOrDie`.** In amqp-client 5.x that method calls `close(...)` on the channel for both a nack and a timeout, and an application-initiated close is not auto-recovered by `AutorecoveringChannel`. A single confirm timeout — RabbitMQ stalls confirms indefinitely under a memory or disk alarm — would therefore leave every subsequent publish throwing `AlreadyClosedException` for the lifetime of the mock, defeating the scheduled-cycle recovery described below. `waitForConfirms(timeout)` is used instead and the publish fails on `false` or `TimeoutException` without destroying the channel.

**Failure containment.** Because a publish can now throw, `publishAll()` contains failures **per message** — it records the failure, continues to the remaining channels, flushes, and then throws an aggregate naming every channel that failed. Without that, the first unroutable channel would abort the whole cycle, and since each cycle restarts at channel #1 a 10-channel spec whose first channel has no bound queue would publish nothing at all, on any channel, forever — strictly worse than the silent single-channel drop being fixed. Both callers of `publishAll()` are then guarded in turn. `startPublishing` wraps each cycle so a failure is logged and the schedule survives — `ScheduledExecutorService.scheduleAtFixedRate` cancels the task permanently once an execution throws, and the throwable is buried in a `ScheduledFuture` nobody reads, so an unguarded cycle would stop periodic publishing for good and stay stopped even after the cause cleared. The load-time `publishOnLoad` call is wrapped in `publishOnLoadSurvivingFailure`, which records a `validationIssue` instead of propagating: that call sits inside the `try` whose `catch` takes out and closes every broker, so an escaping exception would tear down every broker's publishers, subscribers and orchestrators and fail `PUT /mockserver/asyncapi` — and since `publishOnLoad` defaults to true, an exchange-routed AMQP channel with no queue yet bound would deadlock at bootstrap (the consumer that binds the queue cannot start until the mock is up).

**Deferred (not applied at publish time):** `exchange.autoDelete`, `exchange.vhost`, `queue.exclusive`, `queue.autoDelete`, `queue.vhost`, and operation/message-level AMQP bindings (`cc`, `bcc`, `deliveryMode`, `replyTo`, `priority`, `timestamp`, `expiration`).

### Kafka Key Extraction

The Kafka key binding (`bindings.kafka.key`) can be:

- A **scalar literal** (string or number) -- used directly
- A **schema with `const`** -- the const value is used
- A **schema with `example`** -- the example value is used
- A **schema with `examples[]`** -- the first example is used
- A **bare schema** (no literal derivable) -- key is null (not applied)

### PublishOptions Threading

The `AsyncApiMockOrchestrator` iterates each channel's messages via `channel.getMessages()`, generates an example for each message, and builds per-message `PublishOptions` combining the message's Kafka key with the channel-level MQTT qos/retain. Each message results in a separate `publisher.publish(channel, payload, options)` call. For single-message channels, this is equivalent to the previous one-publish-per-channel behavior. Publishers that do not override the `publish(channel, payload, options)` method fall back to the default implementation which ignores the options, preserving backward compatibility.

## Correlation IDs

AsyncAPI messages may define a `correlationId` with a `location` runtime expression that specifies where a correlation identifier should be placed in the published message. The orchestrator generates a unique correlation ID (UUID by default) at publish time and injects it at the specified location.

### Supported Location Expressions

| Expression Pattern | Example | Effect |
|-------------------|---------|--------|
| `$message.header#/<headerName>` | `$message.header#/correlationId` | Adds a header `{headerName: id}` to `PublishOptions.headers`; Kafka publishers emit it as a `RecordHeader` |
| `$message.payload#/<jsonPointer>` | `$message.payload#/metadata/id` | Injects the correlation ID into the JSON payload at the given JSON Pointer path (creates intermediate objects if needed) |

Unrecognised location prefixes are skipped with a DEBUG log (never throw).

### Parsing

The parser reads `message.correlationId` from each message definition (v2 single, v2 oneOf variant, v3 per-message). If it is a `$ref` (e.g. `$ref: "#/components/correlationIds/defaultId"`), it is resolved using the same `resolveRef` mechanism used for message references. The `location` string is stored as `AsyncApiMessage.correlationIdLocation`.

### Injection at Publish Time

In `AsyncApiMockOrchestrator.publishAll()`, for each message with a non-null `correlationIdLocation`:

1. A unique correlation ID is generated via an injectable `Supplier<String>` (defaults to `UUID.randomUUID().toString()`; tests can pin it to a fixed value for deterministic assertions).
2. **Header location** (`$message.header#/...`): the header name is extracted from the location suffix, and a `{headerName: correlationId}` entry is added to the `PublishOptions.headers` map. `KafkaMessagePublisher` passes these as Kafka `RecordHeader` values.
3. **Payload location** (`$message.payload#/...`): the JSON Pointer is extracted, the generated example payload is parsed as JSON (Jackson `ObjectMapper`), the value is set at the pointer path (creating intermediate `ObjectNode` containers as needed), and the payload is re-serialized. If the payload is not valid JSON, injection is skipped with a DEBUG log.
4. One correlation ID is generated per message-publish. The injection happens **before** `publisher.publish()` is called.

### MQTT Header Limitation

MQTT does not support message-level headers. When `PublishOptions.headers` is non-empty and the publisher is `MqttMessagePublisher`, a DEBUG log is emitted noting that header-location correlation IDs are not delivered over MQTT. **Payload-location** correlation IDs work with MQTT because they are injected into the payload JSON before publishing.

### Limitations

- **v3 MQTT operation bindings**: In AsyncAPI 3.x, MQTT QoS and retain are properly located in the top-level `operations` section's bindings, not on channels. The parser checks for channel-level `bindings.mqtt` as a best-effort fallback but does **not** navigate v3 operation-to-channel references. Full v3 operation-binding resolution is deferred.
- **Kafka topic-config bindings**: Kafka channel bindings for topic configuration (partitions, replicas, cleanup policy) are intentionally **not** applied at publish time -- they describe topic creation parameters, not message-level settings.

## Broker Security

The `brokerConfig` supports optional security configuration for connecting to enterprise brokers that require SASL authentication and/or TLS. When security is absent or empty, the adapters use plaintext connections (backward compatible).

### Kafka Security (`kafkaSecurity`)

| Field | Kafka Config Key | Description |
|-------|-----------------|-------------|
| `securityProtocol` | `security.protocol` | Protocol: `PLAINTEXT`, `SSL`, `SASL_PLAINTEXT`, `SASL_SSL` (case-insensitive; anything else is a `400`) |
| `saslMechanism` | `sasl.mechanism` | SASL mechanism: `GSSAPI`, `PLAIN`, `SCRAM-SHA-256`, `SCRAM-SHA-512`, `OAUTHBEARER` (anything else is a `400`) |
| `sslTruststoreLocation` | `ssl.truststore.location` | Path to the SSL truststore file |
| `sslTruststorePassword` | `ssl.truststore.password` | Password for the SSL truststore |
| `sslKeystoreLocation` | `ssl.keystore.location` | Path to the SSL keystore file (for mTLS) |
| `sslKeystorePassword` | `ssl.keystore.password` | Password for the SSL keystore |
| `sslKeyPassword` | `ssl.key.password` | Password for the private key in the keystore |

Example (the JAAS configuration comes from `mockserver.asyncKafkaSaslJaasConfig`, set at startup):
```json
{
  "kafkaBootstrapServers": "broker:9093",
  "kafkaSecurity": {
    "securityProtocol": "SASL_SSL",
    "saslMechanism": "PLAIN",
    "sslTruststoreLocation": "/path/to/truststore.jks",
    "sslTruststorePassword": "changeit"
  }
}
```

#### The JAAS configuration is startup-only

A Kafka `sasl.jaas.config` names the JAAS login module **class** the Kafka client loads, so whoever sets it chooses
code to run in the MockServer JVM (for example `com.sun.security.auth.module.JndiLoginModule`, which performs a
JNDI/LDAP lookup). Control-plane authentication is off by default, so it must not be settable at runtime:

| Route | Behaviour |
|-------|-----------|
| `mockserver.asyncKafkaSaslJaasConfig` (system property, `MOCKSERVER_ASYNC_KAFKA_SASL_JAAS_CONFIG`, property file, `Configuration.asyncKafkaSaslJaasConfig(..)`) | **The only source.** Given to the Kafka client when a request's `securityProtocol` is `SASL_PLAINTEXT` or `SASL_SSL` (which may only use the startup `asyncKafkaBootstrapServers`, see below); never for `PLAINTEXT`/`SSL`. Masked as sensitive (`jaas` is a sensitive-name substring) in `--print-config` and `GET /mockserver/config`. |
| `PUT /mockserver/asyncapi` with `kafkaSecurity.saslJaasConfig` (or any `brokerConfig` field whose name contains `jaas`) | Refused with `400` naming the startup property, and logged at WARN. A JSON `null` counts as absent. |
| `PUT /mockserver/asyncapi` with any other `kafkaSecurity` field | Refused with `400` listing the valid fields, so a class-loading Kafka property (`sasl.login.class`, `sasl.*.callback.handler.class`, `interceptor.classes`, `security.providers`, `config.providers`, serializer/deserializer classes, `ssl.engine.factory.class`, ...) can never be smuggled in. |
| `PUT /mockserver/asyncapi` selecting `SASL_*` with a `kafkaBootstrapServers` that is not the startup `asyncKafkaBootstrapServers` | Refused with `400` before any Kafka client is built, so no credential is sent. Applies whether or not a startup JAAS is set. The two are compared as sets of `host:port` values (trimmed, case-insensitive, any order); omitting `kafkaBootstrapServers` uses the startup value. With no startup bootstrap servers, every `SASL_*` request is refused with a `400` saying one is needed. `PLAINTEXT`/`SSL` requests may still name any bootstrap. |
| `PUT /mockserver/configuration` with `asyncKafkaBootstrapServers` | Always startup-only: `applyTo` skips it and `applyConfigurationUpdate` WARNs. It stays settable at startup and through `Configuration.asyncKafkaBootstrapServers(..)`. |
| `PUT /mockserver/configuration` with `asyncKafkaSaslJaasConfig` | Startup-only pattern: `ConfigurationDTO.applyTo` skips it and `HttpState.applyConfigurationUpdate` WARNs (value not shown); `GET /mockserver/configuration` never returns it (`@JsonIgnore`). |

No other Kafka client property that loads a class is reachable from a request: the producer/consumer
serializers, deserializers, auto-offset-reset and auto-commit settings are hard-coded in the publisher and
subscriber classes, and `KafkaSecurityProperties.applySecurity` writes only the keys in the table above plus `sasl.jaas.config`.
If no JAAS configuration is set at startup, the Kafka client falls back to the JVM-wide
`-Djava.security.auth.login.config` file (also startup-only).

The destination binding follows the held-secret rule in [configuration-reference.md](configuration-reference.md).
A request can no longer carry its own JAAS, so every credential a `SASL_*` client can use is held by the server: the
startup JAAS (with `PLAIN`, the password itself) or the JVM's JAAS login configuration. The server cannot reliably tell
whether the JVM holds one (`-Djava.security.auth.login.config`, `login.config.url.N` in `java.security`, a Kerberos
ticket cache), so every `SASL_*` request is bound, and `asyncKafkaBootstrapServers` is always startup-only rather
than only while a JAAS is set. Residual, not yet closed: the `ssl*Location` keystore and truststore paths and their passwords can still be set at runtime, so a
caller can make the Kafka client read any keystore-format file the MockServer process can read.

### MQTT Security (`mqttSecurity`)

| Field | Description |
|-------|-------------|
| `username` | MQTT broker username |
| `password` | MQTT broker password |
| `sslProperties` | Map of SSL properties passed to Paho `MqttConnectOptions.setSSLProperties()` |

Supported SSL property keys (Paho/IBM conventions):

| Key | Description |
|-----|-------------|
| `com.ibm.ssl.keyStore` | Path to the client keystore |
| `com.ibm.ssl.keyStorePassword` | Keystore password |
| `com.ibm.ssl.trustStore` | Path to the truststore |
| `com.ibm.ssl.trustStorePassword` | Truststore password |
| `com.ibm.ssl.protocol` | SSL protocol (e.g. `TLSv1.2`) |

Example:
```json
{
  "mqttBrokerUrl": "ssl://broker:8883",
  "mqttSecurity": {
    "username": "user",
    "password": "pass",
    "sslProperties": {
      "com.ibm.ssl.trustStore": "/path/to/truststore.jks",
      "com.ibm.ssl.trustStorePassword": "changeit"
    }
  }
}
```

### Implementation

Security configuration is applied through testable utility classes:

- `KafkaSecurityProperties.applySecurity(Properties, KafkaSecurity)` — sets the Kafka config keys on the client properties
- `MqttSecurityOptions.buildConnectOptions(MqttSecurity)` — builds `MqttConnectOptions` with username/password/SSL (returns `null` when empty, preserving the no-arg `connect()` path)

The security objects are parsed from the `brokerConfig` JSON in `AsyncApiControlPlaneImpl.parseBrokerConfig()` and threaded through to the publisher/subscriber constructors.

## Metrics

When the server is started with metrics enabled (`mockserver.metricsEnabled=true`), the async module emits two Prometheus counters labelled by `channel`:

| Metric | Incremented |
|--------|-------------|
| `mock_server_async_messages_published_total{channel}` | Once per message published to a broker (`AsyncApiMockOrchestrator.publishAll()` — both publish-on-load and scheduled publishing) |
| `mock_server_async_messages_consumed_total{channel}` | Once per message recorded from a broker subscription (`KafkaMessageSubscriber` / `MqttMessageSubscriber` record path) |

The module calls the static `Metrics.incrementAsyncMessagePublished(channel)` / `Metrics.incrementAsyncMessageConsumed(channel)` helpers in `mockserver-core`; they are null-safe no-ops when metrics are disabled, so the async hot paths pay nothing when metrics are off. The `optional` Maven scope on `mockserver-core` simply keeps it from being re-exported to downstream consumers of `mockserver-async` — the static import is still a compile-time dependency. The counters move only when a real broker is connected — a broker-less spec load (no `kafkaBootstrapServers`/`mqttBrokerUrl`) leaves them at zero. The dashboard Metrics view charts them on a dedicated "Async message activity" panel, separate from HTTP request activity. See [metrics.md](metrics.md#async-message-counters).

## Build/Docker Wiring

The `mockserver-async` module is wired into the running server:

- **mockserver-netty** declares `mockserver-async` as an optional dependency
- **mockserver-netty-no-dependencies** (the standalone/Docker jar) explicitly includes `mockserver-async` so it's bundled by the shade plugin
- **Registration**: `MockServer.createServerBootstrap()` uses reflection to call `AsyncApiControlPlaneImpl.registerIfAvailable()` at startup, avoiding a hard compile-time dependency
- When the module is absent from the classpath, the `/mockserver/asyncapi` endpoints respond with 501 (Not Implemented).
  All four routes, and the in-process `AsyncApiControlPlaneRegistry` methods, return the single
  `AsyncApiControlPlaneRegistry.NOT_AVAILABLE` text rather than their own copies — the message is the
  whole user experience of an opt-in feature, so it says how to enable the module and must not drift
  between surfaces. The reachable case is a build depending on `mockserver-netty`/`mockserver-core`
  directly (the optional dependency is not pulled in); the shaded jar and the Docker images bundle it already.

## Dependencies

| Dependency | Version | Purpose |
|------------|---------|---------|
| `jackson-databind` | (parent-managed) | JSON parsing and generation |
| `jackson-dataformat-yaml` | (parent-managed) | YAML parsing |
| `kafka-clients` | 3.9.2 | Kafka producer and consumer |
| `org.eclipse.paho.client.mqttv3` | 1.2.5 | MQTT 3.1.1 client (publish and subscribe) |
| `org.eclipse.paho.mqttv5.client` | 1.2.5 | MQTT 5 client (publish and subscribe) |
| `org.apache.avro:avro` | 1.12.0 | Avro JSON&lt;-&gt;binary codec for the Kafka Confluent wire format (Apache 2.0 — avoids the Confluent Community License serde stack) |
| `com.rabbitmq:amqp-client` | 5.28.0 | AMQP 0.9.1 (RabbitMQ) client (publish and subscribe) |
| `mockserver-core` | (optional) | SPI interface, JSON Schema validator, shared utilities |

## Tests

| Test Class | What it covers |
|------------|----------------|
| `AsyncApiParserTest` | AsyncAPI 2.x/3.x parsing (JSON, YAML, refs, edge cases, binding extraction) |
| `MessageExampleGeneratorTest` | Basic example generation (explicit, synthesized, fallback) |
| `MessageExampleGeneratorSchemaAwareTest` | Schema-aware synthesis (enum, default, format, min/max, const, minLength, minItems) |
| `PublishOptionsTest` | PublishOptions construction, validation, isEmpty, qos range checking |
| `AsyncApiMockOrchestratorTest` | Orchestrator publish/schedule lifecycle, PublishOptions threading (mocked publisher) |
| `KafkaMessagePublisherTest` | Basic Kafka publishing (mocked producer) |
| `KafkaMessagePublisherDeliveryFailureTest` | `flush()` drains the producer and reports an async delivery failure to the caller rather than only logging it |
| `KafkaMessagePublisherKeyHeadersTest` | Kafka keys, headers, and PublishOptions (key from bindings) (mocked producer) |
| `MqttMessagePublisherTest` | Basic MQTT publishing (mocked client) |
| `MqttMessagePublisherQosTest` | MQTT QoS, binary payloads, and PublishOptions (retain, qos override) (mocked client) |
| `BoundedMessageStoreTest` | Bounded FIFO store: capacity, eviction, snapshot isolation, edge cases |
| `KafkaMessageSubscriberTest` | Kafka subscribing, message recording, bounded eviction, queued-ops pattern (mocked consumer) |
| `MqttMessageSubscriberTest` | MQTT subscribing, message recording, bounded eviction (mocked client) |
| `MqttTopicFilterTest` | MQTT 3.1.1 §4.7 worked examples: `+`/`#` levels, `#` including the parent level, reserved `$` topics, malformed filters |
| `MqttWildcardSubscriptionTest` | Wildcard subscriptions are retrievable by filter for both MQTT 3.1.1 and MQTT 5 (mocked clients) |
| `AsyncApiSchemaValidatorTest` | Schema validation (required, type, enum, min/max, pattern) |
| `AsyncApiFirstMessageExampleValidationTest` | Per-message first-example schema validation at load time: conforming, non-conforming, no-schema, no-example, multi-message, first-only scope, context naming |
| `AsyncApiControlPlaneImplTest` | Control-plane load/status/reset lifecycle (no real broker) |
| `AsyncApiControlPlaneResetConsoleTest` | `reset()` closes brokers outside the monitor (a blocked console does not hold `status()`), and only the brokers it took out |
| `AsyncApiControlPlaneBoundedCloseTest` | `reset()` waits for broker closes only up to its bound, on a daemon thread that ends; a later `load()`, also on a new control plane, waits for those closes without holding the monitor, or connects anyway once they outlast its wait; a reset during that wait returns promptly and cancels the load; replaced brokers close off the monitor |
| `AsyncApiControlPlaneSecurityTest` | Security scheme parsing from `brokerConfig` JSON (kafkaSecurity, mqttSecurity, edge cases) |
| `AsyncApiControlPlaneKafkaStartupOnlyTest` | `saslJaasConfig` and every class-loading Kafka property are refused; mechanism/protocol allow-lists; the startup JAAS reaches the Kafka client properties for `SASL_*` only; every `SASL_*` request, with or without a startup JAAS, must omit `kafkaBootstrapServers` or match the startup value, and needs one set (otherwise a `400`) |
| `AsyncApiControlPlaneVerifyTest` | Message verification: count semantics (atLeast/atMost/exactly), payload substring, JSON path matching, error cases |
| `AsyncApiControlPlaneRegistryTest` | SPI holder delegation (including verify) and not-available responses (in core) |
| `KafkaSecurityPropertiesTest` | `KafkaSecurityProperties.applySecurity()`: all/partial/empty/null security, blank value skipping |
| `KafkaMessagePublisherSecurityTest` | `buildProducerProperties()` with SASL_SSL, null, empty, and partial security |
| `KafkaMessageSubscriberSecurityTest` | `buildConsumerProperties()` with SASL_SSL, SCRAM, null, empty security |
| `MqttSecurityOptionsTest` | `MqttSecurityOptions.buildConnectOptions()`: username/password, SSL properties, null/empty security |
| `AmqpBindingParserTest` | AsyncAPI AMQP `bindings.amqp` parsing (routingKey/queue, exchange/queue fields, defaults, no-binding, explicit routingKey, isolation from Kafka/MQTT) |
| `AmqpMessagePublisherTest` | AMQP destination derivation and publishing via a mocked RabbitMQ `Channel` (no broker): exchange/queue/routing-key resolution, idempotent declare, header properties, close, `mandatory` + publisher confirms, and unroutable-return reporting |
| `KafkaLiveBrokerIntegrationTest` | Docker-gated (Testcontainers): publish/consume via real Kafka, subscriber recording, orchestrator end-to-end |
| `MqttLiveBrokerIntegrationTest` | Docker-gated (Testcontainers): publish/receive via real Mosquitto, subscriber recording, wildcard subscription recorded on concrete topics and retrieved by filter, orchestrator end-to-end, round-trip |
| `AmqpMessageSubscriberTest` | AMQP subscribe with a mocked RabbitMQ `Channel` (no broker): queue resolution (queue-based, exchange/routing-key, no-binding), `basicConsume` registration, DeliverCallback recording, header extraction, unsubscribe cancel, bounded eviction |
| `Mqtt5MessagePublisherTest` | MQTT 5 publish with a mocked v5 client: QoS/retain, binary payloads, user-property (header) delivery, invalid-QoS guard |
| `Mqtt5MessageSubscriberTest` | MQTT 5 subscribe with a mocked v5 client: recording, user-properties-as-headers, disconnect health, bounded eviction |
| `Mqtt5SecurityOptionsTest` | `Mqtt5SecurityOptions.buildConnectOptions()`: username/password, SSL properties, null/empty security, blank-field skipping |
| `ConfluentWireFormatTest` | Wire-format framing: encode/decode round-trip, magic-byte + big-endian schema id layout, detection, empty payload, error cases |
| `AvroPayloadCodecTest` | Avro JSON&lt;-&gt;binary round-trip, type preservation, distinct encodings, schema-mismatch error |
| `SchemaRegistryClientTest` | Schema Registry REST client with a stubbed `HttpClient`: get-by-id, register, caching, non-200 handling, blank-URL guard |
| `KafkaAvroMessagePublisherTest` | Confluent framing via a mocked producer: registry-less fixed id, registry id, key/headers, missing-schema and schema-mismatch errors |
| `AsyncApiControlPlaneParityConfigTest` | `parseBrokerConfig` of the parity fields: MQTT protocol version, Kafka Avro value format, Schema Registry URL, inline Avro schema (string or object) |
| `AmqpLiveBrokerIntegrationTest` | Docker-gated (Testcontainers): publish **and subscribe/record** against a real RabbitMQ broker — queue-bound channel, exchange + routing-key channel, orchestrator end-to-end from a parsed spec, and an **unbound** exchange asserting an unroutable publish fails rather than silently succeeding (the other exchange tests build their own topology, so they prove the broker routes, not that MockServer notices when it does not) |
| `Mqtt5LiveBrokerIntegrationTest` | Docker-gated (Testcontainers/Mosquitto): MQTT 5 publish/receive, user-property round-trip, subscriber recording, adapter round-trip |
| `KafkaAvroLiveBrokerIntegrationTest` | Docker-gated (Testcontainers/Kafka): registry-less Confluent-framed Avro publish readable by a plain byte[] consumer, end-to-end round-trip through the MockServer Avro publisher/subscriber, and a message written under a different schema id recorded undecoded rather than mis-decoded |
| `KafkaAvroSchemaIdMismatchTest` | Registry-less decode honours the embedded schema id: a same-shape schema under a different id is not decoded into transposed values |

## Configuration Properties (Async Defaults)

The following `ConfigurationProperties` provide server-wide defaults for async messaging. Values set via the request body's `brokerConfig` take precedence over these defaults.

| Property Key | Env Var | Type | Default | Description |
|-------------|---------|------|---------|-------------|
| `mockserver.asyncKafkaBootstrapServers` | `MOCKSERVER_ASYNC_KAFKA_BOOTSTRAP_SERVERS` | String | `""` (none) | Default Kafka bootstrap servers; used when `brokerConfig.kafkaBootstrapServers` is absent. Startup-only; the only brokers a `SASL_*` request may use |
| `mockserver.asyncMqttBrokerUrl` | `MOCKSERVER_ASYNC_MQTT_BROKER_URL` | String | `""` (none) | Default MQTT broker URL; used when `brokerConfig.mqttBrokerUrl` is absent |
| `mockserver.asyncAmqpUri` | `MOCKSERVER_ASYNC_AMQP_URI` | String | `""` (none) | Default AMQP (RabbitMQ) connection URI; used when `brokerConfig.amqpUri` is absent |
| `mockserver.asyncRecordedMessageMaxEntries` | `MOCKSERVER_ASYNC_RECORDED_MESSAGE_MAX_ENTRIES` | int | `1000` | Maximum recorded messages per channel in subscriber stores |

These properties follow the standard four-form pattern (system property, environment variable, property file, `Configuration` instance setter) used by all other MockServer configuration properties.

## Java Client Helpers

`MockServerClient` provides fluent helpers for the asyncapi control-plane:

| Method | HTTP Call | Return / Throw |
|--------|-----------|---------------|
| `loadAsyncApi(String specOrWrappedJson)` | `PUT /mockserver/asyncapi` | JSON response string (spec info) |
| `asyncApiStatus()` | `GET /mockserver/asyncapi` | JSON status string |
| `verifyAsyncMessage(String verificationJson)` | `PUT /mockserver/asyncapi/verify` | Returns `this` on 202; throws `AssertionError` on 406 with the failure description |

Example usage:
```java
MockServerClient client = new MockServerClient("localhost", 1080);
client.loadAsyncApi("{\"spec\":{...}, \"brokerConfig\":{\"kafkaBootstrapServers\":\"localhost:9092\"}}");
String status = client.asyncApiStatus();
client.verifyAsyncMessage("{\"channel\":\"orders\",\"count\":{\"atLeast\":1}}");
```

## Deferred (Honest List)

The following items are **not yet implemented**:

- **Advanced AsyncAPI bindings (remaining)**: Kafka topic-config bindings (partitions, replicas) and v3 operation-level MQTT binding navigation are not yet implemented. MQTT qos/retain, Kafka message key, and AMQP exchange/queue/routing-key bindings are supported (see [AsyncAPI Channel Bindings](#asyncapi-channel-bindings))
- **Kafka Protobuf value format**: the Confluent wire-format framing ([`ConfluentWireFormat`](#kafka-avro--confluent-schema-registry)) is codec-agnostic, but only the **Avro** codec is wired up. Protobuf (which needs a `.proto`/descriptor to build `DynamicMessage`) is deferred. Avro (registry-backed and registry-less) is fully supported for publish and subscribe/verify
- **AMQP binding edge cases**: operation/message-level AMQP bindings (`cc`, `deliveryMode`, etc.) and queue/exchange lifecycle hints (`autoDelete`, `exclusive`, `vhost`) are deferred (see [AMQP Channel Bindings](#amqp-channel-bindings)). AMQP publish **and** subscribe/verify are both supported
- **Schema Registry authentication (basic auth / API keys)**: not yet supported — registry-backed mode works against unauthenticated registries only
- **Cross-protocol correlation linking (F15)**: correlating messages across protocols (e.g. HTTP request to Kafka response) is out of scope; each message's correlation ID is self-contained
