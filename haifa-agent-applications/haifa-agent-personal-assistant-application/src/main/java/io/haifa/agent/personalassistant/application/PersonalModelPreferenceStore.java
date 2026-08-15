package io.haifa.agent.personalassistant.application;

import java.time.Instant;
import java.util.Optional;

public interface PersonalModelPreferenceStore {
    PersonalModelPreference create(String conversationId, PersonalModelPreferenceDraft preference, Instant at);

    Optional<PersonalModelPreference> find(String conversationId);

    PersonalModelPreference change(
            String conversationId,
            long expectedRevision,
            PersonalModelPreferenceDraft preference,
            String idempotencyKeyDigest,
            String requestDigest,
            Instant at);
}
