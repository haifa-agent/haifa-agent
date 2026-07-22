package io.haifa.agent.tool.core;

import io.haifa.agent.core.tool.ToolResult;
import io.haifa.agent.tool.api.FrozenToolBinding;
import io.haifa.agent.tool.api.ToolInvocationException;
import io.haifa.agent.tool.api.ToolInvocationRequest;
import io.haifa.agent.tool.api.ToolInvoker;
import java.util.Objects;

public final class DefaultToolInvoker implements ToolInvoker {
    private final DefaultToolCatalog catalog;

    public DefaultToolInvoker(DefaultToolCatalog catalog) {
        this.catalog = Objects.requireNonNull(catalog, "catalog");
    }

    @Override
    public ToolResult invoke(ToolInvocationRequest request) {
        FrozenToolBinding match = requireBinding(request.binding());
        return catalog.provider(match.coordinate().providerId()).invoke(request);
    }

    @Override
    public void validateBinding(FrozenToolBinding binding) {
        requireBinding(binding);
    }

    private FrozenToolBinding requireBinding(FrozenToolBinding binding) {
        var frozen = catalog.findByCoordinate(binding.coordinate())
                .orElseThrow(() -> new ToolInvocationException("tool coordinate is not in the frozen catalog"));
        if (!frozen.equals(binding)) {
            throw new ToolInvocationException("tool binding differs from the frozen catalog");
        }
        return frozen;
    }
}
