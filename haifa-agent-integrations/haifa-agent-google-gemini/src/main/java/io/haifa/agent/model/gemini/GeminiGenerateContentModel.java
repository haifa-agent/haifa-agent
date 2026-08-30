package io.haifa.agent.model.gemini;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.haifa.agent.core.run.StructuredOutputRequirement;
import io.haifa.agent.core.tool.ProviderToolCallCorrelationId;
import io.haifa.agent.model.api.AgentChatModel;
import io.haifa.agent.model.api.AgentChatRequest;
import io.haifa.agent.model.api.AgentChatResponse;
import io.haifa.agent.model.api.AudioDataPart;
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
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.InetAddress;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/** Official Gemini generateContent adapter with bounded CLIProxyAPI and direct Antigravity CloudCode PA dialects. */
public final class GeminiGenerateContentModel implements AgentChatModel {
    public static final String ADAPTER_VERSION = "1.0.0";
    public static final String CLIPROXY_CREDENTIAL_REF = "env://HAIFA_CLIPROXYAPI_API_KEY";
    private static final int DEFAULT_MAX_RESPONSE_BYTES = 4 * 1024 * 1024;
    private static final int MAX_SSE_LINE_CHARS = 1024 * 1024;
    private static final int MAX_INLINE_MEDIA_BYTES = 12 * 1024 * 1024;
    private static final Set<String> LOOPBACK_NAMES = Set.of("localhost", "127.0.0.1", "::1", "0:0:0:0:0:0:0:1");
    private static final Set<String> ANTIGRAVITY_DIRECT_HOSTS =
            Set.of("cloudcode-pa.googleapis.com", "daily-cloudcode-pa.googleapis.com");

    private final HttpClient http;
    private final ObjectMapper json;
    private final CredentialResolver credentials;
    private final boolean allowInsecureLoopback;
    private final boolean allowStandardLoopbackStub;
    private final int maxResponseBytes;
    private final AntigravityCloudCodeProjectResolver trustedProjectResolver;

    public GeminiGenerateContentModel(HttpClient http, ObjectMapper json, CredentialResolver credentials) {
        this(http, json, credentials, false, DEFAULT_MAX_RESPONSE_BYTES, false, ignored -> Optional.empty());
    }

    public GeminiGenerateContentModel(
            HttpClient http,
            ObjectMapper json,
            CredentialResolver credentials,
            AntigravityCloudCodeProjectResolver trustedProjectResolver) {
        this(http, json, credentials, false, DEFAULT_MAX_RESPONSE_BYTES, false, trustedProjectResolver);
    }

    public GeminiGenerateContentModel(
            HttpClient http,
            ObjectMapper json,
            CredentialResolver credentials,
            boolean allowInsecureLoopback,
            int maxResponseBytes) {
        this(http, json, credentials, allowInsecureLoopback, maxResponseBytes, false, ignored -> Optional.empty());
    }

    GeminiGenerateContentModel(
            HttpClient http,
            ObjectMapper json,
            CredentialResolver credentials,
            boolean allowInsecureLoopback,
            int maxResponseBytes,
            boolean allowStandardLoopbackStub) {
        this(
                http,
                json,
                credentials,
                allowInsecureLoopback,
                maxResponseBytes,
                allowStandardLoopbackStub,
                ignored -> Optional.empty());
    }

    public GeminiGenerateContentModel(
            HttpClient http,
            ObjectMapper json,
            CredentialResolver credentials,
            boolean allowInsecureLoopback,
            int maxResponseBytes,
            boolean allowStandardLoopbackStub,
            AntigravityCloudCodeProjectResolver trustedProjectResolver) {
        this.http = Objects.requireNonNull(http, "http must not be null");
        this.json = Objects.requireNonNull(json, "json must not be null");
        this.credentials = Objects.requireNonNull(credentials, "credentials must not be null");
        this.allowInsecureLoopback = allowInsecureLoopback;
        this.allowStandardLoopbackStub = allowStandardLoopbackStub;
        this.trustedProjectResolver =
                Objects.requireNonNull(trustedProjectResolver, "trustedProjectResolver must not be null");
        if (maxResponseBytes < 1) throw new IllegalArgumentException("maxResponseBytes must be positive");
        this.maxResponseBytes = maxResponseBytes;
    }

    @Override
    public AgentChatResponse invoke(AgentChatRequest request) {
        Objects.requireNonNull(request, "request must not be null");
        validateSelection(request);
        ResolvedCredential credential = resolveCredential(request);
        byte[] body = serializeRequest(request);
        HttpRequest httpRequest = requestBuilder(request, credential, false)
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
                throw httpFailure(request, response.statusCode(), responseBody, response.headers());
            }
            requireContentType(request, response.headers(), "application/json", response.statusCode());
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
        if (!request.model().nativeStreaming()) return AgentChatModel.super.invokeStreaming(request, sink);
        ResolvedCredential credential = resolveCredential(request);
        HttpRequest httpRequest = requestBuilder(request, credential, true)
                .POST(HttpRequest.BodyPublishers.ofByteArray(serializeRequest(request)))
                .build();
        boolean outputObserved = false;
        try {
            HttpResponse<InputStream> response = http.send(httpRequest, HttpResponse.BodyHandlers.ofInputStream());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                byte[] error;
                try (InputStream stream = response.body()) {
                    error = stream.readNBytes(maxResponseBytes + 1);
                }
                throw httpFailure(
                        request,
                        response.statusCode(),
                        error.length > maxResponseBytes ? new byte[0] : error,
                        response.headers());
            }
            requireContentType(request, response.headers(), "text/event-stream", response.statusCode());
            long event = 1;
            if (sink.emit(new ModelStreamEvent.Started(request.callId(), event++)) == ModelStreamControl.CANCEL) {
                try (InputStream ignored = response.body()) {}
                throw failure(
                        request,
                        ModelErrorCategory.CANCELLED,
                        false,
                        0,
                        "stream_cancelled",
                        "model stream was cancelled",
                        null);
            }
            StreamAggregate aggregate = new StreamAggregate(request.model().providerModelId());
            int totalBytes = 0;
            try (InputStream stream = response.body();
                    BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    totalBytes += line.getBytes(StandardCharsets.UTF_8).length + 1;
                    if (line.length() > MAX_SSE_LINE_CHARS || totalBytes > maxResponseBytes) {
                        throw failure(
                                request,
                                ModelErrorCategory.MALFORMED_RESPONSE,
                                false,
                                response.statusCode(),
                                "stream_too_large",
                                "provider stream exceeds the configured size limit",
                                null);
                    }
                    if (!line.startsWith("data:")) continue;
                    String data = line.substring(5).trim();
                    if (data.isEmpty() || "[DONE]".equals(data)) continue;
                    JsonNode frame = parseJson(request, data.getBytes(StandardCharsets.UTF_8));
                    StreamDelta delta = aggregate.accept(providerResponsePayload(request, frame));
                    if (!delta.text().isEmpty()) {
                        outputObserved = true;
                        if (sink.emit(new ModelStreamEvent.ContentDelta(request.callId(), event++, delta.text()))
                                == ModelStreamControl.CANCEL) {
                            throw failure(
                                    request,
                                    ModelErrorCategory.CANCELLED,
                                    false,
                                    0,
                                    "stream_cancelled",
                                    "model stream was cancelled",
                                    null,
                                    null,
                                    true);
                        }
                    }
                }
            }
            AgentChatResponse result = aggregate.finish(request, this);
            sink.emit(new ModelStreamEvent.UsageReported(request.callId(), event, result.usage()));
            return result;
        } catch (HttpTimeoutException exception) {
            throw failure(
                    request,
                    outputObserved ? ModelErrorCategory.PARTIAL_RESPONSE : ModelErrorCategory.TIMEOUT,
                    !outputObserved,
                    0,
                    "timeout",
                    "model stream timed out",
                    exception,
                    null,
                    outputObserved);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw failure(
                    request,
                    ModelErrorCategory.CANCELLED,
                    false,
                    0,
                    "interrupted",
                    "model stream was cancelled",
                    exception,
                    null,
                    outputObserved);
        } catch (ModelInvocationException exception) {
            throw outputObserved && !exception.outputObserved() ? exception.withOutputObserved() : exception;
        } catch (IOException exception) {
            throw failure(
                    request,
                    outputObserved ? ModelErrorCategory.PARTIAL_RESPONSE : ModelErrorCategory.TRANSPORT_ERROR,
                    !outputObserved,
                    0,
                    "stream_io_failure",
                    "model provider stream was interrupted",
                    exception,
                    null,
                    outputObserved);
        }
    }

    private HttpRequest.Builder requestBuilder(
            AgentChatRequest request, ResolvedCredential credential, boolean streaming) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(invocationUri(request, streaming))
                .timeout(request.timeout())
                .header("Content-Type", "application/json")
                .header("Accept", streaming ? "text/event-stream" : "application/json");
        String secret = validateSecret(credential.value());
        if (GeminiDialects.CLIPROXYAPI_ANTIGRAVITY.equals(request.model().dialect())) {
            builder.header("Authorization", "Bearer " + secret);
        } else if (GeminiDialects.ANTIGRAVITY_DIRECT.equals(request.model().dialect())) {
            builder.header("Authorization", "Bearer " + secret).header("User-Agent", "Antigravity");
        } else {
            builder.header("x-goog-api-key", secret);
        }
        return builder;
    }

    private URI invocationUri(AgentChatRequest request, boolean streaming) {
        if (GeminiDialects.ANTIGRAVITY_DIRECT.equals(request.model().dialect())) {
            String suffix = streaming ? ":streamGenerateContent?alt=sse" : ":generateContent";
            return URI.create(request.model().endpoint().toString() + suffix);
        }
        String model = URLEncoder.encode(request.model().providerModelId(), StandardCharsets.UTF_8)
                .replace("+", "%20");
        String suffix = "/models/" + model + (streaming ? ":streamGenerateContent?alt=sse" : ":generateContent");
        return URI.create(request.model().endpoint().toString() + suffix);
    }

    private byte[] serializeRequest(AgentChatRequest request) {
        try {
            return json.writeValueAsBytes(requestBody(request));
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
    }

    private Map<String, Object> requestBody(AgentChatRequest request) {
        Map<String, Object> innerBody = buildInnerRequestBody(request);
        if (GeminiDialects.ANTIGRAVITY_DIRECT.equals(request.model().dialect())) {
            innerBody.put("sessionId", antigravitySessionId(request));
            if (innerBody.get("generationConfig") instanceof Map<?, ?> generation) {
                generation.remove("maxOutputTokens");
            }
            Map<String, Object> directBody = new LinkedHashMap<>();
            String project = resolveProject(request);
            directBody.put("project", project);
            directBody.put("model", request.model().providerModelId());
            directBody.put("userAgent", "antigravity");
            directBody.put("requestType", "agent");
            directBody.put("requestId", "agent-" + request.callId().value());
            directBody.put("request", innerBody);
            return directBody;
        }
        return innerBody;
    }

    private static String antigravitySessionId(AgentChatRequest request) {
        long hash = 0xcbf29ce484222325L;
        String runId = request.runId().value();
        for (int index = 0; index < runId.length(); index++) {
            hash ^= runId.charAt(index);
            hash *= 0x100000001b3L;
        }
        return "-" + Long.toUnsignedString(hash & Long.MAX_VALUE);
    }

    private String resolveProject(AgentChatRequest request) {
        if (request.options().containsKey("project")
                || request.model().invocationOptions().containsKey("project")
                || request.model().providerOptions().containsKey("project")) {
            throw failure(
                    request,
                    ModelErrorCategory.INVALID_REQUEST,
                    false,
                    0,
                    "project_injection_forbidden",
                    "Antigravity project injection via request or model options is forbidden",
                    null);
        }
        String resolved = trustedProjectResolver
                .resolveProject(request.model().credentialRef())
                .orElseThrow(() -> failure(
                        request,
                        ModelErrorCategory.AUTHENTICATION_FAILED,
                        false,
                        0,
                        "antigravity_project_unavailable",
                        "trusted Antigravity project is unavailable for the selected credential",
                        null));
        if (resolved == null
                || resolved.isBlank()
                || resolved.indexOf('\r') >= 0
                || resolved.indexOf('\n') >= 0
                || resolved.indexOf('\0') >= 0) {
            throw failure(
                    request,
                    ModelErrorCategory.AUTHENTICATION_FAILED,
                    false,
                    0,
                    "antigravity_project_invalid",
                    "trusted Antigravity project is invalid",
                    null);
        }
        return resolved.trim();
    }

    private Map<String, Object> buildInnerRequestBody(AgentChatRequest request) {
        List<Map<String, Object>> contents = new ArrayList<>();
        List<Map<String, Object>> systemParts = new ArrayList<>();
        Map<String, CallIdentity> calls = new LinkedHashMap<>();
        List<ModelMessage> messages = request.messages();
        int inlineMediaBytes = 0;
        for (int index = 0; index < messages.size(); index++) {
            ModelMessage message = messages.get(index);
            if (message.role() == ModelMessageRole.SYSTEM) {
                systemParts.add(Map.of("text", message.content()));
            } else if (message.role() == ModelMessageRole.USER) {
                List<Map<String, Object>> parts = new ArrayList<>();
                if (!message.content().isBlank()) parts.add(Map.of("text", message.content()));
                for (var image : message.images()) {
                    if (image instanceof ImageUrlPart) {
                        throw new IllegalArgumentException("Gemini image URLs are not supported; upload image bytes");
                    }
                    ImageDataPart data = (ImageDataPart) image;
                    byte[] bytes = data.bytes();
                    inlineMediaBytes += bytes.length;
                    parts.add(inlineData(data.mediaType(), bytes));
                }
                for (var audio : message.audios()) {
                    AudioDataPart data = (AudioDataPart) audio;
                    byte[] bytes = data.bytes();
                    inlineMediaBytes += bytes.length;
                    parts.add(inlineData(data.mediaType(), bytes));
                }
                if (inlineMediaBytes > MAX_INLINE_MEDIA_BYTES) {
                    throw new IllegalArgumentException("Gemini inline media exceeds the bounded request limit");
                }
                if (parts.isEmpty()) throw new IllegalArgumentException("Gemini user message must not be empty");
                contents.add(content("user", parts));
            } else if (message.role() == ModelMessageRole.ASSISTANT) {
                List<Map<String, Object>> parts;
                if (message.toolCalls().isEmpty()) {
                    parts = List.of(Map.of("text", message.content()));
                } else {
                    parts = continuationParts(request, message);
                    for (ModelToolCall call : message.toolCalls()) {
                        calls.put(
                                call.providerCorrelationId().value(), new CallIdentity(call.name(), call.arguments()));
                    }
                }
                contents.add(content("model", parts));
            } else {
                List<Map<String, Object>> parts = new ArrayList<>();
                while (index < messages.size() && messages.get(index).role() == ModelMessageRole.TOOL) {
                    ModelMessage tool = messages.get(index);
                    String id = tool.providerCorrelationId().orElseThrow().value();
                    CallIdentity call = calls.get(id);
                    if (call == null) {
                        throw failure(
                                request,
                                ModelErrorCategory.INVALID_REQUEST,
                                false,
                                0,
                                "gemini_tool_call_unmatched",
                                "tool result does not match a preceding function call",
                                null);
                    }
                    Map<String, Object> response = tool.toolResultData().isEmpty()
                            ? Map.of("output", tool.content(), "truncated", tool.toolResultTruncated())
                            : tool.toolResultData();
                    parts.add(Map.of("functionResponse", Map.of("id", id, "name", call.name(), "response", response)));
                    index++;
                }
                index--;
                contents.add(content("user", parts));
            }
        }
        validateContents(request, contents);
        Map<String, Object> body = new LinkedHashMap<>();
        if (!systemParts.isEmpty()) body.put("systemInstruction", Map.of("parts", systemParts));
        body.put("contents", contents);
        if (!request.tools().isEmpty()) {
            List<Map<String, Object>> declarations =
                    request.tools().stream().map(this::toolDeclaration).toList();
            List<String> allowed =
                    request.tools().stream().map(ModelToolSpecification::name).toList();
            body.put("tools", List.of(Map.of("functionDeclarations", declarations)));
            body.put(
                    "toolConfig",
                    Map.of("functionCallingConfig", Map.of("mode", "AUTO", "allowedFunctionNames", allowed)));
        }
        Map<String, Object> generation = new LinkedHashMap<>();
        generation.put("maxOutputTokens", request.maxOutputTokens());
        request.structuredOutput().ifPresent(requirement -> addStructuredOutput(generation, requirement));
        body.put("generationConfig", generation);
        if (GeminiDialects.CLIPROXYAPI_ANTIGRAVITY.equals(request.model().dialect())) {
            body.put("safetySettings", safetySettings());
        }
        return body;
    }

    private void validateContents(AgentChatRequest request, List<Map<String, Object>> contents) {
        if (contents.isEmpty()) {
            throw failure(
                    request,
                    ModelErrorCategory.INVALID_REQUEST,
                    false,
                    0,
                    "gemini_contents_empty",
                    "Gemini contents must not be empty",
                    null);
        }
        Map<String, Object> firstContent = contents.getFirst();
        if (!"user".equals(firstContent.get("role"))) {
            throw failure(
                    request,
                    ModelErrorCategory.INVALID_REQUEST,
                    false,
                    0,
                    "gemini_turn_anchor_missing",
                    "Gemini contents must begin with a user turn anchor",
                    null);
        }
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> firstParts = (List<Map<String, Object>>) firstContent.get("parts");
        if (firstParts == null
                || firstParts.isEmpty()
                || firstParts.stream().allMatch(part -> part.containsKey("functionResponse"))) {
            throw failure(
                    request,
                    ModelErrorCategory.INVALID_REQUEST,
                    false,
                    0,
                    "gemini_turn_anchor_missing",
                    "Gemini contents must begin with a user message containing user content",
                    null);
        }

        Set<String> pendingCallIds = new LinkedHashSet<>();
        for (Map<String, Object> content : contents) {
            String role = (String) content.get("role");
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> parts = (List<Map<String, Object>>) content.get("parts");
            if (parts == null || parts.isEmpty()) {
                throw failure(
                        request,
                        ModelErrorCategory.INVALID_REQUEST,
                        false,
                        0,
                        "gemini_content_parts_empty",
                        "Gemini content parts must not be empty",
                        null);
            }

            if ("model".equals(role)) {
                if (!pendingCallIds.isEmpty()) {
                    throw failure(
                            request,
                            ModelErrorCategory.INVALID_REQUEST,
                            false,
                            0,
                            "gemini_tool_call_unmatched",
                            "preceding function calls missing function responses before next model turn",
                            null);
                }
                for (Map<String, Object> part : parts) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> functionCall = (Map<String, Object>) part.get("functionCall");
                    if (functionCall != null) {
                        String id = (String) functionCall.get("id");
                        if (id != null) {
                            pendingCallIds.add(id);
                        }
                    }
                }
            } else if ("user".equals(role)) {
                List<String> responseIds = new ArrayList<>();
                for (Map<String, Object> part : parts) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> functionResponse = (Map<String, Object>) part.get("functionResponse");
                    if (functionResponse != null) {
                        String id = (String) functionResponse.get("id");
                        if (id != null) {
                            responseIds.add(id);
                        }
                    }
                }
                if (!responseIds.isEmpty()) {
                    if (pendingCallIds.isEmpty()) {
                        throw failure(
                                request,
                                ModelErrorCategory.INVALID_REQUEST,
                                false,
                                0,
                                "gemini_tool_call_unmatched",
                                "orphan function response without preceding model function call",
                                null);
                    }
                    for (String resId : responseIds) {
                        if (!pendingCallIds.remove(resId)) {
                            throw failure(
                                    request,
                                    ModelErrorCategory.INVALID_REQUEST,
                                    false,
                                    0,
                                    "gemini_tool_call_unmatched",
                                    "function response id does not match preceding function call: " + resId,
                                    null);
                        }
                    }
                }
            }
        }
        if (!pendingCallIds.isEmpty()) {
            throw failure(
                    request,
                    ModelErrorCategory.INVALID_REQUEST,
                    false,
                    0,
                    "gemini_tool_call_unmatched",
                    "trailing function calls without function responses",
                    null);
        }
    }

    private List<Map<String, Object>> continuationParts(AgentChatRequest request, ModelMessage message) {
        SensitiveModelReasoning protectedState = message.reasoning()
                .orElseThrow(() -> new IllegalArgumentException("Gemini function-call continuation is missing"));
        try {
            JsonNode envelope = protectedState.use(value -> {
                try {
                    return json.readTree(value);
                } catch (JsonProcessingException exception) {
                    throw new InvalidContinuationException(exception);
                }
            });
            if (envelope.path("version").asInt() != 1 || !envelope.path("parts").isArray()) {
                throw new InvalidContinuationException(null);
            }
            List<Map<String, Object>> parts = json.convertValue(
                    envelope.path("parts"),
                    json.getTypeFactory()
                            .constructCollectionType(
                                    List.class,
                                    json.getTypeFactory().constructMapType(Map.class, String.class, Object.class)));
            List<CallIdentity> expected = extractCalls(parts, true);
            if (expected.size() != message.toolCalls().size()) throw new InvalidContinuationException(null);
            for (int index = 0; index < expected.size(); index++) {
                ModelToolCall actual = message.toolCalls().get(index);
                CallIdentity frozen = expected.get(index);
                if (!actual.providerCorrelationId().value().equals(frozen.id())
                        || !actual.name().equals(frozen.name())) throw new InvalidContinuationException(null);
            }
            return parts;
        } catch (InvalidContinuationException | IllegalArgumentException exception) {
            throw failure(
                    request,
                    ModelErrorCategory.INVALID_REQUEST,
                    false,
                    0,
                    "invalid_gemini_continuation",
                    "protected Gemini continuation is missing or invalid",
                    null);
        }
    }

    private Map<String, Object> toolDeclaration(ModelToolSpecification tool) {
        return Map.of(
                "name", tool.name(), "description", tool.description(), "parametersJsonSchema", tool.inputJsonSchema());
    }

    private static void addStructuredOutput(Map<String, Object> generation, StructuredOutputRequirement requirement) {
        generation.put("responseMimeType", "application/json");
        generation.put("responseJsonSchema", requirement.jsonSchema());
    }

    private static List<Map<String, String>> safetySettings() {
        return List.of(
                        "HARM_CATEGORY_HARASSMENT",
                        "HARM_CATEGORY_HATE_SPEECH",
                        "HARM_CATEGORY_SEXUALLY_EXPLICIT",
                        "HARM_CATEGORY_DANGEROUS_CONTENT",
                        "HARM_CATEGORY_CIVIC_INTEGRITY")
                .stream()
                .map(category -> Map.of("category", category, "threshold", "BLOCK_MEDIUM_AND_ABOVE"))
                .toList();
    }

    private static Map<String, Object> content(String role, List<Map<String, Object>> parts) {
        return Map.of("role", role, "parts", parts);
    }

    private static Map<String, Object> inlineData(String mediaType, byte[] bytes) {
        return Map.of(
                "inlineData",
                Map.of("mimeType", mediaType, "data", Base64.getEncoder().encodeToString(bytes)));
    }

    private AgentChatResponse parseResponse(AgentChatRequest request, byte[] body) {
        JsonNode root = providerResponsePayload(request, parseJson(request, body));
        ParsedCandidate parsed = parseCandidate(request, root, true);
        return response(request, root, parsed);
    }

    private JsonNode providerResponsePayload(AgentChatRequest request, JsonNode root) {
        if (!GeminiDialects.ANTIGRAVITY_DIRECT.equals(request.model().dialect())) return root;
        JsonNode response = root.path("response");
        if (response.isObject()) return response;
        throw failure(
                request,
                ModelErrorCategory.MALFORMED_RESPONSE,
                false,
                200,
                "invalid_antigravity_response_envelope",
                "Antigravity response envelope has no response object",
                null);
    }

    private AgentChatResponse response(AgentChatRequest request, JsonNode root, ParsedCandidate parsed) {
        ModelUsage usage = usage(root.path("usageMetadata"));
        Optional<Map<String, Object>> structured = Optional.empty();
        if (request.structuredOutput().isPresent() && parsed.calls().isEmpty()) {
            try {
                structured = Optional.of(json.readValue(parsed.text(), Map.class));
            } catch (JsonProcessingException exception) {
                throw failure(
                        request,
                        ModelErrorCategory.MALFORMED_RESPONSE,
                        false,
                        200,
                        "invalid_structured_output",
                        "provider returned invalid structured output",
                        null);
            }
        }
        Optional<SensitiveModelReasoning> continuation =
                parsed.calls().isEmpty() ? Optional.empty() : Optional.of(encodeContinuation(request, parsed.parts()));
        return new AgentChatResponse(
                textOr(root.path("responseId"), request.callId().value()),
                textOr(root.path("modelVersion"), request.model().providerModelId()),
                parsed.text(),
                parsed.calls(),
                parsed.finishReason(),
                usage,
                "",
                Map.of("provider", "google-gemini", "candidateIndex", 0),
                continuation,
                structured);
    }

    private ParsedCandidate parseCandidate(AgentChatRequest request, JsonNode root, boolean requireFinish) {
        JsonNode candidates = root.path("candidates");
        if (!candidates.isArray() || candidates.size() != 1) {
            throw failure(
                    request,
                    ModelErrorCategory.MALFORMED_RESPONSE,
                    false,
                    200,
                    "invalid_candidates",
                    "provider response must contain exactly one candidate",
                    null);
        }
        JsonNode candidate = candidates.get(0);
        JsonNode partNodes = candidate.path("content").path("parts");
        if (!partNodes.isArray())
            throw failure(
                    request,
                    ModelErrorCategory.MALFORMED_RESPONSE,
                    false,
                    200,
                    "invalid_parts",
                    "provider response candidate has no parts",
                    null);
        List<Map<String, Object>> parts = json.convertValue(
                partNodes,
                json.getTypeFactory()
                        .constructCollectionType(
                                List.class,
                                json.getTypeFactory().constructMapType(Map.class, String.class, Object.class)));
        StringBuilder text = new StringBuilder();
        for (JsonNode part : partNodes)
            if (part.has("text") && !part.path("thought").asBoolean(false))
                text.append(part.path("text").asText());
        List<CallIdentity> identities;
        try {
            identities = extractCalls(parts, false);
        } catch (IllegalArgumentException exception) {
            throw failure(
                    request,
                    ModelErrorCategory.MALFORMED_RESPONSE,
                    false,
                    200,
                    "invalid_function_call",
                    "provider returned an invalid function call",
                    null);
        }
        List<ModelToolCall> calls = identities.stream()
                .map(call ->
                        new ModelToolCall(new ProviderToolCallCorrelationId(call.id()), call.name(), call.arguments()))
                .toList();
        String finish = candidate.path("finishReason").asText("");
        if (requireFinish && finish.isBlank())
            throw failure(
                    request,
                    ModelErrorCategory.MALFORMED_RESPONSE,
                    false,
                    200,
                    "missing_finish_reason",
                    "provider response has no terminal finish reason",
                    null);
        ModelFinishReason reason = calls.isEmpty() ? finishReason(finish) : ModelFinishReason.TOOL_CALLS;
        if (text.isEmpty() && calls.isEmpty() && reason == ModelFinishReason.STOP)
            throw failure(
                    request,
                    ModelErrorCategory.EMPTY_RESPONSE,
                    true,
                    200,
                    "empty_response",
                    "provider returned no usable output",
                    null);
        return new ParsedCandidate(text.toString(), calls, parts, reason, finish);
    }

    private List<CallIdentity> extractCalls(List<Map<String, Object>> parts, boolean continuation) {
        List<CallIdentity> calls = new ArrayList<>();
        for (Map<String, Object> part : parts) {
            Object raw = part.get("functionCall");
            if (!(raw instanceof Map<?, ?> map)) continue;
            String id = requiredMapText(map, "id");
            String name = requiredMapText(map, "name");
            Object arguments = map.get("args");
            Map<String, Object> args = arguments instanceof Map<?, ?> values ? copyStringMap(values) : Map.of();
            String signature = part.get("thoughtSignature") instanceof String value ? value : "";
            if (calls.isEmpty() && signature.isBlank()) {
                throw new IllegalArgumentException("first function call thought signature is missing");
            }
            calls.add(new CallIdentity(id, name, args));
        }
        if (!continuation
                && !calls.isEmpty()
                && calls.stream().map(CallIdentity::id).distinct().count() != calls.size()) {
            throw new IllegalArgumentException("duplicate function call id");
        }
        return calls;
    }

    private SensitiveModelReasoning encodeContinuation(AgentChatRequest request, List<Map<String, Object>> parts) {
        try {
            return SensitiveModelReasoning.of(json.writeValueAsString(Map.of("version", 1, "parts", parts)));
        } catch (IOException exception) {
            throw failure(
                    request,
                    ModelErrorCategory.MALFORMED_RESPONSE,
                    false,
                    200,
                    "continuation_encoding_failed",
                    "provider continuation cannot be protected",
                    null);
        }
    }

    private JsonNode parseJson(AgentChatRequest request, byte[] body) {
        try {
            if (body.length == 0) throw new JsonProcessingException("empty") {};
            return json.readTree(body);
        } catch (IOException exception) {
            throw failure(
                    request,
                    ModelErrorCategory.MALFORMED_RESPONSE,
                    false,
                    200,
                    "invalid_json",
                    "provider returned malformed JSON",
                    null);
        }
    }

    private ModelUsage usage(JsonNode node) {
        return new ModelUsage(
                nonNegative(node, "promptTokenCount"),
                nonNegative(node, "candidatesTokenCount"),
                nonNegative(node, "cachedContentTokenCount"),
                0,
                nonNegative(node, "thoughtsTokenCount"),
                false,
                0);
    }

    private static long nonNegative(JsonNode node, String field) {
        long value = node.path(field).asLong(0);
        return Math.max(0, value);
    }

    private static ModelFinishReason finishReason(String value) {
        return switch (value) {
            case "STOP" -> ModelFinishReason.STOP;
            case "MAX_TOKENS" -> ModelFinishReason.LENGTH;
            case "SAFETY", "BLOCKLIST", "PROHIBITED_CONTENT", "IMAGE_SAFETY", "SPII" ->
                ModelFinishReason.CONTENT_FILTER;
            case "MALFORMED_FUNCTION_CALL", "UNEXPECTED_TOOL_CALL" -> ModelFinishReason.UNKNOWN;
            default -> ModelFinishReason.UNKNOWN;
        };
    }

    private void validateSelection(AgentChatRequest request) {
        if (!ModelApiStyles.GOOGLE_GEMINI_ADAPTER.equals(request.model().adapterType())
                || !ADAPTER_VERSION.equals(request.model().adapterVersion())
                || !ModelApiStyles.GOOGLE_GEMINI_GENERATE_CONTENT.equals(
                        request.model().apiStyle())) {
            throw failure(
                    request,
                    ModelErrorCategory.INVALID_REQUEST,
                    false,
                    0,
                    "adapter_snapshot_mismatch",
                    "frozen model snapshot requires another adapter binding",
                    null);
        }
        URI endpoint = request.model().endpoint();
        String dialect = request.model().dialect();
        if (GeminiDialects.STANDARD.equals(dialect)) {
            boolean official = "https".equalsIgnoreCase(endpoint.getScheme())
                    && "generativelanguage.googleapis.com".equalsIgnoreCase(endpoint.getHost())
                    && "/v1beta".equals(endpoint.getPath())
                    && endpoint.getPort() == -1;
            boolean localStub = allowStandardLoopbackStub
                    && "http".equalsIgnoreCase(endpoint.getScheme())
                    && isLoopback(endpoint)
                    && "/v1beta".equals(endpoint.getPath());
            if ((!official && !localStub)
                    || endpoint.getUserInfo() != null
                    || endpoint.getQuery() != null
                    || endpoint.getFragment() != null) {
                throw failure(
                        request,
                        ModelErrorCategory.INVALID_REQUEST,
                        false,
                        0,
                        "invalid_standard_endpoint",
                        "official Gemini binding requires the governed Google HTTPS endpoint",
                        null);
            }
        } else if (GeminiDialects.CLIPROXYAPI_ANTIGRAVITY.equals(dialect)) {
            if (!allowInsecureLoopback
                    || !"http".equalsIgnoreCase(endpoint.getScheme())
                    || !isLoopback(endpoint)
                    || !"/v1beta".equals(endpoint.getPath())
                    || endpoint.getUserInfo() != null
                    || endpoint.getQuery() != null
                    || endpoint.getFragment() != null
                    || !CLIPROXY_CREDENTIAL_REF.equals(
                            request.model().credentialRef().value())) {
                throw failure(
                        request,
                        ModelErrorCategory.INVALID_REQUEST,
                        false,
                        0,
                        "invalid_cliproxy_binding",
                        "CLIProxyAPI dialect requires the governed loopback endpoint and credential reference",
                        null);
            }
        } else if (GeminiDialects.ANTIGRAVITY_DIRECT.equals(dialect)) {
            boolean official = isGovernedAntigravityDirectEndpoint(endpoint);
            boolean localStub = allowStandardLoopbackStub
                    && "http".equalsIgnoreCase(endpoint.getScheme())
                    && isLoopback(endpoint)
                    && "/v1internal".equals(endpoint.getPath());
            if ((!official && !localStub)
                    || endpoint.getUserInfo() != null
                    || endpoint.getQuery() != null
                    || endpoint.getFragment() != null
                    || !"model-auth://google-antigravity/default"
                            .equals(request.model().credentialRef().value())) {
                throw failure(
                        request,
                        ModelErrorCategory.INVALID_REQUEST,
                        false,
                        0,
                        "invalid_antigravity_direct_endpoint",
                        "official Antigravity direct binding requires the governed CloudCode PA API endpoint",
                        null);
            }
        } else {
            throw failure(
                    request,
                    ModelErrorCategory.INVALID_REQUEST,
                    false,
                    0,
                    "unsupported_dialect",
                    "frozen Gemini dialect is unsupported",
                    null);
        }
    }

    static boolean isGovernedAntigravityDirectEndpoint(URI endpoint) {
        String host = endpoint.getHost();
        return "https".equalsIgnoreCase(endpoint.getScheme())
                && host != null
                && ANTIGRAVITY_DIRECT_HOSTS.contains(host.toLowerCase(Locale.ROOT))
                && "/v1internal".equals(endpoint.getPath())
                && endpoint.getPort() == -1
                && endpoint.getUserInfo() == null
                && endpoint.getQuery() == null
                && endpoint.getFragment() == null;
    }

    private static boolean isLoopback(URI endpoint) {
        String host = endpoint.getHost();
        if (host == null || !LOOPBACK_NAMES.contains(host.toLowerCase(Locale.ROOT))) return false;
        try {
            return InetAddress.getByName(host).isLoopbackAddress();
        } catch (IOException exception) {
            return false;
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

    private void requireContentType(AgentChatRequest request, HttpHeaders headers, String expected, int status) {
        String value = headers.firstValue("Content-Type").orElse("").toLowerCase(Locale.ROOT);
        if (!value.contains(expected))
            throw failure(
                    request,
                    ModelErrorCategory.MALFORMED_RESPONSE,
                    false,
                    status,
                    "unexpected_content_type",
                    "provider returned an unexpected content type",
                    null);
    }

    private ModelInvocationException httpFailure(
            AgentChatRequest request, int status, byte[] body, HttpHeaders headers) {
        ModelErrorCategory category =
                switch (status) {
                    case 400 -> ModelErrorCategory.INVALID_REQUEST;
                    case 401 -> ModelErrorCategory.AUTHENTICATION_FAILED;
                    case 403 -> ModelErrorCategory.PERMISSION_DENIED;
                    case 404 -> ModelErrorCategory.MODEL_NOT_FOUND;
                    case 408 -> ModelErrorCategory.TIMEOUT;
                    case 429 -> ModelErrorCategory.RATE_LIMITED;
                    default ->
                        status >= 500 ? ModelErrorCategory.SERVER_ERROR : ModelErrorCategory.UNKNOWN_PROVIDER_ERROR;
                };
        boolean retryable = status == 408 || status == 429 || status >= 500;
        String code = "http_" + status;
        try {
            JsonNode root = json.readTree(body);
            JsonNode error = root.path("error");
            if (error.isObject() && !error.path("status").asText().isBlank()) {
                code = safeCode(error.path("status").asText());
                JsonNode details = error.path("details");
                if (details.isArray()) {
                    for (JsonNode detail : details) {
                        String reason = detail.path("reason").asText("");
                        if ("QUOTA_EXHAUSTED".equalsIgnoreCase(reason)
                                || "INSUFFICIENT_G1_CREDITS_BALANCE".equalsIgnoreCase(reason)) {
                            code = safeCode(reason);
                            category = ModelErrorCategory.RATE_LIMITED;
                            retryable = false;
                            break;
                        } else if ("RATE_LIMIT_EXCEEDED".equalsIgnoreCase(reason)) {
                            code = safeCode(reason);
                            category = ModelErrorCategory.RATE_LIMITED;
                            retryable = true;
                            break;
                        }
                    }
                }
            } else if (error.isTextual()) {
                code = "cliproxy_error";
            }
            if (status == 429 && retryable) {
                String bodyStr = new String(body, StandardCharsets.UTF_8).toLowerCase(Locale.ROOT);
                if (bodyStr.contains("quota_exhausted")
                        || bodyStr.contains("quota exhausted")
                        || bodyStr.contains("insufficient_g1_credits_balance")) {
                    retryable = false;
                    category = ModelErrorCategory.RATE_LIMITED;
                    code = "quota_exhausted";
                }
            }
        } catch (Exception ignored) {
        }
        Duration retryAfter = headers.firstValue("Retry-After")
                .flatMap(GeminiGenerateContentModel::parseRetryAfter)
                .orElse(null);
        return failure(
                request,
                category,
                retryable,
                status,
                code,
                "model provider rejected the request",
                null,
                retryAfter,
                false);
    }

    private static Optional<Duration> parseRetryAfter(String value) {
        try {
            long seconds = Long.parseLong(value.trim());
            return seconds < 0 ? Optional.empty() : Optional.of(Duration.ofSeconds(seconds));
        } catch (RuntimeException ignored) {
            return Optional.empty();
        }
    }

    private static String safeCode(String value) {
        String code = value.trim().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_.-]", "_");
        return code.isEmpty() ? "provider_error" : code.substring(0, Math.min(code.length(), 96));
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

    private static String textOr(JsonNode node, String fallback) {
        String value = node.asText("").trim();
        return value.isEmpty() ? fallback : value;
    }

    private static String requiredMapText(Map<?, ?> map, String key) {
        Object value = map.get(key);
        if (!(value instanceof String text) || text.isBlank()) throw new IllegalArgumentException(key + " is required");
        return text;
    }

    private static Map<String, Object> copyStringMap(Map<?, ?> source) {
        Map<String, Object> target = new LinkedHashMap<>();
        source.forEach((key, value) -> {
            if (!(key instanceof String text)) throw new IllegalArgumentException("map key must be text");
            target.put(text, value);
        });
        return Map.copyOf(target);
    }

    private record CallIdentity(String id, String name, Map<String, Object> arguments) {
        private CallIdentity(String name, Map<String, Object> arguments) {
            this("", name, arguments);
        }
    }

    private record ParsedCandidate(
            String text,
            List<ModelToolCall> calls,
            List<Map<String, Object>> parts,
            ModelFinishReason finishReason,
            String rawFinishReason) {}

    private record StreamDelta(String text) {}

    private static final class InvalidContinuationException extends RuntimeException {
        InvalidContinuationException(Throwable cause) {
            super(cause);
        }
    }

    private final class StreamAggregate {
        private final String model;
        private final StringBuilder text = new StringBuilder();
        private final List<Map<String, Object>> parts = new ArrayList<>();
        private JsonNode usage;
        private String finish = "";
        private String responseId = "";

        private StreamAggregate(String model) {
            this.model = model;
        }

        private StreamDelta accept(JsonNode root) {
            JsonNode candidates = root.path("candidates");
            StringBuilder deltaText = new StringBuilder();
            if (candidates.isArray() && candidates.size() == 1) {
                JsonNode candidate = candidates.get(0);
                JsonNode nodes = candidate.path("content").path("parts");
                if (nodes.isArray()) {
                    List<Map<String, Object>> frameParts = json.convertValue(
                            nodes,
                            json.getTypeFactory()
                                    .constructCollectionType(
                                            List.class,
                                            json.getTypeFactory()
                                                    .constructMapType(Map.class, String.class, Object.class)));
                    parts.addAll(frameParts);
                    for (JsonNode part : nodes)
                        if (part.has("text") && !part.path("thought").asBoolean(false)) {
                            String value = part.path("text").asText();
                            text.append(value);
                            deltaText.append(value);
                        }
                }
                if (!candidate.path("finishReason").asText().isBlank())
                    finish = candidate.path("finishReason").asText();
            }
            if (root.has("usageMetadata")) usage = root.path("usageMetadata");
            if (responseId.isBlank() && root.has("responseId"))
                responseId = root.path("responseId").asText();
            return new StreamDelta(deltaText.toString());
        }

        private AgentChatResponse finish(AgentChatRequest request, GeminiGenerateContentModel adapter) {
            if (finish.isBlank())
                throw adapter.failure(
                        request,
                        ModelErrorCategory.MALFORMED_RESPONSE,
                        false,
                        200,
                        "missing_finish_reason",
                        "provider stream has no terminal finish reason",
                        null);
            List<CallIdentity> identities;
            try {
                identities = adapter.extractCalls(parts, false);
            } catch (IllegalArgumentException exception) {
                throw adapter.failure(
                        request,
                        ModelErrorCategory.MALFORMED_RESPONSE,
                        false,
                        200,
                        "invalid_function_call",
                        "provider returned an invalid function call",
                        null);
            }
            List<ModelToolCall> calls = identities.stream()
                    .map(call -> new ModelToolCall(
                            new ProviderToolCallCorrelationId(call.id()), call.name(), call.arguments()))
                    .toList();
            if (text.isEmpty() && calls.isEmpty())
                throw adapter.failure(
                        request,
                        ModelErrorCategory.EMPTY_RESPONSE,
                        true,
                        200,
                        "empty_response",
                        "provider returned no usable output",
                        null);
            Optional<SensitiveModelReasoning> continuation =
                    calls.isEmpty() ? Optional.empty() : Optional.of(adapter.encodeContinuation(request, parts));
            Optional<Map<String, Object>> structured = Optional.empty();
            if (request.structuredOutput().isPresent() && calls.isEmpty()) {
                try {
                    structured = Optional.of(json.readValue(text.toString(), Map.class));
                } catch (JsonProcessingException exception) {
                    throw adapter.failure(
                            request,
                            ModelErrorCategory.MALFORMED_RESPONSE,
                            false,
                            200,
                            "invalid_structured_output",
                            "provider returned invalid structured output",
                            null);
                }
            }
            return new AgentChatResponse(
                    responseId.isBlank() ? request.callId().value() : responseId,
                    model,
                    text.toString(),
                    calls,
                    calls.isEmpty() ? finishReason(finish) : ModelFinishReason.TOOL_CALLS,
                    adapter.usage(usage == null ? json.createObjectNode() : usage),
                    "",
                    Map.of("provider", "google-gemini", "candidateIndex", 0),
                    continuation,
                    structured);
        }
    }
}
