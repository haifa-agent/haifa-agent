package io.haifa.agent.personalassistant.application.mission;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class MissionApplicationServiceTest {
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-08-08T00:00:00Z"), ZoneOffset.UTC);

    @Test
    void plannerFailureConvergesThePersistedMissionToFailed() {
        InMemoryMissionStore store = new InMemoryMissionStore();
        MissionApplicationService service = new MissionApplicationService(
                store,
                store,
                request -> {
                    throw new MissionException("MODEL_RESPONSE_INVALID", "planner response was invalid");
                },
                MissionPlanValidator.phaseOne(),
                () -> "mission-failed",
                CLOCK);

        assertThatThrownBy(() -> service.create(command("create-failed", "conversation-failed", "Research")))
                .isInstanceOf(MissionException.class)
                .extracting(value -> ((MissionException) value).code())
                .isEqualTo("MODEL_RESPONSE_INVALID");

        MissionSnapshot failed =
                service.find("mission-failed", "local/public-user").orElseThrow();
        assertThat(failed.state()).isEqualTo(MissionState.FAILED);
        assertThat(failed.failureCode()).contains("MODEL_RESPONSE_INVALID");
        assertThat(failed.finishedAt()).contains(CLOCK.instant());
        assertThat(store.findActive("conversation-failed", "local/public-user")).isEmpty();
    }

    @Test
    void createReplaceConfirmAndCancelAreIdempotent() {
        InMemoryMissionStore store = new InMemoryMissionStore();
        AtomicInteger ids = new AtomicInteger();
        AtomicInteger admissions = new AtomicInteger();
        MissionApplicationService service = new MissionApplicationService(
                store,
                store,
                new DeterministicMissionPlanner(),
                MissionPlanValidator.phaseOne(),
                () -> "mission-" + ids.incrementAndGet(),
                CLOCK,
                MissionExecutionStore.unavailable(),
                Map.of(),
                admissions::incrementAndGet);
        var create = new MissionApplicationService.CreateMission(
                "create-1",
                "local/public-user",
                "conversation-1",
                "Prepare a release brief",
                List.of("Architecture is covered", "Tests are covered"),
                MissionConstraints.DEFAULT);

        MissionSnapshot created = service.create(create);
        MissionSnapshot duplicate = service.create(create);
        assertThat(duplicate.missionId()).isEqualTo(created.missionId());
        assertThat(created.state()).isEqualTo(MissionState.WAITING_CONFIRMATION);
        assertThat(admissions).hasValue(1);

        var regenerate = new MissionApplicationService.RegenerateMissionPlan(
                "regenerate-1", "local/public-user", created.missionId(), created.version());
        MissionSnapshot regenerated = service.regeneratePlan(regenerate);
        assertThat(regenerated.plan().orElseThrow().revisionNo()).isEqualTo(2);
        assertThat(service.regeneratePlan(regenerate).version()).isEqualTo(regenerated.version());

        List<MissionTask> replacement = List.of(MissionDomainTest.task("review-release", 1, List.of()));
        var replace = new MissionApplicationService.ReplaceMissionPlan(
                "replace-1", "local/public-user", created.missionId(), regenerated.version(), replacement);
        MissionSnapshot replaced = service.replacePlan(replace);
        assertThat(service.replacePlan(replace).version()).isEqualTo(replaced.version());

        var confirm = new MissionApplicationService.ChangeMission(
                "confirm-1", "local/public-user", created.missionId(), replaced.version());
        MissionSnapshot confirmed = service.confirm(confirm);
        assertThat(service.confirm(confirm).version()).isEqualTo(confirmed.version());
        assertThat(confirmed.state()).isEqualTo(MissionState.RUNNING);

        var cancel = new MissionApplicationService.ChangeMission(
                "cancel-1", "local/public-user", created.missionId(), confirmed.version());
        assertThat(service.cancel(cancel).state()).isEqualTo(MissionState.CANCELLED);
        assertThat(service.cancel(cancel).state()).isEqualTo(MissionState.CANCELLED);
    }

    @Test
    void rejectsIdempotencyReuseSecondActiveMissionAndStaleRevision() {
        InMemoryMissionStore store = new InMemoryMissionStore();
        AtomicInteger ids = new AtomicInteger();
        MissionApplicationService service = new MissionApplicationService(
                store,
                store,
                new DeterministicMissionPlanner(),
                MissionPlanValidator.phaseOne(),
                () -> "mission-" + ids.incrementAndGet(),
                CLOCK);
        MissionSnapshot created = service.create(command("key-1", "conversation-1", "Objective one"));

        assertThatThrownBy(() -> service.create(command("key-1", "conversation-1", "Different objective")))
                .isInstanceOf(MissionException.class)
                .extracting(value -> ((MissionException) value).code())
                .isEqualTo("MISSION_IDEMPOTENCY_CONFLICT");
        assertThatThrownBy(() -> service.create(command("key-2", "conversation-1", "Another Mission")))
                .isInstanceOf(MissionException.class)
                .extracting(value -> ((MissionException) value).code())
                .isEqualTo("MISSION_ACTIVE_EXISTS");
        assertThatThrownBy(() -> service.replacePlan(new MissionApplicationService.ReplaceMissionPlan(
                        "replace-stale",
                        "local/public-user",
                        created.missionId(),
                        0,
                        List.of(MissionDomainTest.task("task-new", 1, List.of())))))
                .isInstanceOf(MissionException.class)
                .extracting(value -> ((MissionException) value).code())
                .isEqualTo("MISSION_REVISION_STALE");
    }

    @Test
    void deepResearchIsExplicitAndFreezesTheBundledSkillIntoResearchTasks() {
        InMemoryMissionStore store = new InMemoryMissionStore();
        MissionApplicationService service = new MissionApplicationService(
                store,
                store,
                new DeterministicMissionPlanner(),
                new MissionPlanValidator(
                        Set.of("GENERAL", "RESEARCH"),
                        Set.of("deep-research"),
                        Set.of("pa.task-result@v1", "pa.research-task-result@v1")),
                () -> "research-mission",
                CLOCK,
                MissionExecutionStore.unavailable(),
                Map.of(
                        "deep-research",
                        "product/classpath:personal-assistant-bundled@1/deep-research@2.1.0#sha256:test"));
        ResearchBrief brief = new ResearchBrief(
                "What does the evidence show?",
                "bounded",
                "2025-2026",
                "global",
                "technical",
                List.of("primary sources"),
                List.of("opinion"),
                "Markdown report");

        MissionSnapshot snapshot = service.create(new MissionApplicationService.CreateMission(
                "research-1",
                "local/public-user",
                "conversation-research",
                "Research a bounded question",
                List.of("Every material claim is cited"),
                MissionConstraints.DEFAULT,
                MissionMode.DEEP_RESEARCH,
                Optional.of(brief)));

        assertThat(snapshot.mode()).isEqualTo(MissionMode.DEEP_RESEARCH);
        assertThat(snapshot.researchBrief()).contains(brief);
        assertThat(snapshot.selectedSkillId()).contains("deep-research");
        assertThat(snapshot.selectedSkillBinding()).hasValueSatisfying(binding -> assertThat(binding)
                .contains("personal-assistant-bundled", "deep-research@2.1.0", "#sha256:"));
        assertThat(snapshot.plan().orElseThrow().tasks()).allSatisfy(task -> {
            assertThat(task.taskType()).isEqualTo("RESEARCH");
            assertThat(task.requiredSkillIds()).containsExactly("deep-research");
            assertThat(task.resultSchemaId()).isEqualTo("pa.research-task-result");
        });
    }

    private static MissionApplicationService.CreateMission command(String key, String conversation, String objective) {
        return new MissionApplicationService.CreateMission(
                key,
                "local/public-user",
                conversation,
                objective,
                List.of("Result is complete"),
                MissionConstraints.DEFAULT);
    }
}
