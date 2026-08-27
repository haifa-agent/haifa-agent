package io.haifa.agent.personalassistant.server.web.v1.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.haifa.agent.auth.localmodel.ExternalLoginMethodDescriptor;
import io.haifa.agent.auth.localmodel.ExternalLoginMethodId;
import io.haifa.agent.auth.localmodel.ExternalLoginMode;
import io.haifa.agent.auth.localmodel.FileLocalModelAuthStore;
import io.haifa.agent.auth.localmodel.LocalModelAuthenticationService;
import io.haifa.agent.model.api.ModelCapability;
import io.haifa.agent.model.api.ModelReasoningMode;
import io.haifa.agent.personalassistant.server.configuration.product.PersonalAssistantProperties;
import io.haifa.agent.personalassistant.server.web.v1.error.PersonalApiExceptionHandler;
import io.haifa.agent.personalassistant.server.web.v1.mapper.PersonalApiMapper;
import java.net.URI;
import java.nio.file.Path;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;

class PersonalModelAuthenticationControllerTest {
    @TempDir
    Path temp;

    @Test
    void projectsEnabledAntigravityLoginEvenBeforeAConnectionIsStored() {
        LocalModelAuthenticationService service = mock(LocalModelAuthenticationService.class);
        when(service.connections()).thenReturn(java.util.List.of());
        when(service.externalLoginMethods())
                .thenReturn(java.util.List.of(new ExternalLoginMethodDescriptor(
                        ExternalLoginMethodId.GOOGLE_ANTIGRAVITY,
                        "Google sign-in (Antigravity)",
                        java.util.Set.of(ExternalLoginMode.BROWSER),
                        true,
                        Optional.empty())));

        WebTestClient.bindToController(new PersonalModelAuthenticationController(service, new PersonalApiMapper()))
                .build()
                .get()
                .uri("/api/v1/model-connections")
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$[0].providerId")
                .isEqualTo("google-antigravity")
                .jsonPath("$[0].status")
                .isEqualTo("REAUTH_REQUIRED")
                .jsonPath("$[0].externalLoginSupported")
                .isEqualTo(true)
                .jsonPath("$[0].unofficialLocalCompatibility")
                .isEqualTo(true);
    }

    @Test
    void projectsConfiguredEnvironmentReadinessWithoutExposingTheVariableNameOrLogout() {
        var store = new FileLocalModelAuthStore(temp.resolve("auth.json"), new ObjectMapper());
        var provider = new PersonalAssistantProperties.ModelProvider(
                "deepseek",
                "DeepSeek",
                "remote",
                false,
                false,
                URI.create("https://api.deepseek.com"),
                "env://DEEPSEEK_API_KEY",
                java.util.List.of(new PersonalAssistantProperties.ApiBinding(
                        "openai-chat-completions", "deepseek-openai-chat", null)),
                java.util.List.of(new PersonalAssistantProperties.ProviderModel(
                        "deepseek-v4-flash",
                        "DeepSeek V4 Flash",
                        "DeepSeek V4 Flash",
                        "deepseek-v4-flash",
                        "openai-chat-completions",
                        java.util.Set.of(ModelCapability.TEXT_CHAT),
                        ModelReasoningMode.DISABLED,
                        131_072,
                        8_192)),
                null);
        try (var service = new LocalModelAuthenticationService(
                store,
                Optional.empty(),
                reference -> {
                    throw new AssertionError("credential resolution is not expected");
                },
                name -> "DEEPSEEK_API_KEY".equals(name) ? "present" : null)) {
            WebTestClient.bindToController(new PersonalModelAuthenticationController(
                            service, new PersonalApiMapper(), () -> java.util.List.of(provider)))
                    .build()
                    .get()
                    .uri("/api/v1/model-connections")
                    .exchange()
                    .expectStatus()
                    .isOk()
                    .expectBody()
                    .jsonPath("$[0].providerId")
                    .isEqualTo("deepseek")
                    .jsonPath("$[0].status")
                    .isEqualTo("AUTHENTICATED")
                    .jsonPath("$[0].connectionId")
                    .isEqualTo("configured://deepseek/default")
                    .jsonPath("$[0].logoutSupported")
                    .isEqualTo(false)
                    .jsonPath("$[0].connectionId")
                    .value(value -> assertThat(value.toString()).doesNotContain("DEEPSEEK_API_KEY"));
        }
    }

    @Test
    void rejectsApiKeyForCodexAndClearsTheRequestBuffer() {
        var store = new FileLocalModelAuthStore(temp.resolve("auth.json"), new ObjectMapper());
        try (var service = new LocalModelAuthenticationService(
                store,
                Optional.empty(),
                reference -> {
                    throw new AssertionError("credential resolution is not expected");
                },
                ignored -> null)) {
            var controller = new PersonalModelAuthenticationController(service, new PersonalApiMapper());
            char[] secret = "secret-canary".toCharArray();

            assertThatThrownBy(() -> controller
                            .saveApiKey(
                                    "codex-key-1",
                                    new io.haifa.agent.personalassistant.server.web.v1.dto.PersonalApiDtos
                                            .SaveModelApiKey("openai-codex", secret))
                            .block())
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage("AUTH_API_KEY_UNAVAILABLE");
            assertThat(secret).containsOnly('\0');
        }
    }

    @Test
    void savesListsAndDeletesWithoutReturningApiKey() {
        var store = new FileLocalModelAuthStore(temp.resolve("auth.json"), new ObjectMapper());
        try (var service = new LocalModelAuthenticationService(
                store,
                Optional.empty(),
                reference -> {
                    throw new AssertionError("credential resolution is not expected");
                },
                ignored -> null)) {
            var controller = new PersonalModelAuthenticationController(service, new PersonalApiMapper());
            WebTestClient web = WebTestClient.bindToController(controller)
                    .controllerAdvice(new PersonalApiExceptionHandler())
                    .build();

            byte[] body = web.post()
                    .uri("/api/v1/model-connections/api-key")
                    .header("Idempotency-Key", "save-key-1")
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue("{\"providerId\":\"deepseek\",\"apiKey\":\"secret-canary\"}")
                    .exchange()
                    .expectStatus()
                    .isCreated()
                    .expectBody()
                    .jsonPath("$.connectionId")
                    .isEqualTo("model-auth://deepseek/default")
                    .jsonPath("$.apiKey")
                    .doesNotExist()
                    .returnResult()
                    .getResponseBody();
            assertThat(new String(body, java.nio.charset.StandardCharsets.UTF_8))
                    .doesNotContain("secret-canary");

            web.get()
                    .uri("/api/v1/model-connections")
                    .exchange()
                    .expectStatus()
                    .isOk()
                    .expectBody()
                    .jsonPath("$[0].providerId")
                    .isEqualTo("deepseek");

            controller.logout("model-auth://deepseek/default", "logout-key-1").block();
            web.get()
                    .uri("/api/v1/model-connections")
                    .exchange()
                    .expectStatus()
                    .isOk()
                    .expectBody()
                    .json("[]");
        }
    }

    @Test
    void externalLoginFailsClosedWithoutApprovedRegistration() {
        var store = new FileLocalModelAuthStore(temp.resolve("auth.json"), new ObjectMapper());
        try (var service = new LocalModelAuthenticationService(
                store,
                Optional.empty(),
                reference -> {
                    throw new AssertionError("credential resolution is not expected");
                },
                ignored -> null)) {
            WebTestClient.bindToController(new PersonalModelAuthenticationController(service, new PersonalApiMapper()))
                    .controllerAdvice(new PersonalApiExceptionHandler())
                    .build()
                    .post()
                    .uri("/api/v1/model-connections/codex/browser-attempts")
                    .header("Idempotency-Key", "login-1")
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue("{}")
                    .exchange()
                    .expectStatus()
                    .isEqualTo(409)
                    .expectBody()
                    .jsonPath("$.code")
                    .isEqualTo("AUTH_EXTERNAL_APPROVAL_REQUIRED")
                    .jsonPath("$.message")
                    .isEqualTo("The operation cannot be completed.");

            WebTestClient.bindToController(new PersonalModelAuthenticationController(service, new PersonalApiMapper()))
                    .controllerAdvice(new PersonalApiExceptionHandler())
                    .build()
                    .post()
                    .uri("/api/v1/model-connections/antigravity/browser-attempts")
                    .header("Idempotency-Key", "antigravity-login-1")
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue("{}")
                    .exchange()
                    .expectStatus()
                    .isEqualTo(409)
                    .expectBody()
                    .jsonPath("$.code")
                    .isEqualTo("AUTH_EXTERNAL_APPROVAL_REQUIRED");
        }
    }
}
