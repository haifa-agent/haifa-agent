package io.haifa.agent.execution.core.command;

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

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

    private static final List<String> PROTECTED_ENVIRONMENT = List.of(
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
    private static final Pattern GIT_CREDENTIAL_PROTOCOL =
            Pattern.compile("(?is).*\\bgit(?:\\.exe)?\\s+credential(?:-[^\\s;&|]+|\\s).*");
    private static final Pattern GIT_CREDENTIAL_CONFIG = Pattern.compile(
            "(?is).*\\bgit(?:\\.exe)?\\b.*?(?:\\s-c\\s+|\\s--config-env(?:=|\\s+))\\s*[\"']?"
                    + "(?:credential\\.|http\\.[^\\s;&|=]*extraheader|core\\.sshcommand|credentialhelper).*");
    private static final Pattern GITHUB_AUTH_STATUS_SHOW_TOKEN =
            Pattern.compile("(?is).*\\bgh(?:\\.exe)?\\s+auth\\s+status\\b.*--show-token.*");
    private static final Pattern GITHUB_AUTH_STATE_MUTATION_OR_DISCLOSURE =
            Pattern.compile("(?is).*\\bgh(?:\\.exe)?\\s+auth\\s+(?!status(?:\\s|$)).*");

    private CredentialEgressGuard() {}

    /**
     * Returns a stable reason code when the command is a confirmed credential read, echo,
     * override, or redirect path; empty when the command is handled by the generic execution path.
     */
    public static Optional<String> rejectionCode(String command) {
        if (command == null || command.isBlank() || command.indexOf('\0') >= 0) {
            return Optional.empty();
        }
        if (usesProtectedEnvironment(command)) {
            return Optional.of(ENVIRONMENT_OVERRIDE_CODE);
        }
        if (GITHUB_AUTH_STATUS_SHOW_TOKEN.matcher(command).matches()) {
            return Optional.of(GITHUB_TOKEN_DISCLOSURE_CODE);
        }
        if (GITHUB_AUTH_STATE_MUTATION_OR_DISCLOSURE.matcher(command).matches()) {
            return Optional.of(GITHUB_AUTH_STATE_CODE);
        }
        if (GIT_CREDENTIAL_PROTOCOL.matcher(command).matches()) {
            return Optional.of(GIT_CREDENTIAL_PROTOCOL_CODE);
        }
        if (GIT_CREDENTIAL_CONFIG.matcher(command).matches()) {
            return Optional.of(GIT_CONFIG_OVERRIDE_CODE);
        }
        return Optional.empty();
    }

    public static boolean rejects(String command) {
        return rejectionCode(command).isPresent();
    }

    private static boolean usesProtectedEnvironment(String command) {
        String upper = command.toUpperCase(Locale.ROOT);
        return PROTECTED_ENVIRONMENT.stream().anyMatch(name -> containsAssignment(upper, name));
    }

    private static boolean containsAssignment(String command, String name) {
        int fromIndex = 0;
        while (true) {
            int index = command.indexOf(name, fromIndex);
            if (index < 0) return false;
            int end = index + name.length();
            boolean leftBoundary = index == 0 || !isEnvironmentNameCharacter(command.charAt(index - 1));
            int equals = end;
            while (equals < command.length() && Character.isWhitespace(command.charAt(equals))) equals++;
            if (leftBoundary && equals < command.length() && command.charAt(equals) == '=') return true;
            fromIndex = end;
        }
    }

    private static boolean isEnvironmentNameCharacter(char value) {
        return Character.isLetterOrDigit(value) || value == '_';
    }
}
