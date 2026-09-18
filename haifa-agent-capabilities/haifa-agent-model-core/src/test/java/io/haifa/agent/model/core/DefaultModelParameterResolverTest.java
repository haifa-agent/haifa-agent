package io.haifa.agent.model.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.haifa.agent.model.api.ApiStyleId;
import io.haifa.agent.model.api.ModelBindingProfile;
import io.haifa.agent.model.api.ModelCapability;
import io.haifa.agent.model.api.ModelDefinitionId;
import io.haifa.agent.model.api.ModelParameterResolutionException;
import io.haifa.agent.model.api.ModelParameterResolutionFailure;
import io.haifa.agent.model.api.ModelParameterResolutionRequest;
import io.haifa.agent.model.api.ModelProfileStatus;
import io.haifa.agent.model.api.ModelReasoningBehavior;
import io.haifa.agent.model.api.ModelReasoningEffort;
import io.haifa.agent.model.api.ModelReasoningMode;
import io.haifa.agent.model.api.ModelReasoningPolicy;
import java.time.LocalDate;
import java.util.EnumSet;
import java.util.OptionalLong;
import java.util.Set;
import org.junit.jupiter.api.Test;

class DefaultModelParameterResolverTest {
    private final DefaultModelParameterResolver resolver = new DefaultModelParameterResolver();

    @Test
    void resolvesExactProfileAndRejectsStaleOrUnsupportedIntent() {
        ModelBindingProfile profile = profile();
        var effective = resolver.resolve(
                profile,
                new ModelParameterResolutionRequest(
                        profile.bindingId(),
                        profile.version(),
                        profile.digest(),
                        ModelReasoningPolicy.enabled(ModelReasoningEffort.HIGH),
                        4096));

        assertThat(effective.profileDigest()).isEqualTo(profile.digest());
        assertThat(effective.frozenOptions())
                .containsEntry("thinking", "enabled")
                .containsEntry("reasoning_effort", "high")
                .containsEntry("max_output_tokens", 4096);

        assertFailure(
                profile,
                new ModelParameterResolutionRequest(
                        profile.bindingId(), "stale", profile.digest(), ModelReasoningPolicy.disabled(), 4096),
                ModelParameterResolutionFailure.PROFILE_STALE);
        assertFailure(
                profile,
                new ModelParameterResolutionRequest(
                        profile.bindingId(),
                        profile.version(),
                        profile.digest(),
                        ModelReasoningPolicy.enabled(ModelReasoningEffort.LOW),
                        4096),
                ModelParameterResolutionFailure.REASONING_EFFORT_UNSUPPORTED);
    }

    private void assertFailure(
            ModelBindingProfile profile,
            ModelParameterResolutionRequest request,
            ModelParameterResolutionFailure failure) {
        assertThatThrownBy(() -> resolver.resolve(profile, request))
                .isInstanceOfSatisfying(
                        ModelParameterResolutionException.class,
                        exception -> assertThat(exception.failure()).isEqualTo(failure));
    }

    private static ModelBindingProfile profile() {
        return ModelBindingProfile.create(
                new ModelDefinitionId("deepseek-v4-flash-chat"),
                new ApiStyleId("openai-chat-completions"),
                "1.0",
                Set.of(ModelCapability.TEXT_CHAT, ModelCapability.REASONING),
                ModelReasoningBehavior.OPTIONAL,
                EnumSet.of(ModelReasoningMode.DISABLED, ModelReasoningMode.ENABLED),
                EnumSet.of(ModelReasoningEffort.HIGH, ModelReasoningEffort.MAX),
                OptionalLong.empty(),
                1,
                8192,
                true,
                ModelProfileStatus.VERIFIED,
                LocalDate.of(2026, 8, 13));
    }
}
