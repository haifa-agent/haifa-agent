package io.haifa.agent.sdk.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.haifa.agent.core.reference.PrincipalRef;
import io.haifa.agent.core.reference.TenantRef;
import io.haifa.agent.core.run.AgentRun;
import io.haifa.agent.core.run.AgentRunId;
import io.haifa.agent.core.run.AgentRunLimits;
import io.haifa.agent.core.run.AgentRunSpec;
import io.haifa.agent.core.run.AgentRunType;
import io.haifa.agent.core.session.AgentSessionId;
import io.haifa.agent.core.tool.ProviderToolCallCorrelationId;
import io.haifa.agent.core.tool.RuntimeIdempotencyKey;
import io.haifa.agent.core.tool.ToolArguments;
import io.haifa.agent.core.tool.ToolCallId;
import io.haifa.agent.policy.api.ApprovalMode;
import io.haifa.agent.policy.api.PolicyContext;
import io.haifa.agent.policy.api.PolicyDecision;
import io.haifa.agent.policy.api.PolicyEffect;
import io.haifa.agent.policy.api.PolicyPresets;
import io.haifa.agent.policy.api.PolicyRequest;
import io.haifa.agent.policy.api.PolicyRequirementDigest;
import io.haifa.agent.policy.api.PolicyRuleSet;
import io.haifa.agent.policy.core.DefaultPolicyDecisionService;
import io.haifa.agent.runtime.api.AgentRunRequest;
import io.haifa.agent.runtime.api.RuntimeOverrides;
import io.haifa.agent.runtime.core.bootstrap.ConfigurationSnapshotFactory;
import io.haifa.agent.runtime.core.bootstrap.ContentAddressedSnapshotFactory;
import io.haifa.agent.runtime.core.bootstrap.EffectiveCapability;
import io.haifa.agent.runtime.core.bootstrap.ResolvedDefinition;
import io.haifa.agent.runtime.core.bootstrap.ResolvedProfile;
import io.haifa.agent.runtime.core.bootstrap.RunBootstrapper;
import io.haifa.agent.runtime.core.bootstrap.RuntimeCallerContext;
import io.haifa.agent.runtime.core.bootstrap.RuntimeConfigurationSnapshot;
import io.haifa.agent.runtime.core.decision.ToolRequest;
import io.haifa.agent.runtime.core.storage.InMemoryRuntimeStore;
import io.haifa.agent.runtime.core.tool.DefaultPublicToolPolicy;
import io.haifa.agent.runtime.core.tool.DefaultToolPolicyRequestAdapter;
import io.haifa.agent.runtime.core.tool.PublicToolPolicy;
import io.haifa.agent.sdk.SdkTestFixtures;
import io.haifa.agent.skill.api.FrozenSkillBinding;
import io.haifa.agent.skill.api.SkillAlias;
import io.haifa.agent.skill.api.SkillCatalogSnapshot;
import io.haifa.agent.skill.api.SkillContentDigest;
import io.haifa.agent.skill.api.SkillCoordinate;
import io.haifa.agent.skill.api.SkillMetadata;
import io.haifa.agent.skill.api.SkillName;
import io.haifa.agent.skill.api.SkillPackageIndex;
import io.haifa.agent.skill.api.SkillResourceKind;
import io.haifa.agent.skill.api.SkillResourceRef;
import io.haifa.agent.skill.api.SkillScopeRef;
import io.haifa.agent.skill.api.SkillSourceRef;
import io.haifa.agent.skill.api.SkillTrustSnapshot;
import io.haifa.agent.tool.api.FrozenToolBinding;
import io.haifa.agent.tool.api.ToolApprovalRequirement;
import io.haifa.agent.tool.api.ToolRisk;
import io.haifa.agent.tool.api.ToolSideEffect;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class NonInteractiveChildToolPolicyTest {
    private static final String PRODUCT = "child-policy";

    @Test
    void policyAlwaysAndReauthenticationAskAllUseTheSameChildDenialPath() {
        for (var approval : List.of(
                ToolApprovalRequirement.POLICY,
                ToolApprovalRequirement.ALWAYS,
                ToolApprovalRequirement.REAUTHENTICATE)) {
            var fixture = fixture(true, false, approval);
            var original = fixture.base().evaluate(fixture.child(), fixture.binding(), fixture.request());
            assertThat(original.effect()).isEqualTo(PolicyEffect.ASK);
            var policy = fixture.policy();
            assertThat(policy.evaluate(fixture.parent(), fixture.binding(), fixture.request()))
                    .isEqualTo(fixture.base().evaluate(fixture.parent(), fixture.binding(), fixture.request()));
            var actual = policy.evaluate(fixture.child(), fixture.binding(), fixture.request());
            assertThat(actual.effect()).isEqualTo(PolicyEffect.DENY);
            assertThat(actual.challenge()).isEmpty();
            assertThat(actual.reasonCode()).isEqualTo("CHILD_TOOL_APPROVAL_UNAVAILABLE");
            var adapted = new DefaultToolPolicyRequestAdapter(PRODUCT, ApprovalMode.ASK)
                    .adapt(fixture.child(), fixture.binding(), fixture.request());
            var context = adapted.context();
            var frozen = new PolicyRequest(
                    adapted.subject(),
                    new PolicyContext(
                            context.projectRef(),
                            context.sessionRef(),
                            context.runRef(),
                            context.attemptRef(),
                            context.approvalMode(),
                            Optional.of(fixture.child().configurationSnapshot().contentHash())),
                    adapted.action(),
                    adapted.resource(),
                    adapted.risk());
            assertThat(actual.requirementDigest())
                    .isEqualTo(PolicyRequirementDigest.compute(frozen, fixture.rules()))
                    .isNotEqualTo(original.requirementDigest());
        }
    }

    @Test
    void originalAllowAndDenyAreNotReinterpreted() {
        var fixture = fixture(true, true, ToolApprovalRequirement.POLICY);
        for (var effect : List.of(PolicyEffect.ALLOW, PolicyEffect.DENY)) {
            var original = new PolicyDecision(effect, Optional.empty(), "ORIGINAL", "Original", "sha256:original");
            var policy = new NonInteractiveChildToolPolicy(
                    (run, binding, request) -> original, new InMemoryRuntimeStore(), fixture.rules(), PRODUCT);
            assertThat(policy.evaluate(fixture.parent(), fixture.binding(), fixture.request()))
                    .isSameAs(original);
            assertThat(policy.evaluate(fixture.child(), fixture.binding(), fixture.request()))
                    .isSameAs(original);
        }
    }

    @Test
    void reassemblyCannotChangeFrozenParentsOrTheirLaterChildrenInEitherDirection() {
        for (boolean enabled : List.of(false, true)) {
            var fixture = fixture(enabled, !enabled, ToolApprovalRequirement.POLICY);
            var recreated =
                    new NonInteractiveChildToolPolicy(fixture.base(), fixture.store(), fixture.rules(), PRODUCT);
            assertThat(recreated
                            .evaluate(fixture.child(), fixture.binding(), fixture.request())
                            .effect())
                    .isEqualTo(enabled ? PolicyEffect.DENY : PolicyEffect.ASK);
            var parentConfiguration = fixture.store()
                    .configuration(fixture.parent().configurationSnapshot())
                    .orElseThrow();
            var childConfiguration = fixture.store()
                    .configuration(fixture.child().configurationSnapshot())
                    .orElseThrow();
            assertThat(childConfiguration.capabilities()).isEqualTo(parentConfiguration.capabilities());
            var other = fixture(!enabled, !enabled, ToolApprovalRequirement.POLICY);
            assertThat(parentConfiguration.reference())
                    .isNotEqualTo(other.parent().configurationSnapshot());
        }
    }

    @Test
    void parentAskDoesNotRequireAChildPolicySnapshot() {
        var fixture = fixture(false, false, ToolApprovalRequirement.POLICY);
        var original = fixture.base().evaluate(fixture.parent(), fixture.binding(), fixture.request());
        var policy = new NonInteractiveChildToolPolicy(
                (run, binding, request) -> original, new InMemoryRuntimeStore(), fixture.rules(), PRODUCT);
        assertThat(original.effect()).isEqualTo(PolicyEffect.ASK);
        assertThat(policy.evaluate(fixture.parent(), fixture.binding(), fixture.request()))
                .isSameAs(original);
    }

    @Test
    void unknownMarkerVersionOrMissingSnapshotFailsClosed() {
        var fixture = fixture(true, true, ToolApprovalRequirement.POLICY);
        var configuration = fixture.store()
                .configuration(fixture.child().configurationSnapshot())
                .orElseThrow();
        var marker = configuration.capabilities().getFirst();
        var unknown =
                new EffectiveCapability(marker.capabilityId(), "2", marker.bindingRef(), marker.configurationDigest());
        var profile = profile();
        var snapshot = new ContentAddressedSnapshotFactory()
                .create(request(), definition(Set.of()), profile, caller(), List.of(unknown));
        var parent = root(snapshot);
        fixture.store().saveConfiguration(snapshot);
        var malformedChild = AgentRun.createChild(
                new AgentRunId("malformed"),
                parent,
                io.haifa.agent.core.run.AgentInvocationMode.AGENT_AS_TOOL,
                spec(snapshot),
                Instant.EPOCH);
        assertThatThrownBy(() -> fixture.policy().evaluate(malformedChild, fixture.binding(), fixture.request()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("CHILD_TOOL_POLICY_CONFIGURATION_INVALID");
        var empty = new InMemoryRuntimeStore();
        var missing = new NonInteractiveChildToolPolicy(fixture.base(), empty, fixture.rules(), PRODUCT);
        assertThatThrownBy(() -> missing.evaluate(fixture.child(), fixture.binding(), fixture.request()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("CHILD_TOOL_POLICY_CONFIGURATION_INVALID");
    }

    @Test
    void factoryPreservesExistingInputsAndLegacyChildFactoryDelegation() {
        var store = new InMemoryRuntimeStore();
        var request = request();
        var profile = profile();
        var caller = caller();
        var tools = ToolAssembly.prepare(
                null,
                List.of(),
                List.of(SdkTestFixtures.toolRegistration(
                        "writer", ToolRisk.MEDIUM, ToolApprovalRequirement.POLICY, Set.of(ToolSideEffect.FILE_WRITE))));
        var digest = new SkillContentDigest("sha256:" + "b".repeat(64));
        var name = new SkillName("task-planning");
        var binding = new FrozenSkillBinding(
                new SkillAlias(name.value()),
                new SkillCoordinate(
                        SkillScopeRef.sdk(), new SkillSourceRef("fixture", "1"), name, Optional.empty(), digest),
                new SkillMetadata(
                        name, "Plan", Optional.empty(), Optional.empty(), Optional.empty(), Map.of(), Set.of()),
                new SkillPackageIndex(
                        digest,
                        List.of(new SkillResourceRef(
                                "SKILL.md", SkillResourceKind.INSTRUCTION, "text/markdown", digest, 1, true))),
                digest,
                digest,
                "factory-preservation@1");
        var skills = new SkillCatalogSnapshot(digest, "factory-preservation@1", List.of(binding), List.of());
        var trust = new SkillTrustSnapshot("sha256:" + "a".repeat(64), List.of(), List.of());
        var product = SdkTestFixtures.profile(PRODUCT);
        var definition = new ResolvedDefinition(
                product.definitionId(),
                product.definitionVersion(),
                Set.of("writer"),
                Set.of("task-planning"),
                Set.of(),
                "Test",
                List.of());
        var selected =
                new ContentAddressedSnapshotFactory(tools.platform().catalog().snapshot(), skills, trust);
        var existing = new EffectiveCapability("existing", "1", "binding", "sha256:existing");
        AtomicReference<List<EffectiveCapability>> received = new AtomicReference<>();
        ConfigurationSnapshotFactory legacy = (actualRequest, actualDefinition, actualProfile, actualCaller, caps) -> {
            assertThat(actualRequest).isSameAs(request);
            assertThat(actualDefinition).isSameAs(definition);
            assertThat(actualProfile).isSameAs(profile);
            assertThat(actualCaller).isSameAs(caller);
            received.set(caps);
            return selected.create(actualRequest, actualDefinition, actualProfile, actualCaller, caps);
        };
        var factory = NonInteractiveChildToolPolicy.snapshots(legacy, store, true);
        var parentSnapshot = factory.create(request, definition, profile, caller, List.of(existing));
        var original = selected.create(request, definition, profile, caller, List.of(existing));
        assertThat(parentSnapshot.toolBindings())
                .isEqualTo(original.toolBindings())
                .hasSize(1);
        assertThat(parentSnapshot.skillBindings())
                .isEqualTo(original.skillBindings())
                .hasSize(1);
        assertThat(parentSnapshot.skillCatalogDigest()).isEqualTo(original.skillCatalogDigest());
        assertThat(parentSnapshot.skillResolutionPolicyRef()).isEqualTo(original.skillResolutionPolicyRef());
        assertThat(parentSnapshot.skillTrust()).isEqualTo(original.skillTrust()).isEqualTo(trust);
        store.saveConfiguration(parentSnapshot);
        var childFactory = NonInteractiveChildToolPolicy.snapshots(legacy, store, false);
        var child =
                childFactory.createChild(root(parentSnapshot), request, definition, profile, caller, List.of(existing));
        assertThat(received.get()).contains(existing).hasSize(2);
        assertThat(child).isEqualTo(parentSnapshot);
        ConfigurationSnapshotFactory ignoringCapabilities =
                (r, d, p, c, caps) -> selected.create(r, d, p, c, List.of());
        assertThatThrownBy(() -> NonInteractiveChildToolPolicy.snapshots(ignoringCapabilities, store, true)
                        .create(request, definition, profile, caller, List.of(existing)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("CHILD_TOOL_POLICY_CONFIGURATION_INVALID");
    }

    private record Fixture(
            InMemoryRuntimeStore store,
            AgentRun parent,
            AgentRun child,
            FrozenToolBinding binding,
            ToolRequest request,
            PolicyRuleSet rules,
            PublicToolPolicy base) {
        NonInteractiveChildToolPolicy policy() {
            return new NonInteractiveChildToolPolicy(base, store, rules, PRODUCT);
        }
    }

    private static Fixture fixture(
            boolean parentEnabled, boolean reassembledEnabled, ToolApprovalRequirement approval) {
        var store = new InMemoryRuntimeStore();
        var prepared = ToolAssembly.prepare(
                null,
                List.of(),
                List.of(SdkTestFixtures.toolRegistration(
                        "writer", ToolRisk.MEDIUM, approval, Set.of(ToolSideEffect.FILE_WRITE))));
        var original = new ContentAddressedSnapshotFactory(
                prepared.platform().catalog().snapshot());
        var factory = NonInteractiveChildToolPolicy.snapshots(original, store, parentEnabled);
        var snapshot = factory.create(request(), definition(Set.of("writer")), profile(), caller(), List.of());
        store.saveConfiguration(snapshot);
        var parent = root(snapshot);
        var recreated = NonInteractiveChildToolPolicy.snapshots(original, store, reassembledEnabled);
        var bootstrapper = new RunBootstrapper(
                (id, version) -> definition(Set.of("writer")),
                (id, overrides) -> profile(),
                (who, session, project) -> {},
                recreated,
                () -> "unused",
                () -> Instant.EPOCH);
        var child = bootstrapper.bootstrapChild(
                parent, new AgentRunId("child"), request(), definition(Set.of("writer")), profile());
        store.saveConfiguration(child.configuration());
        var rules = PolicyPresets.standardApproval();
        var base = new DefaultPublicToolPolicy(
                new DefaultToolPolicyRequestAdapter(PRODUCT, ApprovalMode.ASK),
                new DefaultPolicyDecisionService(),
                rules);
        var toolRequest = new ToolRequest(
                new ToolCallId("call"),
                new ProviderToolCallCorrelationId("provider"),
                new RuntimeIdempotencyKey("key"),
                "writer",
                "1.0.0",
                new ToolArguments("writer.input", "1", Map.of()));
        return new Fixture(
                store,
                parent,
                child.run(),
                prepared.platform().catalog().snapshot().bindings().getFirst(),
                toolRequest,
                rules,
                base);
    }

    private static AgentRunRequest request() {
        var product = SdkTestFixtures.profile(PRODUCT);
        return new AgentRunRequest(
                "start",
                product.definitionId(),
                Optional.empty(),
                product.defaultRunProfile().id(),
                new AgentSessionId("session"),
                Optional.empty(),
                "Test",
                List.of(),
                RuntimeOverrides.NONE);
    }

    private static ResolvedDefinition definition(Set<String> tools) {
        var product = SdkTestFixtures.profile(PRODUCT);
        return new ResolvedDefinition(product.definitionId(), product.definitionVersion(), tools, Set.of(), "Test");
    }

    private static ResolvedProfile profile() {
        var product = SdkTestFixtures.profile(PRODUCT);
        return new ResolvedProfile(
                product.defaultRunProfile().id(),
                "1.0.0",
                AgentRunType.CHAT,
                product.budget(),
                new AgentRunLimits(8, 1, 1, 30_000, 30_000),
                SdkTestFixtures.snapshot());
    }

    private static RuntimeCallerContext caller() {
        return new RuntimeCallerContext(new TenantRef("tenant"), new PrincipalRef("principal", "human"));
    }

    private static AgentRun root(RuntimeConfigurationSnapshot snapshot) {
        return AgentRun.createRoot(new AgentRunId("parent"), spec(snapshot), Instant.EPOCH);
    }

    private static AgentRunSpec spec(RuntimeConfigurationSnapshot snapshot) {
        return new AgentRunSpec(
                new AgentSessionId("session"),
                null,
                caller().tenant(),
                caller().principal(),
                snapshot.definitionId(),
                snapshot.definitionVersion(),
                snapshot.profileId(),
                snapshot.profileVersion(),
                snapshot.runType(),
                "Test",
                snapshot.budget(),
                snapshot.limits(),
                snapshot.reference());
    }
}
