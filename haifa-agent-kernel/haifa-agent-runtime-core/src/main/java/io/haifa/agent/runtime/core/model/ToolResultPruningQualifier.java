package io.haifa.agent.runtime.core.model;

import io.haifa.agent.core.tool.ToolCall;
import io.haifa.agent.tool.api.FrozenToolBinding;

/**
 * Decides whether one historical Tool Call's result may be replaced by the fixed omission notice before a request is
 * serialized.
 *
 * <p>The qualifier is stateless and receives the Tool Binding resolved from the call's own Run for the current planning
 * operation, or {@code null} when no authoritative binding could be resolved. It must base its answer on those frozen
 * facts and exact tool identity, version and provider. A shortcut based only on a tool name, argument key or payload
 * size is intentionally not sufficient. A qualifier that qualifies nothing disables result pruning; there is no
 * implicit fallback to a built-in name list.
 */
@FunctionalInterface
public interface ToolResultPruningQualifier {

    boolean isPrunable(ToolCall call, FrozenToolBinding binding);

    /** Disables result pruning entirely. */
    static ToolResultPruningQualifier none() {
        return (call, binding) -> false;
    }
}
