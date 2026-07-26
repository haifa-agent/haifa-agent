package io.haifa.agent.runtime.core.event;

import io.haifa.agent.core.run.AgentRunId;
import io.haifa.agent.runtime.api.RunEventCursor;
import io.haifa.agent.runtime.api.RuntimeContractException;
import io.haifa.agent.runtime.api.RuntimeErrorCode;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Base64;
import java.util.Objects;
import java.util.OptionalLong;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Versioned opaque cursor codec for remote adapters.
 *
 * <p>The signing key is application configuration and is never embedded in the cursor. The token
 * binds the run-feed discriminator, run id, feed contract version and exclusive sequence.
 */
public final class OpaqueRunEventCursorCodec {
    private static final String CODEC_VERSION = "c1";
    private static final String FEED_TYPE = "run";
    private static final String BEFORE_FIRST = "~";
    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder DECODER = Base64.getUrlDecoder();

    private final byte[] signingKey;

    public OpaqueRunEventCursorCodec(byte[] signingKey) {
        Objects.requireNonNull(signingKey, "signingKey must not be null");
        if (signingKey.length < 32) {
            throw new IllegalArgumentException("signingKey must contain at least 32 bytes");
        }
        this.signingKey = Arrays.copyOf(signingKey, signingKey.length);
    }

    public String encode(RunEventCursor cursor) {
        Objects.requireNonNull(cursor, "cursor must not be null");
        String sequence = cursor.exclusiveSequence().isPresent()
                ? Long.toString(cursor.exclusiveSequence().getAsLong())
                : BEFORE_FIRST;
        String encodedRunId = ENCODER.encodeToString(cursor.runId().value().getBytes(StandardCharsets.UTF_8));
        byte[] payload = String.join("|", CODEC_VERSION, FEED_TYPE, encodedRunId, cursor.feedVersion(), sequence)
                .getBytes(StandardCharsets.UTF_8);
        return ENCODER.encodeToString(payload) + "." + ENCODER.encodeToString(sign(payload));
    }

    public RunEventCursor decode(String token, AgentRunId expectedRunId, String expectedFeedVersion) {
        Objects.requireNonNull(expectedRunId, "expectedRunId must not be null");
        Objects.requireNonNull(expectedFeedVersion, "expectedFeedVersion must not be null");
        try {
            String[] tokenParts =
                    Objects.requireNonNull(token, "token must not be null").split("\\.", -1);
            if (tokenParts.length != 2) throw invalid();
            byte[] payload = DECODER.decode(tokenParts[0]);
            byte[] suppliedSignature = DECODER.decode(tokenParts[1]);
            if (!MessageDigest.isEqual(sign(payload), suppliedSignature)) throw invalid();

            String[] fields = new String(payload, StandardCharsets.UTF_8).split("\\|", -1);
            if (fields.length != 5 || !CODEC_VERSION.equals(fields[0]) || !FEED_TYPE.equals(fields[1])) {
                throw invalid();
            }
            AgentRunId runId = new AgentRunId(new String(DECODER.decode(fields[2]), StandardCharsets.UTF_8));
            if (!expectedRunId.equals(runId)) throw invalid();
            if (!expectedFeedVersion.equals(fields[3])) {
                throw new RuntimeContractException(
                        RuntimeErrorCode.CONTRACT_VERSION_UNSUPPORTED, "The Run Event Feed version is unsupported");
            }
            OptionalLong sequence =
                    BEFORE_FIRST.equals(fields[4]) ? OptionalLong.empty() : OptionalLong.of(Long.parseLong(fields[4]));
            return new RunEventCursor(runId, fields[3], sequence);
        } catch (RuntimeContractException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw invalid();
        }
    }

    private byte[] sign(byte[] payload) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(signingKey, "HmacSHA256"));
            return mac.doFinal(payload);
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("HMAC-SHA256 is unavailable", exception);
        }
    }

    private static RuntimeContractException invalid() {
        return new RuntimeContractException(RuntimeErrorCode.CURSOR_INVALID, "The Run Event cursor is invalid");
    }
}
