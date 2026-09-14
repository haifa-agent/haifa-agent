package io.haifa.agent.application.project.product.coding.delivery;

import io.haifa.agent.application.project.product.coding.CodingCommandBinding;
import io.haifa.agent.application.project.product.coding.CodingSessionStore;
import io.haifa.agent.core.run.AgentRun;
import io.haifa.agent.core.run.AgentRunId;
import io.haifa.agent.runtime.core.storage.RunStateRepository;
import java.util.Objects;

/** Resolves the repository side-effect upper bound frozen before Runtime dispatch. */
public final class CodingDeliveryIntentResolver {
    private final CodingSessionStore codingSessions;
    private final RunStateRepository runs;

    public CodingDeliveryIntentResolver(CodingSessionStore codingSessions, RunStateRepository runs) {
        this.codingSessions = Objects.requireNonNull(codingSessions, "codingSessions must not be null");
        this.runs = Objects.requireNonNull(runs, "runs must not be null");
    }

    public CodingDeliveryIntent resolve(AgentRun run) {
        AgentRun current = Objects.requireNonNull(run, "run must not be null");
        return codingSessions
                .findCommandByRunId(current.id())
                .or(() -> codingSessions.findPendingCommand(current.sessionId()))
                .map(CodingCommandBinding::deliveryIntent)
                .orElse(CodingDeliveryIntent.WORKTREE_ONLY);
    }

    public CodingDeliveryIntent resolve(AgentRunId runId) {
        AgentRunId current = Objects.requireNonNull(runId, "runId must not be null");
        return codingSessions
                .findCommandByRunId(current)
                .or(() -> runs.find(current).flatMap(run -> codingSessions.findPendingCommand(run.sessionId())))
                .map(CodingCommandBinding::deliveryIntent)
                .orElse(CodingDeliveryIntent.WORKTREE_ONLY);
    }
}
