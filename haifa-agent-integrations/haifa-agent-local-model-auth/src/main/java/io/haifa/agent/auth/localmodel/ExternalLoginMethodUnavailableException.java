package io.haifa.agent.auth.localmodel;

/** Stable, secret-free login-method failure. */
public final class ExternalLoginMethodUnavailableException extends RuntimeException {
    private final String reasonCode;

    public ExternalLoginMethodUnavailableException(String reasonCode) {
        super(ExternalLoginMethodDescriptor.safeText(reasonCode, "reasonCode", 96));
        this.reasonCode = getMessage();
    }

    public String reasonCode() {
        return reasonCode;
    }
}
