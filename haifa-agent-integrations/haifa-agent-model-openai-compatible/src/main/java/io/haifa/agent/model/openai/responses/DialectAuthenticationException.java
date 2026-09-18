package io.haifa.agent.model.openai.responses;

final class DialectAuthenticationException extends RuntimeException {
    private final String providerCode;

    DialectAuthenticationException(String providerCode, String message) {
        super(message);
        this.providerCode = providerCode;
    }

    DialectAuthenticationException(String providerCode, String message, Throwable cause) {
        super(message, cause);
        this.providerCode = providerCode;
    }

    String providerCode() {
        return providerCode;
    }
}
