package io.haifa.agent.application.project.persistence;

import java.time.Instant;
import java.util.List;
import org.apache.ibatis.annotations.Param;

public interface CodingWorkspaceRegistryMapper {
    CodingWorkspaceRegistryRow find(@Param("projectId") String projectId, @Param("workspaceRef") String workspaceRef);

    List<CodingWorkspaceRegistryRow> list(@Param("projectId") String projectId);

    int insert(@Param("row") CodingWorkspaceRegistryRow row);

    int update(@Param("row") CodingWorkspaceRegistryRow row, @Param("expectedVersion") long expectedVersion);

    int disableCorruptLocation(
            @Param("projectId") String projectId,
            @Param("workspaceRef") String workspaceRef,
            @Param("expectedVersion") long expectedVersion,
            @Param("disabledAt") Instant disabledAt,
            @Param("reasonCode") String reasonCode);
}
