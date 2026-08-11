package io.haifa.agent.personalassistant.server.mission;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.haifa.agent.personalassistant.application.mission.MissionConstraints;
import io.haifa.agent.personalassistant.application.mission.MissionException;
import io.haifa.agent.personalassistant.application.mission.MissionPlanValidator;
import io.haifa.agent.personalassistant.application.mission.MissionPlanner;
import io.haifa.agent.personalassistant.application.mission.MissionRuntimeAccess;
import java.util.List;
import org.junit.jupiter.api.Test;

class RuntimeMissionPlannerTest {
    @Test
    void acceptsOnlyTheFrozenStructuredPlanSchema() {
        var planner = planner(
                """
                {"schemaVersion":"pa.mission-plan/v1","tasks":[{"taskId":"task-1","ordinal":1,
                "title":"Plan","objective":"Plan the delivery","acceptanceCriteria":["Explicit"],
                "dependsOn":[],"taskType":"GENERAL","requiredSkillIds":[],
                "resultSchema":{"id":"pa.task-result","version":"v1"}}]}
                """);

        var result = planner.plan(request());

        assertThat(result.tasks()).singleElement().satisfies(task -> {
            assertThat(task.taskId()).isEqualTo("task-1");
            assertThat(task.state().name()).isEqualTo("PLANNED");
        });
        assertThat(result.plannerSessionId()).contains("session-1");
        assertThat(result.plannerRunId()).contains("run-1");
    }

    @Test
    void rejectsProseFencesUnknownFieldsAndTrailingJson() {
        for (String output : List.of(
                "Here is the plan: {}",
                "```json\n{}\n```",
                "{\"schemaVersion\":\"pa.mission-plan/v1\",\"tasks\":[],\"extra\":true}",
                "{\"schemaVersion\":\"pa.mission-plan/v1\",\"tasks\":[]} {}")) {
            assertThatThrownBy(() -> planner(output).plan(request()))
                    .isInstanceOf(MissionException.class)
                    .extracting(error -> ((MissionException) error).code())
                    .isEqualTo("MISSION_PLAN_SCHEMA_INVALID");
        }
    }

    @Test
    void repairsOneInvalidPlannerPayloadThroughAnExplicitBoundedRuntimeRun() {
        String invalid = validPlan() + "</result>\n</result>";
        var repairCalls = new java.util.concurrent.atomic.AtomicInteger();
        MissionRuntimeAccess runtime = new MissionRuntimeAccess() {
            @Override
            public PlannerRunResult runPlanner(MissionPlanner.PlanningRequest ignored) {
                return new PlannerRunResult("session-1", "run-1", invalid);
            }

            @Override
            public PlannerRunResult repairPlanner(
                    MissionPlanner.PlanningRequest ignored,
                    PlannerRunResult invalidRun,
                    String violationCode,
                    String violationMessage,
                    int repairAttemptNo) {
                assertThat(invalidRun.runId()).isEqualTo("run-1");
                assertThat(violationCode).isEqualTo("MISSION_PLAN_SCHEMA_INVALID");
                assertThat(repairAttemptNo).isEqualTo(1);
                repairCalls.incrementAndGet();
                return new PlannerRunResult("repair-session-1", "repair-run-1", validPlan());
            }
        };

        var result = new RuntimeMissionPlanner(runtime, validator(), new ObjectMapper()).plan(request());

        assertThat(repairCalls).hasValue(1);
        assertThat(result.plannerSessionId()).contains("repair-session-1");
        assertThat(result.plannerRunId()).contains("repair-run-1");
        assertThat(result.tasks()).singleElement();
    }

    @Test
    void repairsAValidJsonPlanThatViolatesTheRequestedDagDepth() {
        String tooDeep =
                """
                {"schemaVersion":"pa.mission-plan/v1","tasks":[
                  {"taskId":"first","ordinal":1,"title":"First","objective":"First","acceptanceCriteria":["A"],
                   "dependsOn":[],"taskType":"GENERAL","requiredSkillIds":[],"resultSchema":{"id":"pa.task-result","version":"v1"}},
                  {"taskId":"second","ordinal":2,"title":"Second","objective":"Second","acceptanceCriteria":["B"],
                   "dependsOn":["first"],"taskType":"GENERAL","requiredSkillIds":[],"resultSchema":{"id":"pa.task-result","version":"v1"}},
                  {"taskId":"third","ordinal":3,"title":"Third","objective":"Third","acceptanceCriteria":["C"],
                   "dependsOn":["second"],"taskType":"GENERAL","requiredSkillIds":[],"resultSchema":{"id":"pa.task-result","version":"v1"}}
                ]}
                """;
        var repaired = new java.util.concurrent.atomic.AtomicReference<String>();
        MissionRuntimeAccess runtime = new MissionRuntimeAccess() {
            @Override
            public PlannerRunResult runPlanner(MissionPlanner.PlanningRequest ignored) {
                return new PlannerRunResult("session-1", "run-1", tooDeep);
            }

            @Override
            public PlannerRunResult repairPlanner(
                    MissionPlanner.PlanningRequest ignored,
                    PlannerRunResult invalidRun,
                    String violationCode,
                    String violationMessage,
                    int repairAttemptNo) {
                repaired.set(violationCode + ":" + violationMessage);
                return new PlannerRunResult("repair-session-1", "repair-run-1", validPlan());
            }
        };
        var request = new MissionPlanner.PlanningRequest(
                "mission-1",
                "Deliver",
                List.of("Accepted"),
                new MissionConstraints(4, 2, java.util.Optional.empty()),
                1);

        var result = new RuntimeMissionPlanner(runtime, validator(), new ObjectMapper()).plan(request);

        assertThat(repaired.get()).contains("MISSION_PLAN_DEPENDENCY_DEPTH_EXCEEDED", "dependency depth");
        assertThat(result.plannerRunId()).contains("repair-run-1");
    }

    @Test
    void rejectsExcessiveSerialDependenciesThatRemainAfterTheSingleSchemaRepair() {
        String chain =
                """
                {"schemaVersion":"pa.mission-plan/v1","tasks":[
                  {"taskId":"first","ordinal":1,"title":"First","objective":"First","acceptanceCriteria":["A"],
                   "dependsOn":[],"taskType":"GENERAL","requiredSkillIds":[],"resultSchema":{"id":"pa.task-result","version":"v1"}},
                  {"taskId":"second","ordinal":2,"title":"Second","objective":"Second","acceptanceCriteria":["B"],
                   "dependsOn":["first"],"taskType":"GENERAL","requiredSkillIds":[],"resultSchema":{"id":"pa.task-result","version":"v1"}},
                  {"taskId":"third","ordinal":3,"title":"Third","objective":"Third","acceptanceCriteria":["C"],
                   "dependsOn":["second"],"taskType":"GENERAL","requiredSkillIds":[],"resultSchema":{"id":"pa.task-result","version":"v1"}},
                  {"taskId":"fourth","ordinal":4,"title":"Fourth","objective":"Fourth","acceptanceCriteria":["D"],
                   "dependsOn":["third"],"taskType":"GENERAL","requiredSkillIds":[],"resultSchema":{"id":"pa.task-result","version":"v1"}},
                  {"taskId":"fifth","ordinal":5,"title":"Fifth","objective":"Fifth","acceptanceCriteria":["E"],
                   "dependsOn":["fourth"],"taskType":"GENERAL","requiredSkillIds":[],"resultSchema":{"id":"pa.task-result","version":"v1"}}
                ]}
                """;
        var repairCalls = new java.util.concurrent.atomic.AtomicInteger();
        MissionRuntimeAccess runtime = new MissionRuntimeAccess() {
            @Override
            public PlannerRunResult runPlanner(MissionPlanner.PlanningRequest ignored) {
                return new PlannerRunResult("session-1", "run-1", chain + "</result>");
            }

            @Override
            public PlannerRunResult repairPlanner(
                    MissionPlanner.PlanningRequest ignored,
                    PlannerRunResult invalidRun,
                    String violationCode,
                    String violationMessage,
                    int repairAttemptNo) {
                assertThat(violationCode).isEqualTo("MISSION_PLAN_SCHEMA_INVALID");
                repairCalls.incrementAndGet();
                return new PlannerRunResult("repair-session-1", "repair-run-1", chain);
            }
        };
        var request = new MissionPlanner.PlanningRequest(
                "mission-1",
                "Summarize Ethereum upgrades",
                List.of("Cover the important changes"),
                new MissionConstraints(8, 4, java.util.Optional.empty()),
                1);

        assertThatThrownBy(() -> new RuntimeMissionPlanner(runtime, validator(), new ObjectMapper()).plan(request))
                .isInstanceOf(MissionException.class)
                .extracting(error -> ((MissionException) error).code())
                .isEqualTo("MISSION_PLAN_DEPENDENCY_DEPTH_EXCEEDED");
        assertThat(repairCalls).hasValue(1);
    }

    private static RuntimeMissionPlanner planner(String output) {
        MissionRuntimeAccess runtime = new MissionRuntimeAccess() {
            @Override
            public PlannerRunResult runPlanner(MissionPlanner.PlanningRequest ignored) {
                return new PlannerRunResult("session-1", "run-1", output);
            }

            @Override
            public PlannerRunResult repairPlanner(
                    MissionPlanner.PlanningRequest ignored,
                    PlannerRunResult invalidRun,
                    String violationCode,
                    String violationMessage,
                    int repairAttemptNo) {
                return new PlannerRunResult("repair-session-1", "repair-run-1", output);
            }
        };
        return new RuntimeMissionPlanner(runtime, validator(), new ObjectMapper());
    }

    private static MissionPlanValidator validator() {
        return new MissionPlanValidator(
                java.util.Set.of("GENERAL"), java.util.Set.of(), java.util.Set.of("pa.task-result@v1"));
    }

    private static String validPlan() {
        return """
                {"schemaVersion":"pa.mission-plan/v1","tasks":[{"taskId":"task-1","ordinal":1,
                "title":"Plan","objective":"Plan the delivery","acceptanceCriteria":["Explicit"],
                "dependsOn":[],"taskType":"GENERAL","requiredSkillIds":[],
                "resultSchema":{"id":"pa.task-result","version":"v1"}}]}
                """;
    }

    private static MissionPlanner.PlanningRequest request() {
        return new MissionPlanner.PlanningRequest(
                "mission-1", "Deliver", List.of("Accepted"), MissionConstraints.DEFAULT, 1);
    }
}
