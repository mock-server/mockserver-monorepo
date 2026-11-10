package org.mockserver.netty.unification;

import io.netty.handler.ssl.OpenSsl;
import io.netty.handler.ssl.SslProvider;
import org.junit.BeforeClass;
import org.junit.Test;
import org.mockserver.netty.unification.Http2ConnectionMemoryHarness.Frame;
import org.mockserver.netty.unification.Http2ConnectionMemoryHarness.Result;
import org.mockserver.netty.unification.Http2ConnectionMemoryHarness.Variant;

import java.util.ArrayList;
import java.util.List;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.lessThanOrEqualTo;
import static org.mockserver.netty.unification.Http2ConnectionMemoryHarness.oneStream;
import static org.mockserver.netty.unification.Http2ConnectionMemoryHarness.pinning;
import static org.mockserver.netty.unification.Http2ConnectionMemoryHarness.trackedOverTls;

/**
 * What an HTTP/2 upload holds over TLS, through the server's real {@code SslHandler}, connection codec and stream
 * chain. A DATA frame is then a retained slice of the buffer {@code SslHandler} decrypted it into, not of the read:
 * with OpenSSL (production's provider when its native library loads) buffers of at most 16 KiB, each holding as many
 * whole records of a read as fit; with the JDK's engine one per record. A held frame pins that whole buffer under a
 * non-copying aggregator, and the stream aggregator's copy of the pieces of mostly unused buffers bounds it as it
 * bounds reads in cleartext ({@link Http2StreamPeakHeldTest}). Each shape runs with the client flushing every frame
 * into records of its own and with its frames coalesced into 16 KiB records.
 */
public class Http2StreamPeakHeldOverTlsTest {

    private static final int KIB = 1024;
    private static final int MIB = 1024 * 1024;
    private static final int DEFAULT_WINDOW = 65_535;
    /**
     * As in cleartext: the read a stream is on, a part-used block, the read being delivered and the filler request.
     */
    private static final long SLACK = 64 * KIB + 16 * KIB + 2 * 64 * KIB;
    private static final long HANG_GUARD_MILLIS = 900_000;

    @BeforeClass
    public static void openSslLoads() {
        // production's server provider when available; this module depends on netty-tcnative-boringssl-static
        assertThat("OpenSSL unavailable: " + OpenSsl.unavailabilityCause(), OpenSsl.isAvailable(), is(true));
    }

    @Test(timeout = HANG_GUARD_MILLIS)
    public void shouldNotPinDecryptedBuffersWhenAStreamKeepsOneFramePerRead() {
        int cycles = 300;
        for (Mode mode : Mode.values()) {
            for (int pieceBytes : new int[]{1, 1023, 4097}) {
                List<Frame> frames = pinning(cycles, pieceBytes);
                Result production = trackedOverTls(Variant.PRODUCTION, mode.provider, mode.coalesce, 64 * KIB, frames, cycles + 1);
                Result netty = trackedOverTls(Variant.NETTY, mode.provider, mode.coalesce, 64 * KIB, frames, cycles + 1);
                assertHeldBounded(mode + ", one " + pieceBytes + " B frame per read", mode, production, netty, (long) cycles * pieceBytes, 1);
            }
        }
    }

    @Test(timeout = HANG_GUARD_MILLIS)
    public void shouldNotPinDecryptedBuffersAcrossManyStreamsEachKeepingOneFramePerRead() {
        int streams = 20;
        int cycles = 50;
        int pieceBytes = 4097;
        List<Frame> frames = new ArrayList<>();
        int filler = streams;
        for (int cycle = 0; cycle < cycles; cycle++) {
            for (int stream = 0; stream < streams; stream++) {
                frames.add(new Frame(stream, pieceBytes));
                if (cycle > 0) {
                    frames.add(new Frame(filler++, DEFAULT_WINDOW / 2 - pieceBytes - 128));
                }
                frames.add(Frame.DELIVER_NOW);
            }
        }
        for (Mode mode : Mode.values()) {
            Result production = trackedOverTls(Variant.PRODUCTION, mode.provider, mode.coalesce, 64 * KIB, frames, filler);
            Result netty = trackedOverTls(Variant.NETTY, mode.provider, mode.coalesce, 64 * KIB, frames, filler);
            assertHeldBounded(mode + ", " + streams + " streams of " + pieceBytes + " B frames", mode, production, netty, (long) streams * cycles * pieceBytes, streams);
        }
    }

    @Test(timeout = HANG_GUARD_MILLIS)
    public void shouldHoldAnUploadInSixteenKibFramesWithoutCopying() {
        // a 16 KiB frame and its header do not fit one record, so the frame decoder joins every frame, as it joins a
        // frame cut by a read in cleartext
        for (Mode mode : Mode.values()) {
            Result production = trackedOverTls(Variant.PRODUCTION, mode.provider, mode.coalesce, 64 * KIB, oneStream(MIB, 16 * KIB), 1);
            String description = mode + ": held " + production.peakHeldBytes + " for a body of " + production.bodyBytes;
            assertThat(description, production.aggregatorAllocatedBytes, is(0L));
            assertThat(description, production.peakHeldBytes, lessThanOrEqualTo(2 * production.bodyBytes + SLACK));
        }
    }

    /**
     * Holds at most three times the bodies of the streams keeping frames plus slack per such stream, and never more
     * than Netty's aggregator; where the decrypted buffers outsize the frames, so Netty's aggregator pins them, at most
     * half what it holds.
     */
    private static void assertHeldBounded(String description, Mode mode, Result production, Result netty, long heldBodies, int heldStreams) {
        String detail = description + ": production held " + production.peakHeldBytes + ", Netty's aggregator "
            + netty.peakHeldBytes + ", bodies kept " + heldBodies + ", wire " + production.wireBytes;
        assertThat(detail, production.peakHeldBytes, lessThanOrEqualTo(3 * heldBodies + heldStreams * SLACK));
        assertThat(detail, production.peakHeldBytes, lessThanOrEqualTo(netty.peakHeldBytes));
        if (mode.decryptedBuffersOutsizeFrames()) {
            assertThat(detail, 2 * production.peakHeldBytes, lessThanOrEqualTo(netty.peakHeldBytes));
        }
    }

    private enum Mode {
        OPENSSL_RECORD_PER_FRAME(SslProvider.OPENSSL, false),
        OPENSSL_COALESCED_RECORDS(SslProvider.OPENSSL, true),
        JDK_RECORD_PER_FRAME(SslProvider.JDK, false),
        JDK_COALESCED_RECORDS(SslProvider.JDK, true);

        final SslProvider provider;
        final boolean coalesce;

        Mode(SslProvider provider, boolean coalesce) {
            this.provider = provider;
            this.coalesce = coalesce;
        }

        /**
         * OpenSSL decrypts as many whole records of a read as fit into each buffer of up to 16 KiB; the JDK's engine
         * decrypts each record into a buffer of its own, which only coalesced records make larger than one frame.
         */
        boolean decryptedBuffersOutsizeFrames() {
            return provider == SslProvider.OPENSSL || coalesce;
        }
    }
}
