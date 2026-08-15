package io.haifa.agent.testing.suite;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import io.haifa.agent.testing.repository.RepositoryRevision;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

class SuiteExecutionPlanFingerprintTest {
    private static final RepositoryRevision PRODUCT = revision('a');
    private static final RepositoryRevision CONFIG = revision('b');
    private static final ResolvedAgentProfile PROFILE = profile('e');

    @Test
    void isStableForTheSameResolvedExecutionPlan() {
        SuiteManifest manifest = manifest(3.0, 1);
        MatrixManifest.Combination combination = combination("windows");

        assertEquals(
                SuiteExecutionPlanFingerprint.create(manifest, combination, PROFILE, PRODUCT, CONFIG),
                SuiteExecutionPlanFingerprint.create(manifest, combination, PROFILE, PRODUCT, CONFIG));
    }

    @Test
    void keepsTheReviewedLinuxNightlySmokeDigestStable() {
        assertEquals(
                "f794530d557335969e9d3c11f31767389173ddb02f607a0810cca6a25418b5ba",
                SuiteExecutionPlanFingerprint.create(manifest(3.0, 1), combination("linux"), PROFILE, PRODUCT, CONFIG)
                        .sha256());
    }

    @Test
    void changesWhenBudgetCaseSelectionOrCombinationChanges() {
        SuiteExecutionPlanFingerprint baseline = SuiteExecutionPlanFingerprint.create(
                manifest(3.0, 1), combination("windows"), PROFILE, PRODUCT, CONFIG);

        assertNotEquals(
                baseline,
                SuiteExecutionPlanFingerprint.create(
                        manifest(3.01, 1), combination("windows"), PROFILE, PRODUCT, CONFIG));
        assertNotEquals(
                baseline,
                SuiteExecutionPlanFingerprint.create(
                        manifest(3.0, 2), combination("windows"), PROFILE, PRODUCT, CONFIG));
        assertNotEquals(
                baseline,
                SuiteExecutionPlanFingerprint.create(manifest(3.0, 1), combination("linux"), PROFILE, PRODUCT, CONFIG));
        assertNotEquals(
                baseline,
                SuiteExecutionPlanFingerprint.create(
                        manifest(3.0, 1), combination("windows"), profile('f'), PRODUCT, CONFIG));
    }

    @Test
    void changesWhenEitherRepositoryRevisionChanges() {
        SuiteManifest manifest = manifest(3.0, 1);
        MatrixManifest.Combination combination = combination("linux");
        SuiteExecutionPlanFingerprint baseline =
                SuiteExecutionPlanFingerprint.create(manifest, combination, PROFILE, PRODUCT, CONFIG);

        assertNotEquals(
                baseline, SuiteExecutionPlanFingerprint.create(manifest, combination, PROFILE, revision('c'), CONFIG));
        assertNotEquals(
                baseline, SuiteExecutionPlanFingerprint.create(manifest, combination, PROFILE, PRODUCT, revision('d')));
    }

    private static SuiteManifest manifest(double cost, int repetitions) {
        return new SuiteManifest(
                1,
                "nightly-provider-smoke-v1",
                "primary-v1",
                new SuiteManifest.Budget(20, cost, 1),
                List.of(new SuiteManifest.CaseSelection("CP-01", repetitions, true)));
    }

    private static MatrixManifest.Combination combination(String platform) {
        return new MatrixManifest.Combination(platform + "-primary", platform);
    }

    private static ResolvedAgentProfile profile(char digest) {
        return new ResolvedAgentProfile(
                new AgentProfileManifest(
                        1,
                        "coding-primary",
                        String.valueOf('a').repeat(40),
                        "environments/coding-primary.yaml",
                        String.valueOf(digest).repeat(64)),
                Path.of("coding-primary.yaml"),
                String.valueOf(digest).repeat(64),
                List.of("MODEL_API_KEY"));
    }

    private static RepositoryRevision revision(char value) {
        return new RepositoryRevision(String.valueOf(value).repeat(40), false);
    }
}
