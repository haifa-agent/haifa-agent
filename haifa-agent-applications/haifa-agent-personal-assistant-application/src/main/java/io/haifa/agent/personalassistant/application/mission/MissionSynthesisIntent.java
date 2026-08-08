package io.haifa.agent.personalassistant.application.mission;

import java.util.List;

/** Recoverable one-shot Synthesis request assembled only from settled Task results. */
public record MissionSynthesisIntent(
        String missionId,
        String conversationId,
        String ownerScope,
        MissionMode mode,
        String objective,
        List<String> taskResults,
        List<String> failedItems) {
    public MissionSynthesisIntent {
        taskResults = List.copyOf(taskResults);
        failedItems = List.copyOf(failedItems);
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
}
