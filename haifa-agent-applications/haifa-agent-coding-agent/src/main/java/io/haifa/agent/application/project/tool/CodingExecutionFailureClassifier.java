package io.haifa.agent.application.project.tool;

import io.haifa.agent.execution.api.ExecutionResult;
import io.haifa.agent.execution.api.ExecutionStatus;
import java.util.Locale;

/** Small product-owned mapping from stable execution outcomes to safe semantic failure fields. */
final class CodingExecutionFailureClassifier {
    private CodingExecutionFailureClassifier() {}

    static Classification classify(ExecutionResult result, String boundedOutput) {
        if (result.status() == ExecutionStatus.TIMED_OUT) {
            return new Classification("TIMEOUT", "TIMEOUT", "PROCESS");
        }
        if (result.status() == ExecutionStatus.CANCELLED) {
            return new Classification("CANCELLED", "CANCELLED", "PROCESS");
        }
        if (result.status() == ExecutionStatus.OUTPUT_LIMIT_EXCEEDED) {
            return new Classification("OUTPUT_LIMIT", "OUTPUT_LIMIT_EXCEEDED", "OUTPUT");
        }
        if (result.status() == ExecutionStatus.UNKNOWN) {
            return new Classification("OUTCOME_UNKNOWN", "OUTCOME_UNKNOWN", "PROCESS");
        }
        String providerCode = result.optionalFailure()
                .map(value -> value.code().toUpperCase(Locale.ROOT))
                .orElse("");
        String output = boundedOutput.toLowerCase(Locale.ROOT);
        if (providerCode.contains("NETWORK")
                || output.contains("network is unreachable")
                || output.contains("temporary failure in name resolution")) {
            return new Classification("NETWORK_DENIED", "NETWORK_UNAVAILABLE", "NETWORK");
        }
        if (output.contains("operation not permitted")
                || output.contains("permission denied")
                || output.contains("read-only file system")) {
            String resource = output.contains("tmp")
                            || output.contains("temporary")
                            || output.contains("go-build")
                            || output.contains("gocache")
                    ? "TEMPORARY_DIRECTORY"
                    : "FILESYSTEM";
            return new Classification("FILESYSTEM_DENIED", "FILESYSTEM_ACCESS_DENIED", resource);
        }
        if (output.contains("command not found")
                || output.contains("commandnotfoundexception")
                || output.contains("is not recognized as the name of a cmdlet")
                || output.contains("is not recognized as an internal or external command")
                || output.contains("no such file or directory")
                || output.contains("module not found")
                || output.contains("cannot find package")) {
            return new Classification("DEPENDENCY_UNAVAILABLE", "DEPENDENCY_UNAVAILABLE", "TOOLCHAIN");
        }
        if (output.contains("invalid argument") || output.contains("unknown option")) {
            return new Classification("INVALID_INPUT", "COMMAND_INVALID_INPUT", "COMMAND");
        }
        return new Classification("COMMAND_FAILED", providerCode.isBlank() ? "NON_ZERO_EXIT" : providerCode, "COMMAND");
    }

    record Classification(String category, String stableFailureCode, String resourceClass) {}
}
