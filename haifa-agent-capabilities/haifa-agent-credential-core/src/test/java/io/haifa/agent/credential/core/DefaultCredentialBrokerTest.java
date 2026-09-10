package io.haifa.agent.credential.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.haifa.agent.credential.api.CredentialException;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class DefaultCredentialBrokerTest {
    @Test
    void resolvesExistingSecretAndRejectsMissing() {
        var secrets = Map.of("api-key", "secret-value-123", "github-token", "ghp_abcxyz");
        var broker = new DefaultCredentialBroker(secrets);

        assertThat(broker.getSecret("api-key")).contains("secret-value-123");
        assertThat(broker.requireSecret("api-key")).isEqualTo("secret-value-123");

        assertThat(broker.getSecret("nonexistent")).isEmpty();
        assertThatThrownBy(() -> broker.requireSecret("nonexistent"))
                .isInstanceOf(CredentialException.class)
                .hasMessageContaining("nonexistent");
    }

    @Test
    void redactorRedactsAllKnownSecrets() {
        var broker = new DefaultCredentialBroker(Map.of("key1", "secret-alpha", "key2", "secret-beta"));

        String text = "Authorization: Bearer secret-alpha, token: secret-beta";
        assertThat(broker.redactor().redact(text))
                .doesNotContain("secret-alpha", "secret-beta")
                .contains("[REDACTED]");
    }

    @Test
    void defensivelCopiesProvidedMap() {
        var map = new HashMap<String, String>();
        map.put("key", "secret");
        var broker = new DefaultCredentialBroker(map);

        map.put("key2", "secret2");
        assertThat(broker.getSecret("key2")).isEmpty();
    }
}
