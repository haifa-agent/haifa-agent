package io.haifa.agent.runtime.core.bootstrap;

import io.haifa.agent.core.reference.RunConfigurationSnapshotRef;
import io.haifa.agent.runtime.api.AgentRunRequest;
import io.haifa.agent.skill.api.FrozenSkillBinding;
import io.haifa.agent.skill.api.SkillCatalogSnapshot;
import io.haifa.agent.skill.api.SkillTrustSnapshot;
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
    private final SkillTrustSnapshot trust;

    public ContentAddressedSnapshotFactory() {
        this(
                io.haifa.agent.tool.api.ToolCatalog.empty().snapshot(),
                io.haifa.agent.skill.api.SkillCatalog.empty().snapshot());
    }

    public ContentAddressedSnapshotFactory(ToolCatalogSnapshot tools) {
        this(tools, io.haifa.agent.skill.api.SkillCatalog.empty().snapshot());
    }

    public ContentAddressedSnapshotFactory(ToolCatalogSnapshot tools, SkillCatalogSnapshot skills) {
        this(tools, skills, SkillTrustSnapshot.empty());
    }

    public ContentAddressedSnapshotFactory(
            ToolCatalogSnapshot tools, SkillCatalogSnapshot skills, SkillTrustSnapshot trust) {
        this.tools = Objects.requireNonNull(tools, "tools");
        this.skills = Objects.requireNonNull(skills, "skills");
        this.trust = Objects.requireNonNull(trust, "trust");
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
        var frozenSkillCoordinates = frozenSkills.stream()
                .map(FrozenSkillBinding::coordinate)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        var frozenToolCoordinates = frozenTools.stream()
                .map(FrozenToolBinding::coordinate)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        var frozenScriptGrants = trust.scriptExecutionGrants().stream()
                .filter(grant -> frozenSkillCoordinates.contains(grant.coordinate())
                        && frozenToolCoordinates.contains(grant.toolCoordinate()))
                .sorted(java.util.Comparator.comparing(io.haifa.agent.skill.api.SkillScriptExecutionGrant::id))
                .toList();
        var requiredPackageIds = frozenScriptGrants.stream()
                .map(io.haifa.agent.skill.api.SkillScriptExecutionGrant::packageReviewGrantId)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        var frozenPackageGrants = trust.packageReviewGrants().stream()
                .filter(grant -> frozenSkillCoordinates.contains(grant.coordinate())
                        && (requiredPackageIds.contains(grant.id())
                                || frozenSkills.stream().anyMatch(binding -> binding.packageReviewGrantId()
                                        .filter(grant.id()::equals)
                                        .isPresent())))
                .sorted(java.util.Comparator.comparing(io.haifa.agent.skill.api.SkillPackageReviewGrant::id))
                .toList();
        SkillTrustSnapshot frozenTrust =
                new SkillTrustSnapshot(trust.manifestDigest(), frozenPackageGrants, frozenScriptGrants);
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
                + frozenTrust.manifestDigest() + "|"
                + frozenPackageGrants.stream()
                        .map(grant -> grant.id() + ":" + grant.version() + ":" + grant.state() + ":"
                                + grant.coordinate().externalForm() + ":"
                                + grant.registrationDigest().value() + ":"
                                + grant.expiresAt() + ":" + grant.revokedAt())
                        .toList()
                + "|"
                + frozenScriptGrants.stream()
                        .map(grant -> grant.id() + ":" + grant.version() + ":" + grant.state() + ":"
                                + grant.packageReviewGrantId() + ":" + grant.scriptRelativePath() + ":"
                                + grant.scriptDigest().value() + ":"
                                + grant.toolCoordinate().externalForm() + ":"
                                + grant.argumentPolicyDigest() + ":" + grant.scriptRuntimeRef() + ":"
                                + grant.executionProfileDigest() + ":" + grant.sandboxDigest() + ":"
                                + grant.expiresAt() + ":" + grant.revokedAt())
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
                + profile.model().configurationDigest() + "|"
                + ModelRequestOptions.canonical(profile.modelRequestOptions())
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
                    frozenTrust,
                    definition.allowedChildAgents(),
                    definition.instruction(),
                    request.overrides(),
                    capabilities,
                    profile.model(),
                    profile.modelRequestOptions());
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is required by the Java runtime", exception);
        }
    }
}
