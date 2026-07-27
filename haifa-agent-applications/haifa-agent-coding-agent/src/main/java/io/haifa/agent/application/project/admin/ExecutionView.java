package io.haifa.agent.application.project.admin;

import java.time.Instant;

public record ExecutionView(
        String executionId,
        String status,
        Integer exitCode,
        String changeSetRef,
        String errorCode,
        Instant startedAt,
        Instant endedAt) {}
