package io.haifa.agent.runtime.api;

/** Bounded, display-only explanation of accepted outcomes. */
public record InteractionConsequenceView(String accepted, String rejected, String expired) {
    public InteractionConsequenceView {
        accepted = InteractionOption.requireText(accepted, "accepted", 512);
        rejected = InteractionOption.requireText(rejected, "rejected", 512);
        expired = InteractionOption.requireText(expired, "expired", 512);
    }
}
