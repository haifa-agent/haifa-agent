package io.haifa.agent.store.sqlite.mybatis;

import java.time.Instant;
import java.util.List;
import org.apache.ibatis.annotations.Param;

public interface SdkConversationMapper {
    int insertCommand(@Param("row") SdkConversationCommandRow row);

    SdkConversationCommandRow findCommandByDispatchKey(@Param("dispatchKey") String dispatchKey);

    SdkConversationCommandRow findCommandByIdempotency(
            @Param("callerScopeDigest") String callerScopeDigest,
            @Param("operation") String operation,
            @Param("idempotencyKeyDigest") String idempotencyKeyDigest);

    int completeCommand(
            @Param("dispatchKey") String dispatchKey,
            @Param("runId") String runId,
            @Param("resultRevision") long resultRevision);

    int insertConversation(@Param("row") SdkConversationRow row);

    SdkConversationRow findConversation(@Param("sessionId") String sessionId);

    List<SdkConversationRow> listConversations(
            @Param("tenantId") String tenantId,
            @Param("principalId") String principalId,
            @Param("principalType") String principalType,
            @Param("statuses") List<String> statuses,
            @Param("text") String text,
            @Param("afterAt") Instant afterAt,
            @Param("afterSessionId") String afterSessionId,
            @Param("limit") int limit);

    int reserveActive(
            @Param("sessionId") String sessionId,
            @Param("expectedRevision") long expectedRevision,
            @Param("dispatchKey") String dispatchKey,
            @Param("at") Instant at);

    int activateRun(
            @Param("sessionId") String sessionId,
            @Param("dispatchKey") String dispatchKey,
            @Param("runId") String runId,
            @Param("runVersion") long runVersion,
            @Param("at") Instant at);

    int releasePendingDispatch(
            @Param("sessionId") String sessionId,
            @Param("dispatchKey") String dispatchKey,
            @Param("expectedRevision") long expectedRevision,
            @Param("at") Instant at);

    int clearActive(
            @Param("sessionId") String sessionId,
            @Param("runId") String runId,
            @Param("expectedRevision") long expectedRevision,
            @Param("at") Instant at);

    int rename(
            @Param("sessionId") String sessionId,
            @Param("expectedRevision") long expectedRevision,
            @Param("displayName") String displayName,
            @Param("at") Instant at);

    int changeStatus(
            @Param("sessionId") String sessionId,
            @Param("expectedRevision") long expectedRevision,
            @Param("expected") String expected,
            @Param("target") String target,
            @Param("at") Instant at);
}
