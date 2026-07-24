package io.haifa.agent.runtime.core.bootstrap;

import io.haifa.agent.core.reference.RunConfigurationSnapshotRef;
import io.haifa.agent.runtime.api.AgentRunRequest;
import io.haifa.agent.skill.api.FrozenSkillBinding;
import io.haifa.agent.skill.api.SkillCatalogSnapshot;
import io.haifa.agent.tool.api.FrozenToolBinding;
import io.haifa.agent.tool.api.ToolCatalogSnapshot;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;

/** Deterministic content-addressed configuration snapshot suitable for adapters to persist. */
public final class ContentAddressedSnapshotFactory implements ConfigurationSnapshotFactory {
    private static final int MAX_FROZEN_SKILLS = 64;

    private final ToolCatalogSnapshot tools;
    private final SkillCatalogSnapshot skills;

    public ContentAddressedSnapshotFactory() {
        this(
                io.haifa.agent.tool.api.ToolCatalog.empty().snapshot(),
                io.haifa.agent.skill.api.SkillCatalog.empty().snapshot());
    }

    public ContentAddressedSnapshotFactory(ToolCatalogSnapshot tools) {
        this(tools, io.haifa.agent.skill.api.SkillCatalog.empty().snapshot());
    }

    public ContentAddressedSnapshotFactory(ToolCatalogSnapshot tools, SkillCatalogSnapshot skills) {
        this.tools = Objects.requireNonNull(tools, "tools");
        this.skills = Objects.requireNonNull(skills, "skills");
    }

    public RuntimeConfigurationSnapshot create(
            AgentRunRequest request,
            ResolvedDefinition definition,
            ResolvedProfile profile,
            RuntimeCallerContext caller) {
        return create(request, definition, profile, caller, List.of());
    }

    @Override
    public RuntimeConfigurationSnapshot create(
            AgentRunRequest request,
            ResolvedDefinition definition,
            ResolvedProfile profile,
            RuntimeCallerContext caller,
            List<EffectiveCapability> capabilities) {
        List<FrozenToolBinding> frozenTools = definition.allowedTools().stream()
                .sorted()
                .map(alias -> tools.bindings().stream()
                        .filter(binding -> binding.alias().value().equals(alias))
                        .findFirst()
                        .orElseThrow(() -> new IllegalStateException("allowed tool is absent from catalog: " + alias)))
                .toList();
        if (definition.allowedSkills().size() > MAX_FROZEN_SKILLS) {
            throw new IllegalStateException("allowed skill count exceeds the run snapshot limit");
        }
        List<FrozenSkillBinding> frozenSkills = definition.allowedSkills().stream()
                .sorted()
                .map(alias -> skills.bindings().stream()
                        .filter(binding -> binding.alias().value().equals(alias))
                        .findFirst()
                        .orElseThrow(() -> new IllegalStateException("allowed skill is absent from catalog: " + alias)))
                .toList();
        String canonical = definition.id().value() + "|" + definition.version() + "|"
                + frozenTools.stream()
                        .map(binding -> binding.alias().value() + "="
                                + binding.coordinate().externalForm() + ":" + binding.providerBindingReference() + ":"
                                + binding.catalogDigest())
                        .toList()
                + "|"
                + skills.digest().value() + "|" + skills.resolutionPolicyRef() + "|"
                + frozenSkills.stream()
                        .map(binding -> binding.alias().value() + "="
                                + binding.coordinate().externalForm() + ":"
                                + binding.resourceIndexDigest().value() + ":"
                                + binding.registrationDigest().value() + ":" + binding.resolutionPolicyRef())
                        .toList()
                + "|"
                + definition.allowedChildAgents().stream()
                        .map(value -> value.value())
                        .sorted()
                        .toList() + "|"
                + definition.instruction() + "|"
                + profile.id() + "|" + profile.version() + "|" + profile.runType() + "|" + profile.budget() + "|"
                + profile.limits() + "|" + request.overrides().schemaId() + "|"
                + request.overrides().schemaVersion() + "|"
                + new java.util.TreeMap<>(request.overrides().values())
                + "|" + profile.model().schemaVersion() + "|" + profile.model().providerId() + "|"
                + profile.model().providerVersion() + "|" + profile.model().modelId() + "|"
                + profile.model().modelVersion() + "|"
                + profile.model().providerModelId() + "|" + profile.model().adapterType() + "|"
                + profile.model().adapterVersion() + "|" + profile.model().endpoint() + "|"
                + profile.model().contextWindow() + "|" + profile.model().maxOutputTokens() + "|"
                + profile.model().configurationDigest()
                + "|"
                + capabilities.stream()
                        .sorted()
                        .map(value -> value.capabilityId() + "@" + value.version() + ":"
                                + value.optionalBindingRef().orElse("") + ":" + value.configurationDigest())
                        .toList()
                + "|" + caller.tenant().tenantId() + "|" + caller.principal();
        try {
            String hash = HexFormat.of()
                    .formatHex(MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8)));
            var reference = new RunConfigurationSnapshotRef("config-" + hash.substring(0, 24), "sha256:" + hash);
            return new RuntimeConfigurationSnapshot(
                    reference,
                    definition.id(),
                    definition.version(),
                    profile.id(),
                    profile.version(),
                    profile.runType(),
                    profile.budget(),
                    profile.limits(),
                    frozenTools,
                    frozenSkills,
                    skills.digest(),
                    skills.resolutionPolicyRef(),
                    definition.allowedChildAgents(),
                    definition.instruction(),
                    request.overrides(),
                    capabilities,
                    profile.model());
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is required by the Java runtime", exception);
        }
    }
}
