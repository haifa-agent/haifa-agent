package io.haifa.agent.sdk.contribution;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.haifa.agent.common.time.SystemTimeProvider;
import io.haifa.agent.sdk.api.HaifaAgentException;
import io.haifa.agent.sdk.api.HaifaAgents;
import io.haifa.agent.sdk.internal.InMemoryPersistenceContribution;
import io.haifa.agent.sdk.internal.ToolAssembly;
import io.haifa.agent.skill.api.SkillContentLoader;
import java.util.List;
import org.junit.jupiter.api.Test;

class SkillToolContributionsTest {

    @Test
    void registersBothSkillToolsThroughThePublicBuilderWithoutChangingTheirIdentity() {
        var persistence = new InMemoryPersistenceContribution();
        var loader = SkillContentLoader.empty();
        var time = new SystemTimeProvider();

        List<ToolRegistration> registrations = SkillToolContributions.createRegistrations(persistence, loader, time);

        assertThat(registrations)
                .extracting(registration -> registration.alias().value())
                .containsExactly("skill_load", "skill_resource_read");
        assertThat(registrations)
                .extracting(ToolRegistration::providerBindingReference)
                .containsExactly("runtime-skill-load", "runtime-skill-resource-read");
        assertThat(registrations.get(0).provider())
                .isSameAs(registrations.get(1).provider());
        for (ToolRegistration registration : registrations) {
            assertThat(registration.definition().name().value())
                    .isEqualTo(registration.alias().value());
            assertThat(registration.definition().providerId())
                    .isEqualTo(registration.provider().id());
        }
        assertThat(HaifaAgents.builder().toolRegistrations(registrations)).isNotNull();
        var prepared = ToolAssembly.prepare(null, List.of(), registrations);
        assertThat(prepared.platform().catalog().snapshot().bindings())
                .extracting(binding -> binding.alias().value())
                .containsExactly("skill_load", "skill_resource_read");
        assertThatThrownBy(() ->
                        ToolAssembly.prepare(null, List.of(), List.of(registrations.get(0), registrations.get(0))))
                .isInstanceOf(HaifaAgentException.class)
                .extracting("code")
                .isEqualTo("TOOL_ALIAS_CONFLICT");
    }

    @Test
    void preservesLegacyFactoryAndNullParameterFailures() {
        var persistence = new InMemoryPersistenceContribution();
        var loader = SkillContentLoader.empty();
        var time = new SystemTimeProvider();

        var legacy = SkillToolContributions.create(persistence, loader, time);
        assertThat(legacy)
                .extracting(contribution -> contribution.alias().value())
                .containsExactly("skill_load", "skill_resource_read");
        assertThat(legacy.get(0).provider()).isSameAs(legacy.get(1).provider());

        assertThatThrownBy(() -> SkillToolContributions.createRegistrations(null, loader, time))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("persistence must not be null");
        assertThatThrownBy(() -> SkillToolContributions.createRegistrations(persistence, null, time))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("contentLoader must not be null");
        assertThatThrownBy(() -> SkillToolContributions.createRegistrations(persistence, loader, null))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("time must not be null");
    }
}
