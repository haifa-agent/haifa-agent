package io.haifa.agent.sandbox.api;

public interface SandboxSession extends AutoCloseable {
    SandboxSessionId id();

    SandboxProcessResult execute(SandboxExecution execution);

    default SandboxProcessResult execute(
            SandboxExecution execution, io.haifa.agent.execution.api.ExecutionOutputObserver observer) {
        SandboxProcessResult result = execute(execution);
        observer.onOutput(new io.haifa.agent.execution.api.ProcessOutputChunk(
                io.haifa.agent.execution.api.ExecutionOutputChannel.STDOUT,
                result.stdout(),
                true,
                result.stdoutTruncated()));
        observer.onOutput(new io.haifa.agent.execution.api.ProcessOutputChunk(
                io.haifa.agent.execution.api.ExecutionOutputChannel.STDERR,
                result.stderr(),
                true,
                result.stderrTruncated()));
        return result;
    }

    default SandboxManagedProcess openManagedProcess(SandboxExecution execution) {
        throw new UnsupportedOperationException("managed process sessions are not supported");
    }

    boolean cancel();

    @Override
    void close();
}
