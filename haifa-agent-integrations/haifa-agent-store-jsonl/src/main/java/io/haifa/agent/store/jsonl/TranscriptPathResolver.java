package io.haifa.agent.store.jsonl;

import io.haifa.agent.common.io.SecureFilePermissions;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

final class TranscriptPathResolver {
    private static final Pattern SAFE_RUN_ID = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,127}");

    private final Path root;

    TranscriptPathResolver(Path root) {
        Objects.requireNonNull(root, "root must not be null");
        try {
            Files.createDirectories(root);
            this.root = root.toRealPath(LinkOption.NOFOLLOW_LINKS);
            SecureFilePermissions.secureDirectory(this.root);
        } catch (IOException exception) {
            throw new TranscriptProjectionException(
                    TranscriptDiagnosticCode.PERMISSION_DENIED, "cannot initialize secure transcript root", exception);
        }
        if (!Files.isDirectory(this.root, LinkOption.NOFOLLOW_LINKS)) {
            throw new TranscriptProjectionException(
                    TranscriptDiagnosticCode.WRITE_FAILED, "transcript root is not a directory");
        }
    }

    Path resolve(String runId) {
        String value = validateRunId(runId);
        return controlled(value + ".jsonl");
    }

    Path lock(String runId) {
        return controlled(validateRunId(runId) + ".lock");
    }

    Path rotated(String runId, int index) {
        if (index < 1) throw new IllegalArgumentException("rotation index must be positive");
        return controlled("%s.%06d.jsonl".formatted(validateRunId(runId), index));
    }

    int nextRotationIndex(String runId) {
        return rotatedTranscripts(runId).stream()
                        .mapToInt(path -> rotationIndex(runId, path))
                        .max()
                        .orElse(0)
                + 1;
    }

    List<Path> transcriptFiles(String runId) {
        List<Path> result = new ArrayList<>(rotatedTranscripts(runId));
        Path current = resolve(runId);
        if (Files.exists(current, LinkOption.NOFOLLOW_LINKS)) result.add(current);
        return List.copyOf(result);
    }

    private List<Path> rotatedTranscripts(String runId) {
        String value = validateRunId(runId);
        List<Path> result = new ArrayList<>();
        try (var entries = Files.newDirectoryStream(root, value + ".*.jsonl")) {
            for (Path path : entries) {
                if (Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)
                        && rotatedName(value)
                                .matcher(path.getFileName().toString())
                                .matches()) {
                    result.add(path);
                }
            }
        } catch (IOException exception) {
            throw new TranscriptProjectionException(
                    TranscriptDiagnosticCode.WRITE_FAILED, "cannot inspect transcript segments", exception);
        }
        result.sort(Comparator.comparingInt(path -> rotationIndex(value, path)));
        return result;
    }

    private static Pattern rotatedName(String runId) {
        return Pattern.compile(Pattern.quote(runId) + "\\.\\d{6}\\.jsonl");
    }

    private static int rotationIndex(String runId, Path path) {
        String name = path.getFileName().toString();
        int start = runId.length() + 1;
        return Integer.parseInt(name.substring(start, start + 6));
    }

    private static String validateRunId(String runId) {
        String value = Objects.requireNonNull(runId, "runId must not be null").trim();
        if (!SAFE_RUN_ID.matcher(value).matches() || value.contains("..")) {
            throw new TranscriptProjectionException(
                    TranscriptDiagnosticCode.INVALID_RUN_ID, "run ID cannot be used as a transcript path");
        }
        return value;
    }

    private Path controlled(String fileName) {
        Path candidate = root.resolve(fileName).normalize();
        if (!candidate.getParent().equals(root) || !candidate.startsWith(root)) {
            throw new TranscriptProjectionException(
                    TranscriptDiagnosticCode.PATH_ESCAPE, "transcript path escapes the controlled root");
        }
        if (Files.exists(candidate, LinkOption.NOFOLLOW_LINKS) && Files.isSymbolicLink(candidate)) {
            throw new TranscriptProjectionException(
                    TranscriptDiagnosticCode.PATH_ESCAPE, "transcript path must not be a symbolic link");
        }
        return candidate;
    }
}
