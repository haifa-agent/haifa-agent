package io.haifa.agent.sandbox.api;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collection;
import java.util.Objects;

public record SandboxConfigurationDigest(String value) {
    private static final String PREFIX = "sha256:";

    public SandboxConfigurationDigest {
        value = Objects.requireNonNull(value, "value must not be null").trim().toLowerCase(java.util.Locale.ROOT);
        if (!value.matches("^sha256:[0-9a-f]{64}$")) {
            throw new IllegalArgumentException("value must be a sha256 digest");
        }
    }

    public static SandboxConfigurationDigest sha256Fields(Collection<String> fields) {
        Objects.requireNonNull(fields, "fields must not be null");
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (String field : fields) {
                byte[] bytes = Objects.requireNonNull(field, "digest field must not be null")
                        .getBytes(StandardCharsets.UTF_8);
                digest.update(
                        ByteBuffer.allocate(Integer.BYTES).putInt(bytes.length).array());
                digest.update(bytes);
            }
            return new SandboxConfigurationDigest(
                    PREFIX + java.util.HexFormat.of().formatHex(digest.digest()));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }
}
