package io.haifa.agent.core.persistence;

import java.util.Objects;

/** Classified failure raised when persisted domain history cannot be safely reconstituted. */
public final class DomainReconstitutionException extends IllegalArgumentException {

    private final DomainReconstitutionFailure failure;

    public DomainReconstitutionException(DomainReconstitutionFailure failure, String message) {
        super(message);
        this.failure = Objects.requireNonNull(failure, "failure must not be null");
    }

    public DomainReconstitutionException(DomainReconstitutionFailure failure, String message, RuntimeException cause) {
        super(message, cause);
        this.failure = Objects.requireNonNull(failure, "failure must not be null");
    }

    public DomainReconstitutionFailure failure() {
        return failure;
    }
}
