package io.haifa.agent.model.openai.anthropic;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.haifa.agent.core.tool.ProviderToolCallCorrelationId;
import io.haifa.agent.model.api.AgentChatModel;
import io.haifa.agent.model.api.AgentChatRequest;
import io.haifa.agent.model.api.AgentChatResponse;
import io.haifa.agent.model.api.CredentialResolver;
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
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** Bounded Anthropic Messages adapter with independent Content Block and named-SSE accumulation. */
public final class AnthropicMessagesModel implements AgentChatModel {
    public static final String ADAPTER_TYPE = ModelApiStyles.ANTHROPIC_MESSAGES_ADAPTER;
    public static final String ADAPTER_VERSION = "1.0.0";
    public static final String ANTHROPIC_VERSION = "2023-06-01";

    private static final int DEFAULT_MAX_RESPONSE_BYTES = 4 * 1024 * 1024;
    private static final int MAX_EVENTS = 100_000;
    private static final int MAX_EVENT_BYTES = 1024 * 1024;
    private static final String REASONING_FORMAT = "anthropic-thinking-v1";

    private final HttpClient http;
    private final ObjectMapper json;
    private final CredentialResolver credentials;
    private final boolean allowInsecureHttp;
    private final int maxResponseBytes;

    public AnthropicMessagesModel(HttpClient http, ObjectMapper json, CredentialResolver credentials) {
        this(http, json, credentials, false, DEFAULT_MAX_RESPONSE_BYTES);
    }

    public AnthropicMessagesModel(
            HttpClient http,
            ObjectMapper json,
            CredentialResolver credentials,
            boolean allowInsecureHttp,
            int maxResponseBytes) {
        this.http = Objects.requireNonNull(http, "http must not be null");
        this.json = Objects.requireNonNull(json, "json must not be null");
        this.credentials = Objects.requireNonNull(credentials, "credentials must not be null");
        this.allowInsecureHttp = allowInsecureHttp;
        if (maxResponseBytes < 1) throw new IllegalArgumentException("maxResponseBytes must be positive");
        this.maxResponseBytes = maxResponseBytes;
    }

    @Override
    public AgentChatResponse invoke(AgentChatRequest request) {
        AnthropicMessagesDialects.Profile profile = validateSelection(request);
        ResolvedCredential credential = credential(request);
        HttpRequest httpRequest = request(request, profile, credential, false);
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
                throw httpFailure(request, response.statusCode());
            }
            requireContentType(request, response, "application/json");
            return parseResponse(request, profile, parseJson(request, body));
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

    @Override
    public AgentChatResponse invokeStreaming(AgentChatRequest request, ModelStreamSink sink) {
        Objects.requireNonNull(sink, "sink must not be null");
        AnthropicMessagesDialects.Profile profile = validateSelection(request);
        if (!request.model().nativeStreaming()) return AgentChatModel.super.invokeStreaming(request, sink);
        ResolvedCredential credential = credential(request);
        HttpRequest httpRequest = request(request, profile, credential, true);
        try {
            HttpResponse<InputStream> response = http.send(httpRequest, HttpResponse.BodyHandlers.ofInputStream());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                try (InputStream body = response.body()) {
                    body.readNBytes(maxResponseBytes + 1);
                }
                throw httpFailure(request, response.statusCode());
            }
            requireContentType(request, response, "text/event-stream");
            try (InputStream body = response.body()) {
                return parseStream(request, profile, body, sink);
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

    private AnthropicMessagesDialects.Profile validateSelection(AgentChatRequest request) {
        Objects.requireNonNull(request, "request must not be null");
        if (!ADAPTER_TYPE.equals(request.model().adapterType())
                || !ADAPTER_VERSION.equals(request.model().adapterVersion())) {
            throw new IllegalArgumentException("snapshot selects a different model adapter");
        }
        AnthropicMessagesDialects.Profile profile =
                AnthropicMessagesDialects.resolve(request.model(), allowInsecureHttp);
        if (request.messages().stream().anyMatch(message -> !message.images().isEmpty())) {
            throw new IllegalArgumentException("Anthropic Messages image input is not enabled by this adapter profile");
        }
        if (!request.tools().isEmpty()
                && !request.model().capabilities().contains(io.haifa.agent.model.api.ModelCapability.TOOL_CALLING)) {
            throw new IllegalArgumentException("selected model does not declare tool calling capability");
        }
        if (request.structuredOutput().isPresent() && profile == AnthropicMessagesDialects.Profile.DEEPSEEK) {
            throw failure(
                    request,
                    ModelErrorCategory.INVALID_REQUEST,
                    false,
                    0,
                    "structured_output_unsupported",
                    "DeepSeek Anthropic Messages structured output is not verified",
                    null);
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
        return profile;
    }

    private ResolvedCredential credential(AgentChatRequest request) {
        try {
            return credentials.resolve(request.model().credentialRef());
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
            AgentChatRequest request,
            AnthropicMessagesDialects.Profile profile,
            ResolvedCredential credential,
            boolean stream) {
        byte[] body;
        try {
            body = json.writeValueAsBytes(requestBody(request, profile, stream));
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
        if (body.length > maxResponseBytes) {
            throw failure(
                    request,
                    ModelErrorCategory.INVALID_REQUEST,
                    false,
                    0,
                    "request_too_large",
                    "model request exceeds the configured size limit",
                    null);
        }
        return HttpRequest.newBuilder(messagesUri(request.model().endpoint()))
                .timeout(request.timeout())
                .header("Content-Type", "application/json")
                .header("Accept", stream ? "text/event-stream" : "application/json")
                .header("x-api-key", credential.value())
                .header("anthropic-version", ANTHROPIC_VERSION)
                .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                .build();
    }

    private Map<String, Object> requestBody(
            AgentChatRequest request, AnthropicMessagesDialects.Profile profile, boolean stream) {
        Map<String, Object> options = options(request);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", request.model().providerModelId());
        body.put("max_tokens", request.maxOutputTokens());
        body.put("messages", messages(request));
        body.put("stream", stream);
        String system = instructions(request.messages());
        if (!system.isEmpty()) body.put("system", system);
        if (!request.tools().isEmpty()) {
            body.put(
                    "tools",
                    request.tools().stream().map(AnthropicMessagesModel::tool).toList());
            body.put("tool_choice", toolChoice(options.getOrDefault("tool_choice", "auto")));
        } else if (options.containsKey("tool_choice")) {
            throw new IllegalArgumentException("tool_choice requires at least one tool");
        }
        configureThinking(body, options, profile);
        configureStructuredOutput(body, request);
        if (options.keySet().stream()
                .anyMatch(key -> !key.equals("thinking")
                        && !key.equals("reasoning_token_budget")
                        && !key.equals("reasoning_effort")
                        && !key.equals("tool_choice"))) {
            throw new IllegalArgumentException("unsupported Anthropic Messages invocation option");
        }
        return Map.copyOf(body);
    }

    private static void configureStructuredOutput(Map<String, Object> body, AgentChatRequest request) {
        request.structuredOutput().ifPresent(requirement -> {
            Map<String, Object> outputConfig = new LinkedHashMap<>();
            Object configured = body.get("output_config");
            if (configured instanceof Map<?, ?> map) {
                map.forEach((key, value) -> outputConfig.put(String.valueOf(key), value));
            }
            outputConfig.put("format", Map.of("type", "json_schema", "schema", requirement.jsonSchema()));
            body.put("output_config", Map.copyOf(outputConfig));
        });
    }

    private static Map<String, Object> options(AgentChatRequest request) {
        Map<String, Object> options = new LinkedHashMap<>(request.model().invocationOptions());
        options.putAll(request.options());
        return Map.copyOf(options);
    }

    private void configureThinking(
            Map<String, Object> body, Map<String, Object> options, AnthropicMessagesDialects.Profile profile) {
        Object configured = options.get("thinking");
        if (configured == null) return;
        String mode = String.valueOf(configured);
        if ("disabled".equals(mode)) {
            if (options.containsKey("reasoning_token_budget") || options.containsKey("reasoning_effort")) {
                throw new IllegalArgumentException("disabled thinking cannot configure budget or effort");
            }
            body.put("thinking", Map.of("type", "disabled"));
            return;
        }
        if (!"enabled".equals(mode)) throw new IllegalArgumentException("Anthropic thinking mode is unsupported");
        Map<String, Object> thinking = new LinkedHashMap<>();
        thinking.put("type", "enabled");
        Object budget = options.get("reasoning_token_budget");
        if (profile == AnthropicMessagesDialects.Profile.STANDARD) {
            long value = positiveLong(budget, "reasoning_token_budget");
            thinking.put("budget_tokens", value);
        } else if (budget != null) {
            positiveLong(budget, "reasoning_token_budget");
        }
        body.put("thinking", Map.copyOf(thinking));
        Object effort = options.get("reasoning_effort");
        if (effort != null) {
            String value = String.valueOf(effort);
            List<String> allowed = profile == AnthropicMessagesDialects.Profile.DEEPSEEK
                    ? List.of("high", "max")
                    : List.of("low", "medium", "high", "max");
            if (!allowed.contains(value))
                throw new IllegalArgumentException("Anthropic reasoning effort is unsupported");
            body.put("output_config", Map.of("effort", value));
        }
        Object choice = options.get("tool_choice");
        if (choice != null) {
            String type = toolChoiceType(toolChoice(choice));
            if (!"auto".equals(type) && !"none".equals(type)) {
                throw new IllegalArgumentException("forced Anthropic tool choice is incompatible with thinking");
            }
        }
    }

    private static long positiveLong(Object value, String field) {
        if (!(value instanceof Number number) || number.longValue() < 1) {
            throw new IllegalArgumentException(field + " must be positive");
        }
        return number.longValue();
    }

    private List<Map<String, Object>> messages(AgentChatRequest request) {
        List<Map<String, Object>> result = new ArrayList<>();
        List<Map<String, Object>> pendingToolResults = new ArrayList<>();
        for (ModelMessage message : request.messages()) {
            if (message.role() == ModelMessageRole.SYSTEM || message.role() == ModelMessageRole.DEVELOPER) continue;
            if (message.role() == ModelMessageRole.TOOL) {
                pendingToolResults.add(toolResult(message));
                continue;
            }
            flushToolResults(result, pendingToolResults);
            if (message.role() == ModelMessageRole.USER) {
                result.add(
                        Map.of("role", "user", "content", List.of(Map.of("type", "text", "text", message.content()))));
            } else if (message.role() == ModelMessageRole.ASSISTANT) {
                result.add(assistantMessage(message));
            } else {
                throw new IllegalArgumentException("unsupported Anthropic message role");
            }
        }
        flushToolResults(result, pendingToolResults);
        if (result.isEmpty()) throw new IllegalArgumentException("Anthropic Messages input contains no user turns");
        return List.copyOf(result);
    }

    private static void flushToolResults(
            List<Map<String, Object>> messages, List<Map<String, Object>> pendingToolResults) {
        if (pendingToolResults.isEmpty()) return;
        messages.add(Map.of("role", "user", "content", List.copyOf(pendingToolResults)));
        pendingToolResults.clear();
    }

    private Map<String, Object> assistantMessage(ModelMessage message) {
        List<Map<String, Object>> content = new ArrayList<>();
        message.reasoning().ifPresent(reasoning -> content.addAll(decodeReasoning(reasoning)));
        if (!message.content().isEmpty()) content.add(Map.of("type", "text", "text", message.content()));
        for (ModelToolCall call : message.toolCalls()) {
            content.add(Map.of(
                    "type",
                    "tool_use",
                    "id",
                    call.providerCorrelationId().value(),
                    "name",
                    call.name(),
                    "input",
                    call.arguments()));
        }
        if (content.isEmpty()) throw new IllegalArgumentException("Anthropic assistant turn contains no content");
        return Map.of("role", "assistant", "content", List.copyOf(content));
    }

    private Map<String, Object> toolResult(ModelMessage message) {
        String value = message.toolResultData().isEmpty() ? message.content() : writeJson(message.toolResultData());
        return Map.of(
                "type",
                "tool_result",
                "tool_use_id",
                message.providerCorrelationId().orElseThrow().value(),
                "content",
                value);
    }

    private static Map<String, Object> tool(ModelToolSpecification tool) {
        return Map.of(
                "name", tool.name(),
                "description", tool.description(),
                "input_schema", tool.inputJsonSchema());
    }

    private static Map<String, Object> toolChoice(Object value) {
        if (value instanceof String text) {
            if (!List.of("auto", "none", "any").contains(text)) {
                throw new IllegalArgumentException("Anthropic tool_choice is unsupported");
            }
            return Map.of("type", text);
        }
        if (!(value instanceof Map<?, ?> raw)) {
            throw new IllegalArgumentException("Anthropic tool_choice must be a string or object");
        }
        Map<String, Object> choice = new LinkedHashMap<>();
        raw.forEach((key, item) -> choice.put(String.valueOf(key), item));
        String type = String.valueOf(choice.get("type"));
        if ("tool".equals(type)) {
            String name = String.valueOf(choice.get("name"));
            if (name.isBlank() || choice.size() != 2) {
                throw new IllegalArgumentException("named Anthropic tool_choice is invalid");
            }
            return Map.of("type", "tool", "name", name);
        }
        if (!List.of("auto", "none", "any").contains(type) || choice.size() != 1) {
            throw new IllegalArgumentException("Anthropic tool_choice object is invalid");
        }
        return Map.of("type", type);
    }

    private static String toolChoiceType(Map<String, Object> choice) {
        return String.valueOf(choice.get("type"));
    }

    private static String instructions(List<ModelMessage> messages) {
        return messages.stream()
                .filter(message ->
                        message.role() == ModelMessageRole.SYSTEM || message.role() == ModelMessageRole.DEVELOPER)
                .map(ModelMessage::content)
                .filter(value -> !value.isEmpty())
                .collect(java.util.stream.Collectors.joining("\n\n"));
    }

    private AgentChatResponse parseResponse(
            AgentChatRequest request, AnthropicMessagesDialects.Profile profile, JsonNode root) {
        try {
            return parseResponseValue(request, profile, root);
        } catch (ModelInvocationException exception) {
            throw exception;
        } catch (IllegalArgumentException exception) {
            throw malformed(request, "provider returned an invalid Anthropic Message object");
        }
    }

    private AgentChatResponse parseResponseValue(
            AgentChatRequest request, AnthropicMessagesDialects.Profile profile, JsonNode root) {
        if (!"message".equals(text(root, "type", true)) || !"assistant".equals(text(root, "role", true))) {
            throw malformed(request, "provider response is not an assistant Message");
        }
        JsonNode content = root.path("content");
        if (!content.isArray()) throw malformed(request, "Anthropic Message content must be an array");
        ParsedContent parsed = parseContent(request, profile, content);
        String stopReason = text(root, "stop_reason", true);
        ModelFinishReason finish = finishReason(request, stopReason, parsed.calls());
        return response(
                request,
                text(root, "id", true),
                optionalText(root, "model", request.model().providerModelId()),
                parsed,
                finish,
                usage(request, root.path("usage")),
                stopReason);
    }

    private ParsedContent parseContent(
            AgentChatRequest request, AnthropicMessagesDialects.Profile profile, JsonNode values) {
        StringBuilder text = new StringBuilder();
        List<ModelToolCall> calls = new ArrayList<>();
        List<Map<String, Object>> reasoning = new ArrayList<>();
        int reasoningCharacters = 0;
        for (JsonNode block : values) {
            String type = text(block, "type", true);
            switch (type) {
                case "text" -> text.append(text(block, "text", true));
                case "tool_use" ->
                    calls.add(new ModelToolCall(
                            new ProviderToolCallCorrelationId(text(block, "id", true)),
                            text(block, "name", true),
                            object(request, block.path("input"), "tool input must be a JSON object")));
                case "thinking" -> {
                    String thinking = text(block, "thinking", false);
                    String signature = text(block, "signature", true);
                    reasoningCharacters += thinking.length();
                    reasoning.add(Map.of("type", "thinking", "thinking", thinking, "signature", signature));
                }
                case "redacted_thinking" -> {
                    if (profile == AnthropicMessagesDialects.Profile.DEEPSEEK) {
                        throw malformed(request, "DeepSeek returned unsupported redacted thinking");
                    }
                    reasoning.add(Map.of("type", "redacted_thinking", "data", text(block, "data", true)));
                }
                default -> throw malformed(request, "provider returned an unsupported Anthropic Content Block");
            }
            if (text.length() + reasoningCharacters > maxResponseBytes) {
                throw malformed(request, "provider output exceeds the configured size limit");
            }
        }
        if (text.isEmpty() && calls.isEmpty()) throw malformed(request, "provider response contains no usable output");
        return new ParsedContent(text.toString(), List.copyOf(calls), List.copyOf(reasoning), reasoningCharacters);
    }

    private AgentChatResponse response(
            AgentChatRequest request,
            String id,
            String actualModel,
            ParsedContent parsed,
            ModelFinishReason finish,
            ModelUsage usage,
            String stopReason) {
        boolean retainReasoning =
                !parsed.reasoning().isEmpty() && !parsed.calls().isEmpty();
        Optional<SensitiveModelReasoning> protectedReasoning = retainReasoning
                ? Optional.of(SensitiveModelReasoning.of(encodeReasoning(parsed.reasoning())))
                : Optional.empty();
        return new AgentChatResponse(
                id,
                actualModel,
                parsed.text(),
                parsed.calls(),
                finish,
                usage,
                "",
                Map.of(
                        "stopReason", stopReason,
                        "reasoningBlocks", parsed.reasoning().size(),
                        "reasoningCharacters", parsed.reasoningCharacters()),
                protectedReasoning,
                structuredOutput(request, parsed.text(), parsed.calls(), finish));
    }

    private Optional<Map<String, Object>> structuredOutput(
            AgentChatRequest request, String content, List<ModelToolCall> toolCalls, ModelFinishReason finish) {
        if (request.structuredOutput().isEmpty() || !toolCalls.isEmpty() || finish != ModelFinishReason.STOP) {
            return Optional.empty();
        }
        try {
            JsonNode value = json.readTree(content);
            if (!value.isObject()) throw new IllegalArgumentException("structured output must be an object");
            return Optional.of(json.convertValue(value, new TypeReference<Map<String, Object>>() {}));
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

    private ModelFinishReason finishReason(AgentChatRequest request, String stopReason, List<ModelToolCall> calls) {
        return switch (stopReason) {
            case "tool_use" -> {
                if (calls.isEmpty()) throw malformed(request, "tool_use stop reason contains no tool call");
                yield ModelFinishReason.TOOL_CALLS;
            }
            case "end_turn", "stop_sequence", "pause_turn" ->
                calls.isEmpty() ? ModelFinishReason.STOP : ModelFinishReason.TOOL_CALLS;
            case "max_tokens", "model_context_window_exceeded" -> ModelFinishReason.LENGTH;
            case "refusal" -> ModelFinishReason.CONTENT_FILTER;
            default -> ModelFinishReason.UNKNOWN;
        };
    }

    private ModelUsage usage(AgentChatRequest request, JsonNode usage) {
        if (!usage.isObject()) return ModelUsage.unpriced(0, 0);
        long input = nonNegativeLong(request, usage, "input_tokens");
        long output = nonNegativeLong(request, usage, "output_tokens");
        long cacheRead = nonNegativeLong(request, usage, "cache_read_input_tokens");
        return new ModelUsage(input, output, cacheRead, Math.max(0, input - cacheRead), 0, false, 0);
    }

    private AgentChatResponse parseStream(
            AgentChatRequest request,
            AnthropicMessagesDialects.Profile profile,
            InputStream stream,
            ModelStreamSink sink)
            throws IOException {
        StreamState state = new StreamState(request, profile, sink);
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            String eventName = "";
            StringBuilder data = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                state.addBytes(line.getBytes(StandardCharsets.UTF_8).length + 1);
                if (line.isEmpty()) {
                    if (!data.isEmpty()) {
                        state.accept(eventName, data.toString());
                        eventName = "";
                        data.setLength(0);
                    }
                    continue;
                }
                if (line.startsWith(":")) continue;
                if (line.startsWith("event:")) {
                    eventName = line.substring(6).trim();
                } else if (line.startsWith("data:")) {
                    if (!data.isEmpty()) data.append('\n');
                    data.append(line.substring(5).stripLeading());
                    if (data.length() > MAX_EVENT_BYTES) {
                        throw malformed(request, "Anthropic SSE event is too large");
                    }
                }
            }
            if (!data.isEmpty()) state.accept(eventName, data.toString());
        }
        return state.finish();
    }

    private final class StreamState {
        private final AgentChatRequest request;
        private final AnthropicMessagesDialects.Profile profile;
        private final ModelStreamSink sink;
        private final Map<Integer, BlockState> blocks = new LinkedHashMap<>();
        private long emittedIndex;
        private long eventCount;
        private long bytes;
        private boolean started;
        private boolean terminal;
        private String id;
        private String model;
        private String stopReason;
        private long inputTokens;
        private long outputTokens;
        private long cacheReadTokens;

        private StreamState(AgentChatRequest request, AnthropicMessagesDialects.Profile profile, ModelStreamSink sink) {
            this.request = request;
            this.profile = profile;
            this.sink = sink;
        }

        private void addBytes(long value) {
            bytes += value;
            if (bytes > maxResponseBytes) throw malformed(request, "Anthropic SSE stream is too large");
        }

        private void accept(String eventName, String data) {
            if (++eventCount > MAX_EVENTS) throw malformed(request, "Anthropic SSE event limit exceeded");
            JsonNode event = parseJson(request, data.getBytes(StandardCharsets.UTF_8));
            String type = text(event, "type", true);
            if (eventName.isEmpty() || !eventName.equals(type)) {
                throw malformed(request, "Anthropic SSE event name does not match its payload type");
            }
            if (terminal) throw malformed(request, "Anthropic stream emitted an event after message_stop");
            switch (type) {
                case "message_start" -> start(event);
                case "content_block_start" -> blockStart(event);
                case "content_block_delta" -> blockDelta(event);
                case "content_block_stop" -> blockStop(event);
                case "message_delta" -> messageDelta(event);
                case "message_stop" -> messageStop();
                case "ping" -> {
                    // Anthropic permits ping at any point in the stream.
                }
                case "error" -> throw streamFailure(event);
                default -> {
                    // The Anthropic versioning contract permits new event types; unknown lifecycle events are ignored.
                }
            }
        }

        private void start(JsonNode event) {
            if (started) throw malformed(request, "duplicate Anthropic message_start event");
            JsonNode message = event.path("message");
            if (!message.isObject()) throw malformed(request, "message_start is missing its Message");
            id = text(message, "id", true);
            model = optionalText(message, "model", request.model().providerModelId());
            inputTokens = nonNegativeLong(request, message.path("usage"), "input_tokens");
            cacheReadTokens = nonNegativeLong(request, message.path("usage"), "cache_read_input_tokens");
            started = true;
            emit(new ModelStreamEvent.Started(request.callId(), ++emittedIndex));
        }

        private void blockStart(JsonNode event) {
            requireStarted();
            int index = nonNegativeInt(request, event, "index");
            JsonNode block = event.path("content_block");
            if (!block.isObject()) throw malformed(request, "content_block_start is missing its block");
            BlockState state =
                    switch (text(block, "type", true)) {
                        case "text" -> BlockState.text(index, text(block, "text", false));
                        case "thinking" ->
                            BlockState.thinking(index, text(block, "thinking", false), text(block, "signature", false));
                        case "redacted_thinking" -> {
                            if (profile == AnthropicMessagesDialects.Profile.DEEPSEEK) {
                                throw malformed(request, "DeepSeek returned unsupported redacted thinking");
                            }
                            yield BlockState.redacted(index, text(block, "data", true));
                        }
                        case "tool_use" ->
                            BlockState.tool(
                                    index,
                                    text(block, "id", true),
                                    text(block, "name", true),
                                    initialToolInput(request, block.path("input")));
                        default -> throw malformed(request, "provider streamed an unsupported Anthropic Content Block");
                    };
            if (blocks.putIfAbsent(index, state) != null) {
                throw malformed(request, "duplicate Anthropic Content Block index");
            }
        }

        private void blockDelta(JsonNode event) {
            requireStarted();
            int index = nonNegativeInt(request, event, "index");
            BlockState block = openBlock(index);
            JsonNode delta = event.path("delta");
            String type = text(delta, "type", true);
            switch (type) {
                case "text_delta" -> {
                    block.require(BlockType.TEXT, request);
                    String value = text(delta, "text", true);
                    block.value.append(value);
                    emit(new ModelStreamEvent.ContentDelta(request.callId(), ++emittedIndex, value));
                }
                case "thinking_delta" -> {
                    block.require(BlockType.THINKING, request);
                    String value = text(delta, "thinking", true);
                    block.value.append(value);
                    emit(new ModelStreamEvent.ReasoningDelta(request.callId(), ++emittedIndex, value));
                }
                case "signature_delta" -> {
                    block.require(BlockType.THINKING, request);
                    block.signature.append(text(delta, "signature", true));
                }
                case "input_json_delta" -> {
                    block.require(BlockType.TOOL_USE, request);
                    String value = text(delta, "partial_json", true);
                    block.value.append(value);
                    emit(new ModelStreamEvent.ToolCallDelta(
                            request.callId(), ++emittedIndex, 0, index, block.id, block.name, value));
                }
                default -> throw malformed(request, "provider streamed an unsupported Anthropic Content Block delta");
            }
            if (block.value.length() + block.signature.length() > maxResponseBytes) {
                throw malformed(request, "Anthropic Content Block exceeds the configured size limit");
            }
        }

        private void blockStop(JsonNode event) {
            requireStarted();
            BlockState block = openBlock(nonNegativeInt(request, event, "index"));
            block.closed = true;
            if (block.type == BlockType.THINKING && block.signature.isEmpty()) {
                throw malformed(request, "Anthropic thinking block is missing its signature");
            }
            if (block.type == BlockType.TOOL_USE) {
                object(
                        request,
                        parseJson(request, block.value.toString().getBytes(StandardCharsets.UTF_8)),
                        "tool input must be a JSON object");
            }
        }

        private void messageDelta(JsonNode event) {
            requireStarted();
            JsonNode delta = event.path("delta");
            stopReason = text(delta, "stop_reason", true);
            JsonNode usage = event.path("usage");
            outputTokens = nonNegativeLong(request, usage, "output_tokens");
            long reportedInput = nonNegativeLong(request, usage, "input_tokens");
            if (reportedInput > 0) inputTokens = reportedInput;
            long reportedCache = nonNegativeLong(request, usage, "cache_read_input_tokens");
            if (reportedCache > 0) cacheReadTokens = reportedCache;
        }

        private void messageStop() {
            requireStarted();
            if (stopReason == null || stopReason.isBlank()) {
                throw malformed(request, "Anthropic stream stopped without a stop reason");
            }
            if (blocks.values().stream().anyMatch(block -> !block.closed)) {
                throw malformed(request, "Anthropic stream stopped with an open Content Block");
            }
            terminal = true;
            emit(new ModelStreamEvent.UsageReported(request.callId(), ++emittedIndex, currentUsage()));
        }

        private BlockState openBlock(int index) {
            BlockState block = blocks.get(index);
            if (block == null || block.closed) throw malformed(request, "Anthropic Content Block is not open");
            return block;
        }

        private void requireStarted() {
            if (!started) throw malformed(request, "Anthropic stream event arrived before message_start");
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
            if (!terminal) throw malformed(request, "Anthropic stream ended without message_stop");
            StringBuilder visible = new StringBuilder();
            List<ModelToolCall> calls = new ArrayList<>();
            List<Map<String, Object>> reasoning = new ArrayList<>();
            int reasoningCharacters = 0;
            for (BlockState block : blocks.values().stream()
                    .sorted(Comparator.comparingInt(value -> value.index))
                    .toList()) {
                switch (block.type) {
                    case TEXT -> visible.append(block.value);
                    case TOOL_USE ->
                        calls.add(new ModelToolCall(
                                new ProviderToolCallCorrelationId(block.id),
                                block.name,
                                object(
                                        request,
                                        parseJson(
                                                request, block.value.toString().getBytes(StandardCharsets.UTF_8)),
                                        "tool input must be a JSON object")));
                    case THINKING -> {
                        reasoningCharacters += block.value.length();
                        reasoning.add(Map.of(
                                "type",
                                "thinking",
                                "thinking",
                                block.value.toString(),
                                "signature",
                                block.signature.toString()));
                    }
                    case REDACTED_THINKING ->
                        reasoning.add(Map.of("type", "redacted_thinking", "data", block.value.toString()));
                }
            }
            if (visible.isEmpty() && calls.isEmpty()) {
                throw malformed(request, "Anthropic stream contains no usable output");
            }
            ParsedContent parsed = new ParsedContent(
                    visible.toString(), List.copyOf(calls), List.copyOf(reasoning), reasoningCharacters);
            return response(
                    request, id, model, parsed, finishReason(request, stopReason, calls), currentUsage(), stopReason);
        }

        private ModelUsage currentUsage() {
            return new ModelUsage(
                    inputTokens,
                    outputTokens,
                    cacheReadTokens,
                    Math.max(0, inputTokens - cacheReadTokens),
                    0,
                    false,
                    0);
        }

        private ModelInvocationException streamFailure(JsonNode event) {
            String type = text(event.path("error"), "type", false);
            ModelErrorCategory category =
                    switch (type) {
                        case "authentication_error" -> ModelErrorCategory.AUTHENTICATION_FAILED;
                        case "permission_error" -> ModelErrorCategory.PERMISSION_DENIED;
                        case "rate_limit_error" -> ModelErrorCategory.RATE_LIMITED;
                        case "overloaded_error", "api_error", "timeout_error" ->
                            ModelErrorCategory.PROVIDER_UNAVAILABLE;
                        case "invalid_request_error" -> ModelErrorCategory.INVALID_REQUEST;
                        case "not_found_error" -> ModelErrorCategory.MODEL_NOT_FOUND;
                        default -> ModelErrorCategory.UNKNOWN_PROVIDER_ERROR;
                    };
            return failure(
                    request,
                    category,
                    category == ModelErrorCategory.RATE_LIMITED || category == ModelErrorCategory.PROVIDER_UNAVAILABLE,
                    0,
                    type.isBlank() ? "stream_error" : type,
                    "provider reported an Anthropic stream error",
                    null);
        }
    }

    private List<Map<String, Object>> decodeReasoning(SensitiveModelReasoning reasoning) {
        return reasoning.use(value -> {
            try {
                JsonNode root = json.readTree(value);
                if (!REASONING_FORMAT.equals(text(root, "format", true))
                        || !root.path("blocks").isArray()) {
                    throw new IllegalArgumentException("protected reasoning is not an Anthropic continuation");
                }
                List<Map<String, Object>> blocks = new ArrayList<>();
                for (JsonNode block : root.path("blocks")) {
                    String type = text(block, "type", true);
                    if ("thinking".equals(type)) {
                        blocks.add(Map.of(
                                "type",
                                "thinking",
                                "thinking",
                                text(block, "thinking", false),
                                "signature",
                                text(block, "signature", true)));
                    } else if ("redacted_thinking".equals(type)) {
                        blocks.add(Map.of("type", "redacted_thinking", "data", text(block, "data", true)));
                    } else {
                        throw new IllegalArgumentException("protected reasoning contains an unsupported block");
                    }
                }
                if (blocks.isEmpty()) throw new IllegalArgumentException("protected reasoning contains no blocks");
                return List.copyOf(blocks);
            } catch (JsonProcessingException exception) {
                throw new IllegalArgumentException("protected reasoning is malformed", exception);
            }
        });
    }

    private String encodeReasoning(List<Map<String, Object>> blocks) {
        return writeJson(Map.of("format", REASONING_FORMAT, "blocks", blocks));
    }

    private static String initialToolInput(AgentChatRequest request, JsonNode input) {
        if (input.isMissingNode() || input.isNull() || (input.isObject() && input.isEmpty())) return "";
        if (!input.isObject()) throw malformed(request, "initial tool input must be a JSON object");
        return input.toString();
    }

    private Map<String, Object> object(AgentChatRequest request, JsonNode value, String safeMessage) {
        if (!value.isObject()) throw malformed(request, safeMessage);
        return json.convertValue(value, new TypeReference<>() {});
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

    private static URI messagesUri(URI base) {
        String value = base.toString();
        while (value.endsWith("/")) value = value.substring(0, value.length() - 1);
        if (value.endsWith("/v1/messages")) return URI.create(value);
        return URI.create(value + "/v1/messages");
    }

    private static void requireContentType(AgentChatRequest request, HttpResponse<?> response, String expected) {
        String contentType = response.headers().firstValue("Content-Type").orElse("");
        if (!contentType.toLowerCase(Locale.ROOT).contains(expected)) {
            throw failure(
                    request,
                    ModelErrorCategory.MALFORMED_RESPONSE,
                    false,
                    response.statusCode(),
                    "unexpected_content_type",
                    "provider returned an unexpected content type",
                    null);
        }
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
                && (!required || !value.textValue().isEmpty())) {
            return value.textValue();
        }
        if (!required) return "";
        throw new IllegalArgumentException("provider response is missing " + field);
    }

    private static String optionalText(JsonNode node, String field, String fallback) {
        String value = text(node, field, false);
        return value.isEmpty() ? fallback : value;
    }

    private static ModelInvocationException httpFailure(AgentChatRequest request, int status) {
        ModelErrorCategory category =
                switch (status) {
                    case 400, 413, 422 -> ModelErrorCategory.INVALID_REQUEST;
                    case 401 -> ModelErrorCategory.AUTHENTICATION_FAILED;
                    case 403 -> ModelErrorCategory.PERMISSION_DENIED;
                    case 404 -> ModelErrorCategory.MODEL_NOT_FOUND;
                    case 408, 504 -> ModelErrorCategory.TIMEOUT;
                    case 429 -> ModelErrorCategory.RATE_LIMITED;
                    case 500, 502, 503, 529 -> ModelErrorCategory.PROVIDER_UNAVAILABLE;
                    default -> ModelErrorCategory.UNKNOWN_PROVIDER_ERROR;
                };
        boolean retryable = status == 408 || status == 429 || status == 504 || status == 529 || status >= 500;
        return failure(
                request, category, retryable, status, "http_" + status, "model provider rejected the request", null);
    }

    private static ModelInvocationException malformed(AgentChatRequest request, String message) {
        return failure(request, ModelErrorCategory.MALFORMED_RESPONSE, false, 0, "malformed_response", message, null);
    }

    private static ModelInvocationException failure(
            AgentChatRequest request,
            ModelErrorCategory category,
            boolean retryable,
            int status,
            String code,
            String safeMessage,
            Throwable cause) {
        return new ModelInvocationException(category, retryable, status, code, request.callId(), safeMessage, cause);
    }

    private enum BlockType {
        TEXT,
        THINKING,
        REDACTED_THINKING,
        TOOL_USE
    }

    private static final class BlockState {
        private final int index;
        private final BlockType type;
        private final String id;
        private final String name;
        private final StringBuilder value;
        private final StringBuilder signature;
        private boolean closed;

        private BlockState(int index, BlockType type, String id, String name, String value, String signature) {
            this.index = index;
            this.type = type;
            this.id = id;
            this.name = name;
            this.value = new StringBuilder(value);
            this.signature = new StringBuilder(signature);
        }

        private static BlockState text(int index, String value) {
            return new BlockState(index, BlockType.TEXT, "", "", value, "");
        }

        private static BlockState thinking(int index, String value, String signature) {
            return new BlockState(index, BlockType.THINKING, "", "", value, signature);
        }

        private static BlockState redacted(int index, String data) {
            return new BlockState(index, BlockType.REDACTED_THINKING, "", "", data, "");
        }

        private static BlockState tool(int index, String id, String name, String input) {
            return new BlockState(index, BlockType.TOOL_USE, id, name, input, "");
        }

        private void require(BlockType expected, AgentChatRequest request) {
            if (type != expected) throw malformed(request, "Anthropic Content Block delta type does not match");
        }
    }

    private record ParsedContent(
            String text, List<ModelToolCall> calls, List<Map<String, Object>> reasoning, int reasoningCharacters) {}
}
