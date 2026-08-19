package io.haifa.agent.personalassistant.server.mission;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.haifa.agent.personalassistant.application.PersonalAssistantApplication;
import io.haifa.agent.personalassistant.application.mission.MissionException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/** Quiescent SQLite backup and fresh-directory restore with Artifact reference verification. */
@Component
public final class MissionBackupService {
    private static final String MANIFEST_SCHEMA = "pa.mission-backup/v1";
    private static final String APPLICATION_VERSION = "0.1.0-SNAPSHOT";
    private static final int RUNTIME_SCHEMA_VERSION = 9;
    private static final int MISSION_SCHEMA_VERSION = 7;

    private final SqliteMissionStore store;
    private final MissionDispatcher dispatcher;
    private final ObjectMapper mapper;
    private final Clock clock;
    private final Path dataDirectory;
    private final String productDigest;
    private final String skillBinding;

    @Autowired
    public MissionBackupService(
            SqliteMissionStore store,
            MissionDispatcher dispatcher,
            ObjectMapper mapper,
            Clock clock,
            PersonalAssistantApplication application) {
        this(
                store,
                dispatcher,
                mapper,
                clock,
                application.productDigest(),
                application.skillBindingReference("deep-research").orElse("unavailable"));
    }

    MissionBackupService(
            SqliteMissionStore store,
            MissionDispatcher dispatcher,
            ObjectMapper mapper,
            Clock clock,
            String productDigest,
            String skillBinding) {
        this.store = java.util.Objects.requireNonNull(store);
        this.dispatcher = java.util.Objects.requireNonNull(dispatcher);
        this.mapper = java.util.Objects.requireNonNull(mapper).copy();
        this.clock = java.util.Objects.requireNonNull(clock);
        this.dataDirectory = store.database().getParent();
        this.productDigest = java.util.Objects.requireNonNull(productDigest);
        this.skillBinding = java.util.Objects.requireNonNull(skillBinding);
    }

    MissionBackupService(ObjectMapper mapper, Clock clock, String productDigest, String skillBinding) {
        this.store = null;
        this.dispatcher = null;
        this.mapper = java.util.Objects.requireNonNull(mapper).copy();
        this.clock = java.util.Objects.requireNonNull(clock);
        this.dataDirectory = Path.of(".").toAbsolutePath().normalize();
        this.productDigest = java.util.Objects.requireNonNull(productDigest);
        this.skillBinding = java.util.Objects.requireNonNull(skillBinding);
    }

    public BackupResult create(Path backupDirectory) {
        SqliteMissionStore activeStore = java.util.Objects.requireNonNull(store, "Backup requires an active store");
        MissionDispatcher activeDispatcher =
                java.util.Objects.requireNonNull(dispatcher, "Backup requires an active dispatcher gate");
        Path target = normalizeFreshDirectory(backupDirectory, true);
        if (target.startsWith(dataDirectory)) {
            throw new MissionException(
                    "MISSION_BACKUP_PATH_INVALID", "Backup must be outside the active data directory");
        }
        return activeDispatcher.withClaimsPaused(() -> {
            activeStore.requireQuiescent(now());
            try {
                Files.createDirectories(target);
                Path databaseTarget = target.resolve("personal-assistant.sqlite");
                vacuumInto(databaseTarget);
                List<FileEntry> artifacts = copyReferencedArtifacts(target.resolve("artifacts"));
                FileEntry databaseEntry = fileEntry(target, databaseTarget);
                BackupManifest manifest = new BackupManifest(
                        MANIFEST_SCHEMA,
                        APPLICATION_VERSION,
                        RUNTIME_SCHEMA_VERSION,
                        MISSION_SCHEMA_VERSION,
                        productDigest,
                        skillBinding,
                        now(),
                        databaseEntry,
                        artifacts);
                validateDatabase(databaseTarget, manifest, target.resolve("artifacts"));
                writeManifest(target, manifest);
                return new BackupResult(target, manifest);
            } catch (IOException | SQLException exception) {
                throw new MissionException("MISSION_BACKUP_FAILED", "Mission backup could not be completed", exception);
            }
        });
    }

    public BackupResult restore(Path backupDirectory, Path freshDataDirectory) {
        Path source = normalizeExistingDirectory(backupDirectory);
        Path target = normalizeFreshDirectory(freshDataDirectory, false);
        try {
            BackupManifest manifest =
                    mapper.readValue(source.resolve("manifest.json").toFile(), BackupManifest.class);
            validateManifest(manifest);
            validateFile(source, manifest.database());
            for (FileEntry artifact : manifest.artifacts()) validateFile(source, artifact);
            validateDatabase(source.resolve(manifest.database().relativePath()), manifest, source.resolve("artifacts"));

            Files.createDirectories(target);
            copyNew(source.resolve(manifest.database().relativePath()), target.resolve("personal-assistant.sqlite"));
            for (FileEntry artifact : manifest.artifacts()) {
                Path destination = target.resolve(artifact.relativePath()).normalize();
                requireBelow(target, destination);
                Files.createDirectories(destination.getParent());
                copyNew(source.resolve(artifact.relativePath()), destination);
            }
            validateDatabase(target.resolve("personal-assistant.sqlite"), manifest, target.resolve("artifacts"));
            return new BackupResult(target, manifest);
        } catch (IOException | SQLException exception) {
            throw new MissionException("MISSION_RESTORE_FAILED", "Mission restore could not be completed", exception);
        }
    }

    public BackupManifest verify(Path backupDirectory) {
        Path source = normalizeExistingDirectory(backupDirectory);
        try {
            BackupManifest manifest =
                    mapper.readValue(source.resolve("manifest.json").toFile(), BackupManifest.class);
            validateManifest(manifest);
            validateFile(source, manifest.database());
            for (FileEntry artifact : manifest.artifacts()) validateFile(source, artifact);
            validateDatabase(source.resolve(manifest.database().relativePath()), manifest, source.resolve("artifacts"));
            return manifest;
        } catch (IOException | SQLException exception) {
            throw new MissionException("MISSION_BACKUP_VERIFY_FAILED", "Mission backup verification failed", exception);
        }
    }

    private void vacuumInto(Path target) throws SQLException {
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + store.database());
                var statement = connection.prepareStatement("VACUUM INTO ?")) {
            statement.setString(1, target.toString());
            statement.execute();
        }
    }

    private List<FileEntry> copyReferencedArtifacts(Path backupArtifactRoot) throws SQLException, IOException {
        List<ArtifactRow> rows = artifactRows(store.database());
        List<FileEntry> entries = new ArrayList<>();
        Path sourceRoot = dataDirectory.resolve("artifacts");
        Files.createDirectories(backupArtifactRoot);
        for (ArtifactRow row : rows) {
            Path source = sourceRoot.resolve(row.payloadId()).normalize();
            requireBelow(sourceRoot, source);
            if (!Files.isRegularFile(source, LinkOption.NOFOLLOW_LINKS)
                    || Files.size(source) != row.byteCount()
                    || !sha256(source).equals(row.sha256())) {
                throw new MissionException(
                        "MISSION_ARTIFACT_INTEGRITY_FAILED", "Referenced Mission Artifact is missing or changed");
            }
            Path destination = backupArtifactRoot.resolve(row.payloadId());
            copyNew(source, destination);
            FileEntry entry = fileEntry(backupArtifactRoot.getParent(), destination);
            if (!entry.sha256().equals(row.sha256()) || entry.byteCount() != row.byteCount()) {
                throw new MissionException("MISSION_BACKUP_FAILED", "Copied Mission Artifact failed verification");
            }
            entries.add(entry);
        }
        return entries.stream()
                .sorted(Comparator.comparing(FileEntry::relativePath))
                .toList();
    }

    private void validateDatabase(Path database, BackupManifest manifest, Path artifactRoot)
            throws SQLException, IOException {
        validateManifest(manifest);
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + database)) {
            try (var statement = connection.createStatement()) {
                statement.execute("PRAGMA query_only=ON");
                statement.execute("PRAGMA foreign_keys=ON");
                try (var result = statement.executeQuery("PRAGMA integrity_check")) {
                    if (!result.next() || !"ok".equalsIgnoreCase(result.getString(1))) {
                        throw new MissionException("MISSION_BACKUP_INTEGRITY_FAILED", "SQLite integrity check failed");
                    }
                }
                try (var result = statement.executeQuery("PRAGMA foreign_key_check")) {
                    if (result.next()) {
                        throw new MissionException(
                                "MISSION_BACKUP_REFERENCE_FAILED", "SQLite foreign key check failed");
                    }
                }
            }
            requireSchema(connection, "schema_migration", RUNTIME_SCHEMA_VERSION);
            requireSchema(connection, "personal_schema_history", MISSION_SCHEMA_VERSION);
            if (scalar(
                                    connection,
                                    "SELECT COUNT(*) FROM personal_mission_task_attempt a LEFT JOIN run r ON r.run_id=a.run_id WHERE a.run_id IS NOT NULL AND r.run_id IS NULL")
                            != 0
                    || scalar(
                                    connection,
                                    "SELECT COUNT(*) FROM personal_mission_task_attempt a LEFT JOIN session s ON s.session_id=a.session_id WHERE a.session_id IS NOT NULL AND s.session_id IS NULL")
                            != 0
                    || scalar(
                                    connection,
                                    "SELECT COUNT(*) FROM personal_mission m LEFT JOIN run r ON r.run_id=m.synthesis_run_id WHERE m.synthesis_run_id IS NOT NULL AND r.run_id IS NULL")
                            != 0
                    || scalar(
                                    connection,
                                    "SELECT COUNT(*) FROM personal_mission m LEFT JOIN session s ON s.session_id=m.synthesis_session_id WHERE m.synthesis_session_id IS NOT NULL AND s.session_id IS NULL")
                            != 0) {
                throw new MissionException(
                        "MISSION_BACKUP_REFERENCE_FAILED", "Mission Runtime references are incomplete");
            }
        }
        List<ArtifactRow> rows = artifactRows(database);
        Map<String, FileEntry> entries = new LinkedHashMap<>();
        for (FileEntry entry : manifest.artifacts())
            entries.put(Path.of(entry.relativePath()).getFileName().toString(), entry);
        if (entries.size() != rows.size()) {
            throw new MissionException("MISSION_BACKUP_REFERENCE_FAILED", "Artifact manifest does not match metadata");
        }
        for (ArtifactRow row : rows) {
            FileEntry entry = entries.get(row.payloadId());
            if (entry == null
                    || entry.byteCount() != row.byteCount()
                    || !entry.sha256().equals(row.sha256())) {
                throw new MissionException(
                        "MISSION_BACKUP_REFERENCE_FAILED", "Artifact metadata does not match manifest");
            }
            Path payload = artifactRoot.resolve(row.payloadId()).normalize();
            requireBelow(artifactRoot, payload);
            if (!Files.isRegularFile(payload, LinkOption.NOFOLLOW_LINKS)
                    || Files.size(payload) != row.byteCount()
                    || !sha256(payload).equals(row.sha256())) {
                throw new MissionException("MISSION_ARTIFACT_INTEGRITY_FAILED", "Artifact payload verification failed");
            }
        }
        verifyMissionArtifactIds(database);
    }

    private void verifyMissionArtifactIds(Path database) throws SQLException {
        Set<String> artifactIds = new LinkedHashSet<>();
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + database);
                var statement = connection.prepareStatement("SELECT artifact_id FROM artifact");
                var result = statement.executeQuery()) {
            while (result.next()) artifactIds.add(result.getString(1));
        }
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + database);
                var statement = connection.prepareStatement(
                        "SELECT final_artifact_id,artifact_refs_json FROM personal_mission WHERE final_artifact_id IS NOT NULL OR artifact_refs_json<>'[]'");
                var result = statement.executeQuery()) {
            while (result.next()) {
                String finalId = result.getString(1);
                if (finalId != null && !artifactIds.contains(finalId)) {
                    throw new MissionException(
                            "MISSION_BACKUP_REFERENCE_FAILED", "Final Artifact reference is missing");
                }
                List<String> referencedArtifactIds = mapper.readValue(
                        result.getString(2), mapper.getTypeFactory().constructCollectionType(List.class, String.class));
                for (String id : referencedArtifactIds) {
                    if (!artifactIds.contains(id)) {
                        throw new MissionException(
                                "MISSION_BACKUP_REFERENCE_FAILED", "Mission Artifact reference is missing");
                    }
                }
            }
        } catch (com.fasterxml.jackson.core.JsonProcessingException exception) {
            throw new MissionException(
                    "MISSION_BACKUP_REFERENCE_FAILED", "Mission Artifact reference is invalid", exception);
        }
    }

    private static List<ArtifactRow> artifactRows(Path database) throws SQLException {
        List<ArtifactRow> rows = new ArrayList<>();
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + database);
                var statement = connection.prepareStatement(
                        "SELECT payload_id,payload_sha256,payload_byte_count FROM artifact ORDER BY payload_id");
                var result = statement.executeQuery()) {
            while (result.next())
                rows.add(new ArtifactRow(result.getString(1), result.getString(2), result.getLong(3)));
        }
        return rows;
    }

    private static void requireSchema(java.sql.Connection connection, String table, int expected) throws SQLException {
        try (var statement = connection.prepareStatement("SELECT COALESCE(MAX(version),0) FROM " + table);
                var result = statement.executeQuery()) {
            if (!result.next() || result.getInt(1) != expected) {
                throw new MissionException(
                        "MISSION_BACKUP_SCHEMA_UNSUPPORTED", "Backup schema version is not supported by this release");
            }
        }
    }

    private static long scalar(java.sql.Connection connection, String sql) throws SQLException {
        try (var statement = connection.prepareStatement(sql);
                var result = statement.executeQuery()) {
            return result.next() ? result.getLong(1) : 0;
        }
    }

    private void writeManifest(Path target, BackupManifest manifest) throws IOException {
        Path temporary = target.resolve(
                "manifest.json.tmp-" + UUID.randomUUID().toString().replace("-", ""));
        mapper.writerWithDefaultPrettyPrinter().writeValue(temporary.toFile(), manifest);
        Files.move(temporary, target.resolve("manifest.json"), StandardCopyOption.ATOMIC_MOVE);
    }

    private void validateManifest(BackupManifest manifest) {
        if (manifest == null
                || !MANIFEST_SCHEMA.equals(manifest.schemaVersion())
                || !APPLICATION_VERSION.equals(manifest.applicationVersion())
                || manifest.runtimeSchemaVersion() != RUNTIME_SCHEMA_VERSION
                || manifest.missionSchemaVersion() != MISSION_SCHEMA_VERSION
                || manifest.productDigest() == null
                || !manifest.productDigest().equals(productDigest)
                || manifest.skillBinding() == null
                || !manifest.skillBinding().equals(skillBinding)) {
            throw new MissionException("MISSION_BACKUP_MANIFEST_UNSUPPORTED", "Backup manifest is unsupported");
        }
    }

    private static void validateFile(Path root, FileEntry entry) throws IOException {
        Path path = root.resolve(entry.relativePath()).normalize();
        requireBelow(root, path);
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)
                || Files.size(path) != entry.byteCount()
                || !sha256(path).equals(entry.sha256())) {
            throw new MissionException("MISSION_BACKUP_INTEGRITY_FAILED", "Backup file verification failed");
        }
    }

    private static FileEntry fileEntry(Path root, Path path) throws IOException {
        return new FileEntry(root.relativize(path).toString().replace('\\', '/'), Files.size(path), sha256(path));
    }

    private static String sha256(Path path) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream input = Files.newInputStream(path, StandardOpenOption.READ)) {
                byte[] buffer = new byte[64 * 1024];
                for (int read; (read = input.read(buffer)) >= 0; ) {
                    if (read > 0) digest.update(buffer, 0, read);
                }
            }
            return "sha256:" + HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is required", exception);
        }
    }

    private static void copyNew(Path source, Path destination) throws IOException {
        Files.copy(source, destination, StandardCopyOption.COPY_ATTRIBUTES);
    }

    private static Path normalizeFreshDirectory(Path path, boolean allowAbsent) {
        Path normalized =
                java.util.Objects.requireNonNull(path).toAbsolutePath().normalize();
        if (Files.isSymbolicLink(normalized)) {
            throw new MissionException("MISSION_MAINTENANCE_PATH_INVALID", "Maintenance path cannot be a symlink");
        }
        if (Files.exists(normalized, LinkOption.NOFOLLOW_LINKS)) {
            try (var children = Files.list(normalized)) {
                if (children.findAny().isPresent()) {
                    throw new MissionException(
                            "MISSION_MAINTENANCE_PATH_NOT_FRESH", "Maintenance target must be a fresh directory");
                }
            } catch (IOException exception) {
                throw new MissionException(
                        "MISSION_MAINTENANCE_PATH_INVALID", "Maintenance path is unavailable", exception);
            }
        } else if (!allowAbsent && normalized.getParent() == null) {
            throw new MissionException("MISSION_MAINTENANCE_PATH_INVALID", "Restore target is invalid");
        }
        return normalized;
    }

    private static Path normalizeExistingDirectory(Path path) {
        Path normalized =
                java.util.Objects.requireNonNull(path).toAbsolutePath().normalize();
        if (!Files.isDirectory(normalized, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(normalized)) {
            throw new MissionException("MISSION_BACKUP_PATH_INVALID", "Backup directory is unavailable");
        }
        return normalized;
    }

    private static void requireBelow(Path root, Path path) {
        Path normalizedRoot = root.toAbsolutePath().normalize();
        Path normalized = path.toAbsolutePath().normalize();
        if (!normalized.startsWith(normalizedRoot)
                || normalized.equals(normalizedRoot)
                || Files.isSymbolicLink(normalized)) {
            throw new MissionException("MISSION_MAINTENANCE_PATH_INVALID", "Maintenance file escaped its root");
        }
    }

    private Instant now() {
        return Instant.ofEpochMilli(clock.instant().toEpochMilli());
    }

    private record ArtifactRow(String payloadId, String sha256, long byteCount) {}

    public record FileEntry(String relativePath, long byteCount, String sha256) {}

    public record BackupManifest(
            String schemaVersion,
            String applicationVersion,
            int runtimeSchemaVersion,
            int missionSchemaVersion,
            String productDigest,
            String skillBinding,
            Instant createdAt,
            FileEntry database,
            List<FileEntry> artifacts) {
        public BackupManifest {
            artifacts = List.copyOf(artifacts == null ? List.of() : artifacts);
        }
    }

    public record BackupResult(Path directory, BackupManifest manifest) {}
}
