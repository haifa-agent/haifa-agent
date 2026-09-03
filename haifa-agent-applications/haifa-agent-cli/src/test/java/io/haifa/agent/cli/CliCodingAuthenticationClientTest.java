package io.haifa.agent.cli;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.haifa.agent.application.project.product.coding.client.CodingAuthenticationProgressView;
import io.haifa.agent.auth.localmodel.ExternalLoginAttemptState;
import io.haifa.agent.auth.localmodel.FileLocalModelAuthStore;
import io.haifa.agent.auth.localmodel.LocalModelAuthenticationService;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CliCodingAuthenticationClientTest {
    @TempDir
    Path temp;

    @Test
    void savedApiKeySatisfiesTheSelectedCodingAuthReference() {
        var store = new FileLocalModelAuthStore(temp.resolve("auth.json"), new ObjectMapper());
        var client = client(store, "model-auth://deepseek/default", "deepseek", Map.of());

        assertThat(client.connectionRequired()).isTrue();
        char[] apiKey = "test-key".toCharArray();
        client.saveApiKey(client.apiKeyProviderId(), apiKey);

        assertThat(client.connectionRequired()).isFalse();
        assertThat(apiKey).containsOnly('\0');
        assertThat(client.connections()).singleElement().satisfies(connection -> {
            assertThat(connection.connectionId()).isEqualTo("model-auth://deepseek/default");
            assertThat(connection.accountLabel()).isEqualTo("Saved API key");
        });
    }

    @Test
    void configuredEnvironmentReferenceDoesNotTriggerOnboarding() {
        var store = new FileLocalModelAuthStore(temp.resolve("auth.json"), new ObjectMapper());

        assertThat(client(store, "env://DEEPSEEK_API_KEY", "deepseek", Map.of("DEEPSEEK_API_KEY", "test-key"))
                        .connectionRequired())
                .isFalse();
        assertThat(client(store, "env://DEEPSEEK_API_KEY", "deepseek", Map.of()).connectionRequired())
                .isTrue();
    }

    @Test
    void existingCodexConnectionSuppressesOnboardingWhenTheDefaultProviderIsUnconfigured() {
        var store = new FileLocalModelAuthStore(temp.resolve("auth.json"), new ObjectMapper());
        var service = new LocalModelAuthenticationService(
                store,
                java.util.Optional.empty(),
                reference -> {
                    throw new AssertionError("credential resolution is not expected");
                },
                ignored -> null);
        service.saveApiKey("openai-codex", "test-codex-credential".toCharArray());
        var client = new CliCodingAuthenticationClient(
                service,
                "env://DEEPSEEK_API_KEY",
                "deepseek",
                java.util.List.of("env://DEEPSEEK_API_KEY", "model-auth://openai-codex/default"));

        assertThat(client.connectionRequired()).isFalse();
    }

    @Test
    void mapsExternalLoginStagesToSecretFreeProductProgress() {
        assertThat(CliCodingAuthenticationClient.progressPhase(ExternalLoginAttemptState.AUTHORIZING))
                .contains(CodingAuthenticationProgressView.Phase.STARTING);
        assertThat(CliCodingAuthenticationClient.progressPhase(ExternalLoginAttemptState.WAITING_USER))
                .contains(CodingAuthenticationProgressView.Phase.WAITING_USER);
        assertThat(CliCodingAuthenticationClient.progressPhase(ExternalLoginAttemptState.EXCHANGING))
                .contains(CodingAuthenticationProgressView.Phase.EXCHANGING);
        assertThat(CliCodingAuthenticationClient.progressPhase(ExternalLoginAttemptState.STORING))
                .contains(CodingAuthenticationProgressView.Phase.STORING);
        assertThat(CliCodingAuthenticationClient.progressPhase(ExternalLoginAttemptState.FAILED))
                .isEmpty();
    }

    @Test
    void exposesAntigravityOnlyWhenTheTrustedAssemblyRegisteredIt() {
        var store = new FileLocalModelAuthStore(temp.resolve("auth.json"), new ObjectMapper());
        var service = new LocalModelAuthenticationService(
                store,
                java.util.Optional.empty(),
                reference -> {
                    throw new AssertionError("credential resolution is not expected");
                },
                ignored -> null);

        assertThat(new CliCodingAuthenticationClient(
                                service,
                                "model-auth://deepseek/default",
                                "deepseek",
                                java.util.List.of("model-auth://deepseek/default"),
                                true)
                        .antigravityConnectionSupported())
                .isTrue();
        assertThat(new CliCodingAuthenticationClient(
                                service,
                                "model-auth://google-antigravity/default",
                                "google-antigravity",
                                java.util.List.of("model-auth://google-antigravity/default"),
                                true)
                        .antigravityConnectionSupported())
                .isTrue();
        assertThat(new CliCodingAuthenticationClient(
                                service,
                                "model-auth://deepseek/default",
                                "deepseek",
                                java.util.List.of("model-auth://deepseek/default"),
                                false)
                        .antigravityConnectionSupported())
                .isFalse();
    }

    @Test
    void alwaysExposesCodexLogin() {
        var store = new FileLocalModelAuthStore(temp.resolve("auth.json"), new ObjectMapper());
        var service = new LocalModelAuthenticationService(
                store,
                java.util.Optional.empty(),
                reference -> {
                    throw new AssertionError("credential resolution is not expected");
                },
                ignored -> null);

        assertThat(new CliCodingAuthenticationClient(
                                service,
                                "model-auth://deepseek/default",
                                "deepseek",
                                java.util.List.of("model-auth://deepseek/default"),
                                false)
                        .codexConnectionSupported())
                .isTrue();
        assertThat(new CliCodingAuthenticationClient(
                                service,
                                "model-auth://openai-codex/default",
                                "openai-codex",
                                java.util.List.of("model-auth://openai-codex/default"),
                                false)
                        .codexConnectionSupported())
                .isTrue();
    }

    @Test
    void apiKeyConnectionIsUnavailableForExternalLoginCredentials() {
        var store = new FileLocalModelAuthStore(temp.resolve("auth.json"), new ObjectMapper());

        assertThat(client(store, "model-auth://deepseek/default", "deepseek", Map.of())
                        .apiKeyConnectionSupported())
                .isTrue();
        assertThat(client(store, "model-auth://openai-codex/default", "openai-codex", Map.of())
                        .apiKeyConnectionSupported())
                .isFalse();
        assertThat(client(store, "model-auth://google-antigravity/default", "google-antigravity", Map.of())
                        .apiKeyConnectionSupported())
                .isFalse();
    }

    private CliCodingAuthenticationClient client(
            FileLocalModelAuthStore store,
            String credentialReference,
            String providerId,
            Map<String, String> environment) {
        var service = new LocalModelAuthenticationService(
                store,
                java.util.Optional.empty(),
                reference -> {
                    throw new AssertionError("credential resolution is not expected");
                },
                environment::get);
        return new CliCodingAuthenticationClient(service, credentialReference, providerId);
    }
}
