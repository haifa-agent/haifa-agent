package io.haifa.agent.project.index.file;

import io.haifa.agent.project.index.IndexGeneration;
import io.haifa.agent.project.index.IndexStatus;
import java.util.List;
import java.util.Objects;

public record FileIndexResult(
        IndexGeneration generation, IndexStatus status, List<FileIndexEntry> entries, boolean truncated) {
    public FileIndexResult {
        generation = Objects.requireNonNull(generation, "generation must not be null");
        status = Objects.requireNonNull(status, "status must not be null");
        entries = List.copyOf(Objects.requireNonNull(entries, "entries must not be null"));
    }
}
