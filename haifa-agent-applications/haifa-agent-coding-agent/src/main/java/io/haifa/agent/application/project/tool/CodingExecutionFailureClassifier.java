package io.haifa.agent.application.project.tool;

import io.haifa.agent.execution.api.ExecutionResult;
import io.haifa.agent.execution.api.ExecutionStatus;
import java.util.Locale;

/** Small product-owned mapping from stable execution outcomes to safe semantic failure fields. */
final class CodingExecutionFailureClassifier {
    private CodingExecutionFailureClassifier() {}

    static Classification classify(ExecutionResult result, String boundedOutput) {
        if (result.status() == ExecutionStatus.TIMED_OUT) {
            return new Classification("TIMEOUT", "TIMEOUT", "PROCESS", "Verify state before retrying the command.");
        }
        if (result.status() == ExecutionStatus.CANCELLED) {
            return new Classification("CANCELLED", "CANCELLED", "PROCESS", "Retry only if the task still requires it.");
        }
        if (result.status() == ExecutionStatus.OUTPUT_LIMIT_EXCEEDED) {
            return new Classification(
                    "OUTPUT_LIMIT",
                    "OUTPUT_LIMIT_EXCEEDED",
                    "OUTPUT",
                    "Use a machine-readable command with narrower fields or a smaller result limit.");
        }
        if (result.status() == ExecutionStatus.PROCESS_LIMIT_EXCEEDED) {
            return new Classification(
                    "PROCESS_LIMIT",
                    "PROCESS_LIMIT_EXCEEDED",
                    "PROCESS",
                    "Use a narrower test target or reduce command concurrency before retrying.");
        }
        if (result.status() == ExecutionStatus.UNKNOWN) {
            String stableCode = result.optionalFailure()
                    .map(value -> value.code().toUpperCase(Locale.ROOT))
                    .orElse("OUTCOME_UNKNOWN");
            return new Classification(
                    "OUTCOME_UNKNOWN",
                    stableCode,
                    "PROCESS",
                    "Query the smallest read-only local or remote fact before considering a retry.");
        }
        String providerCode = result.optionalFailure()
                .map(value -> value.code().toUpperCase(Locale.ROOT))
                .orElse("");
        String output = boundedOutput.toLowerCase(Locale.ROOT);
        if (providerCode.contains("NETWORK")
                || output.contains("network is unreachable")
                || output.contains("temporary failure in name resolution")
                || output.contains("could not resolve host")
                || output.contains("name or service not known")
                || output.contains("failed to connect")
                || output.contains("couldn't connect to server")) {
            return new Classification(
                    "NETWORK_DENIED",
                    "NETWORK_UNAVAILABLE",
                    "NETWORK",
                    "Check the trusted host network and proxy configuration, then retry if authorized.");
        }
        if (output.contains("permission denied (publickey)")
                || output.contains("could not read username")
                || output.contains("authentication failed")
                || output.contains("not logged into any github hosts")) {
            return new Classification(
                    "AUTHENTICATION_UNAVAILABLE",
                    "HOST_AUTHENTICATION_UNAVAILABLE",
                    "AUTHENTICATION",
                    "Verify the current OS user's authentication configuration outside the model context, then retry.");
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
            return new Classification(
                    "FILESYSTEM_DENIED",
                    "FILESYSTEM_ACCESS_DENIED",
                    resource,
                    "Use an authorized workspace path or request the required workspace access from the user.");
        }
        if (isConfirmedMissingExecutable(providerCode)) {
            return new Classification(
                    "DEPENDENCY_UNAVAILABLE",
                    providerCode,
                    "TOOLCHAIN",
                    "Install the missing dependency or choose an available equivalent command.");
        }
        if (output.contains("invalid argument") || output.contains("unknown option")) {
            return new Classification(
                    "INVALID_INPUT", "COMMAND_INVALID_INPUT", "COMMAND", "Correct the command arguments and retry.");
        }
        return new Classification(
                "COMMAND_FAILED",
                providerCode.isBlank() ? "EXECUTION_FAILED" : providerCode,
                "COMMAND",
                "Review the bounded command output and choose the smallest corrective action.");
    }

    private static boolean isConfirmedMissingExecutable(String providerCode) {
        return providerCode.equals("EXECUTABLE_NOT_FOUND")
                || providerCode.equals("TOOLCHAIN_NOT_FOUND")
                || providerCode.equals("PROCESS_EXECUTABLE_NOT_FOUND");
    }

    record Classification(String category, String stableFailureCode, String resourceClass, String action) {}
}
