package io.haifa.agent.personalassistant.application.policy;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.haifa.agent.core.agent.AgentDefinitionId;
import io.haifa.agent.core.agent.AgentDefinitionVersion;
import io.haifa.agent.core.reference.PrincipalRef;
import io.haifa.agent.core.reference.ProjectRef;
import io.haifa.agent.core.reference.RunConfigurationSnapshotRef;
import io.haifa.agent.core.reference.TenantRef;
import io.haifa.agent.core.run.AgentRun;
import io.haifa.agent.core.run.AgentRunBudget;
import io.haifa.agent.core.run.AgentRunId;
import io.haifa.agent.core.run.AgentRunLimits;
import io.haifa.agent.core.run.AgentRunSpec;
import io.haifa.agent.core.run.AgentRunType;
import io.haifa.agent.core.session.AgentSessionId;
import io.haifa.agent.core.tool.ProviderToolCallCorrelationId;
import io.haifa.agent.core.tool.RuntimeIdempotencyKey;
import io.haifa.agent.core.tool.ToolArguments;
import io.haifa.agent.core.tool.ToolCallId;
import io.haifa.agent.personalassistant.application.tool.PersonalChecklistTool;
import io.haifa.agent.personalassistant.application.web.PersonalWebPlatform;
import io.haifa.agent.policy.api.PolicyChallenge;
import io.haifa.agent.policy.api.PolicyDecision;
import io.haifa.agent.policy.api.PolicyEffect;
import io.haifa.agent.policy.core.DefaultPolicyDecisionService;
import io.haifa.agent.runtime.core.decision.ToolRequest;
import io.haifa.agent.runtime.core.tool.PublicToolPolicy;
import io.haifa.agent.sdk.api.SdkConfigurationDigest;
import io.haifa.agent.sdk.contribution.PolicyPlatformContribution;
import io.haifa.agent.sdk.contribution.SdkContributionMetadata;
import io.haifa.agent.sdk.product.ProductCapabilities;
import io.haifa.agent.sdk.product.ProductContributionCoordinate;
import io.haifa.agent.sdk.product.ProductProviderSuitability;
import io.haifa.agent.tool.api.FrozenToolBinding;
import io.haifa.agent.tool.api.ToolAlias;
import io.haifa.agent.tool.core.ToolCatalogBuilder;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class PersonalWebAllowPolicyTest {
    private static final Instant NOW = Instant.parse("2026-07-30T00:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final TenantRef TENANT = new TenantRef("local");
    private static final PrincipalRef PRINCIPAL = new PrincipalRef("personal-user", "user");

    @Test
    void directlyAllowsExactFrozenSearchAndFetchWithoutPersistence() {
        Fixture fixture = fixture();
        AtomicInteger delegateCalls = new AtomicInteger();
        PublicToolPolicy policy = PersonalWebAllowPolicy.decorator(fixture.catalog(), fixture.web(), fixture.policy())
                .apply((run, binding, request) -> {
                    delegateCalls.incrementAndGet();
                    return askDecision();
                });

        for (String alias : fixture.web().aliases()) {
            FrozenToolBinding binding =
                    fixture.catalog().findByAlias(new ToolAlias(alias)).orElseThrow();

            PolicyDecision decision = policy.evaluate(run(), binding, request(binding));

            assertThat(decision.effect()).isEqualTo(PolicyEffect.ALLOW);
            assertThat(decision.challenge()).isEmpty();
            assertThat(decision.reasonCode()).isEqualTo("PERSONAL_WEB_READ_ALLOWED");
            assertThat(decision.requirementDigest()).startsWith("sha256:");
        }
        assertThat(delegateCalls).hasValue(0);
        assertThat(List.of(fixture.policy().getClass().getMethods()))
                .extracting(method -> method.getName())
                .doesNotContain("snapshots", "decisions", "authorizationEvidence", "approvalGrants", "projectTrusts");
    }

    @Test
    void delegatesEveryNonWebToolWithoutChangingItsDecision() {
        Fixture fixture = fixture();
        FrozenToolBinding checklist =
                fixture.catalog().findByAlias(PersonalChecklistTool.ALIAS).orElseThrow();
        AtomicInteger delegateCalls = new AtomicInteger();
        PolicyDecision delegated = askDecision();
        PublicToolPolicy policy = PersonalWebAllowPolicy.decorator(fixture.catalog(), fixture.web(), fixture.policy())
                .apply((run, binding, request) -> {
                    delegateCalls.incrementAndGet();
                    return delegated;
                });

        PolicyDecision actual = policy.evaluate(run(), checklist, request(checklist));

        assertThat(actual).isSameAs(delegated);
        assertThat(delegateCalls).hasValue(1);
    }

    private static Fixture fixture() {
        PersonalWebPlatform web = PersonalWebPlatform.create(
                TENANT,
                PRINCIPAL,
                new PersonalWebPlatform.ProviderConfiguration(
                        true,
                        "aliyun",
                        io.haifa.agent.web.provider.AliyunSearchProvider.DEFAULT_ENDPOINT,
                        "test-secret",
                        Duration.ofSeconds(5),
                        1024 * 1024),
                new PersonalWebPlatform.ProviderConfiguration(
                        true,
                        "browserless",
                        io.haifa.agent.web.provider.BrowserlessFetchProvider.DEFAULT_ENDPOINT,
                        "test-secret",
                        Duration.ofSeconds(5),
                        2 * 1024 * 1024),
                new ObjectMapper(),
                CLOCK);
        var builder = new ToolCatalogBuilder();
        var checklist = new PersonalChecklistTool();
        builder.register(
                PersonalChecklistTool.ALIAS, PersonalChecklistTool.definition(), "personal-checklist-v1", checklist);
        web.contributions()
                .forEach(item -> builder.register(
                        item.alias(), item.definition(), item.providerBindingReference(), item.provider()));
        var catalog = builder.freeze();
        var metadata = new SdkContributionMetadata(
                new ProductContributionCoordinate("personal-policy-test", "1.0.0"),
                ProductCapabilities.POLICY,
                SdkConfigurationDigest.sha256("personal-policy-test"),
                ProductProviderSuitability.TEST_ONLY,
                "Personal policy test");
        var policy = new PolicyPlatformContribution(
                metadata, PersonalAssistantPolicyRules.conservative(), new DefaultPolicyDecisionService());
        return new Fixture(web, catalog, policy);
    }

    private static AgentRun run() {
        return AgentRun.createRoot(
                new AgentRunId("run-1"),
                new AgentRunSpec(
                        new AgentSessionId("session-1"),
                        new ProjectRef("personal"),
                        TENANT,
                        PRINCIPAL,
                        new AgentDefinitionId("personal-assistant"),
                        new AgentDefinitionVersion(1, 0, 0),
                        "haifa-personal-assistant",
                        "1",
                        AgentRunType.CHAT,
                        "test",
                        new AgentRunBudget(100, 100, 100, 10, 10, 0, "USD", 0),
                        new AgentRunLimits(10, 0, 1, 60_000, 10_000),
                        new RunConfigurationSnapshotRef("personal-test", "sha256:" + "a".repeat(64))),
                NOW);
    }

    private static ToolRequest request(FrozenToolBinding binding) {
        return new ToolRequest(
                new ToolCallId("call-" + binding.alias().value()),
                new ProviderToolCallCorrelationId("provider-" + binding.alias().value()),
                new RuntimeIdempotencyKey("idempotency-" + binding.alias().value()),
                binding.alias().value(),
                binding.definition().version().value(),
                new ToolArguments(
                        binding.definition().inputSchema().id(),
                        binding.definition().inputSchema().version(),
                        Map.of()));
    }

    private static PolicyDecision askDecision() {
        return new PolicyDecision(
                PolicyEffect.ASK,
                Optional.of(PolicyChallenge.APPROVAL),
                "DELEGATED_APPROVAL",
                "Delegate decision",
                "sha256:delegated-requirement");
    }

    private record Fixture(
            PersonalWebPlatform web, io.haifa.agent.tool.api.ToolCatalog catalog, PolicyPlatformContribution policy) {}
}
