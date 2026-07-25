package io.haifa.agent.store.jsonl;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/** Defense-in-depth validation applied after event-specific safe mapping. */
public final class TranscriptRedactor {
    private static final Set<String> FORBIDDEN_KEYS = Set.of(
            "credential",
            "credentials",
            "token",
            "apikey",
            "api_key",
            "authorization",
            "reasoning",
            "reasoningcontent",
            "prompt",
            "arguments",
            "toolarguments",
            "toolresult",
            "providerresponse",
            "rawresponse");
    private static final Pattern CREDENTIAL_SHAPE =
            Pattern.compile("(?i)(bearer\\s+[a-z0-9._~+/-]{8,}|sk-[a-z0-9_-]{8,}|api[_-]?key\\s*[:=])");
    private static final int MAX_STRING_LENGTH = 1024;

    public SafeTranscriptEvent redact(SafeTranscriptEvent event) {
        Objects.requireNonNull(event, "event must not be null");
        Map<String, Object> safe = new LinkedHashMap<>();
        event.payload().forEach((key, value) -> safe.put(validateKey(key), validateValue(value, key)));
        return new SafeTranscriptEvent(
                event.schemaVersion(),
                event.eventId(),
                event.runId(),
                event.sequence(),
                event.occurredAt(),
                event.eventType(),
                safe);
    }

    private static String validateKey(String key) {
        String normalized = Objects.requireNonNull(key, "payload key must not be null")
                .replace("-", "")
                .replace("_", "")
                .toLowerCase(Locale.ROOT);
        if (FORBIDDEN_KEYS.contains(normalized)) {
            throw new TranscriptProjectionException(
                    TranscriptDiagnosticCode.UNSAFE_PAYLOAD, "forbidden transcript payload field: " + key);
        }
        return key;
    }

    private static Object validateValue(Object value, String field) {
        if (value instanceof String text) {
            if (text.length() > MAX_STRING_LENGTH
                    || CREDENTIAL_SHAPE.matcher(text).find()) {
                throw new TranscriptProjectionException(
                        TranscriptDiagnosticCode.UNSAFE_PAYLOAD, "unsafe transcript value for field: " + field);
            }
            return text;
        }
        if (value instanceof Number || value instanceof Boolean) return value;
        throw new TranscriptProjectionException(
                TranscriptDiagnosticCode.UNSAFE_PAYLOAD, "unsupported transcript value for field: " + field);
    }
}
