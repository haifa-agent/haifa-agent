package io.haifa.agent.project.filesystem;

import io.haifa.agent.project.path.WorkspacePath;
import java.nio.charset.Charset;
import java.util.Objects;

public record FileContent(
        WorkspacePath path,
        String text,
        Charset charset,
        long offset,
        long byteCount,
        long totalByteCount,
        String sourceVersion,
        String contentHash,
        boolean truncated) {
    public FileContent(
            WorkspacePath path, String text, Charset charset, long byteCount, String contentHash, boolean truncated) {
        this(path, text, charset, 0, byteCount, byteCount, contentHash, contentHash, truncated);
    }

    public FileContent {
        path = Objects.requireNonNull(path, "path must not be null");
        text = Objects.requireNonNull(text, "text must not be null");
        charset = Objects.requireNonNull(charset, "charset must not be null");
        if (offset < 0) throw new IllegalArgumentException("offset must not be negative");
        if (byteCount < 0) throw new IllegalArgumentException("byteCount must not be negative");
        if (totalByteCount < 0 || offset + byteCount > totalByteCount) {
            throw new IllegalArgumentException("byte range is outside the source file");
        }
        sourceVersion = Objects.requireNonNull(sourceVersion, "sourceVersion must not be null");
        contentHash = Objects.requireNonNull(contentHash, "contentHash must not be null");
    }

    public long nextOffset() {
        return offset + byteCount;
    }

    public boolean hasMore() {
        return nextOffset() < totalByteCount;
    }
}
