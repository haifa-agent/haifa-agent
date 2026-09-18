package io.haifa.agent.model.api;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Objects;
import java.util.function.Function;

/** Invocation-bound reasoning payload. Its string representation and metadata never reveal the protected text. */
public final class SensitiveModelReasoning {
    public static final int MAX_UTF8_BYTES = 1_048_576;

    private final byte[] utf8;
    private final String digest;

    private SensitiveModelReasoning(byte[] utf8) {
        if (utf8.length == 0 || utf8.length > MAX_UTF8_BYTES) {
            throw new IllegalArgumentException("reasoning payload byte length is invalid");
        }
        this.utf8 = utf8.clone();
        this.digest = sha256(this.utf8);
    }

    public static SensitiveModelReasoning of(String value) {
        String text = Objects.requireNonNull(value, "reasoning must not be null");
        if (text.isEmpty()) throw new IllegalArgumentException("reasoning must not be empty");
        return new SensitiveModelReasoning(text.getBytes(StandardCharsets.UTF_8));
    }

    public static SensitiveModelReasoning fromUtf8(byte[] value) {
        return new SensitiveModelReasoning(Objects.requireNonNull(value, "value must not be null"));
    }

    public int byteLength() {
        return utf8.length;
    }

    public String digest() {
        return digest;
    }

    public <T> T use(Function<String, T> operation) {
        return Objects.requireNonNull(operation, "operation must not be null")
                .apply(new String(utf8, StandardCharsets.UTF_8));
    }

    public byte[] copyUtf8() {
        return utf8.clone();
    }

    @Override
    public String toString() {
        return "SensitiveModelReasoning[bytes=" + utf8.length + ", digest=" + digest.substring(0, 15) + "…]";
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof SensitiveModelReasoning reasoning && Arrays.equals(utf8, reasoning.utf8);
    }

    @Override
    public int hashCode() {
        return Arrays.hashCode(utf8);
    }

    private static String sha256(byte[] value) {
        try {
            return "sha256:"
                    + HexFormat.of()
                            .formatHex(MessageDigest.getInstance("SHA-256").digest(value));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is required", exception);
        }
    }
}
