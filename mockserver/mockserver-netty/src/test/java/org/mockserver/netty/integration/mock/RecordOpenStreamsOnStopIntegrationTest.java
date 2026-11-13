package org.mockserver.netty.integration.mock;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.Unpooled;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.codec.http.*;
import io.netty.handler.codec.http2.*;
import io.netty.util.ReferenceCountUtil;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;
import org.mockserver.client.MockServerClient;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.mock.Expectation;
import org.mockserver.model.HttpResponse;
import org.mockserver.netty.MockServer;
import org.mockserver.serialization.ExpectationSerializer;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import static org.mockserver.configuration.Configuration.configuration;
import static org.mockserver.model.HttpForward.forward;
import static org.mockserver.model.HttpRequest.request;
import static org.mockserver.stop.Stop.stopQuietly;

/**
 * A recorded streamed forward that is still open when MockServer stops is ended, recorded as complete with its chunk
 * timing, and written to the persisted recorded expectations file before the stop finishes, when
 * {@code recordOpenStreamsOnStop} is on (issue #2945).
 */
public class RecordOpenStreamsOnStopIntegrationTest {

    private static final String PATH = "/sse-never-ends";

    private static EventLoopGroup upstreamGroup;
    private static Channel upstreamChannel;
    private static int upstreamPort;

    @BeforeClass
    public static void startUpstream() throws Exception {
        upstreamGroup = new NioEventLoopGroup(1);
        upstreamChannel = new ServerBootstrap()
            .group(upstreamGroup)
            .channel(NioServerSocketChannel.class)
            .childHandler(new ChannelInitializer<SocketChannel>() {
                @Override
                protected void initChannel(SocketChannel ch) {
                    ch.pipeline().addLast(new HttpServerCodec());
                    ch.pipeline().addLast(new HttpObjectAggregator(65536));
                    ch.pipeline().addLast(new EndlessSseHandler());
                }
            })
            .bind(new InetSocketAddress("127.0.0.1", 0)).sync().channel();
        upstreamPort = ((InetSocketAddress) upstreamChannel.localAddress()).getPort();
    }

    @AfterClass
    public static void stopUpstream() {
        if (upstreamChannel != null) {
            upstreamChannel.close();
        }
        if (upstreamGroup != null) {
            upstreamGroup.shutdownGracefully(0, 0, TimeUnit.MILLISECONDS);
        }
    }

    /**
     * Sends a Server-Sent Event every 100 ms and never ends the stream, as a push stream the backend keeps open does.
     */
    @ChannelHandler.Sharable
    static class EndlessSseHandler extends SimpleChannelInboundHandler<FullHttpRequest> {
        @Override
        protected void channelRead0(ChannelHandlerContext ctx, FullHttpRequest request) {
            DefaultHttpResponse head = new DefaultHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK);
            head.headers().set(HttpHeaderNames.CONTENT_TYPE, "text/event-stream");
            HttpUtil.setTransferEncodingChunked(head, true);
            ctx.writeAndFlush(head);
            sendEvent(ctx, 0);
        }

        private void sendEvent(ChannelHandlerContext ctx, int index) {
            ctx.executor().schedule(() -> {
                if (ctx.channel().isActive()) {
                    ctx.writeAndFlush(new DefaultHttpContent(Unpooled.copiedBuffer("data: event" + index + "\n\n", StandardCharsets.UTF_8)))
                        .addListener(future -> {
                            if (future.isSuccess()) {
                                sendEvent(ctx, index + 1);
                            }
                        });
                }
            }, 100, TimeUnit.MILLISECONDS);
        }
    }

    private static final class StreamOpenAtStop {
        private final String persisted;
        private final String receivedByClient;
        private final long stopMillis;

        private StreamOpenAtStop(String persisted, String receivedByClient, long stopMillis) {
            this.persisted = persisted;
            this.receivedByClient = receivedByClient;
            this.stopMillis = stopMillis;
        }

        HttpResponse recordedResponse() {
            Expectation[] expectations = new ExpectationSerializer(new MockServerLogger()).deserializeArray(persisted, true);
            for (Expectation expectation : expectations) {
                if (expectation.getHttpRequest().toString().contains(PATH)) {
                    return expectation.getHttpResponse();
                }
            }
            return null;
        }
    }

    /**
     * Forwards a stream that never ends, reads three events of it, then stops MockServer with the stream still open
     * and the client still connected.
     */
    private static MockServer forwardingServer(File persistedFile, boolean recordOpenStreamsOnStop, long stopDrainMillis) {
        MockServer mockServer = new MockServer(configuration()
            .streamingResponsesEnabled(true)
            .persistRecordedExpectations(true)
            .persistedRecordedExpectationsPath(persistedFile.getAbsolutePath())
            .recordOpenStreamsOnStop(recordOpenStreamsOnStop)
            // an open stream holds the drain until this timeout
            .stopDrainMillis(stopDrainMillis));
        new MockServerClient("localhost", mockServer.getLocalPort())
            .when(request().withPath(PATH))
            .forward(forward().withHost("127.0.0.1").withPort(upstreamPort));
        return mockServer;
    }

    private static File persistedFile() throws Exception {
        File persistedFile = Files.createTempFile("recordOpenStreamsOnStop", ".json").toFile();
        persistedFile.deleteOnExit();
        return persistedFile;
    }

    private StreamOpenAtStop recordStreamOpenAtStop(boolean recordOpenStreamsOnStop, long stopDrainMillis, Consumer<MockServer> stop) throws Exception {
        File persistedFile = persistedFile();
        MockServer mockServer = forwardingServer(persistedFile, recordOpenStreamsOnStop, stopDrainMillis);
        int port = mockServer.getLocalPort();
        try {

            try (Socket socket = new Socket("localhost", port)) {
                socket.setSoTimeout(10_000);
                OutputStream output = socket.getOutputStream();
                output.write(("GET " + PATH + " HTTP/1.1\r\n" +
                    "Host: localhost\r\n" +
                    "Accept: text/event-stream\r\n\r\n").getBytes(StandardCharsets.UTF_8));
                output.flush();

                InputStream input = socket.getInputStream();
                ByteArrayOutputStream received = new ByteArrayOutputStream();
                byte[] buffer = new byte[4096];
                while (!received.toString(StandardCharsets.UTF_8).contains("data: event2")) {
                    int read = input.read(buffer);
                    if (read == -1) {
                        throw new AssertionError("the stream ended before MockServer stopped: " + received);
                    }
                    received.write(buffer, 0, read);
                }

                long stopStart = System.nanoTime();
                stop.accept(mockServer);
                long stopMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - stopStart);
                // read once the stop is over, so this shows what the stop itself wrote
                String persisted = new String(Files.readAllBytes(persistedFile.toPath()), StandardCharsets.UTF_8);

                try {
                    int read;
                    while ((read = input.read(buffer)) != -1) {
                        received.write(buffer, 0, read);
                    }
                } catch (SocketTimeoutException | java.net.SocketException ignored) {
                    // what arrived before the connection went is what the client saw
                }
                return new StreamOpenAtStop(persisted, received.toString(StandardCharsets.UTF_8), stopMillis);
            }
        } finally {
            stopQuietly(mockServer);
        }
    }

    private static void stopAndWait(MockServer mockServer) {
        mockServer.stop();
    }

    private static void stopWithStopRequestAndWaitForItToFinish(MockServer mockServer) {
        try (Socket socket = new Socket("localhost", mockServer.getLocalPort())) {
            socket.setSoTimeout(10_000);
            OutputStream output = socket.getOutputStream();
            output.write(("PUT /mockserver/stop HTTP/1.1\r\nHost: localhost\r\nContent-Length: 0\r\n\r\n").getBytes(StandardCharsets.UTF_8));
            output.flush();
            byte[] statusLine = new byte[12];
            int read = socket.getInputStream().read(statusLine);
            assertThat(new String(statusLine, 0, Math.max(read, 0), StandardCharsets.UTF_8), containsString("200"));
        } catch (Exception e) {
            throw new AssertionError("PUT /mockserver/stop failed", e);
        }
        // the event loops shut down only after the state, and its last write of the file, has stopped
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        while (mockServer.isRunning()) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("MockServer did not stop");
            }
            try {
                TimeUnit.MILLISECONDS.sleep(10);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    private static void assertRecordedCompleteWithChunkTiming(StreamOpenAtStop result) {
        assertRecordedCompleteWithChunkTiming(result, true);
    }

    private static void assertRecordedCompleteWithChunkTiming(StreamOpenAtStop result, boolean http1) {
        HttpResponse recorded = result.recordedResponse();
        assertThat("the open stream is in the persisted file: " + result.persisted, recorded, is(notNullValue()));
        assertThat(recorded.getFirstHeader("x-mockserver-streamed"), is("true"));
        assertThat("the stream is recorded as complete", recorded.containsHeader("x-mockserver-stream-truncated"), is(false));
        assertThat("the recording says MockServer's stop ended it", recorded.getFirstHeader("x-mockserver-stream-closed-by-mockserver-stop"), is("true"));
        String[] delays = recorded.getFirstHeader("x-mockserver-chunk-delays-ms").split(",");
        assertThat("the chunk timing is kept: " + String.join(",", delays), delays.length, greaterThanOrEqualTo(3));
        long longestGap = 0;
        for (String delay : delays) {
            longestGap = Math.max(longestGap, Long.parseLong(delay.trim()));
        }
        assertThat("the upstream sends an event about every 100 ms", longestGap, allOf(greaterThanOrEqualTo(50L), lessThan(5_000L)));
        assertThat(recorded.getBodyAsString(), allOf(containsString("data: event0"), containsString("data: event2")));
        if (http1) {
            assertThat("the client's response ends with the last chunk", result.receivedByClient, containsString("\r\n0\r\n\r\n"));
        }
    }

    @Test(timeout = 60_000)
    public void shouldRecordAStreamStillOpenWhenStoppedCompleteWithChunkTimingWhenRecordOpenStreamsOnStopIsOn() throws Exception {
        StreamOpenAtStop result = recordStreamOpenAtStop(true, 30_000L, RecordOpenStreamsOnStopIntegrationTest::stopAndWait);

        assertRecordedCompleteWithChunkTiming(result);
        assertThat("the ended stream no longer holds the 30 s drain open", result.stopMillis, lessThan(10_000L));
    }

    @Test(timeout = 60_000)
    public void shouldRecordAStreamStillOpenWhenStoppedByStopRequestWhenRecordOpenStreamsOnStopIsOn() throws Exception {
        StreamOpenAtStop result = recordStreamOpenAtStop(true, 30_000L, RecordOpenStreamsOnStopIntegrationTest::stopWithStopRequestAndWaitForItToFinish);

        assertRecordedCompleteWithChunkTiming(result);
    }

    @Test(timeout = 60_000)
    public void shouldRecordAnHttp2StreamStillOpenWhenStoppedCompleteWhenRecordOpenStreamsOnStopIsOn() throws Exception {
        File persistedFile = persistedFile();
        MockServer mockServer = forwardingServer(persistedFile, true, 30_000L);
        int port = mockServer.getLocalPort();
        EventLoopGroup group = new NioEventLoopGroup(1);
        try {
            CountDownLatch sawThirdEvent = new CountDownLatch(1);
            CountDownLatch streamEnded = new CountDownLatch(1);
            StringBuffer received = new StringBuffer();
            Http2ReadingHandler reader = new Http2ReadingHandler(received, sawThirdEvent, streamEnded);
            // prior-knowledge cleartext HTTP/2: the client sends the connection preface, then one request stream
            Channel channel = new io.netty.bootstrap.Bootstrap()
                .group(group)
                .channel(NioSocketChannel.class)
                .handler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel ch) {
                        ch.pipeline().addLast(Http2FrameCodecBuilder.forClient().build());
                        ch.pipeline().addLast(reader);
                    }
                })
                .connect("localhost", port)
                .sync()
                .channel();
            Http2FrameStream stream = reader.openStream();
            Http2Headers headers = new DefaultHttp2Headers().method("GET").scheme("http").authority("localhost:" + port).path(PATH);
            channel.writeAndFlush(new DefaultHttp2HeadersFrame(headers, true).stream(stream)).sync();
            assertThat("the client reads three events: " + received, sawThirdEvent.await(10, TimeUnit.SECONDS), is(true));

            long stopStart = System.nanoTime();
            mockServer.stop();
            long stopMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - stopStart);
            String persisted = new String(Files.readAllBytes(persistedFile.toPath()), StandardCharsets.UTF_8);

            assertThat("the client's stream ended normally, with END_STREAM", streamEnded.await(5, TimeUnit.SECONDS), is(true));
            assertRecordedCompleteWithChunkTiming(new StreamOpenAtStop(persisted, received.toString(), stopMillis), false);
            assertThat("the ended stream no longer holds the 30 s drain open", stopMillis, lessThan(10_000L));
        } finally {
            group.shutdownGracefully(0, 0, TimeUnit.MILLISECONDS);
            stopQuietly(mockServer);
        }
    }

    @Test(timeout = 60_000)
    public void shouldNotRecordAStreamStillOpenWhenStoppedWhenRecordOpenStreamsOnStopIsOff() throws Exception {
        StreamOpenAtStop result = recordStreamOpenAtStop(false, 1_000L, RecordOpenStreamsOnStopIntegrationTest::stopAndWait);

        assertThat("the open stream is lost, as before", result.recordedResponse(), is(nullValue()));
        assertThat(result.receivedByClient, not(containsString("\r\n0\r\n\r\n")));
    }

    /**
     * A client's handler that opens an HTTP/2 stream, collects its DATA and notes when it ends normally.
     */
    private static final class Http2ReadingHandler extends Http2ChannelDuplexHandler {
        private final StringBuffer received;
        private final CountDownLatch sawThirdEvent;
        private final CountDownLatch streamEnded;

        private Http2ReadingHandler(StringBuffer received, CountDownLatch sawThirdEvent, CountDownLatch streamEnded) {
            this.received = received;
            this.sawThirdEvent = sawThirdEvent;
            this.streamEnded = streamEnded;
        }

        private Http2FrameStream openStream() {
            return newStream();
        }

        @Override
        public void channelRead(ChannelHandlerContext ctx, Object msg) {
            if (msg instanceof Http2DataFrame) {
                received.append(((Http2DataFrame) msg).content().toString(StandardCharsets.UTF_8));
                if (received.indexOf("data: event2") >= 0) {
                    sawThirdEvent.countDown();
                }
            }
            if ((msg instanceof Http2DataFrame && ((Http2DataFrame) msg).isEndStream())
                || (msg instanceof Http2HeadersFrame && ((Http2HeadersFrame) msg).isEndStream())) {
                streamEnded.countDown();
            }
            ReferenceCountUtil.release(msg);
        }
    }
}
