package io.haifa.agent.model.openai.responses;

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
import io.haifa.agent.model.api.ModelApiStyles;
import io.haifa.agent.model.api.ModelErrorCategory;
import io.haifa.agent.model.api.ModelFinishReason;
import io.haifa.agent.model.api.ModelInvocationException;
import io.haifa.agent.model.api.ModelMessage;
import io.haifa.agent.model.api.ModelMessageRole;
import io.haifa.agent.model.api.ModelStreamControl;
import io.haifa.agent.model.api.ModelStreamEvent;
import io.haifa.agent.model.api.ModelStreamSink;
import io.haifa.agent.model.api.ModelToolCall;
import io.haifa.agent.model.api.ModelToolSpecification;
import io.haifa.agent.model.api.ModelUsage;
import io.haifa.agent.model.api.ResolvedCredential;
import io.haifa.agent.model.api.SensitiveModelReasoning;
import io.haifa.agent.model.openai.ModelStreamObservation;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/** Bounded OpenAI Responses adapter with an Item-aware synchronous and SSE parser. */
public final class OpenAiResponsesModel implements AgentChatModel {
    public static final String ADAPTER_TYPE = ModelApiStyles.OPENAI_RESPONSES_ADAPTER;
    public static final String ADAPTER_VERSION = "1.0.0";

    private static final int DEFAULT_MAX_RESPONSE_BYTES = 4 * 1024 * 1024;
    private static final int MAX_EVENTS = 100_000;
    private static final int MAX_EVENT_BYTES = 1024 * 1024;

    private final HttpClient http;
    private final ObjectMapper json;
    private final CredentialResolver credentials;
    private final boolean allowInsecureHttp;
    private final int maxResponseBytes;
    private final CodexAccountIdentityResolver codexAccountResolver;

    public OpenAiResponsesModel(HttpClient http, ObjectMapper json, CredentialResolver credentials) {
        this(http, json, credentials, false, DEFAULT_MAX_RESPONSE_BYTES, ignored -> java.util.Optional.empty());
    }

    public OpenAiResponsesModel(
            HttpClient http,
            ObjectMapper json,
            CredentialResolver credentials,
            CodexAccountIdentityResolver codexAccountResolver) {
        this(http, json, credentials, false, DEFAULT_MAX_RESPONSE_BYTES, codexAccountResolver);
    }

    public OpenAiResponsesModel(
            HttpClient http,
            ObjectMapper json,
            CredentialResolver credentials,
            boolean allowInsecureHttp,
            int maxResponseBytes) {
        this(http, json, credentials, allowInsecureHttp, maxResponseBytes, ignored -> java.util.Optional.empty());
    }

    public OpenAiResponsesModel(
            HttpClient http,
            ObjectMapper json,
            CredentialResolver credentials,
            boolean allowInsecureHttp,
            int maxResponseBytes,
            CodexAccountIdentityResolver codexAccountResolver) {
        this.http = Objects.requireNonNull(http, "http must not be null");
        this.json = Objects.requireNonNull(json, "json must not be null");
        this.credentials = Objects.requireNonNull(credentials, "credentials must not be null");
        this.allowInsecureHttp = allowInsecureHttp;
        if (maxResponseBytes < 1) throw new IllegalArgumentException("maxResponseBytes must be positive");
        this.maxResponseBytes = maxResponseBytes;
        this.codexAccountResolver =
                Objects.requireNonNull(codexAccountResolver, "codexAccountResolver must not be null");
    }

    @Override
    public AgentChatResponse invoke(AgentChatRequest request) {
        OpenAiResponsesDialect dialect = validateSelection(request);
        ResolvedCredential credential = credential(request);
        HttpRequest httpRequest = request(request, dialect, credential, false);
        try {
            HttpResponse<InputStream> response = http.send(httpRequest, HttpResponse.BodyHandlers.ofInputStream());
            byte[] body;
            try {
                body = readBounded(response.body(), maxResponseBytes);
            } catch (ResponseTooLargeException exception) {
                throw failure(
                        request,
                        ModelErrorCategory.MALFORMED_RESPONSE,
                        false,
                        response.statusCode(),
                        "response_too_large",
                        "provider response exceeds the configured size limit",
                        exception);
            }
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw httpFailure(request, dialect, response.statusCode(), response.headers(), body);
            }
            requireContentType(request, dialect, response, "application/json");
            return parseResponse(request, dialect, parseJson(request, body));
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
        Objects.requireNonNull(sink, "sink must not be null");
        OpenAiResponsesDialect dialect = validateSelection(request);
        if (!request.model().nativeStreaming()) return AgentChatModel.super.invokeStreaming(request, sink);
        ResolvedCredential credential = credential(request);
        HttpRequest httpRequest = request(request, dialect, credential, true);
        ModelStreamObservation observation = new ModelStreamObservation();
        try {
            HttpResponse<InputStream> response = http.send(httpRequest, HttpResponse.BodyHandlers.ofInputStream());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                byte[] errorBody;
                try (InputStream body = response.body()) {
                    errorBody = body.readNBytes(maxResponseBytes + 1);
                }
                if (errorBody.length > maxResponseBytes) errorBody = new byte[0];
                throw httpFailure(request, dialect, response.statusCode(), response.headers(), errorBody);
            }
            requireContentType(request, dialect, response, "text/event-stream");
            try (InputStream body = response.body()) {
                return parseStream(request, dialect, body, observation.observe(sink));
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

    private OpenAiResponsesDialect validateSelection(AgentChatRequest request) {
        Objects.requireNonNull(request, "request must not be null");
        if (!ADAPTER_TYPE.equals(request.model().adapterType())
                || !ADAPTER_VERSION.equals(request.model().adapterVersion())) {
            throw new IllegalArgumentException("snapshot selects a different model adapter");
        }
        OpenAiResponsesDialect dialect = OpenAiResponsesDialects.resolve(request.model(), allowInsecureHttp);
        if (request.messages().stream().anyMatch(message -> !message.audios().isEmpty())) {
            throw new IllegalArgumentException("audio input is not enabled by this Responses adapter profile");
        }
        dialect.validateRequest(request);
        if (!request.tools().isEmpty()
                && !request.model().capabilities().contains(io.haifa.agent.model.api.ModelCapability.TOOL_CALLING)) {
            throw new IllegalArgumentException("selected model does not declare tool calling capability");
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
        return dialect;
    }

    private ResolvedCredential credential(AgentChatRequest request) {
        try {
            ResolvedCredential credential = credentials.resolve(request.model().credentialRef());
            OpenAiCodexAuthentication.validateHeaderValue(credential.value(), "model credential");
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

    private HttpRequest request(
            AgentChatRequest request, OpenAiResponsesDialect dialect, ResolvedCredential credential, boolean stream) {
        byte[] body;
        try {
            body = json.writeValueAsBytes(requestBody(request, dialect, stream));
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
        HttpRequest.Builder builder = HttpRequest.newBuilder(
                        responsesUri(request.model().endpoint()))
                .timeout(request.timeout())
                .header("Content-Type", "application/json")
                .header("Accept", stream ? "text/event-stream" : "application/json")
                .POST(HttpRequest.BodyPublishers.ofByteArray(body));
        try {
            dialect.decorateHeaders(builder, request, credential.value(), codexAccountResolver);
        } catch (DialectAuthenticationException exception) {
            throw failure(
                    request,
                    ModelErrorCategory.AUTHENTICATION_FAILED,
                    false,
                    0,
                    exception.providerCode(),
                    exception.getMessage(),
                    exception);
        }
        return builder.build();
    }

    private Map<String, Object> requestBody(AgentChatRequest request, OpenAiResponsesDialect dialect, boolean stream) {
        Map<String, Object> options = invocationOptions(request);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", request.model().providerModelId());
        body.put("input", inputItems(request, dialect));
        dialect.customizeRequestBody(request, body);
        body.put("stream", stream);
        body.put("store", false);
        String instructions = instructions(request.messages());
        if (!instructions.isEmpty()) body.put("instructions", instructions);
        if (!request.tools().isEmpty()) {
            body.put("tools", request.tools().stream().map(this::tool).toList());
            Object toolChoice = options.getOrDefault("tool_choice", "auto");
            dialect.validateToolChoice(toolChoice);
            body.put("tool_choice", toolChoice);
        }
        if (request.structuredOutput().isPresent() && options.containsKey("response_format")) {
            throw new IllegalArgumentException("structured output cannot be combined with response_format options");
        }
        Object format = request.structuredOutput().isPresent()
                ? Map.<String, Object>of(
                        "type",
                        "json_schema",
                        "name",
                        request.structuredOutput().orElseThrow().responseName(),
                        "strict",
                        true,
                        "schema",
                        request.structuredOutput().orElseThrow().jsonSchema())
                : options.get("response_format");
        if (format != null) body.put("text", Map.of("format", responseFormat(format)));
        Object effort = options.get("reasoning_effort");
        if (effort != null) body.put("reasoning", Map.of("effort", reasoningEffort(effort)));
        if (options.keySet().stream()
                .anyMatch(key -> !key.equals("response_format")
                        && !key.equals("tool_choice")
                        && !key.equals("reasoning_effort"))) {
            throw new IllegalArgumentException("unsupported Responses invocation option");
        }
        return Map.copyOf(body);
    }

    private static Map<String, Object> invocationOptions(AgentChatRequest request) {
        Map<String, Object> values = new LinkedHashMap<>();
        copyOption(request.model().invocationOptions(), values, "response_format");
        copyOption(request.model().invocationOptions(), values, "tool_choice");
        copyOption(request.model().invocationOptions(), values, "reasoning_effort");
        values.putAll(request.options());
        return Map.copyOf(values);
    }

    private static void copyOption(Map<String, Object> source, Map<String, Object> target, String key) {
        if (source.containsKey(key)) target.put(key, source.get(key));
    }

    private List<Map<String, Object>> inputItems(AgentChatRequest request, OpenAiResponsesDialect dialect) {
        List<Map<String, Object>> items = new ArrayList<>();
        for (ModelMessage message : request.messages()) {
            if (message.role() == ModelMessageRole.SYSTEM || message.role() == ModelMessageRole.DEVELOPER) continue;
            if (message.role() == ModelMessageRole.TOOL) {
                items.add(Map.of(
                        "type",
                        "function_call_output",
                        "call_id",
                        message.providerCorrelationId().orElseThrow().value(),
                        "output",
                        toolOutput(message)));
                continue;
            }
            if (!message.content().isEmpty() || !message.images().isEmpty()) items.add(messageItem(message));
            if (message.role() == ModelMessageRole.ASSISTANT) {
                if (message.reasoning().isPresent()) {
                    items.add(dialect.customizeReasoningInputItem(message).orElseThrow());
                }
                for (ModelToolCall call : message.toolCalls()) {
                    items.add(Map.of(
                            "type",
                            "function_call",
                            "call_id",
                            call.providerCorrelationId().value(),
                            "name",
                            call.name(),
                            "arguments",
                            writeJson(call.arguments())));
                }
            }
        }
        return List.copyOf(items);
    }

    private Map<String, Object> messageItem(ModelMessage message) {
        String role = message.role() == ModelMessageRole.ASSISTANT ? "assistant" : "user";
        List<Map<String, Object>> content = new ArrayList<>();
        if (!message.content().isEmpty()) {
            content.add(Map.of(
                    "type",
                    message.role() == ModelMessageRole.ASSISTANT ? "output_text" : "input_text",
                    "text",
                    message.content()));
        }
        for (var image : message.images()) {
            String imageUrl;
            if (image instanceof ImageUrlPart remote) {
                imageUrl = remote.url().toString();
            } else if (image instanceof ImageDataPart data) {
                imageUrl = "data:" + data.mediaType() + ";base64,"
                        + Base64.getEncoder().encodeToString(data.bytes());
            } else {
                throw new IllegalArgumentException("unsupported model image part");
            }
            content.add(Map.of("type", "input_image", "image_url", imageUrl));
        }
        return Map.of("type", "message", "role", role, "content", List.copyOf(content));
    }

    private Map<String, Object> tool(ModelToolSpecification tool) {
        return Map.of(
                "type", "function",
                "name", tool.name(),
                "description", tool.description(),
                "parameters", tool.inputJsonSchema(),
                "strict", tool.strict());
    }

    private String toolOutput(ModelMessage message) {
        return message.toolResultData().isEmpty() ? message.content() : writeJson(message.toolResultData());
    }

    private String instructions(List<ModelMessage> messages) {
        return messages.stream()
                .filter(message ->
                        message.role() == ModelMessageRole.SYSTEM || message.role() == ModelMessageRole.DEVELOPER)
                .map(ModelMessage::content)
                .filter(value -> !value.isEmpty())
                .collect(java.util.stream.Collectors.joining("\n\n"));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> responseFormat(Object configured) {
        if (!(configured instanceof Map<?, ?> raw)) {
            throw new IllegalArgumentException("response_format must be an object");
        }
        Map<String, Object> value = new LinkedHashMap<>();
        raw.forEach((key, item) -> value.put(String.valueOf(key), item));
        String type = String.valueOf(value.get("type"));
        if (!type.equals("json_object") && !type.equals("json_schema")) {
            throw new IllegalArgumentException("response_format type is unsupported");
        }
        return Map.copyOf(value);
    }

    private static String reasoningEffort(Object configured) {
        String value = String.valueOf(configured);
        if (!List.of("low", "medium", "high", "max", "xhigh").contains(value)) {
            throw new IllegalArgumentException("reasoning_effort is unsupported");
        }
        return value;
    }

    private AgentChatResponse parseResponse(AgentChatRequest request, OpenAiResponsesDialect dialect, JsonNode root) {
        return parseResponse(request, root, "", List.of());
    }

    private AgentChatResponse parseResponse(
            AgentChatRequest request, JsonNode root, String streamedContent, List<ModelToolCall> streamedCalls) {
        try {
            return parseResponseValue(request, root, streamedContent, streamedCalls);
        } catch (ModelInvocationException exception) {
            throw exception;
        } catch (IllegalArgumentException exception) {
            throw malformed(request, "provider returned an invalid Responses object");
        }
    }

    private AgentChatResponse parseResponseValue(
            AgentChatRequest request, JsonNode root, String streamedContent, List<ModelToolCall> streamedCalls) {
        String status = text(root, "status", true);
        if ("failed".equals(status)) {
            throw failure(
                    request,
                    ModelErrorCategory.UNKNOWN_PROVIDER_ERROR,
                    false,
                    0,
                    "response_failed",
                    "provider reported a failed response",
                    null);
        }
        if (!"completed".equals(status) && !"incomplete".equals(status)) {
            throw malformed(request, "provider response has an invalid terminal status");
        }
        StringBuilder content = new StringBuilder();
        StringBuilder reasoning = new StringBuilder();
        List<ModelToolCall> calls = new ArrayList<>();
        JsonNode output = root.path("output");
        if (!output.isArray()) throw malformed(request, "provider response output must be an array");
        for (JsonNode item : output) parseOutputItem(request, item, content, reasoning, calls);
        if (content.isEmpty() && !streamedContent.isEmpty()) content.append(streamedContent);
        if (calls.isEmpty() && !streamedCalls.isEmpty()) calls.addAll(streamedCalls);
        ModelFinishReason finish = !calls.isEmpty()
                ? ModelFinishReason.TOOL_CALLS
                : "incomplete".equals(status) ? incompleteReason(root) : ModelFinishReason.STOP;
        ModelUsage usage = usage(request, root.path("usage"));
        if (content.isEmpty() && calls.isEmpty()) {
            throw emptyResponse(request, "provider response contains no output");
        }
        boolean retainReasoning = !reasoning.isEmpty()
                && !calls.isEmpty()
                && OpenAiResponsesDialects.DEEPSEEK.equals(request.model().dialect());
        return new AgentChatResponse(
                text(root, "id", true),
                optionalText(root, "model", request.model().providerModelId()),
                content.toString(),
                calls,
                finish,
                usage,
                "",
                Map.of("status", status, "reasoningCharacters", reasoning.length()),
                retainReasoning
                        ? java.util.Optional.of(SensitiveModelReasoning.of(reasoning.toString()))
                        : java.util.Optional.empty(),
                structuredOutput(request, content.toString(), calls));
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

    private void parseOutputItem(
            AgentChatRequest request,
            JsonNode item,
            StringBuilder content,
            StringBuilder reasoning,
            List<ModelToolCall> calls) {
        switch (text(item, "type", true)) {
            case "message" -> {
                JsonNode parts = item.path("content");
                if (!parts.isArray()) throw malformed(request, "message content must be an array");
                for (JsonNode part : parts) {
                    String type = text(part, "type", true);
                    if ("output_text".equals(type)) content.append(text(part, "text", true));
                    else if ("refusal".equals(type)) content.append(text(part, "refusal", true));
                }
            }
            case "function_call" ->
                calls.add(new ModelToolCall(
                        new ProviderToolCallCorrelationId(text(item, "call_id", true)),
                        text(item, "name", true),
                        arguments(request, text(item, "arguments", true))));
            case "reasoning" -> appendReasoning(item, reasoning);
            default -> {
                // Unknown output Items are ignored only when known output remains; raw provider data is never exposed.
            }
        }
        if (content.length() + reasoning.length() > maxResponseBytes) {
            throw malformed(request, "provider output exceeds the configured size limit");
        }
    }

    private static void appendReasoning(JsonNode item, StringBuilder reasoning) {
        for (String field : List.of("summary", "content")) {
            JsonNode values = item.path(field);
            if (!values.isArray()) continue;
            for (JsonNode value : values) {
                JsonNode text = value.get("text");
                if (text != null && text.isTextual()) reasoning.append(text.textValue());
            }
        }
    }

    private ModelUsage usage(AgentChatRequest request, JsonNode usage) {
        if (!usage.isObject()) return ModelUsage.unpriced(0, 0);
        long input = nonNegativeLong(request, usage, "input_tokens");
        long output = nonNegativeLong(request, usage, "output_tokens");
        long cached = nonNegativeLong(request, usage.path("input_tokens_details"), "cached_tokens");
        long reasoning = nonNegativeLong(request, usage.path("output_tokens_details"), "reasoning_tokens");
        return new ModelUsage(input, output, cached, Math.max(0, input - cached), reasoning, false, 0);
    }

    private AgentChatResponse parseStream(
            AgentChatRequest request, OpenAiResponsesDialect dialect, InputStream stream, ModelStreamSink sink)
            throws IOException {
        StreamState state = new StreamState(request, dialect, sink);
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            StringBuilder data = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                state.addBytes(line.getBytes(StandardCharsets.UTF_8).length + 1);
                if (line.isEmpty()) {
                    if (!data.isEmpty()) {
                        state.accept(data.toString());
                        data.setLength(0);
                    }
                    continue;
                }
                if (line.startsWith("data:")) {
                    String fragment = line.substring(5).stripLeading();
                    if (!data.isEmpty()) data.append('\n');
                    data.append(fragment);
                    if (data.length() > MAX_EVENT_BYTES) throw malformed(request, "Responses SSE event is too large");
                }
            }
            if (!data.isEmpty()) state.accept(data.toString());
        }
        return state.finish();
    }

    private final class StreamState {
        private final AgentChatRequest request;
        private final OpenAiResponsesDialect dialect;
        private final ModelStreamSink sink;
        private long emittedIndex;
        private long eventCount;
        private long bytes;
        private long sequence = Long.MIN_VALUE;
        private boolean started;
        private boolean terminal;
        private AgentChatResponse completed;
        private final StringBuilder content = new StringBuilder();
        private final Map<Integer, FunctionItem> functions = new LinkedHashMap<>();

        private StreamState(AgentChatRequest request, OpenAiResponsesDialect dialect, ModelStreamSink sink) {
            this.request = request;
            this.dialect = dialect;
            this.sink = sink;
        }

        private void addBytes(long value) {
            bytes += value;
            if (bytes > maxResponseBytes) throw malformed(request, "Responses SSE stream is too large");
        }

        private void accept(String data) {
            if ("[DONE]".equals(data)) {
                if (!terminal) throw interrupted(request, "Responses stream ended before a terminal event");
                return;
            }
            if (++eventCount > MAX_EVENTS) throw malformed(request, "Responses SSE event limit exceeded");
            JsonNode event = parseJson(request, data.getBytes(StandardCharsets.UTF_8));
            validateSequence(event);
            String type = text(event, "type", true);
            if (terminal) throw malformed(request, "Responses stream emitted an event after terminal status");
            if ("response.created".equals(type) || "response.in_progress".equals(type)) {
                start();
                return;
            }
            start();
            switch (type) {
                case "response.output_text.delta" -> emitContent(textDelta(event));
                case "response.output_text.done" -> contentDone(event);
                case "response.reasoning_summary_text.delta", "response.reasoning_text.delta" ->
                    emitReasoning(textDelta(event));
                case "response.output_item.added" -> addItem(event);
                case "response.function_call_arguments.delta" -> functionDelta(event);
                case "response.function_call_arguments.done" -> functionDone(event);
                case "response.output_item.done" -> itemDone(event);
                case "response.completed", "response.incomplete" -> complete(event);
                case "response.failed", "error" ->
                    throw failure(
                            request,
                            ModelErrorCategory.UNKNOWN_PROVIDER_ERROR,
                            false,
                            0,
                            "stream_failed",
                            "provider reported a failed response stream",
                            null);
                default -> {
                    // Structural lifecycle events do not need a provider-neutral public delta.
                }
            }
        }

        private void validateSequence(JsonNode event) {
            dialect.validateEventSequence(event);
            JsonNode configured = event.get("sequence_number");
            if (configured == null) return;
            long current = configured.longValue();
            if (sequence != Long.MIN_VALUE && current <= sequence) {
                throw malformed(request, "Responses sequence_number is not monotonic");
            }
            sequence = current;
        }

        private void start() {
            if (started) return;
            started = true;
            emit(new ModelStreamEvent.Started(request.callId(), ++emittedIndex));
        }

        private void emitContent(String delta) {
            if (delta.isEmpty()) return;
            content.append(delta);
            if (content.length() > maxResponseBytes) throw malformed(request, "Responses text output is too large");
            emit(new ModelStreamEvent.ContentDelta(request.callId(), ++emittedIndex, delta));
        }

        private void emitReasoning(String delta) {
            if (!delta.isEmpty()) emit(new ModelStreamEvent.ReasoningDelta(request.callId(), ++emittedIndex, delta));
        }

        private void contentDone(JsonNode event) {
            String value = text(event, "text", true);
            content.setLength(0);
            content.append(value);
            if (content.length() > maxResponseBytes) throw malformed(request, "Responses text output is too large");
        }

        private void addItem(JsonNode event) {
            JsonNode item = event.path("item");
            if (!"function_call".equals(item.path("type").asText())) return;
            int outputIndex = nonNegativeInt(request, event, "output_index");
            FunctionItem function = new FunctionItem(text(item, "call_id", true), text(item, "name", true));
            String initialArguments = optionalText(item, "arguments", "");
            if (!initialArguments.isEmpty()) function.arguments.append(initialArguments);
            if (functions.putIfAbsent(outputIndex, function) != null) {
                throw malformed(request, "duplicate Responses function output index");
            }
            emit(new ModelStreamEvent.ToolCallDelta(
                    request.callId(), ++emittedIndex, 0, outputIndex, function.callId, function.name, ""));
        }

        private void functionDelta(JsonNode event) {
            int outputIndex = nonNegativeInt(request, event, "output_index");
            FunctionItem item = functions.get(outputIndex);
            if (item == null) throw malformed(request, "function arguments arrived before function item");
            String delta = textDelta(event);
            item.arguments.append(delta);
            if (item.arguments.length() > maxResponseBytes)
                throw malformed(request, "function arguments are too large");
            emit(new ModelStreamEvent.ToolCallDelta(
                    request.callId(), ++emittedIndex, 0, outputIndex, item.callId, item.name, delta));
        }

        private void functionDone(JsonNode event) {
            replaceFunctionArguments(event, text(event, "arguments", true));
        }

        private void itemDone(JsonNode event) {
            JsonNode item = event.path("item");
            if (!"function_call".equals(item.path("type").asText())) return;
            replaceFunctionArguments(event, text(item, "arguments", true));
        }

        private void replaceFunctionArguments(JsonNode event, String value) {
            int outputIndex = nonNegativeInt(request, event, "output_index");
            FunctionItem item = functions.get(outputIndex);
            if (item == null) throw malformed(request, "function arguments completed before function item");
            if (value.length() > maxResponseBytes) throw malformed(request, "function arguments are too large");
            item.arguments.setLength(0);
            item.arguments.append(value);
        }

        private void complete(JsonNode event) {
            JsonNode response = event.path("response");
            if (!response.isObject()) throw malformed(request, "terminal Responses event is missing response");
            completed = parseResponse(request, response, content.toString(), streamedCalls());
            terminal = true;
            emit(new ModelStreamEvent.UsageReported(request.callId(), ++emittedIndex, completed.usage()));
        }

        private List<ModelToolCall> streamedCalls() {
            List<ModelToolCall> calls = new ArrayList<>();
            for (FunctionItem function : functions.values()) {
                String value = function.arguments.isEmpty() ? "{}" : function.arguments.toString();
                calls.add(new ModelToolCall(
                        new ProviderToolCallCorrelationId(function.callId), function.name, arguments(request, value)));
            }
            return List.copyOf(calls);
        }

        private void emit(ModelStreamEvent event) {
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

        private AgentChatResponse finish() {
            if (!terminal || completed == null) {
                throw interrupted(request, "Responses stream ended without a terminal event");
            }
            return completed;
        }
    }

    private static final class FunctionItem {
        private final String callId;
        private final String name;
        private final StringBuilder arguments = new StringBuilder();

        private FunctionItem(String callId, String name) {
            this.callId = callId;
            this.name = name;
        }
    }

    private Map<String, Object> arguments(AgentChatRequest request, String value) {
        try {
            JsonNode parsed = json.readTree(value);
            if (!parsed.isObject()) throw malformed(request, "function arguments must be a JSON object");
            return json.convertValue(parsed, new com.fasterxml.jackson.core.type.TypeReference<>() {});
        } catch (JsonProcessingException exception) {
            throw malformed(request, "function arguments are malformed");
        }
    }

    private String writeJson(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("provider-neutral value cannot be serialized", exception);
        }
    }

    private JsonNode parseJson(AgentChatRequest request, byte[] value) {
        try {
            return json.readTree(value);
        } catch (JsonProcessingException exception) {
            throw malformed(request, "provider returned malformed JSON");
        } catch (IOException exception) {
            throw malformed(request, "provider response could not be read");
        }
    }

    private static byte[] readBounded(InputStream stream, int limit) throws IOException {
        try (InputStream input = stream) {
            byte[] value = input.readNBytes(limit + 1);
            if (value.length > limit) throw new ResponseTooLargeException();
            return value;
        }
    }

    private static final class ResponseTooLargeException extends IOException {}

    private static URI responsesUri(URI base) {
        String value = base.toString();
        while (value.endsWith("/")) value = value.substring(0, value.length() - 1);
        if (value.endsWith("/responses")) return URI.create(value);
        return URI.create(value + "/responses");
    }

    private static void requireContentType(
            AgentChatRequest request, OpenAiResponsesDialect dialect, HttpResponse<?> response, String expected) {
        String contentType = response.headers().firstValue("Content-Type").orElse("");
        if (dialect.allowsEmptyContentType() && contentType.isBlank()) {
            return;
        }
        if (!contentType.toLowerCase(Locale.ROOT).contains(expected)) {
            String mediaType = contentType.split(";", 2)[0].trim().toLowerCase(Locale.ROOT);
            String code = mediaType.isEmpty()
                    ? "unexpected_content_type:missing"
                    : mediaType.matches("[a-z0-9.+-]{1,32}/[a-z0-9.+-]{1,64}")
                            ? "unexpected_content_type:" + mediaType.replace('/', '_')
                            : "unexpected_content_type:invalid";
            throw failure(
                    request,
                    ModelErrorCategory.MALFORMED_RESPONSE,
                    false,
                    response.statusCode(),
                    code,
                    "provider returned an unexpected content type",
                    null);
        }
    }

    private static ModelFinishReason incompleteReason(JsonNode root) {
        String reason = root.path("incomplete_details").path("reason").asText("");
        return "max_output_tokens".equals(reason) ? ModelFinishReason.LENGTH : ModelFinishReason.UNKNOWN;
    }

    private static int nonNegativeInt(AgentChatRequest request, JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || !value.canConvertToInt() || value.intValue() < 0) {
            throw malformed(request, "provider response contains an invalid " + field);
        }
        return value.intValue();
    }

    private static long nonNegativeLong(AgentChatRequest request, JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull() || value.isMissingNode()) return 0;
        if (!value.canConvertToLong() || value.longValue() < 0) {
            throw malformed(request, "provider response contains invalid usage");
        }
        return value.longValue();
    }

    private static String text(JsonNode node, String field, boolean required) {
        JsonNode value = node.get(field);
        if (value != null
                && value.isTextual()
                && (!required || !value.textValue().isEmpty())) return value.textValue();
        if (!required) return "";
        throw new IllegalArgumentException("provider response is missing " + field);
    }

    private static String textDelta(JsonNode event) {
        JsonNode value = event.get("delta");
        if (value != null && value.isTextual()) return value.textValue();
        throw new IllegalArgumentException("provider stream delta must be a string");
    }

    private static String optionalText(JsonNode node, String field, String fallback) {
        String value = text(node, field, false);
        return value.isEmpty() ? fallback : value;
    }

    private ModelInvocationException httpFailure(
            AgentChatRequest request,
            OpenAiResponsesDialect dialect,
            int status,
            java.net.http.HttpHeaders headers,
            byte[] body) {
        JsonNode errorRoot = null;
        if (body != null && body.length > 0) {
            try {
                errorRoot = json.readTree(body);
            } catch (Exception ignored) {
            }
        }
        DialectErrorMapping mapping = dialect.classifyError(status, headers, body, errorRoot);
        return failure(
                request,
                mapping.category(),
                mapping.retryable(),
                status,
                mapping.providerCode(),
                mapping.safeMessage(),
                null,
                mapping.retryAfter().orElse(null),
                false);
    }

    private static ModelInvocationException malformed(AgentChatRequest request, String message) {
        return failure(request, ModelErrorCategory.MALFORMED_RESPONSE, false, 0, "malformed_response", message, null);
    }

    private static ModelInvocationException emptyResponse(AgentChatRequest request, String message) {
        return failure(request, ModelErrorCategory.EMPTY_RESPONSE, true, 200, "empty_response", message, null);
    }

    private static ModelInvocationException interrupted(AgentChatRequest request, String message) {
        return failure(request, ModelErrorCategory.TRANSPORT_ERROR, true, 200, "stream_interrupted", message, null);
    }

    private static ModelInvocationException failure(
            AgentChatRequest request,
            ModelErrorCategory category,
            boolean retryable,
            int status,
            String code,
            String safeMessage,
            Throwable cause) {
        return failure(request, category, retryable, status, code, safeMessage, cause, null, false);
    }

    private static ModelInvocationException failure(
            AgentChatRequest request,
            ModelErrorCategory category,
            boolean retryable,
            int status,
            String code,
            String safeMessage,
            Throwable cause,
            Duration retryAfter,
            boolean outputObserved) {
        return new ModelInvocationException(
                category, retryable, status, code, request.callId(), safeMessage, cause, retryAfter, outputObserved);
    }
}
