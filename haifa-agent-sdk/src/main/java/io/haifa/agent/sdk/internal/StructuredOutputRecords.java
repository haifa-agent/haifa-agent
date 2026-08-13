package io.haifa.agent.sdk.internal;

import io.haifa.agent.core.run.StructuredOutputRequirement;
import java.util.ArrayList;
import java.util.Map;
import java.util.Objects;

/** Internal bridge from the bounded Java record codec to the frozen Runtime output contract. */
public final class StructuredOutputRecords {
    private StructuredOutputRecords() {}

    public static <T extends Record> StructuredOutputRequirement requirement(Class<T> recordType) {
        Objects.requireNonNull(recordType, "recordType must not be null");
        Map<String, Object> schema = JavaRecordSupport.schema(recordType);
        String version = CanonicalSdkDigest.sha256("java-record-output-v1", canonical(schema));
        return new StructuredOutputRequirement(
                "java-record:" + recordType.getName(), version, responseName(recordType), schema);
    }

    public static <T extends Record> T decode(Class<T> recordType, Map<String, Object> output) {
        return JavaRecordSupport.decode(recordType, output);
    }

    private static String responseName(Class<? extends Record> recordType) {
        StringBuilder value = new StringBuilder();
        recordType.getSimpleName().codePoints().forEach(codePoint -> {
            if ((codePoint >= 'A' && codePoint <= 'Z')
                    || (codePoint >= 'a' && codePoint <= 'z')
                    || (codePoint >= '0' && codePoint <= '9')
                    || codePoint == '_'
                    || codePoint == '-') {
                value.appendCodePoint(codePoint);
            } else {
                value.append('_');
            }
        });
        if (value.isEmpty() || !Character.isLetter(value.charAt(0))) value.insert(0, "Record_");
        if (value.length() > 64) value.setLength(64);
        return value.toString();
    }

    private static String canonical(Object value) {
        if (value instanceof Map<?, ?> map) {
            return map.entrySet().stream()
                    .sorted(java.util.Comparator.comparing(entry -> String.valueOf(entry.getKey())))
                    .map(entry -> String.valueOf(entry.getKey()) + "=" + canonical(entry.getValue()))
                    .collect(java.util.stream.Collectors.joining(",", "{", "}"));
        }
        if (value instanceof Iterable<?> iterable) {
            var items = new ArrayList<String>();
            iterable.forEach(item -> items.add(canonical(item)));
            return String.join(",", items);
        }
        return String.valueOf(value);
    }
}
