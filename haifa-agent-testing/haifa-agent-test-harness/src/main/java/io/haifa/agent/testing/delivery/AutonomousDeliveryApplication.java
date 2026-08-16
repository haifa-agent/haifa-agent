package io.haifa.agent.testing.delivery;

import io.haifa.agent.application.project.product.coding.client.CodingAgentClientFactory;
import io.haifa.agent.testing.fixtures.FixturePackageCatalog;
import io.haifa.agent.testing.harness.PlatformManifest;
import io.haifa.agent.testing.harness.PlatformManifestLoader;
import io.haifa.agent.testing.harness.ResolvedTestPlan;
import io.haifa.agent.testing.repository.RepositoryRevision;
import io.haifa.agent.testing.suite.AgentProfileManifestLoader;
import io.haifa.agent.testing.suite.ResolvedAgentProfile;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Executes one Autonomous Delivery suite through the shared Harness run action. */
public final class AutonomousDeliveryApplication {
    private final Clock clock;
    private final CodingAgentClientFactory clientFactory;

    public AutonomousDeliveryApplication(CodingAgentClientFactory clientFactory) {
        this(Clock.systemUTC(), clientFactory);
    }

    AutonomousDeliveryApplication(Clock clock, CodingAgentClientFactory clientFactory) {
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
        this.clientFactory = Objects.requireNonNull(clientFactory, "clientFactory must not be null");
    }

    public Path run(Options options) throws Exception {
        AutonomousDeliveryCaseCatalog catalog = AutonomousDeliveryCaseCatalog.loadVerified();
        AutonomousDeliverySuiteManifest suite =
                new AutonomousDeliverySuiteManifestLoader().load(options.configRoot(), options.suiteId(), catalog);
        PlatformManifest platforms = new PlatformManifestLoader().load(options.configRoot(), suite.matrixRef());
        PlatformManifest.PlatformProfile platform = platforms.requireCombination(options.platformId());
        DeliveryHostProfile host = DeliveryPlatformProfiles.requireCurrentHost(platform);
        ResolvedAgentProfile profile =
                new AgentProfileManifestLoader().load(options.configRoot(), options.agentProfileId());
        RepositoryRevision product = RepositoryRevision.inspect(options.projectRoot());
        RepositoryRevision configuration = RepositoryRevision.inspect(options.configRoot());
        product.requireClean("product repository");
        configuration.requireClean("test-config repository");
        product.requireCompatibleBaseline(
                options.projectRoot(), profile.manifest().compatibleAgentBaselineCommit(), "Agent Profile");
        product.requireCommit(options.buildCommit(), "product repository");

        ResolvedTestPlan plan = AutonomousDeliveryPlanResolver.resolve(
                catalog,
                suite,
                platforms,
                platform,
                profile,
                product,
                configuration,
                new FixturePackageCatalog()
                        .require(
                                options.projectRoot()
                                        .resolve(
                                                "haifa-agent-testing/haifa-agent-test-fixtures/src/main/resources/fixtures"),
                                suite.fixture())
                        .sha256());
        plan.requireApproved(options.approvedPlanSha256());

        List<Path> repositories =
                List.of(options.projectRoot(), options.projectRoot().resolve("docs"), options.configRoot());
        Path campaign = new AutonomousDeliveryCampaign()
                .initialize(
                        options.runParent(),
                        repositories,
                        catalog,
                        platforms,
                        platform,
                        profile,
                        product,
                        configuration);
        Path gate = new AutonomousDeliveryGateCoordinator(clock, clientFactory)
                .run(
                        campaign,
                        options.buildCommit(),
                        suite,
                        catalog,
                        profile,
                        options.toolchains(),
                        host,
                        options.projectRoot(),
                        options.configRoot(),
                        platform,
                        product,
                        configuration,
                        plan,
                        options.approvedMaxCostMinorUnits());
        product.requireUnchanged(RepositoryRevision.inspect(options.projectRoot()), "product repository");
        configuration.requireUnchanged(RepositoryRevision.inspect(options.configRoot()), "test-config repository");
        return gate;
    }

    public record Options(
            Path projectRoot,
            Path configRoot,
            Path runParent,
            String buildCommit,
            String suiteId,
            String platformId,
            String agentProfileId,
            String approvedPlanSha256,
            long approvedMaxCostMinorUnits,
            Map<String, Path> toolchains) {
        public Options {
            toolchains = Map.copyOf(Objects.requireNonNull(toolchains, "toolchains must not be null"));
        }
    }
}
