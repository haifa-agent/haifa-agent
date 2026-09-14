package io.haifa.agent.personalassistant.application.mission;

import java.util.List;

/** Frozen, bounded prior research findings and unresolved gaps from an earlier Mission. */
public record PriorResearchContext(
        String previousMissionId,
        String directAnswer,
        List<String> unresolvedQuestions,
        List<String> unverifiedClaims) {
    public PriorResearchContext {
        previousMissionId = MissionValues.text(previousMissionId, "previousMissionId", 256);
        directAnswer = directAnswer == null ? "" : directAnswer.trim();
        if (directAnswer.length() > 8_000) {
            directAnswer = directAnswer.substring(0, 8_000);
        }
        unresolvedQuestions = MissionValues.texts(unresolvedQuestions, "unresolvedQuestions", 40, 1_000);
        unverifiedClaims = MissionValues.texts(unverifiedClaims, "unverifiedClaims", 40, 1_000);
    }
}
