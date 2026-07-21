package io.haifa.agent.model.openai;

import io.haifa.agent.model.api.CredentialRef;
import io.haifa.agent.model.api.ModelCapability;
import io.haifa.agent.model.api.ModelDefinition;
import io.haifa.agent.model.api.ModelDefinitionId;
import io.haifa.agent.model.api.ModelProviderDefinition;
import io.haifa.agent.model.api.ModelProviderId;
import io.haifa.agent.model.api.ModelStatus;
import io.haifa.agent.model.api.ProviderStatus;
import java.net.URI;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;

/** Safe first-integration defaults. Values remain ordinary governed configuration. */
public final class DeepSeekDefaults {
    public static final ModelProviderId PROVIDER_ID = new ModelProviderId("deepseek");
    public static final ModelDefinitionId MODEL_ID = new ModelDefinitionId("deepseek-v4-pro");
    public static final String ADAPTER_TYPE = "openai-compatible";
    public static final URI ENDPOINT = URI.create("https://api.deepseek.com");

    private DeepSeekDefaults() {}

    public static ModelProviderDefinition provider() {
        ModelDefinition model = new ModelDefinition(
                MODEL_ID,
                "2026-07-21",
                PROVIDER_ID,
                "deepseek-v4-pro",
                "DeepSeek V4 Pro",
                ModelStatus.ACTIVE,
                EnumSet.of(ModelCapability.TEXT_CHAT, ModelCapability.TOOL_CALLING, ModelCapability.STRUCTURED_OUTPUT),
                1_048_576,
                393_216,
                Map.of("thinking", "disabled"),
                Map.of("source", "deepseek-official-docs-2026-07-21"));
        return new ModelProviderDefinition(
                PROVIDER_ID,
                "2026-07-21",
                "DeepSeek",
                ADAPTER_TYPE,
                ENDPOINT,
                new CredentialRef("env://DEEPSEEK_API_KEY"),
                ProviderStatus.ACTIVE,
                List.of(model),
                Map.of("thinking", "disabled"),
                Map.of());
    }
}
