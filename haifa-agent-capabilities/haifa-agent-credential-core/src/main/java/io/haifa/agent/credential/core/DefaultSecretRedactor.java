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
}
