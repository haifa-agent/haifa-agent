package io.haifa.agent.cli;

import io.haifa.agent.sandbox.localnative.LocalNativeSandboxProvider;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Builds the bounded host environment intentionally passed to execution sandboxes. */
final class CliExecutionEnvironment {
    private static final Set<String> LOCAL_NATIVE_MANAGED =
            Set.of("HOME", "USERPROFILE", "TMPDIR", "TMP", "TEMP", "GOTMPDIR", "GOCACHE");

    private CliExecutionEnvironment() {}

    static Map<String, String> resolve(CliConfiguration.Execution configuration, String providerId) {
        return resolve(configuration, providerId, System.getenv(), System.getProperty("os.name", ""));
    }

    static Map<String, String> resolve(
            CliConfiguration.Execution configuration,
            String providerId,
            Map<String, String> hostEnvironment,
            String operatingSystem) {
        boolean windows = operatingSystem.toLowerCase(Locale.ROOT).contains("win");
        boolean mac = operatingSystem.toLowerCase(Locale.ROOT).contains("mac");
        boolean localNative = LocalNativeSandboxProvider.PROVIDER_ID.equals(providerId);
        var resolved = new LinkedHashMap<String, String>();

        if (configuration.inheritEnvironment().contains("*")) {
            hostEnvironment.entrySet().stream()
                    .sorted(Map.Entry.comparingByKey(String.CASE_INSENSITIVE_ORDER))
                    .filter(entry -> allowed(entry.getKey(), localNative))
                    .forEach(entry -> putNonBlank(resolved, entry.getKey(), entry.getValue(), windows));
        } else {
            configuration.inheritEnvironment().stream().sorted().forEach(name -> find(hostEnvironment, name, windows)
                    .filter(entry -> allowed(entry.getKey(), localNative))
                    .ifPresent(entry -> putNonBlank(resolved, entry.getKey(), entry.getValue(), windows)));
        }

        if (windows) {
            inherit(resolved, hostEnvironment, "PATH", true);
            inherit(resolved, hostEnvironment, "PATHEXT", true);
            inherit(resolved, hostEnvironment, "SystemRoot", true);
            inherit(resolved, hostEnvironment, "SystemDrive", true);
            inherit(resolved, hostEnvironment, "WINDIR", true);
            inherit(resolved, hostEnvironment, "ComSpec", true);
            putIfMissing(resolved, "PATHEXT", ".COM;.EXE;.BAT;.CMD", true);
            if (value(resolved, "PATH", true).isEmpty()) {
                String root = value(resolved, "SystemRoot", true).orElse("");
                if (!root.isBlank()) {
                    putIfMissing(
                            resolved,
                            "PATH",
                            String.join(
                                    ";",
                                    Path.of(root, "System32").toString(),
                                    root,
                                    Path.of(root, "System32", "Wbem").toString(),
                                    Path.of(root, "System32", "WindowsPowerShell", "v1.0")
                                            .toString()),
                            true);
                }
            }
        } else {
            inherit(resolved, hostEnvironment, "PATH", false);
            inherit(resolved, hostEnvironment, "SHELL", false);
            inherit(resolved, hostEnvironment, "LANG", false);
            inherit(resolved, hostEnvironment, "LC_ALL", false);
            inherit(resolved, hostEnvironment, "LC_CTYPE", false);
            if (!localNative) {
                inherit(resolved, hostEnvironment, "HOME", false);
                inherit(resolved, hostEnvironment, "USER", false);
                inherit(resolved, hostEnvironment, "LOGNAME", false);
            }
            putIfMissing(
                    resolved,
                    "PATH",
                    mac
                            ? "/opt/homebrew/bin:/usr/local/bin:/usr/bin:/bin:/usr/sbin:/sbin"
                            : "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin",
                    false);
            putIfMissing(resolved, "SHELL", mac ? "/bin/zsh" : "/bin/sh", false);
        }
        return Map.copyOf(resolved);
    }

    private static boolean allowed(String name, boolean localNative) {
        if (name == null || !name.matches("[A-Za-z_][A-Za-z0-9_]{0,127}")) return false;
        String upper = name.toUpperCase(Locale.ROOT);
        return !looksSensitive(upper) && (!localNative || !LOCAL_NATIVE_MANAGED.contains(upper));
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
                || name.endsWith("_AUTH_SOCK")
                || name.equals("DOCKER_HOST")
                || name.equals("KUBECONFIG");
    }

    private static void inherit(
            Map<String, String> target, Map<String, String> source, String name, boolean ignoreCase) {
        if (value(target, name, ignoreCase).isPresent()) return;
        find(source, name, ignoreCase)
                .ifPresent(entry -> putNonBlank(target, entry.getKey(), entry.getValue(), ignoreCase));
    }

    private static java.util.Optional<Map.Entry<String, String>> find(
            Map<String, String> values, String name, boolean ignoreCase) {
        return values.entrySet().stream()
                .filter(entry -> ignoreCase
                        ? entry.getKey().equalsIgnoreCase(name)
                        : entry.getKey().equals(name))
                .findFirst();
    }

    private static java.util.Optional<String> value(Map<String, String> values, String name, boolean ignoreCase) {
        return find(values, name, ignoreCase).map(Map.Entry::getValue).filter(value -> !value.isBlank());
    }

    private static void putIfMissing(Map<String, String> values, String name, String value, boolean ignoreCase) {
        if (value(values, name, ignoreCase).isEmpty()) putNonBlank(values, name, value, ignoreCase);
    }

    private static void putNonBlank(Map<String, String> values, String name, String value, boolean ignoreCase) {
        if (name == null || name.isBlank() || value == null || value.isBlank()) return;
        find(values, name, ignoreCase)
                .ifPresentOrElse(entry -> values.put(entry.getKey(), value), () -> values.put(name, value));
    }
}
