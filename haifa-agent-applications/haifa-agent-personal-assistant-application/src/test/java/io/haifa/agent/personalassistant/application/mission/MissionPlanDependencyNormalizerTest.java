package io.haifa.agent.personalassistant.application.mission;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class MissionPlanDependencyNormalizerTest {
    @Test
    void removesOnlyTheEarliestEdgeNeededToKeepTheDownstreamChainWithinTheLimit() {
        List<MissionTask> normalized = MissionPlanDependencyNormalizer.flattenToMaximumDepth(
                List.of(
                        task("first", 1, List.of()),
                        task("second", 2, List.of("first")),
                        task("third", 3, List.of("second")),
                        task("fourth", 4, List.of("third")),
                        task("fifth", 5, List.of("fourth"))),
                4);

        assertThat(normalized)
                .extracting(MissionTask::dependsOn)
                .containsExactly(List.of(), List.of(), List.of("second"), List.of("third"), List.of("fourth"));
        assertThat(new MissionPlanValidator(Set.of("GENERAL"), Set.of(), Set.of("pa.task-result@v1"))
                        .validate(normalized, new MissionConstraints(8, 4, java.util.Optional.empty())))
                .hasSize(5);
    }

    @Test
    void normalizesEveryExcessiveBranchDeterministically() {
        List<MissionTask> normalized = MissionPlanDependencyNormalizer.flattenToMaximumDepth(
                List.of(
                        task("root", 1, List.of()),
                        task("left", 2, List.of("root")),
                        task("right", 3, List.of("root")),
                        task("merge", 4, List.of("left", "right")),
                        task("deliver", 5, List.of("merge"))),
                3);

        assertThat(new MissionPlanValidator(Set.of("GENERAL"), Set.of(), Set.of("pa.task-result@v1"))
                        .validate(normalized, new MissionConstraints(8, 3, java.util.Optional.empty())))
                .hasSize(5);
        assertThat(normalized)
                .extracting(MissionTask::dependsOn)
                .containsExactly(List.of(), List.of(), List.of(), List.of("left", "right"), List.of("merge"));
    }

    private static MissionTask task(String id, int ordinal, List<String> dependencies) {
        return new MissionTask(
                id,
                ordinal,
                id,
                id,
                List.of("accepted"),
                dependencies,
                "GENERAL",
                Set.of(),
                "pa.task-result",
                "v1",
                MissionTaskState.PLANNED);
    }
}
