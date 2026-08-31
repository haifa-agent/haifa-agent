package io.haifa.agent.application.project.product.coding;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.haifa.agent.model.api.ModelReasoningEffort;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class CodingModelPreferencesTest {

    @Test
    void recommendedDefaultsToRecommendedMode() {
        CodingModelPreferences prefs = CodingModelPreferences.recommended();

        assertThat(prefs.responseMode()).isEqualTo(CodingResponseMode.RECOMMENDED);
        assertThat(prefs.effort()).isEmpty();
    }

    @Test
    void deepModeWithEffort() {
        CodingModelPreferences prefs =
                new CodingModelPreferences(CodingResponseMode.DEEP, Optional.of(ModelReasoningEffort.HIGH));

        assertThat(prefs.responseMode()).isEqualTo(CodingResponseMode.DEEP);
        assertThat(prefs.effort()).contains(ModelReasoningEffort.HIGH);
    }

    @Test
    void rejectsEffortWithNonDeepMode() {
        assertThatThrownBy(() ->
                        new CodingModelPreferences(CodingResponseMode.FAST, Optional.of(ModelReasoningEffort.MEDIUM)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("effort is only valid for DEEP");
    }

    @Test
    void fastModeWithoutEffort() {
        CodingModelPreferences prefs = new CodingModelPreferences(CodingResponseMode.FAST, Optional.empty());

        assertThat(prefs.responseMode()).isEqualTo(CodingResponseMode.FAST);
        assertThat(prefs.effort()).isEmpty();
    }

    @Test
    void digestIsStableForSameInputs() {
        CodingModelPreferences prefs1 = CodingModelPreferences.recommended();
        CodingModelPreferences prefs2 = CodingModelPreferences.recommended();

        assertThat(prefs1.digest()).isEqualTo(prefs2.digest());
        assertThat(prefs1.digest()).startsWith("sha256:");
    }

    @Test
    void digestDiffersForDifferentModes() {
        CodingModelPreferences fast = new CodingModelPreferences(CodingResponseMode.FAST, Optional.empty());
        CodingModelPreferences deep =
                new CodingModelPreferences(CodingResponseMode.DEEP, Optional.of(ModelReasoningEffort.HIGH));

        assertThat(fast.digest()).isNotEqualTo(deep.digest());
    }
}
