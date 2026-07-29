package io.haifa.agent.execution.core.tool;

import java.util.Locale;

public enum ExecutionOperatingSystem {
    WINDOWS,
    LINUX,
    MACOS;

    public static ExecutionOperatingSystem current() {
        String name = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (name.contains("win")) return WINDOWS;
        if (name.contains("mac") || name.contains("darwin")) return MACOS;
        return LINUX;
    }
}
