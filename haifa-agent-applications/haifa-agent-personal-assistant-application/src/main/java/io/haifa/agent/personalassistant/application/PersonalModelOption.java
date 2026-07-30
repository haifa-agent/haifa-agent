package io.haifa.agent.personalassistant.application;

import java.util.Objects;
import java.util.Set;

public record PersonalModelOption(
        String id,
        String displayName,
        String providerId,
        String providerDisplayName,
        Set<String> capabilities,
        int contextWindow) {
    public PersonalModelOption {
        id = text(id, "id");
        displayName = text(displayName, "displayName");
        providerId = text(providerId, "providerId");
        providerDisplayName = text(providerDisplayName, "providerDisplayName");
        capabilities = Set.copyOf(Objects.requireNonNull(capabilities));
        if (contextWindow < 1) throw new IllegalArgumentException("contextWindow must be positive");
    }

    private static String text(String value, String field) {
        String normalized =
                Objects.requireNonNull(value, field + " must not be null").trim();
        if (normalized.isEmpty() || normalized.length() > 256) {
            throw new IllegalArgumentException(field + " is invalid");
        }
        return normalized;
    }
}
