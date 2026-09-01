package io.haifa.agent.model.anthropic;

import com.fasterxml.jackson.databind.JsonNode;
import io.haifa.agent.model.api.AgentChatRequest;
import io.haifa.agent.model.api.ModelApiStyles;
import io.haifa.agent.model.api.ResolvedModelSnapshot;
import java.net.URI;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

final class DeepSeekAnthropicMessagesDialect implements AnthropicMessagesDialect {
    static final DeepSeekAnthropicMessagesDialect INSTANCE = new DeepSeekAnthropicMessagesDialect();

    private DeepSeekAnthropicMessagesDialect() {}

    @Override
    public String id() {
        return AnthropicMessagesDialects.DEEPSEEK;
    }

    @Override
    public String version() {
        return "2026-08-31";
    }

    @Override
    public void validateSnapshot(ResolvedModelSnapshot snapshot, boolean allowInsecureHttp) {
        Objects.requireNonNull(snapshot, "snapshot must not be null");
        URI endpoint = snapshot.endpoint();
        AnthropicMessagesDialectSupport.validateEndpoint(endpoint, allowInsecureHttp);
        boolean loopback = AnthropicMessagesDialectSupport.isLoopback(endpoint);
        String host = endpoint.getHost();
        if (!loopback
                && (!"https".equalsIgnoreCase(endpoint.getScheme())
                        || !"api.deepseek.com".equalsIgnoreCase(host)
                        || !"/anthropic".equals(AnthropicMessagesDialectSupport.normalizedPath(endpoint)))) {
            throw new IllegalArgumentException(
                    "DeepSeek Anthropic endpoint must be https://api.deepseek.com/anthropic");
        }
        if (!AnthropicMessagesBindingRegistry.isAdmitted(
                snapshot.providerId().value(),
                snapshot.providerModelId(),
                ModelApiStyles.ANTHROPIC_MESSAGES,
                AnthropicMessagesDialects.DEEPSEEK)) {
            throw new IllegalArgumentException("DeepSeek Anthropic model profile is not verified");
        }
    }

    @Override
    public void validateRequest(AgentChatRequest request) {
        if (request.structuredOutput().isPresent()) {
            throw new IllegalArgumentException("DeepSeek Anthropic Messages structured output is not verified");
        }
    }

    @Override
    public void configureThinking(Map<String, Object> body, Map<String, Object> options) {
        Object configured = options.get("thinking");
        if (configured == null) return;
        String mode = String.valueOf(configured);
        if ("disabled".equals(mode)) {
            if (options.containsKey("reasoning_token_budget") || options.containsKey("reasoning_effort")) {
                throw new IllegalArgumentException("disabled thinking cannot configure budget or effort");
            }
            body.put("thinking", Map.of("type", "disabled"));
            return;
        }
        if (!"enabled".equals(mode)) throw new IllegalArgumentException("Anthropic thinking mode is unsupported");
        Map<String, Object> thinking = new LinkedHashMap<>();
        thinking.put("type", "enabled");
        Object budget = options.get("reasoning_token_budget");
        if (budget != null) {
            StandardAnthropicMessagesDialect.positiveLong(budget, "reasoning_token_budget");
        }
        body.put("thinking", Map.copyOf(thinking));
        Object effort = options.get("reasoning_effort");
        if (effort != null) {
            String effortVal = String.valueOf(effort);
            if (!List.of("high", "max").contains(effortVal)) {
                throw new IllegalArgumentException("Anthropic reasoning effort is unsupported");
            }
            body.put("output_config", Map.of("effort", effortVal));
        }
        StandardAnthropicMessagesDialect.validateToolChoice(options.get("tool_choice"));
    }

    @Override
    public void validateContentBlock(String type, JsonNode block) {
        if ("redacted_thinking".equals(type)) {
            throw new IllegalArgumentException("DeepSeek returned unsupported redacted thinking");
        }
    }
}
