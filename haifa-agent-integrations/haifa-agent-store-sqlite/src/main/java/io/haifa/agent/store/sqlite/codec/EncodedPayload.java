package io.haifa.agent.store.sqlite.codec;

import java.util.Objects;

public record EncodedPayload(String type, String schemaVersion, byte[] bytes, String hash) {
    public EncodedPayload {
        type = requireText(type, "type");
        schemaVersion = requireText(schemaVersion, "schemaVersion");
        bytes = Objects.requireNonNull(bytes, "bytes must not be null").clone();
        hash = requireText(hash, "hash");
        if (!hash.startsWith("sha256:")) {
            throw new IllegalArgumentException("hash must use sha256");
        }
    }

    @Override
    public byte[] bytes() {
        return bytes.clone();
    }

    private static String requireText(String value, String field) {
        String normalized =
                Objects.requireNonNull(value, field + " must not be null").trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return normalized;
    }
}
