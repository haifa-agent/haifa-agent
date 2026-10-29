package io.haifa.agent.application.project.product.coding.client;

import io.haifa.agent.runtime.api.InteractionView;
import java.util.Objects;
import java.util.Optional;

/** A pending interaction and the product-owned session approval option available for that target. */
public record CodingApprovalPrompt(
        InteractionView interaction, boolean allowForSession, Optional<String> sessionApprovalUnavailableReason) {
    public CodingApprovalPrompt {
        interaction = Objects.requireNonNull(interaction, "interaction must not be null");
        sessionApprovalUnavailableReason = Objects.requireNonNull(sessionApprovalUnavailableReason);
    }

    public CodingApprovalPrompt(InteractionView interaction, boolean allowForSession) {
        this(interaction, allowForSession, Optional.empty());
    }
}
