package org.mockserver.netty.proxy.relay;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPromise;
import io.netty.channel.DefaultChannelPromise;
import io.netty.channel.EventLoop;
import io.netty.channel.WriteBufferWaterMark;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.util.concurrent.ImmediateEventExecutor;
import org.mockito.ArgumentCaptor;
import org.mockito.stubbing.Answer;
import org.mockserver.configuration.Configuration;
import org.mockserver.httpclient.NettyHttpClient;
import org.mockserver.log.model.LogEntry;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.mock.HttpState;
import org.mockserver.model.BinaryMessage;
import org.mockserver.netty.proxy.BinaryRequestProxyingHandler;
import org.mockserver.scheduler.Scheduler;
import org.mockserver.socket.ChannelReadPause;
import org.slf4j.event.Level;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockserver.configuration.Configuration.configuration;
import static org.mockserver.mock.action.http.HttpActionHandler.REMOTE_SOCKET;

/**
 * A binary client connection and its upstream connection as two embedded channels, with the real
 * {@link BinaryRequestProxyingHandler} on the first, so every step of the relay between them is taken by the test.
 */
final class BinaryRelayHarness {

    static final long STALL_TIMEOUT_MILLIS = 1_000;
    static final InetSocketAddress TARGET = new InetSocketAddress("127.0.0.1", 1234);

    final Configuration configuration = configuration()
        .responseWriteStallTimeoutMillis(STALL_TIMEOUT_MILLIS);
    final NettyHttpClient httpClient = mock(NettyHttpClient.class);
    final MockServerLogger mockServerLogger = mock(MockServerLogger.class);
    final Scheduler scheduler = mock(Scheduler.class);
    /** Listener calls handed to the scheduler and not yet run. */
    final List<Runnable> listenerCalls = new ArrayList<>();
    final FlushGate upstreamFlushGate = new FlushGate();
    final FlushGate clientFlushGate = new FlushGate();
    EmbeddedChannel client;
    EmbeddedChannel upstream;
    ChannelPromise connect;
    int upstreamConnections;

    /**
     * @param connectAtOnce whether the upstream connection is connected as soon as it is asked for, or when the test
     *                      completes {@link #connect}
     */
    BinaryRelayHarness(boolean connectAtOnce) {
        when(mockServerLogger.isEnabledForInstance(any(Level.class))).thenReturn(true);
        Answer<ChannelFuture> connects = invocation -> {
            upstreamConnections++;
            upstream = new EmbeddedChannel(upstreamFlushGate, invocation.<ChannelHandler>getArgument(3));
            // any write that is not flushed makes the upstream unwritable
            upstream.config().setWriteBufferWaterMark(new WriteBufferWaterMark(8, 32));
            connect = upstream.newPromise();
            // as Bootstrap.connect does, ahead of any listener its caller adds
            connect.addListener(ChannelFutureListener.CLOSE_ON_FAILURE);
            if (connectAtOnce) {
                connect.setSuccess();
            }
            return connect;
        };
        when(httpClient.connectBinaryRelay(any(EventLoop.class), any(InetSocketAddress.class), anyBoolean(), any(ChannelHandler.class))).thenAnswer(connects);
        when(httpClient.connectBinaryRelay(any(EventLoop.class), any(InetSocketAddress.class), anyBoolean(), any(ChannelHandler.class), any(InetSocketAddress.class))).thenAnswer(connects);
        doAnswer(invocation -> listenerCalls.add(invocation.getArgument(0)))
            .when(scheduler).scheduleLocalCallback(any(Runnable.class), anyBoolean());
    }

    /**
     * The next upstream connect fails before its channel is registered, as {@code Bootstrap.connect} does when no
     * socket can be opened: its channel is closed without its close future ever completing, and the future fails
     * at once, or, as when Netty's global executor runs the listeners, when the test fails {@link #connect}.
     */
    void connectFailsBeforeRegistration(Throwable cause, boolean atOnce) {
        when(httpClient.connectBinaryRelay(any(EventLoop.class), any(InetSocketAddress.class), anyBoolean(), any(ChannelHandler.class))).thenAnswer(invocation -> {
            upstreamConnections++;
            upstream = new EmbeddedChannel(false, false);
            upstream.unsafe().closeForcibly();
            connect = new DefaultChannelPromise(upstream, ImmediateEventExecutor.INSTANCE);
            if (atOnce) {
                connect.setFailure(cause);
            }
            return connect;
        });
    }

    /** Called once the configuration is as the test wants it: the handler reads the listener when it is built. */
    EmbeddedChannel clientConnection() {
        client = new EmbeddedChannel();
        client.attr(REMOTE_SOCKET).set(TARGET);
        client.pipeline().addLast(clientFlushGate);
        client.pipeline().addLast(new BinaryRequestProxyingHandler(configuration, mockServerLogger, scheduler, httpClient, mock(HttpState.class)));
        return client;
    }

    void clientSends(String message) {
        client.writeInbound(Unpooled.copiedBuffer(message, StandardCharsets.UTF_8));
    }

    void upstreamSends(String message) {
        upstream.writeInbound(Unpooled.copiedBuffer(message, StandardCharsets.UTF_8));
    }

    /** What has been written and flushed to the upstream since this was last called. */
    String receivedByUpstream() {
        return drain(upstream);
    }

    /** What has been written and flushed to the client since this was last called. */
    String receivedByClient() {
        return drain(client);
    }

    private static String drain(EmbeddedChannel channel) {
        StringBuilder received = new StringBuilder();
        for (ByteBuf written; (written = channel.readOutbound()) != null; ) {
            received.append(new String(ByteBufUtil.getBytes(written), StandardCharsets.UTF_8));
            written.release();
        }
        return received.toString();
    }

    /** As a socket whose send buffer is full, or has room again, reports itself. */
    static void setWritable(EmbeddedChannel channel, boolean writable) {
        channel.unsafe().outboundBuffer().setUserDefinedWritability(1, writable);
        channel.runPendingTasks();
    }

    static boolean isReading(EmbeddedChannel channel) {
        return channel.config().isAutoRead() && ChannelReadPause.holds(channel) == 0;
    }

    /** Runs the listener call the scheduler was handed first, then what its return does on the client's event loop. */
    void runNextListenerCall() {
        listenerCalls.remove(0).run();
        client.runPendingTasks();
    }

    void stallTimeoutPasses() {
        timePasses(STALL_TIMEOUT_MILLIS);
    }

    void timePasses(long millis) {
        client.advanceTimeBy(millis, TimeUnit.MILLISECONDS);
        client.runScheduledPendingTasks();
    }

    List<LogEntry> logged(Level level) {
        return logged().stream().filter(entry -> entry.getLogLevel() == level).collect(Collectors.toList());
    }

    List<LogEntry> logged() {
        ArgumentCaptor<LogEntry> entries = ArgumentCaptor.forClass(LogEntry.class);
        verify(mockServerLogger, atLeast(0)).logEvent(entries.capture());
        return entries.getAllValues();
    }

    void finish() {
        client.finishAndReleaseAll();
        if (upstream == null) {
            return;
        }
        if (upstream.isRegistered()) {
            upstream.finishAndReleaseAll();
        } else {
            // closed and deregistered already, by the relay or by a connect that failed, so it cannot be closed
            // again; what it was given stays queued until released
            upstream.releaseInbound();
            upstream.releaseOutbound();
        }
    }

    /** Stands in for a socket that takes nothing: what is written stays in the channel's outbound buffer. */
    @ChannelHandler.Sharable
    static final class FlushGate extends ChannelOutboundHandlerAdapter {
        boolean blocked;

        @Override
        public void flush(ChannelHandlerContext ctx) {
            if (!blocked) {
                ctx.flush();
            }
        }
    }
}
