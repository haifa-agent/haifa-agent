package io.haifa.agent.personalassistant.server.configuration.model;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.haifa.agent.core.tool.ProviderToolCallCorrelationId;
import io.haifa.agent.model.api.AgentChatModel;
import io.haifa.agent.model.api.AgentChatResponse;
import io.haifa.agent.model.api.CredentialRef;
import io.haifa.agent.model.api.ModelCapability;
import io.haifa.agent.model.api.ModelDefinitionId;
import io.haifa.agent.model.api.ModelFinishReason;
import io.haifa.agent.model.api.ModelMessageRole;
import io.haifa.agent.model.api.ModelProviderId;
import io.haifa.agent.model.api.ModelToolCall;
import io.haifa.agent.model.api.ModelUsage;
import io.haifa.agent.model.api.ResolvedModelSnapshot;
import io.haifa.agent.model.openai.EnvironmentCredentialResolver;
import io.haifa.agent.model.openai.OpenAiCompatibleChatModel;
import io.haifa.agent.personalassistant.application.product.PersonalAssistantProfile;
import io.haifa.agent.personalassistant.server.configuration.product.PersonalAssistantProperties;
import io.haifa.agent.sdk.contribution.ModelContribution;
import io.haifa.agent.sdk.contribution.SdkContributionMetadata;
import io.haifa.agent.sdk.product.ProductCapabilities;
import io.haifa.agent.sdk.product.ProductContributionCoordinate;
import io.haifa.agent.sdk.product.ProductProviderSuitability;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

/** Creates either the production remote adapter or an explicitly enabled deterministic acceptance model. */
public final class PersonalModelFactory {
    private PersonalModelFactory() {}

    public static ModelContribution create(PersonalAssistantProperties.Model properties, ObjectMapper mapper) {
        boolean deterministic = "deterministic".equals(properties.mode());
        String adapter = deterministic ? "personal-deterministic" : "openai-compatible";
        ResolvedModelSnapshot snapshot = ResolvedModelSnapshot.create(
                new ModelProviderId(deterministic ? "personal-local" : "deepseek"),
                "1.0.0",
                new ModelDefinitionId("personal-chat"),
                "1.0.0",
                properties.providerModelId(),
                adapter,
                "1.0.0",
                properties.endpoint(),
                new CredentialRef(properties.credentialReference()),
                Set.of(ModelCapability.TEXT_CHAT, ModelCapability.TOOL_CALLING),
                64_000,
                8_192,
                Map.of(),
                Map.of());
        AgentChatModel model = deterministic
                ? new DeterministicAcceptanceModel(properties.providerModelId())
                : new OpenAiCompatibleChatModel(
                        adapter,
                        "1.0.0",
                        HttpClient.newBuilder()
                                .connectTimeout(Duration.ofSeconds(10))
                                .build(),
                        mapper,
                        new EnvironmentCredentialResolver(),
                        false,
                        4 * 1024 * 1024);
        return new ModelContribution(
                new SdkContributionMetadata(
                        new ProductContributionCoordinate("haifa-personal-model", "1.0.0"),
                        ProductCapabilities.MODEL,
                        snapshot.configurationDigest(),
                        deterministic ? ProductProviderSuitability.DEVELOPMENT : ProductProviderSuitability.PRODUCTION,
                        deterministic ? "Explicit offline acceptance model" : "OpenAI-compatible Personal model"),
                model,
                snapshot);
    }

    /**
     * Test-only-by-configuration model. Markers select one public tool alias, and a following TOOL message
     * always terminates. It never becomes the default production mode.
     */
    private static final class DeterministicAcceptanceModel implements AgentChatModel {
        private final String modelId;
        private final AtomicLong sequence = new AtomicLong();

        private DeterministicAcceptanceModel(String modelId) {
            this.modelId = modelId;
        }

        @Override
        public AgentChatResponse invoke(io.haifa.agent.model.api.AgentChatRequest request) {
            long current = sequence.incrementAndGet();
            if (request.messages().getLast().role() == ModelMessageRole.TOOL) {
                return response(current, "The requested capability completed.", List.of(), ModelFinishReason.STOP);
            }
            String prompt = request.messages().stream()
                    .filter(message -> message.role() == ModelMessageRole.USER)
                    .map(io.haifa.agent.model.api.ModelMessage::content)
                    .reduce((left, right) -> right)
                    .orElse("");
            String alias;
            Map<String, Object> arguments;
            if (prompt.contains("[skill]")) {
                alias = PersonalAssistantProfile.SKILL_LOAD_ALIAS;
                arguments = Map.of("skill", PersonalAssistantProfile.BUNDLED_SKILL_ALIAS);
            } else if (prompt.contains("[mcp]")) {
                alias = PersonalAssistantProfile.MCP_TOOL_ALIAS;
                arguments = Map.of("text", "offline MCP verification");
            } else if (prompt.contains("[tool]")) {
                alias = PersonalAssistantProfile.PRODUCT_TOOL_ALIAS;
                arguments = Map.of("items", List.of("review the plan", "confirm completion"));
            } else {
                return response(current, "Personal Assistant is ready.", List.of(), ModelFinishReason.STOP);
            }
            return response(
                    current,
                    "",
                    List.of(new ModelToolCall(
                            new ProviderToolCallCorrelationId("personal-call-" + current), alias, arguments)),
                    ModelFinishReason.TOOL_CALLS);
        }

        private AgentChatResponse response(
                long id, String content, List<ModelToolCall> calls, ModelFinishReason reason) {
            return new AgentChatResponse(
                    "personal-response-" + id,
                    modelId,
                    content,
                    calls,
                    reason,
                    ModelUsage.unpriced(12, Math.max(1, content.length() / 4)),
                    "",
                    Map.of("deterministic", true));
        }
    }
}
