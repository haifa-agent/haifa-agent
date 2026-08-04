package io.haifa.agent.testing.suite;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import io.haifa.agent.testing.repository.RepositoryRevision;
import java.util.List;
import org.junit.jupiter.api.Test;

class SuiteExecutionPlanFingerprintTest {
    private static final RepositoryRevision PRODUCT = revision('a');
    private static final RepositoryRevision CONFIG = revision('b');

    @Test
    void isStableForTheSameResolvedExecutionPlan() {
        SuiteManifest manifest = manifest(3.0, 1);
        MatrixManifest.Combination combination = combination("windows", "deepseek-v4-pro");

        assertEquals(
                SuiteExecutionPlanFingerprint.create(manifest, combination, PRODUCT, CONFIG),
                SuiteExecutionPlanFingerprint.create(manifest, combination, PRODUCT, CONFIG));
    }

    @Test
    void keepsTheReviewedLinuxNightlySmokeDigestStable() {
        assertEquals(
                "943e163c6c1212ef95168c1dad4e99666519dee4e54fd73f886ef7e614542148",
                SuiteExecutionPlanFingerprint.create(
                                manifest(3.0, 1), combination("linux", "deepseek-v4-pro"), PRODUCT, CONFIG)
                        .sha256());
    }

    @Test
    void changesWhenBudgetCaseSelectionOrCombinationChanges() {
        SuiteExecutionPlanFingerprint baseline = SuiteExecutionPlanFingerprint.create(
                manifest(3.0, 1), combination("windows", "deepseek-v4-pro"), PRODUCT, CONFIG);

        assertNotEquals(
                baseline,
                SuiteExecutionPlanFingerprint.create(
                        manifest(3.01, 1), combination("windows", "deepseek-v4-pro"), PRODUCT, CONFIG));
        assertNotEquals(
                baseline,
                SuiteExecutionPlanFingerprint.create(
                        manifest(3.0, 2), combination("windows", "deepseek-v4-pro"), PRODUCT, CONFIG));
        assertNotEquals(
                baseline,
                SuiteExecutionPlanFingerprint.create(
                        manifest(3.0, 1), combination("linux", "deepseek-v4-pro"), PRODUCT, CONFIG));
        assertNotEquals(
                baseline,
                SuiteExecutionPlanFingerprint.create(
                        manifest(3.0, 1), combination("windows", "deepseek-r2"), PRODUCT, CONFIG));
    }

    @Test
    void changesWhenEitherRepositoryRevisionChanges() {
        SuiteManifest manifest = manifest(3.0, 1);
        MatrixManifest.Combination combination = combination("linux", "deepseek-v4-pro");
        SuiteExecutionPlanFingerprint baseline =
                SuiteExecutionPlanFingerprint.create(manifest, combination, PRODUCT, CONFIG);

        assertNotEquals(baseline, SuiteExecutionPlanFingerprint.create(manifest, combination, revision('c'), CONFIG));
        assertNotEquals(baseline, SuiteExecutionPlanFingerprint.create(manifest, combination, PRODUCT, revision('d')));
    }

    private static SuiteManifest manifest(double cost, int repetitions) {
        return new SuiteManifest(
                1,
                "nightly-provider-smoke-v1",
                "primary-v1",
                new SuiteManifest.Budget(20, cost, 1),
                List.of(new SuiteManifest.CaseSelection("CP-01", repetitions, true)));
    }

    private static MatrixManifest.Combination combination(String platform, String modelId) {
        return new MatrixManifest.Combination(
                platform + "-deepseek-primary", platform, "deepseek", modelId, "aliyun", "utility");
    }

    private static RepositoryRevision revision(char value) {
        return new RepositoryRevision(String.valueOf(value).repeat(40), false);
    }
}
