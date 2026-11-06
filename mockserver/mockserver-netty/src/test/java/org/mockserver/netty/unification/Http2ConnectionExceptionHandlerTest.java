package org.mockserver.netty.unification;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import io.netty.buffer.UnpooledByteBufAllocator;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.DecoderException;
import io.netty.handler.codec.http2.DefaultHttp2FrameWriter;
import io.netty.handler.codec.http2.DefaultHttp2Headers;
import io.netty.handler.codec.http2.DefaultHttp2PingFrame;
import io.netty.handler.codec.http2.Http2Error;
import io.netty.handler.codec.http2.Http2Exception;
import io.netty.handler.codec.http2.Http2FrameTypes;
import io.netty.handler.codec.http2.Http2FrameWriter;
import io.netty.handler.codec.http2.Http2Headers;
import io.netty.handler.codec.http2.Http2MultiplexHandler;
import io.netty.handler.codec.http2.Http2Settings;
import io.netty.handler.ssl.NotSslRecordException;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.handler.ssl.SslHandler;
import io.netty.handler.ssl.SslProvider;
import io.netty.handler.ssl.util.InsecureTrustManagerFactory;
import io.netty.handler.ssl.util.SelfSignedCertificate;
import io.netty.util.internal.OutOfDirectMemoryError;
import org.junit.After;
import org.junit.Test;
import org.mockserver.configuration.Configuration;
import org.mockserver.exception.ExceptionHandling;
import org.mockserver.lifecycle.LifeCycle;
import org.mockserver.log.model.LogEntry;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.mock.HttpState;
import org.mockserver.mock.action.http.HttpActionHandler;
import org.mockserver.netty.MockServerUnificationInitializer;
import org.mockserver.netty.connection.ServerClosedChannels;
import org.mockserver.scheduler.Scheduler;
import org.slf4j.event.Level;

import javax.net.ssl.SSLException;
import java.io.IOException;
import java.lang.reflect.Constructor;
import java.net.SocketException;
import java.nio.channels.ClosedChannelException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.both;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.endsWith;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.lessThan;
import static org.hamcrest.Matchers.lessThanOrEqualTo;
import static org.hamcrest.Matchers.nullValue;
import static org.hamcrest.Matchers.sameInstance;
import static org.hamcrest.Matchers.startsWith;
import static org.mockito.Mockito.mock;
import static org.mockserver.configuration.Configuration.configuration;

/**
 * What the last handler of a direct HTTP/2 connection's pipeline logs for each kind of exception that reaches it,
 * which of them it closes the connection for, and that the pipeline {@code PortUnificationHandler} assembles ends
 * with it: nothing reaches the end of Netty's pipeline, and Netty's codec still sends its {@code GOAWAY} for a
 * connection error.
 */
public class Http2ConnectionExceptionHandlerTest {

    private final List<HttpState> httpStates = new ArrayList<>();

    @After
    public void stopHttpStates() {
        httpStates.forEach(HttpState::stop);
    }

    private static final String H2C_PREFACE = "PRI * HTTP/2.0\r\n\r\nSM\r\n\r\n";
    private static final int LIMIT = 1024;

    private final List<LogEntry> logged = new ArrayList<>();
    private Level logLevel = Level.DEBUG;
    private final MockServerLogger mockServerLogger = new MockServerLogger(Http2ConnectionExceptionHandlerTest.class) {
        @Override
        public boolean isEnabledForInstance(Level level) {
            return isEnabled(level, logLevel);
        }

        @Override
        public void logEvent(LogEntry logEntry) {
            if (isEnabledForInstance(logEntry.getLogLevel())) {
                logged.add(logEntry);
            }
        }
    };

    @Test
    public void shouldLogAConnectionItsClientClosedOrResetAtDebugWithoutAStackTrace() {
        // recognised by where it was thrown, with no message to go by
        IOException closedWhileReading = new ClosedChannelException();
        closedWhileReading.setStackTrace(new StackTraceElement[]{new StackTraceElement("sun.nio.ch.SocketChannelImpl", "read", "SocketChannelImpl.java", 1)});
        for (IOException cause : new IOException[]{new SocketException("Connection reset"), new IOException("Connection reset by peer"), new IOException("Broken pipe"), closedWhileReading}) {
            EmbeddedChannel channel = new EmbeddedChannel(new Http2ConnectionExceptionHandler(mockServerLogger));
            logged.clear();

            channel.pipeline().fireExceptionCaught(cause);

            String reason = "for " + cause;
            assertThat(reason, logged, hasSize(1));
            assertThat(reason, logged.get(0).getLogLevel(), is(Level.DEBUG));
            assertThat(reason, logged.get(0).getMessageFormat(), is("HTTP/2 connection from:{}closed by its client:{}"));
            assertThat(reason, Arrays.asList(logged.get(0).getArguments()), contains(channel.remoteAddress(), cause.getMessage() != null ? cause.getMessage() : ""));
            assertThat(reason, logged.get(0).getThrowable(), is(nullValue()));
            assertThat(reason, logged.get(0).getMessage(configuration()), containsString("closed by its client:"));
            assertHandledWithoutClosing(channel);
        }
    }

    @Test
    public void shouldLogNothingForAConnectionItsClientResetAtTheDefaultLevel() {
        logLevel = Level.INFO;
        EmbeddedChannel channel = new EmbeddedChannel(new Http2ConnectionExceptionHandler(mockServerLogger));

        channel.pipeline().fireExceptionCaught(new IOException("Connection reset"));

        assertThat(logged, empty());
        assertHandledWithoutClosing(channel);
    }

    @Test
    public void shouldLogAnHttp2ConnectionErrorAsAWarningWithItsCause() {
        Http2Exception protocolError = Http2Exception.connectionError(Http2Error.PROTOCOL_ERROR, "Frame of type %d must be associated with a stream.", 0);
        // as a decoder wraps it, and with a message the closed-connection check would take for a client's reset
        Throwable[] causes = {protocolError, new DecoderException(protocolError), Http2Exception.connectionError(Http2Error.PROTOCOL_ERROR, "connection closed by a bad frame")};
        for (Throwable cause : causes) {
            EmbeddedChannel channel = new EmbeddedChannel(new Http2ConnectionExceptionHandler(mockServerLogger));
            logged.clear();

            channel.pipeline().fireExceptionCaught(cause);

            assertThat(logged, hasSize(1));
            assertThat(logged.get(0).getLogLevel(), is(Level.WARN));
            assertThat(logged.get(0).getMessageFormat(), is("closing HTTP/2 connection from:{}for connection error:{}"));
            assertThat(Arrays.asList(logged.get(0).getArguments()), contains(channel.remoteAddress(), Http2Error.PROTOCOL_ERROR));
            assertThat(logged.get(0).getThrowable(), is(sameInstance(cause)));
            assertHandledWithoutClosing(channel);
        }
    }

    @Test
    public void shouldNotLogARequestAlreadyLoggedAsRefusedForItsHeaderSize() {
        Http2Exception headerBlockTooLarge = Http2Exception.connectionError(Http2Error.PROTOCOL_ERROR, Http2RequestHeaderLimit.HEADER_BLOCK_TOO_LARGE + " (%d)", 1280);
        Http2Exception headerListTooLarge = Http2Exception.headerListSizeError(3, Http2Error.PROTOCOL_ERROR, true, "Header size exceeded max allowed size (%d)", LIMIT);
        for (Throwable cause : new Throwable[]{headerBlockTooLarge, new DecoderException(headerBlockTooLarge), headerListTooLarge}) {
            EmbeddedChannel channel = new EmbeddedChannel(new Http2ConnectionExceptionHandler(mockServerLogger));

            channel.pipeline().fireExceptionCaught(cause);

            assertThat(logged, empty());
            assertHandledWithoutClosing(channel);
        }
    }

    @Test
    public void shouldLogAnSslOrDecoderFaultOnceWithABoundedMessageAndCloseTheConnection() {
        // Netty's message for bytes that are not a TLS record is a hex dump of every one of them
        Throwable notTls = new DecoderException(new NotSslRecordException("not an SSL/TLS record: " + "00".repeat(60_000)));
        for (Throwable cause : new Throwable[]{new DecoderException("not a frame"), new RuntimeException(new SSLException("bad record")), notTls}) {
            EmbeddedChannel channel = new EmbeddedChannel(new Http2ConnectionExceptionHandler(mockServerLogger));
            logged.clear();

            channel.pipeline().fireExceptionCaught(cause);

            String reason = "for " + cause.getClass().getName();
            assertThat(reason, logged, hasSize(1));
            assertThat(reason, logged.get(0).getLogLevel(), is(Level.WARN));
            assertThat(reason, logged.get(0).getMessageFormat(), startsWith("closing HTTP/2 connection "));
            assertThat(reason, logged.get(0).getMessageFormat(), endsWith(" for SSL or decoder fault " + cause.getClass().getName() + ":{}"));
            String message = (String) logged.get(0).getArguments()[0];
            assertThat(reason, message.length(), lessThanOrEqualTo(ExceptionHandling.MAX_FAULT_MESSAGE_LENGTH));
            // the bytes themselves are left out: they are the client's, and no redaction would know a credential in them
            assertThat(reason, message, is(cause == notTls ? "io.netty.handler.ssl.NotSslRecordException: not an SSL/TLS record: 60000 bytes" : cause.getMessage()));
            assertThat("the stack trace would carry the whole message", logged.get(0).getThrowable(), is(nullValue()));
            assertThat(reason, logged.get(0).getMessage(configuration()).length(), lessThan(1024));
            assertThat("passed on to the end of the pipeline", reachedTheEndOfThePipeline(channel), is(nullValue()));
            assertThat("left open, to report every read that follows", channel.isOpen(), is(false));
            channel.finishAndReleaseAll();
        }
    }

    @Test
    public void shouldCloseTheConnectionForAnSslOrDecoderFaultWhateverTheLogLevel() {
        logLevel = Level.ERROR;
        EmbeddedChannel channel = new EmbeddedChannel(new Http2ConnectionExceptionHandler(mockServerLogger));

        channel.pipeline().fireExceptionCaught(new DecoderException("not a frame"));

        assertThat(logged, empty());
        assertThat("passed on to the end of the pipeline", reachedTheEndOfThePipeline(channel), is(nullValue()));
        assertThat("left open because the warning is not logged", channel.isOpen(), is(false));
        channel.finishAndReleaseAll();
    }

    /**
     * Netty's JDK TLS handler, unlike its OpenSSL one, neither closes a connection whose handshake is done when
     * bytes arrive that are not a TLS record, nor stops reading it: every read after that raises the fault again.
     */
    @Test
    public void shouldCloseATlsConnectionOnTheFirstBytesThatAreNotTls() throws Exception {
        SelfSignedCertificate certificate = new SelfSignedCertificate();
        try {
            SslContext serverContext = SslContextBuilder.forServer(certificate.certificate(), certificate.privateKey()).sslProvider(SslProvider.JDK).build();
            SslContext clientContext = SslContextBuilder.forClient().trustManager(InsecureTrustManagerFactory.INSTANCE).sslProvider(SslProvider.JDK).build();
            SslHandler serverTls = serverContext.newHandler(UnpooledByteBufAllocator.DEFAULT);
            EmbeddedChannel server = new EmbeddedChannel(
                serverTls,
                Http2RequestHeaderLimit.frameCodecBuilder(mockServerLogger).initialSettings(Http2RequestHeaderLimit.serverSettings(configuration())).build(),
                new Http2MultiplexHandler(new ChannelInboundHandlerAdapter()),
                new Http2ConnectionExceptionHandler(mockServerLogger)
            );
            EmbeddedChannel client = new EmbeddedChannel(clientContext.newHandler(UnpooledByteBufAllocator.DEFAULT));
            for (boolean moved = true; moved; ) {
                moved = transfer(client, server) | transfer(server, client);
            }
            assertThat("handshake done", serverTls.handshakeFuture().isSuccess(), is(true));

            for (int write = 0; write < 3 && server.isOpen(); write++) {
                server.writeInbound(Unpooled.wrappedBuffer(new byte[2000]));
            }

            assertThat(connectionEntries(), contains(both(startsWith("WARN closing HTTP/2 connection ")).and(endsWith(" for SSL or decoder fault io.netty.handler.codec.DecoderException:{}"))));
            assertThat(server.isOpen(), is(false));
            client.finishAndReleaseAll();
            server.finishAndReleaseAll();
        } finally {
            certificate.delete();
        }
    }

    private static boolean transfer(EmbeddedChannel from, EmbeddedChannel to) {
        boolean moved = false;
        for (ByteBuf bytes = from.readOutbound(); bytes != null; bytes = from.readOutbound()) {
            to.writeInbound(bytes);
            moved = true;
        }
        return moved;
    }

    @Test
    public void shouldLogAnythingElseOnceAsAnErrorWithItsCauseAndCloseTheConnection() {
        logLevel = Level.ERROR;
        IllegalStateException cause = new IllegalStateException("something unexpected");
        EmbeddedChannel channel = new EmbeddedChannel(new Http2ConnectionExceptionHandler(mockServerLogger));

        channel.pipeline().fireExceptionCaught(cause);
        channel.runPendingTasks();

        assertThat(logged, hasSize(1));
        assertThat(logged.get(0).getLogLevel(), is(Level.ERROR));
        assertThat(logged.get(0).getMessageFormat(), startsWith("closing HTTP/2 connection "));
        assertThat(logged.get(0).getMessageFormat(), endsWith(" for unexpected exception"));
        assertThat(logged.get(0).getThrowable(), is(sameInstance(cause)));
        assertThat("passed on to the end of the pipeline", reachedTheEndOfThePipeline(channel), is(nullValue()));
        assertThat("left open, to log it again each time it recurs", channel.isOpen(), is(false));
        channel.finishAndReleaseAll();
    }

    @Test
    public void shouldNameTheDirectMemoryLimitWhenItIsReached() throws Exception {
        logLevel = Level.ERROR;
        Constructor<OutOfDirectMemoryError> constructor = OutOfDirectMemoryError.class.getDeclaredConstructor(String.class);
        constructor.setAccessible(true);
        OutOfDirectMemoryError limitReached = constructor.newInstance("failed to allocate 4194304 byte(s) of direct memory");
        // found anywhere in the cause chain, an HTTP/2 connection error's included
        for (Throwable cause : new Throwable[]{limitReached, Http2Exception.connectionError(Http2Error.INTERNAL_ERROR, limitReached, "decode failed")}) {
            EmbeddedChannel channel = new EmbeddedChannel(new Http2ConnectionExceptionHandler(mockServerLogger));
            logged.clear();

            channel.pipeline().fireExceptionCaught(cause);

            assertThat(logged, hasSize(1));
            assertThat(logged.get(0).getLogLevel(), is(Level.ERROR));
            assertThat(logged.get(0).getMessageFormat(), containsString("direct memory limit (io.netty.maxDirectMemory) reached"));
            assertThat(logged.get(0).getMessageFormat(), containsString("closing connection"));
            assertThat("passed on to the end of the pipeline", reachedTheEndOfThePipeline(channel), is(nullValue()));
            assertThat("as its message says", channel.isOpen(), is(false));
            channel.finishAndReleaseAll();
        }
    }

    @Test
    public void shouldMarkAConnectionItClosesAsClosedByTheServerButNotOneItsClientReset() throws Exception {
        logLevel = Level.ERROR;
        Constructor<OutOfDirectMemoryError> constructor = OutOfDirectMemoryError.class.getDeclaredConstructor(String.class);
        constructor.setAccessible(true);
        EmbeddedChannel limitReached = new EmbeddedChannel(new Http2ConnectionExceptionHandler(mockServerLogger));
        EmbeddedChannel unexpected = new EmbeddedChannel(new Http2ConnectionExceptionHandler(mockServerLogger));
        EmbeddedChannel reset = new EmbeddedChannel(new Http2ConnectionExceptionHandler(mockServerLogger));

        limitReached.pipeline().fireExceptionCaught(constructor.newInstance("failed to allocate 4194304 byte(s) of direct memory"));
        unexpected.pipeline().fireExceptionCaught(new IllegalStateException("something unexpected"));
        unexpected.runPendingTasks();
        reset.pipeline().fireExceptionCaught(new IOException("Connection reset"));

        assertThat("closed at once", ServerClosedChannels.isClosedByServer(limitReached), is(true));
        assertThat("closed with GOAWAY", ServerClosedChannels.isClosedByServer(unexpected), is(true));
        assertThat("reset by its client", ServerClosedChannels.isClosedByServer(reset), is(false));
        limitReached.finishAndReleaseAll();
        unexpected.finishAndReleaseAll();
        reset.finishAndReleaseAll();
    }

    @Test
    public void shouldEndTheDirectHttp2PipelineAfterTheHandlerThatRoutesStreamErrors() {
        EmbeddedChannel channel = directHttp2Connection(configuration());

        List<Class<?>> handlers = channel.pipeline().toMap().values().stream().map(ChannelHandler::getClass).collect(Collectors.toList());

        assertThat(handlers.get(handlers.size() - 1), is((Object) Http2ConnectionExceptionHandler.class));
        assertThat(handlers.get(handlers.size() - 2), is((Object) Http2MultiplexHandler.class));
        channel.finishAndReleaseAll();
    }

    @Test
    public void shouldKeepAClientsResetFromTheEndOfTheDirectHttp2Pipeline() {
        EmbeddedChannel channel = directHttp2Connection(configuration());

        channel.pipeline().fireExceptionCaught(new IOException("Connection reset"));

        assertThat(reachedTheEndOfThePipeline(channel), is(nullValue()));
        assertThat(connectionEntries(), contains("DEBUG HTTP/2 connection from:{}closed by its client:{}"));
        assertThat("left for the transport to close", channel.isOpen(), is(true));
        channel.finishAndReleaseAll();
    }

    @Test
    public void shouldKeepAConnectionErrorFromTheEndOfTheDirectHttp2PipelineAndStillSendGoAway() throws Exception {
        EmbeddedChannel channel = directHttp2Connection(configuration());

        // a PING where the connection's first frame must be SETTINGS
        channel.pipeline().fireChannelRead(frames((writer, ctx) -> writer.writePing(ctx, false, 1L, ctx.newPromise())));
        channel.runPendingTasks();

        assertThat(reachedTheEndOfThePipeline(channel), is(nullValue()));
        assertThat(connectionEntries(), contains("WARN closing HTTP/2 connection from:{}for connection error:{}"));
        assertThat(logged.stream().filter(entry -> entry.getLogLevel() == Level.WARN).findFirst().orElseThrow(AssertionError::new).getThrowable(), instanceOf(Http2Exception.class));
        assertThat(sentFrameTypes(channel), contains(Http2FrameTypes.SETTINGS, Http2FrameTypes.GO_AWAY));
        assertThat(channel.isOpen(), is(false));
        channel.finishAndReleaseAll();
    }

    @Test
    public void shouldCloseTheDirectHttp2ConnectionWithGoAwayForAnythingElseAfterWhatWasAlreadyWritten() throws Exception {
        EmbeddedChannel channel = directHttp2Connection(configuration());
        // written and not yet flushed when the exception arrives
        channel.write(new DefaultHttp2PingFrame(42));

        channel.pipeline().fireExceptionCaught(new IllegalStateException("something unexpected"));
        channel.runPendingTasks();

        assertThat(reachedTheEndOfThePipeline(channel), is(nullValue()));
        assertThat(connectionEntries(), contains(both(startsWith("ERROR closing HTTP/2 connection ")).and(endsWith(" for unexpected exception"))));
        assertThat(sentFrames(channel), contains("SETTINGS", "PING", "GOAWAY INTERNAL_ERROR last stream 0"));
        assertThat(channel.isOpen(), is(false));
        channel.finishAndReleaseAll();
    }

    @Test
    public void shouldLogAHeaderBlockOverTheLimitOnceOnTheDirectHttp2Pipeline() throws Exception {
        EmbeddedChannel channel = directHttp2Connection(configuration().maxHeaderSize(LIMIT));
        Http2Headers overLimit = new DefaultHttp2Headers().method("GET").scheme("http").authority("localhost").path("/").add("x-filler", "a".repeat(4 * LIMIT));

        channel.pipeline().fireChannelRead(frames((writer, ctx) -> {
            writer.writeSettings(ctx, new Http2Settings(), ctx.newPromise());
            writer.writeHeaders(ctx, 3, overLimit, 0, true, ctx.newPromise());
        }));
        channel.runPendingTasks();

        assertThat(reachedTheEndOfThePipeline(channel), is(nullValue()));
        assertThat(connectionEntries(), contains("WARN closing HTTP/2 connection from:{}because a request's header block is more than a quarter larger than maxHeaderSize:{}"));
        assertThat(sentFrameTypes(channel), contains(Http2FrameTypes.SETTINGS, Http2FrameTypes.SETTINGS, Http2FrameTypes.GO_AWAY));
        assertThat(channel.isOpen(), is(false));
        channel.finishAndReleaseAll();
    }

    private static void assertHandledWithoutClosing(EmbeddedChannel channel) {
        assertThat("passed on to the end of the pipeline", reachedTheEndOfThePipeline(channel), is(nullValue()));
        assertThat("closed by the handler", channel.isOpen(), is(true));
        channel.finishAndReleaseAll();
    }

    /**
     * @return what an {@link EmbeddedChannel} recorded as reaching the end of its pipeline, where a socket's
     * pipeline logs it
     */
    private static Throwable reachedTheEndOfThePipeline(EmbeddedChannel channel) {
        try {
            channel.checkException();
            return null;
        } catch (Throwable throwable) {
            return throwable;
        }
    }

    /**
     * A cleartext HTTP/2 connection as MockServer sets one up: its whole pipeline, switched to HTTP/2 by the preface.
     */
    private EmbeddedChannel directHttp2Connection(Configuration configuration) {
        EmbeddedChannel channel = new EmbeddedChannel();
        HttpState httpState = new HttpState(configuration, mockServerLogger, mock(Scheduler.class));
        httpStates.add(httpState);
        channel.pipeline().addLast(new MockServerUnificationInitializer(
            configuration,
            mock(LifeCycle.class),
            httpState,
            mock(HttpActionHandler.class),
            null
        ));
        channel.writeInbound(Unpooled.wrappedBuffer(H2C_PREFACE.getBytes(StandardCharsets.US_ASCII)));
        return channel;
    }

    private List<String> connectionEntries() {
        return logged.stream()
            .filter(entry -> entry.getMessageFormat().contains("HTTP/2 connection"))
            .map(entry -> entry.getLogLevel() + " " + entry.getMessageFormat())
            .collect(Collectors.toList());
    }

    private interface FrameWriting {
        void write(Http2FrameWriter writer, ChannelHandlerContext ctx) throws Exception;
    }

    private static ByteBuf frames(FrameWriting writing) throws Exception {
        EmbeddedChannel client = new EmbeddedChannel(new ChannelOutboundHandlerAdapter());
        writing.write(new DefaultHttp2FrameWriter(), client.pipeline().firstContext());
        client.flush();
        return drainOutbound(client);
    }

    private static ByteBuf drainOutbound(EmbeddedChannel channel) {
        ByteBuf written = Unpooled.buffer();
        for (ByteBuf part = channel.readOutbound(); part != null; part = channel.readOutbound()) {
            written.writeBytes(part);
            part.release();
        }
        return written;
    }

    /**
     * @return each frame the server wrote, in order: its type, and for a {@code GOAWAY} its error code, last stream
     * and debug data
     */
    private static List<String> sentFrames(EmbeddedChannel channel) {
        ByteBuf written = drainOutbound(channel);
        try {
            List<String> frames = new ArrayList<>();
            String sent = ByteBufUtil.prettyHexDump(written);
            while (written.readableBytes() >= 9) {
                int length = written.readUnsignedMedium();
                byte type = written.readByte();
                written.skipBytes(5);
                assertThat("whole frames:\n" + sent, written.readableBytes(), greaterThanOrEqualTo(length));
                ByteBuf payload = written.readSlice(length);
                if (type == Http2FrameTypes.GO_AWAY) {
                    // after the last stream id and the error code
                    String debugData = payload.toString(8, length - 8, StandardCharsets.UTF_8);
                    frames.add(("GOAWAY " + Http2Error.valueOf(payload.getUnsignedInt(4)) + " last stream " + payload.getInt(0) + " " + debugData).trim());
                } else if (type == Http2FrameTypes.SETTINGS) {
                    frames.add("SETTINGS");
                } else if (type == Http2FrameTypes.PING) {
                    frames.add("PING");
                } else {
                    frames.add("type " + type);
                }
            }
            assertThat("whole frames:\n" + ByteBufUtil.prettyHexDump(written), written.isReadable(), is(false));
            return frames;
        } finally {
            written.release();
        }
    }

    /**
     * @return the type of each frame the server wrote, in order
     */
    private static List<Byte> sentFrameTypes(EmbeddedChannel channel) {
        ByteBuf written = drainOutbound(channel);
        try {
            List<Byte> types = new ArrayList<>();
            while (written.readableBytes() >= 9) {
                int length = written.readUnsignedMedium();
                types.add(written.readByte());
                written.skipBytes(5 + length);
            }
            assertThat("whole frames:\n" + ByteBufUtil.prettyHexDump(written), written.isReadable(), is(false));
            return types;
        } finally {
            written.release();
        }
    }
}
