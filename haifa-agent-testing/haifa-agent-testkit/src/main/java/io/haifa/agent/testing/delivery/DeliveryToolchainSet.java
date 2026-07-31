package io.haifa.agent.testing.delivery;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/** Validated exact executables and the minimal PATH exposed to a delivery repeat. */
record DeliveryToolchainSet(
        Path javaExecutable,
        Path javacExecutable,
        Path pythonExecutable,
        Path nodeExecutable,
        Path goExecutable,
        Path gitExecutable,
        Path shellExecutable) {
    private static final List<String> NAMES = List.of("java", "javac", "python", "node", "go", "git", "shell");

    static DeliveryToolchainSet validate(Map<String, Path> values) throws IOException {
        LinkedHashMap<String, Path> executables = new LinkedHashMap<>();
        for (String name : NAMES) {
            Path value = values.get(name);
            if (value == null) throw new IllegalArgumentException("--" + name + "-executable is required");
            Path executable = value.toAbsolutePath().normalize();
            if (!Files.isRegularFile(executable)) {
                throw new IllegalArgumentException(name + " executable must be a regular file");
            }
            executables.put(name, executable.toRealPath());
        }
        Path javaHome = executables.get("java").getParent().getParent();
        if (!executables.get("javac").startsWith(javaHome)) {
            throw new IllegalArgumentException("java and javac executables must belong to the same JDK");
        }
        return new DeliveryToolchainSet(
                executables.get("java"),
                executables.get("javac"),
                executables.get("python"),
                executables.get("node"),
                executables.get("go"),
                executables.get("git"),
                executables.get("shell"));
    }

    Path javaHome() {
        return javaExecutable.getParent().getParent();
    }

    Map<String, Path> executablePaths() {
        LinkedHashMap<String, Path> paths = new LinkedHashMap<>();
        paths.put("java", javaExecutable);
        paths.put("javac", javacExecutable);
        paths.put("python", pythonExecutable);
        paths.put("node", nodeExecutable);
        paths.put("go", goExecutable);
        paths.put("git", gitExecutable);
        paths.put("shell", shellExecutable);
        return Collections.unmodifiableMap(paths);
    }

    Map<String, Path> pathRoots() {
        LinkedHashMap<String, Path> roots = new LinkedHashMap<>();
        executablePaths().forEach((name, executable) -> roots.put(name, executable.getParent()));
        return Collections.unmodifiableMap(roots);
    }

    String minimalPath() {
        LinkedHashSet<String> roots = new LinkedHashSet<>();
        executablePaths()
                .values()
                .forEach(executable -> roots.add(executable.getParent().toString()));
        return String.join(File.pathSeparator, roots);
    }
}
