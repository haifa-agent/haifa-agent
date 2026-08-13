package io.haifa.agent.model.openai;

import static org.assertj.core.api.Assertions.assertThat;

import io.haifa.agent.model.api.CredentialRef;
import io.haifa.agent.model.api.ModelApiBindingDefinition;
import io.haifa.agent.model.api.ModelApiStyles;
import io.haifa.agent.model.api.ModelCapability;
import io.haifa.agent.model.api.ModelDefinitionId;
import io.haifa.agent.model.api.ModelProfileStatus;
import io.haifa.agent.model.api.ModelProviderId;
import io.haifa.agent.model.api.ModelReasoningEffort;
import io.haifa.agent.model.api.ModelReasoningMode;
import io.haifa.agent.model.api.ResolvedModelSnapshot;
import java.net.URI;
import java.time.LocalDate;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class OpenAiCompatibleModelProfileFactoryTest {
    @Test
    void describesOnlyCapabilitiesVerifiedByCurrentDeepSeekDialect() {
        ResolvedModelSnapshot snapshot = ResolvedModelSnapshot.create(
                new ModelProviderId("deepseek"),
                "1",
                new ModelDefinitionId("deepseek-v4-flash-chat"),
                "1",
                "deepseek-v4-flash",
                ModelApiStyles.OPENAI_CHAT_ADAPTER,
                "1",
                ModelApiStyles.OPENAI_CHAT_COMPLETIONS,
                OpenAiCompatibleDialects.DEEPSEEK,
                URI.create("https://api.deepseek.com"),
                new CredentialRef("env://DEEPSEEK_API_KEY"),
                true,
                Set.of(ModelCapability.TEXT_CHAT, ModelCapability.TOOL_CALLING, ModelCapability.REASONING),
                65536,
                16384,
                Map.of(),
                Map.of("thinking", "disabled"));

        var profile = OpenAiCompatibleModelProfileFactory.fromSnapshot(snapshot, LocalDate.of(2026, 8, 13));

        assertThat(profile.bindingId()).isEqualTo(snapshot.modelId());
        assertThat(profile.allowedReasoningModes())
                .containsExactlyInAnyOrder(ModelReasoningMode.DISABLED, ModelReasoningMode.ENABLED);
        assertThat(profile.allowedReasoningEfforts())
                .containsExactlyInAnyOrder(ModelReasoningEffort.HIGH, ModelReasoningEffort.MAX);
        assertThat(profile.toolReasoningContinuationRequired()).isTrue();
        assertThat(profile.digest()).startsWith("sha256:");
    }

    @Test
    void doesNotTrustAnUnknownBindingOnlyBecauseTheProviderIdIsDeepSeek() {
        ResolvedModelSnapshot snapshot = ResolvedModelSnapshot.create(
                new ModelProviderId("deepseek"),
                "1",
                new ModelDefinitionId("deepseek-unknown"),
                "1",
                "future-model",
                ModelApiStyles.OPENAI_CHAT_ADAPTER,
                "1",
                ModelApiStyles.OPENAI_CHAT_COMPLETIONS,
                ModelApiBindingDefinition.STANDARD_DIALECT,
                URI.create("https://api.deepseek.com"),
                new CredentialRef("env://DEEPSEEK_API_KEY"),
                true,
                Set.of(ModelCapability.TEXT_CHAT, ModelCapability.REASONING),
                65536,
                8192,
                Map.of(),
                Map.of());

        var profile = OpenAiCompatibleModelProfileFactory.fromSnapshot(snapshot, LocalDate.of(2026, 8, 13));

        assertThat(profile.status()).isEqualTo(ModelProfileStatus.UNVERIFIED);
        assertThat(profile.selectable()).isFalse();
    }
}
