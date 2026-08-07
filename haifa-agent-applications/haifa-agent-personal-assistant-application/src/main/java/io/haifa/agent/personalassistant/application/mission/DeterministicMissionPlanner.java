package io.haifa.agent.personalassistant.application.mission;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/** Explicit offline planner for tests and explicitly configured deterministic development. */
public final class DeterministicMissionPlanner implements MissionPlanner {
    @Override
    public PlanningResult plan(PlanningRequest request) {
        int count = Math.min(
                request.constraints().maxTasks(),
                Math.max(1, request.acceptanceCriteria().size()));
        List<MissionTask> tasks = new ArrayList<>(count);
        for (int index = 0; index < count; index++) {
            String taskId = "task-" + (index + 1);
            List<String> criteria = List.of(request.acceptanceCriteria()
                    .get(Math.min(index, request.acceptanceCriteria().size() - 1)));
            tasks.add(new MissionTask(
                    taskId,
                    index + 1,
                    "Complete acceptance item " + (index + 1),
                    request.objective() + " — " + criteria.getFirst(),
                    criteria,
                    index == 0 ? List.of() : List.of("task-" + index),
                    "GENERAL",
                    Set.of(),
                    "pa.task-result",
                    "v1",
                    MissionTaskState.PLANNED));
        }
        return new PlanningResult(
                MissionPlanRevision.SCHEMA_ID,
                MissionPlanRevision.SCHEMA_VERSION,
                tasks,
                Optional.empty(),
                Optional.empty());
    }
}
