package io.haifa.agent.application.project.product.coding.verification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.haifa.agent.application.project.product.coding.delivery.CodingValidationScope;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

class CodingVerificationProfileTest {
    @Test
    void resolvesCandidatesBySourcePriorityPerTriggerWithoutBuildingALanguagePluginFramework() {
        CodingVerificationCandidate explicit = candidate(
                "./mvnw -pl :module test",
                CodingVerificationSource.USER_EXPLICIT,
                CodingVerificationTrigger.FINAL_GATE);
        CodingVerificationCandidate repository = candidate(
                "./mvnw verify",
                CodingVerificationSource.REPOSITORY_INSTRUCTIONS,
                CodingVerificationTrigger.FINAL_GATE);
        CodingVerificationCandidate adjacent = candidate(
                "./mvnw -Dtest=FocusedTest test",
                CodingVerificationSource.ADJACENT_TEST,
                CodingVerificationTrigger.ADJACENT_CHANGE);
        CodingVerificationCandidate fallback = candidate(
                "./mvnw test", CodingVerificationSource.ECOSYSTEM_DEFAULT, CodingVerificationTrigger.FINAL_GATE);

        CodingVerificationProfile profile = new CodingVerificationProfileResolver()
                .resolve(List.of(explicit), List.of(repository), List.of(adjacent), List.of(fallback));

        assertThat(profile.candidates()).containsExactly(adjacent, explicit);
        assertThat(profile.ignoredCandidates()).containsExactly(repository, fallback);
        assertThat(profile.instructionText())
                .contains(
                        "sourcePriority=USER_EXPLICIT>REPOSITORY_INSTRUCTIONS>BUILD_CONFIGURATION>ADJACENT_TEST>ECOSYSTEM_DEFAULT")
                .contains("./mvnw -Dtest=FocusedTest test", "./mvnw -pl :module test")
                .doesNotContain("./mvnw verify", "./mvnw test");

        CodingSessionVerificationConfiguration frozen = CodingSessionVerificationConfiguration.freeze(profile);
        assertThat(CodingSessionVerificationConfiguration.fromSessionMetadata(frozen.sessionMetadata()))
                .contains(frozen);
        assertThat(frozen.digest()).hasSize(64);
    }

    @Test
    void rejectsMultiLineTrustedFieldsWithoutRestrictingShellComposition() {
        assertThat(candidate(
                                "./mvnw test && git diff --check || exit 1",
                                CodingVerificationSource.USER_EXPLICIT,
                                CodingVerificationTrigger.FINAL_GATE)
                        .command())
                .contains("&&", "||");
        assertThatThrownBy(() -> candidate(
                        "./mvnw test\necho injected",
                        CodingVerificationSource.USER_EXPLICIT,
                        CodingVerificationTrigger.FINAL_GATE))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("command is invalid");
    }

    private static CodingVerificationCandidate candidate(
            String command, CodingVerificationSource source, CodingVerificationTrigger trigger) {
        return new CodingVerificationCandidate(
                command,
                CodingVerificationCost.MEDIUM,
                Duration.ofMinutes(5),
                trigger,
                source,
                source.name(),
                trigger == CodingVerificationTrigger.ADJACENT_CHANGE
                        ? CodingValidationScope.SELECTED
                        : CodingValidationScope.FULL);
    }
}
