package io.haifa.agent.memory.api;

import java.util.Objects;

public record MemoryEvidenceRef(MemorySourceRef source, String contentDigest) {
    public MemoryEvidenceRef {
        source = Objects.requireNonNull(source, "source must not be null");
        contentDigest = MemoryValues.text(contentDigest, "contentDigest", 128);
        if (!contentDigest.startsWith("sha256:")) {
            throw new IllegalArgumentException("contentDigest must be a sha256 digest");
        }
    }
}
