package io.haifa.agent.application.project.product.coding.delivery;

/** A managed write must not start when its pre-write repository baseline cannot be established. */
public final class RepositoryBaselineUnavailableException extends RuntimeException {
    public RepositoryBaselineUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
