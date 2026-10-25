package org.mockserver.model;

/**
 * @author jamesdbloom
 */
public class ConnectionOptions extends ObjectWithJsonToString {

    private Boolean suppressContentLengthHeader = null;
    private Integer contentLengthHeaderOverride = null;
    private Boolean suppressConnectionHeader = null;
    private Integer chunkSize = null;
    private Delay chunkDelay = null;
    private Boolean keepAliveOverride = null;
    private Boolean closeSocket = null;
    private Delay closeSocketDelay = null;

    public static ConnectionOptions connectionOptions() {
        return new ConnectionOptions();
    }

    ConnectionOptions deepCopy() {
        ConnectionOptions copy = new ConnectionOptions();
        copy.suppressContentLengthHeader = suppressContentLengthHeader;
        copy.contentLengthHeaderOverride = contentLengthHeaderOverride;
        copy.suppressConnectionHeader = suppressConnectionHeader;
        copy.chunkSize = chunkSize;
        copy.chunkDelay = chunkDelay != null ? chunkDelay.deepCopy() : null;
        copy.keepAliveOverride = keepAliveOverride;
        copy.closeSocket = closeSocket;
        copy.closeSocketDelay = closeSocketDelay != null ? closeSocketDelay.deepCopy() : null;
        return copy;
    }

    /**
     * Prevent a "Content-Length" header from being added to the response
     *
     * @param suppressContentLengthHeader if true no "Content-Length" header will be added to the response
     */
    public ConnectionOptions withSuppressContentLengthHeader(Boolean suppressContentLengthHeader) {
        this.suppressContentLengthHeader = suppressContentLengthHeader;
        return this;
    }

    public Boolean getSuppressContentLengthHeader() {
        return suppressContentLengthHeader;
    }

    /**
     * Override the "Content-Length" header with the specified amount, if not set the "Content-Length"
     * header will have a value determined by the length of the body
     *
     * @param contentLengthHeaderOverride the value to use for the "Content-Length" header
     */
    public ConnectionOptions withContentLengthHeaderOverride(Integer contentLengthHeaderOverride) {
        this.contentLengthHeaderOverride = contentLengthHeaderOverride;
        return this;
    }

    public Integer getContentLengthHeaderOverride() {
        return contentLengthHeaderOverride;
    }

    /**
     * Prevent a "Connection" header from being added to the response
     *
     * @param suppressConnectionHeader if true no "Connection" header will be added to the response
     */
    public ConnectionOptions withSuppressConnectionHeader(Boolean suppressConnectionHeader) {
        this.suppressConnectionHeader = suppressConnectionHeader;
        return this;
    }

    public Boolean getSuppressConnectionHeader() {
        return suppressConnectionHeader;
    }

    /**
     * Specifies the size of chunks in a response:
     * <p>
     * If null (the default value), zero or negative then response will not be chunked
     * If positive and non-null the response will have "Transfer-Encoding: chunked" header and will be chunked into chunks of the size specified (as long as the body is large enough)
     *
     * @param chunkSize the size of response chunks, if null response will not be chunked
     */
    public ConnectionOptions withChunkSize(Integer chunkSize) {
        this.chunkSize = chunkSize;
        return this;
    }

    public Integer getChunkSize() {
        return chunkSize;
    }

    /**
     * Specifies the delay between sending each chunk of a chunked response.
     * This simulates slow or degraded network conditions by dribbling the response
     * body over time. Only applies when chunkSize is set to a positive value.
     *
     * @param chunkDelay the delay to apply between each chunk
     */
    public ConnectionOptions withChunkDelay(Delay chunkDelay) {
        this.chunkDelay = chunkDelay;
        return this;
    }

    public Delay getChunkDelay() {
        return chunkDelay;
    }

    /**
     * Override the "Connection" header:
     * if true the "Connection" header is specified with a value of "keep-alive"
     * if false the "Connection" header is specified with a value of "close"
     * if not set the "Connection" header will have a a value of "close" unless the request received is HTTP 1.1 and contains a "Connection" header with a value of "keep-alive"
     * and closeSocket is not true
     *
     * @param keepAliveOverride if true "keep-alive" is used if false "close" is used for the "Connection" header
     */
    public ConnectionOptions withKeepAliveOverride(Boolean keepAliveOverride) {
        this.keepAliveOverride = keepAliveOverride;
        return this;
    }

    public Boolean getKeepAliveOverride() {
        return keepAliveOverride;
    }

    /**
     * Override whether the socket is closed after a response is sent:
     * if true the socket will always be closed,
     * if false the socket will never be closed,
     * if not set the socket will be closed unless the request received is HTTP 1.1 and contains a "Connection" header with a value of "keep-alive"
     *
     * @param closeSocket set whether the socket is closed after a response is sent
     */
    public ConnectionOptions withCloseSocket(Boolean closeSocket) {
        this.closeSocket = closeSocket;
        return this;
    }

    public Boolean getCloseSocket() {
        return closeSocket;
    }

    /**
     * Override how long to delay before closing socket, this value is ignored if socket isn't going to be closed
     *
     * @param closeSocketDelay set delay before closing socket
     */
    public ConnectionOptions withCloseSocketDelay(Delay closeSocketDelay) {
        this.closeSocketDelay = closeSocketDelay;
        return this;
    }

    public Delay getCloseSocketDelay() {
        return closeSocketDelay;
    }
}
