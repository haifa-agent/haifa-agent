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
 * {@code $env:NAME} forms, and {@code git}/{@code gh} used as the command word with a credential
 * subcommand or credential config override located after leading global options (traversal stops at
 * the first non-option token, bounded only by the finite token list, not by a token-count ceiling).
 * It never interprets shell composition ({@code ;}, {@code &&}, {@code |}), nested
 * interpreters, wrappers, quoting grammar, Git business operations, target, risk, or effect.
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
    private static final String CONFIG_ENV_OPTION = "--config-env";
    private static final String CONFIG_ENV_PREFIX = "--config-env=";
    private static final String SHOW_TOKEN = "--show-token";
    private static final String SHOW_TOKEN_SHORT = "-t";
    private static final Set<String> GIT_GLOBAL_OPTIONS_WITH_SEPARATE_VALUE = Set.of(
            "-c", "-C", "--config-env", "--git-dir", "--work-tree", "--namespace", "--exec-path", "--super-prefix");
    private static final Set<String> GH_GLOBAL_OPTIONS_WITH_SEPARATE_VALUE = Set.of("--hostname", "-h");
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
        if (head.equals("env") && envPrefixOverridesProtectedEnvironment(commandWords)) {
            return Optional.of(ENVIRONMENT_OVERRIDE_CODE);
        }
        if (head.equals("export") && hasProtectedAssignment(commandWords.subList(1, commandWords.size()))) {
            return Optional.of(ENVIRONMENT_OVERRIDE_CODE);
        }
        if (head.equals("set")
                && commandWords.size() > 1
                && isProtectedAssignment(stripSurroundingQuotes(commandWords.get(1)))) {
            return Optional.of(ENVIRONMENT_OVERRIDE_CODE);
        }
        if (head.startsWith(ENV_PREFIX) && isProtectedPowerShellAssignment(commandWords)) {
            return Optional.of(ENVIRONMENT_OVERRIDE_CODE);
        }
        if (head.equals("git")) {
            int subcommand = gitSubcommandIndex(commandWords);
            if (subcommand > 0) {
                String gitCommand = commandWords.get(subcommand);
                if (gitCommand.equals("credential") || gitCommand.startsWith("credential-")) {
                    return Optional.of(GIT_CREDENTIAL_PROTOCOL_CODE);
                }
                if (overridesCredentialConfig(commandWords, subcommand)) {
                    return Optional.of(GIT_CONFIG_OVERRIDE_CODE);
                }
            }
        }
        if (head.equals("gh")) {
            int subcommand = ghSubcommandIndex(commandWords);
            if (subcommand > 0 && commandWords.get(subcommand).equals("auth")) {
                int authCommand = nextNonOptionWord(commandWords, subcommand + 1);
                if (authCommand < 0 || !commandWords.get(authCommand).equals("status")) {
                    return Optional.of(GITHUB_AUTH_STATE_CODE);
                }
                if (disclosesToken(commandWords, authCommand + 1)) {
                    return Optional.of(GITHUB_TOKEN_DISCLOSURE_CODE);
                }
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

    /**
     * {@code env [OPTION]... [NAME=VALUE]... COMMAND} applies only its leading option/assignment
     * prefix to the launched command. Later tokens are the launched command's own arguments and must
     * not be read as host environment overrides.
     */
    private static boolean envPrefixOverridesProtectedEnvironment(List<String> commandWords) {
        for (int index = 1; index < commandWords.size(); index++) {
            String word = commandWords.get(index);
            if (word.startsWith("-")) {
                continue;
            }
            if (!isAssignment(word)) {
                return false;
            }
            if (isProtectedAssignment(word)) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasProtectedAssignment(List<String> words) {
        for (String word : words) {
            if (isAssignment(word) && isProtectedAssignment(word)) {
                return true;
            }
        }
        return false;
    }

    private static boolean overridesCredentialConfig(List<String> commandWords, int subcommandIndex) {
        for (int index = 1; index < subcommandIndex; index++) {
            String option = commandWords.get(index);
            String value;
            if (option.equals("-c")) {
                value = commandWords.get(index + 1);
            } else if (option.startsWith(CONFIG_ENV_PREFIX)) {
                value = option.substring(CONFIG_ENV_PREFIX.length());
            } else if (option.equals(CONFIG_ENV_OPTION)) {
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

    /**
     * Locates the Git subcommand after the leading Git global options. Each recognized option that
     * takes a separate value consumes the following token; any other leading {@code -option}
     * consumes only itself. Traversal stops at the first non-option token, so it is bounded by the
     * command's finite token list rather than a token-count ceiling, and later options such as
     * {@code git grep -c ...} are arguments and never treated as global overrides.
     */
    private static int gitSubcommandIndex(List<String> commandWords) {
        int index = 1;
        while (index < commandWords.size()) {
            String word = commandWords.get(index);
            if (!word.startsWith("-")) {
                return index;
            }
            index += GIT_GLOBAL_OPTIONS_WITH_SEPARATE_VALUE.contains(word) ? 2 : 1;
        }
        return -1;
    }

    /**
     * Locates the {@code gh} subcommand after the leading {@code gh} global options. Only the
     * separate-value hostname options consume the following token; every other leading
     * {@code -option} consumes itself. Traversal stops at the first non-option token.
     */
    private static int ghSubcommandIndex(List<String> commandWords) {
        int index = 1;
        while (index < commandWords.size()) {
            String word = commandWords.get(index);
            if (!word.startsWith("-")) {
                return index;
            }
            index += GH_GLOBAL_OPTIONS_WITH_SEPARATE_VALUE.contains(word) ? 2 : 1;
        }
        return -1;
    }

    private static int nextNonOptionWord(List<String> commandWords, int from) {
        for (int index = from; index < commandWords.size(); index++) {
            if (!commandWords.get(index).startsWith("-")) {
                return index;
            }
        }
        return -1;
    }

    private static boolean disclosesToken(List<String> commandWords, int from) {
        for (int index = from; index < commandWords.size(); index++) {
            String word = commandWords.get(index);
            if (word.equals(SHOW_TOKEN) || word.equals(SHOW_TOKEN_SHORT) || word.startsWith(SHOW_TOKEN + "=")) {
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
