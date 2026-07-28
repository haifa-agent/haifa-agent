package io.haifa.agent.sdk.contribution;

import io.haifa.agent.sdk.product.ProductCapabilities;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Exact MCP Tool aliases imported into the unified Tool catalog.
 *
 * <p>This contribution does not create a second invocation path; every alias must also be present
 * in the selected {@link ToolPlatformContribution}.
 */
public final class McpToolCatalogContribution extends AbstractSdkContribution {
    private final Set<String> toolAliases;

    public McpToolCatalogContribution(SdkContributionMetadata metadata, Set<String> toolAliases) {
        super(metadata);
        if (!ProductCapabilities.MCP.equals(metadata.capabilityId())) {
            throw new IllegalArgumentException("MCP contribution must provide the mcp capability");
        }
        this.toolAliases = Objects.requireNonNull(toolAliases, "toolAliases must not be null").stream()
                .map(value -> requireAlias(value))
                .collect(Collectors.toUnmodifiableSet());
        if (this.toolAliases.isEmpty()) {
            throw new IllegalArgumentException("MCP contribution must bind at least one Tool alias");
        }
    }

    public Set<String> toolAliases() {
        return toolAliases;
    }

    private static String requireAlias(String value) {
        String normalized =
                Objects.requireNonNull(value, "tool alias must not be null").trim();
        if (normalized.isEmpty() || normalized.length() > 256) {
            throw new IllegalArgumentException("tool alias must contain 1 to 256 characters");
        }
        return normalized;
    }
}
