package io.haifa.agent.application.project.persistence;

import java.util.List;
import org.apache.ibatis.annotations.Param;

public interface CodingWorkspaceAccessMapper {
    CodingWorkspaceAccessRow find(
            @Param("tenantId") String tenantId,
            @Param("principalType") String principalType,
            @Param("principalId") String principalId,
            @Param("workspaceId") String workspaceId);

    List<CodingWorkspaceAccessRow> list(
            @Param("tenantId") String tenantId,
            @Param("principalType") String principalType,
            @Param("principalId") String principalId);

    int insertIfAbsent(@Param("row") CodingWorkspaceAccessRow row);

    int replace(@Param("row") CodingWorkspaceAccessRow row);

    int delete(
            @Param("tenantId") String tenantId,
            @Param("principalType") String principalType,
            @Param("principalId") String principalId,
            @Param("workspaceId") String workspaceId);
}
