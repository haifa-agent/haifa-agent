package io.haifa.agent.project.filesystem;

import java.util.List;
import java.util.Objects;

public record FileListPage(List<FileEntry> entries, int nextOffset, boolean truncated) {
    public FileListPage {
        entries = List.copyOf(Objects.requireNonNull(entries, "entries must not be null"));
        if (nextOffset < 0) throw new IllegalArgumentException("nextOffset must not be negative");
    }
}
