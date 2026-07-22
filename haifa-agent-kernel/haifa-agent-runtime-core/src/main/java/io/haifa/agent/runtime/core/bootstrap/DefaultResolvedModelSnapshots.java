package io.haifa.agent.runtime.core.bootstrap;

import io.haifa.agent.model.api.CredentialRef;
import io.haifa.agent.model.api.ModelCapability;
import io.haifa.agent.model.api.ModelDefinitionId;
import io.haifa.agent.model.api.ModelProviderId;
import io.haifa.agent.model.api.ModelReasoningEffort;
import io.haifa.agent.model.api.ModelReasoningPolicy;
import io.haifa.agent.model.api.ResolvedModelSnapshot;
import java.net.URI;
import java.util.EnumSet;

/** Compatibility defaults for the first configured external model. */
public final class DefaultResolvedModelSnapshots {
    private DefaultResolvedModelSnapshots() {}

    public static ResolvedModelSnapshot deepSeekV4Pro() {
        return ResolvedModelSnapshot.create(
                new ModelProviderId("deepseek"),
                "2026-07-21",
                new ModelDefinitionId("deepseek-v4-pro"),
                "2026-07-21",
                "deepseek-v4-pro",
                "openai-compatible",
                "1.0.0",
                URI.create("https://api.deepseek.com"),
                new CredentialRef("env://DEEPSEEK_API_KEY"),
                EnumSet.of(
                        ModelCapability.TEXT_CHAT,
                        ModelCapability.TOOL_CALLING,
                        ModelCapability.STRUCTURED_OUTPUT,
                        ModelCapability.REASONING),
                1_048_576,
                8_192,
                ModelReasoningPolicy.enabled(ModelReasoningEffort.HIGH).frozenOptions(),
                ModelReasoningPolicy.enabled(ModelReasoningEffort.HIGH).frozenOptions());
    }
}
