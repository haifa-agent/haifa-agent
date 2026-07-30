package io.haifa.agent.personalassistant.application;

import java.time.Instant;
import java.util.Optional;

public interface PersonalModelPreferenceStore {
    PersonalModelPreference create(String conversationId, String modelId, Instant at);

    Optional<PersonalModelPreference> find(String conversationId);

    PersonalModelPreference change(
            String conversationId,
            long expectedRevision,
            String modelId,
            String idempotencyKeyDigest,
            String requestDigest,
            Instant at);
}
