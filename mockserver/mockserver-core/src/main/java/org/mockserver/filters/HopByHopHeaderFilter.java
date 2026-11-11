package org.mockserver.filters;

import org.mockserver.model.Header;
import org.mockserver.model.Headers;
import org.mockserver.model.HttpRequest;
import org.mockserver.model.HttpResponse;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * @author jamesdbloom
 */
public class HopByHopHeaderFilter {

    private static final List<String> requestHeadersToRemove = Arrays.asList(
        "proxy-connection",
        "connection",
        "keep-alive",
        "transfer-encoding",
        "te",
        "trailer",
        "proxy-authorization",
        "proxy-authenticate",
        "upgrade"
    );

    private static final List<String> responseHeadersToRemove = Arrays.asList(
        "proxy-connection",
        "connection",
        "keep-alive",
        "transfer-encoding",
        "content-length",
        "te",
        "trailer",
        "upgrade"
    );

    public HttpRequest onRequest(HttpRequest request) {
        if (request != null) {
            Headers headers = new Headers();
            for (Header header : request.getHeaderList()) {
                if (!requestHeadersToRemove.contains(header.getName().getValue().toLowerCase(Locale.ENGLISH))) {
                    headers.withEntry(header);
                }
            }
            // empty when every header was hop-by-hop, and then the clone has none
            return request.clone().withHeaders(headers);
        } else {
            return null;
        }
    }

    public HttpResponse onResponse(HttpResponse response) {
        if (response != null) {
            Headers headers = new Headers();
            for (Header header : response.getHeaderList()) {
                if (!responseHeadersToRemove.contains(header.getName().getValue().toLowerCase(Locale.ENGLISH))) {
                    headers.withEntry(header);
                }
            }
            HttpResponse clonedResponse = response.clone();
            // deliberately unlike onRequest: a response whose every header is hop-by-hop keeps them, as it always has;
            // dropping them broke HEAD responses and recorded responses that carry only content-length and connection
            if (!headers.isEmpty()) {
                clonedResponse.withHeaders(headers);
            }
            return clonedResponse;
        } else {
            return null;
        }
    }

}
