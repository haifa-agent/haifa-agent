package io.haifa.agent.sdk.contribution;

import io.haifa.agent.core.tool.ToolCall;
import io.haifa.agent.runtime.api.ApprovalPrompt;
import io.haifa.agent.tool.api.FrozenToolBinding;

/** Product-owned presentation of an exact Tool approval request. */
@FunctionalInterface
public interface ProductApprovalPromptFormatter {
    ApprovalPrompt format(FrozenToolBinding binding, ToolCall call, boolean reauthentication);

    static ProductApprovalPromptFormatter defaultFormatter() {
        return (binding, call, reauthentication) ->
                ApprovalPrompt.of((reauthentication ? "Reauthenticate and approve tool " : "Approve tool ")
                        + binding.alias().value() + " ("
                        + binding.coordinate().externalForm() + ")");
    }
}
