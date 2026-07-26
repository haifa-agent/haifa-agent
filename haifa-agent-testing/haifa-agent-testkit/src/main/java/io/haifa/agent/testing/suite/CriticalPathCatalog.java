package io.haifa.agent.testing.suite;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Critical Path v1 catalog. Test implementation coordinates never live in the private config repository. */
public final class CriticalPathCatalog {
    private static final Map<String, CriticalPathCase> CASES = build();

    private CriticalPathCatalog() {}

    public static List<CriticalPathCase> cases() {
        return List.copyOf(CASES.values());
    }

    public static CriticalPathCase require(String caseId) {
        CriticalPathCase value = CASES.get(caseId);
        if (value == null) throw new IllegalArgumentException("unknown critical-path case: " + caseId);
        return value;
    }

    private static Map<String, CriticalPathCase> build() {
        List<CriticalPathCase> cases = List.of(
                live(
                        "CP-01",
                        "Primary model connectivity",
                        ":haifa-agent-live-tests",
                        "PrimaryModelLiveIT",
                        "DEEPSEEK_API_KEY"),
                e2e(
                        "CP-02",
                        "Single-file boundary defect repair",
                        ":haifa-agent-cli",
                        "CodingAgentLiveE2E#repairsSingleFileBoundaryDefect",
                        "DEEPSEEK_API_KEY"),
                e2e(
                        "CP-03",
                        "Multi-file feature implementation",
                        ":haifa-agent-cli",
                        "CodingAgentLiveE2E#implementsMultiFileDiscountFeature",
                        "DEEPSEEK_API_KEY"),
                e2e(
                        "CP-04",
                        "Execution failure diagnosis and recovery",
                        ":haifa-agent-cli",
                        "CodingAgentLiveE2E#diagnosesFailedExecutionAndRecovers",
                        "DEEPSEEK_API_KEY"),
                e2e(
                        "CP-05",
                        "Dirty workspace preservation",
                        ":haifa-agent-cli",
                        "CodingAgentLiveE2E#preservesUnrelatedDirtyWorkspaceContent",
                        "DEEPSEEK_API_KEY"),
                e2e(
                        "CP-06",
                        "Rejected approval has no side effect",
                        ":haifa-agent-cli",
                        "CodingAgentLiveE2E#rejectedApprovalProducesNoSideEffect",
                        "DEEPSEEK_API_KEY"),
                e2e(
                        "CP-07",
                        "Reviewed Skill activation",
                        ":haifa-agent-e2e-tests",
                        "CriticalPathLiveE2E#activatesReviewedSkill",
                        "DEEPSEEK_API_KEY"),
                new CriticalPathCase(
                        "CP-08",
                        "Web search followed by fetch",
                        CriticalPathCase.TestScope.E2E,
                        ":haifa-agent-e2e-tests",
                        "CriticalPathLiveE2E#searchesAndFetchesPublicWebContent",
                        true,
                        List.of("DEEPSEEK_API_KEY", "ALIYUN_IQS_API_KEY")),
                live(
                        "CP-09",
                        "Utility MCP protocol compatibility",
                        ":haifa-agent-mcp",
                        "UtilityMcpCompatibilityLiveIT"),
                new CriticalPathCase(
                        "CP-10",
                        "SQLite authority and JSONL projection",
                        CriticalPathCase.TestScope.E2E,
                        ":haifa-agent-e2e-tests",
                        "CriticalPathLiveE2E#persistsRunToSqliteAndJsonl",
                        true,
                        List.of("DEEPSEEK_API_KEY", "HAIFA_CONTINUATION_KEY")),
                new CriticalPathCase(
                        "CP-11",
                        "Interaction, event journal, and HITL round trip",
                        CriticalPathCase.TestScope.E2E,
                        ":haifa-agent-e2e-tests",
                        "InteractionEventHitlLiveE2E#completesInteractionEventAndHitlRoundTrip",
                        true,
                        List.of("DEEPSEEK_API_KEY", "ALIYUN_IQS_API_KEY", "HAIFA_CONTINUATION_KEY")));
        LinkedHashMap<String, CriticalPathCase> result = new LinkedHashMap<>();
        for (CriticalPathCase value : cases) {
            if (result.put(value.caseId(), value) != null) {
                throw new IllegalStateException("duplicate critical-path case: " + value.caseId());
            }
        }
        return Collections.unmodifiableMap(result);
    }

    private static CriticalPathCase live(
            String id, String title, String module, String selector, String... requiredSecrets) {
        return new CriticalPathCase(
                id, title, CriticalPathCase.TestScope.LIVE, module, selector, true, List.of(requiredSecrets));
    }

    private static CriticalPathCase e2e(
            String id, String title, String module, String selector, String... requiredSecrets) {
        return new CriticalPathCase(
                id, title, CriticalPathCase.TestScope.E2E, module, selector, true, List.of(requiredSecrets));
    }
}
