package io.haifa.agent.tool.core;

import io.haifa.agent.core.tool.ToolResult;
import io.haifa.agent.tool.api.FrozenToolBinding;
import io.haifa.agent.tool.api.ToolAlias;
import io.haifa.agent.tool.api.ToolInvocationRequest;
import io.haifa.agent.tool.api.ToolInvoker;
import io.haifa.agent.tool.api.ToolReconciliation;
import io.haifa.agent.tool.api.ToolReconciliationRequest;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

public final class DefaultToolInvoker implements ToolInvoker {
    private final DefaultToolCatalog catalog;
    private final Optional<DefaultToolCatalog> predecessor;

    public DefaultToolInvoker(DefaultToolCatalog catalog) {
        this(catalog, Set.of());
    }

    /** Accepts exact bindings from the same catalog before the declared tools were added. */
    public DefaultToolInvoker(DefaultToolCatalog catalog, Set<ToolAlias> addedAliases) {
        this.catalog = Objects.requireNonNull(catalog, "catalog");
        this.predecessor =
                addedAliases.isEmpty() ? Optional.empty() : Optional.of(predecessor(catalog, Set.copyOf(addedAliases)));
    }

    private static DefaultToolCatalog predecessor(DefaultToolCatalog catalog, Set<ToolAlias> addedAliases) {
        if (addedAliases.stream().anyMatch(alias -> catalog.findByAlias(alias).isEmpty())) {
            throw new IllegalArgumentException("added tool alias is not in the catalog");
        }
        var builder = new ToolCatalogBuilder();
        for (var binding : catalog.snapshot().bindings()) {
            if (!addedAliases.contains(binding.alias())) {
                builder.register(
                        binding.alias(),
                        binding.definition(),
                        binding.providerBindingReference(),
                        catalog.provider(binding.coordinate().providerId()));
            }
        }
        return builder.freeze();
    }

    @Override
    public ToolResult invoke(ToolInvocationRequest request) {
        FrozenToolBinding match = requireBinding(request.binding());
        return catalog.provider(match.coordinate().providerId()).invoke(request);
    }

    @Override
    public ToolReconciliation reconcile(ToolReconciliationRequest request) {
        FrozenToolBinding match = requireBinding(request.binding());
        return catalog.provider(match.coordinate().providerId()).reconcile(request);
    }

    @Override
    public void validateBinding(FrozenToolBinding binding) {
        requireBinding(binding);
    }

    private FrozenToolBinding requireBinding(FrozenToolBinding binding) {
        var frozen = catalog.findByCoordinate(binding.coordinate())
                .orElseThrow(() -> new IllegalStateException("tool coordinate is not in the frozen catalog"));
        if (!frozen.equals(binding)
                && predecessor
                        .flatMap(previous -> previous.findByCoordinate(binding.coordinate()))
                        .filter(binding::equals)
                        .isEmpty()) {
            throw new IllegalStateException("tool binding differs from the frozen catalog");
        }
        return frozen;
    }
}
