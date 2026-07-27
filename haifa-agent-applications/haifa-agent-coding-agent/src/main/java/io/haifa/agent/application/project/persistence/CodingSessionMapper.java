package io.haifa.agent.application.project.persistence;

import java.time.Instant;
import java.util.List;
import org.apache.ibatis.annotations.Param;

public interface CodingSessionMapper {
    CodingSessionCommandRow findCommand(
            @Param("callerScopeDigest") String callerScopeDigest,
            @Param("operation") String operation,
            @Param("idempotencyKeyDigest") String idempotencyKeyDigest);

    CodingSessionCommandRow findCommandByDispatchKey(@Param("dispatchKey") String dispatchKey);

    int insertCommand(@Param("row") CodingSessionCommandRow row);

    int completeCommand(@Param("dispatchKey") String dispatchKey, @Param("runId") String runId);

    CodingSessionActivityRow findActivity(@Param("sessionId") String sessionId);

    int insertActivity(@Param("row") CodingSessionActivityRow row);

    List<CodingSessionActivityRow> listActivities(
            @Param("tenantId") String tenantId,
            @Param("principalId") String principalId,
            @Param("principalType") String principalType,
            @Param("projectId") String projectId,
            @Param("textLike") String textLike,
            @Param("afterAt") Instant afterAt,
            @Param("afterSessionId") String afterSessionId,
            @Param("limit") int limit);

    int reserveActive(
            @Param("sessionId") String sessionId,
            @Param("expectedRevision") long expectedRevision,
            @Param("dispatchKey") String dispatchKey,
            @Param("updatedAt") Instant updatedAt);

    int activateRun(
            @Param("sessionId") String sessionId,
            @Param("dispatchKey") String dispatchKey,
            @Param("runId") String runId,
            @Param("runVersion") long runVersion,
            @Param("updatedAt") Instant updatedAt);

    int clearActive(
            @Param("sessionId") String sessionId,
            @Param("runId") String runId,
            @Param("expectedRevision") long expectedRevision,
            @Param("updatedAt") Instant updatedAt);

    CodingFollowUpRow findFollowUp(@Param("followUpId") String followUpId);

    CodingFollowUpRow findFollowUpByDispatchKey(@Param("dispatchKey") String dispatchKey);

    List<CodingFollowUpRow> listRestorableFollowUps(@Param("sessionId") String sessionId, @Param("limit") int limit);

    CodingFollowUpRow findFollowUpByIdempotency(
            @Param("sessionId") String sessionId, @Param("idempotencyKeyDigest") String idempotencyKeyDigest);

    CodingFollowUpRow findDispatchableFollowUp(@Param("sessionId") String sessionId);

    long nextFollowUpSequence(@Param("sessionId") String sessionId);

    int insertFollowUp(@Param("row") CodingFollowUpRow row);

    int claimFollowUp(
            @Param("followUpId") String followUpId,
            @Param("expectedRevision") long expectedRevision,
            @Param("updatedAt") Instant updatedAt);

    int markFollowUpDispatched(
            @Param("followUpId") String followUpId,
            @Param("expectedRevision") long expectedRevision,
            @Param("runId") String runId,
            @Param("updatedAt") Instant updatedAt);

    int restoreFollowUp(
            @Param("followUpId") String followUpId,
            @Param("expectedRevision") long expectedRevision,
            @Param("updatedAt") Instant updatedAt);

    int queuedCount(@Param("sessionId") String sessionId);

    CodingSessionEventCursorRow findEventCursor(@Param("sessionId") String sessionId);

    int upsertEventCursor(@Param("row") CodingSessionEventCursorRow row);
}
