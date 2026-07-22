package io.haifa.agent.credential.core;

import io.haifa.agent.core.reference.TenantRef;
import io.haifa.agent.credential.api.CredentialDefinitionId;
import io.haifa.agent.credential.api.CredentialException;
import io.haifa.agent.credential.api.CredentialLease;
import io.haifa.agent.credential.api.CredentialReference;
import io.haifa.agent.credential.api.CredentialStore;
import io.haifa.agent.credential.api.SecretFunction;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.Arrays;
import java.util.Objects;
import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

public final class AesGcmCredentialStore implements CredentialStore {
    private static final int NONCE_BYTES = 12;
    private static final int TAG_BITS = 128;

    private final CredentialEncryptionKeyProvider keyProvider;
    private final SecureRandom secureRandom;
    private final Clock clock;
    private final EncryptedCredentialRepository repository;

    public AesGcmCredentialStore(CredentialEncryptionKeyProvider keyProvider) {
        this(keyProvider, new SecureRandom(), Clock.systemUTC(), new InMemoryEncryptedCredentialRepository());
    }

    AesGcmCredentialStore(CredentialEncryptionKeyProvider keyProvider, SecureRandom secureRandom, Clock clock) {
        this(keyProvider, secureRandom, clock, new InMemoryEncryptedCredentialRepository());
    }

    AesGcmCredentialStore(
            CredentialEncryptionKeyProvider keyProvider,
            SecureRandom secureRandom,
            Clock clock,
            EncryptedCredentialRepository repository) {
        this.keyProvider = Objects.requireNonNull(keyProvider, "keyProvider");
        this.secureRandom = Objects.requireNonNull(secureRandom, "secureRandom");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.repository = Objects.requireNonNull(repository, "repository");
    }

    @Override
    public void store(
            CredentialReference reference, TenantRef tenant, CredentialDefinitionId definitionId, byte[] secret) {
        Objects.requireNonNull(reference, "reference");
        Objects.requireNonNull(tenant, "tenant");
        Objects.requireNonNull(definitionId, "definitionId");
        Objects.requireNonNull(secret, "secret");
        if (secret.length == 0) {
            throw new IllegalArgumentException("secret must not be empty");
        }
        byte[] nonce = new byte[NONCE_BYTES];
        secureRandom.nextBytes(nonce);
        byte[] plaintext = secret.clone();
        try {
            byte[] ciphertext =
                    cipher(Cipher.ENCRYPT_MODE, key(), nonce, aad(reference, tenant, definitionId), plaintext);
            repository.save(reference, new EncryptedCredentialPayload(tenant, definitionId, nonce, ciphertext));
        } catch (GeneralSecurityException exception) {
            throw new CredentialException("credential encryption failed", exception);
        } finally {
            Arrays.fill(plaintext, (byte) 0);
        }
    }

    @Override
    public CredentialLease lease(
            CredentialReference reference, TenantRef tenant, CredentialDefinitionId definitionId, Instant expiresAt) {
        Objects.requireNonNull(expiresAt, "expiresAt");
        if (!expiresAt.isAfter(clock.instant())) {
            throw new CredentialException("credential lease expiry must be in the future");
        }
        EncryptedCredentialPayload encrypted =
                repository.find(reference).orElseThrow(() -> new CredentialException("credential is unavailable"));
        if (!encrypted.tenant().equals(tenant) || !encrypted.definitionId().equals(definitionId)) {
            throw new CredentialException("credential associated data does not match");
        }
        try {
            byte[] plaintext = cipher(
                    Cipher.DECRYPT_MODE,
                    key(),
                    encrypted.nonce(),
                    aad(reference, tenant, definitionId),
                    encrypted.ciphertext());
            return new MutableCredentialLease(reference, expiresAt, plaintext, clock);
        } catch (GeneralSecurityException exception) {
            throw new CredentialException("credential decryption failed", exception);
        }
    }

    private SecretKey key() {
        SecretKey key = Objects.requireNonNull(keyProvider.keyForEncryption(), "encryption key");
        if (!"AES".equalsIgnoreCase(key.getAlgorithm())) {
            throw new CredentialException("credential encryption key must use AES");
        }
        return key;
    }

    private static byte[] cipher(int mode, SecretKey key, byte[] nonce, byte[] aad, byte[] input)
            throws GeneralSecurityException {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(mode, key, new GCMParameterSpec(TAG_BITS, nonce));
        cipher.updateAAD(aad);
        return cipher.doFinal(input);
    }

    private static byte[] aad(CredentialReference reference, TenantRef tenant, CredentialDefinitionId definitionId) {
        return (reference.value() + "\u0000" + tenant.tenantId() + "\u0000" + definitionId.value())
                .getBytes(StandardCharsets.UTF_8);
    }

    private static final class MutableCredentialLease implements CredentialLease {
        private final CredentialReference reference;
        private final Instant expiresAt;
        private final Clock clock;
        private byte[] secret;

        private MutableCredentialLease(CredentialReference reference, Instant expiresAt, byte[] secret, Clock clock) {
            this.reference = reference;
            this.expiresAt = expiresAt;
            this.secret = secret;
            this.clock = clock;
        }

        @Override
        public CredentialReference reference() {
            return reference;
        }

        @Override
        public Instant expiresAt() {
            return expiresAt;
        }

        @Override
        public synchronized boolean isClosed() {
            return secret == null;
        }

        @Override
        public synchronized <T> T use(SecretFunction<T> action) {
            Objects.requireNonNull(action, "action");
            if (secret == null) {
                throw new IllegalStateException("credential lease is closed");
            }
            if (!expiresAt.isAfter(clock.instant())) {
                close();
                throw new IllegalStateException("credential lease is expired");
            }
            byte[] temporary = secret.clone();
            try {
                return action.apply(temporary);
            } finally {
                Arrays.fill(temporary, (byte) 0);
            }
        }

        @Override
        public synchronized void close() {
            if (secret != null) {
                Arrays.fill(secret, (byte) 0);
                secret = null;
            }
        }
    }
}
