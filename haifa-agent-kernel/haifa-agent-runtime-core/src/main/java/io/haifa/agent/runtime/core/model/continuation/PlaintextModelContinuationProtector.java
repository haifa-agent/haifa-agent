package io.haifa.agent.runtime.core.model.continuation;

import io.haifa.agent.model.api.SensitiveModelReasoning;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.Objects;

/**
 * Persistent plaintext protector for trusted local profiles.
 *
 * <p>The payload remains readable at rest. The marker, binding digest and content digest detect accidental format,
 * binding or content mismatches; they do not provide confidentiality or protection against intentional tampering.
 */
public final class PlaintextModelContinuationProtector implements ModelContinuationProtector {
    private static final byte[] NONCE_MARKER = "HAIFA-NONE-1".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] PAYLOAD_MARKER = "haifa-plaintext-v1:".getBytes(StandardCharsets.US_ASCII);
    private static final int DIGEST_BYTES = 32;

    @Override
    public boolean providesConfidentiality() {
        return false;
    }

    @Override
    public ProtectedModelReasoning protect(SensitiveModelReasoning reasoning, String binding) {
        byte[] clear =
                Objects.requireNonNull(reasoning, "reasoning must not be null").copyUtf8();
        byte[] bindingDigest = digest(binding);
        byte[] contentDigest = digest(clear);
        byte[] payload = new byte[PAYLOAD_MARKER.length + bindingDigest.length + contentDigest.length + clear.length];
        System.arraycopy(PAYLOAD_MARKER, 0, payload, 0, PAYLOAD_MARKER.length);
        System.arraycopy(bindingDigest, 0, payload, PAYLOAD_MARKER.length, bindingDigest.length);
        System.arraycopy(contentDigest, 0, payload, PAYLOAD_MARKER.length + bindingDigest.length, contentDigest.length);
        System.arraycopy(
                clear, 0, payload, PAYLOAD_MARKER.length + bindingDigest.length + contentDigest.length, clear.length);
        return new ProtectedModelReasoning(NONCE_MARKER, payload);
    }

    @Override
    public SensitiveModelReasoning reveal(ProtectedModelReasoning payload, String binding) {
        Objects.requireNonNull(payload, "payload must not be null");
        byte[] protectedBytes = payload.ciphertext();
        int bindingOffset = PAYLOAD_MARKER.length;
        int digestOffset = bindingOffset + DIGEST_BYTES;
        int contentOffset = digestOffset + DIGEST_BYTES;
        if (!MessageDigest.isEqual(NONCE_MARKER, payload.nonce())
                || protectedBytes.length <= contentOffset
                || !startsWith(protectedBytes, PAYLOAD_MARKER)
                || !MessageDigest.isEqual(
                        digest(binding), Arrays.copyOfRange(protectedBytes, bindingOffset, digestOffset))
                || !MessageDigest.isEqual(
                        Arrays.copyOfRange(protectedBytes, digestOffset, contentOffset),
                        digest(Arrays.copyOfRange(protectedBytes, contentOffset, protectedBytes.length)))) {
            throw corrupt();
        }
        return SensitiveModelReasoning.fromUtf8(
                Arrays.copyOfRange(protectedBytes, contentOffset, protectedBytes.length));
    }

    private static boolean startsWith(byte[] value, byte[] prefix) {
        if (value.length < prefix.length) return false;
        return MessageDigest.isEqual(prefix, Arrays.copyOf(value, prefix.length));
    }

    private static byte[] digest(String binding) {
        return digest(
                Objects.requireNonNull(binding, "binding must not be null").getBytes(StandardCharsets.UTF_8));
    }

    private static byte[] digest(byte[] value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is required", exception);
        }
    }

    private static ModelContinuationException corrupt() {
        return new ModelContinuationException(
                ModelContinuationFailure.CORRUPT, "plaintext continuation payload failed format or binding validation");
    }
}
