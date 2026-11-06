package org.mockserver.netty.integration.proxy.http;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

/**
 * A test upstream proxy that records each destination it is asked for. {@code CONNECT} and SOCKS5 (without
 * authentication) connect every destination to the same port on this machine, whatever its name; HTTP answers every
 * request itself, with {@code 200} and the body {@link #HTTP_PROXY_BODY}, and records its request target. A
 * {@code CONNECT} proxy given credentials refuses, with {@code 407}, a {@code CONNECT} that does not carry them.
 */
public final class RecordingUpstreamProxy implements AutoCloseable {

    static final String HTTP_PROXY_BODY = "upstream proxy";

    public enum Protocol {HTTP, CONNECT, SOCKS5}

    private final Protocol protocol;
    private final long timeoutSeconds;
    private final ServerSocket listener;
    private final List<Socket> sockets = new CopyOnWriteArrayList<>();
    private final List<String> destinations = new CopyOnWriteArrayList<>();
    private final List<String> refused = new CopyOnWriteArrayList<>();
    private volatile String requiredProxyAuthorization;

    public RecordingUpstreamProxy(Protocol protocol, long timeoutSeconds) throws IOException {
        this(protocol, timeoutSeconds, new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0));
    }

    /** Listens on {@code bindAddress}: another loopback address, say, on the port another proxy has. */
    public RecordingUpstreamProxy(Protocol protocol, long timeoutSeconds, InetSocketAddress bindAddress) throws IOException {
        this.protocol = protocol;
        this.timeoutSeconds = timeoutSeconds;
        this.listener = new ServerSocket();
        try {
            listener.bind(bindAddress, 50);
        } catch (IOException bindFailed) {
            listener.close();
            throw bindFailed;
        }
        daemon(() -> {
            while (!listener.isClosed()) {
                Socket client = listener.accept();
                sockets.add(client);
                daemon(() -> serve(client));
            }
        });
    }

    public InetSocketAddress address() {
        return new InetSocketAddress(listener.getInetAddress(), listener.getLocalPort());
    }

    /** {@code CONNECT} only: from now on a tunnel is opened only for a request with these Basic credentials. */
    public RecordingUpstreamProxy requiringCredentials(String username, String password) {
        requiredProxyAuthorization = "Basic " + Base64.getEncoder().encodeToString((username + ":" + password).getBytes(StandardCharsets.UTF_8));
        return this;
    }

    /**
     * {@code CONNECT}: the authority; SOCKS5: the address type ({@code domain}, {@code ipv4} or {@code ipv6}), the
     * address and the port; HTTP: the request target.
     */
    public List<String> destinations() {
        return destinations;
    }

    /** {@code CONNECT} only: the authority of each request refused for its credentials. */
    public List<String> refused() {
        return refused;
    }

    private void serve(Socket client) throws IOException {
        client.setSoTimeout((int) TimeUnit.SECONDS.toMillis(timeoutSeconds));
        if (protocol == Protocol.HTTP) {
            String head = readHead(client);
            destinations.add(head.split(" ")[1]);
            write(client, ("HTTP/1.1 200 OK\r\nContent-Length: " + HTTP_PROXY_BODY.length() + "\r\nConnection: close\r\n\r\n" + HTTP_PROXY_BODY).getBytes(StandardCharsets.ISO_8859_1));
            client.close();
            return;
        }
        int port = protocol == Protocol.CONNECT ? readConnect(client) : readSocks5(client);
        if (port < 0) {
            write(client, "HTTP/1.1 407 Proxy Authentication Required\r\nProxy-Authenticate: Basic realm=\"proxy\"\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".getBytes(StandardCharsets.ISO_8859_1));
            client.close();
            return;
        }
        Socket upstream = new Socket("127.0.0.1", port);
        sockets.add(upstream);
        if (protocol == Protocol.CONNECT) {
            write(client, "HTTP/1.1 200 Connection established\r\n\r\n".getBytes(StandardCharsets.ISO_8859_1));
        } else {
            write(client, new byte[]{5, 0, 0, 1, 0, 0, 0, 0, 0, 0});
        }
        daemon(() -> copy(upstream, client));
        copy(client, upstream);
    }

    private static String readHead(Socket client) throws IOException {
        StringBuilder head = new StringBuilder();
        InputStream fromClient = client.getInputStream();
        while (head.indexOf("\r\n\r\n") < 0) {
            int read = fromClient.read();
            if (read == -1) {
                throw new IOException("closed before the request head ended");
            }
            head.append((char) read);
        }
        return head.toString();
    }

    /** @return the port asked for, or -1 for a request refused for its credentials */
    private int readConnect(Socket client) throws IOException {
        String head = readHead(client);
        String authority = head.substring("CONNECT ".length(), head.indexOf(" HTTP/1.1"));
        String required = requiredProxyAuthorization;
        if (required != null && !head.contains("\r\nproxy-authorization: " + required + "\r\n") && !head.contains("\r\nProxy-Authorization: " + required + "\r\n")) {
            refused.add(authority);
            return -1;
        }
        destinations.add(authority);
        return Integer.parseInt(authority.substring(authority.lastIndexOf(':') + 1));
    }

    private int readSocks5(Socket client) throws IOException {
        DataInputStream fromClient = new DataInputStream(client.getInputStream());
        // greeting: version, method count, methods; answer "no authentication"
        fromClient.readUnsignedByte();
        fromClient.readFully(new byte[fromClient.readUnsignedByte()]);
        write(client, new byte[]{5, 0});
        // request: version, command, reserved, address type, address, port
        fromClient.readUnsignedByte();
        fromClient.readUnsignedByte();
        fromClient.readUnsignedByte();
        int addressType = fromClient.readUnsignedByte();
        String destination;
        if (addressType == 3) {
            byte[] name = new byte[fromClient.readUnsignedByte()];
            fromClient.readFully(name);
            destination = "domain " + new String(name, StandardCharsets.US_ASCII);
        } else {
            byte[] address = new byte[addressType == 1 ? 4 : 16];
            fromClient.readFully(address);
            destination = (addressType == 1 ? "ipv4 " : "ipv6 ") + InetAddress.getByAddress(address).getHostAddress();
        }
        int port = fromClient.readUnsignedShort();
        destinations.add(destination + ":" + port);
        return port;
    }

    @Override
    public void close() throws IOException {
        listener.close();
        for (Socket socket : sockets) {
            socket.close();
        }
    }

    static void write(Socket socket, byte[] bytes) throws IOException {
        socket.getOutputStream().write(bytes);
        socket.getOutputStream().flush();
    }

    static void copy(Socket from, Socket to) throws IOException {
        try {
            byte[] buffer = new byte[16 * 1024];
            InputStream input = from.getInputStream();
            OutputStream output = to.getOutputStream();
            for (int read = input.read(buffer); read != -1; read = input.read(buffer)) {
                output.write(buffer, 0, read);
                output.flush();
            }
        } finally {
            from.close();
            to.close();
        }
    }

    static void daemon(IoTask task) {
        Thread thread = new Thread(() -> {
            try {
                task.run();
            } catch (IOException closed) {
                // the listener or a connection was closed
            }
        }, "upstream-proxy");
        thread.setDaemon(true);
        thread.start();
    }

    interface IoTask {
        void run() throws IOException;
    }
}
