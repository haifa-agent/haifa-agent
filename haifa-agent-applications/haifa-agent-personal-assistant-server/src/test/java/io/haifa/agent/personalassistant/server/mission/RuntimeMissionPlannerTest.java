package io.haifa.agent.personalassistant.server.mission;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.haifa.agent.personalassistant.application.mission.MissionConstraints;
import io.haifa.agent.personalassistant.application.mission.MissionException;
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

    private static RuntimeMissionPlanner planner(String output) {
        MissionRuntimeAccess runtime =
                ignored -> new MissionRuntimeAccess.PlannerRunResult("session-1", "run-1", output);
        return new RuntimeMissionPlanner(runtime, new ObjectMapper());
    }

    private static MissionPlanner.PlanningRequest request() {
        return new MissionPlanner.PlanningRequest(
                "mission-1", "Deliver", List.of("Accepted"), MissionConstraints.DEFAULT, 1);
    }
}
