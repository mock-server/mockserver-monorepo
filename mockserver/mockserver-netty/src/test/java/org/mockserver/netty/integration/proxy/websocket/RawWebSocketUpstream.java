package org.mockserver.netty.integration.proxy.websocket;

import io.netty.handler.ssl.JdkSslContext;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.handler.ssl.SslProvider;
import io.netty.handler.ssl.util.SelfSignedCertificate;

import javax.net.ServerSocketFactory;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.mockserver.netty.integration.proxy.websocket.RawWebSocket.*;

/**
 * An upstream WebSocket server on blocking sockets that records exactly what it receives: each handshake's request
 * head, and each frame as sent (fin, opcode, payload). It echoes data frames unchanged (same opcode, same fin), answers
 * a ping with a pong and a close with the same close, and on the text {@code close-me} starts a close of its own
 * ({@code 4002 "server bye"}); on {@code ping-me} it sends a ping ({@code "srv"}). It selects the configured
 * subprotocol when the client offers it, and otherwise none.
 */
public final class RawWebSocketUpstream implements AutoCloseable {

    public final BlockingQueue<Map<String, List<String>>> handshakes = new LinkedBlockingQueue<>();
    // the frames of the connection accepted last, so a test never reads the end of an earlier test's connection
    private volatile BlockingQueue<Frame> frames = new LinkedBlockingQueue<>();
    private volatile CountDownLatch ended = new CountDownLatch(1);
    private final List<Socket> sockets = new CopyOnWriteArrayList<>();
    private final ServerSocket serverSocket;
    private final SelfSignedCertificate certificate;
    private volatile String subprotocol;
    private volatile boolean selectEvenIfNotOffered;

    public RawWebSocketUpstream(boolean tls) throws Exception {
        ServerSocketFactory factory;
        if (tls) {
            certificate = new SelfSignedCertificate();
            JdkSslContext sslContext = (JdkSslContext) SslContextBuilder.forServer(certificate.certificate(), certificate.privateKey())
                .sslProvider(SslProvider.JDK).build();
            factory = sslContext.context().getServerSocketFactory();
        } else {
            certificate = null;
            factory = ServerSocketFactory.getDefault();
        }
        serverSocket = factory.createServerSocket();
        serverSocket.bind(new InetSocketAddress(InetAddress.getByAddress(new byte[]{127, 0, 0, 1}), 0));
        Thread acceptor = new Thread(this::acceptLoop, "raw-ws-upstream-" + serverSocket.getLocalPort());
        acceptor.setDaemon(true);
        acceptor.start();
    }

    public RawWebSocketUpstream subprotocol(String subprotocol) {
        this.subprotocol = subprotocol;
        this.selectEvenIfNotOffered = false;
        return this;
    }

    /** Answers every handshake with this subprotocol, offered or not (which a client must refuse). */
    public RawWebSocketUpstream subprotocolEvenIfNotOffered(String subprotocol) {
        this.subprotocol = subprotocol;
        this.selectEvenIfNotOffered = true;
        return this;
    }

    public int port() {
        return serverSocket.getLocalPort();
    }

    public String authority() {
        return "127.0.0.1:" + port();
    }

    public Map<String, List<String>> awaitHandshake() throws InterruptedException {
        Map<String, List<String>> handshake = handshakes.poll(15, TimeUnit.SECONDS);
        if (handshake == null) {
            throw new AssertionError("the upstream received no handshake within 15 seconds");
        }
        return handshake;
    }

    /** Forgets earlier connections: what they still receive is not seen by {@link #frames()} or {@link #awaitFrame()}. */
    public void forget() {
        handshakes.clear();
        frames = new LinkedBlockingQueue<>();
        ended = new CountDownLatch(1);
    }

    /** Whether the connection accepted last ended (MockServer closed it, or it was closed here) within the time given. */
    public boolean awaitConnectionEnded(long seconds) throws InterruptedException {
        return ended.await(seconds, TimeUnit.SECONDS);
    }

    /** The frames the connection accepted last has received. */
    public BlockingQueue<Frame> frames() {
        return frames;
    }

    public Frame awaitFrame() throws InterruptedException {
        Frame frame = frames.poll(15, TimeUnit.SECONDS);
        if (frame == null) {
            throw new AssertionError("the upstream received no frame within 15 seconds");
        }
        return frame;
    }

    private void acceptLoop() {
        while (!serverSocket.isClosed()) {
            try {
                Socket socket = serverSocket.accept();
                sockets.add(socket);
                Thread connection = new Thread(() -> serve(socket), "raw-ws-upstream-connection");
                connection.setDaemon(true);
                connection.start();
            } catch (IOException closed) {
                return;
            }
        }
    }

    private void serve(Socket socket) {
        try {
            socket.setSoTimeout(30_000);
            String head = readHead(socket.getInputStream());
            Map<String, List<String>> headers = parseHeaders(head);
            BlockingQueue<Frame> received = new LinkedBlockingQueue<>();
            CountDownLatch connectionEnded = new CountDownLatch(1);
            frames = received;
            ended = connectionEnded;
            serveWebSocket(socket, headers, received, connectionEnded);
        } catch (Exception e) {
            // the connection ended
        }
    }

    private void serveWebSocket(Socket socket, Map<String, List<String>> headers, BlockingQueue<Frame> received, CountDownLatch connectionEnded) {
        try {
            handshakes.add(headers);
            String key = headers.get("sec-websocket-key").get(0);
            StringBuilder response = new StringBuilder("HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Accept: ")
                .append(acceptFor(key)).append("\r\n");
            String selected = subprotocol;
            if (selected != null && (selectEvenIfNotOffered || offered(headers, selected))) {
                response.append("Sec-WebSocket-Protocol: ").append(selected).append("\r\n");
            }
            response.append("\r\n");
            write(socket, response.toString());
            RawWebSocket peer = new RawWebSocket(socket, false);
            boolean closeSent = false;
            Frame frame;
            while ((frame = peer.read()) != null) {
                received.add(frame);
                switch (frame.opcode()) {
                    case CLOSE -> {
                        if (!closeSent) {
                            peer.send(true, CLOSE, frame.payload());
                        }
                        socket.close();
                        return;
                    }
                    case PING -> peer.send(true, PONG, frame.payload());
                    case PONG -> {
                        // recorded only
                    }
                    default -> {
                        if (frame.opcode() == TEXT && frame.fin() && "close-me".equals(frame.text())) {
                            closeSent = true;
                            peer.sendClose(4002, "server bye");
                        } else if (frame.opcode() == TEXT && frame.fin() && "ping-me".equals(frame.text())) {
                            peer.send(true, PING, "srv".getBytes(StandardCharsets.UTF_8));
                        } else {
                            peer.send(frame.fin(), frame.opcode(), frame.payload());
                        }
                    }
                }
            }
        } catch (Exception e) {
            // the connection ended
        } finally {
            connectionEnded.countDown();
        }
    }

    private static boolean offered(Map<String, List<String>> headers, String subprotocol) {
        List<String> values = headers.get("sec-websocket-protocol");
        if (values == null) {
            return false;
        }
        for (String value : values) {
            for (String token : value.split(",")) {
                if (token.trim().equals(subprotocol)) {
                    return true;
                }
            }
        }
        return false;
    }

    @Override
    public void close() throws IOException {
        serverSocket.close();
        for (Socket socket : sockets) {
            socket.close();
        }
        if (certificate != null) {
            certificate.delete();
        }
    }
}
