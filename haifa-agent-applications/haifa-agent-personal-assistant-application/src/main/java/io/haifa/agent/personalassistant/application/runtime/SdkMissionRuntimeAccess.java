package io.haifa.agent.personalassistant.application.runtime;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.haifa.agent.common.time.TimeProvider;
import io.haifa.agent.core.agent.AgentDefinitionId;
import io.haifa.agent.core.agent.AgentDefinitionVersion;
import io.haifa.agent.core.content.TextPart;
import io.haifa.agent.core.message.AgentMessageId;
import io.haifa.agent.core.message.MessageRole;
import io.haifa.agent.core.message.MessageStatus;
import io.haifa.agent.core.message.MessageVisibility;
import io.haifa.agent.core.reference.PrincipalRef;
import io.haifa.agent.core.reference.TenantRef;
import io.haifa.agent.core.run.AgentRunId;
import io.haifa.agent.core.run.AgentRunStatus;
import io.haifa.agent.core.session.AgentSession;
import io.haifa.agent.core.session.AgentSessionId;
import io.haifa.agent.core.session.SessionScope;
import io.haifa.agent.personalassistant.application.PersonalModelCatalog;
import io.haifa.agent.personalassistant.application.PersonalModelOption;
import io.haifa.agent.personalassistant.application.mission.MissionDispatchIntent;
import io.haifa.agent.personalassistant.application.mission.MissionException;
import io.haifa.agent.personalassistant.application.mission.MissionMode;
import io.haifa.agent.personalassistant.application.mission.MissionPlanner;
import io.haifa.agent.personalassistant.application.mission.MissionRuntimeAccess;
import io.haifa.agent.personalassistant.application.mission.MissionSynthesisIntent;
import io.haifa.agent.personalassistant.application.mission.MissionTaskRunInput;
import io.haifa.agent.personalassistant.application.mission.MissionUsage;
import io.haifa.agent.personalassistant.application.mission.ReportQualityGate;
import io.haifa.agent.runtime.api.AgentRunRequest;
import io.haifa.agent.runtime.api.RuntimeOverrides;
import io.haifa.agent.runtime.core.storage.SessionMessageDraft;
import io.haifa.agent.sdk.api.HaifaAgent;
import io.haifa.agent.sdk.spi.SdkPersistenceContribution;
import io.haifa.agent.skill.api.SkillContent;
import io.haifa.agent.tool.api.ToolSchema;
import io.haifa.agent.tool.core.JsonSchema202012Validator;
import io.haifa.agent.web.DefaultWebUrlPolicy;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Creates a one-shot internal Session and invokes the standard Runtime for Mission planning. */
public final class SdkMissionRuntimeAccess implements MissionRuntimeAccess {
    public static final String PLANNER_RUN_PROFILE = "personal-mission-planner";
    public static final String PLANNER_REPAIR_RUN_PROFILE = "personal-mission-planner-repair";
    public static final String PLANNER_REPAIR_PROTOCOL_VERSION = "v3";
    public static final String TASK_RUN_PROFILE = MissionTaskRunInput.PRIMARY_RESEARCH_PROFILE;
    public static final String DEPENDENT_TASK_RUN_PROFILE = MissionTaskRunInput.DEPENDENCY_AWARE_RESEARCH_PROFILE;
    public static final String TASK_NORMALIZER_RUN_PROFILE = "personal-mission-task-normalizer";
    public static final String TASK_NORMALIZATION_PROTOCOL_VERSION = "v5";
    private static final int TASK_NORMALIZATION_MAX_ATTEMPTS = 3;
    private static final int PLANNER_REPAIR_INPUT_MAX_CHARACTERS = 16_000;
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final DefaultWebUrlPolicy PUBLIC_WEB_URL_POLICY = new DefaultWebUrlPolicy();
    private static final Pattern PUBLIC_URL = Pattern.compile("https?://[^\\s<>\\\"'\\]\\[)]+");
    private static final Pattern SHA256_DIGEST = Pattern.compile("sha256:[a-f0-9]{64}");
    public static final String SYNTHESIS_RUN_PROFILE = "personal-mission-synthesis";
    public static final String RESEARCH_SYNTHESIS_RUN_PROFILE = "personal-mission-research-synthesis";
    public static final String SYNTHESIS_PROTOCOL_VERSION = "v5";
    private static final int SYNTHESIS_MAX_UNVERIFIED_CLAIMS = 320;
    public static final long TASK_MAX_TOOL_CALLS = MissionTaskRunInput.PRIMARY_RESEARCH_TOOL_CALL_HARD_LIMIT;
    public static final long TASK_RESEARCH_TOOL_CALL_TARGET =
            MissionTaskRunInput.PRIMARY_RESEARCH_TOOL_CALL_STOP_TARGET;
    public static final int TASK_FETCH_MAX_CHARACTERS = MissionTaskRunInput.PRIMARY_FETCH_MAX_CHARACTERS;

    private final HaifaAgent agent;
    private final SdkPersistenceContribution persistence;
    private final TenantRef tenant;
    private final PrincipalRef principal;
    private final TimeProvider time;
    private final PersonalModelCatalog models;
    private final String modelId;
    private final SkillContent deepResearchSkill;

    public SdkMissionRuntimeAccess(
            HaifaAgent agent,
            SdkPersistenceContribution persistence,
            TenantRef tenant,
            PrincipalRef principal,
            TimeProvider time,
            PersonalModelCatalog models,
            String modelId,
            SkillContent deepResearchSkill) {
        this.agent = Objects.requireNonNull(agent);
        this.persistence = Objects.requireNonNull(persistence);
        this.tenant = Objects.requireNonNull(tenant);
        this.principal = Objects.requireNonNull(principal);
        this.time = Objects.requireNonNull(time);
        this.models = Objects.requireNonNull(models);
        this.modelId = Objects.requireNonNull(modelId);
        this.deepResearchSkill = Objects.requireNonNull(deepResearchSkill);
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
        String stable = digest(request.missionId(), Integer.toString(request.revisionNo()));
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
                        plannerPrompt(request),
                        List.of(),
                        RuntimeOverrides.NONE));
        try {
            var terminal = agent.runs()
                    .await(started.runId(), Duration.ofSeconds(180))
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
            return new PlannerRunResult(sessionId.value(), terminal.runId().value(), output, usage(terminal.usage()));
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new MissionException("MISSION_PLANNER_INTERRUPTED", "Mission Planner was interrupted", exception);
        }
    }

    @Override
    public PlannerRunResult repairPlanner(
            MissionPlanner.PlanningRequest request,
            PlannerRunResult invalidRun,
            String violationCode,
            String violationMessage,
            int repairAttemptNo) {
        Objects.requireNonNull(request);
        Objects.requireNonNull(invalidRun);
        Objects.requireNonNull(violationCode);
        Objects.requireNonNull(violationMessage);
        if (repairAttemptNo != 1) {
            throw new IllegalArgumentException("Only the single bounded Mission Plan repair attempt is supported");
        }
        requireStructuredOutput("Mission Plan repair");
        String stable = digest(
                request.missionId(),
                Integer.toString(request.revisionNo()),
                invalidRun.runId(),
                "plan-repair-" + PLANNER_REPAIR_PROTOCOL_VERSION,
                Integer.toString(repairAttemptNo));
        AgentSessionId sessionId =
                new AgentSessionId("mission-planner-repair-" + stable.substring("sha256:".length(), 38));
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
                                        "MISSION_PLANNER_REPAIR",
                                        "missionId",
                                        request.missionId(),
                                        "planRevisionNo",
                                        Integer.toString(request.revisionNo()),
                                        "sourcePlannerRunId",
                                        invalidRun.runId(),
                                        "repairAttemptNo",
                                        Integer.toString(repairAttemptNo))));
            } else {
                AgentSession value = existing.orElseThrow();
                if (!value.tenant().equals(tenant)
                        || !value.owner().equals(principal)
                        || !"MISSION_PLANNER_REPAIR".equals(value.metadata().get("sessionKind"))
                        || !request.missionId().equals(value.metadata().get("missionId"))
                        || !invalidRun.runId().equals(value.metadata().get("sourcePlannerRunId"))) {
                    throw new MissionException(
                            "MISSION_SESSION_CONFLICT", "Planner repair Session ownership is invalid");
                }
            }
            return null;
        });
        var started = agent.runs()
                .start(new AgentRunRequest(
                        "mission:" + request.missionId() + ":plan:" + request.revisionNo() + ":repair:"
                                + repairAttemptNo + ":protocol:" + PLANNER_REPAIR_PROTOCOL_VERSION + ":source:"
                                + invalidRun.runId(),
                        new AgentDefinitionId("personal-assistant"),
                        Optional.of(new AgentDefinitionVersion(1, 0, 0)),
                        PLANNER_REPAIR_RUN_PROFILE,
                        sessionId,
                        Optional.empty(),
                        plannerRepairPrompt(request, invalidRun.structuredOutput(), violationCode, violationMessage),
                        List.of(),
                        RuntimeOverrides.NONE));
        try {
            var terminal = agent.runs()
                    .await(started.runId(), Duration.ofSeconds(120))
                    .orElseThrow(
                            () -> new MissionException("MISSION_PLAN_REPAIR_TIMEOUT", "Mission Plan repair timed out"));
            if (terminal.status() != AgentRunStatus.COMPLETED) {
                throw new MissionException(
                        "MISSION_PLAN_REPAIR_FAILED",
                        terminal.error().map(error -> error.code().wireCode()).orElse("Mission Plan repair failed"));
            }
            String output = terminal.result()
                    .map(result -> result.summary())
                    .or(() -> terminal.output())
                    .filter(value -> !value.isBlank())
                    .orElseThrow(() -> new MissionException(
                            "MISSION_PLAN_SCHEMA_INVALID", "Mission Plan repair returned no structured output"));
            return new PlannerRunResult(sessionId.value(), terminal.runId().value(), output, usage(terminal.usage()));
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new MissionException(
                    "MISSION_PLAN_REPAIR_INTERRUPTED", "Mission Plan repair was interrupted", exception);
        }
    }

    @Override
    public TaskRunBinding startTask(MissionDispatchIntent intent) {
        requireStructuredOutput("Mission Task");
        String stable = digest(intent.missionId(), intent.taskId(), Integer.toString(intent.attemptNo()));
        AgentSessionId sessionId = new AgentSessionId("mission-task-" + stable.substring("sha256:".length(), 38));
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
                                        "productId", "haifa-personal-assistant",
                                        "sessionKind", "MISSION_TASK",
                                        "missionId", intent.missionId(),
                                        "taskId", intent.taskId(),
                                        "attemptNo", Integer.toString(intent.attemptNo()),
                                        "dispatchPayloadDigest", intent.payloadDigest(),
                                        "executionProfileId", intent.runInput().executionProfileId(),
                                        "resultSchemaId", intent.resultSchemaId(),
                                        "resultSchemaVersion", intent.resultSchemaVersion(),
                                        "taskObjective", intent.objective())));
            } else {
                AgentSession value = existing.orElseThrow();
                if (!value.tenant().equals(tenant)
                        || !value.owner().equals(principal)
                        || !"MISSION_TASK".equals(value.metadata().get("sessionKind"))
                        || !intent.missionId().equals(value.metadata().get("missionId"))
                        || !intent.taskId().equals(value.metadata().get("taskId"))
                        || !intent.payloadDigest().equals(value.metadata().get("dispatchPayloadDigest"))
                        || !intent.runInput()
                                .executionProfileId()
                                .equals(value.metadata().get("executionProfileId"))) {
                    throw new MissionException("MISSION_SESSION_CONFLICT", "Task Session ownership is invalid");
                }
            }
            return null;
        });
        String objective = taskPrompt(intent, deepResearchSkill);
        var started = agent.runs()
                .start(new AgentRunRequest(
                        intent.dispatchKey(),
                        new AgentDefinitionId("personal-assistant"),
                        Optional.of(new AgentDefinitionVersion(1, 0, 0)),
                        intent.runInput().executionProfileId(),
                        sessionId,
                        Optional.empty(),
                        objective,
                        List.of(),
                        RuntimeOverrides.NONE));
        return new TaskRunBinding(sessionId.value(), started.runId().value());
    }

    static String taskPrompt(MissionDispatchIntent intent, SkillContent deepResearchSkill) {
        String skillContext = "";
        if (intent.requiredSkillIds().contains("deep-research")) {
            skillContext =
                    """
                    The Deep Research Product Skill was explicitly selected and is preloaded below. Follow it without
                    calling Skill discovery or Skill resource Tools. The Run's research Tool allowlist is authoritative.

                    [deep-research/SKILL.md]
                    %s

                    [deep-research/references/research-method.md]
                    %s

                    [deep-research/references/source-quality.md]
                    %s

                    [deep-research/references/citation-rules.md]
                    %s

                    [deep-research/schemas/research-task-result-v1.json]
                    %s
                    """
                            .formatted(
                                    deepResearchSkill.instructions(),
                                    deepResearchSkill.resource("references/research-method.md"),
                                    deepResearchSkill.resource("references/source-quality.md"),
                                    deepResearchSkill.resource("references/citation-rules.md"),
                                    deepResearchSkill.resource("schemas/research-task-result-v1.json"));
        }
        String dependencyContext =
                MissionDependencyContextProjector.project(intent.runInput().dependencyResults());
        return """
                Complete this Mission task and return a concise result that satisfies every acceptance criterion.
                Mission objective: %s
                Mission acceptance criteria: %s
                Task type: %s
                Required Skills: %s
                Task objective: %s
                Acceptance criteria: %s
                Required result schema: %s@%s

                Frozen direct dependency context (structured, digest-bound, and bounded):
                %s

                Treat completed dependency results as authoritative prior work. Do not repeat searches already covered
                by them. Use research Tools only to close an explicit evidence gap in this Task's acceptance criteria.

                This Run has a hard safety ceiling of %d research Tool calls. At %d completed Tool calls, Runtime
                switches the next model turn to FINALIZE_ONLY and removes all Tool definitions. Immediately return
                the best bounded final result from evidence already collected; do not request another Tool. Every
                web_fetch call must set maxCharacters to at most %d. Prefer several focused excerpts over a full page.
                When you have enough evidence, stop calling Tools even earlier. Always leave enough context and time
                for a final model turn that returns the required JSON result; incomplete but explicit coverage is
                better than losing the entire result by over-researching. In FINALIZE_ONLY, never print or serialize
                Tool-call syntax such as DSML, XML invoke tags, function_call, or tool_calls. Those are not Task
                results. Instead, synthesize the evidence already present into the required JSON object.

                %s
                """
                .formatted(
                        intent.runInput().missionObjective(),
                        intent.runInput().missionAcceptanceCriteria(),
                        intent.taskType(),
                        intent.requiredSkillIds(),
                        intent.objective(),
                        intent.acceptanceCriteria(),
                        intent.resultSchemaId(),
                        intent.resultSchemaVersion(),
                        dependencyContext,
                        intent.runInput().researchToolCallHardLimit(),
                        intent.runInput().researchToolCallStopTarget(),
                        intent.runInput().fetchMaxCharacters(),
                        skillContext);
    }

    @Override
    public TaskRunObservation observeTask(String runId) {
        var snapshot = agent.runs().find(new io.haifa.agent.core.run.AgentRunId(runId));
        if (snapshot.isEmpty()) {
            return new TaskRunObservation(
                    runId,
                    TaskRunState.OUTCOME_UNKNOWN,
                    Optional.empty(),
                    Optional.of("RUN_NOT_FOUND"),
                    MissionUsage.NONE);
        }
        var value = snapshot.orElseThrow();
        MissionUsage usage = usage(value.usage());
        return switch (value.status()) {
            case WAITING_APPROVAL, WAITING_INTERACTION ->
                new TaskRunObservation(runId, TaskRunState.WAITING_USER, Optional.empty(), Optional.empty(), usage);
            case COMPLETED ->
                completedTask(
                        runId, value.result().map(result -> result.summary()).or(() -> value.output()), usage);
            case FAILED, TIMEOUT ->
                new TaskRunObservation(
                        runId,
                        TaskRunState.FAILED,
                        Optional.empty(),
                        value.error()
                                .map(error -> error.code().wireCode())
                                .or(() -> Optional.of(value.status().name())),
                        usage);
            case CANCELLED ->
                new TaskRunObservation(runId, TaskRunState.CANCELLED, Optional.empty(), Optional.empty(), usage);
            default -> new TaskRunObservation(runId, TaskRunState.ACTIVE, Optional.empty(), Optional.empty(), usage);
        };
    }

    private TaskRunObservation completedTask(String runId, Optional<String> result, MissionUsage primaryUsage) {
        if (result.isEmpty() || result.orElseThrow().isBlank()) {
            return new TaskRunObservation(
                    runId,
                    TaskRunState.FAILED,
                    Optional.empty(),
                    Optional.of("MISSION_TASK_RESULT_MISSING"),
                    primaryUsage);
        }
        var persistedRun = persistence.runtimePersistence().runs().find(new AgentRunId(runId));
        if (persistedRun.isEmpty()) {
            return new TaskRunObservation(
                    runId, TaskRunState.OUTCOME_UNKNOWN, Optional.empty(), Optional.of("RUN_NOT_FOUND"), primaryUsage);
        }
        var session = persistence
                .runtimePersistence()
                .sessions()
                .find(persistedRun.orElseThrow().sessionId());
        if (session.isEmpty()
                || !"pa.research-task-result"
                        .equals(session.orElseThrow().metadata().get("resultSchemaId"))) {
            return new TaskRunObservation(runId, TaskRunState.COMPLETED, result, Optional.empty(), primaryUsage);
        }
        try {
            AgentSession sourceSession = session.orElseThrow();
            Object frozenObjective = sourceSession.metadata().get("taskObjective");
            String taskObjective = frozenObjective instanceof String value && !value.isBlank()
                    ? value
                    : persistedRun.orElseThrow().objective();
            Object frozenTaskId = sourceSession.metadata().get("taskId");
            if (!(frozenTaskId instanceof String taskId) || taskId.isBlank()) {
                throw new MissionException(
                        "MISSION_TASK_METADATA_INVALID", "Mission Task Session is missing its frozen task ID");
            }
            NormalizedTaskResult normalized = normalizeResearchTaskResult(
                    runId, persistedRun.orElseThrow().sessionId(), taskId, taskObjective, result.orElseThrow());
            return new TaskRunObservation(
                    runId,
                    TaskRunState.COMPLETED,
                    Optional.of(normalized.result()),
                    Optional.empty(),
                    plus(primaryUsage, normalized.usage()));
        } catch (MissionException failure) {
            return new TaskRunObservation(
                    runId, TaskRunState.FAILED, Optional.empty(), Optional.of(failure.code()), primaryUsage);
        }
    }

    private NormalizedTaskResult normalizeResearchTaskResult(
            String sourceRunId, AgentSessionId sourceSessionId, String taskId, String taskObjective, String result) {
        requireStructuredOutput("Mission Task result normalization");
        String prompt = taskNormalizationPrompt(taskId, taskObjective, result, deepResearchSkill);
        MissionUsage normalizationUsage = MissionUsage.NONE;
        String lastFailure = "MISSION_TASK_NORMALIZATION_FAILED";
        for (int attemptNo = 1; attemptNo <= TASK_NORMALIZATION_MAX_ATTEMPTS; attemptNo++) {
            var started = agent.runs()
                    .start(new AgentRunRequest(
                            "mission-task-normalizer:" + sourceRunId + ":" + TASK_NORMALIZATION_PROTOCOL_VERSION
                                    + ":attempt:" + attemptNo,
                            new AgentDefinitionId("personal-assistant"),
                            Optional.of(new AgentDefinitionVersion(1, 0, 0)),
                            TASK_NORMALIZER_RUN_PROFILE,
                            sourceSessionId,
                            Optional.empty(),
                            prompt,
                            List.of(),
                            RuntimeOverrides.NONE));
            io.haifa.agent.runtime.api.AgentRunSnapshot terminal;
            try {
                terminal = agent.runs()
                        .await(started.runId(), Duration.ofSeconds(120))
                        .orElseThrow(() -> new MissionException(
                                "MISSION_TASK_NORMALIZATION_TIMEOUT", "Mission Task normalization timed out"));
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new MissionException(
                        "MISSION_TASK_NORMALIZATION_INTERRUPTED",
                        "Mission Task normalization was interrupted",
                        exception);
            }
            normalizationUsage = plus(normalizationUsage, usage(terminal.usage()));
            if (terminal.status() != AgentRunStatus.COMPLETED) {
                lastFailure = terminal.error()
                        .map(error -> error.code().wireCode())
                        .orElse(terminal.status().name());
                continue;
            }
            Optional<String> normalized = terminal.result()
                    .map(value -> value.summary())
                    .or(() -> terminal.output())
                    .filter(value -> !value.isBlank())
                    .map(value -> canonicalizeResearchTaskResult(value, taskId));
            if (normalized
                    .filter(value -> isResearchTaskResult(value, deepResearchSkill))
                    .isPresent()) {
                return new NormalizedTaskResult(normalized.orElseThrow(), normalizationUsage);
            }
            lastFailure = "MISSION_TASK_NORMALIZATION_SCHEMA_INVALID";
        }
        return new NormalizedTaskResult(
                conservativeResearchTaskResult(taskObjective, result, lastFailure), normalizationUsage);
    }

    static boolean isResearchTaskResult(String value, SkillContent deepResearchSkill) {
        try {
            JsonNode root = JSON.readTree(value);
            Map<String, Object> schemaDocument = JSON.readValue(
                    deepResearchSkill.resource("schemas/research-task-result-v1.json"), new TypeReference<>() {});
            Map<String, Object> instance = JSON.convertValue(root, new TypeReference<>() {});
            boolean schemaValid = new JsonSchema202012Validator()
                    .validate(new ToolSchema("pa.research-task-result", "v1", schemaDocument), instance)
                    .valid();
            return schemaValid && researchTaskSemanticsValid(root);
        } catch (Exception ignored) {
            return false;
        }
    }

    static String canonicalizeResearchTaskResult(String value) {
        return canonicalizeResearchTaskResult(value, "");
    }

    static String canonicalizeResearchTaskResult(String value, String taskId) {
        try {
            JsonNode parsed = JSON.readTree(value);
            if (!parsed.isObject()) return value;
            for (JsonNode source : parsed.path("sources")) {
                if (!source.isObject()) continue;
                canonicalizeInstant(source, "fetchedAt", false);
                canonicalizeInstant(source, "publishedAt", true);
            }
            if ("pa.research-task-result/v1".equals(parsed.path("schemaVersion").asText())) {
                canonicalizeResearchEvidence((ObjectNode) parsed, taskId);
            }
            return JSON.writeValueAsString(parsed);
        } catch (Exception ignored) {
            return value;
        }
    }

    private static void canonicalizeResearchEvidence(ObjectNode root, String taskId) {
        ArrayNode canonicalSources = JSON.createArrayNode();
        Map<String, String> sourceAliases = new LinkedHashMap<>();
        Map<String, Boolean> fetchedBySource = new LinkedHashMap<>();
        Map<String, String> sourceByLocator = new LinkedHashMap<>();
        for (JsonNode candidate : root.path("sources")) {
            if (!(candidate instanceof ObjectNode source)) continue;
            String originalSourceId = source.path("sourceId").asText();
            String locator = source.path("locator").asText();
            if (originalSourceId.isBlank() || locator.isBlank()) continue;
            String sourceId = namespacedStableId(taskId, originalSourceId);
            source.put("sourceId", sourceId);
            sourceAliases.put(originalSourceId, sourceId);
            try {
                var decision =
                        PUBLIC_WEB_URL_POLICY.evaluate(URI.create(locator).normalize());
                if (!decision.allowed()) continue;
                String normalized = decision.normalizedUrl().toASCIIString();
                String existing = sourceByLocator.putIfAbsent(normalized, sourceId);
                if (existing != null) {
                    sourceAliases.put(originalSourceId, existing);
                    sourceAliases.put(sourceId, existing);
                    continue;
                }
                source.put("locator", normalized);
                source.put("normalizedLocator", normalized);
                source.put("locatorDigest", digest(normalized));
                boolean fetched = "FETCHED".equals(source.path("status").asText())
                        && nullOrInstant(source.get("fetchedAt"))
                        && source.get("fetchedAt") != null
                        && !source.get("fetchedAt").isNull()
                        && source.path("contentDigest").isTextual()
                        && SHA256_DIGEST
                                .matcher(source.path("contentDigest").asText())
                                .matches()
                        && !placeholderDigest(source.path("contentDigest").asText())
                        && source.path("excerpt").isTextual()
                        && !source.path("excerpt").asText().isBlank();
                if (!fetched) {
                    if ("FETCHED".equals(source.path("status").asText())) source.put("status", "UNKNOWN");
                    source.putNull("fetchedAt");
                    source.putNull("contentDigest");
                    source.put("excerpt", "");
                }
                fetchedBySource.put(sourceId, fetched);
                canonicalSources.add(source);
            } catch (IllegalArgumentException ignored) {
                // Unsafe or malformed locators are excluded at the trusted normalization boundary.
            }
        }
        root.set("sources", canonicalSources);

        ArrayNode canonicalClaims = JSON.createArrayNode();
        LinkedHashSet<String> claimIds = new LinkedHashSet<>();
        for (JsonNode candidate : root.path("claims")) {
            if (!(candidate instanceof ObjectNode claim)) continue;
            String claimId = namespacedStableId(taskId, claim.path("claimId").asText());
            if (claimId.isBlank() || !claimIds.add(claimId)) continue;
            claim.put("claimId", claimId);
            if (claim.path("limitations").isArray()) {
                String limitations = java.util.stream.StreamSupport.stream(
                                claim.path("limitations").spliterator(), false)
                        .map(JsonNode::asText)
                        .filter(text -> !text.isBlank())
                        .collect(java.util.stream.Collectors.joining("; "));
                claim.put("limitations", limitations);
            }
            // Normalized Task results intentionally carry no verbatim quotations. Enforce the required empty
            // placeholder even when the model omits it, instead of rejecting otherwise usable evidence.
            claim.putArray("quotedSpans");
            LinkedHashSet<String> references = new LinkedHashSet<>();
            rewriteSourceReferences(claim, "supportingSourceIds", sourceAliases, fetchedBySource, references);
            rewriteSourceReferences(claim, "opposingSourceIds", sourceAliases, fetchedBySource, references);
            if (references.isEmpty()) continue;
            if (references.stream().anyMatch(sourceId -> !fetchedBySource.getOrDefault(sourceId, false))) {
                claim.put("unverified", true);
            }
            canonicalClaims.add(claim);
        }
        root.set("claims", canonicalClaims);
        if (root.path("limitsUsed") instanceof ObjectNode limits) {
            long fetched = fetchedBySource.values().stream()
                    .filter(Boolean::booleanValue)
                    .count();
            limits.put("sources", canonicalSources.size());
            limits.put("fetchCalls", Math.max(limits.path("fetchCalls").asInt(0), fetched));
        }
    }

    private static void rewriteSourceReferences(
            ObjectNode claim,
            String field,
            Map<String, String> aliases,
            Map<String, Boolean> fetchedBySource,
            LinkedHashSet<String> allReferences) {
        LinkedHashSet<String> values = new LinkedHashSet<>();
        claim.path(field).forEach(value -> {
            String canonical = aliases.getOrDefault(value.asText(), value.asText());
            if (fetchedBySource.containsKey(canonical)) values.add(canonical);
        });
        allReferences.addAll(values);
        claim.set(field, JSON.valueToTree(values));
    }

    private static String namespacedStableId(String taskId, String value) {
        if (value == null || value.isBlank()) return "";
        if (taskId == null || taskId.isBlank()) return value;
        String prefix = taskId + "--";
        if (value.startsWith(prefix)) return value;
        String candidate = prefix + value;
        if (candidate.length() <= 128) return candidate;
        String suffix = digest(candidate).substring("sha256:".length(), "sha256:".length() + 16);
        return candidate.substring(0, 128 - suffix.length() - 1) + "-" + suffix;
    }

    private static void canonicalizeInstant(JsonNode source, String field, boolean allowDateOnly) {
        JsonNode timestamp = source.get(field);
        if (timestamp == null || !timestamp.isTextual()) return;
        String value = timestamp.asText();
        try {
            ((ObjectNode) source).put(field, Instant.parse(value).toString());
            return;
        } catch (java.time.format.DateTimeParseException ignored) {
            // Publication evidence frequently preserves only a calendar date.
        }
        if (!allowDateOnly) return;
        try {
            ((ObjectNode) source)
                    .put(
                            field,
                            LocalDate.parse(value)
                                    .atStartOfDay(ZoneOffset.UTC)
                                    .toInstant()
                                    .toString());
        } catch (java.time.format.DateTimeParseException ignored) {
            // Leave invalid values unchanged so schema/semantic validation rejects them.
        }
    }

    private static boolean researchTaskSemanticsValid(JsonNode root) {
        if (containsToolProtocolMarkup(root)) return false;
        JsonNode sources = root.path("sources");
        JsonNode limits = root.path("limitsUsed");
        if (limits.path("sources").asInt(-1) != sources.size()) return false;
        long fetched = java.util.stream.StreamSupport.stream(sources.spliterator(), false)
                .filter(source -> "FETCHED".equals(source.path("status").asText()))
                .count();
        if (limits.path("fetchCalls").asInt(-1) < fetched) return false;
        LinkedHashSet<String> sourceIds = new LinkedHashSet<>();
        for (JsonNode source : sources) {
            if (!sourceIds.add(source.path("sourceId").asText())) return false;
            String locator = source.path("locator").asText();
            if (!publicHttpLocator(locator)) return false;
            if (!nullOrInstant(source.get("fetchedAt")) || !nullOrInstant(source.get("publishedAt"))) return false;
            if ("FETCHED".equals(source.path("status").asText())
                    && (source.path("fetchedAt").isNull()
                            || source.path("contentDigest").isNull()
                            || placeholderDigest(source.path("contentDigest").asText())
                            || source.path("excerpt").asText().isBlank())) return false;
        }
        LinkedHashSet<String> claimIds = new LinkedHashSet<>();
        for (JsonNode claim : root.path("claims")) {
            if (!claimIds.add(claim.path("claimId").asText())) return false;
            LinkedHashSet<String> references = new LinkedHashSet<>();
            claim.path("supportingSourceIds").forEach(value -> references.add(value.asText()));
            claim.path("opposingSourceIds").forEach(value -> references.add(value.asText()));
            if (references.isEmpty() || !sourceIds.containsAll(references)) return false;
        }
        return true;
    }

    private static boolean placeholderDigest(String value) {
        return ("sha256:" + "0".repeat(64)).equals(value);
    }

    private static boolean containsToolProtocolMarkup(JsonNode value) {
        if (value.isTextual()) {
            String text = value.asText().toLowerCase(java.util.Locale.ROOT);
            return text.contains("<｜｜dsml｜｜")
                    || text.contains("<|dsml|")
                    || text.contains("<tool_call")
                    || text.contains("</tool_call")
                    || text.contains("<function=");
        }
        if (value.isContainerNode()) {
            for (JsonNode child : value) {
                if (containsToolProtocolMarkup(child)) return true;
            }
        }
        return false;
    }

    private static boolean nullOrInstant(JsonNode value) {
        if (value == null || value.isNull()) return true;
        if (!value.isTextual()) return false;
        try {
            Instant.parse(value.asText());
            return true;
        } catch (java.time.format.DateTimeParseException ignored) {
            return false;
        }
    }

    private static boolean publicHttpLocator(String locator) {
        try {
            return PUBLIC_WEB_URL_POLICY.evaluate(URI.create(locator)).allowed();
        } catch (IllegalArgumentException ignored) {
            return false;
        }
    }

    public static String conservativeResearchTaskResult(String objective, String result, String normalizationFailure) {
        ObjectNode root = JSON.createObjectNode();
        root.put("schemaVersion", "pa.research-task-result/v1");
        String brief = containsToolProtocolMarkup(JSON.getNodeFactory().textNode(result))
                ? objective
                        + " Structured normalization discarded serialized Tool protocol markup; evidence requires review."
                : result;
        root.put("brief", brief.substring(0, Math.min(brief.length(), 8_000)));
        ArrayNode queries = root.putArray("queries");
        ObjectNode query = queries.addObject();
        query.put("query", objective.substring(0, Math.min(objective.length(), 2_048)));
        query.put("phase", "CROSS_CHECK");

        LinkedHashSet<String> locators = new LinkedHashSet<>();
        Matcher matcher = PUBLIC_URL.matcher(result);
        while (matcher.find() && locators.size() < 10) {
            String candidate = trimUrlPunctuation(matcher.group());
            try {
                URI uri = URI.create(candidate);
                if (publicHttpLocator(candidate)) {
                    locators.add(candidate);
                }
            } catch (IllegalArgumentException ignored) {
                // Ignore malformed locators recovered from free-form notes.
            }
        }
        ArrayNode sources = root.putArray("sources");
        for (String locator : locators) {
            ObjectNode source = sources.addObject();
            source.put("sourceId", "recovered-source-" + digest(locator).substring(7, 23));
            source.put("locator", locator);
            source.put("normalizedLocator", locator);
            source.put("locatorDigest", "sha256:" + "0".repeat(64));
            source.put("title", URI.create(locator).getHost());
            source.put("safetyType", "PUBLIC_WEB");
            source.putNull("fetchedAt");
            source.putNull("publishedAt");
            source.put("status", "UNKNOWN");
            source.put("excerpt", "");
            source.putNull("contentDigest");
        }
        root.putArray("claims");
        root.putArray("artifactRefs");
        root.putArray("unresolvedQuestions")
                .add("Structured normalization was unavailable (" + normalizationFailure
                        + "); recovered notes and source locators require verification.");
        root.put("stopReason", "TIME_LIMIT");
        ObjectNode limits = root.putObject("limitsUsed");
        limits.put("searchCalls", 0);
        limits.put("fetchCalls", 0);
        limits.put("sources", locators.size());
        limits.put("contentBytes", 0);
        try {
            return JSON.writeValueAsString(root);
        } catch (Exception failure) {
            throw new MissionException(
                    "MISSION_TASK_NORMALIZATION_FALLBACK_FAILED",
                    "Mission Task normalization fallback could not be encoded",
                    failure);
        }
    }

    private static String trimUrlPunctuation(String value) {
        int end = value.length();
        while (end > 0 && ".,;:!?，。；：！？".indexOf(value.charAt(end - 1)) >= 0) end--;
        return value.substring(0, end);
    }

    static String taskNormalizationPrompt(
            String taskId, String taskObjective, String result, SkillContent deepResearchSkill) {
        Objects.requireNonNull(deepResearchSkill);
        return """
                Convert the completed research notes below into exactly one JSON object matching
                pa.research-task-result/v1. Produce compact JSON only, without Markdown fences or commentary. Select
                at most 6 strongest sources and 10 decision-relevant claims. Keep brief under 400 characters, each
                title under 140 characters, each claim under 300 characters, each limitation under 240 characters,
                and unresolvedQuestions to at most 10 items. Set every claim.quotedSpans to an empty JSON array and
                do not copy quoted text or long excerpts.

                Required exact top-level fields are schemaVersion, brief, queries, sources, claims, artifactRefs,
                unresolvedQuestions, stopReason, and limitsUsed. Each query has only query and phase. Each source has
                exactly sourceId, locator, normalizedLocator, locatorDigest, title, safetyType, fetchedAt, publishedAt,
                status, excerpt, and contentDigest. Each claim has exactly claimId, claim, supportingSourceIds,
                opposingSourceIds, limitations, unverified, and quotedSpans. artifactRefs must be empty. limitsUsed has
                exactly searchCalls, fetchCalls, sources, and contentBytes. Use lower-case kebab-case stable IDs.
                To make evidence identity Mission-wide, prefix every sourceId and claimId with `%s--`; references in
                supportingSourceIds and opposingSourceIds must use the same prefixed source IDs.

                Use only these exact enum values:
                - query.phase: DISCOVER, DEEPEN, or CROSS_CHECK;
                - source.safetyType: PUBLIC_WEB, or DEVELOPMENT_STUB only for an explicit local fixture;
                - source.status: FETCHED, INACCESSIBLE, STALE, UNKNOWN, CONFLICT, UNDATED, or UNSAFE;
                - stopReason: SUFFICIENT_EVIDENCE, SOURCE_LIMIT, CONTENT_LIMIT, TIME_LIMIT, TOOL_LIMIT,
                  NO_MORE_SAFE_SOURCES, or CANCELLED.
                artifactRefs and every claim.quotedSpans must be JSON arrays, never objects.

                Preserve only evidence present in the notes. Do not invent a source, locator, quote, date, or claim.
                Encode fetchedAt and publishedAt as UTC ISO-8601 instants such as 2026-08-10T00:00:00Z. When a source
                provides only a publication date, use UTC midnight for that same date; never infer a different date.
                Summarize the explicit research question in queries; when exact Tool counts are absent, use zero rather
                than fabricating counts. Source identity is finalized by the trusted Server: copy locator into
                normalizedLocator and use sha256:%s as the locatorDigest placeholder. Unless the notes explicitly
                contain a fetched timestamp and valid content digest, set status to UNKNOWN, fetchedAt and
                contentDigest to null, excerpt to an empty string, and every dependent claim to unverified. Mark all
                other insufficiently supported claims as unverified and list unresolved gaps.

                A successful search result with a public HTTP(S) locator is usable discovery evidence even when a
                later fetch failed. Keep up to the strongest such locators as UNKNOWN sources; do not discard every
                source merely because full-page fetching was unavailable. Do not create a factual claim unless its
                source references close within this result. When the Task selects or freezes a concrete case, company,
                product, event, or other subject, the brief must explicitly name the selected subject if earlier Tool
                results identify one. If no subject can be selected, say that explicitly instead of implying success.

                The frozen Task objective below is authoritative. This normalization Run continues the same source
                Task Session, so use relevant earlier successful Tool results already present in that Session. If the
                final notes contain serialized DSML/XML/function Tool-call markup, ignore the markup itself and recover
                evidence only from the earlier Tool results. Never substitute another company, product, event, region,
                or time period. If the Session contains no usable evidence for the frozen objective, return no sources
                and no claims, keep the brief about the frozen objective, and state the evidence gap explicitly.

                Frozen Task objective:
                %s

                Completed research notes:
                %s
                """
                .formatted(taskId, "0".repeat(64), taskObjective, result);
    }

    @Override
    public void cancelTask(String runId) {
        var snapshot = agent.runs().find(new io.haifa.agent.core.run.AgentRunId(runId));
        if (snapshot.isPresent() && !snapshot.orElseThrow().status().isTerminal()) {
            agent.runs().handle(new io.haifa.agent.core.run.AgentRunId(runId)).cancel();
        }
    }

    @Override
    public SynthesisRunResult runSynthesis(MissionSynthesisIntent intent) {
        return runSynthesisAttempt(intent, null, null, 0);
    }

    @Override
    public SynthesisRunResult reviseSynthesis(
            MissionSynthesisIntent intent,
            SynthesisRunResult previous,
            ReportQualityGate.Result quality,
            int revisionAttempt) {
        if (intent.mode() != MissionMode.DEEP_RESEARCH || quality.passed()) {
            throw new MissionException("MISSION_SYNTHESIS_REVISION_INVALID", "Synthesis revision request is invalid");
        }
        return runSynthesisAttempt(intent, previous, quality, revisionAttempt);
    }

    private SynthesisRunResult runSynthesisAttempt(
            MissionSynthesisIntent intent,
            SynthesisRunResult previous,
            ReportQualityGate.Result quality,
            int revisionAttempt) {
        if (intent.mode() != MissionMode.DEEP_RESEARCH) requireStructuredOutput("Mission Synthesis");
        String stable = digest(intent.missionId(), "synthesis", SYNTHESIS_PROTOCOL_VERSION);
        AgentSessionId sessionId = new AgentSessionId("mission-synthesis-" + stable.substring("sha256:".length(), 38));
        persistence.inTransaction(() -> {
            if (persistence.runtimePersistence().sessions().find(sessionId).isEmpty()) {
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
                                        "productId", "haifa-personal-assistant",
                                        "sessionKind", "MISSION_SYNTHESIS",
                                        "missionId", intent.missionId())));
            }
            return null;
        });
        String dispatchKey = synthesisDispatchKey(intent.missionId(), revisionAttempt);
        String prompt = intent.mode() == MissionMode.DEEP_RESEARCH
                ? researchSynthesisPrompt(intent, previous, quality, revisionAttempt)
                : standardSynthesisPrompt(intent);
        String profile =
                intent.mode() == MissionMode.DEEP_RESEARCH ? RESEARCH_SYNTHESIS_RUN_PROFILE : SYNTHESIS_RUN_PROFILE;
        var started = agent.runs()
                .start(new AgentRunRequest(
                        dispatchKey,
                        new AgentDefinitionId("personal-assistant"),
                        Optional.of(new AgentDefinitionVersion(1, 0, 0)),
                        profile,
                        sessionId,
                        Optional.empty(),
                        prompt,
                        List.of(),
                        RuntimeOverrides.NONE));
        try {
            var terminal = agent.runs()
                    .await(started.runId(), Duration.ofSeconds(120))
                    .orElseThrow(
                            () -> new MissionException("MISSION_SYNTHESIS_TIMEOUT", "Mission Synthesis timed out"));
            if (terminal.status() != AgentRunStatus.COMPLETED) {
                String failure = terminal.error()
                        .map(error -> error.code().wireCode())
                        .orElse(terminal.status().name());
                if (intent.mode() == MissionMode.DEEP_RESEARCH) {
                    return new SynthesisRunResult(
                            sessionId.value(),
                            terminal.runId().value(),
                            conservativeResearchReport(intent, failure),
                            usage(terminal.usage()),
                            List.of("MISSION_SYNTHESIS_FALLBACK:" + failure));
                }
                throw new MissionException("MISSION_SYNTHESIS_FAILED", failure);
            }
            String output = terminal.result()
                    .map(result -> result.summary())
                    .or(() -> terminal.output())
                    .filter(value -> !value.isBlank())
                    .orElse("");
            if (output.isBlank() && intent.mode() == MissionMode.DEEP_RESEARCH) {
                return new SynthesisRunResult(
                        sessionId.value(),
                        terminal.runId().value(),
                        conservativeResearchReport(intent, "MISSION_SYNTHESIS_EMPTY"),
                        usage(terminal.usage()),
                        List.of("MISSION_SYNTHESIS_EMPTY"));
            }
            if (output.isBlank()) {
                throw new MissionException("MISSION_SYNTHESIS_SCHEMA_INVALID", "Mission Synthesis returned no result");
            }
            String normalized =
                    intent.mode() == MissionMode.DEEP_RESEARCH ? canonicalizeResearchReport(output) : output;
            return new SynthesisRunResult(
                    sessionId.value(), terminal.runId().value(), normalized, usage(terminal.usage()));
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new MissionException("MISSION_SYNTHESIS_INTERRUPTED", "Mission Synthesis was interrupted", exception);
        }
    }

    private String standardSynthesisPrompt(MissionSynthesisIntent intent) {
        return """
                [mission-synthesis]
                Produce only one pa.mission-final-result/v1 JSON object. Do not invent Artifact references.
                Mission mode: %s
                Mission objective: %s
                Authoritative settled Task results: %s
                Failed or cancelled Task items: %s
                """
                .formatted(intent.mode(), intent.objective(), intent.taskResults(), intent.failedItems());
    }

    private String researchSynthesisPrompt(
            MissionSynthesisIntent intent,
            SynthesisRunResult previous,
            ReportQualityGate.Result quality,
            int revisionAttempt) {
        String revision = revisionAttempt == 0
                ? "This is the initial candidate."
                : "This is bounded revision " + revisionAttempt + " of 2. Fix only these deterministic failures: "
                        + quality.revisionFeedback() + "\nPrevious candidate:\n" + previous.structuredOutput();
        return """
                [mission-research-synthesis]
                Return only the complete Markdown report, never JSON and never a fenced Markdown block.
                Preserve evidence boundaries: do not invent Task IDs, Source IDs, facts, Artifact references, or URLs.
                Copy every real Task ID exactly into a <!-- haifa-task: task-id --> marker and cite settled sources only
                with [[source-id]]. Use the required stable section markers from the template. A critical external claim
                without sufficient fetched support must be explicitly labeled unverified.

                Mission objective: %s
                Real completed Task IDs in result order: %s
                Failed or cancelled Task items: %s
                Authoritative settled Task result summaries and evidence: %s
                Report template: %s
                Deterministic report quality contract: %s
                Revision instruction: %s
                """
                .formatted(
                        intent.objective(),
                        intent.completedTaskIds(),
                        intent.failedItems(),
                        intent.taskResults(),
                        deepResearchSkill.resource("templates/report.md"),
                        deepResearchSkill.resource("references/report-quality.md"),
                        revision);
    }

    static String canonicalizeResearchReport(String value) {
        String report = value.strip();
        if (report.startsWith("```markdown") && report.endsWith("```")) {
            report = report.substring("```markdown".length(), report.length() - 3)
                    .strip();
        } else if (report.startsWith("```md") && report.endsWith("```")) {
            report = report.substring("```md".length(), report.length() - 3).strip();
        }
        return report;
    }

    public static String conservativeResearchReport(MissionSynthesisIntent intent, String reason) {
        StringBuilder findings = new StringBuilder();
        for (int index = 0; index < intent.taskResults().size(); index++) {
            String taskId = intent.completedTaskIds().get(index);
            String brief = "Settled research evidence was preserved for this task.";
            try {
                String candidate = JSON.readTree(intent.taskResults().get(index))
                        .path("brief")
                        .asText();
                if (!candidate.isBlank()) brief = candidate;
            } catch (Exception ignored) {
                // The deterministic publisher will retain the precise schema failure.
            }
            findings.append("\n<!-- haifa-task: ")
                    .append(taskId)
                    .append(" -->\n### ")
                    .append(taskId)
                    .append("\n\n")
                    .append(brief)
                    .append('\n');
        }
        return """
                # Research report

                <!-- haifa-section: executive-summary -->
                ## Executive summary

                The research tasks finished, but final synthesis was degraded. The evidence-preserving findings below
                remain available and must not be interpreted as a fully quality-approved conclusion.

                <!-- haifa-section: scope-method -->
                ## Scope, assumptions, and method

                The report is limited to the confirmed Mission objective and settled Task evidence. No missing fact was
                inferred or fabricated during deterministic fallback.

                <!-- haifa-section: task-findings -->
                ## Task findings
                %s

                <!-- haifa-section: synthesis -->
                ## Synthesis

                A reliable cross-task synthesis could not be completed. Review the individual findings and sources.

                <!-- haifa-section: conclusions -->
                ## Conclusions and recommendations

                No complete conclusion is claimed. Recreate the Mission if a new research run is required.

                <!-- haifa-section: risks-unknowns -->
                ## Risks, unknowns, and open questions

                Synthesis degradation reason: %s. Failed items: %s.

                <!-- haifa-section: sources -->
                ## Sources

                Source metadata remains available in the accompanying sources artifact.
                """
                .formatted(findings, reason, intent.failedItems());
    }

    public static String synthesisDispatchKey(String missionId) {
        return synthesisDispatchKey(missionId, 0);
    }

    public static String synthesisDispatchKey(String missionId, int revisionAttempt) {
        if (revisionAttempt < 0 || revisionAttempt > 2) {
            throw new IllegalArgumentException("revisionAttempt must be between 0 and 2");
        }
        String suffix = revisionAttempt == 0 ? "" : ":revision-" + revisionAttempt;
        return "mission:" + Objects.requireNonNull(missionId) + ":synthesis:" + SYNTHESIS_PROTOCOL_VERSION + suffix;
    }

    static String canonicalizeResearchSynthesis(String value) {
        try {
            JsonNode parsed = JSON.readTree(value);
            if (!(parsed instanceof ObjectNode root)
                    || !"pa.research-final-result/v1"
                            .equals(root.path("schemaVersion").asText())) {
                return value;
            }
            for (String field : List.of(
                    "reportArtifactRef",
                    "sourcesArtifactRef",
                    "claimEvidenceArtifactRef",
                    "resultArtifactRef",
                    "unresolvedArtifactRef")) {
                root.putNull(field);
            }
            ArrayNode artifactRefs = JSON.createArrayNode();
            root.path("artifactRefs").forEach(reference -> {
                if (reference.isObject()) artifactRefs.add(reference);
            });
            root.set("artifactRefs", artifactRefs);
            canonicalizeSynthesisDirectAnswer(root);
            for (String field : List.of(
                    "completedItems",
                    "failedItems",
                    "sourceRefs",
                    "unverifiedClaims",
                    "unresolvedQuestions",
                    "residualRisks")) {
                canonicalizeSynthesisTextItems(root, field);
            }
            root.retain(List.of(
                    "schemaVersion",
                    "reportArtifactRef",
                    "sourcesArtifactRef",
                    "claimEvidenceArtifactRef",
                    "resultArtifactRef",
                    "unresolvedArtifactRef",
                    "directAnswer",
                    "completedItems",
                    "failedItems",
                    "artifactRefs",
                    "sourceRefs",
                    "unverifiedClaims",
                    "unresolvedQuestions",
                    "residualRisks",
                    "completionKind"));
            return JSON.writeValueAsString(root);
        } catch (Exception ignored) {
            return value;
        }
    }

    private static void canonicalizeSynthesisDirectAnswer(ObjectNode root) {
        JsonNode answer = root.get("directAnswer");
        if (answer == null || answer.isTextual()) return;
        LinkedHashSet<String> parts = new LinkedHashSet<>();
        collectSynthesisAnswerParts(answer, parts);
        String normalized = String.join("\n\n", parts);
        if (!normalized.isBlank()) {
            root.put("directAnswer", normalized.substring(0, Math.min(normalized.length(), 24_000)));
        }
    }

    private static void collectSynthesisAnswerParts(JsonNode value, LinkedHashSet<String> parts) {
        if (value.isTextual()) {
            String text = value.asText().trim();
            if (!text.isBlank()) parts.add(text);
            return;
        }
        if (value.isContainerNode()) value.forEach(child -> collectSynthesisAnswerParts(child, parts));
    }

    private static void canonicalizeSynthesisTextItems(ObjectNode root, String field) {
        JsonNode values = root.get(field);
        if (values == null || !values.isArray()) return;
        ArrayNode normalized = JSON.createArrayNode();
        LinkedHashSet<String> unique = new LinkedHashSet<>();
        values.forEach(value -> {
            String text = synthesisTextItem(value);
            if (!text.isBlank() && unique.add(text)) normalized.add(text);
        });
        root.set(field, normalized);
    }

    private static String synthesisTextItem(JsonNode value) {
        if (value.isTextual()) return value.asText().trim();
        if (!value.isObject()) return "";
        String taskId = value.path("taskId").asText().trim();
        String detail = "";
        for (String field : List.of("result", "reason", "failure", "brief", "message", "status")) {
            JsonNode candidate = value.get(field);
            if (candidate != null
                    && candidate.isTextual()
                    && !candidate.asText().isBlank()) {
                detail = candidate.asText().trim();
                break;
            }
        }
        String result = taskId.isBlank() ? detail : detail.isBlank() ? taskId : taskId + ": " + detail;
        return result.length() <= 4_096 ? result : result.substring(0, 4_096);
    }

    public static String conservativeResearchSynthesis(MissionSynthesisIntent intent, String synthesisFailure) {
        return conservativeResearchSynthesis(intent, synthesisFailure, "");
    }

    public static String conservativeResearchSynthesis(
            MissionSynthesisIntent intent, String synthesisFailure, String preferredSynthesis) {
        ObjectNode root = JSON.createObjectNode();
        root.put("schemaVersion", "pa.research-final-result/v1");
        root.putNull("reportArtifactRef");
        root.putNull("sourcesArtifactRef");
        root.putNull("claimEvidenceArtifactRef");
        root.putNull("resultArtifactRef");
        root.putNull("unresolvedArtifactRef");

        StringBuilder answer = new StringBuilder();
        ArrayNode completedItems = root.putArray("completedItems");
        LinkedHashSet<String> sourceRefs = new LinkedHashSet<>();
        LinkedHashSet<String> unverifiedClaims = new LinkedHashSet<>();
        LinkedHashSet<String> unresolvedQuestions = new LinkedHashSet<>();
        int itemNo = 0;
        for (String encoded : intent.taskResults()) {
            itemNo++;
            try {
                JsonNode task = JSON.readTree(encoded);
                String brief = task.path("brief").asText();
                if (!brief.isBlank()) {
                    if (!answer.isEmpty()) answer.append("\n\n");
                    answer.append("### Research item ")
                            .append(itemNo)
                            .append("\n\n")
                            .append(brief);
                    completedItems.add("Research item " + itemNo + " completed");
                }
                task.path("sources")
                        .forEach(
                                source -> sourceRefs.add(source.path("sourceId").asText()));
                task.path("claims").forEach(claim -> {
                    if (claim.path("unverified").asBoolean()) {
                        unverifiedClaims.add(claim.path("claimId").asText());
                    }
                });
                task.path("unresolvedQuestions").forEach(value -> unresolvedQuestions.add(value.asText()));
            } catch (Exception ignored) {
                unresolvedQuestions.add("A settled research item could not be decoded during fallback synthesis.");
            }
        }
        String preferredAnswer = preferredResearchAnswer(preferredSynthesis);
        if (answer.isEmpty()) answer.append(intent.objective());
        String trustedAnswer = preferredAnswer.isBlank() ? answer.toString() : preferredAnswer;
        root.put("directAnswer", trustedAnswer.substring(0, Math.min(trustedAnswer.length(), 24_000)));
        ArrayNode failedItems = JSON.valueToTree(intent.failedItems());
        root.set("failedItems", failedItems);
        root.putArray("artifactRefs");
        ArrayNode finalSourceRefs =
                JSON.valueToTree(sourceRefs.stream().limit(24).toList());
        root.set("sourceRefs", finalSourceRefs);
        ArrayNode finalUnverifiedClaims = JSON.valueToTree(
                unverifiedClaims.stream().limit(SYNTHESIS_MAX_UNVERIFIED_CLAIMS).toList());
        root.set("unverifiedClaims", finalUnverifiedClaims);
        ArrayNode finalUnresolvedQuestions =
                JSON.valueToTree(unresolvedQuestions.stream().limit(20).toList());
        root.set("unresolvedQuestions", finalUnresolvedQuestions);
        root.putArray("residualRisks")
                .add("Model synthesis was unavailable (" + synthesisFailure
                        + "); a deterministic evidence-preserving fallback was used.");
        root.put("completionKind", intent.failedItems().isEmpty() ? "COMPLETE" : "PARTIAL");
        try {
            return JSON.writeValueAsString(root);
        } catch (Exception failure) {
            throw new MissionException(
                    "MISSION_SYNTHESIS_FALLBACK_FAILED", "Mission Synthesis fallback could not be encoded", failure);
        }
    }

    private static String preferredResearchAnswer(String synthesis) {
        if (synthesis == null || synthesis.isBlank()) return "";
        try {
            JsonNode answer = JSON.readTree(synthesis).path("directAnswer");
            if (answer.isTextual() && !answer.asText().isBlank()) return answer.asText();
        } catch (Exception ignored) {
            // A non-JSON synthesis may still be a useful integrated answer.
        }
        String stripped = synthesis.strip();
        return stripped.startsWith("{") || stripped.startsWith("[") ? "" : stripped;
    }

    @Override
    public void appendFinalMessage(
            String conversationId, String missionId, String synthesisRunId, String finalMessage) {
        String stable = digest(missionId, "final-message", "v1");
        AgentMessageId messageId = new AgentMessageId("mission-final-" + stable.substring("sha256:".length(), 38));
        persistence.inTransaction(() -> {
            if (persistence.runtimePersistence().state().message(messageId).isEmpty()) {
                persistence
                        .runtimePersistence()
                        .state()
                        .appendSessionMessage(new SessionMessageDraft(
                                messageId,
                                new AgentSessionId(conversationId),
                                Optional.of(new io.haifa.agent.core.run.AgentRunId(synthesisRunId)),
                                Optional.empty(),
                                MessageRole.ASSISTANT,
                                MessageStatus.COMPLETED,
                                MessageVisibility.USER_VISIBLE,
                                List.of(new TextPart(finalMessage, "markdown")),
                                Map.of(
                                        "missionId",
                                        missionId,
                                        "messageKey",
                                        "mission:" + missionId + ":final-message:v1"),
                                time.now()));
            }
            return null;
        });
    }

    private PersonalModelOption requireStructuredOutput(String operation) {
        PersonalModelOption model = models.available().stream()
                .filter(value -> value.id().equals(modelId))
                .findFirst()
                .orElseThrow(() -> new MissionException(
                        "MODEL_STRUCTURED_OUTPUT_UNAVAILABLE", operation + " model is unavailable"));
        if (!model.capabilities().contains("STRUCTURED_OUTPUT")) {
            throw new MissionException(
                    "MODEL_STRUCTURED_OUTPUT_UNAVAILABLE", operation + " requires structured output");
        }
        return model;
    }

    private static MissionUsage usage(io.haifa.agent.core.run.AgentRunUsage value) {
        return new MissionUsage(
                Math.addExact(value.inputTokens(), value.outputTokens()), value.modelCalls(), value.toolCalls());
    }

    private static String digest(String... fields) {
        try {
            java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
            for (String field : fields) {
                byte[] bytes = Objects.requireNonNullElse(field, "").getBytes(java.nio.charset.StandardCharsets.UTF_8);
                digest.update(java.nio.ByteBuffer.allocate(Integer.BYTES)
                        .putInt(bytes.length)
                        .array());
                digest.update(bytes);
            }
            return "sha256:" + java.util.HexFormat.of().formatHex(digest.digest());
        } catch (java.security.NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is required", exception);
        }
    }

    private static MissionUsage plus(MissionUsage left, MissionUsage right) {
        return new MissionUsage(
                Math.addExact(left.modelTokens(), right.modelTokens()),
                Math.addExact(left.modelCalls(), right.modelCalls()),
                Math.addExact(left.toolCalls(), right.toolCalls()));
    }

    private record NormalizedTaskResult(String result, MissionUsage usage) {}

    static String plannerPrompt(MissionPlanner.PlanningRequest request) {
        if (request.mode() == MissionMode.DEEP_RESEARCH) {
            return """
                    Produce only one JSON object matching schema pa.mission-plan/v1. Do not use Markdown fences.
                    Required shape: {"schemaVersion":"pa.mission-plan/v1","tasks":[{"taskId":"task-1","ordinal":1,
                    "title":"...","objective":"...","acceptanceCriteria":["..."],"dependsOn":[],
                    "taskType":"RESEARCH","requiredSkillIds":["deep-research"],
                    "resultSchema":{"id":"pa.research-task-result","version":"v1"}}]}.
                    Use at most %d tasks and dependency depth %d. Task IDs must be lower-case kebab-case, ordinals contiguous,
                    and every dependency must exactly equal the taskId of an earlier object in this same tasks array. Never
                    use ordinal placeholders such as task-1 or task-2 unless those are the actual taskId values. Only
                    RESEARCH tasks using deep-research are allowed.
                    Dependency depth counts task nodes, not edges: a root Task has depth 1, its direct dependent has
                    depth 2, and a Task depending on that direct dependent has depth 3. Keep every Task at or below the
                    requested maximum depth.
                    Use the smallest feasible DAG that covers every acceptance criterion, normally 3 to %d Tasks for
                    this request and never more than the stated maximum. Group
                    closely related work using the concrete entities and actions in this Mission; do not copy generic
                    research categories such as landscape, indicators, ownership, or geographic distribution unless the
                    Mission itself explicitly asks for them. Every Task title, objective, and acceptance criterion must
                    name the specific domain subject it covers. Before responding, verify that each Mission acceptance
                    criterion is assigned to at least one Task and rewrite any generic or off-topic Task.
                    Evidence verification, source-quality assessment, and conflict marking belong inside every research
                    Task. Never create a separate cross-Task evidence-checking, verification, or consolidation Task.
                    Research dimensions are parallel work, not workflow stages. If the first Task selects or freezes the
                    subject, every later Task may depend on that first Task only; later Tasks must not depend on each
                    other. If no selection is required, keep all Tasks as roots with empty dependsOn arrays.
                    Do not create a final integration, synthesis, report-writing, or delivery Task. The Mission Synthesis
                    stage assembles the authoritative Task results after every Task settles.
                    Mission objective: %s
                    Acceptance criteria: %s
                    Frozen research brief: %s
                    """
                    .formatted(
                            request.constraints().maxTasks(),
                            request.constraints().maxDependencyDepth(),
                            Math.min(5, request.constraints().maxTasks()),
                            request.objective(),
                            request.acceptanceCriteria(),
                            request.researchBrief().orElseThrow());
        }
        return """
                Produce only one JSON object matching schema pa.mission-plan/v1. Do not use Markdown fences.
                Required shape: {"schemaVersion":"pa.mission-plan/v1","tasks":[{"taskId":"task-1","ordinal":1,
                "title":"...","objective":"...","acceptanceCriteria":["..."],"dependsOn":[],
                "taskType":"GENERAL","requiredSkillIds":[],"resultSchema":{"id":"pa.task-result","version":"v1"}}]}.
                Use at most %d tasks and dependency depth %d. Task IDs must be lower-case kebab-case, ordinals contiguous,
                and every dependency must exactly equal the taskId of an earlier object in this same tasks array. Never
                use ordinal placeholders such as task-1 or task-2 unless those are the actual taskId values. Only GENERAL
                tasks, no Skills.
                Dependency depth counts task nodes, not edges: a root Task has depth 1, its direct dependent has depth 2,
                and a Task depending on that direct dependent has depth 3. Keep every Task at or below the requested
                maximum depth.
                Use the smallest feasible DAG that covers the acceptance criteria and combine closely related work.
                Do not create a final integration, synthesis, report-writing, or delivery Task. The Mission Synthesis
                stage assembles the authoritative Task results after every Task settles.
                Mission objective: %s
                Acceptance criteria: %s
                """
                .formatted(
                        request.constraints().maxTasks(),
                        request.constraints().maxDependencyDepth(),
                        request.objective(),
                        request.acceptanceCriteria());
    }

    static String plannerRepairPrompt(
            MissionPlanner.PlanningRequest request,
            String invalidOutput,
            String violationCode,
            String violationMessage) {
        String boundedOutput = Objects.requireNonNull(invalidOutput);
        if (boundedOutput.length() > PLANNER_REPAIR_INPUT_MAX_CHARACTERS) {
            boundedOutput = boundedOutput.substring(0, PLANNER_REPAIR_INPUT_MAX_CHARACTERS);
        }
        String dependencyRepair = "";
        if ("MISSION_LIMIT_EXCEEDED".equals(violationCode)
                && violationMessage.toLowerCase(java.util.Locale.ROOT).contains("dependency depth")) {
            dependencyRepair =
                    """

                    MANDATORY DEPTH REPAIR: the rejected plan is too serial. Returning the same dependency arrays is
                    invalid. Keep the first Task as a root with dependsOn []. For every later Task, use either [] or a
                    single dependency on the first Task's exact taskId. Do not let later Tasks depend on each other.
                    Preserve their research coverage; Mission Synthesis, not a Task chain, performs integration.
                    """;
        }
        String taskCountRepair = "";
        if ("MISSION_LIMIT_EXCEEDED".equals(violationCode)
                && violationMessage.toLowerCase(java.util.Locale.ROOT).contains("task count")) {
            taskCountRepair =
                    """

                    MANDATORY TASK-COUNT REPAIR: return no more than %d Tasks. Merge closely related research
                    dimensions until the limit is met, while retaining every Mission acceptance criterion inside at
                    least one remaining Task. Remove any separate verification, consolidation, or synthesis Task, then
                    renumber ordinals contiguously. Returning the same number of Tasks is invalid.
                    """
                            .formatted(request.constraints().maxTasks());
        }
        return """
                Repair the invalid Mission Plan below. Return exactly one JSON object and nothing else: no Markdown
                fence, explanation, prefix, suffix, XML tag, or second JSON value. Preserve the intended task coverage,
                but correct syntax and schema violations. The result must match pa.mission-plan/v1 exactly.

                Required shape: {"schemaVersion":"pa.mission-plan/v1","tasks":[{"taskId":"specific-kebab-id",
                "ordinal":1,"title":"...","objective":"...","acceptanceCriteria":["..."],"dependsOn":[],
                "taskType":"%s","requiredSkillIds":%s,
                "resultSchema":{"id":"%s","version":"v1"}}]}.
                Maximum tasks: %d. Maximum dependency depth: %d. Dependencies must reference earlier taskId values.
                Dependency depth counts task nodes, not edges: root depth is 1, a direct dependent is depth 2, and a
                dependent of that Task is depth 3. Flatten dependencies as needed to stay within the maximum.
                %s%s
                Mission objective: %s
                Mission acceptance criteria: %s
                Rejected by deterministic validation: %s - %s

                Invalid Planner output to repair (bounded to %d characters):
                %s
                """
                .formatted(
                        request.mode() == MissionMode.DEEP_RESEARCH ? "RESEARCH" : "GENERAL",
                        request.mode() == MissionMode.DEEP_RESEARCH ? "[\"deep-research\"]" : "[]",
                        request.mode() == MissionMode.DEEP_RESEARCH ? "pa.research-task-result" : "pa.task-result",
                        request.constraints().maxTasks(),
                        request.constraints().maxDependencyDepth(),
                        dependencyRepair,
                        taskCountRepair,
                        request.objective(),
                        request.acceptanceCriteria(),
                        violationCode,
                        violationMessage,
                        PLANNER_REPAIR_INPUT_MAX_CHARACTERS,
                        boundedOutput);
    }
}
