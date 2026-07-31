package io.haifa.agent.application.project.product.coding;

import io.haifa.agent.core.reference.PrincipalRef;
import io.haifa.agent.core.reference.TenantRef;
import io.haifa.agent.core.run.AgentRunId;
import io.haifa.agent.core.session.AgentSessionId;
import io.haifa.agent.core.session.AgentSessionStatus;
import io.haifa.agent.project.domain.ProjectId;
import io.haifa.agent.runtime.api.RunEventCursor;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface CodingSessionStore {
    CodingModelPreference createModelPreference(CodingModelPreference preference);

    Optional<CodingModelPreference> findModelPreference(AgentSessionId sessionId);

    CodingModelPreference changeModel(
            AgentSessionId sessionId,
            long expectedRevision,
            String modelId,
            String idempotencyKeyDigest,
            String requestDigest,
            Instant updatedAt);

    CodingCommandBinding reserveCommand(CodingCommandBinding candidate);

    CodingCommandBinding completeCommand(String dispatchKey, AgentRunId runId);

    Optional<CodingCommandBinding> findCommandByDispatchKey(String dispatchKey);

    CodingSessionActivity createActivity(CodingSessionActivity activity);

    Optional<CodingSessionActivity> findActivity(AgentSessionId sessionId);

    List<CodingSessionActivity> listActivities(
            TenantRef tenant, PrincipalRef principal, ProjectId projectId, CodingSessionQuery query);

    CodingSessionActivity rename(
            AgentSessionId sessionId, long expectedRevision, String displayName, Instant updatedAt);

    CodingSessionActivity updateStatus(
            AgentSessionId sessionId, long expectedRevision, AgentSessionStatus status, Instant updatedAt);

    CodingSessionActivity reserveActive(
            AgentSessionId sessionId, long expectedRevision, String dispatchKey, Instant updatedAt);

    CodingSessionActivity activateRun(
            AgentSessionId sessionId, String dispatchKey, AgentRunId runId, long runVersion, Instant updatedAt);

    CodingSessionActivity clearActive(
            AgentSessionId sessionId, AgentRunId runId, long expectedRevision, Instant updatedAt);

    CodingFollowUp enqueue(CodingFollowUp candidate);

    Optional<CodingFollowUp> findFollowUp(String followUpId);

    Optional<CodingFollowUp> findFollowUpByDispatchKey(String dispatchKey);

    List<CodingFollowUp> listRestorableFollowUps(AgentSessionId sessionId, int limit);

    Optional<CodingDispatchClaim> claimNextForDispatch(
            AgentSessionId sessionId, long expectedActivityRevision, Instant updatedAt);

    CodingFollowUp markDispatched(String followUpId, long expectedRevision, AgentRunId runId, Instant updatedAt);

    CodingFollowUp restore(String followUpId, long expectedRevision, Instant updatedAt);

    int queuedCount(AgentSessionId sessionId);

    Optional<RunEventCursor> findEventCursor(AgentSessionId sessionId);

    RunEventCursor saveEventCursor(AgentSessionId sessionId, RunEventCursor cursor, Instant updatedAt);
}
