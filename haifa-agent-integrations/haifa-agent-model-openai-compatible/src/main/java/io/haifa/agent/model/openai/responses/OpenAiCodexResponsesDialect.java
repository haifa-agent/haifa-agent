package io.haifa.agent.model.openai.responses;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.haifa.agent.model.api.AgentChatRequest;
import io.haifa.agent.model.api.ModelApiStyles;
import io.haifa.agent.model.api.ResolvedModelSnapshot;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

final class OpenAiCodexResponsesDialect implements OpenAiResponsesDialect {
    static final OpenAiCodexResponsesDialect INSTANCE = new OpenAiCodexResponsesDialect();
    private static final ObjectMapper JSON = new ObjectMapper();

    private OpenAiCodexResponsesDialect() {}

    @Override
    public String id() {
        return OpenAiResponsesDialects.OPENAI_CODEX;
    }

    @Override
    public String version() {
        return "2026-08-31";
    }

    @Override
    public void validateSnapshot(ResolvedModelSnapshot snapshot, boolean allowInsecureHttp) {
        Objects.requireNonNull(snapshot, "snapshot must not be null");
        URI endpoint = snapshot.endpoint();
        OpenAiResponsesDialectSupport.validateEndpoint(endpoint, allowInsecureHttp);
        boolean loopback = OpenAiResponsesDialectSupport.isLoopback(endpoint);
        String path = OpenAiResponsesDialectSupport.normalizedPath(endpoint);
        String host = endpoint.getHost();
        if (loopback) {
            if (!"/backend-api/codex".equals(path)) {
                throw new IllegalArgumentException("Codex Responses loopback endpoint must use /backend-api/codex");
            }
        } else if (!"https".equalsIgnoreCase(endpoint.getScheme())
                || !"chatgpt.com".equalsIgnoreCase(host)
                || endpoint.getPort() != -1
                || !"/backend-api/codex".equals(path)) {
            throw new IllegalArgumentException(
                    "Codex Responses endpoint must be https://chatgpt.com/backend-api/codex");
        }
        validateCodexOptions(snapshot, loopback);
        if (!OpenAiResponsesBindingRegistry.isAdmitted(
                snapshot.providerId().value(),
                snapshot.providerModelId(),
                ModelApiStyles.OPENAI_RESPONSES,
                OpenAiResponsesDialects.OPENAI_CODEX)) {
            throw new IllegalArgumentException("OpenAI Codex Responses model profile is not verified");
        }
    }

    private static void validateCodexOptions(ResolvedModelSnapshot snapshot, boolean loopback) {
        String originator = option(snapshot, OpenAiResponsesDialects.CODEX_ORIGINATOR_OPTION);
        String userAgent = option(snapshot, OpenAiResponsesDialects.CODEX_USER_AGENT_OPTION);
        if (!originator.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,63}")) {
            throw new IllegalArgumentException("Codex originator is invalid");
        }
        if (userAgent.length() > 128 || containsHeaderSeparator(userAgent)) {
            throw new IllegalArgumentException("Codex user agent is invalid");
        }
        if (!loopback && !snapshot.credentialRef().value().startsWith("model-auth://openai-codex/")) {
            throw new IllegalArgumentException("Codex Responses requires a Coding Auth credential reference");
        }
    }

    private static String option(ResolvedModelSnapshot snapshot, String name) {
        Object value = snapshot.providerOptions().get(name);
        if (!(value instanceof String text) || text.isBlank()) {
            throw new IllegalArgumentException("Codex provider option is required: " + name);
        }
        return text.trim();
    }

    private static boolean containsHeaderSeparator(String value) {
        return value.indexOf('\r') >= 0 || value.indexOf('\n') >= 0;
    }

    @Override
    public void decorateHeaders(
            HttpRequest.Builder builder,
            AgentChatRequest request,
            String credentialSecret,
            CodexAccountIdentityResolver codexResolver) {
        CodexAccountIdentity identity = codexResolver
                .resolve(request.model().credentialRef())
                .orElseThrow(() -> new DialectAuthenticationException(
                        "codex_account_identity_invalid", "Codex account identity is unavailable"));
        OpenAiCodexAuthentication.apply(builder, request.model(), credentialSecret, identity);
    }

    @Override
    public void customizeRequestBody(AgentChatRequest request, Map<String, Object> body) {
        // Codex does not include max_output_tokens at top-level
    }

    @Override
    public boolean allowsEmptyContentType() {
        return true;
    }

    @Override
    public DialectErrorMapping classifyError(int statusCode, HttpHeaders headers, byte[] body, JsonNode errorRoot) {
        DialectErrorMapping standard =
                StandardOpenAiResponsesDialect.INSTANCE.classifyError(statusCode, headers, body, errorRoot);
        String providerCode = standard.providerCode();
        String safeMessage = standard.safeMessage();
        boolean retryable = standard.retryable();
        Optional<Duration> retryAfter = standard.retryAfter();

        String codexCode = codexProviderCode(body);
        if (codexCode != null) {
            providerCode = codexCode;
        }
        if (statusCode == 429) {
            CodexRateLimit rateLimit = codexRateLimit(body);
            if (rateLimit != null) {
                providerCode = rateLimit.code();
                retryable = "rate_limit_exceeded".equals(providerCode);
                if (retryAfter.isEmpty() && rateLimit.resetsAtEpochSeconds() != null) {
                    long seconds = Math.max(
                            0, rateLimit.resetsAtEpochSeconds() - Instant.now().getEpochSecond());
                    retryAfter = Optional.of(Duration.ofSeconds(seconds));
                }
                safeMessage = rateLimit.planType() == null
                        ? "ChatGPT Codex usage limit reached"
                        : "ChatGPT Codex usage limit reached for the " + rateLimit.planType() + " plan";
            }
        }
        return new DialectErrorMapping(standard.category(), retryable, providerCode, safeMessage, retryAfter);
    }

    private static String codexProviderCode(byte[] body) {
        if (body == null || body.length == 0) return null;
        try {
            JsonNode error = JSON.readTree(body).path("error");
            String code = optionalText(error, "code", optionalText(error, "type", ""));
            if (!code.matches("[A-Za-z0-9_.-]{1,64}")) return null;
            String parameter = optionalText(error, "param", "");
            return parameter.matches("[A-Za-z0-9_.-]{1,64}") ? code + ":" + parameter : code;
        } catch (IOException | RuntimeException ignored) {
            return null;
        }
    }

    private static CodexRateLimit codexRateLimit(byte[] body) {
        if (body == null || body.length == 0) return null;
        try {
            JsonNode error = JSON.readTree(body).path("error");
            String code = optionalText(error, "code", optionalText(error, "type", ""));
            if (!Set.of("usage_limit_reached", "usage_not_included", "rate_limit_exceeded")
                    .contains(code)) {
                return null;
            }
            String rawPlan = optionalText(error, "plan_type", "");
            String plan = sanitizePlanType(rawPlan);
            Long resetsAt =
                    error.hasNonNull("resets_at") && error.get("resets_at").canConvertToLong()
                            ? error.get("resets_at").longValue()
                            : null;
            return new CodexRateLimit(code, plan, resetsAt);
        } catch (IOException | RuntimeException ignored) {
            return null;
        }
    }

    private static String sanitizePlanType(String planType) {
        if (planType == null || planType.isBlank()) {
            return null;
        }
        String cleaned = planType.trim().toLowerCase(Locale.ROOT);
        if (cleaned.length() > 32 || !cleaned.matches("^[a-z0-9_-]+$")) {
            return null;
        }
        return cleaned;
    }

    private static String optionalText(JsonNode node, String field, String defaultValue) {
        if (node == null || !node.hasNonNull(field)) return defaultValue;
        String text = node.get(field).asText("");
        return text.isBlank() ? defaultValue : text.trim();
    }

    private record CodexRateLimit(String code, String planType, Long resetsAtEpochSeconds) {}
}
