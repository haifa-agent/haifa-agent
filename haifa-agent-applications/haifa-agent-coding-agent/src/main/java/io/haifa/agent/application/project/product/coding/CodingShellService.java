package io.haifa.agent.application.project.product.coding;

import io.haifa.agent.core.session.AgentSessionId;
import java.util.List;

public interface CodingShellService extends AutoCloseable {
    CodingShellPlan plan(AgentSessionId sessionId, String command, boolean includeInContext);

    CodingShellResult execute(String token, boolean approved);

    void discard(String token);

    default boolean recoverable() {
        return false;
    }

    default CodingShellExecution start(String token, boolean approved) {
        throw new UnsupportedOperationException("Recoverable shell execution is unavailable");
    }

    default CodingShellExecution execution(AgentSessionId sessionId, String executionId) {
        throw new UnsupportedOperationException("Recoverable shell execution is unavailable");
    }

    default List<CodingShellExecution> executions(AgentSessionId sessionId) {
        throw new UnsupportedOperationException("Recoverable shell execution is unavailable");
    }

    default CodingShellExecution cancel(AgentSessionId sessionId, String executionId) {
        throw new UnsupportedOperationException("Recoverable shell execution is unavailable");
    }

    @Override
    default void close() {}
}
