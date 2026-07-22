package io.haifa.agent.model.openai;

import io.haifa.agent.model.api.AgentChatRequest;
import io.haifa.agent.model.api.ModelProviderDefinition;
import io.haifa.agent.model.api.ResolvedModelSnapshot;
import java.util.Map;
import java.util.Set;

final class DeepSeekOpenAiChatDialect implements OpenAiCompatibleDialect {
    static final DeepSeekOpenAiChatDialect INSTANCE = new DeepSeekOpenAiChatDialect();

    private DeepSeekOpenAiChatDialect() {}

    @Override
    public String id() {
        return OpenAiCompatibleDialects.DEEPSEEK;
    }

    @Override
    public String version() {
        return OpenAiCompatibleDialects.VERSION_1;
    }

    @Override
    public void validateProvider(ModelProviderDefinition provider, boolean allowInsecureHttp) {
        OpenAiCompatibleEndpointPolicy.validate(
                provider.endpoint(), allowInsecureHttp, Set.of("api.deepseek.com"), null);
        validateOptions(provider.options());
        provider.models().forEach(model -> validateOptions(model.options()));
    }

    @Override
    public void validateSnapshot(ResolvedModelSnapshot snapshot, boolean allowInsecureHttp) {
        OpenAiCompatibleEndpointPolicy.validate(
                snapshot.endpoint(), allowInsecureHttp, Set.of("api.deepseek.com"), null);
        validateOptions(snapshot.providerOptions());
        validateOptions(snapshot.invocationOptions());
    }

    @Override
    public void applyRequest(AgentChatRequest request, Map<String, Object> body) {
        String thinking = frozen(request, "thinking", "disabled");
        body.put("thinking", Map.of("type", thinking));
        if ("enabled".equals(thinking)) body.put("reasoning_effort", frozen(request, "reasoning_effort", "high"));
    }

    private static void validateOptions(Map<String, Object> options) {
        Object thinking = options.get("thinking");
        if (thinking != null && !"disabled".equals(thinking) && !"enabled".equals(thinking)) {
            throw new IllegalArgumentException("unsupported thinking mode: " + thinking);
        }
        Object effort = options.get("reasoning_effort");
        if (effort != null && !"high".equals(effort) && !"max".equals(effort)) {
            throw new IllegalArgumentException("DeepSeek supports reasoning_effort high or max");
        }
        if ("disabled".equals(thinking) && effort != null) {
            throw new IllegalArgumentException("disabled thinking cannot have reasoning_effort");
        }
    }

    private static String frozen(AgentChatRequest request, String key, String fallback) {
        return String.valueOf(request.model()
                .invocationOptions()
                .getOrDefault(key, request.model().providerOptions().getOrDefault(key, fallback)));
    }
}
