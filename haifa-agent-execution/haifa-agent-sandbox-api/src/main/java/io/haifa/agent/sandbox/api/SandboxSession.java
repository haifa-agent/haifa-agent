package io.haifa.agent.sandbox.api;

public interface SandboxSession extends AutoCloseable {
    SandboxSessionId id();

    SandboxProcessResult execute(SandboxExecution execution);

    default SandboxManagedProcess openManagedProcess(SandboxExecution execution) {
        throw new UnsupportedOperationException("managed process sessions are not supported");
    }

    boolean cancel();

    @Override
    void close();
}
