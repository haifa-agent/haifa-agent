package io.haifa.agent.application.project.product.coding;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.haifa.agent.model.api.ModelReasoningEffort;
import java.util.List;
import org.junit.jupiter.api.Test;

class CodingModelControlsTest {

    @Test
    void defaultsHaveThreeResponseModesAndReasoningHidden() {
        CodingModelControls controls = CodingModelControls.defaults();

        assertThat(controls.responseMode().allowedValues())
                .containsExactly(CodingResponseMode.FAST, CodingResponseMode.RECOMMENDED, CodingResponseMode.DEEP);
        assertThat(controls.responseMode().recommendedValue()).isEqualTo(CodingResponseMode.RECOMMENDED);
        assertThat(controls.responseMode().visible()).isTrue();
        assertThat(controls.responseMode().readOnly()).isFalse();

        assertThat(controls.reasoningEffort().visible()).isFalse();
        assertThat(controls.reasoningEffort().allowedValues()).isEmpty();
    }

    @Test
    void reasoningEffortVisibleForDeepCapableProfile() {
        CodingModelControls controls = new CodingModelControls(
                new CodingModelControls.ResponseModeControl(
                        "responseMode",
                        true,
                        false,
                        List.of(CodingResponseMode.FAST, CodingResponseMode.RECOMMENDED, CodingResponseMode.DEEP),
                        CodingResponseMode.RECOMMENDED,
                        "Balanced"),
                new CodingModelControls.ReasoningEffortControl(
                        "reasoningEffort",
                        true,
                        false,
                        List.of(ModelReasoningEffort.LOW, ModelReasoningEffort.MEDIUM, ModelReasoningEffort.HIGH),
                        ModelReasoningEffort.MEDIUM,
                        "Multi-level"));

        assertThat(controls.reasoningEffort().visible()).isTrue();
        assertThat(controls.reasoningEffort().allowedValues())
                .containsExactly(ModelReasoningEffort.LOW, ModelReasoningEffort.MEDIUM, ModelReasoningEffort.HIGH);
    }

    @Test
    void rejectsWrongControlKind() {
        assertThatThrownBy(() -> new CodingModelControls.ResponseModeControl(
                        "wrongKind", true, false, List.of(CodingResponseMode.FAST), CodingResponseMode.FAST, "summary"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("control kind");
    }

    @Test
    void rejectsRecommendedNotInAllowed() {
        assertThatThrownBy(() -> new CodingModelControls.ResponseModeControl(
                        "responseMode",
                        true,
                        false,
                        List.of(CodingResponseMode.FAST),
                        CodingResponseMode.RECOMMENDED,
                        "summary"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("recommended value must be allowed");
    }

    @Test
    void rejectsBlankEffectiveSummary() {
        assertThatThrownBy(() -> new CodingModelControls.ResponseModeControl(
                        "responseMode", true, false, List.of(CodingResponseMode.FAST), CodingResponseMode.FAST, "  "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("effectiveSummary");
    }

    @Test
    void reasoningEffortAllowsEmptyAllowedWhenNotVisible() {
        CodingModelControls controls = new CodingModelControls(
                new CodingModelControls.ResponseModeControl(
                        "responseMode",
                        true,
                        false,
                        List.of(CodingResponseMode.FAST, CodingResponseMode.RECOMMENDED),
                        CodingResponseMode.RECOMMENDED,
                        "Standard"),
                new CodingModelControls.ReasoningEffortControl(
                        "reasoningEffort", false, false, List.of(), ModelReasoningEffort.MEDIUM, "Standard"));

        assertThat(controls.reasoningEffort().visible()).isFalse();
        assertThat(controls.reasoningEffort().allowedValues()).isEmpty();
    }
}
