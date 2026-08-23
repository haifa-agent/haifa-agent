package io.haifa.agent.application.project.product.coding.delivery;

import io.haifa.agent.application.project.product.coding.verification.CodingSessionVerificationConfiguration;
import io.haifa.agent.application.project.product.coding.verification.CodingVerificationCandidate;
import java.util.Objects;
import java.util.Optional;

/** Builds parser-free validation evidence from trusted command identity and execution facts. */
public final class CodingValidationAttemptFactory {
    private CodingValidationAttemptFactory() {}

    public static Optional<CodingValidationAttemptEvidence> create(
            String operationFamily,
            String command,
            boolean successful,
            CodingSessionVerificationConfiguration configuration) {
        if (!"TEST".equals(operationFamily) && !"BUILD".equals(operationFamily)) return Optional.empty();
        CodingSessionVerificationConfiguration frozen =
                Objects.requireNonNull(configuration, "configuration must not be null");
        Optional<CodingVerificationCandidate> matched = frozen.profile().exactCandidate(command);
        CodingValidationScope scope =
                matched.map(CodingVerificationCandidate::claimedScope).orElse(CodingValidationScope.UNKNOWN);
        String claimCode =
                switch (scope) {
                    case FULL -> "TRUSTED_FULL_SCOPE";
                    case SELECTED -> "TRUSTED_SELECTED_SCOPE";
                    case UNKNOWN -> matched.isPresent() ? "SCOPE_UNAVAILABLE" : "COMMAND_NOT_IN_FROZEN_PROFILE";
                };
        return Optional.of(new CodingValidationAttemptEvidence(
                CodingValidationAttemptEvidence.SCHEMA_VERSION,
                successful ? CodingValidationStatus.PASSED : CodingValidationStatus.FAILED,
                null,
                null,
                null,
                scope,
                "COUNTS_UNAVAILABLE",
                matched.map(candidate -> candidate.source().name()).orElse("UNMATCHED"),
                claimCode,
                frozen.digest(),
                matched.map(frozen::candidateDigest).orElse("UNMATCHED")));
    }
}
