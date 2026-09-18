package io.haifa.agent.project.diff;

import java.util.List;
import java.util.Objects;

public record DiffRequest(List<DiffFile> files, int maxFiles, int maxFileBytes, int maxOutputBytes) {
    public DiffRequest {
        files = List.copyOf(Objects.requireNonNull(files, "files must not be null"));
        if (maxFiles < 1 || maxFiles > 1_000) throw new IllegalArgumentException("maxFiles is out of range");
        if (maxFileBytes < 1 || maxFileBytes > 16 * 1024 * 1024) {
            throw new IllegalArgumentException("maxFileBytes is out of range");
        }
        if (maxOutputBytes < 1 || maxOutputBytes > 32 * 1024 * 1024) {
            throw new IllegalArgumentException("maxOutputBytes is out of range");
        }
    }
}
