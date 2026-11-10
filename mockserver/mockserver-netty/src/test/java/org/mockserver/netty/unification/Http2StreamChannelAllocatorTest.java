package org.mockserver.netty.unification;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http2.DefaultHttp2FrameWriter;
import io.netty.handler.codec.http2.DefaultHttp2Headers;
import io.netty.handler.codec.http2.Http2Settings;
import io.netty.handler.codec.http2.Http2StreamChannel;
import org.junit.After;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockserver.configuration.Configuration;
import org.mockserver.lifecycle.LifeCycle;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.mock.HttpState;
import org.mockserver.mock.action.http.HttpActionHandler;
import org.mockserver.netty.MockServerUnificationInitializer;
import org.mockserver.scheduler.Scheduler;
import org.mockserver.socket.NettyAllocator;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.sameInstance;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockserver.configuration.Configuration.configuration;

/**
 * A request served on an HTTP/2 stream must be handled with the same pooled allocator as every
 * other MockServer channel. Netty creates each stream's child channel with a fresh default config,
 * so without an explicit pin the handlers on the stream allocate from Netty 4.2's adaptive default
 * even though the connection itself is pooled.
 */
public class Http2StreamChannelAllocatorTest {

    private final List<HttpState> httpStates = new ArrayList<>();

    @After
    public void stopHttpStates() {
        httpStates.forEach(HttpState::stop);
    }

    private static final String H2C_PREFACE = "PRI * HTTP/2.0\r\n\r\nSM\r\n\r\n";

    @Test
    public void shouldServeHttp2StreamWithSharedPooledAllocator() {
        Configuration config = configuration();
        HttpActionHandler actionHandler = mock(HttpActionHandler.class);
        EmbeddedChannel connection = new EmbeddedChannel();
        // mirror MockServer's childOption: the connection channel itself is pooled
        connection.config().setAllocator(NettyAllocator.ALLOCATOR);
        HttpState httpState = new HttpState(config, new MockServerLogger(), mock(Scheduler.class));
        httpStates.add(httpState);
        connection.pipeline().addLast(new MockServerUnificationInitializer(
            config,
            mock(LifeCycle.class),
            httpState,
            actionHandler,
            null
        ));
        try {
            connection.writeInbound(Unpooled.wrappedBuffer(H2C_PREFACE.getBytes(StandardCharsets.US_ASCII)));
            connection.writeInbound(clientSettingsAndGetRequest());
            connection.runPendingTasks();

            ArgumentCaptor<ChannelHandlerContext> handlerContext = ArgumentCaptor.forClass(ChannelHandlerContext.class);
            verify(actionHandler).processAction(any(), any(), handlerContext.capture(), anySet(), anyBoolean(), anyBoolean(), eq(true));

            assertThat(handlerContext.getValue().channel(), instanceOf(Http2StreamChannel.class));
            assertThat(handlerContext.getValue().alloc(), sameInstance(NettyAllocator.ALLOCATOR));
        } finally {
            connection.finishAndReleaseAll();
        }
    }

    private static ByteBuf clientSettingsAndGetRequest() {
        EmbeddedChannel encoder = new EmbeddedChannel(new ChannelInboundHandlerAdapter());
        try {
            ChannelHandlerContext ctx = encoder.pipeline().firstContext();
            DefaultHttp2FrameWriter frameWriter = new DefaultHttp2FrameWriter();
            frameWriter.writeSettings(ctx, new Http2Settings(), ctx.newPromise());
            DefaultHttp2Headers headers = new DefaultHttp2Headers();
            headers.method("GET").path("/allocator").scheme("http").authority("localhost");
            // stream 3, not 1: Http2MultiplexHandler detects a server by a ServerChannel parent, which an
            // EmbeddedChannel lacks, and would treat stream 1 as a client-side h2c upgrade stream
            frameWriter.writeHeaders(ctx, 3, headers, 0, true, ctx.newPromise());
            ctx.flush();
            ByteBuf frames = Unpooled.buffer();
            ByteBuf frame;
            while ((frame = encoder.readOutbound()) != null) {
                frames.writeBytes(frame);
                frame.release();
            }
            return frames;
        } finally {
            encoder.finishAndReleaseAll();
        }
    }
}
