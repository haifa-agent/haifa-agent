package io.haifa.agent.credential.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.haifa.agent.core.reference.TenantRef;
import io.haifa.agent.credential.api.CredentialDefinitionId;
import io.haifa.agent.credential.api.CredentialException;
import io.haifa.agent.credential.api.CredentialReference;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicReference;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;

class AesGcmCredentialStoreTest {
    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");
    private static final TenantRef TENANT = new TenantRef("tenant-1");
    private static final CredentialDefinitionId DEFINITION = new CredentialDefinitionId("provider-token");
    private static final CredentialReference REFERENCE = new CredentialReference("secret-1");

    @Test
    void encryptsAtRestAndIssuesShortLivedZeroizableLease() {
        var store = store(key((byte) 1));
        byte[] source = "sensitive-value".getBytes(StandardCharsets.UTF_8);
        store.store(REFERENCE, TENANT, DEFINITION, source);

        var lease = store.lease(REFERENCE, TENANT, DEFINITION, NOW.plusSeconds(30));
        String leasedValue = lease.use(secret -> new String(secret, StandardCharsets.UTF_8));
        assertThat(leasedValue).isEqualTo("sensitive-value");
        lease.close();

        assertThat(lease.isClosed()).isTrue();
        assertThatThrownBy(() -> lease.use(secret -> secret.length))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("closed");
        assertThat(source).containsExactly("sensitive-value".getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void rejectsWrongKeyAndAssociatedData() {
        AtomicReference<SecretKey> currentKey = new AtomicReference<>(key((byte) 2));
        var store = new AesGcmCredentialStore(currentKey::get, new SecureRandom(), Clock.fixed(NOW, ZoneOffset.UTC));
        store.store(REFERENCE, TENANT, DEFINITION, "secret".getBytes(StandardCharsets.UTF_8));

        currentKey.set(key((byte) 3));
        assertThatThrownBy(() -> store.lease(REFERENCE, TENANT, DEFINITION, NOW.plusSeconds(30)))
                .isInstanceOf(CredentialException.class)
                .hasMessageContaining("decryption failed");
        assertThatThrownBy(() -> store.lease(REFERENCE, new TenantRef("other"), DEFINITION, NOW.plusSeconds(30)))
                .isInstanceOf(CredentialException.class)
                .hasMessageContaining("associated data");
    }

    @Test
    void usesRandomNonceAndRejectsCiphertextTampering() {
        var repository = new InMemoryEncryptedCredentialRepository();
        var store = new AesGcmCredentialStore(
                () -> key((byte) 4), new SecureRandom(), Clock.fixed(NOW, ZoneOffset.UTC), repository);
        byte[] plaintext = "same-secret".getBytes(StandardCharsets.UTF_8);
        store.store(REFERENCE, TENANT, DEFINITION, plaintext);
        EncryptedCredentialPayload first = repository.find(REFERENCE).orElseThrow();
        store.store(REFERENCE, TENANT, DEFINITION, plaintext);
        EncryptedCredentialPayload second = repository.find(REFERENCE).orElseThrow();

        assertThat(first.nonce()).isNotEqualTo(second.nonce());
        assertThat(first.ciphertext()).isNotEqualTo(second.ciphertext());
        assertThat(new String(second.ciphertext(), StandardCharsets.UTF_8)).doesNotContain("same-secret");

        byte[] tampered = second.ciphertext();
        tampered[tampered.length - 1] ^= 1;
        repository.save(REFERENCE, new EncryptedCredentialPayload(TENANT, DEFINITION, second.nonce(), tampered));
        assertThatThrownBy(() -> store.lease(REFERENCE, TENANT, DEFINITION, NOW.plusSeconds(30)))
                .isInstanceOf(CredentialException.class)
                .hasMessageContaining("decryption failed");
    }

    private static AesGcmCredentialStore store(SecretKey key) {
        return new AesGcmCredentialStore(() -> key, new SecureRandom(), Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private static SecretKey key(byte fill) {
        byte[] bytes = new byte[32];
        java.util.Arrays.fill(bytes, fill);
        return new SecretKeySpec(bytes, "AES");
    }
}
