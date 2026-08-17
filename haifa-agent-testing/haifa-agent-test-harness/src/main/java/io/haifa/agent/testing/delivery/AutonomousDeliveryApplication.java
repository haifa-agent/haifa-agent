package io.haifa.agent.testing.delivery;

import io.haifa.agent.application.project.product.coding.client.CodingAgentClientFactory;
import io.haifa.agent.testing.harness.ResolvedRunContext;
import io.haifa.agent.testing.harness.RunEvidenceWriter;
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

    public RunEvidenceWriter.NativeResult run(
            ResolvedRunContext.AutonomousDelivery context,
            long approvedMaxCostMinorUnits,
            Map<String, Path> executablePaths)
            throws Exception {
        Objects.requireNonNull(context, "context must not be null");
        Map<String, Path> toolchains =
                Map.copyOf(Objects.requireNonNull(executablePaths, "executablePaths must not be null"));
        context.productRevision().requireClean("product repository");
        context.testConfigRevision().requireClean("test-config repository");
        DeliveryHostProfile host = DeliveryPlatformProfiles.requireCurrentHost(context.platform());
        List<Path> repositories = List.of(
                context.request().projectRoot(),
                context.request().projectRoot().resolve("docs"),
                context.request().configRoot());
        Path campaign = new AutonomousDeliveryCampaign()
                .initialize(
                        context.request().runRoot(),
                        repositories,
                        context.catalog(),
                        context.platformManifest(),
                        context.platform(),
                        context.agentProfile(),
                        context.productRevision(),
                        context.testConfigRevision());
        return new AutonomousDeliveryGateCoordinator(clock, clientFactory)
                .run(campaign, context, toolchains, host, approvedMaxCostMinorUnits);
    }
}
