package io.haifa.agent.runtime.api;

/** Minimal requester view; products may replace the display label after authorization. */
public record InteractionRequesterView(String principalType, String displayLabel) {
    public InteractionRequesterView {
        principalType = InteractionKind.requireToken(principalType, "principalType");
        displayLabel = InteractionOption.requireText(displayLabel, "displayLabel", 256);
    }
}
