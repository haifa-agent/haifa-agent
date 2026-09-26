package io.haifa.agent.memory.core;

import io.haifa.agent.memory.api.MemoryOperationException;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Thin deterministic hard filter: credentials, payment data and precise identity numbers never enter long-term
 * Memory. It is a floor, not a classifier; products decide what is worth remembering.
 */
public final class SensitiveMemoryFilter {
    public static final String SENSITIVE_CODE = "MEMORY_CONTENT_SENSITIVE";
    private static final Pattern PAYMENT_CARD = Pattern.compile("(?:\\d[ -]*?){13,19}");
    private static final Pattern PRECISE_ID = Pattern.compile("\\b\\d{6}[- ]?\\d{8}[- ]?[0-9xX]{4}\\b");
    private static final List<String> MARKERS = List.of(
            "password",
            "passwd",
            "api_key",
            "api key",
            "apikey",
            "secret",
            "credential",
            "access token",
            "access_token",
            "private key",
            "bearer ",
            "cvv",
            "passport",
            "social security",
            "密码",
            "口令",
            "密钥",
            "身份证");

    private SensitiveMemoryFilter() {}

    public static boolean isSensitive(String text) {
        String value = text.toLowerCase(Locale.ROOT);
        return MARKERS.stream().anyMatch(value::contains)
                || PAYMENT_CARD.matcher(value).find()
                || PRECISE_ID.matcher(value).find();
    }

    public static void requireSafe(String... values) {
        for (String value : values) {
            if (isSensitive(value)) throw new MemoryOperationException(SENSITIVE_CODE);
        }
    }
}
