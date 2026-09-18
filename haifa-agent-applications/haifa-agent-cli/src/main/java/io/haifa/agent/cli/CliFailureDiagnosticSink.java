package io.haifa.agent.cli;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.haifa.agent.application.project.persistence.ProjectPersistenceConfiguration;
import io.haifa.agent.common.io.SecureFilePermissions;
import io.haifa.agent.runtime.core.trace.FailureDiagnosticSink;
import io.haifa.agent.runtime.core.trace.RuntimeTraceEvent;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

/** Durable, bounded CLI diagnostics that deliberately omit exception messages and application data. */
final class CliFailureDiagnosticSink implements FailureDiagnosticSink {
    private static final Pattern SAFE_ID = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,127}");
    private static final int MAX_CAUSES = 8;
    private static final int MAX_FRAMES = 24;
    private static final int MAX_TEXT = 256;

    private final Path root;
    private final ObjectMapper json = new ObjectMapper();

    private CliFailureDiagnosticSink(Path root) {
        this.root = initialize(root);
    }

    static FailureDiagnosticSink forPersistence(ProjectPersistenceConfiguration persistence) {
        Objects.requireNonNull(persistence, "persistence must not be null");
        return persistence
                .databasePath()
                .map(Path::getParent)
                .map(parent -> (FailureDiagnosticSink) forDirectory(parent.resolve("diagnostics")))
                .orElseGet(FailureDiagnosticSink::noop);
    }

    static CliFailureDiagnosticSink forDirectory(Path root) {
        return new CliFailureDiagnosticSink(root);
    }

    @Override
    public synchronized void record(RuntimeTraceEvent context, Throwable failure) {
        Objects.requireNonNull(context, "context must not be null");
        Objects.requireNonNull(failure, "failure must not be null");
        Object rawDiagnosticId = context.safeAttributes().get("diagnosticId");
        if (!(rawDiagnosticId instanceof String diagnosticId)
                || !SAFE_ID.matcher(diagnosticId).matches()) return;

        Map<String, Object> document = new LinkedHashMap<>();
        document.put("schemaVersion", 1);
        document.put("diagnosticId", diagnosticId);
        document.put("runId", bounded(context.runId().value()));
        context.attemptId().ifPresent(value -> document.put("attemptId", bounded(value.value())));
        document.put("occurredAtEpochMillis", context.occurredAt().toEpochMilli());
        document.put(
                "errorCode", bounded(String.valueOf(context.safeAttributes().getOrDefault("errorCode", "UNKNOWN"))));
        document.put("exceptions", exceptions(failure));

        Path target = root.resolve(diagnosticId + ".json").normalize();
        if (!target.getParent().equals(root)) throw new IllegalStateException("diagnostic target escaped its root");
        try {
            Files.write(
                    target,
                    serialize(document),
                    StandardOpenOption.CREATE_NEW,
                    StandardOpenOption.WRITE,
                    LinkOption.NOFOLLOW_LINKS);
            SecureFilePermissions.secureFile(target);
        } catch (java.nio.file.FileAlreadyExistsException ignored) {
            // A diagnostic ID is immutable; never overwrite its first projection.
        } catch (IOException exception) {
            try {
                Files.deleteIfExists(target);
            } catch (IOException cleanupFailure) {
                exception.addSuppressed(cleanupFailure);
            }
            throw new IllegalStateException("failure diagnostic could not be stored", exception);
        }
    }

    private byte[] serialize(Map<String, Object> document) {
        try {
            return json.writeValueAsBytes(document);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("failure diagnostic could not be serialized", exception);
        }
    }

    private static List<Map<String, Object>> exceptions(Throwable failure) {
        List<Map<String, Object>> result = new ArrayList<>();
        Throwable current = failure;
        while (current != null && result.size() < MAX_CAUSES) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("type", bounded(current.getClass().getName()));
            item.put("frames", frames(current.getStackTrace()));
            result.add(Map.copyOf(item));
            current = current.getCause();
        }
        return List.copyOf(result);
    }

    private static List<Map<String, Object>> frames(StackTraceElement[] source) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (StackTraceElement frame : source) {
            if (result.size() == MAX_FRAMES) break;
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("className", bounded(frame.getClassName()));
            item.put("methodName", bounded(frame.getMethodName()));
            if (frame.getFileName() != null) item.put("fileName", bounded(fileName(frame.getFileName())));
            item.put("lineNumber", frame.getLineNumber());
            result.add(Map.copyOf(item));
        }
        return List.copyOf(result);
    }

    private static Path initialize(Path root) {
        Path normalized = Objects.requireNonNull(root, "diagnostic root must not be null")
                .toAbsolutePath()
                .normalize();
        try {
            Files.createDirectories(normalized);
            if (Files.isSymbolicLink(normalized) || !Files.isDirectory(normalized, LinkOption.NOFOLLOW_LINKS)) {
                throw new IllegalArgumentException("diagnostic root must be a non-symlink directory");
            }
            SecureFilePermissions.secureDirectory(normalized);
            return normalized.toRealPath(LinkOption.NOFOLLOW_LINKS);
        } catch (IOException exception) {
            throw new IllegalArgumentException("diagnostic root cannot be initialized", exception);
        }
    }

    private static String fileName(String value) {
        int separator = Math.max(value.lastIndexOf('/'), value.lastIndexOf('\\'));
        return separator < 0 ? value : value.substring(separator + 1);
    }

    private static String bounded(String value) {
        StringBuilder result = new StringBuilder(Math.min(value.length(), MAX_TEXT));
        value.codePoints().forEach(codePoint -> {
            if (result.length() >= MAX_TEXT) return;
            result.appendCodePoint(Character.isISOControl(codePoint) ? ' ' : codePoint);
        });
        return result.toString();
    }
}
