package io.haifa.agent.model.api;

/** Retry contract when a streaming invocation has emitted output before failing. */
public enum ModelPartialOutputFailureBehavior {
    NON_RETRYABLE
}
