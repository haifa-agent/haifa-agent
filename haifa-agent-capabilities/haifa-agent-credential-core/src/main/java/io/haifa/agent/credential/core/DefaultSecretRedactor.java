package io.haifa.agent.credential.core;

import io.haifa.agent.credential.api.SecretRedactor;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

public final class DefaultSecretRedactor implements SecretRedactor {
    private static final String REDACTED = "[REDACTED]";
    private static final Pattern AUTHORIZATION =
            Pattern.compile("(?i)(bearer\\s+|basic\\s+|token\\s+)[A-Za-z0-9._~+/-]+=*", Pattern.MULTILINE);
    private static final Pattern KEY_VALUE = Pattern.compile(
            "(?i)((?:api[-_]?key|access[-_]?token|client[-_]?secret|password)\\s*[:=]\\s*)[^\\s,;]+",
            Pattern.MULTILINE);
    private static final Pattern URI_USER_INFO = Pattern.compile("(?i)(https?://)[^/@\\s]+@", Pattern.MULTILINE);

    private final Set<String> knownSecrets;

    public DefaultSecretRedactor() {
        this(List.of());
    }

    public DefaultSecretRedactor(Collection<String> secrets) {
        this.knownSecrets = Objects.requireNonNull(secrets, "secrets").stream()
                .filter(s -> s != null && !s.isBlank())
                .collect(Collectors.toUnmodifiableSet());
    }

    @Override
    public String redact(String text) {
        if (text == null || text.isBlank()) {
            return text;
        }
        String redacted = text;
        for (String secret : knownSecrets) {
            if (!secret.isBlank()) {
                redacted = redacted.replace(secret, REDACTED);
            }
        }
        redacted = AUTHORIZATION.matcher(redacted).replaceAll("$1" + REDACTED);
        redacted = KEY_VALUE.matcher(redacted).replaceAll("$1" + REDACTED);
        return URI_USER_INFO.matcher(redacted).replaceAll("$1" + REDACTED + "@");
    }
}
