package org.mockserver.logging;

import com.google.common.annotations.VisibleForTesting;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.channel.ChannelPipeline;
import io.netty.handler.codec.http2.Http2FrameLogger;
import io.netty.util.internal.StringUtil;
import org.mockserver.configuration.Configuration;
import org.mockserver.configuration.ForwardCredentialHeadersByHost;
import org.mockserver.fixture.FixtureRedactor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nullable;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * The {@code logRawNetworkTraffic} debug log: the bytes of each connection, as MockServer reads and writes them, at
 * {@code INFO} on this class's logger. Built once per server (and once per forward client) when the property is on;
 * when it is off {@link #forConfiguration} returns null and no handler or frame logger is added anywhere.
 * <p>
 * HTTP/1.1 connections are shown as text with the values of sensitive headers masked, binary connections as a hex
 * dump, and HTTP/2 connections as decoded frames (their header blocks are compressed, so their bytes could not be
 * masked). At most {@link #MAX_BYTES_SHOWN} bytes of each message are shown.
 * <p>
 * Bodies, query strings and the hex dump (including its ASCII column) are logged raw, so they may hold secrets: this
 * is a startup-only debugging aid, not for production. Every line of an entry after its first starts with
 * {@link #CONTINUATION}, and line breaks and control bytes in the traffic are escaped, so traffic cannot forge a line.
 */
public final class RawNetworkTrafficLog {

    public static final String HANDLER_NAME = "raw-network-traffic";
    public static final int MAX_BYTES_SHOWN = 4096;
    public static final String SERVER_SIDE = "server connection";
    public static final String FORWARD_SIDE = "forward connection";
    public static final String REDACTED = FixtureRedactor.REDACTED_PLACEHOLDER;
    public static final String CONTINUATION = "\n| ";

    private static final Logger LOGGER = LoggerFactory.getLogger(RawNetworkTrafficLog.class);
    private static final List<String> EXTRA_SENSITIVE_HEADERS = List.of(
        "X-Auth-Token", "X-Access-Token", "X-Amz-Security-Token", "X-Csrf-Token", "X-Xsrf-Token", "X-Goog-Api-Key", "Ocp-Apim-Subscription-Key"
    );

    private final String side;
    private final Set<String> sensitiveHeaders = new HashSet<>();
    // read when a header is logged, so a header renamed over the control plane is masked from then on
    private final Supplier<String> additionalSensitiveHeader;
    private final BooleanSupplier enabled;
    private final Consumer<String> sink;

    /**
     * The log for one side of the server, or null when {@code logRawNetworkTraffic} is off. Called once, when the
     * server (or a forward client) is built, so the property is fixed at startup.
     */
    @Nullable
    public static RawNetworkTrafficLog forConfiguration(@Nullable Configuration configuration, String side) {
        if (configuration == null || !Boolean.TRUE.equals(configuration.logRawNetworkTraffic())) {
            return null;
        }
        RawNetworkTrafficLog log = new RawNetworkTrafficLog(side, configuration::dataPlaneApiKeyAuthenticationHeader, LOGGER::isInfoEnabled, LOGGER::info);
        log.sensitiveHeaders.addAll(ForwardCredentialHeadersByHost.headerNames(configuration.forwardCredentialHeadersByHost()));
        return log;
    }

    @VisibleForTesting
    public RawNetworkTrafficLog(String side, Supplier<String> additionalSensitiveHeader, Consumer<String> sink) {
        this(side, additionalSensitiveHeader, () -> true, sink);
    }

    private RawNetworkTrafficLog(String side, Supplier<String> additionalSensitiveHeader, BooleanSupplier enabled, Consumer<String> sink) {
        this.side = side;
        this.additionalSensitiveHeader = additionalSensitiveHeader;
        this.enabled = enabled;
        this.sink = sink;
        for (String header : FixtureRedactor.defaultSensitiveHeaders()) {
            sensitiveHeaders.add(header.toLowerCase(Locale.ROOT));
        }
        for (String header : EXTRA_SENSITIVE_HEADERS) {
            sensitiveHeaders.add(header.toLowerCase(Locale.ROOT));
        }
    }

    /**
     * Adds the byte logger last in the pipeline, once: called where a connection's protocol is decided and before its
     * codec is added, so it sits between the codec and the TLS handler (when there is one) and sees decrypted bytes.
     */
    public void addTo(ChannelPipeline pipeline, boolean binary) {
        if (pipeline.get(HANDLER_NAME) == null) {
            pipeline.addLast(HANDLER_NAME, new RawNetworkTrafficHandler(this, binary));
        }
    }

    public static void removeFrom(ChannelPipeline pipeline) {
        if (pipeline.get(HANDLER_NAME) != null) {
            pipeline.remove(HANDLER_NAME);
        }
    }

    public Http2FrameLogger http2FrameLogger() {
        return new RawHttp2FrameLogger(this);
    }

    String side() {
        return side;
    }

    boolean isEnabled() {
        return enabled.getAsBoolean();
    }

    void log(String message) {
        sink.accept(message);
    }

    /**
     * Whether a header name is, or starts with, a sensitive one: failing closed masks a malformed line such as
     * {@code Set-Cookie junk: value}, which Netty's decoder reads as {@code Set-Cookie}.
     */
    boolean isSensitiveHeader(CharSequence name) {
        String lowerCase = name.toString().toLowerCase(Locale.ROOT);
        for (String sensitive : sensitiveHeaders) {
            if (lowerCase.startsWith(sensitive)) {
                return true;
            }
        }
        String additional = additionalSensitiveHeader != null ? additionalSensitiveHeader.get() : null;
        return additional != null && !additional.isBlank() && lowerCase.startsWith(additional.trim().toLowerCase(Locale.ROOT));
    }

    /**
     * Appends at most {@link #MAX_BYTES_SHOWN} of the buffer's readable bytes as text, then how many were left out.
     * Reads by index, so the buffer's indexes and reference count are untouched.
     */
    static void appendText(ByteBuf buf, StringBuilder out) {
        int length = buf.readableBytes();
        int shown = Math.min(length, MAX_BYTES_SHOWN);
        int start = buf.readerIndex();
        for (int i = start; i < start + shown; i++) {
            appendEscaped(buf.getByte(i), out);
        }
        appendNotShown(length - shown, out);
    }

    static void appendHexDump(ByteBuf buf, StringBuilder out) {
        int length = buf.readableBytes();
        int shown = Math.min(length, MAX_BYTES_SHOWN);
        if (shown > 0) {
            out.append(CONTINUATION).append(ByteBufUtil.prettyHexDump(buf, buf.readerIndex(), shown).replace(StringUtil.NEWLINE, CONTINUATION));
        }
        appendNotShown(length - shown, out);
    }

    static void appendNotShown(int notShown, StringBuilder out) {
        if (notShown > 0) {
            out.append(CONTINUATION).append("... ").append(notShown).append(" more bytes not shown");
        }
    }

    static void appendEscaped(byte value, StringBuilder out) {
        int b = value & 0xFF;
        if (b == '\n') {
            out.append("\\n").append(CONTINUATION);
        } else if (b == '\r') {
            out.append("\\r");
        } else if (b == '\t') {
            out.append("\\t");
        } else if (b >= 0x20 && b < 0x7F) {
            out.append((char) b);
        } else {
            out.append("\\x").append(Character.forDigit(b >> 4, 16)).append(Character.forDigit(b & 0xF, 16));
        }
    }
}
