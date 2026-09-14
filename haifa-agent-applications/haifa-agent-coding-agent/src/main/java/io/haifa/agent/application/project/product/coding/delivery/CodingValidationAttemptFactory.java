package io.haifa.agent.application.project.product.coding.delivery;

import io.haifa.agent.application.project.product.coding.verification.CodingSessionVerificationConfiguration;
import io.haifa.agent.application.project.product.coding.verification.CodingVerificationCandidate;
import java.util.Objects;
import java.util.Optional;

/** Builds parser-free validation evidence from trusted command identity and execution facts. */
public final class CodingValidationAttemptFactory {
    private CodingValidationAttemptFactory() {}

    public static Optional<CodingValidationAttemptEvidence> create(
            String command, CodingSessionVerificationConfiguration configuration) {
        CodingSessionVerificationConfiguration frozen =
                Objects.requireNonNull(configuration, "configuration must not be null");
        Optional<CodingVerificationCandidate> matched = frozen.profile().exactCandidate(command);
        if (matched.isEmpty()) return Optional.empty();
        CodingVerificationCandidate candidate = matched.orElseThrow();
        CodingValidationScope scope = candidate.claimedScope();
        String claimCode =
                switch (scope) {
                    case FULL -> "TRUSTED_FULL_SCOPE";
                    case SELECTED -> "TRUSTED_SELECTED_SCOPE";
                    case UNKNOWN -> "SCOPE_UNAVAILABLE";
                };
        return Optional.of(new CodingValidationAttemptEvidence(
                CodingValidationAttemptEvidence.SCHEMA_VERSION,
                scope,
                candidate.source().name(),
                claimCode,
                frozen.digest(),
                frozen.candidateDigest(candidate)));
    }
}
