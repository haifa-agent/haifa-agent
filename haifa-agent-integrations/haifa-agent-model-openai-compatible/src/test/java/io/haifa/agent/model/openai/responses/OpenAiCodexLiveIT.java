package io.haifa.agent.model.openai.responses;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.haifa.agent.auth.localmodel.ExternalLoginRegistry;
import io.haifa.agent.auth.localmodel.FileLocalModelAuthStore;
import io.haifa.agent.auth.localmodel.LocalModelAuthenticationService;
import io.haifa.agent.auth.localmodel.LocalModelCredentialResolver;
import io.haifa.agent.auth.localmodel.codex.CodexDeviceLoginOperation;
import io.haifa.agent.auth.localmodel.codex.CodexExternalLoginMethod;
import io.haifa.agent.auth.localmodel.codex.CodexLocalCompatibilityRegistrationFactory;
import io.haifa.agent.auth.localmodel.codex.CodexTokenClient;
import io.haifa.agent.core.run.AgentRunId;
import io.haifa.agent.model.api.AgentChatRequest;
import io.haifa.agent.model.api.CredentialRef;
import io.haifa.agent.model.api.ModelApiStyles;
import io.haifa.agent.model.api.ModelCallId;
import io.haifa.agent.model.api.ModelCapability;
import io.haifa.agent.model.api.ModelDefinitionId;
import io.haifa.agent.model.api.ModelInvocationException;
import io.haifa.agent.model.api.ModelMessage;
import io.haifa.agent.model.api.ModelMessageRole;
import io.haifa.agent.model.api.ModelProviderId;
import io.haifa.agent.model.api.ResolvedModelSnapshot;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("live")
class OpenAiCodexLiveIT {
    private static final String LIVE_SWITCH = "HAIFA_CODEX_LIVE_TEST";
    private static final String CREDENTIAL_REFERENCE = "model-auth://openai-codex/default";
    private static final URI CODEX_ENDPOINT = URI.create("https://chatgpt.com/backend-api/codex");

    @Test
    void completesHelloWorldWithStoredChatGptCodexCredential() {
        Assumptions.assumeTrue(enabled(LIVE_SWITCH), LIVE_SWITCH + " must explicitly enable the real Codex call");

        Map<String, String> environment = System.getenv();
        Path authFile = authFile(environment);
        Assumptions.assumeTrue(Files.isRegularFile(authFile), "Haifa auth.json is unavailable");

        ObjectMapper json = new ObjectMapper();
        Clock clock = Clock.systemUTC();
        HttpClient http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(15))
                .followRedirects(HttpClient.Redirect.NEVER)
                .proxy(java.net.ProxySelector.getDefault())
                .build();
        FileLocalModelAuthStore store = new FileLocalModelAuthStore(authFile, json);
        var codexMethod = CodexLocalCompatibilityRegistrationFactory.create(environment)
                .map(registration -> new CodexExternalLoginMethod(
                        registration,
                        new CodexTokenClient(http, json, clock, Duration.ofSeconds(30), registration),
                        http,
                        json,
                        SecureRandom::new,
                        Duration.ofMinutes(5),
                        CodexDeviceLoginOperation.Sleeper.system()));
        var credentials = new LocalModelCredentialResolver(
                environment::get,
                store,
                new ExternalLoginRegistry(codexMethod.stream().toList()),
                clock,
                Duration.ZERO);
        var authenticationService =
                new LocalModelAuthenticationService(store, Optional.empty(), credentials, environment::get);
        var model = new OpenAiResponsesModel(http, json, credentials, false, 1024 * 1024, ref -> authenticationService
                .findExternalAccountId(ref, CodexExternalLoginMethod.METHOD_ID)
                .map(CodexAccountIdentity::new));

        var request = new AgentChatRequest(
                new ModelCallId("codex-live-hello-world-call"),
                new AgentRunId("codex-live-hello-world-run"),
                1,
                1,
                snapshot(environment),
                List.of(ModelMessage.text(
                        ModelMessageRole.USER, "Reply with exactly these two lowercase words: hello world")),
                List.of(),
                64,
                Duration.ofSeconds(60),
                Map.of());
        var response = invokeWithSafeDiagnostics(model, request);

        assertThat(response.responseId()).isNotBlank();
        assertThat(response.content().trim().toLowerCase(Locale.ROOT)).isEqualTo("hello world");
        assertThat(response.usage().outputTokens()).isPositive();
    }

    private static io.haifa.agent.model.api.AgentChatResponse invokeWithSafeDiagnostics(
            OpenAiResponsesModel model, AgentChatRequest request) {
        try {
            return model.invokeStreaming(request, ignored -> io.haifa.agent.model.api.ModelStreamControl.CONTINUE);
        } catch (ModelInvocationException failure) {
            throw new AssertionError(
                    "Codex live call failed: category="
                            + failure.category()
                            + ", httpStatus="
                            + failure.httpStatus()
                            + ", providerCode="
                            + failure.providerCode()
                            + ", retryable="
                            + failure.retryable()
                            + ", outputObserved="
                            + failure.outputObserved(),
                    failure);
        }
    }

    private static ResolvedModelSnapshot snapshot(Map<String, String> environment) {
        String providerModelId = environment(environment, "HAIFA_CODEX_MODEL_ID", "gpt-5.6-sol");
        String originator = requireEnvironment(environment, "HAIFA_CODEX_ORIGINATOR");
        String userAgent = environment(environment, "HAIFA_CODEX_USER_AGENT", "haifa-agent-local-compat/1");
        return ResolvedModelSnapshot.create(
                new ModelProviderId("openai-codex"),
                "live-v1",
                new ModelDefinitionId("openai-codex-live"),
                "live-v1",
                providerModelId,
                OpenAiResponsesModel.ADAPTER_TYPE,
                OpenAiResponsesModel.ADAPTER_VERSION,
                ModelApiStyles.OPENAI_RESPONSES,
                OpenAiResponsesDialects.OPENAI_CODEX,
                CODEX_ENDPOINT,
                new CredentialRef(CREDENTIAL_REFERENCE),
                true,
                Set.of(ModelCapability.TEXT_CHAT),
                272_000,
                128_000,
                Map.of(
                        OpenAiResponsesDialects.CODEX_ORIGINATOR_OPTION,
                        originator,
                        OpenAiResponsesDialects.CODEX_USER_AGENT_OPTION,
                        userAgent),
                Map.of());
    }

    private static Path authFile(Map<String, String> environment) {
        String configured = environment.get("HAIFA_CODEX_AUTH_FILE");
        if (configured != null && !configured.isBlank()) {
            return Path.of(configured.trim()).toAbsolutePath().normalize();
        }
        String userHome = System.getProperty("user.home");
        if (userHome == null || userHome.isBlank()) throw new IllegalStateException("user.home is unavailable");
        return Path.of(userHome, ".haifa-agent", "auth.json").toAbsolutePath().normalize();
    }

    private static boolean enabled(String name) {
        return "true".equalsIgnoreCase(System.getenv(name));
    }

    private static String requireEnvironment(Map<String, String> environment, String name) {
        String value = environment.get(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(name + " is required for explicit Codex live execution");
        }
        return value.trim();
    }

    private static String environment(Map<String, String> environment, String name, String fallback) {
        String value = environment.get(name);
        return value == null || value.isBlank() ? fallback : value.trim();
    }
}
