package io.haifa.agent.execution.core.tool;

import io.haifa.agent.execution.api.ExecutionCommand;
import io.haifa.agent.execution.api.ExecutionInput;
import java.util.List;

/** Trusted conversion from an approved language/source pair to process argv plus bounded stdin. */
public interface ScriptRuntimeAdapter {
    String language();

    String executable();

    PreparedScript prepare(String content, List<String> arguments);

    /** Whether {@link #prepare} is available for standard-input script execution. */
    default boolean supportsStandardInput() {
        return true;
    }

    /** Whether this runtime executes a frozen package entry file instead of piped standard input. */
    default boolean supportsFileExecution() {
        return false;
    }

    /**
     * Builds the argv that executes one materialized package entry file relative to the Workspace
     * root. The path segments are the materialized directory plus the entry script; the adapter never
     * accepts an absolute path. Models cannot reach this method: it belongs to the trusted Skill
     * script envelope only.
     */
    default PreparedScript prepareFile(String workspaceRelativeEntryPath, List<String> arguments) {
        throw new UnsupportedOperationException(
                "script runtime " + language() + " does not support entry-file execution");
    }

    record PreparedScript(ExecutionCommand command, ExecutionInput input) {}
}
