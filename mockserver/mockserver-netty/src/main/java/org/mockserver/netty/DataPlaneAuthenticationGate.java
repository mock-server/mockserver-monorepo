package org.mockserver.netty;

import org.mockserver.authentication.dataplane.DataPlaneAuthenticator;
import org.mockserver.configuration.Configuration;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.log.model.LogEntry;
import org.mockserver.mock.HttpState;
import org.mockserver.model.HttpRequest;
import org.mockserver.model.HttpResponse;
import org.mockserver.model.MediaType;
import org.mockserver.responsewriter.ResponseWriter;
import org.slf4j.event.Level;

import static io.netty.handler.codec.http.HttpHeaderNames.WWW_AUTHENTICATE;
import static io.netty.handler.codec.http.HttpResponseStatus.UNAUTHORIZED;
import static org.apache.commons.lang3.StringUtils.isNotBlank;
import static org.mockserver.cors.CORSHeaders.isCorsPreflight;
import static org.mockserver.log.model.LogEntry.LogMessageType.AUTHENTICATION_FAILED;
import static org.mockserver.model.HttpResponse.response;

/**
 * Shared data-plane (mocked endpoint) authentication gate used by every Netty data-plane dispatch
 * path — {@link HttpRequestHandler} (HTTP/1.1, HTTP/2, gRPC-over-h2) and
 * {@code Http3MockServerHandler} (HTTP/3 / QUIC, including gRPC-over-HTTP/3). Keeping the gate in one
 * place ensures the policy and the 401 response are byte-identical across transports and that a new
 * data-plane entry point cannot accidentally skip the check (the HTTP/3 path previously did,
 * fail-OPEN).
 *
 * <p>The decision itself lives in core ({@link DataPlaneAuthenticator}: opt-in, default off,
 * fail-closed when required-but-unconfigured, constant-time secret compare). This helper only
 * invokes it and — on failure — writes the {@code 401} with the correct {@code WWW-Authenticate}
 * challenge through the transport's {@link ResponseWriter} and records the audit log entry.
 */
public final class DataPlaneAuthenticationGate {

    private DataPlaneAuthenticationGate() {
    }

    /**
     * For a path that matches an expectation before this gate can run (HTTP/1.1 {@code respondBeforeBody},
     * gRPC bidi streaming): call it BEFORE the consuming match, which uses up {@code Times} and moves
     * scenarios, and on {@code false} let the request fall through to the dispatch that runs
     * {@link #isAuthenticated}, which writes the {@code 401}.
     */
    public static boolean hasCredentialsIfRequired(Configuration configuration, HttpRequest request) {
        return !configuration.dataPlaneAuthenticationRequired()
            || new DataPlaneAuthenticator(configuration).authenticate(request).isAuthenticated();
    }

    /**
     * Apply the data-plane authentication gate. Call this immediately before dispatching a data-plane
     * request to {@code httpActionHandler.processAction(...)} on every transport, and only after every
     * control-plane route has declined it. A path is never exempt for its {@code /mockserver} prefix alone,
     * because a request that reaches this gate is served by expectation matching whatever its path.
     *
     * <p>Cheap default-off path: when {@code dataPlaneAuthenticationRequired} is false this is a single
     * boolean read and returns {@code true} with no allocation, so behaviour is byte-identical to a
     * server without the feature.
     *
     * @return {@code true} if the request is authenticated (or the gate is disabled) and the caller
     * should proceed to {@code processAction}; {@code false} if a {@code 401} has already been written
     * via {@code responseWriter} and the caller must NOT proceed.
     */
    public static boolean isAuthenticated(
        Configuration configuration,
        MockServerLogger mockServerLogger,
        HttpState httpState,
        HttpRequest request,
        ResponseWriter responseWriter
    ) {
        if (!configuration.dataPlaneAuthenticationRequired()) {
            return true;
        }
        // Every caller runs this only after the control plane declined the request, so a /mockserver or
        // liveness path that reaches here is served by expectation matching and must be gated. The one
        // exception: processAction answers a control-plane CORS preflight before matching, and a browser
        // never sends credentials on a preflight.
        if (isCorsPreflight(request) && httpState.isControlPlaneRequest(request)) {
            return true;
        }
        HttpResponse unauthorizedResponse = unauthorizedResponse(configuration, mockServerLogger, request);
        if (unauthorizedResponse == null) {
            return true;
        }
        responseWriter.writeResponse(request, unauthorizedResponse, false);
        return false;
    }

    /**
     * For a handler that answers a request itself, ahead of {@link #isAuthenticated}, and so must frame the refusal
     * in its own protocol (gRPC Server Reflection, gRPC chaos faults, gRPC decode errors over HTTP/2).
     *
     * @return {@code null} if the request may proceed (authenticated, or the gate is disabled); otherwise the
     * {@code 401} to refuse it with, its failure already logged
     */
    public static HttpResponse unauthorizedResponse(Configuration configuration, MockServerLogger mockServerLogger, HttpRequest request) {
        if (!configuration.dataPlaneAuthenticationRequired()) {
            return null;
        }
        DataPlaneAuthenticator.Outcome outcome = new DataPlaneAuthenticator(configuration).authenticate(request);
        if (outcome.isAuthenticated()) {
            return null;
        }
        HttpResponse unauthorizedResponse = response()
            .withStatusCode(UNAUTHORIZED.code())
            .withReasonPhrase(UNAUTHORIZED.reasonPhrase());
        if (isNotBlank(outcome.wwwAuthenticate())) {
            unauthorizedResponse.withHeader(WWW_AUTHENTICATE.toString(), outcome.wwwAuthenticate());
        }
        // Do NOT echo any credential or the configured scheme detail in the body.
        unauthorizedResponse.withBody("Unauthorized for data plane", MediaType.create("text", "plain"));
        if (mockServerLogger.isEnabledForInstance(Level.INFO)) {
            mockServerLogger.logEvent(
                new LogEntry()
                    .setType(AUTHENTICATION_FAILED)
                    .setLogLevel(Level.INFO)
                    .setCorrelationId(request.getLogCorrelationId())
                    .setHttpRequest(request)
                    .setHttpResponse(unauthorizedResponse)
                    .setMessageFormat("data plane authentication failed so returning response:{}for request:{}")
                    .setArguments(unauthorizedResponse, request)
            );
        }
        return unauthorizedResponse;
    }
}
