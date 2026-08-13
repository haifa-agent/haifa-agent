package io.haifa.agent.personalassistant.application.mission;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

class MissionDomainTest {
    private static final Instant NOW = Instant.parse("2026-08-08T00:00:00Z");
    private final MissionPlanValidator validator = MissionPlanValidator.phaseOne();

    @Test
    void confirmsOneImmutableOrderedPlan() {
        PersonalMission mission = PersonalMission.create(
                "mission-1",
                "conversation-1",
                "local/public-user",
                "Prepare a release brief",
                List.of("Brief is reviewed"),
                MissionConstraints.DEFAULT,
                Optional.empty(),
                NOW);
        mission.proposePlan(List.of(task("task-1", 1, List.of())), Optional.empty(), Optional.empty(), validator, NOW);
        mission.confirm(NOW);

        assertThat(mission.snapshot().state()).isEqualTo(MissionState.RUNNING);
        assertThat(mission.snapshot().plan())
                .get()
                .extracting(MissionPlanRevision::revisionNo)
                .isEqualTo(1);
        assertThatThrownBy(() -> mission.proposePlan(
                        List.of(task("task-2", 1, List.of())), Optional.empty(), Optional.empty(), validator, NOW))
                .isInstanceOf(MissionException.class)
                .extracting(value -> ((MissionException) value).code())
                .isEqualTo("MISSION_PLAN_FROZEN");
    }

    @Test
    void freezesTheConversationModelBindingInTheMissionSnapshot() {
        MissionModelBinding binding = new MissionModelBinding(
                "qwen3.7-plus", "Qwen3.7 Plus", "aliyun-bailian", "阿里云百炼", "sha256:configured-model-snapshot");

        PersonalMission mission = PersonalMission.create(
                "mission-qwen",
                "conversation-qwen",
                "local/public-user",
                "Prepare a research brief",
                List.of("Brief is reviewed"),
                MissionConstraints.DEFAULT,
                Optional.empty(),
                Optional.empty(),
                MissionMode.STANDARD,
                Optional.empty(),
                binding,
                NOW);

        assertThat(mission.snapshot().modelBinding()).isEqualTo(binding);
        assertThat(mission.persistence().modelBinding()).isEqualTo(binding);
    }

    @Test
    void rejectsMissingDependencyCycleAndDepthOverflow() {
        assertThatThrownBy(() ->
                        validator.validate(List.of(task("task-1", 1, List.of("missing"))), MissionConstraints.DEFAULT))
                .isInstanceOf(MissionException.class)
                .hasMessageContaining("does not exist");
        assertThatThrownBy(() -> validator.validate(
                        List.of(task("task-1", 1, List.of("task-2")), task("task-2", 2, List.of("task-1"))),
                        MissionConstraints.DEFAULT))
                .isInstanceOf(MissionException.class);
        assertThatThrownBy(() -> validator.validate(
                        List.of(
                                task("task-1", 1, List.of()),
                                task("task-2", 2, List.of("task-1")),
                                task("task-3", 3, List.of("task-2"))),
                        new MissionConstraints(8, 2, Optional.empty())))
                .isInstanceOf(MissionException.class)
                .hasMessageContaining("depth");
    }

    @Test
    void cancelledMissionCannotBeConfirmed() {
        PersonalMission mission = PersonalMission.create(
                "mission-1",
                "conversation-1",
                "local/public-user",
                "Prepare a release brief",
                List.of("Brief is reviewed"),
                MissionConstraints.DEFAULT,
                Optional.empty(),
                NOW);
        mission.proposePlan(List.of(task("task-1", 1, List.of())), Optional.empty(), Optional.empty(), validator, NOW);
        mission.cancel(NOW);

        assertThatThrownBy(() -> mission.confirm(NOW))
                .isInstanceOf(MissionException.class)
                .extracting(value -> ((MissionException) value).code())
                .isEqualTo("MISSION_CANCELLED");
    }

    static MissionTask task(String id, int ordinal, List<String> dependencies) {
        return new MissionTask(
                id,
                ordinal,
                "Task " + ordinal,
                "Complete task " + ordinal,
                List.of("Task " + ordinal + " is complete"),
                dependencies,
                "GENERAL",
                Set.of(),
                "pa.task-result",
                "v1",
                MissionTaskState.PLANNED);
    }
}
