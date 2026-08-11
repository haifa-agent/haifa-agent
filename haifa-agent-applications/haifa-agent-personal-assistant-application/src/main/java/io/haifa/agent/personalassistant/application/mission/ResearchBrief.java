package io.haifa.agent.personalassistant.application.mission;

import java.util.List;

/** Frozen, bounded Deep Research input captured before planning. */
public record ResearchBrief(
        String question,
        String scope,
        String timeRange,
        String region,
        String audience,
        List<String> sourcePreferences,
        List<String> exclusions,
        String deliveryFormat) {
    public ResearchBrief {
        question = MissionValues.text(question, "research question", 8_000);
        scope = optional(scope, 2_000);
        timeRange = optional(timeRange, 256);
        region = optional(region, 256);
        audience = optional(audience, 256);
        sourcePreferences = MissionValues.texts(sourcePreferences, "sourcePreferences", 20, 256);
        exclusions = MissionValues.texts(exclusions, "exclusions", 20, 256);
        deliveryFormat = optional(deliveryFormat, 256);
    }

    private static String optional(String value, int maximum) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.length() > maximum) {
            throw new MissionException("MISSION_RESEARCH_BRIEF_INVALID", "Research brief field exceeds its limit");
        }
        return normalized;
    }
}
