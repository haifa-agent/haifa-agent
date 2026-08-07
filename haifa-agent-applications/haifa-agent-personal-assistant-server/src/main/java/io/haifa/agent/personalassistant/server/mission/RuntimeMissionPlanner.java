package io.haifa.agent.personalassistant.server.mission;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.haifa.agent.personalassistant.application.mission.MissionException;
import io.haifa.agent.personalassistant.application.mission.MissionPlanRevision;
import io.haifa.agent.personalassistant.application.mission.MissionPlanner;
import io.haifa.agent.personalassistant.application.mission.MissionRuntimeAccess;
import io.haifa.agent.personalassistant.application.mission.MissionTask;
import io.haifa.agent.personalassistant.application.mission.MissionTaskState;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/** Strict JSON decoder around one standard Runtime Planner Run. No prose extraction or fallback is allowed. */
public final class RuntimeMissionPlanner implements MissionPlanner {
    private final MissionRuntimeAccess runtime;
    private final ObjectMapper mapper;

    public RuntimeMissionPlanner(MissionRuntimeAccess runtime, ObjectMapper mapper) {
        this.runtime = java.util.Objects.requireNonNull(runtime);
        this.mapper = mapper.copy()
                .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    }

    @Override
    public PlanningResult plan(PlanningRequest request) {
        MissionRuntimeAccess.PlannerRunResult run = runtime.runPlanner(request);
        try {
            PlanPayload payload = mapper.readValue(run.structuredOutput(), PlanPayload.class);
            if (!"pa.mission-plan/v1".equals(payload.schemaVersion())) {
                throw new MissionException("MISSION_PLAN_SCHEMA_UNSUPPORTED", "Planner schemaVersion is unsupported");
            }
            List<MissionTask> tasks = payload.tasks().stream()
                    .map(task -> new MissionTask(
                            task.taskId(),
                            task.ordinal(),
                            task.title(),
                            task.objective(),
                            task.acceptanceCriteria(),
                            task.dependsOn(),
                            task.taskType(),
                            task.requiredSkillIds(),
                            task.resultSchema().id(),
                            task.resultSchema().version(),
                            MissionTaskState.PLANNED))
                    .toList();
            return new PlanningResult(
                    MissionPlanRevision.SCHEMA_ID,
                    MissionPlanRevision.SCHEMA_VERSION,
                    tasks,
                    Optional.of(run.sessionId()),
                    Optional.of(run.runId()));
        } catch (JsonProcessingException | NullPointerException exception) {
            throw new MissionException(
                    "MISSION_PLAN_SCHEMA_INVALID", "Planner output is not valid pa.mission-plan/v1 JSON", exception);
        }
    }

    private record PlanPayload(String schemaVersion, List<TaskPayload> tasks) {
        private PlanPayload {
            tasks = List.copyOf(tasks);
        }
    }

    private record TaskPayload(
            String taskId,
            int ordinal,
            String title,
            String objective,
            List<String> acceptanceCriteria,
            List<String> dependsOn,
            String taskType,
            Set<String> requiredSkillIds,
            ResultSchemaPayload resultSchema) {
        private TaskPayload {
            acceptanceCriteria = List.copyOf(acceptanceCriteria);
            dependsOn = List.copyOf(dependsOn);
            requiredSkillIds = Set.copyOf(requiredSkillIds);
        }
    }

    private record ResultSchemaPayload(String id, String version) {}
}
