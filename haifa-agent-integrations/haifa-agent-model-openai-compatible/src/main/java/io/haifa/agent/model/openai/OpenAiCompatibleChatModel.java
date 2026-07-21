package io.haifa.agent.model.openai;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.haifa.agent.core.tool.ProviderToolCallCorrelationId;
import io.haifa.agent.model.api.AgentChatModel;
import io.haifa.agent.model.api.AgentChatRequest;
import io.haifa.agent.model.api.AgentChatResponse;
import io.haifa.agent.model.api.CredentialResolver;
import io.haifa.agent.model.api.ModelErrorCategory;
import io.haifa.agent.model.api.ModelFinishReason;
import io.haifa.agent.model.api.ModelInvocationException;
import io.haifa.agent.model.api.ModelMessage;
import io.haifa.agent.model.api.ModelProviderDefinition;
import io.haifa.agent.model.api.ModelToolCall;
import io.haifa.agent.model.api.ModelToolSpecification;
import io.haifa.agent.model.api.ModelUsage;
import io.haifa.agent.model.api.ResolvedCredential;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/** Synchronous bounded OpenAI Chat Completions adapter. */
public final class OpenAiCompatibleChatModel implements AgentChatModel {
    private static final int DEFAULT_MAX_RESPONSE_BYTES = 4 * 1024 * 1024;
    private final String adapterType;
    private final String adapterVersion;
    private final HttpClient http;
    private final ObjectMapper json;
    private final CredentialResolver credentials;
    private final boolean allowInsecureHttp;
    private final int maxResponseBytes;

    public OpenAiCompatibleChatModel(
            ModelProviderDefinition provider, HttpClient http, ObjectMapper json, CredentialResolver credentials) {
        this(provider, http, json, credentials, false, DEFAULT_MAX_RESPONSE_BYTES);
    }

    public OpenAiCompatibleChatModel(
            ModelProviderDefinition provider,
            HttpClient http,
            ObjectMapper json,
            CredentialResolver credentials,
            boolean allowInsecureHttp,
            int maxResponseBytes) {
        this(requireAdapterType(provider), "1.0.0", http, json, credentials, allowInsecureHttp, maxResponseBytes);
        validateProviderDefinition(provider, allowInsecureHttp);
    }

    public OpenAiCompatibleChatModel(
            String adapterType,
            String adapterVersion,
            HttpClient http,
            ObjectMapper json,
            CredentialResolver credentials,
            boolean allowInsecureHttp,
            int maxResponseBytes) {
        this.adapterType = requireText(adapterType, "adapterType");
        this.adapterVersion = requireText(adapterVersion, "adapterVersion");
        this.http = Objects.requireNonNull(http, "http must not be null");
        this.json = Objects.requireNonNull(json, "json must not be null");
        this.credentials = Objects.requireNonNull(credentials, "credentials must not be null");
        this.allowInsecureHttp = allowInsecureHttp;
        if (maxResponseBytes < 1) throw new IllegalArgumentException("maxResponseBytes must be positive");
        this.maxResponseBytes = maxResponseBytes;
    }

    @Override
    public AgentChatResponse invoke(AgentChatRequest request) {
        Objects.requireNonNull(request, "request must not be null");
        validateSelection(request);
        ResolvedCredential credential;
        try {
            credential = credentials.resolve(request.model().credentialRef());
        } catch (RuntimeException exception) {
            throw failure(
                    request,
                    ModelErrorCategory.AUTHENTICATION_FAILED,
                    false,
                    0,
                    "credential_unavailable",
                    "model credential is unavailable",
                    null);
        }
        byte[] body;
        try {
            body = json.writeValueAsBytes(requestBody(request));
        } catch (JsonProcessingException | IllegalArgumentException exception) {
            throw failure(
                    request,
                    ModelErrorCategory.INVALID_REQUEST,
                    false,
                    0,
                    "request_serialization_failed",
                    "model request cannot be serialized",
                    exception);
        }
        HttpRequest httpRequest = HttpRequest.newBuilder(
                        chatCompletionsUri(request.model().endpoint()))
                .timeout(request.timeout())
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .header("Authorization", "Bearer " + credential.value())
                .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                .build();
        try {
            HttpResponse<InputStream> response = http.send(httpRequest, HttpResponse.BodyHandlers.ofInputStream());
            byte[] responseBody;
            try (InputStream stream = response.body()) {
                responseBody = stream.readNBytes(maxResponseBytes + 1);
            }
            if (responseBody.length > maxResponseBytes) {
                throw failure(
                        request,
                        ModelErrorCategory.MALFORMED_RESPONSE,
                        false,
                        response.statusCode(),
                        "response_too_large",
                        "provider response exceeds the configured size limit",
                        null);
            }
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw httpFailure(request, response.statusCode(), responseBody, credential.value());
            }
            String contentType = response.headers().firstValue("Content-Type").orElse("");
            if (!contentType.toLowerCase(Locale.ROOT).contains("application/json")) {
                throw failure(
                        request,
                        ModelErrorCategory.MALFORMED_RESPONSE,
                        false,
                        response.statusCode(),
                        "unexpected_content_type",
                        "provider returned a non-JSON response",
                        null);
            }
            return parseResponse(request, responseBody);
        } catch (HttpTimeoutException exception) {
            throw failure(
                    request, ModelErrorCategory.TIMEOUT, true, 0, "timeout", "model request timed out", exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw failure(
                    request,
                    ModelErrorCategory.CANCELLED,
                    false,
                    0,
                    "interrupted",
                    "model request was cancelled",
                    exception);
        } catch (ModelInvocationException exception) {
            throw exception;
        } catch (IOException exception) {
            throw failure(
                    request,
                    ModelErrorCategory.PROVIDER_UNAVAILABLE,
                    true,
                    0,
                    "io_failure",
                    "model provider is unavailable",
                    exception);
        }
    }

    private void validateSelection(AgentChatRequest request) {
        if (!adapterType.equals(request.model().adapterType())
                || !adapterVersion.equals(request.model().adapterVersion())) {
            throw failure(
                    request,
                    ModelErrorCategory.INVALID_REQUEST,
                    false,
                    0,
                    "adapter_snapshot_mismatch",
                    "frozen model snapshot requires an unavailable adapter binding",
                    null);
        }
        URI endpoint = request.model().endpoint();
        if (!"https".equalsIgnoreCase(endpoint.getScheme())
                && !(allowInsecureHttp && "http".equalsIgnoreCase(endpoint.getScheme()))) {
            throw new IllegalArgumentException("frozen provider endpoint must use HTTPS");
        }
        Object providerThinking = request.model().providerOptions().get("thinking");
        Object modelThinking = request.model().invocationOptions().get("thinking");
        if ((providerThinking != null && !"disabled".equals(providerThinking))
                || (modelThinking != null && !"disabled".equals(modelThinking))) {
            throw new IllegalArgumentException("first integration requires thinking=disabled");
        }
    }

    private URI chatCompletionsUri(URI endpoint) {
        String base = endpoint.toString();
        while (base.endsWith("/")) base = base.substring(0, base.length() - 1);
        return URI.create(base + "/chat/completions");
    }

    private Map<String, Object> requestBody(AgentChatRequest request) {
        LinkedHashMap<String, Object> body = new LinkedHashMap<>();
        body.put("model", request.model().providerModelId());
        body.put("messages", request.messages().stream().map(this::message).toList());
        if (!request.tools().isEmpty()) {
            body.put("tools", request.tools().stream().map(this::tool).toList());
            body.put("tool_choice", "auto");
        }
        body.put("max_tokens", request.maxOutputTokens());
        body.put("thinking", Map.of("type", frozenThinking(request)));
        body.put("stream", false);
        Object responseFormat = request.options().get("response_format");
        if (responseFormat != null) body.put("response_format", validateResponseFormat(responseFormat));
        if (request.options().keySet().stream().anyMatch(key -> !key.equals("response_format"))) {
            throw new IllegalArgumentException("unsupported model invocation option");
        }
        return body;
    }

    private String frozenThinking(AgentChatRequest request) {
        Object value = request.model()
                .invocationOptions()
                .getOrDefault("thinking", request.model().providerOptions().getOrDefault("thinking", "disabled"));
        if (!"disabled".equals(value))
            throw new IllegalArgumentException("first integration requires thinking=disabled");
        return "disabled";
    }

    private Object validateResponseFormat(Object value) {
        if (!(value instanceof Map<?, ?> map) || !"json_object".equals(map.get("type")) || map.size() != 1) {
            throw new IllegalArgumentException("response_format must be {type=json_object}");
        }
        return Map.of("type", "json_object");
    }

    private static String requireAdapterType(ModelProviderDefinition provider) {
        return Objects.requireNonNull(provider, "provider must not be null").adapterType();
    }

    private static void validateProviderDefinition(ModelProviderDefinition provider, boolean allowInsecureHttp) {
        if (!"https".equalsIgnoreCase(provider.endpoint().getScheme())
                && !(allowInsecureHttp
                        && "http".equalsIgnoreCase(provider.endpoint().getScheme()))) {
            throw new IllegalArgumentException("provider endpoint must use HTTPS");
        }
        Object providerThinking = provider.options().get("thinking");
        if (providerThinking != null && !"disabled".equals(providerThinking)) {
            throw new IllegalArgumentException("first integration requires thinking=disabled");
        }
        provider.models().forEach(model -> {
            Object modelThinking = model.options().get("thinking");
            if (modelThinking != null && !"disabled".equals(modelThinking)) {
                throw new IllegalArgumentException("first integration requires thinking=disabled");
            }
        });
    }

    private static String requireText(String value, String field) {
        String normalized =
                Objects.requireNonNull(value, field + " must not be null").trim();
        if (normalized.isEmpty()) throw new IllegalArgumentException(field + " must not be blank");
        return normalized;
    }

    private Map<String, Object> message(ModelMessage message) {
        LinkedHashMap<String, Object> mapped = new LinkedHashMap<>();
        mapped.put("role", message.role().name().toLowerCase(Locale.ROOT));
        mapped.put("content", message.content());
        if (!message.toolCalls().isEmpty()) {
            mapped.put(
                    "tool_calls",
                    message.toolCalls().stream().map(this::toolCall).toList());
        }
        message.providerCorrelationId().ifPresent(value -> mapped.put("tool_call_id", value.value()));
        return mapped;
    }

    private Map<String, Object> toolCall(ModelToolCall call) {
        try {
            return Map.of(
                    "id", call.providerCorrelationId().value(),
                    "type", "function",
                    "function", Map.of("name", call.name(), "arguments", json.writeValueAsString(call.arguments())));
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("tool arguments cannot be serialized", exception);
        }
    }

    private Map<String, Object> tool(ModelToolSpecification specification) {
        LinkedHashMap<String, Object> function = new LinkedHashMap<>();
        function.put("name", specification.name());
        function.put("description", specification.description());
        function.put("parameters", specification.inputJsonSchema());
        function.put("strict", specification.strict());
        return Map.of("type", "function", "function", function);
    }

    private AgentChatResponse parseResponse(AgentChatRequest request, byte[] responseBody) {
        JsonNode root;
        try {
            root = json.readTree(responseBody);
        } catch (IOException exception) {
            throw failure(
                    request,
                    ModelErrorCategory.MALFORMED_RESPONSE,
                    false,
                    200,
                    "invalid_json",
                    "provider returned invalid JSON",
                    exception);
        }
        JsonNode choices = root.path("choices");
        if (!choices.isArray() || choices.size() != 1) {
            throw failure(
                    request,
                    ModelErrorCategory.MALFORMED_RESPONSE,
                    false,
                    200,
                    "invalid_choices",
                    "provider response must contain exactly one choice",
                    null);
        }
        JsonNode choice = choices.get(0);
        ModelFinishReason finishReason =
                finishReason(choice.path("finish_reason").asText(""));
        if (finishReason == ModelFinishReason.CONTENT_FILTER) {
            throw failure(
                    request,
                    ModelErrorCategory.CONTENT_REJECTED,
                    false,
                    200,
                    "content_filter",
                    "provider rejected the generated content",
                    null);
        }
        if (finishReason == ModelFinishReason.INSUFFICIENT_SYSTEM_RESOURCE) {
            throw failure(
                    request,
                    ModelErrorCategory.PROVIDER_UNAVAILABLE,
                    true,
                    200,
                    "insufficient_system_resource",
                    "provider lacks inference capacity",
                    null);
        }
        JsonNode responseMessage = choice.path("message");
        if (!responseMessage.isObject()) {
            throw failure(
                    request,
                    ModelErrorCategory.MALFORMED_RESPONSE,
                    false,
                    200,
                    "missing_message",
                    "provider response is missing a message",
                    null);
        }
        String content = responseMessage.path("content").isNull()
                ? ""
                : responseMessage.path("content").asText("");
        List<ModelToolCall> toolCalls = parseToolCalls(request, responseMessage.path("tool_calls"));
        if (content.isBlank() && toolCalls.isEmpty()) {
            throw failure(
                    request,
                    ModelErrorCategory.MALFORMED_RESPONSE,
                    false,
                    200,
                    "empty_message",
                    "provider response message is empty",
                    null);
        }
        ModelUsage usage = parseUsage(request, root.path("usage"));
        return new AgentChatResponse(
                requiredText(request, root, "id"),
                requiredText(request, root, "model"),
                content,
                toolCalls,
                finishReason,
                usage,
                root.path("system_fingerprint").asText(""),
                Map.of());
    }

    private List<ModelToolCall> parseToolCalls(AgentChatRequest request, JsonNode node) {
        if (node.isMissingNode() || node.isNull()) return List.of();
        if (!node.isArray()) {
            throw failure(
                    request,
                    ModelErrorCategory.MALFORMED_RESPONSE,
                    false,
                    200,
                    "invalid_tool_calls",
                    "provider tool_calls is not an array",
                    null);
        }
        List<ModelToolCall> calls = new ArrayList<>();
        for (JsonNode call : node) {
            String id = requiredText(request, call, "id");
            if (!"function".equals(call.path("type").asText())) {
                throw failure(
                        request,
                        ModelErrorCategory.MALFORMED_RESPONSE,
                        false,
                        200,
                        "unsupported_tool_type",
                        "provider returned an unsupported tool type",
                        null);
            }
            JsonNode function = call.path("function");
            String name = requiredText(request, function, "name");
            String arguments = requiredText(request, function, "arguments");
            try {
                JsonNode parsed = json.readTree(arguments);
                if (!parsed.isObject()) throw new JsonProcessingException("tool arguments must be an object") {};
                @SuppressWarnings("unchecked")
                Map<String, Object> values = json.convertValue(parsed, LinkedHashMap.class);
                calls.add(new ModelToolCall(new ProviderToolCallCorrelationId(id), name, values));
            } catch (JsonProcessingException | IllegalArgumentException exception) {
                throw failure(
                        request,
                        ModelErrorCategory.MALFORMED_RESPONSE,
                        false,
                        200,
                        "invalid_tool_arguments",
                        "provider returned invalid tool arguments",
                        exception);
            }
        }
        return List.copyOf(calls);
    }

    private ModelUsage parseUsage(AgentChatRequest request, JsonNode usage) {
        if (!usage.isObject()) {
            throw failure(
                    request,
                    ModelErrorCategory.MALFORMED_RESPONSE,
                    false,
                    200,
                    "missing_usage",
                    "provider response is missing token usage",
                    null);
        }
        return new ModelUsage(
                nonNegativeLong(request, usage, "prompt_tokens", true),
                nonNegativeLong(request, usage, "completion_tokens", true),
                nonNegativeLong(request, usage, "prompt_cache_hit_tokens", false),
                nonNegativeLong(request, usage, "prompt_cache_miss_tokens", false),
                nonNegativeLong(request, usage.path("completion_tokens_details"), "reasoning_tokens", false),
                false,
                0);
    }

    private long nonNegativeLong(AgentChatRequest request, JsonNode object, String field, boolean required) {
        JsonNode value = object.path(field);
        if (value.isMissingNode() && !required) return 0;
        if (!value.canConvertToLong() || value.asLong() < 0) {
            throw failure(
                    request,
                    ModelErrorCategory.MALFORMED_RESPONSE,
                    false,
                    200,
                    "invalid_usage",
                    "provider returned invalid token usage",
                    null);
        }
        return value.asLong();
    }

    private String requiredText(AgentChatRequest request, JsonNode node, String field) {
        String value = node.path(field).asText("").trim();
        if (value.isEmpty()) {
            throw failure(
                    request,
                    ModelErrorCategory.MALFORMED_RESPONSE,
                    false,
                    200,
                    "missing_" + field,
                    "provider response is missing " + field,
                    null);
        }
        return value;
    }

    private ModelFinishReason finishReason(String value) {
        return switch (value) {
            case "stop" -> ModelFinishReason.STOP;
            case "length" -> ModelFinishReason.LENGTH;
            case "content_filter" -> ModelFinishReason.CONTENT_FILTER;
            case "tool_calls" -> ModelFinishReason.TOOL_CALLS;
            case "insufficient_system_resource" -> ModelFinishReason.INSUFFICIENT_SYSTEM_RESOURCE;
            default -> ModelFinishReason.UNKNOWN;
        };
    }

    private ModelInvocationException httpFailure(AgentChatRequest request, int status, byte[] body, String credential) {
        String providerCode = "http_" + status;
        String safeDetail = "";
        try {
            JsonNode error = json.readTree(body).path("error");
            providerCode = truncate(error.path("code").asText(providerCode), 80).replace(credential, "[REDACTED]");
            safeDetail = truncate(error.path("message").asText(""), 240).replace(credential, "[REDACTED]");
        } catch (IOException ignored) {
            // The raw response is intentionally not propagated.
        }
        ModelErrorCategory category =
                switch (status) {
                    case 400 ->
                        providerCode.toLowerCase(Locale.ROOT).contains("context")
                                        || safeDetail.toLowerCase(Locale.ROOT).contains("context")
                                ? ModelErrorCategory.CONTEXT_TOO_LONG
                                : ModelErrorCategory.INVALID_REQUEST;
                    case 401 -> ModelErrorCategory.AUTHENTICATION_FAILED;
                    case 403 -> ModelErrorCategory.PERMISSION_DENIED;
                    case 404 -> ModelErrorCategory.MODEL_NOT_FOUND;
                    case 408 -> ModelErrorCategory.TIMEOUT;
                    case 429 -> ModelErrorCategory.RATE_LIMITED;
                    default ->
                        status >= 500
                                ? ModelErrorCategory.PROVIDER_UNAVAILABLE
                                : ModelErrorCategory.UNKNOWN_PROVIDER_ERROR;
                };
        boolean retryable = status == 408 || status == 429 || status >= 500;
        return failure(
                request,
                category,
                retryable,
                status,
                providerCode,
                "model provider request failed with HTTP " + status,
                null);
    }

    private static String truncate(String value, int max) {
        if (value == null) return "";
        String normalized = value.replaceAll("[\\r\\n\\t]+", " ").trim();
        return normalized.length() <= max ? normalized : normalized.substring(0, max);
    }

    private ModelInvocationException failure(
            AgentChatRequest request,
            ModelErrorCategory category,
            boolean retryable,
            int status,
            String code,
            String message,
            Throwable cause) {
        return new ModelInvocationException(category, retryable, status, code, request.callId(), message, cause);
    }
}
