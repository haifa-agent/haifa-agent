package io.haifa.agent.cli;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.http.HttpClient;
import java.nio.file.Path;
import java.time.Clock;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CliCodingAuthenticationClientTest {
    @TempDir
    Path temp;

    @Test
    void savedApiKeySatisfiesTheSelectedCodingAuthReference() {
        var store = new CodingAuthFileStore(temp.resolve("auth.json"), new ObjectMapper());
        var client = client(store, "coding-auth://deepseek/default", "deepseek", Map.of());

        assertThat(client.connectionRequired()).isTrue();
        char[] apiKey = "test-key".toCharArray();
        client.saveApiKey(client.apiKeyProviderId(), apiKey);

        assertThat(client.connectionRequired()).isFalse();
        assertThat(apiKey).containsOnly('\0');
        assertThat(client.connections()).singleElement().satisfies(connection -> {
            assertThat(connection.connectionId()).isEqualTo("deepseek/default");
            assertThat(connection.accountLabel()).isEqualTo("Saved API key");
        });
    }

    @Test
    void configuredEnvironmentReferenceDoesNotTriggerOnboarding() {
        var store = new CodingAuthFileStore(temp.resolve("auth.json"), new ObjectMapper());

        assertThat(client(store, "env://DEEPSEEK_API_KEY", "deepseek", Map.of("DEEPSEEK_API_KEY", "test-key"))
                        .connectionRequired())
                .isFalse();
        assertThat(client(store, "env://DEEPSEEK_API_KEY", "deepseek", Map.of()).connectionRequired())
                .isTrue();
    }

    private CliCodingAuthenticationClient client(
            CodingAuthFileStore store, String credentialReference, String providerId, Map<String, String> environment) {
        return new CliCodingAuthenticationClient(
                store,
                Optional.empty(),
                Optional.empty(),
                uri -> false,
                credentialReference,
                providerId,
                environment::get,
                HttpClient.newHttpClient(),
                new ObjectMapper(),
                Clock.systemUTC());
    }
}
