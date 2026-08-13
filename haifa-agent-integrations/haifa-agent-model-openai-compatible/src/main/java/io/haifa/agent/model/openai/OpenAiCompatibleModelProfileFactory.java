package io.haifa.agent.model.openai;

import io.haifa.agent.model.api.ModelApiBindingDefinition;
import io.haifa.agent.model.api.ModelApiStyles;
import io.haifa.agent.model.api.ModelBindingProfile;
import io.haifa.agent.model.api.ModelCapability;
import io.haifa.agent.model.api.ModelProfileStatus;
import io.haifa.agent.model.api.ModelReasoningBehavior;
import io.haifa.agent.model.api.ModelReasoningEffort;
import io.haifa.agent.model.api.ModelReasoningMode;
import io.haifa.agent.model.api.ResolvedModelSnapshot;
import io.haifa.agent.model.openai.anthropic.AnthropicMessagesDialects;
import io.haifa.agent.model.openai.responses.OpenAiResponsesDialects;
import java.time.LocalDate;
import java.util.EnumSet;
import java.util.OptionalLong;
import java.util.Set;

/** Builds provider-neutral profiles from capabilities already verified by this integration. */
public final class OpenAiCompatibleModelProfileFactory {
    public static final String CURRENT_PROFILE_VERSION = "1.0";

    private OpenAiCompatibleModelProfileFactory() {}

    public static ModelBindingProfile fromSnapshot(ResolvedModelSnapshot snapshot, LocalDate verifiedOn) {
        boolean reasoning = snapshot.capabilities().contains(ModelCapability.REASONING);
        ModelProfileStatus status = verifiedDeepSeekBinding(snapshot)
                        || ModelApiStyles.DETERMINISTIC_CHAT.equals(snapshot.apiStyle())
                        || (ModelApiBindingDefinition.STANDARD_DIALECT.equals(snapshot.dialect()) && !reasoning)
                ? ModelProfileStatus.VERIFIED
                : ModelProfileStatus.UNVERIFIED;
        if (!reasoning) {
            return ModelBindingProfile.create(
                    snapshot.modelId(),
                    snapshot.apiStyle(),
                    CURRENT_PROFILE_VERSION,
                    snapshot.capabilities(),
                    ModelReasoningBehavior.NONE,
                    Set.of(ModelReasoningMode.DISABLED),
                    Set.of(),
                    OptionalLong.empty(),
                    1,
                    snapshot.maxOutputTokens(),
                    false,
                    status,
                    verifiedOn);
        }
        Set<ModelReasoningEffort> efforts = verifiedDeepSeekBinding(snapshot)
                ? EnumSet.of(ModelReasoningEffort.HIGH, ModelReasoningEffort.MAX)
                : EnumSet.of(ModelReasoningEffort.HIGH);
        return ModelBindingProfile.create(
                snapshot.modelId(),
                snapshot.apiStyle(),
                CURRENT_PROFILE_VERSION,
                snapshot.capabilities(),
                ModelReasoningBehavior.OPTIONAL,
                EnumSet.of(ModelReasoningMode.DISABLED, ModelReasoningMode.ENABLED),
                efforts,
                OptionalLong.empty(),
                1,
                snapshot.maxOutputTokens(),
                verifiedDeepSeekBinding(snapshot),
                status,
                verifiedOn);
    }

    private static boolean verifiedDeepSeekBinding(ResolvedModelSnapshot snapshot) {
        if (!"deepseek".equals(snapshot.providerId().value())) return false;
        return switch (snapshot.apiStyle().value()) {
            case "openai-chat-completions" ->
                OpenAiCompatibleDialects.DEEPSEEK.equals(snapshot.dialect())
                        && Set.of("deepseek-v4-flash", "deepseek-v4-pro").contains(snapshot.providerModelId());
            case "openai-responses" ->
                OpenAiResponsesDialects.DEEPSEEK.equals(snapshot.dialect())
                        && "deepseek-v4-flash".equals(snapshot.providerModelId());
            case "anthropic-messages" ->
                AnthropicMessagesDialects.DEEPSEEK.equals(snapshot.dialect())
                        && Set.of("deepseek-v4-flash", "deepseek-v4-pro").contains(snapshot.providerModelId());
            default -> false;
        };
    }
}
