package org.mockserver.netty.grpc;

import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http2.DefaultHttp2DataFrame;
import io.netty.handler.codec.http2.DefaultHttp2Headers;
import io.netty.handler.codec.http2.DefaultHttp2HeadersFrame;
import io.netty.handler.codec.http2.Http2StreamFrameToHttpObjectCodec;
import org.junit.After;
import org.junit.Test;
import org.mockserver.configuration.Configuration;
import org.mockserver.dashboard.DashboardWebSocketHandler;
import org.mockserver.grpc.GrpcProtoDescriptorStore;
import org.mockserver.grpc.GrpcStatusMapper;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.matchers.Times;
import org.mockserver.mock.Expectation;
import org.mockserver.mock.HttpState;
import org.mockserver.mock.breakpoint.BreakpointMatcherRegistry;
import org.mockserver.mock.breakpoint.BreakpointPhase;
import org.mockserver.mock.breakpoint.StreamFrameBreakpointRegistry;
import org.mockserver.mock.breakpoint.StreamFrameCallbackDispatcher;
import org.mockserver.model.GrpcBidiResponse;
import org.mockserver.model.GrpcBidiRule;
import org.mockserver.model.HttpRequest;
import org.mockserver.netty.HttpRequestHandler;
import org.mockserver.netty.unification.TraceContextHandler;
import org.mockserver.netty.websocketregistry.CallbackWebSocketServerHandler;
import org.mockserver.scheduler.Scheduler;

import java.nio.file.Paths;
import java.util.Base64;
import java.util.EnumSet;
import java.util.ArrayList;
import java.util.List;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockserver.configuration.Configuration.configuration;

/**
 * Inbound gRPC metadata must be matchable on an HTTP/2 <strong>bidirectional-streaming</strong>
 * expectation.
 * <p>
 * <strong>The gap this pins.</strong> {@code GrpcBidiRouterHandler} used to synthesise the request it
 * matched against from the {@code :path} alone, fabricating only {@code content-type},
 * {@code x-grpc-service} and {@code x-grpc-method}. Every real inbound header on the opening HEADERS
 * frame was discarded before matching, so no metadata matching happened at all on h2 bidi — while
 * HTTP/3 mapped the same headers correctly via {@code Http3RequestBridge}. The user-visible symptom
 * was a live protocol-parity bug: {@code withHeader("x-tenant-id", ...)} on a bidi expectation
 * matched over h3 and silently did not over h2.
 * <p>
 * These tests drive {@code GrpcBidiRouterHandler} directly with an {@link EmbeddedChannel}, because
 * the routing decision (install {@code GrpcBidiStreamHandler} vs fall back to the re-aggregating
 * chain) <em>is</em> the observable outcome of the match.
 */
public class GrpcBidiMetadataMatchingTest {

    private final List<HttpState> httpStates = new ArrayList<>();

    @After
    public void stopHttpStates() {
        httpStates.forEach(HttpState::stop);
    }

    private static final String CHAT_PATH = "/com.example.grpc.GreetingService/Chat";
    private static final String CLIENT_ID = "test-h2-bidi-inbound-client";

    private static GrpcProtoDescriptorStore loadDescriptorStore() {
        GrpcProtoDescriptorStore store = new GrpcProtoDescriptorStore(new MockServerLogger());
        store.loadDescriptorSetFromPath(Paths.get("../mockserver-core/src/test/resources/grpc/greeting.dsc"));
        return store;
    }

    /**
     * Builds a router primed with the given expectation, on its own {@link HttpState}, and returns
     * the channel it is installed on together with that state.
     */
    private Router newRouter(Expectation expectation) {
        return newRouter(expectation, configuration());
    }

    private Router newRouter(Expectation expectation, Configuration config) {
        GrpcProtoDescriptorStore store = loadDescriptorStore();
        MockServerLogger logger = new MockServerLogger();
        HttpState httpState = new HttpState(config, logger, mock(Scheduler.class));
        httpStates.add(httpState);
        httpState.add(expectation);

        CallbackWebSocketServerHandler wsHandler = new CallbackWebSocketServerHandler(httpState);
        DashboardWebSocketHandler dashHandler = new DashboardWebSocketHandler(httpState, false, false);
        TraceContextHandler traceHandler = new TraceContextHandler(config);
        HttpRequestHandler reqHandler = new HttpRequestHandler(
            config, mock(org.mockserver.lifecycle.LifeCycle.class), httpState,
            mock(org.mockserver.mock.action.http.HttpActionHandler.class)
        );
        GrpcBidiRouterHandler router = new GrpcBidiRouterHandler(
            config, store, logger, false, null,
            wsHandler, dashHandler, null, traceHandler, null,
            new GrpcToHttpResponseHandler(logger, store),
            new GrpcToHttpRequestHandler(logger, store),
            reqHandler,
            httpState
        );
        return new Router(new EmbeddedChannel(router), httpState, config, logger);
    }

    private static final class Router {
        final EmbeddedChannel channel;
        final HttpState httpState;
        final Configuration configuration;
        final MockServerLogger logger;

        Router(EmbeddedChannel channel, HttpState httpState, Configuration configuration, MockServerLogger logger) {
            this.channel = channel;
            this.httpState = httpState;
            this.configuration = configuration;
            this.logger = logger;
        }
    }

    /**
     * Routes one HEADERS frame through a router primed with the given expectation, and reports
     * whether the bidi handler was installed — i.e. whether the expectation matched.
     */
    private boolean routesToBidiHandler(Expectation expectation, DefaultHttp2Headers inboundHeaders) {
        return routesToBidiHandler(expectation, inboundHeaders, configuration());
    }

    private boolean routesToBidiHandler(Expectation expectation, DefaultHttp2Headers inboundHeaders, Configuration configuration) {
        Router router = newRouter(expectation, configuration);
        try {
            router.channel.writeInbound(new DefaultHttp2HeadersFrame(inboundHeaders, false));
            boolean bidiInstalled = router.channel.pipeline().get(GrpcBidiStreamHandler.class) != null;
            if (!bidiInstalled) {
                // the only other outcome is the re-aggregating fallback chain
                assertThat("a non-match must fall back to the re-aggregating chain",
                    router.channel.pipeline().get(Http2StreamFrameToHttpObjectCodec.class), is(notNullValue()));
            }
            return bidiInstalled;
        } finally {
            router.channel.finishAndReleaseAll();
        }
    }

    private static Expectation bidiExpectationRequiring(HttpRequest requestMatcher) {
        return new Expectation(requestMatcher, Times.unlimited(), null, 0)
            .thenRespondWithGrpcBidi(
                GrpcBidiResponse.grpcBidiResponse()
                    .withRule(GrpcBidiRule.grpcBidiRule(".*").withResponse("{\"greeting\": \"echo\"}"))
                    .withStatusName("OK"));
    }

    private static DefaultHttp2Headers chatHeaders() {
        DefaultHttp2Headers headers = new DefaultHttp2Headers();
        headers.method("POST");
        headers.path(CHAT_PATH);
        headers.set("content-type", GrpcStatusMapper.GRPC_CONTENT_TYPE);
        return headers;
    }

    /**
     * The headline parity case: an expectation qualified by a metadata header matches when the
     * client sent it. This did not match before the fix.
     */
    @Test
    public void shouldMatchBidiExpectationOnInboundMetadataHeader() {
        DefaultHttp2Headers headers = chatHeaders();
        headers.set("x-tenant-id", "acme");

        assertThat(routesToBidiHandler(
            bidiExpectationRequiring(HttpRequest.request().withPath(CHAT_PATH).withHeader("x-tenant-id", "acme")),
            headers
        ), is(true));
    }

    /**
     * The matching must be real, not merely "headers are now present": an expectation requiring a
     * metadata value the client did not send must still fall through. Without this, mapping the
     * headers could have been indistinguishable from ignoring them.
     */
    @Test
    public void shouldNotMatchBidiExpectationWhenInboundMetadataValueDiffers() {
        DefaultHttp2Headers headers = chatHeaders();
        headers.set("x-tenant-id", "other");

        assertThat(routesToBidiHandler(
            bidiExpectationRequiring(HttpRequest.request().withPath(CHAT_PATH).withHeader("x-tenant-id", "acme")),
            headers
        ), is(false));
    }

    /**
     * With proxy authentication configured, Proxy-Authorization is the proxy hop's credential: it is removed before
     * the bidi request is matched or logged, as on every other path. Without it, the header matches as any other.
     */
    @Test
    public void shouldNotMatchBidiExpectationOnTheProxyCredentialOnlyWhenProxyAuthenticationIsConfigured() {
        DefaultHttp2Headers headers = chatHeaders();
        headers.set("proxy-authorization", "Basic dXNlcjpwYXNzd29yZA==");
        Expectation onProxyCredential = bidiExpectationRequiring(HttpRequest.request().withPath(CHAT_PATH).withHeader("proxy-authorization", ".*"));

        assertThat(routesToBidiHandler(onProxyCredential, headers,
            configuration().proxyAuthenticationUsername("user").proxyAuthenticationPassword("password")), is(false));
        assertThat(routesToBidiHandler(onProxyCredential, headers, configuration()), is(true));
    }

    /**
     * And when the header is absent entirely.
     */
    @Test
    public void shouldNotMatchBidiExpectationWhenInboundMetadataHeaderIsAbsent() {
        assertThat(routesToBidiHandler(
            bidiExpectationRequiring(HttpRequest.request().withPath(CHAT_PATH).withHeader("x-tenant-id", "acme")),
            chatHeaders()
        ), is(false));
    }

    /**
     * Part 2 and part 3 meet here: binary metadata on a bidi stream, written padded in the
     * expectation against the unpadded form a real gRPC client puts on the wire. This needs both the
     * inbound header mapping (or there is nothing to match) and the padding-insensitive comparison
     * (or the spellings differ).
     */
    @Test
    public void shouldMatchBidiExpectationOnPaddedBinaryMetadataAgainstUnpaddedWireValue() {
        DefaultHttp2Headers headers = chatHeaders();
        headers.set("x-trace-bin", "AQIDBA");

        assertThat(routesToBidiHandler(
            bidiExpectationRequiring(HttpRequest.request().withPath(CHAT_PATH)
                .withHeader("x-trace-bin", Base64.getEncoder().encodeToString(new byte[]{1, 2, 3, 4}))),
            headers
        ), is(true));
    }

    /**
     * Pseudo-headers are already represented by the request's method and path, so they must not be
     * mapped in as ordinary headers — a {@code ":path"} header on the request would be meaningless
     * and would leak HTTP/2 framing detail into the matcher and the request log.
     */
    @Test
    public void shouldNotMatchAPseudoHeaderAsAnOrdinaryHeader() {
        assertThat(routesToBidiHandler(
            bidiExpectationRequiring(HttpRequest.request().withPath(CHAT_PATH).withHeader(":path", CHAT_PATH)),
            chatHeaders()
        ), is(false));
    }

    /**
     * The pre-existing synthesized headers must survive: an expectation written against the
     * fabricated {@code content-type}, {@code x-grpc-service} and {@code x-grpc-method} still
     * matches, so mapping the real headers in is purely additive.
     */
    @Test
    public void shouldStillMatchOnTheSynthesizedGrpcHeaders() {
        assertThat(routesToBidiHandler(
            bidiExpectationRequiring(HttpRequest.request().withPath(CHAT_PATH)
                .withHeader("content-type", GrpcStatusMapper.GRPC_CONTENT_TYPE)
                .withHeader("x-grpc-service", "com.example.grpc.GreetingService")
                .withHeader("x-grpc-method", "Chat")),
            chatHeaders()
        ), is(true));
    }

    /**
     * When the inbound frame carries no {@code content-type} at all, the canonical
     * {@code application/grpc} is still synthesised, so an expectation written against it keeps
     * matching rather than regressing to a non-match.
     */
    @Test
    public void shouldSynthesizeContentTypeWhenTheInboundFrameCarriesNone() {
        DefaultHttp2Headers headers = new DefaultHttp2Headers();
        headers.method("POST");
        headers.path(CHAT_PATH);

        assertThat(routesToBidiHandler(
            bidiExpectationRequiring(HttpRequest.request().withPath(CHAT_PATH)
                .withHeader("content-type", GrpcStatusMapper.GRPC_CONTENT_TYPE)),
            headers
        ), is(true));
    }

    /**
     * A bidi expectation with no header constraints at all must be unaffected by the extra inbound
     * metadata now carried on the request.
     */
    @Test
    public void shouldStillMatchAnExpectationWithNoHeaderConstraints() {
        DefaultHttp2Headers headers = chatHeaders();
        headers.set("x-tenant-id", "acme");
        headers.set("user-agent", "grpc-java-netty/1.82.2");

        assertThat(routesToBidiHandler(
            bidiExpectationRequiring(HttpRequest.request().withPath(CHAT_PATH)),
            headers
        ), is(true));
    }

    /**
     * A received metadata value beginning {@code !} must stay literal. Mapping it with the
     * {@code String} overload would parse it as a negation, so an expectation written for the
     * literal value would stop matching while one written for its opposite would start.
     */
    @Test
    public void shouldKeepAReceivedMetadataValueBeginningWithBangLiteral() {
        DefaultHttp2Headers headers = chatHeaders();
        headers.set("x-flag", "!literal");

        assertThat("the literal value must match",
            routesToBidiHandler(
                bidiExpectationRequiring(HttpRequest.request().withPath(CHAT_PATH).withHeader("x-flag", "!literal")),
                headers
            ), is(true));
        assertThat("and it must not have been read as 'anything but literal'",
            routesToBidiHandler(
                bidiExpectationRequiring(HttpRequest.request().withPath(CHAT_PATH).withHeader("x-flag", "literal")),
                headers
            ), is(false));
    }

    /**
     * {@code x-grpc-service} is server-derived from the {@code :path}. Because {@code withHeader}
     * appends rather than replaces, a client-supplied copy would otherwise survive alongside it and
     * — header matching being SUB_SET — let an expectation qualified by a forged service name match
     * a stream belonging to a different service.
     */
    @Test
    public void shouldNotLetAClientSpoofTheDerivedServiceHeader() {
        DefaultHttp2Headers headers = chatHeaders();
        headers.set("x-grpc-service", "com.example.evil.OtherService");
        headers.set("x-grpc-method", "Evil");

        assertThat("a forged service name must not match",
            routesToBidiHandler(
                bidiExpectationRequiring(HttpRequest.request().withPath(CHAT_PATH)
                    .withHeader("x-grpc-service", "com.example.evil.OtherService")),
                headers
            ), is(false));
        assertThat("the path-derived service name is the only one on the request",
            routesToBidiHandler(
                bidiExpectationRequiring(HttpRequest.request().withPath(CHAT_PATH)
                    .withHeader("x-grpc-service", "com.example.grpc.GreetingService")
                    .withHeader("x-grpc-method", "Chat")),
                headers
            ), is(true));
    }

    /**
     * Sanity: the router still refuses to route a bidi stream when the path does not match, rather
     * than installing the bidi handler unconditionally now that more headers are present.
     */
    @Test
    public void shouldFallBackWhenNoExpectationMatchesAtAll() {
        assertThat(routesToBidiHandler(
            bidiExpectationRequiring(HttpRequest.request().withPath("/some.other.Service/Method")),
            chatHeaders()
        ), is(false));
    }

    /**
     * The {@code INBOUND_STREAM} breakpoint lookup uses the same synthesised request, so a
     * breakpoint matcher may be qualified by gRPC metadata exactly as an expectation may. It could
     * not be before, for the same reason — the request the registry was asked about carried only a
     * method and a path.
     * <p>
     * <strong>Observed through the router, not through the registry.</strong> Asking
     * {@code BreakpointMatcherRegistry.findMatch} with a request the test builds by hand would only
     * prove the registry works; it would pass whatever the router does. The observable consequence
     * of the router's own lookup is that an inbound DATA frame is <em>parked</em> and dispatched to
     * the registered callback client instead of being decoded, so that is what is asserted — a
     * breakpoint notification reaches the client for the metadata-carrying stream and not for the
     * bare one.
     */
    @Test
    public void shouldParkInboundFramesWhenABreakpointMatchesOnMetadata() {
        assertThat("a metadata-qualified inbound breakpoint must park the frame when the header is sent",
            dispatchesInboundBreakpoint(true), is(true));
        assertThat("and must not park anything for a stream that did not carry the metadata",
            dispatchesInboundBreakpoint(false), is(false));
    }

    /**
     * Drives a bidi stream carrying (or not carrying) {@code x-tenant-id: acme} past a breakpoint
     * matcher qualified by that header, and reports whether the inbound DATA frame was dispatched to
     * the callback client.
     */
    private boolean dispatchesInboundBreakpoint(boolean sendMetadata) {
        BreakpointMatcherRegistry.getInstance().clear();
        StreamFrameBreakpointRegistry.getInstance().reset();
        StreamFrameCallbackDispatcher.getInstance().reset();

        Router router = newRouter(bidiExpectationRequiring(HttpRequest.request().withPath(CHAT_PATH)));
        EmbeddedChannel clientChannel = new EmbeddedChannel();
        try {
            // a connected callback client is required, or dispatchFrame returns null and the handler
            // falls straight through to decoding
            ChannelHandlerContext clientCtx = mock(ChannelHandlerContext.class);
            when(clientCtx.channel()).thenReturn(clientChannel);
            router.httpState.getWebSocketClientRegistry().registerClient(CLIENT_ID, clientCtx);
            clientChannel.readOutbound(); // drain the registration confirmation

            BreakpointMatcherRegistry.getInstance().register(
                HttpRequest.request().withPath(CHAT_PATH).withHeader("x-tenant-id", "acme"),
                EnumSet.of(BreakpointPhase.INBOUND_STREAM), CLIENT_ID,
                router.configuration, router.logger);

            DefaultHttp2Headers headers = chatHeaders();
            if (sendMetadata) {
                headers.set("x-tenant-id", "acme");
            }
            router.channel.writeInbound(new DefaultHttp2HeadersFrame(headers, false));
            assertThat("the bidi handler must be installed for this to be a meaningful observation",
                router.channel.pipeline().get(GrpcBidiStreamHandler.class), is(notNullValue()));

            // any bytes will do — the breakpoint dispatch happens before gRPC decoding
            router.channel.writeInbound(new DefaultHttp2DataFrame(
                Unpooled.wrappedBuffer(new byte[]{0, 0, 0}), false));

            return clientChannel.readOutbound() != null;
        } finally {
            router.channel.finishAndReleaseAll();
            clientChannel.finishAndReleaseAll();
            BreakpointMatcherRegistry.getInstance().clear();
            StreamFrameBreakpointRegistry.getInstance().reset();
            StreamFrameCallbackDispatcher.getInstance().reset();
        }
    }
}
