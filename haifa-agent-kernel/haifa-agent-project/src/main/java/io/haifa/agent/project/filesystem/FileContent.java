package io.haifa.agent.project.filesystem;

import io.haifa.agent.project.path.WorkspacePath;
import java.nio.charset.Charset;
import java.util.Objects;

public record FileContent(
        WorkspacePath path, String text, Charset charset, long byteCount, String contentHash, boolean truncated) {
    public FileContent {
        path = Objects.requireNonNull(path, "path must not be null");
        text = Objects.requireNonNull(text, "text must not be null");
        charset = Objects.requireNonNull(charset, "charset must not be null");
        if (byteCount < 0) throw new IllegalArgumentException("byteCount must not be negative");
        contentHash = Objects.requireNonNull(contentHash, "contentHash must not be null");
    }
}
