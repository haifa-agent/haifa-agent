package io.haifa.agent.runtime.core.model.continuation;

import java.util.Objects;

/** Encrypted continuation bytes. Access is intentionally limited to the continuation protector. */
public final class ProtectedModelReasoning {
    private final byte[] nonce;
    private final byte[] ciphertext;

    public ProtectedModelReasoning(byte[] nonce, byte[] ciphertext) {
        this.nonce = Objects.requireNonNull(nonce, "nonce must not be null").clone();
        this.ciphertext = Objects.requireNonNull(ciphertext, "ciphertext must not be null")
                .clone();
        if (this.nonce.length != 12 || this.ciphertext.length < 17) {
            throw new IllegalArgumentException("protected reasoning payload is invalid");
        }
    }

    byte[] nonce() {
        return nonce.clone();
    }

    byte[] ciphertext() {
        return ciphertext.clone();
    }

    @Override
    public String toString() {
        return "ProtectedModelReasoning[bytes=" + ciphertext.length + "]";
    }
}
