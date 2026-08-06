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

    /**
     * Executes while exposing the irreversible process-launch boundary.
     *
     * <p>Process-backed implementations must override this method and signal immediately after the process starts.
     * The compatibility default is suitable only for synchronous implementations that return an authoritative result.
     */
    default SandboxProcessResult execute(
            SandboxExecution execution,
            io.haifa.agent.execution.api.ExecutionOutputObserver outputObserver,
            io.haifa.agent.execution.api.ExecutionDispatchObserver dispatchObserver) {
        SandboxProcessResult result = execute(execution, outputObserver);
        dispatchObserver.dispatched();
        return result;
    }

    default SandboxManagedProcess openManagedProcess(SandboxExecution execution) {
        throw new UnsupportedOperationException("managed process sessions are not supported");
    }

    boolean cancel();

    @Override
    void close();
}
