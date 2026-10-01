package io.haifa.agent.store.sqlite;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SqliteFrozenInstructionDiagnosticTest {
    @Test
    void waitingDigestSurvivesTwoDistinctJvmsWithDifferentCurrentProfiles() throws Exception {
        Path evidence = evidence("two-jvm");
        long writer = launch(evidence, "write");
        long reader = launch(evidence, "read");
        assertThat(reader).isNotEqualTo(writer);
        assertThat(Files.readString(evidence.resolve("read.raw.log")))
                .contains("calls=0 writes=0", "status=WAITING_APPROVAL");
    }

    @Test
    void sameProcessRebuildAlsoUsesOriginalFrozenInstruction(@TempDir Path directory) throws Exception {
        FrozenInstructionDiagnosticProcess.execute(directory, "write");
        FrozenInstructionDiagnosticProcess.execute(directory, "read");
    }

    @Test
    void realApprovalResumeRetainsAAndNextRunUsesB() throws Exception {
        Path evidence = evidence("lifecycle");
        launch(evidence, "write");
        launch(evidence, "lifecycle");
        assertThat(Files.readString(evidence.resolve("lifecycle.raw.log")))
                .contains("terminal=COMPLETED", "next_hash=");
    }

    @Test
    void actualSqlitePayloadHashRowHashAndMissingSnapshotFailWithFixedSafeError() throws Exception {
        for (String mutation : List.of(
                "UPDATE configuration_snapshot SET content_payload_hash = 'sha256:0000000000000000000000000000000000000000000000000000000000000000'",
                "UPDATE configuration_snapshot SET content_hash = 'sha256:0000000000000000000000000000000000000000000000000000000000000000'",
                "UPDATE configuration_snapshot SET content_payload = X'000102030405'",
                "DELETE FROM configuration_snapshot")) {
            Path evidence = evidence("corrupt");
            FrozenInstructionDiagnosticProcess.execute(evidence, "write");
            long eventsBefore;
            try (var connection = DriverManager.getConnection("jdbc:sqlite:" + evidence.resolve("runtime.sqlite"));
                    var statement = connection.createStatement()) {
                try (var rows = statement.executeQuery("SELECT COUNT(*) FROM run")) {
                    assertThat(rows.next()).isTrue();
                    assertThat(rows.getLong(1)).isEqualTo(1);
                }
                try (var rows = statement.executeQuery("SELECT COUNT(*) FROM runtime_event")) {
                    assertThat(rows.next()).isTrue();
                    eventsBefore = rows.getLong(1);
                }
                assertThat(statement.executeUpdate(mutation)).isEqualTo(1);
            }
            launch(evidence, "corrupt-read");
            try (var connection = DriverManager.getConnection("jdbc:sqlite:" + evidence.resolve("runtime.sqlite"));
                    var statement = connection.createStatement()) {
                try (var rows = statement.executeQuery("SELECT COUNT(*) FROM run")) {
                    assertThat(rows.next()).isTrue();
                    assertThat(rows.getLong(1)).isEqualTo(1);
                }
                try (var rows = statement.executeQuery("SELECT COUNT(*) FROM runtime_event")) {
                    assertThat(rows.next()).isTrue();
                    assertThat(rows.getLong(1)).isEqualTo(eventsBefore);
                }
            }
            assertThat(Files.readString(evidence.resolve("corrupt-read.raw.log")))
                    .contains("FIXED_SAFE_FAILURE code=INTERNAL_ERROR", "calls=0 writes=0")
                    .doesNotContain("Synthetic frozen instruction", "Synthetic current instruction");
        }
    }

    private static Path evidence(String kind) throws Exception {
        Path root = Path.of(System.getProperty(
                        "frozen.diagnostic.evidence", "../../local-tmp/sdk-frozen-instruction/evidence"))
                .toAbsolutePath()
                .normalize();
        return Files.createDirectories(root.resolve(kind + "-" + UUID.randomUUID()));
    }

    private static long launch(Path evidence, String mode) throws Exception {
        String java =
                Path.of(System.getProperty("java.home"), "bin", "java.exe").toString();
        if (!Files.exists(Path.of(java)))
            java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        String classpath = System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"));
        Process process = new ProcessBuilder(
                        java,
                        "-Xmx128m",
                        "-XX:+UseSerialGC",
                        "-cp",
                        classpath,
                        FrozenInstructionDiagnosticProcess.class.getName(),
                        mode,
                        evidence.toString())
                .redirectErrorStream(true)
                .redirectOutput(evidence.resolve(mode + ".raw.log").toFile())
                .start();
        try {
            assertThat(process.waitFor(40, TimeUnit.SECONDS))
                    .as("bounded child JVM completion; evidence=%s", evidence)
                    .isTrue();
            Files.writeString(evidence.resolve(mode + ".exit.txt"), Integer.toString(process.exitValue()));
            assertThat(process.exitValue())
                    .as("child JVM status; raw evidence=%s", evidence)
                    .isZero();
            return process.pid();
        } finally {
            if (process.isAlive()) {
                process.destroyForcibly();
                process.waitFor(5, TimeUnit.SECONDS);
            }
        }
    }
}
