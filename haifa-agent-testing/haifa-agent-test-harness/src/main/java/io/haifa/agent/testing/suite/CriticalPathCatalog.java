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
                        ":haifa-agent-e2e-tests",
                        "CriticalPathClientLiveE2E#completesAgentBaselineTurn"),
                e2e(
                        "CP-02",
                        "Single-file boundary defect repair",
                        ":haifa-agent-e2e-tests",
                        "CodingAgentLiveE2E#repairsSingleFileBoundaryDefect"),
                e2e(
                        "CP-03",
                        "Multi-file feature implementation",
                        ":haifa-agent-e2e-tests",
                        "CodingAgentLiveE2E#implementsMultiFileDiscountFeature"),
                e2e(
                        "CP-04",
                        "Execution failure diagnosis and recovery",
                        ":haifa-agent-e2e-tests",
                        "CodingAgentLiveE2E#diagnosesFailedExecutionAndRecovers"),
                e2e(
                        "CP-05",
                        "Dirty workspace preservation",
                        ":haifa-agent-e2e-tests",
                        "CodingAgentLiveE2E#preservesUnrelatedDirtyWorkspaceContent"),
                e2e(
                        "CP-06",
                        "Rejected approval has no side effect",
                        ":haifa-agent-e2e-tests",
                        "CodingAgentLiveE2E#rejectedApprovalProducesNoSideEffect"),
                e2e(
                        "CP-07",
                        "Reviewed Skill activation",
                        ":haifa-agent-e2e-tests",
                        "CriticalPathClientLiveE2E#activatesReviewedSkill"),
                e2e(
                        "CP-08",
                        "Web search followed by fetch",
                        ":haifa-agent-e2e-tests",
                        "CriticalPathClientLiveE2E#searchesAndFetchesPublicWebContent"),
                e2e(
                        "CP-09",
                        "Coding Agent discovers and calls Utility MCP",
                        ":haifa-agent-e2e-tests",
                        "CriticalPathClientLiveE2E#discoversAndCallsUtilityMcp"),
                e2e(
                        "CP-10",
                        "SQLite authority and JSONL projection",
                        ":haifa-agent-e2e-tests",
                        "CriticalPathClientLiveE2E#persistsRunToSqliteAndJsonl"),
                e2e(
                        "CP-11",
                        "Interaction, event journal, and HITL round trip",
                        ":haifa-agent-e2e-tests",
                        "InteractionEventHitlLiveE2E#completesInteractionEventAndHitlRoundTrip"));
        LinkedHashMap<String, CriticalPathCase> result = new LinkedHashMap<>();
        for (CriticalPathCase value : cases) {
            if (result.put(value.caseId(), value) != null) {
                throw new IllegalStateException("duplicate critical-path case: " + value.caseId());
            }
        }
        return Collections.unmodifiableMap(result);
    }

    private static CriticalPathCase live(String id, String title, String module, String selector) {
        return new CriticalPathCase(id, title, CriticalPathCase.TestScope.LIVE, module, selector, true);
    }

    private static CriticalPathCase e2e(String id, String title, String module, String selector) {
        return new CriticalPathCase(id, title, CriticalPathCase.TestScope.E2E, module, selector, true);
    }
}
