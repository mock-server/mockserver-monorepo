package org.mockserver.model;

/**
 * Headers that survive the data-plane copy rules unchanged but throw as soon as {@code processAction} reads them, so a
 * request filter can make the continuation after it throw on the event loop. In this package because the copy hook,
 * {@code Headers.deepCopy()}, is package-private.
 */
public class ContinuationThrowingHeaders extends Headers {

    public ContinuationThrowingHeaders(Headers headers) {
        super(headers.getMultimap());
    }

    @Override
    Headers deepCopy() {
        return new ContinuationThrowingHeaders(this);
    }

    @Override
    public boolean containsEntry(NottableString name, NottableString value) {
        throw new IllegalStateException("thrown by processAction after the request filter");
    }
}
