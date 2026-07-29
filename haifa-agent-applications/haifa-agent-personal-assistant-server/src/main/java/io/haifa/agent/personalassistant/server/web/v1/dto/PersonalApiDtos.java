package io.haifa.agent.personalassistant.server.web.v1.dto;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** Versioned wire DTOs. Domain and SDK types never cross the HTTP boundary. */
public final class PersonalApiDtos {
    private PersonalApiDtos() {}

    public record Bootstrap(
            String product,
            String apiVersion,
            String connection,
            String caller,
            List<String> capabilities,
            String assemblyDigest) {}

    public record CreateConversation(String displayName, String message) {}

    public record SubmitMessage(String message) {}

    public record UpdateConversation(String displayName, String status) {}

    public record Conversation(
            String id,
            String displayName,
            String status,
            Optional<String> activeRunId,
            Instant createdAt,
            Instant lastActivityAt,
            long revision) {}

    public record Turn(String id, String role, Optional<String> runId, long sequence, String text, Instant createdAt) {}

    public record Usage(
            long inputTokens,
            long outputTokens,
            long totalTokens,
            long cachedInputTokens,
            long modelCalls,
            long toolCalls) {}

    public record Run(
            String id,
            String conversationId,
            String status,
            long version,
            Instant updatedAt,
            Optional<String> output,
            Optional<String> resultSummary,
            Optional<String> errorCode,
            Usage usage) {}

    public record Activity(
            String activityId,
            String runId,
            String kind,
            String displayName,
            String safeTargetSummary,
            String status,
            Instant startedAt,
            Optional<Instant> completedAt,
            String safeResultSummary,
            Optional<String> interactionRef,
            long version) {}

    public record Interaction(
            String id,
            String runId,
            String conversationId,
            long revision,
            String kind,
            String state,
            String title,
            String safePrompt,
            List<String> allowedActions,
            String inputType,
            int maximumCharacters,
            Instant createdAt,
            Instant expiresAt) {}

    public record InteractionResponse(String action, String text) {}

    public record InteractionReceipt(
            String responseId,
            String interactionId,
            String runId,
            String status,
            String interactionState,
            long revision,
            long runVersion) {}

    public record MemoryCandidate(
            String id,
            String kind,
            String subjectKey,
            String content,
            String status,
            Instant updatedAt,
            long revision) {}

    public record Memory(
            String id,
            long version,
            String kind,
            String subjectKey,
            String content,
            String status,
            Instant createdAt,
            Instant updatedAt) {}

    public record RejectMemory(String reason) {}

    public record InvalidateMemory(String reason) {}

    public record StreamEvent(
            String eventId,
            String type,
            String runId,
            Instant occurredAt,
            String value,
            Optional<Activity> activity,
            String source,
            long sequence) {}

    public record Error(String code, String message, String correlationId) {}
}
