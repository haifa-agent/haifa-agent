package io.haifa.agent.personalassistant.server.web.admin.v1.dto;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Read-only, loopback Admin wire contracts. These DTOs intentionally permit sensitive diagnostic content. */
public final class PersonalAdminDtos {
    private PersonalAdminDtos() {}

    public record Index(String product, String apiVersion, boolean readOnly, String sensitiveDataWarning) {}

    public record Session(
            String id,
            String status,
            Instant createdAt,
            Instant updatedAt,
            long runCount,
            Optional<String> latestRunStatus) {}

    public record Run(
            String id,
            String sessionId,
            String status,
            String objective,
            Instant createdAt,
            Instant updatedAt,
            Optional<Instant> completedAt,
            Optional<String> errorCode) {}

    public record Trace(String sessionId, String runId, Node root, List<Node> nodes, Optional<String> failureNodeId) {}

    public record Node(
            String id,
            Optional<String> parentId,
            String kind,
            String label,
            Optional<String> status,
            Optional<Instant> startedAt,
            Optional<Instant> completedAt,
            Optional<Long> durationMillis,
            Optional<Long> sequence,
            Optional<String> summary,
            Map<String, Object> details) {}
}
