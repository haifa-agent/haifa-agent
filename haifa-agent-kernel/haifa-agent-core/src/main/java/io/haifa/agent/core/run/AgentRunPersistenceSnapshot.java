package io.haifa.agent.core.run;

import io.haifa.agent.core.agent.AgentDefinitionId;
import io.haifa.agent.core.agent.AgentDefinitionVersion;
import io.haifa.agent.core.error.AgentError;
import io.haifa.agent.core.reference.InteractionRequestRef;
import io.haifa.agent.core.reference.PrincipalRef;
import io.haifa.agent.core.reference.ProjectRef;
import io.haifa.agent.core.reference.RunConfigurationSnapshotRef;
import io.haifa.agent.core.reference.TenantRef;
import io.haifa.agent.core.session.AgentSessionId;
import java.time.Instant;

/** Immutable, versioned persistence contract for controlled {@link AgentRun} reconstitution. */
public record AgentRunPersistenceSnapshot(
        String schemaVersion,
        AgentRunId id,
        AgentRunId rootRunId,
        AgentRunId parentRunId,
        AgentSessionId sessionId,
        ProjectRef project,
        TenantRef tenant,
        PrincipalRef principal,
        AgentDefinitionId agentDefinitionId,
        AgentDefinitionVersion agentDefinitionVersion,
        String productProfileId,
        String productProfileVersion,
        AgentRunType runType,
        String invocationMode,
        int depth,
        String objective,
        AgentRunBudget budget,
        AgentRunLimits limits,
        RunConfigurationSnapshotRef configurationSnapshot,
        Instant createdAt,
        String status,
        AgentRunUsage usage,
        AgentRunResult result,
        AgentError error,
        InteractionRequestRef waitingFor,
        RunTerminationReason terminationReason,
        long accumulatedHumanWaitMillis,
        Instant humanWaitStartedAt,
        Instant queuedAt,
        Instant startedAt,
        Instant suspendedAt,
        Instant resumedAt,
        Instant completedAt,
        Instant updatedAt,
        long version) {

    /** Backward-compatible constructor for snapshots created before human-wait timing was persisted. */
    public AgentRunPersistenceSnapshot(
            String schemaVersion,
            AgentRunId id,
            AgentRunId rootRunId,
            AgentRunId parentRunId,
            AgentSessionId sessionId,
            ProjectRef project,
            TenantRef tenant,
            PrincipalRef principal,
            AgentDefinitionId agentDefinitionId,
            AgentDefinitionVersion agentDefinitionVersion,
            String productProfileId,
            String productProfileVersion,
            AgentRunType runType,
            String invocationMode,
            int depth,
            String objective,
            AgentRunBudget budget,
            AgentRunLimits limits,
            RunConfigurationSnapshotRef configurationSnapshot,
            Instant createdAt,
            String status,
            AgentRunUsage usage,
            AgentRunResult result,
            AgentError error,
            InteractionRequestRef waitingFor,
            RunTerminationReason terminationReason,
            Instant queuedAt,
            Instant startedAt,
            Instant suspendedAt,
            Instant resumedAt,
            Instant completedAt,
            Instant updatedAt,
            long version) {
        this(
                schemaVersion,
                id,
                rootRunId,
                parentRunId,
                sessionId,
                project,
                tenant,
                principal,
                agentDefinitionId,
                agentDefinitionVersion,
                productProfileId,
                productProfileVersion,
                runType,
                invocationMode,
                depth,
                objective,
                budget,
                limits,
                configurationSnapshot,
                createdAt,
                status,
                usage,
                result,
                error,
                waitingFor,
                terminationReason,
                0,
                "WAITING_INTERACTION".equals(status) || "WAITING_APPROVAL".equals(status) ? updatedAt : null,
                queuedAt,
                startedAt,
                suspendedAt,
                resumedAt,
                completedAt,
                updatedAt,
                version);
    }
}
