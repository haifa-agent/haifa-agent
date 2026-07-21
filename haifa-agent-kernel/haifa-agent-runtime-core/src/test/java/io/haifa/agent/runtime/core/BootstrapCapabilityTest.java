package io.haifa.agent.runtime.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.haifa.agent.core.agent.AgentCapabilityRequirement;
import io.haifa.agent.core.agent.AgentDefinitionId;
import io.haifa.agent.core.agent.AgentDefinitionVersion;
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
