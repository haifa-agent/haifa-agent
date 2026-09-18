package io.haifa.agent.personalassistant.application.mission;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;

final class MissionValues {
    private MissionValues() {}

    static String text(String value, String field, int maximum) {
        String normalized =
                Objects.requireNonNull(value, field + " must not be null").trim();
        if (normalized.isEmpty() || normalized.length() > maximum) {
            throw new MissionException("MISSION_INVALID", field + " must contain 1 to " + maximum + " characters");
        }
        return normalized;
    }

    static List<String> texts(List<String> values, String field, int maximumItems, int maximumText) {
        List<String> copy = List.copyOf(Objects.requireNonNull(values, field + " must not be null"));
        if (copy.isEmpty() || copy.size() > maximumItems) {
            throw new MissionException(
                    "MISSION_LIMIT_EXCEEDED", field + " must contain 1 to " + maximumItems + " items");
        }
        return copy.stream()
                .map(value -> text(value, field + " entry", maximumText))
                .toList();
    }

    static Instant millisecond(Instant value, String field) {
        Objects.requireNonNull(value, field + " must not be null");
        return Instant.ofEpochMilli(value.toEpochMilli());
    }

    static String digest(String... fields) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (String field : fields) {
                byte[] bytes = Objects.requireNonNullElse(field, "").getBytes(StandardCharsets.UTF_8);
                digest.update(java.nio.ByteBuffer.allocate(Integer.BYTES)
                        .putInt(bytes.length)
                        .array());
                digest.update(bytes);
            }
            return "sha256:" + HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is required", exception);
        }
    }
}
