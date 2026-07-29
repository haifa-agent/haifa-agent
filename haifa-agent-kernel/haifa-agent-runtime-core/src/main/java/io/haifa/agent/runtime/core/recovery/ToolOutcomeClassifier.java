package io.haifa.agent.runtime.core.recovery;

import io.haifa.agent.core.tool.ToolCall;
import io.haifa.agent.core.tool.ToolCallStatus;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/** Classifies only terminal structured facts; it never parses arbitrary command text or stderr. */
public final class ToolOutcomeClassifier {
    public Optional<ToolOutcomeObservation> classify(ToolCall call) {
        ToolCallStatus status = call.status();
        if (status == ToolCallStatus.COMPLETED || !terminal(status)) return Optional.empty();
        Map<String, Object> attributes =
                call.error().map(value -> value.error().attributes()).orElse(Map.of());
        String errorCode =
                call.error().map(value -> value.error().code().value()).orElse("UNCLASSIFIED_TERMINAL");
        ToolFailureCategory category = category(status, text(attributes, "failureCategory", errorCode));
        String stableCode = token(text(attributes, "stableFailureCode", text(attributes, "failureCode", errorCode)));
        String operationFamily = token(text(
                attributes,
                "operationFamily",
                call.arguments().values().get("operationFamily") instanceof String family ? family : "UNKNOWN"));
        String resourceClass = token(text(attributes, "resourceClass", defaultResource(category)));
        String sandboxDigest = digestOrUnknown(text(attributes, "sandboxProfileDigest", ""));
        String toolCoordinate = FailureFingerprint.digest(List.of(call.toolName(), call.toolVersion()));
        FailureFingerprint fingerprint = new FailureFingerprint(
                toolCoordinate, operationFamily, category, stableCode, resourceClass, sandboxDigest);
        return Optional.of(new ToolOutcomeObservation(call.id().value(), status, category, fingerprint));
    }

    private static boolean terminal(ToolCallStatus status) {
        return switch (status) {
            case FAILED, DENIED, CANCELLED, TIMEOUT -> true;
            default -> false;
        };
    }

    private static ToolFailureCategory category(ToolCallStatus status, String value) {
        if (status == ToolCallStatus.DENIED) return ToolFailureCategory.POLICY_DENIED;
        if (status == ToolCallStatus.CANCELLED
                && ("CANCELLED".equalsIgnoreCase(value)
                        || value.toUpperCase(Locale.ROOT).contains("CANCEL"))) {
            return ToolFailureCategory.CANCELLED;
        }
        if (status == ToolCallStatus.TIMEOUT) return ToolFailureCategory.TIMEOUT;
        String normalized = value.toUpperCase(Locale.ROOT);
        for (ToolFailureCategory candidate : ToolFailureCategory.values()) {
            if (normalized.equals(candidate.name())) return candidate;
        }
        if (normalized.contains("OUTCOME_UNKNOWN")) return ToolFailureCategory.OUTCOME_UNKNOWN;
        if (normalized.contains("POLICY")) return ToolFailureCategory.POLICY_DENIED;
        if (normalized.contains("TIMEOUT")) return ToolFailureCategory.TIMEOUT;
        return ToolFailureCategory.UNKNOWN;
    }

    private static String defaultResource(ToolFailureCategory category) {
        return switch (category) {
            case FILESYSTEM_DENIED -> "FILESYSTEM";
            case NETWORK_DENIED -> "NETWORK";
            case DEPENDENCY_UNAVAILABLE -> "TOOLCHAIN";
            case TIMEOUT, CANCELLED, OUTCOME_UNKNOWN -> "PROCESS";
            case POLICY_DENIED -> "POLICY";
            default -> "UNKNOWN";
        };
    }

    private static String text(Map<String, Object> values, String key, String fallback) {
        Object value = values.get(key);
        return value instanceof String text && !text.isBlank() && text.length() <= 256 ? text : fallback;
    }

    private static String token(String value) {
        String normalized = value.trim().toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9_.:-]", "_");
        if (normalized.isEmpty()) return "UNKNOWN";
        return normalized.substring(0, Math.min(128, normalized.length()));
    }

    private static String digestOrUnknown(String value) {
        String normalized = value.trim();
        return normalized.matches("(?:sha256:)?[0-9a-f]{64}")
                ? normalized
                : FailureFingerprint.digest(List.of("unknown-sandbox-profile"));
    }
}
