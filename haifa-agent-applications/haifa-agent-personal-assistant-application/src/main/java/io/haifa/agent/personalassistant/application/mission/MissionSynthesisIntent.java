package io.haifa.agent.personalassistant.application.mission;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** Recoverable one-shot Synthesis request assembled only from settled Task results. */
public record MissionSynthesisIntent(
        String missionId,
        String conversationId,
        String ownerScope,
        MissionMode mode,
        String objective,
        List<String> taskResults,
        List<String> failedItems,
        List<String> completedTaskIds,
        int maxRevisionAttempts,
        long remainingModelTokens,
        Optional<Instant> deadlineAt,
        Optional<ResearchBrief> researchBrief,
        MissionUsage preSynthesisUsage) {
    public MissionSynthesisIntent {
        taskResults = List.copyOf(taskResults);
        failedItems = List.copyOf(failedItems);
        completedTaskIds = List.copyOf(completedTaskIds);
        deadlineAt = java.util.Objects.requireNonNull(deadlineAt);
        researchBrief = java.util.Objects.requireNonNull(researchBrief);
        preSynthesisUsage = java.util.Objects.requireNonNull(preSynthesisUsage);
        if (taskResults.size() != completedTaskIds.size()) {
            throw new IllegalArgumentException("Each settled Task result must retain its real taskId");
        }
        if (maxRevisionAttempts < 0 || maxRevisionAttempts > 2 || remainingModelTokens < 0) {
            throw new IllegalArgumentException("Synthesis revision budget is invalid");
        }
        if (mode == MissionMode.DEEP_RESEARCH && researchBrief.isEmpty()) {
            throw new IllegalArgumentException("Deep Research Synthesis requires the frozen Research Brief");
        }
    }

    public MissionSynthesisIntent(
            String missionId,
            String conversationId,
            String ownerScope,
            MissionMode mode,
            String objective,
            List<String> taskResults,
            List<String> failedItems,
            List<String> completedTaskIds,
            int maxRevisionAttempts,
            long remainingModelTokens,
            Optional<Instant> deadlineAt,
            Optional<ResearchBrief> researchBrief) {
        this(
                missionId,
                conversationId,
                ownerScope,
                mode,
                objective,
                taskResults,
                failedItems,
                completedTaskIds,
                maxRevisionAttempts,
                remainingModelTokens,
                deadlineAt,
                researchBrief,
                MissionUsage.NONE);
    }

    public MissionSynthesisIntent(
            String missionId,
            String conversationId,
            String ownerScope,
            MissionMode mode,
            String objective,
            List<String> taskResults,
            List<String> failedItems) {
        this(
                missionId,
                conversationId,
                ownerScope,
                mode,
                objective,
                taskResults,
                failedItems,
                defaultTaskIds(taskResults.size()),
                2,
                Long.MAX_VALUE,
                Optional.empty(),
                Optional.empty(),
                MissionUsage.NONE);
    }

    public MissionSynthesisIntent(
            String missionId,
            String conversationId,
            String ownerScope,
            MissionMode mode,
            String objective,
            List<String> taskResults) {
        this(missionId, conversationId, ownerScope, mode, objective, taskResults, List.of());
    }

    public boolean revisionAllowed(int revisionAttempt, MissionUsage cumulativeUsage, Instant now) {
        return revisionAttempt >= 1
                && revisionAttempt <= maxRevisionAttempts
                && cumulativeUsage.modelTokens() < remainingModelTokens
                && deadlineAt.map(now::isBefore).orElse(true);
    }

    private static List<String> defaultTaskIds(int size) {
        return java.util.stream.IntStream.range(0, size)
                .mapToObj(index -> "task-" + (index + 1))
                .toList();
    }
}
