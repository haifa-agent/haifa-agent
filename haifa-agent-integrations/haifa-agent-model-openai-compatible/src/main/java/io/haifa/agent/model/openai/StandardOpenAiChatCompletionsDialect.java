package io.haifa.agent.model.openai;

import io.haifa.agent.model.api.AgentChatRequest;
import io.haifa.agent.model.api.ModelProviderDefinition;
import io.haifa.agent.model.api.ResolvedModelSnapshot;
import java.util.Map;
import java.util.Set;

/** Standard OpenAI Chat Completions semantics without vendor-specific request extensions. */
final class StandardOpenAiChatCompletionsDialect implements OpenAiCompatibleDialect {
    static final StandardOpenAiChatCompletionsDialect INSTANCE = new StandardOpenAiChatCompletionsDialect();

    private StandardOpenAiChatCompletionsDialect() {}

    @Override
    public String id() {
        return OpenAiCompatibleDialects.OPENAI_CHAT_COMPLETIONS;
    }

    @Override
    public String version() {
        return OpenAiCompatibleDialects.VERSION_1;
    }

    @Override
    public void validateProvider(ModelProviderDefinition provider, boolean allowInsecureHttp) {
        OpenAiCompatibleEndpointPolicy.validate(
                provider.endpoint(), allowInsecureHttp, Set.of("api.openai.com"), "/v1");
        validateOptions(provider.options());
        provider.models().forEach(model -> validateOptions(model.options()));
    }

    @Override
    public void validateSnapshot(ResolvedModelSnapshot snapshot, boolean allowInsecureHttp) {
        OpenAiCompatibleEndpointPolicy.validate(
                snapshot.endpoint(), allowInsecureHttp, Set.of("api.openai.com"), "/v1");
        validateOptions(snapshot.providerOptions());
        validateOptions(snapshot.invocationOptions());
    }

    @Override
    public void applyRequest(AgentChatRequest request, Map<String, Object> body) {
        // The shared transport already emits the standard Chat Completions fields.
    }

    private static void validateOptions(Map<String, Object> options) {
        Object nativeStreaming = options.get(OpenAiCompatibleDialects.NATIVE_STREAMING);
        if (nativeStreaming != null && !(nativeStreaming instanceof Boolean)) {
            throw new IllegalArgumentException("native_streaming must be boolean");
        }
    }
}
