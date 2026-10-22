package io.haifa.agent.runtime.core.model;

import io.haifa.agent.core.tool.ToolCall;
import io.haifa.agent.core.tool.ToolResult;
import io.haifa.agent.runtime.core.tool.LargeToolResultPolicy;
import io.haifa.agent.tool.api.FrozenToolBinding;
import io.haifa.agent.tool.api.ToolDefinition;
import io.haifa.agent.tool.api.ToolEffectClass;
import io.haifa.agent.tool.api.ToolExecutionMode;
import io.haifa.agent.tool.api.ToolResourceRequirements;
import io.haifa.agent.tool.api.ToolSideEffect;
import java.util.Optional;

/**
 * Qualifies historical Tool results from the frozen Tool Binding that belongs to the call's own Run and configuration.
 *
 * <p>This class is stateless and safe to share. The per-Run binding index belongs to one planning operation (see
 * {@link OperationToolBindings}) and is discarded with it, so no mutable cache is shared across Runtime calls.
 *
 * <p>A candidate must be a successful, sufficiently large, locally readable pure-read result. Network, execution and
 * mutating tools, failed or unknown outcomes and missing or mismatched binding facts all fail closed and keep the
 * original result.
 */
public final class FrozenBindingToolResultQualifier implements ToolResultPruningQualifier {

    private final LargeToolResultPolicy largeResults = LargeToolResultPolicy.defaults();

    @Override
    public boolean isPrunable(ToolCall call, FrozenToolBinding binding) {
        if (binding == null) {
            return false;
        }
        Optional<ToolResult> result = call.result();
        if (result.isEmpty() || !result.get().successful()) {
            return false;
        }
        if (!largeResults.requiresExternalization(result.get())) {
            return false;
        }
        if (!binding.definition().version().value().equals(call.toolVersion())) {
            return false;
        }
        return isSafeLocalPureRead(binding.definition());
    }

    private static boolean isSafeLocalPureRead(ToolDefinition definition) {
        if (definition.effectClass() != ToolEffectClass.PURE_READ) {
            return false;
        }
        ToolResourceRequirements resources = definition.resources();
        if (!resources.networkHosts().isEmpty()
                || !resources.executionProfiles().isEmpty()) {
            return false;
        }
        for (ToolSideEffect sideEffect : definition.sideEffects()) {
            if (sideEffect != ToolSideEffect.FILE_READ) {
                return false;
            }
        }
        return definition.executionMode() == ToolExecutionMode.IN_PROCESS;
    }
}
