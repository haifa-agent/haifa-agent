package io.haifa.agent.application.project.product.coding.client;

import io.haifa.agent.runtime.api.InteractionView;
import java.util.Objects;

/** A pending interaction and the product-owned session approval option available for that target. */
public record CodingApprovalPrompt(InteractionView interaction, boolean allowForSession) {
    public CodingApprovalPrompt {
        interaction = Objects.requireNonNull(interaction, "interaction must not be null");
    }
}
