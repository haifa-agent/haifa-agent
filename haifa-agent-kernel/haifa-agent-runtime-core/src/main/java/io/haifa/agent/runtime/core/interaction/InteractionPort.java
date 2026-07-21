package io.haifa.agent.runtime.core.interaction;

import io.haifa.agent.core.run.AgentRunId;
import io.haifa.agent.runtime.api.InteractionRequestId;
import io.haifa.agent.runtime.api.InteractionResponse;
import io.haifa.agent.runtime.core.bootstrap.RuntimeCallerContext;
import java.time.Instant;
import java.util.Optional;

public interface InteractionPort {
    void create(InteractionRequest request);

    Optional<InteractionRequest> pending(AgentRunId runId);

    Optional<InteractionRequest> find(InteractionRequestId requestId);

    InteractionResolution respond(InteractionResponse response, RuntimeCallerContext caller, Instant receivedAt);
}
