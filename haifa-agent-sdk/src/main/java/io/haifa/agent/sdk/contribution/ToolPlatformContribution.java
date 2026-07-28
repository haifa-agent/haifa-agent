package io.haifa.agent.sdk.contribution;

import io.haifa.agent.sdk.product.ProductCapabilities;
import io.haifa.agent.tool.api.ToolCatalog;
import io.haifa.agent.tool.api.ToolInvoker;
import io.haifa.agent.tool.api.ToolSchemaValidator;
import java.util.Objects;

/** Unified Tool pipeline implementation selected by a Product Profile. */
public final class ToolPlatformContribution extends AbstractSdkContribution {
    private final ToolCatalog catalog;
    private final ToolInvoker invoker;
    private final ToolSchemaValidator schemaValidator;

    public ToolPlatformContribution(
            SdkContributionMetadata metadata,
            ToolCatalog catalog,
            ToolInvoker invoker,
            ToolSchemaValidator schemaValidator) {
        super(metadata);
        if (!ProductCapabilities.TOOL.equals(metadata.capabilityId())) {
            throw new IllegalArgumentException("tool contribution must provide the tool capability");
        }
        this.catalog = Objects.requireNonNull(catalog, "catalog must not be null");
        this.invoker = Objects.requireNonNull(invoker, "invoker must not be null");
        this.schemaValidator = Objects.requireNonNull(schemaValidator, "schemaValidator must not be null");
    }

    public ToolCatalog catalog() {
        return catalog;
    }

    public ToolInvoker invoker() {
        return invoker;
    }

    public ToolSchemaValidator schemaValidator() {
        return schemaValidator;
    }

    @Override
    public void validate() {
        if (!configurationDigest().equals(catalog.snapshot().digest())) {
            throw new IllegalArgumentException("tool contribution digest must match the frozen Tool catalog");
        }
    }
}
