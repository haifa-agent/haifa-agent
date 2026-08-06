package io.haifa.agent.sandbox.host;

public final class HostSandboxException extends io.haifa.agent.sandbox.api.SandboxException {
    public HostSandboxException(String code, String safeMessage) {
        super(code, safeMessage);
    }

    public HostSandboxException(String code, String safeMessage, Throwable cause) {
        super(code, safeMessage, cause);
    }
}
