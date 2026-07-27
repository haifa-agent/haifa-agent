package io.haifa.agent.sandbox.localnative;

import io.haifa.agent.sandbox.api.SandboxConfigurationDigest;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

public record LocalNativeSandboxConfiguration(
        List<String> shellInvocationPrefix,
        Path controlRoot,
        Path seatbeltExecutable,
        Path bubblewrapExecutable,
        Map<String, LocalNativePathGrant> additionalPathPolicies,
        Set<Path> sensitivePaths) {
    public LocalNativeSandboxConfiguration {
        shellInvocationPrefix =
                List.copyOf(Objects.requireNonNull(shellInvocationPrefix, "shellInvocationPrefix must not be null"));
        if (shellInvocationPrefix.isEmpty()
                || shellInvocationPrefix.stream().anyMatch(value -> !validArgument(value))) {
            throw new IllegalArgumentException("shellInvocationPrefix is invalid");
        }
        controlRoot = absolute(controlRoot, "controlRoot");
        seatbeltExecutable = absolute(seatbeltExecutable, "seatbeltExecutable");
        bubblewrapExecutable = absolute(bubblewrapExecutable, "bubblewrapExecutable");
        additionalPathPolicies = validatedPolicies(additionalPathPolicies);
        sensitivePaths = normalizedPaths(sensitivePaths, "sensitivePaths");
        for (LocalNativePathGrant grant : additionalPathPolicies.values()) {
            if (sensitivePaths.stream().anyMatch(sensitive -> overlaps(grant.path(), sensitive))) {
                throw new IllegalArgumentException("additional path policy overlaps a sensitive path");
            }
        }
    }

    public static LocalNativeSandboxConfiguration defaults() {
        Path temporary = Path.of(System.getProperty("java.io.tmpdir"), "haifa-agent-local-native");
        Path home = Path.of(System.getProperty("user.home")).toAbsolutePath().normalize();
        Set<Path> sensitive = new LinkedHashSet<>();
        for (String relative :
                List.of(".ssh", ".aws", ".azure", ".config/gcloud", ".kube", ".docker", ".config/containers")) {
            sensitive.add(home.resolve(relative).normalize());
        }
        List<String> shell = isWindows()
                ? List.of("powershell.exe", "-NoLogo", "-NoProfile", "-NonInteractive", "-Command")
                : List.of("/bin/bash", "-lc");
        return new LocalNativeSandboxConfiguration(
                shell, temporary, Path.of("/usr/bin/sandbox-exec"), Path.of("/usr/bin/bwrap"), Map.of(), sensitive);
    }

    public SandboxConfigurationDigest digest() {
        List<String> fields = new ArrayList<>();
        fields.add("local-native");
        shellInvocationPrefix.forEach(value -> fields.add("shell:" + value));
        fields.add("control:" + controlRoot);
        fields.add("seatbelt:" + seatbeltExecutable);
        fields.add("bubblewrap:" + bubblewrapExecutable);
        additionalPathPolicies.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> fields.add("path:"
                        + entry.getKey()
                        + ":"
                        + entry.getValue().path()
                        + ":"
                        + entry.getValue().readOnly()));
        sensitivePaths.stream()
                .sorted(Comparator.comparing(Path::toString))
                .forEach(path -> fields.add("sensitive:" + path));
        return SandboxConfigurationDigest.sha256Fields(fields);
    }

    List<LocalNativePathGrant> resolveAdditionalPaths(Set<String> references) {
        Objects.requireNonNull(references, "references must not be null");
        List<LocalNativePathGrant> result = new ArrayList<>();
        references.stream().sorted().forEach(reference -> {
            LocalNativePathGrant grant = additionalPathPolicies.get(reference);
            if (grant == null) {
                throw new LocalNativeSandboxException(
                        "WORKSPACE_BIND_FAILED", "trusted additional path policy is unavailable");
            }
            result.add(grant);
        });
        return List.copyOf(result);
    }

    private static Map<String, LocalNativePathGrant> validatedPolicies(Map<String, LocalNativePathGrant> policies) {
        Objects.requireNonNull(policies, "additionalPathPolicies must not be null");
        if (policies.size() > 32) throw new IllegalArgumentException("too many additional path policies");
        Map<String, LocalNativePathGrant> result = new LinkedHashMap<>();
        policies.forEach((reference, grant) -> {
            if (reference == null || !reference.matches("^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$")) {
                throw new IllegalArgumentException("additional path policy reference is invalid");
            }
            result.put(reference, Objects.requireNonNull(grant, "path grant must not be null"));
        });
        return Map.copyOf(result);
    }

    private static Set<Path> normalizedPaths(Set<Path> paths, String field) {
        Objects.requireNonNull(paths, field + " must not be null");
        Set<Path> result = new LinkedHashSet<>();
        for (Path path : paths) {
            Path normalized = absolute(path, field + " entry");
            if (normalized.getParent() == null) {
                throw new IllegalArgumentException(field + " cannot contain a filesystem root");
            }
            result.add(normalized);
        }
        return Set.copyOf(result);
    }

    private static Path absolute(Path path, String field) {
        Path value = Objects.requireNonNull(path, field + " must not be null")
                .toAbsolutePath()
                .normalize();
        if (!value.isAbsolute()) throw new IllegalArgumentException(field + " must be absolute");
        return value;
    }

    private static boolean validArgument(String value) {
        return value != null && !value.isBlank() && value.indexOf('\0') < 0 && value.length() <= 4096;
    }

    private static boolean overlaps(Path first, Path second) {
        return first.startsWith(second) || second.startsWith(first);
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "")
                .toLowerCase(java.util.Locale.ROOT)
                .contains("win");
    }
}
