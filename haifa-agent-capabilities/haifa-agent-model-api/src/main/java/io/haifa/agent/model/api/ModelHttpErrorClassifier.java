package io.haifa.agent.model.api;

import java.net.http.HttpHeaders;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Unified HTTP non-success error classification and response body extraction.
 *
 * <p>Provides standardized classification across model providers without exposing
 * credentials, full prompts, or unvetted vendor messages.
 */
public final class ModelHttpErrorClassifier {
    public static final String PAYMENT_REQUIRED_SAFE_PROMPT = "请检查 Provider 账户余额、套餐、模型授权或账单状态后重试";

    private static final int MAX_BODY_BYTES = 64 * 1024;
    private static final int MAX_CODE_CHARS = 80;
    private static final int MAX_JSON_DEPTH = 20;

    private static final List<String> REQUEST_ID_HEADERS =
            List.of("x-request-id", "request-id", "x-amzn-requestid", "x-goog-request-id");

    private ModelHttpErrorClassifier() {}

    /**
     * Parsed fields extracted from a provider error payload.
     */
    public record ParsedErrorBody(
            String code, String type, String status, String message, String requestId, List<String> reasons) {

        public ParsedErrorBody {
            reasons = reasons != null ? List.copyOf(reasons) : List.of();
        }

        public static ParsedErrorBody empty() {
            return new ParsedErrorBody(null, null, null, null, null, List.of());
        }

        public boolean isEmpty() {
            return (code == null || code.isBlank())
                    && (type == null || type.isBlank())
                    && (status == null || status.isBlank())
                    && (message == null || message.isBlank())
                    && (requestId == null || requestId.isBlank())
                    && reasons.isEmpty();
        }
    }

    /**
     * Optional dialect customizer hook to supplement provider-specific error semantics.
     */
    @FunctionalInterface
    public interface DialectCustomizer {
        ModelErrorMapping customize(
                int statusCode, HttpHeaders headers, ParsedErrorBody parsedBody, ModelErrorMapping defaultMapping);
    }

    /**
     * Classifies a non-2xx HTTP response using default policies.
     */
    public static ModelErrorMapping classify(
            int statusCode, HttpHeaders headers, byte[] body, String credentialToRedact) {
        return classify(statusCode, headers, body, credentialToRedact, (Instant) null, null);
    }

    /**
     * Classifies a non-2xx HTTP response using default policies and an explicit reference instant.
     */
    public static ModelErrorMapping classify(
            int statusCode, HttpHeaders headers, byte[] body, String credentialToRedact, Instant referenceInstant) {
        return classify(statusCode, headers, body, credentialToRedact, referenceInstant, null);
    }

    /**
     * Classifies a non-2xx HTTP response using default policies and optional dialect customization.
     */
    public static ModelErrorMapping classify(
            int statusCode, HttpHeaders headers, byte[] body, String credentialToRedact, DialectCustomizer customizer) {
        return classify(statusCode, headers, body, credentialToRedact, (Instant) null, customizer);
    }

    /**
     * Classifies a non-2xx HTTP response using default policies, reference instant, and optional dialect customization.
     */
    public static ModelErrorMapping classify(
            int statusCode,
            HttpHeaders headers,
            byte[] body,
            String credentialToRedact,
            Instant referenceInstant,
            DialectCustomizer customizer) {
        ParsedErrorBody parsed = parseErrorBody(body);
        ModelErrorMapping defaultMapping =
                computeDefaultMapping(statusCode, headers, parsed, credentialToRedact, referenceInstant);

        if (statusCode == 402) {
            // Strict 402 contract: never retryable, ignore Retry-After, fixed safe message.
            return enforcePaymentRequired(defaultMapping);
        }

        if (customizer != null) {
            ModelErrorMapping customized = customizer.customize(statusCode, headers, parsed, defaultMapping);
            if (customized != null) {
                return customized;
            }
        }
        return defaultMapping;
    }

    private static ModelErrorMapping enforcePaymentRequired(ModelErrorMapping mapping) {
        return new ModelErrorMapping(
                ModelErrorCategory.PAYMENT_REQUIRED,
                false,
                mapping.httpStatus(),
                mapping.providerCode(),
                PAYMENT_REQUIRED_SAFE_PROMPT,
                Optional.empty(),
                mapping.providerRequestId());
    }

    private static ModelErrorMapping computeDefaultMapping(
            int statusCode,
            HttpHeaders headers,
            ParsedErrorBody parsed,
            String credentialToRedact,
            Instant referenceInstant) {
        ModelErrorCategory category = defaultCategory(statusCode);
        boolean retryable = defaultRetryable(statusCode);

        String providerCode = resolveProviderCode(statusCode, parsed, credentialToRedact);
        String requestId = resolveRequestId(headers, parsed);

        Optional<Duration> retryAfter = Optional.empty();
        if (statusCode != 402 && retryable) {
            Instant now = referenceInstant != null ? referenceInstant : Instant.now();
            retryAfter = RetryAfterParser.parse(headers, now);
        }

        String safeMessage = statusCode == 402
                ? PAYMENT_REQUIRED_SAFE_PROMPT
                : "model provider request failed with HTTP " + statusCode;

        return new ModelErrorMapping(
                category, retryable, statusCode, providerCode, safeMessage, retryAfter, Optional.ofNullable(requestId));
    }

    public static ModelErrorCategory defaultCategory(int statusCode) {
        return switch (statusCode) {
            case 400, 413, 422 -> ModelErrorCategory.INVALID_REQUEST;
            case 401 -> ModelErrorCategory.AUTHENTICATION_FAILED;
            case 402 -> ModelErrorCategory.PAYMENT_REQUIRED;
            case 403 -> ModelErrorCategory.PERMISSION_DENIED;
            case 404 -> ModelErrorCategory.MODEL_NOT_FOUND;
            case 408, 504 -> ModelErrorCategory.TIMEOUT;
            case 429 -> ModelErrorCategory.RATE_LIMITED;
            default -> {
                if (statusCode >= 500 && statusCode <= 599) {
                    yield ModelErrorCategory.SERVER_ERROR;
                }
                yield ModelErrorCategory.UNKNOWN_PROVIDER_ERROR;
            }
        };
    }

    public static boolean defaultRetryable(int statusCode) {
        if (statusCode == 402) {
            return false;
        }
        if (statusCode == 408 || statusCode == 429) {
            return true;
        }
        if (statusCode >= 400 && statusCode < 500) {
            return false;
        }
        return statusCode >= 500 && statusCode <= 599;
    }

    private static String resolveProviderCode(int statusCode, ParsedErrorBody parsed, String credentialToRedact) {
        String rawCode = null;
        if (parsed.code() != null && !parsed.code().isBlank()) {
            rawCode = parsed.code();
        } else if (parsed.type() != null && !parsed.type().isBlank()) {
            rawCode = parsed.type();
        } else if (parsed.status() != null && !parsed.status().isBlank()) {
            rawCode = parsed.status();
        }

        if (rawCode == null || rawCode.isBlank()) {
            return "http_" + statusCode;
        }

        return sanitizeCode(rawCode, credentialToRedact, "http_" + statusCode);
    }

    public static String sanitizeCode(String code, String credentialToRedact, String fallback) {
        if (code == null || code.isBlank()) return fallback;
        String sanitized = code.trim().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_.-]", "_");
        if (credentialToRedact != null && !credentialToRedact.isBlank()) {
            sanitized = sanitized.replace(credentialToRedact.trim().toLowerCase(Locale.ROOT), "[redacted]");
        }
        if (sanitized.isEmpty()) return fallback;
        return sanitized.length() <= MAX_CODE_CHARS ? sanitized : sanitized.substring(0, MAX_CODE_CHARS);
    }

    private static String resolveRequestId(HttpHeaders headers, ParsedErrorBody parsed) {
        if (headers != null) {
            for (String headerName : REQUEST_ID_HEADERS) {
                Optional<String> val = headers.firstValue(headerName);
                if (val.isPresent() && !val.get().isBlank()) {
                    return sanitizeRequestId(val.get());
                }
            }
        }
        if (parsed.requestId() != null && !parsed.requestId().isBlank()) {
            return sanitizeRequestId(parsed.requestId());
        }
        return null;
    }

    private static String sanitizeRequestId(String raw) {
        if (raw == null) return null;
        String clean = raw.trim();
        if (clean.isEmpty()) return null;
        return clean.length() <= 128 ? clean : clean.substring(0, 128);
    }

    /**
     * Safely and boundedly parses a response body into structured error fields without third-party libraries.
     */
    public static ParsedErrorBody parseErrorBody(byte[] body) {
        if (body == null || body.length == 0) {
            return ParsedErrorBody.empty();
        }
        int length = Math.min(body.length, MAX_BODY_BYTES);
        String text = new String(body, 0, length, StandardCharsets.UTF_8).trim();
        if (text.isEmpty() || text.startsWith("<")) {
            // Empty or HTML document (e.g. <!DOCTYPE html> or <html>)
            return ParsedErrorBody.empty();
        }

        Object root = parseJson(text);
        if (!(root instanceof Map<?, ?> rootMap)) {
            return ParsedErrorBody.empty();
        }

        @SuppressWarnings("unchecked")
        Map<String, Object> map = (Map<String, Object>) rootMap;

        Object errorObj = map.get("error");
        Map<String, Object> errorMap = (errorObj instanceof Map<?, ?> eMap) ? castMap(eMap) : null;

        String code = null;
        String type = null;
        String status = null;
        String message = null;
        String requestId = null;
        List<String> reasons = new ArrayList<>();

        if (errorMap != null) {
            code = stringOrNull(errorMap.get("code"));
            type = stringOrNull(errorMap.get("type"));
            status = stringOrNull(errorMap.get("status"));
            message = stringOrNull(errorMap.get("message"));
            requestId = stringOrNull(errorMap.get("request_id"));
            collectReasons(errorMap.get("details"), reasons);
        } else if (errorObj instanceof String errStr && !errStr.isBlank()) {
            code = errStr;
        }

        if (code == null) code = stringOrNull(map.get("code"));
        if (type == null) type = stringOrNull(map.get("type"));
        if (status == null) status = stringOrNull(map.get("status"));
        if (message == null) message = stringOrNull(map.get("message"));
        if (requestId == null) {
            requestId = stringOrNull(map.get("request_id"));
            if (requestId == null) requestId = stringOrNull(map.get("requestId"));
            if (requestId == null) requestId = stringOrNull(map.get("id"));
        }
        if (reasons.isEmpty()) {
            collectReasons(map.get("details"), reasons);
        }

        return new ParsedErrorBody(code, type, status, message, requestId, reasons);
    }

    private static void collectReasons(Object detailsObj, List<String> reasons) {
        if (detailsObj instanceof List<?> list) {
            for (Object item : list) {
                if (item instanceof Map<?, ?> itemMap) {
                    Object reason = itemMap.get("reason");
                    if (reason instanceof String r && !r.isBlank()) {
                        reasons.add(r.trim());
                    }
                }
            }
        }
    }

    private static String stringOrNull(Object obj) {
        if (obj == null) return null;
        if (obj instanceof String s) return s.trim();
        if (obj instanceof Number || obj instanceof Boolean) return String.valueOf(obj);
        return null;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> castMap(Map<?, ?> raw) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : raw.entrySet()) {
            if (entry.getKey() != null) {
                result.put(entry.getKey().toString(), entry.getValue());
            }
        }
        return result;
    }

    // --- Minimal, bounded pure-Java JSON parser ---

    static Object parseJson(String text) {
        if (text == null) return null;
        String trimmed = text.trim();
        if (trimmed.isEmpty() || trimmed.startsWith("<")) return null;
        try {
            int[] pos = new int[] {0};
            skipWhitespace(trimmed, pos);
            if (pos[0] >= trimmed.length()) return null;
            return parseValue(trimmed, pos, 0);
        } catch (Exception ignored) {
            return null;
        }
    }

    private static Object parseValue(String s, int[] pos, int depth) {
        if (depth > MAX_JSON_DEPTH) return null;
        skipWhitespace(s, pos);
        if (pos[0] >= s.length()) return null;
        char c = s.charAt(pos[0]);
        if (c == '{') {
            return parseObject(s, pos, depth + 1);
        } else if (c == '[') {
            return parseArray(s, pos, depth + 1);
        } else if (c == '"') {
            return parseString(s, pos);
        } else if (c == 't' || c == 'f') {
            return parseBoolean(s, pos);
        } else if (c == 'n') {
            return parseNull(s, pos);
        } else if (c == '-' || (c >= '0' && c <= '9')) {
            return parseNumber(s, pos);
        }
        return null;
    }

    private static Map<String, Object> parseObject(String s, int[] pos, int depth) {
        Map<String, Object> map = new LinkedHashMap<>();
        pos[0]++; // skip '{'
        skipWhitespace(s, pos);
        if (pos[0] < s.length() && s.charAt(pos[0]) == '}') {
            pos[0]++;
            return map;
        }
        while (pos[0] < s.length()) {
            skipWhitespace(s, pos);
            if (pos[0] >= s.length() || s.charAt(pos[0]) != '"') break;
            String key = parseString(s, pos);
            skipWhitespace(s, pos);
            if (pos[0] >= s.length() || s.charAt(pos[0]) != ':') break;
            pos[0]++; // skip ':'
            Object value = parseValue(s, pos, depth);
            if (key != null) {
                map.put(key, value);
            }
            skipWhitespace(s, pos);
            if (pos[0] >= s.length()) break;
            if (s.charAt(pos[0]) == '}') {
                pos[0]++;
                return map;
            }
            if (s.charAt(pos[0]) == ',') {
                pos[0]++;
            } else {
                break;
            }
        }
        return map;
    }

    private static List<Object> parseArray(String s, int[] pos, int depth) {
        List<Object> list = new ArrayList<>();
        pos[0]++; // skip '['
        skipWhitespace(s, pos);
        if (pos[0] < s.length() && s.charAt(pos[0]) == ']') {
            pos[0]++;
            return list;
        }
        while (pos[0] < s.length()) {
            Object value = parseValue(s, pos, depth);
            list.add(value);
            skipWhitespace(s, pos);
            if (pos[0] >= s.length()) break;
            if (s.charAt(pos[0]) == ']') {
                pos[0]++;
                return list;
            }
            if (s.charAt(pos[0]) == ',') {
                pos[0]++;
            } else {
                break;
            }
        }
        return list;
    }

    private static String parseString(String s, int[] pos) {
        if (pos[0] >= s.length() || s.charAt(pos[0]) != '"') return null;
        pos[0]++; // skip opening '"'
        StringBuilder sb = new StringBuilder();
        while (pos[0] < s.length()) {
            char c = s.charAt(pos[0]++);
            if (c == '"') {
                return sb.toString();
            }
            if (c == '\\') {
                if (pos[0] >= s.length()) break;
                char escape = s.charAt(pos[0]++);
                switch (escape) {
                    case '"' -> sb.append('"');
                    case '\\' -> sb.append('\\');
                    case '/' -> sb.append('/');
                    case 'b' -> sb.append('\b');
                    case 'f' -> sb.append('\f');
                    case 'n' -> sb.append('\n');
                    case 'r' -> sb.append('\r');
                    case 't' -> sb.append('\t');
                    case 'u' -> {
                        if (pos[0] + 4 <= s.length()) {
                            String hex = s.substring(pos[0], pos[0] + 4);
                            try {
                                sb.append((char) Integer.parseInt(hex, 16));
                                pos[0] += 4;
                            } catch (NumberFormatException ignored) {
                                sb.append("\\u").append(hex);
                                pos[0] += 4;
                            }
                        }
                    }
                    default -> sb.append(escape);
                }
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    private static Boolean parseBoolean(String s, int[] pos) {
        if (s.startsWith("true", pos[0])) {
            pos[0] += 4;
            return Boolean.TRUE;
        }
        if (s.startsWith("false", pos[0])) {
            pos[0] += 5;
            return Boolean.FALSE;
        }
        return null;
    }

    private static Object parseNull(String s, int[] pos) {
        if (s.startsWith("null", pos[0])) {
            pos[0] += 4;
        }
        return null;
    }

    private static Number parseNumber(String s, int[] pos) {
        int start = pos[0];
        if (pos[0] < s.length() && s.charAt(pos[0]) == '-') {
            pos[0]++;
        }
        boolean isFloat = false;
        while (pos[0] < s.length()) {
            char c = s.charAt(pos[0]);
            if (c >= '0' && c <= '9') {
                pos[0]++;
            } else if (c == '.' || c == 'e' || c == 'E' || c == '+' || c == '-') {
                isFloat = true;
                pos[0]++;
            } else {
                break;
            }
        }
        String numStr = s.substring(start, pos[0]);
        try {
            if (isFloat) {
                return Double.parseDouble(numStr);
            } else {
                return Long.parseLong(numStr);
            }
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private static void skipWhitespace(String s, int[] pos) {
        while (pos[0] < s.length()) {
            char c = s.charAt(pos[0]);
            if (c == ' ' || c == '\t' || c == '\r' || c == '\n') {
                pos[0]++;
            } else {
                break;
            }
        }
    }
}
