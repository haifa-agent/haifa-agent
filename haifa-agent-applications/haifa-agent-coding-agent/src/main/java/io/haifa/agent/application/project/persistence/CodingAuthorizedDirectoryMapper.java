package io.haifa.agent.application.project.persistence;

import java.time.Instant;
import java.util.List;
import org.apache.ibatis.annotations.Param;

public interface CodingAuthorizedDirectoryMapper {
    CodingAuthorizedDirectoryRow find(@Param("projectId") String projectId, @Param("workspaceRef") String workspaceRef);

    List<CodingAuthorizedDirectoryRow> list(@Param("projectId") String projectId);

    int insert(@Param("row") CodingAuthorizedDirectoryRow row);

    int update(@Param("row") CodingAuthorizedDirectoryRow row, @Param("expectedVersion") long expectedVersion);

    int disable(
            @Param("projectId") String projectId,
            @Param("workspaceRef") String workspaceRef,
            @Param("expectedVersion") long expectedVersion,
            @Param("disabledAt") Instant disabledAt,
            @Param("reasonCode") String reasonCode);
}
