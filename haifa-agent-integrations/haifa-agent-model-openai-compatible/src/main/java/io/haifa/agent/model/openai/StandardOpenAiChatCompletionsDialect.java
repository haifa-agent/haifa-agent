package io.haifa.agent.model.openai;

import io.haifa.agent.model.api.AgentChatRequest;
import io.haifa.agent.model.api.ModelProviderDefinition;
import io.haifa.agent.model.api.ResolvedModelSnapshot;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Standard OpenAI Chat Completions semantics without vendor-specific request extensions. */
final class StandardOpenAiChatCompletionsDialect implements OpenAiCompatibleDialect {
    static final StandardOpenAiChatCompletionsDialect INSTANCE = new StandardOpenAiChatCompletionsDialect();

    private StandardOpenAiChatCompletionsDialect() {}

    @Override
    public String id() {
        return OpenAiCompatibleDialects.STANDARD_IMPLEMENTATION_ID;
    }

    @Override
    public String version() {
        return OpenAiCompatibleDialects.VERSION_1;
    }

    @Override
    public void validateProvider(ModelProviderDefinition provider, boolean allowInsecureHttp) {
        OpenAiCompatibleEndpointPolicy.validate(
                provider.endpoint(), allowInsecureHttp, allowedHosts(provider.options()), "/v1");
        validateOptions(provider.options());
        provider.models().forEach(model -> validateOptions(model.options()));
    }

    @Override
    public void validateSnapshot(ResolvedModelSnapshot snapshot, boolean allowInsecureHttp) {
        OpenAiCompatibleEndpointPolicy.validate(
                snapshot.endpoint(), allowInsecureHttp, allowedHosts(snapshot.providerOptions()), "/v1");
        validateOptions(snapshot.providerOptions());
        validateOptions(snapshot.invocationOptions());
    }

    @Override
    public void applyRequest(AgentChatRequest request, Map<String, Object> body) {
        // The shared transport already emits the standard Chat Completions fields.
    }

    private static void validateOptions(Map<String, Object> options) {
        Object endpointHost = options.get(OpenAiCompatibleDialects.ENDPOINT_HOST);
        if (endpointHost != null && (!(endpointHost instanceof String host) || host.isBlank())) {
            throw new IllegalArgumentException("endpoint_host must be non-blank text");
        }
    }

    private static Set<String> allowedHosts(Map<String, Object> options) {
        Object configured = options.get(OpenAiCompatibleDialects.ENDPOINT_HOST);
        if (configured == null) return Set.of("api.openai.com");
        if (!(configured instanceof String host) || host.isBlank()) {
            throw new IllegalArgumentException("endpoint_host must be non-blank text");
        }
        return Set.of(host.trim().toLowerCase(Locale.ROOT));
    }
}
