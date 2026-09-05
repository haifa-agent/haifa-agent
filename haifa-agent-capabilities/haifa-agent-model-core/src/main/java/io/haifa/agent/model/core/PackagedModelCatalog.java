package io.haifa.agent.model.core;

import io.haifa.agent.model.api.ModelApiStyles;
import io.haifa.agent.model.api.ModelAuthenticationMethod;
import io.haifa.agent.model.api.ModelProviderId;
import java.util.Map;
import java.util.Set;

/**
 * Trusted registration for the versioned catalog packaged by this module.
 *
 * <p>It deliberately contains only the finite dialect and authentication identifiers accepted by the packaged
 * resources. Product assemblies still own endpoint, credential, enablement and binding allowlists through
 * {@link ModelCatalogDeployment}.
 */
public final class PackagedModelCatalog {
    private PackagedModelCatalog() {}

    public static ModelCatalogManifest load(ClassLoader classLoader) {
        return ModelCatalogYamlLoader.fromClasspath(
                        classLoader,
                        Map.of(
                                ModelApiStyles.OPENAI_CHAT_COMPLETIONS,
                                Set.of(
                                        "deepseek-openai-chat",
                                        "aliyun-bailian-openai-chat",
                                        "siliconflow-openai-chat",
                                        "kimi-openai-chat",
                                        "zhipu-openai-chat",
                                        "tokenrhythm-openai-chat"),
                                ModelApiStyles.OPENAI_RESPONSES,
                                Set.of("deepseek-openai-responses", "openai-codex-responses"),
                                ModelApiStyles.ANTHROPIC_MESSAGES,
                                Set.of("deepseek-anthropic-messages"),
                                ModelApiStyles.GOOGLE_GEMINI_GENERATE_CONTENT,
                                Set.of("antigravity-direct")),
                        Map.of(
                                new ModelProviderId("deepseek"),
                                Set.of(ModelAuthenticationMethod.API_KEY),
                                new ModelProviderId("openai-codex"),
                                Set.of(ModelAuthenticationMethod.EXTERNAL_LOGIN),
                                new ModelProviderId("aliyun-bailian"),
                                Set.of(ModelAuthenticationMethod.API_KEY),
                                new ModelProviderId("siliconflow"),
                                Set.of(ModelAuthenticationMethod.API_KEY),
                                new ModelProviderId("kimi"),
                                Set.of(ModelAuthenticationMethod.API_KEY),
                                new ModelProviderId("zhipu"),
                                Set.of(ModelAuthenticationMethod.API_KEY),
                                new ModelProviderId("tokenrhythm"),
                                Set.of(ModelAuthenticationMethod.API_KEY),
                                new ModelProviderId("google-antigravity"),
                                Set.of(ModelAuthenticationMethod.EXTERNAL_LOGIN)))
                .load();
    }
}
