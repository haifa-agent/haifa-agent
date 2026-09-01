package io.haifa.agent.model.gemini;

import io.haifa.agent.model.api.AgentChatRequest;
import io.haifa.agent.model.api.ModelApiBindingDefinition;
import io.haifa.agent.model.api.ResolvedCredential;
import io.haifa.agent.model.api.ResolvedModelSnapshot;
import java.net.URI;
import java.net.http.HttpRequest;
import java.util.Objects;

final class StandardGeminiDialect implements GeminiDialect {
    static final StandardGeminiDialect INSTANCE = new StandardGeminiDialect();

    private StandardGeminiDialect() {}

    @Override
    public String id() {
        return ModelApiBindingDefinition.STANDARD_DIALECT;
    }

    @Override
    public String version() {
        return "2026-08-31";
    }

    @Override
    public void validateSnapshot(
            ResolvedModelSnapshot snapshot, boolean allowInsecureLoopback, boolean allowStandardLoopbackStub) {
        Objects.requireNonNull(snapshot, "snapshot must not be null");
        URI endpoint = snapshot.endpoint();
        GeminiDialectSupport.validateEndpoint(endpoint, allowInsecureLoopback);
        if (!allowStandardLoopbackStub && !GeminiDialectSupport.isGovernedGoogleEndpoint(endpoint)) {
            throw new DialectValidationException(
                    "invalid_standard_endpoint", "official Gemini binding requires the governed Google HTTPS endpoint");
        }
    }

    @Override
    public HttpRequest.Builder requestBuilder(
            AgentChatRequest request, ResolvedCredential credential, boolean streaming) {
        String secret = GeminiDialectSupport.validateSecret(credential.value());
        URI uri = GeminiDialectSupport.standardRequestUri(
                request.model().endpoint(), request.model().providerModelId(), streaming);
        return HttpRequest.newBuilder(uri)
                .timeout(request.timeout())
                .header("Content-Type", "application/json")
                .header("x-goog-api-key", secret);
    }
}
