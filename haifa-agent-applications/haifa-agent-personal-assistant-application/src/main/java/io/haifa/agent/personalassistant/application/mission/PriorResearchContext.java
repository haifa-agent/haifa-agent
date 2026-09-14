package io.haifa.agent.personalassistant.application.mission;

import java.util.List;
import java.util.Objects;

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
        unresolvedQuestions = boundTexts(unresolvedQuestions, 50, 1_000);
        unverifiedClaims = boundTexts(unverifiedClaims, 50, 1_000);
    }

    private static List<String> boundTexts(List<String> values, int maxItems, int maxChars) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        return values.stream()
                .filter(Objects::nonNull)
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .map(s -> s.length() > maxChars ? s.substring(0, maxChars) : s)
                .limit(maxItems)
                .toList();
    }
}
