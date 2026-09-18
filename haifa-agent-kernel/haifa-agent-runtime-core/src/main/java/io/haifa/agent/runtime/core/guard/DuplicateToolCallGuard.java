package io.haifa.agent.runtime.core.guard;

import io.haifa.agent.core.tool.RuntimeIdempotencyKey;
import io.haifa.agent.runtime.core.decision.ToolCallDecision;
import io.haifa.agent.runtime.core.decision.ToolRequest;
import java.util.HashSet;

/** Rejects ambiguous batches that reuse an idempotency key within one model decision. */
public final class DuplicateToolCallGuard {
    public void check(ToolCallDecision decision) {
        var keys = new HashSet<RuntimeIdempotencyKey>();
        for (ToolRequest request : decision.requests()) {
            if (!keys.add(request.idempotencyKey())) {
                throw new IllegalArgumentException("duplicate tool request idempotency key");
            }
        }
    }
}
