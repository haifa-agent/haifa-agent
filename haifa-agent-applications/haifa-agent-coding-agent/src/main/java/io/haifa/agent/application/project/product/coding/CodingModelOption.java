package io.haifa.agent.application.project.product.coding;

import java.util.Objects;
import java.util.Set;

/** Safe product projection for Coding model selection surfaces. */
public record CodingModelOption(
        String id,
        String displayName,
        String providerId,
        String providerDisplayName,
        Set<String> capabilities,
        int contextWindow) {
    public CodingModelOption {
        id = CodingProductValues.requireText(id, "id", 128);
        displayName = CodingProductValues.requireText(displayName, "displayName", 128);
        providerId = CodingProductValues.requireText(providerId, "providerId", 128);
        providerDisplayName = CodingProductValues.requireText(providerDisplayName, "providerDisplayName", 128);
        capabilities = Set.copyOf(Objects.requireNonNull(capabilities, "capabilities must not be null"));
        if (contextWindow < 1) throw new IllegalArgumentException("contextWindow must be positive");
    }
}
