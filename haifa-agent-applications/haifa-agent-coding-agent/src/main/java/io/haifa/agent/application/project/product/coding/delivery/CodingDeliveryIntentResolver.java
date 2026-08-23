package io.haifa.agent.application.project.product.coding.delivery;

import io.haifa.agent.application.project.product.coding.CodingCommandBinding;
import io.haifa.agent.application.project.product.coding.CodingSessionStore;
import io.haifa.agent.core.run.AgentRun;
import io.haifa.agent.core.run.AgentRunId;
import io.haifa.agent.runtime.core.storage.RunStateRepository;
import java.util.Objects;

/** Resolves the product-owned intent frozen before Runtime dispatch, including the pre-bind Saga window. */
public final class CodingDeliveryIntentResolver {
    private final CodingSessionStore codingSessions;
    private final RunStateRepository runs;

    public CodingDeliveryIntentResolver(CodingSessionStore codingSessions, RunStateRepository runs) {
        this.codingSessions = Objects.requireNonNull(codingSessions, "codingSessions must not be null");
        this.runs = Objects.requireNonNull(runs, "runs must not be null");
    }

    public CodingDeliveryIntent resolve(AgentRun run) {
        Objects.requireNonNull(run, "run must not be null");
        return resolve(run.id());
    }

    public CodingDeliveryIntent resolve(AgentRunId runId) {
        Objects.requireNonNull(runId, "runId must not be null");
        return codingSessions
                .findCommandByRunId(runId)
                .or(() -> runs.find(runId).flatMap(run -> codingSessions.findPendingCommand(run.sessionId())))
                .map(CodingCommandBinding::deliveryIntent)
                .orElse(CodingDeliveryIntent.WORKTREE_ONLY);
    }
}
