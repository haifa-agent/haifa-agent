package io.haifa.agent.model.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ResolvedModelSnapshotEffectiveParametersTest {
    @Test
    void derivesDigestBoundRunSnapshotWithoutMutatingBindingSnapshot() {
        ResolvedModelSnapshot base = ResolvedModelSnapshot.create(
                new ModelProviderId("deepseek"),
                "1",
                new ModelDefinitionId("deepseek-v4-flash-chat"),
                "1",
                "deepseek-v4-flash",
                ModelApiStyles.OPENAI_CHAT_ADAPTER,
                "1",
                ModelApiStyles.OPENAI_CHAT_COMPLETIONS,
                "deepseek",
                URI.create("https://api.deepseek.com"),
                new CredentialRef("env://DEEPSEEK_API_KEY"),
                true,
                Set.of(ModelCapability.TEXT_CHAT, ModelCapability.REASONING),
                65536,
                16384,
                Map.of(),
                Map.of("thinking", "disabled"));
        EffectiveModelParameters parameters = new EffectiveModelParameters(
                base.modelId(),
                "1.0",
                "sha256:" + "0".repeat(64),
                new ModelReasoningPolicy(
                        ModelReasoningMode.ENABLED, Optional.of(ModelReasoningEffort.HIGH), OptionalLong.empty()),
                8192);

        ResolvedModelSnapshot derived = base.withEffectiveParameters(parameters);

        assertThat(derived.maxOutputTokens()).isEqualTo(8192);
        assertThat(derived.invocationOptions()).containsEntry("thinking", "enabled");
        assertThat(derived.configurationDigest()).isNotEqualTo(base.configurationDigest());
        assertThat(base.invocationOptions()).containsEntry("thinking", "disabled");
        assertThatThrownBy(() -> base.withEffectiveParameters(new EffectiveModelParameters(
                        new ModelDefinitionId("other"),
                        "1.0",
                        "sha256:" + "0".repeat(64),
                        ModelReasoningPolicy.disabled(),
                        1024)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("different model binding");
    }
}
