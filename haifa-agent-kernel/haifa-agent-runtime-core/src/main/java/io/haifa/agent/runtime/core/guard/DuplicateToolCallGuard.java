package io.haifa.agent.runtime.core.guard;

import io.haifa.agent.core.run.AgentRun;
import io.haifa.agent.runtime.core.decision.ToolCallDecision;
import io.haifa.agent.runtime.core.decision.ToolRequest;
import io.haifa.agent.runtime.core.storage.RuntimeStateRepository;
import java.util.HashSet;
import java.util.Objects;

/** Rejects ambiguous batches that reuse an idempotency key within one model decision. */
public final class DuplicateToolCallGuard {
    private final RuntimeStateRepository state;

    public DuplicateToolCallGuard(RuntimeStateRepository state) {
        this.state = Objects.requireNonNull(state);
    }

    public void check(AgentRun run, ToolCallDecision decision) {
        var keys = new HashSet<String>();
        for (ToolRequest request : decision.requests()) {
            if (!keys.add(request.idempotencyKey())) {
                throw new IllegalArgumentException("duplicate tool request idempotency key");
            }
        }
        for (ToolRequest request : decision.requests()) {
            String requested = fingerprint(request.toolName(), request.toolVersion(), request.arguments());
            long recentRepeats = state.toolCalls(run.id()).stream()
                    .filter(call -> call.status() == io.haifa.agent.core.tool.ToolCallStatus.COMPLETED)
                    .map(call -> fingerprint(call.toolName(), call.toolVersion(), call.arguments()))
                    .toList()
                    .reversed()
                    .stream()
                    .takeWhile(requested::equals)
                    .count();
            if (recentRepeats >= 2) {
                throw new IllegalStateException("repeated semantic tool call detected");
            }
        }
    }

    private static String fingerprint(String name, String version, io.haifa.agent.core.tool.ToolArguments arguments) {
        return name + "@" + version + ":" + arguments.schemaId() + ":" + new java.util.TreeMap<>(arguments.values());
    }
}
