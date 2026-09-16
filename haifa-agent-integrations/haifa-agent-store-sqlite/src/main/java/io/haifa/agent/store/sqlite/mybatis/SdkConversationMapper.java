package io.haifa.agent.store.sqlite.mybatis;

import java.time.Instant;
import java.util.List;
import org.apache.ibatis.annotations.Param;

public interface SdkConversationMapper {
    int insertConversation(@Param("row") SdkConversationRow row);

    SdkConversationRow findConversation(@Param("sessionId") String sessionId);

    List<SdkConversationRow> listConversations(
            @Param("tenantId") String tenantId,
            @Param("principalId") String principalId,
            @Param("principalType") String principalType,
            @Param("text") String text,
            @Param("afterAt") Instant afterAt,
            @Param("afterSessionId") String afterSessionId,
            @Param("limit") int limit);

    int touchLastActivity(@Param("sessionId") String sessionId, @Param("at") Instant at);

    int rename(
            @Param("sessionId") String sessionId,
            @Param("expectedRevision") long expectedRevision,
            @Param("displayName") String displayName,
            @Param("at") Instant at);

    int changeStatus(
            @Param("sessionId") String sessionId,
            @Param("expectedRevision") long expectedRevision,
            @Param("at") Instant at);
}
