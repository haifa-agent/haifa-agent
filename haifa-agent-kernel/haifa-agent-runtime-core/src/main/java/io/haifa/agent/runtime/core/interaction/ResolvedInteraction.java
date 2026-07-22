package io.haifa.agent.runtime.core.interaction;

import io.haifa.agent.runtime.api.InteractionResponse;
import java.util.Objects;

public record ResolvedInteraction(InteractionRequest request, InteractionResponse response) {
    public ResolvedInteraction {
        request = Objects.requireNonNull(request, "request must not be null");
        response = Objects.requireNonNull(response, "response must not be null");
        if (!request.id().equals(response.requestId()) || !request.runId().equals(response.runId())) {
            throw new IllegalArgumentException("interaction response does not match request");
        }
    }
}
