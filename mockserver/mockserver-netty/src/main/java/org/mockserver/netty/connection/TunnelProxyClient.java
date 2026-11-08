package org.mockserver.netty.connection;

import io.netty.channel.Channel;
import io.netty.util.AttributeKey;

/**
 * On MockServer's end of a CONNECT/SOCKS relay loopback: the proxy client's channel of the tunnel, kept on the
 * accepted channel so it is still known after the relay's end of the loopback closes.
 */
public final class TunnelProxyClient {

    private static final AttributeKey<Channel> PROXY_CLIENT = AttributeKey.valueOf("mockserver.relayProxyClient");
    private static final AttributeKey<Channel> ACCEPTED_LOOPBACK = AttributeKey.valueOf("mockserver.relayAcceptedLoopback");
    private static final AttributeKey<Boolean> CARRIES_BINARY = AttributeKey.valueOf("mockserver.relayCarriesBinary");

    /**
     * Fired on MockServer's end of a tunnel's loopback when the tunnel's client has sent nothing for
     * {@code forwardBinaryServerFirstWaitMillis}: its destination may be a server that speaks first.
     */
    public enum ServerSpeaksFirst {
        INSTANCE
    }

    private TunnelProxyClient() {
    }

    public static void remember(Channel accepted, Channel proxyClient) {
        accepted.attr(PROXY_CLIENT).set(proxyClient);
        proxyClient.attr(ACCEPTED_LOOPBACK).set(accepted);
    }

    /**
     * @return MockServer's end of the loopback of the tunnel whose client this is, or null before it has been accepted
     */
    public static Channel acceptedLoopbackOf(Channel proxyClient) {
        return proxyClient.hasAttr(ACCEPTED_LOOPBACK) ? proxyClient.attr(ACCEPTED_LOOPBACK).get() : null;
    }

    /**
     * Marks a tunnel as carrying a binary protocol; must be called before its first bytes are relayed.
     */
    public static void markCarriesBinary(Channel proxyClient) {
        proxyClient.attr(CARRIES_BINARY).set(Boolean.TRUE);
    }

    /**
     * @return whether this is MockServer's end of the loopback of a tunnel carrying a binary protocol, whose bytes are
     * then taken as binary without being looked at again: binary that starts like SOCKS, HTTP or a PROXIED_ preamble
     * stays binary
     */
    public static boolean carriesBinary(Channel accepted) {
        Channel proxyClient = of(accepted);
        return proxyClient != null && proxyClient.hasAttr(CARRIES_BINARY) && Boolean.TRUE.equals(proxyClient.attr(CARRIES_BINARY).get());
    }

    /**
     * @return the proxy client remembered for this channel, or null; never creates the attribute
     */
    public static Channel of(Channel channel) {
        return channel.hasAttr(PROXY_CLIENT) ? channel.attr(PROXY_CLIENT).get() : null;
    }
}
