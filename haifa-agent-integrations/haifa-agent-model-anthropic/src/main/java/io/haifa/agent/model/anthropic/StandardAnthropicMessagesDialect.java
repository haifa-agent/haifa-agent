package io.haifa.agent.model.anthropic;

import com.fasterxml.jackson.databind.JsonNode;
import io.haifa.agent.model.api.ModelApiBindingDefinition;
import io.haifa.agent.model.api.ResolvedModelSnapshot;
import java.net.http.HttpHeaders;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

final class StandardAnthropicMessagesDialect implements AnthropicMessagesDialect {
    static final StandardAnthropicMessagesDialect INSTANCE = new StandardAnthropicMessagesDialect();

    private StandardAnthropicMessagesDialect() {}

    @Override
    public String id() {
        return ModelApiBindingDefinition.STANDARD_DIALECT;
    }

    @Override
    public String version() {
        return "2026-08-31";
    }

    @Override
    public void validateSnapshot(ResolvedModelSnapshot snapshot, boolean allowInsecureHttp) {
        Objects.requireNonNull(snapshot, "snapshot must not be null");
        AnthropicMessagesDialectSupport.validateEndpoint(snapshot.endpoint(), allowInsecureHttp);
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
        long value = positiveLong(budget, "reasoning_token_budget");
        thinking.put("budget_tokens", value);
        body.put("thinking", Map.copyOf(thinking));
        Object effort = options.get("reasoning_effort");
        if (effort != null) {
            String effortVal = String.valueOf(effort);
            if (!List.of("low", "medium", "high", "max").contains(effortVal)) {
                throw new IllegalArgumentException("Anthropic reasoning effort is unsupported");
            }
            body.put("output_config", Map.of("effort", effortVal));
        }
        validateToolChoice(options.get("tool_choice"));
    }

    static void validateToolChoice(Object choice) {
        if (choice != null) {
            String type = String.valueOf(choice);
            if (choice instanceof Map<?, ?> map && map.containsKey("type")) {
                type = String.valueOf(map.get("type"));
            }
            if (!"auto".equals(type) && !"none".equals(type)) {
                throw new IllegalArgumentException("forced Anthropic tool choice is incompatible with thinking");
            }
        }
    }

    static long positiveLong(Object value, String field) {
        if (!(value instanceof Number number) || number.longValue() < 1) {
            throw new IllegalArgumentException(field + " must be positive");
        }
        return number.longValue();
    }

    @Override
    public DialectErrorMapping classifyError(int statusCode, HttpHeaders headers, byte[] body, JsonNode errorRoot) {
        var mapping = io.haifa.agent.model.api.ModelHttpErrorClassifier.classify(statusCode, headers, body, null);
        return DialectErrorMapping.from(mapping);
    }
}
