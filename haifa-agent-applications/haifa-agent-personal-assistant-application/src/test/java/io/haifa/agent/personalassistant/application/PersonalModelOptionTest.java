package io.haifa.agent.personalassistant.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.haifa.agent.model.api.ImageInputProfile;
import io.haifa.agent.model.api.ModelImageSource;
import io.haifa.agent.model.api.ModelProfileStatus;
import io.haifa.agent.model.api.ModelReasoningEffort;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

class PersonalModelOptionTest {

    @Test
    void fullConstructorAcceptsValidValues() {
        ImageInputProfile imageProfile =
                ImageInputProfile.standard(Set.of(ModelImageSource.UPLOAD, ModelImageSource.URL), true);
        PersonalModelControls controls = PersonalModelControlsFixture.defaults();
        PersonalModelPreferences prefs = PersonalModelPreferences.recommended();

        PersonalModelOption option = new PersonalModelOption(
                "qwen3.8-max",
                "aliyun:qwen3.8-max-preview",
                "Qwen 3.8 Max (Preview)",
                "Qwen 3.8 Max (Preview)",
                "aliyun",
                "Aliyun Bailian",
                "OPENAI_CHAT",
                "OpenAI Chat Completions",
                "AVAILABLE",
                "",
                Set.of("TEXT_CHAT", "TOOL_CALLING", "REASONING"),
                128_000,
                16_384,
                "pa-v1",
                "1.0",
                "sha256:abc123",
                ModelProfileStatus.VERIFIED,
                LocalDate.of(2026, 8, 30),
                controls,
                prefs,
                Optional.of(imageProfile));

        assertThat(option.id()).isEqualTo("qwen3.8-max");
        assertThat(option.modelGroupId()).isEqualTo("aliyun:qwen3.8-max-preview");
        assertThat(option.apiStyle()).isEqualTo("OPENAI_CHAT");
        assertThat(option.availability()).isEqualTo("AVAILABLE");
        assertThat(option.maxOutputTokens()).isEqualTo(16_384);
        assertThat(option.imageInput()).isPresent();
    }

    @Test
    void backwardCompatibleConstructorWithoutImageInput() {
        PersonalModelControls controls = PersonalModelControlsFixture.defaults();
        PersonalModelPreferences prefs = PersonalModelPreferences.recommended();

        PersonalModelOption option = new PersonalModelOption(
                "test",
                "group:test",
                "Test",
                "Test",
                "provider",
                "Provider",
                "OPENAI_CHAT",
                "Chat",
                "AVAILABLE",
                "",
                Set.of("TEXT_CHAT"),
                128_000,
                4_096,
                "pa-v1",
                "1.0",
                "digest",
                ModelProfileStatus.VERIFIED,
                LocalDate.now(),
                controls,
                prefs);

        assertThat(option.imageInput()).isEmpty();
    }

    @Test
    void rejectsBlankId() {
        assertThatThrownBy(() -> new PersonalModelOption(
                        "",
                        "group:test",
                        "Test",
                        "Test",
                        "provider",
                        "Provider",
                        "OPENAI_CHAT",
                        "Chat",
                        "AVAILABLE",
                        "",
                        Set.of(),
                        128_000,
                        4_096,
                        "pa-v1",
                        "1.0",
                        "digest",
                        ModelProfileStatus.VERIFIED,
                        LocalDate.now(),
                        PersonalModelControlsFixture.defaults(),
                        PersonalModelPreferences.recommended()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsMaxOutputExceedingContext() {
        assertThatThrownBy(() -> new PersonalModelOption(
                        "test",
                        "group:test",
                        "Test",
                        "Test",
                        "provider",
                        "Provider",
                        "OPENAI_CHAT",
                        "Chat",
                        "AVAILABLE",
                        "",
                        Set.of(),
                        128_000,
                        256_000,
                        "pa-v1",
                        "1.0",
                        "digest",
                        ModelProfileStatus.VERIFIED,
                        LocalDate.now(),
                        PersonalModelControlsFixture.defaults(),
                        PersonalModelPreferences.recommended()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("maxOutputTokens");
    }

    @Test
    void nullUnavailableReasonBecomesEmpty() {
        PersonalModelOption option = new PersonalModelOption(
                "test",
                "group:test",
                "Test",
                "Test",
                "provider",
                "Provider",
                "OPENAI_CHAT",
                "Chat",
                "UNAVAILABLE",
                null,
                Set.of(),
                128_000,
                4_096,
                "pa-v1",
                "1.0",
                "digest",
                ModelProfileStatus.UNVERIFIED,
                LocalDate.now(),
                PersonalModelControlsFixture.defaults(),
                PersonalModelPreferences.recommended());

        assertThat(option.unavailableReason()).isEmpty();
    }

    /** Fixture that provides default PA controls for tests. */
    static final class PersonalModelControlsFixture {
        private PersonalModelControlsFixture() {}

        static PersonalModelControls defaults() {
            return new PersonalModelControls(
                    new PersonalModelControls.ResponseModeControl(
                            "responseMode",
                            true,
                            false,
                            List.of(
                                    PersonalResponseMode.FAST,
                                    PersonalResponseMode.RECOMMENDED,
                                    PersonalResponseMode.DEEP),
                            PersonalResponseMode.RECOMMENDED,
                            "Balanced quality and token consumption",
                            "Response mode controls reasoning depth and output quality"),
                    new PersonalModelControls.ReasoningEffortControl(
                            "reasoningEffort",
                            false,
                            false,
                            List.of(ModelReasoningEffort.LOW, ModelReasoningEffort.MEDIUM, ModelReasoningEffort.HIGH),
                            ModelReasoningEffort.MEDIUM,
                            "Standard reasoning depth",
                            "Controls the depth of model reasoning in DEEP mode"),
                    new PersonalModelControls.ResponseLengthControl(
                            "responseLength",
                            true,
                            false,
                            List.of(
                                    PersonalResponseLength.SHORT,
                                    PersonalResponseLength.RECOMMENDED,
                                    PersonalResponseLength.LONG),
                            PersonalResponseLength.RECOMMENDED,
                            "Standard response length",
                            "Controls the approximate length of model responses"),
                    new PersonalModelControls.ApiStyleControl(
                            "apiStyle",
                            false,
                            true,
                            List.of("OPENAI_CHAT"),
                            "OPENAI_CHAT",
                            "OpenAI Chat Completions",
                            "Protocol style used to communicate with the model"));
        }
    }
}
