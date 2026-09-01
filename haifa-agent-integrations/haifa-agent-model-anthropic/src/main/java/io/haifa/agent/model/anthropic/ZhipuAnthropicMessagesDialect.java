package io.haifa.agent.model.anthropic;

import io.haifa.agent.model.api.ModelApiStyles;
import io.haifa.agent.model.api.ResolvedModelSnapshot;
import java.net.URI;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

final class ZhipuAnthropicMessagesDialect implements AnthropicMessagesDialect {
    static final ZhipuAnthropicMessagesDialect INSTANCE = new ZhipuAnthropicMessagesDialect();

    private ZhipuAnthropicMessagesDialect() {}

    @Override
    public String id() {
        return AnthropicMessagesDialects.ZHIPU;
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
                        || !"open.bigmodel.cn".equalsIgnoreCase(host)
                        || !"/api/anthropic".equals(AnthropicMessagesDialectSupport.normalizedPath(endpoint)))) {
            throw new IllegalArgumentException(
                    "Zhipu Anthropic endpoint must be https://open.bigmodel.cn/api/anthropic");
        }
        if (!AnthropicMessagesBindingRegistry.isAdmitted(
                snapshot.providerId().value(),
                snapshot.providerModelId(),
                ModelApiStyles.ANTHROPIC_MESSAGES,
                AnthropicMessagesDialects.ZHIPU)) {
            throw new IllegalArgumentException("Zhipu Anthropic model profile is not verified");
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
        if ("adaptive".equals(mode)) {
            mode = "enabled";
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
}
