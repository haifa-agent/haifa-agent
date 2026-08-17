package io.haifa.agent.testing.suite;

import io.haifa.agent.testing.harness.PlatformManifest;
import io.haifa.agent.testing.harness.ResolvedTestPlan;
import io.haifa.agent.testing.repository.RepositoryRevision;
import java.util.LinkedHashMap;

/** Builds Critical Path reviewed inputs for the shared resolved plan. */
public final class CriticalPathPlanResolver {
    private CriticalPathPlanResolver() {}

    public static ResolvedTestPlan resolve(
            SuiteManifest manifest,
            PlatformManifest.PlatformProfile platform,
            ResolvedAgentProfile agentProfile,
            RepositoryRevision productRevision,
            RepositoryRevision testConfigRevision) {
        LinkedHashMap<String, Object> inputs = new LinkedHashMap<>();
        inputs.put("suiteType", "critical-path");
        inputs.put("suiteId", manifest.suiteId());
        inputs.put("suite", manifest);
        inputs.put("platform", platform);
        inputs.put("agentProfile", agentProfile.manifest());
        inputs.put("agentAssemblyDigest", agentProfile.agentAssemblyDigest());
        inputs.put("requiredEnvironmentNames", agentProfile.requiredEnvironmentNames());
        inputs.put("credentialEnvironmentNames", agentProfile.credentialEnvironmentNames());
        inputs.put(
                "catalogCases",
                manifest.cases().stream()
                        .map(selection -> CriticalPathCatalog.require(selection.caseId()))
                        .toList());
        inputs.put("productCommit", productRevision.commit());
        inputs.put("testConfigCommit", testConfigRevision.commit());
        return ResolvedTestPlan.freeze(inputs);
    }
}
