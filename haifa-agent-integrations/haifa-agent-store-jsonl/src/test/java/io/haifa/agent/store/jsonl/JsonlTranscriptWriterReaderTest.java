package io.haifa.agent.store.jsonl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class JsonlTranscriptWriterReaderTest {
    private static final Instant NOW = Instant.parse("2026-07-25T08:00:00Z");

    @TempDir
    Path directory;

    @Test
    void writesWholeUtf8LinesAndReaderDeduplicatesAtLeastOnceDelivery() throws Exception {
        JsonlTranscriptWriter writer = new JsonlTranscriptWriter(directory);
        SafeTranscriptEvent event = event("event-1", 1);

        writer.appendAndForce(event);
        writer.appendAndForce(event);

        byte[] bytes = Files.readAllBytes(writer.transcriptPath("run-1"));
        assertThat(bytes[bytes.length - 1]).isEqualTo((byte) '\n');
        assertThat(new String(bytes, StandardCharsets.UTF_8)).contains("COMPLETED");
        TranscriptReadResult result = new JsonlTranscriptReader(directory).read("run-1");
        assertThat(result.events()).containsExactly(event);
        assertThat(result.duplicateCount()).isEqualTo(1);
        assertThat(result.truncatedTail()).isFalse();
    }

    @Test
    void persistsTranscriptTimestampAtMillisecondPrecision() throws Exception {
        JsonlTranscriptWriter writer = new JsonlTranscriptWriter(directory);
        Instant precise = Instant.parse("2026-07-25T08:00:00.123456789Z");
        SafeTranscriptEvent event = new SafeTranscriptEvent(
                "1", "event-precise", "run-1", 1, precise, "run.completed", Map.of("status", "COMPLETED"));

        writer.appendAndForce(event);

        String line = Files.readString(writer.transcriptPath("run-1"), StandardCharsets.UTF_8);
        assertThat(line).contains("2026-07-25T08:00:00.123Z").doesNotContain("456789");
        assertThat(new JsonlTranscriptReader(directory)
                        .read("run-1")
                        .events()
                        .getFirst()
                        .occurredAt())
                .isEqualTo(Instant.parse("2026-07-25T08:00:00.123Z"));
    }

    @Test
    void diagnosesAndRepairsOnlyATruncatedTail() throws Exception {
        JsonlTranscriptWriter writer = new JsonlTranscriptWriter(directory);
        writer.appendAndForce(event("event-1", 1));
        Path path = writer.transcriptPath("run-1");
        Files.writeString(path, "{\"schemaVersion\":", StandardCharsets.UTF_8, StandardOpenOption.APPEND);
        JsonlTranscriptReader reader = new JsonlTranscriptReader(directory);

        TranscriptReadResult degraded = reader.read("run-1");
        assertThat(degraded.truncatedTail()).isTrue();
        assertThat(degraded.events()).hasSize(1);
        assertThat(reader.repairTruncatedTail("run-1")).isTrue();
        assertThat(reader.read("run-1").truncatedTail()).isFalse();
    }

    @Test
    void stopsOnMiddleCorruption() throws Exception {
        JsonlTranscriptWriter writer = new JsonlTranscriptWriter(directory);
        Path path = writer.transcriptPath("run-1");
        Files.writeString(
                path, "{\"broken\":}\n{\"still\":\"not-read\"}\n", StandardCharsets.UTF_8, StandardOpenOption.CREATE);

        assertThatThrownBy(() -> new JsonlTranscriptReader(directory).read("run-1"))
                .isInstanceOf(TranscriptProjectionException.class)
                .extracting(exception -> ((TranscriptProjectionException) exception).code())
                .isEqualTo(TranscriptDiagnosticCode.MIDDLE_CORRUPTION);
    }

    @Test
    void rotatesUnderTheStableLockAndReadsSegmentsInOrder() throws Exception {
        JsonlTranscriptWriter writer = new JsonlTranscriptWriter(directory, 1024);
        for (int sequence = 1; sequence <= 12; sequence++) {
            writer.appendAndForce(event("event-" + sequence, sequence));
        }

        try (var entries = Files.list(directory)) {
            assertThat(entries.map(path -> path.getFileName().toString()).toList())
                    .anyMatch(name -> name.matches("run-1\\.\\d{6}\\.jsonl"))
                    .contains("run-1.jsonl", "run-1.lock");
        }
        TranscriptReadResult result = new JsonlTranscriptReader(directory).read("run-1");
        assertThat(result.events())
                .filteredOn(event -> event.eventType().equals("run.completed"))
                .extracting(SafeTranscriptEvent::eventId)
                .containsExactly(java.util.stream.IntStream.rangeClosed(1, 12)
                        .mapToObj(sequence -> "event-" + sequence)
                        .toArray(String[]::new));
        assertThat(result.events())
                .anyMatch(event -> event.eventType().equals("transcript.rotated")
                        && event.payload().containsKey("segment"));
        assertThat(result.truncatedTail()).isFalse();
    }

    @Test
    void rejectsTraversalAndConcurrentThreadWriter() throws Exception {
        JsonlTranscriptWriter writer = new JsonlTranscriptWriter(directory);
        assertThatThrownBy(() -> writer.transcriptPath("../outside"))
                .isInstanceOf(TranscriptProjectionException.class)
                .extracting(exception -> ((TranscriptProjectionException) exception).code())
                .isEqualTo(TranscriptDiagnosticCode.INVALID_RUN_ID);

        Path path = writer.lockPath("run-1");
        try (FileChannel channel = FileChannel.open(
                        path, StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.APPEND);
                var ignored = channel.lock()) {
            assertThatThrownBy(() -> writer.appendAndForce(event("event-1", 1)))
                    .isInstanceOf(TranscriptProjectionException.class)
                    .extracting(exception -> ((TranscriptProjectionException) exception).code())
                    .isEqualTo(TranscriptDiagnosticCode.FILE_LOCKED);
        }
    }

    @Test
    void rejectsWriterHeldByAnotherProcess() throws Exception {
        JsonlTranscriptWriter writer = new JsonlTranscriptWriter(directory);
        Path path = writer.lockPath("run-1");
        Path ready = directory.resolve("lock-ready");
        Process process = new ProcessBuilder(
                        javaExecutable(),
                        "-cp",
                        System.getProperty("java.class.path"),
                        LockHolder.class.getName(),
                        path.toString(),
                        ready.toString())
                .redirectErrorStream(true)
                .start();
        try {
            awaitReady(process, ready);
            assertThatThrownBy(() -> writer.appendAndForce(event("event-1", 1)))
                    .isInstanceOf(TranscriptProjectionException.class)
                    .extracting(exception -> ((TranscriptProjectionException) exception).code())
                    .isEqualTo(TranscriptDiagnosticCode.FILE_LOCKED);
        } finally {
            process.destroyForcibly();
            process.waitFor(5, TimeUnit.SECONDS);
        }
    }

    public static final class LockHolder {
        private LockHolder() {}

        public static void main(String[] arguments) throws Exception {
            Path path = Path.of(arguments[0]);
            Path ready = Path.of(arguments[1]);
            try (FileChannel channel = FileChannel.open(
                            path, StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.APPEND);
                    var ignored = channel.lock()) {
                Files.writeString(ready, "ready", StandardOpenOption.CREATE_NEW);
                Thread.sleep(30_000);
            }
        }
    }

    private static void awaitReady(Process process, Path ready) throws Exception {
        long deadlineMillis = System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(10);
        while (!Files.exists(ready) && process.isAlive() && System.currentTimeMillis() < deadlineMillis) {
            Thread.sleep(20);
        }
        if (!Files.exists(ready)) {
            throw new AssertionError("lock holder did not start: "
                    + new String(process.getInputStream().readAllBytes()));
        }
    }

    private static String javaExecutable() {
        return Path.of(System.getProperty("java.home"), "bin", isWindows() ? "java.exe" : "java")
                .toString();
    }

    private static boolean isWindows() {
        return System.getProperty("os.name").toLowerCase(java.util.Locale.ROOT).contains("win");
    }

    private static SafeTranscriptEvent event(String id, long sequence) {
        return new SafeTranscriptEvent(
                "1", id, "run-1", sequence, NOW, "run.completed", Map.of("status", "COMPLETED", "version", 4));
    }
}
