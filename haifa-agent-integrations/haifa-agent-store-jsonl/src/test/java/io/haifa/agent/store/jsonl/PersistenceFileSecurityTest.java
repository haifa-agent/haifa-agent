package io.haifa.agent.store.jsonl;

import static org.assertj.core.api.Assertions.assertThat;

import io.haifa.agent.store.sqlite.SqliteStoreConfiguration;
import io.haifa.agent.store.sqlite.SqliteStoreFoundation;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.AclEntryType;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PersistenceFileSecurityTest {
    private static final Set<PosixFilePermission> DIRECTORY_0700 =
            Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE);
    private static final Set<PosixFilePermission> FILE_0600 =
            Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE);

    @Test
    void appliesAndVerifiesPlatformPermissionsForSqliteAndEveryTranscriptFile(@TempDir Path directory)
            throws Exception {
        Path sqliteRoot = Files.createDirectory(directory.resolve("sqlite"));
        Path database = sqliteRoot.resolve("runtime.db");
        Path transcriptRoot = Files.createDirectory(directory.resolve("transcripts"));

        try (SqliteStoreFoundation foundation = SqliteStoreFoundation.initialize(
                        SqliteStoreConfiguration.defaults(database), Clock.systemUTC());
                var connection = foundation.connections().openConnection()) {
            try (var statement = connection.createStatement()) {
                statement.execute("BEGIN IMMEDIATE");
                statement.execute("CREATE TABLE permission_probe(value TEXT)");
            }

            JsonlTranscriptWriter writer = new JsonlTranscriptWriter(transcriptRoot, 1024);
            for (int sequence = 1; sequence <= 12; sequence++) {
                writer.appendAndForce(new SafeTranscriptEvent(
                        "1",
                        "permission-event-" + sequence,
                        "permission-run",
                        sequence,
                        Instant.parse("2026-07-25T08:00:00Z"),
                        "run.completed",
                        Map.of("status", "COMPLETED", "version", sequence)));
            }

            assertSecure(sqliteRoot, true);
            try (var sqliteFiles = Files.list(sqliteRoot)) {
                var files = sqliteFiles
                        .filter(path -> Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
                        .toList();
                assertThat(files).anyMatch(path -> path.getFileName().toString().equals("runtime.db"));
                files.forEach(path -> assertSecure(path, false));
            }
            assertSecure(transcriptRoot, true);
            try (var transcriptFiles = Files.list(transcriptRoot)) {
                var files = transcriptFiles
                        .filter(path -> Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
                        .toList();
                assertThat(files)
                        .anyMatch(path -> path.getFileName().toString().equals("permission-run.lock"))
                        .anyMatch(path -> path.getFileName().toString().equals("permission-run.jsonl"))
                        .anyMatch(path -> path.getFileName().toString().matches("permission-run\\.\\d{6}\\.jsonl"));
                files.forEach(path -> assertSecure(path, false));
            }
        }
    }

    private static void assertSecure(Path path, boolean directory) {
        try {
            if (Files.getFileStore(path).supportsFileAttributeView("posix")) {
                assertThat(Files.getPosixFilePermissions(path, LinkOption.NOFOLLOW_LINKS))
                        .isEqualTo(directory ? DIRECTORY_0700 : FILE_0600);
                return;
            }
            AclFileAttributeView view =
                    Files.getFileAttributeView(path, AclFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
            assertThat(view)
                    .as("platform must expose POSIX permissions or ACLs")
                    .isNotNull();
            var current = path.getFileSystem()
                    .getUserPrincipalLookupService()
                    .lookupPrincipalByName(System.getProperty("user.name"));
            assertThat(view.getAcl()).singleElement().satisfies(entry -> {
                assertThat(entry.type()).isEqualTo(AclEntryType.ALLOW);
                assertThat(entry.principal()).isEqualTo(current);
            });
        } catch (java.io.IOException exception) {
            throw new AssertionError("cannot verify secure file permissions", exception);
        }
    }
}
