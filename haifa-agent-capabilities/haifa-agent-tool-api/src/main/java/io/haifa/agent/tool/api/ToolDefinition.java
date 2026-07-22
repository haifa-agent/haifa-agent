package io.haifa.agent.tool.api;

import io.haifa.agent.credential.api.CredentialRequirement;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Set;

public record ToolDefinition(
        ToolName name,
        SemanticVersion version,
        ToolProviderId providerId,
        String title,
        String description,
        ToolSchema inputSchema,
        ToolSchema outputSchema,
        ToolExecutionMode executionMode,
        boolean cancellationSupported,
        Duration timeout,
        String concurrencyPolicy,
        ToolIdempotency idempotency,
        ToolRisk risk,
        Set<ToolSideEffect> sideEffects,
        ToolResourceRequirements resources,
        List<CredentialRequirement> credentialRequirements,
        ToolApprovalRequirement approvalRequirement,
        String provenance,
        boolean deprecated,
        Set<String> tags) {
    public ToolDefinition {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(version, "version");
        Objects.requireNonNull(providerId, "providerId");
        title = ToolValues.text(title, "title");
        description = ToolValues.text(description, "description");
        Objects.requireNonNull(inputSchema, "inputSchema");
        Objects.requireNonNull(outputSchema, "outputSchema");
        Objects.requireNonNull(executionMode, "executionMode");
        Objects.requireNonNull(timeout, "timeout");
        if (timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException("timeout must be positive");
        }
        concurrencyPolicy = ToolValues.text(concurrencyPolicy, "concurrencyPolicy");
        Objects.requireNonNull(idempotency, "idempotency");
        Objects.requireNonNull(risk, "risk");
        sideEffects = ToolValues.set(sideEffects, "sideEffects");
        Objects.requireNonNull(resources, "resources");
        credentialRequirements = List.copyOf(Objects.requireNonNull(credentialRequirements, "credentialRequirements"));
        Objects.requireNonNull(approvalRequirement, "approvalRequirement");
        provenance = ToolValues.text(provenance, "provenance");
        tags = ToolValues.set(tags, "tags");
    }
}
