package org.mockserver.netty.connection;

import io.netty.channel.Channel;
import io.netty.util.AttributeKey;

/**
 * On MockServer's end of a CONNECT/SOCKS relay loopback: the proxy client's channel of the tunnel, kept on the
 * accepted channel so it is still known after the relay's end of the loopback closes.
 */
public final class TunnelProxyClient {

    private static final AttributeKey<Channel> PROXY_CLIENT = AttributeKey.valueOf("mockserver.relayProxyClient");

    private TunnelProxyClient() {
    }

    public static void remember(Channel accepted, Channel proxyClient) {
        accepted.attr(PROXY_CLIENT).set(proxyClient);
    }

    /**
     * @return the proxy client remembered for this channel, or null; never creates the attribute
     */
    public static Channel of(Channel channel) {
        return channel.hasAttr(PROXY_CLIENT) ? channel.attr(PROXY_CLIENT).get() : null;
    }
}
