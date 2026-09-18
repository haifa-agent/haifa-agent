package io.haifa.agent.personalassistant.application;

import io.haifa.agent.model.api.ModelReasoningEffort;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;
import java.util.Optional;

/** Closed PA user intent. Provider-native parameters are deliberately excluded. */
public record PersonalModelPreferences(
        PersonalResponseMode responseMode,
        Optional<ModelReasoningEffort> effort,
        PersonalResponseLength responseLength) {
    public PersonalModelPreferences {
        responseMode = Objects.requireNonNull(responseMode, "responseMode must not be null");
        effort = Objects.requireNonNull(effort, "effort must not be null");
        responseLength = Objects.requireNonNull(responseLength, "responseLength must not be null");
        if (responseMode != PersonalResponseMode.DEEP && effort.isPresent()) {
            throw new IllegalArgumentException("effort is only valid for DEEP response mode");
        }
    }

    public static PersonalModelPreferences recommended() {
        return new PersonalModelPreferences(
                PersonalResponseMode.RECOMMENDED, Optional.empty(), PersonalResponseLength.RECOMMENDED);
    }

    public String digest() {
        String canonical = String.join(
                "|",
                "pa-model-preferences-v1",
                responseMode.name(),
                effort.map(Enum::name).orElse("none"),
                responseLength.name());
        try {
            return "sha256:"
                    + HexFormat.of()
                            .formatHex(MessageDigest.getInstance("SHA-256")
                                    .digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is required", impossible);
        }
    }
}
