package io.haifa.agent.personalassistant.application.mission;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.haifa.agent.core.reference.PrincipalRef;
import io.haifa.agent.core.reference.TenantRef;
import io.haifa.agent.personalassistant.application.skill.PersonalSkillPlatform;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class SdkMissionRuntimeAccessTest {
    @Test
    void plannerDelegatesFinalIntegrationToTheDedicatedSynthesisStage() {
        var request = new MissionPlanner.PlanningRequest(
                "mission-1",
                "Research the topic",
                List.of("Cite sources"),
                MissionConstraints.DEFAULT,
                1,
                MissionMode.DEEP_RESEARCH,
                Optional.of(new ResearchBrief(
                        "Research the topic",
                        "scope",
                        "all time",
                        "region",
                        "audience",
                        List.of("primary sources"),
                        List.of("unsourced claims"),
                        "Markdown")));

        assertThat(SdkMissionRuntimeAccess.plannerPrompt(request))
                .contains("smallest feasible DAG", "normally 3 to 5 Tasks", "closely related indicators")
                .contains(
                        "every dependency must exactly equal the taskId of an earlier object",
                        "use ordinal placeholders such as task-1 or task-2")
                .contains("Do not create a final integration, synthesis, report-writing, or delivery Task")
                .contains("Never create a separate cross-Task evidence-checking, verification, or consolidation Task")
                .contains("Mission Synthesis", "stage assembles");
    }

    @Test
    void researchTaskPreloadsTheSelectedSkillWhileKeepingOnlyResearchToolsInTheRun() {
        var tenant = new TenantRef("local");
        var principal = new PrincipalRef("personal-user", "user");
        var skill = PersonalSkillPlatform.create(tenant, principal, Optional.empty(), List.of())
                .load("deep-research", tenant, principal);
        var intent = new MissionDispatchIntent(
                "outbox-1",
                "mission-1",
                "owner-1",
                "task-1",
                1,
                "dispatch-1",
                "sha256:" + "1".repeat(64),
                "Research the topic",
                List.of("Cite fetched sources"),
                "RESEARCH",
                List.of("deep-research"),
                "pa.research-task-result",
                "v1",
                Instant.parse("2026-08-09T00:00:00Z"));

        assertThat(SdkMissionRuntimeAccess.taskPrompt(intent, skill))
                .contains(
                        "The Deep Research Product Skill was explicitly selected and is preloaded below",
                        "references/research-method.md",
                        "schemas/research-task-result-v1.json",
                        "utility_wikipedia_search",
                        "pa.research-task-result/v1",
                        "at most 32 research Tool calls",
                        "HARD STOP: after 20 total Tool calls",
                        "maxCharacters to at most 20000")
                .doesNotContain("calling Skill discovery or Skill resource Tools.\n\n                    [missing]");
        assertThat(SdkMissionRuntimeAccess.TASK_RESEARCH_TOOL_CALL_TARGET).isEqualTo(20);

        assertThat(SdkMissionRuntimeAccess.taskNormalizationPrompt("research notes", skill))
                .contains(
                        "exactly one JSON object",
                        "pa.research-task-result/v1",
                        "Do not invent a source",
                        "research notes")
                .doesNotContain("web_search", "web_fetch");
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
        assertThat(root.path("sources").size()).isEqualTo(1);
        assertThat(root.path("sources").get(0).path("status").asText()).isEqualTo("UNKNOWN");
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
                List.of("Ecological transition: MODEL_CONTEXT_TOO_LONG"));

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
                List.of());
        String invalidModelResult =
                "{\"directAnswer\":\"Integrated answer from all settled tasks\",\"sourceRefs\":[\"invented\"]}";

        var root = new ObjectMapper()
                .readTree(SdkMissionRuntimeAccess.conservativeResearchSynthesis(
                        intent, "MISSION_RESULT_SCHEMA_INVALID", invalidModelResult));

        assertThat(root.path("directAnswer").asText()).isEqualTo("Integrated answer from all settled tasks");
        assertThat(root.path("sourceRefs").isEmpty()).isTrue();
        assertThat(root.path("completionKind").asText()).isEqualTo("COMPLETE");
    }
}
