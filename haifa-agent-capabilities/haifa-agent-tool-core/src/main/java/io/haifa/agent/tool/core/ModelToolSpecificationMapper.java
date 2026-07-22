package io.haifa.agent.tool.core;

import io.haifa.agent.model.api.ModelToolSpecification;
import io.haifa.agent.tool.api.FrozenToolBinding;

public final class ModelToolSpecificationMapper {
    public ModelToolSpecification map(FrozenToolBinding binding) {
        var definition = binding.definition();
        var schema = definition.inputSchema();
        return new ModelToolSpecification(
                binding.alias().value(),
                definition.version().value(),
                definition.description(),
                schema.id(),
                schema.version(),
                schema.document(),
                false);
    }
}
