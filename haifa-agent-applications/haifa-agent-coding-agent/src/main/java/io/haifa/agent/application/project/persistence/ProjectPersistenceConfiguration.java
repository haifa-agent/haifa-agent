package io.haifa.agent.application.project.persistence;

import io.haifa.agent.store.sqlite.SqliteStoreConfiguration;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;

public record ProjectPersistenceConfiguration(
        ProjectPersistenceMode mode,
        ProjectPersistenceProtection protection,
        Optional<Path> databasePath,
        Optional<Path> transcriptRoot,
        Optional<String> protectorReference,
        int busyTimeoutMillis,
        int maximumPayloadBytes) {

    public static final int DEFAULT_BUSY_TIMEOUT_MILLIS = SqliteStoreConfiguration.DEFAULT_BUSY_TIMEOUT_MILLIS;
    public static final int DEFAULT_MAXIMUM_PAYLOAD_BYTES = SqliteStoreConfiguration.DEFAULT_MAXIMUM_PAYLOAD_BYTES;

    public ProjectPersistenceConfiguration {
        mode = Objects.requireNonNull(mode, "persistence mode must not be null");
        protection = Objects.requireNonNull(protection, "persistence protection must not be null");
        databasePath = normalize(databasePath, "database path");
        transcriptRoot = normalize(transcriptRoot, "transcript root");
        protectorReference = Objects.requireNonNull(protectorReference, "protectorReference must not be null")
                .map(String::trim)
                .filter(value -> !value.isEmpty());
        if (busyTimeoutMillis < 1 || maximumPayloadBytes < 1) {
            throw new IllegalArgumentException("persistence numeric limits must be positive");
        }
        switch (mode) {
            case MEMORY -> {
                if (protection != ProjectPersistenceProtection.NONE
                        || databasePath.isPresent()
                        || transcriptRoot.isPresent()
                        || protectorReference.isPresent()) {
                    throw new IllegalArgumentException("MEMORY persistence does not accept durable store settings");
                }
            }
            case SQLITE -> requireSqlite(protection, databasePath, transcriptRoot, protectorReference, false);
            case SQLITE_WITH_JSONL -> requireSqlite(protection, databasePath, transcriptRoot, protectorReference, true);
        }
    }

    public ProjectPersistenceConfiguration(
            ProjectPersistenceMode mode,
            Optional<Path> databasePath,
            Optional<Path> transcriptRoot,
            Optional<String> protectorReference,
            int busyTimeoutMillis,
            int maximumPayloadBytes) {
        this(
                mode,
                mode == ProjectPersistenceMode.MEMORY
                        ? ProjectPersistenceProtection.NONE
                        : ProjectPersistenceProtection.AES_GCM,
                databasePath,
                transcriptRoot,
                protectorReference,
                busyTimeoutMillis,
                maximumPayloadBytes);
    }

    public static ProjectPersistenceConfiguration memory() {
        return new ProjectPersistenceConfiguration(
                ProjectPersistenceMode.MEMORY,
                ProjectPersistenceProtection.NONE,
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                DEFAULT_BUSY_TIMEOUT_MILLIS,
                DEFAULT_MAXIMUM_PAYLOAD_BYTES);
    }

    public static ProjectPersistenceConfiguration sqlite(Path databasePath, String protectorReference) {
        return durable(ProjectPersistenceMode.SQLITE, databasePath, null, protectorReference);
    }

    public static ProjectPersistenceConfiguration sqliteWithJsonl(
            Path databasePath, Path transcriptRoot, String protectorReference) {
        return durable(ProjectPersistenceMode.SQLITE_WITH_JSONL, databasePath, transcriptRoot, protectorReference);
    }

    public static ProjectPersistenceConfiguration sqliteUnprotected(Path databasePath) {
        return durableUnprotected(ProjectPersistenceMode.SQLITE, databasePath, null);
    }

    public static ProjectPersistenceConfiguration sqliteWithJsonlUnprotected(Path databasePath, Path transcriptRoot) {
        return durableUnprotected(ProjectPersistenceMode.SQLITE_WITH_JSONL, databasePath, transcriptRoot);
    }

    private static ProjectPersistenceConfiguration durable(
            ProjectPersistenceMode mode, Path databasePath, Path transcriptRoot, String protectorReference) {
        return new ProjectPersistenceConfiguration(
                mode,
                ProjectPersistenceProtection.AES_GCM,
                Optional.ofNullable(databasePath),
                Optional.ofNullable(transcriptRoot),
                Optional.ofNullable(protectorReference),
                DEFAULT_BUSY_TIMEOUT_MILLIS,
                DEFAULT_MAXIMUM_PAYLOAD_BYTES);
    }

    private static ProjectPersistenceConfiguration durableUnprotected(
            ProjectPersistenceMode mode, Path databasePath, Path transcriptRoot) {
        return new ProjectPersistenceConfiguration(
                mode,
                ProjectPersistenceProtection.NONE,
                Optional.ofNullable(databasePath),
                Optional.ofNullable(transcriptRoot),
                Optional.empty(),
                DEFAULT_BUSY_TIMEOUT_MILLIS,
                DEFAULT_MAXIMUM_PAYLOAD_BYTES);
    }

    private static Optional<Path> normalize(Optional<Path> path, String field) {
        return Objects.requireNonNull(path, field + " must not be null").map(value -> {
            if (!value.isAbsolute()) throw new IllegalArgumentException(field + " must be absolute");
            return value.normalize();
        });
    }

    private static void requireSqlite(
            ProjectPersistenceProtection protection,
            Optional<Path> database,
            Optional<Path> transcript,
            Optional<String> protector,
            boolean requireTranscript) {
        if (database.isEmpty()) throw new IllegalArgumentException("SQLite persistence requires a database path");
        if (requireTranscript != transcript.isPresent()) {
            throw new IllegalArgumentException(
                    requireTranscript
                            ? "SQLITE_WITH_JSONL requires a transcript root"
                            : "SQLITE persistence does not accept a transcript root");
        }
        if (protection == ProjectPersistenceProtection.NONE) {
            if (protector.isPresent()) {
                throw new IllegalArgumentException("NONE persistence protection does not accept a protector reference");
            }
            return;
        }
        String reference = protector.orElseThrow(
                () -> new IllegalArgumentException("AES_GCM persistence protection requires a protector reference"));
        if (!reference.startsWith("env://") || reference.length() == "env://".length()) {
            throw new IllegalArgumentException("AES_GCM protector reference must use env://");
        }
    }
}
