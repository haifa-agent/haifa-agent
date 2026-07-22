package io.haifa.agent.runtime.core.model.continuation;

import io.haifa.agent.model.api.SensitiveModelReasoning;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Objects;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/** Local AES-GCM protector. Deployments may replace it with a KMS-backed implementation. */
public final class AesGcmModelContinuationProtector implements ModelContinuationProtector {
    private final SecretKey key;
    private final SecureRandom random;

    public AesGcmModelContinuationProtector(SecretKey key, SecureRandom random) {
        this.key = Objects.requireNonNull(key, "key must not be null");
        this.random = Objects.requireNonNull(random, "random must not be null");
    }

    public static AesGcmModelContinuationProtector ephemeral() {
        try {
            KeyGenerator generator = KeyGenerator.getInstance("AES");
            generator.init(256);
            return new AesGcmModelContinuationProtector(generator.generateKey(), new SecureRandom());
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("AES-GCM is required", exception);
        }
    }

    @Override
    public ProtectedModelReasoning protect(SensitiveModelReasoning reasoning, String binding) {
        byte[] nonce = new byte[12];
        random.nextBytes(nonce);
        try {
            Cipher cipher = cipher(Cipher.ENCRYPT_MODE, nonce, binding);
            return new ProtectedModelReasoning(nonce, cipher.doFinal(reasoning.copyUtf8()));
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("model continuation protection failed", exception);
        }
    }

    @Override
    public SensitiveModelReasoning reveal(ProtectedModelReasoning payload, String binding) {
        try {
            Cipher cipher = cipher(Cipher.DECRYPT_MODE, payload.nonce(), binding);
            return SensitiveModelReasoning.fromUtf8(cipher.doFinal(payload.ciphertext()));
        } catch (GeneralSecurityException exception) {
            throw new ModelContinuationException(
                    ModelContinuationFailure.CORRUPT,
                    "model continuation payload failed integrity validation",
                    exception);
        }
    }

    private Cipher cipher(int mode, byte[] nonce, String binding) throws GeneralSecurityException {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(mode, key, new GCMParameterSpec(128, nonce));
        cipher.updateAAD(binding.getBytes(StandardCharsets.UTF_8));
        return cipher;
    }
}
