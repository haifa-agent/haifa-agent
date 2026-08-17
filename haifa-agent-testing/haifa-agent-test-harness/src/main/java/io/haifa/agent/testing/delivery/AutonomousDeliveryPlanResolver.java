package io.haifa.agent.testing.delivery;

import io.haifa.agent.testing.harness.PlatformManifest;
import io.haifa.agent.testing.harness.ResolvedTestPlan;
import io.haifa.agent.testing.repository.RepositoryRevision;
import io.haifa.agent.testing.suite.ResolvedAgentProfile;
import java.util.LinkedHashMap;

/** Builds Autonomous Delivery reviewed inputs for the shared resolved plan. */
public final class AutonomousDeliveryPlanResolver {
    private AutonomousDeliveryPlanResolver() {}

    public static ResolvedTestPlan resolve(
            AutonomousDeliveryCaseCatalog catalog,
            AutonomousDeliverySuiteManifest suite,
            PlatformManifest matrix,
            PlatformManifest.PlatformProfile combination,
            ResolvedAgentProfile agentProfile,
            RepositoryRevision productRevision,
            RepositoryRevision testConfigRevision,
            String fixturePackageSha256) {
        LinkedHashMap<String, Object> inputs = new LinkedHashMap<>();
        inputs.put("suiteType", "autonomous-delivery");
        inputs.put("catalogId", catalog.catalogId());
        inputs.put("catalogVersion", catalog.catalogVersion());
        inputs.put("catalogSha256", catalog.catalogSha256());
        inputs.put("suiteId", suite.suiteId());
        inputs.put("phase", suite.phase());
        inputs.put("budget", suite.budget());
        inputs.put("fixture", suite.fixture());
        inputs.put("fixturePackageSha256", fixturePackageSha256);
        inputs.put(
                "cases",
                suite.cases().stream()
                        .map(selection -> new CaseEntry(
                                selection.caseId(),
                                catalog.require(selection.caseId()).caseVersion(),
                                selection.repetitions(),
                                selection.blocking()))
                        .toList());
        inputs.put("matrixId", matrix.matrixId());
        inputs.put("platform", combination);
        inputs.put("agentProfile", agentProfile.manifest());
        inputs.put("agentAssemblyDigest", agentProfile.agentAssemblyDigest());
        inputs.put("productCommit", productRevision.commit());
        inputs.put("testConfigCommit", testConfigRevision.commit());
        return ResolvedTestPlan.freeze(inputs);
    }

    private record CaseEntry(String caseId, String caseVersion, int repetitions, boolean blocking) {}
}
