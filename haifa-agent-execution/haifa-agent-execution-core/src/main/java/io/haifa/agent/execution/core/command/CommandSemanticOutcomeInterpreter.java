package io.haifa.agent.execution.core.command;

import io.haifa.agent.execution.api.ExecutionStatus;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/** Product-neutral, bounded exit-code semantics for commands with documented non-zero variants. */
public final class CommandSemanticOutcomeInterpreter {
    public static final String VERSION = "2";
    private static final Set<String> RIPGREP_EXECUTABLES = Set.of("rg", "rg.exe", "ripgrep", "ripgrep.exe");

    private CommandSemanticOutcomeInterpreter() {}

    public static Interpretation interpret(String command, ExecutionStatus status, Integer exitCode) {
        Objects.requireNonNull(command, "command must not be null");
        Objects.requireNonNull(status, "status must not be null");
        if (status == ExecutionStatus.SUCCEEDED) {
            return new Interpretation(CommandSemanticOutcome.SUCCEEDED, "COMMAND_EXIT_ZERO");
        }
        if (status == ExecutionStatus.PROCESS_LIMIT_EXCEEDED) {
            return new Interpretation(CommandSemanticOutcome.COMMAND_FAILED, "PROCESS_LIMIT_EXCEEDED");
        }
        if (status == ExecutionStatus.TIMED_OUT
                || status == ExecutionStatus.CANCELLED
                || status == ExecutionStatus.UNKNOWN
                || status == ExecutionStatus.OUTPUT_LIMIT_EXCEEDED
                || exitCode == null) {
            return new Interpretation(CommandSemanticOutcome.OUTCOME_UNKNOWN, "EXECUTION_OUTCOME_UNKNOWN");
        }
        var classification = SystemGitCliCommandClassifier.classify(command);
        if (exitCode == 1
                && classification.target() == SystemGitCliCommandClassifier.Target.GIT
                && classification.operation() == SystemGitCliCommandClassifier.Operation.DIFF
                && hasOption(command, "--exit-code", "--no-index")) {
            return new Interpretation(CommandSemanticOutcome.EXPECTED_VARIANT, "DIFFERENCES_FOUND");
        }
        if (exitCode == 1
                && classification.target() == SystemGitCliCommandClassifier.Target.GIT
                && classification.reasonCode().equals("GIT_GREP")) {
            return new Interpretation(CommandSemanticOutcome.EMPTY_RESULT, "NO_MATCHES");
        }
        if (exitCode == 1 && isRipgrep(command)) {
            return new Interpretation(CommandSemanticOutcome.EMPTY_RESULT, "NO_MATCHES");
        }
        return new Interpretation(CommandSemanticOutcome.COMMAND_FAILED, "COMMAND_NONZERO_EXIT");
    }

    private static boolean isRipgrep(String command) {
        String remaining = command.stripLeading();
        if (remaining.startsWith("&")) remaining = remaining.substring(1).stripLeading();
        if (remaining.isEmpty()) return false;
        String executable;
        char first = remaining.charAt(0);
        if (first == '\"' || first == '\'') {
            int closingQuote = remaining.indexOf(first, 1);
            if (closingQuote < 0) return false;
            executable = remaining.substring(1, closingQuote);
        } else {
            int separator = 0;
            while (separator < remaining.length() && !Character.isWhitespace(remaining.charAt(separator))) {
                separator++;
            }
            executable = remaining.substring(0, separator);
        }
        String normalized = executable.replace('\\', '/');
        int lastSlash = normalized.lastIndexOf('/');
        String name = normalized.substring(lastSlash + 1).toLowerCase(Locale.ROOT);
        return RIPGREP_EXECUTABLES.contains(name);
    }

    private static boolean hasOption(String command, String... options) {
        for (String option : options) {
            if (command.matches(
                    "(?is).*?(?:^|\\s)[\\\"']?" + java.util.regex.Pattern.quote(option) + "[\\\"']?(?:\\s|$).*")) {
                return true;
            }
        }
        return false;
    }

    public record Interpretation(CommandSemanticOutcome outcome, String reasonCode) {
        public Interpretation {
            outcome = Objects.requireNonNull(outcome, "outcome must not be null");
            if (reasonCode == null || !reasonCode.matches("[A-Z][A-Z0-9_]{2,127}")) {
                throw new IllegalArgumentException("reasonCode must be a stable uppercase code");
            }
        }

        public boolean successfulToolResult() {
            return outcome == CommandSemanticOutcome.SUCCEEDED
                    || outcome == CommandSemanticOutcome.EXPECTED_VARIANT
                    || outcome == CommandSemanticOutcome.EMPTY_RESULT;
        }
    }
}
