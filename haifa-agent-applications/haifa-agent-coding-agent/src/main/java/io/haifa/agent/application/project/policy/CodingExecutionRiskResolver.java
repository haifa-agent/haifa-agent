package io.haifa.agent.application.project.policy;

import io.haifa.agent.execution.core.command.SystemGitCliCommandClassifier;
import io.haifa.agent.policy.api.PolicyRiskLevel;
import java.util.Objects;

/** Trusted Coding product mapping from command classification to invocation-level policy risk. */
public final class CodingExecutionRiskResolver {
    public static final String VERSION = "1";

    private CodingExecutionRiskResolver() {}

    public static Assessment assess(String command, PolicyRiskLevel fallbackRisk) {
        return assess(SystemGitCliCommandClassifier.classify(command), fallbackRisk);
    }

    public static Assessment assess(
            SystemGitCliCommandClassifier.Classification classification, PolicyRiskLevel fallbackRisk) {
        Objects.requireNonNull(classification, "classification must not be null");
        PolicyRiskLevel effectiveRisk = effectiveRisk(classification, fallbackRisk);
        return new Assessment(classification, effectiveRisk);
    }

    private static PolicyRiskLevel effectiveRisk(
            SystemGitCliCommandClassifier.Classification classification, PolicyRiskLevel fallbackRisk) {
        Objects.requireNonNull(fallbackRisk, "fallbackRisk must not be null");
        if (classification.target() == SystemGitCliCommandClassifier.Target.OTHER
                && classification.risk() != SystemGitCliCommandClassifier.Risk.DENIED) {
            return fallbackRisk;
        }
        return switch (classification.risk()) {
            case LOCAL_READ -> PolicyRiskLevel.LOW;
            case LOCAL_WRITE, NETWORK_READ -> PolicyRiskLevel.MEDIUM;
            case EXTERNAL_WRITE, DESTRUCTIVE, UNKNOWN -> PolicyRiskLevel.HIGH;
            case DENIED -> PolicyRiskLevel.CRITICAL;
            case NOT_APPLICABLE -> fallbackRisk;
        };
    }

    public record Assessment(
            SystemGitCliCommandClassifier.Classification classification, PolicyRiskLevel effectiveRisk) {
        public Assessment {
            classification = Objects.requireNonNull(classification, "classification must not be null");
            effectiveRisk = Objects.requireNonNull(effectiveRisk, "effectiveRisk must not be null");
        }
    }
}
