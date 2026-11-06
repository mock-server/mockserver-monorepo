package org.mockserver.netty.proxy.relay;

import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.handler.codec.http2.*;
import org.mockserver.log.model.LogEntry;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.netty.connection.ServerClosedChannels;
import org.slf4j.event.Level;

/**
 * Answers the proxy client's HTTP/2 stream when the CONNECT/SOCKS relay's HTTP/2 loopback stream for it ends without
 * a response: the response failed to decode, passed {@code maxRequestBodySize}, or MockServer reset the stream. The
 * loopback stream ids are not the client's ({@link LoopbackHttp2StreamIdRemapper} pairs them), so the client stream
 * paired with the loopback stream is reset, and the other streams on both connections carry on.
 * <ul>
 *   <li>MockServer reset the loopback stream: the client's stream is reset with the same error code;</li>
 *   <li>the request never reached MockServer (its HEADERS were not sent, or a GOAWAY says it was not processed):
 *       {@code REFUSED_STREAM}, which tells the client it may safely retry (RFC 9113 section 8.7);</li>
 *   <li>otherwise, a failure in the relay itself: {@code INTERNAL_ERROR}.</li>
 * </ul>
 * A stream whose whole final response has been relayed is answered ({@link LoopbackHttp2StreamIdRemapper#answered}),
 * so its close is not reset. A client stream that ends without one is unpaired, then reset on the loopback so MockServer
 * stops working on it ({@link #proxyClientFrameListener}): with the client's code when the client reset it, with
 * {@code CANCEL} when the relay did (a stream error in the client's request). A loopback connection that closes outright
 * is left to {@link LoopbackHttp2ConnectionCloseHandler}, which answers every client stream still open.
 * Sits after the loopback's {@link Http2ConnectionHandler}, on either side of the {@link LoopbackHttp2StreamIdRemapper}:
 * it reads no message, only the two connections' stream events; one per loopback. It calls the other channel's
 * {@link Http2ConnectionHandler} directly, which is safe only because the loopback is bootstrapped on the proxy
 * client's event loop ({@code RelayConnectHandler.channelRead0}).
 */
public class LoopbackHttp2StreamErrorHandler extends ChannelInboundHandlerAdapter {

    private final MockServerLogger mockServerLogger;
    private final Http2Connection loopbackConnection;
    private final LoopbackHttp2StreamIdRemapper streamIds;
    private final Channel proxyClientChannel;
    private final Http2Connection.PropertyKey remoteResetCodeKey;
    private final Http2Connection.PropertyKey localFailureKey;
    private Channel loopbackChannel;

    public LoopbackHttp2StreamErrorHandler(MockServerLogger mockServerLogger, Http2Connection loopbackConnection, LoopbackHttp2StreamIdRemapper streamIds, Channel proxyClientChannel) {
        this.mockServerLogger = mockServerLogger;
        this.loopbackConnection = loopbackConnection;
        this.streamIds = streamIds;
        this.proxyClientChannel = proxyClientChannel;
        this.remoteResetCodeKey = loopbackConnection.newKey();
        this.localFailureKey = loopbackConnection.newKey();
        loopbackConnection.addListener(new Http2ConnectionAdapter() {
            @Override
            public void onStreamClosed(Http2Stream stream) {
                loopbackStreamClosed(stream);
            }
        });
    }

    /**
     * Wraps the loopback's frame listener to record why a stream is about to be reset.
     */
    public Http2FrameListener frameListener(Http2FrameListener delegate) {
        return new Http2FrameListenerDecorator(delegate) {
            @Override
            public int onDataRead(ChannelHandlerContext ctx, int streamId, ByteBuf data, int padding, boolean endOfStream) throws Http2Exception {
                try {
                    return super.onDataRead(ctx, streamId, data, padding, endOfStream);
                } catch (Http2Exception.StreamException streamException) {
                    recordLocalFailure(streamException);
                    throw streamException;
                }
            }

            @Override
            public void onHeadersRead(ChannelHandlerContext ctx, int streamId, Http2Headers headers, int padding, boolean endOfStream) throws Http2Exception {
                try {
                    super.onHeadersRead(ctx, streamId, headers, padding, endOfStream);
                } catch (Http2Exception.StreamException streamException) {
                    recordLocalFailure(streamException);
                    throw streamException;
                }
            }

            @Override
            public void onHeadersRead(ChannelHandlerContext ctx, int streamId, Http2Headers headers, int streamDependency, short weight, boolean exclusive, int padding, boolean endOfStream) throws Http2Exception {
                try {
                    super.onHeadersRead(ctx, streamId, headers, streamDependency, weight, exclusive, padding, endOfStream);
                } catch (Http2Exception.StreamException streamException) {
                    recordLocalFailure(streamException);
                    throw streamException;
                }
            }

            @Override
            public void onRstStreamRead(ChannelHandlerContext ctx, int streamId, long errorCode) {
                // Not passed on: InboundHttp2ToHttpAdapter would only release its partial message, which it also does
                // when the stream is removed, then report the reset as an exception that closes the whole loopback.
                Http2Stream stream = loopbackConnection.stream(streamId);
                if (stream != null) {
                    stream.setProperty(remoteResetCodeKey, errorCode);
                }
            }
        };
    }

    /**
     * Wraps the proxy client's frame listener, and listens to its connection, so a client stream that ends without a
     * whole response is reset on the loopback too, and the client's other streams carry on. A reset the client sends is
     * relayed with its code; a stream the relay resets itself never reaches the frame listener, so its close is what
     * resets the loopback stream.
     */
    public Http2FrameListener proxyClientFrameListener(Http2Connection proxyClientConnection, Http2FrameListener delegate) {
        proxyClientConnection.addListener(new Http2ConnectionAdapter() {
            @Override
            public void onStreamClosed(Http2Stream stream) {
                // a closed client connection closes the loopback, and an answered stream has ended as it should
                if (proxyClientChannel.isActive() && !streamIds.answered(stream.id())) {
                    boolean resetByServer = ServerClosedChannels.consumeHttp2StreamReset(proxyClientChannel, stream.id());
                    resetLoopbackStream(stream.id(), Http2Error.CANCEL.code(), resetByServer);
                }
            }
        });
        return new Http2FrameListenerDecorator(delegate) {
            @Override
            public void onRstStreamRead(ChannelHandlerContext ctx, int streamId, long errorCode) {
                // not passed on, for the same reason as the loopback's: the adapter would close the whole tunnel
                resetLoopbackStream(streamId, errorCode, false);
            }
        };
    }

    @Override
    public void handlerAdded(ChannelHandlerContext ctx) {
        loopbackChannel = ctx.channel();
    }

    private void recordLocalFailure(Http2Exception.StreamException streamException) {
        Http2Stream stream = loopbackConnection.stream(streamException.streamId());
        if (stream != null) {
            stream.setProperty(localFailureKey, streamException);
        }
    }

    /**
     * @param resetByServer MockServer reset the client's stream (on a write stall), so the response writer on the
     *                      loopback's far end must not take this reset for the client leaving
     */
    private void resetLoopbackStream(int clientStreamId, long errorCode, boolean resetByServer) {
        ChannelHandlerContext loopbackCtx = loopbackChannel != null && loopbackChannel.isActive() ? loopbackChannel.pipeline().context(Http2ConnectionHandler.class) : null;
        Integer loopbackStreamId = streamIds.loopbackStreamId(clientStreamId);
        Http2Stream loopbackStream = loopbackStreamId != null ? loopbackConnection.stream(loopbackStreamId) : null;
        // a loopback stream that is closing is what ended the client's stream, and is not answered with a reset
        if (loopbackCtx != null && loopbackStream != null && loopbackStream.state() != Http2Stream.State.CLOSED) {
            // marked only for a reset that is sent: a stream already closing has no close left to forget the mark
            if (resetByServer) {
                ServerClosedChannels.markRelayedHttp2StreamReset(proxyClientChannel, loopbackStreamId);
            }
            // unpaired first, so this reset's close of the loopback stream is not relayed to the client as a reset
            streamIds.unpair(clientStreamId);
            ((Http2ConnectionHandler) loopbackCtx.handler()).resetStream(loopbackCtx, loopbackStreamId, errorCode, loopbackCtx.newPromise());
            loopbackCtx.flush();
        }
    }

    private void loopbackStreamClosed(Http2Stream loopbackStream) {
        if (loopbackChannel == null || !loopbackChannel.isActive()) {
            // the loopback connection closed: LoopbackHttp2ConnectionCloseHandler answers the client's streams
            return;
        }
        Integer clientStreamId = streamIds.clientStreamId(loopbackStream.id());
        if (clientStreamId == null || streamIds.answered(clientStreamId)) {
            return;
        }
        Long remoteResetCode = loopbackStream.getProperty(remoteResetCodeKey);
        long errorCode;
        if (remoteResetCode != null) {
            errorCode = remoteResetCode;
        } else if (!loopbackStream.isHeadersSent() || (loopbackConnection.goAwayReceived() && loopbackStream.id() > loopbackConnection.local().lastStreamKnownByPeer())) {
            errorCode = Http2Error.REFUSED_STREAM.code();
        } else {
            errorCode = Http2Error.INTERNAL_ERROR.code();
        }
        resetProxyClientStream(clientStreamId, errorCode, remoteResetCode == null, loopbackStream.getProperty(localFailureKey));
    }

    private void resetProxyClientStream(int streamId, long errorCode, boolean relayFailure, Throwable cause) {
        ChannelHandlerContext clientCtx = proxyClientChannel.pipeline().context(Http2ConnectionHandler.class);
        if (clientCtx == null || !proxyClientChannel.isActive() || ((Http2ConnectionHandler) clientCtx.handler()).connection().stream(streamId) == null) {
            return;
        }
        // a reset MockServer chose is relayed as it is; it already logged its own action
        if (relayFailure && mockServerLogger.isEnabledForInstance(Level.WARN)) {
            mockServerLogger.logEvent(
                new LogEntry()
                    .setLogLevel(Level.WARN)
                    .setMessageFormat("relayed stream {} to {} ended without a response, resetting the client's stream with error code {}")
                    .setArguments(streamId, proxyClientChannel.remoteAddress(), errorCode)
                    .setThrowable(cause)
            );
        }
        ((Http2ConnectionHandler) clientCtx.handler()).resetStream(clientCtx, streamId, errorCode, clientCtx.newPromise());
        clientCtx.flush();
    }
}
