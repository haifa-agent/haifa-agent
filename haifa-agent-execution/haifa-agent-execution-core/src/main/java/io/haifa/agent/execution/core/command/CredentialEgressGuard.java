package io.haifa.agent.execution.core.command;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Narrow, fail-closed guard for the confirmed commands that could read, echo, override, or
 * redirect host authentication material into model-visible output.
 *
 * <p>This is deliberately not a Git/GitHub command grammar. It does not classify targets,
 * subcommands, operations, risk, or business effects. General {@code git}, {@code gh}, wrapper,
 * and customer-script semantics belong to the model and the generic execution path. The guard
 * recognizes only the exact credential-disclosure and credential-override shapes that would
 * otherwise place a host secret in model context or mutate the host authentication state.</p>
 *
 * <p>Recognition is lexical and command-shaped: protected environment names are only overrides as
 * actual assignments in executable position ({@code NAME=value cmd}, {@code env}/{@code export},
 * cmd {@code set NAME=value}, or PowerShell {@code $env:NAME=value}), and Git/GitHub credential
 * commands only count when they are invoked as the command word of a shell segment. Textual
 * occurrences such as {@code echo "HOME=value"} or {@code echo "gh auth token"} are arguments, not
 * executions, and stay on the generic path.</p>
 *
 * <p>Every rule here is covered by a regression test proving that a protected credential cannot
 * reach model-visible output. When a general Credential egress boundary exists, this guard can be
 * replaced by it; until then these paths stay fail closed.</p>
 */
public final class CredentialEgressGuard {
    public static final String ENVIRONMENT_OVERRIDE_CODE = "AUTHENTICATION_ENVIRONMENT_OVERRIDE";
    public static final String GIT_CONFIG_OVERRIDE_CODE = "GIT_AUTHENTICATION_CONFIG_OVERRIDE";
    public static final String GIT_CREDENTIAL_PROTOCOL_CODE = "GIT_CREDENTIAL_PROTOCOL_DENIED";
    public static final String GITHUB_TOKEN_DISCLOSURE_CODE = "GH_AUTH_TOKEN_DISCLOSURE_DENIED";
    public static final String GITHUB_AUTH_STATE_CODE = "GH_AUTHENTICATION_MUTATION_OR_DISCLOSURE";

    private static final Set<String> PROTECTED_ENVIRONMENT = Set.of(
            "GH_TOKEN",
            "GITHUB_TOKEN",
            "GH_CONFIG_DIR",
            "HOME",
            "USERPROFILE",
            "APPDATA",
            "XDG_CONFIG_HOME",
            "SSH_AUTH_SOCK",
            "GIT_CONFIG_GLOBAL",
            "GIT_CONFIG_SYSTEM",
            "GIT_SSH_COMMAND",
            "GIT_ASKPASS",
            "SSH_ASKPASS");
    private static final Set<String> ASSIGNMENT_COMMANDS = Set.of("env", "export", "set");
    private static final Set<String> CREDENTIAL_CONFIG_KEYS =
            Set.of("credential.", "extraheader", "sshcommand", "credentialhelper");

    private CredentialEgressGuard() {}

    /**
     * Returns a stable reason code when the command is a confirmed credential read, echo,
     * override, or redirect path; empty when the command is handled by the generic execution path.
     */
    public static Optional<String> rejectionCode(String command) {
        if (command == null || command.isBlank() || command.indexOf('\0') >= 0) {
            return Optional.empty();
        }
        List<List<String>> segments = segments(command);
        if (overridesProtectedEnvironment(segments)) {
            return Optional.of(ENVIRONMENT_OVERRIDE_CODE);
        }
        if (anySegment(
                segments,
                words -> isCommand(words, "gh")
                        && words.size() >= 3
                        && words.get(1).equals("auth")
                        && words.get(2).equals("status")
                        && words.subList(3, words.size()).contains("--show-token"))) {
            return Optional.of(GITHUB_TOKEN_DISCLOSURE_CODE);
        }
        if (anySegment(
                segments,
                words -> isCommand(words, "gh")
                        && words.size() >= 2
                        && words.get(1).equals("auth")
                        && (words.size() < 3 || !words.get(2).equals("status")))) {
            return Optional.of(GITHUB_AUTH_STATE_CODE);
        }
        if (anySegment(
                segments,
                words -> isCommand(words, "git")
                        && words.size() >= 2
                        && (words.get(1).equals("credential") || words.get(1).startsWith("credential-")))) {
            return Optional.of(GIT_CREDENTIAL_PROTOCOL_CODE);
        }
        if (overridesGitCredentialConfig(segments)) {
            return Optional.of(GIT_CONFIG_OVERRIDE_CODE);
        }
        return Optional.empty();
    }

    public static boolean rejects(String command) {
        return rejectionCode(command).isPresent();
    }

    private static boolean overridesProtectedEnvironment(List<List<String>> segments) {
        for (List<String> words : segments) {
            if (words.isEmpty()) continue;
            if (hasPowerShellAssignment(words)) return true;
            String head = commandHead(words.getFirst());
            if (ASSIGNMENT_COMMANDS.contains(head)
                    && hasProtectedAssignment(words.subList(1, words.size()), head.equals("set"))) {
                return true;
            }
            int index = 0;
            while (index < words.size() && isAssignment(words.get(index))) {
                if (isProtectedAssignment(words.get(index))) return true;
                index++;
            }
        }
        return false;
    }

    private static boolean hasPowerShellAssignment(List<String> words) {
        for (int index = 0; index < words.size(); index++) {
            String lower = words.get(index).toLowerCase(Locale.ROOT);
            if (!lower.startsWith("$env:")) continue;
            int equals = lower.indexOf('=');
            String name;
            if (equals > "$env:".length()) {
                name = lower.substring("$env:".length(), equals);
            } else if (equals < 0
                    && index + 1 < words.size()
                    && words.get(index + 1).equals("=")) {
                name = lower.substring("$env:".length());
            } else {
                continue;
            }
            if (PROTECTED_ENVIRONMENT.contains(name.toUpperCase(Locale.ROOT))) return true;
        }
        return false;
    }

    private static boolean hasProtectedAssignment(List<String> words, boolean stripQuotes) {
        for (String word : words) {
            String candidate = stripQuotes ? stripSurroundingQuotes(word) : word;
            if (isAssignment(candidate) && isProtectedAssignment(candidate)) return true;
        }
        return false;
    }

    private static boolean overridesGitCredentialConfig(List<List<String>> segments) {
        for (List<String> words : segments) {
            if (!isCommand(words, "git")) continue;
            for (int index = 1; index + 1 < words.size(); index++) {
                String option = words.get(index);
                String value;
                if (option.equals("-c")) {
                    value = words.get(index + 1);
                } else if (option.startsWith("--config-env=")) {
                    value = option.substring("--config-env=".length());
                } else if (option.equals("--config-env")) {
                    value = words.get(index + 1);
                } else {
                    continue;
                }
                if (isCredentialConfigKey(value)) return true;
            }
        }
        return false;
    }

    private static boolean isCredentialConfigKey(String value) {
        int equals = value.indexOf('=');
        String key = (equals >= 0 ? value.substring(0, equals) : value)
                .toLowerCase(Locale.ROOT)
                .replace("\"", "")
                .replace("'", "");
        return CREDENTIAL_CONFIG_KEYS.stream().anyMatch(key::contains);
    }

    private static boolean anySegment(List<List<String>> segments, Predicate<List<String>> predicate) {
        return segments.stream().anyMatch(predicate);
    }

    private static boolean isCommand(List<String> words, String name) {
        return !words.isEmpty() && commandHead(words.getFirst()).equals(name);
    }

    private static boolean isAssignment(String token) {
        int equals = token.indexOf('=');
        if (equals <= 0) return false;
        for (int index = 0; index < equals; index++) {
            char value = token.charAt(index);
            if (!(Character.isLetterOrDigit(value) || value == '_')) return false;
        }
        return true;
    }

    private static boolean isProtectedAssignment(String token) {
        int equals = token.indexOf('=');
        if (equals <= 0) return false;
        return PROTECTED_ENVIRONMENT.contains(token.substring(0, equals).toUpperCase(Locale.ROOT));
    }

    private static String stripSurroundingQuotes(String token) {
        if (token.length() >= 2 && token.charAt(0) == '"' && token.charAt(token.length() - 1) == '"') {
            return token.substring(1, token.length() - 1);
        }
        return token;
    }

    private static String commandHead(String token) {
        String lower = token.toLowerCase(Locale.ROOT);
        int separator = Math.max(lower.lastIndexOf('/'), lower.lastIndexOf('\\'));
        String name = separator >= 0 ? lower.substring(separator + 1) : lower;
        return name.endsWith(".exe") ? name.substring(0, name.length() - 4) : name;
    }

    /** Splits the command text into command segments of whitespace-separated words, honors quotes. */
    private static List<List<String>> segments(String command) {
        List<List<String>> segments = new ArrayList<>();
        List<String> words = new ArrayList<>();
        StringBuilder token = new StringBuilder();
        boolean pending = false;
        char quote = 0;
        for (int index = 0; index < command.length(); index++) {
            char value = command.charAt(index);
            if (quote != 0) {
                token.append(value);
                pending = true;
                if (value == quote) quote = 0;
                continue;
            }
            if (value == '\'' || value == '"') {
                quote = value;
                token.append(value);
                pending = true;
                continue;
            }
            if (Character.isWhitespace(value)) {
                if (pending) {
                    words.add(token.toString());
                    token.setLength(0);
                    pending = false;
                }
                continue;
            }
            if (value == ';' || value == '|' || value == '&' || value == '(' || value == ')') {
                if (pending) {
                    words.add(token.toString());
                    token.setLength(0);
                    pending = false;
                }
                if (!words.isEmpty()) {
                    segments.add(words);
                    words = new ArrayList<>();
                }
                continue;
            }
            token.append(value);
            pending = true;
        }
        if (pending) words.add(token.toString());
        if (!words.isEmpty()) segments.add(words);
        return segments;
    }
}
