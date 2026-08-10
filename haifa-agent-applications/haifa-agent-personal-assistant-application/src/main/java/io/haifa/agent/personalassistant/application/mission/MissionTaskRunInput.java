package io.haifa.agent.personalassistant.application.mission;

import java.util.List;
import java.util.Objects;

/** Immutable, digest-bound input snapshot for one Mission Task Attempt. */
public record MissionTaskRunInput(
        String schemaVersion,
        String missionObjective,
        List<String> missionAcceptanceCriteria,
        String taskObjective,
        List<String> taskAcceptanceCriteria,
        String taskType,
        List<String> requiredSkillIds,
        String resultSchemaId,
        String resultSchemaVersion,
        String executionProfileId,
        int researchToolCallHardLimit,
        int researchToolCallStopTarget,
        int fetchMaxCharacters,
        List<DependencyResult> dependencyResults) {
    public static final String SCHEMA_VERSION = "pa.mission-task-run-input/v1";
    public static final String PRIMARY_RESEARCH_PROFILE = "personal-mission-task";
    public static final String DEPENDENCY_AWARE_RESEARCH_PROFILE = "personal-mission-dependent-task";
    public static final int PRIMARY_RESEARCH_TOOL_CALL_HARD_LIMIT = 40;
    public static final int DEPENDENCY_AWARE_TOOL_CALL_HARD_LIMIT = 32;
    public static final int PRIMARY_RESEARCH_TOOL_CALL_STOP_TARGET = 24;
    public static final int DEPENDENCY_AWARE_TOOL_CALL_STOP_TARGET = 16;
    public static final int PRIMARY_FETCH_MAX_CHARACTERS = 10_000;
    public static final int DEPENDENCY_AWARE_FETCH_MAX_CHARACTERS = 8_000;

    public MissionTaskRunInput {
        if (!SCHEMA_VERSION.equals(schemaVersion)) {
            throw new MissionException("MISSION_INPUT_SCHEMA_INVALID", "Mission Task input schema is unsupported");
        }
        missionObjective = MissionValues.text(missionObjective, "mission objective", 8_000);
        missionAcceptanceCriteria =
                MissionValues.texts(missionAcceptanceCriteria, "mission acceptance criteria", 20, 1_000);
        taskObjective = MissionValues.text(taskObjective, "task objective", 8_000);
        taskAcceptanceCriteria = MissionValues.texts(taskAcceptanceCriteria, "task acceptance criteria", 20, 1_000);
        taskType = MissionValues.text(taskType, "task type", 32);
        requiredSkillIds = List.copyOf(Objects.requireNonNull(requiredSkillIds));
        if (requiredSkillIds.size() > 16) {
            throw new MissionException("MISSION_LIMIT_EXCEEDED", "required Skill IDs must not exceed 16");
        }
        requiredSkillIds = requiredSkillIds.stream()
                .map(value -> MissionValues.text(value, "required Skill ID", 128))
                .toList();
        resultSchemaId = MissionValues.text(resultSchemaId, "result schema ID", 128);
        resultSchemaVersion = MissionValues.text(resultSchemaVersion, "result schema version", 64);
        executionProfileId = MissionValues.text(executionProfileId, "execution profile ID", 128);
        if (researchToolCallHardLimit < 1 || researchToolCallHardLimit > 64) {
            throw new MissionException("MISSION_LIMIT_EXCEEDED", "research Tool call hard limit is invalid");
        }
        if (researchToolCallStopTarget < 1 || researchToolCallStopTarget > 64) {
            throw new MissionException("MISSION_LIMIT_EXCEEDED", "research Tool call stop target is invalid");
        }
        if (fetchMaxCharacters < 1 || fetchMaxCharacters > 20_000) {
            throw new MissionException("MISSION_LIMIT_EXCEEDED", "fetch character limit is invalid");
        }
        dependencyResults = List.copyOf(Objects.requireNonNull(dependencyResults));
        if (dependencyResults.size() > 15) {
            throw new MissionException("MISSION_LIMIT_EXCEEDED", "dependency results must not exceed 15");
        }
        boolean dependencyAware = !dependencyResults.isEmpty();
        String expectedProfile = dependencyAware ? DEPENDENCY_AWARE_RESEARCH_PROFILE : PRIMARY_RESEARCH_PROFILE;
        int expectedToolTarget =
                dependencyAware ? DEPENDENCY_AWARE_TOOL_CALL_STOP_TARGET : PRIMARY_RESEARCH_TOOL_CALL_STOP_TARGET;
        int expectedToolHardLimit =
                dependencyAware ? DEPENDENCY_AWARE_TOOL_CALL_HARD_LIMIT : PRIMARY_RESEARCH_TOOL_CALL_HARD_LIMIT;
        int expectedFetchLimit = dependencyAware ? DEPENDENCY_AWARE_FETCH_MAX_CHARACTERS : PRIMARY_FETCH_MAX_CHARACTERS;
        if (!executionProfileId.equals(expectedProfile)
                || researchToolCallHardLimit != expectedToolHardLimit
                || researchToolCallStopTarget != expectedToolTarget
                || fetchMaxCharacters != expectedFetchLimit) {
            throw new MissionException(
                    "MISSION_INPUT_PROFILE_INVALID", "Mission Task input does not match its dependency-aware profile");
        }
    }

    public static MissionTaskRunInput create(
            String missionObjective,
            List<String> missionAcceptanceCriteria,
            String taskObjective,
            List<String> taskAcceptanceCriteria,
            String taskType,
            List<String> requiredSkillIds,
            String resultSchemaId,
            String resultSchemaVersion,
            List<DependencyResult> dependencyResults) {
        boolean dependencyAware = !dependencyResults.isEmpty();
        return new MissionTaskRunInput(
                SCHEMA_VERSION,
                missionObjective,
                missionAcceptanceCriteria,
                taskObjective,
                taskAcceptanceCriteria,
                taskType,
                requiredSkillIds,
                resultSchemaId,
                resultSchemaVersion,
                dependencyAware ? DEPENDENCY_AWARE_RESEARCH_PROFILE : PRIMARY_RESEARCH_PROFILE,
                dependencyAware ? DEPENDENCY_AWARE_TOOL_CALL_HARD_LIMIT : PRIMARY_RESEARCH_TOOL_CALL_HARD_LIMIT,
                dependencyAware ? DEPENDENCY_AWARE_TOOL_CALL_STOP_TARGET : PRIMARY_RESEARCH_TOOL_CALL_STOP_TARGET,
                dependencyAware ? DEPENDENCY_AWARE_FETCH_MAX_CHARACTERS : PRIMARY_FETCH_MAX_CHARACTERS,
                dependencyResults);
    }

    /** Immutable completed output of one direct dependency in frozen plan order. */
    public record DependencyResult(
            String taskId, String resultSchemaId, String resultSchemaVersion, String resultDigest, String resultJson) {
        public DependencyResult {
            taskId = MissionValues.text(taskId, "dependency task ID", 128);
            resultSchemaId = MissionValues.text(resultSchemaId, "dependency result schema ID", 128);
            resultSchemaVersion = MissionValues.text(resultSchemaVersion, "dependency result schema version", 64);
            resultDigest = MissionValues.text(resultDigest, "dependency result digest", 128);
            resultJson = MissionValues.text(resultJson, "dependency result", 1_000_000);
        }
    }
}
