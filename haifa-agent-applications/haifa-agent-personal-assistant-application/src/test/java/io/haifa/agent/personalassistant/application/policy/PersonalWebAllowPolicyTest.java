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
import io.haifa.agent.policy.api.PolicyAuthorizationEvidence;
import io.haifa.agent.policy.api.PolicyAuthorizationEvidenceStore;
import io.haifa.agent.policy.api.PolicyChallenge;
import io.haifa.agent.policy.api.PolicyDecision;
import io.haifa.agent.policy.api.PolicyDecisionId;
import io.haifa.agent.policy.api.PolicyDecisionStore;
import io.haifa.agent.policy.api.PolicyEffect;
import io.haifa.agent.policy.api.PolicySnapshot;
import io.haifa.agent.policy.api.PolicySnapshotRef;
import io.haifa.agent.policy.api.PolicySnapshotStore;
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
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class PersonalWebAllowPolicyTest {
    private static final Instant NOW = Instant.parse("2026-07-30T00:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final TenantRef TENANT = new TenantRef("local");
    private static final PrincipalRef PRINCIPAL = new PrincipalRef("personal-user", "user");

    @Test
    void directlyAllowsExactFrozenSearchAndFetchAndPersistsBoundDecisions() {
        Fixture fixture = fixture();
        AtomicInteger delegateCalls = new AtomicInteger();
        PublicToolPolicy policy = PersonalWebAllowPolicy.decorator(
                        fixture.catalog(), fixture.web(), fixture.persistence(), CLOCK)
                .apply((run, binding, request) -> {
                    delegateCalls.incrementAndGet();
                    return askDecision("delegate");
                });

        for (String alias : fixture.web().aliases()) {
            FrozenToolBinding binding =
                    fixture.catalog().findByAlias(new ToolAlias(alias)).orElseThrow();

            PolicyDecision decision = policy.evaluate(run(), binding, request(binding));

            assertThat(decision.effect()).isEqualTo(PolicyEffect.ALLOW);
            assertThat(decision.challenge()).isEmpty();
            assertThat(decision.reasonCode()).isEqualTo("PERSONAL_WEB_READ_ALLOWED");
            assertThat(decision.bound()).isTrue();
            assertThat(decision.request().orElseThrow().subject().productId()).isEqualTo("haifa-personal-assistant");
            assertThat(decision.request().orElseThrow().action().capability())
                    .isEqualTo(binding.definition().name().value());
            assertThat(fixture.store().find(decision.id())).contains(decision);
        }
        assertThat(delegateCalls).hasValue(0);
    }

    @Test
    void delegatesEveryNonWebToolWithoutChangingItsDecision() {
        Fixture fixture = fixture();
        FrozenToolBinding checklist =
                fixture.catalog().findByAlias(PersonalChecklistTool.ALIAS).orElseThrow();
        AtomicInteger delegateCalls = new AtomicInteger();
        PolicyDecision delegated = askDecision("checklist-decision");
        PublicToolPolicy policy = PersonalWebAllowPolicy.decorator(
                        fixture.catalog(), fixture.web(), fixture.persistence(), CLOCK)
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
                true,
                "test-secret",
                Duration.ofSeconds(5),
                1024 * 1024,
                2 * 1024 * 1024,
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
        var store = new TestPolicyStore();
        var metadata = new SdkContributionMetadata(
                new ProductContributionCoordinate("personal-policy-test", "1.0.0"),
                ProductCapabilities.POLICY,
                SdkConfigurationDigest.sha256("personal-policy-test"),
                ProductProviderSuitability.TEST_ONLY,
                "Personal policy test persistence");
        PolicyAuthorizationEvidenceStore evidence = new PolicyAuthorizationEvidenceStore() {
            @Override
            public void save(PolicyAuthorizationEvidence value) {}

            @Override
            public Optional<PolicyAuthorizationEvidence> find(PolicyDecisionId decisionId) {
                return Optional.empty();
            }
        };
        return new Fixture(web, catalog, store, new PolicyPlatformContribution(metadata, store, store, evidence));
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

    private static PolicyDecision askDecision(String id) {
        return new PolicyDecision(
                new PolicyDecisionId(id),
                PolicyEffect.ASK,
                Optional.of(PolicyChallenge.APPROVAL),
                "DELEGATED_APPROVAL",
                "Delegate decision",
                new PolicySnapshotRef("delegate"),
                Optional.empty(),
                NOW);
    }

    private record Fixture(
            PersonalWebPlatform web,
            io.haifa.agent.tool.api.ToolCatalog catalog,
            TestPolicyStore store,
            PolicyPlatformContribution persistence) {}

    private static final class TestPolicyStore implements PolicySnapshotStore, PolicyDecisionStore {
        private final Map<PolicySnapshotRef, PolicySnapshot> snapshots = new ConcurrentHashMap<>();
        private final Map<PolicyDecisionId, PolicyDecision> decisions = new ConcurrentHashMap<>();

        @Override
        public void save(PolicySnapshot snapshot) {
            snapshots.put(snapshot.ref(), snapshot);
        }

        @Override
        public Optional<PolicySnapshot> find(PolicySnapshotRef ref) {
            return Optional.ofNullable(snapshots.get(ref));
        }

        @Override
        public void save(PolicyDecision decision) {
            decisions.put(decision.id(), decision);
        }

        @Override
        public Optional<PolicyDecision> find(PolicyDecisionId id) {
            return Optional.ofNullable(decisions.get(id));
        }
    }
}
