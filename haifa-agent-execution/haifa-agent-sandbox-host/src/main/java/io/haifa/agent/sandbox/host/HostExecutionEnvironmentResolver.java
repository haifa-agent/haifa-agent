package io.haifa.agent.sandbox.host;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/** Resolves bounded, secret-free host inputs for Host Guarded and Provider-isolated execution. */
public final class HostExecutionEnvironmentResolver {
    public static final String POLICY_VERSION = "host-execution-environment-v1";
    public static final String HOST_USER_RESOLVED = "HOST_USER_ENVIRONMENT_RESOLVED";
    public static final String PROVIDER_ISOLATED_RESOLVED = "PROVIDER_ISOLATED_ENVIRONMENT_RESOLVED";
    private static final int MAX_VARIABLES = 256;
    private static final int MAX_VALUE_LENGTH = 32_768;
    private static final Set<String> PROVIDER_MANAGED = Set.of(
            "HOME",
            "USERPROFILE",
            "APPDATA",
            "LOCALAPPDATA",
            "HOMEDRIVE",
            "HOMEPATH",
            "XDG_CONFIG_HOME",
            "XDG_DATA_HOME",
            "XDG_CACHE_HOME",
            "XDG_STATE_HOME",
            "TMPDIR",
            "TMP",
            "TEMP",
            "GOTMPDIR",
            "GOCACHE");
    private static final Set<String> INTERPRETER_BOUNDARY = Set.of(
            "VIRTUAL_ENV",
            "CONDA_PREFIX",
            "CONDA_DEFAULT_ENV",
            "PYTHONHOME",
            "PYTHONPATH",
            "PYTHONUSERBASE",
            "NODE_PATH",
            "NPM_CONFIG_PREFIX",
            "BUN_INSTALL",
            "DENO_DIR");
    private static final Set<String> WINDOWS_BASELINE = Set.of(
            "PATH",
            "PATHEXT",
            "SYSTEMROOT",
            "SYSTEMDRIVE",
            "WINDIR",
            "COMSPEC",
            "TEMP",
            "TMP",
            "USERPROFILE",
            "APPDATA",
            "LOCALAPPDATA",
            "HOMEDRIVE",
            "HOMEPATH");
    private static final Set<String> POSIX_BASELINE =
            Set.of("PATH", "SHELL", "HOME", "USER", "LOGNAME", "LANG", "LC_ALL", "LC_CTYPE", "TMPDIR", "TMP", "TEMP");
    private static final Set<String> LINUX_XDG =
            Set.of("XDG_CONFIG_HOME", "XDG_DATA_HOME", "XDG_CACHE_HOME", "XDG_STATE_HOME");

    private HostExecutionEnvironmentResolver() {}

    public static ResolvedHostEnvironment resolveHostUser(
            Map<String, String> hostEnvironment,
            String operatingSystem,
            Path jvmUserHome,
            Path applicationDataRoot,
            Path workspaceRoot,
            Path scratchRoot,
            Set<String> approvedInheritedNames) {
        Objects.requireNonNull(hostEnvironment, "hostEnvironment must not be null");
        Objects.requireNonNull(approvedInheritedNames, "approvedInheritedNames must not be null");
        Os os = Os.parse(operatingSystem);
        List<Path> forbidden = normalizedRoots(applicationDataRoot, workspaceRoot, scratchRoot);
        LinkedHashMap<String, String> resolved = selected(hostEnvironment, approvedInheritedNames, os.windows(), false);
        Set<String> baseline = os.windows() ? WINDOWS_BASELINE : POSIX_BASELINE;
        baseline.stream().sorted().forEach(name -> inherit(resolved, hostEnvironment, name, os.windows(), false));
        if (os.windows()) {
            List.of("USERPROFILE", "APPDATA", "LOCALAPPDATA", "TEMP", "TMP", "SYSTEMROOT", "WINDIR")
                    .forEach(name -> normalizeSafePath(resolved, name, true, forbidden));
        } else {
            List.of("TMPDIR", "TMP", "TEMP").forEach(name -> normalizeSafePath(resolved, name, false, forbidden));
        }
        if (os.linux()) {
            LINUX_XDG.stream().sorted().forEach(name -> {
                remove(resolved, name, false);
                inheritSafePath(resolved, hostEnvironment, name, false, forbidden);
            });
        }

        Path home = resolveHome(hostEnvironment, os, jvmUserHome, forbidden)
                .orElseThrow(() -> new IllegalArgumentException(
                        "HOST_USER_HOME_UNAVAILABLE: no safe host user home is available"));
        putCanonical(resolved, "HOME", home.toString(), os.windows());
        inherit(resolved, hostEnvironment, "SSH_AUTH_SOCK", os.windows(), false);
        putCanonical(resolved, "GIT_TERMINAL_PROMPT", "0", os.windows());
        putCanonical(resolved, "GCM_INTERACTIVE", "Never", os.windows());
        putCanonical(resolved, "GH_PROMPT_DISABLED", "1", os.windows());
        putCanonical(resolved, "GIT_PAGER", "cat", os.windows());
        putCanonical(resolved, "GH_PAGER", "cat", os.windows());
        if (os.windows()) {
            putIfMissing(resolved, "PATHEXT", ".COM;.EXE;.BAT;.CMD", true);
            if (value(resolved, "PATH", true).isEmpty()) {
                value(resolved, "SystemRoot", true)
                        .ifPresent(root -> put(
                                resolved,
                                "PATH",
                                String.join(
                                        ";",
                                        Path.of(root, "System32").toString(),
                                        root,
                                        Path.of(root, "System32", "Wbem").toString(),
                                        Path.of(root, "System32", "WindowsPowerShell", "v1.0")
                                                .toString()),
                                true));
            }
        } else {
            putIfMissing(
                    resolved,
                    "PATH",
                    os.mac()
                            ? "/opt/homebrew/bin:/usr/local/bin:/usr/bin:/bin:/usr/sbin:/sbin"
                            : "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin",
                    false);
            putIfMissing(resolved, "SHELL", os.mac() ? "/bin/zsh" : "/bin/sh", false);
        }
        validateBudget(resolved);
        return new ResolvedHostEnvironment(resolved, resolved.keySet(), HOST_USER_RESOLVED);
    }

    public static ResolvedHostEnvironment resolveProviderIsolated(
            Map<String, String> hostEnvironment, String operatingSystem, Set<String> approvedInheritedNames) {
        Objects.requireNonNull(hostEnvironment, "hostEnvironment must not be null");
        Objects.requireNonNull(approvedInheritedNames, "approvedInheritedNames must not be null");
        Os os = Os.parse(operatingSystem);
        LinkedHashMap<String, String> resolved = selected(hostEnvironment, approvedInheritedNames, os.windows(), true);
        inherit(resolved, hostEnvironment, "PATH", os.windows(), true);
        if (os.windows()) {
            inherit(resolved, hostEnvironment, "PATHEXT", true, true);
            inherit(resolved, hostEnvironment, "SystemRoot", true, true);
            inherit(resolved, hostEnvironment, "ComSpec", true, true);
            putIfMissing(resolved, "PATHEXT", ".COM;.EXE;.BAT;.CMD", true);
        } else {
            inherit(resolved, hostEnvironment, "SHELL", false, true);
            inherit(resolved, hostEnvironment, "LANG", false, true);
            inherit(resolved, hostEnvironment, "LC_ALL", false, true);
            inherit(resolved, hostEnvironment, "LC_CTYPE", false, true);
            putIfMissing(
                    resolved,
                    "PATH",
                    os.mac()
                            ? "/opt/homebrew/bin:/usr/local/bin:/usr/bin:/bin:/usr/sbin:/sbin"
                            : "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin",
                    false);
            putIfMissing(resolved, "SHELL", os.mac() ? "/bin/zsh" : "/bin/sh", false);
        }
        validateBudget(resolved);
        return new ResolvedHostEnvironment(resolved, resolved.keySet(), PROVIDER_ISOLATED_RESOLVED);
    }

    private static LinkedHashMap<String, String> selected(
            Map<String, String> source, Set<String> approved, boolean ignoreCase, boolean providerIsolated) {
        LinkedHashMap<String, String> result = new LinkedHashMap<>();
        if (approved.contains("*")) {
            source.entrySet().stream()
                    .sorted(Map.Entry.comparingByKey(String.CASE_INSENSITIVE_ORDER))
                    .filter(entry -> allowed(entry.getKey(), providerIsolated))
                    .forEach(entry -> put(result, entry.getKey(), entry.getValue(), ignoreCase));
        } else {
            approved.stream().sorted().forEach(name -> find(source, name, ignoreCase)
                    .filter(entry -> allowed(entry.getKey(), providerIsolated))
                    .ifPresent(entry -> put(result, entry.getKey(), entry.getValue(), ignoreCase)));
        }
        return result;
    }

    private static Optional<Path> resolveHome(
            Map<String, String> environment, Os os, Path jvmUserHome, List<Path> forbidden) {
        List<String> candidates = new ArrayList<>();
        find(environment, "HOME", os.windows()).map(Map.Entry::getValue).ifPresent(candidates::add);
        if (os.windows()) {
            find(environment, "USERPROFILE", true).map(Map.Entry::getValue).ifPresent(candidates::add);
            Optional<String> drive = find(environment, "HOMEDRIVE", true).map(Map.Entry::getValue);
            Optional<String> path = find(environment, "HOMEPATH", true).map(Map.Entry::getValue);
            if (drive.isPresent() && path.isPresent()) candidates.add(drive.orElseThrow() + path.orElseThrow());
        }
        if (jvmUserHome != null) candidates.add(jvmUserHome.toString());
        return candidates.stream()
                .map(HostExecutionEnvironmentResolver::safeExistingDirectory)
                .flatMap(Optional::stream)
                .filter(candidate -> forbidden.stream().noneMatch(candidate::startsWith))
                .findFirst();
    }

    private static Optional<Path> safeExistingDirectory(String value) {
        if (!validValue(value)) return Optional.empty();
        try {
            Path path = Path.of(value);
            if (!path.isAbsolute() || !Files.isDirectory(path)) return Optional.empty();
            return Optional.of(path.toRealPath());
        } catch (IOException | RuntimeException exception) {
            return Optional.empty();
        }
    }

    private static void inheritSafePath(
            Map<String, String> target,
            Map<String, String> source,
            String name,
            boolean ignoreCase,
            List<Path> forbidden) {
        find(source, name, ignoreCase)
                .map(Map.Entry::getValue)
                .flatMap(HostExecutionEnvironmentResolver::safeExistingDirectory)
                .filter(path -> forbidden.stream().noneMatch(path::startsWith))
                .ifPresent(path -> put(target, name, path.toString(), ignoreCase));
    }

    private static void normalizeSafePath(
            Map<String, String> environment, String name, boolean ignoreCase, List<Path> forbidden) {
        Optional<Map.Entry<String, String>> entry = find(environment, name, ignoreCase);
        if (entry.isEmpty()) return;
        String originalName = entry.orElseThrow().getKey();
        Optional<Path> normalized = safeExistingDirectory(entry.orElseThrow().getValue())
                .filter(path -> forbidden.stream().noneMatch(path::startsWith));
        remove(environment, name, ignoreCase);
        normalized.ifPresent(path -> put(environment, originalName, path.toString(), ignoreCase));
    }

    private static List<Path> normalizedRoots(Path... roots) {
        List<Path> values = new ArrayList<>();
        for (Path root : roots) {
            if (root == null) continue;
            try {
                Path value = root.toAbsolutePath().normalize();
                if (Files.exists(value)) value = value.toRealPath();
                values.add(value);
            } catch (IOException exception) {
                throw new IllegalArgumentException(
                        "HOST_USER_ENVIRONMENT_INVALID: boundary root is unavailable", exception);
            }
        }
        return List.copyOf(values);
    }

    private static boolean allowed(String name, boolean providerIsolated) {
        if (name == null || !name.matches("[A-Za-z_][A-Za-z0-9_]{0,127}")) return false;
        String upper = name.toUpperCase(Locale.ROOT);
        return !looksSensitive(upper)
                && !INTERPRETER_BOUNDARY.contains(upper)
                && (!providerIsolated || !PROVIDER_MANAGED.contains(upper));
    }

    private static boolean looksSensitive(String name) {
        return name.contains("API_KEY")
                || name.contains("ACCESS_KEY")
                || name.contains("PRIVATE_KEY")
                || name.contains("PASSWORD")
                || name.contains("SECRET")
                || name.contains("TOKEN")
                || name.contains("CREDENTIAL")
                || name.endsWith("_PROXY")
                || name.equals("NO_PROXY")
                || (name.endsWith("_AUTH_SOCK") && !name.equals("SSH_AUTH_SOCK"))
                || name.equals("DOCKER_HOST")
                || name.equals("KUBECONFIG");
    }

    private static void inherit(
            Map<String, String> target,
            Map<String, String> source,
            String name,
            boolean ignoreCase,
            boolean providerIsolated) {
        if (value(target, name, ignoreCase).isPresent()) return;
        find(source, name, ignoreCase)
                .filter(entry -> allowed(entry.getKey(), providerIsolated))
                .ifPresent(entry -> put(target, entry.getKey(), entry.getValue(), ignoreCase));
    }

    private static Optional<Map.Entry<String, String>> find(
            Map<String, String> values, String name, boolean ignoreCase) {
        return values.entrySet().stream()
                .filter(entry -> ignoreCase
                        ? entry.getKey().equalsIgnoreCase(name)
                        : entry.getKey().equals(name))
                .findFirst();
    }

    private static Optional<String> value(Map<String, String> values, String name, boolean ignoreCase) {
        return find(values, name, ignoreCase).map(Map.Entry::getValue).filter(value -> !value.isBlank());
    }

    private static void putIfMissing(Map<String, String> values, String name, String value, boolean ignoreCase) {
        if (value(values, name, ignoreCase).isEmpty()) put(values, name, value, ignoreCase);
    }

    private static void putCanonical(Map<String, String> values, String name, String value, boolean ignoreCase) {
        remove(values, name, ignoreCase);
        put(values, name, value, false);
    }

    private static void remove(Map<String, String> values, String name, boolean ignoreCase) {
        find(values, name, ignoreCase).map(Map.Entry::getKey).ifPresent(values::remove);
    }

    private static void put(Map<String, String> values, String name, String value, boolean ignoreCase) {
        if (!allowed(name, false) || !validValue(value)) return;
        find(values, name, ignoreCase)
                .ifPresentOrElse(entry -> values.put(entry.getKey(), value), () -> values.put(name, value));
    }

    private static boolean validValue(String value) {
        return value != null && !value.isBlank() && value.length() <= MAX_VALUE_LENGTH && value.indexOf('\0') < 0;
    }

    private static void validateBudget(Map<String, String> environment) {
        if (environment.size() > MAX_VARIABLES) {
            throw new IllegalArgumentException("HOST_USER_ENVIRONMENT_INVALID: environment variable budget exceeded");
        }
    }

    private enum Os {
        WINDOWS,
        LINUX,
        MAC;

        private static Os parse(String value) {
            String normalized = Objects.requireNonNull(value, "operatingSystem must not be null")
                    .toLowerCase(Locale.ROOT);
            if (normalized.contains("win")) return WINDOWS;
            if (normalized.contains("mac") || normalized.contains("darwin")) return MAC;
            return LINUX;
        }

        private boolean windows() {
            return this == WINDOWS;
        }

        private boolean linux() {
            return this == LINUX;
        }

        private boolean mac() {
            return this == MAC;
        }
    }
}
