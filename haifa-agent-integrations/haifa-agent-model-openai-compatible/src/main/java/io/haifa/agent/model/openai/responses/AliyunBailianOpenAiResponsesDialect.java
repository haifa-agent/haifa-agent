package io.haifa.agent.model.openai.responses;

import io.haifa.agent.model.api.ModelApiStyles;
import io.haifa.agent.model.api.ResolvedModelSnapshot;
import java.net.URI;
import java.util.Locale;
import java.util.Objects;

final class AliyunBailianOpenAiResponsesDialect implements OpenAiResponsesDialect {
    static final AliyunBailianOpenAiResponsesDialect INSTANCE = new AliyunBailianOpenAiResponsesDialect();

    private AliyunBailianOpenAiResponsesDialect() {}

    @Override
    public String id() {
        return OpenAiResponsesDialects.ALIYUN_BAILIAN;
    }

    @Override
    public String version() {
        return "2026-08-31";
    }

    @Override
    public void validateSnapshot(ResolvedModelSnapshot snapshot, boolean allowInsecureHttp) {
        Objects.requireNonNull(snapshot, "snapshot must not be null");
        URI endpoint = snapshot.endpoint();
        OpenAiResponsesDialectSupport.validateEndpoint(endpoint, allowInsecureHttp);
        boolean loopback = OpenAiResponsesDialectSupport.isLoopback(endpoint);
        if (!loopback) {
            String host = endpoint.getHost();
            String normalizedHost = host != null ? host.toLowerCase(Locale.ROOT) : "";
            boolean workspaceHost = normalizedHost.matches("[a-z0-9-]+\\.[a-z0-9-]+\\.maas\\.aliyuncs\\.com");
            if (!"https".equalsIgnoreCase(endpoint.getScheme())
                    || !workspaceHost
                    || !"/compatible-mode/v1".equals(OpenAiResponsesDialectSupport.normalizedPath(endpoint))) {
                throw new IllegalArgumentException("Bailian Responses endpoint must be workspace scoped");
            }
        }
        if (!OpenAiResponsesBindingRegistry.isAdmitted(
                snapshot.providerId().value(),
                snapshot.providerModelId(),
                ModelApiStyles.OPENAI_RESPONSES,
                OpenAiResponsesDialects.ALIYUN_BAILIAN)) {
            throw new IllegalArgumentException("Bailian Responses model profile is not verified");
        }
    }
}
