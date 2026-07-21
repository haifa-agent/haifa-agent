package io.haifa.agent.runtime.core.model;

import java.util.Objects;

/** Exact immutable adapter binding required by a frozen model snapshot. */
public record ModelAdapterKey(String adapterType, String adapterVersion) {
    public ModelAdapterKey {
        adapterType = requireText(adapterType, "adapterType");
        adapterVersion = requireText(adapterVersion, "adapterVersion");
    }

    private static String requireText(String value, String field) {
        String normalized =
                Objects.requireNonNull(value, field + " must not be null").trim();
        if (normalized.isEmpty()) throw new IllegalArgumentException(field + " must not be blank");
        return normalized;
    }
}
