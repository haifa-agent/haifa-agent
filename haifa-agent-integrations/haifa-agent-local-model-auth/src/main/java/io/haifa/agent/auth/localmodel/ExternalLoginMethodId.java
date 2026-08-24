package io.haifa.agent.auth.localmodel;

import java.util.Locale;
import java.util.Objects;

/** Stable identifier for an allowlisted external login driver. */
public record ExternalLoginMethodId(String value) {
    public static final ExternalLoginMethodId OPENAI_CODEX = new ExternalLoginMethodId("openai-codex");

    public ExternalLoginMethodId {
        value = Objects.requireNonNull(value, "value must not be null").trim().toLowerCase(Locale.ROOT);
        if (!value.matches("[a-z][a-z0-9]*(?:-[a-z0-9]+)*") || value.length() > 64) {
            throw new IllegalArgumentException("external login method id is invalid");
        }
    }

    @Override
    public String toString() {
        return value;
    }
}
