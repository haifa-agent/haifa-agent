package io.haifa.agent.application.project.product.coding;

import io.haifa.agent.application.project.product.coding.verification.CodingVerificationCandidate;
import io.haifa.agent.application.project.product.coding.verification.CodingVerificationSource;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Trusted Host input frozen at Coding Session creation; it is not accepted from conversation text. */
public record CodingSessionCreateOptions(
        List<CodingVerificationCandidate> userVerificationCandidates, Optional<String> initialModelId) {
    public CodingSessionCreateOptions(List<CodingVerificationCandidate> userVerificationCandidates) {
        this(userVerificationCandidates, Optional.empty());
    }

    public CodingSessionCreateOptions {
        userVerificationCandidates = List.copyOf(
                Objects.requireNonNull(userVerificationCandidates, "userVerificationCandidates must not be null"));
        initialModelId = Objects.requireNonNull(initialModelId, "initialModelId must not be null")
                .map(value -> CodingProductValues.requireText(value, "initialModelId", 128));
        if (userVerificationCandidates.size() > 16
                || userVerificationCandidates.stream()
                        .anyMatch(candidate -> candidate.source() != CodingVerificationSource.USER_EXPLICIT)) {
            throw new IllegalArgumentException("user verification candidates are invalid");
        }
    }

    public static CodingSessionCreateOptions defaults() {
        return new CodingSessionCreateOptions(List.of(), Optional.empty());
    }

    public static CodingSessionCreateOptions withInitialModel(String modelId) {
        return new CodingSessionCreateOptions(List.of(), Optional.of(modelId));
    }
}
