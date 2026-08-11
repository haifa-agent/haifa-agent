package io.haifa.agent.personalassistant.server.mission;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.haifa.agent.personalassistant.application.mission.MissionException;
import io.haifa.agent.personalassistant.application.mission.MissionPlanDependencyNormalizer;
import io.haifa.agent.personalassistant.application.mission.MissionPlanRevision;
import io.haifa.agent.personalassistant.application.mission.MissionPlanValidator;
import io.haifa.agent.personalassistant.application.mission.MissionPlanner;
import io.haifa.agent.personalassistant.application.mission.MissionRuntimeAccess;
import io.haifa.agent.personalassistant.application.mission.MissionTask;
import io.haifa.agent.personalassistant.application.mission.MissionTaskState;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/** Strict JSON decoder with one bounded Runtime repair Run. No prose extraction or permissive fallback is allowed. */
public final class RuntimeMissionPlanner implements MissionPlanner {
    private final MissionRuntimeAccess runtime;
    private final MissionPlanValidator validator;
    private final ObjectMapper mapper;

    public RuntimeMissionPlanner(MissionRuntimeAccess runtime, MissionPlanValidator validator, ObjectMapper mapper) {
        this.runtime = java.util.Objects.requireNonNull(runtime);
        this.validator = java.util.Objects.requireNonNull(validator);
        this.mapper = mapper.copy()
                .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    }

    @Override
    public PlanningResult plan(PlanningRequest request) {
        MissionRuntimeAccess.PlannerRunResult run = runtime.runPlanner(request);
        try {
            return decode(run, request, false);
        } catch (InvalidPlanPayloadException invalid) {
            MissionRuntimeAccess.PlannerRunResult repaired =
                    runtime.repairPlanner(request, run, invalid.code(), invalid.getMessage(), 1);
            try {
                return decode(repaired, request, true);
            } catch (InvalidPlanPayloadException stillInvalid) {
                throw new MissionException(
                        stillInvalid.code(),
                        "Planner output remained invalid after one bounded repair attempt: "
                                + stillInvalid.getMessage(),
                        stillInvalid.getCause());
            }
        }
    }

    private PlanningResult decode(
            MissionRuntimeAccess.PlannerRunResult run, PlanningRequest request, boolean allowDeterministicDepthRepair) {
        try {
            PlanPayload payload = mapper.readValue(run.structuredOutput(), PlanPayload.class);
            if (!"pa.mission-plan/v1".equals(payload.schemaVersion())) {
                throw new MissionException("MISSION_PLAN_SCHEMA_UNSUPPORTED", "Planner schemaVersion is unsupported");
            }
            List<MissionTask> proposed = payload.tasks().stream()
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
            List<MissionTask> tasks;
            try {
                tasks = validator.validate(proposed, request.constraints());
            } catch (MissionException invalid) {
                if (!allowDeterministicDepthRepair
                        || !"MISSION_PLAN_DEPENDENCY_DEPTH_EXCEEDED".equals(invalid.code())) {
                    throw invalid;
                }
                tasks = validator.validate(
                        MissionPlanDependencyNormalizer.flattenToMaximumDepth(
                                proposed, request.constraints().maxDependencyDepth()),
                        request.constraints());
            }
            return new PlanningResult(
                    MissionPlanRevision.SCHEMA_ID,
                    MissionPlanRevision.SCHEMA_VERSION,
                    tasks,
                    Optional.of(run.sessionId()),
                    Optional.of(run.runId()));
        } catch (JsonProcessingException | NullPointerException exception) {
            throw new InvalidPlanPayloadException(
                    "MISSION_PLAN_SCHEMA_INVALID", "Planner output is not valid pa.mission-plan/v1 JSON", exception);
        } catch (MissionException exception) {
            throw new InvalidPlanPayloadException(exception.code(), exception.getMessage(), exception);
        }
    }

    private static final class InvalidPlanPayloadException extends RuntimeException {
        private final String code;

        private InvalidPlanPayloadException(String code, String message, Throwable cause) {
            super(message, cause);
            this.code = code;
        }

        private String code() {
            return code;
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
