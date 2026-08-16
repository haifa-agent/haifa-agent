package io.haifa.agent.testing.delivery;

import java.util.Map;
import java.util.Objects;

/** Versioned Phase-specific Gate policy; execution and evidence collection remain outside this type. */
sealed interface AutonomousDeliveryPhasePolicy
        permits AutonomousDeliveryPhasePolicy.PhaseOne,
                AutonomousDeliveryPhasePolicy.PhaseTwo,
                AutonomousDeliveryPhasePolicy.PhaseThree {
    String REVIEWED_READ_ONLY_ANALYZE_STUB = "deterministic-read-only-analyze-v1";

    int phaseNumber();

    default boolean requiresDeterministicAnalyze() {
        return false;
    }

    default boolean requiresDeterministicReplay() {
        return false;
    }

    default boolean requiresExternalVerification() {
        return false;
    }

    default boolean prerequisiteEvidencePassed(
            Map<String, Object> deterministicAnalyze, Map<String, Object> deterministicReplay) {
        Objects.requireNonNull(deterministicAnalyze, "deterministicAnalyze must not be null");
        Objects.requireNonNull(deterministicReplay, "deterministicReplay must not be null");
        return evidencePassed(deterministicAnalyze, requiresDeterministicAnalyze())
                && evidencePassed(deterministicReplay, requiresDeterministicReplay());
    }

    static AutonomousDeliveryPhasePolicy resolve(AutonomousDeliverySuiteManifest suite) {
        Objects.requireNonNull(suite, "suite must not be null");
        return switch (suite.phase()) {
            case "PHASE_1" -> new PhaseOne();
            case "PHASE_2" -> {
                if (!REVIEWED_READ_ONLY_ANALYZE_STUB.equals(suite.readOnlyAnalyzeStubId())) {
                    throw new IllegalArgumentException("PHASE_2 requires the reviewed read-only ANALYZE Stub");
                }
                yield new PhaseTwo();
            }
            case "PHASE_3" -> new PhaseThree();
            default ->
                throw new IllegalArgumentException("production Gate requires a PHASE_1, PHASE_2, or PHASE_3 suite");
        };
    }

    private static boolean evidencePassed(Map<String, Object> evidence, boolean policyRequiresEvidence) {
        if (policyRequiresEvidence) {
            return Boolean.TRUE.equals(evidence.get("required")) && Boolean.TRUE.equals(evidence.get("passed"));
        }
        return !Boolean.TRUE.equals(evidence.get("required")) || Boolean.TRUE.equals(evidence.get("passed"));
    }

    record PhaseOne() implements AutonomousDeliveryPhasePolicy {
        @Override
        public int phaseNumber() {
            return 1;
        }
    }

    record PhaseTwo() implements AutonomousDeliveryPhasePolicy {
        @Override
        public int phaseNumber() {
            return 2;
        }

        @Override
        public boolean requiresDeterministicAnalyze() {
            return true;
        }
    }

    record PhaseThree() implements AutonomousDeliveryPhasePolicy {
        @Override
        public int phaseNumber() {
            return 3;
        }

        @Override
        public boolean requiresDeterministicReplay() {
            return true;
        }

        @Override
        public boolean requiresExternalVerification() {
            return true;
        }
    }
}
