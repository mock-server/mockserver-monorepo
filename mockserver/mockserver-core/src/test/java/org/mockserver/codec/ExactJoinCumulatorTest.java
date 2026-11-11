package org.mockserver.codec;

import io.netty.buffer.AbstractByteBufAllocator;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.buffer.Unpooled;
import io.netty.buffer.UnpooledHeapByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.ByteToMessageDecoder;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import java.util.function.Function;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.lessThanOrEqualTo;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.sameInstance;

public class ExactJoinCumulatorTest {

    private static final int KIB = 1024;
    private static final int FRAME_HEADER = 9;
    private static final int PAYLOAD = 16 * KIB;

    @Test
    public void shouldGrowTheBufferItJoinedAsNettyDoes() {
        CountingAllocator allocator = new CountingAllocator();
        ExactJoinCumulator cumulator = new ExactJoinCumulator();
        ByteBuf cumulation = allocator.heapBuffer(32, Integer.MAX_VALUE).writeBytes(new byte[32]);

        ByteBuf joined = cumulator.cumulate(allocator, cumulation, Unpooled.wrappedBuffer(new byte[16]));
        ByteBuf in = Unpooled.wrappedBuffer(new byte[16]);
        ByteBuf grown = cumulator.cumulate(allocator, joined, in);

        // the first join is exact; the next input grows it in place to a power of two, as MERGE_CUMULATOR grows it
        assertThat(grown, sameInstance(joined));
        assertThat(grown.capacity(), is(64));
        assertThat(grown.readableBytes(), is(64));
        assertThat(in.refCnt(), is(0));
        grown.release();
    }

    @Test
    public void shouldAppendInPlaceWhereTheCumulationHasRoom() {
        CountingAllocator allocator = new CountingAllocator();
        ByteBuf cumulation = allocator.heapBuffer(64).writeBytes(new byte[10]);
        ByteBuf held = cumulation.readRetainedSlice(8);
        ByteBuf in = Unpooled.wrappedBuffer(new byte[]{7, 8, 9});

        ByteBuf result = new ExactJoinCumulator().cumulate(allocator, cumulation, in);

        assertThat(result, sameInstance(cumulation));
        assertThat(result.readableBytes(), is(5));
        assertThat(allocator.allocatedBytes, is(64L));
        assertThat(in.refCnt(), is(0));
        held.release();
        result.release();
        assertThat(cumulation.refCnt(), is(0));
    }

    @Test
    public void shouldJoinACumulationSmallerThanTheInputIntoABufferOfExactlyTheirSize() {
        CountingAllocator allocator = new CountingAllocator();
        ByteBuf cumulation = allocator.heapBuffer(FRAME_HEADER, Integer.MAX_VALUE).writeBytes(new byte[]{1, 2, 3, 4, 5, 6, 7, 8, 9});
        ByteBuf in = allocator.heapBuffer(PAYLOAD, PAYLOAD).writeBytes(new byte[PAYLOAD]);
        in.setByte(PAYLOAD - 1, 42);

        ByteBuf result = new ExactJoinCumulator().cumulate(allocator, cumulation, in);

        assertThat(result, not(sameInstance(cumulation)));
        assertThat(result.capacity(), is(FRAME_HEADER + PAYLOAD));
        assertThat(result.readableBytes(), is(FRAME_HEADER + PAYLOAD));
        assertThat(result.getByte(0), is((byte) 1));
        assertThat(result.getByte(FRAME_HEADER - 1), is((byte) 9));
        assertThat(result.getByte(FRAME_HEADER + PAYLOAD - 1), is((byte) 42));
        assertThat(cumulation.refCnt(), is(0));
        assertThat(in.refCnt(), is(0));
        result.release();
    }

    @Test
    public void shouldLeaveAReplacedCumulationToWhatStillHoldsIt() {
        CountingAllocator allocator = new CountingAllocator();
        ByteBuf cumulation = allocator.heapBuffer(32, 32);
        for (int i = 0; i < 32; i++) {
            cumulation.writeByte(i);
        }
        ByteBuf held = cumulation.readRetainedSlice(24);
        ByteBuf in = Unpooled.wrappedBuffer(new byte[]{99, 98, 97, 96, 95, 94, 93, 92, 91});

        ByteBuf result = new ExactJoinCumulator().cumulate(allocator, cumulation, in);

        assertThat(result.capacity(), is(17));
        assertThat(result.getByte(0), is((byte) 24));
        assertThat(result.getByte(8), is((byte) 99));
        assertThat(in.refCnt(), is(0));
        assertThat(cumulation.refCnt(), is(1));
        assertThat(held.getByte(23), is((byte) 23));
        held.release();
        assertThat(cumulation.refCnt(), is(0));
        result.release();
    }

    @Test
    public void shouldReleaseTheInputButNotTheCumulationWhenTheReplacementCannotBeAllocated() {
        ByteBufAllocator failing = new CountingAllocator() {
            @Override
            public ByteBuf buffer(int initialCapacity) {
                throw new OutOfMemoryError("test");
            }
        };
        ByteBuf cumulation = Unpooled.buffer(4, 4).writeBytes(new byte[4]);
        ByteBuf in = Unpooled.buffer(16).writeBytes(new byte[16]);

        try {
            new ExactJoinCumulator().cumulate(failing, cumulation, in);
            throw new AssertionError("expected the allocation to fail");
        } catch (OutOfMemoryError expected) {
            // as MERGE_CUMULATOR: the input is always released, the cumulation is left to the decoder
        }

        assertThat(in.refCnt(), is(0));
        assertThat(cumulation.refCnt(), is(1));
        cumulation.release();
    }

    @Test
    public void shouldReleaseTheReplacementAndKeepTheCumulationWhenCopyingFails() {
        CountingAllocator allocator = new CountingAllocator();
        // without a backing array the copy has to call getBytes, which fails
        ByteBuf cumulation = new UnpooledHeapByteBuf(allocator, 4, 4) {
            @Override
            public boolean hasArray() {
                return false;
            }

            @Override
            public ByteBuf getBytes(int index, byte[] dst, int dstIndex, int length) {
                throw new IllegalStateException("test");
            }

            @Override
            public ByteBuf getBytes(int index, ByteBuf dst, int dstIndex, int length) {
                throw new IllegalStateException("test");
            }
        }.writeBytes(new byte[4]);
        ByteBuf in = Unpooled.buffer(16).writeBytes(new byte[16]);

        try {
            new ExactJoinCumulator().cumulate(allocator, cumulation, in);
            throw new AssertionError("expected the copy to fail");
        } catch (IllegalStateException expected) {
            // as expandCumulation: the replacement is released, the cumulation is left to the decoder
            assertThat(expected.getMessage(), is("test"));
        }

        assertThat(allocator.created.size(), is(1));
        assertThat(allocator.created.get(0).refCnt(), is(0));
        assertThat(in.refCnt(), is(0));
        assertThat(cumulation.refCnt(), is(1));
        cumulation.release();
    }

    /**
     * Joining exactly again here, once a message is sliced out of the joined buffer, held and allocated more in
     * {@code Http2ConnectionMemoryHarness} (16 KiB frames in shared TLS records), so the joined buffer is expanded as
     * Netty expands it.
     */
    @Test
    public void shouldExpandAJoinedBufferAsNettyDoesWhileASliceOfItIsHeld() {
        CountingAllocator allocator = new CountingAllocator();
        ExactJoinCumulator cumulator = new ExactJoinCumulator();
        ByteBuf cumulation = allocator.heapBuffer(16, 16).writeBytes(new byte[16]);
        ByteBuf joined = cumulator.cumulate(allocator, cumulation, Unpooled.wrappedBuffer(new byte[24]));
        // a decoded message held, and the start of the next left over
        ByteBuf held = joined.readRetainedSlice(36);

        ByteBuf next = cumulator.cumulate(allocator, joined, Unpooled.wrappedBuffer(new byte[30]));

        assertThat(next, not(sameInstance(joined)));
        assertThat(next.capacity(), is(64));
        assertThat(next.readableBytes(), is(4 + 30));
        assertThat(joined.refCnt(), is(1));
        held.release();
        assertThat(joined.refCnt(), is(0));
        next.release();
    }

    /**
     * Each frame in a 16 KiB input and one of its last 9 bytes, as {@code SslHandler} hands over a client's 16 KiB
     * DATA frames sent in records of their own, every payload kept: Netty grows the first input to 32 KiB to take the
     * frame's last bytes, so what is held is twice the payloads.
     */
    @Test
    public void shouldHoldEachPayloadInABufferOfItsOwnSizeWhenItsHeaderArrivedAlone() {
        int frames = 64;
        Outcome merge = decode(ByteToMessageDecoder.MERGE_CUMULATOR, frames, alloc -> recordPerFrame(alloc, frames));
        Outcome pinned = decode(new ExactJoinCumulator(), frames, alloc -> recordPerFrame(alloc, frames));
        long payloads = (long) frames * PAYLOAD;

        assertThat(merge.heldBytes, greaterThanOrEqualTo(2 * payloads));
        assertThat(pinned.heldBytes, is((long) frames * (FRAME_HEADER + PAYLOAD)));
        assertThat(pinned.allocatedBytes, lessThanOrEqualTo(merge.allocatedBytes));
    }

    /**
     * Frames packed into 16 KiB inputs, so each is cut across two: holds and allocates no more than Netty.
     */
    @Test
    public void shouldHoldAndAllocateNoMoreThanNettyForFramesCutAcrossEveryInput() {
        int frames = 64;
        Outcome merge = decode(ByteToMessageDecoder.MERGE_CUMULATOR, frames, alloc -> packed(alloc, frames, PAYLOAD));
        Outcome pinned = decode(new ExactJoinCumulator(), frames, alloc -> packed(alloc, frames, PAYLOAD));

        assertThat(pinned.heldBytes, lessThanOrEqualTo(merge.heldBytes));
        assertThat(pinned.allocatedBytes, lessThanOrEqualTo(merge.allocatedBytes));
    }

    /**
     * A frame of 1 MiB in 16 KiB inputs: once the cumulation is larger than an input it grows as Netty grows it, so
     * the frame is not copied once per input.
     */
    @Test
    public void shouldGrowGeometricallyForAFrameSpanningManyInputs() {
        int payload = 1024 * KIB;
        Outcome merge = decode(ByteToMessageDecoder.MERGE_CUMULATOR, 1, alloc -> packed(alloc, 1, payload));
        Outcome pinned = decode(new ExactJoinCumulator(), 1, alloc -> packed(alloc, 1, payload));

        assertThat(pinned.allocatedBytes, lessThanOrEqualTo(merge.allocatedBytes + 2 * PAYLOAD + FRAME_HEADER));
    }

    @Test
    public void shouldAllocateNothingForFramesThatNeverCrossAnInput() {
        int frames = 64;
        int frameBytes = FRAME_HEADER + PAYLOAD;
        byte[] wire = wire(frames, PAYLOAD);

        Outcome pinned = decode(new ExactJoinCumulator(), frames, alloc -> {
            List<ByteBuf> inputs = new ArrayList<>();
            for (int offset = 0; offset < wire.length; offset += 4 * frameBytes) {
                inputs.add(input(alloc, wire, offset, Math.min(4 * frameBytes, wire.length - offset)));
            }
            return inputs;
        });

        assertThat(pinned.allocatedBytes, is(0L));
    }

    private static List<ByteBuf> recordPerFrame(ByteBufAllocator alloc, int frames) {
        byte[] wire = wire(frames, PAYLOAD);
        List<ByteBuf> inputs = new ArrayList<>();
        for (int offset = 0; offset < wire.length; offset += FRAME_HEADER + PAYLOAD) {
            inputs.add(input(alloc, wire, offset, PAYLOAD));
            inputs.add(input(alloc, wire, offset + PAYLOAD, FRAME_HEADER));
        }
        return inputs;
    }

    private static List<ByteBuf> packed(ByteBufAllocator alloc, int frames, int payload) {
        byte[] wire = wire(frames, payload);
        List<ByteBuf> inputs = new ArrayList<>();
        for (int offset = 0; offset < wire.length; offset += PAYLOAD) {
            inputs.add(input(alloc, wire, offset, Math.min(PAYLOAD, wire.length - offset)));
        }
        return inputs;
    }

    /**
     * Sized to what it holds and growable, as {@code SslHandler}'s are.
     */
    private static ByteBuf input(ByteBufAllocator alloc, byte[] wire, int offset, int length) {
        return alloc.heapBuffer(length, Integer.MAX_VALUE).writeBytes(wire, offset, length);
    }

    private static byte[] wire(int frames, int payload) {
        int frameBytes = FRAME_HEADER + payload;
        byte[] wire = new byte[frames * frameBytes];
        for (int frame = 0; frame < frames; frame++) {
            int at = frame * frameBytes;
            wire[at] = (byte) (payload >>> 16);
            wire[at + 1] = (byte) (payload >>> 8);
            wire[at + 2] = (byte) payload;
            for (int i = 0; i < payload; i++) {
                wire[at + FRAME_HEADER + i] = (byte) (frame * 7 + i);
            }
        }
        return wire;
    }

    /**
     * Decodes length-prefixed frames as an HTTP/2 frame reader does, keeping a slice of every payload; checks every
     * payload, and that every buffer is released once the payloads are. Counts what the decoder allocates, inputs
     * grown in place included.
     */
    private static Outcome decode(ByteToMessageDecoder.Cumulator cumulator, int frames, Function<ByteBufAllocator, List<ByteBuf>> wireInputs) {
        CountingAllocator allocator = new CountingAllocator();
        List<ByteBuf> inputs = wireInputs.apply(allocator);
        long inputBytes = allocator.allocatedBytes;
        List<ByteBuf> payloads = new ArrayList<>();
        ByteToMessageDecoder decoder = new ByteToMessageDecoder() {
            @Override
            protected void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) {
                while (in.readableBytes() >= FRAME_HEADER && in.readableBytes() >= FRAME_HEADER + in.getUnsignedMedium(in.readerIndex())) {
                    int length = in.readUnsignedMedium();
                    in.skipBytes(FRAME_HEADER - 3);
                    payloads.add(in.readRetainedSlice(length));
                }
            }
        };
        decoder.setCumulator(cumulator);
        EmbeddedChannel channel = new EmbeddedChannel();
        channel.config().setAllocator(allocator);
        channel.pipeline().addLast(decoder);
        Set<ByteBuf> roots = Collections.newSetFromMap(new IdentityHashMap<>());
        for (ByteBuf input : inputs) {
            roots.add(root(input));
            channel.writeInbound(input);
        }
        assertThat(payloads.size(), is(frames));
        Outcome outcome = new Outcome();
        outcome.allocatedBytes = allocator.allocatedBytes - inputBytes;
        Set<ByteBuf> held = Collections.newSetFromMap(new IdentityHashMap<>());
        for (int frame = 0; frame < frames; frame++) {
            ByteBuf payload = payloads.get(frame);
            held.add(root(payload));
            for (int i = 0; i < payload.readableBytes(); i += 997) {
                assertThat(payload.getByte(payload.readerIndex() + i), is((byte) (frame * 7 + i)));
            }
        }
        for (ByteBuf root : held) {
            outcome.heldBytes += root.capacity();
        }
        for (ByteBuf payload : payloads) {
            payload.release();
        }
        channel.finishAndReleaseAll();
        for (ByteBuf root : held) {
            assertThat("a held buffer leaked", root.refCnt(), is(0));
        }
        for (ByteBuf root : roots) {
            assertThat("an input leaked", root.refCnt(), is(0));
        }
        return outcome;
    }

    private static ByteBuf root(ByteBuf buffer) {
        ByteBuf root = buffer;
        for (ByteBuf next = root.unwrap(); next != null && next != root; next = root.unwrap()) {
            root = next;
        }
        return root;
    }

    private static final class Outcome {
        long heldBytes;
        long allocatedBytes;
    }

    /**
     * Counts every byte array its buffers allocate, growing in place included.
     */
    private static class CountingAllocator extends AbstractByteBufAllocator {
        long allocatedBytes;
        final List<ByteBuf> created = new ArrayList<>();

        @Override
        protected ByteBuf newHeapBuffer(int initialCapacity, int maxCapacity) {
            ByteBuf buffer = new CountedHeapByteBuf(this, initialCapacity, maxCapacity);
            created.add(buffer);
            return buffer;
        }

        @Override
        protected ByteBuf newDirectBuffer(int initialCapacity, int maxCapacity) {
            return newHeapBuffer(initialCapacity, maxCapacity);
        }

        @Override
        public boolean isDirectBufferPooled() {
            return false;
        }
    }

    private static final class CountedHeapByteBuf extends UnpooledHeapByteBuf {
        CountedHeapByteBuf(CountingAllocator alloc, int initialCapacity, int maxCapacity) {
            super(alloc, initialCapacity, maxCapacity);
        }

        @Override
        protected byte[] allocateArray(int initialCapacity) {
            ((CountingAllocator) alloc()).allocatedBytes += initialCapacity;
            return super.allocateArray(initialCapacity);
        }
    }
}
