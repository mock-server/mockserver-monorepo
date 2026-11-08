package org.mockserver.netty.integration.proxy.direct;

import javax.net.SocketFactory;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.Socket;
import java.net.SocketAddress;
import java.nio.charset.StandardCharsets;

/**
 * For the PostgreSQL JDBC driver's {@code socketFactory}: its connections go through a SOCKS5 or HTTP CONNECT proxy,
 * named by {@code socketFactoryArg} as {@code SOCKS:host:port} or {@code CONNECT:host:port}. The driver creates an
 * unconnected socket and connects it to the server, which here opens the tunnel to the server instead.
 */
public class TunnelSocketFactory extends SocketFactory {

    private final boolean socks;
    private final InetSocketAddress proxy;

    public TunnelSocketFactory(String arg) {
        String[] parts = arg.split(":");
        this.socks = parts[0].equals("SOCKS");
        this.proxy = new InetSocketAddress(parts[1], Integer.parseInt(parts[2]));
    }

    @Override
    public Socket createSocket() {
        return socks ? new Socket(new Proxy(Proxy.Type.SOCKS, proxy)) : new ConnectTunnelSocket(proxy);
    }

    @Override
    public Socket createSocket(String host, int port) throws IOException {
        Socket socket = createSocket();
        socket.connect(new InetSocketAddress(host, port));
        return socket;
    }

    @Override
    public Socket createSocket(String host, int port, InetAddress localHost, int localPort) throws IOException {
        return createSocket(host, port);
    }

    @Override
    public Socket createSocket(InetAddress host, int port) throws IOException {
        return createSocket(host.getHostAddress(), port);
    }

    @Override
    public Socket createSocket(InetAddress address, int port, InetAddress localAddress, int localPort) throws IOException {
        return createSocket(address.getHostAddress(), port);
    }

    private static final class ConnectTunnelSocket extends Socket {
        private final InetSocketAddress proxy;

        private ConnectTunnelSocket(InetSocketAddress proxy) {
            this.proxy = proxy;
        }

        @Override
        public void connect(SocketAddress endpoint, int timeout) throws IOException {
            InetSocketAddress server = (InetSocketAddress) endpoint;
            super.connect(proxy, timeout);
            String authority = server.getHostString() + ":" + server.getPort();
            getOutputStream().write(("CONNECT " + authority + " HTTP/1.1\r\nHost: " + authority + "\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
            getOutputStream().flush();
            String head = readHead(getInputStream());
            if (!head.startsWith("HTTP/1.1 200")) {
                throw new IOException("CONNECT refused: " + head);
            }
        }

        private static String readHead(InputStream input) throws IOException {
            ByteArrayOutputStream head = new ByteArrayOutputStream();
            for (int read; (read = input.read()) != -1; ) {
                head.write(read);
                if (head.toString(StandardCharsets.US_ASCII.name()).endsWith("\r\n\r\n")) {
                    break;
                }
            }
            return head.toString(StandardCharsets.US_ASCII.name());
        }
    }
}
