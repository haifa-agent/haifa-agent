package io.haifa.agent.runtime.core.model;

import io.haifa.agent.core.tool.ToolCall;

/**
 * Decides whether one historical Tool Call's result may be replaced by the fixed omission notice before a request is
 * serialized.
 *
 * <p>A qualifier must base its answer on the call's own frozen facts and exact tool identity, version and provider. A
 * shortcut based only on a tool name, argument key or payload size is intentionally not sufficient. A qualifier that
 * qualifies nothing disables result pruning; there is no implicit fallback to a built-in name list.
 */
@FunctionalInterface
public interface ToolResultPruningQualifier {

    boolean isPrunable(ToolCall call);

    /** Disables result pruning entirely. */
    static ToolResultPruningQualifier none() {
        return call -> false;
    }
}
