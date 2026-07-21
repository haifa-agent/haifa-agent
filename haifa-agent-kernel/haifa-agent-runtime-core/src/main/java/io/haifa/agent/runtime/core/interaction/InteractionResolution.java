package io.haifa.agent.runtime.core.interaction;

import java.util.Objects;

public record InteractionResolution(InteractionRequest request, boolean newlyRecorded) {
    public InteractionResolution {
        request = Objects.requireNonNull(request, "request must not be null");
    }
}
