package io.haifa.agent.personalassistant.application;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

public final class InMemoryPersonalModelPreferenceStore implements PersonalModelPreferenceStore {
    private final Map<String, PersonalModelPreference> values = new LinkedHashMap<>();

    @Override
    public synchronized PersonalModelPreference create(String conversationId, String modelId, Instant at) {
        return values.computeIfAbsent(
                conversationId,
                ignored -> new PersonalModelPreference(
                        conversationId, modelId, 0, Optional.empty(), Optional.empty(), at));
    }

    @Override
    public synchronized Optional<PersonalModelPreference> find(String conversationId) {
        return Optional.ofNullable(values.get(conversationId));
    }

    @Override
    public synchronized PersonalModelPreference change(
            String conversationId,
            long expectedRevision,
            String modelId,
            String idempotencyKeyDigest,
            String requestDigest,
            Instant at) {
        PersonalModelPreference current = find(conversationId).orElseThrow();
        if (current.idempotencyKeyDigest().filter(idempotencyKeyDigest::equals).isPresent()) {
            if (current.requestDigest().filter(requestDigest::equals).isEmpty()) {
                throw new IllegalStateException("MODEL_IDEMPOTENCY_CONFLICT");
            }
            return current;
        }
        if (current.revision() != expectedRevision) throw new IllegalStateException("MODEL_REVISION_STALE");
        PersonalModelPreference changed = new PersonalModelPreference(
                conversationId,
                modelId,
                current.revision() + 1,
                Optional.of(idempotencyKeyDigest),
                Optional.of(requestDigest),
                at);
        values.put(conversationId, changed);
        return changed;
    }
}
