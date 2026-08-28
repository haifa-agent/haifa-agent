package io.haifa.agent.execution.core;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Selects which environment values may be redacted from process output. Host environment resolvers already
 * exclude sensitive-<em>named</em> variables, so the values that remain in the execution environment are public
 * platform or control values (for example {@code PATH}, {@code USERPROFILE}, {@code GIT_PAGER=cat} or
 * {@code GIT_TERMINAL_PROMPT=0}). Redacting those values corrupts ordinary command output - every digit, path
 * or common word would turn into {@code ***} - so only values of non-baseline variables that look like real
 * credentials (at least 8 characters with at least one letter) are treated as secrets here. This stays a
 * defense-in-depth net for user-approved inherited variables without mangling normal tool output.
 */
final class ExecutionOutputRedactionPolicy {
    private static final int MINIMUM_SECRET_LENGTH = 8;

    /** Mirrors the curated non-secret variable names from the host environment resolvers (case-insensitive). */
    private static final Set<String> NON_SECRET_BASELINE_NAMES = Set.of(
            // POSIX baseline
            "HOME",
            "LANG",
            "LC_ALL",
            "LC_CTYPE",
            "LOGNAME",
            "SHELL",
            "USER",
            // Windows baseline
            "APPDATA",
            "COMSPEC",
            "HOMEDRIVE",
            "HOMEPATH",
            "LOCALAPPDATA",
            "PATHEXT",
            "SYSTEMDRIVE",
            "SYSTEMROOT",
            "USERPROFILE",
            "WINDIR",
            // Shared path or temp baseline
            "PATH",
            "TEMP",
            "TMP",
            "TMPDIR",
            // XDG directories
            "XDG_CACHE_HOME",
            "XDG_CONFIG_HOME",
            "XDG_DATA_HOME",
            "XDG_STATE_HOME",
            // Interpreter location variables (paths, never secrets)
            "BUN_INSTALL",
            "CONDA_DEFAULT_ENV",
            "CONDA_PREFIX",
            "DENO_DIR",
            "NODE_PATH",
            "NPM_CONFIG_PREFIX",
            "PYTHONHOME",
            "PYTHONPATH",
            "PYTHONUSERBASE",
            "VIRTUAL_ENV",
            // Toolchain cache directories
            "GOCACHE",
            "GOTMPDIR",
            // Non-interactive control flags pinned by the resolver
            "GCM_INTERACTIVE",
            "GH_PAGER",
            "GH_PROMPT_DISABLED",
            "GIT_PAGER",
            "GIT_TERMINAL_PROMPT",
            // Public socket location
            "SSH_AUTH_SOCK");

    private ExecutionOutputRedactionPolicy() {}

    /** Returns the UTF-8 values eligible for redaction, longest first so longer secrets match before prefixes. */
    static List<byte[]> redactionValues(Map<String, String> environment) {
        var values = new ArrayList<byte[]>();
        environment.forEach((name, value) -> {
            if (isRedactable(name, value)) {
                values.add(value.getBytes(StandardCharsets.UTF_8));
            }
        });
        values.sort(Comparator.comparingInt((byte[] value) -> value.length).reversed());
        return List.copyOf(values);
    }

    private static boolean isRedactable(String name, String value) {
        if (value == null || value.length() < MINIMUM_SECRET_LENGTH) {
            return false;
        }
        if (name != null && NON_SECRET_BASELINE_NAMES.contains(name.toUpperCase(Locale.ROOT))) {
            return false;
        }
        return value.codePoints().anyMatch(Character::isLetter);
    }
}
