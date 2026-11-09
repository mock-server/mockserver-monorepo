package org.mockserver.responsewriter;

import io.netty.handler.codec.http.HttpResponseStatus;
import org.mockserver.configuration.Configuration;
import org.mockserver.cors.CORSHeaders;
import org.mockserver.responseheaders.DefaultResponseHeaders;
import org.mockserver.log.model.LogEntry;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.model.ConnectionOptions;
import org.mockserver.model.Header;
import org.mockserver.model.HttpRequest;
import org.mockserver.model.HttpResponse;
import org.mockserver.version.Version;
import org.slf4j.event.Level;

import static io.netty.handler.codec.http.HttpHeaderNames.*;
import static io.netty.handler.codec.http.HttpHeaderValues.CLOSE;
import static io.netty.handler.codec.http.HttpHeaderValues.KEEP_ALIVE;
import static org.apache.commons.lang3.StringUtils.isNotBlank;
import static org.mockserver.log.model.LogEntry.LogMessageType.INFO;
import static org.mockserver.mock.HttpState.PATH_PREFIX;
import static org.mockserver.mock.HttpState.isControlPlanePathCandidate;
import static org.mockserver.model.Header.header;
import static org.mockserver.model.HttpResponse.notFoundResponse;
import static org.mockserver.model.HttpResponse.response;

/**
 * @author jamesdbloom
 */
public abstract class ResponseWriter {

    // Header is immutable and replaceEntry only reads its name and values, so these two
    // compile-time-constant Connection headers can be shared across every response.
    private static final Header KEEP_ALIVE_CONNECTION_HEADER = header(CONNECTION.toString(), KEEP_ALIVE.toString());
    private static final Header CLOSE_CONNECTION_HEADER = header(CONNECTION.toString(), CLOSE.toString());

    protected final Configuration configuration;
    protected final MockServerLogger mockServerLogger;
    // CORS headers are only added for control-plane / dashboard responses or when
    // enableCORSForAllResponses is on (both off for default mock traffic), so the CORSHeaders
    // resolution — five configuration lookups and a String concat — is built lazily on first use
    // rather than for every per-request ResponseWriter. A ResponseWriter serves a single request flow,
    // so no locking is needed.
    private CORSHeaders corsHeaders;
    private final DefaultResponseHeaders defaultResponseHeaders;

    protected ResponseWriter(Configuration configuration, MockServerLogger mockServerLogger) {
        this.configuration = configuration;
        this.mockServerLogger = mockServerLogger;
        defaultResponseHeaders = new DefaultResponseHeaders(configuration);
    }

    private CORSHeaders corsHeaders() {
        if (corsHeaders == null) {
            corsHeaders = new CORSHeaders(configuration);
        }
        return corsHeaders;
    }

    public void writeResponse(final HttpRequest request, final HttpResponseStatus responseStatus) {
        writeResponse(request, responseStatus, "", "application/json");
    }

    public void writeResponse(final HttpRequest request, final HttpResponseStatus responseStatus, final String body, final String contentType) {
        HttpResponse response = response()
            .withStatusCode(responseStatus.code())
            .withReasonPhrase(responseStatus.reasonPhrase())
            .withBody(body);
        if (body != null && !body.isEmpty()) {
            response.replaceHeader(header(CONTENT_TYPE.toString(), contentType + "; charset=utf-8"));
        }
        writeResponse(request, response, true);
    }

    /**
     * Answers a CORS preflight for a request MockServer's own API would not serve, under the
     * {@code corsAllowOrigin} policy. On a path the control plane also serves with other methods (a bare
     * alias such as {@code /graphql}) only the requested method is allowed, so a cached preflight can
     * never cover a cross-site control-plane request on the same URL.
     */
    public void writeDataPlanePreflightResponse(final HttpRequest request) {
        HttpResponse response = response()
            .withStatusCode(HttpResponseStatus.OK.code())
            .withReasonPhrase(HttpResponseStatus.OK.reasonPhrase())
            .withBody("")
            .withHeader("version", Version.getVersion());
        String requestedMethod = request.getFirstHeader(ACCESS_CONTROL_REQUEST_METHOD.toString());
        if (request.getPath() != null && isControlPlanePathCandidate(request.getPath().getValue()) && !requestedMethod.isEmpty()) {
            response.withHeader(ACCESS_CONTROL_ALLOW_METHODS.toString(), requestedMethod);
        }
        corsHeaders().addCORSHeaders(request, response);
        writeResponse(request, response, false);
    }

    public void writeResponse(final HttpRequest request, HttpResponse response, final boolean apiResponse) {
        if (response == null) {
            response = notFoundResponse();
        }
        // control-plane responses always get the control-plane origin policy; mock and proxy
        // responses get the data-plane headers, the only place those are decided
        if (apiResponse) {
            corsHeaders().addControlPlaneCORSHeaders(request, response);
            defaultResponseHeaders.addDefaultResponseHeaders(response);
        } else {
            addDataPlaneResponseHeaders(request, response);
        }
        // The whole block only emits an INFO diagnostic, so gate the header scan and body
        // materialisation on the log level first: at WARN and above none of this work runs.
        if (mockServerLogger.isEnabledForInstance(Level.INFO)) {
            String contentLengthHeader = response.getFirstHeader(CONTENT_LENGTH.toString());
            if (isNotBlank(contentLengthHeader)) {
                try {
                    int contentLength = Integer.parseInt(contentLengthHeader);
                    int bodyLength = response.getBodyAsRawBytes().length;
                    if (bodyLength > contentLength) {
                        mockServerLogger.logEvent(
                            new LogEntry()
                                .setType(INFO)
                                .setLogLevel(Level.INFO)
                                .setCorrelationId(request.getLogCorrelationId())
                                .setHttpRequest(request)
                                .setHttpResponse(response)
                                .setMessageFormat("returning response with content-length header " + contentLength + " which is smaller then response body length " + bodyLength + ", body will likely be truncated by client receiving request")
                        );
                    }
                } catch (NumberFormatException ignore) {
                    // ignore exception while parsing invalid content-length header
                }
            }
        }
        if (apiResponse) {
            response.withHeader("version", Version.getVersion());
            final String path = request.getPath().getValue();
            if (!path.startsWith(PATH_PREFIX) && !path.equals(configuration.livenessHttpGetPath())) {
                response.withHeader("deprecated",
                    "\"" + path + "\" is deprecated use \"" + PATH_PREFIX + path + "\" instead");
            }
            // send response down the request HTTP2 stream
            if (request.getStreamId() != null) {
                response.withStreamId(request.getStreamId());
            }
        }

        sendResponse(request, addConnectionHeader(request, response));
    }

    /**
     * The headers {@link #writeResponse(HttpRequest, HttpResponse, boolean)} adds to every data-plane response: CORS
     * when {@code enableCORSForAllResponses} is on, the {@code defaultResponseHeaders} (add-if-absent, so an explicit
     * header wins), and the HTTP/2 stream id. Also applied to a response its caller keeps while a copy is written.
     */
    public void addDataPlaneResponseHeaders(final HttpRequest request, final HttpResponse response) {
        if (configuration.enableCORSForAllResponses()) {
            corsHeaders().addCORSHeaders(request, response);
        }
        defaultResponseHeaders.addDefaultResponseHeaders(response);
        if (request.getStreamId() != null) {
            response.withStreamId(request.getStreamId());
        }
    }

    public abstract void sendResponse(HttpRequest request, HttpResponse response);

    /**
     * Announces that this exchange's response is about to be written straight to the channel rather than
     * through {@link #sendResponse}, as SSE, WebSocket, gRPC stream and raw-bytes error responses are. Returns
     * what the handler writing that response runs once it has ended (its last part written, or the exchange
     * ended without one), so a writer that tracks the exchange can release it. Only that handler holds it: on a
     * connection carrying other exchanges too, their ends do not run it. It may run more than once. Does
     * nothing here; a writer whose channel cannot take the HTTP/1.1 objects such a handler writes (HTTP/3) readies
     * the channel for them.
     */
    public Runnable respondingDirectly() {
        return () -> {
        };
    }

    protected HttpResponse addConnectionHeader(final HttpRequest request, final HttpResponse response) {
        ConnectionOptions connectionOptions = response.getConnectionOptions();

        // Copy only the headers: replaceHeader below mutates the Connection header, and this copy
        // exists so that mutation cannot leak into a caller that passed a shared response. Every
        // other field is shared by reference, since nothing here mutates them.
        HttpResponse responseWithConnectionHeader = response.cloneWithHeaders();

        if (connectionOptions != null && (connectionOptions.getSuppressConnectionHeader() != null || connectionOptions.getKeepAliveOverride() != null)) {
            if (!Boolean.TRUE.equals(connectionOptions.getSuppressConnectionHeader())) {
                if (Boolean.TRUE.equals(connectionOptions.getKeepAliveOverride())) {
                    responseWithConnectionHeader.replaceHeader(KEEP_ALIVE_CONNECTION_HEADER);
                } else {
                    responseWithConnectionHeader.replaceHeader(CLOSE_CONNECTION_HEADER);
                }
            }
        } else {
            // a socket closed after the response must not be announced as kept alive, or the client may send its
            // next request into the close
            boolean closesSocket = connectionOptions != null && Boolean.TRUE.equals(connectionOptions.getCloseSocket());
            if (Boolean.TRUE.equals(request.isKeepAlive()) && !closesSocket) {
                responseWithConnectionHeader.replaceHeader(KEEP_ALIVE_CONNECTION_HEADER);
            } else {
                responseWithConnectionHeader.replaceHeader(CLOSE_CONNECTION_HEADER);
            }
        }

        return responseWithConnectionHeader;
    }
}
