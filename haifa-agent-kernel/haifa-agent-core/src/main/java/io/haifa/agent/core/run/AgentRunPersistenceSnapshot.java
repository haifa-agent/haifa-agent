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
        Instant queuedAt,
        Instant startedAt,
        Instant suspendedAt,
        Instant resumedAt,
        Instant completedAt,
        Instant updatedAt,
        long version) {}
