package org.mockserver.netty.proxy;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.util.NetUtil;
import io.netty.util.concurrent.ScheduledFuture;
import org.mockserver.log.model.LogEntry;
import org.mockserver.logging.MockServerLogger;
import org.slf4j.event.Level;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

import static org.mockserver.mock.action.http.HttpActionHandler.REMOTE_SOCKET;
import static org.mockserver.netty.HttpRequestHandler.PROXYING;
import static org.mockserver.netty.proxy.TransparentProxyHandler.TRANSPARENT_ORIGINAL_DST_RESOLVED;
import static org.mockserver.netty.unification.PortUnificationHandler.UNDECIDED_PROTOCOL_WAIT_MILLIS;
import static org.mockserver.socket.SocketAddresses.PROXY_PROTOCOL_SOURCE;

/**
 * Inbound handler that detects and parses PROXY protocol v1 (text format) and v2 (binary
 * format) headers prepended to TCP connections by upstream load balancers (e.g., AWS GWLB,
 * HAProxy, nginx with {@code proxy_protocol on}).
 * <p>
 * <b>Placement:</b> This handler must come before the port-unification handler, so that what
 * follows the header (HTTP, TLS, CONNECT, SOCKS or binary) is what is classified. It inspects
 * the first inbound bytes and dispatches on the first byte:
 * <ul>
 *   <li>{@code 0x0D} — candidate PROXY v2 (binary): the 12-byte v2 signature begins with
 *       {@code 0x0D}, which can never start a v1 header, an HTTP request, or a TLS record.</li>
 *   <li>{@code 'P'} — candidate PROXY v1 (text): the {@code "PROXY "} ASCII signature.</li>
 *   <li>anything else — not a PROXY header; pass through unchanged.</li>
 * </ul>
 * In all cases the handler consumes any recognised header, sets the {@code REMOTE_SOCKET} /
 * {@code PROXYING} / {@code TRANSPARENT_ORIGINAL_DST_RESOLVED} channel attributes, records the
 * header's source as {@code PROXY_PROTOCOL_SOURCE} (the client address recorded for the
 * connection's requests), and removes itself from the pipeline. Bytes that stop matching a
 * signature are passed on at once, without waiting for more; bytes that are only the start of a
 * signature are passed on unchanged once the client has sent nothing more for as long as port
 * unification waits for the start of a protocol.
 * <p>
 * <b>PROXY v1 format (HAProxy spec):</b>
 * <pre>
 * PROXY TCP4 &lt;srcIP&gt; &lt;dstIP&gt; &lt;srcPort&gt; &lt;dstPort&gt;\r\n
 * PROXY TCP6 &lt;srcIP&gt; &lt;dstIP&gt; &lt;srcPort&gt; &lt;dstPort&gt;\r\n
 * PROXY UNKNOWN\r\n
 * </pre>
 * Maximum v1 header length is 107 bytes (per the spec). The handler enforces this bound. As the spec requires, a
 * destination that is not an address of the declared family (a host name, say) makes the header malformed.
 * <p>
 * <b>PROXY v2 format (HAProxy spec):</b> a 12-byte binary signature
 * ({@code 0D 0A 0D 0A 00 0D 0A 51 55 49 54 0A}) followed by a 4-byte prefix
 * (version+command, address-family+transport, and a big-endian uint16 address-block length),
 * then the address block. For the PROXY command (LOCAL is a health-check with no address) and
 * the INET / INET6 families, the destination address and port are extracted. UNIX-socket and
 * unsupported families are consumed but leave destination resolution to downstream strategies.
 * <p>
 * <b>Fail-safe:</b> Malformed, oversized, or unrecognised headers cause the handler to
 * remove itself and pass through all bytes unchanged, logging a warning. The connection
 * continues as if no PROXY header was present.
 *
 * @see TransparentProxyHandler
 * @see CompositeOriginalDestinationResolver
 */
public class ProxyProtocolOriginalDestinationHandler extends ChannelInboundHandlerAdapter {

    /** PROXY protocol v1 ASCII signature. */
    private static final String PROXY_V1_SIGNATURE = "PROXY ";
    private static final byte[] PROXY_V1_SIGNATURE_BYTES = PROXY_V1_SIGNATURE.getBytes(StandardCharsets.US_ASCII);

    /**
     * Maximum length of a PROXY v1 header line (including CRLF).
     * Per the HAProxy PROXY protocol spec, the maximum line is 107 bytes.
     * We use 108 to be slightly generous with the bound check.
     */
    static final int MAX_PROXY_V1_LINE_LENGTH = 108;

    /** PROXY protocol v2 12-byte binary signature. */
    private static final byte[] PROXY_V2_SIGNATURE = {
        0x0D, 0x0A, 0x0D, 0x0A, 0x00, 0x0D, 0x0A, 0x51, 0x55, 0x49, 0x54, 0x0A
    };
    /** First byte of the v2 signature ({@code 0x0D}); used to dispatch v2 vs v1. */
    private static final byte PROXY_V2_SIG_0 = 0x0D;
    /** Fixed-size v2 header prefix: 12-byte signature + version/command + family/transport + uint16 length. */
    static final int V2_HEADER_PREFIX_LENGTH = 16;
    /**
     * Upper bound on the v2 address-block length we will buffer. The spec's largest defined
     * block is 216 bytes (AF_UNIX); this generous cap bounds buffering for malformed lengths.
     */
    static final int MAX_PROXY_V2_ADDR_LENGTH = 1024;

    private final MockServerLogger logger;

    /** Accumulates bytes until we can determine if a PROXY header is present. */
    private ByteBuf cumulation;
    private ScheduledFuture<?> undecidedWait;

    public ProxyProtocolOriginalDestinationHandler(MockServerLogger logger) {
        this.logger = logger;
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
        stopUndecidedWait();
        if (!(msg instanceof ByteBuf)) {
            // Non-ByteBuf message — pass through and remove self
            removeSelfAndFireRead(ctx, msg);
            return;
        }

        ByteBuf buf = (ByteBuf) msg;

        // Accumulate bytes
        if (cumulation == null) {
            cumulation = buf;
        } else {
            cumulation = ctx.alloc().compositeBuffer(2)
                .addComponent(true, cumulation)
                .addComponent(true, buf);
        }

        int readable = cumulation.readableBytes();
        if (readable < 1) {
            // Need at least one byte to dispatch
            return;
        }

        // Dispatch v2 (binary) vs v1 (text) on the first byte. The v2 signature starts with
        // 0x0D, which cannot begin a v1 header ("PROXY ..."), an HTTP request, or a TLS record.
        if (cumulation.getByte(cumulation.readerIndex()) == PROXY_V2_SIG_0) {
            handleV2(ctx);
            return;
        }

        // Check for PROXY v1 signature: bytes that stop matching it are passed on at once, as a client that
        // sent only a few (a SOCKS5 greeting is three) may be waiting for an answer before it sends more
        if (!startsWith(cumulation, PROXY_V1_SIGNATURE_BYTES)) {
            // Not a PROXY header — pass through all accumulated bytes.
            // Clear cumulation BEFORE removeSelf to prevent double-release in handlerRemoved.
            ByteBuf passThrough = cumulation;
            cumulation = null;
            removeSelfAndFireRead(ctx, passThrough);
            return;
        }

        if (readable < PROXY_V1_SIGNATURE_BYTES.length) {
            startUndecidedWait(ctx);
            return;
        }

        // We have the signature; now look for the \r\n terminator
        int crlfIndex = findCrLf(cumulation);
        if (crlfIndex < 0) {
            // No CRLF yet — check if we've exceeded the max line length
            if (readable > MAX_PROXY_V1_LINE_LENGTH) {
                logWarning("PROXY protocol v1 header exceeds maximum length ({} bytes) without CRLF terminator, passing through", readable);
                ByteBuf passThrough = cumulation;
                cumulation = null;
                removeSelfAndFireRead(ctx, passThrough);
                return;
            }
            // Need more bytes
            return;
        }

        // We have a complete PROXY header line
        int headerLength = crlfIndex + 2; // include \r\n
        byte[] headerBytes = new byte[crlfIndex];
        cumulation.getBytes(cumulation.readerIndex(), headerBytes, 0, crlfIndex);
        String headerLine = new String(headerBytes, StandardCharsets.US_ASCII);

        // Advance past the header (consume it)
        cumulation.skipBytes(headerLength);

        // Parse the header
        boolean parsed = parseAndApply(ctx, headerLine);

        if (!parsed) {
            logWarning("malformed PROXY protocol v1 header: \"{}\", passing through", headerLine);
        }

        // Pass through any remaining bytes after the header
        if (cumulation.isReadable()) {
            ByteBuf remaining = cumulation;
            cumulation = null;
            removeSelfAndFireRead(ctx, remaining);
        } else {
            cumulation.release();
            cumulation = null;
            removeSelf(ctx);
        }
    }

    /**
     * Handles a candidate PROXY v2 (binary) header. Called when the first accumulated byte is
     * {@code 0x0D}. Waits for the full signature + prefix + address block, parses the
     * destination for the PROXY command on INET / INET6 families, then consumes the header and
     * passes through any trailing bytes. Falls back to pass-through on any mismatch.
     */
    private void handleV2(ChannelHandlerContext ctx) {
        int readable = cumulation.readableBytes();
        if (!startsWith(cumulation, PROXY_V2_SIGNATURE)) {
            // First byte was 0x0D but the full v2 signature does not match — pass through
            ByteBuf passThrough = cumulation;
            cumulation = null;
            removeSelfAndFireRead(ctx, passThrough);
            return;
        }
        if (readable < PROXY_V2_SIGNATURE.length) {
            startUndecidedWait(ctx);
            return;
        }
        if (readable < V2_HEADER_PREFIX_LENGTH) {
            // Need the version/command, family/transport and length fields
            return;
        }

        int base = cumulation.readerIndex();
        int verCmd = cumulation.getByte(base + 12) & 0xFF;
        int version = (verCmd & 0xF0) >> 4;
        if (version != 2) {
            logWarning("unsupported PROXY protocol v2 version nibble ({}), passing through", version);
            ByteBuf passThrough = cumulation;
            cumulation = null;
            removeSelfAndFireRead(ctx, passThrough);
            return;
        }
        int command = verCmd & 0x0F;
        int famTrans = cumulation.getByte(base + 13) & 0xFF;
        int family = (famTrans & 0xF0) >> 4;
        int addrLen = ((cumulation.getByte(base + 14) & 0xFF) << 8) | (cumulation.getByte(base + 15) & 0xFF);

        if (addrLen > MAX_PROXY_V2_ADDR_LENGTH) {
            logWarning("PROXY protocol v2 address length ({} bytes) exceeds maximum, passing through", addrLen);
            ByteBuf passThrough = cumulation;
            cumulation = null;
            removeSelfAndFireRead(ctx, passThrough);
            return;
        }

        int total = V2_HEADER_PREFIX_LENGTH + addrLen;
        if (readable < total) {
            // Wait for the full address block
            return;
        }

        InetSocketAddress originalDst = null;
        InetSocketAddress source = null;
        // command 0x01 = PROXY (real connection); 0x00 = LOCAL (health-check, no address)
        if (command == 0x01) {
            try {
                originalDst = extractV2Address(cumulation, base + V2_HEADER_PREFIX_LENGTH, family, addrLen, false);
                source = extractV2Address(cumulation, base + V2_HEADER_PREFIX_LENGTH, family, addrLen, true);
            } catch (Exception e) {
                originalDst = null;
                source = null;
                logWarning("failed to extract destination from PROXY protocol v2 address block ({}), deferring to downstream resolution", e.getMessage());
            }
        }

        // Consume the entire v2 header (prefix + address block)
        cumulation.skipBytes(total);

        if (source != null) {
            ctx.channel().attr(PROXY_PROTOCOL_SOURCE).set(source);
        }
        if (originalDst != null) {
            applyOriginalDst(ctx, originalDst, "v2");
        } else if (logger != null && logger.isEnabledForInstance(Level.DEBUG)) {
            // LOCAL command, UNIX/unspec family, or unparseable address — defer to downstream resolution
            logger.logEvent(
                new LogEntry()
                    .setLogLevel(Level.DEBUG)
                    .setMessageFormat("transparent proxy: PROXY protocol v2 header (command {}, family {}) carried no usable destination for channel {}, deferring to downstream resolution")
                    .setArguments(command, family, ctx.channel())
            );
        }

        if (cumulation.isReadable()) {
            ByteBuf remaining = cumulation;
            cumulation = null;
            removeSelfAndFireRead(ctx, remaining);
        } else {
            cumulation.release();
            cumulation = null;
            removeSelf(ctx);
        }
    }

    /**
     * Whether the bytes received so far match the signature for as far as they go.
     */
    private static boolean startsWith(ByteBuf buf, byte[] signature) {
        int readerIndex = buf.readerIndex();
        int compared = Math.min(buf.readableBytes(), signature.length);
        for (int i = 0; i < compared; i++) {
            if (buf.getByte(readerIndex + i) != signature[i]) {
                return false;
            }
        }
        return true;
    }

    /**
     * Extracts the source or destination {@link InetSocketAddress} from a PROXY v2 address block.
     * <p>
     * INET (IPv4): {@code src(4) dst(4) sport(2) dport(2)}; INET6: {@code src(16) dst(16)
     * sport(2) dport(2)}. UNIX-socket and unspecified families return {@code null}.
     *
     * @param buf        the accumulated buffer
     * @param addrOffset absolute index of the first byte of the address block
     * @param family     the address-family nibble (1=INET, 2=INET6, 3=UNIX)
     * @param addrLen    the declared address-block length
     * @param source     the source address rather than the destination
     * @return the address, or {@code null} when none is available
     */
    private InetSocketAddress extractV2Address(ByteBuf buf, int addrOffset, int family, int addrLen, boolean source) throws Exception {
        int addressLength;
        if (family == 0x1) { // AF_INET
            addressLength = 4;
        } else if (family == 0x2) { // AF_INET6
            addressLength = 16;
        } else {
            addressLength = 0;
        }
        if (addressLength > 0) {
            if (addrLen < 2 * addressLength + 4) {
                return null;
            }
            byte[] address = new byte[addressLength];
            buf.getBytes(addrOffset + (source ? 0 : addressLength), address, 0, addressLength);
            int portOffset = addrOffset + 2 * addressLength + (source ? 0 : 2);
            int port = ((buf.getByte(portOffset) & 0xFF) << 8) | (buf.getByte(portOffset + 1) & 0xFF);
            return new InetSocketAddress(InetAddress.getByAddress(address), port);
        }
        // AF_UNIX (0x3) or AF_UNSPEC (0x0) — no IP destination
        return null;
    }

    /**
     * Parses a PROXY v1 header line and sets the channel attributes.
     * <p>
     * Format: {@code PROXY TCP4|TCP6|UNKNOWN <srcIP> <dstIP> <srcPort> <dstPort>}
     *
     * @return true if the header was successfully parsed and attributes set
     */
    private boolean parseAndApply(ChannelHandlerContext ctx, String headerLine) {
        // Split on whitespace: "PROXY", protocol, srcIP, dstIP, srcPort, dstPort
        String[] parts = headerLine.split("\\s+");

        if (parts.length < 2 || !"PROXY".equals(parts[0])) {
            return false;
        }

        String protocol = parts[1];

        // Handle UNKNOWN protocol — no address info, just mark as transparent proxy
        if ("UNKNOWN".equals(protocol)) {
            // PROXY UNKNOWN — no destination info; let downstream resolution handle it
            if (logger != null && logger.isEnabledForInstance(Level.DEBUG)) {
                logger.logEvent(
                    new LogEntry()
                        .setLogLevel(Level.DEBUG)
                        .setMessageFormat("transparent proxy: PROXY protocol UNKNOWN received for channel {}, deferring to downstream resolution")
                        .setArguments(ctx.channel())
                );
            }
            return true;
        }

        // TCP4 or TCP6 — must have 6 parts
        if (parts.length < 6) {
            return false;
        }

        if (!"TCP4".equals(protocol) && !"TCP6".equals(protocol)) {
            return false;
        }

        // the spec allows only an address of the declared family; a name would be resolved, or sent on, as given
        InetAddress dstIp = v1Address(protocol, parts[3]);
        if (dstIp == null) {
            return false;
        }
        int dstPort;
        try {
            dstPort = Integer.parseInt(parts[5]);
        } catch (NumberFormatException e) {
            return false;
        }

        if (dstPort < 0 || dstPort > 65535) {
            return false;
        }

        InetSocketAddress source = v1Source(parts[2], parts[4]);
        if (source != null) {
            ctx.channel().attr(PROXY_PROTOCOL_SOURCE).set(source);
        }
        applyOriginalDst(ctx, new InetSocketAddress(dstIp, dstPort), "v1 (" + protocol + ")");
        return true;
    }

    /**
     * The address a v1 header gives, or {@code null} unless it is an IPv4 address in dotted decimal for {@code TCP4},
     * or an IPv6 address without a zone or brackets for {@code TCP6}; an IPv4 octet with a leading zero is refused, as
     * some readers take it as octal.
     */
    private static InetAddress v1Address(String protocol, String address) {
        boolean valid = "TCP4".equals(protocol)
            ? NetUtil.isValidIpV4Address(address)
            : address.indexOf('%') < 0 && address.indexOf('[') < 0 && NetUtil.isValidIpV6Address(address);
        return valid && !hasLeadingZeroOctet(address) ? NetUtil.createInetAddressFromIpAddressString(address) : null;
    }

    private static boolean hasLeadingZeroOctet(String address) {
        if (address.indexOf('.') < 0) {
            return false;
        }
        for (int i = address.lastIndexOf(':') + 1; i < address.length() - 1; i++) {
            boolean octetStart = i == 0 || address.charAt(i - 1) == '.' || address.charAt(i - 1) == ':';
            if (octetStart && address.charAt(i) == '0' && address.charAt(i + 1) != '.') {
                return true;
            }
        }
        return false;
    }

    /**
     * The client address of a v1 header, or {@code null} when it is not an IP address and port.
     */
    private static InetSocketAddress v1Source(String ip, String port) {
        if (!NetUtil.isValidIpV4Address(ip) && !NetUtil.isValidIpV6Address(ip)) {
            return null;
        }
        try {
            int sourcePort = Integer.parseInt(port);
            return sourcePort >= 0 && sourcePort <= 65535 ? new InetSocketAddress(NetUtil.createInetAddressFromIpAddressString(ip), sourcePort) : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * Sets the resolved original-destination channel attributes and logs at DEBUG.
     */
    private void applyOriginalDst(ChannelHandlerContext ctx, InetSocketAddress originalDst, String via) {
        ctx.channel().attr(REMOTE_SOCKET).set(originalDst);
        ctx.channel().attr(PROXYING).set(Boolean.TRUE);
        ctx.channel().attr(TRANSPARENT_ORIGINAL_DST_RESOLVED).set(Boolean.TRUE);

        if (logger != null && logger.isEnabledForInstance(Level.DEBUG)) {
            logger.logEvent(
                new LogEntry()
                    .setLogLevel(Level.DEBUG)
                    .setMessageFormat("transparent proxy: resolved original destination {} for channel {} via PROXY protocol {}")
                    .setArguments(originalDst, ctx.channel(), via)
            );
        }
    }

    /**
     * Scans the buffer for {@code \r\n} starting from the current reader index.
     *
     * @return the index (relative to reader index 0) of {@code \r}, or -1 if not found
     */
    private int findCrLf(ByteBuf buf) {
        int start = buf.readerIndex();
        int end = buf.writerIndex();
        for (int i = start; i < end - 1; i++) {
            if (buf.getByte(i) == '\r' && buf.getByte(i + 1) == '\n') {
                return i - start;
            }
        }
        return -1;
    }

    private void startUndecidedWait(ChannelHandlerContext ctx) {
        undecidedWait = ctx.executor().schedule(() -> {
            undecidedWait = null;
            if (!ctx.channel().config().isAutoRead()) {
                // the rest may have been sent and be waiting unread, so the silence says nothing yet
                startUndecidedWait(ctx);
                return;
            }
            ByteBuf passThrough = cumulation;
            cumulation = null;
            removeSelfAndFireRead(ctx, passThrough);
            ctx.fireChannelReadComplete();
        }, UNDECIDED_PROTOCOL_WAIT_MILLIS, TimeUnit.MILLISECONDS);
    }

    private void stopUndecidedWait() {
        if (undecidedWait != null) {
            undecidedWait.cancel(false);
            undecidedWait = null;
        }
    }

    private void removeSelfAndFireRead(ChannelHandlerContext ctx, Object msg) {
        removeSelf(ctx);
        ctx.fireChannelRead(msg);
    }

    private void removeSelf(ChannelHandlerContext ctx) {
        if (ctx.pipeline().context(this) != null) {
            ctx.pipeline().remove(this);
        }
    }

    private void logWarning(String format, Object... args) {
        if (logger != null && logger.isEnabledForInstance(Level.WARN)) {
            logger.logEvent(
                new LogEntry()
                    .setLogLevel(Level.WARN)
                    .setMessageFormat("transparent proxy: " + format)
                    .setArguments(args)
            );
        }
    }

    @Override
    public void handlerRemoved(ChannelHandlerContext ctx) {
        stopUndecidedWait();
        // Release any accumulated bytes if the handler is removed unexpectedly
        if (cumulation != null) {
            cumulation.release();
            cumulation = null;
        }
    }
}
