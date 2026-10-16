package io.haifa.agent.runtime.core.model;

import io.haifa.agent.core.run.AgentRunId;
import io.haifa.agent.core.tool.ToolCall;
import io.haifa.agent.runtime.core.storage.RunStateRepository;
import io.haifa.agent.runtime.core.storage.RuntimeStateRepository;
import io.haifa.agent.tool.api.FrozenToolBinding;
import java.util.HashMap;
import java.util.Map;

/**
 * Per-planning-operation index of frozen Tool Bindings.
 *
 * <p>One instance is created and discarded inside a single {@link ModelMessageProjectionPlanner#plan} call. Each
 * relevant Run is therefore loaded at most once per operation, a missing binding can never leak into a later operation,
 * and the index is never shared across operations or threads. It is intentionally not a cache of any longer lifetime.
 */
final class OperationToolBindings {

    private final RunStateRepository runs;
    private final RuntimeStateRepository state;
    private final Map<AgentRunId, Map<String, FrozenToolBinding>> bindingsByRun = new HashMap<>();

    OperationToolBindings(RunStateRepository runs, RuntimeStateRepository state) {
        this.runs = runs;
        this.state = state;
    }

    FrozenToolBinding bindingFor(ToolCall call) {
        if (runs == null || state == null) {
            return null;
        }
        return bindingsByRun.computeIfAbsent(call.runId(), this::loadBindings).get(call.toolName());
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
}
