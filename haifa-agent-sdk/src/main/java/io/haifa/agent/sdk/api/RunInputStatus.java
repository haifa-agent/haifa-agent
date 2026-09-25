package io.haifa.agent.sdk.api;

/** Authoritative state of a steer input as reported to the submitter. */
public enum RunInputStatus {
    /** Newly accepted; it will be applied at the next safe point or observably rejected if the Run stops first. */
    ACCEPTED,
    /** The idempotency key is already bound to this input and it is still waiting for a safe point. */
    DUPLICATE,
    /** The input was appended to the Run's model-visible context at a safe point. */
    APPLIED,
    /** The Run no longer accepts input, or it stopped before the accepted input could be applied. */
    REJECTED
}
