package io.haifa.agent.runtime.core.interaction;

/** Declared terminal or resume behavior when a pending interaction reaches its deadline. */
public enum InteractionExpirationOutcome {
    FAIL_RUN,
    CANCEL_RUN,
    RETURN_TO_AGENT
}
