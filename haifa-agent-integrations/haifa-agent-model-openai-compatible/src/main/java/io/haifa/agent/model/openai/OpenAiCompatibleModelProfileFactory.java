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
        ModelProfileStatus status = verifiedBinding(snapshot)
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
        if (verifiedReadOnlyResponsesBinding(snapshot)) {
            return reasoningProfile(
                    snapshot,
                    verifiedOn,
                    ModelReasoningBehavior.ALWAYS,
                    Set.of(ModelReasoningMode.ENABLED),
                    Set.of(ModelReasoningEffort.HIGH),
                    requiresToolReasoningContinuation(snapshot));
        }
        if (verifiedKimiK3(snapshot)) {
            return reasoningProfile(
                    snapshot,
                    verifiedOn,
                    ModelReasoningBehavior.ALWAYS,
                    Set.of(ModelReasoningMode.ENABLED),
                    EnumSet.of(ModelReasoningEffort.LOW, ModelReasoningEffort.HIGH, ModelReasoningEffort.MAX),
                    true);
        }
        if (verifiedKimiAlwaysK2(snapshot)) {
            return reasoningProfile(
                    snapshot,
                    verifiedOn,
                    ModelReasoningBehavior.ALWAYS,
                    Set.of(ModelReasoningMode.ENABLED),
                    Set.of(ModelReasoningEffort.HIGH),
                    true);
        }
        if (verifiedKimiOptional(snapshot)) {
            return reasoningProfile(
                    snapshot,
                    verifiedOn,
                    ModelReasoningBehavior.OPTIONAL,
                    EnumSet.of(ModelReasoningMode.DISABLED, ModelReasoningMode.ENABLED),
                    Set.of(ModelReasoningEffort.HIGH),
                    true);
        }
        if (verifiedZhipuDynamic52(snapshot)) {
            return reasoningProfile(
                    snapshot,
                    verifiedOn,
                    ModelReasoningBehavior.ADAPTIVE,
                    EnumSet.of(ModelReasoningMode.DISABLED, ModelReasoningMode.ENABLED, ModelReasoningMode.ADAPTIVE),
                    EnumSet.of(ModelReasoningEffort.HIGH, ModelReasoningEffort.MAX),
                    true);
        }
        if (verifiedZhipuDynamicLegacy(snapshot)) {
            return reasoningProfile(
                    snapshot,
                    verifiedOn,
                    ModelReasoningBehavior.ADAPTIVE,
                    EnumSet.of(ModelReasoningMode.DISABLED, ModelReasoningMode.ENABLED, ModelReasoningMode.ADAPTIVE),
                    Set.of(ModelReasoningEffort.HIGH),
                    true);
        }
        if (verifiedZhipuForced(snapshot)) {
            return reasoningProfile(
                    snapshot,
                    verifiedOn,
                    ModelReasoningBehavior.OPTIONAL,
                    EnumSet.of(ModelReasoningMode.DISABLED, ModelReasoningMode.ENABLED),
                    Set.of(ModelReasoningEffort.HIGH),
                    true);
        }
        Set<ModelReasoningEffort> efforts = verifiedDeepSeekBinding(snapshot) || verifiedBailianBinding(snapshot)
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
                requiresToolReasoningContinuation(snapshot),
                status,
                verifiedOn);
    }

    private static ModelBindingProfile reasoningProfile(
            ResolvedModelSnapshot snapshot,
            LocalDate verifiedOn,
            ModelReasoningBehavior behavior,
            Set<ModelReasoningMode> modes,
            Set<ModelReasoningEffort> efforts,
            boolean continuation) {
        return ModelBindingProfile.create(
                snapshot.modelId(),
                snapshot.apiStyle(),
                CURRENT_PROFILE_VERSION,
                snapshot.capabilities(),
                behavior,
                modes,
                efforts,
                OptionalLong.empty(),
                1,
                snapshot.maxOutputTokens(),
                continuation,
                ModelProfileStatus.VERIFIED,
                verifiedOn);
    }

    private static boolean verifiedBinding(ResolvedModelSnapshot snapshot) {
        return verifiedDeepSeekBinding(snapshot)
                || verifiedBailianBinding(snapshot)
                || verifiedKimiK3(snapshot)
                || verifiedKimiAlwaysK2(snapshot)
                || verifiedKimiOptional(snapshot)
                || verifiedZhipuDynamic52(snapshot)
                || verifiedZhipuDynamicLegacy(snapshot)
                || verifiedZhipuForced(snapshot)
                || verifiedCodexBinding(snapshot)
                || verifiedSiliconFlowBinding(snapshot)
                || verifiedTokenRhythmBinding(snapshot);
    }

    private static boolean verifiedReadOnlyResponsesBinding(ResolvedModelSnapshot snapshot) {
        return ModelApiStyles.OPENAI_RESPONSES.equals(snapshot.apiStyle())
                && (verifiedDeepSeekBinding(snapshot)
                        || verifiedBailianBinding(snapshot)
                        || verifiedCodexBinding(snapshot));
    }

    private static boolean verifiedCodexBinding(ResolvedModelSnapshot snapshot) {
        return "openai-codex".equals(snapshot.providerId().value())
                && ModelApiStyles.OPENAI_RESPONSES.equals(snapshot.apiStyle())
                && OpenAiResponsesDialects.OPENAI_CODEX.equals(snapshot.dialect())
                && Set.of("gpt-5.6-sol", "gpt-5.6-terra", "gpt-5.6-luna").contains(snapshot.providerModelId());
    }

    private static boolean verifiedBailianBinding(ResolvedModelSnapshot snapshot) {
        if (!"aliyun-bailian".equals(snapshot.providerId().value())) return false;
        Set<String> chat = Set.of(
                "qwen3.8-max-preview",
                "qwen3.7-max",
                "qwen3.7-max-2026-05-17",
                "qwen3.7-plus",
                "qwen3.7-flash",
                "qwen3-vl-plus");
        if (ModelApiStyles.OPENAI_CHAT_COMPLETIONS.equals(snapshot.apiStyle())) {
            return OpenAiCompatibleDialects.ALIYUN_BAILIAN.equals(snapshot.dialect())
                    && chat.contains(snapshot.providerModelId());
        }
        return ModelApiStyles.OPENAI_RESPONSES.equals(snapshot.apiStyle())
                && OpenAiResponsesDialects.ALIYUN_BAILIAN.equals(snapshot.dialect())
                && Set.of("qwen3.8-max-preview", "qwen3.7-max", "qwen3.7-max-2026-05-17", "qwen3.7-plus")
                        .contains(snapshot.providerModelId());
    }

    private static boolean verifiedKimiK3(ResolvedModelSnapshot snapshot) {
        return verifiedChat(snapshot, "kimi", OpenAiCompatibleDialects.KIMI, Set.of("kimi-k3"));
    }

    private static boolean verifiedKimiAlwaysK2(ResolvedModelSnapshot snapshot) {
        return verifiedChat(snapshot, "kimi", OpenAiCompatibleDialects.KIMI, Set.of("kimi-k2.7-code"));
    }

    private static boolean verifiedKimiOptional(ResolvedModelSnapshot snapshot) {
        return verifiedChat(snapshot, "kimi", OpenAiCompatibleDialects.KIMI, Set.of("kimi-k2.6"));
    }

    private static boolean verifiedZhipuDynamic52(ResolvedModelSnapshot snapshot) {
        return verifiedChat(snapshot, "zhipu", OpenAiCompatibleDialects.ZHIPU, Set.of("glm-5.2"))
                || verifiedMessages(snapshot, "zhipu", AnthropicMessagesDialects.ZHIPU, Set.of("glm-5.2"));
    }

    private static boolean verifiedZhipuDynamicLegacy(ResolvedModelSnapshot snapshot) {
        return verifiedChat(snapshot, "zhipu", OpenAiCompatibleDialects.ZHIPU, Set.of("glm-5.1", "glm-5"));
    }

    private static boolean verifiedZhipuForced(ResolvedModelSnapshot snapshot) {
        return verifiedChat(snapshot, "zhipu", OpenAiCompatibleDialects.ZHIPU, Set.of("glm-4.7"));
    }

    private static boolean verifiedSiliconFlowBinding(ResolvedModelSnapshot snapshot) {
        return verifiedChat(
                snapshot, "siliconflow", OpenAiCompatibleDialects.SILICONFLOW, Set.of("deepseek-ai/DeepSeek-V4-Flash"));
    }

    private static boolean verifiedTokenRhythmBinding(ResolvedModelSnapshot snapshot) {
        // TODO(tokenrhythm): extend with the full reviewed TokenRhythm model id list as needed.
        return verifiedChat(snapshot, "tokenrhythm", OpenAiCompatibleDialects.TOKENRHYTHM, Set.of("deepseek-v4-flash"));
    }

    private static boolean verifiedChat(
            ResolvedModelSnapshot snapshot, String provider, String dialect, Set<String> models) {
        return provider.equals(snapshot.providerId().value())
                && ModelApiStyles.OPENAI_CHAT_COMPLETIONS.equals(snapshot.apiStyle())
                && dialect.equals(snapshot.dialect())
                && models.contains(snapshot.providerModelId());
    }

    private static boolean verifiedMessages(
            ResolvedModelSnapshot snapshot, String provider, String dialect, Set<String> models) {
        return provider.equals(snapshot.providerId().value())
                && ModelApiStyles.ANTHROPIC_MESSAGES.equals(snapshot.apiStyle())
                && dialect.equals(snapshot.dialect())
                && models.contains(snapshot.providerModelId());
    }

    private static boolean requiresToolReasoningContinuation(ResolvedModelSnapshot snapshot) {
        return verifiedDeepSeekBinding(snapshot)
                || (verifiedBailianBinding(snapshot)
                        && ModelApiStyles.OPENAI_CHAT_COMPLETIONS.equals(snapshot.apiStyle()))
                || verifiedKimiOptional(snapshot)
                || verifiedZhipuForced(snapshot);
    }

    private static boolean verifiedDeepSeekBinding(ResolvedModelSnapshot snapshot) {
        if (!"deepseek".equals(snapshot.providerId().value())) return false;
        return switch (snapshot.apiStyle().value()) {
            case "openai-chat-completions" ->
                OpenAiCompatibleDialects.DEEPSEEK.equals(snapshot.dialect())
                        && Set.of("deepseek-v4-flash", "deepseek-v4-pro").contains(snapshot.providerModelId());
            case "openai-responses" ->
                OpenAiResponsesDialects.DEEPSEEK.equals(snapshot.dialect())
                        && Set.of("deepseek-v4-flash", "deepseek-v4-pro").contains(snapshot.providerModelId());
            case "anthropic-messages" ->
                AnthropicMessagesDialects.DEEPSEEK.equals(snapshot.dialect())
                        && Set.of("deepseek-v4-flash", "deepseek-v4-pro").contains(snapshot.providerModelId());
            default -> false;
        };
    }
}
