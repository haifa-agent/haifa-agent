package io.haifa.agent.model.openai;

import io.haifa.agent.model.api.AgentChatRequest;
import io.haifa.agent.model.api.ModelProviderDefinition;
import io.haifa.agent.model.api.ResolvedModelSnapshot;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** SiliconFlow Chat Completions semantics verified against its cumulative streaming usage behavior. */
final class SiliconFlowOpenAiChatDialect implements OpenAiCompatibleDialect {
    static final SiliconFlowOpenAiChatDialect INSTANCE = new SiliconFlowOpenAiChatDialect();
    private static final Set<String> OFFICIAL_HOSTS = Set.of("api.siliconflow.cn");

    private SiliconFlowOpenAiChatDialect() {}

    @Override
    public String id() {
        return OpenAiCompatibleDialects.SILICONFLOW;
    }

    @Override
    public String version() {
        return OpenAiCompatibleDialects.VERSION_1;
    }

    @Override
    public void validateProvider(ModelProviderDefinition provider, boolean allowInsecureHttp) {
        OpenAiCompatibleEndpointPolicy.validate(provider.endpoint(), allowInsecureHttp, OFFICIAL_HOSTS, "/v1");
        validateFrozenHost(provider.endpoint().getHost(), provider.options());
        provider.models().forEach(model -> validateOptions(model.options()));
    }

    @Override
    public void validateSnapshot(ResolvedModelSnapshot snapshot, boolean allowInsecureHttp) {
        OpenAiCompatibleEndpointPolicy.validate(snapshot.endpoint(), allowInsecureHttp, OFFICIAL_HOSTS, "/v1");
        validateFrozenHost(snapshot.endpoint().getHost(), snapshot.providerOptions());
        validateOptions(snapshot.invocationOptions());
    }

    @Override
    public void applyRequest(AgentChatRequest request, Map<String, Object> body) {
        // The shared transport emits the verified OpenAI-compatible request fields.
    }

    @Override
    public StreamUsageMode streamUsageMode() {
        return StreamUsageMode.MONOTONIC_CUMULATIVE;
    }

    private static void validateFrozenHost(String endpointHost, Map<String, Object> options) {
        Object configured = options.get(OpenAiCompatibleDialects.ENDPOINT_HOST);
        if (configured == null) return;
        if (!(configured instanceof String host)
                || host.isBlank()
                || endpointHost == null
                || !host.trim().equalsIgnoreCase(endpointHost)) {
            throw new IllegalArgumentException("SiliconFlow endpoint_host does not match the frozen endpoint");
        }
    }

    private static void validateOptions(Map<String, Object> options) {
        Object endpointHost = options.get(OpenAiCompatibleDialects.ENDPOINT_HOST);
        if (endpointHost != null
                && (!(endpointHost instanceof String host)
                        || host.isBlank()
                        || !OFFICIAL_HOSTS.contains(host.trim().toLowerCase(Locale.ROOT)))) {
            throw new IllegalArgumentException("SiliconFlow endpoint_host is not allowed");
        }
    }
}
