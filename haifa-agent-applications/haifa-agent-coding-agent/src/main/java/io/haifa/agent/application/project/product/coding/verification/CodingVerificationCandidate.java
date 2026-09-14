package io.haifa.agent.application.project.product.coding.verification;

import io.haifa.agent.application.project.product.coding.delivery.CodingValidationScope;
import java.util.Objects;

public record CodingVerificationCandidate(
        String command,
        CodingVerificationCost cost,
        CodingVerificationTrigger trigger,
        CodingVerificationSource source,
        String sourceReference,
        CodingValidationScope claimedScope) {
    public CodingVerificationCandidate(
            String command,
            CodingVerificationCost cost,
            CodingVerificationTrigger trigger,
            CodingVerificationSource source,
            String sourceReference) {
        this(command, cost, trigger, source, sourceReference, CodingValidationScope.UNKNOWN);
    }

    public CodingVerificationCandidate {
        command = singleLine(command, "command", 2_048);
        cost = Objects.requireNonNull(cost, "cost must not be null");
        trigger = Objects.requireNonNull(trigger, "trigger must not be null");
        source = Objects.requireNonNull(source, "source must not be null");
        sourceReference = singleLine(sourceReference, "sourceReference", 256);
        claimedScope = Objects.requireNonNull(claimedScope, "claimedScope must not be null");
    }

    private static String singleLine(String value, String field, int maximum) {
        String normalized =
                Objects.requireNonNull(value, field + " must not be null").trim();
        if (normalized.isEmpty()
                || normalized.length() > maximum
                || normalized.indexOf('\0') >= 0
                || normalized.indexOf('\r') >= 0
                || normalized.indexOf('\n') >= 0) {
            throw new IllegalArgumentException(field + " is invalid");
        }
        return normalized;
    }
}
