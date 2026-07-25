package io.haifa.agent.store.jsonl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.haifa.agent.common.io.SecureFilePermissions;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Diagnostic reader for projected transcripts. It never reconstructs Runtime state. */
public final class JsonlTranscriptReader {
    private final TranscriptPathResolver paths;
    private final SafeTranscriptMapperRegistry mappers;
    private final TranscriptRedactor redactor;
    private final ObjectMapper objectMapper;

    public JsonlTranscriptReader(Path root) {
        this(root, SafeTranscriptMapperRegistry.defaults(), new TranscriptRedactor());
    }

    public JsonlTranscriptReader(Path root, SafeTranscriptMapperRegistry mappers, TranscriptRedactor redactor) {
        this.paths = new TranscriptPathResolver(root);
        this.mappers = Objects.requireNonNull(mappers, "mappers must not be null");
        this.redactor = Objects.requireNonNull(redactor, "redactor must not be null");
        this.objectMapper = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    }

    public TranscriptReadResult read(String runId) {
        List<Path> files = paths.transcriptFiles(runId);
        if (files.isEmpty()) {
            return new TranscriptReadResult(List.of(), false, 0, 0);
        }
        Map<String, SafeTranscriptEvent> unique = new LinkedHashMap<>();
        int duplicates = 0;
        int lineNumber = 0;
        boolean truncatedTail = false;
        long repairOffset = 0;
        Path current = paths.resolve(runId);
        for (int fileIndex = 0; fileIndex < files.size(); fileIndex++) {
            Path file = files.get(fileIndex);
            byte[] bytes = read(file);
            int finalNewline = lastNewline(bytes);
            boolean truncated = finalNewline != bytes.length - 1;
            boolean currentFile = file.equals(current);
            if (truncated && (!currentFile || fileIndex != files.size() - 1)) {
                throw corruption("truncated transcript segment", lineNumber + 1, null);
            }
            if (currentFile) {
                truncatedTail = truncated;
                repairOffset = finalNewline + 1L;
            }
            int completeLength = finalNewline + 1;
            int start = 0;
            for (int index = 0; index < completeLength; index++) {
                if (bytes[index] != '\n') continue;
                lineNumber++;
                byte[] line = Arrays.copyOfRange(bytes, start, index);
                start = index + 1;
                if (line.length == 0) {
                    throw corruption("empty transcript line", lineNumber, null);
                }
                SafeTranscriptEvent event = parse(line, lineNumber);
                if (!event.runId().equals(runId)) {
                    throw corruption("transcript line belongs to a different run", lineNumber, null);
                }
                SafeTranscriptEvent existing = unique.putIfAbsent(event.eventId(), event);
                if (existing != null) {
                    if (!existing.equals(event)) {
                        throw corruption("duplicate event ID has conflicting content", lineNumber, null);
                    }
                    duplicates++;
                }
            }
        }
        return new TranscriptReadResult(new ArrayList<>(unique.values()), truncatedTail, duplicates, repairOffset);
    }

    public boolean repairTruncatedTail(String runId) {
        TranscriptReadResult result = read(runId);
        if (!result.truncatedTail()) return false;
        Path path = paths.resolve(runId);
        Path lockPath = paths.lock(runId);
        try (var lockChannel = java.nio.channels.FileChannel.open(
                        lockPath, StandardOpenOption.CREATE, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
                var ignored = lockChannel.lock()) {
            SecureFilePermissions.secureFile(lockPath);
            try (var channel =
                    java.nio.channels.FileChannel.open(path, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)) {
                channel.truncate(result.repairOffset());
                channel.force(true);
            }
            return true;
        } catch (IOException exception) {
            throw new TranscriptProjectionException(
                    TranscriptDiagnosticCode.WRITE_FAILED, "cannot repair transcript tail", exception);
        }
    }

    private SafeTranscriptEvent parse(byte[] line, int lineNumber) {
        try {
            SafeTranscriptEvent event = objectMapper.readValue(line, SafeTranscriptEvent.class);
            if (!SafeTranscriptEvent.CURRENT_SCHEMA_VERSION.equals(event.schemaVersion())) {
                throw corruption("unsupported transcript schema", lineNumber, null);
            }
            if (event.eventType().equals("transcript.rotated")) {
                validateRotation(event, lineNumber);
            } else if (!mappers.supports(event.eventType())) {
                throw corruption("unknown transcript event type", lineNumber, null);
            }
            return redactor.redact(event);
        } catch (TranscriptProjectionException exception) {
            if (exception.code() == TranscriptDiagnosticCode.MIDDLE_CORRUPTION) throw exception;
            throw corruption("unsafe transcript line", lineNumber, exception);
        } catch (IOException | RuntimeException exception) {
            throw corruption("malformed transcript line", lineNumber, exception);
        }
    }

    private static void validateRotation(SafeTranscriptEvent event, int lineNumber) {
        if (!event.eventId().startsWith("transcript-rotation:" + event.runId() + ":")
                || !event.payload().keySet().equals(java.util.Set.of("segment"))
                || !(event.payload().get("segment") instanceof Number segment)
                || segment.longValue() < 1) {
            throw corruption("invalid transcript rotation marker", lineNumber, null);
        }
    }

    private static byte[] read(Path path) {
        try {
            return Files.readAllBytes(path);
        } catch (IOException exception) {
            throw new TranscriptProjectionException(
                    TranscriptDiagnosticCode.WRITE_FAILED, "cannot read transcript", exception);
        }
    }

    private static TranscriptProjectionException corruption(String message, int lineNumber, Throwable cause) {
        String detail = message + " at line " + lineNumber;
        return cause == null
                ? new TranscriptProjectionException(TranscriptDiagnosticCode.MIDDLE_CORRUPTION, detail)
                : new TranscriptProjectionException(TranscriptDiagnosticCode.MIDDLE_CORRUPTION, detail, cause);
    }

    private static int lastNewline(byte[] bytes) {
        for (int index = bytes.length - 1; index >= 0; index--) {
            if (bytes[index] == '\n') return index;
        }
        return -1;
    }
}
