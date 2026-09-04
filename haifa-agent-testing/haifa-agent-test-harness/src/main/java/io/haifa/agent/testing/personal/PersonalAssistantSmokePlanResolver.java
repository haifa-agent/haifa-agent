package io.haifa.agent.testing.personal;

import io.haifa.agent.testing.harness.PlatformManifest;
import io.haifa.agent.testing.harness.ResolvedTestPlan;
import io.haifa.agent.testing.repository.RepositoryRevision;
import io.haifa.agent.testing.suite.ResolvedAgentProfile;
import java.util.LinkedHashMap;

/** Freezes PA smoke inputs without making a provider call during plan creation. */
public final class PersonalAssistantSmokePlanResolver {
    private PersonalAssistantSmokePlanResolver() {}

    public static ResolvedTestPlan resolve(
            PersonalAssistantSmokeSuiteManifest manifest,
            PlatformManifest.PlatformProfile platform,
            ResolvedAgentProfile profile,
            RepositoryRevision productRevision,
            RepositoryRevision testConfigRevision) {
        LinkedHashMap<String, Object> inputs = new LinkedHashMap<>();
        inputs.put("suiteType", manifest.suiteType());
        inputs.put("suiteId", manifest.suiteId());
        inputs.put("suite", manifest);
        inputs.put("platform", platform);
        inputs.put("agentProfile", profile.manifest());
        inputs.put("agentAssemblyDigest", profile.agentAssemblyDigest());
        inputs.put("requiredEnvironmentNames", profile.requiredEnvironmentNames());
        inputs.put("credentialEnvironmentNames", profile.credentialEnvironmentNames());
        inputs.put(
                "catalogCases",
                manifest.cases().stream()
                        .map(selection -> PersonalAssistantSmokeCatalog.require(selection.caseId()))
                        .toList());
        inputs.put("productCommit", productRevision.commit());
        inputs.put("testConfigCommit", testConfigRevision.commit());
        return ResolvedTestPlan.freeze(inputs);
    }
}
