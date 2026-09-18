package io.haifa.agent.personalassistant.server.web.v1.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import io.haifa.agent.model.api.ImageInputProfile;
import io.haifa.agent.model.api.ModelImageSource;
import io.haifa.agent.model.api.ModelProfileStatus;
import io.haifa.agent.model.api.ModelReasoningEffort;
import io.haifa.agent.personalassistant.application.PersonalAssistantApplication;
import io.haifa.agent.personalassistant.application.PersonalModelControls;
import io.haifa.agent.personalassistant.application.PersonalModelOption;
import io.haifa.agent.personalassistant.application.PersonalModelPreferences;
import io.haifa.agent.personalassistant.application.PersonalResponseLength;
import io.haifa.agent.personalassistant.application.PersonalResponseMode;
import io.haifa.agent.personalassistant.application.PersonalSelectionCompatibility;
import io.haifa.agent.personalassistant.server.web.v1.dto.PersonalApiDtos;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** Locks the safe PA HTTP projection for image limits and selection compatibility. */
class PersonalApiMapperModelProjectionTest {

    private final PersonalApiMapper mapper = new PersonalApiMapper();

    @Test
    void mapsImageInputWhiteListAndNeverLeaksProfileIdentity() {
        ImageInputProfile imageProfile =
                ImageInputProfile.standard(Set.of(ModelImageSource.UPLOAD, ModelImageSource.URL), true);
        PersonalApiDtos.Model dto = mapper.model(option(Optional.of(imageProfile)));

        assertThat(dto.imageInput()).isNotNull();
        assertThat(dto.imageInput().allowedSources()).containsExactly("UPLOAD", "URL");
        assertThat(dto.imageInput().supportedMediaTypes())
                .contains("image/png", "image/jpeg", "image/webp", "image/gif");
        assertThat(dto.imageInput().maxImagesPerRequest()).isEqualTo(4);
        assertThat(dto.imageInput().maxBytesPerItem()).isEqualTo(10 * 1024 * 1024L);
        assertThat(dto.imageInput().maxTotalBytes()).isEqualTo(20 * 1024 * 1024L);
        assertThat(dto.imageInput().maxUrlCharacters()).isEqualTo(2048);
        assertThat(dto.imageInput().detailSupported()).isTrue();
        assertThat(dto.imageInput().allowedDetails()).containsExactly("AUTO", "HIGH", "LOW");
    }

    @Test
    void textOnlyModelHasNoImageInputProjection() {
        PersonalApiDtos.Model dto = mapper.model(option(Optional.empty()));

        assertThat(dto.imageInput()).isNull();
    }

    @Test
    void mapsServerComputedSelectionCompatibility() {
        PersonalApiDtos.ModelSelection current =
                mapper.modelSelection(view(PersonalSelectionCompatibility.CURRENT, true));
        PersonalApiDtos.ModelSelection stale =
                mapper.modelSelection(view(PersonalSelectionCompatibility.RESELECTION_REQUIRED, true));
        PersonalApiDtos.ModelSelection gone =
                mapper.modelSelection(view(PersonalSelectionCompatibility.UNAVAILABLE, false));

        assertThat(current.selectionCompatibility()).isEqualTo("CURRENT");
        assertThat(stale.selectionCompatibility()).isEqualTo("RESELECTION_REQUIRED");
        assertThat(gone.selectionCompatibility()).isEqualTo("UNAVAILABLE");
        assertThat(gone.available()).isFalse();
    }

    private static PersonalAssistantApplication.ModelSelectionView view(
            PersonalSelectionCompatibility compatibility, boolean available) {
        return new PersonalAssistantApplication.ModelSelectionView(
                option(Optional.empty()), PersonalModelPreferences.recommended(), 7L, available, compatibility);
    }

    private static PersonalModelOption option(Optional<ImageInputProfile> imageProfile) {
        return new PersonalModelOption(
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
                controls(),
                PersonalModelPreferences.recommended(),
                imageProfile);
    }

    private static PersonalModelControls controls() {
        return new PersonalModelControls(
                new PersonalModelControls.ResponseModeControl(
                        "responseMode",
                        true,
                        false,
                        List.of(PersonalResponseMode.FAST, PersonalResponseMode.RECOMMENDED, PersonalResponseMode.DEEP),
                        PersonalResponseMode.RECOMMENDED,
                        "Balanced quality and token consumption",
                        "Response mode controls reasoning depth and output quality"),
                new PersonalModelControls.ReasoningEffortControl(
                        "reasoningEffort",
                        true,
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
