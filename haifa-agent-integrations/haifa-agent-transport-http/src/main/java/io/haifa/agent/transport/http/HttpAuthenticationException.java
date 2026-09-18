package io.haifa.agent.transport.http;

/** Safe authentication failure raised by a host resolver. */
public final class HttpAuthenticationException extends RuntimeException {
    public HttpAuthenticationException() {
        super("Authentication is required");
    }
}
