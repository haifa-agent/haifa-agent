package io.haifa.agent.sandbox.host;

public final class HostSandboxException extends RuntimeException {
    private final String code;

    public HostSandboxException(String code, String safeMessage) {
        super(safeMessage);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
