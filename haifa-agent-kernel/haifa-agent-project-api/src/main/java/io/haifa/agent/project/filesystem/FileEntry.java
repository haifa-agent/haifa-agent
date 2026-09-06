package io.haifa.agent.project.filesystem;

import java.util.Objects;

public record FileEntry(FileMetadata metadata) implements Comparable<FileEntry> {
    public FileEntry {
        metadata = Objects.requireNonNull(metadata, "metadata must not be null");
    }

    @Override
    public int compareTo(FileEntry other) {
        return metadata.path().compareTo(other.metadata.path());
    }
}
