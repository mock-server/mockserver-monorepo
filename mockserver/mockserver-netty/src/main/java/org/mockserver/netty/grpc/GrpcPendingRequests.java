package org.mockserver.netty.grpc;

import io.netty.channel.Channel;
import io.netty.util.Attribute;
import io.netty.util.AttributeKey;
import io.netty.util.concurrent.ScheduledFuture;
import org.mockserver.log.model.LogEntry;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.netty.unification.PortUnificationHandler;
import org.slf4j.event.Level;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Per-connection record of the gRPC service/method resolved from each in-flight request, so that
 * {@link GrpcToHttpResponseHandler} can re-frame the matched response as protobuf of the correct
 * output type.
 * <p>
 * <strong>Why this is keyed by HTTP/2 stream id.</strong> Both gRPC handlers are
 * {@code @ChannelHandler.Sharable}, so the state cannot live in a field and must hang off the
 * channel. Keying by stream id is what makes the {@code @Sharable} handlers safe regardless of how
 * many streams share a {@code ctx.channel()}:
 * <ul>
 *   <li>Since issue #2669 every HTTP/2 stream gets its own child channel
 *       ({@link org.mockserver.netty.unification.Http2MultiplexChildInitializer}), so
 *       {@code ctx.channel()} is already per-stream — but each request still carries its stream id,
 *       so keying by it stays correct and needs no special case.</li>
 *   <li>gRPC-Web runs over HTTP/1.1, where there is no stream id and {@code ctx.channel()} is the
 *       shared connection — handled by the single-slot fallback below.</li>
 * </ul>
 * A naive single-slot channel attribute would be <em>wrong</em> on any pipeline that carries more
 * than one exchange on a channel: concurrent (or pipelined) calls overwrite each other, so only the
 * last-recorded pair survives and only one response is converted. Worse, with two <em>different</em>
 * RPCs in flight a response can be converted against the other method's output type, turning a valid
 * response into {@code grpc-status: 13 INTERNAL}.
 * <p>
 * Requests carry {@link org.mockserver.model.HttpRequest#getStreamId()} on HTTP/2 (set in
 * {@code FullHttpRequestToMockServerHttpRequest}, and only when the protocol really is HTTP/2 so
 * an HTTP/1.1 client cannot forge it), and {@code ResponseWriter.writeResponse} copies it onto the
 * response -- so the stream id is available on both sides and is the correct key.
 * <p>
 * <strong>HTTP/1.1 fallback.</strong> There is no stream id, so a single slot is used, consumed
 * and cleared on use. This is correct for the normal case, where a client waits for each response
 * before sending the next request.
 * <p>
 * The slot is <strong>single-shot</strong>: if a second request is recorded before the first
 * response has consumed the slot, the slot is marked ambiguous and {@link #consume} returns
 * {@code null} instead of a record. Netty keeps reading after {@code channelRead} returns, so with
 * HTTP/1.1 pipelining and an asynchronous action (a response delay, a forward) a second request
 * can genuinely be decoded before the first response is written. Without the ambiguity flag the
 * second record would overwrite the first, and the first response would then be converted against
 * the <em>second</em> request's method -- producing a wrong-typed message or a fabricated
 * {@code grpc-status: 13 INTERNAL}. Refusing to convert is strictly safer: the response goes out
 * unconverted (the pre-#2419 behaviour, visible and debuggable) rather than silently wrong. The
 * ambiguity is logged at WARN and clears on the next consume, so it cannot wedge the connection.
 * <p>
 * <strong>Bounding.</strong> An exchange can be abandoned without ever reaching
 * {@link GrpcToHttpResponseHandler#encode} -- an {@code HttpError} drop-connection action, an
 * unreleased request-phase breakpoint, or an exception before the response is written. The
 * attribute dies with the connection, but a long-lived HTTP/2 connection could accumulate many
 * such entries, so the map evicts in insertion order beyond {@link #MAX_PENDING_STREAMS}.
 * <p>
 * <strong>Eviction must never discard a LIVE stream</strong> -- that would silently reintroduce
 * issue #2419 under load, since a stream whose record was evicted skips conversion and goes out as
 * raw JSON. The bound is therefore not a guess about "realistic" concurrency: it is derived from
 * the concurrent-stream limit MockServer itself advertises and Netty enforces, so live eviction is
 * structurally unreachable rather than merely unlikely. See
 * {@link org.mockserver.netty.unification.PortUnificationHandler#HTTP2_MAX_CONCURRENT_STREAMS}.
 * <p>
 * That limit is advertised <strong>explicitly</strong> by MockServer rather than inherited from
 * Netty's default, precisely because the default is not stable: in Netty 4.1
 * {@code Http2Settings.defaultSettings()} is {@code maxHeaderListSize} only (no concurrent-stream
 * limit, so RFC 9113 permits unbounded streams), while 4.2 added
 * {@code maxConcurrentStreams(SMALLEST_MAX_CONCURRENT_STREAMS)} = 100. Depending on that default
 * would make this class's correctness a silent function of the Netty version.
 */
final class GrpcPendingRequests {

    private static final MockServerLogger MOCK_SERVER_LOGGER = new MockServerLogger(GrpcPendingRequests.class);

    /**
     * Upper bound on retained per-stream records, with headroom above the advertised
     * concurrent-stream limit so that reserved/half-closed streams cannot push a live record out.
     * The relationship to the advertised limit is pinned by
     * {@code GrpcToHttpResponseHandlerTest.shouldSizeThePendingBoundAboveTheAdvertisedStreamLimit}
     * so the two cannot drift apart.
     */
    static final int MAX_PENDING_STREAMS = PortUnificationHandler.HTTP2_MAX_CONCURRENT_STREAMS * 2;

    static final AttributeKey<GrpcPendingRequests> ATTRIBUTE =
        AttributeKey.valueOf("MOCKSERVER_GRPC_PENDING_REQUESTS");

    /**
     * A recorded in-flight request: the resolved service/method, plus the {@code grpc-timeout}
     * deadline task (if the client sent one) so it can be cancelled the moment the response is
     * written and cannot leak past the exchange.
     */
    static final class PendingRequest {
        private final String[] serviceMethod;
        /**
         * The original gRPC-Web request content-type ({@code application/grpc-web[-text][+proto]}),
         * or {@code null} for a standard gRPC request. Carried here because a browser gRPC-Web
         * client cannot read HTTP trailers -- {@code grpc-status} must be re-framed into the body --
         * and the matched-expectation response does not otherwise retain the request's
         * {@code x-grpc-web-content-type} marker. See {@link GrpcToHttpResponseHandler#encode}.
         */
        private final String grpcWebContentType;
        private ScheduledFuture<?> deadlineFuture;

        PendingRequest(String serviceName, String methodName, String grpcWebContentType) {
            this.serviceMethod = new String[]{serviceName, methodName};
            this.grpcWebContentType = grpcWebContentType;
        }

        String[] serviceMethod() {
            return serviceMethod;
        }

        String grpcWebContentType() {
            return grpcWebContentType;
        }

        void deadlineFuture(ScheduledFuture<?> deadlineFuture) {
            this.deadlineFuture = deadlineFuture;
        }

        void cancelDeadline() {
            if (deadlineFuture != null) {
                deadlineFuture.cancel(false);
                deadlineFuture = null;
            }
        }
    }

    private final Map<Integer, PendingRequest> byStreamId = new LinkedHashMap<Integer, PendingRequest>() {
        @Override
        protected boolean removeEldestEntry(Map.Entry<Integer, PendingRequest> eldest) {
            boolean evict = size() > MAX_PENDING_STREAMS;
            if (evict) {
                eldest.getValue().cancelDeadline();
                // Reaching here means the advertised concurrent-stream limit was not enforced as
                // expected, so this MAY be discarding a live stream -- which would make that
                // stream's response skip gRPC conversion and go out as raw JSON (issue #2419).
                // Log it so the condition is diagnosable instead of silent.
                MOCK_SERVER_LOGGER.logEvent(
                    new LogEntry()
                        .setLogLevel(Level.WARN)
                        .setMessageFormat("evicting pending gRPC request record for stream {} after exceeding {}"
                            + " retained streams on one connection; if this stream is still open its response"
                            + " will not be converted to gRPC - please raise an issue")
                        .setArguments(eldest.getKey(), MAX_PENDING_STREAMS)
                );
            }
            return evict;
        }
    };

    /**
     * HTTP/1.1 (no stream id) slot. Not part of {@link #byStreamId} so a null key never collides
     * with a real stream id.
     */
    private PendingRequest withoutStreamId;

    /**
     * Set when a second no-stream-id record arrives before the first is consumed, so neither can be
     * safely attributed to a response. See the class javadoc.
     */
    private boolean withoutStreamIdAmbiguous;

    /**
     * Streams already terminated with DEADLINE_EXCEEDED, whose late response must be suppressed.
     * Bounded like {@link #byStreamId} so an abandoned entry cannot accumulate.
     */
    private final Set<Integer> deadlineExceededStreamIds = Collections.newSetFromMap(
        new LinkedHashMap<Integer, Boolean>() {
            @Override
            protected boolean removeEldestEntry(Map.Entry<Integer, Boolean> eldest) {
                return size() > MAX_PENDING_STREAMS;
            }
        });

    private boolean deadlineExceededWithoutStreamId;

    /**
     * Returns the registry for this channel <strong>without creating one</strong>, or {@code null}
     * if no gRPC request has been recorded on it.
     * <p>
     * Used on the outbound path, which runs for every response on a gRPC-enabled server including
     * connections that never carry a gRPC request; creating a registry there would allocate a
     * {@code LinkedHashMap} per connection for nothing.
     */
    static GrpcPendingRequests existingForChannel(Channel channel) {
        return channel.attr(ATTRIBUTE).get();
    }

    /**
     * Returns the registry for this channel, creating it on first use. Safe against a racing
     * creation via {@link Attribute#setIfAbsent}.
     */
    static GrpcPendingRequests forChannel(Channel channel) {
        Attribute<GrpcPendingRequests> attribute = channel.attr(ATTRIBUTE);
        GrpcPendingRequests pendingRequests = attribute.get();
        if (pendingRequests == null) {
            pendingRequests = new GrpcPendingRequests();
            GrpcPendingRequests existing = attribute.setIfAbsent(pendingRequests);
            if (existing != null) {
                pendingRequests = existing;
            }
        }
        return pendingRequests;
    }

    /**
     * Records the service/method decoded from a request. Reads and writes both happen on the
     * channel's event loop, but the methods are synchronized so the registry cannot be corrupted
     * if a future pipeline change writes a response from another thread.
     */
    synchronized PendingRequest record(Integer streamId, String serviceName, String methodName) {
        return record(streamId, serviceName, methodName, null);
    }

    /**
     * As {@link #record(Integer, String, String)}, additionally retaining the original gRPC-Web
     * request content-type so {@link GrpcToHttpResponseHandler#encode} can re-frame the matched
     * response as gRPC-Web (in-body trailer frame) rather than leaving {@code grpc-status} in HTTP
     * trailers that no browser gRPC-Web client can read.
     */
    synchronized PendingRequest record(Integer streamId, String serviceName, String methodName, String grpcWebContentType) {
        PendingRequest serviceMethod = new PendingRequest(serviceName, methodName, grpcWebContentType);
        if (streamId == null) {
            if (withoutStreamId != null) {
                withoutStreamIdAmbiguous = true;
                MOCK_SERVER_LOGGER.logEvent(
                    new LogEntry()
                        .setLogLevel(Level.WARN)
                        .setMessageFormat("a second gRPC request ({}/{}) was decoded on an HTTP/1.1 connection"
                            + " before the previous response was written (pipelining); neither response can be"
                            + " safely attributed to a method, so both will be returned unconverted")
                        .setArguments(serviceName, methodName)
                );
            }
            if (withoutStreamId != null) {
                withoutStreamId.cancelDeadline();
            }
            withoutStreamId = serviceMethod;
        } else {
            byStreamId.put(streamId, serviceMethod);
        }
        return serviceMethod;
    }

    /**
     * Removes and returns the record for this stream, or {@code null} if there is none.
     * <p>
     * The returned array is {@code {service, method, grpcWebContentType}}: element 2 is the original
     * gRPC-Web request content-type (or {@code null} for standard gRPC), so the response handler can
     * re-frame a matched-expectation response as gRPC-Web.
     */
    synchronized String[] consume(Integer streamId) {
        PendingRequest pendingRequest;
        if (streamId == null) {
            pendingRequest = withoutStreamId;
            withoutStreamId = null;
            if (withoutStreamIdAmbiguous) {
                // two or more requests were in flight with no way to tell them apart -- refuse to
                // convert rather than risk converting against the wrong method
                withoutStreamIdAmbiguous = false;
                if (pendingRequest != null) {
                    pendingRequest.cancelDeadline();
                }
                return null;
            }
        } else {
            pendingRequest = byStreamId.remove(streamId);
        }
        if (pendingRequest == null) {
            return null;
        }
        // the exchange is answered -- the deadline can no longer fire, so the timer must not
        // outlive it
        pendingRequest.cancelDeadline();
        String[] serviceMethod = pendingRequest.serviceMethod();
        return new String[]{serviceMethod[0], serviceMethod[1], pendingRequest.grpcWebContentType()};
    }

    /**
     * Discards the HTTP/1.1 slot. Called when a non-gRPC request arrives on the connection, so a
     * record left behind by an abandoned gRPC exchange cannot be applied to an unrelated later
     * response (for example a control-plane JSON response on the same port).
     */
    synchronized void clearWithoutStreamId() {
        if (withoutStreamId != null) {
            withoutStreamId.cancelDeadline();
        }
        withoutStreamId = null;
        withoutStreamIdAmbiguous = false;
    }

    /**
     * Claims the exchange for its elapsed deadline. Returns {@code true} only if the exchange was
     * still in flight, so exactly one of "the response was written" and "the deadline fired" wins
     * even though both run on the channel event loop.
     * <p>
     * The stream is then remembered as deadline-exceeded so that a response arriving later -- the
     * {@code Delay} that outran the client's deadline -- is DROPPED rather than written as a second
     * response on a stream that already carries terminal trailers.
     */
    synchronized boolean claimForDeadline(Integer streamId) {
        PendingRequest pendingRequest;
        if (streamId == null) {
            pendingRequest = withoutStreamId;
            withoutStreamId = null;
            withoutStreamIdAmbiguous = false;
        } else {
            pendingRequest = byStreamId.remove(streamId);
        }
        if (pendingRequest == null) {
            return false;
        }
        pendingRequest.cancelDeadline();
        if (streamId == null) {
            deadlineExceededWithoutStreamId = true;
        } else {
            deadlineExceededStreamIds.add(streamId);
        }
        return true;
    }

    /**
     * Returns (and clears) whether this stream already terminated with DEADLINE_EXCEEDED, so its
     * late response must be suppressed.
     */
    synchronized boolean consumeDeadlineExceeded(Integer streamId) {
        if (streamId == null) {
            boolean exceeded = deadlineExceededWithoutStreamId;
            deadlineExceededWithoutStreamId = false;
            return exceeded;
        }
        return deadlineExceededStreamIds.remove(streamId);
    }

    /**
     * Cancels every outstanding deadline. Called when the connection goes inactive so timers cannot
     * outlive the channel that scheduled them.
     */
    synchronized void cancelAllDeadlines() {
        for (PendingRequest pendingRequest : byStreamId.values()) {
            pendingRequest.cancelDeadline();
        }
        byStreamId.clear();
        if (withoutStreamId != null) {
            withoutStreamId.cancelDeadline();
            withoutStreamId = null;
        }
        deadlineExceededStreamIds.clear();
        deadlineExceededWithoutStreamId = false;
    }

    /**
     * Number of retained per-stream records. Test visibility for the bounding behaviour.
     */
    synchronized int pendingStreamCount() {
        return byStreamId.size();
    }
}
