package io.haifa.agent.testing.harness;

import io.haifa.agent.testing.delivery.AutonomousDeliveryCaseCatalog;
import io.haifa.agent.testing.delivery.AutonomousDeliverySuiteManifest;
import io.haifa.agent.testing.fixtures.FixturePackageCatalog;
import io.haifa.agent.testing.personal.PersonalAssistantSmokeSuiteManifest;
import io.haifa.agent.testing.repository.RepositoryRevision;
import io.haifa.agent.testing.suite.ResolvedAgentProfile;
import io.haifa.agent.testing.suite.SuiteManifest;

/** One verified, immutable set of inputs shared by a native suite execution. */
public sealed interface ResolvedRunContext
        permits ResolvedRunContext.CriticalPath, ResolvedRunContext.AutonomousDelivery, ResolvedRunContext.PersonalAssistantSmoke {
    ExecutionPlanDocument approvedDocument();

    default TestRunRequest request() {
        return approvedDocument().toRunRequest();
    }

    ResolvedAgentProfile agentProfile();

    PlatformManifest.PlatformProfile platform();

    RepositoryRevision productRevision();

    RepositoryRevision testConfigRevision();

    default ResolvedTestPlan nativePlan() {
        return approvedDocument().nativePlan();
    }

    record CriticalPath(
            ExecutionPlanDocument approvedDocument,
            SuiteManifest suite,
            ResolvedAgentProfile agentProfile,
            PlatformManifest.PlatformProfile platform,
            RepositoryRevision productRevision,
            RepositoryRevision testConfigRevision)
            implements ResolvedRunContext {}

    record AutonomousDelivery(
            ExecutionPlanDocument approvedDocument,
            AutonomousDeliveryCaseCatalog catalog,
            AutonomousDeliverySuiteManifest suite,
            ResolvedAgentProfile agentProfile,
            PlatformManifest platformManifest,
            PlatformManifest.PlatformProfile platform,
            FixturePackageCatalog.PackageDescriptor fixturePackage,
            RepositoryRevision productRevision,
            RepositoryRevision testConfigRevision)
            implements ResolvedRunContext {}

    record PersonalAssistantSmoke(
            ExecutionPlanDocument approvedDocument,
            PersonalAssistantSmokeSuiteManifest suite,
            ResolvedAgentProfile agentProfile,
            PlatformManifest.PlatformProfile platform,
            RepositoryRevision productRevision,
            RepositoryRevision testConfigRevision)
            implements ResolvedRunContext {}
}
