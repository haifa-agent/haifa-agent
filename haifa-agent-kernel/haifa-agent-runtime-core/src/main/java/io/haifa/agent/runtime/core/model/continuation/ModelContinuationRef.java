package io.haifa.agent.runtime.core.model.continuation;

import java.util.Objects;

/** Safe reference suitable for message metadata and checkpoints; it never contains reasoning text. */
public record ModelContinuationRef(String id, String version, String digest, int byteLength) {
    public ModelContinuationRef {
        id = requireText(id, "id");
        version = requireText(version, "version");
        digest = requireText(digest, "digest");
        if (!digest.startsWith("sha256:") || byteLength < 1) {
            throw new IllegalArgumentException("continuation digest or byte length is invalid");
        }
    }

    private static String requireText(String value, String field) {
        String result =
                Objects.requireNonNull(value, field + " must not be null").trim();
        if (result.isEmpty()) throw new IllegalArgumentException(field + " must not be blank");
        return result;
    }
}
