package org.mockserver.netty.unification;

import io.netty.handler.codec.http2.AbstractHttp2ConnectionHandlerBuilder;
import io.netty.handler.codec.http2.Http2Connection;
import io.netty.handler.codec.http2.Http2ConnectionDecoder;
import io.netty.handler.codec.http2.Http2ConnectionEncoder;
import io.netty.handler.codec.http2.Http2FrameListener;
import io.netty.handler.codec.http2.Http2FrameLogger;
import io.netty.handler.codec.http2.Http2Settings;

/**
 * Builder for {@link StreamRoutingHttpToHttp2ConnectionHandler}.
 * <p>
 * Netty's own {@code HttpToHttp2ConnectionHandlerBuilder} is {@code final}, so a custom subclass of
 * the handler cannot be installed through it. The standard Netty pattern is to extend
 * {@link AbstractHttp2ConnectionHandlerBuilder} and override the protected
 * {@link #build(Http2ConnectionDecoder, Http2ConnectionEncoder, Http2Settings)} factory method to
 * return the desired handler. Only the setters {@link PortUnificationHandler} actually uses are
 * re-exposed as {@code public}.
 */
public final class StreamRoutingHttpToHttp2ConnectionHandlerBuilder
    extends AbstractHttp2ConnectionHandlerBuilder<StreamRoutingHttpToHttp2ConnectionHandler, StreamRoutingHttpToHttp2ConnectionHandlerBuilder> {

    @Override
    public StreamRoutingHttpToHttp2ConnectionHandlerBuilder initialSettings(Http2Settings settings) {
        return super.initialSettings(settings);
    }

    @Override
    public StreamRoutingHttpToHttp2ConnectionHandlerBuilder frameListener(Http2FrameListener frameListener) {
        return super.frameListener(frameListener);
    }

    @Override
    public StreamRoutingHttpToHttp2ConnectionHandlerBuilder connection(Http2Connection connection) {
        return super.connection(connection);
    }

    @Override
    public StreamRoutingHttpToHttp2ConnectionHandlerBuilder frameLogger(Http2FrameLogger frameLogger) {
        return super.frameLogger(frameLogger);
    }

    @Override
    public StreamRoutingHttpToHttp2ConnectionHandler build() {
        return super.build();
    }

    @Override
    protected StreamRoutingHttpToHttp2ConnectionHandler build(Http2ConnectionDecoder decoder, Http2ConnectionEncoder encoder,
                                                              Http2Settings initialSettings) {
        return new StreamRoutingHttpToHttp2ConnectionHandler(
            decoder, encoder, initialSettings, isValidateHeaders(), decoupleCloseAndGoAway(), flushPreface());
    }
}
