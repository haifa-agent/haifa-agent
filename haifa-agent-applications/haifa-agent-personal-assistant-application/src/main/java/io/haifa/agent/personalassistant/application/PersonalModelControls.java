package io.haifa.agent.personalassistant.application;

import io.haifa.agent.model.api.ModelReasoningEffort;
import java.util.List;
import java.util.Objects;

/** Four closed controls understood by the PA renderer. */
public record PersonalModelControls(
        ResponseModeControl responseMode,
        ReasoningEffortControl reasoningEffort,
        ResponseLengthControl responseLength,
        ApiStyleControl apiStyle) {
    public PersonalModelControls {
        Objects.requireNonNull(responseMode);
        Objects.requireNonNull(reasoningEffort);
        Objects.requireNonNull(responseLength);
        Objects.requireNonNull(apiStyle);
    }

    public record ResponseModeControl(
            String kind,
            boolean visible,
            boolean readOnly,
            List<PersonalResponseMode> allowedValues,
            PersonalResponseMode recommendedValue,
            String effectiveSummary,
            String helpText) {
        public ResponseModeControl {
            requireKind(kind, "responseMode");
            allowedValues = List.copyOf(allowedValues);
            requireRecommended(allowedValues, recommendedValue);
            requireText(effectiveSummary, "effectiveSummary");
            requireText(helpText, "helpText");
        }
    }

    public record ReasoningEffortControl(
            String kind,
            boolean visible,
            boolean readOnly,
            List<ModelReasoningEffort> allowedValues,
            ModelReasoningEffort recommendedValue,
            String effectiveSummary,
            String helpText) {
        public ReasoningEffortControl {
            requireKind(kind, "reasoningEffort");
            allowedValues = List.copyOf(allowedValues);
            if (visible) requireRecommended(allowedValues, recommendedValue);
            requireText(effectiveSummary, "effectiveSummary");
            requireText(helpText, "helpText");
        }
    }

    public record ResponseLengthControl(
            String kind,
            boolean visible,
            boolean readOnly,
            List<PersonalResponseLength> allowedValues,
            PersonalResponseLength recommendedValue,
            String effectiveSummary,
            String helpText) {
        public ResponseLengthControl {
            requireKind(kind, "responseLength");
            allowedValues = List.copyOf(allowedValues);
            requireRecommended(allowedValues, recommendedValue);
            requireText(effectiveSummary, "effectiveSummary");
            requireText(helpText, "helpText");
        }
    }

    public record ApiStyleControl(
            String kind,
            boolean visible,
            boolean readOnly,
            List<String> allowedValues,
            String recommendedValue,
            String effectiveSummary,
            String helpText) {
        public ApiStyleControl {
            requireKind(kind, "apiStyle");
            allowedValues = List.copyOf(allowedValues);
            requireRecommended(allowedValues, recommendedValue);
            requireText(effectiveSummary, "effectiveSummary");
            requireText(helpText, "helpText");
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
