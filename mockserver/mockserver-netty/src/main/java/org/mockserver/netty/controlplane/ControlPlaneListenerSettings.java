package org.mockserver.netty.controlplane;

import com.google.common.net.InetAddresses;
import io.netty.channel.Channel;
import io.netty.handler.codec.quic.QuicChannel;
import io.netty.util.AttributeKey;
import io.netty.handler.ipfilter.IpFilterRuleType;
import io.netty.handler.ipfilter.IpSubnetFilterRule;
import org.mockserver.configuration.Configuration;
import org.mockserver.netty.proxy.relay.RelayLoopbackAddresses;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.List;

/** Immutable access policy captured before any listener binds. */
public final class ControlPlaneListenerSettings {
    public static final AttributeKey<ControlPlaneListenerSettings> KEY = AttributeKey.valueOf("MOCKSERVER_MANAGEMENT_SOURCE_POLICY");
    public final int port;
    public final InetAddress bindAddress;
    private final List<IpSubnetFilterRule> allowed;

    public ControlPlaneListenerSettings(Configuration configuration) {
        Integer port = configuration.controlPlanePort();
        if (port != null && (port < 0 || port > 65535)) {
            throw new IllegalArgumentException("controlPlanePort must be between 0 and 65535");
        }
        this.port = port == null ? -1 : port;
        this.bindAddress = port == null ? null : InetAddresses.forString(configuration.controlPlaneBindAddress());
        String cidrs = configuration.controlPlaneAllowedCidrs();
        this.allowed = parse(port != null && (cidrs == null || cidrs.isBlank()) ? "127.0.0.0/8,::1/128" : cidrs, port == null);
        if (port != null && configuration.http3Port() != null && configuration.http3Port() > 0) {
            throw new IllegalArgumentException("a separate controlPlanePort does not support HTTP/3");
        }
    }

    private static List<IpSubnetFilterRule> parse(String cidrs, boolean emptyAllowed) {
        List<IpSubnetFilterRule> result = new ArrayList<>();
        if (cidrs != null && !cidrs.isBlank()) {
            for (String cidr : cidrs.split(",", -1)) {
                String[] parts = cidr.trim().split("/", -1);
                if (parts.length != 2) {
                    throw new IllegalArgumentException("management restrictions must contain IP CIDRs: " + cidr);
                }
                InetAddress address = InetAddresses.forString(parts[0]);
                result.add(new IpSubnetFilterRule(address, Integer.parseInt(parts[1]), IpFilterRuleType.ACCEPT));
            }
        }
        if (!emptyAllowed && result.isEmpty()) {
            throw new IllegalArgumentException("controlPlaneAllowedCidrs must not be empty");
        }
        return List.copyOf(result);
    }

    public InetSocketAddress source(Channel channel) {
        InetSocketAddress socketPeer = null;
        for (Channel current = channel; current != null; current = current.parent()) {
            RelayLoopbackAddresses.SourceIdentity original = RelayLoopbackAddresses.sourceIdentityOf(current);
            if (original != null) {
                return original.address();
            }
            if (current instanceof QuicChannel quic) {
                return quic.remoteSocketAddress() instanceof InetSocketAddress peer ? peer : null;
            }
            if (current.remoteAddress() instanceof InetSocketAddress peer) {
                socketPeer = peer;
            }
        }
        return socketPeer;
    }

    public boolean allowed(InetSocketAddress address) {
        return allowed.isEmpty() || matches(allowed, address);
    }

    public boolean restrictsSources() {
        return !allowed.isEmpty();
    }

    public static ControlPlaneListenerSettings of(Channel channel) {
        for (Channel current = channel; current != null; current = current.parent()) {
            ControlPlaneListenerSettings settings = current.attr(KEY).get();
            if (settings != null) {
                return settings;
            }
        }
        return null;
    }

    private static boolean matches(List<IpSubnetFilterRule> rules, InetSocketAddress address) {
        return address != null && address.getAddress() != null && rules.stream().anyMatch(rule -> rule.matches(address));
    }
}
