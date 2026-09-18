package io.haifa.agent.application.project.product.coding;

import java.util.Objects;

public record CodingDispatchClaim(CodingSessionActivity activity, CodingFollowUp followUp) {
    public CodingDispatchClaim {
        activity = Objects.requireNonNull(activity, "activity must not be null");
        followUp = Objects.requireNonNull(followUp, "followUp must not be null");
        if (!activity.sessionId().equals(followUp.sessionId())
                || activity.activeDispatchKey()
                        .filter(followUp.dispatchKey()::equals)
                        .isEmpty()) {
            throw new IllegalArgumentException("dispatch claim is inconsistent");
        }
    }
}
