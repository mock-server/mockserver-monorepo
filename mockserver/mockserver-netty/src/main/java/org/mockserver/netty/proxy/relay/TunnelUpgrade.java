package org.mockserver.netty.proxy.relay;

import io.netty.channel.Channel;
import io.netty.channel.ChannelPipeline;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpServerCodec;
import org.mockserver.codec.HttpChunkLineLimiter;
import org.mockserver.codec.HttpLineEndSplitGuard;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.netty.connection.HttpExchangeTracker;
import org.mockserver.netty.connection.InboundConnectionActivity;
import org.mockserver.netty.unification.HttpServerCodecResponsePairing;

/**
 * An HTTP/1.1 tunnel whose request MockServer answered with {@code 101 Switching Protocols} (a WebSocket, mocked or
 * relayed) carries the new protocol from then on: both legs stop decoding HTTP and pass bytes through as they are,
 * as a tunnel that never carried HTTP does ({@link TunnelBinaryPassthrough}). The loopback's codec already passes on
 * what follows the {@code 101} as bytes ({@link LoopbackHttpClientCodec}).
 */
final class TunnelUpgrade {

    private TunnelUpgrade() {
    }

    static boolean isUpgrade(Object msg) {
        return msg instanceof HttpResponse && ((HttpResponse) msg).status().code() == HttpResponseStatus.SWITCHING_PROTOCOLS.code();
    }

    /**
     * Call on the tunnel's event loop once the {@code 101} has been written to the proxy client's leg. The client
     * leg's HTTP handlers are removed after the passthrough is in place, so the bytes its codec still holds reach it.
     */
    static void switchToBytes(Channel proxyClient, Channel loopback, MockServerLogger mockServerLogger) {
        // a WebSocket may legitimately stay silent for long periods
        InboundConnectionActivity.markLongLived(proxyClient);
        TunnelBinaryPassthrough.install(proxyClient, loopback, mockServerLogger);
        ChannelPipeline pipeline = proxyClient.pipeline();
        remove(pipeline, UpstreamProxyRelayHandler.class);
        remove(pipeline, HttpObjectAggregator.class);
        remove(pipeline, HttpExchangeTracker.class);
        HttpChunkLineLimiter.removeFrom(pipeline);
        HttpServerCodecResponsePairing.removeFrom(pipeline);
        HttpLineEndSplitGuard.removeFrom(pipeline);
        remove(pipeline, HttpServerCodec.class);
    }

    private static void remove(ChannelPipeline pipeline, Class<? extends io.netty.channel.ChannelHandler> handlerType) {
        if (pipeline.get(handlerType) != null) {
            pipeline.remove(handlerType);
        }
    }
}
