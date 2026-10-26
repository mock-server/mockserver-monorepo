package org.mockserver.servlet;

import jakarta.servlet.http.HttpServletRequest;
import org.mockserver.authentication.DataPlaneAuthenticationGate;
import org.mockserver.configuration.Configuration;
import org.mockserver.configuration.ServerTlsSettings;
import org.mockserver.log.model.LogEntry;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.mock.HttpState;
import org.mockserver.model.HttpRequest;
import org.mockserver.model.HttpResponse;
import org.mockserver.model.MediaType;
import org.mockserver.responsewriter.ResponseWriter;
import org.slf4j.event.Level;

import java.security.cert.X509Certificate;

import static io.netty.handler.codec.http.HttpResponseStatus.UNAUTHORIZED;
import static org.mockserver.log.model.LogEntry.LogMessageType.AUTHENTICATION_FAILED;
import static org.mockserver.model.HttpResponse.response;

/**
 * The data-plane authentication the WAR servlets ({@code MockServerServlet}, {@code ProxyServlet}) run on a request
 * no control-plane route served, before {@code processAction}: {@link DataPlaneAuthenticationGate} first, then, with
 * {@code tlsMutualAuthenticationRequired}, a client certificate.
 *
 * <p>The servlet container terminates TLS, so it, not MockServer, verifies the client certificate against its own
 * trust store; {@code tlsMutualAuthenticationCertificateChain} does not apply in a WAR. MockServer requires that the
 * request arrived over TLS (otherwise {@code 426}, as on Netty) and that the container reports a verified client
 * certificate in the {@value #CLIENT_CERTIFICATE_ATTRIBUTE} attribute (otherwise {@code 401}).
 */
public final class ServletDataPlaneAuthentication {

    public static final String CLIENT_CERTIFICATE_ATTRIBUTE = "jakarta.servlet.request.X509Certificate";

    private ServletDataPlaneAuthentication() {
    }

    /**
     * @return {@code true} if the caller should go on to {@code processAction}; {@code false} if the refusal has
     * already been written through {@code responseWriter}
     */
    public static boolean isAuthenticated(
        Configuration configuration,
        MockServerLogger mockServerLogger,
        HttpState httpState,
        HttpServletRequest httpServletRequest,
        HttpRequest request,
        ResponseWriter responseWriter
    ) {
        if (!DataPlaneAuthenticationGate.isAuthenticated(configuration, mockServerLogger, httpState, request, responseWriter)) {
            return false;
        }
        if (!Boolean.TRUE.equals(ServerTlsSettings.of(configuration).tlsMutualAuthenticationRequired())) {
            return true;
        }
        if (!httpServletRequest.isSecure()) {
            DataPlaneAuthenticationGate.writeTlsRequiredResponse(mockServerLogger, request, responseWriter);
            return false;
        }
        if (hasClientCertificate(httpServletRequest)) {
            return true;
        }
        HttpResponse unauthorizedResponse = response()
            .withStatusCode(UNAUTHORIZED.code())
            .withReasonPhrase(UNAUTHORIZED.reasonPhrase())
            .withBody("Unauthorized for data plane", MediaType.create("text", "plain"));
        if (mockServerLogger.isEnabledForInstance(Level.INFO)) {
            mockServerLogger.logEvent(
                new LogEntry()
                    .setType(AUTHENTICATION_FAILED)
                    .setLogLevel(Level.INFO)
                    .setCorrelationId(request.getLogCorrelationId())
                    .setHttpRequest(request)
                    .setHttpResponse(unauthorizedResponse)
                    .setMessageFormat("data plane request has no client certificate, which tlsMutualAuthenticationRequired requires, so returning response:{}for request:{}")
                    .setArguments(unauthorizedResponse, request)
            );
        }
        responseWriter.writeResponse(request, unauthorizedResponse, false);
        return false;
    }

    private static boolean hasClientCertificate(HttpServletRequest httpServletRequest) {
        return httpServletRequest.getAttribute(CLIENT_CERTIFICATE_ATTRIBUTE) instanceof X509Certificate[] certificates
            && certificates.length > 0
            && certificates[0] != null;
    }
}
