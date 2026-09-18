package io.haifa.agent.application.project.product.coding;

import java.util.Objects;
import java.util.Optional;

/** Trusted Host input frozen at Coding Session creation; it is not accepted from conversation text. */
public record CodingSessionCreateOptions(Optional<String> initialModelId) {
    public CodingSessionCreateOptions {
        initialModelId = Objects.requireNonNull(initialModelId, "initialModelId must not be null")
                .map(value -> CodingProductValues.requireText(value, "initialModelId", 128));
    }

    public static CodingSessionCreateOptions defaults() {
        return new CodingSessionCreateOptions(Optional.empty());
    }

    public static CodingSessionCreateOptions withInitialModel(String modelId) {
        return new CodingSessionCreateOptions(Optional.of(modelId));
    }
}
