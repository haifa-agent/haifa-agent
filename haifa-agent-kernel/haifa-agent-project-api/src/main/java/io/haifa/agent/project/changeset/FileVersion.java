package io.haifa.agent.project.changeset;

import io.haifa.agent.project.filesystem.FileType;
import java.util.Objects;

public record FileVersion(FileType type, long size, String contentHash) {
    public FileVersion {
        type = Objects.requireNonNull(type, "type must not be null");
        if (size < 0) throw new IllegalArgumentException("size must not be negative");
        contentHash = Objects.requireNonNull(contentHash, "contentHash must not be null")
                .trim();
        if (contentHash.isEmpty()) throw new IllegalArgumentException("contentHash must not be blank");
    }
}
