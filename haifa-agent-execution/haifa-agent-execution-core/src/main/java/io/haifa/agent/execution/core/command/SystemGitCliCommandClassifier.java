package io.haifa.agent.execution.core.command;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Conservative, product-neutral classification for direct system git and gh CLI commands. */
public final class SystemGitCliCommandClassifier {
    private static final Set<String> PROTECTED_ENVIRONMENT = Set.of(
            "GH_TOKEN",
            "GITHUB_TOKEN",
            "GH_CONFIG_DIR",
            "HOME",
            "USERPROFILE",
            "APPDATA",
            "XDG_CONFIG_HOME",
            "SSH_AUTH_SOCK",
            "PATH",
            "GIT_CONFIG_GLOBAL",
            "GIT_CONFIG_SYSTEM",
            "GIT_SSH_COMMAND",
            "GIT_ASKPASS",
            "SSH_ASKPASS");
    private static final Set<String> GIT_READ =
            Set.of("status", "diff", "log", "show", "blame", "rev-parse", "symbolic-ref", "describe");
    private static final Set<String> GIT_WRITE = Set.of(
            "add",
            "commit",
            "switch",
            "checkout",
            "restore",
            "stash",
            "merge",
            "rebase",
            "cherry-pick",
            "revert",
            "worktree",
            "pull");

    private SystemGitCliCommandClassifier() {}

    public static Classification classify(String command) {
        if (command == null || command.isBlank() || command.indexOf('\0') >= 0) {
            return classified(Target.OTHER, Risk.UNKNOWN, Operation.UNKNOWN, "COMMAND_INVALID");
        }
        if (usesProtectedShellEnvironmentSyntax(command)) {
            return classified(
                    detectTarget(command), Risk.DENIED, Operation.UNKNOWN, "AUTHENTICATION_ENVIRONMENT_OVERRIDE");
        }
        if (containsShellComposition(command)) {
            return classified(detectTarget(command), Risk.UNKNOWN, Operation.UNKNOWN, "COMPOUND_OR_WRAPPED_COMMAND");
        }
        List<String> argv = tokenize(command);
        if (argv.isEmpty()) return classified(Target.OTHER, Risk.UNKNOWN, Operation.UNKNOWN, "COMMAND_UNPARSEABLE");
        int index = 0;
        boolean environmentWrapped = false;
        if (basename(argv.get(index)).equals("env")) {
            environmentWrapped = true;
            index++;
            while (index < argv.size() && assignment(argv.get(index))) {
                if (protectedAssignment(argv.get(index))) {
                    return classified(
                            detectTarget(command),
                            Risk.DENIED,
                            Operation.UNKNOWN,
                            "AUTHENTICATION_ENVIRONMENT_OVERRIDE");
                }
                index++;
            }
        } else {
            while (index < argv.size() && assignment(argv.get(index))) {
                environmentWrapped = true;
                if (protectedAssignment(argv.get(index))) {
                    return classified(
                            detectTarget(command),
                            Risk.DENIED,
                            Operation.UNKNOWN,
                            "AUTHENTICATION_ENVIRONMENT_OVERRIDE");
                }
                index++;
            }
        }
        if (index >= argv.size())
            return classified(Target.OTHER, Risk.UNKNOWN, Operation.UNKNOWN, "EXECUTABLE_MISSING");
        String executable = argv.get(index);
        String executableName = basename(executable);
        Target target = target(executableName);
        if (target != Target.OTHER && !bareSystemExecutable(executable, executableName)) {
            return classified(target, Risk.DENIED, Operation.UNKNOWN, "SYSTEM_CLI_PATH_OVERRIDE");
        }
        if (environmentWrapped && target != Target.OTHER) {
            return classified(target, Risk.UNKNOWN, Operation.UNKNOWN, "COMMAND_ENVIRONMENT_WRAPPER");
        }
        return switch (executableName) {
            case "git" -> classifyGit(argv, index + 1);
            case "gh" -> classifyGh(argv, index + 1);
            default -> {
                Target nested = detectTarget(String.join(" ", argv.subList(index + 1, argv.size())));
                yield nested == Target.OTHER
                        ? classified(Target.OTHER, Risk.NOT_APPLICABLE, Operation.UNKNOWN, "NON_GIT_COMMAND")
                        : classified(nested, Risk.UNKNOWN, Operation.UNKNOWN, "UNKNOWN_EXECUTABLE_WRAPPER");
            }
        };
    }

    private static Classification classifyGit(List<String> argv, int index) {
        if (index < argv.size() && argv.get(index).equals("--version")) {
            return git(Risk.LOCAL_READ, "GIT_VERSION");
        }
        while (index < argv.size() && argv.get(index).startsWith("-")) {
            String rawOption = argv.get(index);
            String option = rawOption.toLowerCase(Locale.ROOT);
            if (option.equals("--no-pager")
                    || option.equals("--no-optional-locks")
                    || option.equals("--literal-pathspecs")) {
                index++;
                continue;
            }
            if (rawOption.equals("-c")) {
                if (++index >= argv.size()) return git(Risk.UNKNOWN, "GIT_CONFIG_VALUE_MISSING");
                String config = argv.get(index++).toLowerCase(Locale.ROOT);
                if (config.startsWith("credential.")
                        || (config.startsWith("http.") && config.contains("extraheader"))
                        || config.startsWith("core.sshcommand")
                        || config.startsWith("credentialhelper")) {
                    return git(Risk.DENIED, "GIT_AUTHENTICATION_CONFIG_OVERRIDE");
                }
                return git(Risk.UNKNOWN, "GIT_CONFIG_OVERRIDE_UNCLASSIFIED");
            }
            if (rawOption.equals("-C")) {
                return git(Risk.DENIED, "GIT_REPOSITORY_PATH_OVERRIDE");
            }
            if (option.startsWith("--git-dir")
                    || option.startsWith("--work-tree")
                    || option.startsWith("--config-env")
                    || option.startsWith("--exec-path")) {
                return git(Risk.DENIED, "GIT_EXECUTION_BOUNDARY_OVERRIDE");
            }
            return git(Risk.UNKNOWN, "GIT_GLOBAL_OPTION_UNKNOWN");
        }
        if (index >= argv.size()) return git(Risk.UNKNOWN, "GIT_SUBCOMMAND_MISSING");
        String subcommand = argv.get(index).toLowerCase(Locale.ROOT);
        List<String> args = argv.subList(index + 1, argv.size());
        if (GIT_READ.contains(subcommand)) {
            if (hasPrefixed(args, "--output", "--ext-diff")) {
                return git(Risk.DENIED, Operation.UNKNOWN, "GIT_READ_SIDE_EFFECT_OPTION");
            }
            Operation operation = subcommand.equals("diff") ? Operation.DIFF : Operation.INSPECT;
            return git(
                    Risk.LOCAL_READ,
                    operation,
                    "GIT_" + subcommand.toUpperCase(Locale.ROOT).replace('-', '_'));
        }
        if (subcommand.equals("branch")) {
            return git(
                    hasAny(args, "-d", "-D", "--delete", "-m", "-M", "--move")
                            ? Risk.DESTRUCTIVE
                            : args.stream().anyMatch(value -> !value.startsWith("-"))
                                    ? Risk.LOCAL_WRITE
                                    : Risk.LOCAL_READ,
                    "GIT_BRANCH");
        }
        if (subcommand.equals("remote")) {
            return git(
                    args.isEmpty() || hasAny(args, "-v", "--verbose", "get-url")
                            ? Risk.LOCAL_READ
                            : hasAny(args, "show") ? Risk.NETWORK_READ : Risk.LOCAL_WRITE,
                    "GIT_REMOTE");
        }
        if (subcommand.equals("ls-remote") || subcommand.equals("fetch")) {
            return git(Risk.NETWORK_READ, Operation.INSPECT, "GIT_NETWORK_READ");
        }
        if (subcommand.equals("push")) {
            return git(
                    hasAny(args, "--force", "-f", "--force-with-lease", "--delete", "-d")
                            ? Risk.DESTRUCTIVE
                            : Risk.EXTERNAL_WRITE,
                    "GIT_PUSH");
        }
        if (subcommand.equals("reset") || subcommand.equals("clean")) {
            return git(Risk.DESTRUCTIVE, "GIT_DESTRUCTIVE");
        }
        if (subcommand.startsWith("credential")) {
            return git(Risk.DENIED, "GIT_CREDENTIAL_PROTOCOL_DENIED");
        }
        if (subcommand.equals("tag") && hasAny(args, "-d", "--delete", "-f", "--force")) {
            return git(Risk.DESTRUCTIVE, "GIT_TAG_DESTRUCTIVE");
        }
        if (GIT_WRITE.contains(subcommand) || subcommand.equals("tag")) {
            return git(Risk.LOCAL_WRITE, "GIT_LOCAL_WRITE");
        }
        return git(Risk.UNKNOWN, "GIT_SUBCOMMAND_UNKNOWN");
    }

    private static Classification classifyGh(List<String> argv, int index) {
        if (index < argv.size() && argv.get(index).equals("--version")) {
            return github(Risk.LOCAL_READ, "GH_VERSION");
        }
        while (index < argv.size()
                && (argv.get(index).equals("--repo") || argv.get(index).equals("-R"))) {
            if (++index >= argv.size()) return github(Risk.UNKNOWN, "GH_REPOSITORY_MISSING");
            index++;
        }
        if (index >= argv.size()) return github(Risk.UNKNOWN, "GH_COMMAND_MISSING");
        String group = argv.get(index).toLowerCase(Locale.ROOT);
        List<String> args = argv.subList(index + 1, argv.size());
        if (group.equals("auth")) {
            if (!args.isEmpty() && args.getFirst().equalsIgnoreCase("status")) {
                if (hasAnyIgnoreCase(args, "--show-token")) {
                    return github(Risk.DENIED, "GH_AUTH_TOKEN_DISCLOSURE_DENIED");
                }
                return github(Risk.NETWORK_READ, Operation.INSPECT, "GH_AUTH_STATUS");
            }
            return github(Risk.DENIED, "GH_AUTHENTICATION_MUTATION_OR_DISCLOSURE");
        }
        if (group.equals("api")) {
            boolean writes =
                    hasAnyIgnoreCase(args, "--field", "-f", "--raw-field", "-F", "--input") || methodWrites(args);
            return github(
                    writes ? Risk.EXTERNAL_WRITE : Risk.NETWORK_READ,
                    writes ? Operation.MUTATE : Operation.INSPECT,
                    "GH_API");
        }
        String action = args.isEmpty() ? "" : args.getFirst().toLowerCase(Locale.ROOT);
        if (Set.of("repo", "pr", "issue", "run", "workflow", "release").contains(group)
                && Set.of("list", "view", "status", "checks", "diff", "watch").contains(action)) {
            return github(Risk.NETWORK_READ, Operation.INSPECT, "GH_REMOTE_READ");
        }
        if ((group.equals("repo") || group.equals("release")) && action.equals("delete")) {
            return github(Risk.DESTRUCTIVE, "GH_DESTRUCTIVE");
        }
        if (Set.of("repo", "pr", "issue", "run", "workflow", "release").contains(group)) {
            return github(Risk.EXTERNAL_WRITE, "GH_REMOTE_WRITE");
        }
        return github(Risk.UNKNOWN, "GH_COMMAND_UNKNOWN");
    }

    private static boolean usesProtectedShellEnvironmentSyntax(String command) {
        String upper = command.toUpperCase(Locale.ROOT);
        return PROTECTED_ENVIRONMENT.stream()
                .anyMatch(name -> upper.matches("(?s).*(?:\\$ENV:|(?:^|[;&|]\\s*)SET\\s+)" + name + "\\s*=.*"));
    }

    private static boolean containsShellComposition(String command) {
        boolean single = false;
        boolean doub = false;
        for (int index = 0; index < command.length(); index++) {
            char value = command.charAt(index);
            if (value == '\'' && !doub) single = !single;
            else if (value == '"' && !single) doub = !doub;
            else if (!single
                    && !doub
                    && (value == ';'
                            || value == '|'
                            || value == '&'
                            || value == '\n'
                            || value == '\r'
                            || value == '>'
                            || value == '<'
                            || value == '`')) return true;
        }
        return single || doub;
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

    private static Target detectTarget(String command) {
        String lower = command.toLowerCase(Locale.ROOT);
        if (lower.matches("(?s).*\\bgh(?:\\.exe)?\\b.*")) return Target.GITHUB;
        if (lower.matches("(?s).*\\bgit(?:\\.exe)?\\b.*")) return Target.GIT;
        return Target.OTHER;
    }

    private static String basename(String value) {
        String name;
        try {
            Path fileName = Path.of(value).getFileName();
            name = fileName == null ? value : fileName.toString();
        } catch (RuntimeException ignored) {
            name = value;
        }
        String lower = name.toLowerCase(Locale.ROOT);
        return lower.endsWith(".exe") ? lower.substring(0, lower.length() - 4) : lower;
    }

    private static Target target(String executableName) {
        return switch (executableName) {
            case "git" -> Target.GIT;
            case "gh" -> Target.GITHUB;
            default -> Target.OTHER;
        };
    }

    private static boolean bareSystemExecutable(String raw, String executableName) {
        String lower = raw.toLowerCase(Locale.ROOT);
        return lower.equals(executableName) || lower.equals(executableName + ".exe");
    }

    private static boolean assignment(String value) {
        int equals = value.indexOf('=');
        return equals > 0 && value.substring(0, equals).matches("[A-Za-z_][A-Za-z0-9_]*");
    }

    private static boolean protectedAssignment(String value) {
        return PROTECTED_ENVIRONMENT.contains(
                value.substring(0, value.indexOf('=')).toUpperCase(Locale.ROOT));
    }

    private static boolean hasAny(List<String> values, String... candidates) {
        return values.stream()
                .anyMatch(value -> java.util.Arrays.asList(candidates).contains(value));
    }

    private static boolean hasAnyIgnoreCase(List<String> values, String... candidates) {
        return values.stream().map(value -> value.toLowerCase(Locale.ROOT)).anyMatch(value -> java.util.Arrays.stream(
                        candidates)
                .map(candidate -> candidate.toLowerCase(Locale.ROOT))
                .anyMatch(value::equals));
    }

    private static boolean hasPrefixed(List<String> values, String... prefixes) {
        return values.stream().map(value -> value.toLowerCase(Locale.ROOT)).anyMatch(value -> java.util.Arrays.stream(
                        prefixes)
                .map(prefix -> prefix.toLowerCase(Locale.ROOT))
                .anyMatch(prefix -> value.equals(prefix) || value.startsWith(prefix + "=")));
    }

    private static boolean methodWrites(List<String> values) {
        for (int index = 0; index < values.size(); index++) {
            String value = values.get(index);
            if (value.equalsIgnoreCase("--method") || value.equalsIgnoreCase("-X")) {
                return index + 1 >= values.size() || !values.get(index + 1).equalsIgnoreCase("GET");
            }
            if (value.regionMatches(true, 0, "--method=", 0, 9)) {
                return !value.substring(9).equalsIgnoreCase("GET");
            }
        }
        return false;
    }

    private static Classification git(Risk risk, String reason) {
        return git(risk, riskOperation(risk), reason);
    }

    private static Classification git(Risk risk, Operation operation, String reason) {
        return classified(Target.GIT, risk, operation, reason);
    }

    private static Classification github(Risk risk, String reason) {
        return github(risk, riskOperation(risk), reason);
    }

    private static Classification github(Risk risk, Operation operation, String reason) {
        return classified(Target.GITHUB, risk, operation, reason);
    }

    private static Operation riskOperation(Risk risk) {
        return switch (risk) {
            case LOCAL_WRITE, EXTERNAL_WRITE, DESTRUCTIVE -> Operation.MUTATE;
            case LOCAL_READ, NETWORK_READ -> Operation.INSPECT;
            default -> Operation.UNKNOWN;
        };
    }

    private static Classification classified(Target target, Risk risk, Operation operation, String reason) {
        return new Classification(target, risk, operation, reason);
    }

    public enum Target {
        OTHER,
        GIT,
        GITHUB
    }

    public enum Risk {
        NOT_APPLICABLE,
        LOCAL_READ,
        LOCAL_WRITE,
        NETWORK_READ,
        EXTERNAL_WRITE,
        DESTRUCTIVE,
        UNKNOWN,
        DENIED
    }

    public enum Operation {
        INSPECT,
        DIFF,
        MUTATE,
        UNKNOWN
    }

    public record Classification(Target target, Risk risk, Operation operation, String reasonCode) {
        public boolean readOnly() {
            return risk == Risk.LOCAL_READ || risk == Risk.NETWORK_READ;
        }

        public boolean applies() {
            return target != Target.OTHER || risk == Risk.DENIED;
        }
    }
}
