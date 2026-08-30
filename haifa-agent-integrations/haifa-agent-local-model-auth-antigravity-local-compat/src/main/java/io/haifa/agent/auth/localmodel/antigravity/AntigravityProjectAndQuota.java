package io.haifa.agent.auth.localmodel.antigravity;

import java.util.Objects;

/** Project identity and token credits balance retrieved from CloudCode PA API. */
public record AntigravityProjectAndQuota(
        String projectId, String tierId, double creditAmount, double minCreditAmount, boolean creditsAvailable) {

    public AntigravityProjectAndQuota {
        projectId =
                Objects.requireNonNull(projectId, "projectId must not be null").trim();
        tierId = Objects.requireNonNull(tierId, "tierId must not be null").trim();
        if (projectId.isEmpty()) {
            throw new IllegalArgumentException("projectId must not be blank");
        }
    }

    public static AntigravityProjectAndQuota of(
            String projectId, String tierId, double creditAmount, double minCreditAmount) {
        boolean available = creditAmount >= minCreditAmount;
        return new AntigravityProjectAndQuota(projectId, tierId, creditAmount, minCreditAmount, available);
    }
}
