package io.haifa.agent.application.project.product.coding.delivery;

import static org.assertj.core.api.Assertions.assertThat;

import io.haifa.agent.application.project.product.coding.verification.CodingSessionVerificationConfiguration;
import io.haifa.agent.application.project.product.coding.verification.CodingVerificationCandidate;
import io.haifa.agent.application.project.product.coding.verification.CodingVerificationCost;
import io.haifa.agent.application.project.product.coding.verification.CodingVerificationProfile;
import io.haifa.agent.application.project.product.coding.verification.CodingVerificationSource;
import io.haifa.agent.application.project.product.coding.verification.CodingVerificationTrigger;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class CodingValidationEvidenceTest {
    @Test
    void ignoresRunnerSummariesAndUsesOnlyTheExactFrozenCandidateScope() {
        CodingVerificationCandidate candidate = new CodingVerificationCandidate(
                "python -m pytest tests/test_exact.py",
                CodingVerificationCost.LOW,
                Duration.ofMinutes(2),
                CodingVerificationTrigger.ADJACENT_CHANGE,
                CodingVerificationSource.USER_EXPLICIT,
                "coding-client",
                CodingValidationScope.SELECTED);
        CodingSessionVerificationConfiguration configuration =
                CodingSessionVerificationConfiguration.freeze(new CodingVerificationProfile(List.of(candidate)));

        for (String ignoredRunnerOutput : List.of(
                "1 passed, 4 deselected in 0.12s",
                "Tests run: 9, Failures: 0, Errors: 0, Skipped: 0",
                "test result: ok. 14 passed; 0 failed; 2 ignored;")) {
            CodingValidationAttemptEvidence evidence = CodingValidationAttemptFactory.create(
                            "TEST", candidate.command(), true, configuration)
                    .orElseThrow();

            assertThat(ignoredRunnerOutput).isNotBlank();
            assertThat(evidence.status()).isEqualTo(CodingValidationStatus.PASSED);
            assertThat(evidence.discoveredTestCount()).isNull();
            assertThat(evidence.selectedTestCount()).isNull();
            assertThat(evidence.ignoredTestCount()).isNull();
            assertThat(evidence.countSource()).isEqualTo("COUNTS_UNAVAILABLE");
            assertThat(evidence.scope()).isEqualTo(CodingValidationScope.SELECTED);
            assertThat(evidence.verificationSource()).isEqualTo("USER_EXPLICIT");
            assertThat(evidence.claimCode()).isEqualTo("TRUSTED_SELECTED_SCOPE");
        }
    }

    @Test
    void changedCommandCannotInheritTheFrozenCandidateClaim() {
        CodingVerificationCandidate candidate = new CodingVerificationCandidate(
                "./mvnw test",
                CodingVerificationCost.HIGH,
                Duration.ofMinutes(10),
                CodingVerificationTrigger.FINAL_GATE,
                CodingVerificationSource.BUILD_CONFIGURATION,
                "pom.xml",
                CodingValidationScope.FULL);
        CodingSessionVerificationConfiguration configuration =
                CodingSessionVerificationConfiguration.freeze(new CodingVerificationProfile(List.of(candidate)));

        CodingValidationAttemptEvidence evidence = CodingValidationAttemptFactory.create(
                        "BUILD", "./mvnw test && echo done", false, configuration)
                .orElseThrow();

        assertThat(evidence.status()).isEqualTo(CodingValidationStatus.FAILED);
        assertThat(evidence.scope()).isEqualTo(CodingValidationScope.UNKNOWN);
        assertThat(evidence.verificationSource()).isEqualTo("UNMATCHED");
        assertThat(evidence.claimCode()).isEqualTo("COMMAND_NOT_IN_FROZEN_PROFILE");
    }

    @Test
    void exactFrozenCandidateDoesNotDependOnTheOptionalOperationFamilyHint() {
        CodingVerificationCandidate candidate = new CodingVerificationCandidate(
                "powershell -NoProfile -File verify.ps1",
                CodingVerificationCost.HIGH,
                Duration.ofMinutes(10),
                CodingVerificationTrigger.FINAL_GATE,
                CodingVerificationSource.BUILD_CONFIGURATION,
                "verify.ps1",
                CodingValidationScope.FULL);
        CodingSessionVerificationConfiguration configuration =
                CodingSessionVerificationConfiguration.freeze(new CodingVerificationProfile(List.of(candidate)));

        assertThat(CodingValidationAttemptFactory.create("UNKNOWN", candidate.command(), true, configuration))
                .get()
                .satisfies(evidence -> {
                    assertThat(evidence.status()).isEqualTo(CodingValidationStatus.PASSED);
                    assertThat(evidence.scope()).isEqualTo(CodingValidationScope.FULL);
                    assertThat(evidence.claimCode()).isEqualTo("TRUSTED_FULL_SCOPE");
                });
        assertThat(CodingValidationAttemptFactory.create("INSPECT", candidate.command(), true, configuration))
                .isEmpty();
        assertThat(CodingValidationAttemptFactory.create("UNKNOWN", "arbitrary-command", true, configuration))
                .isEmpty();
    }

    @Test
    void rejectsLegacyRunnerEvidence() {
        Map<String, Object> legacy = Map.ofEntries(
                Map.entry("schemaVersion", "coding-validation-evidence/1"),
                Map.entry("status", "PASSED"),
                Map.entry("discoveredTestCount", 5),
                Map.entry("selectedTestCount", 1),
                Map.entry("ignoredTestCount", 4),
                Map.entry("scope", "SELECTED"),
                Map.entry("countSource", "PYTEST_SUMMARY"),
                Map.entry("claimCode", "SELECTED_TESTS_ONLY"));

        assertThat(CodingValidationAttemptEvidence.fromStructuredData(legacy)).isEmpty();
    }
}
