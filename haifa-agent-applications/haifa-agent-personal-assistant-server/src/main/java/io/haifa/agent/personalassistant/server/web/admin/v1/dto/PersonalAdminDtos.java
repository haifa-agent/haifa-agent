package io.haifa.agent.personalassistant.server.web.admin.v1.dto;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Read-only, loopback Admin wire contracts containing only safe operational diagnostics. */
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

    public record Capabilities(
            String toolCatalogDigest,
            String skillCatalogDigest,
            String skillResolutionPolicy,
            List<Capability> registrations) {}

    public record Capability(
            String id,
            String kind,
            String name,
            String displayName,
            String description,
            String status,
            String source,
            List<String> tags,
            List<CapabilityAttribute> attributes,
            Map<String, Object> details) {}

    public record CapabilityAttribute(String label, String value, String tone) {}

    public record MissionOperations(
            String dispatcherStatus,
            boolean ready,
            boolean maintenancePaused,
            long recoveryCount,
            long lastReconcileLatencyMillis,
            long lastReconcileAtMillis,
            int schemaVersion,
            Map<String, Long> missionStates,
            long activeMissions,
            long activeTaskRuns,
            long unsettledAttempts,
            long pendingOutbox,
            Optional<Long> oldestOutboxAgeMillis,
            long blockedTasks,
            long outcomeUnknownAttempts,
            long budgetExhaustedTasks,
            long modelTokens,
            long modelCalls,
            long toolCalls,
            long duplicatePrevented,
            long databaseBytes,
            long artifactBytes,
            long artifactFiles,
            boolean databaseCapacityWarning,
            boolean artifactCapacityWarning,
            String capacityBlockerCode,
            String retentionBoundary) {}

    public record UpgradeReadiness(
            boolean ready, List<String> blockerCodes, int schemaVersion, String requiredAction) {}

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
