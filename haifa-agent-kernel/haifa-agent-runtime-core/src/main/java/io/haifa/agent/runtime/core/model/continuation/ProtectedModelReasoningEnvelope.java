package io.haifa.agent.runtime.core.model.continuation;

import java.util.Objects;

/** Persistence-safe encrypted envelope. It contains no key material or plaintext. */
public record ProtectedModelReasoningEnvelope(byte[] nonce, byte[] ciphertext) {
    public ProtectedModelReasoningEnvelope {
        nonce = Objects.requireNonNull(nonce, "nonce must not be null").clone();
        ciphertext = Objects.requireNonNull(ciphertext, "ciphertext must not be null")
                .clone();
    }

    @Override
    public byte[] nonce() {
        return nonce.clone();
    }

    @Override
    public byte[] ciphertext() {
        return ciphertext.clone();
    }
}
