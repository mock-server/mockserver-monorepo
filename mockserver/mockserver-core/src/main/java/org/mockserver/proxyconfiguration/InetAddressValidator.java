package org.mockserver.proxyconfiguration;

import org.mockserver.configuration.Configuration;
import org.mockserver.configuration.ConfigurationProperties;
import org.mockserver.model.HttpRequest;

import javax.annotation.Nullable;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;

import static org.apache.commons.lang3.StringUtils.isBlank;

/**
 * Validates that the destination host of a forward or proxy action is not a
 * loopback, link-local, RFC 1918 private, RFC 4193 unique-local, RFC 6598
 * carrier-grade NAT, wildcard or cloud metadata address. This
 * blocks server-side request forgery (SSRF) where an attacker registers an
 * expectation that forwards through MockServer to internal infrastructure.
 * <p>
 * Validation is opt-in via {@code mockserver.forwardProxyBlockPrivateNetworks}
 * (default false) because MockServer is most commonly used to mock services
 * running on localhost, Docker bridge networks, or Kubernetes service IPs.
 * <p>
 * The same checks also apply {@code forwardProxyAllowedHosts} and {@code forwardProxyDeniedHosts}
 * (see {@link ForwardTargetHostPolicy}) to the name given and to the address it resolves to. Whenever any check
 * applies, a host read as IPv4 that is not four decimal octets without leading zeros is refused
 * ({@link HttpProxyRequestTarget#isAmbiguousIpv4}), as an upstream proxy may resolve it to another address.
 */
public final class InetAddressValidator {

    // RFC 5735 / 6890 IPv4 metadata addresses used by AWS, GCP, Azure, Oracle Cloud
    private static final String AWS_GCP_AZURE_IPV4_METADATA = "169.254.169.254";
    // RFC 6052 IPv4-mapped IPv6 for the same address
    private static final String AWS_IPV6_METADATA = "fd00:ec2::254";

    private static final ThreadLocal<HostLookup> LOOKUP_ON_THIS_THREAD = new ThreadLocal<>();

    private InetAddressValidator() {
    }

    /**
     * How a host name is looked up for the check.
     */
    @FunctionalInterface
    interface HostLookup {
        InetAddress lookup(String host) throws UnknownHostException;
    }

    /**
     * Replaces the lookup on the calling thread only, so a test can give a name different answers on successive
     * lookups; {@code null} restores {@link InetAddress#getByName}.
     */
    static void lookupOnThisThread(@Nullable HostLookup lookup) {
        if (lookup == null) {
            LOOKUP_ON_THIS_THREAD.remove();
        } else {
            LOOKUP_ON_THIS_THREAD.set(lookup);
        }
    }

    private static InetAddress lookup(String host) throws UnknownHostException {
        HostLookup lookup = LOOKUP_ON_THIS_THREAD.get();
        String name = stripBrackets(host);
        return lookup != null ? lookup.lookup(name) : InetAddress.getByName(name);
    }

    /**
     * Validate a forward target. No-op if the feature is disabled. Throws
     * IllegalArgumentException when the host is unresolvable or resolves to a
     * blocked address range.
     *
     * @param configuration MockServer configuration (may be null to fall back to global properties)
     * @param host          target host (may be a name or literal address)
     */
    public static void validateForwardTarget(Configuration configuration, String host) {
        validateForwardTarget(isEnabled(configuration), hostPolicy(configuration), host);
    }

    /**
     * As {@link #validateForwardTarget(Configuration, String)}, with the checks given rather than read from a
     * configuration, for a caller that fixes them when it starts and applies them later.
     *
     * @param blockPrivateNetworks whether forwardProxyBlockPrivateNetworks applies
     * @param hostPolicy           forwardProxyAllowedHosts and forwardProxyDeniedHosts, or null for neither
     * @param host                 target host (may be a name or literal address)
     */
    public static void validateForwardTarget(boolean blockPrivateNetworks, @Nullable ForwardTargetHostPolicy hostPolicy, String host) {
        if (!blockPrivateNetworks && hostPolicy == null) {
            return;
        }
        if (isBlank(host)) {
            return;
        }
        rejectAmbiguousIpv4(host);
        InetAddress address = null;
        if (blockPrivateNetworks || hostPolicy.matchesAddresses()) {
            try {
                address = lookup(host);
            } catch (UnknownHostException e) {
                throw new ForwardTargetBlockedException("Forward target host \"" + host + "\" could not be resolved", e);
            }
        }
        reject(blockPrivateNetworks, hostPolicy, host, address);
    }

    /**
     * Validate an <strong>already-resolved</strong> forward/relay target. Runs the
     * identical block checks as {@link #validateForwardTarget(Configuration, String)}
     * but on a concrete {@link InetAddress} rather than re-resolving a host string.
     * This lets a caller validate and then connect to the <em>same</em> resolved
     * address, closing the DNS-rebinding / TOCTOU window where a hostname could
     * resolve to a benign address for the check and an internal address for the
     * connect. No-op when the feature is disabled.
     *
     * @param configuration MockServer configuration (may be null to fall back to global properties)
     * @param address       the concrete resolved target address (null is treated as nothing to check)
     */
    public static void validateForwardTarget(Configuration configuration, InetAddress address) {
        boolean blockPrivateNetworks = isEnabled(configuration);
        ForwardTargetHostPolicy hostPolicy = hostPolicy(configuration);
        if (!blockPrivateNetworks && hostPolicy == null) {
            return;
        }
        if (address == null) {
            return;
        }
        reject(blockPrivateNetworks, hostPolicy, nameOrAddress(address), address);
    }

    /**
     * The name an address was looked up by, or its literal when it has none; {@code getHostName()} is not used
     * because it would look the address up in reverse.
     */
    private static String nameOrAddress(InetAddress address) {
        String nameAndAddress = address.toString();
        int slash = nameAndAddress.indexOf('/');
        return slash > 0 ? nameAndAddress.substring(0, slash) : address.getHostAddress();
    }

    /**
     * Validate a socket target and return the address to connect to. When the check is on a name not yet resolved
     * is resolved once, here, and the result returned, so the address checked is the address connected to: connect
     * to it without looking the name up again. The returned address still carries the name, for SNI and the
     * certificate check. When the check is off the target is returned as it was given.
     *
     * @param configuration MockServer configuration (may be null to fall back to global properties)
     * @param target        the target (null is treated as nothing to check)
     * @return the address to connect to
     */
    public static InetSocketAddress validateForwardTarget(Configuration configuration, InetSocketAddress target) {
        if (target == null) {
            return null;
        }
        boolean blockPrivateNetworks = isEnabled(configuration);
        ForwardTargetHostPolicy hostPolicy = hostPolicy(configuration);
        if (!blockPrivateNetworks && hostPolicy == null) {
            return target;
        }
        rejectAmbiguousIpv4(target.getHostString());
        if (!target.isUnresolved()) {
            reject(blockPrivateNetworks, hostPolicy, target.getHostString(), target.getAddress());
            return target;
        }
        String host = target.getHostString();
        if (!blockPrivateNetworks && !hostPolicy.matchesAddresses()) {
            reject(false, hostPolicy, host, null);
            return target;
        }
        InetAddress address;
        try {
            address = InetAddress.getByAddress(host, lookup(host).getAddress());
        } catch (UnknownHostException e) {
            throw new ForwardTargetBlockedException("Forward target host \"" + host + "\" could not be resolved", e);
        }
        reject(blockPrivateNetworks, hostPolicy, host, address);
        return new InetSocketAddress(address, target.getPort());
    }

    /**
     * Validate what a forwarded HTTP request connects or tunnels to ({@link HttpProxyRequestTarget#destinationHost})
     * and, through {@code forwardHttpProxy}, its request line's authority ({@link HttpProxyRequestTarget#of}), which is
     * refused when malformed even with no check configured, as no request line can be built for it. A request that
     * names no destination is not refused here.
     *
     * @param configuration    MockServer configuration (may be null to fall back to global properties)
     * @param request          the request as it will be sent
     * @param remoteAddress    the address it will be sent to, or null for its socket address or Host header
     * @param throughHttpProxy whether it will be sent through {@code forwardHttpProxy}
     */
    public static void validateForwardTarget(Configuration configuration, HttpRequest request, @Nullable InetSocketAddress remoteAddress, boolean throughHttpProxy) {
        if (request == null) {
            return;
        }
        HttpProxyRequestTarget.Authority proxyTarget = throughHttpProxy ? HttpProxyRequestTarget.of(request) : null;
        if (!isActive(configuration)) {
            return;
        }
        validateForwardTarget(configuration, HttpProxyRequestTarget.destinationHost(request, remoteAddress));
        if (proxyTarget != null) {
            validateForwardTarget(configuration, proxyTarget.host());
        }
    }

    /**
     * @param configuration MockServer configuration (may be null to fall back to global properties)
     * @return whether forwardProxyBlockPrivateNetworks is on
     */
    public static boolean isEnabled(@Nullable Configuration configuration) {
        return configuration != null
            ? Boolean.TRUE.equals(configuration.forwardProxyBlockPrivateNetworks())
            : ConfigurationProperties.forwardProxyBlockPrivateNetworks();
    }

    /**
     * @param configuration MockServer configuration (may be null to fall back to global properties)
     * @return whether forward targets are checked at all: forwardProxyBlockPrivateNetworks is on, or
     * forwardProxyAllowedHosts or forwardProxyDeniedHosts is set
     */
    public static boolean isActive(@Nullable Configuration configuration) {
        return isEnabled(configuration) || hostPolicy(configuration) != null;
    }

    /**
     * @param configuration MockServer configuration (may be null to fall back to global properties)
     * @return forwardProxyAllowedHosts and forwardProxyDeniedHosts parsed, or null when neither is set
     */
    @Nullable
    public static ForwardTargetHostPolicy hostPolicy(@Nullable Configuration configuration) {
        if (configuration != null) {
            return configuration.forwardTargetHostPolicy(configuration.forwardProxyAllowedHosts(), configuration.forwardProxyDeniedHosts());
        }
        return ForwardTargetHostPolicy.reuseOrParse(null, ConfigurationProperties.forwardProxyAllowedHosts(), ConfigurationProperties.forwardProxyDeniedHosts());
    }

    private static void reject(boolean blockPrivateNetworks, @Nullable ForwardTargetHostPolicy hostPolicy, String requestedHost, @Nullable InetAddress address) {
        if (hostPolicy != null) {
            hostPolicy.check(requestedHost, address);
        }
        if (blockPrivateNetworks) {
            rejectIfBlocked(requestedHost, address);
        }
    }

    private static void rejectAmbiguousIpv4(String host) {
        if (HttpProxyRequestTarget.isAmbiguousIpv4(host)) {
            throw new ForwardTargetBlockedException("Forward target \"" + host + "\" refused: an IPv4 address is accepted only as four decimal octets without leading zeros, as an upstream proxy may read another form as a different address");
        }
    }

    private static void rejectIfBlocked(String requestedHost, InetAddress requestedAddress) {
        String ip = requestedAddress.getHostAddress();
        rejectIfBlocked(requestedHost, ip, embeddedIpv4(requestedAddress));
        for (byte[] possible : EmbeddedIpv4.possible(requestedAddress.getAddress())) {
            rejectIfBlocked(requestedHost, ip, ipv4(possible, requestedAddress));
        }
    }

    private static void rejectIfBlocked(String requestedHost, String ip, InetAddress address) {
        if (AWS_GCP_AZURE_IPV4_METADATA.equals(address.getHostAddress()) || AWS_IPV6_METADATA.equalsIgnoreCase(ip)) {
            throw new ForwardTargetBlockedException(
                "Forward to cloud metadata endpoint blocked: " + requestedHost
                    + " (set mockserver.forwardProxyBlockPrivateNetworks=false to allow)");
        }
        if (address.isLoopbackAddress()) {
            throw new ForwardTargetBlockedException(
                "Forward to loopback address blocked: " + requestedHost
                    + " (set mockserver.forwardProxyBlockPrivateNetworks=false to allow)");
        }
        if (address.isLinkLocalAddress()) {
            throw new ForwardTargetBlockedException(
                "Forward to link-local address blocked: " + requestedHost
                    + " (set mockserver.forwardProxyBlockPrivateNetworks=false to allow)");
        }
        if (address.isSiteLocalAddress() || isIpv6UniqueLocal(address)) {
            // Java's isSiteLocalAddress only covers RFC 1918 IPv4 and deprecated fec0::/10 IPv6.
            // Cover the RFC 4193 unique-local IPv6 range (fc00::/7) explicitly so Docker / Kubernetes
            // / Tailscale ULA addresses can't bypass the SSRF policy on IPv6-enabled hosts.
            throw new ForwardTargetBlockedException(
                "Forward to private network blocked: " + requestedHost
                    + " (set mockserver.forwardProxyBlockPrivateNetworks=false to allow)");
        }
        if (isCarrierGradeNat(address)) {
            throw new ForwardTargetBlockedException(
                "Forward to carrier-grade NAT address blocked: " + requestedHost
                    + " (set mockserver.forwardProxyBlockPrivateNetworks=false to allow)");
        }
        if (address.isAnyLocalAddress() || isThisNetwork(address)) {
            throw new ForwardTargetBlockedException(
                "Forward to wildcard address blocked: " + requestedHost
                    + " (set mockserver.forwardProxyBlockPrivateNetworks=false to allow)");
        }
    }

    /**
     * RFC 1122 "this network", {@code 0.0.0.0/8}, which some stacks connect to as this host.
     */
    private static boolean isThisNetwork(InetAddress address) {
        byte[] bytes = address.getAddress();
        return bytes.length == 4 && bytes[0] == 0;
    }

    /**
     * RFC 6598 shared address space, {@code 100.64.0.0/10}, used by carrier-grade NAT and by overlay networks such as
     * Tailscale. An IPv4-mapped IPv6 address is already an {@link java.net.Inet4Address} here: the JDK converts it.
     */
    private static boolean isCarrierGradeNat(InetAddress address) {
        byte[] bytes = address.getAddress();
        return bytes.length == 4 && (bytes[0] & 0xFF) == 100 && (bytes[1] & 0xC0) == 64;
    }

    /**
     * The IPv4 address an IPv6 address carries ({@link EmbeddedIpv4#of}: IPv4-compatible, IPv4-translated or NAT64),
     * which a connection to it may reach; otherwise the address itself. IPv4-mapped addresses are already IPv4 here.
     */
    private static InetAddress embeddedIpv4(InetAddress address) {
        byte[] ipv4 = EmbeddedIpv4.of(address.getAddress(), true);
        return ipv4 != null ? ipv4(ipv4, address) : address;
    }

    private static InetAddress ipv4(byte[] ipv4, InetAddress fallback) {
        try {
            return InetAddress.getByAddress(ipv4);
        } catch (UnknownHostException impossible) {
            return fallback;
        }
    }

    private static boolean isIpv6UniqueLocal(InetAddress address) {
        byte[] bytes = address.getAddress();
        return bytes.length == 16 && (bytes[0] & 0xFE) == 0xFC;
    }

    private static String stripBrackets(String host) {
        if (host.length() >= 2 && host.charAt(0) == '[' && host.charAt(host.length() - 1) == ']') {
            return host.substring(1, host.length() - 1);
        }
        return host;
    }
}
