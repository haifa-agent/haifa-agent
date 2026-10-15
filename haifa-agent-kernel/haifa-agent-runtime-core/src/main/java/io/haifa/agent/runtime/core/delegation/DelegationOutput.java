package io.haifa.agent.runtime.core.delegation;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
import java.util.Objects;

/** Bounded display metadata derived from an available, complete terminal output, never from its summary. */
final class DelegationOutput {
    private static final int PREVIEW_CODE_POINTS = 2_000;
    private static final String MARKER = "\n...\n";

    private DelegationOutput() {}

    static Map<String, Object> metadata(String output) {
        Objects.requireNonNull(output, "output must not be null");
        String digest;
        try {
            digest = HexFormat.of()
                    .formatHex(MessageDigest.getInstance("SHA-256").digest(output.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException unavailable) {
            throw new IllegalStateException("SHA-256 is unavailable", unavailable);
        }
        int length = output.codePointCount(0, output.length());
        boolean truncated = length > PREVIEW_CODE_POINTS;
        String preview = output;
        if (truncated) {
            int budget = PREVIEW_CODE_POINTS - MARKER.length();
            int head = budget * 2 / 3;
            int tail = budget - head;
            preview = output.substring(0, output.offsetByCodePoints(0, head))
                    + MARKER
                    + output.substring(output.offsetByCodePoints(output.length(), -tail));
        }
        return Map.of(
                ChildRunResults.OUTPUT_PREVIEW, preview,
                ChildRunResults.OUTPUT_SHA256, digest,
                ChildRunResults.OUTPUT_TRUNCATED, truncated);
    }
}
