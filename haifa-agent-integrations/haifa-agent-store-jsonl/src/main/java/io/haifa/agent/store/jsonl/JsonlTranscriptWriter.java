package io.haifa.agent.store.jsonl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.haifa.agent.common.io.SecureFilePermissions;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Map;
import java.util.Objects;

/** Writes one complete UTF-8 line under an OS lock and forces it before returning. */
public final class JsonlTranscriptWriter {
    public static final long DEFAULT_MAXIMUM_FILE_BYTES = 16L * 1024 * 1024;

    private final TranscriptPathResolver paths;
    private final ObjectMapper objectMapper;
    private final TranscriptWriteHook hook;
    private final long maximumFileBytes;

    public JsonlTranscriptWriter(Path root) {
        this(root, DEFAULT_MAXIMUM_FILE_BYTES, TranscriptWriteHook.none());
    }

    public JsonlTranscriptWriter(Path root, TranscriptWriteHook hook) {
        this(root, DEFAULT_MAXIMUM_FILE_BYTES, hook);
    }

    public JsonlTranscriptWriter(Path root, long maximumFileBytes) {
        this(root, maximumFileBytes, TranscriptWriteHook.none());
    }

    public JsonlTranscriptWriter(Path root, long maximumFileBytes, TranscriptWriteHook hook) {
        this.paths = new TranscriptPathResolver(root);
        this.hook = Objects.requireNonNull(hook, "hook must not be null");
        if (maximumFileBytes < 1024) {
            throw new IllegalArgumentException("maximum transcript file bytes must be at least 1024");
        }
        this.maximumFileBytes = maximumFileBytes;
        this.objectMapper = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    }

    public Path transcriptPath(String runId) {
        return paths.resolve(runId);
    }

    Path lockPath(String runId) {
        return paths.lock(runId);
    }

    public void appendAndForce(SafeTranscriptEvent event) {
        Objects.requireNonNull(event, "event must not be null");
        Path path = paths.resolve(event.runId());
        hook.beforeWrite(event);
        byte[] line = line(event);
        Path lockPath = paths.lock(event.runId());
        try (FileChannel channel = FileChannel.open(
                        lockPath, StandardOpenOption.CREATE, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
                FileLock ignored = tryLock(channel, lockPath)) {
            secureFile(lockPath);
            rotateIfRequired(event, path, line.length);
            append(path, line);
            hook.afterWriteBeforeForce(event);
            force(path);
            hook.afterForce(event);
        } catch (TranscriptProjectionException exception) {
            throw exception;
        } catch (IOException exception) {
            throw new TranscriptProjectionException(
                    TranscriptDiagnosticCode.WRITE_FAILED, "cannot durably append transcript line", exception);
        }
    }

    private void rotateIfRequired(SafeTranscriptEvent event, Path path, int nextLineBytes) throws IOException {
        if (!java.nio.file.Files.exists(path, LinkOption.NOFOLLOW_LINKS)
                || java.nio.file.Files.size(path) == 0
                || java.nio.file.Files.size(path) + nextLineBytes <= maximumFileBytes) {
            return;
        }
        int index = paths.nextRotationIndex(event.runId());
        SafeTranscriptEvent marker = new SafeTranscriptEvent(
                SafeTranscriptEvent.CURRENT_SCHEMA_VERSION,
                "transcript-rotation:" + event.runId() + ":" + index,
                event.runId(),
                event.sequence(),
                event.occurredAt(),
                "transcript.rotated",
                Map.of("segment", index));
        append(path, line(marker));
        force(path);
        Path rotated = paths.rotated(event.runId(), index);
        java.nio.file.Files.move(path, rotated, StandardCopyOption.ATOMIC_MOVE);
        secureFile(rotated);
    }

    private void append(Path path, byte[] line) throws IOException {
        try (FileChannel data = FileChannel.open(
                path,
                StandardOpenOption.CREATE,
                StandardOpenOption.WRITE,
                StandardOpenOption.APPEND,
                LinkOption.NOFOLLOW_LINKS)) {
            secureFile(path);
            ByteBuffer buffer = ByteBuffer.wrap(line);
            while (buffer.hasRemaining()) data.write(buffer);
        }
    }

    private static void force(Path path) throws IOException {
        try (FileChannel data = FileChannel.open(path, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)) {
            data.force(true);
        }
    }

    private byte[] line(SafeTranscriptEvent event) {
        try {
            byte[] json = objectMapper.writeValueAsBytes(event);
            byte[] line = new byte[json.length + 1];
            System.arraycopy(json, 0, line, 0, json.length);
            line[line.length - 1] = '\n';
            return line;
        } catch (IOException exception) {
            throw new TranscriptProjectionException(
                    TranscriptDiagnosticCode.WRITE_FAILED, "cannot serialize transcript event", exception);
        }
    }

    private static void secureFile(Path path) {
        try {
            SecureFilePermissions.secureFile(path);
        } catch (IOException exception) {
            throw new TranscriptProjectionException(
                    TranscriptDiagnosticCode.PERMISSION_DENIED,
                    "cannot apply secure transcript file permissions",
                    exception);
        }
    }

    private static FileLock tryLock(FileChannel channel, Path path) throws IOException {
        try {
            FileLock lock = channel.tryLock();
            if (lock != null) return lock;
        } catch (OverlappingFileLockException exception) {
            throw new TranscriptProjectionException(
                    TranscriptDiagnosticCode.FILE_LOCKED, "transcript already has a writer: " + path, exception);
        }
        throw new TranscriptProjectionException(
                TranscriptDiagnosticCode.FILE_LOCKED, "transcript already has a writer: " + path);
    }
}
