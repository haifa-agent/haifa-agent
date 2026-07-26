package io.haifa.agent.store.sqlite.mybatis;

import java.util.List;
import org.apache.ibatis.annotations.Param;

/** Explicit mapper boundary for Runtime Store rows. */
public interface RuntimeStoreMapper {

    int insertSession(@Param("row") SessionRow row);

    int updateSession(@Param("row") SessionRow row, @Param("expectedVersion") long expectedVersion);

    SessionRow findSession(@Param("sessionId") String sessionId);

    int insertRun(@Param("row") RunRow row);

    int updateRun(@Param("row") RunRow row, @Param("expectedVersion") long expectedVersion);

    RunRow findRun(@Param("runId") String runId);

    String configurationHash(@Param("configurationRef") String configurationRef);

    int insertAttempt(@Param("row") ExecutionAttemptRow row);

    int updateAttempt(@Param("row") ExecutionAttemptRow row, @Param("expectedVersion") long expectedVersion);

    ExecutionAttemptRow findAttempt(@Param("attemptId") String attemptId);

    ExecutionAttemptRow activeAttempt(@Param("runId") String runId);

    List<ExecutionAttemptRow> attemptsForRun(@Param("runId") String runId);

    long nextMessageSequence(@Param("sessionId") String sessionId);

    int insertMessage(@Param("row") SessionMessageRow row);

    SessionMessageRow findMessage(@Param("messageId") String messageId);

    List<SessionMessageRow> messagesAfter(
            @Param("sessionId") String sessionId, @Param("cursor") long cursor, @Param("limit") int limit);

    List<SessionMessageRow> recentMessages(
            @Param("sessionId") String sessionId, @Param("cursor") long cursor, @Param("limit") int limit);

    Long latestMessageSequence(@Param("sessionId") String sessionId);

    List<SessionMessageRow> messagesForRun(@Param("runId") String runId);

    int redactMessage(@Param("row") SessionMessageRow row);

    int invalidateSummariesForSession(@Param("sessionId") String sessionId);

    int insertStep(@Param("row") StepRow row);

    List<StepRow> stepsForRun(@Param("runId") String runId);

    int insertToolCall(@Param("row") ToolCallRow row);

    List<ToolCallRow> toolCallsForRun(@Param("runId") String runId);

    int insertPlan(@Param("row") PlanRow row);

    int updatePlan(@Param("row") PlanRow row);

    PlanRow findPlan(@Param("runId") String runId);

    int insertOutput(@Param("row") RunOutputRow row);

    RunOutputRow findOutput(@Param("runId") String runId);

    int insertConfiguration(@Param("row") ConfigurationRow row);

    ConfigurationRow findConfiguration(@Param("configurationRef") String configurationRef);

    int insertCheckpoint(@Param("row") CheckpointRow row);

    int insertCheckpointPayload(@Param("row") CheckpointPayloadRow row);

    CheckpointRow latestCheckpoint(@Param("runId") String runId);

    CheckpointRow findCheckpoint(@Param("checkpointId") String checkpointId);

    List<CheckpointRow> checkpointsForRun(@Param("runId") String runId);

    CheckpointPayloadRow findCheckpointPayload(@Param("checkpointId") String checkpointId);

    int ensureEventStream(
            @Param("runId") String runId,
            @Param("headSequence") long headSequence,
            @Param("earliestSequence") long earliestSequence,
            @Param("updatedAt") java.time.Instant updatedAt);

    Long eventHead(@Param("runId") String runId);

    Long eventEarliest(@Param("runId") String runId);

    Long minimumStoredEventSequence(@Param("runId") String runId);

    int advanceEventHead(
            @Param("runId") String runId,
            @Param("expectedHead") long expectedHead,
            @Param("newHead") long newHead,
            @Param("updatedAt") java.time.Instant updatedAt);

    int insertEvent(@Param("row") RuntimeEventRow row);

    List<RuntimeEventRow> eventsForRun(@Param("runId") String runId);

    List<RuntimeEventRow> eventsAfter(
            @Param("runId") String runId,
            @Param("exclusiveSequence") long exclusiveSequence,
            @Param("observedHead") long observedHead,
            @Param("limit") int limit);

    RuntimeEventRow findEventBySequence(@Param("runId") String runId, @Param("sequence") long sequence);

    int deletePublishedOutboxBefore(@Param("runId") String runId, @Param("retainFromSequence") long retainFromSequence);

    int deleteEventsBefore(@Param("runId") String runId, @Param("retainFromSequence") long retainFromSequence);

    int updateEventEarliest(
            @Param("runId") String runId,
            @Param("earliestSequence") long earliestSequence,
            @Param("updatedAt") java.time.Instant updatedAt);

    int insertOutbox(@Param("row") OutboxRow row);

    OutboxRow findOutbox(@Param("eventId") String eventId);

    List<OutboxRow> pendingOutbox();

    int markOutboxPublished(@Param("eventId") String eventId, @Param("publishedAt") java.time.Instant publishedAt);

    int insertOutboxConsumer(
            @Param("consumerId") String consumerId,
            @Param("eventId") String eventId,
            @Param("consumedAt") java.time.Instant consumedAt);

    IdempotencyRow findIdempotency(
            @Param("callerScope") String callerScope, @Param("operation") String operation, @Param("key") String key);

    int insertIdempotency(@Param("row") IdempotencyRow row);

    int markCommandApplied(
            @Param("callerScope") String callerScope,
            @Param("key") String key,
            @Param("updatedAt") java.time.Instant updatedAt);

    int recordCommandResult(@Param("row") IdempotencyRow row);

    ToolJournalRow findToolJournal(@Param("runId") String runId, @Param("key") String key);

    int insertToolJournal(@Param("row") ToolJournalRow row);

    int updateToolJournal(@Param("row") ToolJournalRow row, @Param("expectedState") String expectedState);

    int hasUncertainToolJournal(@Param("runId") String runId);

    int insertInteractionRequest(@Param("row") InteractionRequestRow row);

    int insertInteractionApplication(@Param("requestId") String requestId);

    InteractionRequestRow findInteractionRequest(@Param("requestId") String requestId);

    InteractionRequestRow pendingInteraction(@Param("runId") String runId);

    List<InteractionRequestRow> dueInteractions(
            @Param("runId") String runId, @Param("at") java.time.Instant at, @Param("limit") int limit);

    InteractionResponseRow findInteractionResponse(@Param("responseId") String responseId);

    InteractionResponseRow findInteractionResponseForRequest(@Param("requestId") String requestId);

    InteractionResponseRow findInteractionResponseByIdempotency(
            @Param("callerScope") String callerScope,
            @Param("requestId") String requestId,
            @Param("idempotencyKey") String idempotencyKey);

    int insertInteractionResponse(@Param("row") InteractionResponseRow row);

    int markInteractionResponded(
            @Param("requestId") String requestId,
            @Param("expectedRevision") long expectedRevision,
            @Param("changedAt") java.time.Instant changedAt);

    int transitionInteractionState(
            @Param("requestId") String requestId,
            @Param("expectedRevision") long expectedRevision,
            @Param("expectedState") String expectedState,
            @Param("targetState") String targetState,
            @Param("reasonCode") String reasonCode,
            @Param("changedAt") java.time.Instant changedAt);

    int markInteractionStateApplied(
            @Param("requestId") String requestId,
            @Param("expectedRevision") long expectedRevision,
            @Param("appliedAt") java.time.Instant appliedAt);

    InteractionRequestRow unappliedToolResolution(@Param("runId") String runId);

    int markInteractionApplied(@Param("requestId") String requestId, @Param("appliedAt") java.time.Instant appliedAt);

    int insertRunInput(@Param("row") RunInputRow row);

    RunInputRow findRunInput(@Param("inputId") String inputId);

    RunInputRow findRunInputByIdempotency(
            @Param("callerScope") String callerScope,
            @Param("runId") String runId,
            @Param("idempotencyKey") String idempotencyKey);

    List<RunInputRow> pendingRunInputs(@Param("runId") String runId, @Param("limit") int limit);

    int markRunInputApplied(
            @Param("inputId") String inputId,
            @Param("attemptId") String attemptId,
            @Param("iteration") int iteration,
            @Param("appliedAt") java.time.Instant appliedAt);

    ConversationSummaryRow latestValidSummary(@Param("sessionId") String sessionId);

    ConversationSummaryRow findSummary(@Param("summaryId") String summaryId, @Param("version") long version);

    long latestSummaryVersion(@Param("sessionId") String sessionId);

    int insertSummary(@Param("row") ConversationSummaryRow row);

    int invalidateSummaryContaining(@Param("sessionId") String sessionId, @Param("messageId") String messageId);

    int validSummarySourceCount(@Param("sessionId") String sessionId, @Param("messageIds") List<String> messageIds);

    int upsertMemorySelection(@Param("row") MemorySelectionRow row);

    MemorySelectionRow findMemorySelection(@Param("runId") String runId);

    int insertSkillActivation(@Param("row") SkillActivationRow row);

    SkillActivationRow findSkillActivation(@Param("runId") String runId, @Param("alias") String alias);

    List<SkillActivationRow> skillActivations(@Param("runId") String runId);

    long skillInstructionBytes(@Param("runId") String runId);

    long skillEstimatedTokens(@Param("runId") String runId);

    Long skillResourceBytes(@Param("runId") String runId);

    int insertSkillResourceUsage(@Param("runId") String runId, @Param("updatedAt") java.time.Instant updatedAt);

    int addSkillResourceBytes(
            @Param("runId") String runId,
            @Param("bytes") long bytes,
            @Param("maximum") long maximum,
            @Param("updatedAt") java.time.Instant updatedAt);

    int insertToolResultAsset(@Param("row") ToolResultAssetRow row);

    ToolResultAssetRow findToolResultAsset(@Param("assetRef") String assetRef);

    int insertModelContinuation(@Param("row") ModelContinuationRow row);

    ModelContinuationRow continuationForMessage(@Param("messageId") String messageId);

    List<ModelContinuationRow> modelContinuations(@Param("runId") String runId);
}
