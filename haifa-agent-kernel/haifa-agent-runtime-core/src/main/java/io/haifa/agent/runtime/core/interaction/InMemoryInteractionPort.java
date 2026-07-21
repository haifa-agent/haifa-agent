package io.haifa.agent.runtime.core.interaction;

import io.haifa.agent.core.run.AgentRunId;
import io.haifa.agent.runtime.api.InteractionRequestId;
import io.haifa.agent.runtime.api.InteractionResponse;
import io.haifa.agent.runtime.api.InteractionResponseType;
import io.haifa.agent.runtime.core.bootstrap.RuntimeCallerContext;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

public final class InMemoryInteractionPort implements InteractionPort {
    private final Map<InteractionRequestId, InteractionRequest> requests = new HashMap<>();
    private final Map<String, InteractionResponse> responses = new HashMap<>();
    private final Map<InteractionRequestId, InteractionResponse> resolved = new HashMap<>();

    @Override
    public synchronized void create(InteractionRequest request) {
        if (requests.putIfAbsent(request.id(), request) != null)
            throw new IllegalStateException("interaction already exists");
    }

    @Override
    public synchronized Optional<InteractionRequest> pending(AgentRunId runId) {
        return requests.values().stream()
                .filter(request -> request.runId().equals(runId))
                .filter(request -> !resolved.containsKey(request.id()))
                .findFirst();
    }

    @Override
    public synchronized Optional<InteractionRequest> find(InteractionRequestId requestId) {
        return Optional.ofNullable(requests.get(requestId));
    }

    @Override
    public synchronized InteractionResolution respond(
            InteractionResponse response, RuntimeCallerContext caller, Instant receivedAt) {
        InteractionResponse existing = responses.get(response.responseId().value());
        if (existing != null) {
            if (!existing.equals(response)) throw new IllegalStateException("response id is already used");
            return new InteractionResolution(requireRequest(response.requestId()), false);
        }
        InteractionRequest request = requireRequest(response.requestId());
        if (!request.runId().equals(response.runId()))
            throw new IllegalArgumentException("response run does not match request");
        if (!request.tenant().equals(caller.tenant()) || !request.principal().equals(caller.principal())) {
            throw new SecurityException("caller cannot respond to this interaction");
        }
        if (receivedAt.isAfter(request.expiresAt())) throw new IllegalStateException("interaction has expired");
        if (resolved.containsKey(request.id())) throw new IllegalStateException("interaction already has a response");
        if (request.approval() && response.type() == InteractionResponseType.CLARIFY) {
            throw new IllegalArgumentException("approval interaction requires approve or reject");
        }
        if (!request.approval() && response.type() == InteractionResponseType.APPROVE) {
            throw new IllegalArgumentException("clarification interaction cannot be approved");
        }
        responses.put(response.responseId().value(), response);
        resolved.put(request.id(), response);
        return new InteractionResolution(request, true);
    }

    private InteractionRequest requireRequest(InteractionRequestId id) {
        InteractionRequest request = requests.get(id);
        if (request == null) throw new IllegalArgumentException("unknown interaction request");
        return request;
    }
}
