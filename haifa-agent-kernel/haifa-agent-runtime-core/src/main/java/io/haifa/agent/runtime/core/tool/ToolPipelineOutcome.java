package io.haifa.agent.runtime.core.tool;

import io.haifa.agent.core.tool.ToolResult;
import io.haifa.agent.tool.api.FrozenToolBinding;
import java.util.Objects;

public sealed interface ToolPipelineOutcome
        permits ToolPipelineOutcome.Completed, ToolPipelineOutcome.ApprovalRequired {
    record Completed(ToolResult result) implements ToolPipelineOutcome {
        public Completed {
            Objects.requireNonNull(result, "result");
        }
    }

    record ApprovalRequired(FrozenToolBinding binding, String argumentsDigest, boolean reauthentication)
            implements ToolPipelineOutcome {
        public ApprovalRequired {
            Objects.requireNonNull(binding, "binding");
            if (argumentsDigest == null || argumentsDigest.isBlank()) {
                throw new IllegalArgumentException("argumentsDigest must not be blank");
            }
        }
    }
}
