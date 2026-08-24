package io.haifa.agent.application.project.product.coding;

import io.haifa.agent.application.project.product.coding.delivery.CodingDeliveryIntent;
import io.haifa.agent.application.project.product.coding.verification.CodingVerificationCandidate;
import io.haifa.agent.application.project.product.coding.verification.CodingVerificationSource;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Trusted Host input frozen at Coding Session creation; it is not accepted from conversation text. */
public record CodingSessionCreateOptions(
        CodingDeliveryIntent deliveryIntent,
        List<CodingVerificationCandidate> userVerificationCandidates,
        Optional<String> initialModelId) {
    public CodingSessionCreateOptions(
            CodingDeliveryIntent deliveryIntent, List<CodingVerificationCandidate> userVerificationCandidates) {
        this(deliveryIntent, userVerificationCandidates, Optional.empty());
    }

    public CodingSessionCreateOptions {
        deliveryIntent = Objects.requireNonNull(deliveryIntent, "deliveryIntent must not be null");
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
        return new CodingSessionCreateOptions(CodingDeliveryIntent.WORKTREE_ONLY, List.of(), Optional.empty());
    }

    public static CodingSessionCreateOptions withInitialModel(String modelId) {
        return new CodingSessionCreateOptions(CodingDeliveryIntent.WORKTREE_ONLY, List.of(), Optional.of(modelId));
    }
}
