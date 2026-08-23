package io.haifa.agent.tool.api;

import io.haifa.agent.core.tool.ToolResult;
import java.util.Objects;
import java.util.Optional;

/** Result of a read-only provider reconciliation; it never authorizes or performs a replay. */
public record ToolReconciliation(ToolReconciliationStatus status, String reasonCode, Optional<ToolResult> result) {
    public ToolReconciliation {
        status = Objects.requireNonNull(status, "status must not be null");
        reasonCode = ToolReconciliationRecord.stableCode(reasonCode);
        result = Objects.requireNonNull(result, "result must not be null");
        if ((status == ToolReconciliationStatus.RESOLVED) != result.isPresent()) {
            throw new IllegalArgumentException("only resolved reconciliation carries a tool result");
        }
    }

    public static ToolReconciliation resolved(ToolResult result, String reasonCode) {
        return new ToolReconciliation(
                ToolReconciliationStatus.RESOLVED, reasonCode, Optional.of(Objects.requireNonNull(result)));
    }

    public static ToolReconciliation stillUnknown(String reasonCode) {
        return new ToolReconciliation(ToolReconciliationStatus.STILL_UNKNOWN, reasonCode, Optional.empty());
    }

    public static ToolReconciliation unsupported() {
        return new ToolReconciliation(
                ToolReconciliationStatus.UNSUPPORTED, "RECONCILIATION_UNSUPPORTED", Optional.empty());
    }
}
