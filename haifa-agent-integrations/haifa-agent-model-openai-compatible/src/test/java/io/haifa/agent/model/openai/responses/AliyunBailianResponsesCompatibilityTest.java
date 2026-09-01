package io.haifa.agent.model.openai.responses;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.haifa.agent.model.api.CredentialRef;
import io.haifa.agent.model.api.ModelApiStyles;
import io.haifa.agent.model.api.ModelCapability;
import io.haifa.agent.model.api.ModelDefinitionId;
import io.haifa.agent.model.api.ModelProviderId;
import io.haifa.agent.model.api.ResolvedModelSnapshot;
import java.net.URI;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class AliyunBailianResponsesCompatibilityTest {
    @Test
    void acceptsOnlyReviewedQwenModelsAtWorkspaceScopedEndpoints() {
        assertThat(OpenAiResponsesDialects.resolve(snapshot("qwen3.7-plus", endpoint()), false)
                        .id())
                .isEqualTo(OpenAiResponsesDialects.ALIYUN_BAILIAN);
        assertThatThrownBy(() -> OpenAiResponsesDialects.resolve(snapshot("future-qwen", endpoint()), false))
                .hasMessageContaining("not verified");
        assertThatThrownBy(() ->
                        OpenAiResponsesDialects.resolve(snapshot("fake-bailian", "qwen3.7-plus", endpoint()), false))
                .hasMessageContaining("not verified");
        assertThatThrownBy(() -> OpenAiResponsesDialects.resolve(
                        snapshot("qwen3.7-plus", URI.create("https://dashscope.aliyuncs.com/compatible-mode/v1")),
                        false))
                .hasMessageContaining("workspace scoped");
    }

    private static URI endpoint() {
        return URI.create("https://workspace-123.cn-beijing.maas.aliyuncs.com/compatible-mode/v1");
    }

    private static ResolvedModelSnapshot snapshot(String providerModelId, URI endpoint) {
        return snapshot("aliyun-bailian", providerModelId, endpoint);
    }

    private static ResolvedModelSnapshot snapshot(String providerId, String providerModelId, URI endpoint) {
        return ResolvedModelSnapshot.create(
                new ModelProviderId(providerId),
                "1",
                new ModelDefinitionId("bailian-responses"),
                "1",
                providerModelId,
                ModelApiStyles.OPENAI_RESPONSES_ADAPTER,
                "1",
                ModelApiStyles.OPENAI_RESPONSES,
                OpenAiResponsesDialects.ALIYUN_BAILIAN,
                endpoint,
                new CredentialRef("env://DASHSCOPE_API_KEY"),
                true,
                Set.of(ModelCapability.TEXT_CHAT, ModelCapability.TOOL_CALLING, ModelCapability.REASONING),
                1_048_576,
                131_072,
                Map.of(),
                Map.of("reasoning_effort", "high"));
    }
}
