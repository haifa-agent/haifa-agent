package io.haifa.agent.application.project.tool.web;

import java.util.Objects;

public final class WebProviderException extends RuntimeException {
    private final WebFailureCode failureCode;
    private final WebDispatchState dispatchState;

    public WebProviderException(WebFailureCode failureCode, WebDispatchState dispatchState, String message) {
        this(failureCode, dispatchState, message, null);
    }

    public WebProviderException(
            WebFailureCode failureCode, WebDispatchState dispatchState, String message, Throwable cause) {
        super(WebValues.text(message, "message", 1024), cause);
        this.failureCode = Objects.requireNonNull(failureCode, "failureCode");
        this.dispatchState = Objects.requireNonNull(dispatchState, "dispatchState");
    }

    public WebFailureCode failureCode() {
        return failureCode;
    }

    public WebDispatchState dispatchState() {
        return dispatchState;
    }
}
