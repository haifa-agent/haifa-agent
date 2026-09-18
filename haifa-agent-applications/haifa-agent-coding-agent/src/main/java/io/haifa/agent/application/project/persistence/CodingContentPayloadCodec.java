package io.haifa.agent.application.project.persistence;

import io.haifa.agent.core.reference.AssetRef;
import io.haifa.agent.model.api.SensitiveModelReasoning;
import io.haifa.agent.runtime.core.model.continuation.ModelContinuationProtector;
import io.haifa.agent.runtime.core.model.continuation.ProtectedModelReasoning;
import io.haifa.agent.runtime.core.model.continuation.ProtectedModelReasoningEnvelope;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;

/** Strict, versioned and encrypted codec for undelivered Coding product input. */
final class CodingContentPayloadCodec {
    static final String SCHEMA_VERSION = "1";
    private static final int FORMAT_VERSION = 1;
    private static final int MAX_ATTACHMENTS = 20;

    private final ModelContinuationProtector protector;
    private final int maximumPayloadBytes;

    CodingContentPayloadCodec(ModelContinuationProtector protector, int maximumPayloadBytes) {
        this.protector = Objects.requireNonNull(protector, "protector must not be null");
        if (maximumPayloadBytes < 1 || maximumPayloadBytes > SensitiveModelReasoning.MAX_UTF8_BYTES) {
            throw new IllegalArgumentException("maximumPayloadBytes is outside the protected payload limit");
        }
        this.maximumPayloadBytes = maximumPayloadBytes;
    }

    ProtectedContent encode(String message, List<AssetRef> attachments, String binding) {
        byte[] clear = serialize(message, attachments);
        requireSize(clear);
        ProtectedModelReasoning protectedValue = protector.protect(SensitiveModelReasoning.fromUtf8(clear), binding);
        ProtectedModelReasoningEnvelope envelope = protectedValue.persistenceEnvelope();
        return new ProtectedContent(envelope.nonce(), envelope.ciphertext(), digest(clear));
    }

    Content decode(byte[] nonce, byte[] ciphertext, String expectedDigest, String binding) {
        SensitiveModelReasoning clear = protector.reveal(
                ProtectedModelReasoning.fromPersistenceEnvelope(new ProtectedModelReasoningEnvelope(nonce, ciphertext)),
                binding);
        byte[] bytes = clear.copyUtf8();
        requireSize(bytes);
        if (!digest(bytes).equals(expectedDigest)) {
            throw new IllegalStateException("Coding content digest does not match");
        }
        return deserialize(bytes);
    }

    static String binding(String kind, String identity, String requestDigest) {
        return "coding-content|" + kind + "|" + identity + "|" + requestDigest;
    }

    private byte[] serialize(String message, List<AssetRef> attachments) {
        try {
            var output = new ByteArrayOutputStream();
            try (var data = new DataOutputStream(output)) {
                data.writeInt(FORMAT_VERSION);
                write(data, message);
                data.writeInt(attachments.size());
                for (AssetRef attachment : attachments) {
                    write(data, attachment.assetId());
                    write(data, attachment.mimeType());
                    write(data, attachment.filename());
                }
            }
            return output.toByteArray();
        } catch (IOException impossible) {
            throw new IllegalStateException("Unable to encode Coding content", impossible);
        }
    }

    private Content deserialize(byte[] bytes) {
        try (var input = new DataInputStream(new ByteArrayInputStream(bytes))) {
            if (input.readInt() != FORMAT_VERSION) {
                throw new IllegalStateException("Unsupported Coding content payload version");
            }
            String message = read(input);
            int count = input.readInt();
            if (count < 0 || count > MAX_ATTACHMENTS) {
                throw new IllegalStateException("Coding content attachment count is invalid");
            }
            List<AssetRef> attachments = new ArrayList<>(count);
            for (int index = 0; index < count; index++) {
                attachments.add(new AssetRef(read(input), read(input), read(input)));
            }
            if (input.available() != 0) {
                throw new IllegalStateException("Coding content payload has trailing bytes");
            }
            return new Content(message, attachments);
        } catch (IOException | IllegalArgumentException exception) {
            throw new IllegalStateException("Unable to decode Coding content", exception);
        }
    }

    private static void write(DataOutputStream output, String value) throws IOException {
        byte[] bytes = Objects.requireNonNull(value, "value must not be null").getBytes(StandardCharsets.UTF_8);
        output.writeInt(bytes.length);
        output.write(bytes);
    }

    private String read(DataInputStream input) throws IOException {
        int length = input.readInt();
        if (length < 0 || length > maximumPayloadBytes) {
            throw new IllegalStateException("Coding content field length is invalid");
        }
        return new String(input.readNBytes(length), StandardCharsets.UTF_8);
    }

    private void requireSize(byte[] bytes) {
        if (bytes.length == 0 || bytes.length > maximumPayloadBytes) {
            throw new IllegalArgumentException("Coding content payload exceeds the configured limit");
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

    record ProtectedContent(byte[] nonce, byte[] ciphertext, String digest) {
        ProtectedContent {
            nonce = nonce.clone();
            ciphertext = ciphertext.clone();
            Objects.requireNonNull(digest, "digest must not be null");
        }
    }

    record Content(String message, List<AssetRef> attachments) {
        Content {
            attachments = List.copyOf(attachments);
        }
    }
}
