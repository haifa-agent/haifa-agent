package io.haifa.agent.cli;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
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
