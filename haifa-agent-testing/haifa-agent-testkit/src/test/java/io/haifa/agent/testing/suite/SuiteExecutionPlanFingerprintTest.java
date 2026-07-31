package io.haifa.agent.testing.suite;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import java.util.List;
import org.junit.jupiter.api.Test;

class SuiteExecutionPlanFingerprintTest {
    @Test
    void isStableForTheSameResolvedExecutionPlan() {
        SuiteManifest manifest = manifest(3.0, 1);
        MatrixManifest.Combination combination = combination("windows", "deepseek-v4-pro");

        assertEquals(
                SuiteExecutionPlanFingerprint.create(manifest, combination),
                SuiteExecutionPlanFingerprint.create(manifest, combination));
    }

    @Test
    void keepsTheReviewedLinuxNightlySmokeDigestStable() {
        assertEquals(
                "4d13640977fdb973d0f21815788e716776de59df09d0db9f4a3535f7fe114761",
                SuiteExecutionPlanFingerprint.create(manifest(3.0, 1), combination("linux", "deepseek-v4-pro"))
                        .sha256());
    }

    @Test
    void changesWhenBudgetCaseSelectionOrCombinationChanges() {
        SuiteExecutionPlanFingerprint baseline =
                SuiteExecutionPlanFingerprint.create(manifest(3.0, 1), combination("windows", "deepseek-v4-pro"));

        assertNotEquals(
                baseline,
                SuiteExecutionPlanFingerprint.create(manifest(3.01, 1), combination("windows", "deepseek-v4-pro")));
        assertNotEquals(
                baseline,
                SuiteExecutionPlanFingerprint.create(manifest(3.0, 2), combination("windows", "deepseek-v4-pro")));
        assertNotEquals(
                baseline,
                SuiteExecutionPlanFingerprint.create(manifest(3.0, 1), combination("linux", "deepseek-v4-pro")));
        assertNotEquals(
                baseline,
                SuiteExecutionPlanFingerprint.create(manifest(3.0, 1), combination("windows", "deepseek-r2")));
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
}
