package org.mockserver.netty.unification;

import com.sun.management.ThreadMXBean;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import io.netty.buffer.UnpooledByteBufAllocator;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.DecoderException;
import io.netty.handler.codec.http2.AbstractHttp2ConnectionHandlerBuilder;
import io.netty.handler.codec.http2.DecoratingHttp2ConnectionEncoder;
import io.netty.handler.codec.http2.DefaultHttp2Connection;
import io.netty.handler.codec.http2.DefaultHttp2Headers;
import io.netty.handler.codec.http2.Http2Connection;
import io.netty.handler.codec.http2.Http2ConnectionHandler;
import io.netty.handler.codec.http2.Http2ConnectionHandlerBuilder;
import io.netty.handler.codec.http2.Http2Error;
import io.netty.handler.codec.http2.Http2Exception;
import io.netty.handler.codec.http2.Http2FrameAdapter;
import io.netty.handler.codec.http2.Http2FrameCodec;
import io.netty.handler.codec.http2.Http2FrameCodecBuilder;
import io.netty.handler.codec.http2.Http2FrameListener;
import io.netty.handler.codec.http2.Http2Headers;
import io.netty.handler.codec.http2.Http2Settings;
import io.netty.handler.codec.http2.HttpToHttp2ConnectionHandler;
import io.netty.handler.codec.http2.HttpToHttp2ConnectionHandlerBuilder;
import org.junit.Test;
import org.mockserver.configuration.Configuration;
import org.mockserver.log.model.LogEntry;
import org.mockserver.logging.MockServerLogger;
import org.slf4j.event.Level;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.lessThan;
import static org.mockserver.configuration.Configuration.configuration;

/**
 * How the HTTP/2 server codecs that log a refused header list are built, and what they allocate for a header
 * block that is small on the wire and far over
 * {@code maxHeaderSize} once decoded: one 4 KiB field, sent once and then referred to 12,000 times from HPACK's dynamic
 * table at a byte a time, about 15 KB on the wire and 48 MB decoded. Netty counts each field against the limit as it
 * decodes it and stops keeping them once the limit is passed, so the request is refused for less than the limit itself.
 * <p>
 * The codecs run in an {@link EmbeddedChannel} on buffers without leak tracking: the test JVM's tracking records a
 * stack trace for every byte read, which would be all that this measured.
 */
public class Http2RequestHeaderLimitTest {

    private static final int REFERENCES = 12_000;
    private static final String FIELD_NAME = "x-filler";
    private static final String FIELD_VALUE = "a".repeat(4000);
    private static final long DECODED_BYTES = (long) REFERENCES * (FIELD_NAME.length() + FIELD_VALUE.length() + 32);

    private final Configuration configuration = configuration();
    private final MockServerLogger mockServerLogger = new MockServerLogger(Http2RequestHeaderLimitTest.class);

    @Test
    public void shouldRefuseAFarOverLimitHeaderBlockOnADirectConnectionWithoutHoldingIt() {
        assertRefusedWithinTheAllocationBound(() -> Http2RequestHeaderLimit.frameCodecBuilder(mockServerLogger)
            .initialSettings(Http2RequestHeaderLimit.serverSettings(configuration))
            .build());
    }

    @Test
    public void shouldRefuseAFarOverLimitHeaderBlockInATunnelWithoutHoldingIt() {
        assertRefusedWithinTheAllocationBound(() -> Http2RequestHeaderLimit.tunnelServerHandler(configuration, mockServerLogger, new DefaultHttp2Connection(true), new Http2FrameAdapter(), null, false));
    }

    /**
     * The codecs are built by builders of MockServer's own, to log what they refuse. Everything else about them
     * must stay as Netty's own server builders make it: {@code Http2FrameCodecBuilder.forServer()} sets more than
     * its public constructor does, and a Netty upgrade may add a default. So every field of the builders, the
     * decoder and encoder chains they build and the built handlers' own settings are compared with Netty's. The one
     * difference is deliberate: the direct codec leaves its SETTINGS for the end of the read that adds it to flush.
     */
    @Test
    public void shouldBuildTheDirectCodecAsNettysServerBuilderDoes() {
        Http2FrameCodecBuilder nettys = Http2FrameCodecBuilder.forServer().flushPreface(false);
        Http2FrameCodecBuilder mockServers = Http2RequestHeaderLimit.frameCodecBuilder(mockServerLogger);

        assertThat(fields(mockServers, AbstractHttp2ConnectionHandlerBuilder.class), is(fields(nettys, AbstractHttp2ConnectionHandlerBuilder.class)));
        assertThat(fields(mockServers, Http2FrameCodecBuilder.class), is(fields(nettys, Http2FrameCodecBuilder.class)));
        assertBuiltAlike(mockServers.build(), nettys.build(), Http2FrameCodec.class);
    }

    @Test
    public void shouldBuildTheTunnelHandlerAsNettysBuilderDoes() {
        Http2Connection connection = new DefaultHttp2Connection(true);
        Http2FrameListener frameListener = new Http2FrameAdapter();
        HttpToHttp2ConnectionHandlerBuilder nettys = new HttpToHttp2ConnectionHandlerBuilder()
            .initialSettings(Http2RequestHeaderLimit.serverSettings(configuration))
            .connection(connection)
            .frameListener(frameListener);
        Http2RequestHeaderLimit.TunnelServerHandlerBuilder mockServers = Http2RequestHeaderLimit.tunnelServerHandlerBuilder(configuration, mockServerLogger, connection, frameListener, null);

        assertThat(fields(mockServers, AbstractHttp2ConnectionHandlerBuilder.class), is(fields(nettys, AbstractHttp2ConnectionHandlerBuilder.class)));
        // added as a handshake completes, it flushes its SETTINGS as Netty's does; added while reading, at the read's end
        for (boolean addedWhileReading : new boolean[]{false, true}) {
            // each builder builds on a connection of its own: one connection takes one flow controller pair
            HttpToHttp2ConnectionHandler nettysHandler = new HttpToHttp2ConnectionHandlerBuilder()
                .initialSettings(Http2RequestHeaderLimit.serverSettings(configuration))
                .connection(new DefaultHttp2Connection(true))
                .frameListener(frameListener)
                .flushPreface(!addedWhileReading)
                .build();
            HttpToHttp2ConnectionHandler mockServersHandler = Http2RequestHeaderLimit.tunnelServerHandler(configuration, mockServerLogger, new DefaultHttp2Connection(true), frameListener, null, addedWhileReading);
            assertBuiltAlike(mockServersHandler, nettysHandler, HttpToHttp2ConnectionHandler.class);
        }
    }

    /**
     * The loopback's handler is built by a builder of MockServer's own too, to send no {@code x-http2-} extension
     * header; everything else about it must stay as Netty's {@code HttpToHttp2ConnectionHandlerBuilder} makes it.
     */
    @Test
    public void shouldBuildTheLoopbackHandlerAsNettysBuilderDoes() {
        Http2Connection connection = new DefaultHttp2Connection(false);
        Http2FrameListener frameListener = new Http2FrameAdapter();
        HttpToHttp2ConnectionHandlerBuilder nettys = new HttpToHttp2ConnectionHandlerBuilder()
            .initialSettings(Http2RequestHeaderLimit.relayLoopbackSettings())
            .connection(connection)
            .frameListener(frameListener)
            .flushPreface(true);
        Http2RequestHeaderLimit.RelayLoopbackHandlerBuilder mockServers = Http2RequestHeaderLimit.relayLoopbackHandlerBuilder(mockServerLogger, new EmbeddedChannel(), connection, frameListener, null);

        assertThat(fields(mockServers, AbstractHttp2ConnectionHandlerBuilder.class), is(fields(nettys, AbstractHttp2ConnectionHandlerBuilder.class)));
        HttpToHttp2ConnectionHandler nettysHandler = new HttpToHttp2ConnectionHandlerBuilder()
            .initialSettings(Http2RequestHeaderLimit.relayLoopbackSettings())
            .connection(new DefaultHttp2Connection(false))
            .frameListener(frameListener)
            .flushPreface(true)
            .build();
        assertBuiltAlike(Http2RequestHeaderLimit.relayLoopbackHandler(mockServerLogger, new EmbeddedChannel(), new DefaultHttp2Connection(false), frameListener, null), nettysHandler, HttpToHttp2ConnectionHandler.class);
    }

    @Test
    public void shouldLogOnlyARefusalForTheSizeOfARequestsHeaders() throws Exception {
        Http2Settings settings = Http2RequestHeaderLimit.serverSettings(configuration);
        ChannelHandlerContext ctx = new EmbeddedChannel(new ChannelInboundHandlerAdapter()).pipeline().firstContext();
        String nettysMessage = "Header size exceeded max allowed size (%d)";

        assertThat(logged(ctx, settings, false, Http2Exception.headerListSizeError(3, Http2Error.PROTOCOL_ERROR, true, nettysMessage, 10)), contains(containsString("because its header list is larger than maxHeaderSize")));
        assertThat(logged(ctx, settings, false, Http2Exception.connectionError(Http2Error.PROTOCOL_ERROR, nettysMessage, 10)), contains(containsString("because a request's header block is more than a quarter larger than maxHeaderSize")));

        assertThat("a response's header list, refused as it is written", logged(ctx, settings, true, Http2Exception.headerListSizeError(3, Http2Error.PROTOCOL_ERROR, false, nettysMessage, 10)), empty());
        assertThat("an encoding error raised while reading", logged(ctx, settings, false, Http2Exception.headerListSizeError(3, Http2Error.PROTOCOL_ERROR, false, nettysMessage, 10)), empty());
        assertThat("an outbound connection error", logged(ctx, settings, true, Http2Exception.connectionError(Http2Error.PROTOCOL_ERROR, nettysMessage, 10)), empty());
        assertThat("another protocol error", logged(ctx, settings, false, Http2Exception.connectionError(Http2Error.PROTOCOL_ERROR, "Received frame of type %d while processing headers on stream %d.", 0, 3)), empty());
        assertThat("another error code", logged(ctx, settings, false, Http2Exception.connectionError(Http2Error.FRAME_SIZE_ERROR, nettysMessage, 10)), empty());
        assertThat("another stream error", logged(ctx, settings, false, Http2Exception.streamError(3, Http2Error.PROTOCOL_ERROR, nettysMessage, 10)), empty());
        assertThat("not an HTTP/2 error", logged(ctx, settings, false, new IOException("Header size exceeded max allowed size")), empty());
        ctx.channel().close();
    }

    /**
     * Netty answers a refused request in the method the logging is added to, so a logger that throws must not
     * leave the request unanswered.
     */
    @Test
    public void shouldStillRefuseARequestWhenLoggingTheRefusalThrows() {
        MockServerLogger throwing = new MockServerLogger(Http2RequestHeaderLimitTest.class) {
            @Override
            public boolean isEnabledForInstance(Level level) {
                return true;
            }

            @Override
            public void logEvent(LogEntry logEntry) {
                throw new IllegalStateException("logging failed");
            }
        };

        refuse(Http2RequestHeaderLimit.frameCodecBuilder(throwing).initialSettings(Http2RequestHeaderLimit.serverSettings(configuration)).build(), true);
        refuse(Http2RequestHeaderLimit.tunnelServerHandler(configuration, throwing, new DefaultHttp2Connection(true), new Http2FrameAdapter(), null, false), true);
    }

    private static List<String> logged(ChannelHandlerContext ctx, Http2Settings settings, boolean outbound, Throwable cause) {
        List<String> messages = new ArrayList<>();
        MockServerLogger recording = new MockServerLogger(Http2RequestHeaderLimitTest.class) {
            @Override
            public boolean isEnabledForInstance(Level level) {
                return true;
            }

            @Override
            public void logEvent(LogEntry logEntry) {
                messages.add(logEntry.getMessageFormat());
            }
        };
        Http2RequestHeaderLimit.logRefusal(recording, ctx, settings, outbound, cause);
        return messages;
    }

    /**
     * Compares two built handlers: their own settings, and each decoder and encoder in the chains wrapped around
     * Netty's default ones, class by class with the limits each holds. A tunnel's handlers ({@code HttpToHttp2ConnectionHandler})
     * differ in one deliberate way: their encoder is wrapped in {@link ExtensionHeaderStrippingHttp2ConnectionEncoder}.
     */
    private static void assertBuiltAlike(Http2ConnectionHandler mockServers, Http2ConnectionHandler nettys, Class<?> handlerType) {
        boolean tunnelHandler = handlerType == HttpToHttp2ConnectionHandler.class;
        try {
            for (Class<?> type = handlerType; type != null && type.getName().startsWith("io.netty.handler.codec.http2."); type = type.getSuperclass()) {
                Map<String, String> expected = fields(nettys, type);
                if (tunnelHandler && type == Http2ConnectionHandler.class) {
                    expected.put("encoder", DecoratingHttp2ConnectionEncoder.class.getName());
                }
                assertThat(type.getSimpleName(), fields(mockServers, type), is(expected));
            }
            assertThat("decoder chain", chain(mockServers.decoder()), is(chain(nettys.decoder())));
            List<String> encoderChain = chain(mockServers.encoder());
            if (tunnelHandler) {
                assertThat("the encoder strips extension headers", mockServers.encoder().getClass(), is(ExtensionHeaderStrippingHttp2ConnectionEncoder.class));
                encoderChain.remove(0);
            }
            assertThat("encoder chain", encoderChain, is(chain(nettys.encoder())));
            assertThat("frame writer chain", chain(mockServers.encoder().frameWriter()), is(chain(nettys.encoder().frameWriter())));
        } finally {
            new EmbeddedChannel(mockServers).finishAndReleaseAll();
            new EmbeddedChannel(nettys).finishAndReleaseAll();
        }
    }

    /**
     * Each decorator's class and fields, then those of what it wraps, down to Netty's default implementation.
     */
    private static List<String> chain(Object outermost) {
        List<String> chain = new ArrayList<>();
        for (Object link = outermost; link != null; ) {
            Object wrapped = null;
            StringBuilder description = new StringBuilder(link.getClass().getName());
            for (Class<?> type = link.getClass(); type != null && type != Object.class; type = type.getSuperclass()) {
                description.append(' ').append(fields(link, type));
                for (Field field : type.getDeclaredFields()) {
                    if (field.getName().equals("delegate")) {
                        wrapped = value(field, link);
                    }
                }
            }
            chain.add(description.toString());
            link = wrapped;
        }
        return chain;
    }

    /**
     * The instance fields one class declares: numbers, flags, text and settings by value, anything else by the
     * Netty class it is an instance of (so that MockServer's subclass of a Netty handler compares as that handler).
     */
    private static Map<String, String> fields(Object instance, Class<?> declaredIn) {
        Map<String, String> fields = new TreeMap<>();
        for (Field field : declaredIn.getDeclaredFields()) {
            // a field named for nanoseconds is a clock reading taken when the object was made
            if (!Modifier.isStatic(field.getModifiers()) && !field.getName().contains("Nano")) {
                Object value = value(field, instance);
                if (value == null || value instanceof Number || value instanceof Boolean || value instanceof CharSequence || value instanceof Enum || value instanceof Http2Settings) {
                    fields.put(field.getName(), String.valueOf(value));
                } else {
                    Class<?> type = value.getClass();
                    while (!type.getName().startsWith("io.netty.") && type.getSuperclass() != null) {
                        type = type.getSuperclass();
                    }
                    fields.put(field.getName(), type.getName());
                }
            }
        }
        return fields;
    }

    private static Object value(Field field, Object instance) {
        try {
            field.setAccessible(true);
            return field.get(instance);
        } catch (IllegalAccessException e) {
            throw new AssertionError(e);
        }
    }

    private void assertRefusedWithinTheAllocationBound(Supplier<ChannelHandler> serverCodec) {
        assertThat("far over the limit decoded", DECODED_BYTES, greaterThan(100L * configuration.maxHeaderSize()));
        // a first connection, so that loading the classes involved is not measured
        refuse(serverCodec.get(), false);

        long allocated = refuse(serverCodec.get(), false);

        // less than the limit itself: the fields kept up to the limit all share the one name and value
        assertThat("allocated " + allocated + " bytes for " + DECODED_BYTES + " decoded", allocated, lessThan((long) configuration.maxHeaderSize()));
    }

    /**
     * @param loggerThrows whether the codec's logger throws, which the channel then reports once the request is refused
     * @return the bytes allocated by the server codec while it read and refused the header block
     */
    private static long refuse(ChannelHandler serverCodec, boolean loggerThrows) {
        AtomicReference<String> status = new AtomicReference<>();
        AtomicLong resetErrorCode = new AtomicLong(-1);
        Http2ConnectionHandler clientCodec = new Http2ConnectionHandlerBuilder()
            .server(false)
            // or the client would refuse to send what the server limits
            .encoderIgnoreMaxHeaderListSize(true)
            .frameListener(new Http2FrameAdapter() {
                @Override
                public void onHeadersRead(ChannelHandlerContext ctx, int streamId, Http2Headers headers, int streamDependency, short weight, boolean exclusive, int padding, boolean endStream) {
                    status.set(headers.status().toString());
                }

                @Override
                public void onRstStreamRead(ChannelHandlerContext ctx, int streamId, long errorCode) {
                    resetErrorCode.set(errorCode);
                }
            })
            .build();
        EmbeddedChannel client = new EmbeddedChannel(clientCodec);
        EmbeddedChannel server = new EmbeddedChannel();
        server.config().setAllocator(new UnpooledByteBufAllocator(false, true));
        server.pipeline().addLast(serverCodec);
        try {
            server.writeInbound(withoutLeakTracking(written(client)));

            Http2Headers headers = new DefaultHttp2Headers().method("POST").scheme("http").authority("localhost").path("/limit");
            for (int i = 0; i < REFERENCES; i++) {
                headers.add(FIELD_NAME, FIELD_VALUE);
            }
            ChannelHandlerContext clientCtx = client.pipeline().context(clientCodec);
            clientCodec.encoder().writeHeaders(clientCtx, 3, headers, 0, false, clientCtx.newPromise());
            client.flush();
            byte[] headerBlockFrames = written(client);
            assertThat("small on the wire, and one frame", headerBlockFrames.length, lessThan(16 * 1024));

            ThreadMXBean threads = (ThreadMXBean) ManagementFactory.getThreadMXBean();
            long thread = Thread.currentThread().getId();
            long allocatedBefore = threads.getThreadAllocatedBytes(thread);
            try {
                server.writeInbound(withoutLeakTracking(headerBlockFrames));
                assertThat("the logger's failure is reported", loggerThrows, is(false));
            } catch (DecoderException loggingFailed) {
                assertThat(loggerThrows, is(true));
                assertThat(loggingFailed.getCause().getMessage(), is("logging failed"));
            }
            long allocated = threads.getThreadAllocatedBytes(thread) - allocatedBefore;

            client.writeInbound(Unpooled.wrappedBuffer(written(server)));
            assertThat(status.get(), is("431"));
            assertThat(resetErrorCode.get(), is(Http2Error.PROTOCOL_ERROR.code()));
            assertThat("the connection stays open", server.isOpen(), is(true));
            return allocated;
        } finally {
            client.finishAndReleaseAll();
            server.finishAndReleaseAll();
        }
    }

    private static byte[] written(EmbeddedChannel channel) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        for (ByteBuf buffer = channel.readOutbound(); buffer != null; buffer = channel.readOutbound()) {
            bytes.writeBytes(ByteBufUtil.getBytes(buffer));
            buffer.release();
        }
        return bytes.toByteArray();
    }

    private static ByteBuf withoutLeakTracking(byte[] bytes) {
        return Unpooled.wrappedBuffer(bytes);
    }
}
