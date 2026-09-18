package io.haifa.agent.runtime.core.skill;

import io.haifa.agent.tool.api.ToolAlias;
import io.haifa.agent.tool.api.ToolDefinition;
import io.haifa.agent.tool.api.ToolProvider;
import java.util.Objects;

public record SkillToolCatalogContribution(
        ToolAlias alias, ToolDefinition definition, String providerBindingReference, ToolProvider provider) {
    public SkillToolCatalogContribution {
        alias = Objects.requireNonNull(alias);
        definition = Objects.requireNonNull(definition);
        providerBindingReference = Objects.requireNonNull(providerBindingReference, "providerBindingReference")
                .trim();
        if (providerBindingReference.isEmpty()) {
            throw new IllegalArgumentException("providerBindingReference must not be blank");
        }
        provider = Objects.requireNonNull(provider);
    }
}
