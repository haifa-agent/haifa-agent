package io.haifa.agent.application.project.product.coding.delivery;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class CodingValidationEvidenceTest {
    @Test
    void recordsReliableSelectedAndIgnoredCountsWithoutClaimingCompleteVerification() {
        CodingValidationAttemptEvidence evidence = CodingValidationEvidenceExtractor.extract(
                        "TEST", true, "1 passed, 4 deselected in 0.12s", false)
                .orElseThrow();

        assertThat(evidence.status()).isEqualTo(CodingValidationStatus.PASSED);
        assertThat(evidence.discoveredTestCount()).isEqualTo(5);
        assertThat(evidence.selectedTestCount()).isEqualTo(1);
        assertThat(evidence.ignoredTestCount()).isEqualTo(4);
        assertThat(evidence.scope()).isEqualTo(CodingValidationScope.SELECTED);
        assertThat(evidence.claimCode()).isEqualTo("SELECTED_TESTS_ONLY");
        assertThat(CodingValidationAttemptEvidence.fromStructuredData(evidence.toStructuredData()))
                .contains(evidence);
    }

    @Test
    void retainsUnknownCountsWhenOutputDoesNotProvideReliableEvidence() {
        CodingValidationAttemptEvidence evidence = CodingValidationEvidenceExtractor.extract(
                        "BUILD", false, "compiler returned an implementation-specific diagnostic", false)
                .orElseThrow();

        assertThat(evidence.status()).isEqualTo(CodingValidationStatus.FAILED);
        assertThat(evidence.selectedTestCount()).isNull();
        assertThat(evidence.scope()).isEqualTo(CodingValidationScope.UNKNOWN);
        assertThat(evidence.claimCode()).isEqualTo("TEST_COUNTS_UNAVAILABLE");
    }

    @Test
    void doesNotAddRepeatedPytestSummariesFromBoundedHeadAndTailOutput() {
        CodingValidationAttemptEvidence evidence = CodingValidationEvidenceExtractor.extract(
                        "TEST", true, "1 passed in 0.10s\n...\n1 passed in 0.10s", false)
                .orElseThrow();

        assertThat(evidence.status()).isEqualTo(CodingValidationStatus.PASSED);
        assertThat(evidence.selectedTestCount()).isNull();
        assertThat(evidence.scope()).isEqualTo(CodingValidationScope.UNKNOWN);
    }
}
