package org.mockserver.netty.unification;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.channel.local.LocalAddress;
import io.netty.handler.codec.http.HttpServerCodec;
import io.netty.handler.codec.http2.Http2FrameCodec;
import io.netty.handler.ssl.SslHandler;
import org.junit.After;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;
import org.mockserver.configuration.Configuration;
import org.mockserver.httpclient.NettyHttpClient;
import org.mockserver.lifecycle.LifeCycle;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.mock.HttpState;
import org.mockserver.mock.action.http.HttpActionHandler;
import org.mockserver.netty.MockServerUnificationInitializer;
import org.mockserver.netty.proxy.relay.RelayLoopbackStandIn;
import org.mockserver.netty.proxy.relay.RelayUpstreamStandIn;
import org.mockserver.netty.proxy.BinaryRequestProxyingHandler;
import org.mockserver.scheduler.Scheduler;

import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;
import static org.hamcrest.Matchers.startsWith;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockserver.configuration.Configuration.configuration;
import static org.mockserver.netty.HttpRequestHandler.PROXYING;
import static org.mockserver.mock.action.http.HttpActionHandler.REMOTE_SOCKET;

/**
 * What a connection is taken to be from its first bytes, however those bytes are split across reads, and that a
 * connection taken to be binary stays binary.
 */
public class PortUnificationHandlerDetectionTest {

    private static final byte[] TLS_RECORD_HEADER = {22, 3, 1, 0, 5};
    private static final byte[] SOCKS4_BIND = {4, 2, 0, 80, 127, 0, 0, 1, 'u', 0};
    private static final byte[] SOCKS5_GREETING = {5, 2, 0, 2};
    private static final byte[] HTTP_GET = ascii("GET /some/path HTTP/1.1\r\nHost: example.com\r\n");
    private static final byte[] HTTP_CONNECT = ascii("CONNECT example.com:443 HTTP/1.1\r\nHost: example.com:443\r\n");
    private static final byte[] H2C_PREFACE = ascii("PRI * HTTP/2.0\r\n\r\nSM\r\n\r\n");
    // how a TLS handshake opens: a handshake record of 100 bytes whose first message is a ClientHello. Only its
    // start is ever sent, so the TLS handler that takes it over waits for the rest and the connection stays open
    private static final byte[] CLIENT_HELLO_START = {22, 3, 1, 0, 100, 1, 0, 0, 96, 3, 3};

    // set on the instance, so that a test changing the shared defaults in parallel cannot change what is detected
    private final Configuration configuration = configuration().http2Enabled(true).assumeAllRequestsAreHttp(false);
    private final NettyHttpClient httpClient = mock(NettyHttpClient.class);
    // what is binary is relayed to stand-ins
    private final RelayUpstreamStandIn upstreams = new RelayUpstreamStandIn(httpClient);
    private final List<String> forwardedAsBinary = new ArrayList<>();
    private final List<EmbeddedChannel> channels = new ArrayList<>();
    private final List<RelayLoopbackStandIn> relayLoopbacks = new ArrayList<>();
    // one for the class: each HttpState starts threads of its own, and one of them is not ended by stop()
    private static HttpState httpState;
    // one for each test, made with its first connection, so with the configuration the test has set by then
    private MockServerUnificationInitializer initializer;

    @BeforeClass
    public static void startServerState() {
        httpState = new HttpState(configuration(), new MockServerLogger(), mock(Scheduler.class));
    }

    @AfterClass
    public static void stopServerState() {
        httpState.stop();
        httpState = null;
    }

    private static byte[] ascii(String text) {
        return text.getBytes(StandardCharsets.US_ASCII);
    }

    /** Each write the relay has made to an upstream connection so far, in order, as hex. */
    private List<String> forwardedAsBinary() {
        for (byte[] write : upstreams.writtenInTheClear()) {
            forwardedAsBinary.add(ByteBufUtil.hexDump(write));
        }
        return forwardedAsBinary;
    }

    @After
    public void releaseChannels() {
        channels.forEach(EmbeddedChannel::finishAndReleaseAll);
        relayLoopbacks.forEach(RelayLoopbackStandIn::close);
        upstreams.releaseAll();
        if (initializer != null) {
            initializer.getMcpSessionManager().shutdown();
        }
    }

    /** A connection to a MockServer that forwards what is not HTTP to an upstream. */
    private EmbeddedChannel connection() {
        return connectionBetween(null, null);
    }

    /** MockServer's end of the loopback connection one of its own CONNECT/SOCKS relays opened. */
    private EmbeddedChannel relayLoopbackConnection() {
        RelayLoopbackStandIn relayLoopback = new RelayLoopbackStandIn();
        relayLoopbacks.add(relayLoopback);
        return connectionFrom(relayLoopback);
    }

    private EmbeddedChannel connectionFrom(RelayLoopbackStandIn relayLoopback) {
        return connectionBetween(relayLoopback.address(), relayLoopback.listenerAddress());
    }

    private EmbeddedChannel connectionBetween(SocketAddress remoteAddress, SocketAddress localAddress) {
        if (initializer == null) {
            HttpActionHandler actionHandler = mock(HttpActionHandler.class);
            when(actionHandler.getHttpClient()).thenReturn(httpClient);
            initializer = new MockServerUnificationInitializer(configuration, mock(LifeCycle.class), httpState, actionHandler, null);
        }
        EmbeddedChannel channel = remoteAddress == null ? new EmbeddedChannel() : new EmbeddedChannel() {
            @Override
            protected SocketAddress localAddress0() {
                return localAddress;
            }

            @Override
            protected SocketAddress remoteAddress0() {
                return remoteAddress;
            }
        };
        channel.attr(REMOTE_SOCKET).set(new InetSocketAddress("127.0.0.1", 1234));
        channel.pipeline().addLast(initializer);
        channels.add(channel);
        return channel;
    }

    private static void clientSends(EmbeddedChannel channel, byte... bytes) {
        channel.writeInbound(Unpooled.copiedBuffer(bytes));
    }

    private static String hex(String text) {
        return ByteBufUtil.hexDump(ascii(text));
    }

    private static String replyTo(EmbeddedChannel channel) {
        ByteBuf reply = channel.readOutbound();
        if (reply == null) {
            return null;
        }
        try {
            return ByteBufUtil.hexDump(reply);
        } finally {
            reply.release();
        }
    }

    /** What the connection has been taken to be, or "undecided" while its bytes are still being held. */
    private String takenToBe(EmbeddedChannel channel) {
        String reply = replyTo(channel);
        if (reply != null && reply.startsWith("005b")) {
            return "SOCKS4";
        } else if (reply != null && reply.startsWith("05")) {
            return "SOCKS5";
        } else if (PortUnificationHandler.isSslEnabledUpstream(channel)) {
            // not the TLS handler's presence: without certificates it fails, and closes, on a record that is no handshake
            return "TLS";
        } else if (channel.pipeline().get(Http2FrameCodec.class) != null) {
            return "HTTP/2";
        } else if (channel.pipeline().get(HttpServerCodec.class) != null) {
            return "HTTP/1.1";
        } else if (channel.pipeline().get(BinaryRequestProxyingHandler.class) != null) {
            return "binary";
        }
        return "undecided";
    }

    private void assertTakenToBeHoweverItsFirstBytesAreSplit(String protocol, byte[] firstBytes) {
        EmbeddedChannel whole = connection();
        clientSends(whole, firstBytes);
        assertThat("sent in one read", takenToBe(whole), is(protocol));

        for (int split = 1; split < firstBytes.length; split++) {
            forwardedAsBinary().clear();
            EmbeddedChannel channel = connection();
            clientSends(channel, Arrays.copyOfRange(firstBytes, 0, split));
            clientSends(channel, Arrays.copyOfRange(firstBytes, split, firstBytes.length));
            assertThat("split after " + split + " byte(s)", takenToBe(channel), is(protocol));
            assertThat("split after " + split + " byte(s), nothing is forwarded as binary", forwardedAsBinary(), is(empty()));
        }

        forwardedAsBinary().clear();
        EmbeddedChannel byteAtATime = connection();
        for (byte b : firstBytes) {
            clientSends(byteAtATime, b);
        }
        assertThat("sent a byte at a time", takenToBe(byteAtATime), is(protocol));
        assertThat("sent a byte at a time, nothing is forwarded as binary", forwardedAsBinary(), is(empty()));
    }

    @Test
    public void shouldDetectHttpHoweverItsFirstBytesAreSplit() {
        assertTakenToBeHoweverItsFirstBytesAreSplit("HTTP/1.1", HTTP_GET);
    }

    @Test
    public void shouldDetectConnectHoweverItsFirstBytesAreSplit() {
        assertTakenToBeHoweverItsFirstBytesAreSplit("HTTP/1.1", HTTP_CONNECT);
    }

    @Test
    public void shouldDetectEveryHttpMethodFromItsFirstBytes() {
        for (String method : Arrays.asList("GET", "POST", "PUT", "HEAD", "OPTIONS", "PATCH", "DELETE", "TRACE", "CONNECT")) {
            EmbeddedChannel channel = connection();
            clientSends(channel, ascii(method + " /"));
            assertThat(method, takenToBe(channel), is("HTTP/1.1"));
        }
    }

    @Test
    public void shouldNotTakeALongerWordThatStartsWithAMethodToBeHttp() {
        for (String method : Arrays.asList("GET", "POST", "PUT", "HEAD", "OPTIONS", "PATCH", "DELETE", "TRACE", "CONNECT")) {
            forwardedAsBinary().clear();
            EmbeddedChannel channel = connection();

            clientSends(channel, ascii(method + "X /"));

            assertThat(method + "X", takenToBe(channel), is("binary"));
            assertThat(method + "X is forwarded at once", forwardedAsBinary(), contains(hex(method + "X /")));
        }
    }

    @Test
    public void shouldDetectHttp2PriorKnowledgeHoweverItsFirstBytesAreSplit() {
        assertTakenToBeHoweverItsFirstBytesAreSplit("HTTP/2", H2C_PREFACE);
    }

    @Test
    public void shouldDetectTlsHoweverItsFirstBytesAreSplit() {
        assertTakenToBeHoweverItsFirstBytesAreSplit("TLS", TLS_RECORD_HEADER);
    }

    @Test
    public void shouldTakeARecordHeaderToBeTlsExactlyWhenNettyDoes() {
        for (int firstByte = 0; firstByte < 256; firstByte++) {
            byte[] recordHeader = {(byte) firstByte, 3, 1, 0, 5};
            EmbeddedChannel channel = connection();

            clientSends(channel, recordHeader);

            assertThat("first byte " + firstByte, takenToBe(channel).equals("TLS"), is(SslHandler.isEncrypted(Unpooled.wrappedBuffer(recordHeader), false)));
        }
    }

    @Test
    public void shouldHoldAFirstByteOnlyWhenItIsATlsRecordType() {
        for (int firstByte : new int[]{19, 25}) {
            forwardedAsBinary().clear();
            EmbeddedChannel channel = connection();

            clientSends(channel, (byte) firstByte);

            assertThat(firstByte + " is next to the record types but not one, so it is binary at once", forwardedAsBinary(), contains(ByteBufUtil.hexDump(new byte[]{(byte) firstByte})));
        }
        for (int firstByte : new int[]{20, 24}) {
            forwardedAsBinary().clear();
            EmbeddedChannel channel = connection();

            clientSends(channel, (byte) firstByte);

            assertThat(firstByte + " is the first or last record type, so the rest of a header is waited for", takenToBe(channel), is("undecided"));
            assertThat(forwardedAsBinary(), is(empty()));
        }
    }

    @Test
    public void shouldNotTakeARecordHeaderOfNoLengthToBeTlsHoweverItIsSplit() {
        byte[] notTls = {22, 3, 1, 0, 0, 'x', 'y', 'z'};
        for (int split = 1; split < 5; split++) {
            forwardedAsBinary().clear();
            EmbeddedChannel channel = connection();

            clientSends(channel, Arrays.copyOfRange(notTls, 0, split));
            assertThat("split after " + split + " byte(s), the whole header is waited for", takenToBe(channel), is("undecided"));
            clientSends(channel, Arrays.copyOfRange(notTls, split, notTls.length));

            assertThat("split after " + split + " byte(s)", takenToBe(channel), is("binary"));
            assertThat("split after " + split + " byte(s)", forwardedAsBinary(), contains(ByteBufUtil.hexDump(notTls)));
        }
    }

    @Test
    public void shouldDetectSocks4HoweverItsFirstBytesAreSplit() {
        assertTakenToBeHoweverItsFirstBytesAreSplit("SOCKS4", SOCKS4_BIND);
    }

    @Test
    public void shouldDetectSocks5HoweverItsFirstBytesAreSplit() {
        assertTakenToBeHoweverItsFirstBytesAreSplit("SOCKS5", SOCKS5_GREETING);
    }

    @Test
    public void shouldNotTakeASocks5ConnectionToBeProxyingBeforeItsHandshakeIsAccepted() {
        EmbeddedChannel channel = connection();

        clientSends(channel, SOCKS5_GREETING);

        assertThat(takenToBe(channel), is("SOCKS5"));
        assertThat("marked as proxying only once a CONNECT is accepted", channel.attr(PROXYING).get(), is(nullValue()));
    }

    @Test
    public void shouldForwardAShortFirstBinaryMessageAtOnce() {
        EmbeddedChannel channel = connection();

        clientSends(channel, ascii("hello\n"));

        assertThat(forwardedAsBinary(), contains(hex("hello\n")));
    }

    @Test
    public void shouldForwardAOneByteFirstBinaryMessageAtOnce() {
        EmbeddedChannel channel = connection();

        clientSends(channel, (byte) 0x01);

        assertThat(forwardedAsBinary(), contains("01"));
    }

    @Test
    public void shouldForwardAShortLaterBinaryMessageAtOnce() {
        EmbeddedChannel channel = connection();

        clientSends(channel, ascii("a first message\n"));
        clientSends(channel, ascii("short\n"));
        clientSends(channel, (byte) 0x01);

        assertThat(forwardedAsBinary(), contains(hex("a first message\n"), hex("short\n"), "01"));
    }

    @Test
    public void shouldKeepABinaryConnectionBinaryWhenALaterMessageLooksLikeAnotherProtocol() {
        for (byte[] later : Arrays.asList(HTTP_GET, ascii("POST /some/path HTTP/1.1\r\n"), HTTP_CONNECT, H2C_PREFACE, SOCKS4_BIND, SOCKS5_GREETING, ascii("PROXIED_example.com:80"))) {
            forwardedAsBinary().clear();
            EmbeddedChannel channel = connection();

            clientSends(channel, ascii("a first message\n"));
            clientSends(channel, later);

            String sent = new String(later, StandardCharsets.ISO_8859_1);
            assertThat(sent, forwardedAsBinary(), contains(hex("a first message\n"), ByteBufUtil.hexDump(later)));
            assertThat(sent, takenToBe(channel), is("binary"));
        }
    }

    private static long tlsHandlers(EmbeddedChannel channel) {
        return channel.pipeline().names().stream().filter(name -> name.startsWith("SniHandler") || name.startsWith("SslHandler")).count();
    }

    @Test
    public void shouldTurnOnTlsWhenAHandshakeBeginsOnABinaryConnection() {
        EmbeddedChannel channel = connection();

        clientSends(channel, ascii("a first message\n"));
        clientSends(channel, CLIENT_HELLO_START);

        assertThat(takenToBe(channel), is("TLS"));
        assertThat("the handshake is not a binary message", forwardedAsBinary(), contains(hex("a first message\n")));
        assertThat("the TLS handler is the first to see what the client sends", channel.pipeline().names().get(0), startsWith("SniHandler"));
        assertThat(tlsHandlers(channel), is(1L));
        assertThat("what is decrypted from now on is not looked at again", channel.pipeline().get(PortUnificationHandler.class), is(nullValue()));
    }

    @Test
    public void shouldTurnOnTlsHoweverTheStartOfTheHandshakeIsSplit() {
        for (int split = 1; split < CLIENT_HELLO_START.length; split++) {
            forwardedAsBinary().clear();
            EmbeddedChannel channel = connection();
            clientSends(channel, ascii("a first message\n"));

            clientSends(channel, Arrays.copyOfRange(CLIENT_HELLO_START, 0, split));
            clientSends(channel, Arrays.copyOfRange(CLIENT_HELLO_START, split, CLIENT_HELLO_START.length));

            assertThat("split after " + split + " byte(s)", takenToBe(channel), is("TLS"));
            assertThat("split after " + split + " byte(s)", forwardedAsBinary(), contains(hex("a first message\n")));
            assertThat("split after " + split + " byte(s)", tlsHandlers(channel), is(1L));
        }

        forwardedAsBinary().clear();
        EmbeddedChannel byteAtATime = connection();
        clientSends(byteAtATime, ascii("a first message\n"));
        for (byte b : CLIENT_HELLO_START) {
            clientSends(byteAtATime, b);
        }
        assertThat("sent a byte at a time", takenToBe(byteAtATime), is("TLS"));
        assertThat("sent a byte at a time", forwardedAsBinary(), contains(hex("a first message\n")));
        assertThat("sent a byte at a time", tlsHandlers(byteAtATime), is(1L));
    }

    @Test
    public void shouldTurnOnTlsForTheSmallestAndLargestRecordAHandshakeCanOpenWith() {
        for (byte[] opening : Arrays.asList(new byte[]{22, 3, 0, 0, 1, 1}, new byte[]{22, 3, 4, 0x40, 0, 1})) {
            forwardedAsBinary().clear();
            EmbeddedChannel channel = connection();
            clientSends(channel, ascii("a first message\n"));

            clientSends(channel, opening);

            assertThat(ByteBufUtil.hexDump(opening), takenToBe(channel), is("TLS"));
            assertThat(ByteBufUtil.hexDump(opening), forwardedAsBinary(), contains(hex("a first message\n")));
        }
    }

    @Test
    public void shouldKeepALaterMessageBinaryWhenItOnlyResemblesTheStartOfAHandshake() {
        byte[][] notAHandshake = {
            {23, 3, 1, 0, 100, 1, 0, 0, 96},          // an application data record
            {22, 2, 1, 0, 100, 1, 0, 0, 96},          // not version 3.x
            {22, 4, 1, 0, 100, 1, 0, 0, 96},
            {22, 3, 5, 0, 100, 1, 0, 0, 96},          // past version 3.4
            {22, 3, 1, 0, 0, 1, 0, 0, 96},            // an empty record
            {22, 3, 1, 0x40, 1, 1, 0, 0, 96},         // longer than a record can be
            {22, 3, 1, 0, 100, 2, 0, 0, 96},          // a handshake message, but not a ClientHello
            {22, 3, 1, 0, 100, 0, 0, 0, 96},
        };
        for (byte[] later : notAHandshake) {
            forwardedAsBinary().clear();
            EmbeddedChannel channel = connection();
            clientSends(channel, ascii("a first message\n"));

            clientSends(channel, later);

            String sent = ByteBufUtil.hexDump(later);
            assertThat(sent + " is forwarded at once, as it is", forwardedAsBinary(), contains(hex("a first message\n"), sent));
            assertThat(sent, takenToBe(channel), is("binary"));
            assertThat(sent, tlsHandlers(channel), is(0L));
        }
    }

    @Test
    public void shouldHoldALaterMessageThatCouldStillBecomeAHandshakeOnlyBriefly() {
        for (int length = 1; length < 6; length++) {
            byte[] couldBecome = Arrays.copyOfRange(CLIENT_HELLO_START, 0, length);
            String sent = ByteBufUtil.hexDump(couldBecome);
            forwardedAsBinary().clear();
            EmbeddedChannel channel = connection();
            clientSends(channel, ascii("a first message\n"));

            clientSends(channel, couldBecome);
            channel.advanceTimeBy(PortUnificationHandler.UNDECIDED_PROTOCOL_WAIT_MILLIS - 1, TimeUnit.MILLISECONDS);
            channel.runScheduledPendingTasks();
            assertThat(sent + " is held while the rest of a handshake may still arrive", forwardedAsBinary(), contains(hex("a first message\n")));

            channel.advanceTimeBy(1, TimeUnit.MILLISECONDS);
            channel.runScheduledPendingTasks();
            assertThat(sent + " is forwarded when nothing more arrives", forwardedAsBinary(), contains(hex("a first message\n"), sent));
            assertThat(sent, takenToBe(channel), is("binary"));

            clientSends(channel, ascii("the next message\n"));
            assertThat("and the connection carries on as before", forwardedAsBinary(), contains(hex("a first message\n"), sent, hex("the next message\n")));
        }
    }

    @Test
    public void shouldForwardAHeldLaterMessageOnItsOwnWhenWhatFollowsShowsItIsNotAHandshake() {
        for (int length = 1; length < 6; length++) {
            byte[] held = Arrays.copyOfRange(CLIENT_HELLO_START, 0, length);
            forwardedAsBinary().clear();
            EmbeddedChannel channel = connection();
            clientSends(channel, ascii("a first message\n"));

            clientSends(channel, held);
            clientSends(channel, ascii("xyz"));

            assertThat("the " + length + " byte(s) held were a message, and what settled it is the next", forwardedAsBinary(), contains(hex("a first message\n"), ByteBufUtil.hexDump(held), hex("xyz")));
            assertThat(takenToBe(channel), is("binary"));

            clientSends(channel, ascii("xyz"));
            assertThat("nothing is split off a message that was never held", forwardedAsBinary(), contains(hex("a first message\n"), ByteBufUtil.hexDump(held), hex("xyz"), hex("xyz")));
        }
    }

    @Test
    public void shouldForwardBytesHeldOverSeveralReadsAsOneMessage() {
        EmbeddedChannel channel = connection();
        clientSends(channel, ascii("a first message\n"));

        clientSends(channel, (byte) 22);
        clientSends(channel, (byte) 3);
        clientSends(channel, ascii("xyz"));

        assertThat("only where the held bytes end is remembered", forwardedAsBinary(), contains(hex("a first message\n"), "1603", hex("xyz")));
    }

    @Test
    public void shouldTurnOnTlsWhenAHandshakeFollowsAHeldMessageInTheNextRead() {
        // three held bytes are left out: followed by a handshake they read as the start of one themselves
        for (int length : new int[]{1, 2, 4, 5}) {
            byte[] held = Arrays.copyOfRange(CLIENT_HELLO_START, 0, length);
            forwardedAsBinary().clear();
            EmbeddedChannel channel = connection();
            clientSends(channel, ascii("a first message\n"));

            clientSends(channel, held);
            clientSends(channel, CLIENT_HELLO_START);

            assertThat("the " + length + " byte(s) held were a message, and the handshake is not one", forwardedAsBinary(), contains(hex("a first message\n"), ByteBufUtil.hexDump(held)));
            assertThat("after " + length + " held byte(s)", takenToBe(channel), is("TLS"));
            assertThat("after " + length + " held byte(s)", tlsHandlers(channel), is(1L));
        }
    }

    @Test
    public void shouldHoldWhatFollowsAHeldMessageWhenItCouldBecomeAHandshakeItself() {
        EmbeddedChannel channel = connection();
        clientSends(channel, ascii("a first message\n"));

        clientSends(channel, (byte) 22);
        clientSends(channel, (byte) 22, (byte) 3);
        assertThat("the byte held is delivered, and the two that settled it are held in their turn", forwardedAsBinary(), contains(hex("a first message\n"), "16"));

        channel.advanceTimeBy(PortUnificationHandler.UNDECIDED_PROTOCOL_WAIT_MILLIS - 1, TimeUnit.MILLISECONDS);
        channel.runScheduledPendingTasks();
        assertThat("for as long as any held bytes are", forwardedAsBinary(), contains(hex("a first message\n"), "16"));

        channel.advanceTimeBy(1, TimeUnit.MILLISECONDS);
        channel.runScheduledPendingTasks();
        assertThat(forwardedAsBinary(), contains(hex("a first message\n"), "16", "1603"));
        assertThat(takenToBe(channel), is("binary"));
    }

    @Test
    public void shouldTurnOnTlsWhenTheRestOfAHandshakeFollowsBytesHeldAfterAHeldMessage() {
        EmbeddedChannel channel = connection();
        clientSends(channel, ascii("a first message\n"));

        clientSends(channel, (byte) 22);
        clientSends(channel, (byte) 22, (byte) 3);
        clientSends(channel, Arrays.copyOfRange(CLIENT_HELLO_START, 2, CLIENT_HELLO_START.length));

        assertThat(forwardedAsBinary(), contains(hex("a first message\n"), "16"));
        assertThat(takenToBe(channel), is("TLS"));
        assertThat(tlsHandlers(channel), is(1L));
    }

    @Test
    public void shouldNotHoldALaterMessageWhoseRecordLengthIsAlreadyTooLong() {
        EmbeddedChannel channel = connection();
        clientSends(channel, ascii("a first message\n"));

        // 0x41 as the first length byte: longer than a record can be, whatever the second
        clientSends(channel, (byte) 22, (byte) 3, (byte) 1, (byte) 0x41);
        assertThat(forwardedAsBinary(), contains(hex("a first message\n"), "16030141"));

        clientSends(channel, (byte) 22, (byte) 3, (byte) 1, (byte) 0x40);
        assertThat("0x40 can still be the longest record there is, so it is held", forwardedAsBinary(), contains(hex("a first message\n"), "16030141"));
    }

    @Test
    public void shouldForwardAHeldLaterMessageWhenTheClientCloses() {
        EmbeddedChannel channel = connection();
        clientSends(channel, ascii("a first message\n"));

        clientSends(channel, (byte) 22, (byte) 3, (byte) 1);
        channel.close();

        assertThat(forwardedAsBinary(), contains(hex("a first message\n"), "160301"));
    }

    @Test
    public void shouldNotTurnOnTlsLaterWhenAllRequestsAreAssumedToBeHttp() {
        configuration.assumeAllRequestsAreHttp(true);
        EmbeddedChannel channel = connection();

        // nothing is binary under this setting, so there is no binary connection for a handshake to begin on
        clientSends(channel, (byte) 0, (byte) 0, (byte) 0, (byte) 8, (byte) 4, (byte) 0xd2, (byte) 0x16, (byte) 0x2f);
        assertThat(takenToBe(channel), is("HTTP/1.1"));
        clientSends(channel, CLIENT_HELLO_START);

        assertThat(takenToBe(channel), is("HTTP/1.1"));
        assertThat(tlsHandlers(channel), is(0L));
        assertThat(forwardedAsBinary(), is(empty()));
    }

    // read loops every connection below is put through: small messages, larger ones, and more than one buffer holds
    private static final int[] BYTES_WAITING = {5, 5, 5, 5, 5, 5, 5, 5, 100, 422, 800, 3000, 200_000, 5, 5, 5, 5, 1200};

    /** How the connection's reads are sized from now on. */
    private static List<String> readsFromNowOn(EmbeddedChannel channel) {
        return BinaryAwareRecvByteBufAllocatorTest.readsOf(BinaryAwareRecvByteBufAllocatorTest.readHandle(channel), channel.config(), BYTES_WAITING);
    }

    /** How Netty sizes the same reads on a channel MockServer has not touched. */
    private List<String> readsAsNettySizesThem() {
        EmbeddedChannel untouched = new EmbeddedChannel();
        channels.add(untouched);
        return readsFromNowOn(untouched);
    }

    private static List<String> buffersOf(List<String> reads) {
        List<String> buffers = new ArrayList<>(reads);
        buffers.removeIf(read -> read.startsWith("end of loop"));
        return buffers;
    }

    @Test
    public void shouldSizeTheReadsOfAConnectionThatIsNotBinaryExactlyAsNettyDoes() {
        List<String> asNettySizesThem = readsAsNettySizesThem();
        assertThat("Netty's sizes do follow the traffic down", asNettySizesThem, hasItem("guess 512 buffer 512"));
        String[] protocols = {"HTTP/1.1", "HTTP/1.1", "HTTP/2", "TLS", "TLS", "SOCKS4", "SOCKS5", "undecided", "undecided"};
        byte[][] firstBytes = {HTTP_GET, HTTP_CONNECT, H2C_PREFACE, TLS_RECORD_HEADER, CLIENT_HELLO_START, SOCKS4_BIND, SOCKS5_GREETING, ascii("GE"), {22, 3}};
        for (int i = 0; i < protocols.length; i++) {
            EmbeddedChannel channel = connection();

            clientSends(channel, firstBytes[i]);

            String sent = ByteBufUtil.hexDump(firstBytes[i]);
            assertThat(sent, takenToBe(channel), is(protocols[i]));
            assertThat(sent + ", taken to be " + protocols[i], readsFromNowOn(channel), is(asNettySizesThem));
        }

        EmbeddedChannel tunnel = relayLoopbackConnection();
        clientSends(tunnel, ascii("PROXIED_example.com:80"));
        assertThat(replyTo(tunnel), is(hex("PROXIED_RESPONSE_PROXIED_example.com:80")));
        clientSends(tunnel, HTTP_GET);
        assertThat(takenToBe(tunnel), is("HTTP/1.1"));
        assertThat("HTTP inside a tunnel", readsFromNowOn(tunnel), is(asNettySizesThem));
        assertThat(forwardedAsBinary(), is(empty()));
    }

    @Test
    public void shouldSizeTheReadsOfUnknownBytesAsNettyDoesWhenAllRequestsAreAssumedToBeHttp() {
        configuration.assumeAllRequestsAreHttp(true);
        EmbeddedChannel channel = connection();

        clientSends(channel, ascii("PROP"));

        assertThat(takenToBe(channel), is("HTTP/1.1"));
        assertThat(readsFromNowOn(channel), is(readsAsNettySizesThem()));
    }

    @Test
    public void shouldGiveEveryReadOfABinaryConnection64KiB() {
        EmbeddedChannel channel = connection();

        clientSends(channel, ascii("a first message\n"));

        assertThat(takenToBe(channel), is("binary"));
        assertThat(buffersOf(readsFromNowOn(channel)), everyItem(is("guess 65536 buffer 65536")));
    }

    @Test
    public void shouldGiveEveryReadOfAConnectionTakenToBeBinaryAfterAWait64KiB() {
        EmbeddedChannel channel = connection();
        clientSends(channel, ascii("GET"));
        assertThat("while undecided", readsFromNowOn(channel).get(0), is("guess 2048 buffer 2048"));

        channel.advanceTimeBy(PortUnificationHandler.UNDECIDED_PROTOCOL_WAIT_MILLIS, TimeUnit.MILLISECONDS);
        channel.runScheduledPendingTasks();

        assertThat(takenToBe(channel), is("binary"));
        assertThat(buffersOf(readsFromNowOn(channel)), everyItem(is("guess 65536 buffer 65536")));
    }

    @Test
    public void shouldStillGiveEveryRead64KiBAfterABinaryConnectionTurnsOnTls() {
        EmbeddedChannel channel = connection();
        clientSends(channel, ascii("a first message\n"));

        clientSends(channel, CLIENT_HELLO_START);

        assertThat(takenToBe(channel), is("TLS"));
        assertThat(buffersOf(readsFromNowOn(channel)), everyItem(is("guess 65536 buffer 65536")));
    }

    @Test
    public void shouldHoldAFirstMessageThatCouldStillBecomeAKnownProtocolOnlyBriefly() {
        byte[] tunnelStart = ascii("PROXIED");
        for (byte[] couldBecome : Arrays.asList(ascii("GET"), ascii("P"), ascii("PRI * HTTP"), new byte[]{22, 3, 1}, new byte[]{4, 1, 0}, new byte[]{5}, tunnelStart)) {
            forwardedAsBinary().clear();
            // only a relay's own loopback can send the tunnel's first message
            EmbeddedChannel channel = couldBecome == tunnelStart ? relayLoopbackConnection() : connection();
            String sent = ByteBufUtil.hexDump(couldBecome);

            clientSends(channel, couldBecome);
            assertThat(sent + " is held while the rest may still arrive", forwardedAsBinary(), is(empty()));
            assertThat(sent, takenToBe(channel), is("undecided"));

            channel.advanceTimeBy(PortUnificationHandler.UNDECIDED_PROTOCOL_WAIT_MILLIS - 1, TimeUnit.MILLISECONDS);
            channel.runScheduledPendingTasks();
            assertThat(sent + " is still held just before the wait ends", forwardedAsBinary(), is(empty()));

            channel.advanceTimeBy(1, TimeUnit.MILLISECONDS);
            channel.runScheduledPendingTasks();
            assertThat(sent + " is forwarded when nothing more arrives", forwardedAsBinary(), contains(sent));
            assertThat(sent, takenToBe(channel), is("binary"));
        }
    }

    @Test
    public void shouldRestartTheWaitWhenMoreOfAKnownProtocolsFirstBytesArrive() {
        EmbeddedChannel channel = connection();

        clientSends(channel, ascii("OPT"));
        channel.advanceTimeBy(PortUnificationHandler.UNDECIDED_PROTOCOL_WAIT_MILLIS - 1, TimeUnit.MILLISECONDS);
        channel.runScheduledPendingTasks();
        clientSends(channel, ascii("ION"));
        channel.advanceTimeBy(PortUnificationHandler.UNDECIDED_PROTOCOL_WAIT_MILLIS - 1, TimeUnit.MILLISECONDS);
        channel.runScheduledPendingTasks();
        assertThat("a slow client is still undecided, not binary", takenToBe(channel), is("undecided"));

        clientSends(channel, ascii("S * HTTP/1.1\r\n"));

        assertThat(takenToBe(channel), is("HTTP/1.1"));
        assertThat(forwardedAsBinary(), is(empty()));
    }

    @Test
    public void shouldNotEndTheWaitWhileReadsArePaused() {
        EmbeddedChannel channel = connection();

        clientSends(channel, ascii("GET"));
        channel.config().setAutoRead(false);
        channel.advanceTimeBy(PortUnificationHandler.UNDECIDED_PROTOCOL_WAIT_MILLIS * 3, TimeUnit.MILLISECONDS);
        channel.runScheduledPendingTasks();
        assertThat("the rest may be waiting unread, so silence says nothing", takenToBe(channel), is("undecided"));

        channel.config().setAutoRead(true);
        clientSends(channel, ascii(" / HTTP/1.1\r\n"));

        assertThat(takenToBe(channel), is("HTTP/1.1"));
        assertThat(forwardedAsBinary(), is(empty()));
    }

    @Test
    public void shouldEndTheWaitOnceReadsResumeAndNothingMoreArrives() {
        EmbeddedChannel channel = connection();

        clientSends(channel, ascii("GET"));
        channel.config().setAutoRead(false);
        channel.advanceTimeBy(PortUnificationHandler.UNDECIDED_PROTOCOL_WAIT_MILLIS, TimeUnit.MILLISECONDS);
        channel.runScheduledPendingTasks();
        assertThat(takenToBe(channel), is("undecided"));

        channel.config().setAutoRead(true);
        channel.advanceTimeBy(PortUnificationHandler.UNDECIDED_PROTOCOL_WAIT_MILLIS, TimeUnit.MILLISECONDS);
        channel.runScheduledPendingTasks();

        assertThat("the pause only put the wait off", forwardedAsBinary(), contains(hex("GET")));
        assertThat(takenToBe(channel), is("binary"));
    }

    @Test
    public void shouldForwardAHeldFirstMessageWhenTheClientCloses() {
        EmbeddedChannel channel = connection();

        clientSends(channel, ascii("GET"));
        channel.close();

        assertThat("a client that sends and closes has what it sent forwarded", forwardedAsBinary(), contains(hex("GET")));
    }

    @Test
    public void shouldDetectWhatFollowsATunnelsFirstMessageHoweverThatMessageIsSplit() {
        byte[] tunnelTo = ascii("PROXIED_example.com:80");
        for (int split = 1; split < "PROXIED_".length(); split++) {
            EmbeddedChannel channel = relayLoopbackConnection();

            clientSends(channel, Arrays.copyOfRange(tunnelTo, 0, split));
            clientSends(channel, Arrays.copyOfRange(tunnelTo, split, tunnelTo.length));
            assertThat("split after " + split + " byte(s)", replyTo(channel), is(hex("PROXIED_RESPONSE_PROXIED_example.com:80")));

            // the wait begun for the split message must not decide what the tunnel then carries
            channel.advanceTimeBy(PortUnificationHandler.UNDECIDED_PROTOCOL_WAIT_MILLIS, TimeUnit.MILLISECONDS);
            channel.runScheduledPendingTasks();
            clientSends(channel, HTTP_GET);

            assertThat("split after " + split + " byte(s)", takenToBe(channel), is("HTTP/1.1"));
            assertThat(forwardedAsBinary(), is(empty()));
        }
    }

    @Test
    public void shouldLeaveNoWaitBehindOnceTheConnectionIsDecidedOrDetectionIsRemoved() {
        // no other timer of the connection's, so that any left is the wait's
        configuration.inboundConnectionIdleTimeoutMillis(0L).responseWriteStallTimeoutMillis(0L);
        EmbeddedChannel decided = connection();
        clientSends(decided, ascii("GE"));
        clientSends(decided, ascii("T / HTTP/1.1\r\n"));
        assertThat(takenToBe(decided), is("HTTP/1.1"));
        assertThat("no wait is left running once decided", decided.runScheduledPendingTasks(), is(-1L));

        // a tunnel's first message is decided without detection ending: what follows it is still to be detected
        EmbeddedChannel tunnel = relayLoopbackConnection();
        clientSends(tunnel, ascii("PROX"));
        clientSends(tunnel, ascii("IED_example.com:80"));
        assertThat(replyTo(tunnel), is(hex("PROXIED_RESPONSE_PROXIED_example.com:80")));
        assertThat("nor once one stage of several is decided", tunnel.runScheduledPendingTasks(), is(-1L));

        // as a tunnel does when it takes the connection over
        EmbeddedChannel takenOver = connection();
        clientSends(takenOver, new byte[]{22, 3, 1});
        takenOver.pipeline().remove(PortUnificationHandler.class);
        assertThat("nor once detection has been removed", takenOver.runScheduledPendingTasks(), is(-1L));
    }

    @Test
    public void shouldTakeAHeldFirstMessageToBeHttpWhenAllRequestsAreAssumedToBeHttp() {
        configuration.assumeAllRequestsAreHttp(true);
        EmbeddedChannel channel = connection();

        clientSends(channel, ascii("PRO"));
        channel.advanceTimeBy(PortUnificationHandler.UNDECIDED_PROTOCOL_WAIT_MILLIS, TimeUnit.MILLISECONDS);
        channel.runScheduledPendingTasks();

        assertThat(takenToBe(channel), is("HTTP/1.1"));
        assertThat(forwardedAsBinary(), is(empty()));
    }

    @Test
    public void shouldTakeBytesThatAreNoKnownProtocolToBeHttpAtOnceWhenAllRequestsAreAssumedToBeHttp() {
        configuration.assumeAllRequestsAreHttp(true);
        EmbeddedChannel channel = connection();

        clientSends(channel, ascii("PROP"));

        assertThat(takenToBe(channel), is("HTTP/1.1"));
        assertThat(forwardedAsBinary(), is(empty()));
    }

    @Test
    public void shouldNotTakeTheTunnelsFirstMessageFromAConnectionThatIsNoRelaysLoopback() {
        EmbeddedChannel channel = connection();

        clientSends(channel, ascii("PROXIED_example.com:80"));

        assertThat("the preamble is not acknowledged", replyTo(channel), is(nullValue()));
        assertThat(takenToBe(channel), is("binary"));
        assertThat("no tunnel was opened", channel.attr(PROXYING).get(), is(nullValue()));
        assertThat(forwardedAsBinary(), contains(hex("PROXIED_example.com:80")));
    }

    @Test
    public void shouldNotHoldTheStartOfTheTunnelsFirstMessageFromAConnectionThatIsNoRelaysLoopback() {
        EmbeddedChannel channel = connection();

        clientSends(channel, ascii("PROXIED"));
        clientSends(channel, ascii("_example.com:80"));

        assertThat("the preamble is not acknowledged", replyTo(channel), is(nullValue()));
        assertThat(takenToBe(channel), is("binary"));
        assertThat(forwardedAsBinary(), contains(hex("PROXIED"), hex("_example.com:80")));
    }

    @Test
    public void shouldNotTakeTheTunnelsFirstMessageOnceTheRelaysLoopbackHasClosed() {
        RelayLoopbackStandIn closedLoopback = new RelayLoopbackStandIn();
        closedLoopback.close();
        EmbeddedChannel channel = connectionFrom(closedLoopback);

        clientSends(channel, ascii("PROXIED_example.com:80"));

        assertThat("the preamble is not acknowledged", replyTo(channel), is(nullValue()));
        assertThat(takenToBe(channel), is("binary"));
    }

    @Test
    public void shouldNotTakeTheTunnelsFirstMessageFromTheRelaysSourceAddressToAnotherListener() {
        RelayLoopbackStandIn relayLoopback = new RelayLoopbackStandIn();
        relayLoopbacks.add(relayLoopback);
        EmbeddedChannel channel = connectionBetween(relayLoopback.address(), new LocalAddress("other-listener-" + UUID.randomUUID()));

        clientSends(channel, ascii("PROXIED_example.com:80"));

        assertThat("the preamble is not acknowledged", replyTo(channel), is(nullValue()));
        assertThat(takenToBe(channel), is("binary"));
        assertThat("no tunnel was opened", channel.attr(PROXYING).get(), is(nullValue()));
    }
}
