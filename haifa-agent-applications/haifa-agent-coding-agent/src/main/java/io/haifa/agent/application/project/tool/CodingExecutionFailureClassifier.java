package io.haifa.agent.application.project.tool;

import io.haifa.agent.execution.api.ExecutionResult;
import io.haifa.agent.execution.api.ExecutionStatus;
import io.haifa.agent.execution.core.command.SystemGitCliCommandClassifier;
import java.util.Locale;

/** Small product-owned mapping from stable execution outcomes to safe semantic failure fields. */
final class CodingExecutionFailureClassifier {
    private CodingExecutionFailureClassifier() {}

    static Classification classify(
            ExecutionResult result,
            String boundedOutput,
            SystemGitCliCommandClassifier.Classification commandClassification) {
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
            return new Classification(
                    "OUTCOME_UNKNOWN",
                    "OUTCOME_UNKNOWN",
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
            boolean permissionEligible = commandClassification.target() != SystemGitCliCommandClassifier.Target.OTHER;
            return new Classification(
                    "NETWORK_DENIED",
                    permissionEligible ? "NETWORK_PERMISSION_REQUIRED" : "NETWORK_UNAVAILABLE",
                    "NETWORK",
                    permissionEligible
                            ? "If request_permissions is disclosed, request one exact retry of this Git/GH command; otherwise ask the user to restore network access."
                            : "Check the trusted host network and proxy configuration, then retry if authorized.");
        }
        if (output.contains("permission denied (publickey)")
                || output.contains("could not read username")
                || output.contains("authentication failed")
                || output.contains("not logged into any github hosts")) {
            String code =
                    switch (commandClassification.target()) {
                        case GIT -> "GIT_AUTHENTICATION_UNAVAILABLE";
                        case GITHUB -> "GH_AUTHENTICATION_UNAVAILABLE";
                        case OTHER -> "HOST_AUTHENTICATION_UNAVAILABLE";
                    };
            String action = commandClassification.target() == SystemGitCliCommandClassifier.Target.GITHUB
                    ? "Run gh auth login in your system terminal, then retry the command."
                    : "Verify the current OS user's Git credential helper or SSH agent, then retry the command.";
            return new Classification("AUTHENTICATION_UNAVAILABLE", code, "AUTHENTICATION", action);
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
                    "Use an authorized workspace path or request the required permission from the user.");
        }
        if (output.contains("command not found")
                || output.contains("commandnotfoundexception")
                || output.contains("is not recognized as the name of a cmdlet")
                || output.contains("is not recognized as an internal or external command")
                || output.contains("no such file or directory")
                || output.contains("module not found")
                || output.contains("cannot find package")) {
            String code =
                    switch (commandClassification.target()) {
                        case GIT -> "GIT_CLI_UNAVAILABLE";
                        case GITHUB -> "GH_CLI_UNAVAILABLE";
                        case OTHER -> "DEPENDENCY_UNAVAILABLE";
                    };
            String action =
                    switch (commandClassification.target()) {
                        case GIT -> "Install Git and make git available on the trusted host PATH.";
                        case GITHUB -> "Install GitHub CLI and make gh available on the trusted host PATH.";
                        case OTHER -> "Install the missing dependency or choose an available equivalent command.";
                    };
            return new Classification("DEPENDENCY_UNAVAILABLE", code, "TOOLCHAIN", action);
        }
        if (output.contains("invalid argument") || output.contains("unknown option")) {
            return new Classification(
                    "INVALID_INPUT", "COMMAND_INVALID_INPUT", "COMMAND", "Correct the command arguments and retry.");
        }
        if (commandClassification.target() == SystemGitCliCommandClassifier.Target.GIT
                && (output.contains("bad revision")
                        || output.contains("unknown revision")
                        || output.contains("ambiguous argument")
                        || output.contains("not a valid object name")
                        || output.contains("invalid object name"))) {
            return new Classification(
                    "INVALID_INPUT",
                    "GIT_REVISION_NOT_FOUND",
                    "REPOSITORY_REF",
                    "Read the authoritative repository refs with git status, branch, or rev-parse before retrying once.");
        }
        return new Classification(
                "COMMAND_FAILED",
                providerCode.isBlank() ? "NON_ZERO_EXIT" : providerCode,
                "COMMAND",
                "Review the bounded command output and choose the smallest corrective action.");
    }

    record Classification(String category, String stableFailureCode, String resourceClass, String action) {}
}
