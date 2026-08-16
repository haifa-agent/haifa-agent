package io.haifa.agent.runtime.core.skill;

import static org.assertj.core.api.Assertions.assertThat;

import io.haifa.agent.core.reference.PrincipalRef;
import io.haifa.agent.core.reference.TenantRef;
import io.haifa.agent.core.run.AgentRunId;
import io.haifa.agent.core.tool.ToolArguments;
import io.haifa.agent.core.tool.ToolCallId;
import io.haifa.agent.skill.api.SkillActivation;
import io.haifa.agent.skill.api.SkillActivationRequest;
import io.haifa.agent.skill.api.SkillContent;
import io.haifa.agent.tool.api.FrozenToolBinding;
import io.haifa.agent.tool.api.ToolCoordinate;
import io.haifa.agent.tool.api.ToolDefinitionHash;
import io.haifa.agent.tool.api.ToolInvocationObserver;
import io.haifa.agent.tool.api.ToolInvocationRequest;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

class SkillToolProviderTest {
    @Test
    void returnsModelRepairableFailureForRejectedResourceRequest() {
        SkillActivationService service = new SkillActivationService() {
            @Override
            public SkillActivation activate(SkillActivationRequest request) {
                throw new UnsupportedOperationException();
            }

            @Override
            public SkillContent content(SkillActivationRequest request) {
                throw new UnsupportedOperationException();
            }

            @Override
            public SkillResourceRead readResource(SkillActivationRequest request, String relativePath) {
                throw new SkillRequestRejectedException(
                        "SKILL_RESOURCE_NOT_INDEXED", "skill resource is not present in the frozen index");
            }
        };
        SkillToolProvider provider = new SkillToolProvider(service);
        var contribution = provider.contributions().stream()
                .filter(candidate -> candidate.definition().name().value().equals("skill.resource.read"))
                .findFirst()
                .orElseThrow();
        var definition = contribution.definition();
        var coordinate = new ToolCoordinate(
                definition.name(),
                definition.version(),
                definition.providerId(),
                new ToolDefinitionHash("0".repeat(64)));
        var binding = new FrozenToolBinding(
                contribution.alias(), coordinate, definition, contribution.providerBindingReference(), "catalog");
        AtomicBoolean dispatched = new AtomicBoolean();
        AtomicBoolean acknowledged = new AtomicBoolean();
        var request = new ToolInvocationRequest(
                binding,
                new ToolCallId("tool-call-1"),
                new AgentRunId("run-1"),
                new TenantRef("tenant-1"),
                new PrincipalRef("principal-1", "USER"),
                new ToolArguments(
                        "haifa.skill.resource.read.input",
                        "1.0.0",
                        Map.of("skill", "result-verification", "path", "missing.md")),
                Instant.parse("2026-08-15T00:00:00Z"),
                Optional.of("key-1"),
                () -> false,
                List.of(),
                new ToolInvocationObserver() {
                    @Override
                    public void dispatched() {
                        dispatched.set(true);
                    }

                    @Override
                    public void acknowledged() {
                        acknowledged.set(true);
                    }
                });

        var result = provider.invoke(request);

        assertThat(result.successful()).isFalse();
        assertThat(result.summary()).isEqualTo("skill resource is not present in the frozen index");
        assertThat(result.structuredData())
                .containsEntry("failureCode", "SKILL_RESOURCE_NOT_INDEXED")
                .containsEntry("skill", "result-verification");
        assertThat(dispatched).isTrue();
        assertThat(acknowledged).isTrue();
    }
}
