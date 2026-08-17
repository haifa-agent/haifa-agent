package io.haifa.agent.testing.harness;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Resolves the frozen local toolchain without shell-specific command parsing. */
final class ExecutableResolver {
    private final Map<String, String> environment;

    ExecutableResolver(Map<String, String> environment) {
        this.environment = environment;
    }

    Path require(String name, String environmentName, String... candidates) {
        String explicit = environment.get(environmentName);
        if (explicit != null && !explicit.isBlank()) return regular(Path.of(explicit), name);
        if (name.equals("java") || name.equals("javac")) {
            Path javaHome = Path.of(System.getProperty("java.home"));
            Path candidate = javaHome.resolve("bin").resolve(executable(name));
            if (Files.isRegularFile(candidate))
                return candidate.toAbsolutePath().normalize();
        }
        for (Path directory : pathDirectories()) {
            for (String candidate : candidates) {
                Path resolved = directory.resolve(executable(candidate));
                if (Files.isRegularFile(resolved))
                    return resolved.toAbsolutePath().normalize();
            }
        }
        throw new IllegalArgumentException(name + " executable is unavailable; set " + environmentName);
    }

    private List<Path> pathDirectories() {
        String path = environment.getOrDefault("PATH", environment.getOrDefault("Path", ""));
        ArrayList<Path> directories = new ArrayList<>();
        for (String entry : path.split(java.io.File.pathSeparator)) {
            if (!entry.isBlank()) directories.add(Path.of(entry));
        }
        return directories;
    }

    private static Path regular(Path path, String name) {
        Path normalized = path.toAbsolutePath().normalize();
        if (!Files.isRegularFile(normalized)) throw new IllegalArgumentException(name + " executable is unavailable");
        return normalized;
    }

    private static String executable(String name) {
        boolean windows =
                System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
        return windows && !name.toLowerCase(Locale.ROOT).endsWith(".exe") ? name + ".exe" : name;
    }
}
