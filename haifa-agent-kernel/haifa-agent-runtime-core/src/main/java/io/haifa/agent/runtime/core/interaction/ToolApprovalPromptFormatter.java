package io.haifa.agent.runtime.core.interaction;

import io.haifa.agent.core.tool.ToolCall;
import io.haifa.agent.tool.api.FrozenToolBinding;

@FunctionalInterface
public interface ToolApprovalPromptFormatter {
    String format(FrozenToolBinding binding, ToolCall call, boolean reauthentication);

    static ToolApprovalPromptFormatter defaultFormatter() {
        return (binding, call, reauthentication) ->
                (reauthentication ? "Reauthenticate and approve tool " : "Approve tool ")
                        + binding.alias().value() + " ("
                        + binding.coordinate().externalForm() + ")";
    }
}
