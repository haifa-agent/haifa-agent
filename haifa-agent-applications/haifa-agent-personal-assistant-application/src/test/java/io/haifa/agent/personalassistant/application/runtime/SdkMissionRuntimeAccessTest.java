package io.haifa.agent.personalassistant.application.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.haifa.agent.core.reference.PrincipalRef;
import io.haifa.agent.core.reference.TenantRef;
import io.haifa.agent.personalassistant.application.mission.MissionConstraints;
import io.haifa.agent.personalassistant.application.mission.MissionDispatchIntent;
import io.haifa.agent.personalassistant.application.mission.MissionMode;
import io.haifa.agent.personalassistant.application.mission.MissionPlanner;
import io.haifa.agent.personalassistant.application.mission.MissionSynthesisIntent;
import io.haifa.agent.personalassistant.application.mission.MissionTaskRunInput;
import io.haifa.agent.personalassistant.application.mission.ResearchBrief;
import io.haifa.agent.personalassistant.application.skill.PersonalSkillPlatform;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class SdkMissionRuntimeAccessTest {
    @Test
    void synthesisProtocolVersionOwnsTheRunIdempotencyNamespace() {
        assertThat(SdkMissionRuntimeAccess.SYNTHESIS_PROTOCOL_VERSION).isEqualTo("v6");
        assertThat(SdkMissionRuntimeAccess.STANDARD_SYNTHESIS_PROTOCOL_VERSION).isEqualTo("v2");
        assertThat(SdkMissionRuntimeAccess.STANDARD_SYNTHESIS_REPAIR_PROTOCOL_VERSION)
                .isEqualTo("v1");
        assertThat(SdkMissionRuntimeAccess.PLANNER_REPAIR_PROTOCOL_VERSION).isEqualTo("v4");
        assertThat(SdkMissionRuntimeAccess.TASK_NORMALIZATION_PROTOCOL_VERSION).isEqualTo("v5");
        assertThat(SdkMissionRuntimeAccess.synthesisDispatchKey("mission-1"))
                .isEqualTo("mission:mission-1:synthesis:v6");
        assertThat(SdkMissionRuntimeAccess.synthesisDispatchKey("mission-1", 1))
                .isEqualTo("mission:mission-1:synthesis:v6:revision-1");
        assertThat(SdkMissionRuntimeAccess.synthesisDispatchKey("mission-1", 2))
                .isEqualTo("mission:mission-1:synthesis:v6:revision-2");
        assertThat(SdkMissionRuntimeAccess.standardSynthesisDispatchKey("mission-1"))
                .isEqualTo("mission:mission-1:synthesis:standard:v2");
        assertThat(SdkMissionRuntimeAccess.standardSynthesisRepairDispatchKey("mission-1", "run-invalid", 1))
                .isEqualTo("mission:mission-1:synthesis:standard:v2:repair:v1:attempt-1:source:run-invalid");
    }

    @Test
    void standardSynthesisFreezesTheCompleteSchemaAndOneRepairContract() {
        var intent = new MissionSynthesisIntent(
                "mission-1",
                "conversation-1",
                "owner-1",
                MissionMode.STANDARD,
                "Summarize Ethereum upgrades",
                List.of("{\"task\":\"settled\"}"),
                List.of());

        assertThat(SdkMissionRuntimeAccess.standardSynthesisPrompt(intent))
                .contains(
                        "exactly one JSON object",
                        "pa.mission-final-result/v1",
                        "\"directAnswer\"",
                        "\"completedItems\"",
                        "\"failedItems\"",
                        "\"artifactRefs\":[]",
                        "\"sourceRefs\"",
                        "\"unverifiedClaims\"",
                        "\"unresolvedQuestions\"",
                        "\"residualRisks\"",
                        "\"completionKind\"",
                        "Frozen Mission ID: mission-1",
                        "Do not add result, missionId, missionObjective, missionMode")
                .contains("COMPLETE exactly when failedItems is empty", "PARTIAL", "failedItems is non-empty");

        assertThat(SdkMissionRuntimeAccess.standardSynthesisRepairPrompt(
                        intent,
                        "run-invalid",
                        "{\"schemaVersion\":\"pa.mission-final-result/v1\",\"result\":{}}",
                        "MISSION_RESULT_SCHEMA_INVALID",
                        "directAnswer is invalid",
                        1))
                .contains(
                        "single bounded deterministic schema repair attempt",
                        "Rejected source Synthesis Run ID: run-invalid",
                        "MISSION_RESULT_SCHEMA_INVALID - directAnswer is invalid",
                        "\"result\":{}",
                        "artifactRefs must be empty",
                        "Do not add any other top-level field");
    }

    @Test
    void plannerDelegatesFinalIntegrationToTheDedicatedSynthesisStage() {
        var request = new MissionPlanner.PlanningRequest(
                "mission-1",
                "Plan a family move with a pet from Shanghai to Singapore",
                List.of("Verify immigration policy", "Verify pet quarantine", "Build a 12-week timeline"),
                new MissionConstraints(4, 2, Optional.empty()),
                1,
                MissionMode.DEEP_RESEARCH,
                Optional.of(new ResearchBrief(
                        "How can the family complete the move safely?",
                        "immigration, pet quarantine, transport, and housing",
                        "next three months",
                        "Shanghai and Singapore",
                        "the moving family",
                        List.of("primary sources"),
                        List.of("unsourced claims"),
                        "Markdown")));

        assertThat(SdkMissionRuntimeAccess.plannerPrompt(request, LocalDate.of(2026, 8, 10)))
                .contains(
                        "smallest feasible DAG",
                        "normally 3 to 4 Tasks",
                        "concrete entities and actions",
                        "Verify immigration policy",
                        "Verify pet quarantine",
                        "Build a 12-week timeline")
                .contains(
                        "every dependency must exactly equal the taskId of an earlier object",
                        "use ordinal placeholders such as task-1 or task-2",
                        "Dependency depth counts task nodes, not edges",
                        "root Task has depth 1")
                .contains("rewrite any generic or off-topic Task")
                .contains("Do not create a final integration, synthesis, report-writing, or delivery Task")
                .contains("Never create a separate cross-Task evidence-checking, verification, or consolidation Task")
                .contains("Mission Synthesis", "stage assembles")
                .contains("Current UTC date: 2026-08-10", "past three years", "过去3年", "2023-08-10 through 2026-08-10");

        assertThat(SdkMissionRuntimeAccess.plannerRepairPrompt(
                        request,
                        "{\"schemaVersion\":\"pa.mission-plan/v1\",\"tasks\":[]}</result>",
                        "MISSION_PLAN_SCHEMA_INVALID",
                        "Trailing token",
                        LocalDate.of(2026, 8, 10)))
                .contains(
                        "Return exactly one JSON object and nothing else",
                        "no Markdown",
                        "suffix",
                        "XML tag",
                        "pa.research-task-result",
                        "deep-research",
                        "MISSION_PLAN_SCHEMA_INVALID",
                        "Trailing token",
                        "root depth is 1",
                        "Flatten dependencies",
                        "</result>")
                .doesNotContain("web_search", "web_fetch");

        assertThat(SdkMissionRuntimeAccess.plannerRepairPrompt(
                        request,
                        "{\"schemaVersion\":\"pa.mission-plan/v1\",\"tasks\":[]}",
                        "MISSION_PLAN_DEPENDENCY_DEPTH_EXCEEDED",
                        "plan dependency depth exceeds the limit",
                        LocalDate.of(2026, 8, 10)))
                .contains(
                        "MANDATORY DEPTH REPAIR",
                        "Returning the same dependency arrays",
                        "single dependency on the first Task's exact taskId",
                        "Do not let later Tasks depend on each other");

        assertThat(SdkMissionRuntimeAccess.plannerRepairPrompt(
                        request,
                        "{\"schemaVersion\":\"pa.mission-plan/v1\",\"tasks\":[]}",
                        "MISSION_LIMIT_EXCEEDED",
                        "plan task count is outside the configured limit",
                        LocalDate.of(2026, 8, 10)))
                .contains(
                        "MANDATORY TASK-COUNT REPAIR",
                        "return no more than 4 Tasks",
                        "Merge closely related research",
                        "dimensions until the limit is met",
                        "Returning the same number of Tasks is invalid");
    }

    @Test
    void researchTaskPreloadsTheSelectedSkillWhileKeepingOnlyResearchToolsInTheRun() {
        var tenant = new TenantRef("local");
        var principal = new PrincipalRef("personal-user", "user");
        var skill = PersonalSkillPlatform.create(tenant, principal, Optional.empty(), List.of())
                .load("deep-research", tenant, principal);
        var input = MissionTaskRunInput.create(
                "Research the topic",
                List.of("Cite fetched sources"),
                "Research the topic",
                List.of("Cite fetched sources"),
                "RESEARCH",
                List.of("deep-research"),
                "pa.research-task-result",
                "v1",
                Optional.of(truthfulnessBrief()),
                List.of());
        var intent = new MissionDispatchIntent(
                "outbox-1",
                "mission-1",
                "owner-1",
                "task-1",
                1,
                "dispatch-1",
                "sha256:" + "1".repeat(64),
                input,
                Instant.parse("2026-08-09T00:00:00Z"));

        assertThat(SdkMissionRuntimeAccess.taskPrompt(intent, skill))
                .contains(
                        "The Deep Research Product Skill was explicitly selected and is preloaded below",
                        "references/research-types.md",
                        "TRUTHFULNESS_INVESTIGATION",
                        "Frozen Research Brief",
                        "Which claims about Acme AI are true?",
                        "references/research-method.md",
                        "schemas/research-task-result-v1.json",
                        "utility_wikipedia_search",
                        "pa.research-task-result/v1",
                        "hard safety ceiling of 40 research Tool calls",
                        "At 24 completed Tool calls",
                        "FINALIZE_ONLY",
                        "maxCharacters to at most 10000",
                        "never print or serialize",
                        "DSML")
                .doesNotContain("calling Skill discovery or Skill resource Tools.\n\n                    [missing]");
        assertThat(SdkMissionRuntimeAccess.TASK_RESEARCH_TOOL_CALL_TARGET).isEqualTo(24);

        assertThat(SdkMissionRuntimeAccess.taskNormalizationPrompt(
                        "market-evidence", "Research Amazon Halo", "research notes", skill))
                .contains(
                        "exactly one JSON object",
                        "pa.research-task-result/v1",
                        "Do not invent a source",
                        "query.phase: DISCOVER, DEEPEN, or CROSS_CHECK",
                        "source.safetyType: PUBLIC_WEB",
                        "artifactRefs and every claim.quotedSpans must be JSON arrays",
                        "continues the same source",
                        "serialized DSML",
                        "Never substitute another company",
                        "usable discovery evidence even when a",
                        "later fetch failed",
                        "brief must explicitly name the selected subject",
                        "market-evidence--",
                        "Research Amazon Halo",
                        "research notes")
                .doesNotContain("web_search", "web_fetch");
    }

    @Test
    void dependentTaskReceivesBoundedStructuredPriorWorkAndAnEnforcedSmallerProfile() {
        var tenant = new TenantRef("local");
        var principal = new PrincipalRef("personal-user", "user");
        var skill = PersonalSkillPlatform.create(tenant, principal, Optional.empty(), List.of())
                .load("deep-research", tenant, principal);
        String dependencyResult =
                """
                {
                  "brief":"Verified policy evidence",
                  "sources":[{"sourceId":"official-1","normalizedLocator":"https://example.gov/policy","title":"Policy","status":"FETCHED","publishedAt":"2026-08-01T00:00:00Z"}],
                  "claims":[{"claimId":"claim-1","claim":"The policy applies","supportingSourceIds":["official-1"],"opposingSourceIds":[],"limitations":"Current as queried","unverified":false}],
                  "artifactRefs":[],"unresolvedQuestions":[]
                }
                """;
        var input = MissionTaskRunInput.create(
                "Research a relocation",
                List.of("Deliver an integrated plan"),
                "Integrate the timeline",
                List.of("Reuse policy evidence"),
                "RESEARCH",
                List.of("deep-research"),
                "pa.research-task-result",
                "v1",
                Optional.of(truthfulnessBrief()),
                List.of(new MissionTaskRunInput.DependencyResult(
                        "policy", "pa.research-task-result", "v1", "sha256:" + "b".repeat(64), dependencyResult)));
        var intent = new MissionDispatchIntent(
                "outbox-2",
                "mission-1",
                "owner-1",
                "timeline",
                1,
                "dispatch-2",
                "sha256:" + "c".repeat(64),
                input,
                Instant.parse("2026-08-09T00:00:00Z"));

        assertThat(input.executionProfileId()).isEqualTo(SdkMissionRuntimeAccess.DEPENDENT_TASK_RUN_PROFILE);
        assertThat(SdkMissionRuntimeAccess.taskPrompt(intent, skill))
                .contains(
                        "Frozen direct dependency context",
                        "Verified policy evidence",
                        "https://example.gov/policy",
                        "Do not repeat searches already covered",
                        "hard safety ceiling of 32 research Tool calls",
                        "At 16 completed Tool calls",
                        "FINALIZE_ONLY",
                        "maxCharacters to at most 8000");
    }

    @Test
    void researchTaskAndSynthesisReceiveTheSameFrozenBriefAndResearchTypeTable() {
        var tenant = new TenantRef("local");
        var principal = new PrincipalRef("personal-user", "user");
        var skill = PersonalSkillPlatform.create(tenant, principal, Optional.empty(), List.of())
                .load("deep-research", tenant, principal);
        ResearchBrief brief = truthfulnessBrief();
        var input = MissionTaskRunInput.create(
                brief.question(),
                List.of("Separate promotion from verification"),
                "Verify the material product claims",
                List.of("Find primary evidence and independent validation"),
                "RESEARCH",
                List.of("deep-research"),
                "pa.research-task-result",
                "v1",
                Optional.of(brief),
                List.of());
        var task = new MissionDispatchIntent(
                "outbox-brief",
                "mission-brief",
                "owner-1",
                "truthfulness",
                1,
                "dispatch-brief",
                "sha256:" + "d".repeat(64),
                input,
                Instant.parse("2026-08-10T00:00:00Z"));
        var synthesis = new MissionSynthesisIntent(
                "mission-brief",
                "conversation-1",
                "owner-1",
                MissionMode.DEEP_RESEARCH,
                brief.question(),
                List.of("{\"schemaVersion\":\"pa.research-task-result/v1\"}"),
                List.of(),
                List.of("truthfulness"),
                2,
                10_000,
                Optional.empty(),
                Optional.of(brief));

        String taskPrompt = SdkMissionRuntimeAccess.taskPrompt(task, skill);
        String synthesisPrompt = SdkMissionRuntimeAccess.initialResearchSynthesisPrompt(synthesis, skill);
        String frozenBrief = SdkMissionRuntimeAccess.frozenResearchBrief(Optional.of(brief));
        String typeTable = skill.resource("references/research-types.md");

        assertThat(taskPrompt).contains(frozenBrief, typeTable);
        assertThat(synthesisPrompt).contains(frozenBrief, typeTable);
        assertThat(taskPrompt).hasSizeLessThanOrEqualTo(16_000);
        assertThat(synthesisPrompt).hasSizeLessThanOrEqualTo(8_000);
        assertThat(synthesisPrompt)
                .contains("TRUTHFULNESS_INVESTIGATION", "claim-evidence-counterevidence")
                .doesNotContain("web_search", "web_fetch", "research-method.md", "source-quality.md");
    }

    @Test
    void fiveRepresentativeBriefsShareOneTypeTableAndItsReportAddition() {
        record Scenario(String question, String type, String reportAddition) {}
        List<Scenario> scenarios = List.of(
                new Scenario(
                        "Are the advertised AI product capabilities true?",
                        "TRUTHFULNESS_INVESTIGATION",
                        "claim-evidence-counterevidence"),
                new Scenario(
                        "Which relocation option should the family choose?", "DECISION", "triggers, and failure plan"),
                new Scenario("Which current rules apply to this operator?", "POLICY_RISK", "effective-date table"),
                new Scenario("Why did the service launch fail?", "FAILURE_POSTMORTEM", "timeline and causal analysis"),
                new Scenario(
                        "Explain the ecosystem and its limitations", "GENERAL_RESEARCH", "no type-specific section"));
        var tenant = new TenantRef("local");
        var principal = new PrincipalRef("personal-user", "user");
        var skill = PersonalSkillPlatform.create(tenant, principal, Optional.empty(), List.of())
                .load("deep-research", tenant, principal);
        String typeTable = skill.resource("references/research-types.md");

        for (Scenario scenario : scenarios) {
            ResearchBrief brief = new ResearchBrief(
                    scenario.question(),
                    "Bounded evidence relevant to the question",
                    "<start-date> through <end-date>",
                    "Frozen region",
                    "Decision maker",
                    List.of("primary sources"),
                    List.of("unfetched snippets"),
                    "Markdown report");
            var input = MissionTaskRunInput.create(
                    scenario.question(),
                    List.of("Use bounded evidence"),
                    scenario.question(),
                    List.of("Close material claims"),
                    "RESEARCH",
                    List.of("deep-research"),
                    "pa.research-task-result",
                    "v1",
                    Optional.of(brief),
                    List.of());
            var task = new MissionDispatchIntent(
                    "outbox-" + scenario.type(),
                    "mission-" + scenario.type(),
                    "owner-1",
                    "task-1",
                    1,
                    "dispatch-" + scenario.type(),
                    "sha256:" + "e".repeat(64),
                    input,
                    Instant.parse("2026-08-10T00:00:00Z"));
            var synthesis = new MissionSynthesisIntent(
                    "mission-" + scenario.type(),
                    "conversation-1",
                    "owner-1",
                    MissionMode.DEEP_RESEARCH,
                    scenario.question(),
                    List.of("{\"schemaVersion\":\"pa.research-task-result/v1\"}"),
                    List.of(),
                    List.of("task-1"),
                    2,
                    10_000,
                    Optional.empty(),
                    Optional.of(brief));

            assertThat(typeTable).contains(scenario.type(), scenario.reportAddition());
            assertThat(SdkMissionRuntimeAccess.taskPrompt(task, skill))
                    .contains(SdkMissionRuntimeAccess.frozenResearchBrief(Optional.of(brief)), typeTable);
            assertThat(SdkMissionRuntimeAccess.initialResearchSynthesisPrompt(synthesis, skill))
                    .contains(SdkMissionRuntimeAccess.frozenResearchBrief(Optional.of(brief)), typeTable);
        }
    }

    @Test
    void dependencyProjectionAlwaysRemainsValidJsonWithinTheContextLimit() throws Exception {
        String largeResult =
                """
                {"brief":"%s","sources":[],"claims":[],"artifactRefs":[],"unresolvedQuestions":[]}
                """
                        .formatted("x".repeat(100_000));
        var dependencies = java.util.stream.IntStream.range(0, 8)
                .mapToObj(index -> new MissionTaskRunInput.DependencyResult(
                        "task-" + index,
                        "pa.research-task-result",
                        "v1",
                        "sha256:" + Integer.toHexString(index).repeat(64).substring(0, 64),
                        largeResult))
                .toList();

        String projected = MissionDependencyContextProjector.project(dependencies);

        assertThat(projected).hasSizeLessThanOrEqualTo(MissionDependencyContextProjector.MAX_CONTEXT_CHARACTERS);
        assertThat(new ObjectMapper().readTree(projected).path("dependencies").size())
                .isEqualTo(8);
    }

    @Test
    void conservativeNormalizationFallbackPreservesNotesWithoutInventingClaims() throws Exception {
        var tenant = new TenantRef("local");
        var principal = new PrincipalRef("personal-user", "user");
        var skill = PersonalSkillPlatform.create(tenant, principal, Optional.empty(), List.of())
                .load("deep-research", tenant, principal);
        String notes = "Historical notes https://example.gov.cn/policy and insecure "
                + "http://example.com/legacy and more evidence."
                + "x".repeat(9_000);

        String fallback = SdkMissionRuntimeAccess.conservativeResearchTaskResult(
                "Research Jingning hydropower", notes, "MODEL_RESPONSE_INVALID");
        var root = new ObjectMapper().readTree(fallback);

        assertThat(root.path("schemaVersion").asText()).isEqualTo("pa.research-task-result/v1");
        assertThat(root.path("brief").asText()).hasSize(8_000);
        assertThat(root.path("sources").size()).isEqualTo(2);
        assertThat(root.path("sources").get(0).path("status").asText()).isEqualTo("UNKNOWN");
        assertThat(root.path("sources").get(1).path("status").asText()).isEqualTo("UNKNOWN");
        assertThat(root.path("claims").size()).isZero();
        assertThat(root.path("unresolvedQuestions").get(0).asText()).contains("MODEL_RESPONSE_INVALID");
        assertThat(SdkMissionRuntimeAccess.isResearchTaskResult(fallback, skill))
                .isTrue();
    }

    @Test
    void normalizationRejectsTopLevelShapeThatViolatesTheResearchSchema() throws Exception {
        var tenant = new TenantRef("local");
        var principal = new PrincipalRef("personal-user", "user");
        var skill = PersonalSkillPlatform.create(tenant, principal, Optional.empty(), List.of())
                .load("deep-research", tenant, principal);
        String invalid =
                """
                {"schemaVersion":"pa.research-task-result/v1","brief":"notes",
                "queries":[{"query":"topic","phase":"RESEARCH"}],"sources":[],"claims":[],
                "artifactRefs":[],"unresolvedQuestions":[],"stopReason":"TIME_LIMIT",
                "limitsUsed":{"searchCalls":0,"fetchCalls":0,"sources":0,"contentBytes":0}}
                """;

        assertThat(SdkMissionRuntimeAccess.isResearchTaskResult(invalid, skill)).isFalse();
    }

    @Test
    void normalizationRejectsSerializedToolProtocolAndPlaceholderFetchedDigests() throws Exception {
        var tenant = new TenantRef("local");
        var principal = new PrincipalRef("personal-user", "user");
        var skill = PersonalSkillPlatform.create(tenant, principal, Optional.empty(), List.of())
                .load("deep-research", tenant, principal);
        String invalid =
                """
                {
                  "schemaVersion":"pa.research-task-result/v1",
                  "brief":"<｜｜DSML｜｜tool_calls><｜｜DSML｜｜invoke name=\"web_fetch\">",
                  "queries":[{"query":"Amazon Halo","phase":"CROSS_CHECK"}],
                  "sources":[{
                    "sourceId":"source-1","locator":"https://example.com/halo",
                    "normalizedLocator":"https://example.com/halo",
                    "locatorDigest":"sha256:%s","title":"Halo","safetyType":"PUBLIC_WEB",
                    "fetchedAt":"2026-08-10T00:00:00Z","publishedAt":null,"status":"FETCHED",
                    "excerpt":"Evidence","contentDigest":"sha256:%s"
                  }],
                  "claims":[],"artifactRefs":[],"unresolvedQuestions":[],"stopReason":"TIME_LIMIT",
                  "limitsUsed":{"searchCalls":1,"fetchCalls":1,"sources":1,"contentBytes":64}
                }
                """
                        .formatted("a".repeat(64), "0".repeat(64));

        assertThat(SdkMissionRuntimeAccess.isResearchTaskResult(invalid, skill)).isFalse();
    }

    @Test
    void conservativeFallbackDiscardsSerializedToolProtocolButRecoversPublicLocators() throws Exception {
        String fallback = SdkMissionRuntimeAccess.conservativeResearchTaskResult(
                "Select one representative product failure",
                "<｜｜DSML｜｜tool_calls><｜｜DSML｜｜invoke name=\"web_fetch\">https://example.com/halo",
                "MISSION_TASK_NORMALIZATION_SCHEMA_INVALID");
        JsonNode root = new ObjectMapper().readTree(fallback);

        assertThat(root.path("brief").asText())
                .contains("Select one representative product failure", "discarded serialized Tool protocol markup")
                .doesNotContain("DSML", "tool_calls", "invoke name");
        assertThat(root.path("sources")).hasSize(1);
        assertThat(root.path("sources").get(0).path("status").asText()).isEqualTo("UNKNOWN");
    }

    @Test
    void normalizationAcceptsHttpEvidenceAllowedByThePublicWebPolicy() throws Exception {
        var tenant = new TenantRef("local");
        var principal = new PrincipalRef("personal-user", "user");
        var skill = PersonalSkillPlatform.create(tenant, principal, Optional.empty(), List.of())
                .load("deep-research", tenant, principal);
        String result =
                """
                {
                  "schemaVersion":"pa.research-task-result/v1",
                  "brief":"Verified public evidence",
                  "queries":[{"query":"official policy","phase":"CROSS_CHECK"}],
                  "sources":[{
                    "sourceId":"official-1","locator":"http://www.news.cn/policy",
                    "normalizedLocator":"http://www.news.cn/policy",
                    "locatorDigest":"sha256:%s","title":"Policy","safetyType":"PUBLIC_WEB",
                    "fetchedAt":"2026-08-10T00:00:00Z","publishedAt":null,"status":"FETCHED",
                    "excerpt":"Verified excerpt","contentDigest":"sha256:%s"
                  }],
                  "claims":[{
                    "claimId":"claim-1","claim":"The policy applies","supportingSourceIds":["official-1"],
                    "opposingSourceIds":[],"limitations":"Current as queried","unverified":false,"quotedSpans":[]
                  }],
                  "artifactRefs":[],"unresolvedQuestions":[],"stopReason":"SUFFICIENT_EVIDENCE",
                  "limitsUsed":{"searchCalls":1,"fetchCalls":1,"sources":1,"contentBytes":64}
                }
                """
                        .formatted("a".repeat(64), "b".repeat(64));

        assertThat(SdkMissionRuntimeAccess.isResearchTaskResult(result, skill)).isTrue();
    }

    @Test
    void normalizationCanonicalizesPublicationDatesWithoutInventingADifferentDay() throws Exception {
        String raw =
                """
                {"sources":[{"fetchedAt":"2026-08-10T08:30:00+08:00","publishedAt":"2020-08-30"}]}
                """;

        JsonNode canonical = new ObjectMapper().readTree(SdkMissionRuntimeAccess.canonicalizeResearchTaskResult(raw));

        assertThat(canonical.path("sources").get(0).path("fetchedAt").asText()).isEqualTo("2026-08-10T00:30:00Z");
        assertThat(canonical.path("sources").get(0).path("publishedAt").asText())
                .isEqualTo("2020-08-30T00:00:00Z");
    }

    @Test
    void normalizationRepairsTrustedMetadataWithoutUpgradingUnfetchedEvidence() throws Exception {
        var tenant = new TenantRef("local");
        var principal = new PrincipalRef("personal-user", "user");
        var skill = PersonalSkillPlatform.create(tenant, principal, Optional.empty(), List.of())
                .load("deep-research", tenant, principal);
        String raw =
                """
                {
                  "schemaVersion":"pa.research-task-result/v1","brief":"Google Stadia evidence",
                  "queries":[{"query":"Google Stadia","phase":"CROSS_CHECK"}],
                  "sources":[{
                    "sourceId":"stadia-baike","locator":"https://baike.baidu.com/item/Stadia/云游戏",
                    "normalizedLocator":"https://baike.baidu.com/item/Stadia/云游戏",
                    "locatorDigest":"sha256:%s","title":"Stadia","safetyType":"PUBLIC_WEB",
                    "fetchedAt":"2026-08-10T00:00:00Z","publishedAt":null,"status":"FETCHED",
                    "excerpt":"Search-only evidence","contentDigest":null
                  }],
                  "claims":[{
                    "claimId":"stadia-closed","claim":"Stadia closed","supportingSourceIds":["stadia-baike"],
                    "opposingSourceIds":[],"limitations":["Only a search result was available"],
                    "unverified":false
                  }],
                  "artifactRefs":[],"unresolvedQuestions":[],"stopReason":"SUFFICIENT_EVIDENCE",
                  "limitsUsed":{"searchCalls":1,"fetchCalls":1,"sources":9,"contentBytes":10}
                }
                """
                        .formatted("0".repeat(64));

        JsonNode canonical = new ObjectMapper().readTree(SdkMissionRuntimeAccess.canonicalizeResearchTaskResult(raw));

        assertThat(canonical.path("sources")).hasSize(1);
        assertThat(canonical.path("sources").get(0).path("locator").asText()).contains("%E4%BA%91%E6%B8%B8%E6%88%8F");
        assertThat(canonical.path("sources").get(0).path("locatorDigest").asText())
                .doesNotEndWith("0".repeat(64));
        assertThat(canonical.path("sources").get(0).path("status").asText()).isEqualTo("UNKNOWN");
        assertThat(canonical.path("sources").get(0).path("fetchedAt").isNull()).isTrue();
        assertThat(canonical.path("claims").get(0).path("limitations").asText())
                .isEqualTo("Only a search result was available");
        assertThat(canonical.path("claims").get(0).path("unverified").asBoolean())
                .isTrue();
        assertThat(canonical.path("claims").get(0).path("quotedSpans")).isEmpty();
        assertThat(canonical.path("limitsUsed").path("sources").asInt()).isEqualTo(1);
        assertThat(SdkMissionRuntimeAccess.isResearchTaskResult(canonical.toString(), skill))
                .isTrue();
    }

    @Test
    void normalizationNamespacesTaskLocalEvidenceIdsAndReferences() throws Exception {
        String raw =
                """
                {
                  "schemaVersion":"pa.research-task-result/v1","brief":"Google Stadia evidence",
                  "queries":[{"query":"Google Stadia","phase":"CROSS_CHECK"}],
                  "sources":[{
                    "sourceId":"official-1","locator":"https://blog.google/products/stadia/message-on-stadia-streaming-strategy/",
                    "normalizedLocator":"https://blog.google/products/stadia/message-on-stadia-streaming-strategy/",
                    "locatorDigest":"sha256:%s","title":"Google Stadia update","safetyType":"PUBLIC_WEB",
                    "fetchedAt":"2026-08-10T00:00:00Z","publishedAt":"2022-09-29T00:00:00Z",
                    "status":"FETCHED","excerpt":"Google announced the shutdown.","contentDigest":"sha256:%s"
                  }],
                  "claims":[{
                    "claimId":"shutdown-announcement","claim":"Google announced the shutdown.",
                    "supportingSourceIds":["official-1"],"opposingSourceIds":[],"limitations":"Official statement only",
                    "unverified":false,"quotedSpans":[]
                  }],
                  "artifactRefs":[],"unresolvedQuestions":[],"stopReason":"SUFFICIENT_EVIDENCE",
                  "limitsUsed":{"searchCalls":1,"fetchCalls":1,"sources":1,"contentBytes":64}
                }
                """
                        .formatted("0".repeat(64), "b".repeat(64));

        JsonNode canonical = new ObjectMapper()
                .readTree(SdkMissionRuntimeAccess.canonicalizeResearchTaskResult(raw, "narrative-comparison"));

        assertThat(canonical.path("sources").get(0).path("sourceId").asText())
                .isEqualTo("narrative-comparison--official-1");
        assertThat(canonical.path("claims").get(0).path("claimId").asText())
                .isEqualTo("narrative-comparison--shutdown-announcement");
        assertThat(canonical
                        .path("claims")
                        .get(0)
                        .path("supportingSourceIds")
                        .get(0)
                        .asText())
                .isEqualTo("narrative-comparison--official-1");
    }

    @Test
    void normalizationConvertsUnicodeEvidenceIdsToTheStableAsciiContract() throws Exception {
        String raw =
                """
                {
                  "schemaVersion":"pa.research-task-result/v1","brief":"Ethereum upgrade evidence",
                  "queries":[{"query":"Ethereum Hegota upgrade","phase":"CROSS_CHECK"}],
                  "sources":[{
                    "sourceId":"evidence-task--especificación-éip","locator":"https://eips.ethereum.org/EIPS/eip-7702",
                    "normalizedLocator":"https://eips.ethereum.org/EIPS/eip-7702",
                    "locatorDigest":"sha256:%s","title":"EIP-7702","safetyType":"PUBLIC_WEB",
                    "fetchedAt":null,"publishedAt":"2024-05-07T00:00:00Z",
                    "status":"UNKNOWN","excerpt":"","contentDigest":null
                  }],
                  "claims":[{
                    "claimId":"evidence-task--hegotá-2026","claim":"The roadmap name contains an accented character.",
                    "supportingSourceIds":["evidence-task--especificación-éip"],"opposingSourceIds":[],
                    "limitations":"Roadmap timing remains uncertain","unverified":true,"quotedSpans":[]
                  }],
                  "artifactRefs":[],"unresolvedQuestions":[],"stopReason":"SUFFICIENT_EVIDENCE",
                  "limitsUsed":{"searchCalls":1,"fetchCalls":0,"sources":1,"contentBytes":0}
                }
                """
                        .formatted("0".repeat(64));

        JsonNode canonical = new ObjectMapper()
                .readTree(SdkMissionRuntimeAccess.canonicalizeResearchTaskResult(raw, "evidence-task"));

        assertThat(canonical.path("sources").get(0).path("sourceId").asText())
                .isEqualTo("evidence-task--especificacion-eip");
        assertThat(canonical.path("claims").get(0).path("claimId").asText()).isEqualTo("evidence-task--hegota-2026");
        assertThat(canonical
                        .path("claims")
                        .get(0)
                        .path("supportingSourceIds")
                        .get(0)
                        .asText())
                .isEqualTo("evidence-task--especificacion-eip");
    }

    @Test
    void normalizationClearsFetchMetadataUnlessTheFetchedDigestIsCanonical() throws Exception {
        String raw =
                """
                {
                  "schemaVersion":"pa.research-task-result/v1","brief":"Evidence",
                  "queries":[{"query":"evidence","phase":"CROSS_CHECK"}],
                  "sources":[
                    {"sourceId":"unknown-source","locator":"https://example.com/unknown",
                     "normalizedLocator":"https://example.com/unknown","locatorDigest":"sha256:%s",
                     "title":"Unknown","safetyType":"PUBLIC_WEB","fetchedAt":null,"publishedAt":null,
                     "status":"UNKNOWN","excerpt":"","contentDigest":"%s"},
                    {"sourceId":"invalid-fetch","locator":"https://example.com/fetched",
                     "normalizedLocator":"https://example.com/fetched","locatorDigest":"sha256:%s",
                     "title":"Fetched","safetyType":"PUBLIC_WEB","fetchedAt":"2026-08-10T00:00:00Z",
                     "publishedAt":null,"status":"FETCHED","excerpt":"Evidence","contentDigest":"%s"}
                  ],
                  "claims":[
                    {"claimId":"unknown-claim","claim":"Unknown claim","supportingSourceIds":["unknown-source"],
                     "opposingSourceIds":[],"limitations":"Unknown","unverified":true,"quotedSpans":[]},
                    {"claimId":"fetch-claim","claim":"Fetch claim","supportingSourceIds":["invalid-fetch"],
                     "opposingSourceIds":[],"limitations":"Invalid digest","unverified":false,"quotedSpans":[]}
                  ],
                  "artifactRefs":[],"unresolvedQuestions":[],"stopReason":"SUFFICIENT_EVIDENCE",
                  "limitsUsed":{"searchCalls":1,"fetchCalls":1,"sources":2,"contentBytes":64}
                }
                """
                        .formatted("a".repeat(64), "0".repeat(64), "b".repeat(64), "c".repeat(64));

        JsonNode canonical = new ObjectMapper()
                .readTree(SdkMissionRuntimeAccess.canonicalizeResearchTaskResult(raw, "evidence-task"));

        assertThat(canonical.path("sources")).allSatisfy(source -> {
            assertThat(source.path("status").asText()).isEqualTo("UNKNOWN");
            assertThat(source.path("fetchedAt").isNull()).isTrue();
            assertThat(source.path("contentDigest").isNull()).isTrue();
            assertThat(source.path("excerpt").asText()).isEmpty();
        });
        assertThat(canonical.path("claims"))
                .allSatisfy(claim ->
                        assertThat(claim.path("unverified").asBoolean()).isTrue());
    }

    @Test
    void researchSynthesisCanonicalizationRepairsRepresentationalDrift() throws Exception {
        String raw =
                """
                {"schemaVersion":"pa.research-final-result/v1","mission":"unexpected-extra",
                "directAnswer":{"summary":"answer","evidence":"support"},
                "completedItems":[{"taskId":"timeline","result":"Timeline completed","status":"COMPLETE"}],
                "failedItems":[{"taskId":"policy","reason":"Source unavailable"}],
                "artifactRefs":[null,null],"sourceRefs":[],
                "unverifiedClaims":[],"unresolvedQuestions":[],"residualRisks":[],"completionKind":"COMPLETE"}
                """;

        JsonNode canonical = new ObjectMapper().readTree(SdkMissionRuntimeAccess.canonicalizeResearchSynthesis(raw));

        assertThat(canonical.path("reportArtifactRef").isNull()).isTrue();
        assertThat(canonical.path("sourcesArtifactRef").isNull()).isTrue();
        assertThat(canonical.path("claimEvidenceArtifactRef").isNull()).isTrue();
        assertThat(canonical.path("resultArtifactRef").isNull()).isTrue();
        assertThat(canonical.path("unresolvedArtifactRef").isNull()).isTrue();
        assertThat(canonical.path("artifactRefs")).isEmpty();
        assertThat(canonical.path("directAnswer").asText()).isEqualTo("answer\n\nsupport");
        assertThat(canonical.has("mission")).isFalse();
        assertThat(canonical.path("completedItems").get(0).asText()).isEqualTo("timeline: Timeline completed");
        assertThat(canonical.path("failedItems").get(0).asText()).isEqualTo("policy: Source unavailable");
    }

    @Test
    void conservativeSynthesisPreservesCompletedBriefsAndMarksPartialDelivery() throws Exception {
        String task = SdkMissionRuntimeAccess.conservativeResearchTaskResult(
                "Research Jingning hydropower",
                "Historical notes https://example.gov.cn/policy",
                "MODEL_RESPONSE_INVALID");
        var intent = new MissionSynthesisIntent(
                "mission-1",
                "conversation-1",
                "owner-1",
                MissionMode.DEEP_RESEARCH,
                "Research Jingning hydropower",
                List.of(task),
                List.of("Ecological transition: MODEL_CONTEXT_TOO_LONG"),
                List.of("task-1"),
                2,
                Long.MAX_VALUE,
                Optional.empty(),
                Optional.of(truthfulnessBrief()));

        var root = new ObjectMapper()
                .readTree(SdkMissionRuntimeAccess.conservativeResearchSynthesis(intent, "MODEL_RESPONSE_INVALID"));

        assertThat(root.path("schemaVersion").asText()).isEqualTo("pa.research-final-result/v1");
        assertThat(root.path("directAnswer").asText()).contains("Historical notes");
        assertThat(root.path("sourceRefs").size()).isEqualTo(1);
        assertThat(root.path("failedItems").size()).isEqualTo(1);
        assertThat(root.path("completionKind").asText()).isEqualTo("PARTIAL");
        assertThat(root.path("reportArtifactRef").isNull()).isTrue();
    }

    @Test
    void conservativeSynthesisPreservesAUsableIntegratedAnswerFromInvalidMetadata() throws Exception {
        String task = SdkMissionRuntimeAccess.conservativeResearchTaskResult(
                "Research Jingning hydropower", "Historical task notes", "MODEL_RESPONSE_INVALID");
        var intent = new MissionSynthesisIntent(
                "mission-1",
                "conversation-1",
                "owner-1",
                MissionMode.DEEP_RESEARCH,
                "Research Jingning hydropower",
                List.of(task),
                List.of(),
                List.of("task-1"),
                2,
                Long.MAX_VALUE,
                Optional.empty(),
                Optional.of(truthfulnessBrief()));
        String invalidModelResult =
                "{\"directAnswer\":\"Integrated answer from all settled tasks\",\"sourceRefs\":[\"invented\"]}";

        var root = new ObjectMapper()
                .readTree(SdkMissionRuntimeAccess.conservativeResearchSynthesis(
                        intent, "MISSION_RESULT_SCHEMA_INVALID", invalidModelResult));

        assertThat(root.path("directAnswer").asText()).isEqualTo("Integrated answer from all settled tasks");
        assertThat(root.path("sourceRefs").isEmpty()).isTrue();
        assertThat(root.path("completionKind").asText()).isEqualTo("COMPLETE");
    }

    private static ResearchBrief truthfulnessBrief() {
        return new ResearchBrief(
                "Which claims about Acme AI are true?",
                "real capability, technical origin, business model, and promotional exaggeration",
                "2026-01-01 through 2026-08-10",
                "Global",
                "A product decision maker",
                List.of("primary technical sources", "independent reproducible evaluations"),
                List.of("affiliate promotion", "unfetched search snippets"),
                "Markdown evidence report");
    }
}
