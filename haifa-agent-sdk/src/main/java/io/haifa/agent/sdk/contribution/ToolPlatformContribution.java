package io.haifa.agent.sdk.contribution;

import io.haifa.agent.tool.api.ToolCatalog;
import io.haifa.agent.tool.api.ToolInvoker;
import io.haifa.agent.tool.api.ToolSchemaValidator;
import java.util.Objects;

/** Unified Tool pipeline implementation selected by a Product Profile. */
public record ToolPlatformContribution(ToolCatalog catalog, ToolInvoker invoker, ToolSchemaValidator schemaValidator) {
    public ToolPlatformContribution {
        catalog = Objects.requireNonNull(catalog, "catalog must not be null");
        invoker = Objects.requireNonNull(invoker, "invoker must not be null");
        schemaValidator = Objects.requireNonNull(schemaValidator, "schemaValidator must not be null");
    }
}
