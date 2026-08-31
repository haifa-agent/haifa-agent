package io.haifa.agent.application.project.product.coding;

import io.haifa.agent.model.api.ModelReasoningEffort;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;
import java.util.Optional;

/** Closed Coding user intent for model parameter configuration. */
public record CodingModelPreferences(
        CodingResponseMode responseMode,
        Optional<ModelReasoningEffort> effort) {
    public CodingModelPreferences {
        responseMode = Objects.requireNonNull(responseMode, "responseMode must not be null");
        effort = Objects.requireNonNull(effort, "effort must not be null");
        if (responseMode != CodingResponseMode.DEEP && effort.isPresent()) {
            throw new IllegalArgumentException("effort is only valid for DEEP response mode");
        }
    }

    public static CodingModelPreferences recommended() {
        return new CodingModelPreferences(CodingResponseMode.RECOMMENDED, Optional.empty());
    }

    public String digest() {
        String canonical = String.join(
                "|",
                "coding-model-preferences-v1",
                responseMode.name(),
                effort.map(Enum::name).orElse("none"));
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
