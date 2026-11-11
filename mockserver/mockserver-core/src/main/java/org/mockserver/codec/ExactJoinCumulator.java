package org.mockserver.codec;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.handler.codec.ByteToMessageDecoder;

/**
 * {@link ByteToMessageDecoder#MERGE_CUMULATOR}, except that the first time it must join an input to bytes left over
 * from earlier inputs, it copies both into a buffer of exactly their size instead of growing the cumulation to the
 * next power of two.
 * <p>
 * A decoder that slices its messages out of the cumulation, as the HTTP/2 frame decoder slices DATA frames, keeps
 * the whole buffer allocated while any slice is held. Since Netty 4.2.20 {@code SslHandler} decrypts into buffers of
 * at most 16 KiB, so a 16 KiB DATA frame and its 9-byte header never fit one: Netty grows the first buffer to
 * 32 KiB to take the frame's last bytes, and a body kept as such slices holds twice its size. Joined exactly, it
 * holds its size. A message that spans more inputs than that is grown from the joined buffer as Netty grows it, so
 * it is copied no more than once more than under Netty's cumulator; a cumulation that has room, or is empty, is
 * merged as Netty merges it. One instance per decoder: it remembers the buffer it last joined, by identity only, so a
 * released buffer object reused later at worst gets Netty's own merge.
 */
public final class ExactJoinCumulator implements ByteToMessageDecoder.Cumulator {

    private ByteBuf joined;

    @Override
    public ByteBuf cumulate(ByteBufAllocator alloc, ByteBuf cumulation, ByteBuf in) {
        if (cumulation == in
            || cumulation == joined
            || !cumulation.isReadable()
            || in.readableBytes() <= cumulation.maxFastWritableBytes()) {
            return ByteToMessageDecoder.MERGE_CUMULATOR.cumulate(alloc, cumulation, in);
        }
        try {
            joined = replace(alloc, cumulation, in);
            return joined;
        } finally {
            in.release();
        }
    }

    private static ByteBuf replace(ByteBufAllocator alloc, ByteBuf cumulation, ByteBuf in) {
        int oldBytes = cumulation.readableBytes();
        int newBytes = in.readableBytes();
        int totalBytes = oldBytes + newBytes;
        ByteBuf replacement = alloc.buffer(totalBytes);
        ByteBuf toRelease = replacement;
        try {
            replacement.setBytes(0, cumulation, cumulation.readerIndex(), oldBytes)
                .setBytes(oldBytes, in, in.readerIndex(), newBytes)
                .writerIndex(totalBytes);
            in.readerIndex(in.writerIndex());
            toRelease = cumulation;
            return replacement;
        } finally {
            toRelease.release();
        }
    }
}
