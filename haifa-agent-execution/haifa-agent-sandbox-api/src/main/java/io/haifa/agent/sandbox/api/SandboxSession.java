package io.haifa.agent.sandbox.api;

public interface SandboxSession extends AutoCloseable {
    SandboxSessionId id();

    SandboxProcessResult execute(SandboxExecution execution);

    boolean cancel();

    @Override
    void close();
}
