package io.haifa.agent.application.project.policy;

import io.haifa.agent.policy.api.ApprovalMode;
import io.haifa.agent.policy.api.PolicyRiskLevel;
import java.util.Locale;
import java.util.Optional;

/** Lowest invocation risk that requires ordinary user approval; NEVER disables risk-threshold prompts. */
public enum CodingApprovalThreshold {
    LOW(PolicyRiskLevel.LOW),
    MEDIUM(PolicyRiskLevel.MEDIUM),
    HIGH(PolicyRiskLevel.HIGH),
    NEVER(null);

    private final PolicyRiskLevel minimumRisk;

    CodingApprovalThreshold(PolicyRiskLevel minimumRisk) {
        this.minimumRisk = minimumRisk;
    }

    public Optional<PolicyRiskLevel> minimumRisk() {
        return Optional.ofNullable(minimumRisk);
    }

    public static CodingApprovalThreshold compatibleWith(ApprovalMode mode) {
        return switch (mode) {
            case ASK -> LOW;
            case AUTO, DENY -> NEVER;
        };
    }

    public static CodingApprovalThreshold parse(String value) {
        try {
            return valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException("approval threshold must be one of: low, medium, high, never");
        }
    }
}
