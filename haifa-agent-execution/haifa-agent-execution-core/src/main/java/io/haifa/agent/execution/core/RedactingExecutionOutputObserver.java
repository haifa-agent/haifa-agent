package io.haifa.agent.execution.core;

import io.haifa.agent.execution.api.ExecutionOutputChannel;
import io.haifa.agent.execution.api.ExecutionOutputObserver;
import io.haifa.agent.execution.api.ProcessOutputChunk;
import io.haifa.agent.execution.api.ResolvedExecutionEnvironment;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Streaming observer that redacts URL userinfo and explicitly provided sensitive values across
 * chunk boundaries.
 */
final class RedactingExecutionOutputObserver implements ExecutionOutputObserver {
    private static final byte[] REPLACEMENT = new byte[] {'*', '*', '*'};
    private static final int URL_CARRYOVER_WINDOW = 256;
    private static final Pattern URI_USER_INFO = Pattern.compile("(?i)(https?://)[^/@\\s]+@");

    private final ExecutionOutputObserver delegate;
    private final List<byte[]> secrets;
    private final EnumMap<ExecutionOutputChannel, byte[]> pending = new EnumMap<>(ExecutionOutputChannel.class);

    RedactingExecutionOutputObserver(ExecutionOutputObserver delegate, List<byte[]> secrets) {
        this.delegate = Objects.requireNonNull(delegate, "delegate must not be null");
        this.secrets = sanitizeSecrets(secrets);
        for (ExecutionOutputChannel channel : ExecutionOutputChannel.values()) {
            pending.put(channel, new byte[0]);
        }
    }

    static List<byte[]> extractSecrets(ResolvedExecutionEnvironment environment) {
        if (environment == null || environment.sensitiveNames().isEmpty()) {
            return List.of();
        }
        var secrets = new ArrayList<byte[]>();
        for (String name : environment.sensitiveNames()) {
            String value = environment.values().get(name);
            if (value != null && !value.isEmpty()) {
                secrets.add(value.getBytes(StandardCharsets.UTF_8));
            }
        }
        return sanitizeSecrets(secrets);
    }

    private static List<byte[]> sanitizeSecrets(List<byte[]> secrets) {
        if (secrets == null || secrets.isEmpty()) {
            return List.of();
        }
        var list = new ArrayList<byte[]>();
        for (byte[] secret : secrets) {
            if (secret != null && secret.length > 0) {
                list.add(secret.clone());
            }
        }
        list.sort(Comparator.comparingInt((byte[] value) -> value.length).reversed());
        return List.copyOf(list);
    }

    static byte[] redactAll(byte[] source, List<byte[]> secrets) {
        if (source == null || source.length == 0) {
            return source;
        }
        byte[] safe = source.clone();
        for (byte[] secret : sanitizeSecrets(secrets)) {
            safe = replaceBytes(safe, secret, REPLACEMENT);
        }
        return redactUriUserInfo(safe);
    }

    static byte[] redactUriUserInfo(byte[] source) {
        String value = new String(source, StandardCharsets.UTF_8);
        String redacted = URI_USER_INFO.matcher(value).replaceAll("$1***@");
        return redacted.equals(value) ? source : redacted.getBytes(StandardCharsets.UTF_8);
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

        if (chunk.endOfStream()) {
            pending.put(chunk.channel(), new byte[0]);
            byte[] safe = redactBytes(combined);
            if (safe.length > 0 || chunk.endOfStream()) {
                notifyDelegate(new ProcessOutputChunk(chunk.channel(), safe, true, chunk.truncated()));
            }
            return;
        }

        int pendingLen = trailingPendingLength(combined);
        int safeLength = combined.length - pendingLen;
        if (safeLength > 0) {
            byte[] safePart = Arrays.copyOfRange(combined, 0, safeLength);
            byte[] remainder = Arrays.copyOfRange(combined, safeLength, combined.length);
            pending.put(chunk.channel(), remainder);
            byte[] safe = redactBytes(safePart);
            if (safe.length > 0) {
                notifyDelegate(new ProcessOutputChunk(chunk.channel(), safe, false, chunk.truncated()));
            }
        } else {
            pending.put(chunk.channel(), combined);
        }
    }

    public synchronized void flush() {
        for (ExecutionOutputChannel channel : ExecutionOutputChannel.values()) {
            byte[] remainder = pending.get(channel);
            if (remainder != null && remainder.length > 0) {
                pending.put(channel, new byte[0]);
                byte[] safe = redactBytes(remainder);
                if (safe.length > 0) {
                    notifyDelegate(new ProcessOutputChunk(channel, safe, false, false));
                }
            }
        }
    }

    private int trailingPendingLength(byte[] combined) {
        int pendingLen = 0;
        for (byte[] secret : secrets) {
            int maxK = Math.min(secret.length - 1, combined.length);
            for (int k = maxK; k > pendingLen; k--) {
                if (matchesPrefix(combined, combined.length - k, secret, k)) {
                    pendingLen = k;
                    break;
                }
            }
        }
        int openUrl = findOpenUrlPrefix(combined);
        if (openUrl >= 0) {
            int urlPending = combined.length - openUrl;
            if (urlPending <= URL_CARRYOVER_WINDOW) {
                pendingLen = Math.max(pendingLen, urlPending);
            }
        }
        return pendingLen;
    }

    private static boolean matchesPrefix(byte[] source, int sourceOffset, byte[] target, int length) {
        for (int i = 0; i < length; i++) {
            if (source[sourceOffset + i] != target[i]) {
                return false;
            }
        }
        return true;
    }

    private static int findOpenUrlPrefix(byte[] source) {
        int startScan = Math.max(0, source.length - URL_CARRYOVER_WINDOW);
        for (int i = source.length - 7; i >= startScan; i--) {
            if (isHttpOrHttps(source, i)) {
                boolean hasDelimiter = false;
                for (int j = i; j < source.length; j++) {
                    byte b = source[j];
                    if (b == '@' || b <= ' ' || b == '"' || b == '\'' || b == '}' || b == '>') {
                        hasDelimiter = true;
                        break;
                    }
                }
                if (!hasDelimiter) {
                    return i;
                }
            }
        }
        return -1;
    }

    private static boolean isHttpOrHttps(byte[] source, int offset) {
        if (offset < 0) return false;
        if (offset + 7 <= source.length
                && (source[offset] == 'h' || source[offset] == 'H')
                && (source[offset + 1] == 't' || source[offset + 1] == 'T')
                && (source[offset + 2] == 't' || source[offset + 2] == 'T')
                && (source[offset + 3] == 'p' || source[offset + 3] == 'P')) {
            if (source[offset + 4] == ':' && source[offset + 5] == '/' && source[offset + 6] == '/') {
                return true;
            }
            if (offset + 8 <= source.length
                    && (source[offset + 4] == 's' || source[offset + 4] == 'S')
                    && source[offset + 5] == ':'
                    && source[offset + 6] == '/'
                    && source[offset + 7] == '/') {
                return true;
            }
        }
        return false;
    }

    private byte[] redactBytes(byte[] source) {
        byte[] result = source;
        for (byte[] secret : secrets) {
            result = replaceBytes(result, secret, REPLACEMENT);
        }
        return redactUriUserInfo(result);
    }

    private static byte[] replaceBytes(byte[] source, byte[] target, byte[] replacement) {
        if (target.length == 0 || source.length < target.length) return source;
        ByteArrayOutputStream output = new ByteArrayOutputStream(source.length);
        int index = 0;
        while (index <= source.length - target.length) {
            boolean match = true;
            for (int offset = 0; offset < target.length; offset++) {
                if (source[index + offset] != target[offset]) {
                    match = false;
                    break;
                }
            }
            if (match) {
                output.writeBytes(replacement);
                index += target.length;
            } else {
                output.write(source[index++]);
            }
        }
        output.write(source, index, source.length - index);
        return output.toByteArray();
    }

    private void notifyDelegate(ProcessOutputChunk chunk) {
        try {
            delegate.onOutput(chunk);
        } catch (RuntimeException ignored) {
            // Presentation failures cannot skip process cleanup or execution audit.
        }
    }
}
