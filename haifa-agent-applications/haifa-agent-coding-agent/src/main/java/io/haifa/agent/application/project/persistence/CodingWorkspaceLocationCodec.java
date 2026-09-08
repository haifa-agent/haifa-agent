package io.haifa.agent.application.project.persistence;

import io.haifa.agent.model.api.SensitiveModelReasoning;
import io.haifa.agent.runtime.core.model.continuation.ModelContinuationProtector;
import io.haifa.agent.runtime.core.model.continuation.ProtectedModelReasoning;
import io.haifa.agent.runtime.core.model.continuation.ProtectedModelReasoningEnvelope;
import java.nio.charset.StandardCharsets;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;

/** Protects the host-only physical location stored by the Coding workspace registry. */
final class CodingWorkspaceLocationCodec {
    private static final int MAXIMUM_LOCATION_BYTES = 16 * 1024;

    private final ModelContinuationProtector protector;

    CodingWorkspaceLocationCodec(ModelContinuationProtector protector) {
        this.protector = Objects.requireNonNull(protector, "protector must not be null");
    }

    ProtectedLocation encode(Path location, String binding) {
        byte[] clear = Objects.requireNonNull(location, "location must not be null")
                .toAbsolutePath()
                .normalize()
                .toString()
                .getBytes(StandardCharsets.UTF_8);
        requireSize(clear);
        ProtectedModelReasoning protectedValue = protector.protect(SensitiveModelReasoning.fromUtf8(clear), binding);
        ProtectedModelReasoningEnvelope envelope = protectedValue.persistenceEnvelope();
        return new ProtectedLocation(envelope.nonce(), envelope.ciphertext(), digest(clear));
    }

    Path decode(byte[] nonce, byte[] ciphertext, String expectedDigest, String binding) {
        SensitiveModelReasoning clear = protector.reveal(
                ProtectedModelReasoning.fromPersistenceEnvelope(new ProtectedModelReasoningEnvelope(nonce, ciphertext)),
                binding);
        byte[] bytes = clear.copyUtf8();
        requireSize(bytes);
        if (!digest(bytes).equals(expectedDigest)) {
            throw new IllegalStateException("Coding workspace location digest does not match");
        }
        try {
            Path value = Path.of(new String(bytes, StandardCharsets.UTF_8));
            if (!value.isAbsolute()) throw new IllegalStateException("Coding workspace location is not absolute");
            return value.normalize();
        } catch (InvalidPathException exception) {
            throw new IllegalStateException("Coding workspace location is invalid", exception);
        }
    }

    static String binding(String projectId, String workspaceRef, String locationRef, String physicalFingerprint) {
        return "coding-workspace-location|" + projectId + "|" + workspaceRef + "|" + locationRef + "|"
                + physicalFingerprint;
    }

    private static void requireSize(byte[] bytes) {
        if (bytes.length == 0 || bytes.length > MAXIMUM_LOCATION_BYTES) {
            throw new IllegalArgumentException("Coding workspace location exceeds the protected payload limit");
        }
    }

    private static String digest(byte[] value) {
        try {
            return "sha256:"
                    + HexFormat.of()
                            .formatHex(MessageDigest.getInstance("SHA-256").digest(value));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is required", exception);
        }
    }

    record ProtectedLocation(byte[] nonce, byte[] ciphertext, String digest) {
        ProtectedLocation {
            nonce = nonce.clone();
            ciphertext = ciphertext.clone();
            Objects.requireNonNull(digest, "digest must not be null");
        }
    }
}
