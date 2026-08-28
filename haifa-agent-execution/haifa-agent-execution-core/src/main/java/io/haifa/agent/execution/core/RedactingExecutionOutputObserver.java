package io.haifa.agent.execution.core;

import io.haifa.agent.execution.api.ExecutionOutputChannel;
import io.haifa.agent.execution.api.ExecutionOutputObserver;
import io.haifa.agent.execution.api.ProcessOutputChunk;
import java.io.ByteArrayOutputStream;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Redacts exact environment values without leaking secrets split across output chunks. */
final class RedactingExecutionOutputObserver implements ExecutionOutputObserver {
    private static final byte[] REPLACEMENT = new byte[] {'*', '*', '*'};

    private final ExecutionOutputObserver delegate;
    private final List<byte[]> secrets;
    private final int maximumSecretLength;
    private final EnumMap<ExecutionOutputChannel, byte[]> pending = new EnumMap<>(ExecutionOutputChannel.class);

    RedactingExecutionOutputObserver(ExecutionOutputObserver delegate, Map<String, String> environment) {
        this.delegate = Objects.requireNonNull(delegate, "delegate must not be null");
        secrets = ExecutionOutputRedactionPolicy.redactionValues(environment);
        maximumSecretLength =
                secrets.stream().mapToInt(value -> value.length).max().orElse(1);
        for (ExecutionOutputChannel channel : ExecutionOutputChannel.values()) pending.put(channel, new byte[0]);
    }

    @Override
    public void onStarted() {
        delegate.onStarted();
    }

    @Override
    public void onStarted(io.haifa.agent.execution.api.ExecutionProcessIdentity identity) {
        delegate.onStarted(identity);
    }

    @Override
    public synchronized void onOutput(ProcessOutputChunk chunk) {
        byte[] previous = pending.get(chunk.channel());
        byte[] combined = new byte[previous.length + chunk.bytes().length];
        System.arraycopy(previous, 0, combined, 0, previous.length);
        System.arraycopy(chunk.bytes(), 0, combined, previous.length, chunk.bytes().length);
        int safeBoundary =
                chunk.endOfStream() ? combined.length : Math.max(0, combined.length - maximumSecretLength + 1);
        ByteArrayOutputStream safe = new ByteArrayOutputStream(combined.length);
        int index = 0;
        while (index < safeBoundary) {
            byte[] matched = match(combined, index);
            if (matched != null) {
                safe.writeBytes(REPLACEMENT);
                index += matched.length;
            } else {
                safe.write(combined[index++]);
            }
        }
        pending.put(chunk.channel(), Arrays.copyOfRange(combined, index, combined.length));
        if (chunk.endOfStream() && pending.get(chunk.channel()).length > 0) {
            byte[] remainder = pending.put(chunk.channel(), new byte[0]);
            safe.writeBytes(redactRemainder(remainder));
        }
        if (safe.size() > 0 || chunk.endOfStream()) {
            notifyDelegate(new ProcessOutputChunk(
                    chunk.channel(), safe.toByteArray(), chunk.endOfStream(), chunk.truncated()));
        }
    }

    private byte[] redactRemainder(byte[] source) {
        ByteArrayOutputStream safe = new ByteArrayOutputStream(source.length);
        int index = 0;
        while (index < source.length) {
            byte[] matched = match(source, index);
            if (matched != null) {
                safe.writeBytes(REPLACEMENT);
                index += matched.length;
            } else {
                safe.write(source[index++]);
            }
        }
        return safe.toByteArray();
    }

    private byte[] match(byte[] source, int index) {
        for (byte[] secret : secrets) {
            if (index + secret.length > source.length) continue;
            boolean matches = true;
            for (int offset = 0; offset < secret.length; offset++) {
                if (source[index + offset] != secret[offset]) {
                    matches = false;
                    break;
                }
            }
            if (matches) return secret;
        }
        return null;
    }

    private void notifyDelegate(ProcessOutputChunk chunk) {
        try {
            delegate.onOutput(chunk);
        } catch (RuntimeException ignored) {
            // Presentation failures cannot skip process cleanup or execution audit.
        }
    }
}
