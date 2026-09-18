package io.haifa.agent.application.project.product.coding;

import io.haifa.agent.model.api.ModelReasoningEffort;
import java.util.List;
import java.util.Objects;

/** Two closed controls understood by the Coding terminal renderer. */
public record CodingModelControls(ResponseModeControl responseMode, ReasoningEffortControl reasoningEffort) {
    public CodingModelControls {
        Objects.requireNonNull(responseMode, "responseMode must not be null");
        Objects.requireNonNull(reasoningEffort, "reasoningEffort must not be null");
    }

    public static CodingModelControls defaults() {
        return new CodingModelControls(
                new ResponseModeControl(
                        "responseMode",
                        true,
                        false,
                        List.of(CodingResponseMode.FAST, CodingResponseMode.RECOMMENDED, CodingResponseMode.DEEP),
                        CodingResponseMode.RECOMMENDED,
                        "Balanced quality and token consumption"),
                new ReasoningEffortControl(
                        "reasoningEffort",
                        false,
                        false,
                        List.of(),
                        ModelReasoningEffort.MEDIUM,
                        "Standard reasoning depth"));
    }

    /** Controls for a binding that is visible for reselection guidance but cannot be selected. */
    public static CodingModelControls unavailable() {
        return new CodingModelControls(
                new ResponseModeControl(
                        "responseMode",
                        true,
                        true,
                        List.of(CodingResponseMode.RECOMMENDED),
                        CodingResponseMode.RECOMMENDED,
                        "Unavailable until its binding profile is verified"),
                new ReasoningEffortControl(
                        "reasoningEffort",
                        false,
                        true,
                        List.of(),
                        ModelReasoningEffort.MEDIUM,
                        "Unavailable until its binding profile is verified"));
    }

    public record ResponseModeControl(
            String kind,
            boolean visible,
            boolean readOnly,
            List<CodingResponseMode> allowedValues,
            CodingResponseMode recommendedValue,
            String effectiveSummary) {
        public ResponseModeControl {
            requireKind(kind, "responseMode");
            allowedValues = List.copyOf(allowedValues);
            requireRecommended(allowedValues, recommendedValue);
            requireText(effectiveSummary, "effectiveSummary");
        }
    }

    public record ReasoningEffortControl(
            String kind,
            boolean visible,
            boolean readOnly,
            List<ModelReasoningEffort> allowedValues,
            ModelReasoningEffort recommendedValue,
            String effectiveSummary) {
        public ReasoningEffortControl {
            requireKind(kind, "reasoningEffort");
            allowedValues = List.copyOf(allowedValues);
            if (visible) requireRecommended(allowedValues, recommendedValue);
            requireText(effectiveSummary, "effectiveSummary");
        }
    }

    private static void requireKind(String actual, String expected) {
        if (!expected.equals(actual)) throw new IllegalArgumentException("unsupported control kind");
    }

    private static <T> void requireRecommended(List<T> values, T recommended) {
        if (values.isEmpty() || recommended == null || !values.contains(recommended)) {
            throw new IllegalArgumentException("control recommended value must be allowed");
        }
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " must not be blank");
    }
}
