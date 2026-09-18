package io.haifa.agent.runtime.core.tool;

import io.haifa.agent.tool.api.ToolSchemaValidationError;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * Internal signal carrying a bounded, value-free repair hint for model-generated Tool arguments.
 *
 * <p>The hint is derived only from schema paths and known validation keywords. It never includes
 * the rejected argument values or arbitrary validator messages.
 */
public final class ToolInputValidationException extends IllegalArgumentException {
    private static final long serialVersionUID = 1L;
    private final String repairHint;

    public ToolInputValidationException(List<ToolSchemaValidationError> errors) {
        super("tool input failed schema validation");
        List<ToolSchemaValidationError> copy = List.copyOf(Objects.requireNonNull(errors, "errors must not be null"));
        if (copy.isEmpty()) throw new IllegalArgumentException("errors must not be empty");
        repairHint = "Repair the tool arguments: "
                + copy.stream()
                        .sorted(Comparator.comparingInt(ToolInputValidationException::priority))
                        .map(ToolInputValidationException::safeHint)
                        .distinct()
                        .limit(3)
                        .collect(java.util.stream.Collectors.joining(" "));
    }

    public String repairHint() {
        return repairHint;
    }

    private static int priority(ToolSchemaValidationError error) {
        return switch (error.keyword()) {
            case "combination" -> 0;
            case "required" -> 1;
            case "additionalProperties" -> 2;
            case "enum", "const" -> 3;
            case "security" -> 4;
            case "oneOf" -> 9;
            default -> 5;
        };
    }

    private static String safeHint(ToolSchemaValidationError error) {
        String path = safePath(error.path());
        return switch (error.keyword()) {
            case "combination" ->
                path + " is incompatible with the selected mode; remove it or choose the matching mode.";
            case "required" -> path + " is required for the selected mode.";
            case "additionalProperties" -> path + " is not allowed for the selected mode.";
            case "enum", "const" -> path + " must use a value allowed by the selected mode.";
            case "oneOf" -> path + " must match exactly one declared invocation mode.";
            case "type" -> path + " has the wrong JSON type.";
            case "security" -> path + " is blocked by the execution safety policy.";
            default -> path + " does not satisfy the declared tool schema.";
        };
    }

    private static String safePath(String value) {
        if (value == null || value.isBlank() || value.length() > 128) return "$";
        boolean safe = value.codePoints()
                .allMatch(character -> Character.isLetterOrDigit(character)
                        || character == '$'
                        || character == '.'
                        || character == '/'
                        || character == '_'
                        || character == '-'
                        || character == '['
                        || character == ']');
        return safe ? value : "$";
    }
}
