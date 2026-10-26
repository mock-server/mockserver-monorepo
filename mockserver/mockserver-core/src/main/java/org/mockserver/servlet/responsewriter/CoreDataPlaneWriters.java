package org.mockserver.servlet.responsewriter;

import jakarta.servlet.http.HttpServletResponse;
import org.mockserver.configuration.Configuration;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.mock.HttpState;

/**
 * Builds the servlet writer used when an extension request filter or response transformer is loaded. Called only once
 * {@code HttpState.dataPlaneHooks()} is known to be non-null, and returns the base writer type, so a servlet never
 * needs the transforming class unless one is loaded. ({@code InProcessMockServer} builds its own, as its writer is a
 * private nested class.)
 */
public final class CoreDataPlaneWriters {

    private CoreDataPlaneWriters() {
    }

    /**
     * @param mockServerLogger the logger the servlet's writer is built with
     * @param proxyRequest     whether the servlet serves requests sent to it as a proxy
     */
    public static ServletResponseWriter servlet(Configuration configuration, MockServerLogger mockServerLogger, HttpServletResponse httpServletResponse, HttpState httpState, boolean proxyRequest) {
        return new TransformingServletResponseWriter(configuration, mockServerLogger, httpServletResponse, httpState.getScheduler(), httpState.dataPlaneHooks(), httpState.getMockServerLogger(), proxyRequest);
    }
}
