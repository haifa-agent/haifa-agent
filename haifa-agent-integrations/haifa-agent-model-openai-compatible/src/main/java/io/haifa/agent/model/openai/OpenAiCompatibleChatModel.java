package io.haifa.agent.model.openai;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.haifa.agent.core.tool.ProviderToolCallCorrelationId;
import io.haifa.agent.model.api.AgentChatModel;
import io.haifa.agent.model.api.AgentChatRequest;
import io.haifa.agent.model.api.AgentChatResponse;
import io.haifa.agent.model.api.CredentialResolver;
import io.haifa.agent.model.api.ImageDataPart;
import io.haifa.agent.model.api.ImageUrlPart;
import io.haifa.agent.model.api.ModelErrorCategory;
import io.haifa.agent.model.api.ModelFinishReason;
import io.haifa.agent.model.api.ModelInvocationException;
import io.haifa.agent.model.api.ModelMessage;
import io.haifa.agent.model.api.ModelMessageRole;
import io.haifa.agent.model.api.ModelProviderDefinition;
import io.haifa.agent.model.api.ModelStreamControl;
import io.haifa.agent.model.api.ModelStreamEvent;
import io.haifa.agent.model.api.ModelStreamSink;
import io.haifa.agent.model.api.ModelToolCall;
import io.haifa.agent.model.api.ModelToolSpecification;
import io.haifa.agent.model.api.ModelUsage;
import io.haifa.agent.model.api.ResolvedCredential;
import io.haifa.agent.model.api.SensitiveModelReasoning;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/** Bounded synchronous and SSE OpenAI Chat Completions adapter. */
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
        ResolvedCredential credential = resolveCredential(request);
        byte[] body;
        try {
            body = json.writeValueAsBytes(requestBody(request, false));
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
                .header("Authorization", "Bearer " + credential.value().trim())
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
                throw httpFailure(request, response.statusCode(), responseBody, credential.value(), response.headers());
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
                    ModelErrorCategory.TRANSPORT_ERROR,
                    true,
                    0,
                    "io_failure",
                    "model provider is unavailable",
                    exception);
        }
    }

    @Override
    public AgentChatResponse invokeStreaming(AgentChatRequest request, ModelStreamSink sink) {
        Objects.requireNonNull(request, "request must not be null");
        Objects.requireNonNull(sink, "sink must not be null");
        validateSelection(request);
        if (!request.model().nativeStreaming()) {
            return AgentChatModel.super.invokeStreaming(request, sink);
        }
        ResolvedCredential credential = resolveCredential(request);
        byte[] body;
        try {
            body = json.writeValueAsBytes(requestBody(request, true));
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
                .header("Accept", "text/event-stream")
                .header("Authorization", "Bearer " + credential.value().trim())
                .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                .build();
        ModelStreamObservation observation = new ModelStreamObservation();
        try {
            HttpResponse<InputStream> response = http.send(httpRequest, HttpResponse.BodyHandlers.ofInputStream());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                byte[] responseBody;
                try (InputStream stream = response.body()) {
                    responseBody = stream.readNBytes(maxResponseBytes + 1);
                }
                if (responseBody.length > maxResponseBytes) responseBody = new byte[0];
                throw httpFailure(request, response.statusCode(), responseBody, credential.value(), response.headers());
            }
            String contentType = response.headers().firstValue("Content-Type").orElse("");
            if (!contentType.toLowerCase(Locale.ROOT).contains("text/event-stream")) {
                try (InputStream ignored = response.body()) {
                    // Close the unexpected response without retaining its contents.
                }
                throw failure(
                        request,
                        ModelErrorCategory.MALFORMED_RESPONSE,
                        false,
                        response.statusCode(),
                        "unexpected_content_type",
                        "provider returned a non-SSE response",
                        null);
            }
            try (InputStream stream = response.body()) {
                return parseStream(request, stream, observation.observe(sink));
            }
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
            throw observation.annotate(exception);
        } catch (IOException exception) {
            throw failure(
                    request,
                    observation.outputObserved()
                            ? ModelErrorCategory.PARTIAL_RESPONSE
                            : ModelErrorCategory.TRANSPORT_ERROR,
                    !observation.outputObserved(),
                    0,
                    "stream_io_failure",
                    "model provider stream was interrupted",
                    exception,
                    null,
                    observation.outputObserved());
        }
    }

    private ResolvedCredential resolveCredential(AgentChatRequest request) {
        try {
            ResolvedCredential credential = credentials.resolve(request.model().credentialRef());
            validateSecret(credential.value());
            return credential;
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
    }

    private static String validateSecret(String secret) {
        if (secret == null || secret.isBlank()) {
            throw new IllegalArgumentException("model credential value must not be blank");
        }
        if (secret.indexOf('\r') >= 0 || secret.indexOf('\n') >= 0 || secret.indexOf('\0') >= 0) {
            throw new IllegalArgumentException("model credential contains invalid control characters");
        }
        return secret.trim();
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
        dialect(request).validateSnapshot(request.model(), allowInsecureHttp);
        if (request.messages().stream().anyMatch(message -> !message.audios().isEmpty())) {
            throw new IllegalArgumentException("audio input is not supported by this OpenAI-compatible adapter");
        }
        if (request.structuredOutput().isPresent()
                && !request.model()
                        .capabilities()
                        .contains(io.haifa.agent.model.api.ModelCapability.STRUCTURED_OUTPUT)) {
            throw failure(
                    request,
                    ModelErrorCategory.INVALID_REQUEST,
                    false,
                    0,
                    "structured_output_unsupported",
                    "selected model does not support structured output",
                    null);
        }
    }

    private URI chatCompletionsUri(URI endpoint) {
        String base = endpoint.toString();
        while (base.endsWith("/")) base = base.substring(0, base.length() - 1);
        return URI.create(base + "/chat/completions");
    }

    private Map<String, Object> requestBody(AgentChatRequest request, boolean stream) {
        Map<String, Object> options = invocationOptions(request);
        LinkedHashMap<String, Object> body = new LinkedHashMap<>();
        body.put("model", request.model().providerModelId());
        List<Map<String, Object>> messages =
                new ArrayList<>(request.messages().stream().map(this::message).toList());
        request.structuredOutput().ifPresent(requirement -> {
            if (!io.haifa.agent.model.api.ModelApiBindingDefinition.STANDARD_DIALECT.equals(
                    request.model().dialect())) {
                messages.add(Map.of(
                        "role",
                        dialect(request).structuredOutputInstructionRole(),
                        "content",
                        "When producing the final answer, return one JSON object that exactly matches this schema: "
                                + writeJson(requirement.jsonSchema())));
            }
        });
        body.put("messages", List.copyOf(messages));
        if (!request.tools().isEmpty()) {
            body.put("tools", request.tools().stream().map(this::tool).toList());
            body.put("tool_choice", options.getOrDefault("tool_choice", "auto"));
        }
        body.put("max_tokens", request.maxOutputTokens());
        dialect(request).applyRequest(request, body);
        body.put("stream", stream);
        if (stream) dialect(request).applyStreamingRequest(request, body);
        Object temperature = options.get("temperature");
        if (temperature != null) body.put("temperature", validateTemperature(temperature));
        if (request.structuredOutput().isPresent() && options.containsKey("response_format")) {
            throw new IllegalArgumentException("structured output cannot be combined with response_format options");
        }
        Object responseFormat = request.structuredOutput().isPresent()
                ? structuredResponseFormat(request, request.structuredOutput().orElseThrow())
                : options.get("response_format");
        if (responseFormat != null) body.put("response_format", validateResponseFormat(responseFormat));
        if (options.keySet().stream()
                .anyMatch(key ->
                        !key.equals("response_format") && !key.equals("tool_choice") && !key.equals("temperature"))) {
            throw new IllegalArgumentException("unsupported model invocation option");
        }
        return body;
    }

    private static Map<String, Object> invocationOptions(AgentChatRequest request) {
        Map<String, Object> values = new LinkedHashMap<>();
        copyOption(request.model().invocationOptions(), values, "temperature");
        copyOption(request.model().invocationOptions(), values, "tool_choice");
        copyOption(request.model().invocationOptions(), values, "response_format");
        values.putAll(request.options());
        return Map.copyOf(values);
    }

    private static void copyOption(Map<String, Object> source, Map<String, Object> target, String key) {
        if (source.containsKey(key)) target.put(key, source.get(key));
    }

    private static double validateTemperature(Object configured) {
        if (!(configured instanceof Number number)) {
            throw new IllegalArgumentException("temperature must be numeric");
        }
        double value = number.doubleValue();
        if (!Double.isFinite(value) || value < 0.0d || value > 2.0d) {
            throw new IllegalArgumentException("temperature must be between 0.0 and 2.0");
        }
        return value;
    }

    private AgentChatResponse parseStream(AgentChatRequest request, InputStream stream, ModelStreamSink sink)
            throws IOException {
        long[] eventIndex = {1};
        emit(request, sink, new ModelStreamEvent.Started(request.callId(), eventIndex[0]++));
        StreamAccumulator accumulator = new StreamAccumulator(request);
        int totalBytes = 0;
        int eventBytes = 0;
        StringBuilder data = new StringBuilder();
        boolean done = false;
        try (BufferedReader reader =
                new BufferedReader(new InputStreamReader(stream, java.nio.charset.StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                int lineBytes = line.getBytes(java.nio.charset.StandardCharsets.UTF_8).length + 1;
                totalBytes = Math.addExact(totalBytes, lineBytes);
                eventBytes = Math.addExact(eventBytes, lineBytes);
                if (totalBytes > maxResponseBytes || eventBytes > Math.min(maxResponseBytes, 1024 * 1024)) {
                    throw failure(
                            request,
                            ModelErrorCategory.MALFORMED_RESPONSE,
                            false,
                            200,
                            "stream_response_too_large",
                            "provider stream exceeds the configured size limit",
                            null);
                }
                if (line.isEmpty()) {
                    if (!data.isEmpty()) {
                        done = dispatchStreamEvent(request, data.toString(), accumulator, sink, eventIndex);
                        data.setLength(0);
                        if (done) break;
                    }
                    eventBytes = 0;
                    continue;
                }
                if (line.startsWith(":")) continue;
                if (line.startsWith("data:")) {
                    String value = line.substring(5);
                    if (value.startsWith(" ")) value = value.substring(1);
                    if (!data.isEmpty()) data.append('\n');
                    data.append(value);
                }
            }
        }
        if (!done && !data.isEmpty()) {
            done = dispatchStreamEvent(request, data.toString(), accumulator, sink, eventIndex);
        }
        if (!done) {
            throw failure(
                    request,
                    ModelErrorCategory.TRANSPORT_ERROR,
                    true,
                    200,
                    "stream_ended_without_done",
                    "provider stream ended before completion",
                    null);
        }
        return accumulator.finish(sink, eventIndex);
    }

    private boolean dispatchStreamEvent(
            AgentChatRequest request,
            String data,
            StreamAccumulator accumulator,
            ModelStreamSink sink,
            long[] eventIndex) {
        if ("[DONE]".equals(data.trim())) return true;
        JsonNode chunk;
        try {
            chunk = json.readTree(data);
        } catch (JsonProcessingException exception) {
            throw failure(
                    request,
                    ModelErrorCategory.MALFORMED_RESPONSE,
                    false,
                    200,
                    "invalid_stream_json",
                    "provider returned invalid stream JSON",
                    exception);
        }
        String object = chunk.path("object").asText("");
        if (!dialect(request).acceptsResponseObject(object, true)) {
            throw failure(
                    request,
                    ModelErrorCategory.MALFORMED_RESPONSE,
                    false,
                    200,
                    "unexpected_stream_object",
                    "provider returned an unexpected stream object",
                    null);
        }
        if (chunk.path("error").isObject()) {
            String code = truncate(chunk.path("error").path("code").asText("stream_error"), 80);
            throw failure(
                    request,
                    dialect(request).classifyStreamError(code),
                    false,
                    200,
                    code,
                    "model provider returned an error in the response stream",
                    null);
        }
        accumulator.accept(chunk, sink, eventIndex);
        return false;
    }

    private void emit(AgentChatRequest request, ModelStreamSink sink, ModelStreamEvent event) {
        if (sink.emit(event) == ModelStreamControl.CANCEL) {
            throw failure(
                    request,
                    ModelErrorCategory.CANCELLED,
                    false,
                    0,
                    "stream_cancelled",
                    "model stream was cancelled",
                    null);
        }
    }

    private Object validateResponseFormat(Object value) {
        if (!(value instanceof Map<?, ?> map)) {
            throw new IllegalArgumentException("response_format must be an object");
        }
        if ("json_object".equals(map.get("type")) && map.size() == 1) return Map.of("type", "json_object");
        if ("json_schema".equals(map.get("type")) && map.get("json_schema") instanceof Map<?, ?>) {
            return value;
        }
        throw new IllegalArgumentException("response_format type is unsupported");
    }

    private static Map<String, Object> structuredResponseFormat(
            AgentChatRequest request, io.haifa.agent.core.run.StructuredOutputRequirement requirement) {
        if (io.haifa.agent.model.api.ModelApiBindingDefinition.STANDARD_DIALECT.equals(
                request.model().dialect())) {
            return Map.of(
                    "type",
                    "json_schema",
                    "json_schema",
                    Map.of(
                            "name", requirement.responseName(),
                            "strict", true,
                            "schema", requirement.jsonSchema()));
        }
        return Map.of("type", "json_object");
    }

    private String writeJson(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("structured output schema cannot be serialized", exception);
        }
    }

    private java.util.Optional<Map<String, Object>> structuredOutput(
            AgentChatRequest request, String content, List<ModelToolCall> toolCalls) {
        if (request.structuredOutput().isEmpty() || !toolCalls.isEmpty()) return java.util.Optional.empty();
        try {
            JsonNode value = json.readTree(content);
            if (!value.isObject()) throw new IllegalArgumentException("structured output must be an object");
            return java.util.Optional.of(json.convertValue(value, new TypeReference<Map<String, Object>>() {}));
        } catch (JsonProcessingException | IllegalArgumentException exception) {
            throw failure(
                    request,
                    ModelErrorCategory.MALFORMED_RESPONSE,
                    false,
                    200,
                    "structured_output_invalid",
                    "provider returned invalid structured output",
                    exception);
        }
    }

    private static String requireAdapterType(ModelProviderDefinition provider) {
        Objects.requireNonNull(provider, "provider must not be null")
                .binding(io.haifa.agent.model.api.ModelApiStyles.OPENAI_CHAT_COMPLETIONS);
        return io.haifa.agent.model.api.ModelApiStyles.OPENAI_CHAT_ADAPTER;
    }

    private static void validateProviderDefinition(ModelProviderDefinition provider, boolean allowInsecureHttp) {
        OpenAiCompatibleDialects.resolve(provider).validateProvider(provider, allowInsecureHttp);
    }

    private static OpenAiCompatibleDialect dialect(AgentChatRequest request) {
        return OpenAiCompatibleDialects.resolve(request.model());
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
        mapped.put("content", messageContent(message));
        if (!message.toolCalls().isEmpty()) {
            mapped.put(
                    "tool_calls",
                    message.toolCalls().stream().map(this::toolCall).toList());
        }
        message.reasoning()
                .ifPresent(reasoning ->
                        mapped.put("reasoning_content", reasoning.use(java.util.function.Function.identity())));
        message.providerCorrelationId().ifPresent(value -> mapped.put("tool_call_id", value.value()));
        return mapped;
    }

    private Object messageContent(ModelMessage message) {
        if (!message.images().isEmpty()) {
            List<Map<String, Object>> parts = new ArrayList<>();
            parts.add(Map.of("type", "text", "text", message.content()));
            message.images().forEach(image -> {
                String url =
                        switch (image) {
                            case ImageUrlPart remote -> remote.url().toASCIIString();
                            case ImageDataPart data ->
                                "data:" + data.mediaType() + ";base64,"
                                        + Base64.getEncoder().encodeToString(data.bytes());
                        };
                parts.add(Map.of("type", "image_url", "image_url", Map.of("url", url)));
            });
            return List.copyOf(parts);
        }
        if (message.role() != ModelMessageRole.TOOL
                || (message.toolResultData().isEmpty() && !message.toolResultTruncated())) {
            return message.content();
        }
        LinkedHashMap<String, Object> content = new LinkedHashMap<>();
        content.put("summary", message.content());
        content.put("structuredData", message.toolResultData());
        content.put("truncated", message.toolResultTruncated());
        try {
            return json.writeValueAsString(content);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("tool result content cannot be serialized", exception);
        }
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
        if (!dialect(request).acceptsResponseObject(root.path("object").asText(""), false)) {
            throw failure(
                    request,
                    ModelErrorCategory.MALFORMED_RESPONSE,
                    false,
                    200,
                    "unexpected_response_object",
                    "provider returned an unexpected response object",
                    null);
        }
        if (!choices.isArray()) {
            throw failure(
                    request,
                    ModelErrorCategory.MALFORMED_RESPONSE,
                    false,
                    200,
                    "invalid_choices",
                    "provider response must contain exactly one choice",
                    null);
        }
        if (choices.isEmpty()) {
            throw failure(
                    request,
                    ModelErrorCategory.EMPTY_RESPONSE,
                    true,
                    200,
                    "empty_response",
                    "provider response contains no choices",
                    null);
        }
        if (choices.size() != 1) {
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
        JsonNode reasoningNode = responseMessage.path("reasoning_content");
        String reasoningContent =
                reasoningNode.isMissingNode() || reasoningNode.isNull() ? "" : reasoningNode.asText("");
        if (content.isBlank() && toolCalls.isEmpty()) {
            throw failure(
                    request,
                    ModelErrorCategory.EMPTY_RESPONSE,
                    true,
                    200,
                    "empty_response",
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
                Map.of("reasoningCharacters", reasoningContent.length()),
                reasoningContent.isEmpty() || !retainReasoning(request, finishReason)
                        ? java.util.Optional.empty()
                        : java.util.Optional.of(SensitiveModelReasoning.of(reasoningContent)),
                structuredOutput(request, content, toolCalls));
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
        long directCacheHit = nonNegativeLong(request, usage, "prompt_cache_hit_tokens", false);
        long standardCached = nonNegativeLong(request, usage.path("prompt_tokens_details"), "cached_tokens", false);
        return new ModelUsage(
                nonNegativeLong(request, usage, "prompt_tokens", true),
                nonNegativeLong(request, usage, "completion_tokens", true),
                directCacheHit == 0 ? standardCached : directCacheHit,
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

    private final class StreamAccumulator {
        private final AgentChatRequest request;
        private final StringBuilder content = new StringBuilder();
        private final StringBuilder reasoning = new StringBuilder();
        private final Map<Integer, StreamToolCall> tools = new LinkedHashMap<>();
        private final StreamUsageMode streamUsageMode;
        private String responseId = "";
        private String actualModelId = "";
        private String systemFingerprint = "";
        private ModelFinishReason finalReason;
        private ModelUsage usage;
        private int chunks;

        private StreamAccumulator(AgentChatRequest request) {
            this.request = request;
            this.streamUsageMode = dialect(request).streamUsageMode();
        }

        private void accept(JsonNode chunk, ModelStreamSink sink, long[] eventIndex) {
            chunks++;
            if (chunks > 100_000) {
                throw failure(
                        request,
                        ModelErrorCategory.MALFORMED_RESPONSE,
                        false,
                        200,
                        "too_many_stream_chunks",
                        "provider stream contains too many chunks",
                        null);
            }
            responseId = consistentText(chunk, "id", responseId);
            actualModelId = consistentText(chunk, "model", actualModelId);
            String fingerprint = chunk.path("system_fingerprint").asText("");
            if (!fingerprint.isBlank())
                systemFingerprint = consistent("system_fingerprint", systemFingerprint, fingerprint);

            JsonNode usageNode = chunk.path("usage");
            if (usageNode.isObject()) {
                ModelUsage parsed = parseUsage(request, usageNode);
                if (streamUsageMode == StreamUsageMode.FINAL_ONLY_STRICT && usage != null && !usage.equals(parsed)) {
                    throw failure(
                            request,
                            ModelErrorCategory.MALFORMED_RESPONSE,
                            false,
                            200,
                            "conflicting_stream_usage",
                            "provider stream contains conflicting token usage",
                            null);
                }
                if (streamUsageMode == StreamUsageMode.MONOTONIC_CUMULATIVE) {
                    if (usage != null && !monotonic(usage, parsed)) {
                        throw failure(
                                request,
                                ModelErrorCategory.MALFORMED_RESPONSE,
                                false,
                                200,
                                "non_monotonic_stream_usage",
                                "provider stream usage must be monotonically cumulative",
                                null);
                    }
                    if (!parsed.equals(usage)) usage = parsed;
                } else {
                    usage = parsed;
                    emit(request, sink, new ModelStreamEvent.UsageReported(request.callId(), eventIndex[0]++, parsed));
                }
            }

            JsonNode choices = chunk.path("choices");
            if (!choices.isArray()) {
                throw failure(
                        request,
                        ModelErrorCategory.MALFORMED_RESPONSE,
                        false,
                        200,
                        "invalid_stream_choices",
                        "provider stream choices must be an array",
                        null);
            }
            if (choices.isEmpty()) return;
            if (choices.size() != 1 || choices.get(0).path("index").asInt(-1) != 0) {
                throw failure(
                        request,
                        ModelErrorCategory.MALFORMED_RESPONSE,
                        false,
                        200,
                        "invalid_stream_choice",
                        "provider stream must contain exactly choice zero",
                        null);
            }
            JsonNode choice = choices.get(0);
            String finish = choice.path("finish_reason").asText("");
            if (!finish.isBlank()) {
                ModelFinishReason parsed = finishReason(finish);
                if (finalReason != null && finalReason != parsed) {
                    throw failure(
                            request,
                            ModelErrorCategory.MALFORMED_RESPONSE,
                            false,
                            200,
                            "conflicting_finish_reason",
                            "provider stream contains conflicting finish reasons",
                            null);
                }
                finalReason = parsed;
            }
            JsonNode delta = choice.path("delta");
            if (!delta.isObject()) return;
            String text = nullableText(delta.path("content"));
            if (!text.isEmpty()) {
                content.append(text);
                if (content.length() > maxResponseBytes) tooLarge("stream content");
                emit(request, sink, new ModelStreamEvent.ContentDelta(request.callId(), eventIndex[0]++, text));
            }
            String thought = nullableText(delta.path("reasoning_content"));
            if (!thought.isEmpty()) {
                reasoning.append(thought);
                if (reasoning.length() > maxResponseBytes) tooLarge("stream reasoning");
                emit(request, sink, new ModelStreamEvent.ReasoningDelta(request.callId(), eventIndex[0]++, thought));
            }
            JsonNode toolDeltas = delta.path("tool_calls");
            if (!toolDeltas.isMissingNode() && !toolDeltas.isNull()) {
                if (!toolDeltas.isArray()) malformed("invalid_stream_tool_calls", "stream tool_calls must be an array");
                for (JsonNode toolDelta : toolDeltas) acceptTool(toolDelta, sink, eventIndex);
            }
        }

        private void acceptTool(JsonNode node, ModelStreamSink sink, long[] eventIndex) {
            int index = node.path("index").asInt(-1);
            if (index < 0) malformed("missing_stream_tool_index", "stream tool call is missing its index");
            StreamToolCall tool = tools.computeIfAbsent(index, StreamToolCall::new);
            String id = node.path("id").asText("");
            if (!id.isEmpty()) tool.id = consistent("tool id", tool.id, id);
            String type = node.path("type").asText("");
            if (!type.isEmpty() && !"function".equals(type)) {
                malformed("unsupported_tool_type", "provider returned an unsupported tool type");
            }
            JsonNode function = node.path("function");
            String name = function.path("name").asText("");
            if (!name.isEmpty()) tool.name = consistent("tool name", tool.name, name);
            String arguments = nullableText(function.path("arguments"));
            if (!arguments.isEmpty()) {
                tool.arguments.append(arguments);
                if (tool.arguments.length() > Math.min(maxResponseBytes, 1024 * 1024)) {
                    tooLarge("stream tool arguments");
                }
            }
            emit(
                    request,
                    sink,
                    new ModelStreamEvent.ToolCallDelta(
                            request.callId(), eventIndex[0]++, 0, index, id, name, arguments));
        }

        private AgentChatResponse finish(ModelStreamSink sink, long[] eventIndex) {
            if (responseId.isBlank() || actualModelId.isBlank()) {
                malformed("missing_stream_identity", "provider stream is missing response identity");
            }
            if (finalReason == null) malformed("missing_finish_reason", "provider stream is missing finish reason");
            if (finalReason == ModelFinishReason.CONTENT_FILTER) {
                throw failure(
                        request,
                        ModelErrorCategory.CONTENT_REJECTED,
                        false,
                        200,
                        "content_filter",
                        "provider rejected the generated content",
                        null);
            }
            if (finalReason == ModelFinishReason.INSUFFICIENT_SYSTEM_RESOURCE) {
                throw failure(
                        request,
                        ModelErrorCategory.PROVIDER_UNAVAILABLE,
                        true,
                        200,
                        "insufficient_system_resource",
                        "provider lacks inference capacity",
                        null);
            }
            if (usage == null) malformed("missing_usage", "provider stream is missing token usage");
            List<ModelToolCall> calls = tools.values().stream()
                    .sorted(Comparator.comparingInt(value -> value.index))
                    .map(StreamToolCall::finish)
                    .toList();
            if (content.toString().isBlank() && calls.isEmpty()) {
                throw failure(
                        request,
                        ModelErrorCategory.EMPTY_RESPONSE,
                        true,
                        200,
                        "empty_response",
                        "provider response message is empty",
                        null);
            }
            if (streamUsageMode == StreamUsageMode.MONOTONIC_CUMULATIVE) {
                emit(request, sink, new ModelStreamEvent.UsageReported(request.callId(), eventIndex[0]++, usage));
            }
            return new AgentChatResponse(
                    responseId,
                    actualModelId,
                    content.toString(),
                    calls,
                    finalReason,
                    usage,
                    systemFingerprint,
                    Map.of("reasoningCharacters", reasoning.length()),
                    reasoning.isEmpty() || !retainReasoning(request, finalReason)
                            ? java.util.Optional.empty()
                            : java.util.Optional.of(SensitiveModelReasoning.of(reasoning.toString())),
                    structuredOutput(request, content.toString(), calls));
        }

        private boolean monotonic(ModelUsage previous, ModelUsage current) {
            return current.inputTokens() >= previous.inputTokens()
                    && current.outputTokens() >= previous.outputTokens()
                    && current.cacheHitTokens() >= previous.cacheHitTokens()
                    && current.cacheMissTokens() >= previous.cacheMissTokens()
                    && current.reasoningTokens() >= previous.reasoningTokens();
        }

        private String consistentText(JsonNode node, String field, String current) {
            String value = node.path(field).asText("");
            return value.isBlank() ? current : consistent(field, current, value);
        }

        private String consistent(String field, String current, String value) {
            if (!current.isEmpty() && !current.equals(value)) {
                malformed(
                        "conflicting_stream_" + field.replace(' ', '_'), "provider stream contains conflicting fields");
            }
            return value;
        }

        private String nullableText(JsonNode value) {
            return value.isMissingNode() || value.isNull() ? "" : value.asText("");
        }

        private void tooLarge(String field) {
            malformed("stream_field_too_large", field + " exceeds the configured size limit");
        }

        private void malformed(String code, String message) {
            throw failure(request, ModelErrorCategory.MALFORMED_RESPONSE, false, 200, code, message, null);
        }

        private final class StreamToolCall {
            private final int index;
            private String id = "";
            private String name = "";
            private final StringBuilder arguments = new StringBuilder();

            private StreamToolCall(int index) {
                this.index = index;
            }

            private ModelToolCall finish() {
                if (id.isBlank() || name.isBlank() || arguments.isEmpty()) {
                    malformed("incomplete_stream_tool_call", "provider returned an incomplete tool call");
                }
                try {
                    JsonNode parsed = json.readTree(arguments.toString());
                    if (!parsed.isObject()) throw new JsonProcessingException("tool arguments must be an object") {};
                    @SuppressWarnings("unchecked")
                    Map<String, Object> values = json.convertValue(parsed, LinkedHashMap.class);
                    return new ModelToolCall(new ProviderToolCallCorrelationId(id), name, values);
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
        }
    }

    private ModelInvocationException httpFailure(
            AgentChatRequest request, int status, byte[] body, String credential, HttpHeaders headers) {
        String providerCode = "http_" + status;
        String safeDetail = "";
        try {
            JsonNode error = json.readTree(body).path("error");
            providerCode = truncate(error.path("code").asText(providerCode), 80).replace(credential, "[REDACTED]");
            safeDetail = truncate(error.path("message").asText(""), 240).replace(credential, "[REDACTED]");
        } catch (IOException ignored) {
            // The raw response is intentionally not propagated.
        }
        ModelErrorCategory category = dialect(request).classifyError(status, providerCode, safeDetail);
        if (status >= 500 && category == ModelErrorCategory.PROVIDER_UNAVAILABLE) {
            category = ModelErrorCategory.SERVER_ERROR;
        }
        boolean retryable = status >= 500 || dialect(request).retryable(status, category, providerCode);
        String safeMessage = "model provider request failed with HTTP " + status;
        if (!safeDetail.isBlank()) safeMessage += ": " + safeDetail;
        Duration retryAfter = RetryAfterParser.parse(headers, Instant.now()).orElse(null);
        return failure(request, category, retryable, status, providerCode, safeMessage, null, retryAfter, false);
    }

    private static boolean retainReasoning(AgentChatRequest request, ModelFinishReason finishReason) {
        if (finishReason != ModelFinishReason.TOOL_CALLS) return true;
        return Boolean.TRUE.equals(request.model().invocationOptions().get("requires_reasoning_continuation"));
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
        return failure(request, category, retryable, status, code, message, cause, null, false);
    }

    private ModelInvocationException failure(
            AgentChatRequest request,
            ModelErrorCategory category,
            boolean retryable,
            int status,
            String code,
            String message,
            Throwable cause,
            Duration retryAfter,
            boolean outputObserved) {
        return new ModelInvocationException(
                category, retryable, status, code, request.callId(), message, cause, retryAfter, outputObserved);
    }
}
