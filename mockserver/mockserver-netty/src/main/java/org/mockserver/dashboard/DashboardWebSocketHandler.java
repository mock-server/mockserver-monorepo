package org.mockserver.dashboard;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectWriter;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableMap;
import io.netty.channel.*;
import io.netty.handler.codec.http.DefaultHttpHeaders;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.QueryStringDecoder;
import io.netty.handler.codec.http.websocketx.*;
import io.netty.util.AttributeKey;
import io.netty.util.ReferenceCountUtil;
import io.netty.buffer.Unpooled;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpVersion;
import org.mockserver.collections.CircularHashMap;
import org.mockserver.dashboard.model.DashboardLogEntryDTO;
import org.mockserver.dashboard.model.DashboardLogEntryDTOGroup;
import org.mockserver.dashboard.serializers.*;
import org.mockserver.configuration.Configuration;
import org.mockserver.log.MockServerEventLog;
import org.mockserver.log.model.LogEntry;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.mappers.FullHttpRequestToMockServerHttpRequest;
import org.mockserver.mappers.Http2StreamIds;
import org.mockserver.matchers.HttpRequestMatcher;
import org.mockserver.matchers.Times;
import org.mockserver.mock.Expectation;
import org.mockserver.mock.HttpState;
import org.mockserver.mock.RequestMatchers;
import org.mockserver.mock.listeners.MockServerLogListener;
import org.mockserver.mock.listeners.MockServerMatcherListener;
import org.mockserver.mock.listeners.MockServerMatcherNotifier;
import org.mockserver.model.HttpRequest;
import org.mockserver.model.OpenAPIDefinition;
import org.mockserver.model.RequestDefinition;
import org.mockserver.serialization.HttpRequestSerializer;
import org.mockserver.serialization.ObjectMapperFactory;
import org.mockserver.socket.tls.SniHandler;
import org.mockserver.serialization.model.ExpectationDTO;
import org.slf4j.event.Level;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static com.google.common.net.HttpHeaders.HOST;
import static io.netty.handler.codec.http.HttpHeaderNames.CONTENT_LENGTH;
import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.apache.commons.lang3.StringUtils.isNotBlank;
import static org.mockserver.exception.ExceptionHandling.connectionClosedException;
import static org.mockserver.exception.ExceptionHandling.isSslOrDecoderFault;
import static org.mockserver.exception.ExceptionHandling.sniDescription;
import static org.mockserver.log.model.LogEntry.LogMessageType.*;
import static org.mockserver.model.HttpRequest.request;
import static org.mockserver.netty.unification.PortUnificationHandler.isHttp2Enabled;

/**
 * @author jamesdbloom
 */
// @Sharable is REQUIRED, not an optimisation: Http2MultiplexChildInitializer adds ONE instance to
// every HTTP/2 stream pipeline and Netty's checkMultiplicity throws without it - so do NOT remove it.
// The per-instance scheduler/throttleExecutorService are only safe under that sharing because dashboard
// serving never starts on the shared instance (an HTTP/2 dashboard upgrade is refused with 501, so
// registerListeners never runs there). Serving the dashboard over HTTP/2 would share those executors and
// break the throttle when one stream closed: make them per-channel first (see handlerRemoved).
@ChannelHandler.Sharable
public class DashboardWebSocketHandler extends ChannelInboundHandlerAdapter implements MockServerLogListener, MockServerMatcherListener {

    private static final Predicate<DashboardLogEntryDTO> recordedRequestsPredicate = input
        -> input.getType() == RECEIVED_REQUEST;
    private static final Predicate<DashboardLogEntryDTO> proxiedRequestsPredicate = input
        -> input.getType() == FORWARDED_REQUEST;
    private static final AttributeKey<Boolean> CHANNEL_UPGRADED_FOR_UI_WEB_SOCKET = AttributeKey.valueOf("CHANNEL_UPGRADED_FOR_UI_WEB_SOCKET");
    // The handshaker is created during the HTTP upgrade and read again on a later CloseWebSocketFrame,
    // so it must span channelRead invocations - but this handler is @Sharable, meaning ONE instance
    // serves every dashboard channel it is added to. A shared mutable instance field would let
    // concurrent dashboard connections clobber each other's handshake state and close a channel with
    // another channel's handshaker, so it lives on the channel instead - as CallbackWebSocketServerHandler does.
    private static final AttributeKey<WebSocketServerHandshaker> HANDSHAKER = AttributeKey.valueOf("UI_WEB_SOCKET_HANDSHAKER");
    private static final String UPGRADE_CHANNEL_FOR_UI_WEB_SOCKET_URI = "/_mockserver_ui_websocket";
    // One constant used to cap BOTH log rows and expectations. They have opposite cost profiles, so
    // they are now split. Log rows are cheap-each and change constantly; a dashboard client whose
    // traffic list now virtualises (4c1e82e40) can usefully display more history than the default, so
    // the log-row limit is the ONE knob a client may request - within a server-enforced range (see
    // resolveLogItemLimit). Expectations are expensive-each and change rarely, and after the option-6
    // serialisation cache almost all of that expense is already elided, so their cap stays a FIXED
    // server constant the client cannot influence: adding a second client-tunable knob would only widen
    // the attack surface (below) for a limit that no longer dominates the update cost.
    private static final int DEFAULT_LOG_UPDATE_ITEM_LIMIT = 100;
    // Server-enforced hard maximum for the client-requested log-row limit. THIS number - not the
    // per-request validation - is the actual data-plane safety property: the dashboard WebSocket is
    // UNAUTHENTICATED BY DEFAULT (webSocketUpgradeAuthenticated returns true when no control-plane auth
    // handler is configured), so a client-chosen limit is an ATTACKER-chosen limit on a default
    // deployment. Per-request validation bounds the PER-CLIENT cost; it does NOT bound the aggregate.
    // A client asking for exactly the maximum on every connection forever is valid input and still
    // costs (connections x max x frequency).
    //
    // DO NOT READ getClientRegistry()'s CircularHashMap(100) AS THAT AGGREGATE BOUND. An earlier version
    // of this note did, and it was wrong. The registry is an INSTANCE field, and PortUnificationHandler
    // constructs a NEW DashboardWebSocketHandler for every HTTP/1.1 channel, so in production each
    // instance's registry holds exactly ONE connection - its own. (The @Sharable annotation is real, but
    // the only pipeline that shares one instance is the HTTP/2 child initialiser, and an HTTP/2 dashboard
    // upgrade is refused with 501, so no shared instance ever serves a dashboard.) The 100 cap therefore
    // bounds nothing in a live deployment, and the eviction path below is DEFENSIVE rather than a bound
    // anything relies on.
    //
    // What that means for the real aggregate: every upgraded dashboard registers its OWN instance as a
    // log and matcher listener, so N open dashboards means N independent walks per notification, each up
    // to this maximum deep. The aggregate is N x max x frequency, and N is limited only by whatever
    // limits connections generally - not by 100. Raising this maximum multiplies that whole product.
    // (Each dashboard also costs two threads of its own, which is the practical brake on N today.)
    //
    // 500 is 5x the default - a meaningful history increase for a virtualising client. Per-row log work
    // is O(1), and the one genuinely expensive per-item operation
    // (DescriptionProcessor's OpenAPI parse) is reachable only from the EXPECTATIONS path, which is
    // fixed at EXPECTATION_UPDATE_ITEM_LIMIT and NOT client-tunable - so this knob cannot amplify it.
    //
    // BE PRECISE ABOUT WHICH THROTTLE APPLIES, because this is the note a future reader will trust when
    // deciding whether to raise the ceiling again. The Semaphore(1) is a SINGLE GLOBAL permit refilled
    // ~1/second, and it gates only the JSON serialise-and-write in sendMessage. It does NOT gate the
    // DTO-construction walk below, which has already run by then. That walk executes per registry entry
    // on two paths, and BOTH are now rate-bounded per connection: the push path is coalesced by
    // MockServerEventLogNotifier.COALESCE_WINDOW_MILLIS = 250 (~4/second), and the client-pull path (an
    // inbound TextWebSocketFrame, whose rate and filter the client controls) is coalesced PER CHANNEL by
    // the leading+trailing debounce below (see schedulePullUpdate / PULL_COALESCE_WINDOW_MILLIS), which
    // mirrors the push path's machinery: a burst of inbound frames collapses to at most one leading walk
    // plus one trailing walk per window per connection instead of one walk per frame, and the LAST filter
    // a client sent is always the one finally served. So the honest statement is: this maximum deepens a
    // per-window-bounded walk 5x on an endpoint that is unauthenticated by default; a client can no
    // longer drive an UNBOUNDED rate of these deep walks from the pull path. That walk is a PRE-EXISTING
    // surface at depth 100, and per connection the rate is now bounded on both paths, which is what
    // makes 500 acceptable - NOT any cap on how many dashboards exist.
    // A genuinely tighter aggregate bound, if ever needed, is a separate control (a connection cap or a
    // shared budget), NOT something per-request validation buys.
    private static final int MAX_LOG_UPDATE_ITEM_LIMIT = 500;
    // Expectations stay on the fixed cap - not client-tunable (see the split rationale above).
    private static final int EXPECTATION_UPDATE_ITEM_LIMIT = 100;
    // Maximum number of dashboard connections whose updates this @Sharable handler fans out to (see the
    // fan-out cap note on MAX_LOG_UPDATE_ITEM_LIMIT). getClientRegistry() is a CircularHashMap bounded to
    // this size: adding the (limit+1)th connection EVICTS the eldest. That eviction USED to be silent -
    // the evicted browser kept an open socket that simply stopped updating forever - which is the defect
    // registerClient() fixes by closing the evicted connection so the UI's reconnect handling engages.
    // Named (not a magic 100) so the operator-facing log message and the bound cannot drift apart.
    private static final int DASHBOARD_CONNECTION_LIMIT = 100;
    // Query-string parameter on the upgrade URI by which a client requests a log-row limit, e.g.
    // /_mockserver_ui_websocket?logLimit=250. Absent / invalid resolves to the DEFAULT.
    private static final String LOG_ITEM_LIMIT_PARAM = "logLimit";
    // Per-channel because the handler is @Sharable (ONE instance serves every dashboard channel): the
    // limit is chosen at UPGRADE time and read later on each throttled update, so it must live on the
    // channel, not in an instance field, or concurrent connections would clobber each other's limit -
    // exactly the defect 16a9686a8 fixed for HANDSHAKER. Absent attribute => DEFAULT.
    private static final AttributeKey<Integer> LOG_ITEM_LIMIT = AttributeKey.valueOf("UI_WEB_SOCKET_LOG_ITEM_LIMIT");
    // Debounce window for coalescing the CLIENT-PULL update path, mirroring the push path's
    // MockServerEventLogNotifier.COALESCE_WINDOW_MILLIS. A client controls the rate of inbound
    // TextWebSocketFrames on an endpoint that is unauthenticated by default, and each frame used to
    // trigger a fresh logItemLimit-deep walk (up to 500 deep) with no rate bound at all. The pull path
    // is now coalesced per channel (leading + trailing) over this window: the first frame after a quiet
    // period is served immediately (no added latency for a lone filter change), and a burst that lands
    // within the window collapses to a single trailing walk carrying the LAST filter. Kept equal to the
    // push window so the two paths stay visually consistent (≤ ~4 walks/sec/connection).
    private static final long PULL_COALESCE_WINDOW_MILLIS = 250L;
    // Per-channel coalescing state for the client-pull path. Per-channel (not an instance field) because
    // the handler is @Sharable - one instance serves every dashboard channel - exactly like HANDSHAKER
    // and LOG_ITEM_LIMIT. The registry entry records the client's current FILTER promptly on every frame;
    // this only debounces the WORK (the walk) the filter change triggers. Absent attribute => no pull
    // update has been scheduled on this channel yet.
    private static final AttributeKey<PullCoalescer> PULL_COALESCER = AttributeKey.valueOf("UI_WEB_SOCKET_PULL_COALESCER");
    // Test-only instrument (instance-scoped, so concurrent tests / handlers never pollute each
    // other's reading): counts every DashboardLogEntryDTO this handler's per-update log stream
    // constructs, i.e. the "expensive" per-entry work (the entry survived the cheap predicate AND
    // the request matcher, then a DTO was built). Used to prove the short-circuit reduces the walk
    // from O(entire log) to O(depth actually consumed). Not part of any wire format.
    private final AtomicLong logDtoConstructionCount = new AtomicLong();
    // Test-only instrument (instance-scoped, like logDtoConstructionCount, so concurrent tests / handlers
    // never pollute each other's reading): counts how many times the CLIENT-PULL coalescer actually
    // dispatched an update (i.e. triggered a walk) - NOT how many inbound frames arrived. It is the exact
    // instrument for the coalescing property: a burst of N frames must dispatch far fewer than N walks.
    // It is incremented once per real dispatch and is immune to the sendMessage retry path (which
    // re-walks without going back through the coalescer), so it stays exact where an end-to-end DTO count
    // would be inflated by retries. lastPullFilterDispatched records the filter of the most recent
    // dispatch, so a test can prove the LAST filter a client sent is the one finally served.
    private final AtomicLong pullUpdateDispatchCount = new AtomicLong();
    private volatile RequestDefinition lastPullFilterDispatched;

    @VisibleForTesting
    long logDtoConstructionCountForTesting() {
        return logDtoConstructionCount.get();
    }

    @VisibleForTesting
    long pullUpdateDispatchCountForTesting() {
        return pullUpdateDispatchCount.get();
    }

    @VisibleForTesting
    RequestDefinition lastPullFilterDispatchedForTesting() {
        return lastPullFilterDispatched;
    }

    @VisibleForTesting
    void resetLogDtoConstructionCountForTesting() {
        logDtoConstructionCount.set(0);
    }

    @VisibleForTesting
    static WebSocketServerHandshaker handshakerForChannel(Channel channel) {
        return channel.attr(HANDSHAKER).get();
    }

    // The per-channel log-row limit chosen at upgrade time, or null if the channel never upgraded
    // through the resolver. Lets a test prove the upgrade path stores the resolved, clamped value.
    @VisibleForTesting
    static Integer logItemLimitForChannel(Channel channel) {
        return channel.attr(LOG_ITEM_LIMIT).get();
    }

    @VisibleForTesting
    static int defaultLogItemLimitForTesting() {
        return DEFAULT_LOG_UPDATE_ITEM_LIMIT;
    }

    @VisibleForTesting
    static int maxLogItemLimitForTesting() {
        return MAX_LOG_UPDATE_ITEM_LIMIT;
    }
    // Eagerly initialised and safely published via static-final so reads from the off-event-loop
    // scheduler threads see a fully constructed mapper without a data race. The mapper is stateless
    // and thread-safe once configured, so a single shared instance is correct.
    private static final ObjectMapper objectMapper = ObjectMapperFactory.createObjectMapper(
        new DashboardLogEntryDTOSerializer(),
        new DashboardLogEntryDTOGroupSerializer(),
        new DescriptionSerializer(),
        new ThrowableSerializer()
    );
    // Instance-scoped because the writer's pretty-printing depends on the per-instance prettyPrint
    // flag; written once on the event loop in registerListeners() before any scheduler task that
    // reads it is submitted, so it is safely published to those tasks.
    private ObjectWriter objectWriter;
    private final boolean prettyPrint;
    private final MockServerLogger mockServerLogger;
    private final boolean sslEnabledUpstream;
    private final HttpState httpState;
    private HttpRequestSerializer httpRequestSerializer;
    private Map<ChannelOutboundInvoker, HttpRequest> clientRegistry;
    private RequestMatchers requestMatchers;
    private MockServerEventLog mockServerEventLog;
    private ThreadPoolExecutor scheduler;
    private ScheduledExecutorService throttleExecutorService;
    private Semaphore semaphore;
    // Memo of the (expensive) ExpectationDTO -> JsonNode serialisation, keyed by expectation id.
    // Rebuilding it for up to EXPECTATION_UPDATE_ITEM_LIMIT expectations on EVERY throttled dashboard update
    // (roughly once a second per connected dashboard) reproduced byte-identical JSON whenever the
    // expectation had not changed, so the tree is cached and reused. INVALIDATION is deliberately a
    // read-only, zero-data-plane-cost signal: an entry is reused only when the CURRENT matcher still
    // holds the SAME Expectation object reference AND the same remaining Times as when it was
    // serialised. A control-plane edit swaps the reference (AbstractHttpRequestMatcher.update assigns
    // a new Expectation), and the one serving-path mutation that changes the serialised form —
    // Times consumption — changes remainingTimes; every other serialised field is fixed at
    // construction. Both checks are plain reads of state the data plane already maintains, so the
    // cache adds NOTHING to the request path (it maintains no per-mutation structure of its own). The
    // signal can only ever be conservative: any reference swap or Times change forces a re-serialise,
    // so the dashboard can never show a stale expectation. Bounded like expectationRequestDefinitions
    // (one entry per live expectation); a removed expectation is simply never looked up again and its
    // stale entry ages out of the bounded map. Guarded by its own lock — the heavyweight serialise
    // runs OUTSIDE the lock, only the get/put touch it. @Sharable: a single instance serves every
    // dashboard, so the cache is shared across connections and the reuse compounds.
    private Map<String, ActiveExpectationJson> activeExpectationJsonCache;
    private final Object activeExpectationJsonCacheLock = new Object();
    // Counts genuine (cache-miss) expectation serialisations, so a test can prove an unchanged set is
    // not re-serialised and that exactly one changed expectation is. Never read on any hot path.
    private final AtomicLong activeExpectationSerialisationCount = new AtomicLong(0);

    public DashboardWebSocketHandler(HttpState httpState, boolean sslEnabledUpstream, boolean prettyPrint) {
        this.httpState = httpState;
        this.mockServerLogger = httpState.getMockServerLogger();
        this.sslEnabledUpstream = sslEnabledUpstream;
        this.prettyPrint = prettyPrint;
    }

    // clientRegistry (a non-thread-safe CircularHashMap) is mutated from channelRead / write-future
    // listeners (event loop) and iterated from updated(...) callbacks that run on scheduler threads
    // (MockServerMatcherNotifier / MockServerEventLog notify via Scheduler.submit). All access is
    // serialised on the map instance below to keep it thread-safe.
    @VisibleForTesting
    public synchronized Map<ChannelOutboundInvoker, HttpRequest> getClientRegistry() {
        if (clientRegistry == null) {
            clientRegistry = new CircularHashMap<>(DASHBOARD_CONNECTION_LIMIT);
        }
        return clientRegistry;
    }

    /**
     * Register a newly-upgraded dashboard connection in the bounded client registry, making any
     * connection-limit eviction OBSERVABLE instead of silent. The registry is a
     * {@link CircularHashMap} bounded to {@link #DASHBOARD_CONNECTION_LIMIT}, so adding the
     * (limit+1)th connection evicts the eldest. Left silent, that eldest browser kept an open socket
     * that simply stopped updating forever, with no signal to anyone.
     * <p>
     * The {@code CircularHashMap} eviction listener is NOT usable to fix this: it receives the
     * evicted VALUE (a blank filter {@link HttpRequest}), not the KEY (the
     * {@link ChannelOutboundInvoker} needed to reach that connection) - and the value is the same
     * blank request for every not-yet-filtered client, so it cannot even identify which connection
     * was dropped. So the eviction is detected here from the KEY side and the evicted connection is
     * both logged (so an operator sees WHY a dashboard fell silent) and CLOSED (so the browser's
     * existing "server unreachable" / reconnect handling engages instead of showing a frozen page).
     * <p>
     * Concurrency: the map mutation and the eviction detection happen under the registry lock; the
     * network I/O (the log write and the channel close) happen AFTER the lock is released, because
     * doing I/O while holding the non-thread-safe registry's lock would risk a lock-ordering hazard,
     * and the eviction itself fires re-entrantly inside {@code LinkedHashMap.put} where touching the
     * map again would be a bug. Package-private so a test can drive the eviction path directly.
     *
     * @return the connection that was evicted and closed to make room, or {@code null} if the
     * registry had room for this connection.
     */
    @VisibleForTesting
    ChannelOutboundInvoker registerClient(ChannelOutboundInvoker ctx) {
        ChannelOutboundInvoker evicted = addToRegistryDetectingEviction(ctx);
        if (evicted != null) {
            if (mockServerLogger.isEnabledForInstance(Level.WARN)) {
                mockServerLogger.logEvent(
                    new LogEntry()
                        .setLogLevel(Level.WARN)
                        .setMessageFormat("dashboard connection disconnected because the dashboard connection limit of {} was reached - the oldest connection was closed and its browser will attempt to reconnect")
                        .setArguments(DASHBOARD_CONNECTION_LIMIT)
                );
            }
            evicted.close();
        }
        return evicted;
    }

    // Add ctx to the registry under its lock and, still under the lock, work out which connection the
    // bound evicted (if any) so the caller can close it OUTSIDE the lock. Detection observes the map's
    // OWN decision - the entry that was eldest before the put is absent after it - rather than
    // reimplementing the size>limit eviction policy, so it stays correct if the bound ever changes.
    // (LinkedHashMap.removeEldestEntry always evicts the eldest, so the eldest-before key is the only
    // candidate.) Returns null when the put replaced an existing entry or the registry had room.
    private ChannelOutboundInvoker addToRegistryDetectingEviction(ChannelOutboundInvoker ctx) {
        Map<ChannelOutboundInvoker, HttpRequest> registry = getClientRegistry();
        synchronized (registry) {
            Iterator<ChannelOutboundInvoker> keys = registry.keySet().iterator();
            ChannelOutboundInvoker eldestBefore = keys.hasNext() ? keys.next() : null;
            registry.put(ctx, request());
            if (eldestBefore != null && eldestBefore != ctx && !registry.containsKey(eldestBefore)) {
                return eldestBefore;
            }
            return null;
        }
    }

    @Override
    public void handlerAdded(ChannelHandlerContext ctx) {
        try {
            scheduler = new ThreadPoolExecutor(
                1,
                1,
                0L,
                SECONDS,
                new LinkedBlockingQueue<>(1),
                Executors.defaultThreadFactory(),
                new ThreadPoolExecutor.DiscardOldestPolicy()
            );
        } catch (Throwable throwable) {
            mockServerLogger.logEvent(
                new LogEntry()
                    .setLogLevel(Level.ERROR)
                    .setMessageFormat("exception creating scheduler " + throwable.getMessage())
                    .setThrowable(throwable)
            );
        }
    }

    // NOTE, because this is a trap for anyone adding HTTP/2 dashboard support: both executors are shut
    // down here and NOT nulled, so registerListeners cannot recreate them. On an HTTP/1.1 channel that is
    // correct - PortUnificationHandler builds a fresh handler per channel, so removal means that one
    // connection is finished. But Http2MultiplexChildInitializer creates ONE instance and adds it to
    // EVERY child stream, so the first sibling stream to close would permanently disable the throttle
    // refill and the pull coalescer for all the others (scheduleCoalescedPullFlush would then take its
    // isShutdown branch and dispatch every frame, losing the bound below).
    //
    // That is unreachable TODAY only because a dashboard upgrade over HTTP/2 is refused with 501 in
    // channelRead, so a stream channel never gets CHANNEL_UPGRADED_FOR_UI_WEB_SOCKET and therefore never
    // reaches handleWebSocketFrame. (The refusal is itself reliable because HTTP2_ENABLED is in
    // ConnectionScopeHandler.CONNECTION_SCOPED_ATTRIBUTES and that handler runs FIRST on the child
    // pipeline, so isHttp2Enabled is true on a stream channel rather than silently defaulting to false.)
    // If HTTP/2 dashboards are ever supported, make this per-channel before enabling them.
    @Override
    public void handlerRemoved(ChannelHandlerContext ctx) {
        if (this.scheduler != null) {
            scheduler.shutdown();
        }
        if (this.throttleExecutorService != null) {
            throttleExecutorService.shutdownNow();
        }
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) {
        boolean release = true;
        try {
            if (msg instanceof FullHttpRequest && isDashboardUpgradeUri(((FullHttpRequest) msg).uri())) {
                if (isHttp2Enabled(ctx.channel())) {
                    if (mockServerLogger.isEnabledForInstance(Level.TRACE)) {
                        mockServerLogger.logEvent(
                            new LogEntry()
                                .setLogLevel(Level.TRACE)
                                .setMessageFormat("WebSocket upgrade not supported over HTTP/2 for dashboard connection:{}")
                                .setArguments(ctx.channel().localAddress())
                        );
                    }
                    // This branch fires ONLY on HTTP/2, so the 501 must carry the request's stream
                    // id - otherwise it goes out on a phantom server-initiated stream and the
                    // dashboard hangs instead of being told WebSocket upgrade is unsupported.
                    DefaultFullHttpResponse notImplemented = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.NOT_IMPLEMENTED, Unpooled.EMPTY_BUFFER);
                    Http2StreamIds.stampFromNettyRequest(notImplemented, (FullHttpRequest) msg);
                    ctx.channel().writeAndFlush(notImplemented);
                } else if (!webSocketUpgradeAuthenticated(ctx, (FullHttpRequest) msg)) {
                    // control-plane auth is configured and this upgrade did not present valid
                    // credentials (or the principal lacks the required role): the rejection
                    // response has already been written, so do NOT upgrade — otherwise the
                    // dashboard would push all captured traffic to an unauthenticated client.
                } else {
                    // Resolve the client-requested log-row limit at the SINGLE choke point and pin it
                    // to THIS channel (read later on each throttled update). A bare URI carries no
                    // parameter, so this resolves to the DEFAULT and costs exactly what it does today.
                    ctx.channel().attr(LOG_ITEM_LIMIT).set(
                        resolveLogItemLimit(firstQueryParam(((FullHttpRequest) msg).uri(), LOG_ITEM_LIMIT_PARAM)));
                    upgradeChannel(ctx, (FullHttpRequest) msg);
                    ctx.channel().attr(CHANNEL_UPGRADED_FOR_UI_WEB_SOCKET).set(true);
                }
            } else if (ctx.channel().attr(CHANNEL_UPGRADED_FOR_UI_WEB_SOCKET).get() != null &&
                ctx.channel().attr(CHANNEL_UPGRADED_FOR_UI_WEB_SOCKET).get() &&
                msg instanceof WebSocketFrame) {
                handleWebSocketFrame(ctx, (WebSocketFrame) msg);
            } else {
                release = false;
                ctx.fireChannelRead(msg);
            }
        } finally {
            if (release) {
                ReferenceCountUtil.release(msg);
            }
        }
    }

    // Match the dashboard upgrade on the PATH component only, so a query string (e.g. the client's
    // requested log limit) does not break the match - the equality it replaces would have rejected any
    // /_mockserver_ui_websocket?... entirely. QueryStringDecoder.rawPath() returns the undecoded path
    // before '?'; a bare URI has rawPath()==uri, so existing clients that send no query string are
    // unaffected. Deliberately an EXACT equality on the path, not a prefix/startsWith, so this does not
    // widen what counts as the dashboard upgrade path.
    private static boolean isDashboardUpgradeUri(String uri) {
        return UPGRADE_CHANNEL_FOR_UI_WEB_SOCKET_URI.equals(new QueryStringDecoder(uri).rawPath());
    }

    // First value of the named query-string parameter, or null when absent.
    private static String firstQueryParam(String uri, String name) {
        List<String> values = new QueryStringDecoder(uri).parameters().get(name);
        return (values == null || values.isEmpty()) ? null : values.get(0);
    }

    /**
     * The SINGLE choke point that validates AND bounds a client-requested log-row limit. Every
     * requested value flows through here and this is the ONLY place the maximum is applied, so no two
     * sites can disagree. It FAILS TOWARD THE DEFAULT, never toward the maximum: an absent, blank,
     * non-numeric, negative or zero request resolves to {@link #DEFAULT_LOG_UPDATE_ITEM_LIMIT} - an
     * unparseable value is NOT read as "give me everything". A value above the maximum is clamped down
     * to {@link #MAX_LOG_UPDATE_ITEM_LIMIT}. A valid value below the default is honoured as-is (the
     * client asked for less). This bounds the PER-CLIENT cost only; the aggregate is bounded by the
     * maximum together with the connection cap, not by this check (see MAX_LOG_UPDATE_ITEM_LIMIT).
     */
    @VisibleForTesting
    static int resolveLogItemLimit(String requested) {
        if (requested == null) {
            return DEFAULT_LOG_UPDATE_ITEM_LIMIT;
        }
        int value;
        try {
            value = Integer.parseInt(requested.trim());
        } catch (NumberFormatException nfe) {
            return DEFAULT_LOG_UPDATE_ITEM_LIMIT;
        }
        if (value <= 0) {
            return DEFAULT_LOG_UPDATE_ITEM_LIMIT;
        }
        return Math.min(value, MAX_LOG_UPDATE_ITEM_LIMIT);
    }

    // Read the per-channel log-row limit chosen at upgrade time. The update path holds a
    // ChannelOutboundInvoker (a ChannelHandlerContext in production, an EmbeddedChannel in tests), so
    // resolve it to a Channel to read the attribute; an absent value (channel never upgraded through
    // the resolver, or not channel-shaped) uses the DEFAULT.
    private static int logItemLimitFor(ChannelOutboundInvoker ctx) {
        Channel channel = channelOf(ctx);
        if (channel != null) {
            Integer limit = channel.attr(LOG_ITEM_LIMIT).get();
            if (limit != null) {
                return limit;
            }
        }
        return DEFAULT_LOG_UPDATE_ITEM_LIMIT;
    }

    // The update path holds a ChannelOutboundInvoker (a ChannelHandlerContext in production, an
    // EmbeddedChannel in tests); resolve it to the Channel that carries the per-channel attributes, or
    // null when it is not channel-shaped.
    private static Channel channelOf(ChannelOutboundInvoker ctx) {
        if (ctx instanceof ChannelHandlerContext) {
            return ((ChannelHandlerContext) ctx).channel();
        } else if (ctx instanceof Channel) {
            return (Channel) ctx;
        }
        return null;
    }

    /**
     * Bound the CLIENT-PULL update path so a client cannot drive one full logItemLimit-deep walk per
     * inbound frame. Coalesces per channel with a leading + trailing debounce over
     * {@link #PULL_COALESCE_WINDOW_MILLIS}, reusing the same dirty/scheduled machinery as the push path's
     * {@code MockServerEventLogNotifier}:
     * <ul>
     *   <li><b>Leading edge</b> - the first frame after a quiet period dispatches immediately, so a lone
     *       filter change is served with no added latency (never dropped, never delayed a whole window).</li>
     *   <li><b>Trailing edge</b> - frames that land while the window is open only update the latest filter;
     *       when the window elapses the LATEST filter is served once. So a burst of N frames collapses to
     *       at most one leading + one trailing walk per window, and the LAST filter a client sent is
     *       always the one finally reflected - a superseded filter can never be the last view shown.</li>
     * </ul>
     * Inbound frames for one channel are serialised on that channel's event loop, so the only concurrency
     * is frame-vs-flush; that race is handled exactly as the notifier does (release the gate, then
     * re-check for a late arrival). Never holds a lock, and never touches the {@code getClientRegistry()}
     * lock across the walk.
     */
    @VisibleForTesting
    void schedulePullUpdate(ChannelOutboundInvoker ctx, RequestDefinition filter) {
        Channel channel = channelOf(ctx);
        if (channel == null) {
            // Not channel-shaped (defensive): dispatch immediately rather than drop the update.
            dispatchPullUpdate(ctx, filter);
            return;
        }
        PullCoalescer coalescer = channel.attr(PULL_COALESCER).get();
        if (coalescer == null) {
            PullCoalescer created = new PullCoalescer();
            coalescer = channel.attr(PULL_COALESCER).setIfAbsent(created);
            if (coalescer == null) {
                coalescer = created;
            }
        }
        coalescer.latestFilter = filter;
        coalescer.dirty.set(true);
        if (coalescer.scheduled.compareAndSet(false, true)) {
            fireCoalescedPull(ctx, coalescer);
        }
    }

    // Serve the latest filter now (if there is unserved work) and open/refresh the cooldown window.
    private void fireCoalescedPull(ChannelOutboundInvoker ctx, PullCoalescer coalescer) {
        if (coalescer.dirty.compareAndSet(true, false)) {
            dispatchPullUpdate(ctx, coalescer.latestFilter);
        }
        scheduleCoalescedPullFlush(ctx, coalescer);
    }

    private void scheduleCoalescedPullFlush(ChannelOutboundInvoker ctx, PullCoalescer coalescer) {
        ScheduledExecutorService executor = throttleExecutorService;
        if (executor == null || executor.isShutdown()) {
            // No scheduler available (never in production - registerListeners ran at upgrade). Reset the
            // gate so the next frame still dispatches via the leading edge rather than wedging.
            coalescer.scheduled.set(false);
            return;
        }
        try {
            executor.schedule(() -> flushCoalescedPull(ctx, coalescer), PULL_COALESCE_WINDOW_MILLIS, MILLISECONDS);
        } catch (RejectedExecutionException shuttingDown) {
            coalescer.scheduled.set(false);
        }
    }

    // Runs on the throttle executor when the window elapses. Mirrors MockServerEventLogNotifier.runCoalesced:
    // serve once more if frames arrived during the window (and re-arm), otherwise close the window and
    // re-check for a late arrival that saw the window still open.
    private void flushCoalescedPull(ChannelOutboundInvoker ctx, PullCoalescer coalescer) {
        if (coalescer.dirty.get()) {
            fireCoalescedPull(ctx, coalescer);
        } else {
            coalescer.scheduled.set(false);
            if (coalescer.dirty.get() && coalescer.scheduled.compareAndSet(false, true)) {
                fireCoalescedPull(ctx, coalescer);
            }
        }
    }

    private void dispatchPullUpdate(ChannelOutboundInvoker ctx, RequestDefinition filter) {
        pullUpdateDispatchCount.incrementAndGet();
        lastPullFilterDispatched = filter;
        sendUpdate(ctx, filter);
    }

    // Per-channel client-pull coalescing state. dirty/scheduled carry the same meaning as in
    // MockServerEventLogNotifier: dirty = "there is an unserved filter", scheduled = "a window is open".
    private static final class PullCoalescer {
        private final AtomicBoolean scheduled = new AtomicBoolean(false);
        private final AtomicBoolean dirty = new AtomicBoolean(false);
        private volatile RequestDefinition latestFilter;
    }

    @Override
    public void channelReadComplete(ChannelHandlerContext ctx) {
        ctx.flush();
        // a mid-pipeline handler that swallows channelReadComplete starves Netty's HTTP/2
        // flow-control flush (Http2ConnectionHandler.channelReadComplete -> writePendingBytes),
        // stalling any h2 response larger than the peer's initial window - so propagate the event
        ctx.fireChannelReadComplete();
    }

    /**
     * Gate the dashboard UI WebSocket upgrade with the SAME control-plane authentication /
     * authorization as {@code /mockserver/configuration} and the dashboard HTTP surface.
     * <p>
     * When no control-plane authentication handler is configured (the default) this returns
     * {@code true} immediately without touching the request, so the open-dashboard behaviour is
     * unchanged. When one IS configured it maps the raw Netty upgrade request (with any mTLS
     * client certificates from the channel) to a MockServer request and asks the shared core gate
     * for a decision; on a non-ALLOWED outcome it writes a raw {@code 401}/{@code 403} handshake
     * response and closes the connection, returning {@code false} so the caller does not upgrade.
     * The upgrade is a read, so a read-only control-plane role is permitted to view the dashboard.
     */
    private boolean webSocketUpgradeAuthenticated(final ChannelHandlerContext ctx, FullHttpRequest httpRequest) {
        if (httpState.getControlPlaneAuthenticationHandler() == null) {
            // No control-plane authentication configured: preserve the default open dashboard.
            return true;
        }
        HttpRequest mockServerRequest = new FullHttpRequestToMockServerHttpRequest(
            httpState.getConfiguration(),
            mockServerLogger,
            sslEnabledUpstream,
            SniHandler.retrieveClientCertificates(mockServerLogger, ctx),
            ctx.channel().localAddress() instanceof java.net.InetSocketAddress
                ? ((java.net.InetSocketAddress) ctx.channel().localAddress()).getPort()
                : null
        ).mapFullHttpRequestToMockServerRequest(
            httpRequest,
            null,
            ctx.channel().localAddress(),
            ctx.channel().remoteAddress(),
            SniHandler.getALPNProtocol(mockServerLogger, ctx)
        );
        HttpState.ControlPlaneAuthDecision decision = httpState.evaluateControlPlaneAuthentication(mockServerRequest);
        if (decision.isAllowed()) {
            return true;
        }
        HttpResponseStatus status = decision.outcome() == HttpState.ControlPlaneAuthOutcome.FORBIDDEN
            ? HttpResponseStatus.FORBIDDEN
            : HttpResponseStatus.UNAUTHORIZED;
        if (mockServerLogger.isEnabledForInstance(Level.INFO)) {
            mockServerLogger.logEvent(
                new LogEntry()
                    .setType(AUTHENTICATION_FAILED)
                    .setLogLevel(Level.INFO)
                    .setMessageFormat("dashboard UI web socket upgrade rejected with status {} - control plane authentication required")
                    .setArguments(status.code())
            );
        }
        DefaultFullHttpResponse response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, status, Unpooled.EMPTY_BUFFER);
        response.headers().set(CONTENT_LENGTH, 0);
        ctx.channel().writeAndFlush(response).addListener(ChannelFutureListener.CLOSE);
        return false;
    }

    private void upgradeChannel(final ChannelHandlerContext ctx, FullHttpRequest httpRequest) {
        String webSocketURL = (sslEnabledUpstream ? "wss" : "ws") + "://" + httpRequest.headers().get(HOST) + UPGRADE_CHANNEL_FOR_UI_WEB_SOCKET_URI;
        if (mockServerLogger.isEnabledForInstance(Level.TRACE)) {
            mockServerLogger.logEvent(
                new LogEntry()
                    .setLogLevel(Level.TRACE)
                    .setMessageFormat("upgraded dashboard connection to support web sockets on url{}")
                    .setArguments(webSocketURL)
            );
        }
        final WebSocketServerHandshaker handshaker = new WebSocketServerHandshakerFactory(
            webSocketURL,
            null,
            true,
            Integer.MAX_VALUE
        ).newHandshaker(httpRequest);
        if (handshaker == null) {
            WebSocketServerHandshakerFactory.sendUnsupportedVersionResponse(ctx.channel());
        } else {
            ctx.channel().attr(HANDSHAKER).set(handshaker);
            handshaker.handshake(
                ctx.channel(),
                httpRequest,
                new DefaultHttpHeaders(),
                ctx.channel().newPromise()
            ).addListener((ChannelFutureListener) future -> registerClient(ctx));
        }
        registerListeners();
    }

    @VisibleForTesting
    protected DashboardWebSocketHandler registerListeners() {
        if (objectWriter == null) {
            if (prettyPrint) {
                objectWriter = objectMapper.writerWithDefaultPrettyPrinter();
            } else {
                objectWriter = objectMapper.writer();
            }
        }
        if (httpRequestSerializer == null) {
            httpRequestSerializer = new HttpRequestSerializer(mockServerLogger);
        }
        if (semaphore == null) {
            semaphore = new Semaphore(1);
        }
        if (throttleExecutorService == null) {
            throttleExecutorService = Executors.newScheduledThreadPool(1);
        }
        if (scheduler == null) {
            scheduler = new ThreadPoolExecutor(
                1,
                1,
                0L,
                SECONDS,
                new LinkedBlockingQueue<>(10),
                Executors.defaultThreadFactory(),
                new ThreadPoolExecutor.DiscardOldestPolicy()
            );
        }
        throttleExecutorService.scheduleAtFixedRate(() -> {
            if (semaphore.availablePermits() == 0) {
                semaphore.release(1);
            }
        }, 0, 1, SECONDS);
        if (mockServerEventLog == null) {
            mockServerEventLog = httpState.getMockServerLog();
            mockServerEventLog.registerListener(this);
            requestMatchers = httpState.getRequestMatchers();
            requestMatchers.registerListener(this);
            scheduler.submit(() -> {
                try {
                    MILLISECONDS.sleep(100);
                } catch (InterruptedException ignore) {
                }
                // ensure any exception added during initialisation are caught
                updated(mockServerEventLog);
                updated(requestMatchers, null);
            });
        }
        return this;
    }

    private void handleWebSocketFrame(final ChannelHandlerContext ctx, WebSocketFrame frame) {
        if (frame instanceof CloseWebSocketFrame) {
            final WebSocketServerHandshaker handshaker = ctx.channel().attr(HANDSHAKER).get();
            if (handshaker != null) {
                handshaker.close(ctx.channel(), (CloseWebSocketFrame) frame.retain()).addListener((ChannelFutureListener) future -> {
                    Map<ChannelOutboundInvoker, HttpRequest> registry = getClientRegistry();
                    synchronized (registry) {
                        registry.remove(ctx);
                    }
                });
            } else {
                Map<ChannelOutboundInvoker, HttpRequest> registry = getClientRegistry();
                synchronized (registry) {
                    registry.remove(ctx);
                }
                ctx.close();
            }
        } else if (frame instanceof TextWebSocketFrame) {
            try {
                HttpRequest httpRequest = httpRequestSerializer.deserialize(((TextWebSocketFrame) frame).text());
                Map<ChannelOutboundInvoker, HttpRequest> registry = getClientRegistry();
                synchronized (registry) {
                    // The filter is STATE - record it promptly on every frame so the push path always
                    // serves the client's current filter, even if the resulting pull update is coalesced.
                    registry.put(ctx, httpRequest);
                }
                // The update is WORK - coalesce it per channel so an unbounded burst of client frames
                // cannot drive one deep walk per frame on this unauthenticated-by-default endpoint.
                schedulePullUpdate(ctx, httpRequest);
            } catch (IllegalArgumentException iae) {
                sendMessage(ctx, null, ImmutableMap.of("error", iae.getMessage()), 2);
            }
        } else if (frame instanceof PingWebSocketFrame) {
            ctx.write(new PongWebSocketFrame(frame.content().retain()));
        } else {
            throw new UnsupportedOperationException(frame.getClass().getName() + " frame types not supported");
        }
    }

    private void sendMessage(ChannelOutboundInvoker ctx, RequestDefinition httpRequest, ImmutableMap<String, Object> message, int retryCount) {
        if (semaphore.tryAcquire()) {
            scheduler.submit(() -> {
                try {
                    String text = objectWriter.writeValueAsString(message);
                    ctx.writeAndFlush(new TextWebSocketFrame(text));
                } catch (JsonProcessingException jpe) {
                    mockServerLogger.logEvent(
                        new LogEntry()
                            .setLogLevel(Level.ERROR)
                            .setMessageFormat("exception with serialising UI data " + jpe.getMessage())
                            .setThrowable(jpe)
                    );
                }
            });
        } else if (retryCount >= 0) {
            scheduler.submit(() -> {
                try {
                    TimeUnit.MILLISECONDS.sleep(200);
                } catch (InterruptedException ignore) {
                }
                if (httpRequest != null) {
                    sendUpdate(ctx, httpRequest, retryCount - 1);
                } else {
                    sendMessage(ctx, null, message, retryCount - 1);
                }
            });

        }

    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        if (connectionClosedException(cause)) {
            mockServerLogger.logEvent(
                new LogEntry()
                    .setLogLevel(Level.ERROR)
                    .setMessageFormat("web socket server caught exception")
                    .setThrowable(cause)
            );
        } else if (isSslOrDecoderFault(cause)) {
            mockServerLogger.logEvent(
                new LogEntry()
                    .setLogLevel(Level.WARN)
                    .setMessageFormat("web socket server caught SSL or decoder fault" + sniDescription(ctx.channel()))
                    .setThrowable(cause)
            );
        }
        ctx.close();
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) {
        if (requestMatchers != null) {
            requestMatchers.unregisterListener(this);
        }
        if (mockServerEventLog != null) {
            mockServerEventLog.unregisterListener(this);
        }
        ctx.fireChannelInactive();
    }

    @Override
    public void updated(MockServerEventLog mockServerLog) {
        for (Map.Entry<ChannelOutboundInvoker, HttpRequest> registryEntry : clientRegistrySnapshot()) {
            sendUpdate(registryEntry.getKey(), registryEntry.getValue());
        }
    }

    @Override
    public void updated(RequestMatchers requestMatchers, MockServerMatcherNotifier.Cause cause) {
        for (Map.Entry<ChannelOutboundInvoker, HttpRequest> registryEntry : clientRegistrySnapshot()) {
            sendUpdate(registryEntry.getKey(), registryEntry.getValue());
        }
    }

    // Snapshot the registry under its lock so the (off-event-loop) updated(...) callbacks iterate a
    // stable copy without holding the lock across the heavyweight sendUpdate calls and without racing
    // the event-loop put/remove mutations.
    private List<Map.Entry<ChannelOutboundInvoker, HttpRequest>> clientRegistrySnapshot() {
        Map<ChannelOutboundInvoker, HttpRequest> registry = getClientRegistry();
        synchronized (registry) {
            return new ArrayList<>(registry.entrySet());
        }
    }

    @VisibleForTesting
    void sendUpdate(ChannelOutboundInvoker ctx, RequestDefinition httpRequest) {
        sendUpdate(ctx, httpRequest, 2);
    }

    private void sendUpdate(ChannelOutboundInvoker ctx, RequestDefinition httpRequest, int retryCount) {
        // Client-requested log-row limit for THIS connection (DEFAULT when it requested nothing).
        // Expectations are NOT governed by it - they keep the fixed EXPECTATION_UPDATE_ITEM_LIMIT.
        final int logItemLimit = logItemLimitFor(ctx);
        DescriptionProcessor activeExpectationsDescriptionProcessor = new DescriptionProcessor();
        DescriptionProcessor logMessagesDescriptionProcessor = new DescriptionProcessor();
        DescriptionProcessor recordedRequestsDescriptionProcessor = new DescriptionProcessor();
        DescriptionProcessor proxiedRequestsDescriptionProcessor = new DescriptionProcessor();
        Configuration configuration = httpState.getConfiguration();
        Map<String, String> overrides = configuration.logLevelOverrides();
        Level globalLevel = configuration.logLevel();
        mockServerEventLog
            .retrieveLogEntriesInReverseForUI(
                httpRequest,
                logEntry -> !logEntry.isDeleted()
                    && (logEntry.isAlwaysLog() || overrides == null || overrides.isEmpty()
                    || MockServerLogger.isEnabled(logEntry.getLogLevel(), LogEntry.LogMessageTypeCategory.resolveEffectiveLevel(logEntry.getType(), overrides, globalLevel))),
                logEntry -> {
                    logDtoConstructionCount.incrementAndGet();
                    return new DashboardLogEntryDTO(logEntry, configuration);
                },
                reverseLogEventsStream -> {
                    // Retrieve ONCE: the dashboard needs both the capped page it renders and the
                    // TRUE total, and calling retrieveRequestMatchers twice would be two walks of
                    // the matcher store per connected dashboard per update.
                    List<? extends HttpRequestMatcher> allRequestMatchers =
                        requestMatchers.retrieveRequestMatchers(httpRequest);
                    // The count the dashboard shows is the number of expectations the SERVER holds,
                    // not the size of the page it was sent. Those differ the moment there are more
                    // than EXPECTATION_UPDATE_ITEM_LIMIT of them, and a count pinned at exactly the
                    // limit reads as a bug — it silently stops being a count and becomes the cap.
                    int activeExpectationsTotal = allRequestMatchers.size();
                    // Whether ANY expectation here is an LLM expectation - decided over the whole
                    // retrieved list, NOT the capped page built below.
                    //
                    // Say what this is OF, because it is easy to read as more than it is: the
                    // retrieval is FILTERED by the dashboard's own request filter, so this means
                    // "an LLM expectation exists among those matching the current filter", which is
                    // the whole server set only when no filter is active. That is the right question
                    // for deciding whether to OFFER the LLM Provider control -- filtering by provider
                    // inside a view that contains no LLM expectation would return nothing -- but it
                    // does mean the control comes and goes with the filter. The dashboard offers its LLM
                    // Provider filter from this flag, and it cannot derive that from the page it renders:
                    // the page is capped at EXPECTATION_UPDATE_ITEM_LIMIT, so a server holding more than
                    // that many non-LLM expectations AHEAD of its LLM ones never sends an LLM expectation
                    // in the page at all, and the UI - which can only see the page - would then hide the
                    // filter on exactly the busy server where it is wanted. This is the server-side signal
                    // that closes that gap. Derived from the SAME already-retrieved allRequestMatchers (no
                    // second store walk - that walk is per connected dashboard per throttled update, so a
                    // second one would be a real cost, exactly as for activeExpectationsTotal above); it is
                    // a fresh pass because the limited stream below deliberately stops at the cap and so
                    // cannot observe the tail where the LLM expectation may sit. anyMatch short-circuits on
                    // the first match, so the common case pays for only a few entries. An LLM expectation
                    // is one whose Expectation carries an httpLlmResponse action
                    // (Expectation.getHttpLlmResponse() != null) - the model-level fact the ExpectationDTO
                    // serialises as the "httpLlmResponse" field the UI keys off; decided here from the
                    // matcher model rather than re-observed from the serialised page.
                    boolean activeExpectationsIncludeLlm = allRequestMatchers
                        .stream()
                        .anyMatch(requestMatcher -> requestMatcher.getExpectation() != null
                            && requestMatcher.getExpectation().getHttpLlmResponse() != null);
                    List<ImmutableMap<String, Object>> activeExpectations = allRequestMatchers
                        .stream()
                        .limit(EXPECTATION_UPDATE_ITEM_LIMIT)
                        .map(requestMatcher -> {
                            // Reuse the cached JSON tree when the expectation is unchanged; the
                            // Description is recomputed every time (it is cheap for the common
                            // HttpRequest case and its padding depends on the batch's max length,
                            // so it must be derived from the CURRENT set, not memoised per item).
                            JsonNode expectationJsonNode = activeExpectationValue(requestMatcher);
                            Description description = activeExpectationsDescriptionProcessor.description(requestMatcher.getExpectation().getHttpRequest(), requestMatcher.getExpectation().getId());
                            return ImmutableMap.of(
                                "key", requestMatcher.getExpectation().getId(),
                                "description", description != null ? description : requestMatcher.getExpectation().getId(),
                                "value", expectationJsonNode
                            );
                        })
                        .collect(Collectors.toList());
                    List<Map<String, Object>> proxiedRequests = new LinkedList<>();
                    List<Map<String, Object>> recordedRequests = new LinkedList<>();
                    List<Object> logMessages = new LinkedList<>();
                    populateLogSections(
                        reverseLogEventsStream, true, logItemLimit,
                        logMessages, recordedRequests, proxiedRequests,
                        logMessagesDescriptionProcessor, recordedRequestsDescriptionProcessor, proxiedRequestsDescriptionProcessor);
                    sendMessage(ctx, httpRequest, ImmutableMap.of(
                        "logMessages", logMessages,
                        "activeExpectations", activeExpectations,
                        "activeExpectationsTotal", activeExpectationsTotal,
                        "activeExpectationsIncludeLlm", activeExpectationsIncludeLlm,
                        "recordedRequests", recordedRequests,
                        "proxiedRequests", proxiedRequests // reverse
                    ), retryCount);
                }
            );
    }

    // Consume the reverse-chronological UI log stream into the three dashboard sections.
    // Extracted (and package-private) so a test can drive it twice over an IDENTICAL list of
    // DTOs -- once with shortCircuit=true and once with shortCircuit=false -- and assert the two
    // produce byte-identical sections, proving the short-circuit changes performance not output.
    // Production always passes shortCircuit=true.
    @VisibleForTesting
    static void populateLogSections(
        Stream<DashboardLogEntryDTO> reverseLogEventsStream,
        boolean shortCircuit,
        int logItemLimit,
        List<Object> logMessages,
        List<Map<String, Object>> recordedRequests,
        List<Map<String, Object>> proxiedRequests,
        DescriptionProcessor logMessagesDescriptionProcessor,
        DescriptionProcessor recordedRequestsDescriptionProcessor,
        DescriptionProcessor proxiedRequestsDescriptionProcessor
    ) {
        Map<String, DashboardLogEntryDTOGroup> logEntryGroups = new HashMap<>();
        // The dashboard Traffic/Sessions/Cost panels render each
        // mock-matched request alongside the response that was
        // returned. The reverse-chronological stream surfaces the
        // response (EXPECTATION_RESPONSE / NO_MATCH_RESPONSE)
        // before its corresponding RECEIVED_REQUEST, so we stash
        // responses by correlationId and look them up when the
        // matching request is processed.
        Map<String, Object> responsesByCorrelationId = new HashMap<>();
        // Short-circuit once all three output categories are full. The stream is sequential and
        // ordered and this predicate is evaluated BEFORE each element, so an element only reaches
        // the body while at least one category still has room. Once logMessages, recordedRequests
        // and proxiedRequests have each hit logItemLimit no later element could be added to
        // ANY of them (every add below is guarded by the same size check, and responsesByCorrelationId
        // is only ever consumed to enrich recordedRequests, which is full), so stopping here drops
        // only entries the old full walk would have discarded -- the emitted frame is byte-identical
        // while the walk becomes O(depth needed) not O(entire log).
        Stream<DashboardLogEntryDTO> boundedStream = shortCircuit
            ? reverseLogEventsStream.takeWhile(logEntryDTO ->
                logMessages.size() < logItemLimit
                    || recordedRequests.size() < logItemLimit
                    || proxiedRequests.size() < logItemLimit)
            : reverseLogEventsStream;
        boundedStream
            .forEach(logEntryDTO -> {
                if (logEntryDTO != null) {
                    if (logMessages.size() < logItemLimit) {
                        DashboardLogEntryDTO dashboardLogEntryDTO = logEntryDTO.setDescription(logMessagesDescriptionProcessor.description(logEntryDTO));
                        if (isNotBlank(logEntryDTO.getCorrelationId()) && logEntryDTO.getType() != TRACE) {
                            DashboardLogEntryDTOGroup logEntryGroup = logEntryGroups.get(logEntryDTO.getCorrelationId());
                            if (logEntryGroup == null) {
                                logEntryGroup = new DashboardLogEntryDTOGroup(logMessagesDescriptionProcessor);
                                logEntryGroups.put(logEntryDTO.getCorrelationId(), logEntryGroup);
                                logMessages.add(logEntryGroup);
                            }
                            logEntryGroup.getLogEntryDTOS().add(dashboardLogEntryDTO);
                        } else {
                            logMessages.add(dashboardLogEntryDTO);
                        }
                    }
                    if ((logEntryDTO.getType() == EXPECTATION_RESPONSE || logEntryDTO.getType() == NO_MATCH_RESPONSE)
                        && isNotBlank(logEntryDTO.getCorrelationId())
                        && logEntryDTO.getHttpResponse() != null) {
                        responsesByCorrelationId.putIfAbsent(logEntryDTO.getCorrelationId(), logEntryDTO.getHttpResponse());
                    }
                    if (recordedRequestsPredicate.test(logEntryDTO) && recordedRequests.size() < logItemLimit) {
                        for (RequestDefinition request : logEntryDTO.getHttpRequests()) {
                            if (request != null) {
                                Map<String, Object> value = new LinkedHashMap<>();
                                value.put("httpRequest", request);
                                Object response = isNotBlank(logEntryDTO.getCorrelationId())
                                    ? responsesByCorrelationId.get(logEntryDTO.getCorrelationId())
                                    : null;
                                if (response != null) {
                                    value.put("httpResponse", response);
                                }
                                Map<String, Object> entry = new LinkedHashMap<>();
                                Description description = recordedRequestsDescriptionProcessor.description(request);
                                if (description != null) {
                                    entry.put("description", description);
                                }
                                entry.put("value", value);
                                entry.put("key", logEntryDTO.getId() + "_request");
                                // When the request was received. The dashboard shows this instead of
                                // an ordinal: the list is a capped window, so a position within it
                                // renumbers on every push and corresponds to nothing the reader can
                                // refer to. A timestamp is stable and comparable with the log panel.
                                entry.put("timestamp", logEntryDTO.getTimestamp());
                                recordedRequests.add(entry);
                            }
                        }
                    }
                    if (proxiedRequestsPredicate.test(logEntryDTO) && proxiedRequests.size() < logItemLimit) {
                        Map<String, Object> value = new LinkedHashMap<>();
                        if (logEntryDTO.getHttpRequest() != null) {
                            value.put("httpRequest", logEntryDTO.getHttpRequest());
                        }
                        if (logEntryDTO.getHttpResponse() != null) {
                            value.put("httpResponse", logEntryDTO.getHttpResponse());
                        }
                        Map<String, Object> entry = new LinkedHashMap<>();
                        Description description = proxiedRequestsDescriptionProcessor.description(logEntryDTO.getHttpRequest());
                        if (description != null) {
                            entry.put("description", description);
                        }
                        entry.put("value", value);
                        entry.put("key", logEntryDTO.getId() + "_proxied");
                        if (!value.isEmpty()) {
                            entry.put("timestamp", logEntryDTO.getTimestamp());
                            proxiedRequests.add(entry);
                        }
                    }
                }
            });
    }

    // Lazily created, bounded like expectationRequestDefinitions (one entry per live expectation).
    // CircularHashMap is not thread-safe, so every access is under activeExpectationJsonCacheLock.
    private Map<String, ActiveExpectationJson> activeExpectationJsonCache() {
        if (activeExpectationJsonCache == null) {
            activeExpectationJsonCache = new CircularHashMap<>(httpState.getConfiguration().maxExpectations());
        }
        return activeExpectationJsonCache;
    }

    /**
     * Returns the serialised JSON tree for the given matcher's expectation, reusing the cached tree
     * when the expectation is unchanged. "Unchanged" is judged conservatively against the CURRENT
     * matcher state: the same Expectation object reference AND the same remaining Times as when the
     * tree was built. A control-plane edit swaps the reference; the serving path only ever changes
     * remaining Times among the serialised fields — so any change forces a re-serialise and the tree
     * can never be stale. The heavyweight serialisation runs OUTSIDE the lock; only the lookup and
     * store are inside it. Concurrent callers may both serialise the same id — harmless, the trees are
     * byte-identical for the same (reference, remainingTimes) — and the cached tree is thereafter only
     * ever read (Jackson serialisation does not mutate it), so sharing it across connections is safe.
     */
    private JsonNode activeExpectationValue(HttpRequestMatcher requestMatcher) {
        Expectation expectation = requestMatcher.getExpectation();
        String id = expectation.getId();
        int remainingTimes = remainingTimesOf(expectation);
        synchronized (activeExpectationJsonCacheLock) {
            ActiveExpectationJson cached = activeExpectationJsonCache().get(id);
            if (cached != null && cached.expectation == expectation && cached.remainingTimes == remainingTimes) {
                return cached.value;
            }
        }
        JsonNode value = serialiseActiveExpectation(requestMatcher);
        synchronized (activeExpectationJsonCacheLock) {
            activeExpectationJsonCache().put(id, new ActiveExpectationJson(expectation, remainingTimes, value));
        }
        return value;
    }

    // Remaining Times is the ONLY serialised field a live Expectation mutates on the serving path
    // (matchCount / rotation / chaos anchor are all @JsonIgnore). Unlimited Times reports a stable -1.
    private static int remainingTimesOf(Expectation expectation) {
        Times times = expectation.getTimes();
        return times != null ? times.getRemainingTimes() : Integer.MIN_VALUE;
    }

    // The expensive step this whole cache exists to avoid: build the ExpectationDTO tree (and, for an
    // OpenAPI-defined request, splice in the expanded requestMatchers). Kept byte-identical to the
    // previous inline logic. The returned tree is not mutated after this point.
    private JsonNode serialiseActiveExpectation(HttpRequestMatcher requestMatcher) {
        activeExpectationSerialisationCount.incrementAndGet();
        Expectation expectation = requestMatcher.getExpectation();
        JsonNode expectationJsonNode = objectMapper.valueToTree(new ExpectationDTO(expectation));
        if (expectation.getHttpRequest() instanceof OpenAPIDefinition) {
            JsonNode httpRequestJsonNode = expectationJsonNode.get("httpRequest");
            if (httpRequestJsonNode instanceof ObjectNode) {
                ((ObjectNode) httpRequestJsonNode).set("requestMatchers", objectMapper.valueToTree(requestMatcher.getHttpRequests()));
            }
        }
        return expectationJsonNode;
    }

    /**
     * Number of genuine expectation serialisations performed (cache misses). Reused cached trees do
     * not increment it. Test-only: proves an unchanged expectation set is not re-serialised and that
     * an added / edited / Times-consumed expectation is re-serialised exactly once.
     */
    @VisibleForTesting
    long activeExpectationSerialisationCountForTesting() {
        return activeExpectationSerialisationCount.get();
    }

    // Immutable memo entry: the Expectation reference it was built from, the remaining Times at that
    // moment, and the resulting JSON tree. Identity + remainingTimes together are the conservative
    // "still current?" signal (see activeExpectationValue).
    private static final class ActiveExpectationJson {
        private final Expectation expectation;
        private final int remainingTimes;
        private final JsonNode value;

        private ActiveExpectationJson(Expectation expectation, int remainingTimes, JsonNode value) {
            this.expectation = expectation;
            this.remainingTimes = remainingTimes;
            this.value = value;
        }
    }

}
