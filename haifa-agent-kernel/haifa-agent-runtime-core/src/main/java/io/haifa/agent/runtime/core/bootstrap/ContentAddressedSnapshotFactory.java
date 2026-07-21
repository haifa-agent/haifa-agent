package io.haifa.agent.runtime.core.bootstrap;

import io.haifa.agent.core.reference.RunConfigurationSnapshotRef;
import io.haifa.agent.runtime.api.AgentRunRequest;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** Deterministic content-addressed configuration snapshot suitable for adapters to persist. */
public final class ContentAddressedSnapshotFactory implements ConfigurationSnapshotFactory {
    @Override
    public RuntimeConfigurationSnapshot create(
            AgentRunRequest request,
            ResolvedDefinition definition,
            ResolvedProfile profile,
            RuntimeCallerContext caller) {
        String canonical = definition.id().value() + "|" + definition.version() + "|"
                + new java.util.TreeSet<>(definition.allowedTools()) + "|"
                + definition.allowedChildAgents().stream()
                        .map(value -> value.value())
                        .sorted()
                        .toList() + "|"
                + definition.instruction() + "|"
                + profile.id() + "|" + profile.version() + "|" + profile.runType() + "|" + profile.budget() + "|"
                + profile.limits() + "|" + request.overrides().schemaId() + "|"
                + request.overrides().schemaVersion() + "|"
                + new java.util.TreeMap<>(request.overrides().values())
                + "|" + profile.model().providerId() + "|" + profile.model().modelId() + "|"
                + profile.model().providerModelId() + "|" + profile.model().adapterType() + "|"
                + profile.model().adapterVersion() + "|" + profile.model().configurationDigest()
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
                    definition.allowedTools(),
                    definition.allowedChildAgents(),
                    definition.instruction(),
                    request.overrides(),
                    profile.model());
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is required by the Java runtime", exception);
        }
    }
}
