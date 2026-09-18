package io.haifa.agent.policy.api;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;

/** Stable digest helper for a bounded list of already-safe binding fields. */
public final class PolicyDigest {
    private PolicyDigest() {}

    public static String sha256Fields(List<String> fields) {
        Objects.requireNonNull(fields, "fields must not be null");
        if (fields.size() > 64) throw new IllegalArgumentException("too many digest fields");
        StringBuilder canonical = new StringBuilder();
        for (String field : fields) {
            String value = Objects.requireNonNull(field, "digest field must not be null");
            if (value.length() > 16_384) throw new IllegalArgumentException("digest field is too large");
            canonical.append(value.length()).append(':').append(value).append(';');
        }
        try {
            return HexFormat.of()
                    .formatHex(MessageDigest.getInstance("SHA-256")
                            .digest(canonical.toString().getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }
}
