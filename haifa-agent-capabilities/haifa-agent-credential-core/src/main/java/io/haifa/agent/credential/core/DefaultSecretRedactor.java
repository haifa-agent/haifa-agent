package io.haifa.agent.credential.core;

import io.haifa.agent.credential.api.SecretRedactor;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;

public final class DefaultSecretRedactor implements SecretRedactor {
    private static final String REDACTED = "[REDACTED]";
    private static final Pattern AUTHORIZATION =
            Pattern.compile("(?i)(bearer\\s+|basic\\s+|token\\s+)[A-Za-z0-9._~+/-]+=*", Pattern.MULTILINE);
    private static final Pattern KEY_VALUE = Pattern.compile(
            "(?i)((?:api[-_]?key|access[-_]?token|client[-_]?secret|password)\\s*[:=]\\s*)[^\\s,;]+",
            Pattern.MULTILINE);
    private static final Pattern URI_USER_INFO = Pattern.compile("(?i)(https?://)[^/@\\s]+@", Pattern.MULTILINE);

    private final Set<String> persistentSecrets = ConcurrentHashMap.newKeySet();
    private final ConcurrentHashMap<String, Integer> scopedSecrets = new ConcurrentHashMap<>();

    public DefaultSecretRedactor() {
        this(List.of());
    }

    public DefaultSecretRedactor(Collection<String> secrets) {
        Objects.requireNonNull(secrets, "secrets").stream()
                .filter(s -> s != null && !s.isBlank())
                .forEach(this.persistentSecrets::add);
    }

    @Override
    public AutoCloseable registerScoped(String secret) {
        if (secret == null || secret.isBlank()) {
            return () -> {};
        }
        scopedSecrets.compute(secret, (k, count) -> count == null ? 1 : count + 1);
        AtomicBoolean closed = new AtomicBoolean(false);
        return () -> {
            if (closed.compareAndSet(false, true)) {
                scopedSecrets.compute(secret, (k, count) -> (count == null || count <= 1) ? null : count - 1);
            }
        };
    }

    @Override
    public AutoCloseable registerScoped(Collection<String> secrets) {
        if (secrets == null || secrets.isEmpty()) {
            return () -> {};
        }
        List<String> validSecrets = secrets.stream()
                .filter(s -> s != null && !s.isBlank())
                .distinct()
                .toList();
        if (validSecrets.isEmpty()) {
            return () -> {};
        }
        for (String secret : validSecrets) {
            scopedSecrets.compute(secret, (k, count) -> count == null ? 1 : count + 1);
        }
        AtomicBoolean closed = new AtomicBoolean(false);
        return () -> {
            if (closed.compareAndSet(false, true)) {
                for (String secret : validSecrets) {
                    scopedSecrets.compute(secret, (k, count) -> (count == null || count <= 1) ? null : count - 1);
                }
            }
        };
    }

    public void registerSecret(String secret) {
        if (secret != null && !secret.isBlank()) {
            persistentSecrets.add(secret);
        }
    }

    @Override
    public String redact(String text) {
        if (text == null || text.isBlank()) {
            return text;
        }
        String redacted = text;
        for (String secret : persistentSecrets) {
            if (!secret.isBlank()) {
                redacted = redacted.replace(secret, REDACTED);
            }
        }
        for (String secret : scopedSecrets.keySet()) {
            if (!secret.isBlank()) {
                redacted = redacted.replace(secret, REDACTED);
            }
        }
        redacted = AUTHORIZATION.matcher(redacted).replaceAll("$1" + REDACTED);
        redacted = KEY_VALUE.matcher(redacted).replaceAll("$1" + REDACTED);
        return URI_USER_INFO.matcher(redacted).replaceAll("$1" + REDACTED + "@");
    }

    /**
     * Opens a thread-confined fragment redactor. Ambiguous prefixes/unterminated credential values are
     * withheld until a later fragment proves their boundary; exceeding the explicit carry budget fails closed.
     * Consumers may discard an unfinished tail when their best-effort observation ends.
     */
    public Stream openStream(int maximumPendingCharacters) {
        if (maximumPendingCharacters < 1) throw new IllegalArgumentException("stream budget must be positive");
        return new Stream(maximumPendingCharacters);
    }

    public final class Stream {
        private final int maximumPendingCharacters;
        private final StringBuilder pending = new StringBuilder();
        private boolean failed;

        private Stream(int maximumPendingCharacters) {
            this.maximumPendingCharacters = maximumPendingCharacters;
        }

        /** Returns only text that cannot become part of a credential when another fragment arrives. */
        public String accept(String fragment) {
            Objects.requireNonNull(fragment, "fragment must not be null");
            if (failed) throw new IllegalStateException("Secret redaction stream is closed");
            pending.append(fragment);
            int safeEnd = pending.length();
            int[] maskedUntil = new int[pending.length()];
            for (String secret : persistentSecrets) markLiteral(secret, maskedUntil);
            for (String secret : scopedSecrets.keySet()) markLiteral(secret, maskedUntil);
            for (int offset = 0; offset < safeEnd; offset++) {
                int remaining = pending.length() - offset;
                for (String secret : persistentSecrets) {
                    if (literalPrefix(secret, offset, remaining)) {
                        safeEnd = offset;
                        break;
                    }
                }
                for (String secret : scopedSecrets.keySet()) {
                    if (literalPrefix(secret, offset, remaining)) {
                        safeEnd = offset;
                        break;
                    }
                }
            }
            // Respect the same replacement order as redact(): a prefix inside an already masked value
            // cannot start another credential, while a higher-priority unfinished pattern stays pending.
            for (var pattern : List.of(AUTHORIZATION, KEY_VALUE, URI_USER_INFO)) {
                var matcher = pattern.matcher(pending);
                for (int offset = 0; offset < pending.length(); offset++) {
                    if (maskedUntil[offset] > offset) {
                        offset = maskedUntil[offset] - 1;
                        continue;
                    }
                    matcher.region(offset, pending.length());
                    boolean matched = matcher.lookingAt();
                    if (matcher.hitEnd()) {
                        safeEnd = Math.min(safeEnd, offset);
                        break;
                    }
                    if (matched) {
                        markRange(maskedUntil, offset, matcher.end());
                        offset = matcher.end() - 1;
                    }
                }
            }
            safeEnd = protectOverlappingMatches(safeEnd);
            if (pending.length() - safeEnd > maximumPendingCharacters) {
                pending.setLength(0);
                failed = true;
                throw new IllegalStateException("Secret redaction fragment budget exceeded");
            }
            String safe = redact(pending.substring(0, safeEnd));
            pending.delete(0, safeEnd);
            return safe;
        }

        private void markLiteral(String secret, int[] maskedUntil) {
            for (int start = pending.indexOf(secret); start >= 0; start = pending.indexOf(secret, start + 1)) {
                markRange(maskedUntil, start, start + secret.length());
            }
        }

        private void markRange(int[] maskedUntil, int start, int end) {
            for (int i = start; i < end; i++) maskedUntil[i] = Math.max(maskedUntil[i], end);
        }

        private int protectOverlappingMatches(int end) {
            int previous;
            do {
                previous = end;
                for (var pattern : List.of(AUTHORIZATION, KEY_VALUE, URI_USER_INFO)) {
                    var matcher = pattern.matcher(pending);
                    while (matcher.find() && matcher.start() < end) {
                        if (matcher.end() > end) {
                            end = matcher.start();
                            break;
                        }
                    }
                }
                for (String secret : persistentSecrets) end = protectLiteral(secret, end);
                for (String secret : scopedSecrets.keySet()) end = protectLiteral(secret, end);
            } while (end > 0 && end != previous);
            return end;
        }

        private int protectLiteral(String secret, int end) {
            if (end == 0) return 0;
            int start = pending.lastIndexOf(secret, end - 1);
            return start >= 0 && start + secret.length() > end ? start : end;
        }

        private boolean literalPrefix(String secret, int offset, int remaining) {
            if (remaining >= secret.length()) return false;
            for (int i = 0; i < remaining; i++) {
                if (pending.charAt(offset + i) != secret.charAt(i)) return false;
            }
            return true;
        }

        /** Allows observation owners to release empty stream state between tools. */
        public boolean awaitingMoreText() {
            return pending.length() != 0;
        }
    }
}
