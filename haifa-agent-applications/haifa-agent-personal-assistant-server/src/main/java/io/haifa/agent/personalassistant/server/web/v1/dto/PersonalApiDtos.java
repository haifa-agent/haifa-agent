package io.haifa.agent.personalassistant.server.web.v1.dto;

import java.time.Instant;
import java.util.List;
import java.util.Map;
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
            String assemblyDigest,
            String defaultModelId,
            List<Model> models) {}

    public record CreateConversation(
            String displayName,
            String message,
            String modelId,
            SelectModel modelSelection,
            List<ImageInput> images,
            List<AudioInput> audios) {}

    public record SubmitMessage(String message, List<ImageInput> images, List<AudioInput> audios) {}

    public record ImageInput(String kind, String url, String imageId) {}

    public record AudioInput(String kind, String audioId) {}

    public record UploadedImage(
            String imageId, String mediaType, long sizeBytes, String originalFilename, String sha256) {}

    public record UploadedAudio(
            String audioId, String mediaType, long sizeBytes, String originalFilename, String sha256) {}

    public record RecommendedQuestions(List<String> questions) {}

    public record UpdateConversation(String displayName, String status) {}

    public record Model(
            String id,
            String modelGroupId,
            String modelDisplayName,
            String displayName,
            String providerId,
            String providerDisplayName,
            String apiStyle,
            String apiStyleDisplayName,
            String availability,
            String unavailableReason,
            List<String> capabilities,
            int contextWindow,
            int maxOutputTokens,
            String preferenceSchemaVersion,
            String profileVersion,
            String profileDigest,
            ModelControls controls,
            ModelPreferences recommendedPreferences) {}

    /** Safe model credential projection. Secret and OAuth registration fields are intentionally absent. */
    public record ModelConnection(
            String connectionId,
            String providerId,
            String method,
            String status,
            String accountLabel,
            Long expiresAtEpochMillis,
            String reasonCode,
            boolean apiKeySupported,
            boolean externalLoginSupported,
            boolean logoutSupported,
            boolean unofficialLocalCompatibility) {}

    /** One-shot mutable request buffer; the authentication service clears {@code apiKey} on every path. */
    public record SaveModelApiKey(String providerId, char[] apiKey) {}

    public record ExternalLoginAttempt(
            String attemptId,
            String methodId,
            String mode,
            String state,
            String verificationUri,
            String userCode,
            long expiresAtEpochMillis,
            String reasonCode) {}

    public record ModelControls(
            ResponseModeControl responseMode,
            ReasoningEffortControl reasoningEffort,
            ResponseLengthControl responseLength,
            ApiStyleControl apiStyle) {}

    public record ResponseModeControl(
            String kind,
            boolean visible,
            boolean readOnly,
            List<String> allowedValues,
            String recommendedValue,
            String effectiveSummary,
            String helpText) {}

    public record ReasoningEffortControl(
            String kind,
            boolean visible,
            boolean readOnly,
            List<String> allowedValues,
            String recommendedValue,
            String effectiveSummary,
            String helpText) {}

    public record ResponseLengthControl(
            String kind,
            boolean visible,
            boolean readOnly,
            List<String> allowedValues,
            String recommendedValue,
            String effectiveSummary,
            String helpText) {}

    public record ApiStyleControl(
            String kind,
            boolean visible,
            boolean readOnly,
            List<String> allowedValues,
            String recommendedValue,
            String effectiveSummary,
            String helpText) {}

    public record ModelPreferences(String responseMode, String effort, String responseLength) {}

    public record ModelSelection(Model model, ModelPreferences preferences, long revision, boolean available) {}

    public record SelectModel(
            String modelBindingId,
            String preferenceSchemaVersion,
            String profileVersion,
            String profileDigest,
            ModelPreferences preferences) {}

    public record Conversation(
            String id,
            String displayName,
            String status,
            Optional<String> activeRunId,
            Instant createdAt,
            Instant lastActivityAt,
            long revision,
            ModelSelection model) {}

    public record CreateMission(
            String conversationId,
            String objective,
            List<String> acceptanceCriteria,
            MissionConstraints constraints,
            String mode,
            String selectedSkillId,
            ResearchBrief researchBrief) {}

    public record ResearchBrief(
            String question,
            String scope,
            String timeRange,
            String region,
            String audience,
            List<String> sourcePreferences,
            List<String> exclusions,
            String deliveryFormat) {}

    public record MissionConstraints(Integer maxTasks, Integer maxDependencyDepth, Instant deadlineAt) {}

    public record ReplaceMissionPlan(MissionPlan plan, Boolean regenerate) {}

    public record MissionPlan(List<MissionTask> tasks) {}

    public record MissionTask(
            String taskId,
            Integer ordinal,
            String title,
            String objective,
            List<String> acceptanceCriteria,
            List<String> dependsOn,
            String taskType,
            List<String> requiredSkillIds,
            String resultSchemaId,
            String resultSchemaVersion,
            String state) {}

    public record MissionPlanRevision(
            long revision,
            String schemaId,
            String schemaVersion,
            List<MissionTask> tasks,
            Optional<String> plannerSessionId,
            Optional<String> plannerRunId,
            Instant createdAt) {}

    public record MissionModelBinding(
            String modelId,
            String modelDisplayName,
            String providerId,
            String providerDisplayName,
            String configurationDigest) {}

    /**
     * Mission read model. {@code finalResult} remains an encoded compatibility envelope: standard Missions use
     * {@code pa.mission-final-result/v1}; Deep Research deliveries use {@code pa.research-delivery/v2} with a
     * separate Markdown report Artifact.
     */
    public record MissionSnapshot(
            String schemaVersion,
            String missionId,
            String conversationId,
            MissionModelBinding modelBinding,
            String objective,
            List<String> acceptanceCriteria,
            MissionConstraints constraints,
            String mode,
            Optional<ResearchBrief> researchBrief,
            Optional<String> selectedSkillId,
            Optional<String> selectedSkillBinding,
            String state,
            Optional<MissionPlanRevision> plan,
            List<MissionTask> tasks,
            Optional<String> blocker,
            List<String> artifacts,
            List<String> sources,
            Optional<String> finalResult,
            long version,
            Instant createdAt,
            Instant updatedAt,
            Optional<Instant> confirmedAt,
            Optional<Instant> finishedAt,
            long pollAfterMs,
            MissionExecution execution) {}

    public record MissionExecution(
            String dispatcherStatus,
            boolean recovering,
            boolean allTasksSettled,
            int completedTasks,
            int blockedTasks,
            Optional<String> currentTaskId,
            Optional<MissionAttempt> latestAttempt) {}

    public record MissionAttempt(
            String taskId,
            int attemptNo,
            String state,
            Optional<String> sessionId,
            Optional<String> runId,
            Optional<String> failureCode,
            Instant updatedAt) {}

    public record MissionPage(List<MissionSnapshot> items, Optional<String> nextCursor) {}

    public record CancelMission(String reason) {}

    public record Turn(
            String id,
            String role,
            Optional<String> runId,
            long sequence,
            String text,
            List<TurnImage> images,
            List<TurnAudio> audios,
            Instant createdAt) {}

    public record TurnImage(
            String kind,
            Optional<String> url,
            Optional<String> imageId,
            Optional<String> mediaType,
            long sizeBytes,
            String originalFilename) {}

    public record TurnAudio(String audioId, String mediaType, long sizeBytes, String originalFilename) {}

    public record Usage(
            long inputTokens,
            long outputTokens,
            long totalTokens,
            long cachedInputTokens,
            long modelCalls,
            long toolCalls) {}

    public record ExecutionError(
            String code,
            String message,
            String category,
            String retryability,
            Map<String, Object> details,
            Optional<String> diagnosticId,
            Instant occurredAt) {}

    public record Run(
            String id,
            String conversationId,
            String status,
            long version,
            Instant updatedAt,
            Optional<String> output,
            Optional<String> resultSummary,
            Optional<String> errorCode,
            Optional<ExecutionError> error,
            Optional<Plan> plan,
            Usage usage) {}

    public record Plan(String id, String objective, List<Todo> items, long revision, Instant updatedAt) {}

    public record Todo(
            String id,
            String title,
            String priority,
            String status,
            Optional<Instant> startedAt,
            Optional<Instant> completedAt) {}

    public record Activity(
            String activityId,
            String eventId,
            Optional<String> parentActivityId,
            String runId,
            String kind,
            String displayName,
            String safeTargetSummary,
            String status,
            Optional<Instant> requestedAt,
            Optional<Instant> startedAt,
            Optional<Instant> completedAt,
            Instant occurredAt,
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
            Optional<Instant> expiresAt) {}

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

    public record Error(String code, String message, String correlationId, String diagnosticId, List<String> actions) {
        public Error(String code, String message, String correlationId) {
            this(code, message, correlationId, correlationId, List.of());
        }

        public Error {
            actions = List.copyOf(actions == null ? List.of() : actions);
        }
    }
}
