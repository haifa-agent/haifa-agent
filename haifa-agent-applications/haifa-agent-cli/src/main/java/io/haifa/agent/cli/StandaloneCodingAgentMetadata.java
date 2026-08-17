package io.haifa.agent.cli;

import io.haifa.agent.application.project.product.coding.client.CodingAgentClientMetadata;
import java.util.Objects;

/** Safe, allowlisted identity of one resolved standalone Coding Agent assembly. */
public record StandaloneCodingAgentMetadata(
        String providerId, String modelId, String modelBindingId, String apiStyle, String assemblyDigest)
        implements CodingAgentClientMetadata {
    public StandaloneCodingAgentMetadata {
        providerId = requireText(providerId, "providerId");
        modelId = requireText(modelId, "modelId");
        modelBindingId = requireText(modelBindingId, "modelBindingId");
        apiStyle = requireText(apiStyle, "apiStyle");
        assemblyDigest = requireText(assemblyDigest, "assemblyDigest");
        if (!assemblyDigest.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("assemblyDigest must be lowercase SHA-256");
        }
    }

    private static String requireText(String value, String field) {
        String normalized =
                Objects.requireNonNull(value, field + " must not be null").trim();
        if (normalized.isEmpty()) throw new IllegalArgumentException(field + " must not be blank");
        return normalized;
    }
}
