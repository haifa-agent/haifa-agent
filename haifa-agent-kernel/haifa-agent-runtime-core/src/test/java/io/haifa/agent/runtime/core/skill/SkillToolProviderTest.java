package io.haifa.agent.runtime.core.skill;

import static org.assertj.core.api.Assertions.assertThat;

import io.haifa.agent.core.reference.PrincipalRef;
import io.haifa.agent.core.reference.TenantRef;
import io.haifa.agent.core.run.AgentRunId;
import io.haifa.agent.core.tool.ToolArguments;
import io.haifa.agent.core.tool.ToolCallId;
import io.haifa.agent.skill.api.FrozenSkillBinding;
import io.haifa.agent.skill.api.SkillActivation;
import io.haifa.agent.skill.api.SkillActivationRequest;
import io.haifa.agent.skill.api.SkillAlias;
import io.haifa.agent.skill.api.SkillContent;
import io.haifa.agent.skill.api.SkillContentDigest;
import io.haifa.agent.skill.api.SkillCoordinate;
import io.haifa.agent.skill.api.SkillMetadata;
import io.haifa.agent.skill.api.SkillName;
import io.haifa.agent.skill.api.SkillPackageIndex;
import io.haifa.agent.skill.api.SkillResourceKind;
import io.haifa.agent.skill.api.SkillResourceRef;
import io.haifa.agent.skill.api.SkillScopeRef;
import io.haifa.agent.skill.api.SkillSourceRef;
import io.haifa.agent.tool.api.FrozenToolBinding;
import io.haifa.agent.tool.api.ToolCoordinate;
import io.haifa.agent.tool.api.ToolDefinitionHash;
import io.haifa.agent.tool.api.ToolInvocationObserver;
import io.haifa.agent.tool.api.ToolInvocationRequest;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

class SkillToolProviderTest {
    @Test
    void returnsNonRetryableFailureForUnindexedResourceRequest() {
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
        AtomicBoolean dispatched = new AtomicBoolean();
        AtomicBoolean acknowledged = new AtomicBoolean();
        var request = createRequest(
                provider,
                "skill_resource_read",
                Map.of("skill", "result-verification", "path", "missing.md"),
                dispatched,
                acknowledged);

        var result = provider.invoke(request);

        assertThat(result.successful()).isFalse();
        assertThat(result.summary())
                .isEqualTo(
                        "skill resource is not present in the frozen index. Continue with already injected Skill instructions; do not guess similar resource paths.");
        assertThat(result.structuredData())
                .containsEntry("failureCode", "SKILL_RESOURCE_NOT_INDEXED")
                .containsEntry("skill", "result-verification")
                .containsEntry("retryable", false)
                .containsEntry(
                        "guidance",
                        "Continue with already injected Skill instructions; do not guess similar resource paths.")
                .containsEntry(
                        "action",
                        "Continue with already injected Skill instructions; do not guess similar resource paths.");
        assertThat(dispatched).isTrue();
        assertThat(acknowledged).isTrue();
    }

    @Test
    void returnsNonRetryableFailureForNonTextResourceRequest() {
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
                        "SKILL_RESOURCE_NOT_TEXT", "skill resource is not an auxiliary readable text resource");
            }
        };
        SkillToolProvider provider = new SkillToolProvider(service);
        var request = createRequest(
                provider,
                "skill_resource_read",
                Map.of("skill", "result-verification", "path", "binary.dat"),
                new AtomicBoolean(),
                new AtomicBoolean());

        var result = provider.invoke(request);

        assertThat(result.successful()).isFalse();
        assertThat(result.summary())
                .isEqualTo(
                        "skill resource is not an auxiliary readable text resource. Continue with already injected Skill instructions; do not guess similar resource paths.");
        assertThat(result.structuredData())
                .containsEntry("failureCode", "SKILL_RESOURCE_NOT_TEXT")
                .containsEntry("skill", "result-verification")
                .containsEntry("retryable", false)
                .containsEntry(
                        "guidance",
                        "Continue with already injected Skill instructions; do not guess similar resource paths.")
                .containsEntry(
                        "action",
                        "Continue with already injected Skill instructions; do not guess similar resource paths.");
    }

    @Test
    void activatesSkillWithEmptyReadableResourcesAndDisclosesGuidance() {
        FrozenSkillBinding binding = sampleBinding(
                "git",
                List.of(new SkillResourceRef(
                        "SKILL.md",
                        SkillResourceKind.INSTRUCTION,
                        "text/markdown",
                        new SkillContentDigest("sha256:" + "b".repeat(64)),
                        120,
                        true)));
        SkillActivation activation = new SkillActivation(
                binding,
                "model requested Skill activation",
                "tool-call-1",
                Instant.parse("2026-08-15T00:00:00Z"),
                120,
                30);
        SkillActivationService service = new SkillActivationService() {
            @Override
            public SkillActivation activate(SkillActivationRequest request) {
                return activation;
            }

            @Override
            public SkillContent content(SkillActivationRequest request) {
                throw new UnsupportedOperationException();
            }

            @Override
            public SkillResourceRead readResource(SkillActivationRequest request, String relativePath) {
                throw new UnsupportedOperationException();
            }
        };
        SkillToolProvider provider = new SkillToolProvider(service);
        var request =
                createRequest(provider, "skill_load", Map.of("skill", "git"), new AtomicBoolean(), new AtomicBoolean());

        var result = provider.invoke(request);

        assertThat(result.successful()).isTrue();
        assertThat(result.summary())
                .isEqualTo(
                        "Activated Skill git: Entry SKILL.md instructions are fully injected; no auxiliary readable resources exist, do not call skill_resource_read to guess paths.");
        assertThat(result.structuredData())
                .containsEntry("skill", "git")
                .containsEntry("activated", true)
                .containsEntry("instructionBytes", 120)
                .containsEntry("estimatedTokens", 30)
                .containsEntry("readableResources", List.of())
                .containsEntry(
                        "guidance",
                        "Entry SKILL.md instructions are fully injected; no auxiliary readable resources exist, do not call skill_resource_read to guess paths.");
    }

    @Test
    void activatesSkillWithAuxiliaryReadableResources() {
        FrozenSkillBinding binding = sampleBinding(
                "review-guide",
                List.of(
                        new SkillResourceRef(
                                "SKILL.md",
                                SkillResourceKind.INSTRUCTION,
                                "text/markdown",
                                new SkillContentDigest("sha256:" + "b".repeat(64)),
                                120,
                                true),
                        new SkillResourceRef(
                                "references/guide.md",
                                SkillResourceKind.REFERENCE,
                                "text/markdown",
                                new SkillContentDigest("sha256:" + "c".repeat(64)),
                                200,
                                true),
                        new SkillResourceRef(
                                "assets/diagram.png",
                                SkillResourceKind.ASSET,
                                "image/png",
                                new SkillContentDigest("sha256:" + "d".repeat(64)),
                                500,
                                false),
                        new SkillResourceRef(
                                "docs/checklist.txt",
                                SkillResourceKind.REFERENCE,
                                "text/plain",
                                new SkillContentDigest("sha256:" + "e".repeat(64)),
                                80,
                                true)));
        SkillActivation activation = new SkillActivation(
                binding,
                "model requested Skill activation",
                "tool-call-1",
                Instant.parse("2026-08-15T00:00:00Z"),
                120,
                30);
        SkillActivationService service = new SkillActivationService() {
            @Override
            public SkillActivation activate(SkillActivationRequest request) {
                return activation;
            }

            @Override
            public SkillContent content(SkillActivationRequest request) {
                throw new UnsupportedOperationException();
            }

            @Override
            public SkillResourceRead readResource(SkillActivationRequest request, String relativePath) {
                throw new UnsupportedOperationException();
            }
        };
        SkillToolProvider provider = new SkillToolProvider(service);
        var request = createRequest(
                provider, "skill_load", Map.of("skill", "review-guide"), new AtomicBoolean(), new AtomicBoolean());

        var result = provider.invoke(request);

        assertThat(result.successful()).isTrue();
        assertThat(result.summary())
                .isEqualTo(
                        "Activated Skill review-guide with readable auxiliary resources: docs/checklist.txt, references/guide.md");
        assertThat(result.structuredData())
                .containsEntry("skill", "review-guide")
                .containsEntry("activated", true)
                .containsEntry("instructionBytes", 120)
                .containsEntry("estimatedTokens", 30)
                .containsEntry("readableResources", List.of("docs/checklist.txt", "references/guide.md"))
                .doesNotContainKey("guidance");
    }

    @Test
    void loadDefinitionSchemaDeclaresReadableResourcesAndGuidance() {
        SkillToolProvider provider = new SkillToolProvider(new SkillActivationService() {
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
                throw new UnsupportedOperationException();
            }
        });
        var contribution = provider.contributions().stream()
                .filter(candidate -> candidate.definition().name().value().equals("skill_load"))
                .findFirst()
                .orElseThrow();
        var outputSchema = contribution.definition().outputSchema();

        @SuppressWarnings("unchecked")
        Map<String, Object> properties =
                (Map<String, Object>) outputSchema.document().get("properties");
        @SuppressWarnings("unchecked")
        List<String> required = (List<String>) outputSchema.document().get("required");

        assertThat(properties).containsKey("readableResources").containsKey("guidance");
        assertThat(required).contains("readableResources");
    }

    private static FrozenSkillBinding sampleBinding(String alias, List<SkillResourceRef> resources) {
        SkillContentDigest packageDigest = new SkillContentDigest("sha256:" + "a".repeat(64));
        SkillName name = new SkillName(alias);
        SkillPackageIndex index = new SkillPackageIndex(packageDigest, resources);
        SkillCoordinate coordinate = new SkillCoordinate(
                SkillScopeRef.product(), new SkillSourceRef("fixture", "1"), name, Optional.empty(), packageDigest);
        return new FrozenSkillBinding(
                new SkillAlias(alias),
                coordinate,
                new SkillMetadata(
                        name,
                        "Sample skill description",
                        Optional.empty(),
                        Optional.empty(),
                        Optional.empty(),
                        Map.of(),
                        Set.of()),
                index,
                packageDigest,
                packageDigest,
                "default");
    }

    private static ToolInvocationRequest createRequest(
            SkillToolProvider provider,
            String toolName,
            Map<String, Object> arguments,
            AtomicBoolean dispatched,
            AtomicBoolean acknowledged) {
        var contribution = provider.contributions().stream()
                .filter(candidate -> candidate.definition().name().value().equals(toolName))
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
        return new ToolInvocationRequest(
                binding,
                new ToolCallId("tool-call-1"),
                new AgentRunId("run-1"),
                new TenantRef("tenant-1"),
                new PrincipalRef("principal-1", "USER"),
                new ToolArguments(definition.inputSchema().id(), "1.0.0", arguments),
                Instant.parse("2026-08-15T00:00:00Z"),
                Optional.of("key-1"),
                () -> false,
                Map.of(),
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
    }
}
