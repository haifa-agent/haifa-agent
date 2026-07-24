package io.haifa.agent.skill.api;

import java.util.Objects;

public record SkillResourceRef(
        String relativePath,
        SkillResourceKind kind,
        String mediaType,
        SkillContentDigest digest,
        long byteSize,
        boolean readableText) {
    public SkillResourceRef {
        relativePath = SkillValues.text(relativePath, "relativePath", 512).replace('\\', '/');
        if (relativePath.startsWith("/")
                || relativePath.contains("../")
                || relativePath.contains(":")
                || relativePath.equals("..")) {
            throw new IllegalArgumentException("resource path must be package-relative");
        }
        kind = Objects.requireNonNull(kind, "kind must not be null");
        mediaType = SkillValues.text(mediaType, "mediaType", 128);
        digest = Objects.requireNonNull(digest, "digest must not be null");
        if (byteSize < 0) throw new IllegalArgumentException("byteSize must not be negative");
    }
}
