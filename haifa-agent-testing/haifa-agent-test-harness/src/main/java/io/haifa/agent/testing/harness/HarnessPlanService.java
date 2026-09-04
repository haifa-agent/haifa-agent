package io.haifa.agent.testing.harness;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import io.haifa.agent.testing.assets.TestingAssetPreflight;
import io.haifa.agent.testing.delivery.AutonomousDeliveryCaseCatalog;
import io.haifa.agent.testing.delivery.AutonomousDeliveryPlanResolver;
import io.haifa.agent.testing.delivery.AutonomousDeliverySuiteManifest;
import io.haifa.agent.testing.delivery.AutonomousDeliverySuiteManifestLoader;
import io.haifa.agent.testing.fixtures.FixturePackageCatalog;
import io.haifa.agent.testing.personal.PersonalAssistantSmokePlanResolver;
import io.haifa.agent.testing.personal.PersonalAssistantSmokeSuiteManifest;
import io.haifa.agent.testing.personal.PersonalAssistantSmokeSuiteManifestLoader;
import io.haifa.agent.testing.repository.RepositoryRevision;
import io.haifa.agent.testing.run.SafeRunRoot;
import io.haifa.agent.testing.suite.AgentProfileManifestLoader;
import io.haifa.agent.testing.suite.CriticalPathPlanResolver;
import io.haifa.agent.testing.suite.ResolvedAgentProfile;
import io.haifa.agent.testing.suite.SuiteManifest;
import io.haifa.agent.testing.suite.SuiteManifestLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/** Resolves every suite through one platform/profile/revision/plan kernel. */
public final class HarnessPlanService {
    private final ObjectMapper yaml = new ObjectMapper(new YAMLFactory());

    public ExecutionPlanDocument resolve(TestRunRequest request, RunnerArtifact runnerArtifact) throws Exception {
        return resolveContext(request, runnerArtifact, null).approvedDocument();
    }

    public ResolvedRunContext resolveAndVerify(ExecutionPlanDocument approved, RunnerArtifact currentRunner)
            throws Exception {
        approved.runnerArtifact().requireCurrent(currentRunner);
        ResolvedRunContext current = resolveContext(approved.toRunRequest(), currentRunner, approved.suiteType());
        if (!current.approvedDocument().plan().sha256().equals(approved.plan().sha256())) {
            throw new IllegalArgumentException("approved plan is stale for the current repositories or configuration");
        }
        return current;
    }

    private ResolvedRunContext resolveContext(
            TestRunRequest request, RunnerArtifact runnerArtifact, String expectedSuiteType) throws Exception {
        Path projectRoot = directory(request.projectRoot(), "project root");
        Path configRoot = directory(request.configRoot(), "test config root");
        Path runRoot = request.runRoot().toAbsolutePath().normalize();
        if (request.mode().requiresExternalRunRoot()) {
            SafeRunRoot.requireExternalLocation(
                    runRoot, List.of(projectRoot, projectRoot.resolve("docs"), configRoot), "test run root");
        }
        if (request.mode().requiresFullAssetInventory()) {
            new TestingAssetPreflight().validate(projectRoot, configRoot);
        }
        RepositoryRevision productRevision = RepositoryRevision.inspect(projectRoot);
        RepositoryRevision testConfigRevision = RepositoryRevision.inspect(configRoot);
        ResolvedAgentProfile profile = new AgentProfileManifestLoader().load(configRoot, request.agentProfileRef());
        productRevision.requireCompatibleBaseline(
                projectRoot, profile.manifest().compatibleAgentBaselineCommit(), "Agent Profile");
        if (!request.mode().atLeast(RunMode.LIVE)
                && !profile.credentialEnvironmentNames().isEmpty()) {
            throw new IllegalArgumentException("a credential-backed Agent Profile requires live or release mode");
        }
        String suiteType = (expectedSuiteType == null ? suiteType(configRoot, request.suiteRef()) : expectedSuiteType)
                .trim();
        TestRunRequest normalizedRequest = new TestRunRequest(
                projectRoot,
                configRoot,
                runRoot,
                request.suiteRef(),
                request.agentProfileRef(),
                request.platformRef(),
                request.mode());
        if (suiteType.equals("autonomous-delivery")) {
            AutonomousDeliveryCaseCatalog catalog = AutonomousDeliveryCaseCatalog.loadVerified();
            AutonomousDeliverySuiteManifest suite =
                    new AutonomousDeliverySuiteManifestLoader().load(configRoot, request.suiteRef(), catalog);
            PlatformManifest matrix = new PlatformManifestLoader().load(configRoot, suite.matrixRef());
            PlatformManifest.PlatformProfile platform = matrix.requireCombination(request.platformRef());
            platform.requireCurrentHost();
            FixturePackageCatalog.PackageDescriptor fixturePackage = new FixturePackageCatalog()
                    .require(
                            projectRoot.resolve(
                                    "haifa-agent-testing/haifa-agent-test-fixtures/src/main/resources/fixtures"),
                            suite.fixture());
            ResolvedTestPlan plan = AutonomousDeliveryPlanResolver.resolve(
                    catalog,
                    suite,
                    matrix,
                    platform,
                    profile,
                    productRevision,
                    testConfigRevision,
                    fixturePackage.sha256());
            ExecutionPlanDocument document = ExecutionPlanDocument.freeze(normalizedRequest, plan, runnerArtifact);
            return new ResolvedRunContext.AutonomousDelivery(
                    document,
                    catalog,
                    suite,
                    profile,
                    matrix,
                    platform,
                    fixturePackage,
                    productRevision,
                    testConfigRevision);
        }
        if (suiteType.equals("critical-path")) {
            SuiteManifest suite = new SuiteManifestLoader().load(configRoot, request.suiteRef());
            PlatformManifest matrix = new PlatformManifestLoader().load(configRoot, suite.matrixRef());
            PlatformManifest.PlatformProfile platform = matrix.requireCombination(request.platformRef());
            platform.requireCurrentHost();
            ResolvedTestPlan plan =
                    CriticalPathPlanResolver.resolve(suite, platform, profile, productRevision, testConfigRevision);
            ExecutionPlanDocument document = ExecutionPlanDocument.freeze(normalizedRequest, plan, runnerArtifact);
            return new ResolvedRunContext.CriticalPath(
                    document, suite, profile, platform, productRevision, testConfigRevision);
        }
        if (suiteType.equals("personal-assistant-smoke")) {
            PersonalAssistantSmokeSuiteManifest suite =
                    new PersonalAssistantSmokeSuiteManifestLoader().load(configRoot, request.suiteRef());
            PlatformManifest matrix = new PlatformManifestLoader().load(configRoot, suite.matrixRef());
            PlatformManifest.PlatformProfile platform = matrix.requireCombination(request.platformRef());
            platform.requireCurrentHost();
            ResolvedTestPlan plan = PersonalAssistantSmokePlanResolver.resolve(
                    suite, platform, profile, productRevision, testConfigRevision);
            ExecutionPlanDocument document = ExecutionPlanDocument.freeze(normalizedRequest, plan, runnerArtifact);
            return new ResolvedRunContext.PersonalAssistantSmoke(
                    document, suite, profile, platform, productRevision, testConfigRevision);
        }
        throw new IllegalArgumentException("unsupported suiteType: " + suiteType);
    }

    private String suiteType(Path configRoot, String suiteRef) throws Exception {
        Path suite = configRoot.resolve("suites").resolve(suiteRef + ".yaml").normalize();
        if (!suite.startsWith(configRoot) || !Files.isRegularFile(suite)) {
            throw new IllegalArgumentException("suite file is unavailable: " + suiteRef);
        }
        JsonNode root = yaml.readTree(suite.toFile());
        if (root.hasNonNull("suiteType")) return root.get("suiteType").asText();
        return root.hasNonNull("phase") ? "autonomous-delivery" : "critical-path";
    }

    private static Path directory(Path value, String field) {
        Path normalized = value.toAbsolutePath().normalize();
        if (!Files.isDirectory(normalized))
            throw new IllegalArgumentException(field + " must be an existing directory");
        return normalized;
    }
}
