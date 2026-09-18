package io.haifa.agent.personalassistant.application.mission;

import java.util.Objects;

/** Immutable model identity frozen when a Mission is created. */
public record MissionModelBinding(
        String modelId,
        String modelDisplayName,
        String providerId,
        String providerDisplayName,
        String configurationDigest) {
    public MissionModelBinding {
        modelId = text(modelId, "modelId", 128);
        modelDisplayName = text(modelDisplayName, "modelDisplayName", 256);
        providerId = text(providerId, "providerId", 128);
        providerDisplayName = text(providerDisplayName, "providerDisplayName", 256);
        configurationDigest = text(configurationDigest, "configurationDigest", 256);
    }

    public static MissionModelBinding legacyDefault() {
        return new MissionModelBinding(
                "legacy-default", "Legacy default model", "legacy", "Legacy configuration", "legacy-unfrozen");
    }

    private static String text(String value, String field, int maximum) {
        String normalized =
                Objects.requireNonNull(value, field + " must not be null").trim();
        if (normalized.isEmpty() || normalized.length() > maximum) {
            throw new MissionException("MISSION_MODEL_BINDING_INVALID", field + " is invalid");
        }
        return normalized;
    }
}
