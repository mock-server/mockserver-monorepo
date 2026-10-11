package org.mockserver.netty.integration.mock;

import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.handler.codec.http2.DefaultHttp2Headers;
import io.netty.handler.codec.http2.Http2Headers;
import org.apache.commons.lang3.StringUtils;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.mockserver.client.MockServerClient;
import org.mockserver.configuration.Configuration;
import org.mockserver.log.model.LogEntry;
import org.mockserver.mock.Expectation;
import org.mockserver.model.HttpRequest;
import org.mockserver.model.HttpSseResponse;
import org.mockserver.model.SseEvent;
import org.mockserver.netty.MockServer;
import org.mockserver.netty.integration.Http2TestClient;
import org.mockserver.netty.integration.NettyBufferLeaks;
import org.slf4j.event.Level;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.matchesPattern;
import static org.hamcrest.Matchers.startsWith;
import static org.mockserver.configuration.Configuration.configuration;
import static org.mockserver.model.HttpRequest.request;
import static org.mockserver.stop.Stop.stopQuietly;

/**
 * A client that leaves in the middle of a mocked server-sent-events response has only left: nothing is logged at WARN
 * or above, and the failed write is logged at DEBUG. The client takes the first event and closes its connection while
 * MockServer is still writing the second, which it cannot finish: on HTTP/2 the stream's window is smaller than the
 * event, and on HTTP/1.1 the event is larger than the socket buffers between the two. Through an HTTP/1.1 CONNECT
 * tunnel the relay may instead drain and drop the event once the client has gone, so there the write may also complete.
 */
@RunWith(Parameterized.class)
public class SseClientLeavesMidStreamIntegrationTest {

    private static final int HTTP2_STREAM_WINDOW = 16 * 1024;
    private static final int HTTP2_EVENT_BYTES = 2 * 1024 * 1024;
    // more than the send and receive buffers of every socket between MockServer and the client, through a tunnel too
    private static final int HTTP1_EVENT_BYTES = 32 * 1024 * 1024;
    private static final int READ_TIMEOUT_MILLIS = 15_000;
    private static final Pattern SECOND_EVENT_FAILED = Pattern.compile("client left before streaming chunk\\s+2\\s+was sent:\\s+\\w+(: [^\\n]+)?\\s+for request:.*", Pattern.DOTALL);

    private static InspectableMockServer server;
    private static MockServerClient client;
    private static EventLoopGroup clientGroup;
    private static int leaksBefore;

    public enum Route {
        HTTP1_DIRECT, HTTP1_CONNECT_TUNNEL, HTTP2_DIRECT, HTTP2_CONNECT_TUNNEL
    }

    @Parameterized.Parameters(name = "{0}")
    public static Object[] routes() {
        return Route.values();
    }

    @Parameterized.Parameter
    public Route route;

    @BeforeClass
    public static void startServer() {
        leaksBefore = NettyBufferLeaks.recorded();
        clientGroup = new NioEventLoopGroup(1);
        server = new InspectableMockServer(configuration().logLevel("WARN").startupWarmup(false).proxySetup(false).proxySetupLogging(false)
            .maxRequestBodySize(2 * HTTP1_EVENT_BYTES).streamIdleTimeoutSeconds(120)
            // DEBUG is on while the client leaves: entries that print the large expectation stay unprinted
            .logLevelOverrides(Map.of("EXPECTATION_MATCHED", "WARN", "EXPECTATION_NOT_MATCHED", "WARN", "EXPECTATION_RESPONSE", "WARN")));
        client = new MockServerClient("localhost", server.getLocalPort());
    }

    @AfterClass
    public static void stopServer() throws Exception {
        stopQuietly(client);
        stopQuietly(server);
        if (clientGroup != null) {
            clientGroup.shutdownGracefully(0, 1, TimeUnit.SECONDS).awaitUninterruptibly(10, TimeUnit.SECONDS);
        }
        NettyBufferLeaks.assertNoneSince(leaksBefore);
    }

    @Before
    public void resetServer() {
        server.getConfiguration().logLevel("WARN");
        client.reset();
        boolean http2 = route == Route.HTTP2_DIRECT || route == Route.HTTP2_CONNECT_TUNNEL;
        client.upsert(new Expectation(request().withPath("/sse/.*")).thenRespondWithSse(HttpSseResponse.sseResponse().withEvents(
            SseEvent.sseEvent().withData("first"),
            SseEvent.sseEvent().withData(StringUtils.repeat('x', http2 ? HTTP2_EVENT_BYTES : HTTP1_EVENT_BYTES)),
            SseEvent.sseEvent().withData("last"))));
        server.getConfiguration().logLevel("DEBUG");
    }

    @Test
    public void shouldLogNothingAtWarnForAClientThatLeavesInTheMiddleOfAnSseResponse() throws Exception {
        String path = "/sse/1";
        List<LogEntry> entries = leaveAndAwaitTheSecondEvent(path);
        assertThat("a client that leaves is not an error", warningsAndErrors(entries), is(empty()));
        List<String> left = clientLeft(entries, path);
        if (route == Route.HTTP1_CONNECT_TUNNEL) {
            // MockServer writes to the relay, which drains and drops the rest of the response once the client has gone,
            // so the second event's write either fails or completes whole, depending on which the relay does first
            List<String> secondEventFailed = left.stream().filter(message -> SECOND_EVENT_FAILED.matcher(message).matches()).collect(Collectors.toList());
            List<String> secondEventSent = secondEventSent(entries, path);
            assertThat("the write of the second event ended once, failed or whole, and is logged at DEBUG: failed " + secondEventFailed + ", sent " + secondEventSent,
                secondEventFailed.size() + secondEventSent.size(), is(1));
        } else {
            assertThat("the write of the second event failed, and is logged at DEBUG", left.size(), is(1));
            assertThat(left.get(0), matchesPattern(SECOND_EVENT_FAILED));
        }
    }

    /**
     * Has the client request the path and leave, and returns the server's log once MockServer has closed the connection
     * the client left and logged how the write of the second event ended.
     */
    private List<LogEntry> leaveAndAwaitTheSecondEvent(String path) throws Exception {
        int connectionsBefore = server.getInboundConnectionCount();
        switch (route) {
            case HTTP1_DIRECT:
                leaveHttp1(path, false);
                break;
            case HTTP1_CONNECT_TUNNEL:
                leaveHttp1(path, true);
                break;
            default:
                leaveHttp2(path, route == Route.HTTP2_CONNECT_TUNNEL);
                break;
        }
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
        while (server.getInboundConnectionCount() > connectionsBefore) {
            assertThat("MockServer closes the connection the client left", System.nanoTime() < deadline, is(true));
            Thread.sleep(10);
        }

        // awaited too: a closed connection is counted out before its pending write is failed and logged
        deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
        List<LogEntry> entries = server.logEntries();
        while (clientLeft(entries, path).isEmpty() && secondEventSent(entries, path).isEmpty() && warningsAndErrors(entries).isEmpty() && System.nanoTime() < deadline) {
            Thread.sleep(10);
            entries = server.logEntries();
        }
        return entries;
    }

    private void leaveHttp1(String path, boolean throughTunnel) throws Exception {
        Socket socket = new Socket();
        // a small window, so little of the second event can leave MockServer while it is not being read
        socket.setReceiveBufferSize(4096);
        socket.connect(new InetSocketAddress("127.0.0.1", server.getLocalPort()), READ_TIMEOUT_MILLIS);
        socket.setSoTimeout(READ_TIMEOUT_MILLIS);
        String authority = "127.0.0.1:" + server.getLocalPort();
        try {
            if (throughTunnel) {
                socket.getOutputStream().write(("CONNECT " + authority + " HTTP/1.1\r\nHost: " + authority + "\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
                socket.getOutputStream().flush();
                assertThat(readUntil(socket.getInputStream(), "\r\n\r\n"), startsWith("HTTP/1.1 200 "));
            }
            socket.getOutputStream().write(("GET " + path + " HTTP/1.1\r\nHost: " + authority + "\r\nAccept: text/event-stream\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
            socket.getOutputStream().flush();
            assertThat(readUntil(socket.getInputStream(), "data: first\n\n"), startsWith("HTTP/1.1 200 "));
            // the second event is being written: it is sent as soon as the first has been
            Thread.sleep(500);
        } finally {
            // with unread bytes, closing resets the connection
            socket.close();
        }
    }

    private void leaveHttp2(String path, boolean throughTunnel) throws Exception {
        String authority = throughTunnel ? "localhost:443" : "localhost:" + server.getLocalPort();
        try (Http2TestClient http2 = throughTunnel
            ? Http2TestClient.throughConnect(clientGroup, server.getLocalPort(), "localhost", 443, true)
            : Http2TestClient.tls(clientGroup, server.getLocalPort())) {
            http2.streamWindow(HTTP2_STREAM_WINDOW);
            Http2Headers headers = new DefaultHttp2Headers().method("GET").scheme("https").authority(authority).path(path).add("accept", "text/event-stream");
            Http2TestClient.Exchange exchange = http2.sendReadingOnlyTheResponseHeaders(headers, true);
            assertThat(exchange.status(), is(200));
            exchange.readOneFrame();
            assertThat("the first event arrives", exchange.receivedWithin("data: first\n\n", 15), is(true));
            // the second event is being written, and waits for window the client will not give
            Thread.sleep(500);
        }
    }

    private static String readUntil(InputStream input, String end) throws IOException {
        ByteArrayOutputStream read = new ByteArrayOutputStream();
        while (!read.toString(StandardCharsets.ISO_8859_1).contains(end)) {
            int next = input.read();
            if (next == -1) {
                throw new IOException("connection closed before \"" + end.trim() + "\": " + read.toString(StandardCharsets.ISO_8859_1));
            }
            read.write(next);
        }
        return read.toString(StandardCharsets.ISO_8859_1);
    }

    private static List<String> clientLeft(List<LogEntry> entries, String path) {
        return debugEntries(entries, path, "client left before streaming chunk");
    }

    private static List<String> secondEventSent(List<LogEntry> entries, String path) {
        return debugEntries(entries, path, "sent streaming chunk").stream()
            .filter(message -> message.matches("(?s)sent streaming chunk\\s+2\\s+of.*"))
            .collect(Collectors.toList());
    }

    private static List<String> debugEntries(List<LogEntry> entries, String path, String messageFormatStart) {
        return entries.stream()
            .filter(entry -> entry.getLogLevel() == Level.DEBUG && entry.getMessageFormat() != null && entry.getMessageFormat().startsWith(messageFormatStart))
            .filter(entry -> entry.getHttpRequest() instanceof HttpRequest && path.equals(((HttpRequest) entry.getHttpRequest()).getPath().getValue()))
            .map(LogEntry::getMessage)
            .collect(Collectors.toList());
    }

    /**
     * What MockServer logged at WARN or above since the last reset, for any path, other than the notice it logs when it
     * first intercepts TLS for a tunnel.
     */
    private static List<String> warningsAndErrors(List<LogEntry> entries) {
        return entries.stream()
            .filter(entry -> entry.getLogLevel() != null && entry.getLogLevel().toInt() >= Level.WARN.toInt())
            .map(LogEntry::getMessage)
            .filter(message -> !message.startsWith("Forward proxy is configured to trust ALL"))
            .collect(Collectors.toList());
    }

    /**
     * Reads the server's own event log, with each entry's level, without formatting the entries that hold the large
     * expectation.
     */
    private static final class InspectableMockServer extends MockServer {
        InspectableMockServer(Configuration configuration) {
            super(configuration, 0);
        }

        List<LogEntry> logEntries() throws Exception {
            CompletableFuture<List<LogEntry>> entries = new CompletableFuture<>();
            httpState.getMockServerLog().retrieveMessageLogEntries(null, entries::complete);
            return entries.get(READ_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS);
        }
    }
}
