package io.haifa.agent.personalassistant.application;

/**
 * Server-computed compatibility of a persisted model preference against the current trusted Profile.
 *
 * <p>Ordinary PA HTTP clients never see Profile version or digest. This projection is the only safe
 * signal that tells the UI whether the stored selection still resolves, needs an explicit
 * re-confirmation, or is no longer selectable. It must never be derived from raw Profile fields.
 */
public enum PersonalSelectionCompatibility {
    /** The persisted preference resolves cleanly against the current Profile; no action needed. */
    CURRENT,
    /** The binding still exists, but the stored schema or preferences are no longer valid. */
    RESELECTION_REQUIRED,
    /** The bound model is no longer selectable; the UI must not silently fall back. */
    UNAVAILABLE
}
