package io.haifa.agent.model.openai.responses;

import com.fasterxml.jackson.databind.JsonNode;
import io.haifa.agent.model.api.ModelApiBindingDefinition;
import io.haifa.agent.model.api.ModelHttpErrorClassifier;
import io.haifa.agent.model.api.ResolvedModelSnapshot;
import java.net.http.HttpHeaders;
import java.util.Objects;

final class StandardOpenAiResponsesDialect implements OpenAiResponsesDialect {
    static final StandardOpenAiResponsesDialect INSTANCE = new StandardOpenAiResponsesDialect();

    private StandardOpenAiResponsesDialect() {}

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
        OpenAiResponsesDialectSupport.validateEndpoint(snapshot.endpoint(), allowInsecureHttp);
    }

    @Override
    public DialectErrorMapping classifyError(int statusCode, HttpHeaders headers, byte[] body, JsonNode errorRoot) {
        var mapping = ModelHttpErrorClassifier.classify(statusCode, headers, body, null);
        return DialectErrorMapping.from(mapping);
    }
}
