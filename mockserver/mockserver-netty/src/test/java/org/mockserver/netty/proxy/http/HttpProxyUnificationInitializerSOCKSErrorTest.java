package org.mockserver.netty.proxy.http;

import com.google.common.base.Strings;
import com.google.common.primitives.Bytes;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.HttpContentDecompressor;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpServerCodec;
import io.netty.handler.codec.socks.SocksInitRequestDecoder;
import io.netty.handler.codec.socks.SocksMessageEncoder;
import io.netty.util.CharsetUtil;
import io.netty.util.NetUtil;
import org.apache.commons.codec.DecoderException;
import org.apache.commons.codec.binary.Hex;
import org.junit.After;
import org.junit.Test;
import org.mockserver.lifecycle.LifeCycle;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.mock.HttpState;
import org.mockserver.mock.action.http.HttpActionHandler;
import org.mockserver.netty.MockServerUnificationInitializer;
import org.mockserver.netty.proxy.socks.Socks5ProxyHandler;
import org.mockserver.scheduler.Scheduler;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.hamcrest.CoreMatchers.nullValue;
import static org.hamcrest.collection.IsIterableContainingInOrder.contains;
import static org.hamcrest.core.Is.is;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockserver.configuration.Configuration.configuration;
import static org.slf4j.event.Level.TRACE;

public class HttpProxyUnificationInitializerSOCKSErrorTest {

    private EmbeddedChannel embeddedChannel;
    private final List<HttpState> httpStates = new ArrayList<>();

    @After
    public void releaseChannel() {
        // Release ByteBufs the un-closed EmbeddedChannel still holds (decoder cumulations etc.);
        // a real socket channel frees these on close.
        if (embeddedChannel != null) {
            embeddedChannel.finishAndReleaseAll();
        }
        httpStates.forEach(HttpState::stop);
    }

    private HttpState newHttpState() {
        HttpState httpState = new HttpState(configuration(), new MockServerLogger(), mock(Scheduler.class));
        httpStates.add(httpState);
        return httpState;
    }

    @Test
    public void shouldHandleErrorsDuringSOCKSConnection() throws DecoderException {
        // given - embedded channel
        short localPort = 1234;
        final LifeCycle lifeCycle = mock(LifeCycle.class);
        when(lifeCycle.getScheduler()).thenReturn(mock(Scheduler.class));
        embeddedChannel = new EmbeddedChannel(new MockServerUnificationInitializer(configuration(), lifeCycle, newHttpState(), mock(HttpActionHandler.class), null));

        // and - no SOCKS handlers
        assertThat(embeddedChannel.pipeline().get(Socks5ProxyHandler.class), is(nullValue()));
        assertThat(embeddedChannel.pipeline().get(SocksMessageEncoder.class), is(nullValue()));
        assertThat(embeddedChannel.pipeline().get(SocksInitRequestDecoder.class), is(nullValue()));

        // when - SOCKS INIT message
        embeddedChannel.writeInbound(Unpooled.wrappedBuffer(new byte[]{
            (byte) 0x05,                                        // SOCKS5
            (byte) 0x02,                                        // 1 authentication method
            (byte) 0x00,                                        // NO_AUTH
            (byte) 0x02,                                        // AUTH_PASSWORD
        }));


        // then - INIT response (release the dequeued outbound buffer after asserting)
        ByteBuf initResponse = embeddedChannel.readOutbound();
        assertThat(ByteBufUtil.hexDump(initResponse), is(Hex.encodeHexString(new byte[]{
            (byte) 0x05,                                        // SOCKS5
            (byte) 0x00,                                        // NO_AUTH
        })));
        initResponse.release();

        // and then - should add SOCKS handlers first
        if (MockServerLogger.isEnabled(TRACE)) {
            assertThat(String.valueOf(embeddedChannel.pipeline().names()), embeddedChannel.pipeline().names(), contains(
                "LoggingHandler#0",
                "Socks5CommandRequestDecoder#0",
                "Socks5ServerEncoder#0",
                "Socks5ProxyHandler#0",
                "write-stall",
                "inbound-idle",
                "PortUnificationHandler#0",
                "DefaultChannelPipeline$TailContext#0"
            ));
        } else {
            assertThat(String.valueOf(embeddedChannel.pipeline().names()), embeddedChannel.pipeline().names(), contains(
                "Socks5CommandRequestDecoder#0",
                "Socks5ServerEncoder#0",
                "Socks5ProxyHandler#0",
                "write-stall",
                "inbound-idle",
                "PortUnificationHandler#0",
                "DefaultChannelPipeline$TailContext#0"
            ));
        }

        // and when - SOCKS CONNECT command
        String portInHex = Strings.padStart(BigInteger.valueOf(localPort).toString(16), 4, '0');
        byte[] ipAddressInBytes = NetUtil.createByteArrayFromIpAddressString("127.0.0.1");
        embeddedChannel.writeInbound(Unpooled.wrappedBuffer(Bytes.concat(
            new byte[]{
                (byte) 0x05,                                        // SOCKS5
                (byte) 0x01,                                        // command type CONNECT
                (byte) 0x00,                                        // reserved (must be 0x00)
                (byte) 0x01                                         // address type IPv4
            },
            ipAddressInBytes,                                       // ip address
            Hex.decodeHex(portInHex)                                // port
        )));

        // then - CONNECT response
        byte[] domainInBytes = "127.0.0.1".getBytes(CharsetUtil.US_ASCII);
        String domainLengthAndBytes = Strings.padStart(BigInteger.valueOf(domainInBytes.length).toString(16), 2, '0') + new BigInteger(domainInBytes).toString(16);
        ByteBuf connectResponse = embeddedChannel.readOutbound();
        assertThat(ByteBufUtil.hexDump(connectResponse), is(
            Hex.encodeHexString(new byte[]{
                (byte) 0x05,                                        // SOCKS5
                (byte) 0x01,                                        // general failure (caused by connection failure)
                (byte) 0x00,                                        // reserved (must be 0x00)
                (byte) 0x03,                                        // address type domain
            }) +
                domainLengthAndBytes +                              // ip address
                portInHex                                           // port
        ));
        connectResponse.release();

        // then - channel is closed after error
        assertThat(embeddedChannel.isOpen(), is(false));
    }

    @Test
    public void shouldSwitchToHttp() {
        // given
        embeddedChannel = new EmbeddedChannel();
        embeddedChannel.pipeline().addLast(new MockServerUnificationInitializer(configuration(), mock(LifeCycle.class), newHttpState(), mock(HttpActionHandler.class), null));

        // and - no HTTP handlers
        assertThat(embeddedChannel.pipeline().get(HttpServerCodec.class), is(nullValue()));
        assertThat(embeddedChannel.pipeline().get(HttpContentDecompressor.class), is(nullValue()));
        assertThat(embeddedChannel.pipeline().get(HttpObjectAggregator.class), is(nullValue()));

        // when - basic HTTP request
        embeddedChannel.writeInbound(Unpooled.wrappedBuffer("GET /somePath HTTP/1.1\r\nHost: some.random.host\r\n\r\n".getBytes(UTF_8)));

        // then - should add HTTP handlers last
        if (MockServerLogger.isEnabled(TRACE)) {
            assertThat(String.valueOf(embeddedChannel.pipeline().names()), embeddedChannel.pipeline().names(), contains(
                "LoggingHandler#0",
                "PacedLargeWriteHandler#0",
                "HttpChunkLineLimiter$BeforeCodec#0",
                "HttpServerCodecResponsePairing$BeforeCodec#0",
                "HttpLineEndSplitGuard$BeforeCodec#0",
                "HttpServerCodec#0",
                "HttpLineEndSplitGuard$AfterCodec#0",
                "HttpServerCodecResponsePairing$AfterCodec#0",
                "HttpChunkLineLimiter$AfterCodec#0",
                "ProxyAuthenticationHandler#0",
                "MockServerHttpContentDecompressor#0",
                "HttpContentLengthRemover#0",
                "CoalescingHttpObjectAggregator#0",
                "CallbackWebSocketServerHandler#0",
                "DashboardWebSocketHandler#0",
                "McpStreamableHttpHandler#0",
                "MockServerHttpServerCodec#0",
                "TraceContextHandler#0",
                "HttpRequestHandler#0",
                "DefaultChannelPipeline$TailContext#0"
            ));
        } else {
            assertThat(String.valueOf(embeddedChannel.pipeline().names()), embeddedChannel.pipeline().names(), contains(
                "write-stall",
                "inbound-idle",
                "PacedLargeWriteHandler#0",
                "HttpChunkLineLimiter$BeforeCodec#0",
                "HttpServerCodecResponsePairing$BeforeCodec#0",
                "HttpLineEndSplitGuard$BeforeCodec#0",
                "HttpServerCodec#0",
                "HttpLineEndSplitGuard$AfterCodec#0",
                "HttpServerCodecResponsePairing$AfterCodec#0",
                "HttpChunkLineLimiter$AfterCodec#0",
                "HttpExchangeTracker#0",
                "Http1PipeliningGate#0",
                "ProxyAuthenticationHandler#0",
                "PreserveHeadersNettyRemoves#0",
                "MockServerHttpContentDecompressor#0",
                "HttpContentLengthRemover#0",
                "CoalescingHttpObjectAggregator#0",
                "CallbackWebSocketServerHandler#0",
                "DashboardWebSocketHandler#0",
                "McpStreamableHttpHandler#0",
                "MockServerHttpServerCodec#0",
                "TraceContextHandler#0",
                "HttpRequestHandler#0",
                "DefaultChannelPipeline$TailContext#0"
            ));
        }
    }

    @Test
    public void shouldSupportUnknownProtocol() {
        // given
        embeddedChannel = new EmbeddedChannel(new MockServerUnificationInitializer(configuration(), mock(LifeCycle.class), newHttpState(), mock(HttpActionHandler.class), null));

        // and - channel open
        assertThat(embeddedChannel.isOpen(), is(true));

        // when - basic HTTP request
        embeddedChannel.writeInbound(Unpooled.wrappedBuffer("UNKNOWN_PROTOCOL".getBytes(UTF_8)));

        // then - should add no handlers
        assertThat(embeddedChannel.pipeline().names(), contains(
            "DefaultChannelPipeline$TailContext#0"
        ));

        // and - close channel
        assertThat(embeddedChannel.isOpen(), is(false));
    }

}
