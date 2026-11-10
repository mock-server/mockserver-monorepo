package org.mockserver.authentication;

/** Authenticated proxy identity retained in process, never accepted from request JSON or headers. */
public record ProxyAuthenticationContext(String username) {
}
