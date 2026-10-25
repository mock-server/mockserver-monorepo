package org.mockserver.model;

import java.io.IOException;
import java.io.OutputStream;
import java.io.Writer;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.Charset;
import java.nio.charset.CharsetEncoder;
import java.nio.charset.CoderResult;
import java.nio.charset.CodingErrorAction;
import java.util.ArrayList;
import java.util.List;

/**
 * Bytes written once, in order, into arrays that are kept as they are: content serialised into it is
 * held once, where a growing array is copied each time it grows and again to trim it, and a String is
 * a further copy before it is encoded. The arrays start small and double up to a fixed size, so a
 * small content wastes little and a large one at most one array.
 * <p>
 * Not thread-safe: one thread writes it, then any thread may read it.
 */
public final class SegmentedBytes extends OutputStream {

    static final int FIRST_SEGMENT = 1024;
    static final int LARGEST_SEGMENT = 1 << 20;

    private final int largestSize;
    private final List<byte[]> segments = new ArrayList<>();
    private byte[] current;
    private int position;
    private int size;

    public SegmentedBytes() {
        this(BodyTextEncoder.LARGEST_ARRAY);
    }

    SegmentedBytes(int largestSize) {
        this.largestSize = largestSize;
    }

    @Override
    public void write(int b) {
        if (current == null || position == current.length) {
            nextSegment(1);
        }
        current[position++] = (byte) b;
        size++;
    }

    @Override
    public void write(byte[] bytes, int offset, int length) {
        if (offset < 0 || length < 0 || length > bytes.length - offset) {
            throw new IndexOutOfBoundsException("offset " + offset + " and length " + length + " for an array of " + bytes.length);
        }
        while (length > 0) {
            if (current == null || position == current.length) {
                nextSegment(length);
            }
            int count = Math.min(length, current.length - position);
            System.arraycopy(bytes, offset, current, position, count);
            position += count;
            size += count;
            offset += count;
            length -= count;
        }
    }

    private void nextSegment(int needed) {
        if ((long) size + needed > largestSize) {
            // the size a frontend can write as one buffer, and the size of the one array the String held
            throw new OutOfMemoryError("content of more than " + largestSize + " bytes, the most one buffer can hold");
        }
        int length = (int) Math.min(Math.min(Math.max(FIRST_SEGMENT, (long) size), LARGEST_SEGMENT), (long) largestSize - size);
        current = new byte[length];
        position = 0;
        segments.add(current);
    }

    public int size() {
        return size;
    }

    /**
     * A copy holding copies of these segments, which can still be written to where this one could.
     */
    SegmentedBytes copy() {
        SegmentedBytes copy = new SegmentedBytes(largestSize);
        for (byte[] segment : segments) {
            byte[] copied = segment.clone();
            copy.segments.add(copied);
            if (segment == current) {
                copy.current = copied;
            }
        }
        copy.position = position;
        copy.size = size;
        return copy;
    }

    /**
     * The content as buffers over the arrays it is held in, in order; nothing is copied, so a reader
     * must not write to them.
     */
    public ByteBuffer[] asByteBuffers() {
        ByteBuffer[] buffers = new ByteBuffer[segments.size()];
        int remaining = size;
        for (int i = 0; i < buffers.length; i++) {
            byte[] segment = segments.get(i);
            int length = Math.min(segment.length, remaining);
            buffers[i] = ByteBuffer.wrap(segment, 0, length);
            remaining -= length;
        }
        return buffers;
    }

    /**
     * A copy of the content in one array.
     */
    public byte[] toByteArray() {
        byte[] bytes = new byte[size];
        int offset = 0;
        for (byte[] segment : segments) {
            int length = Math.min(segment.length, size - offset);
            System.arraycopy(segment, 0, bytes, offset, length);
            offset += length;
        }
        return bytes;
    }

    public void writeTo(OutputStream out) throws IOException {
        int remaining = size;
        for (byte[] segment : segments) {
            int length = Math.min(segment.length, remaining);
            out.write(segment, 0, length);
            remaining -= length;
        }
    }

    /**
     * A writer that encodes what is written to it into these bytes, as {@link String#getBytes(Charset)}
     * encodes the same text: a character the charset cannot encode, such as an unpaired surrogate, is
     * replaced by the charset's replacement. A surrogate pair split across two writes is encoded as one
     * character. {@link Writer#close()} encodes what it still holds; nothing is written after it.
     */
    public Writer writer(Charset charset) {
        return new EncodingWriter(charset.newEncoder()
            .onMalformedInput(CodingErrorAction.REPLACE)
            .onUnmappableCharacter(CodingErrorAction.REPLACE));
    }

    private final class EncodingWriter extends Writer {

        private static final int CHUNK = 8192;
        private final CharsetEncoder encoder;
        private final CharBuffer in = CharBuffer.allocate(CHUNK);
        private final ByteBuffer out;
        private boolean closed;

        private EncodingWriter(CharsetEncoder encoder) {
            this.encoder = encoder;
            this.out = ByteBuffer.allocate((int) Math.ceil(CHUNK * (double) encoder.maxBytesPerChar()));
        }

        @Override
        public void write(int c) {
            ensureOpen();
            if (!in.hasRemaining()) {
                encode(false);
            }
            in.put((char) c);
        }

        @Override
        public void write(char[] chars, int offset, int length) {
            ensureOpen();
            while (length > 0) {
                if (!in.hasRemaining()) {
                    encode(false);
                }
                int count = Math.min(length, in.remaining());
                in.put(chars, offset, count);
                offset += count;
                length -= count;
            }
        }

        @Override
        public void write(String text, int offset, int length) {
            ensureOpen();
            while (length > 0) {
                if (!in.hasRemaining()) {
                    encode(false);
                }
                int count = Math.min(length, in.remaining());
                text.getChars(offset, offset + count, in.array(), in.arrayOffset() + in.position());
                in.position(in.position() + count);
                offset += count;
                length -= count;
            }
        }

        /**
         * Encodes the characters held; with {@code endOfInput} false a high surrogate left last is kept
         * for the next write, which may hold its low surrogate.
         */
        private void encode(boolean endOfInput) {
            in.flip();
            CoderResult result;
            do {
                result = encoder.encode(in, out, endOfInput);
                drain();
            } while (result.isOverflow());
            in.compact();
            if (!endOfInput && !in.hasRemaining()) {
                throw new IllegalStateException("the encoder kept every character of a full buffer");
            }
        }

        private void drain() {
            out.flip();
            SegmentedBytes.this.write(out.array(), out.arrayOffset() + out.position(), out.remaining());
            out.clear();
        }

        @Override
        public void flush() {
            // a held high surrogate may yet be paired, so it stays held
        }

        @Override
        public void close() {
            if (!closed) {
                closed = true;
                encode(true);
                CoderResult result;
                do {
                    result = encoder.flush(out);
                    drain();
                } while (result.isOverflow());
            }
        }

        private void ensureOpen() {
            if (closed) {
                throw new IllegalStateException("written after it was closed");
            }
        }
    }
}
