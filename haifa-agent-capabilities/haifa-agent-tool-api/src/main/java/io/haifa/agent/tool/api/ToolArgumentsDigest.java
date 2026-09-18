package io.haifa.agent.tool.api;

import io.haifa.agent.core.tool.ToolArguments;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.Map;
import java.util.Objects;

/** Canonical digest shared by approval policy and trusted Tool providers. */
public final class ToolArgumentsDigest {
    private ToolArgumentsDigest() {}

    public static String sha256(ToolArguments arguments) {
        Objects.requireNonNull(arguments, "arguments must not be null");
        StringBuilder canonical = new StringBuilder();
        appendCanonical(canonical, arguments.schemaId());
        appendCanonical(canonical, arguments.schemaVersion());
        appendCanonical(canonical, arguments.values());
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(canonical.toString().getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static void appendCanonical(StringBuilder target, Object value) {
        if (value == null) {
            target.append('n');
        } else if (value instanceof String text) {
            target.append('s').append(text.length()).append(':').append(text);
        } else if (value instanceof Boolean bool) {
            target.append(bool ? "b1" : "b0");
        } else if (value instanceof Number number) {
            target.append('d')
                    .append(new java.math.BigDecimal(number.toString())
                            .stripTrailingZeros()
                            .toPlainString())
                    .append(';');
        } else if (value instanceof Map<?, ?> map) {
            target.append("m{");
            map.entrySet().stream()
                    .sorted(Comparator.comparing(entry -> String.valueOf(entry.getKey())))
                    .forEach(entry -> {
                        appendCanonical(target, String.valueOf(entry.getKey()));
                        appendCanonical(target, entry.getValue());
                    });
            target.append('}');
        } else if (value instanceof Iterable<?> iterable) {
            target.append("l[");
            iterable.forEach(element -> appendCanonical(target, element));
            target.append(']');
        } else {
            throw new IllegalArgumentException("tool arguments contain a non-JSON value");
        }
    }
}
