package io.haifa.agent.application.project.product.coding;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.haifa.agent.model.api.ImageInputProfile;
import io.haifa.agent.model.api.ModelImageSource;
import io.haifa.agent.model.api.ModelReasoningEffort;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

class CodingModelOptionTest {

    @Test
    void backwardCompatibleConstructorFailsClosedWithoutAProfile() {
        CodingModelOption option = new CodingModelOption(
                "test-model", "Test Model", "test-provider", "Test Provider", Set.of("TEXT_CHAT"), 128_000);

        assertThat(option.id()).isEqualTo("test-model");
        assertThat(option.displayName()).isEqualTo("Test Model");
        assertThat(option.providerId()).isEqualTo("test-provider");
        assertThat(option.providerDisplayName()).isEqualTo("Test Provider");
        assertThat(option.capabilities()).containsExactly("TEXT_CHAT");
        assertThat(option.contextWindow()).isEqualTo(128_000);
        assertThat(option.maxOutputTokens()).isEqualTo(128_000);
        assertThat(option.state().bindingAvailability()).isEqualTo(CodingModelState.BindingAvailability.UNAVAILABLE);
        assertThat(option.unavailableReason()).isNotBlank();
        assertThat(option.controls()).isEqualTo(CodingModelControls.unavailable());
        assertThat(option.recommendedPreferences()).isEqualTo(CodingModelPreferences.recommended());
        assertThat(option.imageInput()).isEmpty();
    }

    @Test
    void fullConstructorAcceptsValidValues() {
        ImageInputProfile imageProfile =
                ImageInputProfile.standard(Set.of(ModelImageSource.UPLOAD, ModelImageSource.URL), true);
        CodingModelControls controls = CodingModelControls.defaults();
        CodingModelPreferences prefs =
                new CodingModelPreferences(CodingResponseMode.DEEP, Optional.of(ModelReasoningEffort.HIGH));

        CodingModelOption option = new CodingModelOption(
                "qwen3.8-max",
                "Qwen 3.8 Max",
                "aliyun",
                "Aliyun Bailian",
                Set.of("TEXT_CHAT", "TOOL_CALLING", "REASONING"),
                128_000,
                16_384,
                new CodingModelState(
                        CodingModelState.Connection.CONNECTED,
                        CodingModelState.BindingAvailability.AVAILABLE,
                        CodingModelState.RuntimeStatus.NORMAL,
                        CodingModelState.RunScope.IDLE),
                "",
                controls,
                prefs,
                Optional.of(imageProfile));

        assertThat(option.id()).isEqualTo("qwen3.8-max");
        assertThat(option.maxOutputTokens()).isEqualTo(16_384);
        assertThat(option.state().bindingAvailability()).isEqualTo(CodingModelState.BindingAvailability.AVAILABLE);
        assertThat(option.imageInput()).isPresent();
        assertThat(option.imageInput().orElseThrow().maxImagesPerRequest()).isEqualTo(4);
    }

    @Test
    void rejectsBlankId() {
        assertThatThrownBy(() -> new CodingModelOption("", "Test", "provider", "Provider", Set.of(), 1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsNullCapabilities() {
        assertThatThrownBy(() -> new CodingModelOption("test", "Test", "provider", "Provider", null, 1))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void rejectsZeroContextWindow() {
        assertThatThrownBy(() -> new CodingModelOption("test", "Test", "provider", "Provider", Set.of(), 0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsMaxOutputExceedingContext() {
        assertThatThrownBy(() -> new CodingModelOption(
                        "test",
                        "Test",
                        "provider",
                        "Provider",
                        Set.of("TEXT_CHAT"),
                        128_000,
                        256_000,
                        CodingModelState.unavailable(),
                        "reason",
                        CodingModelControls.defaults(),
                        CodingModelPreferences.recommended(),
                        Optional.empty()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("maxOutputTokens");
    }

    @Test
    void rejectsZeroMaxOutput() {
        assertThatThrownBy(() -> new CodingModelOption(
                        "test",
                        "Test",
                        "provider",
                        "Provider",
                        Set.of("TEXT_CHAT"),
                        128_000,
                        0,
                        CodingModelState.unavailable(),
                        "reason",
                        CodingModelControls.defaults(),
                        CodingModelPreferences.recommended(),
                        Optional.empty()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("maxOutputTokens");
    }

    @Test
    void unavailableOptionWithReason() {
        CodingModelOption option = new CodingModelOption(
                "test",
                "Test",
                "provider",
                "Provider",
                Set.of(),
                128_000,
                4_096,
                CodingModelState.unavailable(),
                "Binding profile has not passed contract verification",
                CodingModelControls.unavailable(),
                CodingModelPreferences.recommended(),
                Optional.empty());

        assertThat(option.state().bindingAvailability()).isEqualTo(CodingModelState.BindingAvailability.UNAVAILABLE);
        assertThat(option.unavailableReason()).isEqualTo("Binding profile has not passed contract verification");
    }

    @Test
    void rejectsAnAvailableStateWithAnUnavailableReason() {
        CodingModelState available = new CodingModelState(
                CodingModelState.Connection.CONNECTED,
                CodingModelState.BindingAvailability.AVAILABLE,
                CodingModelState.RuntimeStatus.NORMAL,
                CodingModelState.RunScope.IDLE);

        assertThatThrownBy(() -> new CodingModelOption(
                        "test",
                        "Test",
                        "provider",
                        "Provider",
                        Set.of(),
                        128_000,
                        4_096,
                        available,
                        "not selectable",
                        CodingModelControls.defaults(),
                        CodingModelPreferences.recommended(),
                        Optional.empty()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("available model");
    }
}
