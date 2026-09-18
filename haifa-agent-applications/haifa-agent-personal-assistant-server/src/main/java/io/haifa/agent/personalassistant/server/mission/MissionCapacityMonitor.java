package io.haifa.agent.personalassistant.server.mission;

import io.haifa.agent.personalassistant.server.configuration.product.PersonalAssistantProperties;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.DriverManager;
import java.util.HexFormat;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/** Bounded, read-only database and Artifact capacity inspection for admission and diagnostics. */
public final class MissionCapacityMonitor {
    private static final long MAX_INSPECTED_ARTIFACT_FILES = 100_000;

    private final Path database;
    private final Path artifactRoot;
    private final PersonalAssistantProperties.Mission limits;
    private final AtomicLong lastIntegrityCheckMillis = new AtomicLong();
    private final AtomicReference<String> integrityBlocker =
            new AtomicReference<>("MISSION_ARTIFACT_INTEGRITY_UNVERIFIED");

    public MissionCapacityMonitor(Path dataDirectory, PersonalAssistantProperties.Mission limits) {
        Path root = dataDirectory.toAbsolutePath().normalize();
        this.database = root.resolve("personal-assistant.sqlite");
        this.artifactRoot = root.resolve("artifacts");
        this.limits = java.util.Objects.requireNonNull(limits);
    }

    public CapacitySnapshot snapshot() {
        try {
            refreshIntegrityIfDue();
            long databaseBytes = size(database)
                    + size(database.resolveSibling(database.getFileName() + "-wal"))
                    + size(database.resolveSibling(database.getFileName() + "-shm"));
            long artifactBytes = 0;
            long artifactFiles = 0;
            if (Files.exists(artifactRoot, LinkOption.NOFOLLOW_LINKS)) {
                try (var paths = Files.walk(artifactRoot)) {
                    var iterator = paths.iterator();
                    while (iterator.hasNext()) {
                        Path path = iterator.next();
                        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) continue;
                        artifactFiles++;
                        if (artifactFiles > MAX_INSPECTED_ARTIFACT_FILES) {
                            return new CapacitySnapshot(
                                    databaseBytes,
                                    artifactBytes,
                                    artifactFiles,
                                    true,
                                    true,
                                    false,
                                    "MISSION_CAPACITY_SCAN_LIMIT");
                        }
                        artifactBytes = Math.addExact(artifactBytes, Files.size(path));
                    }
                }
            }
            boolean databaseWarning = databaseBytes >= limits.dbWarningBytes();
            boolean artifactWarning = artifactBytes >= limits.artifactWarningBytes();
            boolean accepting = databaseBytes < limits.dbStopBytes()
                    && artifactBytes < limits.artifactStopBytes()
                    && "NONE".equals(integrityBlocker.get());
            String blocker = accepting
                    ? "NONE"
                    : databaseBytes >= limits.dbStopBytes()
                            ? "MISSION_DATABASE_CAPACITY_EXHAUSTED"
                            : artifactBytes >= limits.artifactStopBytes()
                                    ? "MISSION_ARTIFACT_CAPACITY_EXHAUSTED"
                                    : integrityBlocker.get();
            return new CapacitySnapshot(
                    databaseBytes, artifactBytes, artifactFiles, databaseWarning, artifactWarning, accepting, blocker);
        } catch (IOException | ArithmeticException exception) {
            return new CapacitySnapshot(0, 0, 0, true, true, false, "MISSION_CAPACITY_UNAVAILABLE");
        }
    }

    public String refreshIntegrity() {
        String blocker = verifyArtifactIntegrity();
        integrityBlocker.set(blocker);
        lastIntegrityCheckMillis.set(System.currentTimeMillis());
        return blocker;
    }

    private void refreshIntegrityIfDue() {
        long now = System.currentTimeMillis();
        if (now - lastIntegrityCheckMillis.get() >= 30_000) refreshIntegrity();
    }

    private String verifyArtifactIntegrity() {
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + database);
                var statement = connection.prepareStatement(
                        "SELECT payload_id,payload_sha256,payload_byte_count FROM artifact ORDER BY payload_id");
                var result = statement.executeQuery()) {
            while (result.next()) {
                String id = result.getString(1);
                if (id == null || !id.matches("[a-f0-9]{32}")) return "MISSION_ARTIFACT_INTEGRITY_FAILED";
                Path payload = artifactRoot.resolve(id).normalize();
                if (!payload.getParent().equals(artifactRoot)
                        || !Files.isRegularFile(payload, LinkOption.NOFOLLOW_LINKS)
                        || Files.size(payload) != result.getLong(3)
                        || !("sha256:" + sha256(payload)).equals(result.getString(2))) {
                    return "MISSION_ARTIFACT_INTEGRITY_FAILED";
                }
            }
            return "NONE";
        } catch (Exception exception) {
            return "MISSION_ARTIFACT_INTEGRITY_UNAVAILABLE";
        }
    }

    private static String sha256(Path path) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (var input = Files.newInputStream(path)) {
                byte[] buffer = new byte[64 * 1024];
                for (int read; (read = input.read(buffer)) >= 0; ) {
                    if (read > 0) digest.update(buffer, 0, read);
                }
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is required", exception);
        }
    }

    private static long size(Path path) throws IOException {
        return Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) ? Files.size(path) : 0;
    }

    public record CapacitySnapshot(
            long databaseBytes,
            long artifactBytes,
            long artifactFiles,
            boolean databaseWarning,
            boolean artifactWarning,
            boolean acceptingNewWork,
            String blockerCode) {}
}
