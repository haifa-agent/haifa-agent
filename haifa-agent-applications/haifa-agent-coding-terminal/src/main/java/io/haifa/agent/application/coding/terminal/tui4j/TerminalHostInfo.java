package io.haifa.agent.application.coding.terminal.tui4j;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** Allowlisted host facts used for terminal compatibility and shortcut presentation. */
public record TerminalHostInfo(
        OperatingSystem operatingSystem,
        String osName,
        String osVersion,
        String architecture,
        String javaVersion,
        Optional<String> terminalProgram,
        Optional<String> terminalProgramVersion) {
    public TerminalHostInfo {
        operatingSystem = Objects.requireNonNull(operatingSystem, "operatingSystem must not be null");
        osName = required(osName, "osName");
        osVersion = required(osVersion, "osVersion");
        architecture = required(architecture, "architecture");
        javaVersion = required(javaVersion, "javaVersion");
        terminalProgram = safeOptional(terminalProgram, "terminalProgram");
        terminalProgramVersion = safeOptional(terminalProgramVersion, "terminalProgramVersion");
    }

    static TerminalHostInfo system(List<String> environment) {
        return detect(
                Map.of(
                        "os.name", systemProperty("os.name"),
                        "os.version", systemProperty("os.version"),
                        "os.arch", systemProperty("os.arch"),
                        "java.version", systemProperty("java.version")),
                environment);
    }

    static TerminalHostInfo detect(Map<String, String> properties, List<String> environment) {
        Objects.requireNonNull(properties, "properties must not be null");
        List<String> safeEnvironment = List.copyOf(Objects.requireNonNull(environment, "environment must not be null"));
        String osName = property(properties, "os.name");
        return new TerminalHostInfo(
                OperatingSystem.detect(osName),
                osName,
                property(properties, "os.version"),
                property(properties, "os.arch"),
                property(properties, "java.version"),
                environment(safeEnvironment, "TERM_PROGRAM"),
                environment(safeEnvironment, "TERM_PROGRAM_VERSION"));
    }

    private static String systemProperty(String name) {
        try {
            return value(System.getProperty(name));
        } catch (SecurityException ignored) {
            return "unknown";
        }
    }

    private static String property(Map<String, String> properties, String name) {
        return value(properties.get(name));
    }

    private static Optional<String> environment(List<String> environment, String name) {
        String prefix = name + "=";
        return environment.stream()
                .filter(value -> value.startsWith(prefix))
                .map(value -> value(value.substring(prefix.length())))
                .filter(value -> !"unknown".equals(value))
                .findFirst();
    }

    private static String required(String value, String name) {
        String safe = value(value);
        if ("unknown".equals(safe) && value != null && value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return safe;
    }

    private static Optional<String> safeOptional(Optional<String> value, String name) {
        Objects.requireNonNull(value, name + " must not be null");
        return value.map(TerminalHostInfo::value).filter(text -> !"unknown".equals(text));
    }

    private static String value(String value) {
        if (value == null) return "unknown";
        String safe = value.replaceAll("[\\p{Cntrl}]", " ").strip();
        return safe.isEmpty() ? "unknown" : safe;
    }

    public enum OperatingSystem {
        MACOS,
        WINDOWS,
        LINUX,
        OTHER;

        private static OperatingSystem detect(String name) {
            String normalized = name.toLowerCase(Locale.ROOT);
            if (normalized.contains("mac") || normalized.contains("darwin")) return MACOS;
            if (normalized.contains("win")) return WINDOWS;
            if (normalized.contains("linux")) return LINUX;
            return OTHER;
        }
    }
}
