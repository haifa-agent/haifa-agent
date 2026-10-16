package io.haifa.agent.runtime.core.model;

import io.haifa.agent.core.run.AgentRunId;
import io.haifa.agent.core.tool.ToolCall;
import io.haifa.agent.core.tool.ToolResult;
import io.haifa.agent.runtime.core.storage.RunStateRepository;
import io.haifa.agent.runtime.core.storage.RuntimeStateRepository;
import io.haifa.agent.runtime.core.tool.LargeToolResultPolicy;
import io.haifa.agent.tool.api.FrozenToolBinding;
import io.haifa.agent.tool.api.ToolDefinition;
import io.haifa.agent.tool.api.ToolEffectClass;
import io.haifa.agent.tool.api.ToolExecutionMode;
import io.haifa.agent.tool.api.ToolResourceRequirements;
import io.haifa.agent.tool.api.ToolSideEffect;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Qualifies historical Tool results from the frozen Tool Binding that belongs to the call's own Run and configuration.
 *
 * <p>A candidate must be a successful, sufficiently large, locally readable pure-read result. Network, execution and
 * mutating tools, failed or unknown outcomes and missing or mismatched binding facts all fail closed and keep the
 * original result. This class intentionally owns no persistent cache: the per-Run binding index is scoped to one
 * planning operation and is discarded afterwards.
 */
public final class FrozenBindingToolResultQualifier implements ToolResultPruningQualifier {

    private final RunStateRepository runs;
    private final RuntimeStateRepository state;
    private final LargeToolResultPolicy largeResults = LargeToolResultPolicy.defaults();
    private final Map<AgentRunId, Map<String, FrozenToolBinding>> bindingsByRun = new HashMap<>();

    public FrozenBindingToolResultQualifier(RunStateRepository runs, RuntimeStateRepository state) {
        this.runs = runs;
        this.state = Objects.requireNonNull(state, "state must not be null");
    }

    @Override
    public boolean isPrunable(ToolCall call) {
        Optional<ToolResult> result = call.result();
        if (result.isEmpty() || !result.get().successful()) {
            return false;
        }
        if (!largeResults.requiresExternalization(result.get())) {
            return false;
        }
        return bindingFor(call).map(binding -> isSafeLocalPureRead(binding.definition()))
                .orElse(false);
    }

    private Optional<FrozenToolBinding> bindingFor(ToolCall call) {
        if (runs == null) {
            return Optional.empty();
        }
        FrozenToolBinding binding = bindingsByRun
                .computeIfAbsent(call.runId(), this::loadBindings)
                .get(call.toolName());
        if (binding == null || !binding.definition().version().value().equals(call.toolVersion())) {
            return Optional.empty();
        }
        return Optional.of(binding);
    }

    private Map<String, FrozenToolBinding> loadBindings(AgentRunId runId) {
        Map<String, FrozenToolBinding> index = new HashMap<>();
        runs.find(runId)
                .flatMap(run -> state.configuration(run.configurationSnapshot()))
                .ifPresent(configuration -> configuration
                        .toolBindings()
                        .forEach(binding -> index.put(binding.alias().value(), binding)));
        return Map.copyOf(index);
    }

    private static boolean isSafeLocalPureRead(ToolDefinition definition) {
        if (definition.effectClass() != ToolEffectClass.PURE_READ) {
            return false;
        }
        ToolResourceRequirements resources = definition.resources();
        if (!resources.networkHosts().isEmpty() || !resources.executionProfiles().isEmpty()) {
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
