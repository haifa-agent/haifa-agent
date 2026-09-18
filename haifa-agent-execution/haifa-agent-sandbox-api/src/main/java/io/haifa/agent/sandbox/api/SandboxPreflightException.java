package io.haifa.agent.sandbox.api;

/**
 * Expected sandbox preflight rejection when host or environment capabilities cannot satisfy requirements.
 */
public final class SandboxPreflightException extends SandboxException {
    public SandboxPreflightException(String code, String safeMessage) {
        super(code, safeMessage);
    }

    public SandboxPreflightException(String code, String safeMessage, Throwable cause) {
        super(code, safeMessage, cause);
    }
}
