package org.mockserver.netty.proxy;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.embedded.EmbeddedChannel;
import org.junit.Test;
import org.mockserver.log.model.LogEntry;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.proxyconfiguration.ProxyProtocolTrustedSources;
import org.slf4j.event.Level;

import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.mockserver.mock.action.http.HttpActionHandler.REMOTE_SOCKET;
import static org.mockserver.netty.HttpRequestHandler.PROXYING;
import static org.mockserver.socket.SocketAddresses.PROXY_PROTOCOL_SOURCE;

public class ProxyProtocolTrustedSourcesHandlerTest {

    private static final String V1_HEADER = "PROXY TCP4 192.168.1.1 10.0.0.1 56324 80\r\n";
    private static final String HTTP_REQUEST = "GET / HTTP/1.1\r\nHost: example.com\r\n\r\n";
    private static final byte[] V2_HEADER = {
        0x0D, 0x0A, 0x0D, 0x0A, 0x00, 0x0D, 0x0A, 0x51, 0x55, 0x49, 0x54, 0x0A,
        0x21, 0x11, 0x00, 0x0C,
        (byte) 192, (byte) 168, 1, 1, 10, 0, 0, 1, (byte) 0xDC, 0x04, 0x01, (byte) 0xBB
    };

    private final MockServerLogger logger = new MockServerLogger(ProxyProtocolTrustedSourcesHandlerTest.class);
    private final ProxyProtocolTrustedSources trustedSources = ProxyProtocolTrustedSources.parse("10.0.0.0/8, fd00::/8");

    @Test
    public void shouldApplyAV1HeaderFromATrustedIpv4Source() {
        EmbeddedChannel channel = channelFrom(new InetSocketAddress("10.1.2.3", 40000));

        channel.writeInbound(ascii(V1_HEADER + HTTP_REQUEST));

        assertThat(channel.isOpen(), is(true));
        assertThat(channel.attr(REMOTE_SOCKET).get(), is(new InetSocketAddress("10.0.0.1", 80)));
        assertThat(channel.attr(PROXYING).get(), is(Boolean.TRUE));
        assertPassedOn(channel, HTTP_REQUEST);
        channel.finishAndReleaseAll();
    }

    @Test
    public void shouldApplyAV2HeaderFromATrustedIpv6Source() {
        EmbeddedChannel channel = channelFrom(new InetSocketAddress("fd12::7", 40000));

        channel.writeInbound(Unpooled.wrappedBuffer(V2_HEADER));

        assertThat(channel.isOpen(), is(true));
        assertThat(channel.attr(REMOTE_SOCKET).get(), is(new InetSocketAddress("10.0.0.1", 443)));
        channel.finishAndReleaseAll();
    }

    @Test
    public void shouldCloseAConnectionThatSendsAV1HeaderFromAnUntrustedSource() {
        EmbeddedChannel channel = channelFrom(new InetSocketAddress("127.0.0.1", 40000));

        channel.writeInbound(ascii(V1_HEADER + HTTP_REQUEST));

        assertRefused(channel);
    }

    @Test
    public void shouldCloseAConnectionThatSendsAV2HeaderFromAnUntrustedSource() {
        EmbeddedChannel channel = channelFrom(new InetSocketAddress("fe80::1", 40000));

        channel.writeInbound(Unpooled.wrappedBuffer(V2_HEADER));

        assertRefused(channel);
    }

    @Test
    public void shouldCloseAnUntrustedConnectionAsSoonAsItsBytesMatchTheWholeSignature() {
        EmbeddedChannel channel = channelFrom(new InetSocketAddress("127.0.0.1", 40000));

        channel.writeInbound(ascii("PROX"));
        assertThat("not yet a whole signature", channel.isOpen(), is(true));
        channel.writeInbound(ascii("Y TCP4 192.168.1.1"));

        assertRefused(channel);
    }

    @Test
    public void shouldPassOnAConnectionWithoutAHeaderFromAnUntrustedSource() {
        EmbeddedChannel channel = channelFrom(new InetSocketAddress("127.0.0.1", 40000));

        channel.writeInbound(ascii(HTTP_REQUEST));

        assertThat(channel.isOpen(), is(true));
        assertThat(channel.attr(REMOTE_SOCKET).get(), nullValue());
        assertPassedOn(channel, HTTP_REQUEST);
        channel.finishAndReleaseAll();
    }

    @Test
    public void shouldAcceptAHeaderFromAnySourceWhenNoSourceIsListed() {
        EmbeddedChannel channel = channelFrom(new InetSocketAddress("127.0.0.1", 40000), (ProxyProtocolTrustedSources) null);

        channel.writeInbound(ascii(V1_HEADER));

        assertThat(channel.isOpen(), is(true));
        assertThat(channel.attr(REMOTE_SOCKET).get(), is(new InetSocketAddress("10.0.0.1", 80)));
        channel.finishAndReleaseAll();
    }

    @Test
    public void shouldReleaseWhatIsReadAfterTheConnectionWasRefused() throws Exception {
        ProxyProtocolOriginalDestinationHandler handler = new ProxyProtocolOriginalDestinationHandler(logger, trustedSources);
        EmbeddedChannel channel = channelFrom(new InetSocketAddress("127.0.0.1", 40000), handler);
        ChannelHandlerContext context = channel.pipeline().context(handler);
        channel.writeInbound(ascii(V1_HEADER));
        assertRefused(channel);
        ByteBuf readAfterRefusal = ascii(HTTP_REQUEST);

        handler.channelRead(context, readAfterRefusal);

        assertThat(readAfterRefusal.refCnt(), is(0));
    }

    @Test
    public void shouldWarnOfTheFirstRefusalAndLogTheRestAtDebugUntilAMinuteHasPassed() {
        CapturingLogger capturingLogger = new CapturingLogger();
        ProxyProtocolOriginalDestinationHandler.RefusalWarnings refusalWarnings = new ProxyProtocolOriginalDestinationHandler.RefusalWarnings();

        for (int i = 0; i < 3; i++) {
            EmbeddedChannel channel = channelFrom(new InetSocketAddress("127.0.0.1", 40000 + i), new ProxyProtocolOriginalDestinationHandler(capturingLogger, trustedSources, refusalWarnings));
            channel.writeInbound(ascii(V1_HEADER));
            assertRefused(channel);
        }

        List<Level> levels = capturingLogger.logged.stream().map(LogEntry::getLogLevel).collect(Collectors.toList());
        assertThat(levels, contains(Level.WARN, Level.DEBUG, Level.DEBUG));
        assertThat(capturingLogger.logged.get(0).getMessage(), containsString("not in proxyProtocolTrustedSources, closing the connection"));
    }

    @Test
    public void shouldCountTheRefusalsLoggedAtDebugInTheNextWarningOnceAMinuteHasPassed() {
        CapturingLogger capturingLogger = new CapturingLogger();
        AtomicLong now = new AtomicLong(1_000L);
        ProxyProtocolOriginalDestinationHandler.RefusalWarnings refusalWarnings = new ProxyProtocolOriginalDestinationHandler.RefusalWarnings(now::get);

        for (int i = 0; i < 3; i++) {
            refuseOne(capturingLogger, refusalWarnings, 40000 + i);
        }
        now.addAndGet(ProxyProtocolOriginalDestinationHandler.RefusalWarnings.INTERVAL_NANOS);
        refuseOne(capturingLogger, refusalWarnings, 40003);

        List<Level> levels = capturingLogger.logged.stream().map(LogEntry::getLogLevel).collect(Collectors.toList());
        assertThat(levels, contains(Level.WARN, Level.DEBUG, Level.DEBUG, Level.WARN));
        LogEntry afterTheMinute = capturingLogger.logged.get(3);
        assertThat(afterTheMinute.getMessageFormat(), containsString("({} more refused since the last warning, logged at DEBUG)"));
        assertThat(afterTheMinute.getArguments()[2], is(2L));
        assertThat(capturingLogger.logged.get(0).getMessageFormat(), not(containsString("more refused")));
    }

    private void refuseOne(MockServerLogger mockServerLogger, ProxyProtocolOriginalDestinationHandler.RefusalWarnings refusalWarnings, int port) {
        EmbeddedChannel channel = channelFrom(new InetSocketAddress("127.0.0.1", port), new ProxyProtocolOriginalDestinationHandler(mockServerLogger, trustedSources, refusalWarnings));
        channel.writeInbound(ascii(V1_HEADER));
        assertRefused(channel);
    }

    private EmbeddedChannel channelFrom(SocketAddress peer) {
        return channelFrom(peer, trustedSources);
    }

    private EmbeddedChannel channelFrom(SocketAddress peer, ProxyProtocolTrustedSources sources) {
        return channelFrom(peer, new ProxyProtocolOriginalDestinationHandler(logger, sources));
    }

    private static EmbeddedChannel channelFrom(SocketAddress peer, ProxyProtocolOriginalDestinationHandler handler) {
        EmbeddedChannel channel = new EmbeddedChannel() {
            @Override
            protected SocketAddress remoteAddress0() {
                return peer;
            }
        };
        channel.pipeline().addLast(handler);
        return channel;
    }

    private static final class CapturingLogger extends MockServerLogger {
        private final List<LogEntry> logged = new CopyOnWriteArrayList<>();

        @Override
        public boolean isEnabledForInstance(Level level) {
            return true;
        }

        @Override
        public void logEvent(LogEntry logEntry) {
            logged.add(logEntry);
        }
    }

    private static void assertRefused(EmbeddedChannel channel) {
        assertThat("closed", channel.isOpen(), is(false));
        assertThat("nothing passed on", channel.<ByteBuf>readInbound(), nullValue());
        assertThat(channel.attr(REMOTE_SOCKET).get(), nullValue());
        assertThat(channel.attr(PROXYING).get(), nullValue());
        assertThat(channel.attr(PROXY_PROTOCOL_SOURCE).get(), nullValue());
        channel.finishAndReleaseAll();
    }

    private static void assertPassedOn(EmbeddedChannel channel, String expected) {
        ByteBuf passedOn = channel.readInbound();
        assertThat(passedOn, notNullValue());
        assertThat(passedOn.toString(StandardCharsets.US_ASCII), is(expected));
        passedOn.release();
    }

    private static ByteBuf ascii(String text) {
        return Unpooled.copiedBuffer(text, StandardCharsets.US_ASCII);
    }
}
