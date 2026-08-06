package io.haifa.agent.execution.api;

import java.util.Optional;

public interface ExecutionBroker {
    ExecutionResult execute(ExecutionRequest request);

    default ExecutionResult execute(ExecutionRequest request, ExecutionOutputObserver observer) {
        return execute(request);
    }

    /**
     * Executes a request and reports when the underlying process has actually started.
     *
     * <p>The compatibility default reports dispatch only after a synchronous implementation returns. Brokers that
     * launch an external process must override this method and signal immediately after a successful launch.
     */
    default ExecutionResult execute(
            ExecutionRequest request,
            ExecutionOutputObserver outputObserver,
            ExecutionDispatchObserver dispatchObserver) {
        ExecutionResult result = execute(request, outputObserver);
        dispatchObserver.dispatched();
        return result;
    }

    default ManagedProcessSession openManagedSession(ManagedProcessRequest request) {
        throw new UnsupportedOperationException("managed process sessions are not supported");
    }

    boolean cancel(ExecutionId id);

    Optional<ExecutionResult> find(ExecutionId id);
}
