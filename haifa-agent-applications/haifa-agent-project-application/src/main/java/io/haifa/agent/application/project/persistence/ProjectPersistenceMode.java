package io.haifa.agent.application.project.persistence;

public enum ProjectPersistenceMode {
    MEMORY,
    SQLITE,
    SQLITE_WITH_JSONL;

    public static ProjectPersistenceMode parse(String value) {
        if (value == null || value.isBlank()) return MEMORY;
        try {
            return valueOf(value.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("persistence mode must be MEMORY, SQLITE, or SQLITE_WITH_JSONL");
        }
    }
}
