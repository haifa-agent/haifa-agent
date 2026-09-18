package io.haifa.agent.mcp.protocol;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Map;

public final class McpCanonicalizer {
    private McpCanonicalizer() {}

    public static String digest(Object value) {
        try {
            return java.util.HexFormat.of()
                    .formatHex(MessageDigest.getInstance("SHA-256")
                            .digest(canonicalize(value).getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    public static String canonicalize(Object value) {
        StringBuilder output = new StringBuilder();
        append(value, output);
        return output.toString();
    }

    private static void append(Object value, StringBuilder output) {
        if (value == null) {
            output.append("null");
        } else if (value instanceof String text) {
            output.append('"');
            text.codePoints().forEach(codePoint -> appendEscaped(codePoint, output));
            output.append('"');
        } else if (value instanceof Boolean bool) {
            output.append(bool);
        } else if (value instanceof Number number) {
            output.append(new BigDecimal(number.toString()).stripTrailingZeros().toPlainString());
        } else if (value instanceof Map<?, ?> map) {
            output.append('{');
            var entries = new ArrayList<>(map.entrySet());
            entries.sort(Comparator.comparing(entry -> String.valueOf(entry.getKey())));
            for (int index = 0; index < entries.size(); index++) {
                if (index > 0) output.append(',');
                append(String.valueOf(entries.get(index).getKey()), output);
                output.append(':');
                append(entries.get(index).getValue(), output);
            }
            output.append('}');
        } else if (value instanceof Iterable<?> iterable) {
            output.append('[');
            int index = 0;
            for (Object element : iterable) {
                if (index++ > 0) output.append(',');
                append(element, output);
            }
            output.append(']');
        } else if (value instanceof Enum<?> enumeration) {
            append(enumeration.name(), output);
        } else {
            throw new IllegalArgumentException(
                    "unsupported canonical value " + value.getClass().getName());
        }
    }

    private static void appendEscaped(int codePoint, StringBuilder output) {
        if (codePoint == '"') output.append("\\\"");
        else if (codePoint == '\\') output.append("\\\\");
        else if (codePoint < 0x20) output.append(String.format("\\u%04x", codePoint));
        else output.appendCodePoint(codePoint);
    }
}
