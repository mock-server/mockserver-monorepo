# Memory Management

This document covers how MockServer manages memory for log entries and expectations, how default limits are calculated, and how to tune them for your workload.

## Overview

MockServer stores two main categories of data in memory:

1. **Log entries** — recorded requests, matched expectations, forwarded requests, verification results, and operational log messages
2. **Expectations** — request matchers and their associated response/forward actions

Both are stored in bounded circular data structures that evict the oldest entries when full. The default size limits are computed dynamically based on available JVM heap memory.

```mermaid
graph TB
    subgraph "JVM Heap"
        subgraph "Log Entry Storage"
            RB["LMAX Disruptor Ring Buffer
            Pre-allocated LogEntry slots
            Size: nextPowerOfTwo(ringBufferSize)
            default min(maxLogEntries, 16384)"]
            EL["CircularConcurrentLinkedDeque
            Persistent event store
            Count bound: maxLogEntries
            Byte bound: maxEventLogSizeInBytes"]
        end
        subgraph "Expectation Storage"
            PQ["CircularPriorityQueue
            Count bound: maxExpectations
            Byte bound: maxExpectationsSizeInBytes (opt-in)"]
        end
        OTHER["Netty buffers, thread stacks,
        class metadata, GC overhead"]
    end

    RB -->|"cloneAndClear()"| EL
    EL -->|"evict oldest → clear()"| GC[GC eligible]
```

## Default Limit Calculation

Both `maxLogEntries` and `maxExpectations` are computed from the JVM heap **ceiling** (`-Xmx`), a value fixed for the JVM's lifetime. The derived default is therefore a constant: every store constructed in the JVM gets the same capacity, and it does not vary with how much heap happened to be in use when the property was first read. (Before this was fixed, the default was derived from the *momentary free heap* — see [Timing Sensitivity](#timing-sensitivity) — which, combined with the JVM-wide caching of resolved defaults, let an unrelated heavy fixture running before the first store was constructed silently shrink the capacity of every store for the rest of the JVM.)

### Formula

```
heapAvailableInKB = maxHeap / 1024 - 20480      (maxHeap is the heap ceiling the JVM reports, see below)

maxLogEntries          = min(heapAvailableInKB / 8, 250000)          (entry-count bound)
maxExpectations        = min(heapAvailableInKB / 10, 15000)
maxEventLogSizeInBytes = (heapAvailableInKB / 20) * 1024 at INFO/WARN/ERROR/OFF   (retained-entry byte bound; 0 when heap ceiling undefined)
                       = (heapAvailableInKB / 12) * 1024 at DEBUG/TRACE
in-flight byte cap     = max(maxEventLogSizeInBytes, (heapAvailableInKB / 7) * 1024) at INFO/WARN/ERROR/OFF   (ring backlog; 0 when maxEventLogSizeInBytes is 0)
                       = max(maxEventLogSizeInBytes, (heapAvailableInKB / 12) * 1024) at DEBUG/TRACE
```

`maxLogEntries` and `maxEventLogSizeInBytes` are two independent bounds on the **same** event log — a count cap and a byte cap. Whichever is reached first evicts (see [Byte-Budget Eviction](#byte-budget-eviction-maxeventlogsizeinbytes)). The byte cap is derived from the same heap ceiling as the count cap, with a log-level-aware divisor: one-twentieth at `INFO`/`WARN`/`ERROR`/`OFF`, so retained entries do not outlive a young-GC cycle under load (see [Retention and young-GC promotion](#retention-and-young-gc-promotion)), and one-twelfth at `DEBUG`/`TRACE`, which were not measured. Entries still waiting in the ring are bounded by a separate in-flight cap that is never smaller than the retention budget (one-seventh at `WARN`/`ERROR`/`OFF` and `INFO`, one-twelfth at `DEBUG`/`TRACE` by default), so a small retention budget does not drop events during a burst. Together they keep the whole log at or below about a quarter of the ceiling at either level, even while overloaded (see [Validation at the current divisors](#validation-at-the-current-divisors) and [INFO re-measured on the perf rig](#info-re-measured-on-the-perf-rig-2026-10-18)). When a `ServeEventListener` or `ExpectationLifecycleListener` extension is loaded, the events waiting for those listeners (served requests with their bodies, and expectation changes, each weighed by its expectation's `estimatedHeapSize()` because it keeps a removed or superseded expectation reachable) add at most `extensionListenerMaxBytes` between them (one-twentieth of the ceiling, 16 MB without one), so the combined worst case is about 30% of the ceiling; that bound holds even with `maxLoggedBodyBytes` unlimited (see [extensions.md](extensions.md#listeners-h3-h7-h8)). It is **on by default** so a workload with large bodies cannot exhaust the heap through a count-bounded log that cannot see entry size. Set `mockserver.maxEventLogSizeInBytes=0` to disable it and bound the log by count only.

| Parameter | Value | Purpose |
|-----------|-------|---------|
| Base memory reservation | 20 MB (20,480 KB) | Reserved for JVM internals, Netty buffers, thread stacks |
| Per-log-entry estimate | 8 KB | Estimated heap cost per stored log entry (see [analysis below](#per-log-entry-type-estimates)) |
| Per-expectation estimate | 10 KB | Estimated heap cost per stored expectation including matcher (see [analysis below](#expectation-memory-analysis)) |
| Log entry hard cap | 250,000 | Upper bound regardless of heap |
| Expectation hard cap | 15,000 | Upper bound regardless of heap |

### Source Code

| Component | File | Method |
|-----------|------|--------|
| Heap available probe | `ConfigurationProperties.java` | `heapAvailableInKB()` |
| Undefined-max-robust heap calc | `ConfigurationProperties.java` | `computeHeapAvailableInKB(long, long)` |
| Heap-based default with floor | `ConfigurationProperties.java` | `heapBasedDefaultOrFloor(long, long, int, int)` |
| `maxLogEntries()` default | `ConfigurationProperties.java` | `maxLogEntries()` |
| `maxExpectations()` default | `ConfigurationProperties.java` | `maxExpectations()` |
| Ring buffer sizing | `Configuration.java` | `ringBufferSize()` (resolves field → property → `min(maxLogEntries, 16384)`) |
| Ring buffer default resolution | `ConfigurationProperties.java` | `resolveRingBufferSize(int)` |
| `maxEventLogSizeInBytes()` default | `ConfigurationProperties.java` | `defaultMaxEventLogSizeInBytes(long, Level)` |
| In-flight cap default | `ConfigurationProperties.java` | `defaultEventLogInFlightBytes(long, Level)`, `eventLogInFlightBytes(long, long, Level)` |
| DEBUG/TRACE divisor selection | `ConfigurationProperties.java` | `isDebugOrTrace(Level)` |
| Heap ceiling source | `ConfigurationProperties.java` | `heapAvailableInKB()` (`MemoryMXBean` heap max and `Runtime.maxMemory()`) |

### Example: Default Limits by Heap Size

The table below shows the computed defaults for different JVM heap configurations. Because the derivation uses the heap ceiling (`-Xmx`) and not the momentary free heap, these figures depend only on `-Xmx` — they are the same whether the property is first read at a clean start or after a heavy fixture has run.

| Max Heap (`-Xmx`) | Available KB (ceiling − 20 MB) | Default `maxLogEntries` | Default `maxExpectations` | Default `maxEventLogSizeInBytes` at `INFO`/`WARN`/`ERROR`/`OFF` (÷20) | Default `maxEventLogSizeInBytes` at `DEBUG`/`TRACE` (÷12) | Default in-flight cap at `WARN`/`ERROR`/`OFF` and `INFO` (÷7) | Default in-flight cap at `DEBUG`/`TRACE` (÷12) |
|---|---|---|---|---|---|---|---|
| 64 MB | 45,056 | 5,632 | 4,505 | 2,306,048 (2.2 MiB) | 3,844,096 (3.7 MiB) | 6,590,464 (6.3 MiB) | 3,844,096 (3.7 MiB) |
| 128 MB | 110,592 | 13,824 | 11,059 | 5,661,696 (5.4 MiB) | 9,437,184 (9.0 MiB) | 16,177,152 (15.4 MiB) | 9,437,184 (9.0 MiB) |
| 256 MB | 241,664 | 30,208 | 15,000 (capped) | 12,372,992 (11.8 MiB) | 20,621,312 (19.7 MiB) | 35,351,552 (33.7 MiB) | 20,621,312 (19.7 MiB) |
| 512 MB | 503,808 | 62,976 | 15,000 (capped) | 25,794,560 (24.6 MiB) | 42,991,616 (41.0 MiB) | 73,699,328 (70.3 MiB) | 42,991,616 (41.0 MiB) |
| 1 GB | 1,028,096 | 128,512 | 15,000 (capped) | 52,637,696 (50.2 MiB) | 87,730,176 (83.7 MiB) | 150,394,880 (143.4 MiB) | 87,730,176 (83.7 MiB) |
| 2 GB | 2,076,672 | 250,000 (capped) | 15,000 (capped) | 106,324,992 (101.4 MiB) | 177,209,344 (169.0 MiB) | 303,787,008 (289.7 MiB) | 177,209,344 (169.0 MiB) |
| 4 GB | 4,173,824 | 250,000 (capped) | 15,000 (capped) | 213,699,584 (203.8 MiB) | 356,165,632 (339.7 MiB) | 610,570,240 (582.3 MiB) | 356,165,632 (339.7 MiB) |

The Docker images size the heap at 45% of the container memory limit (`-XX:MaxRAMPercentage=45.0`, unless `-Xmx` is set), so a 512 MiB, 1 GiB, 2 GiB or 4 GiB container gets a 232 MiB, 462 MiB, 922 MiB or 1,844 MiB heap, giving `maxLogEntries` of 27,136, 56,576, 115,456 or 233,472 — roughly **27,000 log entries** at the 512 MiB floor (768 MiB for a `-clustered` node with the Infinispan backend) — and an `INFO` or `WARN` `maxEventLogSizeInBytes` of 10.6, 22.1, 45.1 or 91.2 MiB. See [docker.md → Heap Cap](../infrastructure/docker.md#heap-cap).

#### Log entries per request — budget for the expectation count, not a constant

A request does **not** cost a fixed 2-3 entries. The floor is 2-3 (`RECEIVED_REQUEST` + `EXPECTATION_MATCHED` + `EXPECTATION_RESPONSE`), but the matching scan in `RequestMatchers` also emits **one `EXPECTATION_NOT_MATCHED` entry at `INFO` for every expectation it evaluates before finding a match** (`HttpRequestPropertiesMatcher`). `INFO` is the default level, so this is on unless the level is raised.

The scan runs in sorted order and short-circuits at the first match, so the real cost per request is:

| Case | Entries per request |
|------|--------------------|
| Matches the first expectation evaluated | ~3 |
| Matches the k-th expectation evaluated | ~k + 2 |
| Matches nothing (N expectations) | ~N + 3 (N misses + `RECEIVED_REQUEST` + `NO_MATCH_RESPONSE` + the closest-match diagnostic) — or ~N + 2 when the closest-match diagnostic does not fire |

The closest-match diagnostic is the one entry worth being precise about, because it is easy to miscount in either direction. `RequestMatchers` emits exactly **one** additional `EXPECTATION_NOT_MATCHED` summary ("closest expectation matched X/Y fields"), and only when `matchedExpectation == null && closestMatchExpectation != null` at `INFO`. Its numerator is computed by re-evaluating the closest matcher without fail-fast, and that re-evaluation calls `suppressMatchResultLogging()` — so the re-evaluation itself emits nothing. That suppression is what keeps the summary from being *duplicated*; it does not remove the summary. So: one extra entry when a closest match is identified, none when there are no expectations, none below `INFO`.

With N expectations loaded, budget **up to ~N+2 entries per request**, not 2-3. A suite with 200 expectations whose requests mostly match late can burn ~200 entries per request, exhausting a 20,000-entry log in ~100 requests rather than the ~7,000-10,000 a flat 2-3 estimate implies.

Two mitigations reduce the multiplier at scale but do not remove it: above a size threshold the scan narrows to a `(method, exact-path)` candidate bucket rather than the full list, and raising the log level above `INFO` suppresses the not-matched entries entirely.

**Why this matters beyond memory:** under-sizing `maxLogEntries` causes eviction, and eviction silently destroys the evidence that verifications reason about. `verify(never())` and `verify(atMost(n))` cannot distinguish "never happened" from "evicted", so MockServer now **fails** such verifications once the log has evicted rather than passing them on an incomplete record (see [event-system.md](event-system.md) and the `failVerificationOnEvictedLog` property). Sizing this correctly is therefore a correctness concern, not only a memory one.

### Dev Mode Override

When `devMode` is enabled (`--dev` CLI flag, `-Dmockserver.devMode=true`, or `MOCKSERVER_DEV_MODE=true`), `maxLogEntries` and `maxExpectations` default to **1,000** instead of the heap-based formula above. This reduces memory usage for laptop and test-suite workloads. If either property is explicitly set (via system property, environment variable, or properties file), the explicit value takes precedence over the dev-mode default. See [configuration-reference.md](configuration-reference.md) for the full property definition.

### Shared Heap Pool

Both `maxLogEntries` and `maxExpectations` are calculated independently from the same available heap. In the worst case (both buffers completely full with average-sized entries), the combined memory usage could exceed the available heap. In practice this is mitigated by:

- The circular eviction means buffers rarely fill completely — older entries are GC'd as new ones arrive
- Most users have far fewer expectations than the cap allows
- The JVM's garbage collector reclaims memory from evicted entries promptly

On small heaps (< 256 MB), if you have both a large number of expectations AND high request volume, set explicit values for both properties rather than relying on the computed defaults.

### Netty Buffer Memory

Network buffers live outside the stores above, in Netty's pooled allocator. All request/response traffic — including HTTP/2 stream channels, the CONNECT/SOCKS relay and HTTP/3 request streams — uses the same `PooledByteBufAllocator` (see [netty-pipeline.md → ByteBuf Allocator](netty-pipeline.md#bytebuf-allocator)). Before this was pinned, HTTP/2 stream channels used Netty 4.2's adaptive allocator, so an HTTP/2 workload kept two separate buffer pools. Measured with the equivalent JVM-wide setting (`-Dio.netty.allocator.type=pooled`), a single pooled allocator lowered the HTTP/2 benchmark's maximum heap by about 34 MB with throughput and latency unchanged.

#### Direct-memory limit

**Whenever MockServer runs as its own process via the CLI (the jar, the Docker images, the forked Maven
plugin and the launchers), Netty's direct
memory is capped at a quarter of the maximum heap, at least 64 MiB and never more than the heap.** So the
heap plus Netty's buffers can reach 1.25× the heap rather than 2×: at a 232 MiB heap (a 512 MiB container
at the images' 45%) the cap is 64 MiB, and at 462 MiB (1 GiB) it is 115 MiB.

| Setting | Effect |
|---------|--------|
| Nothing set (default) | `org.mockserver.cli.Main` sets Netty's property to `max(64 MiB, heap / 4)`, capped at the heap, in its first static initialiser — Netty reads the property once, when it first initialises. Start-up logs a line starting `netty direct memory limit` at `INFO`, with the limit and whether it is the default or explicit |
| `-Dio.netty.maxDirectMemory=<bytes>` | Used as given; MockServer does not override it. `-1` restores Netty's own default (the JVM limit below). The shaded jar's relocated Netty reads `shaded_package.io.netty.maxDirectMemory` (shade rewrites the literal), so MockServer copies a user's value to that name; the plain name is built at runtime so shade cannot rewrite it (`assert-shaded-direct-memory-property.sh` checks the shaded jar) |
| `-XX:MaxDirectMemorySize=<size>` (command line or `JAVA_TOOL_OPTIONS`) | Netty uses it; MockServer does not set a limit of its own |
| Embedded (`ClientAndServer`, JUnit, Spring) | Unchanged: the process is the test JVM's, not MockServer's |

**Why.** Netty's limit otherwise defaults to the JVM's `MaxDirectMemorySize`, which defaults to the
maximum heap, and nothing stops a workload filling both. Measured Netty direct use under small-body load
stays near 8 MiB (two pooled arenas of 4 MiB chunks on one core); what grows it is buffers held per
connection. The dominant case — every slow reader holding a direct copy of its whole response — is
removed by `PacedLargeWriteHandler` (see
[netty-pipeline.md → Outbound Buffering and Backpressure](netty-pipeline.md#outbound-buffering-and-backpressure)).
The cap covers what remains, and each of these holds a whole body in direct memory until it is done:

- request bodies being aggregated (each connection holds its partial body, up to `maxRequestBodySize`);
- forwarded and proxied responses being aggregated (up to `maxResponseBodySize`, 50 MiB by default);
- responses relayed through a CONNECT/SOCKS tunnel, aggregated on the loopback leg before being written
  to the client.

Every server and forward-client aggregator is built by `HttpObjectAggregators`, which sizes the
aggregator's component limit to `max(1,024, maxContentLength / 1 KiB)` (10,240 for the 10 MiB request
limit, 51,200 for the 50 MiB response limit). Past its limit a `CompositeByteBuf` consolidates everything so
far into one new direct buffer, so with Netty's default of 1,024 collecting a large body briefly needed
about twice its size: a 49 MiB forward failed at a 64 MiB cap and fits now
(`DirectMemoryLimitForwardIntegrationTest`). The limit is not unbounded because each component costs about
110 bytes of heap, and a client can send one-byte chunks: unbounded, 1.5 million of them (9 MB on the wire)
pinned 163 MB of heap. A body whose chunks average under 1 KiB still reaches the limit
(`HttpObjectAggregatorsTest`); there the aggregator merges only the chunks added since its last merge. All of
these aggregators are `CoalescingHttpObjectAggregator`s in `mergeNewComponentsOnly` mode: the HTTP/1.1 server's, the
forward client's HTTP/1.1 one and its own HTTP/2 stream's, and both legs of the CONNECT relay. Netty's consolidation of the whole body each time copied about N² / (2 × limit) bytes
for a body of one-byte chunks: about 5.4 GB for a 10 MiB request and 26.8 GB for a 50 MiB forwarded response. Now
each byte is copied once, plus the whole body again each time half the limit is merged components, which happens
only at limits below about 2 MiB (1.5× the body for one-byte chunks at 1 MiB). The merged components count as one
towards the limit, as the single component Netty's consolidation leaves does, so each merge falls on the chunk where
Netty would have copied the whole body and copies at most that: for any mix of chunk sizes the merges never copy
more than Netty's consolidations, and the copies that free mostly unused reads (below) add at most the body again
to what is copied and one read to what is held (`MergeNewComponentsOnlyNettyDifferentialTest`). Counting each merged component
instead let the merges drift earlier than Netty's, and a 10 MiB-limit body that turned from one-byte to
1,000-byte chunks just before Netty's third consolidation was copied 10.3 MB where Netty copied 31 KB. A merge
releases every chunk it covers, so what is held at once stays within twice the body, the old worst case, plus the
framing of socket reads still holding unmerged chunks (`Http1ChunkComponentLimitTest`).

These aggregators deliberately do not copy runs of tiny chunks into blocks as HTTP/2 streams do (below). A chunk
is a slice of a socket read, and the read stays allocated while any chunk in it is held, so where larger chunks keep
the reads alive a block copy only adds memory. Measured through the real HTTP/1.1 codec, with reads of 64 KiB:
blocks held up to 2.9× the body at a 10 MiB limit (64 chunks of 1,023 bytes and a 1 KiB chunk, then four times 15
one-byte chunks and a 1 KiB chunk, repeated), against 1.1× for Netty's aggregator and for merging alone. The
worst case left is the component heap itself: about 1.2 MB per HTTP/1.1 connection at the 10 MiB
`maxRequestBodySize` (10,240 components, plus up to 1,023 merged ones, × ~110 B). The chunks a body holds from a
read it uses under half of are merged when it moves on to the next read (see **Read buffers a body pins** below), so
padding each read with a chunk extension no longer pins it. Where a merge at the limit leaves part of a read's tiny
chunks unmerged, that part is copied again: a body of one-byte chunks in 64 KiB reads is copied about 1.06 times,
not once (`Http1ChunkComponentLimitTest`).

**HTTP/2 and HTTP/3 request streams** get a tenth of that limit, never below 1,024
(`HttpObjectAggregators.streamComponentLimit`: 1,024 at the 10 MiB default, 6,553 at 64 MiB), because one
connection carries up to 100 concurrent streams (`HTTP2_MAX_CONCURRENT_STREAMS`; for HTTP/3,
`http3InitialMaxStreamsBidirectional`, default 100). A connection's worst case is therefore about 11 MB
(100 × 1,024 × ~110 B) on either protocol at the default, instead of ~110 MB; above 10 MiB it is
100 × `maxRequestBodySize` / 10 KiB × ~110 B, ten times the HTTP/1.1 figure. The total grows with the number
of connections, which only `maxInboundConnections` caps (off by default — see [Connection Memory](#connection-memory)).

- **HTTP/2** — each DATA frame becomes one component. The divisor stays below 16, so a full body in
  16 KiB frames (HTTP/2's default maximum frame size) fits without a copy at any `maxRequestBodySize`: 4,096
  frames at 64 MiB stay 4,096 components (`Http2StreamComponentLimitTest`). The stream aggregator is a
  `CoalescingHttpObjectAggregator` (`HttpObjectAggregators.streamHttpObjectAggregator`). Without it a body of
  one-byte frames was consolidated whole every 1,024 frames, about N² / 2,048 bytes of copying (53.7 GB for
  10 MiB). It now:
  - keeps the first 64 components, every frame of 1 KiB or more, and any run of fewer than 16 consecutive
    frames under 1 KiB (a frame cut short by the flow-control window, say) as they arrive, uncopied;
  - at the 16th frame of a run, copies the run into the free tail of the current 16 KiB block (a new one
    once it is full; the current block is kept when a larger frame interrupts the run), so each such byte is
    copied into a block once, a body of one-byte frames needs one component per 16 KiB, and at most one block per stream is
    part-used (at most 16 KiB unused);
  - once the body passes the limit, at the same frame where Netty would have consolidated the whole body,
    merges only the components added since its last merge (the HTTP/3 rule below), so the merges copy each
    byte at most once more. For this it replaces the aggregator's composite buffer with one that has no limit
    of its own (one extra empty composite per request, about 170 bytes of heap).

  Bytes copied, old → new (`CoalescingHttpObjectAggregatorTest`, and a counting allocator over the same
  aggregators at the 10 MiB default): 10 MiB in one-byte frames 53.7 GB → 10 MiB; in 100-byte frames
  538 MB → 10 MiB; in 1 KiB frames 47 MB → 10 MiB; 8 KiB frames, 16 KiB frames, 16 KiB frames with a
  16,383-byte frame every fourth, and one byte alternating with 16 KiB are unchanged (8.4 MB, 0, 0, 8.4 MB:
  the first merge copies the body so far, as Netty's consolidation did). Mixes of runs and larger frames cost
  more, for two reasons: the first merge still copies the whole body so far, and with fewer components it comes
  later, at a larger body; and bytes copied into a block are copied again when a merge covers them. Across 270
  mixes (runs of 15–32 frames of 1, 100 or 1,023 bytes between 1,023-byte, 1 KiB or 16 KiB frames, at 256 KiB
  to 64 MiB limits) at most 1.8× the body is copied, against up to 160× (and 5,120× for one-byte frames) before,
  and the most held at once (body pieces, blocks and merged copies) is at most 2.0× the body, the old worst
  case; 44 of those mixes peak higher than before (for example 1.83× against 1.00× for runs of 31 one-byte
  frames between 16 KiB frames at a 1 MiB limit). `CoalescingHttpObjectAggregatorTest` bounds copying and the
  peak to twice the body plus one block for runs of 100-byte frames between 1 KiB frames, and the blocks and
  merged copies to the body plus 32 KiB for runs of one-byte frames between 1 KiB frames at a 512 KiB limit. These
  peaks count each frame as a buffer of its own.

  **Through the real connection codec** a DATA frame is a retained slice of the read it arrived in, so the read stays
  allocated while any frame in it, from any stream, is held, and the frame decoder joins a frame cut by a read
  into a buffer grown in powers of two. Measured with `Http2ConnectionMemoryHarness` (`Http2FrameCodec` with
  production's settings, `Http2MultiplexHandler`, the real stream chain, a client honouring flow control, reads
  sized by Netty's adaptive read allocator, and every read, decoder buffer, block and merge counted, capacity growth
  included; `Http2StreamPeakHeldMeasurement` prints the table on demand):
  - an upload in 16 KiB frames, copied by nothing, holds 1.5–1.75× its size under every aggregator; one whose every
    fourth frame the flow-control window cuts a byte short holds 1.6–1.7× under Netty's aggregator and 1.4–1.5×
    under the others, which copy 0.18–0.25× of it (the decoder joins a frame cut by a read into a buffer twice its
    size, under half used when that frame is the short one, so it is copied: see **Read buffers a body pins**);
  - across 70 combinations of 19 uploads (one and four streams of 1-, 100- and 1,023-byte frames, mixes, a 16 KiB-
    frame stream sharing reads with tiny-frame streams, 256 KiB to 10 MiB per stream) with reads of 64 B to 64 KiB,
    the blocks peak at 2.8× the body (runs of one-byte frames between 16 KiB frames), merging alone
    (`mergeNewComponentsOnly`) at 2.0× and Netty's aggregator at 5.3×;
  - the blocks and the copying of mostly unused reads (below) free the reads small frames pin: 1.0–1.1× against
    2.5–3.0× for 1,023-byte frames, 1.1–1.3× against 1.6–5.3× beside a 16 KiB-frame stream, 1.1–1.6× against
    1.8–1.9× for runs of 100-byte frames between 1 KiB frames. They hold more than Netty's aggregator where larger
    frames of the same stream keep their buffers at least half used, and so kept, while the runs of one-byte frames
    between them are copied into blocks: 2.8× against 1.5× for runs of 31 one-byte frames between 16 KiB frames (a
    16 KiB frame cut by a read is joined into a 32 KiB buffer), 1.6–2.0× against 1.5–1.6× for runs of 15 one-byte
    frames between 1 KiB frames at reads of 16 KiB or less. Copying is at most 1.8× the body (1,023-byte frames then
    runs of one-byte frames, at reads of 1 KiB or less);
  - merging alone holds slightly more than Netty's aggregator in four of the 70 combinations (at most 2.0× against
    1.5×, runs of one-byte frames between 16 KiB frames at 64-byte reads), and copies a 10 MiB body of one-byte
    frames 8.3–10.2× (the whole-body restart every 512 merges, about every 512 KiB, at the stream limit), against
    1.0× for the blocks. So streams keep the blocks. The pooled allocator, its live buffers' capacities sampled at every
    allocation, gave the same peaks at 64 KiB reads before that copy (66 of 69 rows identical, the other 3 within 0.2%); that counts
    each buffer's requested capacity, not the pool's size-class rounding.

  These figures are for cleartext h2c. Over TLS a DATA frame is a slice of the buffer `SslHandler` decrypted it into
  instead of the read: with OpenSSL (the server's provider when its native library loads) one buffer per read for all
  its whole records, sized to the read's ciphertext; with the JDK's engine one buffer per record, up to 16 KiB of
  plaintext. Such a buffer is pinned exactly as a read is, and the copy below bounds it the same way, because it
  counts each piece under whatever buffer it unwraps to. Measured through a real `SslHandler` of each provider, the
  client sending each frame in records of its own or coalescing frames into 16 KiB records
  (`Http2StreamPeakHeldOverTlsTest`, 64 KiB reads): one 1,023-byte frame per read beside a completed ~32 KiB request
  held 9.9 MB under Netty's aggregator with OpenSSL (32× the 0.31 MB body, the whole wire) and 5.0 MB with the JDK's
  engine and coalesced records (16×), and 0.45 MB (1.46×) under the stream aggregator with either; 20 streams of
  4,097-byte frames held 6.8–7.7× (OpenSSL) and 3.9× (JDK, coalesced) against 1.14–1.23×; an upload in 16 KiB frames is
  never copied and holds 1.14–1.67×. With the JDK's engine and a record per frame each frame has a buffer of its own,
  so nothing is pinned. `Http2StreamPeakHeldTest` pins both sides through the codec in cleartext.

  The forward client's streams the upstream opens (below) coalesce the same way.

**Read buffers a body pins** (plan item 67). A piece of a body is usually a slice of a socket read, of a decoder's
cumulation, or of `SslHandler`'s output, and the whole buffer stays allocated while any slice of it is held. A client
can put each small piece of a body in a read filled with other, completed, requests (HTTP/2), or pad each chunk-size
line with a chunk extension (HTTP/1.1), up to 8,192 bytes since plan item 74 and unbounded until then. Before this
fix, 1,100 HTTP/2 frames of 1 KiB, each in its own read of about 32 KiB, held 29 MB for a 1.1 MB body, 11,000 one-byte
chunks with 60 KiB extensions held 671 MB (measured on the bare codec, before item 74's limit), and a connection's
streams could hold about 100 × 1,025 × 64 KiB ≈ 6.3 GiB (6.7 GB), held while the streams stay open. Every
`CoalescingHttpObjectAggregator` (HTTP/2 streams, the forward client's HTTP/1.1 and HTTP/2 streams, the HTTP/1.1
server and both legs of the CONNECT relay) now counts, per buffer, the
pieces and bytes it holds; when a body moves on to a new buffer, the pieces it holds from the previous one are copied
into buffers of their own if they are under half that buffer, and so are the pieces left of a buffer once a block
copy takes some of them leaves it under half used (a read held just over half by a 1 B, a 1,039 B and fifteen
1,023-byte frames, whose run of 16 the next read completes, otherwise stayed pinned at 1,040 B used: 2.6× the body
through the codec, against 1.75× under Netty's aggregator). On an HTTP/2 stream, pieces just before the one that
arrived go into the stream's current block instead (a new block is no larger than the body so far, up to 16 KiB), so
a later run copy does not copy them again and a short body is not given a whole block. Every buffer a body pins is
then at least half used,
except the one it is reading and one part-used block, so it holds at most about twice its body plus one read and
16 KiB, and briefly a further copy while a merge at the component limit is made. The one it is reading can be the
frame decoder's buffer that joins a frame cut by a read, grown in powers of two to hold it. A body whose pieces fill
their reads is never copied. Each buffer is counted under what it unwraps to, as its slices are: the allocator returns
a buffer Netty's leak detector samples (one in 128 at the default level, every one at `paranoid`) inside a wrapper
that a composite's components unwrap past (`CoalescingHttpObjectAggregatorLeakAwareBufferTest`). Measured through the real codecs (`Http2StreamPeakHeldTest`, `Http1ChunkPinningTest`):

| Shape | Netty's aggregator | Now |
|---|---|---|
| 1,100 HTTP/2 frames of 1 KiB, one per ~32 KiB read | 29 MB | 1.25 MB |
| 15 one-byte frames and one of 1 KiB, one per read | 28 MB | 0.20 MB |
| 20 streams × 100 frames of 4,097 B, one per read | 54 MB | 8.9 MB |
| 20 streams × 60 one-byte frames, one per read, no other traffic | 84 KB (6.7× the wire) | 9.2 KB (0.74×) |
| 11,000 one-byte HTTP/1.1 chunks, 60 KiB extensions (bare codec, before item 74's limit) | 671 MB | 0.24 MB |
| an upload in 16 KiB frames | 1.5–1.75× | the same, never copied |
| 16 KiB frames, every fourth a byte short (the window) | 1.6–1.7× | 1.4–1.5×, copying 0.18–0.25× |
| a 1 B, a 1,039 B and 15 frames of 1,023 B, repeated | 1.75× | 1.02× |

The cost is a copy of the pieces of mostly unused reads: concurrent uploads whose frames each take a small share of
the reads (50 streams of 1,000- or 4,097-byte frames) are copied about once and hold 1.03–1.09× their bodies instead of
1.5–1.7×, and streams of small frames copy up to about 1.8× their bodies in all. On an HTTP/1.1 upload whose chunks fill
their reads aggregation allocates the same and takes 3–10% longer (`Http1ChunkedAggregationBenchmark`, unpooled
heap, 3 forks, two rounds per side); where every HTTP/2 frame takes a small share of a separate buffer every frame is
copied once, 2.1× (1 B frames) to 7.6× (16 KiB frames) the aggregation time of a body that copies nothing
(`Http2StreamAggregationBenchmark`, which wraps each frame in a buffer of its own over one array). What the decoder
itself buffers for a chunk-size line is bounded separately: see [Chunk-size lines and trailers](#chunk-size-lines-and-trailers).

- **HTTP/3** — Netty hands a request body over in pieces of about one QUIC packet (about 1.1 KiB on
  loopback) whatever DATA frame size the client sent, so one component per piece would reach the limit at
  about 1.1 MiB, and `CompositeByteBuf`'s own consolidation copies the whole body each time it does (about
  four times an 8 MiB body in total). Instead
  `Http3RequestBridge.accumulateBody` keeps the first 64 pieces as they are, then copies each piece under
  16 KiB into 16 KiB blocks and keeps larger pieces as they are, so each byte is copied at most once and a
  body of tiny pieces needs one component per 16 KiB. Only pieces alternating between tiny and 16 KiB or more
  still reach the limit; `limitComponents` then merges the components added since its last merge. Measured
  at the default (`Http3BodyComponentLimitTest`, bytes allocated for copies, unused block space included):
  1.0× the body for an 8 MiB upload in 1,156-byte pieces and for 10 MiB of one-byte pieces, 1.7× for 10 MiB
  alternating one byte and 16 KiB. That last pattern also keeps up to one partly used 16 KiB block per large
  piece, so it can hold about twice its size. A body of fewer than 64 pieces, or of pieces of 16 KiB or more,
  is not copied.
- **Forward client** — its own request stream keeps the HTTP/1.1 limit, because a forward connection carries
  one request at a time (a pooled connection returns to the pool only when its stream ends), and merges only new
  components past it, without blocks, as its HTTP/1.1 aggregator does. The upstream
  can also open streams of its own (a server that answers on a new stream, as MockServer's older HTTP/2 server
  did), and each gets a response aggregator. The client advertises `SETTINGS_MAX_CONCURRENT_STREAMS` 1, so the
  upstream can open only one at a time, and it gets the per-stream limit
  (`Http2ForwardStreamChildInitializer.isPeerInitiated`): a forward connection holds at most one full-limit
  aggregator and one per-stream one (`Http2ForwardStreamComponentLimitTest`).

Two places still copy as they grow:
the relay's HTTP/2 legs, where Netty's `InboundHttp2ToHttpAdapter` writes DATA frames into one growing
buffer (requests, and responses of declared length: a streamed response is relayed frame by frame and never held), and `ByteToMessageDecoder` cumulation, which stays small because the HTTP decoder consumes it as it reads.

**What happens at the cap.** An allocation that would pass it throws `OutOfDirectMemoryError`, and the
connection that made the allocation is closed. That is whichever connection needed a buffer next, not
necessarily the one holding the memory. The process keeps serving. The `exceptionCaught` handlers of the
HTTP/1.1 pipeline, `HttpRequestHandler` (also the HTTP/2 stream children), the MCP handler, the
CONNECT/SOCKS relay and the forward client log it at `ERROR` as `direct memory limit
(io.netty.maxDirectMemory) reached - raise it with … - closing connection <channel> - <Netty's message>` (`ExceptionHandling.directMemoryLimitReached`).
What a client sees:

| Where the allocation failed | What the client sees |
|------|------|
| Forward client, collecting the upstream response | `502` for that request (two concurrent 49 MiB forwards at 64 MiB: both `502`, both upstream connections closed) |
| Its own connection, reading a request body | The connection closes mid-upload (the write fails or is reset) |
| Its own connection, writing a response | That response is not sent in full (not measured; pacing keeps each write to a 32 KiB slice) |

Without the cap the same load grows the process until the kernel kills the container. The fix for a
workload that needs more is `-XX:MaxDirectMemorySize` or `-Dio.netty.maxDirectMemory`. Measured at
`--memory=512m`, one core, 50% heap, with 40 clients each sending 6 MiB of an 8 MiB upload and stalling:
see [the evidence below](#direct-memory-evidence).

**Pooled arena count.** Netty sizes its default number of direct arenas as `min(2 × cores,
maxDirectMemory / 24 MiB)`, so a lower limit can mean fewer arenas: 2 at 64 MiB, 4 at 115 MiB, 9 at
230 MiB. On one or two cores this changes nothing; on a 6-core, 2 GiB container (922 MiB heap, 230 MiB
cap) it is 9 arenas rather than 12, still more than the 5 worker event loops that allocate most buffers.

**Not covered.** Native memory that is not a Netty buffer: TLS state inside BoringSSL (`netty-tcnative`),
thread stacks, and the JDK's own temporary direct buffers, which stay under the JVM's
`MaxDirectMemorySize`. Those grow with the number of connections, which is capped only when `maxInboundConnections` is set.

#### Direct-memory evidence

Measured 2026-09-30 on Docker Desktop (Apple silicon): `eclipse-temurin:25-jdk`, one CPU,
`-XX:+UseZGC -XX:MaxRAMPercentage=50`, the jar-with-dependencies built before and after the change,
`maxLoggedBodyBytes=4096` so logged bodies do not confound the heap. Direct memory is NMT's `Other`
category; `anon` is the container's unreclaimable memory from `memory.stat`. Two loads, each held 30 s:
40 clients requesting an 8 MiB body and not reading (download), and 40 clients sending 6 MiB of an 8 MiB
upload and stalling (upload).

| Load | Limit | Before: direct / anon | After: direct / anon | After: outcome |
|------|-------|-----------------------|----------------------|----------------|
| Download | 512 MiB | killed by the kernel (exit 137) | 20 / 193 MiB | all 40 bodies delivered intact |
| Download | 1 GiB | 340 / 521 MiB | 20 / 203 MiB | all 40 bodies delivered intact |
| Upload | 512 MiB | 256 / 409 MiB (container at 507–511 of 512 MiB) | 64 / 239 MiB | 31 of 40 connections closed at the cap |
| Upload | 1 GiB | 260 / 431 MiB | 128 / 298 MiB | 20 of 40 connections closed at the cap |

Before, the upload at 512 MiB survived only because the heap was nearly empty (86 MiB committed); with a
full heap the same direct growth would not fit. The download direct figure after the change (20 MiB) is
the pooled chunks left from loading the 11 MiB expectation, not per-connection buffering.

#### Decompressed bodies

**A compressed request, or an aggregated upstream response, cannot make MockServer allocate more than its
body-size limit for it, plus one decoder buffer. A streamed upstream response cannot make MockServer hold
more than the same limit of decoded bytes not yet written to the client: past it the stream is aborted.**

| Body | Decompressor | Total bounded by |
|---|---|---|
| Request, every inbound protocol (HTTP/1.1, HTTP/2, HTTP/3) and the relay's streaming scan | `MockServerHttpContentDecompressor` | `maxRequestBodySize` (`413`) |
| Forwarded response, HTTP/1.1 and HTTP/2 (`HttpClientInitializer`, `Http2ForwardStreamChildInitializer`) | `BoundedZstdHttpContentDecompressor` | `maxResponseBodySize` (`502`) |
| MockServer's own response on the CONNECT relay's loopback, HTTP/1.1 / HTTP/2 (`RelayConnectHandler`) | `BoundedZstdHttpContentDecompressor` / `BoundedZstdDecompressorFrameListener` | `maxRequestBodySize` |
| Streamed response (`text/event-stream`, `application/vnd.amazon.eventstream`, `application/x-ndjson`, or a client that asked to stream) | the forward client's or the relay's, as above | bytes not yet written to the client: `maxResponseBodySize` (forward) or `maxRequestBodySize` (relay), then the stream is aborted |

Both limits are read as at least 1 byte (`Configuration` and `ConfigurationProperties` clamp them), so
every consumer sees the same positive limit. An aggregator refuses every body at a limit of 0 and cannot be
built with a negative one; the bounds on bodies that are not aggregated (`DownstreamProxyRelayHandler`'s
bounded form, the HTTP/3 request cap and its decompressor, `SnappyBlockOrFrameDecoder`) also refuse a
non-empty body at 0, and the relay's streaming scan reads no more than the first decoded piece, so a caller
that passes a raw value instead of the accessor cannot leave one unbounded. Only `StreamingBody` reads zero or less as "no bound", for bodies built
without one.

Each decoder passes its output on in pieces as it produces it, and the aggregator after it refuses the
body once the decompressed size passes the limit: a 256 MiB zstd bomb (8 KiB on the wire) gets `413` over
HTTP/1.1 and HTTP/2 at `-Xmx256m`, and a 1 GiB zstd response bomb forwarded at `-Xmx256m` gets `502` at a
heap peak of 108 MiB.

For `zstd` one piece is at most 64 KiB (`BoundedZstdHttpContentDecompressor.ZSTD_MAX_ALLOCATION`, which all
four decompressors share). Netty's `ZstdDecoder(0)`, which `HttpContentDecompressor`'s and
`DelegatingDecompressorFrameListener`'s defaults use, allocates whatever content size the frame header
declares as one buffer, so before this bound a 17-byte body declaring 1.5 GiB threw `OutOfMemoryError` at
`-Xmx256m`, as a request over HTTP/1.1 and HTTP/2 and as an upstream response proxied through the CLI.
The response decompressors differ from Netty's only for `zstd`: a response's `snappy` is still decoded by
Netty's framing-only `SnappyFrameDecoder`, and the other codings size their buffers from the input they
have received. On requests a raw Snappy block is also accepted, and its declared size is checked against
`maxRequestBodySize` before it is allocated. `zstd` is decoded only where zstd-jni is on the classpath, as
it is in the shaded jar and the images (through `kafka-clients`).

**Streamed responses.** A streamed response is not aggregated, and a decoder decompresses everything one
upstream read delivered before read pacing (auto-read off, a read per completed write) can act: one read
of `zstd` (about 32,000:1, so 2 GiB of zeros is about 64 KiB, one ordinary read) decodes to about 2 GiB,
and of `gzip` (about 1,000:1) to about 64 MiB. Before the bound each decoded piece was copied and queued
for the client at once, so a 256 MiB zstd stream (8 KiB on the wire) exhausted a 256 MiB heap. Now every
decoded piece counts from the moment it is relayed until the client write that carries it completes
(`StreamingBody.addChunk` to `chunkWritten`, including pieces pending before the writer subscribes; in the
CONNECT relay's HTTP/1.1 loopback, `DownstreamProxyRelayHandler` counts the same way, and pauses the
loopback's reads (below); before, that loopback read as fast as MockServer wrote, so a slow proxy client of
a long stream queued all of it). The bound is the
limit the same response would have had aggregated: `maxResponseBodySize` (50 MiB by default) for a
forwarded stream over HTTP/1.1 or HTTP/2, `maxRequestBodySize` (10 MiB) in the relay loopback, which only
decodes MockServer's own responses. When a piece would pass it, MockServer logs a `WARN`, closes the
upstream connection or stream, drops what is queued and ends the client's response without its last chunk
(HTTP/1.1: the connection closes; HTTP/2 and HTTP/3: the stream is reset), so the client sees an
incomplete response rather than a complete short one. The rest of the read that passed the bound is still
decoded, and released piece by piece: the same CPU an aggregated response spends past its limit.

Read demand follows the backlog, so a legitimate stream is not affected: `chunkWritten` requests the next
upstream read only once the unwritten bytes have drained to min(64 KiB, limit / 4) (`subscribe` applies
the same test after draining what arrived before it, so a first read that decodes to more than the
watermark is written before a second is requested), and the relay loopback
stops reading above min(256 KiB, limit / 2) unwritten and resumes at half that (it no longer reads after
every completed write while paused). A slow client therefore holds back the upstream, however many chunks
one read holds, and the backlog passes the limit only when what arrives in one read (one upstream read
forward; the read in progress when the relay paused) decodes to more than the limit less that watermark.
Such a stream, a decompression bomb or an extremely compressible one, is aborted; raise the limit for it.
While MockServer withholds reads the upstream connection is quiet by MockServer's choice, so the stream
idle timeout (`streamIdleTimeoutSeconds`) ignores an idle event while `StreamingBody.isAwaitingClient()`
(more than the watermark waits for the client) and applies only while MockServer is reading. A client that
stops reading therefore keeps its stream, holding at most the limit, until it reads on, disconnects or
trips `responseWriteStallTimeoutMillis`, as a client of an aggregated response keeps up to its limit; write
completions were rejected as the idle timeout's progress signal because TCP frees a slow reader's send buffer
in bursts that can be further apart than the timeout.
Only a subscribed body can be awaiting its client: a streamed response that is replaced or dropped before
it is written (a chaos error, rate limit or quota, a forward fallback) has nothing to take
its bytes, so its upstream is reclaimed by the idle timeout as before, and a breakpoint `CLOSE` closes the
upstream at once (`StreamingBody.closeUpstream()`). The bound abort, the idle timeout and an upstream that
closes, fails or sends invalid framing mid-stream all end the client's response without its terminating
chunk; a body that the upstream delimits by closing its connection still ends normally. When the client
has gone, the writer closes the upstream instead of reading the rest of the stream into nothing.
`responseWriteStallTimeoutMillis` (default 60 s) reclaims a client that stops reading, for streamed and
aggregated responses alike: it ends the response incomplete, and the writer's close listener closes a streamed
response's upstream — see [netty-pipeline.md](netty-pipeline.md#response-write-stall-timeout).

A per-read decode limit was rejected. Netty's `ZstdDecoder` decodes a whole cumulation in one `decode`
call with no way to pause it (its removal is deferred until the call returns), and feeding the decoder
smaller slices does not bound its output, because one zstd block of a few bytes decodes to 128 KiB (a
1 KiB slice can decode to about 32 MiB). A demand-driven decode stage would mean holding compressed input
across reads on every streamed response; the queue bound is one counter per stream on the path that
already exists.

**Not covered.** A zstd decoder's window is native memory allocated by zstd-jni, outside the heap and the
direct-memory cap; Netty accepts windows up to 128 MiB (`Window_Log` 27). The streamed bound is per stream,
as an aggregated response's limit is per response, so concurrent streams each hold up to it.

#### Chunk-size lines and trailers

**A chunked HTTP/1.1 request cannot make the decoder buffer more than about 8 KiB of chunk framing.** A chunk-size line with its chunk extensions, or the trailer section, is rejected with `400` and the connection closed once more than 8,192 bytes of it are waiting to be decoded; the decoder holds at most that plus the socket reads either side. Before, both were bounded only by `maxInitialLineLength` and `maxHeaderSize`, which defaulted to `Integer.MAX_VALUE`. The mechanism and its exact edges are in [netty-pipeline.md → Chunk-size line limit](netty-pipeline.md#chunk-size-line-limit).

**The request line and the header section are bounded by default.** `maxInitialLineLength` defaults to 64 KiB and `maxHeaderSize` to 256 KiB (they were `Integer.MAX_VALUE`, so a client that never ended either was held without limit); an HTTP/1.1 request over either is refused with `414` or `431` and the connection closed. `maxHeaderSize` is also the header list limit of HTTP/2 and HTTP/3 (they were limited to Netty's 8,192 bytes whatever it was set to), counted after HPACK or QPACK decoding: each of an HTTP/2 connection's 100 streams can hold up to `maxHeaderSize` of decoded headers, 25 MiB at the default against the 1,000 MiB of bodies the same streams can hold (the size as the protocol counts it, not a heap bound: many tiny fields cost a few times their counted size in heap), and a header block that decodes far past the limit is refused without its fields being kept. See [netty-pipeline.md → Request line and header limits](netty-pipeline.md#request-line-and-header-limits).

**An upstream's response headers are bounded by the same property.** The forward client reads a response's headers and trailers up to `maxHeaderSize` (it read 8,192 bytes whatever the property was set to), so each upstream connection can hold one response's decoded headers and trailers of up to that size, and over HTTP/2 an encoded header block of up to a quarter more; a legacy HTTP/2 upstream can open one stream of its own, which doubles it. A header block that decodes far past the limit is refused without its fields being kept, as for requests. The WebSocket proxy relay reads an upstream's handshake response headers up to the same limit, one response per relayed connection. The number of upstream connections follows the forwards in flight plus the pool's idle connections. See [netty-pipeline.md → Upstream response headers](netty-pipeline.md#upstream-response-headers).

### Connection Memory

Every open client connection costs memory whether or not it carries traffic: kernel socket buffers (about 3.9 KiB per idle connection measured in a 512 MiB container, charged to the container's memory cgroup but outside the JVM heap) plus the channel, its pipeline and per-connection state on the heap. None of the heap-derived limits above bound it, so many idle keep-alive connections can push a container towards its memory limit on their own. Two properties bound it: `inboundConnectionIdleTimeoutMillis` (default 5 minutes) closes connections that are idle with nothing in progress, and `maxInboundConnections` (default off) caps how many are held at once; `mock_server_inbound_connections_open` shows the live count. See [netty-pipeline.md → Inbound Connection Bounds](netty-pipeline.md#inbound-connection-bounds).

#### Pipelined (held) HTTP/1.1 requests

**A pipelining HTTP/1.1 client can make one connection hold more than one request at once, bounded by cost, count and time, never by response bytes.** `Http1PipeliningGate` ([netty-pipeline.md → HTTP/1.1 pipelining gate](netty-pipeline.md#http11-pipelining-gate)) holds a request decoded while another is outstanding, so it can answer them in order; no response is ever buffered, only requests still waiting for their turn.

- **Cost** (checked on every held object): 128 bytes per held object (a held content object, its derived slice and its deque slot) plus the capacity of its unwrapped read buffer, charged once per run of consecutive held objects that share the same buffer (a buffer that reappears after another one in between is charged again). The effective limit is `maxRequestBodySize` (default 10 MiB); past it, reads pause (closing: the newest incomplete held request and everything after it is dropped instead).
- **Count:** 1,024 held requests; a 1,025th held request caps the connection — it and anything later are released unprocessed, and the 1,024 already held still drain in order, up to 1,025 answered requests total, before the connection closes.
- **Time:** the wedge backstop closes a connection whose held requests see no response progress for `inboundConnectionIdleTimeoutMillis` (reused, not a separate property); with it at `0` the connection is never closed for this reason, but is still logged once at `WARN` after 300 s.

`heldCostLimit` is a single aggregate budget over everything currently held, not a per-request allowance: `heldCost` only grows as objects are held and is reset to zero once `held` empties, so the 1,024-request count is reachable only when each held request's own share of the cost is small. So the worst case per pipelining connection is about `maxRequestBodySize` (default 10 MiB) of heap and pinned direct memory, plus at most one more socket read's worth of buffers already in hand when the limit is crossed (the pause takes effect from the next read), not 1,024 times that: the 128 B of per-object overhead is already inside that same budget, not added on top of it. This is on top of the one outstanding request any connection can hold (bounded by `maxRequestBodySize` on its own account) and is additional to, not a replacement for, the per-connection bounds above: `maxInboundConnections` still caps how many connections can pipeline at once.

### Timing Sensitivity

`heapAvailableInKB()` derives its budget from the heap **ceiling** (`-Xmx`), which is fixed for the JVM's lifetime, so the computed default does **not** depend on when the property is first read or on allocation history.

This was previously a real defect. The budget used to be `(maxHeap − usedHeap)`, i.e. the *momentary free heap* at first read. Because the resolved default is cached JVM-wide with no reset path, whatever the free heap looked like at that first read froze the capacity for **every** store in the JVM. A test suite whose first MockServer start happened after a heavy fixture silently got a small store for every instance — for example, at `-Xmx1g` holding ~645 MB before the first read drove the frozen `maxLogEntries` down from 100,000 (the cap at the time) to ~45,957, and a later allocation could not raise it. Under-sizing the log is not merely a memory concern: the ring overwrites silently, so a later `verify` can stop finding what it should. Sizing off the ceiling removes this dependence on allocation ordering entirely.

Note the change moves the basis from "size to currently-free heap" to "size to the heap the JVM is allowed" — the default now over-provisions slightly relative to free memory on a heavily-used small heap, deterministically. Deployments that need a smaller store set `mockserver.maxLogEntries` / `mockserver.maxExpectations` explicitly (which is unaffected by this change and always wins).

### How the Heap Ceiling Is Read

`heapAvailableInKB()` takes the ceiling from the **whole-heap** figures the JVM reports:
`ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getMax()` and `Runtime.maxMemory()`. When both
are defined it uses the smaller. On HotSpot both return the collector's own `max_capacity()`, so they
are equal. The "smaller wins" rule is there so that a source that over-reports cannot inflate the defaults.

It must **never sum the heap memory pools** (`MemoryPoolMXBean`), because pools can overlap. Generational
ZGC (JDK 21 with `+ZGenerational`, and plain `+UseZGC` on JDK 23+) reports the full `-Xmx` as the max of
**both** its young and old pools. Summing the pools therefore doubled the ceiling and every default
derived from it. The Docker images default to ZGC on JDK 25+, so they were affected. So was any user who
ran generational ZGC themselves. The same rule applies to the heap columns of the memory-usage CSV
(`MemoryMonitoring`): `heapMaxAllowed` / `heapCommitted` / `heapUsed` / `heapInitialAllocation` come from
the `MemoryMXBean`. Only the non-heap columns still sum pools.

Measured at `-Xmx1g` (heap ceiling in bytes; eclipse-temurin 21/25 in Docker and a local JDK 17):

| Collector | Summed pool max (old basis) | Whole-heap max (current basis) | `heapAvailableInKB` old → new |
|-----------|----------------------------|--------------------------------|-------------------------------|
| G1 | 1,073,741,822 (Eden/Survivor report `-1`) | 1,073,741,824 | 1,028,095 → 1,028,096 |
| ZGC, non-generational (JDK 17/21) | 1,073,741,824 | 1,073,741,824 | unchanged |
| ZGC, generational (JDK 21 `+ZGenerational`, JDK 25) | **2,147,483,648** | 1,073,741,824 | **2,076,672 → 1,028,096** |
| Shenandoah | 1,073,741,824 | 1,073,741,824 | unchanged |
| Serial | 1,037,959,168 | 1,037,959,168 | unchanged |
| Parallel | 1,048,576,000–1,068,498,944 (depends on CPU count) | 954,728,448 | ~1,003,520–1,022,976 → 911,872 |

Under Parallel the pool sum counts one survivor space that the collector's own ceiling excludes, so the
Parallel defaults drop by about 9–11%. This is a correction in the conservative direction.

### Undefined Heap Max (JMX `getMax()` = -1)

The JMX spec allows `MemoryUsage.getMax()` to return **-1 (undefined)**. In some environments the JMX heap max reports as `-1`/`0` — verified on a **GraalVM native image** of the shaded jar, and possible in exotic JVM/WAR setups. Left unguarded, `max / 1024 - 20480` would then be a large **negative** number, driving `maxExpectations`/`maxLogEntries` to `<= 0` — so the expectation store and log ring buffer silently drop everything (`PUT /mockserver/expectation` returns 201 but the expectation never matches; "Log event ring buffer full" at startup).

`heapAvailableInKB()` is therefore robust to an undefined heap max (see `computeHeapAvailableInKB(...)`):

1. When the JMX heap max is `<= 0`, it falls back to `Runtime.maxMemory()` for the ceiling (and vice versa). (`Runtime.maxMemory()` may be `Long.MAX_VALUE` when the heap is unbounded — the result stays non-negative and the very large value is clamped by the `min(..., cap)` in the callers.)
2. When neither JMX nor `Runtime` yields a usable ceiling, it returns `0`.
3. The result is **floored at 0** — never negative.

The getters then apply a lower bound so a `0` heap-derived value still yields a functional store (see `heapBasedDefaultOrFloor(...)`): when `min(heapAvailableInKB / perEntryKB, cap)` computes to `<= 0`, the default falls back to the **dev-mode default** (1,000) for that property rather than `0`. Explicitly setting `mockserver.maxExpectations` / `mockserver.maxLogEntries` always overrides this.

## Log Entry Memory Analysis

### LogEntry Object Graph

Each `LogEntry` holds references to the HTTP request, response, expectation, and formatting data associated with an event.

```mermaid
graph LR
    LE["LogEntry
    ~112 B shell"]
    LE --> ID["id: String
    UUID, ~112 B"]
    LE --> CID["correlationId: String
    UUID, ~112 B"]
    LE --> TS["timestamp: String
    lazy, ~86 B"]
    LE --> MF["messageFormat: String
    ~78 B"]
    LE --> ARGS["arguments: Object array
    shallow clones of req/resp"]
    LE --> REQ["httpRequests: RequestDefinition[]"]
    LE --> RESP[httpResponse: HttpResponse]
    LE --> EXP[expectation: Expectation]

    REQ --> HR["HttpRequest
    ~116 B shell"]
    HR --> METHOD["method: NottableString
    ~176 B"]
    HR --> PATH["path: NottableString
    ~200 B"]
    HR --> HDRS["headers: Headers
    ~3 KB for 6 headers"]
    HR --> BODY["body: Body
    variable"]
    HR --> SA["socketAddress
    ~116 B"]

    RESP --> SC[statusCode: Integer]
    RESP --> RP[reasonPhrase: String]
    RESP --> RHDRS[headers: Headers]
    RESP --> RBODY[body: BodyWithContentType]
```

### Field-Level Size Estimates

#### LogEntry Shell (~112 bytes)

| Field | Type | Bytes | Notes |
|-------|------|-------|-------|
| Object header | — | 16 | |
| `hashCode` | `int` | 4 | |
| `id` | `String` ref | 4 | UUID string allocated separately (~112 B) |
| `correlationId` | `String` ref | 4 | UUID string (~112 B) |
| `port` | `Integer` ref | 4 | Boxed int (16 B when non-null) |
| `logLevel` | `Level` ref | 4 | Enum singleton |
| `alwaysLog` | `boolean` | 1 | |
| `epochTime` | `long` | 8 | |
| `timestamp` | `String` ref | 4 | Lazy, ~86 B when materialised |
| `type` | `LogMessageType` ref | 4 | Enum singleton |
| `httpRequests` | `RequestDefinition[]` ref | 4 | |
| `httpUpdatedRequests` | `RequestDefinition[]` ref | 4 | Lazy shallow clone |
| `httpResponse` | `HttpResponse` ref | 4 | |
| `httpUpdatedResponse` | `HttpResponse` ref | 4 | Lazy shallow clone |
| `httpError` | `HttpError` ref | 4 | Usually null |
| `expectation` | `Expectation` ref | 4 | |
| `expectationId` | `String` ref | 4 | UUID (~112 B) |
| `throwable` | `Throwable` ref | 4 | Usually null |
| `consumer` | `Runnable` ref | 4 | Always null in stored entries |
| `deleted` | `boolean` | 1 | |
| `messageFormat` | `String` ref | 4 | ~78 B |
| `message` | `String` ref | 4 | Lazy |
| `arguments` | `Object[]` ref | 4 | |
| `because` | `String` ref | 4 | Usually null |
| *(padding)* | — | ~4 | Alignment |

#### HttpRequest (~3.5-5 KB typical)

| Component | Typical Size | Notes |
|-----------|-------------|-------|
| HttpRequest shell (19 fields) | ~116 B | Inherits from `Not` → `ObjectWithJsonToString`; includes 8 B for the two transient references `markBodyAsReceived()` sets (null unless the request arrived with a `Content-Encoding`) |
| `method` (NottableString) | ~176 B | e.g., "GET" — NottableString + value String + json String |
| `path` (NottableString) | ~200 B | e.g., "/api/users" |
| `headers` (Headers + Guava LinkedHashMultimap) | ~3,000 B | 6 typical headers (Host, Content-Type, Accept, User-Agent, Content-Length, Connection) |
| `body` (StringBody, if present) | 0-2,000 B | Null for GET; ~630 B for 100-char JSON; ~1,800 B for 500-char JSON |
| `socketAddress` | ~116 B | host String + port Integer + scheme enum |
| `localAddress` / `remoteAddress` | ~140 B | Two short strings |
| `remoteAddress` holder, once any `clientIp` is used | +24 B, +~56 B, +24-32 B | Before the JVM-wide `clientIp` latch: nothing beyond the string. After it: a 24 B `HttpRequest.RemoteAddress` shared by the connection's requests, which keeps the peer `InetAddress` alive (~56 B IPv4, more for IPv6) and, after the first comparison, its bytes (24 B IPv4, 32 B IPv6). Shared under keep-alive; with one request per connection about 100 B (IPv4) to 170 B (IPv6) per retained entry, bounded by `maxLogEntries`. A request mapped before the latch gains a ~48 B holder plus bytes the first time it is compared |
| `keepAlive` / `secure` | ~32 B | Two boxed Booleans |
| **Total (GET, no body)** | **~3,800 B** | |
| **Total (POST, 200-char body)** | **~4,700 B** | |

#### NottableString (~176-284 bytes each)

Each `NottableString` wraps a value with optional negation and regex support:

| Component | Bytes |
|-----------|-------|
| Object header + 5 fields | ~48 B |
| `value` String (short, e.g., 3-15 chars) | ~64-100 B |
| `json` String (same content) | ~64-100 B |
| **Total (short value like "GET")** | **~176 B** |
| **Total (medium value like "application/json")** | **~250 B** |

#### Headers (~500 bytes per header pair)

Each header is a key-value pair of `NottableString` objects stored in a Guava `LinkedHashMultimap`:

| Component | Per-Header Bytes |
|-----------|-----------------|
| Key NottableString | ~176 B |
| Value NottableString | ~240 B |
| Multimap entry overhead | ~64 B |
| **Total per header** | **~480 B** |

The `Headers` object itself adds ~232 B of overhead (shell + multimap base structure).

#### HttpResponse (~3.5 KB typical)

| Component | Typical Size | Notes |
|-----------|-------------|-------|
| HttpResponse shell (8 fields) | ~76 B | Inherits from `Action` |
| `statusCode` (Integer) | ~16 B | Boxed int |
| `reasonPhrase` (String) | ~44 B | e.g., "OK" |
| `body` (StringBody) | ~630-1,800 B | 100-500 char JSON body |
| `headers` (Headers) | ~2,600 B | 5 typical response headers |
| **Total (200-char JSON body)** | **~3,400 B** | |

#### StringBody (~630 bytes for 100-char body)

| Component | Bytes |
|-----------|-------|
| StringBody shell (inherits 4 levels) | ~72 B |
| `value` String | ~240 B (100 chars × 2 bytes + 40 B overhead) |
| `rawBytes` byte array | ~116 B (100 bytes + 16 B header) |
| `contentType` MediaType | ~200 B |
| **Total (100-char body)** | **~630 B** |
| **Total (500-char body)** | **~1,800 B** |
| **Total (2 KB body)** | **~4,700 B** |

### Per Log Entry Type Estimates

Each log entry type populates a different subset of fields. These estimates assume a typical HTTP request with 6 headers and a small-to-medium JSON body.

| Log Entry Type | Typical Memory | What It Stores |
|---------------|----------------|----------------|
| `RECEIVED_REQUEST` (GET) | **~4.2 KB** | LogEntry shell + strings + HttpRequest |
| `RECEIVED_REQUEST` (POST, 200-char body) | **~5.2 KB** | As above + request body |
| `EXPECTATION_MATCHED` | **~7.8 KB** | LogEntry + HttpRequest + Expectation ref (shared, not copied) |
| `EXPECTATION_RESPONSE` | **~7.9 KB** | LogEntry + HttpRequest + HttpResponse |
| `FORWARDED_REQUEST` | **~10.2 KB** | LogEntry + HttpRequest + HttpResponse + Expectation wrapper |
| `NO_MATCH_RESPONSE` | **~6.5 KB** | LogEntry + HttpRequest + 404 response |
| `CREATED_EXPECTATION` | **~4.9 KB** | LogEntry + expectation definition |
| `INFO` / `WARN` / `ERROR` | **~0.5 KB** | LogEntry shell + message format + arguments |

### Per HTTP Transaction Memory

Each inbound HTTP request generates **2-3 log entries**:

| Scenario | Log Entries Created | Total Memory |
|----------|-------------------|-------------|
| GET matched to expectation | RECEIVED_REQUEST + EXPECTATION_MATCHED + EXPECTATION_RESPONSE | ~20 KB |
| POST matched to expectation | RECEIVED_REQUEST + EXPECTATION_MATCHED + EXPECTATION_RESPONSE | ~22 KB |
| Proxied/forwarded request | RECEIVED_REQUEST + FORWARDED_REQUEST | ~14 KB |
| No match (404) | RECEIVED_REQUEST + NO_MATCH_RESPONSE | ~11 KB |

### Arguments Array and Shallow Clones

The `arguments` field in `LogEntry` stores the objects used for message formatting **as references**, not rendered copies. An `HttpRequest`/`HttpResponse` argument is the same object the entry already holds in `httpRequests`/`httpResponse`; its body is converted to a `LogEntryBody` (a shallow clone sharing headers, cookies and parameters) only at render time, in `getArguments()`, and the clone is discarded after use. An argument that is itself derived from the entry's request, such as the curl command on a `FORWARDED_REQUEST` entry, is stored as a `DeferredLogArgument` and rendered at the same points, so the arguments array adds only a few small objects per entry.

### Header sharing across a connection's requests

**Outcome: a retained request no longer holds its own copy of a header it repeats from the previous request on the same connection.** With a typical API client over HTTP/1.1 (ten headers, eight repeated on every request) a bodiless request retains **3,744 → 1,953 bytes (−48%)**; with a curl-like client (three headers) **2,000 → 1,536 bytes (−23%)**; over HTTP/2 **2,921 → 2,096 bytes (−28%)**. `-XX:+UseStringDeduplication` was measured as the no-code alternative and recovers only about a third as much over HTTP/1.1 and almost nothing over HTTP/2, so it is not a default.

| Retained per bodiless request (ZGC, `WARN`) | HTTP/1.1, 10 headers (8 repeated) | HTTP/1.1, 3 headers | HTTP/2, 10 headers | HTTP/2, 3 headers |
|---|---|---|---|---|
| Before | 3,744 B | 2,000 B | 2,921 B | 1,952 B |
| `-XX:+UseStringDeduplication` | 3,064 B (−18%) | 1,856 B (−7%) | 2,870 B (−2%) | not run |
| Header sharing | 1,953 B (−48%) | 1,536 B (−23%) | 2,096 B (−28%) | 1,617 B (−17%) |
| Both | 1,921 B (−49%) | 1,504 B (−25%) | not run | not run |

HTTP/2 starts lower and gains less because netty's `AsciiString` caches its `toString()`, so a header the client's HPACK encoder indexed already reached the model as one shared `String`; only the `NottableString` wrapper was per request. That is also why deduplication does almost nothing for HTTP/2.

**How it works.** `FullHttpRequestToMockServerHttpRequest` keeps the header name and value wrappers (`NottableString`) of the previous request it mapped. When the header at position *i* has the same name, or the same value, as position *i* of that request (an exact, case-sensitive character comparison), the earlier wrapper is reused instead of a new wrapper, `String` and `byte[]`. What netty hands the mapper depends on the protocol: netty's HTTP/1.1 decoder produces `String` values and `AsciiString` names — a shared constant for `Host`, `Accept`, `Connection`, `Content-Type` and `Content-Length` spelled exactly so, otherwise a fresh instance per request — and HTTP/2 produces `AsciiString` names and values, the same instance on every request for a header the client's HPACK encoder indexed. So the memo also keeps each received `AsciiString` and compares it with `AsciiString.equals`, which returns at once on the same instance and otherwise compares length, cached hash and then the bytes in bulk, rather than char by char; a `String` is compared with `String.equals`. Some HTTP/2 clients — Go's, and so gRPC-Go and k6 — send their headers in a different order on every request, so on an HTTP/2 request, when the position does not match, an `AsciiString` is also looked up by identity among all of the previous request's headers: the same HPACK instance is the same text. Anything that differs, including a case-only difference, gets its own wrapper exactly as before, so header order, case, multiplicity and every retrieve format are unchanged.

- **Why it is safe to share.** `NottableString` is immutable (its only non-final fields are lazily compiled regex patterns, published through `volatile`), and every derived form (`withStyle`, `withSchemaType`, …) returns a copy. The `Headers` container is never shared; only the wrappers inside it are.
- **Thread confinement.** One mapper exists per HTTP/1.1 channel and per HTTP/2 connection, and all of a connection's streams run on its event loop (see [netty-pipeline.md](netty-pipeline.md)), so the memo is only touched by one thread and needs no synchronisation.
- **Bound.** The memo covers the first 64 header positions and holds only the most recent request; slots a shorter request did not use are cleared. It is double-buffered — the previous request is read while the current one is recorded, then the two swap and the buffer swapped out is emptied — so a reordered header cannot find a slot the current request has already overwritten, and the request before last is never held. Each open connection therefore keeps up to eight small arrays (names and values, previous and current, wrapper and received `AsciiString`), sized to its largest request up to 64 headers, and keeps its last request's header text alive after the event log has evicted it. The arrays are allocated on first use — the current pair when the first request is mapped, the other pair on the second — so a mapper that maps one request (a connection closed after one request, or the per-call mapper of the early-response path) allocates four of them, not eight.
- **The byte weigher over-counts shared headers.** `LogEntry.estimatedHeapSize()` still charges `HEADER_ENTRY_OVERHEAD_BYTES` plus the characters for every header value, so an entry whose repeated headers are shared is weighed above what it newly retains. A given `maxEventLogSizeInBytes` therefore still holds the same number of entries as before, but they now occupy less heap: in the runs below, real retained heap per counted byte fell from 0.73× to 0.38× over HTTP/1.1 and from 0.54× to 0.39× over HTTP/2 with ten headers, and from 0.86× to 0.66× and 0.75× to 0.62× with three. So for header-heavy bodiless keep-alive traffic the log reaches its budget, and starts evicting, while holding about 40% of the budget in real heap — about twice as early as before, and about 2.6 times as early as the memory now requires. Its 0.38× is below every `WARN` multiple of the body-carrying profiles in [Re-measured multiples](#re-measured-multiples-2026-09-28) (0.50–0.80×). That is the safe direction: it evicts earlier than necessary, never later. Traffic with sizeable bodies is barely affected, because the body dominates both the weight and the heap.
- **Why not `-XX:+UseStringDeduplication`.** It deduplicates only the `byte[]` behind equal `String`s, not the `String` or `NottableString` objects around it, and only once a string has survived long enough to be a candidate. It is also a JVM flag, so it cannot help the many users who run the jar with their own options. With sharing in place it adds only ~30 bytes per request.

**Method.** Each variant started a fresh server (JDK 25, `-XX:+UseZGC -Xmx512m -Xms512m`, `logLevel=WARN`, `maxLogEntries=400000`, `maxEventLogSizeInBytes=0` so nothing is evicted), created one expectation, warmed up with 5,000 requests and reset. k6 then sent 40,000 matched GET requests over 8 keep-alive connections, each with `Host`, k6's `User-Agent` and the profile's headers (the 10-header profile adds `Accept`, `Accept-Encoding`, `Accept-Language`, a ~250-character `Authorization` bearer token, `X-Client-Version`, `X-Tenant-Id`, and a per-request `traceparent` and `X-Request-Id`). The HTTP/2 runs used TLS with ALPN `h2` (k6's Go client, which orders headers differently on every request, so they also exercise the identity lookup). After forced GCs, `jcmd GC.class_histogram` was taken with the log full (H1) and after `PUT /mockserver/reset` (H2); the table shows (H1 − H2) ÷ 40,000. Repeat runs reproduced each figure to the byte.

**Cost.** In `InboundHeaderSharingBenchmark` (one mapper, ten headers; on demand only, not part of the per-merge allocation gate) the decode allocates **1,976 → 1,496 bytes** per request when eight headers repeat and **1,984 → 1,824 bytes** when every value changes (the names are still shared); `InboundDecodeBenchmark` allocates 40 bytes less per request at 16 KB and 256 KB bodies (its 1 KB baseline is bimodal, so no change is claimed there). The mapper itself does slightly more work, because it compares each header with the previous request's: in three interleaved runs its time per request was unchanged over HTTP/2 when eight headers repeat, about 40–60 ns (9–13%) slower over HTTP/1.1, and 50–130 ns slower in the worst case, where every one of the ten values changes but shares a long prefix with the one before. Headers that change position cost more (`values=reordered`, the order reversed on every other request): about 80 ns per request with ten headers and 0.8 µs with 64 over HTTP/1.1, where only the positional comparison runs, and about 140 ns with ten and 2.1 µs with 64 over HTTP/2, where each miss also scans the previous request's headers by identity — against 0.45–0.5 µs and 3.6–5.4 µs for the whole mapping before. The scan is bounded by the previous request's header count, so it is quadratic only up to 64 headers, and it runs only for HTTP/2: over HTTP/1.1 the only repeated `AsciiString` instances are the five decoder constants, which are already shared, and values are `String`s, so the scan could never find anything there. End to end that does not show: a 40-second sustained k6 load (10-header profile, three interleaved runs of each) served 35,239 req/s at 51.0 µs of server CPU per request before and 35,805 req/s at 49.7 µs after, within run-to-run noise. The same method found no measurable throughput or CPU cost for `-XX:+UseStringDeduplication` either.

### Byte-Budget Eviction (`maxEventLogSizeInBytes`)

The `CircularConcurrentLinkedDeque` supports a second, independent bound: a **body-byte budget** supplied via the `maxEventLogSizeInBytes` configuration property. It is **on by default**, derived from the heap ceiling with a log-level-aware divisor (`(heapAvailableInKB / 20) * 1024` at `INFO`/`WARN`/`ERROR`/`OFF`; `(heapAvailableInKB / 12) * 1024` at `DEBUG`/`TRACE`); it is `0` (disabled) only when the heap ceiling is undefined (e.g. a GraalVM native image), and can be set to `0` explicitly to bound the log by entry count alone. The fractional budget leaves headroom for the expectation store, in-flight Netty buffers and JVM overhead while still bounding the body memory the count cap cannot see.

### Why it exists

`maxLogEntries` caps the *number* of log entries, not their *size*. When each entry holds a large body — for example, an LLM exchange with a multi-hundred-kilobyte tool schema or conversation context — a few thousand entries can exhaust the heap even though the count is low. The byte budget provides a size-based safety valve that the count bound cannot express.

### How it works

On construction, `MockServerEventLog` passes `maxEventLogSizeInBytes` and `LogEntry::estimatedHeapSize` (the weigher) to the 4-argument `CircularConcurrentLinkedDeque` constructor:

```java
this.eventLog = new CircularConcurrentLinkedDeque<>(
    configuration.maxLogEntries(),
    configuration.maxEventLogSizeInBytes(),
    LogEntry::estimatedHeapSize,
    LogEntry::clear);
```

On every `add()`, `evictExcessElements(weight)` runs two passes:
1. **Count pass** — evict oldest until `count < maxSize` (existing count bound).
2. **Byte pass** — when `maxBytes > 0` and a weigher is set, evict oldest until `totalBytes + incomingWeight <= maxBytes`.

A running `AtomicLong totalBytes` tracks the sum of all retained entry weights, updated on every insert and eviction. A single element whose weight alone exceeds `maxBytes` is still admitted — the byte loop stops when the deque is empty rather than rejecting the incoming entry.

### `LogEntry.estimatedHeapSize()`

The weigher used by the byte budget. Returns a **materially honest estimate** — not a precise object-graph walk — of the bytes this entry retains on the heap, counting the terms that actually dominate a retained entry rather than raw body bytes alone:

```java
public long estimatedHeapSize() {
    // per HTTP message (each httpRequests[*] and the httpResponse):
    //   getBodyAsRawBytes().length
    //   + header name/value chars + ~64 B per header value
    //   + ~1.15 KB structural overhead (model object, method/path/status, Body/Headers wrappers)
    // + ~0.9 KB per-entry overhead (LogEntry graph, id/timestamp strings, arrays, deque node)
    //   charged only when the entry carries at least one HTTP message
}
```

The value is computed lazily and cached (`-1` until first call) so the weight is identical at add-time and at evict-time. All counted terms are stable once the entry is built (bodies, headers and the fixed overheads do not change), so the value never changes after first computation — the deque relies on add-time weight matching evict-time weight exactly. It deliberately excludes the lazily-derived `httpUpdated*` copies and the rendered `arguments` (both transient, rebuilt at render time), and — importantly — the memoized `message` string (see below). A **pure diagnostic entry with no request/response** retains almost none of this graph and stays weightless, so the ring in-flight bound keeps ignoring the overwhelming majority of small control entries.

**A retained entry must not regain an uncounted copy after it is weighed.** The weight is memoised when the entry enters the deque, after `releaseDerivedForms()` has dropped each text body's decoded `String`. Two paths used to re-attach a copy afterwards, and both are closed:

- **The curl argument.** A `FORWARDED_REQUEST` entry rendered its curl command eagerly into a `String` argument, which copied the request body. It is now a `DeferredLogArgument` that renders from the request the entry already retains, wherever the `String` used to appear (`getArguments`, `getMessage`, `getCompactMessage`), so log text, retrieve output and the dashboard are unchanged. `equals` compares the rendered value; `hashCode` leaves the arguments out, so hashing never renders one.
- **The post-log response write.** A response is logged before it is written, so the consumer can retain and release its entry first. The write (`BodyDecoderEncoder.bodyToBytes`) then decoded and cached the body's `String` again, on the retained response. Readers that can run after retention now read through `Body.getValueWithoutCaching()` / `toStringWithoutCaching()`, which return a cached `String` if present and otherwise decode without caching: the response write, `HttpRequestToCurlSerializer`, and `LogEntry.updateBody` (retrieve and dashboard rendering).

Other readers of retained bodies still read through `getValue()` and re-attach the decoded `String` to the entries they touch — body matching during a `verify` or `retrieve` whose request matcher has a body, and the model serializers behind a JSON `retrieve` of requests or expectations. That copy is not counted, and it stays until the entry is evicted.

The dashboard keeps, outside the entries, the cut text (64K characters at most) of each retained body it has shown cut, so it decodes a large body once instead of on every update (`DashboardBodyCap.CUT_BODIES`, see [dashboard-ui.md](dashboard-ui.md)). That is not counted either; it holds at most 16M characters in all, its keys are weak (a cut goes once nothing holds its body: when its entry is evicted, or later if a live expectation still holds the same response body) and its values soft (the JVM clears them before running out of heap). With `redactSecretsInLog` on, each cut is the redacted one, and `fixtureBodyRedactFields` adds a second such cache in core: the credential values found in each body of 64K characters or more (`FixtureRedactor.KEPT_BODY_VALUES`, at most 4M characters, weak keys, soft values), so free-text scrubbing does not parse every large body on every render.

The constants were calibrated against a HotSpot **live** (retained-only) heap dump of a filled event log, read with `HprofHisto`. At a non-rendering level this brings the estimate to **within a few percent of the real retained heap** — measured at **~1.0×** the weigher total at `WARN` on 20,000 retained ~1 KB-body entries **for a body retained once** (was ~2.6× before the weigher counted structural overhead). **That figure does not generalise, and a 2026-09-19 re-measurement replaced it as the sizing basis** — see the multiples below: a decoded JSON/XML/text body is retained TWICE (the decoded `String` and the raw `byte[]`, both held by `JsonBody`/`XmlBody`/`StringBody`) and counted once, so the real multiple is ~2.0× at `WARN` for the text workloads a byte budget exists to bound. ~1.0× is the binary-body case.

**Why rendering levels were sized apart.** The one large term the weigher does *not* count is the formatted `message`. At `INFO`/`DEBUG`/`TRACE`, `MockServerEventLog.processLogEntry()` calls `writeToSystemOut()`, which calls `entry.getMessage()` and stores the formatted string on the entry for its whole life in the deque; for a JSON-body workload that message embeds the request body as text, so it is roughly 1.5× the raw body bytes. The weigher cannot count it: it is materialized *after* the weight is first computed and memoized, and it is present only at rendering levels — counting it would either double-count against the level-aware divisor or make the weight depend on when the entry happened to be rendered, breaking the add==evict invariant. So per **counted** byte, a rendering-level entry retains more real heap. Re-measured 2026-09-19 on live heaps (`jmap -histo:live`, marginal cost per body byte): **3.02–3.04×** at `INFO` against **2.01–2.02×** at `WARN` — the text body retained twice, plus the message embedding it a third time. That 1.5× asymmetry was the basis of the earlier `/12` against `/8` pairing. The in-flight divisors were then set to `/12` against `/7` from the 2026-09-28 measurements below, which count the ring backlog as well as the retained entries (see [Validation at the current divisors](#validation-at-the-current-divisors)). Now that entries quoting a request or response no longer retain the rendered message (see [Rendered message no longer retained](#rendered-message-no-longer-retained-900)), the `INFO` composite re-measured at 1.76 against `WARN`'s 1.68, so the `INFO` in-flight floor is now `/7`, which keeps the whole log under a fifth of the ceiling on the re-measured profiles; `DEBUG` and `TRACE`, which were not re-measured, keep `/12` (see [What this means for the `INFO` divisor](#what-this-means-for-the-info-divisor)). The retention divisors no longer follow that ordering: `WARN` and `INFO` retention are both `/20`, for a GC reason, not a heap one (see [Retention and young-GC promotion](#retention-and-young-gc-promotion)). `INFO` moved from `/12` by owner decision on 2026-10-19: `/20` keeps about 40% fewer entries, but under G1 `/12` promoted more and raised p99, and under gen-ZGC it entered the promotion loop sooner once the consumer saturated (see [`INFO` promotion measured on the perf rig](#info-promotion-measured-on-the-perf-rig-2026-10-19)). `DEBUG` and `TRACE`, not measured, keep `/12`. At `WARN`, most `INFO`-level request/response entries are never written to system-out (the `isEnabled(INFO, WARN)` guard returns false), so `getMessage()` is not called and no message is memoised.

**The 2026-09-19 multiples above are superseded; see [Re-measured multiples (2026-09-28)](#re-measured-multiples-2026-09-28).** They predate the derived-form release (`b413de937`) and the render-before-release ordering (`33d0034df`). They were also recorded "per body byte". The budget bounds *counted* bytes, and a mocked exchange charges its request body to both of its entries.

**Behaviour change — an EXPLICITLY SET budget now retains fewer entries.** Before this honest accounting the weigher counted only body bytes, so it under-counted real retention by ~2.6× at `WARN` and ~4.8× at `INFO`. Now that it counts body + headers + structure, the running total reaches the same budget **sooner**, so a given explicit `maxEventLogSizeInBytes` retains fewer entries — the bound doing what it claims. This applies to a value you set yourself; the heap-derived DEFAULT was re-derived against the honest weigher (2026-09-19) and is sized from measurement rather than inherited from the old multiples. Size against the retained multiple, not the budget alone: the budget is the dial, and the multiple is what the heap sees (see [Re-measured multiples](#re-measured-multiples-2026-09-28) for the current figures by workload and level). Raise `maxEventLogSizeInBytes` if you relied on the old (larger) retention for a given budget. An explicit budget also sets a floor under the ring buffer's in-flight cap: the cap is the larger of the budget and the heap-derived in-flight default (see [Ring In-Flight Bounding and Drops](#ring-in-flight-bounding-and-drops)), so raising the budget raises both, and lowering it below the default lowers only retention.

#### Re-measured multiples (2026-09-28)

**Outcome: with every uncounted copy found by this measurement closed (item 1) and both defects fixed (items 2 and 3), the retained entries hold 0.50–0.80× their counted bytes at `WARN` and 1.04–2.03× at `INFO` once the log has drained, under both G1 and generational ZGC.** These figures alone would have allowed much looser divisors. They leave out the ring backlog, which the in-flight cap bounds separately and which fills at the same time as the retained log whenever the consumer lags. [Validation at the current divisors](#validation-at-the-current-divisors) measures the two together and sets the divisors from that.

1. **Fixed: forward-proxy and templated-echo entries retained two copies the weigher could not see.** The `FORWARDED_REQUEST` entry kept its curl command as a `String` in `arguments`, copying the request body, and a response `StringBody.value` was decoded again after the entry was weighed and released, because `HttpActionHandler` logs the exchange *before* `responseWriter.writeResponse(...)`. The curl argument is now rendered on read and the post-write copy reads without caching (see above). Before the fix proxy traffic retained 1.18–1.24× at `WARN` and 2.27–2.30× at `INFO`.
2. **Fixed in `03abda7e7`: the heap ceiling was doubled under generational ZGC.** `heapAvailableInKB()` used to sum `getMax()` over the heap memory pools, and generational ZGC reports `-Xmx` for both of its generations (see [How the Heap Ceiling Is Read](#how-the-heap-ceiling-is-read)). **The first round of generational-ZGC runs (before item 1 was fixed) was taken with that doubled ceiling**: the budget was 265.8 MB at `WARN` and 177.2 MB at `INFO` instead of 131.6 MB and 87.7 MB (at the `/8` and `/12` divisors then in force), so the log held about twice as many entries. The multiples do not depend on the budget's size, so they are unaffected. But the share of the heap was: with the doubled budget, proxy traffic retained 31% of the true ceiling at `WARN` and 39% at `INFO`, above the quarter target. Re-run on master at `03abda7e7` under generational ZGC, the budget (at the `/8` and `/12` divisors then in force) is 131.6 MB and 87.7 MB, the multiples are 0.56 and 1.24 (`WARN`) and 1.68 and 2.29 (`INFO`) for json10k and proxy10k, and proxy traffic retains 15.5% (`WARN`) and 19.1% (`INFO`). That matches G1.
3. **Fixed in `cbfceae1a`: the in-flight byte counter drifted upward.** `translateTo` did not carry the publish-time weight into the ring slot, so the decrement was recomputed. It came out smaller once a sibling entry sharing the request had released the decoded body. After load at `-Xmx1g`, 56–106 MB of phantom in-flight bytes remained at `WARN` (budget 131.6 MB), surviving `reset()`. At `INFO` with 10 KB JSON bodies they reached 95% of the 87.7 MB budget within about 3,400 requests, after which nearly every request/response entry was dropped. **The drift affected the in-flight counter and drops, not retained bytes.** The `WARN` multiples below were taken on the drifting master but had no drops, and they match the drift-fixed runs exactly. The `INFO` multiples were taken on a copy patched with the same one-line fix. On master at `cbfceae1a` the in-flight counter returns to 0 after load. The drops that remain at `INFO` under 8 flooding clients (about 2,900–4,600 per run) are genuine ring-backlog drops, because the consumer renders every entry.

Real retained heap per counted byte, after the two uncounted copies above were closed. Each run fills the log of a fresh `-Xmx1g` server to its default budget with unique request bodies and divides the drop in live heap when the log is reset (`jcmd GC.class_histogram`, H1 − H2) by `mock_server_event_log_retained_bytes`. Collectors: JDK 17 G1 and JDK 25 generational ZGC. Figures in brackets are the same run before the fix, where they differ materially (>0.05).

| Profile | `WARN` G1 | `WARN` gen-ZGC | `INFO` G1 | `INFO` gen-ZGC |
|---|---|---|---|---|
| json1k | 0.64 | 0.80 | 1.04 | 1.15 |
| json10k | 0.53 | 0.56 | 1.60 | 1.70 |
| json100k | 0.50 | 0.51 | 1.84 | 1.82 |
| text10k | 0.53 | 0.56 | 1.30 | 1.33 |
| echo10k (templated response echoing the body) | 0.68 (0.93) | 0.71 (0.93) | 1.77 | 1.81 |
| proxy10k (forward proxy to an echoing upstream) | 0.69 (1.18) | 0.71 (1.24) | 2.00 (2.27) | 2.03 (2.30) |

The worst case is now 0.80× at `WARN` (small bodies under ZGC, whose uncompressed references make the fixed per-entry structure larger) and 2.03× at `INFO` (proxy traffic, whose memoised message embeds the response, the request and the curl command; since 9.0.0 that message is no longer retained, see [Rendered message no longer retained](#rendered-message-no-longer-retained-900)). At `INFO` the fix changes echo traffic only slightly: the consumer renders every entry and lags the writer, so the write usually runs before the release. They were measured before per-connection header sharing, which lowers them over keep-alive connections, most for small bodies with many repeated headers (see [Header sharing across a connection's requests](#header-sharing-across-a-connections-requests)); the worst cases above remain upper bounds. These multiples were measured at the `/8` and `/12` divisors then in force, where the worst case retained about 10% (`WARN`) and 17% (`INFO`) of the heap ceiling once drained. The `INFO` runs of echo10k and text10k under both collectors, and of json100k under generational ZGC, stopped short of the budget because the ring backlog dropped entries. Drops can raise the multiple of mocked and proxied traffic, because they split request/response pairs that share one counted request body (see [Drops raise the multiple](#drops-raise-the-multiple)); runs with drops can read higher than a drop-free run of the same profile. <!-- version-ref:keep -->

**Method.** The same harness measured every column; the pre-fix runs used a master jar built at `0c97981e7` without the UI, and the fixed columns a jar with the item-1 fix applied. Each run started a fresh server with `-Xmx1g -Xms1g`, `metricsEnabled=true` and the level under test. Collectors: JDK 17 G1 (the `java -jar` default) and JDK 25 generational ZGC (the collector the main Docker images run on JDK 26, via `JAVA_TOOL_OPTIONS=-XX:+UseZGC`). Each run created one expectation, warmed up with 300 requests, then called `reset` and re-created the expectation. It took a baseline histogram H0, then drove 8 client threads of unique request bodies. It kept going until `mock_server_event_log_retained_bytes` reached 93% of `mock_server_event_log_max_retained_bytes`, then sent 40% more to turn the log over. Once the ring had drained, it took H1 and read the gauges, then called `PUT /mockserver/reset` and took H2. Every histogram is `jcmd <pid> GC.class_histogram`, which runs a full GC and so counts live objects only. JFR cannot attribute retained heap under ZGC.

- **Isolation.** Event-log retention is the drop in total live heap when the log is reset: H1 − H2. That cancels warm caches, JIT/class metadata and Netty pools. The difference from the pre-load baseline (H1 − H0) agrees to within 0.002× in every run. In the H1 − H2 difference, at least 96% of the bytes are log-entry classes: `byte[]`, `String`, `NottableString`, `LogEntry`, `HttpRequest`/`HttpResponse`, `*Body`, `Headers` and the deque nodes.
- **Denominator.** The multiple is (H1 − H2) ÷ `mock_server_event_log_retained_bytes`.
- **Attribution.** A live `GC.heap_dump` was walked from the large `byte[]` to their referrers. This split the proxy case into `StringBody.rawBytes` (counted), `LogEntry.arguments` → the curl `String` (not counted), and `HttpResponse.body` → `StringBody.value` (not counted, on about 81% of responses).
- **The drift and `INFO`.** At `0c97981e7` the drift stopped most `INFO` runs from filling the log, so `INFO` was also run on a copy patched only to carry the add-time weight into the ring slot. That patch is the same change later committed as `cbfceae1a`. The multiples are unchanged by the patch: identical at `WARN`, and within 0.05 at `INFO`, where both were measured.
- **Confirmation after the fixes.** json10k and proxy10k were re-run at both levels under both collectors on master at `03abda7e7`, which includes both fixes. Every multiple is within 0.05 of the table.

Profiles: `json1k`/`json10k`/`json100k` are unique JSON request bodies with a static `{"ok":true}` response. `text10k` is `text/plain` with the same static response. `echo10k` is `text/plain` with a Mustache template that echoes the body, giving a unique response. `proxy10k` is `text/plain` forwarded through MockServer as an HTTP proxy to a second instance that echoes the body. Some `INFO` runs stopped at 50–87% full because genuine ring-backlog drops set in, and those drops can push their multiples up, as explained under [Validation at the current divisors](#validation-at-the-current-divisors).

#### Rendered message no longer retained (9.2.0)

**Outcome: at `INFO` a retained entry that quotes a request or response now holds what it holds at `WARN`, so the `INFO` column above overstates the current multiples.** The `INFO` excess in that table was the rendered message, memoised on the entry when the consumer wrote it to the console (`LogEntry.messageFor`) and never counted by the weigher. An entry whose arguments include a request, a response, a curl command, an expectation or a collection no longer memoises its message: the console write renders it once (`MockServerLogger.writeToSystemOut` passes the rendered text on rather than rendering it twice) and a later read, such as a plain `LOGS` retrieve, renders it again. Messages with only text arguments are still memoised.

Measured in process (single thread, JDK 17, `-XX:+UseSerialGC`, one `RECEIVED_REQUEST` entry per request taken through the `processLogEntry` steps; heap after GC per retained entry):

| Body, no `Content-Type` | Weigher | `WARN` | `INFO` before | `INFO` after |
|---|---|---|---|---|
| 1 MiB of zero bytes | 1,049,383 B | 1,038,942 B | 7,299,222 B (7.0x) | 1,007,486 B (1.0x) |
| 1 MiB of 7-bit random bytes | 1,049,383 B | 1,038,942 B | 3,216,589 B (3.1x) | 1,007,485 B (1.0x) |
| 1 MiB of random bytes (binary) | 1,049,383 B | 1,038,958 B | 2,405,885 B (2.3x) | 1,007,501 B (1.0x) |
| 4 KiB of zero bytes | 4,900 B | 4,947 B | 29,804 B (6.1x) | 4,947 B (1.0x) |
| 64 B of zero bytes | 866 B | 915 B | 1,572 B (1.8x) | 916 B (1.1x) |

The load-measured `INFO` column above has since been re-run on the perf rig for json10k, proxy10k and json100k; see [INFO re-measured on the perf rig (2026-10-18)](#info-re-measured-on-the-perf-rig-2026-10-18). The retention divisors were left as they were, which is the safe direction: the log now reaches its budget holding less heap than they assume (`INFO` retention later moved to `/20` for a GC reason; see [`INFO` promotion measured on the perf rig](#info-promotion-measured-on-the-perf-rig-2026-10-19)). On those figures the `INFO` in-flight floor moved from `/12` to `/7`; `DEBUG` and `TRACE` keep `/12` (see [What this means for the `INFO` divisor](#what-this-means-for-the-info-divisor)).

**One small-entry figure has since been read from the perf rig's INFO-arm live-heap reset probe** (`info_log_level_arm.live_heap_reset_probe`; `status=measured`, `drained=true`, no problems in 4 of 4 runs: perf builds 664, 667, 668 and 670; daily build 661 predates the probe). It is measured at `INFO`, so it belongs beside the `INFO` columns above and in [Re-measured multiples](#re-measured-multiples-2026-09-28), never the `WARN` ones, and it reads the log left by the INFO sweep's last rungs — rungs that can drop entries (row 126a), and a drop can raise the multiple (see [Drops raise the multiple](#drops-raise-the-multiple)), so this figure is not drop-free by construction. On that log, bodiless keep-alive `GET /simple` traffic under generational ZGC: 0.337× live heap per counted byte, 1,025–1,027 B per entry (about 3,044 counted B per entry), reproduced to 0.001 across the four runs. That sits well below every multiple in the tables above; one of several possible reasons is this profile carrying no body to memoise, header sharing across the keep-alive connections among the others (see [Header sharing across a connection's requests](#header-sharing-across-a-connections-requests)). It does not replace a row of either table; the json and proxy profiles are re-measured below.

#### INFO re-measured on the perf rig (2026-10-18)

**Outcome: at `INFO` the json10k, proxy10k and json100k profiles now retain 0.70–1.00× their counted bytes once drained (was 1.60–2.03×), and the whole log under overload holds 12.9–14.5% of the ceiling at the `/12` in-flight floor in force for these runs (was 23.6–24.2%).** The composite `INFO` bound (largest drained-deque multiple plus largest ring multiple, from different runs) falls from 3.04 to 1.76 (0.999 + 0.760), next to `WARN`'s 1.68. The remaining gap to `WARN` is pairing, not rendering: the `INFO` consumer lags, so in-flight byte drops split request/response pairs and nearly every surviving entry holds its own request (see [Drops raise the multiple](#drops-raise-the-multiple)). On these figures the `INFO` in-flight floor moved to `WARN`'s `/7` (`DEBUG` and `TRACE` keep `/12`), and retention stayed at `/12` until it moved to `/20` on 2026-10-19; see [What this means for the `INFO` divisor](#what-this-means-for-the-info-divisor).

Each cell is perf builds 694–697 (2026-10-18, `perf` queue, 48 logical CPUs), image `mockserver/mockserver:mockserver-snapshot-graaljs` at `929bb4c0a` (digest `sha256:3820386b…`, JDK 25.0.4.1), SUT pinned to cpus 0–5. 1 GiB cells ran in two builds (694 and 697 for json10k and proxy10k; 696 for json100k); where two runs exist the range is shown. Columns: drained multiple after a fill; then, under 60 s of overload, the drained-deque multiple, the ring-backlog multiple and the whole log as a share of the ceiling (the largest of three samples).

| Heap | Profile | Collector | Drained (fill) | Overload deque | Overload ring | Whole log / ceiling |
|---|---|---|---|---|---|---|
| 1 GiB | json10k | G1 | 0.940–0.941 | 0.927–0.939 | 0.695–0.696 | 13.5–13.6% |
| 1 GiB | json10k | gen-ZGC | 0.961–0.962 | 0.980–0.981 | 0.673–0.674 | 13.8% |
| 1 GiB | proxy10k | G1 | 0.797–0.805 | 0.919–0.922 | 0.630–0.639 | 12.9–13.0% |
| 1 GiB | proxy10k | gen-ZGC | 0.871–0.873 | 0.945–0.949 | 0.627–0.631 | 13.1–13.2% |
| 1 GiB | json100k | G1 | 0.997 | 0.996 | 0.688 | 14.0% |
| 1 GiB | json100k | gen-ZGC | 0.998 | 0.999 | 0.679 | 14.0% |
| 256 MiB | json10k | G1 | 0.948 | 0.959 | 0.670 | 13.6% |
| 256 MiB | json10k | gen-ZGC | 0.932 | 0.982 | 0.681 | 13.9% |
| 256 MiB | proxy10k | G1 | 0.702 | 0.920 | 0.658 | 13.1% |
| 256 MiB | proxy10k | gen-ZGC | 0.784 | 0.936 | 0.619 | 12.9% |
| 256 MiB | json100k | G1 | 1.000 | 0.997 | 0.727 | 14.2% |
| 256 MiB | json100k | gen-ZGC | 0.999 | 0.994 | 0.760 | 14.5% |

- **The harness reproduces the 2026-09-28 `WARN` figures.** Each build also ran the fill at `WARN` as a control: json10k 0.500–0.501 (G1) and 0.524–0.525 (gen-ZGC), proxy10k 0.670–0.671 and 0.687–0.690, json100k 0.501–0.504 and 0.504–0.507, against 0.53/0.56, 0.69/0.71 and 0.50/0.51 in [Re-measured multiples](#re-measured-multiples-2026-09-28). Only one `WARN` cell dropped anything (114 in-flight byte drops, json100k gen-ZGC, 256 MiB).
- **Pairing explains `INFO` against `WARN`.** In the 1 GiB json10k G1 fill of build 697, 7,370 `HttpRequest` objects were released for 7,506 entries (in build 694, 7,337 for 7,407); at `WARN` the same fill released 2,290 for 4,575, one request per pair. In the json100k gen-ZGC fill of build 696, 843 entries released 846 requests, with no `HttpResponse` among the 40 largest classes of the histogram difference. A json10k `INFO` entry costs 10,983–11,372 B against 5,754–6,037 B at `WARN`. Every `INFO` cell dropped entries for in-flight bytes: 220–188,211 during the fill and 0.87–9.4 million during the 60 s of overload, none for a full ring.
- **Collector and heap size barely matter.** G1 and gen-ZGC agree within 0.09 drained and 0.04 on the ring; 256 MiB and 1 GiB within about 0.10 drained (proxy10k, whose drops vary most) and 0.09 on the ring.

**Method.** `.buildkite/scripts/steps/perf-test-info-investigation.sh` (`PART=heap`), an on-demand perf-queue step, applies the harness in [Re-measured multiples](#re-measured-multiples-2026-09-28) and [Validation at the current divisors](#validation-at-the-current-divisors) on the rig: a fresh SUT per cell with `-Xmx`=`-Xms`, the collector chosen in `JAVA_TOOL_OPTIONS`, `MOCKSERVER_DISABLE_SYSTEM_OUT=true` and the shipped event-log default; 300 warm-up requests, `reset`, a baseline histogram; 8 k6 closed-loop clients of unique bodies until `mock_server_event_log_retained_bytes` reached 93% of the budget, then 40% longer; the drained probe (`lib/perf-info-heap-probe.sh`: histogram, `PUT /mockserver/reset`, `GC.run`, histogram). `INFO` cells then refilled and held the 8 clients for 60 s, with a histogram at 20, 40 and 55 s. proxy10k sends absolute-form requests through the SUT as an HTTP proxy to a MockServer upstream that echoes the body with a Mustache template. Every fill reached at least 99.7% of its budget.

##### What this means for the `INFO` divisor

At the measured composite of 1.76, the whole log as a share of the ceiling is about 0.999 ÷ retention divisor + 0.760 ÷ in-flight divisor:

| `INFO` divisors (retention, in-flight) | Whole log / ceiling |
|---|---|
| `/12`, `/12` (before) | 14.7% |
| `/12`, `/7` (`WARN`'s in-flight floor; 2026-10-18 to 2026-10-19) | 19.2% |
| `/20`, `/7` (current, `WARN`'s pair) | 15.9% |
| `/7`, `/7` | 25.1% |

So the `INFO` in-flight floor moved to `WARN`'s `/7`, which keeps the whole log under a fifth of the ceiling on these profiles and lets the slow `INFO` consumer hold about 70% more backlog before in-flight byte drops start. `DEBUG` and `TRACE` were not measured here and keep `/12`. These figures did not decide retention. It moved to `WARN`'s `/20` on 2026-10-19, for young-GC promotion, which was measured at `INFO` later (see [`INFO` promotion measured on the perf rig](#info-promotion-measured-on-the-perf-rig-2026-10-19)); on this composite that puts the whole log at about 15.9% of the ceiling. Small-body and echo traffic were not run under overload, as before.

##### Confirmed on the perf rig at `/7` (2026-10-19)

**Outcome: at the `/7` `INFO` in-flight floor the whole log peaked at 14.0–18.3% of the ceiling under overload, below the 19.2% expected, with no OOM in any cell.** The method is the same as above. Perf builds 699 (1 GiB) and 702 (256 MiB) ran on the master snapshot image at `113c46cdf`, pinned by digest `sha256:bcdc0eea…`, with json10k, proxy10k and json100k under G1 and gen-ZGC. All 24 cells were fully measured and neither log has an `OutOfMemoryError`. The `INFO` in-flight cap read 150,394,880 B at 1 GiB, the ceiling ÷ 7 (87,730,176 B at `/12` in builds 694–697).

| Heap | Collector | Drained (fill) | Overload deque | Overload ring | Whole log / ceiling |
|---|---|---|---|---|---|
| 1 GiB | G1 | 0.669–0.992 | 0.825–0.977 | 0.562–0.695 | 14.9–18.1% |
| 1 GiB | gen-ZGC | 0.687–0.998 | 0.826–0.998 | 0.530–0.676 | 14.5–18.0% |
| 256 MiB | G1 | 0.671–0.986 | 0.822–0.995 | 0.577–0.707 | 15.0–18.3% |
| 256 MiB | gen-ZGC | 0.688–0.988 | 0.756–0.968 | 0.556–0.679 | 14.0–17.7% |

- **Composite and drops.**
  - The composite is 1.71 (0.998 + 0.707), which predicts 18.4% at `/12` retention and `/7` in-flight.
  - Drops are compared as a fraction of entries offered, which is drops ÷ (drops + evicted + retained). Raw counts follow how many requests the closed-loop clients completed in the window, so they can rise while fewer entries are lost.
  - **During the fill the fraction fell in all 12 `INFO` cells.**

    | Profile | 1 GiB, was | 1 GiB, now | 256 MiB, was | 256 MiB, now |
    |---|---|---|---|---|
    | json10k | 0.871–0.906 | 0.725–0.811 | 0.807–0.838 | 0.733–0.764 |
    | json100k | 0.976–0.978 | 0.956–0.957 | 0.971–0.976 | 0.960 |
    | proxy10k | 0.25–0.50 | 0 | 0.04–0.20 | 0 |

  - **Two raw counts rose, in the same 1.5 s window.** Both are 256 MiB gen-ZGC. json10k went from 22,247 to 24,460 drops while 7,576 entries were recorded, up from 5,327. json100k went from 16,375 to 18,212.
  - **Raw counts are noisy anyway.** Identical-config repeats differ by up to about 50% (1 GiB json10k G1: 123,415 in build 694 against 188,211 in build 697).
  - **During the 60 s overload the fraction fell in every cell:** json10k 0.946–0.955 → 0.901–0.917, proxy10k 0.686–0.710 → 0.311–0.464, json100k 0.988–0.991 → 0.982–0.986.
  - **`/7` is kept at every heap size, 256 MiB included.** These images also carry the faster consumer render (row 126a), so the lower drop fractions are not all due to the floor.
- **`WARN` control.**
  - The multiples reproduce the earlier runs: json10k 0.500–0.525, proxy10k 0.670–0.692, json100k 0.501–0.507.
  - The 256 MiB json100k `WARN` fills dropped 378 entries (G1, was 0) and 163 (gen-ZGC, was 114). `/7` did not change `WARN`, so this shows the noise in small raw counts.

#### Validation at the current divisors

**Outcome: the in-flight divisor is `/7` at `WARN`/`ERROR`/`OFF` and `INFO`, and `/12` at `DEBUG`/`TRACE`, set so the whole log — retained entries plus the ring backlog — stays at about a quarter of the heap ceiling or less, even while overloaded.** This section set `/7` at `WARN`/`ERROR`/`OFF` and `/12` at `INFO`/`DEBUG`/`TRACE`; the `INFO` floor moved to `/7` once the `INFO` composite was re-measured without the retained rendered message, while `DEBUG` and `TRACE`, not re-measured, keep `/12` (see [INFO re-measured on the perf rig](#info-re-measured-on-the-perf-rig-2026-10-18)). The measurements below were taken when one budget bounded both sites, so retention used the same divisors. Retention at `WARN` and `INFO` is now `/20`, which only lowers the share: 0.974 ÷ 20 + 0.704 ÷ 7 is about 15% of the ceiling at `WARN`, and 0.999 ÷ 20 + 0.760 ÷ 7 about 16% at `INFO`. They are sized on conservative composite bounds: the largest drained-deque multiple plus the largest ring multiple seen at each level, taken from different runs. That gives 1.68× the budget at `WARN` (0.974 + 0.704) and 3.04× at `INFO` (2.314 + 0.724). The whole log could then reach about 24% of the ceiling at `WARN` (1.68 ÷ 7) and 25.3% at `INFO` (3.04 ÷ 12). The largest sums measured in a single run are 1.63 (`WARN`) and 3.00 (`INFO`). At the current divisors, overloaded runs held 19.7–22.9% of the ceiling at `WARN` and 23.6–24.2% at `INFO`. Overload was measured with json100k and proxy10k traffic only; small-body and echo traffic were not run under overload. After 9.2.0 stopped retaining the rendered message, the `INFO` composite re-measured on the perf rig is 1.76, not 3.04, which at `/12` retention and `/7` in-flight gives about 19.2% (see [INFO re-measured on the perf rig](#info-re-measured-on-the-perf-rig-2026-10-18)).

**Why the ring backlog must be counted.** Two byte bounds apply: the retention budget to the retained deque, and the in-flight cap to the bytes of entries published to the ring but not yet processed (see [Ring In-Flight Bounding and Drops](#ring-in-flight-bounding-and-drops)). Whenever the consumer lags, both fill at once, so the log can hold the retention budget plus the in-flight cap in counted bytes. The consumer lags at `INFO` under sustained load, because it renders every entry. It also lags at any level when it does slow work per entry, such as disk capture (`persistRecordedRequestsToDisk`), which serialises, writes and flushes each exchange on the consumer thread. Ring entries have not reached `releaseDerivedForms()` yet, so their real cost per counted byte is measured separately. Sizing from the drained deque alone would have allowed `/4` and `/10`. Those measured 41% and 30% of the ceiling for the whole log while overloaded.

**Method.** This extends the harness from [Re-measured multiples](#re-measured-multiples-2026-09-28).
- After filling the log, 8 clients keep sending for 60 s. At 20, 40 and 55 s into that window the harness takes `jcmd GC.class_histogram` (a full GC, live objects only) and subtracts the idle baseline H0.
- That difference is the whole log at the moment of overload: deque, ring backlog and the handful of requests in flight. The table shows the largest of the three samples.
- The drained-deque multiple is H1 − H2 after the ring has drained, as before. The ring-backlog multiple is (whole log − drained deque) ÷ the in-flight bytes gauge at the same moment.
- `INFO` runs use default settings. `WARN` runs turn on disk capture to make the consumer lag, since no `WARN` run without it built a backlog. Disk capture writes `FORWARDED_REQUEST` and `EXPECTATION_RESPONSE` entries (`MockServerEventLog.processLogEntry`), so both profiles are captured, but only proxy10k made the consumer lag. Their drained multiples include the pairing effect of the drops (see [Drops raise the multiple](#drops-raise-the-multiple)). Compare them with the drop-free 0.69 baseline only after correcting for pairing.
- Collectors are JDK 17 G1 and JDK 25 generational ZGC, at `-Xmx256m` and `-Xmx1g`.
- These runs were taken at `/4` and `/10`. The multiples are per counted byte, so they carry over approximately (within about 0.05 drained and 0.08 ring between the two divisor pairs measured).

Each cell: drained-deque multiple, ring-backlog multiple, whole log as a share of the ceiling, whole live heap as a share of `-Xmx`, at the peak. In these tables "ceiling" means `heapAvailableInKB` (`-Xmx` less 20 MiB), the same base the budget is taken from.

| Heap | Level | Profile | G1 | Generational ZGC |
|---|---|---|---|---|
| 256 MiB | `INFO` | json100k | 2.25, 0.62, 28.7%, 35.1% | 2.31, 0.69, 29.9%, 38.7% |
| 256 MiB | `INFO` | proxy10k | 2.14, 0.72, 28.6%, 33.9% | 2.17, 0.72, 29.0%, 36.5% |
| 256 MiB | `WARN` + disk capture | proxy10k | 0.85, 0.57, 35.6%, 40.4% | 0.93, 0.70, 40.8%, 47.5% |
| 256 MiB | `WARN` + disk capture | json100k | 0.50, no backlog, 13.2%, 20.8% | 0.51, no backlog, 13.1%, 23.1% |
| 1 GiB | `INFO` | json100k | 2.25, 0.61, 28.7%, 30.3% | 2.27, 0.62, 28.8%, 31.1% |
| 1 GiB | `INFO` | proxy10k | 2.14, 0.67, 28.2%, 29.5% | 2.17, 0.70, 28.7%, 30.7% |
| 1 GiB | `WARN` + disk capture | proxy10k | 0.83, 0.57, 34.9%, 36.1% | 0.90, 0.66, 38.8%, 40.5% |
| 1 GiB | `WARN` + disk capture | json100k | 0.50, no backlog, 12.7%, 14.6% | 0.51, no backlog, 12.8%, 15.3% |

- **Deriving the divisors.** The whole log as a share of the ceiling is about (k_deque + k_ring) ÷ divisor, and each run agrees with that to within a point. For example, the 256 MiB ZGC `WARN` proxy run gives (0.93 + 0.70) ÷ 4 = 41% against 40.8% measured, and the 256 MiB ZGC `INFO` json100k run gives (2.31 + 0.69) ÷ 10 = 30% against 29.9%.
  - The composite `WARN` bound is 1.68: 0.974 (the `/7` confirmation run below) plus 0.704 (the 256 MiB ZGC run above), both proxy traffic with disk capture.
  - The composite `INFO` bound is 3.04: 2.314 (json100k, ZGC, 256 MiB) plus 0.724 (proxy10k, ZGC, 256 MiB).
  - Holding the composite to a quarter needs a divisor of at least 6.7 at `WARN`, so `/7` gives about 24%. At `INFO` it needed 12.2, so `/12` gave 25.3%, about a quarter; the re-measured `INFO` composite of 1.76 needs just over 7 (7.04) for one divisor shared by both sites, so `/7` at both would give 25.1%. `DEBUG` and `TRACE` keep the 3.04 bound and `/12`.
- **Heap size makes little difference.** Between 256 MiB and 1 GiB the drained-deque multiples agree within 0.05 and the ring multiples within 0.07; the largest gap is ZGC `INFO` json100k, 0.69 against 0.62. Filling without sustained overload reproduces the drained multiples in [Re-measured multiples](#re-measured-multiples-2026-09-28) within 0.05. The one exception is ZGC `INFO` json100k at 256 MiB, 1.95 against 1.82, where that fill dropped 1,415 entries.
- **Nothing failed.** No run ran out of memory or stalled an allocation under ZGC. Under G1 there was no full GC beyond the three the histograms force. At `/4` and `/10` the largest heap after GC was 212 MiB of 256 MiB and 822 MiB of 1 GiB (ZGC, `WARN` with disk capture, proxy10k).
- **Drops.** Once the ring holds a full budget, new entries are dropped. Per overloaded run that was about 70,000–76,000 for `INFO` json100k, 870,000–910,000 for `INFO` proxy10k, and 470,000–680,000 for `WARN` proxy10k with disk capture.
- **Drops, not disk capture, explain the higher drained `WARN` multiple.** The overloaded `WARN` proxy runs drained at 0.82–0.97×, against 0.69–0.71× in the drop-free fills without capture. No capture-on proxy run was drop-free, so the two cannot be compared directly. The capture-on json100k runs had no drops, and they drained at 0.503 (G1) and 0.507 (ZGC), the same as the capture-off json100k fills. After correcting for pairing (next section) the capture-on runs sit close to the capture-off drop-free baseline: within about 0.007 under G1 and 0.023 under ZGC. That is consistent with disk capture adding no uncounted copy.

#### Drops raise the multiple

A mocked or proxied exchange writes a request entry (`RECEIVED_REQUEST` or `FORWARDED_REQUEST`) and a response entry that share one `HttpRequest`, and the weigher charges that request's body to both. When the consumer falls behind, the ring drops entries once its in-flight bytes reach the in-flight cap. A dropped partner leaves its survivor holding the request alone. Less of the counted total is then shared, and the real heap per counted byte rises.

In an overloaded json100k run, 203 of 236 retained entries held their own `HttpRequest`. After a fill with fewer drops the figure was 126 of 235. That is why the drained `INFO` multiple rises from 1.82–2.03× after a fill to 2.13–2.31× under overload. It is also why the `WARN` proxy multiple rises from 0.69× towards 1.0×. It stayed below 1.0× in every measured run, but a log whose pairs are almost all broken could exceed it.

**A pairing model.** Take the pairing ratio (unique `HttpRequest` + `HttpResponse`) ÷ (`LogEntry` + `HttpResponse`), with instance counts taken from the H1 − H2 histogram. The offset is the drained multiple minus that ratio.
- **`WARN` proxy10k: the model fits.** The offset is 0.019 under G1 and 0.046 under ZGC in every drop-free capture-off run. It is 0.025–0.026 (G1) and 0.063–0.069 (ZGC) in the six overloaded capture-on runs.
- **`INFO` json100k: the model fits under comparable overload.** The offset is 1.405–1.415 across the seven overloaded runs, but 1.32–1.35 after the fills, which dropped fewer entries. The response term does little here: json100k's responses are an 11-byte `{"ok":true}`, and only 3–13 of 197–1,008 retained entries (at most about 3%) were responses. For this profile the model mostly tracks how many entries hold their own request.
- **`INFO` proxy10k: not validated.** The offset is 1.14–1.19 overloaded against 1.24–1.30 after the fills, a gap of about 0.1.

**Confirmation at `/7` and `/12`.** These are the same overload runs at `-Xmx256m`, with the budget set to what `/7` and `/12` give at that heap (33.7 MiB and 19.7 MiB). Same cell layout as above.

| Level | Profile | Collector | Result |
|---|---|---|---|
| `WARN` + disk capture | proxy10k | generational ZGC | 0.97, 0.63, 22.9%, 31.0% |
| `WARN` + disk capture | proxy10k | G1 | 0.82, 0.55, 19.7%, 25.8% |
| `INFO` | json100k | generational ZGC | 2.31, 0.62, 24.2%, 33.5% |
| `INFO` | proxy10k | G1 | 2.14, 0.70, 23.6%, 29.4% |

No run ran out of memory or stalled, and the largest heap after GC was 186 MiB of 256 MiB. The ZGC `WARN` run's drained multiple (0.974) is the highest seen at `WARN`, and it is the drained figure in the composite `WARN` bound.

#### Retention and young-GC promotion

**Outcome: at `WARN`/`ERROR`/`OFF` the default retention budget is a twentieth of the ceiling, not a seventh, because at a seventh retained entries outlive the young-GC interval under load and are promoted. That drives back-to-back major ZGC collections and caps healthy throughput below what the CPU allows. `INFO` uses the same twentieth since 2026-10-19, in place of a twelfth (see [`INFO` promotion measured on the perf rig](#info-promotion-measured-on-the-perf-rig-2026-10-19)).** The cost is history: where the byte budget is the binding bound, the log keeps about a third as many entries for `verify`, `retrieve` and the dashboard. On large heaps with small bodies `maxLogEntries` (capped at 250,000) can bind first, so the loss is smaller: with ~1.3 KB entries the byte budget binds below roughly a 6 GiB heap at `WARN`, and at 4 GB the log keeps about 64% as many entries as at `/7`. Setting `maxEventLogSizeInBytes` restores more.

- **The rule.** An entry is promoted when it is still retained after a young collection. Its lifetime is the retained entries divided by the eviction rate. Measured in bytes allocated, an entry lives about 2.6 times the budget at every request rate. A young cycle allocates about 100–120 MB at a 462 MB heap and 190–360 MB at 922 MB. Across 28 rig rungs a lifetime of at most 0.83 young intervals gave at most 5 MB/s of promotion, and 1.43 or more gave 7–44 MB/s.
- **The evidence.** On a 2-core, 1 GB container (462 MiB heap) the default (`/7`, 66 MB) budget promoted 8–44 MB/s and stalled allocations from about 33k rps; a 4 MB budget promoted at most 1.3 MB/s, with the knee CPU-bound at 41–44k. The same 66 MB budget in a 2 GB container (about a 922 MiB heap) did not loop. The runs were recorded under item 51 of the performance plan, removed when the item closed: see the history of `docs/plans/performance-programme.md`.
- **Dose-response (2 cores, 1 GB container, 462 MiB heap).** No loop at 4, 16 or 32 MB (promotion at most 1.4, 1.5 and 4.4 MB/s; lifetime ratio up to 0.19, 0.73 and 1.15), so the loop starts between 32 and 66 MB, about 40 MB by extrapolation. Below the loop the knee still falls about one rung per doubling of the budget, because each young cycle marks the live retained entries: the highest rung with p50 ≤ 1 ms was 41.2k, 38.3k and 35.6k, against 30.8k at 66 MB. Saturated throughput did not move (42.1–44.1k). Heap/20 there is 23 MB, below the loop with at least 1.6× margin, so its expected knee was about 35.6–38.3k rather than 41k (interpolated). The rig control in perf build 707 (2026-10-19, a newer image) did better: at `/20` p50 stayed at or below 0.25 ms up to its top rung of 44,300 req/s (see [`INFO` promotion measured on the perf rig](#info-promotion-measured-on-the-perf-rig-2026-10-19)).
- **Why the in-flight cap is separate.** The 4 MB run dropped 795–28,566 log events per rung, because the in-flight cap was the same budget and a burst filled it. The in-flight cap therefore keeps its own default (`/7`), so cutting retention does not cut what a burst may hold while it waits to be recorded.
- **`INFO` moved to `/20`; `DEBUG` and `TRACE` keep `/12`.** Measured on the rig, `INFO` retention is a trade-off, not a rule outcome. The owner first kept `/12` for history, then on 2026-10-19, after seeing the G1 figures, moved `INFO` to `/20`: about 40% fewer entries, in exchange for a lower G1 p99 and less promotion, and a gen-ZGC promotion loop that starts one or two ladder rungs later once the consumer saturates (see [`INFO` promotion measured on the perf rig](#info-promotion-measured-on-the-perf-rig-2026-10-19)). On the re-measured `INFO` composite, `/20` retention with the `/7` in-flight floor puts the whole log at about 16% of the ceiling. An earlier `INFO` tail regression seen on the rig (closed as item 40 of the performance plan; see its history) came from a harness that forced a 256 MiB budget on the `INFO` server, not from the `/12` default then in force. `DEBUG` and `TRACE` were not measured.

##### `INFO` promotion measured on the perf rig (2026-10-19)

**Outcome: decided 2026-10-19, `INFO` moves to `/20`, the same as `WARN`.** The owner first kept `/12` for about 67% more history (12,708 against 7,624 entries at 1 GiB), then reversed it after seeing the corrected G1 figures. `/20` keeps about 40% fewer entries. In exchange, G1's p99 is lower at every 12,000–16,000 req/s rung (19.5–27.3 ms against 26.5–39.9 at `/12`) and it promotes less below saturation (at most 4.3 MB/s against up to 17.6 at 2 GiB). Under gen-ZGC the promotion loop is established from 14,000 req/s rather than 12,000 (`/12` already promoted 12.5–21.0 MB/s at the 10,000 rung, which saturated after the prefill), with no throughput or p50 cost to either divisor in perf builds 710 and 711. `DEBUG` and `TRACE` keep `/12`. The measurements behind the decision, from perf builds 707–711:
- **Below consumer saturation, under gen-ZGC (the image's collector), `/12` cost nothing measurable**, and it keeps about two-thirds more entries than `/20` (12,708 against 7,624 at 1 GiB; `/20` keeps 40% fewer). Under G1 it had a cost there: it promoted up to 17.6 MB/s against 4.3 at `/20` (2 GiB) and raised p99, with the same p50.
- **At the saturated 10,000 req/s rung, `/20` did better.** It promoted less and dropped fewer events in every cell that dropped any. At 2 GiB under gen-ZGC it removed both: `/12` promoted 4.96 MB/s and dropped 15,281 events, against 0 and 0 at `/20`.
- **The rule's verdict depends on the collector.** Under gen-ZGC, the image's collector, applying the entry-lifetime rule as at `WARN` gives at least `/18` from the rungs every cell sustained without drops, and about `/22` if 2 GiB's 10,000 rung is counted. Under G1 the lifetime ratio is above the rule's 0.83 bound at both divisors, so the rule, calibrated on ZGC, does not transfer.
- **Past 10,000 req/s at 2 GiB, gen-ZGC `/12` stays in a promotion loop and `/20` delays it, at no throughput or p50 cost to either.** In two repeat builds, at 12,000–16,000 req/s:
  - `/12` promoted 33.4–36.6 MB/s with 16–18 major collections per window;
  - `/20` promoted 0–0.16 MB/s at 12,000 and 6.1–19.5 MB/s at 14,000–16,000;
  - every cell achieved the offered rate, p50 stayed at or below 0.44 ms, and there were no allocation stalls;
  - the consumer dropped events heavily at both divisors, with `/12` dropping 6–62% more under gen-ZGC.

  Under G1 both divisors promoted at most 5.5 MB/s, and drops went either way.
- **The options were:**
  1. keep `/12`, which keeps the larger history and pays for it in promotion and major collections once the consumer saturates;
  2. move `INFO` to `/20`, which keeps 40% fewer entries and holds promotion off for 2,000–4,000 req/s more (one or two ladder rungs) (chosen).

Each figure comes from a single run, except the 2 GiB rungs past 10,000 req/s, which ran twice.

- **Method.** `PART=promotion` of the INFO investigation step (see [ci-cd.md](../infrastructure/ci-cd.md#info-level-investigation)) mirrors the `WARN` dose-response.
  - **Setup.** Each SUT is pinned to 2 cores in a 1 GiB or 2 GiB container (462 or 922 MiB heap), with `-Xlog:gc*`, `MOCKSERVER_DISABLE_SYSTEM_OUT=true` and `maxEventLogSizeInBytes` set to the heap ceiling ÷ divisor. The server's gauges confirmed every budget and the `/7` in-flight cap.
  - **Load.** A `sweep.js` prefill takes the log to its budget, then a 2,000–10,000 req/s ladder runs in 30 s rungs, with metrics sampled every second.
  - **Derivation.** For each 25 s window the step derives:
    - promoted MB/s: the gen-ZGC young `Promoted:` row, or G1 old-region growth in young-only pauses;
    - the young interval;
    - the entry lifetime, retained entries ÷ eviction rate.
  - **Builds.** Perf builds 709 (1 GiB) and 708 (2 GiB) ran `INFO`; 707 ran a `WARN` control at `/7` and `/20` on a 20,000–44,300 req/s ladder. All three used the master snapshot image at `548bae7f5` (digest `sha256:5304e9f3…`). The two divisors were compared within one job on one VM.
  - **Coverage.** All 12 cells were fully measured, the byte budget bound in every rung, and every `INFO` cell achieved the full offered rate up to 10,000 req/s. The ladder stopped there, so the throughput comparison covers only rungs every cell sustained.
- **The control reproduces the rule's thresholds, but not the earlier onset.**
  - **`/7` under gen-ZGC.** A ratio of 0.74–1.07 promoted at most 2.7 MB/s. At 41,200 and 44,300 req/s it looped: ratio 1.85–2.09, promotion 26.2–37.4 MB/s, young interval 0.29–0.31 s, 14–16 major collections per window, and p50 7.2–10.3 ms. There were 5 allocation stalls, all at 44,300.
  - **Onset is about 8k req/s higher than in the earlier runs.** Here 33,100 req/s promoted nothing and the p50 ≤ 1 ms knee was 38,300 req/s. The earlier runs stalled from about 33k with a knee at 30.8k.
  - **`/20` under gen-ZGC.** The ratio stayed at or below 0.31, promotion at or below 0.6 MB/s, and p50 at or below 0.25 ms at every rung.
  - **Allocation lifetime.** Under gen-ZGC an entry lived 2.5–2.8× the budget in bytes allocated, against the 2.6× recorded earlier (1.9–2.0× under G1).
- **`INFO` allocates more per retained byte.**
  - Each request evicts 6 entries, against 2 at `WARN`, at about 3.0 KB counted per entry against 1.3 KB.
  - An entry lives 6.5–7.7× the budget in bytes allocated under gen-ZGC, and 5.3–5.4× under G1. That is about 2.6× the `WARN` figure.
  - So a given ratio needs a divisor about 2.6× larger at `INFO`.

| gen-ZGC, `INFO` | Lifetime ÷ young interval, 2,000–8,000 req/s | Promotion, 2,000–8,000 | At 10,000 req/s |
|---|---|---|---|
| 1 GiB, `/12` | 0.76–1.24 | 0–0.28 MB/s | 35.4 MB/s, ratio 3.26, 33 majors, ring wait 3.48 young intervals, 398,985 `ring_full` drops |
| 1 GiB, `/20` | 0.46–0.60 | 0 | 27.1 MB/s, ratio 1.84, 32 majors, ring wait 3.28, 343,113 drops |
| 2 GiB, `/12` | 0.69–0.97 | 0–0.84 MB/s | 4.96 MB/s, ratio 1.53, 4 majors, ring wait 0.24, 15,281 drops |
| 2 GiB, `/20` | 0.42–0.52 | 0–0.08 MB/s | 0, ratio 0.61, 1 major, no drops |

- **What the rule gives (gen-ZGC).** `WARN`'s `/20` keeps the ratio at or below 0.83 at every rung below the knee, and at `INFO` the ratio scales with the budget.
  - **From the sustained rungs.** On the rungs every cell sustained without drops (up to 8,000 req/s), the largest `/12` ratio was 1.24 (1 GiB). That needs a divisor of at least 12 × 1.24 ÷ 0.83 ≈ 18.
  - **Counting 2 GiB's 10,000 rung.** That rung was below saturation at `/20` and under G1, and gives 12 × 1.53 ÷ 0.83 ≈ 22. Its ratio of 1.53 is past the ≥ 1.43 at which `WARN` promoted, and `/12` did promote there.
  - **At `/20`** the largest unsaturated ratio was 0.61.
- **Below saturation, `/12` cost nothing measurable (gen-ZGC).**
  - Up to 8,000 req/s, `/12` promoted at most 0.84 MB/s, despite ratios up to 1.24. p50 was within 0.01 ms of `/20`. p99 was 1.0–5.5 ms against 1.0–3.8 ms at 1 GiB, and 0.9–3.5 against 0.9–3.4 at 2 GiB.
  - An entry waited in the ring for at most 0.02 young intervals there (0.08 under G1), so rendering does not make promotion structural, as this section once assumed.
- **At saturation, `/20` did better.**
  - **1 GiB, gen-ZGC.** At 10,000 req/s the single `INFO` consumer saturated at both divisors. The ring backlog, which the in-flight cap bounds rather than retention, then dominated entry lifetime. 1 GiB still promoted 27–35 MB/s at either divisor, but `/20` promoted less and dropped about 56,000 fewer events.
  - **2 GiB.** Under gen-ZGC, `/20` kept the 10,000 rung below the rule's threshold, and `/12` did not.
  - **Drops.** At 10,000 req/s, `/12` dropped more than `/20` in every cell that dropped any (gen-ZGC 1 GiB 398,985 against 343,113; gen-ZGC 2 GiB 15,281 against 0; G1 1 GiB 9,383 against 1,616; G1 2 GiB dropped none at either divisor). Dropped events are lost history too, set against `/20`'s smaller retained log. These are single runs, and identical repeats of earlier `INFO` fills varied by up to about 50% in drops.
  - **The lever at saturation.** The consumer's rendering cost (backlog row 126b) is the other lever.
- **G1: the rule does not transfer, so the evidence is promotion and p99 alone.**
  - **Ratios.** Under G1 the lifetime ratio is above 0.83 at both divisors: 1.40–2.48 (1 GiB) and 1.73–3.98 (2 GiB) at `/12`, against 0.99–1.92 and 1.23–2.65 at `/20`. In the `WARN` control at `/20` it reached 0.92.
  - **Promotion at 2 GiB.** G1 promoted 6.2–17.6 MB/s from 2,000 to 8,000 req/s at `/12`, with 15–38 concurrent mark cycles per window, against 0–4.3 MB/s at `/20`.
  - **Promotion at 1 GiB.** G1 promoted 0–6.2 MB/s at `/12` against 0–0.16 at `/20`.
  - **Latency.** p50 matched between the divisors, and the highest rung with p50 ≤ 1 ms was 10,000 req/s in every `INFO` cell. p99 was higher at `/12`: 9.8–23.4 ms against 7.0–16.5 at 2 GiB, and 7.5–17.2 against 4.9–14.3 at 1 GiB.
  - **Bias.** These G1 promotion figures are lower bounds that favour `/12`. Mixed pauses are left out of the sum, and there were more of them at `/12`: 108 against 23 over the run at 1 GiB, and 193 against 32 at 2 GiB.
  - **`WARN` control.** G1 at `/7` promoted 12.7–15.5 MB/s at every rung, again without a p50 knee.
- **Past 10,000 req/s at 2 GiB (perf builds 710 and 711, repeats on separate VMs).** The derivation, image and cells were the same as build 708, on a 10,000–16,000 req/s ladder, with G1 and gen-ZGC each at `/12` and `/20`. Both collectors were run because the step compares collectors as well as divisors, and G1 behaved differently below 10,000.
  - **Coverage.** All 8 cells were fully measured, with no request errors.
  - **The 10,000 rung was the first after the prefill.** Its k6 VU pool dropped 8,072–10,088 iterations, so it achieved 9,664–9,731 req/s. It saturated the consumer in every cell (78,480–202,413 `ring_full` drops). Build 708's 10,000 rung followed six lower rungs and dropped 0–15,281. Read 10,000 req/s from either ladder with that in mind.
  - **Throughput and latency.** The 12,000–16,000 rungs achieved the offered rate in every cell.

| 2 GiB, 12,000–16,000 req/s (710 / 711) | Promotion | Majors per window | Lifetime ÷ young interval | Ring wait ÷ young interval | `ring_full` drops per window | p50 |
|---|---|---|---|---|---|---|
| gen-ZGC `/12` | 33.4–36.6 MB/s | 16–18 | 2.69–3.22 | 1.39–1.77 | 506,823–1,253,398 | 0.16–0.44 ms |
| gen-ZGC `/20` | 0–0.16 at 12,000; 6.1–19.5 at 14,000–16,000 | 2–13 | 0.93–1.78 | 0.78–1.68 | 321,875–1,179,481 | 0.11–0.39 ms |
| G1 `/12` | 0.04–5.48 MB/s | 0–1 | 1.30–1.62 | 0.66–0.83 | 107,633–776,393 | 0.09–0.11 ms |
| G1 `/20` | 0 | 0 | 0.74–1.08 | 0.60–0.96 | 185,834–800,791 | 0.09–0.11 ms |

  - **gen-ZGC, rung by rung.** `/12` dropped 54–62% more than `/20` at 12,000 req/s, 16–23% more at 14,000 and 6–9% more at 16,000. p99 was 8.5–10.5 ms at `/12` against 6.2–10.5 at `/20`.
  - **G1.** Drops went either way. p99 was 26.5–39.9 ms at `/12` against 19.5–27.3 at `/20`.
  - **What this shows.** Past saturation, gen-ZGC's loop at `/12` is retention plus backlog. `/20` halves the retained lifetime, which keeps the 12,000 rung below the loop, but at 14,000–16,000 the ring backlog alone (1.2–1.7 young intervals) pushes `/20` into it too.
  - **What it costs.** The loop's cost on this rig is promotion, major collections and more drops, not throughput or p50: both divisors stayed far below p50 1 ms at every rung, with no allocation stall.

##### Confirmed on the perf rig at `/20` (2026-10-19)

**Outcome: the shipped `/20` ran without OOM, with the expected budget, and the whole log stayed far below a quarter of the ceiling.** Row 126b's faster consumer, which also ships on this image, changed two things against the figures above. The ring barely backs up any more: in-flight bytes peaked at 0.66–18.6 MB of the 150 MB cap, so the whole log peaked at 1.7–3.5% of the ceiling under overload rather than about 16%. No rung dropped, and p99 was lower at every rung. The builds ran on the master snapshot at `3d6875dda` (digest `sha256:db1483ca…`), with `MOCKSERVER_DISABLE_SYSTEM_OUT=true` as in every run above.

- **Heap, perf build 714.** The cells were json10k, proxy10k and json100k at 1 GiB, under G1 and gen-ZGC.
  - **Measurement.** All 12 cells were measured, and the log has no `OutOfMemoryError`.
  - **Budget.** Every `INFO` cell read `max_retained_bytes` 52,637,696, the ceiling ÷ 20, and an in-flight cap of 150,394,880.
  - **Whole log under overload.** It peaked at 1.7–3.5% of the ceiling, against 14.5–18.1% in build 699, because in-flight bytes peaked at 0.66–18.6 MB of the 150 MB cap (the per-cell peaks).
  - **Drops.** There were no `ring_full` drops in any fill or overload. One cell dropped 287 events on in-flight bytes (json100k G1 overload).
  - **Drained multiples.** `INFO` read json10k 0.324–0.346, proxy10k 0.668–0.687 and json100k 0.333–0.336, against 0.878–0.922, 0.669–0.687 and 0.992–0.998 in build 699. The `WARN` control reproduced the earlier runs.
  - **Step result.** The step reported `failed` only because its completeness check then required a ring figure in every overload sample. The check now records a phase whose ring never backed up as `not_reached`, provided the load is shown to have run through the window, and counts in-flight byte drops as backing up, which would make json100k-INFO-G1 `measured` (see [ci-cd.md](../infrastructure/ci-cd.md#info-level-investigation)).
- **Promotion at `/20`, perf build 717.** The cells were 1 GiB and 2 GiB at 2,000–10,000 req/s, compared with builds 708 and 709.
  - **Drops.** No rung dropped. 709's 1 GiB 10,000 rung had dropped 343,113 under gen-ZGC and 1,616 under G1.
  - **Promotion.** gen-ZGC promoted 0–1.28 MB/s, most at the 2,000 rung, the first after the prefill, against 0–0.08 below 10,000 before. G1 promoted 0–1.4 MB/s at 1 GiB (was 0–0.16) and 0–4.56 at 2 GiB (was 0–4.32).
  - **p99.** G1 read 1.1–5.2 ms at 1 GiB (was 4.9–14.3) and 4.1–8.3 ms at 2 GiB (was 7.0–16.5). gen-ZGC read 0.47–0.74 ms (was 0.94–8.48).
- **Promotion at `/20`, perf build 718.** The cells were 2 GiB at 10,000–16,000 req/s, compared with builds 710 and 711.
  - **Drops.** No rung dropped, against 105,577–1,179,481 per window before.
  - **Promotion.** It was at most 0.32 MB/s, with 0–1 major collections per window, against 0–19.52 MB/s and 0–13.
  - **p99.** G1 read 8.8–14.2 ms (was 19.5–38.4) and gen-ZGC 0.57–5.85 ms (was 6.2–10.6).
- **Not yet observed.** The daily run's `info_log_level_arm.config.event_log_budget` should read 47,290,368 on the 922 MiB GraalJS heap. No daily run had used the image yet. Build 717's 2 GiB cells, which have the same heap, read 47,290,368 on the gauge, but they set the budget explicitly.

##### `/12` against `/20` again, with row 126b's consumer (2026-10-19)

**Outcome: with `disableSystemOut` set, the saturation case behind the move to `/20` no longer occurs. Under gen-ZGC at 12,000–16,000 req/s, `/12`'s promotion stayed in the same 0–0.32 MB/s range as `/20`'s. Its p99 stays below 1 ms but is 0.23–0.45 ms higher than `/20` at 14,000–16,000 (within 0.04 ms at 12,000), with 1–2 major collections per window against 0–1. Under G1, `/12` still raises p99 by about 5 ms. Decided 2026-10-19: the owner keeps `INFO` at `/20` because of that G1 p99 cost. This re-measure was the reopen check that row 126b's faster consumer triggered, so that check is closed.**

Perf builds 719 and 720 repeated builds 710 and 711:

- 2 GiB, G1 and gen-ZGC, each at `/12` and `/20`, on a 10,000–16,000 req/s ladder.
- Harness from a hidden ref holding the `not_reached` step change, image `3d6875dda` (`sha256:db1483ca…`).
- All 8 cells measured, with no request errors and no `ring_full` or in-flight drop at any rung.

| 2 GiB, 12,000–16,000 req/s | Builds 710/711 (before 126b) | Builds 719/720 (with 126b) |
|---|---|---|
| gen-ZGC `/12`: promotion, majors per window, p99 | 33.4–36.6 MB/s, 16–18, 8.5–10.5 ms | 0–0.32 MB/s, 1–2, 0.59–0.81 ms |
| gen-ZGC `/20` | 0–19.5 MB/s, 2–13, 6.2–10.5 ms | 0–0.32 MB/s, 0–1, 0.27–0.63 ms |
| G1 `/12` | 0.04–5.48 MB/s, 0–1, 26.5–39.9 ms | 0–5.64 MB/s (12,000 only), 0–1, 14.5–19.7 ms |
| G1 `/20` | 0, 0, 19.5–27.3 ms | 0, 0, 9.1–15.7 ms |
| `ring_full` drops per window | 107,633–1,253,398 | 0 |
| p50 | 0.09–0.44 ms | 0.085–0.103 ms |

- **Lifetime ÷ young interval.** Both divisors are now below the rule's 0.83 at every rung:
  - gen-ZGC: `/12` 0.10–0.12, `/20` 0.06–0.07;
  - G1: `/12` 0.36–0.71, `/20` 0.30–0.41.
  - In 710/711 gen-ZGC `/12` read 2.69–3.22.
- **The 10,000 rung** came first after the prefill again. It achieved 9,615–9,881 req/s, with 3,580–11,563 dropped iterations.
  - G1 `/12` promoted 0.12–7.96 MB/s with p99 11.7–15.6 ms. G1 `/20` promoted at most 0.04 MB/s, with p99 7.7–8.6 ms.
  - gen-ZGC `/20`'s p99 was 7.7 and 42.8 ms on that rung, against 0.60–0.61 at `/12`. That rung also dropped the most iterations, so read it as noise from the prefill transition, not as a divisor effect.
- **History.** `/12` keeps 78,817,280 counted bytes at this heap, against 47,290,368 at `/20`, which is about two-thirds more.
- **Not covered.**
  - The 2,000–8,000 rungs, where G1 `/12` promoted up to 17.6 MB/s in build 708.
  - The console-on default (`disableSystemOut=false`), where the consumer still renders every `INFO` entry and may still saturate as in 710/711.
  - The 1 GiB heap, where build 709's gen-ZGC `/12` promoted 35.4 MB/s at 10,000 req/s.
  - These gaps are recorded as unmeasured and are not open items (backlog row 204f).

#### Compact retention (evaluated 2026-10-12)

**Outcome: declined. Keeping retained entries as encoded `byte[]` (or in direct buffers) and decoding them on read was prototyped and measured, and it does not pay.** For body-carrying traffic, where the byte budget binds, retained heap fell only 6–27%, and only in a design that keeps today's sharing. Per-entry encoding raised it by 65–169%. Object count does fall about fourfold, but every `verify`, `retrieve` and dashboard read then decodes each entry it scans, on the event log's single consumer thread. That made a path-only scan 6–24 times slower. The promotion loop that prompted the idea was fixed by the `/20` retention divisor (see above), which compaction would not have replaced: promoted bytes track retained bytes, and for body traffic they barely move.

| Per mocked exchange (two entries) | Today | Encoded per entry | Encoded, sharing kept | Per entry, deflated | Off-heap per entry (heap + direct) |
|---|---|---|---|---|---|
| Small `GET`, 6 headers, 256 B static response | 1,992 B, 50 objects | 1,648 B (−17%) | 952 B (−52%) | 1,258 B (−37%) | 656 B + 1,228 B |
| 1 KiB JSON request, 1 KiB static JSON response | 2,788 B, 42 objects | 4,604 B (+65%) | 2,024 B (−27%) | 2,028 B (−27%) | 656 B + 4,182 B |
| 10 KiB JSON request, 10 KiB static JSON response | 12,012 B, 42 objects | 32,260 B (+169%) | 11,240 B (−6%) | 8,418 B (−30%) | 586 B + 31,836 B |
| 4 KiB binary request, 4 KiB static binary response | 5,800 B, 40 objects | 13,856 B (+139%) | 5,128 B (−12%) | 13,740 B (+137%) | 566 B + 13,438 B |

Every compact column keeps about 12 objects per exchange, against 40–50 today. "Sharing kept" encodes the exchange's request once for both entries and keeps a static response body as a reference to the expectation's body. It is the best case, and it needs the consumer to recognise each pair and the expectation's body. Each compact holder kept only the entry's scalar fields, so every compact column is a lower bound.

| Cost per entry (median, ns) | Small `GET` | 1 KiB JSON | 10 KiB JSON | 4 KiB binary |
|---|---|---|---|---|
| Encode, on the consumer, per retained entry | 750 | 1,050 | 3,140 | 1,620 |
| Decode (binary codec) | 1,390 | 1,320 | 3,150 | 5,000 |
| Inflate and decode | 5,030 | 5,930 | 16,440 | 21,460 |
| Decode an exchange from the `retrieve` JSON format | 21,310 | 61,800 | 384,970 | 51,970 |
| Path-only match scan, today | 190 | 230 | 210 | 220 |
| Path-only match scan, compact (decode and match) | 1,480 | 1,370 | 3,490 | 5,050 |

**Why it was declined.**

- **The bytes are already lean.** `releaseDerivedForms()` leaves each body as one raw `byte[]`, and that byte array is most of a body-carrying entry. An exchange's two entries share one `HttpRequest`, a static response shares its body with the expectation, and header wrappers are shared across a connection (see [Header sharing across a connection's requests](#header-sharing-across-a-connections-requests)). Encoding each entry separately loses all three. Keeping them needs pair-aware encoding on the consumer and a reference back into the live expectation, so it is no longer simply a compact copy.
- **Reads get slower on the thread that ingests.** Queries run as scans on the single disruptor consumer, which freezes ingestion for the length of the scan (`EventLogQueryLatencyBenchmark`). At the 250,000-entry `maxLogEntries` cap, a path-only `verify` takes the consumer about 50 ms today. Compact entries would take 0.34–1.26 s. While a scan runs, a 16,384-slot ring overflows above about 330,000 entries/s today, but above 13,000–48,000 entries/s with compact entries. Encoding adds 0.75–3.1 µs per entry to the same thread, which at `INFO` is already the bottleneck (see [Ring In-Flight Bounding and Drops](#ring-in-flight-bounding-and-drops)).
- **The dashboard cache stops working.** `DashboardBodyCap.CUT_BODIES` is keyed weakly on the retained body object. A body decoded on each read is a new object every time, so every update would decode and cut the body again.
- **Fidelity costs upkeep.** The codec must carry every field of `HttpRequest` (about 20, with its `RequestDefinition` base) and `HttpResponse` (about a dozen), and change whenever they do. The spike's first codec silently dropped `socketAddress`, which a round-trip equality check caught. Decoding through the existing JSON serialisers keeps fidelity but costs 21–385 µs per exchange.
- **Off-heap needs a second budget.** Direct buffers leave a few hundred heap bytes per entry, but they share `MaxDirectMemorySize` with Netty's pooled buffers (see [Direct-memory limit](#direct-memory-limit)). They also sit outside `-Xmx`, so a container sized to its heap can be killed for exceeding its memory limit. Each per-entry direct buffer also brings its own cleaner object, so a real design would need an arena freed in eviction order. Reads still pay the decode.
- **What it would help.** Marking: a full GC with 40,000 retained entries took 27–38 ms over an empty-heap baseline today, against 6–14 ms with shared compact entries (serial collector, directional). The only large byte saving is small bodiless traffic (−52%), which header sharing has already cut. Reopen this only if the rig shows young-GC marking of retained entries, not promoted bytes, binding at the `/20` budget for that traffic. Even then, compacting headers alone is the smaller change to try first.

**Method.** This was an in-process spike against `mockserver-core`; its code was not landed. Each exchange went through the real `FullHttpRequestToMockServerHttpRequest`, eight mappers round-robin as eight keep-alive connections, each request with two unique headers. The response came from the real `HttpResponseActionHandler`. Both entries then went through the consumer's retention steps at `WARN`: `cloneAndClear`, then `releaseDerivedForms`. Retained heap and object counts are `jcmd GC.class_histogram` deltas over 20,000 exchanges (40,000 entries), on JDK 17 with `-XX:+UseSerialGC`. Retained bytes were checked against a real synchronous `MockServerEventLog` and agreed within 3 bytes per entry. The codec is a length-prefixed binary form, and bodies are decoded through `BodyDecoderEncoder.bytesToBody`, as the mapper decodes them. Each round-trip was checked for equality of the request and response. Costs are medians of 8 rounds over 10,000 entries on JDK 17 G1, on an Apple-silicon Mac, so they are directional, not rig figures. The `retrieve` JSON decode goes through `HttpRequestSerializer` and `HttpResponseSerializer`, which validate against the schema.

### Body truncation (`maxLoggedBodyBytes`)

`maxLoggedBodyBytes` is a secondary in-memory valve (default 0 = unlimited). When set to a positive value, `MockServerEventLog.truncateBodiesForLog()` replaces the request and/or response body in the (already-cloned) log entry with a truncated copy before the entry is added to the deque. A `x-mockserver-body-truncated: <originalLength>` header marks the truncated copy. The entry's arguments that quote the request or response, and the curl command for the request, are pointed at the truncated copy too (`LogEntry.replaceQuoted`); before 9.0.0 they kept the original, so the whole body stayed reachable from the entry and the valve bounded only the `httpRequest`/`httpResponse` fields. Everything else the entry quotes is bounded the same way (`LogEntry.boundQuotedBodies`): the real expectation it names (a closest match), each expectation, request or response argument (a quoted expectation clone, an action, the curl command for another request) gets bodies cut to `maxLoggedBodyBytes`, and the matcher's `because` and any text argument longer than that are cut with a "(N more characters not logged)" suffix. Cut copies are shared per original within an entry, so a named expectation and its quoted clone keep one copy, which the weigher charges with the named expectation; the weigher also charges the `because` length. Before this, a not-matched or removed-expectation entry kept the expectation's whole bodies, and a `because` quoting them, after the expectation itself was removed (`EventLogTruncatedBodyRetentionIntegrationTest` walks every object a retained entry reaches). <!-- version-ref:keep -->

Key ordering guarantee: disk capture (when `persistRecordedRequestsToDisk` is enabled) runs **before** truncation in `processLogEntry`, so the NDJSON archive always receives the full body regardless of `maxLoggedBodyBytes`. The archive captures both forwarded and mocked exchanges and outlives ring-buffer (`maxLogEntries` / `maxEventLogSizeInBytes`) eviction and process restarts; evicted entries can be brought back into the queryable in-memory log via `PUT /mockserver/import?format=recording` (`?source=disk` to read the configured path). See [event-system.md](event-system.md) for the disk-capture and re-import paths.

**Verification impact:** setting `maxLoggedBodyBytes` breaks verification against request or response body content. A verify call that matches on body returns 406 against a truncated log entry where the full body would return 202. Verification by path, method, and header is not affected. The truncation is visible in the retrieved entry and in the dashboard via the `x-mockserver-body-truncated: <originalLength>` header on the stored copy. This is by design — truncation is an intentional trade-off to reduce deque body footprint, not a silent loss.

**Does not prevent ring-backlog drops.** `truncateBodiesForLog()` runs in the LMAX Disruptor consumer, downstream of the ring. The body is already in the ring at full size before truncation can run, so truncation does not reduce the ring's in-flight byte contribution. The ring's in-flight backlog is bounded instead by the in-flight cap (see [Ring In-Flight Bounding and Drops](#ring-in-flight-bounding-and-drops)).

The byte-budget weigher measures the (possibly truncated) body bytes, so truncation reduces the weight contributed to `totalBytes`.

### Ring In-Flight Bounding and Drops

Two byte bounds apply to the event log: `maxEventLogSizeInBytes` to the deque (retained entries), and the in-flight cap to the ring buffer's backlog (published but not yet processed entries). The in-flight cap (`Configuration.maxEventLogInFlightBytes()`) is derived, not a property of its own: the larger of `maxEventLogSizeInBytes` and a heap-derived default (`(heapAvailableInKB / 7) * 1024` at `INFO`/`WARN`/`ERROR`/`OFF`, `/12` at `DEBUG`/`TRACE`), and `0` (disabled) when `maxEventLogSizeInBytes` is `0`. Taking the larger means a small retention budget, default or explicit, never makes a burst drop events, while a budget set above the default still raises the cap. The ring is bounded separately because the deque budget alone cannot see it — at `INFO`, the single consumer formats every entry to system-out and is slow enough that the ring can back up and hold many full bodies before any eviction or truncation downstream can run.

**Mechanism.** `MockServerEventLog.add()` maintains an `AtomicLong inFlightBytes` counter: incremented by `LogEntry.estimatedHeapSize()` on a successful ring publish, decremented by the same amount in `processLogEntry()` when the entry is consumed. The decrement must use the publish-time weight, not a recompute: `translateTo()` carries the memoised weight into the ring slot, because a sibling entry sharing the same `HttpRequest` (a mocked exchange logs both `RECEIVED_REQUEST` and `EXPECTATION_RESPONSE`) may already have released the body's decoded String, and a recompute would then be smaller and leave phantom in-flight bytes that eventually drop every new entry. The counter returns to exactly `0` whenever the ring is idle; `reset()` does not zero it, because producers publish lock-free and zeroing would race an in-progress publish. Before each publish, `wouldExceedInFlightBudget()` checks whether `inFlight + incomingWeight` exceeds the in-flight cap. If it would exceed the cap (and something is already in flight — a single oversized body is always admitted into an empty backlog, mirroring the deque's one-oversized-element rule), the entry is **dropped** rather than published.

**Drops are announced, not silent.**

- A once-only `WARN` is logged on the first drop for each cause, with that cause's remedy: for a full ring, lower the log level or keep `INFO` and set `maxLoggedMatchFailures` to cap the `EXPECTATION_NOT_MATCHED` entries per request (a bigger `ringBufferSize` only absorbs bursts); for the in-flight byte budget, which it names, lower the log level or, with heap to spare, raise `maxEventLogSizeInBytes` above that budget (not `maxLoggedBodyBytes`, which truncates only after the backlog).
- The `mock_server_dropped_log_events` Prometheus counter increments on every drop, labelled `reason="ring_full"` or `reason="in_flight_bytes"` (never reset — mirrors a Prometheus counter's monotonic semantics); `getDroppedLogEventCount(DropReason)` keeps the same per-reason totals with metrics off. See [metrics.md](metrics.md#dropped-log-events-counter).
- Two further counters, `ringFullDroppedSinceLogReset` and `inFlightBytesDroppedSinceLogReset`, track drops for each cause since the last `reset()` or `clear(null)` — this is the **taint** that the fail-closed verify path reads.

**Fail-closed verification.** Either taint counter above zero is treated identically to a deque eviction for the purposes of upper-bound verification. A `verify` with `never()`, `atMost(n)`, `exactly(n)`, `once()`, or `between(a,b)` will **fail** once one is non-zero, because a dropped entry cannot be found even if the event actually happened. `atLeast(n)` and the bare `verify(request)` (which is `atLeast(1)`) are unaffected — they require presence, not absence. `verifySequence` asserts no upper bound, so it is unaffected too.

**The failure names what happened since the last reset.** `upperBoundUnprovableAfterEviction` builds the message from the two taint counters and the deque's count- and byte-driven eviction counts (`getEvictedCount()`, `getByteEvictedCount()`), so it lists only the causes that occurred since the last `reset()` / `clear(null)`, each with its count and its remedy, then the two remedies common to all (reset the log between tests, or `failVerificationOnEvictedLog=false`). The lifetime per-reason totals are not used: they would blame a cause from before the reset.

| Cause since the last reset | Message says | Remedy given |
|---|---|---|
| Ring-full drops | `N log events were DROPPED before being recorded because the ring buffer was full` | lower the log level, or keep it and set `maxLoggedMatchFailures` to cap the `EXPECTATION_NOT_MATCHED` entries per request; a larger `ringBufferSize` only absorbs short bursts |
| In-flight byte drops | `N log events were DROPPED … exceeded the in-flight byte budget of B bytes` | lower the log level or raise `maxEventLogSizeInBytes` (not `maxLoggedBodyBytes`, which truncates only after the backlog) |
| Eviction, count bound | `N recorded entries were EVICTED after the log reached its maximum number of entries (maxLogEntries=…)` | raise `maxLogEntries`, or lower the log level, or keep it and set `maxLoggedMatchFailures` |
| Eviction, byte bound | `… its maximum size in bytes (maxEventLogSizeInBytes=…)` | raise `maxEventLogSizeInBytes`, or set `maxLoggedBodyBytes` to truncate large bodies |

The `VERIFICATION_FAILED` log entry (shown in the dashboard) ends `because the event log has dropped log events`, `evicted entries`, or `dropped log events and evicted entries` to match, followed by a second argument: the same counts and bounds as a map, holding only the causes that occurred. The REST `406` body and every client's assertion message carry the full message unchanged.

| Cause | Fields in the entry's argument |
|---|---|
| Ring-full drops | `droppedRingFull` |
| In-flight byte drops | `droppedInFlightBytes`, `inFlightBytesBudget` |
| Eviction, count bound | `evictedAtMaxLogEntries`, `maxLogEntries` |
| Eviction, byte bound | `evictedAtMaxEventLogSizeInBytes`, `maxEventLogSizeInBytes` |

The map is passed as an argument, not concatenated into the format, so the text log prints it after the sentence and the dashboard receives it as a JSON message part, which it renders as one line per cause with that cause's remedy (see [dashboard-ui.md](dashboard-ui.md#failed-verification-on-an-incomplete-event-log)). The remedy wording is the dashboard's own, kept in step with the message by hand. The field names and the entry's text are pinned on both sides by `mockserver-ui/src/__fixtures__/incompleteLogVerificationFailure.json`.

**Clearing the taint.** `reset()` and `clear(null)` reset both taint counters to zero (`clearDropTaint()`) and re-arm both warn-once latches (`droppedLogEventWarned`, `inFlightBytesDropWarned`), so a suite that clears or resets between tests starts each test with a clean slate. A filtered `clear(request)` deliberately does not reset the taint, since removing some entries says nothing about the evidence already lost by prior drops.

**Budget is recomputed per `applyConfigurationCapacity()`.** When `PUT /mockserver/configuration` changes `maxEventLogSizeInBytes` at runtime, `applyConfigurationCapacity()` sets the deque's `maxBytes` to it and re-derives `maxInFlightBytes` from it, so both bounds track the new value. A shrink applies to every subsequent publish; it cannot evict what is already in the ring (the ring is not resizable).

**Coalesced consumer wake-ups keep a small steady backlog.** The consumer is not woken for every entry (see [Consumer Wake-ups](event-system.md#consumer-wake-ups-coalescingwakewaitstrategy)): below the ceiling up to 10 ms of entries, typically around `min(256, ringSize / 4)` (the backlog at which a producer wakes the consumer, not a limit), can wait in the ring, so `mock_server_event_log_ring_occupancy` and `..._in_flight_bytes` read above zero on a healthy server under load and return to zero within 10 ms of traffic stopping. A publish that takes the in-flight bytes past a quarter of the in-flight cap wakes the consumer immediately, so coalescing cannot cause an in-flight byte-budget drop that prompt processing would have avoided.

**At `INFO`, the consumer itself can be the bottleneck, not just the two byte bounds.** A perf-programme daily run (build 607) logged at `INFO` dropped about 71% of its ~29.9M log events (none at `ERROR`, same run); we estimate this is consistent with the single consumer thread saturating around 8–10k requests/second on a 6-core SUT with the harness's four seeded expectations, where the request matches the last one (about six log entries per request: a diagnostic against each of the first three, the match, and the recorded request/response — more expectations checked before a match means more entries, so the onset rate is lower with more of them). The run's deque byte budget (`maxEventLogSizeInBytes`) did bind (`binding=bytes`, `bytes_utilisation=1`, `bound_reached=true`, 8,575,671 entries evicted) — eviction, not dropping, which `verify` and retrieval already treat as a loss. The in-flight cap could not plausibly bind during the small-body sweep where almost all the drops fell: 16,384 ring slots at a few KB each is far below that cap. That run predates the per-cause counter (`reason` label, added under item 76 of the performance plan), so its split between ring exhaustion and the in-flight cap is modelled from aggregate counts, not measured per rung (see the git history of `docs/plans/performance-programme.md`, closed 2026-10-09: items 40, 75 and 76, which covered the image-heap baseline shift, the per-rung signal and the per-cause counter, are all closed and removed from the plan). Perf builds 653 (scheduled) and 654 (manual), both on 2026-10-10, since confirmed the per-rung signal: at `INFO`, drops start at the 8,000 req/s rung, and about 99.7% of them are from ring exhaustion.

**The consumer is CPU-bound rendering each entry's message (perf build 694, 2026-10-18).** A JFR recording of an `INFO` SUT set up as the INFO arm's (image `929bb4c0a`, 2 GB container, cpus 0–5, `MOCKSERVER_DISABLE_SYSTEM_OUT=true`) under `sweep.js` at 2,000–16,000 req/s reproduced the onset: 45,397 drops by the end of the 8,000 req/s rung, all `ring_full`. The consumer thread `MockServer-EventLog0` used 0.82 of a CPU at 6,000 req/s (about 137 µs per request) and 0.97–0.99 of a CPU, its rung means, from 8,000 req/s (`jdk.ThreadCPULoad`). Of its CPU-time samples on the 8,000–16,000 req/s rungs (`jdk.CPUTimeSample`, 8,817 samples):

| Inclusive share | Where |
|---|---|
| 89.8% | `MockServerLogger.writeToSystemOut` |
| 82.6% | `StringFormatter.formatLogMessage` (rendering the message) |
| 75.5% | `StringFormatter.indentAndToString` |
| 37.9% | `ObjectWithJsonToString.toString`: Jackson pretty-printing the request or response (24.1% in `HttpRequestSerializer`) |
| 34.9% | `Matcher.replaceAll` for the `(?m)^` indent pattern (about 27% self time in its caret/start matching; the `{}` placeholder split adds 1.6%) |
| 5.5% | the SLF4J `logger.info` call itself, including `JDK14LoggerAdapter.fillCallerData` |

The consumer took no monitor for 1 ms or more, so this is CPU, not contention. JFR's sampled allocation on the consumer over the whole recording follows the same split: 83.9% under `formatLogMessage`, 34.9% under the Jackson `toString`, 17.6% under the indent regex and 8.2% under `fillCallerData`, whose stack-trace walk builds a caller for a record no handler prints when `disableSystemOut` is set. The rendered text is discarded in this configuration: `configureLogger` installs no console handler when `disableSystemOut` is true, yet `writeToSystemOut` still rendered and logged the message (no longer, since row 126b; see below). With the console on, as in the shipped default, the consumer also writes every message, so its cost per entry there is likely higher; that was not measured.

Before row 126b the console took the String path only: `writeToSystemOut` called `RedactedView.getMessage`, which rendered through `StringFormatter.formatLogMessage` (each argument's Jackson `toString`, then the indent). A streaming renderer already existed. `LogEntry.writeMessage` calls `StringFormatter.writeLogMessage`, which serialises each argument straight into a `LineIndentingWriter` with the same multiline-`^` semantics (`StringFormatterWriteLogMessageTest`, `StringFormatterRegexDifferentialTest`). Only the plain-text `LOGS` retrieve uses it (`HttpState.writeLogs`). The console does not, because SLF4J takes a `String`, the compact format has its own renderer, and `writeLogMessage` throws an `IOException` where `getMessage` falls back for an argument that cannot be serialised by its fields.

**The indent regex is gone (row 126a).** `indentAndToString` now indents each argument with a loop that has the same multiline-`^` semantics as `LineIndentingWriter` (before the first character and after each `\n`, `\r`, `\r\n`, U+0085, U+2028 or U+2029, but not after a terminator that ends the text). Output is unchanged byte for byte: a frozen corpus of 4,273 renderings (every argument shape the console sees, at indents 0–4, through `getMessage`, the redacted view, the serialised, compact and streamed forms) matched before and after, and `StringFormatterRegexDifferentialTest` compares the loop with the regex on 50,000 random strings over every terminator and the characters either side of its range checks. `LogMessageRenderBenchmark` puts a whole message at 25–37% faster with 35–46% less allocated (see [performance-measurement.md](performance-measurement.md#logmessagerenderbenchmark-jmh--on-demand-not-run-by-ci)). The Jackson serialisation, the other large share of the consumer's time, is unchanged.

**Unread messages are not rendered, and read ones stream their JSON (row 126b).** Two changes, both in mockserver-core:

- *Skip.* `MockServerLogger.writeToSystemOut` now asks `LogOutputProbe` whether anything would print the message before rendering it. It answers "nothing" only for SLF4J's no-operation logger (no provider on the classpath) and for a slf4j-jdk14 logger, relocated or not, whose java.util.logging logger is below the entry's level or reaches no handler through itself or the parents it passes records to, and only when every logger on that chain is the JDK's own class. A custom log manager's logger can print without handlers: log4j-jul's `ApiLogger`, used when `java.util.logging.manager` routes JUL to log4j2, has none, so a chain holding any other logger class is taken to print. That is the perf rig's configuration, because `disableSystemOut` makes `configureLogger` install no console handler. For any other SLF4J provider (Logback, log4j2's SLF4J binding and so on), a logger with a filter, or a logger it cannot inspect, it renders as before. Every other reader renders the message from the entry when it reads it, as it already did at `WARN`, where the consumer never rendered `INFO` entries: the `LOGS` retrieve (`LogEntry.writeMessage`), verification failures, listeners, and the dashboard, which builds its message parts from the format and arguments, not the rendered text. `getMessage` memoises with a racy but safe write: the memo is a `String` or an object with final fields, and two readers that race at worst both render. Memory is unchanged or lower, because a skipped entry decodes no body and memoises no message before `releaseDerivedForms`. `MockServerEventLogDiscardedConsoleTest` runs the same entries with and without a console handler and checks that the `LOGS` retrieve, a verification failure and a listener read identical text, and that without a handler the consumer renders nothing (`LogOutputProbeTest`, which also covers a JUL logger subclass that prints without handlers as log4j-jul's does, and `LogOutputProbeDisableSystemOutTest`).
- *Stream.* Where the text is needed, `formatLogMessage` and `indentAndToString` now serialise an argument whose `toString` is its JSON (`ObjectWithJsonToString`) straight into the message, through `LineIndentingWriter` over a reused `char[]` buffer (`MessageBuffer`), instead of building the pretty-printed `String` first. The message is built in a `char[]` that each thread reuses up to 16K characters (a plain array, so a servlet container's threads pin no MockServer class; a message formatted while it is in use gets its own), because a `StringBuilder` holding Latin-1 text copies an appended `char[]` one character at a time. If serialisation fails, what was written is removed and the argument's `toString` is used, which then shows its fields, as before. `LineIndentingWriter` and `UnquotingWriter` now pass each chunk Jackson writes in bulk instead of a character at a time, which the plain-text `LOGS` retrieve also gains from; `StringFormatterChunkedWritersTest` writes random text through both at every split point, by each of the `char[]`, `String` and single-character methods, because Jackson decides the chunks. Output is unchanged byte for byte: `StringFormatterStreamedJsonDifferentialTest` compares every entry point with the pre-change `toString`-then-indent implementation over 41 argument shapes (requests, responses, expectations, actions, verifications, lists, maps, strings with every line terminator, and arguments that format a message of their own while they are rendered) at indents 0–3 and 14 message layouts (4,592 renderings), plus `getMessage`, the redacted view and `writeMessage` with and without redaction, and 6,000 random raw and string JSON values up to 40,000 characters long. Those span several of Jackson's buffer flushes, so a `\r\n` pair or a terminator lands on each side of a chunk boundary. Two injected faults, one dropping the character held across a chunk and one forgetting the previous character at a chunk boundary, each failed it.

`LogMessageRenderBenchmark` measures both (one fork, JDK 17, developer Mac, so directional only). A console write to a logger that prints nothing now takes 4.3–4.6 ns and allocates nothing, against 0.96–8.8 µs and 3.4–23.7 KB when it renders, in every shape; that run used SLF4J's no-operation logger, so the java.util.logging handler walk is not timed. Rendering a message with its JSON streamed is 5–11% faster than row 126a's `toString`-then-indent and allocates 21–40% less, for example a `JSON` did-not-match entry 7.03 to 6.38 µs and 21.0 to 12.6 KB (see [performance-measurement.md](performance-measurement.md#logmessagerenderbenchmark-jmh--on-demand-not-run-by-ci)). The rig results follow the 126a table below.

**On the perf rig the drop onset moved from 8,000 to 10,000–12,000 req/s.** These are perf builds 700, 701, 703 and 704 (2026-10-19), run with the same `PART=jfr` sweep, two runs per arm on digest-pinned images. The after arm is the snapshot at `113c46cdf` and the before arm is build 694's image at `929bb4c0a`.

| | Before (701, 704) | After (700, 703) |
|---|---|---|
| Consumer CPU at 6,000 req/s | 0.78–0.79 | 0.62 |
| Consumer CPU at 8,000 req/s | 1.00 (saturated) | 0.78 |
| `ring_full` drops at the end of 8,000 | 31,953–43,302 | 0 |
| First `ring_full` drops | 8,000 rung | 10,000 rung (21,302) in 700; 12,000 rung (375,206) in 703 |
| `ring_full` drops by the end of 16,000 | 2.60–2.64 million | 1.45–1.47 million |
| Consumer CPU samples with `java.util.regex` under `indentAndToString` | 34.6–36.3% | 0% |
| Of which in the new indent loop (`appendIndented`) | — | 14.8% |

- **What the shares count.** Shares are the fraction of the consumer thread's `jdk.CPUTimeSample` stacks; build 694 reads 35.0% by the same count. About a third of CPU-time samples were lost in every run, so the 14.8% share is good to a few points; the 0% regex share is not affected.
- **Why this is the fix.** The arms differ by every commit from `929bb4c0a` to `113c46cdf`, including the `/7` `INFO` in-flight floor. No run dropped for in-flight bytes, so that floor did not bind, and the regex share falling to zero ties the gain to this change.
- **Machines.** Both after runs landed on one perf agent and the two before runs on two others. The 21% gap in consumer CPU at 6,000 req/s is far wider than the spread across the three before runs, including build 694 (0.78–0.82).

**With row 126b the consumer no longer saturates up to 16,000 req/s when `disableSystemOut` is set.** Perf builds 712–716 (2026-10-19) used the same `PART=jfr` sweep and the same SUT cpuset (`0-5`), with two runs per arm. The after arm was the master snapshot at `3d6875dda` (712, 715), and the before arm row 126a's image at `113c46cdf` (713, 716).

| | Before (713, 716) | After (712, 715) |
|---|---|---|
| Consumer CPU at 6,000 req/s | 0.62–0.65 | 0.04 |
| Consumer CPU, highest rung mean | 0.97–1.00 from 10,000 | 0.065 at 16,000 |
| First `ring_full` drops | 12,000 rung (349,038) in 713; 10,000 rung (32,380) in 716 | none up to 16,000 |
| `ring_full` drops by the end of 16,000 | 1.37–1.64 million | 0 |
| Peak ring occupancy (of 16,384) | 16,384 | 88–90 |
| Consumer CPU samples, whole run | 9,481–9,510 | 490–530 |
| Samples in `messageFor`, `formatLogMessage` or `indentAndToString` | 21–22% | 0% |
| p99 at 6,000 req/s | 0.597–0.654 ms | 0.605–0.622 ms |
| p99 at 10,000–12,000 req/s | 0.322–0.352 ms | 0.434–0.456 ms |
| p50 at 2,000–6,000 req/s | 0.131–0.258 ms | 0.160–0.283 ms |

- **What is left under `writeToSystemOut`.** That is 4.9–5.9% of the after arm's samples, all in `LogOutputProbe.discards` or `Configuration.disableLogging`.
- **Stack depth.** Before, 15% of the consumer's stacks end at `LogEntry.messageFor`, before its caller, so shares counted by caller are lower bounds. The CPU and drop figures do not depend on stacks.
- **Scope.** This covers only the skip. The SUT sets `MOCKSERVER_DISABLE_SYSTEM_OUT=true`, so streamed JSON, which applies only where the console prints, was not measured on the rig. With the console on (the shipped default) the consumer still renders every `INFO` entry.

## Eviction and GC

When the `CircularConcurrentLinkedDeque` reaches capacity (count or byte budget):

1. The oldest `LogEntry` is removed from the deque via an internal `pollAndEvict()`
2. `totalBytes` is decremented by the evicted entry's weight (before the callback, in case the callback clears the entry)
3. The `onEvictCallback` calls `LogEntry.clear()`, which nulls all reference fields
4. All child objects (HttpRequest, HttpResponse, Strings, etc.) become eligible for garbage collection
5. The `LogEntry` object itself is also GC-eligible (it is fully removed from the deque)

The LMAX Disruptor ring buffer pre-allocates `LogEntry` slots separately. Data is copied into ring buffer slots via `translateTo()`, then the consumer calls `cloneAndClear()` — creating a new `LogEntry` for persistent storage and clearing the ring buffer slot. Ring buffer slots do not contribute to persistent memory usage.

**O(1) capacity check (CPU).** The eviction check on every insert uses an internal `AtomicInteger` size counter, not `ConcurrentLinkedDeque.size()` (which is O(n) — it walks the whole list). This matters because the check runs on the hot path for every log entry: with the O(n) call, once the log was full each insert cost ~`O(maxLogEntries)` and CPU climbed as the log filled (GitHub issue #2329). With the counter, insert/evict/`size()` are O(1). If you change `CircularConcurrentLinkedDeque`, keep all mutators updating the counter (see its javadoc) so `size()` stays accurate.

**Eviction is announced once per server.** Eviction is silent by nature — an entry dropped is a `verify` that later fails to match — so `MockServerEventLog` emits a single WARN the first time the log evicts (latched per log instance via an `AtomicBoolean`, reset on `clear()`/`reset()`, so a busy recording proxy does not emit thousands of lines a second, and the string work stays off the steady-state path). The non-evicting hot path pays two volatile reads (the deque's eviction count and the count already reported to the `mock_server_evicted_log_entries` counter, which advances by the number of entries evicted — see [metrics.md](metrics.md#evicted-log-entries-counter)); no message is built unless the latch is armed. The message (`buildEvictionWarning`) names **which bound was hit** — the deque tracks byte-driven versus count-driven evictions (`getByteEvictedCount()`) — states its current value, and orders remedies cheapest-first, tailored to the bound:

- **Count bound hit:** the cheapest safe lever is to record less — lowering the log level drops the per-non-matching-expectation `EXPECTATION_NOT_MATCHED` diagnostics and cuts entries retained per request, and does **not** affect what `verify` can find, because `RECEIVED_REQUEST`/`EXPECTATION_RESPONSE`/`FORWARDED_REQUEST` (the types verification reads) are added to the log at **every** level (see `MockServerLogger.logEvent`). The same step offers `maxLoggedMatchFailures`, which caps those diagnostics per request while keeping the level. Then raise `maxLogEntries`, then clear/reset.
- **Byte bound hit:** the dominant cost is body bytes, which live on the always-retained entry types, so lowering the level will **not** free them — the message points at `maxLoggedBodyBytes` (truncate bodies) and `maxEventLogSizeInBytes` (raise/disable the budget) instead.

This complements `failVerificationOnEvictedLog` (default `true`), which makes upper-bound verifications (`never()`, `atMost(n)`, `exactly(n)`, `once()`, `between(a,b)`) **fail** rather than pass on discarded evidence — that failure message is likewise bound-aware, and names drops separately from evictions (see [Ring In-Flight Bounding and Drops](#ring-in-flight-bounding-and-drops)). The two together cover both halves of the eviction hazard: the WARN flags coverage loss at the moment it begins, and the fail-closed verify flags it again at the point a specific verification is undermined.

**A stopped server is not kept by process-wide registrations.** A starting server registers itself in
process-wide places: the live-state gauge readers in `Metrics` (expectations, event-log ring, expectation
bytes, cluster members, scheduler queues), the scenario manager of `CrossProtocolEventBus`, and, once it
has served a request, its request sender in `LoadScenarioOrchestrator` and `DriftAlertNotifier`. Each of
these refers back to the server's `HttpState`, and so to its whole event log and expectation store. On
`stop()` the server unregisters each of them, so a stopped server that nothing else references is
collected; a registration made since by another server in the same JVM is left alone. Each place is a
`MostRecentRegistration`: it keeps the registrations of the servers still running, in the order they
registered, uses the most recent, and when the one in use is unregistered goes back to the most recent of
the others instead of to none. So when a newer server stops, an older one still running gets its gauges,
its scenario state (captures, the `scenario` template helper, cross-protocol triggers) and its load and
drift-alert sender back without first accepting a new connection (`RunningServerKeepsItsRegistrationsTest`).
`DriftWiring.IN_EFFECT` (`org.mockserver.lifecycle`) registers only servers that turn a drift setting on (webhook,
LLM semantic analysis or p95 threshold); each entry holds that server's `Configuration` and, for semantic analysis,
its LLM client, and `LifeCycle` stop releases it (`LifeCycleDriftWiringTest`).
The registrations not in use are held weakly, so a server that is never stopped does not stay in those lists
once nothing else refers to it, and the fallback skips ones already collected. (An `HttpState` dropped without `stop()`
is still kept by its own event-log thread, which only `stop()` ends; `AbandonedHttpStateIsCollectedTest`
checks that, once that thread has ended, no process-wide registration keeps it.) A constructor that throws
part way leaves its caller nothing to stop, so it does the same release itself before rethrowing: it ends
the event-log thread, removes the registrations it had made and closes what it had opened, but does not
reset the process-wide AsyncAPI connections, which it never started (`HttpStateFailedConstructionTest`).
Tests in every module stop each `HttpState` they construct, and `HttpStateStoppedGuardTest` (in core,
scanning every module's test sources) fails the build when one does not. The in-flight reader given to `PreemptionSimulator` reads a counter, not the server, so
it holds nothing. `StoppedServerIsCollectedTest` guards this for the default in-memory state backend. With
a clustered backend the three chaos registries and the cross-protocol bus also hold a store of that backend,
which reaches the server through the invalidation listeners the server registered on it; each holds it in a
`MostRecentRegistration` too, so `stop()` lets go of it and an older clustered server still running gets
its own store back (`StoppedClusteredServerIsCollectedTest` in `mockserver-state-infinispan`).
The JSON schema validators compile each schema once per JVM, with a logger of their own, and hand each
caller a validator that logs to the caller's logger, so neither the first server's log nor the server is
kept by them (`JsonSchemaValidatorLoggerTest`). The load-scenario orchestrator keeps its most recent run for
status after it ends; when the server whose sender that run used stops, the run is let go
(`LoadScenarioOrchestratorTest`). An `HttpState` given a new request sender unregisters the one it replaced,
so after `stop()` the load-scenario and drift-alert senders cannot fall back to it, and once stopped it registers
none, as when a connection's first request arrives during the stop (`HttpStateRequestSenderTest`).
The tests' `EchoServer` upstream stops its own event log, and with it that log's thread, when it is stopped
(`EchoServerStopTest`).

In `mockserver-netty`'s unit-test fork, `ClearInlineMocksAfterEachTestClass` (a surefire listener set in
that module's pom) clears Mockito's inline mocks after each test class: a handler mocked into a real
pipeline records invocations whose arguments reach the mock itself, so Mockito would otherwise keep it, its
channel and the stopped server behind it until the fork ends.

**Clearing expectations does NOT clear the log.** `PUT /mockserver/clear?type=EXPECTATIONS` only clears stored expectations; the request/event log is independent and keeps its entries (bounded by `maxLogEntries` and `maxEventLogSizeInBytes`). To free the log, use `PUT /mockserver/clear?type=LOG` (or `?type=ALL`), or `PUT /mockserver/reset` (clears both). Long-running, high-throughput servers should either lower `maxLogEntries`, set a byte budget, or periodically clear the log.

## Expectation Memory Analysis

Stored expectations are heavier than they first appear because each expectation creates a matcher wrapper with sub-matchers for every field.

### Objects Per Stored Expectation

| Component | Typical Size | Notes |
|-----------|-------------|-------|
| `Expectation` shell | ~400 B | id, times, timeToLive, priority, 10 action refs |
| `HttpRequest` (matcher definition) | ~400-600 B | Usually just method + path, fewer headers than a real request |
| `HttpResponse` (response action) | ~500 B - 50+ KB | **Dominated by response body size** |
| `HttpRequestPropertiesMatcher` | ~900-1,200 B | Wrapper with sub-matchers for each field |
| Container overhead (CircularPriorityQueue) | ~350 B | ConcurrentLinkedQueue + ConcurrentSkipListSet + ConcurrentHashMap entries |
| **Total (simple, small response body)** | **~3.5 KB** | |
| **Total (medium, 2 KB response body)** | **~6-7 KB** | |
| **Total (large, 10 KB response body)** | **~15-20 KB** | |
| **Total (very large, 50 KB response body)** | **~55-75 KB** | |

The 75 KB per-expectation estimate in the default formula targets the worst case (large response bodies). For most workloads with small responses, it is 10-20x too conservative.

### Byte-Budget Eviction (`maxExpectationsSizeInBytes`)

`CircularPriorityQueue` supports an optional second bound alongside `maxExpectations`: a byte budget on the total estimated heap the stored expectations retain, weighed by `Expectation.estimatedHeapSize()`. It exists because the count cap cannot see how large an expectation is — and the dominant term is not the raw body but the **parsed JSON matcher tree**: `JsonStringMatcher.matcherJsonNode` is parsed once (lazily, on first match) and held for the expectation's life, typically 12-25x the raw JSON. `estimatedHeapSize()` estimates this from the raw bytes and body type (both fixed when the expectation is built), so the weight is stable between add-time and evict-time and never forces a parse.

Unlike the event log's byte budget, this one is **off by default (`0`) and opt-in**. Expectations are user-configured state, not observational data: evicting a log entry loses history, but evicting an expectation removes a mock the user deliberately registered. So MockServer never evicts expectations by size unless an operator sets `maxExpectationsSizeInBytes`. When set, whichever of `maxExpectations` or the byte budget is reached first evicts the oldest, lowest-priority expectations, announced once per server in the log. A reasonable starting point is about an eighth of the JVM heap (leaving room for the event log, Netty buffers and the working set).

### Removed-Expectation Definitions (verify / retrieve / clear by id)

An expectation id must stay resolvable after the expectation is gone: verifying a `Times.exactly(2)` expectation by id once it has served both responses is a documented, integration-tested feature (`AbstractControlPlaneIntegrationTest.shouldVerifyReceivedRequestsByExpectationId`), and `retrieve` / `clear` accept an id the same way. `RequestMatchers.retrieveRequestDefinitions` therefore resolves an id in this order: the live store (`httpRequestMatchers`), the backend (an expectation live on another cluster node), then `RetiredRequestDefinitions` — the request definitions of **removed** expectations only.

| Aspect | Behaviour |
|--------|-----------|
| What is kept | The `RequestDefinition` of an expectation removed by Times exhaustion, TTL expiry or an explicit clear (not the response, action or matcher) |
| Not kept | Live expectations (resolved from the store, so nothing is duplicated) and expectations **evicted** by `maxExpectations` / `maxExpectationsSizeInBytes` (unchanged: an evicted id does not resolve) |
| Count bound | `maxExpectations`, resized with it on `PUT /mockserver/configuration` |
| Byte bound | `heapAvailableInKB * 1024 / 16` (a sixteenth of the heap-ceiling budget), or 16 MB when the JVM reports no heap ceiling; weighed by `Expectation.estimatedRequestDefinitionHeapSize()` (body, headers, fixed overhead — no matcher-tree term, since no matcher remains) |
| Eviction | Longest-removed first; the most recently removed definition is kept even if it alone exceeds the byte budget |
| Cleared by | `reset`; re-adding an id drops its retired copy |

**Why bytes and not only count.** The previous `CircularHashMap<String, RequestDefinition>` was sized to `maxExpectations` and held every definition ever added until reset, with no byte bound: a suite that churns through times-limited expectations with large request bodies kept up to `maxExpectations` of them (15,000 at the default cap, e.g. 15,000 x 1 MB) after the expectations themselves were gone. **Rejected alternative:** dropping the definition on removal — it breaks verify-by-id for a used-up expectation. **Rejected alternative:** keeping it only while the event log still references the id — verification matches the definition against logged requests, not log entries tagged with the id, so there is no reference to track.

## OpenAI Responses Store

`OpenAiResponsesStore` keeps each `OPENAI_RESPONSES` turn, streamed or not, so a later request can chain to it with `previous_response_id` or fetch it with `GET /v1/responses/{id}`. It has two bounds, and whichever is reached first evicts the least recently used response: a count of 10,000 and an estimated byte budget, `maxOpenAiResponsesSizeInBytes`, on by default at `(heapAvailableInKB / 20) * 1024`. It is not covered by `maxLogEntries`, `maxExpectations` or their byte budgets.

| Aspect | Behaviour |
|--------|-----------|
| What an entry holds | The response body (one JSON string; for a streamed turn, the response object of its `response.completed` event) and the decoded conversation up to that turn |
| What drives its size | The body echoes the request's `tools`, `instructions` and `metadata`, so an entry is at least as large as the tool definitions the client sent with that turn; large tool outputs in the input add to it |
| Count bound | 10,000 (`OpenAiResponsesStore.MAX_RESPONSES`), not configurable |
| Byte bound | `maxOpenAiResponsesSizeInBytes`: default a twentieth of the heap ceiling budget (the share the event log keeps at `WARN`), `0` when the heap ceiling is undefined or when set to `0` (count bound only). Resized by `PUT /mockserver/configuration`; a lower value evicts at once |
| Weigher | `OpenAiResponsesStore.estimatedSize`: 256 bytes per entry plus the body's characters, 8 bytes per conversation message, and for each message the entry adds (not ones shared with the turn it chains from) 128 bytes plus its text, tool-call and tool-result characters |
| Oversized entry | The most recently stored response is kept even when it alone exceeds the budget, so the turn a client chains from next is never the one evicted |
| Not stored | A turn whose request sets `store:false` |
| Cleared by | `reset` |
| Scope | One store per JVM; each `HttpState` applies its server's budget when it starts and on a configuration update, so with several servers in one JVM the last one applied wins |

A chained turn's decoded conversation reuses the prior turn's message objects, so the weigher charges each message once, to the turn that added it. Once that turn is evicted, a later turn that still references its messages keeps them reachable without being charged for them: the budget can under-count by at most the conversation history of the chains still stored. The body, which carries the echoed tool definitions and usually dominates, is always charged in full.

Before the byte bound, an agent that sends, say, 50 KB of tool definitions on every turn retained about 50 KB per stored turn, about 500 MB if the store filled to its count cap. With the default budget at `-Xmx1g` (about 50 MB) such an agent keeps roughly its last 1,000 turns.

## How the Estimates Were Chosen

The per-entry estimates (8 KB for log entries, 10 KB for expectations) are based on the field-level analysis in the sections above. They target the **realistic weighted average** for typical API mocking workloads (small-to-medium JSON bodies, a handful of headers), with a modest safety margin.

| Metric | Estimate Used | Realistic Average | Worst Case |
|--------|--------------|-------------------|------------|
| Per log entry | 8 KB | 6-8 KB | 30+ KB (large bodies) |
| Per expectation | 10 KB | 3.5-7 KB | 75+ KB (huge response bodies) |

For workloads with very large request/response bodies (>10 KB), the automatic defaults may over-provision. Users with such workloads should set explicit values using the tuning guide below, and monitor memory via `outputMemoryUsageCsv`.

**History:** Prior to this change, the estimates were 80 KB per log entry and 75 KB per expectation — values that were 10-16x too conservative for typical workloads, resulting in unnecessarily low limits and unexpected log eviction (GitHub issue [#1285](https://github.com/mock-server/mockserver-monorepo/issues/1285)).

## Tuning Guide

### Configuration Properties

| Property | System Property | Environment Variable | Default |
|----------|----------------|---------------------|---------|
| Max log entries | `mockserver.maxLogEntries` | `MOCKSERVER_MAX_LOG_ENTRIES` | `min(heapAvailableKB / 8, 250000)` |
| Max event log size (bytes) | `mockserver.maxEventLogSizeInBytes` | `MOCKSERVER_MAX_EVENT_LOG_SIZE_IN_BYTES` | `(heapAvailableKB / 12) * 1024` at `INFO`/`DEBUG`/`TRACE`; `(heapAvailableKB / 20) * 1024` at `WARN`/`ERROR`/`OFF` (see formula above; `0` only when heap ceiling is undefined). Also sets a floor under the derived in-flight cap |
| Max logged body bytes | `mockserver.maxLoggedBodyBytes` | `MOCKSERVER_MAX_LOGGED_BODY_BYTES` | `0` (unlimited) |
| Ring buffer size | `mockserver.ringBufferSize` | `MOCKSERVER_RING_BUFFER_SIZE` | `min(maxLogEntries, 16384)` (rounded up to a power of two) |
| Max expectations | `mockserver.maxExpectations` | `MOCKSERVER_MAX_EXPECTATIONS` | `min(heapAvailableKB / 10, 15000)` |
| Max pending delayed responses | `mockserver.maxPendingDelayedResponses` | `MOCKSERVER_MAX_PENDING_DELAYED_RESPONSES` | `min(heapAvailableKB / 64, 100000)`; `0` = unbounded |
| Max queued template actions | `mockserver.maxQueuedTemplateActions` | `MOCKSERVER_MAX_QUEUED_TEMPLATE_ACTIONS` | `min(heapAvailableKB / 64, 100000)`; `0` = unbounded |
| Max OpenAI Responses store size (bytes) | `mockserver.maxOpenAiResponsesSizeInBytes` | `MOCKSERVER_MAX_OPEN_AI_RESPONSES_SIZE_IN_BYTES` | `(heapAvailableKB / 20) * 1024`; `0` = count bound (10,000) only (see [OpenAI Responses Store](#openai-responses-store)) |

The last two bound transient, not retained, memory: requests held while waiting for a delay or a template
thread, each with its request, response writer and channel. The 64 KB per entry is an unmeasured,
deliberately pessimistic estimate chosen so the bound holds well under the heap ceiling for ordinary
request sizes; a request with a large body retains more. The 1,000 floor applies only when the JVM reports
no heap ceiling. Delayed side actions and WebSocket bidi reply frames each have their own budget of the same
size, so the worst case is bounded delayed tasks up to three times `maxPendingDelayedResponses` (plus the frames
of one reply set, which is admitted whole), plus up to `maxQueuedTemplateActions` queued renders, plus one
timed transition per scenario, plus the delays that are counted but not bounded (chained SSE/WebSocket/gRPC
messages and close-socket delays, one per stream or connection). Over a limit a request is answered `503`
instead of being held, and a WebSocket reply set is refused whole by closing the socket with `1013` (see
[request-processing.md](request-processing.md#overload-bounds-on-delayed-and-templated-actions)).

Two per-connection bounds use backpressure instead of a limit, by pausing the connection's reads so TCP flow
control slows the client: a WebSocket bidi connection with more than 128 delayed reply sets pending, and a
connection under TCP chaos latency or bandwidth with more than 64 KiB of inbound data queued. Neither is
configurable; each holds at most its threshold plus what one socket read (64 KiB) decodes to.

Properties are resolved in this order (first match wins):

1. In-memory property cache (set programmatically)
2. Java system property (`-Dmockserver.maxLogEntries=...`)
3. Properties file (`mockserver.properties`)
4. Environment variable (`MOCKSERVER_MAX_LOG_ENTRIES`)
5. Computed default

### Choosing Values

**Step 1: Estimate your per-entry memory**

| Your workload | Estimated per-entry size | Entries per HTTP request |
|---------------|------------------------|------------------------|
| Small API responses (< 1 KB bodies) | 5-8 KB | 2-3 |
| Medium API responses (1-5 KB bodies) | 8-15 KB | 2-3 |
| Large API responses (5-50 KB bodies) | 15-50 KB | 2-3 |
| Proxy mode (request + response stored) | 10-30 KB | 2 |

**Step 2: Calculate how many entries your heap can support**

```
available_heap_MB = Xmx - (estimated_used_heap_MB + 50 MB safety margin)
entries = (available_heap_MB * 1024) / per_entry_KB
```

**Step 3: Account for entries per HTTP request**

```
http_requests_retained = entries / entries_per_request
```

**Step 4: Leave room to read the log back**

A `retrieve` builds its whole response in memory, once, as the bytes it writes (since 9.0.0; before, it also held the serialiser's buffers and a `String` of it), and the response is several times the size of the bodies it reports (see [Retrieve Response Size](event-system.md#retrieve-response-size)). A log that fits the heap can still be too large to retrieve in one call: MockServer then answers `500` with `the retrieve response is too large to build in memory`. Retrieve with a request matcher, or cap what each entry keeps with `maxLoggedBodyBytes`, rather than sizing the heap for a whole-log retrieve. <!-- version-ref:keep -->

### Examples

**Docker container, 256 MB heap, small API mocking:**
```
available = 256 - (80 + 50) = 126 MB = 129,024 KB
per_entry = 8 KB (typical small API)
max_log_entries = 129,024 / 8 = ~16,000
http_requests_retained = 16,000 / 3 = ~5,300
```

Set: `MOCKSERVER_MAX_LOG_ENTRIES=16000`

**Docker container, 512 MB heap, medium API responses:**
```
available = 512 - (150 + 50) = 312 MB = 319,488 KB
per_entry = 12 KB
max_log_entries = 319,488 / 12 = ~26,000
```

Set: `MOCKSERVER_MAX_LOG_ENTRIES=26000`

**Large test suite, 2 GB heap, needs to retain all requests:**
```
available = 2048 - (400 + 50) = 1,598 MB = 1,636,352 KB
per_entry = 8 KB
max_log_entries = 1,636,352 / 8 = ~204,000
```

Set: `MOCKSERVER_MAX_LOG_ENTRIES=200000`

### Memory Monitoring

Enable CSV memory tracking to observe actual memory usage under your workload:

```properties
mockserver.outputMemoryUsageCsv=true
mockserver.memoryUsageCsvDirectory=/tmp/mockserver-metrics
```

This writes a `memoryUsage_YYYY-MM-DD.csv` file every 50 log or expectation updates with columns including `eventLogSize`, `maxLogEntries`, `heapUsed`, and `heapMaxAllowed`. Use this to validate that your `maxLogEntries` setting is appropriate for your heap size.

### Ring Buffer Sizing

The LMAX Disruptor ring buffer is the **in-flight** buffer between the producing Netty I/O threads and
the single consumer thread. It is **decoupled** from `maxLogEntries` (which bounds the separate retained
event history in the `CircularConcurrentLinkedDeque`). The ring only needs to absorb short *bursts* of
log events, not hold the full retained history, so it has its own knob, `ringBufferSize`.

Its size is computed as the next power of two greater than the resolved `ringBufferSize`, which defaults
to `min(maxLogEntries, 16384)`:

| maxLogEntries | Resolved ringBufferSize (default) | Ring Buffer Size (power of two) | Ring Buffer Memory (empty LogEntry shells) |
|---------------|-----------------------------------|---------------------------------|-------------------------------------------|
| 1,000 | 1,000 | 1,024 | ~115 KB |
| 5,000 | 5,000 | 8,192 | ~920 KB |
| 10,000 | 10,000 | 16,384 | ~1.8 MB |
| 50,000 | 16,384 (capped) | 16,384 | ~1.8 MB |
| 250,000 | 16,384 (capped) | 16,384 | ~1.8 MB |

Before this decoupling, `maxLogEntries=250000` forced a 262,144-slot ring (~29 MB of empty `LogEntry`
shells) purely as a side effect of retention sizing; the default 16,384 ceiling caps that at ~1.8 MB
while leaving small deployments (maxLogEntries ≤ 16,384) unchanged.

The ring buffer pre-allocates `LogEntry` objects (just the shells, ~112 bytes each). These are reused via
`translateTo()` / `cloneAndClear()` and do not hold persistent data. The ring buffer memory is a fixed
overhead that does not grow with request volume.

#### Why a separate `ringBufferSize` knob?

The ring buffer absorbs the **rate gap** between producers and the single consumer thread — it must be
large enough that bursts of concurrent log writes do not overflow it (an overflow drops the event and
increments `mock_server_dropped_log_events{reason="ring_full"}`; see [event-system.md](event-system.md)). That gap is a
function of *throughput*, not of *how long you retain history*. Slaving the ring to `maxLogEntries`
therefore over-provisioned the ring for high-retention/low-burst deployments.

- **Default `min(maxLogEntries, 16384)`** — small deployments keep their previous ring exactly; large
  retention settings stop inflating the ring.
- **Raise it** only to absorb short bursts (`mock_server_dropped_log_events{reason="ring_full"}` rising
  in spikes). Sustained ring-full drops at `INFO` mean the single consumer cannot keep up at any ring
  size; lower the log level instead.
- **Lower it** to shave fixed memory if you have a low-throughput, high-retention workload.

Configure it via `mockserver.ringBufferSize`, the `MOCKSERVER_RING_BUFFER_SIZE` environment variable, or
`Configuration.ringBufferSize(int)`. The value is rounded up to the next power of two (a Disruptor
requirement). The `nextPowerOfTwo()` method in `Configuration.java` supports values up to
`2^30 = 1,073,741,824`.
