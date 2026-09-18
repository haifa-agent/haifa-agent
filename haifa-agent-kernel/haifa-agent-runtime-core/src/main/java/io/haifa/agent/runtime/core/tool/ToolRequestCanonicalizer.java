package io.haifa.agent.runtime.core.tool;

import io.haifa.agent.core.run.AgentRun;
import io.haifa.agent.runtime.core.decision.ToolRequest;
import io.haifa.agent.tool.api.FrozenToolBinding;

/** Produces the single canonical Tool request shared by policy, approval, recovery, and invocation. */
@FunctionalInterface
public interface ToolRequestCanonicalizer {
    ToolRequest canonicalize(AgentRun run, FrozenToolBinding binding, ToolRequest request);

    static ToolRequestCanonicalizer identity() {
        return (run, binding, request) -> request;
    }
}
