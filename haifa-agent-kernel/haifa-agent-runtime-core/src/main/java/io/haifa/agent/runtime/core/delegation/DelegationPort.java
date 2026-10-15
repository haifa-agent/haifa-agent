package io.haifa.agent.runtime.core.delegation;

import io.haifa.agent.core.agent.AgentDefinitionId;
import io.haifa.agent.core.run.AgentRun;
import io.haifa.agent.core.tool.ToolCallId;
import java.util.List;
import java.util.Objects;

/**
 * Delegation boundary: every delegation Tool Call of one model response becomes one ordinary child
 * {@link AgentRun}. There is exactly one execution path; the calling parent thread waits until every child of
 * the batch is terminal.
 */
public interface DelegationPort {
    /**
     * Creates, or re-attaches to, one child run per request and runs them in parallel within the parent's
     * {@code maxParallelChildren} and the process capacity; requests beyond those limits wait in order. Returns
     * after every started child reached a terminal state. Each outcome is reported to {@code listener} on the
     * calling thread as soon as it is known.
     *
     * <p>When the parent must stop (cancel, timeout or its wall-time limit), the port requests termination of
     * every started child, reports requests that never started through {@link Listener#notStarted}, and throws
     * {@link io.haifa.agent.runtime.core.control.CancellationObservedException}.
     */
    void executeChildren(AgentRun parent, List<ChildRunRequest> requests, Listener listener);

    /** True while any child run of {@code parent} is not terminal. */
    boolean hasPendingChildren(AgentRun parent);

    /** Requests termination of every non-terminal child run of {@code parent}; never waits for them. */
    void terminateChildren(AgentRun parent);

    /** One validated delegation request, correlated with the parent Tool Call that produced it. */
    record ChildRunRequest(ToolCallId toolCallId, AgentDefinitionId childDefinitionId, String objective, String brief) {
        public ChildRunRequest {
            toolCallId = Objects.requireNonNull(toolCallId, "toolCallId must not be null");
            childDefinitionId = Objects.requireNonNull(childDefinitionId, "childDefinitionId must not be null");
            objective = requireText(objective, "objective");
            brief = requireText(brief, "brief");
        }

        private static String requireText(String value, String field) {
            String normalized =
                    Objects.requireNonNull(value, field + " must not be null").trim();
            if (normalized.isEmpty()) throw new IllegalArgumentException(field + " must not be blank");
            return normalized;
        }
    }

    /** Receives per-request outcomes on the parent's own execution thread. */
    interface Listener {
        /** The child run reached a terminal state. */
        void terminal(ToolCallId toolCallId, AgentRun child);

        /** The request could not create a child run; {@code safeReason} is repairable model-facing text. */
        void rejected(ToolCallId toolCallId, String safeReason);

        /** The parent stopped before this request started a child run. */
        void notStarted(ToolCallId toolCallId);
    }
}
