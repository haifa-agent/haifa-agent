package io.haifa.agent.personalassistant.application.mission;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/** Claimed product Outbox intent carrying only the frozen Task execution request. */
public record MissionDispatchIntent(
        String outboxId,
        String missionId,
        String ownerScope,
        MissionModelBinding modelBinding,
        String taskId,
        int attemptNo,
        String dispatchKey,
        String payloadDigest,
        MissionTaskRunInput runInput,
        Instant claimedAt) {
    public MissionDispatchIntent {
        modelBinding = Objects.requireNonNull(modelBinding, "modelBinding must not be null");
        runInput = Objects.requireNonNull(runInput, "runInput must not be null");
    }

    public MissionDispatchIntent(
            String outboxId,
            String missionId,
            String ownerScope,
            String taskId,
            int attemptNo,
            String dispatchKey,
            String payloadDigest,
            MissionTaskRunInput runInput,
            Instant claimedAt) {
        this(
                outboxId,
                missionId,
                ownerScope,
                MissionModelBinding.legacyDefault(),
                taskId,
                attemptNo,
                dispatchKey,
                payloadDigest,
                runInput,
                claimedAt);
    }

    public MissionDispatchIntent(
            String outboxId,
            String missionId,
            String ownerScope,
            String taskId,
            int attemptNo,
            String dispatchKey,
            String payloadDigest,
            String objective,
            List<String> acceptanceCriteria,
            String taskType,
            List<String> requiredSkillIds,
            String resultSchemaId,
            String resultSchemaVersion,
            Instant claimedAt) {
        this(
                outboxId,
                missionId,
                ownerScope,
                MissionModelBinding.legacyDefault(),
                taskId,
                attemptNo,
                dispatchKey,
                payloadDigest,
                MissionTaskRunInput.create(
                        objective,
                        acceptanceCriteria,
                        objective,
                        acceptanceCriteria,
                        taskType,
                        requiredSkillIds,
                        resultSchemaId,
                        resultSchemaVersion,
                        java.util.Optional.empty(),
                        List.of()),
                claimedAt);
    }

    public MissionDispatchIntent(
            String outboxId,
            String missionId,
            String ownerScope,
            String taskId,
            int attemptNo,
            String dispatchKey,
            String payloadDigest,
            String objective,
            List<String> acceptanceCriteria,
            String resultSchemaId,
            String resultSchemaVersion,
            Instant claimedAt) {
        this(
                outboxId,
                missionId,
                ownerScope,
                taskId,
                attemptNo,
                dispatchKey,
                payloadDigest,
                objective,
                acceptanceCriteria,
                "GENERAL",
                List.of(),
                resultSchemaId,
                resultSchemaVersion,
                claimedAt);
    }

    public String objective() {
        return runInput.taskObjective();
    }

    public List<String> acceptanceCriteria() {
        return runInput.taskAcceptanceCriteria();
    }

    public String taskType() {
        return runInput.taskType();
    }

    public List<String> requiredSkillIds() {
        return runInput.requiredSkillIds();
    }

    public String resultSchemaId() {
        return runInput.resultSchemaId();
    }

    public String resultSchemaVersion() {
        return runInput.resultSchemaVersion();
    }
}
