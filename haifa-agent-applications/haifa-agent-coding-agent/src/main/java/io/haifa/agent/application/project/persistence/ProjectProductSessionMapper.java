package io.haifa.agent.application.project.persistence;

import org.apache.ibatis.annotations.Param;

public interface ProjectProductSessionMapper {
    ProjectProductSessionRow find(@Param("sessionId") String sessionId);

    int insert(@Param("row") ProjectProductSessionRow row);
}
