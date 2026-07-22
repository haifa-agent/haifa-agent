package io.haifa.agent.runtime.core.tool;

import io.haifa.agent.runtime.core.decision.ToolRequest;
import io.haifa.agent.tool.api.FrozenToolBinding;
import io.haifa.agent.tool.api.ToolAlias;
import java.util.List;
import java.util.Objects;

/** Resolves a model-visible alias only against the immutable bindings frozen for one run. */
public final class FrozenToolBindingResolver {
    public FrozenToolBinding resolve(List<FrozenToolBinding> bindings, ToolRequest request) {
        Objects.requireNonNull(bindings, "bindings");
        Objects.requireNonNull(request, "request");
        ToolAlias alias = new ToolAlias(request.toolName());
        FrozenToolBinding binding = bindings.stream()
                .filter(candidate -> candidate.alias().equals(alias))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("unknown frozen tool alias: " + request.toolName()));
        if (!binding.definition().version().value().equals(request.toolVersion())) {
            throw new IllegalArgumentException("tool version does not match frozen binding");
        }
        if (!binding.definition().inputSchema().id().equals(request.arguments().schemaId())
                || !binding.definition()
                        .inputSchema()
                        .version()
                        .equals(request.arguments().schemaVersion())) {
            throw new IllegalArgumentException("tool input schema identity does not match frozen binding");
        }
        return binding;
    }
}
