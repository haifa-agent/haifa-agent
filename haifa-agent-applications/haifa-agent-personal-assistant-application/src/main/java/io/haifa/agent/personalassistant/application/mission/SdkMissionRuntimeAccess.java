package io.haifa.agent.personalassistant.application.mission;

import io.haifa.agent.common.time.TimeProvider;
import io.haifa.agent.core.agent.AgentDefinitionId;
import io.haifa.agent.core.agent.AgentDefinitionVersion;
import io.haifa.agent.core.reference.PrincipalRef;
import io.haifa.agent.core.reference.TenantRef;
import io.haifa.agent.core.run.AgentRunStatus;
import io.haifa.agent.core.session.AgentSession;
import io.haifa.agent.core.session.AgentSessionId;
import io.haifa.agent.core.session.SessionScope;
import io.haifa.agent.personalassistant.application.PersonalModelCatalog;
import io.haifa.agent.personalassistant.application.PersonalModelOption;
import io.haifa.agent.runtime.api.AgentRunRequest;
import io.haifa.agent.runtime.api.RuntimeOverrides;
import io.haifa.agent.sdk.api.HaifaAgent;
import io.haifa.agent.sdk.spi.SdkPersistenceContribution;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** Creates a one-shot internal Session and invokes the standard Runtime for Mission planning. */
public final class SdkMissionRuntimeAccess implements MissionRuntimeAccess {
    public static final String PLANNER_RUN_PROFILE = "personal-mission-planner";

    private final HaifaAgent agent;
    private final SdkPersistenceContribution persistence;
    private final TenantRef tenant;
    private final PrincipalRef principal;
    private final TimeProvider time;
    private final PersonalModelCatalog models;
    private final String modelId;

    public SdkMissionRuntimeAccess(
            HaifaAgent agent,
            SdkPersistenceContribution persistence,
            TenantRef tenant,
            PrincipalRef principal,
            TimeProvider time,
            PersonalModelCatalog models,
            String modelId) {
        this.agent = Objects.requireNonNull(agent);
        this.persistence = Objects.requireNonNull(persistence);
        this.tenant = Objects.requireNonNull(tenant);
        this.principal = Objects.requireNonNull(principal);
        this.time = Objects.requireNonNull(time);
        this.models = Objects.requireNonNull(models);
        this.modelId = Objects.requireNonNull(modelId);
    }

    @Override
    public PlannerRunResult runPlanner(MissionPlanner.PlanningRequest request) {
        PersonalModelOption model = models.available().stream()
                .filter(value -> value.id().equals(modelId))
                .findFirst()
                .orElseThrow(() -> new MissionException(
                        "MODEL_STRUCTURED_OUTPUT_UNAVAILABLE", "Mission Planner model is unavailable"));
        if (!model.capabilities().contains("STRUCTURED_OUTPUT")) {
            throw new MissionException(
                    "MODEL_STRUCTURED_OUTPUT_UNAVAILABLE", "Mission Planner requires structured output");
        }
        String stable = MissionValues.digest(request.missionId(), Integer.toString(request.revisionNo()));
        AgentSessionId sessionId = new AgentSessionId("mission-planner-" + stable.substring("sha256:".length(), 38));
        persistence.inTransaction(() -> {
            Optional<AgentSession> existing =
                    persistence.runtimePersistence().sessions().find(sessionId);
            if (existing.isEmpty()) {
                persistence
                        .runtimePersistence()
                        .sessions()
                        .insert(AgentSession.open(
                                sessionId,
                                tenant,
                                principal,
                                null,
                                SessionScope.EPHEMERAL,
                                time.now(),
                                Map.of(
                                        "productId",
                                        "haifa-personal-assistant",
                                        "sessionKind",
                                        "MISSION_PLANNER",
                                        "missionId",
                                        request.missionId(),
                                        "planRevisionNo",
                                        request.revisionNo())));
            } else {
                AgentSession value = existing.orElseThrow();
                if (!value.tenant().equals(tenant)
                        || !value.owner().equals(principal)
                        || !"MISSION_PLANNER".equals(value.metadata().get("sessionKind"))
                        || !request.missionId().equals(value.metadata().get("missionId"))) {
                    throw new MissionException("MISSION_SESSION_CONFLICT", "Planner Session ownership is invalid");
                }
            }
            return null;
        });
        String dispatchKey = "mission:" + request.missionId() + ":plan:" + request.revisionNo();
        var started = agent.runs()
                .start(new AgentRunRequest(
                        dispatchKey,
                        new AgentDefinitionId("personal-assistant"),
                        Optional.of(new AgentDefinitionVersion(1, 0, 0)),
                        PLANNER_RUN_PROFILE,
                        sessionId,
                        Optional.empty(),
                        prompt(request),
                        List.of(),
                        RuntimeOverrides.NONE));
        try {
            var terminal = agent.runs()
                    .await(started.runId(), Duration.ofSeconds(120))
                    .orElseThrow(() -> new MissionException("MISSION_PLANNER_TIMEOUT", "Mission Planner timed out"));
            if (terminal.status() != AgentRunStatus.COMPLETED) {
                throw new MissionException(
                        "MISSION_PLANNER_FAILED",
                        terminal.error().map(error -> error.code().wireCode()).orElse("Mission Planner failed"));
            }
            String output = terminal.result()
                    .map(result -> result.summary())
                    .or(() -> terminal.output())
                    .filter(value -> !value.isBlank())
                    .orElseThrow(() -> new MissionException(
                            "MISSION_PLAN_SCHEMA_INVALID", "Mission Planner returned no structured output"));
            return new PlannerRunResult(sessionId.value(), terminal.runId().value(), output);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new MissionException("MISSION_PLANNER_INTERRUPTED", "Mission Planner was interrupted", exception);
        }
    }

    private static String prompt(MissionPlanner.PlanningRequest request) {
        return """
                Produce only one JSON object matching schema pa.mission-plan/v1. Do not use Markdown fences.
                Required shape: {"schemaVersion":"pa.mission-plan/v1","tasks":[{"taskId":"task-1","ordinal":1,
                "title":"...","objective":"...","acceptanceCriteria":["..."],"dependsOn":[],
                "taskType":"GENERAL","requiredSkillIds":[],"resultSchema":{"id":"pa.task-result","version":"v1"}}]}.
                Use at most %d tasks and dependency depth %d. Task IDs must be lower-case kebab-case, ordinals contiguous,
                and every dependency must refer to an earlier task. Only GENERAL tasks, no Skills.
                Mission objective: %s
                Acceptance criteria: %s
                """
                .formatted(
                        request.constraints().maxTasks(),
                        request.constraints().maxDependencyDepth(),
                        request.objective(),
                        request.acceptanceCriteria());
    }
}
