package io.haifa.agent.model.core;

/** Deterministic failure categories resolved before an external request. */
public enum ModelSelectionFailure {
    MODEL_NOT_FOUND,
    PROVIDER_NOT_FOUND,
    PROVIDER_NOT_ACTIVE,
    MODEL_NOT_ACTIVE,
    CAPABILITY_MISMATCH,
    ACCESS_DENIED
}
