package io.haifa.agent.execution.core.store;

import io.haifa.agent.core.reference.AssetRef;
import io.haifa.agent.execution.api.ExecutionId;
import io.haifa.agent.execution.api.ExecutionOutput;
import io.haifa.agent.execution.api.ExecutionOutputChannel;
import io.haifa.agent.execution.api.ExecutionOutputStore;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

public final class InMemoryExecutionOutputStore implements ExecutionOutputStore {
    private final ConcurrentHashMap<String, byte[]> values = new ConcurrentHashMap<>();

    @Override
    public ExecutionOutput store(
            ExecutionId id, ExecutionOutputChannel channel, byte[] bytes, int inlineSummaryBytes, boolean truncated) {
        byte[] copy = Arrays.copyOf(bytes, bytes.length);
        boolean binary = looksBinary(copy) || !validUtf8(copy);
        String digest = "sha256:" + hash(copy);
        AssetRef reference = null;
        if (copy.length > inlineSummaryBytes || binary || truncated) {
            String assetId = "execution:" + id.value() + ":" + channel.name().toLowerCase(java.util.Locale.ROOT);
            values.putIfAbsent(assetId, copy);
            reference = new AssetRef(
                    assetId,
                    binary ? "application/octet-stream" : "text/plain; charset=utf-8",
                    id.value() + "-" + channel.name().toLowerCase(java.util.Locale.ROOT) + (binary ? ".bin" : ".txt"));
        }
        String summary;
        if (binary) summary = "<binary output: " + copy.length + " bytes>";
        else {
            int length = Math.min(copy.length, inlineSummaryBytes);
            summary = new String(copy, 0, length, StandardCharsets.UTF_8);
            if (length < copy.length || truncated) summary += "\n<output truncated>";
        }
        return new ExecutionOutput(summary, reference, copy.length, digest, truncated, binary);
    }

    public Optional<byte[]> load(AssetRef reference) {
        return Optional.ofNullable(values.get(reference.assetId())).map(value -> Arrays.copyOf(value, value.length));
    }

    public Map<String, byte[]> snapshot() {
        return Map.copyOf(values);
    }

    private static boolean validUtf8(byte[] bytes) {
        try {
            StandardCharsets.UTF_8
                    .newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes));
            return true;
        } catch (java.nio.charset.CharacterCodingException exception) {
            return false;
        }
    }

    private static boolean looksBinary(byte[] bytes) {
        for (int index = 0; index < Math.min(bytes.length, 8192); index++) if (bytes[index] == 0) return true;
        return false;
    }

    private static String hash(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is required", exception);
        }
    }
}
