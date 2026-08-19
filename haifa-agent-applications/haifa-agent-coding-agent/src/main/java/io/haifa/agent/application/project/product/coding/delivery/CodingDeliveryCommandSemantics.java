package io.haifa.agent.application.project.product.coding.delivery;

import io.haifa.agent.execution.core.command.SystemGitCliCommandClassifier;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Minimal delivery transaction semantics layered on top of the conservative system CLI classifier. */
public final class CodingDeliveryCommandSemantics {
    private CodingDeliveryCommandSemantics() {}

    public static Action action(String command, SystemGitCliCommandClassifier.Classification classification) {
        return switch (classification.reasonCode()) {
            case "GIT_STAGE" -> Action.STAGE;
            case "GIT_COMMIT" -> Action.COMMIT;
            case "GIT_PUSH" -> Action.PUSH;
            case "GH_PR_CREATE", "GH_PR_UPDATE" -> Action.PULL_REQUEST;
            default -> compoundAction(command);
        };
    }

    public static Verification verification(
            String command, SystemGitCliCommandClassifier.Classification classification) {
        String lower = command.toLowerCase(Locale.ROOT);
        if (classification.reasonCode().equals("GIT_STATUS")) return Verification.STATUS;
        if (classification.reasonCode().equals("GIT_DIFF")
                && (lower.contains("--cached") || lower.contains("--staged"))) {
            return Verification.STAGED_DIFF;
        }
        if (classification.reasonCode().equals("GIT_REV_PARSE") && lower.contains("--show-toplevel")) {
            return Verification.REPOSITORY_ROOT;
        }
        if ((classification.reasonCode().equals("GIT_BRANCH") && lower.contains("--show-current"))
                || (classification.reasonCode().equals("GIT_SYMBOLIC_REF") && lower.contains("--short"))) {
            return Verification.BRANCH;
        }
        if (classification.reasonCode().equals("GIT_REV_PARSE") && lower.matches(".*\\bhead\\b.*")) {
            return Verification.HEAD;
        }
        if (classification.reasonCode().equals("GIT_NETWORK_READ") && lower.contains("ls-remote")) {
            return Verification.REMOTE_REF;
        }
        if (classification.reasonCode().equals("GH_REMOTE_READ") && lower.matches(".*\\bpr\\s+view\\b.*")) {
            return Verification.PULL_REQUEST;
        }
        return Verification.NONE;
    }

    public static boolean direct(SystemGitCliCommandClassifier.Classification classification) {
        return !classification.reasonCode().equals("COMPOUND_OR_WRAPPED_COMMAND")
                && !classification.reasonCode().equals("COMMAND_ENVIRONMENT_WRAPPER")
                && !classification.reasonCode().equals("UNKNOWN_EXECUTABLE_WRAPPER");
    }

    public static boolean exactStagePaths(String command) {
        List<String> argv = tokenize(command);
        int stage = indexOf(argv, "add");
        if (stage < 1 || !base(argv.get(stage - 1)).equals("git")) return false;
        List<String> paths = argv.subList(stage + 1, argv.size());
        if (paths.isEmpty()) return false;
        boolean separatorSeen = false;
        boolean concretePathSeen = false;
        for (String value : paths) {
            if (value.equals("--") && !separatorSeen) {
                separatorSeen = true;
                continue;
            }
            if (!separatorSeen && value.startsWith("-")) return false;
            if (value.equals(".")
                    || value.startsWith(":")
                    || value.contains("*")
                    || value.contains("?")
                    || value.contains("[")) return false;
            concretePathSeen = true;
        }
        return concretePathSeen;
    }

    public static boolean explicitPushTarget(String command) {
        List<String> argv = tokenize(command);
        int push = indexOf(argv, "push");
        if (push < 1 || !base(argv.get(push - 1)).equals("git")) return false;
        List<String> positional = argv.subList(push + 1, argv.size()).stream()
                .filter(value -> !value.startsWith("-"))
                .toList();
        return positional.size() >= 2 && !positional.get(1).equals(":");
    }

    public static boolean explicitDevBase(String command) {
        List<String> argv = tokenize(command);
        for (int index = 0; index + 1 < argv.size(); index++) {
            if ((argv.get(index).equals("--base") || argv.get(index).equals("-B"))
                    && argv.get(index + 1).equals("dev")) return true;
        }
        return argv.stream().anyMatch(value -> value.equals("--base=dev"));
    }

    private static Action compoundAction(String command) {
        String lower = command.toLowerCase(Locale.ROOT);
        if (lower.matches("(?s).*\\bgh(?:\\.exe)?\\s+pr\\s+(?:create|edit|close|reopen|ready|review|comment)\\b.*")) {
            return Action.PULL_REQUEST;
        }
        if (lower.matches("(?s).*\\bgit(?:\\.exe)?\\s+push\\b.*")) return Action.PUSH;
        if (lower.matches("(?s).*\\bgit(?:\\.exe)?\\s+commit\\b.*")) return Action.COMMIT;
        if (lower.matches("(?s).*\\bgit(?:\\.exe)?\\s+add\\b.*")) return Action.STAGE;
        return Action.NONE;
    }

    private static int indexOf(List<String> values, String expected) {
        for (int index = 0; index < values.size(); index++) {
            if (values.get(index).equalsIgnoreCase(expected)) return index;
        }
        return -1;
    }

    private static List<String> tokenize(String command) {
        List<String> result = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean single = false;
        boolean doub = false;
        for (int index = 0; index < command.length(); index++) {
            char value = command.charAt(index);
            if (value == '\'' && !doub) single = !single;
            else if (value == '"' && !single) doub = !doub;
            else if (Character.isWhitespace(value) && !single && !doub) {
                if (!current.isEmpty()) {
                    result.add(current.toString());
                    current.setLength(0);
                }
            } else current.append(value);
        }
        if (single || doub) return List.of();
        if (!current.isEmpty()) result.add(current.toString());
        return List.copyOf(result);
    }

    private static String base(String value) {
        String normalized = value.replace('\\', '/');
        int slash = normalized.lastIndexOf('/');
        String name = slash < 0 ? normalized : normalized.substring(slash + 1);
        String lower = name.toLowerCase(Locale.ROOT);
        return lower.endsWith(".exe") ? lower.substring(0, lower.length() - 4) : lower;
    }

    public enum Action {
        NONE,
        STAGE,
        COMMIT,
        PUSH,
        PULL_REQUEST
    }

    public enum Verification {
        NONE,
        STATUS,
        REPOSITORY_ROOT,
        BRANCH,
        STAGED_DIFF,
        HEAD,
        REMOTE_REF,
        PULL_REQUEST
    }
}
