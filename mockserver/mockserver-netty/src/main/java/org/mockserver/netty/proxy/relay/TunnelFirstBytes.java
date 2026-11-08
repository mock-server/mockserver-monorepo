package org.mockserver.netty.proxy.relay;

import io.netty.buffer.ByteBuf;
import io.netty.handler.ssl.SslHandler;

import static org.mockserver.netty.unification.PortUnificationHandler.H2C_PREFACE_LENGTH;
import static org.mockserver.netty.unification.PortUnificationHandler.isPartialOrCompleteH2cPreface;

/**
 * What the first bytes a client sends through a CONNECT or SOCKS tunnel are: a TLS record, the h2c preface, an HTTP/1.x
 * request line, or anything else, which is relayed as a binary connection. HTTP/1.x is told by the grammar of its
 * request line, not by a list of methods, so a request with any method (WebDAV's {@code PROPFIND}, a custom one) is
 * still HTTP, while a text protocol whose line does not end in an HTTP version (Redis, memcached, SMTP) is binary.
 */
final class TunnelFirstBytes {

    enum Protocol {TLS, H2C, HTTP1, BINARY, NEED_MORE}

    // the record content types SslHandler.isEncrypted accepts: change cipher spec (20) to heartbeat (24)
    private static final int TLS_CONTENT_TYPE_FIRST = 20;
    private static final int TLS_CONTENT_TYPE_LAST = 24;
    private static final int TLS_RECORD_HEADER_LENGTH = 5;
    private static final byte[] HTTP_NAME = {'H', 'T', 'T', 'P', '/'};
    // each read rescans what is held, so how far it looks is bounded
    static final int MAX_LOOK_AHEAD = 8192;

    private enum RequestLine {COMPLETE, NOT_ONE, START_OF_ONE, START_OF_ONE_PAST_ITS_METHOD}

    private TunnelFirstBytes() {
    }

    /**
     * @param noMoreToCome the client has fallen silent or closed, so the bytes are judged as they are: the start of a
     *                     request line already past its method is HTTP/1.x, anything else undecided is binary
     */
    static Protocol classify(ByteBuf in, boolean http2Enabled, boolean assumeAllRequestsAreHttp, int maxInitialLineLength, boolean noMoreToCome) {
        int readable = in.readableBytes();
        Protocol notHttp = assumeAllRequestsAreHttp ? Protocol.HTTP1 : Protocol.BINARY;
        int first = in.getUnsignedByte(in.readerIndex());
        if (first >= TLS_CONTENT_TYPE_FIRST && first <= TLS_CONTENT_TYPE_LAST) {
            if (readable < TLS_RECORD_HEADER_LENGTH) {
                return noMoreToCome ? notHttp : Protocol.NEED_MORE;
            }
            return SslHandler.isEncrypted(in) ? Protocol.TLS : notHttp;
        }
        if (http2Enabled && isPartialOrCompleteH2cPreface(in, readable)) {
            if (readable >= H2C_PREFACE_LENGTH) {
                return Protocol.H2C;
            }
            if (!noMoreToCome) {
                return Protocol.NEED_MORE;
            }
        }
        if (assumeAllRequestsAreHttp) {
            return Protocol.HTTP1;
        }
        switch (requestLine(in, maxInitialLineLength)) {
            case COMPLETE:
                return Protocol.HTTP1;
            case START_OF_ONE_PAST_ITS_METHOD:
                return noMoreToCome ? Protocol.HTTP1 : Protocol.NEED_MORE;
            case START_OF_ONE:
                return noMoreToCome ? Protocol.BINARY : Protocol.NEED_MORE;
            default:
                return Protocol.BINARY;
        }
    }

    /**
     * Reads {@code method SP request-target SP HTTP/d.d CRLF}, leniently as Netty's codec does: empty lines before it,
     * runs of spaces or tabs between its parts, a bare LF at its end. A line longer than the codec accepts, or than
     * {@link #MAX_LOOK_AHEAD}, once past its method, is taken for HTTP, so the codec answers it as it would on any
     * connection; a longer method is no method.
     */
    private static RequestLine requestLine(ByteBuf in, int maxInitialLineLength) {
        int start = in.readerIndex();
        int limit = Math.min(maxInitialLineLength, MAX_LOOK_AHEAD);
        // one byte past the limit, to tell a line at it from one over it
        int end = (int) Math.min(in.writerIndex(), (long) start + limit + 1);
        int i = start;
        while (i < end && isLineEnd(in.getByte(i))) {
            i++;
        }
        int methodStart = i;
        while (i < end && isTokenChar(in.getByte(i))) {
            i++;
        }
        if (i == end) {
            // a method longer than any request line the codec accepts is no method
            return i - start > limit ? RequestLine.NOT_ONE : RequestLine.START_OF_ONE;
        }
        if (i == methodStart || !isSpace(in.getByte(i))) {
            return RequestLine.NOT_ONE;
        }
        while (i < end && isSpace(in.getByte(i))) {
            i++;
        }
        int targetStart = i;
        while (i < end && isTargetChar(in.getByte(i))) {
            i++;
        }
        if (i < end && (i == targetStart || !isSpace(in.getByte(i)))) {
            return RequestLine.NOT_ONE;
        }
        while (i < end && isSpace(in.getByte(i))) {
            i++;
        }
        for (int n = 0; n < HTTP_NAME.length && i < end; n++, i++) {
            if (in.getByte(i) != HTTP_NAME[n]) {
                return RequestLine.NOT_ONE;
            }
        }
        i = digits(in, i, end);
        if (i < 0) {
            return RequestLine.NOT_ONE;
        }
        if (i < end && in.getByte(i++) != '.') {
            return RequestLine.NOT_ONE;
        }
        i = digits(in, i, end);
        if (i < 0) {
            return RequestLine.NOT_ONE;
        }
        while (i < end && isSpace(in.getByte(i))) {
            i++;
        }
        if (i < end && in.getByte(i) == '\r') {
            i++;
        }
        if (i < end) {
            return in.getByte(i) == '\n' ? RequestLine.COMPLETE : RequestLine.NOT_ONE;
        }
        // undecided: the line is not over yet
        return i - start > limit ? RequestLine.COMPLETE : RequestLine.START_OF_ONE_PAST_ITS_METHOD;
    }

    /**
     * @return the index after one or more digits, the end if the bytes run out first, or -1 if no digit is there
     */
    private static int digits(ByteBuf in, int i, int end) {
        if (i >= end) {
            return i;
        }
        int digitsStart = i;
        while (i < end && in.getByte(i) >= '0' && in.getByte(i) <= '9') {
            i++;
        }
        return i == digitsStart && i < end ? -1 : i;
    }

    private static boolean isLineEnd(byte b) {
        return b == '\r' || b == '\n';
    }

    private static boolean isSpace(byte b) {
        return b == ' ' || b == '\t';
    }

    /**
     * RFC 9110 tchar: a visible ASCII character other than a delimiter.
     */
    private static boolean isTokenChar(byte b) {
        if (b >= 'a' && b <= 'z' || b >= 'A' && b <= 'Z' || b >= '0' && b <= '9') {
            return true;
        }
        switch (b) {
            case '!': case '#': case '$': case '%': case '&': case '\'': case '*': case '+':
            case '-': case '.': case '^': case '_': case '`': case '|': case '~':
                return true;
            default:
                return false;
        }
    }

    /**
     * Any visible character, or a byte of a UTF-8 sequence, which some clients send unencoded.
     */
    private static boolean isTargetChar(byte b) {
        return (b & 0xFF) > ' ' && b != 0x7F;
    }
}
