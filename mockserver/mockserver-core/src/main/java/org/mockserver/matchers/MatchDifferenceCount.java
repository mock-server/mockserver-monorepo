package org.mockserver.matchers;

import org.mockserver.model.HttpRequest;

public class MatchDifferenceCount {

    private final HttpRequest httpRequest;
    private int failures = 0;

    public MatchDifferenceCount(HttpRequest httpRequest) {
        this.httpRequest = httpRequest;
    }

    public HttpRequest getHttpRequest() {
        return httpRequest;
    }

    @SuppressWarnings("UnusedReturnValue")
    public MatchDifferenceCount incrementFailures() {
        this.failures++;
        return this;
    }

    public int getFailures() {
        return failures;
    }
}
