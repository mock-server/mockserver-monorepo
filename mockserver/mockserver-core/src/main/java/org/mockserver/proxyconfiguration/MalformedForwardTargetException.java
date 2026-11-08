package org.mockserver.proxyconfiguration;

/**
 * Thrown when a forward or proxy target is refused because it cannot be read as exactly one destination: a Host
 * header, socket address or remote address that is not exactly one {@code host} or {@code host:port}, or more than one
 * Host header where the destination is taken from it. It can be thrown with no forward-target check configured, so
 * its message names no policy.
 */
public class MalformedForwardTargetException extends ForwardTargetBlockedException {

    public MalformedForwardTargetException(String message) {
        super(message);
    }

    public MalformedForwardTargetException(String message, Throwable cause) {
        super(message, cause);
    }
}
