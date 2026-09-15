package io.haifa.agent.execution.core.command;

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * Narrow, fail-closed guard for confirmed top-level commands that could read, echo, override, or
 * redirect host authentication material into model-visible output.
 *
 * <p>This is intentionally a leading-token recognizer, not a shell parser and not a Git/GitHub
 * command grammar. It inspects only the first words of the command text: optional leading
 * {@code NAME=value} assignments, the {@code env}/{@code export}/{@code set} builtins, PowerShell
 * {@code $env:NAME} forms, and {@code git}/{@code gh} used as the command word with an exact
 * credential subcommand or credential config override. It never interprets shell composition
 * ({@code ;}, {@code &&}, {@code |}), nested interpreters, wrappers, quoting grammar, Git business
 * operations, target, risk, or effect.
 *
 * <p>Known limits: a credential read or protected override that is not in the leading command
 * position (for example {@code echo x && HOME=/tmp git status}), handed to a wrapper or nested
 * interpreter, or expressed through an unlisted credential path is not recognized here and must be
 * handled by general credential-egress protection. Textual occurrences such as
 * {@code echo "HOME=value"} or {@code echo "gh auth token"} are arguments, not executions, and stay
 * on the generic path.
 *
 * <p>Every recognized path has a regression proving the protected value cannot reach model-visible
 * output. When a general Credential egress boundary exists, this guard can be replaced by it.
 */
public final class CredentialEgressGuard {
    public static final String ENVIRONMENT_OVERRIDE_CODE = "AUTHENTICATION_ENVIRONMENT_OVERRIDE";
    public static final String GIT_CONFIG_OVERRIDE_CODE = "GIT_AUTHENTICATION_CONFIG_OVERRIDE";
    public static final String GIT_CREDENTIAL_PROTOCOL_CODE = "GIT_CREDENTIAL_PROTOCOL_DENIED";
    public static final String GITHUB_TOKEN_DISCLOSURE_CODE = "GH_AUTH_TOKEN_DISCLOSURE_DENIED";
    public static final String GITHUB_AUTH_STATE_CODE = "GH_AUTHENTICATION_MUTATION_OR_DISCLOSURE";

    private static final String ENV_PREFIX = "$env:";
    private static final String CONFIG_ENV_PREFIX = "--config-env=";
    private static final String SHOW_TOKEN = "--show-token";
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
        List<String> words = List.of(command.strip().split("\\s+"));
        int index = 0;
        while (index < words.size() && isAssignment(words.get(index))) {
            if (isProtectedAssignment(words.get(index))) {
                return Optional.of(ENVIRONMENT_OVERRIDE_CODE);
            }
            index++;
        }
        if (index >= words.size()) {
            return Optional.empty();
        }
        List<String> commandWords = words.subList(index, words.size());
        String head = commandHead(commandWords.getFirst());
        if (ASSIGNMENT_COMMANDS.contains(head)
                && hasProtectedAssignment(commandWords.subList(1, commandWords.size()), head.equals("set"))) {
            return Optional.of(ENVIRONMENT_OVERRIDE_CODE);
        }
        if (head.startsWith(ENV_PREFIX) && isProtectedPowerShellAssignment(commandWords)) {
            return Optional.of(ENVIRONMENT_OVERRIDE_CODE);
        }
        if (head.equals("git")) {
            if (hasWord(commandWords, 1, "credential") || hasPrefix(commandWords, 1, "credential-")) {
                return Optional.of(GIT_CREDENTIAL_PROTOCOL_CODE);
            }
            if (overridesCredentialConfig(commandWords)) {
                return Optional.of(GIT_CONFIG_OVERRIDE_CODE);
            }
        }
        if (head.equals("gh") && hasWord(commandWords, 1, "auth")) {
            if (!hasWord(commandWords, 2, "status")) {
                return Optional.of(GITHUB_AUTH_STATE_CODE);
            }
            if (commandWords.subList(3, commandWords.size()).contains(SHOW_TOKEN)) {
                return Optional.of(GITHUB_TOKEN_DISCLOSURE_CODE);
            }
        }
        return Optional.empty();
    }

    public static boolean rejects(String command) {
        return rejectionCode(command).isPresent();
    }

    private static boolean isProtectedPowerShellAssignment(List<String> commandWords) {
        String token = commandWords.getFirst().toLowerCase(Locale.ROOT);
        int equals = token.indexOf('=');
        String name;
        if (equals > ENV_PREFIX.length()) {
            name = token.substring(ENV_PREFIX.length(), equals);
        } else if (equals < 0 && commandWords.size() > 1 && commandWords.get(1).equals("=")) {
            name = token.substring(ENV_PREFIX.length());
        } else {
            return false;
        }
        return PROTECTED_ENVIRONMENT.contains(name.toUpperCase(Locale.ROOT));
    }

    private static boolean hasProtectedAssignment(List<String> words, boolean stripQuotes) {
        for (String word : words) {
            String candidate = stripQuotes ? stripSurroundingQuotes(word) : word;
            if (isAssignment(candidate) && isProtectedAssignment(candidate)) {
                return true;
            }
        }
        return false;
    }

    private static boolean overridesCredentialConfig(List<String> commandWords) {
        for (int index = 1; index + 1 < commandWords.size(); index++) {
            String option = commandWords.get(index);
            String value;
            if (option.equals("-c")) {
                value = commandWords.get(index + 1);
            } else if (option.startsWith(CONFIG_ENV_PREFIX)) {
                value = option.substring(CONFIG_ENV_PREFIX.length());
            } else if (option.equals("--config-env")) {
                value = commandWords.get(index + 1);
            } else {
                continue;
            }
            if (isCredentialConfigKey(value)) {
                return true;
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

    private static boolean hasWord(List<String> words, int index, String value) {
        return index < words.size() && words.get(index).equals(value);
    }

    private static boolean hasPrefix(List<String> words, int index, String prefix) {
        return index < words.size() && words.get(index).startsWith(prefix);
    }

    private static boolean isAssignment(String token) {
        int equals = token.indexOf('=');
        if (equals <= 0) {
            return false;
        }
        for (int index = 0; index < equals; index++) {
            char value = token.charAt(index);
            if (!(Character.isLetterOrDigit(value) || value == '_')) {
                return false;
            }
        }
        return true;
    }

    private static boolean isProtectedAssignment(String token) {
        int equals = token.indexOf('=');
        if (equals <= 0) {
            return false;
        }
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
}
