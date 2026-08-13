package io.haifa.agent.model.openai;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.haifa.agent.model.api.AgentChatModel;
import io.haifa.agent.model.api.ApiStyleId;
import io.haifa.agent.model.api.CredentialRef;
import io.haifa.agent.model.api.CredentialResolver;
import io.haifa.agent.model.api.ModelApiBindingDefinition;
import io.haifa.agent.model.api.ModelApiStyles;
import io.haifa.agent.model.api.ModelCapability;
import io.haifa.agent.model.api.ModelDefinitionId;
import io.haifa.agent.model.api.ModelProviderId;
import io.haifa.agent.model.api.ResolvedModelSnapshot;
import io.haifa.agent.model.openai.anthropic.AnthropicMessagesDialects;
import io.haifa.agent.model.openai.anthropic.AnthropicMessagesModel;
import io.haifa.agent.model.openai.responses.OpenAiResponsesDialects;
import io.haifa.agent.model.openai.responses.OpenAiResponsesModel;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Typed, immutable assembly of one model adapter and its frozen model snapshot.
 *
 * <p>This is an Integration-local convenience API, not a provider discovery or fallback
 * facility. Provider-specific governed factories remain the advanced path for Bailian and Ark.
 */
public final class OpenAiCompatibleModelConfiguration {
    static final String CONNECT_TIMEOUT_MILLIS = "haifa_connect_timeout_millis";
    static final String REQUEST_TIMEOUT_MILLIS = "haifa_request_timeout_millis";

    private static final String DEFAULT_VERSION = "1.0.0";
    private static final Duration DEFAULT_CONNECT_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration DEFAULT_REQUEST_TIMEOUT = Duration.ofSeconds(60);
    private static final Duration MAX_CONNECT_TIMEOUT = Duration.ofMinutes(2);
    private static final Duration MAX_REQUEST_TIMEOUT = Duration.ofMinutes(10);
    private static final int MAX_RESPONSE_BYTES = 4 * 1024 * 1024;

    private final AgentChatModel model;
    private final ResolvedModelSnapshot snapshot;
    private final Duration requestTimeout;

    private OpenAiCompatibleModelConfiguration(
            AgentChatModel model, ResolvedModelSnapshot snapshot, Duration requestTimeout) {
        this.model = Objects.requireNonNull(model, "model must not be null");
        this.snapshot = Objects.requireNonNull(snapshot, "snapshot must not be null");
        this.requestTimeout = Objects.requireNonNull(requestTimeout, "requestTimeout must not be null");
    }

    /** Starts a typed configuration using the host's credential resolver. */
    public static Builder builder(CredentialResolver credentialResolver) {
        return new Builder(credentialResolver);
    }

    /** Returns the adapter bound to the exact coordinate in {@link #snapshot()}. */
    public AgentChatModel model() {
        return model;
    }

    /** Returns the fully validated and digest-protected frozen model snapshot. */
    public ResolvedModelSnapshot snapshot() {
        return snapshot;
    }

    /** Returns the request timeout that a host must freeze in its Run profile. */
    public Duration requestTimeout() {
        return requestTimeout;
    }

    /** API styles implemented by this Integration. */
    public enum ApiStyle {
        CHAT_COMPLETIONS(ModelApiStyles.OPENAI_CHAT_COMPLETIONS),
        RESPONSES(ModelApiStyles.OPENAI_RESPONSES),
        ANTHROPIC_MESSAGES(ModelApiStyles.ANTHROPIC_MESSAGES);

        private final ApiStyleId id;

        ApiStyle(ApiStyleId id) {
            this.id = id;
        }
    }

    /** Generic and DeepSeek compatibility profiles supported by the typed path. */
    public enum Dialect {
        STANDARD,
        DEEPSEEK
    }

    /** Bounded provider-neutral choices mapped to the selected API style. */
    public enum ToolChoice {
        AUTO,
        NONE,
        REQUIRED
    }

    /** Phase 2 response modes; JSON Schema decoding remains outside this phase. */
    public enum ResponseFormat {
        TEXT,
        JSON_OBJECT
    }

    /** Mutable construction surface that produces an immutable configuration. */
    public static final class Builder {
        private final CredentialResolver credentialResolver;
        private String providerId;
        private String providerVersion = DEFAULT_VERSION;
        private String modelId;
        private String modelVersion = DEFAULT_VERSION;
        private String providerModelId;
        private ApiStyle apiStyle = ApiStyle.CHAT_COMPLETIONS;
        private Dialect dialect = Dialect.STANDARD;
        private URI endpoint;
        private CredentialRef credentialRef;
        private boolean nativeStreaming = true;
        private Set<ModelCapability> capabilities = EnumSet.of(ModelCapability.TEXT_CHAT);
        private int contextWindow;
        private int maxOutputTokens;
        private Duration connectTimeout = DEFAULT_CONNECT_TIMEOUT;
        private Duration requestTimeout = DEFAULT_REQUEST_TIMEOUT;
        private Double temperature;
        private ToolChoice toolChoice;
        private ResponseFormat responseFormat = ResponseFormat.TEXT;

        private Builder(CredentialResolver credentialResolver) {
            this.credentialResolver = Objects.requireNonNull(credentialResolver, "credentialResolver must not be null");
        }

        public Builder providerId(String value) {
            providerId = text(value, "providerId");
            return this;
        }

        public Builder providerVersion(String value) {
            providerVersion = text(value, "providerVersion");
            return this;
        }

        public Builder modelId(String value) {
            modelId = text(value, "modelId");
            return this;
        }

        public Builder modelVersion(String value) {
            modelVersion = text(value, "modelVersion");
            return this;
        }

        public Builder providerModelId(String value) {
            providerModelId = text(value, "providerModelId");
            return this;
        }

        public Builder apiStyle(ApiStyle value) {
            apiStyle = Objects.requireNonNull(value, "apiStyle must not be null");
            return this;
        }

        public Builder dialect(Dialect value) {
            dialect = Objects.requireNonNull(value, "dialect must not be null");
            return this;
        }

        public Builder endpoint(URI value) {
            endpoint = Objects.requireNonNull(value, "endpoint must not be null");
            return this;
        }

        public Builder credentialRef(CredentialRef value) {
            credentialRef = Objects.requireNonNull(value, "credentialRef must not be null");
            return this;
        }

        public Builder nativeStreaming(boolean value) {
            nativeStreaming = value;
            return this;
        }

        public Builder capabilities(Set<ModelCapability> values) {
            capabilities = EnumSet.copyOf(Objects.requireNonNull(values, "capabilities must not be null"));
            return this;
        }

        public Builder tokenLimits(int contextWindow, int maxOutputTokens) {
            this.contextWindow = contextWindow;
            this.maxOutputTokens = maxOutputTokens;
            return this;
        }

        public Builder connectTimeout(Duration value) {
            connectTimeout = boundedTimeout(value, "connectTimeout", MAX_CONNECT_TIMEOUT);
            return this;
        }

        public Builder requestTimeout(Duration value) {
            requestTimeout = boundedTimeout(value, "requestTimeout", MAX_REQUEST_TIMEOUT);
            return this;
        }

        public Builder temperature(double value) {
            if (!Double.isFinite(value) || value < 0.0d || value > 2.0d) {
                throw new IllegalArgumentException("temperature must be between 0.0 and 2.0");
            }
            temperature = value;
            return this;
        }

        public Builder toolChoice(ToolChoice value) {
            toolChoice = Objects.requireNonNull(value, "toolChoice must not be null");
            return this;
        }

        public Builder responseFormat(ResponseFormat value) {
            responseFormat = Objects.requireNonNull(value, "responseFormat must not be null");
            return this;
        }

        public OpenAiCompatibleModelConfiguration build() {
            String resolvedProviderId = text(providerId, "providerId");
            String resolvedModelId = text(modelId, "modelId");
            String resolvedProviderModelId = text(providerModelId, "providerModelId");
            URI resolvedEndpoint = httpsEndpoint(endpoint);
            CredentialRef resolvedCredentialRef =
                    Objects.requireNonNull(credentialRef, "credentialRef must not be null");
            Set<ModelCapability> resolvedCapabilities = Set.copyOf(capabilities);
            validateCapabilities(resolvedCapabilities);
            validateTokenLimits();
            validateOptions(resolvedCapabilities);
            String resolvedDialect = OpenAiCompatibleModelConfiguration.dialect(apiStyle, dialect);
            validateProfile(resolvedEndpoint, resolvedProviderModelId, apiStyle, dialect);

            Map<String, Object> providerOptions = providerOptions(resolvedEndpoint);
            Map<String, Object> invocationOptions = invocationOptions();
            Adapter adapter = adapter(apiStyle, credentialResolver, connectTimeout);
            ResolvedModelSnapshot snapshot = ResolvedModelSnapshot.create(
                    new ModelProviderId(resolvedProviderId),
                    providerVersion,
                    new ModelDefinitionId(resolvedModelId),
                    modelVersion,
                    resolvedProviderModelId,
                    adapter.type(),
                    adapter.version(),
                    apiStyle.id,
                    resolvedDialect,
                    resolvedEndpoint,
                    resolvedCredentialRef,
                    nativeStreaming,
                    resolvedCapabilities,
                    contextWindow,
                    maxOutputTokens,
                    providerOptions,
                    invocationOptions);
            return new OpenAiCompatibleModelConfiguration(adapter.model(), snapshot, requestTimeout);
        }

        private void validateTokenLimits() {
            if (contextWindow < 1 || maxOutputTokens < 1 || maxOutputTokens > contextWindow) {
                throw new IllegalArgumentException("token limits must be positive and output must fit the context");
            }
        }

        private void validateCapabilities(Set<ModelCapability> resolvedCapabilities) {
            if (!resolvedCapabilities.contains(ModelCapability.TEXT_CHAT)) {
                throw new IllegalArgumentException("capabilities must include TEXT_CHAT");
            }
        }

        private void validateOptions(Set<ModelCapability> resolvedCapabilities) {
            if (temperature != null && apiStyle != ApiStyle.CHAT_COMPLETIONS) {
                throw new IllegalArgumentException("temperature is supported only by Chat Completions");
            }
            if (toolChoice != null
                    && toolChoice != ToolChoice.NONE
                    && !resolvedCapabilities.contains(ModelCapability.TOOL_CALLING)) {
                throw new IllegalArgumentException("toolChoice requires TOOL_CALLING capability");
            }
            if (dialect == Dialect.DEEPSEEK
                    && apiStyle == ApiStyle.RESPONSES
                    && toolChoice != null
                    && toolChoice != ToolChoice.AUTO) {
                throw new IllegalArgumentException("DeepSeek Responses supports only AUTO toolChoice");
            }
            if (responseFormat == ResponseFormat.JSON_OBJECT && apiStyle == ApiStyle.ANTHROPIC_MESSAGES) {
                throw new IllegalArgumentException("Anthropic Messages does not support responseFormat");
            }
            if (responseFormat == ResponseFormat.JSON_OBJECT
                    && !resolvedCapabilities.contains(ModelCapability.STRUCTURED_OUTPUT)) {
                throw new IllegalArgumentException("JSON_OBJECT responseFormat requires STRUCTURED_OUTPUT capability");
            }
        }

        private Map<String, Object> providerOptions(URI resolvedEndpoint) {
            Map<String, Object> values = new LinkedHashMap<>();
            if (apiStyle == ApiStyle.CHAT_COMPLETIONS && dialect == Dialect.STANDARD) {
                values.putAll(OpenAiCompatibleDialects.configuredOptions(
                        ModelApiBindingDefinition.STANDARD_DIALECT, resolvedEndpoint));
            }
            values.put(CONNECT_TIMEOUT_MILLIS, connectTimeout.toMillis());
            values.put(REQUEST_TIMEOUT_MILLIS, requestTimeout.toMillis());
            return Map.copyOf(values);
        }

        private Map<String, Object> invocationOptions() {
            Map<String, Object> values = new LinkedHashMap<>();
            if (dialect == Dialect.DEEPSEEK) values.put("thinking", "disabled");
            if (temperature != null) values.put("temperature", temperature);
            if (toolChoice != null) {
                values.put("tool_choice", OpenAiCompatibleModelConfiguration.toolChoice(apiStyle, toolChoice));
            }
            if (responseFormat == ResponseFormat.JSON_OBJECT) {
                values.put("response_format", Map.of("type", "json_object"));
            }
            return Map.copyOf(values);
        }
    }

    private static Adapter adapter(ApiStyle style, CredentialResolver credentials, Duration connectTimeout) {
        HttpClient http = HttpClient.newBuilder()
                .connectTimeout(connectTimeout)
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
        ObjectMapper json = new ObjectMapper();
        return switch (style) {
            case CHAT_COMPLETIONS ->
                new Adapter(
                        ModelApiStyles.OPENAI_CHAT_ADAPTER,
                        DEFAULT_VERSION,
                        new OpenAiCompatibleChatModel(
                                ModelApiStyles.OPENAI_CHAT_ADAPTER,
                                DEFAULT_VERSION,
                                http,
                                json,
                                credentials,
                                false,
                                MAX_RESPONSE_BYTES));
            case RESPONSES ->
                new Adapter(
                        OpenAiResponsesModel.ADAPTER_TYPE,
                        OpenAiResponsesModel.ADAPTER_VERSION,
                        new OpenAiResponsesModel(http, json, credentials, false, MAX_RESPONSE_BYTES));
            case ANTHROPIC_MESSAGES ->
                new Adapter(
                        AnthropicMessagesModel.ADAPTER_TYPE,
                        AnthropicMessagesModel.ADAPTER_VERSION,
                        new AnthropicMessagesModel(http, json, credentials, false, MAX_RESPONSE_BYTES));
        };
    }

    private static String dialect(ApiStyle style, Dialect dialect) {
        if (dialect == Dialect.STANDARD) return ModelApiBindingDefinition.STANDARD_DIALECT;
        return switch (style) {
            case CHAT_COMPLETIONS -> OpenAiCompatibleDialects.DEEPSEEK;
            case RESPONSES -> OpenAiResponsesDialects.DEEPSEEK;
            case ANTHROPIC_MESSAGES -> AnthropicMessagesDialects.DEEPSEEK;
        };
    }

    private static Object toolChoice(ApiStyle style, ToolChoice choice) {
        if (style == ApiStyle.ANTHROPIC_MESSAGES && choice == ToolChoice.REQUIRED) return "any";
        return choice.name().toLowerCase(java.util.Locale.ROOT);
    }

    private static void validateProfile(URI endpoint, String providerModelId, ApiStyle style, Dialect dialect) {
        if (dialect == Dialect.STANDARD) {
            if (style == ApiStyle.CHAT_COMPLETIONS && !"/v1".equals(normalizedPath(endpoint))) {
                throw new IllegalArgumentException("standard Chat Completions endpoint path must be /v1");
            }
            return;
        }
        if (!"api.deepseek.com".equalsIgnoreCase(endpoint.getHost())) {
            throw new IllegalArgumentException("DeepSeek endpoint host must be api.deepseek.com");
        }
        String expectedPath = style == ApiStyle.ANTHROPIC_MESSAGES ? "/anthropic" : "";
        if (!expectedPath.equals(normalizedPath(endpoint))) {
            throw new IllegalArgumentException("DeepSeek endpoint path is invalid for the selected API style");
        }
        if (style == ApiStyle.RESPONSES && !"deepseek-v4-flash".equals(providerModelId)) {
            throw new IllegalArgumentException(
                    "DeepSeek Responses supports only the verified deepseek-v4-flash profile");
        }
        if (style == ApiStyle.ANTHROPIC_MESSAGES
                && !Set.of("deepseek-v4-flash", "deepseek-v4-pro").contains(providerModelId)) {
            throw new IllegalArgumentException("DeepSeek Anthropic model profile is not verified");
        }
    }

    private static URI httpsEndpoint(URI value) {
        URI endpoint =
                Objects.requireNonNull(value, "endpoint must not be null").normalize();
        if (!"https".equalsIgnoreCase(endpoint.getScheme())
                || endpoint.getHost() == null
                || endpoint.getRawUserInfo() != null
                || endpoint.getRawQuery() != null
                || endpoint.getRawFragment() != null) {
            throw new IllegalArgumentException(
                    "endpoint must be a clean absolute HTTPS network URI without userinfo, query, or fragment");
        }
        String text = endpoint.toString();
        while (text.endsWith("/")) text = text.substring(0, text.length() - 1);
        return URI.create(text);
    }

    private static String normalizedPath(URI endpoint) {
        String path = endpoint.getPath();
        if (path == null || path.isBlank() || "/".equals(path)) return "";
        while (path.endsWith("/")) path = path.substring(0, path.length() - 1);
        return path;
    }

    private static Duration boundedTimeout(Duration value, String field, Duration maximum) {
        Duration timeout = Objects.requireNonNull(value, field + " must not be null");
        if (timeout.isZero() || timeout.isNegative() || timeout.compareTo(maximum) > 0) {
            throw new IllegalArgumentException(field + " must be positive and no greater than " + maximum);
        }
        return timeout;
    }

    private static String text(String value, String field) {
        String normalized =
                Objects.requireNonNull(value, field + " must not be null").trim();
        if (normalized.isEmpty()) throw new IllegalArgumentException(field + " must not be blank");
        return normalized;
    }

    private record Adapter(String type, String version, AgentChatModel model) {}
}
