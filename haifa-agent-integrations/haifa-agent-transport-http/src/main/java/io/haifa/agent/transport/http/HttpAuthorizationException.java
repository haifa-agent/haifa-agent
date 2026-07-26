package io.haifa.agent.transport.http;

/** Resource-hiding authorization failure raised by a host authorizer. */
public final class HttpAuthorizationException extends RuntimeException {
    public HttpAuthorizationException() {
        super("The resource does not exist or is not visible");
    }
}
