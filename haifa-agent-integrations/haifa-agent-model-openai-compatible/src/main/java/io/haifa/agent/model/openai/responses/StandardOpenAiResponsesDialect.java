package io.haifa.agent.model.openai.responses;

import com.fasterxml.jackson.databind.JsonNode;
import io.haifa.agent.model.api.ModelApiBindingDefinition;
import io.haifa.agent.model.api.ModelErrorCategory;
import io.haifa.agent.model.api.ResolvedModelSnapshot;
import io.haifa.agent.model.openai.RetryAfterParser;
import java.net.http.HttpHeaders;
import java.time.Instant;
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
        ModelErrorCategory category =
                switch (statusCode) {
                    case 400, 422 -> ModelErrorCategory.INVALID_REQUEST;
                    case 401 -> ModelErrorCategory.AUTHENTICATION_FAILED;
                    case 403 -> ModelErrorCategory.PERMISSION_DENIED;
                    case 404 -> ModelErrorCategory.MODEL_NOT_FOUND;
                    case 408 -> ModelErrorCategory.TIMEOUT;
                    case 429 -> ModelErrorCategory.RATE_LIMITED;
                    case 500, 502, 503, 504 -> ModelErrorCategory.SERVER_ERROR;
                    default -> ModelErrorCategory.UNKNOWN_PROVIDER_ERROR;
                };
        String providerCode = "http_" + statusCode;
        String safeMessage = "model provider rejected the request";
        boolean retryable = statusCode == 408 || statusCode == 429 || statusCode >= 500;
        var retryAfter = RetryAfterParser.parse(headers, Instant.now());
        return new DialectErrorMapping(category, retryable, providerCode, safeMessage, retryAfter);
    }
}
