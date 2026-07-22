package io.haifa.agent.credential.core;

import static org.assertj.core.api.Assertions.assertThat;

import io.haifa.agent.core.reference.TenantRef;
import io.haifa.agent.credential.api.CredentialDefinitionId;
import io.haifa.agent.credential.api.CredentialReference;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;

class DefaultSecretRedactorTest {
    @Test
    void redactsTrackedSecretsAndCommonAuthorizationPatterns() {
        Instant now = Instant.parse("2026-01-01T00:00:00Z");
        var store = new AesGcmCredentialStore(
                () -> new SecretKeySpec(new byte[32], "AES"),
                new java.security.SecureRandom(),
                Clock.fixed(now, ZoneOffset.UTC));
        var reference = new CredentialReference("ref");
        var definition = new CredentialDefinitionId("def");
        store.store(reference, new TenantRef("tenant"), definition, "exact-secret".getBytes(StandardCharsets.UTF_8));
        var lease = store.lease(reference, new TenantRef("tenant"), definition, now.plusSeconds(30));
        var redactor = new DefaultSecretRedactor();
        redactor.track(lease);

        assertThat(redactor.redact("token=exact-secret Authorization: Bearer other-secret api_key=third-secret"))
                .doesNotContain("exact-secret", "other-secret", "third-secret")
                .contains("[REDACTED]");
    }
}
