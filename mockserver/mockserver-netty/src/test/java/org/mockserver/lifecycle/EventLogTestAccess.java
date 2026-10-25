package org.mockserver.lifecycle;

import org.mockserver.log.MockServerEventLog;

/**
 * Gives a test outside this package the event log of a running server, which {@link LifeCycle} keeps protected.
 */
public final class EventLogTestAccess {

    private EventLogTestAccess() {
    }

    public static MockServerEventLog eventLog(LifeCycle server) {
        return server.httpState.getMockServerLog();
    }
}
