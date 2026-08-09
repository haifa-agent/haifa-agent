package io.haifa.agent.personalassistant.application.mission;

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
import io.haifa.agent.runtime.api.AgentRunRequest;
import io.haifa.agent.runtime.api.RuntimeOverrides;
import io.haifa.agent.runtime.core.storage.SessionMessageDraft;
import io.haifa.agent.sdk.api.HaifaAgent;
import io.haifa.agent.sdk.spi.SdkPersistenceContribution;
import io.haifa.agent.skill.api.SkillContent;
import io.haifa.agent.tool.api.ToolSchema;
import io.haifa.agent.tool.core.JsonSchema202012Validator;
import java.net.URI;
import java.time.Duration;
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
    public static final String TASK_RUN_PROFILE = "personal-mission-task";
    public static final String TASK_NORMALIZER_RUN_PROFILE = "personal-mission-task-normalizer";
    private static final int TASK_NORMALIZATION_MAX_ATTEMPTS = 3;
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Pattern PUBLIC_URL = Pattern.compile("https?://[^\\s<>\\\"'\\]\\[)]+");
    public static final String SYNTHESIS_RUN_PROFILE = "personal-mission-synthesis";
    public static final long TASK_MAX_TOOL_CALLS = 32;
    public static final long TASK_RESEARCH_TOOL_CALL_TARGET = 20;
    public static final int TASK_FETCH_MAX_CHARACTERS = 20_000;

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
    public TaskRunBinding startTask(MissionDispatchIntent intent) {
        requireStructuredOutput("Mission Task");
        String stable = MissionValues.digest(intent.missionId(), intent.taskId(), Integer.toString(intent.attemptNo()));
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
                                        "resultSchemaId", intent.resultSchemaId(),
                                        "resultSchemaVersion", intent.resultSchemaVersion())));
            } else {
                AgentSession value = existing.orElseThrow();
                if (!value.tenant().equals(tenant)
                        || !value.owner().equals(principal)
                        || !"MISSION_TASK".equals(value.metadata().get("sessionKind"))
                        || !intent.missionId().equals(value.metadata().get("missionId"))
                        || !intent.taskId().equals(value.metadata().get("taskId"))) {
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
                        TASK_RUN_PROFILE,
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
        return """
                Complete this Mission task and return a concise result that satisfies every acceptance criterion.
                Task type: %s
                Required Skills: %s
                Task objective: %s
                Acceptance criteria: %s
                Required result schema: %s@%s

                This Run allows at most %d research Tool calls. HARD STOP: after %d total Tool calls, do not call any
                Tool again; immediately return the best bounded final result from evidence already collected. Every
                web_fetch call must set maxCharacters to at most %d. Prefer several focused excerpts over a full page.
                When you have enough evidence, stop calling Tools even earlier. Always leave enough context and time
                for a final model turn that returns the required JSON result; incomplete but explicit coverage is
                better than losing the entire result by over-researching.

                %s
                """
                .formatted(
                        intent.taskType(),
                        intent.requiredSkillIds(),
                        intent.objective(),
                        intent.acceptanceCriteria(),
                        intent.resultSchemaId(),
                        intent.resultSchemaVersion(),
                        TASK_MAX_TOOL_CALLS,
                        TASK_RESEARCH_TOOL_CALL_TARGET,
                        TASK_FETCH_MAX_CHARACTERS,
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
            NormalizedTaskResult normalized = normalizeResearchTaskResult(
                    runId, persistedRun.orElseThrow().objective(), result.orElseThrow());
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

    private NormalizedTaskResult normalizeResearchTaskResult(String sourceRunId, String objective, String result) {
        requireStructuredOutput("Mission Task result normalization");
        String prompt = taskNormalizationPrompt(result, deepResearchSkill);
        MissionUsage normalizationUsage = MissionUsage.NONE;
        String lastFailure = "MISSION_TASK_NORMALIZATION_FAILED";
        for (int attemptNo = 1; attemptNo <= TASK_NORMALIZATION_MAX_ATTEMPTS; attemptNo++) {
            String stable = MissionValues.digest(
                    sourceRunId, "research-result-normalization", "v1", Integer.toString(attemptNo));
            AgentSessionId sessionId =
                    new AgentSessionId("mission-task-normalizer-" + stable.substring("sha256:".length(), 38));
            int frozenAttemptNo = attemptNo;
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
                                            "productId",
                                            "haifa-personal-assistant",
                                            "sessionKind",
                                            "MISSION_TASK_NORMALIZER",
                                            "sourceRunId",
                                            sourceRunId,
                                            "normalizationAttemptNo",
                                            Integer.toString(frozenAttemptNo))));
                }
                return null;
            });
            var started = agent.runs()
                    .start(new AgentRunRequest(
                            "mission-task-normalizer:" + sourceRunId + ":v1:attempt:" + attemptNo,
                            new AgentDefinitionId("personal-assistant"),
                            Optional.of(new AgentDefinitionVersion(1, 0, 0)),
                            TASK_NORMALIZER_RUN_PROFILE,
                            sessionId,
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
                    .filter(value -> !value.isBlank());
            if (normalized
                    .filter(value -> isResearchTaskResult(value, deepResearchSkill))
                    .isPresent()) {
                return new NormalizedTaskResult(normalized.orElseThrow(), normalizationUsage);
            }
            lastFailure = "MISSION_TASK_NORMALIZATION_SCHEMA_INVALID";
        }
        return new NormalizedTaskResult(
                conservativeResearchTaskResult(objective, result, lastFailure), normalizationUsage);
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

    private static boolean researchTaskSemanticsValid(JsonNode root) {
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
            if (!locator.startsWith("https://")) return false;
            if ("FETCHED".equals(source.path("status").asText())
                    && (source.path("fetchedAt").isNull()
                            || source.path("contentDigest").isNull()
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

    static String conservativeResearchTaskResult(String objective, String result, String normalizationFailure) {
        ObjectNode root = JSON.createObjectNode();
        root.put("schemaVersion", "pa.research-task-result/v1");
        root.put("brief", result.substring(0, Math.min(result.length(), 8_000)));
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
                if ("https".equalsIgnoreCase(uri.getScheme()) && uri.getHost() != null) {
                    locators.add(candidate);
                }
            } catch (IllegalArgumentException ignored) {
                // Ignore malformed locators recovered from free-form notes.
            }
        }
        ArrayNode sources = root.putArray("sources");
        for (String locator : locators) {
            ObjectNode source = sources.addObject();
            source.put(
                    "sourceId",
                    "recovered-source-" + MissionValues.digest(locator).substring(7, 23));
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

    static String taskNormalizationPrompt(String result, SkillContent deepResearchSkill) {
        Objects.requireNonNull(deepResearchSkill);
        return """
                Convert the completed research notes below into exactly one JSON object matching
                pa.research-task-result/v1. Produce compact JSON only, without Markdown fences or commentary. Select
                at most 6 strongest sources and 10 decision-relevant claims. Keep brief under 400 characters, each
                title under 140 characters, each claim under 300 characters, each limitation under 240 characters,
                and unresolvedQuestions to at most 10 items. Do not include quotedSpans or copy long excerpts.

                Required exact top-level fields are schemaVersion, brief, queries, sources, claims, artifactRefs,
                unresolvedQuestions, stopReason, and limitsUsed. Each query has only query and phase. Each source has
                exactly sourceId, locator, normalizedLocator, locatorDigest, title, safetyType, fetchedAt, publishedAt,
                status, excerpt, and contentDigest. Each claim has exactly claimId, claim, supportingSourceIds,
                opposingSourceIds, limitations, unverified, and quotedSpans. artifactRefs must be empty. limitsUsed has
                exactly searchCalls, fetchCalls, sources, and contentBytes. Use lower-case kebab-case stable IDs.

                Preserve only evidence present in the notes. Do not invent a source, locator, quote, date, or claim.
                Summarize the explicit research question in queries; when exact Tool counts are absent, use zero rather
                than fabricating counts. Source identity is finalized by the trusted Server: copy locator into
                normalizedLocator and use sha256:%s as the locatorDigest placeholder. Unless the notes explicitly
                contain a fetched timestamp and valid content digest, set status to UNKNOWN, fetchedAt and
                contentDigest to null, excerpt to an empty string, and every dependent claim to unverified. Mark all
                other insufficiently supported claims as unverified and list unresolved gaps. Valid stopReason values
                are SUFFICIENT_EVIDENCE, SOURCE_LIMIT, CONTENT_LIMIT, TIME_LIMIT, TOOL_LIMIT, NO_MORE_SAFE_SOURCES,
                and CANCELLED.

                Completed research notes:
                %s
                """
                .formatted("0".repeat(64), result);
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
        requireStructuredOutput("Mission Synthesis");
        String stable = MissionValues.digest(intent.missionId(), "synthesis", "v1");
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
        String dispatchKey = "mission:" + intent.missionId() + ":synthesis:v1";
        String prompt =
                """
                [mission-synthesis]
                Produce only one JSON object. Standard Missions use pa.mission-final-result/v1; Deep Research uses
                pa.research-final-result/v1 with directAnswer, completedItems, failedItems, artifactRefs, sourceRefs,
                unverifiedClaims, residualRisks, unresolvedQuestions, completionKind, and the five null Artifact
                placeholders. Do not invent an Artifact reference.
                Mission mode: %s
                Mission objective: %s
                Authoritative settled Task results: %s
                Failed or cancelled Task items: %s
                """
                        .formatted(intent.mode(), intent.objective(), intent.taskResults(), intent.failedItems());
        var started = agent.runs()
                .start(new AgentRunRequest(
                        dispatchKey,
                        new AgentDefinitionId("personal-assistant"),
                        Optional.of(new AgentDefinitionVersion(1, 0, 0)),
                        SYNTHESIS_RUN_PROFILE,
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
                if (intent.mode() == MissionMode.DEEP_RESEARCH) {
                    String failure = terminal.error()
                            .map(error -> error.code().wireCode())
                            .orElse(terminal.status().name());
                    return new SynthesisRunResult(
                            sessionId.value(),
                            terminal.runId().value(),
                            conservativeResearchSynthesis(intent, failure),
                            usage(terminal.usage()));
                }
                throw new MissionException(
                        "MISSION_SYNTHESIS_FAILED", terminal.status().name());
            }
            String output = terminal.result()
                    .map(result -> result.summary())
                    .or(() -> terminal.output())
                    .filter(value -> !value.isBlank())
                    .orElseThrow(() -> new MissionException(
                            "MISSION_SYNTHESIS_SCHEMA_INVALID", "Mission Synthesis returned no result"));
            return new SynthesisRunResult(sessionId.value(), terminal.runId().value(), output, usage(terminal.usage()));
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new MissionException("MISSION_SYNTHESIS_INTERRUPTED", "Mission Synthesis was interrupted", exception);
        }
    }

    static String conservativeResearchSynthesis(MissionSynthesisIntent intent, String synthesisFailure) {
        return conservativeResearchSynthesis(intent, synthesisFailure, "");
    }

    static String conservativeResearchSynthesis(
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
        ArrayNode finalUnverifiedClaims =
                JSON.valueToTree(unverifiedClaims.stream().limit(40).toList());
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
        String stable = MissionValues.digest(missionId, "final-message", "v1");
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
                    Use the smallest feasible DAG that covers the acceptance criteria, normally 3 to 5 Tasks. Combine
                    closely related indicators, ownership, and geographic distribution instead of creating narrow Tasks.
                    Evidence verification, source-quality assessment, and conflict marking belong inside every research
                    Task. Never create a separate cross-Task evidence-checking, verification, or consolidation Task.
                    Do not create a final integration, synthesis, report-writing, or delivery Task. The Mission Synthesis
                    stage assembles the authoritative Task results after every Task settles.
                    Mission objective: %s
                    Acceptance criteria: %s
                    Frozen research brief: %s
                    """
                    .formatted(
                            request.constraints().maxTasks(),
                            request.constraints().maxDependencyDepth(),
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
}
