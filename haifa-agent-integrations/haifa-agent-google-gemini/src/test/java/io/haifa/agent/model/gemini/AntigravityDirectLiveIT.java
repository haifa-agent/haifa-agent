package io.haifa.agent.model.gemini;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.haifa.agent.auth.localmodel.ExternalLoginRegistry;
import io.haifa.agent.auth.localmodel.FileLocalModelAuthStore;
import io.haifa.agent.auth.localmodel.LocalModelCredentialResolver;
import io.haifa.agent.auth.localmodel.antigravity.AntigravityExternalLoginMethod;
import io.haifa.agent.auth.localmodel.antigravity.AntigravityLocalCompatibilityRegistrationFactory;
import io.haifa.agent.auth.localmodel.antigravity.AntigravityProjectAndQuota;
import io.haifa.agent.auth.localmodel.antigravity.AntigravityProjectRegistry;
import io.haifa.agent.auth.localmodel.antigravity.AntigravityTokenClient;
import io.haifa.agent.core.run.AgentRunId;
import io.haifa.agent.model.api.AgentChatRequest;
import io.haifa.agent.model.api.AgentChatResponse;
import io.haifa.agent.model.api.CredentialRef;
import io.haifa.agent.model.api.ModelApiStyles;
import io.haifa.agent.model.api.ModelCallId;
import io.haifa.agent.model.api.ModelCapability;
import io.haifa.agent.model.api.ModelDefinitionId;
import io.haifa.agent.model.api.ModelInvocationException;
import io.haifa.agent.model.api.ModelMessage;
import io.haifa.agent.model.api.ModelMessageRole;
import io.haifa.agent.model.api.ModelProviderId;
import io.haifa.agent.model.api.ModelStreamControl;
import io.haifa.agent.model.api.ModelStreamEvent;
import io.haifa.agent.model.api.ResolvedModelSnapshot;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Explicit opt-in live integration test for direct Google Antigravity CloudCode PA endpoint.
 *
 * <p>Requirements:
 * <ul>
 *   <li>Set {@code HAIFA_ANTIGRAVITY_LIVE_TEST=true}</li>
 *   <li>An authenticated {@code auth.json} file containing {@code model-auth://google-antigravity/default}
 *       (or set {@code HAIFA_ANTIGRAVITY_AUTH_FILE} / {@code HAIFA_ANTIGRAVITY_PROJECT_ID})</li>
 * </ul>
 */
@Tag("live")
class AntigravityDirectLiveIT {
    private static final String LIVE_SWITCH = "HAIFA_ANTIGRAVITY_LIVE_TEST";
    private static final String CREDENTIAL_REFERENCE = "model-auth://google-antigravity/default";
    private static final String DEFAULT_ANTIGRAVITY_ENDPOINT = "https://daily-cloudcode-pa.googleapis.com/v1internal";

    @Test
    void completesHelloWorldWithStoredAntigravityCredential() {
        Assumptions.assumeTrue(enabled(LIVE_SWITCH), LIVE_SWITCH + " must explicitly enable the real Antigravity call");

        Map<String, String> environment = System.getenv();
        Path authFile = authFile(environment);
        Assumptions.assumeTrue(Files.isRegularFile(authFile), "Haifa auth.json is unavailable: " + authFile);

        ObjectMapper json = new ObjectMapper();
        Clock clock = Clock.systemUTC();
        HttpClient http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(15))
                .followRedirects(HttpClient.Redirect.NEVER)
                .proxy(java.net.ProxySelector.getDefault())
                .build();
        FileLocalModelAuthStore store = new FileLocalModelAuthStore(authFile, json);
        AntigravityProjectRegistry projectRegistry = new AntigravityProjectRegistry();

        var antigravityMethod = AntigravityLocalCompatibilityRegistrationFactory.create(environment)
                .map(registration -> new AntigravityExternalLoginMethod(
                        registration,
                        new AntigravityTokenClient(http, json, clock, Duration.ofSeconds(30), registration),
                        http,
                        json,
                        SecureRandom::new,
                        Duration.ofMinutes(5),
                        projection -> projectRegistry.record(new CredentialRef(CREDENTIAL_REFERENCE), projection)));

        var authRegistry = new ExternalLoginRegistry(antigravityMethod.stream().toList());
        var credentials = new LocalModelCredentialResolver(environment::get, store, authRegistry, clock, Duration.ZERO);

        String explicitProject = environment.get("HAIFA_ANTIGRAVITY_PROJECT_ID");
        if (explicitProject != null && !explicitProject.isBlank()) {
            projectRegistry.record(
                    new CredentialRef(CREDENTIAL_REFERENCE),
                    AntigravityProjectAndQuota.of(explicitProject.trim(), "standard", 100.0, 0.0));
        }

        var model = new GeminiGenerateContentModel(
                http, json, credentials, false, 4 * 1024 * 1024, false, projectRegistry::resolve);

        var request = new AgentChatRequest(
                new ModelCallId("antigravity-live-hello-world-call"),
                new AgentRunId("antigravity-live-hello-world-run"),
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
        assertThat(response.content().trim().toLowerCase(Locale.ROOT)).contains("hello world");
        assertThat(response.usage().outputTokens()).isPositive();
    }

    @Test
    void completesStreamingWithStoredAntigravityCredential() {
        Assumptions.assumeTrue(enabled(LIVE_SWITCH), LIVE_SWITCH + " must explicitly enable the real Antigravity call");

        Map<String, String> environment = System.getenv();
        Path authFile = authFile(environment);
        Assumptions.assumeTrue(Files.isRegularFile(authFile), "Haifa auth.json is unavailable: " + authFile);

        ObjectMapper json = new ObjectMapper();
        Clock clock = Clock.systemUTC();
        HttpClient http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(15))
                .followRedirects(HttpClient.Redirect.NEVER)
                .proxy(java.net.ProxySelector.getDefault())
                .build();
        FileLocalModelAuthStore store = new FileLocalModelAuthStore(authFile, json);
        AntigravityProjectRegistry projectRegistry = new AntigravityProjectRegistry();

        var antigravityMethod = AntigravityLocalCompatibilityRegistrationFactory.create(environment)
                .map(registration -> new AntigravityExternalLoginMethod(
                        registration,
                        new AntigravityTokenClient(http, json, clock, Duration.ofSeconds(30), registration),
                        http,
                        json,
                        SecureRandom::new,
                        Duration.ofMinutes(5),
                        projection -> projectRegistry.record(new CredentialRef(CREDENTIAL_REFERENCE), projection)));

        var authRegistry = new ExternalLoginRegistry(antigravityMethod.stream().toList());
        var credentials = new LocalModelCredentialResolver(environment::get, store, authRegistry, clock, Duration.ZERO);

        String explicitProject = environment.get("HAIFA_ANTIGRAVITY_PROJECT_ID");
        if (explicitProject != null && !explicitProject.isBlank()) {
            projectRegistry.record(
                    new CredentialRef(CREDENTIAL_REFERENCE),
                    AntigravityProjectAndQuota.of(explicitProject.trim(), "standard", 100.0, 0.0));
        }

        var model = new GeminiGenerateContentModel(
                http, json, credentials, false, 4 * 1024 * 1024, false, projectRegistry::resolve);

        var request = new AgentChatRequest(
                new ModelCallId("antigravity-live-stream-call"),
                new AgentRunId("antigravity-live-stream-run"),
                1,
                1,
                snapshot(environment),
                List.of(ModelMessage.text(ModelMessageRole.USER, "Count from 1 to 5 separated by spaces: 1 2 3 4 5")),
                List.of(),
                64,
                Duration.ofSeconds(60),
                Map.of());

        List<ModelStreamEvent> events = new ArrayList<>();
        AgentChatResponse response = model.invokeStreaming(request, event -> {
            events.add(event);
            return ModelStreamControl.CONTINUE;
        });

        assertThat(response.responseId()).isNotBlank();
        assertThat(response.content()).contains("1");
        assertThat(events).isNotEmpty();
    }

    private static AgentChatResponse invokeWithSafeDiagnostics(
            GeminiGenerateContentModel model, AgentChatRequest request) {
        try {
            return model.invokeStreaming(request, ignored -> ModelStreamControl.CONTINUE);
        } catch (ModelInvocationException failure) {
            throw new AssertionError(
                    "Antigravity live call failed: category="
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
        String providerModelId = environment(environment, "HAIFA_ANTIGRAVITY_MODEL_ID", "gemini-2.5-pro");
        return ResolvedModelSnapshot.create(
                new ModelProviderId("google-antigravity"),
                "live-v1",
                new ModelDefinitionId("google-antigravity-live"),
                "live-v1",
                providerModelId,
                ModelApiStyles.GOOGLE_GEMINI_ADAPTER,
                GeminiGenerateContentModel.ADAPTER_VERSION,
                ModelApiStyles.GOOGLE_GEMINI_GENERATE_CONTENT,
                GeminiDialects.ANTIGRAVITY_DIRECT,
                URI.create(environment(environment, "HAIFA_ANTIGRAVITY_MODEL_ENDPOINT", DEFAULT_ANTIGRAVITY_ENDPOINT)),
                new CredentialRef(CREDENTIAL_REFERENCE),
                true,
                EnumSet.of(
                        ModelCapability.TEXT_CHAT,
                        ModelCapability.TOOL_CALLING,
                        ModelCapability.STRUCTURED_OUTPUT,
                        ModelCapability.REASONING),
                1_048_576,
                65_536,
                Map.of(),
                Map.of());
    }

    private static Path authFile(Map<String, String> environment) {
        String configured = environment.get("HAIFA_ANTIGRAVITY_AUTH_FILE");
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

    private static String environment(Map<String, String> environment, String name, String fallback) {
        String value = environment.get(name);
        return value == null || value.isBlank() ? fallback : value.trim();
    }
}
