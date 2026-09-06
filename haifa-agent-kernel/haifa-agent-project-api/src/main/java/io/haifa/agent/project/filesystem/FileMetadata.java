package io.haifa.agent.project.filesystem;

import io.haifa.agent.project.path.WorkspacePath;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

public record FileMetadata(
        WorkspacePath path,
        FileType type,
        long size,
        Optional<Instant> lastModifiedAt,
        Optional<String> contentHash,
        boolean link) {
    public FileMetadata {
        path = Objects.requireNonNull(path, "path must not be null");
        type = Objects.requireNonNull(type, "type must not be null");
        if (size < 0) throw new IllegalArgumentException("size must not be negative");
        lastModifiedAt = Objects.requireNonNull(lastModifiedAt, "lastModifiedAt must not be null");
        contentHash = Objects.requireNonNull(contentHash, "contentHash must not be null");
    }
}
