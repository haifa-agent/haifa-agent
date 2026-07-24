package io.haifa.agent.runtime.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.haifa.agent.core.agent.AgentCapabilityRequirement;
import io.haifa.agent.core.agent.AgentDefinitionId;
import io.haifa.agent.core.agent.AgentDefinitionVersion;
import io.haifa.agent.core.checkpoint.CheckpointType;
import io.haifa.agent.core.reference.PrincipalRef;
import io.haifa.agent.core.reference.ProjectRef;
import io.haifa.agent.core.reference.TenantRef;
import io.haifa.agent.core.run.AgentRunBudget;
import io.haifa.agent.core.run.AgentRunLimits;
import io.haifa.agent.core.run.AgentRunType;
import io.haifa.agent.core.session.AgentSessionId;
import io.haifa.agent.runtime.api.AgentRunRequest;
import io.haifa.agent.runtime.api.RuntimeOverrides;
import io.haifa.agent.runtime.core.bootstrap.CapabilityResolutionErrorCode;
import io.haifa.agent.runtime.core.bootstrap.CapabilityResolutionException;
import io.haifa.agent.runtime.core.bootstrap.ContentAddressedSnapshotFactory;
import io.haifa.agent.runtime.core.bootstrap.DefaultCapabilityResolver;
import io.haifa.agent.runtime.core.bootstrap.ResolvedCapability;
import io.haifa.agent.runtime.core.bootstrap.ResolvedDefinition;
import io.haifa.agent.runtime.core.bootstrap.ResolvedProfile;
import io.haifa.agent.runtime.core.bootstrap.RunBootstrapper;
import io.haifa.agent.runtime.core.bootstrap.RuntimeCallerContext;
import io.haifa.agent.runtime.core.checkpoint.CheckpointSnapshotBuilder;
import io.haifa.agent.runtime.core.interaction.InMemoryInteractionPort;
import io.haifa.agent.runtime.core.skill.DefaultSkillActivationService;
import io.haifa.agent.runtime.core.storage.InMemoryRuntimeStore;
import io.haifa.agent.skill.api.SkillActivationRequest;
import io.haifa.agent.skill.api.SkillAlias;
import io.haifa.agent.skill.api.SkillAvailability;
import io.haifa.agent.skill.api.SkillDiscoveryContext;
import io.haifa.agent.skill.api.SkillOrigin;
import io.haifa.agent.skill.api.SkillParserMode;
import io.haifa.agent.skill.api.SkillResolutionPolicy;
import io.haifa.agent.skill.api.SkillScope;
import io.haifa.agent.skill.api.SkillScopeRef;
import io.haifa.agent.skill.api.SkillSourceDescriptor;
import io.haifa.agent.skill.api.SkillSourceRef;
import io.haifa.agent.skill.api.SkillVisibilityContext;
import io.haifa.agent.skill.base.BaseSkills;
import io.haifa.agent.skill.core.CompositeSkillContentLoader;
import io.haifa.agent.skill.core.InMemorySkillSource;
import io.haifa.agent.skill.core.SkillCatalogBuilder;
import io.haifa.agent.skill.core.SkillPackageLimits;
import io.haifa.agent.skill.core.SkillPackageParser;
import io.haifa.agent.tool.api.ToolCatalog;
import io.haifa.agent.tool.api.ToolCatalogSnapshot;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class BootstrapCapabilityTest {
    private static final AgentRunBudget BUDGET = new AgentRunBudget(1000, 1000, 1000, 10, 10, 2, "USD", 1000);
    private static final AgentRunLimits LIMITS = new AgentRunLimits(10, 2, 1, 60_000, 30_000);
    private static final RuntimeCallerContext CALLER =
            new RuntimeCallerContext(new TenantRef("tenant"), new PrincipalRef("principal", "user"));

    @Test
    void bootstrapFreezesAuthorizedWorkspaceCapabilityIntoConfigurationDigest() {
        var requirement = new AgentCapabilityRequirement("workspace.filesystem", "1.*", true);
        var definition = definition(List.of(requirement));
        var capability = new ResolvedCapability(
                "workspace.filesystem", "1.2.0", "workspace-binding-1", "sha256:capability", true);
        var profile = profile(Map.of(capability.capabilityId(), capability));
        AtomicInteger ids = new AtomicInteger();
        RunBootstrapper bootstrapper = new RunBootstrapper(
                (id, version) -> definition,
                (id, overrides) -> profile,
                (caller, session, project) -> {},
                new ContentAddressedSnapshotFactory(),
                () -> "generated-" + ids.incrementAndGet(),
                () -> Instant.parse("2026-07-21T00:00:00Z"));

        var first = bootstrapper.bootstrap(request(true), CALLER);
        var secondCapability = new ResolvedCapability(
                "workspace.filesystem", "1.2.0", "workspace-binding-2", "sha256:capability", true);
        var secondCapabilities = new DefaultCapabilityResolver()
                .resolve(request(true), definition, profile(Map.of(secondCapability.capabilityId(), secondCapability)));
        var secondSnapshot = new ContentAddressedSnapshotFactory()
                .create(
                        request(true),
                        definition,
                        profile(Map.of(secondCapability.capabilityId(), secondCapability)),
                        CALLER,
                        secondCapabilities);

        assertThat(first.configuration().capabilities()).singleElement().satisfies(value -> {
            assertThat(value.capabilityId()).isEqualTo("workspace.filesystem");
            assertThat(value.bindingRef()).isEqualTo("workspace-binding-1");
        });
        assertThat(first.configuration().reference()).isNotEqualTo(secondSnapshot.reference());
    }

    @Test
    void requiredCapabilityFailuresAreTypedAndOptionalAbsenceCreatesNoNoopCapability() {
        var required = definition(List.of(new AgentCapabilityRequirement("workspace.filesystem", "1.0.0", true)));
        var optional = definition(List.of(new AgentCapabilityRequirement("workspace.filesystem", "1.0.0", false)));
        DefaultCapabilityResolver resolver = new DefaultCapabilityResolver();

        assertThatThrownBy(() -> resolver.resolve(request(true), required, profile(Map.of())))
                .isInstanceOfSatisfying(CapabilityResolutionException.class, exception -> assertThat(exception.code())
                        .isEqualTo(CapabilityResolutionErrorCode.MISSING_REQUIRED_CAPABILITY));
        assertThat(resolver.resolve(request(false), optional, profile(Map.of())))
                .isEmpty();
    }

    @Test
    void rejectsUnauthorizedIncompatibleAndUnboundRequiredCapabilities() {
        DefaultCapabilityResolver resolver = new DefaultCapabilityResolver();
        var definition = definition(List.of(new AgentCapabilityRequirement("workspace.filesystem", "2.0.0", true)));

        assertCode(
                resolver,
                definition,
                new ResolvedCapability("workspace.filesystem", "2.0.0", "binding", "sha256:x", false),
                true,
                CapabilityResolutionErrorCode.UNAUTHORIZED);
        assertCode(
                resolver,
                definition,
                new ResolvedCapability("workspace.filesystem", "1.0.0", "binding", "sha256:x", true),
                true,
                CapabilityResolutionErrorCode.VERSION_INCOMPATIBLE);
        assertCode(
                resolver,
                definition,
                new ResolvedCapability("workspace.filesystem", "2.0.0", "binding", "sha256:x", true),
                false,
                CapabilityResolutionErrorCode.BINDING_UNAVAILABLE);
    }

    @Test
    void ordinaryChatHasNoProjectWorkspaceOrEffectiveCapabilities() {
        var definition = definition(List.of());
        var profile = profile(Map.of());
        var capabilities = new DefaultCapabilityResolver().resolve(request(false), definition, profile);
        var snapshot =
                new ContentAddressedSnapshotFactory().create(request(false), definition, profile, CALLER, capabilities);

        assertThat(snapshot.capabilities()).isEmpty();
        assertThat(request(false).project()).isEmpty();
    }

    @Test
    void runConfigurationFreezesExactToolBindingAndCatalogChangesOnlyAffectNewSnapshots() {
        var firstBinding = TestToolPlatform.binding("read", "1.0.0", "read.input", false);
        var changedBinding = TestToolPlatform.binding("read", "1.0.0", "read.input", true);
        var toolDefinition = new ResolvedDefinition(
                new AgentDefinitionId("agent"),
                new AgentDefinitionVersion(1, 0, 0),
                Set.of("read"),
                Set.of(),
                "Complete the objective.");

        var first = new ContentAddressedSnapshotFactory(
                        new ToolCatalogSnapshot(firstBinding.catalogDigest(), List.of(firstBinding)))
                .create(request(false), toolDefinition, profile(Map.of()), CALLER, List.of());
        var changed = new ContentAddressedSnapshotFactory(
                        new ToolCatalogSnapshot(changedBinding.catalogDigest(), List.of(changedBinding)))
                .create(request(false), toolDefinition, profile(Map.of()), CALLER, List.of());

        assertThat(first.toolBindings()).containsExactly(firstBinding);
        assertThat(first.toolBindings().getFirst().coordinate().definitionHash())
                .isNotEqualTo(changed.toolBindings().getFirst().coordinate().definitionHash());
        assertThat(first.reference()).isNotEqualTo(changed.reference());
        assertThat(first.toolBindings()).containsExactly(firstBinding);
    }

    @Test
    void skillBindingIsFrozenActivatedIdempotentlyAndUnavailableAliasesAreDenied() {
        var source = BaseSkills.source();
        var visibility = new SkillVisibilityContext(
                CALLER.tenant(), CALLER.principal(), Optional.empty(), false, Set.of(SkillScope.SDK));
        var catalog = new SkillCatalogBuilder(
                        List.of(source), new SkillResolutionPolicy("runtime-test@1", List.of(SkillScope.SDK), true))
                .build(new SkillDiscoveryContext(visibility));
        var loader = new CompositeSkillContentLoader(List.of(source));
        var definition = new ResolvedDefinition(
                new AgentDefinitionId("agent"),
                new AgentDefinitionVersion(1, 0, 0),
                Set.of(),
                Set.of("task-planning"),
                Set.of(),
                "Complete the objective.",
                List.of());
        var bootstrapper = new RunBootstrapper(
                (id, version) -> definition,
                (id, overrides) -> profile(Map.of()),
                (caller, session, project) -> {},
                new ContentAddressedSnapshotFactory(ToolCatalog.empty().snapshot(), catalog.snapshot()),
                () -> "skill-run",
                () -> Instant.parse("2026-07-21T00:00:00Z"));
        var bootstrap = bootstrapper.bootstrap(request(false), CALLER);
        var store = new InMemoryRuntimeStore();
        store.insert(bootstrap.run());
        store.saveConfiguration(bootstrap.configuration());
        var service =
                new DefaultSkillActivationService(store, store, loader, () -> Instant.parse("2026-07-21T00:00:01Z"));
        var activationRequest = new SkillActivationRequest(
                bootstrap.run().id(),
                CALLER.tenant(),
                CALLER.principal(),
                new SkillAlias("task-planning"),
                "the task has dependent stages",
                "test");

        var first = service.activate(activationRequest);
        var second = service.activate(activationRequest);

        assertThat(bootstrap.configuration().skillBindings()).hasSize(1);
        assertThat(bootstrap.configuration().skillCatalogDigest())
                .isEqualTo(catalog.snapshot().digest());
        assertThat(first).isEqualTo(second);
        assertThat(service.content(activationRequest).instructions()).contains("# Task planning");
        assertThat(store.skillActivations(bootstrap.run().id())).containsExactly(first);
        var checkpoint = new CheckpointSnapshotBuilder(
                        () -> "skill-checkpoint",
                        () -> Instant.parse("2026-07-21T00:00:02Z"),
                        store,
                        store,
                        new InMemoryInteractionPort())
                .build(bootstrap.run(), 1, List.of(), 0, CheckpointType.AUTOMATIC, 1);
        assertThat(checkpoint.state().skillActivations()).singleElement().satisfies(reference -> {
            assertThat(reference.alias()).isEqualTo(first.binding().alias());
            assertThat(reference.coordinate()).isEqualTo(first.binding().coordinate());
            assertThat(reference.registrationDigest()).isEqualTo(first.binding().registrationDigest());
        });
        assertThatThrownBy(() -> service.activate(new SkillActivationRequest(
                        bootstrap.run().id(),
                        CALLER.tenant(),
                        CALLER.principal(),
                        new SkillAlias("result-verification"),
                        "not frozen",
                        "test")))
                .isInstanceOf(SecurityException.class);
    }

    @Test
    void skillResourcesRequireActivationIndexAuthorizationAndRunBudget() {
        String chunk = "x".repeat(400 * 1024);
        var descriptor = new SkillSourceDescriptor(
                new SkillSourceRef("resource-fixture", "1"),
                SkillScopeRef.sdk(),
                SkillOrigin.BUNDLED,
                0,
                SkillParserMode.STRICT,
                true,
                false);
        var source = new InMemorySkillSource(
                descriptor,
                new SkillPackageParser(SkillPackageLimits.defaults()),
                SkillAvailability.ENABLED,
                Map.of(
                        "resource-skill",
                        Map.of(
                                "SKILL.md",
                                """
                                ---
                                name: resource-skill
                                description: Reads indexed resources under a bounded run budget.
                                ---
                                Load only the resource needed for the current step.
                                """
                                        .getBytes(StandardCharsets.UTF_8),
                                "references/a.txt",
                                chunk.getBytes(StandardCharsets.UTF_8),
                                "references/b.txt",
                                chunk.getBytes(StandardCharsets.UTF_8),
                                "references/c.txt",
                                chunk.getBytes(StandardCharsets.UTF_8))));
        var visibility = new SkillVisibilityContext(
                CALLER.tenant(), CALLER.principal(), Optional.empty(), false, Set.of(SkillScope.SDK));
        var catalog = new SkillCatalogBuilder(
                        List.of(source), new SkillResolutionPolicy("resource-test@1", List.of(SkillScope.SDK), true))
                .build(new SkillDiscoveryContext(visibility));
        var loader = new CompositeSkillContentLoader(List.of(source));
        var definition = new ResolvedDefinition(
                new AgentDefinitionId("agent"),
                new AgentDefinitionVersion(1, 0, 0),
                Set.of(),
                Set.of("resource-skill"),
                Set.of(),
                "Complete the objective.",
                List.of());
        var bootstrap = new RunBootstrapper(
                        (id, version) -> definition,
                        (id, overrides) -> profile(Map.of()),
                        (caller, session, project) -> {},
                        new ContentAddressedSnapshotFactory(ToolCatalog.empty().snapshot(), catalog.snapshot()),
                        () -> "resource-run",
                        () -> Instant.parse("2026-07-21T00:00:00Z"))
                .bootstrap(request(false), CALLER);
        var store = new InMemoryRuntimeStore();
        store.insert(bootstrap.run());
        store.saveConfiguration(bootstrap.configuration());
        var service =
                new DefaultSkillActivationService(store, store, loader, () -> Instant.parse("2026-07-21T00:00:01Z"));
        var activationRequest = new SkillActivationRequest(
                bootstrap.run().id(),
                CALLER.tenant(),
                CALLER.principal(),
                new SkillAlias("resource-skill"),
                "read a supporting reference",
                "test");

        assertThatThrownBy(() -> service.readResource(activationRequest, "references/a.txt"))
                .isInstanceOf(SecurityException.class)
                .hasMessageContaining("activated");
        service.activate(activationRequest);
        assertThatThrownBy(() -> service.readResource(activationRequest, "references/missing.txt"))
                .isInstanceOf(SecurityException.class)
                .hasMessageContaining("frozen index");
        for (String path : List.of(
                "references/a.txt", "references/b.txt", "references/c.txt", "references/a.txt", "references/b.txt")) {
            assertThat(service.readResource(activationRequest, path).content()).hasSize(400 * 1024);
        }
        assertThatThrownBy(() -> service.readResource(activationRequest, "references/c.txt"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("budget");
        assertThatThrownBy(() -> service.activate(new SkillActivationRequest(
                        bootstrap.run().id(),
                        new TenantRef("other-tenant"),
                        CALLER.principal(),
                        new SkillAlias("resource-skill"),
                        "cross tenant",
                        "test")))
                .isInstanceOf(SecurityException.class);
    }

    private static void assertCode(
            DefaultCapabilityResolver resolver,
            ResolvedDefinition definition,
            ResolvedCapability capability,
            boolean withProject,
            CapabilityResolutionErrorCode code) {
        assertThatThrownBy(() -> resolver.resolve(
                        request(withProject), definition, profile(Map.of(capability.capabilityId(), capability))))
                .isInstanceOfSatisfying(CapabilityResolutionException.class, exception -> assertThat(exception.code())
                        .isEqualTo(code));
    }

    private static ResolvedDefinition definition(List<AgentCapabilityRequirement> requirements) {
        return new ResolvedDefinition(
                new AgentDefinitionId("agent"),
                new AgentDefinitionVersion(1, 0, 0),
                Set.of(),
                Set.of(),
                "Complete the objective.",
                requirements);
    }

    private static ResolvedProfile profile(Map<String, ResolvedCapability> capabilities) {
        return new ResolvedProfile(
                "profile",
                "1.0.0",
                AgentRunType.CHAT,
                BUDGET,
                LIMITS,
                io.haifa.agent.runtime.core.bootstrap.DefaultResolvedModelSnapshots.deepSeekV4Pro(),
                capabilities);
    }

    private static AgentRunRequest request(boolean withProject) {
        return new AgentRunRequest(
                "request-1",
                new AgentDefinitionId("agent"),
                Optional.empty(),
                "profile",
                new AgentSessionId("session"),
                withProject ? Optional.of(new ProjectRef("project")) : Optional.empty(),
                "objective",
                List.of(),
                RuntimeOverrides.NONE);
    }
}
