package io.haifa.agent.model.openai;

import io.haifa.agent.model.api.AgentChatRequest;
import io.haifa.agent.model.api.ModelErrorCategory;
import io.haifa.agent.model.api.ModelProviderDefinition;
import io.haifa.agent.model.api.ResolvedModelSnapshot;
import java.util.Map;

/** Provider-specific semantics layered over the shared OpenAI Chat transport. */
public interface OpenAiCompatibleDialect {
    String id();

    String version();

    void validateProvider(ModelProviderDefinition provider, boolean allowInsecureHttp);

    void validateSnapshot(ResolvedModelSnapshot snapshot, boolean allowInsecureHttp);

    void applyRequest(AgentChatRequest request, Map<String, Object> body);

    default boolean acceptsResponseObject(String object, boolean streaming) {
        return true;
    }

    default ModelErrorCategory classifyError(int status, String providerCode, String safeDetail) {
        String normalized = (providerCode + " " + safeDetail).toLowerCase(java.util.Locale.ROOT);
        return switch (status) {
            case 400 ->
                normalized.contains("context") || normalized.contains("length") || normalized.contains("token limit")
                        ? ModelErrorCategory.CONTEXT_TOO_LONG
                        : ModelErrorCategory.INVALID_REQUEST;
            case 401 -> ModelErrorCategory.AUTHENTICATION_FAILED;
            case 403 -> ModelErrorCategory.PERMISSION_DENIED;
            case 404 -> ModelErrorCategory.MODEL_NOT_FOUND;
            case 408 -> ModelErrorCategory.TIMEOUT;
            case 429 -> ModelErrorCategory.RATE_LIMITED;
            default ->
                status >= 500 ? ModelErrorCategory.PROVIDER_UNAVAILABLE : ModelErrorCategory.UNKNOWN_PROVIDER_ERROR;
        };
    }

    default ModelErrorCategory classifyStreamError(String providerCode) {
        return classifyError(200, providerCode, "");
    }

    default boolean retryable(int status, ModelErrorCategory category, String providerCode) {
        return category == ModelErrorCategory.TIMEOUT
                || category == ModelErrorCategory.RATE_LIMITED
                || category == ModelErrorCategory.PROVIDER_UNAVAILABLE;
    }
}
