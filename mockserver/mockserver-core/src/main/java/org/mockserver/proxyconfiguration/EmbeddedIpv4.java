package org.mockserver.proxyconfiguration;

import javax.annotation.Nullable;
import java.util.Arrays;

/**
 * The IPv4 address an IPv6 address carries, which a connection to it may reach, so the forward-target checks
 * ({@link InetAddressValidator}, {@link ForwardTargetHostPolicy}) read it as that IPv4 address.
 */
final class EmbeddedIpv4 {

    private static final byte[][] NONE = new byte[0][];

    private EmbeddedIpv4() {
    }

    /**
     * Reads the IPv4 address of an IPv4-mapped ({@code ::ffff:a.b.c.d}) or IPv4-compatible ({@code ::a.b.c.d}, but not
     * {@code ::} or {@code ::1}) address, and with {@code translated}, also of an IPv4-translated
     * ({@code ::ffff:0:a.b.c.d}, RFC 2765) or well-known NAT64 ({@code 64:ff9b::a.b.c.d}, RFC 6052) one.
     *
     * @return the four bytes of that IPv4 address, or null when {@code address} carries none
     */
    @Nullable
    static byte[] of(@Nullable byte[] address, boolean translated) {
        if (address == null || address.length != 16) {
            return null;
        }
        if (zero(address, 0, 8)) {
            boolean mapped = zero(address, 8, 10) && address[10] == (byte) 0xFF && address[11] == (byte) 0xFF;
            boolean siit = translated && address[8] == (byte) 0xFF && address[9] == (byte) 0xFF && zero(address, 10, 12);
            boolean compatible = zero(address, 8, 12) && !(zero(address, 12, 15) && (address[15] & 0xFE) == 0);
            return mapped || siit || compatible ? lastFour(address) : null;
        }
        return translated && isNat64(address, 0) && zero(address, 4, 12) ? lastFour(address) : null;
    }

    /**
     * The IPv4 addresses an address may lead to without standing for one of them, so a deny list or
     * forwardProxyBlockPrivateNetworks refuses it when any is refused, while none satisfies an allow list: each RFC 6052
     * placement (/48, /56, /64, /96) within the local-use NAT64 prefix {@code 64:ff9b:1::/48} (RFC 8215), which an
     * operator may subdivide, and the IPv4 address of a 6to4 address ({@code 2002::/16}, RFC 3056). A reading in
     * {@code 0.0.0.0/8} is skipped: the zero bits around another placement produce one, and no translator goes there.
     */
    static byte[][] possible(@Nullable byte[] address) {
        if (address == null || address.length != 16) {
            return NONE;
        }
        if (address[0] == 0x20 && address[1] == 0x02) {
            return routable(new byte[]{address[2], address[3], address[4], address[5]});
        }
        if (!isNat64(address, 1)) {
            return NONE;
        }
        return routable(
            new byte[]{address[6], address[7], address[9], address[10]},
            new byte[]{address[7], address[9], address[10], address[11]},
            new byte[]{address[9], address[10], address[11], address[12]},
            lastFour(address)
        );
    }

    private static byte[][] routable(byte[]... readings) {
        int count = 0;
        for (byte[] reading : readings) {
            if (reading[0] != 0) {
                readings[count++] = reading;
            }
        }
        return count == readings.length ? readings : Arrays.copyOf(readings, count);
    }

    /**
     * Whether the address is in {@code 64:ff9b::/48} (well-known, {@code localUse} 0) or {@code 64:ff9b:1::/48}.
     */
    private static boolean isNat64(byte[] address, int localUse) {
        return address[0] == 0 && address[1] == 0x64 && address[2] == (byte) 0xFF && address[3] == (byte) 0x9B
            && address[4] == 0 && address[5] == localUse;
    }

    private static byte[] lastFour(byte[] address) {
        return new byte[]{address[12], address[13], address[14], address[15]};
    }

    private static boolean zero(byte[] address, int from, int to) {
        for (int i = from; i < to; i++) {
            if (address[i] != 0) {
                return false;
            }
        }
        return true;
    }
}
