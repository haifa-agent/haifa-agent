package io.haifa.agent.execution.core.tool;

import io.haifa.agent.execution.api.ExecutionCommand;
import io.haifa.agent.execution.api.ExecutionInput;
import java.util.List;

/** Trusted conversion from an approved language/source pair to process argv plus bounded stdin. */
public interface ScriptRuntimeAdapter {
    String language();

    String executable();

    PreparedScript prepare(String content, List<String> arguments);

    record PreparedScript(ExecutionCommand command, ExecutionInput input) {}
}
