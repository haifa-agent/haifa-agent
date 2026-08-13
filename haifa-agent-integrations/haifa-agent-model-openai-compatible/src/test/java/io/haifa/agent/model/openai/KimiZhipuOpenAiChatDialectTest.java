package io.haifa.agent.model.openai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.haifa.agent.core.run.AgentRunId;
import io.haifa.agent.model.api.AgentChatRequest;
import io.haifa.agent.model.api.CredentialRef;
import io.haifa.agent.model.api.ModelApiStyles;
import io.haifa.agent.model.api.ModelCallId;
import io.haifa.agent.model.api.ModelCapability;
import io.haifa.agent.model.api.ModelDefinitionId;
import io.haifa.agent.model.api.ModelMessage;
import io.haifa.agent.model.api.ModelMessageRole;
import io.haifa.agent.model.api.ModelProviderId;
import io.haifa.agent.model.api.ResolvedModelSnapshot;
import io.haifa.agent.model.api.SensitiveModelReasoning;
import java.net.URI;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class KimiZhipuOpenAiChatDialectTest {
    @Test
    void mapsKimiK3EffortWithoutSendingTheK2ThinkingObject() {
        var snapshot = snapshot(
                "kimi",
                "kimi-k3",
                OpenAiCompatibleDialects.KIMI,
                "https://api.moonshot.ai/v1",
                Map.of("thinking", "enabled", "reasoning_effort", "high"));
        Map<String, Object> body = new LinkedHashMap<>();

        KimiOpenAiChatDialect.INSTANCE.applyRequest(request(snapshot, List.of(user()), Map.of()), body);

        assertThat(body).containsEntry("reasoning_effort", "high").doesNotContainKey("thinking");
    }

    @Test
    void preservesKimiK26ReasoningOnlyWhenContinuationExistsAndRejectsExpertSampling() {
        var snapshot = snapshot(
                "kimi",
                "kimi-k2.6",
                OpenAiCompatibleDialects.KIMI,
                "https://api.moonshot.ai/v1",
                Map.of("thinking", "enabled"));
        Map<String, Object> body = new LinkedHashMap<>();
        var messages = List.of(
                user(),
                ModelMessage.assistant("tool pending", List.of(), SensitiveModelReasoning.of("private fixture")));

        KimiOpenAiChatDialect.INSTANCE.applyRequest(request(snapshot, messages, Map.of()), body);

        assertThat(body.get("thinking")).isEqualTo(Map.of("type", "enabled", "keep", "all"));
        assertThatThrownBy(() -> KimiOpenAiChatDialect.INSTANCE.applyRequest(
                        request(snapshot, List.of(user()), Map.of("temperature", 0.2)), new LinkedHashMap<>()))
                .hasMessageContaining("temperature");
    }

    @Test
    void mapsZhipuDynamicAndEffectiveEffortWhileFreezingSamplingOff() {
        var snapshot = snapshot(
                "zhipu",
                "glm-5.2",
                OpenAiCompatibleDialects.ZHIPU,
                "https://open.bigmodel.cn/api/paas/v4",
                Map.of(
                        "thinking",
                        "adaptive",
                        "reasoning_effort",
                        "medium",
                        "do_sample",
                        false,
                        "clear_thinking",
                        false));
        Map<String, Object> body = new LinkedHashMap<>();

        ZhipuOpenAiChatDialect.INSTANCE.applyRequest(request(snapshot, List.of(user()), Map.of()), body);

        assertThat(body)
                .containsEntry("thinking", Map.of("type", "enabled"))
                .containsEntry("reasoning_effort", "high")
                .containsEntry("do_sample", false)
                .containsEntry("clear_thinking", false);
    }

    @Test
    void rejectsProviderEndpointsAndModelsOutsideTheReviewedContracts() {
        var unknownKimi = snapshot(
                "kimi",
                "future-kimi",
                OpenAiCompatibleDialects.KIMI,
                "https://api.moonshot.ai/v1",
                Map.of("thinking", "enabled"));
        var codingEndpoint = snapshot(
                "zhipu",
                "glm-5.2",
                OpenAiCompatibleDialects.ZHIPU,
                "https://open.bigmodel.cn/api/coding/paas/v4",
                Map.of("thinking", "adaptive", "do_sample", false));

        assertThatThrownBy(() -> KimiOpenAiChatDialect.INSTANCE.validateSnapshot(unknownKimi, false))
                .hasMessageContaining("not verified");
        assertThatThrownBy(() -> ZhipuOpenAiChatDialect.INSTANCE.validateSnapshot(codingEndpoint, false))
                .hasMessageContaining("general API endpoint");
    }

    private static AgentChatRequest request(
            ResolvedModelSnapshot snapshot, List<ModelMessage> messages, Map<String, Object> options) {
        return new AgentChatRequest(
                new ModelCallId("provider-contract-call"),
                new AgentRunId("provider-contract-run"),
                1,
                1,
                snapshot,
                messages,
                List.of(),
                4096,
                Duration.ofSeconds(30),
                options);
    }

    private static ModelMessage user() {
        return ModelMessage.text(ModelMessageRole.USER, "hello");
    }

    private static ResolvedModelSnapshot snapshot(
            String provider, String model, String dialect, String endpoint, Map<String, Object> options) {
        return ResolvedModelSnapshot.create(
                new ModelProviderId(provider),
                "1",
                new ModelDefinitionId(provider + "-" + model.replace('.', '-')),
                "1",
                model,
                ModelApiStyles.OPENAI_CHAT_ADAPTER,
                "1",
                ModelApiStyles.OPENAI_CHAT_COMPLETIONS,
                dialect,
                URI.create(endpoint),
                new CredentialRef("env://TEST_API_KEY"),
                true,
                Set.of(ModelCapability.TEXT_CHAT, ModelCapability.TOOL_CALLING, ModelCapability.REASONING),
                1_048_576,
                131_072,
                Map.of(),
                options);
    }
}
